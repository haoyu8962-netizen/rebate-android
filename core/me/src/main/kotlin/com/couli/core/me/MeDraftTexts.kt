package com.couli.core.me

import com.couli.generated.DefaultTexts

/** 「我的」类页面取文案的接口（BR-TEXT-12：页面不写中文，只取键）。生产由 App 组合（契约字典 → 本表 → 已有草稿表）。 */
interface MeTexts {
    /** 缺键为空串。 */
    fun text(key: String): String

    /** 填 {变量}；仍有没填上的变量时用契约 fallback，没有 fallback 时去掉未填的占位。 */
    fun format(
        key: String,
        vars: Map<String, String>,
    ): String
}

/**
 * 「我的」、设置、隐私中心、关于、封禁 / 冻结说明、申诉、通知设置、修改昵称与客服半屏里契约 texts.default.json 还没有的文案键（rule-gaps G08、G35；
 * 照 N-26、N-30、N-31、N-32 的集中草稿表做法）。原文逐字取 规划/08 BR-TEXT-14 表 C（invite.copy_code.done、cs.copy_link.done）、BR-TEXT-23 细则表
 * （risk_reason.*）或设计稿（Me、Settings*、NotifySettings*、PrivacyCenter*、About、RiskNotice*、Appeal*、Cs* 画板）；规划与设计稿都没有的标
 * 「代拟」。三端同名（与鸿蒙 MeDraftTexts 逐键一致）。已在契约字典（privacy.*、perm.*、settings.clear_cache.*、about.copy_diagnostics*、
 * cs.wechat_unavailable / .copy_link / .go_help、amount_mask.*、error.*）与已有草稿表（App 的 LoginDraftTexts：settings.logout.*、cs.appeal_entry；
 * ClipboardDraftTexts：settings.clipboard_auto.*）里的键不在这里重复建。
 *
 * 整张表都是「待定稿，不得提审」：TODO(N-33) 契约补键后改为字典优先、本表兜底；提审前（QA-07）必须替换。
 * 代拟：me.load_failed、me.network_error、me.copy_failed、settings.phone.bound / .unbound（G07）、nickname.empty、nickname.too_long、
 * notify.switch_failed、
 * privacy_center.withdraw_failed、privacy_center.personalization_failed、risk.refresh_failed（Android 追加）、
 * risk.normal.*（G09）、risk.frozen.note_no_until、
 * risk.frozen_upheld.note、appeal.submitted、appeal.next.logout（去掉了 G30 不显示的注销）、cs.help_only.desc、me.switch.*、me.retry、me.close、
 * me.back、appeal.lifted.title.history、appeal.lifted.desc.history、appeal.note.neutral、appeal.state_appealing。设计稿自拟的：nickname.saved
 * （flow-map §23.1.7）、settings.reset_tip.done（§23.2.7）。
 */
object MeDraftTexts : MeTexts {
    const val NETWORK_KEY = "me.network_error"

    /** TODO(N-33) 待定稿，不得提审。键 → 草稿文字。 */
    val DRAFTS: Map<String, String> =
        mapOf(
            "me.title" to "我的",
            "me.guest.title" to "还没有登录",
            "me.guest.desc" to "登录后可以查看返利、提现和邀请好友",
            "me.guest.login" to "登录 / 注册",
            "me.invite_code" to "我的邀请码",
            "me.copy" to "复制",
            "me.inviter_bound" to "邀请人：已绑定",
            "me.inviter_unbound" to "邀请人：未绑定",
            "me.withdrawable" to "可提现余额",
            "me.withdraw" to "提现",
            "me.estimated" to "预估收益",
            "me.group.orders_wallet" to "订单与钱包",
            "me.group.promo" to "推广与邀请",
            "me.group.more" to "更多服务",
            "me.grid.orders" to "我的订单",
            "me.grid.wallet" to "钱包",
            "me.grid.ledger" to "余额流水",
            "me.grid.find_order" to "找回订单",
            "me.grid.invite" to "邀请好友",
            "me.grid.earnings" to "收益看板",
            "me.grid.help" to "帮助中心",
            "me.grid.cs" to "联系客服",
            "me.grid.auth" to "授权管理",
            "me.grid.settings" to "设置",
            "me.bell" to "消息中心",
            "me.guide_bar.close" to "关闭提示",
            "me.amount_hidden" to "金额已隐藏",
            // APP-12v G-M13：眼睛切换为显示后的播报（草稿，待 content 登记）
            "me.amount_shown" to "金额已显示",
            "invite.copy_code.done" to "已复制邀请码",
            "me.copy_failed" to "复制失败，请重试",
            "me.load_failed" to "资料没有加载出来",
            "me.network_error" to "网络不可用，请检查网络后重试",
            "me.retry" to "重试",
            "me.close" to "关闭",
            "me.back" to "返回",
            "me.switch.role" to "开关",
            "me.switch.on" to "已打开",
            "me.switch.off" to "已关闭",
            "settings.title" to "设置",
            "settings.group.account" to "账号与安全",
            "settings.group.privacy" to "隐私",
            "settings.group.general" to "通用",
            "settings.nickname" to "昵称",
            "settings.phone" to "手机号",
            "settings.phone.bound" to "已绑定",
            "settings.phone.unbound" to "未绑定",
            "settings.privacy_center" to "隐私中心",
            "settings.agreement" to "用户协议与隐私政策",
            "settings.agreement.user" to "用户协议",
            "settings.agreement.privacy" to "隐私政策",
            "settings.notify" to "通知设置",
            "settings.reset_tip" to "重新显示下单须知",
            "settings.reset_tip.done" to "已重新显示下单须知",
            "settings.help" to "帮助中心",
            "settings.about" to "关于凑狸",
            "settings.guest_footer" to "登录后可以管理账号、授权和通知",
            "settings.go_login" to "去登录",
            "nickname.title" to "修改昵称",
            "nickname.label" to "昵称",
            "nickname.counter" to "{n}/16",
            "nickname.hint" to "1–16 个字，保存前会做内容检查；每月最多修改 3 次。头像暂不支持修改",
            "nickname.save" to "保存",
            "nickname.clear" to "清除昵称",
            "nickname.saved" to "昵称已修改",
            "nickname.empty" to "请输入昵称",
            "nickname.too_long" to "昵称最多 16 个字",
            "notify.title" to "通知设置",
            "notify.service" to "服务类推送",
            "notify.service_desc" to "跟单成功、返利结算、提现结果等消息。关闭后不再推送，消息中心里照常可以看到",
            "notify.switch_failed" to "设置没有保存，请稍后再试",
            "privacy_center.title" to "隐私中心",
            "privacy_center.collect_list" to "个人信息收集清单",
            "privacy_center.share_list" to "第三方共享清单",
            "privacy_center.privacy_policy" to "隐私政策",
            "privacy_center.agreement" to "用户协议",
            "privacy_center.personalization" to "个性化推荐",
            "privacy_center.personalization_desc" to "关闭后，首页推荐不再参考你的浏览、搜索和点击记录",
            "privacy_center.withdraw_title" to "撤回同意",
            "privacy_center.withdraw_desc" to "撤回后本机会退出登录并进入基本模式，只能浏览。要删除账号请使用账号注销",
            "privacy_center.withdraw" to "撤回同意",
            "privacy_center.withdraw_failed" to "撤回没有生效，请稍后再试",
            "privacy_center.basic_title" to "开启完整功能",
            "privacy_center.basic_desc" to "同意隐私政策后，可以登录、领券购买、查看返利",
            "about.title" to "关于凑狸",
            "about.app_name" to "凑狸",
            "about.version" to "版本 {version}（{build}）",
            "about.filing_title" to "生成式 AI 服务公示",
            "about.copy_desc" to "只复制版本、机型等排查信息，不含账号和手机号，可粘贴给客服",
            "risk_reason.malicious_rights" to "异常售后维权",
            "risk_reason.fraud_invite" to "异常邀请行为",
            "risk_reason.abnormal_trade" to "异常交易",
            "risk_reason.account_security" to "账户安全核验",
            "risk_reason.other" to "违反用户协议",
            "risk.title" to "账号状态",
            "risk.banned.title" to "账号已被限制使用",
            "risk.banned.desc" to "限制期间只能查看本页、提交申诉、联系客服、注销账号或退出登录。",
            "risk.reason" to "原因",
            "risk.limit" to "限制期限",
            "risk.limit.long" to "长期，直到解除",
            "risk.withdrawals" to "进行中的提现",
            "risk.withdrawal_row" to "{amount} · {status}",
            "risk.banned.note" to "如对处理结果有疑问，可以提交申诉。限制期间不能使用消息中心，申诉的处理进度和结果、进行中提现的处理结果都在本页查看。",
            "risk.submit" to "提交申诉",
            "risk.contact_cs" to "联系客服",
            "risk.logout" to "退出登录",
            "risk.appeal" to "申诉",
            "risk.appeal.processing" to "处理中",
            "risk.banned_appealing.note" to "申诉处理中，处理期间账号限制保持不变，不能重复提交。结论出来后会在本页显示，也可以在申诉进度页查看。",
            "risk.view_progress" to "查看申诉进度",
            "risk.latest_appeal" to "最近一次申诉",
            "risk.upheld" to "维持原处理结果",
            "risk.banned_upheld.note" to "申诉已处理，维持原处理结果。如有新的情况，可以再次提交申诉。",
            "risk.frozen.title" to "提现已暂停",
            "risk.frozen.desc" to "暂停只影响提现，浏览、购物等其他功能照常使用。",
            "risk.frozen_until" to "预计恢复",
            "risk.frozen.note" to "到期后会自动恢复，恢复时会发站内信通知你。如有疑问，可以提交申诉。",
            "risk.frozen.note_no_until" to "如有疑问，可以提交申诉。",
            "risk.frozen_appealing.note" to "申诉处理中，处理期间提现仍暂停，不能重复提交。结果会通过站内信通知你，也可以在申诉进度页查看。",
            "risk.frozen_upheld.note" to "申诉已处理，维持原处理结果。如有新的情况，可以再次提交申诉。",
            "risk.normal.title" to "账号状态正常",
            "risk.normal.back_home" to "返回首页",
            "appeal.title" to "申诉",
            "appeal.target" to "申诉对象",
            "appeal.type" to "类型",
            "appeal.type.account" to "账户",
            "appeal.current_state" to "当前状态",
            "appeal.reason" to "原因",
            "appeal.frozen_until" to "预计恢复",
            "appeal.content_label" to "申诉说明",
            "appeal.note.banned" to "我们会尽快处理。处理期间账号限制保持不变；限制期间不能使用消息中心，处理进度和结果在「账号状态」页查看。同一时间只能有一条处理中的申诉",
            "appeal.note.frozen" to "我们会尽快处理，结果会通过站内信通知你，也可以在申诉进度页查看。处理期间提现仍暂停。同一时间只能有一条处理中的申诉",
            "appeal.note.neutral" to "我们会尽快处理，结果会通过站内信通知你，也可以在申诉进度页查看。同一时间只能有一条处理中的申诉",
            "appeal.submit" to "提交申诉",
            "appeal.submitted" to "申诉已提交",
            "appeal.progress.title" to "申诉进度",
            "appeal.progress.badge" to "处理中",
            "appeal.progress.heading" to "申诉处理中",
            "appeal.progress.desc.banned" to "我们会尽快处理，结论出来后会在本页和「账号状态」页显示",
            "appeal.progress.desc.frozen" to "我们会尽快处理，结果会通过站内信通知你，也会在本页显示",
            "appeal.timeline.submitted" to "已提交",
            "appeal.timeline.processing" to "处理中",
            "appeal.timeline.processing_desc.banned" to "处理期间账号限制保持不变",
            "appeal.timeline.processing_desc.frozen" to "处理期间提现仍暂停",
            "appeal.timeline.result" to "处理结果",
            "appeal.timeline.result_pending.banned" to "结论出来后在本页显示",
            "appeal.timeline.result_pending.frozen" to "站内信通知，也会在本页显示",
            "appeal.content_section" to "申诉内容",
            "appeal.content" to "说明",
            "appeal.footer" to "处理中的申诉不能重复提交，有疑问可以",
            "appeal.contact_cs" to "联系客服",
            "appeal.lifted.title.banned" to "账号限制已解除",
            "appeal.lifted.title.frozen" to "提现已恢复",
            "appeal.lifted.desc" to "你的申诉已通过，相关限制已解除。",
            "appeal.lifted.title.history" to "申诉已通过",
            "appeal.lifted.desc.history" to "你的申诉已通过。当前状态以下方显示为准。",
            "appeal.conclusion" to "申诉结论",
            "appeal.passed" to "已通过",
            "appeal.closed_at" to "处理时间",
            "appeal.account_state" to "账号状态",
            "appeal.state_normal" to "正常",
            "appeal.state_appealing" to "申诉处理中",
            "appeal.lifted.note.banned" to "购物前需重新完成平台授权。限制期间的通知已放进消息中心，现在可以查看。",
            "appeal.start" to "开始使用",
            "appeal.view_messages" to "查看消息",
            "appeal.withdraw" to "提现",
            "appeal.withdraw_ok" to "可以正常申请",
            "appeal.lifted.note.frozen" to "结果也已通过站内信通知你。",
            "appeal.go_wallet" to "去钱包",
            "appeal.upheld.badge" to "已处理",
            "appeal.upheld.title" to "维持原处理结果",
            "appeal.upheld.desc" to "你的申诉已处理，维持原处理结果。如有疑问请联系客服。",
            "appeal.upheld.result_line" to "维持原处理结果 · {time}",
            "appeal.current_account_state" to "当前账号状态",
            "appeal.current_frozen_state" to "当前状态",
            "appeal.state" to "状态",
            "appeal.next" to "接下来可以",
            "appeal.next.again" to "有新的情况或材料，可以再次提交申诉；同一时间只能有一条处理中的申诉。",
            "appeal.next.cs" to "对结论有疑问，可以联系客服。",
            "appeal.next.logout" to "也可以退出登录。",
            "appeal.next.frozen" to "浏览、购物等其他功能照常使用；到期后提现会自动恢复，恢复时会发站内信通知你。",
            "appeal.again" to "再次申诉",
            "cs.title" to "联系客服",
            "cs.handoff.desc" to "客服在微信里接待。点下方按钮会打开微信，进入凑狸企业微信客服的会话",
            "cs.safety" to "客服不会向你索要登录验证码和密码",
            "cs.open_wechat" to "打开微信",
            "cs.handoff.go_help" to "先看看帮助中心",
            "cs.open_failed.title" to "暂时打不开客服",
            "cs.open_failed.desc" to "微信没有成功打开。可以再试一次，或复制客服链接后在微信里打开",
            "cs.retry" to "重试",
            "cs.copy_link.done" to "已复制客服链接",
            "cs.help_only.desc" to "客服暂时打不开，可以先看看帮助中心",
            "我的订单" to "me.grid.orders",
            "钱包" to "me.grid.wallet",
            "余额流水" to "me.grid.ledger",
            "找回订单" to "me.grid.find_order",
            "邀请好友" to "me.grid.invite",
            "收益看板" to "me.grid.earnings",
            "帮助中心" to "me.grid.help",
            "联系客服" to "me.grid.cs",
            "授权管理" to "me.grid.auth",
            "设置" to "me.grid.settings", // Android 追加：BR-TEXT-14 正文的兜底句（统一错误消息函数：字典 → 服务端 msg → 本句，G13）；加载中的读屏名（代拟）
            "me.unfinished" to "操作未完成，请稍后再试",
            "me.loading" to "加载中",
            // Android 追加（代拟，R2-04）：RiskNotice 已有内容时刷新失败的标示（后接统一错误文案）
            "risk.refresh_failed" to "状态没有刷新，下面是上次读到的结果。",
            // Android 追加（代拟）：个性化开关同步失败的一次性提示（brief §2.5：本机仍按用户选择保存，不回滚）
            "privacy_center.personalization_failed" to "设置已在本机生效，同步到服务器没有成功，可以稍后再切换一次",
        )

    /** 宫格名（MeView 的 item，设计稿原文）→ 文案键。 */
    private val GRID_KEYS: Map<String, String> =
        mapOf(
            "我的订单" to "me.grid.orders",
            "钱包" to "me.grid.wallet",
            "余额流水" to "me.grid.ledger",
            "找回订单" to "me.grid.find_order",
            "邀请好友" to "me.grid.invite",
            "收益看板" to "me.grid.earnings",
            "帮助中心" to "me.grid.help",
            "联系客服" to "me.grid.cs",
            "授权管理" to "me.grid.auth",
            "设置" to "me.grid.settings",
        )

    /** 设置行（SettingsItems 的 id，设计稿原文）→ 文案键（剪贴板开关的键在 App 的 ClipboardDraftTexts 原处）。 */
    private val SETTINGS_KEYS: Map<String, String> =
        mapOf(
            SettingsItems.ID_PRIVACY to "settings.privacy_center",
            SettingsItems.ID_AGREEMENT to "settings.agreement",
            SettingsItems.ID_ABOUT to "settings.about",
            SettingsItems.ID_HELP to "settings.help",
            SettingsItems.ID_CLEAR_CACHE to "settings.clear_cache",
            SettingsItems.ID_CLIPBOARD to "settings.clipboard_auto",
            SettingsItems.ID_NICKNAME to "settings.nickname",
            SettingsItems.ID_PHONE to "settings.phone",
            // APP-13a：注销账号行（文案在 AccountDraftTexts，G29）
            SettingsItems.ID_DELETE to "settings.delete_account",
            SettingsItems.ID_AUTH to "me.grid.auth",
            SettingsItems.ID_NOTIFY to "settings.notify",
            SettingsItems.ID_RESET_TIP to "settings.reset_tip",
            SettingsItems.GROUP_ACCOUNT to "settings.group.account",
            SettingsItems.GROUP_PRIVACY to "settings.group.privacy",
            SettingsItems.GROUP_GENERAL to "settings.group.general",
        )

    /** 宫格名 → 文案键；不认识的为 null（不显示该格）。 */
    fun gridKey(item: String): String? = GRID_KEYS[item]

    /** 设置行 / 分组名 → 文案键；不认识的为 null。 */
    fun settingsKey(id: String): String? = SETTINGS_KEYS[id]

    /** 契约字典 → 本表；都没有为 null（App 再查已有草稿表）。 */
    fun textOrNull(key: String): String? = DefaultTexts.text(key) ?: DRAFTS[key]

    override fun text(key: String): String = textOrNull(key) ?: DefaultTexts.fallback(key).orEmpty()

    override fun format(
        key: String,
        vars: Map<String, String>,
    ): String = fill(textOrNull(key).orEmpty(), key, vars)

    /** 填变量；仍有未填占位时用契约 fallback，没有 fallback 时去掉占位并收掉多余空白与括号。 */
    fun fill(
        template: String,
        key: String,
        vars: Map<String, String>,
    ): String {
        var text = template
        vars.forEach { (name, value) -> text = text.replace("{$name}", value) }
        if (!PLACEHOLDER.containsMatchIn(text)) return text
        return DefaultTexts.fallback(key) ?: text.replace(DANGLING, "").trim()
    }

    private val PLACEHOLDER = Regex("\\{[a-z0-9_]+\\}")
    private val DANGLING = Regex("\\s*[（(]?\\{[a-z0-9_]+\\}[）)]?\\s*")
}
