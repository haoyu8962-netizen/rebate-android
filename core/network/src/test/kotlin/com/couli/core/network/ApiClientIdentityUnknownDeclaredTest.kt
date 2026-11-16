package com.couli.core.network

import java.util.Collections
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * FU-AND-STEPUP-SUMMARY 推送前补单（独立写手，先红）：03 §4.2「可选登录接口的发送口径」第一、二条里「显式声明身份未知也照发」的五类。
 * 依赖新开关 [ApiCall.sendsWhenIdentityUnknown]（Boolean，缺省 false）：实现落地之前本文件编译不过（预期）。
 *
 * 五类（按请求体条件精确打开）：登录 / 绑手机发码（sendSmsCode，purpose=login / bind）；第三方登录取授权尝试（createOauthAttempt，purpose=login）；
 * GET /v1/config；POST /v1/events；隐私中心撤回隐私同意（recordConsent，type=privacy 且 accepted=false）。照发时不带 Authorization。
 * 身份未知的两种来源各一条：会话读不出（[SessionRead.Unreadable]）；读得出、有 userId 但只有 refresh 没有 access。
 */
class ApiClientIdentityUnknownDeclaredTest {
    private lateinit var server: MockWebServer
    private val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    return MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"code":0,"msg":"","data":{},"trace_id":"$TRACE"}""")
                }
            }
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    // ---------------------------------------------------------------- 登录 / 绑手机发码

    @Test
    fun unreadableSessionStillSendsTheDeclaredLoginCodeWithoutToken() {
        assertSentOnceWithoutToken(client(unreadable()).execute(declared("sendSmsCode", body = LOGIN_CODE)), "/v1/auth/sms-codes")
    }

    @Test
    fun refreshOnlySessionStillSendsTheDeclaredLoginCodeWithoutToken() {
        assertSentOnceWithoutToken(client(refreshOnly()).execute(declared("sendSmsCode", body = LOGIN_CODE)), "/v1/auth/sms-codes")
    }

    @Test
    fun unreadableSessionStillSendsTheDeclaredBindCodeWithoutToken() {
        assertSentOnceWithoutToken(client(unreadable()).execute(declared("sendSmsCode", body = BIND_CODE)), "/v1/auth/sms-codes")
    }

    @Test
    fun refreshOnlySessionStillSendsTheDeclaredBindCodeWithoutToken() {
        assertSentOnceWithoutToken(client(refreshOnly()).execute(declared("sendSmsCode", body = BIND_CODE)), "/v1/auth/sms-codes")
    }

    // ---------------------------------------------------------------- 第三方登录取授权尝试

    @Test
    fun unreadableSessionStillCreatesTheDeclaredLoginOauthAttemptWithoutToken() {
        val result = client(unreadable()).execute(declared("createOauthAttempt", body = OAUTH_LOGIN))
        assertSentOnceWithoutToken(result, "/v1/auth/oauth-attempts")
    }

    @Test
    fun refreshOnlySessionStillCreatesTheDeclaredLoginOauthAttemptWithoutToken() {
        val result = client(refreshOnly()).execute(declared("createOauthAttempt", body = OAUTH_LOGIN))
        assertSentOnceWithoutToken(result, "/v1/auth/oauth-attempts")
    }

    // ---------------------------------------------------------------- 取配置、事件上报

    @Test
    fun unreadableSessionStillFetchesTheDeclaredConfigWithoutToken() {
        assertSentOnceWithoutToken(client(unreadable()).execute(declared("getConfig")), "/v1/config")
    }

    @Test
    fun refreshOnlySessionStillFetchesTheDeclaredConfigWithoutToken() {
        assertSentOnceWithoutToken(client(refreshOnly()).execute(declared("getConfig")), "/v1/config")
    }

    @Test
    fun unreadableSessionStillSendsTheDeclaredEventsWithoutToken() {
        assertSentOnceWithoutToken(client(unreadable()).execute(declared("ingestEvents", body = EVENTS)), "/v1/events")
    }

    @Test
    fun refreshOnlySessionStillSendsTheDeclaredEventsWithoutToken() {
        assertSentOnceWithoutToken(client(refreshOnly()).execute(declared("ingestEvents", body = EVENTS)), "/v1/events")
    }

    // ---------------------------------------------------------------- 隐私中心撤回隐私同意（设备为主体）

    @Test
    fun unreadableSessionStillSendsTheDeclaredPrivacyWithdrawalWithoutTokenAsTheDevice() {
        val result = client(unreadable()).execute(declared("recordConsent", body = PRIVACY_WITHDRAWAL))

        assertSentOnceWithoutToken(result, "/v1/consents")
        assertEquals("设备级记录照旧带设备标识", DEVICE_ID, requests.single().getHeader(HeaderNames.DEVICE_ID))
    }

    @Test
    fun refreshOnlySessionStillSendsTheDeclaredPrivacyWithdrawalWithoutTokenAsTheDevice() {
        val result = client(refreshOnly()).execute(declared("recordConsent", body = PRIVACY_WITHDRAWAL))

        assertSentOnceWithoutToken(result, "/v1/consents")
        assertEquals(DEVICE_ID, requests.single().getHeader(HeaderNames.DEVICE_ID))
    }

    // ---------------------------------------------------------------- 签名接口照旧签名；声明不改变「有令牌就带」「游客不带」

    @Test
    fun theDeclaredLoginCodeIsStillSignedWithTheDeviceCredentials() {
        val result = client(unreadable()).execute(declared("sendSmsCode", body = LOGIN_CODE))

        assertSentOnceWithoutToken(result, "/v1/auth/sms-codes")
        val sent = requests.single()
        assertEquals(DEVICE_ID, sent.getHeader(HeaderNames.DEVICE_ID))
        assertNotNull("x-signed 接口照旧签名", sent.getHeader(HeaderNames.SIGN))
    }

    @Test
    fun aDeclaredRequestStillCarriesTheTokenWhenTheSessionIsReadable() {
        val tokens = ControlledTokens(MemorySessionTokens(ACCESS_A, REFRESH_A, SessionScopeRaw.FULL, ACCOUNT_A))
        val result = client(tokens).execute(declared("sendSmsCode", body = BIND_CODE))

        assertTrue(result.toString(), result is ApiResult.Success)
        assertEquals("有令牌：带上发", "Bearer $ACCESS_A", requests.single().getHeader(HeaderNames.AUTHORIZATION))
    }

    @Test
    fun aDeclaredRequestFromAConfirmedGuestGoesOutAnonymously() {
        assertSentOnceWithoutToken(client(MemorySessionTokens()).execute(declared("getConfig")), "/v1/config")
    }

    @Test
    fun theSwitchIsOffByDefault() {
        assertEquals(false, ApiCall("getConfig").sendsWhenIdentityUnknown)
    }

    // ---------------------------------------------------------------- 假件

    private fun declared(
        operationId: String,
        body: String? = null,
    ) = ApiCall(operationId, body = body, sendsWhenIdentityUnknown = true)

    private fun assertSentOnceWithoutToken(
        result: ApiResult,
        path: String,
    ) {
        assertTrue(result.toString(), result is ApiResult.Success)
        assertEquals("发出 1 次", 1, server.requestCount)
        val sent = requests.single()
        assertEquals(path, sent.path)
        assertNull("身份未知照发时不带 Authorization", sent.getHeader(HeaderNames.AUTHORIZATION))
    }

    private fun client(tokens: SessionTokens) =
        ApiClient(
            identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.2.0", build = "1", channel = "official"),
            baseUrl = { server.url("/").toString() },
            context = { FULL },
            device = FixedDevice,
            tokens = tokens,
            pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
            http = OkHttpClient(),
            sleeper = {},
            retryPolicy = { RetryPolicy.forOperation(it).copy(timeoutMillis = LOCAL_BUDGET_MS) },
        )

    private fun unreadable() =
        ControlledTokens(MemorySessionTokens(ACCESS_A, REFRESH_A, SessionScopeRaw.FULL, ACCOUNT_A)).also { it.unreadable = true }

    private fun refreshOnly() = ControlledTokens(MemorySessionTokens(null, REFRESH_A, SessionScopeRaw.FULL, ACCOUNT_A))

    private object FixedDevice : DeviceCredentials {
        override val deviceId: String = DEVICE_ID
        override val installSecret: String = "test-only.install.device-a"
    }

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

    private companion object {
        const val TRACE = "0199a3b4-5c6d-7e8f-9a0b-1c2d3e4f5a6b"
        const val DEVICE_ID = "device-fixture"
        const val ACCOUNT_A = "account-A"
        const val ACCESS_A = "access-A"
        const val REFRESH_A = "refresh-A"
        const val LOGIN_CODE = """{"phone":"13800005678","purpose":"login"}"""
        const val BIND_CODE = """{"phone":"13800005678","purpose":"bind"}"""
        const val OAUTH_LOGIN = """{"provider":"wechat","purpose":"login"}"""
        const val EVENTS = """{"events":[{"event":"app_open","client_at":"2026-11-16T10:00:00+08:00"}]}"""
        const val PRIVACY_WITHDRAWAL =
            """{"type":"privacy","version":3,"accepted":false,"channel":"privacy_center","client_at":"2026-11-16T10:00:00+08:00"}"""
        const val LOCAL_BUDGET_MS = 30_000L
        val FULL = NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, update = UpdateRaw.SUPPORTED)
    }
}
