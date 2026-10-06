package com.couli.app.privacy

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.routing.AppRouting
import com.couli.app.routing.RoutingPrivacyHooks
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.privacy.PrivacyGate
import com.couli.core.privacy.PrivacyStore
import com.couli.core.privacy.PromptStage
import com.couli.core.privacy.RetryScheduler
import com.couli.core.router.GuardDecision
import com.couli.core.router.GuardKind
import com.couli.core.router.GuardReason
import com.couli.core.router.PendingActionStore
import com.couli.core.router.PrivacyState
import com.couli.core.router.RouteNavigator
import com.couli.core.router.RouteRequest
import com.couli.core.router.RouteSource
import com.couli.core.router.SessionState
import com.couli.core.router.SharedPreferencesPendingBackend
import com.couli.core.router.UpdateCheck
import java.time.Duration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * APP-02b：真实 [PrivacyGate] 经 [AppPrivacy] 接到 [AppRouting]：
 * - 隐私结论每次变化都写进 AppRouting.privacy（→ Router.onPrivacyChanged、重排 pending_action 恢复）；
 * - 冷启动隐私未决时启动意图与 pending_action 等待，闸门有结论后恢复；
 * - 自动恢复时守卫给出拒绝（基本模式 basic_mode_blocked）→ 删除记录（F-ALL-04）；
 * - 撤回同意走清理入口并进入基本模式；Agent 卡片的记录在隐私变为非同意时删除；
 * - 首启弹窗里点《隐私政策》：隐私未决时 App 内来源只读打开 Agreement。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class AppPrivacyRoutingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val looper get() = shadowOf(Looper.getMainLooper())
    private var now = 1_000_000L

    private class MemoryStore : PrivacyStore {
        override var decisionRaw: String = "not_decided"
        override var consentedVersion: String? = null
        override var consentedAtMillis: Long? = null
        override var basicMode: Boolean = false
    }

    private class RecordingNavigator : RouteNavigator {
        val opened = mutableListOf<String>()
        val consentRequests = mutableListOf<String>()

        override fun open(
            route: String,
            params: JsonObject,
            readonly: Boolean,
        ) {
            opened += if (readonly) "$route(readonly)" else route
        }

        override fun showUpgradePrompt(request: RouteRequest) = Unit

        override fun requestConsent(request: RouteRequest) {
            consentRequests += request.route
        }

        override fun blocked(
            request: RouteRequest,
            decision: GuardDecision,
        ) = Unit
    }

    private val store = MemoryStore()
    private var cleanups = 0
    private var cleanupFails = false
    private val initialized = mutableListOf<String>()

    @Before
    fun clearPending() {
        SharedPreferencesPendingBackend(context).delete()
    }

    private fun environment(): EnvironmentGate =
        EnvironmentGate { EnvironmentStartupReconciler.Outcome.CLEANED_AND_RECORDED }.also { it.settleAtStartup() }

    private fun privacy(): AppPrivacy {
        val gate =
            PrivacyGate(store, clock = { now }, bundledPolicyVersion = "1").apply {
                withdrawalCleanup = {
                    cleanups += 1
                    check(!cleanupFails) { "删除失败" }
                }
                retryScheduler = RetryScheduler { _, _ -> }
                PrivacyGate.INIT_ORDER.forEach { name -> registerInitStep(name) { initialized += name } }
            }
        return AppPrivacy(gate, environmentSettled = { true }, clock = { now }).also { it.coldStart() }
    }

    /** 与 AppModule 相同的接线。 */
    private fun routing(privacy: AppPrivacy): AppRouting =
        AppRouting(
            context,
            environment(),
            clock = { now },
            initialPrivacy = privacy.conclusion,
            privacyHooks =
                RoutingPrivacyHooks(
                    promptShowing = { privacy.promptShowing },
                    credentialsReadable = { privacy.credentialAccess.readable() },
                ),
        ).also { routing ->
            privacy.onConclusionChanged { routing.privacy.value = it }
            privacy.onConsentEvent(routing::onConsentEvent)
        }

    private fun stored(): String? = SharedPreferencesPendingBackend(context).read()

    private fun savePending(
        route: String,
        origin: RouteSource,
    ) {
        val payload = JsonObject(mapOf("route" to JsonPrimitive(route)))
        assertTrue(
            PendingActionStore(SharedPreferencesPendingBackend(context), {
                now
            }).save(PendingActionStore.OPEN_ROUTE, payload, now, origin.raw),
        )
    }

    @Test
    fun firstLaunchWaitsForConclusionThenResumesPendingAndInitializesOnce() {
        val privacy = privacy()
        assertEquals(PromptStage.FIRST, privacy.state.value.promptStage)
        assertEquals(PrivacyState.NOT_DECIDED, privacy.conclusion)
        savePending("Me", RouteSource.IN_APP)
        val routing = routing(privacy)
        val navigator = RecordingNavigator()
        routing.attach(navigator)
        looper.idle()
        assertTrue("隐私未决：不恢复", navigator.opened.isEmpty())
        assertNotNull(stored())
        assertTrue("同意前不初始化任何 SDK", initialized.isEmpty())

        privacy.disagree()
        assertEquals(PrivacyState.NOT_DECIDED, routing.privacy.value)
        privacy.agree()
        looper.idle()
        assertEquals(PrivacyState.CONSENTED, routing.privacy.value)
        assertEquals("闸门有结论后恢复一次", listOf("Me"), navigator.opened)
        assertNull(stored())
        assertEquals(PrivacyGate.INIT_ORDER, initialized)

        privacy.initializeIfReady()
        assertEquals("只初始化一次", PrivacyGate.INIT_ORDER, initialized)
    }

    @Test
    fun basicModeAutoResumeRejectDeletesRecord() {
        val privacy = privacy()
        // 上一次运行留下的普通记录（App 内登记）；这次冷启动用户进入基本模式
        savePending("OrderList", RouteSource.IN_APP)
        val routing = routing(privacy)
        val navigator = RecordingNavigator()
        routing.attach(navigator)
        privacy.disagree()
        privacy.stillDisagree()
        looper.idle()
        assertEquals(PrivacyState.BASIC_MODE, routing.privacy.value)
        val outcome = routing.router.lastOutcome.value
        assertEquals(GuardKind.REJECT, outcome?.decision?.kind)
        assertEquals(GuardReason.BASIC_MODE_BLOCKED, outcome?.decision?.reason)
        assertNull("自动恢复被拒绝：删除记录（F-ALL-04）", stored())
        assertTrue(navigator.opened.isEmpty())
        assertTrue("基本模式不初始化", initialized.isEmpty())
    }

    @Test
    fun basicModeTabNeedsConsentThenOpensAfterAgree() {
        val privacy = privacy()
        privacy.disagree()
        privacy.stillDisagree()
        val routing = routing(privacy)
        val navigator = RecordingNavigator()
        routing.attach(navigator)
        looper.idle()

        // 基本模式点「我的」Tab：补同意（登记 pending_action），弹首启弹窗
        val decision = routing.router.open(RouteRequest("Me", JsonObject(emptyMap()), RouteSource.IN_APP))
        assertEquals(GuardKind.REQUIRE_CONSENT, decision.kind)
        assertEquals(listOf("Me"), navigator.consentRequests)
        privacy.requestFullFunction()
        assertEquals(PromptStage.FIRST, privacy.state.value.promptStage)
        assertEquals("弹窗期间仍是基本模式", PrivacyState.BASIC_MODE, routing.privacy.value)

        privacy.agree()
        looper.idle()
        assertEquals(listOf("Me"), navigator.opened)
        assertNull(stored())
    }

    @Test
    fun withdrawCleansCredentialsEntersBasicModeAndDropsAgentPending() {
        store.decisionRaw = "consented"
        store.consentedVersion = "1"
        store.consentedAtMillis = 0L
        val privacy = privacy()
        val routing = routing(privacy)
        routing.attach(RecordingNavigator())
        looper.idle()
        assertEquals(PrivacyState.CONSENTED, routing.privacy.value)
        savePending("Wallet", RouteSource.AGENT_CARD)

        privacy.withdraw()
        looper.idle()
        assertEquals(1, cleanups)
        assertEquals(PrivacyState.BASIC_MODE, routing.privacy.value)
        assertTrue(store.basicMode)
        assertNull("隐私变为非同意：Agent 卡片的记录删除（R1-05）", stored())
    }

    @Test
    fun agreementOpensReadonlyWhilePrivacyUndecided() {
        val privacy = privacy()
        val routing = routing(privacy)
        val navigator = RecordingNavigator()
        routing.attach(navigator)
        val request = RouteRequest("Agreement", JsonObject(mapOf("type" to JsonPrimitive("privacy"))), RouteSource.IN_APP)
        val decision = routing.router.open(request)
        assertEquals(GuardKind.OPEN_READONLY, decision.kind)
        assertEquals(listOf("Agreement(readonly)"), navigator.opened)
        assertNull("不登记 pending_action", stored())

        // 深链在隐私未决时仍等待
        val deeplink = routing.router.open(request.copy(source = RouteSource.DEEPLINK.raw))
        assertEquals(GuardKind.DEFER, deeplink.kind)
        assertFalse(navigator.opened.size > 1)
    }

    private fun basicModeRouting(): Triple<AppPrivacy, AppRouting, RecordingNavigator> {
        val privacy = privacy()
        privacy.disagree()
        privacy.stillDisagree()
        val routing = routing(privacy)
        val navigator = RecordingNavigator()
        routing.attach(navigator)
        looper.idle()
        return Triple(privacy, routing, navigator)
    }

    /** R1-03：基本模式点「订单」登记待办，阅读协议 9 分钟后同意 → 续时并进入 Login（仍缺的前置），不是停在首页。 */
    @Test
    fun agreeingCompletesAPrerequisiteResetsTimerAndEntersLogin() {
        val (privacy, routing, navigator) = basicModeRouting()
        val decision = routing.router.open(RouteRequest("OrderList", JsonObject(emptyMap()), RouteSource.IN_APP))
        assertEquals(GuardKind.REQUIRE_CONSENT, decision.kind)
        privacy.requestFullFunction()

        now += 9 * 60_000L
        privacy.agree()
        looper.idle()
        assertEquals("同意后进入登录页", listOf("Login"), navigator.opened)
        val pending = PendingActionStore(SharedPreferencesPendingBackend(context), { now }).load()
        assertEquals("同意时重置计时（BR-ID-10）", now, pending?.timerStartedAtMillis)

        // 续时后再过 9 分钟（距点击 18 分钟）记录仍有效
        now += 9 * 60_000L
        assertNotNull(PendingActionStore(SharedPreferencesPendingBackend(context), { now }).load())
    }

    /** R1-04：基本模式点「我的」→ 不同意、仍不同意 → 丢弃待办；之后再开启完整功能并同意，不会自动跳到被拒绝的 Me。 */
    @Test
    fun decliningAgainInBasicModeDropsThePendingTarget() {
        val (privacy, routing, navigator) = basicModeRouting()
        routing.router.open(RouteRequest("Me", JsonObject(emptyMap()), RouteSource.IN_APP))
        privacy.requestFullFunction()
        assertNotNull(stored())
        privacy.disagree()
        privacy.stillDisagree()
        looper.idle()
        assertNull("明确拒绝：丢弃本次登记的待办", stored())

        now += 60_000L
        privacy.requestFullFunction()
        privacy.agree()
        looper.idle()
        assertTrue("不会自动打开被拒绝的目标", navigator.opened.isEmpty())
    }

    /** R1-05：基本模式补同意弹窗显示时，运行中收到的深链与推送丢弃；弹窗里主动点协议仍可只读打开。 */
    @Test
    fun runtimeLinksAreDroppedWhileTheConsentPromptShowsInBasicMode() {
        val (privacy, routing, navigator) = basicModeRouting()
        privacy.requestFullFunction()
        assertEquals(PrivacyState.BASIC_MODE, routing.privacy.value)
        routing.onLaunchIntent("couli-dev://r/Agreement?type=privacy", coldStart = false)
        routing.onLaunchIntent("couli-dev://r/Search?q=a", coldStart = false)
        routing.onPushClicked("m-1", coldStart = false)
        looper.idle()
        assertTrue("弹窗期间运行中外部入口全部丢弃", navigator.opened.isEmpty())

        // 用户在弹窗里主动点《隐私政策》：App 内只读通道照常
        val agreement = RouteRequest("Agreement", JsonObject(mapOf("type" to JsonPrimitive("privacy"))), RouteSource.IN_APP)
        routing.router.open(agreement)
        assertEquals(listOf("Agreement(readonly)"), navigator.opened)

        // 弹窗结束（仍不同意）后不补执行被丢弃的深链
        privacy.disagree()
        privacy.stillDisagree()
        looper.idle()
        assertEquals(listOf("Agreement(readonly)"), navigator.opened)
    }

    /** R1-01：撤回清理失败 → 仍记为基本模式；清理完成前会话按游客，不读任何凭据；之后读凭据前重试成功。 */
    @Test
    fun failedWithdrawCleanupBlocksCredentialReadsUntilRetrySucceeds() {
        store.decisionRaw = "consented"
        store.consentedVersion = "1"
        store.consentedAtMillis = 0L
        val privacy = privacy()
        val routing = routing(privacy)
        routing.session.value = SessionState.LOGGED_IN
        looper.idle()
        assertEquals(SessionState.LOGGED_IN.raw, routing.guardState().session)

        cleanupFails = true
        try {
            privacy.withdraw()
            fail("清理失败要抛给调用方")
        } catch (expected: IllegalStateException) {
            assertEquals("删除失败", expected.message)
        }
        assertTrue("隐私决定仍为基本模式", store.basicMode)
        assertFalse(privacy.credentialAccess.readable())
        assertEquals("清理完成前不读会话凭据：按游客", SessionState.GUEST.raw, routing.guardState().session)

        cleanupFails = false
        val before = cleanups
        assertEquals(SessionState.LOGGED_IN.raw, routing.guardState().session)
        assertEquals("读会话前重试了清理", before + 1, cleanups)
        assertTrue(privacy.credentialAccess.readable())
    }

    /** 编排补充 4：首启弹窗显示期间收到的热推送 / 热深链丢弃，不入队；同意后也不补执行。 */
    @Test
    fun hotPushDuringFirstLaunchPromptIsDroppedNotQueued() {
        val privacy = privacy()
        val routing = routing(privacy)
        val navigator = RecordingNavigator()
        routing.attach(navigator)
        looper.idle()
        routing.onPushClicked("m-1", coldStart = false)
        routing.onLaunchIntent("couli-dev://r/Search?q=a", coldStart = false)
        privacy.agree()
        looper.idle()
        assertTrue("热推送、热深链都没有执行", navigator.opened.isEmpty())
        assertNull(
            routing.router.lastOutcome.value
                ?.request
                ?.route
                ?.takeIf { it == "Messages" || it == "Search" },
        )
    }

    /** 编排补充 3：弹窗期间只丢新收到的；冷启动带来的意图保留，弹窗结束后交出一次。 */
    @Test
    fun coldStartIntentSurvivesTheConsentPromptAndIsReleasedAfterwards() {
        val privacy = privacy()
        privacy.disagree()
        privacy.stillDisagree()
        val routing = routing(privacy)
        routing.onLaunchIntent("couli-dev://r/Search?q=cold", coldStart = true)
        privacy.requestFullFunction()
        val navigator = RecordingNavigator()
        routing.attach(navigator)
        looper.idle()
        assertTrue("弹窗显示中：冷启动意图等待", navigator.opened.isEmpty())
        routing.onLaunchIntent("couli-dev://r/Search?q=hot", coldStart = false)

        privacy.disagree()
        privacy.stillDisagree()
        looper.idle()
        assertEquals("只交出冷启动那一条", listOf("Search(readonly)"), navigator.opened)
    }

    /** 编排补充 6：强更等待超时后保留的待办（仍缺登录）不会让重试时刻停在过去而空转。 */
    @Test
    fun keptPendingAfterUpdateTimeoutDoesNotSpinTheDrainTimer() {
        store.decisionRaw = "consented"
        store.consentedVersion = "1"
        store.consentedAtMillis = 0L
        val privacy = privacy()
        savePending("OrderList", RouteSource.IN_APP)
        val routing = routing(privacy)
        routing.update.value = UpdateCheck.CHECKING
        looper.idle()
        routing.attach(RecordingNavigator())
        looper.idle()
        now += 5_001L
        looper.idleFor(Duration.ofMillis(5_001L))
        assertNotNull("仍缺登录：保留", stored())
        val drains = routing.drainCount
        repeat(3) { now += 10_000L }
        looper.idleFor(Duration.ofSeconds(30))
        assertTrue("没有空转（多跑了 ${routing.drainCount - drains} 次）", routing.drainCount - drains <= 1)
    }
}
