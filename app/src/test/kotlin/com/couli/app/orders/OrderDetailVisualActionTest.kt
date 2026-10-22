package com.couli.app.orders

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.orders.OrderDraftTexts
import com.couli.feature.orders.OrderTags
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1400dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OrderDetailVisualActionTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { OrderDetailVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun appealMovesIntoCardWithoutChangingClickRouteOrRequestCount() = action("detail_blacklist", "appeal", "Appeal")

    @Test fun ledgerMovesIntoCardWithoutChangingClickRouteOrRequestCount() = action("detail_clawed_back", "ledger", "Ledger")

    @Test fun contactUsesDedicatedPortExactlyOnce() = action("detail_reason_07", "contact_cs", "Help")

    @Test fun claimActionStillUsesFindOrder() = supplied("CLAIM", "claim", "FindOrder")

    @Test fun reauthActionStillUsesAuthManage() = supplied("REAUTH", "reauth", "AuthManage")

    private fun supplied(
        raw: String,
        action: String,
        route: String,
    ) {
        val detail =
            OrderDetailVisualFixtures.change(
                OrderDetailVisualFixtures.detail("detail_price_compare_risk"),
                "reason_action" to JsonArray(listOf(JsonPrimitive(raw))),
            )
        host.launch(detail)
        checkAction(action, route)
    }

    private fun action(
        fixture: String,
        action: String,
        route: String,
    ) {
        host.launch(OrderDetailVisualFixtures.detail(fixture))
        checkAction(action, route)
    }

    private fun checkAction(
        action: String,
        route: String,
    ) {
        val tag = OrderTags.DETAIL_ACTION_PREFIX + action
        val before = host.api.calls.size
        rule.clickDetail(tag)
        assertEquals(listOf(route to JsonObject(emptyMap())), host.opened)
        assertEquals(before, host.api.calls.size)
        assertEquals(if (action == "contact_cs") 1 else 0, host.contactCount)
        assertEquals(1, host.loadedCount)
        rule.onNodeWithTag(tag).fetchSemanticsNode().assertInside(rule.onNodeWithTag(OrderTags.DETAIL_STATUS).fetchSemanticsNode())
        rule.onNodeWithTag(DETAIL_BOTTOM).assertDoesNotExist()
    }

    @Test fun twoCardButtonsKeepOrderAndEachRealClickCallsExactlyOneExit() {
        host.launch(OrderDetailVisualFixtures.detail("detail_clawed_two"))
        val first = OrderTags.DETAIL_ACTION_PREFIX + "contact_cs"
        val second = OrderTags.DETAIL_ACTION_PREFIX + "ledger"
        rule.clickDetail(first)
        rule.clickDetail(second)
        assertEquals(listOf("Help" to JsonObject(emptyMap()), "Ledger" to JsonObject(emptyMap())), host.opened)
        assertEquals(1, host.contactCount)
        assertEquals(1, host.api.calls.size)
        val a = rule.onNodeWithTag(first).fetchSemanticsNode()
        val b = rule.onNodeWithTag(second).fetchSemanticsNode()
        val card = rule.onNodeWithTag(OrderTags.DETAIL_STATUS).fetchSemanticsNode()
        a.assertInside(card)
        b.assertInside(card)
        assertTrue(a.boundsInRoot.left < b.boundsInRoot.left)
        assertFalse(a.touchBoundsInRoot.overlaps(b.touchBoundsInRoot))
    }

    @Test fun reasonAndWithdrawSplitButPreserveBothCallbacks() {
        host.launch(
            OrderDetailVisualFixtures.change(
                OrderDetailVisualFixtures.detail("detail_settled"),
                "reason_action" to JsonArray(listOf(JsonPrimitive("CONTACT_CS"))),
            ),
        )
        rule.clickDetail(OrderTags.DETAIL_ACTION_PREFIX + "contact_cs")
        rule.clickDetail(OrderTags.DETAIL_ACTION_PREFIX + "withdraw")
        assertEquals(listOf("Help" to JsonObject(emptyMap()), "Withdraw" to JsonObject(emptyMap())), host.opened)
        assertEquals(1, host.api.calls.size)
        rule
            .onNodeWithTag(OrderTags.DETAIL_ACTION_PREFIX + "contact_cs")
            .fetchSemanticsNode()
            .assertInside(rule.onNodeWithTag(OrderTags.DETAIL_STATUS).fetchSemanticsNode())
        rule
            .onNodeWithTag(OrderTags.DETAIL_ACTION_PREFIX + "withdraw")
            .fetchSemanticsNode()
            .assertInside(rule.onNodeWithTag(DETAIL_BOTTOM).fetchSemanticsNode())
        rule
            .onNode(hasTestTag(OrderTags.DETAIL_ACTION_PREFIX + "withdraw") and hasAnyAncestor(hasTestTag(OrderTags.DETAIL_STATUS)))
            .assertDoesNotExist()
    }

    @Test fun pendingAppealDoesNotFilterServerActionAndProgressStillOpensAppeal() {
        host.launch(
            OrderDetailVisualFixtures.change(
                OrderDetailVisualFixtures.detail("detail_appeal_pending"),
                "reason_action" to JsonArray(listOf(JsonPrimitive("APPEAL"))),
            ),
        )
        rule.clickDetail(OrderTags.DETAIL_ACTION_PREFIX + "appeal")
        rule.onNodeWithText(OrderDraftTexts.text("order_detail.appeal_progress")).performScrollTo().performTouchInput { click(center) }
        rule.waitForIdle()
        assertEquals(List(2) { "Appeal" to JsonObject(emptyMap()) }, host.opened)
        assertEquals(1, host.api.calls.size)
    }

    @Test fun pendingProgressAndServerActionHaveSeparate48DpTargets() {
        val (progress, action) = pendingProgressAndContact()
        progress.assertTouch48()
        action.assertTouch48()
        assertFalse(
            "申诉进度与服务端动作的48dp命中区不能重叠：${progress.touchBoundsInRoot} / ${action.touchBoundsInRoot}",
            progress.touchBoundsInRoot.overlaps(action.touchBoundsInRoot),
        )
    }

    @Test fun pendingProgressAndServerActionEdgesClickTheirOwnExitExactlyOnce() {
        val (progress, action) = pendingProgressAndContact()
        val link = progress.touchBoundsInRoot
        val button = action.touchBoundsInRoot
        val d = progress.layoutInfo.density.density
        // 使用共同的横坐标，重点命中相邻的下缘/上缘；不同出口能识别错投，不能都用 Appeal。
        val x = (maxOf(link.left, button.left) + minOf(link.right, button.right)) / 2
        val points =
            listOf(
                Offset(x, link.top + d),
                Offset(x, link.bottom - d),
                Offset(x, button.top + d),
                Offset(x, button.bottom - d),
            )
        val counts =
            points.map { point ->
                rule.touchDetail(point)
                host.opened.size
            }
        assertEquals("四次边缘真实触摸各自只能调用一个出口", listOf(1, 2, 3, 4), counts)
        assertEquals(listOf("Appeal", "Appeal", "Help", "Help").map { it to JsonObject(emptyMap()) }, host.opened)
        assertEquals(2, host.contactCount)
        assertEquals(1, host.api.calls.size)
    }

    private fun pendingProgressAndContact(): Pair<SemanticsNode, SemanticsNode> {
        host.launch(
            OrderDetailVisualFixtures.change(
                OrderDetailVisualFixtures.detail("detail_appeal_pending"),
                "reason_action" to JsonArray(listOf(JsonPrimitive("CONTACT_CS"))),
            ),
        )
        val action = rule.onNodeWithTag(OrderTags.DETAIL_ACTION_PREFIX + "contact_cs").performScrollTo().fetchSemanticsNode()
        val progress = rule.onNodeWithText(OrderDraftTexts.text("order_detail.appeal_progress")).fetchSemanticsNode()
        return progress to action
    }

    @Test fun maxTwoIsAppliedBeforePartitionSoViewCannotInventWithdraw() {
        val detail =
            OrderDetailVisualFixtures.change(
                OrderDetailVisualFixtures.detail("detail_settled"),
                "reason_action" to JsonArray(listOf("CONTACT_CS", "APPEAL", "REAUTH").map(::JsonPrimitive)),
            )
        host.launch(detail)
        rule.clickDetail(OrderTags.DETAIL_ACTION_PREFIX + "contact_cs")
        rule.clickDetail(OrderTags.DETAIL_ACTION_PREFIX + "appeal")
        rule.onNodeWithTag(OrderTags.DETAIL_ACTION_PREFIX + "withdraw").assertDoesNotExist()
        rule.onNodeWithTag(OrderTags.DETAIL_ACTION_PREFIX + "reauth").assertDoesNotExist()
        assertEquals(listOf("Help", "Appeal"), host.opened.map { it.first })
        assertEquals(1, host.api.calls.size)
    }

    @Test fun oldRenderedActionAndCopyCallbacksCannotWriteAfterIdentityChanges() {
        host.launch(OrderDetailVisualFixtures.detail("detail_blacklist"))
        val action =
            rule
                .onNodeWithTag(
                    OrderTags.DETAIL_ACTION_PREFIX + "appeal",
                ).fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        val copy =
            rule
                .onNodeWithTag(OrderTags.DETAIL_COPY)
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        val requests = host.api.calls.size
        rule.runOnIdle {
            host.epoch++
            action()
            copy()
        }
        assertTrue(host.opened.isEmpty())
        assertTrue(host.copies.isEmpty())
        assertEquals(requests, host.api.calls.size)
    }
}
