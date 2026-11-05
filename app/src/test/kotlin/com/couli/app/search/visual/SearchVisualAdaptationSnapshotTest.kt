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
class SearchVisualAdaptationSnapshotTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { SearchVisualHost(rule) }

    @After fun close() = host.close()

    @Test
    fun typingFont200w390() = capture("SearchTyping-font200w390", "history", dark = false, fontScale = 2f)

    @Test
    @Config(qualifiers = "w320dp-h956dp-xhdpi")
    fun typingFont200w320() = capture("SearchTyping-font200w320", "history", dark = false, fontScale = 2f)

    @Test
    @Config(qualifiers = "w840dp-h956dp-xhdpi")
    fun typingW840() = capture("SearchTyping-w840", "history", dark = false, fontScale = 1f)

    @Test
    fun typingDark() = capture("SearchTyping-dark", "history", dark = true, fontScale = 1f)

    @Test
    fun resultsFont200w390() = capture("Search-font200w390", "normal", dark = false, fontScale = 2f)

    @Test
    @Config(qualifiers = "w320dp-h956dp-xhdpi")
    fun resultsFont200w320() = capture("Search-font200w320", "normal", dark = false, fontScale = 2f)

    @Test
    @Config(qualifiers = "w840dp-h956dp-xhdpi")
    fun resultsW840() = capture("Search-w840", "normal", dark = false, fontScale = 1f)

    @Test
    fun resultsDark() = capture("Search-dark", "normal", dark = true, fontScale = 1f)

    @Test
    fun priceFont200w390() = capture("SearchFilterPrice-font200w390", "price", dark = false, fontScale = 2f)

    @Test
    @Config(qualifiers = "w320dp-h956dp-xhdpi")
    fun priceFont200w320() = capture("SearchFilterPrice-font200w320", "price", dark = false, fontScale = 2f)

    @Test
    @Config(qualifiers = "w840dp-h956dp-xhdpi")
    fun priceW840() = capture("SearchFilterPrice-w840", "price", dark = false, fontScale = 1f)

    @Test
    fun priceDark() = capture("SearchFilterPrice-dark", "price", dark = true, fontScale = 1f)

    @Test
    fun emptyFont200w390() = capture("SearchEmpty-font200w390", "empty_fallback", dark = false, fontScale = 2f)

    @Test
    @Config(qualifiers = "w320dp-h956dp-xhdpi")
    fun emptyFont200w320() = capture("SearchEmpty-font200w320", "empty_fallback", dark = false, fontScale = 2f)

    @Test
    @Config(qualifiers = "w840dp-h956dp-xhdpi")
    fun emptyW840() = capture("SearchEmpty-w840", "empty_fallback", dark = false, fontScale = 1f)

    @Test
    fun emptyDark() = capture("SearchEmpty-dark", "empty_fallback", dark = true, fontScale = 1f)

    @Test
    fun errorFont200w390() = capture("SearchError-font200w390", "error", dark = false, fontScale = 2f)

    @Test
    @Config(qualifiers = "w320dp-h956dp-xhdpi")
    fun errorFont200w320() = capture("SearchError-font200w320", "error", dark = false, fontScale = 2f)

    @Test
    @Config(qualifiers = "w840dp-h956dp-xhdpi")
    fun errorW840() = capture("SearchError-w840", "error", dark = false, fontScale = 1f)

    @Test
    fun errorDark() = capture("SearchError-dark", "error", dark = true, fontScale = 1f)

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
