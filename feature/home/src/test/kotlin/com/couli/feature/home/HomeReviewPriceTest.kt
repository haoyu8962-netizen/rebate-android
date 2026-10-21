package com.couli.feature.home

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.ProductCardKeys
import com.couli.core.designsystem.RebateKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** r1 S1-1 / S1-2：真实首页卡的说明点击与大字号金额排版。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h2400dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeReviewPriceTest {
    @get:Rule
    val rule = createComposeRule()
    private val driver by lazy { HomeVisualTestDriver(rule) }

    @After
    fun close() = driver.close()

    @Test
    fun noRebateScrollStillOpensPriceExplanation() = assertNoRebateInfo("scroll")

    @Test
    fun noRebateGridStillOpensPriceExplanation() = assertNoRebateInfo("grid")

    @Test
    fun noRebateFeedStillOpensPriceExplanation() = assertNoRebateInfo("feed")

    @Test
    fun scrollLongAmountsAtFont200Width390AreNotClipped() = assertLongAmounts()

    @Test
    @Config(qualifiers = "w320dp-h2400dp-xhdpi")
    fun scrollLongAmountsAtFont200Width320AreNotClipped() = assertLongAmounts()

    private fun assertNoRebateInfo(section: String) {
        driver.show(section, fixture = HomeReviewFixtures.product(section, noRebate = true))
        val data = HomeReviewFixtures.firstProduct(driver.controller).data
        assertEquals(RebateKind.NONE, data.rebate.kind)
        assertNotNull(data.price)
        assertTrue("样例必须有说明全文", data.disclaimer.isNotEmpty())
        val inTile = hasAnyAncestor(hasTestTag(HomeTags.tap(section, 1)))
        val info = rule.onNode(hasContentDescription(data.infoLabel) and inTile)
        info.assertIsDisplayed().assertHasClickAction().performTouchInput { click() }
        val title = driver.controller.cardTexts.text(ProductCardKeys.DISCLAIMER_TITLE)
        rule.onNodeWithText(title).assertIsDisplayed()
        data.disclaimer.forEach { rule.onNode(hasText(it) and hasAnyAncestor(isDialog())).assertIsDisplayed() }
        rule.onNodeWithText(driver.controller.cardTexts.text(ProductCardKeys.DISCLAIMER_CLOSE)).performClick()
        rule.onNodeWithText(title).assertDoesNotExist()
        info.assertIsDisplayed()
    }

    private fun assertLongAmounts() {
        driver.show("scroll", scale = 2f, fixture = HomeReviewFixtures.product("scroll", longAmounts = true))
        val data = HomeReviewFixtures.firstProduct(driver.controller).data
        val subLine = checkNotNull(data.price?.subLine)
        assertTrue(subLine.contains("199999.98") && subLine.contains("99999.99"))
        rule.onNodeWithTag(HomeTags.tap("scroll", 1)).assertExists()
        val nodes =
            rule.onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult) and
                    hasAnyAncestor(hasTestTag(HomeTags.tap("scroll", 1))),
                useUnmergedTree = true,
            )
        val layouts = mutableListOf<TextLayoutResult>()
        repeat(nodes.fetchSemanticsNodes().size) { index ->
            nodes[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> assertTrue(action(layouts)) }
        }
        val expected = setOf("¥99999.99", "¥88888.88", "–¥99999.99", subLine)
        val selected = layouts.filter { it.layoutInput.text.text in expected }
        assertEquals("不能靠删掉金额或副行通过", expected, selected.map { it.layoutInput.text.text }.toSet())
        selected.forEach { layout ->
            val text = layout.layoutInput.text.text
            assertFalse("$text 宽度裁切", layout.didOverflowWidth)
            assertFalse("$text 高度裁切", layout.didOverflowHeight)
            assertEquals(text.length, layout.getLineEnd(layout.lineCount - 1))
            if (text != subLine) assertEquals("金额段不能拆数字", 1, layout.lineCount)
            repeat(layout.lineCount) { assertFalse("$text 不能省略", layout.isLineEllipsized(it)) }
        }
    }
}
