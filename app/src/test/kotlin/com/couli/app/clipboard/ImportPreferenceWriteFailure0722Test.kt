package com.couli.app.clipboard

import com.couli.core.clipboard.ImportConsentRecord
import com.couli.core.clipboard.ImportPreferenceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S1-1：生产 store + 故障磁盘端口；每次重启从文件加载，不把内存读回当落盘证据。 */
class ImportPreferenceWriteFailure0722Test {
    private var now = 1_800_000_000_123L

    @Test
    fun close_singleFailedCommitIsRetriedAndDiskRestartStaysOff() {
        ImportWriteFailure0722Harness(clock = { now }).use { h ->
            assertEquals(true, h.store.enable("purpose-02"))
            val original = h.store.snapshot()
            val queue = h.store.consentQueue.pending()
            h.backend.failOnlyNextWrite()
            // 允许同步重试成功或异步排队；不指定第一次调用必须先返回失败。
            h.store.setOff()
            assertTrue(h.backend.failures > 0)
            assertFalse(h.store.isAutoReadAllowed("purpose-02"))
            h.settleRetries()
            val restarted = h.restartFromDisk()
            assertEquals(original.copy(enabled = false), restarted.snapshot())
            assertFalse(restarted.isAutoReadAllowed("purpose-02"))
            assertEquals(queue, restarted.consentQueue.pending())
        }
    }

    @Test
    fun close_repeatedFailuresStayOffThenRecoveryPersistsBeforeRestart() {
        // 两种合法 backend：失败后内存已改 / 内存未改。都必须保守关闭。
        for (exposeFailed in listOf(true, false)) {
            ImportWriteFailure0722Harness({ now }, exposeFailed).use { h ->
                assertEquals(true, h.store.enable("purpose-02"))
                val queue = h.store.consentQueue.pending()
                h.backend.failFromNextWrite()
                assertEquals(false, h.store.setOff())
                assertFalse(h.store.isAutoReadAllowed("purpose-02"))
                assertEquals(false, h.store.snapshot().enabled)
                h.runNextRetry()
                assertFalse(h.store.isAutoReadAllowed("purpose-02"))
                assertTrue(h.backend.failures >= 2)
                h.backend.recover()
                h.settleRetries()
                val restarted = h.restartFromDisk()
                assertEquals(false, restarted.snapshot().enabled)
                assertFalse(restarted.isAutoReadAllowed("purpose-02"))
                assertEquals(queue, restarted.consentQueue.pending())
            }
        }
    }

    @Test
    fun g42_closeRecoversAfterHundredsOfFailuresWithoutAnotherUserAction() {
        for (exposeFailed in listOf(true, false)) {
            ImportWriteFailure0722Harness({ now }, exposeFailed).use { h ->
                assertTrue(h.store.enable("purpose-02"))
                val original = h.store.snapshot()
                val queue = h.store.consentQueue.pending()
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
                val allowedDuringFailure = h.store.isAutoReadAllowed("purpose-02")
                assertFalse(allowedDuringFailure)
                h.backend.recover()
                h.settleRetries()
                val restarted = h.restartFromDisk()
                assertEquals(original.copy(enabled = false), restarted.snapshot())
                assertFalse(restarted.isAutoReadAllowed("purpose-02"))
                assertEquals(queue, restarted.consentQueue.pending())
            }
        }
    }

    @Test
    fun g42_enqueueRecoversAfterHundredsOfFailuresWithoutAnotherUserAction() {
        for (exposeFailed in listOf(true, false)) {
            ImportWriteFailure0722Harness({ now }, exposeFailed).use { h ->
                assertTrue(h.store.enable("purpose-02"))
                val original = h.store.snapshot()
                val committed = h.store.consentQueue.pending()
                val extra = ImportConsentRecord("clipboard_auto_import", "purpose-01", now - 10_000L)
                h.backend.failFromNextWrite()
                assertFalse(h.store.consentQueue.enqueue(extra))
                val failureStartedAt = now
                repeat(300) {
                    now += 300_000L // 注入时钟每次推进五分钟；故障跨过 25 小时，不能按时长放弃。
                    if (h.pendingRetries > 0) h.runNextRetry()
                }
                assertEquals(90_000_000L, now - failureStartedAt)
                assertTrue("入队故障也必须跨过原来的 30 次上限", h.backend.failures > 30)
                h.backend.recover()
                h.settleRetries()
                val restarted = h.restartFromDisk()
                assertEquals(committed + extra, restarted.consentQueue.pending())
                assertEquals(original, restarted.snapshot())
            }
        }
    }

    @Test
    fun enable_failedCommitRollsBackAndRestartCanEnableOnSecondClick() {
        ImportWriteFailure0722Harness(clock = { now }).use { h ->
            h.backend.failFromNextWrite()
            assertEquals(false, h.store.enable("purpose-02"))
            assertRolledBack(h.store)
            val restarted = h.restartFromDisk()
            assertRolledBack(restarted)
            now += 10_000L
            assertEquals(true, restarted.enable("purpose-02"))
            assertConsistentEnabled(restarted, now)
        }
    }

    @Test
    fun enable_singleFailureDoesNotAutoRetryOrLeakThroughUnrelatedCommitAndNextClickSucceeds() {
        ImportWriteFailure0722Harness(clock = { now }).use { h ->
            h.backend.failOnlyNextWrite()
            assertEquals(false, h.store.enable("purpose-02"))
            assertTrue(h.backend.failures > 0)
            assertRolledBack(h.store)
            now += 10_000L
            h.settleRetries()
            assertRolledBack(h.store)
            // SharedPreferences 成功 commit 写整份缓存，不能把失败的开启/队列顺带写入磁盘。
            h.store.markNoticeSeen("purpose-02")
            assertRolledBack(h.inspectDisk())
            assertEquals(true, h.store.enable("purpose-02"))
            assertConsistentEnabled(h.restartFromDisk(), now)
        }
    }

    @Test
    fun enable_repeatedClickFailuresRemainOffAfterRecoveryUntilUserClicksAgain() {
        ImportWriteFailure0722Harness(clock = { now }).use { h ->
            h.backend.failFromNextWrite()
            assertEquals(false, h.store.enable("purpose-02"))
            assertRolledBack(h.store)
            now += 10_000L
            // 只有用户再次点击才重试开启；持续失败仍不能授权。
            assertEquals(false, h.store.enable("purpose-02"))
            assertRolledBack(h.store)
            assertTrue(h.backend.failures >= 2)
            h.backend.recover()
            now += 10_000L
            h.settleRetries()
            assertRolledBack(h.store)
            h.store.markNoticeSeen("purpose-02")
            assertRolledBack(h.inspectDisk())
            assertEquals(true, h.store.enable("purpose-02"))
            assertConsistentEnabled(h.restartFromDisk(), now)
        }
    }

    @Test
    fun staleCloseRetryCannotOverwriteUserReenableAfterDiskRecovers() {
        ImportWriteFailure0722Harness(clock = { now }).use { h ->
            assertEquals(true, h.store.enable("purpose-02"))
            val original = h.store.snapshot()
            val queue = h.store.consentQueue.pending()
            h.backend.failFromNextWrite()
            assertEquals(false, h.store.setOff())
            assertTrue(h.pendingRetries > 0)
            h.backend.recover()
            now += 10_000L
            assertEquals(true, h.store.enable("purpose-02"))
            h.settleRetries()
            val restarted = h.restartFromDisk()
            assertEquals(original, restarted.snapshot())
            assertEquals(queue, restarted.consentQueue.pending())
            assertTrue(restarted.isAutoReadAllowed("purpose-02"))
        }
    }

    @Test
    fun staleEnqueueRetryAfterPrivacyWithdrawalCannotRestoreConsentOrReading() {
        ImportWriteFailure0722Harness(clock = { now }).use { h ->
            assertEquals(true, h.store.enable("purpose-02"))
            val committed = h.store.consentQueue.pending()
            h.backend.failFromNextWrite()
            val record = ImportConsentRecord("clipboard_auto_import", "purpose-01", now - 10_000L)
            assertEquals(false, h.store.consentQueue.enqueue(record))
            assertTrue(h.pendingRetries > 0)
            h.backend.recover()
            assertTrue(h.store.clearOnPrivacyWithdraw())
            h.settleRetries()
            val restarted = h.restartFromDisk()
            assertWithdrawn(restarted)
            // G26：已提交的历史同意保留；不替裁定规定尚未提交的第二条是否继续排队。
            assertTrue(restarted.consentQueue.pending().containsAll(committed))
        }
    }

    @Test
    fun staleCloseRetryAfterPrivacyWithdrawalCannotRecreateClearedChoice() {
        ImportWriteFailure0722Harness(clock = { now }).use { h ->
            assertEquals(true, h.store.enable("purpose-02"))
            val queue = h.store.consentQueue.pending()
            h.backend.failFromNextWrite()
            assertEquals(false, h.store.setOff())
            assertTrue(h.pendingRetries > 0)
            h.backend.recover()
            assertTrue(h.store.clearOnPrivacyWithdraw())
            h.settleRetries()
            val restarted = h.restartFromDisk()
            assertWithdrawn(restarted)
            assertEquals(queue, restarted.consentQueue.pending())
        }
    }

    @Test
    fun withdrawalFailedWriteImmediatelyBlocksReadingEvenWhenBackendMemoryStaysOn() {
        for (exposeFailed in listOf(true, false)) {
            ImportWriteFailure0722Harness({ now }, exposeFailed).use { h ->
                assertEquals(true, h.store.enable("purpose-02"))
                h.backend.failFromNextWrite()
                assertFalse(h.store.clearOnPrivacyWithdraw())
                val allowed = h.store.isAutoReadAllowed("purpose-02")
                assertFalse(allowed)
                // 持久清理的恢复由已有 ClipboardHashesWithdrawalTest 的 cleaner 用例验证。
            }
        }
    }

    @Test
    fun staleRollbackRetryCannotEraseLaterSuccessfulEnable() {
        // G38：并入 S2aProbe0722Test；两种 backend 都接受，不能要求必须排了回滚重试。
        for (expose in listOf(true, false)) {
            ImportWriteFailure0722Harness({ now }, expose).use { h ->
                h.backend.failFromNextWrite()
                assertEquals(false, h.store.enable("purpose-02"))
                h.backend.recover()
                now += 10_000L
                // 用户先再点成功，再交付任何旧回滚闭包。
                assertEquals(true, h.store.enable("purpose-02"))
                h.settleRetries()
                assertConsistentEnabled(h.restartFromDisk(), now)
            }
        }
    }

    @Test
    fun staleRollbackRetryAfterCloseFailureThenEnable() {
        ImportWriteFailure0722Harness(clock = { now }).use { h ->
            assertEquals(true, h.store.enable("purpose-02"))
            val original = h.store.snapshot()
            val queue = h.store.consentQueue.pending()
            h.backend.failFromNextWrite()
            assertEquals(false, h.store.setOff())
            assertTrue(h.pendingRetries > 0)
            assertEquals(false, h.store.enable("purpose-02"))
            assertEquals(false, h.store.snapshot().enabled)
            h.backend.recover()
            now += 10_000L
            assertEquals(true, h.store.enable("purpose-02"))
            h.settleRetries()
            val restarted = h.restartFromDisk()
            assertTrue(restarted.isAutoReadAllowed("purpose-02"))
            // G20 有效版本重新打开，仍是原同意时刻及原队列，不能被旧回滚抹掉。
            assertEquals(original, restarted.snapshot())
            assertEquals(queue, restarted.consentQueue.pending())
        }
    }

    @Test
    fun unrelatedSuccessfulCommitPersistsPendingCloseBeforeRetryWhenFailedWriteLeftMemoryOn() {
        ImportWriteFailure0722Harness({ now }, exposeFailedWriteInMemory = false).use { h ->
            assertEquals(true, h.store.enable("purpose-02"))
            val queue = h.store.consentQueue.pending()
            h.backend.failFromNextWrite()
            assertEquals(false, h.store.setOff())
            assertTrue(h.pendingRetries > 0)
            h.backend.recover()
            // F07：无关的成功提交先于关闭重试，不能清掉意图却把旧 on 顺带提交。
            assertTrue(h.store.markNoticeSeen("purpose-03"))
            val committed = h.inspectDisk()
            assertEquals(false, committed.snapshot().enabled)
            assertTrue(committed.hasSeenNotice("purpose-03"))
            assertFalse(committed.isAutoReadAllowed("purpose-02"))
            h.settleRetries()
            val restarted = h.restartFromDisk()
            assertEquals(false, restarted.snapshot().enabled)
            assertEquals(queue, restarted.consentQueue.pending())
        }
    }

    private fun assertRolledBack(store: ImportPreferenceStore) {
        val allowed = store.isAutoReadAllowed("purpose-02")
        val state = store.snapshot()
        val pending = store.consentQueue.pending()
        assertFalse(allowed)
        assertFalse(state.enabled == true)
        assertNull(state.consentVersion)
        assertNull(state.consentedAtMillis)
        assertTrue(pending.isEmpty())
    }

    private fun assertWithdrawn(store: ImportPreferenceStore) {
        val state = store.snapshot()
        assertNull(state.enabled)
        assertNull(state.consentVersion)
        assertNull(state.consentedAtMillis)
        assertNull(state.noticeSeenVersion)
        assertFalse(store.isAutoReadAllowed("purpose-02"))
    }

    @Test
    fun enqueue_singleFailureRetriesWithoutDroppingOrDuplicatingRecordsOnDisk() {
        queueFailureRecovers(repeated = false)
    }

    @Test
    fun enqueue_repeatedFailuresRemainRetryableAndRecoveryPersistsExactlyOnce() {
        queueFailureRecovers(repeated = true)
    }

    private fun queueFailureRecovers(repeated: Boolean) {
        ImportWriteFailure0722Harness(clock = { now }).use { h ->
            val first = ImportConsentRecord("clipboard_auto_import", "purpose-01", now)
            val second = ImportConsentRecord("clipboard_auto_import", "purpose-02", now + 1)
            assertEquals(true, h.store.consentQueue.enqueue(first))
            if (repeated) h.backend.failFromNextWrite() else h.backend.failOnlyNextWrite()
            val result = h.store.consentQueue.enqueue(second)
            assertTrue(h.backend.failures > 0)
            if (repeated) {
                assertEquals(false, result)
                h.runNextRetry()
                assertTrue(h.backend.failures >= 2)
                h.backend.recover()
            }
            now += 10_000L
            h.settleRetries()
            val restarted = h.restartFromDisk()
            assertEquals(listOf(first, second), restarted.consentQueue.pending())
            assertNull(restarted.snapshot().consentVersion)
            assertFalse(restarted.isAutoReadAllowed("purpose-02"))
        }
    }

    private fun assertConsistentEnabled(
        store: ImportPreferenceStore,
        acceptedAt: Long,
    ) {
        assertTrue(store.isAutoReadAllowed("purpose-02"))
        val state = store.snapshot()
        assertEquals(true, state.enabled)
        assertEquals("purpose-02", state.consentVersion)
        assertEquals(acceptedAt, state.consentedAtMillis)
        assertEquals(
            listOf(ImportConsentRecord("clipboard_auto_import", "purpose-02", acceptedAt)),
            store.consentQueue.pending(),
        )
    }
}
