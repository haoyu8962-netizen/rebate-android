package com.couli.app.orders

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.readableText
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

/** O12/G-O02：列表没有实返/扣回/区间字段；非零 est 是反推算哨兵，null 不是0。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1000dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OrderListVisualMoneyTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { OrderListVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun paidUsesSingleEstimateAndListKeepsPaidLeftRebateRightAt17Semibold() {
        host.launch(OrderListVisualFixtures.one())
        val paid = rule.orderAmount("¥45.6")
        val rebate = rule.orderAmount("¥1.23")
        assertTrue(paid.bounds.left < rebate.bounds.left)
        listOf(paid, rebate).forEach {
            assertEquals(
                17f,
                it
                    .result
                    .layoutInput.style.fontSize.value,
                .01f,
            )
            assertEquals(
                600,
                it
                    .result
                    .layoutInput.style.fontWeight
                    ?.weight,
            )
        }
        assertFalse(cardReading("A").contains("–"))
        rule.onNodeWithText("预计 10 月结算", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun maskingHidesRebateVisuallyAndInBothSemanticTreesButKeepsPayment() {
        host.launch(OrderListVisualFixtures.one())
        rule.orderAmount("¥1.23")
        assertTrue(cardReading("A").contains("1.23") || cardReading("A").contains("1元2角3分"))
        rule.onNodeWithTag(OrderTags.MASK).performClick()
        rule.orderAmount("****")
        rule.orderAmount("¥45.6")
        val drawn = rule.orderDrawnTexts("A").map { it.result.layoutInput.text.text }
        assertTrue("显示遮罩后不能仍在清语义节点画原金额", drawn.none { it.contains("1.23") || it.contains("¥****") })
        listOf(false, true).forEach { unmerged ->
            val text = cardReading("A", unmerged)
            assertFalse(text.contains("1.23"))
            assertFalse(text.contains("1元2角3分"))
            assertTrue(text.contains("金额已隐藏"))
            assertFalse(text.contains("¥****"))
        }
        assertEquals(1, host.api.calls.size)
    }

    @Test fun nullAmountsRemainLabelsAndDoNotBecomeZero() {
        host.launch(OrderListVisualFixtures.page("null_amounts"))
        rule.cardText("实付金额", "NULL").assertIsDisplayed()
        rule.cardText("预估返", "NULL").assertIsDisplayed()
        val content = rule.orderDrawnTexts("NULL").joinToString(" ") { it.result.layoutInput.text.text }
        assertFalse(content.contains("¥"))
        assertFalse(content.contains("0元"))
        rule.onNodeWithTag(OrderTags.MASK).performClick()
        assertTrue(rule.orderDrawnTexts("NULL").none { it.result.layoutInput.text.text == "****" })
    }

    @Test fun depositRemainsVisibleWhenMaskedAndHasNoRebateCell() {
        host.launch(OrderListVisualFixtures.one("B"))
        rule.orderAmount("¥30", "B")
        rule.onNodeWithTag(OrderTags.MASK).performClick()
        rule.orderAmount("¥30", "B")
        rule.cardText("预估返", "B").assertDoesNotExist()
        assertTrue(rule.orderDrawnTexts("B").none { it.result.layoutInput.text.text == "****" })
    }

    @Test fun creditedAndClawbackNeverUseEstimateAsActualOrDeduction() {
        host.launch(OrderListVisualFixtures.statusPage("CREDITED"))
        listOf("CREDITED" to "实返", "CREDITED_PART_CLAWED" to "实返", "CLAWED_BACK" to "扣回").forEach { (status, label) ->
            host.refresh(OrderListVisualFixtures.statusPage(status))
            val id = "STATUS_$status"
            rule.cardText(label, id).assertIsDisplayed()
            rule.orderAmount("¥45.6", id)
            assertTrue(rule.orderDrawnTexts(id).none { it.result.layoutInput.text.text == "¥1.23" })
            assertTrue(rule.orderDrawnTexts(id).none { it.result.layoutInput.text.text == "-¥1.23" })
            assertFalse(cardReading(id, true).contains("预计"))
        }
    }

    @Test fun unknownIgnoresNonzeroEstimateAndInvalidUsesFixedNeutralZero() {
        host.launch(OrderListVisualFixtures.one("M"))
        rule.cardText("状态更新中", "M").assertIsDisplayed()
        rule.orderAmount("¥19.9", "M")
        assertTrue(rule.orderDrawnTexts("M").none { it.result.layoutInput.text.text == "¥9876.54" })
        rule.cardText("预估返", "M").assertDoesNotExist()
        host.refresh(OrderListVisualFixtures.one("J"))
        rule.cardText("返利", "J").assertIsDisplayed()
        rule.orderAmount("¥0", "J")
        assertTrue(rule.orderDrawnTexts("J").none { it.result.layoutInput.text.text == "¥7654.32" })
        assertFalse(
            "失效返利不使用强调色",
            rule
                .orderAmount("¥0", "J")
                .result
                .layoutInput.style.color == host.emphasis,
        )
    }

    @Test fun sharePromotionalLabelsRemainAndMaskedNumberHasNoCopyOrSelection() {
        host.launch(OrderListVisualFixtures.one("E"), scope = "share")
        rule.cardText("预估推广收益", "E").assertIsDisplayed()
        rule.orderAmount("¥1.8", "E")
        rule.onNodeWithTag(OrderTags.COPY_PREFIX + "E").assertDoesNotExist()
        val number = rule.onNodeWithTag(OrderTags.ORDER_NO_PREFIX + "E", useUnmergedTree = true).fetchSemanticsNode()
        assertTrue(number.config[SemanticsProperties.ContentDescription].single().contains("星号星号星号星号"))
        host.refresh(OrderListVisualFixtures.one("F"))
        rule.cardText("推广收益", "F").assertIsDisplayed()
        assertTrue(rule.orderDrawnTexts("F").none { it.result.layoutInput.text.text == "¥987.65" })
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.CopyText)).assertCountEquals(0)
    }

    private fun cardReading(
        id: String,
        unmerged: Boolean = false,
    ): String =
        rule
            .onAllNodes(
                hasTestTag(OrderTags.CARD_PREFIX + id) or hasAnyAncestor(hasTestTag(OrderTags.CARD_PREFIX + id)),
                useUnmergedTree = unmerged,
            ).fetchSemanticsNodes()
            .joinToString(" ") { it.readableText() }
}
