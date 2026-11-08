package com.couli.buildlogic

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * LINT-STYLE-android 测试 v2（编排 2026-11-07 04:2x：补 v1 缺口，口径照 D10-734）。v1 的 22 例在 [CouliSourceRulesTest]，一字未动。
 * 只走真实入口 [CouliSourceRules.check]，基线文件与 v1 同名同格式（扫描根下 tools/ci/hardcoded-style-baseline.json）。
 *
 * - 范围：feature/<模块>、app 的 src/main 与 src/release（进正式包）下**任意目录名**都扫（build/、.kotlin/、.idea/、.gradle/、mock/ 照扫，
 *   含 src/main/java 下的 .kt）；只排除源码集之外的模块构建产物（<模块>/build/、<模块>/.kotlin/ 等）。staging / debugFeatures / debug / 测试照旧不扫。
 * - 字号：.sp / .em（含 Float 字面量）、TextUnit(N, TextUnitType.X) 都是数字字号；令牌与常量引用不拦。
 * - dp：Dp(N) 与 N.dp 同等对待，只在间距用途（padding 族、PaddingValues、spacedBy、Spacer）里拦；宽高、描边、声明处不拦。
 * - 圆角：RoundedCornerShape / CutCornerShape / Absolute* 及 CornerSize 的数字实参（Int 百分比、Float px、dp）都拦，每个非零字面量计一次。
 * - 颜色（全部记 no-hex-color）：Color(0x…L)、十进制 Long、按通道 Color(r, g, b[, a])（位置或命名）、parseColor / toColorInt 的 "#…" 字面量。
 * - 只拦字面量：val PAD = 8.dp / Dp(8f) 这类 dp 常量声明不拦，引用处也不拦（常量追踪另立 FU）。
 */
class CouliStyleRulesV2Test {
    private val root: File = Files.createTempDirectory("lintstyle-v2").toFile()
    private val feature = "feature/home/src/main/kotlin/com/couli/feature/home/Page.kt"

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

    private fun emptyBaseline() {
        write("tools/ci/hardcoded-style-baseline.json", "{}\n")
    }

    private fun checkStyle(source: String): List<String> {
        write(feature, source)
        emptyBaseline()
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

    private fun assertEachCountsOnce(
        rule: String,
        snippets: List<String>,
    ) {
        snippets.forEach { snippet -> assertRuleCounts(snippet, checkStyle("fun sample() {\n    $snippet\n}\n"), mapOf(rule to 1)) }
    }

    // ---------------------------------------------------------------- ① 目录：源码集里的任何目录都会进包，照扫

    @Test
    fun styleV2DirectoriesInsidePageSourceSetsAreScannedWhateverTheirName() {
        val hidden =
            listOf(
                "feature/demo/src/main/kotlin/demo/build/Hidden.kt",
                "feature/demo/src/main/kotlin/build/generated/Hidden.kt",
                "feature/demo/src/main/kotlin/demo/.kotlin/Hidden.kt",
                "feature/demo/src/main/kotlin/demo/.idea/Hidden.kt",
                "feature/demo/src/main/kotlin/demo/.gradle/Hidden.kt",
                "feature/demo/src/main/kotlin/demo/mock/Hidden.kt",
                "feature/demo/src/main/java/demo/build/Hidden.kt",
                "app/src/main/kotlin/com/couli/app/build/Hidden.kt",
                "app/src/release/kotlin/com/couli/app/.kotlin/Hidden.kt",
                "app/src/release/kotlin/com/couli/app/build/Hidden.kt",
            )
        hidden.forEach { write(it, "val color = Color(0xFF123456)\n") }
        emptyBaseline()
        listOf(true, false).forEach { scanningRepository ->
            val result = CouliSourceRules.check(root, scanningRepository)
            assertRuleCounts("scanningRepository=$scanningRepository", result, mapOf("no-hex-color" to hidden.size))
            hidden.forEach { path ->
                assertEquals("$path 必须被扫到（scanningRepository=$scanningRepository）：$result", 1, result.count { it.startsWith("$path:1:") })
            }
        }
    }

    @Test
    fun styleV2BuildOutputsOutsideSourceSetsStayUnscanned() {
        write(feature, "val legal = 1\n")
        listOf(
            "feature/demo/build/generated/source/kapt/main/demo/Gen.kt",
            "feature/demo/build/intermediates/src/main/kotlin/demo/Gen.kt",
            "feature/demo/.kotlin/src/main/kotlin/demo/Gen.kt",
            "app/build/generated/ksp/release/kotlin/Gen.kt",
            "app/build/src/release/kotlin/Gen.kt",
            "build/feature/demo/src/main/kotlin/demo/Gen.kt",
        ).forEach { write(it, "val color = Color(0xFF123456)\nval size = 14.sp\n") }
        emptyBaseline()
        listOf(true, false).forEach { scanningRepository ->
            assertEquals("模块构建产物不进页面范围", emptyList<String>(), CouliSourceRules.check(root, scanningRepository))
        }
    }

    // ---------------------------------------------------------------- ④ 源码集：main/java 与 release 进正式包，扫；staging / debugFeatures 不扫

    @Test
    fun styleV2KotlinUnderMainJavaAndReleaseSourceSetsIsScanned() {
        val source = "val a = Color(0xFF123456)\nfun sample() { Modifier.padding(8.dp) }\nval b = RoundedCornerShape(8)\nval c = 14.sp\n"
        listOf("staging", "debugFeatures", "debug").forEach { sourceSet ->
            write("app/src/$sourceSet/java/com/couli/app/Page.kt", source)
            write("feature/demo/src/$sourceSet/java/demo/Page.kt", source)
        }
        write(feature, "val legal = 1\n")
        emptyBaseline()
        assertEquals("staging / debugFeatures / debug 不进失败范围", emptyList<String>(), CouliSourceRules.check(root, scanningRepository = true))
        val scanned =
            listOf(
                "feature/demo/src/main/java/demo/Page.kt",
                "app/src/main/java/com/couli/app/Page.kt",
                "app/src/release/kotlin/com/couli/app/Page.kt",
                "app/src/release/java/com/couli/app/Page.kt",
                "feature/demo/src/release/kotlin/demo/Page.kt",
            )
        scanned.forEach { write(it, source) }
        val result = CouliSourceRules.check(root, scanningRepository = true)
        assertRuleCounts("main/java + release", result, mapOf("no-hex-color" to scanned.size, "no-numeric-style" to 3 * scanned.size))
        scanned.forEach { path -> assertEquals("$path：$result", 4, result.count { it.startsWith("$path:") }) }
    }

    // ---------------------------------------------------------------- ② 字号等价写法

    @Test
    fun styleV2FontSizeEquivalentFormsFail() {
        assertEachCountsOnce(
            "no-numeric-style",
            listOf(
                "Text(\"a\", fontSize = 14.sp)",
                "Text(text = label, fontSize = 14.sp)",
                "TextStyle(fontSize = 14.sp)",
                "SpanStyle(fontSize = 14.sp)",
                "TextUnit(14f, TextUnitType.Sp)",
                "TextUnit(value = 14f, type = TextUnitType.Sp)",
                "TextUnit(1.2f, TextUnitType.Em)",
                "val size = 14.em",
                "val size = 1.2.em",
                "val size = 14f.sp",
                "val size = 14.5f.sp",
            ),
        )
        assertRuleCounts(
            "两个字面量计 2",
            checkStyle("val style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)\n"),
            mapOf("no-numeric-style" to 2),
        )
    }

    @Test
    fun styleV2FontSizeTokensAndConstantReferencesAreLegal() {
        assertRuleCounts(
            "字号令牌 / 常量引用",
            checkStyle(
                """
                const val FONT = 14
                fun sample() {
                    TextStyle(fontSize = CouliTypography.body.fontSize)
                    Text("a", fontSize = CouliTypography.caption.fontSize, style = CouliTypography.body)
                    CouliTypography.body.copy(fontSize = CouliTypography.caption.fontSize)
                    TextUnit(FONT.toFloat(), TextUnitType.Sp)
                    val a = FONT.em
                    val b = TextUnit.Unspecified
                }
                // 14.em, 14f.sp, TextUnit(14f, TextUnitType.Sp)
                val example = "TextUnit(14f, TextUnitType.Sp) 1.2.em 14f.sp"
                """.trimIndent(),
            ),
            emptyMap(),
        )
    }

    // ---------------------------------------------------------------- ③ dp：Dp(N) 与 N.dp 同口径，只在间距用途拦

    @Test
    fun styleV2SpacingDpEquivalentFormsFail() {
        assertEachCountsOnce(
            "no-numeric-style",
            listOf(
                "Modifier.padding(Dp(8f))",
                "Modifier.padding(horizontal = Dp(8f))",
                "PaddingValues(Dp(8f))",
                "Arrangement.spacedBy(Dp(8f))",
                "Spacer(Modifier.height(Dp(8f)))",
                "Modifier.padding(8f.dp)",
                "Modifier.padding(8.5.dp)",
                "PaddingValues(8.dp)",
                "PaddingValues(all = 8.dp)",
                "Modifier.padding(all = 8.dp)",
                "Modifier.paddingFromBaseline(top = 8.dp)",
                "Modifier.absolutePadding(left = 8.dp)",
                "LazyColumn(contentPadding = PaddingValues(8.dp))",
                "Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)",
                "Modifier.padding(if (wide) 8.dp else 0.dp)",
                "Modifier.padding(horizontal = PAD + 4.dp)",
            ),
        )
    }

    @Test
    fun styleV2NonSpacingDpEquivalentFormsAreLegal() {
        assertRuleCounts(
            "Dp(N) 用作宽高 / 描边 / 零值",
            checkStyle(
                """
                fun sample() {
                    Box(Modifier.width(Dp(8f)).height(Dp(48f)).size(Dp(24f)))
                    Modifier.sizeIn(minWidth = Dp(24f)).heightIn(min = 48f.dp)
                    BorderStroke(Dp(1f), CouliColors.borderSubtle)
                    Modifier.border(width = Dp(1f), color = CouliColors.borderSubtle)
                    CircularProgressIndicator(strokeWidth = 2.5.dp)
                    Modifier.padding(Dp(0f)).padding(0f.dp)
                    Arrangement.spacedBy(CouliSpacing.space2)
                }
                """.trimIndent(),
            ),
            emptyMap(),
        )
    }

    // ---------------------------------------------------------------- ⑤ 只拦字面量：dp 常量声明与引用都不拦（常量追踪另立 FU）

    @Test
    fun styleV2DpConstantDeclarationsAndTheirSpacingUsesAreNotLiterals() {
        assertRuleCounts(
            "dp 常量声明",
            checkStyle(
                """
                val PAD = 8.dp
                private val GAP = Dp(8f)
                internal val TOP: Dp = 4.5.dp
                object Metrics {
                    val SIDE = 12.dp
                }
                fun sample() {
                    Modifier.padding(PAD).padding(horizontal = Metrics.SIDE)
                    PaddingValues(top = TOP)
                    Arrangement.spacedBy(GAP)
                    Spacer(Modifier.height(GAP))
                }
                """.trimIndent(),
            ),
            emptyMap(),
        )
    }

    // ---------------------------------------------------------------- ③ 圆角等价写法

    @Test
    fun styleV2CornerShapeEquivalentFormsFail() {
        assertEachCountsOnce(
            "no-numeric-style",
            listOf(
                "RoundedCornerShape(8f)",
                "RoundedCornerShape(size = 8.dp)",
                "RoundedCornerShape(percent = 50)",
                "RoundedCornerShape(topStartPercent = 50)",
                "RoundedCornerShape(CornerSize(8.dp))",
                "RoundedCornerShape(Dp(8f))",
                "Modifier.clip(RoundedCornerShape(8.dp))",
                "CutCornerShape(8)",
                "CutCornerShape(8f)",
                "CutCornerShape(8.dp)",
                "CutCornerShape(topStart = 8.dp)",
                "AbsoluteRoundedCornerShape(8.dp)",
                "AbsoluteCutCornerShape(8.dp)",
            ),
        )
        listOf(
            "RoundedCornerShape(8.dp, 8.dp, 0.dp, 0.dp)",
            "CutCornerShape(topStart = 8.dp, bottomEnd = 4.dp)",
        ).forEach { expression ->
            assertRuleCounts(expression, checkStyle("val shape = $expression\n"), mapOf("no-numeric-style" to 2))
        }
    }

    @Test
    fun styleV2CornerShapeTokensConstantsAndZeroAreLegal() {
        assertRuleCounts(
            "圆角令牌 / 常量 / 零值",
            checkStyle(
                """
                const val RADIUS = 8
                fun sample() {
                    RoundedCornerShape(CouliRadius.button)
                    CutCornerShape(CouliRadius.card)
                    CutCornerShape(RADIUS.dp)
                    RoundedCornerShape(CornerSize(CouliRadius.card))
                    AbsoluteRoundedCornerShape(CouliRadius.sheet)
                    RoundedCornerShape(0f)
                    CutCornerShape(0.dp)
                    Modifier.clip(CircleShape).clip(RectangleShape)
                }
                """.trimIndent(),
            ),
            emptyMap(),
        )
    }

    // ---------------------------------------------------------------- ③ 颜色等价写法（全部记 no-hex-color）

    @Test
    fun styleV2ColorEquivalentFormsFail() {
        assertEachCountsOnce(
            "no-hex-color",
            listOf(
                "Color(0xFF123456L)",
                "Color(0xff_12_34_56L)",
                "Color(color = 0xFF123456)",
                "Color(4282664004L)",
                "Color(red = 0.1f, green = 0.2f, blue = 0.3f)",
                "Color(red = 255, green = 0, blue = 0, alpha = 128)",
                "Color(0.1f, 0.2f, 0.3f)",
                "Color(255, 0, 0)",
                "android.graphics.Color.parseColor(\"#FF0000\")",
                "Color.parseColor(\"#80FF0000\")",
                "\"#FF0000\".toColorInt()",
                "Color(\"#FF0000\".toColorInt())",
                "Color(android.graphics.Color.parseColor(\"#FF123456\"))",
            ),
        )
    }

    @Test
    fun styleV2ColorRuntimeValuesTokensAndTransparentAreLegal() {
        assertRuleCounts(
            "颜色：运行时值 / 令牌 / Transparent",
            checkStyle(
                """
                const val OPAQUE = 0xFFL
                fun sample(serverValue: String, r: Float, g: Float, b: Float) {
                    val a = Color.Transparent
                    val c = Color.Unspecified
                    val d = Color.parseColor(serverValue)
                    val e = android.graphics.Color.parseColor(state.background)
                    val f = serverValue.toColorInt()
                    val h = parseColor((it as? JsonPrimitive)?.content)
                    val i = Color((OPAQUE shl ALPHA_SHIFT) or rgb)
                    val j = Color(red = r, green = g, blue = b)
                    val k = Color(COLOR_VALUE)
                    val l = CouliColors.brandPrimary
                    val anchor = "#section"
                }
                // Color.parseColor("#FF0000") and Color(0xFF123456L)
                val example = "Color.parseColor(\"#FF0000\") Color(255, 0, 0)"
                """.trimIndent(),
            ),
            emptyMap(),
        )
    }

    // ---------------------------------------------------------------- 等价写法混在一条链里：各自计一次，不重复、不串类

    @Test
    fun styleV2MixedChainCountsEachLiteralOnceUnderItsOwnRule() {
        assertRuleCounts(
            "混合链",
            checkStyle(
                "fun sample() {\n" +
                    "    Modifier.padding(Dp(8f)).clip(CutCornerShape(4.dp)).background(Color(0xFF123456L)).width(Dp(48f))\n" +
                    "    Text(\"a\", fontSize = TextUnit(14f, TextUnitType.Sp), color = Color(red = 1f, green = 0f, blue = 0f))\n" +
                    "}\n",
            ),
            mapOf("no-numeric-style" to 3, "no-hex-color" to 2),
        )
    }
}
