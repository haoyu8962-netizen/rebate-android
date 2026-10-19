@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.couli.core.clipboard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FU-AND-0723：所有负向断言均在虚拟调度队列结算后取局部快照；没有 sleep 或真实剪贴板。 */
class ImportCoordinator0723Test {
    @Test
    fun r12ColdStartAndRealBackgroundCreateEpochsButActivityNoiseDoesNot() =
        runTest {
            val f = ImportCoordinator0723Fixture(this)
            val first = f.start()
            assertEquals(1L, first.epoch)
            assertEquals("auto", first.kind)
            assertEquals("page-A", first.pageInstanceId)
            assertEquals("v1", first.consentVersion)
            f.coordinator.onRead(first, ImportReadResult("empty"))
            f.settle()
            val noise =
                listOf(
                    ImportLifecycleEvent.ACTIVITY_RESUMED,
                    ImportLifecycleEvent.CONFIGURATION_CHANGED,
                    ImportLifecycleEvent.SYSTEM_UI_INACTIVE,
                    ImportLifecycleEvent.SYSTEM_UI_ACTIVE,
                    ImportLifecycleEvent.PROCESS_FOREGROUND, // 重复 start 不是新一轮。
                )
            noise.forEach { event ->
                f.coordinator.onLifecycle(event)
                f.environment = f.environment.copy(page = f.environment.page.copy(route = "AgentChat", homeRoot = false))
                f.coordinator.onPageReady()
                f.settle()
                val epoch = f.coordinator.snapshot().epoch
                val reads = f.reads.size
                assertEquals(event.name, first.epoch, epoch)
                assertEquals(event.name, 1, reads)
            }
            // G27：噪声可以切到 AgentChat，但下一轮的有效读取对照要回到 Home（0725 再恢复 Agent）。
            f.environment = f.environment.copy(page = f.environment.page.copy(route = "Home", homeRoot = true))
            // G13：真退后台后 1ms 就回来仍是一轮；不把 ProcessLifecycleOwner 的延迟当节流。
            repeat(3) { index ->
                f.background()
                f.coordinator.onPageReady()
                f.settle()
                val backgroundReads = f.reads.size
                assertEquals(index + 1, backgroundReads)
                advanceTimeBy(1)
                f.foreground()
                f.settle()
                val task = f.reads.last()
                assertEquals(index + 2L, task.epoch)
                assertEquals(index + 2, f.reads.size)
                f.coordinator.onRead(task, ImportReadResult("empty"))
                f.settle()
            }
        }

    @Test
    fun g13FastResumeWithoutProcessStopKeepsEpochAndRealStopStartStillAdvances() =
        runTest {
            val f = ImportCoordinator0723Fixture(this)
            val task = f.start()
            f.coordinator.onRead(task, ImportReadResult("empty"))
            f.settle()
            // 进程观察者尚未发 stop 的快速失活/激活；不虚构后台事件，也不等固定 700ms。
            f.environment = f.environment.copy(page = f.environment.page.copy(focused = false))
            f.coordinator.onLifecycle(ImportLifecycleEvent.SYSTEM_UI_INACTIVE)
            advanceTimeBy(1)
            f.environment = f.environment.copy(page = f.environment.page.copy(focused = true))
            f.coordinator.onLifecycle(ImportLifecycleEvent.ACTIVITY_RESUMED)
            f.coordinator.onLifecycle(ImportLifecycleEvent.PROCESS_FOREGROUND)
            f.coordinator.onPageReady()
            f.settle()
            val epoch = f.coordinator.snapshot().epoch
            val reads = f.reads.size
            assertEquals(task.epoch, epoch)
            assertEquals(1, reads)
            f.background()
            advanceTimeBy(1)
            f.foreground()
            f.settle()
            assertEquals(2, f.reads.size)
            assertNotEquals(task.epoch, f.reads.last().epoch)
        }

    @Test
    fun r11WorkerCallbacksReturnToInjectedMainBeforeCheckingGatesAndDelivering() =
        runTest {
            val f = ImportCoordinator0723Fixture(this)
            val task = f.start()
            withContext(Dispatchers.Default) {
                f.coordinator.onRead(task, ImportReadResult("text", "worker-text"))
            }
            f.settle()
            assertEquals("worker-text", f.extractions.single().second)
            withContext(Dispatchers.Default) {
                f.coordinator.onCandidates(task, ImportCandidateResult("candidates", listOf("worker-candidate")))
            }
            f.settle()
            withContext(Dispatchers.Default) {
                f.coordinator.onParsed(task, buildJsonObject { put("card", "worker-card") })
            }
            f.settle()
            assertEquals(buildJsonObject { put("card", "worker-card") }, f.cards.single().second)
            f.assertFinished(task, "success")
        }

    @Test
    fun r11ClaimPrecedesReentrantReadAndRepeatedReadinessCannotStartAnotherTask() =
        runTest {
            val f = ImportCoordinator0723Fixture(this)
            f.duringRead = { task ->
                assertTrue(f.coordinator.snapshot().consumed)
                assertEquals(
                    task.requestId,
                    f.coordinator
                        .snapshot()
                        .activeAutoTask
                        ?.requestId,
                )
                repeat(5) { f.coordinator.onPageReady() }
            }
            val task = f.start()
            repeat(10) { f.coordinator.onPageReady() }
            f.settle()
            val reads = f.reads.size
            val active = f.coordinator.snapshot().activeAutoTask
            assertEquals(1, reads)
            assertEquals(task.requestId, active?.requestId)
        }

    @Test
    fun r11ScheduledReadRechecksAllExternalGatesBeforeTouchingClipboard() =
        runTest {
            for (gate in listOf("privacy", "version", "switch", "config", "ready", "focus", "sensitive", "modal", "web")) {
                val f = ImportCoordinator0723Fixture(this)
                f.foreground() // 尚未执行注入调度器上的读取。
                when (gate) {
                    "privacy" -> {
                        f.environment = f.environment.copy(privacyAgreed = false)
                    }

                    "version" -> {
                        f.environment = f.environment.copy(configData = ImportCoordinator0723Fixture.config(null))
                    }

                    "switch" -> {
                        assertTrue(f.prefs.setOff())
                    }

                    "config" -> {
                        f.environment =
                            f.environment.copy(
                                configData =
                                    kotlinx.serialization.json.Json.parseToJsonElement(
                                        """{"clipboard":{"consent_version":"v1"},"features":{"clipboard":{"auto_read":{"android":false}}}}""",
                                    ) as kotlinx.serialization.json.JsonObject,
                            )
                    }

                    "ready" -> {
                        f.environment = f.environment.copy(page = f.environment.page.copy(ready = false))
                    }

                    "focus" -> {
                        f.environment = f.environment.copy(page = f.environment.page.copy(focused = false))
                    }

                    "sensitive" -> {
                        f.environment = f.environment.copy(page = f.environment.page.copy(route = "Withdraw"))
                    }

                    "modal" -> {
                        f.environment = f.environment.copy(modal = "trade_jump")
                    }

                    "web" -> {
                        f.environment = f.environment.copy(webSuppressed = true)
                    }
                }
                f.settle()
                val reads = f.reads.size
                val parses = f.parses.size
                assertEquals(gate, 0, reads)
                assertEquals(gate, 0, parses)
            }
        }

    @Test
    fun g21NoticeAppearanceConsumesBeforeEnableAndOldNoticeCannotConsumeNextEpoch() =
        runTest {
            val f = ImportCoordinator0723Fixture(this, enabled = null)
            f.foreground()
            f.settle()
            val shownEpoch = f.notices.single().first
            assertTrue(f.coordinator.snapshot().consumed)
            assertTrue(f.prefs.enable("v1"))
            f.coordinator.onPageReady()
            f.settle()
            val afterEnable = f.reads.size
            assertEquals(0, afterEnable)
            f.background()
            f.coordinator.onLifecycle(ImportLifecycleEvent.PROCESS_FOREGROUND)
            f.coordinator.onNoticeShown(shownEpoch) // 上一轮弹层迟到通知。
            f.coordinator.onPageReady()
            f.settle()
            assertEquals(1, f.reads.size)
            assertNotEquals(shownEpoch, f.reads.single().epoch)
        }

    @Test
    fun r16SystemDialogFocusAndActivityChangesPreserveSameAwaitingTask() =
        runTest {
            val f = ImportCoordinator0723Fixture(this)
            val task = f.start()
            f.coordinator.onAwaitingSystem(task)
            f.environment = f.environment.copy(page = f.environment.page.copy(focused = false))
            f.coordinator.onLifecycle(ImportLifecycleEvent.SYSTEM_UI_INACTIVE)
            f.coordinator.onPageReady()
            f.settle()
            val waiting = f.coordinator.snapshot().activeAutoTask
            val finishes = f.finishes.toList()
            assertEquals(task.requestId, waiting?.requestId)
            assertEquals("awaiting_system", waiting?.phase)
            assertTrue(finishes.isEmpty())
            f.environment = f.environment.copy(page = f.environment.page.copy(focused = true))
            f.coordinator.onLifecycle(ImportLifecycleEvent.SYSTEM_UI_ACTIVE)
            f.coordinator.onLifecycle(ImportLifecycleEvent.ACTIVITY_RESUMED)
            f.coordinator.onLifecycle(ImportLifecycleEvent.CONFIGURATION_CHANGED)
            f.coordinator.onPageDestroyed("unrelated-page")
            f.coordinator.onPageReady()
            f.settle()
            val reads = f.reads.size
            val stillActive = f.coordinator.snapshot().activeAutoTask
            assertEquals(1, reads)
            assertEquals(task.requestId, stillActive?.requestId)
            f.readText(task, "after-system")
            f.candidates(task, "after-system-candidate")
            f.parsed(task, "after-system-card")
            assertEquals("after-system", f.extractions.single().second)
            assertEquals(listOf("after-system-candidate"), f.parses.single().second)
            assertEquals(buildJsonObject { put("card", "after-system-card") }, f.cards.single().second)
        }

    @Test
    fun r16TemporaryGateChangesDoNotInventExtraCancellationReasons() =
        runTest {
            for (change in listOf("not_ready", "not_interactive", "modal", "route", "version")) {
                val f = ImportCoordinator0723Fixture(this)
                val task = f.start()
                when (change) {
                    "not_ready" -> f.environment = f.environment.copy(page = f.environment.page.copy(ready = false))
                    "not_interactive" -> f.environment = f.environment.copy(page = f.environment.page.copy(interactive = false))
                    "modal" -> f.environment = f.environment.copy(modal = "payment")
                    "route" -> f.environment = f.environment.copy(page = f.environment.page.copy(route = "AgentChat", homeRoot = false))
                    "version" -> f.environment = f.environment.copy(configData = ImportCoordinator0723Fixture.config("v2"))
                }
                f.coordinator.onPageReady()
                f.settle()
                val active = f.coordinator.snapshot().activeAutoTask
                val finishes = f.finishes.toList()
                val reads = f.reads.size
                assertEquals(change, task.requestId, active?.requestId)
                assertTrue(change, finishes.isEmpty())
                assertEquals(change, 1, reads)
            }
        }

    @Test
    fun r17SixTerminalsReleaseAndNeverRefundTheConsumedEpoch() =
        runTest {
            for (terminal in listOf("success", "no_candidates", "dedup", "unavailable", "cancelled", "expired")) {
                val f = ImportCoordinator0723Fixture(this)
                val task = f.start()
                when (terminal) {
                    "success" -> {
                        f.readText(task)
                        f.candidates(task)
                        f.parsed(task)
                    }

                    "no_candidates", "dedup" -> {
                        f.readText(task)
                        f.coordinator.onCandidates(task, ImportCandidateResult(terminal))
                    }

                    "unavailable" -> {
                        f.coordinator.onRead(task, ImportReadResult("unavailable"))
                    }

                    "cancelled" -> {
                        f.coordinator.onPageDestroyed(task.pageInstanceId)
                    }

                    "expired" -> {
                        f.environment = f.environment.copy(page = f.environment.page.copy(pageInstanceId = "page-B"))
                        f.readText(task)
                    }
                }
                f.settle()
                f.assertFinished(task, terminal)
                repeat(3) { f.coordinator.onPageReady() }
                f.settle()
                val reads = f.reads.size
                val cards = f.cards.size
                val uploads = f.parses.size
                assertEquals(terminal, 1, reads)
                assertEquals(terminal, if (terminal == "success") 1 else 0, cards)
                assertEquals(terminal, if (terminal == "success") 1 else 0, uploads)
            }
        }

    @Test
    fun r18EmptyUnavailableAndThrownReadsEndWithoutPollingButNextEpochCanRead() =
        runTest {
            for (outcome in listOf("empty", "unavailable", "throw_once", "throw_forever")) {
                val f = ImportCoordinator0723Fixture(this)
                if (outcome.startsWith("throw")) {
                    f.readFailure = {
                        if (outcome == "throw_forever" || f.reads.size == 1) IllegalStateException("fixture-read") else null
                    }
                }
                val task = f.start()
                if (!outcome.startsWith("throw")) f.coordinator.onRead(task, ImportReadResult(outcome))
                f.settle()
                f.assertFinished(task, "unavailable")
                assertEquals(listOf("page-A"), f.fallbacks)
                advanceTimeBy(86_400_000)
                repeat(3) { f.coordinator.onPageReady() }
                f.settle()
                val reads = f.reads.size
                val extractions = f.extractions.size
                val uploads = f.parses.size
                assertEquals(outcome, 1, reads)
                assertEquals(outcome, 0, extractions)
                assertEquals(outcome, 0, uploads)
                f.background()
                f.foreground()
                f.settle()
                assertEquals(outcome, 2, f.reads.size)
                if (outcome == "throw_forever") {
                    f.assertFinished(f.reads.last(), "unavailable")
                } else {
                    val recovered = f.reads.last()
                    f.readText(recovered, "new-text")
                    f.candidates(recovered, "new-candidate")
                    f.parsed(recovered, "new-card")
                    assertEquals(buildJsonObject { put("card", "new-card") }, f.cards.single().second)
                    // 恢复后再次失败，不得复用已结束任务或覆盖成功结果。
                    f.background()
                    f.foreground()
                    f.settle()
                    val third = f.reads.last()
                    f.coordinator.onRead(third, ImportReadResult("unavailable"))
                    f.settle()
                    f.assertFinished(third, "unavailable")
                    assertEquals(1, f.cards.size)
                }
            }
        }

    @Test
    fun r17DuplicateCallbacksCannotUploadOrPresentTwice() =
        runTest {
            val f = ImportCoordinator0723Fixture(this)
            val task = f.start()
            f.readText(task)
            f.readText(task, "duplicate-text")
            val extracts = f.extractions.toList()
            assertEquals(listOf("old-text"), extracts.map { it.second })
            f.candidates(task)
            f.candidates(task, "duplicate-candidate")
            val parses = f.parses.toList()
            assertEquals(listOf(listOf("old-candidate")), parses.map { it.second })
            f.parsed(task)
            f.parsed(task, "duplicate-card")
            f.readText(task, "very-late-text")
            val cards = f.cards.toList()
            val finish = f.finishes.toList()
            assertEquals(listOf(buildJsonObject { put("card", "old-card") }), cards.map { it.second })
            assertEquals(1, finish.size)
            assertNull(f.coordinator.snapshot().activeAutoTask)
        }
}
