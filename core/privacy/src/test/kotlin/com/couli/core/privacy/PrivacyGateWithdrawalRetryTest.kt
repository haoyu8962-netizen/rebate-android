package com.couli.core.privacy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.config.AppEnvironment
import com.couli.core.config.CouliPreferenceNamespaces
import com.couli.core.config.SharedPreferencesNamespaceCleaner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 评审 R1-01（S0）：撤回同意时清理失败，「待清理」标记落盘；下次冷启动、每次读凭据前、每次初始化前重试，直到成功；
 * 清理完成前不得读取任何会话凭据、不初始化。隐私决定照样记为基本模式。
 */
@RunWith(AndroidJUnit4::class)
class PrivacyGateWithdrawalRetryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val session get() = context.getSharedPreferences(CouliPreferenceNamespaces.SESSION, Context.MODE_PRIVATE)
    private var cleanupFails = true
    private var cleanupCalls = 0

    @Before
    fun reset() {
        for (name in listOf(CouliPreferenceNamespaces.PRIVACY_DECISION, CouliPreferenceNamespaces.SESSION)) {
            context
                .getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
        session
            .edit()
            .putString(PlaceholderCredentialKeys.ACCESS_TOKEN, "t")
            .putString(PlaceholderCredentialKeys.DEVICE_ID, "d")
            .commit()
    }

    /** 每次都是新的 Gate（= 新进程），复用同一份 SharedPreferences。清理用真实的占位清理项，可注入失败。 */
    private fun gate(): PrivacyGate {
        val registry = CredentialCleanerRegistry().apply { registerPlaceholderCredentialCleaners(context) }
        return PrivacyGate(SharedPreferencesPrivacyStore(context), clock = { 1_000L }, bundledPolicyVersion = "1").apply {
            withdrawalCleanup = {
                cleanupCalls += 1
                check(!cleanupFails) { "删除失败" }
                registry.cleanAll()
            }
        }
    }

    private fun withdrawExpectingFailure(gate: PrivacyGate) {
        try {
            gate.withdraw()
            fail("清理失败要抛给调用方")
        } catch (expected: IllegalStateException) {
            assertEquals("删除失败", expected.message)
        }
    }

    @Test
    fun failedCleanupIsPersistedAndRetriedOnNextColdStartUntilItSucceeds() {
        val first = gate()
        first.agree()
        withdrawExpectingFailure(first)
        val store = SharedPreferencesPrivacyStore(context)
        assertTrue("待清理标记落盘", store.withdrawalCleanupPending)
        assertEquals("隐私决定仍记为基本模式", PrivacyDecision.BASIC_MODE, first.state().decision)
        assertTrue("令牌还在磁盘上", session.contains(PlaceholderCredentialKeys.ACCESS_TOKEN))
        assertFalse("清理未完成：不得读凭据", first.credentialAccess.readable())

        // 重启 1：仍失败 → 标记保留，仍不可读
        val second = gate()
        val callsBefore = cleanupCalls
        second.coldStart()
        assertEquals("冷启动重试一次", callsBefore + 1, cleanupCalls)
        assertTrue(store.withdrawalCleanupPending)
        assertFalse(second.credentialAccess.readable())
        assertEquals(PrivacyDecision.BASIC_MODE, second.state().decision)

        // 重启 2：清理成功 → 凭据删掉、标记去掉、可读
        cleanupFails = false
        val third = gate()
        third.coldStart()
        assertFalse(store.withdrawalCleanupPending)
        assertFalse(session.contains(PlaceholderCredentialKeys.ACCESS_TOKEN))
        assertFalse(session.contains(PlaceholderCredentialKeys.DEVICE_ID))
        assertTrue(third.credentialAccess.readable())
        val callsAfterDone = cleanupCalls
        third.credentialAccess.readable()
        assertEquals("清理完成后不再重复清理", callsAfterDone, cleanupCalls)
    }

    @Test
    fun everyCredentialReadRetriesCleanupInTheSameProcess() {
        val gate = gate()
        gate.agree()
        withdrawExpectingFailure(gate)
        assertFalse(gate.credentialAccess.readable())
        assertFalse(gate.credentialAccess.readable())
        cleanupFails = false
        assertTrue("读凭据前重试成功", gate.credentialAccess.readable())
        assertFalse(SharedPreferencesPrivacyStore(context).withdrawalCleanupPending)
        assertFalse(session.contains(PlaceholderCredentialKeys.ACCESS_TOKEN))
    }

    @Test
    fun reconsentDoesNotInitializeUntilCleanupCompletes() {
        val steps = mutableListOf<String>()
        val gate =
            gate().apply {
                retryScheduler = RetryScheduler { _, _ -> }
                PrivacyGate.INIT_ORDER.forEach { name -> registerInitStep(name) { steps += name } }
            }
        gate.agree()
        withdrawExpectingFailure(gate)
        gate.requestFullFunction()
        gate.agree()
        assertTrue("决定可以记为同意", gate.state().fullyConsented)
        gate.onConsented()
        assertTrue("清理未完成：不初始化（设备注册会读写 device_id）", steps.isEmpty())

        cleanupFails = false
        gate.onConsented()
        assertEquals("清理完成后初始化一次", PrivacyGate.INIT_ORDER, steps)
        assertFalse(session.contains(PlaceholderCredentialKeys.DEVICE_ID))
    }

    @Test
    fun processKilledBeforeCleanupRanIsRecoveredOnColdStart() {
        // 模拟撤回落盘了标记与基本模式，但清理还没执行进程就被杀
        val store = SharedPreferencesPrivacyStore(context)
        store.decisionRaw = PrivacyDecision.BASIC_MODE.raw
        store.basicMode = true
        store.withdrawalCleanupPending = true
        cleanupFails = false
        val gate = gate()
        gate.coldStart()
        assertEquals("冷启动补做清理", 1, cleanupCalls)
        assertFalse(store.withdrawalCleanupPending)
        assertTrue(session.all.isEmpty())
        assertEquals(PrivacyDecision.BASIC_MODE, gate.state().decision)
        assertFalse("基本模式冷启动不弹", gate.state().mustPrompt)
    }

    @Test
    fun pendingMarkSurvivesEnvironmentSwitch() {
        val gate = gate()
        gate.agree()
        withdrawExpectingFailure(gate)
        SharedPreferencesNamespaceCleaner(context).clean(AppEnvironment.TEST, AppEnvironment.STAGING)
        assertTrue("待清理标记与隐私决定同存，切环境不丢", SharedPreferencesPrivacyStore(context).withdrawalCleanupPending)
    }
}
