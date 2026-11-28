package com.couli.app.trade

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FU-AND-TB-WEBCODE-B 测试：冷启动 / 晚到回跳的装配与文案键（设计稿 §3.1、§6.4；D10-3680 / D10-3681）。按源码核对（同 WebCodeAssemblySourceTest）：
 * - 文案键 auth.confirming、auth.taobao.completed_tap_again：契约补键前取得到 TradeDraftTexts 草稿（TODO(N-28)）；只认键名，不钉中文原文；
 * - CouliApplication 交给入站中转的是 WebCodeInboundDispatcher（交收件箱 + 下一轮交 orphan）；
 * - AppModule 给真实授权器传账号标签，给 AppTrade 传与授权器共用的尝试记录、收件箱和启动闸门；
 * - orphan 逻辑不经 Router 打开任何页面（D10-3681 C-4：恢复槽到期只提示，不自动打开商品页）；
 * - 切换环境的清理表交给 AppTrade（D10-3690 口径 (7)）；商品详情页接认领冷启动恢复槽，搜索页不接（D10-3690 接入点）。
 */
class WebCodeOrphanAssemblySourceTest {
    private val root: File by lazy {
        val start = File(checkNotNull(System.getProperty("user.dir"))).canonicalFile
        generateSequence(start) { it.parentFile }.first { File(it, "contract.lock").isFile }
    }

    private fun read(path: String) = File(root, path).readText()

    /** 去掉块注释、行注释与全部空白。 */
    private fun code(text: String) =
        text
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")
            .replace(Regex("""\s+"""), "")

    /** [text] 里从 [start] 起、括号配平的那一段调用（含括号）；找不到为空串。 */
    @Suppress("ReturnCount") // 找不到起点、配平即返回、扫到末尾仍未配平，三个出口
    private fun call(
        text: String,
        start: String,
    ): String {
        val from = text.indexOf(start)
        if (from < 0) return ""
        var depth = 0
        for (i in from + start.length - 1 until text.length) {
            when (text[i]) {
                '(' -> depth += 1
                ')' -> if (--depth == 0) return text.substring(from, i + 1)
            }
        }
        return ""
    }

    @Test
    fun q01_newTextKeysResolveToDraftsUntilTheContractHasThem() {
        listOf("auth.confirming", "auth.taobao.completed_tap_again").forEach { key ->
            val text = TradeDraftTexts.text(key)
            assertTrue("$key 取得到文字（契约没有时走 TradeDraftTexts 草稿）", text.isNotEmpty())
            assertNotEquals("$key 不是把键名当文字", key, text)
        }
    }

    @Test
    fun q02_applicationHandsInboundCallbacksToTheDispatcher() {
        val application = code(read("app/src/main/kotlin/com/couli/app/CouliApplication.kt"))
        val sink = application.substringAfter("overridefunwebCodeCallbackSink()", missingDelimiterValue = "")
        assertTrue("CouliApplication 仍给出 webCodeCallbackSink()", sink.isNotEmpty())
        assertTrue(
            "webCodeCallbackSink() 给 WebCodeInboundDispatcher（交收件箱，下一轮无人认领交 orphan）",
            application.contains("WebCodeInboundDispatcher("),
        )
    }

    @Test
    fun q03_appModulePassesTheAccountTagToTheRealAuthorizer() {
        val module = code(read("app/src/main/kotlin/com/couli/app/AppModule.kt"))
        val authorizer = call(module, "BrowserWebCodeAuthorizer(")
        assertTrue("AppModule 构造 BrowserWebCodeAuthorizer", authorizer.isNotEmpty())
        assertTrue("真实授权器拿到账号标签（accountTag = …）：$authorizer", authorizer.contains("accountTag="))
        assertTrue("账号标签用 webCodeAccountTag 算（不落用户 id 原文）", module.contains("webCodeAccountTag"))
    }

    @Test
    fun q04_appModuleWiresTheOrphanPortsIntoAppTrade() {
        val module = code(read("app/src/main/kotlin/com/couli/app/AppModule.kt"))
        val trade = call(module, "AppTrade(")
        assertTrue("AppModule 构造 AppTrade", trade.isNotEmpty())
        listOf("webCodeAttempts=", "webCodeInbox=", "webCodeGate=").forEach {
            assertTrue("AppTrade 接上 $it：$trade", trade.contains(it))
        }
    }

    @Test
    fun q05_orphanLogicNeverOpensAPageThroughTheRouter() {
        val orphan = code(read("feature/trade/src/main/kotlin/com/couli/feature/trade/WebCodeOrphanResume.kt"))
        listOf("Router", "RouteRequest", "ProductDetail", "OPEN_LINK", ".open(").forEach {
            assertFalse("orphan 逻辑不出现 $it（C-4：到期只提示，不打开页面、不重放 open）", orphan.contains(it))
        }
    }

    @Test
    fun q06_appModuleRegistersTheTradeEnvironmentCleanup() {
        val module = code(read("app/src/main/kotlin/com/couli/app/AppModule.kt"))
        val provider = module.substringAfter("funappTrade(", missingDelimiterValue = "").substringBefore("@Provides")
        assertTrue("AppModule 仍有 appTrade", provider.isNotEmpty())
        assertTrue(
            "appTrade 注入切换环境的清理表：$provider",
            provider.substringBefore("):AppTrade{").contains("cleaners:EnvironmentSwitchCleanerRegistry"),
        )
        assertTrue("appTrade 把清理表交给 AppTrade（connectEnvironmentCleanup）", provider.contains("connectEnvironmentCleanup(cleaners)"))
    }

    @Test
    fun q07_onlyTheProductDetailPageClaimsTheColdResumeSlot() {
        val search = code(read("app/src/main/kotlin/com/couli/app/search/AppSearch.kt"))
        val product =
            search.substringAfter("funproductConfig(", missingDelimiterValue = "").substringBefore("privatesuspendfunawaitUpdateCheck")
        val list = search.substringAfter("funsearchConfig(", missingDelimiterValue = "").substringBefore("privatefunonMainThread")
        assertTrue(
            "详情页 bindController 接认领（bindProduct）：$product",
            product.contains("bindController={controller->purchase?.bindProduct(controller)}"),
        )
        assertTrue("AppSearch 仍有 searchConfig", list.isNotEmpty())
        assertFalse("搜索页不接认领（票到期照 C-4 只提示）", list.contains("bindProduct") || list.contains("claimColdAuthResume"))
        val route = search.substringAfter("funProductDetailRoute(", missingDelimiterValue = "").substringBefore("@Composable")
        assertTrue("详情页回到前台试认领一次：$route", route.contains("search.claimColdAuthResume(entryId)"))
        val searchRoute = search.substringAfter("funSearchRoute(", missingDelimiterValue = "").substringBefore("@Composable")
        assertTrue("AppSearch 仍有 SearchRoute", searchRoute.isNotEmpty())
        assertFalse("搜索页回到前台不认领", searchRoute.contains("claimColdAuthResume"))
    }
}
