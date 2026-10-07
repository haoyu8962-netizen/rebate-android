package com.couli.core.network

import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * APP-04 Android 第 2 轮评审：
 * - R2-01（共性第 45 条）：调用方的截止时间绑定到 OkHttp 的 Call；到期取消 Call，之后不退避、不重发；
 * - R2-02（共性第 46 条）：共享刷新的调用方有效性接到刷新请求「实际写出前」的守卫，以及登记恢复的入口。
 */
class CallerDeadlineAndRefreshWindowTest {
    private lateinit var server: MockWebServer
    private val reRegistrations = mutableListOf<String?>()
    private val recoveries = mutableListOf<String?>()

    @Volatile
    private var pageOpen = true

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() = server.shutdown()

    private class FixedDevice : DeviceCredentials {
        override val deviceId: String? = "device-fixture"
        override val installSecret: String? = "test-only.install.device-a"
    }

    private fun client(
        http: OkHttpClient = OkHttpClient(),
        retryPolicy: (com.couli.core.generated.ApiOperation) -> RetryDecision = RetryPolicy::forOperation,
    ) = ApiClient(
        identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.2.0", build = "1", channel = "official"),
        baseUrl = { server.url("/").toString() },
        context = { NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, update = UpdateRaw.SUPPORTED) },
        device = FixedDevice(),
        tokens = MemorySessionTokens("access-A", "refresh-A", SessionScopeRaw.FULL, userId = "account-A"),
        pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
        hooks =
            NetworkHooks(
                reRegisterDevice = { usedDeviceId, _ ->
                    reRegistrations += usedDeviceId
                    true
                },
                recoverDevice = { recoveries += it },
            ),
        http = http,
        retryPolicy = retryPolicy,
    )

    private fun envelope(code: Int) =
        MockResponse().setHeader("Content-Type", "application/json").setBody("""{"code":$code,"msg":"","data":null,"trace_id":"trace-0"}""")

    private fun respond(block: (RecordedRequest) -> MockResponse) {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = block(request)
            }
    }

    private fun paths(): List<String?> = List(server.requestCount) { server.takeRequest(1, TimeUnit.SECONDS)?.path }

    // ---------- R2-01 ----------

    @Test
    fun theCallIsCancelledAtTheCallersDeadlineAndNothingIsResentAfterIt() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        // 读接口本身的超时 3 秒、自动重试 1 次（退避 500 毫秒，真实等待）；调用方只剩 400 毫秒预算
        val api = client(retryPolicy = { RetryPolicy.forOperation(it).copy(timeoutMillis = 3_000) })
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BUDGET_MS)
        val started = System.nanoTime()

        val result = api.execute(ApiCall("getMe", ownership = CallOwnership(api.inFlight.epochs(), deadline) { true }))

        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue("阻塞中的 Call 在截止时间被取消（用时 $elapsed 毫秒）", elapsed < BUDGET_MS + SLACK_MS)
        assertTrue("按失败结束：$result", result is ApiResult.LocalReject || result is ApiResult.TransportFailure)
        Thread.sleep(AFTER_DEADLINE_MS)
        assertEquals("截止后不再发出第二个请求", 1, server.requestCount)
    }

    @Test
    fun anExpiredBudgetSendsNothingAtAll() {
        server.enqueue(envelope(0))
        val api = client()
        val past = System.nanoTime() - 1

        val result = api.execute(ApiCall("getMe", ownership = CallOwnership(api.inFlight.epochs(), past) { true }))

        assertEquals(ApiResult.LocalReject(LocalRejectReason.CALL_CANCELLED), result)
        assertEquals(0, server.requestCount)
    }

    // ---------- R2-02 ----------

    /** 唯一的桥调用收到 10002，刷新请求通过循环入口检查后在等连接 / 发出途中页面关闭：刷新请求不写出。 */
    @Test
    fun pageClosedWhileTheRefreshIsConnectingWritesNoRefresh() {
        respond { request -> if (request.path == REFRESH) envelope(0) else envelope(10002) }
        val closeOnRefresh =
            OkHttpClient
                .Builder()
                .eventListener(
                    object : EventListener() {
                        override fun callStart(call: Call) {
                            if (call.request().url.encodedPath == REFRESH) pageOpen = false
                        }
                    },
                ).build()
        val api = client(http = closeOnRefresh)

        val result = api.execute(ApiCall("getMe", ownership = CallOwnership(api.inFlight.epochs()) { pageOpen }))

        assertTrue("按失败结束：$result", result !is ApiResult.Success)
        assertEquals("只有原请求，刷新没有写出", listOf<String?>("/v1/me"), paths())
    }

    /** 刷新请求 10402 → 重新注册后重放；重放回包前页面关闭、重放仍是 10402：只隔离凭据，不再登记恢复。 */
    @Test
    fun pageClosedBeforeTheRefreshReplayAnswersRegistersNoRecovery() {
        var refreshes = 0
        respond { request ->
            if (request.path != REFRESH) {
                envelope(10002)
            } else {
                refreshes += 1
                if (refreshes == 2) pageOpen = false
                envelope(10402)
            }
        }
        val api = client()

        api.execute(ApiCall("getMe", ownership = CallOwnership(api.inFlight.epochs()) { pageOpen }))

        assertEquals("刷新与它的一次重放", 2, refreshes)
        assertEquals("第一次 10402 时页面还在：重新注册一次", 1, reRegistrations.size)
        assertEquals("重放回包前页面已关：不登记恢复", emptyList<String?>(), recoveries)
    }

    // ---------- 共性第 54 条 ----------

    /**
     * 唯一的桥调用收到 10002，共享刷新已写出、服务端不回；调用方到期：在途的刷新请求连同调用一起取消（不留到刷新自己的超时）。
     * 修复前刷新请求不看调用方是否还在，Call 要等满接口超时才结束。
     */
    @Test
    fun theSharedRefreshInFlightIsCancelledWhenItsLastCallerExpires() {
        respond { request -> if (request.path == REFRESH) MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) else envelope(10002) }
        val api = client()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BUDGET_MS)
        val started = System.nanoTime()

        val result = api.execute(ApiCall("getMe", ownership = CallOwnership(api.inFlight.epochs(), deadline) { true }))

        assertTrue("按失败结束：$result", result !is ApiResult.Success)
        val settleBy = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SLACK_MS)
        while (api.inFlight.size > 0 && System.nanoTime() < settleBy) Thread.sleep(POLL_MS)
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertEquals("刷新请求已写出", listOf<String?>("/v1/me", REFRESH), paths())
        assertEquals("到期后刷新请求被取消（用时 $elapsed 毫秒）", 0, api.inFlight.size)
        assertTrue("取消发生在截止时刻附近（用时 $elapsed 毫秒）", elapsed < BUDGET_MS + SLACK_MS)
    }

    /** 共享刷新的调用方页面关闭（没有截止时间）：同样取消在途的刷新请求。 */
    @Test
    fun theSharedRefreshInFlightIsCancelledWhenItsLastCallerIsClosed() {
        respond { request ->
            if (request.path == REFRESH) {
                pageOpen = false
                MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
            } else {
                envelope(10002)
            }
        }
        val api = client()
        val started = System.nanoTime()

        val result = api.execute(ApiCall("getMe", ownership = CallOwnership(api.inFlight.epochs()) { pageOpen }))

        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue("按失败结束：$result", result !is ApiResult.Success)
        assertTrue("页面关闭后刷新请求很快被取消（用时 $elapsed 毫秒）", elapsed < SLACK_MS * 2)
        assertEquals(0, api.inFlight.size)
    }

    private companion object {
        const val REFRESH = "/v1/auth/refresh"
        const val BUDGET_MS = 400L
        const val SLACK_MS = 600L
        const val AFTER_DEADLINE_MS = 1_200L
        const val POLL_MS = 20L
    }
}
