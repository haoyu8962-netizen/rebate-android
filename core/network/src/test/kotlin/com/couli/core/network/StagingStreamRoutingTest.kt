package com.couli.core.network

import com.couli.core.generated.ApiOperations
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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
 * FU-X-STAGING-HOSTS-android 测试 v2（D10-598 ①）：ApiClient 只在 streamBaseUrl 非空时把 `/v1/agent/` 下的 operation 发往 stream 入口，
 * 其余仍发 api 入口；为 null（PROD、LOCAL、TEST，以及不传该形参）时与底版逐项一致。两个 MockWebServer 分别代表 api-stg 与 stream-stg。
 * 约定的形状（handback v2 §2 V1–V3）：ApiClient 构造形参 `streamBaseUrl: () -> String?`（缺省 `{ null }`）；路由口径是纯函数
 * `ApiEntryRouting.usesStreamEntry(pathTemplate)`，按 operation 的 pathTemplate 判断、前缀 `/v1/agent/` 精确匹配。
 */
@Suppress("TooManyFunctions") // 逐项路由、头、会话、重试与环境闸门用例
class StagingStreamRoutingTest {
    private lateinit var api: MockWebServer
    private lateinit var stream: MockWebServer
    private lateinit var other: MockWebServer
    private val sleeps = CopyOnWriteArrayList<Long>()

    @Before
    fun start() {
        api = MockWebServer().apply { start() }
        stream = MockWebServer().apply { start() }
        other = MockWebServer().apply { start() }
        listOf(api, stream, other).forEach { it.dispatcher = Recorder() }
    }

    @After
    fun stop() = listOf(api, stream, other).forEach { it.shutdown() }

    /** 记下收到的请求；/v1/auth/refresh 回新令牌，sendAgentMessage 回 SSE，其余回成功外壳。可按次序注入断连或 10002。 */
    private class Recorder : Dispatcher() {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val script = CopyOnWriteArrayList<MockResponse>()

        /** 连接建立时 MockWebServer 先问 peek 取套接字策略（断连类注入要在这里给出）。 */
        override fun peek(): MockResponse = script.firstOrNull() ?: super.peek()

        override fun dispatch(request: RecordedRequest): MockResponse {
            requests += request
            if (script.isNotEmpty()) return script.removeAt(0)
            return when {
                request.path == "/v1/auth/refresh" -> refreshed()
                request.method == "POST" && request.path!!.endsWith("/messages") -> sse()
                else -> ok()
            }
        }
    }

    private fun MockWebServer.recorder() = dispatcher as Recorder

    private fun MockWebServer.paths() = recorder().requests.map { it.method + " " + it.path }

    private fun MockWebServer.base() = url("/").toString()

    private class FixedDevice : DeviceCredentials {
        override val deviceId: String = "device-fixture"
        override val installSecret: String = "test-only.install.device-a"
    }

    private fun tokens() = MemorySessionTokens("access-A", "refresh-A", SessionScopeRaw.FULL, userId = "account-A")

    private fun client(
        streamBase: () -> String?,
        apiBase: () -> String? = { api.base() },
        tokens: SessionTokens = tokens(),
    ) = ApiClient(
        identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.2.0", build = "1", channel = "official"),
        baseUrl = apiBase,
        streamBaseUrl = streamBase,
        context = { NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, update = UpdateRaw.SUPPORTED) },
        device = FixedDevice(),
        tokens = tokens,
        pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
        sleeper = { sleeps += it },
    )

    /** 底版形状：不传 streamBaseUrl。 */
    private fun legacyClient() =
        ApiClient(
            identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.2.0", build = "1", channel = "official"),
            baseUrl = { api.base() },
            context = {
                NetworkContext(
                    PrivacyRaw.FULL,
                    environmentSettled = true,
                    credentialsReadable = true,
                    update = UpdateRaw.SUPPORTED,
                )
            },
            device = FixedDevice(),
            tokens = tokens(),
            pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
            sleeper = { sleeps += it },
        )

    private class Sink : ApiStreamSink {
        val done = CountDownLatch(1)
        var ended = false
        var envelope: ApiResult? = null

        override fun onHeaders(
            status: Int,
            headers: Map<String, String>,
        ) = Unit

        override fun onBytes(chunk: ByteArray) = Unit

        override fun onEnd() {
            ended = true
            done.countDown()
        }

        override fun onFailure(kind: String) = done.countDown()

        override fun onEnvelope(result: ApiResult) {
            envelope = result
            done.countDown()
        }
    }

    private fun streamOnce(client: ApiClient): Sink {
        val sink = Sink()
        client.stream(ApiStreamRequest("sendAgentMessage", mapOf("id" to "s-1"), BODY, null), sink)
        assertTrue("流式调用应在时限内结束", sink.done.await(WAIT, TimeUnit.SECONDS))
        return sink
    }

    /** 七个 agent operation 中非流式的六个（按 ApiClient 实际调用点：StreamClient / AgentContractPort 走 execute）。 */
    private val agentCalls =
        listOf(
            ApiCall("createAgentSession", body = "{}") to "POST /v1/agent/sessions",
            ApiCall("getCurrentAgentSession") to "GET /v1/agent/sessions/current",
            ApiCall("listAgentSessionMessages", pathParams = mapOf("id" to "s-1")) to "GET /v1/agent/sessions/s-1/messages",
            ApiCall("cancelAgentRun", pathParams = mapOf("run_id" to "r-1"), singleShot = true) to "POST /v1/agent/runs/r-1/cancel",
            ApiCall("setAgentMessageFeedback", pathParams = mapOf("message_id" to "m-1"), body = "{}") to
                "POST /v1/agent/messages/m-1/feedback",
            ApiCall("reportAgentMessage", pathParams = mapOf("message_id" to "m-1"), body = "{}") to
                "POST /v1/agent/messages/m-1/report",
        )

    // ---------------------------------------------------------------- 路由口径

    @Test fun n01RoutingRuleIsAnExactPathPrefix() {
        listOf("/v1/agent/sessions", "/v1/agent/sessions/current", "/v1/agent/sessions/{id}/messages", "/v1/agent/runs/{run_id}/cancel")
            .forEach { assertTrue("应走 stream：$it", ApiEntryRouting.usesStreamEntry(it)) }
        listOf(
            "/v1/agent",
            "/v1/agents",
            "/v1/agents/x",
            "/v1/agentX/y",
            "/v1/agent-x/y",
            "/v1/agent_x/y",
            "/v1/AGENT/sessions",
            "/v2/agent/sessions",
            "/v1/me/agent/x",
            "v1/agent/sessions",
            "/v1/config",
            "/v1/auth/refresh",
            "/healthz",
            "",
        ).forEach { assertFalse("不得走 stream：'$it'", ApiEntryRouting.usesStreamEntry(it)) }
    }

    @Test fun n02ContractOperationsRoutedToStreamAreExactlyTheSevenAgentOperations() {
        val routed =
            ApiOperations.all.values
                .filter { ApiEntryRouting.usesStreamEntry(it.pathTemplate) }
                .map { it.operationId }
                .toSet()
        assertEquals(
            setOf(
                "createAgentSession",
                "getCurrentAgentSession",
                "listAgentSessionMessages",
                "sendAgentMessage",
                "cancelAgentRun",
                "setAgentMessageFeedback",
                "reportAgentMessage",
            ),
            routed,
        )
    }

    // ---------------------------------------------------------------- streamBaseUrl 非空

    @Test fun n03WithStreamBaseTheSseCallGoesToTheStreamEntry() {
        val sink = streamOnce(client(streamBase = { stream.base() }))
        assertTrue("SSE 正常结束", sink.ended)
        assertEquals(listOf("POST /v1/agent/sessions/s-1/messages"), stream.paths())
        assertEquals("api 入口不应收到 agent 请求", emptyList<String>(), api.paths())
        val recorded = stream.recorder().requests.single()
        assertEquals("Bearer access-A", recorded.getHeader("Authorization"))
        assertEquals("device-fixture", recorded.getHeader("X-Device-Id"))
        assertEquals("text/event-stream", recorded.getHeader("Accept"))
        assertEquals("identity", recorded.getHeader("Accept-Encoding"))
        assertNull(recorded.getHeader("X-Sign"))
    }

    @Test fun n04WithStreamBaseEveryNonStreamingAgentOperationGoesToTheStreamEntry() {
        val client = client(streamBase = { stream.base() })
        agentCalls.forEach { (call, _) ->
            val result = client.execute(call)
            assertTrue("${call.operationId} 成功：$result", result is ApiResult.Success)
        }
        assertEquals(agentCalls.map { it.second }, stream.paths())
        assertEquals(emptyList<String>(), api.paths())
    }

    @Test fun n05WithStreamBaseNonAgentCallsStayOnTheApiEntry() {
        val client = client(streamBase = { stream.base() })
        assertTrue(client.execute(ApiCall("getConfig")) is ApiResult.Success)
        assertTrue(client.execute(ApiCall("getHealthz")) is ApiResult.Success)
        assertEquals(listOf("GET /v1/config", "GET /healthz"), api.paths())
        assertEquals("stream 入口只收 /v1/agent/*", emptyList<String>(), stream.paths())
    }

    // ---------------------------------------------------------------- streamBaseUrl 为 null：与底版一致

    @Test fun n06NullStreamBaseKeepsEveryRequestOnTheApiEntry() {
        listOf(client(streamBase = { null }), legacyClient()).forEachIndexed { i, client ->
            api.recorder().requests.clear()
            agentCalls.forEach { (call, _) -> assertTrue("${call.operationId}", client.execute(call) is ApiResult.Success) }
            assertTrue(streamOnce(client).ended)
            assertTrue(client.execute(ApiCall("getConfig")) is ApiResult.Success)
            assertEquals(
                "第 $i 种（显式 null / 不传形参）全部走 api",
                agentCalls.map { it.second } + "POST /v1/agent/sessions/s-1/messages" + "GET /v1/config",
                api.paths(),
            )
        }
        assertEquals(emptyList<String>(), stream.paths())
    }

    // ---------------------------------------------------------------- 两入口行为一致

    @Test fun n07HeadersAreTheSameOnBothEntries() {
        fun headersOf(server: MockWebServer): Map<String, String> =
            server
                .recorder()
                .requests
                .single()
                .headers
                .toMultimap()
                .filterKeys { it.lowercase() !in setOf("host", "x-trace-id") }
                .mapValues { it.value.joinToString(",") }
        assertTrue(client(streamBase = { stream.base() }).execute(ApiCall("getCurrentAgentSession")) is ApiResult.Success)
        assertTrue(client(streamBase = { null }).execute(ApiCall("getCurrentAgentSession")) is ApiResult.Success)
        assertEquals("除 Host、X-Trace-Id 外逐项相同", headersOf(api), headersOf(stream))
        assertTrue(headersOf(stream).keys.any { it.equals("Authorization", ignoreCase = true) })
    }

    @Test fun n08Refresh10002OnTheStreamEntryRefreshesViaApiAndReplaysToStream() {
        stream.recorder().script += envelope(10002, 401)
        val tokens = tokens()
        val sink = streamOnce(client(streamBase = { stream.base() }, tokens = tokens))
        assertTrue("刷新后重放一次并正常结束", sink.ended)
        assertEquals("刷新走 api 入口（/v1/auth/* 不是 agent 路径）", listOf("POST /v1/auth/refresh"), api.paths())
        assertEquals(
            listOf("Bearer access-A", "Bearer access-B"),
            stream.recorder().requests.map { it.getHeader("Authorization") },
        )
        assertEquals("写操作只发两次：被 10002 拒的原请求 + 重放一次", List(2) { "POST /v1/agent/sessions/s-1/messages" }, stream.paths())
        assertEquals("access-B", tokens.accessToken)
        // v3.1（评审 S2-4）：重放不产生新写——写请求标识（请求体里的 client_msg_id）逐字节相同；幂等键头前后一致。
        // sendAgentMessage 在契约里 x-idempotent=false，底版就不带 Idempotency-Key（ApiStreamTest 同口径），所以这里钉「前后都为 null」
        val sent = stream.recorder().requests
        assertEquals("重放的请求体（含 client_msg_id）与原请求逐字节相同", List(2) { BODY }, sent.map { it.body.readUtf8() })
        assertEquals("Idempotency-Key 前后一致（本操作不幂等，两次都不带）", listOf<String?>(null, null), sent.map { it.getHeader("Idempotency-Key") })
        assertEquals("X-Device-Id 前后相同", List(2) { "device-fixture" }, sent.map { it.getHeader("X-Device-Id") })
        assertEquals("刷新只发一次", 1, api.recorder().requests.size)
    }

    @Test fun n09GetRetryOnDisconnectIsTheSameOnBothEntries() {
        fun run(
            server: MockWebServer,
            streamBase: () -> String?,
        ): Triple<Boolean, Int, List<Long>> {
            sleeps.clear()
            server.recorder().script += MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
            val result = client(streamBase = streamBase).execute(ApiCall("getCurrentAgentSession"))
            return Triple(result is ApiResult.Success, server.recorder().requests.size, sleeps.toList())
        }
        val onApi = run(api, { null })
        api.recorder().requests.clear()
        val onStream = run(stream, { stream.base() })
        assertTrue("断连后自动重试成功", onApi.first)
        assertTrue("底版口径：读接口断连至少重试一次", onApi.second >= 2)
        assertEquals("重试次数与退避两入口一致", onApi, onStream)
        assertEquals("重试仍在 stream 入口，不落到 api", 0, api.recorder().requests.size)
    }

    // ---------------------------------------------------------------- 环境闸门

    @Test fun n10UnsettledEnvironmentRejectsAgentCallsEvenWithAStreamBase() {
        val client = client(streamBase = { stream.base() }, apiBase = { null })
        val result = client.execute(ApiCall("getCurrentAgentSession"))
        assertTrue("环境未定（api 基址为 null）一律本地拒绝：$result", result is ApiResult.LocalReject)
        assertEquals(emptyList<String>(), stream.paths())
    }

    @Test fun n11StreamBaseChangeBeforeTheRetryBehavesLikeAnApiBaseChange() {
        fun run(streamMode: Boolean): Pair<String, Int> {
            val switched = AtomicReference<String?>(null)
            val first = if (streamMode) stream else api
            first.recorder().script += MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
            val apiBase = { if (!streamMode) switched.get() ?: api.base() else api.base() }
            val streamBase = { if (streamMode) switched.get() ?: stream.base() else null }
            val client =
                ApiClient(
                    identity =
                        ClientIdentity(
                            appId = "couli",
                            platform = "android",
                            appVersion = "1.2.0",
                            build = "1",
                            channel = "official",
                        ),
                    baseUrl = apiBase,
                    streamBaseUrl = streamBase,
                    context = {
                        NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, update = UpdateRaw.SUPPORTED)
                    },
                    device = FixedDevice(),
                    tokens = tokens(),
                    pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
                    // 退避期间切换环境：基址换成 other
                    sleeper = { switched.set(other.base()) },
                )
            val result = client.execute(ApiCall("getCurrentAgentSession"))
            val kind = if (result is ApiResult.LocalReject) "LocalReject:" + result.reason else result.javaClass.simpleName
            return kind to other.recorder().requests.size
        }
        val apiSwitch = run(streamMode = false)
        listOf(api, stream, other).forEach { it.recorder().requests.clear() }
        val streamSwitch = run(streamMode = true)
        // 底版口径（只换基址、不推进环境世代）：重试按新基址重新准备；stream 基址变化必须得到同样的结果与同样的落点次数
        assertEquals("stream 基址变化与 api 基址变化结果一致", apiSwitch, streamSwitch)
    }

    private companion object {
        const val BODY = """{"client_msg_id":"Previous_01234567","text":"提现记录在哪看"}"""
        const val SSE_TEXT = ": ping\n\nevent: meta\nid: 1\ndata: {\"session_id\":\"s\",\"run_id\":\"r\",\"message_id\":\"m\"}\n\n"
        const val WAIT = 10L

        fun ok() =
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"code":0,"msg":"","data":{},"trace_id":"trace-0123456789"}""")

        fun sse() = MockResponse().setHeader("Content-Type", "text/event-stream; charset=utf-8").setBody(SSE_TEXT)

        fun envelope(
            code: Int,
            status: Int,
        ) = MockResponse()
            .setResponseCode(status)
            .setHeader("Content-Type", "application/json")
            .setBody("""{"code":$code,"msg":"","data":null,"trace_id":"trace-0123456789"}""")

        fun refreshed() =
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"code":0,"msg":"","data":{"access_token":"access-B",""" +
                    """"access_expires_at":"2026-10-02T13:30:00+08:00",""" +
                    """"refresh_token":"refresh-B","refresh_expires_at":"2026-11-01T11:30:00+08:00",""" +
                    """"session_scope":"full"},"trace_id":"t"}""",
            )
    }
}
