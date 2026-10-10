package com.couli.app.home

import androidx.compose.runtime.Composable
import com.couli.app.routing.AppRouting
import com.couli.app.routing.PlaceholderRoutePage
import com.couli.app.routing.RouteDestination

/**
 * debug / staging：HomePreview（debug_only 路由，TECH-11，G24）注册进路由页表；Router 用 DebugRoutes 校验名称与参数（in_app、deeplink）。
 * Release 的同名函数返回空表（路由表里也没有 HomePreview，按未知路由处理）。
 */
internal fun variantHomePreviewPages(
    home: AppHome?,
    routing: AppRouting,
    onExit: (String?) -> Unit,
): Map<String, @Composable (RouteDestination) -> Unit> =
    mapOf(
        HOME_PREVIEW_ROUTE to { page: RouteDestination ->
            if (home != null) HomePreviewRoute(home, routing, page, onExit) else PlaceholderRoutePage(page)
        },
    )

/** debug_only 路由名（只在调试源码集出现，release-scan 按路由名检查 Release 制品）。 */
private const val HOME_PREVIEW_ROUTE = "HomePreview"

/** 调试面板引用（保证标记常量留在 debug / staging 制品里，release-scan --self-test 命中）。 */
internal val homePreviewMarker: String get() = HOME_PREVIEW_MARKER
