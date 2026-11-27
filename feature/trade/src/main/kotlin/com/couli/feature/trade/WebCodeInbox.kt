package com.couli.feature.trade

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * 回跳的进程内一次性收件箱（设计稿 §3.1、§6.3；只在内存，不落盘）：中转 Activity [deliver]，授权器按 sha256(state)（UTF-8 字节的小写十六进制）
 * [await]。回跳先到、等待后到，或等待先到、回跳后到，都能交付；只交给 state 哈希相同的等待；交付一次后清空，同一 state 再来的回跳丢弃；
 * 槽位最多保留 [SLOT_TTL_MILLIS]。
 * - 单槽：没有等待认领的回跳放进槽位，新的覆盖旧的；state 不符的等待不吞掉它（伪造的回跳也不打断真实等待）。
 * - 被取消的等待当场注销；回跳已交到它手里、它却在拿到之前被取消时，放回槽位（留给晚到回跳的处理）。
 * 线程安全（中转 Activity 在主线程交付，等待方在调用方协程）。
 */
class WebCodeInbox(
    private val clock: () -> Long = System::currentTimeMillis,
) : WebCodeCallbackSink {
    private class Slot(
        val stateSha256: String,
        val callback: WebCodeCallback,
        val deliveredAtMillis: Long,
    )

    private class Waiter(
        val stateSha256: String,
        val result: CompletableDeferred<WebCodeCallback> = CompletableDeferred(),
    )

    private val lock = Any()
    private var slot: Slot? = null
    private val waiters = mutableListOf<Waiter>()

    /** 已经交付过的 state 哈希 → 交付时间（同一 state 再来的回跳丢弃；超过 [SLOT_TTL_MILLIS] 的记录剪掉）。 */
    private val consumed = LinkedHashMap<String, Long>()

    override fun deliver(callback: WebCodeCallback) {
        val hash = webCodeStateSha256(callback.state)
        synchronized(lock) {
            val now = clock()
            prune(now)
            if (hash in consumed) return
            val waiter = waiters.firstOrNull { it.stateSha256 == hash }
            if (waiter != null) {
                waiters.remove(waiter)
                consumed[hash] = now
                waiter.result.complete(callback)
            } else {
                slot = Slot(hash, callback, now)
            }
        }
    }

    suspend fun await(stateSha256: String): WebCodeCallback {
        val waiter = Waiter(stateSha256)
        synchronized(lock) {
            val now = clock()
            prune(now)
            val held = slot
            if (held != null && held.stateSha256 == stateSha256) {
                slot = null
                consumed[stateSha256] = now
                return held.callback
            }
            waiters += waiter
        }
        try {
            return waiter.result.await()
        } catch (cancelled: CancellationException) {
            synchronized(lock) {
                waiters.remove(waiter)
                if (waiter.result.isCompleted && !waiter.result.isCancelled) {
                    // 已交到手里却没来得及拿：放回槽位，不让这条回跳丢失
                    consumed.remove(stateSha256)
                    slot = Slot(stateSha256, waiter.result.getCompleted(), clock())
                }
            }
            throw cancelled
        }
    }

    /** 退出登录、切换账号、切换环境时清空。 */
    fun clear() {
        synchronized(lock) {
            slot = null
            consumed.clear()
        }
    }

    private fun prune(now: Long) {
        if (slot?.let { now - it.deliveredAtMillis > SLOT_TTL_MILLIS } == true) slot = null
        consumed.entries.removeAll { (_, at) -> now - at > SLOT_TTL_MILLIS }
    }

    companion object {
        const val SLOT_TTL_MILLIS: Long = 10 * 60 * 1000L
    }
}
