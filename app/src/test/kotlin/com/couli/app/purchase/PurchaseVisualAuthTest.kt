package com.couli.app.purchase

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.assertNoTextOverflow
import com.couli.app.product.textLayout
import com.couli.feature.trade.AuthStepResult
import com.couli.feature.trade.PurchaseDialog
import com.couli.feature.trade.TradeOperations
import com.couli.feature.trade.TradeTags
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], shadows = [PurchaseVisualCallbacks::class], qualifiers = "w390dp-h956dp-xhdpi")
class PurchaseVisualAuthTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { PurchaseVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun declinedRetry() = declined(false, true)

    @Test fun declinedNoRebate() = declined(false, false)

    @Test fun failedRetry() = declined(true, true)

    @Test fun failedNoRebate() = declined(true, false)

    private fun declined(
        failed: Boolean,
        retry: Boolean,
    ) {
        host.launch()
        host.auth()
        if (failed) {
            host.ports.authResult = AuthStepResult.Failed
            rule.onNodeWithTag(TradeTags.AUTHORIZE).performClick()
        } else {
            rule.onNodeWithContentDescription("关闭").performClick()
        }
        rule.waitForIdle()
        host.counts(open = 1, auth = (if (failed) 1 else 0) to 0)
        assertEquals(PurchaseDialog.AuthDeclined("taobao", failed), host.controller.state.value.dialog)
        val oldKey =
            host.api
                .last()
                .call.idempotencyKey
        rule.button(if (retry) "重试" else "仍去购买（无返利）").performClick()
        rule.waitForIdle()
        host.counts(open = 2, auth = (if (failed) 1 else 0) to 0)
        assertNotEquals(
            oldKey,
            host.api
                .last()
                .call.idempotencyKey,
        )
        val body =
            host.api
                .last()
                .call.body
                .orEmpty()
        assertEquals(!retry, body.contains("\"no_rebate\":true"))
        if (!retry) assertTrue(body.contains(if (failed) "auth_failed" else "auth_declined"))
    }

    @Test fun authorizeBindsOnceThenCreatesOneFreshOpen() {
        host.launch()
        host.auth()
        val oldKey =
            host.api
                .last()
                .call.idempotencyKey
        host.counts(open = 1)
        rule.onNodeWithTag(TradeTags.AUTHORIZE).performClick()
        rule.waitForIdle()
        host.counts(open = 1, auth = 1 to 1)
        rule.onNodeWithTag(TradeTags.AUTHORIZE).performClick()
        rule.waitForIdle()
        host.counts(open = 1, auth = 1 to 1)
        rule.runOnIdle { host.api.succeed(JsonObject(mapOf("status" to JsonPrimitive("active"))), TradeOperations.BIND_UNION) }
        rule.waitForIdle()
        host.counts(open = 2, auth = 1 to 1)
        assertNotEquals(
            oldKey,
            host.api
                .last()
                .call.idempotencyKey,
        )
    }

    @Test fun unavailableOkOnlyCloses() {
        host.launch()
        host.buy()
        host.fail(30101, JsonObject(mapOf("reason" to JsonPrimitive("auth_unavailable"))))
        rule.button("知道了").performClick()
        rule.waitForIdle()
        host.counts(open = 1)
        assertNull(host.controller.state.value.dialog)
    }

    @Test fun sdkFailedOkOnlyCloses() {
        host.launch()
        host.buy()
        host.answer(PurchaseVisualFixtures.jump("sdk"))
        rule.button("知道了").performClick()
        rule.waitForIdle()
        host.counts(open = 1)
        assertNull(host.controller.state.value.dialog)
    }

    @Test fun jumpTipContinuesExistingAttemptAndMarksReadOnce() {
        host.showTip = true
        host.launch()
        host.buy()
        host.answer("unchanged")
        host.counts(open = 1)
        rule.runOnIdle { host.api.succeed(JsonObject(mapOf("jump_tip" to JsonObject(emptyMap()))), TradeOperations.GET_TIPS) }
        rule.waitForIdle()
        rule.onNodeWithTag(TradeTags.JUMP_TIP).assertIsDisplayed().performClick()
        rule.waitForIdle()
        host.counts(open = 1, jumps = 1)
        assertEquals(1, host.count(TradeOperations.GET_TIPS))
        assertEquals(1, host.count(TradeOperations.MARK_TIP_READ))
        assertEquals(
            "{\"platform\":\"taobao\"}",
            host.api
                .last(TradeOperations.MARK_TIP_READ)
                .call.body,
        )
        assertEquals(1, host.reports.size)
    }

    @Test
    @Config(qualifiers = "w390dp-h667dp-xhdpi")
    fun shortAuthSheetAt200KeepsHeaderAndAuthorizeVisibleWithoutClipping() {
        host.launch(fontScale = 2f)
        host.auth()
        val title = rule.onNodeWithText("购买前需完成淘宝授权，用于识别你的订单", useUnmergedTree = true)
        title.assertIsDisplayed().assertNoTextOverflow()
        assertEquals(
            2f,
            title
                .textLayout()
                .layoutInput.density.fontScale,
            .001f,
        )
        val action = rule.onNodeWithTag(TradeTags.AUTHORIZE).assertIsDisplayed()
        rule.onNodeWithText("去授权", useUnmergedTree = true).assertNoTextOverflow()
        val a = action.fetchSemanticsNode().boundsInWindow
        val t = title.fetchSemanticsNode().boundsInWindow
        assertTrue("标题和固定按钮不能重叠", t.bottom <= a.top)
        action.performClick()
        rule.waitForIdle()
        host.counts(open = 1, auth = 1 to 1)
    }
}
