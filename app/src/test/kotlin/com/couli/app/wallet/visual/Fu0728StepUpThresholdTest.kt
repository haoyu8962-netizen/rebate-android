package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Constraints
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTheme
import com.couli.core.wallet.stepup.FitSheetBody
import com.couli.core.wallet.stepup.StepUpTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FU-AND-0728 U2（F-AND-69 ①，APP-09v-d 评审 r2 S2-1）：三段半屏 → 降级的判定。中段底部留白 [INSET]（二次验证 = 16，给【重新获取】48 命中区下半截）
 * 算进中段、从间距里扣，`fixed` 已减 inset，但判定 `fixed + minBody ≤ budget` 没把它加回：阈值附近中段视口只有 minBody，其中 inset 是留白，
 * 内容可用只剩 minBody − inset，且不滚动时看不到底部留白（命中区下半截又会被裁）。修正后（`fixed + inset + minBody ≤ budget` 或等价）：
 * 不降级时中段视口至少放得下「minBody 高的内容 + 底部留白」不需滚动；放不下就整块降级。
 *
 * 直接排 [FitSheetBody]（二次验证用的同一实现，参数取 StepUpSheet 的实参：minBody = 48 + 32、bodyBottomInset = 16），父布局按像素给定可用高度（budget），
 * 中段内容恰为 minBody 高。先在不限高下量出总高 T（= fixed + inset + minBody），再按 T 附近的预算判。
 * 底版：nearTheOldThreshold… 红（视口 T−8dp：不降级且中段要滚 8dp），其余两条绿；修正后全绿。变异：把判定改回 `fixed + minBody.roundToPx() <= budget` → 红。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Fu0728StepUpThresholdTest {
    @get:Rule val rule = createComposeRule()

    /** 父布局给的最大高度（像素）；null = 不限高（量总高用）。 */
    private var budget by mutableStateOf<Int?>(null)

    @Test fun nearTheOldThresholdFallsBackOrShowsTheContentAndItsBottomInsetWithoutScrolling() {
        val total = launchAndMeasure()
        budget = total - px(HALF_INSET_DP)
        rule.waitForIdle()
        assertFallbackOrNoMiddleScroll("预算 = T − 8dp（旧判定放得下、新判定放不下）")
    }

    @Test fun atTheRestoredThresholdStaysThreeSectionsAndNeedsNoScrolling() {
        val total = launchAndMeasure()
        budget = total
        rule.waitForIdle()
        assertFalse("预算 = T：内容 + 留白恰好放得下，不降级", fallback())
        assertEquals("预算 = T：中段不需滚动（留白可见）", 0f, middleScrollMax(), SCROLL_EPS)
    }

    @Test fun clearlyBelowMinBodyFallsBack() {
        val total = launchAndMeasure()
        budget = total - px(INSET_DP + HALF_INSET_DP)
        rule.waitForIdle()
        assertTrue("预算 = T − 24dp（中段视口 < minBody）：整块降级", fallback())
    }

    // ---------------------------------------------------------------- 辅助

    /** 不限高排一次，量出总高 T（像素）；先正：不降级、中段不滚。 */
    private fun launchAndMeasure(): Int {
        rule.setContent {
            CouliTheme {
                Layout(content = { Sheet() }) { measurables, c ->
                    val limit = budget
                    val placeable =
                        measurables.single().measure(
                            Constraints(maxWidth = c.maxWidth, maxHeight = limit ?: Constraints.Infinity),
                        )
                    layout(c.maxWidth, placeable.height.coerceAtMost(c.maxHeight)) { placeable.place(0, 0) }
                }
            }
        }
        rule.waitForIdle()
        assertFalse("先正：不限高时不降级", fallback())
        assertEquals("先正：不限高时中段不滚", 0f, middleScrollMax(), SCROLL_EPS)
        val total =
            rule
                .onNodeWithTag(FIT, useUnmergedTree = true)
                .fetchSemanticsNode()
                .size.height
        assertTrue("先正：总高大于 minBody + 留白：$total", total > px(MIN_BODY_DP + INSET_DP))
        return total
    }

    @androidx.compose.runtime.Composable
    private fun Sheet() {
        val focus = remember { FocusRequester() }
        FitSheetBody(
            title = TITLE,
            modifier = Modifier.testTag(FIT),
            titleFocus = focus,
            onClose = {},
            closeLabel = CLOSE,
            // 与 StepUpSheet 的实参同一写法（中段至少放得下验证码一行）
            minBody = CouliSizes.touchTarget + CouliSpacing.space8,
            closeTag = null,
            actions = { Box(Modifier.fillMaxWidth().height(CouliSizes.touchTarget)) },
            bodyBottomInset = CouliSpacing.space4,
            content = { Box(Modifier.fillMaxWidth().height(CouliSizes.touchTarget + CouliSpacing.space8).testTag(CONTENT)) },
        )
    }

    private fun fallback(): Boolean =
        rule.onAllNodesWithTag(StepUpTags.SHEET_FALLBACK, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** 三段态中段（带 SHEET_MIDDLE 标记、自身可滚）的最大滚动量（像素）；降级态中段不滚，记 0。 */
    private fun middleScrollMax(): Float {
        val node = rule.onNodeWithTag(StepUpTags.SHEET_MIDDLE, useUnmergedTree = true).fetchSemanticsNode()
        val range = node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) ?: return 0f
        return range.maxValue()
    }

    private fun assertFallbackOrNoMiddleScroll(what: String) {
        assertNotNull("先正：中段内容在", rule.onNodeWithTag(CONTENT, useUnmergedTree = true).fetchSemanticsNode())
        val fell = fallback()
        val scroll = middleScrollMax()
        assertTrue(
            "$what：不降级时中段须不滚动就放得下 minBody 高的内容和底部留白（实际：降级 = $fell，中段还要滚 ${scroll / density()}dp）",
            fell || scroll <= SCROLL_EPS,
        )
    }

    private fun density(): Float =
        rule
            .onNodeWithTag(FIT, useUnmergedTree = true)
            .fetchSemanticsNode()
            .layoutInfo.density.density

    private fun px(dp: Int): Int = (dp * density()).toInt()

    private companion object {
        const val FIT = "fu0728_fit_sheet"
        const val CONTENT = "fu0728_fit_content"
        const val TITLE = "验证"
        const val CLOSE = "关闭"
        const val MIN_BODY_DP = 80
        const val INSET_DP = 16
        const val HALF_INSET_DP = 8
        const val SCROLL_EPS = 0.5f
    }
}
