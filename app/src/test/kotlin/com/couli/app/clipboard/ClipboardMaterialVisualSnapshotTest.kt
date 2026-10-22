@file:OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)

package com.couli.app.clipboard

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** ⑥独立视图 fixture（不是接口集成），从真实共享组件截取卡内版式，基准由实现者后录。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h2042dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClipboardMaterialVisualSnapshotTest {
    @get:Rule val rule = createComposeRule()
    private val driver by lazy { ClipboardVisualDriver(rule) }

    @After fun close() = driver.close()

    @Test fun materialResultSix() {
        driver.host.overlay = { ClipboardMaterialVisualFixture.Sheet() }
        driver.launch()
        rule.onNodeWithTag(ClipboardMaterialVisualFixture.TAG).assertIsDisplayed()
        rule
            .onNodeWithTag(ClipboardMaterialVisualFixture.TAG)
            .captureRoboImage("src/test/snapshots/home-v/HomeClipboardResults-v3-6-fixture-light.png")
    }
}
