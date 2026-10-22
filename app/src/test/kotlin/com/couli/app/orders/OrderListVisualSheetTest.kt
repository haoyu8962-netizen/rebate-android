package com.couli.app.orders

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.assertNoTextOverflow
import com.couli.app.product.readableText
import com.couli.app.product.textLayout
import com.couli.feature.orders.OrderTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1000dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OrderListVisualSheetTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { OrderListVisualTestHost(rule) }

    private fun restoreFocus() {
        rule.mainClock.advanceTimeBy(400)
        org.robolectric.Shadows
            .shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(400))
        rule.waitForIdle()
        rule.onNodeWithTag(OrderTags.FILTER_PLATFORM).assertIsFocused()
    }

    @After fun close() = host.close()

    @Test fun eachPlatformOptionHas20DpOfficialIconAndSingleSpokenName() {
        host.launch(OrderListVisualFixtures.one())
        rule.onNodeWithTag(OrderTags.FILTER_PLATFORM).performClick()
        listOf("taobao" to "淘宝", "jd" to "京东", "pdd" to "拼多多").forEach { (code, label) ->
            val option = rule.onNodeWithTag(OrderTags.FILTER_OPTION_PREFIX + code).assertIsDisplayed().fetchSemanticsNode()
            option.assertTouch48()
            assertEquals(Role.RadioButton, option.config[SemanticsProperties.Role])
            assertEquals(1, label.toRegex().findAll(option.readableText()).count())
            val text =
                rule.onNode(
                    hasText(label) and hasAnyAncestor(hasTestTag(OrderTags.FILTER_OPTION_PREFIX + code)),
                    useUnmergedTree = true,
                )
            val d = option.layoutInfo.density.density
            assertEquals("图标20 + 间距8", 28f, (text.fetchSemanticsNode().boundsInRoot.left - option.boundsInRoot.left) / d, 2f)
            val rect =
                Rect(
                    option.boundsInRoot.left,
                    option.boundsInRoot.center.y - 10 * d,
                    option.boundsInRoot.left + 20 * d,
                    option.boundsInRoot.center.y + 10 * d,
                )
            rule.withOrderPixels(option) { bitmap ->
                assertTrue("官方图不能留空：$code", bitmap.colorCount(rect, host.surface.toArgb()) < rect.width * rect.height * .6f)
            }
        }
    }

    @Test fun optionRowsHaveDividersAndSelectionUsesCircledCheck() {
        host.launch(OrderListVisualFixtures.one())
        rule.onNodeWithTag(OrderTags.FILTER_PLATFORM).performClick()
        val all = rule.onNodeWithTag(OrderTags.FILTER_OPTION_PREFIX + "all").fetchSemanticsNode()
        val taobao = rule.onNodeWithTag(OrderTags.FILTER_OPTION_PREFIX + "taobao").fetchSemanticsNode()
        val d = all.layoutInfo.density.density
        assertTrue(all.config[SemanticsProperties.Selected])
        rule.withOrderPixels(all) { bitmap ->
            val between = Rect(all.boundsInRoot.left, all.boundsInRoot.bottom - d, all.boundsInRoot.right, taobao.boundsInRoot.top + d)
            assertTrue("选项之间有细分隔线", bitmap.colorCount(between, host.border.toArgb()) >= between.width * d * .8f)
            val centerX = all.boundsInRoot.right - 10 * d
            val topY = all.boundsInRoot.center.y - 8 * d
            val crown = Rect(centerX - 3 * d, topY - 2 * d, centerX + 3 * d, topY + 2 * d)
            assertTrue("选中是圆圈对勾，不能只有裸对勾", bitmap.colorCount(crown, host.emphasis.toArgb()) >= 3 * d)
        }
    }

    @Test fun selectingPlatformUsesExistingQueryAndDismissRestoresFocus() {
        host.launch(OrderListVisualFixtures.one())
        rule.onNodeWithTag(OrderTags.FILTER_PLATFORM).performClick()
        assertEquals(1, host.api.calls.size)
        rule.onNodeWithTag(OrderTags.FILTER_OPTION_PREFIX + "pdd").performClick()
        assertEquals("pdd", host.model.controller.state.value.conditions.platform)
        assertEquals(2, host.api.calls.size)
        restoreFocus()
        val filter = rule.onNodeWithTag(OrderTags.FILTER_PLATFORM).fetchSemanticsNode()
        assertTrue(filter.config.contains(SemanticsActions.Expand))
        rule.onNodeWithTag(OrderTags.FILTER_PLATFORM).performClick()
        rule.onNodeWithContentDescription("关闭").performClick()
        restoreFocus()
        assertEquals(2, host.api.calls.size)
    }

    @Test fun monthNoteUsesApprovedDictionaryWordingAndEarliestDateBoundsOptions() {
        host.launch(OrderListVisualFixtures.page("history"))
        rule.onNodeWithTag(OrderTags.FILTER_MONTH).performClick()
        rule.onNodeWithText("按付款时间", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("按付款时间；预售单按付定金时间", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag(OrderTags.FILTER_OPTION_PREFIX + "2026-08").assertDoesNotExist()
        rule.onNodeWithTag(OrderTags.FILTER_OPTION_PREFIX + "2026-09").performScrollTo().performClick()
        assertEquals("2026-09", host.model.controller.state.value.conditions.month)
        assertEquals(2, host.api.calls.size)
    }

    @Test
    @Config(qualifiers = "w320dp-h1000dp-xhdpi")
    fun platformSheetActuallyReceives200PercentFontAndOptionsRemainClickable() {
        host.launch(OrderListVisualFixtures.one(), fontScale = 2f)
        rule.onNodeWithTag(OrderTags.FILTER_PLATFORM).performClick()
        val title = rule.onNode(hasText("平台") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading), useUnmergedTree = true)
        assertEquals(
            "独立窗口也必须200%",
            2f,
            title
                .textLayout()
                .layoutInput.density.fontScale,
            .001f,
        )
        assertEquals(
            17f,
            title
                .textLayout()
                .layoutInput.style.fontSize.value,
            .01f,
        )
        title.assertNoTextOverflow()
        rule.onNodeWithTag(OrderTags.FILTER_OPTION_PREFIX + "pdd").performScrollTo()
        val text =
            rule.onNode(
                hasText("拼多多") and hasAnyAncestor(hasTestTag(OrderTags.FILTER_OPTION_PREFIX + "pdd")),
                useUnmergedTree = true,
            )
        assertEquals(
            2f,
            text
                .textLayout()
                .layoutInput.density.fontScale,
            .001f,
        )
        text.assertNoTextOverflow()
        rule.onNodeWithTag(OrderTags.FILTER_OPTION_PREFIX + "pdd").performClick()
        assertEquals("pdd", host.model.controller.state.value.conditions.platform)
        assertFalse(host.opened.isNotEmpty())
    }
}
