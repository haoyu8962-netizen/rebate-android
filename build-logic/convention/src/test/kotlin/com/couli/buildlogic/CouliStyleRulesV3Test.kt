package com.couli.buildlogic

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * LINT-STYLE-android 测试 v3（评审 r1 S1-1：间距命名实参）。v1 在 [CouliSourceRulesTest]、v2 在 [CouliStyleRulesV2Test]，都一字未动。
 * 只走真实入口 [CouliSourceRules.check]，基线文件与 v1 / v2 同名同格式。
 *
 * 「间距参数名」口径：调用里的**命名实参**，名字
 * - 等于 `padding`、`gap`、`spacing`，或
 * - 以 `Padding`、`Gap`、`Spacing` 结尾（小驼峰后缀，首字母大写），
 * 就是间距用途。它的实参表达式里每个非零 `N.dp` / `Dp(N)` 记一次 no-numeric-style（与 padding 族同口径，按源码位置去重）。
 * 例：组件库的 `gap` / `horizontalGap` / `verticalGap` / `wrappedCenterGap` / `contentGap`，页面的 `labelGap` / `titleGap` /
 * `valueGap` / `spacing` / `itemSpacing`。
 *
 * 不是间距的：
 * - 宽高尺寸类名字（width、height、size、minHeight、iconSize、strokeWidth、elevation…）；
 * - 只是**以** gap / spacing 开头或在中间含有它的名字（`gapIndicatorSize`、`spacingSliderHeight`）——按后缀判，不按包含判；
 * - 令牌、常量引用、零值；
 * - `val gap = 8.dp` 这类声明（不是命名实参，照 v2 口径 7 不拦）和 `gap == 8.dp` 比较。
 * 位置实参无从知道形参名，不断言。`inset`、`space` 不纳入、也不断言。
 */
class CouliStyleRulesV3Test {
    private val root: File = Files.createTempDirectory("lintstyle-v3").toFile()
    private val feature = "feature/wallet/src/main/kotlin/com/couli/feature/wallet/Page.kt"

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun write(
        relative: String,
        text: String,
    ): File =
        File(root, relative).apply {
            parentFile.mkdirs()
            writeText(text)
        }

    private fun checkStyle(source: String): List<String> {
        write(feature, source)
        write("tools/ci/hardcoded-style-baseline.json", "{}\n")
        return CouliSourceRules.check(root, scanningRepository = false)
    }

    private fun assertRuleCounts(
        context: String,
        result: List<String>,
        expected: Map<String, Int>,
    ) {
        val message = "$context → $result"
        assertEquals(message, expected.values.sum(), result.size)
        expected.forEach { (rule, count) -> assertEquals(message, count, result.count { "[$rule]" in it }) }
    }

    private fun assertEachCountsOnce(snippets: List<String>) {
        snippets.forEach { snippet ->
            assertRuleCounts(snippet, checkStyle("fun sample() {\n    $snippet\n}\n"), mapOf("no-numeric-style" to 1))
        }
    }

    // ---------------------------------------------------------------- 正例：间距命名实参传字面量 dp，每个计一次

    @Test
    fun styleV3ComponentGapArgumentsWithLiteralDpFail() {
        assertEachCountsOnce(
            listOf(
                "FeedbackBlock(gap = 8.dp)",
                "CouliChipFlow(horizontalGap = 12.dp)",
                "CouliChipFlow(verticalGap = 4.dp)",
                "CouliChipFlow(wrappedCenterGap = 6.dp)",
                "WalletSection(contentGap = 16.dp)",
                "IdentityRow(label = \"a\", value = \"b\", labelGap = 8.dp)",
                "SettingsRow(titleGap = 12.dp)",
                "SettingsRow(valueGap = 4.dp)",
                "TitleValue(title = t, value = v, gap = 8.dp, modifier = Modifier.weight(1f))",
            ),
        )
    }

    @Test
    fun styleV3SpacingArgumentsWithLiteralDpFail() {
        assertEachCountsOnce(
            listOf(
                "WalletCard(spacing = 16.dp) { Text(\"a\") }",
                "FlowRow(itemSpacing = 8.dp)",
                "Grid(rowSpacing = 8.dp)",
                "Grid(columnSpacing = 8.dp)",
            ),
        )
    }

    @Test
    fun styleV3SpacingArgumentEquivalentFormsFail() {
        assertEachCountsOnce(
            listOf(
                "WalletCard(spacing = Dp(8f))",
                "IdentityRow(labelGap = Dp(8f))",
                "IdentityRow(labelGap = 8f.dp)",
                "IdentityRow(labelGap = 8.5.dp)",
                "CouliChipFlow(horizontalGap = Dp(12f))",
                "WalletCard(spacing = if (wide) 16.dp else CouliSpacing.space2)",
                "IdentityRow(labelGap = CouliSpacing.space2 + 4.dp)",
                "IdentityRow(labelGap = max(8.dp, extra))",
                "Layout(gap = 8.dp.roundToPx())",
                "IdentityRow(\n        label = \"a\",\n        labelGap =\n            8.dp,\n    )",
            ),
        )
    }

    @Test
    fun styleV3SeveralSpacingArgumentsInOneCallCountEach() {
        assertRuleCounts(
            "一次调用里多个间距命名实参",
            checkStyle(
                """
                fun sample() {
                    CouliChipFlow(
                        horizontalGap = 12.dp,
                        verticalGap = Dp(4f),
                        modifier = Modifier.width(120.dp).height(48.dp),
                    )
                    SettingsRow(title = "a", titleGap = 12.dp, valueGap = 4.dp, minHeight = 56.dp)
                }
                """.trimIndent(),
            ),
            mapOf("no-numeric-style" to 4),
        )
    }

    @Test
    fun styleV3SpacingArgumentsAreNotCountedTwice() {
        assertRuleCounts(
            "已在间距调用里的 dp、sp 字距：各计一次",
            checkStyle(
                """
                fun sample() {
                    Arrangement.spacedBy(space = 8.dp)
                    LazyColumn(contentPadding = PaddingValues(horizontal = 8.dp))
                    Text("a", style = CouliTypography.title.copy(letterSpacing = 2.sp))
                }
                """.trimIndent(),
            ),
            mapOf("no-numeric-style" to 3),
        )
    }

    // ---------------------------------------------------------------- 反例：令牌 / 零值 / 宽高类名字 / 含而不以之结尾 / 声明 / 比较

    @Test
    fun styleV3SpacingArgumentsWithTokensConstantsAndZeroAreLegal() {
        assertRuleCounts(
            "间距命名实参传令牌 / 常量 / 零值",
            checkStyle(
                """
                fun sample(titleGap: Dp) {
                    WalletCard(spacing = CouliSpacing.space4) { Text("a") }
                    IdentityRow(label = "a", value = "b", labelGap = CouliSpacing.space2)
                    TitleValue(title = t, value = v, gap = titleGap)
                    CouliChipFlow(horizontalGap = CouliSpacing.space3, verticalGap = CouliSpacing.space2)
                    WalletSection(contentGap = STEP_LINE_GAP)
                    FeedbackBlock(gap = 0.dp)
                    FlowRow(itemSpacing = Dp(0f))
                    SettingsRow(titleGap = CouliSpacing.space3, valueGap = CouliSpacing.space1)
                }
                """.trimIndent(),
            ),
            emptyMap(),
        )
    }

    @Test
    fun styleV3DimensionNamedArgumentsAreNotSpacing() {
        assertRuleCounts(
            "宽高尺寸类命名实参",
            checkStyle(
                """
                fun sample() {
                    Avatar(size = 40.dp)
                    CouliButton(width = 120.dp, height = 48.dp, minHeight = 44.dp)
                    Icon(painter = p, contentDescription = null, iconSize = Dp(24f))
                    CircularProgressIndicator(strokeWidth = 2.dp)
                    Surface(elevation = 4.dp, minWidth = 64.dp, maxHeight = Dp(320f))
                    Divider(thickness = 1.dp)
                }
                """.trimIndent(),
            ),
            emptyMap(),
        )
    }

    @Test
    fun styleV3NamesThatOnlyStartWithOrContainGapOrSpacingAreNotSpacing() {
        assertRuleCounts(
            "以 gap / spacing 开头而不以之结尾",
            checkStyle(
                """
                fun sample() {
                    Timeline(gapIndicatorSize = 8.dp)
                    SpacingEditor(spacingSliderHeight = 24.dp)
                }
                """.trimIndent(),
            ),
            emptyMap(),
        )
    }

    @Test
    fun styleV3GapDeclarationsAndComparisonsAreNotNamedArguments() {
        assertRuleCounts(
            "gap / spacing 声明与比较",
            checkStyle(
                """
                val gap = 8.dp
                private val labelGap: Dp = Dp(8f)
                fun sample() {
                    val spacing = 16.dp
                    var itemSpacing = 4.dp
                    require(gap == 8.dp)
                    WalletCard(spacing = spacing) { Text("a") }
                }
                """.trimIndent(),
            ),
            emptyMap(),
        )
    }
}
