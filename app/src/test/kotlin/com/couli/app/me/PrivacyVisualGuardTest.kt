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
 * APP-12w-b 逻辑零改动守卫（brief-b-android §5、G-P14；D10-403 ①②③）。基线 app/src/test/resources/privacy-visual/guard-baseline.json 由测试作者在
 * 底版 fe60a02 生成（生成脚本 NATIVE/APP-12w/tests-b-android-guard-gen.py，算法与本文件逐项一致）；`files` 为 [{"h": sha256, "p": 路径}] 数组。
 * - 整文件 sha256：core/me（MeDraftTexts.kt 另判）、core/privacy、core/clipboard、core/designsystem（FeedbackComponents.kt 另判）、feature/trade、
 *   contracts/snapshot、contract.lock、specs/me-vectors.json、app/…/me 全部、AppPrivacy、AppShell、feature/me 其他页面与设置一族视图件、
 *   保护测试（AppMeIntegrationTest、AccountEntryIntegrationTest、ClipboardHashesWithdrawalTest、AppShellUpdateTest、
 *   SettingsVisual*.kt、MeVisual*.kt）、
 *   me-v / settings-v / stepup-v / ds02 基准图。APP-12w-a 的 settings-visual/guard-baseline.json 不在此列（按 D10-403 ① 另由新鲜独立 Claude 换哈希）。
 * - 行段：PrivacyCenter.kt 文件头到 PrivacyCenterConfig 收尾（1–172，import 除外）、PrivacyCenterScreen 头到 model（180–195）逐字节、
 *   确认框块（310–324）去掉 titleAlign / scrim 两行后逐字节；AboutController.kt 文件头到 AboutScreen 的 `val texts`（1–91，import 除外）。
 * - MePorts.kt 只新增 MeTags 常量；MeUi.kt 原有参数原样原顺序（新增必须带默认值，尾随 lambda 仍最后）；
 * - G-P05：FeedbackComponents.kt 半屏段（CouliSheet / CouliSheetBody）以外去 import 后逐字节，两函数签名只在尾随 content 之前多 `headerCentered: Boolean = false`；
 * - G-P07：MeDraftTexts.kt 除 cs.safety 一行外逐字节。
 * 签名比对先去掉注释（参数前的 KDoc 不算改动），再按顶层逗号切参、去空白。
 */
class PrivacyVisualGuardTest {
    private val root = PrivacyVisualFixtures.root
    private val base: JsonObject =
        Json
            .parseToJsonElement(
                requireNotNull(javaClass.classLoader?.getResource("privacy-visual/guard-baseline.json")) { "缺守卫基线" }.readText(),
            ).jsonObject
    private val me = "feature/me/src/main/kotlin/com/couli/feature/me/"

    private val protectedFiles: Map<String, String> by lazy {
        val items = base.getValue("files").jsonArray.map { it.jsonObject }
        items.forEach { assertEquals("基线 files 每项只许 h / p 两个字段：$it", setOf("h", "p"), it.keys) }
        val pairs = items.map { it.text("p") to it.text("h") }
        pairs.forEach { (path, hash) -> assertTrue("基线哈希须为 64 位小写十六进制：$path", SHA256.matches(hash)) }
        val map = pairs.toMap()
        assertEquals("基线 files 路径不得重复", pairs.size, map.size)
        map
    }

    @Test fun baselineIsForFe60a02AndCoversTheRedLineFiles() {
        assertEquals("fe60a02a829c3cc302e17486611f690d96775cff", base.text("base"))
        val files = protectedFiles
        assertTrue("清单不得被清空", files.size >= MIN_FILES)
        listOf(
            "core/me/src/main/kotlin/com/couli/core/me/MePresentation.kt",
            "core/me/src/main/kotlin/com/couli/core/me/CustomerServiceEntry.kt",
            "core/me/src/main/kotlin/com/couli/core/me/MeLocalStore.kt",
            "core/me/src/test/kotlin/com/couli/core/me/MeVectorTest.kt",
            "core/privacy/src/main/kotlin/com/couli/core/privacy/PrivacyGate.kt",
            "core/designsystem/src/main/kotlin/com/couli/core/designsystem/CouliInputs.kt",
            "app/src/main/kotlin/com/couli/app/privacy/AppPrivacy.kt",
            "app/src/main/kotlin/com/couli/app/me/AppMe.kt",
            "app/src/main/kotlin/com/couli/app/me/MeRoutes.kt",
            "app/src/main/kotlin/com/couli/app/me/AndroidMeSystem.kt",
            "app/src/test/kotlin/com/couli/app/me/AppMeIntegrationTest.kt",
            "app/src/test/kotlin/com/couli/app/me/SettingsVisualGuardTest.kt",
            "app/src/test/kotlin/com/couli/app/clipboard/ClipboardHashesWithdrawalTest.kt",
            "app/src/test/kotlin/com/couli/app/shell/AppShellUpdateTest.kt",
            "specs/me-vectors.json",
            "contract.lock",
            me + "SettingsParts.kt",
            me + "Settings.kt",
            me + "MeTabController.kt",
        ).forEach { assertTrue("必须冻结：$it", it in files) }
        assertTrue("core/privacy 全部在冻结清单", files.keys.count { it.startsWith("core/privacy/") } >= MIN_PRIVACY)
        assertTrue("feature/trade 全部在冻结清单", files.keys.count { it.startsWith("feature/trade/") } >= MIN_TRADE)
        val allowed =
            listOf(
                "/PrivacyCenter.kt",
                "/AboutController.kt",
                "/CsSheets.kt",
                "/MeUi.kt",
                "/MePorts.kt",
                "/FeedbackComponents.kt",
                "/MeDraftTexts.kt",
            )
        assertTrue("本任务允许改的文件不得整文件冻结（另有行段 / 签名判据）", files.keys.none { path -> allowed.any { path.endsWith(it) } })
        assertTrue("APP-12w-a 的守卫基线由 D10-403 ① 另行换哈希，不在本守卫", files.keys.none { it.endsWith("settings-visual/guard-baseline.json") })
    }

    @Test fun protectedFilesAreByteIdentical() {
        val changed =
            protectedFiles.filter { (path, expected) ->
                val file = File(root, path)
                !file.isFile || sha(file.readBytes()) != expected
            }
        assertTrue("受保护文件被改动：${changed.keys}", changed.isEmpty())
    }

    @Test fun privacyCenterControllerAndConfigAreUnchanged() {
        val lines = lines(me + "PrivacyCenter.kt")
        val start = lines.indexOfFirst { it.startsWith("class PrivacyCenterConfig(") }
        assertTrue("PrivacyCenterConfig 仍在 PrivacyCenter.kt", start >= 0)
        val end = (start until lines.size).first { lines[it] == ")" }
        assertEquals(
            "PrivacyCenter.kt 1–172 行（PrivacyState / PrivacyCenterController / PrivacyCenterConfig）零改动（import 除外）",
            base.text("privacyHead"),
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

    @Test fun privacyCenterScreenHeadIsUnchanged() {
        val lines = lines(me + "PrivacyCenter.kt")
        val start = lines.indexOf("fun PrivacyCenterScreen(config: PrivacyCenterConfig) {")
        val end = lines.indexOf("    val model = remember(controller, resumes) { controller.model() }")
        assertTrue("PrivacyCenterScreen 头与 model 行仍在：$start / $end", start >= 0 && end > start)
        assertEquals(
            "PrivacyCenter.kt 180–195 行（执行器、bindLeave、状态收集、OnResumed、model）逐字节不变",
            base.text("privacyScreenHead"),
            joined(
                lines.subList(
                    start,
                    end + 1,
                ),
            ),
        )
    }

    @Test fun withdrawDialogOnlyAddsTitleAlignAndScrim() {
        val lines = lines(me + "PrivacyCenter.kt")
        val start = lines.indexOf("    val confirm = state.confirm")
        assertTrue("确认框块仍在 PrivacyCenterScreen 里", start >= 0)
        val end = (start + 1 until lines.size).first { lines[it] == "    }" }
        val block = lines.subList(start, end + 1)
        assertEquals(
            "确认框（310–324）只许多出 titleAlign = … / scrim = … 两个实参，回调、编号、主次、遮罩、标记一字不动",
            base.text("privacyDialog"),
            joined(block.filterNot { it.trim().startsWith("titleAlign =") || it.trim().startsWith("scrim =") }),
        )
    }

    @Test fun aboutControllerIsUnchanged() {
        val lines = lines(me + "AboutController.kt")
        val fn = lines.indexOf("fun AboutScreen(")
        assertTrue("AboutScreen 签名首行不变", fn >= 0)
        val end = (fn until lines.size).first { lines[it] == "    val texts = deps.texts" }
        assertEquals("AboutController.kt 1–91 行零改动（import 除外）", base.text("aboutHead"), joined(noImports(lines.subList(0, end + 1))))
    }

    @Test fun mePortsOnlyAddsTags() {
        val lines = lines(me + "MePorts.kt")
        val tags = lines.indexOf("object MeTags {")
        assertEquals("MeTags 之前逐字节不变", base.text("mePortsPrefix"), joined(lines.subList(0, tags)))
        var cursor = tags
        base.getValue("meTags").jsonArray.map { it.jsonPrimitive.content }.forEach { line ->
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
                assertTrue("G-P03 $name：原参数「$param」须原样（含默认值）保留并保持顺序；现为 $now", found != null)
                cursor = found!! + 1
            }
            (now - old.toSet()).forEach { added -> assertTrue("G-P03 $name：新增参数必须带默认值：$added", added.contains("=")) }
            val last = old.last()
            if (isTrailingLambda(last)) assertEquals("G-P03 $name：尾随 lambda 必须仍在最后", last, now.last())
        }
    }

    /** G-P05 / D10-401：CouliSheet 与 CouliSheetBody 原参数原样原顺序，只在尾随 content 之前多一个 `headerCentered: Boolean = false`；不许另起重载。 */
    @Test fun couliSheetOnlyAddsHeaderCenteredDefaultFalse() {
        val source = File(root, FEEDBACK).readText()
        base.getValue("sheetParams").jsonObject.forEach { (name, raw) ->
            val old = raw.jsonArray.map { it.jsonPrimitive.content }
            assertEquals("$name 只能有一个定义", 1, Regex("""\bfun $name\(""").findAll(source).count())
            val now = params(source, name)
            assertEquals(
                "G-P05 $name：参数 = 原参数 + headerCentered:Boolean=false（在尾随 content 之前）；现为 $now",
                old.dropLast(1) + HEADER_CENTERED + old.last(),
                now,
            )
        }
    }

    @Test fun feedbackComponentsOutsideTheSheetIsUnchanged() {
        val lines = lines(FEEDBACK)
        val kdoc = lines.indexOf(SHEET_KDOC_FIRST) - 1
        assertTrue("CouliSheet 的 KDoc 首段仍在", kdoc >= 0 && lines[kdoc] == "/**")
        val handle = lines.indexOf("/** 半屏把手 36 × 4。 */")
        assertTrue(handle > kdoc)
        assertEquals(
            "FeedbackComponents.kt 半屏段以外（import 除外）逐字节不变（D10-401 ①）",
            base.text("feedbackOutsideSheet"),
            joined(
                noImports(lines.subList(0, kdoc) + lines.subList(handle, lines.size)),
            ),
        )
    }

    @Test fun draftTextsOnlyChangeTheSafetyLine() {
        val lines = lines(DRAFT)
        assertEquals("cs.safety 只有一行", 1, lines.count { it.contains("\"cs.safety\" to") })
        assertEquals(
            "G-P07：MeDraftTexts.kt 除 cs.safety 一行外逐字节不变（键名不变、不新增键）",
            base.text("draftWithoutSafety"),
            joined(
                lines.filterNot {
                    it.contains("\"cs.safety\" to")
                },
            ),
        )
    }

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

    private fun lines(path: String): List<String> {
        val text = File(root, path).readText()
        val all = text.split("\n")
        return if (text.endsWith("\n")) all.dropLast(1) else all
    }

    private fun noImports(lines: List<String>) = lines.filterNot { it.startsWith("import ") }

    private fun joined(lines: List<String>) = sha(lines.joinToString("\n").toByteArray())

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** 与生成脚本同一算法：先去注释，再按顶层逗号切参数，去掉全部空白（「->」里的「>」不算尖括号）。 */
    private fun params(
        raw: String,
        name: String,
    ): List<String> {
        val source = raw.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")
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
        const val MIN_PRIVACY = 10
        const val FEEDBACK = "core/designsystem/src/main/kotlin/com/couli/core/designsystem/FeedbackComponents.kt"
        const val DRAFT = "core/me/src/main/kotlin/com/couli/core/me/MeDraftTexts.kt"
        const val HEADER_CENTERED = "headerCentered:Boolean=false"
        const val SHEET_KDOC_FIRST =
            " * 底部半屏（GUIDE §3）：白底、上两角 24、顶部 36×4 把手；标题 → 正文 → 操作。头部与底部操作 [actions] 固定，只有正文滚动，"
        val SHA256 = Regex("^[0-9a-f]{64}$")
        val RECEIVER = Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*\\.(?=\\()")
        val BLOCK_COMMENT = Regex("/\\*[\\s\\S]*?\\*/")
        val LINE_COMMENT = Regex("//[^\\n]*")
    }
}
