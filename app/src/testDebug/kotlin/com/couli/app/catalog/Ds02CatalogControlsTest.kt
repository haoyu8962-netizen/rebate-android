@file:OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)

package com.couli.app.catalog

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.couliColorTokens
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-xxhdpi")
class Ds02CatalogControlsTest(
    private val component: String,
    private val key: String,
    private val changingToken: String,
) {
    @get:Rule val rule = createComposeRule()
    private val sampleTag = "catalog-sample/$component/$key"

    @Test
    fun themeAndFontControlsChangeRealPreviewAndCanReturnToDefault() {
        var lightCanvas = 0
        var darkCanvas = 0
        var lightComponentColor = 0
        var darkComponentColor = 0
        rule.setContent {
            CouliTheme(darkTheme = false) {
                lightCanvas = couliColorTokens().getValue("background.canvas").toArgb()
                lightComponentColor = couliColorTokens().getValue(changingToken).toArgb()
            }
            CouliTheme(darkTheme = true) {
                darkCanvas = couliColorTokens().getValue("background.canvas").toArgb()
                darkComponentColor = couliColorTokens().getValue(changingToken).toArgb()
            }
            CouliTheme { ComponentCatalog(initial = CatalogPage.INDEX, onClose = {}) }
        }
        rule.onNodeWithTag("catalog-index").performScrollToNode(hasTestTag("catalog-entry/$component"))
        rule.onNodeWithTag("catalog-entry/$component").performClick()
        rule.waitForIdle()
        showSample()
        assertNotEquals("样例必须选浅深不同的令牌：$changingToken", lightComponentColor, darkComponentColor)
        assertTrue("差异须超过像素容差：$changingToken", !ds02Near(lightComponentColor, darkComponentColor))
        val initial = pixels("light")
        val font100 = layout().layoutInput.density.fontScale
        assertEquals(1f, font100, 0.001f)
        rule.onNodeWithTag("catalog-theme-dark").performClick()
        showSample()
        val dark = pixels("dark")
        assertTrue("组件实色未换肤（忽略页面画布和抗锯齿边）", solidForegroundChanged(initial, dark, lightCanvas, darkCanvas))
        rule.onNodeWithTag("catalog-font-200").performClick()
        showSample()
        val large = layout()
        assertEquals(2f, large.layoutInput.density.fontScale, 0.001f)
        assertTrue("200% 字号被截断", !ds02HasRealTextOverflow(large))
        rule.onNodeWithTag("catalog-theme-light").performClick()
        rule.onNodeWithTag("catalog-font-100").performClick()
        showSample()
        assertEquals(1f, layout().layoutInput.density.fontScale, 0.001f)
        assertTrue("切回后仍是深色", ds02Same(ds02Pixels(initial), ds02Pixels(pixels("light-restored"))))
    }

    private fun showSample() {
        // 导航、主题 / 字号重组与滚动需要可推进的帧；不能依赖样例恰好在首屏。
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.onNodeWithTag("catalog-cases/$component").performScrollToNode(hasTestTag(sampleTag))
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(sampleTag).assertIsDisplayed()
        rule.mainClock.advanceTimeBy(320)
    }

    private fun layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        rule
            .onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult) and hasAnyAncestor(hasTestTag(sampleTag)),
                useUnmergedTree = true,
            )[0]
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
        return results.single()
    }

    private fun pixels(name: String): java.awt.image.BufferedImage {
        val file = File("build/ds02-controls/$component-$name.png").apply { parentFile?.mkdirs() }
        rule
            .onNodeWithTag(
                "catalog-control/$component/$key",
                useUnmergedTree = true,
            ).captureRoboImage(file.path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        return requireNotNull(ImageIO.read(file))
    }

    private fun solidForegroundChanged(
        before: java.awt.image.BufferedImage,
        after: java.awt.image.BufferedImage,
        lightCanvas: Int,
        darkCanvas: Int,
    ): Boolean {
        assertEquals(before.width, after.width)
        assertEquals(before.height, after.height)
        for (y in 1 until before.height - 1) {
            for (x in 1 until before.width - 1) {
                val a = before.getRGB(x, y)
                val b = after.getRGB(x, y)
                if (ds02Near(a, b) || ds02Near(a, lightCanvas) || ds02Near(b, darkCanvas)) continue
                // 3×3 实色内部，避免文字没变色、只有周边画布影响抗锯齿却误绿。
                if ((-1..1).all { dy ->
                        (-1..1).all { dx ->
                            ds02Near(a, before.getRGB(x + dx, y + dy)) && ds02Near(b, after.getRGB(x + dx, y + dy))
                        }
                    }
                ) {
                    return true
                }
            }
        }
        return false
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun parameters(): List<Array<Any>> =
            listOf(
                arrayOf("CouliButton", "secondary-standard/default", "background.surface"),
                arrayOf("PriceTag", "coupon-list/long-price", "price.text"),
                arrayOf("RebateTag", "range/wrap-at-dash", "rebate.background"),
            )
    }
}
