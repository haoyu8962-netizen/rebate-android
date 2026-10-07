package com.couli.core.clientcore

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-ALL-08：APP-03b 三端共性第 26 条（发送票据的生命周期）在 Android 的核对。sink 是同步的：返回即这一批结束（拒收或已完成），
 * 票据在 flush 的 finally 里立刻摘掉；之后的撤回、清空不会再去取消已经结束的批次，只有确实还在发送中的票据会被取消。
 */
class TrackerTicketLifecycleTest {
    private var file: String? = null
    private val cancelled = AtomicInteger()

    private fun queue(sink: TrackerSink) =
        TrackerQueue(
            readFile = { file },
            writeFile = { file = it },
            sink = sink,
            privacy = PrivacyStateRaw.CONSENTED,
            userId = { null },
            deviceId = { "device-fixture" },
            platform = ClientPlatformRaw.ANDROID,
            appVersion = "1.0.0",
            sessionId = { "session-test" },
            clock = { 0L },
            canUpload = { true },
        )

    @Test
    fun syncRejectedBatchEndsItsTicketImmediately() {
        val queue =
            queue { _, t ->
                t.onCancel { cancelled.incrementAndGet() }
                false
            }
        assertTrue(queue.track(ClientEvents.appLaunch(page = "Home")))
        queue.flush()

        queue.clear()

        assertEquals("拒收的批次已结束：清空不再取消它", 0, cancelled.get())
    }

    @Test
    fun completedBatchEndsItsTicketAndLaterWithdrawalDoesNotTouchIt() {
        val queue =
            queue { _, t ->
                t.onCancel { cancelled.incrementAndGet() }
                true
            }
        assertTrue(queue.track(ClientEvents.appLaunch(page = "Home")))
        queue.flush()
        assertEquals(0, queue.queueSize)

        queue.onPrivacyChanged(PrivacyStateRaw.BASIC_MODE)

        assertEquals("已完成的批次不在途，撤回不取消它", 0, cancelled.get())
    }

    @Test
    fun onlyTheBatchStillSendingIsCancelled() {
        lateinit var queue: TrackerQueue
        queue =
            queue { _, t ->
                t.onCancel { cancelled.incrementAndGet() }
                // 发送进行中撤回：这一张票据确实还在发送，必须被取消
                queue.onPrivacyChanged(PrivacyStateRaw.BASIC_MODE)
                false
            }
        assertTrue(queue.track(ClientEvents.appLaunch(page = "Home")))
        queue.flush()

        assertEquals("在途票据被取消一次", 1, cancelled.get())
    }
}
