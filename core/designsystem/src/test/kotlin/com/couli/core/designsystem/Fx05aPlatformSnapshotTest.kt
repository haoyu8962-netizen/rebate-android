package com.couli.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** V1–V6 视觉复核矩阵。测试作者不录基准；实现方按 test-change 核图后录，缺图直接失败。 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w320dp-h1400dp-xxhdpi")
class Fx05aPlatformSnapshotTest(
    private val dark: Boolean,
    private val fontScale: Float,
) {
    @get:Rule val rule = createComposeRule()

    @Test
    fun officialIconsBadgesAndInlineTitleHaveReviewedGoldens() {
        val theme = if (dark) "dark" else "light"
        val file = File("src/test/snapshots/fx05a/platform-$theme-font${(fontScale * 100).toInt()}.png")
        val recording = System.getProperty("roborazzi.test.record") == "true"
        assertTrue("缺少人工核准基准：$file；由实现方按 test-change 录制", recording || file.isFile)
        if (recording) file.parentFile?.mkdirs()
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                CouliTheme(darkTheme = dark) {
                    Column(
                        Modifier
                            .width(320.dp)
                            .background(CouliColors.backgroundCanvas)
                            .padding(16.dp)
                            .testTag("matrix"),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        platforms.forEach { (code, name) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                PlatformIcon(code, size = PlatformIconSize.HOME)
                                PlatformBadge(name = name, platformCode = code)
                            }
                        }
                        PlatformBadge(name = "天猫", platformCode = "tmall")
                        PlatformBadge(name = "future_shop", platformCode = "future_shop")
                        BadgedTitle(platformName = "拼多多", title = "全脂纯牛奶 250ml × 24 盒 整箱装 早餐奶")
                    }
                }
            }
        }
        rule.onNodeWithTag("matrix", useUnmergedTree = true).captureRoboImage(file.path)
    }

    companion object {
        private val platforms =
            listOf(
                "taobao" to "淘宝",
                "jd" to "京东",
                "pdd" to "拼多多",
                "vip" to "唯品会",
                "douyin" to "抖音",
                "meituan" to "美团",
                "eleme" to "饿了么",
                "fliggy" to "飞猪",
                "didi" to "滴滴",
            )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}, fontScale={1}")
        fun parameters(): List<Array<Any>> = listOf(false, true).flatMap { dark -> listOf(1f, 2f).map { arrayOf(dark, it) } }
    }
}
