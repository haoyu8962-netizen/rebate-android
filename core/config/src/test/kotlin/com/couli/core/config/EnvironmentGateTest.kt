package com.couli.core.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 启动对账失败 → 阻断；重试成功 → 放行且记录更新；阻断期间记录不变（R2-01）。 */
@RunWith(AndroidJUnit4::class)
class EnvironmentGateTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** 前 [failures] 次清理抛错，之后成功；记录每次调用。 */
    private class FlakyCleaner(
        var failures: Int,
    ) : EnvironmentSwitchCleaner {
        val calls = mutableListOf<Pair<AppEnvironment, AppEnvironment>>()
        val failure = IllegalStateException("disk full")

        override fun clean(
            from: AppEnvironment,
            to: AppEnvironment,
        ) {
            calls += from to to
            if (failures > 0) {
                failures--
                throw failure
            }
        }
    }

    private fun session() = context.getSharedPreferences(CouliPreferenceNamespaces.SESSION, Context.MODE_PRIVATE)

    /** 模拟上一次以 [previous] 运行并写下了会话；返回本次（目标 [target]）的闸门与清理器。 */
    private fun overInstall(
        previous: AppEnvironment,
        target: AppEnvironment,
        failures: Int,
    ): Triple<EnvironmentGate, FlakyCleaner, EnvironmentStartupReconciler> {
        EnvironmentStartupReconciler(context, EnvironmentSwitchCleanerRegistry()).reconcile(previous)
        assertTrue(session().edit().putString("access_token", "old-${previous.key}").commit())
        val cleaner = FlakyCleaner(failures)
        val registry =
            EnvironmentSwitchCleanerRegistry().apply {
                register(cleaner)
                register(SharedPreferencesNamespaceCleaner(context))
            }
        val reconciler = EnvironmentStartupReconciler(context, registry)
        return Triple(EnvironmentGate { reconciler.reconcile(target) }, cleaner, reconciler)
    }

    @Test
    fun blockedBeforeStartupReconciliation() {
        val gate = EnvironmentGate { EnvironmentStartupReconciler.Outcome.UNCHANGED }

        val state = gate.state.value

        assertTrue(state is EnvironmentGate.State.Blocked)
        assertTrue((state as EnvironmentGate.State.Blocked).cause is EnvironmentGate.NotReconciledYet)
        assertFalse(gate.isSettled)
        assertTrue(runCatching { gate.requireSettled() }.isFailure)
    }

    @Test
    fun cleanupFailingTwiceBlocksAndKeepsTheRecord() {
        val (gate, cleaner, reconciler) = overInstall(AppEnvironment.TEST, AppEnvironment.STAGING, failures = 2)

        val state = gate.settleAtStartup()

        // 自动重试了一次（共两次），仍失败 → 阻断
        assertEquals(EnvironmentGate.STARTUP_ATTEMPTS, cleaner.calls.size)
        assertTrue(state is EnvironmentGate.State.Blocked)
        assertSame(cleaner.failure, (state as EnvironmentGate.State.Blocked).cause)
        assertEquals(state, gate.state.value)
        assertFalse(gate.isSettled)
        assertTrue(runCatching { gate.requireSettled() }.isFailure)
        // 记录不变：仍是上次生效的 test，下次启动 / 重试会再清理
        assertEquals(AppEnvironment.TEST, reconciler.lastApplied)
    }

    @Test
    fun retryAfterBlockedSettlesAndUpdatesTheRecord() {
        val (gate, cleaner, reconciler) = overInstall(AppEnvironment.TEST, AppEnvironment.STAGING, failures = 3)

        assertTrue(gate.settleAtStartup() is EnvironmentGate.State.Blocked)
        assertTrue(gate.retry() is EnvironmentGate.State.Blocked) // 第三次仍失败
        assertEquals(AppEnvironment.TEST, reconciler.lastApplied)

        val state = gate.retry()

        assertEquals(EnvironmentGate.State.Settled(EnvironmentStartupReconciler.Outcome.CLEANED_AND_RECORDED), state)
        assertEquals(state, gate.state.value)
        assertTrue(gate.isSettled)
        gate.requireSettled()
        assertEquals(AppEnvironment.STAGING, reconciler.lastApplied)
        assertEquals(List(4) { AppEnvironment.TEST to AppEnvironment.STAGING }, cleaner.calls)
        // 旧环境会话已清
        assertNull(session().getString("access_token", null))
    }

    @Test
    fun singleFailureIsRecoveredByTheAutomaticRetry() {
        val (gate, cleaner, reconciler) = overInstall(AppEnvironment.STAGING, AppEnvironment.PROD, failures = 1)

        val state = gate.settleAtStartup()

        assertEquals(EnvironmentGate.State.Settled(EnvironmentStartupReconciler.Outcome.CLEANED_AND_RECORDED), state)
        assertEquals(2, cleaner.calls.size)
        assertEquals(AppEnvironment.PROD, reconciler.lastApplied)
    }

    @Test
    fun retryWhenSettledDoesNothing() {
        var calls = 0
        val gate =
            EnvironmentGate {
                calls++
                EnvironmentStartupReconciler.Outcome.UNCHANGED
            }
        gate.settleAtStartup()

        gate.retry()

        assertEquals(1, calls)
        assertTrue(gate.isSettled)
    }
}
