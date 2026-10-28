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
 * APP-12w-c 逻辑零改动守卫（brief-c-android §5、G-R05 / G-R16；D10-406 ①–④）。基线 app/src/test/resources/risk-visual/guard-baseline.json 由测试作者在
 * 底版 e9fdd94 生成（生成脚本 NATIVE/APP-12w/tests-c-android-guard-gen.py，算法与本文件逐项一致）；`files` 为 [{"h": sha256, "p": 路径}] 数组。
 * - 整文件 sha256：core/me（MeDraftTexts.kt 另判）、core/wallet、core/privacy、core/clipboard、core/network、core/designsystem（CouliIcons.kt 另判）、
 *   feature/trade、feature/wallet、contracts/snapshot、contract.lock、specs/me-vectors.json、specs/privacy-visual-vectors.json、
 *   app/…/me、app/…/wallet、
 *   AppShell、feature/me 其他页面与设置 / 隐私一族视图件、注销 / 换号、保护测试（AppMeIntegrationTest、AccountEntryIntegrationTest、AppWalletIntegrationTest、
 *   WalletVisualBehaviorTest、SettingsVisual*、PrivacyVisual*）、me-v / settings-v / privacy-v / stepup-v / wallet-v / ds02 基准图。
 * - 行段（去 import 后逐字节）：RiskNotice.kt 1–310、319–336、确认框 390–402（去掉 titleAlign / scrim 两行）、主按钮键映射与回调 349–360（去空白）；
 *   Appeal.kt 1–379、388–404、确认框 454–466（同上）；回调串与执行器引用集合、纯规则引用集合与改前相等（防视图另起判定）。
 * - MePorts.kt 只新增 MeTags；MeUi.kt 原参数原样原顺序（新增必须带默认值，尾随 lambda 仍最后）；AccountUi.kt LocalAccountDeps 之前逐字节、
 *   DeleteAccountLink 原参数原样（末尾只多带默认值参数）；CouliIcons.kt 原有成员逐字节、只在 object 末尾追加 lock / pauseCircle（G-R06）；
 *   MeDraftTexts.kt 除 G-R09 五行与 G-R14 / G-R17 新增行外逐字节。
 * - 两份已合并守卫基线（settings-visual / privacy-visual）只许换清单内文件的 h 与 draftWithoutSafety（G-R02 / D10-403 ①），其余逐字节。
 */
@Suppress("TooManyFunctions")
class RiskVisualGuardTest {
    private val root = RiskVisualFixtures.root
    private val base: JsonObject =
        Json
            .parseToJsonElement(
                requireNotNull(javaClass.classLoader?.getResource("risk-visual/guard-baseline.json")) { "缺守卫基线" }.readText(),
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

    @Test fun baselineIsForE9fdd94AndCoversTheRedLineFiles() {
        assertEquals("e9fdd94ce114ab8e670cea731accb3e6d4e2170c", base.text("base"))
        val files = protectedFiles
        assertTrue("清单不得被清空", files.size >= MIN_FILES)
        listOf(
            "core/me/src/main/kotlin/com/couli/core/me/MePresentation.kt",
            "core/me/src/main/kotlin/com/couli/core/me/MeJson.kt",
            "core/me/src/test/kotlin/com/couli/core/me/MeVectorTest.kt",
            "core/wallet/src/main/kotlin/com/couli/core/wallet/WithdrawalText.kt",
            "app/src/main/kotlin/com/couli/app/me/AppMe.kt",
            "app/src/main/kotlin/com/couli/app/me/MeRoutes.kt",
            "app/src/test/kotlin/com/couli/app/me/AppMeIntegrationTest.kt",
            "app/src/test/kotlin/com/couli/app/account/AccountEntryIntegrationTest.kt",
            "app/src/test/kotlin/com/couli/app/me/SettingsVisualGuardTest.kt",
            "app/src/test/kotlin/com/couli/app/me/PrivacyVisualGuardTest.kt",
            "specs/me-vectors.json",
            "contract.lock",
            me + "AccountPorts.kt",
            me + "DeleteAccountController.kt",
            me + "SettingsParts.kt",
            me + "PrivacyAboutCsParts.kt",
        ).forEach { assertTrue("必须冻结：$it", it in files) }
        listOf("core/network/", "core/wallet/", "feature/wallet/", "feature/trade/", "app/src/test/snapshots/privacy-v/").forEach { dir ->
            assertTrue("$dir 在冻结清单", files.keys.any { it.startsWith(dir) })
        }
        val allowed =
            listOf(
                "/RiskNotice.kt",
                "/Appeal.kt",
                "/MeUi.kt",
                "/MePorts.kt",
                "/AccountUi.kt",
                "/CouliIcons.kt",
                "/MeDraftTexts.kt",
                "/RiskAppealParts.kt",
                "tools/ci/verify.sh",
                "guard-baseline.json",
            )
        assertTrue("本任务允许改的文件不得整文件冻结（另有判据）", files.keys.none { path -> allowed.any { path.endsWith(it) } })
    }

    @Test fun protectedFilesAreByteIdentical() {
        val changed =
            protectedFiles.filter { (path, expected) ->
                val file = File(root, path)
                !file.isFile || sha(file.readBytes()) != expected
            }
        assertTrue("受保护文件被改动：${changed.keys}", changed.isEmpty())
    }

    // ---------------------------------------------------------------- RiskNotice.kt

    @Test fun riskNoticeControllerAndConfigAreUnchanged() {
        assertEquals(
            "RiskNotice.kt 1–310（RiskState / RiskNoticeController / RiskNoticeConfig）零改动（import 除外）",
            base.text("riskHead"),
            head("RiskNotice.kt", "RiskNoticeConfig"),
        )
    }

    @Test fun riskNoticeScreenHeadIsUnchanged() {
        assertEquals(
            "RiskNotice.kt 319–336（执行器、bindLeave、OnResumed、状态 / 闸门收集、model / banned / onBack）逐字节",
            base.text("riskScreenHead"),
            segment(
                "RiskNotice.kt",
                "fun RiskNoticeScreen(config: RiskNoticeConfig) {",
                "    val onBack = if (model != null && model.showBack) config.onBack else null",
            ),
        )
    }

    @Test fun riskLogoutDialogOnlyAddsTitleAlignAndScrim() {
        assertEquals("退出确认框（390–402）只许多 titleAlign / scrim 两个实参", base.text("riskDialog"), dialog("RiskNotice.kt"))
    }

    @Test fun riskPrimaryKeyMappingAndCallbackAreVerbatim() {
        assertEquals("主按钮键映射与 onClick（349–360，去空白后）恰一次", 1, count(squeezed("RiskNotice.kt"), base.text("riskPrimary")))
    }

    // ---------------------------------------------------------------- Appeal.kt

    @Test fun appealControllerAndConfigAreUnchanged() {
        assertEquals(
            "Appeal.kt 1–379（AppealState / AppealController / AppealConfig）零改动（import 除外）",
            base.text("appealHead"),
            head("Appeal.kt", "AppealConfig"),
        )
    }

    @Test fun appealScreenHeadIsUnchanged() {
        assertEquals(
            "Appeal.kt 388–404（执行器、bindLeave、OnResumed、state、两个闸门、layout、content）逐字节",
            base.text("appealScreenHead"),
            segment("Appeal.kt", "fun AppealScreen(config: AppealConfig) {", "    val content = state.phase == AppealState.CONTENT"),
        )
    }

    @Test fun appealLogoutDialogOnlyAddsTitleAlignAndScrim() {
        assertEquals("退出确认框（454–466）只许多 titleAlign / scrim 两个实参", base.text("appealDialog"), dialog("Appeal.kt"))
    }

    @Test fun appealSubmitButtonPropertiesAreVerbatim() {
        val src = squeezed("Appeal.kt")
        listOf(
            "onClick={controller.submit(version)}",
            "enabled=AppealForm.body(state.input)!=null&&!submitBlocked",
            "loading=state.submitting",
            "Modifier.fillMaxWidth().testTag(MeTags.APPEAL_SUBMIT)",
        ).forEach { assertEquals("提交按钮属性逐字恰一次：$it", 1, count(src, it)) }
    }

    // ---------------------------------------------------------------- 两页共同

    @Test fun callbackStringsKeepTheirCounts() {
        base.getValue("callbacks").jsonObject.forEach { (file, raw) ->
            val src = squeezed(file)
            raw.jsonObject.forEach { (needle, n) ->
                assertEquals("$file 回调串「$needle」出现次数与改前相等", n.jsonPrimitive.content.toInt(), count(src, needle))
            }
        }
    }

    @Test fun pagesReadNoNewControllerMembers() {
        base.getValue("controllerRefs").jsonObject.forEach { (file, raw) ->
            assertEquals(
                "$file 只用执行器已暴露的成员（不新增入口 / 读取）",
                raw.jsonArray.map { it.jsonPrimitive.content }.toSet(),
                refs(CONTROLLER, File(root, me + file).readText()),
            )
        }
    }

    @Test fun pagesCallTheSamePureRules() {
        base.getValue("ruleRefs").jsonObject.forEach { (file, raw) ->
            assertEquals(
                "$file 的 AppealCopy / RiskLayout / RiskNoticeView / RiskReasonText / AppealView / AppealForm 引用集合与改前相等（防视图另起判定）",
                raw.jsonArray.map { it.jsonPrimitive.content }.toSet(),
                refs(RULES, File(root, me + file).readText()),
            )
        }
    }

    // ---------------------------------------------------------------- 共用件

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
            onlyAddsDefaults(name, raw.jsonArray.map { it.jsonPrimitive.content }, params(source, name))
        }
    }

    @Test fun accountUiOnlyTouchesDeleteAccountLink() {
        val lines = lines(me + "AccountUi.kt")
        val end = lines.indexOf("    androidx.compose.runtime.staticCompositionLocalOf { null }")
        assertTrue("LocalAccountDeps 定义仍在", end >= 0)
        assertEquals(
            "AccountUi.kt 到 LocalAccountDeps（含）去 import 后逐字节（G-R04 / G-R17 只动其后的 DeleteAccountLink / deletionAvailable）",
            base.text("accountUiPrefix"),
            joined(
                noImports(
                    lines.subList(
                        0,
                        end + 1,
                    ),
                ),
            ),
        )
        val source = File(root, me + "AccountUi.kt").readText()
        assertEquals("DeleteAccountLink 只有一个定义", 1, Regex("""\bfun DeleteAccountLink\(""").findAll(source).count())
        onlyAddsDefaults(
            "DeleteAccountLink",
            base.getValue("deleteLinkParams").jsonArray.map {
                it.jsonPrimitive.content
            },
            params(source, "DeleteAccountLink"),
        )
        val squeezed = source.filterNot(Char::isWhitespace)
        assertEquals(
            "去向不变：openDeletion(RISK_NOTICE) 恰一次",
            1,
            count(squeezed, "account.env.openDeletion(com.couli.core.me.account.AccountRaw.RISK_NOTICE)"),
        )
        assertEquals(
            "显示判据仍是路由可用性（同一表达式恰一次）",
            1,
            count(squeezed, "DeleteAccountController.ROUTEin") + count(squeezed, "DeleteAccountController.ROUTE!in"),
        )
        assertEquals("文案仍是 settings.delete_account", 1, count(squeezed, "\"settings.delete_account\""))
    }

    /** G-R06：CouliIcons 原有成员逐字节、只在 object 末尾的「}」之前追加 lock / pauseCircle 两个成员。 */
    @Test fun couliIconsOnlyAppendsLockAndPauseCircle() {
        val lines = lines(ICONS)
        val n =
            base
                .getValue("iconsPrefixLines")
                .jsonPrimitive.content
                .toInt()
        assertEquals("原有成员与文件头逐字节", base.text("iconsPrefix"), joined(lines.subList(0, n)))
        val start = lines.indexOf("object CouliIcons {")
        val end = (start until lines.size).first { lines[it] == "}" }
        assertEquals("object 收尾之后逐字节", base.text("iconsSuffix"), joined(lines.subList(end, lines.size)))
        val added =
            Regex("""^ {4}val ([A-Za-z]+): ImageVector""", RegexOption.MULTILINE)
                .findAll(lines.subList(n, end).joinToString("\n"))
                .map { it.groupValues[1] }
                .toSet()
        assertTrue("追加段只放 lock / pauseCircle：$added", added.isEmpty() || added == setOf("lock", "pauseCircle"))
        assertEquals(
            "原有成员不删不改名",
            base
                .getValue("iconsMembers")
                .jsonArray
                .map { it.jsonPrimitive.content }
                .toSet(),
            members(lines.subList(0, n)),
        )
    }

    @Test fun draftTextsOnlyChangeTheAllowedLines() {
        val allowed = base.getValue("draftAllowedKeys").jsonArray.map { it.jsonPrimitive.content }
        val kept = lines(DRAFT).filterNot { line -> allowed.any { line.contains("\"$it\" to") } || line.trim().startsWith("// APP-12w-c") }
        assertEquals("MeDraftTexts.kt 除 G-R09 五行与 G-R14 / G-R17 新增行外逐字节（键名不变）", base.text("draftOutsideAllowed"), joined(kept))
        allowed.forEach { key -> assertTrue("每个允许的键至多一行：$key", lines(DRAFT).count { it.contains("\"$key\" to") } <= 1) }
    }

    /** G-R02 / D10-403 ①：两份已合并守卫基线只换清单内文件的 h 与 draftWithoutSafety，不删项、不改 base、不动其余。 */
    @Test fun mergedGuardBaselinesOnlySwapTheListedHashes() {
        val swap =
            base
                .getValue("swappable")
                .jsonArray
                .map { it.jsonPrimitive.content }
                .toSet()
        assertEquals("settings-visual 基线只换清单内的 h", base.text("settingsGuardNormalized"), normalized(SETTINGS_GUARD, swap))
        assertEquals("privacy-visual 基线只换清单内的 h 与 draftWithoutSafety", base.text("privacyGuardNormalized"), normalized(PRIVACY_GUARD, swap))
    }

    // ---------------------------------------------------------------- 算法（与生成脚本逐项一致）

    private fun onlyAddsDefaults(
        name: String,
        old: List<String>,
        now: List<String>,
    ) {
        var cursor = 0
        old.forEach { param ->
            val found = (cursor until now.size).firstOrNull { now[it] == param }
            assertTrue("$name：原参数「$param」须原样（含默认值）保留并保持顺序；现为 $now", found != null)
            cursor = found!! + 1
        }
        (now - old.toSet()).forEach { added -> assertTrue("$name：新增参数必须带默认值：$added", added.contains("=")) }
        val last = old.last()
        if (isTrailingLambda(last)) assertEquals("$name：尾随 lambda 必须仍在最后", last, now.last())
    }

    private fun normalized(
        path: String,
        swap: Set<String>,
    ): String {
        val text = File(root, path).readText()
        val out =
            text.split("\n").map { raw ->
                var line = raw
                ITEM.find(line)?.let { m ->
                    if (m.groupValues[1] in swap) {
                        line =
                            line.substring(0, m.range.first) + "{\"h\": \"*\", \"p\": \"" + m.groupValues[1] + "\"}" +
                            line.substring(m.range.last + 1)
                    }
                }
                if (line.startsWith(" \"draftWithoutSafety\": ")) line = " \"draftWithoutSafety\": \"*\","
                line
            }
        return sha(out.joinToString("\n").toByteArray())
    }

    private fun members(lines: List<String>): Set<String> =
        Regex("""^ {4}val ([A-Za-z]+): ImageVector""", RegexOption.MULTILINE)
            .findAll(lines.joinToString("\n"))
            .map { it.groupValues[1] }
            .toSet()

    private fun head(
        file: String,
        config: String,
    ): String {
        val lines = lines(me + file)
        val start = lines.indexOfFirst { it.startsWith("class $config(") }
        assertTrue("$config 仍在 $file", start >= 0)
        val end = (start until lines.size).first { lines[it] == ")" }
        return joined(noImports(lines.subList(0, end + 1)))
    }

    private fun segment(
        file: String,
        first: String,
        last: String,
    ): String {
        val lines = lines(me + file)
        val start = lines.indexOf(first)
        val end = lines.indexOf(last)
        assertTrue("$file 行段锚点仍在：$start / $end", start >= 0 && end > start)
        return joined(lines.subList(start, end + 1))
    }

    private fun dialog(file: String): String {
        val lines = lines(me + file)
        val start = lines.indexOf("    val dialog = state.logoutDialog")
        assertTrue("确认框块仍在 $file 的页面函数里", start >= 0)
        val end = (start + 1 until lines.size).first { lines[it] == "    }" }
        return joined(lines.subList(start, end + 1).filterNot { it.trim().startsWith("titleAlign =") || it.trim().startsWith("scrim =") })
    }

    private fun refs(
        regex: Regex,
        source: String,
    ): Set<String> = regex.findAll(source).map { it.groupValues[1] + "." + it.groupValues[2] }.toSet()

    private fun squeezed(file: String) = File(root, me + file).readText().filterNot(Char::isWhitespace)

    private fun count(
        haystack: String,
        needle: String,
    ): Int {
        var n = 0
        var from = haystack.indexOf(needle)
        while (from >= 0) {
            n++
            from = haystack.indexOf(needle, from + needle.length)
        }
        return n
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
        const val MIN_FILES = 1200
        const val ICONS = "core/designsystem/src/main/kotlin/com/couli/core/designsystem/CouliIcons.kt"
        const val DRAFT = "core/me/src/main/kotlin/com/couli/core/me/MeDraftTexts.kt"
        const val SETTINGS_GUARD = "app/src/test/resources/settings-visual/guard-baseline.json"
        const val PRIVACY_GUARD = "app/src/test/resources/privacy-visual/guard-baseline.json"
        val SHA256 = Regex("^[0-9a-f]{64}$")
        val ITEM = Regex("""\{"h": "[0-9a-f]{64}", "p": "([^"]+)"\}""")
        val CONTROLLER = Regex("""\b(controller)(?:\.|::)([A-Za-z]+)""")
        val RULES = Regex("""\b(AppealCopy|RiskLayout|RiskNoticeView|RiskReasonText|AppealView|AppealForm)(?:\.|::)([A-Za-z_]+)""")
        val RECEIVER = Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*\\.(?=\\()")
        val BLOCK_COMMENT = Regex("/\\*[\\s\\S]*?\\*/")
        val LINE_COMMENT = Regex("//[^\\n]*")
    }
}
