package com.couli.app.routing

import android.annotation.SuppressLint
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.couli.app.variantLaunchExtraAllowlist
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTypography
import com.couli.core.router.GuardDecision
import com.couli.core.router.RouteNames
import com.couli.core.router.RouteNavigator
import com.couli.core.router.RouteRequest
import com.couli.feature.web.WebContainers
import com.couli.generated.DefaultTexts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** 导航起点：Tab 外壳（隐私闸门有结论之前是启动底图，APP-02b）。 */
@Serializable
data object HomeDestination

/** 按路由名推入的页面（Navigation Compose 类型安全路由；参数以 JSON 文本携带）。 */
@Serializable
data class RouteDestination(
    val name: String,
    val paramsJson: String,
    val readonly: Boolean,
)

/** 「请升级 App」提示（routes.json 没有它的路由名，TODO(N-14) rule-gaps G03）。 */
@Serializable
data object UpgradeDestination

/** 路由页标记前缀（UI 测试、冒烟用）：`route_page_<路由名>`。 */
const val ROUTE_PAGE_TAG_PREFIX = "route_page_"
const val UPGRADE_PAGE_TAG = "route_upgrade_prompt"

/**
 * 路由名 → 页面的注册表。Router 只认路由名，不 import Feature（03 §3.1）；各 Feature 的入口视图在各自任务里注册进来。
 * APP-02a 只有占位页：没注册的路由一律显示 [PlaceholderRoutePage]。
 */
class RoutePageRegistry(
    private val pages: Map<String, @Composable (RouteDestination) -> Unit> = emptyMap(),
) {
    @Composable
    fun Render(destination: RouteDestination) {
        val page = pages[destination.name]
        if (page != null) page(destination) else PlaceholderRoutePage(destination)
    }
}

/**
 * NavHost：首页为起点；Router 的结论经 [NavControllerNavigator] 落到这里。
 * 设置导航图之前先清洗宿主 Activity 的 Intent（[LaunchIntentSanitizer]，R1-01）：Navigation 不得自行消费外部启动请求，
 * 所有外部入口只经深链解析与 Router。
 */
@Composable
fun AppNavHost(
    routing: AppRouting,
    home: @Composable () -> Unit,
    registry: RoutePageRegistry = remember { RoutePageRegistry() },
    navController: NavHostController = rememberNavController(),
    launchExtraAllowlist: Set<String> = variantLaunchExtraAllowlist(),
    shellHooks: ShellHooks = ShellHooks(),
) {
    val activity = LocalActivity.current
    // 必须在组合期间、NavHost 设置导航图之前替换（SideEffect 在组合之后才执行，来不及）
    remember(activity) {
        activity?.let { host -> LaunchIntentSanitizer.sanitize(host.intent, launchExtraAllowlist).also { host.intent = it } }
    }
    DisposableEffect(navController, routing) {
        val navigator = NavControllerNavigator(navController, shellHooks)
        routing.attach(navigator)
        onDispose { routing.detach(navigator) }
    }
    // 路由一确定移出回退栈（清栈、被替换、强更替换导航栈）就同步拆除其中的 WebView 容器，不等退场过渡（APP-04 R2-03、共性第 47 条）
    // 完整回退栈只有 currentBackStack 给得出（标了 RestrictTo(LIBRARY_GROUP)，只读 StateFlow）：visibleEntries 不含被盖住、仍在栈中的
    // 条目，currentBackStackEntryFlow 只有栈顶，都不能用来判断「已移出回退栈」
    @SuppressLint("RestrictedApi")
    val backStack = navController.currentBackStack
    WebContainers.FollowBackStack(backStack)
    // 登录页确定出栈的同步出口（APP-05a 共性第 9 条）：同一个回退栈流、同一条主线程消息里交给外壳（作废本次登录尝试）
    LaunchedEffect(backStack, shellHooks) {
        withContext(Dispatchers.Main.immediate) { backStack.collect { shellHooks.onBackStack(it) } }
    }
    NavHost(navController = navController, startDestination = HomeDestination) {
        composable<HomeDestination> { home() }
        composable<RouteDestination> { entry ->
            val destination = entry.toRoute<RouteDestination>()
            // 每一页显示前重新过守卫（恢复的导航状态、隐私收紧后返回旧页）：不再允许就清栈回到起点，不渲染（APP-02b R1-06）
            if (shellHooks.pageAllowed(destination)) {
                registry.Render(destination)
            } else {
                LaunchedEffect(entry) { navController.popBackStack(HomeDestination, inclusive = false) }
            }
        }
        composable<UpgradeDestination> { UpgradePromptPage() }
    }
}

/**
 * 外壳给导航的钩子（APP-02b）：
 * - [tabRoutes] / [onOpenTab]：Router 打开的是 Tab 根路由（不带参数）时回到起点并切到那个 Tab，不另推一页；
 *   同时带上 Router 给的只读标记（R1-07：只读降级不能在 Tab 分支丢失）；
 * - [onRequestConsent]：Router 要求补同意（基本模式下点需同意的功能）时弹首启弹窗；
 * - [pageAllowed]：推入的页面显示前按当前状态重新过守卫（R1-06），false = 清栈回到起点、不渲染。
 */
class ShellHooks(
    val tabRoutes: Set<String> = setOf(RouteNames.HOME),
    val onOpenTab: (route: String, readonly: Boolean) -> Unit = { _, _ -> },
    /**
     * Tab 根路由带了参数（OrderList {scope}，routes.json）：在切到该 Tab 之前把参数交给 Tab 根（订单列表切到对应子 Tab，APP-08）。
     * 只有声明了参数的 Tab 根路由会带参数；Router 已按 routes.json 校验。
     */
    val onTabParams: (route: String, params: JsonObject) -> Unit = { _, _ -> },
    val onRequestConsent: (RouteRequest) -> Unit = {},
    val pageAllowed: (RouteDestination) -> Boolean = { true },
    /** 回退栈变化（主线程，回退栈一更新就调；APP-05a：登录页出栈即作废登录尝试）。 */
    val onBackStack: (List<NavBackStackEntry>) -> Unit = {},
)

/** Router 结论 → Navigation Compose。 */
class NavControllerNavigator(
    private val navController: NavController,
    private val hooks: ShellHooks = ShellHooks(),
) : RouteNavigator {
    override fun open(
        route: String,
        params: JsonObject,
        readonly: Boolean,
    ) {
        if (route in hooks.tabRoutes) {
            navController.popBackStack(HomeDestination, inclusive = false)
            if (params.isNotEmpty()) hooks.onTabParams(route, params)
            hooks.onOpenTab(route, readonly)
        } else if (route == RouteNames.FORCE_UPDATE) {
            navigateToForceUpdate(navController)
        } else {
            navController.navigate(RouteDestination(route, params.toString(), readonly))
        }
    }

    override fun showUpgradePrompt(request: RouteRequest) {
        navController.navigate(UpgradeDestination)
    }

    /** 弹首启弹窗（基本模式下用户主动点需要同意的功能；Agent 卡片同意后不补打开，由 Router 决定是否登记）。 */
    override fun requestConsent(request: RouteRequest) = hooks.onRequestConsent(request)

    // 不打开：冷启动停在首页（起点），前台停在当前页。TODO(APP-04): 埋点 route_entry_blocked（只记路由名与入口类别）。
    override fun blocked(
        request: RouteRequest,
        decision: GuardDecision,
    ) = Unit
}

/** ForceUpdate 的导航目的地（不带参数）。 */
val FORCE_UPDATE_DESTINATION = RouteDestination(RouteNames.FORCE_UPDATE, "{}", readonly = false)

/**
 * 进入强更页（APP-02c；03 §4.1「命中 below_min 时无论在哪个页面都进入 ForceUpdate」）：清掉起点之上的页面再推强更页，
 * 已在栈顶时不重复推。强更页的次要入口（隐私政策、登录等）推在它上面，返回回到强更页。
 */
fun navigateToForceUpdate(navController: NavController) {
    navController.navigate(FORCE_UPDATE_DESTINATION) {
        popUpTo(HomeDestination) { inclusive = false }
        launchSingleTop = true
    }
}

/** 返回栈里有没有强更页。 */
fun hasForceUpdatePage(navController: NavController): Boolean =
    runCatching { navController.getBackStackEntry(FORCE_UPDATE_DESTINATION) }.isSuccess

/** 受限登录标记（UI 测试、冒烟用）：从强更页进入的登录，不显示邀请码等业务输入（BR-ID-01「受限会话」）。 */
const val RESTRICTED_LOGIN_TAG = "route_login_restricted"

/** 强更状态标记（UI 测试、冒烟用）：强更状态下从强更页进入的隐私中心（R1-05）。 */
const val FORCE_UPDATE_ENTRY_TAG = "route_from_force_update"

/**
 * 占位页：只显示路由名、参数与是否只读（开发字样，不是业务文案）。
 * [restrictedLogin]：从强更页进入的登录（受限登录）。TODO(APP-05): 真实登录页按这个标记隐藏邀请码等业务输入。
 * [fromForceUpdate]：强更状态下从强更页进入（隐私中心等次要入口）。真实隐私中心（APP-12 PrivacyCenterScreen）按强更状态只给协议、清单与撤回同意；占位页只留给没接「我的」的外壳单测。
 */
@Composable
fun PlaceholderRoutePage(
    destination: RouteDestination,
    restrictedLogin: Boolean = false,
    fromForceUpdate: Boolean = false,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(CouliSpacing.space4)
                .testTag(ROUTE_PAGE_TAG_PREFIX + destination.name),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        Text(text = destination.name, style = CouliTypography.title, color = CouliColors.textPrimary)
        Text(text = destination.paramsJson, style = CouliTypography.body, color = CouliColors.textSecondary)
        if (destination.readonly) {
            Text(text = "readonly", style = CouliTypography.body, color = CouliColors.textSecondary)
        }
        if (restrictedLogin) {
            Text(
                text = "restricted login (no invite code)",
                style = CouliTypography.body,
                color = CouliColors.textSecondary,
                modifier = Modifier.testTag(RESTRICTED_LOGIN_TAG),
            )
        }
        if (fromForceUpdate) {
            Text(
                text = "from force update (below_min)",
                style = CouliTypography.body,
                color = CouliColors.textSecondary,
                modifier = Modifier.testTag(FORCE_UPDATE_ENTRY_TAG),
            )
        }
    }
}

/** 升级提示占位：契约还没有「请升级 App」的文案键，先只放【去更新】（app_update.go_store）。TODO(N-14)。 */
@Composable
fun UpgradePromptPage() {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(CouliSpacing.space4)
                .testTag(UPGRADE_PAGE_TAG),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        // TODO(N-14): 「请升级 App」没有目标版本（routes.json since 只说明本端何时支持），选店门槛未定；
        //  商店打开方式还是 null（N-17）。强更与可关闭提示的选店见 AppUpdate（APP-02c）。
        TextButton(onClick = {}) {
            Text(text = DefaultTexts.text("app_update.go_store").orEmpty())
        }
    }
}
