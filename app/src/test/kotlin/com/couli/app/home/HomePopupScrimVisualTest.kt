package com.couli.app.home

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.clipboard.ClipboardVisualDriver
import com.couli.app.shell.HOME_SEARCH_ENTRY_TAG
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomePopupScrimVisualTest {
    @get:Rule val rule = createComposeRule()
    private val driver by lazy { ClipboardVisualDriver(rule) }

    @After fun close() = driver.close()

    @Test fun lightScrimPixelMatchesToken() = assertScrim(dark = false)

    @Test fun darkScrimPixelMatchesToken() = assertScrim(dark = true)

    private fun assertScrim(dark: Boolean) {
        driver.popup(dark = dark)
        val background = rule.onNodeWithTag(HOME_SEARCH_ENTRY_TAG).fetchSemanticsNode()
        val point = Offset(8f, 16f) * background.layoutInfo.density.density
        val (before, actual) = PopupWindowProbe(rule).scrimPixel(background, point.x.toInt(), point.y.toInt())
        val expected =
            driver.host.scrim
                .compositeOver(Color(before))
                .toArgb()
        // 半透明令牌先与同坐标未遮罩像素合成；每个 ARGB 通道容差 1/255，仅容许整数量化舍入。
        listOf(24, 16, 8, 0).forEach { shift ->
            val delta = abs((expected ushr shift and 255) - (actual ushr shift and 255))
            assertTrue("遮罩必须只叠一层 overlayScrim：expected=${expected.toUInt()} actual=${actual.toUInt()} channel=$shift", delta <= 1)
        }
    }
}
