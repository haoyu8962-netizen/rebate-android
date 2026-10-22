package com.couli.app.orders

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.textLayout
import com.couli.feature.orders.OrderTags
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** O1/O2 + G-O04：完整 pinned × scope × stale，真实点击出口，不新增请求。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OrderListVisualClaimTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { OrderListVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun selfFreshTrue() = visibility(true, "self", false, true)

    @Test fun selfFreshFalse() = visibility(false, "self", false, false)

    @Test fun shareFreshTrue() = visibility(true, "share", false, false)

    @Test fun shareFreshFalse() = visibility(false, "share", false, false)

    @Test fun selfStaleTrue() = visibility(true, "self", true, false)

    @Test fun selfStaleFalse() = visibility(false, "self", true, false)

    @Test fun shareStaleTrue() = visibility(true, "share", true, false)

    @Test fun shareStaleFalse() = visibility(false, "share", true, false)

    @Test fun pinnedWholeRowIsBetweenTitleAndTabsAndStaysOutsideScrollingList() {
        host.launch(OrderListVisualFixtures.page("list_self_pinned"))
        val entry = rule.onNodeWithTag(FIND_PINNED).assertIsDisplayed().fetchSemanticsNode()
        val title = rule.onNodeWithText("订单", useUnmergedTree = true).fetchSemanticsNode()
        val tab = rule.onNodeWithTag(OrderTags.TAB_SELF).fetchSemanticsNode()
        val density = entry.layoutInfo.density.density
        assertEquals(Role.Button, entry.config[SemanticsProperties.Role])
        entry.assertTouch48()
        assertTrue(entry.boundsInRoot.top >= title.boundsInRoot.bottom)
        assertTrue(entry.boundsInRoot.bottom <= tab.boundsInRoot.top)
        assertEquals(358f, entry.boundsInRoot.width / density, 2f)
        val label =
            rule
                .onNodeWithText("未跟单？去找回", useUnmergedTree = true)
                .textLayout()
                .layoutInput.style
        assertEquals(17f, label.fontSize.value, .01f)
        assertEquals(600, label.fontWeight?.weight)
        val before = entry.boundsInRoot
        rule.onNodeWithTag(OrderTags.LIST).performScrollToIndex(4)
        assertEquals(before, rule.onNodeWithTag(FIND_PINNED).fetchSemanticsNode().boundsInRoot)
    }

    @Test fun refreshingFalseRemovesPinnedAndRestoresSingleTopRightEntry() {
        host.launch(OrderListVisualFixtures.pinned(OrderListVisualFixtures.one(), true))
        rule.onNodeWithTag(FIND_PINNED).assertIsDisplayed()
        rule.onNodeWithTag(OrderTags.FIND_ENTRY).assertDoesNotExist()
        host.refresh(OrderListVisualFixtures.one())
        rule.onNodeWithTag(FIND_PINNED).assertDoesNotExist()
        rule.onNodeWithTag(OrderTags.FIND_ENTRY).assertIsDisplayed()
        assertEquals(2, host.api.calls.size)
    }

    @Test fun malformedSuccessfulRefreshClearsPinnedButKeepsItemsAndPreviousCursor() {
        val page = OrderListVisualFixtures.page("list_self_pinned")
        host.launch(page)
        rule.onNodeWithTag(FIND_PINNED).assertIsDisplayed()
        rule.onNodeWithTag(OrderTags.FIND_ENTRY).assertDoesNotExist()
        val items =
            host.model.controller.state.value.items
                .toList()
        assertEquals(5, items.size)
        assertEquals(1, host.api.calls.size)

        // ApiResult.Success 的载荷就是 data；{} 不是网络失败，也不是可解析空列表。
        host.refresh(JsonObject(emptyMap()))
        assertEquals("坏响应不能丢掉原列表", items, host.model.controller.state.value.items)
        assertPreviousOrNewCursor(page.getValue("next_cursor").jsonPrimitive.content)
        rule.onNodeWithTag(FIND_PINNED).assertDoesNotExist()
        rule.onNodeWithTag(OrderTags.FIND_ENTRY).assertIsDisplayed()
    }

    @Test fun parseableSuccessfulRefreshStillReplacesItemsCursorAndKeepsRequestedPinned() {
        host.launch(OrderListVisualFixtures.page("list_self_pinned"))
        rule.onNodeWithTag(FIND_PINNED).assertIsDisplayed()
        val page =
            JsonObject(
                OrderListVisualFixtures.pinned(OrderListVisualFixtures.one("B"), true) +
                    ("next_cursor" to JsonPrimitive("visual-refresh-control")),
            )
        host.refresh(page)
        assertEquals(
            listOf("B"),
            host.model.controller.state.value.items
                .map { it.orderId },
        )
        rule.onNodeWithTag(FIND_PINNED).assertIsDisplayed()
        rule.onNodeWithTag(OrderTags.FIND_ENTRY).assertDoesNotExist()
        assertPreviousOrNewCursor("visual-refresh-control")
    }

    private fun assertPreviousOrNewCursor(cursor: String) {
        // 通过下一页真实请求验游标，不读取控制器的私有 nextCursor。
        rule.runOnIdle { host.model.controller.loadMore() }
        rule.waitForIdle()
        assertEquals(3, host.api.calls.size)
        assertEquals(listOf("scope=self", "scope=self", "scope=self&cursor=$cursor"), host.api.calls.map { it.query })
        assertTrue(host.api.calls.all { it.operationId == "listOrders" })
    }

    private fun visibility(
        pinned: Boolean,
        scope: String,
        stale: Boolean,
        expected: Boolean,
    ) {
        val page = OrderListVisualFixtures.pinned(OrderListVisualFixtures.one(if (scope == "share") "E" else "A"), pinned)
        if (stale) host.stale(page, scope)
        host.launch(page, scope = scope)
        val shown = if (expected) FIND_PINNED else OrderTags.FIND_ENTRY
        rule.onNodeWithTag(if (expected) OrderTags.FIND_ENTRY else FIND_PINNED).assertDoesNotExist()
        val entry = rule.onNodeWithTag(shown).assertIsDisplayed().fetchSemanticsNode()
        entry.assertTouch48()
        val requests = host.api.calls.toList()
        assertEquals("listOrders", requests.single().operationId)
        assertEquals("scope=$scope", requests.single().query)
        if (expected) {
            val bounds = entry.touchBoundsInRoot
            val inset = 2 * entry.layoutInfo.density.density
            // 左右两端真触摸，证明整行可点，而非只链接文字可点。
            rule.touch(Offset(bounds.left + inset, bounds.center.y))
            rule.touch(Offset(bounds.right - inset, bounds.center.y))
        } else {
            rule.onNodeWithTag(shown).performClick()
        }
        assertEquals(List(if (expected) 2 else 1) { "FindOrder" to kotlinx.serialization.json.JsonObject(emptyMap()) }, host.opened)
        assertEquals("找回入口不能触发新接口", requests, host.api.calls)
        assertTrue(host.copies.isEmpty())
    }
}
