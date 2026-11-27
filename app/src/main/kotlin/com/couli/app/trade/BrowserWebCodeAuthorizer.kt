package com.couli.app.trade

import com.couli.feature.trade.AuthStepResult
import com.couli.feature.trade.WebCodeAttemptStore
import com.couli.feature.trade.WebCodeAuthorizer
import com.couli.feature.trade.WebCodeInbox

/**
 * 淘宝 web_code 的真实授权器（设计稿 §2 ①–④、§5.5、§6.1、§6.3；D10-2717 定值）：
 * ① auth_url 只放行 https 的 oauth.taobao.com（不带 userinfo），不在白名单返回 Failed、不打开；
 * ② 记下本机尝试（只存 sha256(state) 与 [clock] 时间）；
 * ③ 经 [launcher] 打开，打不开返回 Failed；
 * ④ 等 [inbox] 里 state 哈希匹配的回跳（Code → Credential 并删记录；error=1 → Failed 并删记录），或 [returns] 先 STOPPED 再 STARTED
 *    之后（再让一轮）仍无回跳 → Cancelled（记录保留）。不另设本地计时器。等待里的「再让一轮」用调用方协程上下文，不直接取 Dispatchers.Main。
 * [available] 恒为 true（release 与关着假服务的 debug / staging 都能执行）。
 *
 * TODO(FU-AND-TB-WEBCODE-A 实现)：接口桩，现在 available=false、authorize 恒返回 Failed。
 */
@Suppress("UnusedPrivateProperty") // 接口桩
class BrowserWebCodeAuthorizer(
    private val launcher: AuthBrowserLauncher,
    private val inbox: WebCodeInbox,
    private val attempts: WebCodeAttemptStore,
    private val returns: AuthReturnSignal,
    private val clock: () -> Long = System::currentTimeMillis,
) : WebCodeAuthorizer {
    override val available: Boolean get() = false

    override suspend fun authorize(
        authUrl: String,
        state: String,
    ): AuthStepResult = AuthStepResult.Failed
}
