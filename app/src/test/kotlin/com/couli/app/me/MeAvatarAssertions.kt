package com.couli.app.me

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithText
import com.couli.app.product.commonLayout
import com.couli.app.product.iconMaskDifference
import com.couli.app.product.inkPixels
import com.couli.app.product.outerBounds
import com.couli.app.product.productPixels
import com.couli.core.me.MeDraftTexts
import com.couli.feature.me.MeTags
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** 沿用 MeVisualStructureTest 的真实布局取法；不要求新增 tag 或产品 API。 */
internal fun ComposeContentTestRule.avatarBounds(guest: Boolean = false): Rect {
    val column =
        if (guest) {
            commonLayout(
                onNodeWithText(MeDraftTexts.text("me.guest.title"), useUnmergedTree = true).fetchSemanticsNode().layoutInfo,
                onNodeWithText(MeDraftTexts.text("me.guest.desc"), useUnmergedTree = true).fetchSemanticsNode().layoutInfo,
            )
        } else {
            commonLayout(
                meTag(MeTags.ME_NICKNAME).fetchSemanticsNode().layoutInfo,
                meTag(MeTags.ME_INVITER).fetchSemanticsNode().layoutInfo,
            )
        }
    val row = checkNotNull(column.parentInfo)
    val avatar = row.meChildren().first { it !== column }
    val bounds = outerBounds(avatar)
    val density = avatar.density.density
    assertMeDp(56f, bounds.width, density)
    assertMeDp(56f, bounds.height, density)
    return bounds
}

internal fun ComposeContentTestRule.assertAvatarImage(
    host: MeAvatarTestHost,
    color: Int,
) {
    val rect = avatarBounds()
    val pixels = productPixels()
    try {
        val radius = rect.width / 2f
        val inset = rect.width / 28f // 2dp：避开抗锯齿边，不避开圆内的加载空白或方图四角。
        val width = rect.width.toInt()
        val surface = host.surface.toArgb()
        val wrong =
            (0 until width * rect.height.toInt())
                .asSequence()
                .map { index -> rect.left.toInt() + index % width to rect.top.toInt() + index / width }
                .filter { (x, y) -> abs(distance(rect, x, y) - radius) > inset }
                .firstOrNull { (x, y) -> pixels.getPixel(x, y) != if (distance(rect, x, y) < radius) color else surface }
        assertTrue("头像须是 56dp 圆内满铺服务端图、圆外露卡片底；首个不符像素=$wrong", wrong == null)
    } finally {
        pixels.recycle()
    }
}

private fun distance(
    rect: Rect,
    x: Int,
    y: Int,
): Float {
    val dx = x + .5f - rect.center.x
    val dy = y + .5f - rect.center.y
    return kotlin.math.sqrt(dx * dx + dy * dy)
}

internal fun ComposeContentTestRule.assertAvatarFallback(
    host: MeAvatarTestHost,
    guest: Boolean = false,
) {
    val actual = avatarBounds(guest)
    val reference = meTag(AVATAR_REFERENCE).meBounds()
    val pixels = productPixels()
    try {
        // 比较中间 40dp 的真实人形轮廓，不能用“有一个占位节点”或“背景非空”代替。
        val inset = actual.width * 8f / 56f
        val actualIcon = actual.deflate(inset)
        val referenceIcon = reference.deflate(inset)
        val size = actualIcon.width.toInt()
        val background = host.muted.toArgb()
        assertTrue("内置人形参照必须有墨迹", pixels.inkPixels(referenceIcon, background) > 40)
        val difference = pixels.iconMaskDifference(actualIcon, referenceIcon, size, background)
        assertTrue("必须保留内置人形，轮廓差异=$difference", difference < size * size / 100)
        assertEquals(background, pixels.getPixel(actual.center.x.toInt(), (actual.top + actual.height * .1f).toInt()))
        assertEquals(host.surface.toArgb(), pixels.getPixel((actual.left + 2).toInt(), (actual.top + 2).toInt()))
    } finally {
        pixels.recycle()
    }
}

internal fun ComposeContentTestRule.assertAvatarDecorative(guest: Boolean = false) {
    val rect = avatarBounds(guest)
    listOf(false, true).forEach { unmerged ->
        val nodes = onAllNodes(SemanticsMatcher("全部节点") { true }, useUnmergedTree = unmerged).fetchSemanticsNodes()
        val avatarNodes =
            nodes.filter {
                val b = it.boundsInRoot
                b.width > 0 && b.height > 0 && b.left >= rect.left && b.right <= rect.right && b.top >= rect.top && b.bottom <= rect.bottom
            }
        avatarNodes.forEach {
            assertTrue(
                "头像不能单独朗读",
                it.config
                    .getOrNull(SemanticsProperties.ContentDescription)
                    .orEmpty()
                    .isEmpty(),
            )
            assertTrue(
                "头像不能带朗读文字",
                it.config
                    .getOrNull(SemanticsProperties.Text)
                    .orEmpty()
                    .isEmpty(),
            )
            assertTrue("头像不能单独获得焦点", !it.config.contains(SemanticsProperties.Focused))
            assertTrue("头像不是按钮", !it.config.contains(SemanticsActions.OnClick))
            assertTrue("装饰头像不声明独立角色", !it.config.contains(SemanticsProperties.Role))
        }
    }
}
