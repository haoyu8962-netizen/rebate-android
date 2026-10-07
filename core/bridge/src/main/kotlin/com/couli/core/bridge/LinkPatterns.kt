package com.couli.core.bridge

import java.net.URI
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 链接形态类别（contracts/enums link_pattern_category）。 */
object LinkCategory {
    const val PRODUCT = "product"
    const val PROMO = "promo"
    const val UNION_HOST = "union_host"
}

/** 一条命中：平台与类别。 */
data class LinkMatch(
    val platform: String,
    val category: String,
)

/**
 * 规范化后的 https 地址（BR-ATTR-29、03 §5.1；APP-04 三端共性第 17 条）：按浏览器的方式规范化后再匹配——
 * 先去掉制表与换行、把反斜杠当作「/」（WHATWG 对 https 的处理），用标准解析器（java.net.URI）解析；主机转小写、去掉末尾的点；
 * 默认端口 443 去掉；路径消除点段（含百分号编码的点段 `%2e`、`.%2E` 等），再按 BR-ATTR-29 百分号解码一次，解码后出现的反斜杠
 * 同样当作「/」并再消一次点段（编码过的反斜杠变体，宁可多拦）。解析不了、不是 https、带用户信息、没有主机的为 null。
 */
data class NormalizedUrl(
    val host: String,
    val port: Int?,
    val path: String,
) {
    companion object {
        private const val HTTPS = "https"
        private const val HTTP = "http"
        private const val DEFAULT_PORT = 443
        private const val HTTP_DEFAULT_PORT = 80
        private val IGNORED = Regex("[\\t\\n\\r]")

        /** 浏览器式预处理（去掉制表与换行、反斜杠当「/」）后的标准解析结果；解析不了为 null。 */
        internal fun uriOf(raw: String): URI? = runCatching { URI(raw.trim().replace(IGNORED, "").replace('\\', '/')) }.getOrNull()

        /**
         * [allowHttp] 只给授权页判定用（可信容器的 Debug 本机源是 http）：http 地址同样规范化，默认端口 80 去掉。
         * 链接形态表的匹配一律只认 https（缺省）。
         */
        @Suppress("ReturnCount")
        fun parse(
            raw: String,
            allowHttp: Boolean = false,
        ): NormalizedUrl? {
            val uri = uriOf(raw) ?: return null
            val host =
                uri.host
                    ?.lowercase()
                    ?.trimEnd('.')
                    ?.takeIf { it.isNotEmpty() } ?: return null
            val https = uri.scheme.equals(HTTPS, ignoreCase = true)
            val http = allowHttp && uri.scheme.equals(HTTP, ignoreCase = true)
            if (!(https || http) || uri.rawUserInfo != null) return null
            val defaultPort = if (https) DEFAULT_PORT else HTTP_DEFAULT_PORT
            val port = uri.port.takeIf { it != -1 && it != defaultPort }
            val resolved = removeDotSegments(uri.rawPath.orEmpty())
            val decoded = percentDecodeOnce(resolved) ?: return null
            val path = removeDotSegments(decoded.replace('\\', '/'))
            return NormalizedUrl(host, port, path.ifEmpty { "/" })
        }

        /** RFC 3986 §5.2.4 消除点段；`.`、`..` 的百分号编码形式（不分大小写）同样算点段。 */
        internal fun removeDotSegments(path: String): String {
            if (path.isEmpty()) return path
            val segments = path.split('/')
            val output = ArrayDeque<String>()
            segments.forEachIndexed { index, segment ->
                val last = index == segments.lastIndex
                when (segment.replace("%2e", ".", ignoreCase = true)) {
                    "." -> {
                        if (last) output.addLast("")
                    }

                    ".." -> {
                        if (output.size > 1) output.removeLast()
                        if (last) output.addLast("")
                    }

                    else -> {
                        output.addLast(segment)
                    }
                }
            }
            val joined = output.joinToString("/")
            return if (path.startsWith('/') && !joined.startsWith('/')) "/$joined" else joined
        }

        /** 百分号解码一次（UTF-8）；编码不合法时为 null（调用方按无法规范化处理）。 */
        @Suppress("ReturnCount") // 无需解码、编码不完整、不是十六进制逐项返回
        internal fun percentDecodeOnce(text: String): String? {
            if ('%' !in text) return text
            val bytes = java.io.ByteArrayOutputStream()
            var index = 0
            while (index < text.length) {
                val char = text[index]
                if (char == '%') {
                    if (index + ESCAPE_DIGITS > text.lastIndex) return null
                    val value = text.substring(index + 1, index + ESCAPE_LENGTH).toIntOrNull(HEX) ?: return null
                    bytes.write(value)
                    index += ESCAPE_LENGTH
                } else {
                    bytes.write(char.toString().toByteArray(Charsets.UTF_8))
                    index += 1
                }
            }
            return bytes.toString(Charsets.UTF_8.name())
        }

        private const val HEX = 16
        private const val ESCAPE_DIGITS = 2
        private const val ESCAPE_LENGTH = 3
    }
}

/**
 * 链接形态表（/v1/config.link_patterns：`{version, rules:[{platform, category, hosts, path_patterns}]}`）。
 * 主机按「整域含子域」匹配：主机等于规则主机，或以「.规则主机」结尾（有点边界，不按字符串前后缀猜）。
 * - union_host：整域阻断，不看 path_patterns（APP-04 三端共性第 18 条：不因路径规则失效而放行）；
 * - product、promo：path_patterns 为空表示整域命中；非空时路径（规范化、解码一次后）命中其中任一正则才算。
 * 整表完整校验（第 18、40 条）：任何一条缺字段、类别不在契约枚举里、主机不是合法主机名（为空、不是字符串、带协议 / 路径 / 端口）、
 * 正则写错，整张表都算坏表，[parse] 返回 null，
 * 由调用方整表回落到上一层（当前 → 有效的 LKG → 包内），不逐条跳过、不与其他层合并。
 */
class LinkPatterns private constructor(
    private val rules: List<Rule>,
) {
    private class Rule(
        val platform: String,
        val category: String,
        val hosts: List<String>,
        val paths: List<Regex>,
    )

    /** 规则条数（0 = 空表）。 */
    val size: Int get() = rules.size

    /** 命中的全部类别（按表内顺序）。 */
    fun matches(url: NormalizedUrl): List<LinkMatch> =
        rules
            .filter { rule -> rule.hosts.any { hostMatches(url.host, it) } && pathMatches(rule, url.path) }
            .map { LinkMatch(it.platform, it.category) }

    private fun pathMatches(
        rule: Rule,
        path: String,
    ): Boolean = rule.category == LinkCategory.UNION_HOST || rule.paths.isEmpty() || rule.paths.any { it.containsMatchIn(path) }

    companion object {
        val EMPTY = LinkPatterns(emptyList())

        /** 类别只认契约枚举 link_pattern_category 的取值（共性第 40 条；生成的枚举，不手写）。 */
        private val CATEGORIES: Set<String> =
            com.couli.generated.LinkPatternCategory.known
                .map { it.raw }
                .toSet()

        /**
         * 合法主机名（共性第 40 条）：只含字母、数字、连字符与点，每段 1–63 个字符、不以连字符开头或结尾，总长不超过 253；
         * 允许末尾一个点。带协议、路径、端口、用户信息、通配符、空白的一律不合法（整表回落）。
         */
        private val HOST_LABEL = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$", RegexOption.IGNORE_CASE)
        private const val MAX_HOST_LENGTH = 253

        internal fun isValidHost(host: String): Boolean {
            val trimmed = host.removeSuffix(".")
            return trimmed.isNotEmpty() && trimmed.length <= MAX_HOST_LENGTH && trimmed.split('.').all { HOST_LABEL.matches(it) }
        }

        fun hostMatches(
            host: String,
            ruleHost: String,
        ): Boolean {
            val target = ruleHost.lowercase().trimEnd('.')
            return target.isNotEmpty() && (host == target || host.endsWith(".$target"))
        }

        /** 解析并完整校验 link_patterns；不是对象、没有 rules 数组或任何一条规则不合格时为 null（坏表，调用方整表回落）。 */
        @Suppress("ReturnCount") // 不是表、没有 rules、任何一条不合格逐项返回
        fun parse(table: JsonObject?): LinkPatterns? {
            val rules = table?.get("rules") as? JsonArray ?: return null
            val parsed = rules.map { (it as? JsonObject)?.let(::rule) ?: return null }
            return LinkPatterns(parsed)
        }

        /** 表是否完整有效（与 [parse] 同一口径）。 */
        fun isValid(table: JsonObject?): Boolean = parse(table) != null

        @Suppress("ReturnCount")
        private fun rule(item: JsonObject): Rule? {
            val platform = item.text("platform")?.takeIf { it.isNotEmpty() } ?: return null
            val category = item.text("category")?.takeIf { it in CATEGORIES } ?: return null
            val hostItems = item["hosts"] as? JsonArray ?: return null
            val hosts = hostItems.map { element -> (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null }
            if (hosts.isEmpty() || hosts.any { !isValidHost(it) }) return null
            val pathItems = item["path_patterns"] as? JsonArray ?: return null
            val paths =
                pathItems.map { element ->
                    val source = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
                    runCatching { Regex(source) }.getOrNull() ?: return null
                }
            return Rule(platform, category, hosts, paths)
        }

        private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
