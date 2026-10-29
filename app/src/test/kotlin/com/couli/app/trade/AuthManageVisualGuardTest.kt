package com.couli.app.trade

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
 * APP-06w 逻辑零改动守卫（brief §5「逻辑零改动证明」、brief-android §5、gaps G-A02 / G-A03 / G-A12；裁定 D10-414 ①②）。基线
 * app/src/test/resources/auth-manage-visual/guard-baseline.json 由测试作者在底版 1dbb163 生成（NATIVE/APP-06w/tests-android-guard-gen.py，算法与本文件逐项一致）；
 * `files` 为 [{"h": sha256, "p": 路径}] 数组。
 * - 整文件 sha256：底版全部已跟踪文件，除本任务允许改的 AuthManage.kt、tools/ci/verify.sh 与五份已合并守卫基线（各有判据）。含执行器 / 授权流程 / 跳转执行器 /
 *   购买流程（TaobaoAuthFlow、TradeServices、TradeJump、OpenPurchaseController、OpenTradeLogic、TradeUi、PurchaseVisualParts）、组件库与网络层、App 接线
 *   （AuthManageRoute、AppTrade、TradeDraftTexts、AppShell）、debugFeatures / release、全部已有测试与基准、specs/open-trade-vectors.json、contract.lock。
 * - AuthManage.kt 文件头 → AuthManageScreen 生命周期 DisposableEffect 收尾（第 1–491 行，去 import）逐字节；ErrorBlock 执行段三行逐字节；
 *   42 个回调 / 判定 / 标记 / 文案选键串（去空白）出现次数与改前相等；整文件执行器成员与配置成员引用集合与改前相等（防视图另起判定、不新增入口）。
 * - 五份已合并守卫基线只许换 AuthManage.kt 那一行 h（settings / privacy / risk / account-visual-b 各一处；account-visual 不含），另许各自归一化值按原算法重算
 *   （risk 的 settings / privacyGuardNormalized、account-visual 的 mergedNormalized、account-visual-b 的 mergedB，由各守卫自己核对）；被换的 h 只能是底版值或当前文件值。
 * - tools/ci/verify.sh：除含 AuthManageVisual 的新增行外逐字节。
 */
@Suppress("TooManyFunctions")
class AuthManageVisualGuardTest {
    private val root = AuthManageVisualFixtures.root
    private val base: JsonObject =
        Json
            .parseToJsonElement(
                requireNotNull(javaClass.classLoader?.getResource("auth-manage-visual/guard-baseline.json")) { "缺守卫基线" }.readText(),
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

    @Test fun baselineIsFor1dbb163AndCoversTheRedLineFiles() {
        assertEquals("1dbb163d0b9ec869228b76813437c9d7adf18902", base.text("base"))
        val files = protectedFiles
        assertTrue("清单不得被清空", files.size >= MIN_FILES)
        REQUIRED.forEach { assertTrue("必须冻结：$it", it in files) }
        ALLOWED.forEach { allowed -> assertTrue("本任务允许改的不得整文件冻结：$allowed", allowed !in files) }
        assertTrue("本任务新增测试不进清单", files.keys.none { it.contains("AuthManageVisual") || it.contains("auth-manage") })
        assertTrue(
            "已有基准图全部冻结（ds02 / product-v / settings-v / me-v / order-detail-v …）",
            files.keys.count { it.startsWith("app/src/test/snapshots/") } > 300,
        )
    }

    @Test fun protectedFilesAreByteIdentical() {
        val changed = protectedFiles.filter { (p, h) -> !File(root, p).isFile || sha(File(root, p).readBytes()) != h }
        assertTrue("受保护文件被改动：${changed.keys}", changed.isEmpty())
    }

    // ---------------------------------------------------------------- AuthManage.kt

    @Test fun pageHeadUpToTheLifecycleObserverIsUnchanged() {
        val lines = lines(PAGE)
        val start = lines.indexOfFirst { it.startsWith("fun AuthManageScreen(") }
        assertTrue("AuthManageScreen 仍在", start >= 0)
        val end = (start until lines.size).first { lines[it] == "    }" }
        assertEquals("头段收尾仍是生命周期观察的 onDispose", "        onDispose { lifecycle.removeObserver(observer) }", lines[end - 1])
        assertEquals(
            "AuthManage.kt 第 1–491 行（执行器、配置、ViewModel、标记、文案键、页面头段生命周期）去 import 后逐字节",
            base.text("pageHead"),
            joined(noImports(lines.subList(0, end + 1))),
        )
    }

    @Test fun errorBlockExecutorLinesAreUnchanged() {
        val lines = lines(PAGE)
        val m = lines.indexOfFirst { it.trim() == "val message = AuthManageController.errorMessage(state, texts)" }
        assertTrue("ErrorBlock 的 message 行仍在", m >= 0)
        assertEquals(
            "message / focus / ErrorAnnouncement 三行逐字（去首尾空白）",
            base.text("errorHead"),
            joined(lines.subList(m, m + 3).map { it.trim() }),
        )
    }

    @Test fun callbackConditionAndTagStringsKeepTheirCounts() {
        val src = File(root, PAGE).readText().filterNot(Char::isWhitespace)
        base.getValue("callbacks").jsonObject.forEach { (needle, n) ->
            assertEquals("AuthManage.kt「$needle」出现次数与改前相等", n.jsonPrimitive.content.toInt(), count(src, needle))
        }
    }

    @Test fun pageReadsNoNewControllerOrConfigMembers() {
        val text = File(root, PAGE).readText() + "\n" + File(root, PARTS).let { if (it.isFile) it.readText() else "" }
        assertEquals("执行器成员集合与改前相等", list("controllerRefs").toSet(), refs(CONTROLLER, text))
        assertEquals("配置成员集合与改前相等（onBack / onContact 去向不变）", list("configRefs").toSet(), refs(CONFIG, text))
    }

    // ---------------------------------------------------------------- 已合并守卫基线（G-A02 / D10-414 ②）

    @Test fun mergedGuardBaselinesOnlySwapTheAuthManageHash() {
        val swapBase = base.getValue("swapBase").jsonArray.associate { it.jsonObject.text("p") to it.jsonObject.text("h") }
        val merged = base.getValue("mergedAm").jsonArray.map { it.jsonObject }
        assertEquals("五份全覆盖（settings / privacy / risk / account-visual / account-visual-b）", 5, merged.size)
        val now = sha(File(root, PAGE).readBytes())
        merged.forEach { item ->
            val path = item.text("p")
            assertEquals("$path 只换 AuthManage.kt 的 h（另许归一化值按原算法重算）", item.text("h"), normalized(path))
            val text = File(root, path).readText()
            val want = item.text("swaps").toInt()
            assertEquals("$path 含 AuthManage.kt 恰 $want 项（不删项、不加项）", want, count(text, "\"p\": \"$PAGE\"}"))
            if (want == 1) {
                val h =
                    Regex("""\{"h": "([0-9a-f]{64})", "p": "${Regex.escape(PAGE)}"\}""")
                        .find(text)
                        ?.groupValues
                        ?.get(1)
                assertTrue("$path 的 AuthManage.kt 只能是底版值或当前文件值：$h", h == swapBase[PAGE] || h == now)
            }
        }
    }

    // ---------------------------------------------------------------- verify.sh

    @Test fun verifyScriptOnlyGainsTheAuthManageStep() {
        val all = lines(VERIFY)
        val added = all.filter { it.contains("AuthManageVisual") }
        assertTrue("至多新增 2 行（一步）：$added", added.size <= 2)
        assertEquals("verify.sh 其余逐字节", base.text("verifyOutside"), joined(all.filterNot { it.contains("AuthManageVisual") }))
    }

    // ---------------------------------------------------------------- 算法（与生成脚本逐项一致）

    private fun normalized(path: String): String {
        var inBlock = false
        val out =
            File(root, path).readText().split("\n").map { raw ->
                var line = raw
                ITEM.find(line)?.let { m ->
                    if (m.groupValues[1] == PAGE) {
                        line =
                            line.substring(0, m.range.first) + "{\"h\": \"*\", \"p\": \"" + m.groupValues[1] + "\"}" +
                            line.substring(m.range.last + 1)
                    }
                }
                line = NORM.replace(line) { it.groupValues[1] + "\"*\"" }
                when {
                    BLOCKS.any { line.startsWith(it) } -> inBlock = true
                    inBlock && line.startsWith(" ]") -> inBlock = false
                    inBlock -> line = HLINE.replace(line) { it.groupValues[1] + "\"*\"" + it.groupValues[2] }
                }
                line
            }
        return sha(out.joinToString("\n").toByteArray())
    }

    private fun refs(
        regex: Regex,
        source: String,
    ): Set<String> = regex.findAll(source).map { it.groupValues[1] + "." + it.groupValues[2] }.toSet()

    private fun list(name: String): List<String> = base.getValue(name).jsonArray.map { it.jsonPrimitive.content }

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
        const val MIN_FILES = 2900
        const val TRADE = "feature/trade/src/main/kotlin/com/couli/feature/trade/"
        const val PAGE = TRADE + "AuthManage.kt"
        const val PARTS = TRADE + "AuthManageParts.kt"
        const val VERIFY = "tools/ci/verify.sh"
        const val RES = "app/src/test/resources/"
        val ALLOWED =
            listOf(
                PAGE,
                VERIFY,
                RES + "settings-visual/guard-baseline.json",
                RES + "privacy-visual/guard-baseline.json",
                RES + "risk-visual/guard-baseline.json",
                RES + "account-visual/guard-baseline.json",
                RES + "account-visual-b/guard-baseline.json",
            )
        val REQUIRED =
            listOf(
                TRADE + "TaobaoAuthFlow.kt",
                TRADE + "TradeServices.kt",
                TRADE + "TradeJump.kt",
                TRADE + "OpenPurchaseController.kt",
                TRADE + "OpenTradeLogic.kt",
                TRADE + "TradeUi.kt",
                TRADE + "PurchaseVisualParts.kt",
                "feature/trade/src/test/kotlin/com/couli/feature/trade/AuthManageControllerTest.kt",
                "feature/trade/src/test/kotlin/com/couli/feature/trade/AuthManageReviewR2Test.kt",
                "feature/trade/src/test/kotlin/com/couli/feature/trade/FollowUp0607Test.kt",
                "feature/trade/src/test/kotlin/com/couli/feature/trade/OpenPurchaseReviewR3Test.kt",
                "feature/trade/build.gradle.kts",
                "core/designsystem/src/main/kotlin/com/couli/core/designsystem/PlatformIcons.kt",
                "core/designsystem/src/main/kotlin/com/couli/core/designsystem/FeedbackComponents.kt",
                "core/designsystem/src/main/kotlin/com/couli/core/designsystem/CouliButtons.kt",
                "core/designsystem/src/main/kotlin/com/couli/core/designsystem/CouliFoundation.kt",
                "core/designsystem/src/main/kotlin/com/couli/core/designsystem/CouliIcons.kt",
                "core/network/src/main/kotlin/com/couli/core/network/RetryAfterGate.kt",
                "core/network/src/main/kotlin/com/couli/core/network/PageApi.kt",
                "app/src/main/kotlin/com/couli/app/trade/AuthManageRoute.kt",
                "app/src/main/kotlin/com/couli/app/trade/AppTrade.kt",
                "app/src/main/kotlin/com/couli/app/trade/TradeDraftTexts.kt",
                "app/src/test/kotlin/com/couli/app/trade/AppTradeReviewR1Test.kt",
                "app/src/test/kotlin/com/couli/app/trade/TradeRouteNamesTest.kt",
                "app/src/test/kotlin/com/couli/app/me/SettingsVisualBehaviorTest.kt",
                "app/src/test/kotlin/com/couli/app/me/SettingsVisualProbe.kt",
                "app/src/test/kotlin/com/couli/app/me/RiskVisualProbe.kt",
                "app/src/test/kotlin/com/couli/app/me/MeVisualAssertions.kt",
                "app/src/test/kotlin/com/couli/app/product/ProductVisualAssertions.kt",
                "app/src/test/kotlin/com/couli/app/purchase/PurchaseVisualAppHost.kt",
                "app/src/test/kotlin/com/couli/app/purchase/PurchaseVisualStructureTest.kt",
                "app/src/test/kotlin/com/couli/app/account/AccountVisualPhoneGuardTest.kt",
                "app/src/test/kotlin/com/couli/app/me/RiskVisualGuardTest.kt",
                "app/src/test/kotlin/com/couli/app/account/AccountVisualGuardTest.kt",
                "specs/open-trade-vectors.json",
                "contract.lock",
                "contracts/snapshot/contracts/texts.default.json",
                "contracts/snapshot/design/design-tokens.json",
            )
        val SHA256 = Regex("^[0-9a-f]{64}$")
        val ITEM = Regex("""\{"h": "[0-9a-f]{64}", "p": "([^"]+)"\}""")
        val NORM = Regex("""^( "(?:settings|privacy)GuardNormalized": )"[0-9a-f]{64}"""")
        val HLINE = Regex("""^(\s*"h": )"[0-9a-f]{64}"(\s*)$""")
        val BLOCKS = listOf(" \"mergedNormalized\": [", " \"mergedB\": [")
        val CONTROLLER = Regex("""\b(controller)(?:\.|::)([A-Za-z]+)""")
        val CONFIG = Regex("""\b(config)(?:\.|::)([A-Za-z]+)""")
    }
}
