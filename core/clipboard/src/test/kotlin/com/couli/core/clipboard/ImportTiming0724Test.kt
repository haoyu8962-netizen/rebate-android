@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.couli.core.clipboard

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** F-AND-54：只驱动生产协调器；排队窗口内不执行调度器，不额外补 pageReady。 */
class ImportTiming0724Test {
    @Test
    fun queuedSwitchOffThenOnCannotResurrectTheCancelledRead() =
        runTest {
            val f = ImportCoordinator0723Fixture(this)
            f.foreground()
            assertTrue(f.prefs.setOff())
            f.coordinator.onSwitchOff()
            assertTrue(f.prefs.enable("v1"))
            f.settle()
            val reads = f.reads.toList()
            assertEquals(emptyList<ImportTask>(), reads)
            // 对照：真正的新 pageReady 可以重新尝试尚未消费的轮次。
            f.coordinator.onPageReady()
            f.settle()
            assertEquals(1, f.reads.size)
        }

    @Test
    fun queuedBackgroundThenForegroundCannotRunTheOldQueueWithoutPageReady() =
        runTest {
            val f = ImportCoordinator0723Fixture(this)
            f.foreground()
            f.background()
            f.coordinator.onLifecycle(ImportLifecycleEvent.PROCESS_FOREGROUND)
            f.settle()
            val reads = f.reads.toList()
            assertTrue(reads.isEmpty())
            f.coordinator.onPageReady()
            f.settle()
            assertEquals(1, f.reads.size)
            assertEquals(2L, f.reads.single().epoch)
        }

    @Test
    fun unconsentedTapIsNotReplayedWhenConsentReturnsBeforeQueueExecution() =
        runTest {
            val f = ImportCoordinator0723Fixture(this, enabled = false)
            f.environment = f.environment.copy(privacyAgreed = false)
            f.coordinator.onPasteTap(ImportTask.PURPOSE_PRODUCT_LOOKUP)
            f.environment = f.environment.copy(privacyAgreed = true)
            f.settle()
            val reads = f.reads.size
            assertEquals(0, reads)
            f.coordinator.onPasteTap(ImportTask.PURPOSE_PRODUCT_LOOKUP)
            f.settle()
            assertEquals(1, f.reads.size)
        }

    @Test
    fun consentLostAfterTapButBeforeExecutionBlocksRead() =
        runTest {
            val f = ImportCoordinator0723Fixture(this, enabled = false)
            f.coordinator.onPasteTap(ImportTask.PURPOSE_PRODUCT_LOOKUP)
            f.environment = f.environment.copy(privacyAgreed = false)
            f.settle()
            val reads = f.reads.size
            assertEquals(0, reads)
            f.environment = f.environment.copy(privacyAgreed = true)
            f.coordinator.onPasteTap(ImportTask.PURPOSE_PRODUCT_LOOKUP)
            f.settle()
            assertEquals(1, f.reads.size)
        }

    @Test
    fun samePageDoubleTapIsSuppressedButAnotherPageOfTheSameRouteCanPaste() =
        runTest {
            for (queued in listOf(true, false)) {
                val f = ImportCoordinator0723Fixture(this, enabled = false)
                f.coordinator.onPasteTap(ImportTask.PURPOSE_PRODUCT_LOOKUP)
                if (!queued) f.settle()
                f.coordinator.onPasteTap(ImportTask.PURPOSE_PRODUCT_LOOKUP)
                f.environment = f.environment.copy(page = f.environment.page.copy(pageInstanceId = "page-B"))
                f.coordinator.onPasteTap(ImportTask.PURPOSE_PRODUCT_LOOKUP)
                f.settle()
                val onB = f.reads.filter { it.pageInstanceId == "page-B" }
                assertEquals("跨页有效点击不能被 A 的同目的任务挡住 queued=$queued", 1, onB.size)
                assertTrue(f.reads.count { it.pageInstanceId == "page-A" } <= 1)
                val b = onB.single()
                f.readText(b, "fresh-B-text")
                f.candidates(b, "fresh-B-candidate")
                f.parsed(b, "fresh-B-card")
                assertEquals(
                    "fresh-B-card",
                    f.cards
                        .single()
                        .second["card"]
                        .toString()
                        .trim('"'),
                )
            }
        }

    @Test
    fun featureConsentRevocationKeepsManualButPrivacyRevocationCancelsIt() =
        runTest {
            for (privacyRevoked in listOf(false, true)) {
                val f = ImportCoordinator0723Fixture(this, enabled = false)
                f.coordinator.onPasteTap(ImportTask.PURPOSE_PRODUCT_LOOKUP)
                f.settle()
                val task = f.reads.single()
                f.readText(task)
                f.candidates(task)
                f.environment = f.environment.copy(privacyAgreed = !privacyRevoked)
                f.coordinator.onConsentRevoked()
                f.parsed(task, "manual-survives-feature-only")
                val cards = f.cards.toList()
                assertEquals(if (privacyRevoked) 0 else 1, cards.size)
                assertEquals(if (privacyRevoked) "cancelled" else "success", f.finishes.single().second)
            }
        }
}
