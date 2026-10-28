package com.couli.app.account

import com.couli.core.me.account.AccountDraftTexts
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APP-13v-a 源码契约（brief-android §3 / §5、gaps G-D03 / G-D07 / G-D11 / G-D12 / G-D13 / G-D18）。按内容判，不按行号；去空白后计数。
 * 底版上：新文件 AccountVisualParts.kt 不存在、草稿值未换、确认框未居中 → 相关用例红；「不得出现」一类在底版上就成立（绿）。
 */
@Suppress("TooManyFunctions")
class AccountVisualSourceContractTest {
    private val root = AccountVisualFixtures.root
    private val dir = File(root, "feature/me/src/main/kotlin/com/couli/feature/me")
    private val pageFile = File(dir, "DeleteAccountScreen.kt")
    private val partsFile = File(dir, "AccountVisualParts.kt")
    private val page get() = pageFile.readText().filterNot(Char::isWhitespace)
    private val parts get() = if (partsFile.isFile) partsFile.readText().filterNot(Char::isWhitespace) else ""

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

    // ---------------------------------------------------------------- G-D03 新视图件文件

    @Test fun gd03NewPartsFileExistsWithItsOwnTags() {
        assertTrue("G-D03 本组私有视图件放新文件 AccountVisualParts.kt", partsFile.isFile)
        assertTrue("新标记放新文件里的 object AccountVisualTags（不改 AccountPorts.kt，D39）", parts.contains("objectAccountVisualTags{"))
    }

    /** APP-12w-b PrivacyVisualSourceContractTest 对 feature/me 下所有 *Parts.kt 的禁词（连注释也算），新文件必须先过这一关。 */
    @Test fun gd03PartsFileAvoidsTheMergedPartsContractWords() {
        assertTrue("先正：新文件存在", partsFile.isFile)
        val raw = partsFile.readText()
        listOf(
            "DeleteAccount",
            "openDeletion",
            "controller",
            "entry.",
            "deps.",
            "CustomerServiceEntry",
            "MeEnv",
            "ModalBottomSheet(",
            "privacy_center.basic_desc",
        ).forEach { assertFalse("AccountVisualParts.kt 不得出现「$it」（含注释）", raw.contains(it)) }
        assertFalse("不得出现裸 Dialog(（确认框用 CouliDialog）", Regex("""(?<![A-Za-z])Dialog\(""").containsMatchIn(parts))
        assertFalse("不得出现 loading=withdrawing", parts.contains("loading=withdrawing"))
    }

    @Test fun gd03PartsFileIsAppearanceOnly() {
        assertTrue("先正：新文件存在", partsFile.isFile)
        val raw = partsFile.readText()
        listOf(".env", "model.", "AccountDeps", "DeleteAccountController", "PhoneChangeController", "BindPhoneViewModel", "deps")
            .forEach { assertFalse("视图件只收已求值参数与 lambda，不得出现「$it」", raw.contains(it)) }
        listOf(
            "collectAsState",
            "LaunchedEffect(",
            "rememberCoroutineScope",
            "ownedPhone",
            "phone_masked",
            "System.currentTimeMillis",
            "Instant.now",
            "ApiCall(",
        ).forEach { assertFalse("视图件不读可观察状态、不起协程、不取号码与时钟：$it", raw.contains(it)) }
    }

    // ---------------------------------------------------------------- 页面写法

    @Test fun pageUsesSettingsPaddingAndTheWhiteBottomBar() {
        assertEquals("D1 contentPadding = SETTINGS_CONTENT_PADDING", 1, count(page, "contentPadding=SETTINGS_CONTENT_PADDING"))
        assertEquals("D10 bottomSurface = true", 1, count(page, "bottomSurface=true"))
    }

    @Test fun pageNoLongerCallsTheFrozenOldParts() {
        listOf("AccountPendingCard(", "AccountAbandonDialog(", "AccountCheckRow(", "GroupLabel(")
            .forEach { assertEquals("注销页不再调旧件 $it（新写法在 AccountVisualParts.kt；旧件留在 AccountUi.kt 原处不删）", 0, count(page, it)) }
        assertTrue("旧件本体仍在（AccountUi.kt 冻结）", File(dir, "AccountUi.kt").readText().contains("internal fun AccountPendingCard("))
    }

    @Test fun gd12HintIsChosenFromShowGoWithdrawOnly() {
        assertTrue("G-D12 新键在页面里", page.contains("\"deletion.withdraw_hint.no_withdraw\""))
        assertEquals(
            "选句只读已有字段 showGoWithdraw",
            1,
            count(page, "if(view.showGoWithdraw)\"deletion.withdraw_hint\"else\"deletion.withdraw_hint.no_withdraw\""),
        )
        assertTrue("【去提现】显示条件原样", count(page, "if(view.showGoWithdraw)") >= 1)
        assertTrue("G-D12 新键进资金术语表（BR-TEXT-12）", "deletion.withdraw_hint.no_withdraw" in AccountDraftTexts.FUND_TERMS)
        assertTrue(
            "原有资金术语不删",
            AccountDraftTexts.FUND_TERMS.containsAll(listOf("deletion.waiver", "deletion.withdraw_hint", "deletion.negative.note")),
        )
    }

    @Test fun gd13InFlightIsPassedReadOnly() {
        assertEquals("G-D13 在途行只读 pending.inFlight", 1, count(page, "inFlight=pending.inFlight"))
        assertEquals("busy 判定原样", 1, count(page, "busy=pending.inFlight||gateBlocked"))
        assertFalse("页面不另判在途（不读 deletionGate.active 等执行层状态）", page.contains(".active"))
    }

    @Test fun gd18AbandonDialogCentersTitleAndUsesTheScrimToken() {
        val all = page + parts
        assertEquals("确认框只在新视图件里包一层 CouliDialog", 1, count(parts, "CouliDialog("))
        assertEquals("页面层不直接建 CouliDialog", 0, count(page, "CouliDialog("))
        assertEquals("G-D18 标题居中", 1, count(parts, "titleAlign=TextAlign.Center"))
        assertEquals("G-D18 遮罩令牌", 1, count(parts, "scrim=CouliColors.overlayScrim"))
        assertEquals("点遮罩不关闭不执行（原样）", 1, count(parts, "dismissOnClickOutside=false"))
        listOf("AccountTags.ABANDON_OK", "AccountTags.ABANDON_CANCEL", "AccountTags.ABANDON_DIALOG").forEach {
            assertTrue("确认框标记原样：$it", all.contains(it))
        }
        assertTrue(
            "【确定放弃】/【再想想】键原样",
            parts.contains("\"pending_confirm.abandon.ok\"") && parts.contains("\"pending_confirm.abandon.cancel\""),
        )
    }

    @Test fun gd18ToastIconIsChosenByKey() {
        assertTrue("D20 成功类轻提示带对勾（按键传 icon）", page.contains("CouliIcons.check"))
        assertTrue("对勾只给已放弃 / 已处理两个键", page.contains("pending_confirm.abandoned") || page.contains("AccountRaw.ABANDONED_KEY"))
        assertTrue(page.contains("pending_confirm.already_done") || page.contains("ALREADY_DONE_KEY"))
        assertTrue("MeToastHost 仍是唯一轻提示宿主（位置不改）", count(page, "MeToastHost(") == 1)
    }

    @Test fun d11RoundCheckKeepsToggleableSemantics() {
        assertTrue("先正：新文件存在", partsFile.isFile)
        assertTrue("toggleable + Role.Checkbox", parts.contains("toggleable(") && parts.contains("role=Role.Checkbox"))
        assertTrue("stateDescription 原样", parts.contains("stateDescription="))
        assertTrue("命中 48", parts.contains("heightIn(min=CouliSizes.touchTarget)"))
        assertEquals("勾选回调原样（页面里恰一次）", 1, count(page, "controller.toggle(version)"))
        assertFalse("不换 DS AgreementCheckRow（标记与语义结构不同，G-D03）", page.contains("AgreementCheckRow(") || parts.contains("AgreementCheckRow("))
    }

    @Test fun d4NegativeSentenceIsMovedVerbatim() {
        assertEquals("负余额句金额来源原样（negativeAmountFen → CouliFormat.money）", 1, count(page, "valamount=view.negativeAmountFen"))
        assertEquals("显示条件原样", 2, count(page, "view.stateRaw==DeletionView.NEGATIVE"))
        assertTrue("polite 原样", page.contains("liveRegion=LiveRegionMode.Polite"))
        assertTrue("assertive 原样（统一错误）", page.contains("LiveRegionMode.Assertive") || parts.contains("LiveRegionMode.Assertive"))
    }

    @Test fun noNewEntriesNoPhoneNoClock() {
        val all = page + parts
        listOf("ownedPhone", "phone_masked", "System.currentTimeMillis", "Instant.now", "deps.api", "ApiCall(", "onValueChange")
            .forEach { assertFalse("不得出现 $it", all.contains(it)) }
        listOf("help_entry", "查看完整", "notice.full", "Help\"").forEach { assertFalse("G-D09 不加新入口：$it", all.contains(it)) }
        assertFalse("页面与视图件不写中文（文案经文案字典取键）", Regex("[\\u4e00-\\u9fa5]").containsMatchIn(pageCodeOnly() + partsCodeOnly()))
    }

    @Test fun gd07DraftValuesUseTheFinalWording() {
        AccountVisualFixtures.drafts().forEach { (key, value) ->
            assertEquals("G-D07 / G-D12 草稿值（键名不变）：$key", value, AccountDraftTexts.DRAFTS[key])
        }
        AccountVisualFixtures.kept().forEach { (key, value) ->
            assertEquals("其余草稿值不改：$key", value, AccountDraftTexts.DRAFTS[key])
        }
        assertEquals("G-D08 倒计时措辞维持（三份冻结测试依赖）", "{n} 秒后重新获取", AccountDraftTexts.DRAFTS["phone_change.resend_in"])
    }

    /** 去掉注释与字符串后的源码（中文只许出现在注释里）。 */
    private fun pageCodeOnly(): String = codeOnly(pageFile.readText())

    private fun partsCodeOnly(): String = if (partsFile.isFile) codeOnly(partsFile.readText()) else ""

    private fun codeOnly(src: String): String =
        src
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("//[^\\n]*"), "")
}
