package com.couli.app.wallet

import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.ErrorEffect
import com.couli.core.network.ErrorEffectKind
import com.couli.core.network.PendingKeyRecord
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PendingTarget
import com.couli.core.network.ReplayMode
import com.couli.core.wallet.PayoutAccountDisplay
import com.couli.core.wallet.StepUpTokenCache
import com.couli.core.wallet.WalletGates
import com.couli.feature.wallet.FlowState
import com.couli.feature.wallet.PayoutAccountController
import com.couli.feature.wallet.StepUpUiState
import com.couli.feature.wallet.WalletApi
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
 * APP-09 Android 第 1 轮评审（android-review-r1.md R1-01..R1-08、R1-12、R1-14；共性第 14–20、23、25 条）的执行器级复现：真实的 SensitiveFlow、
 * StepUpController、WithdrawController、WithdrawRecordsController、PayoutAccountController，网络换成可控的假 [WalletApi]（每个请求一个
 * [CompletableDeferred]，测试决定先后与内容），协程在 Unconfined 上同步推进，交错顺序完全确定。网络层的点击归属（PendingTarget）另见
 * core/network 的 ApiClientPendingTargetTest。
 */
@Suppress("LargeClass", "TooManyFunctions")
class WalletReviewR1Test {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var now = 1_000_000L

    @After
    fun tearDown() = scope.cancel()

    // ---------------------------------------------------------------- 假环境

    private class Env : WalletEnv {
        var epoch = 1L
        var user: String? = "user-a"
        val opened = mutableListOf<String>()

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
    }

    private class Call(
        val call: ApiCall,
        val answer: CompletableDeferred<ApiResult> = CompletableDeferred(),
    )

    private class FakeApi : WalletApi {
        val calls = mutableListOf<Call>()
        val confirms = mutableListOf<Pair<PendingTarget, CompletableDeferred<ApiResult>>>()
        val abandons = mutableListOf<Pair<PendingTarget, CompletableDeferred<ApiResult>>>()
        val tokens = mutableListOf<String?>()
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
        ): ApiResult {
            tokens += stepUpToken
            val answer = CompletableDeferred<ApiResult>()
            confirms += target to answer
            return answer.await()
        }

        override suspend fun abandonPending(
            operationId: String,
            target: PendingTarget,
        ): ApiResult {
            val answer = CompletableDeferred<ApiResult>()
            abandons += target to answer
            return answer.await()
        }

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
    private val deps =
        WalletDeps(
            api = api,
            env = env,
            gates = gates,
            tokens = StepUpTokenCache(),
            cache = null,
            mask = null,
            realname = null,
            labor = null,
            clock = { now },
            service = scope,
        )

    private fun ok(data: String) = ApiResult.Success(Json.parseToJsonElement(data), "trace-000123", null)

    private fun fail(
        code: Int,
        data: String = "{}",
    ) = ApiResult.Failure(
        code,
        "",
        Json.parseToJsonElement(data).jsonObject,
        "trace-000123",
        ErrorEffect(code, ErrorEffectKind.TOAST, ReplayMode.NONE, false),
        null,
    )

    private fun pendingRecord(
        key: String,
        account: String = "user-a",
    ) = PendingKeyRecord(
        key,
        WithdrawController.OPERATION,
        """{"amount_fen":1000}""",
        account,
        """{"amount_fen":1000}""",
        PendingKeyStore.UNKNOWN,
        everUnknown = true,
    )

    /** 二次验证面板的发码与验证一律自动成功。 */
    private fun autoStepUp() {
        api.auto = { call ->
            when (call.operationId) {
                "sendSmsCode" -> ok("""{"resend_after_sec":60,"expires_in_sec":300}""")
                "stepUp" -> ok("""{"step_up_token":"fixture-step-up-token","expire_at":"2099-01-01T00:00:00+08:00"}""")
                else -> null
            }
        }
    }

    private fun withdrawWithPending(key: String = "key-a1"): WithdrawController {
        api.pending = pendingRecord(key)
        api.auto = { call ->
            when (call.operationId) {
                WithdrawController.RULES_OPERATION -> ok(RULES_OK)
                WithdrawController.PAYOUT_OPERATION -> ok(PAYOUT_ALIPAY)
                else -> null
            }
        }
        return WithdrawController(deps, scope).also { it.start() }
    }

    // ---------------------------------------------------------------- R1-01 / R1-02

    @Test
    fun r1_01_confirmAndAbandonCarryClickAccountEpochAndKey() {
        val controller = withdrawWithPending("key-a1")
        val flow = controller.flow
        assertEquals(
            "key-a1",
            flow.state.value.pending
                ?.key,
        )
        flow.confirm("key-a1")
        val (target, _) = api.confirms.single()
        assertEquals("user-a", target.account)
        assertEquals("key-a1", target.key)
        val ownership = checkNotNull(target.ownership)
        assertTrue(ownership.stillOwned())
        // 点击之后切换到账号 B（会话世代变了）：这次确认在网络层读取、占用、发送前都不再归属（不会作用于 B 的记录）
        env.epoch += 1
        env.user = "user-b"
        assertFalse("会话换代后点击时的归属失效", ownership.stillOwned())

        // 放弃同样只按点击时的那张卡：卡上不是这个键的点击不受理
        env.epoch = 1
        env.user = "user-a"
        val other = withdrawWithPending("key-a2")
        other.flow.askAbandon("key-a1")
        other.flow.abandon("key-a1")
        assertTrue("不是本页渲染的键，不发作废", api.abandons.isEmpty())
        other.flow.askAbandon("key-a2")
        other.flow.abandon("key-a2")
        assertEquals(
            "key-a2",
            api.abandons
                .single()
                .first.key,
        )
        assertEquals(
            "user-a",
            api.abandons
                .single()
                .first.account,
        )
    }

    @Test
    fun r1_02_confirmAndAbandonHaveSendTimeGateAndKeepKeyWhenBlockedLocally() {
        val controller = withdrawWithPending()
        val flow = controller.flow
        // 点击之前已在禁用期：不发，提示稍后再试，记录保留
        gates.withdraw.start(5_000)
        flow.confirm("key-a1")
        flow.askAbandon("key-a1")
        assertTrue(api.confirms.isEmpty())
        assertTrue(api.abandons.isEmpty())
        assertEquals(
            "pending_confirm.retry_later",
            flow.state.value.pendingNotice
                ?.key,
        )
        now += 6_000
        // 点击时可发，排队期间另一请求收到 42901：发送前闸门（网络层每次写出前核对）为 false
        flow.confirm("key-a1")
        val target = api.confirms.single().first
        assertEquals(true, target.ownership?.sendAllowed?.invoke())
        gates.withdraw.start(5_000)
        assertEquals(false, target.ownership?.sendAllowed?.invoke())
        // 网络层本地拒绝：原键保持待确认，提示稍后再试
        api.confirms
            .single()
            .second
            .complete(ApiResult.LocalReject("call_cancelled"))
        assertEquals(FlowState.PENDING, flow.state.value.phase)
        assertEquals(
            "key-a1",
            flow.state.value.pending
                ?.key,
        )
        assertEquals(
            "pending_confirm.retry_later",
            flow.state.value.pendingNotice
                ?.key,
        )
    }

    // ---------------------------------------------------------------- R1-03

    @Test
    fun r1_03_confirmReplaysAfterStepUpAtMostOnce() {
        val controller = withdrawWithPending()
        autoStepUp()
        val flow = controller.flow
        flow.confirm("key-a1")
        api.confirms[0].second.complete(fail(10003))
        assertNotNull("第一次 10003 弹二次验证", flow.stepUp.state.value)
        flow.stepUp.onCodeChange("123456")
        flow.stepUp.verify()
        assertEquals("验证后原键重放一次", 2, api.confirms.size)
        assertEquals("fixture-step-up-token", api.tokens[1])
        api.confirms[1].second.complete(fail(10003))
        assertNull("再收到 10003 不再弹面板", flow.stepUp.state.value)
        assertEquals("不再续发", 2, api.confirms.size)
        assertEquals(FlowState.PENDING, flow.state.value.phase)
        assertEquals(
            "key-a1",
            flow.state.value.pending
                ?.key,
        )
        assertEquals(
            "error.10003",
            flow.state.value.pendingNotice
                ?.key,
        )
        // 用户再点【确认上一笔】是新的一次确认，预算重新计
        flow.confirm("key-a1")
        api.confirms[2].second.complete(fail(10003))
        assertNotNull(flow.stepUp.state.value)
    }

    @Test
    fun r1_03_submitReplaysAfterStepUpAtMostOnce() {
        api.auto = { call ->
            when (call.operationId) {
                WithdrawController.RULES_OPERATION -> ok(RULES_OK)
                WithdrawController.PAYOUT_OPERATION -> ok(PAYOUT_ALIPAY)
                "sendSmsCode" -> ok("""{"resend_after_sec":60}""")
                "stepUp" -> ok("""{"step_up_token":"fixture-step-up-token","expire_at":"2099-01-01T00:00:00+08:00"}""")
                else -> null
            }
        }
        val controller = WithdrawController(deps, scope).also { it.start() }
        val flow = controller.flow
        flow.submit("""{"amount_fen":1000}""", """{"amount_fen":1000}""")
        api.of(WithdrawController.OPERATION)[0].answer.complete(fail(10003))
        flow.stepUp.onCodeChange("123456")
        flow.stepUp.verify()
        val submits = api.of(WithdrawController.OPERATION)
        assertEquals(2, submits.size)
        assertEquals("原键重放", submits[0].call.idempotencyKey, submits[1].call.idempotencyKey)
        submits[1].answer.complete(fail(10003))
        assertNull(flow.stepUp.state.value)
        assertEquals(2, api.of(WithdrawController.OPERATION).size)
        assertEquals(FlowState.EDITING, flow.state.value.phase)
        assertEquals(
            "error.10003",
            flow.state.value.error
                ?.key,
        )
    }

    // ---------------------------------------------------------------- R1-04

    @Test
    fun r1_04_resendEntersSendingAtOnceAndPanelsShareInFlightSlot() {
        val first =
            com.couli.feature.wallet
                .StepUpController(deps, scope) { true }
        first.open(
            "withdraw",
            com.couli.feature.wallet.FormOwner
                .of(env),
            onToken = {},
            onClosed = {},
        )
        assertEquals(StepUpUiState.SENDING, first.state.value?.phase)
        // 连点【重新获取】：发码在途期间不受理
        repeat(3) { first.resend() }
        assertEquals(1, api.of("sendSmsCode").size)
        // 另一个面板（同账号、号码、action）在途期间打开：不再发码
        val second =
            com.couli.feature.wallet
                .StepUpController(deps, scope) { true }
        second.open(
            "withdraw",
            com.couli.feature.wallet.FormOwner
                .of(env),
            onToken = {},
            onClosed = {},
        )
        assertEquals(1, api.of("sendSmsCode").size)
        assertEquals(StepUpUiState.SENDING, second.state.value?.phase)
        // 原面板关闭后结果才回来：共享倒计时照样更新，第二个面板恢复倒计时
        first.dismiss()
        api.of("sendSmsCode")[0].answer.complete(ok("""{"resend_after_sec":60}"""))
        assertEquals(StepUpUiState.READY, second.state.value?.phase)
        assertTrue((second.state.value?.resendLeftSec ?: 0) > 0)
        second.dismiss()
        // 第三次打开：倒计时还没到，不发
        val third =
            com.couli.feature.wallet
                .StepUpController(deps, scope) { true }
        third.open(
            "withdraw",
            com.couli.feature.wallet.FormOwner
                .of(env),
            onToken = {},
            onClosed = {},
        )
        assertEquals(1, api.of("sendSmsCode").size)
        third.dismiss()
    }

    @Test
    fun r1_04_sendTimeGateChecksSharedDeadline() {
        val panel =
            com.couli.feature.wallet
                .StepUpController(deps, scope) { true }
        panel.open(
            "withdraw",
            com.couli.feature.wallet.FormOwner
                .of(env),
            onToken = {},
            onClosed = {},
        )
        val owner = api.of("sendSmsCode")[0].call.ownership!!
        assertEquals(true, owner.sendAllowed?.invoke())
        // 别处刚建立了同一槽位的截止时间：这次请求在网络层续发前被拦
        deps.sms.start(
            com.couli.feature.wallet.SmsDeadlines
                .Slot("user-a", "13800005678", "withdraw"),
            now + 60_000,
        )
        assertEquals(false, owner.sendAllowed?.invoke())
        panel.dismiss()
    }

    // ---------------------------------------------------------------- R1-05（页面接线）

    @Test
    fun r1_05_abandonGetting10004OpensLaborAgreementAndKeepsKey() {
        val controller = withdrawWithPending()
        val flow = controller.flow
        flow.askAbandon("key-a1")
        flow.abandon("key-a1")
        api.abandons
            .single()
            .second
            .complete(fail(10004, """{"consent_type":"labor_agreement"}"""))
        assertEquals(listOf("LaborAgreement"), env.opened)
        assertEquals(
            "key-a1",
            flow.state.value.pending
                ?.key,
        )
        assertEquals(FlowState.PENDING, flow.state.value.phase)
    }

    // ---------------------------------------------------------------- R1-06

    @Test
    fun r1_06_olderPayoutReadNeverOverwritesNewerAccount() {
        api.auto = { call -> if (call.operationId == WithdrawController.RULES_OPERATION) ok(RULES_OK) else null }
        val controller = WithdrawController(deps, scope)
        controller.start()
        controller.onResume()
        val reads = api.of(WithdrawController.PAYOUT_OPERATION)
        assertEquals(2, reads.size)
        reads[1].answer.complete(ok(PAYOUT_BANK))
        reads[0].answer.complete(ok(PAYOUT_ALIPAY))
        assertEquals(
            "bank_card",
            controller.state.value.payout
                ?.methodRaw,
        )
        assertEquals(
            "尾号 0009",
            controller.state.value.payout
                ?.maskedAccount,
        )
    }

    // ---------------------------------------------------------------- R1-07 / R1-08

    @Test
    fun r1_07_refreshBlocksPagingWithOldCursorUntilFirstPageReturns() {
        val records = WithdrawRecordsController(deps, scope, focusId = null)
        records.load()
        api.of(WithdrawRecordsController.LIST_OPERATION)[0].answer.complete(ok(page("W1", "cursor-41")))
        assertEquals("cursor-41", records.state.value.nextCursor)
        assertTrue(records.refresh())
        records.loadMore()
        val lists = api.of(WithdrawRecordsController.LIST_OPERATION)
        assertEquals("刷新第一页回来之前不翻页", 2, lists.size)
        assertFalse(lists.any { it.call.query?.contains("cursor=") == true })
        lists[1].answer.complete(ok(page("W1", "cursor-21")))
        records.loadMore()
        assertEquals(
            "第一页回来后按新游标翻页",
            "cursor=cursor-21&limit=20",
            api
                .of(WithdrawRecordsController.LIST_OPERATION)
                .last()
                .call.query,
        )
    }

    @Test
    fun r1_08_olderDetailOfSameWithdrawalNeverOverwritesNewer() {
        val records = WithdrawRecordsController(deps, scope, focusId = null)
        records.load()
        api.of(WithdrawRecordsController.LIST_OPERATION)[0].answer.complete(ok(page("W1", null)))
        records.toggle(records.state.value.version, "W1")
        records.toggle(records.state.value.version, "W1")
        records.toggle(records.state.value.version, "W1")
        val details = api.of(WithdrawRecordsController.DETAIL_OPERATION)
        assertEquals(2, details.size)
        details[1].answer.complete(ok(record("W1", "PAID_API", "\"20260928200040011100\"")))
        val after = records.state.value.version
        details[0].answer.complete(ok(record("W1", "PENDING_REVIEW", "null")))
        assertEquals(
            "PAID_API",
            records.state.value.details["W1"]
                ?.status,
        )
        assertEquals(
            "20260928200040011100",
            records.state.value.details["W1"]
                ?.channelOrderId,
        )
        // 共性第 28 条：卡片本身（状态、副文案、操作入口）也用同一份最新详情
        assertEquals(
            "PAID_API",
            records.state.value.items
                .single()
                .status,
        )
        assertEquals("旧详情不推进版本", after, records.state.value.version)
    }

    // ---------------------------------------------------------------- R1-12

    @Test
    fun r1_12_oldCanWithdrawFalseDoesNotStopAmountFromRefetchingRules() {
        var rulesCalls = 0
        api.auto = { call ->
            when (call.operationId) {
                WithdrawController.RULES_OPERATION -> {
                    rulesCalls += 1
                    if (call.query == null) ok(RULES_BLOCKED_30305) else ok(RULES_OK)
                }

                WithdrawController.PAYOUT_OPERATION -> {
                    ok(PAYOUT_ALIPAY)
                }

                else -> {
                    null
                }
            }
        }
        val controller = WithdrawController(deps, scope)
        controller.start()
        assertEquals(
            false,
            controller.state.value.rules
                ?.canWithdraw,
        )
        val before = rulesCalls
        controller.onAmountChange("10")
        // 0.5 秒停顿（真实延时，Unconfined 下在默认执行器上续跑）
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (rulesCalls == before && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
        assertEquals("合法金额停顿后带 amount_fen 重取 rules", before + 1, rulesCalls)
        Thread.sleep(POLL_MS)
        assertEquals(
            true,
            controller.state.value.rules
                ?.canWithdraw,
        )
    }

    // ---------------------------------------------------------------- R1-14

    @Test
    fun r1_14_changeAccountUsesBuildTimeVersionAndIdentity() {
        var answer = PAYOUT_ALIPAY
        api.auto = { call -> if (call.operationId == "getPayoutAccount") ok(answer) else null }
        val controller = PayoutAccountController(deps, scope)
        controller.load()
        val shown: PayoutAccountDisplay = controller.state.value.account!!
        val shownVersion = controller.state.value.version
        // 刷新换成了另一个账号（版本前进）：旧卡片的回调带的是旧版本与旧账号
        answer = PAYOUT_BANK
        controller.onResume()
        assertEquals(
            "bank_card",
            controller.state.value.account
                ?.methodRaw,
        )
        controller.change(shownVersion, shown)
        assertFalse("旧画面的【更换收款账号】不作用于新数据", controller.state.value.editing)
        controller.change(controller.state.value.version, shown)
        assertFalse("账号身份不符也不受理", controller.state.value.editing)
        controller.change(controller.state.value.version, controller.state.value.account!!)
        assertTrue(controller.state.value.editing)
    }

    // ---------------------------------------------------------------- 共性第 26–32 条（鸿蒙第 2 轮）

    @Test
    fun lesson27_late42901AfterPanelClosedStillWritesSharedGate() {
        val panel =
            com.couli.feature.wallet
                .StepUpController(deps, scope) { true }
        panel.open(
            "withdraw",
            com.couli.feature.wallet.FormOwner
                .of(env),
            onToken = {},
            onClosed = {},
        )
        panel.dismiss()
        val failure =
            ApiResult.Failure(
                42901,
                "",
                JsonObject(emptyMap()),
                "trace-000123",
                ErrorEffect(42901, ErrorEffectKind.TOAST, ReplayMode.NONE, false, disableMillis = 9_000),
                null,
            )
        api.of("sendSmsCode")[0].answer.complete(failure)
        assertTrue("面板已关闭，42901 仍写入共享限流", gates.sms.active)
        assertFalse(
            deps.sms.busy(
                com.couli.feature.wallet.SmsDeadlines
                    .Slot("user-a", "13800005678", "withdraw"),
            ),
        )
    }

    @Test
    fun lesson26_secondPanelRecoversRetryStateWhenFirstSendFails() {
        val first =
            com.couli.feature.wallet
                .StepUpController(deps, scope) { true }
        first.open(
            "withdraw",
            com.couli.feature.wallet.FormOwner
                .of(env),
            onToken = {},
            onClosed = {},
        )
        val second =
            com.couli.feature.wallet
                .StepUpController(deps, scope) { true }
        first.dismiss()
        second.open(
            "withdraw",
            com.couli.feature.wallet.FormOwner
                .of(env),
            onToken = {},
            onClosed = {},
        )
        assertEquals(StepUpUiState.SENDING, second.state.value?.phase)
        api.of("sendSmsCode")[0].answer.complete(fail(50001))
        assertEquals("在途结束解除 sending，可重新获取", StepUpUiState.READY, second.state.value?.phase)
        assertEquals(0L, second.state.value?.resendLeftSec)
        second.resend()
        assertEquals(2, api.of("sendSmsCode").size)
        second.dismiss()
    }

    @Test
    fun lesson29_30_refreshFailureKeepsOldListCursorAndShowsRetry() {
        val records = WithdrawRecordsController(deps, scope, focusId = null)
        records.load()
        api.of(WithdrawRecordsController.LIST_OPERATION)[0].answer.complete(ok(page("W1", "cursor-41")))
        records.refresh()
        api.of(WithdrawRecordsController.LIST_OPERATION)[1].answer.complete(fail(50001))
        val state = records.state.value
        assertEquals(listOf("W1"), state.items.map { it.withdrawalId })
        assertNotNull("独立显示错误", state.error)
        assertTrue(state.error!!.showTrace6)
        records.loadMore()
        assertEquals(
            "失败后旧列表与旧游标照旧配对",
            "cursor=cursor-41&limit=20",
            api
                .of(WithdrawRecordsController.LIST_OPERATION)
                .last()
                .call.query,
        )
    }

    @Test
    fun lesson30_walletRefreshServerErrorKeepsOldDataNotCurrent() {
        val wallet =
            com.couli.feature.wallet
                .WalletController(deps, scope)
        wallet.load()
        api.of("getWalletSummary")[0].answer.complete(ok(SUMMARY))
        val version = wallet.state.value.version
        assertTrue(wallet.refresh())
        api.of("getWalletSummary")[1].answer.complete(fail(50001))
        assertNotNull(wallet.state.value.summary)
        assertEquals(
            "error.50001",
            wallet.state.value.error
                ?.key,
        )
        wallet.withdraw(wallet.state.value.version)
        wallet.withdraw(version)
        assertTrue("旧数据不是当前，【提现】不受理", env.opened.isEmpty())
        // 重试成功：错误消失、恢复可用
        assertTrue(wallet.refresh())
        api.of("getWalletSummary")[2].answer.complete(ok(SUMMARY))
        assertNull(wallet.state.value.error)
        wallet.withdraw(wallet.state.value.version)
        assertEquals(listOf("Withdraw"), env.opened)
    }

    @Test
    fun lesson30_payoutRefreshErrorShowsRetryAndBlocksChange() {
        var answer: ApiResult = ok(PAYOUT_ALIPAY)
        api.auto = { call -> if (call.operationId == "getPayoutAccount") answer else null }
        val controller = PayoutAccountController(deps, scope)
        controller.load()
        val shown = controller.state.value.account!!
        answer = fail(50001)
        controller.onResume()
        assertEquals(
            "error.50001",
            controller.state.value.loadError
                ?.key,
        )
        assertEquals(shown, controller.state.value.account)
        controller.change(controller.state.value.version, shown)
        assertFalse(controller.state.value.editing)
    }

    @Test
    fun lesson30_payoutLineErrorSurvivesLaterRulesSuccess() {
        val controller = WithdrawController(deps, scope)
        controller.start()
        api.of(WithdrawController.PAYOUT_OPERATION)[0].answer.complete(fail(50001))
        api.of(WithdrawController.RULES_OPERATION)[0].answer.complete(ok(RULES_OK))
        assertEquals(
            "rules 成功不清掉收款账号的读取错误",
            "error.50001",
            controller.state.value.payoutError
                ?.key,
        )
        controller.retry()
        api.of(WithdrawController.PAYOUT_OPERATION)[1].answer.complete(ok(PAYOUT_ALIPAY))
        assertNull(controller.state.value.payoutError)
    }

    @Test
    fun lesson32_offlineKeepsOldSummaryAsStaleWithoutDiskCache() {
        val wallet =
            com.couli.feature.wallet
                .WalletController(deps, scope)
        wallet.load()
        val loadedAt = now
        api.of("getWalletSummary")[0].answer.complete(ok(SUMMARY))
        now += 60_000
        wallet.refresh()
        api.of("getWalletSummary")[1].answer.complete(ApiResult.TransportFailure("timeout", null, null, false))
        assertNotNull(wallet.state.value.summary)
        assertEquals("用内存里最近一次成功的时间", loadedAt, wallet.state.value.staleAt)
        wallet.withdraw(wallet.state.value.version)
        assertTrue(env.opened.isEmpty())
    }

    // ---------------------------------------------------------------- 共性第 34–38 条（鸿蒙第 3 轮）

    @Test
    fun lesson35_refreshInFlightRejectsAnotherRefresh() {
        val records = WithdrawRecordsController(deps, scope, focusId = null)
        records.load()
        api.of(WithdrawRecordsController.LIST_OPERATION)[0].answer.complete(ok(page("W1", "cursor-41")))
        assertTrue(records.refresh())
        assertFalse("刷新在途时不再受理", records.refresh())
        assertEquals(2, api.of(WithdrawRecordsController.LIST_OPERATION).size)
        api.of(WithdrawRecordsController.LIST_OPERATION)[1].answer.complete(ok(page("W1", "cursor-21")))
        assertTrue(records.refresh())
    }

    @Test
    fun lesson37_asyncStepUpRiskGuideAndSuccessWaitWhilePageCovered() {
        api.auto = { call ->
            when (call.operationId) {
                WithdrawController.RULES_OPERATION -> ok(RULES_OK)
                WithdrawController.PAYOUT_OPERATION -> ok(PAYOUT_ALIPAY)
                else -> null
            }
        }
        val controller = WithdrawController(deps, scope).also { it.start() }
        val flow = controller.flow
        val body = """{"amount_fen":1000}"""
        // 10003：页面被覆盖时不弹面板，保留原键
        flow.submit(body, body)
        controller.onVisibility(false)
        api.of(WithdrawController.OPERATION)[0].answer.complete(fail(10003))
        assertNull("被覆盖时不弹二次验证", flow.stepUp.state.value)
        assertEquals(
            "error.10003",
            flow.state.value.error
                ?.key,
        )
        controller.onVisibility(true)
        flow.submit(body, body)
        val submits = api.of(WithdrawController.OPERATION)
        // F-AND-46（FU-AND-0720）：取 submits[1] 之前先断言提交次数——回来不自动续发、用户再点恰好发出第 2 次
        assertEquals("回来不自动续发，用户再点恰好发出第 2 次", 2, submits.size)
        assertEquals("回来再点沿用原键", submits[0].call.idempotencyKey, submits[1].call.idempotencyKey)
        // 44001：被覆盖时不弹风控弹窗
        controller.onVisibility(false)
        submits[1].answer.complete(fail(44001))
        assertNull(flow.state.value.riskDialog)
        assertEquals(
            "error.44001",
            flow.state.value.error
                ?.key,
        )
        // 10004 引导：被覆盖时不导航
        controller.onVisibility(true)
        flow.submit(body, body)
        controller.onVisibility(false)
        api.of(WithdrawController.OPERATION)[2].answer.complete(fail(10004, """{"consent_type":"labor_agreement"}"""))
        assertTrue("被覆盖时不打开引导页", env.opened.isEmpty())
        // 成功：被覆盖时不替换页面，回到可见再进提现记录
        controller.onVisibility(true)
        flow.submit(body, body)
        controller.onVisibility(false)
        api.of(WithdrawController.OPERATION)[3].answer.complete(ok("""{"withdrawal_id":"W9"}"""))
        assertTrue(env.opened.isEmpty())
        controller.onVisibility(true)
        assertEquals(listOf("WithdrawRecords"), env.opened)
    }

    @Test
    fun lesson38_everyFailureKeepingOldSummaryShowsNeutralUpdatedTime() {
        val wallet =
            com.couli.feature.wallet
                .WalletController(deps, scope)
        wallet.load()
        val loadedAt = now
        api.of("getWalletSummary")[0].answer.complete(ok(SUMMARY))
        now += 60_000
        wallet.refresh()
        api.of("getWalletSummary")[1].answer.complete(fail(50001))
        assertEquals(loadedAt, wallet.state.value.staleAt)
        assertFalse("不说成网络不可用", wallet.state.value.staleNetwork)
        // 坏数据也一样
        wallet.refresh()
        api.of("getWalletSummary")[2].answer.complete(ok("""{"available_fen":"bad"}"""))
        assertEquals(loadedAt, wallet.state.value.staleAt)
        assertFalse(wallet.state.value.staleNetwork)
    }

    private companion object {
        const val WAIT_MS = 3_000L
        const val POLL_MS = 50L
        const val RULES_OK =
            """{"can_withdraw":true,"block_code":null,"block_reason":null,"max_withdrawable_fen":1200,"withdrawable_fen":1234,""" +
                """"daily_remaining":1,"monthly_remaining":10,"est_fee_fen":0,"est_tax_fen":0,"est_net_fen":1000,""" +
                """"quick_amounts_fen":[500,1000]}"""
        const val RULES_BLOCKED_30305 =
            """{"can_withdraw":false,"block_code":30305,"block_reason":null,"max_withdrawable_fen":0,"withdrawable_fen":1234,""" +
                """"daily_remaining":1,"monthly_remaining":10,"est_fee_fen":null,"est_tax_fen":null,"est_net_fen":null,""" +
                """"quick_amounts_fen":[]}"""
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
            cursor: String?,
        ) = """{"items":[${record(id, "PENDING_REVIEW", "null")}],"next_cursor":${cursor?.let { "\"$it\"" } ?: "null"}}"""
    }
}
