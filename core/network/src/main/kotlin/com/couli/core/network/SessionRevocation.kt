package com.couli.core.network

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException

/**
 * 会话撤销标记的编码（F-AND-32，对照鸿蒙 FU-HM-0715 R1-02 / R1-03、FU-HM-0717 第 3 项）：令牌文件（couli.session）整体写不进时，撤销标记另落在
 * 独立介质（[RevocationMarks]，App 里是 noBackupFilesDir 下的小文件）。两处不能同一次提交，所以标记记的是「撤销的是哪份会话」：
 * - `e:<持久化登录世代>`：撤销这一次登录（新登录必换新世代，残留的精确标记对不上新会话，不误伤）；
 * - `*`：清除时不知道存储里是哪份会话（没装载过、读不出、升级前登录没有世代）——存储里的任何会话都按已撤销；
 * - `s:<持久化登录世代>`：除了这一次登录，其余一律撤销（新登录保存后删不掉的 `*` / 不完整标记改写成它，FU-HM-0717 第 3 项）。
 *
 * 落盘格式带完整性校验：`couli-rv1|<目标>|<目标长度>|<目标的 FNV-1a 32 位摘要>|end`。存在但为空、截断、缺结束符、长度或摘要对不上的
 * 一律按「任何会话都撤销」阻断（[blocks]），只有完整的标记才按目标比较。标记不是凭据（世代只用于本机缓存归属）。
 */
object SessionRevocation {
    const val ANY = "*"
    private const val HEADER = "couli-rv1"
    private const val END = "end"
    private const val FIELDS = 5
    private const val TARGET = 1
    private const val LENGTH = 2
    private const val DIGEST = 3
    private const val PREFIX_LENGTH = 2
    private const val BYTE_MASK = 0xffL
    private const val FNV_OFFSET = 0x811c9dc5L
    private const val FNV_PRIME = 0x01000193L
    private const val MASK_32 = 0xffffffffL
    private const val HEX = 16
    private const val DIGEST_CHARS = 8

    /** 撤销登录世代为 [epoch] 的那份会话；不知道是哪份（null）为 [ANY]。 */
    fun revoking(epoch: String?): String = epoch?.takeIf { it.isNotEmpty() }?.let { "e:$it" } ?: ANY

    /** 除了登录世代 [epoch] 之外一律撤销。 */
    fun supersededBy(epoch: String): String = "s:$epoch"

    fun encode(target: String): String = listOf(HEADER, target, target.length.toString(), digest(target), END).joinToString("|")

    /** 完整的标记 → 目标；空、截断、格式不符、长度或摘要对不上 → null（不完整）。 */
    @Suppress("ReturnCount")
    fun decode(text: String?): String? {
        if (text.isNullOrEmpty()) return null
        val parts = text.split('|')
        if (parts.size != FIELDS || parts[0] != HEADER || parts[FIELDS - 1] != END) return null
        val target = parts[TARGET]
        if (target.isEmpty() || parts[LENGTH] != target.length.toString() || parts[DIGEST] != digest(target)) return null
        return target.takeIf { it == ANY || ((it.startsWith("e:") || it.startsWith("s:")) && it.length > PREFIX_LENGTH) }
    }

    /**
     * 这份标记文本（[text]）是否阻断登录世代为 [sessionEpoch] 的会话（null = 升级前登录、没有世代）：不完整 → 阻断；`*` → 阻断；`e:X` → 世代
     * 等于 X 才阻断；`s:X` → 世代不等于 X 就阻断。
     */
    fun blocks(
        text: String,
        sessionEpoch: String?,
    ): Boolean {
        val target = decode(text) ?: return true
        return when {
            target == ANY -> true
            target.startsWith("e:") -> sessionEpoch == target.substring(PREFIX_LENGTH)
            else -> sessionEpoch != target.substring(PREFIX_LENGTH)
        }
    }

    private fun digest(text: String): String {
        var hash = FNV_OFFSET
        text.toByteArray(Charsets.UTF_8).forEach { byte ->
            hash = hash xor (byte.toLong() and BYTE_MASK)
            hash = (hash * FNV_PRIME) and MASK_32
        }
        return hash.toString(HEX).padStart(DIGEST_CHARS, '0')
    }
}

/**
 * 令牌文件之外的撤销标记介质（F-AND-32）。读写失败一律抛 [IOException]：读不出时调用方按「会话读不出」，写不进时按「只剩本进程内的隔离」。
 */
interface RevocationMarks {
    /** 正式文件与临时文件里的标记文本（不存在的不列出）。读不出抛错。 */
    fun read(): List<String>

    /** 原子写入（临时文件落盘后改名）；失败抛错（可能留下临时文件，读取时同样核对）。 */
    fun write(text: String)

    /** 删除正式文件与临时文件；任一删不掉抛错。 */
    fun delete()
}

/**
 * 文件实现：[file] 与同目录的 `.tmp`。写入先 fsync 临时文件再改名（标记在删除令牌之前确实落盘）。
 *
 * 读取与删除只把「明确不存在」（[NoSuchFileException]）当成没有标记（第 1 轮评审 R1-02）：不用 [File.exists]——它在目录查不了（没有权限、
 * 路径里有一段不是目录、其他 I/O 错误）时同样返回 false，会把查询失败吞成「没有标记」。权限、目录结构及其他 I/O 错误一律抛 [IOException]。
 */
class FileRevocationMarks(
    private val file: File,
) : RevocationMarks {
    private val temp: File get() = File(file.parentFile, file.name + TEMP_SUFFIX)

    @Synchronized
    override fun read(): List<String> = listOf(file, temp).mapNotNull(::readOrAbsent)

    /** 明确不存在 → null；读到 → 文本；其余（权限、不是目录、是目录、I/O 错误、安全管理器拒绝）→ 抛 [IOException]。 */
    @Suppress("SwallowedException", "TooGenericExceptionCaught") // 只有 NoSuchFileException 是「没有」；其余统一换成 IOException 上抛
    private fun readOrAbsent(target: File): String? =
        try {
            String(Files.readAllBytes(target.toPath()), Charsets.UTF_8)
        } catch (absent: NoSuchFileException) {
            null
        } catch (error: IOException) {
            throw error
        } catch (error: RuntimeException) {
            throw IOException("cannot query ${target.name}", error)
        }

    @Synchronized
    override fun write(text: String) {
        val dir = file.parentFile
        if (dir != null && !dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create ${dir.name}")
        FileOutputStream(temp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!temp.renameTo(file)) throw IOException("cannot replace ${file.name}")
    }

    /** 两个文件都删（不存在的跳过）；任一查询或删除失败抛错（不把查不了当成已删，R1-02）。 */
    @Synchronized
    @Suppress("TooGenericExceptionCaught")
    override fun delete() {
        val failures =
            listOf(file, temp).mapNotNull { target ->
                try {
                    Files.deleteIfExists(target.toPath())
                    null
                } catch (error: IOException) {
                    "${target.name}: ${error.javaClass.simpleName}"
                } catch (error: RuntimeException) {
                    "${target.name}: ${error.javaClass.simpleName}"
                }
            }
        if (failures.isNotEmpty()) throw IOException("cannot delete ${failures.joinToString()}")
    }

    private companion object {
        const val TEMP_SUFFIX = ".tmp"
    }
}

/**
 * 令牌存储自己发现会话不能用并丢弃（内容损坏、校验不通过、被落盘的撤销标记阻断）时的同步通知（F-AND-33，对照鸿蒙 FU-HM-0717
 * `onSelfDiscarded`）。通知在令牌存储的锁里、在一次令牌读取过程中发出：监听方不得再读令牌（ApiClient 只作废已绑定的会话归属）。
 */
interface SessionSelfDiscards {
    fun onSelfDiscarded(listener: () -> Unit) = Unit
}
