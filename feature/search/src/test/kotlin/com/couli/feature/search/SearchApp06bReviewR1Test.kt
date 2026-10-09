package com.couli.feature.search

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.CouliTheme
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.PageApi
import com.couli.generated.DefaultTexts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 第 1 轮评审 R1-09、R1-16、R1-17 在搜索页上的回归测试（整页 [SearchScreen] / 执行器 + 本地假服务）：
 * - R1-09：搜索卡按自己的 product_key + item_ref 重取权威卡片并替换（30144、open 响应给不全字段时）；
 * - R1-16：搜索卡 no_rebate_cause=price_compare 时渲染原因行与【看看相似商品】，操作绑定那张卡；
 * - R1-17：滚动恢复任务绑定查询轮次与页面生命周期（用户拖动、新查询即取消；被覆盖时暂停、冻结位置记录；进后台同步保存）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-xxhdpi")
class SearchApp06bReviewR1Test {
    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<ApiCall>()

    // ---------------------------------------------------------------- R1-09

    @Test
    fun r1_09_refetchReplacesThatCardWithTheAuthoritativeOne() {
        val api =
            object : PageApi {
                override suspend fun send(call: ApiCall): ApiResult {
                    calls += call
                    return if (call.operationId == SearchController.GET_PRODUCT) {
                        success(card(0, link = "link-fresh", final = 2590))
                    } else {
                        success(page(0 until 2, null))
                    }
                }

                override fun cancelOwned(owner: CallOwnership) = Unit

                override fun epochs(): CallEpochs = CallEpochs(0, 0)
            }
        val controller = SearchController(api, OnlyTaobao, CoroutineScope(Dispatchers.Unconfined))
        controller.open(q = "milk", platform = null)
        val target = controller.state.value.items[0]
        controller.refetchItem(target)
        val call = calls.last()
        assertEquals(SearchController.GET_PRODUCT, call.operationId)
        assertEquals(mapOf("product_key" to "tb:0"), call.pathParams)
        assertEquals("item_ref=v1.tb.0.sig", call.query)
        val replaced = controller.state.value.items[0]
        assertEquals("换成新 link_id", "link-fresh", replaced["link_id"]!!.jsonPrimitive.content)
        assertEquals("2590", replaced["final_price_fen"]!!.jsonPrimitive.content)
        assertEquals(
            "别的卡不动",
            "link-1",
            controller.state.value.items[1]["link_id"]!!
                .jsonPrimitive.content,
        )
        controller.leave()
    }

    // ---------------------------------------------------------------- R1-16

    @Test
    fun r1_16_aPriceCompareCardShowsTheReasonAndSimilarEntryBoundToThatCard() {
        val similar = mutableListOf<JsonObject>()
        val api =
            object : PageApi {
                override suspend fun send(call: ApiCall): ApiResult = success(page(0 until 2, null, compareAt = 1))

                override fun cancelOwned(owner: CallOwnership) = Unit

                override fun epochs(): CallEpochs = CallEpochs(0, 0)
            }
        val config =
            SearchPageConfig(
                api = api,
                config = OnlyTaobao,
                texts = Texts,
                history = SearchHistory(),
                query = "milk",
                actions = SearchPageActions(onSimilar = { similar += it }),
            )
        rule.setContent { CouliTheme { SearchScreen(config) } }
        rule.waitForIdle()
        rule.onNodeWithTag(SearchTags.COMPARE_PREFIX + "tb:0", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag(SearchTags.COMPARE_PREFIX + "tb:1", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(SearchTags.COMPARE_PREFIX + "tb:1_similar", useUnmergedTree = true).performClick()
        assertEquals("tb:1", similar.single()["product_key"]!!.jsonPrimitive.content)
    }

    // ---------------------------------------------------------------- R1-17

    private fun newModel(
        store: ViewModelStore,
        config: SearchPageConfig,
        saved: SavedStateHandle,
    ): SearchViewModel {
        val factory = viewModelFactory { initializer { SearchViewModel(config, saved) } }
        return ViewModelProvider.create(store, factory)[SearchViewModel::class]
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).also { it.currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    /** 先在第一个 ViewModel 上滚到第二页的 [TARGET]，再销毁它、用同一份保存状态新建（第二页由 [gate] 控制何时回答）。 */
    private fun restoredOnPageTwo(
        gate: CompletableDeferred<Unit>,
        owner: Owner = Owner(),
    ): Pair<SearchPageConfig, SavedStateHandle> {
        var gated = false
        val api =
            object : PageApi {
                override suspend fun send(call: ApiCall): ApiResult {
                    calls += call
                    val query = call.query.orEmpty()
                    val second = query.contains("cursor=c2")
                    if (second && gated && !gate.isCompleted) gate.await()
                    return success(if (second) page(PAGE_SIZE until 2 * PAGE_SIZE, null) else page(0 until PAGE_SIZE, "c2"))
                }

                override fun cancelOwned(owner: CallOwnership) = Unit

                override fun epochs(): CallEpochs = CallEpochs(0, 0)
            }
        val config = SearchPageConfig(api = api, config = OnlyTaobao, texts = Texts, history = SearchHistory(), query = "milk")
        val saved = SavedStateHandle()
        val firstStore = ViewModelStore()
        var model by mutableStateOf(newModel(firstStore, config, saved))
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) { key(model) { CouliTheme { SearchScreen(model, config) } } }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(SearchTags.RESULTS).performScrollToIndex(PAGE_SIZE)
        rule.waitForIdle()
        rule.onNodeWithTag(SearchTags.RESULTS).performScrollToIndex(TARGET + 1)
        rule.waitForIdle()
        rule.onNodeWithTag(SearchTags.CARD_PREFIX + "tb:$TARGET").assertIsDisplayed()
        rule.runOnIdle {
            gated = true
            firstStore.clear()
            model = newModel(ViewModelStore(), config, saved)
        }
        rule.waitForIdle()
        return config to saved
    }

    @Test
    fun r1_17_aUserDragCancelsThePendingRestore() {
        val gate = CompletableDeferred<Unit>()
        restoredOnPageTwo(gate)
        // 恢复正在等第二页；用户先拖动了列表
        rule.onNodeWithTag(SearchTags.CARD_PREFIX + "tb:0").assertIsDisplayed()
        rule.onNodeWithTag(SearchTags.RESULTS).performTouchInput { swipeUp(startY = centerY, endY = centerY - SWIPE_PX) }
        rule.waitForIdle()
        gate.complete(Unit)
        rule.waitForIdle()
        rule.onNodeWithTag(SearchTags.CARD_PREFIX + "tb:$TARGET").assertDoesNotExist()
    }

    @Test
    fun r1_17_aNewQueryCancelsThePendingRestore() {
        val gate = CompletableDeferred<Unit>()
        restoredOnPageTwo(gate)
        rule.onNodeWithTag(SearchTags.INPUT).performTextClearance()
        rule.onNodeWithTag(SearchTags.INPUT).performTextInput("tea")
        rule.onNodeWithTag(SearchTags.SUBMIT).performClick()
        rule.waitForIdle()
        gate.complete(Unit)
        rule.waitForIdle()
        rule.onNodeWithTag(SearchTags.CARD_PREFIX + "tb:0").assertIsDisplayed()
        rule.onNodeWithTag(SearchTags.CARD_PREFIX + "tb:$TARGET").assertDoesNotExist()
        assertTrue("新一轮查询没有为旧锚点继续翻页", calls.none { it.query.orEmpty().let { q -> q.contains("q=tea") && q.contains("cursor") } })
    }

    @Test
    fun r1_17_aCoveredPageNeitherRestoresNorRecordsUntilItIsBack() {
        val owner = Owner()
        val api =
            object : PageApi {
                override suspend fun send(call: ApiCall): ApiResult = success(page(0 until PAGE_SIZE, "c2"))

                override fun cancelOwned(owner: CallOwnership) = Unit

                override fun epochs(): CallEpochs = CallEpochs(0, 0)
            }
        val config = SearchPageConfig(api = api, config = OnlyTaobao, texts = Texts, history = SearchHistory(), query = "milk")
        val saved = SavedStateHandle()
        val firstStore = ViewModelStore()
        var model by mutableStateOf(newModel(firstStore, config, saved))
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) { key(model) { CouliTheme { SearchScreen(model, config) } } }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(SearchTags.RESULTS).performScrollToIndex(ANCHOR)
        rule.waitForIdle()
        val anchor = saved.get<Int>(K_INDEX)
        // 被详情盖住（ON_STOP）：之后列表的位置变化不记
        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        rule.onNodeWithTag(SearchTags.RESULTS).performScrollToIndex(2)
        rule.waitForIdle()
        assertEquals("被覆盖时冻结记录", anchor, saved.get<Int>(K_INDEX))
        // 进程回收后在被覆盖状态下重建：恢复任务暂停，不滚
        rule.runOnIdle {
            firstStore.clear()
            model = newModel(ViewModelStore(), config, saved)
        }
        rule.waitForIdle()
        rule.onNodeWithTag(SearchTags.CARD_PREFIX + "tb:0").assertIsDisplayed()
        // 回到前台：继续恢复
        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        rule.waitForIdle()
        rule.onNodeWithTag(SearchTags.CARD_PREFIX + "tb:0").assertDoesNotExist()
        rule.onNodeWithTag(SearchTags.CARD_PREFIX + "tb:${anchor!! - 1}").assertIsDisplayed()
    }

    @Test
    fun r1_17_stoppingWritesTheLatestViewportSynchronouslyThenFreezes() {
        val saved = SavedStateHandle()
        val model =
            newModel(
                ViewModelStore(),
                SearchPageConfig(api = NoAnswer, config = OnlyTaobao, texts = Texts, history = SearchHistory(), query = null),
                saved,
            )
        model.onScrolled(ScrollAnchor(3, 10, 7))
        // 最新位置还没经快照流记下，就进后台：同步写入此刻视口
        model.onPageStopped(ScrollAnchor(9, 42, 14))
        assertEquals(9, saved.get<Int>(K_INDEX))
        assertEquals(42, saved.get<Int>(K_OFFSET))
        assertEquals(14, saved.get<Int>(K_LAST))
        model.onScrolled(ScrollAnchor(0, 0, 4))
        assertEquals("冻结后不再记", 9, saved.get<Int>(K_INDEX))
        model.onPageStarted()
        model.onScrolled(ScrollAnchor(1, 0, 5))
        assertEquals(1, saved.get<Int>(K_INDEX))
        assertNull(model.pendingScroll)
    }

    // ---------------------------------------------------------------- 假服务与数据

    private object NoAnswer : PageApi {
        override suspend fun send(call: ApiCall): ApiResult = CompletableDeferred<ApiResult>().await()

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
                "search.submit" to "搜索",
                "card.retry_later" to "稍后再试",
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
        const val TRACE = "0199a3b4-5c6d-7e8f-9a0b-1c2d3e000777"
        const val PAGE_SIZE = 20
        const val TARGET = 25
        const val ANCHOR = 8
        const val SWIPE_PX = 300f

        // SearchViewModel 的保存状态键（与 SearchViewModel 同名）
        const val K_INDEX = "search.scroll_index"
        const val K_OFFSET = "search.scroll_offset"
        const val K_LAST = "search.scroll_last_visible"

        fun success(data: String): ApiResult = ApiResult.Success(Json.parseToJsonElement(data), TRACE, null)

        fun card(
            i: Int,
            link: String = "link-1",
            final: Long = 2990,
            compare: Boolean = false,
        ): String {
            val rebate =
                if (compare) {
                    """"rebate_min_fen":0,"rebate_max_fen":0,"rebate_basis":"no_rebate","no_rebate_cause":"price_compare",""" +
                        """"cta":{"text_key":"btn.buy.no_rebate"},"est_net_price_fen":$final,"""
                } else {
                    """"rebate_min_fen":269,"rebate_max_fen":269,"rebate_basis":"normal","cta":{"text_key":"btn.buy.coupon"},""" +
                        """"est_net_price_fen":${final - 269},"""
                }
            return """{"card_id":"card-$i","product_key":"tb:$i","item_ref":"v1.tb.$i.sig","platform":"taobao","title":"item $i",""" +
                """"price_fen":${final + 1000},"coupon_fen":1000,"final_price_fen":$final,$rebate"age_sec":12,"link_id":"$link",""" +
                """"disclaimer_keys":["price_basis"],"availability":"ok"}"""
        }

        fun page(
            indices: IntRange,
            cursor: String?,
            compareAt: Int? = null,
        ): String {
            val items = indices.joinToString(",") { card(it, compare = it == compareAt) }
            val next = cursor?.let { "\"$it\"" } ?: "null"
            return """{"items":[$items],"next_cursor":$next,"has_more":${cursor != null},"fallback_items":[]}"""
        }
    }
}
