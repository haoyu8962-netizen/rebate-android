package com.couli.app.followup

import com.couli.core.agent.AgentErrorCodes
import com.couli.core.agent.toAgentResult
import com.couli.core.me.NicknameOutcome
import com.couli.core.me.WriteOutcome
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.ErrorEffect
import com.couli.core.network.ErrorEffectKind
import com.couli.core.network.PageApi
import com.couli.core.network.ReplayMode
import com.couli.core.network.ResultSignals
import com.couli.core.orders.OrderErrorAction
import com.couli.core.orders.OrderErrorKind
import com.couli.core.orders.PendingDismissAction
import com.couli.core.orders.PendingTrackDismiss
import com.couli.core.push.PushReportState
import com.couli.core.push.PushTokenReporter
import com.couli.core.push.PushTokenSource
import com.couli.core.wallet.OutcomeInput
import com.couli.core.wallet.SensitiveOutcome
import com.couli.core.wallet.WalletError
import com.couli.core.wallet.WalletErrors
import com.couli.feature.me.MeErrors
import com.couli.feature.me.MeResultInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FU-AND-0715：
 * - 第 4 项（F-X-09）：非强更的 10405（网络层 Effect 为 REFRESH_ONLY / 刷新失败清会话）在各页的结局表里一律「统一错误文案 + 可重试，不进强更」；
 *   网络层判定 FORCE_UPDATE 的 10405 照旧进强更。覆盖钱包读取（我的沿用）、钱包敏感写（提现 / 收款账号）、我的写（昵称、申诉共用）、订单列表 /
 *   详情 / 待跟单关闭、Agent 端口。
 * - 第 6 项（F-X-08 ②）：推送令牌上报遇 42901——禁用期内新触发不发、到期后按当时的会话与令牌补发一次、失败旧请求不自动重试；会话换代清禁用期。
 */
class FollowupAnd0715ResultTest {
    private fun failure(
        code: Int,
        kind: ErrorEffectKind,
        disable: Long? = null,
    ) = ApiResult.Failure(
        code = code,
        msg = "",
        data = JsonObject(mapOf("min_supported_version" to JsonPrimitive("0.0.1"))),
        traceId = "trace-0123456789",
        effect = ErrorEffect(code, kind, ReplayMode.NONE, registerPending = false, disableMillis = disable),
        idempotencyKey = null,
    )

    private val refreshOnly = failure(10405, ErrorEffectKind.REFRESH_ONLY)
    private val refreshFailed = failure(10405, ErrorEffectKind.CLEAR_SESSION_LOGIN)
    private val forced = failure(10405, ErrorEffectKind.FORCE_UPDATE)

    @Test
    fun fx09_result_signals_only_force_on_the_network_effect() {
        assertTrue(ResultSignals.forcesUpdate(forced))
        assertFalse(ResultSignals.forcesUpdate(refreshOnly))
        assertTrue(ResultSignals.nonForcedUpdate(refreshOnly))
        assertTrue(ResultSignals.nonForcedUpdate(refreshFailed))
        assertFalse(ResultSignals.nonForcedUpdate(forced))
    }

    @Test
    fun fx09_wallet_and_me_reads_show_the_unified_error_with_trace6() {
        for (result in listOf(refreshOnly, refreshFailed)) {
            val wallet = WalletErrors.of(result)
            assertEquals(WalletError.SERVER, wallet.kind)
            assertEquals("error.50001", wallet.textKey)
            assertTrue(wallet.showTrace6)
            assertEquals("error.50001", MeErrors.of(result).textKey)
        }
        assertEquals(WalletError.FORCE_UPDATE, WalletErrors.of(forced).kind)
    }

    @Test
    fun fx09_wallet_sensitive_writes_stay_retryable() {
        fun of(
            phase: String,
            everUnknown: Boolean,
            forcedNow: Boolean,
        ) = SensitiveOutcome.of(
            SensitiveOutcome.WITHDRAW,
            phase,
            everUnknown,
            OutcomeInput(OutcomeInput.ERROR, code = 10405, forced = forcedNow),
        )
        val submit = of(SensitiveOutcome.SUBMIT, everUnknown = false, forcedNow = false)
        assertEquals(SensitiveOutcome.ERROR_TRACE6, submit.actionRaw)
        assertEquals("error.50001", submit.textKey)
        assertFalse("键不结束", submit.keyEnds)
        assertEquals(SensitiveOutcome.PENDING_CONFIRM, of(SensitiveOutcome.SUBMIT, everUnknown = true, forcedNow = false).actionRaw)
        for (phase in listOf(SensitiveOutcome.CONFIRM, SensitiveOutcome.ABANDON)) {
            val outcome = of(phase, everUnknown = true, forcedNow = false)
            assertEquals(phase, SensitiveOutcome.PENDING_CONFIRM, outcome.actionRaw)
            assertEquals(SensitiveOutcome.RETRY_LATER_KEY, outcome.textKey)
            assertEquals(SensitiveOutcome.FORCE_UPDATE, of(phase, everUnknown = true, forcedNow = true).actionRaw)
        }
        assertEquals(SensitiveOutcome.FORCE_UPDATE, of(SensitiveOutcome.SUBMIT, everUnknown = false, forcedNow = true).actionRaw)
    }

    @Test
    fun fx09_me_writes_show_the_unified_error() {
        val outcome = NicknameOutcome.model(MeResultInput.of(refreshOnly))
        assertEquals(WriteOutcome.ERROR_TRACE6, outcome.actionRaw)
        assertEquals("error.50001", outcome.textKey)
        assertTrue("保留输入（可再点）", outcome.keepInput)
        assertEquals(WriteOutcome.FORCE_UPDATE, NicknameOutcome.model(MeResultInput.of(forced)).actionRaw)
    }

    @Test
    fun fx09_orders_and_pending_dismiss_stay_on_the_page() {
        fun of(
            target: String,
            result: ApiResult.Failure,
        ) = OrderErrorAction.of(target, result.code, false, 0L, result.traceId, false, false, ResultSignals.forcesUpdate(result))
        val list = of(OrderErrorAction.LIST_FIRST, refreshOnly)
        assertEquals(OrderErrorKind.ERROR_TRACE6, list.actionRaw)
        assertEquals("error.50001", list.textKey)
        assertTrue(list.showTrace6)
        assertEquals(OrderErrorKind.PAGE_ERROR, of(OrderErrorAction.LIST_NEXT, refreshOnly).actionRaw)
        assertEquals(OrderErrorKind.ERROR_TRACE6, of(OrderErrorAction.DETAIL, refreshFailed).actionRaw)
        assertEquals(OrderErrorKind.FORCE_UPDATE, of(OrderErrorAction.DETAIL, forced).actionRaw)
        assertEquals(
            PendingDismissAction.ERROR_TOAST,
            PendingTrackDismiss.onOutcome(PendingTrackDismiss.KIND_ERROR, 10405, emptyMap(), forced = false).actionRaw,
        )
        assertEquals(
            PendingDismissAction.FORCE_UPDATE,
            PendingTrackDismiss.onOutcome(PendingTrackDismiss.KIND_ERROR, 10405, emptyMap(), forced = true).actionRaw,
        )
    }

    @Test
    fun fx09_agent_port_hands_non_forced_10405_over_as_the_generic_error() {
        val shown = refreshOnly.toAgentResult()
        assertEquals(AgentErrorCodes.GENERIC, shown.code)
        assertEquals("trace 不变", "trace-0123456789", shown.traceId)
        assertEquals(AgentErrorCodes.GENERIC, refreshFailed.toAgentResult().code)
        assertEquals(AgentErrorCodes.FORCE_UPDATE, forced.toAgentResult().code)
    }

    // ---------------------------------------------------------------- F-X-08 ②：推送上报遇 42901

    private class FakeApi : PageApi {
        val sent = mutableListOf<ApiCall>()
        val results = ArrayDeque<ApiResult>()
        val owners = mutableListOf<CallOwnership>()

        override suspend fun send(call: ApiCall): ApiResult {
            call.ownership?.let { owners += it }
            if (call.ownership?.sendAllowed?.invoke() == false) return ApiResult.LocalReject("call_cancelled")
            sent += call
            return results.removeFirstOrNull() ?: ApiResult.Success(JsonObject(emptyMap()), "trace-ok", null)
        }

        override fun cancelOwned(owner: CallOwnership) = Unit

        override fun epochs(): CallEpochs = CallEpochs(0, 0)
    }

    private class Source : PushTokenSource {
        var token: String? = "tok-1"
        var listener: () -> Unit = {}

        override fun start() = Unit

        override fun stop() = Unit

        override fun current(): String? = token

        override fun subscribe(listener: () -> Unit): () -> Unit {
            this.listener = listener
            return {}
        }

        fun change(value: String) {
            token = value
            listener()
        }
    }

    private class State : PushReportState {
        var session = "s1"
        var forcedCalls = 0

        override fun level(): String = "member"

        override fun sessionScope(): String = "full"

        override fun forceUpdate(): String = "supported"

        override fun sessionKey(): String = session

        override fun account(): String = "user-a"

        override fun onForceUpdate() {
            forcedCalls += 1
        }
    }

    private class Rig {
        val api = FakeApi()
        val source = Source()
        val state = State()
        val waits = mutableListOf<Long>()
        var expire = CompletableDeferred<Unit>()
        val reporter =
            PushTokenReporter(source, state, api, CoroutineScope(Dispatchers.Unconfined)) { ms ->
                waits += ms
                expire.await()
            }

        fun tokenOf(call: ApiCall): String =
            kotlinx.serialization.json.Json
                .parseToJsonElement(call.body.orEmpty())
                .jsonObject["token"]!!
                .jsonPrimitive.content
    }

    @Test
    fun fx08_push_42901_defers_new_triggers_and_catches_up_once_with_the_current_session_and_token() {
        val rig = Rig()
        rig.api.results += failure(ResultSignals.RATE_LIMITED, ErrorEffectKind.DISABLE_BUTTON, disable = 3_000L)
        rig.reporter.coldStart()
        assertEquals(1, rig.api.sent.size)
        assertTrue("42901：禁用期开始", rig.reporter.rateLimited)
        assertEquals("按 Retry-After 计时", listOf(3_000L), rig.waits)
        // 禁用期内的新触发：不发，只记待报
        rig.source.change("tok-2")
        rig.source.change("tok-3")
        assertEquals("禁用期内新触发不发", 1, rig.api.sent.size)
        assertTrue(rig.reporter.pending)
        // 到期：按当时的会话与令牌值补发一次
        rig.expire.complete(Unit)
        assertEquals("到期后补发一次", 2, rig.api.sent.size)
        assertEquals("用当时的令牌值", "tok-3", rig.tokenOf(rig.api.sent.last()))
        assertFalse(rig.reporter.rateLimited)
        assertEquals(0, rig.state.forcedCalls)
    }

    @Test
    fun fx08_push_42901_without_new_triggers_does_not_retry_the_failed_request() {
        val rig = Rig()
        rig.api.results += failure(ResultSignals.RATE_LIMITED, ErrorEffectKind.DISABLE_BUTTON, disable = 3_000L)
        rig.reporter.coldStart()
        rig.expire.complete(Unit)
        assertEquals("失败的旧请求不自动重试", 1, rig.api.sent.size)
        assertFalse(rig.reporter.rateLimited)
        // 之后的新触发照常发
        rig.source.change("tok-2")
        assertEquals(2, rig.api.sent.size)
    }

    @Test
    fun fx08_push_42901_gate_is_cleared_by_a_session_change() {
        val rig = Rig()
        rig.api.results += failure(ResultSignals.RATE_LIMITED, ErrorEffectKind.DISABLE_BUTTON, disable = 3_000L)
        rig.reporter.coldStart()
        rig.source.change("tok-2")
        assertEquals(1, rig.api.sent.size)
        // 换账号：禁用期与待报清掉；新登录照常发（不等旧禁用期）
        rig.state.session = "s2"
        rig.reporter.sessionChanged()
        assertFalse(rig.reporter.rateLimited)
        rig.reporter.loginSucceeded()
        assertEquals(2, rig.api.sent.size)
        // 旧禁用期的到期不再补发
        rig.expire.complete(Unit)
        assertEquals(2, rig.api.sent.size)
    }

    @Test
    fun fx08_push_non_forced_10405_is_an_ordinary_failure() {
        val rig = Rig()
        rig.api.results += refreshOnly
        rig.reporter.coldStart()
        assertEquals("非强更的 10405 不交强更分流", 0, rig.state.forcedCalls)
        val forcedRig = Rig()
        forcedRig.api.results += forced
        forcedRig.reporter.coldStart()
        assertEquals(1, forcedRig.state.forcedCalls)
    }
}
