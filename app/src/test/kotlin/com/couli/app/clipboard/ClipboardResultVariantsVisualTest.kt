package com.couli.app.clipboard

import android.app.Application
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
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
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClipboardResultVariantsVisualTest {
    @get:Rule val rule = createComposeRule()
    private val driver by lazy { ClipboardVisualDriver(rule) }

    @After fun close() = driver.close()

    @Test fun priceUnavailableHidesEveryAmountAndPlacesOutlineActionInsideBottomRight() {
        driver.launch()
        // 故意保留旧金额：availability 优先，不能把服务端残留价格画出来。
        driver.parse(ClipboardVisualFixtures.cards(ClipboardVisualFixtures.card(availability = "price_unavailable")))
        val card = rule.onNodeWithTag(ClipboardScreens.CARD_PREFIX + 0, useUnmergedTree = true).fetchSemanticsNode()
        val nodes = card.descendants()
        val allText = nodes.flatMap { it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { text -> text.text } }
        val descriptions = nodes.flatMap { it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() } }
        assertFalse("④ 可见金额和读屏金额均隐藏", (allText + descriptions).any { "¥" in it || "元" in it })
        val hint =
            rule
                .onNode(hasText("暂时查不到该商品价格") and hasAnyAncestor(isDialog()), useUnmergedTree = true)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val action = rule.onNode(hasText("稍后再试") and hasAnyAncestor(isDialog())).assertIsDisplayed().assertIsNotEnabled()
        val b = action.fetchSemanticsNode().boundsInRoot
        assertTrue("提示上、操作下", hint.bottom <= b.top)
        assertTrue("操作在卡内右侧", b.center.x > card.boundsInRoot.center.x && card.boundsInRoot.contains(b.center))
        OverlayVisualProbe(rule).assertOutline(action.fetchSemanticsNode(), driver.host.surface.toArgb(), driver.host.border.toArgb())
    }

    @Test fun offShelfKeepsExistingDisabledBuyAndDoesNotAddSimilarSearch() {
        driver.launch()
        val source = ClipboardVisualFixtures.card(availability = "off_shelf")
        driver.parse(ClipboardVisualFixtures.cards(source))
        assertEquals(source, (driver.host.clipboard.result.value as ClipboardResult.CardList).rows.single().card)
        rule.onNode(hasText("领券购买") and hasAnyAncestor(isDialog())).assertIsNotEnabled()
        rule.onNodeWithText("搜相似商品").assertDoesNotExist()
    }

    @Test fun noRebateKeepsExistingBuyAndDoesNotAddSimilarSearch() {
        driver.launch()
        val source = ClipboardVisualFixtures.noRebate()
        driver.parse(ClipboardVisualFixtures.cards(source))
        assertEquals(source, (driver.host.clipboard.result.value as ClipboardResult.CardList).rows.single().card)
        // ③本轮不动：BR-TEXT-10 现有列表卡隐藏「暂无返利」，购买仍可用。
        rule.onNode(hasText("暂无返利") and hasAnyAncestor(isDialog()), useUnmergedTree = true).assertDoesNotExist()
        rule.onNode(hasText("去购买（无返利）") and hasAnyAncestor(isDialog())).assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithText("搜相似商品").assertDoesNotExist()
    }
}
