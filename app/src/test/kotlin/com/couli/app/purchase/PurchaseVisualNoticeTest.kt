package com.couli.app.purchase

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.trade.TradeDraftTexts
import com.couli.feature.trade.TradeOperations
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** B7/B8：每个错误码分别点两个真实按钮；行为计数先断言，再断言预先取到的主次像素。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], shadows = [PurchaseVisualCallbacks::class], qualifiers = "w390dp-h956dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PurchaseVisualNoticeTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { PurchaseVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun blocked30153Contact() = notice(30153, true)

    @Test fun blocked30153NoRebate() = notice(30153, false)

    @Test fun blocked30103Contact() = notice(30103, true)

    @Test fun blocked30103NoRebate() = notice(30103, false)

    @Test fun blocked30151Contact() = notice(30151, true)

    @Test fun blocked30151NoRebate() = notice(30151, false)

    @Test fun blockedTitleHasWarningIconAndNoVisibleCloseButton() {
        host.launch()
        host.buy()
        host.fail(30153)
        host.counts(open = 1)
        val title = rule.onNodeWithText(TradeDraftTexts.text("error.30153"), useUnmergedTree = true).fetchSemanticsNode()
        val view = (title.root as ViewRootForTest).view
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(Canvas(bitmap)) }
        try {
            val bounds = title.boundsInRoot
            val ink =
                (bounds.top.toInt() until bounds.bottom.toInt()).sumOf { y ->
                    (0 until bitmap.width).count { x -> bitmap.getPixel(x, y) == host.warningColor.toArgb() }
                }
            assertTrue("标题行缺警告图标", ink > 10)
        } finally {
            bitmap.recycle()
        }
        rule.onNodeWithContentDescription("关闭").assertDoesNotExist()
    }

    private fun notice(
        code: Int,
        contact: Boolean,
    ) {
        host.launch()
        host.buy()
        host.fail(code)
        host.counts(open = 1)
        val main = rule.fillRatio(rule.button("联系客服").fetchSemanticsNode(), host.primaryColor)
        val other = rule.fillRatio(rule.button("仍去购买（无返利）").fetchSemanticsNode(), host.primaryColor)
        val oldKey =
            host.api
                .last()
                .call.idempotencyKey
        if (contact) {
            rule.button("联系客服").performClick()
            rule.waitForIdle()
            host.counts(open = 1, callbacks = 1 to 0)
            PurchaseVisualCallbacks.assertCalls(host.controller, "contactService")
            assertNull(host.controller.state.value.dialog)
        } else {
            rule.button("仍去购买（无返利）").performClick()
            rule.waitForIdle()
            host.counts(open = 2)
            PurchaseVisualCallbacks.assertCalls(host.controller, "buyWithoutRebate")
            val call = host.api.last().call
            assertNotEquals(oldKey, call.idempotencyKey)
            assertEquals(
                true,
                Json
                    .parseToJsonElement(call.body!!)
                    .jsonObject
                    .getValue("no_rebate")
                    .jsonPrimitive.boolean,
            )
            host.answer("unchanged")
            host.counts(open = 2, jumps = 1)
            assertEquals(1, host.reports.size)
        }
        assertPrimaryAndSecondary(main, other)
    }

    @Test fun unconfirmedPrimaryClosesThenOnlyAnotherBuyCreatesOneFreshOpen() {
        host.launch()
        host.buy()
        host.fail(50303)
        val key =
            host.api
                .last()
                .call.idempotencyKey
        host.counts(open = 1)
        // 允许定位旧版「重试」，专门抓只改文案/不改回调；文字另有独立测试。
        rule.onNode((hasText("稍后再试") or hasText("重试")) and hasClickAction()).performClick()
        rule.waitForIdle()
        host.counts(open = 1)
        assertNull(host.controller.state.value.dialog)
        PurchaseVisualCallbacks.assertCalls(host.controller, "dismissDialog")
        assertEquals(1, host.buyClicks)
        host.buy()
        host.counts(open = 2)
        assertEquals(2, host.buyClicks)
        assertNotEquals(
            key,
            host.api
                .last()
                .call.idempotencyKey,
        )
        host.answer("unchanged")
        host.counts(open = 2, jumps = 1)
    }

    @Test fun unconfirmedPrimaryHasOnlyDismissCallback() {
        host.launch()
        host.buy()
        host.fail(50303)
        rule.onNode((hasText("稍后再试") or hasText("重试")) and hasClickAction()).performClick()
        rule.waitForIdle()
        PurchaseVisualCallbacks.assertCalls(host.controller, "dismissDialog")
        host.counts(open = 1)
    }

    @Test fun unconfirmedPrimaryTextIsRetryLater() {
        host.launch()
        host.buy()
        host.fail(50303)
        rule.button("稍后再试").assertIsDisplayed()
        rule.onNodeWithText("重试").assertDoesNotExist()
        host.counts(open = 1)
    }

    @Test fun unconfirmedSecondaryStillCreatesOneNoRebateOpen() {
        host.launch()
        host.buy()
        host.fail(50303)
        val key =
            host.api
                .last()
                .call.idempotencyKey
        rule.button("仍去购买（无返利）").performClick()
        rule.waitForIdle()
        host.counts(open = 2)
        PurchaseVisualCallbacks.assertCalls(host.controller, "buyWithoutRebate")
        assertNotEquals(
            key,
            host.api
                .last()
                .call.idempotencyKey,
        )
        assertEquals(
            true,
            Json
                .parseToJsonElement(
                    host.api
                        .last()
                        .call.body!!,
                ).jsonObject
                .getValue("no_rebate")
                .jsonPrimitive.boolean,
        )
        host.answer("unchanged")
        host.counts(open = 2, jumps = 1)
        assertEquals(0, host.count(TradeOperations.GET_UNION_AUTH_URL))
    }
}
