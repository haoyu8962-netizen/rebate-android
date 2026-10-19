package com.couli.core.designsystem

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * BRAND-01：Logo 资源齐全、宽高比与 SVG 画布一致、原色版 / 反白版颜色对；CouliLogo 默认按主题底色选版。
 * 取样点按原画布（高 256）：(55,150) 在图形主体内，(320,128) 在横版字标笔画内。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-mdpi")
class CouliLogoTest {
    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun everyVariantHasDistinctColorAndInverseDrawables() {
        val ids = mutableSetOf<Int>()
        for (variant in CouliLogoVariant.entries) {
            for (inverse in listOf(false, true)) {
                val id = couliLogoRes(variant, inverse)
                ids += id
                val drawable = requireNotNull(context.getDrawable(id)) { "$variant inverse=$inverse 取不到" }
                val ratio = drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight
                assertEquals("$variant 宽高比应与 SVG 画布一致", variant.aspectRatio, ratio, 0.02f)
            }
            assertNotEquals(couliLogoRes(variant, false), couliLogoRes(variant, true))
        }
        assertEquals(4, ids.size)
    }

    @Test
    fun colorVersionsUseBrandAndInkInverseVersionsAreWhite() {
        val mark = render(CouliLogoVariant.MARK, inverse = false)
        assertClose(BRAND, mark.getPixel(55, 150))
        assertEquals(0, android.graphics.Color.alpha(mark.getPixel(128, 128)))
        assertClose(WHITE, render(CouliLogoVariant.MARK, inverse = true).getPixel(55, 150))

        val horizontal = render(CouliLogoVariant.HORIZONTAL, inverse = false)
        assertClose(BRAND, horizontal.getPixel(55, 150))
        assertClose(INK, horizontal.getPixel(320, 128))
        val horizontalInverse = render(CouliLogoVariant.HORIZONTAL, inverse = true)
        assertClose(WHITE, horizontalInverse.getPixel(55, 150))
        assertClose(WHITE, horizontalInverse.getPixel(320, 128))
    }

    @Test
    fun couliThemePicksColorVersion() {
        var prefersInverse: Boolean? = null
        rule.setContent {
            CouliTheme { prefersInverse = couliLogoPrefersInverse() }
        }
        rule.waitForIdle()
        assertFalse(requireNotNull(prefersInverse))
    }

    @Test
    fun darkBackgroundPicksInverseVersion() {
        var prefersInverse: Boolean? = null
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF28251F))) {
                prefersInverse = couliLogoPrefersInverse()
            }
        }
        rule.waitForIdle()
        assertTrue(requireNotNull(prefersInverse))
    }

    /** 按原画布尺寸（高 256px）把矢量画进位图。 */
    private fun render(
        variant: CouliLogoVariant,
        inverse: Boolean,
    ): Bitmap {
        val height = CANVAS_HEIGHT
        val width = Math.round(height * variant.aspectRatio)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val drawable = requireNotNull(context.getDrawable(couliLogoRes(variant, inverse)))
        drawable.setBounds(0, 0, width, height)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    private fun assertClose(
        expected: Int,
        actual: Int,
    ) {
        val channels =
            listOf<
                (
                    Int,
                ) -> Int,
            >(android.graphics.Color::alpha, android.graphics.Color::red, android.graphics.Color::green, android.graphics.Color::blue)
        val close = channels.all { channel -> kotlin.math.abs(channel(expected) - channel(actual)) <= TOLERANCE }
        assertTrue("期望 #%08X，实际 #%08X".format(expected, actual), close)
    }

    private companion object {
        const val CANVAS_HEIGHT = 256
        const val TOLERANCE = 6
        val BRAND = Color(0xFFC44820).toArgb()
        val INK = Color(0xFF28251F).toArgb()
        val WHITE = Color.White.toArgb()
    }
}
