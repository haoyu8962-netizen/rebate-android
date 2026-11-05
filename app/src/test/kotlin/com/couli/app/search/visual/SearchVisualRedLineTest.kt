package com.couli.app.search.visual

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText as textMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.network.percentEncode
import com.couli.feature.search.SearchTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h956dp-xhdpi")
class SearchVisualRedLineTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { SearchVisualHost(rule) }

    @After fun close() = host.close()

    @Test fun redLine1QueryEntrySendsExactlyOneSearch() {
        host.launch("category")
        assertEquals(listOf("searchProducts"), host.api.calls.map { it.operationId })
        assertEquals(
            "q=${percentEncode("食品生鲜")}&platform=taobao&sort=relevance",
            host.api.calls
                .single()
                .query,
        )
    }

    @Test fun redLine1IdleEntryFocusesInputWithoutRequest() {
        host.launch("idle")
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag(SearchTags.INPUT).assertIsFocused()
        assertEquals(0, host.api.calls.size)
    }

    @Test fun redLine2InputClearPriceDraftResetDismissAndInfoDoNotRequest() {
        host.launch()
        rule.onNodeWithTag(SearchTags.INPUT).performTextInput("样例")
        rule.onNodeWithTag(SearchTags.CLEAR).performClick()
        host.openPrice()
        rule.onNodeWithTag(SearchTags.PRICE_RESET).performClick()
        host.back()
        host.openInfo()
        rule.onNode(textMatcher("知道了") and hasClickAction()).performClick()
        rule.waitForIdle()
        assertEquals(1, host.api.calls.size)
    }

    @Test fun redLine3SubmitKeywordOnceAndClearHistoryNeverSearches() {
        host.launch("history")
        rule.onNodeWithTag(SearchTags.HISTORY_CLEAR).performClick()
        assertEquals(0, host.api.calls.size)
        rule.onNodeWithTag(SearchTags.INPUT).performTextInput("样例商品")
        rule.onNodeWithTag(SearchTags.SUBMIT).performClick()
        assertEquals(listOf("searchProducts"), host.api.calls.map { it.operationId })
        assertTrue(
            host.api.calls
                .single()
                .query
                .orEmpty()
                .startsWith("q=" + percentEncode("样例商品")),
        )
    }

    @Test fun redLine3HistoryIsOneSubmission() {
        host.launch("history")
        rule.onNodeWithTag(SearchTags.HISTORY_PREFIX + "抽纸").performClick()
        assertEquals(listOf("searchProducts"), host.api.calls.map { it.operationId })
        assertTrue(
            host.api.calls
                .single()
                .query
                .orEmpty()
                .startsWith("q=" + percentEncode("抽纸")),
        )
    }

    @Test fun redLine4TabSortCouponAndPriceEachSendOnceInOriginalOrder() {
        host.launch()
        rule.onNodeWithTag(SearchTags.TAB_PREFIX + "pdd").performClick()
        rule.onNodeWithTag(SearchTags.SORT_PREFIX + "sales_desc").performClick()
        rule.onNodeWithTag(SearchTags.COUPON).performClick()
        host.openPrice()
        assertEquals(4, host.api.calls.size)
        rule.onNodeWithTag(SearchTags.PRICE_CONFIRM).performClick()
        val q = "q=" + percentEncode("纯牛奶")
        assertEquals(
            listOf(
                "$q&platform=taobao&sort=relevance",
                "$q&platform=pdd&sort=relevance",
                "$q&platform=pdd&sort=sales_desc",
                "$q&platform=pdd&sort=sales_desc&has_coupon=true",
                "$q&platform=pdd&sort=sales_desc&has_coupon=true&price_min_fen=1000&price_max_fen=2000",
            ),
            host.api.calls.map { it.query },
        )
        assertEquals(List(5) { "searchProducts" }, host.api.calls.map { it.operationId })
    }

    @Test fun redLine5PageRetrySendsExactlyOnce() {
        host.launch("error")
        host.api.answer = { SearchVisualApi.success(SearchVisualFixtures.page()) }
        rule.onNodeWithTag(SearchTags.RETRY).performClick()
        assertEquals(2, host.api.calls.size)
        assertEquals(
            host.api.calls
                .first()
                .query,
            host.api.calls
                .last()
                .query,
        )
    }

    @Test fun redLine5PagingRetryKeepsCursorAndOriginalResults() {
        host.launch("paging_error")
        val failed =
            host.api.calls
                .last()
                .query
        assertEquals(2, host.api.calls.size)
        host.api.answer = { SearchVisualApi.success(SearchVisualFixtures.page(emptyList())) }
        rule.onNodeWithTag(SearchTags.RETRY).performClick()
        assertEquals(3, host.api.calls.size)
        assertEquals(
            failed,
            host.api.calls
                .last()
                .query,
        )
        rule.onNodeWithTag(SearchTags.RESULTS).performScrollToIndex(1)
        rule.onNodeWithTag(SearchTags.CARD_PREFIX + "taobao:sample-tb_single").assertIsDisplayed()
    }

    @Test fun redLine6CardAndBuyHaveSeparateExactDestinations() {
        host.launch("long_title")
        val raw = SearchVisualFixtures.card("long_title")
        rule.onNodeWithTag(SearchTags.CARD_PREFIX + "taobao:sample-long_title").performClick()
        assertEquals(listOf(Triple("taobao", "taobao:sample-long_title", "sample-ref-long_title")), host.opened)
        assertEquals(0, host.bought.size)
        rule.onNode(textMatcher("领券购买") and hasClickAction()).performClick()
        assertEquals(listOf(raw to "search.result_list.1"), host.bought)
        assertEquals(1, host.opened.size)
        assertEquals(1, host.api.calls.size)
    }

    @Test fun redLine6SimilarUsesSameRawCardOnce() {
        host.launch("compare")
        rule.onNodeWithText("看看相似商品").performClick()
        assertEquals(listOf(SearchVisualFixtures.card("compare")), host.similar)
        assertEquals(1, host.api.calls.size)
    }

    @Test fun redLine8DisabledPasteAndBackDoNotSearch() {
        host.launch("disabled")
        rule.onNodeWithText("粘贴链接查返利").performClick()
        rule.onNodeWithContentDescription("返回").performClick()
        assertEquals(1, host.pastes)
        assertEquals(1, host.backs)
        assertEquals(0, host.api.calls.size)
        rule.onNodeWithTag(SearchTags.RETRY).assertDoesNotExist()
    }

    @Test fun redLine10IdleClickableSetIsOnlyExistingHeaderPasteAndHistory() {
        host.launch("history")
        // 输入框本身也有语义 OnClick；四个历史、清空、返回、搜索、粘贴，加输入框共九个。
        assertEquals(9, rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size)
        rule.onNodeWithTag(SearchTags.COUPON).assertDoesNotExist()
        rule.onNodeWithTag(SearchTags.PRICE).assertDoesNotExist()
    }
}
