package com.couli.app.search.submit

import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FU-AND-SEARCH-LINK-OWNER 测试 v1（D10-768 新结构）：结构断言，不跑产品流程。约定的最小接口（实现者照做，名字是断言的锚点）：
 *
 * AppClipboard（app）：
 * - `OwnerSnapshot` 新字段 `searchSubmit`（在途识别提交的 id，或含 id 的不可变槽对象），由 `publish()` 原子发布；
 * - `fun beginSearchSubmit(origin: String): Long`：主线程发号、记下 origin / screen / session / epochs、写槽、`publish()`；
 * - `fun stillOwned(id: Long): Boolean`：单一谓词 = `published.get().searchSubmit` 对上 id，再加世代比对；只读 `published` 与 final / volatile；
 * - `fun dropSearchSubmit(id: Long)`：搜索侧出口（再提交、新关键词、离页），槽仍是 id 时经 `clearSearchSubmit()` 清；
 * - `private fun clearSearchSubmit()`：唯一清槽入口 = 槽置空 + `publish()` + `cancelOwned`（该提交名下全部归属）；
 *   既有作废出口 `screenChanged` / `invalidateAll` / `onSensitiveOverlay` 各追加一行调它，`onSessionChanged` / `onEnvironmentSwitched` /
 *   `onPrivacyChanged` 经 `invalidateAll` 或直接调它。
 *
 * SearchController（feature:search）：不再自持识别归属——没有 `linkSerial` / `linkOwners` 字段，不再自拼票据（源码里不再出现 `linkSerial`、
 * `linkOwners`、`ticketValid`、`.ticket(`）。
 *
 * 局限：源码扫描按函数名定位、按花括号配对取函数体（字符串、注释里的花括号也会计数）；只看函数体里的直接调用与裸名，被调方法内部读了什么扫不到。
 * 「出口漏清槽」「某路径自拼归属」「续搜不带 id」的行为面由 SearchSubmitOwnerPropertyTest 兜。
 */
class SearchSubmitOwnerStructureTest {
    private val clipboard: String by lazy { File(root, APP_CLIPBOARD_PATH).readText() }

    @Test
    fun controllerKeepsNoRecognitionOwnership() {
        val fields = fieldsOf(SEARCH_CONTROLLER).keys.filter { it in OWN_FIELDS }
        val source = File(root, SEARCH_CONTROLLER_PATH).readText()
        val names = OWN_NAMES.filter { Regex(it).containsMatchIn(source) }.map { it.replace("\\b", "").replace("\\", "") }
        assertTrue(
            "SearchController 不得再自持识别归属（字段 $fields，源码仍出现 $names）；归属由剪贴板的 searchSubmit 槽统一管理",
            fields.isEmpty() && names.isEmpty(),
        )
    }

    @Test
    fun ownerSnapshotPublishesSearchSubmitSlot() {
        val fields = fieldsOf(OWNER_SNAPSHOT).keys
        assertTrue("AppClipboard.OwnerSnapshot 须有 searchSubmit 字段（由 publish() 原子发布；实际字段 $fields）", SLOT in fields)
        val publish = body(clipboard, "publish").orEmpty()
        assertTrue("publish() 须把 searchSubmit 写进快照", Regex("\\b$SLOT\\s*=").containsMatchIn(publish))
    }

    @Test
    fun everyInvalidationExitClearsSlotThroughOneEntry() {
        val missing = mutableListOf<String>()
        if (body(clipboard, CLEAR) == null) missing += "缺 fun $CLEAR("
        (DIRECT_EXITS + "dropSearchSubmit").forEach { exit ->
            val code = body(clipboard, exit)
            if (code == null || !calls(code, CLEAR)) missing += exit
        }
        VIA_INVALIDATE_ALL.forEach { exit ->
            val code = body(clipboard, exit)
            if (code == null || !(calls(code, CLEAR) || calls(code, "invalidateAll"))) missing += exit
        }
        assertTrue("剪贴板各作废出口须都调同一个清槽入口 $CLEAR()（缺：$missing）", missing.isEmpty())
    }

    @Test
    fun slotIsWrittenOnlyByBeginAndClear() {
        val absent = listOf("beginSearchSubmit", CLEAR).filter { body(clipboard, it) == null }
        val writers =
            Regex("\\b$SLOT\\w*\\s*=(?!=)")
                .findAll(clipboard)
                .filterNot { commentLine(clipboard, it.range.first) }
                .map { enclosingFunction(clipboard, it.range.first) }
                .filterNot { it in SLOT_WRITERS }
                .toSet()
        assertTrue(
            "searchSubmit 槽只能由 beginSearchSubmit 发号、$CLEAR 清空（publish 只拷进快照）；缺入口 $absent，越权写入的函数 $writers",
            absent.isEmpty() && writers.isEmpty(),
        )
    }

    @Test
    fun clearEntryPublishesAndCancelsOwned() {
        val code = body(clipboard, CLEAR)
        val lacks =
            when {
                code == null -> listOf("fun $CLEAR(")
                else -> listOf("publish", "cancelOwned").filterNot { calls(code, it) }
            }
        assertTrue("$CLEAR() 须只做「清槽 + publish() + cancelOwned(该提交名下全部归属)」（缺 $lacks）", lacks.isEmpty())
    }

    @Test
    fun stillOwnedReadsOnlyPublishedSnapshot() {
        val code = body(clipboard, "stillOwned")
        val problems = mutableListOf<String>()
        if (code == null) {
            problems += "缺 fun stillOwned(id: Long)"
        } else {
            if (!code.contains("published.get()")) problems += "没有读 published.get()"
            if (!Regex("\\b$SLOT\\b").containsMatchIn(code)) problems += "没有比对 searchSubmit"
            val fields = fieldsOf(APP_CLIPBOARD)
            val unsafe = referencedNames(code).mapNotNull { fields[it] }.filterNot(::safelyPublished).map { it.name }
            if (unsafe.isNotEmpty()) problems += "直接读普通字段 $unsafe"
        }
        assertTrue("单一谓词 stillOwned(id) 在网络线程上求值，只能读 published 原子快照与 final / volatile：$problems", problems.isEmpty())
    }

    // ---------------------------------------------------------------- 源码与反射

    private fun safelyPublished(field: Field): Boolean = Modifier.isVolatile(field.modifiers) || Modifier.isFinal(field.modifiers)

    private fun fieldsOf(name: String): Map<String, Field> =
        Class
            .forName(name, false, javaClass.classLoader)
            .declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .associateBy { it.name }

    private fun calls(
        code: String,
        name: String,
    ): Boolean = Regex("\\b$name\\s*\\(").containsMatchIn(code)

    /** 裸名（`x.name` 的成员访问不算，`this.name` 算）。 */
    private fun referencedNames(code: String): Set<String> =
        IDENTIFIER
            .findAll(code)
            .filter { match ->
                val before = code.substring(0, match.range.first)
                !before.endsWith(".") || before.endsWith("this.")
            }.map { it.value }
            .toSet()

    private fun commentLine(
        text: String,
        at: Int,
    ): Boolean {
        val line = text.substring(text.lastIndexOf('\n', at) + 1, at).trimStart()
        return line.startsWith("*") || line.startsWith("//") || line.startsWith("/*")
    }

    private fun enclosingFunction(
        text: String,
        at: Int,
    ): String =
        FUNCTION
            .findAll(text.substring(0, at))
            .lastOrNull()
            ?.groupValues
            ?.get(1) ?: "<类体>"

    /** 成员函数 [name] 的函数体（块体取花括号之间；表达式体取 `=` 之后到下一个成员）；没有为 null。 */
    @Suppress("ReturnCount", "CyclomaticComplexMethod") // 找不到 / 块体 / 表达式体逐项返回
    private fun body(
        text: String,
        name: String,
    ): String? {
        val start = Regex("\\bfun\\s+$name\\s*\\(").find(text) ?: return null
        val afterParams = closeOf(text, start.range.last, '(', ')') ?: return null
        var i = afterParams + 1
        var depth = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '(') depth += 1
            if (c == ')') depth -= 1
            if (depth == 0 && (c == '{' || c == '=')) break
            i += 1
        }
        if (i >= text.length) return null
        if (text[i] == '{') return closeOf(text, i, '{', '}')?.let { text.substring(i + 1, it) }
        return text
            .substring(i + 1)
            .lineSequence()
            .withIndex()
            .takeWhile { (index, line) -> index == 0 || (line.isNotBlank() && line.takeWhile { it == ' ' }.length > MEMBER_INDENT) }
            .joinToString("\n") { it.value }
    }

    private fun closeOf(
        text: String,
        open: Int,
        opening: Char,
        closing: Char,
    ): Int? {
        var depth = 0
        for (i in open until text.length) {
            if (text[i] == opening) depth += 1
            if (text[i] == closing) depth -= 1
            if (depth == 0) return i
        }
        return null
    }

    private companion object {
        const val APP_CLIPBOARD = "com.couli.app.clipboard.AppClipboard"
        const val OWNER_SNAPSHOT = "com.couli.app.clipboard.AppClipboard\$OwnerSnapshot"
        const val SEARCH_CONTROLLER = "com.couli.feature.search.SearchController"
        const val APP_CLIPBOARD_PATH = "app/src/main/kotlin/com/couli/app/clipboard/AppClipboard.kt"
        const val SEARCH_CONTROLLER_PATH = "feature/search/src/main/kotlin/com/couli/feature/search/SearchController.kt"
        const val SLOT = "searchSubmit"
        const val CLEAR = "clearSearchSubmit"
        const val MEMBER_INDENT = 4
        val OWN_FIELDS = setOf("linkSerial", "linkOwners")
        val OWN_NAMES = listOf("\\blinkSerial\\b", "\\blinkOwners\\b", "\\bticketValid\\b", "\\.ticket\\(")
        val DIRECT_EXITS = listOf("screenChanged", "invalidateAll", "onSensitiveOverlay")
        val VIA_INVALIDATE_ALL = listOf("onSessionChanged", "onEnvironmentSwitched", "onPrivacyChanged")
        val SLOT_WRITERS = setOf("beginSearchSubmit", CLEAR, "publish")
        val IDENTIFIER = Regex("\\b[A-Za-z_][A-Za-z0-9_]*\\b")
        val FUNCTION = Regex("\\bfun\\s+(?:[A-Za-z_][\\w<>?, ]*\\.)?([A-Za-z_]\\w*)\\s*\\(")
        val root: File =
            generateSequence(File(System.getProperty("user.dir") ?: ".").canonicalFile) { it.parentFile }
                .first { File(it, "settings.gradle.kts").isFile && File(it, APP_CLIPBOARD_PATH).isFile }
    }
}
