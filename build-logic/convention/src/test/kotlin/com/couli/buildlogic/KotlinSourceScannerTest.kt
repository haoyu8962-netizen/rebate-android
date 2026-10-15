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
}
