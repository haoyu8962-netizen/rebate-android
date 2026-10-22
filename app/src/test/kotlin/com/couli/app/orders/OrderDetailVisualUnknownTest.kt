package com.couli.app.orders

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.orders.OrderDraftTexts
import com.couli.feature.orders.OrderTags
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1400dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OrderDetailVisualUnknownTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { OrderDetailVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun unknownHasOnlyLedgerAndRefreshEvenWithAllServerActions() {
        host.launch(OrderDetailVisualFixtures.detail("detail_unknown"))
        val expected = listOf(OrderTags.DETAIL_ACTION_PREFIX + "ledger")
        val nodes =
            rule
                .onAllNodes(
                    SemanticsMatcher("action tags") {
                        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(OrderTags.DETAIL_ACTION_PREFIX) == true
                    },
                ).fetchSemanticsNodes()
        assertEquals(expected, nodes.map { it.config[SemanticsProperties.TestTag] })
        rule.onNodeWithTag(DETAIL_REFRESH).assertExists()
        rule.clickDetail(OrderTags.DETAIL_ACTION_PREFIX + "ledger")
        assertEquals(listOf("Ledger" to JsonObject(emptyMap())), host.opened)
        assertEquals(1, host.api.calls.size)
    }

    @Test fun refreshUsesExistingGetOrderExactlyOnceAndPreservesParameters() {
        host.launch(OrderDetailVisualFixtures.detail("detail_unknown"))
        val first = host.api.calls.single()
        rule.clickDetail(DETAIL_REFRESH)
        assertEquals(2, host.api.calls.size)
        assertEquals(
            first,
            host.api.calls
                .last()
                .copy(ownership = first.ownership),
        )
        assertEquals(2, host.loadedCount)
        assertTrue(host.opened.isEmpty())
    }

    @Test fun unknownRetainsReadOnlyPendingProgress() {
        host.launch(
            OrderDetailVisualFixtures.change(
                OrderDetailVisualFixtures.detail("detail_unknown"),
                "appeal_pending" to JsonPrimitive(true),
            ),
        )
        rule.onNodeWithText(OrderDraftTexts.text("order_detail.appeal_progress")).performScrollTo().performTouchInput { click(center) }
        rule.waitForIdle()
        assertEquals(listOf("Appeal" to JsonObject(emptyMap())), host.opened)
        rule.onNodeWithTag(OrderTags.DETAIL_ACTION_PREFIX + "appeal").assertDoesNotExist()
    }

    @Test fun refreshRespects42901AndRepeatedClicksDoNotIssueRequests() {
        val detail = OrderDetailVisualFixtures.detail("detail_unknown")
        host.launch(detail)
        // 另一订单请求触发共享42901禁用期；当前已显示详情仍保留。
        rule.runOnIdle { host.gate.start(5000) }
        val refresh = rule.onNodeWithTag(DETAIL_REFRESH)
        refresh.assertIsNotEnabled()
        val before = host.api.calls.size
        refresh.performScrollTo().performTouchInput {
            click(center)
            click(center)
        }
        rule.waitForIdle()
        assertEquals(before, host.api.calls.size)
    }

    @Test fun oldRefreshCallbackCannotFetchAfterIdentityChanges() {
        host.launch(OrderDetailVisualFixtures.detail("detail_unknown"))
        val callback =
            rule
                .onNodeWithTag(DETAIL_REFRESH)
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        val before = host.api.calls.size
        rule.runOnIdle {
            host.epoch++
            callback()
        }
        assertEquals(before, host.api.calls.size)
    }
}
