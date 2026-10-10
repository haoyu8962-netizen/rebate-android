package com.couli.core.clipboard

import com.couli.core.network.ErrorActionMapper
import com.couli.core.network.HeaderNames
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** parse 的一条结果（多卡弹层按搜索卡样式列出；行内 error_code 的那条按该码置灰并给文案）。 */
data class ParseRow(
    val card: JsonObject?,
    val errorCode: Int?,
    val hitRaw: String?,
)

/**
 * parse 结果到页面动作（brief §2.6；规则空白 G09、CLIP-19、CLIP-20）：
 * - open_detail：只有 1 张卡 → 以进程内的卡片种子打开 ProductDetail，沿用这张卡的 [linkId]（G09）；
 * - card_list：多条结果 → 当前页弹层按搜索卡样式列出（[rows]，行内 error_code 的按该码置灰）；
 * - notice：30131 / 30141 等只提示（[textKey]）；
 * - search_prefill：30132 → error.30132 加【用商品名搜索】（[prefill] 取「」内或首行，提取不到留空）；
 * - retry_after：42901 → 按 Retry-After 禁用粘贴入口与提示条按钮 [disableMs]（缺省或非法 5 秒）；
 * - error：5xxxx 等 → error.<code>，有 trace_id 时带末 6 位（[showTrace6]）。
 * 10405 由网络层走 APP-03a 的强更分流（ErrorActionMapper → AppUpdate.on10405），这里只给 force_update，页面不另写。
 */
data class ParseAction(
    val actionRaw: String,
    val linkId: String? = null,
    val prefill: String? = null,
    val textKey: String? = null,
    val showTrace6: Boolean? = null,
    val disableMs: Long? = null,
    val card: JsonObject? = null,
    val rows: List<ParseRow> = emptyList(),
    val errorCode: Int? = null,
    val traceId: String? = null,
)

object ParseResultAction {
    const val OPEN_DETAIL = "open_detail"
    const val CARD_LIST = "card_list"
    const val NOTICE = "notice"
    const val SEARCH_PREFILL = "search_prefill"
    const val RETRY_AFTER = "retry_after"
    const val ERROR = "error"
    const val FORCE_UPDATE = "force_update"

    const val UNSUPPORTED = 30131
    const val UNRECOGNIZED = 30132
    const val OFF_SHELF = 30141
    const val RATE_LIMITED = 42901
    const val FORCE_UPDATE_CODE = 10405
    private const val SERVER_ERROR_MIN = 50_000

    /** [response] 原样是 ParseInputResponse 外壳（code、data.results、trace_id）；[errorCode] 为整体失败的业务码。 */
    @Suppress("ReturnCount")
    fun map(
        response: JsonObject?,
        errorCode: Int?,
        retryAfter: String?,
        traceId: String?,
    ): ParseAction {
        if (errorCode != null && errorCode != 0) return failure(errorCode, retryAfter, traceId, hitRaw = null)
        val data = response?.get("data") as? JsonObject
        val rows =
            (data?.get("results") as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                ?.map { row ->
                    ParseRow(
                        card = row["card"] as? JsonObject,
                        errorCode = (row["error_code"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull,
                        hitRaw = ((row["hit"] as? JsonObject)?.get("raw") as? JsonPrimitive)?.takeIf { it.isString }?.content,
                    )
                }?.filter { it.card != null || it.errorCode != null }
                .orEmpty()
        val trace = traceId ?: (response?.get("trace_id") as? JsonPrimitive)?.takeIf { it.isString }?.content
        return when {
            rows.isEmpty() -> {
                ParseAction(ERROR, textKey = "error.$SERVER_ERROR_MIN", showTrace6 = trace != null, traceId = trace)
            }

            rows.size > 1 -> {
                ParseAction(CARD_LIST, rows = rows, traceId = trace)
            }

            rows[0].card != null -> {
                val card = rows[0].card
                ParseAction(OPEN_DETAIL, linkId = card?.text("link_id"), card = card, traceId = trace)
            }

            else -> {
                failure(rows[0].errorCode ?: SERVER_ERROR_MIN, retryAfter, trace, rows[0].hitRaw)
            }
        }
    }

    private fun failure(
        code: Int,
        retryAfter: String?,
        traceId: String?,
        hitRaw: String?,
    ): ParseAction =
        when {
            code == UNRECOGNIZED -> {
                ParseAction(SEARCH_PREFILL, prefill = ClipboardText.searchPrefill(hitRaw), textKey = key(code), errorCode = code)
            }

            code == RATE_LIMITED -> {
                ParseAction(RETRY_AFTER, textKey = key(code), disableMs = retryAfterMillis(retryAfter), errorCode = code)
            }

            code == FORCE_UPDATE_CODE -> {
                ParseAction(FORCE_UPDATE, errorCode = code)
            }

            code >= SERVER_ERROR_MIN -> {
                ParseAction(ERROR, textKey = key(code), showTrace6 = traceId != null, errorCode = code, traceId = traceId)
            }

            else -> {
                // 30131、30141 与其余业务码：只提示（30141 在多卡弹层里另按置灰处理）
                ParseAction(NOTICE, textKey = key(code), errorCode = code, traceId = traceId)
            }
        }

    /** Retry-After 的非负整数秒（与网络层同一个解析）；缺省、非法、0 都按 5 秒。 */
    fun retryAfterMillis(raw: String?): Long {
        val parsed = raw?.let { ErrorActionMapper.retryAfterMillis(mapOf(HeaderNames.RETRY_AFTER to it)) }
        return parsed?.takeIf { it > 0 } ?: ErrorActionMapper.DEFAULT_RETRY_AFTER_MILLIS
    }

    private fun key(code: Int) = "error.$code"

    private fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/** 手动粘贴的动作（brief §2.5；G10）：parse / open_search_typing / search_prefill / invite / fill_invite / invite_not_found。 */
data class ManualPasteDecision(
    val actionRaw: String,
    val uploadText: String? = null,
    val prefill: String? = null,
    val code: String? = null,
    val textKey: String? = null,
)

/**
 * 用户主动粘贴（首页「去粘贴」、SearchTyping「粘贴查返利」、开关关闭时首页的粘贴按钮、登录页邀请码【粘贴】）：
 * - 商品入口（home、search_typing）：剪贴板为空 → 进 SearchTyping（flow-map 23.2.25）；抽到商品链接 → 只上传商品链接与「」标题（G04）；
 *   与自动识别同一套上传过滤（A-R3-01，BR-ID-16）：config.share_domains 下的邀请落地页 `/i/{code}` 不算商品链接、一律不上传，商品与邀请
 *   混合时只上传商品片段；只有邀请内容（落地页或「邀请码」字样且抽到码）→ invite，只在本地抽码（[ManualPasteDecision.code]），不发 parse；
 *   非空但既没有商品链接也不是邀请 → 不上传，本地显示 30132 的同一种提示，预填「」内或首行（G10）；
 * - 登录页邀请码（login_invite）：只抽码填入输入框；抽不到 → 轻提示「未识别到邀请码」，不清空已有输入（BR-INV-04）。
 */
object ManualPasteAction {
    const val ENTRY_HOME = "home"
    const val ENTRY_SEARCH_TYPING = "search_typing"
    const val ENTRY_LOGIN_INVITE = "login_invite"

    const val PARSE = "parse"
    const val OPEN_SEARCH_TYPING = "open_search_typing"
    const val SEARCH_PREFILL = "search_prefill"
    const val FILL_INVITE = "fill_invite"

    /** 商品入口粘贴到的只有邀请内容：本地抽码，游客确认后预填登录页；不上传 parse（A-R3-01）。 */
    const val INVITE = "invite"
    const val INVITE_NOT_FOUND = "invite_not_found"

    /** [shareDomains] 是 config.share_domains（邀请落地页的域名，与自动识别 [ClipboardClassifier] 同一个来源）。 */
    @Suppress("ReturnCount") // 登录页邀请码、空剪贴板、商品链接、邀请、其余五个出口
    fun decide(
        entry: String,
        text: String?,
        rules: ClipboardRules,
        shareDomains: List<String> = emptyList(),
    ): ManualPasteDecision {
        if (entry == ENTRY_LOGIN_INVITE) {
            val code = text?.let(InviteCodeExtractor::extract)
            return if (code != null) ManualPasteDecision(FILL_INVITE, code = code) else ManualPasteDecision(INVITE_NOT_FOUND)
        }
        if (text.isNullOrEmpty()) return ManualPasteDecision(OPEN_SEARCH_TYPING)
        // 与自动识别同一套过滤（A-R3-01）：邀请落地页不是商品链接，不进上传片段；长度 / 形态过滤（过长、过短、纯字母数字、排除词）
        // 同样适用于手动粘贴的商品入口（APP-07 共性第 22 条，F-CLIP-02）：不合格的不上传，按「识别不了」给用商品名搜索
        // FU-CLIP C2 / C8：只认本地候选（白名单链接去掉邀请落地页 + 淘口令），抽不到候选 → 本地 30132（G10 不变）
        val candidates = ClipboardCandidates.extract(text, rules, shareDomains)
        if (candidates.isNotEmpty() && ClipboardClassifier.productRejection(text, candidates.map { it.raw }, rules) == null) {
            return ManualPasteDecision(PARSE, uploadText = ClipboardCandidates.uploadOf(candidates, text, rules, shareDomains))
        }
        val code = InviteCodeExtractor.extract(text)
        if (code != null && ClipboardClassifier.inviteTriggered(text, shareDomains, rules)) {
            return ManualPasteDecision(INVITE, code = code)
        }
        return ManualPasteDecision(
            SEARCH_PREFILL,
            prefill = ClipboardText.localPrefill(text, rules, shareDomains),
            textKey = "error.${ParseResultAction.UNRECOGNIZED}",
        )
    }
}

/** 邀请确认的去向（brief §2.7）：prefill_login / bind_phone_first / backfill_pending_contract / none。 */
data class InviteConfirmDecision(
    val actionRaw: String,
)

/**
 * 邀请提示条点确认之后（BR-INV-04 细则；规则空白 G01、G16）：
 * - 游客：码放进进程内的预填容器，经 Router 打开 Login，随登录提交（不发请求、不持久化）；
 * - 已登录、可补填（invite_backfill.eligible 且没有上级）：未绑手机 → 先绑手机（bind_phone_first）；已绑手机 → 补填绑定，但契约没有
 *   `POST /v1/me/inviter` 与 InviteBackfill 路由（G01），只给判定值 backfill_pending_contract，界面上不出已登录用户的邀请提示条；
 * - getMe 拿不到、不可补填、已有上级 → none。
 */
object InviteConfirmAction {
    const val SESSION_GUEST = "guest"
    const val SESSION_MEMBER = "member"
    const val SESSION_PHONE = "phone"

    const val PREFILL_LOGIN = "prefill_login"
    const val BIND_PHONE_FIRST = "bind_phone_first"
    const val BACKFILL_PENDING_CONTRACT = "backfill_pending_contract"
    const val NONE = "none"

    @Suppress("ReturnCount") // 游客、不可补填、按会话三个出口
    fun decide(
        session: String,
        me: JsonObject?,
    ): InviteConfirmDecision {
        if (session == SESSION_GUEST) return InviteConfirmDecision(PREFILL_LOGIN)
        if (!InviteEligibility.loggedInEligible(me)) return InviteConfirmDecision(NONE)
        return when (session) {
            SESSION_MEMBER -> InviteConfirmDecision(BIND_PHONE_FIRST)

            // TODO(N-30) G01：契约补 POST /v1/me/inviter 与 InviteBackfill 路由前不发任何绑定请求
            SESSION_PHONE -> InviteConfirmDecision(BACKFILL_PENDING_CONTRACT)

            else -> InviteConfirmDecision(NONE)
        }
    }
}

/** 可绑定判定（brief §2.7）：游客一律可以；已登录只看 getMe 的 invite_backfill.eligible 与 inviter_bound，拿不到或未知时不可以。 */
object InviteEligibility {
    fun of(
        guest: Boolean,
        me: JsonObject?,
    ): Boolean = guest || loggedInEligible(me)

    fun loggedInEligible(me: JsonObject?): Boolean {
        val bound = (me?.get("inviter_bound") as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        val backfill = me?.get("invite_backfill") as? JsonObject
        val eligible = (backfill?.get("eligible") as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        return bound == false && eligible == true
    }
}
