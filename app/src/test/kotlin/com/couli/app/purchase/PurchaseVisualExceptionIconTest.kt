package com.couli.app.purchase

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.trade.TradeDraftTexts
import com.couli.core.designsystem.CouliIcons
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], shadows = [PurchaseVisualCallbacks::class], qualifiers = "w390dp-h956dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PurchaseVisualExceptionIconTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { PurchaseVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun failedErrorAlertAndRetry() = verify("failed", false, false)

    @Test fun failedErrorAlertAndNoRebate() = verify("failed", false, true)

    @Test fun failedDarkErrorAlertAndRetry() = verify("failed", true, false)

    @Test fun failedDarkErrorAlertAndNoRebate() = verify("failed", true, true)

    @Test fun unavailableSecondaryInfoAndDismiss() = verify("unavailable", false)

    @Test fun unavailableDarkSecondaryInfoAndDismiss() = verify("unavailable", true)

    @Test fun sdkErrorAlertAndDismiss() = verify("sdk", false)

    @Test fun sdkDarkErrorAlertAndDismiss() = verify("sdk", true)

    private fun verify(
        scenario: String,
        dark: Boolean,
        noRebate: Boolean = false,
    ) {
        val info = scenario == "unavailable"
        val probe = PurchaseVisualDialogIconProbe(rule, host)
        probe.reference(if (info) CouliIcons.info else CouliIcons.alert, if (info) PurchaseIconTone.SECONDARY else PurchaseIconTone.ERROR)
        host.launch(dark = dark)
        host.prepare(scenario, rule)
        val title =
            when (scenario) {
                "failed" -> TradeDraftTexts.text("error.30104.credential_invalid")
                "unavailable" -> TradeDraftTexts.format("error.30101.auth_unavailable", mapOf("platform_name" to "淘宝"))
                else -> TradeDraftTexts.text("jump.taobao_sdk_unavailable")
            }
        val visual = runCatching { probe.assertLeadingIcon(title) }
        val failed = scenario == "failed"
        host.counts(open = 1, auth = (if (failed) 1 else 0) to 0)
        val reportsBefore = host.reports.toList()
        rule
            .button(
                if (!failed) {
                    "知道了"
                } else if (noRebate) {
                    "仍去购买（无返利）"
                } else {
                    "重试"
                },
            ).performClick()
        rule.waitForIdle()
        host.counts(open = if (failed) 2 else 1, auth = (if (failed) 1 else 0) to 0)
        if (failed) {
            PurchaseVisualCallbacks.assertCalls(host.controller, "authorize", if (noRebate) "buyWithoutRebate" else "retryPurchase")
            val body =
                Json
                    .parseToJsonElement(
                        host.api
                            .last()
                            .call.body!!,
                    ).jsonObject
            assertEquals(noRebate, body["no_rebate"]?.jsonPrimitive?.boolean ?: false)
        } else {
            PurchaseVisualCallbacks.assertCalls(host.controller, "dismissDialog")
            assertNull(host.controller.state.value.dialog)
        }
        checkReports(scenario, reportsBefore)
        visual.getOrThrow()
    }

    private fun checkReports(
        scenario: String,
        before: List<JsonObject>,
    ) {
        assertEquals("弹窗点击不能重复上报", before, host.reports)
        assertEquals(if (scenario == "sdk") 1 else 0, host.reports.size)
        assertEquals("SDK未就绪时没有真实open调用", 0, host.ports.sdkOpens)
        if (scenario == "sdk") {
            assertEquals(
                "sdk_init_failed",
                host.reports
                    .single()
                    .getValue("result")
                    .jsonPrimitive.content,
            )
            assertEquals(
                "sdk",
                host.reports
                    .single()
                    .getValue("step")
                    .jsonPrimitive.content,
            )
        }
    }
}
