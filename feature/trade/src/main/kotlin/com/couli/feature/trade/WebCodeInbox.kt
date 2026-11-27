package com.couli.feature.trade

import kotlinx.coroutines.awaitCancellation

/**
 * 回跳的进程内一次性收件箱（设计稿 §3.1、§6.3；只在内存，不落盘）：中转 Activity [deliver]，授权器按 sha256(state)（UTF-8 字节的小写十六进制）
 * [await]。回跳先到、等待后到，或等待先到、回跳后到，都能交付；只交给 state 哈希相同的等待；交付一次后清空，同一 state 再来的回跳丢弃；
 * 槽位最多保留 [SLOT_TTL_MILLIS]。
 *
 * TODO(FU-AND-TB-WEBCODE-A 实现)：接口桩，现在 [deliver] 丢弃、[await] 一直挂起。
 */
class WebCodeInbox(
    @Suppress("UnusedPrivateProperty") // 接口桩
    private val clock: () -> Long = System::currentTimeMillis,
) : WebCodeCallbackSink {
    override fun deliver(callback: WebCodeCallback) = Unit

    @Suppress("UnusedParameter") // 接口桩
    suspend fun await(stateSha256: String): WebCodeCallback = awaitCancellation()

    /** 退出登录、切换账号、切换环境时清空。 */
    fun clear() = Unit

    companion object {
        const val SLOT_TTL_MILLIS: Long = 10 * 60 * 1000L
    }
}
