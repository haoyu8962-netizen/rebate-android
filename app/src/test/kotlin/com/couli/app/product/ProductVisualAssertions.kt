package com.couli.app.product

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** Robolectric 必须 View.draw；captureToImage 的 PixelCopy 在这里会给出空白图。 */
internal fun ComposeContentTestRule.productPixels(): Bitmap {
    val view = (onRoot().fetchSemanticsNode().root as ViewRootForTest).view
    val result = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    runOnUiThread { view.draw(Canvas(result)) }
    return result
}

internal fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
    val results = mutableListOf<TextLayoutResult>()
    performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
    return results.single()
}

internal fun SemanticsNodeInteraction.assertNoTextOverflow() {
    val result = textLayout()
    val text = result.layoutInput.text.text
    val node = fetchSemanticsNode()
    val bounds = node.boundsInRoot
    // boundsInRoot 已经被父容器裁切；必须从未裁切原点与实测 size 还原文字布局位置。
    // 不用语义重建的 MultiParagraph.width，避免再次把 T1 的父约束宽当成文字宽。
    val origin = node.layoutInfo.coordinates.positionInRoot()
    val layout = Rect(origin.x, origin.y, origin.x + result.size.width, origin.y + result.size.height)
    assertTrue(
        "横向裁切（位置）：$text，文字布局=$layout，可见区域=$bounds",
        layout.left >= bounds.left - 1f && layout.right <= bounds.right + 1f,
    )
    assertTrue("横向裁切（父容器宽度）：$text", result.size.width <= bounds.width + 1f)
    assertTrue(
        "纵向裁切（位置）：$text，文字布局=$layout，可见区域=$bounds",
        layout.top >= bounds.top - 1f && layout.bottom <= bounds.bottom + 1f,
    )
    // String Text 的语义结果可能按父约束重建 MultiParagraph；其 width 不是实际文字宽。
    // Center 对齐的行坐标也相对该父宽：取 right-left，不能直接拿 right 与节点宽比较。
    val available = minOf(result.size.width.toFloat(), bounds.width)
    if (!result.layoutInput.softWrap) {
        // 语义重建也可能把禁止换行的文本拆行；固有宽度才能抓住真实的横向硬裁切。
        val intrinsicWidth = result.multiParagraph.maxIntrinsicWidth
        assertTrue("横向裁切：$text，固有宽=$intrinsicWidth，可见宽=$available", intrinsicWidth <= available + 1f)
    }
    repeat(result.lineCount) { line ->
        val lineWidth = result.getLineRight(line) - result.getLineLeft(line)
        assertTrue("横向裁切：$text，行宽=$lineWidth，可见宽=$available", lineWidth <= available + 1f)
        assertFalse("省略：$text", result.isLineEllipsized(line))
    }
    assertFalse("纵向裁切：$text", result.didOverflowHeight)
}

internal fun commonLayout(
    first: LayoutInfo,
    second: LayoutInfo,
): LayoutInfo {
    val ancestors = generateSequence(first) { it.parentInfo }.toList()
    return generateSequence(second) { it.parentInfo }.first { candidate -> ancestors.any { it === candidate } }
}

internal fun outerBounds(layout: LayoutInfo): Rect =
    (layout.getModifierInfo().firstOrNull()?.coordinates ?: layout.coordinates).boundsInRoot()

internal fun SemanticsNode.readableText(): String =
    (
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    ).joinToString(" ")

internal fun assertContainsRect(
    outer: Rect,
    inner: Rect,
) {
    assertTrue(
        "$inner 必须在 $outer 内",
        inner.left >= outer.left && inner.right <= outer.right &&
            inner.top >= outer.top && inner.bottom <= outer.bottom,
    )
}

internal fun Bitmap.inkPixels(
    rect: Rect,
    background: Int,
): Int =
    (rect.left.toInt() until rect.right.toInt()).sumOf { x ->
        (rect.top.toInt() until rect.bottom.toInt()).count { y -> getPixel(x, y) != background }
    }

/** 仅容忍绝对坐标造成的 1 物理像素抗锯齿偏移；比较图标墨迹轮廓，不放宽成“非空即可”。 */
internal fun Bitmap.iconMaskDifference(
    actual: Rect,
    expected: Rect,
    size: Int,
    background: Int,
): Int {
    val shifts = (-1..1).flatMap { x -> (-1..1).map { y -> x to y } }
    return shifts.minOf { (dx, dy) ->
        (0 until size * size).count { index ->
            val x = index % size
            val y = index / size
            val actualInk = getPixel(actual.left.toInt() + x, actual.top.toInt() + y) != background
            val expectedInk = getPixel(expected.left.toInt() + x + dx, expected.top.toInt() + y + dy) != background
            actualInk != expectedInk
        }
    }
}
