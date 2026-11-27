package com.couli.app.trade

import android.content.SharedPreferences
import com.couli.feature.trade.WebCodeAttempt
import com.couli.feature.trade.WebCodeAttemptStore

/**
 * 本机尝试记录的 SharedPreferences 实现（设计稿 §3.1、§8）：只写 sha256(state)（小写十六进制）与发起时间；不写 state 原文、不写 code。
 * 命名空间由 App 装配时给（[prefs]）。
 *
 * TODO(FU-AND-TB-WEBCODE-A 实现)：接口桩，现在什么都不存。
 */
class SharedPreferencesWebCodeAttempts(
    @Suppress("UnusedPrivateProperty") // 接口桩
    private val prefs: SharedPreferences,
) : WebCodeAttemptStore {
    override fun record(
        state: String,
        nowMillis: Long,
    ) = Unit

    override fun current(): WebCodeAttempt? = null

    override fun clear() = Unit
}
