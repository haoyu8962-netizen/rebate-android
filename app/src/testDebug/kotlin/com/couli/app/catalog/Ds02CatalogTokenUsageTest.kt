@file:OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)

package com.couli.app.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.couliColorTokens
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 遍历所有状态；图片本身可不染色，但每个组件至少一个样例必须真正消费令牌。 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h1800dp-xxhdpi")
class Ds02CatalogTokenUsageTest(
    private val component: String,
    private val dark: Boolean,
    private val preserveSurfaces: Boolean,
) {
    @get:Rule val rule = createComposeRule()
    private val excluded =
        if (preserveSurfaces) {
            setOf("background.canvas", "background.surface", "background.muted")
        } else {
            setOf("background.canvas")
        }
    private val probeName = if (preserveSurfaces) "keep-three-backgrounds" else "keep-canvas"

    @Test
    fun everyCaseIsProbedAndAtLeastOneConsumesTokensWithoutPaintingTheWholeShell() {
        val cases = Ds02Inventory.cases.filter { it.component == component }
        var sample by mutableStateOf(cases.first())
        var overrides by mutableStateOf(emptyMap<String, Color>())
        var keys = emptySet<String>()
        var retainedBackgrounds = emptyMap<String, Color>()
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CouliTheme(darkTheme = dark, colorOverrides = overrides) {
                val colors = couliColorTokens()
                keys = colors.keys - excluded
                retainedBackgrounds = excluded.associateWith { colors.getValue(it) }
                Box(Modifier.width(379.dp).background(couliColorTokens().getValue("background.canvas"))) {
                    CatalogComponentSample(sample.component, sample.variant, sample.state, Modifier.testTag("probe"))
                }
            }
        }
        val sentinel = Color(0xFF19CBE1)
        var consumed = false
        var hasText = false
        cases.forEach { case ->
            rule.runOnIdle {
                sample = case
                overrides = emptyMap()
            }
            // 暂停时钟时先让 Snapshot 状态写入通知重组，再推进确定的绘制帧。
            rule.waitForIdle()
            rule.mainClock.advanceTimeBy(320)
            assertTrue(keys.isNotEmpty())
            val backgroundsBefore = retainedBackgrounds.toMap()
            hasText = hasText || sampleHasText()
            val before = ds02Pixels(pixels(case, "before"))
            rule.runOnIdle { overrides = keys.associateWith { sentinel } }
            // 暂停时钟时先让 Snapshot 状态写入通知重组，再推进确定的绘制帧。
            rule.waitForIdle()
            rule.mainClock.advanceTimeBy(320)
            val after = pixels(case, "after")
            assertEquals("保留的底色不得被探针替换：$probeName", backgroundsBefore, retainedBackgrounds)
            assertTrue("样例外壳整块铺组件色：$case", ds02Perimeter(after).any { !ds02Near(it, sentinel.toArgb()) })
            consumed = consumed || (!ds02Same(before, ds02Pixels(after)) && ds02Pixels(after).any { ds02Near(it, sentinel.toArgb()) })
        }
        // 无文字的占位 / 纯背景组件可仅依赖 surface/muted；有文字时仍必须通过第二轮（含深色）。
        val backgroundOnly = component in setOf("Skeleton", "ProductCardSkeleton", "ImagePlaceholder", "Divider", "Avatar")
        if (!preserveSurfaces || !backgroundOnly || hasText) {
            assertTrue("组件所有样例均忽略非豁免令牌：$component / dark=$dark / $probeName", consumed)
        }
    }

    private fun sampleHasText(): Boolean =
        rule
            .onAllNodes(
                SemanticsMatcher("实际可读文字") { node ->
                    node.config
                        .getOrNull(SemanticsProperties.Text)
                        .orEmpty()
                        .any { it.text.isNotBlank() } ||
                        node.config
                            .getOrNull(SemanticsProperties.EditableText)
                            ?.text
                            ?.isNotBlank() == true
                },
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
            .isNotEmpty()

    private fun pixels(
        sample: Ds02Case,
        suffix: String,
    ): BufferedImage {
        val file =
            File("build/ds02-token-probes/$component/${sample.variant}-${sample.state}-$dark-$probeName-$suffix.png")
                .apply { parentFile?.mkdirs() }
        rule.onNodeWithTag("probe", useUnmergedTree = true).captureRoboImage(
            file.path,
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record),
        )
        return requireNotNull(ImageIO.read(file))
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}/dark={1}/preserveSurfaces={2}")
        fun parameters(): List<Array<Any>> =
            Ds02Inventory.cases
                .map { it.component }
                .distinct()
                .filter { it != "BrandMark" }
                .flatMap { component ->
                    listOf(false, true).flatMap { dark -> listOf(false, true).map { arrayOf(component, dark, it) } }
                }
    }
}

/** 深色 + 不替换三种底色：固定字色不能靠白卡片变色通过，真实主题文字必须通过。 */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-xxhdpi")
class Ds02TokenProbeOracleTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun darkProbeRejectsHardcodedTextAndAcceptsThemedTextAfterRecomposition() {
        var overrides by mutableStateOf(emptyMap<String, Color>())
        var keys = emptySet<String>()
        val sentinel = Color(0xFF19CBE1)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CouliTheme(darkTheme = true, colorOverrides = overrides) {
                val tokens = couliColorTokens()
                keys = tokens.keys - setOf("background.canvas", "background.surface", "background.muted")
                androidx.compose.foundation.layout.Column(Modifier.background(tokens.getValue("background.canvas"))) {
                    listOf(false, true).forEach { themed ->
                        Box(Modifier.width(200.dp).background(tokens.getValue("background.surface"))) {
                            androidx.compose.material3.Text(
                                "返利 ¥123.45",
                                Modifier.testTag(if (themed) "themed-text" else "hardcoded-text"),
                                color = if (themed) tokens.getValue("text.primary") else Color(0xFFF2EEE6),
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(320)
        val goodBefore = pixels("themed-text", "before")
        val badBefore = pixels("hardcoded-text", "before")
        rule.runOnIdle { overrides = keys.associateWith { sentinel } }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(320)
        val goodAfter = pixels("themed-text", "after")
        val badAfter = pixels("hardcoded-text", "after")
        assertTrue("正常主题文字必须真正变色", !ds02Same(goodBefore, goodAfter) && goodAfter.any { ds02Near(it, sentinel.toArgb()) })
        assertTrue("固定字色没有消费探针，不能判通过", ds02Same(badBefore, badAfter) && badAfter.none { ds02Near(it, sentinel.toArgb()) })
    }

    private fun pixels(
        tag: String,
        phase: String,
    ): IntArray {
        val file = File("build/ds02-token-probes/oracle/$tag-$phase.png").apply { parentFile?.mkdirs() }
        rule.onNodeWithTag(tag, useUnmergedTree = true).captureRoboImage(
            file.path,
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record),
        )
        return ds02Pixels(requireNotNull(ImageIO.read(file)))
    }
}
