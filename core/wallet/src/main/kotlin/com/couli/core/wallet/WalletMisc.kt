package com.couli.core.wallet

import com.couli.core.clientcore.AtomicTextFile
import com.couli.core.router.AppVersion
import com.couli.core.router.EntryGuard
import com.couli.core.router.GuardState
import com.couli.core.router.PrivacyState
import com.couli.core.router.RouteParams
import com.couli.core.router.RouteRequest
import com.couli.core.router.RouteSource
import com.couli.core.router.SessionState
import com.couli.core.router.UpdateState
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 收益看板入口（G20，F-WDR-14，M-公开）：同时满足才显示——Config features.flags["earnings.dashboard.visible"] 为 true，且 Earnings 路由在本端本版本
 * 可用（routes.json since 非 null 且不高于当前版本）。现在三端 since 都为 null，入口一律不显示。
 */
object EarningsEntry {
    fun visible(
        flag: Boolean?,
        routeSince: String?,
        appVersion: String,
    ): Boolean = flag == true && routeSince != null && AppVersion.atLeast(appVersion, routeSince)
}

/**
 * 资金与实名四页（Withdraw、PayoutAccount、LaborAgreement、RealName）不接受任何路由参数（BR-WDR-01 细则，AC-S1-81 ⑫）：全部丢弃、照常打开，记
 * route_params_dropped（只有路由名与个数）。其余路由的参数原样（由 Router 按 routes.json 校验）。
 */
object FundRouteParams {
    const val EVENT = "route_params_dropped"

    data class Filtered(
        val openedParams: JsonObject,
        val dropped: Int,
        val event: String?,
    )

    fun filtered(
        route: String,
        params: JsonObject,
    ): Filtered =
        when (route) {
            in RouteParams.dropAllParamsRoutes -> Filtered(JsonObject(emptyMap()), params.size, EVENT.takeIf { params.isNotEmpty() })

            // DeleteAccount（APP-13a G21）：参数全部丢弃、照常打开，不记 route_params_dropped（events.json 的 route 枚举里没有它）
            in RouteParams.silentDropRoutes -> Filtered(JsonObject(emptyMap()), params.size, null)

            else -> Filtered(params, 0, null)
        }

    /**
     * 向量接线。[opened]、openedParams、dropped 取自真实 Router 守卫（[EntryGuard.decide]，App 内来源、已同意、版本正常、已绑手机并实名；
     * since=null 的路由按 Debug 路由可用性覆盖放行，G43）的结论；事件只按 [filtered]。
     */
    fun filter(
        route: String,
        params: JsonObject,
    ): JsonObject {
        val view = filtered(route, params)
        val decision = EntryGuard.decide(RouteRequest(route, params, RouteSource.IN_APP), probeState(route))
        val opened = decision.opensPage
        return jsonObj(
            "opened" to JsonPrimitive(opened),
            "openedParams" to (if (opened) decision.params else view.openedParams),
            "dropped" to JsonPrimitive(if (opened) decision.paramsDropped else view.dropped),
            "event" to view.event.json(),
        )
    }

    private fun probeState(route: String): GuardState =
        GuardState(
            PrivacyState.CONSENTED,
            UpdateState.OK,
            SessionState.PHONE_BOUND,
            environmentSettled = true,
            appVersion = PROBE_VERSION,
            isDebugBuild = false,
            realnameVerified = true,
            availabilityOverrides = setOf(route),
        )

    private const val PROBE_VERSION = "1.0.0"
}

/** 一份缓存的钱包摘要与它的成功获取时刻。 */
data class CachedWalletSummary(
    val summary: WalletSummary,
    val fetchedAt: Long,
)

/**
 * 钱包断网显示上次数据（TECH-20，G22）：只缓存钱包摘要（GET /v1/wallet/summary 最近一次成功响应），Withdraw、rules、提现记录、收款账号一律不缓存。
 * 登出、换账号、会话失效、撤回同意、切换环境时删除；进后台、刷新成功不删（刷新成功是更新缓存）。只有传输层失败才用缓存，5xxxx 不用。
 *
 * 落盘（[Store]）：App 私有、不备份的目录（noBackupFilesDir）下按账号分目录（目录名是账号的哈希），原子写；删除要确认落盘成功，失败时保留「待清理」
 * 状态（内存 + 缓存目录之外的 [PendingClearFlag] + 目录内标记文件），清理完成之前不读回、不写入；区分「目录不存在」与「枚举失败」
 * （APP-08 共性第 20、28 条，与订单缓存同一做法）。
 */
object WalletStaleCache {
    const val KIND_SUMMARY = "summary"

    /** 清缓存的事件（三端同名）。 */
    val CLEAR_EVENTS: Set<String> = setOf("logout", "account_switch", "session_invalid", "consent_revoked", "env_switch")

    fun isCacheable(kind: String): Boolean = kind == KIND_SUMMARY

    fun clearsOn(event: String): Boolean = event in CLEAR_EVENTS

    /** 「待清理」状态的持久化，放在缓存目录之外（App 用 SharedPreferences commit；[set] / [clear] 返回是否确认写入，[isSet] 读不出按已设置）。 */
    interface PendingClearFlag {
        fun isSet(): Boolean

        fun set(): Boolean

        fun clear(): Boolean
    }

    /** 缓存根目录旁边的一个文件（与缓存目录同级、不在其中）：没有接线时的缺省（单测）。 */
    class SiblingFileFlag(
        root: File,
    ) : PendingClearFlag {
        private val file = AtomicTextFile(File(root.absoluteFile.parentFile, root.name + ".pending_clear"))

        override fun isSet(): Boolean = runCatching { file.read() != null }.getOrDefault(true)

        override fun set(): Boolean = runCatching { file.write("1") }.isSuccess && runCatching { file.read() != null }.getOrDefault(false)

        override fun clear(): Boolean =
            runCatching { file.write(null) }.isSuccess && !runCatching { file.read() != null }.getOrDefault(true)
    }

    /** 落盘。方法同步执行（文件很小），只在主线程用；IO 失败不抛给页面（读按没有缓存、写放弃），只有 [clearAll] 失败才抛。 */
    @Suppress("TooManyFunctions") // 读写、归属、清理与登录世代各一组
    class Store(
        private val root: File,
        private val flag: PendingClearFlag = SiblingFileFlag(root),
        /** 列目录（只有测试替换：模拟目录在、却枚举失败）。 */
        private val lister: (File) -> Array<File>? = { it.listFiles() },
    ) {
        private val marker = File(root, PENDING_MARKER)

        @Volatile
        private var pendingClear = false

        /**
         * 按「账号 + 持久化登录世代」分区（F-AND-25；与订单缓存同一做法，见 OrderStaleCache.Store.partitionByLoginEpoch）：世代由调用方给出（页面固定身份
         * 时的那一份，F-X-17，FU-AND-0717），存储不在读写时读「此刻的世代」；给不出按未知，不读不写。没打开时按账号分区。
         */
        @Volatile
        private var partitioned = false

        /** 打开按登录世代分区（只能从关变为开）。 */
        fun partitionByLoginEpoch() {
            partitioned = true
        }

        @Synchronized
        @Suppress("ReturnCount")
        fun read(
            account: String?,
            /** 页面固定的持久化登录世代。 */
            loginEpoch: String? = null,
        ): CachedWalletSummary? {
            val dir = accountDir(account, loginEpoch) ?: return null
            val file = File(dir, SUMMARY_FILE)
            val text = runCatching { AtomicTextFile(file).read() }.getOrNull() ?: return null
            val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return discard(file)
            val summary = WalletJson.summary(root["data"]) ?: return discard(file)
            val at = (root["fetched_at"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return discard(file)
            return CachedWalletSummary(summary, at)
        }

        @Synchronized
        fun write(
            account: String?,
            summary: WalletSummary,
            fetchedAt: Long,
            /** 页面 / 请求固定的持久化登录世代（F-X-17：不在写入时读当前世代）。 */
            loginEpoch: String? = null,
        ) {
            val dir = ownedDir(account, loginEpoch) ?: return
            val body = JsonObject(mapOf("fetched_at" to JsonPrimitive(fetchedAt), "data" to WalletJson.encode(summary)))
            runCatching { AtomicTextFile(File(dir, SUMMARY_FILE)).write(body.toString()) }
        }

        /**
         * 已确认的会话身份（[account] 为 null = 已确认没有会话；[loginEpoch] 同一份快照里的持久化登录世代）与归属标签不一致（登出、换账号、会话失效、同账号
         * 重新登录）：全部删除（直接比较固定标签，F-X-17 补充）。世代给不出时不动。身份暂时未知时调用方只调 [completePendingClear]（F-X-19 补充）。
         * 返回是否完成（没完成调用方登记、之后重试，F-X-19 补充三）。
         */
        @Synchronized
        @Suppress("ReturnCount") // 待清理、身份未知、标签比较逐项
        fun reconcileOwner(
            account: String?,
            loginEpoch: String? = null,
        ): Boolean {
            if (!cleaned()) return false
            val current = if (account == null) null else ownerKey(account, loginEpoch) ?: return true
            val owner = runCatching { AtomicTextFile(File(root, OWNER_FILE)).read() }.getOrElse { UNREADABLE_OWNER }
            return if (owner != null && owner != current) runCatching { clearAll() }.isSuccess else true
        }

        /** 只完成已登记的待清理（身份暂时未知时也照常执行，F-X-19 补充二）；返回此刻是否没有待清理。 */
        @Synchronized
        fun completePendingClear(): Boolean = cleaned()

        /** 全部删除：任何一个文件删不掉都保留待清理标记并抛错；待清理期间不读回、不写入。 */
        @Synchronized
        fun clearAll() {
            pendingClear = true
            flag.set()
            if (!root.exists()) {
                finishClear()
                return
            }
            runCatching { if (root.isDirectory) marker.writeText("1") }
            val children = (if (root.isDirectory) lister(root) else null) ?: incomplete("wallet cache directory not listable")
            val leftovers = children.filter { it.name != PENDING_MARKER }.filterNot { it.deleteRecursively() && !it.exists() }
            if (leftovers.isNotEmpty()) incomplete("wallet cache not fully deleted")
            val remaining = lister(root) ?: incomplete("wallet cache directory not listable")
            if (remaining.any { it.name != PENDING_MARKER }) incomplete("wallet cache not fully deleted")
            if (marker.exists() && !marker.delete()) incomplete("wallet cache marker not deleted")
            finishClear()
        }

        private fun finishClear() {
            if (!flag.clear()) incomplete("wallet cache pending flag not cleared")
            pendingClear = false
        }

        private fun incomplete(reason: String): Nothing = throw IOException(reason)

        /** 目录外标记读不出按已设置（未知，F-AND-25）。 */
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

        /** 写入前确保归属（按调用方给的固定身份，F-X-17）。 */
        private fun ownedDir(
            account: String?,
            loginEpoch: String?,
        ): File? {
            reconcileOwner(account, loginEpoch)
            val dir = accountDir(account, loginEpoch) ?: return null
            val ok = runCatching { AtomicTextFile(File(root, OWNER_FILE)).write(dir.name) }.isSuccess
            return dir.takeIf { ok }
        }

        private fun <T> discard(file: File): T? {
            runCatching { AtomicTextFile(file).write(null) }
            return null
        }

        companion object {
            private const val PENDING_MARKER = "pending_clear"
            private const val UNREADABLE_OWNER = "<unreadable>"
            private const val OWNER_FILE = "owner"
            private const val SUMMARY_FILE = "summary.json"
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
