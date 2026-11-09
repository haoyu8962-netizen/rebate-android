@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.couli.app.search.submit

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.search.submit.SubmitReplies.LINK
import com.couli.app.search.submit.SubmitReplies.SERVER_ERROR
import com.couli.app.search.submit.SubmitReplies.TITLE
import com.couli.app.search.submit.SubmitReplies.TITLED_LINK
import com.couli.app.search.submit.SubmitReplies.UNSUPPORTED
import com.couli.app.search.submit.SubmitReplies.WORD
import com.couli.core.network.ApiResult
import com.couli.core.router.UpdateCheck
import com.couli.feature.search.SearchPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * FU-AND-SEARCH-LINK-OWNER 测试 v3（评审 r2 S0-1）：矩阵之外的恢复路径。
 * - 恢复门等待期间用户自己提交了新的（链接或普通词）：门放行后旧查询不得复活，也不得冲掉用户的新提交；
 * - 恢复门超时（5 秒）、恢复门等待中离页；
 * - 保存时无可搜平台（关键词还在）、识别在途（还没有查询）、识别已呈现（不重放）；
 * - 恢复成输入态之后再搜普通词或再提交链接：来源跟着新的查询走；路由 q 普通词照常恢复。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
@Suppress("TooManyFunctions") // 每条恢复路径一条
class SearchSubmitOwnerRestoreCasesTest {
    @Before fun useTestMain() = Dispatchers.setMain(StandardTestDispatcher())

    @After fun resetMain() = Dispatchers.resetMain()

    // ---------------------------------------------------------------- 恢复门

    @Test
    fun freshLinkSubmittedWhileGateClosedKeepsItsRecognition() =
        runTest {
            fallbackShown { f ->
                restartedPage(f, RestoreMode.PROCESS, RestoreGate.GATED) { g, base ->
                    g.onSearchPage()
                    drainRestore()
                    g.model.onSubmit()
                    drainRestore()
                    assertEquals("前提：用户在门内重新提交，发一次识别", listOf(RESTORE_PARSE), g.api.calls.map { it.operationId })
                    g.setUpdateCheck(UpdateCheck.OK)
                    settleRestore()
                    assertEquals("门放行后旧查询不得复活", listOf(RESTORE_PARSE), g.api.calls.map { it.operationId })
                    g.api.parseReply.complete(SubmitReplies.single())
                    settleRestore()
                    assertEquals("门放行不得作废用户的新识别：结果照常呈现", base.navigations + 1, g.navigations.size)
                }
            }
        }

    @Test
    fun freshWordSubmittedWhileGateClosedIsNotOverwritten() =
        runTest {
            fallbackShown { f ->
                restartedPage(f, RestoreMode.PROCESS, RestoreGate.GATED) { g, _ ->
                    g.onSearchPage()
                    drainRestore()
                    g.model.onInput(WORD)
                    g.model.onSubmit()
                    drainRestore()
                    g.setUpdateCheck(UpdateCheck.OK)
                    settleRestore()
                    val keywords =
                        g.api.calls
                            .filter { it.operationId == RESTORE_SEARCH }
                            .map(::keywordOfCall)
                    assertEquals("门放行后只有用户的新词，旧查询不得复活", listOf<String?>(WORD), keywords)
                    assertEquals("页面查询仍是用户的新词", WORD, g.page.keyword)
                    assertEquals("用户的新词结果照常展示", SearchPhase.EMPTY, g.page.phase)
                    assertNotNull("用户的新词结果照常消费", g.page.shownSeq)
                    assertEquals("输入框是用户的新词", WORD, g.model.input.value)
                }
            }
        }

    @Test
    fun gateTimeoutStillDropsRecognitionQuery() =
        runTest {
            fallbackShown { f ->
                val input = f.model.input.value
                restartedPage(f, RestoreMode.PROCESS, RestoreGate.GATED) { g, base ->
                    g.onSearchPage()
                    settleRestore()
                    assertQueryDropped(g, base, input, "恢复门超时放行后")
                }
            }
        }

    @Test
    fun leavingWhileGateClosedSendsNothing() =
        runTest {
            fallbackShown { f ->
                restartedPage(f, RestoreMode.PROCESS, RestoreGate.GATED) { g, base ->
                    g.onSearchPage()
                    drainRestore()
                    g.leaveSearch()
                    g.setUpdateCheck(UpdateCheck.OK)
                    settleRestore()
                    assertNoNewCalls(g, base, "恢复门等待中离页")
                }
            }
        }

    // ---------------------------------------------------------------- 保存时的页面状态

    /** r3 S1 的无平台路径叠加回收：识别失败时没有可搜平台，关键词留在页面上；重开后平台恢复，也不得按旧关键词搜。 */
    @Test
    fun noPlatformAtSaveDoesNotReviveQueryWhenPlatformIsBack() =
        runTest {
            SearchSubmitFixture(backgroundScope, searchEnabled = false).use { f ->
                f.onSearchPage()
                f.submit(LINK)
                drainRestore()
                f.api.parseReply.complete(SubmitReplies.failure(SERVER_ERROR))
                drainRestore()
                assertEquals("前提：无可搜索平台，关键词留在页面上", LINK to SearchPhase.UNAVAILABLE, f.page.keyword to f.page.phase)
                val input = f.model.input.value
                restartedPage(f, RestoreMode.PROCESS, RestoreGate.OPEN) { g, base ->
                    g.onSearchPage()
                    settleRestore()
                    assertQueryDropped(g, base, input, "无平台时保存、重开后平台恢复")
                }
            }
        }

    @Test
    fun recognitionInFlightAtDeathSendsNothingAndKeepsInput() =
        runTest {
            SearchSubmitFixture(backgroundScope).use { f ->
                f.onSearchPage()
                f.submit(LINK)
                drainRestore()
                restartedPage(f, RestoreMode.PROCESS, RestoreGate.OPEN) { g, base ->
                    g.onSearchPage()
                    settleRestore()
                    assertQueryDropped(g, base, LINK, "识别在途时回收")
                }
            }
        }

    @Test fun recognizedSingleCardIsNotReplayed() = recognizedNotReplayed(LINK, SubmitReplies.single(), LINK)

    @Test
    fun recognizedCardListIsNotReplayed() =
        recognizedNotReplayed(LINK, SubmitReplies.cards(SubmitReplies.card(), SubmitReplies.card(SubmitReplies.SECOND_KEY)), LINK)

    @Test fun recognizedPrefillIsNotReplayed() = recognizedNotReplayed(TITLED_LINK, SubmitReplies.failure(UNSUPPORTED), TITLE)

    private fun recognizedNotReplayed(
        text: String,
        reply: ApiResult,
        input: String,
    ) = runTest {
        SearchSubmitFixture(backgroundScope).use { f ->
            f.onSearchPage()
            f.submit(text)
            drainRestore()
            f.api.parseReply.complete(reply)
            drainRestore()
            restartedPage(f, RestoreMode.PROCESS, RestoreGate.OPEN) { g, base ->
                g.onSearchPage()
                settleRestore()
                assertQueryDropped(g, base, input, "识别已呈现后回收")
            }
        }
    }

    // ---------------------------------------------------------------- 恢复之后的新查询

    @Test
    fun plainSearchAfterDropRestoresNormallyNextTime() =
        runTest {
            fallbackShown { f ->
                restartedPage(f, RestoreMode.PROCESS, RestoreGate.OPEN) { g, base ->
                    g.onSearchPage()
                    settleRestore()
                    assertNoNewCalls(g, base, "第一次恢复")
                    g.model.onInput(WORD)
                    g.model.onSubmit()
                    settleRestore()
                    assertEquals("前提：普通词已展示", SearchPhase.EMPTY, g.page.phase)
                    restartedPage(g, RestoreMode.PROCESS, RestoreGate.OPEN) { h, _ ->
                        h.onSearchPage()
                        settleRestore()
                        assertEquals(
                            "输入态之后搜的普通词，来源是普通词：照常恢复",
                            listOf<String?>(WORD),
                            h.api.written
                                .filter { it.operationId == RESTORE_SEARCH }
                                .map(::keywordOfCall),
                        )
                        assertNotNull("普通词结果照常消费", h.page.shownSeq)
                    }
                }
            }
        }

    @Test
    fun resubmittedFallbackIsDroppedAgainOnNextRestore() =
        runTest {
            fallbackShown { f ->
                restartedPage(f, RestoreMode.PROCESS, RestoreGate.OPEN) { g, base ->
                    g.onSearchPage()
                    settleRestore()
                    assertNoNewCalls(g, base, "第一次恢复")
                    g.model.onSubmit()
                    drainRestore()
                    g.api.parseReply.complete(SubmitReplies.failure(SERVER_ERROR))
                    settleRestore()
                    assertEquals("前提：重新提交后又回落", LINK, g.page.keyword)
                    val input = g.model.input.value
                    restartedPage(g, RestoreMode.PROCESS, RestoreGate.OPEN) { h, hBase ->
                        h.onSearchPage()
                        settleRestore()
                        assertQueryDropped(h, hBase, input, "第二次回落后回收")
                    }
                }
            }
        }

    @Test
    fun routePlainWordRestoresNormally() =
        runTest {
            SearchSubmitFixture(backgroundScope, query = WORD).use { f ->
                settleRestore()
                assertEquals("前提：路由 q 普通词已展示", WORD, f.page.keyword)
                restartedPage(f, RestoreMode.PROCESS, RestoreGate.OPEN, query = WORD) { g, _ ->
                    g.onSearchPage()
                    settleRestore()
                    assertEquals("路由 q 普通词照常恢复一次", listOf<String?>(WORD), g.api.written.map(::keywordOfCall))
                    assertTrue("结果照常消费", g.page.shownSeq != null)
                }
            }
        }

    /** 提交 [LINK] 识别失败、回落结果已展示（还有下一页）。 */
    private fun TestScope.fallbackShown(body: (SearchSubmitFixture) -> Unit) {
        SearchSubmitFixture(backgroundScope).use { f ->
            f.api.hasMore(0)
            f.onSearchPage()
            f.submit(LINK)
            drainRestore()
            f.api.parseReply.complete(SubmitReplies.failure(SERVER_ERROR))
            drainRestore()
            assertEquals("前提：回落结果已展示", SearchPhase.EMPTY, f.page.phase)
            assertEquals("前提：回落结果已展示", LINK, f.page.keyword)
            body(f)
        }
    }
}
