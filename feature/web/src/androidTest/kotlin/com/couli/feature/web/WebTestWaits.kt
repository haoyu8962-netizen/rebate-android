package com.couli.feature.web

import android.os.SystemClock
import android.webkit.WebView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * F-AND-31（FU-AND-0716）：feature/web 仪器测试的统一等待——不用固定时长 / 固定轮次猜页面何时就绪，等页面或桥 / 容器回调交回结论。
 * - [CEILING_MS] 只是「条件一直不满足」时判失败的上限（满足即返回），不是通过条件；到上限抛 AssertionError（带等待名与最后状态），不挂死。
 * - 读页面状态的探测（evaluateJavascript）在主框架提交新文档、渲染繁忙时会迟到，回调也可能随旧文档一起丢失（三条已知偶发的失败栈都是
 *   导航中的探测超时）。[PageProbe.await] 一次只让一个探测在途，[RESEND_MS] 没回才另发一个（迟到的旧回答照样算），任何一次回答
 *   满足条件即结束；回答不满足就接着等。断言本身不变。
 */
internal object WebWait {
    const val CEILING_MS = 30_000L
    const val POLL_MS = 50L
    const val RESEND_MS = 2_000L

    /** 原生侧条件（容器回调、测试替身记录、埋点）：满足即返回，到上限判失败。 */
    fun until(
        message: String,
        ceilingMs: Long = CEILING_MS,
        detail: () -> String = { "" },
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.uptimeMillis() + ceilingMs
        while (!condition()) {
            if (SystemClock.uptimeMillis() >= deadline) throw AssertionError("timed out after ${ceilingMs}ms: $message ${detail()}")
            Thread.sleep(POLL_MS)
        }
    }

    /** 等容器 / 桥回调计数器落下（回调交回结论）。 */
    fun latch(
        message: String,
        latch: CountDownLatch,
        ceilingMs: Long = CEILING_MS,
    ) {
        if (!latch.await(ceilingMs, TimeUnit.MILLISECONDS)) throw AssertionError("timed out after ${ceilingMs}ms: $message")
    }
}

/**
 * 测试注入的手势时钟（F-AND-31）：只在测试显式推进时走动。点击与随后那次桥调用读到的是同一时刻，1 秒手势窗口不随调度延迟漂移；
 * 窗口本身（[GestureClock] 的 0..1000 毫秒判定）照生产逻辑执行。
 */
internal class ManualClock(
    start: Long = START,
) {
    private val now = AtomicLong(start)

    fun now(): Long = now.get()

    fun advance(millis: Long) {
        now.addAndGet(millis)
    }

    private companion object {
        const val START = 1_000_000L
    }
}

/** 读写一个 WebView 的页面状态；[onMain] 在主线程执行并等它执行完（ActivityScenario.onActivity / compose.runOnUiThread）。 */
internal class PageProbe(
    private val webView: WebView,
    private val onMain: (() -> Unit) -> Unit,
) {
    /** 动作脚本（导航、改 DOM、发桥消息）：不等回调——会触发导航的脚本，回调可能随旧文档丢失；结果由随后的等待确认。 */
    fun fire(script: String) = onMain { webView.evaluateJavascript(script, null) }

    /** 页面已稳定（没有在途导航）时读一次值：等回调到上限，没回判失败。 */
    fun read(
        script: String,
        ceilingMs: Long = WebWait.CEILING_MS,
    ): String {
        val latch = CountDownLatch(1)
        var result = "null"
        onMain {
            webView.evaluateJavascript(script) { value ->
                result = value ?: "null"
                latch.countDown()
            }
        }
        if (!latch.await(
                ceilingMs,
                TimeUnit.MILLISECONDS,
            )
        ) {
            throw AssertionError("evaluateJavascript timed out after ${ceilingMs}ms: $script")
        }
        return result
    }

    /** 等页面交回满足 [accept] 的回答（见 [WebWait]），返回那次回答。 */
    fun await(
        message: String,
        script: String,
        ceilingMs: Long = WebWait.CEILING_MS,
        accept: (String) -> Boolean,
    ): String {
        val open = AtomicBoolean(true)
        val answers = LinkedBlockingQueue<String>()
        val deadline = SystemClock.uptimeMillis() + ceilingMs
        var sent = 0
        var answered = 0
        var last: String? = null
        var lastSentAt = 0L
        var nextSendAt = SystemClock.uptimeMillis()
        try {
            while (true) {
                val now = SystemClock.uptimeMillis()
                val waiting = sent > answered && now - lastSentAt < WebWait.RESEND_MS
                if (!waiting && now >= nextSendAt) {
                    onMain { webView.evaluateJavascript(script) { value -> if (open.get()) answers.offer(value ?: "null") } }
                    sent += 1
                    lastSentAt = now
                }
                val left = deadline - SystemClock.uptimeMillis()
                if (left <= 0) {
                    throw AssertionError("timed out after ${ceilingMs}ms: $message (probes sent=$sent answered=$answered last=$last)")
                }
                val answer = answers.poll(minOf(left, WebWait.POLL_MS), TimeUnit.MILLISECONDS) ?: continue
                answered += 1
                last = answer
                if (accept(answer)) return answer
                nextSendAt = SystemClock.uptimeMillis() + WebWait.POLL_MS
            }
        } finally {
            open.set(false)
        }
    }
}
