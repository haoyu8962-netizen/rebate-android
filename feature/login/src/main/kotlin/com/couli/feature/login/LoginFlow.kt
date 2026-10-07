// 错误码、长度与时长是契约 / 业务规则取值（error-codes、BR-ID-05、BR-INV-01），按值直接写
@file:Suppress("MagicNumber", "ReturnCount") // 按码逐项提前返回

package com.couli.feature.login

import com.couli.core.generated.ApiOperations
import com.couli.core.network.ErrorActionInput
import com.couli.core.network.ErrorActionMapper
import com.couli.core.network.ErrorActionState
import com.couli.core.network.ErrorEffect
import com.couli.core.network.ErrorEffectKind
import com.couli.core.network.SessionScopeRaw
import com.couli.core.network.UpdateRaw
import java.text.Normalizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/*
 * 短信登录主链路的纯逻辑（APP-05a；BR-ID-04、BR-ID-05、BR-ID-10、BR-INV-01/02/06/18、BR-TEXT-14；03 §4.2）。三端同名；
 * 向量 specs/login-vectors.json（LoginVectorTest）。没有界面、网络与计时器：执行器是 [LoginService]，页面是 LoginScreen。
 */

/** 手机号规范化结果。[reasonRaw] 与服务端 20001 data.reason 同名（phone_invalid）。 */
data class PhoneResult(
    val valid: Boolean,
    val normalized: String?,
    val reasonRaw: String?,
)

/**
 * 手机号规范化（BR-ID-05 细则「手机号规范化」，与服务端同一规格）：① NFKC；② 删除全部空白（含 U+3000、U+00A0）、零宽字符
 * （U+200B–U+200D、U+FEFF）与连字符（「-」及 U+2010–U+2015）；③ 去国家码：「+86」开头，或「0086」「86」开头且其后恰为 11 位
 * 数字；④ 必须匹配 ^1[3-9][0-9]{9}$。服务端永远自己再规范化一遍，客户端结果只用于提示与提交。
 */
object PhoneNumber {
    const val REASON_INVALID = "phone_invalid"
    private val STRIP = Regex("[\\s\\p{Z}\\u200B-\\u200D\\uFEFF\\-\\u2010-\\u2015]")
    private val PATTERN = Regex("1[3-9][0-9]{9}")
    private val ELEVEN_DIGITS = Regex("[0-9]{11}")

    fun normalize(raw: String): PhoneResult {
        var text = nfkc(raw).replace(STRIP, "")
        when {
            text.startsWith("+86") -> text = text.substring(3)
            text.startsWith("0086") && ELEVEN_DIGITS.matches(text.substring(4)) -> text = text.substring(4)
            text.startsWith("86") && ELEVEN_DIGITS.matches(text.substring(2)) -> text = text.substring(2)
        }
        return if (PATTERN.matches(text)) PhoneResult(true, text, null) else PhoneResult(false, null, REASON_INVALID)
    }

    /** 页面显示用的脱敏号码：138****5678（设计稿 LoginCode「验证码已发送至」）。 */
    fun masked(normalized: String): String =
        if (normalized.length == PHONE_LENGTH) normalized.take(3) + "****" + normalized.substring(7) else normalized

    internal fun nfkc(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFKC)

    private const val PHONE_LENGTH = 11
}

/**
 * 登录页的协议勾选（BR-ID-04：勾选框默认不勾；未勾选点登录 → 确认框；同意后记下点击时刻并继续）。[consentAtMillis] 是本机记下的
 * 勾选 / 确认时刻（登录请求的 consent_at），不是服务端已写入 consent_records。取消勾选清除记下的时刻：之后再登录必须重新确认
 * （TODO(N-26) AGREEMENT-01：历史时刻的去留未规定，保守清除）。[check] 用注入的时钟，[confirm] 用实参（毫秒）。
 */
class AgreementState(
    private val clock: () -> Long,
) {
    var checked: Boolean = false
        private set

    /** 确认框正在显示。 */
    var showConfirm: Boolean = false
        private set

    /** 最近一次点登录（或在确认框里同意）之后可以继续。 */
    var proceed: Boolean = false
        private set

    var consentAtMillis: Long? = null
        private set

    /** 点【获取验证码】或【登录】：已勾选直接继续；未勾选弹确认框。 */
    fun tapLogin() {
        if (checked && consentAtMillis != null) {
            showConfirm = false
            proceed = true
            return
        }
        showConfirm = true
        proceed = false
    }

    fun check() {
        checked = true
        consentAtMillis = clock()
        showConfirm = false
        proceed = false
    }

    fun uncheck() {
        checked = false
        consentAtMillis = null
        proceed = false
    }

    /** 确认框【同意并继续】：勾上并记下点击时刻。 */
    fun confirm(nowMillis: Long) {
        checked = true
        consentAtMillis = nowMillis
        showConfirm = false
        proceed = true
    }

    /** 确认框【取消】、关闭，或点了框里的协议链接（不算同意）：不勾选、不继续。 */
    fun cancel() {
        showConfirm = false
        proceed = false
    }

    /** 页面取走「继续」一次（同一次同意不触发两次请求）。 */
    fun consumeProceed(): Boolean = proceed.also { proceed = false }
}

/** 发码之后的倒计时与页面状态。[stateRaw]：code_sent / rate_limited / 其余同 [LoginErrorMapper] 的 stateRaw。 */
data class SmsCountdownResult(
    val countdownSec: Int,
    val canResend: Boolean,
    val stateRaw: String,
    val textKey: String?,
)

/**
 * 发码倒计时（BR-ID-05：60 秒 1 条；03 §4.2、BR-TEXT-14：42901 按 Retry-After 禁用触发按钮，缺省 5 秒——编排者 2026-10-07 更正，
 * 不取较大者）。Retry-After 的解析与 ErrorActionMapper 同一个函数（不另造）。
 */
object SmsCountdown {
    const val RESEND_SECONDS = 60
    const val STATE_SENT = "code_sent"
    const val STATE_RATE_LIMITED = "rate_limited"

    /** [code] 是业务码（成功为 0）。 */
    @Suppress("UnusedParameter") // 与 iOS / 鸿蒙同一签名；截止时刻由执行器按自己的时钟记
    fun after(
        code: Int,
        retryAfterHeader: String?,
        nowMillis: Long,
    ): SmsCountdownResult {
        val headers = retryAfterHeader?.let { mapOf(RETRY_AFTER to it) }.orEmpty()
        return fromMillis(code, ErrorActionMapper.retryAfterMillis(headers), JsonObject(emptyMap()))
    }

    /**
     * 执行器用（ApiResult 不带响应头，ErrorEffect.disableMillis 已是同一函数算出的禁用毫秒数）。成功时取 60 秒；data.resend_after_sec
     * 更大时按它（契约：至少 60）。TODO(N-26) SMS-01：小于 60、缺头、HTTP-date 等由 ErrorActionMapper 统一兜底（缺省 5 秒）。
     */
    fun fromMillis(
        code: Int,
        disableMillis: Long,
        data: JsonObject,
    ): SmsCountdownResult {
        if (code == 0) {
            val resend = (data["resend_after_sec"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            val seconds = resend?.takeIf { it in (RESEND_SECONDS + 1)..MAX_SECONDS } ?: RESEND_SECONDS
            return SmsCountdownResult(seconds, false, STATE_SENT, null)
        }
        val mapped = LoginErrorMapper.map(LoginErrorMapper.SEND_SMS_CODE, code, data)
        if (code == RATE_LIMITED) {
            val seconds = ((disableMillis + MILLIS - 1) / MILLIS).coerceIn(1L, MAX_SECONDS.toLong()).toInt()
            return SmsCountdownResult(seconds, false, STATE_RATE_LIMITED, mapped.textKey)
        }
        return SmsCountdownResult(0, true, mapped.stateRaw, mapped.textKey)
    }

    private const val RETRY_AFTER = "Retry-After"
    private const val RATE_LIMITED = 42901
    private const val MILLIS = 1000L

    /** 一天：Retry-After 与 resend_after_sec 的上限（与网络层 Retry-After 位数上限同量级）。 */
    private const val MAX_SECONDS = 86_400
}

/** 验证码输入的形状（只报告原字符串，不规定 UI 清洗，rule-gaps CODE-01）。 */
data class CodeShape(
    val complete: Boolean,
    val digitsOnly: Boolean,
)

/** 6 位短信验证码（BR-ID-05；openapi LoginBySmsRequest.code ^[0-9]{6}$）。 */
object CodeInput {
    const val LENGTH = 6

    fun accept(raw: String): CodeShape {
        val digits = raw.all { it in '0'..'9' }
        return CodeShape(complete = digits && raw.length == LENGTH, digitsOnly = digits)
    }

    /** 输入框的清洗：只留 ASCII 数字，最多 6 位（TODO(N-26) CODE-01：全角与粘贴策略未规定，保守只收 ASCII）。 */
    fun sanitize(raw: String): String = raw.filter { it in '0'..'9' }.take(LENGTH)
}

/**
 * 登录相关错误码在页面上的状态与文案。[stateRaw]：field_error / code_error / code_expired / rate_limited / risk_blocked /
 * registration_blocked（LoginCodeBlocked）/ force_update / deletion_processing（LoginDeletionProcessing）/ session_expired
 * （LoginSessionExpired）/ provider_unavailable / service_error / network_error / page_action / message / toast。
 * [field] 要在旁边提示的字段；[route] 网络层或页面要去的路由；[disableMillis] 42901 禁用触发按钮的毫秒数。
 */
data class LoginErrorResult(
    val stateRaw: String,
    val textKey: String?,
    val field: String?,
    val route: String?,
    val disableMillis: Long = 0L,
)

/** 登录相关错误码 → 页面状态（经 [ErrorActionMapper]，不另造码表）。 */
object LoginErrorMapper {
    const val SEND_SMS_CODE = "sendSmsCode"
    const val LOGIN_BY_SMS = "loginBySms"
    const val STATE_SERVICE_ERROR = "service_error"
    const val STATE_NETWORK_ERROR = "network_error"
    const val STATE_SESSION_EXPIRED = "session_expired"
    const val STATE_CODE_EXPIRED = "code_expired"
    const val STATE_REGISTRATION_BLOCKED = "registration_blocked"
    const val STATE_DELETION_PROCESSING = "deletion_processing"
    const val STATE_FORCE_UPDATE = "force_update"

    /**
     * [op] 是 operationId；[code] 是业务码；[data] 是外壳 data。登录页没有会话：按「受支持、正常作用域」映射（rule-gaps ERROR-01：
     * 10405 受限令牌、强更中 10404 由网络层按真实状态处理）。
     */
    fun map(
        op: String,
        code: Int,
        data: JsonObject,
        headers: Map<String, String> = emptyMap(),
    ): LoginErrorResult {
        val operation = ApiOperations.byId(op) ?: return LoginErrorResult("message", null, null, null)
        val effect =
            ErrorActionMapper.map(
                ErrorActionInput(
                    code = code,
                    data = data,
                    operation = operation,
                    headers = headers,
                    isReplay = false,
                    state = ErrorActionState(UpdateRaw.SUPPORTED, SessionScopeRaw.FULL, ""),
                ),
            )
        return ofEffect(op, code, effect)
    }

    /** 网络层已给出的 Effect（[com.couli.core.network.ApiResult.Failure.effect]）→ 页面状态。 */
    @Suppress("CyclomaticComplexMethod") // 按码与 Effect 种类逐项对照 BR-TEXT-14
    fun ofEffect(
        op: String,
        code: Int,
        effect: ErrorEffect,
    ): LoginErrorResult {
        val key = effect.textKey
        val route = effect.route
        when (code) {
            20002 -> return LoginErrorResult("code_error", key, FIELD_CODE, route)

            20003 -> return LoginErrorResult(STATE_CODE_EXPIRED, key, FIELD_CODE, route)

            // 发码被拦：只提示；登录（注册）被拦：LoginCodeBlocked 弹窗（不建号，验证码要重新获取）
            44001 -> return LoginErrorResult(if (op == LOGIN_BY_SMS) STATE_REGISTRATION_BLOCKED else "risk_blocked", key, null, route)

            10007 -> if (route == null) return LoginErrorResult(STATE_DELETION_PROCESSING, key, null, null)
        }
        return when (effect.kind) {
            ErrorEffectKind.FIELD_ERROR -> {
                LoginErrorResult("field_error", key, effect.fields?.firstOrNull(), route)
            }

            ErrorEffectKind.DISABLE_BUTTON -> {
                LoginErrorResult(SmsCountdown.STATE_RATE_LIMITED, key, null, route, effect.disableMillis ?: 0L)
            }

            ErrorEffectKind.FORCE_UPDATE -> {
                LoginErrorResult(STATE_FORCE_UPDATE, key, null, route)
            }

            ErrorEffectKind.CLEAR_SESSION_LOGIN, ErrorEffectKind.CLEAR_SESSION_STAY_FORCE_UPDATE -> {
                LoginErrorResult(STATE_SESSION_EXPIRED, key, null, route)
            }

            ErrorEffectKind.GENERIC_ERROR -> {
                LoginErrorResult(STATE_SERVICE_ERROR, key, null, route)
            }

            ErrorEffectKind.PAGE_ACTION -> {
                LoginErrorResult(if (code == 50305) "provider_unavailable" else "page_action", key, null, route)
            }

            ErrorEffectKind.FALLBACK_MESSAGE -> {
                LoginErrorResult("message", key, null, route)
            }

            else -> {
                LoginErrorResult("toast", key, null, route)
            }
        }
    }

    const val FIELD_PHONE = "phone"
    const val FIELD_CODE = "code"
}

/**
 * 邀请码输入框的判定。[valid] 只判注册页可接受的语法（空值可省略），不决定能否登录（rule-gaps INVITE-01）；[normalized] 规范化后的码；
 * [noticeKey] 输入框下方的告知句（BR-INV-18 细则），不显示输入框时为 null。
 */
data class InviteDecision(
    val showInviteField: Boolean,
    val valid: Boolean,
    val normalized: String,
    val noticeKey: String?,
)

/**
 * 注册页邀请码（BR-INV-01 字符集 23456789ABCDEFGHJKLMNPQRSTUVWXYZ、长度 6；BR-INV-02 规范化：NFKC → 删空白与零宽 → 大写，不做 O→0
 * 纠错、不删连字符）。受限登录（从强更页进入）不显示邀请码等业务输入（BR-ID-01 细则「受限登录」）。
 */
object InviteField {
    const val NOTICE_KEY = "invite.notice_inviter"

    /** openapi LoginBySmsRequest.invite_code maxLength。 */
    private const val MAX_WIRE_LENGTH = 32
    private val STRIP = Regex("[\\s\\p{Z}\\u200B-\\u200D\\uFEFF]")
    private val PATTERN = Regex("[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{6}")

    fun normalize(raw: String): String = PhoneNumber.nfkc(raw).replace(STRIP, "").uppercase()

    fun decide(
        raw: String,
        context: JsonObject,
    ): InviteDecision {
        val normalized = normalize(raw)
        val valid = normalized.isEmpty() || PATTERN.matches(normalized)
        val restricted = (context["restricted"] as? JsonPrimitive)?.booleanOrNull == true
        if (restricted) return InviteDecision(false, valid, "", null)
        return InviteDecision(true, valid, normalized, NOTICE_KEY)
    }

    /**
     * 登录请求里带的 invite_code：受限登录不带；规范化后为空不带；超过契约长度不带（否则整个登录 20001）。语法不对的照样带上，由服务端
     * 判（失败只回 invite_bind.failed，不阻止注册，INVITE-01）。
     */
    fun wireValue(
        raw: String,
        restricted: Boolean,
    ): String? {
        if (restricted) return null
        return normalize(raw).takeIf { it.isNotEmpty() && it.length <= MAX_WIRE_LENGTH }
    }
}

/** 登录成功后的动作（[AfterLogin.plan] 的元素；三端同名）。 */
object AfterLoginAction {
    /** 会话已由 ApiClient 保存（执行器只在 ApiClient 返回成功后继续，INTEGRATION-01）。 */
    const val SAVE_SESSION = "save_session"

    /** 交给 Router 守卫恢复 pending_action（BR-ID-10）。 */
    const val RESUME_PENDING = "resume_pending"

    /** 受限会话或强更：删除业务类 pending_action，不执行。 */
    const val DISCARD_PENDING = "discard_pending"
    const val ENTER_FORCE_UPDATE = "enter_force_update"

    /**
     * 受限会话与强更「受支持」并存（本机结论与服务端不一致，AFTER-02）：结论未定，不动待办；执行器先做一次受限会话复查（单飞刷新），
     * 再按最新的作用域与强更结论重新计划（APP-05a 共性第 4 条）。
     */
    const val REFRESH_RESTRICTED = "refresh_restricted"

    /** 复查之后仍是 deletion_only、强更仍是 supported：待办保留、关闭登录页，由 Router 随强更结论决定（共性名字对齐）。 */
    const val HOLD_PENDING = "hold_pending"
}

/** [AfterLogin.plan] 的强更状态取值。 */
object AfterLoginUpdate {
    const val SUPPORTED = "supported"
    const val BELOW = "below"
    const val UNKNOWN = "unknown"
    const val CHECKING = "checking"
}

/**
 * 登录成功后的会话 / 路由计划（03 §4.2「受限会话」、§4.4；BR-ID-01、BR-ID-10）：
 * - 先 save_session（ApiClient 已保存成功才算登录成功）；
 * - 受限会话（deletion_only）且强更低于 / 未知 / 检查中：丢弃业务 pending_action，进强更页；
 * - 正常会话但强更低于：同样丢弃并进强更页（正常会话不绕过强更）；
 * - deletion_only 且强更 supported：refresh_restricted（不删待办，执行器复查后重新计划）；
 * - 其余：有 pending_action 时交给 Router 守卫恢复（未知 / 检查中由 Router 自己等待或按只读清单处理）。
 * 邀请绑定结果（invite_bind）不改变计划、不截断登录（AFTER-03）。
 */
object AfterLogin {
    fun plan(
        response: JsonObject,
        state: JsonObject,
    ): List<String> {
        val actions = mutableListOf(AfterLoginAction.SAVE_SESSION)
        val restricted = response.text("session_scope") == SessionScopeRaw.DELETION_ONLY
        val update = state.text("update")
        val pending = (state["pendingAction"] as? JsonPrimitive)?.booleanOrNull == true
        val below = update == AfterLoginUpdate.BELOW
        val notSupported = update != AfterLoginUpdate.SUPPORTED
        when {
            (restricted && notSupported) || below -> {
                if (pending) actions += AfterLoginAction.DISCARD_PENDING
                actions += AfterLoginAction.ENTER_FORCE_UPDATE
            }

            restricted -> {
                actions += AfterLoginAction.REFRESH_RESTRICTED
            }

            pending -> {
                actions += AfterLoginAction.RESUME_PENDING
            }
        }
        return actions
    }

    /** 执行器用：作用域与强更、待办状态 → 计划。 */
    fun plan(
        sessionScope: String,
        isNewUser: Boolean,
        update: String,
        pendingAction: Boolean,
    ): List<String> =
        plan(
            JsonObject(mapOf("session_scope" to JsonPrimitive(sessionScope), "is_new_user" to JsonPrimitive(isNewUser))),
            JsonObject(mapOf("update" to JsonPrimitive(update), "pendingAction" to JsonPrimitive(pendingAction))),
        )

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/** 登录请求上送的协议版本（BR-ID-04；openapi LegalVersions）。 */
data class LegalVersions(
    val privacy: Int,
    val agreement: Int,
) {
    companion object {
        /**
         * 包内当前版本：包内默认配置（default_config.json 的 legal.privacy.version、legal.agreement.version）。读不出时按 1（契约最小值）。
         * TODO(N-26) LEGAL-01：首启弹窗记录的包内隐私政策版本仍是占位 1，待法务定稿随包导出协议副本后统一。
         */
        fun parse(configJson: String?): LegalVersions {
            val root = configJson?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            val legal = root?.get("legal") as? JsonObject
            return LegalVersions(version(legal, "privacy"), version(legal, "agreement"))
        }

        private fun version(
            legal: JsonObject?,
            name: String,
        ): Int {
            val value = ((legal?.get(name) as? JsonObject)?.get("version") as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            return value?.takeIf { it >= 1 } ?: 1
        }
    }
}
