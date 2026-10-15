package com.couli.buildlogic

/**
 * 按文件语言选词法（FU-AND-0715 第 1 轮评审 R1-04）：`.kt` 用 Kotlin 词法（块注释可嵌套、字符串模板），`.java` 用 Java 词法（块注释**不**嵌套、
 * 没有字符串模板、文本块有转义）。其余扩展名不支持，返回 null（调用方按 `[scan-incomplete]` 非零退出）。
 */
internal fun scanSource(
    extension: String,
    source: String,
): ScannedSource? =
    when (extension) {
        "kt" -> scanKotlinSource(source)
        "java" -> scanJavaSource(source)
        else -> null
    }

/** 纯函数入口：扫描一段 Java 源码。 */
internal fun scanJavaSource(source: String): ScannedSource = JavaSourceScanner(source).scan()

/**
 * 行终止符（FU-AND-0715 第 2 轮评审 R2-02）：Java（JLS §3.4）与 Kotlin（语法 `NL: '\n' | '\r' '\n'?`）都认 LF、CR、CRLF 三种。词法只按 LF 分行，
 * 所以扫描前把**单独的** CR（后面不是 LF）换成 LF——一对一替换，[ScannedSource.code] 仍与源码等长、偏移不变；CRLF 保留（CR 在 LF 之前当普通字符，
 * 只算一次换行）。这样行注释、普通字符串、字符字面量在单独 CR 处结束，文本块开头三引号后单独 CR 也算换行，行号与编译器一致。
 * 其他字符（U+0085、U+2028、U+2029 等）在两种语言里都不是行终止符，照普通字符处理。
 */
internal fun normalizeLineTerminators(source: String): String {
    if (source.indexOf('\r') < 0) return source
    val out = StringBuilder(source.length)
    source.forEachIndexed { i, ch ->
        out.append(if (ch == '\r' && source.getOrNull(i + 1) != '\n') '\n' else ch)
    }
    return out.toString()
}

/**
 * 极简 Java 词法，只区分代码、注释、字符串、文本块与字符字面量（[ScannedSource] 口径与 Kotlin 版相同：[ScannedSource.code] 与源码等长）：
 * - 行注释；块注释**不嵌套**——开始符之后第一个结束符就结束（评审 R1-04 的样例：注释里再写一个开始符，第一个结束符之后的 SDK 初始化调用是代码）；
 * - 普通字符串（反斜杠转义，遇换行未闭合）；文本块（`"""` 后只能有空白再换行；反斜杠转义；第一个未转义的 `"""` 结束）；
 * - 字符字面量（反斜杠转义）；`$` 是普通标识符字符，没有模板。
 * - 换行：LF、CR、CRLF 都是行终止符（R2-02），单独的 CR 同样结束行注释、普通字符串与字符字面量。
 *
 * 暂不支持、按扫不完整报（[ScanProblem]，fail-closed）：Unicode 转义 `\uXXXX`（Java 在词法之前翻译，可能变出引号或注释边界）、反引号（不是 Java
 * 记号）、文本块开头格式不对，以及各种未闭合的记号。
 */
internal class JavaSourceScanner(
    text: String,
) {
    /** 行终止符已统一（R2-02，[normalizeLineTerminators]）。 */
    private val source = normalizeLineTerminators(text)
    private val code = StringBuilder(source.length)
    private val strings = mutableListOf<StringLiteral>()
    private val problems = mutableListOf<ScanProblem>()
    private var line = 1
    private var index = 0

    fun scan(): ScannedSource {
        unicodeEscapes()
        while (index < source.length) {
            when {
                source.startsWith("//", index) -> lineComment()
                source.startsWith(BLOCK_START, index) -> blockComment()
                source.startsWith(TEXT_BLOCK, index) -> textBlock()
                source[index] == '"' -> string()
                source[index] == '\'' -> charLiteral()
                source[index] == '`' -> unsupported("反引号不是 Java 记号").also { step(keepCode = true) }
                else -> step(keepCode = true)
            }
        }
        return ScannedSource(code.toString(), strings.toList(), problems.toList())
    }

    /** Unicode 转义在 Java 词法之前就翻译（JLS §3.3）：前面有偶数个反斜杠的 `\u` 都算，一律不支持（整份文件报扫不完整）。 */
    private fun unicodeEscapes() {
        var lineNo = 1
        var backslashes = 0
        source.forEachIndexed { i, ch ->
            if (ch == '\\' && source.getOrNull(i + 1) == 'u' && backslashes % 2 == 0) {
                problems += ScanProblem(lineNo, "Java Unicode 转义（\\uXXXX，词法前翻译）暂不支持")
            }
            backslashes = if (ch == '\\') backslashes + 1 else 0
            if (ch == '\n') lineNo++
        }
    }

    private fun unsupported(reason: String) {
        problems += ScanProblem(line, reason)
    }

    private fun lineComment() {
        while (index < source.length && source[index] != '\n') step()
    }

    /** Java 块注释不嵌套：第一个结束符结束。 */
    private fun blockComment() {
        val start = line
        step()
        step()
        while (index < source.length && !source.startsWith(BLOCK_END, index)) step()
        if (index < source.length) {
            step()
            step()
        } else {
            problems += ScanProblem(start, "未闭合的块注释")
        }
    }

    private fun string() {
        val start = line
        val text = StringBuilder()
        step()
        var state = OPEN
        while (state == OPEN) state = stringStep(text, textBlock = false)
        if (text.isNotEmpty()) strings += StringLiteral(start, text.toString())
        if (state != CLOSED) problems += ScanProblem(start, "未闭合的字符串")
    }

    /** 文本块：`"""` 后只能有空白再换行；内容里反斜杠转义下一个字符；第一个未转义的 `"""` 结束。 */
    private fun textBlock() {
        val start = line
        repeat(TEXT_BLOCK.length) { step() }
        while (source.getOrNull(index) in TEXT_BLOCK_BLANKS) step()
        if (source.getOrNull(index) != '\n') problems += ScanProblem(start, "文本块开头的三引号之后必须换行")
        val contentLine = line + if (source.getOrNull(index) == '\n') 1 else 0
        val text = StringBuilder()
        var state = OPEN
        while (state == OPEN) state = stringStep(text, textBlock = true)
        if (text.isNotEmpty()) strings += StringLiteral(contentLine, text.toString().removePrefix("\n"))
        if (state != CLOSED) problems += ScanProblem(start, "未闭合的文本块")
    }

    /** 字符串 / 文本块里的下一个单元：返回 [OPEN]（继续）、[CLOSED]（遇到结束引号）、[UNTERMINATED]（文件结束；普通字符串遇换行）。 */
    private fun stringStep(
        text: StringBuilder,
        textBlock: Boolean,
    ): Int {
        val ch = source.getOrNull(index)
        val escaped = ch == '\\' && index + 1 < source.length && (textBlock || source[index + 1] != '\n')
        return when {
            ch == null || (!textBlock && ch == '\n') -> {
                UNTERMINATED
            }

            textBlock && source.startsWith(TEXT_BLOCK, index) -> {
                repeat(TEXT_BLOCK.length) { step() }
                CLOSED
            }

            !textBlock && ch == '"' -> {
                step()
                CLOSED
            }

            escaped -> {
                text.append(source, index, index + 2)
                step()
                step()
                OPEN
            }

            else -> {
                text.append(ch)
                step()
                OPEN
            }
        }
    }

    private fun charLiteral() {
        val start = line
        step()
        var guard = 0
        while (guard < MAX_CHAR_LITERAL && source.getOrNull(index) !in CHAR_LITERAL_STOPS) {
            val escape = source[index] == '\\' && source.getOrNull(index + 1) !in LINE_END_STOPS
            if (escape) step()
            step()
            guard++
        }
        if (source.getOrNull(index) == '\'') step() else problems += ScanProblem(start, "未闭合的字符字面量")
    }

    private fun step(keepCode: Boolean = false) {
        val ch = source[index++]
        code.append(if (keepCode || ch == '\n') ch else ' ')
        if (ch == '\n') line++
    }

    private companion object {
        const val TEXT_BLOCK = "\"\"\""
        const val BLOCK_START = "/" + "*"
        const val BLOCK_END = "*" + "/"
        const val MAX_CHAR_LITERAL = 8
        const val OPEN = 0
        const val CLOSED = 1
        const val UNTERMINATED = 2
        val TEXT_BLOCK_BLANKS = setOf(' ', '\t', '\u000c', '\r')
        val CHAR_LITERAL_STOPS = setOf(null, '\'', '\n')
        val LINE_END_STOPS = setOf(null, '\n')
    }
}
