package com.couli.feature.trade

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
import com.couli.core.network.SessionRead
import com.couli.core.network.SessionReader
import com.couli.core.network.SessionScopeRaw
import com.couli.core.network.SessionSnapshot
import com.couli.core.network.SessionTokens
import com.couli.core.network.UpdateRaw
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
 * FU-AND-STEPUP-SUMMARY 推送前补单（独立写手，先红）：03 §4.2「可选登录接口的发送口径」第一、二条在购买（openLink）真实调用点上的落地——
 * 转链 / 外跳不声明「身份未知也照发」：会话读不出、或读得出却只有 refresh 没有 access 时，[OpenPurchaseController] 的 openLink 本地拒绝
 * session_unreadable、传输层收到 0 个请求、不外跳；确认游客照常匿名发出（对照）。真实 [ApiClient] → MockWebServer。
 */
class OpenPurchaseIdentityUnknownTest {
    private lateinit var server: MockWebServer
    private val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    val data = TradeFixtures.openResult(attempt = "attempt-1")
                    return MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"code":0,"msg":"","data":$data,"trace_id":"trace-000654"}""")
                }
            }
        server.start()
    }

    @After
    fun stop() {
        scope.cancel()
        server.shutdown()
    }

    @Test
    fun unreadableSessionRejectsOpenLinkLocallyAndSendsNothing() =
        runBlocking {
            val tokens = ControlledTokens(MemorySessionTokens(ACCESS_A, REFRESH_A, SessionScopeRaw.FULL, ACCOUNT_A))
            tokens.unreadable = true
            val run = buy(tokens)

            assertEquals(ApiResult.LocalReject(LocalRejectReason.SESSION_UNREADABLE), run.openResult())
            assertEquals("传输层收到 0 个请求", 0, server.requestCount)
            assertTrue("不外跳", run.ports.launched.isEmpty())
        }

    @Test
    fun refreshOnlySessionRejectsOpenLinkLocallyAndSendsNothing() =
        runBlocking {
            val tokens = ControlledTokens(MemorySessionTokens(null, REFRESH_A, SessionScopeRaw.FULL, ACCOUNT_A))
            val run = buy(tokens)

            assertEquals(
                "有 userId、只有 refresh 没有 access 也是身份未知",
                ApiResult.LocalReject(LocalRejectReason.SESSION_UNREADABLE),
                run.openResult(),
            )
            assertEquals(0, server.requestCount)
            assertTrue(run.ports.launched.isEmpty())
        }

    @Test
    fun confirmedGuestStillOpensTheLinkAnonymously() =
        runBlocking {
            val run = buy(MemorySessionTokens())

            assertTrue(run.openResult().toString(), run.openResult() is ApiResult.Success)
            val opens = requests.filter { it.path?.startsWith("/v1/links/") == true }
            assertEquals(1, opens.size)
            assertNull("确认游客不带令牌", opens.single().getHeader(HeaderNames.AUTHORIZATION))
        }

    // ---------------------------------------------------------------- 假件

    private class Run(
        val ports: FakePorts,
        val api: RecordingPageApi,
    ) {
        fun openResult(): ApiResult? = api.results.firstOrNull { it.first.operationId == TradeOperations.OPEN_LINK }?.second
    }

    private suspend fun buy(tokens: SessionTokens): Run {
        val api = RecordingPageApi(ApiClientPageApi(client(tokens)))
        val ports = FakePorts()
        val controller =
            withContext(scope.coroutineContext) {
                OpenPurchaseController(
                    api = api,
                    executor = TradeJumpExecutor(ports, FakeBaichuan(), {}, now = { TradeFixtures.NOW }),
                    ports = ports,
                    env = FakeEnv(loggedIn = false).also { it.card = TradeFixtures.card() },
                    pending = MemoryPurchasePending(),
                    counter = AuthSheetCounter(),
                    tips = JumpTipService(api) { null },
                    webCode = FakeWebCode(),
                    baichuan = FakeBaichuan(),
                    scope = scope,
                    timeoutMillis = OPEN_TIMEOUT_MS,
                    browserNoticeMillis = 0L,
                )
            }
        withContext(scope.coroutineContext) { controller.buy(TradeFixtures.card(), null) }
        withTimeout(WAIT_MS) {
            while (api.results.none { it.first.operationId == TradeOperations.OPEN_LINK }) delay(POLL_MS)
        }
        delay(SETTLE_MS)
        return Run(ports, api)
    }

    private fun client(tokens: SessionTokens) =
        ApiClient(
            identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.0.0", build = "1", channel = "official"),
            baseUrl = { server.url("/").toString() },
            context = { NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, UpdateRaw.SUPPORTED) },
            device = FixtureDevice,
            tokens = tokens,
            pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
            sleeper = {},
        )

    /** 记下每次调用与结果（区分「本地拒绝」与「发出了」）。 */
    private class RecordingPageApi(
        private val inner: PageApi,
    ) : PageApi {
        val results: MutableList<Pair<ApiCall, ApiResult>> = Collections.synchronizedList(mutableListOf())

        override suspend fun send(call: ApiCall): ApiResult = inner.send(call).also { results += call to it }

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
        const val ACCESS_A = "access-A"
        const val REFRESH_A = "refresh-A"
        const val WAIT_MS = 10_000L
        const val POLL_MS = 20L
        const val SETTLE_MS = 200L
        const val OPEN_TIMEOUT_MS = 5_000L
    }
}
