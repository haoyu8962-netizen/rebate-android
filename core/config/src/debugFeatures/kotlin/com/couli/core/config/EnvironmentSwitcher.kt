package com.couli.core.config

import android.annotation.SuppressLint
import android.content.Context

/**
 * 调试用的环境切换（debug 默认 test，staging 默认 staging；03 §3.6）。只在 debug、staging 构建里编译。
 *
 * - [current]：显式选择（调试面板切换过）优先，否则构建默认。
 * - [reconcileAtStartup]：启动时与「上次生效的环境」核对，不同则先清理再记录（覆盖安装改变默认环境的情况）。
 * - [switchTo]：先跑全部清理器；清理成功后才写入新的显式选择与「已生效」记录。清理失败抛异常、什么都不写。
 */
class EnvironmentSwitcher(
    context: Context,
    private val defaultEnvironment: AppEnvironment,
    private val cleaners: EnvironmentSwitchCleanerRegistry,
) {
    private val preferences =
        context.applicationContext.getSharedPreferences(
            SharedPreferencesNamespaceCleaner.DEBUG_SELECTION_PREFERENCES,
            Context.MODE_PRIVATE,
        )

    private val reconciler = EnvironmentStartupReconciler(context, cleaners)

    /** 显式选择的环境；没切换过时为 null。 */
    val selected: AppEnvironment?
        get() = AppEnvironment.fromKey(preferences.getString(KEY_ENVIRONMENT, null))

    val current: AppEnvironment
        get() = selected ?: defaultEnvironment

    val endpoints: EnvironmentEndpoints
        get() = EnvironmentEndpointsCatalog.endpoints(current)

    val capabilities: DebugCapabilities = DebugCapabilities { current }

    /** 启动时调用：把 [current] 与上次生效的环境核对（见 [EnvironmentStartupReconciler.reconcile]）。 */
    fun reconcileAtStartup(): EnvironmentStartupReconciler.Outcome = reconciler.reconcile(current)

    /** 切到 [target]；与当前相同时不做任何事。返回是否发生了切换。清理失败抛出异常，不改变当前环境。选择同步落盘。 */
    @SuppressLint("ApplySharedPref")
    fun switchTo(target: AppEnvironment): Boolean {
        val from = current
        if (from == target) return false
        cleaners.cleanAll(from, target)
        // 先写选择、再记「已生效」：两步之间进程被杀，下次启动 current=target 而记录仍是旧环境，会再清理一次，不会漏清。
        check(preferences.edit().putString(KEY_ENVIRONMENT, target.key).commit()) { "保存环境选择失败" }
        reconciler.markApplied(target)
        return true
    }

    private companion object {
        const val KEY_ENVIRONMENT = "environment"
    }
}
