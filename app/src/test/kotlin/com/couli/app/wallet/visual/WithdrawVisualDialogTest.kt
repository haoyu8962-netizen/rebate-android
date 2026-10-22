package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.textLayout
import com.couli.core.network.PendingTarget
import com.couli.core.wallet.WalletDraftTexts
import com.couli.feature.wallet.WalletTags
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1100dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WithdrawVisualDialogTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WithdrawVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun withdrawCancelAndAbandonAreNotReversed() = abandon(false)

    @Test fun payoutCancelAndAbandonAreNotReversed() = abandon(true)

    @Test fun withdrawConfirmUsesOriginalKeyExactlyOnce() = confirm(false)

    @Test fun payoutConfirmUsesOriginalKeyExactlyOnce() = confirm(true)

    @Test fun withdrawRiskAcknowledgeAndAppealDoNotSubmitAgain() = risk(false)

    @Test fun payoutRiskAcknowledgeAndAppealDoNotSaveAgain() = risk(true)

    @Test fun abandonSystemBackDoesNotWrite() {
        seed(false)
        tapTag(WalletTags.PENDING_ABANDON)
        rule.runOnUiThread { ShadowDialog.getLatestDialog().onBackPressed() }
        rule.waitForIdle()
        assertFalse(host.flow.state.value.abandonConfirm)
        assertEquals(emptyList<PendingTarget>(), host.api.abandons)
        assertEquals(0, host.api.writes().size)
    }

    @Test fun abandonScrimDismissesAsCancelWithoutRequest() {
        seed(false)
        tapTag(WalletTags.PENDING_ABANDON)
        dismissOutside()
        assertFalse(host.flow.state.value.abandonConfirm)
        assertEquals(0, host.api.abandons.size + host.api.confirms.size + host.api.writes().size)
    }

    @Test fun riskScrimDismissesAsAcknowledgeWithoutAppealOrAnotherWrite() {
        host.launch()
        host.input()
        tapTag(WalletTags.WITHDRAW_SUBMIT)
        dismissOutside()
        assertEquals(null, host.flow.state.value.riskDialog)
        assertEquals(0, host.env.appeals.size)
        assertEquals(1, host.api.writes().size)
    }

    private fun dismissOutside() {
        rule.runOnUiThread {
            val dialog = ShadowDialog.getLatestDialog()
            // Compose 1.11.4 要完整 DOWN/UP 才把遮罩手势交给 onDismissRequest。
            listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP).forEach { action ->
                val event = android.view.MotionEvent.obtain(0, 16, action, -100f, -100f, 0)
                try {
                    dialog.onTouchEvent(event)
                } finally {
                    event.recycle()
                }
            }
        }
        rule.waitForIdle()
    }

    @Test fun oldAbandonCallbackAfterSameAccountReloginCannotWrite() {
        seed(false)
        tapTag(WalletTags.PENDING_ABANDON)
        val callback =
            rule
                .onNode(hasText(text("pending_confirm.abandon.ok")) and hasClickAction())
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        rule.runOnIdle {
            host.env.epoch++
            callback()
        }
        assertEquals(0, host.api.abandons.size)
        assertEquals(0, host.api.writes().size)
        assertEquals("visual-withdraw-original", host.api.pending?.key)
    }

    @Test fun oldConfirmCallbackAfterAccountSwitchCannotWrite() {
        seed(false)
        val callback =
            rule
                .onNodeWithTag(WalletTags.PENDING_CONFIRM)
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        rule.runOnIdle {
            host.env.user = "another-user"
            callback()
        }
        assertEquals(0, host.api.confirms.size)
        assertEquals(0, host.api.writes().size)
    }

    @Test fun w61ActualAbandonPrimarySlotIsCancel() {
        seed(false)
        tapTag(WalletTags.PENDING_ABANDON)
        primary("pending_confirm.abandon.cancel", "pending_confirm.abandon.ok")
    }

    @Test fun w60ActualRiskPrimarySlotIsAppeal() {
        host.launch()
        host.input()
        tapTag(WalletTags.WITHDRAW_SUBMIT)
        primary("cs.appeal_entry", "withdraw.risk_blocked.ok")
    }

    private fun primary(
        primaryKey: String,
        secondaryKey: String,
    ) {
        val main = rule.onNodeWithText(text(primaryKey), useUnmergedTree = true)
        val second = rule.onNodeWithText(text(secondaryKey), useUnmergedTree = true)
        assertEquals(
            host.colors
                .getValue("button.primary.default.text"),
            main
                .textLayout()
                .layoutInput.style.color,
        )
        assertTrue(
            "默认字号主右次左",
            main
                .fetchSemanticsNode()
                .boundsInRoot.center.x >
                second
                    .fetchSemanticsNode()
                    .boundsInRoot.center.x,
        )
    }

    private fun abandon(payout: Boolean) {
        seed(payout)
        val before = host.api.events.toList()
        tapTag(WalletTags.PENDING_ABANDON)
        assertEquals("打开确认框不能先放弃", before, host.api.events)
        tapText("pending_confirm.abandon.cancel")
        assertEquals("再想想零请求", before, host.api.events)
        assertFalse(host.flow.state.value.abandonConfirm)
        tapTag(WalletTags.PENDING_ABANDON)
        host.api.pendingHold = CompletableDeferred()
        tapText("pending_confirm.abandon.ok")
        assertEquals(1, host.api.abandons.size)
        assertEquals(0, host.api.confirms.size)
        assertEquals(0, host.api.writes().size)
        assertEquals(before + "abandon:${if (payout) "savePayoutAccount" else "createWithdrawal"}", host.api.events)
        ticket(host.api.abandons.single(), payout)
        rule.onNodeWithTag(WalletTags.PENDING_CONFIRM).assertIsNotEnabled()
        rule.onNodeWithTag(WalletTags.PENDING_ABANDON).assertIsNotEnabled()
        rule.onNodeWithTag(WalletTags.PENDING_ABANDON).performClick()
        assertEquals("在途重复点击仍一次", 1, host.api.abandons.size)
    }

    private fun confirm(payout: Boolean) {
        seed(payout)
        val before = host.api.events.toList()
        host.api.pendingHold = CompletableDeferred()
        tapTag(WalletTags.PENDING_CONFIRM)
        assertEquals(before + "confirm:${if (payout) "savePayoutAccount" else "createWithdrawal"}", host.api.events)
        assertEquals(1, host.api.confirms.size)
        assertEquals(0, host.api.abandons.size)
        assertEquals(0, host.api.writes().size)
        ticket(host.api.confirms.single(), payout)
        assertEquals(listOf("fixture-existing-token"), host.api.confirmTokens)
        rule.onNodeWithTag(WalletTags.PENDING_CONFIRM).assertIsNotEnabled().performClick()
        assertEquals(1, host.api.confirms.size)
    }

    private fun risk(payout: Boolean) {
        host.launch(payout = payout)
        if (payout) {
            tapTag(WalletTags.PAYOUT_CHANGE)
            rule.onNodeWithTag(WalletTags.PAYOUT_LOGON).performScrollTo().performTextReplacement("13800005678")
            rule.onNodeWithTag(WalletTags.PAYOUT_PAYEE).performScrollTo().performTextReplacement("张三")
            tapTag(WalletTags.PAYOUT_SAVE)
        } else {
            host.input()
            tapTag(WalletTags.WITHDRAW_SUBMIT)
        }
        assertEquals(1, host.api.writes().size)
        assertNotNull(host.flow.state.value.riskDialog)
        tapText("withdraw.risk_blocked.ok")
        assertEquals(0, host.env.appeals.size)
        assertEquals(1, host.api.writes().size)
        tapTag(if (payout) WalletTags.PAYOUT_SAVE else WalletTags.WITHDRAW_SUBMIT)
        assertEquals(2, host.api.writes().size)
        tapText("cs.appeal_entry")
        assertEquals(listOf(if (payout) "PayoutAccount" else "Withdraw"), host.env.appeals)
        assertEquals(2, host.api.writes().size)
        assertEquals(0, host.api.confirms.size + host.api.abandons.size)
    }

    private fun seed(payout: Boolean) {
        host.api.pending = WithdrawVisualFixtures.pending(payout)
        host.launch(payout = payout)
        assertEquals(0, host.api.writes().size)
    }

    private fun ticket(
        target: PendingTarget,
        payout: Boolean,
    ) {
        assertEquals(WithdrawVisualFixtures.pending(payout).key, target.key)
        assertEquals("withdraw-visual-user", target.account)
        assertNotNull(target.ownership)
        assertTrue(checkNotNull(target.ownership).stillOwned())
        host.env.epoch++
        assertFalse("身份票据不能读取点击时的新世代", checkNotNull(target.ownership).stillOwned())
    }

    private fun text(key: String) = WalletDraftTexts.text(key)

    private fun tapText(key: String) {
        rule.onNodeWithText(text(key)).performTouchInput { click(center) }
        rule.waitForIdle()
    }

    private fun tapTag(tag: String) {
        rule.onNodeWithTag(tag).performTouchInput { click(center) }
        rule.waitForIdle()
    }
}
