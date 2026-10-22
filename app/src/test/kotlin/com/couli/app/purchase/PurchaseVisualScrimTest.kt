package com.couli.app.purchase

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliDialog
import com.couli.core.designsystem.CouliTheme
import com.couli.feature.trade.TradeTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], shadows = [PurchaseVisualCallbacks::class], qualifiers = "w390dp-h956dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PurchaseVisualScrimTest {
    @get:Rule val rule = createComposeRule()
    private val standard = lazy { PurchaseVisualTestHost(rule) }
    private val host get() = standard.value
    private var scrim = Color.Unspecified

    @After fun close() {
        if (standard.isInitialized()) host.close()
    }

    @Test fun priceLight() = purchase("price", false)

    @Test fun priceDark() = purchase("price", true)

    @Test fun rebateLight() = purchase("rebate", false)

    @Test fun rebateDark() = purchase("rebate", true)

    @Test fun compareLight() = purchase("compare", false)

    @Test fun compareDark() = purchase("compare", true)

    @Test fun authFailedLight() = purchase("failed", false)

    @Test fun authFailedDark() = purchase("failed", true)

    @Test fun unavailableLight() = purchase("unavailable", false)

    @Test fun unavailableDark() = purchase("unavailable", true)

    @Test fun sdkLight() = purchase("sdk", false)

    @Test fun sdkDark() = purchase("sdk", true)

    @Test fun blockedLight() = purchase("blocked", false)

    @Test fun blockedDark() = purchase("blocked", true)

    @Test fun unconfirmedLight() = purchase("unconfirmed", false)

    @Test fun unconfirmedDark() = purchase("unconfirmed", true)

    @Test fun loadingLight() = purchase("opening", false)

    @Test fun loadingDark() = purchase("opening", true)

    @Test fun browserLoadingLight() = purchase("browser", false)

    @Test fun browserLoadingDark() = purchase("browser", true)

    @Test fun defaultDialogLightKeepsLegacyDim() = legacy(false)

    @Test fun defaultDialogDarkKeepsLegacyDim() = legacy(true)

    @Composable
    private fun ScrimSamples() {
        scrim = CouliColors.overlayScrim
        Row {
            Box(Modifier.size(16.dp).background(CouliColors.backgroundCanvas).testTag("scrim_canvas"))
            // 深色页面本身太暗，0.55 与旧 0.6 只差 1；白色样本必须同时测，防止容差掩盖错误。
            Box(Modifier.size(16.dp).background(Color.White).testTag("scrim_white"))
        }
    }

    private fun measurements(): List<PurchaseScrimSample> =
        listOf("scrim_canvas", "scrim_white").map { PurchaseVisualWindowProbe(rule).scrim(rule.onNodeWithTag(it).fetchSemanticsNode()) }

    private fun purchase(
        scenario: String,
        dark: Boolean,
    ) {
        host.reference = { ScrimSamples() }
        host.launch(if (scenario == "compare") "pdd_coupon" else "tb_coupon_range", dark = dark)
        host.prepare(scenario, rule)
        val measured = measurements()
        val auth = if (scenario == "failed") 1 else 0
        host.counts(open = 1, auth = auth to 0)
        closePurchase(scenario)
        val calls =
            when (scenario) {
                "failed" -> arrayOf("authorize", "retryPurchase")
                "blocked" -> arrayOf("contactService")
                "opening" -> arrayOf("cancelLoading")
                "browser" -> arrayOf("cancelBrowser")
                else -> arrayOf("dismissDialog")
            }
        PurchaseVisualCallbacks.assertCalls(host.controller, *calls)
        host.counts(open = if (scenario == "failed") 2 else 1, auth = auth to 0, callbacks = (if (scenario == "blocked") 1 else 0) to 0)
        measured.forEach { assertScrim(it, scrim) }
    }

    private fun closePurchase(scenario: String) {
        when (scenario) {
            "opening", "browser" -> {
                rule.onNodeWithTag(TradeTags.LOADING_CANCEL).performClick()
            }

            "compare" -> {
                rule.onNodeWithContentDescription("关闭").performClick()
            }

            else -> {
                rule
                    .button(
                        when (scenario) {
                            "failed" -> "重试"
                            "blocked" -> "联系客服"
                            "unconfirmed" -> "稍后再试"
                            "unavailable", "sdk" -> "知道了"
                            else -> "取消"
                        },
                    ).performClick()
            }
        }
        rule.waitForIdle()
    }

    private fun legacy(dark: Boolean) {
        var confirms = 0
        var dismisses = 0
        rule.setContent {
            CouliTheme(darkTheme = dark) {
                Box(Modifier.fillMaxSize().background(CouliColors.backgroundCanvas)) {
                    ScrimSamples()
                    CouliDialog("旧默认对照", "确认", { confirms += 1 }, { dismisses += 1 }, dismissText = "取消")
                }
            }
        }
        val measured = measurements()
        rule.button("确认").performClick()
        assertEquals(1, confirms)
        assertEquals(0, dismisses)
        rule.button("取消").performClick()
        assertEquals(1, confirms)
        assertEquals(1, dismisses)
        measured.forEach {
            assertEquals("不传遮罩参数仍为旧系统dim", .6f, it.dim, .001f)
            assertScrim(it, Color.Black.copy(alpha = .6f))
        }
        assertTrue("旧默认不能冒充购买令牌", !measured.last().after.matchesToastIconTint(scrim.compositeOver(Color(measured.last().before)).toArgb()))
    }

    private fun assertScrim(
        sample: PurchaseScrimSample,
        overlay: Color,
    ) {
        val expected = overlay.compositeOver(Color(sample.before)).toArgb()
        println(
            "SCRIM before=${sample.before.toUInt().toString(
                16,
            )} actual=${sample.after.toUInt().toString(16)} expected=${expected.toUInt().toString(16)} dim=${sample.dim}",
        )
        assertTrue("遮罩ARGB各通道容差≤2；expected=$expected actual=${sample.after}", sample.after.matchesToastIconTint(expected))
    }
}
