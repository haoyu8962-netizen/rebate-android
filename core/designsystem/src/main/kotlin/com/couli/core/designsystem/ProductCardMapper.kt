package com.couli.core.designsystem

import android.util.Log
import com.couli.generated.DefaultTexts
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
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

    /** cta.text_key 渲染不出完整文案时改用的动词键（FU-X-CTA-KEY-DRIFT R2，D10-861）：先经调用方 texts，再用包内契约字典。 */
    const val BUY_FALLBACK = "btn.buy"
}

/**
 * cta 文案键的默认诊断（FU-X-CTA-KEY-DRIFT R3）：经系统日志写一条，只带 text_key 原值，不带商品、用户、链接、店铺、价格。
 * Release 照写（与 AppWeb「invalid config key」同一写法，不是调试入口）；同一进程同一个键只写一次（卡片重组、详情重画不刷屏）。
 * 去重缓存最多记 [MAX_KEYS] 个键：满了以后没记过的键照写、只是不再进缓存（新键第一次诊断不丢，R3；Codex 评审 r1 S1）。
 * 纯 JVM 单测里 android.util.Log 不可用，静默跳过、不抛错。
 */
object CtaKeyLog {
    private const val TAG = "CouliCta"
    private const val MAX_KEYS = 64
    private val logged: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun fallback(textKey: String) {
        val fresh = if (logged.size < MAX_KEYS) logged.add(textKey) else textKey !in logged
        if (!fresh) return
        runCatching { Log.w(TAG, "cta text_key not renderable, using ${ProductCardKeys.BUY_FALLBACK}: $textKey") }
    }
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
 * - 购买按钮动词：服务端 cta.text_key（BR-TEXT-12、TRADE-21）经 texts 取字典原文；原文带 {amount} 时填同卡返利标签的单一金额，
 *   渲染不出完整文案的改用 btn.buy 并记诊断，没有 cta / 空键禁用（FU-X-CTA-KEY-DRIFT R1–R4，见 [ctaVerb]）；
 * - 优惠标签：服务端 benefit_tags 原样，预售卡缺「预售」时补上；预售说明在价格副行（PriceTagContent.subLines）；
 * - 口径说明：disclaimer_keys 按数组顺序取文字（BR-PRICE-17）；
 * - 取价时间（详情大卡）：「取价 今天 HH:mm」（BR-TEXT-11 列表过去时间），stale 时另给价格变化提示（BR-PRICE-11，共性第 3 条）；
 * - 图片：契约 image（https）经 [LocalProductImageLoader] 加载，没有或失败时占位（APP-06a 共性第 22 条）；
 * - 平台购买状态（features.platform_status）：not_launched 隐藏返利金额与预估返后、按钮「X返利即将开放」禁用，maintenance 按钮「稍后再试」禁用。
 */
@Suppress("TooManyFunctions") // 逐字段映射 + cta 文案渲染（R1–R4）的私有小函数
object ProductCardMapper {
    /** BR-PRICE-11：age_sec 超过 300 秒（或 stale=true）时提示价格可能已变化；正好 300 秒不提示。 */
    private const val STALE_AGE_SEC = 300L
    private const val AMOUNT_UNKNOWN = "amount_unknown"
    private const val PRICE_UNAVAILABLE = "price_unavailable"
    private const val OFF_SHELF = "off_shelf"
    private const val AMOUNT_NAME = "amount"
    private const val AMOUNT_VAR = "{$AMOUNT_NAME}"
    private val PLACEHOLDER = Regex("\\{[a-z0-9_]+\\}")

    /** 包内契约字典（锁内 texts.default.json）：调用方 texts 给不出 btn.buy 时用。 */
    private val BUNDLED = ProductCardTexts { DefaultTexts.text(it).orEmpty() }

    /**
     * [onCtaFallback]：cta.text_key 渲染不出、改用 btn.buy 时调一次，只给 text_key 原值（R3）；没有 cta / 空键与渲染得出的键都不调。
     * 缺省为 [CtaKeyLog.fallback]。
     */
    @Suppress("CyclomaticComplexMethod", "ComplexCondition", "LongMethod", "LongParameterList") // 逐字段映射，缺失即未知
    fun map(
        card: JsonObject,
        texts: ProductCardTexts,
        nowMillis: Long = System.currentTimeMillis(),
        buyEnabled: Boolean = true,
        purchaseStatus: String? = null,
        onCtaFallback: (String) -> Unit = CtaKeyLog::fallback,
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
            when {
                unavailable || finalFen == null -> null

                priceFen != null && couponFen != null -> PriceTagModel.make(platform, priceFen, couponFen, finalFen, presale)

                // open 已给出新券后价、券前价 / 券额待补齐（[PRICE_PENDING]）：照常显示已知新价，不带口径词（APP-06b 共性第 34 条）
                else -> PriceTagModel.neutral(finalFen, presale)
            }
        val min = card.fen("rebate_min_fen")
        val earned =
            if (provisionalRebate(card)) {
                // 返利已确认、口径还没补上：按服务端区间临时展示（A-R2-01），不当「暂无返利」
                RebateTagModel.provisional(min, card.fen("rebate_max_fen"))
            } else {
                RebateTagModel.make(basis, min, card.fen("rebate_max_fen"), loginRequired = false)
            }
        val rebate = if (notLaunched) RebateTagModel.notice(notLaunchedText) else earned
        val ctaKey = (card["cta"] as? JsonObject)?.text("text_key").orEmpty()
        // {amount} 与返利标签同一格式化结果（单一金额时 min = max）
        val amount = earned.singleAmount?.let { shown -> min?.let { CtaAmount(shown, CouliFormat.moneySpoken(it)) } }
        val verb =
            ctaVerb(ctaKey, amount, texts, onCtaFallback).let {
                // 未开放隐藏返利金额：动词也不带金额（金额与返利标签同源；按钮显示「X返利即将开放」，D10-921 ④）
                if (notLaunched && it.amountSpoken != null) fallbackVerb(amount, texts) else it
            }
        val buyBase = verb.text
        val stale = stale(card)
        val keys = disclaimerKeys(card)
        val quoted = card.text("quoted_at")?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
        val data =
            ProductCardData(
                platformName = texts.text(ProductCardKeys.platform(platform)),
                title = card.text("title").orEmpty(),
                price = price,
                rebate = rebate,
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
                platformCode = platform,
                // 推广标识原样（BR-TEXT-17）；空串没有可显示的字，按没有
                adLabel = card.text("ad_label")?.takeIf(String::isNotEmpty),
                buyVerbAmount = verb.amountSpoken?.let { BuyVerbAmount(text = buyBase, spoken = it) },
            )
        val buyState =
            when {
                unavailable || notLaunched || maintenance -> BuyButtonState.RETRY_LATER

                // 下架（availability=off_shelf，含 30141 写进这张卡的）：卡片按钮立即置灰（搜索卡与详情一致，APP-06b 共性第 28 条）
                card.text("availability") == OFF_SHELF -> BuyButtonState.DISABLED

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

    /**
     * 客户端临时字段（不来自接口）：open 已给出新券后价、券前价 / 券额待补齐（APP-06b 共性第 34 条）。为 true 且券后价已知时不算
     * 「价格不可用」：照常显示新价（不带口径词）、可购买；补齐后去掉。
     */
    const val PRICE_PENDING = "client_price_pending"

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
     * 直链卡除外：BR-PRICE-08 规定它本来就不显示价格，只显示专用返利标签）。open 已给出新券后价、只是券前价 / 券额待补齐
     * （[PRICE_PENDING]）不算：照常显示新价、可购买（APP-06b 共性第 34 条）。
     */
    @Suppress("ReturnCount") // 逐条判定
    fun priceUnavailable(card: JsonObject): Boolean {
        if (card.text("availability") == PRICE_UNAVAILABLE) return true
        if (card.text("rebate_basis") == AMOUNT_UNKNOWN) return false
        if (card.flag(PRICE_PENDING) && card.fen("final_price_fen") != null) return false
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

    /** 同卡返利标签的单一金额：[shown] 是标签上的「¥2.69」，[spoken] 是读屏读法「2.69 元」。 */
    private class CtaAmount(
        val shown: String,
        val spoken: String,
    )

    /** 购买按钮动词；[amountSpoken] 不为 null 表示动词里已填同卡单一返利金额，是这句动词的读屏读法。 */
    private class CtaVerb(
        val text: String,
        val amountSpoken: String? = null,
    )

    /**
     * 购买按钮动词（cta.text_key → 字，FU-X-CTA-KEY-DRIFT 向量 R1–R4）：
     * - 没有 cta / 空键：空动词（按钮禁用），不兜底、不记诊断（R4）；
     * - 渲染得出（[render]）：字典原文，{amount} 已填（R1）；
     * - 渲染不出：记一次诊断（只给 text_key），改用 btn.buy（[fallbackVerb]，R2 / R3）；按钮状态照常由价格、下架、平台状态判。
     */
    private fun ctaVerb(
        key: String,
        amount: CtaAmount?,
        texts: ProductCardTexts,
        onFallback: (String) -> Unit,
    ): CtaVerb {
        if (key.isEmpty()) return CtaVerb("")
        return render(key, amount, texts) ?: fallbackVerb(amount, texts).also { onFallback(key) }
    }

    /** btn.buy：先经调用方 texts（草稿 / 下发文案可覆盖），给不出可用文字时用包内契约字典。 */
    private fun fallbackVerb(
        amount: CtaAmount?,
        texts: ProductCardTexts,
    ): CtaVerb =
        render(ProductCardKeys.BUY_FALLBACK, amount, texts)
            ?: render(ProductCardKeys.BUY_FALLBACK, amount, BUNDLED)
            ?: CtaVerb("")

    /**
     * 渲染一个文案键；渲染不出（R2）返回 null：texts 给空串、给回键名本身、结果带花括号，或契约原文里有填不上的变量
     * （{amount} 只在同卡是单一金额时填得上，其余变量客户端不认识）。变量按包内契约字典的原文判：调用方 texts 可能把没给值的 {变量}
     * 删掉（SearchDraftTexts.text），只看结果认不出来。
     */
    @Suppress("ReturnCount") // 缺变量、渲染不出、渲染得出三个出口
    private fun render(
        key: String,
        amount: CtaAmount?,
        texts: ProductCardTexts,
    ): CtaVerb? {
        val template = DefaultTexts.text(key).orEmpty()
        val needsAmount = template.contains(AMOUNT_VAR)
        if (PLACEHOLDER.containsMatchIn(template.replace(AMOUNT_VAR, "")) || (needsAmount && amount == null)) return null
        if (amount == null || !needsAmount) return texts.text(key).takeIf { usable(it, key) }?.let { CtaVerb(it) }
        val text = texts.format(key, mapOf(AMOUNT_NAME to amount.shown)).takeIf { usable(it, key) } ?: return null
        // 金额的两种写法填出来不同，才说明动词里确实填进了这笔金额（调用方文案可能不带 {amount}）
        val spoken = texts.format(key, mapOf(AMOUNT_NAME to amount.spoken)).takeIf { it != text && usable(it, key) }
        return CtaVerb(text, amountSpoken = spoken)
    }

    private fun usable(
        text: String,
        key: String,
    ): Boolean = text.isNotEmpty() && text != key && !text.contains('{') && !text.contains('}')

    fun disclaimerKeys(card: JsonObject): List<String> =
        (card["disclaimer_keys"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.flag(key: String): Boolean = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true

    /** 整数分；null、缺失、字符串、小数都按「未知」（null），不补 0。 */
    private fun JsonObject.fen(key: String): Long? = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
}
