package com.couli.app

import android.os.Handler
import android.os.Looper
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.PageApi
import com.couli.feature.wallet.WalletApi
import java.util.concurrent.TimeUnit

/**
 * F-AND-36（FU-AND-0716）：App 集成测试的统一等待——等流程交回结论，不用固定轮次 / 固定时长猜后台线程（MockWebServer、OkHttp、IO 协程）
 * 什么时候做完。
 * - [CEILING_MS] 只是「结论一直不来」时判失败的上限，到上限抛 AssertionError（带调用方给的现场），不挂死；结论来了立即返回。
 * - 每一轮先 [pump]（主线程队列 + Compose 空闲），再查条件。
 * - 第 1 轮评审 R1-02：不再用「网络层没有在途调用 + 主线程空闲」推测结论（传输层在交回业务层之前就注销在途记录，业务解析、后续请求与主线程结算
 *   都可能还没跑）。等待只看流程自己交回的完成信号：任务（Job）结束、页面结算序号、或 [FlowLedger] 记下的业务回调结束。
 */
internal object FlowWait {
    const val CEILING_MS = 30_000L
    const val POLL_MS = 10L

    fun until(
        pump: () -> Unit,
        detail: () -> String = { "" },
        ceilingMs: Long = CEILING_MS,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ceilingMs)
        while (true) {
            pump()
            if (condition()) return
            if (System.nanoTime() >= deadline) throw AssertionError("等待超时（$ceilingMs 毫秒）：${detail()}")
            Thread.sleep(POLL_MS)
        }
    }

    /** 等 [ledger] 里满足 [matches] 的业务调用至少有 [count] 次连同调用方的回调处理都已结束（见 [FlowLedger]）。 */
    fun settled(
        ledger: FlowLedger,
        count: Int,
        pump: () -> Unit,
        detail: () -> String = { "" },
        matches: (ApiCall) -> Boolean,
    ) = until(pump, { "已结算 ${ledger.settled(matches)}/$count；${detail()}" }) { ledger.settled(matches) >= count }
}

/**
 * 业务调用的完成记录（F-AND-36，R1-02）：包在页面 / 执行器实际使用的 API 端口外面。一次调用从发起到「交回调用方、调用方这条主线程消息里的回调处理
 * 也执行完」才算结算：端口返回时在调用方的续体里往主线程队列尾部投一条记录消息，它只能排在调用方这次回调处理（以及处理期间投递的主线程消息）之后执行。
 * 适用于调用方在主线程协程里调用（AppWallet、提现页的作用域都是 Main.immediate）；网络层注销在途记录、IO 线程被延迟调度、调用方续体尚未恢复时
 * 都不会记为结算。
 */
internal class FlowLedger {
    private val main = Handler(Looper.getMainLooper())
    private val started = mutableListOf<ApiCall>()
    private val done = mutableListOf<ApiCall>()

    fun started(matches: (ApiCall) -> Boolean): Int = synchronized(this) { started.count(matches) }

    fun settled(matches: (ApiCall) -> Boolean): Int = synchronized(this) { done.count(matches) }

    suspend fun around(
        call: ApiCall,
        block: suspend () -> ApiResult,
    ): ApiResult {
        synchronized(this) { started += call }
        try {
            return block()
        } finally {
            main.post { synchronized(this) { done += call } }
        }
    }

    /** 页面端口（FU-AND-0716 窄修二：订单列表 / 详情等经 [PageApi.send] 的读取，调用方作用域同为 Main.immediate）。 */
    fun page(inner: PageApi): PageApi =
        object : PageApi by inner {
            override suspend fun send(call: ApiCall): ApiResult = around(call) { inner.send(call) }
        }

    /** 钱包端口（AppWallet 的实名查询、提现页的 rules / 估算都经它的 send）。 */
    fun wallet(inner: WalletApi): WalletApi =
        object : WalletApi by inner {
            override suspend fun send(call: ApiCall): ApiResult = around(call) { inner.send(call) }
        }
}
