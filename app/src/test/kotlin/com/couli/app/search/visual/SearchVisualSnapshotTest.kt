package com.couli.app.search.visual

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 仅登记入口；不录制基准。半屏使用 captureScreenRoboImage，包含独立模态窗口。 */
@Suppress("TooManyFunctions")
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h956dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchVisualSnapshotTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { SearchVisualHost(rule) }

    @After fun close() = host.close()

    @Test fun typingHistory() = capture("SearchTyping-v2-light", "history")

    @Test fun typingEmpty() = capture("SearchTyping-empty-light", "idle")

    @Test fun normal() = capture("Search-v2-light", "normal")

    @Test fun category() = capture("SearchCategory-v2-light", "category")

    @Test fun pdd() = capture("Search-pdd-light", "pdd")

    @Test fun price() = capture("SearchFilterPrice-v2-light", "price")

    @Test fun priceInvalid() = capture("SearchFilterPrice-invalid-light", "price_invalid")

    @Test fun emptyFallback() = capture("SearchEmpty-v2-light", "empty_fallback")

    @Test fun empty() = capture("SearchEmpty-no-fallback-light", "empty")

    @Test fun disclaimer() = capture("SearchDisclaimerNote-v2-light", "disclaimer")

    @Test fun loading() = capture("SearchLoading-light", "loading")

    @Test fun paging() = capture("SearchPaging-light", "paging")

    @Test fun error() = capture("SearchError-light", "error")

    @Test fun limited() = capture("Search42901-light", "limited")

    @Test fun pagingError() = capture("SearchPagingError-light", "paging_error")

    @Test fun disabled() = capture("SearchDisabled-light", "disabled")

    @Test fun oneOff() = capture("SearchOneOff-light", "one_off")

    @Test fun notice() = capture("SearchDisabledNotice-light", "notice")

    @Test fun stale() = capture("SearchStale-light", "stale")

    @Test fun offShelf() = capture("SearchOffShelf-light", "off_shelf")

    @Test fun compare() = capture("SearchCompare-light", "compare")

    @Test fun unavailablePrice() = capture("SearchPriceUnavailable-light", "price_unavailable")

    @Test fun longTitle() = capture("SearchLongTitle-light", "long_title")

    @Test fun longPrice() = capture("SearchLongPrice-light", "long_price")

    private fun capture(
        name: String,
        scenario: String,
        dark: Boolean = false,
        fontScale: Float = 1f,
    ) {
        host.launch(scenario, dark, fontScale)
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(300)
        captureScreenRoboImage("src/test/snapshots/search-v/$name.png")
    }
}
