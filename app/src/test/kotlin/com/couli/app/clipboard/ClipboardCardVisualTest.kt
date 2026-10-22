package com.couli.app.clipboard

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.DisclaimerKeys
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClipboardCardVisualTest {
    @get:Rule val rule = createComposeRule()
    private val driver by lazy { ClipboardVisualDriver(rule) }
    private val probe by lazy { OverlayVisualProbe(rule) }

    @After fun close() = driver.close()

    @Test fun singleCardHasNoVisibleHeadingButKeepsPaneTitle() {
        driver.launch()
        driver.parse()
        rule.onNodeWithText("识别到的商品", useUnmergedTree = true).assertDoesNotExist()
        rule.onAllNodes(paneNamed("识别到的商品"), useUnmergedTree = true).assertCountEquals(1)
        rule.onNodeWithTag(ClipboardScreens.CARD_PREFIX + 0).assertIsDisplayed()
        val close = rule.onNodeWithContentDescription("关闭").assertIsDisplayed().fetchSemanticsNode()
        val card = rule.onNodeWithTag(ClipboardScreens.CARD_PREFIX + 0).fetchSemanticsNode().boundsInRoot
        assertTrue("关闭位于卡片右上", close.boundsInRoot.center.x > card.center.x && close.boundsInRoot.bottom <= card.top)
        probe.assertTarget48(close)
    }

    @Test fun onePriceBasisLineIsBelowCardInsideSheet() {
        driver.launch()
        driver.parse()
        val basis = checkNotNull(DisclaimerKeys.text("price_basis"))
        val footer = rule.onNode(hasText(basis) and hasAnyAncestor(isDialog()), useUnmergedTree = true).assertIsDisplayed()
        val card = rule.onNodeWithTag(ClipboardScreens.CARD_PREFIX + 0).fetchSemanticsNode().boundsInRoot
        assertTrue("口径在卡下，不借用被遮住的首页页脚", footer.fetchSemanticsNode().boundsInRoot.top >= card.bottom)
        rule.onAllNodes(hasText(basis) and hasAnyAncestor(isDialog()), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test fun multipleProductsStayAListAndRetainAllRows() {
        driver.launch()
        driver.parse(ClipboardVisualFixtures.cards(ClipboardVisualFixtures.card(), ClipboardVisualFixtures.card(key = "tb:second")))
        val result = driver.host.clipboard.result.value as ClipboardResult.CardList
        assertEquals(2, result.rows.size)
        val first =
            rule
                .onNodeWithTag(ClipboardScreens.CARD_PREFIX + 0)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val second =
            rule
                .onNodeWithTag(ClipboardScreens.CARD_PREFIX + 1)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        assertEquals(first.left, second.left, 1f)
        assertEquals(first.width, second.width, 1f)
        assertTrue("多条纵向列表不覆盖、不丢行", first.bottom < second.top)
    }

    @Test fun closeDismissesOnlyResultAndDoesNotReadWriteOrNavigate() {
        driver.launch()
        driver.parse()
        val reads = driver.host.system.reads
        val calls =
            driver.host.api.calls
                .toList()
        rule.onNodeWithContentDescription("关闭").performTouchInput { click() }
        driver.host.idle()
        assertNull(driver.host.clipboard.result.value)
        assertEquals(reads, driver.host.system.reads)
        assertEquals(calls, driver.host.api.calls)
        assertNull(driver.host.routing.lastTrace.value)
        // HomeVisualClipboard.write 会直接抛错，写/清空剪贴板同样失败。
    }

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun font200KeepsAmountsAndBuyTextUnclipped() {
        driver.launch(fontScale = 2f)
        driver.parse()
        val inCard = hasAnyAncestor(hasTestTag(ClipboardScreens.CARD_PREFIX + 0))
        listOf("¥13.45", "¥0.56", "–¥1.12", "领券购买").forEach {
            probe.assertTextFits(rule.onNode(hasText(it) and inCard, useUnmergedTree = true).performScrollTo().assertIsDisplayed())
        }
        val texts =
            rule
                .onNodeWithTag(ClipboardScreens.CARD_PREFIX + 0, useUnmergedTree = true)
                .fetchSemanticsNode()
                .descendants()
                .flatMap { it.config.getOrElse(SemanticsProperties.Text) { emptyList() } }
        assertTrue("返利区间仍完整", texts.joinToString().contains("¥0.56") && texts.joinToString().contains("¥1.12"))
        probe.assertTarget48(rule.onNodeWithContentDescription("关闭").assertIsDisplayed().fetchSemanticsNode())
    }
}
