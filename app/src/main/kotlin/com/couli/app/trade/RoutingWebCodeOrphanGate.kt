package com.couli.app.trade

import com.couli.app.routing.AppRouting
import com.couli.core.router.PrivacyState
import com.couli.core.router.SessionState
import com.couli.core.router.UpdateCheck
import com.couli.feature.trade.WebCodeOrphanGate
import com.couli.feature.trade.WebCodeOrphanStartup
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 冷启动 / 晚到回跳的启动闸门（FU-AND-TB-WEBCODE-B，设计稿 §6.4 第 1 步；03 §4.4；D10-3681 B-4），读守卫用的同一套状态（[AppRouting]）：
 * 等环境闸门 settled、隐私有结论、会话已恢复、强更检查有结果，最多等 versionWaitMillis（5 秒），然后：
 * - 隐私不是已同意 → BASIC_MODE；
 * - 没有会员会话（[account] 为 null；或守卫看到的是游客 / 受限，含撤回同意的清理没完成）→ LOGGED_OUT；
 * - 低于最低版本 → BELOW_MIN_VERSION；
 * - 环境仍没 settled、强更检查仍在进行或结果未知 → VERSION_UNKNOWN（不提交 bindings、丢弃回跳）；
 * - 其余 → READY。
 * 「会话已恢复」：ApiClient 里有会话时，要等登录模块把它发布给守卫（守卫的会话不再是游客）；ApiClient 里没有会话即未登录，不用等。
 * 环境闸门没有可订阅的状态，按 [pollMillis] 轮询（只在冷启动 / 晚到回跳这一处、最多 5 秒）。
 */
class RoutingWebCodeOrphanGate(
    private val routing: AppRouting,
    private val account: () -> String?,
    private val pollMillis: Long = POLL_MILLIS,
) : WebCodeOrphanGate {
    override suspend fun await(versionWaitMillis: Long): WebCodeOrphanStartup {
        withTimeoutOrNull(versionWaitMillis) {
            while (!settled()) delay(pollMillis)
        }
        return verdict()
    }

    private fun settled(): Boolean {
        val guard = routing.guardState()
        val sessionRestored = account() == null || guard.session != SessionState.GUEST.raw
        return guard.environmentSettled &&
            routing.privacy.value != PrivacyState.NOT_DECIDED &&
            sessionRestored &&
            routing.update.value != UpdateCheck.CHECKING
    }

    private fun verdict(): WebCodeOrphanStartup {
        val guard = routing.guardState()
        val member = guard.session == SessionState.LOGGED_IN.raw || guard.session == SessionState.PHONE_BOUND.raw
        val update = routing.update.value
        val unknown = !guard.environmentSettled || update == UpdateCheck.CHECKING || update == UpdateCheck.UNKNOWN
        return when {
            routing.privacy.value != PrivacyState.CONSENTED -> WebCodeOrphanStartup.BASIC_MODE
            account() == null || !member -> WebCodeOrphanStartup.LOGGED_OUT
            update == UpdateCheck.BELOW_MIN -> WebCodeOrphanStartup.BELOW_MIN_VERSION
            unknown -> WebCodeOrphanStartup.VERSION_UNKNOWN
            else -> WebCodeOrphanStartup.READY
        }
    }

    private companion object {
        const val POLL_MILLIS = 100L
    }
}
