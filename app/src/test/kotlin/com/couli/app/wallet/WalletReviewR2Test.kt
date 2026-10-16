package com.couli.app.wallet

import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.ErrorEffect
import com.couli.core.network.ErrorEffectKind
import com.couli.core.network.PendingKeyRecord
import com.couli.core.network.PendingTarget
import com.couli.core.network.ReplayMode
import com.couli.core.wallet.StepUpTokenCache
import com.couli.core.wallet.WalletGates
import com.couli.feature.wallet.EstimateUi
import com.couli.feature.wallet.LaborAgreementController
import com.couli.feature.wallet.LaborBackend
import com.couli.feature.wallet.PayoutAccountController
import com.couli.feature.wallet.RealNameBackend
import com.couli.feature.wallet.RealNameController
import com.couli.feature.wallet.SmsDeadlines
import com.couli.feature.wallet.StepUpController
import com.couli.feature.wallet.WalletApi
import com.couli.feature.wallet.WalletController
import com.couli.feature.wallet.WalletDeps
import com.couli.feature.wallet.WalletEnv
import com.couli.feature.wallet.WithdrawController
import com.couli.feature.wallet.WithdrawRecordsController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APP-09 Android 第 2 轮评审（android-review-r2.md R2-01..R2-10；共性第 39–47 条）的执行器级复现：真实的 SensitiveFlow、StepUpController、
 * WithdrawController、WithdrawRecordsController、PayoutAccountController、WalletController 与实名 / 劳务执行器，网络换成可控的假 [WalletApi]
 * （每个请求一个 [CompletableDeferred]，测试决定先后与内容），协程在 Unconfined 上同步推进。网络层的点击账号（ApiCall.expectedAccount）另见
 * core/network 的 ApiClientPendingTargetTest r2_01；系统返回关闭资金半屏（R2-11）见 AppWalletIntegrationTest。
 */
@Suppress("LargeClass", "TooManyFunctions")
class WalletReviewR2Test {
    /** 会话级作用域（App 的 scope）。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    /** 页面作用域（ViewModel 的 viewModelScope）：出栈时取消。 */
    private val pageScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var now = 1_000_000L

    @After
    fun tearDown() {
        pageScope.cancel()
        scope.cancel()
    }

    // ---------------------------------------------------------------- 假环境

    private class Env : WalletEnv {
        var epoch = 1L
        var user: String? = "user-a"
        val opened = mutableListOf<String>()
        var saved = 0
        var dirty = 0

        override fun loggedIn() = user != null

        override fun sessionEpoch() = epoch

        override fun account() = user

        override fun configData(): JsonObject? = null

        override fun appVersion() = "1.0.0"

        override fun earningsSince(): String? = null

        override fun open(
            route: String,
            params: JsonObject,
        ) {
            opened += route
        }

        override fun replaceTop(
            route: String,
            params: JsonObject,
            entryId: String?,
        ) {
            opened += route
        }

        override fun copy(text: String) = true

        override fun forceUpdate() = Unit

        override fun stepUpPhone(account: String) = "13800005678"

        /** 会话作用域（FU-AND-0717 F-AND-37：token 按作用域隔离）。 */
        var scope = "full"

        override fun sessionScope() = scope

        val dirtyFor = mutableListOf<Pair<String, Long>>()

        override fun markWalletDirty(
            account: String,
            epoch: Long,
        ) {
            dirty += 1
            dirtyFor += account to epoch
        }

        override fun payoutSaved(entryId: String?): Boolean {
            saved += 1
            return false
        }
    }

    private class Call(
        val call: ApiCall,
        val answer: CompletableDeferred<ApiResult> = CompletableDeferred(),
    )

    private class FakeApi : WalletApi {
        val calls = mutableListOf<Call>()
        var pending: PendingKeyRecord? = null

        /** 自动回答（不登记为待回答）；返回 null 的由测试手动回答。 */
        var auto: (ApiCall) -> ApiResult? = { null }

        fun of(operation: String) = calls.filter { it.call.operationId == operation }

        override fun pendingFor(operationId: String): PendingKeyRecord? = pending?.takeIf { it.operationId == operationId }

        override fun restorePending() = true

        override suspend fun confirmPending(
            operationId: String,
            stepUpToken: String?,
            target: PendingTarget,
        ): ApiResult = CompletableDeferred<ApiResult>().also { confirms += it }.await()

        val confirms = mutableListOf<CompletableDeferred<ApiResult>>()

        override suspend fun abandonPending(
            operationId: String,
            target: PendingTarget,
        ): ApiResult = CompletableDeferred<ApiResult>().await()

        override suspend fun send(call: ApiCall): ApiResult {
            auto(call)?.let { return it }
            val entry = Call(call)
            calls += entry
            return entry.answer.await()
        }

        override fun cancelOwned(owner: CallOwnership) = Unit

        override fun epochs() = CallEpochs(0, 0)
    }

    private val env = Env()
    private val api = FakeApi()
    private val gates = WalletGates(scope) { now }

    private fun deps(
        realname: RealNameBackend? = null,
        labor: LaborBackend? = null,
    ) = WalletDeps(
        api = api,
        env = env,
        gates = gates,
        tokens = StepUpTokenCache(),
        cache = null,
        mask = null,
        realname = realname,
        labor = labor,
        clock = { now },
        service = scope,
    )

    private val deps = deps()

    private fun ok(data: String) = ApiResult.Success(Json.parseToJsonElement(data), "trace-000123", null)

    private fun fail(
        code: Int,
        data: String = "{}",
        disableMillis: Long? = null,
    ) = ApiResult.Failure(
        code,
        "",
        Json.parseToJsonElement(data).jsonObject,
        "trace-000123",
        ErrorEffect(code, ErrorEffectKind.TOAST, ReplayMode.NONE, false, disableMillis = disableMillis),
        null,
    )

    private fun withdraw(amount: String = ""): WithdrawController {
        api.auto = { call ->
            when (call.operationId) {
                WithdrawController.RULES_OPERATION -> ok(RULES_OK)
                WithdrawController.PAYOUT_OPERATION -> ok(PAYOUT_ALIPAY)
                "sendSmsCode" -> ok("""{"resend_after_sec":60}""")
                "stepUp" -> ok("""{"step_up_token":"fixture-step-up-token","expire_at":"2099-01-01T00:00:00+08:00"}""")
                else -> null
            }
        }
        return WithdrawController(deps, pageScope, initialAmount = amount).also { it.start() }
    }

    private val slot = SmsDeadlines.Slot("user-a", "13800005678", "withdraw")

    // ---------------------------------------------------------------- R2-01（S0）

    @Test
    fun r2_01_submitAndStepUpReplayCarryClickAccountAndEpoch() {
        val controller = withdraw()
        val flow = controller.flow
        val body = """{"amount_fen":1000}"""
        flow.submit(body, body)
        val first = api.of(WithdrawController.OPERATION).single()
        assertEquals("点击时的账号交给网络层核对", "user-a", first.call.expectedAccount)
        assertTrue(first.call.ownership!!.stillOwned())
        first.answer.complete(fail(10003))
        flow.stepUp.onCodeChange("123456")
        flow.stepUp.verify()
        val replay = api.of(WithdrawController.OPERATION)[1]
        assertEquals("原键重放", first.call.idempotencyKey, replay.call.idempotencyKey)
        assertEquals("重放沿用点击时的账号", "user-a", replay.call.expectedAccount)
        // A 点击后、请求排队期间会话换成 B：两次请求的归属都失效（网络层不再写出、不登记到 B）
        env.epoch += 1
        env.user = "user-b"
        assertFalse(first.call.ownership!!.stillOwned())
        assertFalse(replay.call.ownership!!.stillOwned())
    }

    @Test
    fun r2_01_replayAfterSessionChangedToBIsNotSent() {
        val controller = withdraw()
        val flow = controller.flow
        val body = """{"amount_fen":1000}"""
        flow.submit(body, body)
        api
            .of(WithdrawController.OPERATION)
            .single()
            .answer
            .complete(fail(10003))
        // 二次验证期间换成 B（App 的会话通知还没到达本页）：拿到 token 也不发 A 的重放
        api.auto = { call -> if (call.operationId == "sendSmsCode") ok("""{"resend_after_sec":60}""") else null }
        flow.stepUp.onCodeChange("123456")
        flow.stepUp.verify()
        env.epoch += 1
        env.user = "user-b"
        api
            .of("stepUp")
            .single()
            .answer
            .complete(ok("""{"step_up_token":"fixture-step-up-token","expire_at":"2099-01-01T00:00:00+08:00"}"""))
        assertEquals("没有发出重放", 1, api.of(WithdrawController.OPERATION).size)
    }

    // ---------------------------------------------------------------- FU-AND-0717 F-AND-37

    /** 新建一张提现表单并提交一次，返回这次提交请求带的 step-up token。 */
    private fun submitOnNewForm(): String? {
        val before = api.of(WithdrawController.OPERATION).size
        val body = """{"amount_fen":1000}"""
        withdraw().flow.submit(body, body)
        return api
            .of(WithdrawController.OPERATION)
            .drop(before)
            .single()
            .call.stepUpToken
    }

    /**
     * F-AND-37：钱包 SensitiveFlow 的 step-up token 走与 APP-13a 共用的 StepUpTokenCache 按「账号 + 登录世代 + 作用域 + action」核对：验证通过写入时带世代与
     * 作用域；同世代同作用域的下一张表单照常复用（对照）；同账号换了登录世代（会话通知还没到、缓存没清）或作用域变了，旧 token 不复用。
     */
    @Test
    fun fuand0717_and37_aWalletStepUpTokenIsNotReusedAfterTheEpochOrScopeChanges() {
        val controller = withdraw()
        val body = """{"amount_fen":1000}"""
        controller.flow.submit(body, body)
        api
            .of(WithdrawController.OPERATION)
            .single()
            .answer
            .complete(fail(10003))
        controller.flow.stepUp.onCodeChange("123456")
        controller.flow.stepUp.verify()
        val token = "fixture-step-up-token"
        assertEquals("重放带验证得到的 token", token, api.of(WithdrawController.OPERATION)[1].call.stepUpToken)
        assertEquals("按世代与作用域写入", token, deps.tokens.take("withdraw", "user-a", now, 1L, "full"))
        // 对照：同账号、同世代、同作用域的下一张表单复用
        assertEquals(token, submitOnNewForm())
        // 作用域变了（同世代 full → deletion_only，缓存没清）：不复用
        env.scope = "deletion_only"
        assertNull("换作用域后旧 token 不复用", submitOnNewForm())
        env.scope = "full"
        // 同账号重新登录（世代 1 → 2）而会话通知还没执行（缓存没清）：不复用
        env.epoch = 2L
        assertNull("换世代后旧 token 不复用", submitOnNewForm())
    }

    // ---------------------------------------------------------------- R2-02

    @Test
    fun r2_02_smsSettlementSurvivesPageScopeCancellation() {
        val panel = StepUpController(deps, pageScope) { true }
        panel.open(
            "withdraw",
            com.couli.feature.wallet.FormOwner
                .of(env),
            onToken = {},
            onClosed = {},
        )
        // 用户关面板并退出提现页：ViewModel 被清除，页面作用域取消
        panel.dismiss()
        pageScope.cancel()
        api.of("sendSmsCode")[0].answer.complete(ok("""{"resend_after_sec":60}"""))
        assertTrue("共享倒计时照常建立", deps.sms.remaining(slot, now) > 0)
        assertFalse(deps.sms.busy(slot))
        // 重新进入：倒计时没到，不再发码
        val again = StepUpController(deps, scope) { true }
        again.open(
            "withdraw",
            com.couli.feature.wallet.FormOwner
                .of(env),
            onToken = {},
            onClosed = {},
        )
        assertEquals(1, api.of("sendSmsCode").size)
        again.dismiss()
    }

    @Test
    fun r2_02_late42901AfterPageScopeCancelledStillStartsSharedGate() {
        val panel = StepUpController(deps, pageScope) { true }
        panel.open(
            "withdraw",
            com.couli.feature.wallet.FormOwner
                .of(env),
            onToken = {},
            onClosed = {},
        )
        pageScope.cancel()
        api.of("sendSmsCode")[0].answer.complete(fail(42901, disableMillis = 9_000))
        assertTrue(gates.sms.active)
    }

    @Test
    fun r2_02_slotReleaseBoundToRequestIdentity() {
        val first = StepUpController(deps, scope) { true }
        first.open(
            "withdraw",
            com.couli.feature.wallet.FormOwner
                .of(env),
            onToken = {},
            onClosed = {},
        )
        // 会话换代清空槽位后，同一账号重新登录又发了一次
        deps.sms.clear()
        first.dismiss()
        val second = StepUpController(deps, scope) { true }
        second.open(
            "withdraw",
            com.couli.feature.wallet.FormOwner
                .of(env),
            onToken = {},
            onClosed = {},
        )
        assertEquals(2, api.of("sendSmsCode").size)
        // 旧请求结束：不得释放新请求的占用
        api.of("sendSmsCode")[0].answer.complete(fail(50001))
        assertTrue("新请求仍占用槽位", deps.sms.busy(slot))
        api.of("sendSmsCode")[1].answer.complete(ok("""{"resend_after_sec":60}"""))
        assertFalse(deps.sms.busy(slot))
        second.dismiss()
    }

    // ---------------------------------------------------------------- R2-03

    @Test
    fun r2_03_successAfterBackgroundThenForegroundNavigatesImmediately() {
        val controller = withdraw()
        val body = """{"amount_fen":1000}"""
        controller.flow.submit(body, body)
        // 提交后切后台，再在响应到达前回到提现页
        controller.onVisibility(false)
        controller.onVisibility(true)
        api.of(WithdrawController.OPERATION)[0].answer.complete(ok("""{"withdrawal_id":"W9"}"""))
        assertEquals("发起页已在栈顶可见：立即进提现记录", listOf("WithdrawRecords"), env.opened)
        assertEquals("钱包需要刷新", 1, env.dirty)
    }

    @Test
    fun r2_03_successWhileHiddenKeepsSubmitUnavailableUntilConsumed() {
        val controller = withdraw(amount = "10")
        val body = """{"amount_fen":1000}"""
        controller.flow.submit(body, body)
        controller.onVisibility(false)
        api.of(WithdrawController.OPERATION)[0].answer.complete(ok("""{"withdrawal_id":"W9"}"""))
        assertTrue(env.opened.isEmpty())
        assertTrue("消费前提交入口不可用", controller.state.value.successPending)
        controller.submit(controller.state.value.version)
        assertEquals(1, api.of(WithdrawController.OPERATION).size)
        controller.onVisibility(true)
        assertEquals(listOf("WithdrawRecords"), env.opened)
    }

    @Test
    fun lesson40_successAfterPageLeftStillMarksWalletDirty() {
        val controller = withdraw()
        val body = """{"amount_fen":1000}"""
        controller.flow.submit(body, body)
        controller.leave()
        pageScope.cancel()
        api.of(WithdrawController.OPERATION)[0].answer.complete(ok("""{"withdrawal_id":"W9"}"""))
        assertEquals("页面已出栈，共享结算照常", 1, env.dirty)
        assertTrue("不导航", env.opened.isEmpty())
    }

    @Test
    fun lesson40_submit42901AfterPageLeftStillStartsWithdrawGate() {
        val controller = withdraw()
        val body = """{"amount_fen":1000}"""
        controller.flow.submit(body, body)
        controller.leave()
        pageScope.cancel()
        api.of(WithdrawController.OPERATION)[0].answer.complete(fail(42901, disableMillis = 9_000))
        assertTrue(gates.withdraw.active)
    }

    // ---------------------------------------------------------------- R2-04 / R2-05

    @Test
    fun r2_04_refreshListNeverOverwritesDetailAcceptedThisRound() {
        val records = WithdrawRecordsController(deps, pageScope, focusId = null)
        records.load()
        api.of(WithdrawRecordsController.LIST_OPERATION)[0].answer.complete(ok(page("W1", "PENDING_REVIEW")))
        assertTrue(records.refresh())
        // 第一页未返回时展开一张此前未展开的记录：本轮详情先回来
        records.toggle(records.state.value.version, "W1")
        api.of(WithdrawRecordsController.DETAIL_OPERATION)[0].answer.complete(ok(record("W1", "PAID_API", "\"20260928200040011100\"")))
        // 较早取得的列表随后返回审核中
        api.of(WithdrawRecordsController.LIST_OPERATION)[1].answer.complete(ok(page("W1", "PENDING_REVIEW")))
        val state = records.state.value
        assertEquals(
            "PAID_API",
            state.items
                .single()
                .status,
        )
        assertEquals("20260928200040011100", state.details["W1"]?.channelOrderId)
        assertEquals("PAID_API", state.details["W1"]?.status)
    }

    @Test
    fun r2_05_focusDetailNotSentAfterList42901() {
        val records = WithdrawRecordsController(deps, pageScope, focusId = "W7")
        records.load()
        api.of(WithdrawRecordsController.LIST_OPERATION)[0].answer.complete(fail(42901, disableMillis = 60_000))
        assertTrue("列表的限流先结算", gates.read.active)
        assertTrue("详情没有发出", api.of(WithdrawRecordsController.DETAIL_OPERATION).isEmpty())
        assertEquals(
            "error.42901",
            records.state.value.error
                ?.key,
        )
    }

    @Test
    fun r2_05_focusDetail42901SettledBeforePageChecks() {
        val records = WithdrawRecordsController(deps, pageScope, focusId = "W7")
        records.load()
        api.of(WithdrawRecordsController.LIST_OPERATION)[0].answer.complete(ok(page("W1", "PENDING_REVIEW")))
        records.leave()
        api.of(WithdrawRecordsController.DETAIL_OPERATION)[0].answer.complete(fail(42901, disableMillis = 60_000))
        assertTrue("页面已出栈，详情的限流仍按会话写入", gates.read.active)
    }

    @Test
    fun lesson46_recordsRefreshBlockedLocallyShowsErrorOnOldList() {
        val records = WithdrawRecordsController(deps, pageScope, focusId = null)
        records.load()
        api.of(WithdrawRecordsController.LIST_OPERATION)[0].answer.complete(ok(page("W1", "PENDING_REVIEW")))
        gates.read.start(60_000)
        records.load(refresh = true)
        assertEquals(
            "error.42901",
            records.state.value.error
                ?.key,
        )
        assertEquals(1, api.of(WithdrawRecordsController.LIST_OPERATION).size)
    }

    // ---------------------------------------------------------------- R2-06 / R2-10

    @Test
    fun r2_06_saveSuccessInvalidatesEarlierAccountRead() {
        val controller = PayoutAccountController(deps, pageScope)
        controller.load()
        api.of(PayoutAccountController.GET_OPERATION)[0].answer.complete(ok(PAYOUT_ALIPAY))
        // 回前台触发一次读取，服务端取得旧账号 X 后延迟返回
        controller.onResume()
        val stale = api.of(PayoutAccountController.GET_OPERATION)[1]
        val body = """{"payout_method":"bank_card"}"""
        controller.flow.submit(body, """{"method":"bank_card","account":null,"payee":null}""")
        api.of(PayoutAccountController.OPERATION)[0].answer.complete(ok(PAYOUT_BANK))
        assertEquals(
            "bank_card",
            controller.state.value.account
                ?.methodRaw,
        )
        stale.answer.complete(ok(PAYOUT_ALIPAY))
        assertEquals(
            "保存之前的读取不得把新账号覆盖回去",
            "bank_card",
            controller.state.value.account
                ?.methodRaw,
        )
    }

    @Test
    fun lesson41_payoutSavedWhileHiddenDefersPopUntilVisible() {
        val controller = PayoutAccountController(deps, pageScope)
        controller.load()
        api.of(PayoutAccountController.GET_OPERATION)[0].answer.complete(ok(PAYOUT_ALIPAY))
        controller.flow.submit("""{"payout_method":"bank_card"}""", """{"method":"bank_card","account":null,"payee":null}""")
        controller.onVisibility(false)
        api.of(PayoutAccountController.OPERATION)[0].answer.complete(ok(PAYOUT_BANK))
        assertEquals("不可见时不出栈", 0, env.saved)
        controller.onVisibility(true)
        assertEquals(1, env.saved)
    }

    @Test
    fun r2_10_realnameGuideFromAccountReadWaitsWhileHidden() {
        val controller = PayoutAccountController(deps, pageScope)
        controller.load()
        controller.onVisibility(false)
        api.of(PayoutAccountController.GET_OPERATION)[0].answer.complete(fail(30304))
        assertTrue("不可见时不打开实名页", env.opened.isEmpty())
        assertNotNull(controller.state.value.loadError)
        controller.onVisibility(true)
        assertTrue("导航许可已作废：回来不自动打开", env.opened.isEmpty())
        // 回到本页的重读（页面可见）再按结果引导
        controller.onResume()
        api.of(PayoutAccountController.GET_OPERATION)[1].answer.complete(fail(30304))
        assertEquals(listOf("RealName"), env.opened)
    }

    @Test
    fun lesson46_payoutReadBlockedLocallyMarksOldAccountNotCurrent() {
        val controller = PayoutAccountController(deps, pageScope)
        controller.load()
        api.of(PayoutAccountController.GET_OPERATION)[0].answer.complete(ok(PAYOUT_ALIPAY))
        val shown = controller.state.value.account!!
        gates.read.start(60_000)
        controller.onResume()
        assertEquals(
            "error.42901",
            controller.state.value.loadError
                ?.key,
        )
        controller.change(controller.state.value.version, shown)
        assertFalse("旧账号不再算当前，不能发起更换", controller.state.value.editing)
    }

    // ---------------------------------------------------------------- R2-07 / R2-08

    @Test
    fun r2_07_payoutRereadBlockedLocallyClearsOldAccountRow() {
        val controller = withdraw()
        assertNotNull(controller.state.value.payout)
        gates.read.start(60_000)
        controller.onResume()
        assertNull("旧账号行清掉", controller.state.value.payout)
        assertEquals(
            "error.42901",
            controller.state.value.payoutError
                ?.key,
        )
    }

    @Test
    fun r2_08_estimateBlockedByGateDuringDebounceDoesNotStayCalculating() {
        val controller = withdraw()
        controller.onAmountChange("10")
        assertEquals(EstimateUi.Calculating, controller.state.value.estimate)
        // 防抖等待期间别的读取收到 42901
        gates.read.start(60_000)
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (controller.state.value.estimate == EstimateUi.Calculating && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
        assertEquals(EstimateUi.Hidden, controller.state.value.estimate)
        assertEquals(
            "error.42901",
            controller.state.value.readError
                ?.key,
        )
    }

    @Test
    fun r2_08_estimateCancelledBySendGateIsSettled() {
        val controller = withdraw()
        api.auto = { call ->
            when {
                call.operationId == WithdrawController.RULES_OPERATION && call.query == null -> ok(RULES_OK)
                call.operationId == WithdrawController.PAYOUT_OPERATION -> ok(PAYOUT_ALIPAY)
                else -> null
            }
        }
        controller.onAmountChange("10")
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (api.of(WithdrawController.RULES_OPERATION).isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
        val estimate = api.of(WithdrawController.RULES_OPERATION).single()
        // 实际发送前另一读取收到 42901：网络层发送前闸门拦下（本地拒绝）
        gates.read.start(60_000)
        // 网络层写出前核对发送前闸门（被它拦下，本地拒绝 call_cancelled）
        assertEquals(
            false,
            estimate.call.ownership
                ?.sendAllowed
                ?.invoke(),
        )
        estimate.answer.complete(ApiResult.LocalReject("call_cancelled"))
        assertEquals(EstimateUi.Hidden, controller.state.value.estimate)
        assertEquals(
            "error.42901",
            controller.state.value.readError
                ?.key,
        )
    }

    // ---------------------------------------------------------------- R2-09

    @Test
    fun r2_09_walletReturnBlockedByGateTurnsKeptSummaryStale() {
        val wallet = WalletController(deps, pageScope)
        wallet.load()
        val loadedAt = now
        api.of(WalletController.OPERATION)[0].answer.complete(ok(SUMMARY))
        now += 60_000
        gates.read.start(60_000)
        assertFalse(wallet.refresh())
        val state = wallet.state.value
        assertNotNull(state.summary)
        assertEquals("用最近成功时间标更新时间", loadedAt, state.staleAt)
        assertFalse("中性文案", state.staleNetwork)
        assertEquals("error.42901", state.error?.key)
        wallet.withdraw(state.version)
        assertTrue("【提现】不受理", env.opened.isEmpty())
    }

    // ---------------------------------------------------------------- 同类：实名、劳务（共性第 39、41 条）

    private class FakeRealName : RealNameBackend {
        val answers = mutableListOf<CompletableDeferred<ApiResult>>()

        override suspend fun submit(
            name: String,
            idNo: String,
            idempotencyKey: String,
        ): ApiResult = CompletableDeferred<ApiResult>().also { answers += it }.await()
    }

    @Test
    fun lesson41_realnameDoneWhileHiddenPopsOnlyWhenVisible() {
        val backend = FakeRealName()
        var backs = 0
        val controller = RealNameController(deps(realname = backend), pageScope) { backs += 1 }
        controller.onNameChange("测试")
        controller.onIdChange("11010119900307001X")
        controller.submit()
        controller.toggleConsent()
        controller.agreeAndSubmit()
        controller.onVisibility(false)
        backend.answers.single().complete(ok("{}"))
        assertEquals("不可见时不出栈", 0, backs)
        controller.onVisibility(true)
        assertEquals(1, backs)
    }

    private class FakeLabor : LaborBackend {
        val signs = mutableListOf<CompletableDeferred<ApiResult>>()

        override suspend fun current(): ApiResult =
            ApiResult.Success(
                Json.parseToJsonElement("""{"version":"v1","text":"协议正文","text_sha256":"abc","need_sign":true}"""),
                "trace-000123",
                null,
            )

        override suspend fun sign(
            version: String,
            textSha256: String,
        ): ApiResult = CompletableDeferred<ApiResult>().also { signs += it }.await()
    }

    @Test
    fun lesson41_laborSignedWhileHiddenPopsOnlyWhenVisible() {
        val backend = FakeLabor()
        var backs = 0
        val controller = LaborAgreementController(deps(labor = backend), pageScope) { backs += 1 }
        controller.load()
        controller.toggle()
        controller.sign()
        controller.onVisibility(false)
        backend.signs.single().complete(ok("{}"))
        assertEquals(0, backs)
        controller.onVisibility(true)
        assertEquals(1, backs)
    }

    // ---------------------------------------------------------------- 共性第 49、51 条（鸿蒙补课评审）

    private fun pendingRecord(key: String) =
        PendingKeyRecord(
            key,
            WithdrawController.OPERATION,
            """{"amount_fen":1000}""",
            "user-a",
            """{"amount_fen":1000}""",
            com.couli.core.network.PendingKeyStore.UNKNOWN,
            everUnknown = true,
        )

    @Test
    fun lesson49_confirmStepUpClosedWhenKeySettledElsewhere() {
        api.pending = pendingRecord("key-a1")
        val controller = withdraw()
        val flow = controller.flow
        api.auto = { call -> if (call.operationId == "sendSmsCode") ok("""{"resend_after_sec":60}""") else null }
        flow.confirm("key-a1")
        api.confirms.single().complete(fail(10003))
        assertNotNull("确认得到 10003 弹二次验证", flow.stepUp.state.value)
        // 别的页面（或已出栈页面的迟到响应）结算了这个键
        api.pending = null
        deps.pending.notify("user-a", WithdrawController.OPERATION)
        assertNull("所属的键已结束：同步收起旧验证面板", flow.stepUp.state.value)
    }

    @Test
    fun lesson49_tokenAfterKeyReplacedIsNotReplayed() {
        api.pending = pendingRecord("key-a1")
        val controller = withdraw()
        val flow = controller.flow
        api.auto = { call -> if (call.operationId == "sendSmsCode") ok("""{"resend_after_sec":60}""") else null }
        flow.confirm("key-a1")
        api.confirms.single().complete(fail(10003))
        flow.stepUp.onCodeChange("123456")
        flow.stepUp.verify()
        // 验证在途期间记录换成了另一个键（没有收到结算通知）
        api.pending = pendingRecord("key-a2")
        api
            .of("stepUp")
            .single()
            .answer
            .complete(ok("""{"step_up_token":"fixture-step-up-token","expire_at":"2099-01-01T00:00:00+08:00"}"""))
        assertEquals("不对新键续发确认", 1, api.confirms.size)
    }

    @Test
    fun lesson51_blockedGuideKeepsTargetWithManualEntry() {
        val controller = withdraw(amount = "10")
        val flow = controller.flow
        val body = """{"amount_fen":1000}"""
        flow.submit(body, body)
        controller.onVisibility(false)
        api.of(WithdrawController.OPERATION)[0].answer.complete(fail(10004, """{"consent_type":"labor_agreement"}"""))
        assertTrue(env.opened.isEmpty())
        assertEquals("保留引导目标", "LaborAgreement", flow.state.value.guideRoute)
        assertEquals("输入保留", "10", controller.state.value.amountText)
        flow.openGuide()
        assertTrue("不可见时手动入口也不导航", env.opened.isEmpty())
        controller.onVisibility(true)
        flow.openGuide()
        assertEquals(listOf("LaborAgreement"), env.opened)
        assertNull(flow.state.value.guideRoute)
        assertEquals("不自动重提", 1, api.of(WithdrawController.OPERATION).size)
    }

    @Test
    fun lesson51_manualGuideBoundToSession() {
        val controller = withdraw(amount = "10")
        val flow = controller.flow
        val body = """{"amount_fen":1000}"""
        flow.submit(body, body)
        controller.onVisibility(false)
        api.of(WithdrawController.OPERATION)[0].answer.complete(fail(10005))
        controller.onVisibility(true)
        env.epoch += 1
        flow.openGuide()
        assertTrue("会话已换：旧引导不打开", env.opened.isEmpty())
    }

    @Test
    fun lesson51_payoutReadGuideKeepsManualEntry() {
        val controller = PayoutAccountController(deps, pageScope)
        controller.load()
        controller.onVisibility(false)
        api.of(PayoutAccountController.GET_OPERATION)[0].answer.complete(fail(30304))
        assertEquals("RealName", controller.state.value.guideRoute)
        controller.onVisibility(true)
        controller.openGuide()
        assertEquals(listOf("RealName"), env.opened)
    }

    private companion object {
        const val WAIT_MS = 3_000L
        const val POLL_MS = 50L
        const val RULES_OK =
            """{"can_withdraw":true,"block_code":null,"block_reason":null,"max_withdrawable_fen":1200,"withdrawable_fen":1234,""" +
                """"daily_remaining":1,"monthly_remaining":10,"est_fee_fen":0,"est_tax_fen":0,"est_net_fen":1000,""" +
                """"quick_amounts_fen":[500,1000]}"""
        const val SUMMARY =
            """{"available_fen":1000,"withdrawable_fen":1000,"negative_fen":0,"frozen_fen":200,"estimated_total_fen":1019,""" +
                """"estimated_fen":269,"pending_credit_fen":750,"pending_credit_paused_fen":450,"next_credit_period":"2026-11",""" +
                """"credit_overdue":false,"withdrawn_fen":0,"risk_paused_reason":null,"withdraw_enabled":true}"""
        const val PAYOUT_ALIPAY =
            """{"payout_method":"alipay","masked_account":"138****5678","masked_payee_name":"**三","change_remaining_this_month":2}"""
        const val PAYOUT_BANK =
            """{"payout_method":"bank_card","masked_account":"尾号 0009","bank_name":"示例银行","masked_payee_name":"**三",""" +
                """"change_remaining_this_month":1}"""

        fun record(
            id: String,
            status: String,
            tradeNo: String,
        ) = """{"withdrawal_id":"$id","status":"$status","review_mode":"manual","amount_fen":1000,"fee_fen":0,"tax_fen":0,""" +
            """"net_fen":1000,"payout_channel":"alipay","payout_channel_name":"支付宝","masked_account":"138****5678",""" +
            """"reason_code":null,"fail_action":null,"created_at":"2026-10-12T10:00:00+08:00","channel_order_id":$tradeNo}"""

        fun page(
            id: String,
            status: String,
        ) = """{"items":[${record(id, status, "null")}],"next_cursor":null}"""
    }
}
