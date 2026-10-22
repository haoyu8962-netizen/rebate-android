package com.couli.app.trade

import com.couli.app.search.SearchDraftTexts
import com.couli.feature.trade.TradeTexts
import com.couli.generated.DefaultTexts

/**
 * 购买流程、AuthSheet、JumpTip、AuthManage 里契约 texts.default.json 还没有的文案键（APP-06b rule-gaps【Android 实现发现】，TODO(N-28)）。
 * 照设计稿 ProductBuyOpening、ProductBuyTimeout、ProductPriceChanged(+RebateGone、Cancelled)、ProductRebateGone(+Cancelled)、
 * ProductPriceCompareNoRebate(+Cancelled)、ProductAuthSheet、ProductAuthDeclined、ProductAuthFailed、ProductAuthUnavailable、ProductJumpTip、
 * ProductJumpNotInstalled、ProductJumpBrowser、ProductJumpSdkFailed、AuthManageBlocked 逐字集中放在这一处；三端同名。契约有的键（buy.*、
 * error.*、auth_manage.*、jump.taobao_sdk_unavailable、no_rebate.price_compare.*、share_page.copy_tpwd.done）一律读 DefaultTexts。
 * 平台名、返回、加载中等沿用 [SearchDraftTexts]。
 *
 * TODO(N-28) 整张表都是「待定稿，不得提审」：契约补键后删除本文件、改读 DefaultTexts。
 */
internal object TradeDraftTexts : TradeTexts {
    /** TODO(N-28) 待定稿，不得提审。键 → 草稿文字。 */
    val DRAFTS: Map<String, String> =
        mapOf(
            // ---- 浏览器打开（ProductJumpBrowser，BR-ATTR-27 京东行「将通过浏览器打开京东」）
            "trade.browser_notice" to "将通过浏览器打开{platform_name}",
            // ---- 价格变动 / 券失效 / 返利归零（BR-PRICE-13 / 14）
            "trade.price_changed.title" to "价格已变动",
            "trade.price_changed.before" to "之前（{price_label}）",
            "trade.price_changed.now" to "现在（{price_label}）",
            // 新价口径未补齐（券失效、普通改价后券额未知）：不标口径词（共性第 30、37 条，三端同名）
            "trade.price_changed.now_plain" to "现在",
            // 原价口径未知（共性第 47 条）：「之前」不带口径词
            "trade.price_changed.before_plain" to "之前",
            "trade.price_changed.ask" to "继续购买？最终价格以下单页为准",
            // 券失效没有单独的画板（BR-PRICE-14「弹券失效确认」）：标题按规则原意
            "trade.coupon_gone.title" to "优惠券已失效，价格已变动",
            "trade.rebate_gone.line" to "该商品当前暂无返利",
            "trade.rebate_gone.note" to "继续购买不会获得返利",
            "trade.rebate_gone.title" to "该商品当前暂无返利，继续购买？",
            // 只在新旧价格相同时用（共性第 45 条）；新价不同（变动未达阈值）用 body_changed，{price_now} 是「现在（券后）」或券额未补齐时的「现在」
            "trade.rebate_gone.body" to "价格没有变化，{price_label} {price}。继续购买不会获得返利",
            "trade.rebate_gone.body_changed" to "{price_now} {price}。继续购买不会获得返利",
            // 价格没有变化但口径未知（共性第 47 条）：不默认「券后」
            "trade.rebate_gone.body_plain" to "价格没有变化，{price}。继续购买不会获得返利",
            "trade.cancel" to "取消",
            "trade.continue" to "继续购买",
            "trade.cancelled.price" to "已取消购买，价格已更新为最新",
            "trade.cancelled.price_rebate" to "已取消购买，价格和返利信息已更新",
            "trade.cancelled.rebate" to "已取消购买，返利信息已更新",
            // requote_failed（BR-PRICE-13「用返回链接外跳，同时提示价格以下单页为准」；没有画板）
            "trade.requote_notice" to "暂时无法确认最新价格，以下单页为准",
            // ---- 比价无返利（BR-PRICE-08）
            "trade.compare.body" to "继续购买不会获得返利，最终价格以下单页为准。",
            "trade.compare.explain" to "查看说明",
            "trade.buy_no_rebate" to "仍去购买（无返利）",
            // ---- AuthSheet / 关闭 / 失败 / 不可用（BR-ID-17 / 18 / 24）
            "trade.close" to "关闭",
            "trade.auth.reason" to "授权后，才能识别你在{platform_name}下的订单并计算返利",
            "trade.auth.privacy" to "只用于识别订单，不获取你的淘宝昵称、头像和账号名",
            "trade.auth_declined.title" to "未授权将无法获得返利",
            "trade.auth_declined.body" to "完成{platform_name}授权后购买，才能识别这笔订单并计算返利。也可以不授权直接去{platform_name}购买，这笔订单不计返利。",
            "trade.auth_failed.body" to "未授权将无法获得返利。可以重新授权，或不授权直接去{platform_name}购买（这笔订单不计返利）。",
            "trade.auth_unavailable.body" to "商品信息保留在本页，稍后可以再点购买。",
            "trade.ok" to "知道了",
            // ---- JumpTip（BR-ATTR-21；文案后台可配 jump_tip.<platform>，配置为空时用画板的两句）
            "trade.jump_tip.title" to "下单须知",
            "trade.jump_tip.line1" to "在打开的商品页直接下单",
            "trade.jump_tip.line2" to "不要加购后隔天再买",
            "trade.jump_tip.go" to "知道了，去{platform_name}",
            // ---- 外跳失败（BR-ATTR-27）
            "trade.sdk_failed.body" to "这次没有跳转，商品信息保留在本页，稍后可以再点购买。",
            "trade.not_installed" to "安装{platform_name}后下单才有返利",
            "trade.not_installed.copied" to "安装{platform_name}后下单才有返利，口令已复制",
            // 淘宝未安装、口令已复制（ProductJumpNotInstalled-v2，08 BR-TEXT-14 表 C，G-D11；三端同名，登记 CT-16f）：按钮与轻提示
            "trade.not_installed.copy_tpwd" to "复制淘口令",
            "trade.not_installed.tpwd_copied" to "已复制淘口令",
            "trade.jump_failed" to "暂时无法打开{platform_name}，请稍后再试",
            // 跳转方案过了 expire_at（A-R1-12；没有画板与文案键，草稿，TODO(N-28)）
            "trade.jump_expired" to "购买链接已过期，请重新点购买",
            // ---- 联系客服（统一客服出口未接入，暂经 Router 打开帮助中心，TODO(N-28)）
            "trade.contact" to "联系客服",
        )

    private val PLACEHOLDER = Regex("\\{[a-z0-9_]+\\}")

    private fun raw(key: String): String? = DefaultTexts.text(key) ?: DRAFTS[key] ?: SearchDraftTexts.DRAFTS[key]

    /** 先取契约字典，再取草稿；带 {变量} 的文案（没有给值）用 fallback。缺键返回空串。 */
    override fun text(key: String): String {
        val raw = raw(key) ?: DefaultTexts.fallback(key) ?: return ""
        return if (PLACEHOLDER.containsMatchIn(raw)) DefaultTexts.fallback(key) ?: raw.replace(PLACEHOLDER, "") else raw
    }

    /** 填 {变量}；值为空或仍有没填上的变量时用 fallback（BR-TEXT-14：缺变量回退）。 */
    @Suppress("ReturnCount") // 缺键、填完、回落三个出口
    override fun format(
        key: String,
        vars: Map<String, String>,
    ): String {
        var text = raw(key) ?: return text(key)
        vars.forEach { (name, value) -> if (value.isNotEmpty()) text = text.replace("{$name}", value) }
        if (!PLACEHOLDER.containsMatchIn(text)) return text
        return DefaultTexts.fallback(key) ?: text.replace(PLACEHOLDER, "")
    }
}
