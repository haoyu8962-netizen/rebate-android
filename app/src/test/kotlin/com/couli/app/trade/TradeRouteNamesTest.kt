package com.couli.app.trade

import com.couli.app.search.AppSearch
import com.couli.feature.trade.OpenPurchaseController
import com.couli.generated.Routes
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-AND-17（APP-06a 共性第 23 条）：购买流程、详情与授权管理经 Router 打开的每个路由名都在 routes.json 里（登录、搜索、帮助中心、授权管理、
 * 问问 AI 是 AgentChat、商品详情）。不在表里的名字会得到升级提示，等于按钮失效。
 */
class TradeRouteNamesTest {
    @Test
    fun everyRouteOpenedByTheTradeFlowExistsInRoutesJson() {
        val used =
            listOf(
                OpenPurchaseController.LOGIN_ROUTE,
                OpenPurchaseController.SEARCH_ROUTE,
                OpenPurchaseController.HELP_ROUTE,
                AppSearch.SEARCH_ROUTE,
                AppSearch.PRODUCT_ROUTE,
                AppSearch.AGENT_ROUTE,
                "AuthManage",
                "AuthSheet",
                "JumpTip",
            )
        used.forEach { name -> assertTrue("$name 不在 routes.json", name in Routes.all) }
    }
}
