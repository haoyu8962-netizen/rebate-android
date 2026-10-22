package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.wallet.WalletDraftTexts
import com.couli.feature.wallet.WalletTags
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1000dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WithdrawVisualBlockedTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WithdrawVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun w47RealnameHidesPayoutSlaAndIrrevocableAndOpensOnlyOnClick() {
        blocked("blocked_30304")
        hiddenSections()
        assertEquals(0, host.env.opened.size)
        rule.onNodeWithText(WalletDraftTexts.text("withdraw.guide.realname")).performScrollTo().performTouchInput { click(center) }
        assertEquals(listOf("RealName"), host.env.opened.map { it.first })
        assertEquals(0, host.api.writes().size)
    }

    @Test fun blockedGuideRealTouchRequestsNoWriteAndOpensOnce() {
        blocked("blocked_30304")
        val before = host.api.events.toList()
        rule.onNodeWithTag(WalletTags.WITHDRAW_GUIDE_BUTTON).performScrollTo().performTouchInput { click(center) }
        assertEquals(listOf("RealName"), host.env.opened.map { it.first })
        assertEquals(before, host.api.events)
    }

    @Test fun w47OtherBlockedCodesAlsoHideThreeSections() {
        blocked("blocked_30306")
        hiddenSections()
        rule.onNodeWithTag(WalletTags.WITHDRAW_SUBMIT).assertIsNotEnabled()
        assertEquals(0, host.api.writes().size)
    }

    @Test fun w48DailyLimitGuideOpensConfiguredRulesWithoutWrite() {
        blocked("blocked_30309")
        val reads = host.api.events.toList()
        // 页面底部原规则链接不能冒充引导框新增入口。
        val button = rule.onNode(hasText("查看提现规则") and hasClickAction())
        button.performScrollTo().performTouchInput { click(center) }
        assertEquals(listOf("Help"), host.env.opened.map { it.first })
        assertEquals(
            "\"withdraw-rules-fixture\"",
            host.env.opened
                .single()
                .second["article_id"]
                .toString(),
        )
        assertEquals(reads, host.api.events)
    }

    @Test fun w48NoHelpTargetShowsNeitherRulesEntry() {
        host.env.help = false
        blocked("blocked_30309")
        rule.onNodeWithTag(WalletTags.WITHDRAW_RULES_LINK).assertDoesNotExist()
        rule.onNodeWithText("查看提现规则").assertDoesNotExist()
        assertEquals(0, host.env.opened.size)
    }

    @Test fun gw21ZeroRemainingDisablesActualChangeClick() = change(0)

    @Test fun gw21NullRemainingStillAllowsChangeWithoutSaving() = change(null)

    @Test fun gw21PositiveRemainingStillAllowsChangeWithoutSaving() = change(1)

    private fun blocked(name: String) {
        host.api.rules = WithdrawVisualFixtures.ok(WithdrawVisualFixtures.rules(name))
        host.launch()
    }

    private fun hiddenSections() {
        rule.onNodeWithTag(WalletTags.WITHDRAW_PAYOUT).assertDoesNotExist()
        rule.onNodeWithTag(WalletTags.WITHDRAW_SLA).assertDoesNotExist()
        rule.onNodeWithText(WalletDraftTexts.text("withdraw.irrevocable")).assertDoesNotExist()
    }

    private fun change(remaining: Long?) {
        host.api.payout =
            WithdrawVisualFixtures.ok(
                JsonObject(
                    WithdrawVisualFixtures.payout() +
                        ("change_remaining_this_month" to (JsonPrimitive(remaining ?: 1L))),
                ),
            )
        host.launch(payout = true)
        if (remaining == null) {
            // 契约报文次数必填；这里只构造合法的可空展示模型，不能为此修改生产解析器。
            rule.runOnIdle {
                val controller = host.payoutModel.controller
                val state = controller.state.value
                val field = controller.javaClass.getDeclaredField("mutable").apply { isAccessible = true }
                val mutable = field.get(controller)
                mutable.javaClass.getMethod("setValue", Any::class.java).apply { isAccessible = true }.invoke(
                    mutable,
                    state.copy(account = checkNotNull(state.account).copy(changeRemaining = null)),
                )
            }
        }
        val change = rule.onNodeWithTag(WalletTags.PAYOUT_CHANGE)
        val before = host.api.events.toList()
        if (remaining == 0L) {
            change.assertIsNotEnabled().performTouchInput { click(center) }
            rule.onNodeWithTag(WalletTags.PAYOUT_BOUND).assertIsDisplayed()
        } else {
            change.assertIsEnabled().performTouchInput { click(center) }
            rule.onNodeWithTag(WalletTags.PAYOUT_LOGON).assertExists()
        }
        assertEquals(before, host.api.events)
        assertEquals(0, host.api.writes().size)
    }
}
