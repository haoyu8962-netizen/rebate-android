package com.couli.app.wallet.visual

import com.couli.core.me.account.AccountDraftTexts
import com.couli.core.wallet.WalletDraftTexts
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APP-09v-d 资金逻辑零改动 + 源码契约（brief §3 / §5、G-W19）。
 * 冻结：brief §5 列表（core/wallet 下除 StepUpSheet.kt / WalletTexts.kt 外全部）、四宿主的身份 / 发码 / 验证控制器、FU-AND-0718…0721 / Invariant / Wiring 等保护测试、
 * WithdrawScreen 前 305 行、DS-02 的 CodeInput 与 StepUpSheet 目录基准图（预期 0 张变化）。StepUpSheet.kt 允许改版式，但回调票据与可用条件逐字守住。
 */
class StepUpVisualFundsGuardTest {
    private val root = WithdrawVisualFixtures.root

    @Test fun protectedLogicTestsAndCatalogImagesAreByteIdenticalTo6770a58() {
        val baseline = resource()
        assertEquals("6770a580c2c9eac45b47589f2b24e24b2659dbdc", baseline.getValue("base").jsonPrimitive.content)
        val files = baseline.getValue("files").jsonObject
        assertTrue("清单不得被清空", files.size >= MIN_FILES)
        assertFalse("StepUpSheet.kt 是本任务允许改的文件，不应冻结", files.keys.any { it.endsWith("stepup/StepUpSheet.kt") })
        files.forEach { (path, expected) ->
            assertEquals("资金 / 身份保护文件被改：$path", expected.jsonPrimitive.content, digest(File(root, path).readBytes()))
        }
        val images = baseline.getValue("images").jsonObject
        assertTrue("DS-02 CodeInput / StepUpSheet 目录图不得被清空", images.size >= MIN_IMAGES)
        images.forEach { (path, expected) ->
            assertEquals("DS-02 目录基准被重录：$path", expected.jsonPrimitive.content, digest(File(root, path).readBytes()))
        }
        val prefix = baseline.getValue("prefix").jsonObject
        val bytes = File(root, prefix.getValue("path").jsonPrimitive.content).readBytes()
        var end = 0
        repeat(VIEW_MODEL_LINES) {
            while (end < bytes.size && bytes[end] != NEWLINE) end++
            if (end < bytes.size) end++
        }
        assertEquals("WithdrawScreen 前 305 行（ViewModel）禁止变动", prefix.getValue("sha256").jsonPrimitive.content, digest(bytes.copyOf(end)))
    }

    @Test fun sheetCallbacksStillCarryTheRenderTimeOwnerExactlyOnce() {
        val source = sheet()
        assertTrue("回调只捕获渲染时面板身份", source.contains("val owner = state.owner"))
        assertEquals("【验证并提交】是唯一验证入口", 1, Regex("""controller\.verify\(""").findAll(source).count())
        assertEquals(1, Regex("""controller\.verify\(owner\)""").findAll(source).count())
        assertEquals("【重新获取】是唯一发码入口", 1, Regex("""controller\.resend\(""").findAll(source).count())
        assertEquals(1, Regex("""controller\.resend\(owner\)""").findAll(source).count())
        assertEquals("输入回调原样带 owner", 1, Regex("""controller\.onCodeChange\(\s*\w+\s*,\s*owner\s*\)""").findAll(source).count())
        val verifyLine = source.lines().single { it.contains("controller.verify(") }
        assertTrue("验证只挂在点击上：$verifyLine", verifyLine.contains("onClick"))
    }

    @Test fun enabledConditionsAreMovedVerbatim() {
        val flat = sheet().replace(Regex("""\s+"""), " ")
        assertTrue("【验证并提交】可用条件不变", flat.contains("enabled = state.canVerify && !verifyBlocked"))
        assertTrue(
            "【重新获取】可用条件（smsBlocked / smsLimited / phase）原样搬",
            flat.contains("!smsBlocked && !state.smsLimited && state.phase == StepUpUiState.READY"),
        )
        assertTrue("验证中 loading 不变", flat.contains("loading = state.phase == StepUpUiState.VERIFYING"))
    }

    @Test fun noNewSubmitEntryInTheSheet() {
        val source = sheet()
        assertFalse("不接输入法完成键（新提交入口）", source.contains("keyboardActions") || source.contains("onDone"))
        assertFalse("不按输入长度自动验证", Regex("""LaunchedEffect\(\s*state\.code""").containsMatchIn(source))
        assertFalse("半屏不读号码（G16）", source.contains("stepUpPhone"))
    }

    @Test fun lockedSheetStaysLocked() {
        val flat = sheet().replace(Regex("""\s+"""), " ")
        listOf(
            "sheetGesturesEnabled = false",
            "shouldDismissOnBackPress = true",
            "shouldDismissOnClickOutside = false",
            "onDismissRequest = onClose",
            "testTag(StepUpTags.SHEET_MIDDLE)",
            "testTag(StepUpTags.SHEET_FALLBACK)",
        ).forEach { assertTrue("LockedSheet / FitSheetBody 保留：$it", flat.contains(it)) }
    }

    @Test fun countdownWordingComesFromLoginCodeCountdownDraft() {
        assertEquals(
            "WalletTexts 补 login.code.countdown 草稿（G-W19）",
            "48 秒后可重新获取",
            WalletDraftTexts.format(
                "login.code.countdown",
                mapOf("n" to "48"),
            ),
        )
        assertEquals(
            "换手机号 / 注销用的 AccountDraftTexts 同一句",
            "48 秒后可重新获取",
            AccountDraftTexts.format("login.code.countdown", mapOf("n" to "48")),
        )
        val source = sheet()
        assertTrue(source.contains("\"login.code.countdown\""))
        assertFalse("不再用旧措辞键", source.contains("\"step_up.resend_in\""))
    }

    @Test fun titleKeyAndWordingStayContractDriven() {
        assertEquals("标题键 = error.10003 文案（不在客户端写新句）", WalletDraftTexts.text("error.10003"), WalletDraftTexts.text("step_up.title"))
        assertFalse(WalletDraftTexts.DRAFTS.values.any { it.contains("请先完成二次验证") })
        assertTrue("标题仍按 action 取键", sheet().contains("StepUpController.titleKey(state.action, texts::has)"))
    }

    @Test fun codeInputGainsOnlyAnOptionalInputModifier() {
        val source = File(root, "core/designsystem/src/main/kotlin/com/couli/core/designsystem/CouliInputs.kt").readText()
        val signature = source.substringAfter("fun CodeInput(").substringBefore(") {")
        assertTrue("CodeInput 加可选 inputModifier（默认不变，DS-02 CodeInput 基准 0 变化）", signature.contains("inputModifier: Modifier = Modifier"))
        val params = signature.lines().map { it.trim() }.filter { it.isNotEmpty() }
        assertTrue(
            "modifier 仍是首个可选参数",
            params.indexOfFirst { it.startsWith("modifier: Modifier = Modifier") } < params.indexOfFirst { it.startsWith("inputModifier") },
        )
        assertTrue("实际二次验证用 6 格 CodeInput", sheet().contains("CodeInput("))
    }

    private fun sheet(): String = File(root, "core/wallet/src/main/kotlin/com/couli/core/wallet/stepup/StepUpSheet.kt").readText()

    private fun resource() =
        WithdrawVisualFixtures.obj(
            checkNotNull(javaClass.getResourceAsStream("/stepup-visual/funds-baseline.json")).bufferedReader().use { it.readText() },
        )

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MIN_FILES = 59
        const val MIN_IMAGES = 60
        const val VIEW_MODEL_LINES = 305
        const val NEWLINE: Byte = 10
    }
}
