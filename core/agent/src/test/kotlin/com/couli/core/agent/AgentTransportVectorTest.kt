/*
 * 实现前测试；只读产出，尚未编译运行。
 *
 * JSON接线：
 * - JsonObject输入/结果，只转换类型并调用真实生产逻辑，不接收expect/name/axes。
 * - StreamUtf8Decoder.feedBytes(ByteArray): String；finish(): String；
 *   bufferedByteCount: Int。
 * - AgentSse.feedBytes(ByteArray)、finish(): List<JsonObject>；
 *   bufferedByteCount: Int；输出沿用kindRaw/event/id/dataText。
 * - AgentHistory.decode(response, Routes.all, nowMs: Long): JsonObject。
 * - 任务书类型AgentSeqGuard、AgentHistoryMerge、AgentReconnect、
 *   AgentOwnershipStop、AgentFeedback、AgentReport、AgentTransportErrorPolicy、
 *   AgentAvailability提供of(input: JsonObject, nowMs: Long): JsonObject。
 *   时间线驱动真实状态机并返回观察投影，不另写测试专用业务判定。
 * - §2未命名纯函数，均为of(input, nowMs):
 *   ApiStreamRoutingPolicy：ApiClient.stream(request,sink)实际调用的分流；
 *   ApiStreamRequestPolicy：实际流入口的请求、准入、重放；
 *   AgentTimeoutPolicy：StreamClient的计时；
 *   AgentStreamLimitPolicy：真实流的累计上限；
 *   AgentCancelPolicy：停止；AgentQuotaPolicy：30502按钮。
 * - ApiStream*Policy位于com.couli.core.network。
 * - AgentOwnershipStop决策由Core导出，身份cancel由应用级协调器执行。
 * - historyMerge的paging/entry接同一历史协调逻辑。
 * - fixtureFrames来自锁定duplicate fixture，不能按fixtureRaw名字返回结果。
 * - Core向量不代替:core:network的MockWebServer测试、真实页面接线、
 *   抓包与Release制品扫描；这些按brief §2.15执行。
 * - 测试层只展开数据、编码字节、注入时间，不实现业务策略。
 */

package com.couli.core.agent

import com.couli.core.network.ApiStreamRequestPolicy
import com.couli.core.network.ApiStreamRoutingPolicy
import com.couli.generated.Routes
import java.io.File
import java.time.OffsetDateTime
import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTransportVectorTest {
    private val groups = listOf(
        "sseBytes", "contentRouting", "timeouts", "seqGap", "history",
        "historyMerge", "reconnect", "cancel", "ownershipCancel",
        "jsonErrors", "quotaNext", "feedback", "report", "availability",
    )

    private data class VectorCase(
        val name: String,
        val input: JsonObject,
        val expected: JsonObject,
    )

    private val empty = JsonObject(emptyMap())

    private val root: File by lazy {
        val start = File(checkNotNull(System.getProperty("user.dir"))).canonicalFile
        generateSequence(start) { it.parentFile }
            .firstOrNull { File(it, "contract.lock").isFile }
            ?: error("从 $start 向上找不到 contract.lock")
    }

    private fun load(path: String): JsonElement =
        Json.parseToJsonElement(File(root, path).readText(Charsets.UTF_8))

    private val raw by lazy {
        load("specs/agent-transport-vectors.json").jsonObject
    }

    private val vectors by lazy {
        expand(raw, raw.getValue("assets").jsonObject).jsonObject
    }

    private fun JsonObject.text(key: String): String {
        val value = getValue(key).jsonPrimitive
        check(value.isString) { "$key 应为字符串" }
        return value.content
    }

    private fun merge(a: JsonObject, b: JsonObject): JsonObject = JsonObject(a + b)

    private fun expand(
        value: JsonElement,
        assets: JsonObject,
        depth: Int = 0,
    ): JsonElement {
        check(depth < 64) { "数据引用递归过深" }
        if (value is JsonArray) {
            return JsonArray(value.map { expand(it, assets, depth + 1) })
        }
        if (value !is JsonObject) return value

        value["\$ref"]?.let {
            val name = it.jsonPrimitive.content
            val resolved = expand(assets.getValue(name), assets, depth + 1)
            return value["with"]?.let { patch ->
                merge(resolved.jsonObject, expand(patch, assets, depth + 1).jsonObject)
            } ?: resolved
        }
        value["\$repeat"]?.let {
            val count = value.getValue("count").jsonPrimitive.int
            check(count in 0..100_000) { "非法重复次数" }
            return JsonPrimitive(it.jsonPrimitive.content.repeat(count))
        }
        value["\$concat"]?.let { parts ->
            return JsonPrimitive(parts.jsonArray.joinToString("") {
                val item = expand(it, assets, depth + 1).jsonPrimitive
                check(item.isString)
                item.content
            })
        }
        return JsonObject(value.mapValues { expand(it.value, assets, depth + 1) })
    }

    private fun cases(row: JsonObject): List<VectorCase> {
        var result = listOf(
            VectorCase(
                row.text("name"),
                row["input"]?.jsonObject ?: empty,
                row["expect"]?.jsonObject ?: empty,
            )
        )
        for (axisValue in row["axes"]?.jsonArray ?: JsonArray(emptyList())) {
            val axis = axisValue.jsonArray
            check(axis.isNotEmpty()) { "空参数轴" }
            result = result.flatMap { base ->
                axis.map { value ->
                    val variant = value.jsonObject
                    VectorCase(
                        "${base.name}/${variant.text("name")}",
                        merge(base.input, variant["input"]?.jsonObject ?: empty),
                        merge(base.expected, variant["expect"]?.jsonObject ?: empty),
                    )
                }
            }
        }
        return result
    }

    private fun nowMs(input: JsonObject): Long {
        val iso = input["nowIso"]?.jsonPrimitive?.content
            ?: vectors.getValue("conventions").jsonObject.text("nowIso")
        check(iso.endsWith("+08:00")) { "绝对时间必须带 +08:00" }
        return OffsetDateTime.parse(iso).toInstant().toEpochMilli()
    }

    private fun chunks(input: JsonObject): List<ByteArray> {
        val original = input.getValue("chunks").jsonArray.map {
            val chunk = it.jsonObject
            when (chunk.text("encodingRaw")) {
                "utf8" -> chunk.text("value").toByteArray(Charsets.UTF_8)
                "base64" -> Base64.getDecoder().decode(chunk.text("value"))
                else -> error("未知字节编码")
            }
        }
        val split = input["splitSizes"] ?: return original
        val sizes = split.jsonArray.map { it.jsonPrimitive.int }
        check(sizes.isNotEmpty() && sizes.all { it > 0 })
        val bytes = ByteArray(original.sumOf { it.size })
        var position = 0
        for (part in original) {
            part.copyInto(bytes, position)
            position += part.size
        }
        val result = mutableListOf<ByteArray>()
        var offset = 0
        var index = 0
        while (offset < bytes.size) {
            val end = minOf(bytes.size, offset + sizes[index % sizes.size])
            result += bytes.copyOfRange(offset, end)
            offset = end
            index += 1
        }
        return result
    }

    private fun sse(input: JsonObject, now: Long): JsonObject {
        if (input["modeRaw"]?.jsonPrimitive?.content == "limits") {
            return AgentStreamLimitPolicy.of(input, now)
        }
        val chunks = chunks(input)
        if (input["modeRaw"]?.jsonPrimitive?.content == "utf8") {
            val decoder = StreamUtf8Decoder()
            val emitted = chunks.map { decoder.feedBytes(it) }
            val text = emitted.joinToString("") + decoder.finish()
            return buildJsonObject {
                put("text", text)
                put("emitted", JsonArray(emitted.map(::JsonPrimitive)))
                put("bufferedBytes", decoder.bufferedByteCount)
            }
        }

        val parser = AgentSse()
        val items = mutableListOf<JsonObject>()
        for (chunk in chunks) items += parser.feedBytes(chunk)
        items += parser.finish()
        return buildJsonObject {
            put("items", JsonArray(items.map { item ->
                buildJsonObject {
                    put("kindRaw", item.getValue("kindRaw"))
                    item["event"]?.let { put("eventRaw", it) }
                    item["id"]?.let { put("id", it) }
                    item["dataText"]?.let {
                        put("dataText", it)
                        put(
                            "dataUtf8Bytes",
                            it.jsonPrimitive.content.toByteArray(Charsets.UTF_8).size,
                        )
                    }
                }
            }))
            put("bufferedBytes", parser.bufferedByteCount)
        }
    }

    private fun historyEnvelope(input: JsonObject): JsonObject {
        input["response"]?.let { return it.jsonObject }
        return buildJsonObject {
            put("code", 0)
            put("msg", "")
            put("data", buildJsonObject {
                put("items", input.getValue("items"))
                put("next_cursor", input["nextCursor"] ?: JsonNull)
                put("expired", input["expired"] ?: JsonPrimitive(false))
            })
            put("trace_id", "0199a3b4-5c6d-7e8f-9a0b-1c2d3e4f5a6b")
        }
    }

    private fun fixtureInput(input: JsonObject): JsonObject {
        val name = input["fixtureRaw"]?.jsonPrimitive?.content ?: return input
        check(name in setOf(
            "duplicate-ended-done",
            "duplicate-ended-error",
            "duplicate-in-progress",
        ))
        val file = File(
            root,
            "contracts/snapshot/contracts/fixtures/agent-streams/$name.ndjson",
        )
        val frames = file.readLines(Charsets.UTF_8)
            .filter { it.isNotEmpty() }
            .map { Json.parseToJsonElement(it).jsonObject }
        check(frames.size == 2) { "duplicate必须两帧" }
        return merge(input, buildJsonObject { put("fixtureFrames", JsonArray(frames)) })
    }

    private fun run(group: String, input: JsonObject): JsonObject {
        val now = nowMs(input)
        return when (group) {
            "sseBytes" -> sse(input, now)
            "contentRouting" ->
                if (input["modeRaw"]?.jsonPrimitive?.content in setOf("request", "replay")) {
                    ApiStreamRequestPolicy.of(input, now)
                } else {
                    ApiStreamRoutingPolicy.of(input, now)
                }
            "timeouts" -> AgentTimeoutPolicy.of(input, now)
            "seqGap" -> AgentSeqGuard.of(input, now)
            "history" -> AgentHistory.decode(historyEnvelope(input), Routes.all, now)
            "historyMerge" -> AgentHistoryMerge.of(input, now)
            "reconnect" -> AgentReconnect.of(fixtureInput(input), now)
            "cancel" -> AgentCancelPolicy.of(input, now)
            "ownershipCancel" -> AgentOwnershipStop.of(input, now)
            "jsonErrors" -> AgentTransportErrorPolicy.of(input, now)
            "quotaNext" -> AgentQuotaPolicy.of(input, now)
            "feedback" -> AgentFeedback.of(input, now)
            "report" -> AgentReport.of(input, now)
            "availability" -> AgentAvailability.of(input, now)
            else -> error("未接线组 $group")
        }
    }

    private fun match(
        actual: JsonElement?,
        expected: JsonElement,
        path: String,
        exact: Boolean = false,
    ) {
        checkNotNull(actual) { "$path：缺字段" }
        when (expected) {
            is JsonObject -> {
                val got = actual.jsonObject
                if (exact) assertEquals("$path.keys", expected.keys, got.keys)
                val exactKeys = setOf(
                    "requests", "bodies", "buttons", "gateWrites", "headers", "params",
                )
                for ((key, value) in expected) {
                    match(got[key], value, "$path.$key", exact || key in exactKeys)
                }
            }
            is JsonArray -> {
                val got = actual.jsonArray
                assertEquals("$path.length", expected.size, got.size)
                for (index in expected.indices) {
                    match(got[index], expected[index], "$path[$index]", exact)
                }
            }
            else -> assertEquals(path, expected, actual)
        }
    }

    private fun rejectHistoryAmounts(value: JsonElement, path: String) {
        val forbidden = setOf(
            "withdrawable_fen", "estimated_total_fen", "latest_withdrawal",
            "next_credit_period", "credit_overdue", "amount_fen",
            "withdrawable", "estimatedTotal", "appliedAmount",
        )
        when (value) {
            is JsonObject -> value.forEach { (key, child) ->
                assertFalse("$path.$key：历史保留了金额", key in forbidden)
                rejectHistoryAmounts(child, "$path.$key")
            }
            is JsonArray -> value.forEachIndexed { index, child ->
                rejectHistoryAmounts(child, "$path[$index]")
            }
            else -> Unit
        }
    }

    @Test
    fun manifestAndContractExamples() {
        assertEquals(1, vectors.getValue("version").jsonPrimitive.int)
        assertTrue(vectors.getValue("sources").jsonArray.isNotEmpty())
        assertTrue(vectors.getValue("gaps").jsonArray.isNotEmpty())
        val names = mutableSetOf<String>()
        for (group in groups) {
            val rows = vectors.getValue(group).jsonArray
            assertTrue(group, rows.isNotEmpty())
            for (row in rows) {
                for (case in cases(row.jsonObject)) {
                    val name = "$group/${case.name}"
                    assertTrue(name, case.expected.isNotEmpty())
                    assertTrue("重复测试名 $name", names.add(name))
                }
            }
        }

        val assets = vectors.getValue("assets").jsonObject
        val api = load("contracts/snapshot/contracts/openapi.json").jsonObject
        val path = api.getValue("paths").jsonObject
            .getValue("/v1/agent/sessions/{id}/messages").jsonObject

        fun examples(method: String, media: String): JsonObject =
            path.getValue(method).jsonObject
                .getValue("responses").jsonObject
                .getValue("200").jsonObject
                .getValue("content").jsonObject
                .getValue(media).jsonObject
                .getValue("examples").jsonObject

        val streams = examples("post", "text/event-stream")
        for (name in listOf("accepted", "duplicateEnded", "duplicateInProgress")) {
            val text = assets.text(name)
            assertEquals(name, streams.getValue(name).jsonObject.text("value"), text)
            assertTrue(name, text.endsWith("\n\n"))
        }
        val history = examples("get", "application/json")
        for (name in listOf("active", "expired")) {
            assertEquals(name, history.getValue(name).jsonObject.getValue("value"), assets[name])
        }
    }

    @Test
    fun allTransportVectors() {
        for (group in groups) {
            for (row in vectors.getValue(group).jsonArray) {
                for (case in cases(row.jsonObject)) {
                    val label = "$group/${case.name}"
                    try {
                        val actual = run(group, case.input)
                        match(actual, case.expected, label)
                        if (group == "history") rejectHistoryAmounts(actual, label)
                    } catch (error: Throwable) {
                        throw AssertionError(label, error)
                    }
                }
            }
        }
    }
}
