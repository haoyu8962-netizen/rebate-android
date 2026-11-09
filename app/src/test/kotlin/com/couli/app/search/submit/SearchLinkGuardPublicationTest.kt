package com.couli.app.search.submit

import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 修复 r3 / 评审 r2 S1-2（测试 v6）：发送侧守卫的线程安全发布（结构断言，不跑产品流程）。识别票据与识别归属由 IO / 传输监视线程调用
 * （ApiClient 的写出前守卫），它们读的主线程状态必须经原子快照或 volatile 字段发布，不能直接读普通字段。
 *
 * - AppClipboard：`searchSubmitTicket` 返回的闭包（函数体里第一个 `return` 之后的源码）里出现的 AppClipboard 字段，必须是 final（如
 *   `published: AtomicReference<OwnerSnapshot>`、`api`、`sessionEpoch`）或 volatile；r2 的闭包直接读普通 `var screenGen`。
 * - SearchController：识别归属读的 `left`、`linkSerial` 若仍是字段，必须 volatile 或 java.util.concurrent.atomic 类型。
 *
 * 局限：JVM 上无法稳定复现可见性竞态，这里只钉结构。源码扫描按名字定位 `fun searchSubmitTicket(`（实现须保留这个入口名，闭包在第一个
 * `return` 之后）；闭包若改为调用别处的方法，只扫到调用处，被调方法里读的字段扫不到，需评审另核。字段是否 volatile 用反射读字节码标志，不看注释。
 */
class SearchLinkGuardPublicationTest {
    @Test
    fun searchSubmitTicketClosureReadsOnlyPublishedState() {
        val body = functionBody(source(APP_CLIPBOARD_PATH), "fun searchSubmitTicket(")
        val closure = body.substringAfter("return", missingDelimiterValue = body)
        val fields = fieldsOf(APP_CLIPBOARD)
        val unsafe =
            referencedNames(closure)
                .mapNotNull { fields[it] }
                .filterNot(::safelyPublished)
                .map { it.name }
                .distinct()
        assertTrue(
            "识别票据的闭包在 IO 线程上求值，不得直接读普通字段（须经 published 原子快照或 volatile）：$unsafe",
            unsafe.isEmpty(),
        )
    }

    @Test
    fun recognitionOwnershipFieldsAreSafelyPublished() {
        val fields = fieldsOf(SEARCH_CONTROLLER)
        val unsafe = OWNERSHIP_FIELDS.mapNotNull { fields[it] }.filterNot(::safelyPublished).map { it.name }
        assertTrue(
            "识别归属在 IO 线程上读 left / linkSerial，须 volatile 或原子类型（实际为普通字段：$unsafe）",
            unsafe.isEmpty(),
        )
    }

    /** volatile，或 final（不再重新赋值：原子引用 / 原子数、函数、只读世代来源等）。 */
    private fun safelyPublished(field: Field): Boolean = Modifier.isVolatile(field.modifiers) || Modifier.isFinal(field.modifiers)

    private fun fieldsOf(name: String): Map<String, Field> =
        Class
            .forName(name, false, javaClass.classLoader)
            .declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .associateBy { it.name }

    /** 源码里作为裸名出现的标识符（`x.name` 的成员访问不算，`this.name` 算）。 */
    private fun referencedNames(code: String): Set<String> =
        IDENTIFIER
            .findAll(code)
            .filter { match ->
                val before = code.substring(0, match.range.first)
                !before.endsWith(".") || before.endsWith("this.")
            }.map { it.value }
            .toSet()

    private fun functionBody(
        text: String,
        signature: String,
    ): String {
        val at = text.indexOf(signature)
        assertTrue("找不到 $signature（本扫描按这个入口名定位识别票据）", at >= 0)
        val open = text.indexOf("{\n", at)
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth += 1
                '}' -> depth -= 1
            }
            if (depth == 0) return text.substring(open + 1, i)
        }
        error("$signature 的函数体没有闭合")
    }

    private fun source(path: String): String = File(root, path).readText()

    private companion object {
        const val APP_CLIPBOARD = "com.couli.app.clipboard.AppClipboard"
        const val SEARCH_CONTROLLER = "com.couli.feature.search.SearchController"
        const val APP_CLIPBOARD_PATH = "app/src/main/kotlin/com/couli/app/clipboard/AppClipboard.kt"
        val OWNERSHIP_FIELDS = listOf("left", "linkSerial")
        val IDENTIFIER = Regex("\\b[A-Za-z_][A-Za-z0-9_]*\\b")
        val root: File =
            generateSequence(File(System.getProperty("user.dir") ?: ".").canonicalFile) { it.parentFile }
                .first { File(it, "settings.gradle.kts").isFile && File(it, APP_CLIPBOARD_PATH).isFile }
    }
}
