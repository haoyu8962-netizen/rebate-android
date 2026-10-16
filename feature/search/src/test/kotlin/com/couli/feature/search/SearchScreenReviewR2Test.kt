package com.couli.feature.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.PriceTerms
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.PageApi
import com.couli.generated.DefaultTexts
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 第 2 轮评审（android-review-r2.md）的页面级测试，整页 [SearchScreen] + 本地假服务：
 * - R2-03 销毁旧 ViewModel（onCleared，模拟进程被系统回收）后，用同一份 SavedStateHandle 新建 ViewModel 和新的组合：关键词、筛选、
 *   价格草稿、滚到第二页的位置都按保存的状态恢复，不回到路由参数，也不当作新查询滚回顶部；
 * - R2-04 混排边界：可见卡片集合变化时，说明区的句子跟着变，占用高度不变（列表高度也不变）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-xxhdpi")
class SearchScreenReviewR2Test {
    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun pageConfig(
        api: PageApi,
        query: String?,
    ): SearchPageConfig = SearchPageConfig(api = api, config = OnlyTaobao, texts = Texts, history = SearchHistory(), query = query)

    /** 用 [store] 托管 ViewModel（store.clear() 时 onCleared，同进程被回收时一样）。 */
    private fun newModel(
        store: ViewModelStore,
        config: SearchPageConfig,
        saved: SavedStateHandle,
    ): SearchViewModel {
        val factory = viewModelFactory { initializer { SearchViewModel(config, saved) } }
        return ViewModelProvider.create(store, factory)[SearchViewModel::class]
    }

    @Test
    fun r2_03_recreatingTheViewModelWithTheSameSavedStateHandleRestoresThePage() {
        val api = TwoPages()
        // 从首页进入（路由没有 q）
        val config = pageConfig(api, query = null)
        val saved = SavedStateHandle()
        val firstStore = ViewModelStore()
        var model by mutableStateOf(newModel(firstStore, config, saved))
        rule.setContent { key(model) { CouliTheme { SearchScreen(model, config) } } }
        rule.waitForIdle()
        rule.onNodeWithTag(SearchTags.INPUT).performTextInput("milk")
        rule.onNodeWithTag(SearchTags.SUBMIT).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(SearchTags.COUPON).performClick()
        rule.waitForIdle()
        // 滚到第二页（第 1 页 20 张，接近底部时自动翻页）
        rule.onNodeWithTag(SearchTags.RESULTS).performScrollToIndex(PAGE_SIZE)
        rule.waitForIdle()
        rule.onNodeWithTag(SearchTags.RESULTS).performScrollToIndex(TARGET + 1)
        rule.waitForIdle()
        rule.onNodeWithTag(SearchTags.CARD_PREFIX + "tb:$TARGET").assertIsDisplayed()
        // 填了还没提交的价格草稿
        rule.onNodeWithTag(SearchTags.PRICE).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(SearchTags.PRICE_MIN).performTextInput("10")
        rule.onNodeWithTag(SearchTags.PRICE_MAX).performTextInput("50")
        rule.waitForIdle()
        val before = calls.size

        // 旧 ViewModel 销毁；同一份保存状态新建 ViewModel、新的组合（rememberSaveable 也不沿用）
        rule.runOnIdle {
            firstStore.clear()
            model = newModel(ViewModelStore(), config, saved)
        }
        rule.waitForIdle()

        rule.onNodeWithTag(SearchTags.INPUT).assert(hasText("milk"))
        rule.onNodeWithTag(SearchTags.COUPON).assertIsSelected()
        rule.onNodeWithTag(SearchTags.PRICE_MIN).assert(hasText("10"))
        rule.onNodeWithTag(SearchTags.PRICE_MAX).assert(hasText("50"))
        assertTrue("按保存的条件重搜第一页", calls[before].contains("q=milk") && calls[before].contains("has_coupon=true"))
        assertTrue("不是路由参数的空输入态", calls.drop(before).isNotEmpty())
        rule.onNodeWithTag(SearchTags.CARD_PREFIX + "tb:$TARGET").assertIsDisplayed()
        rule.onNodeWithTag(SearchTags.CARD_PREFIX + "tb:0").assertDoesNotExist()
    }

    @Test
    fun r2_03_anAnchorAtTheEndOfPageOneComesBackAtTheSameOffset() {
        val config = pageConfig(TwoPages(), query = "milk")
        val saved = SavedStateHandle()
        val firstStore = ViewModelStore()
        var model by mutableStateOf(newModel(firstStore, config, saved))
        rule.setContent { key(model) { CouliTheme { SearchScreen(model, config) } } }
        rule.waitForIdle()
        // 第一页最后一张卡在顶部（下面露出第二页的卡）
        rule.onNodeWithTag(SearchTags.RESULTS).performScrollToIndex(PAGE_SIZE)
        rule.waitForIdle()
        // 第二页到了之后再滚一次，第一页最后一张才能停在顶部
        rule.onNodeWithTag(SearchTags.RESULTS).performScrollToIndex(PAGE_SIZE)
        rule.waitForIdle()
        val last = SearchTags.CARD_PREFIX + "tb:${PAGE_SIZE - 1}"
        val topBefore = rule.onNodeWithTag(last).getBoundsInRoot().top

        rule.runOnIdle {
            firstStore.clear()
            model = newModel(ViewModelStore(), config, saved)
        }
        rule.waitForIdle()

        assertEquals("恢复到同一位置（不被截到第一页末尾之上）", topBefore, rule.onNodeWithTag(last).getBoundsInRoot().top)
        rule.onNodeWithTag(SearchTags.CARD_PREFIX + "tb:$PAGE_SIZE").assertIsDisplayed()
    }

    /**
     * FU-AND-0717 F-X-18：进程重建后的一次性恢复按真实布局确认落点后才结束（结束原因 aligned），不是 scrollToItem 返回就算完成；落点与保存时一致。
     * 布局滞后的确定性场景见 core/designsystem 的 RestoreLandingTest（逐帧模拟）。
     */
    @Test
    fun fuand0717_x18_theRestoreEndsOnlyAfterTheRealLayoutConfirmsTheAnchor() {
        val config = pageConfig(TwoPages(), query = "milk")
        val saved = SavedStateHandle()
        val firstStore = ViewModelStore()
        var model by mutableStateOf(newModel(firstStore, config, saved))
        rule.setContent { key(model) { CouliTheme { SearchScreen(model, config) } } }
        rule.waitForIdle()
        rule.onNodeWithTag(SearchTags.RESULTS).performScrollToIndex(PAGE_SIZE)
        rule.waitForIdle()
        rule.onNodeWithTag(SearchTags.RESULTS).performScrollToIndex(TARGET + 1)
        rule.waitForIdle()
        val card = SearchTags.CARD_PREFIX + "tb:$TARGET"
        val topBefore = rule.onNodeWithTag(card).getBoundsInRoot().top

        rule.runOnIdle {
            firstStore.clear()
            model = newModel(ViewModelStore(), config, saved)
        }
        // 落点确认按显示帧推进：逐帧推进直到恢复结束（上限内没结束按失败）
        var frames = 0
        while (!(model.pendingScroll == null && model.restoreEnd != null)) {
            check(frames++ < MAX_FRAMES) { "恢复在 $MAX_FRAMES 帧内没有结束：pending=${model.pendingScroll} end=${model.restoreEnd}" }
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
        }
        assertEquals("按真实布局确认后结束", RESTORE_LANDED, model.restoreEnd)
        assertEquals("回到同一位置", topBefore, rule.onNodeWithTag(card).getBoundsInRoot().top)
    }

    /**
     * FU-AND-0717 F-X-18 补充：开始新一轮查询时同步清掉旧锚点——条件与轮次写进保存状态的同一步里，滚动锚点已是空的（第一次持久化就是「新条件 + 空锚点」），
     * 进程此刻被杀也不会把旧锚点套到新一轮的结果上。
     */
    @Test
    fun fuand0717_x18_aNewQueryPersistsTheNewConditionsWithAnEmptyAnchorInTheSameStep() {
        val config = pageConfig(TwoPages(), query = "milk")
        val saved = SavedStateHandle()
        val model = newModel(ViewModelStore(), config, saved)
        rule.setContent { CouliTheme { SearchScreen(model, config) } }
        rule.waitForIdle()
        rule.onNodeWithTag(SearchTags.RESULTS).performScrollToIndex(PAGE_SIZE)
        rule.waitForIdle()
        assertTrue("前提：记下了非零锚点", (saved.get<Int>("search.scroll_index") ?: 0) > 0)
        val round = saved.get<Long>("search.round")
        // 停住 Compose 时钟：只看新一轮条件写进保存状态的那一步（页面上「新一轮滚回顶部、再记视口锚点」的效果此时还没执行）
        rule.mainClock.autoAdvance = false
        var seen: Pair<Long?, Int?>? = null
        rule.runOnUiThread {
            model.onCouponToggle()
            seen = saved.get<Long>("search.round") to saved.get<Int>("search.scroll_index")
        }
        rule.mainClock.autoAdvance = true
        assertTrue("开始了新一轮", seen?.first != round)
        assertEquals("同一步里锚点已清零", 0, seen?.second)
        assertEquals(0, saved.get<Int>("search.scroll_offset"))
    }

    /**
     * FU-AND-0717 F-X-20（模拟器实走发现，Debug 假搜索第一页 3 张）：锚点是第一页最后一张卡，进程重建时列表自己按系统保存的位置恢复（rememberSaveable），
     * 第一页只够几张卡就被截到末尾，恢复任务把这次截断当成别人的移动而让位，结果停在上一张卡。改为有待恢复时列表从顶部开始、由恢复任务定位：
     * 先翻到第二页再顶对齐，结束原因 aligned、位置与回收前一致。
     */
    @Test
    fun fuand0717_x20_anAnchorAtTheEndOfAShortFirstPageRestoresAfterTheListsOwnSavedPositionIsRestoredToo() {
        // 与 App 一样，进程恢复后先等本轮版本检查（restoreGate）再重搜：等待期间列表已组合、系统保存的列表位置已恢复
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val config =
            SearchPageConfig(
                api = SmallPages(),
                config = OnlyTaobao,
                texts = Texts,
                history = SearchHistory(),
                query = "milk",
                restoreGate = { gate.await() },
            )
        val saved = SavedStateHandle()
        val firstStore = ViewModelStore()
        // 普通变量（不是可观察状态）：换 ViewModel 不触发重组，只在下面模拟的「系统按保存状态重建组合」时才用新的
        var model = newModel(firstStore, config, saved)
        val restorer =
            androidx.compose.ui.test.junit4
                .StateRestorationTester(rule)
        restorer.setContent { CouliTheme { SearchScreen(model, config) } }
        rule.waitForIdle()
        rule.onNodeWithTag(SearchTags.RESULTS).performScrollToIndex(SMALL_TARGET_INDEX)
        rule.waitForIdle()
        val card = SearchTags.CARD_PREFIX + "tb:${SMALL_TARGET_INDEX - 1}"
        val topBefore = rule.onNodeWithTag(card).getBoundsInRoot().top
        // 进程被回收：旧 ViewModel 清掉，同一份 SavedStateHandle 新建；组合按系统保存的状态重建（rememberSaveable 的列表位置也随之恢复）
        rule.runOnIdle {
            firstStore.clear()
            model = newModel(ViewModelStore(), config, saved)
        }
        restorer.emulateSavedInstanceStateRestore()
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        gate.complete(Unit)
        var frames = 0
        while (!(model.pendingScroll == null && model.restoreEnd != null)) {
            check(frames++ < MAX_FRAMES) { "恢复在 $MAX_FRAMES 帧内没有结束：pending=${model.pendingScroll} end=${model.restoreEnd}" }
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
        }
        assertEquals("恢复任务没有被列表自己的位置恢复挤掉", RESTORE_LANDED, model.restoreEnd)
        assertEquals("锚点（第一页末张）回到原位置", topBefore, rule.onNodeWithTag(card).getBoundsInRoot().top)
    }

    /**
     * FU-AND-0717 第 1 轮修复 R1-06（搜索页）：进程重建后恢复任务正在等第二页（网络挂住）时，手指按下、没有移动——即算用户接管：恢复取消（按此刻视口记位置），
     * 第二页到了也不再定位。
     */
    @Test
    fun fuand0717_r1_06_aTouchWithoutMovingWhileTheRestoreWaitsForTheNextPageCancelsIt() {
        val api = SmallPages()
        val config = pageConfig(api, query = "milk")
        val saved = SavedStateHandle()
        val firstStore = ViewModelStore()
        var model by mutableStateOf(newModel(firstStore, config, saved))
        rule.setContent { key(model) { CouliTheme { SearchScreen(model, config) } } }
        rule.waitForIdle()
        rule.onNodeWithTag(SearchTags.RESULTS).performScrollToIndex(SMALL_TARGET_INDEX)
        rule.waitForIdle()
        // 重建后的第二页挂住：恢复任务停在「等翻页」
        val held = kotlinx.coroutines.CompletableDeferred<Unit>()
        api.hold = held
        rule.runOnIdle {
            firstStore.clear()
            model = newModel(ViewModelStore(), config, saved)
        }
        framesUntil("恢复任务在等第二页") { api.secondPageAsked && model.pendingScroll != null }
        rule.onNodeWithTag(SearchTags.RESULTS).performTouchInput { down(center) }
        rule.waitForIdle()
        assertEquals("按下即取消", null, model.pendingScroll)
        assertEquals(RESTORE_TAKEN_OVER, model.restoreEnd)
        rule.onNodeWithTag(SearchTags.RESULTS).performTouchInput { up() }
        held.complete(Unit)
        framesUntil("第二页已到") { model.state.value.endReached }
        assertEquals("第二页到了也不再定位", RESTORE_TAKEN_OVER, model.restoreEnd)
        rule.onNodeWithTag(SearchTags.CARD_PREFIX + "tb:0").assertIsDisplayed()
    }

    /** 按显示帧推进直到 [condition] 成立（上限 [MAX_FRAMES] 帧，超过按失败）。 */
    private fun framesUntil(
        what: String,
        condition: () -> Boolean,
    ) {
        var frames = 0
        while (!condition()) {
            check(frames++ < MAX_FRAMES) { "$MAX_FRAMES 帧内没有等到：$what" }
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
        }
    }

    /** 第一页 3 张（游标 c2），第二页 2 张（与 Debug 假搜索同形）。 */
    private inner class SmallPages : PageApi {
        /** 第二页挂住（非空时）。 */
        @Volatile var hold: kotlinx.coroutines.CompletableDeferred<Unit>? = null

        @Volatile var secondPageAsked = false

        override suspend fun send(call: ApiCall): ApiResult {
            val second = call.query.orEmpty().contains("cursor=c2")
            if (second) {
                hold?.let {
                    secondPageAsked = true
                    it.await()
                }
            }
            return success(if (second) page(3 until 5, null) else page(0 until 3, "c2"))
        }

        override fun cancelOwned(owner: CallOwnership) = Unit

        override fun epochs(): CallEpochs = CallEpochs(0, 0)
    }

    @Test
    fun r2_04_disclaimerKeepsItsHeightWhenTheVisibleCardsChange() {
        val api = Mixed()
        val config = pageConfig(api, query = "milk")
        val model = newModel(ViewModelStore(), config, SavedStateHandle())
        rule.setContent { CouliTheme { SearchScreen(model, config) } }
        rule.waitForIdle()
        // 首屏只有比价区间卡：没有普通返利的结算句
        rule.onNodeWithText(PriceTerms.SETTLE_BASIS).assertIsDisplayed()
        rule.onNodeWithText(PriceTerms.REBATE_ESTIMATE).assertDoesNotExist()
        val disclaimerBefore = height(SearchTags.DISCLAIMER)
        val listBefore = height(SearchTags.RESULTS)
        // 滚到混排边界之后：普通返利卡可见，句子加上结算说明
        rule.onNodeWithTag(SearchTags.RESULTS).performScrollToIndex(COMPARE_CARDS + 1)
        rule.waitForIdle()
        rule.onNodeWithText(PriceTerms.REBATE_ESTIMATE).assertIsDisplayed()
        assertEquals("说明区高度不随可见卡片变化", disclaimerBefore, height(SearchTags.DISCLAIMER))
        assertEquals("列表高度不变（不会把边界上的卡挤出去再露出来）", listBefore, height(SearchTags.RESULTS))
        // 再滚回去：句子回到只有比价卡的那组，高度仍不变
        rule.onNodeWithTag(SearchTags.RESULTS).performScrollToIndex(0)
        rule.waitForIdle()
        rule.onNodeWithText(PriceTerms.REBATE_ESTIMATE).assertDoesNotExist()
        assertEquals(disclaimerBefore, height(SearchTags.DISCLAIMER))
    }

    @Test
    fun r2_04_reservedLinesCoverEveryVisibleSubset() {
        val compare = listOf("price_basis", "rebate_compare")
        val normal = listOf("price_basis", "rebate_estimate")
        val general = listOf("price_basis.general", "rebate_estimate")
        val all = listOf(compare, normal, general)
        val reserved = reservedDisclaimerLines(all)
        val subsets = listOf(listOf(compare), listOf(normal), listOf(general), listOf(compare, normal), listOf(normal, general), all)
        subsets.forEach { subset -> assertTrue(subset.toString(), reserved.containsAll(pageDisclaimerLines(subset))) }
    }

    private fun height(tag: String): Dp = rule.onNodeWithTag(tag).getBoundsInRoot().let { it.bottom - it.top }

    // ---- 本地假服务

    /** 第一页 tb:0..19（游标 c2），第二页 tb:20..39（到底）。 */
    private inner class TwoPages : PageApi {
        override suspend fun send(call: ApiCall): ApiResult {
            val query = call.query.orEmpty()
            calls += query
            val second = query.contains("cursor=c2")
            return success(if (second) page(PAGE_SIZE until 2 * PAGE_SIZE, null) else page(0 until PAGE_SIZE, "c2"))
        }

        override fun cancelOwned(owner: CallOwnership) = Unit

        override fun epochs(): CallEpochs = CallEpochs(0, 0)
    }

    /** 前 [COMPARE_CARDS] 张是比价区间卡，之后是普通返利卡（混排）。 */
    private inner class Mixed : PageApi {
        override suspend fun send(call: ApiCall): ApiResult {
            calls += call.query.orEmpty()
            return success(page(0 until PAGE_SIZE, null) { it < COMPARE_CARDS })
        }

        override fun cancelOwned(owner: CallOwnership) = Unit

        override fun epochs(): CallEpochs = CallEpochs(0, 0)
    }

    private object OnlyTaobao : SearchConfigPort {
        override fun statusLayers(): JsonObject = Json.parseToJsonElement("""{"current":{"taobao":"on"}}""").jsonObject

        override fun refresh() = Unit

        override fun issuedMark(): Long = 0

        override fun appliedMark(): Long = 0

        override fun subscribe(listener: () -> Unit): () -> Unit = {}
    }

    private object Texts : SearchTexts {
        private val drafts =
            mapOf(
                "platform.taobao" to "淘宝",
                "external_page.retry" to "重试",
                "search.filter.price" to "价格区间",
                "search.filter.coupon" to "只看有券",
                "search.submit" to "搜索",
                "search.filter.sheet.min" to "最低价",
                "search.filter.sheet.max" to "最高价",
                "search.filter.sheet.confirm" to "确定",
                "search.filter.sheet.reset" to "重置",
            )

        override fun text(key: String): String = DefaultTexts.text(key) ?: drafts[key] ?: DefaultTexts.fallback(key) ?: ""

        override fun format(
            key: String,
            vars: Map<String, String>,
        ): String {
            var text = DefaultTexts.text(key) ?: drafts[key] ?: return text(key)
            vars.forEach { (name, value) -> if (value.isNotEmpty()) text = text.replace("{$name}", value) }
            return text
        }
    }

    private companion object {
        const val TRACE = "0199a3b4-5c6d-7e8f-9a0b-1c2d3e000123"
        const val PAGE_SIZE = 20
        const val TARGET = 25
        const val COMPARE_CARDS = 6
        const val MAX_FRAMES = 600

        /** 第一页最后一张（tb:2）的列表下标（前面有一项提示行）。 */
        const val SMALL_TARGET_INDEX = 3

        fun success(data: String): ApiResult = ApiResult.Success(Json.parseToJsonElement(data), TRACE, null)

        fun page(
            indices: IntRange,
            cursor: String?,
            compare: (Int) -> Boolean = { false },
        ): String {
            val items =
                indices.joinToString(",") { i ->
                    val rebate =
                        if (compare(i)) {
                            """"rebate_min_fen":56,"rebate_max_fen":112,"rebate_basis":"compare",""" +
                                """"disclaimer_keys":["price_basis","rebate_compare"],"""
                        } else {
                            """"rebate_min_fen":269,"rebate_max_fen":269,"rebate_basis":"normal",""" +
                                """"disclaimer_keys":["price_basis","rebate_estimate"],"""
                        }
                    """{"product_key":"tb:$i","platform":"taobao","title":"item $i","price_fen":3990,"coupon_fen":1000,""" +
                        """"final_price_fen":2990,$rebate"est_net_price_fen":2721,"age_sec":12,""" +
                        """"cta":{"text_key":"btn.buy.coupon"},"availability":"ok"}"""
                }
            val next = cursor?.let { "\"$it\"" } ?: "null"
            return """{"items":[$items],"next_cursor":$next,"has_more":${cursor != null},"fallback_items":[]}"""
        }
    }
}
