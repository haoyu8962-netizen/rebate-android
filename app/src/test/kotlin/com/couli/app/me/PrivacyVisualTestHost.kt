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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText as textIs
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.couli.core.clientcore.AmountMaskStore
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.LocalCouliAnnouncer
import com.couli.core.me.CustomerServiceEntry
import com.couli.core.me.MeLocalStore
import com.couli.feature.me.AboutScreen
import com.couli.feature.me.CsSheetHost
import com.couli.feature.me.MeDeps
import com.couli.feature.me.MeGates
import com.couli.feature.me.MeTabScreen
import com.couli.feature.me.MeTags
import com.couli.feature.me.PrivacyCenterConfig
import com.couli.feature.me.PrivacyCenterScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.robolectric.Shadows.shadowOf

/**
 * APP-12w-b 的真实页面宿主（照 SettingsVisualTestHost / MeVisualTestHost）：
 * - 隐私中心：真实 PrivacyCenterScreen → 真实 PrivacyCenterController（页面级 ViewModel 持有，R1-08）→ 真实 MeLocalStore / MeGates；只替换传输层
 *   （[api]）与环境出口（[env]：路由、系统设置、首启弹窗、撤回协调器、强更上下文）。
 * - 关于：真实 AboutScreen → 真实 AboutController → 真实 Diagnostics；剪贴板统一写入口由 [env] 记账。
 * - 客服：真实 CsSheetHost + 真实 CustomerServiceEntry，底稿为真实「我的 · 已登录」（MeTabScreen），只替换 [ports]。
 * 顶部 54dp 是状态栏占位（画板顶部 54）。所有入口都用真实触摸驱动。
 */
@Suppress("TooManyFunctions") // 宿主逐项给真实入口与量具
internal class PrivacyVisualTestHost(
    private val rule: ComposeContentTestRule,
) : AutoCloseable {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val service = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val env = PrivacyVisualEnv()
    val api = PrivacyVisualApi()
    val ports = PrivacyVisualCsPorts()
    val entry = CustomerServiceEntry(ports)
    val announcements = MeVisualAnnouncementProbe(rule)
    val spoken get() = announcements.messages
    val palette = PrivacyVisualPalette()
    lateinit var deps: MeDeps
    lateinit var local: MeLocalStore
    private var underlay: MeDeps? = null
    var backs = 0
    var scrimSamples = false

    @Suppress("LongParameterList")
    fun launchPrivacy(
        session: String = "privacy_member",
        dark: Boolean = false,
        fontScale: Float = 1f,
        withdrawing: Boolean = false,
        personalization: Boolean? = null,
        statusBar: Boolean = true,
    ) {
        env.identityFlow.value = PrivacyVisualEnv.of(PrivacyVisualFixtures.level(session))
        env.withdrawing.value = withdrawing
        env.forceContext = PrivacyVisualFixtures.forceUpdate(session)
        env.registered = PrivacyVisualFixtures.deviceRegistered(session)
        env.legalVersion = PrivacyVisualFixtures.legalPrivacyVersion(session)
        env.statuses = listOf("push", "photos").associateWith { PrivacyVisualFixtures.permissionStatus(session, it) }
        prepareDeps(personalization ?: PrivacyVisualFixtures.personalization(session))
        show(dark, fontScale, statusBar) {
            PrivacyCenterScreen(PrivacyCenterConfig(deps = deps, onBack = { backs += 1 }))
        }
        idle()
        settle("隐私中心已显示") { has(MeTags.PRIVACY_PAGE) }
    }

    fun launchAbout(
        filed: Boolean = true,
        dark: Boolean = false,
        fontScale: Float = 1f,
        copyOk: Boolean = true,
    ) {
        env.config = PrivacyVisualFixtures.config(if (filed) PrivacyVisualFixtures.filed else PrivacyVisualFixtures.unfiled)
        env.copyOk = copyOk
        prepareDeps(true)
        show(dark, fontScale, statusBar = true) {
            AboutScreen(deps = deps, onBack = { backs += 1 })
        }
        idle()
        settle("关于页已显示") { has(MeTags.ABOUT_COPY) }
    }

    /**
     * 客服半屏：底稿「我的 · 已登录」+ 真实 CsSheetHost；按 fixture 的 cs_* 情形配置 [ports] 后经真实入口打开。
     * cs_open_failed 走桥入口（拉起 false → CsOpenFailed），其余走 [CustomerServiceEntry.open]。到达状态前那次拉起不计入账本。
     */
    fun launchCs(
        case: String = "cs_open_failed",
        dark: Boolean = false,
        fontScale: Float = 1f,
    ) {
        ports.apply(case)
        val meEnv = MeVisualEnv()
        val meApi = MeVisualApi().also { it.answer = MeVisualFixtures.ok(MeVisualFixtures.profile("me_member")) }
        val clock = { MeVisualFixtures.now }
        context
            .getSharedPreferences("couli_amount_mask", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val mask = AmountMaskStore(context)
        val me =
            MeDeps(
                meApi,
                meEnv,
                MeGates(service, clock = clock),
                AppMeTexts,
                MeLocalStore(context).also { it.clearAll() },
                clock = clock,
                service = service,
            )
        underlay = me
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalCouliAnnouncer provides announcements.announcer,
            ) {
                CouliTheme(darkTheme = dark) {
                    palette.Capture()
                    Box(Modifier.fillMaxSize().background(CouliColors.backgroundCanvas)) {
                        Column(Modifier.fillMaxSize()) {
                            Row(Modifier.fillMaxWidth().height(STATUS_BAR.dp)) {}
                            MeTabScreen(me, mask)
                        }
                        CsSheetHost(entry, AppMeTexts)
                    }
                }
            }
        }
        idle()
        settle("我的底稿已显示") { has(MeTags.ME_NICKNAME) }
        rule.runOnUiThread {
            if (PrivacyVisualFixtures.text("via", PrivacyVisualFixtures.case(case)) == "bridge") {
                entry.openForBridge("Me")
            } else {
                entry.open("Me")
            }
        }
        idle()
        settle("客服半屏已显示") { has(MeTags.CS_SHEET) }
        ports.opened.clear()
    }

    private fun prepareDeps(personalization: Boolean) {
        val clock = { PrivacyVisualFixtures.now }
        local =
            MeLocalStore(context).also {
                it.clearAll()
                it.setPersonalization(personalization, PrivacyVisualFixtures.now)
            }
        deps =
            MeDeps(
                api,
                env,
                MeGates(service, clock = clock),
                AppMeTexts,
                local,
                clock = clock,
                service = service,
            )
    }

    private fun show(
        dark: Boolean,
        fontScale: Float,
        statusBar: Boolean,
        page: @androidx.compose.runtime.Composable () -> Unit,
    ) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalCouliAnnouncer provides announcements.announcer,
            ) {
                CouliTheme(darkTheme = dark) {
                    palette.Capture()
                    Column(Modifier.fillMaxSize().background(CouliColors.backgroundCanvas)) {
                        if (statusBar) {
                            Row(Modifier.fillMaxWidth().height(STATUS_BAR.dp)) {
                                if (scrimSamples) {
                                    Box(Modifier.size(16.dp).background(CouliColors.backgroundCanvas).testTag(SCRIM_CANVAS))
                                    Box(Modifier.size(16.dp).background(Color.White).testTag(SCRIM_WHITE))
                                }
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

    fun countTag(tag: String): Int = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    /** 真实触摸点一次；只有可滚动祖先时才先滚到可见（半屏、弹窗里不调 performScrollTo）。 */
    fun tap(node: SemanticsNodeInteraction) {
        if (node.insideScroll()) node.performScrollTo()
        node.performTouchInput { click(center) }
        idle()
    }

    fun tap(tag: String) = tap(node(tag))

    /** 节点上已注册的点击回调（用于「关闭 / 换身份后再调旧回调」的零动作断言；不经界面）。 */
    fun clickAction(node: SemanticsNodeInteraction): () -> Unit {
        val action =
            node
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        return { action() }
    }

    /** 弹窗里的文字（祖先带 paneTitle，区别于页面上同名的「撤回同意」标题与按钮）。 */
    fun dialogText(text: String): SemanticsNodeInteraction = rule.onNode(textIs(text) and hasAnyAncestor(PANE), useUnmergedTree = true)

    /** 页面上的读屏标题（不在弹窗里）。 */
    fun pageHeading(text: String): SemanticsNodeInteraction =
        rule.onNode(
            textIs(text) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading) and !hasAnyAncestor(PANE),
            useUnmergedTree = true,
        )

    /** 页面上的纯文字（不在弹窗、不在半屏标记之下）。 */
    fun pageText(text: String): SemanticsNodeInteraction = rule.onNode(textIs(text) and !hasAnyAncestor(PANE), useUnmergedTree = true)

    fun closeButton(): SemanticsNodeInteraction =
        rule.onNode(hasContentDescription(AppMeTexts.text("me.close")) and hasClickAction(), useUnmergedTree = true)

    /** 半屏窗口里的文字（客服半屏标题 / 说明 / 安全提示）：排除「我的」底稿所在窗口（底稿宫格也有「联系客服」）。 */
    fun sheetText(text: String): SemanticsNodeInteraction {
        val page = node(MeTags.ME_PAGE).fetchSemanticsNode().root
        val ids =
            rule
                .onAllNodes(textIs(text), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .filter { it.root !== page }
                .map { it.id }
                .toSet()
        return rule.onNode(SemanticsMatcher("半屏窗口里的「$text」") { it.id in ids }, useUnmergedTree = true)
    }

    /** 含 [node] 的那个窗口（页面 / 弹窗 / 半屏）的根语义节点。 */
    fun rootOf(node: SemanticsNodeInteraction): SemanticsNode {
        val target = node.fetchSemanticsNode()
        return rule
            .onAllNodes(isRoot(), useUnmergedTree = false)
            .fetchSemanticsNodes()
            .first { it.root === target.root }
    }

    /** [node] 所在窗口的整窗像素（页面、确认框、半屏各自的 Compose 根；坐标与 boundsInRoot 一致）。 */
    fun pixels(node: SemanticsNodeInteraction): Bitmap {
        val view = (node.fetchSemanticsNode().root as ViewRootForTest).view
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(Canvas(image)) }
        return image
    }

    fun idle() {
        repeat(4) {
            shadowOf(Looper.getMainLooper()).idle()
            rule.waitForIdle()
        }
    }

    /**
     * v2（T5）：CouliDialog / CouliSheet 打开后由 FocusOnAppear 延时 150ms 才请求焦点；断言焦点前推进主时钟 [FOCUS_SETTLE_MS] 再空转。
     */
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
            underlay?.tab?.leave()
            service.cancel()
        }
    }

    companion object {
        const val STATUS_BAR = 54
        const val SCRIM_CANVAS = "privacy_visual_scrim_canvas"
        const val SCRIM_WHITE = "privacy_visual_scrim_white"
        val PANE: SemanticsMatcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle)
        const val FOCUS_SETTLE_MS = 200L
        private const val SETTLE_ROUNDS = 200
        private const val SETTLE_SLEEP_MS = 10L
    }
}

/** 渲染时的令牌色（页面不写色值，测试也只比令牌）。 */
internal class PrivacyVisualPalette {
    var canvas = Color.Unspecified
    var surface = Color.Unspecified
    var muted = Color.Unspecified
    var primary = Color.Unspecified
    var secondary = Color.Unspecified
    var link = Color.Unspecified
    var success = Color.Unspecified
    var warningText = Color.Unspecified
    var warningBackground = Color.Unspecified
    var error = Color.Unspecified
    var subtle = Color.Unspecified
    var control = Color.Unspecified
    var scrim = Color.Unspecified
    var brand = Color.Unspecified
    var buttonPrimary = Color.Unspecified

    @androidx.compose.runtime.Composable
    @androidx.compose.runtime.ReadOnlyComposable
    fun Capture() {
        canvas = CouliColors.backgroundCanvas
        surface = CouliColors.backgroundSurface
        muted = CouliColors.backgroundMuted
        primary = CouliColors.textPrimary
        secondary = CouliColors.textSecondary
        link = CouliColors.textLink
        success = CouliColors.statusSuccessText
        warningText = CouliColors.statusWarningText
        warningBackground = CouliColors.statusWarningBackground
        error = CouliColors.statusErrorText
        subtle = CouliColors.borderSubtle
        control = CouliColors.borderControl
        scrim = CouliColors.overlayScrim
        brand = CouliColors.brandPrimary
        buttonPrimary = CouliColors.buttonPrimaryDefaultBackground
    }
}

/** [rect] 内颜色接近 [color] 的像素外接框；没有为 null。 */
internal fun Bitmap.pvColorBox(
    rect: Rect,
    color: Color,
    tolerance: Int = 6,
): Rect? {
    var left = Int.MAX_VALUE
    var top = Int.MAX_VALUE
    var right = Int.MIN_VALUE
    var bottom = Int.MIN_VALUE
    for (x in rect.left.toInt().coerceAtLeast(0) until rect.right.toInt().coerceAtMost(width)) {
        for (y in rect.top.toInt().coerceAtLeast(0) until rect.bottom.toInt().coerceAtMost(height)) {
            if (sameColor(getPixel(x, y), color, tolerance)) {
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x + 1)
                bottom = maxOf(bottom, y + 1)
            }
        }
    }
    return if (left == Int.MAX_VALUE) null else Rect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
}

/** 合并树按遍历顺序展开（读屏顺序近似；不代替实机 TalkBack）。 */
internal fun SemanticsNode.pvFlatten(): List<SemanticsNode> = listOf(this) + children.flatMap { it.pvFlatten() }
