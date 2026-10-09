package com.couli.core.orders

import com.couli.core.network.percentEncode
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 订单列表的查询状态（纯逻辑，三端向量 specs/order-vectors.json 的 query 组；BR-TEXT-02 细则「订单列表的状态分组与查找」、G11）：
 * - 只带非默认的参数：scope 必带；status_group 为 all 时不带（契约「不传 = 全部」）；平台、月份、搜索词为空不带；从不带 display_status；
 * - 搜索词只去首尾空白，其余原样上传（客户端不做任何匹配）；规整后为空不带；最多 40 个 Unicode 字符（超长截到 40，输入框本身也限 40）；
 * - 切子 Tab：分组回到「全部」，平台、月份、搜索词清空；筛选可叠加；
 * - 任一条件变化或下拉刷新都开新一轮（cursor 清空）；翻页带上一页的 next_cursor，为 null 时不再发请求。
 *
 * [apply] 的操作名与向量一致：set_scope、set_group、set_platform、set_month、set_q、next_page、refresh。
 */
@Suppress("TooManyFunctions") // 向量的每个操作一个入口
class OrderQuery private constructor(
    scope: String,
) {
    var scope: String = scope
        private set
    var group: String = ALL
        private set
    var platform: String? = null
        private set
    var month: String? = null
        private set
    var q: String? = null
        private set
    var cursor: String? = null
        private set

    /** 最近一次翻页拿到的 next_cursor 为 null：已到底，不再发翻页请求。 */
    private var exhausted = false

    /** 查询轮次：任一条件变化或刷新加 1（翻页不变）。 */
    var round: Long = 0
        private set

    /** 原地修改（三端测试接线约定）。 */
    fun apply(
        op: String,
        value: String?,
    ) {
        when (op) {
            OP_SCOPE -> setScope(value)
            OP_GROUP -> setGroup(value)
            OP_PLATFORM -> setPlatform(value)
            OP_MONTH -> setMonth(value)
            OP_Q -> setQ(value)
            OP_NEXT_PAGE -> nextPage(value)
            OP_REFRESH -> newRound()
            else -> Unit
        }
    }

    /** 切子 Tab：分组回到「全部」、其余条件清空（BR-TEXT-02 细则）。 */
    fun setScope(value: String?) {
        scope = normalizeScope(value)
        group = ALL
        platform = null
        month = null
        q = null
        newRound()
    }

    fun setGroup(value: String?) {
        group = value?.takeIf { it in GROUPS } ?: ALL
        newRound()
    }

    fun setPlatform(value: String?) {
        platform = value?.takeIf(String::isNotEmpty)
        newRound()
    }

    fun setMonth(value: String?) {
        month = value?.takeIf { MONTH.matches(it) }
        newRound()
    }

    /** 提交或清空搜索框（不是每次键入；G11）。 */
    fun setQ(value: String?) {
        q = normalizeQ(value)
        newRound()
    }

    /** 翻页：带上一页的 next_cursor；为 null 时不再发请求（[sendsRequest] = false）。 */
    fun nextPage(nextCursor: String?) {
        if (nextCursor.isNullOrEmpty()) {
            exhausted = true
            return
        }
        cursor = nextCursor
        exhausted = false
    }

    private fun newRound() {
        cursor = null
        exhausted = false
        round += 1
    }

    /** 这一刻要不要发请求：已到底的翻页不发。 */
    fun sendsRequest(): Boolean = !exhausted

    /** 请求参数（只带非默认的；三端向量 params）。 */
    fun params(): JsonObject {
        val out = linkedMapOf<String, JsonElement>("scope" to JsonPrimitive(scope))
        if (group != ALL) out["status_group"] = JsonPrimitive(group)
        platform?.let { out["platform"] = JsonPrimitive(it) }
        month?.let { out["paid_month"] = JsonPrimitive(it) }
        q?.let { out["q"] = JsonPrimitive(it) }
        cursor?.let { out["cursor"] = JsonPrimitive(it) }
        return JsonObject(out)
    }

    /** 查询串（ApiCall.query；百分号编码，不含 "?"）。 */
    fun queryString(): String =
        params().entries.joinToString("&") { (key, value) -> key + "=" + percentEncode((value as JsonPrimitive).content) }

    /** 默认视图（第一页、全部分组、没有平台 / 月份 / 搜索词）：只有它可以落盘缓存（G10）。 */
    val isDefaultView: Boolean get() = group == ALL && platform == null && month == null && q == null

    /** 有筛选或搜索词（传输失败时不用缓存，G10）。 */
    val filtered: Boolean get() = !isDefaultView

    /** 当前条件的快照（保存与恢复；不含 cursor）。 */
    fun conditions(): OrderConditions = OrderConditions(scope, group, platform, month, q)

    companion object {
        const val SELF = "self"
        const val SHARE = "share"
        const val ALL = "all"
        const val OP_INIT = "init"
        const val OP_SCOPE = "set_scope"
        const val OP_GROUP = "set_group"
        const val OP_PLATFORM = "set_platform"
        const val OP_MONTH = "set_month"
        const val OP_Q = "set_q"
        const val OP_NEXT_PAGE = "next_page"
        const val OP_REFRESH = "refresh"

        /** 搜索框最多 40 个 Unicode 字符（openapi listOrders q maxLength 40，G11）。 */
        const val Q_MAX = 40

        /** 状态分组（enum order_status_group，BR-TEXT-02 细则）；顺序即胶囊顺序。 */
        val GROUPS: List<String> = listOf(ALL, "estimating", "credited", "no_rebate")
        private val MONTH = Regex("^[0-9]{4}-(0[1-9]|1[0-2])$")

        /** 路由参数 scope（routes.json OrderList：self / share，缺省 self）。 */
        fun initial(scope: String?): OrderQuery = OrderQuery(normalizeScope(scope))

        /** 按保存的条件重建（配置重建、进程回收后恢复；不合法的条件丢弃）。 */
        fun restore(saved: OrderConditions): OrderQuery =
            OrderQuery(normalizeScope(saved.scope)).apply {
                group = saved.group.takeIf { it in GROUPS } ?: ALL
                platform = saved.platform?.takeIf(String::isNotEmpty)
                month = saved.month?.takeIf { MONTH.matches(it) }
                q = normalizeQ(saved.q)
            }

        fun normalizeScope(value: String?): String = if (value == SHARE) SHARE else SELF

        /** 只去首尾空白（含全角空格），超过 40 个 Unicode 字符截到 40；空为 null。 */
        fun normalizeQ(value: String?): String? {
            val trimmed = value?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            return limit(trimmed)
        }

        /** 输入框的上限：按 Unicode 码点截到 40（不切开代理对）。 */
        fun limit(text: String): String {
            val count = text.codePointCount(0, text.length)
            return if (count <= Q_MAX) text else text.substring(0, text.offsetByCodePoints(0, Q_MAX))
        }
    }
}

/** 一轮查询的条件（保存到 SavedStateHandle 用）。 */
data class OrderConditions(
    val scope: String,
    val group: String,
    val platform: String?,
    val month: String?,
    val q: String?,
)

/**
 * 月份筛选的选项（G12）：当前月份按本机时钟在 +08:00 下取；earliest_visible_date 非空时从当前月份倒推到它所在的月份（含），为 null 时只给
 * 最近 12 个月；另加「全部月份」（不带 paid_month）。
 */
object OrderMonthOptions {
    private val SHANGHAI: ZoneId = ZoneId.of("Asia/Shanghai")
    private val FORMAT = DateTimeFormatter.ofPattern("yyyy-MM", Locale.ROOT)
    private const val RECENT = 12

    /** 防御：服务端给了很早的 earliest_visible_date 时最多列 10 年。 */
    private const val MAX_MONTHS = 120

    fun months(
        earliestVisibleDate: String?,
        nowMs: Long,
    ): List<String> {
        val current = YearMonth.from(Instant.ofEpochMilli(nowMs).atZone(SHANGHAI))
        val earliest = earliestVisibleDate?.let { runCatching { YearMonth.from(LocalDate.parse(it)) }.getOrNull() }
        val count =
            if (earliest == null) {
                RECENT
            } else {
                val span = (current.year - earliest.year) * MONTHS_PER_YEAR + (current.monthValue - earliest.monthValue) + 1
                span.coerceIn(1, MAX_MONTHS)
            }
        return (0 until count).map { current.minusMonths(it.toLong()).format(FORMAT) }
    }

    /** 三端向量的 JSON 出口：{months, includesAll}。 */
    fun of(
        earliestVisibleDate: String?,
        nowMs: Long,
    ): JsonObject =
        JsonObject(
            mapOf(
                "months" to kotlinx.serialization.json.JsonArray(months(earliestVisibleDate, nowMs).map(::JsonPrimitive)),
                "includesAll" to JsonPrimitive(true),
            ),
        )

    private const val MONTHS_PER_YEAR = 12
}

/** 平台筛选的选项（G13）：enums/platform 里 phase 为 M-内测 的淘宝、京东、拼多多；null 为「全部平台」。 */
object OrderPlatformOptions {
    val platforms: List<String> = listOf("taobao", "jd", "pdd")
}
