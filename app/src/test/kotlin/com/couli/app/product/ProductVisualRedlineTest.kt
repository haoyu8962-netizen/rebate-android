package com.couli.app.product

import android.app.Application
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.search.SearchDraftTexts
import com.couli.core.designsystem.ProductCardMapper
import com.couli.feature.product.CreditNote
import com.couli.feature.product.ProductTags
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** APP-06a 红线仍是行为要求，不能用新视觉替换旧判定。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1800dp-xhdpi")
class ProductVisualRedlineTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { ProductVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun nullPriceDoesNotBecomeZeroAndUnavailableHidesAllAmounts() {
        val card = ProductVisualFixtures.card("price_unavailable")
        assertNull(ProductCardMapper.map(card, SearchDraftTexts, ProductVisualFixtures.now).data.price)
        host.launch("price_unavailable")
        rule.onNodeWithText("暂时查不到该商品价格").assertExists()
        rule.onNodeWithText("¥", substring = true, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag(ProductTags.BUY).assertIsNotEnabled().performClick()
        assertTrue(host.buys.isEmpty())
    }

    @Test fun amountUnknownKeepsUnknownInsteadOfZero() {
        val card = ProductVisualFixtures.card("amount_unknown")
        assertNull(ProductCardMapper.map(card, SearchDraftTexts, ProductVisualFixtures.now).data.price)
        host.launch("amount_unknown")
        rule.onNodeWithText("¥0", substring = true, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag(ProductTags.BUY).assertIsEnabled()
    }

    @Test fun notLaunchedHidesRebateFromTextAndAccessibilityAndDisablesPurchase() {
        host.status = "not_launched"
        host.launch()
        rule.onNodeWithText("¥1.23", substring = true, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("预估返后", substring = true, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("¥46.78", useUnmergedTree = true).assertExists()
        val leaked =
            rule
                .onAllNodes(
                    androidx.compose.ui.test.SemanticsMatcher("读屏返利金额泄露") {
                        listOf("1.23", "2.34", "45.55").any { amount -> it.readableText().contains(amount) }
                    },
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
        assertTrue(leaked.isEmpty())
        rule.onNodeWithTag(ProductTags.BUY).assertIsNotEnabled().performClick()
        assertTrue(host.buys.isEmpty())
    }

    @Test fun maintenanceAndRecoveryStillFollowLiveConfig() {
        host.launch()
        host.status = "maintenance"
        host.refresh()
        rule.onNodeWithTag(ProductTags.BUY).assertIsNotEnabled().performClick()
        assertTrue(host.buys.isEmpty())
        host.status = "on"
        host.refresh()
        rule.onNodeWithTag(ProductTags.BUY).assertIsEnabled().performClick()
        assertEquals(1, host.buys.size)
    }

    @Test fun creditSwitchFalseOnlyHidesCreditLine() {
        host.credit = false
        host.launch()
        rule.onNodeWithTag(ProductTags.DISCLAIMER).performScrollTo().assertExists()
        rule.onNodeWithText("确认收货后随联盟月度结算入账").assertDoesNotExist()
        rule.onNodeWithText("券后价按单件计算", substring = true).assertExists()
    }

    @Test fun missingCreditSwitchPreservesCreditLine() {
        host.launch()
        rule.onNodeWithText("确认收货后随联盟月度结算入账").performScrollTo().assertExists()
    }

    @Test fun creditMatrixKeepsBasisZeroAndIndependentSwitchRules() {
        listOf("tb_coupon_range", "jd_no_coupon_single", "amount_unknown").forEach { name ->
            val card = ProductVisualFixtures.card(name)
            assertTrue(name, CreditNote.visible(card, null))
            assertTrue(name, CreditNote.visible(card, true))
            assertFalse(name, CreditNote.visible(card, false))
        }
        listOf("tb_rebate_gone", "pdd_price_compare", "login_required").forEach { name ->
            assertFalse(name, CreditNote.visible(ProductVisualFixtures.card(name), true))
        }
        val zero = JsonObject(ProductVisualFixtures.card() + ("rebate_max_fen" to JsonPrimitive(0)))
        assertFalse(CreditNote.visible(zero, null))
    }

    @Test fun guestBuyStillPassesPublicModeAndServerCard() {
        host.loggedIn = false
        host.launch("login_required")
        rule.onNodeWithTag(ProductTags.BUY).performClick()
        assertEquals(ProductVisualFixtures.card("login_required") to true, host.buys.single())
    }
}
