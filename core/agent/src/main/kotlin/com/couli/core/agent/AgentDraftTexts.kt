package com.couli.core.agent

import com.couli.core.wallet.WalletDraftTexts
import com.couli.generated.DefaultTexts

/** Agent 页取文案的接口（BR-TEXT-12：页面不写中文，只取键）。生产为 [AgentDraftTexts]（契约字典优先，草稿兜底）。 */
fun interface AgentTexts {
    fun text(key: String): String
}

/**
 * Agent 页（APP-10）里契约 texts.default.json 还没有的文案键（rule-gaps G12、G16、G25、G29、G30；照 N-26、N-30–N-35 的集中草稿表做法）。
 * 整张表都是「待定稿，不得提审」：TODO(N-36)、TODO(N-38) 契约补键后改为契约优先、本表兜底（现在已是契约优先）。
 * - agent.guide.*、agent.earnings.*：逐字照抄 规划/08 BR-TEXT-22 细则表（G12）。
 * - 输入框、按钮、胶囊、额度页、未开放态：照设计稿 Agent / AgentStreaming / AgentDisconnected / AgentUnavailable / AgentQuota* 的原文。
 * - AgentConsent（G16）：照 AgentConsent 稿的可见文字，「[待定：同意文本其余正文]」待法务定稿（Q-F3），不得提审。
 * - 标「代拟」的：规划与设计稿都没有原文。
 * 收益卡字段标签复用 APP-09 钱包草稿表的同义词（BR-TEXT-01 术语：可提现余额、预估收益、最早预计、入账核对中）。三端同名（鸿蒙 AgentDraftTexts.ets）。
 */
object AgentDraftTexts : AgentTexts {
    /** 金额隐藏的遮罩（与钱包、订单页同一写法，不带 ¥、位数、正负号）。 */
    const val MASK = "****"

    /** 读屏拼接多段文字的分隔（中文逗号，代拟）。 */
    const val SPOKEN_SEPARATOR = "，"

    /** 一行里多段展示文字的分隔（代拟）。 */
    const val LINE_SEPARATOR = " · "

    /** TODO(N-36) 待定稿，不得提审。键 → 草稿文字。 */
    val DRAFTS: Map<String, String> =
        mapOf(
            // ---- BR-TEXT-22 细则表（逐字）
            "agent.guide.Wallet" to "余额和提现入口都在钱包页。",
            "agent.guide.WithdrawRecords" to "提现进度在提现记录里查看。",
            "agent.guide.AuthManage" to "淘宝、拼多多的授权状态在授权管理页查看。",
            "agent.guide.OrderList" to "你的订单和跟单提示都在订单页。",
            "agent.guide.InviteShare" to "邀请码和邀请海报在邀请好友页。",
            "agent.guide.Messages" to "通知消息都在消息中心。",
            "agent.guide.open" to "去看看",
            "agent.guide.unavailable" to "这个我没法直接帮你打开。",
            "agent.earnings.summary" to "你的收益情况见下方卡片，以钱包页为准。",
            "agent.earnings.unavailable" to "暂时查不到收益信息，可以稍后再试，或到钱包页查看。",
            "agent.earnings.history" to "这是之前查的收益，最新情况以钱包页为准。",
            "agent.earnings.fallback" to "请到钱包页查看收益与提现进度。",
            "agent.earnings.applied_amount" to "申请金额",
            "agent.earnings.open_wallet" to "去钱包",
            "agent.earnings.open_records" to "看提现记录",
            "agent.earnings.need_login" to "登录后才能查你的收益，点下方按钮登录。",
            "agent.earnings.need_phone" to "绑定手机号后才能查你的收益，点下方按钮去绑定。",
            // ---- 收益卡字段标签（复用钱包草稿表同义词；「我的收益」「更新于」「最近一笔提现」代拟，G12）
            "agent.draft.earnings.title" to "我的收益",
            "agent.draft.earnings.withdrawable" to WalletDraftTexts.text("wallet.withdrawable"),
            "agent.draft.earnings.estimated" to WalletDraftTexts.text("wallet.estimated"),
            // 收益汇总的 next_credit_period 是「最早预计」结算月份（BR-TEXT-04，鸿蒙 R1-15）：复用钱包「最早预计」+「{month}结算」，不与订单卡共用键
            "agent.draft.earnings.earliest" to WalletDraftTexts.text("wallet.earliest_label") + " " + WALLET_EARLIEST,
            "agent.draft.earnings.credit_overdue" to WalletDraftTexts.text("wallet.overdue"),
            "agent.draft.earnings.updated_at" to "更新于 {time}",
            "agent.draft.earnings.latest" to "最近一笔提现",
            "agent.draft.amount_hidden" to "金额已隐藏",
            // ---- 页面骨架（设计稿 Agent / AgentStreaming / AgentDisconnected）
            "agent.draft.new_chat" to "新对话",
            "agent.draft.examples_title" to "你可以这样问我",
            "agent.draft.chip.paste" to "粘贴查返利",
            "agent.draft.chip.orders" to "查订单",
            "agent.draft.input_placeholder" to "说说想买什么，或粘贴商品链接",
            "agent.draft.send" to "发送",
            "agent.draft.stop" to "停止",
            "agent.draft.disconnected" to "连接中断",
            "agent.draft.retry" to "重试",
            "agent.draft.disconnected_hint" to "已生成的内容保留在上面，点「重试」继续查询",
            "agent.draft.streaming_hint" to "还在生成，其他商品查到后会继续显示",
            "agent.draft.back_to_bottom" to "回到底部",
            "agent.draft.cancelled" to "已停止生成",
            "agent.draft.too_long" to "最多输入 500 字",
            "agent.draft.context.product" to "关于这个商品",
            "agent.draft.context.order" to "关于这笔订单",
            "agent.draft.context.text" to "关于这段文字",
            "agent.draft.paste_hint" to "粘贴后点发送",
            "agent.draft.ask_ai" to "问问 AI",
            // 代拟：读屏的「用户说」前缀与 AI 回复前缀
            "agent.draft.a11y.user_said" to "我说：{text}",
            "agent.draft.a11y.button" to "{action}，{target}",
            "agent.draft.a11y.thinking" to "正在生成",
            // ---- 额度用完、去搜索（AgentQuotaGuest / AgentQuotaPhone / AgentUnavailable）
            "agent.draft.quota.guest_hint" to "登录并绑定手机号后，每天可以多问几次。",
            "agent.draft.quota.phone_hint" to "绑定手机号后，每天可以多问几次。",
            // 已绑手机的会员额度用完（鸿蒙 H10 / H23，BR-AI-15 none 分支；AgentQuotaPhone 稿没有这一态，代拟）：不再引导绑定
            "agent.draft.quota.member_hint" to "今天的提问次数用完了，明天再来吧。",
            "agent.draft.quota.input_disabled" to "今日次数已用完，明天恢复",
            "agent.draft.search" to "去搜索",
            "agent.draft.search_q" to "去搜索「{q}」",
            "agent.draft.search_hint" to "会带上你这次输入的关键词，到搜索页继续找。",
            "agent.draft.login" to "登录",
            "agent.draft.bind_phone" to "绑定手机号",
            // ---- 购买（BR-PRICE-14，G25；代拟）
            "agent.draft.refresh_batch" to "换一批",
            "agent.draft.coupon_gone" to "这个券已领完",
            // ---- 卡片（代拟：rule_ref、handoff、auth_required、claim_draft 的按钮与标签）
            "agent.draft.rule.view" to "查看完整规则",
            "agent.draft.handoff.open" to "联系客服",
            "agent.draft.auth.union" to "购买前需要先完成{platform_name}授权",
            "agent.draft.auth.go" to "去授权",
            "agent.draft.claim.title" to "订单找回信息",
            "agent.draft.claim.order_no" to "订单号",
            "agent.draft.claim.click_time" to "点击时间",
            "agent.draft.order.title" to "订单",
            "agent.draft.material.title" to "素材参考",
            // ---- 未开放（G01；与服务端 30501 同一版式，正文取 error.30501）
            "agent.draft.unavailable_hint" to "可以先用搜索找货。",
            // ---- AgentConsent（G16：照稿；待法务定稿，不得提审）
            "agent.draft.consent.title" to "使用 AI 助手前，请先确认",
            "agent.draft.consent.line1" to "对话内容和搜索词将发送给阿里云通义千问",
            "agent.draft.consent.line2" to "对话日志保存期限：190 天",
            "agent.draft.consent.line3" to "请勿输入他人姓名、地址等个人信息",
            "agent.draft.consent.pending" to "[待定：同意文本其余正文]",
            "agent.draft.consent.check" to "我已阅读并同意以上说明",
            "agent.draft.consent.agree" to "同意并使用",
            "agent.draft.consent.decline" to "暂不使用",
            "agent.draft.consent.declined" to "你已选择暂不使用 AI 助手，App 其他功能不受影响。",
            "agent.draft.consent.reagree" to "重新同意",
            // 同意没有记录成功（鸿蒙 R1-01 / H18；代拟）：仍不能使用，给【重新同意】
            "agent.draft.consent.failed" to "同意没有保存成功，暂时不能使用 AI 助手，请重新同意。",
            // ---- 设置页撤回（G18；代拟）
            "agent.draft.withdraw.row" to "撤回 AI 服务同意",
            "agent.draft.withdraw.confirm_title" to "撤回 AI 服务同意？",
            "agent.draft.withdraw.confirm_body" to "撤回后当前对话会清空，下次使用 AI 助手需要重新同意。",
            "agent.draft.withdraw.confirm" to "撤回",
            "agent.draft.withdraw.cancel" to "取消",
            "agent.draft.withdraw.done" to "已撤回 AI 服务同意",
            // ---- 读屏：页面引导卡按钮的完整名称里的页面名（代拟，与页面标题一致，鸿蒙 H03）
            "agent.draft.route.Wallet" to "钱包",
            "agent.draft.route.WithdrawRecords" to "提现记录",
            "agent.draft.route.AuthManage" to "授权管理",
            "agent.draft.route.OrderList" to "订单",
            "agent.draft.route.InviteShare" to "邀请好友",
            "agent.draft.route.Messages" to "消息中心",
            // ---- APP-10b（TODO(N-38)，G30）：契约 texts.default.json 没有这些键。照 AgentResult / AgentReport / AgentReported 稿与 flow-map 23.2.22
            // 逐字；标「代拟」的规划与设计稿都没有原文（三端同一张，鸿蒙 AgentDraftTexts.ets）。error.30505 不显示（G25 静默），不进本表。
            "agent.draft.feedback.up" to "赞",
            "agent.draft.feedback.down" to "踩",
            "agent.draft.feedback.up_selected" to "已赞",
            "agent.draft.feedback.down_selected" to "已踩",
            "agent.draft.report.entry" to "举报",
            // 读屏等价入口（G29、G54）：无障碍「更多操作」里的动作名（代拟）
            "agent.draft.report.more" to "更多操作",
            "agent.draft.report.more_entry" to "更多操作，举报",
            // 只有卡片、没有文字的回复的读屏操作宿主（Android R2-06，代拟）
            "agent.draft.a11y.card_reply" to "AI 回复的卡片",
            "agent.draft.report.title" to "举报这条回复",
            "agent.draft.report.subtitle" to "选择原因后提交，我们会核实处理",
            "agent.draft.report.reason.misleading" to "内容不实或有误导",
            "agent.draft.report.reason.illegal" to "含违法违规内容",
            "agent.draft.report.reason.vulgar" to "含低俗、冒犯内容",
            "agent.draft.report.reason.infringe" to "侵犯他人权益",
            "agent.draft.report.reason.other" to "其他问题",
            "agent.draft.report.supplement" to "补充说明（选填）",
            "agent.draft.report.submit" to "提交举报",
            "agent.draft.report.reported" to "已举报",
            "agent.draft.report.toast" to "举报已提交，我们会核实处理",
            "agent.draft.report.joiner" to "：",
            "agent.draft.report.too_long" to "补充说明太长了，请删减后再提交",
            "agent.draft.report.reason_error" to "请重新选择举报原因",
            "agent.draft.report.close" to "关闭",
            "agent.draft.action_failed" to "操作没有成功，请稍后再试",
            "agent.draft.stopping" to "正在停止",
            "agent.draft.stopped" to "已停止",
            "agent.draft.limited" to "回复内容过长，后面的内容不再显示",
            "agent.draft.replying" to "回复中",
            "agent.draft.refresh" to "刷新",
            "agent.draft.history.earlier" to "加载更早的消息",
            "agent.draft.history.none" to "没有更早的消息",
            "agent.draft.history.failed" to "加载失败，点此重试",
            "agent.draft.history.load_failed" to "对话记录加载失败",
            "agent.draft.readonly" to "这个对话已结束，只能查看",
            "agent.draft.consent.not_saved" to "同意没有保存成功",
            "agent.draft.quota.tomorrow" to "今天的提问次数用完了，明天再来吧",
            // 缺号（G36）：本条结束、等历史替换（代拟）
            "agent.draft.incomplete" to "这条回复不完整，正在重新获取",
        )

    private val WALLET_EARLIEST: String get() = WalletDraftTexts.DRAFTS["wallet.earliest"].orEmpty()

    private val PLACEHOLDER = Regex("\\{[a-z0-9_]+\\}")

    /** 契约优先（texts.default.json），其次本表；都没有返回空串（不显示原始键）。带 {变量} 的原文原样返回，由 [format] 填。 */
    override fun text(key: String): String = DefaultTexts.text(key) ?: DRAFTS[key].orEmpty()

    fun has(key: String): Boolean = DefaultTexts.text(key) != null || key in DRAFTS

    /** 填 {变量}；仍有没填上的变量时去掉占位（不把占位符显示给用户）。 */
    fun format(
        key: String,
        vars: Map<String, String>,
    ): String = vars.entries.fold(text(key)) { acc, (name, value) -> acc.replace("{$name}", value) }.replace(PLACEHOLDER, "")

    /** 收益卡预估收益下的说明行（鸿蒙 R1-15）：「最早预计 11 月结算」+ 逾期时「入账核对中」，用 · 连接；都没有为空串。 */
    fun earningsCredit(
        month: String?,
        overdue: Boolean,
    ): String =
        listOfNotNull(
            month?.let { format("agent.draft.earnings.earliest", mapOf("month" to it)) },
            text("agent.draft.earnings.credit_overdue").takeIf { overdue },
        ).joinToString(LINE_SEPARATOR)
}
