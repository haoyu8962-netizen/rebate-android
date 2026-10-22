package com.couli.app.wallet

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.wallet.WalletDraftTexts
import com.couli.feature.wallet.WalletTags
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 只声明截图入口；作者不执行 record / verify，不生成基准。设计说明列不截图。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WalletVisualSnapshotTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { WalletVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun wallet() = capture("Wallet-light")

    @Test fun frozen() = capture("WalletFrozen-light", "frozen")

    @Test fun negative() = capture("WalletNegative-v2-light", "negative")

    @Test fun risk() = capture("WalletRiskFrozen-v2-light", "risk")

    // G-W02 不实现钱包内轻提示；这张只拍点击前入口，去向与零预取在 BehaviorTest 实点验证。
    @Test fun riskTapEntry() = capture("WalletRiskFrozenTap-entry-GW02-light", "risk")

    @Test fun statesDisabled() = capture("WalletStates-01-disabled-light", "disabled")

    @Test fun statesRiskNoExpiry() = capture("WalletStates-02-risk-no-expiry-light", "risk")

    @Test fun statesNoMonth() = capture("WalletStates-03-no-month-light", "no_month")

    @Test fun statesOverdue() = capture("WalletStates-04-overdue-light", "overdue")

    @Test fun statesReceivedZero() = capture("WalletStates-05-received-zero-GW04-light", "received_zero")

    @Test fun hidden() = capture("WalletHidden-light", hidden = true)

    @Test fun hiddenNegative() = capture("state-negative-hidden-light", "negative", hidden = true)

    @Test fun dark() = capture("WalletDark-dark", dark = true)

    @Test fun negativeDark() = capture("state-negative-dark", "negative", dark = true)

    @Test fun riskDark() = capture("state-risk-dark", "risk", dark = true)

    @Test fun earnings() {
        host.env.earnings = true
        host.env.since = "1.0.0"
        capture("WalletEarningsEntry-light")
    }

    @Test fun stale() = stale(false)

    @Test fun staleDark() = stale(true)

    @Test fun loading() = loading(false)

    @Test fun loadingDark() = loading(true)

    @Test fun error() = error(false)

    @Test fun errorDark() = error(true)

    @Test fun noteEstimate() = help("WalletNoteEstimate-light", "estimated")

    @Test fun noteFrozen() = help("WalletNoteFrozen-light", "frozen", "frozen")

    @Test fun notePaused() = help("WalletNotePaused-light", "pending_paused")

    @Test fun font200At390() = capture("Wallet-font200-w390-light", fontScale = 2f)

    @Test fun font200Dark() = capture("Wallet-font200-w390-dark", dark = true, fontScale = 2f)

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun font200At320() = capture("Wallet-font200-w320-light", fontScale = 2f)

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun font200LongAt320() = capture("state-long-font200-w320-light", "long_amounts", fontScale = 2f)

    @Test
    @Config(qualifiers = "w840dp-h1000dp-xhdpi")
    fun wide() = capture("Wallet-w840-light")

    private fun stale(dark: Boolean) {
        host.seedCache()
        host.api.answer = WalletVisualFixtures.offline()
        capture("WalletStale-GW05-${if (dark) "dark" else "light"}", dark = dark)
    }

    private fun loading(dark: Boolean) {
        host.api.hold = CompletableDeferred()
        capture("state-loading-${if (dark) "dark" else "light"}", dark = dark)
    }

    private fun error(dark: Boolean) {
        host.api.answer = WalletVisualFixtures.failure()
        capture("state-error-${if (dark) "dark" else "light"}", dark = dark)
    }

    private fun help(
        name: String,
        key: String,
        summary: String = "normal",
    ) {
        host.launch(summary, statusBar = true)
        rule.onNodeWithContentDescription(WalletDraftTexts.text("wallet.help.${key}_label")).performClick()
        rule.walletTag(WalletTags.HELP_SHEET).assertExists()
        rule.waitForIdle()
        // 浮层在独立 Window，整屏 capture 包含背景及 Sheet，不能只拍正文节点。
        captureScreenRoboImage("src/test/snapshots/wallet-v/$name.png")
    }

    private fun capture(
        name: String,
        summary: String = "normal",
        dark: Boolean = false,
        fontScale: Float = 1f,
        hidden: Boolean = false,
    ) {
        host.launch(summary, dark, fontScale, hidden, statusBar = true)
        if (host.api.hold == null &&
            host.api.answer is com.couli.core.network.ApiResult.Success
        ) {
            rule.walletTag(WalletTags.WALLET_HERO).assertExists()
        }
        if (host.api.hold != null) rule.onNodeWithContentDescription("加载中").assertExists()
        if (host.api.answer == WalletVisualFixtures.failure()) rule.walletTag(WalletTags.WALLET_ERROR).assertExists()
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(300)
        rule.onRoot().captureRoboImage("src/test/snapshots/wallet-v/$name.png")
    }
}
