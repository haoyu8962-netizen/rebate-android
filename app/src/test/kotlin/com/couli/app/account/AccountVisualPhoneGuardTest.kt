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
 * APP-13v-b（换号 + 绑号）逻辑零改动守卫（brief-android §5、gaps G-D02 / G-D03 / G-D16；裁定 D10-407 ①②）。基线
 * app/src/test/resources/account-visual-b/guard-baseline.json 由测试作者在底版 20b8f00（已含 APP-13v-a）生成（NATIVE/APP-13v/tests-b-android-guard-gen.py，
 * 算法与本文件逐项一致）；`files` 为 [{"h": sha256, "p": 路径}] 数组。
 * - 整文件 sha256：底版全部已跟踪文件，除本段允许改的 PhoneChangePage.kt、BindPhoneScreen.kt、LoginDraftTexts.kt、AccountVisualParts.kt、
 *   stepup-v 换号宿主图、fixture、tools/ci/verify.sh 与四份已合并守卫基线（各有判据）。含 a 段全部测试与 account-v 37+4 张基准、AccountDraftTexts.kt。
 * - 行段（按内容锚点，去 import）：PhoneChangePage.kt 文件头到「验证码已发送」LaunchedEffect 收尾、pendingDesc 到 phoneSummary 收尾；
 *   BindPhoneScreen.kt 文件头到 BindPhoneScreen 收尾（1–893，含 BindPhoneViewModel 全部）、BindPhoneGuideDialog、MergeTarget、pendingGuideLabel（表达式段）、
 *   BindPendingCard 执行段（签名到 desc 选句；外观段放开，测试 v2 T1；基线经 NATIVE/APP-13v/tests-b-android-v2-guard-gen.py 在底版自算补丁）。
 * - 回调 / 判定 / 文案键串（去空白）出现次数与改前相等；执行器 / 视图模型成员引用集合、纯规则引用集合与改前相等（防视图另起判定、不新增入口）。
 * - LoginDraftTexts.kt：除 G-D17 两键（与 // APP-13v 注释）外逐字节；AccountVisualParts.kt：底版非 import 行按原顺序全部保留（只许插入新行）。
 * - 四份已合并守卫基线（settings / privacy / risk / account-visual）只换清单内 h（G-D02 / D10-407 ②），risk 两个归一化值与 account-visual 三个
 *   mergedNormalized 值另许按原算法重算（由各自守卫核对），其余逐字节；被换的 h 只能是底版值或该文件当前 sha256。
 */
@Suppress("TooManyFunctions")
class AccountVisualPhoneGuardTest {
    private val root = AccountVisualFixtures.root
    private val base: JsonObject =
        Json
            .parseToJsonElement(
                requireNotNull(javaClass.classLoader?.getResource("account-visual-b/guard-baseline.json")) { "缺守卫基线" }.readText(),
            ).jsonObject

    private val protectedFiles: Map<String, String> by lazy {
        val items = base.getValue("files").jsonArray.map { it.jsonObject }
        items.forEach { assertEquals("基线 files 每项只许 h / p：$it", setOf("h", "p"), it.keys) }
        val pairs = items.map { it.text("p") to it.text("h") }
        pairs.forEach { (p, h) -> assertTrue("64 位小写十六进制：$p", SHA256.matches(h)) }
        val map = pairs.toMap()
        assertEquals("路径不重复", pairs.size, map.size)
        map
    }

    @Test fun baselineIsFor20b8f00AndCoversTheRedLineFiles() {
        assertEquals("20b8f00ebcb88fad4645e89673aa337d046e1760", base.text("base"))
        val files = protectedFiles
        assertTrue("清单不得被清空", files.size >= MIN_FILES)
        REQUIRED.forEach { assertTrue("必须冻结：$it", it in files) }
        ALLOWED.forEach { allowed -> assertTrue("本段允许改的不得整文件冻结：$allowed", files.keys.none { it == allowed }) }
        assertTrue("a 段基准图仍冻结", files.keys.any { it.startsWith("app/src/test/snapshots/account-v/DeleteAccount") })
        assertTrue("本段新增测试不进清单", files.keys.none { it.contains("AccountVisualPhone") || it.contains("PhoneAccountVisual") })
    }

    @Test fun protectedFilesAreByteIdentical() {
        val changed = protectedFiles.filter { (p, h) -> !File(root, p).isFile || sha(File(root, p).readBytes()) != h }
        assertTrue("受保护文件被改动：${changed.keys}", changed.isEmpty())
    }

    // ---------------------------------------------------------------- PhoneChangePage.kt

    @Test fun phonePageHeadIsUnchanged() {
        val lines = lines(PAGE)
        val start = lines.indexOf("    LaunchedEffect(state.sentNotice?.id) {")
        assertTrue("「验证码已发送」LaunchedEffect 仍在", start >= 0)
        val end = (start until lines.size).first { lines[it] == "    }" }
        assertEquals(
            "PhoneChangePage.kt 1–87（状态 / 半屏 / 闸门收集、version / formGen、倒计时 tick、BackHandler、可见性、待确认刷新、已发送播报）去 import 后逐字节",
            base.text("phoneHead"),
            joined(noImports(lines.subList(0, end + 1))),
        )
    }

    @Test fun phoneSummaryRulesAreUnchanged() {
        val lines = lines(PAGE)
        val start = lines.indexOf("internal fun pendingDesc(")
        val summary = lines.indexOf("internal fun phoneSummary(")
        assertTrue("锚点仍在：$start / $summary", start >= 0 && summary > start)
        val end = (summary until lines.size).first { lines[it] == "}" }
        assertEquals("pendingDesc / phoneSummary（不列当前号码 G03）逐字节", base.text("phoneRules"), joined(lines.subList(start, end + 1)))
    }

    @Test fun phoneCallbackAndConditionStringsKeepTheirCounts() {
        val src = squeezed(PAGE)
        base.getValue("phoneCallbacks").jsonObject.forEach { (needle, n) ->
            assertEquals("PhoneChangePage.kt「$needle」出现次数与改前相等", n.jsonPrimitive.content.toInt(), count(src, needle))
        }
    }

    @Test fun phonePageReadsNoNewControllerMembersOrRules() {
        val text = File(root, PAGE).readText()
        assertEquals("执行器成员集合与改前相等", set("phoneControllerRefs"), refs(CONTROLLER, text))
        assertEquals("纯规则引用集合与改前相等", set("phoneRuleRefs"), refs(PHONE_RULES, text))
    }

    // ---------------------------------------------------------------- BindPhoneScreen.kt

    @Test fun bindHeadIsUnchanged() {
        val lines = lines(BIND)
        val start = lines.indexOfFirst { it.startsWith("fun BindPhoneScreen(") }
        assertTrue("BindPhoneScreen 仍在", start >= 0)
        val end = (start until lines.size).first { lines[it] == "}" }
        assertEquals(
            "BindPhoneScreen.kt 1–893（标记、配置、状态、BindPhoneViewModel、BindPhoneScreen）去 import 后逐字节",
            base.text("bindHead"),
            joined(noImports(lines.subList(0, end + 1))),
        )
    }

    @Test fun bindGuideMergeTargetAndGuideLabelAreUnchanged() {
        val lines = lines(BIND)
        mapOf(
            "bindGuide" to "fun BindPhoneGuideDialog(",
            "bindMergeTarget" to "private fun MergeTarget(",
            "bindGuideLabel" to "private fun pendingGuideLabel(",
        ).forEach { (field, signature) ->
            val start = lines.indexOfFirst { it.startsWith(signature) }
            assertTrue("$signature 仍在", start >= 0)
            // 测试 v2 T1：表达式体（签名行以 = 结尾）取到第一个空行之前；v1 一律取到第一个列 0 的 }，把 pendingGuideLabel 之后的整张待确认卡
            // （G-D18 / D38 要改的外观）一并冻住了。块体函数照旧取到列 0 的 }。
            val end =
                if (lines[start].trimEnd().endsWith("=")) {
                    (start until lines.size).first { lines[it].isEmpty() } - 1
                } else {
                    (start until lines.size).first { lines[it] == "}" }
                }
            assertEquals("$signature 逐字节（登录引导弹窗 / 并号信息块 / 补办入口选键原样）", base.text(field), joined(lines.subList(start, end + 1)))
        }
    }

    /**
     * 测试 v2 T1：待确认卡只冻执行段（照 a 段 proof 口径），外观段放开（G-D18 / D38 改白卡写法）。执行段 = 签名行到 `val desc =` 块收尾：
     * 原键取值与 `?: return`、`usable` 条件、说明选句逐字节；卡内与放弃框的回调 / 条件串计数在 bindCallbacks（enabled = usable ×2、四个 onClick、
     * 补办入口、提示、放弃框 onConfirm / onDismiss / onDismissRequest、confirmEnabled、dismissOnClickOutside = false），视图模型成员集合见下一条。
     */
    @Test fun bindPendingCardExecutorHeadIsUnchanged() {
        val lines = lines(BIND)
        val start = lines.indexOfFirst { it.startsWith("private fun BindPendingCard(") }
        assertTrue("BindPendingCard 仍在", start >= 0)
        val desc = (start until lines.size).first { lines[it].startsWith("    val desc =") }
        val end = (desc until lines.size).first { lines[it] == "        }" }
        assertEquals("BindPendingCard 签名 → key / usable / desc 逐字节", base.text("bindPendingHead"), joined(lines.subList(start, end + 1)))
    }

    @Test fun bindCallbackAndConditionStringsKeepTheirCounts() {
        val src = squeezed(BIND)
        base.getValue("bindCallbacks").jsonObject.forEach { (needle, n) ->
            assertEquals("BindPhoneScreen.kt「$needle」出现次数与改前相等", n.jsonPrimitive.content.toInt(), count(src, needle))
        }
    }

    @Test fun bindReadsNoNewModelMembersOrRules() {
        val text = File(root, BIND).readText()
        assertEquals("视图模型成员集合与改前相等", set("bindModelRefs"), refs(MODEL, text))
        assertEquals("纯规则引用集合与改前相等", set("bindRuleRefs"), refs(BIND_RULES, text))
    }

    // ---------------------------------------------------------------- LoginDraftTexts.kt / AccountVisualParts.kt

    @Test fun loginDraftTextsOnlyGainTheTwoG17Keys() {
        val allowed = base.getValue("loginDraftAllowed").jsonArray.map { it.jsonPrimitive.content }
        val all = lines(LDRAFT)
        val kept = mutableListOf<String>()
        var skip = false
        all.forEach { line ->
            val trimmed = line.trim()
            when {
                skip -> skip = false
                allowed.any { line.contains("\"$it\" to") } -> skip = trimmed.endsWith(" to")
                trimmed.startsWith("// APP-13v") -> Unit
                else -> kept += line
            }
        }
        assertEquals("LoginDraftTexts.kt 除 G-D17 两键与「// APP-13v」注释外逐字节", base.text("loginDraftOutside"), joined(kept))
        allowed.forEach { key -> assertTrue("每键至多一处：$key", all.count { it.contains("\"$key\" to") } <= 1) }
    }

    @Test fun accountVisualPartsOnlyGainNewLines() {
        val want = base.getValue("partsLines").jsonArray.map { it.jsonPrimitive.content }
        val have = noImports(lines(PARTS)).map { sha(it.toByteArray()).take(LINE_HASH) }
        var i = 0
        have.forEach { if (i < want.size && it == want[i]) i++ }
        assertEquals("AccountVisualParts.kt 底版非 import 行按原顺序全部保留（a 段共用件只许追加，不改不删）", want.size, i)
    }

    // ---------------------------------------------------------------- 已合并守卫基线（G-D02 / D10-407 ②）

    @Test fun mergedGuardBaselinesOnlySwapTheListedHashes() {
        val swapB = list("swappableB").toSet()
        val swapAV = list("swappableAccountVisual").toSet()
        assertEquals("settings / privacy / risk 只许换 2 项（PhoneChangePage.kt、换号宿主图）", 2, swapB.size)
        assertEquals("account-visual 只许换 3 项（PhoneChangePage.kt、BindPhoneScreen.kt、LoginDraftTexts.kt）", 3, swapAV.size)
        val swapBase = base.getValue("swapBase").jsonArray.associate { it.jsonObject.text("p") to it.jsonObject.text("h") }
        base.getValue("mergedB").jsonArray.map { it.jsonObject }.forEach { item ->
            val path = item.text("p")
            val av = path.endsWith("account-visual/guard-baseline.json")
            val swap = if (av) swapAV else swapB
            assertEquals("$path 只换清单内的 h（另许归一化值按原算法重算）", item.text("h"), normalized(path, swap, av))
            val text = File(root, path).readText()
            swap.forEach { p ->
                assertEquals("$path 仍恰有一项 $p（不删项）", 1, count(text, "\"p\": \"$p\"}"))
                val h =
                    Regex("""\{"h": "([0-9a-f]{64})", "p": "${Regex.escape(p)}"\}""")
                        .find(text)
                        ?.groupValues
                        ?.get(1)
                val now = sha(File(root, p).readBytes())
                assertTrue("$path 的 $p 只能是底版值或当前文件值：$h", h == swapBase[p] || h == now)
            }
        }
    }

    /** G-D06：换号宿主图由实现方在最终产品代码上重录（页面改版必变），半屏区域逐像素不变（a 段同算法、同底版值）。 */
    @Test fun hostScreenshotKeepsTheSheetPixelIdentical() {
        base.getValue("hostSheetCrops").jsonArray.map { it.jsonObject }.forEach { item ->
            val path = item.text("p")
            val image = requireNotNull(javax.imageio.ImageIO.read(File(root, path))) { "读不出 $path" }
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
            assertEquals("$path 半屏上沿不变", item.text("top").toInt(), top)
            assertEquals("$path 半屏区域逐像素不变", item.text("h"), digest.digest().joinToString("") { "%02x".format(it) })
        }
    }

    /** G-D15：fixture 只追加 b 段组 segmentB，a 段各组逐字节不变。 */
    @Test fun fixtureKeepsSegmentAByteIdentical() {
        val text = File(root, "specs/account-visual-vectors.json").readText()
        val cut = text.indexOf(",\n \"segmentB\": ")
        assertTrue("segmentB 追加在文件尾", cut > 0)
        assertEquals("a 段部分逐字节", base.text("fixtureAPart"), sha(text.substring(0, cut).toByteArray()))
    }

    // ---------------------------------------------------------------- 算法（与生成脚本逐项一致）

    private fun normalized(
        path: String,
        swap: Set<String>,
        maskMergedBlock: Boolean,
    ): String {
        var inBlock = false
        val out =
            File(root, path).readText().split("\n").map { raw ->
                var line = raw
                ITEM.find(line)?.let { m ->
                    if (m.groupValues[1] in swap) {
                        line =
                            line.substring(0, m.range.first) + "{\"h\": \"*\", \"p\": \"" + m.groupValues[1] + "\"}" +
                            line.substring(m.range.last + 1)
                    }
                }
                line = NORM.replace(line) { it.groupValues[1] + "\"*\"" }
                if (maskMergedBlock) {
                    when {
                        line.startsWith(" \"mergedNormalized\": [") -> inBlock = true
                        inBlock && line.startsWith(" ]") -> inBlock = false
                        inBlock -> line = HLINE.replace(line) { it.groupValues[1] + "\"*\"" + it.groupValues[2] }
                    }
                }
                line
            }
        return sha(out.joinToString("\n").toByteArray())
    }

    private fun refs(
        regex: Regex,
        source: String,
    ): Set<String> = regex.findAll(source).map { it.groupValues[1] + "." + it.groupValues[2] }.toSet()

    private fun set(name: String): Set<String> = list(name).toSet()

    private fun list(name: String): List<String> = base.getValue(name).jsonArray.map { it.jsonPrimitive.content }

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
        const val MIN_FILES = 2800
        const val LINE_HASH = 16
        const val ME = "feature/me/src/main/kotlin/com/couli/feature/me/"
        const val LOGIN = "feature/login/src/main/kotlin/com/couli/feature/login/"
        const val PAGE = ME + "PhoneChangePage.kt"
        const val PARTS = ME + "AccountVisualParts.kt"
        const val BIND = LOGIN + "BindPhoneScreen.kt"
        const val LDRAFT = "app/src/main/kotlin/com/couli/app/login/LoginDraftTexts.kt"
        val ALLOWED =
            listOf(
                PAGE,
                PARTS,
                BIND,
                LDRAFT,
                "app/src/test/snapshots/stepup-v/WithdrawStepUp-v2-host-phone-change-light.png",
                "specs/account-visual-vectors.json",
                "tools/ci/verify.sh",
                "app/src/test/resources/settings-visual/guard-baseline.json",
                "app/src/test/resources/privacy-visual/guard-baseline.json",
                "app/src/test/resources/risk-visual/guard-baseline.json",
                "app/src/test/resources/account-visual/guard-baseline.json",
            )
        val REQUIRED =
            listOf(
                ME + "PhoneChangeController.kt",
                ME + "DeleteAccountController.kt",
                ME + "DeleteAccountScreen.kt",
                ME + "AccountUi.kt",
                ME + "AccountPorts.kt",
                ME + "Settings.kt",
                ME + "MeUi.kt",
                ME + "RiskAppealParts.kt",
                LOGIN + "BindPhoneService.kt",
                LOGIN + "LoginFlow.kt",
                "core/me/src/main/kotlin/com/couli/core/me/account/AccountDraftTexts.kt",
                "core/me/src/main/kotlin/com/couli/core/me/account/PhoneChangePolicies.kt",
                "core/wallet/src/main/kotlin/com/couli/core/wallet/stepup/StepUpSheet.kt",
                "core/wallet/src/main/kotlin/com/couli/core/wallet/stepup/StepUpController.kt",
                "core/designsystem/src/main/kotlin/com/couli/core/designsystem/CouliInputs.kt",
                "core/designsystem/src/main/kotlin/com/couli/core/designsystem/FeedbackComponents.kt",
                "core/designsystem/src/main/kotlin/com/couli/core/designsystem/CouliIcons.kt",
                "app/src/test/kotlin/com/couli/app/account/AccountHarness.kt",
                "app/src/test/kotlin/com/couli/app/account/AccountVisualHarness.kt",
                "app/src/test/kotlin/com/couli/app/account/AccountVisualGuardTest.kt",
                "app/src/test/kotlin/com/couli/app/account/AccountVisualSourceContractTest.kt",
                "app/src/test/kotlin/com/couli/app/account/PhoneChangeIntegrationTest.kt",
                "app/src/test/kotlin/com/couli/app/account/StepUpVisualAccountTest.kt",
                "app/src/test/kotlin/com/couli/app/account/AccountFollowupAnd0717Test.kt",
                "app/src/test/kotlin/com/couli/app/login/AppOAuthBindPhoneTest.kt",
                "app/src/test/resources/stepup-visual/funds-baseline.json",
                "feature/login/src/test/kotlin/com/couli/feature/login/BindPhoneScreenTest.kt",
                "feature/login/src/test/kotlin/com/couli/feature/login/BindPhoneLateOwnerTest.kt",
                "specs/account-vectors.json",
                "contract.lock",
            )
        val SHA256 = Regex("^[0-9a-f]{64}$")
        val ITEM = Regex("""\{"h": "[0-9a-f]{64}", "p": "([^"]+)"\}""")
        val NORM = Regex("""^( "(?:settings|privacy)GuardNormalized": )"[0-9a-f]{64}"""")
        val HLINE = Regex("""^(\s*"h": )"[0-9a-f]{64}"(\s*)$""")
        val CONTROLLER = Regex("""\b(controller)(?:\.|::)([A-Za-z]+)""")
        val MODEL = Regex("""\b(model)(?:\.|::)([A-Za-z]+)""")
        val PHONE_RULES =
            Regex("""\b(PhoneChangeState|PhoneChangeOutcome|PhoneChangeController|DeletionSummary|PhoneMask)(?:\.|::)([A-Za-z_]+)""")
        val BIND_RULES = Regex("""\b(PhoneNumber|CodeInput|BindPhoneDialog|DeletionSummary)(?:\.|::)([A-Za-z_]+)""")
    }
}
