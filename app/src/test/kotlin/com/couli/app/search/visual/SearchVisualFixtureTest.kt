package com.couli.app.search.visual

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchVisualFixtureTest {
    @Test fun allIdentifiersAreSyntheticAndNoBStageSignalsAreInvented() {
        val cards = SearchVisualFixtures.data.getValue("cards").jsonObject
        assertEquals(13, cards.size)
        cards.values.forEach { value ->
            val row = value.jsonObject
            assertTrue(
                row
                    .getValue("product_key")
                    .jsonPrimitive.content
                    .contains(":sample-"),
            )
            assertTrue(
                row
                    .getValue("item_ref")
                    .jsonPrimitive.content
                    .startsWith("sample-ref-"),
            )
            assertTrue(
                row
                    .getValue("title")
                    .jsonPrimitive.content
                    .startsWith("样例"),
            )
            assertEquals("样例商品店", row.getValue("shop_name").jsonPrimitive.content)
            assertFalse(row.containsKey("image"))
            assertFalse(row.containsKey("platform_status"))
            assertFalse(row.getValue("rebate_basis").jsonPrimitive.content in listOf("login_required", "amount_unknown"))
        }
    }

    @Test fun boardHistoryClockAndPriceShapesAreFixed() {
        assertEquals(listOf("纯牛奶", "抽纸", "洗衣凝珠", "保温杯"), SearchVisualFixtures.history)
        assertEquals(1793757600000L, SearchVisualFixtures.now)
        assertEquals(3, SearchVisualFixtures.cards("normal").size)
        assertEquals(2, SearchVisualFixtures.cards("pdd").size)
        assertEquals(2, SearchVisualFixtures.cards("fallback").size)
        assertEquals(
            "56",
            SearchVisualFixtures
                .card("tb_range")
                .getValue("rebate_min_fen")
                .jsonPrimitive.content,
        )
        assertEquals(
            "112",
            SearchVisualFixtures
                .card("tb_range")
                .getValue("rebate_max_fen")
                .jsonPrimitive.content,
        )
        assertEquals(
            "56",
            SearchVisualFixtures
                .card("tb_single")
                .getValue("rebate_max_fen")
                .jsonPrimitive.content,
        )
        assertEquals(
            "true",
            SearchVisualFixtures
                .card("tb_presale")
                .getValue("is_presale")
                .jsonPrimitive.content,
        )
        assertEquals(
            "5",
            SearchVisualFixtures
                .error("limited")
                .getValue("retry_after_seconds")
                .jsonPrimitive.content,
        )
    }
}
