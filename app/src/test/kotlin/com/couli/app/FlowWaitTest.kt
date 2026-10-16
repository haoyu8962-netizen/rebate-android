package com.couli.app

import android.app.Application
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiClient
import com.couli.core.network.ApiResult
import com.couli.core.network.ClientIdentity
import com.couli.core.network.DeviceCredentials
import com.couli.core.network.InFlightCalls
import com.couli.core.network.MemoryPendingKeyBackend
import com.couli.core.network.MemorySessionTokens
import com.couli.core.network.NetworkContext
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PrivacyRaw
import com.couli.core.network.UpdateRaw
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * FU-AND-0716 第 1 轮评审 R1-02：「传输已结束、业务回调被挂起」时等待不得提前返回。真实 ApiClient + MockWebServer：响应已读完、网络层已注销在途记录，
 * 但交回业务层之前被挂住（模拟 IO 线程被延迟调度 / 解析之后还有一段工作）——[FlowWait.settled] 必须等到业务回调真正执行完才返回；
 * 对照用例记下 r0 的「网络层空闲 + 主线程空闲两轮」判据在这个窗口里已经成立（会提前返回）。
 * 第 2 轮评审 R2-02：闸门由测试显式放行（观察完、断言完才放），不再用另一线程固定睡 1.5 秒放行；时间上限只用于失败退出。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class FlowWaitTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ApiClient
    private val inFlight = InFlightCalls()
    private val tokens = MemorySessionTokens("access-one", "refresh-one", "full", "user-one")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val arrived = AtomicInteger(0)

    /** 传输层交回之后、业务层拿到结果之前的挂起点。 */
    private val hold = CountDownLatch(1)
    private val released = AtomicBoolean(false)
    private val processed = AtomicBoolean(false)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    arrived.incrementAndGet()
                    return MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"code":0,"msg":"","data":{"user_id":"user-one"},"trace_id":"trace-000001"}""")
                }
            }
        server.start()
        client =
            ApiClient(
                identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.0.0", build = "1", channel = "official"),
                baseUrl = { server.url("/").toString() },
                context = { NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, UpdateRaw.SUPPORTED) },
                device =
                    object : DeviceCredentials {
                        override val deviceId: String = "device-fixture"
                        override val installSecret: String = "install-fixture"
                    },
                tokens = tokens,
                pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
                inFlight = inFlight,
                sleeper = {},
            )
    }

    @After
    fun tearDown() {
        hold.countDown()
        scope.cancel()
        server.shutdown()
    }

    private fun pump() = shadowOf(Looper.getMainLooper()).idle()

    /**
     * 一次业务流程：主线程协程经 [ledger] 包着的端口发 getMe；端口在传输层交回之后挂住，放行后才把结果交给调用方；调用方随后在主线程处理（[processed]）。
     */
    private fun startFlow(ledger: FlowLedger) {
        val call = ApiCall("getMe")
        scope.launch {
            ledger.around(call) {
                val result: ApiResult = client.send(call)
                withContext(Dispatchers.IO) { hold.await(FlowWait.CEILING_MS, TimeUnit.MILLISECONDS) }
                result
            }
            processed.set(true)
        }
    }

    /** 传输层已结束：请求已到服务端，网络层没有在途调用。 */
    private fun transportDone(): Boolean = arrived.get() > 0 && inFlight.size == 0

    /** 显式放行挂起点（R2-02：不再由另一线程固定睡 1.5 秒后放行，由测试在观察完之后主动放行）。 */
    private fun release() {
        released.set(true)
        hold.countDown()
    }

    /**
     * 正向：闸门一直关着，直到等待已经在「传输层已结束」的状态下又轮询了 [RELEASE_AFTER_IDLE_ROUNDS] 轮（阶段信号：此时等待尚未返回、
     * 业务回调未执行、未结算）才由等待自己的 pump 主动放行。任何看「网络层空闲 + 主线程空闲」的判据都会在放行之前返回，于是 released=false。
     */
    @Test
    fun fuand0716_r1_02_theFlowWaitDoesNotReturnBeforeTheHeldBusinessCallbackHasRun() {
        val ledger = FlowLedger()
        startFlow(ledger)
        var idleRounds = 0
        var heldWhileWaiting = false
        val pumpAndRelease = {
            pump()
            if (!released.get() && transportDone()) {
                idleRounds += 1
                if (idleRounds >= RELEASE_AFTER_IDLE_ROUNDS) {
                    heldWhileWaiting = !processed.get() && ledger.settled { true } == 0
                    release()
                }
            }
        }
        val detail = { "inFlight=${inFlight.size} released=${released.get()}" }
        FlowWait.settled(ledger, 1, pumpAndRelease, detail) { it.operationId == "getMe" }
        assertTrue("放行前等待已在传输结束后持续轮询、业务回调未执行、未结算", heldWhileWaiting)
        assertTrue("返回时挂起点已放行", released.get())
        assertTrue("返回时业务回调已执行完", processed.get())
    }

    /**
     * 对照：闸门保持关闭，r0 的判据（网络层没有在途调用 + 主线程队列空，连续两轮）照样成立，而业务回调还没执行——它会提前返回。
     * 观察和断言都在放行之前完成，之后才主动放行并等真正的结算；不依赖任何固定时长。
     */
    @Test
    fun fuand0716_r1_02_theTransportIdleCriterionIsAlreadyMetWhileTheBusinessCallbackIsHeld() {
        val ledger = FlowLedger()
        startFlow(ledger)
        var quietRounds = 0
        FlowWait.until(::pump, { "inFlight=${inFlight.size}" }) {
            val quiet = transportDone() && shadowOf(Looper.getMainLooper()).isIdle
            quietRounds = if (quiet) quietRounds + 1 else 0
            quietRounds >= 2
        }
        assertFalse("闸门还关着", released.get())
        assertFalse("网络层空闲时业务回调仍挂着", processed.get())
        assertEquals("还没有结算", 0, ledger.settled { true })
        release()
        FlowWait.settled(ledger, 1, ::pump) { true }
        assertTrue(processed.get())
    }

    private companion object {
        /** 正向用例在传输层结束后再轮询这么多轮才放行：多于 r0「两轮」判据，r0 判据必在放行前返回。 */
        const val RELEASE_AFTER_IDLE_ROUNDS = 5
    }
}
