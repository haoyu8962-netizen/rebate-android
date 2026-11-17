package com.couli.core.network

import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** 一次调用的完整断言：实际交给假传输的请求数、逐请求 Authorization、本地拒绝原因、是否成功。 */
internal data class BarrierNotReadableOutcome(
    val requestCount: Int,
    val authorizations: List<String?>,
    val localRejectReason: String?,
    val succeeded: Boolean,
    val signedRequests: Int,
) {
    companion object {
        fun rejected(reason: String) = BarrierNotReadableOutcome(0, emptyList(), reason, false, 0)

        fun sent(
            authorization: String? = null,
            signed: Boolean = false,
        ) = BarrierNotReadableOutcome(1, listOf(authorization), null, true, if (signed) 1 else 0)
    }
}

/**
 * 沿用 ApiStreamTest.localExchangeAnswersOnlyAfterWriteTimeAdmission 的传输接点：answer 在真实写出前核对之后才收到请求。
 * 不使用 sleep、反射或伪造 NotReadable；会话和设备都经过同一份真实 CredentialReadBarrier。
 */
internal class BarrierNotReadableFixture(
    stored: MemorySessionTokens = MemorySessionTokens(ACCESS, "refresh-A", SessionScopeRaw.FULL, ACCOUNT),
    beforeAdmission: (Request) -> Unit = {},
    responseCode: (Request) -> Int = { 0 },
) {
    val tokens = BarrierNotReadableTokens(stored)
    val barrier = CredentialReadBarrier()
    val inFlight = InFlightCalls()
    var state = FULL
        private set
    private val wire = BarrierNotReadableWire(beforeAdmission, responseCode)
    private val device = BarrierNotReadableDevice(barrier) { state }
    val credentialReads: Int get() = tokens.reads + device.reads
    val sentCount: Int get() = wire.requests.size
    val sentRequests: List<Request> get() = wire.requests.toList()
    var lastResult: ApiResult? = null
        private set
    val api =
        ApiClient(
            identity = ClientIdentity("couli", "android", "1.2.0", "1", "official"),
            baseUrl = { "https://barrier-not-readable.invalid/" },
            context = { state },
            device = device,
            tokens = tokens,
            pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
            sleeper = {},
            inFlight = inFlight,
            readBarrier = barrier,
            local = wire,
        )

    fun execute(call: ApiCall): BarrierNotReadableOutcome {
        val before = sentCount
        val result = api.execute(call)
        lastResult = result
        val received = wire.requests.drop(before)
        return BarrierNotReadableOutcome(
            received.size,
            received.map { it.header(HeaderNames.AUTHORIZATION) },
            (result as? ApiResult.LocalReject)?.reason,
            result is ApiResult.Success,
            received.count { it.header(HeaderNames.SIGN) != null },
        )
    }

    /**
     * beforePublish 的第一步同步执行请求：内部闸已关闭，已准入读取已排空，但网络世代作废和外部发布都还没发生。
     * 同步回调只是测试调度点；请求仍完整走 ApiClient → HttpTransport → 写出前核对 → LocalExchange.answer。
     */
    fun inClosedWindow(call: ApiCall): BarrierNotReadableOutcome = inClosedWindow(listOf(call)).single()

    fun inClosedWindow(calls: List<ApiCall>): List<BarrierNotReadableOutcome> {
        val epochs = inFlight.epochs()
        val reads = credentialReads
        var result: List<BarrierNotReadableOutcome>? = null
        barrier.raise(
            beforePublish = {
                try {
                    assertClosedWindow(epochs)
                    result =
                        calls.map { call ->
                            val outcome = execute(call)
                            assertEquals("屏障窗口内会话和设备存储均零新增读取", reads, credentialReads)
                            outcome
                        }
                    assertClosedWindow(epochs)
                    assertEquals("屏障窗口内会话和设备存储均零新增读取", reads, credentialReads)
                } finally {
                    inFlight.onEnvironmentSwitching()
                }
            },
            publish = { state = FULL.copy(environmentSettled = false) },
        )
        assertTrue("raise 返回后内部闸重新打开", barrier.read({ true }, { false }) { true })
        return checkNotNull(result)
    }

    private fun assertClosedWindow(epochs: CallEpochs) {
        assertFalse("真实内部闸已关闭", barrier.read({ true }, { false }) { true })
        assertEquals("网络世代尚未作废", epochs, inFlight.epochs())
        assertEquals("外部仍是完整模式、环境已就绪、撤回清理已完成", FULL, state)
    }

    /** 与原窗口相同的真实 raise；记录读增量，让新增用例先核对发送结局，再断言零读取。 */
    fun <T> observeClosedWindow(action: () -> T): BarrierNotReadableObservation<T> {
        val epochs = inFlight.epochs()
        val reads = credentialReads
        var observed: BarrierNotReadableObservation<T>? = null
        barrier.raise(
            beforePublish = {
                try {
                    assertClosedWindow(epochs)
                    val value = action()
                    assertClosedWindow(epochs)
                    observed = BarrierNotReadableObservation(value, credentialReads - reads)
                } finally {
                    inFlight.onEnvironmentSwitching()
                }
            },
            publish = { state = FULL.copy(environmentSettled = false) },
        )
        assertTrue("raise 返回后内部闸重新打开", barrier.read({ true }, { false }) { true })
        return checkNotNull(observed)
    }

    /** 用例 2 只恢复环境可读，不保存或替换会话；同一 ApiCall 再次执行。 */
    fun settleEnvironment() {
        assertFalse("先完成窗口后的外部发布", state.environmentSettled)
        assertEquals("切换只推进一次环境世代", CallEpochs(0, 1), inFlight.epochs())
        state = FULL
    }

    fun publishAnonymous(next: NetworkContext) {
        barrier.raise(beforePublish = inFlight::onPrivacyRevoked, publish = { state = next })
        assertTrue("已发布状态的对照：内部闸已经打开", barrier.read({ true }, { false }) { true })
        assertEquals(CallEpochs(1, 0), inFlight.epochs())
        assertEquals(next, state)
    }

    companion object {
        const val ACCOUNT = "account-A"
        const val ACCESS = "access-A"
        const val BEARER = "Bearer $ACCESS"
        val FULL = NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, update = UpdateRaw.SUPPORTED)

        fun search() = ApiCall("searchProducts", query = "q=phone&platform=taobao")
    }
}

/** 不把存储伪装成 Unreadable；盘上一直是正常会话，拒读必须来自真实屏障。 */
internal class BarrierNotReadableTokens(
    private val inner: MemorySessionTokens,
) : SessionTokens by inner,
    SessionReader {
    var reads = 0
        private set
    var beforeClear: (() -> Unit)? = null
    var clearAttempts = 0
        private set

    override fun clearIf(expectedGeneration: Long): Boolean {
        clearAttempts += 1
        beforeClear?.invoke()
        return inner.clearIf(expectedGeneration)
    }

    override fun snapshot(): SessionSnapshot {
        reads += 1
        return inner.snapshot()
    }

    override fun readSession(): SessionRead = SessionRead.Present(snapshot())

    override val accessToken: String? get() = snapshot().accessToken
    override val refreshToken: String? get() = snapshot().refreshToken
    override val scope: String? get() = snapshot().scope
    override val userId: String? get() = snapshot().userId
    override val generation: Long get() = snapshot().generation
    override val loginEpoch: Long
        get() {
            reads += 1
            return inner.loginEpoch
        }
}

/** 对照 DeviceRegistry.current：设备标识与签名密钥也由同一屏障现判，不用永远可读的 FixedDevice 绕过屏障。 */
private class BarrierNotReadableDevice(
    private val barrier: CredentialReadBarrier,
    private val context: () -> NetworkContext,
) : DeviceCredentials {
    var reads = 0
        private set

    override val deviceId: String? get() = read("device-fixture")
    override val installSecret: String? get() = read("test-only.install.device-a")

    private fun read(value: String): String? =
        barrier.read(
            allowed = {
                val state = context()
                state.privacy == PrivacyRaw.FULL && state.credentialsReadable && state.environmentSettled
            },
            denied = { null },
            read = {
                reads += 1
                value
            },
        )
}

private class BarrierNotReadableWire(
    private val beforeAdmission: (Request) -> Unit,
    private val responseCode: (Request) -> Int,
) : LocalExchange {
    val requests = mutableListOf<Request>()

    override fun handles(request: Request): Boolean {
        beforeAdmission(request)
        return true
    }

    override fun answer(
        request: Request,
        canceled: () -> Boolean,
    ): Response {
        requests += request
        return Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Type", "application/json")
            .body("""{"code":${responseCode(request)},"msg":"","data":{},"trace_id":"barrier-not-readable"}""".toResponseBody(null))
            .build()
    }
}

internal data class BarrierNotReadableObservation<T>(
    val value: T,
    val newReads: Int,
) {
    fun assertNoReads() {
        assertEquals("屏障窗口内会话、会话世代和设备存储均零新增读取", 0, newReads)
    }
}

/** 卡住真实调用的指定边界；测试线程关闸后才放行，等调用结算完才作废网络世代、发布外部状态。 */
internal class BarrierNotReadablePause {
    private val reached = CountDownLatch(1)
    private val resume = CountDownLatch(1)

    fun stopHere() {
        reached.countDown()
        check(resume.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "未在关闸窗口内恢复调用" }
    }

    fun <T> finishInClosedWindow(
        rig: BarrierNotReadableFixture,
        action: () -> T,
    ): BarrierNotReadableObservation<T> {
        val task = FutureTask<T> { action() }
        val worker = Thread(task, "barrier-not-readable-interleaving").apply { isDaemon = true }
        worker.start()
        return try {
            assertTrue("关闸前已到指定边界", reached.await(WAIT_SECONDS, TimeUnit.SECONDS))
            rig.observeClosedWindow {
                resume.countDown()
                task.get(WAIT_SECONDS, TimeUnit.SECONDS)
            }
        } finally {
            resume.countDown()
            task.cancel(true)
            worker.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
            assertFalse("用例工作线程已退出", worker.isAlive)
        }
    }

    companion object {
        private const val WAIT_SECONDS = 5L
    }
}
