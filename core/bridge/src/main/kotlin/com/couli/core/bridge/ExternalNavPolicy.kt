package com.couli.core.bridge

import kotlinx.serialization.json.JsonObject

/** 导航去向（三端测试接线约定的 actionRaw）。 */
object ExternalNavAction {
    const val LOAD = "load"
    const val BLOCK = "block"
    const val TO_NATIVE_PRODUCT = "to_native_product"
    const val CANCEL_REPORT = "cancel_report"
}

/**
 * 一次导航判定。[reason] 没有契约枚举（TODO(N-22)），只作日志与埋点映射用：非 https 时为 scheme 类别
 * （http、intent、market、tel、sms、mailto、app_scheme、other_scheme，与 events.yaml external_page_blocked_nav.kind 同名），
 * 命中规则时为类别名，解析失败为 invalid_url。[match] 为决定这次去向的规则（平台、类别）。
 *
 * [hit]（Android 第 2 轮评审 R2-06、共性第 49 条）：地址是否命中链接形态表的任一条规则，与导航去向分开保存——只命中 promo 时
 * 照常放行（load、[match] 为 null），[hit] 仍为 true。external_page_nav_host 的 hit 字段按它上报。
 */
data class ExternalNavDecision(
    val actionRaw: String,
    val reason: String?,
    val match: LinkMatch? = null,
    val hit: Boolean = match != null,
)

/**
 * 第三方页容器的导航判定（ExternalNavigationPolicy，03 §5.1；规则在 BR-ATTR-29）。输入 URL、框架类型（主框架 / 子框架、是否新窗口）
 * 与规则表，输出去向；没有界面，三端共用一组向量。
 *
 * - 先看 scheme：只放行 https；其余（http、intent://、应用市场、tel、sms、mailto、file、javascript、blob、data 及其他自定义
 *   scheme）一律 cancel_report（取消并上报，不交给系统，intent:// 不解析也不加载备用地址）。
 * - https 再按链接形态表：product 命中（主机与路径）时，主框架与新窗口请求交原生商品识别（to_native_product），子框架只拦下
 *   （block，BR-ATTR-29 ②(b)）；union_host 命中（整域含子域）block；promo 不用于容器导航阻断（load）；其余 load。
 * - 规则表：调用方给的当前表（ConfigStore 的 current → LKG）优先；为 null 或是坏表（[LinkPatterns.parse] 完整校验不过）时整表
 *   用构造时注入的包内快照 [bundledLinkPatterns]，当前表不会与快照逐条合并（APP-04 三端共性第 18 条）。两层都没有可用规则时
 *   https 导航一律 block（reason = rules_unavailable，拒绝加载；容器另在打开前整页不开）。
 *
 * 不在本函数里的（由容器接线另判，见 feature:web 的 ExternalWebView）：隐私状态、external_page.product_intercept 与
 * union_host_block 开关、下载（响应头 / MIME / 下载回调）、子框架回调能否拿到。
 */
class ExternalNavPolicy(
    bundledLinkPatterns: JsonObject,
) {
    private val bundled: LinkPatterns? = LinkPatterns.parse(bundledLinkPatterns)?.takeIf { it.size > 0 }

    @Suppress("ReturnCount")
    fun decide(
        url: String,
        isMainFrame: Boolean,
        isNewWindow: Boolean,
        linkPatterns: JsonObject?,
    ): ExternalNavDecision {
        val scheme = schemeOf(url)
        if (scheme != HTTPS) return ExternalNavDecision(ExternalNavAction.CANCEL_REPORT, schemeKind(scheme))
        val normalized =
            NormalizedUrl.parse(url) ?: return ExternalNavDecision(ExternalNavAction.CANCEL_REPORT, INVALID_URL)
        val table = table(linkPatterns) ?: return ExternalNavDecision(ExternalNavAction.BLOCK, RULES_UNAVAILABLE)
        val matches = table.matches(normalized)
        val product = matches.firstOrNull { it.category == LinkCategory.PRODUCT }
        if (product != null) {
            val action = if (isNewWindow || isMainFrame) ExternalNavAction.TO_NATIVE_PRODUCT else ExternalNavAction.BLOCK
            return ExternalNavDecision(action, LinkCategory.PRODUCT, product)
        }
        val union = matches.firstOrNull { it.category == LinkCategory.UNION_HOST }
        if (union != null) return ExternalNavDecision(ExternalNavAction.BLOCK, LinkCategory.UNION_HOST, union)
        // promo 不阻断容器导航，但命中规则表的事实保留（R2-06）
        return ExternalNavDecision(ExternalNavAction.LOAD, null, hit = matches.isNotEmpty())
    }

    /**
     * 系统浏览器出口（03 §5.1 can_open_in_browser）：地址是 https 且不命中链接形态表的任何类别（union_host、product、promo）。
     * 主框架页面每次完成导航后重算；点击时对要交出去的地址再判一次。
     */
    fun canOpenInBrowser(
        url: String,
        linkPatterns: JsonObject?,
    ): Boolean {
        val normalized = NormalizedUrl.parse(url) ?: return false
        return table(linkPatterns)?.matches(normalized)?.isEmpty() == true
    }

    /** 当前生效的规则表条数（0 = 空表，容器据此保守降级）。 */
    fun ruleCount(linkPatterns: JsonObject?): Int = table(linkPatterns)?.size ?: 0

    /** 生效的规则表：当前表完整有效且非空就用它，否则整表回落到包内快照；都不可用为 null。 */
    private fun table(current: JsonObject?): LinkPatterns? = current?.let(LinkPatterns::parse)?.takeIf { it.size > 0 } ?: bundled

    companion object {
        private const val HTTPS = "https"
        const val INVALID_URL = "invalid_url"
        const val RULES_UNAVAILABLE = "rules_unavailable"

        /** URL 的 scheme（小写）；没有合法 scheme 时为空串。 */
        fun schemeOf(url: String): String {
            val candidate = url.substringBefore(':', missingDelimiterValue = "")
            val valid = candidate.isNotEmpty() && candidate.first().isLetter() && candidate.all { it.isLetterOrDigit() || it in "+-." }
            return if (valid) candidate.lowercase() else ""
        }

        /** scheme → external_page_blocked_nav.kind。 */
        fun schemeKind(scheme: String): String =
            when (scheme) {
                "http" -> "http"
                "intent" -> "intent"
                "market" -> "market"
                "tel" -> "tel"
                "sms", "smsto" -> "sms"
                "mailto" -> "mailto"
                "", "file", "javascript", "blob", "data", "about", "content" -> "other_scheme"
                else -> "app_scheme"
            }
    }
}
