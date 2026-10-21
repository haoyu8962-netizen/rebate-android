@file:OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)

package com.couli.core.designsystem

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** 独立从包内原图解码；不调用产品 resolver / PlatformIcon，不生成或更新入库基准。 */
@Composable
internal fun Fx05aOfficialReference(
    sourceFile: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val assets = LocalContext.current.assets
    val bitmap = remember(sourceFile) { assets.open("platform/$sourceFile.png").use { requireNotNull(BitmapFactory.decodeStream(it)) } }
    Image(bitmap.asImageBitmap(), contentDescription = null, modifier = modifier.size(size))
}

/** 临时像素探针仅写 JUnit TemporaryFolder，与 src/test/snapshots 隔离。 */
internal fun ComposeContentTestRule.fx05aPixels(
    tag: String,
    directory: File,
): BufferedImage {
    val file = File(directory, "$tag.png")
    onNodeWithTag(tag, useUnmergedTree = true).captureRoboImage(
        file.path,
        roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record),
    )
    return requireNotNull(ImageIO.read(file))
}

internal fun assertFx05aPixelsEqual(
    label: String,
    expected: BufferedImage,
    actual: BufferedImage,
) {
    assertEquals("$label 宽度", expected.width, actual.width)
    assertEquals("$label 高度", expected.height, actual.height)
    var differing = 0
    for (y in 0 until expected.height) {
        for (x in 0 until expected.width) {
            val a = actual.getRGB(x, y)
            val b = expected.getRGB(x, y)
            if (listOf(0, 8, 16, 24).any { abs((a ushr it and 255) - (b ushr it and 255)) > 2 }) differing++
        }
    }
    assertTrue("$label 有 $differing 个像素不同（每通道容差 2，不放宽差异面积）", differing == 0)
}
