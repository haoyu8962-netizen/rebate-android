@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.couli.app.search.submit

import android.app.Application
import com.couli.app.search.submit.SubmitReplies.LINK
import com.couli.app.search.submit.SubmitReplies.SERVER_ERROR
import com.couli.feature.search.SearchPhase
import com.couli.generated.Sort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.Config

/**
 * FU-AND-SEARCH-LINK-OWNER 测试 v3（Codex 评审 r2 S0-1、S2-1）：保存恢复的性质测试。识别提交回落的查询写进了 SavedStateHandle，页面重建后新 Controller
 * 没有归属；归属丢失不等于普通关键词。穷举「起点 × 保存前事件 × 重建方式 × 恢复门」，每个组合断言：
 * 来源为识别提交的旧查询零请求、零消费，页面回输入态，保留输入文字，不自动补发新票据；配置切换、自动翻页、重试、换条件都不复活；再回收一次也不复活；
 * 用户自己重新提交照常工作（撤回隐私后不再识别，不查这一项）。
 *
 * 「未作废直接回收」[BeforeSave.NONE] 同样要求回输入态：恢复后已无法证明归属仍有效（评审 r2 建议）。
 * 失败信息以「[起点×事件×重建×恢复门]」开头。参数用字符串越过 Robolectric 类加载器边界。
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class SearchSubmitOwnerRestorePropertyTest(
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
    fun recognitionSourcedQueryIsNotRevivedByRestore() =
        runTest {
            val routeQuery = if (start == Start.ROUTE_QUERY) LINK else null
            SearchSubmitFixture(backgroundScope, query = routeQuery).use { f ->
                FirstLife(f, this).reachStart()
                event.act(f)
                drainRestore()
                val input = f.model.input.value
                val basic = event == BeforeSave.PRIVACY
                restartedPage(f, mode, gate, basic, routeQuery) { g, base ->
                    openRestoreGate(g, gate, base, combo)
                    assertQueryDropped(g, base, input, "$combo 放行恢复门后")
                    probeDroppedPage(g, base, input, combo)
                    assertSecondRestoreStaysDropped(g, input, basic)
                    if (!basic) assertUserCanResubmit(g, base, combo)
                }
            }
        }

    /** 恢复成输入态之后再被回收一次：保存状态里不得留下能复活旧查询的东西。 */
    private fun TestScope.assertSecondRestoreStaysDropped(
        g: SearchSubmitFixture,
        input: String,
        basic: Boolean,
    ) {
        val again = g.saveState()
        SearchSubmitFixture(backgroundScope, basic = basic, savedState = again).use { h ->
            val base = RestoreBaseline.of(h)
            h.onSearchPage()
            settleRestore()
            assertQueryDropped(h, base, input, "$combo 第二次回收后")
        }
    }

    // ---------------------------------------------------------------- 第一生：把页面推到起点

    private inner class FirstLife(
        private val f: SearchSubmitFixture,
        private val test: TestScope,
    ) {
        private val writes: List<CompletableDeferred<Unit>> = (0 until HOLDS).map { f.api.holdWrite(RESTORE_SEARCH, it) }
        private val replies: List<CompletableDeferred<Unit>> = (0 until HOLDS).map { f.api.holdReply(it) }

        init {
            f.api.hasMore(0)
        }

        @Suppress("ReturnCount") // 每个起点一个停靠出口
        fun reachStart() {
            if (start != Start.ROUTE_QUERY) {
                f.onSearchPage()
                f.submit(LINK)
            }
            test.drainRestore()
            f.api.parseReply.complete(SubmitReplies.failure(SERVER_ERROR))
            test.drainRestore()
            assertEquals("$combo 前提：回落已受理，页面关键词是原文", LINK, f.page.keyword)
            if (start == Start.ACCEPTED) return
            release(0, write = true, reply = false)
            val written = f.api.written.last()
            assertEquals("$combo 前提：回落已写出", RESTORE_SEARCH, written.operationId)
            if (start == Start.WRITTEN) return
            if (start == Start.CONTINUED) {
                f.pushSearchStatus(taobao = "off", jd = "on")
                test.drainRestore()
                assertEquals("$combo 前提：自动续搜已交到网络层", "jd", platformOfCall(f.api.calls.last()))
                return
            }
            release(0, write = false, reply = true)
            assertTrue("$combo 前提：回落结果已展示", f.page.shownSeq != null && f.page.phase == SearchPhase.EMPTY)
            when (start) {
                Start.PAGED -> {
                    assertTrue("$combo 前提：可以翻页", f.model.controller.loadMore())
                    release(1, write = true, reply = true)
                }

                Start.RESORTED -> {
                    assertTrue("$combo 前提：可以换排序", f.model.controller.selectSort(Sort.SALES_DESC.raw))
                    release(1, write = true, reply = true)
                }

                else -> {
                    Unit
                }
            }
        }

        private fun release(
            index: Int,
            write: Boolean,
            reply: Boolean,
        ) {
            test.drainRestore()
            if (write) writes[index].complete(Unit)
            if (reply) replies[index].complete(Unit)
            test.drainRestore()
        }
    }

    internal enum class Start(
        val label: String,
    ) {
        ACCEPTED("回落已受理"),
        WRITTEN("回落已写出"),
        SHOWN("回落已展示"),
        CONTINUED("续搜在途"),
        PAGED("翻页已展示"),
        RESORTED("换排序后"),
        ROUTE_QUERY("路由q回落已展示"),
    }

    companion object {
        private const val HOLDS = 6

        @JvmStatic
        @Parameters(name = "{index}: {0}×{1}×{2}×{3}")
        fun cases(): List<Array<String>> =
            Start.entries
                .flatMap { start ->
                    BeforeSave.entries.flatMap { event ->
                        RestoreMode.entries.flatMap { mode ->
                            RestoreGate.entries.map { arrayOf(start.name, event.name, mode.name, it.name) }
                        }
                    }
                }.also { check(it.isNotEmpty()) }
    }
}
