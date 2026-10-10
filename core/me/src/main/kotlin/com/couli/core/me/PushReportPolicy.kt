package com.couli.core.me

import com.couli.core.me.MeJson.bool
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

    val inFlight: Boolean get() = inFlightSession != null
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
                action(PushAction.NONE)
            }

            PushEvent.RESPONSE_OK, PushEvent.RESPONSE_ERROR, PushEvent.RESPONSE_10405 -> {
                val origin = state.string("session")
                if (inFlightSession == null || origin != inFlightSession) return action(PushAction.DROP)
                inFlightSession = null
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
