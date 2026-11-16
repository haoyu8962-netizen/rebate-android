package com.couli.app.clipboard

import com.couli.core.clipboard.ParseInputService
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiClient
import com.couli.core.network.ApiClientPageApi
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.ClientIdentity
import com.couli.core.network.DeviceCredentials
import com.couli.core.network.HeaderNames
import com.couli.core.network.LocalRejectReason
import com.couli.core.network.MemoryPendingKeyBackend
import com.couli.core.network.MemorySessionTokens
import com.couli.core.network.NetworkContext
import com.couli.core.network.PageApi
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
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * FU-AND-STEPUP-SUMMARY 推送前补单（独立写手，先红）：03 §4.2「可选登录接口的发送口径」第一、二条在解析输入（parseInput）真实调用点
 * [ParseInputService] 上的落地。放在 app 模块：core/clipboard 的测试依赖里没有 MockWebServer（本单不改构建文件），app 依赖它与 core/network。
 *
 * - 身份未知（会话读不出；或读得出、有 userId 但只有 refresh 没有 access）：parseInput 不声明，本地拒绝 session_unreadable、传输层 0 个请求、
 *   不出卡；
 * - 确认游客：不带令牌照发；有令牌：带上发（对照）。
 */
class ParseInputIdentityUnknownTest {
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
                        .setBody("""{"code":0,"msg":"","data":{"kind":"unrecognized"},"trace_id":"trace-000001"}""")
                }
            }
        server.start()
    }

    @After
    fun stop() = server.shutdown()

    @Test
    fun unreadableSessionRejectsParseInputLocallyAndSendsNothing() =
        runBlocking {
            val tokens = ControlledTokens(MemorySessionTokens("access-A", "refresh-A", SessionScopeRaw.FULL, ACCOUNT_A))
            tokens.unreadable = true
            val api = RecordingPageApi(ApiClientPageApi(client(tokens)))

            val outcome = ParseInputService(api).parse(UPLOAD, owner(api))

            assertEquals(ApiResult.LocalReject(LocalRejectReason.SESSION_UNREADABLE), api.results.single())
            assertEquals("传输层收到 0 个请求", 0, server.requestCount)
            assertTrue("本地拒绝不出卡", outcome.dropped)
        }

    @Test
    fun refreshOnlySessionRejectsParseInputLocallyAndSendsNothing() =
        runBlocking {
            val tokens = ControlledTokens(MemorySessionTokens(null, "refresh-A", SessionScopeRaw.FULL, ACCOUNT_A))
            val api = RecordingPageApi(ApiClientPageApi(client(tokens)))

            val outcome = ParseInputService(api).parse(UPLOAD, owner(api))

            assertEquals(
                "有 userId、只有 refresh 没有 access 也是身份未知",
                ApiResult.LocalReject(LocalRejectReason.SESSION_UNREADABLE),
                api.results.single(),
            )
            assertEquals(0, server.requestCount)
            assertTrue(outcome.dropped)
        }

    @Test
    fun confirmedGuestStillParsesAnonymously() =
        runBlocking {
            val api = RecordingPageApi(ApiClientPageApi(client(MemorySessionTokens())))

            ParseInputService(api).parse(UPLOAD, owner(api))

            assertTrue(api.results.single().toString(), api.results.single() is ApiResult.Success)
            val sent = requests.single()
            assertEquals("/v1/inputs/parse", sent.path)
            assertNull("确认游客不带令牌", sent.getHeader(HeaderNames.AUTHORIZATION))
        }

    @Test
    fun readableSessionParsesWithTheMembersToken() =
        runBlocking {
            val tokens = ControlledTokens(MemorySessionTokens("access-A", "refresh-A", SessionScopeRaw.FULL, ACCOUNT_A))
            val api = RecordingPageApi(ApiClientPageApi(client(tokens)))

            ParseInputService(api).parse(UPLOAD, owner(api))

            assertEquals("Bearer access-A", requests.single().getHeader(HeaderNames.AUTHORIZATION))
        }

    // ---------------------------------------------------------------- 假件

    private fun owner(api: PageApi) = CallOwnership(api.epochs()) { true }

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

    /** 记下 parseInput 的结果（区分「本地拒绝」与「发出了」）。 */
    private class RecordingPageApi(
        private val inner: PageApi,
    ) : PageApi {
        val results: MutableList<ApiResult> = Collections.synchronizedList(mutableListOf())

        override suspend fun send(call: ApiCall): ApiResult =
            inner.send(call).also { if (call.operationId == ParseInputService.OPERATION) results += it }

        override fun cancelOwned(owner: CallOwnership) = inner.cancelOwned(owner)

        override fun epochs(): CallEpochs = inner.epochs()
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

    private object FixtureDevice : DeviceCredentials {
        override val deviceId: String = "device-fixture"
        override val installSecret: String = "install-fixture"
    }

    private companion object {
        const val ACCOUNT_A = "account-A"
        const val UPLOAD = "https://m.tb.cn/h.abc"
        const val LOCAL_BUDGET_MS = 30_000L
    }
}
