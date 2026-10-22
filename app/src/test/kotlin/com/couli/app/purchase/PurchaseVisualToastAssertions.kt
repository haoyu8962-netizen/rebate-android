package com.couli.app.purchase

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import com.couli.app.product.iconMaskDifference
import kotlin.math.abs
import org.junit.Assert.assertTrue

/** CouliToast：内边距 12dp、图标 20dp，行内垂直居中；保持 v1 的取样位置。 */
internal fun SemanticsNode.toastIconBounds(): Rect {
    val density = layoutInfo.density.density
    val size = (20 * density).toInt()
    val left = boundsInRoot.left + 12 * density
    val top = boundsInRoot.center.y - size / 2f
    return Rect(left, top, left + size, top + size)
}

/** T1：仅容忍 ARGB 每通道 ±2；不把不同主题色或透明图标当作同色。 */
internal fun Int.matchesToastIconTint(tint: Int): Boolean =
    listOf(24, 16, 8, 0).all { shift ->
        abs(((this ushr shift) and 0xff) - ((tint ushr shift) and 0xff)) <= 2
    }

/** 先验指定色墨迹，再原地归一实际/参考区域；沿用 ±1 位移与 size/2 轮廓差上限。 */
internal fun Bitmap.assertToastIcon(
    actual: Rect,
    expected: Rect,
    tint: Int,
) {
    val actualInk =
        (actual.left.toInt() until actual.right.toInt()).sumOf { x ->
            (actual.top.toInt() until actual.bottom.toInt()).count { y -> getPixel(x, y).matchesToastIconTint(tint) }
        }
    assertTrue("轻提示缺指定颜色图标：$actualInk", actualInk > 20)
    normalizeToastIcon(actual, tint)
    // ±1 位移会读到参考框外一圈；也归一其背景，否则深色/透明背景被误算作墨迹。
    normalizeToastIcon(expected.inflate(1f), tint)
    val size = actual.width.toInt()
    val diff = iconMaskDifference(actual, expected, size, Color.WHITE)
    println("TOAST_ICON actualInk=$actualInk diff=$diff limit=${size / 2}")
    assertTrue("图标轮廓不符：$diff", diff <= size / 2)
}

private fun Bitmap.normalizeToastIcon(
    rect: Rect,
    tint: Int,
) {
    for (x in rect.left.toInt() until rect.right.toInt()) {
        for (y in rect.top.toInt() until rect.bottom.toInt()) {
            val color = if (getPixel(x, y).matchesToastIconTint(tint)) Color.BLACK else Color.WHITE
            setPixel(x, y, color)
        }
    }
}
