package com.couli.core.network

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ErrorCollector

/**
 * D10-2005 ④ / D10-2006：屏障窗口内（内部闸已关、网络世代未作废、外部未发布）的双重失败——刷新回包成功、replaceIf 抛错、隔离删除（clearIf）也抛错。
 * 窗口外的同一分支由 Fix11 钉住；这里钉窗口内的现状四点：
 * ① 原调用拿到通用错误（10002，GENERIC_ERROR），不带世代、不跳登录；
 * ② 窗口内零发送、零凭据读取，没有任何请求带旧令牌或以原账号身份发出；
 * ③ 窗口发布后清理器（AppModule 注册的 tokens.clear()）出手，网络层按游客交回（D10-2008：界面同步由 App 层负责，网络层这条路径不发通知、不钉机制）；
 * ④ 原调用的结算拖过网络世代作废时，原调用拿到 call_cancelled。
 * 装配与 [BarrierNotReadableFix8Fixture] 相同（真实屏障、发布窗口、计数存储、LocalExchange 写出后接点），只多一个 context 接点：④ 要让原调用线程
 * 在刷新结算之后、核对网络世代之前停住。
 */
class ApiClientBarrierNotReadableFix12Test {
    @get:Rule
    val failures = ErrorCollector()

    @Test
    fun doubleFailureInClosedWindowGivesGenericErrorWithoutEpochOrLoginAndSendsNothing() {
        val rig = Fix12Rig()
        val before = rig.stored.snapshot()
        val epoch = rig.stored.loginEpoch
        val window = doubleFailureInWindow(rig)
        val result = window.result

        // ① 原调用：通用错误、不带世代、不跳登录
        assertTrue("原调用应交回业务失败：$result", result is ApiResult.Failure)
        result as ApiResult.Failure
        assertEquals(10002, result.code)
        assertEquals("fix12", result.traceId)
        assertEquals("窗口内双重失败按未知世代，原调用拿通用错误", ErrorEffectKind.GENERIC_ERROR, result.effect.kind)
        assertNull("不带世代", result.effect.sessionEpoch)
        assertNull("不跳登录", result.effect.route)
        assertEquals(ReplayMode.NONE, result.effect.replay)
        assertFalse(result.effect.registerPending)
        assertTrue("通用错误带 trace6，可提示", result.effect.includeTrace6)
        assertEquals("界面只收到这一条通用错误 Effect", listOf(result.effect), window.effects)
        assertEquals("窗口内没有任何会话通知", emptyList<Long>(), window.events)
        assertEquals("成功刷新回包确实到达替换存储的失败点", 1, window.replaces)
        assertTrue("刷新结算的清理也走到了 clearIf 并抛错（双重失败）", window.clears >= 1)
        assertFalse("窗口内身份按暂时未知", window.identityKnown)
        assertEquals("失败写入没有改盘上的旧会话", before, rig.stored.snapshot())
        assertEquals(epoch, rig.stored.loginEpoch)
        assertEquals("双重失败不会另存任何会话", 0, rig.tokens.saveAttempts.get())

        // ② 窗口内零发送：只有关闸前发出的两条；固定原账号与不固定账号的后续调用都本地拒、不发
        assertWindowSentNothing(rig, window)
        failures.checkSucceeds { window.observed.assertNoReads() }
        failures.checkSucceeds { assertEquals("窗口内 generation / loginEpoch 零读取", listOf(0, 0), window.epochReadDelta) }

        // 发布之后（还没跑清理器）仍没有补发会话通知或别的 Effect
        assertEquals(CallEpochs(0, 1), rig.control.inFlight.epochs())
        assertEquals(emptyList<Long>(), rig.events.toList())
        assertEquals(listOf(result.effect), rig.effects.toList())
    }

    @Test
    fun cleanerAfterWindowPublishMovesUiToGuest() {
        val rig = Fix12Rig()
        val before = rig.stored.snapshot()
        val window = doubleFailureInWindow(rig)
        assertTrue("前提：原调用已在窗口内结束：${window.result}", window.result is ApiResult.Failure)
        assertEquals("前提：窗口内无会话通知", emptyList<Long>(), window.events)
        assertEquals("窗口已发布：环境切换中，网络世代已作废", BarrierNotReadableFixture.FULL.copy(environmentSettled = false), rig.control.state)
        val effects = rig.effects.toList()

        // ③ 清理器：AppModule 切环境 / 撤回同意注册的会话令牌清理项都是 tokens.clear()（在途作废已由窗口的 onEnvironmentSwitching 完成）
        rig.tokens.clear()
        assertEquals("清理器清空了盘上旧会话", SessionSnapshot(null, null, null, null, before.generation + 1), rig.stored.snapshot())
        rig.control.settleEnvironment()

        val identity = rig.api.sessionIdentity()
        failures.checkSucceeds { assertTrue("清理后身份已知", identity.known) }
        failures.checkSucceeds { assertNull("清理后没有账号", identity.account) }
        failures.checkSucceeds { assertNull("AppLogin.sessionState 据此判为 GUEST", rig.api.activeSessionScope()) }
        failures.checkSucceeds {
            assertEquals(
                "固定原账号的请求本地拒、零发送",
                ApiResult.LocalReject(LocalRejectReason.ACCOUNT_MISMATCH),
                rig.api.execute(ApiCall("getMe", expectedAccount = BarrierNotReadableFixture.ACCOUNT)),
            )
        }
        failures.checkSucceeds { assertEquals("清理后没有新增请求", 2, rig.requests.size) }
        failures.checkSucceeds { assertEquals("清理不另造 Effect", effects, rig.effects.toList()) }
    }

    @Test
    fun settlementDraggedPastNetworkEpochInvalidationEndsOriginalCallAsCallCancelled() {
        val rig = Fix12Rig()
        val before = rig.stored.snapshot()
        val failure = arm(rig)
        val settled = AtomicBoolean(false)
        rig.tokens.beforeClear = {
            // 刷新结算的 clearIf：此时刷新已在窗口内失败，结算之后才唤醒原调用
            settled.set(true)
            error("clearIf 隔离删除持续失败，包括刷新协调器结算")
        }
        val reached = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val task = FutureTask { rig.api.execute(ApiCall("getMe")) }
        val worker = Thread(task, "fix12-original-call").apply { isDaemon = true }
        val paused = AtomicBoolean(false)
        rig.onContext = {
            // 原调用醒来后第一次取状态（refreshAfter → outcomeFor），在 cancelled(logical) 核对之前停住
            if (Thread.currentThread() === worker && settled.get() && paused.compareAndSet(false, true)) {
                reached.countDown()
                check(resume.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "未恢复原调用" }
            }
        }
        worker.start()
        try {
            assertTrue("关闸前已停在 replaceIf", failure.reached.await(WAIT_SECONDS, TimeUnit.SECONDS))
            val epochReads = rig.tokens.readCounts()
            val observed =
                rig.control.observeClosedWindow {
                    failure.resume.countDown()
                    assertTrue("刷新已在窗口内结算，原调用停在核对网络世代之前", reached.await(WAIT_SECONDS, TimeUnit.SECONDS))
                    assertFalse("原调用此时还没结束", task.isDone)
                    Triple(rig.events.toList(), rig.effects.toList(), rig.requests.size)
                }
            failures.checkSucceeds { observed.assertNoReads() }
            failures.checkSucceeds { assertEquals(listOf(0, 0), rig.tokens.readCounts().zip(epochReads) { a, b -> a - b }) }
            assertEquals("窗口内结算没有会话通知", emptyList<Long>(), observed.value.first)
            assertEquals("窗口内结算没有 Effect", emptyList<ErrorEffect>(), observed.value.second)
            assertEquals("窗口内零发送", 2, observed.value.third)
            assertEquals("网络世代已作废", CallEpochs(0, 1), rig.control.inFlight.epochs())

            resume.countDown()
            val result = task.get(WAIT_SECONDS, TimeUnit.SECONDS)
            assertEquals(
                "结算拖过网络世代作废：原调用拿 call_cancelled",
                ApiResult.LocalReject(LocalRejectReason.CALL_CANCELLED),
                result,
            )
            assertEquals("call_cancelled 不输出会话类或通用错误 Effect", emptyList<ErrorEffect>(), rig.effects.toList())
            assertEquals(emptyList<Long>(), rig.events.toList())
            assertEquals(2, rig.requests.size)
            assertEquals(before, rig.stored.snapshot())
        } finally {
            failure.resume.countDown()
            resume.countDown()
            task.cancel(true)
            worker.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
            assertFalse("用例工作线程已退出", worker.isAlive)
        }
    }

    /** 刷新线程停在 replaceIf（关闸前），放行后 replaceIf 抛错；clearIf 一直抛错。 */
    private fun arm(rig: Fix12Rig): ReplaceFailure {
        val failure = ReplaceFailure()
        rig.answer = { request ->
            if (request.url.encodedPath.endsWith("/refresh")) 0 to BarrierNotReadableFix8Fixture.PAIR else 10002 to "{}"
        }
        rig.tokens.beforeReplace = {
            failure.replaces.incrementAndGet()
            failure.reached.countDown()
            check(failure.resume.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "未在关闸窗口内恢复刷新" }
            error("replaceIf 写盘失败，未写入新令牌")
        }
        rig.tokens.beforeClear = { error("clearIf 隔离删除持续失败，包括刷新协调器结算") }
        return failure
    }

    /** 原调用 getMe 在关闸前发出、触发刷新；刷新停在 replaceIf；关闸后放行，原调用与两条后续调用都在窗口内结束。 */
    private fun doubleFailureInWindow(rig: Fix12Rig): InWindow {
        val failure = arm(rig)
        val task = FutureTask { rig.api.execute(ApiCall("getMe")) }
        val worker = Thread(task, "fix12-original-call").apply { isDaemon = true }
        worker.start()
        try {
            assertTrue("关闸前已停在 replaceIf", failure.reached.await(WAIT_SECONDS, TimeUnit.SECONDS))
            val sentAtClose = rig.requests.toList()
            val epochReads = rig.tokens.readCounts()
            val observed =
                rig.control.observeClosedWindow {
                    failure.resume.countDown()
                    val result = task.get(WAIT_SECONDS, TimeUnit.SECONDS)
                    val events = rig.events.toList()
                    val effects = rig.effects.toList()
                    val identityKnown = rig.api.sessionIdentity().known
                    val pinned = rig.api.execute(ApiCall("getMe", expectedAccount = BarrierNotReadableFixture.ACCOUNT))
                    val anonymous = rig.api.execute(ApiCall("getMe"))
                    WindowValues(result, events, effects, identityKnown, pinned, anonymous, rig.requests.toList())
                }
            val delta = rig.tokens.readCounts().zip(epochReads) { a, b -> a - b }
            return InWindow(observed, sentAtClose, failure.replaces.get(), rig.tokens.clearAttempts.get(), delta)
        } finally {
            failure.resume.countDown()
            task.cancel(true)
            worker.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
            assertFalse("用例工作线程已退出", worker.isAlive)
        }
    }

    private fun assertWindowSentNothing(
        rig: Fix12Rig,
        window: InWindow,
    ) {
        assertEquals("关闸前只发出原调用和刷新", listOf("/v1/me", "/v1/auth/refresh"), window.sentAtClose.map { it.url.encodedPath })
        assertEquals(
            "只有关闸前的原调用带旧令牌，刷新不带 Bearer",
            listOf(BarrierNotReadableFixture.BEARER, null),
            window.sentAtClose.map { it.header(HeaderNames.AUTHORIZATION) },
        )
        assertEquals("窗口内零发送", window.sentAtClose, window.observed.value.sentAtEnd)
        assertTrue("固定原账号的调用窗口内本地拒：${window.pinned}", window.pinned is ApiResult.LocalReject)
        assertTrue("不固定账号的调用窗口内本地拒：${window.anonymous}", window.anonymous is ApiResult.LocalReject)
        assertEquals("发布之后也没有补发", window.sentAtClose, rig.requests.toList())
    }

    private class ReplaceFailure {
        val reached = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val replaces = AtomicInteger()
    }

    private data class WindowValues(
        val result: ApiResult,
        val events: List<Long>,
        val effects: List<ErrorEffect>,
        val identityKnown: Boolean,
        val pinned: ApiResult,
        val anonymous: ApiResult,
        val sentAtEnd: List<Request>,
    )

    private class InWindow(
        val observed: BarrierNotReadableObservation<WindowValues>,
        val sentAtClose: List<Request>,
        val replaces: Int,
        val clears: Int,
        val epochReadDelta: List<Int>,
    ) {
        val result: ApiResult get() = observed.value.result
        val events: List<Long> get() = observed.value.events
        val effects: List<ErrorEffect> get() = observed.value.effects
        val identityKnown: Boolean get() = observed.value.identityKnown
        val pinned: ApiResult get() = observed.value.pinned
        val anonymous: ApiResult get() = observed.value.anonymous
    }

    companion object {
        private const val WAIT_SECONDS = 5L
    }
}

/** 与 [BarrierNotReadableFix8Fixture] 同一装配；context 多一个接点 [onContext]（只在 ④ 用来让原调用线程停在指定位置）。 */
private class Fix12Rig {
    val stored = MemorySessionTokens("access-A", "refresh-A", SessionScopeRaw.FULL, BarrierNotReadableFixture.ACCOUNT)
    val control = BarrierNotReadableFixture(stored)
    val tokens = BarrierNotReadableFix8Tokens(control.tokens)
    val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())
    val events: MutableList<Long> = Collections.synchronizedList(mutableListOf())
    val effects: MutableList<ErrorEffect> = Collections.synchronizedList(mutableListOf())

    @Volatile
    var answer: (Request) -> Pair<Int, String> = { 0 to "{}" }

    @Volatile
    var onContext: () -> Unit = {}
    val api =
        ApiClient(
            identity = ClientIdentity("couli", "android", "1.2.0", "1", "official"),
            baseUrl = { "https://barrier-not-readable.invalid/" },
            context = {
                onContext()
                control.state
            },
            device =
                object : DeviceCredentials {
                    override val deviceId: String? get() = read("device-fixture")
                    override val installSecret: String? get() = read("test-only.install.device-a")

                    private fun read(value: String): String? = control.barrier.read({ true }, { null }) { value }
                },
            tokens = tokens,
            pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
            hooks = NetworkHooks(onSessionStored = { events += it }, onEffect = { effects += it }),
            sleeper = {},
            inFlight = control.inFlight,
            readBarrier = control.barrier,
            local =
                object : LocalExchange {
                    override fun handles(request: Request): Boolean = true

                    override fun answer(
                        request: Request,
                        canceled: () -> Boolean,
                    ): Response {
                        requests += request
                        val (code, data) = answer(request)
                        return Response
                            .Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("""{"code":$code,"msg":"","data":$data,"trace_id":"fix12"}""".toResponseBody(null))
                            .build()
                    }
                },
        )
}
