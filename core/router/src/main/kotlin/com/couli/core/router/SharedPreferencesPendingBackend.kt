package com.couli.core.router

import android.annotation.SuppressLint
import android.content.Context
import com.couli.core.config.CouliPreferenceNamespaces

/**
 * pending_action 落盘（进程被回收后可恢复，BR-ID-10 例）。SharedPreferences 名 [CouliPreferenceNamespaces.ROUTER]
 * 已登记在命名空间里：切换环境时随 SharedPreferencesNamespaceCleaner 一并清理。
 * 写入用 commit()：返回 false 即视为失败（调用方据此不进入前置页后的自动恢复）。
 */
class SharedPreferencesPendingBackend(
    context: Context,
) : PendingBackend {
    private val preferences =
        context.applicationContext.getSharedPreferences(CouliPreferenceNamespaces.ROUTER, Context.MODE_PRIVATE)

    override fun read(): String? = preferences.getString(KEY_PENDING_ACTION, null)

    @SuppressLint("ApplySharedPref")
    override fun write(value: String): Boolean = preferences.edit().putString(KEY_PENDING_ACTION, value).commit()

    @SuppressLint("ApplySharedPref")
    override fun delete(): Boolean = preferences.edit().remove(KEY_PENDING_ACTION).commit()

    companion object {
        const val KEY_PENDING_ACTION = "pending_action"
    }
}
