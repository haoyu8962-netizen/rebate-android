package com.couli.app.clipboard

import android.content.SharedPreferences
import com.couli.core.clipboard.ClipRead
import com.couli.core.clipboard.ClipboardRecords
import com.couli.core.clipboard.ClipboardWriteLedger
import com.couli.core.clipboard.ClipboardWriter
import com.couli.core.clipboard.SystemClipboard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FU-AND-0607 第 2 轮 F1-03（APP-07 共性第 23、25 条）：自写 hash 经真实 [ClipboardPreferences] 写入，必须以同步 commit() 的结果
 * 判断是否落盘。SharedPreferences 磁盘写失败时内存照样更新、读得回——读回不能当成功；记不下就不写系统剪贴板。
 */
class ClipboardHashCommitTest {
    private val link = "https://e.tb.cn/h.AbCdEf?tk=Xy12"

    /** 包装的 SharedPreferences：编辑先进内存（与系统实现一致），commit() 按 [diskOk] 决定是否写进「磁盘」并返回结果。 */
    private class FlakyPrefs(
        val disk: MutableMap<String, Any?> = mutableMapOf(),
        var diskOk: Boolean = true,
    ) : SharedPreferences {
        private val memory = disk.toMutableMap()
        var commits = 0
        var applies = 0

        /** 还能成功落盘的提交次数（之后的提交只改内存、返回 false；R1-04 同类排查用）。 */
        var okCommits = Int.MAX_VALUE

        override fun getAll(): MutableMap<String, *> = memory.toMutableMap()

        override fun getString(
            key: String?,
            defValue: String?,
        ): String? = memory[key] as String? ?: defValue

        override fun getStringSet(
            key: String?,
            defValues: MutableSet<String>?,
        ): MutableSet<String>? = defValues

        override fun getInt(
            key: String?,
            defValue: Int,
        ): Int = memory[key] as Int? ?: defValue

        override fun getLong(
            key: String?,
            defValue: Long,
        ): Long = memory[key] as Long? ?: defValue

        override fun getFloat(
            key: String?,
            defValue: Float,
        ): Float = memory[key] as Float? ?: defValue

        override fun getBoolean(
            key: String?,
            defValue: Boolean,
        ): Boolean = memory[key] as Boolean? ?: defValue

        override fun contains(key: String?): Boolean = key in memory

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val changes = mutableMapOf<String, Any?>()
            private var clear = false

            private fun put(
                key: String?,
                value: Any?,
            ): SharedPreferences.Editor {
                changes[key.orEmpty()] = value
                return this
            }

            override fun putString(
                key: String?,
                value: String?,
            ) = put(key, value)

            override fun putStringSet(
                key: String?,
                values: MutableSet<String>?,
            ) = put(key, values)

            override fun putInt(
                key: String?,
                value: Int,
            ) = put(key, value)

            override fun putLong(
                key: String?,
                value: Long,
            ) = put(key, value)

            override fun putFloat(
                key: String?,
                value: Float,
            ) = put(key, value)

            override fun putBoolean(
                key: String?,
                value: Boolean,
            ) = put(key, value)

            override fun remove(key: String?) = put(key, null)

            override fun clear(): SharedPreferences.Editor {
                clear = true
                return this
            }

            private fun mergeInto(target: MutableMap<String, Any?>) {
                if (clear) target.clear()
                changes.forEach { (k, v) -> if (v == null) target.remove(k) else target[k] = v }
            }

            override fun commit(): Boolean {
                commits += 1
                mergeInto(memory)
                val ok = diskOk && okCommits > 0
                if (okCommits > 0) okCommits -= 1
                if (ok) mergeInto(disk)
                return ok
            }

            override fun apply() {
                applies += 1
                mergeInto(memory)
                if (diskOk) mergeInto(disk)
            }
        }
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

    @Test
    fun f1_03_aHashKeptInMemoryButNotCommittedDoesNotTouchTheSystemClipboard() {
        val prefs = FlakyPrefs(diskOk = false)
        val ledger = ClipboardWriteLedger(store = ClipboardPreferences(prefs).hashes)
        val system = CountingClipboard()

        assertFalse("commit() 返回 false：写入失败", ClipboardWriter(system, ledger).write(link))
        assertEquals("不写系统剪贴板", 0, system.writes)
        assertTrue("内存里读得回（正是不能用读回判断的原因）", ledger.contains(link))
        assertEquals("同步提交，不走异步 apply", 0, prefs.applies)

        // 重建存储（进程重启）：磁盘没有这条；系统剪贴板也没写过，所以不会出现未登记的自写内容被识别上传
        val rebuilt = ClipboardWriteLedger(store = ClipboardPreferences(FlakyPrefs(prefs.disk)).hashes)
        assertFalse(rebuilt.contains(link))
    }

    @Test
    fun f1_03_aCommittedHashIsWrittenAndSurvivesARebuild() {
        val prefs = FlakyPrefs()
        val system = CountingClipboard()
        assertTrue(ClipboardWriter(system, ClipboardWriteLedger(store = ClipboardPreferences(prefs).hashes)).write(link))
        assertEquals(1, system.writes)
        assertTrue("重建后仍能排除自写内容", ClipboardWriteLedger(store = ClipboardPreferences(FlakyPrefs(prefs.disk)).hashes).contains(link))
    }

    /**
     * R1-04 同类（撤回清理）：第一次删记录的提交失败时内存里的键已删、磁盘还在；重试不能只按内存列键（那样提交的是空改动，磁盘不变），
     * 必须重新提交已知记录键的删除，确认落盘后磁盘上才没有记录。
     */
    @Test
    fun r1_04_aWithdrawalRetryResubmitsRecordDeletesThatMemoryAlreadyForgot() {
        val salted = listOf(ClipboardRecords.KEY_SELF, ClipboardRecords.KEY_PROMPTED, ClipboardRecords.KEY_SALT_ID)
        val keys = salted + ClipboardRecords.LEGACY_KEYS
        val prefs = FlakyPrefs(keys.associate { "hash.$it" to "样例值" }.toMutableMap<String, Any?>())
        prefs.okCommits = 1
        assertTrue(runCatching { ClipboardPreferences(prefs).clearHashes() }.isFailure)
        assertTrue("内存里已删", prefs.all.keys.none { it.startsWith("hash.") })
        assertTrue("磁盘还在", prefs.disk.keys.any { it.startsWith("hash.") })
        prefs.okCommits = Int.MAX_VALUE
        ClipboardPreferences(prefs).clearHashes()
        assertTrue("确认落盘后磁盘上没有记录", prefs.disk.keys.none { it.startsWith("hash.") })
    }
}
