package com.couli.core.clientcore

import java.io.File
import java.io.IOException

/**
 * 小文本文件的读写（ConfigStore 的 LKG、TrackerQueue 的队列；App 放在 filesDir 下，不在 cacheDir：「清除缓存」不清，03 §4.3）。
 * - [read]：不存在为 null；读取出错抛给调用方（ConfigStore、TrackerQueue 按读不出处理）。
 * - [write]：写临时文件再改名替换（不会留下写了一半的文件）；null 删除正式文件与残留的临时文件。任何失败抛 [IOException]（切换环境、撤回同意的清理
 *   据此保持待完成、下次重试）。
 */
class AtomicTextFile(
    private val file: File,
) {
    @Synchronized
    fun read(): String? = if (file.exists()) file.readText(Charsets.UTF_8) else null

    @Synchronized
    @Suppress("ThrowsCount") // 删除、建目录、改名三处失败各自报错
    fun write(text: String?) {
        val temp = File(file.parentFile, file.name + TEMP_SUFFIX)
        if (text == null) {
            // 正式文件与临时文件都删（APP-03b R1-01：改名之前进程退出会把旧数据留在 .tmp 里）；两个都试，任一删不掉都报错
            val leftovers = listOf(file, temp).filter { it.exists() && !it.delete() }
            if (leftovers.isNotEmpty()) throw IOException("cannot delete ${leftovers.joinToString { it.name }}")
            return
        }
        val dir = file.parentFile
        if (dir != null && !dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create ${dir.name}")
        temp.writeText(text, Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            temp.delete()
            throw IOException("cannot replace ${file.name}")
        }
    }

    private companion object {
        const val TEMP_SUFFIX = ".tmp"
    }
}
