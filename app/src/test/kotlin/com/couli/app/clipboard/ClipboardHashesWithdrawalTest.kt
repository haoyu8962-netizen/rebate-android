package com.couli.app.clipboard

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.config.CouliPreferenceNamespaces
import com.couli.core.privacy.CredentialCleanerRegistry
import com.couli.core.privacy.PrivacyDecision
import com.couli.core.privacy.PrivacyGate
import com.couli.core.privacy.SharedPreferencesPrivacyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 第 3 轮评审 A-R3-02（APP-03b 共性第 11 条）：撤回同意时剪贴板 hash 的删除要确认落盘（commit() 返回 true）才算成功；没落盘就抛错，
 * 撤回清理保留「待清理」标记，同一进程读凭据前、下次冷启动（新进程）时重试，直到确认删除。设备级开关不在清理范围。
 * 生产接线同 AppModule：CredentialCleanerRegistry 里登记 ClipboardPreferences.clearHashes()。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class ClipboardHashesWithdrawalTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clipboardPrefs get() = context.getSharedPreferences(CouliPreferenceNamespaces.CLIPBOARD, Context.MODE_PRIVATE)

    /** 包一层真实的 SharedPreferences：[failCommit] 为 true 时 Editor.commit() 返回 false 且不落盘（模拟写盘失败）。 */
    private class FlakyPrefs(
        private val real: SharedPreferences,
    ) : SharedPreferences by real {
        var failCommit = true
        var commits = 0

        override fun edit(): SharedPreferences.Editor {
            val editor = real.edit()
            return object : SharedPreferences.Editor by editor {
                override fun commit(): Boolean {
                    commits += 1
                    return if (failCommit) false else editor.commit()
                }

                override fun apply() {
                    if (!failCommit) editor.apply()
                }
            }
        }
    }

    private lateinit var flaky: FlakyPrefs

    @Before
    fun reset() {
        for (name in listOf(CouliPreferenceNamespaces.PRIVACY_DECISION, CouliPreferenceNamespaces.CLIPBOARD)) {
            context
                .getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
        clipboardPrefs
            .edit()
            .putString("hash.self_written", "aaa")
            .putString("hash.prompted", "bbb@1")
            .putBoolean("device_auto_detect", false)
            .commit()
        flaky = FlakyPrefs(clipboardPrefs)
    }

    /** 每次都是新的 Gate（= 新进程），复用同一份磁盘记录；清理项与 AppModule 同一个：ClipboardPreferences.clearHashes()。 */
    private fun gate(): PrivacyGate {
        val registry = CredentialCleanerRegistry().apply { register(CLEANER) { ClipboardPreferences(flaky).clearHashes() } }
        return PrivacyGate(SharedPreferencesPrivacyStore(context), clock = { 1_000L }, bundledPolicyVersion = "1").apply {
            withdrawalCleanup = { registry.cleanAll() }
        }
    }

    private fun hashesOnDisk(): Set<String> =
        clipboardPrefs.all.keys
            .filter { it.startsWith("hash.") }
            .toSet()

    private fun withdrawExpectingFailure(gate: PrivacyGate) {
        try {
            gate.withdraw()
            fail("hash 删除没落盘：撤回清理要报失败，不能误报完成")
        } catch (expected: IllegalStateException) {
            assertEquals("剪贴板 hash 记录删除未落盘", expected.message)
        }
    }

    @Test
    fun deleteNotCommittedKeepsThePendingMarkAndTheHashes() {
        val first = gate()
        first.agree()
        withdrawExpectingFailure(first)
        assertTrue("确实尝试了落盘", flaky.commits > 0)
        assertTrue("待清理标记保留", SharedPreferencesPrivacyStore(context).withdrawalCleanupPending)
        assertTrue(first.credentialAccess.cleanupPending)
        assertEquals("hash 仍在磁盘上（没被误报为已清）", setOf("hash.self_written", "hash.prompted"), hashesOnDisk())
        assertFalse("清理未完成：不得读凭据", first.credentialAccess.readable())
        assertEquals("隐私决定仍记为基本模式", PrivacyDecision.BASIC_MODE, first.state().decision)
    }

    @Test
    fun retryInTheSameProcessSucceedsOnceTheDeleteIsCommitted() {
        val first = gate()
        first.agree()
        withdrawExpectingFailure(first)
        assertFalse("仍失败：重试也不报成功", first.credentialAccess.readable())
        assertTrue(SharedPreferencesPrivacyStore(context).withdrawalCleanupPending)

        flaky.failCommit = false
        assertTrue("重试成功", first.credentialAccess.readable())
        assertTrue("hash 已删除落盘", hashesOnDisk().isEmpty())
        assertFalse("确认删除后才去掉待清理标记", SharedPreferencesPrivacyStore(context).withdrawalCleanupPending)
        assertFalse("设备级开关不在撤回清理范围", clipboardPrefs.getBoolean("device_auto_detect", true))
    }

    @Test
    fun processRestartRetriesUntilTheDeleteIsCommitted() {
        val first = gate()
        first.agree()
        withdrawExpectingFailure(first)

        // 重启 1：仍写不进盘 → 标记保留，hash 仍在
        val second = gate()
        val commitsBefore = flaky.commits
        second.coldStart()
        assertTrue("冷启动重试了删除", flaky.commits > commitsBefore)
        assertTrue(SharedPreferencesPrivacyStore(context).withdrawalCleanupPending)
        assertEquals(setOf("hash.self_written", "hash.prompted"), hashesOnDisk())
        assertFalse(second.credentialAccess.readable())

        // 重启 2：写盘恢复 → 删除落盘，标记去掉
        flaky.failCommit = false
        val third = gate()
        third.coldStart()
        assertTrue("hash 已删除落盘", hashesOnDisk().isEmpty())
        assertFalse(SharedPreferencesPrivacyStore(context).withdrawalCleanupPending)
        assertTrue(third.credentialAccess.readable())
        assertEquals(PrivacyDecision.BASIC_MODE, third.state().decision)
    }

    private companion object {
        const val CLEANER = "clipboard_hashes"
    }
}
