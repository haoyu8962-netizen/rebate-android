package com.couli.app.orders

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import com.couli.feature.orders.OrderTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** AmountGrid 的数值 Text 清空读屏语义；只读实际 Text 节点的排版缓存，不向产品添加测试语义。 */
internal fun ComposeContentTestRule.orderDrawnTexts(id: String): List<OrderDrawnText> =
    drawnTexts(onNodeWithTag(OrderTags.CARD_PREFIX + id).fetchSemanticsNode().layoutInfo)

internal fun ComposeContentTestRule.drawnTexts(root: LayoutInfo): List<OrderDrawnText> {
    var result = emptyList<OrderDrawnText>()
    runOnUiThread { result = layoutTree(root).flatMap(::drawnTextAt) }
    return result
}

internal fun ComposeContentTestRule.orderAmount(
    text: String,
    id: String = "A",
): OrderDrawnText {
    val matches = orderDrawnTexts(id).filter { it.result.layoutInput.text.text == text }
    assertEquals("真实绘制文字应恰好一份：$text；实际=${orderDrawnTexts(id).map { it.result.layoutInput.text.text }}", 1, matches.size)
    return matches.single().also { assertTrue("$text 应可见", it.bounds.width > 0 && it.bounds.height > 0) }
}

/** Compose 版本由本仓锁定；反射仅越过内部可见性读取布局，不改节点或语义。版本不兼容直接失败，不静默跳过。 */
private fun layoutTree(root: LayoutInfo): List<LayoutInfo> {
    val children = root.javaClass.getMethod("getChildrenInfo").invoke(root) as List<*>
    return listOf(root) + children.filterIsInstance<LayoutInfo>().flatMap(::layoutTree)
}

private fun drawnTextAt(layout: LayoutInfo): List<OrderDrawnText> {
    val chain =
        layout.javaClass.methods
            .single { it.name.startsWith("getNodes$") }
            .invoke(layout)
    val head =
        chain.javaClass.methods
            .single { it.name.startsWith("getHead$") }
            .invoke(chain)
    val nodes =
        generateSequence(head) { node ->
            node.javaClass.methods
                .single { it.name.startsWith("getChild$") }
                .invoke(node)
        }
    return nodes
        .filter { it.javaClass.name.startsWith("androidx.compose.foundation.text.modifiers.Text") }
        .mapNotNull { node ->
            val apply = node.javaClass.methods.singleOrNull { it.name == "applySemantics" } ?: return@mapNotNull null
            val config = SemanticsConfiguration()
            apply.invoke(node, config)
            val results = mutableListOf<TextLayoutResult>()
            config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            results.singleOrNull()?.let { OrderDrawnText(it, layout) }
        }.toList()
}

internal class OrderDrawnText(
    val result: TextLayoutResult,
    private val layoutInfo: LayoutInfo,
) {
    val bounds: Rect get() = layoutInfo.coordinates.boundsInRoot()

    /** 与 main ProductVisualAssertions v3 相同判据：实测盒的位置+宽度、逐行宽、固有宽、无省略和纵向溢出。 */
    fun assertNoOverflow() {
        val text = result.layoutInput.text.text
        val origin = layoutInfo.coordinates.positionInRoot()
        val layout = Rect(origin.x, origin.y, origin.x + result.size.width, origin.y + result.size.height)
        val visible = bounds
        assertTrue("横向裁切（位置）：$text，$layout / $visible", layout.left >= visible.left - 1 && layout.right <= visible.right + 1)
        assertTrue("横向裁切（父容器宽度）：$text", result.size.width <= visible.width + 1)
        assertTrue("纵向裁切（位置）：$text，$layout / $visible", layout.top >= visible.top - 1 && layout.bottom <= visible.bottom + 1)
        val available = minOf(result.size.width.toFloat(), visible.width)
        if (!result.layoutInput.softWrap) {
            assertTrue("横向裁切：$text", result.multiParagraph.maxIntrinsicWidth <= available + 1)
        }
        repeat(result.lineCount) { line ->
            assertTrue("横向裁切：$text", result.getLineRight(line) - result.getLineLeft(line) <= available + 1)
            assertFalse("省略：$text", result.isLineEllipsized(line))
        }
        assertFalse("纵向裁切：$text", result.didOverflowHeight)
    }
}
