package com.couli.core.privacy

import android.annotation.SuppressLint
import android.content.Context
import com.couli.core.config.CouliPreferenceNamespaces

/**
 * 撤回同意的占位清理项（BR-ID-13）。会话层与设备注册还没实现，先约定它们落在 `couli.session` 里的键名，
 * 撤回时按键删除（切换环境时整个 `couli.session` 由 SharedPreferencesNamespaceCleaner 清掉，两条路都不会漏）。
 * TODO(APP-03): 设备注册（device_id、install_secret 进 Keystore）实现后换成真实清理；
 * TODO(APP-05): 会话（access_token、refresh_token、h5_token）实现后换成真实清理。
 */
object PlaceholderCredentialKeys {
    const val ACCESS_TOKEN = "access_token"
    const val REFRESH_TOKEN = "refresh_token"
    const val H5_TOKEN = "h5_token"
    const val DEVICE_ID = "device_id"
    const val INSTALL_SECRET = "install_secret"
}

/** 注册 [CredentialCleanerRegistry.REQUIRED] 三项占位清理。 */
fun CredentialCleanerRegistry.registerPlaceholderCredentialCleaners(context: Context) {
    val appContext = context.applicationContext
    register(CredentialCleanerRegistry.TOKENS) {
        removeSessionKeys(
            appContext,
            PlaceholderCredentialKeys.ACCESS_TOKEN,
            PlaceholderCredentialKeys.REFRESH_TOKEN,
            PlaceholderCredentialKeys.H5_TOKEN,
        )
    }
    register(CredentialCleanerRegistry.DEVICE_ID) { removeSessionKeys(appContext, PlaceholderCredentialKeys.DEVICE_ID) }
    register(CredentialCleanerRegistry.INSTALL_SECRET) { removeSessionKeys(appContext, PlaceholderCredentialKeys.INSTALL_SECRET) }
}

@SuppressLint("ApplySharedPref") // 撤回同意后立刻进入基本模式，凭据必须同步删掉
private fun removeSessionKeys(
    context: Context,
    vararg keys: String,
) {
    val editor = context.getSharedPreferences(CouliPreferenceNamespaces.SESSION, Context.MODE_PRIVATE).edit()
    keys.forEach { editor.remove(it) }
    check(editor.commit()) { "清除会话凭据失败：${keys.joinToString()}" }
}
