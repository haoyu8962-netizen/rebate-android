package com.couli.app.product

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.product.ProductTags
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** D1–D14。只声明截图；测试作者不运行 record，也不生成/修改任何基准。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h956dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProductVisualSnapshotTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { ProductVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun coupon() = capture("Product-v2-light")

    @Test fun noCoupon() = capture("ProductJd-light", "jd_no_coupon_single")

    @Test fun pdd() = capture("state-pdd-light", "pdd_coupon")

    @Test fun presale() = capture("state-presale-light", "presale")

    @Test fun compare() = capture("ProductPriceCompareCancelled-v2-light", "pdd_price_compare")

    @Test fun rebateGone() = capture("ProductRebateGoneCancelled-v2-light", "tb_rebate_gone")

    @Test fun priceChanged() = capture("ProductPriceChangedCancelled-v2-light", "tb_price_changed")

    @Test fun priceAndRebateChanged() = capture("ProductPriceChangedRebateGoneCancelled-v2-light", "tb_price_changed_rebate_gone")

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun offShelf() = capture("ProductOffShelf-v3-light", "tb_off_shelf")

    @Test fun dark() = capture("ProductDark-dark", dark = true)

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun offShelfDark() = capture("ProductOffShelfDark-dark", "tb_off_shelf", dark = true)

    @Test fun amountUnknown() = capture("state-amount-unknown-light", "amount_unknown")

    @Test fun unavailable() = capture("state-price-unavailable-light", "price_unavailable")

    @Test fun stale() = capture("state-stale-light", "stale")

    @Test fun adLabel() = capture("state-ad-label-light", "ad_label")

    @Test fun guest() {
        host.loggedIn = false
        capture("state-guest-light", "login_required")
    }

    @Test fun maintenance() {
        host.status = "maintenance"
        capture("state-maintenance-light")
    }

    @Test fun notLaunched() {
        host.status = "not_launched"
        capture("state-not-launched-light")
    }

    @Test fun loading() = loadingSnapshot(false)

    @Test fun loadingDark() = loadingSnapshot(true)

    @Test fun error() = errorSnapshot(50401, "state-error-light")

    @Test fun expired() = errorSnapshot(30143, "state-ref-expired-light")

    @Test fun entryOffShelf() = errorSnapshot(30141, "state-off-shelf-entry-light")

    @Test fun errorDark() = errorSnapshot(50401, "state-error-dark", dark = true)

    @Test fun font200At390() = capture("Product-v2-font200-w390-light", fontScale = 2f)

    @Test fun font200Dark() = capture("ProductDark-font200-w390-dark", dark = true, fontScale = 2f)

    @Test
    @Config(qualifiers = "w320dp-h956dp-xhdpi")
    fun font200At320() = capture("Product-v2-font200-w320-light", fontScale = 2f)

    @Test
    @Config(qualifiers = "w320dp-h956dp-xhdpi")
    fun singleAmountFont200At320() = capture("ProductJd-font200-w320-light", "jd_no_coupon_single", fontScale = 2f)

    @Test
    @Config(qualifiers = "w840dp-h956dp-xhdpi")
    fun wide() = capture("Product-v2-w840-light")

    private fun loadingSnapshot(dark: Boolean) {
        host.api.hold = CompletableDeferred()
        capture("state-loading-${if (dark) "dark" else "light"}", dark = dark, stateTag = ProductTags.LOADING)
    }

    private fun errorSnapshot(
        code: Int,
        name: String,
        dark: Boolean = false,
    ) {
        host.api.fail(code)
        capture(name, dark = dark, stateTag = ProductTags.ERROR)
    }

    private fun capture(
        name: String,
        card: String = "tb_coupon_range",
        dark: Boolean = false,
        fontScale: Float = 1f,
        stateTag: String = ProductTags.BUY,
    ) {
        host.launch(card, dark = dark, fontScale = fontScale, statusBar = true)
        rule.onNodeWithTag(stateTag).assertExists()
        // 固定骨架 shimmer 时刻；不等待永不结束的动画，也不把状态文字当数据就绪的替代品。
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(300)
        rule.onRoot().captureRoboImage("src/test/snapshots/product-v/$name.png")
    }
}
