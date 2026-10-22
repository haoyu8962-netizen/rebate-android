package com.couli.core.orders

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderDetailVisualFixtureTest {
    private val root =
        generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .first { File(it, "contract.lock").isFile }
    private val vectors = Json.parseToJsonElement(File(root, "specs/order-vectors.json").readText()).jsonObject
    private val visual = vectors.getValue("visualOrders").jsonObject

    @Test fun allThirtyFixturesParseStrictlyAndUseOnlyLockedFields() {
        val details = visual.getValue("details").jsonObject
        val reasons = visual.getValue("reasonDetails").jsonObject
        assertEquals(18, details.size)
        assertEquals((1..12).map { "detail_reason_%02d".format(it) }.toSet(), reasons.keys)
        (details + reasons).forEach { (name, raw) ->
            val obj = raw.jsonObject
            val parsed = OrderJson.detail(obj)
            assertNotNull(name, parsed)
            assertTrue(name, parsed!!.timeline.isNotEmpty())
            val forbidden =
                setOf(
                    "rebate_min_fen",
                    "rebate_max_fen",
                    "diff_fen",
                    "first_est_rebate_fen",
                    "reason_sub",
                    "appeal_result",
                    "appeal_submitted_at",
                )
            assertTrue("不能臆造字段：$name", obj.keys.intersect(forbidden).isEmpty())
            listOf("pay_amount_fen", "est_rebate_fen", "actual_fen", "clawback_fen").forEach { key ->
                val value = obj.getValue(key)
                assertTrue("金额只能是整数分或null", value.toString() == "null" || value.toString().matches(Regex("[0-9]+")))
            }
        }
    }

    @Test fun originalKnownActionVectorsRemainExact() {
        vectors
            .getValue("detailActions")
            .jsonArray
            .map {
                it.jsonObject
            }.filterNot {
                it
                    .getValue("name")
                    .jsonPrimitive.content
                    .startsWith("G_O08_")
            }.forEach { row ->
                assertEquals(
                    row.getValue("name").toString(),
                    row.getValue("expect"),
                    OrderDetailActions.of(row.getValue("detail").jsonObject),
                )
            }
    }

    @Test fun allOriginalRuleGroupsStillMatchUsingTheExistingVectorAdapter() {
        val adapter = OrderVectorTest()
        val run =
            OrderVectorTest::class.java.declaredMethods
                .single { it.name == "run" }
                .apply { isAccessible = true }
        val match =
            OrderVectorTest::class.java.declaredMethods
                .single { it.name == "match" }
                .apply { isAccessible = true }
        val groups =
            listOf(
                "query",
                "monthOptions",
                "orderNumber",
                "titleImage",
                "statusText",
                "amountCells",
                "creditPeriod",
                "timeline",
                "whyNoRebate",
                "priceCompareEntry",
                "detailActions",
                "viewProduct",
                "pendingTracks",
                "pendingDismiss",
                "errorAction",
                "cachePolicy",
                "cacheClear",
            )
        var count = 0
        for (group in groups) {
            for (value in vectors.getValue(group).jsonArray) {
                val row = value.jsonObject
                val name = row.getValue("name").jsonPrimitive.content
                if (name.startsWith("G_O08_unknown_")) continue
                val label = "$group.$name"
                match.invoke(adapter, run.invoke(adapter, group, row, label), row.getValue("expect"), label, false)
                count++
            }
        }
        assertEquals("原17组241条向量，不因未知状态新红灯停止后续组", 241, count)
    }

    @Test fun addedUnknownVectorsSuppressEveryReasonActionAndKeepProgressFlag() {
        val rows =
            vectors
                .getValue("detailActions")
                .jsonArray
                .map {
                    it.jsonObject
                }.filter {
                    it
                        .getValue("name")
                        .jsonPrimitive.content
                        .startsWith("G_O08_unknown_")
                }
        assertEquals(10, rows.size)
        rows.forEach { row ->
            assertEquals(row.getValue("name").toString(), row.getValue("expect"), OrderDetailActions.of(row.getValue("detail").jsonObject))
        }
    }

    @Test fun pendingFixtureHasNoAppealButClientDoesNotFilterExplicitServerAppeal() {
        val pending =
            visual
                .getValue("details")
                .jsonObject
                .getValue("detail_appeal_pending")
                .jsonObject
        assertEquals(emptyList<String>(), OrderJson.detail(pending)!!.reasonAction)
        val supplied = JsonObject(pending + ("reason_action" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("APPEAL")))))
        val view = OrderDetailActions.of(OrderJson.detail(supplied)!!)
        assertEquals(listOf("appeal"), view.buttons.map { it.actionRaw })
        assertTrue(view.appealPendingBlock)
    }

    @Test fun noRebateAndUnknownContainNonzeroSentinelsSoSuppressionIsNotVacuous() {
        listOf("detail_unknown", "detail_price_compare_no_rebate", "detail_blacklist").forEach { name ->
            val item =
                OrderJson.detail(
                    visual
                        .getValue("details")
                        .jsonObject
                        .getValue(name)
                        .jsonObject,
                )!!
            assertTrue(item.summary.estRebateFen!! > 0)
            assertFalse(item.summary.orderNo.isNullOrBlank())
        }
    }
}
