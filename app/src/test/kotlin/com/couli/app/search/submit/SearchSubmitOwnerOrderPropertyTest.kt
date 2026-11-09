@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.couli.app.search.submit

import android.app.Application
import android.net.Uri
import android.os.Looper
import com.couli.app.search.submit.SubmitReplies.LINK
import com.couli.app.search.submit.SubmitReplies.SECOND_LINK
import com.couli.app.search.submit.SubmitReplies.SERVER_ERROR
import com.couli.app.search.submit.SubmitReplies.WORD
import com.couli.core.network.ApiCall
import com.couli.feature.search.SearchPhase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * FU-AND-SEARCH-LINK-OWNER 测试 v2（Codex 对抗评审 r1 S0-1、S0-2）：性质测试的「顺序」与「翻页」维度。两条不变式与 v1 相同，不变式 1 加强为：
 * 作废之后，**不得以任何提交的归属**写出属于旧提交的查询——查询（q）与归属必须同属一次提交；新提交的票据合法，也不能替旧提交的关键词发请求。
 *
 * 维度：
 * - 起点 [Point]：回落写出前 / 响应前、**回落结果已展示**（S0-1：没有迟到回落替它清关键词）、续搜写出前 / 响应前、**翻页写出前 / 响应前 /
 *   主线程消费前**（S0-2：回落第一页已展示、还有下一页，翻页 loadMore 即 AutoLoadMore 的入口）、**翻页未启动**（作废之后才自动翻页）；
 * - 事件 [Event]：v1 的直接作废事件，另加「切号后再提交」「切环境后再提交」（S0-1：旧身份留下的 A 查询不得借新身份 / 新环境下 B 的票据发出）；
 * - 顺序 [Order]：**配置先到**（作废之后、旧请求放行与排空之前，配置换平台 → configChanged → fallBack → run()，S0-1）或放行先到（v1 顺序）。
 *
 * 步骤：推进到起点（途中排空主线程）→ 记作废点 → 事件 →（翻页未启动：此刻调一次 loadMore）→ 按顺序：配置先到则先换平台、只 idle 主 Looper，
 * 再放行全部停住的请求；放行先到则先放行 → 排空 → 断言 → 配置复活探针（换平台 → 全关 → 恢复）→ 再断言。
 *
 * 旧提交的查询 = q 不是新提交关键词（[WORD]、[SECOND_LINK]）的 searchProducts（回落、续搜、翻页都按原文 [LINK] 搜）。新提交的响应与识别永远停住，
 * 它们不会被误当成旧提交的消费。失败信息以「[事件×起点×顺序]」开头。
 * 口径（D10-942）：回落页作废后（含覆盖后返回）配置换平台回到输入态、不续搜；这里只断言「不写出、不消费」，不要求续搜。
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class SearchSubmitOwnerOrderPropertyTest(
    eventName: String,
    pointName: String,
    orderName: String,
) {
    private val event = Event.valueOf(eventName)
    private val point = Point.valueOf(pointName)
    private val order = Order.valueOf(orderName)
    private val combo = "[${event.label}×${point.label}×${order.label}]"

    @Before fun useTestMain() = Dispatchers.setMain(StandardTestDispatcher())

    @After fun resetMain() = Dispatchers.resetMain()

    @Test
    fun invalidatedSubmitQueryNeverWrittenNorConsumed() =
        runTest {
            SearchSubmitFixture(backgroundScope).use { f ->
                val run = Run(f, this)
                run.reachPoint()
                val mark = run.fire()
                if (point == Point.PAGE_NOT_STARTED) f.model.controller.loadMore()
                if (order == Order.CONFIG_FIRST) {
                    run.switchPlatform()
                    idleLooper()
                }
                run.releaseAll()
                run.settle()
                assertInvariants(f, mark, "放行之后")
                run.probeConfig()
                assertInvariants(f, mark, "配置复活探针之后")
            }
        }

    // ---------------------------------------------------------------- 一次组合的驱动

    private inner class Run(
        private val f: SearchSubmitFixture,
        private val test: TestScope,
    ) {
        private val parseWrite = f.api.holdWrite(PARSE)
        private val searchWrites: List<CompletableDeferred<Unit>> = (0 until HOLDS).map { f.api.holdWrite(SEARCH, it) }
        private val searchReplies: List<CompletableDeferred<Unit>> = (0 until HOLDS).map { f.api.holdReply(it) }

        /** 当前下发的 search_status（taobao, jd）；夹具包内配置为 taobao on、jd off。 */
        private var status = ON to OFF

        init {
            NEW_KEYWORDS.forEach(f.api::holdKeyword)
            // 回落第一页（第 0 个 searchProducts）还有下一页：翻页起点要用
            f.api.hasMore(0)
        }

        @Suppress("ReturnCount") // 每个起点一个停靠出口
        fun reachPoint() {
            f.onSearchPage()
            f.submit(LINK)
            drain()
            parseWrite.complete(Unit)
            f.api.reply(0).complete(SubmitReplies.failure(SERVER_ERROR))
            drain()
            assertEquals("$combo 前提：识别失败后回落关键词请求已交到网络层", listOf(PARSE, SEARCH), SubmitReplies.operations(f))
            if (point == Point.FALLBACK_BEFORE_WRITE) return
            searchWrites[0].complete(Unit)
            drain()
            assertEquals("$combo 前提：回落已写出", listOf(PARSE, SEARCH), f.api.writtenOperations)
            if (point == Point.FALLBACK_BEFORE_REPLY) return
            if (point.continuation) {
                reachContinuation()
                return
            }
            searchReplies[0].complete(Unit)
            drain()
            assertTrue("$combo 前提：回落结果已展示（shownSeq=${f.page.shownSeq}）", f.page.shownSeq != null)
            assertEquals("$combo 前提：回落结果已展示", SearchPhase.EMPTY, f.page.phase)
            if (point == Point.FALLBACK_SHOWN || point == Point.PAGE_NOT_STARTED) return
            assertTrue("$combo 前提：回落第一页之后可以翻页", f.model.controller.loadMore())
            drain()
            assertEquals("$combo 前提：翻页请求已交到网络层", 2, searchCalls().size)
            if (point == Point.PAGE_BEFORE_WRITE) return
            searchWrites[1].complete(Unit)
            drain()
            assertEquals("$combo 前提：翻页已写出、响应停住", listOf(PARSE, SEARCH, SEARCH), f.api.writtenOperations)
            if (point == Point.PAGE_BEFORE_REPLY) return
            // 翻页响应已到（网络侧完成），主线程还没处理：不排空
            searchReplies[1].complete(Unit)
        }

        /** 回落在途时配置换平台（taobao 关、jd 开）：自动续搜在 jd 上按原文再搜。 */
        private fun reachContinuation() {
            pushStatus(OFF to ON)
            drain()
            val continued = searchCalls()
            assertTrue("$combo 前提：配置换平台后自动续搜已交到网络层（实际 ${describe(continued)}）", continued.size >= 2)
            assertEquals("$combo 前提：续搜在新平台上", JD, platformOf(continued.last()))
            if (point == Point.CONTINUE_BEFORE_WRITE) return
            searchWrites[continued.size - 1].complete(Unit)
            drain()
            assertEquals("$combo 前提：续搜已写出、响应停住", JD, platformOf(f.api.written.last()))
        }

        fun fire(): Mark {
            val mark =
                Mark(
                    written = f.api.written.size,
                    navigations = f.navigations.size,
                    history = oldHistory(f),
                    shownSeq = f.page.shownSeq,
                    phase = f.page.phase,
                    loadMoreError = f.page.loadMoreError,
                    result = f.clipboard.result.value,
                    input = "",
                )
            event.act(f)
            return mark.copy(input = f.model.input.value)
        }

        fun switchPlatform() = pushStatus(if (status.first == ON) OFF to ON else ON to OFF)

        fun releaseAll() {
            searchWrites.forEach { it.complete(Unit) }
            searchReplies.forEach { it.complete(Unit) }
        }

        fun probeConfig() {
            val before = status
            switchPlatform()
            settle()
            pushStatus(OFF to OFF)
            settle()
            pushStatus(before)
            settle()
        }

        private fun pushStatus(next: Pair<String, String>) {
            status = next
            f.pushSearchStatus(next.first, next.second)
        }

        private fun searchCalls(): List<ApiCall> = f.api.calls.filter { it.operationId == SEARCH }

        private fun drain() {
            repeat(DRAIN_ROUNDS) {
                test.runCurrent()
                idleLooper()
            }
        }

        fun settle() {
            repeat(DRAIN_ROUNDS) {
                test.advanceUntilIdle()
                idleLooper()
            }
        }
    }

    // ---------------------------------------------------------------- 不变式

    private fun assertInvariants(
        f: SearchSubmitFixture,
        mark: Mark,
        stage: String,
    ) {
        val late =
            f.api.written
                .drop(mark.written)
                .filter(::ofOldSubmit)
        assertTrue(
            "$combo $stage 不变式 1：作废后不得以任何提交的归属写出旧提交的查询（实际写出 ${describe(late)}）",
            late.isEmpty(),
        )
        val page = f.page
        val consumed = mutableListOf<String>()
        if (f.navigations.size != mark.navigations) consumed += "导航 ${f.navigations.drop(mark.navigations).map { it.first }}"
        if (f.clipboard.result.value != mark.result) consumed += "出卡 / 提示 ${f.clipboard.result.value}"
        if (f.model.input.value != mark.input) consumed += "改输入框「${f.model.input.value}」（作废时「${mark.input}」）"
        if (oldHistory(f) != mark.history) consumed += "写历史 ${oldHistory(f)}（作废时 ${mark.history}）"
        if (page.shownSeq != mark.shownSeq) consumed += "结果页 shownSeq=${page.shownSeq}（作废时 ${mark.shownSeq}）"
        if (page.loadMoreError != mark.loadMoreError) consumed += "翻页错误态 ${page.loadMoreError}"
        val resultPhase = page.phase == SearchPhase.CONTENT || page.phase == SearchPhase.EMPTY
        if (resultPhase && page.phase != mark.phase) consumed += "结果页 phase=${page.phase}（作废时 ${mark.phase}）"
        assertTrue("$combo $stage 不变式 2：作废后不得再消费旧提交的结果：$consumed", consumed.isEmpty())
    }

    private data class Mark(
        val written: Int,
        val navigations: Int,
        val history: Pair<List<String>, List<String>>,
        val shownSeq: Long?,
        val phase: String,
        val loadMoreError: Any?,
        val result: Any?,
        val input: String,
    )

    internal enum class Event(
        val label: String,
        val act: (SearchSubmitFixture) -> Unit,
    ) {
        ACCOUNT("切号", SearchSubmitFixture::switchAccount),
        ENVIRONMENT("切环境", SearchSubmitFixture::switchEnvironment),
        PRIVACY("撤回隐私", SearchSubmitFixture::withdrawPrivacy),
        COVER("页面覆盖", SearchSubmitFixture::coverWithDetail),
        BACK("覆盖后返回", { f ->
            f.coverWithDetail()
            f.onSearchPage()
        }),
        LEAVE("离页", SearchSubmitFixture::leaveSearch),
        RESUBMIT("再提交", { f -> f.submit(SECOND_LINK) }),
        NEW_KEYWORD("新关键词", { f -> f.submit(WORD) }),
        SENSITIVE("敏感浮层", { f -> f.clipboard.onSensitiveOverlay() }),
        ACCOUNT_THEN_RESUBMIT("切号后再提交", { f ->
            f.switchAccount()
            f.submit(SECOND_LINK)
        }),
        ENVIRONMENT_THEN_RESUBMIT("切环境后再提交", { f ->
            f.switchEnvironment()
            f.submit(SECOND_LINK)
        }),
    }

    internal enum class Point(
        val label: String,
        val continuation: Boolean = false,
    ) {
        FALLBACK_BEFORE_WRITE("回落写出前"),
        FALLBACK_BEFORE_REPLY("回落响应前"),
        FALLBACK_SHOWN("回落结果已展示"),
        CONTINUE_BEFORE_WRITE("续搜写出前", continuation = true),
        CONTINUE_BEFORE_REPLY("续搜响应前", continuation = true),
        PAGE_BEFORE_WRITE("翻页写出前"),
        PAGE_BEFORE_REPLY("翻页响应前"),
        PAGE_BEFORE_CONSUME("翻页主线程消费前"),
        PAGE_NOT_STARTED("作废后才自动翻页"),
    }

    internal enum class Order(
        val label: String,
    ) {
        CONFIG_FIRST("配置先到"),
        RELEASE_FIRST("放行先到"),
    }

    companion object {
        private const val PARSE = "parseInput"
        private const val SEARCH = "searchProducts"
        private const val JD = "jd"
        private const val ON = "on"
        private const val OFF = "off"
        private const val HOLDS = 8
        private const val DRAIN_ROUNDS = 3
        private val NEW_KEYWORDS = listOf(WORD, SECOND_LINK)

        @JvmStatic
        @Parameters(name = "{index}: {0}×{1}×{2}")
        fun cases(): List<Array<String>> =
            Event.entries
                .flatMap { event ->
                    Point.entries.flatMap { point ->
                        Order.entries.map { arrayOf(event.name, point.name, it.name) }
                    }
                }.also { check(it.size == Event.entries.size * Point.entries.size * Order.entries.size) }

        private fun queryOf(call: ApiCall): Uri = Uri.parse("https://fixture.invalid/?${call.query}")

        private fun platformOf(call: ApiCall): String? = queryOf(call).getQueryParameter("platform")

        /** 旧提交的查询：q 不是新提交关键词的 searchProducts（不论挂在哪次提交的归属下）。parse 由 v1 覆盖，这里不再计。 */
        private fun ofOldSubmit(call: ApiCall): Boolean =
            call.operationId == SEARCH && queryOf(call).getQueryParameter("q") !in NEW_KEYWORDS

        private fun oldHistory(f: SearchSubmitFixture): Pair<List<String>, List<String>> {
            val (app, page) = SubmitReplies.history(f)
            return app.filterNot { it in NEW_KEYWORDS } to page.filterNot { it in NEW_KEYWORDS }
        }

        private fun describe(calls: List<ApiCall>): String =
            calls
                .map { call ->
                    val q = queryOf(call)
                    "${call.operationId}(q=${q.getQueryParameter("q")}, platform=${q.getQueryParameter("platform")})"
                }.toString()

        private fun idleLooper() = shadowOf(Looper.getMainLooper()).idle()
    }
}
