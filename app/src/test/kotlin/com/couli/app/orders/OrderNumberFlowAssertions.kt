package com.couli.app.orders

import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.SemanticsNodeInteraction
import com.couli.app.product.textLayout
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** M10：按 TextLayoutResult 末行的真实文字右端量间距，绝不能用 weight 后的节点盒子右边。 */
internal fun SemanticsNodeInteraction.assertCopyFollowsLastLine(copy: SemanticsNodeInteraction) {
    val numberLayout = textLayout()
    val copyLayout = copy.textLayout()
    val origin = fetchSemanticsNode().layoutInfo.coordinates.positionInRoot()
    val copyOrigin =
        copy
            .fetchSemanticsNode()
            .layoutInfo.coordinates
            .positionInRoot()
    val density = numberLayout.layoutInput.density.density
    val last = numberLayout.lineCount - 1
    assertEquals("复制必须作为一个整体，不拆成两行", 1, copyLayout.lineCount)
    val end = origin.x + numberLayout.getLineRight(last)
    val bottom = origin.y + numberLayout.getLineBottom(last)
    val baseline = origin.y + numberLayout.getLineBaseline(last)
    val copyLeft = copyOrigin.x + copyLayout.getLineLeft(0)
    val copyTop = copyOrigin.y + copyLayout.getLineTop(0)
    val copyBaseline = copyOrigin.y + copyLayout.getLineBaseline(0)
    if (abs(copyBaseline - baseline) <= 2 * density) {
        val gap = copyLeft - end
        assertTrue("复制距末行实际文字末端过远/重叠：gap=$gap，文字末端=$end", gap >= -1 && gap <= 16 * density)
    } else {
        assertTrue("复制不能停在多行单号中部：复制top=$copyTop，末行bottom=$bottom", copyTop >= bottom - 1)
        assertTrue("复制换行后必须紧接单号，不能留大段空白", copyTop - bottom <= 16 * density)
        val start = origin.x + numberLayout.getLineLeft(0)
        assertTrue("复制整体换行后应回到单号列起点", copyLeft - start >= -1 && copyLeft - start <= 16 * density)
    }
}
