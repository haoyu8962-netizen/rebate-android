package com.couli.app.clipboard

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import java.io.Closeable
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.assertEquals
import org.robolectric.Shadows.shadowOf

/**
 * 真实 SharedPreferences 的故障包装：volatile 实例接收全部内存改动，只有成功 commit 才写 durable 实例和独立磁盘镜像。
 * 默认 ClipboardPreferences 适配器必须读取包装后的返回值；重启仅从文件创建全新偏好实例，不能拿 volatile 缓存重建。
 */
internal class ProductionImportPreferences0722Harness(
    private val context: Context,
) : Closeable {
    private val directory = Files.createTempDirectory("import-production-0722-").toFile()
    private val disk = File(directory, "committed.bin")
    private val names = mutableListOf<String>()
    val flaky = newFlaky(emptyMap<String, Any>())
    val preferences = ClipboardPreferences(flaky, SampleCipher)

    private fun newPreferences(): SharedPreferences {
        val name = "import-0722-${UUID.randomUUID()}"
        names += name
        return context.getSharedPreferences(name, Context.MODE_PRIVATE)
    }

    private fun newFlaky(seed: Map<String, *>): CommitFailingPreferences0722 {
        val volatile = newPreferences()
        val durable = newPreferences()
        check(copyPreferences0722(seed, volatile))
        check(copyPreferences0722(seed, durable))
        return CommitFailingPreferences0722(volatile, durable) {
            ObjectOutputStream(disk.outputStream()).use { it.writeObject(HashMap(durable.all)) }
        }
    }

    fun restartFromDisk(): ClipboardPreferences = inspectDisk()

    /** 只读磁盘探针：不把当前 UI 实例当作已经重启。 */
    fun inspectDisk(): ClipboardPreferences {
        @Suppress("UNCHECKED_CAST") // 只读本夹具写出的临时文件，无外部输入。
        val committed =
            if (disk.exists()) {
                ObjectInputStream(disk.inputStream()).use { it.readObject() as Map<String, *> }
            } else {
                emptyMap<String, Any>()
            }
        return ClipboardPreferences(newFlaky(committed), SampleCipher)
    }

    override fun close() {
        names.forEach { context.deleteSharedPreferences(it) }
        check(directory.deleteRecursively())
    }
}

internal class CommitFailingPreferences0722(
    private val volatile: SharedPreferences,
    val durable: SharedPreferences,
    private val onCommitted: () -> Unit,
) : SharedPreferences by volatile {
    private var failAt: Int? = null
    private var continuous = false
    var commits = 0
        private set
    var failures = 0
        private set

    fun failOnlyNextCommit() {
        failAt = commits + 1
        continuous = false
    }

    fun failFromNextCommit() {
        failAt = commits + 1
        continuous = true
    }

    fun recover() {
        failAt = null
    }

    override fun edit(): SharedPreferences.Editor {
        val editor = volatile.edit()
        return object : SharedPreferences.Editor by editor {
            override fun commit(): Boolean {
                // Android commit 失败时进程缓存也可能已改；下一次成功提交会写整份缓存。
                check(editor.commit())
                commits += 1
                val failed = failAt?.let { commits == it || (continuous && commits >= it) } == true
                if (failed) {
                    failures += 1
                    return false
                }
                val committed = copyPreferences0722(volatile.all, durable)
                if (committed) onCommitted()
                return committed
            }

            override fun apply() {
                commit()
            }
        }
    }
}

/** 仅复制 SharedPreferences 公共值类型，不知道产品字段/键名/序列化布局。 */
private fun copyPreferences0722(
    source: Map<String, *>,
    target: SharedPreferences,
): Boolean {
    val editor = target.edit().clear()
    source.forEach { (key, value) ->
        when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Long -> editor.putLong(key, value)
            is Int -> editor.putInt(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.map { it as String }.toSet())
            null -> editor.remove(key)
            else -> error("Unexpected SharedPreferences value type")
        }
    }
    return editor.commit()
}

/**
 * 最小观测约定：默认重试在主线程 Looper 上，prefs.pendingImportWriteRetries 计入已排队/执行中的本存储任务。
 * 推进真实默认调度到任务完成；不替换 scheduleWriteRetry，不指定延时毫秒数，不手工补写用户动作。
 */
internal fun settleDefaultImportRetries0722(prefs: ClipboardPreferences) {
    repeat(128) {
        if (prefs.pendingImportWriteRetries == 0) return
        shadowOf(Looper.getMainLooper()).runToNextTask()
    }
    assertEquals("默认重试必须在有限任务内结算", 0, prefs.pendingImportWriteRetries)
}
