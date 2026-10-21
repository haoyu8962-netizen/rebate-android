package com.couli.app.product

import java.io.File
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 仅补运行时语义无法区分的接线约束；布局/像素由其余用例实测。 */
class ProductVisualWiringTest {
    private val product = source("feature/product/src/main/kotlin")
    private val cards =
        File(
            ProductVisualFixtures.root,
            "core/designsystem/src/main/kotlin/com/couli/core/designsystem/ProductCard.kt",
        ).readText().withoutComments()

    @Test fun navigationDelegatesToDesignSystemAndOnlyPageHandlesSystemBack() {
        assertTrue("D1 必须使用系统 NavBar", Regex("\\bNavBar\\s*\\(").containsMatchIn(product))
        assertTrue("页面已有 BackHandler，NavBar 必须关闭自己的系统返回", Regex("handleSystemBack\\s*=\\s*false").containsMatchIn(product))
        assertEquals("页面只有一个系统返回处理器", 1, Regex("\\bBackHandler\\s*[( {]").findAll(product).count())
    }

    @Test fun detailBadgedTitlePassesPlatformCodeExplicitly() {
        val large = cards.substringAfter("fun ProductCardLarge(").substringBefore("private fun LargePriceBlock")
        assertTrue("D4 禁止按中文平台名猜图", Regex("platformCode\\s*=").containsMatchIn(large))
        val mapper = source("core/designsystem/src/main/kotlin")
        assertTrue("映射层必须把契约平台编码带入详情数据", Regex("platformCode\\s*=\\s*platform\\b").containsMatchIn(mapper))
    }

    @Test fun creditLineUsesClockAndErrorStatesUseSharedComponents() {
        assertTrue("D7 入账行传钟表图标", product.contains("CouliIcons.clock"))
        assertTrue("D11 通用错误使用 ErrorState", Regex("\\bErrorState\\s*\\(").containsMatchIn(product))
        assertTrue("G-D05 无卡片下架使用 EmptyState", Regex("\\bEmptyState\\s*\\(").containsMatchIn(product))
    }

    @Test fun fixtureContainsBoardAmountsAndNullPricesWithoutChangingDecisions() {
        val cards = ProductVisualFixtures.cards
        assertEquals(15, cards.size)
        assertEquals(
            "4678",
            cards
                .getValue("tb_coupon_range")
                .jsonObject
                .getValue("final_price_fen")
                .jsonPrimitive.content,
        )
        assertEquals(
            "234",
            cards
                .getValue("jd_no_coupon_single")
                .jsonObject
                .getValue("rebate_max_fen")
                .jsonPrimitive.content,
        )
        assertEquals(
            "price_compare",
            cards
                .getValue("pdd_price_compare")
                .jsonObject
                .getValue("no_rebate_cause")
                .jsonPrimitive.content,
        )
        assertEquals(JsonNull, cards.getValue("price_unavailable").jsonObject.getValue("final_price_fen"))
        assertEquals(
            "4678",
            cards
                .getValue("tb_off_shelf")
                .jsonObject
                .getValue("final_price_fen")
                .jsonPrimitive.content,
        )
        assertFalse("fixture 不伪造 images 数组", cards.values.any { "images" in it.jsonObject })
    }

    private fun source(relative: String): String =
        File(ProductVisualFixtures.root, relative)
            .walkTopDown()
            .filter { it.extension == "kt" }
            .joinToString("\n") { it.readText().withoutComments() }

    private fun String.withoutComments(): String = replace(Regex("/\\*[\\s\\S]*?\\*/|//[^\\n]*"), "")
}
