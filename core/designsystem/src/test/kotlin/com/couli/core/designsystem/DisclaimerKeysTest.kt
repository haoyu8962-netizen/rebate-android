package com.couli.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** BR-PRICE-03 细则「无券商品与混排列表的口径说明」：列表底部那一句按各卡 disclaimer_keys 选（R1-06）。 */
class DisclaimerKeysTest {
    private val withCoupon = listOf(DisclaimerKeys.PRICE_BASIS, DisclaimerKeys.REBATE_COMPARE)
    private val noCoupon = listOf(DisclaimerKeys.PRICE_BASIS_GENERAL, DisclaimerKeys.REBATE_COMPARE)
    private val amountUnknown = emptyList<String>()

    @Test
    fun allCardsWithCouponUsePriceBasis() {
        assertEquals(DisclaimerKeys.PRICE_BASIS, DisclaimerKeys.listFooterPriceKey(listOf(withCoupon, withCoupon)))
    }

    @Test
    fun mixedListUsesGeneral() {
        // 例：首页物料流 6 张卡，5 张有券、1 张无券 → 底部「价格按单件计算…」
        val cards = List(5) { withCoupon } + listOf(noCoupon)
        assertEquals(DisclaimerKeys.PRICE_BASIS_GENERAL, DisclaimerKeys.listFooterPriceKey(cards))
    }

    @Test
    fun allNoCouponUsesGeneral() {
        assertEquals(DisclaimerKeys.PRICE_BASIS_GENERAL, DisclaimerKeys.listFooterPriceKey(listOf(noCoupon, noCoupon)))
    }

    @Test
    fun cardsWithoutPriceDoNotDecide() {
        assertEquals(DisclaimerKeys.PRICE_BASIS, DisclaimerKeys.listFooterPriceKey(listOf(withCoupon, amountUnknown)))
        assertNull(DisclaimerKeys.listFooterPriceKey(listOf(amountUnknown)))
    }

    @Test
    fun textsAreTheRuleDefaults() {
        assertEquals("券后价按单件计算，不含运费及会员价、跨店满减等优惠，以下单页为准", DisclaimerKeys.text(DisclaimerKeys.PRICE_BASIS))
        assertEquals("价格按单件计算，不含运费及会员价、跨店满减等优惠，以下单页为准", DisclaimerKeys.text(DisclaimerKeys.PRICE_BASIS_GENERAL))
        assertNull(DisclaimerKeys.text("unknown_key"))
    }
}
