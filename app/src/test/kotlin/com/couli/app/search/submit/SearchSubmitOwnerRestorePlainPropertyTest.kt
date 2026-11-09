@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.couli.app.search.submit

import android.app.Application
import com.couli.app.search.submit.SubmitReplies.LINK
import com.couli.app.search.submit.SubmitReplies.SERVER_ERROR
import com.couli.app.search.submit.SubmitReplies.WORD
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
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.Config

/**
 * FU-AND-SEARCH-LINK-OWNER 测试 v3（评审 r2 建议）：普通关键词恢复的回归。修 S0-1 时不得把普通关键词一起丢掉：保存时页面的查询是用户直接搜的词，
 * 恢复后照常重搜一次、照常展示，轮次不变、不记历史，之后翻页和配置续搜照常。「来源」必须跟着最新的查询走：回落页之后用户又搜了普通词，按普通词恢复；
 * 普通词页上再提交链接、识别在途时回收，页面的查询仍是普通词，也按普通词恢复，且不补发识别。
 * 失败信息以「[起点×事件×重建×恢复门]」开头。
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class SearchSubmitOwnerRestorePlainPropertyTest(
    startName: String,
    eventName: String,
    modeName: String,
    gateName: String,
) {
    private val start = Start.valueOf(startName)
    private val event = BeforeSave.valueOf(eventName)
    private val mode = RestoreMode.valueOf(modeName)
    private val gate = RestoreGate.valueOf(gateName)
    private val combo = "[${start.label}×${event.label}×${mode.label}×${gate.label}]"

    @Before fun useTestMain() = Dispatchers.setMain(StandardTestDispatcher())

    @After fun resetMain() = Dispatchers.resetMain()

    @Test
    fun plainKeywordIsSearchedAgainAfterRestore() =
        runTest {
            SearchSubmitFixture(backgroundScope).use { f ->
                reachStart(f)
                event.act(f)
                drainRestore()
                val input = f.model.input.value
                val round = f.page.round
                restartedPage(f, mode, gate, basic = event == BeforeSave.PRIVACY) { g, base ->
                    val first = g.api.calls.count { it.operationId == RESTORE_SEARCH }
                    g.api.hasMore(first)
                    openRestoreGate(g, gate, base, combo)
                    assertRestored(g, base, input, round)
                    assertTrue("$combo 恢复后翻页照常", g.model.controller.loadMore())
                    settleRestore()
                    g.pushSearchStatus(taobao = "off", jd = "on")
                    settleRestore()
                    val searches = g.api.written.drop(base.written)
                    assertEquals("$combo 恢复、翻页、配置续搜都按普通词", listOf(WORD, WORD, WORD), searches.map(::keywordOfCall))
                    assertEquals("$combo 配置续搜到新平台", "jd", platformOfCall(searches.last()))
                }
            }
        }

    private fun TestScope.reachStart(f: SearchSubmitFixture) {
        f.onSearchPage()
        if (start == Start.AFTER_FALLBACK) {
            f.submit(LINK)
            drainRestore()
            f.api.parseReply.complete(SubmitReplies.failure(SERVER_ERROR))
            drainRestore()
            assertEquals("$combo 前提：回落已展示", SearchPhase.EMPTY, f.page.phase)
        }
        f.submit(WORD)
        drainRestore()
        assertEquals("$combo 前提：普通词已展示", SearchPhase.EMPTY, f.page.phase)
        assertEquals("$combo 前提：普通词已展示", WORD, f.page.keyword)
        if (start == Start.THEN_RECOGNITION) {
            f.submit(LINK)
            drainRestore()
            assertEquals("$combo 前提：识别在途，页面查询仍是普通词", WORD, f.page.keyword)
        }
    }

    private fun assertRestored(
        g: SearchSubmitFixture,
        base: RestoreBaseline,
        input: String,
        round: Long,
    ) {
        val calls = g.api.calls.drop(base.calls)
        assertEquals("$combo 普通词照常重搜一次，不补发识别", listOf(RESTORE_SEARCH), calls.map { it.operationId })
        assertEquals("$combo 按保存的普通词搜", WORD, keywordOfCall(calls.single()))
        val page = g.page
        assertEquals("$combo 结果照常展示", SearchPhase.EMPTY, page.phase)
        assertNotNull("$combo 结果照常消费", page.shownSeq)
        assertEquals("$combo 页面查询仍是普通词", WORD, page.keyword)
        assertEquals("$combo 输入框保留", input, g.model.input.value)
        assertEquals("$combo 恢复不算新一轮", round, page.round)
        assertEquals("$combo 恢复重搜不记历史", base.history, g.pageConfig.history.items)
    }

    internal enum class Start(
        val label: String,
    ) {
        SHOWN("普通词已展示"),
        AFTER_FALLBACK("回落后又搜普通词"),
        THEN_RECOGNITION("普通词页再提交识别在途"),
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{index}: {0}×{1}×{2}×{3}")
        fun cases(): List<Array<String>> =
            Start.entries
                .flatMap { start ->
                    BeforeSave.entries.filterNot { it == BeforeSave.RESUBMIT }.flatMap { event ->
                        RestoreMode.entries.flatMap { mode ->
                            RestoreGate.entries.map { arrayOf(start.name, event.name, mode.name, it.name) }
                        }
                    }
                }
    }
}
