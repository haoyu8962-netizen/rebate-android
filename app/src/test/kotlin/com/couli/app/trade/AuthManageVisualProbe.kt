@file:Suppress("MagicNumber")

package com.couli.app.trade

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.test.SemanticsNodeInteraction
import com.couli.app.me.countNear
import com.couli.app.me.edgeDown
import com.couli.app.me.edgeUp
import com.couli.app.me.firstFromLeft
import com.couli.app.me.firstFromRight
import com.couli.app.me.meChildren
import com.couli.app.me.regionAround
import com.couli.app.me.rootBounds
import com.couli.app.me.subtree
import com.couli.app.me.textBox
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/*
 * APP-06w 量具（只读；复用 com.couli.app.me 的 SettingsVisualProbe / RiskVisualProbe 文字盒、像素扫描、LayoutNode 子树）：
 * - 量外框不量内容盒：卡片 / 停用块 / 轻提示都按令牌色从内部一点扫到真实外缘；
 * - 卡片取样列退到卡左缘内 14dp（圆角 16 在 2dp 处深约 8dp，APP-12w-b v2 T1）；
 * - 平台图标是位图：像素断言避开图标区，图标只量 LayoutNode 外框（APP-06v / APP-11v 先例）；
 * - 视觉断言先正后反：每个「没有」都与同一量具量出的「有」对照。
 */

internal const val AM_SAMPLE_DP = 14f

/** [anchor]（卡内某行的文字）所在白卡的外框：同一行从左 / 右第一个 surface 像素，竖向在卡左缘内 14dp 扫。 */
internal fun AuthManageVisualHost.cardOf(
    anchor: SemanticsNodeInteraction,
    px: Bitmap = pixels(),
): Rect {
    val d = density()
    val surface = palette.surface
    val y = anchor.textBox().center.y
    val left = px.firstFromLeft(y, surface)
    val right = px.firstFromRight(y, surface)
    assertTrue("行文字在白卡里（左缘 $left / 右缘 $right）", left < anchor.textBox().left && right > anchor.textBox().right)
    val x = left + AM_SAMPLE_DP * d
    return Rect(left, px.edgeUp(x, y, surface), right, px.edgeDown(x, y, surface))
}

/** 圆角深度：外框上沿往下 [depthDp] 那一行，第一个 [color] 像素离外框左缘多远（dp）。 */
internal fun Bitmap.cornerInsetDp(
    box: Rect,
    color: Color,
    depthDp: Float,
    d: Float,
): Float = (firstFromLeft(box.top + depthDp * d, color, box.left) - box.left) / d

/** 半径 [r] 的圆角在 [depth] 深处的理论内缩（dp）。 */
internal fun cornerInsetFor(
    r: Float,
    depth: Float,
): Float = r - sqrt(r * r - (r - depth) * (r - depth))

/**
 * 圆角取样（v2 T5，能区分 16 与 24）：[card] 的左右缘在行文字中线处量（远离圆角）；上下缘改在卡片水平正中重量——[cardOf] 的上下缘在左缘内
 * 14dp 那一列取，24 圆角下会低约 2dp，旧的「2dp 深处内缩」因此分不出 16 与 24。取样都在像素中心、离外缘 [depthDp] 深处，避开最外一圈抗锯齿。
 */
internal class CornerSample(
    /** 左上：真实上沿往下 depth 那一行，第一个卡色像素离左缘（dp）。 */
    val upperLeft: Float,
    /** 左下：真实下沿往上 depth 那一行，同上。 */
    val lowerLeft: Float,
    /** 左上（竖向）：左缘往右 depth 那一列，卡色最上一行离真实上沿（dp）。 */
    val upperColumn: Float,
)

internal fun Bitmap.cornerSample(
    card: Rect,
    color: Color,
    depthDp: Float,
    d: Float,
): CornerSample {
    val top = edgeUp(card.center.x, card.top + d, color)
    val bottom = edgeDown(card.center.x, card.bottom - d, color)
    val half = 0.5f
    val upper = (firstFromLeft(top + depthDp * d + half, color, card.left - d) - card.left) / d
    val lower = (firstFromLeft(bottom - depthDp * d - half, color, card.left - d) - card.left) / d
    val column = (edgeUp(card.left + depthDp * d + half, top + CORNER_PROBE_DP * d, color) - top) / d
    return CornerSample(upper, lower, column)
}

/** 竖向取样起点：真实上沿往下 30dp（已过圆角、在行文字左侧 16dp 内的空白里）。 */
private const val CORNER_PROBE_DP = 30f

/** 文字左外侧 [gapDp]、上沿外 [upDp] 处出发按 [color] 扫出的连通外框（停用块 / 轻提示）。 */
internal fun Bitmap.blockAround(
    text: SemanticsNodeInteraction,
    color: Color,
    d: Float,
    gapDp: Float = 4f,
    upDp: Float = 4f,
): Rect? {
    val box = text.textBox()
    val start = regionAround(box.left - gapDp * d, box.top - upDp * d, color, 3) ?: return null
    // regionAround 的横向只沿起点那一行；纵向只沿起点那一列：两次合成外框
    return start
}

/** LayoutNode 子树里近似正方形、边长 [min]..[max] dp 的叶子（图标盒；平台图标、圆勾、警告三角、转圈）。 */
internal fun LayoutInfo.squareLeaves(
    d: Float,
    min: Float,
    max: Float,
): List<Rect> =
    subtree()
        .filter { it.meChildren().isEmpty() }
        .map { it.rootBounds() }
        .filter { abs(it.width - it.height) <= d && it.width / d in min..max }
        .distinct()

internal fun SemanticsNodeInteraction.squareLeaves(
    min: Float,
    max: Float,
): List<Rect> {
    val n = fetchSemanticsNode()
    return n.layoutInfo.squareLeaves(n.layoutInfo.density.density, min, max)
}

/** [rect] 里接近 [color] 的像素个数。 */
internal fun Bitmap.inkOf(
    rect: Rect,
    color: Color,
    tolerance: Int = 24,
): Int = countNear(rect, color, tolerance)

internal fun assertNear(
    message: String,
    expectedDp: Float,
    actualPx: Float,
    d: Float,
    tolerance: Float = 1.5f,
) = assertEquals("$message（dp）", expectedDp, actualPx / d, tolerance)

internal fun <T> assertSome(
    message: String,
    value: T?,
): T {
    assertNotNull("$message：没有量到", value)
    return value!!
}
