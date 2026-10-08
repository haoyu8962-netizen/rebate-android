// 错误码是契约取值（error-codes），按值直接写
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/*
 * 第三方登录、绑手机与空号并入的纯逻辑（APP-05b；BR-ID-04、BR-ID-06、BR-INV-03 细则；03 §4.2）。三端同名；向量
 * specs/oauth-login-vectors.json（OAuthLoginVectorTest）。没有界面、网络、SDK 与计时器：执行器是 [OAuthLoginService] /
 * [BindPhoneService]，页面在本模块的 BindPhoneScreen 与登录页。routeRaw / actionRaw / resultRaw 是三端接线名，不是契约路由。
 */

/** 第三方 provider 名（openapi CreateOauthAttemptRequest.provider）。 */
object OAuthProvider {
    const val WECHAT = "wechat"
    const val APPLE = "apple"
    const val HUAWEI = "huawei"

    /** provider → 登录接口 operationId；不认识的为 null。 */
    fun loginOperation(provider: String): String? =
        when (provider) {
            WECHAT -> "loginByWechat"
            APPLE -> "loginByApple"
            HUAWEI -> "loginByHuawei"
            else -> null
        }
}

/** 被拒的回调（观测接线：attempt 句柄与回调编号，不是服务端字段）。 */
data class DroppedCallback(
    val attempt: String,
    val seq: Int,
)

/**
 * 授权尝试的归属与页面世代（BR-ID-04 细则「授权尝试」「先校验、后消费」；任务书 §2.1）：
 * - [create] 取一个新的本地尝试（未终止时再次 create，旧尝试作废：TODO(N-29) ATTEMPT 原文未定义，保守处理）；
 * - [callback] 只接收凭证：当前尝试、已拉起、未终止、还没收到过凭证才接受；其余（迟到、重复、旧尝试）一律记入 [dropped]；
 * - [submit] 是唯一提交边界：同一尝试只提交一次，已终止或没有凭证的不提交（不记 dropped）；
 * - [cancel] / [leave] / [timeout] 终止当前尝试：回前台后迟到的回调只丢弃。
 * 同步纯逻辑；SDK 回调可能来自任意线程，方法加锁。
 */
class OAuthAttemptSession {
    private class Record(
        val handle: String,
    ) {
        var launched = false
        var credential = false
        var submitted = false
        var terminated = false
    }

    private val lock = Any()
    private val records = mutableMapOf<String, Record>()
    private var current: Record? = null
    private var counter = 0
    private val submittedHandles = mutableListOf<String>()
    private val droppedCallbacks = mutableListOf<DroppedCallback>()

    /** 已提交的尝试（次序即提交次序）。 */
    val submitted: List<String> get() = synchronized(lock) { submittedHandles.toList() }

    /** 被拒的回调。 */
    val dropped: List<DroppedCallback> get() = synchronized(lock) { droppedCallbacks.toList() }

    fun create(): String =
        synchronized(lock) {
            terminateCurrent()
            counter += 1
            val record = Record("oauth-attempt-$counter")
            records[record.handle] = record
            current = record
            record.handle
        }

    fun launch(attempt: String) {
        synchronized(lock) { live(attempt)?.launched = true }
    }

    /** 第三方回调带回凭证；返回是否接受（不接受的记入 [dropped]）。 */
    fun callback(
        attempt: String,
        seq: Int,
    ): Boolean =
        synchronized(lock) {
            val record = live(attempt)?.takeIf { it.launched }
            if (record == null || record.credential || record.submitted) {
                droppedCallbacks += DroppedCallback(attempt, seq)
                return false
            }
            record.credential = true
            true
        }

    /** 提交（登录请求的唯一边界）；返回这次是否真正提交。 */
    fun submit(attempt: String): Boolean =
        synchronized(lock) {
            val record = live(attempt)
            if (record == null || !record.credential || record.submitted) return false
            record.submitted = true
            submittedHandles += attempt
            true
        }

    /** 用户在第三方授权里取消。 */
    fun cancel() = synchronized(lock) { terminateCurrent() }

    /** 离开登录页（出栈、换方式、登出、归属变化）。 */
    fun leave() = synchronized(lock) { terminateCurrent() }

    /** 授权等待超时。 */
    fun timeout() = synchronized(lock) { terminateCurrent() }

    /** 这个尝试仍是当前、没有终止。 */
    fun isLive(attempt: String): Boolean = synchronized(lock) { live(attempt) != null }

    private fun live(attempt: String): Record? = records[attempt]?.takeIf { !it.terminated && current === it }

    private fun terminateCurrent() {
        current?.terminated = true
        current = null
    }
}

/**
 * 第三方登录成功后的分流。[routeRaw]：done（老用户直接登录）/ bind_phone_guide（第三方新号绑手机引导）/ error（不是可保存的登录
 * 响应）。[saveSession] 是保存意图（原子保存与世代检查由 ApiClient 与 [LoginService] 负责）。[firstPurchaseTip]：登录响应不含判定
 * 所需的信息（rule-gaps INVITE-STATE），一律不给值。
 */
data class OAuthRouteResult(
    val routeRaw: String,
    val saveSession: Boolean,
    val firstPurchaseTip: Boolean? = null,
)

/**
 * 第三方登录响应 → 分流（BR-ID-04 细则：四个登录接口都带 is_new_user；BR-INV-03 细则 ①：第三方新号登录成功后弹一次绑手机引导）。
 * 只看完整外壳的 code、data.tokens 与 is_new_user；deletion_only（受限登录）沿用 APP-05a 的登录后计划（done）。并号不来自登录响应
 * （rule-gaps LOGIN-MERGE），只来自 bindPhone 的 30411 mergeable。
 */
object OAuthLoginRoute {
    const val DONE = "done"
    const val BIND_PHONE_GUIDE = "bind_phone_guide"
    const val ERROR = "error"

    fun map(
        provider: String,
        response: JsonObject,
    ): OAuthRouteResult {
        val code = (response["code"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        if (OAuthProvider.loginOperation(provider) == null || code != 0) return OAuthRouteResult(ERROR, false)
        val data = response["data"] as? JsonObject ?: return OAuthRouteResult(ERROR, false)
        return ofData(data)
    }

    /** 已成功的登录响应的 data（LoginData）→ 分流。 */
    fun ofData(data: JsonObject): OAuthRouteResult {
        val tokens = data["tokens"] as? JsonObject ?: return OAuthRouteResult(ERROR, false)
        if (tokens.text("access_token") == null || tokens.text("refresh_token") == null) return OAuthRouteResult(ERROR, false)
        val restricted = tokens.text("session_scope") == SessionScopeRaw.DELETION_ONLY
        val isNew = (data["is_new_user"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true
        return if (isNew && !restricted) OAuthRouteResult(BIND_PHONE_GUIDE, true) else OAuthRouteResult(DONE, true)
    }

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/**
 * 第三方登录错误的页面动作。[actionRaw]：reauthorize（20004：用户重新取尝试并授权，不自动重试）/ provider_unavailable（50305：保留
 * 其他方式，不自动重试）/ field_error / risk_blocked（44001）/ force_update（10405）/ service_error（5xxxx，附 trace6）/ retry_after
 * （42901）/ network_error / message（其余）。[disableMillis] 42901 的禁用毫秒数（Retry-After）。
 */
data class OAuthErrorResult(
    val actionRaw: String,
    val textKey: String?,
    val showTrace6: Boolean?,
    val disableMillis: Long = 0L,
)

/** 第三方登录（含取授权尝试）错误码 → 页面动作，接已有的 [ErrorActionMapper]（不另造码表）。 */
object OAuthLoginErrorAction {
    const val REAUTHORIZE = "reauthorize"
    const val PROVIDER_UNAVAILABLE = "provider_unavailable"
    const val FIELD_ERROR = "field_error"
    const val RISK_BLOCKED = "risk_blocked"
    const val FORCE_UPDATE = "force_update"
    const val SERVICE_ERROR = "service_error"
    const val RETRY_AFTER = "retry_after"
    const val NETWORK_ERROR = "network_error"
    const val MESSAGE = "message"

    fun map(
        provider: String,
        code: Int,
        data: JsonObject,
        headers: Map<String, String> = emptyMap(),
    ): OAuthErrorResult {
        val opId = OAuthProvider.loginOperation(provider) ?: OAuthProvider.loginOperation(OAuthProvider.WECHAT)!!
        val operation = ApiOperations.byId(opId) ?: return OAuthErrorResult(MESSAGE, null, false)
        // 登录页没有会话：按「受支持、正常作用域」映射（同 LoginErrorMapper；网络层按真实状态另行处理强更与会话）
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
        return ofEffect(code, effect)
    }

    /** 网络层已给出的 Effect（[com.couli.core.network.ApiResult.Failure.effect]）→ 页面动作。 */
    fun ofEffect(
        code: Int,
        effect: ErrorEffect,
    ): OAuthErrorResult {
        val key = effect.textKey
        if (code == 44001) return OAuthErrorResult(RISK_BLOCKED, key, false)
        if (code == 50305) return OAuthErrorResult(PROVIDER_UNAVAILABLE, key, false)
        return when (effect.kind) {
            ErrorEffectKind.REAUTHORIZE -> OAuthErrorResult(REAUTHORIZE, key, false)
            ErrorEffectKind.FIELD_ERROR -> OAuthErrorResult(FIELD_ERROR, key, false)
            ErrorEffectKind.FORCE_UPDATE -> OAuthErrorResult(FORCE_UPDATE, key, false)
            ErrorEffectKind.DISABLE_BUTTON -> OAuthErrorResult(RETRY_AFTER, key, false, effect.disableMillis ?: 0L)
            ErrorEffectKind.GENERIC_ERROR -> OAuthErrorResult(SERVICE_ERROR, key, true)
            else -> OAuthErrorResult(MESSAGE, key, effect.includeTrace6)
        }
    }
}

/**
 * 第三方登录按钮是否显示（BR-ID-04 细则「未安装微信时」；任务书 §2.2）：
 * - 微信：本机已安装（检测结果未知时调用方按「照常显示」传 true）且适配器已允许启用。三端正式适配器恒为不可用（无 SDK、无 AppID），
 *   所以正式包不显示；Debug 假适配器只用于测试与截图（rule-gaps BUTTONS）。
 * - Apple：只有 iOS（BR-ID-04 默认值只给 iOS 写了 SIWA；向量 appleButton.android = false）。
 */
object ThirdPartyButtons {
    const val PLATFORM_ANDROID = "android"
    private val PLATFORMS = setOf("ios", PLATFORM_ANDROID, "harmony")

    fun wechatVisible(
        platform: String,
        installed: Boolean,
        adapterAvailable: Boolean,
    ): Boolean = platform in PLATFORMS && installed && adapterAvailable

    fun appleVisible(platform: String): Boolean = platform == "ios"
}

/**
 * 绑手机相关响应的页面动作。[actionRaw]：done（bindPhone 成功：之后刷新 getMe 与会话状态）/ retry_after（发码已受理交倒计时，或
 * 42901 按 Retry-After 等待）/ merge_confirm（30411 mergeable 且带 merge_ticket：弹并号确认，不自动并号）/ error（提示 textKey）。
 */
data class BindActionResult(
    val actionRaw: String,
    val textKey: String?,
)

/**
 * 绑手机发码（purpose=bind）与 bindPhone 的响应 → 页面动作（BR-ID-05、BR-ID-06；openapi bindPhone 的 30411 data.reason=mergeable 与
 * merge_ticket）。输入是本地接线包装 {operation, purpose?, body, headers?}，body 才是契约外壳。
 */
object BindPhoneAction {
    const val DONE = "done"
    const val RETRY_AFTER = "retry_after"
    const val MERGE_CONFIRM = "merge_confirm"
    const val ERROR = "error"
    const val BIND_OPERATION = "bindPhone"
    const val SMS_OPERATION = "sendSmsCode"
    private const val GENERIC_KEY = "error.50001"

    fun map(input: JsonObject): BindActionResult {
        val operation = (input["operation"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val body = input["body"] as? JsonObject
        if (operation == null || body == null) return BindActionResult(ERROR, GENERIC_KEY)
        val code = (body["code"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: return BindActionResult(ERROR, GENERIC_KEY)
        val data = body["data"] as? JsonObject ?: JsonObject(emptyMap())
        val headers =
            (input["headers"] as? JsonObject)
                ?.mapNotNull { (name, value) -> (value as? JsonPrimitive)?.takeIf { it.isString }?.let { name to it.content } }
                ?.toMap()
                .orEmpty()
        return ofCode(operation, code, data, headers)
    }

    fun ofCode(
        operation: String,
        code: Int,
        data: JsonObject,
        headers: Map<String, String> = emptyMap(),
    ): BindActionResult {
        if (code == 0) return BindActionResult(if (operation == BIND_OPERATION) DONE else RETRY_AFTER, null)
        if (operation == BIND_OPERATION && code == 30411) {
            return if (mergeTicket(data) != null) {
                BindActionResult(MERGE_CONFIRM, "error.30411.mergeable")
            } else {
                BindActionResult(ERROR, "error.30411")
            }
        }
        val op = ApiOperations.byId(operation) ?: return BindActionResult(ERROR, GENERIC_KEY)
        val effect =
            ErrorActionMapper.map(
                ErrorActionInput(
                    code = code,
                    data = data,
                    operation = op,
                    headers = headers,
                    isReplay = false,
                    state = ErrorActionState(UpdateRaw.SUPPORTED, SessionScopeRaw.FULL, ""),
                ),
            )
        return ofEffect(effect)
    }

    /** 网络层已给出的 Effect → 页面动作（30411 先经 [ofCode] 判并号）。 */
    fun ofEffect(effect: ErrorEffect): BindActionResult =
        if (effect.kind ==
            ErrorEffectKind.DISABLE_BUTTON
        ) {
            BindActionResult(RETRY_AFTER, effect.textKey)
        } else {
            BindActionResult(ERROR, effect.textKey)
        }

    /** 30411 的并号凭据：reason=mergeable 且 merge_ticket 是非空字符串才有；否则 null（不能并号）。 */
    fun mergeTicket(data: JsonObject): String? {
        val reason = (data["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val ticket = (data["merge_ticket"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return ticket?.takeIf { reason == "mergeable" && it.isNotEmpty() }
    }
}

/**
 * 并号确认的结果。[resultRaw]：cancelled（请求发出前取消确认：不并号、不保存会话）/ rejected（确认后收到 30411：票据过期、已用或条件
 * 改变）/ unavailable（锁定契约没有并号接口，TODO(N-29) MERGE-SUCCESS：不发请求）/ unsupported（收到成功外壳但没有契约 schema，不拿
 * LoginResponse 冒充，不保存）/ failed（其他错误）。
 */
data class MergeResult(
    val resultRaw: String,
    val saveSession: Boolean,
)

/** 并号确认 / 取消（BR-ID-06 OPS-04；规划/04 §6.1 30411）。任何结果都不保存会话（并号成功响应的形状未定）。 */
object MergeDecision {
    const val CANCEL = "cancel"
    const val CONFIRM = "confirm"
    const val CANCELLED = "cancelled"
    const val REJECTED = "rejected"
    const val UNAVAILABLE = "unavailable"
    const val UNSUPPORTED = "unsupported"
    const val FAILED = "failed"

    fun apply(
        decision: String,
        response: JsonObject?,
    ): MergeResult {
        if (decision != CONFIRM) return MergeResult(CANCELLED, false)
        if (response == null) return MergeResult(UNAVAILABLE, false)
        return when ((response["code"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull) {
            30411 -> MergeResult(REJECTED, false)
            0 -> MergeResult(UNSUPPORTED, false)
            else -> MergeResult(FAILED, false)
        }
    }
}
