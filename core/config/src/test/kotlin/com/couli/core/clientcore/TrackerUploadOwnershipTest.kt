package com.couli.core.clientcore

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APP-03b 第 2 轮评审 R2-05：上报批次的归属。flush 在锁内取出批次后暂停（[TrackerQueue.afterBatchTaken]）→ 撤回同意或切换环境
 * → flush 恢复：已取出的批次不再交给 sink；交给 sink 之后的撤回会取消在途上报。
 */
class TrackerUploadOwnershipTest {
    private val pool = Executors.newCachedThreadPool()
    private val uploads: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())
    private var file: String? = null

    @Volatile
    private var outerEpoch = 0L

    @After
    fun stop() {
        pool.shutdownNow()
    }

    private fun queue(sink: TrackerSink = TrackerSink { events, _ -> uploads.add(events) }) =
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
            epochs = { outerEpoch },
        )

    /** 在后台线程 flush，停在「批次已取出、还没交给 sink」；返回恢复用的闩与任务。 */
    private fun pausedFlush(queue: TrackerQueue): Pair<CountDownLatch, Future<*>> {
        val paused = CountDownLatch(1)
        val resume = CountDownLatch(1)
        queue.afterBatchTaken = {
            paused.countDown()
            resume.await(5, TimeUnit.SECONDS)
        }
        val task = pool.submit { queue.flush() }
        assertTrue("flush 已取出批次", paused.await(5, TimeUnit.SECONDS))
        return resume to task
    }

    @Test
    fun r2_05_batchTakenBeforeWithdrawalIsNeverHandedToTheSink() {
        val queue = queue()
        assertTrue(queue.track(ClientEvents.appLaunch(page = "Home")))
        val (resume, task) = pausedFlush(queue)

        queue.onPrivacyChanged(PrivacyStateRaw.BASIC_MODE)
        resume.countDown()
        task.get(5, TimeUnit.SECONDS)

        assertTrue("撤回之后不再上报撤回前的数据", uploads.isEmpty())
        assertEquals(0, queue.queueSize)
    }

    @Test
    fun r2_05_batchTakenBeforeAnEnvironmentSwitchIsNeverHandedToTheSink() {
        val queue = queue()
        assertTrue(queue.track(ClientEvents.appLaunch(page = "Home")))
        val (resume, task) = pausedFlush(queue)

        // 环境切换：在途请求世代先变（InFlightCalls），再清队列
        outerEpoch += 1
        queue.clear()
        resume.countDown()
        task.get(5, TimeUnit.SECONDS)

        assertTrue(uploads.isEmpty())
        assertEquals(0, queue.queueSize)
    }

    @Test
    fun r2_05_outerEpochChangeAloneInvalidatesTheBatch() {
        val queue = queue()
        assertTrue(queue.track(ClientEvents.appLaunch(page = "Home")))
        val (resume, task) = pausedFlush(queue)

        // 撤回清理的第一步（作废在途请求）已执行、队列清理还没轮到
        outerEpoch += 1
        resume.countDown()
        task.get(5, TimeUnit.SECONDS)

        assertTrue(uploads.isEmpty())
    }

    @Test
    fun r2_05_withdrawalCancelsAnUploadAlreadyHandedToTheSink() {
        val handed = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        var validAfterCancel = true
        val queue =
            queue(
                TrackerSink { _, ticket ->
                    ticket.onCancel { cancelled.countDown() }
                    handed.countDown()
                    // 模拟在途网络请求：被取消才返回
                    val wasCancelled = cancelled.await(5, TimeUnit.SECONDS)
                    validAfterCancel = ticket.isValid()
                    !wasCancelled
                },
            )
        assertTrue(queue.track(ClientEvents.appLaunch(page = "Home")))
        val task = pool.submit { queue.flush() }
        assertTrue(handed.await(5, TimeUnit.SECONDS))

        queue.onPrivacyChanged(PrivacyStateRaw.BASIC_MODE)
        task.get(5, TimeUnit.SECONDS)

        assertEquals("撤回时取消在途上报", 0, cancelled.count)
        assertFalse(validAfterCancel)
        assertEquals(0, queue.queueSize)
    }

    /** 在后台线程 flush，停在「最后一次归属核对已通过、批次已交接、还没发送」（R3-01 的窗口）；返回恢复用的闩与任务。 */
    private fun flushPausedAfterHandOver(queue: TrackerQueue): Pair<CountDownLatch, Future<*>> {
        val paused = CountDownLatch(1)
        val resume = CountDownLatch(1)
        queue.afterHandOver = {
            paused.countDown()
            resume.await(5, TimeUnit.SECONDS)
        }
        val task = pool.submit { queue.flush() }
        assertTrue("最后一次核对已通过", paused.await(5, TimeUnit.SECONDS))
        return resume to task
    }

    @Test
    fun r3_01_withdrawalAfterTheFinalCheckDeliversNothingToAnAdaptedSink() {
        val queue = queue(TrackerSink.of { events -> uploads.add(events) })
        assertTrue(queue.track(ClientEvents.appLaunch(page = "Home")))
        val (resume, task) = flushPausedAfterHandOver(queue)

        queue.onPrivacyChanged(PrivacyStateRaw.BASIC_MODE)
        resume.countDown()
        task.get(5, TimeUnit.SECONDS)

        assertEquals("最终核对之后撤回，sink 收到 0 条", 0, uploads.size)
        assertEquals(0, queue.queueSize)
    }

    @Test
    fun r3_01_withdrawalAfterTheFinalCheckDeliversNothingEvenToASinkIgnoringTheTicket() {
        val queue = queue(TrackerSink { events, _ -> uploads.add(events) })
        assertTrue(queue.track(ClientEvents.appLaunch(page = "Home")))
        val (resume, task) = flushPausedAfterHandOver(queue)

        queue.onPrivacyChanged(PrivacyStateRaw.BASIC_MODE)
        resume.countDown()
        task.get(5, TimeUnit.SECONDS)

        assertEquals("已交接未发送的批次随 ticket 作废取消", 0, uploads.size)
        assertEquals(0, queue.queueSize)
    }

    @Test
    fun r3_01_clearAfterTheFinalCheckDeliversNothing() {
        val queue = queue(TrackerSink.of { events -> uploads.add(events) })
        assertTrue(queue.track(ClientEvents.appLaunch(page = "Home")))
        val (resume, task) = flushPausedAfterHandOver(queue)

        queue.clear()
        resume.countDown()
        task.get(5, TimeUnit.SECONDS)

        assertEquals(0, uploads.size)
        assertEquals(0, queue.queueSize)
    }

    @Test
    fun r3_01_adaptedSinkChecksTheTicketBeforeCallingTheLambda() {
        val stale = UploadTicket(null, 0L, 0L) { false }
        assertFalse(TrackerSink.of { events -> uploads.add(events) }.upload(listOf("e"), stale))
        assertEquals("ticket 无效时不调用 lambda", 0, uploads.size)
    }

    @Test
    fun ownedBatchIsStillUploaded() {
        val queue = queue()
        assertTrue(queue.track(ClientEvents.appLaunch(page = "Home")))

        queue.flush()

        assertEquals(1, uploads.single().size)
        assertEquals(0, queue.queueSize)
    }
}
