package com.couli.core.network

import okhttp3.Call

/** 隐私世代与环境世代：撤回同意、切换环境各加 1（A-R2-01）。 */
data class CallEpochs(
    val privacy: Long,
    val environment: Long,
)

/**
 * 在途调用登记（A-R2-01、lessons 22）：每个已准备好、正在等连接 / TLS / 响应的 OkHttp [Call] 都登记在这里。
 *
 * - [onPrivacyRevoked]（撤回同意清理的第一项）与 [onEnvironmentSwitching]（环境切换清理的第一项）先把对应世代加 1，再取消全部
 *   已登记的 Call：还在等连接的请求不再发出，已发出的不再等响应。
 * - 登记时世代已变的 Call 不执行；网络拦截器在连接建立后、写出请求前（[admit]）再核对一次世代与放行条件。
 *
 * App 里一个进程一份（AppModule 单例），由 [ApiClient] 与两处清理入口共用。线程安全。
 */
class InFlightCalls {
    private val lock = Any()
    private var privacy = 0L
    private var environment = 0L

    /** 在途的 Call → 调用方的归属标记（[ApiClient.cancelOwned] 用；没有为 null）。 */
    private val calls = mutableMapOf<Call, Any?>()

    /** 当前世代。 */
    fun epochs(): CallEpochs = synchronized(lock) { CallEpochs(privacy, environment) }

    /** 撤回同意：隐私世代加 1，取消全部在途请求。 */
    fun onPrivacyRevoked() = invalidate { privacy += 1 }

    /** 切换环境：环境世代加 1，取消全部在途请求。 */
    fun onEnvironmentSwitching() = invalidate { environment += 1 }

    /** 当前登记的在途 Call 数（只用于观察）。 */
    val size: Int get() = synchronized(lock) { calls.size }

    private fun invalidate(bump: () -> Unit) {
        val cancelled: List<Call>
        synchronized(lock) {
            bump()
            cancelled = calls.keys.toList()
        }
        cancelled.forEach(Call::cancel)
    }

    /** 登记一个即将执行的 Call（[tag] 为调用方的归属标记）；世代已不是 [epochs] 时不登记（调用方不执行它）。 */
    internal fun register(
        call: Call,
        epochs: CallEpochs,
        tag: Any? = null,
    ): Boolean =
        synchronized(lock) {
            if (CallEpochs(privacy, environment) != epochs) return false
            calls[call] = tag
            true
        }

    internal fun unregister(call: Call) {
        synchronized(lock) { calls -= call }
    }

    /**
     * 取消归属标记为 [tag] 的全部在途 Call（APP-05a 共性第 1 条：换号、取消、登录页出栈时同步取消这次尝试已交给传输层的请求）。
     * 调用方先让自己的归属核对失效，再调这里：还没写出的不再写出，已写出的不再等响应。
     */
    fun cancelTagged(tag: Any) {
        val cancelled = synchronized(lock) { calls.filterValues { it === tag }.keys.toList() }
        cancelled.forEach(Call::cancel)
    }

    /**
     * 写出请求前的核对：世代仍是 [epochs] 且 [check] 放行（返回 null）时放行；否则返回拒绝原因。[check] 不在锁内执行（它会读会话与
     * 刷新链，避免锁顺序反转）；核对之后再看一次世代，核对期间的撤回不会漏掉，之后的撤回由 [Call.cancel] 中止写出。
     */
    internal fun admit(
        epochs: CallEpochs,
        check: () -> String?,
    ): String? {
        val stale = { epochs() != epochs }
        return if (stale()) LocalRejectReason.CALL_CANCELLED else check() ?: LocalRejectReason.CALL_CANCELLED.takeIf { stale() }
    }
}
