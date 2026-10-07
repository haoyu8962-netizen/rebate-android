package com.couli.core.network

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * APP-04 三端共性第 30 条（iOS I-R1-05）：迟到的响应在进入恢复分支（10002 刷新、10401 校时、10402 重新注册）之前先查调用有效性。
 * 桥调用的页面在响应到达前已关闭或换了文档（CallOwnership 的有效性变为 false）：不发起刷新、不校时、不重新注册，按 call_cancelled 结束。
 */
class LateRecoveryValidityTest {
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

    private fun client(clock: () -> Long = System::currentTimeMillis) =
        ApiClient(
            identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.2.0", build = "1", channel = "official"),
            baseUrl = { server.url("/").toString() },
            context = {
                NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, update = UpdateRaw.SUPPORTED)
            },
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
            clock = clock,
            sleeper = {},
        )

    /** 响应到达时页面已关闭：先把有效性置为 false，再回 [response]。 */
    private fun respondAfterPageClosed(response: () -> MockResponse) {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path != "/v1/auth/refresh") pageOpen = false
                    return response()
                }
            }
    }

    private fun bridgeCall(
        api: ApiClient,
        call: (CallOwnership) -> ApiCall,
    ): ApiResult = api.execute(call(CallOwnership(api.inFlight.epochs()) { pageOpen }))

    private fun envelope(code: Int) =
        MockResponse().setHeader("Content-Type", "application/json").setBody("""{"code":$code,"msg":"","data":null,"trace_id":"trace-0"}""")

    @Test
    fun late10002ForAClosedPageStartsNoRefresh() {
        respondAfterPageClosed { envelope(10002) }
        val api = client()

        val result = bridgeCall(api) { ApiCall("getMe", ownership = it) }

        assertEquals(ApiResult.LocalReject(LocalRejectReason.CALL_CANCELLED), result)
        assertEquals("只有原请求，没有刷新请求", 1, server.requestCount)
    }

    @Test
    fun late10401ForAClosedPageNeitherCorrectsTheClockNorResigns() {
        val serverDate = "Wed, 30 Sep 2026 06:00:00 GMT"
        respondAfterPageClosed { envelope(10401).setHeader("Date", serverDate) }
        val api = client(clock = { 0L })

        bridgeCall(api) { ApiCall("openLink", mapOf("link_id" to "abc"), body = "{}", ownership = it) }
        assertEquals("没有重签", 1, server.requestCount)

        // 之后一次普通请求的时间戳仍是本机时钟（没有被迟到的 10401 校时）
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = envelope(0)
            }
        api.execute(ApiCall("openLink", mapOf("link_id" to "abc"), body = "{}"))
        server.takeRequest()
        assertEquals("0", server.takeRequest().getHeader(HeaderNames.TIMESTAMP))
    }

    @Test
    fun late10402ForAClosedPageNeitherReRegistersNorRegistersARecovery() {
        respondAfterPageClosed { envelope(10402) }
        val api = client()

        val result = bridgeCall(api) { ApiCall("getMe", ownership = it) }

        assertTrue("按失败结束，没有重放", result is ApiResult.Failure)
        assertEquals(1, server.requestCount)
        assertEquals(emptyList<String?>(), reRegistrations)
        assertEquals(emptyList<String?>(), recoveries)
    }

    /**
     * 共性第 41 条（iOS I-R2-02）：共享刷新窗口登记有效的调用方；刷新请求校时重发之前，唯一的调用方（桥调用）已失效 → 不再续发。
     */
    @Test
    fun refreshWindowStopsResendingWhenNoLiveCallerIsLeft() {
        var refreshes = 0
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path != "/v1/auth/refresh") return envelope(10002)
                    refreshes += 1
                    // 刷新请求发出去之后页面关了；服务端要求校时（10401 + Date）
                    pageOpen = false
                    return envelope(10401).setHeader("Date", "Wed, 30 Sep 2026 06:00:00 GMT")
                }
            }
        val api = client(clock = { 0L })

        bridgeCall(api) { ApiCall("getMe", ownership = it) }

        assertEquals("刷新只发了一次：没有有效调用方后不校时重发", 1, refreshes)
    }
}
