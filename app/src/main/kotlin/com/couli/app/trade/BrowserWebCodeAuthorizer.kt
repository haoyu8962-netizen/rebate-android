package com.couli.app.trade

import com.couli.feature.trade.AuthCredential
import com.couli.feature.trade.AuthStepResult
import com.couli.feature.trade.WebCodeAttemptStore
import com.couli.feature.trade.WebCodeAuthorizer
import com.couli.feature.trade.WebCodeCallback
import com.couli.feature.trade.WebCodeInbox
import com.couli.feature.trade.webCodeStateSha256
import java.net.URI
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.yield

/**
 * 淘宝 web_code 的真实授权器（设计稿 §2 ①–④、§5.5、§6.1、§6.3；D10-2717 定值）：
 * ① auth_url 只放行 https 的 oauth.taobao.com（不带 userinfo、不带端口），不在白名单返回 Failed、不打开、不记尝试；
 * ② 记下本机尝试（只存 sha256(state) 与 [clock] 时间）；
 * ③ 经 [launcher] 打开，打不开返回 Failed（并删掉这条尝试记录）；
 * ④ 等 [inbox] 里 state 哈希匹配的回跳（Code → Credential 并删记录；error=1 → Failed 并删记录），或 [returns] 先 STOPPED 再 STARTED
 *    之后（再让一轮）仍无回跳 → Cancelled（记录保留，留给晚到回跳核对）。不另设本地计时器。「再让一轮」用调用方协程上下文的 [yield]，
 *    不直接取 Dispatchers.Main。等待被取消（页面离开、新的点击）时，收件箱里的认领随之注销，晚到的回跳留在收件箱。
 * [available] 恒为 true（release 与关着假服务的 debug / staging 都能执行；没有浏览器时在打开那一步返回 Failed）。
 * code 只在内存里经过（收件箱 → 凭证），不保存、不进日志。
 */
class BrowserWebCodeAuthorizer(
    private val launcher: AuthBrowserLauncher,
    private val inbox: WebCodeInbox,
    private val attempts: WebCodeAttemptStore,
    private val returns: AuthReturnSignal,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * 此刻的账号标签（webCodeAccountTag(用户 id)；游客为 null）：记尝试时一起记下，冷启动 / 晚到回跳据此核对账号（FU-AND-TB-WEBCODE-B，设计稿 §2 ②）。
     * TODO(FU-AND-TB-WEBCODE-B 接口桩)：桩里不用；实现时改调 attempts.record(state, now, accountTag())，删记录改用 attempts.clearIfCurrent(哈希)（评审 S3 F2）。
     */
    private val accountTag: () -> String? = { null },
) : WebCodeAuthorizer {
    override val available: Boolean get() = true

    override suspend fun authorize(
        authUrl: String,
        state: String,
    ): AuthStepResult {
        if (!isAllowedAuthUrl(authUrl)) return AuthStepResult.Failed
        val stateSha256 = webCodeStateSha256(state)
        attempts.record(state, clock())
        return coroutineScope {
            // 先登记两件要等的事，再打开授权页：打开之后 MainActivity 立刻 onStop，不能漏掉
            val returned = async(start = CoroutineStart.UNDISPATCHED) { awaitReturnToApp() }
            val callback = async(start = CoroutineStart.UNDISPATCHED) { inbox.await(stateSha256) }
            try {
                if (!launcher.open(authUrl)) {
                    attempts.clear()
                    AuthStepResult.Failed
                } else {
                    select<AuthStepResult> {
                        callback.onAwait { finish(it) }
                        returned.onAwait {
                            // 中转 Activity 在自己的 onCreate 里同步送出回跳，早于 MainActivity 的 onStart；再让一轮，回跳已到就以回跳为准
                            yield()
                            if (callback.isCompleted) finish(callback.await()) else AuthStepResult.Cancelled
                        }
                    }
                }
            } finally {
                returned.cancel()
                callback.cancel()
            }
        }
    }

    /** 拉起之后「先 STOPPED、再 STARTED」：用户从授权页回到了 App。只有 STARTED（拉起之前的那次）不算。 */
    private suspend fun awaitReturnToApp() {
        var left = false
        returns.events.first { event ->
            if (event == AuthReturnEvent.STOPPED) left = true
            event == AuthReturnEvent.STARTED && left
        }
    }

    private fun finish(callback: WebCodeCallback): AuthStepResult {
        attempts.clear()
        return when (callback) {
            is WebCodeCallback.Code -> AuthStepResult.Credential(AuthCredential.WebCode(callback.code))
            is WebCodeCallback.Incomplete -> AuthStepResult.Failed
        }
    }

    companion object {
        /** auth_url 白名单（D10-2717）：只放 https://oauth.taobao.com。 */
        const val AUTH_HOST: String = "oauth.taobao.com"
        private const val AUTH_SCHEME = "https"

        /** https、主机正好是 [AUTH_HOST]、不带 userinfo、不带端口；解析不了一律不放行。 */
        fun isAllowedAuthUrl(authUrl: String): Boolean {
            val uri = runCatching { URI(authUrl) }.getOrNull() ?: return false
            return uri.scheme == AUTH_SCHEME &&
                !uri.isOpaque &&
                uri.rawUserInfo == null &&
                uri.port == -1 &&
                uri.host?.lowercase() == AUTH_HOST
        }
    }
}
