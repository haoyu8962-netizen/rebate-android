package com.couli.app.purchase

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.robolectric.shadows.ShadowDialog

/** 读真实 Window；系统 dim 和 Compose 遮罩一起合成，避免漏检双重遮罩。只在内存画图。 */
internal class PurchaseVisualWindowProbe(
    private val rule: ComposeContentTestRule,
) {
    fun pixels(node: SemanticsNode): Bitmap {
        val view = (node.root as ViewRootForTest).view
        return Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { image ->
            rule.runOnUiThread { view.draw(Canvas(image)) }
        }
    }

    fun scrim(node: SemanticsNode): PurchaseScrimSample {
        val owner = (node.root as ViewRootForTest).view
        val main = owner.rootView
        val window = requireNotNull(ShadowDialog.getLatestDialog().window)
        val origin = position(main)
        val ownerPosition = position(owner)
        val center = node.boundsInRoot.center
        val x = (center.x + ownerPosition[0] - origin[0]).toInt()
        val y = (center.y + ownerPosition[1] - origin[1]).toInt()
        val image = Bitmap.createBitmap(main.width, main.height, Bitmap.Config.ARGB_8888)
        val attributes = window.attributes
        val dimmed = attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0
        try {
            var before = 0
            rule.runOnUiThread {
                val canvas = Canvas(image)
                main.draw(canvas)
                before = image.getPixel(x, y)
                if (dimmed) canvas.drawColor(Color.argb((attributes.dimAmount * 255).roundToInt(), 0, 0, 0))
                val offset = position(window.decorView)
                canvas.translate((offset[0] - origin[0]).toFloat(), (offset[1] - origin[1]).toFloat())
                window.decorView.draw(canvas)
            }
            assertEquals("采样底色必须不透明", 255, Color.alpha(before))
            return PurchaseScrimSample(before, image.getPixel(x, y), if (dimmed) attributes.dimAmount else 0f)
        } finally {
            image.recycle()
        }
    }

    private fun position(view: View): IntArray = IntArray(2).also(view::getLocationOnScreen)
}

internal data class PurchaseScrimSample(
    val before: Int,
    val after: Int,
    val dim: Float,
)
