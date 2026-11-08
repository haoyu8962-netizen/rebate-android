package com.couli.core.designsystem

import com.couli.generated.DefaultTexts
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APP-06a 共性第 1、3 条：接口商品卡 → 卡片数据。缺失价格保留「未知」（不补 0）；价格不可用时不显示任何金额、按钮「稍后再试」；
 * 金额未知卡（amount_unknown）不是价格不可用；stale 或 age_sec > 300 给取价处的价格变化提示（正好 300 不提示）。
 */
class ProductCardMapperTest {
    private val texts = ProductCardTexts { key -> "[$key]" }

    private fun card(extra: String): JsonObject =
        Json
            .parseToJsonElement(
                """{"product_key":"tb:1","platform":"taobao","title":"t","price_fen":3990,"coupon_fen":1000,"final_price_fen":2990,""" +
                    """"est_net_price_fen":2721,"rebate_min_fen":269,"rebate_max_fen":269,"rebate_basis":"normal","is_presale":false,""" +
                    """"cta":{"text_key":"btn.buy.coupon"},"stale":false,"age_sec":12,"quoted_at":"2026-10-02T09:30:00+08:00",""" +
                    """"disclaimer_keys":["price_basis","rebate_estimate"],"benefit_tags":["有券"],"availability":"ok"$extra}""",
            ).jsonObject

    private fun with(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(card("") + pairs.associate { it.first to Json.parseToJsonElement(it.second) })

    @Test
    fun normalCardMapsPriceRebateCtaAndDisclaimers() {
        // FU-X-CTA-KEY-DRIFT（#24，D10-327）：锁内键显示契约字典原文（向量 R1、known.coupon），按钮字从字典来、不再写死 PriceTerms，
        // 所以这条用会给出字典原文的 texts（原来的「[key]」texts 下 btn.buy.coupon 经 texts 取字会是「[btn.buy.coupon]」）
        val m = ProductCardMapper.map(card(""), ProductCardTexts { key -> DefaultTexts.text(key) ?: "[$key]" }, nowMillis = 0L)
        assertNotNull(m.data.price)
        assertEquals("领券购买", m.data.buyBase)
        assertEquals(BuyButtonState.NORMAL, m.buyState)
        assertEquals(listOf(PriceTerms.PRICE_BASIS, PriceTerms.REBATE_ESTIMATE), m.data.disclaimer)
        assertNull(m.data.priceUnavailableText)
        assertNull(m.data.staleNote)
    }

    @Test
    fun missingPriceIsUnknownNotZero() {
        val m = ProductCardMapper.map(with("final_price_fen" to "null"), texts)
        assertTrue(m.priceUnavailable)
        assertNull("不补 0、不显示金额", m.data.price)
        assertNull(m.data.estNet)
        assertEquals("[card.price_unavailable]", m.data.priceUnavailableText)
        assertEquals(BuyButtonState.RETRY_LATER, m.buyState)
        assertFalse("价格不可用不显示口径 i", m.data.showsDisclaimerButton)
    }

    @Test
    fun priceUnavailableAvailabilityHidesAmountsEvenWithNumbers() {
        val m = ProductCardMapper.map(with("availability" to "\"price_unavailable\""), texts)
        assertTrue(m.priceUnavailable)
        assertNull(m.data.price)
    }

    @Test
    fun amountUnknownCardIsNotPriceUnavailable() {
        val unknown =
            with(
                "rebate_basis" to "\"amount_unknown\"",
                "price_fen" to "null",
                "coupon_fen" to "null",
                "final_price_fen" to "null",
                "rebate_min_fen" to "null",
                "rebate_max_fen" to "null",
                "cta" to """{"text_key":"btn.buy"}""",
            )
        val m = ProductCardMapper.map(unknown, texts)
        assertFalse(m.priceUnavailable)
        assertNull(m.data.price)
        assertEquals(RebateKind.UNKNOWN, m.data.rebate.kind)
        assertEquals(BuyButtonState.NORMAL, m.buyState)
    }

    @Test
    fun staleOrOldQuoteGivesThePriceMayChangeNote() {
        assertEquals("[card.price_stale]", ProductCardMapper.map(with("stale" to "true"), texts).data.staleNote)
        assertNotNull(ProductCardMapper.map(with("age_sec" to "301"), texts).data.staleNote)
        assertNull("正好 300 秒不提示", ProductCardMapper.map(with("age_sec" to "300"), texts).data.staleNote)
    }

    /**
     * FU-X-CTA-KEY-DRIFT（#24，D10-327；原名 unknownCtaKeyGoesThroughTextsAndMissingCtaDisablesBuying）：未知键先经 texts，texts 给得出字照常显示；
     * texts 给不出（空串 / 回键名）回落 btn.buy「购买」、照常可点、记一条只带键的诊断（向量 R2 / R3：unknown.contract、unknown.echo、unknown.none）；
     * 没有 cta / 空键仍禁用、不兜底、不记诊断（R4：missing.cta、missing.empty_key）。
     */
    @Test
    fun unknownCtaKeyGoesThroughTextsThenFallsBackToBuyAndMissingCtaDisablesBuying() {
        val other = with("cta" to """{"text_key":"btn.other"}""")
        val rendered = mutableListOf<String>()
        val shown = ProductCardMapper.map(other, texts, onCtaFallback = { rendered += it })
        assertEquals("[btn.other]", shown.data.buyBase)
        assertEquals(BuyButtonState.NORMAL, shown.buyState)
        assertTrue("texts 给得出字不算缺键", rendered.isEmpty())
        listOf(ProductCardTexts { "" }, ProductCardTexts { key -> key }).forEach { blank ->
            val logged = mutableListOf<String>()
            val m = ProductCardMapper.map(other, blank, onCtaFallback = { logged += it })
            assertEquals("购买", m.data.buyBase)
            assertEquals("缺键本身不置灰", BuyButtonState.NORMAL, m.buyState)
            assertEquals(listOf("btn.other"), logged)
        }
        listOf("null", """{"text_key":""}""").forEach { cta ->
            val logged = mutableListOf<String>()
            val m = ProductCardMapper.map(with("cta" to cta), texts, onCtaFallback = { logged += it })
            assertEquals("", m.data.buyBase)
            assertEquals(BuyButtonState.DISABLED, m.buyState)
            assertTrue("没有键不记诊断", logged.isEmpty())
        }
    }
}
