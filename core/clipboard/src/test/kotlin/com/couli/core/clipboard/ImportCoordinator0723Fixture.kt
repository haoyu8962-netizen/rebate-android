@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.couli.core.clipboard

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/** 只替换 I/O；轮次、claim、门槛、调度、过期与 finish 全走待实现的生产 ImportCoordinator。 */
internal class ImportCoordinator0723Fixture(
    val test: TestScope,
    enabled: Boolean? = true,
) {
    private val ownerThread = Thread.currentThread()
    private val values = mutableMapOf<String, String>()
    val backend =
        object : ImportPreferenceBackend {
            override fun read(key: String): String? = values[key]

            override fun write(changes: Map<String, String?>): Boolean {
                changes.forEach { (key, value) ->
                    if (value == null) values.remove(key) else values[key] = value
                }
                return true
            }
        }
    val prefs =
        ImportPreferenceStore(
            backend = backend,
            clock = { test.testScheduler.currentTime },
            scheduleWriteRetry = { error("成功的内存存储不应重试") },
        )
    var environment =
        ImportEnvironment(
            privacyAgreed = true,
            configData = config("v1"),
            page = ImportPageScope("page-A", "Home", true, true, true, true),
            modal = "none",
            webSuppressed = false,
        )
    val reads = mutableListOf<ImportTask>()
    val extractions = mutableListOf<Pair<ImportTask, String>>()
    val parses = mutableListOf<Pair<ImportTask, List<String>>>()
    val cards = mutableListOf<Pair<ImportTask, JsonObject>>()
    val deliveries = mutableListOf<Pair<ImportTask, String>>()
    val fallbacks = mutableListOf<String>()
    val finishes = mutableListOf<Pair<ImportTask, String>>()
    var readFailure: (() -> Throwable?)? = null
    var duringRead: ((ImportTask) -> Unit)? = null
    private var serial = 0
    val coordinator: ImportCoordinator =
        ImportCoordinator(
            prefs = prefs,
            environment = {
                assertOwnerThread()
                environment
            },
            workScope = test, // 仅有限工作；常驻 actor/Channel 循环不能挂在此 scope。
            clock = { test.testScheduler.currentTime },
            requestIdGen = { "request-${++serial}" },
            ports =
                object : ImportCoordinatorPorts {
                    override fun deliver(
                        task: ImportTask,
                        text: String,
                    ) {
                        assertOwnerThread()
                        deliveries += task to text
                    }

                    override fun read(task: ImportTask) {
                        assertOwnerThread()
                        reads += task // 入口同步计数，抛异常也计一次。
                        duringRead?.invoke(task)
                        readFailure?.invoke()?.let { throw it }
                    }

                    override fun extract(
                        task: ImportTask,
                        text: String,
                    ) {
                        assertOwnerThread()
                        extractions += task to text
                    }

                    override fun parse(
                        task: ImportTask,
                        candidates: List<String>,
                    ) {
                        assertOwnerThread()
                        parses += task to candidates.toList()
                    }

                    override fun present(
                        task: ImportTask,
                        result: JsonObject,
                    ) {
                        assertOwnerThread()
                        cards += task to result
                    }

                    override fun showFallback(pageInstanceId: String) {
                        assertOwnerThread()
                        fallbacks += pageInstanceId
                    }

                    override fun finished(
                        task: ImportTask,
                        terminalRaw: String,
                    ) {
                        assertOwnerThread()
                        // 观察的是释放后的通知，过期也必须先释放自己。
                        val active = coordinator.snapshot().activeAutoTask
                        assertTrue("finish 通知前应释放自己", active?.requestId != task.requestId)
                        finishes += task to terminalRaw
                    }
                },
        )

    init {
        if (enabled != null) {
            assertTrue(prefs.setOn())
            if (!enabled) assertTrue(prefs.setOff())
        }
    }

    private fun assertOwnerThread() {
        assertEquals("门槛快照和 I/O 端口必须回注入的主线程调度器", ownerThread, Thread.currentThread())
    }

    fun settle() {
        test.advanceUntilIdle()
    }

    fun foreground() {
        coordinator.onLifecycle(ImportLifecycleEvent.PROCESS_FOREGROUND)
        coordinator.onPageReady()
    }

    fun background() {
        coordinator.onLifecycle(ImportLifecycleEvent.PROCESS_BACKGROUND)
    }

    fun start(): ImportTask {
        foreground()
        settle()
        assertEquals(1, reads.size)
        return reads.single()
    }

    fun readText(
        task: ImportTask,
        value: String = "old-text",
    ) {
        coordinator.onRead(task, ImportReadResult("text", value))
        settle()
    }

    fun candidates(
        task: ImportTask,
        value: String = "old-candidate",
    ) {
        coordinator.onCandidates(task, ImportCandidateResult("candidates", listOf(value)))
        settle()
    }

    fun parsing(): ImportTask {
        val task = start()
        readText(task)
        candidates(task)
        assertEquals(listOf("old-candidate"), parses.single().second)
        return task
    }

    fun parsed(
        task: ImportTask,
        value: String = "old-card",
    ) {
        coordinator.onParsed(task, buildJsonObject { put("card", value) })
        settle()
    }

    fun assertFinished(
        task: ImportTask,
        terminal: String,
    ) {
        val actual = finishes.filter { it.first.requestId == task.requestId }.map { it.second }
        assertEquals(listOf(terminal), actual)
        val active = coordinator.snapshot().activeAutoTask
        assertNull(active)
        assertTrue(coordinator.snapshot().consumed)
    }

    companion object {
        fun config(version: String?): JsonObject =
            buildJsonObject {
                putJsonObject("clipboard") {
                    if (version != null) put("consent_version", version)
                }
            }
    }
}
