package com.couli.core.router

import java.net.URI
import java.net.URISyntaxException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 目标网页白名单（编排者三端统一裁定；03 §4.4「WebPage」「ExternalPage」、§5.1）：
 * - ExternalPage：url 的主机须在 [GuardState.externalHosts] 里（完整主机名、不分大小写）；
 * - WebPage：url 的来源（`https://host[:port]`）须在 [GuardState.bridgeOrigins] 里；
 * 两份名单由可注入配置给出，缺省空 = 全拒。url 必须是 https、有主机、不带 userinfo，解析失败一律拒绝。其余路由不受影响。
 *
 * TODO(N-14): 03 §4.4 原文只要求「从深链进入」的 ExternalPage 查 external_hosts（推送回查与 App 内配置的目标不受限）；
 *  这里按编排者裁定对所有来源统一检查，规划同步后再对齐。
 */
object TargetAllowlist {
    fun allows(
        route: String,
        params: JsonObject,
        state: GuardState,
    ): Boolean {
        val uri = if (route == RouteNames.EXTERNAL_PAGE || route == RouteNames.WEB_PAGE) httpsUri(params) else null
        return when (route) {
            RouteNames.EXTERNAL_PAGE -> uri != null && state.externalHosts.any { it.equals(uri.host, ignoreCase = true) }
            RouteNames.WEB_PAGE -> uri != null && origin(uri) in state.bridgeOrigins.map { it.lowercase().trimEnd('/') }
            else -> true
        }
    }

    private fun httpsUri(params: JsonObject): URI? {
        val raw = (params["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val uri =
            try {
                raw?.let(::URI)
            } catch (
                @Suppress("SwallowedException") malformed: URISyntaxException,
            ) {
                null
            }
        return uri?.takeIf { it.scheme.equals("https", ignoreCase = true) && !it.host.isNullOrEmpty() && it.rawUserInfo == null }
    }

    private fun origin(uri: URI): String {
        val port = if (uri.port == -1 || uri.port == HTTPS_DEFAULT_PORT) "" else ":${uri.port}"
        return "https://${uri.host.lowercase()}$port"
    }

    private const val HTTPS_DEFAULT_PORT = 443
}
