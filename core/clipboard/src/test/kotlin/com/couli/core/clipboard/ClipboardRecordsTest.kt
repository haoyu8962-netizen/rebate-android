package com.couli.core.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本机记录与片段抽取（brief §2.2、§2.3；G04、G08）：只存 hash 与时间（存储里没有原文）；24 小时去重；撤回同意清空；上传内容只含链接与「」标题；
 * 生产 hash 为 SHA-256。
 */
class ClipboardRecordsTest {
    private val raw = "分享 https://e.tb.cn/h.AbCdEf?tk=Xy12 「降噪蓝牙耳机」 私人备注：周五前买"

    @Test
    fun ledgerAndHistoryStoreOnlyHashes() {
        val store = MemoryClipboardHashStore()
        val ledger = ClipboardWriteLedger(store = store)
        var now = 1_000L
        val history = PromptHistory({ now }, store)
        ledger.record(raw)
        history.record(ClipboardText.sha256(raw))
        val stored = listOf(store.read("self_written"), store.read("prompted")).joinToString("|")
        assertFalse("存储里不能有原文", stored.contains("e.tb.cn") || stored.contains("降噪") || stored.contains("备注"))
        assertTrue(ledger.contains(raw))
        assertEquals(ClipboardText.sha256(raw), history.stamps().single().hash)
        // 新实例读同一份存储（跨进程保留）
        assertTrue(ClipboardWriteLedger(store = store).contains(raw))
        now += 1
        assertEquals(1, PromptHistory({ now }, store).stamps().size)
    }

    @Test
    fun clearRemovesEverything() {
        val store = MemoryClipboardHashStore()
        val ledger = ClipboardWriteLedger(store = store)
        val history = PromptHistory({ 0L }, store)
        ledger.record(raw)
        history.record("h")
        ledger.clear()
        history.clear()
        assertFalse(ledger.contains(raw))
        assertTrue(history.stamps().isEmpty())
        assertNull(store.read("self_written"))
        assertNull(store.read("prompted"))
    }

    @Test
    fun rewritingTheSameTextMovesItToNewest() {
        val ledger = ClipboardWriteLedger(capacity = 2)
        ledger.record("a")
        ledger.record("b")
        ledger.record("a")
        ledger.record("c")
        assertTrue("重复写入同一内容移到最新", ledger.contains("a"))
        assertFalse(ledger.contains("b"))
    }

    @Test
    fun promptWindowIsStrictlyLessThan24Hours() {
        var now = 0L
        val history = PromptHistory({ now })
        history.record("h")
        val context = { ClipboardContext(emptyList(), history.stamps(), true, null, emptyList()) { "h" } }
        now = ClipboardClassifier.PROMPT_WINDOW_MS - 1
        assertEquals(
            ClipboardClassifier.REASON_PROMPTED_RECENTLY,
            ClipboardClassifier.classify("https://e.tb.cn/x1", context(), ClipboardRules.defaults, now).reasonRaw,
        )
        now = ClipboardClassifier.PROMPT_WINDOW_MS
        assertEquals(
            ClipboardClassifier.KIND_PRODUCT,
            ClipboardClassifier.classify("https://e.tb.cn/x1", context(), ClipboardRules.defaults, now).kindRaw,
        )
    }

    @Test
    fun recordsWithin24HoursAreNotEvictedByCount() {
        var now = 0L
        val history = PromptHistory({ now })
        // A-R1-07：24 小时内对 51 段不同内容出过提示条，第 1 段仍在记录里（不被条数上限挤掉）
        repeat(FIFTY_ONE) { index ->
            now = index * MINUTE_MS
            history.record("h$index")
        }
        assertEquals(FIFTY_ONE, history.stamps().size)
        val context = ClipboardContext(emptyList(), history.stamps(), true, null, emptyList()) { "h0" }
        assertEquals(
            ClipboardClassifier.REASON_PROMPTED_RECENTLY,
            ClipboardClassifier.classify("https://e.tb.cn/x1", context, ClipboardRules.defaults, now).reasonRaw,
        )
        // 只按 24 小时到期清理：下一次记录时，已满 24 小时的那几条被删掉
        now = ClipboardClassifier.PROMPT_WINDOW_MS + MINUTE_MS
        history.record("fresh")
        val kept = history.stamps().map { it.hash }
        assertFalse(kept.contains("h0"))
        assertFalse(kept.contains("h1"))
        assertTrue(kept.contains("h2"))
        assertEquals(FIFTY_ONE - 2 + 1, kept.size)
    }

    @Test
    fun uploadTextCarriesOnlyLinksAndTitle() {
        val verdict =
            ClipboardClassifier.classify(
                raw,
                ClipboardContext(emptyList(), emptyList(), true, null, emptyList()),
                ClipboardRules.defaults,
                0L,
            )
        assertEquals("https://e.tb.cn/h.AbCdEf?tk=Xy12\n降噪蓝牙耳机", verdict.uploadText)
        assertFalse(verdict.uploadText.orEmpty().contains("备注"))
        assertEquals(64, verdict.hash?.length)
    }

    @Test
    fun inviteLandingLinkIsNotAProductLink() {
        val context = ClipboardContext(emptyList(), emptyList(), true, null, listOf("share.example.test"))
        val verdict = ClipboardClassifier.classify("好友邀请你 https://share.example.test/i/K7M2QX", context, ClipboardRules.defaults, 0L)
        assertEquals(ClipboardClassifier.KIND_INVITE, verdict.kindRaw)
        assertEquals("K7M2QX", verdict.inviteCode)
        // 子域、端口不认（CLIP-18 未定时从严）
        assertFalse(ClipboardClassifier.inviteLanding("https://x.share.example.test/i/K7M2QX", listOf("share.example.test")))
        assertFalse(ClipboardClassifier.inviteLanding("https://share.example.test:8443/i/K7M2QX", listOf("share.example.test")))
    }

    @Test
    fun manualPasteWithoutLinkStaysLocal() {
        val decision = ManualPasteAction.decide(ManualPasteAction.ENTRY_HOME, "￥Zx8Cv7Bn6Mq￥「全脂纯牛奶 250ml」", ClipboardRules.defaults)
        assertEquals(ManualPasteAction.SEARCH_PREFILL, decision.actionRaw)
        assertNull("没抽到链接不上传", decision.uploadText)
        assertEquals("全脂纯牛奶 250ml", decision.prefill)
        assertEquals("error.30132", decision.textKey)
    }

    /** A-R3-01：手动粘贴与自动识别同一套上传过滤，share_domains 下的邀请落地页不上传，只本地抽码。 */
    @Test
    fun manualPasteOfAnInviteLandingStaysLocal() {
        val domains = listOf("share.example.test")
        for (entry in listOf(ManualPasteAction.ENTRY_HOME, ManualPasteAction.ENTRY_SEARCH_TYPING)) {
            val text = "邀请码 K7M2QX https://share.example.test/i/K7M2QX"
            val landing = ManualPasteAction.decide(entry, text, ClipboardRules.defaults, domains)
            assertEquals(ManualPasteAction.INVITE, landing.actionRaw)
            assertEquals("K7M2QX", landing.code)
            assertNull("邀请落地页不上传", landing.uploadText)
            val keyword = ManualPasteAction.decide(entry, "邀请码 K7M2QX", ClipboardRules.defaults, domains)
            assertEquals(ManualPasteAction.INVITE, keyword.actionRaw)
            assertNull(keyword.uploadText)
        }
    }

    @Test
    fun manualPasteOfProductAndInviteUploadsOnlyTheProductPart() {
        val decision =
            ManualPasteAction.decide(
                ManualPasteAction.ENTRY_HOME,
                "【淘宝】https://e.tb.cn/h.AbCdEf?tk=Xy12 「降噪蓝牙耳机」 好友邀请 https://share.example.test/i/K7M2QX 邀请码 K7M2QX",
                ClipboardRules.defaults,
                listOf("share.example.test"),
            )
        assertEquals(ManualPasteAction.PARSE, decision.actionRaw)
        assertEquals("只上传商品链接与「」标题", "https://e.tb.cn/h.AbCdEf?tk=Xy12\n降噪蓝牙耳机", decision.uploadText)
        assertFalse(decision.uploadText.orEmpty().contains("share.example.test"))
        assertFalse(decision.uploadText.orEmpty().contains("K7M2QX"))
    }

    @Test
    fun configCannotOverrideBundledRules() {
        val raw =
            kotlinx.serialization.json.Json
                .parseToJsonElement("""{"min_length":1,"exclude_words":["x"]}""")
        assertEquals(ClipboardRules.defaults, ClipboardRules.fromConfig(raw))
    }

    private companion object {
        const val FIFTY_ONE = 51
        const val MINUTE_MS = 60_000L
    }
}
