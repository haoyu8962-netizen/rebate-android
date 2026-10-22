package com.couli.app.home

import android.app.Application
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.clipboard.ClipboardVisualDriver
import com.couli.app.clipboard.ClipboardVisualFixtures
import com.couli.app.clipboard.OverlayVisualProbe
import com.couli.app.clipboard.descendants
import com.couli.app.clipboard.paneNamed
import com.couli.core.home.HomeDraftTexts
import com.couli.feature.home.HomeTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A15/G-V06/G-V07：真实弹窗的几何、语义、像素和触摸，静态图片不换协议。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomePopupVisualTest {
    @get:Rule val rule = createComposeRule()
    private val driver by lazy { ClipboardVisualDriver(rule) }
    private val probe by lazy { OverlayVisualProbe(rule) }

    @After fun close() = driver.close()

    @Test fun imageFillsPopupWithoutBackingCardOrSeparateCloseRow() {
        driver.popup()
        val popup = rule.onNodeWithTag(HomeTags.POPUP).fetchSemanticsNode().boundsInRoot
        val image = rule.onNodeWithTag(HomeTags.POPUP_IMAGE).fetchSemanticsNode()
        val b = image.boundsInRoot
        val density = image.layoutInfo.density.density
        assertEquals("去掉衬底内边距", popup, b)
        assertEquals("390dp 左右各留 32dp", 326f, b.width / density, 1f)
        assertEquals("按静态图比例", 326f / 408f, b.width / b.height, 0.01f)
        probe.bitmap(image) { pixels ->
            assertEquals(ClipboardVisualFixtures.POPUP_COLOR, pixels.getPixel(b.center.x.toInt(), (b.top + 24 * density).toInt()))
        }
    }

    @Test fun closeOverlapsImageTopRightAndWinsRealTouchDispatch() {
        driver.popup()
        val image = rule.onNodeWithTag(HomeTags.POPUP_IMAGE).fetchSemanticsNode().boundsInRoot
        val close = rule.onNodeWithTag(HomeTags.POPUP_CLOSE).assertIsDisplayed()
        val b = close.fetchSemanticsNode().boundsInRoot
        probe.assertTarget48(close.fetchSemanticsNode())
        assertTrue("关闭在图片右上内侧", image.contains(b.center) && b.center.x > image.center.x && b.center.y < image.center.y)
        close.performTouchInput { click() }
        driver.host.idle()
        rule.onNodeWithTag(HomeTags.POPUP).assertDoesNotExist()
        assertNull("关闭不能穿透到图片 action", driver.host.routing.lastTrace.value)
    }

    @Test fun defaultPaneNameOrderAndInitialCloseFocusArePreserved() {
        driver.popup()
        assertEquals("首页弹窗", HomeDraftTexts.text(HomeDraftTexts.POPUP_NAME))
        val popup = rule.onNodeWithTag(HomeTags.POPUP).assert(paneNamed("首页弹窗")).fetchSemanticsNode()
        rule.onNodeWithTag(HomeTags.POPUP_CLOSE).assertIsFocused()
        val tags =
            popup
                .descendants()
                .sortedBy {
                    it.config.getOrElse(SemanticsProperties.TraversalIndex) { 0f }
                }.map { it.config.getOrElse(SemanticsProperties.TestTag) { "" } }
        assertTrue("关闭语义必须先于图片", tags.indexOf(HomeTags.POPUP_CLOSE) in 0 until tags.indexOf(HomeTags.POPUP_IMAGE))
    }

    @Test fun lightCloseSurfaceIsPaintedAboveImage() = assertClosePixels(dark = false)

    @Test fun darkCloseSurfaceIsPaintedAboveImage() = assertClosePixels(dark = true)

    @Test fun adLabelStaysVerbatimAtImageTopLeft() {
        driver.popup()
        val label =
            rule
                .onNode(hasText(ClipboardVisualFixtures.AD) and hasAnyAncestor(hasTestTag(HomeTags.POPUP)))
                .assertIsDisplayed()
                .fetchSemanticsNode()
        val image = rule.onNodeWithTag(HomeTags.POPUP_IMAGE).fetchSemanticsNode().boundsInRoot
        val density = label.layoutInfo.density.density
        assertTrue("推广在图片左上", label.boundsInRoot.center.x < image.center.x && label.boundsInRoot.center.y < image.center.y)
        assertTrue("左上距离最多 16dp（含 8dp 内边距）", label.boundsInRoot.left - image.left <= 16 * density)
        assertTrue(label.boundsInRoot.top - image.top <= 16 * density)
    }

    @Test fun backClosesPopupWithoutAction() {
        driver.popup()
        probe.back()
        driver.host.idle()
        rule.onNodeWithTag(HomeTags.POPUP).assertDoesNotExist()
        assertNull(driver.host.routing.lastTrace.value)
    }

    @Test
    @Config(qualifiers = "w320dp-h400dp-xhdpi")
    fun shortViewportFont200KeepsCloseVisibleAndUsable() {
        driver.popup(fontScale = 2f)
        PopupWindowProbe(rule).assertAdFontAndBounds(2f)
        val close = rule.onNodeWithTag(HomeTags.POPUP_CLOSE).assertIsDisplayed()
        probe.assertTarget48(close.fetchSemanticsNode())
        close.performTouchInput { click() }
        driver.host.idle()
        rule.onNodeWithTag(HomeTags.POPUP).assertDoesNotExist()
    }

    @Test fun unsafeImageActionCannotBypassWhitelistOrPreventClosing() {
        driver.popup(action = """{"url":"https://evil.example.test/escape"}""")
        rule.onNodeWithTag(HomeTags.POPUP_IMAGE).performTouchInput { click() }
        driver.host.idle()
        assertNull(driver.host.routing.lastTrace.value)
        rule.onNodeWithTag(HomeTags.POPUP_CLOSE).performTouchInput { click() }
        driver.host.idle()
        rule.onNodeWithTag(HomeTags.POPUP).assertDoesNotExist()
    }

    private fun assertClosePixels(dark: Boolean) {
        driver.popup(dark = dark)
        val close = rule.onNodeWithTag(HomeTags.POPUP_CLOSE).fetchSemanticsNode()
        val image = rule.onNodeWithTag(HomeTags.POPUP_IMAGE).fetchSemanticsNode().boundsInRoot
        val b = close.boundsInRoot
        assertTrue("检测点必须在图片区内", image.contains(b.center))
        probe.bitmap(close) { pixels ->
            val y = (b.center.y - 12 * close.layoutInfo.density.density).toInt()
            assertEquals("圆底实际绘制在图片之上", driver.host.surface.toArgb(), pixels.getPixel(b.center.x.toInt(), y))
        }
    }
}
