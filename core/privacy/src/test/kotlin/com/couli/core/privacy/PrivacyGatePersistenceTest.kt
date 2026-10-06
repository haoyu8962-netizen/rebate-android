package com.couli.core.privacy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.config.AppEnvironment
import com.couli.core.config.CouliPreferenceNamespaces
import com.couli.core.config.SharedPreferencesNamespaceCleaner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * APP-02b 本端补充：持久化（SharedPreferences）、切换环境不误删、撤回同意不漏删、撤回作废旧重试、记录损坏的保守处理。
 * 向量本身见 PrivacyGateVectorTest（Codex，不改）。
 */
@RunWith(AndroidJUnit4::class)
class PrivacyGatePersistenceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var now = 1_000L

    @Before
    fun reset() {
        context
            .getSharedPreferences(CouliPreferenceNamespaces.PRIVACY_DECISION, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context
            .getSharedPreferences(CouliPreferenceNamespaces.SESSION, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun gate(
        store: PrivacyStore = SharedPreferencesPrivacyStore(context),
        version: String = "10",
    ): PrivacyGate = PrivacyGate(store, clock = { now }, bundledPolicyVersion = version)

    @Test
    fun decisionSurvivesRestartAndEnvironmentSwitch() {
        gate().apply {
            agree()
            assertTrue(state().fullyConsented)
        }
        SharedPreferencesNamespaceCleaner(context).clean(AppEnvironment.TEST, AppEnvironment.STAGING)
        val restarted = gate()
        restarted.coldStart()
        assertTrue("切换环境不清本机隐私决定", restarted.state().fullyConsented)
        val store = SharedPreferencesPrivacyStore(context)
        assertEquals("10", store.consentedVersion)
        assertEquals(1_000L, store.consentedAtMillis)
    }

    @Test
    fun withdrawClearsSessionCredentialsKeepsBasicModeAcrossRestart() {
        val session = context.getSharedPreferences(CouliPreferenceNamespaces.SESSION, Context.MODE_PRIVATE)
        session
            .edit()
            .apply {
                PlaceholderCredentialKeys
                    .run {
                        listOf(
                            ACCESS_TOKEN,
                            REFRESH_TOKEN,
                            H5_TOKEN,
                            DEVICE_ID,
                            INSTALL_SECRET,
                        )
                    }.forEach { putString(it, "x") }
                putString("unrelated", "keep")
            }.commit()
        val registry = CredentialCleanerRegistry().apply { registerPlaceholderCredentialCleaners(context) }
        assertEquals(CredentialCleanerRegistry.REQUIRED, registry.registeredNames())

        val gate = gate().apply { withdrawalCleanup = { registry.cleanAll() } }
        gate.agree()
        gate.withdraw()

        assertEquals(setOf("unrelated"), session.all.keys)
        val restarted = gate()
        restarted.coldStart()
        assertEquals(PrivacyDecision.BASIC_MODE, restarted.state().decision)
        assertFalse("基本模式冷启动不自动弹", restarted.state().mustPrompt)
    }

    @Test
    fun withdrawWithoutCleanupWiringFailsButStillEntersBasicMode() {
        val gate = gate()
        gate.agree()
        try {
            gate.withdraw()
            fail("没接清理入口必须报错")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("withdrawalCleanup"))
        }
        assertEquals(PrivacyDecision.BASIC_MODE, gate.state().decision)
    }

    @Test
    fun withdrawCancelsPendingDeviceRetriesAndReconsentInitializesAgain() {
        val jobs = mutableListOf<() -> Unit>()
        var attempts = 0
        var pushes = 0
        val gate =
            gate().apply {
                withdrawalCleanup = {}
                retryScheduler = RetryScheduler { _, action -> jobs += action }
                registerInitStep(PrivacyGate.DEVICE_REGISTER) {
                    attempts += 1
                    error("offline")
                }
                registerInitStep(PrivacyGate.PUSH) { pushes += 1 }
            }
        gate.agree()
        gate.onConsented()
        assertEquals(1, attempts)
        assertEquals("设备注册失败不阻塞推送", 1, pushes)
        assertEquals(1, jobs.size)

        gate.withdraw()
        jobs.removeAt(0)()
        assertEquals("撤回后旧重试作废", 1, attempts)

        gate.requestFullFunction()
        gate.agree()
        gate.onConsented()
        assertEquals("撤回后再同意：重新初始化", 2, attempts)
        assertEquals(2, pushes)
    }

    @Test
    fun corruptOrContradictoryRecordsAreHandledConservatively() {
        val store = SharedPreferencesPrivacyStore(context)
        store.decisionRaw = "garbage"
        assertEquals(PromptStage.FIRST, gate(store).state().promptStage)

        store.decisionRaw = "consented"
        store.consentedVersion = null
        assertEquals("同意却缺版本：重新同意", PromptStage.FIRST, gate(store).state().promptStage)

        store.consentedVersion = "x"
        assertEquals(PromptStage.FIRST, gate(store).state().promptStage)

        store.consentedVersion = "10"
        store.basicMode = true
        val contradictory = gate(store).state()
        assertEquals("决定与基本模式矛盾：按基本模式", PrivacyDecision.BASIC_MODE, contradictory.decision)
        assertFalse(contradictory.mustPrompt)
    }

    @Test
    fun backoffDelaysAreCapped() {
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L),
            (1..8).map { PrivacyGate.retryDelayMillis(it) },
        )
    }

    @Test
    fun onConsentedDoesNothingBeforeConsent() {
        val calls = mutableListOf<String>()
        val gate = gate().apply { PrivacyGate.INIT_ORDER.forEach { name -> registerInitStep(name) { calls += name } } }
        gate.onConsented()
        assertTrue(calls.isEmpty())
        assertNull(SharedPreferencesPrivacyStore(context).consentedVersion)
    }
}
