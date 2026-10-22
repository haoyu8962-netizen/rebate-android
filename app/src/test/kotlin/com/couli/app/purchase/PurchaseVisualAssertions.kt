package com.couli.app.purchase

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import org.junit.Assert.assertTrue

internal fun ComposeContentTestRule.button(text: String): SemanticsNodeInteraction =
    onNode(hasText(text) and hasClickAction() and hasAnyAncestor(isDialog()))

/** 检查真实按钮填充色，不能用主次文案顺序代替主次样式。弹窗取自身 Window 的 View.draw。 */
internal fun ComposeContentTestRule.fillRatio(
    node: SemanticsNode,
    color: Color,
): Float {
    val view = (node.root as ViewRootForTest).view
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    runOnUiThread { view.draw(Canvas(bitmap)) }
    val bounds = node.boundsInRoot
    var matched = 0
    var total = 0
    for (x in bounds.left.toInt().coerceAtLeast(0) until bounds.right.toInt().coerceAtMost(bitmap.width)) {
        for (y in bounds.top.toInt().coerceAtLeast(0) until bounds.bottom.toInt().coerceAtMost(bitmap.height)) {
            total += 1
            if (bitmap.getPixel(x, y) == color.toArgb()) matched += 1
        }
    }
    bitmap.recycle()
    check(total > 0) { "不可用空区域冒充按钮" }
    return matched.toFloat() / total
}

internal fun assertPrimaryAndSecondary(
    primary: Float,
    secondary: Float,
) {
    assertTrue("主按钮须实际填充主色：$primary", primary > .5f)
    assertTrue("次按钮不能实际填充主色：$secondary", secondary < .1f)
}
