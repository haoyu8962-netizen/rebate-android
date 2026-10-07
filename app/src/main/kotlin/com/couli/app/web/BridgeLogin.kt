package com.couli.app.web

import com.couli.app.login.AppLogin
import com.couli.app.routing.AppRouting
import com.couli.core.bridge.BridgeCallValidity
import com.couli.core.bridge.BridgeOutcome
import com.couli.core.router.GuardDecision
import com.couli.core.router.SessionState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 桥调用 auth.login（APP-04 共性第 66 条；bridge.schema.json auth.login「Opens Login; resolves when the user finishes or leaves」）：
 * - 已登录（完整会话）：不打开登录页，直接 logged_in = true；
 * - 否则先登记等待，再以桥来源经 Router 打开登录页（[open]，享有导航调用的作废豁免）；Router 没有打开登录页（被拒、要等待、复核时
 *   已过时）按此刻的会话结算；
 * - 打开了：等用户完成登录（会话已保存 → logged_in = true，结果绑定这次登录保存的登录世代，本次登录引起的会话变化不把调用作废），
 *   或取消、离开登录页（logged_in = false）。等待期间按 [BridgeCallValidity] 核对宿主世代与隐私，失效即结束（BridgeHost 回 90500）。
 * 只在主线程使用。
 */
internal class BridgeLogin(
    private val login: () -> AppLogin,
    private val routing: AppRouting,
    private val round: () -> Long,
) {
    /** 以桥来源经 Router 立即打开登录页（导航前的复核同 nav.open）。返回给页面的结果与是否真的打开了登录页。 */
    @Suppress("ReturnCount") // 失败、没有推页面、推了页面三个出口
    fun open(update: String): Pair<BridgeOutcome, Boolean> {
        // 本次导航真正交给 Router 的结论（R3-01：不比较 Router.lastOutcome 的对象，相同结论会被状态流按值去重）
        var executed: GuardDecision? = null
        val outcome =
            BridgeRouting.open(routing.router, routing::guardState, LOGIN, JsonObject(emptyMap()), update, round) { executed = it }
        if (outcome !is BridgeOutcome.Ok) return outcome to false
        // 被拒或要等待（Router 不推页面）：按此刻的会话结算
        val navigate = outcome.afterReply ?: return BridgeOutcome.Ok(result(loggedIn())) to false
        // auth.login 要等登录页结束才回复（契约），打开登录页不能等回复之后：在这次调用里执行（登录页压在本容器之上，不拆本容器）
        navigate()
        val decision = executed
        val opened = decision != null && decision.opensPage && decision.route == LOGIN
        return BridgeOutcome.Ok(result(loggedIn())) to opened
    }

    @Suppress("ReturnCount") // 已登录、没有打开、离开、完成四个出口
    suspend fun run(
        update: String,
        validity: BridgeCallValidity,
        opening: suspend (suspend () -> BridgeOutcome) -> BridgeOutcome,
    ): BridgeOutcome {
        if (loggedIn()) return BridgeOutcome.Ok(result(true))
        val app = login()
        val wait = app.beginBridgeLogin()
        try {
            var opened = false
            val outcome =
                opening {
                    val (reply, pushed) = open(update)
                    opened = pushed
                    reply
                }
            if (outcome !is BridgeOutcome.Ok || !opened) return outcome
            val epoch = app.awaitBridgeLogin(wait, validity::stillValid) ?: return BridgeOutcome.Ok(result(false))
            return BridgeOutcome.Ok(result(true), boundSessionEpoch = epoch)
        } finally {
            app.endBridgeLogin(wait)
        }
    }

    private fun loggedIn(): Boolean = routing.session.value == SessionState.PHONE_BOUND

    private fun result(loggedIn: Boolean) = JsonObject(mapOf("logged_in" to JsonPrimitive(loggedIn)))

    private companion object {
        const val LOGIN = "Login"
    }
}
