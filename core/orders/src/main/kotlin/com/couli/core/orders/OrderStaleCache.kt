package com.couli.core.orders

import com.couli.core.clientcore.AtomicTextFile
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** 一份缓存的列表第一页（默认视图）与它的成功获取时刻。 */
data class CachedOrderList(
    val data: OrderListData,
    val fetchedAt: Long,
)

/** 一份缓存的订单详情与它的成功获取时刻。 */
data class CachedOrderDetail(
    val detail: OrderDetail,
    val fetchedAt: Long,
)

/**
 * 断网显示上次数据（TECH-20，G10）。策略（三端向量 cachePolicy / cacheClear 组）：
 * - 只缓存默认视图：每个账号 × 每个 scope 的第一页（全部分组、没有平台、月份、搜索词，没有 cursor），加最近打开的订单详情（每个账号最多
 *   30 条，按最近使用淘汰）；
 * - 登出、换账号、会话失效、撤回同意、切换环境时删除；进后台、刷新成功不删（刷新成功是更新缓存）。
 *
 * 落盘（[Store]）：App 私有、不备份的目录（Android noBackupFilesDir）下按账号分目录（目录名是账号的哈希，不落明文账号），原子写；删除要确认
 * 落盘成功，失败时保留「待清理」状态（内存 + 缓存目录之外的 [PendingClearFlag] + 目录内标记文件），清理完成之前不读回、不写入旧数据；读取失败或
 * 记录损坏一律当作「没有缓存」（并删掉坏文件）。
 */
object OrderStaleCache {
    const val KIND_LIST = "list"
    const val KIND_DETAIL = "detail"

    /** 每个账号最多缓存的详情条数。 */
    const val DETAIL_LIMIT = 30

    private val DEFAULT_ONLY = setOf("scope")

    /** 清缓存的事件（三端同名）。 */
    val CLEAR_EVENTS: Set<String> = setOf("logout", "account_switch", "session_invalid", "consent_revoked", "env_switch")

    fun isCacheable(
        kind: String,
        query: JsonObject?,
    ): Boolean =
        when (kind) {
            KIND_DETAIL -> {
                true
            }

            KIND_LIST -> {
                val params = query ?: JsonObject(emptyMap())
                val group = (params["status_group"] as? JsonPrimitive)?.content
                params.keys.all { it in DEFAULT_ONLY || (it == "status_group" && group == OrderQuery.ALL) }
            }

            else -> {
                false
            }
        }

    fun clearsOn(event: String): Boolean = event in CLEAR_EVENTS

    /**
     * 「待清理」状态的持久化，放在缓存目录之外（APP-08 Android R3-02、共性第 28 条）：缓存目录局部故障时目录内的标记可能和缓存一起写不进、
     * 删不掉，重启后同账号的归属一致就会读回旧订单。[set] / [clear] 返回是否确认写入（SharedPreferences 用 commit）；[isSet] 读不出按已设置。
     */
    interface PendingClearFlag {
        fun isSet(): Boolean

        fun set(): Boolean

        fun clear(): Boolean
    }

    /** 缓存根目录旁边的一个文件（与缓存目录同级、不在其中）：没有接线时的缺省（单测、core 自用）；App 用 SharedPreferences。 */
    class SiblingFileFlag(
        root: File,
    ) : PendingClearFlag {
        private val file = AtomicTextFile(File(root.absoluteFile.parentFile, root.name + PENDING_SUFFIX))

        override fun isSet(): Boolean = runCatching { file.read() != null }.getOrDefault(true)

        override fun set(): Boolean = runCatching { file.write("1") }.isSuccess && isSetConfirmed()

        override fun clear(): Boolean = runCatching { file.write(null) }.isSuccess && !isSetConfirmed()

        /** 确认读得到（读不出按没有：[set] 据此确认写入；[clear] 之后读不出不算清掉，见 [isSet]）。 */
        private fun isSetConfirmed(): Boolean = runCatching { file.read() != null }.getOrDefault(false)

        private companion object {
            const val PENDING_SUFFIX = ".pending_clear"
        }
    }

    /**
     * 缓存的落盘。所有方法同步执行（文件很小），只在主线程用；IO 失败不抛给页面（读按没有缓存、写放弃），只有 [clearAll] 失败才抛
     * （撤回清理、切换环境据此保留待清理并重试）。
     */
    @Suppress("TooManyFunctions") // 列表、详情、账号归属、清理各一组
    class Store internal constructor(
        private val root: File,
        /** 缓存目录之外的待清理状态（R3-02）：启动、读、写之前都核验，设了就先完成清理。 */
        private val flag: PendingClearFlag,
        /** 列目录（只有测试替换：模拟目录在、却枚举失败）。 */
        private val lister: (File) -> Array<File>?,
    ) {
        constructor(root: File, flag: PendingClearFlag = SiblingFileFlag(root)) : this(root, flag, { it.listFiles() })

        internal constructor(root: File, lister: (File) -> Array<File>?) : this(root, SiblingFileFlag(root), lister)

        private val marker = File(root, PENDING_MARKER)

        @Volatile
        private var pendingClear = false

        /**
         * 按「账号 + 持久化登录世代」分区（F-AND-25，[partitionByLoginEpoch]）：打开之后，缓存目录与归属记录按这两者的哈希分区，世代由**调用方**给出——
         * 请求 / 页面绑定时固定的那一份（F-X-17，FU-AND-0717：存储不再在读写时自己去读「此刻的世代」，旧响应在最后一次身份核对之后、写入之前遇到同账号
         * 重新登录，也只会写进它自己那一代的分区，随后由会话对账删掉）。调用方给不出世代（null、空串）按未知：不读、不写、不按归属清理。
         * 没打开（单测、core 自用）时按账号分区（原口径），给出的世代不用。
         */
        @Volatile
        private var partitioned = false

        /** 打开按登录世代分区（App 在每个取得实例的地方调；只能从关变为开）。 */
        fun partitionByLoginEpoch() {
            partitioned = true
        }

        /** 读 [scope] 的列表缓存；没有、坏了、待清理没清掉都为 null。 */
        @Synchronized
        @Suppress("ReturnCount") // 没有目录、没有文件、坏数据逐项
        fun readList(
            account: String?,
            scope: String,
            /** 页面固定的持久化登录世代（[partitionByLoginEpoch] 打开时用）。 */
            loginEpoch: String? = null,
        ): CachedOrderList? {
            val dir = accountDir(account, loginEpoch) ?: return null
            val root = read(File(dir, listFile(scope))) ?: return null
            val data = OrderJson.listData(root["data"]) ?: return discard(File(dir, listFile(scope)))
            val at = (root["fetched_at"] as? JsonPrimitive)?.longOrNull ?: return discard(File(dir, listFile(scope)))
            return CachedOrderList(data.copy(nextCursor = null), at)
        }

        /** 写 [scope] 的列表缓存（调用方保证是默认视图的第一页）。 */
        @Synchronized
        fun writeList(
            account: String?,
            scope: String,
            data: OrderListData,
            fetchedAt: Long,
            /** 请求发起时固定的持久化登录世代（F-X-17：不在写入时读当前世代）。 */
            loginEpoch: String? = null,
        ) {
            val dir = ownedDir(account, loginEpoch) ?: return
            val body =
                JsonObject(
                    mapOf(
                        "fetched_at" to JsonPrimitive(fetchedAt),
                        "data" to
                            JsonObject(
                                mapOf(
                                    "items" to JsonArray(data.items.map(OrderJson::encode)),
                                    "next_cursor" to JsonNull,
                                    "earliest_visible_date" to (data.earliestVisibleDate?.let(::JsonPrimitive) ?: JsonNull),
                                ),
                            ),
                    ),
                )
            write(File(dir, listFile(scope)), body)
        }

        @Synchronized
        @Suppress("ReturnCount") // 没有目录、没有记录、坏数据逐项
        fun readDetail(
            account: String?,
            orderId: String,
            loginEpoch: String? = null,
        ): CachedOrderDetail? {
            val dir = accountDir(account, loginEpoch) ?: return null
            val entry = details(dir)[orderId] as? JsonObject ?: return null
            val detail = (entry["detail"] as? JsonObject)?.let(OrderJson::detail) ?: return null
            val at = (entry["fetched_at"] as? JsonPrimitive)?.longOrNull ?: return null
            return CachedOrderDetail(detail, at)
        }

        /** 写一条详情；超过 [DETAIL_LIMIT] 条按最近使用淘汰（这条排到最新）。 */
        @Synchronized
        fun writeDetail(
            account: String?,
            detail: OrderDetail,
            fetchedAt: Long,
            loginEpoch: String? = null,
        ) {
            val dir = ownedDir(account, loginEpoch) ?: return
            val existing = details(dir)
            val entry = JsonObject(mapOf("fetched_at" to JsonPrimitive(fetchedAt), "detail" to OrderJson.encode(detail)))
            val ordered = LinkedHashMap<String, JsonElement>(existing.filterKeys { it != detail.summary.orderId })
            ordered[detail.summary.orderId] = entry
            val kept =
                ordered.entries
                    .toList()
                    .takeLast(DETAIL_LIMIT)
                    .associate { it.key to it.value }
            write(File(dir, DETAILS_FILE), JsonObject(kept))
        }

        /**
         * 已确认的会话身份（[account] 为 null = 已确认没有会话；[loginEpoch] 是同一份身份快照里的持久化登录世代）与缓存的归属标签不一致（登出、换账号、
         * 会话失效；分区打开后还有同账号重新登录）：全部删除。直接比较固定的分区标签，不先看会话计数（F-X-17 补充）。登录世代给不出时不知道归属，
         * 不删也不读写（F-AND-25）。**身份暂时未知时调用方不调本方法**（F-X-19 补充：未知不等于没有会话），只调 [completePendingClear]。
         * 返回是否完成（待清理没清掉、删除失败为 false：调用方登记、之后重试，F-X-19 补充三）。
         */
        @Synchronized
        @Suppress("ReturnCount") // 待清理、身份未知、标签比较逐项
        fun reconcileOwner(
            account: String?,
            loginEpoch: String? = null,
        ): Boolean {
            // 启动（connectSession）与会话变化时先核验缓存目录之外的待清理状态：上次没清完的先清完（R3-02），清不掉的读写路径同样拦住
            if (!cleaned()) return false
            val current = if (account == null) null else ownerKey(account, loginEpoch) ?: return true
            // 归属记录读不出（I/O 故障）不能当成「没有归属」：按归属不一致全部删除（共性第 20 条：清理路径的失败分支）
            val owner = runCatching { AtomicTextFile(File(root, OWNER_FILE)).read() }.getOrElse { UNREADABLE_OWNER }
            return if (owner != null && owner != current) runCatching { clearAll() }.isSuccess else true
        }

        /** 只完成已登记的待清理（身份暂时未知时也照常执行，F-X-19 补充二）；返回此刻是否没有待清理。 */
        @Synchronized
        fun completePendingClear(): Boolean = cleaned()

        /**
         * 全部删除（撤回同意、切换环境、登出、换账号）：任何一个文件删不掉都保留待清理标记并抛错（调用方保持待完成、之后重试）；
         * 待清理期间不读回、不写入。
         */
        @Synchronized
        fun clearAll() {
            pendingClear = true
            // 先把待清理状态落到缓存目录之外并确认写入（R3-02）：目录内的标记可能和缓存一起写不进；写不进时仍尽力删除，删不掉照常报失败
            val flagged = flag.set()
            if (!flagged) OrderLog.warn("orders: cache pending flag not persisted")
            // 根目录根本不存在：没有任何缓存，也就没有标记要删（不是枚举失败）
            if (!root.exists()) {
                finishClear()
                return
            }
            runCatching { if (root.isDirectory) marker.writeText("1") }
            // 区分「目录不存在」与「目录在、却枚举失败」（权限、I/O 故障时 listFiles 返回 null）：后者不能当成空目录，保留待清理标记并报失败，
            // 期间不读回、不写入；确认全部删除后才清标记（共性第 20 条）
            val children = (if (root.isDirectory) lister(root) else null) ?: incomplete("order cache directory not listable")
            val leftovers =
                children
                    .filter { it.name != PENDING_MARKER }
                    .filterNot { it.deleteRecursively() && !it.exists() }
            if (leftovers.isNotEmpty()) incomplete("order cache not fully deleted")
            // 再列一次确认只剩标记（删除期间又冒出来的文件、子目录没删干净都按失败）
            val remaining = lister(root) ?: incomplete("order cache directory not listable")
            if (remaining.any { it.name != PENDING_MARKER }) incomplete("order cache not fully deleted")
            if (marker.exists() && !marker.delete()) incomplete("order cache marker not deleted")
            finishClear()
        }

        /** 确认全部删除之后：先清缓存目录之外的待清理状态（确认写入），再清内存状态；清不掉按未完成（下次读写前再来）。 */
        private fun finishClear() {
            if (!flag.clear()) incomplete("order cache pending flag not cleared")
            pendingClear = false
        }

        /** 清理没有完成：保留待清理状态并报失败（调用方保持待完成、之后重试）。 */
        private fun incomplete(reason: String): Nothing = throw IOException(reason)

        /** 待清理期间先重试清理；清不掉返回 false（不读、不写）。目录外标记读不出按已设置（未知，F-AND-25）。 */
        private fun cleaned(): Boolean {
            if (!pendingClear && !marker.exists() && !runCatching { flag.isSet() }.getOrDefault(true)) return true
            return runCatching { clearAll() }.isSuccess
        }

        /** 账号 → 分区键：没打开分区按账号哈希；打开后按「账号 + 调用方给的登录世代」哈希，世代给不出为 null（不读不写）。 */
        @Suppress("ReturnCount") // 没打开分区、世代给不出、给出三个出口
        private fun ownerKey(
            account: String,
            loginEpoch: String?,
        ): String? {
            if (!partitioned) return hash(account)
            val epoch = loginEpoch?.takeIf { it.isNotEmpty() } ?: return null
            return hash(account + EPOCH_SEPARATOR + epoch)
        }

        private fun accountDir(
            account: String?,
            loginEpoch: String?,
        ): File? {
            if (account.isNullOrEmpty() || !cleaned()) return null
            return ownerKey(account, loginEpoch)?.let { File(root, it) }
        }

        /**
         * 写入前确保归属：别的账号（或同账号的旧登录世代）的缓存先全部删掉（删不掉就不写）。归属只按调用方给的固定身份（F-X-17）：同账号重新登录之后
         * 迟到的旧写入落进旧世代分区（并把归属标签改回旧世代），新世代读不到，下一次会话对账按标签不一致整体删除。
         */
        private fun ownedDir(
            account: String?,
            loginEpoch: String?,
        ): File? {
            reconcileOwner(account, loginEpoch)
            val dir = accountDir(account, loginEpoch) ?: return null
            val ok = runCatching { AtomicTextFile(File(root, OWNER_FILE)).write(dir.name) }.isSuccess
            return dir.takeIf { ok }
        }

        private fun details(dir: File): JsonObject {
            val file = File(dir, DETAILS_FILE)
            return read(file) ?: JsonObject(emptyMap())
        }

        /** 读一个文件：没有为 null；读不出或损坏删掉它并按没有缓存。 */
        private fun read(file: File): JsonObject? {
            val text =
                try {
                    AtomicTextFile(file).read()
                } catch (
                    @Suppress("SwallowedException") error: IOException,
                ) {
                    null
                } ?: return null
            return runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: discard(file)
        }

        private fun write(
            file: File,
            body: JsonObject,
        ) {
            runCatching { AtomicTextFile(file).write(body.toString()) }.onFailure { OrderLog.warn("orders: cache write failed") }
        }

        private fun <T> discard(file: File): T? {
            runCatching { AtomicTextFile(file).write(null) }
            return null
        }

        private fun listFile(scope: String): String = "list_$scope.json"

        companion object {
            private const val PENDING_MARKER = "pending_clear"

            /** 归属记录读不出时的占位（不会与任何账号的哈希相同）。 */
            private const val UNREADABLE_OWNER = "<unreadable>"
            private const val OWNER_FILE = "owner"
            private const val DETAILS_FILE = "details.json"
            private const val HASH_HEX = 16
            private const val EPOCH_SEPARATOR = "\u0000login_epoch:"

            /** 账号 → 目录名（SHA-256 前 16 位十六进制；不落明文账号）。 */
            fun hash(account: String): String =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(account.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                    .take(HASH_HEX)
        }
    }
}
