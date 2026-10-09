package com.couli.core.designsystem

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-AND-17（APP-06a 共性第 22、27、31 条在 Android 上的核对）：
 * - 22：卡片数据带契约 image（只收 https；没有为 null → 占位）；
 * - 27：features.platform_status=maintenance → 按钮「稍后再试」禁用；详情模型 buyEnabled=false；
 * - 31：not_launched → 返利金额、预估返后与读屏金额都不显示，换成「X返利即将开放」，价格保留，按钮禁用。
 */
class ProductCardPlatformStatusTest {
    private val texts =
        object : ProductCardTexts {
            override fun text(key: String): String =
                when (key) {
                    "platform.taobao" -> "淘宝"
                    "error.50301.not_launched" -> "{platform_name}返利即将开放"
                    "card.retry_later" -> "稍后再试"
                    else -> "[$key]"
                }
        }

    private fun card(extra: String = ""): JsonObject =
        Json
            .parseToJsonElement(
                """{"product_key":"tb:1","platform":"taobao","title":"t","price_fen":3990,"coupon_fen":1000,"final_price_fen":2990,""" +
                    """"est_net_price_fen":2721,"rebate_min_fen":269,"rebate_max_fen":269,"rebate_basis":"normal","is_presale":false,""" +
                    """"cta":{"text_key":"btn.buy.coupon"},"stale":false,"age_sec":12,""" +
                    """"disclaimer_keys":["price_basis","rebate_estimate"],""" +
                    """"availability":"ok"$extra}""",
            ).jsonObject

    @Test
    fun lesson22_imageUrlComesFromTheContractField() {
        assertEquals(
            "https://img.example.test/p/1.png",
            ProductCardMapper.map(card(""","image":"https://img.example.test/p/1.png""""), texts).data.imageUrl,
        )
        assertNull("只收 https", ProductCardMapper.map(card(""","image":"http://img.example.test/p/1.png""""), texts).data.imageUrl)
        assertNull("没有图 → 占位", ProductCardMapper.map(card(""","image":null"""), texts).data.imageUrl)
    }

    @Test
    fun lesson27_maintenanceShowsRetryLaterDisabled() {
        val m = ProductCardMapper.map(card(), texts, purchaseStatus = "maintenance")
        assertEquals(BuyButtonState.RETRY_LATER, m.buyState)
        assertEquals("稍后再试", m.retryLaterText)
        assertTrue("维护中照常显示返利", m.data.rebate.hasAmount)
        val view =
            ProductDetailState.map(
                card(),
                Json.parseToJsonElement("""{"loggedIn":true,"privacy":"consented","platformStatus":"maintenance"}""").jsonObject,
            )
        assertFalse(view.buyEnabled)
        assertEquals("maintenance", view.purchaseStatus)
        val on = ProductDetailState.map(card(), Json.parseToJsonElement("""{"loggedIn":true,"privacy":"consented"}""").jsonObject)
        assertTrue("没有平台状态按 on", on.buyEnabled)
    }

    @Test
    fun lesson31_notLaunchedHidesRebateAmountsEstNetAndSpokenAmounts() {
        val m = ProductCardMapper.map(card(), texts, purchaseStatus = "not_launched")
        assertEquals("淘宝返利即将开放", m.data.rebate.text)
        assertFalse(m.data.rebate.hasAmount)
        assertFalse(
            "读屏不读金额",
            m.data.rebate.spoken
                .contains("2.69"),
        )
        assertNull(m.data.estNet)
        assertNull(m.data.visibleEstNet)
        assertEquals("价格保留", "¥29.9", m.data.price?.mainAmount)
        assertEquals(BuyButtonState.RETRY_LATER, m.buyState)
        assertEquals("淘宝返利即将开放", m.retryLaterText)
    }
}
