package com.couli.app.web

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.couli.app.routing.AppRouting
import com.couli.app.routing.RouteDestination
import com.couli.core.router.RouteNames
import com.couli.core.router.RouteRequest
import com.couli.core.router.RouteSource
import com.couli.feature.web.ExternalWebScreen
import com.couli.feature.web.TrustedWebScreen
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private fun RouteDestination.param(key: String): String? =
    (runCatching { Json.parseToJsonElement(paramsJson) as? JsonObject }.getOrNull()?.get(key) as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content

/** WebPage（可信容器）：url 已由 Router 按可信源白名单放行（TargetAllowlist）；容器加载前再判一次。 */
@Composable
fun WebPageRoute(
    page: RouteDestination,
    web: AppWeb,
    routing: AppRouting,
    onClose: () -> Unit,
) {
    val config = remember(web) { web.trustedConfig() }
    TrustedWebScreen(
        url = page.param("url").orEmpty(),
        config = config,
        // 宿主用容器实例自己的作用域：被盖住不取消，出栈时随容器拆除取消
        hostFactory = { ui, scope -> web.newHost(ui, scope) },
        onOpenExternal = { url -> openExternal(routing, url) },
        onClose = onClose,
    )
}

/** 可信容器跳到白名单外的 https 地址：在新的 ExternalWebView 打开（经 Router，in_app 入口）。 */
fun openExternal(
    routing: AppRouting,
    url: String,
) {
    routing.router.open(RouteRequest(RouteNames.EXTERNAL_PAGE, JsonObject(mapOf("url" to JsonPrimitive(url))), RouteSource.IN_APP.raw))
}

/** ExternalPage（第三方页容器）。 */
@Composable
fun ExternalPageRoute(
    page: RouteDestination,
    web: AppWeb,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val routing = web.routing
    ExternalWebScreen(
        url = page.param("url").orEmpty(),
        // 路由参数 title、nav_style 传进容器（第 24 条）：副标题与标题栏配色
        subtitle = page.param("title"),
        navStyle = page.param("nav_style"),
        policy = web.externalPolicy,
        linkPatterns = web::linkPatterns,
        flags = web::externalFlags,
        track = web::track,
        appVersion = web.appVersion,
        onProductIntent = { url, platform, showUnresolved -> if (!productIntent(routing, url, platform)) showUnresolved() },
        onOpenBrowser = { target -> openInBrowser(context, target) },
        onSearch = { openSearch(routing) },
        onClose = onClose,
        browserAvailable = remember(context) { browserAvailable(context) },
    )
}

/** 平台页面被拦后的「去搜索」（BR-ATTR-29 ②(b)）：经 Router 打开搜索页（in_app 入口）。 */
internal fun openSearch(routing: AppRouting) {
    routing.router.open(RouteRequest(SEARCH, JsonObject(emptyMap()), RouteSource.IN_APP.raw))
}

private const val SEARCH = "Search"

/**
 * 商品回流（APP-04 三端共性第 8 条，03 §5.1）：取消原导航后向 Router 发出 ProductDetail 意图，item_ref 是去掉片段的商品页 URL
 * （BR-PROD-11 原样透传）。product_key 只能由服务端识别给出（TODO(APP-08)：先调识别接口再带上 product_key）；识别接入前 Router
 * 按参数不合 schema 拒绝，返回 false，由容器提示 external_page.product_unresolved（留在原页，不只写日志）。
 */
internal fun productIntent(
    routing: AppRouting,
    url: String,
    platform: String?,
): Boolean {
    val params =
        buildMap {
            platform?.let { put("platform", JsonPrimitive(it)) }
            put("item_ref", JsonPrimitive(url.take(ITEM_REF_MAX)))
        }
    return routing.router.open(RouteRequest(PRODUCT_DETAIL, JsonObject(params), RouteSource.IN_APP.raw)).opensPage
}

private const val PRODUCT_DETAIL = "ProductDetail"
private const val ITEM_REF_MAX = 1024

/** 本机有没有能确定是浏览器的 App：与点击时用同一套解析（[BrowserExit]）。没有就不显示【在浏览器打开】。 */
private fun browserAvailable(context: Context): Boolean = BrowserExit.intentFor(context.packageManager, BrowserExit.PROBE_URL) != null

/** 【在浏览器打开】：只在用户点击时交出当前主框架地址（已再判一次），不带任何归因参数。 */
private fun openInBrowser(
    context: Context,
    url: String,
) {
    // 只交给已确认是浏览器的那个包（不让系统按 App Links 交给别的 App）；解析不到时什么都不做
    val intent = BrowserExit.intentFor(context.packageManager, url) ?: return
    runCatching { context.startActivity(intent) }.onFailure { if (it !is ActivityNotFoundException) throw it }
}

/**
 * 系统浏览器出口的目标解析（03 §5.1、APP-04 三端共性第 9 条，Android 第 1 轮评审 R1-05）。可用性判断与真正启动用同一个函数：
 * 1. 浏览器 = 声明了 ACTION_MAIN + CATEGORY_APP_BROWSER 的 App（单层查询，不嵌套 selector；清单 queries 已登记这条查询）；
 * 2. 系统默认的网页处理者正好是其中之一时优先用它，否则取第一个；
 * 3. 交出的 Intent 是 ACTION_VIEW + BROWSABLE + 该 URL，并用 setPackage 限定在这个浏览器包内解析——系统只会在这个包里找活动，
 *    App Links 认领了该域名的其他 App 收不到；这个包里没有能打开该 URL 的活动时视为没有出口（返回 null）。
 */
internal object BrowserExit {
    /** 可用性探测用的地址（只用于解析，不会被打开）。 */
    const val PROBE_URL = "https://example.com/"

    fun intentFor(
        packageManager: PackageManager,
        url: String,
    ): Intent? {
        val browsers =
            packageManager
                .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER), 0)
                .mapNotNull { it.activityInfo?.packageName }
                .distinct()
        if (browsers.isEmpty()) return null
        val preferred =
            packageManager
                .resolveActivity(view(url, null), PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo
                ?.packageName
                ?.takeIf { it in browsers }
        return (listOfNotNull(preferred) + browsers)
            .distinct()
            .map { view(url, it) }
            .firstOrNull { packageManager.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY) != null }
    }

    private fun view(
        url: String,
        browserPackage: String?,
    ): Intent =
        Intent(Intent.ACTION_VIEW, url.toUri())
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .apply { browserPackage?.let(::setPackage) }
}
