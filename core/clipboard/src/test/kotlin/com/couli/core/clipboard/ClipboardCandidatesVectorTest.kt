/*
 * 实现前测试；只读交付，未编译运行；不修改旧 ClipboardVectorTest。
 *
 * 接线约定：被测类型都位于 com.couli.core.clipboard。
 * - ClipboardRules.defaults.tightened(ClipboardLimits)；
 *   ClipboardLimits(platforms: List<String>?, removeHosts: List<String>?,
 *   tpwdEnabled: Boolean?)；fromFeatureFlags(Map<String, Boolean>)；
 *   .platforms 为 List<String>?，null 表示无限制。
 * - ClipboardHostPolicy.check(url, rules) -> allowed/platform/reasonRaw。
 * - TpwdExtractor.extract(text, rules) -> List<String>。
 * - ClipboardCandidates.extract(text, rules, shareDomains)
 *   -> 每项 kindRaw/platform/raw 均为 String；
 *   uploadText(text, rules, shareDomains) -> String?。
 * - ClipboardPromptDisplay.describe(candidates)
 *   -> platform/labelRaw/display: String?/count: Int/displayName，整体可为 null。
 * - ClipboardClassifier.classify(text, context, rules, nowMs) 与 ClipboardContext、
 *   ClipboardPromptStamp 沿用旧测试 API；时间为 Long 毫秒。
 *   ManualPasteAction.decide(entry, text, rules, shareDomains) 保留旧三参数调用；
 *   返回 actionRaw/uploadText/prefill/code。
 * - ClipboardFingerprint(salt: ByteArray)；hash(text): String；saltId: String。
 * - ClipboardRecords(store, saltStore, clock: () -> Long, randomSalt: () -> ByteArray)；
 *   recordSelfWritten/recordPrompted/isSelfWritten/withdraw 返回 Boolean；
 *   promptedWithin24h 返回 Boolean?。
 * - MemoryClipboardHashStore.dump(): List<String> 返回全部实际存储键和值。
 *   MemoryClipboardSaltStore 初始无盐；setSalt(ByteArray) 供测试直接换盐；
 *   failReads/failWrites 为可写 Boolean；hasSalt 为无副作用的观察属性。
 *   failSalt 同时置读写失败；setSalt 不重建 ClipboardRecords。
 * - ClipboardBridgeRead.respond(consentRaw, read: () -> String?, rules, shareDomains)。
 * - ShareImportAction.decide(state: JsonObject, mime, text, rules, shareDomains)
 *   -> actionRaw/uploadText/prefill/scene/display。
 * - expect 缺键不判定；显式 null 必须无值。shareDomains 缺省取 fixtures；
 *   tighten 缺省取 ClipboardRules.defaults。
 *   分享 state 缺省 overlay=none、parseInFlight=false 是测试中性前提。
 * - 每条 records 使用独立内存存储、毫秒时钟和随机盐队列。
 *   HMAC 与 saltId 核对向量已知答案；未加盐摘要另用 MessageDigest。
 * - 复用 JUnit4、kotlinx.serialization.json；不接系统剪贴板、网络或持久化。
 *   新向量只覆盖 FU-CLIP，旧向量继续覆盖原长度、自写、24 小时及邀请回归；
 *   gaps 中桥入口、生命周期、隐私红线与分享接收集成验收须另外完成。
 */

package com.couli.core.clipboard

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardCandidatesVectorTest {
    private val nowMs = 1_800_000_000_000L
    private val groups =
        listOf(
            "hostCheck",
            "flagLimits",
            "tpwdExtract",
            "candidates",
            "promptDisplay",
            "classify",
            "manualPaste",
            "fingerprint",
            "records",
            "bridgeRead",
            "shareImport",
        )

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
                File(root, "specs/clipboard-candidates-vectors.json").readText(Charsets.UTF_8),
            ).jsonObject
    }

    @Test
    fun manifest() {
        assertEquals(1, vectors.getValue("version").jsonPrimitive.int)
        assertTrue(vectors.strings("sources").isNotEmpty())
        assertTrue(vectors.strings("gaps").isNotEmpty())
        val fixtures = vectors.obj("fixtures")
        assertEquals(32, saltBytes(fixtures.text("saltHex")).size)
        assertEquals(32, saltBytes(fixtures.text("otherSaltHex")).size)
        assertNotEquals(fixtures.text("saltHex"), fixtures.text("otherSaltHex"))
        for (group in groups) {
            val rows = vectors.rows(group)
            assertTrue("$group 不能为空", rows.isNotEmpty())
            val names = rows.map { it.text("name") }
            assertTrue("$group 名称不能为空", names.all { it.isNotBlank() })
            assertEquals("$group 名称重复", names.size, names.toSet().size)
        }
    }

    @Test
    fun hostCheck() {
        each("hostCheck") { row, expected, label ->
            val actual = ClipboardHostPolicy.check(row.text("url"), rules(row))
            fields(
                expected,
                json(
                    "allowed" to JsonPrimitive(actual.allowed),
                    "platform" to str(actual.platform),
                    "reasonRaw" to str(actual.reasonRaw),
                ),
                label,
            )
        }
    }

    @Test
    fun flagLimits() {
        each("flagLimits") { row, expected, label ->
            val flags = row.obj("flags").mapValues { it.value.jsonPrimitive.boolean }
            val actual = ClipboardLimits.fromFeatureFlags(flags)
            fields(
                expected,
                json(
                    "platforms" to (
                        actual.platforms?.let { JsonArray(it.map(::JsonPrimitive)) }
                            ?: JsonNull
                    ),
                ),
                label,
            )
        }
    }

    @Test
    fun tpwdExtract() {
        each("tpwdExtract") { row, expected, label ->
            val actual = TpwdExtractor.extract(row.text("text"), rules(row))
            fields(expected, json("tokens" to JsonArray(actual.map(::JsonPrimitive))), label)
        }
    }

    @Test
    fun candidates() {
        each("candidates") { row, expected, label ->
            val input = row.text("text")
            val rules = rules(row)
            val domains = domains(row)
            val actual = ClipboardCandidates.extract(input, rules, domains)
            val items =
                JsonArray(
                    actual.map {
                        json(
                            "kindRaw" to str(it.kindRaw),
                            "platform" to str(it.platform),
                            "raw" to str(it.raw),
                        )
                    },
                )
            fields(
                expected,
                json(
                    "items" to items,
                    "uploadText" to str(ClipboardCandidates.uploadText(input, rules, domains)),
                ),
                label,
            )
        }
    }

    @Test
    fun promptDisplay() {
        each("promptDisplay") { row, expected, label ->
            val candidates = ClipboardCandidates.extract(row.text("text"), rules(row), domains(row))
            val actual =
                ClipboardPromptDisplay.describe(candidates)
                    ?: throw AssertionError("$label: 应有显示信息")
            fields(
                expected,
                displayFields(
                    actual.platform,
                    actual.labelRaw,
                    actual.display,
                    actual.count,
                    actual.displayName,
                ),
                label,
            )
        }
    }

    @Test
    fun classify() {
        each("classify") { row, expected, label ->
            val wire = row.obj("context")
            val fingerprint = ClipboardFingerprint(saltBytes(vectors.obj("fixtures").text("saltHex")))
            val context =
                ClipboardContext(
                    selfWritten = wire.strings("selfWritten").map(fingerprint::hash),
                    prompted =
                        wire.rows("prompted").map {
                            ClipboardPromptStamp(
                                hash = fingerprint.hash(it.text("text")),
                                atMs = nowMs - it.getValue("agoMs").jsonPrimitive.long,
                            )
                        },
                    inviteEligible = wire.getValue("inviteEligible").jsonPrimitive.boolean,
                    selfInviteCode = wire.optionalText("selfInviteCode"),
                    shareDomains = wire.strings("shareDomains"),
                    hash = fingerprint::hash,
                )
            val actual = ClipboardClassifier.classify(row.text("text"), context, rules(row), nowMs)
            fields(
                expected,
                json(
                    "kindRaw" to str(actual.kindRaw),
                    "reasonRaw" to str(actual.reasonRaw),
                    "inviteCode" to str(actual.inviteCode),
                    "uploadText" to str(actual.uploadText),
                ),
                label,
            )
        }
    }

    @Test
    fun manualPaste() {
        each("manualPaste") { row, expected, label ->
            assertTrue("$label.text 必须存在", row.containsKey("text"))
            val actual =
                ManualPasteAction.decide(
                    row.text("entry"),
                    row.optionalText("text"),
                    rules(row),
                    domains(row),
                )
            fields(
                expected,
                json(
                    "actionRaw" to str(actual.actionRaw),
                    "uploadText" to str(actual.uploadText),
                    "prefill" to str(actual.prefill),
                    "code" to str(actual.code),
                ),
                label,
            )
        }
    }

    @Test
    fun fingerprint() {
        each("fingerprint") { row, expected, label ->
            val input = row.text("text")
            val actual = ClipboardFingerprint(saltBytes(row.text("saltHex")))
            val hex = actual.hash(input)
            val plain =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(input.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            fields(
                expected,
                json(
                    "hex" to str(hex),
                    "saltId" to str(actual.saltId),
                    "notPlainSha256" to JsonPrimitive(hex != plain),
                ),
                label,
            )
        }
    }

    @Test
    fun records() {
        for (row in vectors.rows("records")) {
            val label = "records.${row.text("name")}"
            val store = MemoryClipboardHashStore()
            val saltStore = MemoryClipboardSaltStore()
            var clock = nowMs
            val pendingSalts = mutableListOf<ByteArray>()
            val records =
                ClipboardRecords(
                    store = store,
                    saltStore = saltStore,
                    clock = { clock },
                    randomSalt = {
                        if (pendingSalts.isEmpty()) {
                            throw AssertionError("$label: 未安排的 randomSalt 调用")
                        }
                        pendingSalts.removeAt(0)
                    },
                )
            row.rows("steps").forEachIndexed { index, step ->
                val op = step.text("op")
                val stepLabel = "$label.$index.$op"
                when (op) {
                    "nextRandomSalt" -> {
                        pendingSalts.add(saltBytes(step.text("saltHex")))
                    }

                    "setSalt" -> {
                        saltStore.setSalt(saltBytes(step.text("saltHex")))
                    }

                    "failSalt" -> {
                        saltStore.failReads = true
                        saltStore.failWrites = true
                    }

                    "clock" -> {
                        clock = step.getValue("nowMs").jsonPrimitive.long
                    }

                    "recordWrite" -> {
                        assertEquals(
                            stepLabel,
                            step.getValue("expectOk").jsonPrimitive.boolean,
                            records.recordSelfWritten(step.text("text")),
                        )
                    }

                    "recordPrompt" -> {
                        assertEquals(
                            stepLabel,
                            step.getValue("expectOk").jsonPrimitive.boolean,
                            records.recordPrompted(step.text("text")),
                        )
                    }

                    "withdraw" -> {
                        assertEquals(
                            stepLabel,
                            step.getValue("expectOk").jsonPrimitive.boolean,
                            records.withdraw(),
                        )
                    }

                    "checkWrite" -> {
                        assertEquals(
                            stepLabel,
                            step.getValue("expect").jsonPrimitive.boolean,
                            records.isSelfWritten(step.text("text")),
                        )
                    }

                    "checkPrompt" -> {
                        val raw = step.getValue("expect")
                        val expected = if (raw == JsonNull) null else raw.jsonPrimitive.boolean
                        assertEquals(
                            stepLabel,
                            expected,
                            records.promptedWithin24h(step.text("text")),
                        )
                    }

                    "storeLacks" -> {
                        val stored = store.dump().joinToString("\n")
                        step.strings("fragments").forEachIndexed { fragmentIndex, fragment ->
                            assertFalse(
                                "$stepLabel.fragment[$fragmentIndex]",
                                stored.contains(fragment),
                            )
                        }
                    }

                    "storeHas" -> {
                        assertTrue(
                            stepLabel,
                            store.dump().joinToString("\n").contains(step.text("hex")),
                        )
                    }

                    "saltPresent" -> {
                        assertEquals(
                            stepLabel,
                            step.getValue("expect").jsonPrimitive.boolean,
                            saltStore.hasSalt,
                        )
                    }

                    else -> {
                        throw AssertionError("$stepLabel: 未知步骤")
                    }
                }
            }
            assertTrue("$label: 安排的随机盐未使用", pendingSalts.isEmpty())
        }
    }

    @Test
    fun bridgeRead() {
        each("bridgeRead") { row, expected, label ->
            assertTrue("$label.clipboard 必须存在", row.containsKey("clipboard"))
            val clipboard = row.optionalText("clipboard")
            var reads = 0
            val actual =
                ClipboardBridgeRead.respond(
                    consentRaw = row.text("consent"),
                    read = {
                        reads += 1
                        clipboard
                    },
                    rules = rules(row),
                    shareDomains = domains(row),
                )
            fields(expected, json("text" to str(actual), "reads" to JsonPrimitive(reads)), label)
        }
    }

    @Test
    fun shareImport() {
        each("shareImport") { row, expected, label ->
            val state =
                buildJsonObject {
                    put("overlay", "none")
                    put("parseInFlight", false)
                    for ((key, value) in row.obj("state")) put(key, value)
                }
            assertTrue("$label.text 必须存在", row.containsKey("text"))
            val actual =
                ShareImportAction.decide(
                    state,
                    row.text("mime"),
                    row.optionalText("text"),
                    rules(row),
                    domains(row),
                )
            val display =
                actual.display?.let {
                    displayFields(it.platform, it.labelRaw, it.display, it.count, it.displayName)
                } ?: JsonNull
            fields(
                expected,
                json(
                    "actionRaw" to str(actual.actionRaw),
                    "uploadText" to str(actual.uploadText),
                    "prefill" to str(actual.prefill),
                    "scene" to str(actual.scene),
                    "display" to display,
                ),
                label,
            )
        }
    }

    private fun rules(row: JsonObject): ClipboardRules {
        val raw = row["tighten"]?.jsonObject ?: return ClipboardRules.defaults
        return ClipboardRules.defaults.tightened(
            ClipboardLimits(
                platforms = raw.optionalStrings("platforms"),
                removeHosts = raw.optionalStrings("removeHosts"),
                tpwdEnabled = raw["tpwdEnabled"]?.jsonPrimitive?.boolean,
            ),
        )
    }

    private fun domains(row: JsonObject): List<String> =
        row.optionalStrings("shareDomains") ?: vectors.obj("fixtures").strings("shareDomains")

    private fun each(
        group: String,
        body: (JsonObject, JsonObject, String) -> Unit,
    ) {
        val rows = vectors.rows(group)
        assertTrue("$group 不能为空", rows.isNotEmpty())
        for (row in rows) body(row, row.obj("expect"), "$group.${row.text("name")}")
    }

    private fun fields(
        expected: JsonObject,
        actual: JsonObject,
        label: String,
    ) {
        for ((key, value) in expected) {
            assertTrue("$label.$key: 缺少实际值", actual.containsKey(key))
            assertEquals("$label.$key", value, actual.getValue(key))
        }
    }

    private fun displayFields(
        platform: String,
        labelRaw: String,
        display: String?,
        count: Int,
        displayName: String,
    ): JsonObject =
        json(
            "platform" to str(platform),
            "labelRaw" to str(labelRaw),
            "display" to str(display),
            "count" to JsonPrimitive(count),
            "displayName" to str(displayName),
        )
}

private fun json(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(pairs.toMap())

private fun str(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull

private fun saltBytes(hex: String): ByteArray {
    require(Regex("[0-9a-f]{64}").matches(hex)) { "样例盐必须是 32 字节小写 hex" }
    return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
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

private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject

private fun JsonObject.rows(key: String): List<JsonObject> = getValue(key).jsonArray.map { it.jsonObject }

private fun JsonObject.strings(key: String): List<String> =
    getValue(key).jsonArray.map {
        check(it.jsonPrimitive.isString) { "$key 元素应为字符串" }
        it.jsonPrimitive.content
    }

private fun JsonObject.optionalStrings(key: String): List<String>? {
    val value = this[key] ?: return null
    return if (value == JsonNull) null else strings(key)
}
