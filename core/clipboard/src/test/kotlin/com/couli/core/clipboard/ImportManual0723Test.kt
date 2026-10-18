@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.couli.core.clipboard

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportManual0723Test {
    @Test
    fun r19PrivacyBlocksManualReadAndARealTapAfterRecoveryCanRead() =
        runTest {
            val f = ImportCoordinator0723Fixture(this)
            assertTrue(f.prefs.clearOnPrivacyWithdraw())
            f.environment = f.environment.copy(privacyAgreed = false)
            f.coordinator.onPasteTap("product_lookup")
            f.settle()
            val reads = f.reads.size
            val extracts = f.extractions.size
            val parses = f.parses.size
            val cards = f.cards.size
            assertEquals(0, reads)
            assertEquals(0, extracts)
            assertEquals(0, parses)
            assertEquals(0, cards)
            f.environment = f.environment.copy(privacyAgreed = true)
            assertTrue(f.prefs.markNoticeSeen("v1"))
            // 恢复本身不重放先前被拒的点击，必须再点一次。
            f.coordinator.onPageReady()
            f.settle()
            val beforeFreshTap = f.reads.size
            assertEquals(0, beforeFreshTap)
            f.coordinator.onPasteTap("product_lookup")
            f.settle()
            val manual = f.reads.single()
            assertEquals("manual", manual.kind)
            assertNull(f.coordinator.snapshot().activeAutoTask)
            f.readText(manual, "recovered-text")
            f.candidates(manual, "recovered-candidate")
            f.parsed(manual, "recovered-card")
            assertEquals(buildJsonObject { put("card", "recovered-card") }, f.cards.single().second)
        }

    @Test
    fun r19ManualDoesNotOccupyAutoSlotAndDoesNotBlockTheFirstForegroundEpoch() =
        runTest {
            for (alreadyForeground in listOf(false, true)) {
                val f = ImportCoordinator0723Fixture(this, enabled = false)
                if (alreadyForeground) {
                    f.foreground()
                    f.settle()
                }
                f.coordinator.onPasteTap("product_lookup")
                f.settle()
                val manual = f.reads.single()
                val before = f.coordinator.snapshot()
                assertNull(before.activeAutoTask)
                assertTrue(!before.consumed)
                assertTrue(f.prefs.enable("v1"))
                if (alreadyForeground) f.coordinator.onPageReady() else f.foreground()
                f.settle()
                val auto = f.reads.last()
                assertEquals(2, f.reads.size)
                assertEquals("auto", auto.kind)
                assertNotEquals(manual.requestId, auto.requestId)
                if (!alreadyForeground) assertNotEquals(before.epoch, auto.epoch)
                assertEquals(
                    auto.requestId,
                    f.coordinator
                        .snapshot()
                        .activeAutoTask
                        ?.requestId,
                )
                // 手动结束不能释放仍在读的自动任务；两个结果都交付各自的票据。
                f.readText(manual, "manual-text")
                f.candidates(manual, "manual-candidate")
                f.parsed(manual, "manual-card")
                assertEquals(
                    auto.requestId,
                    f.coordinator
                        .snapshot()
                        .activeAutoTask
                        ?.requestId,
                )
                f.readText(auto, "auto-text")
                f.candidates(auto, "auto-candidate")
                f.parsed(auto, "auto-card")
                val cards = f.cards.toList()
                assertEquals(listOf(manual.requestId, auto.requestId), cards.map { it.first.requestId })
                assertEquals(
                    listOf("manual-card", "auto-card").map { value -> buildJsonObject { put("card", value) } },
                    cards.map { it.second },
                )
                assertNull(f.coordinator.snapshot().activeAutoTask)
                assertTrue(f.coordinator.snapshot().consumed)
            }
        }

    @Test
    fun r19SwitchOffDoesNotCancelManualButBackgroundAndPageDestructionDo() =
        runTest {
            for (reason in listOf("switch_off", "background", "page_destroyed")) {
                val f = ImportCoordinator0723Fixture(this, enabled = false)
                f.foreground()
                f.settle()
                f.coordinator.onPasteTap("product_lookup")
                f.settle()
                val task = f.reads.single()
                f.readText(task)
                f.candidates(task)
                when (reason) {
                    "switch_off" -> {
                        assertTrue(f.prefs.enable("v1"))
                        assertTrue(f.prefs.setOff())
                        f.coordinator.onSwitchOff()
                    }

                    "background" -> {
                        f.background()
                    }

                    "page_destroyed" -> {
                        f.coordinator.onPageDestroyed(task.pageInstanceId)
                    }
                }
                f.settle()
                f.parsed(task, "manual-card")
                val cards = f.cards.toList()
                val terminals = f.finishes.filter { it.first.requestId == task.requestId }.map { it.second }
                assertEquals(if (reason == "switch_off") 1 else 0, cards.size)
                assertEquals(listOf(if (reason == "switch_off") "success" else "cancelled"), terminals)
                assertNull(f.coordinator.snapshot().activeAutoTask)
            }
        }
}
