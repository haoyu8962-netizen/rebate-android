package com.couli.core.clipboard

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FU-CLIP 的补充单测（向量之外的实现细节）：「」标题最小化（共性第 5、8 条）、记录的迁移 / 撤回顺序 / 换盐后的提示记录、features.flags 解析、
 * 候选的「仍被允许」判定（规则表变化作废，§2.1 第 5 条、共性第 9 条）。
 */
class ClipboardFuClipTest {
    private val rules = ClipboardRules.defaults
    private val domains = listOf("share.example.test")
    private val salt = ByteArray(32) { it.toByte() }

    // ---------------------------------------------------------------- 共性第 5 条：标题最小化

    @Test
    fun lesson5_aTitleCarryingAnUnlistedLinkIsDroppedWhole() {
        val upload = ClipboardCandidates.uploadText("https://e.tb.cn/demo 「查看订单 https://example.com/private?order=123」", rules, domains)
        assertEquals("https://e.tb.cn/demo", upload)
    }

    @Test
    fun lesson5_whitelistedLinksAndTokensInsideTheTitleAreStrippedAndCannotBypassTheCap() {
        val text = "https://m.tb.cn/a https://m.tb.cn/b https://m.tb.cn/c 「好物 https://m.tb.cn/d ￥AbCdEfGh12￥ 耳机」"
        val upload = ClipboardCandidates.uploadText(text, rules, domains)
        assertEquals("https://m.tb.cn/a\nhttps://m.tb.cn/b\nhttps://m.tb.cn/c\n好物 耳机", upload)
    }

    @Test
    fun lesson5_inviteLandingBareDomainEmailIpOrLooseTokenInsideTheTitleDropsIt() {
        for (title in listOf(
            "https://share.example.test/i/K7M2QX",
            "访问 www.example.com",
            "见 example.org 详情",
            "邮箱 someone@example.com",
            "地址 10.0.0.1",
            "口令 ￥Ab12Cd￥",
            "新版 /CZ001 abcdefgh/",
            "ftp://files.example.test/a",
        )) {
            assertEquals(title, "https://m.tb.cn/h.x", ClipboardCandidates.uploadText("https://m.tb.cn/h.x 「$title」", rules, domains))
        }
    }

    @Test
    fun lesson8_localPrefillIsMinimizedToo() {
        assertNull(ClipboardText.localPrefill("看看 「订单 https://example.com/x」", rules, domains))
        assertEquals("保温杯 500ml", ClipboardText.localPrefill("https://example.com/x 「保温杯 500ml」", rules, domains))
        assertNull("首行含链接形态留空", ClipboardText.localPrefill("见 example.com\n第二行", rules, domains))
        assertEquals("普通首行", ClipboardText.localPrefill("普通首行\n第二行", rules, domains))
        val decision =
            ManualPasteAction.decide(
                ManualPasteAction.ENTRY_HOME,
                "私人 https://example.com/a?order=9 「订单 https://example.com/b」",
                rules,
                domains,
            )
        assertEquals(ManualPasteAction.SEARCH_PREFILL, decision.actionRaw)
        assertNull("手动粘贴的本地 30132 预填不带私人链接", decision.prefill)
    }

    @Test
    fun classifierUploadsTheMinimizedTitle() {
        val context = ClipboardContext(emptyList(), emptyList(), true, null, domains, hash = ClipboardFingerprint(salt)::hash)
        val verdict = ClipboardClassifier.classify("https://m.tb.cn/h.x 「耳机 https://example.com/p」", context, rules, 0L)
        assertEquals("https://m.tb.cn/h.x", verdict.uploadText)
        assertEquals(1, verdict.candidates.size)
    }

    // ---------------------------------------------------------------- 规则表

    @Test
    fun flagsFromConfigOnlyTighten() {
        val data =
            Json.parseToJsonElement(
                """{"features":{"flags":{"clipboard.enabled.jd":false,"clipboard.enabled.pdd":"false"}}}""",
            ) as JsonObject
        assertEquals(listOf("taobao", "pdd"), ClipboardLimits.fromConfigData(data).platforms)
        assertNull(ClipboardLimits.fromConfigData(null).platforms)
        val tightened = rules.tightened(ClipboardLimits.fromConfigData(data))
        assertFalse(ClipboardCandidate(ClipboardCandidate.KIND_URL, "jd", "https://u.jd.com/a").stillAllowed(tightened))
        assertTrue(ClipboardCandidate(ClipboardCandidate.KIND_URL, "taobao", "https://m.tb.cn/a").stillAllowed(tightened))
        val noTaobao = rules.tightened(ClipboardLimits(platforms = listOf("jd")))
        assertFalse(ClipboardCandidate(ClipboardCandidate.KIND_TPWD, "taobao", "￥AbCdEfGh12￥").stillAllowed(noTaobao))
    }

    @Test
    fun javaUriRejectsWhatItCannotParse() {
        assertEquals(ClipboardHostPolicy.UNPARSABLE, ClipboardHostPolicy.check("https://m.tb.cn/a b", rules).reasonRaw)
        assertEquals(ClipboardHostPolicy.UNPARSABLE, ClipboardHostPolicy.check("https://m.tb.cn/%zz", rules).reasonRaw)
    }

    // ---------------------------------------------------------------- C3 记录

    private fun records(
        store: MemoryClipboardHashStore = MemoryClipboardHashStore(),
        saltStore: MemoryClipboardSaltStore = MemoryClipboardSaltStore(),
    ) = ClipboardRecords(store, saltStore, clock = { 1_000L }, randomSalt = { salt.copyOf() })

    @Test
    fun withdrawDeletesTheSaltFirstAndKeepsRecordsWhenThatFails() {
        val store = MemoryClipboardHashStore()
        val saltStore = MemoryClipboardSaltStore()
        val records = records(store, saltStore)
        assertTrue(records.recordSelfWritten("样例自写"))
        saltStore.failWrites = true
        assertFalse("删盐没确认 → 不误报完成", records.withdraw())
        assertTrue("先删盐：删盐失败时记录还在（下次重试同一步骤）", store.dump().any { it.startsWith(ClipboardRecords.KEY_SELF) })
        saltStore.failWrites = false
        assertTrue(records.withdraw())
        assertTrue(store.dump().isEmpty())
        assertFalse(saltStore.hasSalt)
    }

    @Test
    fun dropLegacyRemovesOnlyTheOldUnsaltedKeys() {
        val store = MemoryClipboardHashStore()
        val plain = MessageDigest.getInstance("SHA-256").digest("x".toByteArray()).joinToString("") { "%02x".format(it) }
        store.write(ClipboardWriteLedger.KEY, plain)
        store.write(PromptHistory.KEY, "$plain@1")
        val records = records(store)
        assertTrue(records.recordSelfWritten("样例自写"))
        assertTrue(records.dropLegacy())
        assertFalse(store.dump().joinToString().contains(plain))
        assertTrue("加盐记录保留", records.isSelfWritten("样例自写"))
    }

    @Test
    fun aPromptStampFromAReplacedSaltIsNotRecordedUnderTheNewSalt() {
        val saltStore = MemoryClipboardSaltStore()
        val records = records(saltStore = saltStore)
        val view = checkNotNull(records.view())
        saltStore.setSalt(ByteArray(32) { (it + 0x20).toByte() })
        assertFalse(records.recordPromptedHash(view.fingerprint, view.fingerprint.hash("样例")))
    }

    @Test
    fun theSelfWriteLedgerPortIsSalted() {
        val store = MemoryClipboardHashStore()
        val records = records(store)
        val writer = ClipboardWriter(CountingSystem(), records)
        assertTrue(writer.write("样例口令"))
        assertTrue(store.dump().joinToString().contains(ClipboardFingerprint(salt).hash("样例口令")))
        assertFalse(store.dump().joinToString().contains(ClipboardText.sha256("样例口令")))
    }

    private class CountingSystem : SystemClipboard {
        override fun read(): ClipRead = ClipRead.Unavailable

        override fun write(
            text: String,
            label: String,
        ) = true
    }

    // ---------------------------------------------------------------- R1-04：迁移与清理只以确认落盘为准

    /**
     * 与 SharedPreferences 相同的内存 / 磁盘行为：写入先改内存（读得回），提交失败时磁盘不变；[failing] 里的键提交失败。[writes] 记提交次数。
     * 进程重启 = 只用 [disk] 重建（[rebuilt]）。
     */
    private class SplitHashStore(
        val disk: MutableMap<String, String> = mutableMapOf(),
    ) : ClipboardHashStore {
        private val memory = disk.toMutableMap()
        val failing = mutableSetOf<String>()
        var writes = 0

        override fun read(key: String): String? = memory[key]

        override fun write(
            key: String,
            value: String?,
        ): Boolean {
            writes += 1
            if (value == null) memory.remove(key) else memory[key] = value
            if (key in failing) return false
            if (value == null) disk.remove(key) else disk[key] = value
            return true
        }

        fun rebuilt() = SplitHashStore(disk.toMutableMap())
    }

    @Test
    fun r1_04_aLegacyDeleteThatFailedAfterMemoryChangedIsResubmittedAndOnlyDiskConfirmationCompletesIt() {
        val plain = MessageDigest.getInstance("SHA-256").digest("x".toByteArray()).joinToString("") { "%02x".format(it) }
        val store = SplitHashStore(mutableMapOf(ClipboardWriteLedger.KEY to plain))
        val records = ClipboardRecords(store, MemoryClipboardSaltStore(), clock = { 1_000L }, randomSalt = { salt.copyOf() })
        store.failing += ClipboardWriteLedger.KEY
        assertFalse("首次删除没落盘：未完成", records.dropLegacy())
        assertNull("内存里已经没有这个键", store.read(ClipboardWriteLedger.KEY))
        assertEquals("磁盘上还在", plain, store.disk[ClipboardWriteLedger.KEY])
        val before = store.writes
        assertFalse("仍失败：仍未完成（不因内存里键已不在就误报）", records.dropLegacy())
        assertTrue("重试重新提交了删除", store.writes > before)
        store.failing.clear()
        assertTrue(records.dropLegacy())
        assertFalse("确认落盘后磁盘上没有旧未加盐记录", store.disk.containsKey(ClipboardWriteLedger.KEY))
        val restarted = store.rebuilt()
        assertFalse(restarted.disk.values.any { it.contains(plain) })
    }

    @Test
    fun r1_04_aSaltIdWhoseCommitFailedIsRewrittenBeforeTheNextRecordInsteadOfTrustingMemory() {
        val store = SplitHashStore()
        val saltStore = MemoryClipboardSaltStore()
        val records = ClipboardRecords(store, saltStore, clock = { 1_000L }, randomSalt = { salt.copyOf() })
        store.failing += ClipboardRecords.KEY_SALT_ID
        assertFalse("saltId 没落盘：自写记不上", records.recordSelfWritten("样例自写"))
        store.failing.clear()
        assertTrue(records.recordSelfWritten("样例自写"))
        // 进程重启：只看磁盘
        val restarted = ClipboardRecords(store.rebuilt(), saltStore, clock = { 1_000L }, randomSalt = { salt.copyOf() })
        assertTrue("磁盘上的 saltId 与记录属于同一个盐", restarted.isSelfWritten("样例自写"))
    }
}
