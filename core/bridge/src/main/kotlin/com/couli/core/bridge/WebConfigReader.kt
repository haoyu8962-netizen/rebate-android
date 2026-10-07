package com.couli.core.bridge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 从生效配置（/v1/config 的 data：current → LKG → 包内）里取两类容器与桥要用的取值。纯函数，缺字段一律按最保守的取值。
 */
object WebConfigReader {
    /**
     * 可信 H5 源（TrustedWebView 只加载它们、桥只认它们）：**只取** bridge_origins 里的完整 https origin（APP-04 三端共性第 3 条）。
     * h5_base_url 只是页面地址的前缀，不当白名单用。[config] 是 ConfigStore 的生效配置，层级依次为本次成功拉取的当前版本 →
     * 上一次成功拉取后写进 App 私有目录的 LKG → 包内版本（包内没有 bridge_origins，所以没有服务端配置时白名单为空，WebPage 不打开）。
     * TODO(N-22)：契约没有给 /v1/config 定义签名，无从「验签」；契约补上签名后，LKG 与当前版本验签失败时这里应得到空集。
     * bridge_origins 在 openapi 里是 FreeForm、示例为 {}，形状未定；这里只接受「完整 origin 字符串的数组」，其他形状一律为空（不放行）。
     */
    fun trustedOrigins(config: JsonObject): Set<String> =
        (config["bridge_origins"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            ?.mapNotNull { BridgeProtocol.normalizeOriginForRules(it, allowHttp = false) }
            .orEmpty()
            .toSet()

    /** 自有分享域（share.open 的第 ② 步，整主机相等）。 */
    fun shareDomains(config: JsonObject): Set<String> = config.strings("share_domains").map { it.lowercase().trimEnd('.') }.toSet()

    /** 深链能打开 ExternalPage 的主机（Router 的 TargetAllowlist）。 */
    fun externalHosts(config: JsonObject): List<String> = config.strings("external_hosts")

    /**
     * external_page.nav_host_sample_bp（万分比，0–10000；BR-ATTR-29 细则「覆盖限制与待定事项」，APP-04 三端共性第 39 条）：
     * - 没有下发（含没有 external_page）：缺省 [DEFAULT_NAV_HOST_SAMPLE_BP] = 1000（10%）；
     * - 服务端显式下发 0：关闭采样；
     * - 0–10000 的整数：原样；
     * - 不合法（不是整数、字符串、小数、超出范围）：不当成关闭，按缺省 1000。
     */
    fun navHostSampleBp(config: JsonObject): Int {
        val raw = (config["external_page"] as? JsonObject)?.get("nav_host_sample_bp") ?: return DEFAULT_NAV_HOST_SAMPLE_BP
        val value =
            (raw as? JsonPrimitive)
                ?.takeIf { !it.isString }
                ?.content
                ?.toIntOrNull()
        return value?.takeIf { it in 0..MAX_BP } ?: DEFAULT_NAV_HOST_SAMPLE_BP
    }

    /** nav_host_sample_bp 没有下发时的缺省值（BR-ATTR-29：1000 = 10%）。 */
    const val DEFAULT_NAV_HOST_SAMPLE_BP: Int = 1_000

    private const val MAX_BP = 10_000

    /** features.flags 里的布尔开关；缺失或不是布尔为 [default]。 */
    fun flag(
        config: JsonObject,
        name: String,
        default: Boolean,
    ): Boolean {
        val flags = (config["features"] as? JsonObject)?.get("flags") as? JsonObject
        val value = (flags?.get(name) as? JsonPrimitive)?.takeIf { !it.isString }?.content
        return value?.toBooleanStrictOrNull() ?: default
    }

    /**
     * app.getConfig：只返回 H5 白名单里的键（openapi：Subset of /v1/config limited to the keys whitelisted for H5）。
     * TODO(N-22)：契约没有登记 H5 白名单；保守地只给不含地址、规则表、客服、文案与法务版本的展示类配置。
     */
    fun h5Values(
        config: JsonObject,
        keys: List<String>,
    ): JsonObject = JsonObject(keys.filter { it in H5_CONFIG_KEYS }.mapNotNull { key -> config[key]?.let { key to it } }.toMap())

    /** app.getConfig 可以给 H5 的键（TODO(N-22)）。 */
    val H5_CONFIG_KEYS: Set<String> = setOf("features", "h5_release", "display", "claim", "help_links")

    private fun JsonObject.strings(key: String): List<String> =
        (get(key) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()
}
