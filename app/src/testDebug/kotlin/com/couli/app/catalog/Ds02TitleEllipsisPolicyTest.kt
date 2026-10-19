package com.couli.app.catalog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 只允许商品标题省略：整份原文（包括不可见部分）不得携带金额。整数规格数量不当金额。 */
internal fun ds02AssertProductTitleMayEllipsize(
    text: String,
    maxLines: Int,
) {
    assertTrue("商品标题至少保留两行", maxLines >= 2)
    val amount =
        Regex(
            """[¥￥]|(?i:\b(?:CNY|RMB)\s*\d)|\d[\d,，]*[.．]\d{2}(?!\d)|\d[\d,，]*(?:\.\d+)?\s*元""" +
                """|(?:价格|到手价|券后价|返利|实付)\s*[:：]?\s*\d|\d{1,3}(?:[,，]\d{3})+""",
        )
    assertFalse("允许省略的标题不得含金额：$text", amount.containsMatchIn(java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)))
}

class Ds02TitleEllipsisPolicyTest {
    @Test
    fun productSpecificationsAndLongTitlesMayUseTwoLines() {
        ds02AssertProductTitleMayEllipsize("全脂纯牛奶 250ml × 24 盒 整箱装", 2)
        ds02AssertProductTitleMayEllipsize("矿泉水 1.5L × 12 瓶 家庭装", 3)
    }

    @Test
    fun priceOrRebateCannotBeHiddenInTheTitleWhitelist() {
        listOf("¥99", "￥99.00", "到手 123.45", "99元", "RMB 99", "CNY 99", "返利：12", "1,234", "９９．００")
            .forEach { amount ->
                assertThrows("漏拦金额 $amount", AssertionError::class.java) {
                    ds02AssertProductTitleMayEllipsize("商品标题 $amount", 2)
                }
            }
    }

    @Test
    fun titleCannotBeReducedToOneLine() {
        assertThrows(AssertionError::class.java) { ds02AssertProductTitleMayEllipsize("纯牛奶", 1) }
    }
}
