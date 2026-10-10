package com.couli.core.clipboard

import java.net.URI
import java.net.URISyntaxException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

// FU-CLIP（三端同名，向量 specs/clipboard-candidates-vectors.json）：本地商品候选（白名单链接与淘口令）、「」标题最小化、上传前可见平台、
// 桥 clipboard.read 的最小化输出、系统分享导入的判定。全是纯函数：原文只在调用方的内存里经过，结果只含抽出的片段。

/** 包内默认白名单的一项（K01）：主机等于 [host]、或以「.[host]」结尾即命中 [platform]。[displayName] 是提示条上的平台名（K26：tmall.com 显示天猫）。 */
data class ClipboardHostRule(
    val host: String,
    val platform: String,
    val displayName: String,
)

/**
 * 远程收紧项（FU-CLIP §2.1 第 5 条；K02、K03、K30）：只能减不能加。[platforms] 只保留这些平台（白名单之外的平台名忽略；空 = 全关）；
 * [removeHosts] 去掉与默认项完全相同的项；[tpwdEnabled] 为 false 关口令。各项为 null 表示这一维度不限制。
 */
data class ClipboardLimits(
    val platforms: List<String>? = null,
    val removeHosts: List<String>? = null,
    val tpwdEnabled: Boolean? = null,
) {
    companion object {
        /** features.flags 的电商平台开关前缀（K03）。 */
        const val FLAG_PREFIX = "clipboard.enabled."

        /**
         * features.flags → 收紧项（K03；F-CLIP-05「按平台一键关闭」）：只看 clipboard.enabled.taobao|jd|pdd 为 false 的键，等价于从 platforms
         * 去掉该平台；true 与缺键无效果；端键 clipboard.enabled.ios|android|harmony 与未知平台忽略。没有被关的平台时 platforms 为 null。
         * TODO(N-34) K03：电商平台级开关的含义待规划确认。
         */
        fun fromFeatureFlags(flags: Map<String, Boolean>?): ClipboardLimits {
            val off = ClipboardRules.PLATFORMS.filter { flags?.get(FLAG_PREFIX + it) == false }
            return if (off.isEmpty()) ClipboardLimits() else ClipboardLimits(platforms = ClipboardRules.PLATFORMS - off.toSet())
        }

        /** 配置 data.features.flags（JSON）里的布尔值 → [fromFeatureFlags]；非布尔值忽略。 */
        fun fromConfigData(data: JsonObject?): ClipboardLimits {
            val flags = (data?.get("features") as? JsonObject)?.get("flags") as? JsonObject
            val booleans =
                flags
                    ?.mapNotNull { (key, value) ->
                        (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull?.let { key to it }
                    }?.toMap()
            return fromFeatureFlags(booleans)
        }
    }
}

/** 文本里切出的一个片段与它在原文中的位置（只在内存里，随调用结束丢弃）。 */
data class ClipboardSpan(
    val raw: String,
    val index: Int,
)

/** 链接判定结果（FU-CLIP §2.1 第 2 条）：allowed 时 [platform]、[rule]、[host] 有值；否则 [reasonRaw] 为第一个命中的拒绝原因。 */
data class ClipboardHostCheck(
    val allowed: Boolean,
    val platform: String?,
    val reasonRaw: String?,
    val rule: ClipboardHostRule? = null,
    /** 规范化后的主机（小写、去一个末尾点）。 */
    val host: String? = null,
    /** 主机之后还有路径、查询或片段（显示为「/…」，K26）。 */
    val hasRest: Boolean = false,
)

/**
 * 链接白名单判定（FU-CLIP §2.1 第 2 条；K01、K06）：标准解析器 `java.net.URI`（不用不校验的 android.net.Uri）解析失败 → unparsable；
 * 解析器之外再做字面检查，两者都通过才算。原因顺序固定：unparsable → scheme → userinfo → port → ip → host_invalid → not_allowed。
 * 判定用规范化主机，上传与显示用原串。与服务端 link-patterns 同口径：主机等于白名单某项或以「.项」结尾即命中。
 */
object ClipboardHostPolicy {
    const val UNPARSABLE = "unparsable"
    const val SCHEME = "scheme"
    const val USERINFO = "userinfo"
    const val PORT = "port"
    const val IP = "ip"
    const val HOST_INVALID = "host_invalid"
    const val NOT_ALLOWED = "not_allowed"

    private val SCHEME_PATTERN = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*):")
    private val HOST_CHARS = Regex("^[a-z0-9.\\-]+$")
    private val DIGITS = Regex("^[0-9]+$")
    private val HEX = Regex("^0x[0-9a-f]*$")
    private val AUTHORITY_STOPS = charArrayOf('/', '?', '#', '\\')

    private fun parses(raw: String): Boolean =
        try {
            URI(raw)
            true
        } catch (_: URISyntaxException) {
            false
        }

    private fun reject(
        reason: String,
        host: String? = null,
    ) = ClipboardHostCheck(false, null, reason, host = host)

    @Suppress("ReturnCount", "CyclomaticComplexMethod") // 原因按固定顺序逐项短路
    fun check(
        raw: String,
        rules: ClipboardRules,
    ): ClipboardHostCheck {
        val scheme = SCHEME_PATTERN.find(raw)
        if (!parses(raw) || scheme == null) return reject(UNPARSABLE)
        val rest = raw.substring(scheme.value.length)
        if (scheme.groupValues[1].lowercase() !in setOf("http", "https")) return reject(SCHEME)
        if (!rest.startsWith("//")) return reject(UNPARSABLE)
        val after = rest.substring(2)
        val end = after.indexOfAny(AUTHORITY_STOPS).let { if (it < 0) after.length else it }
        val authority = after.substring(0, end)
        val tail = after.substring(end)
        val hasRest = tail.isNotEmpty() && tail != "/"
        if (authority.isEmpty()) return reject(UNPARSABLE)
        if ('@' in authority) return reject(USERINFO)
        if (authority.startsWith('[')) {
            val close = authority.indexOf(']')
            return when {
                close < 0 -> reject(UNPARSABLE)
                close + 1 < authority.length -> reject(PORT)
                else -> reject(IP)
            }
        }
        if ('[' in authority || ']' in authority) return reject(UNPARSABLE)
        if (':' in authority) return reject(PORT)
        val host = authority.lowercase().removeSuffix(".")
        val labels = host.split('.')
        val last = labels.last()
        if (DIGITS.matches(last) || HEX.matches(last)) return reject(IP, host)
        val badLabel = labels.any { it.isEmpty() || it.startsWith('-') || it.endsWith('-') }
        if (!HOST_CHARS.matches(host) || labels.size < 2 || badLabel) return reject(HOST_INVALID, host)
        val rule = rules.hostRules.firstOrNull { host == it.host || host.endsWith("." + it.host) } ?: return reject(NOT_ALLOWED, host)
        return ClipboardHostCheck(true, rule.platform, null, rule, host, hasRest)
    }
}

/**
 * 淘口令抽取（FU-CLIP §2.1 第 3 条；K04，TODO(N-34) 待 B1-07 样本校准）：只对 taobao。旧版同一包裹符号成对、中间 8～16 位字母数字且不是纯数字、
 * 前后不紧贴 ASCII 字母数字；新版「/CZ 四位数字 空格 8～16 位字母数字/」。抽出整段（含符号），按出现位置排序。京口令、拼多多口令不识别。
 */
object TpwdExtractor {
    private val OLD = Regex("([￥¥$€£₤₳¢₴₰])([A-Za-z0-9]{8,16})\\1")
    private val NEW = Regex("/CZ[0-9]{4} [A-Za-z0-9]{8,16}/")
    private val ALL_DIGITS = Regex("^[0-9]+$")

    fun extract(
        text: String,
        rules: ClipboardRules,
    ): List<String> = spans(text, rules).map { it.raw }

    fun spans(
        text: String,
        rules: ClipboardRules,
    ): List<ClipboardSpan> {
        if (!rules.tpwdActive) return emptyList()
        val out = mutableListOf<ClipboardSpan>()
        var from = 0
        while (from <= text.length) {
            val match = OLD.find(text, from) ?: break
            val start = match.range.first
            val stop = match.range.last + 1
            val touches = asciiAlnum(text.getOrNull(start - 1)) || asciiAlnum(text.getOrNull(stop))
            if (touches || ALL_DIGITS.matches(match.groupValues[2])) {
                from = start + 1
            } else {
                out += ClipboardSpan(match.value, start)
                from = stop
            }
        }
        NEW.findAll(text).forEach { out += ClipboardSpan(it.value, it.range.first) }
        return out.sortedBy { it.index }
    }

    private fun asciiAlnum(c: Char?): Boolean = c != null && (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9')
}

/** 一个本地抽出的商品候选（[kindRaw] url / tpwd）：只在内存里，随请求或提示条作废而释放。 */
data class ClipboardCandidate(
    val kindRaw: String,
    val platform: String,
    val raw: String,
    val index: Int = 0,
    val displayName: String = ClipboardRules.displayNameOf(platform),
    /** 链接的规范化主机（口令为 null）。 */
    val host: String? = null,
    val hasRest: Boolean = false,
) {
    /** 在 [rules] 下仍是允许的候选（规则表变化时作废已显示的提示条，§2.1 第 5 条）。 */
    fun stillAllowed(rules: ClipboardRules): Boolean =
        when (kindRaw) {
            KIND_URL -> ClipboardHostPolicy.check(raw, rules).allowed
            else -> rules.tpwdActive
        }

    companion object {
        const val KIND_URL = "url"
        const val KIND_TPWD = "tpwd"
    }
}

/**
 * 商品候选（FU-CLIP §2.1 第 4 条；G04、K07、A02）：白名单链接（去掉邀请落地页）与淘口令，按在原文中的出现位置排序，合计最多
 * [ClipboardRules.maxUploadLinks]（3）个；上传内容 = 候选 + 最小化后的「」标题，换行连接，原文其余部分不上传。没有候选 → 不是商品。
 */
object ClipboardCandidates {
    fun extract(
        text: String,
        rules: ClipboardRules,
        shareDomains: List<String>,
    ): List<ClipboardCandidate> {
        val all = mutableListOf<ClipboardCandidate>()
        val links = ClipboardText.linkSpans(text)
        for (link in links) {
            if (ClipboardClassifier.inviteLanding(link.raw, shareDomains)) continue
            val check = ClipboardHostPolicy.check(link.raw, rules)
            val rule = check.rule
            if (check.allowed && rule != null) {
                all +=
                    ClipboardCandidate(
                        ClipboardCandidate.KIND_URL,
                        rule.platform,
                        link.raw,
                        link.index,
                        rule.displayName,
                        check.host,
                        check.hasRest,
                    )
            }
        }
        for (token in TpwdExtractor.spans(text, rules)) {
            // 落在某个链接片段里的不算口令（链接字符集含 $ 等包裹符号）
            if (links.none { overlaps(token, it) }) {
                all += ClipboardCandidate(ClipboardCandidate.KIND_TPWD, ClipboardRules.PLATFORM_TAOBAO, token.raw, token.index)
            }
        }
        return all.sortedBy { it.index }.take(rules.maxUploadLinks.coerceAtLeast(0))
    }

    /** 上传内容；没有候选为 null。 */
    fun uploadText(
        text: String,
        rules: ClipboardRules,
        shareDomains: List<String>,
    ): String? = uploadOf(extract(text, rules, shareDomains), text, rules, shareDomains)

    fun uploadOf(
        candidates: List<ClipboardCandidate>,
        text: String,
        rules: ClipboardRules,
        shareDomains: List<String>,
    ): String? {
        if (candidates.isEmpty()) return null
        return ClipboardText.uploadText(candidates.map { it.raw }, text, candidates.size, rules, shareDomains)
    }

    internal fun overlaps(
        a: ClipboardSpan,
        b: ClipboardSpan,
    ): Boolean = a.index < b.index + b.raw.length && a.index + a.raw.length > b.index
}

/**
 * 「」标题片段的最小化（FU-CLIP 共性第 5 条；鸿蒙 R2-01，S0）：标题只用来帮服务端认商品，不能夹带候选以外的东西。
 * - 标题里的 http(s) 链接逐个判定：任何一个不在白名单（含邀请落地页、伪装域名、IP、端口、userinfo）→ 整段舍弃；全部在白名单 → 从标题里剔除
 *   （它们本来就按原文位置参与候选排序与 3 个上限，超出上限的不能借标题上传）；
 * - 能识别的淘口令从标题里剔除；剔除后仍有其他链接形态（任意 scheme://、www.、裸域名、IPv4）或像口令的成对包裹片段 → 整段舍弃；
 * - 剔除后只剩空白 → 没有标题。
 */
object ClipboardTitlePolicy {
    private val SCHEME = Regex("[A-Za-z][A-Za-z0-9+.\\-]*://")
    private val WWW = Regex("(^|[^A-Za-z0-9])www\\.", RegexOption.IGNORE_CASE)
    private val BARE_HOST = Regex("(^|[^A-Za-z0-9.\\-])(?:[A-Za-z0-9\\-]+\\.)+[A-Za-z]{2,}(?![A-Za-z0-9])")
    private val IPV4 = Regex("(^|[^0-9.])[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}(?![0-9])")
    private val LOOSE_TOKEN = Regex("([￥¥$€£₤₳¢₴₰])([^\\s￥¥$€£₤₳¢₴₰]{4,40})\\1")
    private val LOOSE_NEW_TOKEN = Regex("/[A-Za-z]{2}[0-9]{2,6}\\s")
    private val LETTER = Regex("[A-Za-z]")
    private val SPACES = Regex("\\s+")

    @Suppress("ReturnCount") // 舍弃条件逐项短路
    fun minimize(
        title: String?,
        rules: ClipboardRules,
        shareDomains: List<String>,
    ): String? {
        if (title == null) return null
        val strip = mutableListOf<ClipboardSpan>()
        val links = ClipboardText.linkSpans(title)
        for (link in links) {
            if (ClipboardClassifier.inviteLanding(link.raw, shareDomains)) return null
            if (!ClipboardHostPolicy.check(link.raw, rules).allowed) return null
            strip += link
        }
        TpwdExtractor.spans(title, rules).filter { token -> links.none { ClipboardCandidates.overlaps(token, it) } }.forEach { strip += it }
        var rest: String = title
        for (span in strip.sortedByDescending { it.index }) {
            rest = rest.substring(0, span.index) + " " + rest.substring(span.index + span.raw.length)
        }
        if (unsafe(rest)) return null
        return rest.replace(SPACES, " ").trim().ifEmpty { null }
    }

    /** 剔除白名单链接与口令后的残留里还有链接形态或像口令的片段。 */
    @Suppress("ReturnCount") // 各形态逐项短路
    fun unsafe(rest: String): Boolean {
        if (SCHEME.containsMatchIn(rest) || WWW.containsMatchIn(rest) || BARE_HOST.containsMatchIn(rest)) return true
        if (IPV4.containsMatchIn(rest) || LOOSE_NEW_TOKEN.containsMatchIn(rest)) return true
        var from = 0
        while (from <= rest.length) {
            val match = LOOSE_TOKEN.find(rest, from) ?: return false
            if (LETTER.containsMatchIn(match.groupValues[2])) return true
            from = match.range.first + 1
        }
        return false
    }
}

/** 提示条上显示的平台与缩略候选（K26）：[labelRaw] link / tpwd；链接 [display] = 小写主机 +（有路径或查询时）「/…」，口令为 null。 */
data class ClipboardPromptInfo(
    val platform: String,
    val labelRaw: String,
    val display: String?,
    val count: Int,
    val displayName: String,
) {
    companion object {
        const val LABEL_LINK = "link"
        const val LABEL_TPWD = "tpwd"
    }
}

/** 上传前可见平台（FU-CLIP §2.6；K26、K27）：取第一个候选的平台与类型；不显示路径与查询（可能带追踪参数）。 */
object ClipboardPromptDisplay {
    @Suppress("ReturnCount") // 没有候选、口令、链接三个出口
    fun describe(candidates: List<ClipboardCandidate>): ClipboardPromptInfo? {
        val first = candidates.firstOrNull() ?: return null
        val host = first.host
        if (first.kindRaw == ClipboardCandidate.KIND_TPWD || host == null) {
            return ClipboardPromptInfo(first.platform, ClipboardPromptInfo.LABEL_TPWD, null, candidates.size, first.displayName)
        }
        val display = host + if (first.hasRest) "/…" else ""
        return ClipboardPromptInfo(first.platform, ClipboardPromptInfo.LABEL_LINK, display, candidates.size, first.displayName)
    }
}

/**
 * 桥 clipboard.read 的最小化输出（FU-CLIP §2.3；K12、K13、K14）：读之前核对隐私已同意当前版本（不满足不读、回 ""）；读到的内容只回本地候选拼接
 * （白名单链接 / 口令最多 3 个 + 最小化后的「」标题），邀请内容、非白名单链接与其余文字都不回，抽不到回 ""；不套自写排除与 24 小时去重。
 */
object ClipboardBridgeRead {
    @Suppress("ReturnCount") // 未同意、读不到、候选三个出口
    fun respond(
        consentRaw: String,
        read: () -> String?,
        rules: ClipboardRules,
        shareDomains: List<String>,
    ): String {
        if (consentRaw != ClipboardReadPolicy.CONSENT_AGREED) return ""
        val text = read()
        if (text.isNullOrEmpty()) return ""
        return ClipboardCandidates.uploadText(text, rules, shareDomains).orEmpty()
    }
}

/**
 * 系统分享收到的文本当场抽出的东西（FU-CLIP §2.5；K17）：只有候选、上传内容、最小化后的「」标题与显示信息，原文不在这里。
 */
data class ShareImportPayload(
    val mimeOk: Boolean,
    val empty: Boolean,
    val tooLong: Boolean,
    val candidates: List<ClipboardCandidate> = emptyList(),
    val uploadText: String? = null,
    val prefill: String? = null,
    val display: ClipboardPromptInfo? = null,
)

/** 分享导入的判定：[actionRaw] prompt / search_prefill / home_only / ignore_mime / drop / ignore_busy；[scene] 只在 prompt 时为 share_ext。 */
data class ShareImportDecision(
    val actionRaw: String,
    val uploadText: String? = null,
    val prefill: String? = null,
    val scene: String? = null,
    val display: ClipboardPromptInfo? = null,
    val candidates: List<ClipboardCandidate> = emptyList(),
)

/**
 * 系统分享导入（FU-CLIP §2.5 第 3 条；K16～K23）。判定顺序：MIME 不是 text/plain → ignore_mime；未同意当前版本 / 基本模式 → drop（K18）；敏感路由
 * 或浮层（含首启隐私弹窗、强更）→ drop（K19）；parse 在途 → ignore_busy（K22）；文本为空 → home_only；超过 4000 字或没有候选 → search_prefill
 * （预填只取最小化后的「」标题，没有就留空，不拿原文首行）；有候选 → prompt（scene=share_ext，K16）。不套 24 小时去重、不查自写记录（K21）、
 * 不套剪贴板的长度过滤（AS02）。
 */
object ShareImportAction {
    const val PROMPT = "prompt"
    const val SEARCH_PREFILL = "search_prefill"
    const val HOME_ONLY = "home_only"
    const val IGNORE_MIME = "ignore_mime"
    const val DROP = "drop"
    const val IGNORE_BUSY = "ignore_busy"

    const val MIME_TEXT = "text/plain"
    const val SCENE_SHARE = "share_ext"

    /** ParseInputRequest.text 的上限（契约 1～4000）。 */
    const val MAX_TEXT = 4000

    /** 当场抽候选（原文只在这次调用里经过，结果不含原文）。 */
    @Suppress("ReturnCount") // MIME、空、过长、候选四个出口
    fun payload(
        mime: String?,
        text: String?,
        rules: ClipboardRules,
        shareDomains: List<String>,
    ): ShareImportPayload {
        if (mime?.lowercase() != MIME_TEXT) return ShareImportPayload(mimeOk = false, empty = false, tooLong = false)
        if (text.isNullOrEmpty()) return ShareImportPayload(mimeOk = true, empty = true, tooLong = false)
        // TODO(N-34) AS01：超长分享的口径未定，按「没有候选」给本地提示、不预填
        if (ClipboardText.codePoints(text) > MAX_TEXT) return ShareImportPayload(mimeOk = true, empty = false, tooLong = true)
        val candidates = ClipboardCandidates.extract(text, rules, shareDomains)
        return ShareImportPayload(
            mimeOk = true,
            empty = false,
            tooLong = false,
            candidates = candidates,
            uploadText = ClipboardCandidates.uploadOf(candidates, text, rules, shareDomains),
            prefill = ClipboardTitlePolicy.minimize(ClipboardText.quotedTitle(text), rules, shareDomains),
            display = ClipboardPromptDisplay.describe(candidates),
        )
    }

    /** [state] 的键：consent、route、overlay（缺省 none）、parseInFlight（缺省 false）。 */
    @Suppress("ReturnCount") // 判定逐项短路
    fun decideWith(
        state: JsonObject,
        payload: ShareImportPayload,
    ): ShareImportDecision {
        if (!payload.mimeOk) return ShareImportDecision(IGNORE_MIME)
        if (state.text("consent") != ClipboardReadPolicy.CONSENT_AGREED) return ShareImportDecision(DROP)
        val overlay = state.text("overlay") ?: ClipboardSensitive.OVERLAY_NONE
        if (ClipboardSensitive.sensitive(state.text("route"), overlay)) return ShareImportDecision(DROP)
        val busy = (state["parseInFlight"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true
        if (busy) return ShareImportDecision(IGNORE_BUSY)
        if (payload.empty) return ShareImportDecision(HOME_ONLY)
        if (payload.tooLong || payload.uploadText == null || payload.candidates.isEmpty()) {
            return ShareImportDecision(SEARCH_PREFILL, prefill = if (payload.tooLong) null else payload.prefill)
        }
        return ShareImportDecision(PROMPT, payload.uploadText, payload.prefill, SCENE_SHARE, payload.display, payload.candidates)
    }

    fun decide(
        state: JsonObject,
        mime: String?,
        text: String?,
        rules: ClipboardRules,
        shareDomains: List<String>,
    ): ShareImportDecision = decideWith(state, payload(mime, text, rules, shareDomains))

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
}
