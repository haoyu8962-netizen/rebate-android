package com.couli.core.network

import com.couli.core.generated.ApiOperation

/**
 * 一个收到 10002、等待刷新后重放的请求。[body] 保留原始字节（UTF-8 文本），重放时原样发出；[key] 为原 Idempotency-Key。
 */
data class RefreshRequest(
    val id: String,
    val operation: ApiOperation,
    val key: String?,
    val body: String,
)

/**
 * 令牌单飞刷新（03 §4.2「Token 刷新」；BR-ID-07）：同一时间窗口里收到 10002 的请求只触发 1 次刷新，其余排队等待；
 * 刷新成功后每个等待的请求用新令牌沿用原键重放 1 次；刷新失败清会话一次，Effect 为跳登录（强更状态下只清会话、停在强更页）。
 *
 * - [run]：非阻塞地接纳请求；窗口里没有进行中的刷新时发起一次 [refresh]。
 * - [awaitRefresh]：不需要重放的刷新（受限令牌在升级后先刷新、10405 的 refresh_only），与请求共享同一窗口。
 * - 回调在刷新完成的线程上串行交付（先重放 / 清会话，再通知监听）。
 *
 * @param refresh 发起一次刷新；完成时调用传入的回调：null = 失败，非 null = 新的 access_token。
 * @param replay 用新令牌重放一个请求（沿用 [RefreshRequest.key] 与 [RefreshRequest.body]）。
 * @param clearSession 刷新失败时清会话（令牌与受限作用域）。
 * @param onFailure 刷新失败时，本窗口里等待的请求（调用方据此结束它们的等待）。
 * @param forceUpdateNow 结算那一刻是否在强更状态（lessons 20）：给了就以它为准决定失败后停在强更页还是跳登录，
 *   不用入队时捕获的值；没给（三端向量接线）按入队时传入的 forceUpdate。
 */
class RefreshCoordinator(
    private val refresh: ((String?) -> Unit) -> Unit,
    private val replay: (RefreshRequest, String) -> Unit,
    private val clearSession: () -> Unit,
    private val clock: () -> Long,
    private val onFailure: (List<RefreshRequest>) -> Unit = {},
    private val forceUpdateNow: (() -> Boolean)? = null,
) {
    private val lock = Any()
    private val waiting = mutableListOf<RefreshRequest>()
    private val listeners = mutableListOf<(String?) -> Unit>()
    private var inFlight = false
    private var forceUpdateInWindow = false
    private var window = 0

    /** 实际发起的刷新次数。 */
    @Volatile
    var refreshCalls: Int = 0
        private set

    /** 已重放的请求数。 */
    @Volatile
    var replayed: Int = 0
        private set

    /** 最近一次刷新的结果：none / replayed / clear_session_login / clear_session_stay_force_update。 */
    @Volatile
    var effectRaw: String = EFFECT_NONE
        private set

    /** 最近一次刷新完成的时刻（毫秒）；还没完成过为 null。 */
    @Volatile
    var lastCompletedAtMillis: Long? = null
        private set

    /** 结算窗口时抛错的回调数（只用于观察）。 */
    @Volatile
    var failures: Int = 0
        private set

    /** 是否有进行中的刷新。 */
    val refreshing: Boolean get() = synchronized(lock) { inFlight }

    /** 接纳已收到 10002 的请求。[forceUpdate] = 当前在强更状态（刷新失败时只清会话、停在强更页）。 */
    fun run(
        requests: List<RefreshRequest>,
        forceUpdate: Boolean,
    ) {
        if (requests.isEmpty()) return
        enqueue(forceUpdate) { waiting += requests }
    }

    /** 等待（必要时发起）一次刷新，不重放任何请求。[listener] 收到新令牌或 null（失败）。 */
    fun awaitRefresh(
        forceUpdate: Boolean,
        listener: (String?) -> Unit,
    ) = enqueue(forceUpdate) { listeners += listener }

    private fun enqueue(
        forceUpdate: Boolean,
        add: () -> Unit,
    ) {
        val start: Int?
        synchronized(lock) {
            add()
            forceUpdateInWindow = forceUpdateInWindow || forceUpdate
            start =
                if (inFlight) {
                    null
                } else {
                    inFlight = true
                    refreshCalls += 1
                    window += 1
                    window
                }
        }
        if (start != null) startRefresh(start)
    }

    /** 发起刷新；发起本身抛错（线程池拒绝等）按刷新失败结算窗口，不留下永远在途的窗口（R1-08）。 */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // 发起失败即按刷新失败结算
    private fun startRefresh(windowId: Int) {
        try {
            refresh { token -> complete(windowId, token) }
        } catch (error: Exception) {
            complete(windowId, null)
        }
    }

    private fun complete(
        windowId: Int,
        token: String?,
    ) {
        val batch: List<RefreshRequest>
        val notify: List<(String?) -> Unit>
        val queuedForceUpdate: Boolean
        synchronized(lock) {
            // 同一窗口只完成一次（刷新回调被重复调用时忽略）
            if (!inFlight || windowId != window) return
            batch = waiting.toList()
            notify = listeners.toList()
            waiting.clear()
            listeners.clear()
            queuedForceUpdate = forceUpdateInWindow
            forceUpdateInWindow = false
            inFlight = false
        }
        // 窗口已在锁内结算（inFlight = false）；回调各自隔离：某个回调抛错（如清会话时写盘失败、时钟或强更状态读取失败）
        // 不妨碍通知其余等待者，每个等待者结算且只结算一次（R1-08、lessons 17）
        settle { lastCompletedAtMillis = clock() }
        if (token != null) {
            batch.forEach {
                settle { replay(it, token) }
                replayed += 1
            }
            effectRaw = EFFECT_REPLAYED
        } else {
            settle(clearSession)
            var forceUpdate = queuedForceUpdate
            settle { forceUpdate = forceUpdateNow?.invoke() ?: queuedForceUpdate }
            effectRaw = if (forceUpdate) ErrorEffectKind.CLEAR_SESSION_STAY_FORCE_UPDATE.raw else ErrorEffectKind.CLEAR_SESSION_LOGIN.raw
            settle { onFailure(batch) }
        }
        notify.forEach { listener -> settle { listener(token) } }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // 任何回调异常都不能留下未通知的等待者（只计数）
    private fun settle(action: () -> Unit) {
        try {
            action()
        } catch (error: Exception) {
            failures += 1
        }
    }

    companion object {
        const val EFFECT_NONE = "none"
        const val EFFECT_REPLAYED = "replayed"
    }
}
