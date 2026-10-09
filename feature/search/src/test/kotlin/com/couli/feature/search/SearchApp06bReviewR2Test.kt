package com.couli.feature.search

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 第 2 轮评审 R2-03、R2-04 在搜索页上的回归测试（整页 [SearchScreen] + 本地假服务）：
 * - R2-03：恢复任务用「实际位置偏离预期」的通用判定让位：读屏的滚动动作（ScrollBy、ScrollToIndex，不经拖动事件）、键盘移动焦点带来的滚动都
 *   取消恢复，并按用户的位置记录；判定本身（有别人的滚动在进行 = 惯性 / 动画滚动、项数变化重取基准、容差）单测；
 * - R2-04：页面出栈的同步出口（App 经 [SearchPageConfig.bindLeave] 登记的那个回调）在组合还在、生命周期仍为 RESUMED 时触发：恢复任务作废
 *   （之后数据到齐也不滚）、待恢复锚点清除、位置记录冻结，迟到的滚动与 ON_STOP 回调都不再写保存状态。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-xxhdpi")
class SearchApp06bReviewR2Test {
    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<ApiCall>()

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

    private class Restored(
        val saved: SavedStateHandle,
        val model: () -> SearchViewModel,
        val leave: () -> Unit,
        val owner: Owner,
    )

    /**
     * 先在第一个 ViewModel 上滚到第二页的 [TARGET]，再销毁它、用同一份保存状态新建（进程回收后的恢复；第二页由 [gate] 控制何时回答）。
     * 新 ViewModel 的出栈出口由 [SearchPageConfig.bindLeave] 交出来（与 App 的回退栈同步出口同一个回调）。
     */
    private fun restoredOnPageTwo(gate: CompletableDeferred<Unit>): Restored {
        var gated = false
        var leave: () -> Unit = {}
        val owner = Owner()
        val api =
            object : PageApi {
                override suspend fun send(call: ApiCall): ApiResult {
                    calls += call
                    val second = call.query.orEmpty().contains("cursor=c2")
                    if (second && gated && !gate.isCompleted) gate.await()
                    return success(if (second) page(PAGE_SIZE until 2 * PAGE_SIZE, null) else page(0 until PAGE_SIZE, "c2"))
                }

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
                bindLeave = { leave = it },
            )
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
        // 恢复正在等第二页
        assertTrue("恢复任务在等第二页", model.pendingScroll != null)
        rule.onNodeWithTag(SearchTags.CARD_PREFIX + "tb:0").assertIsDisplayed()
        return Restored(saved, { model }, { leave() }, owner)
    }

    /** 第二页到达之后：没有被抢回旧位置（[TARGET] 不在视口），记录的是用户此刻的位置（不是保存的旧锚点）。 */
    private fun assertNotPulledBack(
        restored: Restored,
        gate: CompletableDeferred<Unit>,
    ) {
        rule.waitForIdle()
        assertNull("恢复任务已让位", restored.model().pendingScroll)
        val recorded = restored.saved.get<Int>(K_INDEX)
        assertTrue("按用户此刻的位置记录（$recorded）", recorded != null && recorded < PAGE_SIZE)
        gate.complete(Unit)
        rule.waitForIdle()
        rule.onNodeWithTag(SearchTags.CARD_PREFIX + "tb:$TARGET").assertDoesNotExist()
    }

    // ---------------------------------------------------------------- R2-03

    @Test
    fun r2_03_aScreenReaderScrollByCancelsThePendingRestore() {
        val gate = CompletableDeferred<Unit>()
        val restored = restoredOnPageTwo(gate)
        // 读屏的「向下滚动」：语义动作 ScrollBy（动画滚动，不经拖动事件）
        rule.onNodeWithTag(SearchTags.RESULTS).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, SCROLL_PX) }
        assertNotPulledBack(restored, gate)
    }

    @Test
    fun r2_03_anAccessibilityScrollToIndexCancelsThePendingRestore() {
        val gate = CompletableDeferred<Unit>()
        val restored = restoredOnPageTwo(gate)
        // 辅助功能 / 读屏把某一项滚进视口（ScrollToIndex，不经拖动事件）
        rule.onNodeWithTag(SearchTags.RESULTS).performScrollToIndex(USER_INDEX)
        assertNotPulledBack(restored, gate)
        assertEquals("记下用户滚到的位置", USER_INDEX, restored.saved.get<Int>(K_INDEX))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun r2_03_aKeyboardFocusMoveCancelsThePendingRestore() {
        val gate = CompletableDeferred<Unit>()
        val restored = restoredOnPageTwo(gate)
        // 键盘：焦点放到第一张卡，连按 ↓，焦点移动把下面的卡带进视口（列表随之滚动，没有拖动事件）
        rule.onNodeWithTag(SearchTags.CARD_PREFIX + "tb:0").performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
        repeat(KEY_PRESSES) {
            rule.onNodeWithTag(SearchTags.RESULTS).performKeyInput { pressKey(Key.DirectionDown) }
            rule.waitForIdle()
        }
        assertFalse("键盘移动焦点后列表确实离开了顶部", isAtTop())
        assertNotPulledBack(restored, gate)
    }

    private fun isAtTop(): Boolean =
        runCatching {
            rule.onNodeWithTag(SearchTags.CARD_PREFIX + "tb:0").assertIsDisplayed()
            true
        }.getOrDefault(false)

    @Test
    fun r2_03_theDriftRuleCoversForeignScrollsLayoutRebasesAndTolerance() {
        val start = ListPosition(index = 0, offset = 0, count = 2, scrolling = false)
        val e = RestoreExpectation(start)
        assertFalse("没动", e.drifted(start))
        // 首屏到达（项数变）：内容造成的位置变化，重取基准不算偏离
        assertFalse(e.drifted(ListPosition(1, 0, 22, scrolling = false)))
        assertFalse("基准已换", e.drifted(ListPosition(1, 0, 22, scrolling = false)))
        assertFalse("容差内", e.drifted(ListPosition(1, 1, 22, scrolling = false)))
        assertTrue("项数没变而位置偏离（读屏 / 键盘移动后停下）", e.drifted(ListPosition(1, 40, 22, scrolling = false)))
        // 有不属于任务的滚动在进行（惯性、动画滚动、读屏滚动动作进行中）：即使位置还没变也算
        val f = RestoreExpectation(start)
        assertTrue(f.drifted(start.copy(scrolling = true)))
        // 任务自己移动：期间不判，之后按实际落点记预期
        val g = RestoreExpectation(start)
        runBlocking {
            g.move(
                move = { assertFalse(g.drifted(start.copy(index = 9, scrolling = true))) },
                landed = { start.copy(index = 9, offset = 12) },
            )
        }
        assertFalse(g.drifted(start.copy(index = 9, offset = 12)))
        assertTrue(g.drifted(start.copy(index = 3, offset = 12)))
    }

    // ---------------------------------------------------------------- R2-04

    @Test
    fun r2_04_aSynchronousExitBeforeTheCompositionGoesAwayStopsTheRestoreAndFreezesWrites() {
        val gate = CompletableDeferred<Unit>()
        val restored = restoredOnPageTwo(gate)
        val before = restored.saved.get<Int>(K_INDEX)
        val keyword = restored.saved.get<String>(K_INPUT)
        // 页面经回退栈同步出口出栈：组合还在（退出动画期间），生命周期仍为 RESUMED
        rule.runOnIdle { restored.leave() }
        assertFalse("页面实例已作废", restored.model().alive)
        assertNull("待恢复锚点已清除", restored.model().pendingScroll)
        assertEquals(Lifecycle.State.RESUMED, restored.owner.registry.currentState)
        // 数据之后才到齐：不滚（执行器也已作废，不再翻页）
        gate.complete(Unit)
        rule.waitForIdle()
        rule.onNodeWithTag(SearchTags.CARD_PREFIX + "tb:$TARGET").assertDoesNotExist()
        // 退出动画期间迟到的滚动、ON_STOP：都不写已出栈页面的保存状态
        rule.onNodeWithTag(SearchTags.RESULTS).performScrollToIndex(USER_INDEX)
        rule.waitForIdle()
        rule.runOnIdle { restored.owner.registry.currentState = Lifecycle.State.CREATED }
        rule.runOnIdle { restored.model().onPageStarted() }
        rule.runOnIdle { restored.model().onScrolled(ScrollAnchor(3, 0, 6)) }
        assertEquals("位置记录冻结", before, restored.saved.get<Int>(K_INDEX))
        assertNotEquals(USER_INDEX, restored.saved.get<Int>(K_INDEX))
        assertEquals("其它持久化入口也不写", keyword, restored.saved.get<String>(K_INPUT))
        rule.runOnIdle { restored.model().onInput("tea") }
        assertEquals(keyword, restored.saved.get<String>(K_INPUT))
    }

    // ---------------------------------------------------------------- 假服务与数据

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
        const val TRACE = "0199a3b4-5c6d-7e8f-9a0b-1c2d3e000778"
        const val PAGE_SIZE = 20
        const val TARGET = 25
        const val USER_INDEX = 5
        const val SCROLL_PX = 600f
        const val KEY_PRESSES = 8

        // SearchViewModel 的保存状态键（与 SearchViewModel 同名）
        const val K_INDEX = "search.scroll_index"
        const val K_INPUT = "search.input"

        fun success(data: String): ApiResult = ApiResult.Success(Json.parseToJsonElement(data), TRACE, null)

        fun card(i: Int): String =
            """{"card_id":"card-$i","product_key":"tb:$i","item_ref":"v1.tb.$i.sig","platform":"taobao","title":"item $i",""" +
                """"price_fen":3990,"coupon_fen":1000,"final_price_fen":2990,"rebate_min_fen":269,"rebate_max_fen":269,""" +
                """"rebate_basis":"normal","cta":{"text_key":"btn.buy.coupon"},"est_net_price_fen":2721,"age_sec":12,""" +
                """"link_id":"link-$i",""" +
                """"disclaimer_keys":["price_basis"],"availability":"ok"}"""

        fun page(
            indices: IntRange,
            cursor: String?,
        ): String {
            val items = indices.joinToString(",") { card(it) }
            val next = cursor?.let { "\"$it\"" } ?: "null"
            return """{"items":[$items],"next_cursor":$next,"has_more":${cursor != null},"fallback_items":[]}"""
        }
    }
}
