@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.couli.core.clipboard

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** 四类撤销中「撤回同意」分功能/隐私两个入口场景；每类都穿过五个执行窗口。 */
@RunWith(Parameterized::class)
class ImportCancellation0723Test(
    private val reason: String,
    private val window: String,
) {
    @Test
    fun cancelStopsQueuedReadDropsCandidateAndIgnoresLateResponse() =
        runTest {
            val f = ImportCoordinator0723Fixture(this)
            f.foreground()
            if (window != "queued_read") f.settle()
            // 不强行要求尚未执行的调度已经分配 requestId。
            val task = f.reads.singleOrNull() ?: f.coordinator.snapshot().activeAutoTask
            if (window in listOf("candidate_inflight", "queued_parse", "parse_inflight")) {
                f.readText(requireNotNull(task))
            }
            if (window in listOf("queued_parse", "parse_inflight")) {
                f.coordinator.onCandidates(requireNotNull(task), ImportCandidateResult("candidates", listOf("before-cancel")))
                if (window == "parse_inflight") f.settle()
            }
            when (reason) {
                "background" -> {
                    f.background()
                }

                "page_destroyed" -> {
                    f.coordinator.onPageDestroyed("page-A")
                }

                "switch_off" -> {
                    assertTrue(f.prefs.setOff()) // 与 0722 钩子顺序一致：store 先关，再通知。
                    f.coordinator.onSwitchOff()
                }

                "feature_consent" -> {
                    f.coordinator.onConsentRevoked()
                }

                "privacy_consent" -> {
                    assertTrue(f.prefs.clearOnPrivacyWithdraw())
                    f.environment = f.environment.copy(privacyAgreed = false)
                    f.coordinator.onConsentRevoked()
                }
            }
            f.settle()
            val activeAfterCancel = f.coordinator.snapshot().activeAutoTask
            assertNull(activeAfterCancel)
            if (task != null) {
                // 替身故意无视取消，模拟系统/网络已经投递但现在才送达的旧回调。
                f.coordinator.onRead(task, ImportReadResult("text", "late-text"))
                f.coordinator.onCandidates(task, ImportCandidateResult("candidates", listOf("late-candidate")))
                f.coordinator.onParsed(task, buildJsonObject { put("card", "late-card") })
            }
            f.settle()
            val reads = f.reads.size
            val extracts = f.extractions.size
            val parses = f.parses.size
            val cards = f.cards.size
            val finishes = f.finishes.toList()
            assertEquals("$reason/$window", if (window == "queued_read") 0 else 1, reads)
            assertEquals(if (window in listOf("candidate_inflight", "queued_parse", "parse_inflight")) 1 else 0, extracts)
            assertEquals(if (window == "parse_inflight") 1 else 0, parses)
            assertEquals(0, cards)
            if (task != null) {
                assertEquals(listOf("cancelled"), finishes.filter { it.first.requestId == task.requestId }.map { it.second })
            }
        }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}/{1}")
        fun cases(): List<Array<String>> =
            listOf("background", "page_destroyed", "switch_off", "feature_consent", "privacy_consent").flatMap { reason ->
                listOf("queued_read", "read_inflight", "candidate_inflight", "queued_parse", "parse_inflight").map {
                    arrayOf(reason, it)
                }
            }
    }
}
