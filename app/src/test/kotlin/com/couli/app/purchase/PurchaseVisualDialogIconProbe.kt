package com.couli.app.purchase

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.couli.app.product.commonLayout
import com.couli.app.product.outerBounds
import com.couli.app.product.textLayout
import com.couli.core.designsystem.CouliColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

internal enum class PurchaseIconTone { ERROR, WARNING, SECONDARY }

/** 跨 Window 的图标对照：实际弹窗取 View.draw，参考矢量放主窗口；复用 v2 色差与轮廓判据。 */
internal class PurchaseVisualDialogIconProbe(
    private val rule: ComposeContentTestRule,
    private val host: PurchaseVisualTestHost,
) {
    private var tint = Color.Unspecified
    private var warningBackground = Color.Unspecified

    fun reference(
        icon: ImageVector,
        tone: PurchaseIconTone,
    ) {
        host.reference = {
            tint =
                when (tone) {
                    PurchaseIconTone.ERROR -> CouliColors.statusErrorText
                    PurchaseIconTone.WARNING -> CouliColors.statusWarningText
                    PurchaseIconTone.SECONDARY -> CouliColors.textSecondary
                }
            warningBackground = CouliColors.statusWarningBackground
            Box(Modifier.fillMaxSize().padding(4.dp), contentAlignment = Alignment.BottomEnd) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp).testTag(REFERENCE_ICON))
            }
        }
    }

    fun assertLeadingIcon(
        text: String,
        centerLine: Boolean = true,
    ) {
        val interaction = rule.onNodeWithText(text, useUnmergedTree = true)
        val node = interaction.fetchSemanticsNode()
        val density = node.layoutInfo.density
        val size = (20 * density.density).toInt()
        val lineHeight =
            with(density) {
                interaction
                    .textLayout()
                    .layoutInput.style.lineHeight
                    .toPx()
            }
        val left = node.boundsInRoot.left - 28 * density.density
        val top = node.boundsInRoot.top + if (centerLine) (lineHeight - size) / 2f else 0f
        val region = Rect(left, top, left + size, top + size)
        val reference = rule.onNodeWithTag(REFERENCE_ICON).fetchSemanticsNode()
        val probe = PurchaseVisualWindowProbe(rule)
        val actual = probe.pixels(node)
        val expected = probe.pixels(reference)
        val pair = Bitmap.createBitmap(size * 2 + 12, size + 8, Bitmap.Config.ARGB_8888)
        try {
            pair.eraseColor(android.graphics.Color.WHITE)
            val actualRect = Rect(4f, 4f, 4f + size, 4f + size)
            val expectedRect = Rect(size + 8f, 4f, size * 2 + 8f, size + 4f)
            Canvas(pair).apply {
                drawBitmap(actual, region.integerRect(), actualRect.integerRect(), null)
                drawBitmap(expected, reference.boundsInRoot.integerRect(), expectedRect.integerRect(), null)
            }
            pair.assertToastIcon(actualRect, expectedRect, tint.toArgb())
        } finally {
            actual.recycle()
            expected.recycle()
            pair.recycle()
        }
    }

    fun assertWarningText(
        title: String,
        note: String,
    ) {
        for (text in listOf(title, note)) {
            val style =
                rule
                    .onNodeWithText(text, useUnmergedTree = true)
                    .textLayout()
                    .layoutInput.style
            assertEquals("警告块文字色：$text", tint, style.color)
        }
    }

    fun assertWarningBackground(
        title: String,
        note: String,
    ) {
        val first = rule.onNodeWithText(title, useUnmergedTree = true).fetchSemanticsNode()
        val second = rule.onNodeWithText(note, useUnmergedTree = true).fetchSemanticsNode()
        val bounds = outerBounds(commonLayout(first.layoutInfo, second.layoutInfo))
        val gap = 4 * first.layoutInfo.density.density
        val pixels = PurchaseVisualWindowProbe(rule).pixels(first)
        try {
            val samples =
                listOf(
                    bounds.left - gap to bounds.center.y,
                    bounds.right + gap to bounds.center.y,
                    bounds.center.x to bounds.bottom + gap,
                )
            for ((x, y) in samples) {
                val color = pixels.getPixel(x.toInt(), y.toInt())
                assertTrue("警告块缺令牌底色：($x,$y)=$color", color.matchesToastIconTint(warningBackground.toArgb()))
            }
        } finally {
            pixels.recycle()
        }
    }
}

private fun Rect.integerRect(): android.graphics.Rect = android.graphics.Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())

private const val REFERENCE_ICON = "purchase_dialog_reference_icon"
