package com.couli.core.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EnvironmentStartupReconcilerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun firstRunRecordsWithoutCleaning() {
        val seen = mutableListOf<Pair<AppEnvironment, AppEnvironment>>()
        val reconciler = EnvironmentStartupReconciler(context, recordingRegistry(seen))
        assertNull(reconciler.lastApplied)

        assertEquals(EnvironmentStartupReconciler.Outcome.RECORDED_FIRST_RUN, reconciler.reconcile(AppEnvironment.TEST))

        assertTrue(seen.isEmpty())
        assertEquals(AppEnvironment.TEST, reconciler.lastApplied)
    }

    @Test
    fun sameEnvironmentDoesNothing() {
        val seen = mutableListOf<Pair<AppEnvironment, AppEnvironment>>()
        EnvironmentStartupReconciler(context, recordingRegistry(seen)).reconcile(AppEnvironment.STAGING)

        val outcome = EnvironmentStartupReconciler(context, recordingRegistry(seen)).reconcile(AppEnvironment.STAGING)

        assertEquals(EnvironmentStartupReconciler.Outcome.UNCHANGED, outcome)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun overInstallThatChangesTheEnvironmentCleansBeforeRecording() {
        // debug（默认 test）首次启动并写入会话；同包名 staging 覆盖安装后以 staging 启动。
        EnvironmentStartupReconciler(context, EnvironmentSwitchCleanerRegistry()).reconcile(AppEnvironment.TEST)
        assertTrue(session().edit().putString("access_token", "old").commit())
        val seen = mutableListOf<Pair<AppEnvironment, AppEnvironment>>()
        val registry =
            recordingRegistry(seen).apply {
                register(SharedPreferencesNamespaceCleaner(context))
            }
        val reconciler = EnvironmentStartupReconciler(context, registry)

        val outcome = reconciler.reconcile(AppEnvironment.STAGING)

        assertEquals(EnvironmentStartupReconciler.Outcome.CLEANED_AND_RECORDED, outcome)
        assertEquals(listOf(AppEnvironment.TEST to AppEnvironment.STAGING), seen)
        assertNull(session().getString("access_token", null))
        assertEquals(AppEnvironment.STAGING, reconciler.lastApplied)
    }

    @Test
    fun failedCleanupIsNotRecordedAndIsRetriedOnNextLaunch() {
        EnvironmentStartupReconciler(context, EnvironmentSwitchCleanerRegistry()).reconcile(AppEnvironment.STAGING)
        val failure = IllegalStateException("disk full")
        val failing = EnvironmentSwitchCleanerRegistry().apply { register { _, _ -> throw failure } }

        val thrown = runCatching { EnvironmentStartupReconciler(context, failing).reconcile(AppEnvironment.PROD) }

        assertSame(failure, thrown.exceptionOrNull())
        assertEquals(AppEnvironment.STAGING, EnvironmentStartupReconciler(context, failing).lastApplied)

        // 下次启动：清理成功后才记录 prod
        val seen = mutableListOf<Pair<AppEnvironment, AppEnvironment>>()
        val nextLaunch = EnvironmentStartupReconciler(context, recordingRegistry(seen))
        assertEquals(EnvironmentStartupReconciler.Outcome.CLEANED_AND_RECORDED, nextLaunch.reconcile(AppEnvironment.PROD))
        assertEquals(listOf(AppEnvironment.STAGING to AppEnvironment.PROD), seen)
        assertEquals(AppEnvironment.PROD, nextLaunch.lastApplied)
    }

    private fun session() = context.getSharedPreferences(CouliPreferenceNamespaces.SESSION, Context.MODE_PRIVATE)

    private fun recordingRegistry(seen: MutableList<Pair<AppEnvironment, AppEnvironment>>) =
        EnvironmentSwitchCleanerRegistry().apply { register { from, to -> seen += from to to } }
}
