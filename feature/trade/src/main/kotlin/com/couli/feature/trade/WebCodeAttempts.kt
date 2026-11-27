package com.couli.feature.trade

/** 本机发起过的一次 web_code 尝试：只有 sha256(state)（小写十六进制）与发起时间，没有 state 原文、没有 code。 */
data class WebCodeAttempt(
    val stateSha256: String,
    val startedAtMillis: Long,
)

/**
 * 本机尝试记录（设计稿 §3.1、§5.5 第 1 条）：只存 1 条，新的覆盖旧的；只落 sha256(state) 与时间。
 * 实现放 App（SharedPreferencesWebCodeAttempts）。
 */
interface WebCodeAttemptStore {
    /** 记下一次尝试：存 sha256([state]) 与 [nowMillis]；[state] 原文不落盘。 */
    fun record(
        state: String,
        nowMillis: Long,
    )

    fun current(): WebCodeAttempt?

    fun clear()
}
