package com.couli.core.config

/**
 * 切换环境时的清理入口（03 §3.6）：先清本机会话与缓存，再在新环境重新注册、重新登录。
 * 会话、令牌、LKG 配置、页面缓存、pending_action 等由各自模块以后注册进 [EnvironmentSwitchCleanerRegistry]；
 * APP-01 先注册本 App 的 SharedPreferences 命名空间（[SharedPreferencesNamespaceCleaner]）。
 * TODO(APP-03): Keystore 里的会话密钥、DataStore 文件随存储层实现后注册。
 */
fun interface EnvironmentSwitchCleaner {
    fun clean(
        from: AppEnvironment,
        to: AppEnvironment,
    )
}

/** 清理入口注册表；按注册顺序执行。 */
class EnvironmentSwitchCleanerRegistry {
    private val cleaners = mutableListOf<EnvironmentSwitchCleaner>()

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
