@file:OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)

package com.couli.app.home

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.clipboard.ClipboardVisualDriver
import com.couli.feature.home.HomeTags
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A15：含真实 AppShell 背景及 Dialog 的全窗口截图；本轮只声明路径，不录基准。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1189dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomePopupVisualSnapshotTest {
    @get:Rule val rule = createComposeRule()
    private val driver by lazy { ClipboardVisualDriver(rule) }

    @After fun close() = driver.close()

    @Test fun popupStaticLight() = capture("MainPopupStatic-light")

    @Test fun popupStaticDark() = capture("MainPopupDark-dark", dark = true)

    @Test
    @Config(qualifiers = "w320dp-h400dp-xhdpi")
    fun popupStaticShort() = capture("MainPopupStatic-short-light")

    @Test
    @Config(qualifiers = "w320dp-h400dp-xhdpi")
    fun popupFont200DarkShort() = capture("MainPopupStatic-short-font200-dark", dark = true, fontScale = 2f)

    private fun capture(
        name: String,
        dark: Boolean = false,
        fontScale: Float = 1f,
    ) {
        driver.popup(dark = dark, fontScale = fontScale)
        PopupWindowProbe(rule).assertAdFontAndBounds(fontScale)
        rule.onNodeWithTag(HomeTags.POPUP_IMAGE).assertIsDisplayed()
        rule.onNodeWithTag(HomeTags.POPUP_CLOSE).assertIsDisplayed()
        captureScreenRoboImage("src/test/snapshots/home-v/$name.png")
    }
}
