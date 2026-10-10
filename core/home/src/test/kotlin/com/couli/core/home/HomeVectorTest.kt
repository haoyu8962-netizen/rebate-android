/*
 * 实现前测试；只读输出，未编译运行。
 *
 * 接线约定：
 * - 生产类型位于 com.couli.core.home；实现方新增 :core:home。
 * - 使用已有 JUnit4、kotlinx.serialization.json，不加依赖。
 * - JSON 对象参数/结果为 JsonObject，通用值为 JsonElement?，
 *   对象数组为 List<JsonObject>。生产强类型模型可提供同名 JSON 接线；
 *   接线只转换类型，不得计算或替换测试期望。
 * - HomeJson.decode(pageJson, platform: String, appVersion: String): JsonObject；
 *   kept 每项含 id/type/itemCount，其余字段照题定 API。
 * - HomeSectionFilter.keep(minAppVersionJsonOrNull: JsonElement?, absent: Boolean,
 *   platform: String, appVersion: String): Boolean。
 * - HomeSourcePolicy()；on(event: String, arg: JsonObject): JsonObject。
 * - HomeLkgScope.of(level: String, userId: String?, env: String): String。
 * - HomeLocalData.clearsOn(event: String, item: String): Boolean。
 * - NoticeFilter.visible(sectionId: String, itemsJson: List<JsonObject>,
 *   closedKeys: List<String>, nowMs: Long): JsonObject；
 *   closeKey(sectionId: String, itemId: String, contentVersion: Int): String。
 * - PopupPolicy.decide(popupJson, level: String, nowMs: Long, shownToday: Int,
 *   coldStartShown: Boolean, blockers: List<String>, imageLoaded: Boolean): JsonObject。
 *   popupJson 是完整 section。
 * - RouteTargetCheck.decode(json: JsonObject?): JsonObject?。
 * - HomeSpm.of(sectionId: String, slot: Int): String。
 * - HomeAdLabel.of(sectionLabel: JsonElement?, itemLabel: JsonElement?): String?；
 *   "absent" 转 Kotlin null，显式 JSON null 保留 JsonNull。
 * - ExposureTracker(sectionId: String, slot: Int)；
 *   onVisible(tMs: Long, ratio: Double, stay: Int): Boolean。
 * - CardErrorDedupe()；shouldSend(pageVersion: Int, sectionId: String,
 *   reason: String): Boolean。
 * - HomePreviewPolicy.of(routeParams: JsonObject, result: JsonObject): JsonObject；
 *   返回 screenRaw/pageKey/token 和五个副作用布尔值。
 * - page 为字符串时取 goldenPages[该键]，否则直接传对象。
 * - 时间必须带 +08:00，转换绝对毫秒；不读系统时钟。
 *   版本比较复用 UpdateGate.compareOrNull；时间文案沿用 CouliFormat。
 * - 每端运行所有 platformRaw 行。每行独立策略对象；
 *   exposure.stay 缺省沿用前一步，初值 0；sentIndexes 从 0 起。
 * - expect 未写字段不判定；显式 null 接受缺字段/JsonNull。
 *   数组有序且等长；params/closable 精确比较；actionsRaw 比较多重集合。
 * - scope/closeKey 只比相等关系，不锁定代拟分隔符；
 *   notice.closedKeys 通过 noticeCloseKey 向量映射为实际键，再作为输入。
 * - G14 kept 投影只含普通卡与 popupId 指定的 popup；其余合法 popup
 *   是否仍存于 kept 未定，不断言；popupId 精确断言。
 * - G24 模拟 Router 丢弃多余参数，仅传 page_key/token。
 * - 不访问网络、权限或本机存储；执行层与制品覆盖限制见 vectors.gaps。
 */

package com.couli.core.home

import java.io.File
import java.time.OffsetDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeVectorTest {
    private val groups = listOf(
        "pageDecode", "minAppVersion", "sourcePolicy", "lkgScope",
        "localDataClear", "notice", "noticeCloseKey", "popup",
        "action", "spm", "adLabel", "exposure", "cardErrorDedupe", "preview",
    )

    private val vectors: JsonObject by lazy {
        val start = File(
            System.getProperty("user.dir") ?: error("缺少 user.dir"),
        ).canonicalFile
        val root = generateSequence(start) { it.parentFile }
            .firstOrNull { File(it, "contract.lock").isFile }
            ?: error("从 $start 向上找不到 contract.lock")
        Json.parseToJsonElement(
            File(root, "specs/home-sdui-vectors.json").readText(Charsets.UTF_8),
        ).jsonObject
    }

    @Test
    fun manifest() {
        assertEquals(1, vectors.number("version"))
        assertTrue(vectors.getValue("sources").jsonArray.isNotEmpty())
        assertTrue(vectors.getValue("gaps").jsonArray.isNotEmpty())
        for (group in groups) {
            val rows = vectors.rows(group)
            assertTrue("$group 不能为空", rows.isNotEmpty())
            val names = rows.map { it.text("name") }
            assertTrue("$group 名称不能为空", names.all { it.isNotEmpty() })
            assertEquals("$group 重复 name", names.size, names.toSet().size)
            for (row in rows) {
                if (group == "sourcePolicy") {
                    assertTrue(row.rows("steps").isNotEmpty())
                    row.rows("steps").forEach { it.getValue("expect").jsonObject }
                } else {
                    row.getValue("expect").jsonObject
                }
            }
        }
        val golden = vectors.getValue("goldenPages").jsonObject
        val references = vectors.rows("pageDecode").mapNotNull {
            (it["page"] as? JsonPrimitive)?.takeIf { value -> value.isString }?.content
        }.toSet()
        assertTrue(golden.isNotEmpty())
        assertEquals("每个 golden 都必须进入解码测试", golden.keys, references)
    }

    @Test
    fun vectorCases() {
        for (group in groups.filterNot { it == "lkgScope" || it == "noticeCloseKey" }) {
            for (row in vectors.rows(group)) {
                val label = "$group.${row.text("name")}"
                try {
                    run(group, row, label)
                } catch (failure: AssertionError) {
                    throw AssertionError(label, failure)
                } catch (failure: Exception) {
                    throw AssertionError(label, failure)
                }
            }
        }
    }

    @Test
    fun scopeRelations() = checkRelations("lkgScope", "scope")

    @Test
    fun noticeKeyRelations() = checkRelations("noticeCloseKey", "key")

    private fun run(group: String, row: JsonObject, label: String) {
        if (group == "sourcePolicy") {
            val policy = HomeSourcePolicy()
            for ((index, step) in row.rows("steps").withIndex()) {
                val actual = policy.on(
                    step.text("event"),
                    step["arg"]?.jsonObject ?: JsonObject(emptyMap()),
                )
                val expected = step.getValue("expect").jsonObject
                val path = "$label[$index]"
                assertEquals(
                    "$path.actionsRaw",
                    expected.strings("actionsRaw").sorted(),
                    actual.strings("actionsRaw").sorted(),
                )
                match(
                    actual,
                    JsonObject(expected.filterKeys { it != "actionsRaw" }),
                    path,
                )
            }
            return
        }

        val expected = row.getValue("expect").jsonObject
        when (group) {
            "pageDecode" -> {
                val input = row.getValue("page")
                val page = if (input is JsonPrimitive && input.isString) {
                    vectors.getValue("goldenPages").jsonObject.getValue(input.content).jsonObject
                } else {
                    input.jsonObject
                }
                val actual = HomeJson.decode(
                    page, row.text("platformRaw"), row.text("appVersion"),
                )
                val kept = actual.rows("kept")
                val popupId = actual.optionalText("popupId")
                val projected = kept.filter {
                    it.text("type") != "popup" || it.text("id") == popupId
                }.map {
                    buildJsonObject {
                        put("id", it.getValue("id"))
                        put("type", it.getValue("type"))
                    }
                }
                val observed = actual.toMutableMap()
                observed["kept"] = JsonArray(projected)
                expected["itemCounts"]?.jsonObject?.let { counts ->
                    observed["itemCounts"] = buildJsonObject {
                        for (id in counts.keys) {
                            val card = kept.firstOrNull { it.text("id") == id }
                                ?: error("$label.itemCounts 找不到 $id")
                            put(id, card.getValue("itemCount"))
                        }
                    }
                }
                match(JsonObject(observed), expected, label)
            }

            "minAppVersion" -> {
                val value = row.getValue("minAppVersion")
                val absent = value == JsonPrimitive("absent")
                val actual = HomeSectionFilter.keep(
                    if (absent) null else value,
                    absent,
                    row.text("platformRaw"),
                    row.text("appVersion"),
                )
                match(buildJsonObject { put("keep", actual) }, expected, label)
            }

            "localDataClear" -> match(
                buildJsonObject {
                    put("clear", HomeLocalData.clearsOn(row.text("event"), row.text("item")))
                },
                expected,
                label,
            )

            "notice" -> {
                val actual = NoticeFilter.visible(
                    row.text("sectionId"),
                    row.rows("items"),
                    row.strings("closedKeys").map { mappedCloseKey(it) },
                    milliseconds(row.text("nowIso")),
                )
                val wanted = expected.toMutableMap()
                wanted.remove("nextExpiryIso")
                wanted["nextExpiryMs"] = expected.optionalText("nextExpiryIso")
                    ?.let { JsonPrimitive(milliseconds(it)) } ?: JsonNull
                match(actual, JsonObject(wanted), label)
            }

            "popup" -> match(
                PopupPolicy.decide(
                    row.getValue("popup").jsonObject,
                    row.text("levelRaw"),
                    milliseconds(row.text("nowIso")),
                    row.number("shownToday"),
                    row.flag("coldStartShown"),
                    row.strings("blockers"),
                    row.flag("imageLoaded"),
                ),
                expected,
                label,
            )

            "action" -> {
                val input = row.getValue("json")
                val actual = RouteTargetCheck.decode(
                    if (input == JsonNull) null else input.jsonObject,
                )
                val ok = expected.flag("ok")
                assertEquals("$label.ok", ok, actual != null)
                if (ok) {
                    val target = requireNotNull(actual)
                    assertTrue(
                        "$label 不得输出脚本或其他动作字段",
                        target.keys.all { it == "route" || it == "params" },
                    )
                    match(
                        target,
                        JsonObject(expected.filterKeys { it != "ok" }),
                        label,
                        exact = expected.containsKey("params"),
                    )
                }
            }

            "spm" -> match(
                buildJsonObject {
                    put("spm", HomeSpm.of(row.text("sectionId"), row.number("slot")))
                },
                expected,
                label,
            )

            "adLabel" -> {
                val actual = HomeAdLabel.of(
                    labelInput(row.getValue("sectionAdLabel")),
                    labelInput(row.getValue("itemAdLabel")),
                )
                match(
                    buildJsonObject { put("label", actual?.let { JsonPrimitive(it) } ?: JsonNull) },
                    expected,
                    label,
                )
            }

            "exposure" -> {
                val tracker = ExposureTracker(row.text("sectionId"), row.number("slot"))
                val fires = mutableListOf<Long>()
                var stay = 0
                for (step in row.rows("steps")) {
                    step["stay"]?.let { stay = it.jsonPrimitive.int }
                    val time = step.getValue("tMs").jsonPrimitive.long
                    if (tracker.onVisible(
                            time,
                            step.getValue("visibleRatio").jsonPrimitive.double,
                            stay,
                        )
                    ) {
                        fires.add(time)
                    }
                }
                match(
                    buildJsonObject {
                        put("fireAtMs", JsonArray(fires.map { JsonPrimitive(it) }))
                    },
                    expected,
                    label,
                )
            }

            "cardErrorDedupe" -> {
                val dedupe = CardErrorDedupe()
                val sent = mutableListOf<Int>()
                for ((index, event) in row.rows("events").withIndex()) {
                    if (dedupe.shouldSend(
                            event.number("pageVersion"),
                            event.text("sectionId"),
                            event.text("reasonRaw"),
                        )
                    ) {
                        sent.add(index)
                    }
                }
                match(
                    buildJsonObject {
                        put("sentIndexes", JsonArray(sent.map { JsonPrimitive(it) }))
                    },
                    expected,
                    label,
                )
            }

            "preview" -> {
                val raw = row.getValue("routeParams").jsonObject
                val pageKey = raw.text("page_key")
                val token = raw.text("token")
                val routed = buildJsonObject {
                    put("page_key", pageKey)
                    put("token", token)
                }
                val actual = HomePreviewPolicy.of(
                    routed, row.getValue("result").jsonObject,
                )
                val wanted = expected.toMutableMap()
                wanted["pageKey"] = JsonPrimitive(pageKey)
                wanted["token"] = JsonPrimitive(token)
                match(actual, JsonObject(wanted), label)
            }

            else -> error("$label 未登记执行器")
        }
    }

    private fun relationValue(row: JsonObject, group: String): String =
        if (group == "lkgScope") {
            HomeLkgScope.of(
                row.text("levelRaw"), row.optionalText("userId"), row.text("envRaw"),
            )
        } else {
            NoticeFilter.closeKey(
                row.text("sectionId"), row.text("itemId"), row.number("contentVersion"),
            )
        }

    private fun checkRelations(group: String, key: String) {
        val rows = vectors.rows(group)
        val actual = rows.map { relationValue(it, group) }
        val expected = rows.map { it.getValue("expect").jsonObject.text(key) }
        for (i in rows.indices) {
            assertTrue("$group[$i] 不得为空", actual[i].isNotEmpty())
            for (j in rows.indices) {
                assertEquals(
                    "$group[$i,$j]",
                    expected[i] == expected[j],
                    actual[i] == actual[j],
                )
            }
        }
    }

    private fun mappedCloseKey(fixtureKey: String): String {
        val row = vectors.rows("noticeCloseKey").firstOrNull {
            it.getValue("expect").jsonObject.text("key") == fixtureKey
        } ?: error("关闭键未在 noticeCloseKey 定义：$fixtureKey")
        return relationValue(row, "noticeCloseKey")
    }

    private fun labelInput(value: JsonElement): JsonElement? =
        if (value == JsonPrimitive("absent")) null else value

    private fun milliseconds(iso: String): Long {
        require(iso.endsWith("+08:00")) { "时刻必须带 +08:00" }
        return OffsetDateTime.parse(iso).toInstant().toEpochMilli()
    }

    private fun match(
        actual: JsonElement?,
        expected: JsonElement,
        path: String,
        exact: Boolean = false,
    ) {
        when (expected) {
            JsonNull -> assertTrue("$path 应无值", actual == null || actual == JsonNull)
            is JsonArray -> {
                val observed = actual as? JsonArray ?: error("$path 应为数组")
                assertEquals("$path.length", expected.size, observed.size)
                expected.indices.forEach { i ->
                    match(observed[i], expected[i], "$path[$i]", exact)
                }
            }
            is JsonObject -> {
                val observed = actual as? JsonObject ?: error("$path 应为对象")
                if (exact) assertEquals("$path.keys", expected.keys, observed.keys)
                for ((key, value) in expected) {
                    match(
                        observed[key],
                        value,
                        "$path.$key",
                        exact || key == "params" || key == "closable",
                    )
                }
            }
            else -> assertEquals(path, expected, actual)
        }
    }
}

private fun JsonObject.text(key: String): String {
    val value = getValue(key).jsonPrimitive
    check(value.isString) { "$key 应为字符串" }
    return value.content
}

private fun JsonObject.optionalText(key: String): String? {
    val value = this[key] ?: return null
    return if (value == JsonNull) null else text(key)
}

private fun JsonObject.number(key: String): Int = getValue(key).jsonPrimitive.int
private fun JsonObject.flag(key: String): Boolean = getValue(key).jsonPrimitive.boolean
private fun JsonObject.rows(key: String): List<JsonObject> =
    getValue(key).jsonArray.map { it.jsonObject }

private fun JsonObject.strings(key: String): List<String> =
    getValue(key).jsonArray.map {
        check(it.jsonPrimitive.isString) { "$key 元素应为字符串" }
        it.jsonPrimitive.content
    }
