package com.couli.app.clipboard

import com.couli.generated.DefaultTexts

/**
 * 剪贴板识别（APP-07）里契约 texts.default.json 还没有的文案键（规则空白 G11，needs.md N-30）：照规划原文（BR-TEXT-14、BR-INV-04）或设计稿
 * HomeClipboard、HomeClipboardInvite、SearchTyping、LoginCodeInvite、Settings、SettingsGuest、ShareHubInvalid 逐字集中放在这一处。三端同名
 * （iOS / Android / 鸿蒙 ClipboardDraftTexts）。有键的（clipboard.prompt、clipboard.invite_prompt、error.*）一律读 DefaultTexts，不放这里。
 *
 * TODO(N-30) 整张表都是「待定稿，不得提审」：契约补键后删除本文件、改读 DefaultTexts；提审前（QA-07）必须替换。
 */
internal object ClipboardDraftTexts {
    /** TODO(N-30) 待定稿，不得提审。键 → 草稿文字。 */
    val DRAFTS: Map<String, String> =
        mapOf(
            // ---- HomeClipboard / HomeClipboardInvite：提示条按钮（设计稿按钮字「粘贴」；读屏名）与关闭
            "clipboard.prompt.action" to "粘贴",
            "clipboard.prompt.action_label" to "粘贴查返利",
            "clipboard.invite_prompt.action" to "粘贴",
            "clipboard.invite_prompt.action_label" to "粘贴并绑定邀请人",
            "clipboard.prompt.close" to "关闭",
            // ---- 首页「粘贴链接，先查返利 · 去粘贴」卡（HomeClipboard / Main / HomeGuest）
            "clipboard.home_card.title" to "粘贴链接，先查返利",
            "clipboard.home_card.subtitle" to "支持淘宝、京东、拼多多",
            "clipboard.home_card.action" to "去粘贴",
            // ---- Android 自绘粘贴按钮（BR-ID-16「粘贴查返利」）与 SearchTyping 的说明文字
            "clipboard.paste_entry" to "粘贴查返利",
            "clipboard.paste_entry.hint" to "复制了商品链接？点下方按钮识别并查返利",
            // ---- 30132 的【用商品名搜索】（BR-TEXT-14 表 A；ShareHubInvalid 的提示样式）
            "clipboard.search_by_name" to "用商品名搜索",
            "clipboard.notice.close" to "关闭",
            // ---- 多卡弹层（没有专稿，按搜索卡样式列出，标题措辞待定稿）
            "clipboard.card_list.title" to "识别到的商品",
            "clipboard.card_list.close" to "关闭",
            // ---- 登录验证码页邀请码行【粘贴】（LoginCodeInvite）与抽不到时的轻提示（BR-INV-04「未识别到邀请码」）
            "clipboard.login_invite.paste" to "粘贴",
            "clipboard.login_invite.paste_label" to "粘贴邀请码",
            "clipboard.login_invite.not_found" to "未识别到邀请码",
            // ---- 设置页开关（BR-TEXT-14 表 C settings.clipboard_auto / .desc，契约快照未同步）
            "settings.clipboard_auto" to "打开 App 自动识别剪贴板",
            "settings.clipboard_auto.desc" to "打开 App 时识别复制的商品链接，可以随时关闭",
        )

    private val PLACEHOLDER = Regex("\\{[a-z0-9_]+\\}")

    /** 先取契约字典，再取草稿；带 {变量} 的文案（没有给值）用 fallback。缺键返回空串。 */
    fun text(key: String): String {
        val raw = DefaultTexts.text(key) ?: DRAFTS[key] ?: DefaultTexts.fallback(key) ?: return ""
        return if (PLACEHOLDER.containsMatchIn(raw)) DefaultTexts.fallback(key) ?: raw.replace(PLACEHOLDER, "") else raw
    }

    /** 填 {变量}；值为空或仍有没填上的变量时用 fallback（BR-TEXT-14：缺变量回退）。 */
    @Suppress("ReturnCount") // 缺键、填完、回落三个出口
    fun format(
        key: String,
        vars: Map<String, String>,
    ): String {
        var text = DefaultTexts.text(key) ?: DRAFTS[key] ?: return text(key)
        vars.forEach { (name, value) -> if (value.isNotEmpty()) text = text.replace("{$name}", value) }
        if (!PLACEHOLDER.containsMatchIn(text)) return text
        return DefaultTexts.fallback(key) ?: text.replace(PLACEHOLDER, "")
    }
}
