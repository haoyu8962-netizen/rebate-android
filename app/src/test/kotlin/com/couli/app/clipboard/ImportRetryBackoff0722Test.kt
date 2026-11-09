package com.couli.app.clipboard

import android.app.Application
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.clipboard.ImportPreferenceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** 不装配 Compose，单独观测默认 Handler 调度；虚拟时间推进，不实际等待五分钟。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class ImportRetryBackoff0722Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = 1_800_000_000_123L

    @Test
    fun g42_coreDefaultRetryIntervalsNeverExceedFiveMinutes() {
        ImportWriteFailure0722Harness(clock = { now }).use { h ->
            // 特意省略 scheduleWriteRetry；不能用测试队列冒充默认退避策略。
            val store = ImportPreferenceStore(h.backend, clock = { now })
            assertTrue(store.setOn())
            h.backend.failFromNextWrite()
            assertFalse(store.setOff())
            assertBoundedRetryIntervals { h.backend.writes }
            h.backend.recover()
            advanceToNextWrite { h.backend.writes }
            val restarted = h.restartFromDisk()
            assertEquals(false, restarted.snapshot().enabled)
        }
    }

    @Test
    fun g42_productionAdapterDefaultRetryIntervalsNeverExceedFiveMinutes() {
        ProductionImportPreferences0722Harness(context).use { h ->
            val store = h.preferences.importPreferences(clock = { now })
            assertTrue(store.setOn())
            h.flaky.failFromNextCommit()
            assertFalse(store.setOff())
            assertBoundedRetryIntervals { h.flaky.commits }
            h.flaky.recover()
            settleDefaultImportRetries0722(h.preferences)
            val restarted = h.restartFromDisk().importPreferences(clock = { now })
            assertEquals(false, restarted.snapshot().enabled)
        }
    }

    private fun assertBoundedRetryIntervals(attempts: () -> Int) {
        // 不指定首个延时、倍率或固定序列。24 个重试足以覆盖常见退避的封顶段；
        // 故意与超过 30 次的存活测试分开，使“上界”和“放弃重试”有独立失败证据。
        var firstInterval = 0L
        repeat(24) { attempt ->
            val before = SystemClock.uptimeMillis()
            advanceToNextWrite(attempts)
            val interval = SystemClock.uptimeMillis() - before
            assertTrue("失败重试应让出时间，不能忙循环：$interval ms", interval > 0L)
            assertTrue("相邻失败重试间隔不得超过五分钟：$interval ms", interval <= 300_000L)
            if (attempt == 0) firstInterval = interval
            if (attempt == 2) {
                assertTrue("第三次退避必须大于第一次的两倍，不能退回固定延时", interval > firstInterval * 2L)
            }
            assertTrue("后续退避不能短于第一次：$interval < $firstInterval ms", interval >= firstInterval)
        }
    }

    private fun advanceToNextWrite(attempts: () -> Int) {
        val before = attempts()
        // 跳过无关 Looper 消息，只以真实 backend / commit 入口计数为重试已执行的证据。
        repeat(128) {
            shadowOf(Looper.getMainLooper()).runToNextTask()
            if (attempts() > before) return
        }
        error("未观察到下一次生产写盘重试")
    }
}
