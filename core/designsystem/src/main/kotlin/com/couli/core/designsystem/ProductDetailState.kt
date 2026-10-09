package com.couli.core.designsystem

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * 详情页模型的原始判定（APP-06a；向量 specs/search-product-vectors.json detail 组，三端同名）。显示文字由页面经 [ProductCardMapper] 格式化。
 * 规则空白（rule-gaps、N-27）一律保守处理：判定不了的状态不显示。
 */
data class ProductDetailView(
    /** 购买按钮文案键：服务端 cta.text_key 原样（BR-TEXT-12、TRADE-21：客户端不自己决定）；缺失为空串。 */
    val buttonKeyRaw: String,
    /** 预售（is_presale，BR-PRICE-22）：「预售」标签与说明。 */
    val presale: Boolean,
    /** 可返利、金额未知（rebate_basis=amount_unknown，BR-PRICE-08、TRADE-08）。 */
    val rebateUnknown: Boolean,
    /** 公开态：游客或基本模式（不表示公开发布版本的分享入口，rule-gaps）。 */
    val publicMode: Boolean,
    /**
     * 购买按钮可点：有按钮文案键，且 availability 不是 off_shelf / price_unavailable。
     * TODO(N-27)：下架与价格不可用的按钮文案键未规定（rule-gaps），按钮置灰、文案仍用服务端键。
     */
    val buyEnabled: Boolean,
    /** 缓存 / 商品池降级（stale=true 或 age_sec > 300，BR-PRICE-11）：取价处提示价格可能已变化；不是降级卡（见 [degraded]）。 */
    val stale: Boolean,
    /** 价格不可用（BR-PRICE-01）：不显示任何金额，价格保持未知、不补 0。 */
    val priceUnavailable: Boolean,
    /**
     * 平台购买状态（features.platform_status.<platform>，PlatformPurchaseStatus；03 §7.4、BR-PROD-10，APP-06a 共性第 27、31 条）：
     * on / maintenance / not_launched；context 没给（缺失）为 null，按 on。maintenance、not_launched 时 [buyEnabled] 为 false。
     */
    val purchaseStatus: String? = null,
) {
    /**
     * 降级卡（ProductCardDegrade）。TODO(N-27)：getProduct 没有平台状态与认领配置，stale 也不等于画板的五种能力降级；判定不了，
     * 恒为 false（页面不显示降级卡），补齐依据后再判。
     */
    val degraded: Boolean get() = false

    /**
     * 返利消失（ProductRebateGone）。TODO(N-27)、TODO(APP-06b)：只有 open 复核时返利由正变 0 才算（BR-PRICE-13），单张当前卡判定不了；
     * 恒为 false（不显示）。
     */
    val rebateGone: Boolean get() = false
}

/**
 * getProduct 的 ProductCard → 详情页模型。context：{ loggedIn: Boolean, privacy: "consented" | "basic", platformStatus?: "on" | "maintenance" |
 * "not_launched" }（platformStatus 取这一刻的 features.platform_status.<platform>，APP-06b 接入）。
 */
object ProductDetailState {
    const val PRIVACY_CONSENTED = "consented"
    const val PRIVACY_BASIC = "basic"
    private const val OFF_SHELF = "off_shelf"
    private const val AMOUNT_UNKNOWN = "amount_unknown"

    fun map(
        product: JsonObject,
        context: JsonObject,
    ): ProductDetailView {
        val key = ((product["cta"] as? JsonObject)?.get("text_key") as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        val loggedIn = (context["loggedIn"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true
        val basic = (context["privacy"] as? JsonPrimitive)?.content == PRIVACY_BASIC
        val unavailable = ProductCardMapper.priceUnavailable(product)
        val offShelf = (product["availability"] as? JsonPrimitive)?.content == OFF_SHELF
        val status = (context["platformStatus"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val purchaseBlocked = status == ProductCardMapper.PURCHASE_MAINTENANCE || status == ProductCardMapper.PURCHASE_NOT_LAUNCHED
        return ProductDetailView(
            buttonKeyRaw = key,
            presale = (product["is_presale"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true,
            rebateUnknown = (product["rebate_basis"] as? JsonPrimitive)?.content == AMOUNT_UNKNOWN,
            publicMode = !loggedIn || basic,
            buyEnabled = key.isNotEmpty() && !offShelf && !unavailable && !purchaseBlocked,
            stale = ProductCardMapper.stale(product),
            priceUnavailable = unavailable,
            purchaseStatus = status,
        )
    }
}
