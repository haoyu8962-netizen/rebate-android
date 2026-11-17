package com.couli.core.network

import com.couli.core.generated.ApiOperations
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** review-r2-codex / D10-1948：仅补规则测试，不把预期失败冒充实跑结果。 */
class ApiClientBarrierNotReadableFix3Test {
    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun stop() {
        server.shutdown()
    }

    @Test
    fun clearSessionEnteredAfterBarrierClosesDoesNotReadOrEndSession() {
        val rig = Rig(server)
        val before = rig.stored.snapshot()
        val epoch = rig.stored.loginEpoch
        var ended = 0

        // 清除入口本身在 beforePublish 里才开始，不在 clearIf 内暂停。
        val observed = rig.window { rig.api.clearSessionIf(epoch) { ended += 1 } }
        val after = rig.stored.snapshot()
        val afterEpoch = rig.stored.loginEpoch
        rig.control.settleEnvironment()

        assertEquals("清除入口不发请求", 0, server.requestCount)
        observed.assertNoReads()
        assertEquals("不尝试清原会话", 0, rig.control.tokens.clearAttempts)
        assertEquals(before, after)
        assertEquals(epoch, afterEpoch)
        assertEquals("不执行会话结束回调（Router 收尾的可观测入口）", 0, ended)
        assertTrue("不发身份变更通知", rig.storedEvents.isEmpty())
        rig.assertNoLogin()
        assertEquals(ACCOUNT, rig.api.activeUserId())
    }

    @Test
    fun sessionIdentityRawReadThrowsThenClosedBarrierForbidsFallbackGenerationRead() {
        val rig = Rig(server)
        val entered = CountDownLatch(1)
        val thrown = AtomicBoolean()
        lateinit var identity: FutureTask<SessionIdentity>
        val window =
            FutureTask {
                rig.window {
                    entered.countDown()
                    identity.get(WAIT_SECONDS, TimeUnit.SECONDS)
                }
            }
        val raiser = worker(window, "identity-exception-barrier")
        // 沿用 SessionReader / 委托内存存储替身；先读，再注入异常，盘上始终保留 A。
        rig.tokens.afterRead = {
            raiser.start()
            // raise 先关闸，再等 rawRead 的 active 退出；只探测真实闸，不靠 sleep 猜时序。
            rig.awaitClosed()
            thrown.set(true)
            error("注入 rawRead 读取异常")
        }
        // 只为确定 catch 的非法 getter 与 beforePublish 的先后；修复后不调用它也不会卡住测试。
        rig.tokens.beforeGeneration = { assertTrue(entered.await(WAIT_SECONDS, TimeUnit.SECONDS)) }
        identity = FutureTask { rig.api.sessionIdentity() }
        val reader = worker(identity, "identity-exception-reader")
        reader.start()
        val observed =
            try {
                window.get(WAIT_SECONDS, TimeUnit.SECONDS)
            } finally {
                entered.countDown()
                identity.cancel(true)
                window.cancel(true)
                join(reader)
                join(raiser)
            }

        assertTrue("确实从 rawRead 抛异常，而非伪造 Withheld", thrown.get())
        assertFalse(observed.value.known)
        assertNull(observed.value.account)
        assertNull(observed.value.partition)
        assertEquals(0, server.requestCount)
        observed.assertNoReads()
    }

    @Test
    fun registeredRefreshRejectedBeforePrepareKeepsSessionAndCanRefreshAgain() {
        refreshRecovery(beforePrepare = true)
    }

    @Test
    fun preparedRefreshRejectedBeforeWriteKeepsSessionAndCanRefreshAgain() {
        refreshRecovery(beforePrepare = false)
    }

    @Test
    fun refreshQueueWithheldMatchesUnreadableTransientClassification() {
        // 对照组在同一个入队读点返回真实 SessionRead.Unreadable，不把 Withheld 伪装成它。
        val unreadable = Rig(server)
        server.enqueue(envelope(10002))
        unreadable.atContext("awaitRefreshWindow") { unreadable.tokens.unreadable = true }
        val unreadableBefore = unreadable.stored.snapshot()
        val unreadableEpoch = unreadable.stored.loginEpoch
        val reference = unreadable.api.execute(ApiCall("getMe"))
        assertEquals(transient(LocalRejectReason.SESSION_UNREADABLE), reference)
        assertEquals(1, server.requestCount)
        assertEquals(unreadableBefore, unreadable.stored.snapshot())
        assertEquals(unreadableEpoch, unreadable.stored.loginEpoch)
        assertEquals(0, unreadable.control.tokens.clearAttempts)
        assertEquals("Unreadable 不登记刷新任务", 0, unreadable.api.refreshCoordinator.refreshCalls)
        unreadable.assertNoLogin()

        val rig = Rig(server)
        val before = rig.stored.snapshot()
        val epoch = rig.stored.loginEpoch
        val pause = Pause()
        server.enqueue(envelope(10002))
        // context 的直接调用者必须是入队函数；在 rawRead 登记 active 之前停，避免关闸等待自身。
        rig.atContext("awaitRefreshWindow", pause::stopHere)
        val observed = pause.finish(rig) { rig.api.execute(ApiCall("getMe")) }

        assertEquals("两组各只有触发 10002 的原请求", 2, server.requestCount)
        observed.assertNoReads()
        assertEquals(before, rig.stored.snapshot())
        assertEquals(epoch, rig.stored.loginEpoch)
        assertEquals(0, rig.control.tokens.clearAttempts)
        assertEquals("Withheld 同样不登记刷新任务", 0, rig.api.refreshCoordinator.refreshCalls)
        rig.assertNoLogin()
        assertEquals("Withheld 必须与 Unreadable 同为 session_unreadable 暂时失败，不能落 Stale 通用错误", reference, observed.value)
    }

    @Test
    fun cachedAccountAThenAccountBAndUnknownWindowNeverRestoresSameOwnership() {
        val rig = Rig(server)
        val ownerA = rig.api.sessionIdentity()
        assertTrue(ownerA.known)
        assertEquals(ACCOUNT, ownerA.account)
        rig.stored.save("access-B", "refresh-B", SessionScopeRaw.FULL, "account-B")

        val observed = rig.window { rig.api.sessionIdentity() }
        val unknown = observed.value
        assertFalse(unknown.known)
        assertEquals("窗口内只拿到缓存的 A 世代", ownerA.loginEpoch, unknown.loginEpoch)
        assertEquals(IdentityVerdict.UNKNOWN, unknown.verdictFor(ownerA))
        assertFalse(unknown.isOwnedBy(ACCOUNT, ownerA.loginEpoch))
        observed.assertNoReads()
        rig.control.settleEnvironment()
        val restored = rig.api.sessionIdentity()

        assertEquals("account-B", restored.account)
        assertEquals(IdentityVerdict.CHANGED, restored.verdictFor(ownerA))
        assertEquals("窗口中新建页面拿的未知归属也必须重置", IdentityVerdict.CHANGED, restored.verdictFor(unknown))
        assertFalse(restored.isOwnedBy(ACCOUNT, ownerA.loginEpoch))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun firstIdentityInClosedWindowUsesSentinelAndNeverAuthorizesRecoveredWrite() {
        val rig = Rig(server)
        assertEquals(0, rig.control.tokens.reads)

        val observed = rig.window { rig.api.sessionIdentity() }
        val unknown = observed.value
        assertFalse(unknown.known)
        assertEquals(Long.MIN_VALUE, unknown.loginEpoch)
        assertEquals(IdentityVerdict.UNKNOWN, unknown.verdictFor(unknown))
        assertFalse(unknown.isOwnedBy(ACCOUNT, unknown.loginEpoch))
        observed.assertNoReads()
        rig.control.settleEnvironment()
        val restored = rig.api.sessionIdentity()

        assertTrue(restored.known)
        assertEquals(ACCOUNT, restored.account)
        assertEquals(IdentityVerdict.CHANGED, restored.verdictFor(unknown))
        assertFalse("恢复后也不能用窗口内的旧票据写入", restored.isOwnedBy(ACCOUNT, unknown.loginEpoch))
        assertFalse(restored.isOwnedBy(unknown.account, unknown.loginEpoch))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun unsignedLoginPhoneRealnameKeepEpochAndRecoverSameCallsWithOwnBearer() {
        val rig = Rig(server)
        val before = rig.stored.snapshot()
        val epoch = rig.stored.loginEpoch
        val calls = listOf(ApiCall("getMe"), ApiCall("getMyInvite"), ApiCall("getPayoutAccount"))
        assertEquals(listOf("login", "phone", "realname"), calls.map { ApiOperations.all.getValue(it.operationId).auth })
        calls.forEach {
            assertNull(it.expectedAccount)
            assertFalse(ApiOperations.all.getValue(it.operationId).signed)
        }

        val observed = rig.window { calls.map(rig.api::execute) }

        assertEquals(List(3) { ApiResult.LocalReject(LocalRejectReason.SESSION_UNREADABLE) }, observed.value)
        assertEquals(0, server.requestCount)
        observed.assertNoReads()
        assertEquals(before, rig.stored.snapshot())
        assertEquals(epoch, rig.stored.loginEpoch)
        assertEquals(0, rig.control.tokens.clearAttempts)
        assertTrue(rig.storedEvents.isEmpty())
        rig.assertNoLogin()
        rig.control.settleEnvironment()
        calls.forEachIndexed { index, call ->
            server.enqueue(envelope(0))
            assertTrue("解除屏障后重用同一个 ApiCall", rig.api.execute(call) is ApiResult.Success)
            assertEquals(index + 1, server.requestCount)
            val request = checkNotNull(server.takeRequest(WAIT_SECONDS, TimeUnit.SECONDS))
            assertEquals(ApiOperations.all.getValue(call.operationId).pathTemplate, request.path)
            assertEquals(BEARER, request.getHeader(HeaderNames.AUTHORIZATION))
        }
        assertEquals(before, rig.stored.snapshot())
        assertEquals(epoch, rig.stored.loginEpoch)
        assertEquals(ACCOUNT, rig.api.activeUserId())
        rig.assertNoLogin()
    }

    private fun refreshRecovery(beforePrepare: Boolean) {
        val rig = Rig(server)
        val before = rig.stored.snapshot()
        val epoch = rig.stored.loginEpoch
        val pause = Pause()
        var prepared: Request? = null
        if (beforePrepare) {
            // refreshTokens 直接取 context 时，start 已由 enlist 固定，prepare 尚未调用。
            rig.atContext("refreshTokens", pause::stopHere)
        } else {
            rig.beforeWire = { request ->
                if (request.url.encodedPath == "/v1/auth/refresh" && prepared == null) {
                    prepared = request
                    pause.stopHere()
                }
            }
        }
        server.enqueue(envelope(10002))
        val call = ApiCall("getMe")
        val observed = pause.finish(rig) { rig.api.execute(call) }
        val refreshesAfterWindow = rig.api.refreshCoordinator.refreshCalls
        val afterWindow = rig.stored.snapshot()
        val epochAfterWindow = rig.stored.loginEpoch
        val clearsAfterWindow = rig.control.tokens.clearAttempts
        val countAfterWindow = server.requestCount
        val effectsAfterWindow = rig.effects.toList()
        rig.control.settleEnvironment()
        val accountAfterWindow = rig.api.activeUserId()

        // 红点断言前也执行恢复闭环，避免只证明「仍能读到账号」。不保存、不补写旧会话。
        server.enqueue(envelope(10002))
        server.enqueue(envelope(0, """{"access_token":"access-new","refresh_token":"refresh-new","session_scope":"full"}"""))
        server.enqueue(envelope(0))
        val recovered = rig.api.execute(call)

        assertEquals("窗口内只有原调用写出；刷新和重放均零发送", 1, countAfterWindow)
        assertEquals("关闸时刷新已登记，start 已取得", 1, refreshesAfterWindow)
        assertRefreshBoundary(beforePrepare, rig, prepared)
        val reason = if (beforePrepare) LocalRejectReason.DEVICE_NOT_READY else LocalRejectReason.SESSION_UNREADABLE
        assertEquals(
            "暂时失败、原会话、登录世代、账号和清除次数必须同时保持",
            listOf(transient(reason), before, epoch, ACCOUNT, 0),
            listOf(observed.value, afterWindow, epochAfterWindow, accountAfterWindow, clearsAfterWindow),
        )
        assertTrue("暂时失败不交付跳登录 Effect", effectsAfterWindow.isEmpty())
        observed.assertNoReads()
        assertTrue("同一账号解除屏障后真的再刷新并重放成功：$recovered", recovered is ApiResult.Success)
        assertEquals("暂时失败没有缓存成拒绝，解除后能登记第二次刷新", 2, rig.api.refreshCoordinator.refreshCalls)
        assertEquals("原失败请求 + 恢复原请求 + 刷新 + 重放", 4, server.requestCount)
        val requests = List(4) { checkNotNull(server.takeRequest(WAIT_SECONDS, TimeUnit.SECONDS)) }
        assertEquals(listOf("/v1/me", "/v1/me", "/v1/auth/refresh", "/v1/me"), requests.map { it.path })
        assertEquals(BEARER, requests[0].getHeader(HeaderNames.AUTHORIZATION))
        assertEquals(BEARER, requests[1].getHeader(HeaderNames.AUTHORIZATION))
        assertTrue(requests[2].body.readUtf8().contains("refresh-A"))
        assertEquals("Bearer access-new", requests[3].getHeader(HeaderNames.AUTHORIZATION))
        assertEquals(
            before.copy(accessToken = "access-new", refreshToken = "refresh-new", generation = before.generation + 1),
            rig.stored.snapshot(),
        )
        assertEquals("刷新不推进登录世代", epoch, rig.stored.loginEpoch)
        assertEquals(0, rig.control.tokens.clearAttempts)
        rig.assertNoLogin()
    }

    private fun assertRefreshBoundary(
        beforePrepare: Boolean,
        rig: Rig,
        prepared: Request?,
    ) {
        if (beforePrepare) {
            assertTrue("设备读取确实被同一真实屏障拒绝", rig.deviceDenials > 0)
        } else {
            assertEquals("POST", checkNotNull(prepared).method)
            assertTrue(checkNotNull(prepared).header(HeaderNames.SIGN) != null)
        }
    }

    /** 重用已有真实窗口与计数替身；只有传输换成 MockWebServer，并加只读调度观察点。 */
    private class Rig(
        server: MockWebServer,
    ) {
        val stored = MemorySessionTokens("access-A", "refresh-A", SessionScopeRaw.FULL, ACCOUNT)
        val control = BarrierNotReadableFixture(stored)
        val tokens = ReadProbe(control.tokens)
        val effects = mutableListOf<ErrorEffect>()
        val storedEvents = mutableListOf<Long>()
        var beforeWire: (Request) -> Unit = {}
        private var contextHook: () -> Unit = {}
        private var deviceReads = 0
        var deviceDenials = 0
            private set
        private val device =
            object : DeviceCredentials {
                override val deviceId: String? get() = read("device-fixture")
                override val installSecret: String? get() = read("test-only.install.device-a")

                private fun read(value: String): String? =
                    control.barrier.read(
                        allowed = { control.state == BarrierNotReadableFixture.FULL },
                        denied = {
                            deviceDenials += 1
                            null
                        },
                        read = {
                            deviceReads += 1
                            value
                        },
                    )
            }
        val api =
            ApiClient(
                identity = ClientIdentity("couli", "android", "1.2.0", "1", "official"),
                baseUrl = { server.url("/").toString() },
                context = {
                    contextHook()
                    control.state
                },
                device = device,
                tokens = tokens,
                pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
                hooks = NetworkHooks(onEffect = { effects += it }, onSessionStored = { storedEvents += it }),
                sleeper = {},
                inFlight = control.inFlight,
                readBarrier = control.barrier,
                local =
                    object : LocalExchange {
                        override fun handles(request: Request): Boolean {
                            beforeWire(request)
                            return false // 不认领请求，继续真实 OkHttp 写出前核对与 MockWebServer。
                        }

                        override fun answer(
                            request: Request,
                            canceled: () -> Boolean,
                        ): Response? = null
                    },
            )

        fun atContext(
            method: String,
            action: () -> Unit,
        ) {
            val armed = AtomicBoolean(true)
            contextHook = {
                // 与 SendGateIdentityGapsTest 的栈观察装配一致；不反射调用私有方法、不猜读取次数。
                val caller = Thread.currentThread().stackTrace.firstOrNull { it.className == ApiClient::class.java.name }
                if (caller?.methodName == method && armed.compareAndSet(true, false)) action()
            }
        }

        fun <T> window(action: () -> T): Window<T> {
            val generations = tokens.generationReads
            val epochs = tokens.epochReads
            val devices = deviceReads
            val observed = control.observeClosedWindow(action)
            return Window(observed, tokens.generationReads - generations, tokens.epochReads - epochs, deviceReads - devices)
        }

        fun awaitClosed() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
            while (control.barrier.read({ true }, { false }) { true }) {
                check(System.nanoTime() < deadline) { "真实屏障未关闭" }
                Thread.yield()
            }
        }

        fun assertNoLogin() {
            assertFalse(
                effects.any {
                    it.kind == ErrorEffectKind.LOGIN ||
                        it.kind == ErrorEffectKind.CLEAR_SESSION_LOGIN ||
                        it.kind == ErrorEffectKind.CLEAR_SESSION_STAY_FORCE_UPDATE
                },
            )
            assertTrue("没有登录或其他跳转 Effect", effects.all { it.route == null })
        }
    }

    /** 复用 BarrierNotReadableTokens 的读取计数与清除计数，另外拆开两种世代读增量。 */
    private class ReadProbe(
        private val backing: BarrierNotReadableTokens,
    ) : SessionTokens by backing,
        SessionReader {
        var generationReads = 0
        var epochReads = 0
        var afterRead: () -> Unit = {}
        var beforeGeneration: () -> Unit = {}
        var unreadable = false

        override fun readSession(): SessionRead {
            val snapshot = backing.snapshot()
            afterRead()
            return if (unreadable) SessionRead.Unreadable(snapshot.generation) else SessionRead.Present(snapshot)
        }

        override val generation: Long
            get() {
                beforeGeneration()
                generationReads += 1
                return backing.generation
            }
        override val loginEpoch: Long
            get() {
                epochReads += 1
                return backing.loginEpoch
            }
    }

    private data class Window<T>(
        val observed: BarrierNotReadableObservation<T>,
        val generationReads: Int,
        val epochReads: Int,
        val deviceReads: Int,
    ) {
        val value: T get() = observed.value

        fun assertNoReads() {
            assertEquals("窗口内 generation / loginEpoch / 设备读增量", listOf(0, 0, 0), listOf(generationReads, epochReads, deviceReads))
            observed.assertNoReads()
        }
    }

    /** 与 BarrierNotReadablePause 相同的双闩，不在已登记的屏障 read 内暂停。 */
    private class Pause {
        private val reached = CountDownLatch(1)
        private val resume = CountDownLatch(1)

        fun stopHere() {
            reached.countDown()
            check(resume.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "未恢复关闸边界" }
        }

        fun <T> finish(
            rig: Rig,
            action: () -> T,
        ): Window<T> {
            val task = FutureTask<T> { action() }
            val thread = worker(task, "barrier-fix3-call")
            thread.start()
            return try {
                assertTrue("关闸前已到指定边界", reached.await(WAIT_SECONDS, TimeUnit.SECONDS))
                rig.window {
                    resume.countDown()
                    task.get(WAIT_SECONDS, TimeUnit.SECONDS)
                }
            } finally {
                resume.countDown()
                task.cancel(true)
                join(thread)
            }
        }
    }

    companion object {
        private const val WAIT_SECONDS = 5L
        private const val ACCOUNT = BarrierNotReadableFixture.ACCOUNT
        private const val BEARER = BarrierNotReadableFixture.BEARER

        private fun worker(
            task: Runnable,
            name: String,
        ) = Thread(task, name).apply { isDaemon = true }

        private fun join(thread: Thread) {
            thread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
            assertFalse("测试工作线程必须退出", thread.isAlive)
        }

        private fun transient(reason: String) = ApiResult.TransportFailure(reason, null, null, false)

        private fun envelope(
            code: Int,
            data: String = "{}",
        ) = MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("""{"code":$code,"msg":"","data":$data,"trace_id":"barrier-fix3"}""")
    }
}
