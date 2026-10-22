package com.couli.app.me

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.couli.app.product.textLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

internal fun ComposeContentTestRule.meTag(tag: String) = onNodeWithTag(tag, useUnmergedTree = true)

internal fun SemanticsNodeInteraction.meBounds() = fetchSemanticsNode().boundsInRoot

internal fun SemanticsNodeInteraction.meDensity() = fetchSemanticsNode().layoutInfo.density.density

internal fun ComposeContentTestRule.meReadouts(unmerged: Boolean): List<String> =
    onAllNodes(
        SemanticsMatcher("公共读屏节点") { it.config.contains(SemanticsProperties.ContentDescription) },
        useUnmergedTree = unmerged,
    ).fetchSemanticsNodes().flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }

internal fun MeVisualTestHost.assertReads(count: Int) = assertEquals(List(count) { "getMe" }, api.calls.map { it.operationId })

internal fun assertMeDp(
    expected: Float,
    actual: Float,
    density: Float,
) = assertEquals("dp", expected, actual / density, 1f)

/** 根坐标只转换一次；真实触摸，不调用语义 OnClick。 */
internal fun ComposeContentTestRule.meTouch(point: Offset) {
    val root = onRoot().fetchSemanticsNode().positionInRoot
    onRoot().performTouchInput { click(point - root) }
    waitForIdle()
}

internal fun assertMeTargetsSeparate(
    first: Rect,
    second: Rect,
    density: Float,
) {
    listOf(first, second).forEach { assertTrue("命中必须至少48dp：$it", it.width >= 48 * density - 1 && it.height >= 48 * density - 1) }
    val intersection = first.intersect(second)
    assertTrue("命中区不得重叠：$first / $second", intersection.width <= 0 || intersection.height <= 0)
}

/** 量真正末字，不量 weight 撑大的文字盒。复制图标的视觉中心由其触控中心求出。 */
internal fun SemanticsNodeInteraction.assertMeCopyFollows(copy: SemanticsNodeInteraction) {
    val layout = textLayout()
    val origin = fetchSemanticsNode().layoutInfo.coordinates.positionInRoot()
    val glyph = layout.getBoundingBox(layout.layoutInput.text.lastIndex).translate(origin)
    val center = copy.fetchSemanticsNode().touchBoundsInRoot.center
    val density = meDensity()
    val icon = Rect(center.x - 8 * density, center.y - 8 * density, center.x + 8 * density, center.y + 8 * density)
    val inline = kotlin.math.abs(icon.center.y - glyph.center.y) <= 5 * density
    if (inline) {
        assertTrue("复制须紧随实际末字，不能推到行尾：$glyph / $icon", icon.left - glyph.right in -1f..(16 * density))
    } else {
        assertTrue("复制不能停在多行文字中部", icon.top >= glyph.bottom - 1)
        assertTrue("复制换行紧跟末行", icon.top - glyph.bottom <= 12 * density)
        assertTrue("复制换行回到文字起点", kotlin.math.abs(icon.left - origin.x) <= 16 * density)
    }
}

/** Compose LayoutInfo未公开子列表；只读真实LayoutNode，升级时失败而不是猜尺寸。 */
internal fun androidx.compose.ui.layout.LayoutInfo.meChildren(): List<androidx.compose.ui.layout.LayoutInfo> =
    (javaClass.getMethod("getChildren\$ui").invoke(this) as List<*>).map { it as androidx.compose.ui.layout.LayoutInfo }
