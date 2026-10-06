package com.couli.core.router

import com.couli.generated.RouteSpec
import com.couli.generated.Routes

/**
 * 路由查表：普通路由来自 [Routes]；debug_only 路由只在 debug / staging 构建（[VariantDebugRoutes]）
 * 且调用方声明 isDebugBuild 时可见，否则按未知路由处理。
 */
object RouteCatalog {
    fun spec(
        name: String,
        includeDebugRoutes: Boolean,
    ): RouteSpec? = Routes.spec(name) ?: if (includeDebugRoutes) VariantDebugRoutes.all[name] else null
}

/** 路由名常量（只列 Router 自己要落到的几个页面；其余页面由调用方按名字打开）。 */
object RouteNames {
    const val HOME = "Home"
    const val LOGIN = "Login"
    const val BIND_PHONE = "BindPhone"
    const val REAL_NAME = "RealName"
    const val EXTERNAL_PAGE = "ExternalPage"
    const val WEB_PAGE = "WebPage"
    const val MESSAGES = "Messages"
    const val FORCE_UPDATE = "ForceUpdate"
}

/**
 * 版本号比较：按「.」分段的数字比较（1.10.0 > 1.9.9），缺的段按 0；「-」或「+」之后的后缀（如 -staging）忽略。
 * 任一段不是数字即视为无法比较，返回 null（调用方按未上线处理，不放行）。
 */
object AppVersion {
    fun compare(
        left: String,
        right: String,
    ): Int? {
        val a = parse(left)
        val b = parse(right)
        return if (a == null || b == null) {
            null
        } else {
            (0 until maxOf(a.size, b.size))
                .map { index -> a.getOrElse(index) { 0L }.compareTo(b.getOrElse(index) { 0L }) }
                .firstOrNull { it != 0 } ?: 0
        }
    }

    /** [version] 是否不低于 [since]；无法比较时 false。 */
    fun atLeast(
        version: String,
        since: String,
    ): Boolean = (compare(version, since) ?: -1) >= 0

    private fun parse(raw: String): List<Long>? {
        val core = raw.trim().substringBefore('-').substringBefore('+')
        val parts = core.split('.')
        val valid = core.isNotEmpty() && parts.all { it.length in 1..MAX_SEGMENT_DIGITS && it.all(Char::isDigit) }
        return if (valid) parts.map { it.toLong() } else null
    }

    private const val MAX_SEGMENT_DIGITS = 9
}
