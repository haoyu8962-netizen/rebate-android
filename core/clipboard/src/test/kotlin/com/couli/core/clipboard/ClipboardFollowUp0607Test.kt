package com.couli.core.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FU-AND-0607：APP-07 共性第 22 条（手动粘贴商品入口同样走本地长度 / 形态过滤）、第 23 条（自写 hash 记不下就不写系统剪贴板）。
 * 撤修复记录见 NATIVE/FU-AND-0607/revert-checks.txt。
 */
class ClipboardFollowUp0607Test {
    private val link = "https://e.tb.cn/h.AbCdEf?tk=Xy12"

    @Test
    fun lesson22_manualPasteAppliesTheSameLengthAndShapeFilter() {
        val rules = ClipboardRules.defaults
        assertEquals(ManualPasteAction.PARSE, ManualPasteAction.decide(ManualPasteAction.ENTRY_HOME, link, rules).actionRaw)
        val tooLong = link + " " + "很长的说明".repeat(120)
        assertNotEquals("过长不上传", ManualPasteAction.PARSE, ManualPasteAction.decide(ManualPasteAction.ENTRY_HOME, tooLong, rules).actionRaw)
        val excluded = rules.copy(excludedWords = listOf("测试"))
        assertNotEquals(
            "排除词不上传",
            ManualPasteAction.PARSE,
            ManualPasteAction.decide(ManualPasteAction.ENTRY_SEARCH_TYPING, "$link 测试", excluded).actionRaw,
        )
        val strict = rules.copy(minLength = 40)
        assertNotEquals("过短不上传", ManualPasteAction.PARSE, ManualPasteAction.decide(ManualPasteAction.ENTRY_HOME, link, strict).actionRaw)
    }

    private class CountingClipboard : SystemClipboard {
        var writes = 0

        override fun read(): ClipRead = ClipRead.Unavailable

        override fun write(
            text: String,
            label: String,
        ): Boolean {
            writes += 1
            return true
        }
    }

    /** 落不下的存储：写入抛错。 */
    private class BrokenStore : ClipboardHashStore {
        override fun read(key: String): String? = null

        override fun write(
            key: String,
            value: String?,
        ): Boolean = error("storage unavailable")
    }

    /** 内存里记下了（读得回）但没确认持久化的存储：读回有不算成功（APP-07 共性第 25 条）。 */
    private class LossyStore : ClipboardHashStore {
        private val memory = MemoryClipboardHashStore()

        override fun read(key: String): String? = memory.read(key)

        override fun write(
            key: String,
            value: String?,
        ): Boolean {
            memory.write(key, value)
            return false
        }
    }

    @Test
    fun lesson23_aWriteWhoseHashCannotBeRecordedDoesNotTouchTheSystemClipboard() {
        listOf(BrokenStore(), LossyStore()).forEach { store ->
            val system = CountingClipboard()
            val writer = ClipboardWriter(system, ClipboardWriteLedger(store = store))
            assertFalse("记不下 hash：返回失败", writer.write(link))
            assertEquals("不写系统剪贴板", 0, system.writes)
        }
        val system = CountingClipboard()
        val ledger = ClipboardWriteLedger()
        assertTrue(ClipboardWriter(system, ledger).write(link))
        assertEquals(1, system.writes)
        assertTrue(ledger.contains(link))
    }
}
