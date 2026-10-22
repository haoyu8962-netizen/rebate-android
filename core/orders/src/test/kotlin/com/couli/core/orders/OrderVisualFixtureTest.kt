package com.couli.core.orders

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderVisualFixtureTest {
    private val group: JsonObject by lazy {
        val root =
            generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
                .first { File(it, "contract.lock").isFile }
        Json
            .parseToJsonElement(File(root, "specs/order-vectors.json").readText())
            .jsonObject
            .getValue("visualOrders")
            .jsonObject
    }

    @Test fun everyVisualRowPassesStrictParserWithoutDroppedFieldsOrInventedListAmounts() {
        val pages = group.getValue("pages").jsonObject.values + group.getValue("statusPages").jsonObject.values
        assertEquals("13 页面 + 12 状态，防止空 fixture 通过", 25, pages.size)
        pages.forEach { value ->
            val page = value.jsonObject
            val parsed = checkNotNull(OrderJson.listData(page))
            assertEquals(page.getValue("items").jsonArray.size, parsed.items.size)
            assertEquals(0, parsed.dropped)
            page.getValue("items").jsonArray.forEach { raw ->
                val row = raw.jsonObject
                assertTrue(row.containsKey("quantity"))
                assertTrue(row.containsKey("pay_amount_fen"))
                assertTrue(row.containsKey("est_rebate_fen"))
                assertFalse(
                    row.keys.any {
                        it in
                            setOf("actual_fen", "clawback_fen", "rebate_min_fen", "rebate_max_fen", "expected_credit_period")
                    },
                )
            }
        }
    }

    @Test fun claimEntryPinnedIsStrictBooleanAndDefaultsFalse() {
        // 用 getter 反射只为先于实现保持编译；缺接口是明确业务红，不用 assume/ignore 放过。
        val getter = OrderListData::class.java.methods.singleOrNull { it.name == "getClaimEntryPinned" }
        assertNotNull("G-O04：OrderListData 需新增 claimEntryPinned: Boolean = false", getter)
        val cases = listOf(null, JsonNull, JsonPrimitive(false), JsonPrimitive(true), JsonPrimitive("true"), JsonPrimitive(1))
        cases.forEach { raw ->
            val page = JsonObject(mapOf("items" to JsonArray(emptyList())) + (raw?.let { mapOf("claim_entry_pinned" to it) } ?: emptyMap()))
            val parsed = checkNotNull(OrderJson.listData(page))
            assertEquals("claim_entry_pinned=$raw", raw == JsonPrimitive(true), getter!!.invoke(parsed))
        }
    }

    @Test fun pinnedIsNotPersistedAndRecreatedCacheDefaultsFalse() {
        val pinned =
            group
                .getValue("pages")
                .jsonObject
                .getValue("list_self_pinned")
                .jsonObject
        val parsed = checkNotNull(OrderJson.listData(pinned))
        val root =
            kotlin.io.path
                .createTempDirectory("orders-pinned-")
                .toFile()
        try {
            OrderStaleCache.Store(root).writeList("user", "self", parsed, 123L)
            val files = root.walkTopDown().filter { it.isFile }.toList()
            assertTrue("先确认缓存确实写出", files.isNotEmpty())
            assertTrue("缓存不保留与时间相关的置顶字段", files.none { it.readText().contains("claim_entry_pinned") })
            val restored = checkNotNull(OrderStaleCache.Store(root).readList("user", "self"))
            assertEquals(parsed.items, restored.data.items)
            val getter = OrderListData::class.java.methods.singleOrNull { it.name == "getClaimEntryPinned" }
            assertNotNull("G-O04：读取缓存后的缺省为 false", getter)
            assertEquals(false, getter!!.invoke(restored.data))
        } finally {
            root.deleteRecursively()
        }
    }
}
