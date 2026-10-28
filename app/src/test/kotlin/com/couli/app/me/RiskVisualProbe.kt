package com.couli.app.me

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/*
 * APP-12w-c 的量具（只读，复用 SettingsVisualProbe / PrivacyVisualTestHost 的文字盒、像素扫描与图标盒）：
 * - 卡片：从卡内一点按 surface 色向四周扫出外框（量外框不量内容盒，APP-12w-a T1）；通栏行贴卡内缘时取样列退到行左缘内 14dp（APP-12w-b v2 T1 / T2）；
 * - 胶囊 / 图标块：在文字或标题附近按令牌色取外接框（浅色 muted 与 canvas 只差 3–4，取色容差 ≤ 2）；
 * - 图标：真实 LayoutNode 正方形叶子（iconBoxes），按尺寸与位置筛。
 * 视觉断言先正后反：每个「没有」的断言都与同一量具量出的「有」对照。
 */

internal const val RISK_ROW_SAMPLE_DP = 14f

/** 从 (x, y) 出发按 [color] 向四周扫，得到同色连通区域的外接框（卡片 / 底栏 / 图标块外框）；起点不是 [color] 时为 null。 */
internal fun Bitmap.regionAround(
    x: Float,
    y: Float,
    color: Color,
    tolerance: Int = 2,
): Rect? {
    if (!sameColor(at(x, y), color, tolerance)) return null
    var top = y.toInt()
    while (top > 0 && sameColor(at(x, top - 1f), color, tolerance)) top--
    var bottom = y.toInt()
    while (bottom < height - 1 && sameColor(at(x, bottom + 1f), color, tolerance)) bottom++
    var left = x.toInt()
    while (left > 0 && sameColor(at(left - 1f, y), color, tolerance)) left--
    var right = x.toInt()
    while (right < width - 1 && sameColor(at(right + 1f, y), color, tolerance)) right++
    return Rect(left.toFloat(), top.toFloat(), right + 1f, bottom + 1f)
}

/** 文字所在卡片的外框：取样点在文字左缘外 [insetDp]（默认 8dp，落在卡内边距里）、文字竖直中线。不在卡里（底色不是 surface）时为 null。 */
internal fun Bitmap.cardOf(
    text: SemanticsNodeInteraction,
    surface: Color,
    insetDp: Float = 8f,
): Rect? {
    val box = text.textBox()
    val d = text.density()
    return regionAround(box.left - insetDp * d, box.center.y, surface)
}

/** 文字周围的令牌色外接框（胶囊 / 图标块底色）：在文字盒外扩 [padHDp] × [padVDp] 的范围里找。 */
internal fun Bitmap.tintAround(
    text: SemanticsNodeInteraction,
    color: Color,
    padHDp: Float = 40f,
    padVDp: Float = 14f,
    tolerance: Int = 2,
): Rect? {
    val box = text.box()
    val d = text.density()
    return pvColorBox(Rect(box.left - padHDp * d, box.top - padVDp * d, box.right + padHDp * d, box.bottom + padVDp * d), color, tolerance)
}

/** [page] 子树里与 [area] 相交、边长在 [min, max] dp 的正方形叶子（图标）。 */
internal fun SemanticsNodeInteraction.iconsIn(
    area: Rect,
    min: Float,
    max: Float,
): List<Rect> = iconBoxes(min, max).filter { it.overlaps(area) }

internal fun SemanticsNodeInteraction.weight(): FontWeight? = textStyle().fontWeight

internal fun SemanticsNodeInteraction.color(): Color = textStyle().color

internal fun assertColor(
    message: String,
    expected: Color,
    actual: Color,
) = assertTrue("$message：期望 $expected 实际 $actual", sameColor(actual.toArgb(), expected, 2))

/** 字号（sp，按 1 倍）。 */
internal fun assertFont(
    message: String,
    expectedSp: Float,
    node: SemanticsNodeInteraction,
) = assertEquals(message, expectedSp, node.textStyle().fontSize.value, 0.01f)

internal fun assertBox(
    message: String,
    box: Rect?,
): Rect {
    assertNotNull("$message：没有量到", box)
    return box!!
}

/** 合并树里读屏会读出的全部片段（含弹窗窗口外的页面）。 */
internal fun List<SemanticsNode>.spokenAll(): List<String> = flatMap { it.spokenParts() }

internal fun SemanticsNode.isHeadingNode(): Boolean = config.contains(SemanticsProperties.Heading)

internal fun SemanticsNode.hasClick(): Boolean = config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick)
