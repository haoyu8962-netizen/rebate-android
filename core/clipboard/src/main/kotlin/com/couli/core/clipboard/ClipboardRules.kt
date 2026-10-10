package com.couli.core.clipboard

import java.security.MessageDigest
import java.text.Normalizer
import kotlinx.serialization.json.JsonElement

/**
 * 本地过滤规则（F-CLIP-02、BR-INV-04 细则；规则空白 G02、G03、G14）。包内默认取原文数值：长度 10～500、排除不超过 20 位的纯数字或纯
 * 字母数字、排除词表为空；邀请触发前提为文本含 `config.share_domains` 任一域名的 `/i/{code}` 落地页，或含「邀请码」字样。
 *
 * 商品候选只认包内白名单（[hostRules]）里的链接与淘口令（[tpwdActive]）；白名单只能经 [tightened] 收紧（FU-CLIP K01～K04）。
 *
 * TODO(N-30) G02 / TODO(N-34) K02：`config.clipboard` 在契约里是 FreeForm，键名与类型没定，[fromConfig] 不按猜测的键名解析，一律用包内默认。
 */
data class ClipboardRules(
    val minLength: Int = DEFAULT_MIN_LENGTH,
    val maxLength: Int = DEFAULT_MAX_LENGTH,
    /** 不超过这个长度的纯数字 / 纯字母数字文本排除（F-CLIP-02）。 */
    val pureAlnumMaxLength: Int = DEFAULT_PURE_ALNUM_MAX,
    val excludedWords: List<String> = emptyList(),
    /** 邀请触发字样（BR-INV-04 细则「含『邀请码』字样」）。 */
    val inviteKeyword: String = INVITE_KEYWORD,
    /** 上传的候选（白名单链接与口令合计）最多几个（G04、K07：按出现顺序；契约「At most 3 links or tokens」）。 */
    val maxUploadLinks: Int = DEFAULT_MAX_LINKS,
    /** 商品链接白名单（FU-CLIP K01：包内默认，只能经 [tightened] 收紧）。 */
    val hostRules: List<ClipboardHostRule> = DEFAULT_HOSTS,
    /** 淘口令开关（K04；收紧后可关）。 */
    val tpwdEnabled: Boolean = true,
    /** 仍开着的电商平台（K02、K03）。 */
    val platforms: List<String> = PLATFORMS,
) {
    /** 淘口令此刻可用（开关开、淘宝平台未被关）。 */
    val tpwdActive: Boolean get() = tpwdEnabled && PLATFORM_TAOBAO in platforms

    /**
     * 远程收紧（FU-CLIP §2.1 第 5 条；K02、K03、K30）：返回新规则，只减不加——platforms 与现有平台取交集（未知平台名忽略），removeHosts
     * 只去掉与现有项完全相同的项，tpwdEnabled=false 关口令。任何取值都不能增加主机或放宽判定。
     */
    fun tightened(limits: ClipboardLimits): ClipboardRules {
        val kept = limits.platforms?.let { only -> platforms.filter { it in only } } ?: platforms
        val removed = limits.removeHosts.orEmpty()
        val hosts = hostRules.filter { it.platform in kept && it.host !in removed }
        return copy(hostRules = hosts, tpwdEnabled = tpwdEnabled && limits.tpwdEnabled != false, platforms = kept)
    }

    companion object {
        const val PLATFORM_TAOBAO = "taobao"
        const val PLATFORM_JD = "jd"
        const val PLATFORM_PDD = "pdd"

        /** M-内测平台（04 §2.1），顺序即 [ClipboardLimits.fromFeatureFlags] 的输出顺序。 */
        val PLATFORMS: List<String> = listOf(PLATFORM_TAOBAO, PLATFORM_JD, PLATFORM_PDD)

        /**
         * 包内默认白名单（K01，三端逐字一致）：主机等于该项、或以「.项」结尾即命中。TODO(N-34) K01 / K31：B1-07 出 link-patterns 真实规则后
         * 由契约 / 规划决定是否改为构建时生成，仍保持「远程只能收紧」。
         */
        val DEFAULT_HOSTS: List<ClipboardHostRule> =
            listOf(
                ClipboardHostRule("taobao.com", PLATFORM_TAOBAO, "淘宝"),
                ClipboardHostRule("tb.cn", PLATFORM_TAOBAO, "淘宝"),
                ClipboardHostRule("tmall.com", PLATFORM_TAOBAO, "天猫"),
                ClipboardHostRule("jd.com", PLATFORM_JD, "京东"),
                ClipboardHostRule("3.cn", PLATFORM_JD, "京东"),
                ClipboardHostRule("yangkeduo.com", PLATFORM_PDD, "拼多多"),
                ClipboardHostRule("pinduoduo.com", PLATFORM_PDD, "拼多多"),
            )

        /** 平台显示名（K26）：口令与没有主机信息时用。 */
        fun displayNameOf(platform: String): String =
            when (platform) {
                PLATFORM_JD -> "京东"
                PLATFORM_PDD -> "拼多多"
                else -> "淘宝"
            }

        const val DEFAULT_MIN_LENGTH = 10
        const val DEFAULT_MAX_LENGTH = 500
        const val DEFAULT_PURE_ALNUM_MAX = 20
        const val DEFAULT_MAX_LINKS = 3
        const val INVITE_KEYWORD = "邀请码"

        val defaults: ClipboardRules = ClipboardRules()

        /**
         * 下发的 config.clipboard（FreeForm）→ 规则。TODO(N-30) G02：结构未定，任何下发值都认不出，整体忽略、用包内默认（不按猜测的键名
         * 解析）。契约定下键名后在这里解析，认不出的结构仍整体回落。
         */
        @Suppress("UnusedParameter") // 契约定键前不读下发值
        fun fromConfig(raw: JsonElement?): ClipboardRules = defaults
    }
}

/** 文本片段的抽取（G04、BR-TEXT-14 的 30132 预填规则）：只在内存里做，原文不落盘、不进日志。 */
object ClipboardText {
    /** http(s) 链接：只取 ASCII URL 字符，遇到空白、全角与中文标点即止（G03、G04）。 */
    private val LINK = Regex("(?i)https?://[A-Za-z0-9\\-._~:/?#\\[\\]@!$&'()*+,;=%]+")
    private val TITLE = Regex("「([^「」]+)」")

    fun links(text: String): List<String> = LINK.findAll(text).map { it.value }.toList()

    /** 「」内的第一段文字（去掉首尾空白）；没有为 null。 */
    fun quotedTitle(text: String): String? =
        TITLE
            .find(text)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /** 30132【用商品名搜索】的预填（BR-TEXT-14：「」内或首行商品名，提取不到留空）。 */
    fun searchPrefill(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return quotedTitle(text) ?: text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
    }

    /** 链接片段与它在原文中的位置（候选按原文位置排序用）。 */
    fun linkSpans(text: String): List<ClipboardSpan> = LINK.findAll(text).map { ClipboardSpan(it.value, it.range.first) }.toList()

    /**
     * 上传内容（G04）：候选（最多 [maxLinks] 个，按出现顺序）加「」内的标题，换行连接；原文其余部分不上传。FU-CLIP 共性第 5 条（S0）：标题先经
     * [ClipboardTitlePolicy.minimize] 最小化——剔除其中的白名单链接与口令，含非白名单链接、无法识别的口令或其他链接形态时整段舍弃。
     */
    fun uploadText(
        links: List<String>,
        text: String,
        maxLinks: Int,
        rules: ClipboardRules = ClipboardRules.defaults,
        shareDomains: List<String> = emptyList(),
    ): String =
        (links.take(maxLinks) + listOfNotNull(ClipboardTitlePolicy.minimize(quotedTitle(text), rules, shareDomains))).joinToString("\n")

    /**
     * 本地 30132（没有候选、不上传）的【用商品名搜索】预填（BR-TEXT-14：「」内或首行，提取不到留空）。预填点了就发搜索，与上传同一套最小化
     * （共性第 5 条）：有「」标题时只用最小化后的标题（被整段舍弃时留空，不退回首行）；首行含链接形态或像口令的片段时留空。
     */
    @Suppress("ReturnCount") // 空、有标题、首行三个出口
    fun localPrefill(
        text: String?,
        rules: ClipboardRules,
        shareDomains: List<String>,
    ): String? {
        if (text.isNullOrBlank()) return null
        val title = quotedTitle(text)
        if (title != null) return ClipboardTitlePolicy.minimize(title, rules, shareDomains)
        val line = searchPrefill(text) ?: return null
        val risky = linkSpans(line).isNotEmpty() || TpwdExtractor.spans(line, rules).isNotEmpty() || ClipboardTitlePolicy.unsafe(line)
        return line.takeUnless { risky }
    }

    /** Unicode 码点数（契约 text 1～4000 按字符计）。 */
    fun codePoints(text: String): Int = text.codePointCount(0, text.length)

    /** NFKC 规范化（全角 → 半角等，BR-INV-04 细则）。 */
    fun nfkc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)

    /** 本机记录用的 hash（G08：原文 UTF-8 字节的 SHA-256，十六进制）。 */
    fun sha256(text: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
}

/**
 * 邀请码抽取（BR-INV-04 细则）：NFKC 后转大写，按 `(?<![0-9A-Z])[2-9A-HJ-NP-Z]{6}(?![0-9A-Z])` 取第一个匹配；不判断触发前提。
 */
object InviteCodeExtractor {
    private val CODE = Regex("(?<![0-9A-Z])[2-9A-HJ-NP-Z]{6}(?![0-9A-Z])")

    fun extract(text: String): String? = CODE.find(ClipboardText.nfkc(text).uppercase())?.value
}
