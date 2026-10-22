package com.couli.app.purchase

import android.app.Application
import android.os.Looper
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.product.ProductTags
import com.couli.feature.trade.TradeOperations
import com.couli.feature.trade.TradeTags
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** B1/B3/B5/G-D07；所有入口从真实购买按钮驱动，取消后的迟到响应也不得外跳。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], shadows = [PurchaseVisualCallbacks::class], qualifiers = "w390dp-h956dp-xhdpi")
class PurchaseVisualFlowTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { PurchaseVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun priceContinue() = confirmation("price", true)

    @Test fun priceCancel() = confirmation("price", false)

    @Test fun couponContinue() = confirmation("coupon", true)

    @Test fun couponCancel() = confirmation("coupon", false)

    @Test fun rebateContinue() = confirmation("rebate", true)

    @Test fun rebateCancel() = confirmation("rebate", false)

    @Test fun priceRebateContinue() = confirmation("price_rebate", true)

    @Test fun priceRebateCancel() = confirmation("price_rebate", false)

    private fun confirmation(
        response: String,
        confirm: Boolean,
    ) {
        host.launch()
        host.buy()
        host.answer(response)
        host.counts(open = 1)
        val updated = host.card
        rule.button(if (confirm) "继续购买" else "取消").performClick()
        rule.waitForIdle()
        host.counts(open = 1, jumps = if (confirm) 1 else 0)
        PurchaseVisualCallbacks.assertCalls(host.controller, if (confirm) "confirm" else "dismissDialog")
        assertEquals(if (confirm) 1 else 0, host.reports.size)
        assertEquals("取消/继续都保留已复核卡", updated, host.card)
        assertNull(host.controller.state.value.dialog)
        if (!confirm) {
            val key =
                when (response) {
                    "rebate" -> "trade.cancelled.rebate"
                    "price_rebate" -> "trade.cancelled.price_rebate"
                    else -> "trade.cancelled.price"
                }
            assertEquals(
                key,
                host.controller.state.value.toast
                    ?.key,
            )
        }
    }

    @Test fun loadingCancelRevokesLateJumpAndDoesNotAddDataToModal() {
        host.launch()
        host.buy()
        val loading = rule.onNodeWithTag(TradeTags.LOADING).fetchSemanticsNode()
        val texts =
            generateSequence(listOf(loading)) { nodes -> nodes.flatMap { it.children }.takeIf { it.isNotEmpty() } }
                .flatten()
                .flatMap { it.config.getOrElse(SemanticsProperties.Text) { emptyList() } }
                .joinToString { it.text }
        assertTrue(texts.contains("正在打开淘宝"))
        assertTrue("加载层不泄露商品/金额", !texts.contains("¥") && !texts.contains("纯牛奶"))
        rule.onNodeWithTag(TradeTags.LOADING_CANCEL).performClick()
        rule.waitForIdle()
        host.counts(open = 1)
        host.answer("unchanged")
        host.counts(open = 1)
        assertEquals(0, host.reports.size)
        rule.onNodeWithTag(TradeTags.LOADING).assertDoesNotExist()
    }

    @Test fun timeoutRetryPreservesExactRequestAndOnlySendsOnce() {
        host.launch()
        host.buy()
        val before = host.api.last().call
        rule.runOnIdle { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9)) }
        rule.waitForIdle()
        host.counts(open = 1)
        rule.onNodeWithTag(TradeTags.TIMEOUT_RETRY).performClick()
        rule.waitForIdle()
        host.counts(open = 2)
        val after = host.api.last().call
        assertEquals(before.idempotencyKey, after.idempotencyKey)
        assertEquals(before.pathParams, after.pathParams)
        assertEquals(before.body, after.body)
        host.answer("unchanged")
        host.counts(open = 2, jumps = 1)
    }

    @Test fun offShelfErrorDoesNotJumpAndDisablesBuy() = offShelf(false, false)

    @Test fun offShelfSuccessDoesNotJumpAndDisablesBuy() = offShelf(true, false)

    @Test fun offShelfErrorUsesPoliteStateAnnouncementInsteadOfToast() = offShelf(false, true)

    @Test fun offShelfSuccessUsesPoliteStateAnnouncementInsteadOfToast() = offShelf(true, true)

    private fun offShelf(
        success: Boolean,
        announcement: Boolean,
    ) {
        host.launch()
        host.buy()
        if (success) host.answer("off_shelf") else host.fail(30141)
        host.counts(open = 1)
        assertEquals(0, host.reports.size)
        rule.onNodeWithTag(ProductTags.BUY).assertIsNotEnabled().performClick()
        rule.waitForIdle()
        host.counts(open = 1)
        assertEquals(1, host.buyClicks)
        assertNull(host.controller.state.value.dialog)
        if (announcement) {
            assertNull("去掉30141轻提示", host.controller.state.value.toast)
            rule.onNodeWithTag(TradeTags.TOAST).assertDoesNotExist()
            val polite =
                rule
                    .onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite), true)
                    .fetchSemanticsNodes()
            assertTrue("状态变化须保留 polite 播报", polite.any { it.config.toString().contains("商品已下架") })
        }
        assertEquals(0, host.count(TradeOperations.GET_UNION_AUTH_URL))
    }

    @Test fun browserCancelKeepsCountsAndDoesNotExecuteFallback() {
        host.launch("jd_no_coupon_single")
        host.buy()
        host.answer(PurchaseVisualFixtures.jump("h5", "https://shop.example.test/item"))
        rule.onNodeWithTag(TradeTags.BROWSER).assertExists()
        host.counts(open = 1)
        rule.onNodeWithTag(TradeTags.LOADING_CANCEL).performClick()
        rule.waitForIdle()
        host.counts(open = 1)
        assertEquals(0, host.reports.size)
    }

    @Test fun cachedRequoteStillJumpsExactlyOnce() {
        host.launch()
        host.buy()
        host.answer("cached")
        host.counts(open = 1, jumps = 1)
        assertEquals(1, host.reports.size)
        assertEquals(
            "trade.requote_notice",
            host.controller.state.value.toast
                ?.key,
        )
    }
}
