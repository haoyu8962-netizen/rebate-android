package com.couli.app.shell

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.couli.app.privacy.AppPrivacy
import com.couli.app.privacy.toRouterPrivacy
import com.couli.app.routing.AppNavHost
import com.couli.app.routing.AppRouting
import com.couli.app.routing.HomeDestination
import com.couli.app.routing.PlaceholderRoutePage
import com.couli.app.routing.RouteDestination
import com.couli.app.routing.RoutePageRegistry
import com.couli.app.routing.ShellHooks
import com.couli.app.update.AppUpdate
import com.couli.app.update.ForceUpdateActions
import com.couli.app.update.ForceUpdatePage
import com.couli.app.update.UpdatePromptDialog
import com.couli.app.update.UpdateUi
import com.couli.core.router.EntryGuard
import com.couli.core.router.GuardKind
import com.couli.core.router.GuardState
import com.couli.core.router.PrivacyState
import com.couli.core.router.RouteNames
import com.couli.core.router.RouteRequest
import com.couli.core.router.RouteSource
import com.couli.core.router.SessionState
import com.couli.core.router.UpdateCheck
import com.couli.core.router.UpdateState
import com.couli.core.update.UpdateStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * App 外壳（APP-02b；03 §4.1、§4.4）：环境闸门 settled 之后由 RootScreen 组合。
 *
 * - 隐私闸门还没有结论（首次安装、隐私政策升级后的冷启动）：导航起点是启动底图，上面是首启弹窗 / 二次说明；
 *   弹窗里的《隐私政策》《用户协议》经 Router 只读打开 Agreement（占位页），返回后弹窗重新出现。
 * - 有结论（已同意 / 基本模式）：起点是 Tab 外壳；基本模式下首页 Tab 的根是基本模式页。
 *   基本模式点【开启完整功能】、或 Router 要求补同意时，在当前页上弹首启弹窗。
 * - 弹窗显示期间返回键被吞掉（BackHandler + Dialog 不响应返回键、点外部不关闭，BR-ID-11）。
 *
 * 启动意图与 pending_action 的放行由 [AppRouting] 按「环境 → 隐私有结论 → 强更 → 外壳就绪」判断（03 §4.4）；
 * 隐私结论变化经 [AppPrivacy.onConclusionChanged] 通知 AppRouting（AppModule 接线）。
 *
 * 强更（APP-02c；03 §4.1）：
 * - 强更结论为 below_min 时无论在哪个页面都进入 ForceUpdate（清掉起点之上的页面）；强更页吞返回键；从强更页进入的
 *   隐私政策、登录（受限登录标记）推在它上面，返回回到强更页；结论解除（重试后不再低于最低版本）时回到起点。
 * - below_recommended 且该弹时，在当前页上弹可关闭更新提示（不在强更页、首启弹窗之上弹）。
 *
 * @param homeContent 首页 Tab 的根内容（首页 Feature 的占位；debug / staging 是调试首屏）。
 * @param update 强更检查（APP-02c）；为 null 时不显示强更页与更新提示（只测外壳的单测）。
 */
@Composable
fun AppShell(
    routing: AppRouting,
    privacy: AppPrivacy,
    homeContent: @Composable () -> Unit,
    navController: NavHostController = rememberNavController(),
    update: AppUpdate? = null,
) {
    val privacyState by privacy.state.collectAsStateWithLifecycle()
    val conclusion = privacyState.toRouterPrivacy()
    // 选中的 Tab 与它的只读标记都来自守卫 / Router 的结论（R1-07），不由基本模式推导
    var selection by rememberSaveable(stateSaver = TabSelection.Saver) {
        mutableStateOf(guardedSelection(ShellTab.HOME, routing.guardState()))
    }
    val noUpdate = remember { MutableStateFlow<UpdateUi?>(null) }
    val updateUi by (update?.ui ?: noUpdate).collectAsStateWithLifecycle()
    val registry =
        remember(homeContent, update) {
            RoutePageRegistry(
                mapOf(
                    ShellTab.HOME.route to { _ -> homeContent() },
                    RouteNames.FORCE_UPDATE to { _ -> update?.let { ForceUpdateRoute(it, routing) } },
                    // 强更状态下能到登录页只有一条路：从强更页进入（受限登录，BR-ID-01「受限会话」）
                    RouteNames.LOGIN to { page ->
                        PlaceholderRoutePage(page, restrictedLogin = routing.update.value == UpdateCheck.BELOW_MIN)
                    },
                    // 隐私中心占位（TODO(APP-12)）：强更状态下只可能从强更页进入，带强更状态标记（R1-05）
                    PRIVACY_CENTER_ROUTE to { page ->
                        PlaceholderRoutePage(page, fromForceUpdate = routing.update.value == UpdateCheck.BELOW_MIN)
                    },
                ),
            )
        }
    val hooks =
        remember(privacy) {
            ShellHooks(
                tabRoutes = ShellTab.routes,
                onOpenTab = { route, readonly -> ShellTab.ofRoute(route)?.let { selection = TabSelection(it, readonly) } },
                onRequestConsent = { privacy.requestFullFunction() },
                pageAllowed = { page -> stillAllowedWithUpdate(page, routing.guardState()) },
            )
        }
    val activity = LocalActivity.current

    // 隐私结论变化（先于 Router 收到通知，AppPrivacy.onConclusionChanged(beforeRouting = true)）：
    // 进入基本模式（含撤回）→ 清导航栈、切回基本模式页；其余变化按新结论重新过守卫选中的 Tab（R1-06），只收紧不放宽（R2-02）。
    // 首次组合（含 Activity 重建、字号变化后恢复的 Tab）也重新过一次守卫，同样只收紧：恢复不是新的原生点击，
    // 保存的只读标记原样保留，只有用户重新点 Tab（Router 新的打开结果，经 onOpenTab）才能解除（R2-02）。
    // 返回栈里的每一页在显示时由 ShellHooks.pageAllowed 重新过守卫。
    DisposableEffect(privacy, navController, routing) {
        selection = tightenedSelection(selection, routing.guardState().copy(privacy = conclusion.raw))
        val unsubscribe =
            privacy.onConclusionChanged(beforeRouting = true) { after ->
                val state = routing.guardState().copy(privacy = after.raw)
                selection =
                    if (after == PrivacyState.BASIC_MODE) {
                        // 基本模式保留已知强更（03 §4.4，R1-01）：清栈后 below_min 仍回到强更页
                        resetToStart(navController, update?.ui?.value?.status)
                        guardedSelection(ShellTab.HOME, state)
                    } else {
                        tightenedSelection(selection, state)
                    }
            }
        onDispose { unsubscribe() }
    }

    // 强更（APP-02c）：进入 below_min → 进强更页（已在强更页或它上面的次要入口时不动）；解除 → 清掉强更页回到起点。
    // 导航栈每次变化也重新同步（R1-01）：任何清栈（基本模式、Tab 点击、守卫清掉旧页）之后，below_min 时强更页始终在最上层
    val updateStatus = updateUi?.status
    val top by navController.currentBackStackEntryAsState()
    LaunchedEffect(updateStatus, top?.id, navController) { syncForceUpdatePage(navController, updateStatus) }

    AppNavHost(
        routing = routing,
        home = {
            if (conclusion == PrivacyState.NOT_DECIDED) {
                LaunchBackdrop()
            } else {
                TabShell(
                    selected = selection.tab,
                    onSelect = { tab -> onTabClick(tab, routing) },
                    tabRoot = { tab ->
                        TabRoot(
                            tab = tab,
                            readonly = selection.readonly,
                            basicMode = conclusion == PrivacyState.BASIC_MODE,
                            registry = registry,
                            routing = routing,
                            onEnableFullFunction = { privacy.requestFullFunction() },
                        )
                    },
                )
            }
        },
        registry = registry,
        navController = navController,
        shellHooks = hooks,
    )

    // 弹窗只在协议页（从弹窗里点开的 Agreement）之外显示：看协议时让出屏幕，返回后重新出现
    val topRoute = top?.takeIf { it.destination.hasRoute(RouteDestination::class) }?.toRoute<RouteDestination>()?.name
    val readingAgreement = topRoute == AGREEMENT_ROUTE
    val showPrompt = privacyState.mustPrompt && !readingAgreement
    BackHandler(enabled = showPrompt) {}
    // 可关闭更新提示：不在首启弹窗、强更页之上弹
    if (update != null) UpdatePromptHost(update, updateUi, hidden = showPrompt || topRoute == RouteNames.FORCE_UPDATE)
    if (showPrompt) {
        PrivacyPromptDialog(
            stage = privacyState.promptStage,
            actions =
                PrivacyPromptActions(
                    onAgree = { privacy.agree() },
                    onDisagree = { privacy.disagree() },
                    onStillDisagree = { privacy.stillDisagree() },
                    onExitApp = {
                        activity?.finishAndRemoveTask()
                        // 进程可能还在：下次进入按冷启动重新判断，从首启弹窗开始
                        privacy.coldStart()
                    },
                    onOpenAgreement = { link -> openAgreement(routing, link) },
                ),
        )
    }
}

/** 选中的 Tab 与守卫给它的只读标记（R1-07）。 */
data class TabSelection(
    val tab: ShellTab,
    val readonly: Boolean,
) {
    companion object {
        val Saver: Saver<TabSelection, Any> =
            listSaver(
                save = { listOf(it.tab.name, it.readonly) },
                restore = { TabSelection(ShellTab.valueOf(it[0] as String), it[1] as Boolean) },
            )
    }
}

/**
 * 按 [state] 用 App 内来源重新判定 [tab]：能打开（含只读）→ 留在该 Tab，只读标记取守卫结论；否则回到首页 Tab
 * （首页不能完整打开时一律只读）。
 */
internal fun guardedSelection(
    tab: ShellTab,
    state: GuardState,
): TabSelection {
    val decision = EntryGuard.decide(inAppRequest(tab.route), state)
    return when (decision.kind) {
        GuardKind.OPEN -> TabSelection(tab, readonly = false)
        GuardKind.OPEN_READONLY -> TabSelection(tab, readonly = true)
        else -> if (tab == ShellTab.HOME) TabSelection(tab, readonly = true) else guardedSelection(ShellTab.HOME, state)
    }
}

/**
 * 恢复的选择（Activity 重建、隐私结论变化）重新过守卫，**只收紧不放宽**（R2-02，03 §4.4）：
 * 守卫不再允许该 Tab → 按 [guardedSelection] 回到首页；仍允许 → 留在该 Tab，只读 = 保存的只读 || 守卫的只读。
 * 状态恢复不是新的原生点击，不能按 IN_APP 重新判定把 Router 自动恢复时给的只读解除。
 */
internal fun tightenedSelection(
    saved: TabSelection,
    state: GuardState,
): TabSelection {
    val guarded = guardedSelection(saved.tab, state)
    return if (guarded.tab == saved.tab) guarded.copy(readonly = guarded.readonly || saved.readonly) else guarded
}

/**
 * 返回栈里的一页现在还能不能显示（R1-06：恢复保存的导航状态、隐私收紧后回到旧页时重新过守卫）：
 * 按 App 内来源重新判定，能完整打开，或应只读且本页就是只读，才显示；否则外壳清栈回到起点。
 */
internal fun stillAllowed(
    page: RouteDestination,
    state: GuardState,
): Boolean {
    val params = runCatching { Json.parseToJsonElement(page.paramsJson).jsonObject }.getOrNull() ?: return false
    val decision = EntryGuard.decide(RouteRequest(page.name, params, RouteSource.IN_APP), state)
    return when (decision.kind) {
        GuardKind.OPEN -> true

        GuardKind.OPEN_READONLY -> page.readonly

        // 现在要补同意、要登录、被拒、要等待：这页不该还在栈里
        else -> false
    }
}

/** Tab 的根页：经路由注册表渲染（Feature 占位页），只读标记取守卫结论；基本模式下首页 Tab 换成基本模式页。 */
@Composable
private fun TabRoot(
    tab: ShellTab,
    readonly: Boolean,
    basicMode: Boolean,
    registry: RoutePageRegistry,
    routing: AppRouting,
    onEnableFullFunction: () -> Unit,
) {
    val destination = RouteDestination(tab.route, EMPTY_PARAMS, readonly = readonly)
    if (tab == ShellTab.HOME && basicMode) {
        BasicModePage(
            onSearch = { routing.router.open(inAppRequest(SEARCH_ROUTE)) },
            onSettings = { routing.router.open(inAppRequest(SETTINGS_ROUTE)) },
            onEnableFullFunction = onEnableFullFunction,
        ) { registry.Render(destination) }
    } else {
        registry.Render(destination)
    }
}

/**
 * 点 Tab：一律交给 Router（R1-02）。只有 OPEN / OPEN_READONLY 才切到该 Tab（Router → 导航钩子 onOpenTab，带只读标记）；
 * 要登录、绑手机、实名 → Router 登记 pending_action 并打开前置页；基本模式下要补同意 → 登记并弹首启弹窗；其余不打开。
 */
private fun onTabClick(
    tab: ShellTab,
    routing: AppRouting,
) {
    routing.router.open(inAppRequest(tab.route))
}

private fun openAgreement(
    routing: AppRouting,
    link: AgreementLink,
) {
    routing.router.open(RouteRequest(AGREEMENT_ROUTE, JsonObject(mapOf("type" to JsonPrimitive(link.type))), RouteSource.IN_APP))
}

internal fun inAppRequest(route: String): RouteRequest = RouteRequest(route, JsonObject(emptyMap()), RouteSource.IN_APP)

internal const val AGREEMENT_ROUTE = "Agreement"
internal const val PRIVACY_CENTER_ROUTE = "PrivacyCenter"
private const val SEARCH_ROUTE = "Search"
private const val SETTINGS_ROUTE = "Settings"
private const val EMPTY_PARAMS = "{}"
