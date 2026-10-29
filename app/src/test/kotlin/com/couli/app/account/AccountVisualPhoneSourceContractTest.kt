package com.couli.app.account

import com.couli.core.me.account.AccountDraftTexts
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APP-13v-b 源码契约（brief-android §3 / §5、gaps G-D03 / G-D04 / G-D08 / G-D09 / G-D13 / G-D17 / G-D18）。按内容判，不按行号；去空白后计数。
 * 换号页 = PhoneChangePage.kt + feature/me 下 b 段新视图件（*Parts.kt，AccountVisualParts.kt 与已合并的 Parts 除外）；绑号页 = BindPhoneScreen.kt 第
 * 893 行之后的视图段（按 `fun BindPhoneScreen(` 收尾锚点切）+ feature/login 下新 *Parts.kt。底版上「新写法」一类应红，「不得出现」一类应绿。
 */
@Suppress("TooManyFunctions")
class AccountVisualPhoneSourceContractTest {
    private val root = AccountVisualFixtures.root
    private val meDir = File(root, "feature/me/src/main/kotlin/com/couli/feature/me")
    private val loginDir = File(root, "feature/login/src/main/kotlin/com/couli/feature/login")
    private val pageFile = File(meDir, "PhoneChangePage.kt")
    private val bindFile = File(loginDir, "BindPhoneScreen.kt")
    private val accountParts = File(meDir, "AccountVisualParts.kt")

    /** b 段新增的 feature/me 视图件（已合并的 Parts 不算）。 */
    private val meParts: List<File>
        get() =
            meDir.listFiles().orEmpty().filter {
                it.name.endsWith("Parts.kt") && it.name !in MERGED_PARTS
            }

    /** b 段新增的 feature/login 视图件。 */
    private val loginParts: List<File>
        get() = loginDir.listFiles().orEmpty().filter { it.name.endsWith("Parts.kt") }

    private val page get() = squeeze(pageFile.readText())
    private val phoneView get() = page + meParts.joinToString("") { squeeze(it.readText()) }
    private val bindView: String
        get() {
            val lines = bindFile.readText().split("\n")
            val start = lines.indexOfFirst { it.startsWith("fun BindPhoneScreen(") }
            val end = (start until lines.size).first { lines[it] == "}" }
            return squeeze(lines.drop(end + 1).joinToString("\n")) + loginParts.joinToString("") { squeeze(it.readText()) }
        }

    // ---------------------------------------------------------------- G-D04 不接输满自动提交

    @Test fun gd04NoValueChangeLambdaSubmitsOrChecksLength() {
        val sources = listOf("换号" to phoneView, "绑号" to bindView, "共用件" to squeeze(accountParts.readText()))
        sources.forEach { (where, src) ->
            lambdas(src, "onValueChange={").plus(lambdas(src, "onChange={")).forEach { body ->
                FORBIDDEN_IN_INPUT.forEach {
                    assertFalse("$where：输入回调里不得判断长度或发起验证 / 提交 / 绑定（G-D04、红线 ⑦⑧⑨）：$it in $body", body.contains(it))
                }
            }
            listOf("keyboardActions", "onDone=", "LaunchedEffect(state.code").forEach {
                assertFalse("$where：不接输入法完成键 / 观察验证码自动提交：$it", src.contains(it))
            }
        }
    }

    @Test fun gd04CodeFieldsAreTheSixCellCodeInputWithAnInputModifier() {
        assertTrue("换号两处验证码换组件库 CodeInput（6 格）", count(phoneView, "CodeInput(") >= 2)
        assertTrue("标记经 inputModifier 挂在输入节点", count(phoneView, "inputModifier=") >= 2)
        assertEquals("不再用整串验证码框（AccountInput otp）", 0, count(page, "otp=true"))
        assertTrue("绑号验证码换 CodeInput", count(bindView, "CodeInput(") >= 1 && count(bindView, "inputModifier=") >= 1)
        assertTrue("CodeInput 的 cells 不关（默认 6 格）", !phoneView.contains("cells=false") && !bindView.contains("cells=false"))
    }

    // ---------------------------------------------------------------- G-D03 写法与旧件

    @Test fun gd03PhonePageUsesTheSegmentAPartsAndNotTheFrozenOldOnes() {
        assertEquals("待确认卡用 a 段 PendingPanel（白卡写法）", 1, count(page, "PendingPanel("))
        assertEquals("放弃确认框用 a 段 AbandonConfirmDialog", 1, count(page, "AbandonConfirmDialog("))
        listOf("AccountPendingCard(", "AccountAbandonDialog(", "AccountInput(").forEach {
            assertEquals("换号页不再调旧件 $it（旧件留在冻结的 AccountUi.kt 原处）", 0, count(phoneView, it))
        }
        assertEquals("G-D13 在途行只读 pending.inFlight（恰一处）", 1, count(page, "inFlight=pending.inFlight"))
        assertFalse("页面不另判在途", page.contains(".active"))
    }

    @Test fun gd03NewPartsFilesAreAppearanceOnly() {
        (meParts + loginParts).forEach { file ->
            val raw = file.readText()
            PARTS_FORBIDDEN.forEach { assertFalse("${file.name} 只管外观、回调由页面传入（含注释）：不得出现「$it」", raw.contains(it)) }
            assertFalse("${file.name} 不得出现裸 Dialog(", Regex("""(?<![A-Za-z])Dialog\(""").containsMatchIn(squeeze(raw)))
        }
        assertTrue(
            "a 段 AccountVisualParts.kt 仍只有一个 CouliDialog（新确认框不往里加，a 段契约）",
            count(squeeze(accountParts.readText()), "CouliDialog(") == 1,
        )
    }

    // ---------------------------------------------------------------- G-D18 确认框

    @Test fun gd18RiskDialogCentersTheTitleAndUsesTheScrimToken() {
        assertEquals("换号页只剩 44001 一个 CouliDialog（放弃框走 AbandonConfirmDialog）", 1, count(page, "CouliDialog("))
        assertEquals("G-D18 标题居中", 1, count(page, "titleAlign=TextAlign.Center"))
        assertEquals("G-D18 遮罩令牌", 1, count(page, "scrim=CouliColors.overlayScrim"))
        assertEquals("点遮罩不关闭（原样）", 1, count(page, "dismissOnClickOutside=false"))
    }

    @Test fun gd18BindDialogsCenterTheTitleAndKeepTheGuideDialog() {
        assertEquals("并号 / 44001 / 放弃三个框都居中", 3, count(bindView, "titleAlign=TextAlign.Center"))
        assertEquals("三个框都用遮罩令牌", 3, count(bindView, "scrim=CouliColors.overlayScrim"))
        assertEquals("只有放弃框点遮罩不关闭（并号 / 44001 现状不改）", 1, count(bindView, "dismissOnClickOutside=false"))
        val guide = functionText(bindFile.readText(), "fun BindPhoneGuideDialog(")
        assertFalse("登录流程的引导弹窗不动（G-D17）", guide.contains("titleAlign") || guide.contains("scrim"))
    }

    @Test fun gd18ToastIconIsChosenByKey() {
        assertTrue("成功类轻提示带对勾（按键传 icon）", phoneView.contains("CouliIcons.check"))
        assertTrue("对勾只给已放弃 / 已处理", phoneView.contains("pending_confirm.abandoned") || phoneView.contains("ABANDONED_KEY"))
        assertEquals("MeToastHost 仍是唯一轻提示宿主", 1, count(page, "MeToastHost("))
    }

    // ---------------------------------------------------------------- G-D17 绑号顶部区

    @Test fun gd17TopBarHasNoTitleAndTheBodyIsNoLongerRendered() {
        val top = functionText(bindFile.readText(), "private fun BindTopBar(")
        assertTrue("先正：顶栏仍有返回", top.contains("BindPhoneTags.BACK"))
        assertFalse("G-D17 顶栏不再有标题", top.contains("invite.bind_phone_guide.title"))
        assertTrue("标题移到内容区", bindView.contains("\"invite.bind_phone_guide.title\""))
        listOf("invite.bind_phone_guide.benefit", "invite.bind_phone_guide.merge_hint").forEach {
            assertTrue("新草稿键在视图里：$it", bindView.contains("\"$it\""))
        }
        assertEquals(
            "「.body」只留在登录引导弹窗（本页不再渲染）",
            1,
            count(squeeze(bindFile.readText()), "\"invite.bind_phone_guide.body\""),
        )
    }

    // ---------------------------------------------------------------- G-D08 / G-D09 / 号码 / 时钟 / 中文

    @Test fun gd08CountdownWordingStays() {
        assertEquals("G-D08", "{n} 秒后重新获取", AccountDraftTexts.DRAFTS["phone_change.resend_in"])
        assertEquals("G-D08", "{sec} 秒后重新获取", LoginDraftTextsProbe.raw("bind_phone.countdown"))
        assertTrue("换号页仍用 phone_change.resend_in", count(phoneView, "\"phone_change.resend_in\"") >= 2)
        assertTrue("绑号仍用 bind_phone.countdown", bindView.contains("\"bind_phone.countdown\""))
        listOf("login.code.countdown", "秒后可重新获取").forEach {
            assertFalse("不换成「可重新获取」：$it", phoneView.contains(it) || bindView.contains(it))
        }
    }

    @Test fun noNewEntriesNoPhoneNoClockNoChinese() {
        val all = phoneView + bindView
        listOf("ownedPhone", "phone_masked", "System.currentTimeMillis", "Instant.now", "deps.api", "ApiCall(").forEach {
            assertFalse("不得出现 $it", all.contains(it))
        }
        listOf("help_entry", "收不到验证码", "/v1/auth/merge").forEach { assertFalse("G-D09 / 并号不加新入口：$it", all.contains(it)) }
        val code =
            listOf(pageFile.readText(), *meParts.map { it.readText() }.toTypedArray(), *loginParts.map { it.readText() }.toTypedArray())
                .joinToString("\n") { codeOnly(it) }
        assertFalse("页面与视图件不写中文（文案经字典取键）", Regex("[\\u4e00-\\u9fa5]").containsMatchIn(code))
        assertFalse("绑号视图段不写中文", Regex("[\\u4e00-\\u9fa5]").containsMatchIn(codeOnly(bindViewRaw())))
    }

    @Test fun stepBarKeepsTheSpokenSummaryAndTheTag() {
        assertTrue("读屏合并「第 n 步，共 2 步」原样", phoneView.contains("\"phone_change.step_spoken\""))
        assertTrue("标记原样", phoneView.contains("AccountTags.PHONE_STEPS"))
        assertTrue("mergeDescendants 原样", phoneView.contains("mergeDescendants=true"))
    }

    // ---------------------------------------------------------------- 工具

    private fun bindViewRaw(): String {
        val lines = bindFile.readText().split("\n")
        val start = lines.indexOfFirst { it.startsWith("fun BindPhoneScreen(") }
        val end = (start until lines.size).first { lines[it] == "}" }
        return lines.drop(end + 1).joinToString("\n")
    }

    /** 去空白源码里每个 `[head]…}` lambda 的体（按花括号配对）。 */
    private fun lambdas(
        src: String,
        head: String,
    ): List<String> {
        val out = mutableListOf<String>()
        var from = src.indexOf(head)
        while (from >= 0) {
            val open = from + head.length - 1
            out += src.substring(open + 1, closing(src, open))
            from = src.indexOf(head, from + head.length)
        }
        return out
    }

    /** 与 [open] 处 `{` 配对的 `}` 下标（找不到为串尾）。 */
    private fun closing(
        src: String,
        open: Int,
    ): Int {
        var depth = 0
        for (i in open until src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return i
            }
        }
        return src.length
    }

    /** 某个顶层函数从签名行到第一个 `}` 行（列 0）的原文。 */
    private fun functionText(
        text: String,
        signature: String,
    ): String {
        val lines = text.split("\n")
        val start = lines.indexOfFirst { it.startsWith(signature) }
        assertTrue("找不到 $signature", start >= 0)
        val end = (start until lines.size).first { lines[it] == "}" }
        return lines.subList(start, end + 1).joinToString("\n")
    }

    private fun squeeze(s: String) = s.filterNot(Char::isWhitespace)

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

    private fun codeOnly(src: String): String =
        src
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("//[^\\n]*"), "")

    private companion object {
        val MERGED_PARTS =
            setOf("SettingsParts.kt", "MeTabParts.kt", "PrivacyAboutCsParts.kt", "RiskAppealParts.kt", "AccountVisualParts.kt")
        val FORBIDDEN_IN_INPUT =
            listOf(".verify(", ".submit(", ".onSubmit(", "length==", "CODE_LENGTH", ".complete", "CodeInput.accept", "onGetCode")
        val PARTS_FORBIDDEN =
            listOf(
                "DeleteAccount",
                "openDeletion",
                "controller",
                "entry.",
                "deps",
                ".env",
                "model.",
                "BindPhoneViewModel",
                "PhoneChangeController",
                "CustomerServiceEntry",
                "MeEnv",
                "ModalBottomSheet(",
                "collectAsState",
                "LaunchedEffect(",
                "rememberCoroutineScope",
                "ownedPhone",
                "System.currentTimeMillis",
            )
    }
}
