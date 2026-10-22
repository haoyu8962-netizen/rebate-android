package com.couli.app.orders

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import com.couli.feature.orders.OrderTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

internal const val DETAIL_REFRESH = "order_detail_refresh"
internal const val DETAIL_BOTTOM = "order_detail_bottom_bar"
internal const val DETAIL_STATUS_ICON = "order_detail_status_icon"

internal fun ComposeContentTestRule.detailText(
    text: String,
    tag: String = OrderTags.DETAIL_STATUS,
): SemanticsNodeInteraction = onNode(hasText(text) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)

internal fun ComposeContentTestRule.detailDrawn(tag: String = OrderTags.DETAIL_STATUS): List<OrderDrawnText> =
    drawnTexts(onNodeWithTag(tag).fetchSemanticsNode().layoutInfo)

internal fun ComposeContentTestRule.detailAmount(text: String): OrderDrawnText {
    val matches = detailDrawn(OrderTags.DETAIL_AMOUNTS).filter { it.result.layoutInput.text.text == text }
    assertEquals("真实金额恰好一份：$text", 1, matches.size)
    return matches.single()
}

internal fun ComposeContentTestRule.clickDetail(tag: String) {
    val node = onNodeWithTag(tag)
    // 固定底栏没有滚动父节点；其余入口先滚入视口，再发真实触摸。
    if (generateSequence(node.fetchSemanticsNode()) {
            it.parent
        }.any { it.config.contains(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) }
    ) {
        node.performScrollTo()
    }
    node.fetchSemanticsNode().assertTouch48()
    node.performTouchInput { click(center) }
    waitForIdle()
}

internal fun ComposeContentTestRule.touchDetail(point: Offset) {
    val origin = onRoot().fetchSemanticsNode().boundsInRoot.topLeft
    onRoot().performTouchInput { click(point - origin) }
    waitForIdle()
}

internal fun SemanticsNode.assertInside(parent: SemanticsNode) {
    assertTrue(
        "必须位于状态卡内部：$boundsInRoot / ${parent.boundsInRoot}",
        boundsInRoot.left >= parent.boundsInRoot.left - 1 && boundsInRoot.right <= parent.boundsInRoot.right + 1 &&
            boundsInRoot.top >= parent.boundsInRoot.top - 1 && boundsInRoot.bottom <= parent.boundsInRoot.bottom + 1,
    )
    assertTrue("不能仅靠位置伪装卡内节点", generateSequence(this) { it.parent }.any { it.id == parent.id })
}
