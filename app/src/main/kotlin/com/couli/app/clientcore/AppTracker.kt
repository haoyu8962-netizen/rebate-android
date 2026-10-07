package com.couli.app.clientcore

import com.couli.core.clientcore.PeriodicScheduler
import com.couli.core.clientcore.TrackerQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tracker 的启动接线（APP-03b R2-04，03 §4.7）：真实冷启动里在环境闸门就绪、隐私冷启动之后显式取得 [TrackerQueue]（读回上次留下的
 * 队列）并启动每 10 秒的定时上报；不依赖清理器里的 Provider 懒加载。环境闸门阻断时不取（不读旧环境的队列），闸门【重试】成功后由
 * MainActivity 补。只生效一次。
 */
class AppTracker(
    private val queue: () -> TrackerQueue,
    private val scheduler: PeriodicScheduler,
    private val environmentSettled: () -> Boolean,
) {
    private val started = AtomicBoolean(false)

    /** 冷启动（Application.onCreate、闸门【重试】成功后各调一次）。 */
    fun startIfReady() {
        if (environmentSettled() && started.compareAndSet(false, true)) queue().startPeriodicFlush(scheduler)
    }
}
