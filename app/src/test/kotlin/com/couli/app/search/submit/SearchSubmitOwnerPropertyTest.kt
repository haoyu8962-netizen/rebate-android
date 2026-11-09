@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.couli.app.search.submit

import android.app.Application
import android.net.Uri
import android.os.Looper
import com.couli.app.search.submit.SubmitReplies.LINK
import com.couli.app.search.submit.SubmitReplies.SECOND_KEY
import com.couli.app.search.submit.SubmitReplies.SECOND_LINK
import com.couli.app.search.submit.SubmitReplies.SERVER_ERROR
import com.couli.app.search.submit.SubmitReplies.TITLED_LINK
import com.couli.app.search.submit.SubmitReplies.WORD
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * FU-AND-SEARCH-LINK-OWNER 测试 v1（D10-768）：性质测试，穷举「事件 × 时点（× 识别结局）」，每个组合只断言两条不变式——
 * 1. 作废之后，不再写出任何属于该提交的请求（识别、回落、配置驱动的自动续搜）；
 * 2. 作废之后，不再消费任何属于该提交的结果（导航、出卡 / 提示、预填、写历史、结果页）。
 *
 * 「该提交」= 搜索页提交的那条链接（[LINK] / [TITLED_LINK]）：它的第 0 个 parse，以及 q 不是新提交（[WORD]、[SECOND_LINK]）的全部
 * searchProducts（回落与续搜都按原文搜）。「写出」以夹具的写出前守卫为准（[SubmitApi.written]，与 ApiClient 同口径）。
 *
 * 每个组合的步骤：
 * 1. 预先把第 0 个 parse 的写出、前 [HOLDS] 个 searchProducts 的写出与响应都停住；新提交的词（[WORD]、[SECOND_LINK]）的响应永远停住，
 *    新提交的识别（第 1 个 parse）永远不回——它们是合法的新提交，不能被当成旧提交的消费；
 * 2. 推进到时点（[Point]），途中每一步都排空主线程（协程 + 主 Looper，不推进虚拟时间），所以作废前已写出的请求的历史已记完；
 * 3. 记下作废点（[Mark]），触发事件（[Event]）；
 * 4. 放行全部停住的请求，排空 → 断言两条不变式；
 * 5. 「配置复活探针」：配置换平台 → 全关 → 恢复，每步排空 → 再断言一次（作废后的配置变化不得按旧提交自动续搜，评审 r3 S1）。
 *
 * 事件 [Event.CONFIG_SWITCH]、[Event.CONFIG_RESTORE] 本身不作废提交，它们驱动自动续搜：
 * - 配置换平台：在时点上先换平台（只 idle 主 Looper，续搜请求已排进主线程），然后切号作废——「回落 / 续搜在途 → 配置换平台 → 切号」；
 * - 配置恢复平台：先全关（无平台），切号作废，再恢复平台——r3 S1 原路径「无平台 → 票据失效 → 配置恢复平台 → 自动续搜」。
 * 另加 [Event.SENSITIVE]（敏感浮层，剪贴板既有作废出口 onSensitiveOverlay），使 6 个剪贴板出口都有行为覆盖。
 *
 * 失败信息都以「[事件×时点×结局]」开头。参数用字符串越过 Robolectric 类加载器边界。
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class SearchSubmitOwnerPropertyTest(
    eventName: String,
    pointName: String,
    outcomeName: String,
) {
    private val event = Event.valueOf(eventName)
    private val point = Point.valueOf(pointName)
    private val outcome = Outcome.valueOf(outcomeName)
    private val combo = "[${event.label}×${point.label}×${outcome.label}]"

    @Before fun useTestMain() = Dispatchers.setMain(StandardTestDispatcher())

    @After fun resetMain() = Dispatchers.resetMain()

    @Test
    fun invalidatedSubmitNeitherWritesNorConsumes() =
        runTest {
            SearchSubmitFixture(backgroundScope).use { f ->
                val run = Run(f, this)
                run.reachPoint()
                val mark = run.fire()
                run.releaseAll()
                run.settle()
                assertInvariants(f, mark, "放行之后")
                run.probeConfig()
                assertInvariants(f, mark, "配置复活探针之后")
                assertNull("$combo 不变式 2：作废后不得写商品种子（单卡直达的消费）", SubmitReplies.claimSeed(f, SubmitReplies.FIRST_KEY))
                assertNull("$combo 不变式 2：作废后不得写商品种子（第二张卡）", SubmitReplies.claimSeed(f, SECOND_KEY))
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
        }

        private val text = if (outcome == Outcome.UNSUPPORTED) TITLED_LINK else LINK

        private val parseResult: ApiResult get() = if (point.parse) outcome.result() else SubmitReplies.failure(SERVER_ERROR)

        @Suppress("ReturnCount") // 每个时点一个停靠出口
        fun reachPoint() {
            f.onSearchPage()
            f.submit(text)
            drain()
            assertEquals("$combo 前提：提交候选，先只发识别", listOf(PARSE), SubmitReplies.operations(f))
            assertTrue("$combo 前提：识别还没写出", f.api.written.isEmpty())
            if (point == Point.PARSE_BEFORE_WRITE) return
            parseWrite.complete(Unit)
            drain()
            assertEquals("$combo 前提：识别已写出", listOf(PARSE), f.api.writtenOperations)
            if (point == Point.PARSE_BEFORE_REPLY) return
            // 识别响应已到（IO 侧完成），主线程还没处理：PARSE_BEFORE_CONSUME 停在这里，不排空
            f.api.reply(0).complete(parseResult)
            if (point == Point.PARSE_BEFORE_CONSUME) return
            drain()
            assertEquals("$combo 前提：识别失败后回落关键词请求已交到网络层", listOf(PARSE, SEARCH), SubmitReplies.operations(f))
            assertEquals("$combo 前提：回落按原文搜", listOf(PARSE), f.api.writtenOperations)
            if (point == Point.FALLBACK_BEFORE_WRITE) return
            searchWrites[0].complete(Unit)
            drain()
            assertEquals("$combo 前提：回落已写出、响应停住", listOf(PARSE, SEARCH), f.api.writtenOperations)
            if (point == Point.FALLBACK_BEFORE_REPLY) return
            // 回落在途时配置换平台（taobao 关、jd 开）：自动续搜在 jd 上按原文再搜（同一次提交的续搜）
            pushStatus(OFF to ON)
            drain()
            val continued = f.api.calls.filter { it.operationId == SEARCH }
            assertTrue("$combo 前提：配置换平台后自动续搜已交到网络层（实际 ${describe(continued)}）", continued.size >= 2)
            assertEquals("$combo 前提：续搜在新平台上", JD, platformOf(continued.last()))
            if (point == Point.CONTINUE_BEFORE_WRITE) return
            searchWrites[continued.size - 1].complete(Unit)
            drain()
            val last = f.api.written.last()
            assertEquals("$combo 前提：续搜已写出、响应停住", SEARCH, last.operationId)
            assertEquals("$combo 前提：续搜已写出、响应停住", JD, platformOf(last))
        }

        /** 在时点上触发事件；返回作废点（配置事件先改配置，再在切号之前记作废点）。 */
        fun fire(): Mark =
            when (event) {
                Event.CONFIG_SWITCH -> {
                    pushStatus(if (status.first == ON) OFF to ON else ON to OFF)
                    idleLooper()
                    mark().also { f.switchAccount() }
                }

                Event.CONFIG_RESTORE -> {
                    val before = status
                    pushStatus(OFF to OFF)
                    idleLooper()
                    mark().also {
                        f.switchAccount()
                        pushStatus(before)
                        idleLooper()
                    }
                }

                else -> {
                    mark().also { event.act(f) }
                }
            }.copy(input = f.model.input.value)

        private fun mark(): Mark =
            Mark(
                written = f.api.written.size,
                navigations = f.navigations.size,
                history = oldHistory(f),
                shownSeq = f.page.shownSeq,
                result = f.clipboard.result.value,
                input = f.model.input.value,
            )

        fun releaseAll() {
            parseWrite.complete(Unit)
            f.api.reply(0).complete(parseResult)
            searchWrites.forEach { it.complete(Unit) }
            searchReplies.forEach { it.complete(Unit) }
        }

        /** 配置换平台 → 全关 → 恢复：每步排空。作废后的配置变化不得按旧提交续搜。 */
        fun probeConfig() {
            val before = status
            pushStatus(if (before.first == ON) OFF to ON else ON to OFF)
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

        /** 排空主线程：协程（不推进虚拟时间）与主 Looper（AppSearch 的 main.post）。 */
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
                .filter { ofOldSubmit(f, it) }
        assertTrue("$combo $stage 不变式 1：作废后不得再写出该提交的请求（实际写出 ${describe(late)}）", late.isEmpty())
        val consumed = mutableListOf<String>()
        if (f.navigations.size != mark.navigations) consumed += "导航 ${f.navigations.drop(mark.navigations).map { it.first }}"
        if (f.clipboard.result.value != mark.result) consumed += "出卡 / 提示 ${f.clipboard.result.value}"
        if (f.model.input.value != mark.input) consumed += "预填 / 改输入框「${f.model.input.value}」（作废时「${mark.input}」）"
        if (oldHistory(f) != mark.history) consumed += "写历史 ${oldHistory(f)}（作废时 ${mark.history}）"
        val page = f.page
        if (page.shownSeq != mark.shownSeq) consumed += "结果页 shownSeq=${page.shownSeq}（作废时 ${mark.shownSeq}）"
        if (page.phase == SearchPhase.CONTENT || page.phase == SearchPhase.EMPTY) consumed += "结果页 phase=${page.phase}"
        assertTrue("$combo $stage 不变式 2：作废后不得再消费该提交的结果：$consumed", consumed.isEmpty())
    }

    /** 作废点：此刻已写出的请求数、导航数、旧提交的历史、结果页序号、剪贴板结果、事件之后的输入框。 */
    private data class Mark(
        val written: Int,
        val navigations: Int,
        val history: Pair<List<String>, List<String>>,
        val shownSeq: Long?,
        val result: Any?,
        val input: String,
    )

    /** 事件（[act] 只用于直接作废的事件；配置事件见 [Run.fire]）。 */
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
        CONFIG_SWITCH("配置换平台后切号", {}),
        CONFIG_RESTORE("无平台→切号→配置恢复平台", {}),
    }

    /** 时点。[parse] 为 true 时识别结局按 [Outcome] 取，否则识别失败（回落与续搜）。 */
    internal enum class Point(
        val label: String,
        val parse: Boolean,
    ) {
        PARSE_BEFORE_WRITE("parse写出前", true),
        PARSE_BEFORE_REPLY("parse响应前", true),
        PARSE_BEFORE_CONSUME("parse主线程消费前", true),
        FALLBACK_BEFORE_WRITE("回落写出前", false),
        FALLBACK_BEFORE_REPLY("回落响应前", false),
        CONTINUE_BEFORE_WRITE("续搜写出前", false),
        CONTINUE_BEFORE_REPLY("续搜响应前", false),
    }

    /** 识别结局（只在 parse 时点上展开）。 */
    internal enum class Outcome(
        val label: String,
        val result: () -> ApiResult,
    ) {
        SINGLE("单卡", { SubmitReplies.single() }),
        MULTI("多卡", { SubmitReplies.cards(SubmitReplies.card(), SubmitReplies.card(SECOND_KEY, "jd", SECOND_LINK)) }),
        UNSUPPORTED("30131预填", { SubmitReplies.failure(SubmitReplies.UNSUPPORTED) }),
        FAILURE("识别失败", { SubmitReplies.failure(SERVER_ERROR) }),
    }

    companion object {
        private const val PARSE = "parseInput"
        private const val SEARCH = "searchProducts"
        private const val JD = "jd"
        private const val ON = "on"
        private const val OFF = "off"

        /** 预先停住的 searchProducts 个数（回落 + 续搜 + 新关键词，足够覆盖放行前的全部请求）。 */
        private const val HOLDS = 8
        private const val DRAIN_ROUNDS = 3

        /** 新提交（合法）的关键词：它们的请求不算旧提交的，响应永远停住。 */
        private val NEW_KEYWORDS = listOf(WORD, SECOND_LINK)

        @JvmStatic
        @Parameters(name = "{index}: {0}×{1}×{2}")
        fun cases(): List<Array<String>> =
            Event.entries
                .flatMap { event ->
                    Point.entries.flatMap { point ->
                        val outcomes = if (point.parse) Outcome.entries else listOf(Outcome.FAILURE)
                        outcomes.map { arrayOf(event.name, point.name, it.name) }
                    }
                }.also { check(it.size == Event.entries.size * (3 * Outcome.entries.size + 4)) }

        private fun queryOf(call: ApiCall): Uri = Uri.parse("https://fixture.invalid/?${call.query}")

        private fun platformOf(call: ApiCall): String? = queryOf(call).getQueryParameter("platform")

        /** 旧提交的请求：第 0 个 parse，或 q 不是新提交关键词的 searchProducts。 */
        private fun ofOldSubmit(
            f: SearchSubmitFixture,
            call: ApiCall,
        ): Boolean =
            when (call.operationId) {
                PARSE -> call === f.api.parseCalls.first()
                SEARCH -> queryOf(call).getQueryParameter("q") !in NEW_KEYWORDS
                else -> true
            }

        /** 两份历史（App 共用一份、本页 ViewModel 一份）里不属于新提交的词。 */
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
