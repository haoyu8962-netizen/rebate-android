package com.couli.app.purchase

import android.os.Looper
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.couli.feature.trade.AuthStepResult
import com.couli.feature.trade.PurchaseDialog
import com.couli.feature.trade.PurchasePhase
import com.couli.feature.trade.TradeOperations
import com.couli.feature.trade.TradeTags
import java.time.Duration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.robolectric.Shadows.shadowOf

/** 所有画面先走实际输入/响应到达目标状态；不向控制器私有 StateFlow 塞值。 */
internal fun PurchaseVisualTestHost.prepare(
    scenario: String,
    rule: ComposeContentTestRule,
) {
    buy()
    when (scenario) {
        "opening" -> {
            Unit
        }

        "timeout" -> {
            rule.runOnIdle { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9)) }
        }

        "auth", "declined", "failed" -> {
            fail(30101, PurchaseVisualFixtures.auth)
            when (scenario) {
                "declined" -> {
                    rule.onNodeWithContentDescription("关闭").performClick()
                }

                "failed" -> {
                    ports.authResult = AuthStepResult.Failed
                    rule.onNodeWithTag(TradeTags.AUTHORIZE).performClick()
                }
            }
        }

        "unavailable" -> {
            fail(30101, JsonObject(mapOf("reason" to JsonPrimitive("auth_unavailable"))))
        }

        "blocked" -> {
            fail(30153)
        }

        "blocked30103" -> {
            fail(30103)
        }

        "blocked30151" -> {
            fail(30151)
        }

        "unconfirmed" -> {
            fail(50303)
        }

        else -> {
            prepareOther(scenario, rule)
        }
    }
    rule.waitForIdle()
}

private fun PurchaseVisualTestHost.prepareOther(
    scenario: String,
    rule: ComposeContentTestRule,
) {
    when (scenario) {
        "maintenance" -> {
            fail(50301, JsonObject(mapOf("reason" to JsonPrimitive("maintenance"))))
        }

        "browser" -> {
            answer(PurchaseVisualFixtures.jump("h5", "https://shop.example.test/item"))
        }

        "sdk" -> {
            answer(PurchaseVisualFixtures.jump("sdk"))
        }

        "tip" -> {
            answer("unchanged")
            rule.runOnIdle { api.succeed(JsonObject(mapOf("jump_tip" to JsonObject(emptyMap()))), TradeOperations.GET_TIPS) }
        }

        "cancel_price", "cancel_rebate", "cancel_price_rebate", "cancel_compare" -> {
            answer(scenario.removePrefix("cancel_"))
            if (scenario == "cancel_compare") rule.onNodeWithContentDescription("关闭").performClick() else rule.button("取消").performClick()
        }

        else -> {
            answer(scenario)
        }
    }
    rule.waitForIdle()
}

/** 防止尚未进入目标浮层时误录成正常详情页。 */
internal fun PurchaseVisualTestHost.assertScenarioReady(scenario: String) {
    val state = controller.state.value
    val ready =
        when (scenario) {
            "opening" -> state.phase == PurchasePhase.LOADING
            "timeout" -> state.phase == PurchasePhase.TIMEOUT
            "browser" -> state.browserNotice
            "off_shelf" -> state.toast == null && state.dialog == null
            else -> SNAPSHOT_DIALOGS[scenario]?.isInstance(state.dialog) ?: (state.toast != null && state.dialog == null)
        }
    check(ready) { "截图目标尚未就绪：$scenario，实际=$state" }
}

private val SNAPSHOT_DIALOGS =
    mapOf(
        "auth" to PurchaseDialog.AuthSheet::class,
        "declined" to PurchaseDialog.AuthDeclined::class,
        "failed" to PurchaseDialog.AuthDeclined::class,
        "unavailable" to PurchaseDialog.AuthUnavailable::class,
        "blocked" to PurchaseDialog.Notice::class,
        "blocked30103" to PurchaseDialog.Notice::class,
        "blocked30151" to PurchaseDialog.Notice::class,
        "unconfirmed" to PurchaseDialog.Notice::class,
        "sdk" to PurchaseDialog.SdkFailed::class,
        "tip" to PurchaseDialog.JumpTip::class,
        "price" to PurchaseDialog.ConfirmPrice::class,
        "coupon" to PurchaseDialog.ConfirmPrice::class,
        "price_rebate" to PurchaseDialog.ConfirmPrice::class,
        "rebate" to PurchaseDialog.ConfirmRebateGone::class,
        "compare" to PurchaseDialog.CompareNoRebate::class,
    )
