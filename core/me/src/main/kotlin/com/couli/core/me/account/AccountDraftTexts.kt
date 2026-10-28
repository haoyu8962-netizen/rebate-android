package com.couli.core.me.account

import com.couli.core.me.MeDraftTexts
import com.couli.core.me.MeTexts
import com.couli.core.wallet.WalletDraftTexts
import com.couli.core.wallet.WalletTexts
import com.couli.generated.DefaultTexts

/**
 * 注销账号、换手机号、BindPhone 待确认与二次验证面板 action 变体里契约 texts.default.json 还没有的文案键（APP-13a G29；照 N-11、N-26、N-30、
 * N-31、N-32、N-33 的集中草稿表做法）。原文逐字取设计稿（DeleteAccount*、PhoneChange*、SettingsDeletionWithdrawn 画板）或规划（BR-TEXT-14 表 C
 * phone_change.risk_blocked.desc、BR-TEXT-23 cancelled、BR-ID-08 原句）；两边都没有的标「代拟」。三端同名、同字（与鸿蒙 AccountDraftTexts 逐键一致）。
 * 已有键不重建：契约字典（pending_confirm.*、app_update.*、error.*）、WalletDraftTexts（step_up.*、cs.appeal_entry）、MeDraftTexts（me.*、settings.*、
 * risk.*）。查找顺序：契约字典 → 本表 → WalletDraftTexts → MeDraftTexts → 契约 fallbacks。
 *
 * 整张表都是「待定稿，不得提审」：TODO(N-37) 契约补键后改为字典优先、本表兜底；提审前（QA-07）必须替换。
 * 资金术语（BR-TEXT-12 细则「资金术语键」）：deletion.waiver（勾选句）、deletion.withdraw_hint（申请前先提现的提示）、deletion.withdrawable、
 * deletion.estimated、deletion.negative、deletion.negative.note。
 * 代拟：deletion.cooling.cancel_expired、deletion.cooling.cancel_unknown、deletion.cooling.cancelling、
 * deletion.funds_failed、deletion.load_failed、deletion.bind_phone、deletion.waiver.role / .checked / .unchecked、
 * phone_change.current_hint、phone_change.code_sent、phone_change.done.body_plain、phone_change.scope_denied、
 * step_up.scope_denied、pending_confirm.summary.new_phone、step_up.title.phone_change、step_up.title.account_deletion、
 * settings.delete_account、bind_phone.pending_hint、appeal.next.delete_logout、deletion.retry、phone_change.step_spoken。
 * 设计稿标「自拟 / 待商量」的：phone_change.done.body、deletion.pending_hint、phone_change.pending_hint、pending_confirm.abandon.note.*。
 */
object AccountDraftTexts : WalletTexts, MeTexts {
    /** 资金术语键（BR-TEXT-12 细则）。 */
    val FUND_TERMS: List<String> =
        listOf(
            "deletion.waiver",
            "deletion.withdraw_hint",
            "deletion.withdrawable",
            "deletion.estimated",
            "deletion.negative",
            "deletion.negative.note",
            // APP-13v G-D12：不可去提现时的提示句（资金术语）
            "deletion.withdraw_hint.no_withdraw",
        )

    /** TODO(N-37) 待定稿，不得提审。键 → 草稿文字。 */
    val DRAFTS: Map<String, String> =
        linkedMapOf(
            // ---- 注销说明与条件确认（DeleteAccount、DeleteAccountNegative）
            "deletion.title" to "账号注销",
            "deletion.intro" to "注销完成后无法恢复，请先确认下面的内容",
            "deletion.funds.title" to "账户资金",
            // 资金术语，BR-TEXT-12
            "deletion.withdrawable" to "可提现余额",
            "deletion.estimated" to "预估收益",
            "deletion.negative" to "待抵扣",
            "deletion.negative.note" to "待抵扣是余额为负时、需要由之后的结算入账抵扣的金额，抵扣回正后可以申请注销",
            "deletion.withdraw_hint" to "注销后余额和预估收益都会放弃，建议先提现",
            // APP-13v G-D12：从风控来源 / 受限会话进入（没有【去提现】）时的句子，不再建议先提现
            "deletion.withdraw_hint.no_withdraw" to "注销后余额和预估收益都会放弃",
            "deletion.go_withdraw" to "去提现",
            "deletion.contact_cs" to "联系客服",
            "deletion.notice.title" to "注销须知",
            "deletion.notice.1" to "提交后有 7 天冷静期，冷静期内可以随时撤回",
            "deletion.notice.2" to "冷静期内账号只能查看，不能领券购买、提现和邀请好友",
            "deletion.notice.3" to "冷静期满后进入注销处理，自提交起 15 个工作日内完成",
            "deletion.notice.4" to "注销后手机号、昵称、实名信息、收款账号等会被删除或匿名化，账号无法恢复；法律法规要求保留的交易与涉税记录除外",
            "deletion.notice.5" to "注销后用同一手机号可以重新注册；注销完成后 180 天内，新账号不能领取新人奖励",
            "deletion.notice.6" to "有进行中的提现时不能申请，请等提现完成",
            // 资金术语，BR-TEXT-12
            "deletion.waiver" to "放弃未提现余额和预估收益",
            "deletion.waiver.role" to "复选框",
            "deletion.waiver.checked" to "已选中",
            "deletion.waiver.unchecked" to "未选中",
            "deletion.submit" to "申请注销",
            "deletion.submit_hint" to "勾选后才能申请，提交前需要验证身份",
            "deletion.pending_hint" to "上一次提交的结果确认前，不能再次申请",
            "deletion.pending_card_hint" to "结果确认前不能再次申请注销",
            "deletion.funds_failed" to "账户资金暂时读不出来，读到后才能申请注销",
            "deletion.load_failed" to "注销进度暂时读不出来，请稍后再试",
            "deletion.retry" to "重试",
            // BR-ID-08 原句（G10：本端不支持任何第三方重新授权）
            "deletion.step_up_unsupported" to "请先绑定手机号，或在支持该登录方式的设备上操作",
            "deletion.bind_phone" to "去绑定手机号",
            // ---- 冷静期（DeleteAccountCooling；G15 can_cancel=false 待补稿）
            "deletion.cooling.badge" to "冷静期中",
            "deletion.cooling.title" to "注销申请已提交",
            "deletion.cooling.deadline" to "{time} 前可以撤回注销",
            "deletion.cooling.restore" to "撤回后账号恢复正常使用",
            "deletion.cooling.timeline.apply" to "提交注销申请",
            "deletion.cooling.timeline.cooling" to "冷静期 7 天",
            "deletion.cooling.timeline.cooling_desc" to "至 {time}，期间可以撤回",
            "deletion.cooling.timeline.process" to "注销处理",
            "deletion.cooling.timeline.process_desc" to "自提交起 15 个工作日内完成",
            "deletion.cooling.readonly" to "冷静期内账号只能查看，不能领券购买、提现和邀请好友",
            "deletion.cooling.cancel" to "撤回注销",
            "deletion.cooling.cancelling" to "提交中…",
            "deletion.cooling.cancel_expired" to "冷静期已结束，不能再撤回",
            "deletion.cooling.cancel_unknown" to "暂时没有收到结果，请稍后再试",
            // BR-TEXT-23 DELETION_PROGRESS cancelled 模板同句（SettingsDeletionWithdrawn 轻提示）
            "deletion.cancelled_toast" to "已撤回注销申请，账号恢复正常使用。",
            // ---- 待确认摘要（DeleteAccountPending*、PhoneChangePending*；G24）
            "pending_confirm.summary.operation" to "提交的操作",
            "pending_confirm.summary.operation.deletion" to "申请注销账号",
            "pending_confirm.summary.waiver" to "已勾选",
            "pending_confirm.summary.submitted_at" to "提交时间",
            "pending_confirm.summary.new_phone" to "提交的新手机号",
            "pending_confirm.abandon.note.deletion" to "如果上一次提交已经处理完，会进入注销冷静期；放弃不会撤回注销，冷静期内可以在注销页撤回",
            "pending_confirm.abandon.note.phone_change" to "如果上一次提交已经处理完，手机号会显示为新手机号；放弃不会把它改回原手机号",
            // ---- 换手机号（PhoneChange、PhoneChangeNew、PhoneChangeDone、PhoneChangeNewBlocked；G03、G22）
            "phone_change.title" to "更换手机号",
            "phone_change.step1" to "验证当前手机号",
            "phone_change.step2" to "验证新手机号",
            // 代拟（Android 追加）：两步指示的读屏句
            "phone_change.step_spoken" to "第 {n} 步，共 2 步",
            "phone_change.current_hint" to "验证码将发送至你绑定的手机号",
            "phone_change.code_sent" to "验证码已发送至你绑定的手机号",
            "phone_change.intro" to "先验证当前手机号，再验证新手机号。更换后，用手机号登录时请使用新手机号",
            "phone_change.code_label" to "验证码",
            "phone_change.get_code" to "获取验证码",
            "phone_change.resend_in" to "{n} 秒后重新获取",
            "phone_change.next" to "下一步",
            "phone_change.cant_use_old" to "原手机号不能用了？联系客服",
            "phone_change.new_phone" to "新手机号",
            "phone_change.prefix" to "+86",
            "phone_change.new_hint" to "新手机号不能是其他凑狸账号正在使用的号码",
            "phone_change.submit_hint" to "提交后，当前手机号会换成新手机号",
            "phone_change.submit" to "提交",
            "phone_change.done.title" to "手机号已更换",
            "phone_change.done.body" to "当前手机号 {phone}。以后用手机号登录时，请使用新手机号",
            "phone_change.done.body_plain" to "以后用手机号登录时，请使用新手机号",
            "phone_change.done.ok" to "完成",
            "phone_change.pending_hint" to "结果确认前不能再次更换手机号",
            "phone_change.scope_denied" to "当前状态不能更换手机号",
            // BR-TEXT-14 表 C 原文（G09）
            "phone_change.risk_blocked.desc" to
                "手机号没有更换，当前手机号不变，填写的新手机号保留在本页，验证码需要重新获取。如有疑问，可以联系客服申诉，客服核实后会帮你登记。",
            "phone_change.risk_blocked.ok" to "知道了",
            // ---- 二次验证面板标题的 action 变体（WithdrawStepUp 版式；G29 代拟）
            "step_up.title.phone_change" to "为保障账号安全，请先验证当前手机号",
            "step_up.title.account_deletion" to "申请注销前，请先完成短信验证",
            "step_up.scope_denied" to "当前状态只能进行注销相关的验证",
            // ---- 入口
            "settings.delete_account" to "注销账号",
            "bind_phone.pending_hint" to "结果确认前不能再次绑定手机号",
            "appeal.next.delete_logout" to "不再使用，可以注销账号；也可以退出登录。",
        )

    private val PLACEHOLDER = Regex("\\{[a-z0-9_]+\\}")
    private val DANGLING = Regex("\\s*[（(]?\\{[a-z0-9_]+\\}[）)]?\\s*")

    /** 契约字典 → 本表 → WalletDraftTexts → MeDraftTexts；都没有为 null。 */
    fun textOrNull(key: String): String? = DefaultTexts.text(key) ?: DRAFTS[key] ?: WalletDraftTexts.DRAFTS[key] ?: MeDraftTexts.DRAFTS[key]

    override fun has(key: String): Boolean = textOrNull(key) != null

    /** 不带变量的文案；正文带占位时用契约 fallback（缺变量回退）；都没有为空串。 */
    override fun text(key: String): String {
        val raw = textOrNull(key) ?: return DefaultTexts.fallback(key).orEmpty()
        return if (PLACEHOLDER.containsMatchIn(raw)) DefaultTexts.fallback(key) ?: raw.replace(DANGLING, "").trim() else raw
    }

    /** 填 {变量}（空值不填）；仍有没填上的占位时用契约 fallback，没有 fallback 时去掉占位并收掉多余空白与括号。 */
    @Suppress("ReturnCount") // 缺键、填完、回落三个出口
    override fun format(
        key: String,
        vars: Map<String, String>,
    ): String {
        var text = textOrNull(key) ?: return text(key)
        vars.forEach { (name, value) -> if (value.isNotEmpty()) text = text.replace("{$name}", value) }
        if (!PLACEHOLDER.containsMatchIn(text)) return text
        return DefaultTexts.fallback(key) ?: text.replace(DANGLING, "").trim()
    }
}
