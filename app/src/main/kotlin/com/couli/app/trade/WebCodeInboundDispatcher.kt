package com.couli.app.trade

import android.os.Handler
import android.os.Looper
import com.couli.feature.trade.WebCodeCallback
import com.couli.feature.trade.WebCodeCallbackSink
import com.couli.feature.trade.WebCodeInbox
import com.couli.feature.trade.webCodeStateSha256

/**
 * 入站中转交付回跳的落点（FU-AND-TB-WEBCODE-B，设计稿 §6.4「什么时候触发」）：InboundSchemeActivity 经 Application 的
 * webCodeCallbackSink() 拿到它。[deliver] 当场交给 [inbox]（热路径的等待者照旧认领），再在主线程的下一轮循环（[post]）里用
 * [WebCodeInbox.takeUnclaimed] 取这条回跳：仍没有等待者认领 → 交给 [orphans]（App 接 AppTrade.onUnclaimedWebCode）；已被认领 → 什么都不做。
 * 只在内存里经手回跳，不保存、不进日志。
 */
class WebCodeInboundDispatcher(
    private val inbox: WebCodeInbox,
    private val orphans: (WebCodeCallback) -> Unit,
    private val post: (Runnable) -> Unit = { Handler(Looper.getMainLooper()).post(it) },
) : WebCodeCallbackSink {
    override fun deliver(callback: WebCodeCallback) {
        inbox.deliver(callback)
        val stateSha256 = webCodeStateSha256(callback.state)
        post(Runnable { inbox.takeUnclaimed(stateSha256)?.let(orphans) })
    }
}
