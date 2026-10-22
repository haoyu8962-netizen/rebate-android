package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.wallet.WalletTags
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 只登记截图入口；测试作者不得执行 record，浮层必须含独立窗口。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WithdrawVisualSnapshotTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WithdrawVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun board1() = shot("normal", "Withdraw-light")

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun board2() = shot("calculating", "WithdrawCalculating-GW12-light")

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun board3() = shot("estimate_failed", "WithdrawEstimateFailed-light")

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun board4() = shot("help", "WithdrawNoteNet-GW27-light")

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun board5() = shot("blocked_30304", "WithdrawRealName-light")

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun board6() = shot("submitting", "WithdrawSubmitting-light")

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun board7() = shot("risk", "WithdrawBlocked-light")

    @Test
    @Config(qualifiers = "w390dp-h1080dp-xhdpi")
    fun board8() = shot("pending", "WithdrawPendingConfirm-light")

    @Test
    @Config(qualifiers = "w390dp-h1080dp-xhdpi")
    fun board9() = shot("busy", "WithdrawPendingBusy-light")

    @Test
    @Config(qualifiers = "w390dp-h1080dp-xhdpi")
    fun board10() = shot("still", "WithdrawPendingStill-light")

    @Test
    @Config(qualifiers = "w390dp-h1080dp-xhdpi")
    fun board11() = shot("abandon", "WithdrawAbandonConfirm-light")

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun board12() = shot("abandoned", "WithdrawAbandoned-light")

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun board13() = shot("completed", "WithdrawAbandonCompleted-v2-GW18-light")

    @Test fun pageState1() = shot("blocked_30301", "WithdrawPageStates-blocked_30301-light")

    @Test fun pageState2() = shot("blocked_30302", "WithdrawPageStates-blocked_30302-light")

    @Test fun pageState3() = shot("blocked_30304", "WithdrawPageStates-blocked_30304-light")

    @Test fun pageState4() = shot("blocked_30305", "WithdrawPageStates-blocked_30305-light")

    @Test fun pageState5() = shot("blocked_30306", "WithdrawPageStates-blocked_30306-light")

    @Test fun pageState6() = shot("blocked_30307", "WithdrawPageStates-blocked_30307-light")

    @Test fun pageState7() = shot("blocked_30308", "WithdrawPageStates-blocked_30308-light")

    @Test fun pageState8() = shot("blocked_30309", "WithdrawPageStates-blocked_30309-light")

    @Test fun pageState9() = shot("blocked_10004", "WithdrawPageStates-blocked_10004-light")

    @Test fun pageState10() = shot("blocked_below_min", "WithdrawPageStates-blocked_below_min-light")

    @Test fun pageState11() = shot("blocked_not_multiple", "WithdrawPageStates-blocked_not_multiple-light")

    @Test fun pageState12() = shot("blocked_above_max", "WithdrawPageStates-blocked_above_max-light")

    @Test fun pageState13() = shot("blocked_above_method_max", "WithdrawPageStates-blocked_above_method_max-light")

    @Test fun pageState14() = shot("blocked_net_too_small", "WithdrawPageStates-blocked_net_too_small-light")

    @Test fun pageState15() = shot("blocked_daily_count", "WithdrawPageStates-blocked_daily_count-light")

    @Test fun pageState16() = shot("blocked_monthly_count", "WithdrawPageStates-blocked_monthly_count-light")

    @Test fun pageState17() = shot("blocked_payee_daily_users", "WithdrawPageStates-blocked_payee_daily_users-light")

    @Test fun pageState18() = shot("blocked_self_purchase_required", "WithdrawPageStates-blocked_self_purchase_required-light")

    @Test fun pageState19() = shot("blocked_account_frozen", "WithdrawPageStates-blocked_account_frozen-light")

    @Test fun pageState20() = shot("blocked_payee_daily_count", "WithdrawPageStates-blocked_payee_daily_count-light")

    @Test fun pageState21() = shot("blocked_future", "WithdrawPageStates-blocked_future-light")

    @Test fun loadingDark() = shot("loading", "state-withdraw-loading-dark", dark = true)

    @Test fun errorDark() = shot("error", "state-withdraw-error-dark", dark = true)

    @Test fun normalDark() = shot("normal", "WithdrawDark-dark", dark = true)

    @Test fun pendingDark() = shot("pending", "state-withdraw-pending-dark", dark = true)

    @Test fun busyDark() = shot("busy", "state-withdraw-busy-dark", dark = true)

    @Test fun stillDark() = shot("still", "state-withdraw-still-dark", dark = true)

    @Test fun riskDark() = shot("risk", "state-withdraw-risk-dark", dark = true)

    @Test fun abandonDark() = shot("abandon", "state-withdraw-abandon-dark", dark = true)

    @Test fun evidenceLoading() = shot("loading", "WithdrawPageStates-loading-light")

    @Test fun evidenceError() = shot("error", "WithdrawPageStates-error-light")

    @Test fun evidenceSlaAuto() = shot("sla_auto", "WithdrawPageStates-sla_auto-light")

    @Test fun evidenceSlaDefault() = shot("sla_default", "WithdrawPageStates-sla_default-light")

    @Test fun evidenceAmountError() = shot("amount_error", "WithdrawPageStates-amount_error-light")

    @Test fun evidenceAboveMax() = shot("above_max", "WithdrawPageStates-above_max-light")

    @Test fun evidenceNameError() = shot("name_error", "WithdrawPageStates-name_error-light")

    @Test fun evidenceAccountError() = shot("account_error", "WithdrawPageStates-account_error-light")

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun stressFont200W390() = shot("stress", "Withdraw-stress-font200-w390-light", fontScale = 2f)

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun pendingFont200W390() = shot("pending", "Withdraw-pending-font200-w390-light", fontScale = 2f)

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun abandonFont200W390() = shot("abandon", "Withdraw-abandon-font200-w390-light", fontScale = 2f)

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun stressFont200W320() = shot("stress", "Withdraw-stress-font200-w320-light", fontScale = 2f)

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun pendingFont200W320() = shot("pending", "Withdraw-pending-font200-w320-light", fontScale = 2f)

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun abandonFont200W320() = shot("abandon", "Withdraw-abandon-font200-w320-light", fontScale = 2f)

    @Test
    @Config(qualifiers = "w840dp-h1000dp-xhdpi")
    fun wide() = shot("normal", "Withdraw-w840-light")

    @Test fun stressFont200Dark() = shot("stress", "Withdraw-stress-font200-w390-dark", dark = true, fontScale = 2f)

    private fun shot(
        scene: String,
        name: String,
        dark: Boolean = false,
        fontScale: Float = 1f,
    ) {
        val overlay = WithdrawVisualScenes(rule, host).show(scene, dark = dark, fontScale = fontScale)
        val path = "src/test/snapshots/withdraw-v/$name.png"
        if (overlay) {
            captureScreenRoboImage(path)
        } else {
            if (fontScale > 1f) {
                rule.onRoot().captureRoboImage("src/test/snapshots/withdraw-v/$name-top.png")
                val target = if (scene == "pending") WalletTags.PENDING_RECORDS else WalletTags.WITHDRAW_QUICK_PREFIX + "100000000"
                rule.onNodeWithTag(target).performScrollTo()
            }
            rule.onRoot().captureRoboImage(path)
        }
    }
}
