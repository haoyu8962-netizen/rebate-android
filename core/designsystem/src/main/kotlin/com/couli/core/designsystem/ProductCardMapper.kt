package com.couli.core.designsystem

import java.time.OffsetDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** 卡片映射要的文案（调用方给：契约字典 → 草稿表；缺键返回空串）。 */
fun interface ProductCardTexts {
    fun text(key: String): String

    /** 带 {变量} 的文案；缺省只做替换（调用方的字典按 BR-TEXT-14 处理缺变量的回落）。 */
    fun format(
        key: String,
        vars: Map<String, String>,
    ): String = vars.entries.fold(text(key)) { acc, (name, value) -> acc.replace("{$name}", value) }
}

/** 文案键（三端同名）。平台名、口径按钮名、图片占位等不在契约字典里，由调用方的草稿表给（TODO(N-27)）。 */
object ProductCardKeys {
    fun platform(platform: String): String = "platform.$platform"

    const val DISCLAIMER_BUTTON = "card.disclaimer_button"
    const val IMAGE_LABEL = "card.image_placeholder"

    /** 口径说明弹层（SearchDisclaimerNote）的标题与关闭按钮。 */
    const val DISCLAIMER_TITLE = "card.disclaimer_title"
    const val DISCLAIMER_CLOSE = "card.disclaimer_close"

    /** 价格不可用的提示（BR-PRICE-01「暂时查不到该商品价格」）。 */
    const val PRICE_UNAVAILABLE = "card.price_unavailable"

    /** 取价处的价格变化提示（BR-PRICE-11）。 */
    const val PRICE_STALE = "card.price_stale"

    /** 价格不可用时按钮的「稍后再试」（BR-TEXT-14 表 C）。 */
    const val RETRY_LATER = "card.retry_later"
}

/** 一张接口商品卡的映射结果：DesignSystem 卡片数据 + 按钮状态与判定。 */
data class MappedProductCard(
    val data: ProductCardData,
    val buyState: BuyButtonState,
    /** 「稍后再试」的文字（价格不可用时按钮用）。 */
    val retryLaterText: String,
    val ctaKey: String,
    val priceUnavailable: Boolean,
    val stale: Boolean,
)

/**
 * 接口的 ProductCard（openapi 9cd1e6f，searchProducts / getProduct 的 JSON 对象）→ [ProductCardData]（APP-06a）。
 * 只做格式化，不做计算（BR-PRICE-17）：价格、返利、预估返后都用服务端字段经 [PriceTagModel] / [RebateTagModel] / [EstNetModel]。
 * - 价格三字段原样保留「未知」（null），不补 0（BR-PRICE-01，共性第 1 条）：价格不可用时卡上不显示任何金额，显示提示，按钮「稍后再试」禁用；
 * - 购买按钮动词：服务端 cta.text_key（BR-TEXT-12、TRADE-21）；三个已登记的键取 [PriceTerms]（N-11 补键前），其余经 texts；
 * - 优惠标签：服务端 benefit_tags 原样，预售卡缺「预售」时补上；预售说明在价格副行（PriceTagContent.subLines）；
 * - 口径说明：disclaimer_keys 按数组顺序取文字（BR-PRICE-17）；
 * - 取价时间（详情大卡）：「取价 今天 HH:mm」（BR-TEXT-11 列表过去时间），stale 时另给价格变化提示（BR-PRICE-11，共性第 3 条）；
 * - 图片：契约 image（https）经 [LocalProductImageLoader] 加载，没有或失败时占位（APP-06a 共性第 22 条）；
 * - 平台购买状态（features.platform_status）：not_launched 隐藏返利金额与预估返后、按钮「X返利即将开放」禁用，maintenance 按钮「稍后再试」禁用。
 */
object ProductCardMapper {
    /** BR-PRICE-11：age_sec 超过 300 秒（或 stale=true）时提示价格可能已变化；正好 300 秒不提示。 */
    private const val STALE_AGE_SEC = 300L
    private const val AMOUNT_UNKNOWN = "amount_unknown"
    private const val PRICE_UNAVAILABLE = "price_unavailable"

    @Suppress("CyclomaticComplexMethod", "ComplexCondition") // 逐字段映射，缺失即未知
    fun map(
        card: JsonObject,
        texts: ProductCardTexts,
        nowMillis: Long = System.currentTimeMillis(),
        buyEnabled: Boolean = true,
        purchaseStatus: String? = null,
    ): MappedProductCard {
        val platform = card.text("platform").orEmpty()
        // 平台明确 not_launched（features.platform_status）：隐藏返利金额、预估返后及读屏金额，只留商品价格与禁用按钮（ProductCardDegrade ①，
        // APP-06a 共性第 31 条）；maintenance：按钮「稍后再试」禁用（共性第 27 条）
        val notLaunched = purchaseStatus == PURCHASE_NOT_LAUNCHED
        val maintenance = purchaseStatus == PURCHASE_MAINTENANCE
        val notLaunchedText = texts.format(NOT_LAUNCHED_KEY, mapOf("platform_name" to texts.text(ProductCardKeys.platform(platform))))
        val basis = card.text("rebate_basis").orEmpty()
        val presale = card.flag("is_presale")
        val unavailable = priceUnavailable(card)
        val priceFen = card.fen("price_fen")
        val couponFen = card.fen("coupon_fen")
        val finalFen = card.fen("final_price_fen")
        val price =
            if (!unavailable && priceFen != null && couponFen != null && finalFen != null) {
                PriceTagModel.make(platform, priceFen, couponFen, finalFen, presale)
            } else {
                null
            }
        val min = card.fen("rebate_min_fen")
        val ctaKey = (card["cta"] as? JsonObject)?.text("text_key").orEmpty()
        val buyBase = buyText(ctaKey, texts)
        val stale = stale(card)
        val keys = disclaimerKeys(card)
        val quoted = card.text("quoted_at")?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
        val data =
            ProductCardData(
                platformName = texts.text(ProductCardKeys.platform(platform)),
                title = card.text("title").orEmpty(),
                price = price,
                rebate =
                    when {
                        notLaunched -> RebateTagModel.notice(notLaunchedText)

                        // 返利已确认、口径还没补上：按服务端区间临时展示（A-R2-01），不当「暂无返利」
                        provisionalRebate(card) -> RebateTagModel.provisional(min, card.fen("rebate_max_fen"))

                        else -> RebateTagModel.make(basis, min, card.fen("rebate_max_fen"), loginRequired = false)
                    },
                buyBase = buyBase,
                imageLabel = texts.text(ProductCardKeys.IMAGE_LABEL),
                estNet = if (unavailable || notLaunched) null else EstNetModel.make(basis, min, card.fen("est_net_price_fen")),
                benefitTags = benefitTags(card, presale),
                disclaimer = keys.mapNotNull { key -> (DisclaimerKeys.text(key) ?: texts.text(key)).takeIf(String::isNotEmpty) },
                disclaimerKeys = keys,
                infoLabel = texts.text(ProductCardKeys.DISCLAIMER_BUTTON),
                shopName = card.text("shop_name")?.takeIf(String::isNotEmpty),
                quotedAt = quoted?.let { "${PriceTerms.QUOTED} ${CouliFormat.timeListPast(it, nowMillis)}" },
                priceUnavailableText = if (unavailable) texts.text(ProductCardKeys.PRICE_UNAVAILABLE) else null,
                staleNote = if (stale && !unavailable) texts.text(ProductCardKeys.PRICE_STALE).takeIf(String::isNotEmpty) else null,
                imageUrl = card.text("image")?.takeIf { it.startsWith(HTTPS) },
            )
        val buyState =
            when {
                unavailable || notLaunched || maintenance -> BuyButtonState.RETRY_LATER
                buyEnabled && buyBase.isNotEmpty() -> BuyButtonState.NORMAL
                else -> BuyButtonState.DISABLED
            }
        val retryLater = if (notLaunched && !unavailable) notLaunchedText else texts.text(ProductCardKeys.RETRY_LATER)
        return MappedProductCard(data, buyState, retryLater, ctaKey, unavailable, stale)
    }

    /**
     * 客户端临时字段（不来自接口）：open 复核确认返利从无到有、服务端只给了区间没给口径时由购买流程写上（A-R2-01）；重取到口径后去掉。
     */
    const val PROVISIONAL_REBATE = "client_rebate_provisional"

    /** 返利临时展示状态：带 [PROVISIONAL_REBATE] 且口径未知（没有 rebate_basis）。 */
    fun provisionalRebate(card: JsonObject): Boolean = card.flag(PROVISIONAL_REBATE) && card.text("rebate_basis").isNullOrEmpty()

    /** features.platform_status 的取值（PlatformPurchaseStatus）。 */
    const val PURCHASE_ON = "on"
    const val PURCHASE_MAINTENANCE = "maintenance"
    const val PURCHASE_NOT_LAUNCHED = "not_launched"
    private const val NOT_LAUNCHED_KEY = "error.50301.not_launched"
    private const val HTTPS = "https://"

    /**
     * 价格不可用（BR-PRICE-01、AC-S1-09）：availability=price_unavailable，或价格三字段任一缺失（rebate_basis=amount_unknown 的拼多多
     * 直链卡除外：BR-PRICE-08 规定它本来就不显示价格，只显示专用返利标签）。
     */
    @Suppress("ReturnCount") // 逐条判定
    fun priceUnavailable(card: JsonObject): Boolean {
        if (card.text("availability") == PRICE_UNAVAILABLE) return true
        if (card.text("rebate_basis") == AMOUNT_UNKNOWN) return false
        return card.fen("price_fen") == null || card.fen("coupon_fen") == null || card.fen("final_price_fen") == null
    }

    /** 缓存 / 商品池降级报价（BR-PRICE-11）：stale=true，或 age_sec > 300。 */
    fun stale(card: JsonObject): Boolean = card.flag("stale") || (card.fen("age_sec") ?: 0L) > STALE_AGE_SEC

    /** 优惠标签：服务端 benefit_tags 原样；预售卡没有带「预售」时补上（BR-PRICE-22 预售标签），不重复。 */
    private fun benefitTags(
        card: JsonObject,
        presale: Boolean,
    ): List<BenefitTag> {
        val server =
            (card["benefit_tags"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf(String::isNotEmpty) }
                .orEmpty()
        val tags = if (presale && PriceTerms.PRESALE !in server) server + PriceTerms.PRESALE else server
        return tags.map { BenefitTag(it) }
    }

    /** 购买按钮动词（cta.text_key → 字）。 */
    fun buyText(
        key: String,
        texts: ProductCardTexts,
    ): String =
        when (key) {
            "btn.buy.coupon" -> PriceTerms.BUY_COUPON
            "btn.buy" -> PriceTerms.BUY
            "btn.buy.no_rebate" -> PriceTerms.BUY_NO_REBATE
            "" -> ""
            else -> texts.text(key)
        }

    fun disclaimerKeys(card: JsonObject): List<String> =
        (card["disclaimer_keys"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.flag(key: String): Boolean = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true

    /** 整数分；null、缺失、字符串、小数都按「未知」（null），不补 0。 */
    private fun JsonObject.fen(key: String): Long? = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
}
