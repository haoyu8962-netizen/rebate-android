package com.couli.core.clipboard

/** 24 小时内提示过的一条记录（只有 hash 与时间，G08）。 */
data class ClipboardPromptStamp(
    val hash: String,
    val atMs: Long,
)

/**
 * 分类时的本机状态（纯逻辑 DTO，不是服务端字段）：
 * @property selfWritten App 自己写入剪贴板的内容的 hash（最近 10 条，F-CLIP-02）；
 * @property prompted 出过提示条的 hash 与时间（24 小时去重）；
 * @property inviteEligible 用户可以绑定上级（游客一律可以；已登录看 getMe 的 invite_backfill.eligible 与 inviter_bound，拿不到不可以）；
 * @property selfInviteCode 本人的邀请码（本人码不提示）；
 * @property shareDomains config.share_domains（邀请落地页 `/i/{code}` 的域名）；
 * @property hash 原文 → hash（生产注入本机加盐指纹 [ClipboardFingerprint.hash]，FU-CLIP C3；缺省的未加盐 SHA-256 只给旧向量测试）。
 */
class ClipboardContext(
    val selfWritten: List<String>,
    val prompted: List<ClipboardPromptStamp>,
    val inviteEligible: Boolean,
    val selfInviteCode: String?,
    val shareDomains: List<String>,
    val hash: (String) -> String = ClipboardText::sha256,
)

/** 分类结果：[kindRaw] product / invite / none；[uploadText] 只含本地抽出的片段（G04），原文不在结果里。 */
data class ClipboardClassification(
    val kindRaw: String,
    val reasonRaw: String? = null,
    val inviteCode: String? = null,
    val uploadText: String? = null,
    /** 原文的 hash（出提示条时记入 PromptHistory）。 */
    val hash: String? = null,
    /** 商品的本地候选（FU-CLIP：提示条显示平台与缩略候选，点击上传同一份）。 */
    val candidates: List<ClipboardCandidate> = emptyList(),
)

/**
 * 本地分类（brief §2.2；BR-ID-16、BR-INV-04、OPS-06；规则空白 G03、G04、G08、G14）。顺序：
 * 1. App 自写的 hash → none（self_written）；
 * 2. 24 小时内（严格小于）提示过的同一 hash → none（prompted_recently）；
 * 3. 商品候选：通过长度与纯字母数字、排除词规则，并且有本地候选（FU-CLIP：白名单链接与淘口令；邀请落地页 `/i/{code}` 链接不算；没有候选
 *    仍回 no_link）→ product；
 * 4. 否则邀请：抽到码、满足触发前提（落地页或「邀请码」字样）、不是本人码、用户可绑定 → invite（邀请不套用商品的长度规则，G14）；
 * 5. 否则 none：抽到了码就给邀请的拒绝原因（no_trigger / self_code / not_eligible），抽不到给商品的拒绝原因。
 * 商品优先：同一段文本命中商品就只出商品提示条（OPS-06）。
 */
object ClipboardClassifier {
    const val KIND_PRODUCT = "product"
    const val KIND_INVITE = "invite"
    const val KIND_NONE = "none"

    const val REASON_SELF_WRITTEN = "self_written"
    const val REASON_PROMPTED_RECENTLY = "prompted_recently"
    const val REASON_TOO_SHORT = "too_short"
    const val REASON_TOO_LONG = "too_long"
    const val REASON_PURE_ALNUM = "pure_alnum"
    const val REASON_EXCLUDED_WORD = "excluded_word"
    const val REASON_NO_LINK = "no_link"
    const val REASON_NO_TRIGGER = "no_trigger"
    const val REASON_SELF_CODE = "self_code"
    const val REASON_NOT_ELIGIBLE = "not_eligible"

    /** 同一 hash 的提示间隔（G08：严格小于 24 小时不再提示）。 */
    const val PROMPT_WINDOW_MS = 24L * 60 * 60 * 1000

    /** 「≤20 位的纯数字或纯字母串」（BR-ID-16 细则字面，G26）：数字、字母各自成类，数字字母混合不算。 */
    private val PURE_DIGITS = Regex("^[0-9]+$")
    private val PURE_LETTERS = Regex("^[A-Za-z]+$")

    @Suppress("ReturnCount") // 自写、去重、商品、邀请逐级短路
    fun classify(
        text: String,
        context: ClipboardContext,
        rules: ClipboardRules,
        nowMs: Long,
    ): ClipboardClassification {
        val hash = context.hash(text)
        if (hash in context.selfWritten) return none(REASON_SELF_WRITTEN, hash)
        if (context.prompted.any { it.hash == hash && nowMs - it.atMs in 0 until PROMPT_WINDOW_MS }) {
            return none(REASON_PROMPTED_RECENTLY, hash)
        }
        // FU-CLIP C2 / C8：商品判据从「有 http(s) 链接」换成「有本地候选」（白名单链接去掉邀请落地页 + 淘口令，K01、K04、K07）
        val candidates = ClipboardCandidates.extract(text, rules, context.shareDomains)
        val productReason = productRejection(text, candidates.map { it.raw }, rules)
        if (productReason == null) {
            return ClipboardClassification(
                kindRaw = KIND_PRODUCT,
                uploadText = ClipboardCandidates.uploadOf(candidates, text, rules, context.shareDomains),
                hash = hash,
                candidates = candidates,
            )
        }
        val code = InviteCodeExtractor.extract(text) ?: return none(productReason, hash)
        val inviteReason =
            when {
                !inviteTriggered(text, context.shareDomains, rules) -> REASON_NO_TRIGGER
                context.selfInviteCode?.let { InviteCodeExtractor.extract(it) } == code -> REASON_SELF_CODE
                !context.inviteEligible -> REASON_NOT_ELIGIBLE
                else -> null
            }
        return if (inviteReason == null) {
            ClipboardClassification(kindRaw = KIND_INVITE, inviteCode = code, hash = hash)
        } else {
            none(inviteReason, hash)
        }
    }

    /** 商品候选的拒绝原因；null = 是商品候选。手动粘贴的商品入口用同一套（APP-07 共性第 22 条）。 */
    internal fun productRejection(
        text: String,
        links: List<String>,
        rules: ClipboardRules,
    ): String? {
        val length = text.codePointCount(0, text.length)
        return when {
            length < rules.minLength -> REASON_TOO_SHORT
            length > rules.maxLength -> REASON_TOO_LONG
            length <= rules.pureAlnumMaxLength && (PURE_DIGITS.matches(text) || PURE_LETTERS.matches(text)) -> REASON_PURE_ALNUM
            rules.excludedWords.any { it.isNotEmpty() && text.contains(it) } -> REASON_EXCLUDED_WORD
            links.isEmpty() -> REASON_NO_LINK
            else -> null
        }
    }

    /** 邀请触发前提（BR-INV-04 细则）：含 share_domains 任一域名的 `/i/{code}` 落地页链接，或含「邀请码」字样。 */
    fun inviteTriggered(
        text: String,
        shareDomains: List<String>,
        rules: ClipboardRules = ClipboardRules.defaults,
    ): Boolean =
        ClipboardText.nfkc(text).contains(rules.inviteKeyword) ||
            ClipboardText.links(text).any { inviteLanding(it, shareDomains) }

    /**
     * 是不是邀请落地页链接：主机名与 share_domains 之一完全相同（不区分大小写；不认子域、端口与 userinfo，CLIP-18 未定时从严），路径为
     * `/i/{code}`。
     */
    @Suppress("ReturnCount") // 没有域名、路径不对、主机与码三个出口
    fun inviteLanding(
        link: String,
        shareDomains: List<String>,
    ): Boolean {
        if (shareDomains.isEmpty()) return false
        val rest = link.substringAfter("://", "")
        val host = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        val path = rest.removePrefix(host)
        if (!path.startsWith(LANDING_PREFIX)) return false
        val segment =
            path
                .removePrefix(LANDING_PREFIX)
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
        return shareDomains.any { it.equals(host, ignoreCase = true) } && InviteCodeExtractor.extract(segment) != null
    }

    private const val LANDING_PREFIX = "/i/"

    private fun none(
        reason: String,
        hash: String,
    ) = ClipboardClassification(kindRaw = KIND_NONE, reasonRaw = reason, hash = hash)
}
