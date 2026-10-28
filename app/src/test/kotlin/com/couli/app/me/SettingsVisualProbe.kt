package com.couli.app.me

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText as textIs
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.text.TextStyle
import com.couli.app.product.assertNoTextOverflow
import com.couli.app.product.productPixels
import com.couli.app.product.textLayout
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/*
 * 设置一族的量具（只读）：文字盒用真实 TextLayoutResult + 未裁切原点；卡片 / 提示条 / 底栏边界用 View.draw 的像素按令牌色扫描；
 * 图标盒用真实 LayoutNode 子树（MeVisualAssertions.meChildren，升级 Compose 不认识时明确失败，不猜尺寸）。
 * 视觉断言先正后反：每个「没有」的断言旁边都有同一量具量出「有」的对照。
 */

/** 文字节点的实际排版盒（不是 weight 撑大的父盒，也不是被父容器裁过的可见区）。 */
internal fun SemanticsNodeInteraction.textBox(): Rect {
    val layout = textLayout()
    val origin = fetchSemanticsNode().layoutInfo.coordinates.positionInRoot()
    return Rect(origin.x, origin.y, origin.x + layout.size.width, origin.y + layout.size.height)
}

/** 实际字形（各行 left / right 的并集）。 */
internal fun SemanticsNodeInteraction.glyphBox(): Rect {
    val layout = textLayout()
    val origin = fetchSemanticsNode().layoutInfo.coordinates.positionInRoot()
    val left = (0 until layout.lineCount).minOf { layout.getLineLeft(it) }
    val right = (0 until layout.lineCount).maxOf { layout.getLineRight(it) }
    return Rect(origin.x + left, origin.y, origin.x + right, origin.y + layout.size.height)
}

internal fun SemanticsNodeInteraction.textStyle(): TextStyle = textLayout().layoutInput.style

internal fun SemanticsNodeInteraction.lines(): Int = textLayout().lineCount

internal fun SemanticsNodeInteraction.touch(): Rect = fetchSemanticsNode().touchBoundsInRoot

internal fun SemanticsNodeInteraction.box(): Rect = fetchSemanticsNode().boundsInRoot

internal fun SemanticsNodeInteraction.density(): Float = fetchSemanticsNode().layoutInfo.density.density

internal fun SemanticsNodeInteraction.fontScale(): Float = fetchSemanticsNode().layoutInfo.density.fontScale

/** 某标记之下（含自身）文字恰为 [text] 的未合并节点。 */
internal fun ComposeContentTestRule.textUnder(
    tag: String,
    text: String,
): SemanticsNodeInteraction = onNode(textIs(text) and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))), useUnmergedTree = true)

internal fun ComposeContentTestRule.textNode(
    text: String,
    substring: Boolean = false,
): SemanticsNodeInteraction = onNode(textIs(text, substring = substring), useUnmergedTree = true)

/**
 * 弹窗里的按钮（v2，T2 / T3）：CouliButton 的 OnClick 在按钮节点、文字在子 Text 节点，未合并树里没有同时有文字与点击的节点；
 * 取「可点击、其下有文字恰为 [text]、且在 paneTitle 弹窗之内」的唯一节点。
 */
internal fun ComposeContentTestRule.dialogButton(text: String): SemanticsNodeInteraction {
    val inDialog = hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle))
    return onNode(hasClickAction() and hasAnyDescendant(textIs(text)) and inDialog, useUnmergedTree = true)
}

/** 页面的纵向滚动容器（其上沿 = 标题栏下沿）。 */
internal fun ComposeContentTestRule.scrollUnder(tag: String): SemanticsNodeInteraction =
    onNode(hasScrollAction() and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)

internal fun ComposeContentTestRule.pixels(): Bitmap = productPixels()

internal fun sameColor(
    actual: Int,
    expected: Color,
    tolerance: Int = 3,
): Boolean {
    val e = expected.toArgb()
    return listOf(16, 8, 0).all { shift -> abs((actual ushr shift and 255) - (e ushr shift and 255)) <= tolerance }
}

internal fun Bitmap.at(
    x: Float,
    y: Float,
): Int = getPixel(x.toInt().coerceIn(0, width - 1), y.toInt().coerceIn(0, height - 1))

/** 从 (x, y) 向上走，返回仍是 [color] 的最上一行的上沿（起点就不是 [color] 时返回 y + 1）。 */
internal fun Bitmap.edgeUp(
    x: Float,
    y: Float,
    color: Color,
): Float {
    var row = y.toInt()
    if (!sameColor(at(x, row.toFloat()), color)) return row + 1f
    while (row > 0 && sameColor(at(x, row - 1f), color)) row--
    return row.toFloat()
}

/** 从 (x, y) 向下走，返回仍是 [color] 的最下一行的下沿。 */
internal fun Bitmap.edgeDown(
    x: Float,
    y: Float,
    color: Color,
): Float {
    var row = y.toInt()
    if (!sameColor(at(x, row.toFloat()), color)) return row.toFloat()
    while (row < height - 1 && sameColor(at(x, row + 1f), color)) row++
    return row + 1f
}

/** 从左往右第一个 [color] 像素的左沿。 */
internal fun Bitmap.firstFromLeft(
    y: Float,
    color: Color,
    from: Float = 0f,
): Float {
    var x = from.toInt()
    while (x < width && !sameColor(at(x.toFloat(), y), color)) x++
    return x.toFloat()
}

/** 从右往左第一个 [color] 像素的右沿。 */
internal fun Bitmap.firstFromRight(
    y: Float,
    color: Color,
    from: Float = width - 1f,
): Float {
    var x = from.toInt()
    while (x > 0 && !sameColor(at(x.toFloat(), y), color)) x--
    return x + 1f
}

internal fun Bitmap.countNear(
    rect: Rect,
    color: Color,
    tolerance: Int = 3,
): Int {
    var n = 0
    for (x in rect.left.toInt().coerceAtLeast(0) until rect.right.toInt().coerceAtMost(width)) {
        for (y in rect.top.toInt().coerceAtLeast(0) until rect.bottom.toInt().coerceAtMost(height)) {
            if (sameColor(getPixel(x, y), color, tolerance)) n++
        }
    }
    return n
}

/** [rect] 内不是 [background] 的像素（墨迹）外接框；没有墨迹为 null。 */
internal fun Bitmap.ink(
    rect: Rect,
    background: Color,
    tolerance: Int = 12,
): Rect? {
    var left = Int.MAX_VALUE
    var top = Int.MAX_VALUE
    var right = Int.MIN_VALUE
    var bottom = Int.MIN_VALUE
    for (x in rect.left.toInt().coerceAtLeast(0) until rect.right.toInt().coerceAtMost(width)) {
        for (y in rect.top.toInt().coerceAtLeast(0) until rect.bottom.toInt().coerceAtMost(height)) {
            if (!sameColor(getPixel(x, y), background, tolerance)) {
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x + 1)
                bottom = maxOf(bottom, y + 1)
            }
        }
    }
    return if (left == Int.MAX_VALUE) null else Rect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
}

/** 真实 LayoutNode 子树（含自身）。 */
internal fun LayoutInfo.subtree(): List<LayoutInfo> = listOf(this) + meChildren().flatMap { it.subtree() }

internal fun LayoutInfo.rootBounds(): Rect = coordinates.boundsInRoot()

/** 标记节点之下近似正方形、边长在 [min, max] dp 的叶子布局（图标盒；文字与按钮不是正方形叶子）。 */
internal fun SemanticsNodeInteraction.iconBoxes(
    min: Float = 12f,
    max: Float = 28f,
): List<Rect> {
    val node = fetchSemanticsNode()
    val d = node.layoutInfo.density.density
    return node.layoutInfo
        .subtree()
        .filter { it.meChildren().isEmpty() }
        .map { it.rootBounds() }
        .filter { abs(it.width - it.height) <= d && it.width / d in min..max }
        .distinct()
}

internal fun assertDp(
    message: String,
    expected: Float,
    actualPx: Float,
    density: Float,
    tolerance: Float = 1f,
) = assertEquals(message, expected, actualPx / density, tolerance)

internal fun assertAtLeastDp(
    message: String,
    expected: Float,
    actualPx: Float,
    density: Float,
) = assertTrue("$message：期望 ≥ $expected dp，实际 ${actualPx / density} dp", actualPx / density >= expected - 0.5f)

internal fun assertSeparate(
    message: String,
    first: Rect,
    second: Rect,
) {
    val i = first.intersect(second)
    assertTrue("$message：命中区重叠 $first / $second", i.width <= 0f || i.height <= 0f)
}

/** 合并树里的读屏片段：文字、内容描述、状态描述（读屏按这些读出）。 */
internal fun SemanticsNode.spokenParts(): List<String> =
    config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
        listOfNotNull(config.getOrNull(SemanticsProperties.StateDescription))

internal fun spokenContains(text: String): SemanticsMatcher =
    SemanticsMatcher("读屏含「$text」") { node -> node.spokenParts().any { it.contains(text) } }

/** 裁切判据（APP-11v-b r2）：宽度与位置同时比、逐行宽、省略、纵向；复用 ProductVisualAssertions v3。 */
internal fun SemanticsNodeInteraction.assertTextFully() = assertNoTextOverflow()
