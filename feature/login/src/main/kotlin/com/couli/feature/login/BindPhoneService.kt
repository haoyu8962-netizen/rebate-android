// 错误码、秒数是契约 / 业务规则取值（error-codes、BR-ID-05），按值直接写
@file:Suppress("MagicNumber", "ReturnCount") // 本地校验、归属失效、各结果逐项提前返回

package com.couli.feature.login

import com.couli.core.network.ApiCall
import com.couli.core.network.ApiClient
import com.couli.core.network.ApiResult
import com.couli.core.network.CallOwnership
import com.couli.core.network.ErrorEffectKind
import com.couli.core.network.LocalRejectReason
import com.couli.core.network.ResultSignals
import com.couli.core.network.SessionTokens
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * 绑定的结果。[actionRaw]：[BindPhoneAction] 的 done / merge_confirm / retry_after / error，或 stale / busy / code_incomplete /
 * phone_invalid。[field]：要在旁边提示的字段（phone / code）；[maskedPhone] 并号确认里显示的手机号账号（脱敏）。
 */
data class BindOutcome(
    val actionRaw: String,
    val textKey: String? = null,
    val trace6: String = "",
    val disableMillis: Long = 0L,
    val field: String? = null,
    val maskedPhone: String = "",
    /** [OPEN_ROUTE] 时要经 Router 打开的页面（10007 stage=cooling → DeleteAccount，APP-05b 共性第 10 条）。 */
    val route: String? = null,
) {
    companion object {
        const val OPEN_ROUTE = "open_route"
        const val STALE = "stale"
        const val BUSY = "busy"
        const val CODE_INCOMPLETE = "code_incomplete"
        const val PHONE_INVALID = "phone_invalid"
    }
}

/** [BindPhoneService] 要 App 提供的出口。 */
interface BindPhoneHost {
    /**
     * 绑定成功、getMe 已刷新：守卫的会话状态变为已绑手机，绑定这一前置步骤完成（重置计时）并经 Router 守卫恢复待办
     * （Router.onPrerequisiteCompleted）。已过期的待办不会被续活。
     */
    fun phoneBound()

    /** 没有绑定就离开绑手机页：暂缓的待办经守卫恢复（只恢复、不重置计时；仍需手机号的保留，不再自动拉起绑手机页）。 */
    fun leftUnbound() = Unit
}

/**
 * 绑手机页的执行器（APP-05b；BR-ID-05、BR-ID-06；openapi bindPhone、sendSmsCode purpose=bind、getMe）：
 * - 发码：purpose=bind；与短信登录共用按规范化号码的截止时间（成功 60 秒、42901 Retry-After），同一号码截止前不再发出（APP-05a 共性
 *   第 5 条）；50401 按错误提示（trace6）。
 * - 绑定：手机号 + 6 位码。首次绑定不带 step-up（契约：step-up 只用于换号）。成功 → 刷新 getMe（phone_bound）→ 通知会话状态变化并
 *   恢复待办；30411 mergeable（带 merge_ticket）→ 并号确认（不自动并号，ticket 只在内存）；其余按 [BindPhoneAction]。
 * - 并号：取消 → 不发请求、不并号、不保存会话；确认 → 锁定契约没有并号接口（TODO(N-29) MERGE-SUCCESS），不伪造请求，给出明确的
 *   「暂不可用」结果。
 * - 归属：页面离开、换号时 [invalidate]，在途请求取消、迟到结果不改页面；发码与绑定在入口固定登录世代，首次派发与每次写出前都核对
 *   （另一账号的会话已保存 → 不发出，共性第 15 条）；绑定结果只记到发起时的那个会话。
 * 页面在主线程调用。
 */
@Suppress("TooManyFunctions")
class BindPhoneService(
    private val client: ApiClient,
    private val tokens: SessionTokens,
    private val login: LoginService,
    private val phoneBinding: PhoneBindingState,
    private val host: BindPhoneHost,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    private var serial = 0L
    private val owners = mutableListOf<CallOwnership>()
    private var sending = false
    private var binding = false

    /** 并号凭据（30411 mergeable 的 merge_ticket）：只在内存，取消、离页、换号时清除。 */
    private var mergeTicket: String? = null

    /** 离开页面、换号：在途请求作废并取消，并号凭据清除。 */
    fun invalidate() {
        serial += 1
        mergeTicket = null
        val cancelled = synchronized(owners) { owners.toList().also { owners.clear() } }
        cancelled.forEach(client::cancelOwned)
    }

    val busy: Boolean get() = sending || binding

    /** 有待确认的并号（30411 mergeable）。 */
    val mergePending: Boolean get() = mergeTicket != null

    /** 这个号码（任意写法）距可以再发码还有几秒；0 = 可以发（与登录页同一份截止时间）。 */
    fun resendRemainingSec(rawPhone: String): Int = gate(rawPhone, SmsCountdown.STATE_SENT)

    /** 这个号码（任意写法）成功发码后可以再发的真实截止时刻；没有在倒计时为 null。 */
    fun resendDeadline(rawPhone: String): Long? {
        val phone = PhoneNumber.normalize(rawPhone).normalized ?: return null
        val (until, kind) = login.sendGate(phone) ?: return null
        return until.takeIf { kind == SmsCountdown.STATE_SENT }
    }

    /** 这个号码的发码 42901 限流还剩几秒。 */
    fun sendLimitedRemainingSec(rawPhone: String): Int = gate(rawPhone, SmsCountdown.STATE_RATE_LIMITED)

    private fun gate(
        rawPhone: String,
        kind: String,
    ): Int {
        val phone = PhoneNumber.normalize(rawPhone).normalized ?: return 0
        val (until, gateKind) = login.sendGate(phone) ?: return 0
        return if (gateKind == kind) ceilSec(until - clock()) else 0
    }

    /** 绑手机发码（purpose=bind）。 */
    suspend fun sendCode(rawPhone: String): SendCodeOutcome {
        val phone =
            PhoneNumber.normalize(rawPhone).normalized
                ?: return SendCodeOutcome(FIELD_ERROR, 0, PHONE_INVALID_KEY, LoginErrorMapper.FIELD_PHONE, null)
        login.sendGate(phone)?.let { (until, kind) ->
            val remaining = ceilSec(until - clock())
            return if (kind == SmsCountdown.STATE_SENT) {
                SendCodeOutcome(LoginService.STATE_COUNTDOWN, remaining, null, null, phone)
            } else {
                SendCodeOutcome(SmsCountdown.STATE_RATE_LIMITED, remaining, RATE_LIMITED_KEY, null, phone)
            }
        }
        if (sending) return SendCodeOutcome(BindOutcome.BUSY, 0, null, null, null)
        val issued = serial
        // 共性第 15 条：入口固定登录世代；首次派发与每次写出前核对，换了账号就不发出
        val epoch = tokens.loginEpoch
        val body = JsonObject(mapOf("phone" to JsonPrimitive(phone), "purpose" to JsonPrimitive(PURPOSE_BIND))).toString()
        val owner = ownerFor(issued, epoch)
        sending = true
        val result =
            try {
                client.send(ApiCall(LoginErrorMapper.SEND_SMS_CODE, body = body, ownership = owner))
            } finally {
                sending = false
                release(owner)
            }
        return sent(result, phone, issued, epoch)
    }

    private fun sent(
        result: ApiResult,
        phone: String,
        issued: Long,
        epoch: Long,
    ): SendCodeOutcome {
        val stale = issued != serial || tokens.loginEpoch != epoch
        return when (result) {
            is ApiResult.Success -> {
                val countdown = SmsCountdown.fromMillis(0, 0L, result.data as? JsonObject ?: JsonObject(emptyMap()))
                // 已被服务端受理：不论页面是否还在，这个号码的截止时间都要记下（与登录页共用）
                login.recordSendGate(phone, clock() + countdown.countdownSec * MILLIS, SmsCountdown.STATE_SENT)
                if (stale) {
                    SendCodeOutcome(BindOutcome.STALE, 0, null, null, null)
                } else {
                    SendCodeOutcome(countdown.stateRaw, countdown.countdownSec, null, null, phone)
                }
            }

            is ApiResult.Failure -> {
                val countdown = SmsCountdown.fromMillis(result.code, result.effect.disableMillis ?: 0L, result.data)
                if (countdown.stateRaw == SmsCountdown.STATE_RATE_LIMITED) {
                    login.recordSendGate(phone, clock() + countdown.countdownSec * MILLIS, SmsCountdown.STATE_RATE_LIMITED)
                }
                if (stale) return SendCodeOutcome(BindOutcome.STALE, 0, null, null, null)
                // 共性第 10、16 条：注销冷静期（10007 stage=cooling）→ 保留导航动作，由页面经 Router 打开注销进度页；processing 只提示
                navigation(result)?.let { route ->
                    return SendCodeOutcome(BindOutcome.OPEN_ROUTE, 0, result.effect.textKey, null, phone, route = route)
                }
                val mapped = BindPhoneAction.ofEffect(result.effect)
                val field = result.effect.fields?.firstOrNull()
                SendCodeOutcome(
                    countdown.stateRaw,
                    countdown.countdownSec,
                    mapped.textKey,
                    field,
                    phone,
                    ResultSignals.trace6(result.traceId),
                )
            }

            else -> {
                if (stale || result.isStale()) {
                    SendCodeOutcome(BindOutcome.STALE, 0, null, null, null)
                } else {
                    SendCodeOutcome(NETWORK_ERROR, 0, LoginService.GENERIC_KEY, null, phone)
                }
            }
        }
    }

    /** 绑定（首次绑定，不带 step-up）。 */
    @Suppress("LongMethod", "CyclomaticComplexMethod") // 本地校验、发出、成功刷新、各错误分流按顺序写在一处
    suspend fun bind(
        rawPhone: String,
        code: String,
    ): BindOutcome {
        val phone =
            PhoneNumber.normalize(rawPhone).normalized
                ?: return BindOutcome(BindOutcome.PHONE_INVALID, PHONE_INVALID_KEY, field = LoginErrorMapper.FIELD_PHONE)
        if (!CodeInput.accept(code).complete) return BindOutcome(BindOutcome.CODE_INCOMPLETE, field = LoginErrorMapper.FIELD_CODE)
        if (binding) return BindOutcome(BindOutcome.BUSY)
        mergeTicket = null
        val issued = serial
        // 绑定结果只记到发起时的那个会话（另一账号的会话在等待中保存 → 不记到别人身上）
        // 共性第 15 条：首次派发与每次写出前都核对仍是这个会话（另一账号的会话已保存 → 不发出，不能用别人的令牌绑定）
        val epoch = tokens.loginEpoch
        val body = JsonObject(mapOf("phone" to JsonPrimitive(phone), "code" to JsonPrimitive(code))).toString()
        val owner = ownerFor(issued, epoch)
        binding = true
        val result =
            try {
                client.send(ApiCall(BindPhoneAction.BIND_OPERATION, body = body, summary = PhoneNumber.masked(phone), ownership = owner))
            } finally {
                binding = false
                release(owner)
            }
        if (result is ApiResult.Success) {
            // 绑定已在服务端完成：不论页面是否还在，这个会话已绑手机；推进更新世代，绑定前发出的 getMe 回包作废（共性第 12 条）
            if (tokens.loginEpoch == epoch) phoneBinding.bindSucceeded(epoch)
            refreshMe(epoch)
            if (issued != serial || tokens.loginEpoch != epoch) return BindOutcome(BindOutcome.STALE)
            host.phoneBound()
            return BindOutcome(BindPhoneAction.DONE)
        }
        if (issued != serial || tokens.loginEpoch != epoch) return BindOutcome(BindOutcome.STALE)
        // 共性第 10 条：注销冷静期（10007 stage=cooling）→ 经 Router 打开可撤回的注销进度页；processing 只提示（下面按错误文案）
        if (result is ApiResult.Failure) {
            navigation(result)?.let { route -> return BindOutcome(BindOutcome.OPEN_ROUTE, result.effect.textKey, route = route) }
        }
        return when (result) {
            is ApiResult.Failure -> {
                val mapped =
                    if (result.code == MERGEABLE_CODE) {
                        BindPhoneAction.ofCode(BindPhoneAction.BIND_OPERATION, result.code, result.data)
                    } else {
                        BindPhoneAction.ofEffect(result.effect)
                    }
                if (mapped.actionRaw == BindPhoneAction.MERGE_CONFIRM) {
                    mergeTicket = BindPhoneAction.mergeTicket(result.data)
                    BindOutcome(
                        mapped.actionRaw,
                        mapped.textKey,
                        ResultSignals.trace6(result.traceId),
                        maskedPhone = PhoneNumber.masked(phone),
                    )
                } else {
                    BindOutcome(
                        mapped.actionRaw,
                        mapped.textKey,
                        ResultSignals.trace6(result.traceId),
                        ResultSignals.disableMillis(result),
                        field = fieldOf(result.code, result.effect.fields),
                    )
                }
            }

            else -> {
                // 未决键未结、账号未知等：不发出，提示通用错误（TODO(N-29) BIND-PENDING）；作废、换代的不提示
                if (result.isStale()) BindOutcome(BindOutcome.STALE) else BindOutcome(BindPhoneAction.ERROR, LoginService.GENERIC_KEY)
            }
        }
    }

    /**
     * getMe（契约：绑定成功后再取一次 /v1/me）：按 phone_bound 更新，只以发起时的会话发出、只记到那个会话；发起时捕获更新世代，回来时
     * 世代变了（又一次绑定成功）不记（共性第 12 条）。
     */
    private suspend fun refreshMe(epoch: Long) {
        if (tokens.loginEpoch != epoch) return
        val generation = phoneBinding.generation()
        val owner = CallOwnership(client.inFlight.epochs()) { tokens.loginEpoch == epoch }
        val result = client.send(ApiCall(OAuthLoginService.GET_ME_OPERATION, ownership = owner))
        if (result !is ApiResult.Success || tokens.loginEpoch != epoch) return
        val bound = ((result.data as? JsonObject)?.get("phone_bound") as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        if (bound != null) phoneBinding.applyIfCurrent(epoch, generation, bound)
    }

    /** 失败里要经 Router 打开的页面（10007 stage=cooling → DeleteAccount）；没有为 null。 */
    private fun navigation(result: ApiResult.Failure): String? =
        result.effect.route?.takeIf { result.effect.kind == ErrorEffectKind.NAVIGATE }

    /**
     * 并号确认框的决定（BR-ID-06 OPS-04）：
     * - cancel：请求发出前取消，不并号、不保存会话，清除凭据；留在绑手机页（落点未规定，TODO(N-29) MERGE-CANCEL）。
     * - confirm：锁定契约（9cd1e6f）没有 POST /v1/auth/merge 的 operation 与成功 schema——不伪造接口、不发请求，返回 unavailable
     *   （页面提示「暂不可用」），凭据清除（TODO(N-29) MERGE-SUCCESS）。
     */
    fun decideMerge(decision: String): MergeResult {
        val hadTicket = mergeTicket != null
        mergeTicket = null
        return if (decision == MergeDecision.CONFIRM && hadTicket) {
            MergeDecision.apply(MergeDecision.CONFIRM, null)
        } else {
            MergeDecision.apply(MergeDecision.CANCEL, null)
        }
    }

    /** 离开绑手机页（没有绑定）：交给 App 恢复暂缓的待办。 */
    fun leftUnbound() = host.leftUnbound()

    /** 当前会话是否已知已绑手机（进页时查清已绑就不必再绑）。 */
    fun knownBound(): Boolean = phoneBinding.get(tokens.loginEpoch) == true

    /** 请求归属：页面序号没变（没离页、没换号）且仍是入口时的会话（登录世代 [epoch]，共性第 15 条）。 */
    private fun ownerFor(
        issued: Long,
        epoch: Long,
    ): CallOwnership =
        CallOwnership(client.inFlight.epochs()) { serial == issued && tokens.loginEpoch == epoch }
            .also { synchronized(owners) { owners += it } }

    private fun release(owner: CallOwnership) {
        synchronized(owners) { owners.remove(owner) }
    }

    private fun ApiResult.isStale(): Boolean =
        when (this) {
            is ApiResult.LocalReject -> reason in STALE_REASONS
            is ApiResult.TransportFailure -> kind == KIND_CANCELLED
            is ApiResult.SessionNotSaved -> true
            else -> false
        }

    companion object {
        const val PURPOSE_BIND = "bind"
        const val MERGE_UNAVAILABLE_KEY = "bind_phone.merge.unavailable"
        const val PHONE_INVALID_KEY = "error.20001.phone_invalid"
        const val FIELD_ERROR = "field_error"
        const val NETWORK_ERROR = "network_error"
        private const val RATE_LIMITED_KEY = "error.42901"
        private const val MERGEABLE_CODE = 30411
        private const val MILLIS = 1000L
        private const val KIND_CANCELLED = "cancelled"
        private val STALE_REASONS = setOf(LocalRejectReason.CALL_CANCELLED, LocalRejectReason.SESSION_CHANGED)

        /** 字段错误落在哪个输入框（20001 手机号；20002 / 20003 验证码，03 §10.5）。 */
        fun fieldOf(
            code: Int,
            fields: List<String>?,
        ): String? =
            when (code) {
                20002, 20003 -> LoginErrorMapper.FIELD_CODE
                20001 -> fields?.firstOrNull() ?: LoginErrorMapper.FIELD_PHONE
                else -> null
            }

        private fun ceilSec(millis: Long): Int = if (millis <= 0) 0 else ((millis + MILLIS - 1) / MILLIS).toInt()
    }
}
