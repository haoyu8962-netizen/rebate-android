@file:OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)

package com.couli.core.designsystem

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-xxhdpi")
class Ds02CheckboxGeometryTest(
    private val fontScale: Float,
    private val checked: Boolean,
) {
    @get:Rule val rule = createComposeRule()

    @Test
    fun glyphIsCircleWithSolidCheckedOrSpecifiedUncheckedStroke() {
        val checkedColor = Color(0xFF19CBE1)
        rule.setContent {
            CouliTheme(colorOverrides = mapOf((if (checked) "control.checked" else "border.control") to checkedColor)) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    CouliCheckbox(checked, {}, "同意", modifier = Modifier.testTag("checkbox"))
                }
            }
        }
        val node = rule.onNodeWithTag("checkbox")
        node.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        val file = File("build/ds02-geometry/checkbox-$fontScale-$checked.png").apply { parentFile?.mkdirs() }
        node.captureRoboImage(file.path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        val bitmap = requireNotNull(ImageIO.read(file))
        val points = coloredPoints(bitmap, checkedColor.toArgb())
        assertTrue("没有采用 control.checked 令牌", points.isNotEmpty())
        val left = points.minOf { it.first }
        val right = points.maxOf { it.first }
        val top = points.minOf { it.second }
        val bottom = points.maxOf { it.second }
        // 先在测试作用域求倍率，避免 with 接收者的 Density.fontScale 遮蔽参数。
        val scale = minOf(fontScale, 1.5f)
        val expected = with(rule.density) { (18f * scale).dp.toPx() }
        assertTrue("直径应为 18dp × min(fontScale, 1.5)", abs(right - left + 1 - expected) <= 2f)
        assertTrue("圆形高宽相同", abs((right - left) - (bottom - top)) <= 1)
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        val radius = expected / 2f + 1f
        assertTrue(
            "发现圆外着色：方形 / 圆角方形勾选框不能通过",
            points.all { (x, y) ->
                (x - cx) * (x - cx) + (y - cy) * (y - cy) <= radius * radius
            },
        )
        // 不能用细圆圈骗过轮廓：选中应为实心圆（白色勾占少量面积）。
        if (checked) {
            assertTrue("选中态不是实心圆", points.size > expected * expected * 0.45f)
        } else {
            val minStroke = with(rule.density) { (1.5f * scale).dp.toPx() }
            val maxStroke = with(rule.density) { (2f * scale).dp.toPx() }
            // 中心扫描线只取左环段，右环段单独对称检查。允许边缘抗锯齿损失 1px。
            val middle = points.filter { abs(it.second - cy) <= 0.5f }
            val leftStroke =
                middle
                    .filter { it.first < cx }
                    .map { it.first }
                    .distinct()
                    .size
            val rightStroke =
                middle
                    .filter { it.first > cx }
                    .map { it.first }
                    .distinct()
                    .size
            assertTrue("未选态必须空心", points.none { (x, y) -> abs(x - cx) < 2f && abs(y - cy) < 2f })
            listOf(leftStroke, rightStroke).forEach { width ->
                assertTrue("未选描边应为 1.5–2dp × scale，实测 $width px", width >= minStroke - 1.5f && width <= maxStroke + 1f)
            }
        }
    }

    private fun coloredPoints(
        bitmap: java.awt.image.BufferedImage,
        color: Int,
    ): List<Pair<Int, Int>> =
        buildList {
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    if (near(bitmap.getRGB(x, y), color)) add(x to y)
                }
            }
        }

    private fun near(
        a: Int,
        b: Int,
    ): Boolean = listOf(0, 8, 16, 24).all { abs((a ushr it and 255) - (b ushr it and 255)) <= 2 }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "fontScale={0}/checked={1}")
        fun parameters(): List<Array<Any>> = listOf(1f, 2f, 3f).flatMap { scale -> listOf(false, true).map { arrayOf(scale, it) } }
    }
}
