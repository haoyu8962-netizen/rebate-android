package com.couli.feature.trade

import java.security.MessageDigest

/**
 * 本机发起过的一次 web_code 尝试：只有 sha256(state)（小写十六进制）与发起时间，没有 state 原文、没有 code。
 * [accountTag]：发起时的账号标签（[webCodeAccountTag]，用户 id 的哈希；游客或没记为 null。FU-AND-TB-WEBCODE-B，设计稿 §2 ②、§6.4 第 2 步）。
 */
data class WebCodeAttempt(
    val stateSha256: String,
    val startedAtMillis: Long,
    val accountTag: String? = null,
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

    /**
     * 记下一次尝试并带上账号标签 [accountTag]（FU-AND-TB-WEBCODE-B）。
     * TODO(FU-AND-TB-WEBCODE-B 接口桩)：缺省实现丢掉标签、只走两参数的 [record]；实现方改为落盘标签（SharedPreferencesWebCodeAttempts 覆盖）。
     */
    fun record(
        state: String,
        nowMillis: Long,
        accountTag: String?,
    ) = record(state, nowMillis)

    fun current(): WebCodeAttempt?

    fun clear()

    /**
     * 只在当前记录的哈希等于 [stateSha256] 时删除（评审 S3 F2：两次尝试并存时，先结束的那次不删后一次的记录）。
     * TODO(FU-AND-TB-WEBCODE-B 接口桩)：缺省什么都不做。实现方要把缺省实现改成「current()?.stateSha256 == stateSha256 才 clear()」——
     * 授权器单测里的 MemoryWebCodeAttempts（甲写的替身）没有覆盖本函数，授权器改调本函数后靠缺省实现删记录。
     */
    fun clearIfCurrent(stateSha256: String) = Unit
}

/** sha256([state])：UTF-8 字节的小写十六进制（尝试记录、收件箱认领共用同一算法）。 */
fun webCodeStateSha256(state: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(state.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/**
 * 账号标签（设计稿 §2 ②：用户 id 的哈希）：尝试记录落盘的是它，不落用户 id 原文；冷启动 / 晚到回跳核对「发起时的账号 = 当前账号」用它比。
 * 算法同 [webCodeStateSha256]（UTF-8 字节的 sha256，小写十六进制）。
 * TODO(FU-AND-TB-WEBCODE-B 接口桩)：恒返回空串。
 */
@Suppress("UNUSED_PARAMETER", "UnusedParameter", "FunctionOnlyReturningConstant") // 接口桩，实现方替换
fun webCodeAccountTag(account: String): String = ""
