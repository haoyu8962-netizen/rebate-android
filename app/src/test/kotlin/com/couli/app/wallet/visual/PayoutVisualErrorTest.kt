package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.font.FontWeight
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.productPixels
import com.couli.core.wallet.PayoutAccountForm
import com.couli.core.wallet.WalletDraftTexts
import com.couli.feature.wallet.WalletTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** W79–W81：保存失败的展示语气与位置；分类、键的去留、请求次数不变（SensitiveOutcome 只读）。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1100dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PayoutVisualErrorTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { PayoutIdentityVisualHost(rule) }
    private val p by lazy { PayoutIdentityProbe(rule) }

    @After fun close() = host.close()

    @Test fun w79ChangeLimitIsWarningBarNotRedError() = warning("changeLimit", "error.30303.payout_account_change_limit")

    @Test fun w79VerifyLimitIsWarningBarNotRedError() = warning("verifyLimit", "error.30303.payout_account_verify_limit")

    @Test fun w80BankCardTakenErrorSitsUnderCardNumberNotPayee() {
        host.api.payout = PayoutIdentityVisualFixtures.ok(PayoutIdentityVisualFixtures.payout("unbound"))
        host.api.write = PayoutIdentityVisualFixtures.saveError("accountTaken")
        host.launch(PayoutIdentityPage.PAYOUT)
        p.tag(WalletTags.PAYOUT_METHOD_BANK).performTouchInput { click(center) }
        rule.waitForIdle()
        host.type(WalletTags.PAYOUT_CARD, PayoutIdentityVisualFixtures.form("bank", "cardNo"))
        host.type(WalletTags.PAYOUT_BANK_NAME, PayoutIdentityVisualFixtures.form("bank", "bankName"))
        host.type(WalletTags.PAYOUT_PAYEE, PayoutIdentityVisualFixtures.form("bank", "payeeName"))
        p.tag(WalletTags.PAYOUT_SAVE).performTouchInput { click(center) }
        rule.waitForIdle()
        assertEquals(1, host.api.writes().size)
        val flow = currentFlow()
        assertEquals("error.30308", flow.error?.key)
        assertNull("不得改成 20001 字段错误", flow.fieldErrorKey)
        p.tag(WalletTags.PAYOUT_ERROR).assertDoesNotExist()
        val error = p.tag(WalletTags.PAYOUT_FIELD_ERROR_PREFIX + PayoutAccountForm.CARD_NO).fetchSemanticsNode().boundsInRoot
        assertTrue("30308 在卡号框下", error.top >= p.fieldBox(WalletTags.PAYOUT_CARD).bottom)
        assertTrue("30308 在开户行之上", error.bottom <= p.fieldBox(WalletTags.PAYOUT_BANK_NAME).top)
        p.tag(WalletTags.PAYOUT_FIELD_ERROR_PREFIX + PayoutAccountForm.PAYEE_NAME).assertDoesNotExist()
    }

    @Test fun w81SameCardBusyIsNeutralNoticeWithoutSpinnerAndButtonComesBack() {
        submit("busy")
        assertEquals("error.40901", currentFlow().error?.key)
        val bar = p.tag(WalletTags.PAYOUT_ERROR).fetchSemanticsNode()
        val d = density()
        val bg = rule.productPixels().pixel(bar.boundsInRoot.left + 4 * d, bar.boundsInRoot.center.y)
        assertFalse("G-W22 40901 不用红色错误底", near(bg, host.color("status.error.background").argb(), 3))
        assertTrue(
            "G-W22 中性或警告提示条",
            near(bg, host.color("background.muted").argb(), 3) || near(bg, host.color("status.warning.background").argb(), 3),
        )
        val message = WalletDraftTexts.text("error.40901")
        assertTrue("提示文字不是错误红", p.style(message).color != host.color("status.error.text"))
        assertEquals(
            "G-W22 不加常转圈（与实际状态不符）",
            0,
            rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo), true).fetchSemanticsNodes().count {
                it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) == ProgressBarRangeInfo.Indeterminate
            },
        )
        p.tag(WalletTags.PAYOUT_SAVE).assertIsEnabled()
        assertEquals("40901 只发一次，不自动重发", 1, host.api.writes().size)
        assertEquals(1, rule.onAllNodesWithText(message, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    private fun warning(
        fixture: String,
        key: String,
    ) {
        submit(fixture)
        assertEquals("分类与键不变", key, currentFlow().error?.key)
        val node = p.tag(WalletTags.PAYOUT_ERROR).fetchSemanticsNode()
        assertTrue(
            "W79 仍主动播报",
            node.config.getOrNull(SemanticsProperties.LiveRegion) in setOf(LiveRegionMode.Polite, LiveRegionMode.Assertive),
        )
        val d = density()
        val bar = node.boundsInRoot
        val pixels = rule.productPixels()
        assertTrue(
            "W79 警告浅黄底，不是红色错误底",
            near(pixels.pixel(bar.left + 4 * d, bar.center.y), host.color("status.warning.background").argb(), 2),
        )
        val message = WalletDraftTexts.text(key)
        val style = p.style(message)
        assertEquals("W79 警告字色", host.color("status.warning.text"), style.color)
        assertEquals("W79 500", FontWeight.W500, style.fontWeight)
        assertEquals(14f, style.fontSize.value, .01f)
        val left = p.glyphLeft(message)
        val top = p.bounds(message).top
        assertTrue(
            "W79 左侧 20dp 警告色三角",
            pixels.countNear(Rect(left - 30 * d, top, left - 4 * d, top + 22 * d), host.color("status.warning.text").argb(), 40) > 12 * d,
        )
        assertEquals("真实点击保存恰一次", 1, host.api.writes().size)
        assertEquals(1, host.keys)
    }

    private fun submit(fixture: String) {
        host.api.payout = PayoutIdentityVisualFixtures.ok(PayoutIdentityVisualFixtures.payout("boundAlipay"))
        host.api.write = PayoutIdentityVisualFixtures.saveError(fixture)
        host.launch(PayoutIdentityPage.PAYOUT)
        p.tag(WalletTags.PAYOUT_CHANGE).performTouchInput { click(center) }
        rule.waitForIdle()
        host.type(WalletTags.PAYOUT_LOGON, PayoutIdentityVisualFixtures.form("alipay", "alipayAccount"))
        host.type(WalletTags.PAYOUT_PAYEE, PayoutIdentityVisualFixtures.form("alipay", "payeeName"))
        p.tag(WalletTags.PAYOUT_SAVE).performTouchInput { click(center) }
        rule.waitForIdle()
    }

    private fun currentFlow() = host.payoutModel.controller.flow.state.value

    private fun density(): Float = p.layoutOf(WalletTags.PAYOUT_PAGE).density.density
}
