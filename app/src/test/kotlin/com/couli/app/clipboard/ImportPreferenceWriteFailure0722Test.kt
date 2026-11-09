package com.couli.app.clipboard

import com.couli.core.clipboard.ImportPreferenceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S1-1：生产 store + 故障磁盘端口；每次重启从文件加载，不把内存读回当落盘证据。 */
class ImportPreferenceWriteFailure0722Test {
    private var now = 1_800_000_000_123L

    @Test
    fun close_singleFailedCommitIsRetriedAndDiskRestartStaysOff() {
        ImportWriteFailure0722Harness(clock = { now }).use { h ->
            assertEquals(true, h.store.setOn())
            val original = h.store.snapshot()
            h.backend.failOnlyNextWrite()
            // 允许同步重试成功或异步排队；不指定第一次调用必须先返回失败。
            h.store.setOff()
            assertTrue(h.backend.failures > 0)
            assertFalse(h.store.isAutoSwitchOn())
            h.settleRetries()
            val restarted = h.restartFromDisk()
            assertEquals(original.copy(enabled = false), restarted.snapshot())
            assertFalse(restarted.isAutoSwitchOn())
        }
    }

    @Test
    fun close_repeatedFailuresStayOffThenRecoveryPersistsBeforeRestart() {
        // 两种合法 backend：失败后内存已改 / 内存未改。都必须保守关闭。
        for (exposeFailed in listOf(true, false)) {
            ImportWriteFailure0722Harness({ now }, exposeFailed).use { h ->
                assertEquals(true, h.store.setOn())
                h.backend.failFromNextWrite()
                assertEquals(false, h.store.setOff())
                assertFalse(h.store.isAutoSwitchOn())
                assertEquals(false, h.store.snapshot().enabled)
                h.runNextRetry()
                assertFalse(h.store.isAutoSwitchOn())
                assertTrue(h.backend.failures >= 2)
                h.backend.recover()
                h.settleRetries()
                val restarted = h.restartFromDisk()
                assertEquals(false, restarted.snapshot().enabled)
                assertFalse(restarted.isAutoSwitchOn())
            }
        }
    }

    @Test
    fun g42_closeRecoversAfterHundredsOfFailuresWithoutAnotherUserAction() {
        for (exposeFailed in listOf(true, false)) {
            ImportWriteFailure0722Harness({ now }, exposeFailed).use { h ->
                assertTrue(h.store.setOn())
                val original = h.store.snapshot()
                h.backend.failFromNextWrite()
                assertFalse(h.store.setOff())
                // 只交付产品安排的任务；若旧实现已放弃，不替它安排新任务。
                val failureStartedAt = now
                repeat(300) {
                    now += 300_000L // 注入时钟每次推进五分钟；故障跨过 25 小时，不能按时长放弃。
                    if (h.pendingRetries > 0) h.runNextRetry()
                }
                assertEquals(90_000_000L, now - failureStartedAt)
                assertTrue("故障必须跨过原来的 30 次上限", h.backend.failures > 30)
                val allowedDuringFailure = h.store.isAutoSwitchOn()
                assertFalse(allowedDuringFailure)
                h.backend.recover()
                h.settleRetries()
                val restarted = h.restartFromDisk()
                assertEquals(original.copy(enabled = false), restarted.snapshot())
                assertFalse(restarted.isAutoSwitchOn())
            }
        }
    }

    @Test
    fun enable_failedCommitRollsBackAndRestartCanEnableOnSecondClick() {
        ImportWriteFailure0722Harness(clock = { now }).use { h ->
            assertTrue(h.store.setOff())
            h.backend.failFromNextWrite()
            assertEquals(false, h.store.setOn())
            assertRolledBack(h.store)
            val restarted = h.restartFromDisk()
            assertRolledBack(restarted)
            assertEquals(true, restarted.setOn())
            assertConsistentEnabled(restarted)
        }
    }

    @Test
    fun enable_singleFailureDoesNotAutoRetryOrLeakAndNextClickSucceeds() {
        ImportWriteFailure0722Harness(clock = { now }).use { h ->
            assertTrue(h.store.setOff())
            h.backend.failOnlyNextWrite()
            assertEquals(false, h.store.setOn())
            assertTrue(h.backend.failures > 0)
            assertRolledBack(h.store)
            now += 10_000L
            h.settleRetries()
            assertRolledBack(h.store)
            assertRolledBack(h.inspectDisk())
            assertEquals(true, h.store.setOn())
            assertConsistentEnabled(h.restartFromDisk())
        }
    }

    @Test
    fun enable_repeatedClickFailuresRemainOffAfterRecoveryUntilUserClicksAgain() {
        ImportWriteFailure0722Harness(clock = { now }).use { h ->
            assertTrue(h.store.setOff())
            h.backend.failFromNextWrite()
            assertEquals(false, h.store.setOn())
            assertRolledBack(h.store)
            now += 10_000L
            // 只有用户再次点击才重试开启；持续失败仍不能授权。
            assertEquals(false, h.store.setOn())
            assertRolledBack(h.store)
            assertTrue(h.backend.failures >= 2)
            h.backend.recover()
            now += 10_000L
            h.settleRetries()
            assertRolledBack(h.store)
            assertRolledBack(h.inspectDisk())
            assertEquals(true, h.store.setOn())
            assertConsistentEnabled(h.restartFromDisk())
        }
    }

    @Test
    fun staleCloseRetryCannotOverwriteUserReenableAfterDiskRecovers() {
        ImportWriteFailure0722Harness(clock = { now }).use { h ->
            assertEquals(true, h.store.setOn())
            val original = h.store.snapshot()
            h.backend.failFromNextWrite()
            assertEquals(false, h.store.setOff())
            assertTrue(h.pendingRetries > 0)
            h.backend.recover()
            now += 10_000L
            assertEquals(true, h.store.setOn())
            h.settleRetries()
            val restarted = h.restartFromDisk()
            assertEquals(original, restarted.snapshot())
            assertTrue(restarted.isAutoSwitchOn())
        }
    }

    @Test
    fun staleRollbackRetryCannotEraseLaterSuccessfulEnable() {
        // G38：并入 S2aProbe0722Test；两种 backend 都接受，不能要求必须排了回滚重试。
        for (expose in listOf(true, false)) {
            ImportWriteFailure0722Harness({ now }, expose).use { h ->
                assertTrue(h.store.setOff())
                h.backend.failFromNextWrite()
                assertEquals(false, h.store.setOn())
                h.backend.recover()
                now += 10_000L
                // 用户先再点成功，再交付任何旧回滚闭包。
                assertEquals(true, h.store.setOn())
                h.settleRetries()
                assertConsistentEnabled(h.restartFromDisk())
            }
        }
    }

    @Test
    fun staleRollbackRetryAfterCloseFailureThenEnable() {
        ImportWriteFailure0722Harness(clock = { now }).use { h ->
            assertEquals(true, h.store.setOn())
            val original = h.store.snapshot()
            h.backend.failFromNextWrite()
            assertEquals(false, h.store.setOff())
            assertTrue(h.pendingRetries > 0)
            assertEquals(false, h.store.setOn())
            assertEquals(false, h.store.snapshot().enabled)
            h.backend.recover()
            now += 10_000L
            assertEquals(true, h.store.setOn())
            h.settleRetries()
            val restarted = h.restartFromDisk()
            assertTrue(restarted.isAutoSwitchOn())
            assertEquals(original, restarted.snapshot())
        }
    }

    @Test
    fun unrelatedSuccessfulCommitPersistsPendingCloseBeforeRetryWhenFailedWriteLeftMemoryOn() {
        ImportWriteFailure0722Harness({ now }, exposeFailedWriteInMemory = false).use { h ->
            assertEquals(true, h.store.setOn())
            h.backend.failFromNextWrite()
            assertEquals(false, h.store.setOff())
            assertTrue(h.pendingRetries > 0)
            h.backend.recover()
            // F07：回前台补写（flush）先于关闭重试，把关闭落盘，不能清掉意图却把旧 on 顺带提交。
            assertTrue(h.store.flushPendingWrites())
            val committed = h.inspectDisk()
            assertEquals(false, committed.snapshot().enabled)
            assertFalse(committed.isAutoSwitchOn())
            h.settleRetries()
            val restarted = h.restartFromDisk()
            assertEquals(false, restarted.snapshot().enabled)
        }
    }

    /** G35：开启没落盘按没开启算；本单前置都是用户已关闭（setOff），回滚后仍关闭。 */
    private fun assertRolledBack(store: ImportPreferenceStore) {
        assertFalse(store.isAutoSwitchOn())
        assertEquals(false, store.snapshot().enabled)
    }

    private fun assertConsistentEnabled(store: ImportPreferenceStore) {
        assertTrue(store.isAutoSwitchOn())
        assertEquals(true, store.snapshot().enabled)
    }
}
