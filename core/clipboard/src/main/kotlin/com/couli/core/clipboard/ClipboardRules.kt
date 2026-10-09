package com.couli.core.clipboard

import java.security.MessageDigest
import java.text.Normalizer
import kotlinx.serialization.json.JsonElement

/**
 * 本地过滤规则（F-CLIP-02、BR-INV-04 细则；规则空白 G02、G03、G14）。包内默认取原文数值：长度 10～500、排除不超过 20 位的纯数字或纯
 * 字母数字、排除词表为空；邀请触发前提为文本含 `config.share_domains` 任一域名的 `/i/{code}` 落地页，或含「邀请码」字样。
 *
 * TODO(N-30) G02：`config.clipboard` 在契约里是 FreeForm，键名与类型没定，[fromConfig] 不按猜测的键名解析，一律用包内默认。
 */
data class ClipboardRules(
    val minLength: Int = DEFAULT_MIN_LENGTH,
    val maxLength: Int = DEFAULT_MAX_LENGTH,
    /** 不超过这个长度的纯数字 / 纯字母数字文本排除（F-CLIP-02）。 */
    val pureAlnumMaxLength: Int = DEFAULT_PURE_ALNUM_MAX,
    val excludedWords: List<String> = emptyList(),
    /** 邀请触发字样（BR-INV-04 细则「含『邀请码』字样」）。 */
    val inviteKeyword: String = INVITE_KEYWORD,
    /** 上传的链接最多几个（G04：按出现顺序）。 */
    val maxUploadLinks: Int = DEFAULT_MAX_LINKS,
) {
    companion object {
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

    /** 上传内容（G04）：链接（最多 [maxLinks] 个，按出现顺序）加「」内的标题，换行连接；原文其余部分不上传。 */
    fun uploadText(
        links: List<String>,
        text: String,
        maxLinks: Int,
    ): String = (links.take(maxLinks) + listOfNotNull(quotedTitle(text))).joinToString("\n")

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
