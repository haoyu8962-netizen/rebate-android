/*
 * 实现前测试；只读输出，未编译运行。
 *
 * 接线约定：以下类型位于 com.couli.core.clipboard。
 * - ClipboardReadPolicy.decide(platform: String, state: JsonObject)
 *   -> { actionRaw: String, reasonRaw: String? }。
 * - InviteCodeExtractor.extract(text: String): String?。
 * - ClipboardRules.defaults。
 * - ClipboardClassifier.classify(text: String, context: ClipboardContext,
 *   rules: ClipboardRules, nowMs: Long)
 *   -> { kindRaw: String, reasonRaw: String?, inviteCode: String?,
 *        uploadText: String? }。
 * - ClipboardContext(selfWritten: List<String>,
 *   prompted: List<ClipboardPromptStamp>, inviteEligible: Boolean,
 *   selfInviteCode: String?, shareDomains: List<String>,
 *   hash: (String) -> String)。
 *   selfWritten 为 hash 列表；ClipboardPromptStamp(hash: String, atMs: Long)。
 *   这些是纯逻辑 DTO，不是新增服务端字段。
 * - ClipboardWriteLedger(capacity: Int = 10, hash: (String) -> String)；
 *   record(text: String)；contains(text: String): Boolean。
 *   hash 为题定注入依赖，不使用系统剪贴板或持久化。
 * - ParseResultAction.map(response: JsonObject?, errorCode: Int?,
 *   retryAfter: String?, traceId: String?)
 *   -> { actionRaw: String, linkId: String?, prefill: String?,
 *        textKey: String?, showTrace6: Boolean?, disableMs: Long? }。
 *   response 原样传 ParseInputResponse 外壳，errorCode 为业务码。
 * - ManualPasteAction.decide(entry: String, text: String?, rules: ClipboardRules)
 *   -> { actionRaw: String, uploadText: String?, prefill: String?, code: String? }。
 * - InviteConfirmAction.decide(session: String, me: JsonObject?)
 *   -> { actionRaw: String }；me 为题定邀请状态投影。
 * - expect 缺字段不判定，显式 null 必须无值。
 * - neutralState 只补向量规定的中性前提，不测试生产配置默认值；
 *   显式值覆盖补值，iosDetectHit 缺失保持缺失。
 * - 所有平台行都执行；每行独立 hash/ledger；nowMs 固定为毫秒。
 *   prompted.agoMs 转成 nowMs-agoMs，测试不实现业务判定。
 * - 模块复用仓库已有 JUnit4、kotlinx.serialization.json 测试依赖。
 *   不用真实网络、系统剪贴板、Robolectric 或 Thread.sleep。
 * - gaps 中的执行层验收不由这些纯函数测试代替。
 */

package com.couli.core.clipboard

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardVectorTest {
    private val nowMs = 1_800_000_000_000L

    private val groups =
        listOf(
            "readPolicy",
            "inviteExtract",
            "classify",
            "writeLedger",
            "parseResult",
            "manualPaste",
            "inviteConfirm",
        )

    private class VectorHasher {
        private val values = mutableMapOf<String, String>()

        fun digest(text: String): String = values.getOrPut(text) { "fixture-digest-${values.size + 1}" }
    }

    private val vectors: JsonObject by lazy {
        val start =
            File(
                System.getProperty("user.dir")
                    ?: throw AssertionError("找不到 user.dir"),
            ).canonicalFile
        val root =
            generateSequence(start) { it.parentFile }
                .firstOrNull { File(it, "contract.lock").isFile }
                ?: throw AssertionError("从 $start 向上找不到 contract.lock")

        Json
            .parseToJsonElement(
                File(root, "specs/clipboard-vectors.json").readText(Charsets.UTF_8),
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
        }

        for (row in vectors.rows("classify")) {
            when (row.text("name")) {
                "length_500" -> assertEquals(500, row.text("text").length)
                "length_501" -> assertEquals(501, row.text("text").length)
            }
        }
    }

    @Test
    fun readPolicyVectors() {
        each("readPolicy") { row, expected, label ->
            val platform = row.text("platform")
            val actual =
                ClipboardReadPolicy.decide(
                    platform = platform,
                    state = neutralState(platform, row.getValue("state").jsonObject),
                )

            assertEquals("$label.actionRaw", expected.text("actionRaw"), actual.actionRaw)
            assertText(expected, "reasonRaw", actual.reasonRaw, label)
        }
    }

    @Test
    fun inviteExtractVectors() {
        each("inviteExtract") { row, expected, label ->
            assertTrue("$label.code 必须存在", expected.containsKey("code"))
            assertText(
                expected,
                "code",
                InviteCodeExtractor.extract(row.text("text")),
                label,
            )
        }
    }

    @Test
    fun classifyVectors() {
        each("classify") { row, expected, label ->
            val hashes = VectorHasher()
            val wire = row.getValue("context").jsonObject
            val context =
                ClipboardContext(
                    selfWritten = wire.strings("selfWritten").map(hashes::digest),
                    prompted =
                        wire.rows("prompted").map { item ->
                            ClipboardPromptStamp(
                                hash = hashes.digest(item.text("text")),
                                atMs = nowMs - item.getValue("agoMs").jsonPrimitive.long,
                            )
                        },
                    inviteEligible = wire.getValue("inviteEligible").jsonPrimitive.boolean,
                    selfInviteCode = wire.optionalText("selfInviteCode"),
                    shareDomains = wire.strings("shareDomains"),
                    hash = hashes::digest,
                )

            val actual =
                ClipboardClassifier.classify(
                    text = row.text("text"),
                    context = context,
                    rules = ClipboardRules.defaults,
                    nowMs = nowMs,
                )

            assertEquals("$label.kindRaw", expected.text("kindRaw"), actual.kindRaw)
            assertText(expected, "reasonRaw", actual.reasonRaw, label)
            assertText(expected, "inviteCode", actual.inviteCode, label)
            assertText(expected, "uploadText", actual.uploadText, label)
        }
    }

    @Test
    fun writeLedgerVectors() {
        each("writeLedger") { row, expected, label ->
            val hashes = VectorHasher()
            val ledger = ClipboardWriteLedger(capacity = 10, hash = hashes::digest)
            row.strings("writes").forEach(ledger::record)

            assertEquals(
                label,
                expected.getValue("excluded").jsonPrimitive.boolean,
                ledger.contains(row.text("probe")),
            )
        }
    }

    @Test
    fun parseResultVectors() {
        each("parseResult") { row, expected, label ->
            val actual =
                ParseResultAction.map(
                    response = row.optionalObject("response"),
                    errorCode = row["errorCode"]?.jsonPrimitive?.int,
                    retryAfter = row.optionalObject("headers")?.optionalText("Retry-After"),
                    traceId = row.optionalText("traceId"),
                )

            assertEquals("$label.actionRaw", expected.text("actionRaw"), actual.actionRaw)
            assertText(expected, "linkId", actual.linkId, label)
            assertText(expected, "prefill", actual.prefill, label)
            assertText(expected, "textKey", actual.textKey, label)

            if (expected.containsKey("showTrace6")) {
                assertEquals(
                    "$label.showTrace6",
                    expected.getValue("showTrace6").jsonPrimitive.boolean,
                    actual.showTrace6,
                )
            }
            if (expected.containsKey("disableMs")) {
                assertEquals(
                    "$label.disableMs",
                    expected.getValue("disableMs").jsonPrimitive.long,
                    actual.disableMs,
                )
            }
        }
    }

    @Test
    fun manualPasteVectors() {
        each("manualPaste") { row, expected, label ->
            assertTrue("$label.text 必须存在", row.containsKey("text"))
            val actual =
                ManualPasteAction.decide(
                    entry = row.text("entry"),
                    text = row.optionalText("text"),
                    rules = ClipboardRules.defaults,
                )

            assertEquals("$label.actionRaw", expected.text("actionRaw"), actual.actionRaw)
            assertText(expected, "uploadText", actual.uploadText, label)
            assertText(expected, "prefill", actual.prefill, label)
            assertText(expected, "code", actual.code, label)
        }
    }

    @Test
    fun inviteConfirmVectors() {
        each("inviteConfirm") { row, expected, label ->
            val actual =
                InviteConfirmAction.decide(
                    session = row.text("session"),
                    me = row.optionalObject("me"),
                )
            assertEquals(label, expected.text("actionRaw"), actual.actionRaw)
        }
    }

    private fun neutralState(
        platform: String,
        raw: JsonObject,
    ): JsonObject =
        buildJsonObject {
            put("overlay", "none")
            put("configEnabled", true)
            put("webSuppressed", false)
            put("readThisForeground", false)
            if (platform == "ios") {
                put("iosChangeCountChanged", true)
            }
            if (platform == "android") {
                put("androidAutoSwitch", true)
                put("androidWindowFocused", true)
            }
            for ((key, value) in raw) {
                put(key, value)
            }
        }

    private fun each(
        group: String,
        body: (JsonObject, JsonObject, String) -> Unit,
    ) {
        val rows = vectors.rows(group)
        assertTrue("$group 不能为空", rows.isNotEmpty())
        for (row in rows) {
            body(
                row,
                row.getValue("expect").jsonObject,
                "$group.${row.text("name")}",
            )
        }
    }

    private fun assertText(
        expected: JsonObject,
        key: String,
        actual: String?,
        label: String,
    ) {
        if (expected.containsKey(key)) {
            assertEquals("$label.$key", expected.optionalText(key), actual)
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
    if (value == JsonNull) return null
    check(value.jsonPrimitive.isString) { "$key 应为字符串或 null" }
    return value.jsonPrimitive.content
}

private fun JsonObject.optionalObject(key: String): JsonObject? {
    val value = this[key] ?: return null
    return if (value == JsonNull) null else value.jsonObject
}

private fun JsonObject.rows(key: String): List<JsonObject> = getValue(key).jsonArray.map { it.jsonObject }

private fun JsonObject.strings(key: String): List<String> =
    getValue(key).jsonArray.map {
        check(it.jsonPrimitive.isString) { "$key 元素应为字符串" }
        it.jsonPrimitive.content
    }
