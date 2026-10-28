package com.couli.app.me

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.feature.me.MeTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * brief-c §4 第 4、5 条：每个改了样式或换了控件的入口，真实点击一次 → 动作 / 导航恰好一次、目标与改前一致；进入的请求次数与顺序不变、
 * 本任务新增零请求（无 listUnionBindings，G-R10）；回调只捕获渲染时的版本 / 执行器（旧回调零动作）。底版上应全绿。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1600dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions")
class RiskVisualBehaviorTest {
    @get:Rule val rule = createComposeRule()
    private val host by lazy { RiskVisualTestHost(rule) }

    @After fun tearDown() {
        assertTrue("没有未登记的请求：${host.api.unexpected}", host.api.unexpected.isEmpty())
        host.close()
    }

    // ---------------------------------------------------------------- 进入的请求

    @Test fun enteringBannedNoticeReadsMeThenAppealsAndWithdrawalsOnce() {
        host.launchRisk()
        assertEquals(
            listOf(RiskVisualApi.GET_ME, RiskVisualApi.LIST_APPEALS, RiskVisualApi.LIST_WITHDRAWALS),
            host.api.ops(),
        )
    }

    @Test fun enteringNormalNoticeReadsOnlyMe() {
        host.launchRisk(risk = "risk_normal", withdrawals = "withdrawals_none")
        assertEquals(listOf(RiskVisualApi.GET_ME), host.api.ops())
    }

    @Test fun enteringAppealReadsMeThenAppealsOnce() {
        host.launchAppeal(risk = "risk_normal", appeals = "appeals_revoked_banned")
        assertEquals(listOf(RiskVisualApi.GET_ME, RiskVisualApi.LIST_APPEALS), host.api.ops())
        assertEquals("G-R10：不新增 listUnionBindings", 0, host.api.count(RiskVisualApi.UNION_BINDINGS))
    }

    @Test fun orderAppealIsIgnoredSoOnlyAccountAppealsDecideTheLayout() {
        host.launchAppeal(appeals = "appeals_order_only")
        assertTrue("列表里只有订单申诉 → 提交版式（G15）", host.has(MeTags.APPEAL_INPUT))
    }

    // ---------------------------------------------------------------- 主按钮

    @Test fun bannedPrimaryOpensAppealOnce() = primary("risk_banned", "Appeal")

    @Test fun appealingPrimaryOpensAppealOnce() = primary("risk_appealing_banned", "Appeal", "appeals_processing_banned")

    @Test fun normalPrimaryOpensHomeOnce() = primary("risk_normal", "Home")

    @Test fun stalePrimaryFromAnOlderVersionDoesNothing() {
        host.api.once(RiskVisualApi.LIST_WITHDRAWALS, RiskVisualFixtures.failure())
        host.launchRisk()
        host.settle("提现分区错误") { host.has(MeTags.RISK_WITHDRAWALS_RETRY) }
        val before = host.api.count(RiskVisualApi.LIST_WITHDRAWALS)
        val stale = host.clickAction(host.node(MeTags.RISK_PRIMARY))
        val retry = host.clickAction(host.node(MeTags.RISK_WITHDRAWALS_RETRY))
        // 测试 v2 T4：Compose clickable 的语义点击动作转发到节点「当前」的 onClick；v1 等重组之后再调，调到的已是新版本的回调（底版即红）。
        // 这里在同一个主线程任务里先重取提现（传输层同步回包、Main.immediate → 执行器版本 +1），紧接着调渲染时的旧主按钮回调——
        // 两者之间不可能重组，调到的一定是旧版本的 onClick，才真正量到「旧版本回调零动作」。
        rule.runOnUiThread {
            retry()
            stale()
        }
        host.idle()
        assertEquals("重取提现已发出（版本因此前进）", before + 1, host.api.count(RiskVisualApi.LIST_WITHDRAWALS))
        host.settle("提现重取") { host.has(MeTags.RISK_WITHDRAWALS) }
        assertTrue("旧版本主按钮回调零动作（共性第 7 条）", host.env.opened.isEmpty())
        host.tap(MeTags.RISK_PRIMARY)
        assertEquals(listOf("Appeal"), host.env.opened)
    }

    // ---------------------------------------------------------------- 客服、分区重试

    @Test fun riskContactCsIsOnce() {
        host.launchRisk()
        host.tap(MeTags.RISK_CS)
        assertEquals(listOf("RiskNotice"), host.env.cs)
    }

    @Test fun riskErrorFallbackContactCsIsOnce() {
        host.api.answers[RiskVisualApi.GET_ME] = RiskVisualFixtures.failure()
        host.launchRisk()
        host.tap(MeTags.RISK_CS)
        assertEquals(listOf("RiskNotice"), host.env.cs)
    }

    @Test fun appealProgressContactCsIsOnce() {
        host.launchAppeal(risk = "risk_appealing_banned", appeals = "appeals_processing_banned")
        host.tap(MeTags.APPEAL_CS)
        assertEquals(listOf("Appeal"), host.env.cs)
    }

    @Test fun appealUpheldContactCsIsOnce() {
        host.launchAppeal(appeals = "appeals_upheld_banned")
        host.tap(MeTags.APPEAL_CS)
        assertEquals(listOf("Appeal"), host.env.cs)
    }

    @Test fun appealsSectionRetryRereadsOnlyAppeals() {
        host.api.once(RiskVisualApi.LIST_APPEALS, RiskVisualFixtures.failure())
        host.launchRisk()
        host.settle("申诉分区错误") { host.has(MeTags.RISK_APPEALS_RETRY) }
        val before = host.api.ops()
        host.tap(MeTags.RISK_APPEALS_RETRY)
        assertEquals("只重取申诉摘要（R1-07）", before + RiskVisualApi.LIST_APPEALS, host.api.ops())
    }

    @Test fun withdrawalsSectionRetryRereadsOnlyWithdrawals() {
        host.api.once(RiskVisualApi.LIST_WITHDRAWALS, RiskVisualFixtures.failure())
        host.launchRisk()
        host.settle("提现分区错误") { host.has(MeTags.RISK_WITHDRAWALS_RETRY) }
        val before = host.api.ops()
        host.tap(MeTags.RISK_WITHDRAWALS_RETRY)
        assertEquals("只重取提现（G16）", before + RiskVisualApi.LIST_WITHDRAWALS, host.api.ops())
    }

    // ---------------------------------------------------------------- 结果页动作

    // 测试 v2 T2：解除封禁结果按 fixture 画板 AppealResultLifted-v2 登记带 10006 进入标记（G10：normal 且非 10006 进入走冻结组）
    @Test fun liftedStartOpensHomeOnce() = action("risk_normal", "appeals_revoked_banned", "start", "Home", entered10006 = true)

    @Test fun liftedViewMessagesOpensMessagesOnce() =
        action("risk_normal", "appeals_revoked_banned", "view_messages", "Messages", entered10006 = true)

    @Test fun liftedFrozenGoWalletOpensWalletOnce() = action("risk_normal", "appeals_revoked_frozen", "go_wallet", "Wallet")

    @Test fun liftedHistoryContactCsIsOnce() {
        host.launchAppeal(risk = "risk_banned", appeals = "appeals_revoked_banned")
        host.tap(MeTags.APPEAL_ACTION_PREFIX + "contact_cs")
        assertEquals(listOf("Appeal"), host.env.cs)
        assertTrue(host.env.opened.isEmpty())
    }

    @Test fun staleLiftedActionAfterIdentityChangeDoesNothing() {
        host.launchAppeal(risk = "risk_normal", appeals = "appeals_revoked_banned", entered10006 = true)
        val stale = host.clickAction(host.node(MeTags.APPEAL_ACTION_PREFIX + "start"))
        rule.runOnIdle { host.env.identityFlow.value = RiskVisualEnv.member(RiskVisualEnv.ACCOUNT_B, 2) }
        host.idle()
        rule.runOnIdle { stale() }
        host.idle()
        assertTrue("换身份后旧执行器的结果页按钮零动作", host.env.opened.isEmpty())
    }

    @Test fun staleAgainAfterItWasUsedDoesNothing() {
        host.launchAppeal(appeals = "appeals_upheld_banned")
        val stale = host.clickAction(host.node(MeTags.APPEAL_AGAIN))
        host.tap(MeTags.APPEAL_AGAIN)
        assertTrue(host.has(MeTags.APPEAL_INPUT))
        host.input(RiskVisualFixtures.input)
        rule.runOnIdle { stale() }
        host.idle()
        val text =
            host
                .editable()
                .fetchSemanticsNode()
                .config
                .getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.EditableText) { null }
                ?.text
        assertEquals("旧【再次申诉】回调不清空已输入（版本已变）", RiskVisualFixtures.input, text)
        assertEquals("零请求", 0, host.api.count(RiskVisualApi.SUBMIT))
    }

    // ---------------------------------------------------------------- 辅助

    private fun primary(
        risk: String,
        route: String,
        appeals: String = "appeals_empty",
    ) {
        host.launchRisk(risk = risk, appeals = appeals, withdrawals = "withdrawals_none")
        host.tap(MeTags.RISK_PRIMARY)
        assertEquals("主按钮一次 = 导航一次（去向不变）", listOf(route), host.env.opened)
        assertTrue(host.env.cs.isEmpty() && host.env.logouts.isEmpty())
    }

    private fun action(
        risk: String,
        appeals: String,
        raw: String,
        route: String,
        entered10006: Boolean? = null,
    ) {
        host.launchAppeal(risk = risk, appeals = appeals, entered10006 = entered10006)
        host.tap(MeTags.APPEAL_ACTION_PREFIX + raw)
        assertEquals("结果页「$raw」一次 = 导航一次", listOf(route), host.env.opened)
        assertEquals("不新增请求", listOf(RiskVisualApi.GET_ME, RiskVisualApi.LIST_APPEALS), host.api.ops())
    }
}
