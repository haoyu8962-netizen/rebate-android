package com.couli.core.router

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android 评审第 1 轮（android-review-r1.md）R1-02～R1-07 的回归用例。 */
class RouterReviewFixesTest {
    private val empty = JsonObject(emptyMap())

    private class RecordingNavigator : RouteNavigator {
        val events = mutableListOf<String>()

        override fun open(
            route: String,
            params: JsonObject,
            readonly: Boolean,
        ) {
            events += "open:$route:${if (readonly) "ro" else "rw"}"
        }

        override fun showUpgradePrompt(request: RouteRequest) {
            events += "upgrade:${request.route}"
        }

        override fun requestConsent(request: RouteRequest) {
            events += "consent:${request.route}"
        }

        override fun blocked(
            request: RouteRequest,
            decision: GuardDecision,
        ) {
            events += "blocked:${request.route}:${decision.reason}"
        }
    }

    private class MutableState(
        var privacy: PrivacyState = PrivacyState.CONSENTED,
        var update: UpdateState = UpdateState.OK,
        var session: SessionState = SessionState.GUEST,
        var settled: Boolean = true,
        var realname: Boolean = false,
    ) {
        fun guard() =
            GuardState(
                privacy = privacy,
                update = update,
                session = session,
                environmentSettled = settled,
                appVersion = "1.0.0",
                isDebugBuild = false,
                realnameVerified = realname,
            )
    }

    private fun params(vararg pairs: Pair<String, String>) = JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })

    // ---------- R1-02：敏感动作不登记；只存通过 schema 的参数 ----------

    @Test
    fun sensitiveRoutesNeverRegisterPendingUnderAnyPrerequisite() {
        val current = MutableState()
        val backend = MemoryPendingBackend()
        val router = Router(current::guard, PendingActionStore(backend) { 0L }, RecordingNavigator())
        val cases =
            listOf(
                Triple("Withdraw", SessionState.GUEST, GuardKind.REQUIRE_LOGIN),
                Triple("Withdraw", SessionState.LOGGED_IN, GuardKind.REQUIRE_BIND_PHONE),
                Triple("Withdraw", SessionState.PHONE_BOUND, GuardKind.REQUIRE_REALNAME),
                Triple("PayoutAccount", SessionState.PHONE_BOUND, GuardKind.REQUIRE_REALNAME),
                Triple("LaborAgreement", SessionState.LOGGED_IN, GuardKind.REQUIRE_BIND_PHONE),
                Triple("RealName", SessionState.GUEST, GuardKind.REQUIRE_LOGIN),
                Triple("RealName", SessionState.LOGGED_IN, GuardKind.REQUIRE_BIND_PHONE),
            )
        for ((route, session, kind) in cases) {
            current.session = session
            // 带上「姓名」等本应丢弃的参数：既不登记，也不会被存下
            val decision = router.open(RouteRequest(route, params("name" to "x"), RouteSource.IN_APP))
            assertEquals("$route $session", kind, decision.kind)
            assertNull("$route $session 不登记", backend.read())
        }
        // 基本模式下用户主动点 DeleteAccount / Withdraw：先补同意，但不登记
        current.privacy = PrivacyState.BASIC_MODE
        for (route in listOf("DeleteAccount", "Withdraw", "PayoutAccount", "LaborAgreement", "RealName")) {
            assertEquals(GuardKind.REQUIRE_CONSENT, router.open(RouteRequest(route, empty, RouteSource.IN_APP)).kind)
            assertNull("$route 补同意也不登记", backend.read())
        }
    }

    @Test
    fun legacySensitiveRecordIsClearedOnResumeWithoutOpening() {
        val current = MutableState(session = SessionState.PHONE_BOUND, realname = true)
        val backend = MemoryPendingBackend()
        val store = PendingActionStore(backend) { 0L }
        val navigator = RecordingNavigator()
        val router = Router(current::guard, store, navigator)
        // 旧版本留下的提现记录
        store.save(PendingActionStore.OPEN_ROUTE, JsonObject(mapOf("route" to JsonPrimitive("Withdraw"))))
        assertNull(router.onPrerequisiteCompleted())
        assertNull(backend.read())
        assertTrue(navigator.events.isEmpty())
    }

    @Test
    fun onlySchemaValidParamsAreRegistered() {
        val current = MutableState(privacy = PrivacyState.BASIC_MODE)
        val backend = MemoryPendingBackend()
        val router = Router(current::guard, PendingActionStore(backend) { 0L }, RecordingNavigator())
        // 基本模式补同意先于参数校验：不合 schema 的参数不登记
        val bad = RouteRequest("OrderDetail", JsonObject(mapOf("order_id" to JsonPrimitive(1))), RouteSource.IN_APP)
        assertEquals(GuardKind.REQUIRE_CONSENT, router.open(bad).kind)
        assertNull(backend.read())
        val extra = RouteRequest("Wallet", params("phone" to "13800000000"), RouteSource.IN_APP)
        assertEquals(GuardKind.REQUIRE_CONSENT, router.open(extra).kind)
        assertNull("多余参数（schema additionalProperties=false）不登记", backend.read())
        val external = RouteRequest("ExternalPage", params("url" to "https://evil.invalid/"), RouteSource.IN_APP)
        router.open(external)
        assertNull("目标不在白名单不登记", backend.read())
        val good = RouteRequest("OrderDetail", params("order_id" to "o-1"), RouteSource.IN_APP)
        router.open(good)
        val saved = PendingActionStore(backend) { 0L }.load()
        assertEquals(JsonObject(mapOf("route" to JsonPrimitive("OrderDetail"), "params" to params("order_id" to "o-1"))), saved?.params)
    }

    // ---------- R1-03：回查前过闸门；代次 ----------

    @Test
    fun pushIsNotLookedUpWhenGatesBlock() =
        runBlocking {
            val lookups = AtomicInteger()
            val landing =
                PushLanding({
                    lookups.incrementAndGet()
                    PushLookupResult.Found("Wallet", empty)
                })
            val current = MutableState(session = SessionState.LOGGED_IN)
            val navigator = RecordingNavigator()
            val router = Router(current::guard, PendingActionStore(MemoryPendingBackend()) { 0L }, navigator)

            current.settled = false
            assertEquals(GuardReason.ENVIRONMENT_UNSETTLED, router.openPush("m", landing)?.reason)
            current.settled = true
            current.privacy = PrivacyState.NOT_DECIDED
            assertEquals(GuardReason.PRIVACY_UNDECIDED, router.openPush("m", landing)?.reason)
            current.privacy = PrivacyState.BASIC_MODE
            assertEquals(GuardReason.BASIC_MODE_BLOCKED, router.openPush("m", landing)?.reason)
            current.privacy = PrivacyState.CONSENTED
            current.update = UpdateState.BELOW_MIN
            current.session = SessionState.RESTRICTED
            assertEquals("below_min + 受限会话：直接 ForceUpdate", GuardKind.FORCE_UPDATE, router.openPush("m", landing)?.kind)
            current.update = UpdateState.OK
            assertEquals("受限会话不回查：先登录", GuardKind.REQUIRE_LOGIN, router.openPush("m", landing)?.kind)
            assertEquals("以上都不发回查", 0, lookups.get())

            current.session = SessionState.LOGGED_IN
            assertEquals(GuardKind.OPEN, router.openPush("m", landing)?.kind)
            assertEquals(1, lookups.get())
        }

    @Test
    fun lookupResultFromPreviousSessionIsDiscarded() =
        runBlocking {
            val current = MutableState(session = SessionState.LOGGED_IN)
            val navigator = RecordingNavigator()
            val router = Router(current::guard, PendingActionStore(MemoryPendingBackend()) { 0L }, navigator)
            val started = CompletableDeferred<Unit>()
            val result = CompletableDeferred<PushLookupResult>()
            val landing =
                PushLanding({
                    started.complete(Unit)
                    result.await()
                })
            // A 登录 → 回查挂起
            val push = async { router.openPush("m-a", landing) }
            started.await()
            // 退出 → B 登录
            current.session = SessionState.GUEST
            router.onSessionEnded()
            current.session = SessionState.LOGGED_IN
            result.complete(PushLookupResult.Found("Wallet", empty))
            assertNull("代次不符：丢弃", push.await())
            assertTrue(navigator.events.none { it.startsWith("open:Wallet") })

            // 环境切换同样作废
            val envStarted = CompletableDeferred<Unit>()
            val envResult = CompletableDeferred<PushLookupResult>()
            val envPush =
                async {
                    router.openPush(
                        "m-b",
                        PushLanding({
                            envStarted.complete(Unit)
                            envResult.await()
                        }),
                    )
                }
            envStarted.await()
            router.onEnvironmentChanged()
            envResult.complete(PushLookupResult.Found("Wallet", empty))
            assertNull(envPush.await())
            assertTrue(navigator.events.isEmpty())
        }

    // ---------- R1-04：Messages 兜底也经守卫；5 秒到点即兜底 ----------

    @Test
    fun messagesFallbackGoesThroughGuard() {
        fun decide(
            route: String,
            update: UpdateState,
            session: SessionState,
        ) = EntryGuard.decide(
            RouteRequest(route, empty, RouteSource.PUSH),
            MutableState(update = update, session = session).guard(),
        )
        val unknownGuest = decide("AuthManage", UpdateState.UNKNOWN, SessionState.GUEST)
        assertEquals(GuardKind.REQUIRE_LOGIN, unknownGuest.kind)
        assertEquals("Login", unknownGuest.route)
        assertTrue(unknownGuest.fallbackToMessages)
        val okGuest = decide("BindPhone", UpdateState.OK, SessionState.GUEST)
        assertEquals("push_entry_fallback 也要登录", GuardKind.REQUIRE_LOGIN, okGuest.kind)
        val unknownLoggedIn = decide("AuthManage", UpdateState.UNKNOWN, SessionState.LOGGED_IN)
        assertEquals(GuardKind.OPEN_READONLY, unknownLoggedIn.kind)
        assertEquals(GuardReason.UPDATE_UNKNOWN_PUSH_FALLBACK, unknownLoggedIn.reason)
    }

    /** 编排者补充（iOS 第 3 轮）：兜底在 EntryGuard 本身对 Messages 完整判定，直接 Router.open(source=push) 的游客也进不了 Messages。 */
    @Test
    fun directPushOpenByGuestNeverReachesMessages() {
        for (update in listOf(UpdateState.OK, UpdateState.UNKNOWN, UpdateState.BELOW_RECOMMENDED)) {
            for (route in listOf("Login", "BindPhone", "AuthManage", "AgentChat", "Messages")) {
                val current = MutableState(update = update, session = SessionState.GUEST)
                val backend = MemoryPendingBackend()
                val navigator = RecordingNavigator()
                val router = Router(current::guard, PendingActionStore(backend) { 0L }, navigator)
                val decision = router.open(RouteRequest(route, empty, RouteSource.PUSH))
                val label = "$update $route"
                assertTrue(label, navigator.events.none { it.startsWith("open:Messages") })
                if (decision.kind == GuardKind.REQUIRE_LOGIN) {
                    assertEquals(label, "open:Login:rw", navigator.events.single())
                    val saved = PendingActionStore(backend) { 0L }.load()?.params?.get("route")
                    val allowed = setOf(null, JsonPrimitive("Messages"), JsonPrimitive(route))
                    assertTrue("$label 登记的只能是 Messages 或原落点", saved in allowed)
                } else {
                    // 自身声明 push、无需登录的落点（如 AgentChat）照常处理；Messages 永不作为游客的打开目标
                    assertFalse(label, decision.opensPage && decision.route == "Messages")
                }
            }
        }
        // 已绑手机未实名、受限会话同样不能借兜底绕过 Messages 的登录要求
        val restricted = MutableState(session = SessionState.RESTRICTED)
        val navigator = RecordingNavigator()
        val router = Router(restricted::guard, PendingActionStore(MemoryPendingBackend()) { 0L }, navigator)
        assertEquals(GuardKind.REQUIRE_LOGIN, router.open(RouteRequest("Login", empty, RouteSource.PUSH)).kind)
        assertTrue(navigator.events.none { it.startsWith("open:Messages") })
        // 已登录：兜底照常打开 Messages（向量 push_login_falls_back）
        val loggedIn = MutableState(session = SessionState.LOGGED_IN)
        val opened = RecordingNavigator()
        Router(loggedIn::guard, PendingActionStore(MemoryPendingBackend()) { 0L }, opened)
            .open(RouteRequest("Login", empty, RouteSource.PUSH))
        assertEquals("open:Messages:rw", opened.events.single())
    }

    @Test
    fun logoutDuringLookupThenFallbackRequiresLoginAndRegistersMessages() =
        runBlocking {
            val current = MutableState(update = UpdateState.UNKNOWN, session = SessionState.LOGGED_IN)
            val backend = MemoryPendingBackend()
            val navigator = RecordingNavigator()
            val router = Router(current::guard, PendingActionStore(backend) { 0L }, navigator)
            val landing =
                PushLanding({
                    // 回查期间会话变成游客（未经 onSessionEnded 的情形也要守住）
                    current.session = SessionState.GUEST
                    PushLookupResult.Found("AuthManage", empty)
                })
            val decision = router.openPush("m", landing)
            assertEquals(GuardKind.REQUIRE_LOGIN, decision?.kind)
            assertEquals("open:Login:rw", navigator.events.last())
            assertTrue(navigator.events.none { it.startsWith("open:Messages") })
            val saved = PendingActionStore(backend) { 0L }.load()
            assertEquals("登记的是兜底后的 Messages", JsonPrimitive("Messages"), saved?.params?.get("route"))
        }

    @Test
    fun lookupTimeoutFallsBackAtDeadlineEvenIfLookupIgnoresCancellation() =
        runBlocking {
            val finished = CountDownLatch(1)
            val late = AtomicInteger()
            val landing =
                PushLanding(
                    lookup = {
                        // 不响应取消的回查：阻塞线程 1.5 秒
                        withContext(Dispatchers.IO) {
                            Thread.sleep(1_500L)
                        }
                        late.incrementAndGet()
                        finished.countDown()
                        PushLookupResult.Found("Wallet", empty)
                    },
                    timeoutMillis = 100L,
                )
            val current = MutableState(session = SessionState.LOGGED_IN)
            val navigator = RecordingNavigator()
            val router = Router(current::guard, PendingActionStore(MemoryPendingBackend()) { 0L }, navigator)
            val startedAt = System.nanoTime()
            val decision = router.openPush("m", landing)
            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
            assertEquals(GuardKind.OPEN, decision?.kind)
            assertEquals("open:Messages:rw", navigator.events.single())
            assertTrue("到点即兜底，不等回查（实际 $elapsedMillis ms）", elapsedMillis < 1_000L)
            // 迟到的结果不打开任何页面
            finished.await(5, TimeUnit.SECONDS)
            yield()
            assertEquals(listOf("open:Messages:rw"), navigator.events)
        }

    // ---------- R1-05：Agent 记录遇隐私阻断即删除 ----------

    @Test
    fun agentPendingIsDeletedWhenPrivacyBlocksAndNotReplayedAfterConsent() {
        val current = MutableState()
        val backend = MemoryPendingBackend()
        val navigator = RecordingNavigator()
        val router = Router(current::guard, PendingActionStore(backend) { 0L }, navigator)
        assertEquals(GuardKind.REQUIRE_LOGIN, router.open(RouteRequest("Wallet", empty, RouteSource.AGENT_CARD)).kind)
        assertNotNull(backend.read())
        // 隐私变为未决，Agent 请求得到 require_consent：此刻就删除旧记录
        current.privacy = PrivacyState.NOT_DECIDED
        assertEquals(GuardKind.REQUIRE_CONSENT, router.open(RouteRequest("Wallet", empty, RouteSource.AGENT_CARD)).kind)
        assertNull("阻断即删除", backend.read())
        // 同意 → 登录：都不补打开
        current.privacy = PrivacyState.CONSENTED
        assertNull(router.onPrerequisiteCompleted())
        current.session = SessionState.LOGGED_IN
        assertNull(router.onPrerequisiteCompleted())
        assertTrue(navigator.events.none { it.startsWith("open:Wallet") })
    }

    @Test
    fun privacyChangeAloneDeletesAgentPending() {
        val current = MutableState()
        val backend = MemoryPendingBackend()
        val router = Router(current::guard, PendingActionStore(backend) { 0L }, RecordingNavigator())
        router.open(RouteRequest("Wallet", empty, RouteSource.AGENT_CARD))
        // 普通（非 Agent）记录不受影响
        val inApp = MemoryPendingBackend()
        val inAppRouter = Router(current::guard, PendingActionStore(inApp) { 0L }, RecordingNavigator())
        inAppRouter.open(RouteRequest("Wallet", empty, RouteSource.IN_APP))
        // 冷启动隐私闸门：状态变为未决（尚未发生任何 open）
        current.privacy = PrivacyState.NOT_DECIDED
        router.onPrivacyChanged()
        inAppRouter.onPrivacyChanged()
        assertNull(backend.read())
        assertNotNull(inApp.read())
        // 基本模式同样删除
        current.privacy = PrivacyState.CONSENTED
        router.open(RouteRequest("Wallet", empty, RouteSource.AGENT_CARD))
        current.privacy = PrivacyState.BASIC_MODE
        router.onPrivacyChanged()
        assertNull(backend.read())
    }

    // ---------- R1-06 / R1-07：强更等待从闸门通过后起算；闸门未就绪无定时器 ----------

    @Test
    fun updateWaitStartsAfterEnvironmentAndPrivacyGates() {
        val queue = StartupIntentQueue()
        val link = StartupIntent.Link(RouteRequest("Search", empty, RouteSource.DEEPLINK))
        val gatesClosed = StartupReadiness(environmentSettled = true, privacy = PrivacyState.NOT_DECIDED, update = UpdateCheck.CHECKING)
        queue.offer(link, coldStart = true, readiness = gatesClosed, now = 0L)
        assertTrue(queue.drain(gatesClosed, now = 10_000L).isEmpty())
        assertNull("闸门未通过：不安排定时器", queue.nextDeadline(gatesClosed))
        val gatesOpen = gatesClosed.copy(privacy = PrivacyState.CONSENTED)
        assertTrue("t=10s 闸门通过：不立即按 unknown 放行", queue.drain(gatesOpen, now = 10_000L).isEmpty())
        assertEquals(15_000L, queue.nextDeadline(gatesOpen))
        // t=12s 检查返回 ok：按 ok 放行
        val ok = gatesOpen.copy(update = UpdateCheck.OK)
        assertNull("已有结果：不需要定时器", queue.nextDeadline(ok))
        assertEquals(listOf(ReleasedIntent(link, UpdateState.OK)), queue.drain(ok, now = 12_000L))
    }

    @Test
    fun updateWaitTimesOutFiveSecondsAfterGatesOpen() {
        val queue = StartupIntentQueue()
        val push = StartupIntent.Push("m")
        val envBlocked = StartupReadiness(environmentSettled = false, privacy = PrivacyState.CONSENTED, update = UpdateCheck.CHECKING)
        queue.offer(push, coldStart = true, readiness = envBlocked, now = 0L)
        assertTrue(queue.drain(envBlocked, now = 30_000L).isEmpty())
        val open = envBlocked.copy(environmentSettled = true)
        assertTrue(queue.drain(open, now = 30_000L).isEmpty())
        assertTrue(queue.drain(open, now = 34_999L).isEmpty())
        assertEquals(listOf(ReleasedIntent(push, UpdateState.UNKNOWN)), queue.drain(open, now = 35_000L))
        assertNull(queue.nextDeadline(open))
        assertFalse(queue.size > 0)
    }

    @Test
    fun shellNotReadyHasNoDeadline() {
        val queue = StartupIntentQueue()
        val noShell =
            StartupReadiness(environmentSettled = true, privacy = PrivacyState.CONSENTED, update = UpdateCheck.CHECKING, shellReady = false)
        queue.offer(StartupIntent.Push("m"), coldStart = true, readiness = noShell, now = 0L)
        assertTrue(queue.drain(noShell, now = 60_000L).isEmpty())
        assertNull(queue.nextDeadline(noShell))
    }

    // ---------- R2-03：强更等待从环境与隐私闸门通过起算，外壳就绪不影响计时 ----------

    @Test
    fun shellReadyAfterTimeoutReleasesAsUnknownImmediately() {
        val queue = StartupIntentQueue()
        val link = StartupIntent.Link(RouteRequest("Search", empty, RouteSource.DEEPLINK))
        val noShell =
            StartupReadiness(environmentSettled = true, privacy = PrivacyState.CONSENTED, update = UpdateCheck.CHECKING, shellReady = false)
        queue.offer(link, coldStart = true, readiness = noShell, now = 0L) // t=0：闸门通过、强更开始检查
        assertTrue(queue.drain(noShell, now = 3_000L).isEmpty())
        assertNull("外壳未就绪：不安排定时器", queue.nextDeadline(noShell))
        val shell = noShell.copy(shellReady = true)
        assertEquals("t=10s 外壳就绪：已超时，直接按 unknown", listOf(ReleasedIntent(link, UpdateState.UNKNOWN)), queue.drain(shell, now = 10_000L))
    }

    @Test
    fun shellReadyBeforeTimeoutWaitsOnlyTheRemainder() {
        val queue = StartupIntentQueue()
        val push = StartupIntent.Push("m")
        val noShell =
            StartupReadiness(environmentSettled = true, privacy = PrivacyState.CONSENTED, update = UpdateCheck.CHECKING, shellReady = false)
        queue.offer(push, coldStart = true, readiness = noShell, now = 0L)
        assertTrue(queue.drain(noShell, now = 1_000L).isEmpty())
        val shell = noShell.copy(shellReady = true)
        assertTrue("t=3s 外壳就绪：未满 5 秒", queue.drain(shell, now = 3_000L).isEmpty())
        assertEquals("截止时刻仍按 t=0 起算", 5_000L, queue.nextDeadline(shell))
        assertEquals(listOf(ReleasedIntent(push, UpdateState.UNKNOWN)), queue.drain(shell, now = 5_000L))
    }

    @Test
    fun waitStartsWhenGatesOpenEvenWithoutIntentsAndResetsOnResult() {
        val queue = StartupIntentQueue()
        val checking =
            StartupReadiness(environmentSettled = true, privacy = PrivacyState.CONSENTED, update = UpdateCheck.CHECKING, shellReady = false)
        assertTrue(queue.drain(checking, now = 0L).isEmpty()) // 队列为空时也记下起点
        queue.offer(StartupIntent.ResumePending, coldStart = true, readiness = checking, now = 7_000L)
        assertEquals(
            listOf(ReleasedIntent(StartupIntent.ResumePending, UpdateState.UNKNOWN)),
            queue.drain(checking.copy(shellReady = true), now = 7_000L),
        )
        // 检查有了结果后清零；再次进入检查中重新起算
        val ok = checking.copy(update = UpdateCheck.OK, shellReady = true)
        assertTrue(queue.drain(ok, now = 8_000L).isEmpty())
        queue.offer(StartupIntent.Push("m"), coldStart = false, readiness = checking.copy(shellReady = true), now = 20_000L)
        assertTrue(queue.drain(checking.copy(shellReady = true), now = 20_000L).isEmpty())
        assertEquals(25_000L, queue.nextDeadline(checking.copy(shellReady = true)))
    }

    @Test
    fun resumePendingIsQueuedAtMostOnce() {
        val queue = StartupIntentQueue()
        val closed = StartupReadiness(environmentSettled = false, privacy = PrivacyState.CONSENTED, update = UpdateCheck.OK)
        assertTrue(queue.offer(StartupIntent.ResumePending, coldStart = true, readiness = closed, now = 0L))
        assertTrue(queue.offer(StartupIntent.ResumePending, coldStart = false, readiness = closed, now = 1L))
        assertEquals(1, queue.size)
    }

    @Test
    fun coldStartResumeKeepsPendingWithoutEnteringPrerequisitePage() {
        val backend = MemoryPendingBackend()
        val navigator = RecordingNavigator()
        val router = Router(MutableState()::guard, PendingActionStore(backend) { 0L }, navigator)
        router.open(RouteRequest("OrderList", empty, RouteSource.IN_APP))
        navigator.events.clear()
        val decision = router.resumePending(enterPrerequisite = false)
        assertEquals(GuardKind.REQUIRE_LOGIN, decision?.kind)
        assertNotNull("前置未满足：保留", backend.read())
        assertTrue("不自动拉起登录页", navigator.events.isEmpty())
    }
}
