package com.couli.app.orders

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Rect

/** 在单张骨架卡内按固定网格取连通块；只在内存运行，不输出图片。 */
internal fun orderSkeletonBlocks(
    bitmap: Bitmap,
    area: Rect,
    color: Int,
    step: Int,
): List<Rect> {
    val left = area.left.toInt()
    val top = area.top.toInt()
    val columns = area.width.toInt() / step
    val rows = area.height.toInt() / step
    val remaining = BooleanArray(columns * rows) { i -> bitmap.getPixel(left + i % columns * step, top + i / columns * step) == color }
    val result = mutableListOf<Rect>()
    for (start in remaining.indices) {
        if (remaining[start]) result += orderSkeletonBlock(remaining, start, columns, step).translate(left.toFloat(), top.toFloat())
    }
    return result.filter { it.width * it.height >= 16 * step * step }
}

private fun orderSkeletonBlock(
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
