package com.couli.app.login

import com.couli.app.clipboard.ClipboardDraftTexts
import com.couli.feature.login.LoginTexts
import com.couli.generated.DefaultTexts

/**
 * 登录页与退出登录里契约 texts.default.json 还没有的文案键（APP-05a rule-gaps TEXT-01，对应 N-26）。照设计稿 Login、LoginAgreeConfirm、
 * LoginCode、LoginCodeBlocked、LoginCodeInvite、LoginInviteBound、LoginInviteFailed、LoginDeletionProcessing、SettingsLogoutConfirm
 * 逐字集中放在这一处；规划 BR-TEXT-14 已有措辞的（login.risk_blocked.desc、cs.appeal_entry、settings.logout.*）照规划原文。
 * 三端同名（iOS / Android / 鸿蒙 LoginDraftTexts）。有键的（error.*、login.help_entry）一律读 DefaultTexts，不放这里。
 * APP-17a（登录改版）：协议整句、确认框按动作的键、验证码页求助、重新获取提示、上次登录标签按规划 BR-TEXT-14 表 C 原文；设置、邀请码手填、
 * 【登录】按钮、拆段协议句与旧确认框键随入口删除。
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
            "login.title" to "手机号登录",
            "login.phone.label" to "手机号",
            "login.phone.prefix" to "+86",
            "login.phone.placeholder" to "请输入手机号",
            "login.phone.clear" to "清除手机号",
            // 协议整句（BR-TEXT-14 表 C）；两个链接按下面的原文在整句里定位（AgreementSentence，G16）
            "login.agree.text" to "我已阅读并同意《用户协议》和《隐私政策》，未注册的手机号验证后将自动注册凑狸账号",
            "login.agree.agreement" to "《用户协议》",
            "login.agree.privacy" to "《隐私政策》",
            "login.agree.checked" to "已勾选",
            "login.agree.unchecked" to "未勾选",
            "login.get_code" to "获取验证码",
            // 共性第 12、16 条的独立入口（设计稿没有这一项，措辞待定稿）
            "login.code.continue" to "继续输入验证码",
            "login.other_methods" to "其他登录方式",
            // 「上次登录」标签（BR-TEXT-14 表 C；闸前只进第三方按钮的读屏说明）
            "login.last_method_tag" to "上次登录",
            // ---- LoginAgreeConfirm（BR-TEXT-14 表 C：正文整句、两个协议链接在句中；主按钮按动作，G10）
            "login.agree_confirm.title" to "请阅读并同意以下条款",
            "login.agree_confirm.body" to "《用户协议》《隐私政策》，未注册的号码将自动注册凑狸账号",
            "login.agree_confirm.body_oauth" to "《用户协议》《隐私政策》，首次登录将自动注册凑狸账号",
            "login.agree_confirm.btn_get_code" to "同意并获取验证码",
            "login.agree_confirm.btn_login" to "同意并登录",
            "login.agree_confirm.btn_disagree" to "不同意",
            // ---- LoginCode（满 6 位自动登录，没有【登录】按钮）
            "login.code.title" to "输入验证码",
            "login.code.sent_to" to "验证码已发送至 {phone}",
            "login.code.label" to "验证码",
            "login.code.countdown" to "{n} 秒后可重新获取",
            "login.code.resend" to "重新获取",
            "login.code.resend_needed" to "验证码需要重新获取",
            "login.code.help_entry" to "收不到验证码？",
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
            // APP-13v G-D17：BindPhone-v2 副标题与并号提示条（契约 texts.default.json 缺键，TODO(N-06)；三端同键同字，契约补键后换字典）
            "invite.bind_phone_guide.benefit" to "绑定手机号，方便提现和找回账号。",
            "invite.bind_phone_guide.merge_hint" to "之前用手机号注册过？绑定同一号码，符合条件时可并入原账号。",
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
            // ---- APP-17c：待绑定码小字行（BR-TEXT-14 表 C 原文；10-19 口径不带告知句）。渲染属视觉闸后
            "login.pending_invite" to "已识别好友邀请码 {code}",
            "login.pending_invite.remove" to "移除",
            // ---- 设置页·退出登录（BR-TEXT-14 表 C settings.logout.*，契约快照未同步）
            "settings.logout" to "退出登录",
            "settings.logout.confirm" to "确定退出登录？",
            "settings.logout.confirm_ok" to "退出登录",
            "settings.logout.confirm_cancel" to "取消",
        )

    private val PLACEHOLDER = Regex("\\{[a-z0-9_]+\\}")

    /** 先取契约字典，再取草稿；带 {变量} 的契约文案（没有给值）用它的 fallback。缺键返回空串。 */
    override fun text(key: String): String {
        // 剪贴板的集中草稿表（TODO(N-30)）兜底：设置页里挂的剪贴板开关等外壳页面经这里取字
        val raw = DefaultTexts.text(key) ?: DRAFTS[key] ?: ClipboardDraftTexts.DRAFTS[key] ?: DefaultTexts.fallback(key) ?: return ""
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
