package com.couli.app.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.couliColorTokens
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 与目录页共用生产样例渲染入口，测试不另抄一份组件装配。缺基准必须红。 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h1800dp-xxhdpi")
class Ds02CatalogSnapshotTest(
    private val sample: Ds02Case,
    private val dark: Boolean,
) {
    @get:Rule val rule = createComposeRule()

    @Test
    fun everyInventoryCaseHasReviewedGoldenInBothThemes() {
        val theme = if (dark) "dark" else "light"
        val file = File("src/test/snapshots/ds02/${sample.component}/${sample.variant}-${sample.state}-$theme.png")
        val recording = System.getProperty("roborazzi.test.record") == "true"
        assertTrue("缺基准：$file；实现完成并人工核图后用 recordRoborazziDebug 录制", recording || file.isFile)
        if (recording) file.parentFile?.mkdirs()
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CouliTheme(darkTheme = dark) {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, if (sample.variant == "vertical200") 2f else 1f),
                ) {
                    Box(
                        Modifier
                            .width(379.dp)
                            .heightIn(min = 48.dp)
                            .background(couliColorTokens().getValue("background.canvas"))
                            .testTag("ds02-snapshot"),
                    ) {
                        CatalogComponentSample(
                            componentId = sample.component,
                            variantId = sample.variant,
                            stateId = sample.state,
                            modifier = Modifier,
                        )
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(320)
        rule.onNodeWithTag("ds02-snapshot", useUnmergedTree = true).assertIsDisplayed().captureRoboImage(file.path)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}/dark={1}")
        fun parameters(): List<Array<Any>> =
            Ds02Inventory.cases.flatMap { sample ->
                listOf(false, true).map { dark -> arrayOf(sample, dark) }
            }
    }
}
