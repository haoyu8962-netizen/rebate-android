package com.couli.buildlogic

/**
 * 字符串字面量里的一段文字（不含引号、不含插值里的代码）及其起始行。
 * 带插值的字符串按插值切成多段，各段分别记录；插值里的嵌套字符串另记。
 */
internal data class StringLiteral(
    val line: Int,
    val text: String,
    /**
     * 按编译器转义规则还原后的文字（F-X-07 补充二，FU-AND-0717）：普通字符串里的 `\uXXXX`、`\"`、`\\`、`\$` 等还原成字符；原始字符串没有转义，
     * 与 [text] 相同。规则按 [text] 与它都查（`"\u4e2d"` 编译后就是中文）。
     */
    val decoded: String = text,
)

/**
 * 扫不完整的一处（F-X-07，FU-AND-0715：fail-closed）：未闭合的字符串 / 块注释 / 字符字面量 / 反引号标识符 / 字符串模板。词法在这些地方只能
 * 猜边界，之后的代码与字符串归属不可信，规则任务据此以非零退出，不只打印诊断。
 */
internal data class ScanProblem(
    val line: Int,
    val reason: String,
)

/**
 * 扫描结果：
 * - [code]：与源码等长，注释、字符串与字符字面量的文字替换为空格（保留换行），
 *   字符串模板 `${…}` / `$name` 里的代码原样保留（SDK 初始化规则要能看见插值里的调用）；
 * - [strings]：全部字符串字面量文字（含插值里嵌套的字符串）；
 * - [problems]：扫不完整的地方（空 = 整个文件扫完且边界确定）。
 */
internal data class ScannedSource(
    val code: String,
    val strings: List<StringLiteral>,
    val problems: List<ScanProblem> = emptyList(),
    /** 字符字面量的内容（[StringLiteral.decoded] 为还原后的那一个字符；F-X-07 补充二：`'中'`、`'\u4e2d'` 同样是面向用户的文字）。 */
    val chars: List<StringLiteral> = emptyList(),
    /** 行注释（含开头的 `//`，到行尾为止）及其所在行；LINT-STYLE 的行尾豁免注释只认真正的注释，不认字符串里的同样字样。 */
    val lineComments: List<StringLiteral> = emptyList(),
)

/** 纯函数入口：扫描一段 Kotlin 源码。 */
internal fun scanKotlinSource(source: String): ScannedSource = KotlinSourceScanner(source).scan()

/**
 * 极简 Kotlin 词法（递归下降），只区分代码、注释、字符串、字符字面量与字符串模板：
 * - 行注释、可嵌套的块注释；
 * - 普通字符串（反斜杠转义，遇换行视为未闭合而结束）与原始字符串（三引号；无转义，
 *   结束于连续引号的最后三个，多出的引号属于内容）；
 * - 两种字符串里的 `${…}`：其中是任意代码（可再含字符串、模板、lambda 花括号、注释、字符字面量），
 *   按花括号深度找到配对的 `}`；`$name` 的标识符当代码保留；
 * - 字符字面量（含单个双引号、转义的单引号、Unicode 转义）；
 * - 反引号标识符原样当代码；
 * - 多美元符号插值（F-AND-02，Kotlin 2.x `$$"…"` / `$$"""…"""`）：前缀有 n 个 `$` 的字符串里，连续 n 个 `$` 才开始模板，更少的是文字。
 * 未闭合的记号照旧按上面的规则结束（不吞后面的代码），但记入 [ScannedSource.problems]（F-X-07 fail-closed）。
 *
 * FU-AND-0717（F-X-07 补充 / 补充二，对照 iOS FU-IOS-0712 R1-02、鸿蒙 FU-HM-0723 R2-03…05）：
 * - 改用 kotlinc PSI 不可行：build-logic 只 compileOnly 了 AGP / KGP，KGP 的发布件明确排除 kotlin-compiler-embeddable（编译器在隔离类路径里跑），
 *   插件运行类路径上没有 Kotlin 解析器；要用就得新增依赖声明（AGENTS.md §3 第 8 条不允许），所以仍手写词法，按编译器（Kotlin.flex）的口径逐项对齐；
 * - 转义：普通字符串与字符字面量里只认 `\t \b \n \r \' \" \\ \$` 与恰好 4 位十六进制的 `\uXXXX`，还原值进 [StringLiteral.decoded]；
 *   其他写法编译器报错，这里按扫不完整（判不清）。原始字符串没有转义（反斜杠是文字，其后的 `$` 仍可开始模板）；
 * - 字符字面量的内容进 [ScannedSource.chars]；内容不是恰好一个字符（含代理对）按扫不完整；
 * - 反引号标识符：反引号本身在 [ScannedSource.code] 里写成空格（`` `MobSDK`.init(…) `` 对 SDK 规则就是 `MobSDK.init(…)`），里面的名字照旧是代码；
 * - Kotlin 没有正则字面量：`/` 只可能是除号或注释开头，后缀运算符（`!!`、`++`、`--`）之后的除号、行注释与块注释开头与编译器同样按最长匹配处理；
 * - Unicode 行终止符（U+2028 / U+2029 / U+0085）：Kotlin 与 Java 编译器都不把它们当换行，但编辑器、评审工具与正则 `^` / `$`（MULTILINE）会，
 *   人看到的分行与编译器不一致——出现在文件任何位置一律按扫不完整（[unicodeLineTerminatorProblems]），要用请写成 `\u2028` 转义。
 */
@Suppress("TooManyFunctions") // 每种记号一个入口
internal class KotlinSourceScanner(
    text: String,
) {
    /** 行终止符已统一（R2-02：Kotlin 同样认 LF、CR、CRLF，[normalizeLineTerminators]）。 */
    private val source = normalizeLineTerminators(text)
    private val code = StringBuilder(source.length)
    private val strings = mutableListOf<StringLiteral>()
    private val problems = mutableListOf<ScanProblem>()
    private var line = 1
    private var index = 0

    private val chars = mutableListOf<StringLiteral>()
    private val lineComments = mutableListOf<StringLiteral>()

    fun scan(): ScannedSource {
        problems += unicodeLineTerminatorProblems(source)
        scanCode(insideTemplate = false)
        return ScannedSource(code.toString(), strings.toList(), problems.sortedBy { it.line }, chars.toList(), lineComments.toList())
    }

    /**
     * 扫描代码。[insideTemplate] 为 true 时在配对的 `}`（花括号深度回到 0）处停下，
     * 该 `}` 不消费（由调用方处理）。
     */
    @Suppress("CyclomaticComplexMethod") // 记号种类逐项
    private fun scanCode(insideTemplate: Boolean) {
        var depth = 0
        while (index < source.length) {
            val ch = source[index]
            when {
                source.startsWith(LINE_COMMENT, index) -> {
                    lineComment()
                }

                source.startsWith(BLOCK_COMMENT_START, index) -> {
                    blockComment()
                }

                source.startsWith(RAW_QUOTE, index) -> {
                    string(raw = true)
                }

                ch == '"' -> {
                    string(raw = false)
                }

                ch == '$' && interpolationPrefix() > 0 -> {
                    // 多美元符号插值的前缀（F-AND-02）：$ 当代码保留，随后的字符串按 n 个 $ 开始模板
                    val dollars = interpolationPrefix()
                    repeat(dollars) { step(keepCode = true) }
                    string(raw = source.startsWith(RAW_QUOTE, index), dollars = dollars)
                }

                ch == '\'' -> {
                    charLiteral()
                }

                ch == '`' -> {
                    backtickIdentifier()
                }

                ch == '}' && insideTemplate && depth == 0 -> {
                    return
                }

                else -> {
                    if (ch == '{') depth++
                    if (ch == '}') depth--
                    step(keepCode = true)
                }
            }
        }
    }

    private fun lineComment() {
        val start = index
        val startLine = line
        while (source.getOrNull(index) !in LINE_END_STOPS) step()
        lineComments += StringLiteral(startLine, source.substring(start, index))
    }

    /** 代码里位于 `$` 时：连续的 `$` 后面紧跟引号就是插值前缀，返回 `$` 的个数；否则 0。 */
    private fun interpolationPrefix(): Int {
        var run = 0
        while (source.getOrNull(index + run) == '$') run++
        return if (source.getOrNull(index + run) == '"') run else 0
    }

    /** Kotlin 块注释可嵌套。 */
    private fun blockComment() {
        val start = line
        var nesting = 0
        do {
            val opens = source.startsWith(BLOCK_COMMENT_START, index)
            val closes = !opens && source.startsWith(BLOCK_COMMENT_END, index)
            if (opens) nesting++
            if (closes) nesting--
            step()
            if (opens || closes) step()
        } while (nesting > 0 && index < source.length)
        if (nesting > 0) problems += ScanProblem(start, "未闭合的块注释")
    }

    /** 字符串：[raw] 为原始字符串（三引号），否则普通字符串；[dollars] 是插值前缀的 `$` 个数（普通写法为 1）。 */
    private fun string(
        raw: Boolean,
        dollars: Int = 1,
    ) {
        val start = line
        val segment = Segment(line, dollars)
        repeat(if (raw) RAW_QUOTE.length else 1) { step() }
        var open = true
        while (open && index < source.length) open = stringStep(segment, raw)
        segment.flush()
        if (!segment.closed) problems += ScanProblem(start, if (raw) "未闭合的原始字符串" else "未闭合的字符串")
    }

    /** 处理字符串里的下一个单元；返回字符串是否仍未结束。 */
    private fun stringStep(
        segment: Segment,
        raw: Boolean,
    ): Boolean {
        val ch = source[index]
        return when {
            raw && source.startsWith(RAW_QUOTE, index) -> {
                // 结束于连续引号的最后三个：多出的引号是内容
                var run = 0
                while (source.getOrNull(index + run) == '"') run++
                segment.append(source, index, index + run - RAW_QUOTE.length)
                repeat(run) { step() }
                segment.closed = true
                false
            }

            !raw && ch == '"' -> {
                step()
                segment.closed = true
                false
            }

            // 未闭合的普通字符串在行尾结束，不吞后面的代码
            !raw && ch == '\n' -> {
                false
            }

            !raw && ch == '\\' && source.getOrNull(index + 1) !in LINE_END_STOPS -> {
                val start = index
                val decoded = escape()
                segment.append(source, start, index, decoded)
                true
            }

            ch == '$' && template(segment) -> {
                true
            }

            else -> {
                segment.append(source, index, index + 1)
                step()
                true
            }
        }
    }

    /**
     * 当前位于反斜杠（普通字符串或字符字面量里）：按编译器的转义规则消费并返回还原后的字符；不是合法转义（编译器报错）记扫不完整，
     * 只消费反斜杠与下一个字符、返回原文。
     */
    @Suppress("ReturnCount")
    private fun escape(): String {
        val next = source.getOrNull(index + 1)
        SIMPLE_ESCAPES[next]?.let { value ->
            step()
            step()
            return value.toString()
        }
        if (next == 'u') {
            val hex = source.substring(index + 2, minOf(source.length, index + 2 + UNICODE_HEX))
            if (hex.length == UNICODE_HEX && hex.all { it in HEX_DIGITS }) {
                repeat(2 + UNICODE_HEX) { step() }
                return hex.toInt(HEX_RADIX).toChar().toString()
            }
        }
        problems += ScanProblem(line, "无法识别的转义（\\$next），编译器会报错")
        val raw = source.substring(index, minOf(source.length, index + 2))
        repeat(raw.length) { step() }
        return raw
    }

    /**
     * 当前位于字符串里的 `$`。是模板则处理并返回 true；否则（`$` 不够前缀个数、后面不是模板开头）返回 false，由调用方当文字。
     * 连续 r 个 `$`、前缀 n 个时：r ≥ n 且之后是模板开头 → 前 r − n 个是文字、后 n 个开始模板；否则这 r 个都是文字。
     * `${…}` 的内容当代码递归扫描；`$name` 的标识符当代码保留。
     */
    @Suppress("ReturnCount")
    private fun template(segment: Segment): Boolean {
        var run = 0
        while (source.getOrNull(index + run) == '$') run++
        val next = source.getOrNull(index + run)
        val opens = next == '{' || next == '`' || next?.isIdentifierStart() == true
        if (run < segment.dollars || !opens) {
            // 不是模板：这一串 $ 都是文字（下一个单元从 $ 之后继续）
            segment.append(source, index, index + run)
            repeat(run) { step() }
            return true
        }
        segment.append(source, index, index + run - segment.dollars)
        repeat(run - segment.dollars) { step() }
        segment.flush()
        val start = line
        repeat(segment.dollars) { step() } // $
        when (next) {
            '{' -> {
                step() // {
                scanCode(insideTemplate = true)
                if (index < source.length) step() else problems += ScanProblem(start, "未闭合的字符串模板")
            }

            '`' -> {
                backtickIdentifier()
            }

            else -> {
                while (source.getOrNull(index)?.isIdentifierPart() == true) step(keepCode = true)
            }
        }
        segment.restart(line)
        return true
    }

    /** 字符字面量；内容不进字符串规则的 [strings]，另记进 [chars]（还原转义）。内容不是恰好一个字符按扫不完整（F-X-07 补充二）。 */
    private fun charLiteral() {
        val start = line
        step() // 开单引号
        val raw = StringBuilder()
        val decoded = StringBuilder()
        if (source.getOrNull(index) == '\\' && source.getOrNull(index + 1) !in LINE_END_STOPS) {
            val from = index
            decoded.append(escape())
            raw.append(source, from, index)
        }
        // 剩余内容（普通字符或代理对）直到闭单引号；遇换行停下
        var guard = 0
        while (guard < MAX_CHAR_LITERAL && source.getOrNull(index) !in CHAR_LITERAL_STOPS) {
            raw.append(source[index])
            decoded.append(source[index])
            step()
            guard++
        }
        if (source.getOrNull(index) != '\'') {
            problems += ScanProblem(start, "未闭合的字符字面量")
            return
        }
        step()
        val value = decoded.toString()
        if (value.codePointCount(0, value.length) != 1) problems += ScanProblem(start, "字符字面量的内容不是一个字符，编译器会报错")
        chars += StringLiteral(start, raw.toString(), value)
    }

    /** 反引号标识符：名字是代码，两个反引号写成空格（FU-AND-0717：`` `MobSDK`.init(…) `` 对 SDK 规则就是 `MobSDK.init(…)`）。 */
    private fun backtickIdentifier() {
        val start = line
        step()
        while (source.getOrNull(index) !in BACKTICK_STOPS) step(keepCode = true)
        if (source.getOrNull(index) == '`') step() else problems += ScanProblem(start, "未闭合的反引号标识符")
    }

    /** 消费一个字符：[keepCode] 为 true 原样写入代码，否则写空格（换行总是保留）。 */
    private fun step(keepCode: Boolean = false) {
        val ch = source[index++]
        code.append(if (keepCode || ch == '\n') ch else ' ')
        if (ch == '\n') line++
    }

    /** 一段连续的字符串文字；遇插值时 flush，插值后从新行号重新开始。[dollars] 是模板前缀的 `$` 个数。 */
    private inner class Segment(
        private var startLine: Int,
        val dollars: Int,
    ) {
        private val text = StringBuilder()
        private val decoded = StringBuilder()

        /** 遇到了闭合引号（没遇到 = 未闭合）。 */
        var closed = false

        /** 追加原文 [start]..[end]；[value] 是还原后的值（转义），缺省与原文相同。 */
        fun append(
            from: String,
            start: Int,
            end: Int,
            value: String = from.substring(start, end),
        ) {
            text.append(from, start, end)
            decoded.append(value)
        }

        fun flush() {
            if (text.isNotEmpty()) strings += StringLiteral(startLine, text.toString(), decoded.toString())
            text.setLength(0)
            decoded.setLength(0)
        }

        fun restart(newLine: Int) {
            startLine = newLine
        }
    }

    private companion object {
        const val LINE_COMMENT = "//"
        const val BLOCK_COMMENT_START = "/*"
        const val BLOCK_COMMENT_END = "*/"
        const val RAW_QUOTE = "\"\"\""
        const val MAX_CHAR_LITERAL = 8
        const val UNICODE_HEX = 4
        const val HEX_RADIX = 16
        const val HEX_DIGITS = "0123456789abcdefABCDEF"
        val SIMPLE_ESCAPES =
            mapOf('t' to '\t', 'b' to '\b', 'n' to '\n', 'r' to '\r', '\'' to '\'', '"' to '"', '\\' to '\\', '$' to '$')
        val CHAR_LITERAL_STOPS = setOf(null, '\'', '\n')
        val BACKTICK_STOPS = setOf(null, '`', '\n')
        val LINE_END_STOPS = setOf(null, '\n')
    }
}

private fun Char.isIdentifierStart(): Boolean = isLetter() || this == '_'

private fun Char.isIdentifierPart(): Boolean = isLetterOrDigit() || this == '_'
