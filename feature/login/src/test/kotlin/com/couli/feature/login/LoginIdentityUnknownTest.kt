package com.couli.feature.login

import com.couli.core.network.ApiClient
import com.couli.core.network.ClientIdentity
import com.couli.core.network.DeviceCredentials
import com.couli.core.network.HeaderNames
import com.couli.core.network.MemoryPendingKeyBackend
import com.couli.core.network.MemorySessionTokens
import com.couli.core.network.NetworkContext
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PrivacyRaw
import com.couli.core.network.RetryPolicy
import com.couli.core.network.SessionRead
import com.couli.core.network.SessionReader
import com.couli.core.network.SessionScopeRaw
import com.couli.core.network.SessionSnapshot
import com.couli.core.network.SessionTokens
import com.couli.core.network.UpdateRaw
import java.util.Collections
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * FU-AND-STEPUP-SUMMARY 推送前补单（独立写手）：03 §4.2「可选登录接口的发送口径」第二条在登录相关真实调用点上的落地——登录与绑手机的发码
 * （purpose=login / bind）、第三方登录取授权尝试（purpose=login）是登录要用到的请求，「不能被读不出的旧会话挡住」：身份未知（会话读不出，
 * 或读得出却只有 refresh 没有 access）时照发、不带 Authorization。
 *
 * 当前代码上这些用例预期已经是绿的（optional 读不出照发）；实现「身份未知默认本地拒绝」之后，它们钉住调用点必须按请求体显式声明照发。
 * 真实 [LoginService] / [BindPhoneService] / [OAuthLoginService] + 真实 [ApiClient] → MockWebServer。
 */
class LoginIdentityUnknownTest {
    private lateinit var server: MockWebServer
    private val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())
    private val now = 1_700_000_000_000L

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    val data =
                        when (request.path) {
                            ATTEMPTS -> """{"attempt_id":"attempt-1","nonce":"nonce-1","expire_at":"2099-01-01T00:00:00+08:00"}"""
                            SMS -> """{"resend_after_sec":60,"expires_in_sec":300}"""
                            else -> "{}"
                        }
                    return MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"code":0,"msg":"","data":$data,"trace_id":"trace-000001"}""")
                }
            }
        server.start()
    }

    @After
    fun stop() = server.shutdown()

    // ---------------------------------------------------------------- 登录发码（purpose=login）

    @Test
    fun unreadableSessionStillSendsTheLoginCodeWithoutToken() =
        runBlocking {
            val tokens = unreadable()
            val outcome = login(tokens).sendCode(PHONE)

            assertEquals(SmsCountdown.STATE_SENT, outcome.stateRaw)
            assertSentOnceWithoutToken(SMS, purpose = "login")
        }

    @Test
    fun refreshOnlySessionStillSendsTheLoginCodeWithoutToken() =
        runBlocking {
            val tokens = refreshOnly()
            val outcome = login(tokens).sendCode(PHONE)

            assertEquals(SmsCountdown.STATE_SENT, outcome.stateRaw)
            assertSentOnceWithoutToken(SMS, purpose = "login")
        }

    // ---------------------------------------------------------------- 绑手机发码（purpose=bind）

    @Test
    fun unreadableSessionStillSendsTheBindCodeWithoutToken() =
        runBlocking {
            val tokens = unreadable()
            val api = client(tokens)
            val bind = BindPhoneService(api, tokens, login(tokens, api), PhoneBindingState(), NoHost) { now }

            bind.sendCode(PHONE)

            assertSentOnceWithoutToken(SMS, purpose = "bind")
        }

    @Test
    fun refreshOnlySessionStillSendsTheBindCodeWithoutToken() =
        runBlocking {
            val tokens = refreshOnly()
            val api = client(tokens)
            val bind = BindPhoneService(api, tokens, login(tokens, api), PhoneBindingState(), NoHost) { now }

            bind.sendCode(PHONE)

            assertSentOnceWithoutToken(SMS, purpose = "bind")
        }

    // ---------------------------------------------------------------- 第三方登录取授权尝试（purpose=login）

    @Test
    fun unreadableSessionStillCreatesTheLoginOauthAttemptWithoutToken() =
        runBlocking {
            val tokens = unreadable()
            val api = client(tokens)
            val oauth = OAuthLoginService(api, tokens, login(tokens, api), listOf(CancellingAdapter), PhoneBindingState())

            oauth.loginWith(OAuthProvider.WECHAT, now)

            assertSentOnceWithoutToken(ATTEMPTS, purpose = "login")
        }

    @Test
    fun refreshOnlySessionStillCreatesTheLoginOauthAttemptWithoutToken() =
        runBlocking {
            val tokens = refreshOnly()
            val api = client(tokens)
            val oauth = OAuthLoginService(api, tokens, login(tokens, api), listOf(CancellingAdapter), PhoneBindingState())

            oauth.loginWith(OAuthProvider.WECHAT, now)

            assertSentOnceWithoutToken(ATTEMPTS, purpose = "login")
        }

    // ---------------------------------------------------------------- 假件

    private fun assertSentOnceWithoutToken(
        path: String,
        purpose: String,
    ) {
        val sent = requests.filter { it.path == path }
        assertEquals("$path 发出 1 次", 1, sent.size)
        assertNull("身份未知照发时不带 Authorization", sent.single().getHeader(HeaderNames.AUTHORIZATION))
        val body = Json.parseToJsonElement(sent.single().body.readUtf8()).jsonObject
        assertEquals(purpose, body["purpose"]?.jsonPrimitive?.content)
    }

    private fun login(
        tokens: SessionTokens,
        api: ApiClient = client(tokens),
    ) = LoginService(api, tokens, NoHost, LegalVersions(1, 1), pendingInvite = PendingInviteCode()) { now }

    private fun client(tokens: SessionTokens) =
        ApiClient(
            identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.0.0", build = "1", channel = "official"),
            baseUrl = { server.url("/").toString() },
            context = { NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, UpdateRaw.SUPPORTED) },
            device = FixtureDevice,
            tokens = tokens,
            pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
            sleeper = {},
            retryPolicy = { RetryPolicy.forOperation(it).copy(timeoutMillis = LOCAL_BUDGET_MS) },
        )

    private fun unreadable() =
        ControlledTokens(MemorySessionTokens("access-A", "refresh-A", SessionScopeRaw.FULL, "account-A")).also { it.unreadable = true }

    /** 读得出、有 userId，但只有 refresh_token 没有 access_token。 */
    private fun refreshOnly() = ControlledTokens(MemorySessionTokens(null, "refresh-A", SessionScopeRaw.FULL, "account-A"))

    /** 可控的会话存储（同 KeystoreSessionTokens 的 [SessionReader]）：[unreadable] 时读不出。 */
    private class ControlledTokens(
        val inner: MemorySessionTokens,
    ) : SessionTokens by inner,
        SessionReader {
        @Volatile var unreadable = false

        override fun readSession(): SessionRead =
            if (unreadable) SessionRead.Unreadable(inner.generation) else SessionRead.Present(inner.snapshot())

        override fun snapshot(): SessionSnapshot =
            if (unreadable) SessionSnapshot(null, null, null, null, inner.generation) else inner.snapshot()

        override val accessToken: String? get() = snapshot().accessToken
        override val refreshToken: String? get() = snapshot().refreshToken
        override val scope: String? get() = snapshot().scope
        override val userId: String? get() = snapshot().userId
    }

    /** 取到授权尝试之后，第三方授权立即取消（本文件只看取授权尝试这一个请求）。 */
    private object CancellingAdapter : ThirdPartyAuthAdapter {
        override val provider: String = OAuthProvider.WECHAT

        override fun adapterAvailable(): Boolean = true

        override fun installed(): Boolean? = null

        override suspend fun authorize(nonce: String): ThirdPartyAuthResult = ThirdPartyAuthResult.of(ThirdPartyAuthKind.CANCELLED)
    }

    private object NoHost : LoginHost, BindPhoneHost {
        override fun phoneBound(owner: BindPhoneOwner) = Unit

        override fun updateState() = AfterLoginUpdate.SUPPORTED

        override fun hasPendingAction() = false

        override fun closeLogin() = Unit

        override fun resumePending() = Unit

        override fun discardPending() = Unit

        override fun enterForceUpdate() = Unit

        override fun sessionChanged() = Unit

        override fun sessionSynced() = Unit

        override fun sessionEnded() = Unit
    }

    private object FixtureDevice : DeviceCredentials {
        override val deviceId: String = "device-fixture"
        override val installSecret: String = "install-fixture"
    }

    private companion object {
        const val PHONE = "13800138000"
        const val SMS = "/v1/auth/sms-codes"
        const val ATTEMPTS = "/v1/auth/oauth-attempts"
        const val LOCAL_BUDGET_MS = 30_000L
    }
}
