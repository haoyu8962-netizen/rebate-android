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
 * 扫描结果：
 * - [code]：与源码等长，注释、字符串与字符字面量的文字替换为空格（保留换行），
 *   字符串模板 `${…}` / `$name` 里的代码原样保留（SDK 初始化规则要能看见插值里的调用）；
 * - [strings]：全部字符串字面量文字（含插值里嵌套的字符串）。
 */
internal data class ScannedSource(
    val code: String,
    val strings: List<StringLiteral>,
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
 * - 反引号标识符原样当代码。
 */
internal class KotlinSourceScanner(
    private val source: String,
) {
    private val code = StringBuilder(source.length)
    private val strings = mutableListOf<StringLiteral>()
    private var line = 1
    private var index = 0

    fun scan(): ScannedSource {
        scanCode(insideTemplate = false)
        return ScannedSource(code.toString(), strings.toList())
    }

    /**
     * 扫描代码。[insideTemplate] 为 true 时在配对的 `}`（花括号深度回到 0）处停下，
     * 该 `}` 不消费（由调用方处理）。
     */
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

    /** Kotlin 块注释可嵌套。 */
    private fun blockComment() {
        var nesting = 0
        do {
            val opens = source.startsWith(BLOCK_COMMENT_START, index)
            val closes = !opens && source.startsWith(BLOCK_COMMENT_END, index)
            if (opens) nesting++
            if (closes) nesting--
            step()
            if (opens || closes) step()
        } while (nesting > 0 && index < source.length)
    }

    /** 字符串：[raw] 为原始字符串（三引号），否则普通字符串。 */
    private fun string(raw: Boolean) {
        val segment = Segment(line)
        repeat(if (raw) RAW_QUOTE.length else 1) { step() }
        var open = true
        while (open && index < source.length) open = stringStep(segment, raw)
        segment.flush()
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
                false
            }

            !raw && ch == '"' -> {
                step()
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
     * 当前位于字符串里的 `$`。是模板则处理并返回 true；否则（单独的 `$`）返回 false，由调用方当文字。
     * `${…}` 的内容当代码递归扫描；`$name` 的标识符当代码保留。
     */
    private fun template(segment: Segment): Boolean {
        val next = source.getOrNull(index + 1)
        if (next != '{' && next != '`' && next?.isIdentifierStart() != true) return false
        segment.flush()
        step() // $
        when (next) {
            '{' -> {
                step() // {
                scanCode(insideTemplate = true)
                if (index < source.length) step() // 配对的 }
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
        if (source.getOrNull(index) == '\'') step()
    }

    private fun backtickIdentifier() {
        step(keepCode = true)
        while (source.getOrNull(index) !in BACKTICK_STOPS) step(keepCode = true)
        if (source.getOrNull(index) == '`') step(keepCode = true)
    }

    /** 消费一个字符：[keepCode] 为 true 原样写入代码，否则写空格（换行总是保留）。 */
    private fun step(keepCode: Boolean = false) {
        val ch = source[index++]
        code.append(if (keepCode || ch == '\n') ch else ' ')
        if (ch == '\n') line++
    }

    /** 一段连续的字符串文字；遇插值时 flush，插值后从新行号重新开始。 */
    private inner class Segment(
        private var startLine: Int,
    ) {
        private val text = StringBuilder()

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
