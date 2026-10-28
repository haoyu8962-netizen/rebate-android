package com.couli.app.me

import android.app.Application
import android.view.MotionEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText as textIs
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.me.account.AccountRaw
import com.couli.feature.me.AccountTags
import com.couli.feature.me.MeTags
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * brief-c §0.1 / §5「红线逐条测试」（处罚 / 申诉敏感页，S0；三端同名 redLine1…redLine6）。**底版 e9fdd94 上必须全绿**（行为一字不动），任何发起条件 /
 * 次数 / 顺序 / 去向 / 编号的回退都应变红（变异见交回 §4）。全部从真实控件的真实触摸出发计数；「旧回调」取自节点上已注册的点击动作。
 * ① 提交申诉 ② 再次申诉（锁定契约无 can_resubmit：维持结果页总是显示——CT-26e 升锁后改，G-R01 / G-R18）③ 不显示处理时限、原因 / 期限只取服务端字段
 * ④ 退出确认一次性编号 ⑤ 注销入口按路由可用性、去向 risk_notice ⑥ 申诉行无点击、整页可点节点集合不变（G-R07）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1600dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions")
class RiskVisualRedLineTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { RiskVisualTestHost(rule) }

    @After fun tearDown() = host.close()

    private fun t(key: String) = AppMeTexts.text(key)

    // ---------------------------------------------------------------- ① 提交申诉

    @Test fun redLine1BlankInputCannotSubmitAndSendsNothing() {
        host.launchAppeal()
        assertDisabled("空输入不可点")
        host.input(RiskVisualFixtures.blank)
        assertDisabled("只有空白不可点（去首尾空白后为空）")
        host.tap(MeTags.APPEAL_SUBMIT)
        assertEquals("空白输入点 0 次", 0, host.api.count(RiskVisualApi.SUBMIT))
    }

    @Test fun redLine1OneTapIsOneSubmitWithVerbatimBodyAndAFreshKey() {
        host.api.answers[RiskVisualApi.SUBMIT] = RiskVisualFixtures.ok(RiskVisualFixtures.submitted(RiskVisualFixtures.input))
        host.launchAppeal()
        host.input(RiskVisualFixtures.inputPadded)
        host.tap(MeTags.APPEAL_SUBMIT)
        val sent = host.api.calls.filter { it.operationId == RiskVisualApi.SUBMIT }
        assertEquals("一次点击 = 一次 submitAppeal", 1, sent.size)
        assertEquals(
            "body 逐字 = {target_type: account, content: 去首尾空白}，不带 target_id（G11）",
            JsonObject(mapOf("target_type" to JsonPrimitive("account"), "content" to JsonPrimitive(RiskVisualFixtures.input))),
            Json.parseToJsonElement(sent.single().body!!).jsonObject,
        )
        assertTrue("带 Idempotency-Key", !sent.single().idempotencyKey.isNullOrEmpty())
        assertEquals("键来自这一次点击（newKey 恰一次）", listOf(sent.single().idempotencyKey), host.keys)
        host.settle("同页切进度") { host.has(MeTags.APPEAL_PROGRESS) }
        assertTrue("成功后同一页切到处理中，不新开页、不导航", host.env.opened.isEmpty())
        assertFalse("处理中不再有提交按钮", host.has(MeTags.APPEAL_SUBMIT))
        assertEquals(
            "请求顺序：进入 getMe → listAppeals，提交 1 次，之后不自动重读",
            listOf(RiskVisualApi.GET_ME, RiskVisualApi.LIST_APPEALS, RiskVisualApi.SUBMIT),
            host.api.ops(),
        )
    }

    @Test fun redLine1TwoTapsUseTwoDifferentKeysAndUnknownResultIsNotResent() {
        host.api.answers[RiskVisualApi.SUBMIT] = RiskVisualFixtures.failure(RiskVisualFixtures.code("serverCode"))
        host.launchAppeal()
        host.input(RiskVisualFixtures.input)
        host.tap(MeTags.APPEAL_SUBMIT)
        host.settle("统一错误") { host.has(MeTags.APPEAL_ERROR) }
        assertEquals("失败不自动重发", 1, host.api.count(RiskVisualApi.SUBMIT))
        host.tap(MeTags.APPEAL_SUBMIT)
        val keys =
            host.api.calls
                .filter { it.operationId == RiskVisualApi.SUBMIT }
                .map { it.idempotencyKey }
        assertEquals("第二次点击 = 第二次请求", 2, keys.size)
        assertNotEquals("每次点击新 Idempotency-Key（G14）", keys[0], keys[1])
    }

    @Test fun redLine1TapWhileSubmittingIsZero() {
        val gate = host.holdSubmit()
        host.api.answers[RiskVisualApi.SUBMIT] = RiskVisualFixtures.ok(RiskVisualFixtures.submitted(RiskVisualFixtures.input))
        host.launchAppeal()
        host.input(RiskVisualFixtures.input)
        val stale = host.clickAction(host.node(MeTags.APPEAL_SUBMIT))
        host.tap(MeTags.APPEAL_SUBMIT)
        assertEquals(1, host.api.count(RiskVisualApi.SUBMIT))
        host.tap(MeTags.APPEAL_SUBMIT)
        rule.runOnIdle { stale() }
        host.idle()
        assertEquals("提交中再点 / 旧回调 = 0 次", 1, host.api.count(RiskVisualApi.SUBMIT))
        host.release(gate)
        host.settle("同页切进度") { host.has(MeTags.APPEAL_PROGRESS) }
        assertEquals(1, host.api.count(RiskVisualApi.SUBMIT))
    }

    @Test fun redLine1RateLimitWindowBlocksTheButtonAndSendsZero() {
        host.api.answers[RiskVisualApi.SUBMIT] = RiskVisualFixtures.failure(RiskVisualFixtures.code("rateCode"))
        host.launchAppeal()
        host.input(RiskVisualFixtures.input)
        host.tap(MeTags.APPEAL_SUBMIT)
        assertEquals(1, host.api.count(RiskVisualApi.SUBMIT))
        assertDisabled("42901 启动提交禁用期：按钮不可点")
        host.tap(MeTags.APPEAL_SUBMIT)
        assertEquals("禁用期内 0 次", 1, host.api.count(RiskVisualApi.SUBMIT))
        assertEquals("保留输入", RiskVisualFixtures.input, editableText())
    }

    // ---------------------------------------------------------------- ② 再次申诉

    @Test fun redLine2AgainIsAlwaysShownZeroRequestsAndReturnsToSubmit() {
        // 锁定契约 a7c9f1f 无 can_resubmit：维持结果页【再次申诉】总是显示；CT-26e 升锁后改（G-R01 / G-R18，FU-RISK-CT26E）
        host.launchAppeal(appeals = "appeals_upheld_banned")
        assertTrue("维持结果页【再次申诉】总是显示", host.has(MeTags.APPEAL_AGAIN))
        val before = host.api.calls.size
        host.tap(MeTags.APPEAL_AGAIN)
        assertEquals("【再次申诉】零请求", before, host.api.calls.size)
        assertTrue("回到提交版式", host.has(MeTags.APPEAL_INPUT) && host.has(MeTags.APPEAL_SUBMIT))
        assertEquals("清空输入", "", editableText())
        assertTrue("不导航", host.env.opened.isEmpty())
    }

    @Test fun redLine2FrozenUpheldAgainIsAlsoAlwaysShown() {
        host.launchAppeal(risk = "risk_frozen", appeals = "appeals_upheld_frozen")
        assertTrue(host.has(MeTags.APPEAL_AGAIN))
        val before = host.api.calls.size
        host.tap(MeTags.APPEAL_AGAIN)
        assertEquals(before, host.api.calls.size)
        assertTrue(host.has(MeTags.APPEAL_INPUT))
    }

    // ---------------------------------------------------------------- ③ 处理时限 / 原因 / 期限

    @Test fun redLine3BannedShowsNoDeadline() {
        host.launchRisk()
        assertNoDeadline()
    }

    @Test fun redLine3BannedAppealingReasonAndLimitComeFromTheServerFields() {
        host.launchRisk(risk = "risk_appealing_banned", appeals = "appeals_processing_banned")
        assertNoDeadline()
        assertEquals("原因 = 字典（fixture reason_category）", 1, host.count(t("risk_reason.malicious_rights")))
        assertTrue("封禁写法：限制期限是固定说法", host.has(MeTags.RISK_LIMIT))
        assertFalse("封禁写法不显示预计恢复", host.has(MeTags.RISK_UNTIL))
    }

    @Test fun redLine3FrozenAppealingUntilIsTheFormattedServerValue() {
        host.launchRisk(risk = "risk_appealing_frozen", appeals = "appeals_processing_frozen")
        assertNoDeadline()
        assertEquals(1, host.count(t("risk_reason.abnormal_trade")))
        assertEquals("预计恢复 = frozen_until 原值格式化", 1, host.count(RiskVisualFixtures.expect("frozenUntilText")))
        assertFalse("冻结写法无限制期限", host.has(MeTags.RISK_LIMIT))
    }

    @Test fun redLine3UnknownCategoryHidesTheReasonRowOnBothPages() {
        host.launchRisk(risk = "risk_unknown_reason", withdrawals = "withdrawals_none")
        assertFalse("表外类别：原因行消失（G08）", host.has(MeTags.RISK_REASON))
        assertEquals("不显示原始编码", 0, host.count(RiskVisualFixtures.reasonCategory("risk_unknown_reason")!!))
        assertEquals(0, rule.onAllNodes(textIs(t("risk.reason")), useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test fun redLine3AppealPagesShowNoDeadline() {
        host.launchAppeal(risk = "risk_appealing_banned", appeals = "appeals_processing_banned")
        assertNoDeadline()
    }

    @Test fun redLine3UpheldResultShowsNoDeadline() {
        host.launchAppeal(appeals = "appeals_upheld_banned")
        assertNoDeadline()
    }

    @Test fun redLine3UnknownCategoryOnTheAppealSubmitPage() {
        host.launchAppeal(risk = "risk_unknown_reason")
        assertEquals(0, host.count(RiskVisualFixtures.reasonCategory("risk_unknown_reason")!!))
        assertEquals("申诉对象卡也不显示原因行", 0, host.count(t("appeal.reason")))
    }

    // ---------------------------------------------------------------- ④ 退出确认

    @Test fun redLine4RiskCancelIsZeroAndTheOldConfirmIsVoid() = cancelIsZero(risk = true)

    @Test fun redLine4AppealCancelIsZeroAndTheOldConfirmIsVoid() = cancelIsZero(risk = false)

    @Test fun redLine4RiskBackIsCancelAndScrimDoesNothing() = backAndScrim(risk = true)

    @Test fun redLine4AppealBackIsCancelAndScrimDoesNothing() = backAndScrim(risk = false)

    @Test fun redLine4RiskConfirmIsOnceAndReuseIsVoid() = confirmOnce(risk = true)

    @Test fun redLine4AppealConfirmIsOnceAndReuseIsVoid() = confirmOnce(risk = false)

    @Test fun redLine4IdentityChangeVoidsTheOldConfirm() {
        launchExit(risk = true)
        host.tap(MeTags.RISK_LOGOUT)
        val stale = host.clickAction(rule.dialogButton(t("settings.logout.confirm_ok")))
        rule.runOnIdle { host.env.identityFlow.value = RiskVisualEnv.member(RiskVisualEnv.ACCOUNT_B, 2) }
        host.idle()
        assertFalse("换身份：执行器重建，旧确认框作废", host.has(MeTags.RISK_LOGOUT_DIALOG))
        rule.runOnIdle { stale() }
        host.idle()
        assertEquals("换身份后旧确认 0 次", 0, host.env.logouts.size)
    }

    // ---------------------------------------------------------------- ⑤ 注销入口

    @Test fun redLine5PublicBannedHasOneDeletionEntryGoingToRiskNotice() {
        host.launchRisk(public = true)
        assertEquals("公开版：恰 1 个「注销账号」可点节点", 1, deletionClickables())
        host.tap(AccountTags.RISK_DELETE)
        assertEquals("经 Router 开注销页一次，来源 risk_notice", listOf(AccountRaw.RISK_NOTICE), host.accountEnv.deletions)
        assertTrue("不经其它出口", host.env.opened.isEmpty() && host.env.logouts.isEmpty() && host.env.cs.isEmpty())
    }

    @Test fun redLine5InternalBuildHasNoDeletionEntry() {
        host.launchRisk(public = false)
        assertEquals("内测版：0 个注销节点", 0, deletionClickables())
        assertFalse(host.has(AccountTags.RISK_DELETE))
        assertTrue("先正：联系客服与退出仍在", host.has(MeTags.RISK_CS) && host.has(MeTags.RISK_LOGOUT))
    }

    @Test fun redLine5PublicUpheldResultHasOneDeletionEntry() {
        host.launchAppeal(appeals = "appeals_upheld_banned", public = true)
        assertEquals(1, deletionClickables())
        host.tap(AccountTags.APPEAL_DELETE)
        assertEquals(listOf(AccountRaw.RISK_NOTICE), host.accountEnv.deletions)
    }

    @Test fun redLine5InternalUpheldResultAndFrozenGroupHaveNoDeletionEntry() {
        host.launchAppeal(appeals = "appeals_upheld_banned", public = false)
        assertEquals(0, deletionClickables())
        assertTrue(host.has(MeTags.APPEAL_LOGOUT))
    }

    @Test fun redLine5FrozenNoticeHasNoExitRowAtAll() {
        host.launchRisk(risk = "risk_frozen", withdrawals = "withdrawals_none", public = true)
        assertEquals("冻结版式无出口行（现状）", 0, deletionClickables())
        assertFalse(host.has(MeTags.RISK_CS) || host.has(MeTags.RISK_LOGOUT))
    }

    // ---------------------------------------------------------------- ⑥ 申诉行无点击、可点节点集合不变

    @Test fun redLine6BannedAppealingRowIsNotClickableAndClickablesAreUnchanged() {
        host.launchRisk(risk = "risk_appealing_banned", appeals = "appeals_processing_banned")
        assertRowNotClickable(t("risk.appeal.processing"))
        assertEquals(
            "整页可点节点 = 改前集合（G-R07：申诉行不新增入口）",
            setOf(t("risk.view_progress"), t("risk.contact_cs"), DELETE, t("risk.logout")),
            clickableLabels(),
        )
    }

    @Test fun redLine6BannedUpheldRowIsNotClickable() {
        host.launchRisk(appeals = "appeals_upheld_banned")
        assertRowNotClickable(t("risk.upheld"))
        assertEquals(setOf(t("risk.submit"), t("risk.contact_cs"), DELETE, t("risk.logout")), clickableLabels())
    }

    @Test fun redLine6FrozenAppealingClickablesAreBackAndPrimaryOnly() {
        host.launchRisk(risk = "risk_appealing_frozen", appeals = "appeals_processing_frozen", withdrawals = "withdrawals_none")
        assertRowNotClickable(t("risk.appeal.processing"))
        assertEquals(setOf(t("me.back"), t("risk.view_progress")), clickableLabels())
    }

    // ---------------------------------------------------------------- 辅助

    private fun launchExit(risk: Boolean) {
        if (risk) host.launchRisk() else host.launchAppeal(appeals = "appeals_upheld_banned")
    }

    private fun logoutTag(risk: Boolean) = if (risk) MeTags.RISK_LOGOUT else MeTags.APPEAL_LOGOUT

    private fun dialogTag(risk: Boolean) = if (risk) MeTags.RISK_LOGOUT_DIALOG else MeTags.APPEAL_LOGOUT_DIALOG

    private fun cancelIsZero(risk: Boolean) {
        launchExit(risk)
        host.tap(logoutTag(risk))
        assertTrue("点【退出登录】先出确认框", host.has(dialogTag(risk)))
        val stale = host.clickAction(rule.dialogButton(t("settings.logout.confirm_ok")))
        host.tap(rule.dialogButton(t("settings.logout.confirm_cancel")))
        assertFalse("【取消】关闭确认框", host.has(dialogTag(risk)))
        rule.runOnIdle { stale() }
        host.idle()
        assertEquals("【取消】与关闭后的旧确认 = 0 次", 0, host.env.logouts.size)
    }

    private fun backAndScrim(risk: Boolean) {
        launchExit(risk)
        host.tap(logoutTag(risk))
        rule.runOnUiThread {
            val dialog = ShadowDialog.getLatestDialog()
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
                val event = MotionEvent.obtain(0, SCRIM_EVENT_MS, action, SCRIM_X, SCRIM_Y, 0)
                try {
                    dialog.onTouchEvent(event)
                } finally {
                    event.recycle()
                }
            }
        }
        host.idle()
        assertTrue("点遮罩不关闭", host.has(dialogTag(risk)))
        assertEquals("点遮罩不执行", 0, host.env.logouts.size)
        @Suppress("DEPRECATION")
        rule.runOnUiThread { ShadowDialog.getLatestDialog().onBackPressed() }
        host.idle()
        assertFalse("系统返回 = 取消", host.has(dialogTag(risk)))
        assertEquals("返回零动作", 0, host.env.logouts.size)
    }

    private fun confirmOnce(risk: Boolean) {
        launchExit(risk)
        host.tap(logoutTag(risk))
        val button = rule.dialogButton(t("settings.logout.confirm_ok"))
        assertEquals("确认标记挂在【退出登录】确认上", host.node(dialogTag(risk)).fetchSemanticsNode().id, button.fetchSemanticsNode().id)
        val stale = host.clickAction(button)
        host.tap(button)
        assertEquals("【确认】→ logout 恰 1 次、渲染时的身份", listOf(RiskVisualEnv.member()), host.env.logouts)
        rule.runOnIdle { stale() }
        host.idle()
        assertEquals("同一确认框再点仍 1 次（编号用过即作废）", 1, host.env.logouts.size)
        assertTrue("退出不经页面导航", host.env.opened.isEmpty())
    }

    private fun assertDisabled(message: String) {
        val node = host.node(MeTags.APPEAL_SUBMIT).fetchSemanticsNode()
        assertTrue(message, node.config.contains(SemanticsProperties.Disabled))
    }

    private fun editableText(): String =
        host
            .editable()
            .fetchSemanticsNode()
            .config
            .getOrElseNullable(SemanticsProperties.EditableText) { null }
            ?.text
            .orEmpty()

    /** 页面所有文字（未合并树，含时间线、提示条、按钮）里不出现任何处理时限 / 截止字样（BR-TEXT-23 细则）。 */
    private fun assertNoDeadline() {
        val texts =
            rule
                .onAllNodes(hasTextOrEditable(), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .flatMap { it.spokenParts() }
        assertTrue("先正：页面有文字", texts.isNotEmpty())
        RiskVisualFixtures.forbidden.forEach { word ->
            assertTrue("不显示处理时限 / 截止：「$word」出现在 ${texts.filter { it.contains(word) }}", texts.none { it.contains(word) })
        }
    }

    private fun hasTextOrEditable() =
        androidx.compose.ui.test.SemanticsMatcher
            .keyIsDefined(SemanticsProperties.Text)

    private fun deletionClickables(): Int =
        rule
            .onAllNodes(hasClickAction(), useUnmergedTree = false)
            .fetchSemanticsNodes()
            .count { node -> node.spokenParts().any { it.contains(DELETE) } }

    private fun clickableLabels(): Set<String> =
        rule
            .onAllNodes(hasClickAction(), useUnmergedTree = false)
            .fetchSemanticsNodes()
            .map { it.spokenParts().joinToString(" ") }
            .toSet()

    /** 申诉行（含 [value] 的合并节点）及其祖先都没有点击动作；先正：主按钮有。 */
    private fun assertRowNotClickable(value: String) {
        val nodes = rule.onRoot().fetchSemanticsNode().pvFlatten()
        val row = nodes.filter { node -> node.spokenParts().any { it == value } }
        assertTrue("找到申诉行「$value」", row.isNotEmpty())
        row.forEach { node ->
            generateSequence(node) { it.parent }.forEach {
                assertFalse("申诉行不可点（G-R07）：${it.spokenParts()}", it.hasClick())
            }
        }
        assertTrue("先正：主按钮可点", host.node(MeTags.RISK_PRIMARY).fetchSemanticsNode().hasClick())
    }

    private companion object {
        val DELETE = AppMeTexts.text("settings.delete_account")
        const val SCRIM_EVENT_MS = 16L
        const val SCRIM_X = 4f
        const val SCRIM_Y = 4f
    }
}
