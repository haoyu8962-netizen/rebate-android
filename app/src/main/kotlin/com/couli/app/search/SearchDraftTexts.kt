package com.couli.app.search

import com.couli.feature.product.ProductTexts
import com.couli.feature.search.SearchTexts
import com.couli.generated.DefaultTexts

/**
 * 搜索页与商品详情页里契约 texts.default.json 还没有的文案键（APP-06a rule-gaps：普通搜索空态键、平台名、排序名等未入契约，对应 N-27）。
 * 照设计稿 Search、SearchTyping、SearchEmpty、SearchFilterPrice、SearchDisclaimerNote、Product、A11yHitSearch、A11yFocusProduct 逐字集中
 * 放在这一处；排序名照 BR-PRICE-18。三端同名（iOS / Android / 鸿蒙 SearchDraftTexts）。有键的（error.*、external_page.retry）一律读
 * DefaultTexts，不放这里。价格、返利、购买按钮动词与口径说明的词在 DesignSystem PriceTerms（N-11）。
 *
 * TODO(N-27) 整张表都是「待定稿，不得提审」：契约补键后删除本文件、改读 DefaultTexts；提审前（QA-07）必须替换。
 */
internal object SearchDraftTexts : SearchTexts, ProductTexts {
    /** TODO(N-27) 待定稿，不得提审。键 → 草稿文字。 */
    val DRAFTS: Map<String, String> =
        mapOf(
            // ---- 平台名（contracts/enums/platform.yaml 的显示名未入契约字典）
            "platform.taobao" to "淘宝",
            "platform.jd" to "京东",
            "platform.pdd" to "拼多多",
            "platform.meituan" to "美团",
            "platform.vip" to "唯品会",
            "platform.douyin" to "抖音",
            "platform.eleme" to "饿了么",
            "platform.kuaishou" to "快手",
            "platform.suning" to "苏宁",
            // ---- 卡片（DesignSystem ProductCardMapper）
            "card.disclaimer_button" to "价格与返利说明",
            "card.image_placeholder" to "商品图",
            "card.disclaimer_title" to "价格与返利说明",
            "card.disclaimer_close" to "知道了",
            // 价格不可用（BR-PRICE-01、AC-S1-09「暂时查不到该商品价格」）、取价处的降级提示（BR-PRICE-11）、按钮「稍后再试」（BR-TEXT-14 表 C）
            "card.price_unavailable" to "暂时查不到该商品价格",
            "card.price_stale" to "价格可能已变化，以下单页为准",
            "card.retry_later" to "稍后再试",
            // ---- Search / SearchTyping
            "search.back" to "返回",
            "search.placeholder" to "搜索商品",
            "search.clear" to "清除",
            "search.submit" to "搜索",
            "search.history.title" to "搜索历史",
            "search.history.clear" to "清空",
            "search.sort.relevance" to "综合",
            "search.sort.sales_desc" to "销量",
            "search.sort.final_price_asc" to "券后价从低到高",
            "search.sort.rebate_desc" to "返利从高到低",
            // ---- SearchFilterPrice
            "search.filter.coupon" to "只看有券",
            "search.filter.price" to "价格区间",
            "search.filter.price_range" to "券后 {min}–{max}",
            "search.filter.price_min_only" to "券后 {min} 以上",
            "search.filter.price_max_only" to "券后 {max} 以内",
            "search.filter.sheet.min" to "最低价",
            "search.filter.sheet.max" to "最高价",
            "search.filter.sheet.note" to "按券后价筛选，单位元",
            "search.filter.sheet.reset" to "重置",
            "search.filter.sheet.confirm" to "确定",
            "search.filter.sheet.invalid" to "请输入 0.01–100000 元，最低价不能高于最高价",
            // ---- SearchEmpty
            "search.empty.title" to "没有找到符合条件的商品",
            "search.empty.message" to "换个关键词，或放宽价格区间再试试",
            "search.fallback.title" to "看看{platform_name}的其他商品",
            "search.fallback.note" to "{platform_name}推荐商品，不受当前关键词和价格区间限制",
            // ---- 没有可搜索的平台（BR-PROD-10：search_status 全部不是 on；设计稿没有这一项，措辞待定稿）
            "search.unavailable" to "暂时没有可以搜索的平台，可以粘贴商品链接查返利",
            // ---- 分页（设计稿无文字，按 GUIDE §3 列表底部写法）
            "search.load_more" to "加载更多",
            "search.loading" to "加载中",
            "search.end" to "没有更多了",
            // ---- 缓存降级（BR-PROD-07 降级表「结果顶部显示」）
            "search.stale_notice" to "价格可能已变化，下单前以平台为准",
            // ---- Product / A11yFocusProduct
            "product.title" to "商品详情",
            "product.back" to "返回",
            "product.ask_ai" to "问问 AI",
            "product.image" to "商品图片，{title}",
            "product.research" to "重新搜索",
            // 入账说明（BR-TEXT-04「ProductDetail『确认收货后随联盟月度结算入账』文案（texts，无变量）」、F-PROD-05；契约字典未入键）
            "product.credit_note" to "确认收货后随联盟月度结算入账",
        )

    private val PLACEHOLDER = Regex("\\{[a-z0-9_]+\\}")

    /** 先取契约字典，再取草稿；带 {变量} 的文案（没有给值）用 fallback。缺键返回空串。 */
    override fun text(key: String): String {
        val raw = DefaultTexts.text(key) ?: DRAFTS[key] ?: DefaultTexts.fallback(key) ?: return ""
        return if (PLACEHOLDER.containsMatchIn(raw)) DefaultTexts.fallback(key) ?: raw.replace(PLACEHOLDER, "") else raw
    }

    /** 填 {变量}；值为空或仍有没填上的变量时用 fallback（BR-TEXT-14：缺变量回退）。 */
    @Suppress("ReturnCount") // 缺键、填完、回落三个出口
    override fun format(
        key: String,
        vars: Map<String, String>,
    ): String {
        var text = DefaultTexts.text(key) ?: DRAFTS[key] ?: return text(key)
        vars.forEach { (name, value) -> if (value.isNotEmpty()) text = text.replace("{$name}", value) }
        if (!PLACEHOLDER.containsMatchIn(text)) return text
        return DefaultTexts.fallback(key) ?: text.replace(PLACEHOLDER, "")
    }
}
