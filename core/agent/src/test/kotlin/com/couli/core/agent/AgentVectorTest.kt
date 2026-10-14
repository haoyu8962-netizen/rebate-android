/*
 * 实现前测试；只读输出，未编译运行。
 *
 * 接线约定：
 * - 生产类型位于 com.couli.core.agent；JUnit4 + kotlinx.serialization.json。
 * - JSON 对象参数/结果为 JsonObject，对象数组为 List<JsonObject>；
 *   强类型实现可提供同名 JSON 重载，只转换类型，不计算测试期望。
 * - AgentSse()；feed(chunk)、finish() -> List<JsonObject>；
 *   AgentSse.fromNdjsonLine(line) -> JsonObject。
 *   SseItem.id 为字符串；FrameDecoder 的 id 为整数。
 * - AgentFrameDecoder.decode(item) -> JsonObject，含 resultRaw/event/id/data；
 *   ok 保留原 data；合法卡外壳中的非法内层留给卡解码回退。
 * - AgentCardDecoder.decode(card, origin, Routes.all) -> JsonObject。
 * - StreamReducer.initial() -> State；reduce(state, input: JsonObject) -> State；
 *   normalize(state) -> JsonObject。input：
 *   {kindRaw:"frame",frame:decoded}、{kindRaw:"ping|eof|timeout|localCancel"}、
 *   {kindRaw:"historyCard",card:完整卡外壳}。
 * - AgentText.sanitize(text): String；blocks(sanitizedText): List<JsonObject>。
 * - PageGuidePolicy.tap(card, Routes.all) -> JsonObject。
 * - EarningsCardView.of(card, origin, amountMasked, nowMs: Long) -> JsonObject。
 *   nowIso 必须带 +08:00；金额用已有格式化函数；不展示的金额字段省略。
 * - AgentTicketPolicy.onEvent(eventRaw) -> JsonObject；
 *   accept(issued: JsonObject, current: JsonObject?): Boolean。
 * - AgentSendErrorPolicy.of(http, code, data: JsonObject?, retryAfter: String?,
 *   traceId: String?, consentType: String?) -> JsonObject。
 * - AgentStreamErrorPolicy.of(errorFrameData) -> JsonObject；
 *   AgentDuplicatePolicy.of(terminalFrame) -> JsonObject。
 * - ClientMsgId.new(): String；forRetry(situationRaw, previous): String。
 * - AgentConsentPolicy.of(http, code, consentType: String?) -> JsonObject。
 * - user.dir 向上找 contract.lock，读取 specs/agent-vectors.json；
 *   fixture：contracts/snapshot/contracts/fixtures/agent-streams/<name>.ndjson。
 * - streams.fixture 与 lines 恰选一项；fixture 无扩展名。行号从1起且计 ping，
 *   insertAfterLine 在原始行后插入，extraInputs 在末尾追加；
 *   history 来源只转 historyCard，不补 meta/done。
 * - streams 终态精确比较；其他 expect 缺字段不判定，显式 null 接受缺失/JsonNull；
 *   数组有序等长，params/buttons 精确比较。ndjson.dataText 按 JSON 内容比较，
 *   sse.dataText 逐字比较。不在测试层实现判序、卡片校验、错误映射。
 * - 不访问网络、假服务、持久化、真实 Router；执行层边界见 vectors.gaps。
 */

package com.couli.core.agent

import com.couli.generated.Routes
import java.io.File
import java.time.OffsetDateTime
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentVectorTest {
    private val groups = listOf(
        "sse", "ndjson", "frames", "cards", "streams", "text",
        "pageGuideTap", "earningsView", "ticket", "ticketCompare",
        "sendErrors", "streamErrors", "duplicate", "consent",
    )
    private val root: File by lazy {
        val start = File(System.getProperty("user.dir") ?: error("缺少 user.dir")).canonicalFile
        generateSequence(start) { it.parentFile }
            .firstOrNull { File(it, "contract.lock").isFile }
            ?: error("从 $start 向上找不到 contract.lock")
    }
    private val fixtureDir by lazy {
        File(root, "contracts/snapshot/contracts/fixtures/agent-streams")
    }
    private val vectors by lazy {
        Json.parseToJsonElement(File(root, "specs/agent-vectors.json").readText(Charsets.UTF_8)).jsonObject
    }

    @Test
    fun manifestAndFixtureInventory() {
        assertEquals(1, vectors.number("version"))
        assertTrue(vectors.getValue("sources").jsonArray.isNotEmpty())
        assertTrue(vectors.getValue("gaps").jsonArray.isNotEmpty())
        for (group in groups) {
            val rows = vectors.rows(group)
            val names = rows.map { it.text("name") }
            assertTrue(group, rows.isNotEmpty())
            assertTrue(group, names.all { it.isNotBlank() })
            assertEquals(group, names.size, names.toSet().size)
        }
        val actual = checkNotNull(fixtureDir.listFiles()) { "缺少 fixture 目录" }
            .filter { it.isFile && it.extension == "ndjson" }.map { it.name }.toSet()
        val referenced = vectors.rows("streams")
            .mapNotNull { it.optionalText("fixture") }.map { "$it.ndjson" }.toSet()
        assertEquals(15, actual.size)
        assertEquals(15, referenced.size)
        assertEquals("快照与 streams 引用必须完全一致", actual, referenced)
        vectors.rows("streams").forEach { streamLines(it) }

        assertEquals(
            setOf("OrderList", "Wallet", "WithdrawRecords", "Messages", "InviteShare", "AuthManage"),
            Routes.all.filterValues { it.agentGuide }.keys,
        )
        assertEquals(
            setOf("BindPhone", "Withdraw", "RealName", "PayoutAccount", "LaborAgreement", "DeleteAccount"),
            Routes.all.filterValues { it.agentGuideAccount != null }.keys,
        )
        val long = vectors.rows("streamErrors").single { it.text("name").contains("超100字") }
            .obj("frame").obj("data").text("fallback_q")
        assertTrue(long.codePointCount(0, long.length) > 100)
    }

    @Test
    fun allVectors() {
        for (group in groups) {
            for (row in vectors.rows(group)) {
                val label = "$group.${row.text("name")}"
                try {
                    val actual = run(group, row)
                    val expected = row.getValue("expect")
                    if (group == "ndjson") {
                        match(semanticItem(actual.jsonObject), semanticItem(expected.jsonObject), label)
                    } else {
                        match(actual, expected, label, exact = group == "streams")
                    }
                } catch (failure: Throwable) {
                    throw AssertionError("$label: ${failure.message}", failure)
                }
            }
        }
    }

    @Test
    fun clientMessageIds() {
        val spec = vectors.obj("clientMsgId")
        val pattern = Regex(spec.text("pattern"))
        val issued = mutableSetOf<String>()
        repeat(64) {
            val id = ClientMsgId.new()
            assertTrue(id, pattern.matches(id))
            assertTrue("new() 不得复用", issued.add(id))
        }
        val previous = "Previous_01234567"
        for (row in spec.rows("cases")) {
            val result = ClientMsgId.forRetry(row.text("situationRaw"), previous)
            assertTrue(row.text("name"), pattern.matches(result))
            assertEquals(
                row.text("name"), row.obj("expect").bool("sameAsPrevious"), result == previous,
            )
        }
    }

    private fun run(group: String, row: JsonObject): JsonElement = when (group) {
        "sse" -> {
            val parser = AgentSse()
            val result = mutableListOf<JsonObject>()
            row.strings("chunks").forEach { result.addAll(parser.feed(it)) }
            result.addAll(parser.finish())
            JsonArray(result)
        }
        "ndjson" -> AgentSse.fromNdjsonLine(row.text("line"))
        "frames" -> {
            val raw = row.getValue("frame")
            val line = if (raw is JsonPrimitive && raw.isString) raw.content else raw.toString()
            val result = AgentFrameDecoder.decode(AgentSse.fromNdjsonLine(line))
            if (row.obj("expect").text("resultRaw") == "ok") {
                assertEquals(raw.jsonObject.getValue("id"), result["id"])
                assertEquals(raw.jsonObject.getValue("data"), result["data"])
            }
            result
        }
        "cards" -> AgentCardDecoder.decode(row.obj("card"), row.text("origin"), Routes.all)
        "streams" -> reduceStream(row)
        "text" -> {
            val sanitized = AgentText.sanitize(row.text("input"))
            buildJsonObject {
                put("sanitized", sanitized)
                put("blocks", JsonArray(AgentText.blocks(sanitized)))
            }
        }
        "pageGuideTap" -> PageGuidePolicy.tap(row.obj("card"), Routes.all)
        "earningsView" -> {
            val iso = row.text("nowIso")
            require(iso.endsWith("+08:00"))
            val nowMs = OffsetDateTime.parse(iso).toInstant().toEpochMilli()
            val result = EarningsCardView.of(row.obj("card"), row.text("origin"), row.bool("amountMasked"), nowMs)
            val expected = row.obj("expect")
            if (expected.text("shapeRaw") != "live") {
                for (key in listOf(
                    "withdrawable", "estimatedTotal", "appliedAmount",
                    "withdrawable_fen", "estimated_total_fen", "latest_withdrawal",
                )) {
                    assertFalse("${row.text("name")}: 禁止金额字段 $key", result.containsKey(key))
                }
            }
            if (!expected.bool("latestWithdrawalShown")) {
                assertFalse(result.containsKey("appliedAmount"))
                assertFalse(result.containsKey("latestTitleKey"))
            }
            result
        }
        "ticket" -> AgentTicketPolicy.onEvent(row.text("eventRaw"))
        "ticketCompare" -> buildJsonObject {
            put("accept", AgentTicketPolicy.accept(row.obj("issued"), row.optionalObject("current")))
        }
        "sendErrors" -> AgentSendErrorPolicy.of(
            row.number("http"), row.number("code"), row.optionalObject("data"),
            row.optionalText("retryAfter"), row.optionalText("traceId"), row.optionalText("consentType"),
        )
        "streamErrors" -> AgentStreamErrorPolicy.of(row.obj("frame").obj("data"))
        "duplicate" -> {
            val fixture = row.text("fixture")
            val terminal = Json.parseToJsonElement(fixtureLines(fixture).last()).jsonObject
            val state = reduceStream(buildJsonObject { put("fixture", fixture) })
            assertTrue(state.bool("duplicate"))
            match(state["quotaLeft"], JsonNull, "duplicate.quotaLeft")
            JsonObject(
                AgentDuplicatePolicy.of(terminal) + mapOf(
                    "mergeIntoMessage" to state.getValue("mergeIntoMessage"),
                    "updatesQuota" to state.getValue("updatesQuota"),
                ),
            )
        }
        "consent" -> AgentConsentPolicy.of(
            row.number("http"), row.number("code"), row.optionalText("consentType"),
        )
        else -> error("未接线组 $group")
    }

    private fun reduceStream(row: JsonObject): JsonObject {
        val lines = streamLines(row)
        val inserts = row.optionalObject("insertAfterLine") ?: JsonObject(emptyMap())
        inserts.keys.forEach { require(it.toIntOrNull() in 1..lines.size) }
        var state = StreamReducer.initial()
        fun inject(raw: String) {
            require(raw in listOf("eof", "timeout", "local_cancel"))
            state = StreamReducer.reduce(state, input(if (raw == "local_cancel") "localCancel" else raw))
        }
        lines.forEachIndexed { index, line ->
            val item = AgentSse.fromNdjsonLine(line)
            if (row.optionalText("origin") == "history") {
                val decoded = AgentFrameDecoder.decode(item)
                assertEquals("ok", decoded.text("resultRaw"))
                assertEquals("card", decoded.text("event"))
                state = StreamReducer.reduce(state, input("historyCard", "card", decoded.getValue("data")))
            } else if (item.text("kindRaw") == "ping") {
                state = StreamReducer.reduce(state, input("ping"))
            } else {
                // 不在测试层过滤 unknown_event 或 invalid_frame。
                state = StreamReducer.reduce(state, input("frame", "frame", AgentFrameDecoder.decode(item)))
            }
            inserts[(index + 1).toString()]?.jsonArray?.forEach { inject(it.jsonPrimitive.content) }
        }
        row["extraInputs"]?.jsonArray?.forEach { inject(it.jsonPrimitive.content) }
        return StreamReducer.normalize(state)
    }

    private fun input(kind: String, key: String? = null, value: JsonElement? = null) =
        buildJsonObject {
            put("kindRaw", kind)
            if (key != null) put(key, requireNotNull(value))
        }

    private fun streamLines(row: JsonObject): List<String> {
        require(row.containsKey("fixture") xor row.containsKey("lines")) {
            "fixture 与 lines 必须二选一"
        }
        val lines = row.optionalText("fixture")?.let { fixtureLines(it) } ?: row.strings("lines")
        require(lines.isNotEmpty() && lines.none { it.isEmpty() })
        return lines
    }

    private fun fixtureLines(name: String): List<String> {
        require(name.isNotEmpty() && '/' !in name && '.' !in name)
        val lines = File(fixtureDir, "$name.ndjson").readText(Charsets.UTF_8)
            .replace("\r\n", "\n").split('\n')
        return if (lines.lastOrNull() == "") lines.dropLast(1) else lines
    }

    private fun semanticItem(item: JsonObject): JsonObject {
        val text = item.optionalText("dataText") ?: return item
        return JsonObject(item + ("dataText" to Json.parseToJsonElement(text)))
    }

    private fun match(actual: JsonElement?, expected: JsonElement, path: String, exact: Boolean = false) {
        when (expected) {
            JsonNull -> assertTrue(path, actual == null || actual == JsonNull)
            is JsonObject -> {
                assertTrue("$path 应为对象", actual is JsonObject)
                val obj = actual!!.jsonObject
                if (exact) assertEquals("$path.keys", expected.keys, obj.keys)
                expected.forEach { (key, value) ->
                    match(obj[key], value, "$path.$key", exact || key == "params" || key == "buttons")
                }
            }
            is JsonArray -> {
                assertTrue("$path 应为数组", actual is JsonArray)
                val array = actual!!.jsonArray
                assertEquals("$path.size", expected.size, array.size)
                expected.indices.forEach { match(array[it], expected[it], "$path[$it]", exact) }
            }
            else -> assertEquals(path, expected, actual)
        }
    }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.let {
        require(it.isString) { "$key 应为字符串" }
        it.content
    }
    private fun JsonObject.optionalText(key: String): String? =
        if (this[key] == null || this[key] == JsonNull) null else text(key)
    private fun JsonObject.obj(key: String) = getValue(key).jsonObject
    private fun JsonObject.optionalObject(key: String): JsonObject? =
        if (this[key] == null || this[key] == JsonNull) null else obj(key)
    private fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.int
    private fun JsonObject.bool(key: String) = getValue(key).jsonPrimitive.boolean
    private fun JsonObject.rows(key: String) = getValue(key).jsonArray.map { it.jsonObject }
    private fun JsonObject.strings(key: String) = getValue(key).jsonArray.map {
        require(it.jsonPrimitive.isString)
        it.jsonPrimitive.content
    }
}
