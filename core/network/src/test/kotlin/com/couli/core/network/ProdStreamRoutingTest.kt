package com.couli.core.network

import com.couli.core.config.AppEnvironment
import com.couli.core.config.EnvironmentEndpointsCatalog
import com.couli.core.config.ProdEndpoints
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
 * FU-X-STAGING-HOSTS-android 测试 v3（D10-598 ①）：PROD 的流式基址取自 ProdEndpoints / 环境表，恒为 null；按 App 接线的同一口径
 * （streamBaseUrl = PROD 的值）构造 ApiClient，含 `/v1/agent/` 在内的全部请求都发 api 基址，与底版一致。
 */
class ProdStreamRoutingTest {
    private lateinit var api: MockWebServer
    private val requests = CopyOnWriteArrayList<String>()

    @Before
    fun start() {
        api = MockWebServer().apply { start() }
        api.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request.method + " " + request.path
                    val sse = request.method == "POST" && request.path!!.endsWith("/messages")
                    return if (sse) {
                        MockResponse().setHeader("Content-Type", "text/event-stream").setBody(": ping\n\n")
                    } else {
                        MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setBody("""{"code":0,"msg":"","data":{},"trace_id":"trace-0123456789"}""")
                    }
                }
            }
    }

    @After
    fun stop() = api.shutdown()

    private class FixedDevice : DeviceCredentials {
        override val deviceId: String = "device-fixture"
        override val installSecret: String = "test-only.install.device-a"
    }

    @Test fun prodStreamBaseIsNullEverywhere() {
        assertNull(ProdEndpoints.endpoints.streamBaseUrl)
        assertNull(EnvironmentEndpointsCatalog.endpoints(AppEnvironment.PROD).streamBaseUrl)
    }

    @Test fun withTheProdStreamBaseEveryRequestGoesToTheApiBase() {
        val client =
            ApiClient(
                identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.2.0", build = "1", channel = "official"),
                baseUrl = { api.url("/").toString() },
                streamBaseUrl = { EnvironmentEndpointsCatalog.endpoints(AppEnvironment.PROD).streamBaseUrl },
                context = {
                    NetworkContext(
                        PrivacyRaw.FULL,
                        environmentSettled = true,
                        credentialsReadable = true,
                        update = UpdateRaw.SUPPORTED,
                    )
                },
                device = FixedDevice(),
                tokens = MemorySessionTokens("access-A", "refresh-A", SessionScopeRaw.FULL, userId = "account-A"),
                pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
                sleeper = {},
            )
        val calls =
            listOf(
                ApiCall("createAgentSession", body = "{}"),
                ApiCall("getCurrentAgentSession"),
                ApiCall("listAgentSessionMessages", pathParams = mapOf("id" to "s-1")),
                ApiCall("cancelAgentRun", pathParams = mapOf("run_id" to "r-1"), singleShot = true),
                ApiCall("setAgentMessageFeedback", pathParams = mapOf("message_id" to "m-1"), body = "{}"),
                ApiCall("reportAgentMessage", pathParams = mapOf("message_id" to "m-1"), body = "{}"),
                ApiCall("getConfig"),
            )
        calls.forEach { assertTrue(it.operationId, client.execute(it) is ApiResult.Success) }
        val done = CountDownLatch(1)
        client.stream(
            ApiStreamRequest("sendAgentMessage", mapOf("id" to "s-1"), """{"client_msg_id":"Previous_01234567","text":"x"}""", null),
            object : ApiStreamSink {
                override fun onHeaders(
                    status: Int,
                    headers: Map<String, String>,
                ) = Unit

                override fun onBytes(chunk: ByteArray) = Unit

                override fun onEnd() = done.countDown()

                override fun onFailure(kind: String) = done.countDown()

                override fun onEnvelope(result: ApiResult) = done.countDown()
            },
        )
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertEquals(
            listOf(
                "POST /v1/agent/sessions",
                "GET /v1/agent/sessions/current",
                "GET /v1/agent/sessions/s-1/messages",
                "POST /v1/agent/runs/r-1/cancel",
                "POST /v1/agent/messages/m-1/feedback",
                "POST /v1/agent/messages/m-1/report",
                "GET /v1/config",
                "POST /v1/agent/sessions/s-1/messages",
            ),
            requests.toList(),
        )
    }
}
