package com.couli.app.routing

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
import com.couli.generated.DefaultTexts
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** 首页（Tab 外壳在 APP-02b 做；现在是各构建类型的首屏内容）。 */
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
) {
    val activity = LocalActivity.current
    // 必须在组合期间、NavHost 设置导航图之前替换（SideEffect 在组合之后才执行，来不及）
    remember(activity) {
        activity?.let { host -> LaunchIntentSanitizer.sanitize(host.intent, launchExtraAllowlist).also { host.intent = it } }
    }
    DisposableEffect(navController, routing) {
        val navigator = NavControllerNavigator(navController)
        routing.attach(navigator)
        onDispose { routing.detach(navigator) }
    }
    NavHost(navController = navController, startDestination = HomeDestination) {
        composable<HomeDestination> { home() }
        composable<RouteDestination> { entry -> registry.Render(entry.toRoute<RouteDestination>()) }
        composable<UpgradeDestination> { UpgradePromptPage() }
    }
}

/** Router 结论 → Navigation Compose。 */
class NavControllerNavigator(
    private val navController: NavController,
) : RouteNavigator {
    override fun open(
        route: String,
        params: JsonObject,
        readonly: Boolean,
    ) {
        if (route == RouteNames.HOME && params.isEmpty()) {
            navController.popBackStack(HomeDestination, inclusive = false)
        } else {
            navController.navigate(RouteDestination(route, params.toString(), readonly))
        }
    }

    override fun showUpgradePrompt(request: RouteRequest) {
        navController.navigate(UpgradeDestination)
    }

    // TODO(APP-02b): 弹隐私弹窗（基本模式下用户主动点需要同意的功能；Agent 卡片同意后不补打开）。
    override fun requestConsent(request: RouteRequest) = Unit

    // 不打开：冷启动停在首页（起点），前台停在当前页。TODO(APP-04): 埋点 route_entry_blocked（只记路由名与入口类别）。
    override fun blocked(
        request: RouteRequest,
        decision: GuardDecision,
    ) = Unit
}

/** 占位页：只显示路由名、参数与是否只读（开发字样，不是业务文案）。 */
@Composable
fun PlaceholderRoutePage(destination: RouteDestination) {
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
        // TODO(APP-02c): 打开应用商店（与 ForceUpdate 同一套选店逻辑）。
        TextButton(onClick = {}) {
            Text(text = DefaultTexts.text("app_update.go_store").orEmpty())
        }
    }
}
