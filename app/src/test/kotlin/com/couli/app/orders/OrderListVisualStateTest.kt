package com.couli.app.orders

import android.app.Application
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.assertNoTextOverflow
import com.couli.app.product.textLayout
import com.couli.feature.orders.OrderTags
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1000dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OrderListVisualStateTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { OrderListVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun emptySelfHas96IconCompactHomeActionAndHeading() = empty("self", "还没有自购订单")

    @Test fun emptyShareHas96IconCompactHomeActionAndHeading() = empty("share", "还没有分享订单")

    @Test fun searchSubmitAndClearUseExistingQueryAndCountIs12SpCentered() {
        host.launch(OrderListVisualFixtures.page("search_self"))
        rule.onNodeWithTag(OrderTags.SEARCH).performTextInput("纯牛奶")
        assertEquals(1, host.api.calls.size)
        rule.onNodeWithTag(OrderTags.SEARCH).performImeAction()
        val count = rule.onNodeWithTag(OrderTags.SEARCH_FOUND).assertIsDisplayed()
        assertEquals(
            12f,
            count
                .textLayout()
                .layoutInput.style.fontSize.value,
            .01f,
        )
        assertEquals(LiveRegionMode.Polite, count.fetchSemanticsNode().config[SemanticsProperties.LiveRegion])
        val page = rule.onNodeWithTag(OrderTags.LIST_PAGE).fetchSemanticsNode()
        assertEquals(
            page.boundsInRoot.center.x,
            count
                .fetchSemanticsNode()
                .boundsInRoot.center.x,
            2f,
        )
        assertEquals("纯牛奶", host.model.controller.state.value.conditions.q)
        rule.onNodeWithTag(OrderTags.SEARCH_CLEAR).performClick()
        assertEquals(null, host.model.controller.state.value.conditions.q)
        assertEquals(3, host.api.calls.size)
    }

    @Test fun shareSearchEmptyShowsScopeNoteAndNoHomeAction() {
        host.launch(OrderListVisualFixtures.page("search_share_empty"), scope = "share")
        rule.onNodeWithTag(OrderTags.SEARCH).performTextInput("不存在")
        rule.onNodeWithTag(OrderTags.SEARCH).performImeAction()
        rule.onNodeWithTag(OrderTags.SEARCH_EMPTY).assertIsDisplayed()
        rule.onNodeWithText("分享订单只按商品名搜索", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("去首页逛逛").assertDoesNotExist()
        assertEquals(2, host.api.calls.size)
    }

    @Test fun firstPageErrorKeepsTraceAndRetryFetchesOnceWithoutFakeEmptyState() {
        host.api.fail()
        host.launch()
        rule.onNodeWithTag(OrderTags.ERROR).assertIsDisplayed()
        rule.onNodeWithTag(OrderTags.EMPTY).assertDoesNotExist()
        rule.onNodeWithText("abc123", substring = true, useUnmergedTree = true).assertIsDisplayed()
        host.api.success(OrderListVisualFixtures.one())
        rule.onNode(hasText("重试") and hasClickAction()).performClick()
        rule.onNodeWithTag(OrderTags.CARD_PREFIX + "A").assertIsDisplayed()
        rule.onNodeWithTag(OrderTags.ERROR).assertDoesNotExist()
        assertEquals(2, host.api.calls.size)
    }

    @Test fun paginationFailureKeepsExistingCardsAndRetriesExactCursorOnce() {
        host.api.nextAnswer = OrderListVisualApi.failure()
        host.api.nextHold = null
        host.launch()
        rule.onNodeWithTag(OrderTags.LIST).performScrollToIndex(4)
        rule.onNodeWithTag(OrderTags.RETRY).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(OrderTags.LOAD_MORE_ERROR).assertIsDisplayed()
        assertEquals(
            listOf("A", "B", "M", "C", "J"),
            host.model.controller.state.value.items
                .map { it.orderId },
        )
        val failedQuery =
            host.api.calls
                .last()
                .query
        host.api.nextAnswer =
            com.couli.core.network.ApiResult
                .Success(OrderListVisualFixtures.page("next_page"), null, null)
        val before = host.api.calls.size
        rule.onNodeWithTag(OrderTags.RETRY).performClick()
        assertEquals(before + 1, host.api.calls.size)
        assertEquals(
            failedQuery,
            host.api.calls
                .last()
                .query,
        )
        assertEquals(
            listOf("A", "B", "M", "C", "J", "C_PAGE_2"),
            host.model.controller.state.value.items
                .map { it.orderId },
        )
        assertTrue(host.model.controller.state.value.endReached)
        assertTrue(host.opened.isEmpty())
    }

    @Test fun staleBannerReadsCachedTimePolitelyAndRefreshClearsIt() {
        host.stale(OrderListVisualFixtures.one())
        host.launch(OrderListVisualFixtures.one())
        val banner = rule.onNodeWithTag(OrderTags.STALE).assertIsDisplayed().fetchSemanticsNode()
        assertEquals(LiveRegionMode.Polite, banner.config[SemanticsProperties.LiveRegion])
        rule.onNodeWithText("09:30", substring = true, useUnmergedTree = true).assertIsDisplayed()
        host.refresh(OrderListVisualFixtures.one())
        rule.onNodeWithTag(OrderTags.STALE).assertDoesNotExist()
        rule.onNodeWithTag(OrderTags.CARD_PREFIX + "A").assertIsDisplayed()
        assertEquals(2, host.api.calls.size)
    }

    @Test fun historyHintStaysReadableAtEndAndDoesNotGenerateAnotherPage() {
        host.launch(OrderListVisualFixtures.page("history"))
        rule.onNodeWithTag(OrderTags.LIST).performScrollToIndex(6)
        val hint = rule.onNodeWithTag(OrderTags.HISTORY_HINT).assertIsDisplayed()
        hint.assertNoTextOverflow()
        assertEquals(
            12f,
            hint
                .textLayout()
                .layoutInput.style.fontSize.value,
            .01f,
        )
        assertEquals(1, host.api.calls.size)
    }

    private fun empty(
        scope: String,
        title: String,
    ) {
        host.launch(OrderListVisualFixtures.page("list_${scope}_empty"), scope = scope)
        rule.onNodeWithTag(OrderTags.EMPTY).assertIsDisplayed()
        val heading = rule.onNodeWithText(title, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode()
        assertTrue(heading.config.contains(SemanticsProperties.Heading))
        val action = rule.onNodeWithText("去首页逛逛").fetchSemanticsNode()
        action.assertTouch48()
        val empty = rule.onNodeWithTag(OrderTags.EMPTY).fetchSemanticsNode()
        assertTrue("空态按钮不是通栏", action.boundsInRoot.width < empty.boundsInRoot.width * .8f)
        rule.withOrderPixels(empty) { bitmap ->
            val d = empty.layoutInfo.density.density
            val blocks = orderSkeletonBlocks(bitmap, empty.boundsInRoot, host.muted.toArgb(), d.toInt())
            assertTrue("96dp 图标底块", blocks.any { kotlin.math.abs(it.width / d - 96) < 2 && kotlin.math.abs(it.height / d - 96) < 2 })
        }
        rule.onNodeWithText("去首页逛逛").performClick()
        assertEquals(listOf("Home" to JsonObject(emptyMap())), host.opened)
        assertFalse(host.copies.isNotEmpty())
        assertEquals(1, host.api.calls.size)
    }
}
