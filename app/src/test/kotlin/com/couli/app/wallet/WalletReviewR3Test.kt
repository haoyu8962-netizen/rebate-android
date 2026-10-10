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
import com.couli.core.wallet.PayoutForm
import com.couli.core.wallet.StepUpTokenCache
import com.couli.core.wallet.WalletGates
import com.couli.feature.wallet.EstimateUi
import com.couli.feature.wallet.FlowState
import com.couli.feature.wallet.FormOwner
import com.couli.feature.wallet.LaborAgreementController
import com.couli.feature.wallet.LaborBackend
import com.couli.feature.wallet.PayoutAccountController
import com.couli.feature.wallet.PayoutSnapshot
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
import com.couli.feature.wallet.WithdrawSnapshot
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
 * APP-09 Android 第 3 轮评审（android-review-r3.md R3-01..R3-07；共性第 52–57 条）的执行器级复现，沿用第 2 轮的假环境写法（下面原说明照旧）。
 * App 接线（钱包失效通知、记录页 OnReturn、实名前置查询的限流与可见世代）另见 AppWalletIntegrationTest 的 r3_* 用例。
 *
 * 第 2 轮原说明：APP-09 Android 第 2 轮评审（android-review-r2.md R2-01..R2-10；共性第 39–47 条）的执行器级复现：真实的 SensitiveFlow、StepUpController、
 * WithdrawController、WithdrawRecordsController、PayoutAccountController、WalletController 与实名 / 劳务执行器，网络换成可控的假 [WalletApi]
 * （每个请求一个 [CompletableDeferred]，测试决定先后与内容），协程在 Unconfined 上同步推进。网络层的点击账号（ApiCall.expectedAccount）另见
 * core/network 的 ApiClientPendingTargetTest r2_01；系统返回关闭资金半屏（R2-11）见 AppWalletIntegrationTest。
 */
@Suppress("LargeClass", "TooManyFunctions")
class WalletReviewR3Test {
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

        /** 下一次读取账号之后执行一次（模拟：A 的结果通过归属核对之后，IO 线程随即保存了 B 的令牌）。 */
        var afterAccountRead: (() -> Unit)? = null

        override fun account(): String? {
            val current = user
            afterAccountRead?.let {
                afterAccountRead = null
                it()
            }
            return current
        }

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
        ): ApiResult = CompletableDeferred<ApiResult>().also { abandons += it }.await()

        val abandons = mutableListOf<CompletableDeferred<ApiResult>>()

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
        newKey = { "key-" + (++keys) },
        service = scope,
    )

    /** 生成过的幂等键个数（表单身份要先于生成键核对）。 */
    private var keys = 0

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

    /** 网络层写出前核对发送前闸门：被它拦下（返回 false），本地拒绝 call_cancelled。 */
    private fun rejectBySendGate(entry: Call) {
        assertEquals(
            false,
            entry.call.ownership
                ?.sendAllowed
                ?.invoke(),
        )
        entry.answer.complete(ApiResult.LocalReject("call_cancelled"))
    }

    /** 切到 B：B 的令牌已保存（会话世代、账号都变了），但 App 主线程里的清空通知（onSessionChanged）还没执行。 */
    private fun switchToBWithoutNotice() {
        env.epoch += 1
        env.user = "user-b"
    }

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

    // ---------------------------------------------------------------- R3-01（S0）

    @Test
    fun r3_01_withdrawFormOfAClickedAfterBTokenSavedIsNotSent() {
        val controller = withdraw(amount = "10")
        val version = controller.state.value.version
        val owner = controller.flow.state.value.owner
        assertEquals("表单绑定 A 的账号与世代", "user-a", owner.account)
        switchToBWithoutNotice()
        controller.submit(version, owner)
        controller.flow.submit("""{"amount_fen":1000}""", """{"amount_fen":1000}""")
        assertTrue("A 的表单不发", api.of(WithdrawController.OPERATION).isEmpty())
        assertEquals("不生成键", 0, keys)
        assertEquals(com.couli.feature.wallet.FlowState.EDITING, controller.flow.state.value.phase)
        // 清空通知执行之后：B 的新表单正常提交，交给网络层的是 B
        controller.onSessionChanged()
        controller.onAmountChange("10")
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (controller.state.value.estimate == EstimateUi.Calculating && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
        controller.submit(controller.state.value.version, controller.flow.state.value.owner)
        assertEquals(
            "user-b",
            api
                .of(WithdrawController.OPERATION)
                .single()
                .call.expectedAccount,
        )
    }

    @Test
    fun r3_01_payoutFormOfASavedAfterBTokenSavedIsNotSent() {
        val controller = PayoutAccountController(deps, pageScope)
        controller.load()
        api.of(PayoutAccountController.GET_OPERATION)[0].answer.complete(ok("""{"payout_method":null}"""))
        controller.chooseMethod("alipay")
        controller.onLogonChange("fixture@example.com")
        controller.onPayeeChange("测试")
        val owner = controller.flow.state.value.owner
        switchToBWithoutNotice()
        controller.save(owner)
        controller.save()
        assertTrue("A 填的收款账号不发、不登记到 B", api.of(PayoutAccountController.OPERATION).isEmpty())
        assertEquals(0, keys)
    }

    @Test
    fun r3_01_confirmAndAbandonOfACardAfterBTokenSavedAreNotSent() {
        api.pending = pendingRecord("key-a1")
        val controller = withdraw()
        val flow = controller.flow
        assertEquals(
            "key-a1",
            flow.state.value.pending
                ?.key,
        )
        val owner = flow.state.value.owner
        switchToBWithoutNotice()
        flow.confirm("key-a1", owner)
        flow.askAbandon("key-a1", owner)
        assertFalse("不弹放弃确认", flow.state.value.abandonConfirm)
        flow.abandon("key-a1", owner)
        assertTrue("确认不发", api.confirms.isEmpty())
        assertTrue("放弃不发", api.abandons.isEmpty())
    }

    @Test
    fun r3_01_realnameFormOfASubmittedAfterBTokenSavedIsNotSent() {
        val backend = FakeRealName()
        val controller = RealNameController(deps(realname = backend), pageScope) {}
        controller.onNameChange("测试")
        controller.onIdChange("11010119900307001X")
        val owner = controller.state.value.owner
        controller.submit(owner)
        controller.toggleConsent()
        switchToBWithoutNotice()
        controller.agreeAndSubmit(owner)
        assertTrue("A 的姓名与身份证号不发", backend.answers.isEmpty())
        assertFalse(controller.state.value.submitting)
    }

    @Test
    fun r3_01_laborSignOfAAfterBTokenSavedIsNotSent() {
        val backend = FakeLabor()
        val controller = LaborAgreementController(deps(labor = backend), pageScope) {}
        controller.load()
        controller.toggle()
        val owner = controller.state.value.owner
        assertEquals("user-a", owner.account)
        switchToBWithoutNotice()
        controller.sign(owner)
        assertTrue("A 读到的协议不以 B 的身份签署", backend.signs.isEmpty())
        assertFalse(controller.state.value.signing)
    }

    // ---------------------------------------------------------------- R3-02

    @Test
    fun r3_02_dirtyNoticeVoidsEarlierSummaryReadAndRefetches() {
        val wallet = WalletController(deps, pageScope)
        wallet.load()
        api.of(WalletController.OPERATION)[0].answer.complete(ok(SUMMARY))
        // 回流刷新先发出（会取得扣款前的余额），随后提现成功
        assertTrue(wallet.refresh())
        val early = api.of(WalletController.OPERATION)[1]
        deps.walletDirty.notify("user-a", env.epoch)
        assertEquals("失效后重取", 3, api.of(WalletController.OPERATION).size)
        api.of(WalletController.OPERATION)[2].answer.complete(ok(SUMMARY.replace("\"available_fen\":1000", "\"available_fen\":0")))
        early.answer.complete(ok(SUMMARY))
        assertEquals(
            "成功之前发出的旧摘要不覆盖",
            0L,
            wallet.state.value.summary
                ?.availableFen,
        )
    }

    @Test
    fun r3_02_dirtyNoticeOfOtherSessionIgnored() {
        val wallet = WalletController(deps, pageScope)
        wallet.load()
        api.of(WalletController.OPERATION)[0].answer.complete(ok(SUMMARY))
        deps.walletDirty.notify("user-b", env.epoch)
        deps.walletDirty.notify("user-a", env.epoch + 1)
        assertEquals(1, api.of(WalletController.OPERATION).size)
    }

    @Test
    fun r3_02_dirtyWhileGatedVoidsInFlightOldSummaryAndMarksStale() {
        val wallet = WalletController(deps, pageScope)
        wallet.load()
        api.of(WalletController.OPERATION)[0].answer.complete(ok(SUMMARY))
        assertTrue(wallet.refresh())
        val early = api.of(WalletController.OPERATION)[1]
        gates.read.start(60_000)
        deps.walletDirty.notify("user-a", env.epoch)
        assertNotNull("被限流：旧数据状态", wallet.state.value.staleAt)
        early.answer.complete(ok(SUMMARY))
        assertNotNull("成功之前发出的旧摘要不得把它恢复成当前", wallet.state.value.staleAt)
        assertEquals(
            "error.42901",
            wallet.state.value.error
                ?.key,
        )
    }

    /** 共性第 58 条：确认 / 放弃得到原结果成功（ALREADY_DONE），发起页已离开也按原会话标记钱包需要刷新。 */
    @Test
    fun lesson58_confirmAndAbandonSuccessAfterPageLeftMarkWalletDirty() {
        api.pending = pendingRecord("key-a1")
        val first = withdraw()
        first.flow.confirm("key-a1")
        first.leave()
        pageScope.cancel()
        api.confirms.single().complete(ok("""{"withdrawal_id":"W9"}"""))
        assertEquals("确认成功：页面已离开仍标记", 1, env.dirty)
        val second = WithdrawController(deps, scope).also { it.start() }
        second.flow.askAbandon("key-a1")
        second.flow.abandon("key-a1")
        second.leave()
        api.abandons.single().complete(ok("""{"withdrawal_id":"W9"}"""))
        assertEquals("放弃得到 completed 且原结果成功：页面已离开仍标记", 2, env.dirty)
    }

    /** 共性第 58 条：每个钱包实例各自收到失效通知并各自重取，一个实例刷新不清掉其他实例的需求。 */
    @Test
    fun lesson58_everyWalletInstanceRefreshesIndependently() {
        val one = WalletController(deps, pageScope)
        val two = WalletController(deps, pageScope)
        one.load()
        two.load()
        api.of(WalletController.OPERATION)[0].answer.complete(ok(SUMMARY))
        api.of(WalletController.OPERATION)[1].answer.complete(ok(SUMMARY))
        one.refresh()
        deps.walletDirty.notify("user-a", env.epoch)
        assertEquals("两个实例都重取", 5, api.of(WalletController.OPERATION).size)
        assertTrue(two.alive)
    }

    // ---------------------------------------------------------------- R3-03

    @Test
    fun r3_03_focusReadNeverOverwritesNewerDetailAcceptedThisRound() {
        val records = WithdrawRecordsController(deps, pageScope, focusId = "W7")
        records.load()
        api.of(WithdrawRecordsController.LIST_OPERATION)[0].answer.complete(ok(page("W7", "PENDING_REVIEW")))
        // 上次定位详情读取失败
        api.of(WithdrawRecordsController.DETAIL_OPERATION)[0].answer.complete(fail(50001))
        assertTrue(records.refresh())
        api.of(WithdrawRecordsController.LIST_OPERATION)[1].answer.complete(ok(page("W7", "PENDING_REVIEW")))
        val focus = api.of(WithdrawRecordsController.DETAIL_OPERATION)[1]
        // 定位读取 F1 在途时用户展开这张单，普通详情 F2 先回来已到账
        records.toggle(records.state.value.version, "W7")
        api.of(WithdrawRecordsController.DETAIL_OPERATION)[2].answer.complete(ok(record("W7", "PAID_API", "\"20260928200040011100\"")))
        focus.answer.complete(ok(record("W7", "PENDING_REVIEW", "null")))
        val state = records.state.value
        assertEquals("PAID_API", state.items.first().status)
        assertEquals("20260928200040011100", state.details["W7"]?.channelOrderId)
    }

    // ---------------------------------------------------------------- R3-04

    @Test
    fun r3_04_recordsRefreshWhileGatedShowsErrorOnOldList() {
        val records = WithdrawRecordsController(deps, pageScope, focusId = null)
        records.load()
        api.of(WithdrawRecordsController.LIST_OPERATION)[0].answer.complete(ok(page("W1", "PENDING_REVIEW")))
        gates.read.start(60_000)
        assertFalse(records.refresh())
        assertEquals(
            "error.42901",
            records.state.value.error
                ?.key,
        )
        assertEquals("保留列表", 1, records.state.value.items.size)
    }

    // ---------------------------------------------------------------- R3-05

    @Test
    fun r3_05_payoutReadCancelledBySendGateEndsLoading() {
        val controller = PayoutAccountController(deps, pageScope)
        controller.load()
        gates.read.start(60_000)
        rejectBySendGate(api.of(PayoutAccountController.GET_OPERATION)[0])
        assertEquals("不停在加载中", com.couli.feature.wallet.PayoutSnapshot.ERROR, controller.state.value.phase)
        assertEquals(
            "error.42901",
            controller.state.value.loadError
                ?.key,
        )
    }

    @Test
    fun r3_05_payoutRereadCancelledBySendGateMarksShownAccountFailed() {
        val controller = PayoutAccountController(deps, pageScope)
        controller.load()
        api.of(PayoutAccountController.GET_OPERATION)[0].answer.complete(ok(PAYOUT_ALIPAY))
        val shown = controller.state.value.account!!
        controller.onResume()
        gates.read.start(60_000)
        rejectBySendGate(api.of(PayoutAccountController.GET_OPERATION)[1])
        assertEquals(
            "error.42901",
            controller.state.value.loadError
                ?.key,
        )
        controller.change(controller.state.value.version, shown)
        assertFalse("读取失败时不能发起更换", controller.state.value.editing)
    }

    /** 同一机制（共性第 56 条）：展开详情被发送前限流闸门拦下也给 42901，不静默。 */
    @Test
    fun r3_05_recordDetailCancelledBySendGateShows42901() {
        val records = WithdrawRecordsController(deps, pageScope, focusId = null)
        records.load()
        api.of(WithdrawRecordsController.LIST_OPERATION)[0].answer.complete(ok(page("W1", "PENDING_REVIEW")))
        records.toggle(records.state.value.version, "W1")
        gates.read.start(60_000)
        rejectBySendGate(api.of(WithdrawRecordsController.DETAIL_OPERATION)[0])
        assertEquals(
            "error.42901",
            records.state.value.toast
                ?.key,
        )
    }

    // ---------------------------------------------------------------- 定向复核 RC-01（S0，共性第 59 条）：输入、自动填充与金额保存回调

    /** 清空通知执行之后，A 表单的旧输入回调才到达：不写进 B 的新表单；B 自己的输入照常。 */
    @Test
    fun rc01_realnameOldInputAfterNoticeNotWrittenIntoBForm() {
        val controller = RealNameController(deps(realname = FakeRealName()), pageScope) {}
        val ownerA = controller.state.value.owner
        switchToBWithoutNotice()
        controller.onSessionChanged()
        assertEquals("user-b", controller.state.value.owner.account)
        controller.onNameChange("测试", ownerA)
        controller.onIdChange("11010119900307001X", ownerA)
        assertEquals("A 的姓名不进 B 的表单", "", controller.state.value.name)
        assertEquals("A 的身份证号不进 B 的表单", "", controller.state.value.idNo)
        controller.onIdChange("110101199003070028", controller.state.value.owner)
        assertEquals("110101199003070028", controller.state.value.idNo)
    }

    /** 会话已换、清空通知还没执行：旧回调同样不写（不以执行时的会话为准）。 */
    @Test
    fun rc01_realnameInputBeforeNoticeNotWritten() {
        val controller = RealNameController(deps(realname = FakeRealName()), pageScope) {}
        val ownerA = controller.state.value.owner
        switchToBWithoutNotice()
        controller.onIdChange("11010119900307001X", ownerA)
        controller.onIdChange("11010119900307001X")
        assertEquals("", controller.state.value.idNo)
    }

    @Test
    fun rc01_payoutOldInputAfterNoticeNotWrittenIntoBForm() {
        val controller = PayoutAccountController(deps, pageScope)
        controller.load()
        api.of(PayoutAccountController.GET_OPERATION)[0].answer.complete(ok("""{"payout_method":null}"""))
        val ownerA = controller.flow.state.value.owner
        switchToBWithoutNotice()
        controller.onSessionChanged()
        controller.chooseMethod("bank_card", ownerA)
        controller.onCardChange("6222020200001234567", ownerA)
        controller.onBankChange("示例银行", ownerA)
        controller.onLogonChange("fixture@example.com", ownerA)
        controller.onPayeeChange("测试", ownerA)
        val form = controller.state.value.form
        assertEquals("A 填的卡号、户名不进 B 的表单", PayoutForm(), form)
        controller.onPayeeChange("测试", controller.flow.state.value.owner)
        assertEquals("测试", controller.state.value.form.payeeName)
    }

    @Test
    fun rc01_withdrawOldAmountAfterNoticeNotWrittenIntoBForm() {
        val controller = withdraw()
        val ownerA = controller.flow.state.value.owner
        switchToBWithoutNotice()
        assertFalse("清空通知前的旧回调不受理", controller.onAmountChange("12", ownerA))
        controller.onSessionChanged()
        assertFalse("清空通知后的旧回调不受理", controller.onAmountChange("10", ownerA))
        assertEquals("", controller.state.value.amountText)
        assertTrue(controller.onAmountChange("10", controller.flow.state.value.owner))
        assertEquals("10", controller.state.value.amountText)
    }

    @Test
    fun rc01_laborOldToggleAfterNoticeNotApplied() {
        val controller = LaborAgreementController(deps(labor = FakeLabor()), pageScope) {}
        controller.load()
        val ownerA = controller.state.value.owner
        switchToBWithoutNotice()
        controller.onSessionChanged()
        controller.toggle(ownerA)
        assertFalse("A 页上的勾选不作用于 B 读到的协议", controller.state.value.checked)
    }

    // ---------------------------------------------------------------- 定向复核 RC-02（共性第 60 条）：二次验证面板沿用打开它的身份

    @Test
    fun rc02_stepUpPanelOfANeverVerifiesOrResendsAsB() {
        val controller = withdraw()
        val flow = controller.flow
        var sms = 0
        api.auto = { call ->
            if (call.operationId == "sendSmsCode") {
                sms += 1
                fail(50001)
            } else {
                null
            }
        }
        val body = """{"amount_fen":1000}"""
        flow.submit(body, body)
        api
            .of(WithdrawController.OPERATION)
            .single()
            .answer
            .complete(fail(10003))
        val panel = flow.stepUp.state.value!!
        assertEquals("面板沿用提交时固定的身份", FormOwner("user-a", 1L), panel.owner)
        assertEquals(1, sms)
        flow.stepUp.onCodeChange("123456", panel.owner)
        // B 的令牌已保存，清空通知还没执行
        switchToBWithoutNotice()
        flow.stepUp.onCodeChange("654321", panel.owner)
        assertEquals(
            "123456",
            flow.stepUp.state.value!!
                .code,
        )
        flow.stepUp.verify(panel.owner)
        flow.stepUp.verify()
        assertTrue("A 面板的验证码不以 B 的身份发出", api.of("stepUp").isEmpty())
        flow.stepUp.resend(panel.owner)
        flow.stepUp.resend()
        assertEquals("不按 B 的号码、槽位重新发码", 1, sms)
    }

    @Test
    fun rc02_stepUpPanelOpenedFromConfirmUsesConfirmOwner() {
        api.pending = pendingRecord("key-a1")
        val controller = withdraw()
        val flow = controller.flow
        flow.confirm("key-a1")
        api.confirms.single().complete(fail(10003))
        assertEquals(
            FormOwner("user-a", 1L),
            flow.stepUp.state.value
                ?.owner,
        )
    }

    // ---------------------------------------------------------------- 定向复核 RC-03（共性第 61 条）：钱包失效通知带已固定的会话

    @Test
    fun rc03_dirtyNoticeCarriesFixedSessionEvenIfBSavedRightAfterCheck() {
        val controller = withdraw()
        val body = """{"amount_fen":1000}"""
        controller.flow.submit(body, body)
        val call = api.of(WithdrawController.OPERATION).single()
        // A 的成功通过 settleShared 的归属核对后，IO 线程随即保存了 B 的令牌
        env.afterAccountRead = { switchToBWithoutNotice() }
        call.answer.complete(ok("""{"withdrawal_id":"W1"}"""))
        assertEquals("user-b", env.user)
        assertEquals("通知按 A 的账号与世代发出，不重读当前会话", listOf("user-a" to 1L), env.dirtyFor)
    }

    // ---------------------------------------------------------------- 定向复核 RC-04（共性第 62 条）：发送前限流拒绝保留明确原因

    /** 网络层写出前被发送前闸门拒绝；Retry-After 随即到期，结果才交付主线程。 */
    private fun rejectThenExpire(entry: Call) {
        assertEquals(
            false,
            entry.call.ownership
                ?.sendAllowed
                ?.invoke(),
        )
        now += 61_000
        entry.answer.complete(ApiResult.LocalReject("call_cancelled"))
    }

    @Test
    fun rc04_payoutReadRejectedThenGateExpiresStillSettles() {
        val controller = PayoutAccountController(deps, pageScope)
        controller.load()
        gates.read.start(60_000)
        rejectThenExpire(api.of(PayoutAccountController.GET_OPERATION)[0])
        assertEquals("不停在加载中", PayoutSnapshot.ERROR, controller.state.value.phase)
        assertEquals(
            "error.42901",
            controller.state.value.loadError
                ?.key,
        )
        assertFalse("可否重试按当前截止时间", gates.read.active)
    }

    @Test
    fun rc04_payoutReadCancelledNotByGateStaysSilent() {
        val controller = PayoutAccountController(deps, pageScope)
        controller.load()
        gates.read.start(60_000)
        // 发送前闸门从未拒绝它（例如归属核对先失败）：不是限流，不显示 42901
        api.of(PayoutAccountController.GET_OPERATION)[0].answer.complete(ApiResult.LocalReject("call_cancelled"))
        assertNull(controller.state.value.loadError)
    }

    @Test
    fun rc04_withdrawPayoutRowRejectedThenGateExpiresShows42901() {
        api.auto = { call -> if (call.operationId == WithdrawController.RULES_OPERATION) ok(RULES_OK) else null }
        val controller = WithdrawController(deps, pageScope).also { it.start() }
        gates.read.start(60_000)
        rejectThenExpire(api.of(WithdrawController.PAYOUT_OPERATION).single())
        assertEquals(
            "error.42901",
            controller.state.value.payoutError
                ?.key,
        )
    }

    @Test
    fun rc04_withdrawRulesRejectedThenGateExpiresEndsLoading() {
        api.auto = { call -> if (call.operationId == WithdrawController.PAYOUT_OPERATION) ok(PAYOUT_ALIPAY) else null }
        val controller = WithdrawController(deps, pageScope).also { it.start() }
        gates.read.start(60_000)
        rejectThenExpire(api.of(WithdrawController.RULES_OPERATION).single())
        assertEquals(WithdrawSnapshot.ERROR, controller.state.value.phase)
        assertEquals(
            "error.42901",
            controller.state.value.loadError
                ?.key,
        )
    }

    @Test
    fun rc04_walletSummaryRejectedThenGateExpiresShows42901() {
        val wallet = WalletController(deps, pageScope)
        wallet.load()
        gates.read.start(60_000)
        rejectThenExpire(api.of(WalletController.OPERATION).single())
        assertEquals(
            "error.42901",
            wallet.state.value.error
                ?.key,
        )
    }

    @Test
    fun rc04_recordsListAndDetailRejectedThenGateExpiresShow42901() {
        val records = WithdrawRecordsController(deps, pageScope, focusId = null)
        records.load()
        gates.read.start(60_000)
        rejectThenExpire(api.of(WithdrawRecordsController.LIST_OPERATION).single())
        assertEquals(
            "error.42901",
            records.state.value.error
                ?.key,
        )
        assertTrue(records.refresh())
        api.of(WithdrawRecordsController.LIST_OPERATION)[1].answer.complete(ok(page("W1", "PENDING_REVIEW")))
        records.toggle(records.state.value.version, "W1")
        gates.read.start(60_000)
        rejectThenExpire(api.of(WithdrawRecordsController.DETAIL_OPERATION).single())
        assertEquals(
            "error.42901",
            records.state.value.toast
                ?.key,
        )
    }

    @Test
    fun rc04_recordsLoadMoreRejectedThenGateExpiresShows42901() {
        val records = WithdrawRecordsController(deps, pageScope, focusId = null)
        records.load()
        api.of(WithdrawRecordsController.LIST_OPERATION)[0].answer.complete(
            ok("""{"items":[${record("W1", "PENDING_REVIEW", "null")}],"next_cursor":"c1"}"""),
        )
        records.loadMore()
        gates.read.start(60_000)
        rejectThenExpire(api.of(WithdrawRecordsController.LIST_OPERATION)[1])
        assertFalse(records.state.value.loadingMore)
        assertEquals(
            "error.42901",
            records.state.value.moreError
                ?.key,
        )
    }

    @Test
    fun rc04_submitRejectedThenGateExpiresShows42901() {
        val controller = withdraw()
        val flow = controller.flow
        val body = """{"amount_fen":1000}"""
        flow.submit(body, body)
        gates.withdraw.start(60_000)
        rejectThenExpire(api.of(WithdrawController.OPERATION).single())
        assertEquals(FlowState.EDITING, flow.state.value.phase)
        assertEquals(
            "error.42901",
            flow.state.value.error
                ?.key,
        )
    }

    @Test
    fun rc04_stepUpSmsAndVerifyRejectedThenGateExpiresShow42901() {
        val controller = withdraw()
        val flow = controller.flow
        api.auto = { null }
        val body = """{"amount_fen":1000}"""
        flow.submit(body, body)
        api
            .of(WithdrawController.OPERATION)
            .single()
            .answer
            .complete(fail(10003))
        gates.sms.start(60_000)
        rejectThenExpire(api.of("sendSmsCode").single())
        assertEquals(
            "发码被发送前限流拒绝",
            "error.42901",
            flow.stepUp.state.value
                ?.error
                ?.key,
        )
        flow.stepUp.onCodeChange("123456")
        flow.stepUp.verify()
        gates.verify.start(60_000)
        rejectThenExpire(api.of("stepUp").single())
        assertEquals(
            "验证被发送前限流拒绝",
            "error.42901",
            flow.stepUp.state.value
                ?.error
                ?.key,
        )
    }

    // ---------------------------------------------------------------- 测试替身（同第 2 轮）

    private class FakeRealName : RealNameBackend {
        val answers = mutableListOf<CompletableDeferred<ApiResult>>()

        override suspend fun submit(
            name: String,
            idNo: String,
            idempotencyKey: String,
        ): ApiResult = CompletableDeferred<ApiResult>().also { answers += it }.await()
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
