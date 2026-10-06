package com.couli.core.router

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APP-02b Android 评审第 1 轮：
 * - R1-03：[Router.onPrerequisiteCompleted]（resume = false）只续时不恢复（主动同意 = 完成一个前置步骤）；
 * - R1-04：[Router.onConsentDeclined] 明确仍不同意 → 丢弃本次登记的待办；
 * - R1-05：[StartupReadiness.consentPromptShowing] 基本模式补同意弹窗显示时，运行中的深链与推送丢弃，排队的意图等弹窗结束。
 */
class RouterPrivacyPromptTest {
    private val empty = JsonObject(emptyMap())
    private var now = 0L

    private class Navigator : RouteNavigator {
        val events = mutableListOf<String>()

        override fun open(
            route: String,
            params: JsonObject,
            readonly: Boolean,
        ) {
            events += "open:$route"
        }

        override fun showUpgradePrompt(request: RouteRequest) = Unit

        override fun requestConsent(request: RouteRequest) {
            events += "consent:${request.route}"
        }

        override fun blocked(
            request: RouteRequest,
            decision: GuardDecision,
        ) = Unit
    }

    private var privacy = PrivacyState.BASIC_MODE

    private fun guard() =
        GuardState(
            privacy = privacy,
            update = UpdateState.OK,
            session = SessionState.GUEST,
            environmentSettled = true,
            appVersion = "1.0.0",
            isDebugBuild = false,
        )

    @Test
    fun markPrerequisiteStepDoneResetsTimerWithoutOpening() {
        val backend = MemoryPendingBackend()
        val store = PendingActionStore(backend) { now }
        val navigator = Navigator()
        val router = Router(::guard, store, navigator)
        assertEquals(GuardKind.REQUIRE_CONSENT, router.open(RouteRequest("OrderList", empty, RouteSource.IN_APP)).kind)
        assertEquals(listOf("consent:OrderList"), navigator.events)

        now = 9 * 60_000L
        privacy = PrivacyState.CONSENTED
        assertNull("只续时：不恢复", router.onPrerequisiteCompleted(resume = false))
        assertEquals("只续时，不打开", listOf("consent:OrderList"), navigator.events)
        assertEquals(now, store.load()?.timerStartedAtMillis)

        // 续时后再过 9 分钟仍有效，恢复进入登录页（OrderList 需要登录）
        now += 9 * 60_000L
        assertEquals(GuardKind.REQUIRE_LOGIN, router.resumePending(enterPrerequisite = true)?.kind)
        assertEquals("open:Login", navigator.events.last())
        assertNotNull(store.load())
    }

    @Test
    fun consentDeclinedDropsTheRegisteredPending() {
        val backend = MemoryPendingBackend()
        val router = Router(::guard, PendingActionStore(backend) { now }, Navigator())
        router.open(RouteRequest("Me", empty, RouteSource.IN_APP))
        assertNotNull("基本模式点「我的」登记待办", backend.read())
        router.onConsentDeclined()
        assertNull("仍不同意：丢弃", backend.read())
    }

    @Test
    fun consentDeclinedKeepsPendingWhenAlreadyConsented() {
        privacy = PrivacyState.CONSENTED
        val backend = MemoryPendingBackend()
        val router = Router(::guard, PendingActionStore(backend) { now }, Navigator())
        router.open(RouteRequest("OrderList", empty, RouteSource.IN_APP))
        assertNotNull(backend.read())
        router.onConsentDeclined()
        assertNotNull("已同意（无关的登录待办）不受影响", backend.read())
    }

    @Test
    fun runtimeLinksAreDroppedWhileConsentPromptShowsInBasicMode() {
        val queue = StartupIntentQueue()
        val prompting =
            StartupReadiness(
                environmentSettled = true,
                privacy = PrivacyState.BASIC_MODE,
                update = UpdateCheck.OK,
                consentPromptShowing = true,
            )
        val link = StartupIntent.Link(RouteRequest("Agreement", empty, RouteSource.DEEPLINK))
        assertFalse("运行中深链丢弃", queue.offer(link, coldStart = false, prompting, 0L))
        assertFalse("运行中推送丢弃", queue.offer(StartupIntent.Push("m1"), coldStart = false, prompting, 0L))
        assertEquals(0, queue.size)

        // 冷启动带来的意图排队，弹窗显示期间不交出，弹窗结束后交出
        assertTrue(queue.offer(link, coldStart = true, prompting, 0L))
        assertTrue(queue.drain(prompting, 0L).isEmpty())
        assertEquals(1, queue.drain(prompting.copy(consentPromptShowing = false), 0L).size)

        // 没有弹窗的基本模式：运行中深链照常排队
        assertTrue(queue.offer(link, coldStart = false, prompting.copy(consentPromptShowing = false), 0L))
    }

    /** 编排补充 1：补同意弹窗期间自动恢复（隐私阻断 reject）不删这条待办；同意后按前置完成继续。 */
    @Test
    fun autoResumeWhileConsentPromptPendingKeepsTheRecordThenAgreeContinues() {
        val backend = MemoryPendingBackend()
        val store = PendingActionStore(backend) { now }
        val navigator = Navigator()
        val router = Router(::guard, store, navigator)
        router.open(RouteRequest("OrderList", empty, RouteSource.IN_APP))
        val auto = router.resumePending(enterPrerequisite = false)
        assertEquals(GuardKind.REJECT, auto?.kind)
        assertNotNull("弹窗期间不删", backend.read())
        assertEquals(listOf("consent:OrderList"), navigator.events)

        privacy = PrivacyState.CONSENTED
        assertEquals(GuardKind.REQUIRE_LOGIN, router.onPrerequisiteCompleted()?.kind)
        assertEquals("open:Login", navigator.events.last())
        assertNotNull(backend.read())
    }

    /** 编排补充 1：进程重启后（新 Router，内存标记没有）基本模式下的自动恢复照常 reject 删除。 */
    @Test
    fun afterProcessRestartBasicModeAutoResumeDeletesTheRecord() {
        val backend = MemoryPendingBackend()
        Router(::guard, PendingActionStore(backend) { now }, Navigator()).open(RouteRequest("OrderList", empty, RouteSource.IN_APP))
        assertNotNull(backend.read())
        val restarted = Router(::guard, PendingActionStore(backend) { now }, Navigator())
        assertEquals(GuardKind.REJECT, restarted.resumePending(enterPrerequisite = false)?.kind)
        assertNull(backend.read())
    }

    /** 编排补充 2：推送兜底到 Messages 且需要登录时，登记的待办是 Messages 而不是原始目标。 */
    @Test
    fun messagesFallbackNeedingLoginRegistersMessages() {
        privacy = PrivacyState.CONSENTED
        val backend = MemoryPendingBackend()
        val store = PendingActionStore(backend) { now }
        val navigator = Navigator()
        val unknown = guard().copy(update = UpdateState.UNKNOWN.raw)
        val router = Router({ unknown }, store, navigator)
        val decision = router.open(RouteRequest("Me", empty, RouteSource.PUSH))
        assertTrue(decision.fallbackToMessages)
        assertEquals(GuardKind.REQUIRE_LOGIN, decision.kind)
        assertEquals(
            "Messages",
            store
                .load()
                ?.params
                ?.get("route")
                ?.let { (it as JsonPrimitive).content },
        )
        assertEquals("open:Login", navigator.events.last())
    }
}
