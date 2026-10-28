package com.couli.app.me

import com.couli.core.me.MeDraftTexts
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 源码契约（去空白后比对；与行为用例互补，挡「看起来对、其实换了回调 / 另起判定」的实现）。底版上 G 项相关条目应红（交回 §5）。
 * - 两个退出确认框：标题居中、遮罩令牌（R9 / G-S07），点遮罩不关闭原样；页面层不另建 Dialog。
 * - 申诉：白底固定底栏（R15 / G-R22 贴键盘）；输入换 CouliTextField 多行 + 占位草稿键，回调与错误焦点原样（R13 / G-R14）。
 * - 头部图标（G-R06）、出口行（G-R04）、注销句与入口同判据（G-R17）、私有视图件只管外观（G-R03）。
 * - 视图不另起判定：不读 CT-26e 字段、不计次、不比较时间、不新增请求（G-R01 / G-R05 / G-R10）。
 * - 草稿值（G-R09 / G-R14 / G-R17）与不改的句子（G-R10 / G-R11）；verify.sh 新增一步。
 */
@Suppress("TooManyFunctions")
class RiskVisualSourceContractTest {
    private val root = RiskVisualFixtures.root
    private val dir = File(root, "feature/me/src/main/kotlin/com/couli/feature/me")

    private fun squeezed(name: String) = File(dir, name).readText().filterNot(Char::isWhitespace)

    private fun count(
        haystack: String,
        needle: String,
    ) = haystack.split(needle).size - 1

    private val risk get() = squeezed("RiskNotice.kt")
    private val appeal get() = squeezed("Appeal.kt")
    private val partsFile get() = File(dir, "RiskAppealParts.kt")
    private val parts get() = if (partsFile.isFile) partsFile.readText().filterNot(Char::isWhitespace) else ""

    /** 页面文件里执行器之后的视图段（RiskNoticeConfig / AppealConfig 收尾之后）。 */
    private fun viewPart(
        name: String,
        config: String,
    ): String {
        val text = File(dir, name).readText()
        val start = text.indexOf("class $config(")
        val end = text.indexOf("\n)\n", start)
        return text.substring(end + 3).filterNot(Char::isWhitespace)
    }

    @Test fun r9BothLogoutDialogsCenterTheTitleAndUseTheScrimToken() {
        listOf("RiskNotice.kt" to risk, "Appeal.kt" to appeal).forEach { (name, src) ->
            assertEquals("$name 恰一个 CouliDialog", 1, count(src, "CouliDialog("))
            assertEquals("$name 标题居中", 1, count(src, "titleAlign=TextAlign.Center"))
            assertEquals("$name 遮罩令牌", 1, count(src, "scrim=CouliColors.overlayScrim"))
            assertEquals("$name 点遮罩不关闭不执行（原样）", 1, count(src, "dismissOnClickOutside=false"))
        }
    }

    @Test fun noPageLevelDialogOrSheet() {
        listOf(risk, appeal, parts).forEach { src ->
            assertFalse("页面层不另建 Dialog", Regex("""(?<![A-Za-z])Dialog\(""").containsMatchIn(src))
            assertFalse("页面层不另建 ModalBottomSheet", src.contains("ModalBottomSheet("))
        }
    }

    @Test fun r15AppealUsesTheWhiteFixedBottomBar() {
        assertTrue("MeSubPage(bottomSurface = true)：白底固定栏、键盘弹起贴键盘（R15 / G-R22）", appeal.contains("bottomSurface=true"))
    }

    @Test fun r13AppealInputIsAMultilineCouliTextField() {
        assertEquals("换组件库 CouliTextField（G-R14）", 1, count(appeal, "CouliTextField("))
        assertFalse("不再用 M3 OutlinedTextField", appeal.contains("OutlinedTextField"))
        listOf(
            "singleLine=false",
            "onValueChange=controller::onInput",
            "accessibilityLabel=texts.text(\"appeal.content_label\")",
            "placeholder=texts.text(\"appeal.placeholder\")",
            "showErrorText=false",
            "MeTags.APPEAL_INPUT",
        ).forEach { assertTrue("CouliTextField 实参：$it", appeal.contains(it)) }
        assertEquals("错误焦点与 assertive 原样", 1, count(appeal, "ErrorFocusText(error,state.error?.id,errorFocus,MeTags.APPEAL_ERROR)"))
    }

    @Test fun gr06HeaderUsesLockAndPauseCircle() {
        val view = risk + parts
        assertTrue("封禁头部锁（G-R06）", view.contains("CouliIcons.lock"))
        assertTrue("冻结头部暂停圆（G-R06）", view.contains("CouliIcons.pauseCircle"))
        val icons = File(root, "core/designsystem/src/main/kotlin/com/couli/core/designsystem/CouliIcons.kt").readText()
        assertTrue(icons.contains("val lock: ImageVector") && icons.contains("val pauseCircle: ImageVector"))
    }

    @Test fun gr04ExitRowsKeepDeleteAccountLinkAndUseLinkStyling() {
        assertEquals("账号状态的注销入口仍是 DeleteAccountLink（不新增入口）", 1, count(risk, "DeleteAccountLink(AccountTags.RISK_DELETE"))
        assertEquals("维持结果页同上", 1, count(appeal, "DeleteAccountLink(AccountTags.APPEAL_DELETE"))
        val view = risk + appeal + parts
        assertTrue("链接 text.link", view.contains("CouliColors.textLink"))
        assertTrue("分隔符 border.subtle", view.contains("CouliColors.borderSubtle"))
        listOf(risk, appeal).forEach { assertFalse("分隔符不再用 border.control", it.contains("color=CouliColors.borderControl")) }
        val ui = File(dir, "MeUi.kt").readText().filterNot(Char::isWhitespace)
        assertTrue(
            "LinkText 在尾随 onClick 之前加 style / color 两个带默认值参数（默认 = 旧外观）",
            ui.contains(
                "funLinkText(text:String,tag:String,style:TextStyle=CouliTypography.body," +
                    "color:Color=CouliColors.brandPrimary,onClick:()->Unit,)",
            ),
        )
    }

    @Test fun gr17SentencesAndEntryShareOneCriterion() {
        val account = File(dir, "AccountUi.kt").readText().filterNot(Char::isWhitespace)
        assertTrue("判据抽成 deletionAvailable()（AccountUi.kt）", account.contains("fundeletionAvailable("))
        assertTrue("DeleteAccountLink 用同一判据", account.substringAfter("funDeleteAccountLink(").contains("deletionAvailable()"))
        listOf("RiskNotice.kt" to risk, "Appeal.kt" to appeal).forEach { (name, src) ->
            assertTrue("$name 用 deletionAvailable() 选句子", src.contains("deletionAvailable()"))
            assertFalse("$name 不另写路由判据", src.contains("DeleteAccountController") || src.contains("availableRoutes"))
        }
        assertTrue("封禁说明句按判据选 risk.banned.desc.no_deletion", risk.contains("\"risk.banned.desc.no_deletion\""))
        assertTrue(
            "「接下来可以」第 3 条按判据选含注销的句子",
            appeal.contains("\"appeal.next.logout.with_deletion\"") || appeal.contains("\"appeal.next.delete_logout\""),
        )
    }

    @Test fun gr03PartsFileIsAppearanceOnly() {
        assertTrue("本组私有视图件放新文件 RiskAppealParts.kt（G-R03）", partsFile.isFile)
        val text = partsFile.readText()
        listOf("controller", "deps.", "MeEnv", "env.", "DeleteAccount", "openDeletion", "AppealCopy", "RiskNoticeView").forEach {
            assertFalse("RiskAppealParts.kt 只管外观、回调与显隐由页面传入：不得出现 $it", text.contains(it))
        }
    }

    @Test fun viewsInventNoRules() {
        val views = listOf(viewPart("RiskNotice.kt", "RiskNoticeConfig"), viewPart("Appeal.kt", "AppealConfig"), parts)
        views.forEach { view ->
            listOf(
                "attempt_no",
                "can_resubmit",
                "prev_risk_state",
                "30418",
                "listUnionBindings",
                "deadline",
                "System.currentTimeMillis",
                "Instant.now",
                "appeals.size",
                "appeals.count",
                "deps.clock()<",
                "deps.clock()>",
                "<deps.clock()",
                ">deps.clock()",
                "deps.api",
                "deps.service",
                "ApiCall(",
            ).forEach { assertFalse("视图不另起判定 / 不新增请求（G-R01 / G-R05 / G-R10）：$it", view.contains(it)) }
        }
    }

    @Test fun gr09g14g17DraftValues() {
        listOf(
            "risk.limit.long",
            "risk.banned_upheld.note",
            "risk.frozen_upheld.note",
            "risk.frozen.note_no_until",
            "appeal.next.again",
            "appeal.placeholder",
            "risk.banned.desc.no_deletion",
        ).forEach { key -> assertEquals("草稿定稿：$key", RiskVisualFixtures.draft(key), MeDraftTexts.textOrNull(key)) }
        val withDeletion = MeDraftTexts.textOrNull("appeal.next.logout.with_deletion") ?: AppMeTexts.text("appeal.next.delete_logout")
        assertEquals("「接下来可以」公开版第 3 条", RiskVisualFixtures.draft("appeal.next.logout.with_deletion"), withDeletion)
    }

    @Test fun keptSentencesAreUnchanged() {
        listOf("risk.banned.desc", "appeal.next.logout", "appeal.lifted.note.banned", "appeal.withdraw_ok").forEach { key ->
            assertEquals("不改（G-R10 / G-R11 / G-R17 现值）：$key", RiskVisualFixtures.kept(key), MeDraftTexts.text(key))
        }
    }

    @Test fun verifyRunsTheRiskSnapshotsAfterThePrivacyStep() {
        val lines = File(root, "tools/ci/verify.sh").readLines()
        val privacy = lines.indexOfFirst { it.contains("--tests \"*PrivacyVisualSnapshotTest\"") }
        val mine = lines.indexOfFirst { it.contains("--tests \"*RiskVisualSnapshotTest\"") }
        assertEquals("verify.sh 恰一步跑 RiskVisualSnapshotTest", 1, lines.count { it.contains("RiskVisualSnapshotTest") })
        assertTrue("在隐私中心一步之后", privacy in 0 until mine)
        assertTrue("用 verifyRoborazziDebug", lines[mine].contains(":app:verifyRoborazziDebug"))
    }
}
