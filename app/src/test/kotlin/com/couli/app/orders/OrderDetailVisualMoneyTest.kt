package com.couli.app.orders

import android.app.Application
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
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
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1400dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OrderDetailVisualMoneyTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { OrderDetailVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun rebateIsLeft28BoldAndPayRight22Semibold() {
        host.launch()
        val rebate = rule.detailAmount("¥4.5")
        val pay = rule.detailAmount("¥39.9")
        assertTrue(rebate.bounds.right < pay.bounds.left)
        assertEquals(28f, rebate.result.layoutInput.style.fontSize.value, .01f)
        assertEquals(
            700,
            rebate.result.layoutInput.style.fontWeight
                ?.weight,
        )
        assertEquals(22f, pay.result.layoutInput.style.fontSize.value, .01f)
        assertEquals(
            600,
            pay.result.layoutInput.style.fontWeight
                ?.weight,
        )
        rebate.assertNoOverflow()
        pay.assertNoOverflow()
    }

    @Test fun nullActualAndPayAreNotZeroOrEstimatedSubstitutes() {
        host.launch(OrderDetailVisualFixtures.detail("detail_null_money"))
        rule.detailText("实返").assertExists()
        rule.detailText("实付金额").assertExists()
        assertTrue(
            rule.detailDrawn(OrderTags.DETAIL_AMOUNTS).none {
                it.result.layoutInput.text.text
                    .contains("¥")
            },
        )
    }

    @Test fun knownActualAndClawbackUseTheirOwnIntegerFields() {
        host.launch(OrderDetailVisualFixtures.detail("detail_part_clawed"))
        rule.detailAmount("¥1.4").assertNoOverflow()
        rule.detailAmount("¥159").assertNoOverflow()
        rule.detailText("已结算（部分扣回 ¥4）").assertExists()
        assertTrue(
            rule.detailDrawn().none {
                it.result.layoutInput.text.text
                    .contains("¥5.4")
            },
        )
    }

    @Test fun maskRemovesRealAndSpokenAmountsIncludingPartialClawbackTitle() {
        host.launch(OrderDetailVisualFixtures.detail("detail_part_clawed"))
        rule.detailAmount("¥1.4")
        rule.detailText("已结算（部分扣回 ¥4）").assertExists()
        rule.onNodeWithTag(OrderTags.MASK).performClick()
        assertTrue(
            rule.detailDrawn().none {
                it.result.layoutInput.text.text
                    .contains("¥1.4") ||
                    it.result.layoutInput.text.text
                        .contains("¥4")
            },
        )
        rule.detailAmount("****")
        rule.detailAmount("¥159")
        listOf(false, true).forEach { unmerged ->
            val reading =
                rule
                    .onAllNodes(
                        hasTestTag(OrderTags.DETAIL_STATUS) or hasAnyAncestor(hasTestTag(OrderTags.DETAIL_STATUS)),
                        useUnmergedTree = unmerged,
                    ).fetchSemanticsNodes()
                    .joinToString(" ") { it.config.toString() }
            assertTrue(reading.contains("金额已隐藏"))
            listOf(
                "¥1.4",
                "¥4",
                com.couli.core.designsystem.CouliFormat
                    .moneySpoken(140),
                com.couli.core.designsystem.CouliFormat
                    .moneySpoken(400),
            ).forEach {
                assertFalse(reading.contains(it))
            }
        }
    }

    @Test fun unknownSuppressesNonzeroRebateActualClawbackSentinelsButKeepsPay() {
        host.launch(OrderDetailVisualFixtures.detail("detail_unknown"))
        rule.detailAmount("¥19.9")
        val values = rule.detailDrawn(OrderTags.DETAIL_AMOUNTS).map { it.result.layoutInput.text.text }.filter { it.contains("¥") }
        assertEquals(listOf("¥19.9"), values)
    }

    @Test fun depositRemainsVisibleWhenMaskIsOnAndHasNoRebateCell() {
        host.launch(OrderDetailVisualFixtures.detail("detail_presale"))
        rule.detailAmount("¥30")
        rule.onNodeWithTag(OrderTags.MASK).performClick()
        rule.detailAmount("¥30")
        assertFalse(rule.detailDrawn().any { it.result.layoutInput.text.text == "****" })
    }

    @Test fun invalidZeroAndNegativeClawbackHaveNeutralColor() {
        host.launch(OrderDetailVisualFixtures.detail("detail_clawed_back"))
        val amount = rule.detailAmount("-¥8")
        assertEquals(host.primary, amount.result.layoutInput.style.color)
        assertEquals(28f, amount.result.layoutInput.style.fontSize.value, .01f)
    }

    @Test fun invalidFixedZeroDoesNotLeakNonzeroEstimate() {
        host.launch(OrderDetailVisualFixtures.detail("detail_blacklist"))
        val zero = rule.detailAmount("¥0")
        assertEquals(host.primary, zero.result.layoutInput.style.color)
        assertEquals(
            listOf("¥0", "¥129").sorted(),
            rule
                .detailDrawn(OrderTags.DETAIL_AMOUNTS)
                .map { it.result.layoutInput.text.text }
                .filter { it.contains("¥") }
                .sorted(),
        )
    }

    @Test fun paidHasOnlyContractSingleAmountWithoutFabricatedRiskRange() {
        host.launch(OrderDetailVisualFixtures.detail("detail_price_compare_risk"))
        rule.detailAmount("¥1.23")
        assertFalse(
            rule.detailDrawn().any {
                it.result.layoutInput.text.text
                    .contains("–") ||
                    it.result.layoutInput.text.text
                        .contains("较低金额")
            },
        )
        rule.onNodeWithTag(OrderTags.DETAIL_HELP).assertDoesNotExist()
    }
}
