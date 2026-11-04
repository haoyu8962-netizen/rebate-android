package com.couli.app.clipboard

import com.couli.core.clipboard.ImportGates
import com.couli.generated.Routes
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APP-06a 共性第 23 条：剪贴板经 Router 打开的路由名（Search、ProductDetail、Login）都在 routes.json 里；敏感清单的路由名也都是契约里的
 * 名字（写错一个就等于那一页不受保护）。InviteBackfill 不在 routes.json（G01），不得直接打开。
 */
class ClipboardRouteNamesTest {
    @Test
    fun everyRouteOpenedOrGuardedExistsInRoutesJson() {
        listOf(AppClipboard.SEARCH_ROUTE, AppClipboard.PRODUCT_DETAIL_ROUTE, "Login", "Settings").forEach { name ->
            assertTrue("$name 不在 routes.json", name in Routes.all)
        }
        assertTrue("InviteBackfill 即使未登记路由也必须敏感", "InviteBackfill" in ImportGates.sensitiveRoutes)
        (ImportGates.sensitiveRoutes - "InviteBackfill").forEach { name -> assertTrue("敏感清单 $name 不在 routes.json", name in Routes.all) }
        // FU-X-HOME-PAGE-API test-change（#24）：锁 00deded0ef6f 的 routes.json:848 已登记 InviteBackfill；
        // 本端没有页面，按 P1 取 B 回落升级提示（HomePageApiP1RouteTest）
        assertTrue("InviteBackfill 已在新锁 routes.json 登记", "InviteBackfill" in Routes.all)
    }
}
