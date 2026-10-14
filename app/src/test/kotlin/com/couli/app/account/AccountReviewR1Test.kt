@file:Suppress("MagicNumber")

package com.couli.app.account

import android.app.Application
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.me.account.PendingSettlement
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * APP-13a Android 第 1 轮评审（android-review-r1.md，R1-01 S0 + R1-02..R1-15 S1）的回归测试：每条一个用例，走真实页面、真实 Router 与网络层管线，
 * 只把传输层换成本地假服务。用例名带评审编号。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions", "LargeClass")
class AccountReviewR1Test : AccountHarness() {
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

    private fun node(tag: String) =
        composeRule
            .onAllNodesWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .first()

    /** 从设置页「手机号」行进 BindPhone（未绑手机的账号）。 */
    private fun openBindPhone() {
        serverState.phoneBound = false
        launch()
        open(SETTINGS)
        idleUntil { has(PHONE_ROW) && enabled(PHONE_ROW) }
        click(PHONE_ROW)
        idleUntil("BindPhone") { has(BindPhoneTags.PAGE) }
    }

    /** BindPhone 首次绑定超时 → 待确认卡。 */
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

    private fun collectSignals(sink: (PendingSettlement) -> Unit) =
        CoroutineScope(Dispatchers.Main.immediate).launch { account.shared.pendingSignals.collect { sink(it) } }

    private fun advance(millis: Long) {
        now += millis
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
        composeRule.mainClock.advanceTimeBy(millis)
        settle()
    }

    // ---------------------------------------------------------------- R1-01 S0

    /** R1-01：手机号、验证码不进 SavedStateHandle；系统保存状态里旧版本留下的原文在构造时清掉，进程恢复后表单为空（只靠未决记录的脱敏摘要）。 */
    @Test
    fun r1_01_bindPhoneFormNeverEntersTheSavedStateAndRestoredRawValuesAreDropped() {
        launch()
        val saved = SavedStateHandle()
        lateinit var model: BindPhoneViewModel
        composeRule.runOnUiThread {
            model = BindPhoneViewModel(login.bindPageConfig(), saved)
            model.onPhoneChange(PHONE_NEW)
            model.onCodeChange(CODE_OK)
        }
        settle()
        assertEquals("内存里的表单照常", PHONE_NEW, model.state.value.phone)
        val stored = saved.keys().mapNotNull { saved.get<Any>(it)?.toString() }
        assertTrue("系统保存状态里没有手机号原文：$stored", stored.none { it.contains(PHONE_NEW) })
        assertTrue("系统保存状态里没有验证码：$stored", stored.none { it.contains(CODE_OK) })

        // 进程恢复：系统保存状态里带着旧版本写下的原文
        val restored = SavedStateHandle(mapOf("bind_phone.phone" to PHONE_NEW, "bind_phone.code" to CODE_OK))
        lateinit var again: BindPhoneViewModel
        composeRule.runOnUiThread { again = BindPhoneViewModel(login.bindPageConfig(), restored) }
        settle()
        assertEquals("恢复后不回填手机号", "", again.state.value.phone)
        assertEquals("恢复后不回填验证码", "", again.state.value.code)
        assertFalse("原文从系统保存状态里清掉", restored.contains("bind_phone.phone") || restored.contains("bind_phone.code"))
    }

    // ---------------------------------------------------------------- R1-02

    /** R1-02：保留 A 页面渲染出的【绑定】回调 → 换成 B、页面回到前台按 B 重建、填好 B 的表单 → 执行旧回调：零请求（不提交 B 的表单）。 */
    @Test
    fun r1_02_anOldBindSubmitCallbackAfterTheSessionSwitchAndRebuildDoesNotSubmitTheNewForm() {
        openBindPhone()
        type(BindPhoneTags.PHONE, PHONE_NEW)
        type(BindPhoneTags.CODE, CODE_OK)
        val oldSubmit = clickOf(BindPhoneTags.SUBMIT)
        switchSession(USER_B)
        coverAndReturn()
        idleUntil("按 B 重建后表单为空") { has(BindPhoneTags.PAGE) && !hasText(PHONE_NEW) }
        type(BindPhoneTags.PHONE, PHONE_NEW)
        type(BindPhoneTags.CODE, CODE_OK)
        val before = all("POST", BIND).size
        oldSubmit()
        settle()
        settle()
        assertEquals("旧回调不受理：不提交新表单", before, all("POST", BIND).size)
        assertTrue(records(USER_B).isEmpty())
    }

    // ---------------------------------------------------------------- R1-03

    /** R1-03：绑定在途时离开（ViewModel 清除）→ 迟到的成功照常做全部共享结算：绑定状态、清全部 token、getMe 刷新、未决通知。 */
    @Test
    fun r1_03_aBindInFlightWhenThePageIsClearedStillRunsTheWholeSharedSettlement() {
        openBindPhone()
        val seen = mutableListOf<PendingSettlement>()
        val job = collectSignals { seen += it }
        composeRule.runOnUiThread { account.tokensCache.put("withdraw", USER_A, "tok-test-w", now + 300_000L) }
        type(BindPhoneTags.PHONE, PHONE_NEW)
        tap(BindPhoneTags.GET_CODE)
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        enqueue("POST", BIND) {
            arrived.countDown()
            release.await(WAIT_S, TimeUnit.SECONDS)
            serverState.phoneBound = true
            envelope("{}")
        }
        type(BindPhoneTags.CODE, CODE_OK)
        if (all("POST", BIND).isEmpty() && has(BindPhoneTags.SUBMIT)) tap(BindPhoneTags.SUBMIT)
        assertTrue(arrived.await(WAIT_S, TimeUnit.SECONDS))
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        assertFalse("绑手机页已出栈", has(BindPhoneTags.PAGE))
        val reads = all("GET", ME).size
        release.countDown()
        idleUntil("共享结算：本会话已绑手机") { login.phoneBinding.get(tokens.loginEpoch) == true }
        idleUntil("守卫状态变为已绑手机") { routing.session.value == SessionState.PHONE_BOUND }
        idleUntil("成功后刷新 getMe") { all("GET", ME).size > reads }
        idleUntil("未决通知") { seen.any { it.operationId == "bindPhone" } }
        assertNull("清全部 action 的 token", account.tokensCache.take("withdraw", USER_A, now))
        job.cancel()
    }

    // ---------------------------------------------------------------- R1-04

    /** R1-04：换号请求在途时进 BindPhone（没有待确认卡）→ 原请求超时、记录变为结果未知并发通知 → BindPhone 不用重进就出现待确认卡。 */
    @Test
    fun r1_04_bindPhoneShowsThePendingCardWhenAPhoneChangeElsewhereBecomesUnknown() {
        launch()
        openPhoneChange()
        passStep1()
        fillStep2()
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        enqueue("POST", BIND) {
            arrived.countDown()
            release.await(WAIT_S, TimeUnit.SECONDS)
            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
        }
        tap(AccountTags.PHONE_SUBMIT)
        assertTrue(arrived.await(WAIT_S, TimeUnit.SECONDS))
        open("BindPhone")
        idleUntil("BindPhone") { top() == "BindPhone" && has(BindPhoneTags.PAGE) }
        settle()
        assertFalse("在途（从未未知）不显示待确认卡", has(BindPhonePendingTags.CARD))
        release.countDown()
        idleUntil("记录变为结果未知") { records().singleOrNull()?.stateRaw == PendingKeyStore.UNKNOWN }
        idleUntil("订阅共享通知后从存储刷新出待确认卡") { has(BindPhonePendingTags.CARD) }
        assertEquals("没有离开 BindPhone", "BindPhone", top())
    }

    // ---------------------------------------------------------------- R1-05

    /** R1-05：待确认【确认】在途时被别的页面盖住 → 迟到的成功只做共享结算，不按路由名关 BindPhone（也不连带盖在上面的页面）。 */
    @Test
    fun r1_05_aLateConfirmSuccessWhileCoveredDoesNotCloseBindPhoneByRouteName() {
        bindPhoneTimesOut()
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        enqueue("POST", BIND) {
            arrived.countDown()
            release.await(WAIT_S, TimeUnit.SECONDS)
            serverState.phoneBound = true
            envelope("{}")
        }
        tap(BindPhonePendingTags.CONFIRM)
        assertTrue(arrived.await(WAIT_S, TimeUnit.SECONDS))
        open("Help")
        settle()
        val stackBefore = stack()
        assertEquals("Help", stackBefore.last())
        release.countDown()
        idleUntil("共享结算照常：已绑手机") { routing.session.value == SessionState.PHONE_BOUND }
        settle()
        assertEquals("不按路由名出栈 BindPhone", stackBefore, stack())
        assertTrue("键已结束", records().isEmpty())
    }

    // ---------------------------------------------------------------- R1-06

    /** R1-06：确认得 42901 → 共享 phoneGate 未到期时待确认两个按钮置灰（页面侧也禁用），到期自动恢复。 */
    @Test
    fun r1_06_pendingButtonsFollowTheSharedPhoneGateAndRecoverWhenItExpires() {
        bindPhoneTimesOut()
        enqueueCode("POST", BIND, 42901, headers = mapOf("Retry-After" to "2"))
        tap(BindPhonePendingTags.CONFIRM)
        idleUntil("共享 phoneGate 已结算") { account.shared.phoneGate.active }
        settle()
        assertFalse("【确认上一次提交】置灰", enabled(BindPhonePendingTags.CONFIRM))
        assertFalse("【放弃上一次提交】置灰", enabled(BindPhonePendingTags.ABANDON))
        advance(3_000L)
        idleUntil("到期恢复") { enabled(BindPhonePendingTags.CONFIRM) && enabled(BindPhonePendingTags.ABANDON) }
    }

    // ---------------------------------------------------------------- R1-07

    /**
     * R1-07：受支持版本（min_supported_version 不高于本机）而会话仍是 deletion_only 时申请注销得 10405 → 网络层只刷新（REFRESH_ONLY，刷新为 full）；
     * 页面不按码重判强更（不调强更出口）。
     */
    @Test
    fun r1_07_aRefreshOnly10405OnApplyDoesNotCallTheForceUpdateExit() {
        serverState.requireStepUp = false
        // 强更下的受限会话（刷新后仍是 deletion_only）
        refreshScope = "deletion_only"
        launch(scope = "deletion_only", forced = true)
        idleUntil { top() == FORCE_UPDATE && has(com.couli.app.update.FORCE_UPDATE_DELETE_TAG) }
        click(com.couli.app.update.FORCE_UPDATE_DELETE_TAG)
        idleUntil("注销页") { top() == DELETE_ACCOUNT && has(AccountTags.DELETE_SUBMIT) }
        tap(AccountTags.DELETE_WAIVER)
        // 服务端回 10405 但本机版本不低于 min_supported_version：网络层对遗留的 deletion_only 只刷新（REFRESH_ONLY）
        enqueueCode("POST", DELETION, 10405, """{"min_supported_version":"0.0.1"}""")
        val forced = accountForcedCalls
        tap(AccountTags.DELETE_SUBMIT)
        idleUntil("申请已发出") { all("POST", DELETION).isNotEmpty() }
        idleUntil("结果已落地") { hasText(t("pending_confirm.retry_later")) || accountForcedCalls != forced }
        settle()
        assertEquals("页面不按 10405 码重判强更", forced, accountForcedCalls)
        assertTrue("提示稍后再试（G62）", hasText(t("pending_confirm.retry_later")))
    }

    // ---------------------------------------------------------------- R1-08

    /** R1-08：申请得 30416 → 入账回正后回到前台（明确刷新）读到 negative_fen=0 → 不再强制负余额态，可以重新申请。 */
    @Test
    fun r1_08_after30416AnExplicitRefreshWithAPositiveBalanceReEnablesApply() {
        serverState.requireStepUp = false
        launch()
        openDeleteFromSettings()
        enqueueCode("POST", DELETION, 30416, """{"amount_fen":200}""")
        serverState.negativeFen = 200
        tap(AccountTags.DELETE_WAIVER)
        tap(AccountTags.DELETE_SUBMIT)
        idleUntil("本次 30416") { has(AccountTags.DELETE_NEGATIVE_NOTICE) }
        serverState.negativeFen = 0
        coverAndReturn()
        idleUntil("回正后不再是负余额态") { !has(AccountTags.DELETE_NEGATIVE_NOTICE) && has(AccountTags.DELETE_SUBMIT) }
        click(AccountTags.DELETE_WAIVER)
        assertTrue("可以重新申请", enabled(AccountTags.DELETE_SUBMIT))
    }

    // ---------------------------------------------------------------- R1-09

    /** R1-09：第 2 步发码在途时返回第 1 步 → 迟到回包后重新验证进第 2 步，【获取验证码】可用（sending2 已随返回重置）。 */
    @Test
    fun r1_09_backToStep1WhileTheStep2SendIsInFlightLeavesTheSendButtonUsableAfterReverifying() {
        launch()
        openPhoneChange()
        passStep1()
        type(AccountTags.PHONE_NEW, PHONE_NEW)
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        enqueue("POST", SMS) {
            arrived.countDown()
            release.await(WAIT_S, TimeUnit.SECONDS)
            envelope("null", 50001)
        }
        click(AccountTags.PHONE_STEP2_SEND)
        assertTrue(arrived.await(WAIT_S, TimeUnit.SECONDS))
        androidx.test.espresso.Espresso
            .pressBack()
        settle()
        idleUntil("回第 1 步") { has(AccountTags.PHONE_STEP1) }
        release.countDown()
        settle()
        settle()
        passStep1()
        type(AccountTags.PHONE_NEW, PHONE_NEW)
        assertTrue("第 2 步【获取验证码】可用", enabled(AccountTags.PHONE_STEP2_SEND))
    }

    // ---------------------------------------------------------------- R1-10

    /** R1-10：第 2 步提交后被盖住、期间收到 44001 → 回到页面时展示一次完整风控弹窗（含【联系客服申诉】），关掉后再回来不重复。 */
    @Test
    fun r1_10_a44001ReceivedWhileHiddenIsShownOnceWhenThePageReturns() {
        launch()
        openPhoneChange()
        passStep1()
        fillStep2()
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        enqueue("POST", BIND) {
            arrived.countDown()
            release.await(WAIT_S, TimeUnit.SECONDS)
            envelope("{}", 44001)
        }
        tap(AccountTags.PHONE_SUBMIT)
        assertTrue(arrived.await(WAIT_S, TimeUnit.SECONDS))
        coverAndReturn {
            release.countDown()
            settle()
            settle()
            assertFalse("盖住时不弹", has(AccountTags.PHONE_RISK_APPEAL))
        }
        idleUntil("回到页面展示风控弹窗") { has(AccountTags.PHONE_RISK_APPEAL) }
        tap(AccountTags.PHONE_RISK_OK)
        assertFalse(has(AccountTags.PHONE_RISK_APPEAL))
        coverAndReturn()
        settle()
        assertFalse("只展示一次", has(AccountTags.PHONE_RISK_APPEAL))
    }

    // ---------------------------------------------------------------- R1-11

    /** R1-11：撤回结果未知 → 核对读取 5xxxx：按读取失败显示（撤回不可用、可重试）；【重试】查明已撤回 → 撤回成功结算（回设置页）。 */
    @Test
    fun r1_11_aFailedReconcileReadAfterAnUnknownCancelMarksLoadFailedAndRetryFinishesTheCancel() {
        serverState.deletion = deletionJson("cooling", canCancel = true)
        launch()
        openDeleteFromSettings()
        idleUntil("冷静期") { has(AccountTags.DELETE_CANCEL) && enabled(AccountTags.DELETE_CANCEL) }
        enqueueTimeout("POST", CANCEL)
        enqueueCode("GET", DELETION, 50001, trace = "trace-abcdef")
        val reads = all("GET", DELETION).size
        tap(AccountTags.DELETE_CANCEL)
        idleUntil("核对读取已发出") { all("GET", DELETION).size > reads }
        idleUntil("核对失败按读取失败显示") { has(AccountTags.DELETE_LOAD_ERROR) }
        assertTrue("带 trace6", hasText("abcdef"))
        assertFalse("读取失败时撤回不可用", has(AccountTags.DELETE_CANCEL) && enabled(AccountTags.DELETE_CANCEL))
        serverState.deletion = deletionJson("cancelled", canCancel = false, reason = "user")
        composeRule
            .onNode(
                androidx.compose.ui.test
                    .hasText(t("deletion.retry")),
                useUnmergedTree = true,
            ).performClick()
        settle()
        idleUntil("重试查明已撤回 → 撤回成功结算（回设置页）") { top() == SETTINGS }
    }

    // ---------------------------------------------------------------- R1-12

    /** R1-12：强更页「注销账号 / 撤销注销」标签的 getDeletion 与注销页共用 deletionRead：42901 结算截止时间，期间不再发。 */
    @Test
    fun r1_12_theForceUpdateLabelReadSharesTheDeletionReadGate() {
        launch(forced = true)
        idleUntil("强更页") { top() == FORCE_UPDATE }
        settle()
        enqueueCode("GET", DELETION, 42901, headers = mapOf("Retry-After" to "30"))
        val before = all("GET", DELETION).size
        composeRule.runOnUiThread { account.refreshForceUpdateLabel() }
        idleUntil("标签读取已发出") { all("GET", DELETION).size > before }
        idleUntil("42901 写进共享的 deletionRead") { account.deps.deletionRead.active }
        val limited = all("GET", DELETION).size
        composeRule.runOnUiThread { account.refreshForceUpdateLabel() }
        settle()
        assertEquals("限流期间不发", limited, all("GET", DELETION).size)
        assertNotNull("限流期间用兜底标签", account.forceUpdateLabel.value)
    }

    // ---------------------------------------------------------------- R1-13

    /** R1-13：bind 发码成功后改填同一号码的另一种合法写法（+86、空格）→ 仍在同一把键的倒计时里，【获取验证码】不可用。 */
    @Test
    fun r1_13_theBindSendCountdownIsKeyedByTheNormalizedNumber() {
        launch()
        openPhoneChange()
        passStep1()
        type(AccountTags.PHONE_NEW, PHONE_NEW)
        click(AccountTags.PHONE_STEP2_SEND)
        idleUntil("bind 发码") { all("POST", SMS).count { body(it)["purpose"].toString().trim('"') == "bind" } == 1 }
        settle()
        assertFalse(enabled(AccountTags.PHONE_STEP2_SEND))
        type(AccountTags.PHONE_NEW, "+86 139 0000 1234")
        settle()
        assertFalse("同一号码的另一种写法仍在倒计时", enabled(AccountTags.PHONE_STEP2_SEND))
    }

    // ---------------------------------------------------------------- R1-14

    /** R1-14：二次验证面板验证得 42901 → 关面板、回第 1 步：【下一步】同样置灰、点了也不发（同一份验证截止时间）。 */
    @Test
    fun r1_14_step1VerifyAndTheStepUpSheetShareOneVerifyGate() {
        launch()
        openPhoneChange()
        passStep1()
        fillStep2()
        enqueueCode("POST", BIND, 10003)
        tap(AccountTags.PHONE_SUBMIT)
        idleUntil("二次验证面板") { has(STEP_UP_SHEET) }
        enqueueCode("POST", STEP_UP, 42901, headers = mapOf("Retry-After" to "30"))
        type(STEP_UP_CODE, CODE_OK)
        tap(STEP_UP_SUBMIT)
        idleUntil("面板验证 42901") { account.stepUp.verifyGate.active }
        tap(STEP_UP_CLOSE)
        androidx.test.espresso.Espresso
            .pressBack()
        settle()
        idleUntil("回第 1 步") { has(AccountTags.PHONE_STEP1) }
        type(AccountTags.PHONE_STEP1_CODE, CODE_OK)
        val verifies = all("POST", STEP_UP).size
        assertFalse("第 1 步【下一步】同样置灰", enabled(AccountTags.PHONE_STEP1_NEXT))
        tap(AccountTags.PHONE_STEP1_NEXT)
        assertEquals("截止时间内不发验证", verifies, all("POST", STEP_UP).size)
    }

    // ---------------------------------------------------------------- R1-15

    /** R1-15：第 2 步验证码 20002 → 输入框带具体错误说明（error 语义），焦点移到错误提示，播报一次。 */
    @Test
    fun r1_15_aFieldErrorIsAttachedToTheInputFocusedAndAnnouncedOnce() {
        launch()
        openPhoneChange()
        passStep1()
        fillStep2()
        enqueueCode("POST", BIND, 20002)
        tap(AccountTags.PHONE_SUBMIT)
        idleUntil("字段错误") { has(AccountTags.PHONE_FIELD_ERROR) }
        settle()
        val message = textOf(AccountTags.PHONE_FIELD_ERROR)
        assertTrue(message.isNotEmpty())
        assertEquals("错误说明关联到输入框", message, node(AccountTags.PHONE_STEP2_CODE).config.getOrNull(SemanticsProperties.Error))
        composeRule.mainClock.advanceTimeBy(500L)
        settle()
        idleUntil("焦点移到错误提示") { node(AccountTags.PHONE_FIELD_ERROR).config.getOrNull(SemanticsProperties.Focused) == true }
        idleUntil("播报一次") { synchronized(announced) { announced.count { it == message } } == 1 }
        settle()
        assertEquals("不重复播报", 1, synchronized(announced) { announced.count { it == message } })
    }
}
