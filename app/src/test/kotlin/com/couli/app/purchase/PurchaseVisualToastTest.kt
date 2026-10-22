package com.couli.app.purchase

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.productPixels
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliIcons
import com.couli.feature.trade.TradeTags
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** B4：真实轻提示左侧必须绘出指定图标与色，不能只在测试语义上写图标名。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h956dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PurchaseVisualToastTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { PurchaseVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun cancelledPriceUsesInfoAndDoesNotJump() = toast("cancel_price")

    @Test fun cancelledRebateUsesInfoAndDoesNotJump() = toast("cancel_rebate")

    @Test fun cancelledPriceRebateUsesInfoAndDoesNotJump() = toast("cancel_price_rebate")

    @Test fun requoteUsesInfoWhileStillJumpingOnce() = toast("cached")

    @Test fun maintenanceUsesWarningAndDoesNotJump() = toast("maintenance")

    @Test fun copiedUsesCopyAndDoesNotAuthorize() = toast("copied")

    @Test fun cancelledDarkStillUsesSecondaryTint() = toast("cancel_price", dark = true)

    private fun toast(
        scenario: String,
        dark: Boolean = false,
    ) {
        val icon =
            when (scenario) {
                "maintenance" -> CouliIcons.alert
                "copied" -> CouliIcons.copy
                else -> CouliIcons.info
            }
        host.reference = {
            Box(Modifier.fillMaxSize().padding(4.dp), contentAlignment = Alignment.BottomEnd) {
                Icon(
                    icon,
                    null,
                    tint = if (scenario == "maintenance") CouliColors.statusWarningText else CouliColors.textSecondary,
                    modifier = Modifier.size(20.dp).testTag("purchase_reference_icon"),
                )
            }
        }
        host.launch(dark = dark)
        if (scenario == "copied") {
            host.buy()
            host.answer(PurchaseVisualFixtures.jump("copy_tpwd", "FIXTURE-TPWD"))
        } else if (scenario == "maintenance") {
            host.buy()
            host.fail(50301, JsonObject(mapOf("reason" to JsonPrimitive("maintenance"))))
        } else {
            host.prepare(scenario, rule)
        }
        host.counts(open = 1, jumps = if (scenario == "cached") 1 else 0, copies = if (scenario == "copied") 1 else 0)
        assertToastIcon(scenario)
    }

    private fun assertToastIcon(scenario: String) {
        val toast = rule.onNodeWithTag(TradeTags.TOAST).fetchSemanticsNode()
        val expected = rule.onNodeWithTag("purchase_reference_icon").fetchSemanticsNode().boundsInRoot
        val pixels = rule.productPixels()
        try {
            val tint = (if (scenario == "maintenance") host.warningColor else host.secondaryColor).toArgb()
            pixels.assertToastIcon(toast.toastIconBounds(), expected, tint)
        } finally {
            pixels.recycle()
        }
    }
}
