package com.couli.app.web

import android.content.Context
import com.couli.core.bridge.TrustedAuthSync
import com.couli.core.bridge.WebAuthIdentity
import com.couli.core.network.ApiClient
import com.couli.core.network.ErrorEffect
import com.couli.core.network.ErrorEffectKind
import com.couli.core.network.SessionIdentity
import com.couli.feature.web.AndroidTrustedWebStore
import com.couli.feature.web.AndroidWebDataJournal
import com.couli.feature.web.AndroidWebDataOwnerRecord
import com.couli.feature.web.TrustedWebViewFactory
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * App 的可信 WebView 切号入口（F-X-25 v2）：连到全 App 唯一的 [TrustedAuthSync] 与可信 WebView 工厂。
 * - 保存新会话之前（ApiClient 的 beforeSessionSave 钩子，[beforeSessionSave]）：账号变了先持久登记待清（S1-01）；
 * - 会话保存、刷新、清除（onSessionStored 钩子，[sessionHook]）：在保存 / 清除的同步区里送一份身份快照（I1）；
 * - 退出登录（[onLoggedOut]）、刷新被拒 / 10404 确实清掉了本世代会话（[effectHook]）、切换环境、撤回同意：[sessionCleared]，不读凭据（I5）；
 * - 每个可信容器经 [syncForContainer] 取到同一份协调器（顺带按此刻的快照比对一次），经 [webViews] 取同一个工厂。
 * 协调器的基线来自持久的「可信 Web 数据归属」记录（S0-02），不靠进程内第一次快照。
 * [NONE] 只给不建可信容器的单元测试构造（Hilt 注入的永远是真实的一份）。
 */
class TrustedWebAuth(
    private val sync: Provider<TrustedAuthSync>?,
    private val client: Provider<ApiClient>?,
    private val factory: Provider<TrustedWebViewFactory>? = null,
) {
    /** 会话保存 / 刷新 / 清除之后（同步区）：把此刻的身份快照交给协调器；账号变化或登出时可信页面在返回前已隔离。 */
    fun onSessionStored() {
        val coordinator = sync?.get() ?: return
        val api = client?.get() ?: return
        coordinator.observe(api.sessionIdentity().toWebAuth())
    }

    /** 保存新会话之前（网络线程、保存锁内）：账号与当前归属不同时先隔离并持久写入待清标记（S1-01）。 */
    fun beforeSessionSave(account: String?): Boolean = sync?.get()?.beforeSessionSave(account) ?: true

    /** 明确清除了会话（不读凭据，I5）。 */
    fun sessionCleared(reason: String) {
        sync?.get()?.sessionCleared(reason)
    }

    /** 退出登录：原会话确已结束（本机清除、或已被别处清掉）时调，不读凭据（I5、S0-02）。 */
    fun onLoggedOut() = sessionCleared(REASON_LOGOUT)

    /** 包一层 ApiClient 的 onSessionStored 钩子：先同步交身份快照，再执行 App 原有的通知（[then]）。 */
    fun sessionHook(then: (generation: Long) -> Unit): (Long) -> Unit =
        { generation ->
            onSessionStored()
            then(generation)
        }

    /**
     * 包一层 ApiClient 的 onEffect 钩子：清掉了此刻这一代会话的 Effect（世代由网络层在清理的同步区里定下）先登记可信 Web 数据待清理。
     * 10404（服务端不再认这一登录）是 Android 上账号注销完成后的唯一本机终点——注销申请进入冷静期时会话保留，冷静期满账号删除后，下一次
     * 调用 / 刷新收到 10404 由网络层清会话——原因记 account_deleted；其余清会话（刷新被拒等）记 session_rejected。原因只作来源说明，
     * 协调器对任何明确清除一视同仁。
     */
    fun effectHook(
        currentEpoch: () -> Long,
        then: (ErrorEffect) -> Unit,
    ): (ErrorEffect) -> Unit =
        { effect ->
            if (effect.clearedSession(currentEpoch())) {
                if (effect.code == LOGIN_GONE) sessionCleared(REASON_ACCOUNT_DELETED) else sessionCleared(REASON_SESSION_REJECTED)
            }
            then(effect)
        }

    /** 可信容器用的协调器；先按此刻的身份快照比对一次（同一身份无动作）。 */
    fun syncForContainer(): TrustedAuthSync? {
        val coordinator = sync?.get() ?: return null
        client?.get()?.let { coordinator.observe(it.sessionIdentity().toWebAuth()) }
        return coordinator
    }

    /** 可信容器用的 WebView 工厂（建好即挂在当前可信存储上，D10-310 (6)）。 */
    fun webViews(): TrustedWebViewFactory? = factory?.get()

    companion object {
        val NONE = TrustedWebAuth(null, null)

        /** 持久待清标记、归属记录、存储状态的文件名（noBackupFilesDir 下）。 */
        private const val STORE_NAME = "app"

        /** 每一步（A 的执行确认、拆除确认、F 路径每类清理）的等待上限；换存储、新建页为它的 10 倍。 */
        private const val STEP_TIMEOUT_MS = 10_000L
        const val REASON_LOGOUT = "logout"
        const val REASON_ACCOUNT_DELETED = "account_deleted"
        const val REASON_SESSION_REJECTED = "session_rejected"

        /** 10404：refresh_token 失效 / 登录已不被服务端承认（含注销完成的账号）。 */
        private const val LOGIN_GONE = 10404

        /** App 唯一的可信存储（P：每次切号换新配置文件；F：先拆后清默认配置文件；路径首次创建时判定并写盘）。 */
        fun store(context: Context): AndroidTrustedWebStore = AndroidTrustedWebStore(context, STORE_NAME, STEP_TIMEOUT_MS)

        /** App 唯一的可信 WebView 工厂。 */
        fun factory(store: AndroidTrustedWebStore): TrustedWebViewFactory = TrustedWebViewFactory(store, store.profiles)

        /** App 唯一的协调器：noBackup 下的持久标记与归属记录、可信存储，在主线程上执行。 */
        fun create(
            context: Context,
            store: AndroidTrustedWebStore,
        ): TrustedAuthSync =
            TrustedAuthSync(
                CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                AndroidWebDataJournal(context, STORE_NAME),
                AndroidWebDataOwnerRecord(context, STORE_NAME),
                store,
                STEP_TIMEOUT_MS,
            )

        private fun ErrorEffect.clearedSession(currentEpoch: Long): Boolean {
            val clears = kind == ErrorEffectKind.CLEAR_SESSION_LOGIN || kind == ErrorEffectKind.CLEAR_SESSION_STAY_FORCE_UPDATE
            return clears && sessionEpoch != null && sessionEpoch == currentEpoch
        }
    }
}

/** ApiClient 的身份快照 → 切号判定用的身份（同一份快照的账号、登录世代、是否已知）。 */
internal fun SessionIdentity.toWebAuth(): WebAuthIdentity = WebAuthIdentity(known, account, loginEpoch)
