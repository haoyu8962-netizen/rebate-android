package com.couli.core.orders

import com.couli.generated.DefaultTexts

/** 订单页面取文案的接口（BR-TEXT-12：页面不写中文，只取键）。生产为 [OrderDraftTexts]（契约字典优先，草稿兜底）。 */
interface OrderTexts {
    /** 不带变量的文案；带 {变量} 的文案按 fallback（BR-TEXT-14）；缺键为空串。 */
    fun text(key: String): String

    /** 填 {变量}；值为空或仍有没填上的变量时用 fallback；没有 fallback 时去掉未填的占位。 */
    fun format(
        key: String,
        vars: Map<String, String>,
    ): String
}

/**
 * 订单与待跟单卡里契约 texts.default.json 还没有的文案键（rule-gaps G01、G14、G21：状态文案、原因文案、金额格标签、页面结构与按钮）。
 * 原文逐字取 BR-TEXT-02 映射表、BR-TEXT-05 细则表、BR-TEXT-14 表 C 与设计稿 Orders* / OrderDetail* / ProductPendingTrack；
 * 「代拟」的几条（复制失败、网络错误、PUNISH 默认说明、申诉中说明去掉提交时间）设计稿与规划都没有，提审前替换。
 * 契约里已有的键（order_list.*、order_status_group.*、order_timeline.deposit_paid / final_paid、pending_track_card.title / desc、
 * net.stale_data、perm.push.*、amount_mask.hide / show、error.*）一律读 DefaultTexts，不放这里。三端同名（iOS / 鸿蒙 OrderDraftTexts）。
 *
 * TODO(N-31) 整张表都是「待定稿，不得提审」：契约补键（/v1/dict 或 texts.default.json）后删除本表、改读字典。
 */
object OrderDraftTexts : OrderTexts {
    /** TODO(N-31) 待定稿，不得提审。键 → 草稿文字。 */
    val DRAFTS: Map<String, String> =
        mapOf(
            // ---- 状态文案（BR-TEXT-02 映射表，G01）
            "order_status.DEPOSIT_PAID.label" to "已付定金",
            "order_status.DEPOSIT_PAID.hint" to "尾款付清后计算返利",
            "order_status.PAID.label" to "已付款，返利待确认",
            "order_status.PAID.hint" to "确认收货后随联盟月度结算入账",
            "order_status.WAITING.label" to "已收货，等待联盟结算",
            "order_status.WAITING.hint" to "预计 {credit_period}结算",
            "order_status.CREDITING.label" to "入账核对中",
            "order_status.CREDITING.hint" to "如有疑问请联系客服",
            "order_status.RIGHTS_PENDING.label" to "售后处理中，入账暂停",
            "order_status.RIGHTS_PENDING.hint" to "售后结束后随联盟结算入账",
            "order_status.REVIEWING.label" to "入账核对中",
            "order_status.REVIEWING.hint" to "如有疑问请联系客服",
            "order_status.NO_REBATE.label" to "本单无返利",
            "order_status.CREDITED.label" to "已结算",
            "order_status.CREDITED_PART_CLAWED.label" to "已结算（部分扣回 {z}）",
            "order_status.INVALID.label" to "已失效",
            "order_status.CLAWED_BACK.label" to "已扣回",
            "order_status.UNKNOWN.label" to "状态更新中",
            "order_status.UNKNOWN.hint" to "请稍后查看",
            // ---- 原因（BR-TEXT-05 细则表，G01；PUNISH 子原因 G04 不做）
            "order_reason.REFUND.title" to "订单已退款或取消",
            "order_reason.REFUND.desc" to "退款或取消后平台不再支付返利",
            "order_reason.PART_REFUND.title" to "部分退款",
            "order_reason.PART_REFUND.desc" to "返利按剩余金额计算",
            "order_reason.RIGHTS.title" to "发起售后维权，返利取消",
            "order_reason.RIGHTS.desc" to "售后成功后平台不再支付这笔返利",
            "order_reason.PUNISH.title" to "订单被联盟判定违规",
            // 代拟：BR-TEXT-05 的 PUNISH 说明按 reason_sub 细分，OrderDetail 没有 reason_sub（G04），先给不带子原因的默认说明
            "order_reason.PUNISH.desc" to "如有疑问可联系客服",
            "order_reason.PRICE_COMPARE.title" to "比价订单",
            "order_reason.PRICE_COMPARE.desc" to "联盟按比价订单规则计算，返利相应降低",
            "order_reason.PRICE_PROTECT.title" to "商家价保退差",
            "order_reason.PRICE_PROTECT.desc" to "返利按价保后实付金额重新计算",
            "order_reason.SETTLE_DIFF.title" to "结算金额调整",
            "order_reason.SETTLE_DIFF.desc" to "联盟结算金额与预估不同，已按结算金额调整",
            "order_reason.NOT_TRACKED.title" to "未找到这笔订单的返利记录",
            "order_reason.NOT_TRACKED.desc" to "可能不是通过本 App 链接下单，或下单前点过其他返利链接",
            "order_reason.EXPIRED_CLICK.title" to "下单时链接已过期",
            "order_reason.EXPIRED_CLICK.desc" to "点击链接超过 {click_valid_days} 天才下单",
            "order_reason.RELATION_INVALID.title" to "淘宝授权未完成或已失效",
            "order_reason.RELATION_INVALID.desc" to "重新授权后可提交找回",
            "order_reason.OTHER_TLJ.title" to "订单归其他推广者",
            "order_reason.OTHER_TLJ.desc" to "下单时使用了其他推广者的淘礼金",
            "order_reason.BLACKLIST.title" to "订单未通过安全校验",
            "order_reason.BLACKLIST.desc" to "如有疑问可提交申诉",
            "order_reason.PRESALE_UNPAID.title" to "预售尾款未付",
            "order_reason.PRESALE_UNPAID.desc" to "尾款未付，订单未成交",
            "order_reason.COMMISSION_ZERO.title" to "返利已取消",
            "order_reason.COMMISSION_ZERO.desc" to "平台将这笔订单的返利调整为 0，平台未说明原因",
            "order_reason.OTHER.title" to "其他原因",
            "order_reason.OTHER.desc" to "可联系客服查询",
            "order_reason.clawed_back_extra" to "已从余额扣除",
            // ---- 金额格标签（G14：设计稿 Orders / OrderDetail* 原文）
            "order_amount.paid" to "实付金额",
            "order_amount.est_rebate" to "预估返",
            "order_amount.actual" to "实返",
            "order_amount.rebate" to "返利",
            "order_amount.clawback" to "扣回",
            "order_amount.est_promo" to "预估推广收益",
            "order_amount.promo" to "推广收益",
            // ---- 订单列表（设计稿 Orders、OrdersShare、OrdersEmpty、OrdersShareEmpty、OrdersSearch*、OrdersFilter*）
            "order_list.title" to "订单",
            "order_list.tab.self" to "自购",
            "order_list.tab.share" to "分享",
            "order_list.find_entry" to "未跟单？去找回",
            "order_list.search.label" to "搜索订单",
            "order_list.search.clear" to "清除",
            "order_list.empty.self.title" to "还没有自购订单",
            "order_list.empty.self.message" to "通过凑狸领券下单后，订单会显示在这里；下单后没看到，可以点「去找回」提交",
            "order_list.empty.share.title" to "还没有分享订单",
            "order_list.empty.share.message" to "好友通过你分享的商品下单后，订单会显示在这里",
            "order_list.empty.action" to "去首页逛逛",
            "order_list.search.found" to "找到 {n} 笔订单",
            "order_list.search.none" to "没有找到相关订单",
            "order_list.search.share_note" to "分享订单只按商品名搜索",
            "order_list.filter.all_platforms" to "全部平台",
            "order_list.filter.all_months" to "全部月份",
            "order_list.filter.month_note" to "按付款时间；预售单按付定金时间",
            "order_list.filter.month_item" to "{year} 年 {month} 月",
            "order_list.filter.close" to "关闭",
            "order_list.load_more" to "上拉加载更多",
            "order_list.loading" to "加载中",
            "order_list.end" to "没有更多了",
            "order_list.quantity" to "件数 {n}",
            "order_list.order_no" to "订单号 {no}",
            "order_list.copy" to "复制",
            "order_list.copy_label" to "复制订单号",
            // 读屏：分享单脱敏号里的「*」读作「星号」（adaptation-a11y-spec §9）
            "order_list.star_spoken" to "星号",
            "order_list.image_placeholder" to "商品图",
            "order_list.retry" to "重试",
            // 代拟：断网、超时（没有缓存时的错误态；BR-TEXT-14 没有网络错误的键）
            "order_list.network_error" to "网络不可用，请检查网络后重试",
            "order_list.load_failed" to "加载失败",
            // ---- 订单详情（设计稿 OrderDetail*）
            "order_detail.title" to "订单详情",
            "order_detail.back" to "返回",
            "order_detail.why_no_rebate" to "为什么没返利？",
            "order_detail.view_help" to "查看说明",
            "order_detail.timeline" to "时间线",
            // 原因说明与「已从余额扣除」之间的分隔（设计稿 OrderDetailClawedBack）
            "order_detail.desc_join" to "；",
            "order_detail.appeal_pending.title" to "申诉中",
            // 代拟：设计稿写「10月12日 10:30 提交，结果会通过站内信通知你」，OrderDetail 没有申诉提交时间（G08），去掉时间
            "order_detail.appeal_pending.desc" to "结果会通过站内信通知你",
            "order_detail.appeal_progress" to "查看申诉进度",
            "order_detail.copy.done" to "已复制订单号",
            // 代拟：记 hash 失败不写剪贴板时的提示（APP-07 共性第 23 条；设计稿与规划都没有）
            "order_detail.copy.failed" to "复制失败，请重试",
            // ---- 详情按钮（BR-TEXT-02 状态固有按钮、BR-TEXT-05 action）
            "order_action.withdraw" to "去提现",
            "order_action.ledger" to "查看流水",
            "order_action.contact_cs" to "联系客服",
            "order_action.appeal" to "去申诉",
            "order_action.claim" to "去找回",
            "order_action.reauth" to "去授权",
            // ---- 时间线（BR-TEXT-02「时间线」，G01、G16）
            "order_timeline.paid" to "付款",
            "order_timeline.received" to "收货",
            "order_timeline.credit_expected" to "联盟结算",
            "order_timeline.credit_expected_month" to "预计 {credit_period}结算",
            "order_timeline.credited" to "已结算",
            "order_timeline.invalid" to "失效",
            "order_timeline.clawed_back" to "扣回",
            "order_timeline.part_clawed_back" to "部分扣回",
            "order_timeline.not_happened" to "未发生",
            // ---- 待跟单卡（ProductPendingTrack）
            "pending_track_card.close" to "关闭订单同步提示",
            "pending_track_card.claim" to "未跟单？去找回",
            // ---- 金额隐藏（BR-TEXT-10 细则「金额隐藏」，G21；adaptation-a11y-spec §9）
            "amount_mask.mask" to "****",
            "amount_mask.hidden_spoken" to "金额已隐藏",
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
        )

    /*
     * 「预计 {credit_period}结算」：{credit_period} 已是「11 月」/「2027 年 1 月」（CreditPeriodText），占位后不再加空格，拼出来是 BR-TEXT-04 例子的
     * 「预计 11 月结算」。
     */

    /** 草稿的 fallback（变量缺失时；BR-TEXT-14 缺变量回退）。 */
    private val DRAFT_FALLBACKS: Map<String, String> =
        mapOf(
            "order_status.CREDITED_PART_CLAWED.label" to "已结算（部分扣回）",
            "order_reason.EXPIRED_CLICK.desc" to "点击链接超过有效期才下单",
            "order_timeline.credit_expected_month" to "联盟结算",
            "order_list.search.found" to "找到相关订单",
        )

    private val PLACEHOLDER = Regex("\\{[a-z0-9_]+\\}")

    private fun raw(key: String): String? = DefaultTexts.text(key) ?: DRAFTS[key]

    private fun fallback(key: String): String? = DefaultTexts.fallback(key) ?: DRAFT_FALLBACKS[key]

    override fun text(key: String): String {
        val raw = raw(key) ?: fallback(key) ?: return ""
        return if (PLACEHOLDER.containsMatchIn(raw)) fallback(key) ?: raw.replace(PLACEHOLDER, "") else raw
    }

    @Suppress("ReturnCount") // 缺键、填完、回落三个出口
    override fun format(
        key: String,
        vars: Map<String, String>,
    ): String {
        var text = raw(key) ?: return text(key)
        vars.forEach { (name, value) -> if (value.isNotEmpty()) text = text.replace("{$name}", value) }
        if (!PLACEHOLDER.containsMatchIn(text)) return text
        return fallback(key) ?: text.replace(PLACEHOLDER, "")
    }

    /** 平台显示名（不认识的平台按原编码不显示，返回空串）。 */
    fun platformName(platform: String): String = text("platform.$platform")
}
