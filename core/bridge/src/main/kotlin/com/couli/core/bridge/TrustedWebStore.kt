package com.couli.core.bridge

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 可信容器的 Web 数据存储（F-X-25 v2，D10-309）。可信 WebView 一律建在 [current] 上（Android：P 路径 = 一个 WebView 配置文件名；
 * F 路径 = 默认配置文件 [ClearingTrustedWebStore.DEFAULT_STORE]）。
 * - [rotate]：只在全部可信 WebView 已销毁并确认之后调用。P：新建一个空的配置文件（新名先持久化再创建）并改为活动存储，旧的进待删；
 *   F：按 [TrustedWebDataKind] 清默认配置文件。[completed] 的 true 只在换好 / 清完且可证明时给出；false、抛错、不回调都算失败（I2）。
 * - [discardPending]：删除退役的存储（P：旧配置文件）。true = 已无残留；false = 有删不掉的（仍被占用），留待下次（冷启动）再删。
 * 外部容器（ExternalWebView 的独立配置文件）从不在这里（I6）。
 */
interface TrustedWebStore {
    fun current(): String

    fun rotate(completed: (Boolean) -> Unit)

    fun discardPending(completed: (Boolean) -> Unit)
}

/** F 路径清理的四类数据（sessionStorage 随旧 WebView 实例消失，不单列；旧文档已销毁，没有页面可以执行清理脚本）。顺序固定。 */
enum class TrustedWebDataKind { COOKIE, LOCAL_STORAGE, INDEXED_DB, HTTP_CACHE }

/** 清一类可信 Web 数据（只给 F 路径存储用）：[completed] 的 true 只在该类已完成且已证实成功时给出；false、抛错、不回调都按失败（I2）。 */
fun interface TrustedWebDataCleaner {
    fun clear(
        kind: TrustedWebDataKind,
        completed: (Boolean) -> Unit,
    )
}

/**
 * F 路径（系统 WebView 不支持多配置文件）：可信容器用默认配置文件，[current] 恒为 [DEFAULT_STORE]。[rotate] 在最后一个可信 WebView
 * 销毁确认之后先等宽限期 [graceMillis]（D10-310 (2)：给隔离前已在途的 keepalive / beacon 的迟到 Set-Cookie 落地，随后一并清掉），
 * 再按 [TrustedWebDataKind] 顺序逐类清理；每类都要在 [timeoutMillis] 内拿到 true，false / 抛错 / 超时任一出现即整体失败。
 * 迟到的回调落在已结束的那一次上，不算数。新的 [rotate] 作废尚未结束的上一次（它按失败结束）。没有可删的退役存储：[discardPending] 立即 true。
 */
class ClearingTrustedWebStore(
    private val scope: CoroutineScope,
    private val cleaner: TrustedWebDataCleaner,
    private val timeoutMillis: Long,
    private val graceMillis: Long = GRACE_MS,
) : TrustedWebStore {
    private val lock = Any()
    private var running: Job? = null

    override fun current(): String = DEFAULT_STORE

    override fun rotate(completed: (Boolean) -> Unit) {
        val once = OnceCallback(completed)
        synchronized(lock) {
            running?.cancel()
            running =
                scope.launch {
                    try {
                        once(clearAll())
                    } finally {
                        // 被新的一次作废、所在作用域被取消：按失败结束（只结算一次）
                        once(false)
                    }
                }
        }
    }

    override fun discardPending(completed: (Boolean) -> Unit) = completed(true)

    private suspend fun clearAll(): Boolean {
        delay(graceMillis)
        for (kind in TrustedWebDataKind.entries) {
            if (!awaitSignal(timeoutMillis) { done -> cleaner.clear(kind, done) }) return false
        }
        return true
    }

    companion object {
        /** F 路径的存储 id：默认配置文件。 */
        const val DEFAULT_STORE = "default"

        /** 最后一个可信 WebView 销毁后、开始清理前的宽限期（D10-310 (2)）。 */
        const val GRACE_MS = 2_000L
    }
}

/** 只交出第一次结果的回调（之后的调用一律忽略）。 */
internal class OnceCallback(
    private val callback: (Boolean) -> Unit,
) {
    private val done =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    operator fun invoke(value: Boolean) {
        if (done.compareAndSet(false, true)) callback(value)
    }
}

/**
 * 发起一次带完成信号的操作并等它（I2）：信号为 true 才算成功；false、发起时抛错、[timeout] 内没有信号都算失败。迟到的信号只落在这次的
 * 一次性结果上，不会被后来的尝试读到。
 */
@Suppress("TooGenericExceptionCaught", "SwallowedException") // 发起时抛错 = 这一步失败（不是成功），由调用方按失败处理
internal suspend fun awaitSignal(
    timeout: Long,
    start: (done: (Boolean) -> Unit) -> Unit,
): Boolean {
    // 发起前先查取消（评审 v2 r2 S1-01）：已被作废的一轮不再发起任何一步
    currentCoroutineContext().ensureActive()
    val result = CompletableDeferred<Boolean>()
    try {
        start { value -> result.complete(value) }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        return false
    }
    return withTimeoutOrNull(timeout) { result.await() } ?: false
}
