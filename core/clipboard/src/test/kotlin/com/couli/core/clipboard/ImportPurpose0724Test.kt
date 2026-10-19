@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.couli.core.clipboard

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** R19/R20：这里只锁三种目的的成功分流；桥 90004/90500 和 AgentChat UI 留给 0725。 */
class ImportPurpose0724Test {
    @Test
    fun g27_agentIsNotAnAutomaticTargetUntil0725ProvidesRendering() =
        runTest {
            val f = ImportCoordinator0723Fixture(this)
            f.environment = f.environment.copy(page = f.environment.page.copy(route = "AgentChat", homeRoot = false))
            f.foreground()
            f.settle()
            assertEquals(0, f.reads.size)
            assertEquals(0, f.parses.size)
            assertEquals(0, f.cards.size)
            f.environment = f.environment.copy(page = f.environment.page.copy(route = "Home", homeRoot = true))
            f.coordinator.onPageReady()
            f.settle()
            assertEquals("非目标页丢弃本轮，不回首页补读", 0, f.reads.size)
            f.background()
            f.foreground()
            f.settle()
            assertEquals(1, f.reads.size)
        }

    @Test
    fun onlyProductLookupExtractsWhileFillAndBridgeDeliverUnmodifiedTextOnce() =
        runTest {
            for (purpose in listOf("product_lookup", "fill_input", "bridge_read")) {
                val f = ImportCoordinator0723Fixture(this, enabled = false)
                f.coordinator.onPasteTap(purpose)
                f.settle()
                val task = f.reads.single()
                val text = "原样文本\n https://u.jd.com/AbC123  "
                f.readText(task, text)
                f.readText(task, "重复回调不能覆盖原始结果")
                val extracts = f.extractions.toList()
                val delivered = f.deliveries.toList()
                assertNull(f.coordinator.snapshot().activeAutoTask)
                if (purpose == "product_lookup") {
                    assertEquals(listOf(text), extracts.map { it.second })
                    assertEquals(0, delivered.size)
                    f.candidates(task)
                    f.parsed(task)
                    assertEquals(1, f.cards.size)
                } else {
                    assertEquals(0, extracts.size)
                    assertEquals(listOf(text), delivered.map { it.second })
                    assertEquals(purpose, delivered.single().first.purpose)
                    assertEquals(0, f.parses.size)
                    assertEquals(0, f.cards.size)
                    assertEquals(listOf("success"), f.finishes.map { it.second })
                }
            }
        }
}
