package com.couli.app.wallet.visual

import android.graphics.Bitmap
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.couli.app.product.productPixels
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliIcons
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

internal const val E_REFERENCE_ALERT = "e_reference_alert_20"

/** 参考图标：同一 DS 三角在 20dp 下的实际墨迹，供错误条图标尺寸比对（不按源码猜尺寸）。 */
@Composable
internal fun ReferenceAlertIcon() {
    Icon(
        CouliIcons.alert,
        contentDescription = null,
        tint = CouliColors.statusErrorText,
        modifier = Modifier.size(20.dp).testTag(E_REFERENCE_ALERT),
    )
}

/** INFO 提示条（画板 #EFEAE2 底、14 主文字、20 次文字色信息图标）：浅灰底在卡外 16dp 起。 */
internal fun ComposeContentTestRule.assertInfoBar(
    p: PayoutIdentityProbe,
    host: PayoutIdentityVisualHost,
    text: String,
) {
    val style = p.style(text)
    assertEquals("INFO 条 14：$text", 14f, style.fontSize.value, .01f)
    assertEquals("INFO 条主文字色：$text", host.color("text.primary"), style.color)
    val bounds = p.bounds(text)
    val d =
        p
            .text(text)
            .fetchSemanticsNode()
            .layoutInfo.density.density
    val pixels = productPixels()
    assertTrue(
        "INFO 条浅灰底（卡外 16dp 起，不是白卡上的纯文字）：$text",
        near(pixels.pixel(18 * d, bounds.center.y), host.color("background.muted").argb(), 2),
    )
    val left = p.glyphLeft(text)
    assertTrue(
        "INFO 条左侧 20dp 次文字色信息图标：$text",
        pixels.countNear(Rect(left - 30 * d, bounds.top, left - 4 * d, bounds.top + 22 * d), host.color("text.secondary").argb(), 40) >
            12 * d,
    )
    pixels.assertInfoIconInk(Rect(left - 30 * d, bounds.top, left - 4 * d, bounds.top + 22 * d), host, "INFO 条图标：$text")
}

/**
 * FU-AND-0728 U1（F-AND-68，变异 M13「INFO 图标改主文字色」）：INFO 图标颜色判据收紧（上面「近次文字色像素 > 12dp」那条照旧保留，不放宽）。
 * 旧判据只数容差 40 的近次文字色像素，主文字色图标的抗锯齿边缘（主文字色与浅灰底的混合）也会落进去。这里改看「墨心」：图标框里离 INFO 底色最远的像素
 * （抗锯齿边缘都是墨色与底色的混合，离底色不会比墨心更远）必须贴近 text.secondary；并且框里不得出现贴近 text.primary 的像素
 * （次文字色图标与浅灰底的任何混合色离主文字色都在 170 以上，三通道差之和）。
 */
internal fun Bitmap.assertInfoIconInk(
    icon: Rect,
    host: PayoutIdentityVisualHost,
    what: String,
) {
    val secondary = host.color("text.secondary").argb()
    val primary = host.color("text.primary").argb()
    val background = host.color("background.muted").argb()
    var core = background
    var coreDistance = -1
    var primaryLike = 0
    for (x in icon.left.toInt().coerceAtLeast(0) until icon.right.toInt().coerceAtMost(width)) {
        for (y in icon.top.toInt().coerceAtLeast(0) until icon.bottom.toInt().coerceAtMost(height)) {
            val pixel = getPixel(x, y)
            val distance = inkDistance(pixel, background)
            if (distance > coreDistance) {
                coreDistance = distance
                core = pixel
            }
            if (inkDistance(pixel, primary) <= INK_TOLERANCE) primaryLike += 1
        }
    }
    assertTrue("$what：图标框里有墨迹（墨心离底色 $coreDistance）", coreDistance >= INK_PRESENT)
    assertTrue(
        "$what：图标墨心是次文字色（实际 #${Integer.toHexString(core)}，离 text.secondary ${inkDistance(core, secondary)}）",
        inkDistance(core, secondary) <= INK_TOLERANCE,
    )
    assertEquals("$what：图标框里没有主文字色像素", 0, primaryLike)
}

/** 三通道差之和（不看 alpha）。 */
private fun inkDistance(
    a: Int,
    b: Int,
): Int = listOf(16, 8, 0).sumOf { abs((a ushr it and 255) - (b ushr it and 255)) }

private const val INK_TOLERANCE = 24
private const val INK_PRESENT = 96

/** ERROR 语气错误条（W92：红底、红字 500、20dp 三角，assertive 播报不变）。图标尺寸与参考图标墨迹比对。 */
internal fun ComposeContentTestRule.assertErrorTone(
    p: PayoutIdentityProbe,
    colors: Map<String, androidx.compose.ui.graphics.Color>,
    tag: String,
    message: String,
) {
    val node = p.tag(tag).fetchSemanticsNode()
    val d = node.layoutInfo.density.density
    assertEquals(
        "错误条仍 assertive 播报一次",
        androidx.compose.ui.semantics.LiveRegionMode.Assertive,
        node.config.getOrNull(SemanticsProperties.LiveRegion),
    )
    val style = p.style(message)
    assertEquals("W92 错误条红字", colors.getValue("status.error.text"), style.color)
    assertEquals("W92 错误条 500", FontWeight.W500, style.fontWeight)
    assertEquals(14f, style.fontSize.value, .01f)
    val pixels = productPixels()
    val bar = node.boundsInRoot
    assertTrue("W92 红色浅底", near(pixels.pixel(bar.left + 4 * d, bar.center.y), colors.getValue("status.error.background").argb(), 2))
    val left = p.glyphLeft(message)
    val top = p.bounds(message).top
    val red = colors.getValue("status.error.text").argb()
    val ink = pixels.boxOf(Rect(bar.left, bar.top, left - 2 * d, bar.bottom), red, 40)
    assertNotNull("W92 左侧红色三角", ink)
    val reference = p.tag(E_REFERENCE_ALERT).fetchSemanticsNode().boundsInRoot
    val expected = checkNotNull(pixels.boxOf(reference, red, 40)) { "参考图标没有墨迹" }
    assertEquals("W92 三角 20dp（与 20dp 参考墨迹同高）", expected.height / d, ink!!.height / d, 1f)
    assertTrue("图标与首行对齐", ink.top >= top - 4 * d)
}

/** 读屏遍历顺序（未合并树先序）：返回带 [tag] 的节点序号。 */
internal fun ComposeContentTestRule.traversalIndex(tag: String): Int {
    val order = mutableListOf<SemanticsNode>()

    fun walk(node: SemanticsNode) {
        order += node
        node.children.forEach(::walk)
    }
    walk(onRoot(useUnmergedTree = true).fetchSemanticsNode())
    val index = order.indexOfFirst { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
    assertTrue("读屏树里找不到 $tag", index >= 0)
    return index
}
