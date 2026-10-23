package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.text.font.FontWeight
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.CouliComponentTokens
import com.couli.core.wallet.WalletDraftTexts
import com.couli.feature.wallet.WalletTags
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * APP-09v-d W63–W66：把手（装饰）、标题键不变、说明挪到标题区、6 格验证码 + 读屏专用标签；以及半屏遮罩令牌（a/b/c 评审抓过的 overlayScrim）。
 * 依据 WithdrawStepUp-v2.dc.html（a4c1a513c313）/ WithdrawStepUpDark.dc.html（adc11c54ead6）与 brief-android §2.5。先正后反：每项先确认目标存在再比尺寸 / 颜色。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StepUpVisualStructureTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WithdrawVisualTestHost(rule) }
    private val kit by lazy { StepUpVisualKit(rule, host) }

    @After fun close() = host.close()

    // ---------------------------------------------------------------- W63 把手

    @Test fun w63HandleIs36x4BorderSubtleCenteredAboveTitleLight() = handle(dark = false)

    @Test fun w63HandleIs36x4BorderSubtleCenteredAboveTitleDark() = handle(dark = true)

    @Test fun w63HandleIsDecorativeWithoutSemanticsOrActions() {
        kit.open()
        val title = kit.titleNode()
        val above =
            rule
                .onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .filter { it.root == title.root && it.boundsInRoot.height > 0f && it.boundsInRoot.bottom <= title.boundsInRoot.top }
        above.forEach {
            val c = it.config
            assertTrue("把手不得可点 / 可展开收起：$it", c.getOrNull(SemanticsActions.OnClick) == null)
            assertTrue(c.getOrNull(SemanticsActions.Expand) == null && c.getOrNull(SemanticsActions.Collapse) == null)
            assertTrue(c.getOrNull(SemanticsActions.Dismiss) == null && c.getOrNull(SemanticsActions.CustomActions) == null)
            assertTrue("把手不得有读屏文字（clearAndSetSemantics）：$it", c.getOrNull(SemanticsProperties.ContentDescription).isNullOrEmpty())
            assertTrue(c.getOrNull(SemanticsProperties.Text).isNullOrEmpty())
        }
    }

    @Test fun w63HandleDoesNotEnableSwipeOrOutsideDismiss() {
        kit.open()
        val sheet = kit.tagged(WalletTags.STEP_UP_SHEET)
        val before = host.api.events.toList()
        rule.onNodeWithTag(WalletTags.STEP_UP_SHEET).performTouchInput { swipeDown(startY = 1f, endY = height.toFloat() * 2) }
        rule.waitForIdle()
        assertTrue("下滑不关闭（共性第 48 条）", host.flow.stepUp.showing)
        rule.onNodeWithTag(WalletTags.STEP_UP_SHEET).performTouchInput {
            click(Offset(width / 2f, -sheet.boundsInRoot.top / 2f))
        }
        rule.waitForIdle()
        assertTrue("点遮罩不关闭（共性第 48 条）", host.flow.stepUp.showing)
        assertEquals("手势不发任何请求", before, host.api.events)
    }

    @Test fun w63SystemBackStillUsesTheCloseExitWithoutWrites() {
        kit.open()
        val before = host.api.writes().map { it.operationId }
        rule.runOnUiThread { ShadowDialog.getLatestDialog().onBackPressed() }
        rule.waitForIdle()
        assertFalse("系统返回走关闭出口", host.flow.stepUp.showing)
        assertEquals("关闭不验证、不重放", before, host.api.writes().map { it.operationId })
        assertEquals(1, host.keys)
    }

    // ---------------------------------------------------------------- W64 标题

    @Test fun w64TitleKeepsTheError10003TextAndIs17Semibold() {
        kit.open()
        val title = kit.titleNode()
        assertEquals("标题键不变（G-W19：文案随契约，不写「请先完成二次验证」）", WalletDraftTexts.text("error.10003"), title.drawnText())
        assertFalse(title.drawnText().contains("二次验证"))
        val style = title.textLayout().layoutInput.style
        assertEquals(17f, style.fontSize.value, .01f)
        assertEquals(FontWeight.SemiBold, style.fontWeight)
    }

    // ---------------------------------------------------------------- W65 说明挪到标题区、无摘要无号码

    @Test fun w65SentNoticeSitsInTheTitleAreaNotTheScrollingMiddle() {
        kit.open()
        val title = kit.titleNode()
        val sent = kit.sentNode()
        val code = kit.tagged(WalletTags.STEP_UP_CODE)
        val d = sent.density()
        assertFalse("说明句挪到标题区：不在中段滚动区里（W65）", sent.isAncestorTagged(WalletTags.SHEET_MIDDLE))
        assertTrue("说明在标题下方", sent.boundsInRoot.top >= title.boundsInRoot.bottom - d)
        assertTrue("说明在验证码上方", sent.boundsInRoot.bottom <= code.boundsInRoot.top)
        assertEquals("说明与标题左对齐", title.boundsInRoot.left / d, sent.boundsInRoot.left / d, 1f)
        assertTrue("标题与说明间距 ≤ 12dp（画板 gap 8）", (sent.boundsInRoot.top - title.boundsInRoot.bottom) / d <= 12f)
        val style = sent.textLayout().layoutInput.style
        assertEquals("说明 14 次文字", 14f, style.fontSize.value, .01f)
        assertSameColor("说明用次文字色", kit.tokens().secondary, style.color)
    }

    @Test fun w65NoBusinessSummaryAndNoPhoneDigitsInTheSheet() {
        kit.open()
        val texts =
            rule
                .onAllNodes(kit.inSheet(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .map { it.drawnText() }
        assertTrue("先正：半屏有文字", texts.isNotEmpty())
        texts.forEach {
            assertFalse("不显示号码（G16）：$it", it.contains("138") || it.contains("5678") || it.contains("****"))
            assertFalse("不做业务摘要（G-W19 / CT-26c）：$it", it.contains("¥") || it.contains("收款账号") || it.contains("提现 "))
        }
    }

    // ---------------------------------------------------------------- W66 6 格验证码

    @Test fun w66CodeLabelIsScreenReaderOnlyAndSpokenProgressKept() {
        kit.open()
        val code = kit.tagged(WalletTags.STEP_UP_CODE)
        assertTrue("STEP_UP_CODE 是实际可输入节点", code.config.getOrNull(SemanticsActions.SetText) != null)
        assertEquals("验证码，已输入 0 位，共 6 位", code.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(""))
        val visibleLabel =
            rule
                .onAllNodes(kit.inSheet(hasText(WalletDraftTexts.text("step_up.code_label"))), useUnmergedTree = true)
                .fetchSemanticsNodes()
        assertTrue("「验证码」只作读屏标签，不画可见文字（W66）", visibleLabel.isEmpty())
        rule.onNodeWithTag(WalletTags.STEP_UP_CODE).performTextReplacement("38")
        rule.waitForIdle()
        assertEquals(
            "验证码，已输入 2 位，共 6 位",
            kit
                .tagged(WalletTags.STEP_UP_CODE)
                .config
                .getOrNull(SemanticsProperties.ContentDescription)
                ?.joinToString(""),
        )
    }

    @Test fun w66SixCellsWithGap8AndComponentCellHeightLight() = cells(dark = false)

    @Test fun w66SixCellsWithGap8AndComponentCellHeightDark() = cells(dark = true)

    @Test fun w66CurrentCellHasTheThickerActiveBorder() {
        kit.open()
        val code = kit.tagged(WalletTags.STEP_UP_CODE)
        val edges = cellEdges(kit.shot(code), code)
        rule.onNodeWithTag(WalletTags.STEP_UP_CODE).performSemanticsAction(SemanticsActions.RequestFocus)
        rule.onNodeWithTag(WalletTags.STEP_UP_CODE).performTextReplacement("38")
        rule.waitForIdle()
        val focused = kit.tagged(WalletTags.STEP_UP_CODE)
        assertTrue("先正：输入框已获焦", focused.config.getOrNull(SemanticsProperties.Focused) == true)
        val shot = kit.shot(focused)
        val r = shot.px(focused.boundsInRoot)
        val y = r.center.y.roundToInt()
        val surface = kit.tokens().surface
        val d = focused.density()

        fun leftBorder(cell: Int): Int {
            var x = edges[cell].first
            var width = 0
            while (shot.ink(x, y, surface)) {
                width++
                x++
            }
            return width
        }
        assertTrue("当前格（第 3 格）2dp 描边：${leftBorder(2) / d}dp", leftBorder(2) / d >= 1.5f)
        assertTrue("非当前格 1dp：${leftBorder(3) / d}dp", leftBorder(3) / d <= 1.25f)
    }

    // ---------------------------------------------------------------- 遮罩（a/b/c 评审：遮罩用 overlayScrim）

    @Test fun sheetScrimUsesOverlayTokenLight() = scrim(dark = false)

    @Test fun sheetScrimUsesOverlayTokenDark() = scrim(dark = true)

    // ---------------------------------------------------------------- 实现

    private fun handle(dark: Boolean) {
        kit.open(dark = dark)
        val title = kit.titleNode()
        val shot = kit.shot(title)
        val t = shot.px(title.boundsInRoot)
        val d = title.density()
        val cx = shot.bitmap.width / 2
        // 只在标题行（含关闭按钮）之上、中间三分之一里找：避开关闭图标抗锯齿边缘的近似色
        val limit = minOf(t.top, shot.px(kit.tagged(WalletTags.STEP_UP_CLOSE).boundsInRoot).top).toInt()
        var top = limit
        while (top > 0 && shot.opaque(cx, top - 1)) top--
        val color = kit.tokens().borderSubtle
        var minX = Int.MAX_VALUE
        var maxX = -1
        var minY = Int.MAX_VALUE
        var maxY = -1
        for (y in top until limit) {
            for (x in shot.bitmap.width / 3 until shot.bitmap.width * 2 / 3) {
                if (shot.near(x, y, color, HANDLE_TOLERANCE)) {
                    minX = minOf(minX, x)
                    maxX = maxOf(maxX, x)
                    minY = minOf(minY, y)
                    maxY = maxOf(maxY, y)
                }
            }
        }
        assertTrue("半屏顶部、标题之上要画 border.subtle 把手（W63）", maxX >= 0)
        val width = (maxX - minX + 1) / d
        val height = (maxY - minY + 1) / d
        assertEquals("把手宽 36dp", 36f, width, 2f)
        assertEquals("把手高 4dp", 4f, height, 1.5f)
        assertEquals("把手水平居中", shot.bitmap.width / 2f / d, (minX + maxX + 1) / 2f / d, 1.5f)
        assertTrue("把手在标题行之上且不贴标题", (limit - maxY) / d >= 4f)
    }

    private fun cells(dark: Boolean) {
        kit.open(dark = dark)
        val code = kit.tagged(WalletTags.STEP_UP_CODE)
        val d = code.density()
        val edges = cellEdges(kit.shot(code), code)
        val gaps = (0 until CELLS - 1).map { (edges[it + 1].first - edges[it].last - 1) / d }
        gaps.forEach { assertEquals("格间距 8dp：$gaps", 8f, it, 1.5f) }
        val widths = edges.map { (it.last - it.first + 1) / d }
        widths.forEach { assertEquals("6 格等宽：$widths", widths.first(), it, 1.5f) }
        val shot = kit.shot(code)
        val r = shot.px(code.boundsInRoot)
        val x = (edges[0].first + edges[0].last) / 2
        val vertical = shot.verticalRuns(x, (r.top - 4 * d).toInt(), (r.bottom + 4 * d).toInt(), kit.tokens().surface)
        assertTrue("格子上下描边：$vertical", vertical.size >= 2)
        val height = (vertical.last().last - vertical.first().first + 1) / d
        assertEquals("格高取组件令牌 codeCellHeight", CouliComponentTokens.codeCellHeight.value, height, 2f)
    }

    /** 空码、未获焦时沿输入框中线扫描：每格左右两条描边，共 12 段；返回每格的 [左描边起点, 右描边终点]。 */
    private fun cellEdges(
        shot: StepUpShot,
        code: androidx.compose.ui.semantics.SemanticsNode,
    ): List<IntRange> {
        val r = shot.px(code.boundsInRoot)
        val d = code.density()
        val runs =
            shot.horizontalRuns(
                r.center.y.roundToInt(),
                (r.left - 2 * d).toInt(),
                (r.right + 2 * d).toInt(),
                kit.tokens().surface,
            )
        assertEquals("6 格验证码：中线上应有 12 段格子描边（W66），实际 $runs", CELLS * 2, runs.size)
        return (0 until CELLS).map { runs[it * 2].first..runs[it * 2 + 1].last }
    }

    private fun scrim(dark: Boolean) {
        kit.open(dark = dark, sample = true)
        val white = rule.onNodeWithTag(StepUpVisualKit.SCRIM_WHITE).fetchSemanticsNode()
        val sample = WithdrawVisualWindowProbe(rule).scrim(white)
        assertEquals("先正：遮罩下采样为白", android.graphics.Color.WHITE, sample.before)
        val expected = kit.tokens().scrim.compositeOver(Color.White)
        val actual = Color(sample.after)
        listOf(expected.red to actual.red, expected.green to actual.green, expected.blue to actual.blue).forEach { (e, a) ->
            assertTrue("半屏遮罩 = overlayScrim（画板 rgba(40,37,31,.4) / 深色 rgba(0,0,0,.55)）：期望 $expected 实际 $actual", abs(e - a) * 255 <= 2.5f)
        }
    }

    private companion object {
        const val CELLS = 6
        const val HANDLE_TOLERANCE = 12
    }
}
