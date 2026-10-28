package com.couli.app.me

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText as textIs
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.couli.app.account.AppAccountTexts
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.LocalCouliAnnouncer
import com.couli.core.me.MeLocalStore
import com.couli.core.me.account.AccountShared
import com.couli.core.wallet.StepUpTokenCache
import com.couli.core.wallet.WalletRateGate
import com.couli.core.wallet.stepup.SmsDeadlines
import com.couli.core.wallet.stepup.StepUpDeps
import com.couli.feature.me.AccountDeps
import com.couli.feature.me.AppealConfig
import com.couli.feature.me.AppealScreen
import com.couli.feature.me.LocalAccountDeps
import com.couli.feature.me.MeDeps
import com.couli.feature.me.MeGates
import com.couli.feature.me.MeTags
import com.couli.feature.me.RiskNoticeConfig
import com.couli.feature.me.RiskNoticeScreen
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.robolectric.Shadows.shadowOf

/**
 * APP-12w-c 的真实页面宿主（照 PrivacyVisualTestHost / SettingsVisualTestHost）：
 * - 账号状态：真实 RiskNoticeScreen → 真实 RiskNoticeController（页面级 ViewModel 持有，R1-08）→ 真实 MeGates 读取闸门；
 * - 申诉：真实 AppealScreen → 真实 AppealController（提交闸门、幂等键由 [MeDeps.newKey] 每次点击生成）；
 * - 注销入口：真实 DeleteAccountLink，经 LocalAccountDeps 提供真实 AccountDeps；路由可用性两档（公开版含 DeleteAccount、内测版不含）。
 * 只替换传输层（[api]）、环境出口（[env]、[accountEnv]）。顶部 54dp 是状态栏占位（画板顶部 54）。所有入口都用真实触摸驱动。
 */
@Suppress("TooManyFunctions") // 宿主逐项给真实入口与量具
internal class RiskVisualTestHost(
    private val rule: ComposeContentTestRule,
) : AutoCloseable {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val service = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val env = RiskVisualEnv()
    val api = RiskVisualApi()
    val accountEnv = RiskVisualAccountEnv(env)
    val announcements = MeVisualAnnouncementProbe(rule)
    val spoken get() = announcements.messages
    val palette = RiskVisualPalette()
    lateinit var deps: MeDeps
    lateinit var account: AccountDeps
    var backs = 0
    var scrimSamples = false
    val keys = mutableListOf<String>()

    /** 按 fixture 预置报文：getMe（risk）、listAppeals、listWithdrawals；进入标记与注销路由可用性。已有的 [RiskVisualApi.answers] 不覆盖。 */
    fun prime(
        risk: String,
        appeals: String,
        withdrawals: String,
        entered10006: Boolean?,
        public: Boolean,
    ) {
        api.answers.getOrPut(RiskVisualApi.GET_ME) { RiskVisualFixtures.ok(RiskVisualFixtures.me(risk)) }
        api.answers.getOrPut(RiskVisualApi.LIST_APPEALS) { RiskVisualFixtures.ok(RiskVisualFixtures.appeals(appeals)) }
        api.answers.getOrPut(RiskVisualApi.LIST_WITHDRAWALS) { RiskVisualFixtures.ok(RiskVisualFixtures.withdrawals(withdrawals)) }
        env.entered10006 = entered10006 ?: RiskVisualFixtures.entered10006(risk)
        env.routes = RiskVisualFixtures.routes(public)
    }

    @Suppress("LongParameterList")
    fun launchRisk(
        risk: String = "risk_banned",
        appeals: String = "appeals_empty",
        withdrawals: String = "withdrawals_pending",
        entered10006: Boolean? = null,
        public: Boolean = true,
        dark: Boolean = false,
        fontScale: Float = 1f,
        wait: Boolean = true,
    ) {
        prime(risk, appeals, withdrawals, entered10006, public)
        prepareDeps()
        show(dark, fontScale) { RiskNoticeScreen(RiskNoticeConfig(deps = deps, onBack = { backs += 1 })) }
        idle()
        if (wait) settle("账号状态已显示") { has(MeTags.RISK_PRIMARY) || has(MeTags.RISK_ERROR) }
    }

    @Suppress("LongParameterList")
    fun launchAppeal(
        risk: String = "risk_banned",
        appeals: String = "appeals_empty",
        entered10006: Boolean? = null,
        public: Boolean = true,
        dark: Boolean = false,
        fontScale: Float = 1f,
        wait: Boolean = true,
    ) {
        prime(risk, appeals, "withdrawals_none", entered10006, public)
        prepareDeps()
        show(dark, fontScale) { AppealScreen(AppealConfig(deps = deps, onBack = { backs += 1 })) }
        idle()
        if (wait) {
            settle("申诉页已显示") {
                listOf(MeTags.APPEAL_INPUT, MeTags.APPEAL_PROGRESS, MeTags.APPEAL_RESULT, MeTags.APPEAL_LOAD_ERROR).any(::has)
            }
        }
    }

    /** 按 fixture 画板登记的状态打开对应页面（截图入口与逐张对照用）。 */
    fun launchBoard(
        board: String,
        dark: Boolean = RiskVisualFixtures.dark(board),
        fontScale: Float = 1f,
        public: Boolean = true,
    ) {
        val b = RiskVisualFixtures.board(board)
        val risk = RiskVisualFixtures.text("risk", b)
        val appeals = RiskVisualFixtures.text("appeals", b)
        val entered = RiskVisualFixtures.text("entered10006", b).toBoolean()
        if (RiskVisualFixtures.text("page", b) == "risk") {
            launchRisk(risk, appeals, RiskVisualFixtures.text("withdrawals", b), entered, public, dark, fontScale)
        } else {
            launchAppeal(risk, appeals, entered, public, dark, fontScale)
        }
    }

    private fun prepareDeps() {
        val clock = { RiskVisualFixtures.now }
        deps =
            MeDeps(
                api,
                env,
                MeGates(service, clock = clock),
                AppMeTexts,
                MeLocalStore(context).also { it.clearAll() },
                clock = clock,
                newKey = { "risk-visual-click-${keys.size + 1}".also { keys += it } },
                service = service,
            )
        accountEnv.accountIdentityFlow.value = accountEnv.accountIdentityFlow.value.copy(me = env.identity())
        val stepUp =
            StepUpDeps(
                api = api,
                env = accountEnv,
                smsGate = WalletRateGate(service, clock),
                verifyGate = WalletRateGate(service, clock),
                sms = SmsDeadlines(),
                tokens = StepUpTokenCache(),
                clock = clock,
                service = service,
                texts = AppAccountTexts,
            )
        account =
            AccountDeps(
                me = deps,
                api = RiskVisualAccountApi(api),
                env = accountEnv,
                stepUp = stepUp,
                shared = AccountShared(service, clock) { _, _ -> true },
                texts = AppAccountTexts,
                walletRead = WalletRateGate(service, clock),
                deletionRead = WalletRateGate(service, clock),
                clock = clock,
                service = service,
            )
    }

    private fun show(
        dark: Boolean,
        fontScale: Float,
        page: @Composable () -> Unit,
    ) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalCouliAnnouncer provides announcements.announcer,
                LocalAccountDeps provides account,
            ) {
                CouliTheme(darkTheme = dark) {
                    palette.Capture()
                    Column(Modifier.fillMaxSize().background(CouliColors.backgroundCanvas)) {
                        Row(Modifier.fillMaxWidth().height(STATUS_BAR.dp)) {
                            if (scrimSamples) {
                                Box(Modifier.size(16.dp).background(CouliColors.backgroundCanvas).testTag(SCRIM_CANVAS))
                                Box(Modifier.size(16.dp).background(Color.White).testTag(SCRIM_WHITE))
                            }
                        }
                        page()
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- 操作（真实触摸）

    fun node(tag: String): SemanticsNodeInteraction = rule.onNodeWithTag(tag, useUnmergedTree = true)

    fun has(tag: String): Boolean = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    fun count(text: String): Int = rule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().size

    /** 真实触摸点一次；只有可滚动祖先时才先滚到可见（弹窗里不调 performScrollTo）。 */
    fun tap(node: SemanticsNodeInteraction) {
        if (node.insideScroll()) node.performScrollTo()
        node.performTouchInput { click(center) }
        idle()
    }

    fun tap(tag: String) = tap(node(tag))

    /** 节点上已注册的点击回调（「关闭 / 换版本 / 换身份后再调旧回调」的零动作断言；不经界面）。 */
    fun clickAction(node: SemanticsNodeInteraction): () -> Unit {
        val action =
            node
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        return { action() }
    }

    /** 申诉输入：标记本身或标记之下唯一可设置文字的节点（旧 OutlinedTextField 与组件库 CouliTextField 都命中，D10-378 同口径）。 */
    fun editable(): SemanticsNodeInteraction =
        rule.onNode(
            hasSetTextAction() and (hasTestTag(MeTags.APPEAL_INPUT) or hasAnyAncestor(hasTestTag(MeTags.APPEAL_INPUT))),
            useUnmergedTree = true,
        )

    fun input(text: String) {
        editable().performTextReplacement(text)
        idle()
    }

    /** submitAppeal 挂起（造「提交中」）；返回放行器。 */
    fun holdSubmit(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { api.hold = it }

    fun release(gate: CompletableDeferred<Unit>) {
        rule.runOnUiThread { gate.complete(Unit) }
        idle()
    }

    /** 弹窗里的文字（祖先带 paneTitle）。 */
    fun dialogText(text: String): SemanticsNodeInteraction = rule.onNode(textIs(text) and hasAnyAncestor(PANE), useUnmergedTree = true)

    /** 页面上的读屏标题（不在弹窗里）。 */
    fun pageHeading(text: String): SemanticsNodeInteraction =
        rule.onNode(
            textIs(text) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading) and !hasAnyAncestor(PANE),
            useUnmergedTree = true,
        )

    /** 页面上的纯文字（不在弹窗里）。 */
    fun pageText(text: String): SemanticsNodeInteraction = rule.onNode(textIs(text) and !hasAnyAncestor(PANE), useUnmergedTree = true)

    /** 页面上文字恰为 [text] 的全部未合并节点，按从上到下排序（如进度页的「处理中」胶囊在上、时间线步骤在下）。 */
    fun pageTexts(text: String): List<SemanticsNodeInteraction> {
        val ids =
            rule
                .onAllNodes(textIs(text) and !hasAnyAncestor(PANE), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .sortedBy { it.boundsInRoot.top }
                .map { it.id }
        return ids.map { id -> rule.onNode(SemanticsMatcher("节点 $id") { it.id == id }, useUnmergedTree = true) }
    }

    /** 含 [node] 的那个窗口（页面 / 弹窗）的根语义节点。 */
    fun rootOf(node: SemanticsNodeInteraction): SemanticsNode {
        val target = node.fetchSemanticsNode()
        return rule
            .onAllNodes(isRoot(), useUnmergedTree = false)
            .fetchSemanticsNodes()
            .first { it.root === target.root }
    }

    /** [node] 所在窗口的整窗像素（坐标与 boundsInRoot 一致）。 */
    fun pixels(node: SemanticsNodeInteraction): Bitmap {
        val view = (node.fetchSemanticsNode().root as ViewRootForTest).view
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(Canvas(image)) }
        return image
    }

    fun idle() {
        repeat(IDLE_ROUNDS) {
            shadowOf(Looper.getMainLooper()).idle()
            rule.waitForIdle()
        }
    }

    /** CouliDialog 打开后 FocusOnAppear 延时 150ms 才请求焦点：断言焦点前推进主时钟再空转（APP-12w-b 测试 v2 T5）。 */
    fun settleFocus() {
        rule.mainClock.advanceTimeBy(FOCUS_SETTLE_MS)
        idle()
    }

    fun settle(
        what: String,
        condition: () -> Boolean,
    ) {
        repeat(SETTLE_ROUNDS) {
            idle()
            if (condition()) return
            Thread.sleep(SETTLE_SLEEP_MS)
        }
        error("等待超时：$what")
    }

    override fun close() {
        rule.runOnUiThread {
            api.hold?.complete(Unit)
            service.cancel()
        }
    }

    companion object {
        const val STATUS_BAR = 54
        const val SCRIM_CANVAS = "risk_visual_scrim_canvas"
        const val SCRIM_WHITE = "risk_visual_scrim_white"
        val PANE: SemanticsMatcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle)
        const val FOCUS_SETTLE_MS = 200L
        private const val IDLE_ROUNDS = 4
        private const val SETTLE_ROUNDS = 200
        private const val SETTLE_SLEEP_MS = 10L
    }
}

/** 渲染时的令牌色（页面不写色值，测试也只比令牌；浅 / 深各取一次）。 */
internal class RiskVisualPalette {
    var canvas = Color.Unspecified
    var surface = Color.Unspecified
    var muted = Color.Unspecified
    var primary = Color.Unspecified
    var secondary = Color.Unspecified
    var link = Color.Unspecified
    var brand = Color.Unspecified
    var subtle = Color.Unspecified
    var control = Color.Unspecified
    var scrim = Color.Unspecified
    var pendingText = Color.Unspecified
    var pendingBackground = Color.Unspecified
    var warningText = Color.Unspecified
    var warningBackground = Color.Unspecified
    var errorText = Color.Unspecified
    var errorBackground = Color.Unspecified
    var successText = Color.Unspecified
    var successBackground = Color.Unspecified
    var buttonPrimary = Color.Unspecified

    @Composable
    @ReadOnlyComposable
    fun Capture() {
        canvas = CouliColors.backgroundCanvas
        surface = CouliColors.backgroundSurface
        muted = CouliColors.backgroundMuted
        primary = CouliColors.textPrimary
        secondary = CouliColors.textSecondary
        link = CouliColors.textLink
        brand = CouliColors.brandPrimary
        subtle = CouliColors.borderSubtle
        control = CouliColors.borderControl
        scrim = CouliColors.overlayScrim
        pendingText = CouliColors.statusPendingText
        pendingBackground = CouliColors.statusPendingBackground
        warningText = CouliColors.statusWarningText
        warningBackground = CouliColors.statusWarningBackground
        errorText = CouliColors.statusErrorText
        errorBackground = CouliColors.statusErrorBackground
        successText = CouliColors.statusSuccessText
        successBackground = CouliColors.statusSuccessBackground
        buttonPrimary = CouliColors.buttonPrimaryDefaultBackground
    }
}
