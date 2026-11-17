package com.couli.core.network

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** D10-1966 C（沿用 D10-1955 白名单约束）：仅做源码结构守卫；入口是否真的能被屏障拒绝仍由行为测试和评审核验。 */
class EpochReadSingleEntryStaticTest {
    @Test
    fun epochMemberReadsMustBelongToReviewedFunctions() {
        // Gradle Test 的工作目录是 core/network；不向上猜目录，避免扫错树或空跑绿。
        val module = File(".").canonicalFile
        val root = File(module, "src/main")
        assertTrue("找不到 core/network 源码根：${root.path}；请从模块工作目录运行", root.isDirectory)
        val files =
            root
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .sortedBy { it.path }
                .toList()
        assertTrue("源码根没有 Kotlin 文件：${root.path}", files.isNotEmpty())
        val storageTypes = storageTypes(files.map { it.readText() })
        val sources =
            files.associate { file ->
                val path = file.relativeTo(module).invariantSeparatorsPath
                val scan = runCatching { inspect(file.readText(), storageTypes) }.getOrElse { throw AssertionError("$path 扫描失败", it) }
                path to scan
            }
        val allowlist = File(module, "src/test/resources/epoch-read-allowlist.txt")
        assertTrue("缺少白名单：${allowlist.path}", allowlist.isFile)
        val problems = audit(sources, allowlist.readLines())
        assertTrue("D10-1966 会话存储世代读取未收口：\n${problems.joinToString("\n")}", problems.isEmpty())
    }

    @Test
    fun scannerKeepsRealReadsAndTheirOutermostFunction() {
        val source =
            """
            interface SessionTokens { val loginEpoch: Long; val generation: Long }
            fun block(tokens: SessionTokens) {
                // tokens.loginEpoch
                /* tokens.generation /* nested */ tokens.loginEpoch */
                val text = "tokens.generation \" still text"
                val raw = RAWtokens.loginEpochRAW
                val character = '"'
                val generation = 1
                fun nested() = tokens /* gap */ ?. `loginEpoch`
                run { tokens.generation += 1 }
                tokens.generation = generation
            }
            fun expression(tokens: SessionTokens) =
                run { tokens.generation }
                    .also { tokens.loginEpoch }
            val outside = MemorySessionTokens().generation
            val property: Long get() = MemorySessionTokens().generation
            fun declarationOnly(generation: Long) = generation
            """.trimIndent().replace("RAW", "\"\"\"")
        val scan = inspect(source, setOf("SessionTokens", "MemorySessionTokens"))
        assertEquals(listOf("block", "block", "expression", "expression", NO_FUNCTION, "get:property"), scan.reads.map { it.function })
        assertEquals(listOf(9, 10, 14, 15, 16, 17), scan.reads.map { it.line })
    }

    @Test
    fun stringTemplatesRemainExecutableCode() {
        val source = "fun template(tokens: SessionTokens) = \"literal tokens.generation ${'$'}{run { tokens.loginEpoch }}\""
        assertEquals(listOf("template"), inspect(source).reads.map { it.function })
        assertEquals(emptyList<Read>(), inspect("fun text() = \"tokens.loginEpoch\"").reads)
    }

    @Test
    fun allowlistRejectsMissingReasonsStaleRowsAndExcessEntries() {
        val path = "src/main/Sample.kt"
        val sources = mapOf(path to inspect("fun entry(tokens: SessionTokens) = tokens.generation\nfun idle() = 0"))
        assertTrue(audit(sources, listOf("$path|entry|屏障内统一读取")).isEmpty())
        assertTrue(audit(sources, emptyList()).any { "$path:1 [entry]" in it })
        assertTrue(audit(sources, listOf("$path|entry| ")).any { "理由为空" in it })
        assertTrue(audit(sources, listOf("src/main/Missing.kt|entry|理由")).any { "文件不存在" in it })
        assertTrue(audit(sources, listOf("$path|missing|理由")).any { "函数不存在" in it })
        assertTrue(audit(sources, listOf("$path|idle|理由")).any { "已无读取" in it })
        assertTrue(audit(sources, List(MAX_ENTRIES + 1) { "$path|entry|理由" }).any { "超过上限" in it })
        assertTrue(audit(sources, List(2) { "$path|entry|理由" }).any { "重复条目" in it })
        assertTrue(audit(sources, listOf("$path|entry")).any { "格式错误" in it })
        val outside = mapOf(path to inspect("val tokens: SessionTokens = create()\nval outside = tokens.generation"))
        assertTrue(audit(outside, listOf("$path|$NO_FUNCTION|理由")).any { "函数不存在" in it })
    }

    @Test
    fun receiverTypesDistinguishStorageAliasesSnapshotsAndShadowing() {
        val source =
            """
            class Holder(private val injected: SessionTokens) {
                fun entry(snapshot: SessionSnapshot, identity: SessionIdentity) {
                    val renamed = injected
                    renamed.generation
                    this.injected.loginEpoch
                    (injected as SessionTokens).generation
                    snapshot.generation
                    identity.loginEpoch
                    fun nested(injected: SessionSnapshot) = injected.generation
                    injected.loginEpoch
                }
                fun factory(): SessionTokens = injected
                fun fromFactory() = factory().generation
                fun snapshotFactory(): SessionSnapshot = makeSnapshot()
                fun fromSnapshot() = snapshotFactory().generation
            }
            fun unrelated(injected: Other, tokens: SessionIdentity) {
                injected.generation
                tokens.loginEpoch
            }
            """.trimIndent()
        val scan = inspect(source)
        assertEquals(listOf("entry", "entry", "entry", "entry", "fromFactory"), scan.reads.map { it.function })
        assertEquals(listOf(4, 5, 6, 10, 13), scan.reads.map { it.line })
    }

    @Test
    fun storageImplementationStateIsExcludedButOtherStorageReadsStillCount() {
        val source =
            """
            class MemorySessionTokens(private val peer: SessionTokens) : SessionTokens {
                private var current: SessionSnapshot = initial()
                override val generation: Long get() = current.generation
                fun save() { current = copy(current.generation + 1) }
                fun compare() = peer.generation + this.generation
            }
            class Client(private val tokens: SessionTokens) {
                fun entry() = tokens.generation + tokens.loginEpoch
            }
            """.trimIndent()
        val scan = inspect(source)
        assertEquals(listOf("compare", "compare", "entry", "entry"), scan.reads.map { it.function })
        assertEquals(listOf(5, 5, 8, 8), scan.reads.map { it.line })
        val path = "src/main/Memory.kt"
        assertTrue(audit(mapOf(path to scan), listOf("$path|save|旧的内部快照例外")).any { "已无读取" in it })
    }

    @Test
    fun storageSubtypesAreCollectedAcrossFilesWithoutNameHeuristics() {
        val declarations =
            """
            interface CustomStore : SessionTokens {}
            class RenamedStore : CustomStore {}
            class PretendSessionTokens { val generation: Long = 0 }
            class GenericHolder : Box<SessionTokens> {}
            """.trimIndent()
        val usage =
            """
            fun concrete(storage: RenamedStore) = storage.generation
            fun constructed() = RenamedStore().loginEpoch
            fun unrelated(storage: PretendSessionTokens) = storage.generation
            fun generic(storage: GenericHolder) = storage.generation
            """.trimIndent()
        val scan = inspect(usage, storageTypes(listOf(declarations, usage)))
        assertEquals(listOf("concrete", "constructed"), scan.reads.map { it.function })
    }

    private fun audit(
        sources: Map<String, Scan>,
        lines: List<String>,
    ): List<String> {
        val errors = mutableListOf<String>()
        val entries =
            lines.mapIndexedNotNull { index, line ->
                val entry = line.trim()
                if (entry.isEmpty() || entry.startsWith('#')) null else (index + 1) to entry
            }
        if (entries.size > MAX_ENTRIES) errors += "白名单 ${entries.size} 条，超过上限 $MAX_ENTRIES；请收口读取路径"
        val allowed = mutableSetOf<Pair<String, String>>()
        for ((line, text) in entries) {
            auditEntry(sources, line, text, allowed, errors)
        }
        for ((path, scan) in sources) {
            for (read in scan.reads) {
                if (path to read.function !in allowed) errors += "$path:${read.line} [${read.function}] ${read.member}"
            }
        }
        return errors
    }

    private fun auditEntry(
        sources: Map<String, Scan>,
        line: Int,
        text: String,
        allowed: MutableSet<Pair<String, String>>,
        errors: MutableList<String>,
    ) {
        val parts = text.split('|').map(String::trim)
        if (parts.size != 3) {
            errors += "白名单:$line 格式错误：必须为 相对路径|所在函数名|理由"
            return
        }
        val (path, function, reason) = parts
        if (reason.isBlank()) errors += "白名单:$line 理由为空"
        val key = path to function
        if (!allowed.add(key)) errors += "白名单:$line 重复条目：$path [$function]"
        val scan = sources[path]
        when {
            scan == null -> errors += "白名单:$line 文件不存在或不在 src/main 的 Kotlin 扫描范围：$path"
            function !in scan.functions -> errors += "白名单:$line 函数不存在：$path [$function]"
            scan.reads.none { it.function == function } -> errors += "白名单:$line 函数已无读取：$path [$function]"
        }
    }

    private data class Read(
        val line: Int,
        val function: String,
        val member: String,
    )

    private data class Scan(
        val functions: Set<String>,
        val reads: List<Read>,
    )

    private data class Token(
        val text: String,
        val line: Int,
    )

    private data class Function(
        val name: String,
        val start: Int,
        val end: Int,
    )

    private fun inspect(
        source: String,
        storageTypes: Set<String> = storageTypes(listOf(source)),
    ): Scan {
        val tokens = Lexer(source).scan()
        val pairs = matchingDelimiters(tokens)
        val functions = functions(tokens, pairs)
        val receivers = StorageReceivers(tokens, pairs, functions, storageTypes)
        val reads =
            tokens.indices.mapNotNull { index ->
                val token = tokens[index]
                // 只收 SessionTokens 及其实现实例的 getter；快照字段与存储自身 backing state 不算。
                // 声明及同名局部变量没有成员运算符；纯赋值不是读取，复合赋值 / ++ / -- 仍是读取。
                if (isNonRead(tokens, index) || !receivers.isStorage(index - 2)) {
                    null
                } else {
                    val outer = functions.filter { index in it.start..it.end }.minByOrNull { it.start }
                    Read(token.line, outer?.name ?: NO_FUNCTION, token.text)
                }
            }
        return Scan(functions.map { it.name }.toSet(), reads)
    }

    private fun isNonRead(
        tokens: List<Token>,
        index: Int,
    ): Boolean =
        tokens[index].text !in MEMBERS || tokens.getOrNull(index - 1)?.text !in setOf(".", "?.", "::") ||
            tokens.getOrNull(index + 1)?.text == "="

    private data class ClassScope(
        val name: String,
        val start: Int,
        val end: Int,
        val parents: Set<String>,
    )

    private fun classes(
        tokens: List<Token>,
        pairs: Map<Int, Int>,
    ): List<ClassScope> =
        tokens.indices.mapNotNull { start ->
            if (tokens[start].text !in setOf("class", "interface", "object")) return@mapNotNull null
            val name = tokens.getOrNull(start + 1)?.text ?: return@mapNotNull null
            if (!name.first().isJavaIdentifierStart()) return@mapNotNull null
            classScope(tokens, pairs, start, name)
        }

    private fun classScope(
        tokens: List<Token>,
        pairs: Map<Int, Int>,
        start: Int,
        name: String,
    ): ClassScope {
        var cursor = start + 2
        var expectParent = false
        var typeDepth = 0
        val parents = mutableSetOf<String>()
        while (cursor < tokens.size && tokens[cursor].text !in setOf("{", "}", ";", "class", "interface", "fun", "val", "var")) {
            val text = tokens[cursor].text
            when {
                text == "<" -> {
                    typeDepth++
                }

                text == ">" -> {
                    typeDepth--
                }

                typeDepth == 0 && text in setOf(":", ",") -> {
                    expectParent = true
                }

                typeDepth == 0 && expectParent && text.first().isJavaIdentifierStart() -> {
                    var nameEnd = cursor
                    while (tokens.getOrNull(nameEnd + 1)?.text == ".") nameEnd += 2
                    parents += tokens[nameEnd].text
                    expectParent = false
                    cursor = nameEnd
                }
            }
            cursor = (pairs[cursor] ?: cursor) + 1
        }
        return ClassScope(name, start, pairs[cursor] ?: (cursor - 1), parents)
    }

    /** 全模块收集继承关系；不靠 tokens 变量名或类名后缀猜类型。 */
    private fun storageTypes(sources: List<String>): Set<String> {
        val declarations =
            sources.flatMap { source ->
                val tokens = Lexer(source).scan()
                classes(tokens, matchingDelimiters(tokens))
            }
        val result = mutableSetOf("SessionTokens")
        do {
            val added = declarations.filter { declaration -> declaration.parents.any { it in result } }.map { it.name }
            val changed = result.addAll(added)
        } while (changed)
        return result
    }

    private data class Binding(
        val name: String,
        val index: Int,
        val scope: IntRange,
        val type: String?,
    )

    /**
     * 有限的声明/接收者解析：参数、字段、局部 val 别名、构造器、显式返回类型、this、括号与强转。
     * 名称遮蔽按最内层作用域处理；快照只沿自己的类型解析，存储实现类不整类豁免。
     * 不是 Kotlin 编译器：隐式 lambda 接收者、泛型推断、跨文件工厂返回值仍须结构评审。
     */
    private inner class StorageReceivers(
        private val tokens: List<Token>,
        private val pairs: Map<Int, Int>,
        private val functions: List<Function>,
        private val storageTypes: Set<String>,
    ) {
        private val classes = classes(tokens, pairs)
        private val reverse = pairs.entries.associate { it.value to it.key }
        private val scopes =
            pairs.filterKeys { tokens[it].text == "{" }.map { it.key..it.value } +
                functions.map { it.start..it.end } + classes.map { it.start..it.end }
        private val bindings =
            tokens.indices.mapNotNull { index ->
                val typed = tokens.getOrNull(index + 1)?.text == ":"
                val variable = tokens.getOrNull(index - 1)?.text in setOf("val", "var")
                if ((!typed && !variable) || !tokens[index].text.first().isJavaIdentifierStart()) return@mapNotNull null
                val scope = scopes.filter { index in it }.minByOrNull { it.last - it.first } ?: tokens.indices
                Binding(tokens[index].text, index, scope, if (typed) typeName(index + 2) else null)
            }

        fun isStorage(end: Int): Boolean = typeAt(end, mutableSetOf()) in storageTypes

        private fun typeName(start: Int): String? {
            var end = start
            while (tokens.getOrNull(end + 1)?.text == ".") end += 2
            return tokens.getOrNull(end)?.text
        }

        private fun typeAt(
            end: Int,
            visiting: MutableSet<Int>,
        ): String? {
            if (end !in tokens.indices || !visiting.add(end)) return null
            try {
                return resolvedTypeAt(end, visiting)
            } finally {
                visiting.remove(end)
            }
        }

        private fun resolvedTypeAt(
            end: Int,
            visiting: MutableSet<Int>,
        ): String? =
            when (tokens[end].text) {
                "!!" -> typeAt(end - 1, visiting)
                "this" -> classes.filter { end in it.start..it.end }.minByOrNull { it.end - it.start }?.name
                ")" -> parenthesizedType(end, visiting)
                else -> bindingAt(end, visiting)?.let { bindingType(it, visiting) }
            }

        private fun parenthesizedType(
            end: Int,
            visiting: MutableSet<Int>,
        ): String? {
            val open = reverse.getValue(end)
            val callable = tokens.getOrNull(open - 1)?.takeIf { it.line == tokens[open].line }?.text
            if (callable in storageTypes) return callable
            val returnStart = callableReturnStart(callable)
            return if (returnStart != null) typeName(returnStart) else groupedType(end, open, callable, visiting)
        }

        private fun groupedType(
            end: Int,
            open: Int,
            callable: String?,
            visiting: MutableSet<Int>,
        ): String? {
            // 分组/强转表达式；函数实参不是函数返回值，不能把 snapshot(tokens) 当作存储。
            if (callable != null && (callable.first().isJavaIdentifierPart() || callable == ")")) return null
            val cast = (open + 1 until end).lastOrNull { tokens[it].text == "as" }
            return if (cast != null) {
                typeName(cast + if (tokens.getOrNull(cast + 1)?.text == "?") 2 else 1)
            } else {
                typeAt(end - 1, visiting)
            }
        }

        private fun callableReturnStart(callable: String?): Int? {
            val function = functions.firstOrNull { it.name == callable }
            if (function != null) {
                val params = (function.start..function.end).firstOrNull { tokens[it].text == "(" }
                val close = params?.let(pairs::get)
                if (close != null && tokens.getOrNull(close + 1)?.text == ":") return close + 2
            }
            return null
        }

        private fun bindingAt(
            end: Int,
            visiting: MutableSet<Int>,
        ): Binding? {
            val text = tokens[end].text
            val qualified = tokens.getOrNull(end - 1)?.text in setOf(".", "?.")
            val receiverType = if (qualified) typeAt(end - 2, visiting) else null
            val owner = classes.firstOrNull { it.name == receiverType }
            return bindings
                .filter { candidate ->
                    candidate.name == text &&
                        if (qualified) {
                            owner != null && candidate.scope.last == owner.end && candidate.scope.first >= owner.start
                        } else {
                            end in candidate.scope && (candidate.index < end || classes.any { candidate.scope == it.start..it.end })
                        }
                }.sortedWith(compareBy<Binding> { it.scope.last - it.scope.first }.thenByDescending { it.index })
                .firstOrNull()
        }

        private fun bindingType(
            binding: Binding,
            visiting: MutableSet<Int>,
        ): String? =
            when {
                binding.type != null -> binding.type
                tokens.getOrNull(binding.index + 1)?.text != "=" -> null
                else -> initializerType(binding, visiting)
            }

        private fun initializerType(
            binding: Binding,
            visiting: MutableSet<Int>,
        ): String? {
            var valueEnd = binding.index + 2
            while (valueEnd < tokens.size) {
                val next = (pairs[valueEnd] ?: valueEnd) + 1
                if (next >= tokens.size || tokens[next].line > tokens[valueEnd].line ||
                    tokens[next].text in setOf(";", "}", ",")
                ) {
                    return typeAt(next - 1, visiting)
                }
                valueEnd = next
            }
            return null
        }
    }

    private fun matchingDelimiters(tokens: List<Token>): Map<Int, Int> {
        val stack = mutableListOf<Int>()
        val pairs = mutableMapOf<Int, Int>()
        val closing = mapOf(")" to "(", "]" to "[", "}" to "{")
        for ((index, token) in tokens.withIndex()) {
            if (token.text in closing.values) stack += index
            if (token.text in closing) {
                check(stack.isNotEmpty() && tokens[stack.last()].text == closing[token.text]) {
                    "无法解析第 ${token.line} 行的括号；静态扫描不能跳过此文件"
                }
                pairs[stack.removeAt(stack.lastIndex)] = index
            }
        }
        check(stack.isEmpty()) { "源码括号未闭合；静态扫描不能空跑" }
        return pairs
    }

    private fun functions(
        tokens: List<Token>,
        pairs: Map<Int, Int>,
    ): List<Function> {
        val result = mutableListOf<Function>()
        for ((start, token) in tokens.withIndex()) {
            functionAt(tokens, pairs, start, token)?.let { result += it }
        }
        return result
    }

    private fun functionAt(
        tokens: List<Token>,
        pairs: Map<Int, Int>,
        start: Int,
        token: Token,
    ): Function? {
        val accessor = token.text in setOf("get", "set") && tokens.getOrNull(start + 1)?.text == "("
        if (!isFunctionStart(tokens, start, token, accessor)) return null
        val open = parameterOpen(tokens, start)
        return open?.let { functionName(tokens, start, token, accessor, it)?.let { name -> functionBody(tokens, pairs, start, it, name) } }
    }

    private fun isFunctionStart(
        tokens: List<Token>,
        start: Int,
        token: Token,
        accessor: Boolean,
    ): Boolean = (token.text == "fun" || accessor) && !(accessor && tokens.getOrNull(start - 1)?.text in setOf(".", "?.", "::", "fun"))

    private fun parameterOpen(
        tokens: List<Token>,
        start: Int,
    ): Int? {
        val open = (start + 1 until tokens.size).firstOrNull { tokens[it].text in setOf("(", "{", "=", ";", "}") } ?: return null
        return if (tokens[open].text == "(") open else null
    }

    private fun functionBody(
        tokens: List<Token>,
        pairs: Map<Int, Int>,
        start: Int,
        open: Int,
        name: String,
    ): Function {
        var body = checkNotNull(pairs[open]) + 1
        while (body < tokens.size && tokens[body].text !in BODY_STOPS) body = (pairs[body] ?: body) + 1
        val end =
            when (tokens.getOrNull(body)?.text) {
                "{" -> checkNotNull(pairs[body])
                "=" -> expressionEnd(tokens, pairs, body + 1)
                else -> body - 1 // 抽象声明也确实是函数，但不能放行下一函数里的读取。
            }
        return Function(name, start, end)
    }

    private fun functionName(
        tokens: List<Token>,
        start: Int,
        token: Token,
        accessor: Boolean,
        open: Int,
    ): String? =
        if (accessor) {
            accessorName(tokens, start, token)
        } else {
            // 匿名函数归最外层具名函数。
            if (open == start + 1) null else tokens[open - 1].text
        }

    private fun accessorName(
        tokens: List<Token>,
        start: Int,
        token: Token,
    ): String? {
        val property = (start - 1 downTo 0).firstOrNull { tokens[it].text in setOf("val", "var", "fun", "}") }
        if (property == null || tokens[property].text !in setOf("val", "var")) return null
        return "${token.text}:${tokens[property + 1].text}"
    }

    private fun expressionEnd(
        tokens: List<Token>,
        pairs: Map<Int, Int>,
        start: Int,
    ): Int {
        var index = start
        while (index < tokens.size) {
            val boundary = expressionBoundary(tokens, pairs, start, index)
            if (boundary != null) return boundary
            index = (pairs[index] ?: index) + 1
        }
        return tokens.lastIndex
    }

    private fun expressionBoundary(
        tokens: List<Token>,
        pairs: Map<Int, Int>,
        start: Int,
        index: Int,
    ): Int? {
        val text = tokens[index].text
        if (isExpressionStop(text, index, start)) return index - 1
        val end = pairs[index] ?: index
        val next = end + 1
        // 表达式体在完整表达式后的换行结束；点链、二元运算和 if/try 的续段仍属原函数。
        val continuations =
            setOf(".", "?.", "::", "?:", "+", "-", "*", "/", "%", "&&", "||", "==", "!=", "=", "->", "else", "catch", "finally")
        return if (endsAtNewline(tokens, end, next, continuations)) end else null
    }

    private fun isExpressionStop(
        text: String,
        index: Int,
        start: Int,
    ): Boolean = text == "}" || text == ";" || (index > start && text in DECLARATIONS)

    private fun endsAtNewline(
        tokens: List<Token>,
        end: Int,
        next: Int,
        continuations: Set<String>,
    ): Boolean = next < tokens.size && tokens[next].line > tokens[end].line && isNotContinuation(tokens, end, next, continuations)

    private fun isNotContinuation(
        tokens: List<Token>,
        end: Int,
        next: Int,
        continuations: Set<String>,
    ): Boolean = tokens[end].text !in continuations && tokens[next].text !in continuations

    /** 小型词法器：注释与字面量不产 token；保留原行号，模板中的可执行表达式递归扫描。 */
    private class Lexer(
        private val source: String,
    ) {
        private val tokens = mutableListOf<Token>()
        private var index = 0
        private var line = 1

        fun scan(): List<Token> {
            code(template = false)
            return tokens
        }

        private fun step(count: Int = 1) {
            repeat(count) {
                if (source[index] == '\n') line++
                index++
            }
        }

        private fun code(template: Boolean) {
            var braces = 0
            while (index < source.length) {
                val char = source[index]
                if (template && char == '}' && braces == 0) return
                if (!consumeNonOperator(char)) {
                    // 最长匹配，不能把 == 当作纯赋值，也不能漏掉安全调用 / 可调用引用。
                    val operator = OPERATORS.firstOrNull { source.startsWith(it, index) } ?: char.toString()
                    tokens += Token(operator, line)
                    if (char == '{') braces++
                    if (char == '}') braces--
                    step(operator.length)
                }
            }
            check(!template) { "第 $line 行字符串模板未闭合" }
        }

        private fun consumeNonOperator(char: Char): Boolean =
            when {
                char.isWhitespace() -> {
                    step()
                    true
                }

                source.startsWith("//", index) -> {
                    while (index < source.length && source[index] != '\n') step()
                    true
                }

                source.startsWith("/*", index) -> {
                    comment()
                    true
                }

                char == '"' -> {
                    string()
                    true
                }

                char == '\'' -> {
                    character()
                    true
                }

                char == '`' -> {
                    identifier(quoted = true)
                    true
                }

                char.isLetterOrDigit() || char == '_' -> {
                    identifier(quoted = false)
                    true
                }

                else -> {
                    false
                }
            }

        private fun identifier(quoted: Boolean) {
            val startLine = line
            if (quoted) step()
            val start = index
            while (index < source.length &&
                if (quoted) source[index] != '`' else source[index].isLetterOrDigit() || source[index] == '_'
            ) {
                step()
            }
            tokens += Token(source.substring(start, index), startLine)
            if (quoted) {
                check(index < source.length) { "反引号标识符未闭合" }
                step()
            }
        }

        private fun comment() {
            step(2)
            var depth = 1
            while (index < source.length && depth > 0) {
                when {
                    source.startsWith("/*", index) -> {
                        depth++
                        step(2)
                    }

                    source.startsWith("*/", index) -> {
                        depth--
                        step(2)
                    }

                    else -> {
                        step()
                    }
                }
            }
            check(depth == 0) { "块注释未闭合" }
        }

        private fun character() {
            step()
            while (index < source.length && source[index] != '\'') {
                if (source[index] == '\\') step()
                check(index < source.length) { "字符转义未闭合" }
                step()
            }
            check(index < source.length) { "字符字面量未闭合" }
            step()
        }

        private fun string() {
            val quote = if (source.startsWith("\"\"\"", index)) "\"\"\"" else "\""
            step(quote.length)
            while (index < source.length) {
                when {
                    source.startsWith(quote, index) -> {
                        step(quote.length)
                        return
                    }

                    quote.length == 1 && source[index] == '\\' -> {
                        step()
                        check(index < source.length) { "字符串转义未闭合" }
                        step()
                    }

                    source.startsWith("${'$'}{", index) -> {
                        step()
                        tokens += Token("{", line)
                        step()
                        code(template = true)
                        tokens += Token("}", line)
                        step()
                    }

                    else -> {
                        step()
                    }
                }
            }
            error("字符串字面量未闭合")
        }
    }

    private companion object {
        const val MAX_ENTRIES = 12
        const val NO_FUNCTION = "<无外层函数>"
        val MEMBERS = setOf("loginEpoch", "generation")
        val DECLARATIONS = setOf("fun", "val", "var", "class", "interface", "object", "init", "constructor", "get", "set")
        val BODY_STOPS = DECLARATIONS + setOf("{", "=", ";", "}")
        val OPERATORS = listOf("===", "!==", "==", "!=", "?.", "::", "+=", "-=", "*=", "/=", "%=", "++", "--", "->")
    }
}
