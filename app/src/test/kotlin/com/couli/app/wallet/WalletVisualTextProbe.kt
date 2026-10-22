package com.couli.app.wallet

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * AmountGrid 为避免重复读屏，数值 Text 使用 clearAndSetSemantics。只读探针从真实 Text modifier 取排版，
 * 不把数字补回语义树、不重新测量一份假 Text。锁定 Compose 1.11.4；升级若内部形状变化必须显式失败。
 * 读屏断言仍用 walletReadout / 公共语义查询，不走此探针。
 */
internal fun SemanticsNode.walletRenderedText(): String? =
    layoutInfo.getModifierInfo().firstNotNullOfOrNull { info ->
        val element = info.modifier
        if (element.javaClass.simpleName !in
            setOf("TextStringSimpleElement", "TextAnnotatedStringElement")
        ) {
            return@firstNotNullOfOrNull null
        }
        val value =
            element.javaClass
                .getDeclaredField("text")
                .apply { isAccessible = true }
                .get(element)
        when (value) {
            is String -> value
            is AnnotatedString -> value.text
            else -> error("未知 Text modifier 内容")
        }
    }

internal fun SemanticsNodeInteraction.walletTextLayout(): TextLayoutResult {
    val node = fetchSemanticsNode()
    val result = mutableListOf<TextLayoutResult>()
    val action =
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: node
            .walletTextSemantics()
            .getOrNull(SemanticsActions.GetTextLayoutResult)
            ?.action
    checkNotNull(action) { "真实文字没有排版探针：${node.walletRenderedText()}" }(result)
    return result.single()
}

private fun SemanticsNode.walletTextSemantics(): SemanticsConfiguration {
    val chain = layoutInfo.javaClass.getMethod("getNodes\$ui").invoke(layoutInfo)
    val head = chain.javaClass.getMethod("getHead\$ui").invoke(chain)
    val nodes = generateSequence(head) { it.javaClass.getMethod("getChild\$ui").invoke(it) }
    val text = nodes.first { it.javaClass.simpleName in setOf("TextStringSimpleNode", "TextAnnotatedStringNode") }
    return SemanticsConfiguration().also {
        text.javaClass.getMethod("applySemantics", SemanticsPropertyReceiver::class.java).invoke(text, it)
    }
}

/** 与 main ProductVisualAssertions v3 相同判据；唯一差异是可以读取被清语义的真实金额 Text。 */
internal fun SemanticsNodeInteraction.assertWalletTextWhole() {
    val result = walletTextLayout()
    val text = result.layoutInput.text.text
    val node = fetchSemanticsNode()
    val bounds = node.boundsInRoot
    val origin = node.layoutInfo.coordinates.positionInRoot()
    val layout = Rect(origin.x, origin.y, origin.x + result.size.width, origin.y + result.size.height)
    assertTrue("横向裁切（位置）：$text，$layout / $bounds", layout.left >= bounds.left - 1f && layout.right <= bounds.right + 1f)
    assertTrue("横向裁切（父容器宽度）：$text", result.size.width <= bounds.width + 1f)
    assertTrue("纵向裁切（位置）：$text，$layout / $bounds", layout.top >= bounds.top - 1f && layout.bottom <= bounds.bottom + 1f)
    val available = minOf(result.size.width.toFloat(), bounds.width)
    if (!result.layoutInput.softWrap) {
        assertTrue("横向裁切：$text", result.multiParagraph.maxIntrinsicWidth <= available + 1f)
    }
    repeat(result.lineCount) { line ->
        assertTrue("横向裁切：$text", result.getLineRight(line) - result.getLineLeft(line) <= available + 1f)
        assertFalse("省略：$text", result.isLineEllipsized(line))
    }
    assertFalse("纵向裁切：$text", result.didOverflowHeight)
}
