package com.couli.app.login

import android.annotation.SuppressLint
import android.content.Context
import com.couli.feature.login.LastLoginMethodBackend

/**
 * 上次登录方式的持久化（APP-17a；G05、G22、G24）：独立 SharedPreferences [NAME]，唯一键 [KEY]，只存方式（不存号码、账号）。名字故意不以
 * `couli.` 开头：切换环境的命名空间清理不会抹掉它（G24）；撤回同意由 LastLoginMethodStore 登记的清理项删除。写入、删除一律同步 commit，
 * 拿到返回值确认落盘（删除失败交给撤回清理入口报错重试）。
 */
@SuppressLint("ApplySharedPref", "UseKtx") // 必须 commit 并拿到返回值（KTX 的 edit(commit = true) 丢掉返回值）
internal class LastLoginMethodPreferences(
    context: Context,
) : LastLoginMethodBackend {
    private val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    override fun read(): String? = prefs.getString(KEY, null)

    override fun write(method: String?): Boolean {
        val edit = prefs.edit()
        if (method == null) edit.remove(KEY) else edit.putString(KEY, method)
        return edit.commit()
    }

    companion object {
        const val NAME = "couli_last_login_method"
        const val KEY = "last_method"
    }
}
