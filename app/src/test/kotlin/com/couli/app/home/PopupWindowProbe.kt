package com.couli.app.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.couli.app.clipboard.ClipboardVisualFixtures
import com.couli.app.clipboard.OverlayVisualProbe
import com.couli.core.designsystem.CouliTypography
import com.couli.feature.home.HomeTags
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.robolectric.shadows.ShadowDialog

/** v2：测实际窗口、实际文字排版；位图只在内存用 View.draw 合成，不录截图。 */
internal class PopupWindowProbe(
    private val rule: ComposeContentTestRule,
) {
    fun assertCloseInsideWindow() {
        val close = rule.onNodeWithTag(HomeTags.POPUP_CLOSE).assertIsDisplayed().fetchSemanticsNode()
        val view = (close.root as ViewRootForTest).view
        val window = view.rootView
        val offset = IntArray(2).also(view::getLocationInWindow).let { Offset(it[0].toFloat(), it[1].toFloat()) }
        val density = close.layoutInfo.density.density
        listOf(close.boundsInRoot, close.touchBoundsInRoot).forEach { bounds ->
            val b = bounds.translate(offset)
            assertTrue("关闭可见边界与命中区都必须至少 48dp：$b", b.width >= 48 * density && b.height >= 48 * density)
            assertTrue("关闭完整位于窗口 ${window.width}×${window.height} 内：$b", b.left >= 0 && b.top >= 0)
            assertTrue("关闭不能越出窗口右/下边界：$b", b.right <= window.width && b.bottom <= window.height)
        }
        OverlayVisualProbe(rule).assertTarget48(close)
    }

    fun assertAdFontAndBounds(expectedScale: Float) {
        val ad =
            rule
                .onNode(
                    hasText(ClipboardVisualFixtures.AD) and hasAnyAncestor(hasTestTag(HomeTags.POPUP)),
                    useUnmergedTree = true,
                ).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        val node = ad.fetchSemanticsNode()
        rule.runOnUiThread { checkNotNull(node.config[SemanticsActions.GetTextLayoutResult].action).invoke(layouts) }
        assertEquals(1, layouts.size)
        val input = layouts.single().layoutInput
        assertEquals("弹窗内实际排版 density，不能只检查首页", expectedScale, input.density.fontScale, 0.001f)
        assertEquals("角标仍用 caption，不得缩字号逃避适配", CouliTypography.caption.fontSize, input.style.fontSize)
        val expectedPx = with(Density(input.density.density, expectedScale)) { CouliTypography.caption.fontSize.toPx() }
        assertEquals("实际字号像素包含窗口缩放", expectedPx, with(input.density) { input.style.fontSize.toPx() }, 0.01f)
        // 字号前置断言通过后才查角标、关闭，快照也调用本方法。
        OverlayVisualProbe(rule).assertTextFits(ad)
        assertCloseInsideWindow()
        val image = rule.onNodeWithTag(HomeTags.POPUP_IMAGE).fetchSemanticsNode().boundsInRoot
        val b = node.boundsInRoot
        assertTrue("角标完整留在图片区", b.left >= image.left && b.top >= image.top && b.right <= image.right && b.bottom <= image.bottom)
        val close = rule.onNodeWithTag(HomeTags.POPUP_CLOSE).fetchSemanticsNode().boundsInRoot
        assertTrue("角标文字不能被关闭覆盖", !b.overlaps(close))
    }

    /** 两个真实窗口逐层 View.draw，并读取 Window 的实际 dim；不会遗漏默认 dim 与显式遮罩的重复叠加。 */
    fun scrimPixel(
        background: SemanticsNode,
        x: Int,
        y: Int,
    ): Pair<Int, Int> {
        val main = (background.root as ViewRootForTest).view.rootView
        val dialog = requireNotNull(ShadowDialog.getLatestDialog().window)
        val popup = rule.onNodeWithTag(HomeTags.POPUP).fetchSemanticsNode()
        val popupPosition = screenPosition((popup.root as ViewRootForTest).view)
        val origin = screenPosition(main)
        val offset = Offset((popupPosition[0] - origin[0]).toFloat(), (popupPosition[1] - origin[1]).toFloat())
        assertFalse("取样须在遮罩区，不能把两个窗口的局部坐标直接比较", popup.boundsInRoot.translate(offset).contains(Offset(x.toFloat(), y.toFloat())))
        val image = Bitmap.createBitmap(main.width, main.height, Bitmap.Config.ARGB_8888)
        try {
            var before = 0
            rule.runOnUiThread {
                val canvas = Canvas(image)
                main.draw(canvas)
                before = image.getPixel(x, y)
                val attributes = dialog.attributes
                if (attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) {
                    canvas.drawColor(Color.argb((attributes.dimAmount * 255).roundToInt(), 0, 0, 0))
                }
                val position = screenPosition(dialog.decorView)
                canvas.translate((position[0] - origin[0]).toFloat(), (position[1] - origin[1]).toFloat())
                dialog.decorView.draw(canvas)
            }
            return before to image.getPixel(x, y)
        } finally {
            image.recycle()
        }
    }

    private fun screenPosition(view: View): IntArray = IntArray(2).also(view::getLocationOnScreen)
}
