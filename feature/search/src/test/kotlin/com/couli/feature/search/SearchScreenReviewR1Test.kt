package com.couli.feature.search

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.CouliAnnouncer
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.ErrorStateFocus
import com.couli.core.designsystem.LocalCouliAnnouncer
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
 * 第 1 轮评审（android-review-r1.md）的页面级测试，整页 [SearchScreen] + 本地假服务：
 * - R1-05 口径说明每屏底部都能看到（不只在无限列表最后一项）；R1-04 普通返利卡的结算说明在页底；
 * - R1-06 配置重建（StateRestorationTester 模拟 Activity 重建 / 回退栈恢复）后滚动位置不被清零；
 * - R1-07 价格区间未提交的输入在重建后仍在；
 * - R1-09 首屏失败、翻页失败：立即播报（带 trace6），读完后焦点到【重试】；42901 时【重试】保持禁用。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-xxhdpi")
class SearchScreenReviewR1Test {
    @get:Rule
    val rule = createComposeRule()

    private val spoken = mutableListOf<String>()

    private fun pageConfig(api: PageApi): SearchPageConfig =
        SearchPageConfig(api = api, config = OnlyTaobao, texts = ScreenTexts, history = SearchHistory(), query = "milk")

    private fun show(config: SearchPageConfig) {
        rule.setContent {
            CompositionLocalProvider(LocalCouliAnnouncer provides CouliAnnouncer { spoken += it }) {
                CouliTheme { SearchScreen(config) }
            }
        }
    }

    @Test
    fun r1_05_priceAndRebateDisclaimerIsVisibleOnEveryScreen() {
        show(pageConfig(ScriptedApi { success(page(0 until ITEMS)) }))
        rule.waitForIdle()
        rule.onNodeWithText(PriceTerms.PRICE_BASIS).assertIsDisplayed()
        rule.onNodeWithText(PriceTerms.REBATE_ESTIMATE).assertIsDisplayed()
        rule.onNodeWithTag(SearchTags.RESULTS).performScrollToIndex(MIDDLE)
        rule.waitForIdle()
        rule.onNodeWithTag(SearchTags.CARD_PREFIX + "tb:$MIDDLE").assertIsDisplayed()
        rule.onNodeWithText(PriceTerms.PRICE_BASIS).assertIsDisplayed()
        rule.onNodeWithText(PriceTerms.REBATE_ESTIMATE).assertIsDisplayed()
    }

    @Test
    fun r1_06_scrollPositionSurvivesRecreation() {
        val restore = StateRestorationTester(rule)
        val config = pageConfig(ScriptedApi { success(page(0 until ITEMS)) })
        restore.setContent {
            CompositionLocalProvider(LocalCouliAnnouncer provides CouliAnnouncer { spoken += it }) {
                CouliTheme { SearchScreen(config) }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(SearchTags.RESULTS).performScrollToIndex(MIDDLE)
        rule.waitForIdle()
        rule.onNodeWithTag(SearchTags.CARD_PREFIX + "tb:$MIDDLE").assertIsDisplayed()
        restore.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        rule.onNodeWithTag(SearchTags.CARD_PREFIX + "tb:$MIDDLE").assertIsDisplayed()
        rule.onNodeWithTag(SearchTags.CARD_PREFIX + "tb:0").assertDoesNotExist()
    }

    @Test
    fun r1_07_unsubmittedPriceDraftSurvivesRecreation() {
        val restore = StateRestorationTester(rule)
        val config = pageConfig(ScriptedApi { success(page(0 until 3)) })
        restore.setContent { CouliTheme { SearchScreen(config) } }
        rule.waitForIdle()
        rule.onNodeWithTag(SearchTags.PRICE).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(SearchTags.PRICE_MIN).performTextInput("10")
        rule.onNodeWithTag(SearchTags.PRICE_MAX).performTextInput("50")
        rule.waitForIdle()
        restore.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        rule.onNodeWithTag(SearchTags.PRICE_MIN).assert(hasText("10"))
        rule.onNodeWithTag(SearchTags.PRICE_MAX).assert(hasText("50"))
    }

    @Test
    fun r1_09_firstScreenErrorIsAnnouncedThenRetryGetsFocus() {
        rule.mainClock.autoAdvance = false
        show(pageConfig(ScriptedApi { failure(50401) }))
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag(SearchTags.RETRY).assertIsNotFocused()
        rule.mainClock.advanceTimeBy(ErrorStateFocus.MAX_MILLIS)
        rule.onNodeWithTag(SearchTags.RETRY).assertIsFocused()
        assertEquals(listOf("出了点问题，请稍后再试（000123）"), spoken)
    }

    @Test
    fun r1_09_loadMoreErrorIsAnnouncedThenRetryGetsFocus() {
        rule.mainClock.autoAdvance = false
        show(
            pageConfig(
                ScriptedApi { call ->
                    if (call.query.orEmpty().contains("cursor=")) failure(50401) else success(page(0 until 3, "c2"))
                },
            ),
        )
        repeat(FRAMES) { rule.mainClock.advanceTimeByFrame() }
        rule.onNodeWithTag(SearchTags.LOAD_MORE_ERROR).assertExists()
        rule.mainClock.advanceTimeBy(ErrorStateFocus.MAX_MILLIS)
        rule.onNodeWithTag(SearchTags.RETRY).assertIsFocused()
        assertTrue(spoken.contains("出了点问题，请稍后再试（000123）"))
    }

    @Test
    fun r1_09_rateLimitedErrorIsAnnouncedAndRetryStaysDisabled() {
        rule.mainClock.autoAdvance = false
        show(pageConfig(ScriptedApi { failure(42901, disableMillis = 60_000) }))
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(ErrorStateFocus.MAX_MILLIS)
        rule.onNodeWithTag(SearchTags.RETRY).assertIsNotEnabled()
        assertEquals(listOf("操作太频繁，请稍后再试（000123）"), spoken)
    }

    // ---- 本地假服务

    /** 每个请求立即按 [answer] 回答（不挂起）。 */
    private class ScriptedApi(
        private val answer: (ApiCall) -> ApiResult,
    ) : PageApi {
        override suspend fun send(call: ApiCall): ApiResult = answer(call)

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

    /** 契约字典 + 本测试用到的几个草稿键（平台名、按钮名）。 */
    private object ScreenTexts : SearchTexts {
        private val drafts =
            mapOf(
                "platform.taobao" to "淘宝",
                "external_page.retry" to "重试",
                "search.filter.price" to "价格区间",
                "search.filter.sheet.min" to "最低价",
                "search.filter.sheet.max" to "最高价",
                "search.filter.sheet.confirm" to "确定",
                "search.filter.sheet.reset" to "重置",
            )
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

    private companion object {
        const val TRACE = "0199a3b4-5c6d-7e8f-9a0b-1c2d3e000123"
        const val ITEMS = 20
        const val MIDDLE = 12
        const val FRAMES = 30

        fun success(data: String): ApiResult = ApiResult.Success(Json.parseToJsonElement(data), TRACE, null)

        fun failure(
            code: Int,
            disableMillis: Long? = null,
        ): ApiResult {
            val kind = if (disableMillis != null) ErrorEffectKind.DISABLE_BUTTON else ErrorEffectKind.GENERIC_ERROR
            val effect = ErrorEffect(code, kind, ReplayMode.NONE, registerPending = false, disableMillis = disableMillis)
            return ApiResult.Failure(code, "", JsonObject(emptyMap()), TRACE, effect, null)
        }

        fun page(
            indices: IntRange,
            cursor: String? = null,
        ): String {
            val items =
                indices.joinToString(",") { i ->
                    """{"product_key":"tb:$i","platform":"taobao","title":"item $i","price_fen":3990,"coupon_fen":1000,""" +
                        """"final_price_fen":2990,"rebate_min_fen":269,"rebate_max_fen":269,"rebate_basis":"normal",""" +
                        """"est_net_price_fen":2721,"disclaimer_keys":["price_basis","rebate_estimate"],"age_sec":12,""" +
                        """"cta":{"text_key":"btn.buy.coupon"},"availability":"ok"}"""
                }
            val next = cursor?.let { "\"$it\"" } ?: "null"
            return """{"items":[$items],"next_cursor":$next,"has_more":${cursor != null},"fallback_items":[]}"""
        }
    }
}
