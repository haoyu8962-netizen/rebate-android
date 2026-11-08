package com.couli.buildlogic

import java.io.File

/**
 * LINT-STYLE（D10-643 ④；三端统一口径 D10-734）：页面层禁写死样式。规则名与 web 对齐：
 * - `no-hex-color`：颜色字面量——`Color(0x…)` / 十进制 Long / 按通道 `Color(r, g, b[, a])`（位置或命名，任一通道是数字字面量）、
 *   `parseColor("#…")` / `"#…".toColorInt()`；外层再包一层 `Color(…)` 不重复计；`Color.Transparent`、令牌、运行时值不计；
 * - `no-numeric-style`：数字样式字面量——
 *   - 字号：`N.sp` / `N.em`（含 Float、小数；声明处也算）、`TextUnit(N, …)`；
 *   - 圆角：`RoundedCornerShape` / `CutCornerShape` / `AbsoluteRoundedCornerShape` / `AbsoluteCutCornerShape` / `CornerSize` 实参里的
 *     非零数字字面量（Int 百分比、Float px、`N.dp`、`Dp(N)`），每个计一次；
 *   - 间距：只在间距用途里拦 `N.dp` / `Dp(N)`（padding 一族、`PaddingValues`、`spacedBy`、`Spacer` 的宽高、
 *     名为 `padding` / `gap` / `spacing` 或以 `Padding` / `Gap` / `Spacing` 结尾的命名实参）；
 *     宽高、描边、dp 常量声明不拦（只拦字面量，常量追踪另立 FU）；
 *   - 零值不计；同一字面量只计一次（按源码偏移去重，不跨规则重复）。
 * - `exemption-reason`：行尾豁免 `// couli-style-disable-line couli-style/<规则>[, …] -- 原因` 只豁免本行指定规则；
 *   没有 `--` 或原因空白（以及格式不对）报 exemption-reason，原违规照报。G1（`couli-style/design-system-required`）同样可用。
 *
 * 范围：`feature/<模块>/src/{main,release}/` 与 `app/src/{main,release}/` 之下（任意深度）的 `.kt`（源码集里的任何目录名都扫，见 [CouliSourceRules]）；
 * 测试、staging、debug、debugFeatures 源码集不进失败范围。
 *
 * 基线（[BASELINE_PATH]，扫描根下）：`{ "couli-style/<规则>": { "相对路径": 计数 } }`，规则 × 文件计数。计数变多、变少（含文件消失）都失败，
 * 变少须同步降基线；不能跨文件或跨规则挪用额度。`exemption-reason` 不进基线、永远失败。
 * 生成：`./gradlew couliRules -Pcouli.rules.writeBaseline=true`（按扫描根现状重写，逐字节稳定）。
 */
@Suppress("TooManyFunctions") // 豁免、分析、计数、渲染、读基线、比对各一个入口
internal object HardcodedStyleGuard {
    const val HEX = "no-hex-color"
    const val NUMERIC = "no-numeric-style"
    const val EXEMPTION = "exemption-reason"
    const val PREFIX = "couli-style/"
    const val BASELINE_PATH = "tools/ci/hardcoded-style-baseline.json"
    private const val BASELINE_MARKER = "[style-baseline]"
    private const val DISABLE = "couli-style-disable-line"

    /** 进基线的两条规则（JSON 键为 `couli-style/<规则>`）。 */
    val BASELINE_RULES = listOf(HEX, NUMERIC)

    private val PAGE_SOURCE_SET = Regex("""^(?:feature/[^/]+|app)/src/(?:main|release)/""")

    /** 相对扫描根的路径是否在页面源码集（feature/<模块> 或 app 的 main / release）之内（目录或文件）。 */
    fun inPageSourceSet(relative: String): Boolean = PAGE_SOURCE_SET.containsMatchIn(relative)

    /** 样式规则是否管这个文件：页面源码集里的 Kotlin 源码。 */
    fun applies(relative: String): Boolean = inPageSourceSet(relative) && relative.endsWith(".kt")

    /** 一处命中：行号、规则、说明。 */
    data class Finding(
        val line: Int,
        val rule: String,
        val detail: String,
    )

    /** 一个文件的结果：豁免后仍计数的命中、豁免注释本身的问题（exemption-reason）、各行有效豁免的规则。 */
    class FileResult(
        val findings: List<Finding>,
        val exemptionProblems: List<Finding>,
        val exempted: Map<Int, Set<String>>,
    )

    /** 只解析豁免注释（G1 范围但不在样式范围的文件用）。 */
    fun exemptions(scanned: ScannedSource): FileResult {
        val (exempted, problems) = parseExemptions(scanned.lineComments)
        return FileResult(emptyList(), problems, exempted)
    }

    /** 分析一个页面文件：[text] 是原文（与 [ScannedSource.code] 在统一换行后逐字符对齐）。 */
    fun analyze(
        text: String,
        scanned: ScannedSource,
    ): FileResult {
        val (exempted, problems) = parseExemptions(scanned.lineComments)
        val raw = StyleScan(normalizeLineTerminators(text), scanned.code).findings()
        val kept = raw.filterNot { PREFIX + it.rule in exempted[it.line].orEmpty() }
        return FileResult(kept, problems, exempted)
    }

    // ---------------------------------------------------------------- 豁免注释

    private val EXEMPTION_SYNTAX =
        Regex("""^//+\s*$DISABLE\s+($PREFIX[\w-]+(?:\s*,\s*$PREFIX[\w-]+)*)\s*(?:--(.*))?$""")

    private fun parseExemptions(comments: List<StringLiteral>): Pair<Map<Int, Set<String>>, List<Finding>> {
        val exempted = mutableMapOf<Int, MutableSet<String>>()
        val problems = mutableListOf<Finding>()
        comments.filter { DISABLE in it.text }.forEach { comment ->
            val match = EXEMPTION_SYNTAX.matchEntire(comment.text.trimEnd())
            val reason = match?.groups?.get(2)?.value
            when {
                match == null -> {
                    problems += Finding(comment.line, EXEMPTION, "豁免注释格式不对：应为 `// $DISABLE $PREFIX<规则> -- 原因`")
                }

                reason == null || reason.isBlank() -> {
                    problems += Finding(comment.line, EXEMPTION, "豁免注释必须在 `--` 后写明原因（原违规照报）")
                }

                else -> {
                    val rules = match.groupValues[1].split(',').map { it.trim() }
                    exempted.getOrPut(comment.line) { mutableSetOf() } += rules
                }
            }
        }
        return exempted to problems
    }

    // ---------------------------------------------------------------- 基线

    /** 规则 × 文件计数（只含非零项），规则与路径都排序。 */
    fun counts(results: Map<String, FileResult>): Map<String, Map<String, Int>> =
        BASELINE_RULES
            .associateWith { rule ->
                results
                    .mapValues { (_, result) -> result.findings.count { it.rule == rule } }
                    .filterValues { it > 0 }
                    .toSortedMap()
            }.filterValues { it.isNotEmpty() }
            .mapKeys { PREFIX + it.key }
            .toSortedMap()

    /** 与 Python `json.dumps(…, ensure_ascii=False, sort_keys=True, indent=2) + "\n"` 逐字节相同。 */
    fun render(counts: Map<String, Map<String, Int>>): String {
        if (counts.isEmpty()) return "{}\n"
        val rules =
            counts.toSortedMap().entries.joinToString(",\n") { (rule, files) ->
                val body = files.toSortedMap().entries.joinToString(",\n") { (path, count) -> "    ${quote(path)}: $count" }
                "  ${quote(rule)}: {\n$body\n  }"
            }
        return "{\n$rules\n}\n"
    }

    private fun quote(text: String): String =
        buildString {
            append('"')
            text.forEach { ch ->
                when {
                    ch == '"' -> append("\\\"")
                    ch == '\\' -> append("\\\\")
                    ch == '\n' -> append("\\n")
                    ch == '\r' -> append("\\r")
                    ch == '\t' -> append("\\t")
                    ch < ' ' -> append("\\u%04x".format(ch.code))
                    else -> append(ch)
                }
            }
            append('"')
        }

    /** 读扫描根下的基线：不存在 = 空；格式不对返回诊断（失败）。 */
    @Suppress("ReturnCount") // 不存在、读不出、解析三个出口
    fun readBaseline(root: File): Pair<Map<String, Map<String, Int>>, List<String>> {
        val file = File(root, BASELINE_PATH)
        if (!file.exists()) return emptyMap<String, Map<String, Int>>() to emptyList()
        val text =
            runCatching { file.readText() }.getOrElse { error ->
                return emptyMap<String, Map<String, Int>>() to listOf("$BASELINE_PATH: $BASELINE_MARKER 读不出基线（${error.message}）")
            }
        val parsed = runCatching { BaselineParser(text).parse() }
        val problem = parsed.exceptionOrNull()?.let { "$BASELINE_PATH: $BASELINE_MARKER 基线格式不对（${it.message}）" }
        return parsed.getOrDefault(emptyMap()) to listOfNotNull(problem)
    }

    /**
     * 按规则 × 文件比对：相等不报；实际多于基线 → 该文件该规则的每处命中都报（`路径:行: [规则] …`）；实际少于基线（含文件消失）→
     * 一条「须同步降基线」（`路径: [规则] …`）。
     */
    fun compare(
        results: Map<String, FileResult>,
        baseline: Map<String, Map<String, Int>>,
    ): List<String> {
        val actual = counts(results)
        return BASELINE_RULES.flatMap { rule ->
            val key = PREFIX + rule
            val have = actual[key].orEmpty()
            val allowed = baseline[key].orEmpty()
            (have.keys + allowed.keys).toSortedSet().flatMap { path ->
                mismatch(rule, path, have[path] ?: 0, allowed[path] ?: 0, results[path])
            }
        }
    }

    /** 一个规则 × 文件的比对结果（相等为空）。 */
    private fun mismatch(
        rule: String,
        path: String,
        count: Int,
        limit: Int,
        result: FileResult?,
    ): List<String> =
        when {
            count > limit -> {
                result?.findings.orEmpty().filter { it.rule == rule }.map { finding ->
                    "$path:${finding.line}: [$rule] ${finding.detail}（本文件基线 $limit 处、实际 $count 处，新增即失败）"
                }
            }

            count < limit -> {
                listOf("$path: [$rule] 本文件基线 $limit 处、实际 $count 处：须同步降低 $BASELINE_PATH（只减不增，不留空头额度）")
            }

            else -> {
                emptyList()
            }
        }

    /** 极简 JSON 读取，只认基线格式：两层对象，值为正整数；规则键只能是 [BASELINE_RULES]。 */
    private class BaselineParser(
        private val text: String,
    ) {
        private var index = 0

        fun parse(): Map<String, Map<String, Int>> {
            val result = sortedMapOf<String, Map<String, Int>>()
            obj { rule ->
                require(rule.removePrefix(PREFIX) in BASELINE_RULES && rule.startsWith(PREFIX)) { "未知规则键 $rule" }
                val files = sortedMapOf<String, Int>()
                obj { path ->
                    val count = number()
                    require(count > 0) { "$rule / $path 的计数必须是正整数" }
                    require(files.put(path, count) == null) { "重复路径 $path" }
                }
                require(result.put(rule, files) == null) { "重复规则键 $rule" }
            }
            skip()
            require(index == text.length) { "第 $index 个字符之后还有多余内容" }
            return result
        }

        private fun obj(entry: (String) -> Unit) {
            expect('{')
            skip()
            if (peek() == '}') {
                index++
                return
            }
            var more = true
            while (more) {
                skip()
                val key = string()
                skip()
                expect(':')
                skip()
                entry(key)
                skip()
                val next = peek()
                require(next == ',' || next == '}') { "第 $index 个字符应为 , 或 }" }
                more = next == ','
                index++
            }
        }

        private fun string(): String {
            expect('"')
            val out = StringBuilder()
            var closed = false
            while (!closed) {
                val ch = text.getOrNull(index++) ?: error("字符串未闭合")
                when (ch) {
                    '"' -> closed = true
                    '\\' -> out.append(escape())
                    else -> out.append(ch)
                }
            }
            return out.toString()
        }

        private fun escape(): Char {
            val ch = text.getOrNull(index++) ?: error("转义未完")
            return when (ch) {
                '"', '\\', '/' -> {
                    ch
                }

                'n' -> {
                    '\n'
                }

                'r' -> {
                    '\r'
                }

                't' -> {
                    '\t'
                }

                'b' -> {
                    '\b'
                }

                'f' -> {
                    '\u000c'
                }

                'u' -> {
                    text
                        .substring(index, minOf(text.length, index + HEX_DIGITS))
                        .toInt(HEX_RADIX)
                        .toChar()
                        .also { index += HEX_DIGITS }
                }

                else -> {
                    error("无法识别的转义 \\$ch")
                }
            }
        }

        private fun number(): Int {
            val start = index
            while (peek()?.isDigit() == true) index++
            require(index > start) { "第 $start 个字符应为数字" }
            return text.substring(start, index).toInt()
        }

        private fun expect(ch: Char) {
            require(peek() == ch) { "第 $index 个字符应为 $ch" }
            index++
        }

        private fun peek(): Char? = text.getOrNull(index)

        private fun skip() {
            while (peek()?.let { it in WHITESPACE } == true) index++
        }

        private companion object {
            const val HEX_DIGITS = 4
            const val HEX_RADIX = 16
            const val WHITESPACE = " \n\r\t"
        }
    }
}

/**
 * 一个文件的样式字面量扫描。[code] 是注释、字符串已换成空格的源码（[ScannedSource.code]），[source] 是统一换行后的原文（二者逐字符对齐）：
 * 调用与实参在 [code] 上找（字符串里的括号、逗号不干扰），只有 `"#…"` 字符串字面量回到 [source] 看内容。
 */
@Suppress("TooManyFunctions") // 每类字面量一个入口，加调用 / 实参切分
private class StyleScan(
    private val source: String,
    private val code: String,
) {
    private val lineStarts =
        buildList<Int> {
            add(0)
            code.forEachIndexed { i, ch -> if (ch == '\n') add(i + 1) }
        }
    private val calls = findCalls()

    /** 数字样式字面量的起始偏移 → 说明（同一偏移只记一次）。 */
    private val numeric = sortedMapOf<Int, String>()

    /** 颜色字面量的偏移 → 说明。 */
    private val hex = sortedMapOf<Int, String>()

    fun findings(): List<HardcodedStyleGuard.Finding> {
        calls.forEach(::inspect)
        TEXT_UNIT_SUFFIX.findAll(code).forEach { match ->
            val literal = match.groups[1] ?: return@forEach
            if (!isZero(literal.value)) numeric.putIfAbsent(literal.range.first, "数字字号 `${match.value.trim()}`，改用字号令牌")
        }
        TO_COLOR_INT.findAll(source).forEach { match ->
            val dot = match.groups[2]?.range?.first ?: return@forEach
            if (code[dot] == '.' && code[match.range.first] == ' ') {
                hex.putIfAbsent(match.range.first, "颜色字面量 `${match.groups[1]?.value}`，改用颜色令牌")
            }
        }
        val numericFindings = numeric.map { (offset, detail) -> finding(offset, HardcodedStyleGuard.NUMERIC, detail) }
        val hexFindings = hex.map { (offset, detail) -> finding(offset, HardcodedStyleGuard.HEX, detail) }
        return (hexFindings + numericFindings).sortedWith(compareBy({ it.line }, { it.rule }))
    }

    private fun inspect(call: Call) {
        when (call.name) {
            in CORNER_CALLS -> {
                numbersIn(call.open + 1, call.close).forEach { (offset, value) ->
                    numeric.putIfAbsent(offset, "圆角数字字面量 `$value`（${call.name}），改用圆角令牌")
                }
            }

            in SPACING_CALLS -> {
                spacingDp(call.open + 1, call.close, call.name)
            }

            "Spacer" -> {
                spacer(call)
            }

            "TextUnit" -> {
                textUnit(call)
            }

            "Color" -> {
                color(call)
            }

            "parseColor" -> {
                parseColor(call)
            }
        }
        call.args.filter { it.name != null && PADDING_ARGUMENT.matches(it.name) }.forEach { spacingDp(it.start, it.end, "${it.name} =") }
    }

    /** `Spacer(…)` 里 width / height / size 的 dp 是留白。 */
    private fun spacer(call: Call) {
        calls
            .filter { it.open > call.open && it.close < call.close && it.name in SPACER_DIMENSIONS }
            .forEach { spacingDp(it.open + 1, it.close, "Spacer.${it.name}") }
    }

    /** `TextUnit(N, …)`：value 实参（命名或第一个位置实参）是非零数字字面量。 */
    private fun textUnit(call: Call) {
        val value = call.args.firstOrNull { it.name == "value" } ?: call.args.firstOrNull { it.name == null }
        val literal = value?.let { pureLiteral(it) }
        if (literal != null && !isZero(literal.second)) {
            numeric.putIfAbsent(literal.first, "数字字号 `TextUnit(${literal.second}, …)`，改用字号令牌")
        }
    }

    /** `Color(…)`：任一实参（colorSpace 除外）是数字字面量——0x… / 十进制 Long / 按通道。 */
    private fun color(call: Call) {
        val literal = call.args.filter { it.name != "colorSpace" }.firstNotNullOfOrNull { pureLiteral(it) }
        if (literal != null) hex.putIfAbsent(call.nameStart, "颜色字面量 `Color(…${literal.second}…)`，改用颜色令牌")
    }

    /** `parseColor("#…")`：唯一实参是 `"#…"` 字符串字面量（运行时值不拦）。 */
    private fun parseColor(call: Call) {
        val arg = call.args.singleOrNull()
        val text = arg?.let { source.substring(it.start, it.end).trim() }
        if (text != null && HEX_STRING.matches(text)) {
            hex.putIfAbsent(call.nameStart, "颜色字面量 `parseColor($text)`，改用颜色令牌")
        }
    }

    /** [from, to) 里的间距 dp 字面量：`N.dp` 与 `Dp(N)`，非零。 */
    private fun spacingDp(
        from: Int,
        to: Int,
        where: String,
    ) {
        val region = code.substring(from, to)
        (DP_SUFFIX.findAll(region) + DP_CONSTRUCTOR.findAll(region)).forEach { match ->
            val literal = match.groups[1] ?: return@forEach
            if (!isZero(literal.value)) {
                numeric.putIfAbsent(from + literal.range.first, "间距数字字面量 `${match.value.trim()}`（$where），改用间距令牌")
            }
        }
    }

    /** [from, to) 里所有非零数字字面量：（偏移，原文）。 */
    private fun numbersIn(
        from: Int,
        to: Int,
    ): List<Pair<Int, String>> =
        NUMBER
            .findAll(code.substring(from, to))
            .filterNot { isZero(it.value) }
            .map { from + it.range.first to it.value }
            .toList()

    /** 实参整个就是一个数字字面量（可带正负号）时返回（偏移，原文）。 */
    private fun pureLiteral(arg: Arg): Pair<Int, String>? =
        PURE_LITERAL
            .matchEntire(code.substring(arg.start, arg.end))
            ?.groups
            ?.get(1)
            ?.let { arg.start + it.range.first to it.value }

    private fun finding(
        offset: Int,
        rule: String,
        detail: String,
    ) = HardcodedStyleGuard.Finding(lineOf(offset), rule, detail)

    private fun lineOf(offset: Int): Int {
        val index = lineStarts.binarySearch(offset)
        return if (index >= 0) index + 1 else -index - 1
    }

    // ---------------------------------------------------------------- 调用与实参

    private class Arg(
        val name: String?,
        val start: Int,
        val end: Int,
    )

    private class Call(
        val name: String,
        val nameStart: Int,
        val open: Int,
        val close: Int,
        val args: List<Arg>,
    )

    private fun findCalls(): List<Call> =
        CALL
            .findAll(code)
            .filterNot { DECLARATION.containsMatchIn(code.substring(maxOf(0, it.range.first - DECLARATION_WINDOW), it.range.first)) }
            .map { match ->
                val open = match.range.last
                val close = closing(open)
                Call(match.groupValues[1], match.range.first, open, close, arguments(open, close))
            }.toList()

    /** 与 [open] 处的 `(` 配对的 `)`；没配上返回 code 长度（扫不完整由词法另报）。 */
    private fun closing(open: Int): Int {
        var depth = 0
        for (i in open until code.length) {
            val ch = code[i]
            if (ch in OPENERS) depth++
            if (ch in CLOSERS && --depth == 0) return i
        }
        return code.length
    }

    /** 顶层逗号切实参；`名字 = 值` 的值从 `=` 之后算起。 */
    private fun arguments(
        open: Int,
        close: Int,
    ): List<Arg> {
        val args = mutableListOf<Arg>()
        var depth = 0
        var start = open + 1
        for (i in open + 1..close) {
            val ch = if (i < code.length) code[i] else ')'
            when {
                ch in OPENERS -> {
                    depth++
                }

                ch in CLOSERS && depth > 0 -> {
                    depth--
                }

                (ch == ',' && depth == 0) || i == close -> {
                    argument(start, i)?.let { args += it }
                    start = i + 1
                }
            }
        }
        return args
    }

    @Suppress("ReturnCount") // 空实参、位置实参、命名实参
    private fun argument(
        start: Int,
        end: Int,
    ): Arg? {
        val text = code.substring(start, end)
        // 字符串字面量在 code 里是空格：实参是否为空按原文看（`parseColor("#…")` 的实参只有一个字符串）
        if (source.substring(start, end).isBlank()) return null
        val named = NAMED_ARGUMENT.find(text) ?: return Arg(null, start, end)
        return Arg(named.groupValues[1], start + named.range.last + 1, end)
    }

    private companion object {
        /** Kotlin 数字字面量（十六进制 / 二进制 / 十进制，可带下划线、小数、指数与 f / L / u 后缀）。 */
        const val NUM =
            """(?:0[xX][0-9a-fA-F_]+[uU]?L?|0[bB][01_]+[uU]?L?|\d[\d_]*(?:\.\d[\d_]*)?(?:[eE][+-]?\d+)?[fFL]?)"""

        val NUMBER = Regex("""(?<![\w.])$NUM(?!\w)""")
        val PURE_LITERAL = Regex("""\s*[-+]?\s*($NUM)\s*""")
        val TEXT_UNIT_SUFFIX = Regex("""(?<![\w.])($NUM)\s*\.\s*(?:sp|em)(?!\w)""")
        val DP_SUFFIX = Regex("""(?<![\w.])($NUM)\s*\.\s*dp(?!\w)""")
        val DP_CONSTRUCTOR = Regex("""(?<!\w)Dp\s*\(\s*[-+]?\s*($NUM)\s*\)""")
        val HEX_STRING = Regex(""""#[0-9A-Fa-f]{3,8}"""")
        val TO_COLOR_INT = Regex(""""(#[0-9A-Fa-f]{3,8})"\s*(\.)\s*toColorInt\s*\(""")
        val CALL = Regex("""(?<![\w$])([A-Za-z_]\w*)\s*\(""")
        val NAMED_ARGUMENT = Regex("""^\s*([A-Za-z_]\w*)\s*=(?!=)""")

        // 间距命名实参（整名匹配）：`padding` / `gap` / `spacing`，或以 `Padding` / `Gap` / `Spacing` 结尾；`letterSpacing` 只传 sp，不进 dp 统计。
        val PADDING_ARGUMENT = Regex("""padding|gap|spacing|\w*(?:Padding|Gap|Spacing)""")
        val DECLARATION = Regex("""\b(?:fun|class|interface|constructor)\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?$""")
        const val DECLARATION_WINDOW = 80
        const val OPENERS = "([{"
        const val CLOSERS = ")]}"

        val CORNER_CALLS =
            setOf("RoundedCornerShape", "CutCornerShape", "AbsoluteRoundedCornerShape", "AbsoluteCutCornerShape", "CornerSize")
        val SPACING_CALLS = setOf("padding", "paddingFromBaseline", "absolutePadding", "PaddingValues", "spacedBy")
        val SPACER_DIMENSIONS = setOf("width", "height", "size", "requiredWidth", "requiredHeight", "requiredSize")

        fun isZero(literal: String): Boolean {
            val text = literal.replace("_", "").trimEnd('L', 'u', 'U')
            return when {
                text.startsWith("0x", ignoreCase = true) -> text.drop(2).all { it == '0' }
                text.startsWith("0b", ignoreCase = true) -> text.drop(2).all { it == '0' }
                else -> text.trimEnd('f', 'F').toDoubleOrNull() == 0.0
            }
        }
    }
}
