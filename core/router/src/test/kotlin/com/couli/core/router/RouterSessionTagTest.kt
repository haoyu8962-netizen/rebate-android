package com.couli.core.router

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-AND-14（APP-05a 共性第 25 条）：会话结束的 Router 清理只清原会话归属的 pending_action。登记时记下会话标签（登录世代）；原会话被
 * 别处清掉（10404、刷新失败）之后用户新建的待办标签更大，登出收尾按原会话的世代清理时保留它。
 */
class RouterSessionTagTest {
    private val empty = JsonObject(emptyMap())
    private var epoch = 1L
    private var session = SessionState.PHONE_BOUND

    private object Navigator : RouteNavigator {
        override fun open(
            route: String,
            params: JsonObject,
            readonly: Boolean,
        ) = Unit

        override fun showUpgradePrompt(request: RouteRequest) = Unit

        override fun requestConsent(request: RouteRequest) = Unit

        override fun blocked(
            request: RouteRequest,
            decision: GuardDecision,
        ) = Unit
    }

    private fun guard() =
        GuardState(
            privacy = PrivacyState.CONSENTED,
            update = UpdateState.OK,
            session = session,
            environmentSettled = true,
            appVersion = "1.0.0",
            isDebugBuild = false,
            realnameVerified = false,
        )

    private fun router(tagged: Boolean = true): Router =
        Router(::guard, PendingActionStore(MemoryPendingBackend()) { 0L }, Navigator).also { router ->
            if (tagged) router.sessionTag = { epoch }
        }

    /** 游客点需登录入口：登记待办并进登录页。 */
    private fun Router.registerAsGuest(route: String = "OrderList") {
        session = SessionState.GUEST
        open(RouteRequest(route, empty, RouteSource.IN_APP))
    }

    @Test
    fun aPendingActionCreatedAfterTheOriginalSessionWasClearedSurvivesTheLogoutCleanup() {
        val router = router()
        // 原会话（世代 1）被 10404 清掉：世代前进到 2，此时的整体清理清的是之前的待办
        epoch = 2
        router.onSessionEnded()
        // 用户以游客身份点需登录入口：新待办属于之后的会话
        router.registerAsGuest()
        assertTrue(router.hasPendingAction)

        // 登出收尾（原会话世代 1，原会话已被清掉）：只清原会话归属的待办
        router.onSessionEnded(throughSession = 1)
        assertTrue("原会话被清掉之后新建的待办保留给正在进行的登录", router.hasPendingAction)
    }

    @Test
    fun aPendingActionOfTheOriginalSessionIsCleared() {
        val router = router()
        router.registerAsGuest()
        assertTrue(router.hasPendingAction)
        router.onSessionEnded(throughSession = 1)
        assertFalse(router.hasPendingAction)
    }

    @Test
    fun theDefaultCleanupClearsEverythingRegisteredBeforeTheCurrentEpoch() {
        val router = router()
        router.registerAsGuest()
        // 会话被清（世代前进）之后的整体清理：登记于之前世代的待办删除
        epoch = 2
        router.onSessionEnded()
        assertFalse(router.hasPendingAction)
    }

    /** APP-05b 共性第 9 条：待办暂缓是持续状态——任何恢复入口都只保留记录，明确释放后才恢复；会话结束时一并解除。 */
    @Test
    fun aHeldPendingActionIsNotResumedByAnyEntryUntilReleased() {
        val router = router()
        router.registerAsGuest()
        session = SessionState.LOGGED_IN
        router.holdPending()
        assertTrue(router.resumePending(enterPrerequisite = false) == null)
        assertTrue(router.onPrerequisiteCompleted(resume = true) == null)
        assertTrue("暂缓期间待办保留", router.hasPendingAction)
        router.releaseHeldPending()
        assertTrue(router.resumePending(enterPrerequisite = false)?.opensPage == true)
        assertFalse(router.hasPendingAction)

        router.registerAsGuest()
        router.holdPending()
        router.onSessionEnded(throughSession = epoch)
        assertFalse(router.pendingHeld)
    }

    /** APP-05b 共性第 8 条：恢复前的核对没过（phone_bound 待查）→ 这次不恢复、保留记录。 */
    @Test
    fun aResumeGateThatSaysNotYetKeepsThePendingAction() {
        val router = router()
        router.registerAsGuest()
        session = SessionState.LOGGED_IN
        var ready = false
        router.resumeGate = { ready }
        assertTrue(router.resumePending(enterPrerequisite = false) == null)
        assertTrue(router.hasPendingAction)
        ready = true
        assertTrue(router.resumePending(enterPrerequisite = false)?.opensPage == true)
    }

    @Test
    fun withoutASessionTagOrWithAnUnknownOwnerTheCleanupIsConservative() {
        val untagged = router(tagged = false)
        untagged.registerAsGuest()
        untagged.onSessionEnded(throughSession = 0)
        assertFalse("没有接标签来源：整体清理", untagged.hasPendingAction)

        // 进程重启后（同一份存储、新 Router）登记归属未知：按原会话的处理
        val backend = MemoryPendingBackend()
        val before = Router(::guard, PendingActionStore(backend) { 0L }, Navigator).also { it.sessionTag = { epoch } }
        epoch = 5
        before.registerAsGuest()
        val restarted = Router(::guard, PendingActionStore(backend) { 0L }, Navigator).also { it.sessionTag = { epoch } }
        assertTrue(restarted.hasPendingAction)
        restarted.onSessionEnded(throughSession = 1)
        assertFalse(restarted.hasPendingAction)
    }
}
