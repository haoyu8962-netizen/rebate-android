package com.couli.buildlogic

/**
 * 字符串字面量里的一段文字（不含引号、不含插值里的代码）及其起始行。
 * 带插值的字符串按插值切成多段，各段分别记录；插值里的嵌套字符串另记。
 */
internal data class StringLiteral(
    val line: Int,
    val text: String,
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

    fun scan(): ScannedSource {
        scanCode(insideTemplate = false)
        return ScannedSource(code.toString(), strings.toList(), problems.toList())
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
        while (source.getOrNull(index) !in LINE_END_STOPS) step()
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
                segment.append(source, index, index + 2)
                step()
                step()
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

    /** 字符字面量；内容不属于字符串规则。 */
    private fun charLiteral() {
        val start = line
        step() // 开单引号
        if (source.getOrNull(index) == '\\') {
            step()
            if (source.getOrNull(index) !in LINE_END_STOPS) step()
        }
        // 剩余内容（Unicode 转义的数字、普通字符或代理对）直到闭单引号；遇换行停下
        var guard = 0
        while (guard < MAX_CHAR_LITERAL && source.getOrNull(index) !in CHAR_LITERAL_STOPS) {
            step()
            guard++
        }
        if (source.getOrNull(index) == '\'') step() else problems += ScanProblem(start, "未闭合的字符字面量")
    }

    private fun backtickIdentifier() {
        val start = line
        step(keepCode = true)
        while (source.getOrNull(index) !in BACKTICK_STOPS) step(keepCode = true)
        if (source.getOrNull(index) == '`') step(keepCode = true) else problems += ScanProblem(start, "未闭合的反引号标识符")
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

        /** 遇到了闭合引号（没遇到 = 未闭合）。 */
        var closed = false

        fun append(
            from: String,
            start: Int,
            end: Int,
        ) {
            text.append(from, start, end)
        }

        fun flush() {
            if (text.isNotEmpty()) strings += StringLiteral(startLine, text.toString())
            text.setLength(0)
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
        val CHAR_LITERAL_STOPS = setOf(null, '\'', '\n')
        val BACKTICK_STOPS = setOf(null, '`', '\n')
        val LINE_END_STOPS = setOf(null, '\n')
    }
}

private fun Char.isIdentifierStart(): Boolean = isLetter() || this == '_'

private fun Char.isIdentifierPart(): Boolean = isLetterOrDigit() || this == '_'
