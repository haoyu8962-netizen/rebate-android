package com.couli.core.router

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 入站深链配置（apps.json 的入站回调类；03 §4.4「深链」）。
 * @property schemes 自定义 scheme（`<scheme>://r/<name>`）。
 * @property hosts 开启验证的网页链接域名（`https://<host>/r/<name>`，按完整主机名匹配）。
 * 两者都由调用方按构建注入；本模块不内建任何默认值（apps.json inbound 目前为空，needs.md N-05）。
 */
data class DeepLinkConfig(
    val schemes: List<String>,
    val hosts: List<String>,
)

/**
 * 深链解析（03 §4.4）：只认两种形状——`https://<配置的主机>/r/<路由名>?<参数>` 与 `<配置的 scheme>://r/<路由名>?<参数>`。
 * 非法输入一律返回 null。得到的请求 source 恒为 deeplink（启动参数里带的任何 route 都按深链校验）。
 *
 * 参数：query 里的值一律是字符串（`q=123` 仍是 "123"），百分号解码按 UTF-8 严格解码，`+` 按字面量（不当空格）。
 * 已知路由按 params schema 校验，不合格返回 null；未知路由名保留原样交给守卫（得到升级提示）。
 *
 * 保守做法（规则未写，rule-gaps G05；TODO(N-14)）：整串超过 [MAX_URL_LENGTH] 字符、带 fragment / 端口 / userinfo、
 * 重复参数名、空参数名、没有「=」的参数、非法百分号编码、路由名不是字母数字，都返回 null。
 */
object DeepLinkParser {
    const val MAX_URL_LENGTH = 4096
    private const val ROUTE_PREFIX = "r"
    private const val HTTPS = "https"
    private val ROUTE_NAME = Regex("^[A-Za-z][A-Za-z0-9]{0,63}$")

    // scheme://authority/path?query#fragment（RFC 3986 附录 B 的拆分式）
    private val URI_SHAPE = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://([^/?#]*)(/[^?#]*)?(?:\\?([^#]*))?(#.*)?$")
    private const val GROUP_SCHEME = 1
    private const val GROUP_AUTHORITY = 2
    private const val GROUP_PATH = 3
    private const val GROUP_QUERY = 4
    private const val GROUP_FRAGMENT = 5

    fun parse(
        url: String,
        config: DeepLinkConfig,
    ): RouteRequest? {
        val match = URI_SHAPE.matchEntire(url)?.takeIf { url.length <= MAX_URL_LENGTH && it.groups[GROUP_FRAGMENT] == null }
        val groups = match?.groupValues
        val name = groups?.let { routeName(it[GROUP_SCHEME], it[GROUP_AUTHORITY], it[GROUP_PATH], config) }
        val params = if (name == null) null else groups.queryParams()
        val valid = params != null && name != null && paramsMatch(name, params)
        return if (valid) RouteRequest(route = name, params = params, source = RouteSource.DEEPLINK) else null
    }

    private fun List<String>.queryParams(): JsonObject? = parseQuery(this[GROUP_QUERY])

    /** 按形状取路由名；不合格返回 null。 */
    private fun routeName(
        scheme: String,
        authority: String,
        path: String,
        config: DeepLinkConfig,
    ): String? {
        val lowerScheme = scheme.lowercase()
        val segments = path.removePrefix("/").split('/')
        val name =
            when {
                lowerScheme == HTTPS && config.hosts.any { it.equals(authority, ignoreCase = true) } -> {
                    segments.takeIf { it.size == 2 && it[0] == ROUTE_PREFIX }?.get(1)
                }

                lowerScheme != HTTPS && config.schemes.any { it.equals(scheme, ignoreCase = true) } && authority == ROUTE_PREFIX -> {
                    segments.singleOrNull()
                }

                else -> {
                    null
                }
            }
        return name?.takeIf { path.startsWith("/") && ROUTE_NAME.matches(it) }
    }

    private fun paramsMatch(
        name: String,
        params: JsonObject,
    ): Boolean {
        // 深链的 debug_only 路由（如 HomePreview）只在 debug / staging 构建里查得到；查不到的名字交给守卫按未知处理。
        val spec = RouteCatalog.spec(name, includeDebugRoutes = true) ?: return true
        return RouteParams.isValid(spec, params)
    }

    private fun parseQuery(query: String): JsonObject? {
        val pairs = if (query.isEmpty()) emptyList() else query.split('&').map(::decodePair)
        val keys = pairs.map { it?.first }
        val valid = pairs.all { it != null && it.first.isNotEmpty() } && keys.toSet().size == keys.size
        return if (valid) JsonObject(pairs.filterNotNull().associate { (key, value) -> key to JsonPrimitive(value) }) else null
    }

    /** `key=value`，两边都百分号解码；没有「=」或空键、非法编码返回 null。 */
    private fun decodePair(pair: String): Pair<String, String>? {
        val separator = pair.indexOf('=')
        val key = if (separator > 0) percentDecode(pair.substring(0, separator)) else null
        val value = if (separator > 0) percentDecode(pair.substring(separator + 1)) else null
        return if (key != null && value != null) key to value else null
    }

    /** 严格的 UTF-8 百分号解码；`+` 保持字面量；非法编码返回 null。 */
    private fun percentDecode(raw: String): String? {
        val bytes = ByteArrayOutputStream()
        var index = 0
        while (index < raw.length) {
            val ch = raw[index]
            if (ch == '%') {
                val byte = raw.hexByteAt(index + 1) ?: return null
                bytes.write(byte)
                index += PERCENT_TRIPLET
            } else {
                bytes.write(ch.toString().toByteArray(Charsets.UTF_8))
                index++
            }
        }
        return try {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray()))
                .toString()
        } catch (
            @Suppress("SwallowedException") malformed: CharacterCodingException,
        ) {
            null
        }
    }

    private const val PERCENT_TRIPLET = 3
    private const val HEX_RADIX = 16

    private fun String.hexByteAt(start: Int): Int? = if (start + 2 <= length) substring(start, start + 2).toIntOrNull(HEX_RADIX) else null
}
