/*
 * FU-X-INVITE-MANUAL-DEDUP 测试 v1（实现前先红）：消费 specs/clipboard-vectors.json 的 manualPasteInviteDedup 组（三端逐字节共用）。
 * 依据：couli e411188 BR-INV-04 正文 ④ 与细则「手动粘贴的邀请提示去重」；编排 D10-593 J1–J4。
 *
 * 接线约定（实现须提供，位于 com.couli.core.clipboard，生产 AppClipboard 的自动与手动邀请分支都调它）：
 * - InviteDedupPolicy.decide(kindRaw: String, entry: String?, text: String, selfWritten: List<String>,
 *   prompted: List<ClipboardPromptStamp>, saltAvailable: Boolean, nowMs: Long, hash: (String) -> String): InviteDedupDecision
 *   kindRaw 取 ImportTask.KIND_AUTO / KIND_MANUAL；entry 取 ManualPasteAction.ENTRY_*（自动为 null）；selfWritten、prompted 里是 hash；
 *   窗口复用 ClipboardClassifier.PROMPT_WINDOW_MS（严格小于）。saltAvailable=false 时不得调用 hash（不回退到不加盐 hash）。
 * - InviteDedupDecision(show: Boolean, record: Boolean, reasonRaw: String?)。
 * flows 段是执行层场景，由 app 模块的 ClipboardInviteManualDedupTest 在真实 AppClipboard 上执行；这里只核它的形状，免得三端各读各的。
 */

package com.couli.core.clipboard

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardInviteDedupVectorTest {
    private val nowMs = 1_800_000_000_000L

    private val vectors: JsonObject by lazy {
        val start = File(System.getProperty("user.dir") ?: throw AssertionError("找不到 user.dir")).canonicalFile
        val root =
            generateSequence(start) { it.parentFile }
                .firstOrNull { File(it, "contract.lock").isFile }
                ?: throw AssertionError("从 $start 向上找不到 contract.lock")
        Json.parseToJsonElement(File(root, "specs/clipboard-vectors.json").readText(Charsets.UTF_8)).jsonObject
    }

    private val group: JsonObject by lazy {
        assertNotNull("缺 manualPasteInviteDedup 组", vectors[GROUP])
        vectors.getValue(GROUP).jsonObject
    }

    @Test
    fun manifest() {
        val keys = vectors.keys.toList()
        assertEquals("新组紧跟在 manualPaste 之后", keys.indexOf("manualPaste") + 1, keys.indexOf(GROUP))
        assertEquals(1, group.getValue("schemaVersion").jsonPrimitive.int)
        assertTrue(group.getValue("sources").jsonArray.isNotEmpty())
        val fields = group.getValue("fields").jsonObject.keys
        listOf("decide", "decide.context", "decide.expect", "flows", "flows.ops", "flows.expect").forEach {
            assertTrue("fields 缺 $it", it in fields)
        }
        for (part in listOf("decide", "flows")) {
            val names = group.objects(part).map { it.text("name") }
            assertTrue("$part 不能为空", names.isNotEmpty())
            assertTrue("$part 行名以 BR-INV-04. 开头", names.all { it.startsWith("BR-INV-04.") })
            assertEquals("$part 行名重复", names.size, names.toSet().size)
        }
    }

    /** 每行都是「已满足触发前提」的邀请文本：抽得到码、命中落地页或「邀请码」字样（与 classify 组同一份分享域名）。 */
    @Test
    fun decideRowsAreTriggeredInviteTexts() {
        for (row in group.objects("decide")) {
            val text = row.text("text")
            assertNotNull("${row.text("name")} 抽不到邀请码", InviteCodeExtractor.extract(text))
            assertTrue("${row.text("name")} 不满足触发前提", ClipboardClassifier.inviteTriggered(text, SHARE_DOMAINS))
        }
    }

    @Test
    fun decideVectors() {
        val failures = group.objects("decide").mapNotNull(::checkDecide)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun flowRowsUseOnlyKnownOpsAndFields() {
        for (flow in group.objects("flows")) {
            val name = flow.text("name")
            assertEquals("$name given.session", "guest", flow.getValue("given").jsonObject.text("session"))
            val steps = flow.objects("steps")
            assertTrue("$name 没有步骤", steps.isNotEmpty())
            steps.forEachIndexed { index, step -> checkStepShape("$name#$index", step) }
        }
    }

    private fun checkDecide(row: JsonObject): String? {
        val name = row.text("name")
        val context = row.getValue("context").jsonObject
        val saltAvailable = context.getValue("saltAvailable").jsonPrimitive.boolean
        val digests = mutableMapOf<String, String>()
        val digest: (String) -> String = { digests.getOrPut(it) { "fixture-digest-${digests.size + 1}" } }
        val selfWritten = context.elements("selfWritten").map { digest(it.jsonPrimitive.content) }
        val prompted =
            context.objects("prompted").map { stamp ->
                ClipboardPromptStamp(digest(stamp.text("text")), nowMs - stamp.getValue("agoMs").jsonPrimitive.long)
            }
        var unsaltedHash = false
        val hash: (String) -> String = { text ->
            if (!saltAvailable) unsaltedHash = true
            digest(text)
        }
        val kind = row.text("kind")
        val entry = row.optText("entry")
        val actual =
            InviteDedupPolicy.decide(kind, entry, row.text("text"), selfWritten, prompted, saltAvailable, nowMs, hash)
        val expect = row.getValue("expect").jsonObject
        val wrong = mutableListOf<String>()
        if (unsaltedHash) wrong += "盐不可用时仍算了 hash"
        if (expect.getValue("show").jsonPrimitive.boolean != actual.show) wrong += "show=${actual.show}"
        if (expect.getValue("record").jsonPrimitive.boolean != actual.record) wrong += "record=${actual.record}"
        if (expect.optText("reasonRaw") != actual.reasonRaw) wrong += "reasonRaw=${actual.reasonRaw}"
        return if (wrong.isEmpty()) null else "$name：$wrong"
    }

    private fun checkStepShape(
        at: String,
        step: JsonObject,
    ) {
        val op = step.text("op")
        assertTrue("$at 未知 op=$op", op in OPS)
        assertTrue("$at 多余字段 ${step.keys}", step.keys.all { it in STEP_FIELDS })
        if (op in TEXT_OPS) assertTrue("$at 缺 text", step.text("text").isNotEmpty())
        if (op == "paste") assertTrue("$at entry", step.text("entry") in PASTE_ENTRIES)
        if (op == "wait") assertTrue("$at ms>0", (step.getValue("ms").jsonPrimitive.longOrNull ?: 0L) > 0L)
        step["then"]?.let { assertTrue("$at then", it.jsonPrimitive.content in THEN && op in SHOWING_OPS) }
        val expect = step["expect"]?.jsonObject ?: return
        assertTrue("$at expect 多余字段 ${expect.keys}", expect.keys.all { it in EXPECT_FIELDS })
        expect["show"]?.let { assertNotNull("$at show 须为布尔", it.jsonPrimitive.booleanOrNull) }
        expect["fallback"]?.let { assertNotNull("$at fallback 须为布尔", it.jsonPrimitive.booleanOrNull) }
        if ("code" in expect) assertEquals("$at code 只用于 fill_input", "fill_input", op)
    }

    private fun JsonObject.objects(name: String): List<JsonObject> = getValue(name).jsonArray.map { it.jsonObject }

    private fun JsonObject.elements(name: String): List<JsonElement> = getValue(name).jsonArray

    private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonObject.optText(name: String): String? {
        val value = this[name] ?: return null
        return if (value == JsonNull) null else value.jsonPrimitive.content
    }

    private companion object {
        const val GROUP = "manualPasteInviteDedup"
        val SHARE_DOMAINS = listOf("share.example.test")
        val OPS =
            setOf(
                "paste",
                "foreground",
                "wait",
                "app_write",
                "fill_input",
                "bridge_read",
                "login",
                "switch_account",
                "logout",
                "withdraw_consent",
                "basic_mode",
                "consent_again",
                "salt_rotate",
                "salt_unavailable",
                "salt_restore",
            )
        val TEXT_OPS = setOf("paste", "foreground", "app_write", "fill_input", "bridge_read")
        val SHOWING_OPS = setOf("paste", "foreground")
        val PASTE_ENTRIES = setOf("home", "search_typing")
        val THEN = setOf("close", "confirm", "ignore")
        val STEP_FIELDS = setOf("op", "entry", "text", "ms", "then", "expect")
        val EXPECT_FIELDS = setOf("show", "uploads", "fallback", "code", "inviteRecords", "productRecords")
    }
}
