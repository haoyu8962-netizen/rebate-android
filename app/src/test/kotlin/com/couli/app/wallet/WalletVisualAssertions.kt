package com.couli.app.wallet

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

internal fun ComposeContentTestRule.walletTag(tag: String) = onNodeWithTag(tag, useUnmergedTree = true)

internal fun ComposeContentTestRule.walletText(text: String) =
    onNode(
        SemanticsMatcher("真实绘制文字 $text") {
            it.walletRenderedText() == text
        },
        useUnmergedTree = true,
    )

internal fun ComposeContentTestRule.walletTextCount(text: String): Int =
    onAllNodes(
        SemanticsMatcher("可见文字包含 $text") { it.walletRenderedText()?.contains(text) == true },
        useUnmergedTree = true,
    ).fetchSemanticsNodes().size

internal fun SemanticsNodeInteraction.walletBounds(): Rect = fetchSemanticsNode().boundsInRoot

internal fun SemanticsNodeInteraction.walletDp(): Float = fetchSemanticsNode().layoutInfo.density.density

internal fun SemanticsNode.walletDescendants(): List<SemanticsNode> = listOf(this) + children.flatMap { it.walletDescendants() }

internal fun SemanticsNode.walletReadout(): String =
    walletDescendants().joinToString(" ") {
        (
            it.config
                .getOrNull(SemanticsProperties.Text)
                .orEmpty()
                .map { text -> text.text } +
                it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        ).joinToString(" ")
    }

/** 按实际最后一个字的位置，而不是可被 weight(1f) 撑宽的文字节点右边。 */
internal fun SemanticsNodeInteraction.walletLastGlyph(): Rect {
    val layout = walletTextLayout()
    val glyph = layout.getBoundingBox(layout.layoutInput.text.lastIndex)
    val origin = fetchSemanticsNode().layoutInfo.coordinates.positionInRoot()
    return glyph.translate(origin)
}

internal fun assertWalletGap(
    expectedDp: Float,
    actualPx: Float,
    density: Float,
) = assertEquals("画板间距（dp）", expectedDp, actualPx / density, 2f)

internal fun WalletVisualTestHost.assertSummaryReads(count: Int) {
    assertEquals("钱包只可发送摘要读取；不得加 rules / getMe / 写请求", List(count) { "getWalletSummary" }, api.calls.map { it.operationId })
    assertTrue("不得确认/放弃未决资金请求", api.pendingWrites.isEmpty())
}

/** 精确匹配一个独立语义节点；不从整页拼接文字中寻找子串，也不读渲染探针。 */
internal fun ComposeContentTestRule.assertWalletHiddenReadouts(
    names: List<String>,
    unmerged: Boolean,
) {
    names.forEach { name ->
        val nodes = onAllNodesWithContentDescription(name, substring = false, useUnmergedTree = unmerged)
        nodes.assertCountEquals(1)
        assertEquals(
            "必须是独立读法，不能并进其他金额项：$name",
            listOf(name),
            nodes
                .fetchSemanticsNodes()
                .single()
                .config
                .getOrNull(SemanticsProperties.ContentDescription),
        )
    }
}
