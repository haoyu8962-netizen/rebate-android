package com.couli.app.wallet.visual

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextStyle
import com.couli.app.product.outerBounds
import com.couli.app.product.textLayout
import kotlin.math.abs
import org.robolectric.shadows.ShadowDialog

/** APP-09v-e 共用的几何 / 像素探针。裁切判据同时比尺寸与位置；像素一律在实际绘制结果上取，不以源码或语义代替。 */
internal class PayoutIdentityProbe(
    private val rule: ComposeContentTestRule,
) {
    fun tag(id: String): SemanticsNodeInteraction = rule.onNodeWithTag(id, useUnmergedTree = true)

    fun text(label: String): SemanticsNodeInteraction = rule.onNodeWithText(label, useUnmergedTree = true)

    fun style(label: String): TextStyle = text(label).textLayout().layoutInput.style

    fun bounds(label: String): Rect = text(label).fetchSemanticsNode().boundsInRoot

    /** 实际字形左缘（不是 Text 节点左缘）：右对齐 / 居中时节点比字形宽。 */
    fun glyphLeft(label: String): Float {
        val node = text(label).fetchSemanticsNode()
        val layout = text(label).textLayout()
        return node.boundsInRoot.left + layout.getLineLeft(0)
    }

    fun glyphRight(label: String): Float {
        val node = text(label).fetchSemanticsNode()
        val layout = text(label).textLayout()
        return node.boundsInRoot.left + (0 until layout.lineCount).maxOf { layout.getLineRight(it) }
    }

    /** 顶栏底边：从标题向上，取高度仍低于 80dp 的最外层祖先（顶栏本身），不依赖顶栏内部结构。 */
    fun navBottom(title: String): Float {
        val node = text(title).fetchSemanticsNode()
        val d = node.layoutInfo.density.density
        val nav = generateSequence(node.layoutInfo) { it.parentInfo }.takeWhile { it.height / d < 80f }.last()
        return outerBounds(nav).bottom
    }

    /** 输入框可见框（含描边）：从输入节点向上找第一个高度 ≥ 44dp 的祖先；不拿 48dp 命中区或整列冒充。 */
    fun fieldBox(id: String): Rect {
        val node = tag(id).fetchSemanticsNode()
        val d = node.layoutInfo.density.density
        val box = generateSequence(node.layoutInfo) { it.parentInfo }.first { it.height / d >= 44f }
        return outerBounds(box)
    }

    fun layoutOf(id: String): LayoutInfo = tag(id).fetchSemanticsNode().layoutInfo
}

/**
 * v2：只有节点确有可滚动祖先（与 performScrollTo 同一判据：祖先带 SemanticsActions.ScrollBy）时才先滚到可见；
 * 底栏、半屏底部按钮、读取失败态等不在滚动容器里的固定节点原样返回。调用方随后一律用真实触摸点击中心并计数，不改用语义动作。
 */
internal fun SemanticsNodeInteraction.scrollToIfScrollable(): SemanticsNodeInteraction {
    val scrollable =
        generateSequence(fetchSemanticsNode().parent) { it.parent }
            .any { it.config.contains(SemanticsActions.ScrollBy) }
    return if (scrollable) performScrollTo() else this
}

internal fun Bitmap.pixel(
    x: Float,
    y: Float,
): Int = getPixel(x.toInt().coerceIn(0, width - 1), y.toInt().coerceIn(0, height - 1))

internal fun Color.argb(): Int = toArgb()

internal fun near(
    actual: Int,
    expected: Int,
    tolerance: Int = 6,
): Boolean = listOf(16, 8, 0).all { abs((actual ushr it and 255) - (expected ushr it and 255)) <= tolerance }

internal fun Bitmap.countNear(
    rect: Rect,
    color: Int,
    tolerance: Int = 24,
): Int {
    var count = 0
    for (x in rect.left.toInt().coerceAtLeast(0) until rect.right.toInt().coerceAtMost(width)) {
        for (y in rect.top.toInt().coerceAtLeast(0) until rect.bottom.toInt().coerceAtMost(height)) {
            if (near(getPixel(x, y), color, tolerance)) count++
        }
    }
    return count
}

/** 区域内与 [color] 接近的像素外接矩形；没有则 null。 */
internal fun Bitmap.boxOf(
    rect: Rect,
    color: Int,
    tolerance: Int = 24,
): Rect? {
    var left = Int.MAX_VALUE
    var top = Int.MAX_VALUE
    var right = Int.MIN_VALUE
    var bottom = Int.MIN_VALUE
    for (x in rect.left.toInt().coerceAtLeast(0) until rect.right.toInt().coerceAtMost(width)) {
        for (y in rect.top.toInt().coerceAtLeast(0) until rect.bottom.toInt().coerceAtMost(height)) {
            if (near(getPixel(x, y), color, tolerance)) {
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x + 1)
                bottom = maxOf(bottom, y + 1)
            }
        }
    }
    return if (left == Int.MAX_VALUE) null else Rect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
}

/** 半屏 / 弹窗是独立窗口：只画最新 Dialog 的 decor，并给出该窗口内 Compose 根到位图的偏移。 */
internal class DialogPixels(
    val image: Bitmap,
    private val dx: Float,
    private val dy: Float,
) {
    fun map(rect: Rect): Rect = rect.translate(dx, dy)
}

internal fun ComposeContentTestRule.dialogPixels(node: SemanticsNode): DialogPixels {
    val decor = requireNotNull(ShadowDialog.getLatestDialog()?.window).decorView
    val owner = (node.root as ViewRootForTest).view
    val image = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
    runOnUiThread { decor.draw(Canvas(image)) }
    val ownerAt = location(owner)
    val decorAt = location(decor)
    return DialogPixels(image, (ownerAt[0] - decorAt[0]).toFloat(), (ownerAt[1] - decorAt[1]).toFloat())
}

private fun location(view: View): IntArray = IntArray(2).also(view::getLocationOnScreen)
