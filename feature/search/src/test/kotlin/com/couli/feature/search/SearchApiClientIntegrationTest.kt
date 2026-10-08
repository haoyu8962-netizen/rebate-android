package com.couli.feature.search

import com.couli.core.network.ApiClient
import com.couli.core.network.ApiClientPageApi
import com.couli.core.network.ClientIdentity
import com.couli.core.network.DeviceCredentials
import com.couli.core.network.MemoryPendingKeyBackend
import com.couli.core.network.MemorySessionTokens
import com.couli.core.network.NetworkContext
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PrivacyRaw
import com.couli.core.network.UpdateRaw
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
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
 * APP-06a 本地假服务集成测试（任务书 §2.5）：真实 [ApiClient] → MockWebServer（本机，不连任何真实地址）。
 * 换 Tab 时旧请求经归属作废并取消传输（还在等服务端回答的那次不再等），新 Tab 的结果正常展示；离开页面后在途请求同样取消、不改界面。
 */
class SearchApiClientIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ApiClient
    private val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())
    private val release = CountDownLatch(1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    val platform = request.requestUrl?.queryParameter("platform")
                    // 淘宝那次一直不回答，直到测试放行（模拟慢响应）
                    if (platform == "taobao") release.await(WAIT_S, TimeUnit.SECONDS)
                    return envelope(page(platform ?: "x"))
                }
            }
        server.start()
        client =
            ApiClient(
                identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.0.0", build = "1", channel = "official"),
                baseUrl = { server.url("/").toString() },
                context = {
                    NetworkContext(
                        PrivacyRaw.FULL,
                        environmentSettled = true,
                        credentialsReadable = true,
                        update = UpdateRaw.SUPPORTED,
                    )
                },
                device = FixtureDevice,
                tokens = MemorySessionTokens(),
                pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
                sleeper = {},
            )
    }

    @After
    fun stop() {
        release.countDown()
        scope.cancel()
        server.shutdown()
    }

    @Test
    fun tabChangeCancelsTheSlowOldRequestAndShowsOnlyTheNewTab() =
        runBlocking {
            val controller = withContext(scope.coroutineContext) { SearchController(ApiClientPageApi(client), Config, scope) }
            withContext(scope.coroutineContext) { controller.open("牛奶", "taobao") }
            waitUntil { requests.size == 1 }
            assertTrue(requests[0].requestUrl!!.encodedQuery!!.startsWith("q=%E7%89%9B%E5%A5%B6&platform=taobao&sort=relevance"))
            withContext(scope.coroutineContext) { controller.selectTab("jd") }
            withTimeout(WAIT_MS) { controller.state.first { it.phase == SearchPhase.CONTENT } }
            assertEquals(
                listOf("jd:1"),
                controller.state.value.items
                    .map { it.string("product_key") },
            )
            release.countDown()
            delay(SETTLE_MS)
            assertEquals(
                "淘宝的迟到响应不覆盖京东结果",
                listOf("jd:1"),
                controller.state.value.items
                    .map { it.string("product_key") },
            )
            waitUntil { client.inFlight.size == 0 }
        }

    @Test
    fun leavingCancelsTheInFlightRequestAndTheStateStaysPut() =
        runBlocking {
            val controller = withContext(scope.coroutineContext) { SearchController(ApiClientPageApi(client), Config, scope) }
            withContext(scope.coroutineContext) { controller.open("牛奶", "taobao") }
            waitUntil { client.inFlight.size == 1 }
            val before = controller.state.value
            withContext(scope.coroutineContext) { controller.leave() }
            waitUntil { client.inFlight.size == 0 }
            release.countDown()
            delay(SETTLE_MS)
            assertEquals(before, controller.state.value)
        }

    private suspend fun waitUntil(condition: () -> Boolean) {
        withTimeout(WAIT_MS) {
            while (!condition()) delay(POLL_MS)
        }
    }

    private object Config : SearchConfigPort {
        override fun statusLayers(): JsonObject = Json.parseToJsonElement("""{"current":{"taobao":"on","jd":"on"}}""").jsonObject

        override fun refresh() = Unit

        override fun issuedMark(): Long = 0

        override fun appliedMark(): Long = 0

        override fun subscribe(listener: () -> Unit): () -> Unit = {}
    }

    private object FixtureDevice : DeviceCredentials {
        override val deviceId: String = "device-fixture"
        override val installSecret: String = "install-fixture"
    }

    private companion object {
        const val WAIT_S = 10L
        const val WAIT_MS = 10_000L
        const val SETTLE_MS = 300L
        const val POLL_MS = 20L

        fun envelope(data: String) =
            MockResponse()
                .setHeader(
                    "Content-Type",
                    "application/json",
                ).setBody("""{"code":0,"msg":"","data":$data,"trace_id":"trace-000789"}""")

        fun page(platform: String): String {
            val key = if (platform == "jd") "jd:1" else "tb:1"
            return """{"items":[{"product_key":"$key","platform":"$platform","title":"t","price_fen":3990,"coupon_fen":0,""" +
                """"final_price_fen":3990,"rebate_min_fen":100,"rebate_max_fen":100,"rebate_basis":"normal",""" +
                """"cta":{"text_key":"btn.buy"},"stale":false,"age_sec":1,"availability":"ok"}],""" +
                """"next_cursor":null,"has_more":false,"fallback_items":[]}"""
        }
    }
}
