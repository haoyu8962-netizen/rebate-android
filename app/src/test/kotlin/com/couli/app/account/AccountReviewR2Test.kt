@file:Suppress("MagicNumber")

package com.couli.app.account

import android.annotation.SuppressLint
import android.app.Application
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText as textMatcher
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.toRoute
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.routing.RouteDestination
import com.couli.core.network.PendingKeyStore
import com.couli.core.router.SessionState
import com.couli.core.router.UpdateCheck
import com.couli.feature.login.BindPhonePendingTags
import com.couli.feature.login.BindPhoneTags
import com.couli.feature.login.BindPhoneViewModel
import com.couli.feature.me.AccountTags
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * APP-13a Android 第 2 轮评审（android-review-r2.md，R2-01 S0 + R2-02..R2-09 S1）的回归测试：走真实 AppShell、Router 与网络层管线，只把传输层换成本地假服务。
 * 用例名带评审编号。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions", "LargeClass")
class AccountReviewR2Test : AccountHarness() {
    /** 真实页面上某个控件此刻渲染出的点击回调（保留下来，换代后再执行）。 */
    private fun clickOf(tag: String): () -> Unit {
        val node =
            composeRule
                .onAllNodesWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .first()
        val action = node.config.getOrNull(SemanticsActions.OnClick)?.action
        assertNotNull("控件没有点击动作", action)
        return { composeRule.runOnUiThread { action!!() } }
    }

    /** 栈里那个绑手机页条目上的 ViewModel（页面经真实 Router 打开、由 NavBackStackEntry 持有）。 */
    @SuppressLint("RestrictedApi")
    private fun bindModel(): BindPhoneViewModel {
        lateinit var model: BindPhoneViewModel
        composeRule.runOnUiThread {
            val entry =
                navController.currentBackStack.value.last {
                    it.destination.hasRoute(RouteDestination::class) && it.toRoute<RouteDestination>().name == BIND_PHONE
                }
            model = ViewModelProvider.create(entry)[BindPhoneViewModel::class]
        }
        return model
    }

    /** 从设置页「手机号」行进 BindPhone（未绑手机的账号）。 */
    private fun openBindPhone() {
        serverState.phoneBound = false
        launch()
        open(SETTINGS)
        idleUntil { has(PHONE_ROW) && enabled(PHONE_ROW) }
        click(PHONE_ROW)
        idleUntil("BindPhone") { has(BindPhoneTags.PAGE) }
    }

    /** BindPhone 首次绑定超时 → 待确认卡（手机号、验证码仍在输入框里）。 */
    private fun bindPhoneTimesOut() {
        openBindPhone()
        type(BindPhoneTags.PHONE, PHONE_NEW)
        tap(BindPhoneTags.GET_CODE)
        enqueueTimeout("POST", BIND)
        type(BindPhoneTags.CODE, CODE_OK)
        if (all("POST", BIND).isEmpty() && has(BindPhoneTags.SUBMIT)) tap(BindPhoneTags.SUBMIT)
        idleUntil("待确认卡") { has(BindPhonePendingTags.CARD) }
    }

    /** 被别的页面盖住再回来（真实生命周期：暂停 → 恢复）。 */
    private fun coverAndReturn(after: () -> Unit = {}) {
        open("Help")
        settle()
        after()
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
    }

    /** 网络层「会话已保存 / 刷新」通知在生产里做的同步（NetworkHooks.onSessionStored → AppLogin.onSessionStored → AppMe.syncIdentity）。 */
    private fun sessionStored() {
        login.onSessionStored(tokens.generation)
        me.syncIdentity()
    }

    private fun advance(millis: Long) {
        now += millis
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
        composeRule.mainClock.advanceTimeBy(millis)
        settle()
    }

    /** Router 最近一次 open 的目标路由。 */
    private fun lastRoute(): String? =
        routing.router.lastOutcome.value
            ?.request
            ?.route

    /** 合并树里带这段文字、可点击的按钮此刻是否可用。 */
    private fun buttonEnabled(text: String): Boolean =
        composeRule
            .onAllNodes(textMatcher(text) and hasClickAction())
            .fetchSemanticsNodes()
            .first()
            .config
            .getOrNull(SemanticsProperties.Disabled) == null

    // ---------------------------------------------------------------- R2-01 S0

    /**
     * R2-01：A 的 BindPhone 停在前台（手机号、验证码已填，待确认卡在）→ 别处保存了 B 的会话 → 在会话保存通知的同一条主线程消息里，页面已换新实例、清空手机号 /
     * 验证码 / 待确认卡（不等一秒计时或回到前台）；之前渲染出的确认回调不发请求。
     */
    @Test
    fun r2_01_aForegroundBindPhoneIsClearedInTheSameMessageWhenAnotherAccountIsSavedElsewhere() {
        bindPhoneTimesOut()
        val model = bindModel()
        val oldConfirm = clickOf(BindPhonePendingTags.CONFIRM)
        val before = model.state.value
        assertEquals(PHONE_NEW, before.phone)
        assertNotNull(before.pendingKey)
        var after = before
        composeRule.runOnUiThread {
            tokens.save("access-$USER_B", "refresh-$USER_B", "full", USER_B)
            sessionStored()
            // 同一条消息里读：不经过任何计时、回前台或重组
            after = model.state.value
        }
        assertTrue("同步换新表单实例", after.binding !== before.binding)
        assertEquals("同步清空手机号", "", after.phone)
        assertEquals("同步清空验证码", "", after.code)
        assertNull("同步清掉 A 的待确认卡", after.pendingKey)
        assertEquals("新实例绑定 B", USER_B, after.binding.owner.account)
        val posts = all("POST", BIND).size
        oldConfirm()
        settle()
        assertEquals("旧确认回调不受理", posts, all("POST", BIND).size)
        assertFalse("页面上不再有 A 的手机号", hasText(PHONE_NEW))
    }

    /** R2-01：同一账号刷新令牌改变作用域（loginEpoch 不变）→ 旧表单同步作废、清空；旧的输入、提交、确认回调都不再写模型或发包。 */
    @Test
    fun r2_01_aSameAccountScopeChangeInvalidatesTheOldFormAndItsCallbacks() {
        bindPhoneTimesOut()
        val model = bindModel()
        val oldConfirm = clickOf(BindPhonePendingTags.CONFIRM)
        val old = model.state.value.binding
        val epoch = tokens.loginEpoch
        var after = model.state.value
        composeRule.runOnUiThread {
            assertTrue(tokens.replaceIf(tokens.generation, "access-$USER_A", "refresh-$USER_A", "deletion_only"))
            sessionStored()
            after = model.state.value
        }
        assertEquals("刷新不换登录世代", epoch, tokens.loginEpoch)
        assertTrue("作用域变化即换新实例", after.binding !== old)
        assertEquals("deletion_only", after.binding.owner.scope)
        assertEquals("旧表单清空", "", after.phone)
        val posts = all("POST", BIND).size
        oldConfirm()
        composeRule.runOnUiThread {
            model.onPhoneChange(PHONE_OLD, old)
            model.onCodeChange(CODE_OK, old)
            model.onSubmit(old)
        }
        settle()
        assertEquals("旧回调不发包", posts, all("POST", BIND).size)
        assertEquals("旧回调不写新表单", "", model.state.value.phone)
    }

    // ---------------------------------------------------------------- R2-02

    /**
     * R2-02：本机版本已受支持、仍持有 deletion_only 会话（不在强更）→ 强更页标签的 getDeletion 回 10405 → 网络层只刷新（REFRESH_ONLY）→ 读取结算只认 Effect，
     * 不进强更。
     */
    @Test
    fun r2_02_aRefreshOnly10405OnTheLabelReadDoesNotEnterForceUpdate() {
        refreshScope = "deletion_only"
        enqueueCode("GET", DELETION, 10405, """{"min_supported_version":"0.0.1"}""")
        launch(scope = "deletion_only")
        idleUntil("标签读取已发出") { all("GET", DELETION).isNotEmpty() }
        idleUntil("网络层只刷新") { all("POST", "/v1/auth/refresh").isNotEmpty() }
        settle()
        settle()
        assertNotEquals("REFRESH_ONLY 的 10405 不进强更", UpdateCheck.BELOW_MIN, update.routerUpdate)
        assertNotEquals(FORCE_UPDATE, top())
    }

    /** R2-02：同上，但刷新失败（清会话）→ 沿用网络层结论（清会话），读取结算不进强更。 */
    @Test
    fun r2_02_aFailedRefreshAfterTheLabelRead10405DoesNotEnterForceUpdate() {
        refreshScope = "deletion_only"
        enqueueCode("GET", DELETION, 10405, """{"min_supported_version":"0.0.1"}""")
        // 标签读取之前的刷新（受限令牌在升级后先刷新）照常成功、换一个新令牌；标签读取（用新令牌）得到 10405 之后的那次刷新失败
        repeat(6) {
            enqueue("POST", "/v1/auth/refresh") {
                if (all("GET", DELETION).isEmpty()) {
                    envelope(
                        """{"access_token":"access-$USER_A-r1","access_expires_at":"2099-01-01T00:00:00+08:00",""" +
                            """"refresh_token":"refresh-$USER_A","refresh_expires_at":"2099-01-01T00:00:00+08:00",""" +
                            """"session_scope":"deletion_only"}""",
                    )
                } else {
                    envelope("null", 10404)
                }
            }
        }
        launch(scope = "deletion_only")
        idleUntil("标签读取已发出") { all("GET", DELETION).isNotEmpty() }
        idleUntil("刷新失败清会话") { tokens.accessToken == null }
        settle()
        settle()
        assertNotEquals("刷新失败的 10405 不进强更", UpdateCheck.BELOW_MIN, update.routerUpdate)
        assertNotEquals(FORCE_UPDATE, top())
    }

    // ---------------------------------------------------------------- R2-03

    /**
     * R2-03：进 BindPhone 时 phone_bound 未知 → 补查 getMe 在途时打开 Help 盖住 → 补查回来已绑定：绑定状态照常结算（守卫变为已绑手机），但不按路由名出栈，
     * BindPhone 与盖在上面的 Help 都还在。
     */
    @Test
    fun r2_03_aLateRecheckDoesNotCloseTheCoveringPageOrTheBindPhonePage() {
        serverState.phoneBound = false
        launch()
        idleUntil("冷启动的 phone_bound 已查清") { login.phoneBinding.get(tokens.loginEpoch) == false }
        val hold = CountDownLatch(1)
        meHold = hold
        // 同一账号重新登录：新登录世代的 phone_bound 未知（查询停在服务端）
        switchSession(USER_A)
        assertNull(login.phoneBinding.get(tokens.loginEpoch))
        open(BIND_PHONE)
        idleUntil("BindPhone") { top() == BIND_PHONE && has(BindPhoneTags.PAGE) }
        open("Help")
        idleUntil("Help 盖住") { top() == "Help" }
        val stackBefore = stack()
        serverState.phoneBound = true
        hold.countDown()
        meHold = null
        idleUntil("绑定状态照常结算") { routing.session.value == SessionState.PHONE_BOUND }
        settle()
        settle()
        assertEquals("不按路由名出栈 BindPhone，也不连带关掉 Help", stackBefore, stack())
    }

    // ---------------------------------------------------------------- R2-04

    /** R2-04：申请得 30416 → 自动重取钱包失败（资金区重试）→ 余额回正后点资金区【重试】读到 negative_fen=0 → 不再显示负余额，可以重新申请。 */
    @Test
    fun r2_04_aUserFundsRetryAfter30416ReadsTheNewBalanceAndReEnablesApply() {
        serverState.requireStepUp = false
        launch()
        openDeleteFromSettings()
        serverState.negativeFen = 200
        enqueueCode("POST", DELETION, 30416, """{"amount_fen":200}""")
        enqueueCode("GET", WALLET, 50001)
        tap(AccountTags.DELETE_WAIVER)
        tap(AccountTags.DELETE_SUBMIT)
        idleUntil("30416 后的自动重取失败：资金区重试") { has(AccountTags.DELETE_FUNDS_RETRY) }
        serverState.negativeFen = 0
        click(AccountTags.DELETE_FUNDS_RETRY)
        idleUntil("重试读到新余额") { has(AccountTags.DELETE_WITHDRAWABLE) }
        settle()
        assertFalse("按新读取更新资格：不再是负余额", has(AccountTags.DELETE_NEGATIVE_NOTICE))
        click(AccountTags.DELETE_WAIVER)
        assertTrue("可以重新申请", enabled(AccountTags.DELETE_SUBMIT))
    }

    // ---------------------------------------------------------------- R2-05

    /**
     * R2-05：换号实例 A 提交在途时返回设置 → 进实例 B、完成第 1 步（私有 token T2）→ A 成功（共享结算）→ B 的私有 token 同步失效：B 提交不带 T2。
     */
    @Test
    fun r2_05_aPhoneChangeSuccessElsewhereInvalidatesThisFlowsPrivateStepUpToken() {
        launch()
        openPhoneChange()
        passStep1()
        fillStep2()
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        enqueue("POST", BIND) {
            arrived.countDown()
            release.await(WAIT_S, TimeUnit.SECONDS)
            envelope("{}")
        }
        tap(AccountTags.PHONE_SUBMIT)
        assertTrue(arrived.await(WAIT_S, TimeUnit.SECONDS))
        androidx.test.espresso.Espresso
            .pressBack()
        settle()
        idleUntil("A 退出到设置") { !has(AccountTags.PHONE_PAGE) && has(PHONE_ROW) }
        click(PHONE_ROW)
        idleUntil("实例 B") { has(AccountTags.PHONE_PAGE) }
        passStep1()
        val tokenB = all("POST", STEP_UP).size
        assertTrue("B 拿到了自己的 token", tokenB >= 2)
        fillStep2()
        release.countDown()
        idleUntil("A 成功、B 解除在途限制") { records().isEmpty() && enabled(AccountTags.PHONE_SUBMIT) }
        val posts = all("POST", BIND).size
        tap(AccountTags.PHONE_SUBMIT)
        idleUntil("B 提交已发出") { all("POST", BIND).size > posts }
        assertNull("别处成功后 B 的私有 token 已失效，不带入请求", all("POST", BIND).last().stepUp)
    }

    // ---------------------------------------------------------------- R2-06

    /** R2-06：第 2 步退后台再回前台（可见世代推进）→ 发码得 44001 → 页面有效且可见，立即展示风控弹窗（不暂存）。 */
    @Test
    fun r2_06_aStep2Send44001AfterReturningToTheForegroundShowsTheRiskDialogAtOnce() {
        launch()
        openPhoneChange()
        passStep1()
        type(AccountTags.PHONE_NEW, PHONE_NEW)
        coverAndReturn()
        enqueueCode("POST", SMS, 44001)
        click(AccountTags.PHONE_STEP2_SEND)
        idleUntil("立即展示风控弹窗（含【联系客服申诉】）") { has(AccountTags.PHONE_RISK_APPEAL) }
    }

    // ---------------------------------------------------------------- R2-07

    /**
     * R2-07：待确认【确认】得 10001 → 页面许可有效时经 Router 引导去登录；被盖住期间得到的 10001 → 回来后保留绑定本实例的手动入口，点了经 Router 打开。
     * 两种情况记录都保持待确认，不自动提交。
     */
    @Test
    fun r2_07_aPendingConfirmPrerequisiteGuidesThroughTheRouterOrKeepsAManualEntry() {
        bindPhoneTimesOut()
        enqueueCode("POST", BIND, 10001)
        tap(BindPhonePendingTags.CONFIRM)
        idleUntil("许可有效：经 Router 引导去登录") { lastRoute() == "Login" }
        assertEquals("记录保持待确认", PendingKeyStore.UNKNOWN, records().single().stateRaw)
        // 回到 BindPhone（关掉引导打开的页面）
        while (top() != BIND_PHONE && stack().size > 1) {
            composeRule.runOnUiThread { navController.popBackStack() }
            settle()
        }
        idleUntil("回到待确认卡") { has(BindPhonePendingTags.CARD) && enabled(BindPhonePendingTags.CONFIRM) }

        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        enqueue("POST", BIND) {
            arrived.countDown()
            release.await(WAIT_S, TimeUnit.SECONDS)
            envelope("null", 10001)
        }
        tap(BindPhonePendingTags.CONFIRM)
        assertTrue(arrived.await(WAIT_S, TimeUnit.SECONDS))
        coverAndReturn {
            release.countDown()
            settle()
            settle()
        }
        idleUntil("许可失效：保留手动入口") { has(BindPhonePendingTags.GUIDE) }
        assertEquals("盖住期间不导航", BIND_PHONE, top())
        val posts = all("POST", BIND).size
        click(BindPhonePendingTags.GUIDE)
        idleUntil("手动入口经 Router 打开") { lastRoute() == "Login" }
        settle()
        assertEquals("补办不自动提交", posts, all("POST", BIND).size)
        assertEquals("记录保持待确认", PendingKeyStore.UNKNOWN, records().single().stateRaw)
    }

    // ---------------------------------------------------------------- R2-08

    /** R2-08：待确认【确认】得 50001、【放弃】得 50401，都带 trace_id → 提示用统一格式化，附 trace_id 末 6 位。 */
    @Test
    fun r2_08_pendingConfirmAndAbandon5xxxxShowTrace6() {
        bindPhoneTimesOut()
        enqueueCode("POST", BIND, 50001, trace = "trace-abc987")
        tap(BindPhonePendingTags.CONFIRM)
        idleUntil("确认的提示带 trace6") { has(BindPhonePendingTags.NOTICE) && textOf(BindPhonePendingTags.NOTICE).contains("abc987") }
        idleUntil { enabled(BindPhonePendingTags.ABANDON) }
        enqueueCode("POST", ABANDON, 50401, trace = "trace-def654")
        tap(BindPhonePendingTags.ABANDON)
        tap(BindPhonePendingTags.ABANDON_OK)
        idleUntil("放弃的提示带 trace6") { has(BindPhonePendingTags.NOTICE) && textOf(BindPhonePendingTags.NOTICE).contains("def654") }
    }

    // ---------------------------------------------------------------- R2-09

    /** R2-09：钱包摘要 42901（Retry-After 30）→ 资金区【重试】置灰；到期同步恢复（重取成功或按钮可用）。 */
    @Test
    fun r2_09_theFundsRetryFollowsTheWalletReadGateAndRecoversOnExpiry() {
        launch()
        openDeleteFromSettings()
        // 回到前台的明确刷新只发一次钱包摘要读取：它得到 42901
        enqueueCode("GET", WALLET, 42901, headers = mapOf("Retry-After" to "30"))
        coverAndReturn()
        idleUntil("资金区读取限流") { has(AccountTags.DELETE_FUNDS_RETRY) && account.deps.walletRead.active }
        settle()
        assertFalse("限流期间资金区【重试】置灰", enabled(AccountTags.DELETE_FUNDS_RETRY))
        advance(31_000L)
        idleUntil("到期恢复") {
            !account.deps.walletRead.active && (!has(AccountTags.DELETE_FUNDS_RETRY) || enabled(AccountTags.DELETE_FUNDS_RETRY))
        }
    }

    /** R2-09：getDeletion 42901（Retry-After 30）→ 读取失败态【重试】置灰；到期同步恢复。 */
    @Test
    fun r2_09_theDeletionLoadRetryFollowsTheDeletionReadGateAndRecoversOnExpiry() {
        launch()
        openDeleteFromSettings()
        enqueueCode("GET", DELETION, 42901, headers = mapOf("Retry-After" to "30"))
        coverAndReturn()
        idleUntil("读取失败态") { has(AccountTags.DELETE_LOAD_ERROR) && account.deps.deletionRead.active }
        settle()
        val retry = t("deletion.retry")
        assertFalse("限流期间读取失败态【重试】置灰", buttonEnabled(retry))
        advance(31_000L)
        idleUntil("到期恢复") { !account.deps.deletionRead.active && (!has(AccountTags.DELETE_LOAD_ERROR) || buttonEnabled(retry)) }
    }

    private companion object {
        const val BIND_PHONE = "BindPhone"
    }
}
