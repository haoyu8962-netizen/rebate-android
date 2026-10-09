package com.couli.feature.trade

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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * APP-06b 本地假服务集成测试（任务书 §2.9）：真实 [ApiClient] → MockWebServer（本机，不连任何真实地址）。
 * - 超时后【重试】发出的请求带同一个 Idempotency-Key，服务端按同键返回同一个 attempt_id，外跳的 link_jump 带这个编号；先前那次迟到也不外跳第二次；
 * - 离开页面时在途的 open 被取消（传输层不再等）。
 */
class OpenLinkApiClientIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ApiClient
    private val keys: MutableList<String?> = Collections.synchronizedList(mutableListOf())
    private val release = CountDownLatch(1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val key = request.getHeader("Idempotency-Key")
                    val first = synchronized(keys) { keys.add(key).let { keys.size == 1 } }
                    // 第一次请求一直不回答，直到测试放行（模拟超过 8 秒的慢响应）
                    if (first) release.await(WAIT_S, TimeUnit.SECONDS)
                    val attempt = "attempt-for-${key?.takeLast(KEY_TAIL)}"
                    return MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"code":0,"msg":"","data":${TradeFixtures.openResult(attempt = attempt)},"trace_id":"trace-000654"}""")
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
    fun retryAfterTimeoutSendsTheSameKeyAndGetsTheSameAttempt() =
        runBlocking {
            val ports = FakePorts()
            val events = Collections.synchronizedList(mutableListOf<JsonObject>())
            val controller =
                withContext(scope.coroutineContext) {
                    OpenPurchaseController(
                        api = ApiClientPageApi(client),
                        executor = TradeJumpExecutor(ports, FakeBaichuan(), { events += it }, now = { TradeFixtures.NOW }),
                        ports = ports,
                        env = FakeEnv(loggedIn = false).also { it.card = TradeFixtures.card() },
                        pending = MemoryPurchasePending(),
                        counter = AuthSheetCounter(),
                        tips = JumpTipService(ApiClientPageApi(client)) { null },
                        webCode = FakeWebCode(),
                        baichuan = FakeBaichuan(),
                        scope = scope,
                        timeoutMillis = TEST_TIMEOUT_MS,
                        browserNoticeMillis = 0L,
                    )
                }
            withContext(scope.coroutineContext) { controller.buy(TradeFixtures.card(), null) }
            waitUntil { controller.state.value.phase == PurchasePhase.TIMEOUT }
            assertEquals(1, keys.size)
            withContext(scope.coroutineContext) { controller.retryAfterTimeout() }
            waitUntil { ports.launched.isNotEmpty() }
            assertEquals("两次请求同一个 Idempotency-Key", keys[0], keys[1])
            assertEquals("attempt-for-${keys[0]!!.takeLast(KEY_TAIL)}", events.single()["attempt_id"]!!.jsonPrimitive.content)
            release.countDown()
            delay(SETTLE_MS)
            assertEquals("先前那次迟到的响应不再外跳", 1, ports.launched.size)
        }

    @Test
    fun leavingCancelsTheInFlightOpen() =
        runBlocking {
            val ports = FakePorts()
            val controller =
                withContext(scope.coroutineContext) {
                    OpenPurchaseController(
                        api = ApiClientPageApi(client),
                        executor = TradeJumpExecutor(ports, FakeBaichuan(), {}, now = { TradeFixtures.NOW }),
                        ports = ports,
                        env = FakeEnv(loggedIn = false),
                        pending = MemoryPurchasePending(),
                        counter = AuthSheetCounter(),
                        tips = JumpTipService(ApiClientPageApi(client)) { null },
                        webCode = FakeWebCode(),
                        baichuan = FakeBaichuan(),
                        scope = scope,
                    )
                }
            withContext(scope.coroutineContext) { controller.buy(TradeFixtures.card(), null) }
            waitUntil { client.inFlight.size == 1 }
            withContext(scope.coroutineContext) { controller.leave() }
            waitUntil { client.inFlight.size == 0 }
            release.countDown()
            delay(SETTLE_MS)
            assertTrue(ports.launched.isEmpty())
        }

    private suspend fun waitUntil(condition: () -> Boolean) {
        withTimeout(WAIT_MS) {
            while (!condition()) delay(POLL_MS)
        }
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
        const val TEST_TIMEOUT_MS = 300L
        const val KEY_TAIL = 8
    }
}
