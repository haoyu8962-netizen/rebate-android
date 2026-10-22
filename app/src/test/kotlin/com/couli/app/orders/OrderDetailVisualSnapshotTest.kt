package com.couli.app.orders

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.orders.OrderTags
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 只声明快照入口；本轮禁止执行 record/verify。原因稿截真实页面的状态卡，不复制画板。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OrderDetailVisualSnapshotTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { OrderDetailVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun estimatedLight() = page("OrderDetailEstimated-v2-light", "detail_estimated", dark = false)

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun estimatedDark() = page("OrderDetailEstimated-v2-dark", "detail_estimated", dark = true)

    @Test
    @Config(qualifiers = "w390dp-h900dp-xhdpi")
    fun settledLight() = page("OrderDetailSettled-v2-light", "detail_settled", dark = false)

    @Test
    @Config(qualifiers = "w390dp-h900dp-xhdpi")
    fun settledDark() = page("OrderDetailSettled-v2-dark", "detail_settled", dark = true)

    @Test
    @Config(qualifiers = "w390dp-h920dp-xhdpi")
    fun partclawedLight() = page("OrderDetailPartClawed-v2-light", "detail_part_clawed", dark = false)

    @Test
    @Config(qualifiers = "w390dp-h920dp-xhdpi")
    fun partclawedDark() = page("OrderDetailPartClawed-v2-dark", "detail_part_clawed", dark = true)

    @Test
    @Config(qualifiers = "w390dp-h880dp-xhdpi")
    fun norebateLight() = page("OrderDetailNoRebate-v2-light", "detail_no_rebate", dark = false)

    @Test
    @Config(qualifiers = "w390dp-h880dp-xhdpi")
    fun norebateDark() = page("OrderDetailNoRebate-v2-dark", "detail_no_rebate", dark = true)

    @Test
    @Config(qualifiers = "w390dp-h930dp-xhdpi")
    fun blacklistLight() = page("OrderDetailBlacklist-v2-light", "detail_blacklist", dark = false)

    @Test
    @Config(qualifiers = "w390dp-h930dp-xhdpi")
    fun blacklistDark() = page("OrderDetailBlacklist-v2-dark", "detail_blacklist", dark = true)

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun presaleLight() = page("OrderDetailPresale-v2-light", "detail_presale", dark = false)

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun presaleDark() = page("OrderDetailPresale-v2-dark", "detail_presale", dark = true)

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun rightspendingLight() = page("OrderDetailRightsPending-v2-light", "detail_rights_pending", dark = false)

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun rightspendingDark() = page("OrderDetailRightsPending-v2-dark", "detail_rights_pending", dark = true)

    @Test
    @Config(qualifiers = "w390dp-h1109dp-xhdpi")
    fun reviewingLight() = page("OrderDetailReviewing-v2-light", "detail_reviewing", dark = false)

    @Test
    @Config(qualifiers = "w390dp-h1109dp-xhdpi")
    fun reviewingDark() = page("OrderDetailReviewing-v2-dark", "detail_reviewing", dark = true)

    @Test
    @Config(qualifiers = "w390dp-h912dp-xhdpi")
    fun clawedbackLight() = page("OrderDetailClawedBack-v2-light", "detail_clawed_back", dark = false)

    @Test
    @Config(qualifiers = "w390dp-h912dp-xhdpi")
    fun clawedbackDark() = page("OrderDetailClawedBack-v2-dark", "detail_clawed_back", dark = true)

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun shareLight() = page("OrderDetailShare-v2-light", "detail_share", dark = false)

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun shareDark() = page("OrderDetailShare-v2-dark", "detail_share", dark = true)

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun pricecomparenorebateLight() = page("OrderDetailPriceCompareNoRebate-v2-light", "detail_price_compare_no_rebate", dark = false)

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun pricecomparenorebateDark() = page("OrderDetailPriceCompareNoRebate-v2-dark", "detail_price_compare_no_rebate", dark = true)

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun pricecompareriskLight() = page("OrderDetailPriceCompareRisk-v2-light", "detail_price_compare_risk", dark = false)

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun pricecompareriskDark() = page("OrderDetailPriceCompareRisk-v2-dark", "detail_price_compare_risk", dark = true)

    @Test
    @Config(qualifiers = "w390dp-h930dp-xhdpi")
    fun appealpendingLight() = page("OrderDetailAppealPending-v2-light", "detail_appeal_pending", dark = false)

    @Test
    @Config(qualifiers = "w390dp-h930dp-xhdpi")
    fun appealpendingDark() = page("OrderDetailAppealPending-v2-dark", "detail_appeal_pending", dark = true)

    @Test
    @Config(qualifiers = "w390dp-h1135dp-xhdpi")
    fun unknownLight() = page("OrderDetailUnknown-light", "detail_unknown", dark = false)

    @Test
    @Config(qualifiers = "w390dp-h1135dp-xhdpi")
    fun unknownDark() = page("OrderDetailUnknown-dark", "detail_unknown", dark = true)

    @Test
    @Config(qualifiers = "w390dp-h1119dp-xhdpi")
    fun darkBoard() = page("OrderDetailDark-dark", dark = true)

    @Test fun crediting() = page("state-detail-crediting-light", "detail_crediting")

    @Test fun reason01() = reason("01")

    @Test fun reason02() = reason("02")

    @Test fun reason03() = reason("03")

    @Test fun reason04() = reason("04")

    @Test fun reason05() = reason("05")

    @Test fun reason06() = reason("06")

    @Test fun reason07() = reason("07")

    @Test fun reason08() = reason("08")

    @Test fun reason09() = reason("09")

    @Test fun reason10() = reason("10")

    @Test fun reason11() = reason("11")

    @Test fun reason12() = reason("12")

    @Test
    @Config(qualifiers = "w390dp-h1800dp-xhdpi")
    fun estimated200w390() = page("OrderDetailEstimated-v2-font200-w390-light", "detail_estimated", fontScale = 2f)

    @Test
    @Config(qualifiers = "w390dp-h1800dp-xhdpi")
    fun stress200w390() = page("OrderDetailPriceCompareRisk-v2-font200-w390-light", "detail_stress", fontScale = 2f)

    @Test
    @Config(qualifiers = "w390dp-h1800dp-xhdpi")
    fun clawedtwo200w390() = page("OrderDetailClawedBack-v2-font200-w390-light", "detail_clawed_two", fontScale = 2f)

    @Test
    @Config(qualifiers = "w320dp-h1800dp-xhdpi")
    fun estimated200w320() = page("OrderDetailEstimated-v2-font200-w320-light", "detail_estimated", fontScale = 2f)

    @Test
    @Config(qualifiers = "w320dp-h1800dp-xhdpi")
    fun stress200w320() = page("OrderDetailPriceCompareRisk-v2-font200-w320-light", "detail_stress", fontScale = 2f)

    @Test
    @Config(qualifiers = "w320dp-h1800dp-xhdpi")
    fun clawedtwo200w320() = page("OrderDetailClawedBack-v2-font200-w320-light", "detail_clawed_two", fontScale = 2f)

    @Test
    @Config(qualifiers = "w390dp-h1800dp-xhdpi")
    fun dark200() = page("OrderDetailEstimated-v2-font200-w390-dark", fontScale = 2f, dark = true)

    @Test
    @Config(qualifiers = "w840dp-h1000dp-xhdpi")
    fun wide() = page("OrderDetailSettled-v2-w840-light", "detail_settled")

    @Test fun loadingLight() = loading(false)

    @Test fun loadingDark() = loading(true)

    @Test fun errorLight() = error(false)

    @Test fun errorDark() = error(true)

    @Test fun notFound() {
        host.api.fail(30701)
        page("state-detail-not-found-light")
    }

    @Test fun stale() {
        host.stale()
        page("state-detail-stale-light")
    }

    @Test fun hidden() {
        host.mask.toggle()
        page("OrderDetailPartClawed-hidden-light", "detail_part_clawed")
    }

    private fun page(
        name: String,
        fixture: String = "detail_estimated",
        dark: Boolean = false,
        fontScale: Float = 1f,
    ) {
        host.launch(OrderDetailVisualFixtures.detail(fixture), dark = dark, fontScale = fontScale, shell = true)
        capture(name)
    }

    private fun reason(index: String) {
        host.launch(OrderDetailVisualFixtures.detail("detail_reason_$index"))
        val node = rule.onNodeWithTag(OrderTags.DETAIL_STATUS).assertIsDisplayed().fetchSemanticsNode()
        val bounds =
            com.couli.app.product
                .outerBounds(node.layoutInfo)
        rule.withOrderPixels(node) { bitmap ->
            val card =
                android.graphics.Bitmap.createBitmap(
                    bitmap,
                    bounds.left.toInt(),
                    bounds.top.toInt(),
                    bounds.width.toInt(),
                    bounds.height.toInt(),
                )
            try {
                card.captureRoboImage("src/test/snapshots/order-detail-v/OrderDetailReasonStates-$index-light.png")
            } finally {
                card.recycle()
            }
        }
    }

    private fun loading(dark: Boolean) {
        host.api.hold = CompletableDeferred()
        host.launch(dark = dark, shell = true)
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(300)
        capture("state-detail-loading-${if (dark) "dark" else "light"}")
    }

    private fun error(dark: Boolean) {
        host.api.fail()
        page("state-detail-error-${if (dark) "dark" else "light"}", dark = dark)
    }

    private fun capture(name: String) {
        rule.onNodeWithTag(OrderTags.DETAIL_PAGE).assertIsDisplayed()
        rule.onRoot().captureRoboImage("src/test/snapshots/order-detail-v/$name.png")
    }
}
