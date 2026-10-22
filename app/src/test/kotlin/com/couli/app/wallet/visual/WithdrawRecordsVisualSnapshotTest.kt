package com.couli.app.wallet.visual

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.wallet.WalletTags
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CompletableDeferred
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
class WithdrawRecordsVisualSnapshotTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WithdrawRecordsVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun listLight() = page("WithdrawRecords-v2-light", "list", null)

    @Test
    fun pending() = page("WithdrawRecordsPending-v2-GW10-light", "list", "PENDING")

    @Test
    fun paid() = page("WithdrawRecordsPaid-v2-GW10-light", "list", "PAID")

    @Test
    fun failed() = page("WithdrawRecordsFailed-v2-light", "list", "FAILED")

    @Test
    fun autoDeciding() = page("WithdrawRecordsAutoDeciding-v2-GW09-light", "board_auto_deciding", "AUTO_DECIDING")

    @Test
    fun autoWaiting() = page("WithdrawRecordsAutoWaiting-v2-GW09-light", "board_auto_waiting", "AUTO_WAITING")

    @Test
    fun paying() = page("WithdrawRecordsPaying-v2-GW09-light", "board_paying", "PAYING")

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun empty() = page("WithdrawRecordsEmpty-light", "empty", null)

    @Test fun evidenceStates1() = page("WithdrawRecordsStates-v2-01-auto-deciding-GW09-light", "auto_deciding")

    @Test fun evidenceStates2() = page("WithdrawRecordsStates-v2-06-auto-waiting-GW09-light", "auto_waiting")

    @Test fun evidenceStates3() = page("WithdrawRecordsStates-v2-07-pending-fallback-GW10-light", "pending")

    @Test fun evidenceStates4() = page("WithdrawRecordsStates-v2-09-approved-GW09-light", "approved")

    @Test fun evidenceStates5() = page("WithdrawRecordsStates-v2-10-paying-GW09-light", "paying")

    @Test fun evidenceStates6() = page("WithdrawRecordsStates-v2-12-paid-light", "paid")

    @Test fun evidenceStates7() = page("WithdrawRecordsStates-v2-13-rejected-light", "rejected")

    @Test fun evidenceStates8() = page("WithdrawRecordsStates-v2-14-failed-account-light", "failed")

    @Test fun evidenceStates9() = page("WithdrawRecordsStates-v2-15-failed-other-GW09-light", "failed_other")

    @Test fun evidenceMore1() = page("WithdrawRecordsMore-01-tax-GW09-light", "tax")

    @Test fun evidenceMore2() = page("WithdrawRecordsMore-02-manual-GW09-light", "manual")

    @Test fun evidenceMore3() = page("WithdrawRecordsMore-03-negative-balance-light", "reject_negative_balance")

    @Test fun evidenceMore4() = page("WithdrawRecordsMore-04-risk-light", "reject_risk_suspect")

    @Test fun evidenceMore5() = page("WithdrawRecordsMore-04-order-light", "reject_order_abnormal")

    @Test fun evidenceMore6() = page("WithdrawRecordsMore-04-payee-light", "reject_payee_info_invalid")

    @Test fun evidenceMore7() = page("WithdrawRecordsMore-04-user-request-GW09-light", "reject_user_request")

    @Test fun evidenceMore8() = page("WithdrawRecordsMore-04-other-light", "reject_other")

    @Test fun evidenceMore9() = page("WithdrawRecordsMore-05-unknown-light", "unknown")

    @Test fun dark() = page("WithdrawRecordsDark-GW10-dark", "list", "PAID", dark = true)

    @Test fun font200At390() = page("WithdrawRecordsPaid-v2-font200-w390-light", "stress", "STRESS", fontScale = 2f)

    @Test fun font200DarkAt390() = page("WithdrawRecordsPaid-v2-font200-w390-dark", "stress", "STRESS", dark = true, fontScale = 2f)

    @Test
    @Config(qualifiers = "w320dp-h1000dp-xhdpi")
    fun font200At320() = page("WithdrawRecordsPaid-v2-font200-w320-light", "stress", "STRESS", fontScale = 2f)

    @Test
    @Config(qualifiers = "w840dp-h1000dp-xhdpi")
    fun wide() = page("WithdrawRecords-w840-light", "list", "PAID")

    @Test fun loadingLight() = loading(false)

    @Test fun loadingDark() = loading(true)

    @Test fun errorLight() = error(false)

    @Test fun errorDark() = error(true)

    @Test fun emptyDark() = page("WithdrawRecordsEmpty-dark", "empty", dark = true)

    @Test fun loadingMore() {
        host.launch("pagination", shell = true)
        assertEquals(true, host.controller.state.value.loadingMore)
        capture("state-records-loading-more-light")
    }

    @Test fun paginationError() {
        host.api.nextHold = null
        host.api.nextAnswer = WithdrawRecordsVisualFixtures.failure()
        host.launch("pagination", shell = true)
        rule.onNodeWithTag(WalletTags.RECORDS_LOAD_MORE).assertIsDisplayed()
        capture("state-records-more-error-light")
    }

    @Test fun focusedDifferentMonth() {
        host.launch("list", focus = "FOCUS", shell = true)
        rule.onNodeWithTag(WalletTags.RECORD_DETAIL_PREFIX + "FOCUS").assertIsDisplayed()
        capture("state-records-focused-different-month-light")
    }

    private fun page(
        name: String,
        fixture: String,
        expand: String? = null,
        dark: Boolean = false,
        fontScale: Float = 1f,
    ) {
        host.launch(fixture, dark = dark, fontScale = fontScale, shell = true)
        if (expand != null) {
            host.toggle(expand)
            rule.onNodeWithTag(WalletTags.RECORDS_LIST).performScrollToIndex(0)
        }
        if (fontScale == 2f) {
            capture("$name-top")
            rule.onNodeWithTag(WalletTags.RECORD_TRADE_NO, useUnmergedTree = true).performScrollTo()
            rule.onNodeWithTag(WalletTags.RECORD_COPY).assertIsDisplayed()
        }
        capture(name)
    }

    private fun loading(dark: Boolean) {
        host.api.hold = CompletableDeferred()
        host.launch(dark = dark, shell = true)
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(300)
        capture("state-records-loading-${if (dark) "dark" else "light"}")
    }

    private fun error(dark: Boolean) {
        host.api.answer = WithdrawRecordsVisualFixtures.failure()
        host.launch(dark = dark, shell = true)
        rule.onNodeWithTag(WalletTags.RECORDS_ERROR).assertIsDisplayed()
        capture("state-records-error-${if (dark) "dark" else "light"}")
    }

    private fun capture(name: String) {
        rule.onNodeWithTag(WalletTags.RECORDS_PAGE).assertIsDisplayed()
        rule.onRoot().captureRoboImage("src/test/snapshots/records-v/$name.png")
    }
}
