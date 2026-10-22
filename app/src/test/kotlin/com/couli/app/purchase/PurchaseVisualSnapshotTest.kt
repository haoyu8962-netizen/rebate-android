package com.couli.app.purchase

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.product.ProductVisualFixtures
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** B1–B15。只提供截图入口；作者不运行此类、不录基准。含 Dialog/Sheet 必须整屏捕获所有窗口。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h956dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PurchaseVisualSnapshotTest {
    @get:Rule val rule = createComposeRule()
    private val standard = lazy { PurchaseVisualTestHost(rule) }
    private val actualApp = lazy { PurchaseVisualAppHost(rule) }
    private val host get() = standard.value

    @After fun close() {
        if (standard.isInitialized()) host.close()
        if (actualApp.isInitialized()) actualApp.value.close()
    }

    @Test fun opening() = capture("opening", "ProductBuyOpening-v2-light")

    @Test fun timeout() = capture("timeout", "ProductBuyTimeout-v2-light")

    @Test fun auth() = capture("auth", "ProductAuthSheet-v2-light")

    @Test fun declined() = capture("declined", "ProductAuthDeclined-v2-light")

    @Test fun failed() = capture("failed", "ProductAuthFailed-v2-light")

    @Test fun unavailable() = capture("unavailable", "ProductAuthUnavailable-v2-light")

    @Test fun blocked() = capture("blocked", "ProductAuthBlocked-light")

    @Test fun blocked30103() = capture("blocked30103", "state-30103-light")

    @Test fun blocked30151() = capture("blocked30151", "state-30151-light")

    @Test fun unconfirmed() = capture("unconfirmed", "ProductPriceUnconfirmed-light")

    @Test fun maintenance() = capture("maintenance", "ProductConvertPaused-v2-light")

    @Test fun browser() = capture("browser", "ProductJumpBrowser-v2-light")

    @Test fun sdk() = capture("sdk", "ProductJumpSdkFailed-v2-light")

    @Test fun tip() = capture("tip", "ProductJumpTip-v2-light")

    @Test fun price() = capture("price", "ProductPriceChanged-v2-light")

    @Test fun coupon() = capture("coupon", "ProductCouponGone-light")

    @Test fun rebate() = capture("rebate", "ProductRebateGone-v2-light")

    @Test fun price_rebate() = capture("price_rebate", "ProductPriceChangedRebateGone-v2-light")

    @Test fun compare() = capture("compare", "ProductPriceCompareNoRebate-v2-light")

    @Test fun cached() = capture("cached", "ProductRequoteCached-light")

    @Test fun cancel_price() = capture("cancel_price", "ProductPriceChangedCancelled-v2-light")

    @Test fun cancel_rebate() = capture("cancel_rebate", "ProductRebateGoneCancelled-v2-light")

    @Test fun cancel_price_rebate() = capture("cancel_price_rebate", "ProductPriceChangedRebateGoneCancelled-v2-light")

    @Test fun cancel_compare() = capture("cancel_compare", "ProductPriceCompareCancelled-v2-light")

    @Test fun authDark() = capture("auth", "ProductAuthSheetDark-dark", dark = true)

    @Test fun tipDark() = capture("tip", "ProductJumpTipDark-dark", dark = true)

    @Test fun priceDark() = capture("price", "ProductPriceChangedDark-dark", dark = true)

    @Test fun blockedDark() = capture("blocked", "ProductAuthBlocked-dark", dark = true)

    @Test fun openingDark() = capture("opening", "ProductBuyOpening-v2-dark", dark = true)

    @Test fun cancel_priceDark() = capture("cancel_price", "ProductPriceChangedCancelled-v2-dark", dark = true)

    @Test
    @Config(qualifiers = "w390dp-h667dp-xhdpi")
    fun authFont200() = capture("auth", "state-auth-font200-h667-light", fontScale = 2f)

    @Test
    @Config(qualifiers = "w390dp-h667dp-xhdpi")
    fun tipFont200() = capture("tip", "state-tip-font200-h667-light", fontScale = 2f)

    @Test
    @Config(qualifiers = "w390dp-h956dp-xhdpi")
    fun unconfirmedFont200() = capture("unconfirmed", "state-unconfirmed-font200-h956-light", fontScale = 2f)

    @Test
    @Config(qualifiers = "w390dp-h956dp-xhdpi")
    fun compareFont200() = capture("compare", "state-compare-font200-h956-light", fontScale = 2f)

    @Test
    @Config(qualifiers = "w390dp-h1000dp-xhdpi")
    fun notInstalled() = installed(false)

    @Test
    @Config(qualifiers = "w390dp-h1000dp-xhdpi")
    fun notInstalledDark() = installed(true)

    @Test
    @Config(qualifiers = "w390dp-h1130dp-xhdpi")
    fun pendingNotify() = pending("ProductPendingTrack-v2-light", claim = false, decided = false)

    @Test
    @Config(qualifiers = "w390dp-h1050dp-xhdpi")
    fun pendingDecided() = pending("ProductPendingTrackDecided-v2-light", claim = false, decided = true)

    @Test
    @Config(qualifiers = "w390dp-h1082dp-xhdpi")
    fun pendingClaim() = pending("ProductPendingTrackClaim-light", claim = true, decided = true)

    @Test
    @Config(qualifiers = "w390dp-h1082dp-xhdpi")
    fun pendingDark() = pending("ProductPendingTrackClaim-dark", claim = true, decided = true, dark = true)

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun offShelfAfterOpen() = capture("off_shelf", "ProductOffShelf-v3-light")

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun offShelfAfterOpenDark() = capture("off_shelf", "ProductOffShelfDark-dark", dark = true)

    private fun capture(
        scenario: String,
        name: String,
        dark: Boolean = false,
        fontScale: Float = 1f,
    ) {
        host.fillQuote =
            when (scenario.removePrefix("cancel_")) {
                "price" -> ProductVisualFixtures.card("tb_price_changed")
                "price_rebate" -> ProductVisualFixtures.card("tb_price_changed_rebate_gone")
                else -> null
            }
        host.showTip = scenario == "tip"
        host.explanation = scenario.contains("compare")
        val card =
            when {
                scenario.contains("compare") -> "pdd_coupon"
                scenario == "browser" -> "jd_no_coupon_single"
                else -> "tb_coupon_range"
            }
        host.launch(card, dark, fontScale, shell = true)
        host.prepare(scenario, rule)
        host.assertScenarioReady(scenario)
        host.counts(open = 1, jumps = if (scenario == "cached") 1 else 0, auth = (if (scenario == "failed") 1 else 0) to 0)
        shot(name)
    }

    private fun installed(dark: Boolean) {
        val app = actualApp.value
        app.launch(dark = dark)
        app.buy()
        app.answer()
        check(app.ports.copies.size == 1)
        shot("ProductJumpNotInstalled-v2-${if (dark) "dark" else "light"}")
    }

    private fun pending(
        name: String,
        claim: Boolean,
        decided: Boolean,
        dark: Boolean = false,
    ) {
        val pending = PurchaseVisualPending(host, rule)
        pending.permission = if (decided) "granted" else "not_determined"
        host.extraBanner = { PurchaseVisualPendingSlot(pending) }
        host.launch(dark = dark, shell = true)
        pending.show(claim)
        rule.assertPurchasePendingSlot()
        host.counts(open = 1, jumps = 1)
        shot(name)
    }

    private fun shot(name: String) {
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(300)
        captureScreenRoboImage("src/test/snapshots/product-v/purchase/$name.png")
    }
}
