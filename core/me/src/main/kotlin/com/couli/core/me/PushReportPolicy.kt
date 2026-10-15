package com.couli.core.me

import com.couli.core.me.MeJson.bool
import com.couli.core.me.MeJson.fen
import com.couli.core.me.MeJson.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 推送上报策略的事件（三端同名）。 */
object PushEvent {
    const val LOGIN_SUCCESS = "login_success"
    const val COLD_START = "cold_start"
    const val TOKEN_CHANGED = "token_changed"
    const val SESSION_CHANGED = "session_changed"
    const val RESPONSE_OK = "response_ok"
    const val RESPONSE_ERROR = "response_error"
    const val RESPONSE_10405 = "response_10405"

    /** 42901（F-X-08 ②，FU-AND-0715；对照 iOS APP-12 I26）：会话级禁用期开始。 */
    const val RESPONSE_42901 = "response_42901"

    /** 42901 的禁用期到了（执行器按 Retry-After 计时后发出）。 */
    const val RATE_LIMIT_EXPIRED = "rate_limit_expired"
    const val LOGOUT = "logout"
    const val CONSENT_WITHDRAWN = "consent_withdrawn"
}

/** 推送上报策略的动作。 */
object PushAction {
    const val NONE = "none"
    const val SEND = "send"
    const val MARK_PENDING = "mark_pending"
    const val DROP = "drop"
    const val FORCE_UPDATE = "force_update"
}

/**
 * 推送上报的纯策略（G18，BR-ID-07 细则「推送令牌与会话」）。状态：当前会话的身份档、会话作用域、有无令牌、令牌值、强更结论、会话世代；在途的会话世代；
 * 待报标记。
 * - 触发只有三种：登录成功（新会话保存之后）、已登录冷启动（强更检查有结果且不低于）、已登录期间令牌值变化。
 * - 同一时刻只一条在途：在途期间同一会话的新触发只记待报；在途结束后按当时的会话与令牌值再发一次（只发最新值）。
 * - 每条绑定发起时的会话世代：响应的会话不是在途那条的 → drop（不改任何状态）；会话换代（session_changed）清掉在途与待报。
 * - 不报：未同意 / 基本模式 / 游客（levelRaw 不是 member）、deletion_only、没有令牌、强更低于最低版本。
 * - 失败不自动重试；10405 交强更分流（force_update），不重发。
 * - 42901（F-X-08 ②，与 iOS APP-12 I26 同口径）：会话级禁用期——禁用期内的触发（含在途结束后的补发待报、新的令牌变化）不发、只记待报；到期
 *   （rate_limit_expired）有待报才按当时的会话与令牌值补发一次（这是新的触发，不是重试失败的旧请求）；会话换代、退出、撤回清掉禁用期。
 *   只有在途那条的 42901 开始禁用期（旧会话的迟到响应 drop、不开始也不改计时）；到期事件要带开始时的轮次 limitRound，对不上 none（R2-01）。
 * - 退出、撤回之后本实例终止，所有事件 none（重新启用另建实例）。
 * 非响应事件的 state 是当前状态补丁；响应事件的 state.session 是原请求的会话世代，不覆盖当前会话。令牌值只在返回的 sendToken 里交给发送方。
 */
class PushReportPolicy {
    private var level = ""
    private var scope = ""
    private var hasToken = false
    private var token = ""
    private var update = "unknown"
    private var session = ""
    private var inFlightSession: String? = null
    private var pendingFlag = false
    private var terminated = false

    /** 42901 禁用期中（F-X-08 ②）。 */
    private var limited = false

    /**
     * 禁用期的轮次（FU-AND-0715 R2-01）：每次实际接纳的 42901（在途那条的响应）加一；到期事件必须带上开始计时时拿到的轮次（state.limitRound），
     * 对不上（旧轮次、旧会话的计时、没带轮次）一律 none、不改任何状态——旧计时不能解除新禁用期。
     */
    private var round = 0L

    val inFlight: Boolean get() = inFlightSession != null
    val rateLimited: Boolean get() = limited

    /** 当前禁用期的轮次（执行器开始计时时记下，到期时原样带回）。 */
    val limitRound: Long get() = round
    val pending: Boolean get() = pendingFlag
    val stopped: Boolean get() = terminated

    private fun apply(patch: JsonObject) {
        patch.string("levelRaw")?.let { level = it }
        patch.string("sessionScopeRaw")?.let { scope = it }
        patch.bool("hasToken")?.let { hasToken = it }
        patch.string("token")?.let { token = it }
        patch.string("forceUpdateRaw")?.let { update = it }
        patch.string("session")?.let { session = it }
    }

    private fun eligible(): Boolean =
        !terminated && level == MeLevel.MEMBER && scope == FULL && hasToken && token.isNotEmpty() && session.isNotEmpty() && update != BELOW

    private fun send(): JsonObject {
        inFlightSession = session
        pendingFlag = false
        return obj(
            "actionRaw" to JsonPrimitive(PushAction.SEND),
            "sendToken" to JsonPrimitive(token),
            "sendSession" to JsonPrimitive(session),
        )
    }

    private fun action(raw: String): JsonObject = obj("actionRaw" to JsonPrimitive(raw))

    @Suppress("ReturnCount") // 不可报、记待报、发送三个出口
    private fun trigger(): JsonObject {
        if (!eligible()) return action(PushAction.NONE)
        // 42901 禁用期内：不发，记待报（到期后按当时的会话与令牌值补发一次）
        if (limited) {
            pendingFlag = true
            return action(PushAction.MARK_PENDING)
        }
        if (inFlightSession != null && inFlightSession == session) {
            pendingFlag = true
            return action(PushAction.MARK_PENDING)
        }
        return send()
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    fun on(
        event: String,
        state: JsonObject,
    ): JsonObject {
        if (terminated) return action(PushAction.NONE)
        return when (event) {
            PushEvent.LOGIN_SUCCESS, PushEvent.TOKEN_CHANGED -> {
                apply(state)
                trigger()
            }

            PushEvent.COLD_START -> {
                apply(state)
                if (update != SUPPORTED) action(PushAction.NONE) else trigger()
            }

            PushEvent.SESSION_CHANGED -> {
                apply(state)
                inFlightSession = null
                pendingFlag = false
                limited = false
                action(PushAction.NONE)
            }

            PushEvent.RATE_LIMIT_EXPIRED -> {
                if (!limited || state.fen("limitRound") != round) return action(PushAction.NONE)
                apply(state)
                limited = false
                if (!pendingFlag) return action(PushAction.NONE)
                pendingFlag = false
                trigger()
            }

            PushEvent.RESPONSE_OK, PushEvent.RESPONSE_ERROR, PushEvent.RESPONSE_10405, PushEvent.RESPONSE_42901 -> {
                val origin = state.string("session")
                if (inFlightSession == null || origin != inFlightSession) return action(PushAction.DROP)
                inFlightSession = null
                // 42901：禁用期先开始，再结算待报（待报留到到期；失败的那条不重发）
                if (event == PushEvent.RESPONSE_42901) {
                    limited = true
                    round += 1
                    return action(PushAction.NONE)
                }
                if (event == PushEvent.RESPONSE_10405) {
                    pendingFlag = false
                    return action(PushAction.FORCE_UPDATE)
                }
                if (pendingFlag) {
                    pendingFlag = false
                    // 在途期间有新触发：按当时的会话与令牌值补发最新值（失败的那条不重发——这是新的触发）
                    return trigger()
                }
                action(PushAction.NONE)
            }

            PushEvent.LOGOUT, PushEvent.CONSENT_WITHDRAWN -> {
                terminated = true
                inFlightSession = null
                pendingFlag = false
                action(PushAction.NONE)
            }

            else -> {
                action(PushAction.NONE)
            }
        }
    }

    private companion object {
        const val FULL = "full"
        const val BELOW = "below"
        const val SUPPORTED = "supported"
    }
}
