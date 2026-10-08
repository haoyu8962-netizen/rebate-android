package com.couli.feature.search

import com.couli.core.designsystem.PriceTerms
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.ErrorEffect
import com.couli.core.network.ErrorEffectKind
import com.couli.core.network.PageApi
import com.couli.core.network.ReplayMode
import com.couli.generated.DefaultTexts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APP-06a 搜索执行器的集成测试（任务书 §2.5；本地假服务：[FakeApi] 按请求逐个结算，[FakeConfig] 模拟 ConfigStore 的三层取值与确认编号）。
 * 覆盖：搜索 / 换 Tab / 换排序 / 换筛选 / 翻页时迟到的旧响应被丢弃、离开页面后迟到响应不改界面、search_status 不是 on 的平台不出现，
 * 以及 APP-06a 共性第 2、4、6、7、8、9 条（search_disabled 首屏与翻页、重新开放、42901 禁用、trace6、翻页错误保留结果）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchControllerIntegrationTest {
    private val api = FakeApi()
    private val config = FakeConfig()

    private fun TestScope.controller(): SearchController = SearchController(api, config, backgroundScope) { testScheduler.currentTime }

    @Test
    fun tabsOnlyShowPlatformsWhoseSearchStatusIsOn() =
        runTest {
            config.layers = layers(current = """{"taobao":"on","jd":"off","pdd":"future_status"}""", lkg = """{"jd":"on","pdd":"on"}""")
            val c = controller()
            c.open(null, "jd")
            assertEquals(listOf("taobao"), c.state.value.tabs)
            assertEquals("想要的平台不可用时回落到第一个可用平台", "taobao", c.state.value.platform)
            assertFalse("off 平台不能选中", c.selectTab("jd"))
        }

    @Test
    fun noSearchablePlatformSendsNothing() =
        runTest {
            config.layers = layers(current = """{"taobao":"off","jd":"off","pdd":"off"}""")
            val c = controller()
            c.open("纸巾", null)
            runCurrent()
            assertEquals(SearchPhase.UNAVAILABLE, c.state.value.phase)
            assertTrue(api.calls.isEmpty())
        }

    @Test
    fun searchBuildsTheContractQueryAndShowsTheResult() =
        runTest {
            val c = controller()
            c.open(null, null)
            assertTrue(c.submit("  纯  牛奶 "))
            runCurrent()
            assertEquals(
                "q=%E7%BA%AF%20%E7%89%9B%E5%A5%B6&platform=taobao&sort=relevance",
                api.calls
                    .single()
                    .call.query,
            )
            assertEquals(
                SearchController.SEARCH_PRODUCTS,
                api.calls
                    .single()
                    .call.operationId,
            )
            api.complete(0, page(listOf("tb:a", "tb:b"), cursor = "c2"))
            runCurrent()
            val s = c.state.value
            assertEquals(SearchPhase.CONTENT, s.phase)
            assertEquals(listOf("tb:a", "tb:b"), s.items.map { it.string("product_key") })
            assertFalse(s.endReached)
        }

    @Test
    fun newKeywordInvalidatesTheOldRequestImmediatelyAndItsLateResponseIsDropped() =
        runTest {
            val c = controller()
            c.open(null, null)
            c.submit("纸巾")
            runCurrent()
            c.submit("牛奶")
            runCurrent()
            val old = api.calls[0]
            assertFalse("新请求一开始旧请求就失效", old.owner.stillOwned())
            assertTrue("旧请求的传输被取消", old.owner in api.cancelled)
            api.complete(0, page(listOf("tb:old")))
            runCurrent()
            assertEquals(SearchPhase.LOADING, c.state.value.phase)
            assertNull(c.state.value.shownSeq)
            api.complete(1, page(listOf("tb:new")))
            runCurrent()
            assertEquals(
                listOf("tb:new"),
                c.state.value.items
                    .map { it.string("product_key") },
            )
        }

    @Test
    fun tabSortAndFilterChangesDropLateOldResponses() =
        runTest {
            config.layers = layers(current = """{"taobao":"on","jd":"on"}""")
            val c = controller()
            c.open("牛奶", null)
            runCurrent()
            assertTrue(c.selectTab("jd"))
            assertTrue(c.selectSort("final_price_asc"))
            assertTrue(c.setFilters(true, 1000, 2000))
            runCurrent()
            assertEquals(4, api.calls.size)
            assertEquals(
                "q=%E7%89%9B%E5%A5%B6&platform=jd&sort=final_price_asc&has_coupon=true&price_min_fen=1000&price_max_fen=2000",
                api.calls[3].call.query,
            )
            // 最新的先到，之后旧的三个迟到
            api.complete(3, page(listOf("jd:latest")))
            api.complete(0, page(listOf("tb:first")))
            api.complete(1, page(listOf("jd:tab")))
            api.complete(2, page(listOf("jd:sort")))
            runCurrent()
            assertEquals(
                listOf("jd:latest"),
                c.state.value.items
                    .map { it.string("product_key") },
            )
            assertTrue(api.calls.take(3).all { !it.owner.stillOwned() })
        }

    @Test
    fun invalidPriceRangeIsRejectedLocally() =
        runTest {
            val c = controller()
            c.open("牛奶", null)
            runCurrent()
            assertFalse("BR-PRICE-15：0 不合法", c.setFilters(false, 0, 100))
            assertFalse("最低高于最高", c.setFilters(false, 5000, 3000))
            assertFalse("超上限", c.setFilters(false, null, 10_000_001))
            assertEquals(1, api.calls.size)
        }

    @Test
    fun loadMoreAppendsAndReachesTheEnd_andATabChangeDropsTheLatePage() =
        runTest {
            config.layers = layers(current = """{"taobao":"on","jd":"on"}""")
            val c = controller()
            c.open("牛奶", null)
            runCurrent()
            api.complete(0, page(listOf("tb:1"), cursor = "c2"))
            runCurrent()
            assertTrue(c.loadMore())
            runCurrent()
            assertTrue(
                api.calls[1]
                    .call.query!!
                    .endsWith("&cursor=c2"),
            )
            api.complete(1, page(listOf("tb:2"), cursor = null))
            runCurrent()
            assertEquals(
                listOf("tb:1", "tb:2"),
                c.state.value.items
                    .map { it.string("product_key") },
            )
            assertTrue(c.state.value.endReached)
            assertFalse("到底不再翻页", c.loadMore())
            assertFalse("翻页结束后不再显示加载中", c.state.value.loadingMore)

            c.selectTab("jd")
            runCurrent()
            api.complete(2, page(listOf("jd:1"), cursor = "j2"))
            runCurrent()
            c.loadMore()
            runCurrent()
            c.selectTab("taobao")
            runCurrent()
            api.complete(3, page(listOf("jd:2"), cursor = null))
            runCurrent()
            assertEquals("换 Tab 后京东第二页迟到：丢弃", SearchPhase.LOADING, c.state.value.phase)
            assertTrue(
                c.state.value.items
                    .isEmpty(),
            )
        }

    @Test
    fun leavingThePageDropsLateResponsesAndCancelsTransport() =
        runTest {
            val c = controller()
            c.open("牛奶", null)
            runCurrent()
            assertEquals(1, config.listeners)
            val before = c.state.value
            c.leave()
            assertFalse(api.calls[0].owner.stillOwned())
            assertTrue(api.calls[0].owner in api.cancelled)
            assertEquals("出栈时取消配置订阅", 0, config.listeners)
            api.complete(0, page(listOf("tb:late")))
            runCurrent()
            assertEquals(before, c.state.value)
            assertFalse("离开后不再发请求", c.submit("纸巾"))
            assertEquals(1, api.calls.size)
        }

    @Test
    fun searchDisabledOnTheFirstPageHidesThePlatformRefreshesConfigAndFallsBackWithoutRetry() =
        runTest {
            config.layers = layers(current = """{"taobao":"on","jd":"on"}""")
            config.issued = 7
            val c = controller()
            c.open("牛奶", "jd")
            runCurrent()
            api.fail(0, 50304, """{"platform":"jd","reason":"search_disabled"}""")
            runCurrent()
            val s = c.state.value
            assertEquals(listOf("taobao"), s.tabs)
            assertEquals("taobao", s.platform)
            assertEquals(1, config.refreshes)
            assertEquals("error.50304.search_disabled", s.notice?.textKey)
            assertEquals("jd", s.notice?.platform)
            assertEquals("回落平台上自动重搜", SearchPhase.LOADING, s.phase)
            assertTrue(
                api.calls[1]
                    .call.query!!
                    .contains("platform=taobao"),
            )
            assertFalse("不给重试", c.retry())
        }

    @Test
    fun searchDisabledOnTheSecondPageIsHandledLikeTheFirstPage() =
        runTest {
            config.layers = layers(current = """{"taobao":"on","jd":"on"}""")
            val c = controller()
            c.open("牛奶", "jd")
            runCurrent()
            api.complete(0, page(listOf("jd:1"), cursor = "j2"))
            runCurrent()
            c.loadMore()
            runCurrent()
            api.fail(1, 50304, """{"platform":"jd","reason":"search_disabled"}""")
            runCurrent()
            val s = c.state.value
            assertEquals(listOf("taobao"), s.tabs)
            assertNull("翻页错误不保留重试入口", s.loadMoreError)
            assertEquals(1, config.refreshes)
            assertTrue(
                api.calls[2]
                    .call.query!!
                    .contains("platform=taobao"),
            )
        }

    @Test
    fun hiddenPlatformComesBackOnlyAfterANewerConfirmedConfigSaysOn() =
        runTest {
            config.layers = layers(current = """{"taobao":"on","jd":"on"}""")
            config.issued = 3
            config.applied = 3
            val c = controller()
            c.open("牛奶", "jd")
            runCurrent()
            api.fail(0, 50304, """{"platform":"jd","reason":"search_disabled"}""")
            runCurrent()
            // 禁用前就在途的那次拉取（编号 3）结算：配置里京东仍是 on，但不能提前恢复
            config.notifyChanged()
            assertEquals(listOf("taobao"), c.state.value.tabs)
            // 分界之后的拉取（编号 4）确认京东 off：继续隐藏
            config.applied = 4
            config.layers = layers(current = """{"taobao":"on","jd":"off"}""")
            config.notifyChanged()
            assertEquals(listOf("taobao"), c.state.value.tabs)
            // 再之后（编号 5，200 或 304 确认）京东重新开放：恢复 Tab，当前平台不变、不打断
            config.applied = 5
            config.layers = layers(current = """{"taobao":"on","jd":"on"}""")
            config.notifyChanged()
            assertEquals(listOf("taobao", "jd"), c.state.value.tabs)
            assertEquals("taobao", c.state.value.platform)
        }

    @Test
    fun configChangeTurningTheCurrentPlatformOffCancelsItsRequestAndFallsBack() =
        runTest {
            config.layers = layers(current = """{"taobao":"on","jd":"on"}""")
            val c = controller()
            c.open("牛奶", "jd")
            runCurrent()
            config.layers = layers(current = """{"taobao":"on","jd":"off"}""")
            config.notifyChanged()
            runCurrent()
            assertFalse(api.calls[0].owner.stillOwned())
            assertTrue(api.calls[0].owner in api.cancelled)
            assertEquals("taobao", c.state.value.platform)
            assertEquals(2, api.calls.size)
        }

    @Test
    fun rateLimitDisablesSearchTabsSortFilterAndRetryUntilRetryAfter() =
        runTest {
            config.layers = layers(current = """{"taobao":"on","jd":"on"}""")
            val c = controller()
            c.open("牛奶", null)
            runCurrent()
            api.fail(0, 42901, "null", disableMillis = 10_000)
            runCurrent()
            assertTrue(c.state.value.blocked)
            assertFalse(c.retry())
            assertFalse(c.submit("纸巾"))
            assertFalse(c.selectTab("jd"))
            assertFalse(c.selectSort("sales_desc"))
            assertFalse(c.setFilters(true, null, null))
            assertEquals(1, api.calls.size)
            advanceTimeBy(10_001)
            runCurrent()
            assertFalse("到期自动恢复", c.state.value.blocked)
            assertTrue(c.retry())
        }

    @Test
    fun serverErrorKeepsTheTrace6_andLoadMoreErrorKeepsTheShownItems() =
        runTest {
            val c = controller()
            c.open("牛奶", null)
            runCurrent()
            api.fail(0, 50401, "{}")
            runCurrent()
            assertEquals(SearchPhase.ERROR, c.state.value.phase)
            assertEquals("出了点问题，请稍后再试（000123）", errorText(c.state.value.error, "taobao", TestTexts))

            assertTrue(c.retry())
            runCurrent()
            api.complete(1, page(listOf("tb:1"), cursor = "c2"))
            runCurrent()
            c.loadMore()
            runCurrent()
            api.fail(2, 50401, "{}")
            runCurrent()
            val s = c.state.value
            assertEquals(listOf("tb:1"), s.items.map { it.string("product_key") })
            assertNotNull(s.loadMoreError)
            assertEquals("出了点问题，请稍后再试（000123）", errorText(s.loadMoreError, s.platform, TestTexts))
            assertTrue("翻页失败可重试", c.retry())
        }

    @Test
    fun staleQuotesRaiseThePriceMayChangeNotice() =
        runTest {
            val c = controller()
            c.open("牛奶", null)
            runCurrent()
            api.complete(0, page(listOf("tb:1"), stale = true))
            runCurrent()
            assertTrue(c.state.value.stale)
        }

    // ---- 第 1 轮评审（android-review-r1.md）

    @Test
    fun r1_01_singlePlatformSearchDisabledThenConfigStillOnRestoresTheTabWithoutSearchingAgain() =
        runTest {
            config.layers = layers(current = """{"taobao":"on"}""")
            val c = controller()
            c.open("牛奶", null)
            runCurrent()
            api.fail(0, 50304, """{"platform":"taobao","reason":"search_disabled"}""")
            runCurrent()
            assertEquals(SearchPhase.UNAVAILABLE, c.state.value.phase)
            assertTrue(
                c.state.value.tabs
                    .isEmpty(),
            )
            // 刷新之后的配置（200 或有效 304）仍说淘宝 on：恢复 Tab，但同一轮不自动重搜（否则会 50304 → 隐藏 → 恢复 → 再搜，循环）
            config.applied = config.issued
            config.notifyChanged()
            runCurrent()
            assertEquals(listOf("taobao"), c.state.value.tabs)
            assertEquals("taobao", c.state.value.platform)
            assertEquals("没有自动再发搜索", 1, api.calls.size)
            assertEquals(SearchPhase.IDLE, c.state.value.phase)
            assertNotNull("提示仍在", c.state.value.notice)
            // 再来一次配置确认也不发
            config.applied += 1
            config.notifyChanged()
            runCurrent()
            assertEquals(1, api.calls.size)
            // 下一次搜索由用户触发
            assertTrue(c.submit("牛奶"))
            runCurrent()
            assertEquals(2, api.calls.size)
        }

    @Test
    fun r1_01_allPlatformsSearchDisabledInTurnDoNotLoopWhenConfigComesBack() =
        runTest {
            config.layers = layers(current = """{"taobao":"on","jd":"on"}""")
            val c = controller()
            c.open("牛奶", "taobao")
            runCurrent()
            api.fail(0, 50304, """{"platform":"taobao","reason":"search_disabled"}""")
            runCurrent()
            assertTrue(
                "回落京东",
                api.calls[1]
                    .call.query!!
                    .contains("platform=jd"),
            )
            api.fail(1, 50304, """{"platform":"jd","reason":"search_disabled"}""")
            runCurrent()
            assertEquals(SearchPhase.UNAVAILABLE, c.state.value.phase)
            config.applied = config.issued
            config.notifyChanged()
            runCurrent()
            assertEquals(
                setOf("taobao", "jd"),
                c.state.value.tabs
                    .toSet(),
            )
            assertEquals("两个平台本轮都关闭过：不再自动搜", 2, api.calls.size)
            assertEquals(SearchPhase.IDLE, c.state.value.phase)
            // 用户换 Tab 是新的一轮
            val other =
                c.state.value.tabs
                    .first { it != c.state.value.platform }
            assertTrue(c.selectTab(other))
            runCurrent()
            assertEquals(3, api.calls.size)
        }

    @Test
    fun r1_02_configChangeDuringRateLimitCancelsAndSwitchesTabButSendsNothingUntilRetryAfter() =
        runTest {
            config.layers = layers(current = """{"taobao":"on","jd":"on"}""")
            val c = controller()
            c.open("牛奶", "taobao")
            runCurrent()
            api.fail(0, 42901, "null", disableMillis = 10_000)
            runCurrent()
            assertTrue(c.state.value.blocked)
            config.layers = layers(current = """{"taobao":"off","jd":"on"}""")
            config.notifyChanged()
            runCurrent()
            assertEquals("Tab 立即更新", listOf("jd"), c.state.value.tabs)
            assertEquals("jd", c.state.value.platform)
            assertEquals("限流期间不发回落搜索", 1, api.calls.size)
            assertTrue(c.state.value.blocked)
            assertEquals(SearchPhase.ERROR, c.state.value.phase)
            assertEquals(
                "仍显示限流错误",
                42901,
                c.state.value.error
                    ?.code,
            )
            assertFalse(c.retry())
            advanceTimeBy(10_001)
            runCurrent()
            assertEquals(1, api.calls.size)
            assertFalse(c.state.value.blocked)
            assertTrue(c.retry())
            runCurrent()
            assertTrue(
                api.calls[1]
                    .call.query!!
                    .contains("platform=jd"),
            )
        }

    @Test
    fun r1_02_configChangeDuringRateLimitAfterALoadMoreErrorAlsoSendsNothing() =
        runTest {
            config.layers = layers(current = """{"taobao":"on","jd":"on"}""")
            val c = controller()
            c.open("牛奶", "taobao")
            runCurrent()
            api.complete(0, page(listOf("tb:1"), cursor = "c2"))
            runCurrent()
            c.loadMore()
            runCurrent()
            api.fail(1, 42901, "null", disableMillis = 10_000)
            runCurrent()
            config.layers = layers(current = """{"taobao":"off","jd":"on"}""")
            config.notifyChanged()
            runCurrent()
            assertEquals(2, api.calls.size)
            assertTrue(
                "旧平台的结果不再显示",
                c.state.value.items
                    .isEmpty(),
            )
            assertEquals("jd", c.state.value.platform)
        }

    @Test
    fun r1_03_trace6IsShownForErrorsWhoseTextHasNoPlaceholder() =
        runTest {
            config.layers = layers(current = """{"taobao":"on","jd":"on"}""")
            val c = controller()
            c.open("牛奶", "jd")
            runCurrent()
            api.fail(0, 50304, """{"platform":"jd"}""")
            runCurrent()
            assertEquals("京东搜索暂不可用，请稍后再试（000123）", errorText(c.state.value.error, "jd", TestTexts))

            // 翻页 50304
            assertTrue(c.retry())
            runCurrent()
            api.complete(1, page(listOf("jd:1"), cursor = "j2"))
            runCurrent()
            c.loadMore()
            runCurrent()
            api.fail(2, 50304, """{"platform":"jd"}""")
            runCurrent()
            assertEquals("京东搜索暂不可用，请稍后再试（000123）", errorText(c.state.value.loadMoreError, "jd", TestTexts))

            // search_disabled 的提示
            assertTrue(c.retry())
            runCurrent()
            api.fail(3, 50304, """{"platform":"jd","reason":"search_disabled"}""")
            runCurrent()
            assertEquals("京东暂不提供搜索，可以粘贴商品链接查返利（000123）", errorText(c.state.value.notice, null, TestTexts))
        }

    @Test
    fun r1_03_trace6IsNotDuplicatedAndOmittedWithoutTraceId() {
        val withPlaceholder = SearchResultView(SearchStateRaw.ERROR, "error.50401", 50401, null, TRACE)
        assertEquals("出了点问题，请稍后再试（000123）", errorText(withPlaceholder, "taobao", TestTexts))
        val noTrace = SearchResultView(SearchStateRaw.ERROR, "error.42901", 42901, null, null)
        assertEquals("操作太频繁，请稍后再试", errorText(noTrace, "taobao", TestTexts))
        val limited = SearchResultView(SearchStateRaw.ERROR, "error.42901", 42901, null, TRACE)
        assertEquals("操作太频繁，请稍后再试（000123）", errorText(limited, "taobao", TestTexts))
    }

    @Test
    fun r1_04_pageFooterKeepsTheRebateSentences() {
        val estimate = "预估返利以平台结算为准，未结算或订单失效时不返"
        assertEquals(
            "普通返利卡：价格句 + 返利结算说明",
            listOf(PriceTerms.PRICE_BASIS, estimate),
            pageDisclaimerLines(listOf(listOf("price_basis", "rebate_estimate"))),
        )
        assertEquals(
            "比价区间卡",
            listOf(PriceTerms.PRICE_BASIS, "以结算为准"),
            pageDisclaimerLines(listOf(listOf("price_basis", "rebate_compare"))),
        )
        // 混排：价格句合并为 general；返利句两种都保留
        val mixed =
            pageDisclaimerLines(
                listOf(listOf("price_basis", "rebate_estimate"), listOf("price_basis.general", "rebate_compare"), listOf("price_basis")),
            )
        assertEquals(3, mixed.size)
        assertEquals(estimate, mixed[1])
        assertEquals("以结算为准", mixed[2])
        assertEquals(PriceTerms.PRICE_BASIS_GENERAL, mixed[0])
        assertTrue(pageDisclaimerLines(emptyList()).isEmpty())
    }

    @Test
    fun r1_06_roundOnlyChangesWhenANewFirstPageStarts() =
        runTest {
            val c = controller()
            c.open("牛奶", null)
            runCurrent()
            val first = c.state.value.round
            api.complete(0, page(listOf("tb:1"), cursor = "c2"))
            runCurrent()
            c.loadMore()
            runCurrent()
            api.complete(1, page(listOf("tb:2")))
            runCurrent()
            assertEquals("翻页不算新一轮", first, c.state.value.round)
            c.submit("纸巾")
            runCurrent()
            assertEquals(first + 1, c.state.value.round)
        }

    // ---- 假服务

    private class Pending(
        val call: ApiCall,
        val owner: CallOwnership,
        val result: CompletableDeferred<ApiResult>,
    )

    private class FakeApi : PageApi {
        val calls = mutableListOf<Pending>()
        val cancelled = mutableListOf<CallOwnership>()

        override suspend fun send(call: ApiCall): ApiResult {
            val pending = Pending(call, call.ownership!!, CompletableDeferred())
            calls += pending
            return pending.result.await()
        }

        override fun cancelOwned(owner: CallOwnership) {
            cancelled += owner
        }

        override fun epochs(): CallEpochs = CallEpochs(0, 0)

        fun complete(
            index: Int,
            data: String,
        ) {
            calls[index].result.complete(ApiResult.Success(Json.parseToJsonElement(data), TRACE, null))
        }

        fun fail(
            index: Int,
            code: Int,
            data: String,
            disableMillis: Long? = null,
        ) {
            val parsed = Json.parseToJsonElement(data) as? JsonObject ?: JsonObject(emptyMap())
            val kind = if (disableMillis != null) ErrorEffectKind.DISABLE_BUTTON else ErrorEffectKind.GENERIC_ERROR
            val effect = ErrorEffect(code, kind, ReplayMode.NONE, registerPending = false, disableMillis = disableMillis)
            calls[index].result.complete(ApiResult.Failure(code, "", parsed, TRACE, effect, null))
        }
    }

    private class FakeConfig : SearchConfigPort {
        var layers: JsonObject = layers(current = """{"taobao":"on"}""")
        var issued = 0L
        var applied = 0L
        var refreshes = 0
        private val subscribers = mutableListOf<() -> Unit>()
        val listeners: Int get() = subscribers.size

        override fun statusLayers(): JsonObject = layers

        override fun refresh() {
            refreshes += 1
            issued += 1
        }

        override fun issuedMark(): Long = issued

        override fun appliedMark(): Long = applied

        override fun subscribe(listener: () -> Unit): () -> Unit {
            subscribers += listener
            return { subscribers -= listener }
        }

        fun notifyChanged() = subscribers.toList().forEach { it() }
    }

    private object TestTexts : SearchTexts {
        private val platforms = mapOf("platform.taobao" to "淘宝", "platform.jd" to "京东")

        override fun text(key: String): String = DefaultTexts.text(key) ?: platforms[key] ?: DefaultTexts.fallback(key) ?: ""

        override fun format(
            key: String,
            vars: Map<String, String>,
        ): String {
            var text = DefaultTexts.text(key) ?: return text(key)
            vars.forEach { (name, value) -> if (value.isNotEmpty()) text = text.replace("{$name}", value) }
            return text
        }
    }

    private companion object {
        const val TRACE = "0199a3b4-5c6d-7e8f-9a0b-1c2d3e000123"

        fun layers(
            current: String? = null,
            lkg: String? = null,
        ): JsonObject {
            val fields = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
            current?.let { fields["current"] = Json.parseToJsonElement(it).jsonObject }
            lkg?.let { fields["lkg"] = Json.parseToJsonElement(it).jsonObject }
            return JsonObject(fields)
        }

        fun page(
            keys: List<String>,
            cursor: String? = null,
            stale: Boolean = false,
        ): String {
            val items =
                keys.joinToString(",") { key ->
                    """{"product_key":"$key","platform":"taobao","title":"t","price_fen":3990,"coupon_fen":1000,"final_price_fen":2990,""" +
                        """"rebate_min_fen":269,"rebate_max_fen":269,"rebate_basis":"normal","stale":$stale,"age_sec":12,""" +
                        """"cta":{"text_key":"btn.buy.coupon"},"availability":"ok"}"""
                }
            val next = cursor?.let { "\"$it\"" } ?: "null"
            return """{"items":[$items],"next_cursor":$next,"has_more":${cursor != null},"fallback_items":[]}"""
        }
    }
}
