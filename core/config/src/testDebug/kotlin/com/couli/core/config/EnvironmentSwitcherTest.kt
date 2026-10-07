package com.couli.core.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EnvironmentSwitcherTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun defaultsToTheBuildTypeEnvironment() {
        val switcher = EnvironmentSwitcher(context, AppEnvironment.STAGING, EnvironmentSwitchCleanerRegistry())

        assertEquals(AppEnvironment.STAGING, switcher.current)
        assertEquals("https://api.staging.couli.invalid", switcher.endpoints.apiBaseUrl)
    }

    @Test
    fun switchingCleansBeforePersistingTheNewEnvironment() {
        val session = context.getSharedPreferences("couli.session", Context.MODE_PRIVATE)
        assertTrue(session.edit().putString("access_token", "old").commit())
        val registry = EnvironmentSwitchCleanerRegistry()
        val seen = mutableListOf<Pair<AppEnvironment, AppEnvironment>>()
        registry.register { from, to -> seen += from to to }
        registry.register(SharedPreferencesNamespaceCleaner(context))
        val switcher = EnvironmentSwitcher(context, AppEnvironment.TEST, registry)

        assertTrue(switcher.switchTo(AppEnvironment.PROD))

        assertEquals(listOf(AppEnvironment.TEST to AppEnvironment.PROD), seen)
        assertTrue(SharedPreferencesNamespaceCleaner(context).namespacedPreferenceNamesOnDisk().isEmpty())
        assertEquals(AppEnvironment.PROD, EnvironmentStartupReconciler(context, registry).lastApplied)
        assertEquals(AppEnvironment.PROD, switcher.current)
        assertEquals(ProdEndpoints.endpoints, switcher.endpoints)
        assertFalse(switcher.capabilities.isAvailable(DebugCapability.MOCK_DATA))
        assertTrue(switcher.capabilities.isAvailable(DebugCapability.SWITCH_SERVER))
    }

    /**
     * APP-03b R1-05、lessons 10：开始通知在任何清理之前（闸门同步关闭），清理与提交期间闸门一直关着；结束通知在新环境提交之后，
     * 此时 current 已是新环境（App 在这里重新开始设备注册与配置拉取）。
     */
    @Test
    fun switchListenersBracketCleanupAndFireAfterTheNewEnvironmentIsCommitted() {
        val registry = EnvironmentSwitchCleanerRegistry()
        val gate = EnvironmentGate { EnvironmentStartupReconciler.Outcome.UNCHANGED }.apply { settleAtStartup() }
        val switcher = EnvironmentSwitcher(context, AppEnvironment.TEST, registry)
        val events = mutableListOf<String>()
        registry.addSwitchListener(
            object : EnvironmentSwitchListener {
                override fun onSwitchStarting() {
                    gate.closeForSwitch()
                    events += "starting"
                }

                override fun onSwitchFinished(committed: Boolean) {
                    gate.reopenAfterSwitch()
                    events += "finished:$committed:${switcher.current.key}:${gate.isSettled}"
                }
            },
        )
        registry.register { _, _ -> events += "clean:gate=${gate.isSettled}" }

        assertTrue(switcher.switchTo(AppEnvironment.STAGING))

        assertEquals(listOf("starting", "clean:gate=false", "finished:true:staging:true"), events)
    }

    @Test
    fun failedCleanupFinishesUncommittedAndKeepsTheEnvironment() {
        val registry = EnvironmentSwitchCleanerRegistry()
        val finished = mutableListOf<Boolean>()
        registry.addSwitchListener(
            object : EnvironmentSwitchListener {
                override fun onSwitchStarting() = Unit

                override fun onSwitchFinished(committed: Boolean) {
                    finished += committed
                }
            },
        )
        registry.register { _, _ -> error("cleanup failed") }
        val switcher = EnvironmentSwitcher(context, AppEnvironment.TEST, registry)

        assertTrue(runCatching { switcher.switchTo(AppEnvironment.STAGING) }.isFailure)

        assertEquals(listOf(false), finished)
        assertEquals(AppEnvironment.TEST, switcher.current)
    }

    @Test
    fun switchingToTheCurrentEnvironmentDoesNothing() {
        val registry = EnvironmentSwitchCleanerRegistry()
        var cleaned = 0
        registry.register { _, _ -> cleaned++ }
        val switcher = EnvironmentSwitcher(context, AppEnvironment.TEST, registry)

        assertFalse(switcher.switchTo(AppEnvironment.TEST))
        assertEquals(0, cleaned)
    }

    @Test
    fun overInstallFromDebugToStagingCleansAtStartup() {
        // debug 构建（默认 test）首次启动，从未手动切换，写入会话
        val debugBuild = EnvironmentSwitcher(context, AppEnvironment.TEST, EnvironmentSwitchCleanerRegistry())
        assertEquals(EnvironmentStartupReconciler.Outcome.RECORDED_FIRST_RUN, debugBuild.reconcileAtStartup())
        assertTrue(session().edit().putString("access_token", "test-token").commit())
        // 同包名 staging 覆盖安装（默认 staging）后启动
        val seen = mutableListOf<Pair<AppEnvironment, AppEnvironment>>()
        val registry = EnvironmentSwitchCleanerRegistry()
        registry.register { from, to -> seen += from to to }
        registry.register(SharedPreferencesNamespaceCleaner(context))
        val stagingBuild = EnvironmentSwitcher(context, AppEnvironment.STAGING, registry)

        assertEquals(EnvironmentStartupReconciler.Outcome.CLEANED_AND_RECORDED, stagingBuild.reconcileAtStartup())

        assertEquals(listOf(AppEnvironment.TEST to AppEnvironment.STAGING), seen)
        assertNull(session().getString("access_token", null))
        assertEquals(AppEnvironment.STAGING, stagingBuild.current)
        assertEquals(EnvironmentStartupReconciler.Outcome.UNCHANGED, stagingBuild.reconcileAtStartup())
    }

    @Test
    fun explicitSelectionSurvivesRestartWithoutCleaningAgain() {
        val registry = EnvironmentSwitchCleanerRegistry()
        var cleaned = 0
        registry.register { _, _ -> cleaned++ }
        val switcher = EnvironmentSwitcher(context, AppEnvironment.TEST, registry)
        switcher.reconcileAtStartup()
        assertTrue(switcher.switchTo(AppEnvironment.LOCAL))
        assertEquals(1, cleaned)

        val restarted = EnvironmentSwitcher(context, AppEnvironment.TEST, registry)

        assertEquals(AppEnvironment.LOCAL, restarted.current)
        assertEquals(EnvironmentStartupReconciler.Outcome.UNCHANGED, restarted.reconcileAtStartup())
        assertEquals(1, cleaned)
    }

    @Test
    fun failedCleanupKeepsTheCurrentEnvironment() {
        val registry = EnvironmentSwitchCleanerRegistry()
        registry.register { _, _ -> throw IllegalStateException("boom") }
        val switcher = EnvironmentSwitcher(context, AppEnvironment.TEST, registry)
        switcher.reconcileAtStartup()

        assertTrue(runCatching { switcher.switchTo(AppEnvironment.STAGING) }.isFailure)

        assertEquals(AppEnvironment.TEST, switcher.current)
        assertNull(switcher.selected)
        assertEquals(AppEnvironment.TEST, EnvironmentStartupReconciler(context, registry).lastApplied)
    }

    private fun session() = context.getSharedPreferences(CouliPreferenceNamespaces.SESSION, Context.MODE_PRIVATE)
}
