package com.couli.app.login

import com.couli.feature.login.LoginTexts
import com.couli.generated.DefaultTexts

/**
 * 登录页与退出登录里契约 texts.default.json 还没有的文案键（APP-05a rule-gaps TEXT-01，对应 N-26）。照设计稿 Login、LoginAgreeConfirm、
 * LoginCode、LoginCodeBlocked、LoginCodeInvite、LoginInviteBound、LoginInviteFailed、LoginDeletionProcessing、SettingsLogoutConfirm
 * 逐字集中放在这一处；规划 BR-TEXT-14 已有措辞的（login.risk_blocked.desc、cs.appeal_entry、settings.logout.*）照规划原文。
 * 三端同名（iOS / Android / 鸿蒙 LoginDraftTexts）。有键的（error.*、invite.notice_inviter、login.help_entry）一律读 DefaultTexts，
 * 不放这里。
 *
 * TODO(N-26) 整张表都是「待定稿，不得提审」：契约补键后删除本文件、改读 DefaultTexts；提审前（QA-07）必须替换。
 */
internal object LoginDraftTexts : LoginTexts {
    /** TODO(N-26) 待定稿，不得提审。键 → 草稿文字。 */
    val DRAFTS: Map<String, String> =
        mapOf(
            // ---- Login
            "login.brand" to "凑狸",
            "login.close" to "关闭",
            "login.back" to "返回",
            "login.settings" to "设置",
            "login.title" to "手机号登录",
            "login.phone.label" to "手机号",
            "login.phone.prefix" to "+86",
            "login.phone.placeholder" to "请输入手机号",
            "login.phone.clear" to "清除手机号",
            "login.agree.prefix" to "我已阅读并同意",
            "login.agree.agreement" to "《用户协议》",
            "login.agree.and" to "和",
            "login.agree.privacy" to "《隐私政策》",
            "login.agree.suffix" to "，",
            "login.agree.hint" to "未注册的手机号验证后自动创建账号",
            "login.agree.checked" to "已勾选",
            "login.agree.unchecked" to "未勾选",
            "login.get_code" to "获取验证码",
            // 共性第 12、16 条的独立入口（设计稿没有这一项，措辞待定稿）
            "login.code.continue" to "继续输入验证码",
            "login.invite.entry" to "有邀请码？填写邀请码（选填）",
            "login.other_methods" to "其他登录方式",
            // ---- LoginAgreeConfirm（正文拆成前后两段，中间是两个可点的协议链接）
            "login.agree_confirm.title" to "请阅读并同意协议",
            "login.agree_confirm.before" to "登录前请阅读并同意",
            "login.agree_confirm.after" to "。",
            "login.agree_confirm.cancel" to "取消",
            "login.agree_confirm.ok" to "同意并继续",
            // ---- LoginCode / LoginCodeInvite
            "login.code.title" to "输入验证码",
            "login.code.sent_to" to "验证码已发送至 {phone}",
            "login.code.label" to "验证码",
            "login.code.countdown" to "{sec} 秒后可重新获取",
            "login.code.resend" to "重新获取",
            "login.invite.label" to "邀请码（选填）",
            "login.invite.placeholder" to "6 位邀请码",
            "login.submit" to "登录",
            // ---- LoginCodeBlocked（标题用 error.44001；说明句 BR-TEXT-14 表 C 已有措辞）
            "login.risk_blocked.desc" to
                "账号没有创建，填写的手机号保留在本页，验证码需要重新获取。如有疑问，可以联系客服申诉，客服核实后会帮你登记。",
            "login.risk_blocked.ok" to "知道了",
            // ---- APP-05b：第三方登录按钮（Login 画板「微信」，读屏名称「微信登录」）、{provider_name} 的取值（三端同名）
            "login.wechat" to "微信登录",
            "login.wechat.short" to "微信",
            "login.apple" to "通过 Apple 登录",
            "login.apple.short" to "Apple",
            "login.provider.wechat" to "微信",
            "login.provider.apple" to "Apple",
            "login.provider.huawei" to "华为账号",
            // ---- APP-05b：LoginBindPhoneGuide 按钮（标题、正文用契约 invite.bind_phone_guide.*）
            "invite.bind_phone_guide.skip" to "暂不",
            "invite.bind_phone_guide.go" to "去绑定",
            // ---- APP-05b：BindPhone / BindPhoneMerge（并号正文用契约 error.30411.mergeable）
            "bind_phone.code.placeholder" to "6 位验证码",
            "bind_phone.countdown" to "{sec} 秒后重新获取",
            "bind_phone.submit" to "绑定",
            "bind_phone.done" to "手机号已绑定",
            // 共性第 11 条：倒计时走完、可以重新获取时的读屏播报（设计稿没有这句，措辞待定稿）
            "bind_phone.can_resend" to "可以重新获取验证码",
            "login.code.can_resend" to "可以重新获取验证码",
            "bind_phone.merge.title" to "并入原账号",
            "bind_phone.merge.into" to "并入到",
            "bind_phone.merge.account" to "手机号账号 {phone}",
            "bind_phone.merge.cancel" to "取消",
            "bind_phone.merge.ok" to "并入",
            // TODO(N-29) MERGE-SUCCESS：锁定契约没有并号接口，确认后给明确的「暂不可用」（不发请求、不并号）
            "bind_phone.merge.unavailable" to "暂时无法并入原账号，请稍后再试，或退出后用手机号登录",
            "cs.appeal_entry" to "联系客服申诉",
            // ---- LoginDeletionProcessing（标题用 error.10007）
            "login.deletion.body" to
                "这个账号正在注销，注销完成前不能登录。注销自提交起 15 个工作日内完成，完成后可以用同一手机号重新注册。如有疑问，可以联系客服。",
            "login.deletion.cs" to "联系客服",
            "login.deletion.ok" to "知道了",
            // ---- LoginInviteBound / LoginInviteFailed（BR-INV-06 细则：42901 另有一句）
            "login.invite_bound" to "登录成功，已绑定邀请人",
            "login.invite_failed.title" to "邀请码未生效",
            "login.invite_failed.body" to "邀请码无效，注册 7 天内可在“我的”补填",
            "login.invite_failed.rate_limited" to "尝试次数过多，请明天再试",
            "login.invite_failed.ok" to "知道了",
            "login.invite_failed.backfill" to "去补填",
            // ---- 设置页·退出登录（BR-TEXT-14 表 C settings.logout.*，契约快照未同步）
            "settings.logout" to "退出登录",
            "settings.logout.confirm" to "确定退出登录？",
            "settings.logout.confirm_ok" to "退出登录",
            "settings.logout.confirm_cancel" to "取消",
        )

    private val PLACEHOLDER = Regex("\\{[a-z0-9_]+\\}")

    /** 先取契约字典，再取草稿；带 {变量} 的契约文案（没有给值）用它的 fallback。缺键返回空串。 */
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
