package com.couli.core.config

/**
 * 切换环境时的清理入口（03 §3.6）：先清本机会话与缓存，再在新环境重新注册、重新登录。
 * 会话、令牌、LKG 配置、页面缓存、pending_action 等由各自模块以后注册进 [EnvironmentSwitchCleanerRegistry]；
 * APP-01 先注册本 App 的 SharedPreferences 命名空间（[SharedPreferencesNamespaceCleaner]）。
 * APP-03a 起 App 另注册会话令牌清除与 Keystore 会话密钥删除（AppModule）；APP-03b 注册设备凭据、LKG、埋点队列。
 */
fun interface EnvironmentSwitchCleaner {
    fun clean(
        from: AppEnvironment,
        to: AppEnvironment,
    )
}

/**
 * 运行中切换环境（调试面板）的生命周期（APP-03b R1-05、lessons 10）：[onSwitchStarting] 在任何清理之前同步调用（App 关环境闸门：
 * 清理与提交期间不注册设备、不发任何请求）；[onSwitchFinished] 在新环境的选择与「已生效」记录都提交之后（[committed] 为 true），
 * 或清理 / 提交失败、环境保持不变之后（false）调用。启动对账（[EnvironmentStartupReconciler]）不经过这里：闸门本来就在对账成功后才打开。
 */
interface EnvironmentSwitchListener {
    fun onSwitchStarting()

    fun onSwitchFinished(committed: Boolean)
}

/** 清理入口注册表；按注册顺序执行。 */
class EnvironmentSwitchCleanerRegistry {
    private val cleaners = mutableListOf<EnvironmentSwitchCleaner>()
    private val listeners = mutableListOf<EnvironmentSwitchListener>()

    @Synchronized
    fun addSwitchListener(listener: EnvironmentSwitchListener) {
        listeners += listener
    }

    /**
     * 运行中切换环境：先通知开始（关闸门），再清理（[cleanAll]），再 [commit] 新环境；最后通知结束。清理或提交失败照样通知结束
     * （committed = false），异常抛给调用方。
     */
    fun switching(
        from: AppEnvironment,
        to: AppEnvironment,
        commit: () -> Unit,
    ) {
        val current = synchronized(this) { listeners.toList() }
        current.forEach { it.onSwitchStarting() }
        var committed = false
        try {
            cleanAll(from, to)
            commit()
            committed = true
        } finally {
            current.forEach { it.onSwitchFinished(committed) }
        }
    }

    @Synchronized
    fun register(cleaner: EnvironmentSwitchCleaner) {
        cleaners += cleaner
    }

    @Synchronized
    fun registered(): List<EnvironmentSwitchCleaner> = cleaners.toList()

    /** 依次执行全部清理；某一项抛错不影响其余项，最后把第一个错误抛出。 */
    fun cleanAll(
        from: AppEnvironment,
        to: AppEnvironment,
    ) {
        val failures =
            registered().mapNotNull { cleaner ->
                runCatching { cleaner.clean(from, to) }.exceptionOrNull()
            }
        failures.firstOrNull()?.let { throw it }
    }
}
