package com.couli.core.network

import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * APP-10b §2 第 1、15 条：ApiClient 流式通道对本地假服务（MockWebServer）的集成测试——真实 OkHttp、真实拦截链（公共头、写出前 admit 的网络拦截器、单次发送计数）、
 * InFlightCalls / CallOwnership。头（公共头齐全、无 X-Sign、无 Idempotency-Key、有令牌才带 Authorization、Accept、Accept-Encoding: identity）、Content-Type 分流、
 * 10002 刷新后同 client_msg_id 重放一次、10402 重放、42901、10405、撤回隐私 / 切环境 / 句柄取消同步停止交付、admit 拒绝时 notSent、单次发送不刷新不重放、断线类重载
 * 关掉 GET 自动重试、本机替身在写出前核对之后才回答。
 */
class ApiStreamTest {
    private lateinit var server: MockWebServer
    private val forceUpdates = CopyOnWriteArrayList<String?>()
    private val reRegisters = AtomicInteger()
    private val sleeps = CopyOnWriteArrayList<Long>()
    private val inFlight = InFlightCalls()

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() = server.shutdown()

    private class FixedDevice : DeviceCredentials {
        override val deviceId: String = "device-fixture"
        override val installSecret: String = "test-only.install.device-a"
    }

    private fun client(
        tokens: SessionTokens = MemorySessionTokens("access-A", "refresh-A", SessionScopeRaw.FULL, userId = "account-A"),
        local: LocalExchange? = null,
        reRegister: Boolean = true,
    ) = ApiClient(
        identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.2.0", build = "1", channel = "official"),
        baseUrl = { server.url("/").toString() },
        context = { NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, update = UpdateRaw.SUPPORTED) },
        device = FixedDevice(),
        tokens = tokens,
        pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
        hooks =
            NetworkHooks(
                onForceUpdate = { forceUpdates += it },
                reRegisterDevice = { _, _ ->
                    reRegisters.incrementAndGet()
                    reRegister
                },
            ),
        sleeper = { sleeps += it },
        inFlight = inFlight,
        local = local,
    )

    /** 记下 IO 线程交来的回调。 */
    private class Sink : ApiStreamSink {
        val headers = CopyOnWriteArrayList<Map<String, String>>()
        val bytes = ByteArrayOutputStream()
        val events = CopyOnWriteArrayList<String>()
        var envelope: ApiResult? = null
        val done = CountDownLatch(1)
        val firstBytes = CountDownLatch(1)

        override fun onHeaders(
            status: Int,
            headers: Map<String, String>,
        ) {
            this.headers += headers
            events += "headers"
        }

        override fun onBytes(chunk: ByteArray) {
            synchronized(bytes) { bytes.write(chunk) }
            events += "bytes"
            firstBytes.countDown()
        }

        override fun onEnd() {
            events += "end"
            done.countDown()
        }

        override fun onFailure(kind: String) {
            events += "failure:$kind"
            done.countDown()
        }

        override fun onEnvelope(result: ApiResult) {
            envelope = result
            events += "envelope"
            done.countDown()
        }

        fun text(): String = synchronized(bytes) { bytes.toString(Charsets.UTF_8.name()) }
    }

    private fun sse(body: String) =
        MockResponse()
            .setHeader("Content-Type", "text/event-stream; charset=utf-8")
            .setHeader("X-Trace-Id", "0199a3b4-5c6d-7e8f-9a0b-1c2d3e4f5a6b")
            .setChunkedBody(body, CHUNK)

    private fun envelope(
        code: Int,
        status: Int = 422,
        retryAfter: String? = null,
    ) = MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", "application/json")
        .apply { retryAfter?.let { setHeader("Retry-After", it) } }
        .setBody("""{"code":$code,"msg":"","data":null,"trace_id":"trace-0123456789"}""")

    private fun request(
        body: String = BODY,
        ownership: CallOwnership? = null,
        operation: String = "sendAgentMessage",
    ) = ApiStreamRequest(operation, mapOf("id" to "s-1"), body, ownership)

    @Test
    fun sseHeadersBytesAndEndThroughTheRealPipeline() {
        server.enqueue(sse(SSE_TEXT))
        val sink = Sink()
        val handle = client().stream(request(), sink)
        assertTrue(sink.done.await(WAIT, TimeUnit.SECONDS))
        val recorded = server.takeRequest()
        assertEquals("/v1/agent/sessions/s-1/messages", recorded.path)
        assertEquals("POST", recorded.method)
        assertEquals(BODY, recorded.body.readUtf8())
        // 公共头齐全、有令牌带 Authorization、不签名、不带 Idempotency-Key、Accept 与 Accept-Encoding: identity（G17、G18）
        for (h in listOf("X-App-Id", "X-Platform", "X-App-Version", "X-Build", "X-Channel", "X-Trace-Id", "X-Device-Id")) {
            assertTrue(h, recorded.getHeader(h) != null)
        }
        assertEquals("Bearer access-A", recorded.getHeader("Authorization"))
        assertNull(recorded.getHeader("X-Sign"))
        assertNull(recorded.getHeader("X-Timestamp"))
        assertNull(recorded.getHeader("X-Nonce"))
        assertNull(recorded.getHeader("Idempotency-Key"))
        assertEquals("text/event-stream", recorded.getHeader("Accept"))
        assertEquals("identity", recorded.getHeader("Accept-Encoding"))
        // 分流为流：响应头（小写键）→ 原始字节（分块到达，逐字节相同）→ 结束一次
        assertEquals("text/event-stream; charset=utf-8", sink.headers.single()["content-type"])
        assertEquals(SSE_TEXT, sink.text())
        assertEquals("end", sink.events.last())
        assertEquals(1, sink.events.count { it == "end" })
        assertNull(sink.envelope)
        assertTrue(handle.delivered > 0)
    }

    @Test
    fun guestCarriesNoAuthorizationButDeviceHeader() {
        server.enqueue(sse(SSE_TEXT))
        val sink = Sink()
        client(tokens = MemorySessionTokens()).stream(request(), sink)
        assertTrue(sink.done.await(WAIT, TimeUnit.SECONDS))
        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Authorization"))
        assertEquals("device-fixture", recorded.getHeader("X-Device-Id"))
    }

    @Test
    fun nonSseResponsesGoThroughTheEnvelopePath() {
        server.enqueue(envelope(30501))
        val sink = Sink()
        client().stream(request(), sink)
        assertTrue(sink.done.await(WAIT, TimeUnit.SECONDS))
        assertEquals(30501, (sink.envelope as ApiResult.Failure).code)
        assertTrue(sink.headers.isEmpty())
        // 网关错误页：没有统一外壳
        server.enqueue(MockResponse().setResponseCode(502).setHeader("Content-Type", "text/html").setBody("<html>bad gateway</html>"))
        val html = Sink()
        client().stream(request(), html)
        assertTrue(html.done.await(WAIT, TimeUnit.SECONDS))
        assertEquals(HttpTransport.KIND_NO_ENVELOPE, (html.envelope as ApiResult.TransportFailure).kind)
        // 非 2xx 却是 event-stream：按没有统一外壳（G19）
        server.enqueue(MockResponse().setResponseCode(403).setHeader("Content-Type", "text/event-stream").setBody(": ping\n\n"))
        val forbidden = Sink()
        client().stream(request(), forbidden)
        assertTrue(forbidden.done.await(WAIT, TimeUnit.SECONDS))
        assertTrue(forbidden.envelope is ApiResult.TransportFailure)
    }

    @Test
    fun refresh10002ReplaysOnceWithTheSameBodyBeforeAnySseByte() {
        val sends = CopyOnWriteArrayList<String>()
        val refreshes = AtomicInteger()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when (request.path) {
                        "/v1/auth/refresh" -> {
                            refreshes.incrementAndGet()
                            MockResponse().setHeader("Content-Type", "application/json").setBody(
                                """{"code":0,"msg":"","data":{"access_token":"access-B",""" +
                                    """"access_expires_at":"2026-10-02T13:30:00+08:00",""" +
                                    """"refresh_token":"refresh-B","refresh_expires_at":"2026-11-01T11:30:00+08:00",""" +
                                    """"session_scope":"full"},"trace_id":"t"}""",
                            )
                        }

                        else -> {
                            sends += request.body.readUtf8() + "|" + request.getHeader("Authorization")
                            if (sends.size == 1) envelope(10002, 401) else sse(SSE_TEXT)
                        }
                    }
            }
        val sink = Sink()
        client().stream(request(), sink)
        assertTrue(sink.done.await(WAIT, TimeUnit.SECONDS))
        assertEquals(1, refreshes.get())
        assertEquals(listOf("$BODY|Bearer access-A", "$BODY|Bearer access-B"), sends.toList())
        assertEquals(SSE_TEXT, sink.text())
    }

    @Test
    fun register10402ReplaysOnce() {
        val sends = AtomicInteger()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (sends.incrementAndGet() ==
                        1
                    ) {
                        envelope(10402, 401)
                    } else {
                        sse(SSE_TEXT)
                    }
            }
        val sink = Sink()
        client().stream(request(), sink)
        assertTrue(sink.done.await(WAIT, TimeUnit.SECONDS))
        assertEquals(1, reRegisters.get())
        assertEquals(2, sends.get())
        assertEquals(SSE_TEXT, sink.text())
    }

    @Test
    fun rateLimitAndForceUpdateComeBackAsEnvelopes() {
        server.enqueue(envelope(42901, 429, retryAfter = "12"))
        val limited = Sink()
        client().stream(request(), limited)
        assertTrue(limited.done.await(WAIT, TimeUnit.SECONDS))
        assertEquals(12_000L, (limited.envelope as ApiResult.Failure).effect.disableMillis)
        server.enqueue(
            MockResponse()
                .setHeader(
                    "Content-Type",
                    "application/json",
                ).setBody("""{"code":10405,"msg":"","data":{"min_supported_version":"9.0.0"},"trace_id":"t"}"""),
        )
        val forced = Sink()
        client().stream(request(), forced)
        assertTrue(forced.done.await(WAIT, TimeUnit.SECONDS))
        assertEquals(listOf("9.0.0"), forceUpdates.toList())
    }

    @Test
    fun handleCancelPrivacyRevokeAndEnvironmentSwitchStopDeliverySynchronously() {
        for (stopper in listOf<
            (
                ApiStreamHandle,
            ) -> Unit,
        >({ it.cancel() }, { inFlight.onPrivacyRevoked() }, { inFlight.onEnvironmentSwitching() })) {
            server.enqueue(sse(SSE_TEXT.repeat(REPEAT)).throttleBody(THROTTLE_BYTES, THROTTLE_MS, TimeUnit.MILLISECONDS))
            val sink = Sink()
            val handle = client().stream(request(), sink)
            assertTrue(sink.firstBytes.await(WAIT, TimeUnit.SECONDS))
            stopper(handle)
            val before = sink.events.size
            Thread.sleep(SETTLE_MS)
            // 作废之后：0 块交付、不交终结（被取消不回调 onFailure / onEnd）
            assertEquals(before, sink.events.size)
            assertFalse(sink.events.contains("end"))
            assertTrue(sink.text().length < SSE_TEXT.length * REPEAT)
        }
    }

    @Test
    fun admitRejectAtWriteTimeIsNotSentAndNothingReachesTheServer() {
        val ownership = CallOwnership(inFlight.epochs(), sendAllowed = { false }) { true }
        val sink = Sink()
        client().stream(request(ownership = ownership), sink)
        assertTrue(sink.done.await(WAIT, TimeUnit.SECONDS))
        assertEquals(LocalRejectReason.CALL_CANCELLED, (sink.envelope as ApiResult.LocalReject).reason)
        assertEquals(0, server.requestCount)
        // 表外 operation 走流式入口：本地拒绝
        val other = Sink()
        client().stream(request(operation = "listAgentSessionMessages"), other)
        assertEquals(ApiStreamOperations.NOT_STREAMING, (other.envelope as ApiResult.LocalReject).reason)
    }

    @Test
    fun cancelOwnedCancelsTheTaggedStream() {
        server.enqueue(sse(SSE_TEXT.repeat(REPEAT)).throttleBody(THROTTLE_BYTES, THROTTLE_MS, TimeUnit.MILLISECONDS))
        val ownership = CallOwnership(inFlight.epochs()) { true }
        val api = client()
        val sink = Sink()
        api.stream(request(ownership = ownership), sink)
        assertTrue(sink.firstBytes.await(WAIT, TimeUnit.SECONDS))
        api.cancelOwned(ownership)
        Thread.sleep(SETTLE_MS)
        val before = sink.events.size
        Thread.sleep(SETTLE_MS)
        assertEquals(before, sink.events.size)
        assertFalse(sink.events.contains("end"))
    }

    @Test
    fun singleShotCancelNeverRefreshesReRegistersOrReplays() {
        val sends = AtomicInteger()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/v1/auth/refresh") error("单次发送不得刷新")
                    return if (sends.incrementAndGet() == 1) envelope(10002, 401) else envelope(10402, 401)
                }
            }
        val api = client()
        val written = CopyOnWriteArrayList<Boolean>()
        val ownership = CallOwnership(inFlight.epochs(), onWritten = { written += it }) { true }
        val r1 = api.execute(ApiCall("cancelAgentRun", pathParams = mapOf("run_id" to "run-1"), ownership = ownership, singleShot = true))
        assertEquals(10002, (r1 as ApiResult.Failure).code)
        assertTrue(r1.effect.route == null)
        val r2 = api.execute(ApiCall("cancelAgentRun", pathParams = mapOf("run_id" to "run-1"), ownership = ownership, singleShot = true))
        assertEquals(10402, (r2 as ApiResult.Failure).code)
        assertEquals(0, reRegisters.get())
        assertEquals(2, sends.get())
        // 写出信号：每次写出前核对放行时调一次
        assertEquals(listOf(true, true), written.toList())
    }

    @Test
    fun historyReloadWithoutAutoRetrySendsExactlyOnce() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val r =
            client().execute(
                ApiCall("listAgentSessionMessages", pathParams = mapOf("id" to "s-1"), query = "limit=20", autoRetry = false),
            )
        assertTrue(r is ApiResult.TransportFailure)
        assertEquals(1, server.requestCount)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun localExchangeAnswersOnlyAfterWriteTimeAdmission() {
        val asked = AtomicInteger()
        val local =
            object : LocalExchange {
                override fun handles(request: Request): Boolean = request.url.encodedPath.startsWith("/v1/agent/")

                override fun answer(
                    request: Request,
                    canceled: () -> Boolean,
                ): Response {
                    asked.incrementAndGet()
                    return Response
                        .Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "text/event-stream")
                        .body(SSE_TEXT.toResponseBody(null))
                        .build()
                }
            }
        val api = client(local = local)
        val blocked = Sink()
        api.stream(request(ownership = CallOwnership(inFlight.epochs(), sendAllowed = { false }) { true }), blocked)
        assertTrue(blocked.done.await(WAIT, TimeUnit.SECONDS))
        assertEquals(0, asked.get())
        val sink = Sink()
        api.stream(request(), sink)
        assertTrue(sink.done.await(WAIT, TimeUnit.SECONDS))
        assertEquals(1, asked.get())
        assertEquals(SSE_TEXT, sink.text())
        assertEquals(0, server.requestCount)
    }

    // ---------------------------------------------------------------- 第 2 轮修复 R2-02（rule-gaps A23）

    @Test
    fun fixR2Stream10001WithTheMembersTokenEndsThatLoginGenerationWithoutNavigationEffect() {
        val tokens = MemorySessionTokens("access-A", "refresh-A", SessionScopeRaw.FULL, userId = "account-A")
        val epoch = tokens.loginEpoch
        server.enqueue(envelope(10001, status = 401))
        val sink = Sink()
        client(tokens).stream(request(), sink)
        assertTrue(sink.done.await(WAIT, TimeUnit.SECONDS))
        val failure = sink.envelope as ApiResult.Failure
        assertEquals(10001, failure.code)
        assertEquals("Bearer access-A", server.takeRequest().getHeader("Authorization"))
        // 公共管线按世代结算原登录的会话失效：令牌清掉、登录世代前进；Effect 仍是 LOGIN（跳不跳由页面按自己的许可决定），带清理后的世代
        assertNull(tokens.accessToken)
        assertTrue(tokens.loginEpoch != epoch)
        assertEquals(ErrorEffectKind.LOGIN, failure.effect.kind)
        assertEquals(tokens.loginEpoch, failure.effect.sessionEpoch)
    }

    @Test
    fun fixR2Stream10001ArrivingAfterANewLoginLeavesTheNewSessionAlone() {
        val tokens = MemorySessionTokens("access-A", "refresh-A", SessionScopeRaw.FULL, userId = "account-A")
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    // A 的请求在途时：A 退出、B 登录（新的登录世代）；之后 A 的 10001 才到
                    tokens.clear()
                    tokens.save("access-B", "refresh-B", SessionScopeRaw.FULL, "account-B")
                    return envelope(10001, status = 401)
                }
            }
        val sink = Sink()
        client(tokens).stream(request(), sink)
        assertTrue(sink.done.await(WAIT, TimeUnit.SECONDS))
        assertEquals("access-B", tokens.accessToken)
        assertEquals("account-B", tokens.userId)
        // 旧世代的结果不交付会话类副作用
        val effect = (sink.envelope as? ApiResult.Failure)?.effect
        assertTrue(effect == null || effect.sessionEpoch == null)
    }

    @Test
    fun fixR2SingleShotCancel10001DoesNotClearTheSession() {
        val tokens = MemorySessionTokens("access-A", "refresh-A", SessionScopeRaw.FULL, userId = "account-A")
        server.enqueue(envelope(10001, status = 401))
        val r = client(tokens).execute(ApiCall("cancelAgentRun", pathParams = mapOf("run_id" to "r-1"), singleShot = true))
        assertEquals(10001, (r as ApiResult.Failure).code)
        assertEquals("access-A", tokens.accessToken)
    }

    private companion object {
        const val BODY = """{"client_msg_id":"Previous_01234567","text":"提现记录在哪看"}"""
        const val SSE_TEXT =
            ": ping\n\nevent: meta\nid: 1\ndata: {\"session_id\":\"s\",\"run_id\":\"r\",\"message_id\":\"m\"}\n\n" +
                "event: text.delta\nid: 2\ndata: {\"seq\":2,\"delta\":\"中😀文\"}\n\n"
        const val CHUNK = 7
        const val WAIT = 10L
        const val REPEAT = 40
        const val THROTTLE_BYTES = 16L
        const val THROTTLE_MS = 100L
        const val SETTLE_MS = 400L
    }
}
