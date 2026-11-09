@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.couli.app.clipboard

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.clipboard.Clipboard0724Fixture.Companion.DAY
import com.couli.core.clipboard.ClipRead
import com.couli.core.clipboard.ClipboardClassifier
import com.couli.core.clipboard.ClipboardFingerprint
import com.couli.core.clipboard.ClipboardPromptStamp
import com.couli.core.clipboard.ClipboardRecords
import com.couli.core.clipboard.ClipboardSaltRead
import com.couli.core.clipboard.ManualPasteAction
import com.couli.core.clipboard.PromptHistory
import com.couli.core.router.PrivacyState
import com.couli.core.router.SessionState
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * FU-X-INVITE-MANUAL-DEDUP 测试 v1（实现前先红）：手动粘贴的邀请提示条也受「同一内容 24 小时只提示一次」约束（couli e411188 BR-INV-04 正文 ④
 * 与细则「手动粘贴的邀请提示去重」；编排 D10-593 J1–J4）。
 * - [sharedFlowVectors]：逐条执行 specs/clipboard-vectors.json manualPasteInviteDedup.flows（三端共用场景），在真实 AppClipboard 上；
 * - 其余用例补 Android 侧的执行层细节：确认条出现次数、被挡下时零上传 / 零 30132 / 零导航、记录写在出条那一刻且键是原文的加盐 hash、
 *   已登录不写、会话变化不清、fill_input / Agent / 桥读取不读不写、盐不可用时手动照常出条不写。
 * 只替换系统剪贴板、网络、时钟与盐的加密（[Clipboard0724Fixture]）；时钟只由测试推进。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class ClipboardInviteManualDedupTest {
    @Test
    fun sharedFlowVectors() =
        runTest {
            val failures = mutableListOf<String>()
            for (flow in flows()) {
                val name = flow.getValue("name").jsonPrimitive.content
                val cipher = SampleCipherImpl()
                Clipboard0724Fixture(this, cipher = cipher).use { f ->
                    f.system.content = ClipRead.Text(NEUTRAL)
                    f.foreground()
                    runCatching { FlowRunner(f, cipher, name, failures).run(flow) }
                        .onFailure { failures += "$name 执行异常：$it" }
                }
            }
            assertTrue(failures.joinToString("\n"), failures.isEmpty())
        }

    /** T1 / J4：24 小时内连粘 3 次只出 1 个确认条；被挡下的两次照常读剪贴板，但不上传、不落 30132、不导航、不出首页提示条、不预填。 */
    @Test
    fun manualRepeatWithin24hShowsOneConfirmAndTheRestEndSilently() =
        runTest {
            Clipboard0724Fixture(this).use { f ->
                f.system.content = ClipRead.Text(NEUTRAL)
                f.foreground()
                val start = f.now
                val reads = f.system.reads
                val seen = mutableListOf<ClipboardResult?>()
                for (offset in listOf(0L, HOUR, DAY - SECOND)) {
                    f.now = start + offset
                    f.system.content = ClipRead.Text(INVITE)
                    f.clipboard.paste(ManualPasteAction.ENTRY_HOME)
                    f.settle()
                    seen += f.clipboard.result.value
                    assertNull("手动粘贴不出首页提示条", f.clipboard.prompt.value)
                }
                assertEquals("每次点击都读一次剪贴板（被挡下也是读后静默）", reads + 3, f.system.reads)
                val confirms = seen.filterIsInstance<ClipboardResult.InviteConfirm>().map { it.id }.toSet()
                assertEquals("24 小时内同一原文只出 1 个确认条，实际=$seen", 1, confirms.size)
                assertTrue("被挡下不换结果、不出轻提示或 30132", seen.none { it is ClipboardResult.Notice || it is ClipboardResult.Unrecognized })
                assertEquals("被挡下不上传", 0, f.parseBodies().size)
                assertEquals("被挡下不打开 / 预填搜索", emptyList<Any>(), f.navigations)
                assertNull("没点确认，不预填待绑定码", f.clipboard.pendingInvite.peek())
                f.now = start + DAY
                f.clipboard.paste(ManualPasteAction.ENTRY_HOME)
                f.settle()
                val again = f.clipboard.result.value
                assertTrue("满 24 小时再出条，实际=$again", again is ClipboardResult.InviteConfirm && again.id !in confirms)
                assertEquals(0, f.parseBodies().size)
                assertEquals(0, f.system.writes)
            }
        }

    /** T1 / J4（SearchTyping）：首页自动出过条后，在 SearchTyping 点「粘贴查返利」静默结束；换一段文案的同一个码照常在本页出确认条。 */
    @Test
    fun searchTypingPasteBlockedByAnAutomaticPromptStaysSilentOnThatPage() =
        runTest {
            Clipboard0724Fixture(this).use { f ->
                f.system.content = ClipRead.Text(INVITE)
                f.foreground()
                val prompt = checkNotNull(f.clipboard.prompt.value) { "前提：自动在首页出了邀请条" }
                f.clipboard.onPromptClose(prompt.id)
                f.settle()
                f.clipboard.onBackStack("Search", SEARCH_ENTRY, setOf(SEARCH_ENTRY))
                f.settle()
                f.now += HOUR
                val reads = f.system.reads
                f.clipboard.paste(ManualPasteAction.ENTRY_SEARCH_TYPING)
                f.settle()
                assertEquals("确实读了剪贴板", reads + 1, f.system.reads)
                assertNull("自动出过条的同一原文，手动 24 小时内静默（BR-INV-04 ④）", f.clipboard.result.value)
                assertEquals(0, f.parseBodies().size)
                assertEquals(emptyList<Any>(), f.navigations)
                f.system.content = ClipRead.Text(LANDING)
                f.clipboard.paste(ManualPasteAction.ENTRY_SEARCH_TYPING)
                f.settle()
                val confirm = f.clipboard.result.value
                assertTrue("不同原文照常出条，实际=$confirm", confirm is ClipboardResult.InviteConfirm)
                assertEquals(f.clipboard.currentOrigin(), confirm?.origin)
            }
        }

    /** 记录在确认条发布的同时写下（不等确认或关闭），键是原文的本机加盐 HMAC（不是抽出的码），只进邀请记录、不进商品摘要记录。 */
    @Test
    fun recordIsWrittenWhenTheConfirmIsPublishedAndKeyedByTheRawText() =
        runTest {
            Clipboard0724Fixture(this).use { f ->
                f.system.content = ClipRead.Text(NEUTRAL)
                f.foreground()
                f.system.content = ClipRead.Text(INVITE)
                f.clipboard.paste(ManualPasteAction.ENTRY_HOME)
                f.settle()
                assertTrue(f.clipboard.result.value is ClipboardResult.InviteConfirm)
                val salt = (f.prefs.salt.read() as ClipboardSaltRead.Present).salt
                val expected = ClipboardPromptStamp(ClipboardFingerprint(salt).hash(INVITE), f.now)
                assertEquals("出条即记（原文 hash，时间为出条时刻）", listOf(expected), stamps(f, ClipboardRecords.KEY_PROMPTED))
                assertTrue(
                    "不是按抽出的码记",
                    stamps(f, ClipboardRecords.KEY_PROMPTED).none { it.hash == ClipboardFingerprint(salt).hash("K7M2QX") },
                )
                assertEquals("商品摘要记录不动（S2-2）", null, f.disk.getString("hash." + ClipboardRecords.KEY_PRODUCTS, null))
                assertTrue(
                    "存储里没有原文与邀请码",
                    f.disk.all.values
                        .map { it.toString() }
                        .none { it.contains("K7M2QX") || it.contains("邀请码") },
                )
                assertNull("记录不代表确认", f.clipboard.pendingInvite.peek())
            }
        }

    /** T4 / J2：已登录（G01）粘贴不出条也不写；登录、换号、退出都不清邀请记录，退回游客后 24 小时内仍静默。 */
    @Test
    fun memberPasteWritesNothingAndSessionChangesKeepTheInviteRecord() =
        runTest {
            Clipboard0724Fixture(this).use { f ->
                f.system.content = ClipRead.Text(NEUTRAL)
                f.foreground()
                f.system.content = ClipRead.Text(INVITE)
                f.clipboard.paste(ManualPasteAction.ENTRY_HOME)
                f.settle()
                f.clipboard.consumeResult(checkNotNull(f.clipboard.result.value))
                f.settle()
                val stored = f.disk.getString("hash." + ClipboardRecords.KEY_PROMPTED, null)
                assertTrue("前提：游客出条已记", stored != null)
                changeSession(f, SessionState.PHONE_BOUND)
                f.system.content = ClipRead.Text(OTHER_INVITE)
                f.clipboard.paste(ManualPasteAction.ENTRY_HOME)
                f.settle()
                assertNull("已登录不出条（G01）", f.clipboard.result.value)
                assertEquals("已登录没出条，不写记录；登录不清记录", stored, f.disk.getString("hash." + ClipboardRecords.KEY_PROMPTED, null))
                changeSession(f, SessionState.PHONE_BOUND)
                changeSession(f, SessionState.GUEST)
                assertEquals("换号、退出不清邀请记录（J2）", stored, f.disk.getString("hash." + ClipboardRecords.KEY_PROMPTED, null))
                f.now += HOUR
                f.system.content = ClipRead.Text(INVITE)
                f.clipboard.paste(ManualPasteAction.ENTRY_HOME)
                f.settle()
                assertNull("退回游客后 24 小时内同一原文仍静默", f.clipboard.result.value)
                f.system.content = ClipRead.Text(OTHER_INVITE)
                f.clipboard.paste(ManualPasteAction.ENTRY_HOME)
                f.settle()
                assertTrue("已登录时没出过条的内容，游客照常出条", f.clipboard.result.value is ClipboardResult.InviteConfirm)
                assertEquals(0, f.requests.size)
            }
        }

    /** T7：出过条之后 24 小时内，填写邀请码页「粘贴」两次都照常填码，Agent 粘贴照常拿原文，桥读取照常执行；三者都不读不写邀请记录。 */
    @Test
    fun fillInputAgentAndBridgeReadsAreNotDeduplicatedAndDoNotTouchTheRecord() =
        runTest {
            Clipboard0724Fixture(this).use { f ->
                f.system.content = ClipRead.Text(NEUTRAL)
                f.foreground()
                f.system.content = ClipRead.Text(INVITE)
                f.clipboard.paste(ManualPasteAction.ENTRY_HOME)
                f.settle()
                f.clipboard.consumeResult(checkNotNull(f.clipboard.result.value))
                f.settle()
                val stored = f.disk.getString("hash." + ClipboardRecords.KEY_PROMPTED, null)
                val reads = f.system.reads
                f.now += HOUR
                assertEquals("K7M2QX", f.clipboard.pasteInviteCode())
                assertEquals("第二次照常预填", "K7M2QX", f.clipboard.pasteInviteCode())
                assertEquals("Agent 粘贴原样交付", INVITE, f.clipboard.pasteForAgent())
                f.clipboard.bridgeRead()
                f.settle()
                assertEquals(reads + 4, f.system.reads)
                assertEquals("不写邀请记录", stored, f.disk.getString("hash." + ClipboardRecords.KEY_PROMPTED, null))
                assertNull("都不出条", f.clipboard.result.value)
                assertNull(f.clipboard.prompt.value)
                assertEquals(0, f.parseBodies().size)
            }
        }

    /** J3：盐的安全存储不可用时，手动粘贴每次都出条、不写记录（不回退到不加盐 hash）；自动读取照旧不出条。 */
    @Test
    fun saltUnavailableManualPasteShowsEveryTimeWithoutWritingWhileAutomaticStaysQuiet() =
        runTest {
            val cipher = SampleCipherImpl()
            Clipboard0724Fixture(this, cipher = cipher).use { f ->
                f.system.content = ClipRead.Text(NEUTRAL)
                f.foreground()
                assertTrue("前提：盐已生成", f.prefs.salt.read() is ClipboardSaltRead.Present)
                val before = f.disk.all.filterKeys { it.startsWith("hash.") }
                cipher.failing = true
                val ids = mutableListOf<Long>()
                repeat(2) {
                    f.system.content = ClipRead.Text(INVITE)
                    f.clipboard.paste(ManualPasteAction.ENTRY_HOME)
                    f.settle()
                    val confirm = f.clipboard.result.value
                    assertTrue("盐不可用时手动照常出条，第 ${it + 1} 次实际=$confirm", confirm is ClipboardResult.InviteConfirm)
                    ids += checkNotNull(confirm).id
                    f.clipboard.consumeResult(confirm)
                    f.settle()
                }
                assertEquals(2, ids.toSet().size)
                assertEquals("不写任何记录", before, f.disk.all.filterKeys { it.startsWith("hash.") })
                f.nextRound(INVITE)
                assertNull("自动照旧不出条", f.clipboard.prompt.value)
                assertEquals(0, f.parseBodies().size)
            }
        }

    // ---------------------------------------------------------------- 共享场景执行器

    private class FlowRunner(
        private val f: Clipboard0724Fixture,
        private val cipher: SampleCipherImpl,
        private val flow: String,
        private val failures: MutableList<String>,
    ) {
        private var onSearch = false

        fun run(row: JsonObject) {
            row.getValue("steps").jsonArray.forEachIndexed { index, element ->
                step("$flow#$index", element.jsonObject)
            }
        }

        private fun step(
            at: String,
            step: JsonObject,
        ) {
            val beforeResult = f.clipboard.result.value
            val beforePrompt = f.clipboard.prompt.value
            val beforeUploads = f.parseBodies().size
            val beforeNavigations = f.navigations.size
            val code = act(step)
            f.settle()
            val result = f.clipboard.result.value
            val prompt = f.clipboard.prompt.value
            val newConfirm = (result as? ClipboardResult.InviteConfirm)?.takeIf { it.id != beforeResult?.id }
            val newPrompt = prompt?.takeIf { it.kind == ClipboardClassifier.KIND_INVITE && it.id != beforePrompt?.id }
            val newFallback =
                (
                    result != null && result.id != beforeResult?.id &&
                        (result is ClipboardResult.Notice || result is ClipboardResult.Unrecognized)
                ) ||
                    f.navigations.size > beforeNavigations
            val actual =
                mapOf(
                    "show" to (newConfirm != null || newPrompt != null),
                    "uploads" to f.parseBodies().size - beforeUploads,
                    "fallback" to newFallback,
                    "code" to code,
                    "inviteRecords" to liveRecords(ClipboardRecords.KEY_PROMPTED),
                    "productRecords" to liveRecords(ClipboardRecords.KEY_PRODUCTS),
                )
            compare(at, step, actual)
            afterShown(step["then"]?.jsonPrimitive?.content ?: "close", newConfirm, newPrompt)
        }

        private fun compare(
            at: String,
            step: JsonObject,
            actual: Map<String, Any?>,
        ) {
            step["expect"]?.jsonObject?.forEach { (field, value) ->
                val want: Any? =
                    when (field) {
                        "show", "fallback" -> value.jsonPrimitive.boolean
                        "code" -> value.jsonPrimitive.content
                        else -> value.jsonPrimitive.int
                    }
                if (want != actual[field]) failures += "$at ${step["op"]} $field 期望 $want 实际 ${actual[field]}"
            }
        }

        /** 出条后用户的处理：close（缺省）关闭、confirm 点确认、ignore 不理。 */
        private fun afterShown(
            then: String,
            confirm: ClipboardResult.InviteConfirm?,
            prompt: ClipboardPrompt?,
        ) {
            when (then) {
                "close" -> {
                    confirm?.let(f.clipboard::consumeResult)
                    prompt?.let { f.clipboard.onPromptClose(it.id) }
                }

                "confirm" -> {
                    confirm?.let(f.clipboard::confirmManualInvite)
                    prompt?.let { f.clipboard.onPromptAction(it.id) }
                }
            }
            f.settle()
        }

        @Suppress("CyclomaticComplexMethod") // 共享场景的 op 逐个映射到 Android 入口
        private fun act(step: JsonObject): String? {
            val text = step["text"]?.jsonPrimitive?.content
            when (step.getValue("op").jsonPrimitive.content) {
                "paste" -> paste(step.getValue("entry").jsonPrimitive.content, checkNotNull(text))
                "foreground" -> foreground(checkNotNull(text))
                "wait" -> f.now += step.getValue("ms").jsonPrimitive.long
                "app_write" -> assertTrue("App 自写须成功", f.clipboard.writer.write(checkNotNull(text)))
                "fill_input" -> return fillInput(checkNotNull(text))
                "bridge_read" -> bridgeRead(checkNotNull(text))
                "login", "switch_account" -> changeSession(f, SessionState.PHONE_BOUND)
                "logout" -> changeSession(f, SessionState.GUEST)
                "withdraw_consent" -> privacy(PrivacyState.BASIC_MODE, withdrawn = true)
                "basic_mode" -> privacy(PrivacyState.BASIC_MODE, withdrawn = false)
                "consent_again" -> consentAgain()
                "salt_rotate" -> assertTrue(f.prefs.salt.write(OTHER_SAMPLE_SALT))
                "salt_unavailable" -> cipher.failing = true
                "salt_restore" -> cipher.failing = false
                else -> throw AssertionError("未知 op：$step")
            }
            return null
        }

        private fun paste(
            entry: String,
            text: String,
        ) {
            page(search = entry == ManualPasteAction.ENTRY_SEARCH_TYPING)
            f.system.content = ClipRead.Text(text)
            f.clipboard.paste(entry)
        }

        private fun foreground(text: String) {
            page(search = false)
            f.nextRound(text)
        }

        private fun fillInput(text: String): String? {
            f.system.content = ClipRead.Text(text)
            return f.clipboard.pasteInviteCode()
        }

        private fun bridgeRead(text: String) {
            f.system.content = ClipRead.Text(text)
            f.clipboard.bridgeRead()
        }

        private fun page(search: Boolean) {
            if (search == onSearch) return
            if (search) {
                f.clipboard.onBackStack(
                    "Search",
                    SEARCH_ENTRY,
                    setOf(SEARCH_ENTRY),
                )
            } else {
                f.clipboard.onBackStack(null, null, emptySet())
            }
            onSearch = search
            f.settle()
        }

        private fun privacy(
            state: PrivacyState,
            withdrawn: Boolean,
        ) {
            f.privacy = state
            f.clipboard.onPrivacyChanged(state, withdrawn = withdrawn)
        }

        /** 重新同意隐私政策并重新打开剪贴板功能（看过用途说明）；不碰记录。 */
        private fun consentAgain() {
            f.privacy = PrivacyState.CONSENTED
            f.clipboard.onPrivacyChanged(PrivacyState.CONSENTED)
            assertTrue(f.preferences.setOn())
        }

        /** 仍有效的条数：属于当前盐、距今严格小于 24 小时；盐读不出（安全存储不可用）时数存储里 24 小时内的条数。 */
        private fun liveRecords(key: String): Int {
            val live = stamps(f, key).count { f.now - it.atMs in 0 until DAY }
            return when (val salt = f.prefs.salt.read()) {
                is ClipboardSaltRead.Present -> {
                    val current = ClipboardFingerprint(salt.salt).saltId
                    if (f.prefs.hashes.read(ClipboardRecords.KEY_SALT_ID) == current) live else 0
                }

                ClipboardSaltRead.Absent -> {
                    0
                }

                ClipboardSaltRead.Failed -> {
                    live
                }
            }
        }
    }

    private companion object {
        const val INVITE = "邀请码 K7M2QX"
        const val OTHER_INVITE = "邀请码 P8N3RY"
        const val LANDING = "https://share.example.test/i/K7M2QX"
        const val NEUTRAL = "今天下午三点在楼下见面"
        const val SEARCH_ENTRY = "search-invite-dedup"
        const val SECOND = 1_000L
        const val HOUR = 3_600_000L

        fun stamps(
            f: Clipboard0724Fixture,
            key: String,
        ): List<ClipboardPromptStamp> = PromptHistory(store = f.prefs.hashes, key = key).stamps()

        fun changeSession(
            f: Clipboard0724Fixture,
            state: SessionState,
        ) {
            f.sessionEpoch += 1
            f.routing.session.value = state
            f.clipboard.onSessionStored()
            f.settle()
        }

        fun flows(): List<JsonObject> {
            val start = File(System.getProperty("user.dir") ?: throw AssertionError("找不到 user.dir")).canonicalFile
            val root =
                generateSequence(start) { it.parentFile }
                    .firstOrNull { File(it, "contract.lock").isFile }
                    ?: throw AssertionError("从 $start 向上找不到 contract.lock")
            val vectors = Json.parseToJsonElement(File(root, "specs/clipboard-vectors.json").readText(Charsets.UTF_8)).jsonObject
            val rows =
                vectors
                    .getValue("manualPasteInviteDedup")
                    .jsonObject
                    .getValue("flows")
                    .jsonArray
                    .map { it.jsonObject }
            assertFalse("flows 不能为空", rows.isEmpty())
            return rows
        }
    }
}
