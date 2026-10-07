// 错误码、秒数是契约 / 业务规则取值（error-codes、BR-ID-05），按值直接写
@file:Suppress("MagicNumber", "ReturnCount") // 本地校验、归属失效、各结果逐项提前返回

package com.couli.feature.login

import com.couli.core.network.ApiCall
import com.couli.core.network.ApiClient
import com.couli.core.network.ApiResult
import com.couli.core.network.CallOwnership
import com.couli.core.network.LocalRejectReason
import com.couli.core.network.RestrictedRecheck
import com.couli.core.network.SessionEnd
import com.couli.core.network.SessionScopeRaw
import com.couli.core.network.SessionTokens
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** 登录执行器要 App 提供的出口（生产实现是 App 的 AppLogin，接 Router、导航栈与强更状态；集成测试用同一份接口）。 */
interface LoginHost {
    /** 强更状态（[AfterLoginUpdate]：supported / below / unknown / checking），取这一刻的值。 */
    fun updateState(): String

    /** 本机有没有持久化的 pending_action。 */
    fun hasPendingAction(): Boolean

    /** 关闭登录页，回到发起页（恢复 pending_action、进强更页之前都先出栈：原意图不压在登录页上，受限登录也不停在验证码页）。 */
    fun closeLogin()

    /** 交给 Router 守卫恢复 pending_action（Router.onPrerequisiteCompleted：重新过一次守卫，执行一次后删除）。 */
    fun resumePending()

    /** 删除 pending_action，不执行。 */
    fun discardPending()

    /** 进入强更页（网络层已按受限会话进强更时这里是幂等的确认）。执行器先 [closeLogin] 再调它。 */
    fun enterForceUpdate()

    /** 会话建立或结束（登录成功、登出）：守卫的会话状态、各模块据此刷新；Router 随之按当前状态处理满足条件的 pending_action。 */
    fun sessionChanged()

    /**
     * 只同步会话状态，不恢复 pending_action（APP-05a 共性第 10 条）：已作废的登录尝试（用户已取消、页面已出栈）在会话确已保存或被
     * 清除时用——守卫的会话状态、强更页照常同步，待办留给有效的流程处理。
     */
    fun sessionSynced()

    /** 登出或原会话被清除：Router 清 pending_action、作废推送回查（Router.onSessionEnded）。 */
    fun sessionEnded()

    /**
     * 这次登录尝试有效地完成了（会话已保存、尝试未作废），在关闭登录页之前调用（APP-04 共性第 66 条：等待中的桥调用 auth.login 据此
     * 以 logged_in = true 结算，并绑定这次登录保存的登录世代 [loginEpoch]）。缺省什么都不做。
     */
    fun loginCompleted(loginEpoch: Long) = Unit
}

/**
 * 发码结果。[stateRaw]：code_sent / rate_limited / field_error / … / stale（页面已换号或离开，忽略）/ busy（已有在途请求）/
 * countdown_active（这个号码上次成功发码的 60 秒还没到：不再发出，页面直接进验证码步继续输入已收到的码，共性第 5 条）。
 * [phone] 规范化后的号码；[trace6] 外壳 trace_id 后 6 位（通用错误态附注，BR-TEXT-14）。
 */
data class SendCodeOutcome(
    val stateRaw: String,
    val countdownSec: Int,
    val textKey: String?,
    val field: String?,
    val phone: String?,
    val trace6: String = "",
)

/**
 * 手机号页【继续输入验证码】入口（共性第 5、12、16 条）：与【获取验证码】（重新发码，[LoginService.sendCode]）分开。这个号码有仍在
 * 有效期内、或可能已送达（发码结果不确定，如 50401）的验证码时为 [CONTINUE]，不论此刻能不能重发；否则为 [SEND]（没有可继续的码）。
 */
data class PhoneEntry(
    val kind: String,
    val phone: String?,
    /** 进入验证码步后的重发倒计时：成功发码的 60 秒还没到时为剩余秒数；其余为 0（重新获取另按发码限流禁用）。 */
    val countdownSec: Int,
) {
    companion object {
        const val CONTINUE = "continue_code"
        const val SEND = "send"
    }
}

/**
 * 登录页两个入口各自的 42901 禁用截止时刻（共性第 11 条，03 §4.2「按 Retry-After 禁用触发的按钮」）：发码收到 42901 只禁用发码入口，
 * 仍有效的验证码照样可以提交；登录收到 42901 才禁用登录按钮。两个截止时刻分开存，页面用 now 现算。
 */
class LoginRateLimits {
    private var sendUntil = 0L
    private var loginUntil = 0L

    fun limitSend(
        nowMillis: Long,
        millis: Long,
    ) {
        sendUntil = maxOf(sendUntil, nowMillis + millis.coerceAtLeast(0L))
    }

    fun limitLogin(
        nowMillis: Long,
        millis: Long,
    ) {
        loginUntil = maxOf(loginUntil, nowMillis + millis.coerceAtLeast(0L))
    }

    fun sendBlocked(nowMillis: Long): Boolean = nowMillis < sendUntil

    fun loginBlocked(nowMillis: Long): Boolean = nowMillis < loginUntil

    fun sendRemainingSec(nowMillis: Long): Int = ceilSeconds(sendUntil - nowMillis)

    fun loginRemainingSec(nowMillis: Long): Int = ceilSeconds(loginUntil - nowMillis)

    private fun ceilSeconds(millis: Long): Int = if (millis <= 0) 0 else ((millis + 999) / 1000).toInt()
}

/**
 * 登录结果。[stateRaw]：logged_in / [LoginErrorResult.stateRaw] 的取值 / stale / busy / agreement_required / code_incomplete。
 * [actions] 执行了的登录后计划；[inviteResult] invite_bind.result（受限登录或没有为 null）；[inviteCode] invite_bind.code。
 */
data class LoginOutcome(
    val stateRaw: String,
    val textKey: String? = null,
    val field: String? = null,
    val disableMillis: Long = 0L,
    val actions: List<String> = emptyList(),
    val inviteResult: String? = null,
    val inviteCode: Int? = null,
    val trace6: String = "",
) {
    companion object {
        const val LOGGED_IN = "logged_in"
        const val STALE = "stale"
        const val BUSY = "busy"
        const val AGREEMENT_REQUIRED = "agreement_required"
        const val CODE_INCOMPLETE = "code_incomplete"
    }
}

/**
 * 短信登录的执行器（APP-05a；03 §4.2、§4.4；BR-ID-04、BR-ID-05、BR-ID-10；lessons：归属与世代、先持久化、等待后复核）：
 * - 发码：先按 normalize_phone 规范化，不合法本地给 20001 同一文案、不发出；同一号码在发码截止时间前不再发出（共性第 5 条）；
 *   成功按 60 秒倒计时，42901 按 Retry-After；
 * - 登录：手机号、6 位码、协议勾选时刻齐全才发；请求体带包内当前协议版本与勾选 / 确认时刻；受限登录不带邀请码；ApiClient 保存会话
 *   成功才算登录成功（session_save_failed / invalid_session_tokens 不算），之后按 [AfterLogin.plan] 依次执行；
 * - 归属（共性第 1 条）：发码与登录请求都带本次尝试的归属（[CallOwnership]）。换号、取消、离开页面、登出时 [invalidateAttempt]
 *   在同一把锁里推进尝试序号，并同步取消这次尝试已交给传输层的请求（[ApiClient.cancelOwned]）。ApiClient 在同一把锁里核对归属后才
 *   保存会话：迟到的旧登录响应不保存会话、不通知 Router、不恢复 pending_action；
 * - 登出：带当前会话调 logout（不论成败），回来时登录世代没变就清本机会话；世代变了要区分「已换成新会话」（不清，只通知会话变化）和
 *   「原会话已被清除」（10404、刷新失败清会话：照样完成 Router.sessionEnded 等清理，共性第 3 条）。
 * 页面在主线程调用；网络在 IO 线程。
 */
@Suppress("TooManyFunctions", "LongParameterList") // 发码、登录、登出与各自的本地判定集中在一处
class LoginService(
    private val client: ApiClient,
    private val tokens: SessionTokens,
    private val host: LoginHost,
    private val legal: LegalVersions = LegalVersions(1, 1),
    /**
     * 会话级作用域（共性第 13 条）：登出及其收尾在这里执行，不随设置页等页面的生命周期取消。App 给主线程的 App 级作用域（收尾要通知
     * Router）；缺省是独立的后台作用域（单测）。
     */
    private val sessionScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** 某个号码的发码截止时刻与种类（code_sent：成功发码后的重发倒计时；rate_limited：42901 的 Retry-After）。 */
    private data class SendDeadline(
        val untilMillis: Long,
        val kind: String,
    )

    /** 发码截止时间，按规范化后的手机号（共性第 5 条）：所有发码入口都经 [sendCode]；随执行器存活（App 内一个），关页再进照样生效。 */
    private val deadlines = mutableMapOf<String, SendDeadline>()

    /**
     * 已发出、或可能已送达的验证码的有效期截止时刻（按规范化号码，共性第 12、16 条）：发码成功，或发码结果不确定（50401 依赖超时等，
     * 短信可能已送达）时记下；登录成功（码已用掉）或服务端回 code_expired 时删除。码是否有效最终由服务端判定。
     */
    private val issuedCodes = mutableMapOf<String, Long>()

    /** 尝试序号：换号、离开页面、登出时推进；旧请求的响应据此作废。只在 [attemptLock] 下写。 */
    @Volatile
    private var serial = 0L

    /** 作废尝试与 ApiClient 保存会话共用的同步区（[CallOwnership.commitLock]）。 */
    private val attemptLock = Any()

    /** 本次尝试已发出请求的归属（[invalidateAttempt] 时据此取消传输）。 */
    private val owners = mutableListOf<CallOwnership>()
    private var sending = false
    private var loggingIn = false

    @Volatile
    private var loggingOut = false

    /** 正在进行的登出（会话作用域里；重复点击共用同一次）。只在调用方线程（主线程）读写。 */
    private var logoutJob: Deferred<Boolean>? = null

    /** 正由登录流程处理的会话的登录世代（保存之后、登录后计划执行完之前；共性第 14 条，App 的会话通知据此不重复恢复待办）。 */
    @Volatile
    private var flowEpoch: Long? = null

    /**
     * 登录世代为 [loginEpoch] 的会话此刻归登录流程管（保存之后、登录后计划执行完之前，含登录触发的受限会话复查）：这期间到达的会话
     * 状态通知（复查刷新成 full 等）只同步状态，不另行恢复待办——有效的流程自己交 Router 恢复，被取消的复查不恢复（共性第 10、14、19 条）。
     * 网络层的通知在复查返回之前就已排进主线程，所以复查触发的通知一定在流程结束前处理。流程结束后，同一会话由冷启动或版本恢复触发的
     * 刷新（触发来源不是登录尝试）照常经守卫恢复待办。
     */
    fun ownsSession(loginEpoch: Long): Boolean = loginEpoch == flowEpoch

    val busy: Boolean get() = sending || loggingIn

    /** 正在登出（网络层这期间清会话的跳登录 Effect 由 App 忽略：登出本来就回到游客）。 */
    val loggingOutNow: Boolean get() = loggingOut

    /**
     * 换了手机号、取消、离开登录页：在途的发码与登录结果不再改页面、不保存会话；已交给传输层的请求同步取消（归属核对此时已返回 false，
     * 之后的重试、续发也由 ApiClient 的归属核对结束）。
     */
    fun invalidateAttempt() {
        synchronized(attemptLock) { serial += 1 }
        val cancelled = synchronized(owners) { owners.toList().also { owners.clear() } }
        cancelled.forEach(client::cancelOwned)
    }

    /** 这个号码（任意写法）距可以再发码还有几秒；0 = 可以发。 */
    fun resendRemainingSec(rawPhone: String): Int {
        val phone = PhoneNumber.normalize(rawPhone).normalized ?: return 0
        return active(phone)?.let { ceilSec(it.untilMillis - clock()) } ?: 0
    }

    /**
     * 这个号码（任意写法）的发码 42901 限流还剩几秒；0 = 没有限流（共性第 21 条：页面的「获取验证码是否禁用」按规范化号码读这里，
     * 换号即切换，换回旧号码时旧限制仍然生效）。
     */
    fun sendLimitedRemainingSec(rawPhone: String): Int {
        val phone = PhoneNumber.normalize(rawPhone).normalized ?: return 0
        val gate = active(phone)?.takeIf { it.kind == SmsCountdown.STATE_RATE_LIMITED } ?: return 0
        return ceilSec(gate.untilMillis - clock())
    }

    /** 这个号码（任意写法）已收到的验证码还有几秒有效；没有或已过期为 0。 */
    fun issuedCodeRemainingSec(rawPhone: String): Int {
        val phone = PhoneNumber.normalize(rawPhone).normalized ?: return 0
        val until = issuedCodes[phone] ?: return 0
        val now = clock()
        if (now >= until) {
            issuedCodes.remove(phone)
            return 0
        }
        return ceilSec(until - now)
    }

    /**
     * 手机号页【继续输入验证码】（共性第 5、12、16 条）：只看这个号码有没有仍在有效期内（含结果不确定、可能已送达）的码，不以「此刻能否
     * 重发」为条件——发码倒计时中、42901 限流中、已可重发时都一样可以回验证码步，不发码、不作废旧码。没有可继续的码为 [PhoneEntry.SEND]。
     */
    fun continueEntry(rawPhone: String): PhoneEntry {
        val phone = PhoneNumber.normalize(rawPhone).normalized
        if (phone == null || issuedCodeRemainingSec(phone) <= 0) return PhoneEntry(PhoneEntry.SEND, null, 0)
        val gate = active(phone)
        val countdown = if (gate?.kind == SmsCountdown.STATE_SENT) ceilSec(gate.untilMillis - clock()) else 0
        return PhoneEntry(PhoneEntry.CONTINUE, phone, countdown)
    }

    suspend fun sendCode(rawPhone: String): SendCodeOutcome {
        val phone =
            PhoneNumber.normalize(rawPhone).normalized ?: run {
                // 与服务端 20001 phone_invalid 同一文案，不发短信、不计频控（BR-ID-05）
                val local = LoginErrorMapper.map(LoginErrorMapper.SEND_SMS_CODE, 20001, phoneInvalidData())
                return SendCodeOutcome(local.stateRaw, 0, local.textKey, LoginErrorMapper.FIELD_PHONE, null)
            }
        active(phone)?.let { gate ->
            // 同一号码截止前不再发出：成功发码的倒计时内 → 页面直接进验证码步用已收到的码；42901 的禁用期内 → 照旧禁用
            val remaining = ceilSec(gate.untilMillis - clock())
            return if (gate.kind == SmsCountdown.STATE_SENT) {
                SendCodeOutcome(STATE_COUNTDOWN, remaining, null, null, phone)
            } else {
                SendCodeOutcome(SmsCountdown.STATE_RATE_LIMITED, remaining, RATE_LIMITED_KEY, null, phone)
            }
        }
        if (sending) return SendCodeOutcome(LoginOutcome.BUSY, 0, null, null, null)
        val issued = serial
        val body = JsonObject(mapOf("phone" to JsonPrimitive(phone), "purpose" to JsonPrimitive("login"))).toString()
        val owner = ownerFor(issued)
        sending = true
        val result =
            try {
                client.send(ApiCall(LoginErrorMapper.SEND_SMS_CODE, body = body, ownership = owner))
            } finally {
                sending = false
                release(owner)
            }
        return sent(result, phone, issued)
    }

    private fun sent(
        result: ApiResult,
        phone: String,
        issued: Long,
    ): SendCodeOutcome {
        val stale = issued != serial
        when (result) {
            is ApiResult.Success -> {
                val data = result.data as? JsonObject ?: JsonObject(emptyMap())
                val countdown = SmsCountdown.fromMillis(0, 0L, data)
                // 发码已被服务端受理：不论页面是否还在，这个号码的截止时间都要记下（换号再换回来也不能绕过）
                deadlines[phone] = SendDeadline(clock() + countdown.countdownSec * MILLIS, SmsCountdown.STATE_SENT)
                issuedCodes[phone] = clock() + codeValiditySec(data) * MILLIS
                if (stale) return staleSend()
                return SendCodeOutcome(countdown.stateRaw, countdown.countdownSec, null, null, phone)
            }

            is ApiResult.Failure -> {
                val mapped = LoginErrorMapper.ofEffect(LoginErrorMapper.SEND_SMS_CODE, result.code, result.effect)
                val countdown = SmsCountdown.fromMillis(result.code, result.effect.disableMillis ?: 0L, result.data)
                if (countdown.stateRaw == SmsCountdown.STATE_RATE_LIMITED) {
                    deadlines[phone] = SendDeadline(clock() + countdown.countdownSec * MILLIS, SmsCountdown.STATE_RATE_LIMITED)
                }
                if (result.code in UNCERTAIN_CODES) mayHaveSent(phone)
                if (stale) return staleSend()
                return SendCodeOutcome(
                    countdown.stateRaw,
                    countdown.countdownSec,
                    mapped.textKey,
                    mapped.field,
                    phone,
                    trace6(result.traceId),
                )
            }

            else -> {
                // 共性第 16、22 条：分清「请求还没写出」（LocalReject：核对不放行、写出前被作废，服务端没收到）与「已写出但结果未知」
                // （TransportFailure：超时、断连、没有信封，以及写出之后因换号、离开页面被取消的 cancelled）。后者短信可能已送达：
                // 不论这次尝试是否已作废，都按号码记下可以继续输入的期限（换回这个号码时仍可输入实际收到的码）；旧请求不改当前页面
                if (writtenWithoutResult(result)) mayHaveSent(phone)
                if (stale) return staleSend()
                val failed = transportFailure(result)
                return SendCodeOutcome(failed.stateRaw, 0, failed.textKey, null, phone, failed.trace6)
            }
        }
    }

    /**
     * 发码请求已写出、没拿到确定结果（共性第 22 条）：传输层只在请求已交给连接（网络拦截器放行之后）才给 [ApiResult.TransportFailure]，
     * 写出之前结束的一律是 [ApiResult.LocalReject]（不计入）。
     */
    private fun writtenWithoutResult(result: ApiResult): Boolean = result is ApiResult.TransportFailure

    /** 发码结果不确定（共性第 16 条）：按验证码有效期缺省值记下「可能已送达」，已有更晚的截止时刻不缩短。 */
    private fun mayHaveSent(phone: String) {
        val until = clock() + CODE_VALIDITY_SEC * MILLIS
        issuedCodes[phone] = maxOf(issuedCodes[phone] ?: 0L, until)
    }

    /**
     * 短信登录。[consentAtMillis]：勾选或确认框同意的时刻（[AgreementState.consentAtMillis]）；[restricted]：从强更页进入的受限登录
     * （APP-02c 已有的来源标记，不进路由参数，rule-gaps ROUTE-01）。
     */
    @Suppress("ReturnCount")
    suspend fun login(
        rawPhone: String,
        code: String,
        consentAtMillis: Long?,
        inviteRaw: String,
        restricted: Boolean,
    ): LoginOutcome {
        val phone =
            PhoneNumber.normalize(rawPhone).normalized ?: run {
                val local = LoginErrorMapper.map(LoginErrorMapper.LOGIN_BY_SMS, 20001, phoneInvalidData())
                return LoginOutcome(local.stateRaw, local.textKey, LoginErrorMapper.FIELD_PHONE)
            }
        if (!CodeInput.accept(code).complete) return LoginOutcome(LoginOutcome.CODE_INCOMPLETE, field = LoginErrorMapper.FIELD_CODE)
        // 未勾选协议不能登录（BR-ID-04）：页面先弹确认框
        if (consentAtMillis == null || consentAtMillis <= 0L) return LoginOutcome(LoginOutcome.AGREEMENT_REQUIRED)
        if (loggingIn) return LoginOutcome(LoginOutcome.BUSY)
        val issued = serial
        val fields =
            mutableMapOf(
                "phone" to JsonPrimitive(phone),
                "code" to JsonPrimitive(code),
                "legal_versions" to
                    JsonObject(mapOf("privacy" to JsonPrimitive(legal.privacy), "agreement" to JsonPrimitive(legal.agreement))),
                "consent_at" to JsonPrimitive(Instant.ofEpochMilli(consentAtMillis).toString()),
            )
        InviteField.wireValue(inviteRaw, restricted)?.let { fields["invite_code"] = JsonPrimitive(it) }
        // 共性第 1 条：归属。作废后 ApiClient 在保存令牌之前就结束这次调用（call_cancelled），旧响应不保存会话
        val owner = ownerFor(issued)
        loggingIn = true
        val result =
            try {
                client.send(ApiCall(LoginErrorMapper.LOGIN_BY_SMS, body = JsonObject(fields).toString(), ownership = owner))
            } finally {
                loggingIn = false
                release(owner)
            }
        if (result is ApiResult.Success) {
            // 码已用掉：手机号页不再提供「继续输入」
            issuedCodes.remove(phone)
            return loggedIn(result, issued, restricted)
        }
        if (issued != serial) return LoginOutcome(LoginOutcome.STALE)
        if (result is ApiResult.Failure) {
            val mapped = LoginErrorMapper.ofEffect(LoginErrorMapper.LOGIN_BY_SMS, result.code, result.effect)
            if (mapped.stateRaw == LoginErrorMapper.STATE_CODE_EXPIRED) issuedCodes.remove(phone)
            return LoginOutcome(mapped.stateRaw, mapped.textKey, mapped.field, mapped.disableMillis, trace6 = trace6(result.traceId))
        }
        return transportFailure(result)
    }

    /**
     * 登出（settings.logout；规划/01 F-ACC-05）：返回 true = 本机已回到游客。logout 请求失败（断网等）照样清本机会话
     * （TODO(N-26) INTEGRATION-02：服务端会话只能等过期，规则未写失败时是否提示）。
     *
     * 共性第 13 条：请求与收尾都在 [sessionScope] 里执行，调用方（设置页）离开、被重建而取消时只是不再等结果，登出照常完成，本机会话与
     * 待办照样按原会话世代清理并通知 Router。登出进行中再次调用共用同一次。
     */
    suspend fun logout(): Boolean {
        val running = logoutJob?.takeIf { it.isActive } ?: sessionScope.async { performLogout() }.also { logoutJob = it }
        return running.await()
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // logout 请求的任何失败（含作用域被取消）都照常收尾
    private suspend fun performLogout(): Boolean {
        invalidateAttempt()
        val epoch = tokens.loginEpoch
        loggingOut = true
        try {
            if (client.activeSessionScope() != null) client.send(ApiCall(LOGOUT))
        } catch (error: Exception) {
            // 请求异常或被取消：不影响本机清理（下面不再挂起）
        } finally {
            loggingOut = false
        }
        return finishLogout(epoch)
    }

    /**
     * 登出收尾（不挂起，取消也不会打断；共性第 3、13、23 条）：交给 ApiClient 在保存登录的同一把锁里核对原会话的登录世代并删除，
     * Router 的清理（[LoginHost.sessionEnded]）作为绑定原会话的收尾在同一把锁里执行——核对与删除之间插进来的新登录要么先保存
     * （这里按「已换成新会话」不清、不清待办），要么因世代已变而保存失败，不会出现「核对通过、新会话保存、随即被删」。
     */
    private fun finishLogout(epoch: Long): Boolean {
        val ended = client.clearSessionIf(epoch) { host.sessionEnded() }
        // 已换成新会话（新登录）：不清新会话，只通知会话变化
        host.sessionChanged()
        return ended != SessionEnd.REPLACED
    }

    /**
     * 登录响应已保存会话之后的流程。这一代会话在流程期间归登录流程管（[ownsSession]）：App 的会话状态通知只同步、不另行恢复待办；
     * 流程结束（有效或作废）后交还：之后冷启动、版本恢复触发的受限会话刷新成 full 由通知经守卫恢复（共性第 14、19 条）。
     */
    private suspend fun loggedIn(
        result: ApiResult.Success,
        issued: Long,
        restricted: Boolean,
    ): LoginOutcome {
        val epoch = tokens.loginEpoch
        flowEpoch = epoch
        try {
            return runLoggedIn(result, issued, restricted)
        } finally {
            if (flowEpoch == epoch) flowEpoch = null
        }
    }

    private suspend fun runLoggedIn(
        result: ApiResult.Success,
        issued: Long,
        restricted: Boolean,
    ): LoginOutcome {
        // ApiClient 已核对归属并保存成功（作废的尝试在保存前就以 call_cancelled 结束）；再确认本机确有会话
        val scope = client.activeSessionScope() ?: return LoginOutcome(LoginErrorMapper.STATE_SERVICE_ERROR, GENERIC_KEY)
        if (issued != serial) {
            // 保存之后、回到这里之前被作废：会话确已建立，只同步会话状态，不关页、不恢复待办（共性第 10 条）
            host.sessionSynced()
            return LoginOutcome(LoginOutcome.STALE)
        }
        val data = result.data as? JsonObject ?: JsonObject(emptyMap())
        val isNew = (data["is_new_user"] as? JsonPrimitive)?.booleanOrNull == true
        var actions = AfterLogin.plan(scope, isNew, host.updateState(), host.hasPendingAction())
        if (AfterLoginAction.REFRESH_RESTRICTED in actions) {
            actions = recheckRestricted(issued, isNew) ?: return recheckEnded(issued)
        }
        execute(actions)
        val bind = (data["invite_bind"] as? JsonObject)?.takeUnless { restricted }
        val inviteResult = (bind?.get("result") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val inviteCode = (bind?.get("code") as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        return LoginOutcome(LoginOutcome.LOGGED_IN, actions = actions, inviteResult = inviteResult, inviteCode = inviteCode)
    }

    /**
     * 共性第 4、10 条：deletion_only 与 supported 并存——结论未定，先不动待办。带本次尝试的归属显式复查受限会话（单飞刷新），再按最新的
     * 作用域与强更结论重新计划；仍是 deletion_only 且 supported 时输出 hold_pending（待办保留，交给 Router 随强更结论决定）。
     * 复查没有完成（取消、会话已清或换代、暂时失败）返回 null，由 [recheckEnded] 收尾。
     */
    private suspend fun recheckRestricted(
        issued: Long,
        isNew: Boolean,
    ): List<String>? {
        val owner = ownerFor(issued)
        recheck =
            try {
                withContext(Dispatchers.IO) { client.refreshRestrictedSession(owner) }
            } finally {
                release(owner)
            }
        if (issued != serial || recheck != RestrictedRecheck.DONE) return null
        val scope = client.activeSessionScope() ?: return null
        val again = AfterLogin.plan(scope, isNew, host.updateState(), host.hasPendingAction())
        val replanned = again.filter { it != AfterLoginAction.REFRESH_RESTRICTED && it != AfterLoginAction.SAVE_SESSION }.toMutableList()
        if (AfterLoginAction.REFRESH_RESTRICTED in again) replanned += AfterLoginAction.HOLD_PENDING
        return listOf(AfterLoginAction.SAVE_SESSION, AfterLoginAction.REFRESH_RESTRICTED) + replanned
    }

    private var recheck: RestrictedRecheck? = null

    /** 受限会话复查没有完成：已作废的只同步会话状态（不恢复、不消费待办）；会话被清的回 session_expired；暂时失败保留会话与待办。 */
    private fun recheckEnded(issued: Long): LoginOutcome {
        host.sessionSynced()
        if (issued != serial || recheck == RestrictedRecheck.CANCELLED) return LoginOutcome(LoginOutcome.STALE)
        return when {
            client.activeSessionScope() == null -> {
                LoginOutcome(LoginErrorMapper.STATE_SESSION_EXPIRED, SESSION_EXPIRED_KEY)
            }

            recheck == RestrictedRecheck.TRANSIENT -> {
                // 受限会话还在、结论未定：保留待办，关闭登录页（受限会话下的页面由守卫按受限处理），不打开待办
                execute(listOf(AfterLoginAction.SAVE_SESSION, AfterLoginAction.REFRESH_RESTRICTED, AfterLoginAction.HOLD_PENDING))
                LoginOutcome(
                    LoginOutcome.LOGGED_IN,
                    actions = listOf(AfterLoginAction.SAVE_SESSION, AfterLoginAction.REFRESH_RESTRICTED, AfterLoginAction.HOLD_PENDING),
                )
            }

            else -> {
                LoginOutcome(LoginOutcome.STALE)
            }
        }
    }

    /**
     * 执行登录后计划（共性第 2 条）：先删要丢弃的待办（免得会话变化的路由同步把它执行掉）→ 明确移除登录页（含从强更页进入的受限登录）
     * → 通知会话变化 → 交 Router 恢复待办，或进入 / 同步强更页。
     */
    private fun execute(actions: List<String>) {
        // 先报告登录完成（关闭登录页之前：等待中的桥调用 auth.login 不会把这次关页当成「离开」）
        flowEpoch?.let(host::loginCompleted)
        if (AfterLoginAction.DISCARD_PENDING in actions) host.discardPending()
        host.closeLogin()
        host.sessionChanged()
        actions.forEach { action ->
            when (action) {
                AfterLoginAction.RESUME_PENDING -> host.resumePending()
                AfterLoginAction.ENTER_FORCE_UPDATE -> host.enterForceUpdate()
            }
        }
    }

    private fun ownerFor(issued: Long): CallOwnership =
        CallOwnership(client.inFlight.epochs(), commitLock = attemptLock) { serial == issued }
            .also { synchronized(owners) { owners += it } }

    private fun release(owner: CallOwnership) {
        synchronized(owners) { owners.remove(owner) }
    }

    private fun active(phone: String): SendDeadline? {
        val found = deadlines[phone] ?: return null
        if (clock() >= found.untilMillis) {
            deadlines.remove(phone)
            return null
        }
        return found
    }

    private fun staleSend() = SendCodeOutcome(LoginOutcome.STALE, 0, null, null, null)

    private fun transportFailure(result: ApiResult): LoginOutcome =
        when (result) {
            is ApiResult.LocalReject -> {
                // 隐私、环境、会话世代在等待中变了：本次作废，页面不提示；其余本地拒绝（设备未就绪等）按通用错误提示
                if (result.reason in STALE_REASONS) LoginOutcome(LoginOutcome.STALE) else LoginOutcome(SERVICE_ERROR, GENERIC_KEY)
            }

            // 登录响应缺令牌或令牌没能保存：不算登录成功（lessons 40、APP-03b 19）；作废、换代后到达的同样不算，页面不提示
            is ApiResult.SessionNotSaved -> {
                if (result.reason in STALE_REASONS) {
                    LoginOutcome(LoginOutcome.STALE)
                } else {
                    LoginOutcome(SERVICE_ERROR, GENERIC_KEY, trace6 = trace6(result.traceId))
                }
            }

            is ApiResult.TransportFailure -> {
                when (result.kind) {
                    KIND_CANCELLED -> LoginOutcome(LoginOutcome.STALE)
                    KIND_NO_ENVELOPE -> LoginOutcome(SERVICE_ERROR, GENERIC_KEY)
                    else -> LoginOutcome(LoginErrorMapper.STATE_NETWORK_ERROR, GENERIC_KEY)
                }
            }

            is ApiResult.SessionEnded -> {
                LoginOutcome(LoginErrorMapper.STATE_SESSION_EXPIRED, SESSION_EXPIRED_KEY)
            }

            else -> {
                LoginOutcome(SERVICE_ERROR, GENERIC_KEY)
            }
        }

    companion object {
        /** 发码入口的本地结果（三端对齐名）：倒计时还没结束，同一个号码不再发码。 */
        const val STATE_COUNTDOWN = "countdown_active"

        /** 受限会话复查后仍受限（三端对齐名，同 [AfterLoginAction.HOLD_PENDING]）。 */
        const val HOLD_PENDING = AfterLoginAction.HOLD_PENDING

        /** 验证码有效期缺省秒数（BR-ID-05）。 */
        const val CODE_VALIDITY_SEC = 300
        const val GENERIC_KEY = "error.50001"
        const val SESSION_EXPIRED_KEY = "error.10404"
        private const val RATE_LIMITED_KEY = "error.42901"
        private const val SERVICE_ERROR = LoginErrorMapper.STATE_SERVICE_ERROR
        private const val LOGOUT = "logout"

        /** [ApiResult.TransportFailure.kind] 的取值（core:network HttpTransport 的 KIND_*）。 */
        private const val KIND_CANCELLED = "cancelled"
        private const val KIND_NO_ENVELOPE = "no_envelope"
        private const val MILLIS = 1000L
        private const val TRACE_TAIL = 6
        private const val MAX_CODE_VALIDITY_SEC = 3600
        private val STALE_REASONS =
            setOf(LocalRejectReason.CALL_CANCELLED, LocalRejectReason.SESSION_CHANGED)

        /** 发码结果不确定的业务码（短信可能已送达，共性第 16 条）：50401 依赖超时、50001 服务端错误。 */
        private val UNCERTAIN_CODES = setOf(50401, 50001)

        private fun ceilSec(millis: Long): Int = if (millis <= 0) 0 else ((millis + MILLIS - 1) / MILLIS).toInt()

        private fun trace6(traceId: String?): String = traceId?.takeLast(TRACE_TAIL).orEmpty()

        /** 发码成功响应的 expires_in_sec（契约 SendSmsCodeData，BR-ID-05：300）；缺失或不合法按 300。 */
        private fun codeValiditySec(data: JsonObject): Int =
            (data["expires_in_sec"] as? JsonPrimitive)
                ?.takeIf { !it.isString }
                ?.intOrNull
                ?.takeIf { it in 1..MAX_CODE_VALIDITY_SEC } ?: CODE_VALIDITY_SEC

        private fun phoneInvalidData(): JsonObject =
            JsonObject(
                mapOf(
                    "fields" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(LoginErrorMapper.FIELD_PHONE))),
                    "reason" to JsonPrimitive(PhoneNumber.REASON_INVALID),
                ),
            )

        /** 受限会话作用域（守卫的会话状态用）。 */
        fun isRestricted(scope: String?): Boolean = scope == SessionScopeRaw.DELETION_ONLY
    }
}
