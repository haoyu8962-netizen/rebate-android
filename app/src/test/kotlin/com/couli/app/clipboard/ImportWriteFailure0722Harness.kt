package com.couli.app.clipboard

import com.couli.core.clipboard.ImportPreferenceBackend
import com.couli.core.clipboard.ImportPreferenceStore
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.util.Properties
import org.junit.Assert.assertTrue

/**
 * 独立磁盘文件与易失缓存，模拟 commit 返回 false 但进程内仍可读到新值的情况。
 * 重启只带文件路径，不传缓存、store、重试闭包；不借用 Android 的进程级 SharedPreferences 缓存。
 * 新的生产重试参数只在本文件引用，接口约定见 tests-handback 第 4 版。
 */
internal class ImportWriteFailure0722Harness(
    private val clock: () -> Long,
    exposeFailedWriteInMemory: Boolean = true,
) : Closeable {
    private val directory = Files.createTempDirectory("import-0722-disk-").toFile()
    private val file = File(directory, "durable.properties")
    val backend = DiskImportBackend0722(file, exposeFailedWriteInMemory)
    private val retries = ArrayDeque<() -> Unit>()
    val store = ImportPreferenceStore(backend, clock, scheduleWriteRetry = { retries.addLast(it) })

    val pendingRetries: Int get() = retries.size

    /** 独立读盘探针，不结束当前进程；只用于证明无关 commit 没把失败的开启夹带落盘。 */
    fun inspectDisk(): ImportPreferenceStore = ImportPreferenceStore(DiskImportBackend0722(file, true), clock)

    /** 只推进产品自己安排的重试；测试不补调用 enable / setOff / enqueue。 */
    fun runNextRetry() {
        assertTrue("失败必须留下可执行的生产重试", retries.isNotEmpty())
        retries.removeFirst().invoke()
    }

    fun settleRetries() {
        repeat(32) {
            if (retries.isEmpty()) return
            retries.removeFirst().invoke()
        }
        assertTrue("磁盘恢复后重试应有限完成，不得忙循环", retries.isEmpty())
    }

    fun restartFromDisk(): ImportPreferenceStore {
        retries.clear() // 模拟进程结束：旧进程的闭包不能帮重启后的实例补写。
        val restartedRetries = ArrayDeque<() -> Unit>()
        return ImportPreferenceStore(
            DiskImportBackend0722(file, exposeFailedWriteInMemory = true),
            clock,
            scheduleWriteRetry = { restartedRetries.addLast(it) },
        )
    }

    override fun close() {
        retries.clear()
        check(directory.deleteRecursively())
    }
}

internal class DiskImportBackend0722(
    private val file: File,
    private val exposeFailedWriteInMemory: Boolean,
) : ImportPreferenceBackend {
    private val memory =
        Properties().apply {
            if (file.exists()) file.inputStream().use { load(it) }
        }
    private var failedWrite: Int? = null
    private var failContinuously = false
    var writes = 0
        private set
    var failures = 0
        private set

    fun failFromNextWrite() {
        failedWrite = writes + 1
        failContinuously = true
    }

    fun failOnlyNextWrite() {
        failedWrite = writes + 1
        failContinuously = false
    }

    fun recover() {
        failedWrite = null
    }

    override fun read(key: String): String? = memory.getProperty(key)

    override fun write(changes: Map<String, String?>): Boolean {
        writes += 1
        val fail = failedWrite?.let { writes == it || (failContinuously && writes >= it) } == true
        if (!fail || exposeFailedWriteInMemory) {
            changes.forEach { (key, value) -> if (value == null) memory.remove(key) else memory.setProperty(key, value) }
        }
        if (fail) {
            failures += 1
            return false
        }
        file.outputStream().use { memory.store(it, null) }
        return true
    }
}
