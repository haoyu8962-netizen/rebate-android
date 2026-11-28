package com.couli.app.purchase

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.trade.TradeDraftTexts
import com.couli.feature.trade.PurchaseDialog
import com.couli.feature.trade.TradeOperations
import com.couli.feature.trade.TradeTags
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * FU-AND-TB-WEBCODE-B 测试：AuthSheet 的「确认中」（设计稿 §3.2 PurchaseDialog.AuthSheet、§6.1；D10-3680）。点【去授权】拿到授权码、bindings 还没结果时，
 * 半屏显示文案键 auth.confirming 的文字（契约补键前是 TradeDraftTexts 草稿；只认键，不钉中文），不可关闭；bindings active 后消失并新键 open 一次。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], shadows = [PurchaseVisualCallbacks::class], qualifiers = "w390dp-h956dp-xhdpi")
class PurchaseVisualAuthConfirmingTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { PurchaseVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun confirmingTextShowsWhileBindingsIsPendingAndTheSheetCannotBeClosed() {
        val confirming = TradeDraftTexts.text("auth.confirming")
        assertTrue("auth.confirming 取得到文字", confirming.isNotEmpty())
        host.launch()
        host.auth()
        rule.onAllNodesWithText(confirming, useUnmergedTree = true).assertCountEquals(0)
        rule.onNodeWithTag(TradeTags.AUTHORIZE).performClick()
        rule.waitForIdle()
        host.counts(open = 1, auth = 1 to 1)
        assertEquals(PurchaseDialog.AuthSheet("taobao", busy = true, confirming = true), host.controller.state.value.dialog)
        rule.onNodeWithText(confirming, useUnmergedTree = true).assertIsDisplayed()
        rule.runOnIdle { host.controller.dismissDialog() }
        rule.waitForIdle()
        rule.onNodeWithText(confirming, useUnmergedTree = true).assertIsDisplayed()
        rule.runOnIdle { host.api.succeed(JsonObject(mapOf("status" to JsonPrimitive("active"))), TradeOperations.BIND_UNION) }
        rule.waitForIdle()
        host.counts(open = 2, auth = 1 to 1)
        rule.onAllNodesWithText(confirming, useUnmergedTree = true).assertCountEquals(0)
    }
}
