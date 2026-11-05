package com.couli.app.search.visual

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import com.couli.app.product.textLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

internal fun SemanticsNode.assertSearchTouch48() {
    val d = layoutInfo.density.density
    assertTrue("命中宽至少48", touchBoundsInRoot.width / d >= 47.5f)
    assertTrue("命中高至少48", touchBoundsInRoot.height / d >= 47.5f)
    assertTrue("语义高至少48", boundsInRoot.height / d >= 47.5f)
}

internal fun SemanticsNodeInteraction.assertSearchFont(size: Float) {
    assertEquals(
        size,
        textLayout()
            .layoutInput.style.fontSize.value,
        .01f,
    )
}

/** 读真实布局的修饰链，不要求实现添加测试 tag；升级 Compose 后探针失配应失败。 */
internal fun searchLayoutTree(root: LayoutInfo): List<LayoutInfo> {
    val children = root.javaClass.getMethod("getChildrenInfo").invoke(root) as List<*>
    return listOf(root) + children.filterIsInstance<LayoutInfo>().flatMap(::searchLayoutTree)
}

internal fun ComposeContentTestRule.assertSearchCapsule32(node: SemanticsNode) {
    runOnUiThread {
        val borders =
            searchLayoutTree(node.layoutInfo).flatMap { layout ->
                layout.getModifierInfo().filter {
                    it.modifier.javaClass.simpleName
                        .contains("Border")
                }
            }
        assertEquals("每个胶囊恰好一个描边外框", 1, borders.size)
        val coordinates = checkNotNull(borders.single().coordinates)
        assertEquals("量描边外框，非文字盒", 32f, coordinates.size.height / node.layoutInfo.density.density, 1f)
    }
    node.assertSearchTouch48()
}

internal fun SemanticsNode.searchTop(): Float = layoutInfo.coordinates.positionInRoot().y

internal fun ComposeContentTestRule.searchPixels(node: SemanticsNode): Bitmap {
    val view = (node.root as ViewRootForTest).view
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    runOnUiThread { view.draw(Canvas(bitmap)) }
    return bitmap
}
