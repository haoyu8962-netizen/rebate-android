package com.couli.core.privacy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.config.CouliPreferenceNamespaces
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * APP-03b：PrivacyGate.onConsented 的设备注册步骤接异步的 DeviceRegistry.registerAsync：第一步就是设备注册，失败（回调 false）按已有
 * 退避重试，成功后不再重试；撤回同意作废排着的重试；凭据丢失后 [PrivacyGate.restartDeviceRegistration] 从第 1 次重新开始。
 */
@RunWith(AndroidJUnit4::class)
class PrivacyGateDeviceStepTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val jobs = mutableListOf<Pair<Long, () -> Unit>>()
    private val order = mutableListOf<String>()
    private val outcomes = ArrayDeque<Boolean>()
    private var attempts = 0

    @Before
    fun reset() {
        context
            .getSharedPreferences(CouliPreferenceNamespaces.PRIVACY_DECISION, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun gate(): PrivacyGate =
        PrivacyGate(SharedPreferencesPrivacyStore(context), clock = { 1_000L }, bundledPolicyVersion = "1").apply {
            withdrawalCleanup = {}
            retryScheduler = RetryScheduler { delay, action -> jobs += delay to action }
            registerDeviceStep { onResult ->
                attempts += 1
                order += PrivacyGate.DEVICE_REGISTER
                onResult(outcomes.removeFirstOrNull() ?: true)
            }
            PrivacyGate.INIT_ORDER.filter { it != PrivacyGate.DEVICE_REGISTER }.forEach { name -> registerInitStep(name) { order += name } }
        }

    @Test
    fun deviceRegistrationRunsFirstAndRetriesWithBackoffUntilItSucceeds() {
        outcomes += listOf(false, false, true)
        val gate = gate()
        gate.agree()
        gate.onConsented()

        assertEquals(PrivacyGate.INIT_ORDER, order)
        assertEquals(1, attempts)
        assertEquals(listOf(PrivacyGate.retryDelayMillis(1)), jobs.map { it.first })

        jobs.removeAt(0).second()
        assertEquals(2, attempts)
        jobs.removeAt(0).second()
        assertEquals(3, attempts)
        assertEquals("成功后不再排重试", 0, jobs.size)
    }

    @Test
    fun withdrawalCancelsQueuedRetries() {
        outcomes += false
        val gate = gate()
        gate.agree()
        gate.onConsented()
        gate.withdraw()

        jobs.removeAt(0).second()
        assertEquals(1, attempts)
    }

    @Test
    fun restartAfterCredentialsLostRegistersAgainFromTheFirstAttempt() {
        val gate = gate()
        gate.agree()
        gate.onConsented()
        assertEquals(1, attempts)

        outcomes += false
        gate.restartDeviceRegistration()
        assertEquals(2, attempts)
        assertEquals(listOf(PrivacyGate.retryDelayMillis(1)), jobs.map { it.first })
    }

    @Test
    fun answeringTwiceSchedulesOnlyOneRetry() {
        val gate =
            PrivacyGate(SharedPreferencesPrivacyStore(context), clock = { 1_000L }, bundledPolicyVersion = "1").apply {
                withdrawalCleanup = {}
                retryScheduler = RetryScheduler { delay, action -> jobs += delay to action }
                registerDeviceStep { onResult ->
                    onResult(false)
                    onResult(false)
                }
            }
        gate.agree()
        gate.onConsented()
        assertEquals(1, jobs.size)
    }
}
