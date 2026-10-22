@file:OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)

package com.couli.app.clipboard

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.clipboard.ClipboardUi
import com.couli.core.network.ApiResult
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A6/A16–18：真实 AppShell + ClipboardResultHost；先断言状态出现，禁止录空白背景。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1305dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClipboardVisualSnapshotTest {
    @get:Rule val rule = createComposeRule()
    private val driver by lazy { ClipboardVisualDriver(rule) }

    @After fun close() = driver.close()

    @Test
    @Config(qualifiers = "w390dp-h1171dp-xhdpi")
    fun consentSheet() {
        driver.launch(fresh = true)
        driver.consent()
        capture("HomeClipboardConsent-v3-light", "clipboard_consent_notice")
    }

    @Test fun singleProductLight() {
        driver.launch()
        driver.parse()
        capture("HomeClipboardCard-v3-light", ClipboardScreens.CARD_LIST)
    }

    @Test
    @Config(qualifiers = "w390dp-h1047dp-xhdpi")
    fun singleProductDark() {
        driver.launch(dark = true)
        driver.parse()
        capture("HomeClipboardCardDark-v3-dark", ClipboardScreens.CARD_LIST)
    }

    @Test
    @Config(qualifiers = "w390dp-h2042dp-xhdpi")
    fun resultOneUnrecognized() {
        driver.launch()
        driver.parse(ClipboardVisualFixtures.unrecognized())
        capture("HomeClipboardResults-v3-1-light", ClipboardScreens.UNRECOGNIZED)
    }

    @Test
    @Config(qualifiers = "w390dp-h2042dp-xhdpi")
    fun resultFourPriceUnavailable() {
        driver.launch()
        driver.parse(ClipboardVisualFixtures.cards(ClipboardVisualFixtures.card(availability = "price_unavailable")))
        capture("HomeClipboardResults-v3-4-light", ClipboardScreens.CARD_LIST)
    }

    @Test
    @Config(qualifiers = "w390dp-h2042dp-xhdpi")
    fun resultFiveNetworkFailure() {
        driver.launch()
        driver.parse(ApiResult.TransportFailure("io", null, null, false))
        capture("HomeClipboardResults-v3-5-light", ClipboardScreens.RETRY)
    }

    @Test
    @Config(qualifiers = "w390dp-h1127dp-xhdpi")
    fun inviteBar() {
        driver.launch(guest = true)
        driver.invite()
        capture("HomeClipboardInvite-v3-light", ClipboardUi.PROMPT_BAR)
    }

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun consentFont200() {
        driver.launch(fontScale = 2f, fresh = true)
        driver.consent()
        capture("HomeClipboardConsent-v3-font200-w320-light", "clipboard_consent_notice")
    }

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun productFont200Dark() {
        driver.launch(fontScale = 2f, dark = true)
        driver.parse()
        capture("HomeClipboardCard-v3-font200-w320-dark", ClipboardScreens.CARD_LIST)
    }

    private fun capture(
        name: String,
        tag: String,
    ) {
        rule.onNodeWithTag(tag).assertIsDisplayed()
        captureScreenRoboImage("src/test/snapshots/home-v/$name.png")
    }
}
