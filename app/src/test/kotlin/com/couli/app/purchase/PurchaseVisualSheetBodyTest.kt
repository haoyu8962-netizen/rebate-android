package com.couli.app.purchase

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.assertNoTextOverflow
import com.couli.app.product.textLayout
import com.couli.app.trade.TradeDraftTexts
import com.couli.feature.trade.TradeOperations
import com.couli.feature.trade.TradeTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], shadows = [PurchaseVisualCallbacks::class], qualifiers = "w390dp-h956dp-xhdpi")
class PurchaseVisualSheetBodyTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { PurchaseVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun noticeBodyUses17With25Point5LineHeight() = notice(false, 1f)

    @Test fun noticeBodyDarkUses17With25Point5LineHeight() = notice(true, 1f)

    @Test
    @Config(qualifiers = "w390dp-h667dp-xhdpi")
    fun shortNoticeAt200DoesNotClipAndKeepsBodySize() = notice(false, 2f)

    @Test
    @Config(qualifiers = "w390dp-h667dp-xhdpi")
    fun shortDarkNoticeAt200DoesNotClipAndKeepsBodySize() = notice(true, 2f)

    private fun notice(
        dark: Boolean,
        scale: Float,
    ) {
        host.showTip = true
        host.launch(dark = dark, fontScale = scale)
        host.prepare("tip", rule)
        host.counts(open = 1)
        val visual =
            runCatching {
                for (key in listOf("trade.jump_tip.line1", "trade.jump_tip.line2")) {
                    checkLine(TradeDraftTexts.text(key), 17f, 25.5f, scale, TradeTags.JUMP_TIP)
                }
            }
        rule.onNodeWithTag(TradeTags.JUMP_TIP).assertIsDisplayed().performClick()
        rule.waitForIdle()
        host.counts(open = 1, jumps = 1)
        PurchaseVisualCallbacks.assertCalls(host.controller, "confirm")
        assertEquals(1, host.count(TradeOperations.MARK_TIP_READ))
        visual.getOrThrow()
    }

    @Test
    @Config(qualifiers = "w390dp-h667dp-xhdpi")
    fun authExplanationAt200Keeps14With21LineHeightAndDoesNotClip() {
        host.launch(fontScale = 2f)
        host.auth()
        val lines =
            listOf(
                TradeDraftTexts.format("trade.auth.reason", mapOf("platform_name" to "淘宝")),
                TradeDraftTexts.text("trade.auth.privacy"),
                TradeDraftTexts.text("auth_manage.rebind_note"),
            )
        val visual = runCatching { lines.forEach { checkLine(it, 14f, 21f, 2f, TradeTags.AUTHORIZE) } }
        rule.onNodeWithTag(TradeTags.AUTHORIZE).assertIsDisplayed().performClick()
        rule.waitForIdle()
        host.counts(open = 1, auth = 1 to 1)
        PurchaseVisualCallbacks.assertCalls(host.controller, "authorize")
        visual.getOrThrow()
    }

    private fun checkLine(
        text: String,
        size: Float,
        lineHeight: Float,
        scale: Float,
        action: String,
    ) {
        val node = rule.onNodeWithText(text, useUnmergedTree = true)
        node.performScrollTo().assertIsDisplayed().assertNoTextOverflow()
        val layout = node.textLayout()
        assertEquals("正文档", size, layout.layoutInput.style.fontSize.value, .001f)
        assertEquals("画板行高", lineHeight, layout.layoutInput.style.lineHeight.value, .001f)
        assertEquals("窗口字体缩放", scale, layout.layoutInput.density.fontScale, .001f)
        val bounds = node.fetchSemanticsNode().boundsInWindow
        val button =
            rule
                .onNodeWithTag(action)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInWindow
        assertTrue("正文不能被固定按钮遮住", bounds.bottom <= button.top + 1f)
    }
}
