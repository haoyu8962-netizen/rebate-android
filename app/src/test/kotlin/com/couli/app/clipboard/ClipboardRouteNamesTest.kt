package com.couli.app.clipboard

import com.couli.core.clipboard.ClipboardSensitive
import com.couli.generated.Routes
import org.junit.Assert.assertFalse
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
        ClipboardSensitive.routes.forEach { name -> assertTrue("敏感清单 $name 不在 routes.json", name in Routes.all) }
        assertFalse("InviteBackfill 没有登记，不能打开", "InviteBackfill" in Routes.all)
    }
}
