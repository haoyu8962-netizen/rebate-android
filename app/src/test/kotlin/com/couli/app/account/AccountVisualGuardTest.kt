package com.couli.app.account

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
 * APP-13v-a 逻辑零改动守卫（brief-android §5、gaps G-D02 / G-D16）。基线 app/src/test/resources/account-visual/guard-baseline.json 由测试作者在底版
 * a9fc8ae 生成（生成脚本 NATIVE/APP-13v/tests-a-android-guard-gen.py，算法与本文件逐项一致）；`files` 为 [{"h": sha256, "p": 路径}] 数组。
 * - 整文件 sha256：core 全部（AccountDraftTexts.kt 另判）、feature/me 全部源码（DeleteAccountScreen.kt 另判；新文件 AccountVisualParts.kt 不在清单）、
 *   feature/login、feature/wallet、app/src/main/kotlin、contracts/snapshot、contract.lock、specs（新 fixture 除外）、
 *   保护测试（app/…/account 既有全部、app/…/login、wallet/visual/StepUpVisual*、SettingsVisual* / PrivacyVisual* / RiskVisual*、feature/login 测试）、
 *   测试资源（三份已合并守卫基线另判）、全部既有快照目录（stepup-v 两张宿主图除外，G-D02 重录）。
 * - 行段（按内容锚点，不按行号）：DeleteAccountScreen.kt 文件头到 `val back = {…}` 收尾（去 import）、deletionSummary 到 guideLabel 收尾，逐字节。
 * - 回调 / 判定表达式（去空白）出现次数与改前相等；执行器成员引用集合、纯规则引用集合与改前相等（防视图另起判定、不新增入口）。
 * - AccountDraftTexts.kt：除 G-D07 五个值、G-D12 新键与 FUND_TERMS 里的新键一行外逐字节（键名不变）。
 * - 三份已合并守卫基线（settings / privacy / risk）各只许换 a 段 4 项的 h（基线 mergedSwapA，测试 v2 由 3 / 5 更正），risk 另许两个归一化值按原算法重算（G-D02 / D10-403 ①），其余逐字节。
 * - b 段（换号 / 绑号）要改的 PhoneChangePage.kt、BindPhoneScreen.kt、LoginDraftTexts.kt 在本基线里整文件冻结：a 段不得碰；b 段按同法只换这几项的 h（G-D01）。
 */
@Suppress("TooManyFunctions")
class AccountVisualGuardTest {
    private val root = AccountVisualFixtures.root
    private val base: JsonObject =
        Json
            .parseToJsonElement(
                requireNotNull(javaClass.classLoader?.getResource("account-visual/guard-baseline.json")) { "缺守卫基线" }.readText(),
            ).jsonObject

    private val protectedFiles: Map<String, String> by lazy {
        val items = base.getValue("files").jsonArray.map { it.jsonObject }
        items.forEach { assertEquals("基线 files 每项只许 h / p 两个字段：$it", setOf("h", "p"), it.keys) }
        val pairs = items.map { it.text("p") to it.text("h") }
        pairs.forEach { (path, hash) -> assertTrue("基线哈希须为 64 位小写十六进制：$path", SHA256.matches(hash)) }
        val map = pairs.toMap()
        assertEquals("基线 files 路径不得重复", pairs.size, map.size)
        map
    }

    @Test fun baselineIsForA9fc8aeAndCoversTheRedLineFiles() {
        assertEquals("a9fc8ae19d32fe37643145ef2e2ac5d8bf37208f", base.text("base"))
        val files = protectedFiles
        assertTrue("清单不得被清空", files.size >= MIN_FILES)
        REQUIRED.forEach { assertTrue("必须冻结：$it", it in files) }
        listOf(
            "core/network/",
            "core/wallet/",
            "core/designsystem/",
            "feature/wallet/",
            "contracts/snapshot/",
            "app/src/test/snapshots/ds02/",
            "app/src/test/snapshots/me-v/",
            "app/src/test/snapshots/settings-v/",
            "app/src/test/snapshots/privacy-v/",
            "app/src/test/snapshots/risk-v/",
            "app/src/test/snapshots/stepup-v/",
            "app/src/test/snapshots/wallet-v/",
            "app/src/test/snapshots/withdraw-v/",
            "app/src/test/snapshots/payout-v/",
        ).forEach { dir -> assertTrue("$dir 在冻结清单", files.keys.any { it.startsWith(dir) }) }
        val allowed =
            listOf(
                "/DeleteAccountScreen.kt",
                "/AccountVisualParts.kt",
                "/AccountDraftTexts.kt",
                "WithdrawStepUp-v2-host-deletion-light.png",
                "WithdrawStepUp-v2-host-phone-change-light.png",
                "tools/ci/verify.sh",
                "guard-baseline.json",
                "account-visual-vectors.json",
            )
        assertTrue("本任务允许改的文件不得整文件冻结（另有判据）", files.keys.none { path -> allowed.any { path.endsWith(it) } })
        assertTrue("本任务新增的测试不进冻结清单", files.keys.none { it.contains("/AccountVisual") || it.contains("/account-v/") })
    }

    @Test fun protectedFilesAreByteIdentical() {
        val changed =
            protectedFiles.filter { (path, expected) ->
                val file = File(root, path)
                !file.isFile || sha(file.readBytes()) != expected
            }
        assertTrue("受保护文件被改动：${changed.keys}", changed.isEmpty())
    }

    // ---------------------------------------------------------------- DeleteAccountScreen.kt

    @Test fun pageHeadIsUnchanged() {
        val lines = lines(PAGE)
        val back = lines.indexOf("    val back = {")
        assertTrue("`val back = {` 仍在", back >= 0)
        val end = (back until lines.size).first { lines[it] == "    }" }
        assertEquals(
            "DeleteAccountScreen.kt 1–96（配置、身份、来源交接、执行器、bindLeave、生命周期、状态与闸门、读闸门重取、version、view、back）去 import 后逐字节",
            base.text("pageHead"),
            joined(noImports(lines.subList(0, end + 1))),
        )
    }

    @Test fun summaryAndGuideRulesAreUnchanged() {
        val lines = lines(PAGE)
        val start = lines.indexOf("internal fun deletionSummary(")
        val guide = lines.indexOf("private fun guideLabel(route: String): String =")
        assertTrue("deletionSummary / guideLabel 锚点仍在：$start / $guide", start >= 0 && guide > start)
        val end = (guide until lines.size).first { lines[it] == "    }" }
        assertEquals("deletionSummary 到 guideLabel 收尾（197–210）逐字节", base.text("pageRules"), joined(lines.subList(start, end + 1)))
    }

    @Test fun callbackAndConditionStringsKeepTheirCounts() {
        val src = squeezed(PAGE)
        base.getValue("callbacks").jsonObject.forEach { (needle, n) ->
            assertEquals("DeleteAccountScreen.kt 回调 / 条件串「$needle」出现次数与改前相等", n.jsonPrimitive.content.toInt(), count(src, needle))
        }
    }

    @Test fun pageReadsNoNewControllerMembers() {
        assertEquals(
            "DeleteAccountScreen.kt 只用执行器已暴露的同一组成员（不新增入口 / 读取）",
            base
                .getValue("controllerRefs")
                .jsonArray
                .map { it.jsonPrimitive.content }
                .toSet(),
            refs(CONTROLLER, File(root, PAGE).readText()),
        )
    }

    @Test fun pageCallsTheSamePureRules() {
        assertEquals(
            "DeleteAccountScreen.kt 的 DeletionView / DeletionPresentation / DeletionSummary / AccountRaw 引用集合与改前相等（防视图另起判定）",
            base
                .getValue("ruleRefs")
                .jsonArray
                .map { it.jsonPrimitive.content }
                .toSet(),
            refs(RULES, File(root, PAGE).readText()),
        )
    }

    // ---------------------------------------------------------------- AccountDraftTexts.kt

    @Test fun draftTextsOnlyChangeTheAllowedLines() {
        val allowed = base.getValue("draftAllowedKeys").jsonArray.map { it.jsonPrimitive.content }
        val all = lines(DRAFT)
        val kept = mutableListOf<String>()
        var skip = false
        all.forEach { line ->
            val trimmed = line.trim()
            when {
                skip -> skip = false
                allowed.any { line.contains("\"$it\" to") } -> skip = trimmed.endsWith(" to")
                trimmed == "\"deletion.withdraw_hint.no_withdraw\"," || trimmed.startsWith("// APP-13v") -> Unit
                else -> kept += line
            }
        }
        assertEquals(
            "AccountDraftTexts.kt 除 G-D07 五个值、G-D12 新键（含 FUND_TERMS 一行）与「// APP-13v」注释外逐字节（键名不变）",
            base.text("draftOutsideAllowed"),
            joined(kept),
        )
        allowed.forEach { key -> assertTrue("每个允许的键至多一处定义：$key", all.count { it.contains("\"$key\" to") } <= 1) }
    }

    // ---------------------------------------------------------------- 三份已合并守卫

    /**
     * G-D02 / D10-403 ① / D10-407 ②：settings / privacy / risk 三份已合并基线**各恰换 a 段 4 项**的 h（DeleteAccountScreen.kt、AccountDraftTexts.kt、
     * 两张 stepup-v 宿主图——G-D07 的 phone_change.new_hint 显示在换号第 2 步，换号宿主图在 a 段就变，半屏仍逐像素不变），risk 的两个归一化值按原算法重算；
     * PhoneChangePage.kt 属 b 段，a 段不遮、须是底版值；不删项、不改 base、不动其余。每个被换的 h 只能是底版值（尚未换）或该文件当前的 sha256（换对了），
     * 不许换成任意值。（测试 v2：v1 遮的是 a / b 两段 5 项、交回写「3 项」，都与 a 段实际不符，按实现交回 §6 核实后更正。）
     */
    @Test fun mergedGuardBaselinesOnlySwapTheListedHashes() {
        val swap =
            base
                .getValue("swappable")
                .jsonArray
                .map { it.jsonPrimitive.content }
                .toSet()
        assertEquals("清单只含 a / b 两段允许改的 5 项", 5, swap.size)
        val swapA = base.getValue("mergedSwapA").jsonArray.map { it.jsonObject }
        val swapAPaths = swapA.map { it.text("p") }.toSet()
        assertEquals("a 段恰 4 项", 4, swapAPaths.size)
        assertTrue("a 段 4 项都在两段清单内", swap.containsAll(swapAPaths))
        assertTrue("PhoneChangePage.kt 属 b 段，a 段不换", swapAPaths.none { it.endsWith("PhoneChangePage.kt") })
        base.getValue("mergedNormalized").jsonArray.map { it.jsonObject }.forEach { item ->
            val path = item.text("p")
            assertEquals("$path 只换 a 段 4 项的 h（risk 另含两个归一化值）", item.text("h"), normalized(path, swapAPaths))
            val text = File(root, path).readText()
            swapA.forEach { entry ->
                val p = entry.text("p")
                assertEquals("$path 仍恰有一项 $p（不删项）", 1, count(text, "\"p\": \"$p\""))
                val h =
                    Regex("""\{"h": "([0-9a-f]{64})", "p": "${Regex.escape(p)}"\}""")
                        .find(text)
                        ?.groupValues
                        ?.get(1)
                val now = sha(File(root, p).readBytes())
                assertTrue("$path 的 $p 只能是底版值或当前文件值：$h", h == entry.text("h") || h == now)
            }
        }
    }

    /**
     * G-D02 ③ / G-D06：两张 stepup-v 宿主图由实现方在最终产品代码上重录（页面改版必变），但半屏区域必须逐像素不变——从底部左缘（x = 10px）
     * 向上找半屏白底的上沿，再往下让开 8px 圆角，裁到图底，ARGB 逐像素 sha256 与底版相等。
     */
    @Test fun hostScreenshotsKeepTheSheetPixelIdentical() {
        base.getValue("hostSheetCrops").jsonArray.map { it.jsonObject }.forEach { item ->
            val path = item.text("p")
            val image = javax.imageio.ImageIO.read(File(root, path))
            requireNotNull(image) { "读不出 $path" }
            val ref = image.getRGB(SHEET_X, image.height - 1)
            var y = image.height - 1
            while (y > 0 && image.getRGB(SHEET_X, y - 1) == ref) y--
            val top = y + SHEET_MARGIN
            val digest = MessageDigest.getInstance("SHA-256")
            for (row in top until image.height) {
                for (x in 0 until image.width) {
                    val v = image.getRGB(x, row)
                    digest.update(byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()))
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            assertEquals("$path 半屏上沿位置不变（半屏高度零改动）", item.text("top").toInt(), top)
            assertEquals("$path 半屏区域逐像素不变（宿主页面改版、半屏零改动）", item.text("h"), hash)
        }
    }

    // ---------------------------------------------------------------- 算法（与生成脚本逐项一致）

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
                NORM.replace(line) { it.groupValues[1] + "\"*\"" }
            }
        return sha(out.joinToString("\n").toByteArray())
    }

    private fun refs(
        regex: Regex,
        source: String,
    ): Set<String> = regex.findAll(source).map { it.groupValues[1] + "." + it.groupValues[2] }.toSet()

    private fun squeezed(path: String) = File(root, path).readText().filterNot(Char::isWhitespace)

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

    private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content

    private fun lines(path: String): List<String> {
        val text = File(root, path).readText()
        val all = text.split("\n")
        return if (text.endsWith("\n")) all.dropLast(1) else all
    }

    private fun noImports(lines: List<String>) = lines.filterNot { it.startsWith("import ") }

    private fun joined(lines: List<String>) = sha(lines.joinToString("\n").toByteArray())

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val SHEET_X = 10
        const val SHEET_MARGIN = 8
        const val ME = "feature/me/src/main/kotlin/com/couli/feature/me/"
        val REQUIRED =
            listOf(
                ME + "AccountUi.kt",
                ME + "AccountPorts.kt",
                ME + "DeleteAccountController.kt",
                ME + "PhoneChangeController.kt",
                ME + "PhoneChangePage.kt",
                ME + "Settings.kt",
                ME + "SettingsSubPages.kt",
                ME + "SettingsParts.kt",
                ME + "MeUi.kt",
                ME + "MePorts.kt",
                ME + "RiskNotice.kt",
                ME + "Appeal.kt",
                ME + "RiskAppealParts.kt",
                "core/me/src/main/kotlin/com/couli/core/me/account/DeletionPolicies.kt",
                "core/me/src/main/kotlin/com/couli/core/me/account/PhoneChangePolicies.kt",
                "core/me/src/main/kotlin/com/couli/core/me/account/AccountShared.kt",
                "core/me/src/main/kotlin/com/couli/core/me/account/AccountOutcomeSupport.kt",
                "core/me/src/main/kotlin/com/couli/core/me/account/AccountJson.kt",
                "core/me/src/main/kotlin/com/couli/core/me/account/AccountEntries.kt",
                "core/wallet/src/main/kotlin/com/couli/core/wallet/stepup/StepUpSheet.kt",
                "core/wallet/src/main/kotlin/com/couli/core/wallet/stepup/StepUpController.kt",
                "core/designsystem/src/main/kotlin/com/couli/core/designsystem/CouliInputs.kt",
                "core/designsystem/src/main/kotlin/com/couli/core/designsystem/CouliIcons.kt",
                "core/designsystem/src/main/kotlin/com/couli/core/designsystem/FeedbackComponents.kt",
                "feature/login/src/main/kotlin/com/couli/feature/login/BindPhoneScreen.kt",
                "feature/login/src/main/kotlin/com/couli/feature/login/BindPhoneService.kt",
                "app/src/main/kotlin/com/couli/app/login/LoginDraftTexts.kt",
                "app/src/main/kotlin/com/couli/app/account/AppAccount.kt",
                "app/src/test/kotlin/com/couli/app/account/AccountHarness.kt",
                "app/src/test/kotlin/com/couli/app/account/DeleteAccountIntegrationTest.kt",
                "app/src/test/kotlin/com/couli/app/account/PhoneChangeIntegrationTest.kt",
                "app/src/test/kotlin/com/couli/app/account/AccountEntryIntegrationTest.kt",
                "app/src/test/kotlin/com/couli/app/account/AccountFollowupAnd0717Test.kt",
                "app/src/test/kotlin/com/couli/app/account/StepUpVisualAccountTest.kt",
                "app/src/test/kotlin/com/couli/app/login/AppOAuthBindPhoneTest.kt",
                "app/src/test/kotlin/com/couli/app/me/SettingsVisualGuardTest.kt",
                "app/src/test/kotlin/com/couli/app/me/PrivacyVisualGuardTest.kt",
                "app/src/test/kotlin/com/couli/app/me/PrivacyVisualSourceContractTest.kt",
                "app/src/test/kotlin/com/couli/app/me/RiskVisualGuardTest.kt",
                "app/src/test/resources/stepup-visual/funds-baseline.json",
                "feature/login/src/test/kotlin/com/couli/feature/login/BindPhoneScreenTest.kt",
                "feature/login/src/test/kotlin/com/couli/feature/login/BindPhoneLateOwnerTest.kt",
                "specs/account-vectors.json",
                "specs/me-vectors.json",
                "contract.lock",
            )
        const val MIN_FILES = 2000
        const val PAGE = "feature/me/src/main/kotlin/com/couli/feature/me/DeleteAccountScreen.kt"
        const val DRAFT = "core/me/src/main/kotlin/com/couli/core/me/account/AccountDraftTexts.kt"
        val SHA256 = Regex("^[0-9a-f]{64}$")
        val ITEM = Regex("""\{"h": "[0-9a-f]{64}", "p": "([^"]+)"\}""")
        val NORM = Regex("""^( "(?:settings|privacy)GuardNormalized": )"[0-9a-f]{64}"""")
        val CONTROLLER = Regex("""\b(controller)(?:\.|::)([A-Za-z]+)""")
        val RULES = Regex("""\b(DeletionView|DeletionPresentation|DeletionSummary|AccountRaw)(?:\.|::)([A-Za-z_]+)""")
    }
}
