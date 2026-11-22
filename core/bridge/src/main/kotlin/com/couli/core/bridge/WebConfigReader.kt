package com.couli.core.bridge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 从生效配置（/v1/config 的 data：current → LKG → 包内）里取两类容器与桥要用的取值。纯函数，缺字段一律按最保守的取值。
 */
object WebConfigReader {
    // 可信 H5 源不在这里取（N-22，D10-2539）：bridge_origins 是签名白名单 {"jws": …}，只认验签通过的「上次可用白名单」
    // （BridgeOriginsVerifier → ConfigStore.trustedOrigins）。原先「只读字符串数组、不验签」的 trustedOrigins 已删。

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
