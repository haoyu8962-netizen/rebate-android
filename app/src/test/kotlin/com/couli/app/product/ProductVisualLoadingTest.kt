package com.couli.app.product

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** D10：不能只给原来的两块骨架补一个 liveRegion；按真实内容结构摆六个块。 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h956dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProductVisualLoadingTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { ProductVisualTestHost(rule) }

    @After fun close() = host.close()

    @Test fun skeletonContainsHeroPriceRebateTwoTitleLinesAndBottomButton() {
        host.api.hold = CompletableDeferred()
        host.launch()
        val root = rule.onRoot().fetchSemanticsNode()
        val density = root.layoutInfo.density.density
        val pixels = rule.productPixels()
        val blocks =
            try {
                pixels.skeletonBlocks(host.muted.toArgb(), density.toInt())
            } finally {
                pixels.recycle()
            }
        assertTrue("头图、40%价格、返利、两行标题、底栏按钮：实际 $blocks", blocks.size >= 6)
        val hero = blocks.maxBy { it.width * it.height }
        assertTrue("头图骨架同比例", abs(hero.width / hero.height - 358f / 262f) < .02f)
        val priceCardWidth = 326 * density
        assertTrue("40%价格行", blocks.any { abs(it.width - priceCardWidth * .4f) < 4 * density })
        assertTrue("70%标题行", blocks.any { abs(it.width - priceCardWidth * .7f) < 4 * density })
        assertTrue("100%标题行", blocks.any { abs(it.width - priceCardWidth) < 4 * density })
        assertTrue("底栏按钮骨架", blocks.any { it.top > root.boundsInRoot.bottom - 120 * density && it.height >= 48 * density })
    }
}

/** 固定密度网格上的连通色块，不存 PNG、不创建基准。16 平方 dp 以下的碎点不算骨架。 */
private fun Bitmap.skeletonBlocks(
    color: Int,
    step: Int,
): List<Rect> {
    val columns = width / step
    val rows = height / step
    val remaining = BooleanArray(columns * rows) { i -> getPixel(i % columns * step, i / columns * step) == color }
    val result = mutableListOf<Rect>()
    for (start in remaining.indices) {
        if (remaining[start]) result += takeBlock(remaining, start, columns, step)
    }
    return result.filter { it.width * it.height >= 16 * step * step }
}

private fun takeBlock(
    remaining: BooleanArray,
    start: Int,
    columns: Int,
    step: Int,
): Rect {
    val queue = ArrayDeque<Int>()
    queue.add(start)
    remaining[start] = false
    var left = start % columns
    var right = left
    var top = start / columns
    var bottom = top
    while (queue.isNotEmpty()) {
        val i = queue.removeFirst()
        val x = i % columns
        val y = i / columns
        left = minOf(left, x)
        right = maxOf(right, x)
        top = minOf(top, y)
        bottom = maxOf(bottom, y)
        val neighbours =
            listOfNotNull(
                (i - 1).takeIf { x > 0 },
                (i + 1).takeIf { x < columns - 1 },
                (i - columns).takeIf { it >= 0 },
                (i + columns).takeIf { it < remaining.size },
            )
        neighbours.filter { remaining[it] }.forEach {
            remaining[it] = false
            queue.add(it)
        }
    }
    return Rect((left * step).toFloat(), (top * step).toFloat(), ((right + 1) * step).toFloat(), ((bottom + 1) * step).toFloat())
}
