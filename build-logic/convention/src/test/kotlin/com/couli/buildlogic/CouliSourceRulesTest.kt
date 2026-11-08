package com.couli.buildlogic

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * FU-AND-0715 第 1 轮评审 R1-04 / R1-05：规则本体 [CouliSourceRules.check]（couliRules 任务直接调用它）在真实目录上的 fail-closed。
 * - R1-04：`.java` 用 Java 词法（块注释不嵌套）：`/* /* */ SentryAndroid.init(context); // */` 里的调用是代码，必须报 SDK 违规；
 * - R1-05：一个正常源码文件 + 一个列不出内容的源码目录 → `[scan-incomplete]`（不静默跳过子树）。
 * - R2-02：单独的回车（U+000D）在 Java / Kotlin 里都是行终止符，结束行注释、普通字符串、字符字面量；
 * - R2-03：目录能列出名字但条目属性查不了（chmod 444：有读、没有执行 / 搜索权限）→ `[scan-incomplete]`，不当成「不是文件」丢掉。
 */
class CouliSourceRulesTest {
    private val root: File = Files.createTempDirectory("fuand0715-r1-rules").toFile()
    private val locked = mutableListOf<File>()

    @After
    fun cleanUp() {
        locked.forEach {
            it.setReadable(true, false)
            it.setExecutable(true, false)
        }
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

    @Test
    fun javaBlockCommentsDoNotNestSoTheSdkInitAfterTheFirstCloseIsReported() {
        write(
            "app/src/main/java/com/couli/app/Init.java",
            "package com.couli.app;\n\nclass Init {\n    void run(Object context) {\n" +
                "        /* /* */ SentryAndroid.init(context); // */\n    }\n}\n",
        )
        val result = CouliSourceRules.check(root, scanningRepository = false)
        assertEquals(1, result.size)
        assertTrue(result.single(), result.single().contains("[sdk-init-only-in-privacy-gate]"))
        assertTrue(result.single(), result.single().startsWith("app/src/main/java/com/couli/app/Init.java:5:"))
    }

    @Test
    fun javaLexerKeepsKotlinOnlyRulesApart() {
        // Java 没有字符串模板：${...} 是文字；Kotlin 的嵌套块注释照旧
        val java = scanJavaSource("String s = \"\${SentryAndroid.init(c)}\"; /* a /* b */ int x = 1;")
        assertFalse(java.code.contains("SentryAndroid.init"))
        assertTrue(java.code.contains("int x = 1;"))
        assertEquals(emptyList<ScanProblem>(), java.problems)
        assertFalse(scanKotlinSource("/* /* */ SentryAndroid.init(context); // */").code.contains("SentryAndroid"))
        // 文本块：转义的引号不结束；内容里的调用不是代码
        val q = "\"\"\""
        val block = scanJavaSource("String t = $q\n  a \\$q SentryAndroid.init(c)\n  $q; Sentry.init(o);")
        assertFalse(block.code.contains("SentryAndroid.init"))
        assertTrue(block.code.contains("Sentry.init(o);"))
        assertEquals(emptyList<ScanProblem>(), block.problems)
    }

    @Test
    fun unsupportedJavaConstructsAndLanguagesAreIncomplete() {
        assertTrue(scanJavaSource("char c = '\\u0027'; SentryAndroid.init(c);").problems.any { "Unicode" in it.reason })
        assertTrue(scanJavaSource("/* never closed\nSentryAndroid.init(c);").problems.any { it.reason == "未闭合的块注释" })
        assertTrue(scanJavaSource("String t = \"\"\"inline\"\"\";").problems.isNotEmpty())
        assertTrue(scanJavaSource("int `x` = 1;").problems.isNotEmpty())
        assertEquals(null, scanSource("scala", "object A"))
        // 转义的反斜杠之后的 u 不是 Unicode 转义
        assertEquals(emptyList<ScanProblem>(), scanJavaSource("String s = \"\\\\u\";").problems)
    }

    @Test
    fun anUntraversableSourceDirectoryNextToANormalFileIsScanIncomplete() {
        write("feature/home/src/main/kotlin/Ok.kt", "package ok\n\nval ok = 1\n")
        val hidden = File(root, "feature/search/src/main/kotlin/hidden")
        write("feature/search/src/main/kotlin/hidden/Init.kt", "fun f(c: Any) = SentryAndroid.init(c)\n")
        assertTrue(hidden.setReadable(false, false))
        assertTrue(hidden.setExecutable(false, false))
        locked += hidden
        assumeTrue("以 root 运行时权限位不生效，此用例不适用", hidden.listFiles() == null)

        val result = CouliSourceRules.check(root, scanningRepository = false)
        assertTrue("目录列不出内容必须报扫不完整：$result", result.any { it.contains("[scan-incomplete]") && "hidden" in it })
        // 恢复可读后照常扫到里面的违规
        hidden.setReadable(true, false)
        hidden.setExecutable(true, false)
        assertTrue(CouliSourceRules.check(root, scanningRepository = false).single().contains("[sdk-init-only-in-privacy-gate]"))
    }

    @Test
    fun aNormalTreePassesAndTheEmptyOrMissingRootIsIncomplete() {
        write("feature/home/src/main/kotlin/Ok.kt", "package ok\n\nval ok = 1\n")
        write("app/src/main/java/Ok.java", "class Ok { String s = \"/* not a comment */\"; }\n")
        assertEquals(emptyList<String>(), CouliSourceRules.check(root, scanningRepository = false))
        assertTrue(CouliSourceRules.check(File(root, "missing"), scanningRepository = false).single().contains("[scan-incomplete]"))
        val empty = File(root, "empty").apply { mkdirs() }
        assertTrue(CouliSourceRules.check(empty, scanningRepository = false).single().contains("[scan-incomplete]"))
    }

    // ---------------------------------------------------------------- R2-02：单独回车

    @Test
    fun aLoneCarriageReturnEndsAJavaLineCommentSoTheSdkInitAfterItIsReported() {
        val file =
            write(
                "app/src/main/java/com/couli/app/Init.java",
                "package com.couli.app;\n\nclass Init {\n    void run(Object c) {\n        // comment\rSentryAndroid.init(c);\n    }\n}\n",
            )
        val bytes = String(file.readBytes(), Charsets.UTF_8)
        assertTrue("样例真的含单独回车字节", Regex("\r(?!\n)").containsMatchIn(bytes))
        val result = CouliSourceRules.check(root, scanningRepository = false)
        assertEquals(result.toString(), 1, result.size)
        // 行号按 Java 行终止符算：回车之后是第 6 行
        val expected = "app/src/main/java/com/couli/app/Init.java:6: [sdk-init-only-in-privacy-gate]"
        assertTrue(result.single(), result.single().startsWith(expected))
    }

    @Test
    fun loneCarriageReturnsAndCrlfAreLineTerminatorsForEveryJavaAndKotlinToken() {
        val cr = "\r"
        // 行注释：Java 与 Kotlin 都在单独回车处结束
        val java = scanJavaSource("// a${cr}SentryAndroid.init(c);")
        assertTrue(java.code.contains("SentryAndroid.init(c);"))
        assertEquals(emptyList<ScanProblem>(), java.problems)
        assertTrue(scanKotlinSource("// a${cr}SentryAndroid.init(c)").code.contains("SentryAndroid.init(c)"))
        // CRLF 只算一次换行；行注释照旧到行尾结束
        val crlf = scanJavaSource("// a$cr\nSentryAndroid.init(c);$cr\n// b$cr\nint x;")
        assertTrue(crlf.code.contains("SentryAndroid.init(c);"))
        assertEquals("与源码等长", "// a$cr\nSentryAndroid.init(c);$cr\n// b$cr\nint x;".length, crlf.code.length)
        // 普通字符串、字符字面量：遇单独回车未闭合（扫不完整），回车之后是代码
        val string = scanJavaSource("String s = \"abc${cr}SentryAndroid.init(c);")
        assertTrue(string.problems.any { it.reason == "未闭合的字符串" && it.line == 1 })
        assertTrue(string.code.contains("SentryAndroid.init(c);"))
        val char = scanJavaSource("char c = 'a${cr}SentryAndroid.init(c);")
        assertTrue(char.problems.any { it.reason == "未闭合的字符字面量" })
        assertTrue(char.code.contains("SentryAndroid.init(c);"))
        assertTrue(scanKotlinSource("val s = \"abc${cr}SentryAndroid.init(c)").problems.isNotEmpty())
        // 文本块：开头三引号后单独回车算换行（合法），内容跨单独回车，结束后的调用是代码
        val q = "\"\"\""
        val block = scanJavaSource("String t = $q$cr  SentryAndroid.init(a)$cr  $q; Sentry.init(o);")
        assertEquals(emptyList<ScanProblem>(), block.problems)
        assertFalse(block.code.contains("SentryAndroid.init"))
        assertTrue(block.code.contains("Sentry.init(o);"))
        // 块注释里的单独回车只计行号；行号与 LF 版一致
        assertEquals(
            scanJavaSource("/* a\n b */ x\n'").problems.map { it.line },
            scanJavaSource("/* a$cr b */ x$cr'").problems.map { it.line },
        )
    }

    // ---------------------------------------------------------------- R2-03：可列名但查不了条目属性

    @Test
    fun anEntryThatCanBeListedButWhoseAttributesCannotBeQueriedIsScanIncomplete() {
        write("feature/home/src/main/kotlin/Ok.kt", "package ok\n\nval ok = 1\n")
        val dir = File(root, "feature/search/src/main/kotlin/locked")
        write("feature/search/src/main/kotlin/locked/Hidden.kt", "fun f(c: Any) = SentryAndroid.init(c)\n")
        write("feature/search/src/main/kotlin/locked/nested/Deeper.kt", "fun g(c: Any) = Sentry.init(c)\n")
        // chmod 444：保留读、去掉执行 / 搜索权限
        Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("r--r--r--"))
        locked += dir
        assumeTrue(
            "以 root 运行时权限位不生效，此用例不适用",
            dir.list()?.toSet() == setOf("Hidden.kt", "nested") && !File(dir, "Hidden.kt").isFile && !File(dir, "nested").isDirectory,
        )

        val result = CouliSourceRules.check(root, scanningRepository = false)
        assertTrue("可列名但属性查不了的源码文件必须报扫不完整：$result", result.any { "[scan-incomplete]" in it && "locked/Hidden.kt" in it })
        assertTrue("可列名但属性查不了的子目录也必须报：$result", result.any { "[scan-incomplete]" in it && "locked/nested" in it })
        assertFalse(result.any { "Ok.kt" in it })
        // 恢复权限后照常扫到里面的两处违规
        Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
        val restored = CouliSourceRules.check(root, scanningRepository = false)
        assertEquals(restored.toString(), 2, restored.count { "[sdk-init-only-in-privacy-gate]" in it })
        assertFalse(restored.any { "[scan-incomplete]" in it })
    }

    // ---------------------------------------------------------------- FU-AND-0717（F-X-07 补充 / 补充二）：规则本体在真实目录上

    private val feature = "feature/home/src/main/kotlin/com/couli/feature/home/Page.kt"

    /** 转义写的中文（`"\u4e2d"`、`'\u4e2d'`、`'中'`）编译后就是中文：no-cjk-string-in-feature 照报。 */
    @Test
    fun fuand0717_escapedCjkStringsAndCharLiteralsInFeatureAreReported() {
        write(feature, "package p\n\nval a = \"\\u4e2d\\u6587\"\nval b = '\\u4e2d'\nval c = '中'\nval d = \"\\u0041\"\n")
        val result = CouliSourceRules.check(root, scanningRepository = false)
        assertEquals(result.toString(), listOf(3, 4, 5), result.map { it.substringAfter("Page.kt:").substringBefore(':').toInt() })
        assertTrue(result.toString(), result.all { "[no-cjk-string-in-feature]" in it })
    }

    /** 反引号标识符、注释边界（块注释、调用、行注释里再放一个结束符）、后缀运算符之后的斜杠都不能绕过 SDK 规则；注释里的调用不误报。 */
    @Test
    fun fuand0717_sdkInitBehindCommentBoundariesBackticksAndPostfixOperatorsIsReported() {
        write(
            "core/push/src/main/kotlin/com/couli/core/push/Init.kt",
            "package p\n\n" +
                "fun a(c: Any) { /* 记号 */ MobSDK.init(c) // */\n}\n" + // 3
                "fun b(c: Any) { `MobSDK`.`init`(c) }\n" + // 5
                "fun d(c: Any?) { val r = c!!.hashCode()/2; SentryAndroid.init(c) }\n" + // 6
                "fun e(c: Any) { /* /* */ Sentry.init(c) // */ }\n" + // 7：嵌套，整段注释，不报
                "fun f(c: Any) { val s = \"/*\"; WXAPIFactory.createWXAPI(c) }\n", // 8
        )
        val result = CouliSourceRules.check(root, scanningRepository = false)
        assertTrue(result.toString(), result.all { "[sdk-init-only-in-privacy-gate]" in it })
        assertEquals(result.toString(), listOf(3, 5, 6, 8), result.map { it.substringAfter("Init.kt:").substringBefore(':').toInt() })
    }

    /** Java：块注释不嵌套，「块注释、调用、行注释里的结束符」中的调用是代码；后缀运算符之后的块注释照常结束。 */
    @Test
    fun fuand0717_javaCommentBoundarySamplesAreReported() {
        write(
            "app/src/main/java/com/couli/app/Boot.java",
            "class Boot {\n  void a(Object c) { /* 记号 */ Sentry.init(c); // */\n  }\n" +
                "  void b(int x, Object c) { x++/* \" */; MobPush.initialize(c); }\n}\n",
        )
        val result = CouliSourceRules.check(root, scanningRepository = false)
        assertEquals(result.toString(), listOf(2, 4), result.map { it.substringAfter("Boot.java:").substringBefore(':').toInt() }.sorted())
    }

    /** 判不清一律非零：Unicode 行终止符、无法识别的转义、多于一个字符的字符字面量。 */
    @Test
    fun fuand0717_undecidableLexemesAreScanIncomplete() {
        write(feature, "package p\n\n// note\u2028MobSDK.init(c)\nval a = \"\\q\"\nval b = 'ab'\n")
        write("app/src/main/java/com/couli/app/Boot.java", "class Boot { // x\u2029 Sentry.init(c);\n}\n")
        val result = CouliSourceRules.check(root, scanningRepository = false)
        assertEquals(result.toString(), 4, result.count { "[scan-incomplete]" in it })
        assertTrue(result.toString(), result.any { it.startsWith("feature/home/src/main/kotlin/com/couli/feature/home/Page.kt:3:") })
        assertTrue(result.toString(), result.any { it.startsWith("app/src/main/java/com/couli/app/Boot.java:1:") })
    }

    // ---------------------------------------------------------------- LINT-STYLE v1：D10-734，沿用真实扫描入口

    @Test
    fun styleHexColorLiteralsFailInFeatureAndAppMain() {
        listOf(feature, "app/src/main/kotlin/demo/Page.kt").forEach { path ->
            write(path, "val color = Color(0xFF123456)\n")
        }
        writeStyleBaseline()
        val result = CouliSourceRules.check(root, scanningRepository = true)
        assertRuleCounts(result, mapOf("no-hex-color" to 2))
        assertTrue(result.toString(), result.any { it.startsWith("$feature:1:") })
        assertTrue(result.toString(), result.any { it.startsWith("app/src/main/kotlin/demo/Page.kt:1:") })
    }

    @Test
    fun styleHexColorSupportsWhitespaceQualifiedCallsAndUnderscores() {
        val result =
            checkStyle(
                """
                val a = Color (
                    0xFF_12_34_56,
                )
                val b = androidx.compose.ui.graphics.Color(0xffabcdef)
                """.trimIndent(),
            )
        assertRuleCounts(result, mapOf("no-hex-color" to 2))
    }

    @Test
    fun styleTransparentTokensConstantsCommentsAndStringsAreLegal() {
        assertRuleCounts(
            checkStyle(
                """
                val a = Color.Transparent
                val b = CouliColors.brandPrimary
                val c = Color(COLOR_VALUE)
                // Color(0xFF123456) and Modifier.padding(8.dp)
                /* RoundedCornerShape(8) and 14.sp */
                val example = "Color(0xFF123456), Modifier.padding(8.dp), RoundedCornerShape(8), 14.sp"
                """.trimIndent(),
            ),
            emptyMap(),
        )
    }

    @Test
    fun stylePaddingAndNamedSpacingArgumentsFailPerLiteral() {
        listOf(
            "Modifier.padding(8.dp)",
            "Modifier.padding(start = 8.dp)",
            "Modifier.padding(\n    top = 8.dp,\n)",
            "PaddingValues(horizontal = 8.dp)",
            "TextLink(padding = 8.dp)",
            "Modifier.padding(start = -8.dp)",
        ).forEach { call ->
            assertRuleCounts(checkStyle("fun sample() { $call }"), mapOf("no-numeric-style" to 1))
        }
        assertRuleCounts(
            checkStyle("fun sample() { Modifier.padding(horizontal = 8.dp, vertical = 4.dp) }"),
            mapOf("no-numeric-style" to 2),
        )
    }

    @Test
    fun styleSpacedByAndSpacerDimensionsAreSpacing() {
        listOf(
            "Arrangement.spacedBy(8.dp)",
            "Arrangement.spacedBy(space = 8.dp)",
            "Spacer(Modifier.height(8.dp))",
            "Spacer(modifier = Modifier.width(8.dp))",
            "Spacer(\n    modifier = Modifier.size(8.dp),\n)",
        ).forEach { call ->
            assertRuleCounts(checkStyle("fun sample() { $call }"), mapOf("no-numeric-style" to 1))
        }
    }

    @Test
    fun styleCornerAndTextUnitLiteralsFail() {
        listOf(
            "RoundedCornerShape(8)",
            "RoundedCornerShape(8.dp)",
            "RoundedCornerShape(topStart = 8.dp)",
            "14.sp",
            "1.5.sp",
        ).forEach { expression ->
            assertRuleCounts(checkStyle("val style = $expression"), mapOf("no-numeric-style" to 1))
        }
        assertRuleCounts(
            checkStyle("val shape = RoundedCornerShape(topStart = 8.dp, bottomEnd = 4.dp)"),
            mapOf("no-numeric-style" to 2),
        )
    }

    @Test
    fun styleOrdinaryDimensionsAndBorderWidthsAreLegal() {
        assertRuleCounts(
            checkStyle(
                """
                fun sample() {
                    Box(Modifier.size(24.dp).width(48.dp).height(56.dp))
                    Modifier.sizeIn(minWidth = 24.dp, minHeight = 48.dp)
                    Modifier.widthIn(max = 320.dp).heightIn(min = 48.dp)
                    Modifier.requiredSize(24.dp).requiredWidth(24.dp).requiredHeight(24.dp)
                    Modifier.border(width = 1.dp, color = CouliColors.borderSubtle)
                    BorderStroke(1.dp, CouliColors.borderSubtle)
                    CircularProgressIndicator(strokeWidth = 2.dp)
                }
                """.trimIndent(),
            ),
            emptyMap(),
        )
    }

    @Test
    fun styleZeroDpTokensAndNumericConstantReferencesAreLegal() {
        assertRuleCounts(
            checkStyle(
                """
                const val GAP = 8
                const val RADIUS = 8
                const val FONT = 14
                fun sample() {
                    Modifier.padding(0.dp).padding(CouliSpacing.space2).padding(GAP.dp)
                    Arrangement.spacedBy(0.dp)
                    Spacer(Modifier.height(0.dp))
                    RoundedCornerShape(0.dp)
                    RoundedCornerShape(CouliRadius.button)
                    RoundedCornerShape(RADIUS)
                    Text(fontSize = FONT.sp, style = CouliTypography.body)
                }
                """.trimIndent(),
            ),
            emptyMap(),
        )
    }

    @Test
    fun styleNestedCallsDoNotTurnWidthsIntoSpacingOrLoseRealPadding() {
        assertRuleCounts(
            checkStyle(
                """
                fun sample() {
                    Column(Modifier.padding(8.dp)) {
                        Box(Modifier.width(24.dp).height(48.dp))
                        Spacer(Modifier.height(4.dp))
                    }
                }
                """.trimIndent(),
            ),
            mapOf("no-numeric-style" to 2),
        )
    }

    @Test
    fun styleInlineExemptionWithReasonOnlyAppliesToItsRuleAndLine() {
        assertRuleCounts(
            checkStyle(
                """
                val a = Color(0xFF123456) // couli-style-disable-line couli-style/no-hex-color -- external brand swatch
                val b = 14.sp // couli-style-disable-line couli-style/no-numeric-style -- approved icon glyph metric
                val c = Color(0xFF234567)
                val d = 16.sp // couli-style-disable-line couli-style/no-hex-color -- only color is exempt
                """.trimIndent(),
            ),
            mapOf("no-hex-color" to 1, "no-numeric-style" to 1),
        )
    }

    @Test
    fun styleMissingOrBlankExemptionReasonFailsAndDoesNotSuppressViolation() {
        listOf("", " --", " --   ").forEach { suffix ->
            val result =
                checkStyle("val a = Color(0xFF123456) // couli-style-disable-line couli-style/no-hex-color$suffix\n")
            assertRuleCounts(result, mapOf("exemption-reason" to 1, "no-hex-color" to 1))
        }
    }

    @Test
    fun styleExemptionTextInsideAStringDoesNotSuppressCode() {
        val source =
            "val note = \"// couli-style-disable-line couli-style/no-hex-color -- fake\"; val a = Color(0xFF123456)"
        assertRuleCounts(checkStyle(source), mapOf("no-hex-color" to 1))
    }

    @Test
    fun styleBaselineEqualCountsPassRegardlessOfLineNumbers() {
        write(feature, "// moved\n\nval a = Color(0xFF123456)\nval b = 14.sp\n")
        writeStyleBaseline(hex = 1, numeric = 1)
        assertEquals(emptyList<String>(), CouliSourceRules.check(root, scanningRepository = true))
    }

    @Test
    fun styleBaselineIncreaseFailsForEachRule() {
        listOf("no-hex-color" to "Color(0xFF123456)", "no-numeric-style" to "14.sp").forEach { (rule, expression) ->
            write(feature, "val a = $expression\nval b = $expression\n")
            writeStyleBaseline(hex = if (rule == "no-hex-color") 1 else 0, numeric = if (rule == "no-numeric-style") 1 else 0)
            assertBaselineFailure(rule, feature)
        }
    }

    @Test
    fun styleBaselineDecreaseFailsUntilTheBaselineIsLowered() {
        listOf("no-hex-color" to "Color(0xFF123456)", "no-numeric-style" to "14.sp").forEach { (rule, expression) ->
            write(feature, "val a = $expression\n")
            writeStyleBaseline(hex = if (rule == "no-hex-color") 2 else 0, numeric = if (rule == "no-numeric-style") 2 else 0)
            assertBaselineFailure(rule, feature)
            writeStyleBaseline(hex = if (rule == "no-hex-color") 1 else 0, numeric = if (rule == "no-numeric-style") 1 else 0)
            assertEquals(emptyList<String>(), CouliSourceRules.check(root, scanningRepository = true))
        }
    }

    @Test
    fun styleBaselineRemovedFileMustBeRemovedFromBaseline() {
        write(feature, "val a = 14.sp\n")
        write("app/src/main/kotlin/demo/Empty.kt", "val legal = 1\n")
        writeStyleBaseline(numeric = 1)
        assertTrue(File(root, feature).delete())
        assertBaselineFailure("no-numeric-style", feature)
        writeStyleBaseline()
        assertEquals(emptyList<String>(), CouliSourceRules.check(root, scanningRepository = true))
    }

    @Test
    fun styleBaselineCannotTransferAllowanceBetweenFilesOrRules() {
        val other = "feature/home/src/main/kotlin/demo/Other.kt"
        write(feature, "val legal = 1\n")
        write(other, "val a = Color(0xFF123456)\n")
        writeStyleBaseline(hex = 1)
        assertBaselineFailure("no-hex-color", other)
        assertTrue(File(root, other).delete())
        write(feature, "val a = 14.sp\n")
        assertBaselineFailure("no-numeric-style", feature)
    }

    @Test
    fun styleBaselineCannotHideAnEmptyExemptionReason() {
        write(feature, "val a = Color(0xFF123456) // couli-style-disable-line couli-style/no-hex-color --\n")
        writeStyleBaseline(hex = 1)
        val result = CouliSourceRules.check(root, scanningRepository = true)
        assertTrue(result.toString(), result.any { "[exemption-reason]" in it })
    }

    @Test
    fun styleOnlyFeatureAndAppMainAreScannedForStyle() {
        val source = "val a = Color(0xFF123456)\nfun sample() { Modifier.padding(8.dp) }\nval b = RoundedCornerShape(8)\nval c = 14.sp\n"
        listOf("test", "testDebug", "androidTest", "androidTestDebug", "staging", "debug", "debugFeatures").forEach { sourceSet ->
            write("feature/demo/src/$sourceSet/kotlin/demo/Page.kt", source)
            write("app/src/$sourceSet/kotlin/demo/Page.kt", source)
        }
        write("core/designsystem/src/main/kotlin/demo/Page.kt", source)
        write("core/generated/src/main/kotlin/demo/Page.kt", source)
        write("tools/example/feature/demo/src/main/kotlin/demo/Page.kt", source)
        writeStyleBaseline()
        assertEquals(emptyList<String>(), CouliSourceRules.check(root, scanningRepository = true))
        write(feature, source)
        write("app/src/main/kotlin/demo/Page.kt", source)
        assertRuleCounts(
            CouliSourceRules.check(root, scanningRepository = true),
            mapOf("no-hex-color" to 2, "no-numeric-style" to 6),
        )
    }

    @Test
    fun styleSystemButtonsAreNotInG1ListAndDoNotWarn() {
        // 编排 D10-830（couli #295 ui-rules/android.md §8）：本单撤掉「三个按钮进 G1 名单」，名单与 main（49cb455）一致；直接用 M3 按钮不产生 G1 警告
        assertEquals(
            "G1 名单必须与 main 一致",
            setOf(
                "androidx.compose.material3.Checkbox",
                "androidx.compose.material3.Switch",
                "androidx.compose.material3.RadioButton",
                "androidx.compose.material3.TextField",
                "androidx.compose.material3.OutlinedTextField",
                "androidx.compose.material3.AlertDialog",
                "androidx.compose.material3.ModalBottomSheet",
                "androidx.compose.material3.TopAppBar",
                "androidx.compose.material3.CenterAlignedTopAppBar",
                "androidx.compose.material3.NavigationBar",
                "androidx.compose.material3.FilterChip",
                "androidx.compose.material3.SegmentedButton",
                "androidx.compose.foundation.text.BasicTextField",
            ),
            DesignSystemGuard.CONTROLS.toSet(),
        )
        assertEquals("G1 名单不得有重复", DesignSystemGuard.CONTROLS.size, DesignSystemGuard.CONTROLS.toSet().size)
        listOf("Button", "TextButton", "IconButton").forEach { control ->
            val name = "androidx.compose.material3.$control"
            assertFalse("G1 名单不得含 $control", name in DesignSystemGuard.CONTROLS)
            listOf(
                "import $name\nfun sample() { $control() }",
                "import $name as PlatformControl\nfun sample() { PlatformControl() }",
                "fun sample() { $name() }",
                "import androidx.compose.material3.*\nfun sample() { $control() }",
            ).forEach { source ->
                assertRuleCounts(checkStyle(source), emptyMap())
                assertEquals(source, emptyList<String>(), CouliSourceRules.warnings(root, scanningRepository = false))
            }
        }
    }

    @Test
    fun styleLocalButtonsAndComponentWrappersDoNotWarn() {
        val source =
            """
            fun Button() = Unit
            fun TextButton() = Unit
            fun IconButton() = Unit
            fun sample() { Button(); TextButton(); IconButton(); CouliButton() }
            // androidx.compose.material3.Button()
            val example = "androidx.compose.material3.IconButton()"
            """.trimIndent()
        assertRuleCounts(checkStyle(source), emptyMap())
        assertEquals(emptyList<String>(), CouliSourceRules.warnings(root, scanningRepository = false))
    }

    @Test
    fun styleSystemControlExemptionRequiresReasonAndDoesNotLeakToNextLine() {
        val source =
            """
            import androidx.compose.material3.Switch
            fun sample() {
                Switch() // couli-style-disable-line couli-style/design-system-required -- component library has no equivalent
                Switch()
            }
            """.trimIndent()
        assertRuleCounts(checkStyle(source), emptyMap())
        val warnings = CouliSourceRules.warnings(root, scanningRepository = false)
        assertEquals(warnings.toString(), 1, warnings.size)
        assertTrue(warnings.single(), warnings.single().startsWith("$feature:4:"))
        assertRuleCounts(
            checkStyle(
                "fun sample() { androidx.compose.material3.Switch() } " +
                    "// couli-style-disable-line couli-style/design-system-required --",
            ),
            mapOf("exemption-reason" to 1),
        )
        assertEquals(1, CouliSourceRules.warnings(root, scanningRepository = false).size)
    }

    private fun checkStyle(source: String): List<String> {
        write(feature, source)
        writeStyleBaseline()
        return CouliSourceRules.check(root, scanningRepository = false)
    }

    private fun writeStyleBaseline(
        hex: Int = 0,
        numeric: Int = 0,
    ) {
        val entries =
            listOf("no-hex-color" to hex, "no-numeric-style" to numeric)
                .filter { it.second > 0 }
                .joinToString(",\n") { (rule, count) -> "  \"couli-style/$rule\": {\"$feature\": $count}" }
        write("tools/ci/hardcoded-style-baseline.json", "{\n$entries\n}\n")
    }

    private fun assertRuleCounts(
        result: List<String>,
        expected: Map<String, Int>,
    ) {
        assertEquals(result.toString(), expected.values.sum(), result.size)
        expected.forEach { (rule, count) -> assertEquals(result.toString(), count, result.count { "[$rule]" in it }) }
    }

    private fun assertBaselineFailure(
        rule: String,
        path: String,
    ) {
        val result = CouliSourceRules.check(root, scanningRepository = true)
        assertTrue("规则 × 文件计数不等必须失败：$result", result.any { "[$rule]" in it && path in it })
    }
}
