package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.productPixels
import com.couli.app.product.textLayout
import com.couli.core.wallet.PayoutAccountForm
import com.couli.feature.wallet.WalletTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1000dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WithdrawVisualErrorTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WithdrawVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun w49BalanceErrorIsBelowAmountWithoutChangingOutcome() = withdrawError(30301)

    @Test fun w49AboveMaxIsBelowAmountWithoutChangingOutcome() = withdrawError(30303, "above_max")

    @Test fun w49BelowMinIsBelowAmountWithoutChangingOutcome() = withdrawError(30303, "below_min")

    @Test fun w49StepErrorIsBelowAmountWithoutChangingOutcome() = withdrawError(30303, "not_multiple")

    @Test fun w49MethodLimitIsBelowAmount() = withdrawError(30303, "above_method_max")

    @Test fun w49NetTooSmallIsBelowAmount() = withdrawError(30303, "net_too_small")

    @Test fun w49NonAmountErrorStaysInTopBar() {
        host.api.write = WithdrawVisualFixtures.failure(30303, """{"reason":"account_frozen"}""")
        host.launch()
        host.input()
        rule.onNodeWithTag(WalletTags.WITHDRAW_SUBMIT).performTouchInput { click(center) }
        rule.onNodeWithTag(WalletTags.WITHDRAW_ERROR).assertExists()
        rule.onNodeWithTag(WalletTags.WITHDRAW_FIELD_ERROR).assertDoesNotExist()
        assertEquals(1, host.api.writes().size)
    }

    @Test fun shared30307IsUnderPayeeNotAccount() = payoutError(30307, WalletTags.PAYOUT_PAYEE, PayoutAccountForm.PAYEE_NAME)

    @Test fun shared30308IsUnderAccountNotPayee() = payoutError(30308, WalletTags.PAYOUT_LOGON, PayoutAccountForm.ALIPAY_LOGON_ID)

    private fun withdrawError(
        code: Int,
        reason: String? = null,
    ) {
        host.api.write = WithdrawVisualFixtures.failure(code, if (reason == null) "{}" else """{"reason":"$reason"}""")
        host.launch()
        host.input()
        rule.onNodeWithTag(WalletTags.WITHDRAW_SUBMIT).performTouchInput { click(center) }
        rule.waitForIdle()
        assertEquals(1, host.api.writes().size)
        assertEquals("10", host.model.controller.state.value.amountText)
        assertNull("必须仍是业务错误，不得伪改为20001", host.flow.state.value.fieldErrorKey)
        assertTrue(
            host.flow.state.value.fieldErrors
                .isEmpty(),
        )
        assertEquals(
            "error.$code" + (reason?.let { ".$it" } ?: ""),
            host.flow.state.value.error
                ?.key,
        )
        rule.onNodeWithTag(WalletTags.WITHDRAW_ERROR).assertDoesNotExist()
        below(WalletTags.WITHDRAW_INPUT, WalletTags.WITHDRAW_FIELD_ERROR)
    }

    private fun payoutError(
        code: Int,
        field: String,
        key: String,
    ) {
        host.api.payout = WithdrawVisualFixtures.ok(WithdrawVisualFixtures.payout("empty"))
        host.api.write = WithdrawVisualFixtures.failure(code)
        host.launch(payout = true)
        rule.onNodeWithTag(WalletTags.PAYOUT_LOGON).performTextReplacement("13800005678")
        rule.onNodeWithTag(WalletTags.PAYOUT_PAYEE).performTextReplacement("张三")
        rule.onNodeWithTag(WalletTags.PAYOUT_SAVE).performTouchInput { click(center) }
        rule.waitForIdle()
        assertEquals(1, host.api.writes().size)
        assertEquals(
            "error.$code",
            host.flow.state.value.error
                ?.key,
        )
        assertNull(host.flow.state.value.fieldErrorKey)
        assertTrue(
            host.flow.state.value.fieldErrors
                .isEmpty(),
        )
        rule.onNodeWithTag(WalletTags.PAYOUT_ERROR).assertDoesNotExist()
        below(field, WalletTags.PAYOUT_FIELD_ERROR_PREFIX + key)
    }

    private fun below(
        field: String,
        error: String,
    ) {
        val errorNode =
            rule
                .onNodeWithTag(error)
                .performScrollTo()
                .assertIsDisplayed()
                .fetchSemanticsNode()
        val input = rule.onNodeWithTag(field).fetchSemanticsNode()
        val d = input.layoutInfo.density.density
        assertTrue("字段错误须在本字段紧邻下方", errorNode.boundsInRoot.top >= input.boundsInRoot.bottom)
        assertTrue("字段与错误间距不能隔着其他卡", errorNode.boundsInRoot.top - input.boundsInRoot.bottom <= 36 * d)
        val message = checkNotNull(host.flow.state.value.error).let { host.deps.texts.text(it.key) }
        val layout = rule.onNodeWithText(message, useUnmergedTree = true).textLayout()
        assertEquals("W50 14sp", 14f, layout.layoutInput.style.fontSize.value, .01f)
        assertEquals(host.colors.getValue("status.error.text"), layout.layoutInput.style.color)
        val textBounds = rule.onNodeWithText(message, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val icon = Rect(textBounds.left - 20 * d, textBounds.center.y - 8 * d, textBounds.left - 4 * d, textBounds.center.y + 8 * d)
        assertTrue(
            "W50字段提示左侧须有16dp错误图标",
            rule.productPixels().recordColorCount(icon, host.colors.getValue("status.error.text").toArgb()) > 8,
        )
        assertEquals(message, input.config.getOrNull(SemanticsProperties.Error))
        host.settleEstimate()
        assertEquals("同一错误只主动播报一次", 1, host.spoken.count { it == message })
        assertEquals(1, host.api.writes().size)
    }
}
