package com.couli.core.clientcore

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * APP-03b Android 第 1 轮评审：
 * - R1-01：清理（write(null)）同时删掉改名前中断留下的 .tmp；任一删不掉都报错（清理入口保持待完成）。
 * - R1-07：连续成功响应里缺字段、缺平台时，按平台回落到最近一次成功配置的取值，不回落到冷启动时的旧 LKG。
 * - R1-08：Tracker 接入可注入的 10 秒周期调度；只在已同意时运行，撤回同意即停。
 * - lessons 11：队列文件删不掉时记下待清空，确认删掉之前不采集、不上报、不读回旧队列。
 */
class ClientCoreReviewR1Test {
    private val dir: File = Files.createTempDirectory("couli-client-core").toFile()

    @After
    fun cleanup() {
        dir.deleteRecursively()
    }

    // ---------- R1-01 ----------

    @Test
    fun r1_01_clearAlsoDeletesTheTempFileLeftByAnInterruptedWrite() {
        val target = File(dir, "queue.json")
        val file = AtomicTextFile(target)
        file.write("""{"format":1}""")
        // 写入在改名之前中断：旧数据留在临时文件里
        val temp = File(dir, "queue.json.tmp")
        temp.writeText("""{"device_id":"device-old"}""")

        file.write(null)

        assertFalse(target.exists())
        assertFalse("临时文件里的旧数据也删掉", temp.exists())
    }

    @Test
    fun r1_01_tempFileThatCannotBeDeletedFailsTheCleanup() {
        val file = AtomicTextFile(File(dir, "lkg.json"))
        // 删不掉的临时文件（非空目录）
        val temp = File(dir, "lkg.json.tmp").apply { mkdirs() }
        File(temp, "stuck").writeText("x")

        try {
            file.write(null)
            fail("临时文件删不掉必须报错，清理保持待完成")
        } catch (expected: IOException) {
            assertTrue(expected.message.orEmpty().contains("lkg.json.tmp"))
        }
    }

    // ---------- R1-07 ----------

    private class Fetches {
        var pending: ((ConfigReply) -> Unit)? = null

        @Suppress("UnusedParameter") // 本组不看 If-None-Match
        fun fetch(
            etag: String?,
            done: (ConfigReply) -> Unit,
        ) {
            pending = done
        }

        fun ok(body: String) {
            val done = checkNotNull(pending)
            pending = null
            done(ConfigReply(ConfigReply.OK, body, null))
        }
    }

    private fun search(vararg pairs: Pair<String, String>): String =
        """{"features":{"search_status":{${pairs.joinToString(",") { """"${it.first}":"${it.second}"""" }}}}}"""

    @Test
    fun r1_07_platformMissingFromTheLatestSuccessFallsBackToThePreviousSuccessNotTheColdStartLkg() {
        var stored: String? = null
        val fetches = Fetches()

        fun store() =
            ConfigStore(
                readFile = { stored },
                writeFile = { stored = it },
                fetcher = fetches::fetch,
                clock = { 0L },
                bundled = search("taobao" to "off", "jd" to "off"),
            )
        // 冷启动前的 LKG：taobao=off
        var config = store()
        config.onColdStart()
        fetches.ok(search("taobao" to "off", "jd" to "on"))

        config = store()
        config.onColdStart()
        assertEquals("off", config.searchStatus("taobao"))
        // 成功响应 A 把 taobao 改为 on
        fetches.ok(search("taobao" to "on"))
        assertEquals("on", config.searchStatus("taobao"))
        // 成功响应 B 缺 taobao：取最近一次成功（A）的 on，不是冷启动 LKG 的 off
        config.onForeground()
        fetches.ok(search("jd" to "off"))
        assertEquals("on", config.searchStatus("taobao"))
        assertEquals("off", config.searchStatus("jd"))
        // 成功响应 C 整个缺 features：两个平台都沿用最近一次成功的取值
        config.onForeground()
        fetches.ok("""{"dict_version":"c"}""")
        assertEquals("on", config.searchStatus("taobao"))
        assertEquals("off", config.searchStatus("jd"))

        // 重启后（请求未完成）按文件里逐平台最近一次成功的取值
        val restarted = store()
        restarted.onColdStart()
        assertEquals("on", restarted.searchStatus("taobao"))
        assertEquals("off", restarted.searchStatus("jd"))
    }

    // ---------- R1-08 / lessons 11 ----------

    private class ManualScheduler : PeriodicScheduler {
        val intervals = mutableListOf<Long>()
        var action: (() -> Unit)? = null
        var closed = 0

        override fun every(
            intervalMillis: Long,
            action: () -> Unit,
        ): AutoCloseable {
            intervals += intervalMillis
            this.action = action
            return AutoCloseable {
                closed += 1
                this.action = null
            }
        }

        fun tick() = action?.invoke()
    }

    private class QueueFile {
        var value: String? = null
        var failDelete = false

        fun read(): String? = value

        fun write(text: String?) {
            if (text == null && failDelete) throw IOException("cannot delete")
            value = text
        }
    }

    private fun tracker(
        file: QueueFile,
        uploads: MutableList<List<String>>,
        privacy: String = PrivacyStateRaw.CONSENTED,
    ) = TrackerQueue(
        readFile = file::read,
        writeFile = file::write,
        sink = {
            uploads += it
            true
        },
        privacy = privacy,
        userId = { null },
        deviceId = "device-fixture",
        platform = ClientPlatformRaw.ANDROID,
        appVersion = "1.0.0",
        sessionId = "session-test",
        clock = { 0L },
        canUpload = { true },
    )

    @Test
    fun r1_08_partialBatchIsUploadedByTheTenSecondTimer() {
        val uploads = mutableListOf<List<String>>()
        val queue = tracker(QueueFile(), uploads)
        val scheduler = ManualScheduler()
        queue.startPeriodicFlush(scheduler)
        assertEquals(listOf(TrackerQueue.FLUSH_INTERVAL_MILLIS), scheduler.intervals)

        repeat(3) { assertTrue(queue.track(ClientEvents.appLaunch(page = "Home"))) }
        assertTrue("不满 10 条不触发", uploads.isEmpty())

        scheduler.tick()

        assertEquals(3, uploads.single().size)
        assertEquals(0, queue.queueSize)
    }

    @Test
    fun r1_08_timerStopsOnWithdrawalAndRestartsAfterConsent() {
        val uploads = mutableListOf<List<String>>()
        val queue = tracker(QueueFile(), uploads)
        val scheduler = ManualScheduler()
        queue.startPeriodicFlush(scheduler)
        assertTrue(queue.track(ClientEvents.appLaunch(page = "Home")))

        queue.onPrivacyChanged(PrivacyStateRaw.BASIC_MODE)
        assertEquals(1, scheduler.closed)
        scheduler.tick()
        assertTrue("撤回后不上报", uploads.isEmpty())
        assertEquals(0, queue.queueSize)

        queue.onPrivacyChanged(PrivacyStateRaw.CONSENTED)
        assertEquals("重新同意后定时器再启动", 2, scheduler.intervals.size)
        assertTrue(queue.track(ClientEvents.appLaunch(page = "Home")))
        scheduler.tick()
        assertEquals(1, uploads.single().size)
    }

    @Test
    fun lessons11_queueThatCannotBeDeletedIsNotReloadedOrUploadedUntilDeletionSucceeds() {
        val file = QueueFile()
        val uploads = mutableListOf<List<String>>()
        val queue = tracker(file, uploads)
        assertTrue(queue.track(ClientEvents.appLaunch(page = "Home")))
        val oldQueue = file.value

        file.failDelete = true
        try {
            queue.clear()
            fail("删不掉要报错，撤回清理保持待完成")
        } catch (expected: IOException) {
            assertEquals(oldQueue, file.value)
        }

        // 删不掉期间：不采集、不上报
        assertFalse(queue.track(ClientEvents.appLaunch(page = "Home")))
        queue.flush()
        assertTrue(uploads.isEmpty())

        // 未同意状态重建（重启，撤回清理的待清理标记仍在）：不读回旧队列
        val restarted = tracker(file, uploads, privacy = PrivacyStateRaw.NOT_DECIDED)
        assertEquals(0, restarted.queueSize)
        restarted.onPrivacyChanged(PrivacyStateRaw.CONSENTED)
        assertFalse(restarted.track(ClientEvents.appLaunch(page = "Home")))
        assertEquals(oldQueue, file.value)

        // 删除终于成功：之后正常采集，旧事件不复活
        file.failDelete = false
        assertTrue(queue.track(ClientEvents.appLaunch(page = "Home")))
        assertTrue("旧队列已被新队列替换", file.value != oldQueue)
        queue.flush()
        assertEquals(1, uploads.single().size)
    }
}
