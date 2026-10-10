package com.couli.core.orders

import com.couli.core.clientcore.AtomicTextFile
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 订单页恢复记录里的个人数据部分（FU-AND-0710 R1-03 / R2-02，APP-08 共性第 36、40、45 条）：账号归属（账号哈希）、平台与月份筛选、
 * 搜索词与输入框文字（可能是完整订单号）、滚动锚点所在的订单。系统保存状态（SavedStateHandle）里只存一个随机引用与不含个人数据的值。
 */
data class OrderRestoreRecord(
    val owner: String?,
    val platform: String? = null,
    val month: String? = null,
    val q: String? = null,
    val input: String = "",
    val anchorOrderId: String? = null,
)

/**
 * 订单页恢复记录的落盘（App 放在 noBackupFilesDir 下）：每份记录一个文件（文件名 = 随机引用），原子写（临时文件改名替换，失败抛错）。
 *
 * - 写入只认「当前清理代次」（[epoch]）：每次 [clearAll] 先加 1，页面在清理之前取得的代次写不进来（会话清理后旧页面的迟到写入作废）；
 *   页面清空自己的内存状态后（AppOrders 的清理通知）再取新的代次。
 * - [clearAll]（撤回同意、进入基本模式、切换环境；AppModule 直接登记进凭据清理器与环境清理器，Application 启动的补清理与环境对账同样执行）：
 *   先把「待清理」落到记录目录之外（[flag]，commit 确认）并在目录内放标记，再删除全部记录、再列一次确认只剩标记，最后清标记；任何一步失败都抛
 *   [IOException]（清理器据此判失败、保留待清理并重试）。
 * - 待清理期间（本进程内存、目录外标记、目录内标记任一存在）：读一律为 null、写一律拒绝，每次读写前先重试清理；进程重建后同样生效
 *   （内存没了，两处落盘标记仍在）。
 */
@Suppress("TooManyFunctions") // 读、写、清理与编解码各一组
class OrderRestoreStore internal constructor(
    private val root: File,
    private val flag: OrderStaleCache.PendingClearFlag,
    /** 删除一个文件或目录并确认（测试替换：模拟删不掉）。 */
    private val deleter: (File) -> Boolean,
    /** 原子写入一个记录文件，失败抛错（测试替换：模拟写不进）。 */
    private val writer: (File, String) -> Unit,
) {
    constructor(root: File, flag: OrderStaleCache.PendingClearFlag = OrderStaleCache.SiblingFileFlag(root)) :
        this(root, flag, ::deleteConfirmed, { file, text -> AtomicTextFile(file).write(text) })

    private val marker = File(root, PENDING_MARKER)

    /** 本进程内的待清理状态（落盘的两处标记另行核验）。 */
    private var pendingClear = false

    /** 清理代次：每次 [clearAll] 加 1；页面取得的代次与它不同就写不进来。 */
    @Volatile
    var epoch: Long = 0L
        private set

    /** 新的记录引用（随机，不含个人数据；写进系统保存状态）。 */
    fun newRef(): String = UUID.randomUUID().toString()

    /** 读 [ref] 的记录；没有、引用不合法、待清理没清掉、读不出或损坏都为 null（损坏的删掉）。 */
    @Synchronized
    @Suppress("ReturnCount") // 引用、清理、读取逐项
    fun read(ref: String?): OrderRestoreRecord? {
        if (ref == null || !REF_PATTERN.matches(ref) || !cleaned()) return null
        val file = File(root, ref + SUFFIX)
        val text = runCatching { AtomicTextFile(file).read() }.getOrNull() ?: return null
        return decode(text) ?: run {
            runCatching { deleter(file) }
            null
        }
    }

    /** 写 [ref] 的记录：[boundEpoch] 必须仍是当前清理代次、没有待清理；写不进返回 false（调用方当作没有恢复记录）。 */
    @Synchronized
    fun write(
        ref: String,
        boundEpoch: Long,
        record: OrderRestoreRecord,
    ): Boolean {
        if (!REF_PATTERN.matches(ref) || !cleaned() || boundEpoch != epoch) return false
        val ok = runCatching { writer(File(root, ref + SUFFIX), encode(record)) }.isSuccess
        if (!ok) OrderLog.warn("orders: restore record not written")
        return ok
    }

    /** 页面创建时只留本页这一份（进程被杀、没被恢复的旧记录删掉）；删不掉不影响页面（清理时再删）。 */
    @Synchronized
    fun retainOnly(ref: String?) {
        if (!cleaned()) return
        val keep = ref?.plus(SUFFIX)
        runCatching { root.listFiles() }
            .getOrNull()
            ?.filter { it.name != keep && it.name != PENDING_MARKER }
            ?.forEach { runCatching { deleter(it) } }
    }

    /**
     * 全部删除（撤回同意、进入基本模式、切换环境）：代次先加 1（之前取得代次的写入一律作废），任何一个文件删不掉都保留待清理并抛错。
     */
    @Synchronized
    fun clearAll() {
        epoch += 1
        clearFiles()
    }

    private fun clearFiles() {
        pendingClear = true
        // 先把待清理状态落到记录目录之外并确认写入；写不进时仍尽力删除，并在目录内留标记（进程重建后也读得到）
        if (!flag.set()) OrderLog.warn("orders: restore pending flag not persisted")
        if (!root.exists()) return finishClear()
        runCatching { if (root.isDirectory) marker.writeText("1") }
        val children = (if (root.isDirectory) root.listFiles() else null) ?: incomplete("restore directory not listable")
        val leftovers = children.filter { it.name != PENDING_MARKER }.filterNot { runCatching { deleter(it) }.getOrDefault(false) }
        if (leftovers.isNotEmpty()) incomplete("restore records not fully deleted")
        val remaining = root.listFiles() ?: incomplete("restore directory not listable")
        if (remaining.any { it.name != PENDING_MARKER }) incomplete("restore records not fully deleted")
        if (marker.exists() && !marker.delete()) incomplete("restore marker not deleted")
        finishClear()
    }

    /** 确认全部删除之后：先清目录外的待清理状态（确认写入），再清内存状态；清不掉按未完成。 */
    private fun finishClear() {
        if (!flag.clear()) incomplete("restore pending flag not cleared")
        pendingClear = false
    }

    private fun incomplete(reason: String): Nothing = throw IOException(reason)

    /** 没有待清理，或重试清理成功（重试不改代次）。 */
    private fun cleaned(): Boolean {
        val pending = pendingClear || marker.exists() || runCatching { flag.isSet() }.getOrDefault(true)
        if (!pending) return true
        return runCatching { clearFiles() }.isSuccess
    }

    private fun encode(record: OrderRestoreRecord): String =
        JsonObject(
            mapOf(
                "owner" to str(record.owner),
                "platform" to str(record.platform),
                "month" to str(record.month),
                "q" to str(record.q),
                "input" to JsonPrimitive(record.input),
                "anchor_order_id" to str(record.anchorOrderId),
            ),
        ).toString()

    private fun decode(text: String): OrderRestoreRecord? {
        val o = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null

        fun text(key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return OrderRestoreRecord(
            owner = text("owner"),
            platform = text("platform"),
            month = text("month"),
            q = text("q"),
            input = text("input").orEmpty(),
            anchorOrderId = text("anchor_order_id"),
        )
    }

    private fun str(value: String?) = value?.let(::JsonPrimitive) ?: JsonNull

    private companion object {
        const val PENDING_MARKER = "pending_clear"
        const val SUFFIX = ".json"
        val REF_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}

private fun deleteConfirmed(file: File): Boolean = file.deleteRecursively() && !file.exists()
