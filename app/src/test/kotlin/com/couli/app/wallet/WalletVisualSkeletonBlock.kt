package com.couli.app.wallet

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Rect
import kotlin.math.ceil
import kotlin.math.floor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

internal data class WalletVisualSkeletonBlock(
    val name: String,
    val bounds: Rect,
)

/**
 * W17 主卡结构独立期望：相对加载容器定位，20 内边距、16 行距，首行 / 金额行 / 底部左右两格。
 * 不从已画出的色带反推位置，缺首行时不得把下一行重新认作首行；金额行按钮高48、金额高40且居中。
 */
internal fun walletHeroSkeletonBlocks(
    loading: Rect,
    density: Float,
): List<WalletVisualSkeletonBlock> {
    val left = loading.left + 20 * density
    val right = loading.right - 20 * density
    val top = loading.top + 20 * density
    val width = right - left
    val cellWidth = (width - 12 * density) / 2

    fun block(
        name: String,
        x: Float,
        yDp: Float,
        widthPx: Float,
        heightDp: Float,
    ) = WalletVisualSkeletonBlock(name, Rect(x, top + yDp * density, x + widthPx, top + (yDp + heightDp) * density))
    return listOf(
        block("主卡首行/余额标签", left, 0f, width * 0.4f, 20f),
        block("主卡首行/提现记录", right - width * 0.18f, 0f, width * 0.18f, 20f),
        block("主卡金额行/余额", left, 40f, width * 0.45f, 40f),
        block("主卡金额行/提现按钮", right - 96 * density, 36f, 96 * density, 48f),
        block("主卡底部左格/冻结标签", left, 100f, cellWidth * 0.4f, 16f),
        block("主卡底部左格/冻结金额", left, 120f, cellWidth * 0.7f, 20f),
        block("主卡底部右格/已提现标签", left + cellWidth + 12 * density, 100f, cellWidth * 0.4f, 16f),
        block("主卡底部右格/已提现金额", left + cellWidth + 12 * density, 120f, cellWidth * 0.7f, 20f),
    )
}

/** 每块分别找真实背景墨迹，四边位置与覆盖面积都必须成立；骨架无公共语义，不能用空布局盒代替像素。 */
internal fun assertWalletVisualSkeletonBlocks(
    pixels: Bitmap,
    blocks: List<WalletVisualSkeletonBlock>,
    ink: Int,
    density: Float,
) {
    blocks.forEach { assertWalletSkeletonBlock(pixels, it, ink, density) }
}

private fun assertWalletSkeletonBlock(
    pixels: Bitmap,
    block: WalletVisualSkeletonBlock,
    ink: Int,
    density: Float,
) {
    val expected = block.bounds
    val search = expected.inflate(3 * density)
    var left = pixels.width
    var top = pixels.height
    var right = -1
    var bottom = -1
    var count = 0
    for (y in maxOf(0, floor(search.top).toInt()) until minOf(pixels.height, ceil(search.bottom).toInt())) {
        for (x in maxOf(0, floor(search.left).toInt()) until minOf(pixels.width, ceil(search.right).toInt())) {
            if (pixels.getPixel(x, y) == ink) {
                left = minOf(left, x)
                right = maxOf(right, x)
                top = minOf(top, y)
                bottom = maxOf(bottom, y)
                count++
            }
        }
    }
    assertTrue("${block.name} 缺可见占位像素，期望位置=$expected", count > 0)
    val actual = Rect(left.toFloat(), top.toFloat(), (right + 1).toFloat(), (bottom + 1).toFloat())
    listOf(
        "左" to (expected.left to actual.left),
        "上" to (expected.top to actual.top),
        "右" to (expected.right to actual.right),
        "下" to (expected.bottom to actual.bottom),
    ).forEach { (edge, values) ->
        assertEquals("${block.name} $edge 边位置，实际=$actual", values.first, values.second, density)
    }
    assertTrue("${block.name} 必须有实心占位面积，不能只有细边框/少量像素", count >= expected.width * expected.height * 0.8f)
}

/** v1 旧判据只用于保留整体下部占位覆盖，并在 M11 反例中明确证明旧断言会误放行。 */
internal fun assertWalletSkeletonGlobalCoverage(
    pixels: Bitmap,
    ink: Int,
    density: Float,
) {
    val rows =
        (0 until pixels.height).filter { y ->
            (pixels.width / 10 until pixels.width * 9 / 10).count { x -> pixels.getPixel(x, y) == ink } >= 16 * density
        }
    val bands = rows.zipWithNext().count { (a, b) -> b > a + 1 } + if (rows.isEmpty()) 0 else 1
    assertTrue("整体至少3条带，实际=$bands", bands >= 3)
    assertTrue("整体占位累计至少160dp，实际=${rows.size / density}", rows.size / density >= 160)
}
