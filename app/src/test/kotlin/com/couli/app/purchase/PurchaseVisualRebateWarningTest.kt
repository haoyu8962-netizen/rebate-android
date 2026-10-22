package com.couli.app.purchase

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.ProductVisualFixtures
import com.couli.core.designsystem.CouliIcons
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], shadows = [PurchaseVisualCallbacks::class], qualifiers = "w390dp-h956dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PurchaseVisualRebateWarningTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { PurchaseVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun warningBackgroundAndContinue() = warning("background", false, true)

    @Test fun warningDarkBackgroundAndCancel() = warning("background", true, false)

    @Test fun warningAlertAndCancel() = warning("icon", false, false)

    @Test fun warningDarkAlertAndContinue() = warning("icon", true, true)

    @Test fun warningTextAndContinue() = warning("text", false, true)

    @Test fun warningDarkTextAndCancel() = warning("text", true, false)

    private fun warning(
        part: String,
        dark: Boolean,
        confirm: Boolean,
    ) {
        val probe = PurchaseVisualDialogIconProbe(rule, host)
        probe.reference(CouliIcons.alert, PurchaseIconTone.WARNING)
        host.fillQuote = ProductVisualFixtures.card("tb_price_changed_rebate_gone")
        host.launch(dark = dark)
        host.prepare("price_rebate", rule)
        host.counts(open = 1)
        for (text in listOf("之前（券后）", "现在（券后）", "¥46.78", "¥49.9")) rule.onNodeWithText(text).assertIsDisplayed()
        val updated = host.card
        assertEquals(4990L, updated.getValue("final_price_fen").jsonPrimitive.long)
        assertEquals(0L, updated.getValue("rebate_min_fen").jsonPrimitive.long)
        assertEquals(0L, updated.getValue("rebate_max_fen").jsonPrimitive.long)
        val visual =
            runCatching {
                when (part) {
                    "background" -> probe.assertWarningBackground(TITLE, NOTE)
                    "icon" -> probe.assertLeadingIcon(TITLE, centerLine = false)
                    else -> probe.assertWarningText(TITLE, NOTE)
                }
            }
        rule.button(if (confirm) "继续购买" else "取消").performClick()
        rule.waitForIdle()
        host.counts(open = 1, jumps = if (confirm) 1 else 0)
        PurchaseVisualCallbacks.assertCalls(host.controller, if (confirm) "confirm" else "dismissDialog")
        assertEquals(if (confirm) 1 else 0, host.reports.size)
        assertEquals(updated, host.card)
        visual.getOrThrow()
    }

    companion object {
        private const val TITLE = "该商品当前暂无返利"
        private const val NOTE = "继续购买不会获得返利"
    }
}
