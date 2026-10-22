package com.couli.app.home

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.clipboard.ClipboardVisualDriver
import com.couli.feature.home.HomeTags
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** r1 S1：合法图片比例不能压小或裁剪关闭命中区；近边缘真实点击不能穿到图片。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w320dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomePopupBoundaryVisualTest {
    @get:Rule val rule = createComposeRule()
    private val driver by lazy { ClipboardVisualDriver(rule) }

    @After fun close() = driver.close()

    @Test fun wideImageKeepsWholeCloseTarget() = assertClose(IntSize(1000, 100))

    @Test fun narrowPortraitKeepsWholeCloseTarget() = assertClose(IntSize(100, 2000))

    @Test
    @Config(qualifiers = "w320dp-h200dp-xhdpi")
    fun wideImageInShortWindowCanClose() = assertClose(IntSize(1000, 100))

    @Test
    @Config(qualifiers = "w320dp-h200dp-xhdpi")
    fun narrowPortraitInShortWindowCanClose() = assertClose(IntSize(100, 2000))

    @Test
    @Config(qualifiers = "w320dp-h200dp-xhdpi")
    fun regularImageInShortWindowCanClose() = assertClose(IntSize(326, 408))

    private fun assertClose(size: IntSize) {
        driver.popup(imageSize = size)
        PopupWindowProbe(rule).assertCloseInsideWindow()
        rule.onNodeWithTag(HomeTags.POPUP_CLOSE).performTouchInput { click(Offset(2f, height - 2f)) }
        driver.host.idle()
        rule.onNodeWithTag(HomeTags.POPUP).assertDoesNotExist()
        assertNull(
            driver.host.home.controller.value
                ?.state
                ?.value
                ?.popup,
        )
        assertNull("关闭不能穿透到图片 action", driver.host.routing.lastTrace.value)
    }
}
