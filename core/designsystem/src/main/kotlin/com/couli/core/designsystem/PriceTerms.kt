package com.couli.core.designsystem

import com.couli.generated.DefaultTexts

/**
 * 价格、返利与时间格式里用到的固定术语（DesignSystem 里唯一一处写这些字）。
 * 逐字照 08 原文：BR-PRICE-04 / 05（券后、券前、售价、券、拼单…）、BR-PRICE-07（以结算为准）、
 * BR-PRICE-09（预估返后）、BR-PRICE-22 与 BR-TEXT-12 细则（tag.presale「预售」、presale.price_note）、
 * BR-TEXT-10（暂无返利）、BR-TEXT-11（今天、昨天）、BR-TEXT-14 表 C（btn.buy.rebate_suffix）、
 * BR-PRICE-03 正文与细则「无券商品与混排列表的口径说明」（price_basis、price_basis.general）、BR-PRICE-17（rebate_estimate）。
 *
 * TODO(N-11): 契约 texts.default.json 补上这些键（tag.rebate、tag.presale、presale.price_note、btn.buy.rebate_suffix、
 * price_basis、price_basis.general、rebate_estimate、rebate_compare、价格口径词等）后改读 [DefaultTexts]，删掉这里的字面量。
 * 已有键的两条（rebate_amount_unknown、rebate_login_to_view）直接读字典。
 */
object PriceTerms {
    /** 金额前的人民币符号（BR-TEXT-10）。 */
    const val YUAN_SIGN = "¥"

    /** 负号：ASCII「-」，放在「¥」前（BR-TEXT-10）。 */
    const val MINUS = "-"

    /** 流水正数前缀（BR-TEXT-10）。 */
    const val PLUS = "+"

    /** 区间连接符 U+2013，前后无空格（BR-TEXT-10）。 */
    const val RANGE_DASH = "–"

    /** 副行分隔「 · 」（BR-PRICE-04 例：「券前 ¥39.9 · 券 ¥10」）。 */
    const val DOT_SEPARATOR = " · "

    // BR-PRICE-04 / 05：价格口径词
    const val AFTER_COUPON = "券后"
    const val BEFORE_COUPON = "券前"
    const val SALE_PRICE = "售价"
    const val COUPON = "券"
    const val PDD_AFTER_COUPON = "拼单券后"
    const val PDD_BEFORE_COUPON = "拼单券前"
    const val PDD_PRICE = "拼单价"

    /** 无券状态（BR-PRICE-04「显示无券状态」；设计稿副行写「无券」）。 */
    const val NO_COUPON = "无券"

    /** 返利短标签（BR-PRICE-05：界面短标签只允许「预估返」）。 */
    const val EST_REBATE = "预估返"

    /** 预估返后价短标签（BR-PRICE-05 / 09）。 */
    const val EST_NET = "预估返后"

    /** min = max = 0 的返利文案（BR-TEXT-10；列表里隐藏）。 */
    const val NO_REBATE = "暂无返利"

    /** 比价区间的后缀说明（BR-PRICE-07 rebate_compare 默认文案「以结算为准」）。 */
    const val SETTLE_BASIS = "以结算为准"

    /**
     * 金额隐藏的缺省遮罩与读屏（BR-TEXT-10「****」；adaptation-a11y-spec §9「金额已隐藏」）：调用方只给 amountHidden、
     * 没给 [AmountMask] 时用（[AmountMask.DEFAULT_HIDDEN]）。TODO(N-11): 字典补键后改读 [DefaultTexts]。
     */
    const val AMOUNT_MASK = "****"
    const val AMOUNT_HIDDEN_SPOKEN = "金额已隐藏"

    /** 口径说明 price_basis（BR-PRICE-03 正文；只用于有券卡，细则「无券商品与混排列表的口径说明」）。TODO(N-11) */
    const val PRICE_BASIS = "券后价按单件计算，不含运费及会员价等优惠，以下单页为准"

    /** 口径说明 price_basis.general（BR-PRICE-03 细则：无券卡，及含无券卡的列表底部）。TODO(N-11) */
    const val PRICE_BASIS_GENERAL = "价格按单件计算，不含运费及会员价等优惠，以下单页为准"

    /** 口径说明 rebate_estimate 默认文案（BR-PRICE-17）。TODO(N-11) */
    const val REBATE_ESTIMATE = "预估返利以平台结算为准，未结算或订单失效时不返"

    /** 预售标签（BR-PRICE-22；BR-TEXT-12 细则 tag.presale）。 */
    const val PRESALE = "预售"

    /** 预售价格说明（BR-PRICE-22；BR-TEXT-12 细则 presale.price_note）。 */
    const val PRESALE_NOTE = "定金与尾款以下单页为准"

    // 购买按钮动词（BR-TEXT-12 细则、TRADE-21：服务端 cta.text_key → 字）。TODO(N-11): 契约补 btn.buy* 键后改读字典

    /** btn.buy.coupon「领券购买」。 */
    const val BUY_COUPON = "领券购买"

    /** btn.buy「去购买」。 */
    const val BUY = "去购买"

    /** btn.buy.no_rebate「去购买（无返利）」。 */
    const val BUY_NO_REBATE = "去购买（无返利）"

    /** 详情取价时间的前缀（Product 画板「取价 10:00」）。TODO(N-11) */
    const val QUOTED = "取价"

    /** 购买按钮后缀模板（BR-TEXT-14 表 C btn.buy.rebate_suffix「· 预估返 {amount}」）。 */
    const val BUY_REBATE_SUFFIX_TEMPLATE = "· 预估返 {amount}"

    // BR-TEXT-11：列表过去时间的相对日
    const val TODAY = "今天"
    const val YESTERDAY = "昨天"

    // 读屏用的金额读法（adaptation-a11y-spec §9：金额读成完整句，如「券后 19.9 元」「预估返 1.23 元到 2.34 元」）
    const val SPOKEN_YUAN = "元"
    const val SPOKEN_TO = "到"
    const val SPOKEN_NEGATIVE = "负"
    const val SPOKEN_PAUSE = "，"

    /** 「可返利，金额以订单为准」：契约键 rebate_amount_unknown。 */
    val amountUnknown: String get() = DefaultTexts.text("rebate_amount_unknown").orEmpty()

    /** 「登录查看返利」：契约键 rebate_login_to_view。 */
    val loginToView: String get() = DefaultTexts.text("rebate_login_to_view").orEmpty()
}
