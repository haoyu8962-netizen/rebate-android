package com.couli.app.catalog

import java.awt.image.BufferedImage
import kotlin.math.abs

/** 容忍每通道 ±2 的光栅化误差；不能用大比例容差掩盖整块未换色。 */
internal fun ds02Near(
    a: Int,
    b: Int,
): Boolean = listOf(0, 8, 16, 24).all { shift -> abs((a ushr shift and 255) - (b ushr shift and 255)) <= 2 }

internal fun ds02Same(
    a: IntArray,
    b: IntArray,
): Boolean = a.size == b.size && a.indices.all { ds02Near(a[it], b[it]) }

internal fun ds02Pixels(image: BufferedImage): IntArray = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)

internal fun ds02Perimeter(image: BufferedImage): List<Int> =
    buildList {
        for (x in 0 until image.width) {
            add(image.getRGB(x, 0))
            add(image.getRGB(x, image.height - 1))
        }
        for (y in 0 until image.height) {
            add(image.getRGB(0, y))
            add(image.getRGB(image.width - 1, y))
        }
    }
