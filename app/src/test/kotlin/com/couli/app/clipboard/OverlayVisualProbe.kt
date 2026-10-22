package com.couli.app.clipboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.robolectric.shadows.ShadowDialog

/** T5：用 View.draw 读取真实像素，不调用 Robolectric 会超时的 captureToImage；不写磁盘。 */
internal class OverlayVisualProbe(
    private val rule: ComposeContentTestRule,
) {
    fun bitmap(
        node: SemanticsNode,
        block: (Bitmap) -> Unit,
    ) {
        val view = (node.root as ViewRootForTest).view
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            rule.runOnUiThread { view.draw(Canvas(image)) }
            block(image)
        } finally {
            image.recycle()
        }
    }

    fun assertTarget48(node: SemanticsNode) {
        val density = node.layoutInfo.density.density
        assertTrue("点击宽度至少 48dp", node.touchBoundsInRoot.width >= 48f * density - 1)
        assertTrue("点击高度至少 48dp", node.touchBoundsInRoot.height >= 48f * density - 1)
    }

    fun assertOutline(
        node: SemanticsNode,
        surface: Int,
        border: Int,
    ) {
        val b = node.boundsInRoot
        bitmap(node) { image ->
            val x = b.center.x.toInt()
            val edge = (b.top.toInt() until (b.top + 5 * node.layoutInfo.density.density).toInt()).map { y -> image.getPixel(x, y) }
            assertTrue("次按钮上沿必须有真实描边，文字按钮不能冒充", border in edge)
            assertEquals("按钮内侧是 surface 底", surface, image.getPixel(x, (b.top + 8 * node.layoutInfo.density.density).toInt()))
        }
    }

    fun assertTextFits(node: SemanticsNodeInteraction) {
        val result = node.overlayTextLayout()
        val text = result.layoutInput.text.text
        val semantics = node.fetchSemanticsNode()
        val bounds = semantics.boundsInRoot
        // 与 APP-06v r2 修复同判据：从未裁切原点与实测 size 还原文字布局，核对可见区内的位置。
        // 不用语义重建的 MultiParagraph.width，避免再次把 T1 的父约束宽当成文字宽。
        val origin = semantics.layoutInfo.coordinates.positionInRoot()
        val layout = Rect(origin.x, origin.y, origin.x + result.size.width, origin.y + result.size.height)
        assertTrue(
            "横向裁切（位置）：$text，文字布局=$layout，可见区域=$bounds",
            layout.left >= bounds.left - 1f && layout.right <= bounds.right + 1f,
        )
        assertTrue("横向裁切（父容器宽度）：$text", result.size.width <= bounds.width + 1f)
        assertTrue(
            "纵向裁切（位置）：$text，文字布局=$layout，可见区域=$bounds",
            layout.top >= bounds.top - 1f && layout.bottom <= bounds.bottom + 1f,
        )
        // 与 APP-06v T1 同判据：String Text 可按父约束重建段落，Center 行坐标也相对父宽。
        // 因而比较 right-left 与可见宽，不能比较绝对 right；仅容许 1 物理像素取整误差。
        val available = minOf(result.size.width.toFloat(), bounds.width)
        if (!result.layoutInput.softWrap) {
            // 语义重建可能把禁止换行的文字拆行；固有宽度仍须能放进实际可见区域。
            val intrinsicWidth = result.multiParagraph.maxIntrinsicWidth
            assertTrue("横向裁切：$text，固有宽=$intrinsicWidth，可见宽=$available", intrinsicWidth <= available + 1f)
        }
        repeat(result.lineCount) { line ->
            val lineWidth = result.getLineRight(line) - result.getLineLeft(line)
            assertTrue("横向裁切：$text，行宽=$lineWidth，可见宽=$available", lineWidth <= available + 1f)
            assertFalse("省略：$text", result.isLineEllipsized(line))
        }
        assertFalse("纵向裁切：$text", result.didOverflowHeight)
    }

    @Suppress("DEPRECATION") // 与 DS-02 原生交互测试一致，触发系统窗口返回入口。
    fun back() {
        val dialog = requireNotNull(ShadowDialog.getLatestDialog())
        assertTrue("必须使用系统模态窗口", dialog.isShowing)
        rule.runOnUiThread { dialog.onBackPressed() }
        rule.waitForIdle()
    }

    fun scrim() {
        val decor = requireNotNull(ShadowDialog.getLatestDialog().window).decorView
        rule.runOnUiThread {
            val now = SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
                val event = MotionEvent.obtain(now, now, action, 8f, 100f, 0)
                decor.dispatchTouchEvent(event)
                event.recycle()
            }
        }
        rule.waitForIdle()
    }

    fun swipeSheetDown(node: SemanticsNodeInteraction) {
        node.performTouchInput {
            swipe(Offset(center.x, 2f), Offset(center.x, height - 2f), 200)
        }
        rule.waitForIdle()
    }
}

internal fun SemanticsNodeInteraction.overlayTextLayout(): TextLayoutResult {
    val results = mutableListOf<TextLayoutResult>()
    performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
    return results.single()
}

internal fun paneNamed(name: String): SemanticsMatcher = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, name)

internal fun SemanticsNode.descendants(): List<SemanticsNode> = listOf(this) + children.flatMap { it.descendants() }
