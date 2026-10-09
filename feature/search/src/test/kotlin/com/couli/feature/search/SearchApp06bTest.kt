package com.couli.feature.search

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewmodel.initializer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.DisclaimerKeys
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * APP-06b 在搜索结果卡上的接线与 F-AND-17（APP-06a 共性第 25、27、31 条在 Android 上的核对）：
 * - 25：纯比价区间卡（price_compare_risk）只用 rebate_compare，不追加 rebate_estimate（页底说明只按服务端 disclaimer_keys 选，不兜底补句）；
 * - 27：列表卡购买按钮按 features.platform_status 禁用，订阅配置变化后立即生效；
 * - 31：not_launched 时列表卡隐藏返利金额与预估返后，只留价格与禁用按钮；
 * - 卡片【购买】交给 App 层的购买流程（带 spm search.result_list.<坑位>）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-xxhdpi")
class SearchApp06bTest {
    @get:Rule
    val rule = createComposeRule()

    private var status: String? = "on"
    private val listeners = mutableListOf<() -> Unit>()
    private val buys = mutableListOf<Pair<JsonObject, String>>()

    private val port =
        object : SearchConfigPort {
            override fun statusLayers(): JsonObject = Json.parseToJsonElement("""{"current":{"taobao":"on"}}""").jsonObject

            override fun refresh() = Unit

            override fun issuedMark(): Long = 0

            override fun appliedMark(): Long = 0

            override fun subscribe(listener: () -> Unit): () -> Unit {
                listeners += listener
                return { listeners -= listener }
            }

            override fun platformStatus(platform: String): String? = status
        }

    private fun show() {
        val config =
            SearchPageConfig(
                api = OneCard,
                config = port,
                texts = Texts,
                history = SearchHistory(),
                query = "milk",
                actions = SearchPageActions(onBuyIntent = { card, spm -> buys += card to spm }),
            )
        rule.setContent { CouliTheme { SearchScreen(config) } }
        rule.waitForIdle()
    }

    @Test
    fun lesson25_pureCompareCardsNeverGetTheEstimateSentence() {
        val compareOnly = listOf(listOf(DisclaimerKeys.PRICE_BASIS, DisclaimerKeys.REBATE_COMPARE))
        val lines = pageDisclaimerLines(compareOnly)
        assertFalse(lines.contains(PriceTerms.REBATE_ESTIMATE))
        assertTrue(lines.contains(PriceTerms.SETTLE_BASIS))
        assertTrue("普通卡下发了才放", pageDisclaimerLines(listOf(listOf(DisclaimerKeys.REBATE_ESTIMATE))).contains(PriceTerms.REBATE_ESTIMATE))
    }

    @Test
    fun lesson27_listBuyButtonFollowsPlatformStatusAndConfigChanges() {
        show()
        rule.onNode(hasText("领券购买", substring = true)).assertIsEnabled()
        rule.onNode(hasText("领券购买", substring = true)).performClick()
        assertEquals("search.result_list.1", buys.single().second)
        status = "maintenance"
        rule.runOnIdle { listeners.toList().forEach { it() } }
        rule.waitForIdle()
        rule.onNodeWithText("稍后再试").assertIsNotEnabled()
    }

    @Test
    fun lesson31_notLaunchedHidesListRebateAmounts() {
        status = "not_launched"
        show()
        // 返利标签的位置与禁用按钮各一处
        rule.onAllNodesWithText("淘宝返利即将开放", useUnmergedTree = true).assertCountEquals(2)
        rule.onNodeWithText("¥2.69", substring = true, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("预估返后", substring = true, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("¥29.9", substring = true, useUnmergedTree = true).assertExists()
    }

    @Test
    fun lesson26_noKeywordAndAllPlatformsOffGoesStraightToUnavailable() {
        var layers = """{"current":{"taobao":"off","jd":"off"}}"""
        val subscribers = mutableListOf<() -> Unit>()
        val closable =
            object : SearchConfigPort {
                override fun statusLayers(): JsonObject = Json.parseToJsonElement(layers).jsonObject

                override fun refresh() = Unit

                override fun issuedMark(): Long = 0

                override fun appliedMark(): Long = 0

                override fun subscribe(listener: () -> Unit): () -> Unit {
                    subscribers += listener
                    return { subscribers -= listener }
                }
            }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val controller = SearchController(OneCard, closable, scope)
        controller.open(q = null, platform = null)
        assertEquals("无关键词打开、全部平台关闭：立即进入无可搜索平台", SearchPhase.UNAVAILABLE, controller.state.value.phase)
        layers = """{"current":{"taobao":"on"}}"""
        subscribers.toList().forEach { it() }
        assertEquals("平台恢复：回到输入态", SearchPhase.IDLE, controller.state.value.phase)
        layers = """{"current":{"taobao":"off"}}"""
        subscribers.toList().forEach { it() }
        assertEquals("输入态时全部关闭：立即进入无可搜索平台", SearchPhase.UNAVAILABLE, controller.state.value.phase)
        controller.leave()
    }

    @Test
    fun lesson28_restoredPageWaitsForTheUpdateCheckBeforeSearching() {
        val calls = mutableListOf<String>()
        val api =
            object : PageApi {
                override suspend fun send(call: ApiCall): ApiResult {
                    calls += call.query.orEmpty()
                    return OneCard.send(call)
                }

                override fun cancelOwned(owner: CallOwnership) = Unit

                override fun epochs(): CallEpochs = CallEpochs(0, 0)
            }
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val saved = androidx.lifecycle.SavedStateHandle()
        val first =
            androidx.lifecycle.ViewModelProvider.create(
                androidx.lifecycle.ViewModelStore(),
                androidx.lifecycle.viewmodel.viewModelFactory {
                    initializer { SearchViewModel(SearchPageConfig(api, port, Texts, SearchHistory(), query = "milk"), saved) }
                },
            )[SearchViewModel::class]
        rule.waitForIdle()
        assertEquals(1, calls.size)
        assertFalse(first.restored)
        // 进程被回收：同一份保存状态新建 ViewModel，带版本检查的等待
        val config = SearchPageConfig(api, port, Texts, SearchHistory(), query = "milk", restoreGate = { gate.await() })
        val restored =
            androidx.lifecycle.ViewModelProvider.create(
                androidx.lifecycle.ViewModelStore(),
                androidx.lifecycle.viewmodel.viewModelFactory { initializer { SearchViewModel(config, saved) } },
            )[SearchViewModel::class]
        rule.waitForIdle()
        assertTrue(restored.restored)
        assertEquals("版本检查有结果之前不重搜", 1, calls.size)
        gate.complete(Unit)
        rule.waitUntil(TIMEOUT_MS) { calls.size == 2 }
        assertTrue(calls[1].contains("q=milk"))
    }

    private object OneCard : PageApi {
        override suspend fun send(call: ApiCall): ApiResult =
            ApiResult.Success(
                Json.parseToJsonElement(
                    """{"items":[{"product_key":"tb:1","platform":"taobao","title":"t","price_fen":3990,"coupon_fen":1000,""" +
                        """"final_price_fen":2990,"est_net_price_fen":2721,"rebate_min_fen":269,""" +
                        """"rebate_max_fen":269,"rebate_basis":"normal",""" +
                        """"cta":{"text_key":"btn.buy.coupon"},"stale":false,"age_sec":1,""" +
                        """"disclaimer_keys":["price_basis","rebate_estimate"],""" +
                        """"availability":"ok"}],"next_cursor":null,"has_more":false,"fallback_items":[]}""",
                ),
                "trace-000888",
                null,
            )

        override fun cancelOwned(owner: CallOwnership) = Unit

        override fun epochs(): CallEpochs = CallEpochs(0, 0)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }

    private object Texts : SearchTexts {
        private val drafts = mapOf("platform.taobao" to "淘宝", "card.retry_later" to "稍后再试")
        private val placeholder = Regex("\\{[a-z0-9_]+\\}")

        override fun text(key: String): String = DefaultTexts.text(key) ?: drafts[key] ?: DefaultTexts.fallback(key) ?: ""

        override fun format(
            key: String,
            vars: Map<String, String>,
        ): String {
            var text = DefaultTexts.text(key) ?: drafts[key] ?: return text(key)
            vars.forEach { (name, value) -> if (value.isNotEmpty()) text = text.replace("{$name}", value) }
            return if (placeholder.containsMatchIn(text)) DefaultTexts.fallback(key) ?: text.replace(placeholder, "") else text
        }
    }
}
