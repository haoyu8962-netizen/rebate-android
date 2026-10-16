@file:Suppress("MagicNumber")

package com.couli.app.account

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.me.account.KeyedDeadlines
import com.couli.core.network.ApiCall
import com.couli.feature.login.BindPhoneTags
import com.couli.feature.me.AccountTags
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FU-AND-0717 第 7 项（APP-13a 共性 49，鸿蒙 FU-HM-0722 R3-02 同类）：限流（42901）写入闸门前复核发起时的完整身份（账号 + 登录世代 + 作用域）。
 * 同账号同登录世代里会话作用域经真实刷新从 full 变成 deletion_only（[refreshTo]）之后，迟到的旧请求 42901 不写进新作用域的闸门；对照：作用域不变时照常写入。
 * 注销申请 / 确认 / 放弃 / 撤回、换号提交与第 2 步发码、绑手机的绑定与发码各一例。等待都按「请求已到假服务 → 刷新完成 → 放行 → 动作后起的协程全部结束」结算后再断言。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions")
class AccountFollowupAnd0717ScopeGateTest : AccountHarness() {
    /** 一次挂住的 42901 应答：请求到达后等测试放行。 */
    private inner class Held {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)

        fun respond(): MockResponse {
            arrived.countDown()
            release.await(com.couli.app.FlowWait.CEILING_MS, TimeUnit.MILLISECONDS)
            return envelope("null", 42901, "trace-429001").setHeader("Retry-After", "30")
        }

        fun awaitArrived() = assertTrue("挂住的请求已到达假服务", arrived.await(com.couli.app.FlowWait.CEILING_MS, TimeUnit.MILLISECONDS))
    }

    /** 一次真实的令牌刷新（getMe 收到 10002 → 单飞刷新），刷新结果的作用域为 [scope]；同账号、同登录世代。 */
    private fun refreshTo(scope: String) {
        val epoch = tokens.loginEpoch
        refreshScope = scope
        accessExpired = true
        Thread { api.execute(ApiCall("getMe")) }.start()
        idleUntil("刷新为同世代的 $scope") { account.identity.value.scope == scope }
        assertEquals("同一登录世代", epoch, tokens.loginEpoch)
    }

    /** 三个会话级作用域（登录、账号、「我的」）里此刻在跑的协程。 */
    private fun running(): Set<kotlinx.coroutines.Job> =
        listOf(loginSessionScope, account.deps.service, me.deps.service)
            .flatMap { scope -> scope.coroutineContext[kotlinx.coroutines.Job]!!.children.filter { it.isActive } }
            .toSet()

    /**
     * [action] 发出请求（[path] 挂住）→ 请求到达 → 记下这次动作起的协程（发送与结算的那个任务）→ [changeScope] 为 true 时同世代刷新为 deletion_only →
     * 放行 42901 → 等这次动作起的协程全部结束（结算完成；闸门之后起的到期计时不在其中）再断言。
     */
    private fun heldRateLimit(
        method: String,
        path: String,
        changeScope: Boolean,
        action: () -> Unit,
    ) {
        val held = Held()
        enqueue(method, path) { held.respond() }
        val sent = all(method, path).size
        val before = running()
        val mine: Set<kotlinx.coroutines.Job>
        try {
            action()
            held.awaitArrived()
            mine = running() - before
            assertTrue("这次动作起了结算任务", mine.isNotEmpty())
            if (changeScope) refreshTo("deletion_only")
        } finally {
            held.release.countDown()
        }
        idleUntil("迟到的 42901 结算完") { mine.none { it.isActive } }
        assertEquals("这次只发出 1 个请求", sent + 1, all(method, path).size)
    }

    // ---------------------------------------------------------------- 注销

    private fun openDelete() {
        serverState.requireStepUp = false
        launch()
        openDeleteFromSettings()
    }

    @Test
    fun fuand0717_c49_aLateDeletionApply42901AfterASameEpochScopeChangeDoesNotGateTheNewScope() {
        openDelete()
        heldRateLimit("POST", DELETION, changeScope = true) { checkWaiverAndSubmit() }
        assertFalse("迟到的 42901 不写新作用域的注销闸门", account.shared.deletionGate.active)
    }

    @Test
    fun fuand0717_c49_control_aDeletionApply42901WithoutAScopeChangeStillGates() {
        openDelete()
        heldRateLimit("POST", DELETION, changeScope = false) { checkWaiverAndSubmit() }
        assertTrue("作用域没变：照常写入", account.shared.deletionGate.active)
    }

    /** 申请超时 → 待确认卡（结果未知）。 */
    private fun deletionPending() {
        openDelete()
        enqueueTimeout("POST", DELETION)
        checkWaiverAndSubmit()
        idleUntil("待确认卡") { has(AccountTags.PENDING_CARD) && enabled(AccountTags.PENDING_CONFIRM) }
    }

    @Test
    fun fuand0717_c49_aLateDeletionConfirm42901AfterASameEpochScopeChangeDoesNotGateTheNewScope() {
        deletionPending()
        heldRateLimit("POST", DELETION, changeScope = true) { tap(AccountTags.PENDING_CONFIRM) }
        assertFalse("迟到的确认 42901 不写新作用域的注销闸门", account.shared.deletionGate.active)
    }

    @Test
    fun fuand0717_c49_aLateDeletionAbandon42901AfterASameEpochScopeChangeDoesNotGateTheNewScope() {
        deletionPending()
        heldRateLimit("POST", ABANDON, changeScope = true) {
            tap(AccountTags.PENDING_ABANDON)
            idleUntil("放弃二次确认") { has(AccountTags.ABANDON_OK) }
            tap(AccountTags.ABANDON_OK)
        }
        assertFalse("迟到的放弃 42901 不写新作用域的注销闸门", account.shared.deletionGate.active)
    }

    @Test
    fun fuand0717_c49_aLateWithdraw42901AfterASameEpochScopeChangeDoesNotGateTheNewScope() {
        serverState.deletion = deletionJson("cooling", canCancel = true)
        launch()
        openDeleteFromSettings()
        idleUntil("冷静期") { has(AccountTags.DELETE_CANCEL) && enabled(AccountTags.DELETE_CANCEL) }
        heldRateLimit("POST", CANCEL, changeScope = true) { tap(AccountTags.DELETE_CANCEL) }
        assertFalse("迟到的撤回 42901 不写新作用域的撤回闸门", account.shared.cancelGate.active)
    }

    // ---------------------------------------------------------------- 换号

    @Test
    fun fuand0717_c49_aLatePhoneChangeSubmit42901AfterASameEpochScopeChangeDoesNotGateTheNewScope() {
        serverState.requireStepUp = false
        launch()
        openPhoneChange()
        passStep1()
        fillStep2()
        idleUntil("【提交】可用") { enabled(AccountTags.PHONE_SUBMIT) }
        heldRateLimit("POST", BIND, changeScope = true) { tap(AccountTags.PHONE_SUBMIT) }
        assertFalse("迟到的换号提交 42901 不写新作用域的换号闸门", account.shared.phoneGate.active)
    }

    @Test
    fun fuand0717_c49_control_aPhoneChangeSubmit42901WithoutAScopeChangeStillGates() {
        serverState.requireStepUp = false
        launch()
        openPhoneChange()
        passStep1()
        fillStep2()
        idleUntil("【提交】可用") { enabled(AccountTags.PHONE_SUBMIT) }
        heldRateLimit("POST", BIND, changeScope = false) { tap(AccountTags.PHONE_SUBMIT) }
        assertTrue("作用域没变：照常写入", account.shared.phoneGate.active)
    }

    @Test
    fun fuand0717_c49_aLatePhoneChangeStep2Send42901AfterASameEpochScopeChangeDoesNotGateTheNewScope() {
        serverState.requireStepUp = false
        launch()
        openPhoneChange()
        passStep1()
        type(AccountTags.PHONE_NEW, PHONE_NEW)
        heldRateLimit("POST", SMS, changeScope = true) { click(AccountTags.PHONE_STEP2_SEND) }
        val key = KeyedDeadlines.bindKey(USER_A, PHONE_NEW, "bind")
        assertEquals("迟到的第 2 步发码 42901 不写新作用域的发码截止", 0L, account.shared.bindSms.remaining(key))
    }

    /** 第 1 步发码（发到原手机号；stillOwned 只看登录世代，作用域变化后仍会结算）：迟到的 42901 不写新作用域的第 1 步发码限流。 */
    @Test
    fun fuand0717_c49_aLatePhoneChangeStep1Send42901AfterASameEpochScopeChangeDoesNotGateTheNewScope() {
        serverState.requireStepUp = false
        launch()
        openPhoneChange()
        heldRateLimit("POST", SMS, changeScope = true) { click(AccountTags.PHONE_STEP1_SEND) }
        val slot =
            com.couli.core.wallet.stepup.SmsDeadlines
                .Slot(USER_A, PHONE_OLD, "phone_change")
        assertEquals("迟到的第 1 步发码 42901 不写限流", 0L, account.stepUp.sms.limitedLeft(slot, now))
    }

    @Test
    fun fuand0717_c49_control_aPhoneChangeStep1Send42901WithoutAScopeChangeStillGates() {
        serverState.requireStepUp = false
        launch()
        openPhoneChange()
        heldRateLimit("POST", SMS, changeScope = false) { click(AccountTags.PHONE_STEP1_SEND) }
        val slot =
            com.couli.core.wallet.stepup.SmsDeadlines
                .Slot(USER_A, PHONE_OLD, "phone_change")
        assertTrue("作用域没变：照常写入", account.stepUp.sms.limitedLeft(slot, now) > 0L)
    }

    // ---------------------------------------------------------------- 绑手机

    private fun openBindPhone() {
        serverState.phoneBound = false
        launch()
        open(SETTINGS)
        idleUntil { has(PHONE_ROW) && enabled(PHONE_ROW) }
        click(PHONE_ROW)
        idleUntil("BindPhone") { has(BindPhoneTags.PAGE) }
    }

    @Test
    fun fuand0717_c49_aLateBindPhoneBind42901AfterASameEpochScopeChangeDoesNotGateTheNewScope() {
        openBindPhone()
        type(BindPhoneTags.PHONE, PHONE_NEW)
        sendBindCode()
        heldRateLimit("POST", BIND, changeScope = true) {
            type(BindPhoneTags.CODE, CODE_OK)
            submitBindIfNotStarted(requireNoRequest = true)
        }
        assertFalse("迟到的绑定 42901 不写新作用域的绑定闸门", account.shared.phoneGate.active)
    }

    @Test
    fun fuand0717_c49_control_aBindPhoneBind42901WithoutAScopeChangeStillGates() {
        openBindPhone()
        type(BindPhoneTags.PHONE, PHONE_NEW)
        sendBindCode()
        heldRateLimit("POST", BIND, changeScope = false) {
            type(BindPhoneTags.CODE, CODE_OK)
            submitBindIfNotStarted(requireNoRequest = true)
        }
        assertTrue("作用域没变：照常写入", account.shared.phoneGate.active)
    }

    @Test
    fun fuand0717_c49_aLateBindPhoneSend42901AfterASameEpochScopeChangeDoesNotGateTheNewScope() {
        openBindPhone()
        type(BindPhoneTags.PHONE, PHONE_NEW)
        heldRateLimit("POST", SMS, changeScope = true) { tap(BindPhoneTags.GET_CODE) }
        assertEquals("迟到的发码 42901 不写发码截止", 0, login.bindPhone.sendLimitedRemainingSec(PHONE_NEW))
    }

    @Test
    fun fuand0717_c49_control_aBindPhoneSend42901WithoutAScopeChangeStillGates() {
        openBindPhone()
        type(BindPhoneTags.PHONE, PHONE_NEW)
        heldRateLimit("POST", SMS, changeScope = false) { tap(BindPhoneTags.GET_CODE) }
        assertTrue("作用域没变：照常写入", login.bindPhone.sendLimitedRemainingSec(PHONE_NEW) > 0)
    }
}
