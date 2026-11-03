package com.couli.core.clipboard

/**
 * 一次邀请出条判定的结果：[show] 出不出邀请条；[record] 出条的同时写不写邀请提示记录；[reasonRaw] 两者都为 true 时为 null，否则为
 * [InviteDedupPolicy] 里的 REASON_* 之一（三端同名，specs/clipboard-vectors.json manualPasteInviteDedup.decide）。
 */
data class InviteDedupDecision(
    val show: Boolean,
    val record: Boolean,
    val reasonRaw: String?,
)

/**
 * 邀请提示条的独立去重（BR-INV-04 正文 ④ 与细则「手动粘贴的邀请提示去重」；BR-ID-16 细则「本地过滤与去重默认值」；03 §4.6 InviteBranch）：
 * 同一内容（剪贴板原文的本机加盐 hash，不规范化、不按抽出的码）出条起算严格小于 24 小时只提示一次，自动读取与手动粘贴都适用、共用一份记录，
 * 与商品候选摘要记录分开存。纯函数：调用方已确认抽到码、满足触发前提、当前账号可绑定、没有商品候选；不含路由、会话与存储。
 * - 自写排除只对自动读取（BR-INV-04 正文自写排除一句；D10-593 J1：手动不读自写台账，照常出条，仍受本去重）；
 * - 盐不可用（D10-593 J3）：手动照常出条、不写记录，自动不出条；都不调用 [hash]，不回退到不加盐 hash；
 * - fill_input（登录页邀请码框）只抽码填入，不是出条目的，不读不写本记录。
 * 被挡下由调用方静默结束（D10-593 J4：不出条、不上传、不落 30132、不预填搜索）。
 */
object InviteDedupPolicy {
    const val REASON_PROMPTED_RECENTLY = "prompted_recently"
    const val REASON_SELF_WRITTEN = "self_written"
    const val REASON_SALT_UNAVAILABLE = "salt_unavailable"
    const val REASON_NOT_PROMPT_PURPOSE = "not_prompt_purpose"

    /**
     * @param kindRaw [ImportTask.KIND_AUTO] / [ImportTask.KIND_MANUAL]
     * @param entry 手动任务的入口 [ManualPasteAction.ENTRY_HOME] / [ManualPasteAction.ENTRY_SEARCH_TYPING] /
     *   [ManualPasteAction.ENTRY_LOGIN_INVITE]；自动为 null
     * @param text 剪贴板原文
     * @param selfWritten App 自写记录的 hash（只自动读取用）
     * @param prompted 邀请提示记录（hash 与出条时刻）
     * @param saltAvailable 加盐 hash 可用；false 时不调用 [hash]
     * @param hash 原文 → 本机加盐 hash
     */
    @Suppress("LongParameterList", "ReturnCount") // 三端同一签名；目的、盐、自写、去重逐项短路
    fun decide(
        kindRaw: String,
        entry: String?,
        text: String,
        selfWritten: List<String>,
        prompted: List<ClipboardPromptStamp>,
        saltAvailable: Boolean,
        nowMs: Long,
        hash: (String) -> String,
    ): InviteDedupDecision {
        val manual = kindRaw != ImportTask.KIND_AUTO
        if (manual && entry == ManualPasteAction.ENTRY_LOGIN_INVITE) {
            return InviteDedupDecision(show = false, record = false, reasonRaw = REASON_NOT_PROMPT_PURPOSE)
        }
        if (!saltAvailable) return InviteDedupDecision(show = manual, record = false, reasonRaw = REASON_SALT_UNAVAILABLE)
        val key = hash(text)
        if (!manual && key in selfWritten) return InviteDedupDecision(show = false, record = false, reasonRaw = REASON_SELF_WRITTEN)
        if (prompted.any { it.hash == key && nowMs - it.atMs in 0 until ClipboardClassifier.PROMPT_WINDOW_MS }) {
            return InviteDedupDecision(show = false, record = false, reasonRaw = REASON_PROMPTED_RECENTLY)
        }
        return InviteDedupDecision(show = true, record = true, reasonRaw = null)
    }
}
