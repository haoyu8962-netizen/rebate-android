package com.couli.app.wallet.visual

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.couli.core.designsystem.CouliColors
import com.couli.core.wallet.WalletDraftTexts
import com.couli.core.wallet.stepup.StepUpController
import com.couli.feature.wallet.WalletTags
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/** 渲染时从 CouliTheme 取出的令牌（浅 / 深各自取值，不在测试里写死颜色）。 */
internal data class StepUpTokens(
    val surface: Color,
    val borderSubtle: Color,
    val error: Color,
    val secondary: Color,
    val scrim: Color,
)

/** 某一窗口（主窗口或半屏所在的对话框窗口）的实际像素；[dx]/[dy] 是 Compose 根在该窗口里的偏移。 */
internal class StepUpShot(
    val bitmap: Bitmap,
    private val dx: Float,
    private val dy: Float,
) {
    fun px(bounds: Rect): Rect = bounds.translate(dx, dy)

    fun opaque(
        x: Int,
        y: Int,
    ): Boolean = x in 0 until bitmap.width && y in 0 until bitmap.height && android.graphics.Color.alpha(bitmap.getPixel(x, y)) == OPAQUE

    /** 与底色差得足够远的不透明像素（描边、文字、图标墨迹）。 */
    fun ink(
        x: Int,
        y: Int,
        background: Color,
    ): Boolean = opaque(x, y) && distance(bitmap.getPixel(x, y), background.toArgb()) > INK_DISTANCE

    fun near(
        x: Int,
        y: Int,
        color: Color,
        tolerance: Int,
    ): Boolean = opaque(x, y) && distance(bitmap.getPixel(x, y), color.toArgb()) <= tolerance

    /** 一条水平线上连续墨迹段（像素区间，含两端）。 */
    fun horizontalRuns(
        y: Int,
        from: Int,
        to: Int,
        background: Color,
    ): List<IntRange> = runs((from..to).map { ink(it, y, background) }, from)

    fun verticalRuns(
        x: Int,
        from: Int,
        to: Int,
        background: Color,
    ): List<IntRange> = runs((from..to).map { ink(x, it, background) }, from)

    private fun runs(
        marks: List<Boolean>,
        origin: Int,
    ): List<IntRange> {
        val result = mutableListOf<IntRange>()
        var start = -1
        marks.forEachIndexed { index, on ->
            if (on && start < 0) start = index
            if (!on && start >= 0) {
                result += (origin + start) until (origin + index)
                start = -1
            }
        }
        if (start >= 0) result += (origin + start)..(origin + marks.lastIndex)
        return result
    }

    companion object {
        const val OPAQUE = 255
        const val INK_DISTANCE = 48

        fun distance(
            a: Int,
            b: Int,
        ): Int =
            abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) +
                abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)) +
                abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b))
    }
}

/**
 * 二次验证半屏（APP-09v-d）的到达方式：真实提现页 / 收款账号页 → 真实提交 → 服务端 10003 → 生产代码打开半屏并发码。
 * 只控制网络答复，不伪造 StepUpUiState。
 */
internal class StepUpVisualKit(
    private val rule: ComposeContentTestRule,
    val host: WithdrawVisualTestHost,
) {
    var tokens: StepUpTokens? = null
        private set

    fun open(
        payout: Boolean = false,
        dark: Boolean = false,
        fontScale: Float = 1f,
        sample: Boolean = false,
    ) {
        host.api.write = WithdrawVisualFixtures.failure(STEP_UP_REQUIRED)
        host.reference = {
            tokens =
                StepUpTokens(
                    CouliColors.backgroundSurface,
                    CouliColors.borderSubtle,
                    CouliColors.statusErrorText,
                    CouliColors.textSecondary,
                    CouliColors.overlayScrim,
                )
            if (sample) Box(Modifier.size(16.dp).background(Color.White).testTag(SCRIM_WHITE))
        }
        host.launch(payout = payout, dark = dark, fontScale = fontScale, token = false)
        if (payout) {
            rule.onNodeWithTag(WalletTags.PAYOUT_CHANGE).performScrollTo().performClick()
            rule.onNodeWithTag(WalletTags.PAYOUT_LOGON).performScrollTo().performTextReplacement("13800005678")
            rule.onNodeWithTag(WalletTags.PAYOUT_PAYEE).performScrollTo().performTextReplacement("张三")
            rule.onNodeWithTag(WalletTags.PAYOUT_SAVE).performClick()
        } else {
            host.input("10")
            host.settleEstimate()
            rule.onNodeWithTag(WalletTags.WITHDRAW_SUBMIT).performClick()
        }
        rule.waitForIdle()
        rule.onNodeWithTag(WalletTags.STEP_UP_CODE, useUnmergedTree = true).assertExists()
        val write = if (payout) "savePayoutAccount" else "createWithdrawal"
        assertEquals("打开半屏只发一次原写请求与一次发码", listOf(write, "sendSmsCode"), host.api.writes().map { it.operationId })
    }

    fun tokens(): StepUpTokens = checkNotNull(tokens) { "主题令牌未取到" }

    fun title(action: String = "withdraw"): String = WalletDraftTexts.text(StepUpController.titleKey(action, WalletDraftTexts::has))

    fun node(
        matcher: SemanticsMatcher,
        what: String,
    ): SemanticsNode {
        val nodes = rule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()
        if (nodes.isEmpty()) fail("半屏里找不到：$what")
        return nodes.first()
    }

    fun inSheet(matcher: SemanticsMatcher): SemanticsMatcher = matcher and hasAnyAncestor(hasTestTag(WalletTags.STEP_UP_SHEET))

    fun titleNode(action: String = "withdraw"): SemanticsNode =
        node(inSheet(hasText(title(action)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)), "标题（heading）")

    fun sentNode(): SemanticsNode = node(inSheet(hasText(WalletDraftTexts.text("step_up.sent"))), "「验证码已发送至你绑定的手机号」")

    fun countdownNode(): SemanticsNode = node(inSheet(hasText(COUNTDOWN_TAIL, substring = true)), "「n 秒后可重新获取」倒计时（login.code.countdown）")

    fun tagged(tag: String): SemanticsNode = node(hasTestTag(tag), tag)

    fun shot(node: SemanticsNode): StepUpShot {
        val owner = (node.root as ViewRootForTest).view
        val window = owner.rootView
        val o = IntArray(2).also(owner::getLocationOnScreen)
        val w = IntArray(2).also(window::getLocationOnScreen)
        val image = Bitmap.createBitmap(window.width, window.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { window.draw(Canvas(image)) }
        return StepUpShot(image, (o[0] - w[0]).toFloat(), (o[1] - w[1]).toFloat())
    }

    companion object {
        const val STEP_UP_REQUIRED = 10003
        const val SCRIM_WHITE = "step_up_scrim_white"
        const val COUNTDOWN_TAIL = "秒后可重新获取"
        const val FULL_COUNTDOWN = "60 秒后可重新获取"
    }
}

internal fun SemanticsNode.density(): Float = layoutInfo.density.density

internal fun SemanticsNode.dp(px: Float): Float = px / density()

/** 未裁切的实际布局矩形（与 boundsInRoot 比较，裁切判据同时看尺寸与位置）。 */
internal fun SemanticsNode.unclipped(): Rect {
    val p = layoutInfo.coordinates.positionInRoot()
    return Rect(p.x, p.y, p.x + layoutInfo.width, p.y + layoutInfo.height)
}

internal fun SemanticsNode.assertNotClipped(what: String) {
    val full = unclipped()
    val shown = boundsInRoot
    val d = density()
    assertEquals("$what 可见宽度不得被裁切", full.width / d, shown.width / d, 1f)
    assertEquals("$what 可见高度不得被裁切", full.height / d, shown.height / d, 1f)
    assertEquals("$what 可见位置与未裁切位置一致（左）", full.left / d, shown.left / d, 1f)
    assertEquals("$what 可见位置与未裁切位置一致（上）", full.top / d, shown.top / d, 1f)
}

internal fun SemanticsNode.textLayout(): TextLayoutResult {
    val results = mutableListOf<TextLayoutResult>()
    val action = config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
    assertTrue("节点没有文字布局：$this", action != null && action(results) && results.isNotEmpty())
    return results.first()
}

internal fun SemanticsNode.drawnText(): String =
    config
        .getOrNull(SemanticsProperties.Text)
        .orEmpty()
        .joinToString("") { it.text }

internal fun SemanticsNode.isAncestorTagged(tag: String): Boolean {
    var current = parent
    while (current != null) {
        if (current.config.getOrNull(SemanticsProperties.TestTag) == tag) return true
        current = current.parent
    }
    return false
}

internal fun SemanticsNode.clickable(): Boolean =
    config.getOrNull(SemanticsActions.OnClick) != null && config.getOrNull(SemanticsProperties.Disabled) == null

internal fun assertSameColor(
    what: String,
    expected: Color,
    actual: Color,
) {
    val e = expected.toArgb()
    val a = actual.toArgb()
    assertTrue(
        "$what：期望 #%08X 实际 #%08X".format(e, a),
        StepUpShot.distance(e, a) <= 3 && android.graphics.Color.alpha(e) == android.graphics.Color.alpha(a),
    )
}
