package com.couli.app.trade

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.couli.core.config.CouliPreferenceNamespaces
import com.couli.feature.trade.WebCodeAttempt
import com.couli.feature.trade.WebCodeAttemptStore
import com.couli.feature.trade.webCodeStateSha256

/**
 * 本机尝试记录的 SharedPreferences 实现（设计稿 §3.1、§8）：只写 sha256(state)（小写十六进制）、发起时间与账号标签（用户 id 的哈希，
 * 游客不写）；不写 state 原文、不写 code、不写用户 id 原文。只存 1 条，新的覆盖旧的（旧标签不留）。
 * 命名空间由 App 装配时给（[prefs]；生产为 CouliPreferenceNamespaces.WEBCODE_ATTEMPTS，切换环境时随命名空间清理）。
 */
class SharedPreferencesWebCodeAttempts(
    private val prefs: SharedPreferences,
) : WebCodeAttemptStore {
    override fun record(
        state: String,
        nowMillis: Long,
    ) = record(state, nowMillis, null)

    override fun record(
        state: String,
        nowMillis: Long,
        accountTag: String?,
    ) {
        prefs.edit {
            clear()
            putString(KEY_STATE_SHA256, webCodeStateSha256(state))
            putLong(KEY_STARTED_AT, nowMillis)
            accountTag?.let { putString(KEY_ACCOUNT_TAG, it) }
        }
    }

    override fun current(): WebCodeAttempt? {
        val hash = prefs.getString(KEY_STATE_SHA256, null)?.takeIf { SHA256_HEX.matches(it) && prefs.contains(KEY_STARTED_AT) }
        return hash?.let { WebCodeAttempt(it, prefs.getLong(KEY_STARTED_AT, 0L), prefs.getString(KEY_ACCOUNT_TAG, null)) }
    }

    override fun clear() {
        prefs.edit { clear() }
    }

    private companion object {
        const val KEY_STATE_SHA256 = "state_sha256"
        const val KEY_STARTED_AT = "started_at"
        const val KEY_ACCOUNT_TAG = "account_tag"
        val SHA256_HEX = Regex("[0-9a-f]{64}")
    }
}

/** 淘宝 web_code 授权的本机尝试记录（FU-AND-TB-WEBCODE-A）：只存 sha256(state) 与时间；切换环境时随 couli. 命名空间清理。 */
internal fun webCodeAttemptPreferences(context: Context): SharedPreferences =
    context.applicationContext.getSharedPreferences(CouliPreferenceNamespaces.WEBCODE_ATTEMPTS, Context.MODE_PRIVATE)
