package com.couli.app.wallet.visual

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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
import com.couli.feature.wallet.WalletTags
import org.junit.Assert.assertTrue

internal fun ComposeContentTestRule.recordText(
    text: String,
    id: String,
): SemanticsNodeInteraction =
    onNode(
        hasText(text) and hasAnyAncestor(hasTestTag(WalletTags.RECORD_PREFIX + id)),
        useUnmergedTree = true,
    )

internal fun ComposeContentTestRule.record(id: String) = onNodeWithTag(WalletTags.RECORD_PREFIX + id, useUnmergedTree = true)

internal fun ComposeContentTestRule.drawnTexts(): List<String> =
    onAllNodes(
        androidx.compose.ui.test.SemanticsMatcher
            .keyIsDefined(SemanticsProperties.Text),
        useUnmergedTree = true,
    ).fetchSemanticsNodes().flatMap {
        it.config
            .getOrNull(SemanticsProperties.Text)
            .orEmpty()
            .map { text -> text.text }
    }

internal fun Bitmap.recordColorCount(
    bounds: Rect,
    color: Int,
): Int =
    (bounds.left.toInt().coerceAtLeast(0) until bounds.right.toInt().coerceAtMost(width)).sumOf { x ->
        (bounds.top.toInt().coerceAtLeast(0) until bounds.bottom.toInt().coerceAtMost(height)).count { y -> getPixel(x, y) == color }
    }

/** 真实触摸，包含48dp扩展区；不以语义performClick冒充边缘命中。 */
internal fun ComposeContentTestRule.touchRecordEdge(
    tag: String,
    edge: Int,
) {
    val target = onNodeWithTag(tag, useUnmergedTree = true).performScrollTo().fetchSemanticsNode()
    val b = target.touchBoundsInRoot
    val d = target.layoutInfo.density.density
    assertTrue("$tag 实际命中区至少48×48dp：$b", b.width >= 48 * d && b.height >= 48 * d)
    val point =
        when (edge) {
            0 -> Offset(b.center.x, b.top + d)
            1 -> Offset(b.center.x, b.bottom - d)
            2 -> Offset(b.left + d, b.center.y)
            else -> Offset(b.right - d, b.center.y)
        }
    val root = onRoot().fetchSemanticsNode().boundsInRoot
    onRoot().performTouchInput { click(point - root.topLeft) }
    waitForIdle()
}

internal fun assertRecordTargetsSeparate(
    first: Rect,
    second: Rect,
) {
    assertTrue("命中区不可重叠：$first / $second", first.intersect(second).let { it.width <= 0f || it.height <= 0f })
}
