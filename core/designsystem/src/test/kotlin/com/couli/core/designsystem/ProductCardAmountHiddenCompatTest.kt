package com.couli.core.designsystem

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * R1-08：ProductCardList 只传 amountHidden=true（卡片数据带真实返利、amountMask=null）时，
 * 返利标签、读屏、预估返后与购买按钮用同一份隐藏状态，真实返利金额不显示、不朗读。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-xxhdpi")
class ProductCardAmountHiddenCompatTest {
    @get:Rule
    val rule = createComposeRule()

    private fun card(mask: AmountMask? = null) =
        ProductCardData(
            platformName = "淘宝",
            title = "样例商品",
            price = PriceTagModel.make("taobao", 2990, 0, 2990, false),
            rebate = RebateTagModel.make("normal", 269, 269, false),
            buyBase = "去购买",
            imageLabel = "商品图",
            estNet = EstNetModel.make("normal", 269, 2721),
            disclaimer = listOf(PriceTerms.PRICE_BASIS_GENERAL),
            infoLabel = "口径说明",
            amountMask = mask,
        )

    @Test
    fun withAmountHiddenUnifiesTheMaskSource() {
        val plain = card()
        val hidden = plain.withAmountHidden(true)
        assertTrue(hidden.amountHidden)
        assertEquals(AmountMask.DEFAULT_HIDDEN, hidden.amountMask)
        assertEquals("预估返 ****", hidden.displayedRebate.text)
        assertEquals("预估返，金额已隐藏", hidden.displayedRebate.spoken)
        assertNull("隐藏时不出预估返后", hidden.visibleEstNet)
        // 调用方给了自己的遮罩：沿用它
        val custom = card(AmountMask(hidden = false, mask = "***", spoken = "已隐藏")).withAmountHidden(true)
        assertEquals("预估返 ***", custom.displayedRebate.text)
        // 显式显示：遮罩设为不隐藏
        assertFalse(card(AmountMask.DEFAULT_HIDDEN).withAmountHidden(false).amountHidden)
        assertTrue("一致时原样返回", plain.withAmountHidden(false) === plain)
    }

    @Test
    fun listCardWithOnlyAmountHiddenFlagHidesRebateEverywhere() {
        rule.setContent {
            CouliTheme {
                ProductCardList(data = card(), onBuy = {}, amountHidden = true)
            }
        }
        rule.onAllNodes(hasText("2.69", substring = true)).fetchSemanticsNodes().let { assertTrue("返利金额不显示", it.isEmpty()) }
        rule
            .onAllNodes(hasContentDescription("2.69", substring = true))
            .fetchSemanticsNodes()
            .let { assertTrue("返利金额不朗读", it.isEmpty()) }
        rule.onAllNodes(hasContentDescription("预估返，金额已隐藏")).fetchSemanticsNodes().let { assertEquals(1, it.size) }
        rule.onAllNodes(hasText(PriceTerms.EST_NET, substring = true)).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
        rule.onNodeWithText("去购买").assertExists()
    }
}
