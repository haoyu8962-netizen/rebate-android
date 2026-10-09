/*
 * 实现前测试；只读输出，未编译运行。
 *
 * 接线约定：
 * - 生产类型位于 com.couli.core.orders；测试使用 JUnit4、
 *   kotlinx.serialization.json 和已有 Generated 模块，不增加依赖。
 * - JSON 边界：对象入参与对象返回值为 JsonObject，数组为 List<JsonObject>；
 *   JsonNull 表示空值。可以由生产强类型模型提供同名 JSON 入参重载，
 *   测试只解包及比较，不实现业务判定。
 * - OrderQuery.initial(scope: String?) -> OrderQuery，null 表示路由未给 scope；
 *   apply(op: String, value: String?) 原地修改；params(): JsonObject；
 *   sendsRequest(): Boolean。set_q 表示提交/清空，不表示每次键入。
 *   next_page 的 value 使用 step.nextCursor，其他操作使用 step.value。
 * - 以下 of 返回 JsonObject：
 *   OrderMonthOptions.of(earliestVisibleDate: String?, nowMs: Long)；
 *   OrderNumberDisplay.of(item)；OrderTitleImage.of(item)；
 *   OrderStatusText.of(displayStatusRaw: String)；OrderAmountCells.of(item, masked)；
 *   CreditPeriodText.of(displayStatus: String, period: String?, nowMs: Long)；
 *   WhyNoRebate.of(detail)；
 *   PriceCompareEntry.of(detail, helpLinks: JsonObject?, routeCatalog: Map<String, RouteSpec>)；
 *   OrderDetailActions.of(detail)；ViewProductEntry.of(detail)。
 * - OrderTimelineView.of(displayStatus: String, nodes: List<JsonObject>, nowMs: Long)
 *   -> List<JsonObject>；
 *   PendingTrackCards.of(items: List<JsonObject>, session: String,
 *     notification: String, requestedBefore: Boolean) -> List<JsonObject>。
 * - PendingTrackDismiss.request(card): JsonObject；
 *   PendingTrackDismiss.onOutcome(outcome): JsonObject。
 * - OrderErrorAction.map(target: String, code: Int?, transport: Boolean,
 *   headers: Map<String, String>, traceId: String?, hasCache: Boolean,
 *   filtered: Boolean): JsonObject。
 * - OrderStaleCache.isCacheable(kind: String, query: JsonObject?): Boolean；
 *   OrderStaleCache.clearsOn(event: String): Boolean。
 * - 字段同向量结构；金额为可空整数分，以 Long 读取，不转浮点元。
 *   时间从 nowIso 解析为毫秒，按 +08:00 解释，不读系统时钟。
 *   金额渲染沿用 CouliFormat，format-vectors 已覆盖的格式不重复。
 * - expect 缺字段不判定；显式 null 接受字段缺失/JsonNull。
 *   params/request/buttons/cards 精确比较，数组有序且长度必须一致。
 * - G11 超长策略未定，仅断言实际发送的 q 不超过 40 个 Unicode 码点；
 *   本组 fixture 不含多码点字素，不裁定组合字符计数。
 * - 不访问网络、权限、剪贴板或缓存文件。执行层验收见向量 gaps。
 */

package com.couli.core.orders

import com.couli.generated.Routes
import java.io.File
import java.time.OffsetDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderVectorTest {
    private val groups =
        listOf(
            "query", "monthOptions", "orderNumber", "titleImage", "statusText",
            "amountCells", "creditPeriod", "timeline", "whyNoRebate",
            "priceCompareEntry", "detailActions", "viewProduct", "pendingTracks",
            "pendingDismiss", "errorAction", "cachePolicy", "cacheClear",
        )

    private val vectors: JsonObject by lazy {
        val start =
            File(System.getProperty("user.dir") ?: error("缺少 user.dir")).canonicalFile
        val root =
            generateSequence(start) { it.parentFile }
                .firstOrNull { File(it, "contract.lock").isFile }
                ?: error("从 $start 向上找不到 contract.lock")
        Json.parseToJsonElement(
            File(root, "specs/order-vectors.json").readText(Charsets.UTF_8),
        ).jsonObject
    }

    @Test
    fun manifest() {
        assertEquals(1, vectors.getValue("version").jsonPrimitive.int)
        assertTrue(vectors.getValue("sources").jsonArray.isNotEmpty())
        assertTrue(vectors.getValue("gaps").jsonArray.isNotEmpty())
        for (group in groups) {
            val rows = vectors.rows(group)
            assertTrue("$group 不能为空", rows.isNotEmpty())
            val names = rows.map { it.text("name") }
            assertTrue("$group 名称不能为空", names.all { it.isNotBlank() })
            assertEquals("$group 名称重复", names.size, names.toSet().size)
            rows.forEach { it.getValue("expect").jsonObject }
        }
        for (route in listOf(
            "OrderList", "OrderDetail", "FindOrder", "ProductDetail", "Help",
            "Ledger", "Withdraw", "Appeal", "AuthManage",
        )) {
            assertTrue("生成路由表缺少 $route", Routes.all.containsKey(route))
        }
        for (row in vectors.rows("query")) {
            val name = row.text("name")
            if (name == "G11_unicode_40" || name == "G11_over40_policy_unspecified") {
                val value = row.rows("steps")[1].text("value")
                val count = value.codePointCount(0, value.length)
                if (name == "G11_unicode_40") {
                    assertEquals(name, 40, count)
                    assertTrue("$name 必须含非 BMP 字符", value.length > 40)
                } else {
                    assertTrue(name, count > 40)
                }
            }
        }
    }

    @Test
    fun orderVectors() {
        for (group in groups) {
            for (row in vectors.rows(group)) {
                val label = "$group.${row.text("name")}"
                try {
                    match(run(group, row, label), row.getValue("expect"), label)
                } catch (failure: AssertionError) {
                    throw AssertionError("$label: ${failure.message}", failure)
                } catch (failure: Exception) {
                    throw AssertionError("$label: ${failure.message}", failure)
                }
            }
        }
    }

    private fun run(group: String, row: JsonObject, label: String): JsonObject =
        when (group) {
            "query" -> {
                var query: OrderQuery? = null
                val steps = row.rows("steps")
                assertTrue("$label.steps", steps.isNotEmpty())
                for (step in steps) {
                    val op = step.text("op")
                    if (op == "init") {
                        query = OrderQuery.initial(step.optionalText("value"))
                    } else {
                        val current = checkNotNull(query) { "$label: apply 前没有 init" }
                        current.apply(
                            op,
                            step.optionalText(if (op == "next_page") "nextCursor" else "value"),
                        )
                    }
                }
                val current = checkNotNull(query) { "$label: 没有 init" }
                val params = current.params()
                if (current.sendsRequest()) {
                    params.optionalText("q")?.let {
                        assertTrue("$label.q 超过 40 字", it.codePointCount(0, it.length) <= 40)
                    }
                }
                buildJsonObject {
                    put("params", params)
                    put("sendsRequest", current.sendsRequest())
                }
            }
            "monthOptions" ->
                OrderMonthOptions.of(row.optionalText("earliestVisibleDate"), nowMs(row))
            "orderNumber" -> OrderNumberDisplay.of(row.getValue("item").jsonObject)
            "titleImage" -> OrderTitleImage.of(row.getValue("item").jsonObject)
            "statusText" -> OrderStatusText.of(row.text("displayStatus"))
            "amountCells" ->
                OrderAmountCells.of(row.getValue("item").jsonObject, row.bool("masked", false))
            "creditPeriod" ->
                CreditPeriodText.of(
                    row.text("displayStatus"), row.optionalText("period"), nowMs(row),
                )
            "timeline" ->
                buildJsonObject {
                    put(
                        "rows",
                        JsonArray(
                            OrderTimelineView.of(
                                row.text("displayStatus"), row.rows("nodes"), nowMs(row),
                            ),
                        ),
                    )
                }
            "whyNoRebate" -> WhyNoRebate.of(row.getValue("detail").jsonObject)
            "priceCompareEntry" ->
                PriceCompareEntry.of(
                    row.getValue("detail").jsonObject,
                    row.optionalObject("helpLinks"),
                    Routes.all,
                )
            "detailActions" -> OrderDetailActions.of(row.getValue("detail").jsonObject)
            "viewProduct" -> ViewProductEntry.of(row.getValue("detail").jsonObject)
            "pendingTracks" ->
                buildJsonObject {
                    put(
                        "cards",
                        JsonArray(
                            PendingTrackCards.of(
                                row.rows("items"),
                                row.text("session"),
                                row.text("notification"),
                                row.getValue("requestedBefore").jsonPrimitive.boolean,
                            ),
                        ),
                    )
                }
            "pendingDismiss" -> {
                val result =
                    PendingTrackDismiss.onOutcome(row.getValue("outcome").jsonObject)
                buildJsonObject {
                    for ((key, value) in result) put(key, value)
                    put("request", PendingTrackDismiss.request(row.getValue("card").jsonObject))
                }
            }
            "errorAction" ->
                OrderErrorAction.map(
                    row.text("target"),
                    row["code"]?.jsonPrimitive?.int,
                    row.bool("transport", false),
                    row.optionalObject("headers")?.mapValues { (_, value) ->
                        check(value.jsonPrimitive.isString)
                        value.jsonPrimitive.content
                    } ?: emptyMap(),
                    row.optionalText("traceId"),
                    row.getValue("hasCache").jsonPrimitive.boolean,
                    row.bool("filtered", false),
                )
            "cachePolicy" ->
                buildJsonObject {
                    put(
                        "cacheable",
                        OrderStaleCache.isCacheable(row.text("kind"), row.optionalObject("query")),
                    )
                }
            "cacheClear" ->
                buildJsonObject {
                    put("clear", OrderStaleCache.clearsOn(row.text("event")))
                }
            else -> error("没有执行器：$group")
        }

    private fun match(
        actual: JsonElement?,
        expected: JsonElement,
        path: String,
        exact: Boolean = false,
    ) {
        when (expected) {
            JsonNull -> assertTrue("$path 应无值", actual == null || actual == JsonNull)
            is JsonObject -> {
                assertTrue("$path 应为对象", actual is JsonObject)
                val objectValue = actual as JsonObject
                if (exact) assertEquals("$path 键集合", expected.keys, objectValue.keys)
                for ((key, value) in expected) {
                    match(
                        objectValue[key],
                        value,
                        "$path.$key",
                        exact || key in setOf("params", "request", "buttons", "cards"),
                    )
                }
            }
            is JsonArray -> {
                assertTrue("$path 应为数组", actual is JsonArray)
                val arrayValue = actual as JsonArray
                assertEquals("$path 数量", expected.size, arrayValue.size)
                for (index in expected.indices) {
                    match(arrayValue[index], expected[index], "$path[$index]", exact)
                }
            }
            else -> {
                // JsonElement 保留 string/number/boolean 类型，不以文本强转掩盖错误。
                assertEquals(path, expected, actual)
            }
        }
    }

    private fun nowMs(row: JsonObject): Long {
        val iso = row.text("nowIso")
        assertTrue("nowIso 必须明确 +08:00", iso.endsWith("+08:00"))
        return OffsetDateTime.parse(iso).toInstant().toEpochMilli()
    }
}

private fun JsonObject.text(key: String): String {
    val value = getValue(key).jsonPrimitive
    check(value.isString) { "$key 应为 string" }
    return value.content
}

private fun JsonObject.optionalText(key: String): String? {
    val value = this[key] ?: return null
    if (value == JsonNull) return null
    check(value.jsonPrimitive.isString) { "$key 应为 string/null" }
    return value.jsonPrimitive.content
}

private fun JsonObject.optionalObject(key: String): JsonObject? {
    val value = this[key] ?: return null
    return if (value == JsonNull) null else value.jsonObject
}

private fun JsonObject.rows(key: String): List<JsonObject> =
    getValue(key).jsonArray.map { it.jsonObject }

private fun JsonObject.bool(key: String, defaultValue: Boolean): Boolean =
    this[key]?.jsonPrimitive?.boolean ?: defaultValue
