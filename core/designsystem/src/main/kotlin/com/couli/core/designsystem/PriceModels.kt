package com.couli.core.designsystem

import androidx.compose.runtime.Immutable
import com.couli.generated.Platform
import com.couli.generated.RebateBasis

/** [PriceTagModel.make] 的结果：主价口径词 + 主价，副行（有券时），无券状态，预售标签。 */
@Immutable
data class PriceTagContent(
    val mainLabel: String,
    val mainAmount: String,
    val subLine: String?,
    val noCoupon: Boolean,
    val presaleLabel: String?,
    /** 读屏名称：「券后 29.9 元」（副行另读）。 */
    val mainSpoken: String,
)

/** 价格标签模型（BR-PRICE-04 / 05 / 22）：客户端只格式化，不重算价格。 */
object PriceTagModel {
    /**
     * 有券：主价「券后 ¥final」，副行「券前 ¥price · 券 ¥coupon」（不划线）；无券：「售价 ¥final」并标无券；
     * 拼多多在口径词前加「拼单」，无券为「拼单价」。预售只加标签（BR-PRICE-22），价格仍按总价。
     * 规则空白：负价格、负券额、未知平台、矛盾字段 08 没有规定；这里不校验也不纠正，未知平台按非拼多多写法。
     */
    fun make(
        platform: String,
        priceFen: Long,
        couponFen: Long,
        finalPriceFen: Long,
        isPresale: Boolean,
    ): PriceTagContent {
        val pdd = Platform.fromRaw(platform) == Platform.PDD
        val hasCoupon = couponFen > 0
        val label =
            when {
                hasCoupon && pdd -> PriceTerms.PDD_AFTER_COUPON
                hasCoupon -> PriceTerms.AFTER_COUPON
                pdd -> PriceTerms.PDD_PRICE
                else -> PriceTerms.SALE_PRICE
            }
        val subLine =
            if (hasCoupon) {
                val before = if (pdd) PriceTerms.PDD_BEFORE_COUPON else PriceTerms.BEFORE_COUPON
                "$before ${CouliFormat.money(priceFen)}${PriceTerms.DOT_SEPARATOR}${PriceTerms.COUPON} ${CouliFormat.money(couponFen)}"
            } else {
                null
            }
        return PriceTagContent(
            mainLabel = label,
            mainAmount = CouliFormat.money(finalPriceFen),
            subLine = subLine,
            noCoupon = !hasCoupon,
            presaleLabel = if (isPresale) PriceTerms.PRESALE else null,
            mainSpoken = "$label ${CouliFormat.moneySpoken(finalPriceFen)}",
        )
    }
}

/** 返利标签的种类；[raw] 与向量里的 kind 字符串一致。 */
enum class RebateKind(
    val raw: String,
) {
    AMOUNT("amount"),
    RANGE("range"),
    NONE("none"),
    UNKNOWN("unknown"),
    LOGIN("login"),
}

/**
 * [RebateTagModel.make] 的结果。[text] 是整句（「预估返 ¥1.35–¥2.69」）；组件按 [label] + [amountSegments] 排版，
 * 区间的两段只在破折号处折行（GUIDE §6）。
 */
@Immutable
class RebateTagContent internal constructor(
    val kind: RebateKind,
    val text: String,
    /** 标签词（「预估返」）；无金额的种类为整句文案。 */
    val label: String,
    /** 金额段：单值一段「¥2.69」，区间两段「¥1.35」「–¥2.69」；无金额为空。 */
    val amountSegments: List<String>,
    /** 读屏名称：「预估返 1.35 元到 2.69 元」。 */
    val spoken: String,
    /** 金额已被遮罩（[masked]）：金额段只有遮罩，不能再当金额用（购买按钮后缀等）。 */
    val isMasked: Boolean = false,
) {
    /** 向量里的 kind 字符串：amount / range / none / unknown / login。 */
    val kindRaw: String get() = kind.raw

    /** 单一金额（kind = amount）时的金额字符串，供购买按钮后缀用（与标签同一格式化结果）。 */
    val singleAmount: String? get() = if (kind == RebateKind.AMOUNT && !isMasked) amountSegments.firstOrNull() else null

    /** 有没有可显示的金额。 */
    val hasAmount: Boolean get() = kind == RebateKind.AMOUNT || kind == RebateKind.RANGE

    /**
     * 是否用返利强调色（GUIDE §7.1「无返利 / 返利 ¥0 不用橙」；三端统一，F-AND-06）：「暂无返利」（含异常数据降级）中性；
     * 金额、区间、金额未知、登录查看用返利色。金额被遮罩时按种类（不让颜色泄露金额是否为 0）。
     */
    val usesRebateAccent: Boolean get() = kind != RebateKind.NONE

    /**
     * 金额隐藏时的返利标签（编排者三端统一裁定）：有金额的种类显示「预估返 ****」，读屏「预估返，金额已隐藏」；
     * 没有金额的种类（暂无返利、金额未知、登录查看）不含金额，原样返回。
     */
    fun masked(mask: AmountMask): RebateTagContent =
        if (!hasAmount || isMasked) {
            this
        } else {
            RebateTagContent(
                kind = kind,
                text = "$label ${mask.mask}",
                label = label,
                amountSegments = listOf(mask.mask),
                spoken = label + PriceTerms.SPOKEN_PAUSE + mask.spoken,
                isMasked = true,
            )
        }

    override fun equals(other: Any?): Boolean =
        other is RebateTagContent && other.kind == kind && other.text == text && other.isMasked == isMasked

    override fun hashCode(): Int = kind.hashCode() * HASH_PRIME + text.hashCode()

    override fun toString(): String = "RebateTagContent(${kind.raw}, $text)"

    private companion object {
        const val HASH_PRIME = 31
    }
}

/** 返利标签模型（BR-PRICE-07 / 08，BR-TEXT-10）。 */
object RebateTagModel {
    /**
     * - login_required 或 loginRequired：「登录查看返利」（契约键 rebate_login_to_view）。
     * - amount_unknown：「可返利，金额以订单为准」（契约键 rebate_amount_unknown），不显示金额。
     * - no_rebate，或 max = 0：「暂无返利」（列表里由组件隐藏）。
     * - normal / price_compare_risk：min = max 单值，min < max 区间（含 min = 0）。
     *
     * 异常数据（编排者裁定，三端统一；TODO(N-13)：08 补规则后按原文改）：loginRequired 优先于 basis；
     * normal / price_compare_risk 却缺一端、端点为负、min > max（不排序）、上限为 0，以及未知 basis → 一律按「暂无返利」（kind none）。
     * 「可返利，金额以订单为准」只给 basis = amount_unknown，不能对数据异常的卡承诺有返利。
     */
    fun make(
        basis: String,
        minFen: Long?,
        maxFen: Long?,
        loginRequired: Boolean,
    ): RebateTagContent {
        val parsed = RebateBasis.fromRaw(basis)
        return when {
            loginRequired || parsed == RebateBasis.LOGIN_REQUIRED -> textOnly(RebateKind.LOGIN, PriceTerms.loginToView)

            parsed == RebateBasis.AMOUNT_UNKNOWN -> textOnly(RebateKind.UNKNOWN, PriceTerms.amountUnknown)

            parsed == RebateBasis.NO_REBATE -> textOnly(RebateKind.NONE, PriceTerms.NO_REBATE)

            parsed == RebateBasis.NORMAL || parsed == RebateBasis.PRICE_COMPARE_RISK -> amounts(minFen, maxFen)

            // TODO(N-13)：未知 basis 按异常数据处理
            else -> textOnly(RebateKind.NONE, PriceTerms.NO_REBATE)
        }
    }

    private fun amounts(
        minFen: Long?,
        maxFen: Long?,
    ): RebateTagContent =
        when {
            // TODO(N-13)：缺一端、负数、min > max（不排序）、上限为 0 都按「暂无返利」
            minFen == null || maxFen == null || !isOrderedRange(minFen, maxFen) || maxFen == 0L -> {
                textOnly(RebateKind.NONE, PriceTerms.NO_REBATE)
            }

            else -> {
                amountTag(minFen, maxFen)
            }
        }

    private fun isOrderedRange(
        minFen: Long,
        maxFen: Long,
    ): Boolean = minFen in 0..maxFen

    private fun amountTag(
        minFen: Long,
        maxFen: Long,
    ): RebateTagContent {
        val label = PriceTerms.EST_REBATE
        return if (minFen == maxFen) {
            val amount = CouliFormat.money(minFen)
            RebateTagContent(
                kind = RebateKind.AMOUNT,
                text = "$label $amount",
                label = label,
                amountSegments = listOf(amount),
                spoken = "$label ${CouliFormat.moneySpoken(minFen)}",
            )
        } else {
            RebateTagContent(
                kind = RebateKind.RANGE,
                text = "$label ${CouliFormat.rebateRange(minFen, maxFen)}",
                label = label,
                amountSegments = listOf(CouliFormat.money(minFen), PriceTerms.RANGE_DASH + CouliFormat.money(maxFen)),
                spoken =
                    "$label ${CouliFormat.moneySpoken(minFen)}${PriceTerms.SPOKEN_TO}${CouliFormat.moneySpoken(maxFen)}",
            )
        }
    }

    private fun textOnly(
        kind: RebateKind,
        text: String,
    ) = RebateTagContent(kind = kind, text = text, label = text, amountSegments = emptyList(), spoken = text)
}

/** 「预估返后 ¥x」那一行（BR-PRICE-09）。 */
object EstNetModel {
    /**
     * 只在 basis 为 normal / price_compare_risk、返利下限 > 0 时显示，金额用服务端下发的 est_net_price_fen。
     * 规则空白：可显示却没有 est_net_price_fen 时 08 没有规定；保守不显示（客户端不自己算）。
     */
    fun make(
        basis: String,
        minFen: Long?,
        estNetPriceFen: Long?,
    ): String? {
        val parsed = RebateBasis.fromRaw(basis)
        val showable = parsed == RebateBasis.NORMAL || parsed == RebateBasis.PRICE_COMPARE_RISK
        val hasLowerBound = minFen != null && minFen > 0
        return if (showable && hasLowerBound && estNetPriceFen != null) {
            "${PriceTerms.EST_NET} ${CouliFormat.money(estNetPriceFen)}"
        } else {
            null
        }
    }
}

/** 购买按钮文案（BR-TEXT-14 表 C btn.buy.rebate_suffix）。 */
object BuyButtonModel {
    const val CONTEXT_LIST = "list"
    const val CONTEXT_DETAIL = "detail"
    const val CONTEXT_AGENT = "agent"
    const val CONTEXT_LINK_LANDING = "link_landing"
    const val CONTEXT_SHARE = "share"

    /** 显示返利金额的页面（LinkLanding、分享中间页与海报不显示，BR-PRICE-06、BR-ATTR-05）。 */
    internal val contextsShowingAmount: Set<String> = setOf(CONTEXT_LIST, CONTEXT_DETAIL, CONTEXT_AGENT)

    /**
     * 单一金额 > 0、金额没隐藏、所在页面显示返利金额时，在动词后拼「 · 预估返 ¥x」；区间、无返利、金额未知、登录查看都只写动词。
     * loading、禁用、「稍后再试」不拼：由 [BuyButton] 组件按状态决定，不经这里。未知 context 保守不拼。
     */
    fun text(
        base: String,
        rebate: RebateTagContent,
        amountHidden: Boolean,
        context: String,
    ): String {
        val amount = rebate.singleAmount
        return if (amount != null && !amountHidden && context in contextsShowingAmount) {
            "$base " + PriceTerms.BUY_REBATE_SUFFIX_TEMPLATE.replace("{amount}", amount)
        } else {
            base
        }
    }
}

/**
 * 口径说明的键与列表底部那一句的选择（BR-PRICE-03 正文与细则「无券商品与混排列表的口径说明」、BR-PRICE-17）。
 * 文案在契约补键前读 [PriceTerms]（TODO(N-11)）。
 */
object DisclaimerKeys {
    const val PRICE_BASIS = "price_basis"
    const val PRICE_BASIS_GENERAL = "price_basis.general"
    const val REBATE_ESTIMATE = "rebate_estimate"
    const val REBATE_COMPARE = "rebate_compare"

    /**
     * 列表底部的价格口径句：按各卡下发的 disclaimer_keys 判断，不自己看有没有券。
     * 只要有一张卡带 price_basis.general（无券卡，含有券无券混排）就用 price_basis.general；
     * 带价格口径的卡全是 price_basis 才用 price_basis；没有任何卡带价格口径（如全是金额未知卡）返回 null。
     */
    fun listFooterPriceKey(cardKeys: List<List<String>>): String? =
        when {
            cardKeys.any { PRICE_BASIS_GENERAL in it } -> PRICE_BASIS_GENERAL
            cardKeys.any { PRICE_BASIS in it } -> PRICE_BASIS
            else -> null
        }

    /** 键 → 默认文案；未知键返回 null（不显示）。 */
    fun text(key: String): String? =
        when (key) {
            PRICE_BASIS -> PriceTerms.PRICE_BASIS
            PRICE_BASIS_GENERAL -> PriceTerms.PRICE_BASIS_GENERAL
            REBATE_ESTIMATE -> PriceTerms.REBATE_ESTIMATE
            REBATE_COMPARE -> PriceTerms.SETTLE_BASIS
            else -> null
        }
}
