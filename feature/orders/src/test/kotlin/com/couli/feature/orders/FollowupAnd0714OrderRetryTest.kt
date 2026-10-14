package com.couli.feature.orders

import android.app.Application
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.CouliTheme
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.PageApi
import com.couli.core.orders.OrderDetail
import com.couli.core.orders.OrderDraftTexts
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * FU-AND-0714 第 1 轮评审 R1-03（F-AND-28）：订单列表构造时账号读不出（已登录、account 为 null）→ 可恢复的错误态；同一登录世代里账号恢复后，
 * **真实页面**的【重试】按钮与下拉刷新（页面根节点上的 [OrderListActions]）能重建页面身份：先清空、换新的渲染身份，再按新账号取第一页。
 * 旧账号的回调（渲染身份带别的账号）、登录世代已变的回调照旧拒绝。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class FollowupAnd0714OrderRetryTest {
    @get:Rule
    val composeRule = createComposeRule()

    private class Session : OrderEnv {
        var epoch = 1L
        var user: String? = null

        override fun loggedIn() = true

        override fun sessionEpoch() = epoch

        override fun account() = user

        override fun helpLinks(): JsonObject? = null

        override fun copy(text: String) = true

        override fun open(
            route: String,
            params: JsonObject,
        ) = Unit

        override fun forceUpdate() = Unit

        override fun onDetailLoaded(detail: OrderDetail) = Unit
    }

    private class Sends : PageApi {
        val calls = mutableListOf<ApiCall>()

        override suspend fun send(call: ApiCall): ApiResult {
            calls += call
            return ApiResult.Success(Json.parseToJsonElement(EMPTY_PAGE), "trace-000123", null)
        }

        override fun cancelOwned(owner: CallOwnership) = Unit

        override fun epochs() = CallEpochs(0, 0)
    }

    private val session = Session()
    private val sends = Sends()

    private fun launch() {
        val config = OrderListConfig(sends, session, OrderDraftTexts, cache = null, mask = null, pending = null)
        composeRule.setContent { CouliTheme { OrderListScreen(config) } }
        settle()
    }

    private fun settle() {
        repeat(ROUNDS) {
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.waitForIdle()
        }
    }

    private fun actions(): OrderListActions =
        composeRule
            .onNodeWithTag(OrderTags.LIST_PAGE)
            .fetchSemanticsNode()
            .config[OrderListActions.Key]

    private fun showsError(): Boolean = composeRule.onAllNodesWithTag(OrderTags.ERROR).fetchSemanticsNodes().isNotEmpty()

    private fun clickRetry() {
        composeRule
            .onNode(hasText(OrderDraftTexts.text("order_list.retry")) and hasClickAction() and hasAnyAncestor(hasTestTag(OrderTags.ERROR)))
            .performSemanticsAction(SemanticsActions.OnClick)
        settle()
    }

    @Test
    fun r1_03_identity_failure_retry_button_on_the_real_page_rebuilds_identity_and_loads() {
        launch()
        assertTrue("可恢复的错误态", showsError())
        assertTrue("不发请求", sends.calls.isEmpty())
        val stale = actions()
        assertNull(stale.ticket.account)

        // 账号仍读不出：【重试】不发请求
        clickRetry()
        assertTrue(sends.calls.isEmpty())

        // 同一登录世代里账号恢复：真实页面的【重试】按钮先清空、换渲染身份，再按新账号取第一页
        session.user = "user-a"
        clickRetry()
        assertEquals(1, sends.calls.size)
        assertEquals(OrderListController.LIST_OPERATION, sends.calls.single().operationId)
        assertFalse(showsError())
        val fresh = actions()
        assertEquals("渲染身份换成新账号", "user-a", fresh.ticket.account)
        assertTrue("页面代次前进（旧回调失效）", fresh.ticket.page > stale.ticket.page)
        // 重建之前的旧回调（账号 null 的那份渲染身份）不再生效
        assertFalse(stale.onRetry())
        stale.onRefresh()
        settle()
        assertEquals(1, sends.calls.size)
    }

    @Test
    fun r1_03_identity_failure_pull_refresh_through_page_actions_rebuilds_identity() {
        launch()
        session.user = "user-a"
        composeRule.runOnUiThread { actions().onRefresh() }
        settle()
        assertEquals("下拉刷新同样重建身份并取第一页", 1, sends.calls.size)
        assertEquals("user-a", actions().ticket.account)
    }

    @Test
    fun r1_03_relogin_epoch_change_and_other_account_callbacks_are_still_rejected() {
        launch()
        val stale = actions()
        // 登录世代已变（重新登录，会话通知还没到）：不经这个入口重建
        session.user = "user-a"
        session.epoch = 2L
        composeRule.runOnUiThread {
            assertFalse(stale.onRetry())
            stale.onRefresh()
        }
        settle()
        assertTrue(sends.calls.isEmpty())

        // 回到同一世代、账号恢复：重建成功；之后换号（user-b）时 user-a 的旧回调照旧被拒（旧账号回调拒绝规则保留）
        session.epoch = 1L
        clickRetry()
        assertEquals(1, sends.calls.size)
        val userA = actions()
        session.user = "user-b"
        composeRule.runOnUiThread {
            assertFalse(userA.onRetry())
            userA.onRefresh()
        }
        settle()
        assertEquals("旧账号的回调不发请求", 1, sends.calls.size)
    }

    private companion object {
        const val ROUNDS = 3
        const val EMPTY_PAGE = """{"items":[],"next_cursor":null,"earliest_visible_date":null}"""
    }
}
