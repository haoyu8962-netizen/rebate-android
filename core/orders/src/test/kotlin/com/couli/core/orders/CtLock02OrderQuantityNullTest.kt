package com.couli.core.orders

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * CT-LOCK-02：OrderSummary / OrderDetail 的 quantity 由 integer 改为 integer | null。
 * 本端解析本来就按「缺失 / 非数字 → null、不当坏数据」处理：带 null 的行不丢、件数为空（不显示），回填原样写 null。
 */
class CtLock02OrderQuantityNullTest {
    private val row =
        Json.parseToJsonElement(
            """{"order_id":"o-1","platform":"taobao","display_status":"pending","order_no":"123456",
            |"pay_amount_fen":1990,"est_rebate_fen":100,"quantity":null,"timeline":[]}
            """.trimMargin(),
        ) as JsonObject

    @Test
    fun nullQuantityKeepsTheRowAndReadsAsMissing() {
        val summary = OrderJson.summary(row)
        assertNotNull("row with quantity=null is not malformed", summary)
        assertNull(summary!!.quantity)
        val list = OrderJson.listData(JsonObject(mapOf("items" to kotlinx.serialization.json.JsonArray(listOf(row)))))
        assertEquals(1, list!!.items.size)
        assertEquals(0, list.dropped)
        val detail = OrderJson.detail(row)
        assertNotNull(detail)
        assertNull(detail!!.summary.quantity)
        assertEquals(JsonNull, OrderJson.encode(summary)["quantity"])
    }

    @Test
    fun integerQuantityIsUnchanged() {
        val withQuantity = JsonObject(row + ("quantity" to kotlinx.serialization.json.JsonPrimitive(2)))
        assertEquals(2, OrderJson.summary(withQuantity)!!.quantity)
    }
}
