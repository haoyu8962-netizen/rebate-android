package com.couli.app.search.visual

import android.app.Application
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText as textMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.textLayout
import com.couli.core.designsystem.ModalFocus
import com.couli.feature.search.SearchTags
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h956dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchVisualOverlayTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { SearchVisualHost(rule) }

    @After fun close() = host.close()

    @Test fun priceLabelsStayAbovePopulatedInputsAndCloseIsTopRight() {
        host.launch("price")
        listOf("最低价" to SearchTags.PRICE_MIN, "最高价" to SearchTags.PRICE_MAX).forEach { (label, tag) ->
            val text = rule.onNodeWithText(label, useUnmergedTree = true)
            text.assertIsDisplayed().assertSearchFont(12f)
            val input = rule.onNodeWithTag(tag)
            input.assertSearchFont(17f)
            val labelNode = text.fetchSemanticsNode()
            val inputNode = input.fetchSemanticsNode()
            assertTrue("标签位于输入框上方，不再是框内占位", labelNode.searchTop() + labelNode.size.height <= inputNode.searchTop())
        }
        val close = rule.onNodeWithContentDescription("关闭").assertIsDisplayed().fetchSemanticsNode()
        close.assertSearchTouch48()
        val title = rule.onNode(textMatcher("价格区间") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).fetchSemanticsNode()
        assertTrue("关闭在标题右侧", close.boundsInRoot.left >= title.boundsInRoot.right)
        assertTrue("关闭在输入内容上方", close.searchTop() < rule.onNodeWithTag(SearchTags.PRICE_MIN).fetchSemanticsNode().searchTop())
        rule.onNodeWithContentDescription("关闭").performClick()
        rule.mainClock.advanceTimeBy(ModalFocus.RESTORE_DELAY_MILLIS + 500)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ModalFocus.RESTORE_DELAY_MILLIS + 500))
        rule.waitForIdle()
        rule.onNodeWithTag(SearchTags.PRICE_MIN).assertDoesNotExist()
        rule.onNodeWithTag(SearchTags.PRICE).assertIsFocused()
        assertEquals("改草稿和关闭不发请求", 1, host.api.calls.size)
    }

    @Test fun invalidPriceStaysInSheetAndResetDoesNotRequest() {
        host.launch("price_invalid")
        rule.onNodeWithText("请输入 0.01–100000 元，最低价不能高于最高价").assertIsDisplayed()
        assertEquals(1, host.api.calls.size)
        rule.onNodeWithTag(SearchTags.PRICE_RESET).performClick()
        assertEquals(
            "",
            rule
                .onNodeWithTag(SearchTags.PRICE_MIN)
                .textLayout()
                .layoutInput.text.text,
        )
        assertEquals(
            "",
            rule
                .onNodeWithTag(SearchTags.PRICE_MAX)
                .textLayout()
                .layoutInput.text.text,
        )
        assertEquals(1, host.api.calls.size)
    }

    @Test fun disclaimerIsSheetWithCloseAndOutlinedFullWidthActionThenReturnsToSameInfo() {
        host.launch()
        val trigger = rule.onAllNodesWithContentDescription("价格与返利说明")[0].fetchSemanticsNode().id
        host.openInfo()
        val close = rule.onNodeWithContentDescription("关闭").assertIsDisplayed().fetchSemanticsNode()
        close.assertSearchTouch48()
        val action = rule.onNode(textMatcher("知道了") and hasClickAction()).fetchSemanticsNode()
        action.assertSearchTouch48()
        val title = rule.onNode(textMatcher("价格与返利说明") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        val heading = title.fetchSemanticsNode()
        assertTrue("通栏操作宽于标题", action.boundsInRoot.width >= heading.boundsInRoot.width)
        assertTrue("操作位于半屏正文下方", action.searchTop() > heading.searchTop())
        val pixels = rule.searchPixels(action)
        val d = action.layoutInfo.density.density
        try {
            assertEquals(
                "知道了为描边次按钮，内底白色",
                0xFFFFFFFF.toInt(),
                pixels.getPixel(
                    action.boundsInRoot.center.x
                        .toInt(),
                    (
                        action.boundsInRoot.top +
                            4 * d
                    ).toInt(),
                ),
            )
        } finally {
            pixels.recycle()
        }
        rule.onNode(textMatcher("知道了") and hasClickAction()).performClick()
        rule.mainClock.advanceTimeBy(ModalFocus.RESTORE_DELAY_MILLIS + 500)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ModalFocus.RESTORE_DELAY_MILLIS + 500))
        rule.waitForIdle()
        val returned = rule.onAllNodesWithContentDescription("价格与返利说明")[0]
        assertEquals("回到打开说明的同一张卡", trigger, returned.fetchSemanticsNode().id)
        returned.assertIsFocused()
        assertEquals(1, host.api.calls.size)
    }

    @Test fun disclaimerTopRightCloseAlsoReturnsFocusWithoutRequest() {
        host.launch("disclaimer")
        rule.onNodeWithContentDescription("关闭").performClick()
        rule.mainClock.advanceTimeBy(ModalFocus.RESTORE_DELAY_MILLIS + 500)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ModalFocus.RESTORE_DELAY_MILLIS + 500))
        rule.waitForIdle()
        rule.onAllNodesWithContentDescription("价格与返利说明")[0].assertIsFocused()
        assertEquals(1, host.api.calls.size)
    }
}
