package com.couli.core.orders

import com.couli.generated.DisplayStatus
import com.couli.generated.OrderTimelineNode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 订单列表的一行（openapi OrderSummary；订单详情在它之上加 [OrderDetail] 的字段）。金额一律可空的整数分（NullableFen：null 为未知，
 * 不显示、不补 0、不由别的字段推算）。[orderNo] 与 [maskedOrderNo] 恰好一个（自购单完整、分享单脱敏，BR-TEXT-02 细则「订单号的显示」）。
 * [displayStatusRaw] 原样保留，显示一律经 [displayStatus]（客户端外的编码按 UNKNOWN）。
 * [actualFen] / [clawbackFen] 只在详情响应里有（锁定契约的 OrderSummary 没有这两个字段，rule-gaps Android A01）。
 */
data class OrderSummary(
    val orderId: String,
    val platform: String,
    val title: String?,
    val imageUrl: String?,
    val payAmountFen: Long?,
    val quantity: Int?,
    val orderNo: String?,
    val maskedOrderNo: String?,
    val displayStatusRaw: String,
    val reason: String?,
    val estRebateFen: Long?,
    val paidAt: String?,
    val isOtherProduct: Boolean,
    val actualFen: Long? = null,
    val clawbackFen: Long? = null,
) {
    /** 显示用的状态：契约 11 个编码之外（含大小写不同、空串）一律 UNKNOWN（「状态更新中」）。 */
    val displayStatus: DisplayStatus get() = OrderJson.status(displayStatusRaw)

    /** 分享单（只有脱敏单号）：金额前缀用「预估推广收益 / 推广收益」，没有复制。 */
    val isShare: Boolean get() = maskedOrderNo != null && orderNo == null
}

/** 时间线的一个节点（openapi OrderTimelineItem）：[at] 为 null 表示还没发生或平台不给时间（置灰）；[period] 只在 credit_expected 上。 */
data class OrderTimelineItem(
    val nodeRaw: String,
    val at: String?,
    val period: String?,
) {
    val node: OrderTimelineNode get() =
        OrderTimelineNode.entries.firstOrNull { it != OrderTimelineNode.UNKNOWN && it.raw == nodeRaw }
            ?: OrderTimelineNode.UNKNOWN
}

/** 订单详情（openapi OrderDetail）：列表行的字段 + 详情字段。 */
data class OrderDetail(
    val summary: OrderSummary,
    val productKey: String?,
    val reasonAction: List<String>,
    val timeline: List<OrderTimelineItem>,
    val expectedCreditPeriod: String?,
    val creditOverdue: Boolean,
    val appealPending: Boolean,
    /** null（平台的判定字段还没验证通过）按「不是比价订单」处理（BR-TEXT-03）。 */
    val isPriceCompare: Boolean?,
)

/** 待跟单卡的一项（openapi PendingTrack）：按尝试（attempt_id）显示与关闭（BR-ATTR-21 细则「完成与关闭按尝试」）。 */
data class PendingTrack(
    val linkId: String,
    val attemptId: String,
    val platform: String,
    val jumpedAt: String,
    val showClaimEntry: Boolean,
    val dismissed: Boolean,
)

/**
 * 列表一页（openapi OrderListData）。[dropped]：按坏数据丢弃的行数（只记日志，不显示任何单号）。
 * [claimEntryPinned]：「未跟单？去找回」置顶（openapi `claim_entry_pinned`，只 scope=self，服务端按 BR-ATTR-17 计算；G-O04）。只读、缺省 false，
 * 只认 JSON 布尔 true；与当时时间有关，离线缓存不存（读回的缓存一律 false）。
 */
data class OrderListData(
    val items: List<OrderSummary>,
    val nextCursor: String?,
    val earliestVisibleDate: String?,
    val dropped: Int = 0,
    val claimEntryPinned: Boolean = false,
)

/** 日志出口（App 接到 Logcat；不写订单号、金额与任何个人信息）。缺省丢弃，纯逻辑单测不依赖 Android Log。 */
object OrderLog {
    @Volatile
    var sink: (String) -> Unit = {}

    fun warn(message: String) = sink(message)
}

/**
 * 订单接口的手写解析（照 TradeJson 的先例，openapi 的订单模型没有生成）。
 * - [summary] / [detail] / [pendingTrack]：严格解析网络数据，必填字段缺失或类型不对、order_no 与 masked_order_no 两个都有或都没有的
 *   整条按坏数据丢弃（返回 null，记日志，不显示任何单号）；
 * - [lenientSummary] / [lenientDetail]：显示规则的 JSON 入参（三端向量）用，缺字段按空值，不判定行是否合法。
 */
@Suppress("TooManyFunctions") // 每个模型一个解析入口 + 取值小函数
object OrderJson {
    /** 显示状态：只认契约里的 11 个编码（区分大小写），其余（含空串）为 UNKNOWN。 */
    fun status(raw: String?): DisplayStatus =
        DisplayStatus.entries.firstOrNull { it != DisplayStatus.UNKNOWN && it.raw == raw } ?: DisplayStatus.UNKNOWN

    @Suppress("ReturnCount") // 不是对象、没有 items、解析三个出口
    fun listData(data: JsonElement?): OrderListData? {
        val root = data as? JsonObject ?: return null
        val rows = root["items"] as? JsonArray ?: return null
        val items = rows.mapNotNull { (it as? JsonObject)?.let(::summary) }
        val dropped = rows.size - items.size
        if (dropped > 0) OrderLog.warn("orders: dropped $dropped malformed rows")
        return OrderListData(
            items = items,
            nextCursor = root.text("next_cursor")?.takeIf(String::isNotEmpty),
            earliestVisibleDate = root.text("earliest_visible_date")?.takeIf(String::isNotEmpty),
            dropped = dropped,
            claimEntryPinned = (root["claim_entry_pinned"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true,
        )
    }

    /** 严格解析一行；坏数据返回 null。 */
    @Suppress("ReturnCount") // 逐个必填字段短路
    fun summary(obj: JsonObject): OrderSummary? {
        val orderId = obj.text("order_id")?.takeIf(String::isNotEmpty) ?: return null
        val platform = obj.text("platform")?.takeIf(String::isNotEmpty) ?: return null
        val status = obj.text("display_status") ?: return null
        val orderNo = obj.text("order_no")?.takeIf(String::isNotEmpty)
        val masked = obj.text("masked_order_no")?.takeIf(String::isNotEmpty)
        if ((orderNo == null) == (masked == null)) return null
        if (!obj.fenValid("pay_amount_fen") || !obj.fenValid("est_rebate_fen")) return null
        return lenientSummary(obj).copy(orderId = orderId, platform = platform, displayStatusRaw = status)
    }

    /** 显示规则用的宽松解析：缺字段按空值（不判定合法性；合法性由 [OrderNumberDisplay] 等各自判定）。 */
    fun lenientSummary(obj: JsonObject): OrderSummary =
        OrderSummary(
            orderId = obj.text("order_id").orEmpty(),
            platform = obj.text("platform").orEmpty(),
            title = obj.text("title"),
            imageUrl = obj.text("image_url")?.takeIf(String::isNotEmpty),
            payAmountFen = obj.fen("pay_amount_fen"),
            quantity = obj.long("quantity")?.toInt()?.takeIf { it >= 1 },
            orderNo = obj.text("order_no")?.takeIf(String::isNotEmpty),
            maskedOrderNo = obj.text("masked_order_no")?.takeIf(String::isNotEmpty),
            displayStatusRaw = obj.text("display_status").orEmpty(),
            reason = obj.text("reason"),
            estRebateFen = obj.fen("est_rebate_fen"),
            paidAt = obj.text("paid_at"),
            isOtherProduct = obj.bool("is_other_product") == true,
            actualFen = obj.fen("actual_fen"),
            clawbackFen = obj.fen("clawback_fen"),
        )

    /** 严格解析详情；坏数据返回 null。 */
    fun detail(obj: JsonObject): OrderDetail? {
        val summary = summary(obj) ?: return null
        return lenientDetail(obj).copy(summary = summary)
    }

    fun lenientDetail(obj: JsonObject): OrderDetail =
        OrderDetail(
            summary = lenientSummary(obj),
            productKey = obj.text("product_key")?.takeIf(String::isNotEmpty),
            reasonAction =
                (obj["reason_action"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
                    .orEmpty(),
            timeline = (obj["timeline"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::timelineItem) }.orEmpty(),
            expectedCreditPeriod = obj.text("expected_credit_period"),
            creditOverdue = obj.bool("credit_overdue") == true,
            appealPending = obj.bool("appeal_pending") == true,
            isPriceCompare = obj.bool("is_price_compare"),
        )

    fun timelineItem(obj: JsonObject): OrderTimelineItem? {
        val node = obj.text("node") ?: return null
        return OrderTimelineItem(node, obj.text("at"), obj.text("period"))
    }

    fun pendingTracks(data: JsonElement?): List<PendingTrack>? {
        val rows = (data as? JsonObject)?.get("items") as? JsonArray ?: return null
        return rows.mapNotNull { (it as? JsonObject)?.let(::pendingTrack) }
    }

    /** 待跟单卡一项：缺 link_id / attempt_id / platform 的丢弃（关闭请求带不出正确的尝试）。 */
    @Suppress("ReturnCount")
    fun pendingTrack(obj: JsonObject): PendingTrack? {
        val linkId = obj.text("link_id")?.takeIf(String::isNotEmpty) ?: return null
        val attemptId = obj.text("attempt_id")?.takeIf(String::isNotEmpty) ?: return null
        val platform = obj.text("platform")?.takeIf(String::isNotEmpty) ?: return null
        return PendingTrack(
            linkId = linkId,
            attemptId = attemptId,
            platform = platform,
            jumpedAt = obj.text("jumped_at").orEmpty(),
            showClaimEntry = obj.bool("show_claim_entry") == true,
            dismissed = obj.bool("dismissed") == true,
        )
    }

    // ---------------------------------------------------------------- 序列化（缓存落盘；只写契约里的字段）

    fun encode(item: OrderSummary): JsonObject =
        JsonObject(
            linkedMapOf<String, JsonElement>(
                "order_id" to JsonPrimitive(item.orderId),
                "platform" to JsonPrimitive(item.platform),
                "title" to item.title.json(),
                "image_url" to item.imageUrl.json(),
                "pay_amount_fen" to item.payAmountFen.json(),
                "quantity" to (item.quantity?.let { JsonPrimitive(it) } ?: JsonNull),
                "display_status" to JsonPrimitive(item.displayStatusRaw),
                "reason" to item.reason.json(),
                "est_rebate_fen" to item.estRebateFen.json(),
                "paid_at" to item.paidAt.json(),
                "is_other_product" to JsonPrimitive(item.isOtherProduct),
            ).apply {
                item.orderNo?.let { put("order_no", JsonPrimitive(it)) }
                item.maskedOrderNo?.let { put("masked_order_no", JsonPrimitive(it)) }
                item.actualFen?.let { put("actual_fen", JsonPrimitive(it)) }
                item.clawbackFen?.let { put("clawback_fen", JsonPrimitive(it)) }
            },
        )

    fun encode(detail: OrderDetail): JsonObject =
        JsonObject(
            encode(detail.summary) +
                mapOf(
                    "product_key" to detail.productKey.json(),
                    "reason_action" to JsonArray(detail.reasonAction.map(::JsonPrimitive)),
                    "timeline" to
                        JsonArray(
                            detail.timeline.map {
                                JsonObject(mapOf("node" to JsonPrimitive(it.nodeRaw), "at" to it.at.json(), "period" to it.period.json()))
                            },
                        ),
                    "expected_credit_period" to detail.expectedCreditPeriod.json(),
                    "credit_overdue" to JsonPrimitive(detail.creditOverdue),
                    "actual_fen" to detail.summary.actualFen.json(),
                    "clawback_fen" to detail.summary.clawbackFen.json(),
                    "appeal_pending" to JsonPrimitive(detail.appealPending),
                    "is_price_compare" to (detail.isPriceCompare?.let { JsonPrimitive(it) } ?: JsonNull),
                ),
        )

    // ---------------------------------------------------------------- 取值

    internal fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    internal fun JsonObject.bool(key: String): Boolean? = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    private fun JsonObject.long(key: String): Long? = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

    /** 整数分：只认 JSON 整数（字符串、小数、布尔一律当作没有）。 */
    internal fun JsonObject.fen(key: String): Long? = long(key)

    /** 金额字段要么是 null / 缺失，要么是整数；别的类型（字符串、小数）按坏数据。 */
    @Suppress("ReturnCount") // 缺失、null、整数三个出口
    private fun JsonObject.fenValid(key: String): Boolean {
        val value = get(key) ?: return true
        if (value == JsonNull) return true
        return (value as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull != null
    }

    private fun String?.json(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull

    private fun Long?.json(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull
}
