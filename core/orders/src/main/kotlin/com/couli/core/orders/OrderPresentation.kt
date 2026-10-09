package com.couli.core.orders

import com.couli.core.router.RouteParams
import com.couli.generated.DisplayStatus
import com.couli.generated.OrderTimelineNode
import com.couli.generated.RouteSpec
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * 订单卡与详情的显示规则（纯逻辑；三端向量 specs/order-vectors.json）。每个规则一个对象：强类型入口给页面用，`of(JsonObject)` 是向量的
 * JSON 出口（只做解包与组装，判定都在强类型入口里）。金额只用服务端字段，为 null 时不显示、不补 0、不由别的字段推算（BR-TEXT-02、
 * APP-06a 第 1 条）。
 */

/** 订单号的显示（BR-TEXT-02 细则「订单号的显示」）：自购单完整可复制；分享单脱敏原样、不可复制；两个都有或都没有为坏数据，不显示任何单号。 */
data class OrderNumberView(
    val text: String?,
    val copyable: Boolean,
    val valid: Boolean,
)

object OrderNumberDisplay {
    fun of(item: OrderSummary): OrderNumberView {
        val full = item.orderNo
        val masked = item.maskedOrderNo
        return when {
            full != null && masked == null -> OrderNumberView(full, copyable = true, valid = true)
            masked != null && full == null -> OrderNumberView(masked, copyable = false, valid = true)
            else -> OrderNumberView(null, copyable = false, valid = false)
        }
    }

    fun of(item: JsonObject): JsonObject =
        of(OrderJson.lenientSummary(item)).let {
            obj("text" to it.text.json(), "copyable" to JsonPrimitive(it.copyable), "valid" to JsonPrimitive(it.valid))
        }
}

/** 标题与商品图：is_other_product 时标题用「好友购买的其他商品」、图用占位；标题为 null 时标题区留空（不编造）、图用占位。 */
data class OrderTitleView(
    val title: String?,
    val titleKey: String?,
    val imageUrl: String?,
) {
    val usePlaceholderImage: Boolean get() = imageUrl == null
}

object OrderTitleImage {
    const val OTHER_PRODUCT_KEY = "order_list.other_product"

    fun of(item: OrderSummary): OrderTitleView =
        when {
            item.isOtherProduct -> OrderTitleView(null, OTHER_PRODUCT_KEY, null)
            item.title == null -> OrderTitleView(null, null, null)
            else -> OrderTitleView(item.title, null, item.imageUrl)
        }

    fun of(item: JsonObject): JsonObject =
        of(OrderJson.lenientSummary(item)).let {
            obj(
                "title" to it.title.json(),
                "titleKey" to it.titleKey.json(),
                "usePlaceholderImage" to JsonPrimitive(it.usePlaceholderImage),
            )
        }
}

/** 状态文案键（G01：草稿表，原文 BR-TEXT-02 映射表）。INVALID、CLAWED_BACK 的 hint 由原因区给（[WhyNoRebate]），这里为 null。 */
data class OrderStatusView(
    val status: DisplayStatus,
    val labelKey: String,
    val hintKey: String?,
)

object OrderStatusText {
    private val HINTED =
        setOf(
            DisplayStatus.DEPOSIT_PAID,
            DisplayStatus.PAID,
            DisplayStatus.WAITING,
            DisplayStatus.CREDITING,
            DisplayStatus.RIGHTS_PENDING,
            DisplayStatus.REVIEWING,
            DisplayStatus.UNKNOWN,
        )

    fun of(status: DisplayStatus): OrderStatusView {
        val name = status.keyName()
        return OrderStatusView(status, "order_status.$name.label", "order_status.$name.hint".takeIf { status in HINTED })
    }

    fun of(displayStatusRaw: String): JsonObject =
        of(OrderJson.status(displayStatusRaw)).let {
            obj(
                "labelKey" to JsonPrimitive(it.labelKey),
                "hintKey" to it.hintKey.json(),
                "statusRaw" to JsonPrimitive(it.status.keyName()),
            )
        }
}

/** 状态在文案键里的名字：契约编码原样，UNKNOWN 写成 UNKNOWN（生成枚举的 raw 是 __unknown__）。 */
internal fun DisplayStatus.keyName(): String = if (this == DisplayStatus.UNKNOWN) "UNKNOWN" else raw

/**
 * 金额格的一格。[fen] 为 null 时不显示金额（标签照常）；[fixedZero] 是 INVALID 的「返利 ¥0」（映射表原文，固定值，不是推算）；
 * [minus] 是扣回「-¥z」；[maskable] 为金额隐藏开关管得着的格（预估返、实返、推广收益、扣回、返利；实付与已付定金不隐藏，G21）。
 */
data class AmountCellView(
    val labelKey: String,
    val fen: Long?,
    val minus: Boolean = false,
    val fixedZero: Boolean = false,
    val maskable: Boolean = false,
    val rebate: Boolean = false,
)

/** 两列金额格：[left] 实付 / 已付定金；[right] 预估返、实返、推广收益、扣回或「返利 ¥0」，没有为 null。 */
data class AmountCellsView(
    val left: AmountCellView,
    val right: AmountCellView?,
)

object OrderAmountCells {
    const val DEPOSIT_KEY = "order_list.deposit_amount"
    const val PAID_KEY = "order_amount.paid"
    const val EST_KEY = "order_amount.est_rebate"
    const val ACTUAL_KEY = "order_amount.actual"
    const val REBATE_KEY = "order_amount.rebate"
    const val CLAWBACK_KEY = "order_amount.clawback"
    const val EST_PROMO_KEY = "order_amount.est_promo"
    const val PROMO_KEY = "order_amount.promo"
    const val MASK = "****"

    private val ESTIMATED =
        setOf(DisplayStatus.PAID, DisplayStatus.WAITING, DisplayStatus.CREDITING, DisplayStatus.RIGHTS_PENDING, DisplayStatus.REVIEWING)
    private val CREDITED = setOf(DisplayStatus.CREDITED, DisplayStatus.CREDITED_PART_CLAWED)

    // TODO(N-31) G02：PAID 的区间「预估返 ¥a–¥b」需要 rebate_min_fen / rebate_max_fen，契约只有 est_rebate_fen，先按单值显示
    fun of(item: OrderSummary): AmountCellsView {
        val status = item.displayStatus
        val left =
            if (status == DisplayStatus.DEPOSIT_PAID) {
                AmountCellView(DEPOSIT_KEY, item.payAmountFen)
            } else {
                AmountCellView(PAID_KEY, item.payAmountFen)
            }
        val share = item.isShare
        val right =
            when (status) {
                in ESTIMATED -> AmountCellView(if (share) EST_PROMO_KEY else EST_KEY, item.estRebateFen, maskable = true, rebate = true)
                in CREDITED -> AmountCellView(if (share) PROMO_KEY else ACTUAL_KEY, item.actualFen, maskable = true, rebate = true)
                DisplayStatus.CLAWED_BACK -> AmountCellView(CLAWBACK_KEY, item.clawbackFen, minus = true, maskable = true)
                DisplayStatus.INVALID -> AmountCellView(REBATE_KEY, 0L, fixedZero = true, maskable = true)
                else -> null
            }
        return AmountCellsView(left, right)
    }

    /** 金额格的 JSON 出口（三端向量）：隐藏时可隐藏的格 maskedText 为「****」（不带 ¥、位数、正负号）。 */
    fun of(
        item: JsonObject,
        masked: Boolean,
    ): JsonObject {
        val view = of(OrderJson.lenientSummary(item))
        return obj("left" to cell(view.left, masked), "right" to (view.right?.let { cell(it, masked) } ?: JsonNull))
    }

    private fun cell(
        cell: AmountCellView,
        masked: Boolean,
    ): JsonObject {
        val out =
            linkedMapOf<String, JsonElement>(
                "labelKey" to JsonPrimitive(cell.labelKey),
                "fen" to cell.fen.json(),
                "maskedText" to maskedText(cell, masked).json(),
            )
        if (cell.minus) out["sign"] = JsonPrimitive("minus")
        if (cell.fixedZero) out["fixedZero"] = JsonPrimitive(true)
        return JsonObject(out)
    }

    /** 隐藏后的文字：只给可隐藏、且有金额的格（金额为 null 本来就不显示）。 */
    fun maskedText(
        cell: AmountCellView,
        masked: Boolean,
    ): String? = MASK.takeIf { masked && cell.maskable && cell.fen != null }
}

/**
 * 预计结算月份（BR-TEXT-04，OPS-01）：只 WAITING 且 period 非 null 出「预计 {credit_period} 结算」，同年「{M} 月」、跨年「{YYYY} 年 {M} 月」
 * （今天按 +08:00）；客户端只格式化、不推算。PAID 显示「确认收货后随联盟月度结算入账」（G05：入账开关读不到时按开启）。
 */
data class CreditPeriodView(
    val textKey: String?,
    val monthText: String?,
)

object CreditPeriodText {
    private val SHANGHAI: ZoneId = ZoneId.of("Asia/Shanghai")
    private val PERIOD = Regex("^([0-9]{4})-(0[1-9]|1[0-2])$")
    const val WAITING_KEY = "order_status.WAITING.hint"
    const val PAID_KEY = "order_status.PAID.hint"

    fun of(
        status: DisplayStatus,
        period: String?,
        nowMs: Long,
    ): CreditPeriodView =
        when (status) {
            DisplayStatus.WAITING -> monthText(period, nowMs)?.let { CreditPeriodView(WAITING_KEY, it) } ?: CreditPeriodView(null, null)
            DisplayStatus.PAID -> CreditPeriodView(PAID_KEY, null)
            else -> CreditPeriodView(null, null)
        }

    /** 「11 月」/「2027 年 1 月」；格式不对为 null（不显示，不猜）。 */
    fun monthText(
        period: String?,
        nowMs: Long,
    ): String? {
        val match = period?.let { PERIOD.matchEntire(it) } ?: return null
        val year = match.groupValues[1].toInt()
        val month = match.groupValues[2].toInt()
        val nowYear = Instant.ofEpochMilli(nowMs).atZone(SHANGHAI).year
        return if (year ==
            nowYear
        ) {
            "$month${OrderFormat.MONTH_SUFFIX}"
        } else {
            "$year${OrderFormat.YEAR_SUFFIX}$month${OrderFormat.MONTH_SUFFIX}"
        }
    }

    fun of(
        displayStatus: String,
        period: String?,
        nowMs: Long,
    ): JsonObject =
        of(OrderJson.status(displayStatus), period, nowMs).let { obj("textKey" to it.textKey.json(), "monthText" to it.monthText.json()) }
}

/** 时间线的一行：[time] 为「YYYY-MM-DD HH:mm」（+08:00），没有时间为 null 且置灰；credit_expected 的 [monthText] 按 G16。 */
data class TimelineRow(
    val node: OrderTimelineNode,
    val labelKey: String,
    val time: String?,
    val grey: Boolean,
    val monthText: String?,
)

/**
 * 时间线（BR-TEXT-02「时间线」）：按响应顺序；不认识的节点忽略（不显示编码原文）；at 为 null 的节点置灰、不显示时间；credit_expected 只在
 * display_status=WAITING 且 period 非 null 时显示「预计 {credit_period} 结算」，否则「联盟结算」（G16）；预售单的 deposit_paid 没有时间时
 * 只显示「付定金」（同一个键的 fallback），final_paid 用「付尾款 {time}」。
 */
object OrderTimelineView {
    fun of(
        status: DisplayStatus,
        nodes: List<OrderTimelineItem>,
        nowMs: Long,
    ): List<TimelineRow> =
        nodes.mapNotNull { item ->
            val node = item.node
            if (node == OrderTimelineNode.UNKNOWN) return@mapNotNull null
            val time = item.at?.let(OrderFormat::minute)
            val month =
                if (node == OrderTimelineNode.CREDIT_EXPECTED && status == DisplayStatus.WAITING) {
                    CreditPeriodText.monthText(item.period, nowMs)
                } else {
                    null
                }
            TimelineRow(node, "order_timeline.${node.raw}", time, grey = time == null, monthText = month)
        }

    fun of(
        displayStatus: String,
        nodes: List<JsonObject>,
        nowMs: Long,
    ): List<JsonObject> =
        of(OrderJson.status(displayStatus), nodes.mapNotNull(OrderJson::timelineItem), nowMs).map {
            obj(
                "labelKey" to JsonPrimitive(it.labelKey),
                "time" to it.time.json(),
                "grey" to JsonPrimitive(it.grey),
                "monthText" to it.monthText.json(),
            )
        }
}

/**
 * 原因区「为什么没返利？」（AI-09：直接显示，不进 AI、不发任何请求；BR-TEXT-02、03、05）：
 * - INVALID / CLAWED_BACK 且 reason 为已知编码：标题 + 说明（CLAWED_BACK 另加「已从余额扣除」）；
 * - NO_REBATE 且 is_price_compare=true：order.price_compare.hint；
 * - 其他状态下 reason 为 diff 类（PART_REFUND、PRICE_COMPARE、PRICE_PROTECT、SETTLE_DIFF）：只显示原因标题（G03：差额行要的字段契约没有）；
 * - 未知编码不显示原因区（G15），CANCELLED 在枚举里但 BR-TEXT-05 没有文案行，按未知处理。
 */
data class WhyNoRebateView(
    val show: Boolean,
    val headingKey: String? = null,
    val titleKey: String? = null,
    val descKey: String? = null,
    val extraKey: String? = null,
    val hintKey: String? = null,
    val reason: String? = null,
) {
    companion object {
        val HIDDEN = WhyNoRebateView(show = false)
    }
}

object WhyNoRebate {
    const val HEADING_KEY = "order_detail.why_no_rebate"
    const val PRICE_COMPARE_HINT = "order.price_compare.hint"
    const val CLAWED_EXTRA = "order_reason.clawed_back_extra"

    /** BR-TEXT-05 细则表里有文案的编码（CANCELLED 不在内）。 */
    val KNOWN: Set<String> =
        setOf(
            "REFUND",
            "PART_REFUND",
            "RIGHTS",
            "PUNISH",
            "PRICE_COMPARE",
            "PRICE_PROTECT",
            "SETTLE_DIFF",
            "NOT_TRACKED",
            "EXPIRED_CLICK",
            "RELATION_INVALID",
            "OTHER_TLJ",
            "BLACKLIST",
            "PRESALE_UNPAID",
            "COMMISSION_ZERO",
            "OTHER",
        )
    val DIFF: Set<String> = setOf("PART_REFUND", "PRICE_COMPARE", "PRICE_PROTECT", "SETTLE_DIFF")

    fun of(detail: OrderDetail): WhyNoRebateView {
        val status = detail.summary.displayStatus
        val reason = detail.summary.reason?.takeIf { it in KNOWN }
        return when {
            (status == DisplayStatus.INVALID || status == DisplayStatus.CLAWED_BACK) && reason != null -> {
                WhyNoRebateView(
                    show = true,
                    headingKey = HEADING_KEY.takeIf { status == DisplayStatus.INVALID },
                    titleKey = "order_reason.$reason.title",
                    descKey = "order_reason.$reason.desc",
                    extraKey = CLAWED_EXTRA.takeIf { status == DisplayStatus.CLAWED_BACK },
                    reason = reason,
                )
            }

            status == DisplayStatus.NO_REBATE && detail.isPriceCompare == true -> {
                WhyNoRebateView(show = true, hintKey = PRICE_COMPARE_HINT)
            }

            status != DisplayStatus.INVALID && status != DisplayStatus.CLAWED_BACK && reason in DIFF -> {
                WhyNoRebateView(show = true, titleKey = "order_reason.$reason.title", reason = reason)
            }

            else -> {
                WhyNoRebateView.HIDDEN
            }
        }
    }

    fun of(detail: JsonObject): JsonObject =
        of(OrderJson.lenientDetail(detail)).let {
            obj(
                "show" to JsonPrimitive(it.show),
                "titleKey" to it.titleKey.json(),
                "descKey" to it.descKey.json(),
                "hintKey" to it.hintKey.json(),
                "extraKey" to it.extraKey.json(),
            )
        }
}

/** 一个经 Router 打开的目标（路由名都在 routes.json）。 */
data class RouteTarget(
    val route: String,
    val params: JsonObject,
)

/**
 * 比价说明入口「查看说明」（BR-TEXT-03「比价说明入口」）：help_links.price_compare 已配置，且 ① reason=PRICE_COMPARE 或 ③ NO_REBATE 且
 * is_price_compare=true 时显示（② 比价风险 hint 依赖契约没有的 rebate_basis，G02）。目标必须是 HelpLinkTarget（Help + 非空 article_id），
 * 路由在 routes.json 里、参数按该路由的 schema 校验通过；否则不显示。不占按钮位。
 */
object PriceCompareEntry {
    const val HELP_LINK_KEY = "price_compare"
    private const val HELP_ROUTE = "Help"
    private const val ARTICLE_ID = "article_id"

    fun of(
        detail: OrderDetail,
        helpLinks: JsonObject?,
        routes: Map<String, RouteSpec>,
    ): RouteTarget? {
        val status = detail.summary.displayStatus
        val applies = detail.summary.reason == "PRICE_COMPARE" || (status == DisplayStatus.NO_REBATE && detail.isPriceCompare == true)
        if (!applies) return null
        return target(helpLinks, routes)
    }

    /** 配置的帮助文章目标（HelpLinkTarget 校验 + routes.json 校验）；不合法为 null。 */
    @Suppress("ReturnCount") // 逐项校验
    fun target(
        helpLinks: JsonObject?,
        routes: Map<String, RouteSpec>,
    ): RouteTarget? {
        val entry = helpLinks?.get(HELP_LINK_KEY) as? JsonObject ?: return null
        val route = (entry["route"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val params = entry["params"] as? JsonObject ?: return null
        if (route != HELP_ROUTE) return null
        val article = (params[ARTICLE_ID] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (article.isNullOrEmpty()) return null
        val spec = routes[route] ?: return null
        if (!RouteParams.isValid(spec, params)) return null
        return RouteTarget(route, params)
    }

    fun of(
        detail: JsonObject,
        helpLinks: JsonObject?,
        routeCatalog: Map<String, RouteSpec>,
    ): JsonObject {
        val target = of(OrderJson.lenientDetail(detail), helpLinks, routeCatalog)
        return if (target == null) {
            obj("show" to JsonPrimitive(false))
        } else {
            obj("show" to JsonPrimitive(true), "route" to JsonPrimitive(target.route), "params" to target.params)
        }
    }
}

/** 详情底部的一个按钮。[actionRaw] 三端同名：claim、reauth、contact_cs、appeal、withdraw、ledger。 */
data class OrderActionButton(
    val actionRaw: String,
    val route: String,
    val primary: Boolean,
) {
    val labelKey: String get() = "order_action.$actionRaw"
}

/** 详情按钮与「申诉中」块。 */
data class OrderActionsView(
    val buttons: List<OrderActionButton>,
    val appealPendingBlock: Boolean,
)

/**
 * 详情按钮（BR-TEXT-05：状态固有按钮 + reason.action，最多 2 个，有 reason.action 时它为主按钮）：reason_action 里本端认识的动作在前
 * （CLAIM → FindOrder；REAUTH → AuthManage，G09；CONTACT_CS → Help，统一客服出口接入前，TODO(N-31) G09；APPEAL → Appeal，G08），
 * 状态固有按钮在后（CREDITED / CREDITED_PART_CLAWED【去提现】→ Withdraw；CLAWED_BACK【查看流水】→ Ledger），超过 2 个只留前 2 个，
 * 第一个为主按钮；不认识的 action 忽略。分享单同样处理。appeal_pending=true 时原因区下加「申诉中」块（G08）。
 */
object OrderDetailActions {
    const val MAX = 2

    val REASON_ACTIONS: Map<String, Pair<String, String>> =
        linkedMapOf(
            "CLAIM" to ("claim" to "FindOrder"),
            "REAUTH" to ("reauth" to "AuthManage"),
            "CONTACT_CS" to ("contact_cs" to "Help"),
            "APPEAL" to ("appeal" to "Appeal"),
        )

    fun of(detail: OrderDetail): OrderActionsView {
        val fromReason = detail.reasonAction.mapNotNull { REASON_ACTIONS[it] }.distinct()
        val inherent =
            when (detail.summary.displayStatus) {
                DisplayStatus.CREDITED, DisplayStatus.CREDITED_PART_CLAWED -> listOf("withdraw" to "Withdraw")
                DisplayStatus.CLAWED_BACK -> listOf("ledger" to "Ledger")
                else -> emptyList()
            }
        val buttons =
            (fromReason + inherent).distinct().take(MAX).mapIndexed { index, (action, route) ->
                OrderActionButton(action, route, primary = index == 0)
            }
        return OrderActionsView(buttons, detail.appealPending)
    }

    fun of(detail: JsonObject): JsonObject =
        of(OrderJson.lenientDetail(detail)).let { view ->
            obj(
                "buttons" to
                    JsonArray(
                        view.buttons.map {
                            obj(
                                "actionRaw" to JsonPrimitive(it.actionRaw),
                                "route" to JsonPrimitive(it.route),
                                "primary" to JsonPrimitive(it.primary),
                            )
                        },
                    ),
                "appealPendingBlock" to JsonPrimitive(view.appealPendingBlock),
            )
        }
}

/**
 * 「查看商品」（F-ORD-16，BR-TEXT-02 细则）：product_key 非 null 且 is_other_product=false 时显示；打开 ProductDetail {platform, product_key}
 * （不带 item_ref、不带任何订单或 link 信息），之后的购买是一次新的 open。不占按钮名额。
 */
object ViewProductEntry {
    private const val ROUTE = "ProductDetail"

    @Suppress("ReturnCount") // 没有商品键、其他商品或没有平台两个出口
    fun of(detail: OrderDetail): RouteTarget? {
        val key = detail.productKey ?: return null
        if (detail.summary.isOtherProduct || detail.summary.platform.isEmpty()) return null
        return RouteTarget(ROUTE, obj("platform" to JsonPrimitive(detail.summary.platform), "product_key" to JsonPrimitive(key)))
    }

    fun of(detail: JsonObject): JsonObject {
        val target = of(OrderJson.lenientDetail(detail)) ?: return obj("show" to JsonPrimitive(false))
        return obj("show" to JsonPrimitive(true), "route" to JsonPrimitive(target.route), "params" to target.params)
    }
}

/** 订单页用到的时间格式（BR-TEXT-11：+08:00、24 小时制）。 */
object OrderFormat {
    const val MONTH_SUFFIX = " 月"
    const val YEAR_SUFFIX = " 年 "

    /** ISO 8601 → 毫秒；格式不对为 null（不显示，不猜）。 */
    fun epochMillis(iso: String?): Long? = iso?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

    /** 「YYYY-MM-DD HH:mm」（订单时间线）。 */
    fun minute(iso: String): String? = epochMillis(iso)?.let(com.couli.core.designsystem.CouliFormat::timeMinute)
}

internal fun obj(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(linkedMapOf(*pairs))

internal fun String?.json(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull

internal fun Long?.json(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull
