package com.couli.buildlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinSourceScannerTest {
    private fun texts(source: String): List<String> = scanKotlinSource(source).strings.map { it.text }

    private fun code(source: String): String = scanKotlinSource(source).code

    @Test
    fun codeKeepsLengthAndLineBreaks() {
        val source = "val a = \"x\\ny\" // c\n/* b\n */ val b = 'q'\n"
        val scanned = scanKotlinSource(source)
        assertEquals(source.length, scanned.code.length)
        assertEquals(source.count { it == '\n' }, scanned.code.count { it == '\n' })
    }

    @Test
    fun plainStringAndEscapedQuote() {
        assertEquals(listOf("a\\\"b"), texts("val s = \"a\\\"b\""))
    }

    @Test
    fun templateWithNestedStringLiterals() {
        // 评审复现：val text = "${if (ok) "成功" else "失败"}"
        val source = "val text = \"\${if (ok) \"成功\" else \"失败\"}\""
        assertEquals(listOf("成功", "失败"), texts(source))
        val code = code(source)
        assertTrue(code.contains("if (ok)"))
        assertTrue(code.contains("else"))
        assertFalse(code.contains("成功"))
    }

    @Test
    fun templateKeepsCallsVisibleToSdkRule() {
        val source = "val s = \"init \${SentryAndroid.init(context)} done\""
        assertTrue(code(source).contains("SentryAndroid.init(context)"))
        assertEquals(listOf("init ", " done"), texts(source))
    }

    @Test
    fun nestedTemplateInsideTemplateString() {
        val source = "val s = \"a\${f(\"b\${g(\"中\")}c\")}d\" + \"tail\""
        assertEquals(listOf("a", "b", "中", "c", "d", "tail"), texts(source))
        val code = code(source)
        assertTrue(code.contains("f("))
        assertTrue(code.contains("g("))
        assertTrue(code.contains("+"))
    }

    @Test
    fun lambdaBracesInsideTemplate() {
        val source = "val s = \"\${list.map { \"x\$it\" }.joinToString()}!\"\nval t = 1"
        assertEquals(listOf("x", "!"), texts(source))
        val code = code(source)
        assertTrue(code.contains("list.map {"))
        assertTrue(code.contains("}.joinToString()"))
        assertTrue(code.contains("val t = 1"))
    }

    @Test
    fun simpleNameTemplateIsCode() {
        val source = "val s = \"hello \$name!\""
        assertEquals(listOf("hello ", "!"), texts(source))
        assertTrue(code(source).contains("name"))
    }

    @Test
    fun rawStringWithTemplateAndQuotes() {
        val q = "\"\"\""
        val source = "val s = $q a \"quoted\" \${SentryAndroid.init(c)} \${\"中文\"} b$q\nval after = 1"
        assertEquals(listOf(" a \"quoted\" ", " ", "中文", " b"), texts(source))
        val code = code(source)
        assertTrue(code.contains("SentryAndroid.init(c)"))
        assertTrue(code.contains("val after = 1"))
    }

    @Test
    fun rawStringEndsAtLastOfQuoteRun() {
        val q = "\"\"\""
        val source = "val s = ${q}x\"$q\nval after = 1"
        assertEquals(listOf("x\""), texts(source))
        assertTrue(code(source).contains("val after = 1"))
    }

    @Test
    fun rawStringBackslashIsNotEscape() {
        val q = "\"\"\""
        val source = "val s = $q\\$q + \"中\""
        assertEquals(listOf("\\", "中"), texts(source))
    }

    @Test
    fun charLiteralsWithQuotes() {
        val source = "val a = '\"'\nval b = '\\''\nval c = '\\u0041'\nval d = \"中\""
        assertEquals(listOf("中"), texts(source))
        val code = code(source)
        assertFalse(code.contains("'"))
        assertTrue(code.contains("val d ="))
    }

    @Test
    fun charLiteralQuoteInsideTemplate() {
        val source = "val s = \"\${if (c == '\"') \"中\" else \"x\"}\""
        assertEquals(listOf("中", "x"), texts(source))
    }

    @Test
    fun quotesInCommentsAreIgnored() {
        val source =
            "// he said \"hi\n" +
                "/* \"unclosed /* nested \" */ still comment \" */\n" +
                "val s = \"real\"\n" +
                "SentryAndroid.init(c)"
        assertEquals(listOf("real"), texts(source))
        assertTrue(code(source).contains("SentryAndroid.init(c)"))
        assertFalse(code(source).contains("still comment"))
    }

    @Test
    fun commentInsideTemplate() {
        val source = "val s = \"\${f(/* \" } */ \"中\")}\""
        assertEquals(listOf("中"), texts(source))
    }

    @Test
    fun unterminatedStringStopsAtLineEnd() {
        val source = "val s = \"abc\nSentryAndroid.init(c)"
        assertEquals(listOf("abc"), texts(source))
        assertTrue(code(source).contains("SentryAndroid.init(c)"))
    }

    @Test
    fun dollarWithoutTemplateIsText() {
        assertEquals(listOf("$ 5 \$"), texts("val s = \"$ 5 \$\""))
    }

    @Test
    fun linesOfLiteralSegments() {
        val q = "\"\"\""
        val source = "val s = ${q}a\n\${x}\nb$q"
        val strings = scanKotlinSource(source).strings
        assertEquals(listOf(1, 2), strings.map { it.line })
    }

    // ---------------------------------------------------------------- FU-AND-0715：F-AND-02 多美元符号插值、F-X-07 fail-closed

    private fun problems(source: String): List<String> = scanKotlinSource(source).problems.map { it.reason }

    @Test
    fun multiDollarStringOnlyStartsTemplatesWithTheFullPrefix() {
        // $$"…"：单个 $ 是文字（含 ${ 中文 }），$$ 才开始模板
        val source = "val s = \$\$\"price \${ 中文 } \$name \$\${SentryAndroid.init(c)} \$\$id!\"\nval after = 1"
        assertEquals(listOf("price \${ 中文 } \$name ", " ", "!"), texts(source))
        val code = code(source)
        assertTrue(code.contains("SentryAndroid.init(c)"))
        assertTrue(code.contains("id"))
        assertTrue(code.contains("val after = 1"))
        assertEquals(emptyList<String>(), problems(source))
    }

    @Test
    fun multiDollarRawStringAndExtraDollarsAreText() {
        val q = "\"\"\""
        // $$$x 在 $$ 前缀下：前一个 $ 是文字，后两个开始模板
        val source = "val s = \$\$$q a \${x} \$\$\$y b$q\nval after = 1"
        assertEquals(listOf(" a \${x} \$", " b"), texts(source))
        assertTrue(code(source).contains("y"))
        assertTrue(code(source).contains("val after = 1"))
        assertEquals(emptyList<String>(), problems(source))
    }

    @Test
    fun completeSourcesHaveNoProblems() {
        val q = "\"\"\""
        val source = "val a = \"x\\\"y\" // c \"\n/* a /* b */ c */ val b = '\\''\nval c = $q\${f(\"\${g()}\")}$q\nval `d e` = '\"'"
        assertEquals(emptyList<String>(), problems(source))
    }

    @Test
    fun unterminatedTokensAreReportedAsIncomplete() {
        val q = "\"\"\""
        assertEquals(listOf("未闭合的字符串"), problems("val s = \"abc\nval t = 1"))
        assertEquals(listOf("未闭合的原始字符串"), problems("val s = ${q}abc\nSentryAndroid.init(c)"))
        assertEquals(listOf("未闭合的块注释"), problems("/* a /* b */ still\nval t = 1"))
        assertEquals(listOf("未闭合的字符字面量"), problems("val c = 'ab\nval t = 1"))
        assertEquals(listOf("未闭合的反引号标识符"), problems("val `a b\nval t = 1"))
        assertTrue(problems("val s = \"\${f(\"x\")").contains("未闭合的字符串模板"))
        // 行号是记号开始的那一行
        assertEquals(listOf(2), scanKotlinSource("val a = 1\nval s = \"open\n").problems.map { it.line })
    }

    // ---------------------------------------------------------------- FU-AND-0717（F-X-07 补充 / 补充二）

    private fun decoded(source: String): List<String> = scanKotlinSource(source).strings.map { it.decoded }

    /** iOS FU-IOS-0712 R1-02 的 Kotlin 版：块注释在第一个配对的结束符结束，之后是代码，行尾的结束符在行注释里。 */
    @Test
    fun fuand0717_blockCommentThenCodeThenLineCommentHoldingACloser() {
        val source = "/* 记号 */ MobSDK.init(c) // */\nval t = 1"
        val scanned = scanKotlinSource(source)
        assertTrue(scanned.code.contains("MobSDK.init(c)"))
        assertTrue(scanned.code.contains("val t = 1"))
        assertFalse(scanned.code.contains("记号"))
        assertEquals(emptyList<ScanProblem>(), scanned.problems)
    }

    /** Kotlin 块注释可嵌套（与编译器一致）：内层的结束符只回到深度 1，后面的调用仍在注释里；深度没回到 0 就是未闭合。 */
    @Test
    fun fuand0717_nestedBlockCommentDepthDecidesTheBoundary() {
        val closed = scanKotlinSource("/* /* */ MobSDK.init(c) */ val s = \"中\"")
        assertFalse(closed.code.contains("MobSDK"))
        assertEquals(listOf("中"), closed.strings.map { it.text })
        assertEquals(emptyList<ScanProblem>(), closed.problems)
        // 行注释记号在块注释里不起作用：行尾的结束符把深度带回 0，整段（含调用）都是注释（与 Java 的不嵌套相反，见 CouliSourceRulesTest）
        val swallowed = scanKotlinSource("/* /* */ MobSDK.init(c) // */\nval t = 1")
        assertFalse(swallowed.code.contains("MobSDK"))
        assertTrue(swallowed.code.contains("val t = 1"))
        assertEquals(emptyList<ScanProblem>(), swallowed.problems)
        // 深度回不到 0 → 判不清
        assertEquals(listOf("未闭合的块注释"), problems("/* /* */ MobSDK.init(c)\nval t = 1"))
        // 注释里的引号不是字符串：注释在第一个结束符处结束，之后的引号才开始（未闭合的）字符串
        assertEquals(listOf("未闭合的字符串"), problems("/* \"*/\" /* */ x"))
    }

    /** 字符串、字符字面量里的注释记号是文字：不开始注释，后面的调用仍是代码。 */
    @Test
    fun fuand0717_commentMarkersInsideStringsAndCharsAreText() {
        val source =
            "val a = \"/*\"; MobSDK.init(c); val b = \"*/\"\nval d = \"//\"; Sentry.init(o)\n" +
                "val e = \"\\\"/*\"; '\"'; SentryAndroid.init(x) // \"\n"
        val scanned = scanKotlinSource(source)
        assertTrue(scanned.code.contains("MobSDK.init(c)"))
        assertTrue(scanned.code.contains("Sentry.init(o)"))
        assertTrue(scanned.code.contains("SentryAndroid.init(x)"))
        assertEquals(listOf("/*", "*/", "//", "\\\"/*"), scanned.strings.map { it.text })
        assertEquals(emptyList<ScanProblem>(), scanned.problems)
    }

    /** 模板里的字符串含 `}` 与注释记号：不提前结束模板，模板之后的文字照旧是字符串。 */
    @Test
    fun fuand0717_templateStringsHoldingBracesAndCommentMarkers() {
        val source = "val s = \"\${f(\"}/*\")} 中 \${g('}')}\"; MobSDK.init(c)"
        val scanned = scanKotlinSource(source)
        assertEquals(listOf("}/*", " 中 "), scanned.strings.map { it.text })
        assertTrue(scanned.code.contains("f("))
        assertTrue(scanned.code.contains("MobSDK.init(c)"))
        assertEquals(emptyList<ScanProblem>(), scanned.problems)
    }

    /** 普通字符串的转义按编译器还原（`\u4e2d` 编译后是中文）；`\$` 不开始模板；不认识的转义编译器报错 → 判不清。 */
    @Test
    fun fuand0717_escapesInPlainStringsAreDecodedLikeTheCompiler() {
        assertEquals(listOf("中文"), decoded("val s = \"\\u4e2d\\u6587\""))
        assertEquals(listOf("\\u4e2d\\u6587"), texts("val s = \"\\u4e2d\\u6587\""))
        assertEquals(listOf("a\"b\\c\$x\t"), decoded("val s = \"a\\\"b\\\\c\\\$x\\t\""))
        // \$ 是文字：后面的名字不是模板代码
        assertFalse(code("val s = \"\\\$MobSDK\"").contains("MobSDK"))
        assertEquals(listOf("无法识别的转义（\\q），编译器会报错"), problems("val s = \"\\q\""))
        assertEquals(listOf("无法识别的转义（\\u），编译器会报错"), problems("val s = \"\\u12\""))
        assertEquals(listOf("无法识别的转义（\\u），编译器会报错"), problems("val s = \"\\u12g4\""))
    }

    /** 原始字符串没有转义：反斜杠是文字，`\u4e2d` 就是六个字符；反斜杠之后的 `${…}` 仍是模板。 */
    @Test
    fun fuand0717_rawStringsHaveNoEscapes() {
        val q = "\"\"\""
        val scanned = scanKotlinSource("val s = $q\\u4e2d \\\${MobSDK.init(c)}$q")
        assertEquals(listOf("\\u4e2d \\"), scanned.strings.map { it.decoded })
        assertTrue(scanned.code.contains("MobSDK.init(c)"))
        assertEquals(emptyList<ScanProblem>(), scanned.problems)
    }

    /** 字符字面量的内容按转义还原后记下；不是恰好一个字符（编译器报错）→ 判不清。 */
    @Test
    fun fuand0717_charLiteralsAreDecodedAndMustHoldOneCharacter() {
        val scanned = scanKotlinSource("val a = '中'\nval b = '\\u4e2d'\nval c = '\\''\nval d = '😀'")
        assertEquals(listOf("中", "中", "'", "😀"), scanned.chars.map { it.decoded })
        assertEquals(emptyList<ScanProblem>(), scanned.problems)
        assertEquals(listOf("字符字面量的内容不是一个字符，编译器会报错"), problems("val c = 'ab'"))
        assertTrue(problems("val c = '\\u4e'").contains("无法识别的转义（\\u），编译器会报错"))
    }

    /** 反引号标识符：反引号写成空格、名字留在代码里（SDK 规则看得见 `MobSDK`.`init`(…)）。 */
    @Test
    fun fuand0717_backticksAreBlankedButNamesStayCode() {
        val source = "`MobSDK`.`init`(c)"
        val code = code(source)
        assertEquals(source.length, code.length)
        assertEquals(" MobSDK . init (c)", code)
    }

    /** Kotlin 没有正则字面量：后缀 `!!` / `++` / `--` 之后的单斜杠是除号，双斜杠与斜杠星号按最长匹配是注释（与编译器一致）。 */
    @Test
    fun fuand0717_postfixOperatorsFollowedBySlashes() {
        val division = scanKotlinSource("val r = b!!/2 / \"中\".length; MobSDK.init(c)")
        assertTrue(division.code.contains("MobSDK.init(c)"))
        assertEquals(listOf("中"), division.strings.map { it.text })
        assertFalse(code("x++//MobSDK.init(c)\nval t = 1").contains("MobSDK"))
        val block = scanKotlinSource("x--/* \" */; MobSDK.init(c)")
        assertTrue(block.code.contains("MobSDK.init(c)"))
        assertEquals(emptyList<ScanProblem>(), block.problems)
        // 除号之后的引号仍开始字符串（不会被当成正则的一部分吞掉后面的代码）
        assertEquals(listOf("/ 中 /"), texts("val r = a!! / 2; val s = \"/ 中 /\""))
    }

    /** Unicode 行终止符：编译器不当换行、编辑器当换行——出现在代码、注释、字符串里都按判不清（行号是所在行）。 */
    @Test
    fun fuand0717_unicodeLineTerminatorsAreIncompleteAnywhere() {
        val reason = "Unicode 行终止符（U+2028 / U+2029 / U+0085）：编译器不当换行、编辑器与评审工具当换行，边界无法确认"
        assertEquals(listOf(reason), problems("// note\u2028MobSDK.init(c)"))
        assertEquals(listOf(reason), problems("val s = \"a\u2029b\""))
        assertEquals(listOf(2), scanKotlinSource("val a = 1\n/* x\u0085 */ val b = 2").problems.map { it.line })
        assertEquals(listOf(reason), scanJavaSource("// note\u2028Sentry.init(o);").problems.map { it.reason })
        // 写成转义没有问题
        assertEquals(emptyList<String>(), problems("val s = \"a\\u2028b\""))
    }
}
