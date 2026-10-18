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

class ImportStaleCallback0723Test {
    @Test
    fun r15ReadAndParseCallbacksEachMatchEveryOwnershipField() =
        runTest {
            for (stage in listOf("read", "parse")) {
                for (field in listOf("page", "consent", "reconsented", "missing_consent", "switch", "interactive")) {
                    val f = ImportCoordinator0723Fixture(this)
                    val task = if (stage == "read") f.start() else f.parsing()
                    when (field) {
                        "page" -> {
                            f.environment = f.environment.copy(page = f.environment.page.copy(pageInstanceId = "page-B"))
                        }

                        "consent" -> {
                            f.environment = f.environment.copy(configData = ImportCoordinator0723Fixture.config("v2"))
                        }

                        "reconsented" -> {
                            f.environment = f.environment.copy(configData = ImportCoordinator0723Fixture.config("v2"))
                            assertTrue(f.prefs.enable("v2")) // 当前门槛重新全过，旧票据的 v1 仍必须过期。
                        }

                        "missing_consent" -> {
                            f.environment = f.environment.copy(configData = ImportCoordinator0723Fixture.config(null))
                        }

                        "switch" -> {
                            assertTrue(f.prefs.setOff())
                        }

                        // 不靠取消钩子，也必须在回包时复核。
                        "interactive" -> {
                            f.environment = f.environment.copy(page = f.environment.page.copy(interactive = false))
                        }
                    }
                    if (stage == "read") f.readText(task) else f.parsed(task)
                    f.settle()
                    f.assertFinished(task, "expired")
                    val uploads = f.parses.size
                    val cards = f.cards.size
                    val extracts = f.extractions.size
                    assertEquals("$stage/$field", if (stage == "parse") 1 else 0, uploads)
                    assertEquals("$stage/$field", if (stage == "parse") 1 else 0, extracts)
                    assertEquals("$stage/$field", 0, cards)
                }
            }
        }

    @Test
    fun r15CompletedAutoRequestCannotDeliverAgainOrReleaseTheCurrentOwner() =
        runTest {
            for (stage in listOf("read", "parse")) {
                val f = ImportCoordinator0723Fixture(this)
                val old = f.parsing()
                f.parsed(old, "previous-card")
                f.background()
                f.foreground()
                f.settle()
                val task = f.reads.last()
                assertNotEquals(old.requestId, task.requestId)
                if (stage == "parse") {
                    f.readText(task, "current-text")
                    f.candidates(task, "current-candidate")
                }
                val beforeExtracts = f.extractions.toList()
                val beforeParses = f.parses.toList()
                val beforeCards = f.cards.toList()
                if (stage == "read") f.readText(old, "late-text") else f.parsed(old, "late-card")
                f.settle()
                val active = f.coordinator.snapshot().activeAutoTask
                val extracts = f.extractions.toList()
                val parses = f.parses.toList()
                val cards = f.cards.toList()
                assertEquals(task.requestId, active?.requestId)
                assertEquals(beforeExtracts, extracts)
                assertEquals(beforeParses, parses)
                assertEquals(beforeCards, cards)
                if (stage == "read") {
                    f.readText(task, "current-text")
                    f.candidates(task, "current-candidate")
                }
                f.parsed(task, "current-card")
                assertEquals(buildJsonObject { put("card", "current-card") }, f.cards.last().second)
                assertEquals(2, f.cards.size)
            }
        }

    @Test
    fun r17OldReadAndParseCannotClearNewTaskOrOverwriteItsResult() =
        runTest {
            for (stage in listOf("read", "parse")) {
                val f = ImportCoordinator0723Fixture(this)
                val old = if (stage == "read") f.start() else f.parsing()
                f.background()
                f.foreground()
                f.settle()
                val fresh = f.reads.last()
                assertNotEquals(old.requestId, fresh.requestId)
                assertNotEquals(old.epoch, fresh.epoch)
                if (stage == "read") f.readText(old, "stale-text") else f.parsed(old, "stale-card")
                val active = f.coordinator.snapshot().activeAutoTask
                val cardsBefore = f.cards.size
                assertEquals(fresh.requestId, active?.requestId)
                assertEquals(0, cardsBefore)
                f.readText(fresh, "fresh-text")
                f.candidates(fresh, "fresh-candidate")
                f.parsed(fresh, "fresh-card")
                val cards = f.cards.toList()
                assertEquals(listOf(buildJsonObject { put("card", "fresh-card") }), cards.map { it.second })
                assertEquals(listOf("fresh-candidate"), f.parses.last().second)
                assertEquals("fresh-text", f.extractions.last().second)
                assertEquals(1, f.finishes.count { it.first.requestId == fresh.requestId })
            }
        }

    @Test
    fun r15ManualMatchesPageAndRequestButIgnoresAutoOnlyFields() =
        runTest {
            for (stage in listOf("read", "parse")) {
                for (pageMatches in listOf(true, false)) {
                    val f = ImportCoordinator0723Fixture(this, enabled = false)
                    f.coordinator.onPasteTap("product_lookup")
                    f.settle()
                    val task = f.reads.single()
                    assertEquals("manual", task.kind)
                    assertEquals("product_lookup", task.purpose)
                    assertTrue(!f.coordinator.snapshot().consumed)
                    assertNull(f.coordinator.snapshot().activeAutoTask)
                    if (stage == "parse") {
                        f.readText(task)
                        f.candidates(task)
                    }
                    f.environment =
                        f.environment.copy(
                            configData = ImportCoordinator0723Fixture.config(null),
                            page = f.environment.page.copy(pageInstanceId = if (pageMatches) "page-A" else "page-B"),
                        )
                    // 第一次上报前台轮次；没有后台/撤销事件。旧手动票据保持原样。
                    val previousEpoch = f.coordinator.snapshot().epoch
                    f.coordinator.onLifecycle(ImportLifecycleEvent.PROCESS_FOREGROUND)
                    f.settle()
                    assertNotEquals(previousEpoch, f.coordinator.snapshot().epoch)
                    if (stage == "read") {
                        f.readText(task, "manual-text")
                        f.candidates(task, "manual-candidate")
                    }
                    f.parsed(task, "manual-card")
                    val cards = f.cards.toList()
                    val parses = f.parses.toList()
                    assertEquals(if (pageMatches) 1 else 0, cards.size)
                    assertEquals(if (pageMatches || stage == "parse") 1 else 0, parses.size)
                    if (pageMatches) assertEquals(buildJsonObject { put("card", "manual-card") }, cards.single().second)
                    assertTrue(!f.coordinator.snapshot().consumed)
                }
            }
        }

    @Test
    fun r15CompletedManualRequestCannotDeliverToAnotherManualTaskInTheSameEpoch() =
        runTest {
            for (stage in listOf("read", "parse")) {
                val f = ImportCoordinator0723Fixture(this, enabled = false)
                f.foreground()
                f.settle()
                f.coordinator.onPasteTap("product_lookup")
                f.settle()
                val old = f.reads.single()
                f.readText(old)
                f.candidates(old)
                f.parsed(old, "previous-manual-card")
                f.coordinator.onPasteTap("product_lookup")
                f.settle()
                val task = f.reads.last()
                assertNotEquals(old.requestId, task.requestId)
                assertEquals(old.epoch, task.epoch)
                assertEquals(old.pageInstanceId, task.pageInstanceId)
                if (stage == "parse") {
                    f.readText(task)
                    f.candidates(task)
                }
                val beforeExtracts = f.extractions.toList()
                val beforeParses = f.parses.toList()
                val beforeCards = f.cards.toList()
                if (stage == "read") f.readText(old, "late-text") else f.parsed(old, "late-card")
                f.settle()
                val extracts = f.extractions.toList()
                val parses = f.parses.toList()
                val cards = f.cards.toList()
                assertEquals(beforeExtracts, extracts)
                assertEquals(beforeParses, parses)
                assertEquals(beforeCards, cards)
                if (stage == "read") {
                    f.readText(task)
                    f.candidates(task)
                }
                f.parsed(task, "manual-owner-card")
                assertEquals(buildJsonObject { put("card", "manual-owner-card") }, f.cards.last().second)
                assertEquals(2, f.cards.size)
                assertNull(f.coordinator.snapshot().activeAutoTask)
            }
        }

    @Test
    fun r18ManualStillWorksAfterAutomaticUnavailableInTheSameEpoch() =
        runTest {
            val f = ImportCoordinator0723Fixture(this)
            val auto = f.start()
            f.coordinator.onRead(auto, ImportReadResult("unavailable"))
            f.settle()
            f.coordinator.onPasteTap("product_lookup")
            f.settle()
            val manual = f.reads.last()
            assertEquals(2, f.reads.size)
            assertEquals("manual", manual.kind)
            assertNotEquals(auto.requestId, manual.requestId)
            f.readText(manual, "manual-after-failure")
            f.candidates(manual, "manual-candidate")
            f.parsed(manual, "manual-card")
            assertEquals(buildJsonObject { put("card", "manual-card") }, f.cards.single().second)
            assertTrue(f.coordinator.snapshot().consumed)
        }
}
