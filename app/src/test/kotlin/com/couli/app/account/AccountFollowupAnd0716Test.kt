@file:Suppress("MagicNumber")

package com.couli.app.account

import android.annotation.SuppressLint
import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.toRoute
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.FlowWait
import com.couli.app.routing.RouteDestination
import com.couli.core.router.RouteSource
import com.couli.core.router.SessionState
import com.couli.feature.login.BindPhoneDialog
import com.couli.feature.login.BindPhoneTags
import com.couli.feature.login.BindPhoneViewModel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FU-AND-0716 第 3 项（iOS APP-05b 第 1 轮评审教训回灌）在真实 AppShell + Router + AppLogin + BindPhone 页上的回归：
 * - ④ R1-04：绑定写请求的回调核对发起时的页面许可——发起后页面被覆盖（或进后台）再回 30411 mergeable：不弹并号框、不留并号凭据补弹；
 * - ⑥ R1-09：页面级绑定限流（Retry-After）按会话归属隔离——A 的限流不挡 B 的绑定；
 * - R1-08：进页补查在页面被覆盖期间查明已绑，回到页面时只退出这一个绑手机页并按 resumeOnly 恢复待办。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccountFollowupAnd0716Test : AccountHarness() {
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

    /** [sends]：这次会真的发码（同一号码仍在倒计时时按钮置灰、不发，B 换实例后重填即如此）。 */
    private fun fillForm(sends: Boolean = true) {
        val model = bindModel()
        val seq = model.state.value.resultSeq
        type(BindPhoneTags.PHONE, PHONE_NEW)
        tap(BindPhoneTags.GET_CODE)
        // 发码结果已在页面结算（不只是请求到达假服务），免得它的结算序号算到后面的提交上
        idleUntil("发码") { all("POST", SMS).isNotEmpty() && (!sends || model.state.value.resultSeq > seq) }
        type(BindPhoneTags.CODE, CODE_OK)
    }

    /**
     * F-AND-36（R1-02）：等页面把 [from] 之后的那一次写请求结果结算完（[com.couli.feature.login.BindPhoneUiState.resultSeq] 推进：等待结束、按许可落地或丢弃），
     * 不看网络层是否空闲。
     */
    private fun pageSettled(
        model: BindPhoneViewModel,
        from: Int,
    ) = idleUntil("页面结算写请求结果") { model.state.value.resultSeq > from }

    /** ④（iOS R1-04）：提交后被别的页面盖住，期间回 30411 mergeable → 不弹并号框、不保存并号凭据；回到页面也不补弹。 */
    @Test
    fun fuand0716_04_aMergeableBindAnswerAfterThePageWasCoveredNeitherShowsNorKeepsTheMergeTicket() {
        openBindPhone()
        fillForm()
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        enqueue("POST", BIND) {
            arrived.countDown()
            release.await(FlowWait.CEILING_MS, TimeUnit.MILLISECONDS)
            envelope("""{"reason":"mergeable","merge_ticket":"TEST_ONLY"}""", 30411, "trace-304110")
        }
        tap(BindPhoneTags.SUBMIT)
        assertTrue("绑定请求已到达", arrived.await(FlowWait.CEILING_MS, TimeUnit.MILLISECONDS))
        val model = bindModel()
        val seq = model.state.value.resultSeq
        // 发起之后页面被覆盖（真实生命周期：暂停）
        open("Help")
        idleUntil("Help 盖住") { top() == "Help" }
        release.countDown()
        idleUntil("提交结束") { !model.state.value.submitting }
        pageSettled(model, seq)
        assertNull("许可失效：不弹并号框", model.state.value.dialog)
        assertFalse("许可失效：不保存旧并号凭据", login.bindPhone.mergePending)
        // 回到页面：不补弹已失效请求的并号框
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        idleUntil("回到 BindPhone") { top() == BIND_PHONE }
        assertNull("回来不补弹", model.state.value.dialog)
        assertFalse(has(BindPhoneTags.MERGE_OK))
    }

    /**
     * ④（iOS R1-04）：首次绑定超时 → 待确认卡【确认上一次提交】在途时页面被盖住，确认回 30411 mergeable → 不弹并号框、不保存并号凭据，
     * 回来也不补弹（待确认卡照共享状态重新读取）。
     */
    @Test
    fun fuand0716_04_aMergeablePendingConfirmAfterThePageWasCoveredNeitherShowsNorKeepsTheMergeTicket() {
        openBindPhone()
        fillForm()
        enqueueTimeout("POST", BIND)
        tap(BindPhoneTags.SUBMIT)
        idleUntil("待确认卡") { has(com.couli.feature.login.BindPhonePendingTags.CARD) }
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        enqueue("POST", BIND) {
            arrived.countDown()
            release.await(FlowWait.CEILING_MS, TimeUnit.MILLISECONDS)
            envelope("""{"reason":"mergeable","merge_ticket":"TEST_ONLY"}""", 30411, "trace-304110")
        }
        tap(com.couli.feature.login.BindPhonePendingTags.CONFIRM)
        assertTrue("确认请求已到达", arrived.await(FlowWait.CEILING_MS, TimeUnit.MILLISECONDS))
        val model = bindModel()
        val seq = model.state.value.resultSeq
        open("Help")
        idleUntil("Help 盖住") { top() == "Help" }
        release.countDown()
        idleUntil("确认结束") { !model.state.value.pendingBusy }
        pageSettled(model, seq)
        assertNull("许可失效：不弹并号框", model.state.value.dialog)
        assertFalse("许可失效：不保存旧并号凭据", login.bindPhone.mergePending)
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        idleUntil("回到 BindPhone") { top() == BIND_PHONE }
        assertNull("回来不补弹", model.state.value.dialog)
    }

    /** ④ 对照：页面一直可见时同样的 30411 mergeable 照常弹并号框（改动只挡失效许可）。 */
    @Test
    fun fuand0716_04_theSameMergeableAnswerWhileThePageStaysVisibleShowsTheMergeDialog() {
        openBindPhone()
        fillForm()
        enqueueCode("POST", BIND, 30411, """{"reason":"mergeable","merge_ticket":"TEST_ONLY"}""")
        tap(BindPhoneTags.SUBMIT)
        val model = bindModel()
        idleUntil("并号框") { model.state.value.dialog == BindPhoneDialog.MERGE }
        assertTrue(login.bindPhone.mergePending)
    }

    /** 提交绑定、服务端把回答挂住；返回放行闸与绑定页模型（模型的结算序号在提交之前取得，见 [submitSeq]）。 */
    private fun submitHeldBind(): Pair<CountDownLatch, BindPhoneViewModel> {
        openBindPhone()
        fillForm()
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        enqueue("POST", BIND) {
            arrived.countDown()
            release.await(FlowWait.CEILING_MS, TimeUnit.MILLISECONDS)
            serverState.phoneBound = true
            envelope("{}")
        }
        submitSeq = bindModel().state.value.resultSeq
        tap(BindPhoneTags.SUBMIT)
        assertTrue("绑定请求已到达", arrived.await(FlowWait.CEILING_MS, TimeUnit.MILLISECONDS))
        return release to bindModel()
    }

    /** [submitHeldBind] 提交之前页面的结算序号。 */
    private var submitSeq = 0

    /** 共性第 25 条「成功先到」：被盖住期间绑定成功 → 不出栈；覆盖页出栈、BindPhone 重新可见 → 完成一次出栈。 */
    @Test
    fun fuand0716_25_aBindThatSucceedsWhileCoveredExitsOnceThePageIsVisibleAgain() {
        val (release, model) = submitHeldBind()
        open("Help")
        idleUntil("Help 盖住") { top() == "Help" }
        release.countDown()
        idleUntil("提交结束") { !model.state.value.submitting }
        idleUntil("共享结算：已绑") { routing.session.value == SessionState.PHONE_BOUND }
        pageSettled(model, submitSeq)
        assertTrue("被盖住期间不出栈", BIND_PHONE in stack())
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        idleUntil("回到页面后完成出栈") { BIND_PHONE !in stack() }
    }

    /** 共性第 25 条「页面先恢复」：盖住后又回到 BindPhone（许可已换代），之后绑定成功 → 页面已可见，立即完成一次出栈，不等下一次可见。 */
    @Test
    fun fuand0716_25_aBindThatSucceedsAfterThePageAlreadyCameBackExitsAtOnce() {
        val (release, model) = submitHeldBind()
        open("Help")
        idleUntil("Help 盖住") { top() == "Help" }
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        idleUntil("回到 BindPhone") { top() == BIND_PHONE }
        release.countDown()
        idleUntil("提交结束") { !model.state.value.submitting }
        idleUntil("成功后立即出栈") { BIND_PHONE !in stack() }
        assertEquals(SessionState.PHONE_BOUND, routing.session.value)
    }

    /** 共性第 26 条：A 在被盖住期间绑定成功（延后完成）→ 换成 B 的会话 → 回到 BindPhone：不按 A 的成功退出 B 的绑定页。 */
    @Test
    fun fuand0716_26_aDeferredBindCompletionOfAccountAIsDroppedWhenTheSessionSwitchesToB() {
        val (release, model) = submitHeldBind()
        open("Help")
        idleUntil("Help 盖住") { top() == "Help" }
        release.countDown()
        idleUntil("提交结束") { !model.state.value.submitting }
        pageSettled(model, submitSeq)
        composeRule.runOnUiThread {
            tokens.save("access-$USER_B", "refresh-$USER_B", "full", USER_B)
            login.onSessionStored(tokens.generation)
            me.syncIdentity()
            // B 的共享状态也是已绑（最不利的情形：只剩原归属核对挡着）
            login.phoneBinding.bindSucceeded(tokens.loginEpoch)
        }
        settle()
        composeRule.runOnUiThread { navController.popBackStack() }
        // 回到 BindPhone 且已前台（onPageVisible 已执行：延后完成记录在这里消费或作废），B 的会话同步引起的补查（若有）连同收尾结束
        idleUntil("回到 BindPhone") { bindPageResumed() && login.phoneChecksSettled() }
        assertEquals("B 的绑定页不因 A 的成功退出", BIND_PHONE, top())
        assertEquals(USER_B, model.state.value.binding.owner.account)
    }

    /** ⑥（iOS R1-09）：A 的绑定收到较长 Retry-After → 别处保存了 B 的会话（页面同步换新实例）→ B 的绑定不被 A 的截止时间挡住。 */
    @Test
    fun fuand0716_06_aRetryAfterOfAccountADoesNotBlockTheBindFormOfAccountB() {
        openBindPhone()
        fillForm()
        enqueueCode("POST", BIND, 42901, headers = mapOf("Retry-After" to "300"))
        val seq = bindModel().state.value.resultSeq
        tap(BindPhoneTags.SUBMIT)
        val model = bindModel()
        idleUntil("A 被限流") { all("POST", BIND).size == 1 && !model.state.value.submitting }
        pageSettled(model, seq)
        composeRule.runOnUiThread {
            tokens.save("access-$USER_B", "refresh-$USER_B", "full", USER_B)
            login.onSessionStored(tokens.generation)
            me.syncIdentity()
        }
        settle()
        assertEquals("页面已换成 B 的实例", USER_B, model.state.value.binding.owner.account)
        assertEquals("B 的表单没有 A 的限流", 0, model.state.value.bindLimitedSec)
        val before = all("POST", BIND).size
        fillForm(sends = false)
        composeRule.runOnUiThread { model.onSubmit() }
        idleUntil("B 的绑定照常发出") { all("POST", BIND).size == before + 1 }
        assertEquals(USER_B, all("POST", BIND).last().user)
    }

    /**
     * R1-08：冷启动后同账号重新登录，phone_bound 未知 → 主动打开手机号级页面，守卫先进 BindPhone（登记待办）→ 进页补查在途时被 Help 盖住
     * → 补查查明已绑（不导航）→ Help 出栈、BindPhone 重新有效：只退出这一个绑手机页，待办经守卫恢复（resumeOnly）。
     */
    @Test
    fun fuand0716_r1_08_aRecheckThatFoundBoundWhileCoveredClosesTheBindPageWhenItIsVisibleAgain() {
        serverState.phoneBound = false
        launch()
        idleUntil("冷启动的 phone_bound 已查清") { login.phoneBinding.get(tokens.loginEpoch) == false }
        val hold = CountDownLatch(1)
        meHold = hold
        switchSession(USER_A)
        assertNull(login.phoneBinding.get(tokens.loginEpoch))
        open(PHONE_ROUTE)
        idleUntil("守卫先进 BindPhone") { top() == BIND_PHONE && has(BindPhoneTags.PAGE) }
        assertTrue("登记了待办", routing.router.hasPendingAction)
        open("Help")
        idleUntil("Help 盖住") { top() == "Help" }
        serverState.phoneBound = true
        hold.countDown()
        meHold = null
        idleUntil("绑定状态照常结算") { routing.session.value == SessionState.PHONE_BOUND }
        // 进页补查任务连同收尾（记下延后记录、同步守卫、释放暂缓）都已结束（R1-02：等任务本身，不看网络层空闲）
        idleUntil("补查任务结束") { login.phoneChecksSettled() }
        assertTrue("覆盖期间 BindPhone 不出栈", BIND_PHONE in stack())
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        idleUntil("回到页面时只退出这一个绑手机页") { BIND_PHONE !in stack() }
        // 待办以 pending_action 来源经守卫重新判定一次（这套假服务里版本检查结论未知，守卫按 update_unknown_blocked 不打开页面——判定本身照生产）
        idleUntil("待办经守卫恢复") {
            routing.router.lastOutcome.value?.let {
                it.request.route == PHONE_ROUTE && it.request.source == RouteSource.PENDING_ACTION.raw
            } == true
        }
        assertFalse("待办已消费", routing.router.hasPendingAction)
        assertFalse(routing.router.pendingHeld)
    }

    /** 栈顶是 BindPhone 且已处于前台（RESUMED）。 */
    private fun bindPageResumed(): Boolean {
        var resumed = false
        composeRule.runOnUiThread {
            val entry = navController.currentBackStackEntry
            resumed = top() == BIND_PHONE && entry?.lifecycle?.currentState == androidx.lifecycle.Lifecycle.State.RESUMED
        }
        return resumed
    }

    private companion object {
        const val BIND_PHONE = "BindPhone"
        const val PHONE_ROUTE = "InviteShare"
    }
}
