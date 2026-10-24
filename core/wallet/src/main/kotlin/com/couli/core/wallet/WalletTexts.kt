package com.couli.core.wallet

import com.couli.core.designsystem.CouliFormat
import com.couli.generated.DefaultTexts

/** 钱包类页面取文案的接口（BR-TEXT-12：页面不写中文，只取键）。生产为 [WalletDraftTexts]（契约字典优先，草稿兜底）。 */
interface WalletTexts {
    /** 不带变量的文案；带 {变量} 的文案按 fallback（BR-TEXT-14）；缺键为空串。 */
    fun text(key: String): String

    /** 填 {变量}；值为空或仍有没填上的变量时用 fallback；没有 fallback 时去掉未填的占位。 */
    fun format(
        key: String,
        vars: Map<String, String>,
    ): String

    /** 键是否存在（契约或草稿表）。 */
    fun has(key: String): Boolean
}

/**
 * 钱包、提现、二次验证、提现记录、实名、收款账号、劳务协议里契约 texts.default.json 还没有的文案键（rule-gaps G08、G09）。原文逐字取
 * BR-TEXT-06 / 07 / 08 与 BR-TEXT-14 表 C（wallet.help.*、withdraw.risk_blocked.desc、cs.appeal_entry），其余逐字取设计稿（Wallet* /
 * Withdraw* / WithdrawRecords* / RealName* / LaborAgreement / PayoutAccount*）；设计稿也没有的标「代拟」。契约里已有的键（withdraw.*、
 * withdraw_detail.*、pending_confirm.*、amount_mask.hide / show、net.stale_data、error.*）一律读 DefaultTexts，不放这里。三端同名。
 *
 * TODO(N-32) 整张表都是「待定稿，不得提审」：契约补键（/v1/dict 或 texts.default.json）后删除本表、改读字典。
 */
object WalletDraftTexts : WalletTexts {
    /** TODO(N-32) 待定稿，不得提审。键 → 草稿文字。 */
    val DRAFTS: Map<String, String> =
        mapOf(
            // ---- 钱包页（设计稿 Wallet*；BR-TEXT-01）
            "wallet.title" to "钱包",
            "wallet.back" to "返回",
            "wallet.withdrawable" to "可提现余额",
            "wallet.records" to "提现记录",
            "wallet.withdraw" to "提现",
            "wallet.frozen" to "冻结中",
            "wallet.withdrawn" to "已提现",
            "wallet.estimated" to "预估收益",
            "wallet.received" to "其中已收货 {amount}",
            "wallet.earliest_label" to "最早预计",
            "wallet.earliest" to "{month}结算",
            "wallet.overdue" to "入账核对中",
            // APP-09v G-W07：WalletStates ④ 右列标签「结算进度」（设计稿措辞，08 只规定附「入账核对中」）
            "wallet.settle_progress" to "结算进度",
            "wallet.paused" to "其中 {amount} 暂缓入账",
            // APP-09v G-W06：主卡下警告条首行（WalletNegative-v2），次行取 error.30302
            "wallet.negative" to "待抵扣 {amount}",
            "wallet.estimate_note" to "按联盟最新预估计算，以联盟结算金额为准",
            "wallet.draft.risk_paused" to "提现已暂停",
            "wallet.draft.risk_paused_reason" to "提现已暂停：{reason}",
            "wallet.risk_hint" to "浏览、购物照常。点这里查看说明或申诉",
            "wallet.entry.orders" to "订单",
            "wallet.entry.ledger" to "余额流水",
            "wallet.entry.earnings" to "收益看板",
            // 代拟：整页加载失败与网络错误（设计稿没有画钱包的错误态）
            "wallet.load_failed" to "加载失败",
            "wallet.retry" to "重试",
            // 代拟（APP-09 共性第 51 条）：被拦下的前置引导的手动入口
            "wallet.guide.bind_phone" to "去绑定手机号",
            "wallet.guide.login" to "去登录",
            "wallet.loading" to "加载中",
            "wallet.network_error" to "网络不可用，请检查网络后重试",
            // 代拟：保留旧摘要但不是网络原因的读取失败（5xxxx、42901、坏数据）标更新时间（03 §4.2，APP-09 共性第 38 条）
            "wallet.updated_at" to "更新于 {time}",
            // ---- 问号说明（BR-TEXT-14 表 C wallet.help.*，代理起草的默认；按钮与读屏名取设计稿 WalletNote* / WithdrawNoteNet）
            "wallet.help.estimated" to "还没结算的返利和推广收益的估计值，按联盟最新预估计算，以联盟结算金额为准，结算后计入可提现余额",
            "wallet.help.pending_paused" to "部分订单暂时不能结算，处理完成后继续按联盟结算入账，如有疑问请联系客服",
            "wallet.help.frozen" to "已申请提现、正在审核或打款的金额，这部分暂时不能再次提现",
            "wallet.help.fee_tax" to "提现时扣除的手续费与代扣的个人所得税，每次的金额以提现页显示为准",
            "wallet.help.estimated_label" to "预估收益说明",
            "wallet.help.pending_paused_label" to "暂缓入账说明",
            "wallet.help.frozen_label" to "冻结中说明",
            "wallet.help.fee_tax_label" to "预计到账说明",
            "wallet.help.estimated_title" to "预估收益",
            "wallet.help.pending_paused_title" to "暂缓入账",
            "wallet.help.frozen_title" to "冻结中",
            "wallet.help.fee_tax_title" to "预计到账",
            "wallet.help.ok" to "知道了",
            "wallet.help.close" to "关闭",
            // ---- 金额隐藏（BR-TEXT-10 细则「金额隐藏」；与 APP-08 OrderDraftTexts 同名同文）
            "amount_mask.mask" to "****",
            "amount_mask.hidden_spoken" to "金额已隐藏",
            // 脱敏号里的「*」读作「星号」（adaptation-a11y-spec §9；与 APP-08 order_list.star_spoken 同文）
            "wallet.star_spoken" to "星号",
            // ---- 提现页（设计稿 Withdraw*）
            "withdraw.title" to "提现申请",
            "withdraw.amount_label" to "提现金额",
            "withdraw.realname_placeholder" to "完成实名后可输入",
            "withdraw.payout_label" to "收款账号",
            "withdraw.irrevocable" to "提交后不能自行撤销，如需撤销请联系客服",
            "withdraw.submit" to "提交",
            "withdraw.submitting" to "提交中…",
            // BR-TEXT-07 原文（Config texts.withdraw.sla_text 没下发时用）
            "withdraw.sla_default" to "人工审核，工作日 24 小时内处理，节假日顺延",
            "withdraw.sla_text_auto" to "预计几分钟内到账",
            "withdraw.sla_text" to "{text}",
            "withdraw.guide.realname" to "去实名认证",
            // 代拟（G28）：设计稿只画了未实名的引导态
            "withdraw.guide.payout" to "去绑定收款账号",
            "withdraw.guide.labor" to "去签署劳务协议",
            "withdraw.guide.help" to "提现规则",
            // TODO(N-32) APP-09v-c G-W15 / G-W27：30309 引导框按钮，措辞取自设计稿 WithdrawPageStates ⑧（契约无键）
            "withdraw.guide.rules" to "查看提现规则",
            // BR-TEXT-14 表 C（2026-10-05，措辞取自设计稿 WithdrawBlocked）
            "withdraw.risk_blocked.desc" to "这笔提现没有提交，金额没有冻结，可提现余额不变，已填的金额保留在本页。如有疑问，可以联系客服申诉，客服核实后会帮你登记。",
            "withdraw.risk_blocked.ok" to "知道了",
            "cs.appeal_entry" to "联系客服申诉",
            // TODO(N-32) 设计稿 A11yAnnounce 标「待商量」
            "withdraw.submitted_spoken" to "提现申请已提交，审核中",
            // 代拟（FU-AND-0718 R1-01）：成功或在途表单恢复后的中性提示，不推断最终结局。
            "withdraw.submission_locked" to "本页已有提现操作，请到提现记录查看结果",
            // 代拟（FU-AND-0719 F-AND-42；FU-AND-0720 R1-04 补登记）：成功后等待进入记录页。
            "withdraw.submitted_opening_records" to "已提交，正在打开提现记录",
            // 代拟（FU-AND-0720 F-AND-45 R1-01）：换代后的上一张表单迟到成功，一次性轻提示。
            "withdraw.previous_submitted" to "上一笔提现已提交，可在提现记录查看",
            "withdraw.spoken.quick" to "提现金额 {amount}，预计到账 {net}",
            "withdraw.spoken.amount" to "提现金额 {amount}",
            "withdraw.load_failed" to "加载失败",
            // ---- 待确认（契约 pending_confirm.* 以外、设计稿 WithdrawPending* / PayoutAccountPending* 上的几句）
            "pending_confirm.withdraw.records" to "查看提现记录",
            "pending_confirm.withdraw.note" to "结果确认前不能发起新的提现",
            "pending_confirm.withdraw.amount_label" to "上一笔金额",
            "pending_confirm.payout_label" to "收款账号",
            "pending_confirm.payout_account.account_label" to "提交的收款账号",
            "pending_confirm.payout_account.payee_label" to "收款人",
            "pending_confirm.payout_account.time_label" to "提交时间",
            "pending_confirm.payout_account.note" to "结果确认前不能再次更换收款账号",
            "pending_confirm.payout_account.abandon_note" to "如果上一次提交已经处理完，收款账号会显示为提交的新账号；放弃不会把它改回原账号",
            // ---- 二次验证面板（设计稿 WithdrawStepUp；G16 不显示号码）
            "step_up.title" to "为保障资金安全，请先完成短信验证",
            // 代拟（G16）：响应不返回脱敏号码
            "step_up.sent" to "验证码已发送至你绑定的手机号",
            "step_up.code_label" to "验证码",
            "step_up.resend_in" to "{sec} 秒后重新获取",
            "step_up.resend" to "重新获取",
            // 半屏格下一行倒计时（APP-09v-d W67 / G-W19）：与登录验证码页同键同句（LoginDraftTexts「login.code.countdown」），契约补键后字典优先
            "login.code.countdown" to "{n} 秒后可重新获取",
            "step_up.submit" to "验证并提交",
            "step_up.close" to "关闭",
            "step_up.code_spoken" to "验证码，已输入 {n} 位，共 6 位",
            // 代拟（Android 空白 A01）：本机取不到当前账号的手机号时不发码
            "step_up.no_phone" to "暂时无法发送验证码，请稍后再试",
            "step_up.sending" to "正在发送验证码…",
            // ---- 提现记录（设计稿 WithdrawRecords*；BR-TEXT-06 / BR-WDR-25，G08）
            "withdraw_records.title" to "提现记录",
            "withdraw_records.month" to "{year} 年 {month} 月",
            "withdraw_records.item" to "提现 {amount}",
            "withdraw_records.channel_line" to "{date} · {account}",
            "withdraw_records.expand" to "展开详情",
            "withdraw_records.collapse" to "收起详情",
            "withdraw_records.end" to "没有更多记录了",
            "withdraw_records.empty.title" to "还没有提现记录",
            "withdraw_records.empty.desc" to "申请提现后可以在这里查看进度",
            "withdraw_records.load_more" to "加载更多",
            "withdraw_detail.amount" to "申请金额",
            "withdraw_detail.fee" to "手续费",
            "withdraw_detail.tax" to "代扣个税",
            "withdraw_detail.net_label.expected" to "预计到账",
            "withdraw_detail.net_label.actual" to "实际到账",
            "withdraw_detail.payout" to "收款账号",
            "withdraw_detail.created" to "申请时间",
            "withdraw_detail.cs" to "联系客服",
            "withdraw_detail.doubt" to "对结果有疑问可以联系客服",
            "withdraw_detail.change_account" to "修改收款账号",
            // 代拟：复制失败（APP-07 统一写入口记不下自写 hash 时）
            "withdraw_detail.copy_failed" to "复制失败，请重试",
            // BR-TEXT-06 状态标题（未知编码「处理中」）
            "withdrawal_status.PENDING_REVIEW.title" to "审核中",
            "withdrawal_status.APPROVED.title" to "审核中",
            "withdrawal_status.PAYING.title" to "打款中",
            "withdrawal_status.PAID_API.title" to "已到账",
            "withdrawal_status.PAID_MANUAL.title" to "已到账",
            "withdrawal_status.REJECTED.title" to "未通过",
            "withdrawal_status.FAILED.title" to "打款未成功",
            "withdrawal_status.UNKNOWN.title" to "处理中",
            // APP-10 G11（TODO(N-32 / N-36)）：Agent 收益卡的 latest_withdrawal.title_key 按 BR-TEXT-12 命名 withdrawal_status.<CODE>.label；
            // 只对上面已有的 CODE 补 .label（与同表 .title 同一文字，不新造标题），收益卡只做精确键查找，查不到整行不显示
            "withdrawal_status.PENDING_REVIEW.label" to "审核中",
            "withdrawal_status.APPROVED.label" to "审核中",
            "withdrawal_status.PAYING.label" to "打款中",
            "withdrawal_status.PAID_API.label" to "已到账",
            "withdrawal_status.PAID_MANUAL.label" to "已到账",
            "withdrawal_status.REJECTED.label" to "未通过",
            "withdrawal_status.FAILED.label" to "打款未成功",
            "withdrawal_status.UNKNOWN.label" to "处理中",
            // G08 副文案草稿（金额由 CouliFormat 渲染，模板不加 ¥）
            "withdrawal.draft.frozen" to "{amount} 已冻结",
            "withdrawal.draft.processing" to "处理中，结果会通知你",
            "withdrawal.draft.paid" to "已转入{payout_channel_name} {masked_account}，到账 {net}",
            "withdrawal.draft.tax" to "已代扣个税 {tax}",
            "withdrawal.draft.returned" to "{amount} 已退回可提现余额",
            "withdrawal.draft.returned.change_account" to "{amount} 已退回可提现余额，可修改收款账号后重新提现",
            "withdrawal.draft.channel" to "{payout_channel_name} {masked_account}",
            "withdrawal.draft.join" to "；",
            // BR-TEXT-08 驳回原因（编码以 BR-WDR-10 为准）
            "withdraw_reject_reason.RISK_SUSPECT" to "账户存在异常，需要核实，请联系客服",
            "withdraw_reject_reason.ORDER_ABNORMAL" to "部分订单状态待核实，请稍后重新申请",
            "withdraw_reject_reason.PAYEE_INFO_INVALID" to "收款信息有误，请核对后重新申请",
            "withdraw_reject_reason.USER_REQUEST" to "已按你的要求取消本次提现，金额已退回余额",
            "withdraw_reject_reason.OTHER" to "其他原因，请联系客服",
            "withdraw_reject_reason.NEGATIVE_BALANCE" to "有订单被扣回，冻结金额已用于抵扣",
            // BR-TEXT-08 失败原因（非微信，支付宝错误码待沙箱核实）
            "withdraw_fail_reason.PAYEE_NOT_EXIST" to "收款支付宝账号不存在",
            "withdraw_fail_reason.PAYEE_USER_INFO_ERROR" to "收款账号姓名与实名信息不一致",
            "withdraw_fail_reason.PAYEE_ACCOUNT_STATUS_ERROR" to "收款支付宝账号状态异常",
            // ---- 实名认证（设计稿 RealName / RealNameConsent；G01）
            "realname.title" to "实名认证",
            "realname.intro" to "提现前需要完成实名认证",
            "realname.intro_desc" to "只支持 18 位居民身份证，用姓名和身份证号核验，不需要刷脸",
            "realname.name" to "姓名",
            "realname.name_placeholder" to "请输入身份证上的姓名",
            "realname.id_no" to "身份证号",
            "realname.id_no_placeholder" to "请输入 18 位身份证号",
            "realname.note_fixed" to "实名信息提交后不能自行修改，如需更正请联系客服",
            "realname.note_age" to "未满 14 周岁暂不能实名认证；未满 18 周岁时，每月提现金额有上限",
            "realname.submit" to "提交认证",
            // 代拟（G01）：Release 构建里实名提交接口还没有契约
            "realname.unavailable" to "实名认证暂未开放，请稍后再试",
            "realname.consent.title" to "实名认证需要你单独同意",
            "realname.consent.item1" to "会收集你的姓名和身份证号，用于提现前核验身份",
            "realname.consent.item2" to "核验时，姓名和身份证号会提交给实名核验服务方比对",
            "realname.consent.item3" to "核验没有通过时，不保存你的姓名和身份证号",
            "realname.consent.body_title" to "同意书正文（待法务提供）",
            "realname.consent.body" to "完整的个人信息处理说明由法务定稿后放在这里",
            "realname.consent.check" to "我已阅读并同意以上说明",
            "realname.consent.ok" to "同意并继续",
            "realname.consent.cancel" to "暂不认证",
            // 代拟：实名完成后的轻提示
            "realname.done" to "实名认证已完成",
            // ---- 劳务协议（设计稿 LaborAgreement；G02）
            "labor.title" to "劳务协议签署",
            "labor.intro" to "首次提现前需要签署劳务协议。签一次，之后的提现都有效；协议更新需要重新签署时会再提示你",
            "labor.name" to "劳务协议",
            "labor.version" to "版本 {version}",
            "labor.fulltext_label" to "协议全文",
            // 代拟（G02）：Release 构建里劳务协议接口还没有契约
            "labor.unavailable" to "协议正文暂未提供",
            "labor.signed_note" to "签署后可在「我的 → 设置 → 协议」查看已签版本",
            "labor.check" to "我已阅读并同意《劳务协议》",
            "labor.sign" to "同意并签署",
            // 代拟：签署完成后的轻提示
            "labor.done" to "劳务协议已签署",
            // ---- 收款账号（设计稿 PayoutAccount*；G12–G15）
            "payout.title" to "收款账号",
            "payout.intro" to "提现会转入这里。每人同一时间只有 1 个收款账号",
            "payout.method" to "收款方式",
            "payout.alipay" to "支付宝",
            "payout.alipay_desc" to "填写支付宝登录用的手机号或邮箱",
            "payout.bank" to "本人借记卡",
            "payout.bank_desc" to "只支持本人名下的借记卡，不支持信用卡",
            "payout.alipay_account" to "支付宝账号",
            "payout.alipay_placeholder" to "手机号或邮箱",
            "payout.card_no" to "银行卡号",
            // 代拟（G12）：开户行由用户填写，设计稿是「按卡号自动识别」
            "payout.card_no_placeholder" to "请输入银行卡号",
            "payout.bank_name" to "开户行",
            "payout.bank_name_placeholder" to "请输入开户行",
            "payout.payee" to "收款人姓名",
            "payout.payee_placeholder" to "请输入收款人姓名",
            "payout.payee_note" to "须与实名认证的姓名一致",
            "payout.step_up_note" to "保存时需要短信验证",
            "payout.bank_note" to "保存时需要短信验证",
            "payout.save" to "保存",
            "payout.saving" to "保存中…",
            "payout.current" to "当前收款账号",
            "payout.channel.alipay" to "支付宝",
            "payout.channel.bank_card" to "银行卡",
            // BR-ID-33 细则「收款账号的脱敏格式」（G15，待确认摘要本机生成）
            "payout.bank_tail" to "{bank} 尾号 {tail}",
            "payout.payee_label" to "收款人",
            "payout.remaining_label" to "本月还能变更",
            "payout.remaining" to "{n} 次",
            "payout.change" to "更换收款账号",
            "payout.bound_note" to "收款账号不能解绑，只能更换。更换需要短信验证，每月能变更的次数有限",
            // 设计稿 PayoutAccountBlocked
            "payout.blocked.desc" to "收款账号没有保存。如有疑问，可以联系客服申诉，客服核实后会帮你登记。",
            // 代拟：保存成功的轻提示
            "payout.saved" to "收款账号已保存",
            "payout.load_failed" to "加载失败",
        )

    /** 草稿的 fallback（变量缺失时；BR-TEXT-14 缺变量回退）。 */
    private val DRAFT_FALLBACKS: Map<String, String> =
        mapOf(
            "wallet.draft.risk_paused_reason" to "提现已暂停",
            "withdraw.sla_text" to "人工审核，工作日 24 小时内处理，节假日顺延",
        )

    private val PLACEHOLDER = Regex("\\{[a-z0-9_]+\\}")

    private fun raw(key: String): String? = DefaultTexts.text(key) ?: DRAFTS[key]

    private fun fallback(key: String): String? = DefaultTexts.fallback(key) ?: DRAFT_FALLBACKS[key]

    override fun has(key: String): Boolean = raw(key) != null

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
}

/** 一行带变量的文案（键 + 变量；金额变量是整数分，渲染时才交给 [CouliFormat]）。 */
data class TextLine(
    val key: String,
    val amounts: Map<String, Long> = emptyMap(),
    val strings: Map<String, String> = emptyMap(),
) {
    /** 渲染：金额按 BR-TEXT-10（[CouliFormat.money]）。 */
    fun render(texts: WalletTexts): String = texts.format(key, strings + amounts.mapValues { CouliFormat.money(it.value) })
}
