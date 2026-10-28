package com.couli.app.me

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APP-12w-a 逻辑零改动守卫（brief-android §5、G-S18；任务书红线）。基线 app/src/test/resources/settings-visual/guard-baseline.json 由测试作者在
 * 底版 5a795b5 生成（生成算法与本文件逐项一致，脚本见交回 §5）：
 * - 整文件 sha256：core/me 全部、core/clipboard、core/designsystem、feature/trade 全部、contracts/snapshot、contract.lock、feature/me 的执行器与其他页面、
 *   app/…/me 下全部、AppClipboard、AppShell、APP-12 / FU-AND-0717 / Invariant / Wiring / 账号 / 剪贴板 0722 / 我的视觉等保护测试、me-v / stepup-v / ds02 基准图；
 * - 行段：Settings.kt 第 1–475 行（到 SettingsConfig 收尾）、SettingsSubPages.kt 第 1–172 行（到 NicknameController 收尾）——import 行除外
 *   （ktlint no-unused-imports 下换组件必然删 import，不属逻辑）；SettingsSubPages.kt 第 255–375 行（NotifyState / NotifySettingsController）逐字节；
 *   ClipboardScreens.kt 除 ClipboardSettingsRow（及其 KDoc）外全部（import 行除外）；
 * - MePorts.kt：MeTags 之前逐字节、MeTags 原有每一行按原顺序仍在（只许新增常量）；
 * - MeUi.kt：原有共用件的参数逐个原样、原顺序保留（含默认值），新增参数必须带默认值，尾随 lambda 仍在最后。
 * - v3（编排裁定 2026-10-28「CouliTextField 加带默认值可选参数」）：组件库 CouliInputs.kt 仍整文件冻结，基线哈希只此一项更新为
 *   加参后的文件；另由 [couliTextFieldOnlyAppendsTwoDefaultedParameters] 锁签名：原 16 个参数原样原顺序，末尾只多
 *   `showErrorText: Boolean = true`、`clearButtonModifier: Modifier = Modifier` 两个。
 * - v4（只为 gitleaks generic-api-key 误报）：基线 `files` 由「路径为键、sha256 为值」改为数组，每项一行 `{"h": sha256, "p": 路径}`
 *   （路径里的 oauth / token / key / auth 等词后面不再紧跟哈希）。1155 项、行段基线与 v3 逐项等价，由 [protectedFiles] 还原成映射，
 *   并要求每项恰有 h / p 两个字段、哈希为 64 位小写十六进制、路径不重复。
 */
class SettingsVisualGuardTest {
    private val root = SettingsVisualFixtures.root
    private val base: JsonObject =
        Json
            .parseToJsonElement(
                requireNotNull(javaClass.classLoader?.getResource("settings-visual/guard-baseline.json")) { "缺守卫基线" }.readText(),
            ).jsonObject
    private val me = "feature/me/src/main/kotlin/com/couli/feature/me/"

    /** v4：基线 `files` 数组还原成「路径 → sha256」；格式不对或路径重复直接失败，不静默丢项。 */
    private val protectedFiles: Map<String, String> by lazy {
        val items = base.getValue("files").jsonArray.map { it.jsonObject }
        items.forEach { assertEquals("基线 files 每项只许 h / p 两个字段：$it", setOf("h", "p"), it.keys) }
        val pairs = items.map { it.text("p") to it.text("h") }
        pairs.forEach { (path, hash) -> assertTrue("基线哈希须为 64 位小写十六进制：$path", SHA256.matches(hash)) }
        val map = pairs.toMap()
        assertEquals("基线 files 路径不得重复", pairs.size, map.size)
        map
    }

    @Test fun baselineIsFor5a795b5AndNotEmptied() {
        assertEquals("5a795b516389b3d1947bcdea680ed2a587192bd0", base.getValue("base").jsonPrimitive.content)
        val files = protectedFiles
        assertTrue("清单不得被清空", files.size >= MIN_FILES)
        listOf(
            "core/me/src/main/kotlin/com/couli/core/me/MePresentation.kt",
            "contract.lock",
            "app/src/main/kotlin/com/couli/app/clipboard/AppClipboard.kt",
            "app/src/test/kotlin/com/couli/app/clipboard/ClipboardConsent0722IntegrationTest.kt",
            "app/src/test/kotlin/com/couli/app/followup/FollowupAnd0721InvariantTest.kt",
            COULI_INPUTS,
        ).forEach { assertTrue("必须冻结：$it", it in files) }
        assertTrue("feature/trade 全部在冻结清单", files.keys.count { it.startsWith("feature/trade/") } >= MIN_TRADE)
        assertTrue(
            "本任务允许改的文件不得冻结",
            files.keys.none {
                it.endsWith("/Settings.kt") || it.endsWith("/SettingsSubPages.kt") ||
                    it.endsWith("/MeUi.kt")
            },
        )
    }

    @Test fun protectedFilesAreByteIdentical() {
        val changed =
            protectedFiles.filter { (path, expected) ->
                val file = File(root, path)
                !file.isFile || sha(file.readBytes()) != expected
            }
        assertTrue("受保护文件被改动：${changed.keys}", changed.isEmpty())
    }

    @Test fun settingsLines1To475ExceptImportsAreUnchanged() {
        val lines = lines(me + "Settings.kt")
        val start = lines.indexOfFirst { it.startsWith("class SettingsConfig(") }
        val end = (start until lines.size).first { lines[it] == ")" }
        assertEquals(
            "Settings.kt 1–475 行（SettingsState / SettingsController / SettingsConfig）零改动",
            base.text("settingsHead"),
            joined(
                noImports(
                    lines.subList(
                        0,
                        end + 1,
                    ),
                ),
            ),
        )
    }

    @Test fun nicknameControllerAndNotifyControllerAreUnchanged() {
        val lines = lines(me + "SettingsSubPages.kt")
        val nickname = lines.indexOfFirst { it.startsWith("class NicknameController(") }
        val nicknameEnd = (nickname until lines.size).first { lines[it] == "}" }
        assertEquals(
            "SettingsSubPages.kt 1–172 行（NicknameState / NicknameController）零改动",
            base.text("nicknameHead"),
            joined(
                noImports(
                    lines.subList(
                        0,
                        nicknameEnd + 1,
                    ),
                ),
            ),
        )
        val start = lines.indexOfFirst { it.startsWith("/** 通知设置子页的状态。") }
        val notify = lines.indexOfFirst { it.startsWith("class NotifySettingsController(") }
        val end = (notify until lines.size).first { lines[it] == "}" }
        assertEquals(
            "SettingsSubPages.kt 255–375 行（NotifyState / NotifySettingsController）逐字节不变",
            base.text("notifyBlock"),
            joined(
                lines.subList(
                    start,
                    end + 1,
                ),
            ),
        )
    }

    @Test fun clipboardScreensOnlyChangesTheSettingsRow() {
        val lines = lines("app/src/main/kotlin/com/couli/app/clipboard/ClipboardScreens.kt")
        val fn = lines.indexOfFirst { it.startsWith("fun ClipboardSettingsRow(") }
        val kdoc = (0 until fn).last { lines[it] == "/**" }
        assertEquals("ClipboardSettingsRow 之前（import 除外）不变", base.text("clipboardPrefix"), joined(noImports(lines.subList(0, kdoc))))
        val suffix = lines.indexOf("private const val PROMPT_KEY = \"clipboard.prompt\"")
        assertEquals("ClipboardSettingsRow 之后不变", base.text("clipboardSuffix"), joined(lines.subList(suffix, lines.size)))
    }

    @Test fun mePortsOnlyAddsTags() {
        val lines = lines(me + "MePorts.kt")
        val tags = lines.indexOf("object MeTags {")
        assertEquals("MeTags 之前逐字节不变", base.text("mePortsPrefix"), joined(lines.subList(0, tags)))
        val original = base.getValue("meTags").jsonArray.map { it.jsonPrimitive.content }
        var cursor = tags
        original.forEach { line ->
            val found = (cursor until lines.size).firstOrNull { lines[it] == line }
            assertTrue("MeTags 原有行被改或乱序：$line", found != null)
            cursor = found!! + 1
        }
    }

    @Test fun meUiOnlyAddsOptionalParametersAndKeepsDefaults() {
        val source = File(root, me + "MeUi.kt").readText()
        base.getValue("meUi").jsonObject.forEach { (name, raw) ->
            val old = raw.jsonArray.map { it.jsonPrimitive.content }
            val now = params(source, name)
            var cursor = 0
            old.forEach { param ->
                val found = (cursor until now.size).firstOrNull { now[it] == param }
                assertTrue("G-S02 $name：原参数「$param」须原样（含默认值）保留并保持顺序；现为 $now", found != null)
                cursor = found!! + 1
            }
            (now - old.toSet()).forEach { added -> assertTrue("G-S02 $name：新增参数必须带默认值：$added", added.contains("=")) }
            val last = old.last()
            if (isTrailingLambda(last)) assertEquals("G-S02 $name：尾随 lambda 必须仍在最后", last, now.last())
        }
    }

    /** v3：CouliTextField 原参数逐个原样（含默认值）、原顺序在前，末尾只新增两个带默认值的参数；不许另起重载绕开。 */
    @Test fun couliTextFieldOnlyAppendsTwoDefaultedParameters() {
        val source = File(root, COULI_INPUTS).readText()
        assertEquals("CouliTextField 只能有一个定义", 1, Regex("""\bfun CouliTextField\(""").findAll(source).count())
        val now = params(source, "CouliTextField")
        assertEquals("CouliTextField 原参数须原样、原顺序在前；现为 $now", COULI_TEXT_FIELD_BASE, now.take(COULI_TEXT_FIELD_BASE.size))
        assertEquals(
            "CouliTextField 新增参数只许 showErrorText = true、clearButtonModifier = Modifier（默认值行为不变）",
            listOf("showErrorText:Boolean=true", "clearButtonModifier:Modifier=Modifier"),
            now.drop(COULI_TEXT_FIELD_BASE.size),
        )
    }

    /**
     * v2：只有「参数类型本身是非空函数类型」才是尾随 lambda（可写成 `Foo { }`）。旧判据只看类型串里有没有「->」，
     * 把 `rows: List<@Composable () -> Unit>` 这类类型参数里含函数类型的也算进去，致 RowCard 末尾不能再加可选参数。
     * 参数已去空白：先去掉前缀 `@Composable`，再去掉接收者（如 `ColumnScope.`），剩下须以「(…)->」开头；
     * `(() -> Unit)?` 这类可空函数类型的首括号闭合后跟「?」，不算。
     */
    private fun isTrailingLambda(param: String): Boolean {
        val type =
            param
                .substringAfter(":")
                .substringBefore("=")
                .removePrefix("@Composable")
                .replace(RECEIVER, "")
        var depth = 0
        val close =
            type.indices.firstOrNull { index ->
                if (type[index] == '(') depth++
                if (type[index] == ')') depth--
                depth == 0
            }
        return type.startsWith("(") && close != null && type.startsWith("->", close + 1)
    }

    private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content

    private fun lines(path: String) = File(root, path).readLines()

    private fun noImports(lines: List<String>) = lines.filterNot { it.startsWith("import ") }

    private fun joined(lines: List<String>) = sha(lines.joinToString("\n").toByteArray())

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** 与生成脚本同一算法：按顶层逗号切参数，去掉全部空白（「->」里的「>」不算尖括号）。 */
    private fun params(
        source: String,
        name: String,
    ): List<String> {
        val head = "fun $name("
        var i = source.indexOf(head).also { assertTrue("源码里找不到 $name", it >= 0) } + head.length
        var depth = 0
        val current = StringBuilder()
        val out = mutableListOf<String>()
        while (true) {
            val ch = source[i]
            when {
                ch == '(' || ch == '<' -> depth++
                ch == '>' && source[i - 1] != '-' -> depth--
                ch == ')' && depth == 0 -> break
                ch == ')' -> depth--
            }
            if (ch == ',' && depth == 0) {
                out += current.toString()
                current.clear()
            } else {
                current.append(ch)
            }
            i++
        }
        if (current.isNotBlank()) out += current.toString()
        return out.map { it.filterNot(Char::isWhitespace) }.filter { it.isNotEmpty() }
    }

    private companion object {
        const val MIN_FILES = 1000
        const val MIN_TRADE = 20
        const val COULI_INPUTS = "core/designsystem/src/main/kotlin/com/couli/core/designsystem/CouliInputs.kt"

        /** 底版 5a795b5 的 CouliTextField 参数（与 [params] 同算法、已去空白）。 */
        val COULI_TEXT_FIELD_BASE =
            listOf(
                "value:String",
                "onValueChange:(String)->Unit",
                "modifier:Modifier=Modifier",
                "placeholder:String?=null",
                "enabled:Boolean=true",
                "readOnly:Boolean=false",
                "error:String?=null",
                "clearLabel:String?=null",
                "maxLength:Int?=null",
                "singleLine:Boolean=true",
                "keyboardOptions:KeyboardOptions=KeyboardOptions.Default",
                "keyboardActions:KeyboardActions=KeyboardActions.Default",
                "accessibilityLabel:String?=null",
                "leading:(@Composable()->Unit)?=null",
                "fieldContentType:ContentType?=null",
                "interactionSource:MutableInteractionSource?=null",
            )
        val SHA256 = Regex("^[0-9a-f]{64}$")
        val RECEIVER = Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*\\.(?=\\()")
    }
}
