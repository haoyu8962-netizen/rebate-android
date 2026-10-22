package com.couli.app.orders

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import com.couli.feature.orders.OrderTags
import org.junit.Assert.assertTrue

internal const val FIND_PINNED = "orders_find_pinned"

internal fun ComposeContentTestRule.cardText(
    text: String,
    id: String = "A",
): SemanticsNodeInteraction = onNode(hasText(text) and hasAnyAncestor(hasTestTag(OrderTags.CARD_PREFIX + id)), useUnmergedTree = true)

internal fun SemanticsNode.assertTouch48() {
    val density = layoutInfo.density.density
    assertTrue("实际命中区至少48×48dp：$touchBoundsInRoot", touchBoundsInRoot.width >= 48 * density - 1)
    assertTrue("实际命中区至少48×48dp：$touchBoundsInRoot", touchBoundsInRoot.height >= 48 * density - 1)
}

internal fun ComposeContentTestRule.touch(point: Offset) {
    val page = onNodeWithTag(OrderTags.LIST_PAGE)
    val origin = page.fetchSemanticsNode().boundsInRoot.topLeft
    page.performTouchInput { click(point - origin) }
    waitForIdle()
}

/** O3/O6/O11/O13/O18：通过实际 View.draw 取像素；不能用 Robolectric 的 captureToImage。 */
internal fun ComposeContentTestRule.withOrderPixels(
    node: SemanticsNode,
    block: (Bitmap) -> Unit,
) {
    val view = (node.root as ViewRootForTest).view
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    try {
        runOnUiThread { view.draw(Canvas(bitmap)) }
        block(bitmap)
    } finally {
        bitmap.recycle()
    }
}

internal fun Bitmap.colorCount(
    rect: Rect,
    color: Int,
): Int =
    (rect.left.toInt().coerceAtLeast(0) until rect.right.toInt().coerceAtMost(width)).sumOf { x ->
        (rect.top.toInt().coerceAtLeast(0) until rect.bottom.toInt().coerceAtMost(height)).count { y -> getPixel(x, y) == color }
    }

internal fun Rect.insetBy(value: Float): Rect = Rect(left + value, top + value, right - value, bottom - value)
