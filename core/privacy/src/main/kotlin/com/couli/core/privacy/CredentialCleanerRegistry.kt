package com.couli.core.privacy

/**
 * 撤回同意时的本地清理入口（BR-ID-13：清除本地令牌、device_id、install_secret 后进入基本模式）。
 * 与 APP-01 的 EnvironmentSwitchCleanerRegistry 同一种做法：各模块把自己的清理项注册进来，[cleanAll] 依次执行。
 *
 * 会话与设备注册还不存在：App 先注册三个占位清理项（[TOKENS]、[DEVICE_ID]、[INSTALL_SECRET]），
 * 各自清 `couli.session` 里约定的键；TODO(APP-03)/TODO(APP-05): 会话、Keystore、设备注册实现后换成真实清理。
 */
class CredentialCleanerRegistry {
    private val cleaners = mutableListOf<Pair<String, () -> Unit>>()

    @Synchronized
    fun register(
        name: String,
        cleaner: () -> Unit,
    ) {
        cleaners += name to cleaner
    }

    @Synchronized
    fun registeredNames(): List<String> = cleaners.map { it.first }

    /** 依次执行全部清理；某一项抛错不影响其余项，最后把第一个错误抛出。 */
    fun cleanAll() {
        val snapshot = synchronized(this) { cleaners.toList() }
        val failures = snapshot.mapNotNull { (_, cleaner) -> runCatching(cleaner).exceptionOrNull() }
        failures.firstOrNull()?.let { throw it }
    }

    companion object {
        /** access_token、refresh_token、h5_token。 */
        const val TOKENS = "tokens"
        const val DEVICE_ID = "device_id"
        const val INSTALL_SECRET = "install_secret"

        /** BR-ID-13 要求撤回时必须清的三项；App 启动时核对都已注册（漏注册 = 漏删）。 */
        val REQUIRED: List<String> = listOf(TOKENS, DEVICE_ID, INSTALL_SECRET)
    }
}
