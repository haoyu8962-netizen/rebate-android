package com.couli.app.shell

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.couli.app.agent.AgentRoute
import com.couli.app.agent.AgentWithdrawSettingsRow
import com.couli.app.agent.AppAgent
import com.couli.app.clipboard.AppClipboard
import com.couli.app.clipboard.ClipboardConsentHost
import com.couli.app.clipboard.ClipboardResultHost
import com.couli.app.clipboard.ClipboardSettingsRow
import com.couli.app.clipboard.HomeClipboardArea
import com.couli.app.home.AppHome
import com.couli.app.home.variantHomePreviewPages
import com.couli.app.login.AppLogin
import com.couli.app.login.BindPhoneRoute
import com.couli.app.login.LoginNoticeHost
import com.couli.app.login.LoginRoute
import com.couli.app.login.SettingsRoute
import com.couli.app.me.AppMe
import com.couli.app.me.MeOverlayHost
import com.couli.app.me.MeTabRoute
import com.couli.app.me.meRoutePages
import com.couli.app.orders.AppOrders
import com.couli.app.orders.OrderDetailRoute
import com.couli.app.orders.OrderListRoute
import com.couli.app.privacy.AppPrivacy
import com.couli.app.privacy.toRouterPrivacy
import com.couli.app.routing.AppNavHost
import com.couli.app.routing.AppRouting
import com.couli.app.routing.HomeDestination
import com.couli.app.routing.PlaceholderRoutePage
import com.couli.app.routing.RouteDestination
import com.couli.app.routing.RoutePageRegistry
import com.couli.app.routing.ShellHooks
import com.couli.app.search.AppSearch
import com.couli.app.search.ProductDetailRoute
import com.couli.app.search.SearchRoute
import com.couli.app.trade.AuthManageRoute
import com.couli.app.trade.openAuthManage
import com.couli.app.update.AppUpdate
import com.couli.app.update.ForceUpdateActions
import com.couli.app.update.ForceUpdatePage
import com.couli.app.update.UpdatePromptDialog
import com.couli.app.update.UpdateUi
import com.couli.app.wallet.AppWallet
import com.couli.app.wallet.WALLET_ROUTES
import com.couli.app.wallet.WalletRoute
import com.couli.app.web.AppWeb
import com.couli.app.web.ExternalPageRoute
import com.couli.app.web.H5RoutePage
import com.couli.app.web.WebPageRoute
import com.couli.core.router.EntryGuard
import com.couli.core.router.GuardDecision
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
import com.couli.core.wallet.WalletRoutes
import com.couli.feature.home.homeGrayscale
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
 * @param web 两类 WebView 容器与 JSBridge 的接线（APP-04）；为 null 时 WebPage / ExternalPage 是占位页（只测外壳的单测）。
 * @param clipboard 剪贴板识别（APP-07）：首页提示条与粘贴卡、设置页开关、parse 结果；外壳把「当前前台界面」（回退栈栈顶、选中的 Tab、
 *   基本模式页）同步告诉它。为 null 时都不显示（只测外壳的单测）。
 * @param orders 订单（APP-08）：订单 Tab 根（OrderList）与 OrderDetail；为 null 时是占位页（只测外壳的单测）。
 * @param me 「我的」、设置、隐私中心、关于、封禁说明、申诉与统一客服出口（APP-12）。为 null 时（只测外壳与更早任务的单测）保留更早任务的占位：
 *   「我的」入口行、设置页的退出登录与剪贴板开关、隐私中心占位页。
 * @param agent Agent 页（APP-10）：AgentChat Tab 根与设置页【撤回 AI 服务同意】一行；为 null 时（只测外壳与更早任务的单测）AgentChat 是占位页。
 */
@Composable
@Suppress("CyclomaticComplexMethod") // 外壳按可选接线（强更、桥、登录）逐项组合
fun AppShell(
    routing: AppRouting,
    privacy: AppPrivacy,
    homeContent: @Composable () -> Unit,
    navController: NavHostController = rememberNavController(),
    update: AppUpdate? = null,
    web: AppWeb? = null,
    login: AppLogin? = null,
    search: AppSearch? = null,
    clipboard: AppClipboard? = null,
    orders: AppOrders? = null,
    wallet: AppWallet? = null,
    me: AppMe? = null,
    home: AppHome? = null,
    agent: AppAgent? = null,
) {
    val privacyState by privacy.state.collectAsStateWithLifecycle()
    val conclusion = privacyState.toRouterPrivacy()
    // 选中的 Tab 与它的只读标记都来自守卫 / Router 的结论（R1-07），不由基本模式推导
    var selection by rememberSaveable(stateSaver = TabSelection.Saver) {
        mutableStateOf(guardedSelection(ShellTab.HOME, routing.guardState()))
    }
    val noUpdate = remember { MutableStateFlow<UpdateUi?>(null) }
    val updateUi by (update?.ui ?: noUpdate).collectAsStateWithLifecycle()
    // 可关闭的更新提示此刻显示（剪贴板用途弹层排在它之后，G22）
    val updateDialog = updateUi?.state?.showPrompt == true
    val registry =
        remember(homeContent, update, web, login, search, clipboard, orders, wallet, me, home, agent) {
            RoutePageRegistry(
                mapOf<String, @Composable (RouteDestination) -> Unit>(
                    // 两类 WebView 容器（APP-04）；没有接线（只测外壳的单测）时是占位页
                    RouteNames.WEB_PAGE to { page ->
                        web?.let { WebPageRoute(page, it, routing) { navController.popBackStack() } } ?: PlaceholderRoutePage(page)
                    },
                    RouteNames.EXTERNAL_PAGE to { page ->
                        web?.let { ExternalPageRoute(page, it) { navController.popBackStack() } } ?: PlaceholderRoutePage(page)
                    },
                    ShellTab.HOME.route to { _ -> homeContent() },
                    RouteNames.FORCE_UPDATE to { _ -> update?.let { ForceUpdateRoute(it, routing, me?.account) } },
                    // 强更状态下能到登录页只有一条路：从强更页进入（受限登录，BR-ID-01「受限会话」）
                    RouteNames.LOGIN to { page ->
                        val restricted = routing.update.value == UpdateCheck.BELOW_MIN
                        login?.let { LoginRoute(it, restricted) } ?: PlaceholderRoutePage(page, restrictedLogin = restricted)
                    },
                    // 绑手机（APP-05b）：第三方登录未绑手机时由守卫或绑手机引导打开
                    RouteNames.BIND_PHONE to { page ->
                        login?.let { BindPhoneRoute(it) } ?: PlaceholderRoutePage(page)
                    },
                    // 设置页：接了「我的」（APP-12）时由下面的 meRoutePages 替换；没接时（只测外壳与更早任务的单测）保留 APP-05a 的退出登录与 APP-07 的剪贴板开关
                    SETTINGS_ROUTE to { page ->
                        login?.let {
                            SettingsRoute(it, routing, onBack = { navController.popBackStack() }) {
                                clipboard?.let { board -> ClipboardSettingsSlot(board, privacy) }
                            }
                        } ?: PlaceholderRoutePage(page)
                    },
                    // 搜索与商品详情（APP-06a）；没有接线（只测外壳的单测）时是占位页
                    SEARCH_ROUTE to { page ->
                        search?.let { SearchRoute(it, page) { navController.popBackStack() } } ?: PlaceholderRoutePage(page)
                    },
                    PRODUCT_DETAIL_ROUTE to { page ->
                        search?.let { ProductDetailRoute(it, page) { navController.popBackStack() } } ?: PlaceholderRoutePage(page)
                    },
                    // 订单（APP-08）：订单 Tab 根与订单详情；没有接线（只测外壳的单测）时是占位页
                    ShellTab.ORDERS.route to { page ->
                        orders?.let { OrderListRoute(it, page) } ?: PlaceholderRoutePage(page)
                    },
                    ORDER_DETAIL_ROUTE to { page ->
                        orders?.let { OrderDetailRoute(it, page) { navController.popBackStack() } } ?: PlaceholderRoutePage(page)
                    },
                    // 授权管理（APP-06b，BR-ID-17 细则「授权管理页」）：从「我的」与设置进入（需要登录，守卫负责）
                    AUTH_MANAGE_ROUTE to { page ->
                        search?.trade?.let { AuthManageRoute(it, search) { navController.popBackStack() } } ?: PlaceholderRoutePage(page)
                    },
                    // 隐私中心：接了「我的」（APP-12）时由下面的 meRoutePages 替换（强更受限上下文只给协议、清单与撤回）；没接时保留占位与强更标记（R1-05）
                    PRIVACY_CENTER_ROUTE to { page ->
                        PlaceholderRoutePage(page, fromForceUpdate = routing.update.value == UpdateCheck.BELOW_MIN)
                    },
                ) + h5RoutePages(web, routing) { navController.popBackStack() } +
                    // 钱包、提现、提现记录、收款账号、实名、劳务协议（APP-09）；没有接线（只测外壳的单测）时是占位页
                    walletRoutePages(wallet) { navController.popBackStack() } +
                    // 设置、隐私中心、关于、封禁说明、申诉（APP-12）：接线后替换更早任务的占位
                    (
                        if (me != null) {
                            meRoutePages(
                                me,
                                { navController.popBackStack() },
                                clipboard?.let { board ->
                                    { ClipboardSettingsSlot(board, privacy) }
                                },
                                // 【撤回 AI 服务同意】（APP-10 G18）：只在 Agent 可用的构建与环境、已同意时显示
                                agent?.let { a -> { AgentWithdrawSettingsRow(a) } },
                            )
                        } else {
                            emptyMap()
                        }
                    ) +
                    // HomePreview（APP-11，debug / staging；Release 为空表）：【退出预览】只关发起的这一个回退栈条目（APP-09 第 50 条）
                    variantHomePreviewPages(home, routing) { entryId ->
                        if (entryId == null || navController.currentBackStackEntry?.id == entryId) navController.popBackStack()
                    },
            )
        }
    val hooks =
        remember(privacy, login, search, clipboard, orders, wallet, me, home, agent) {
            ShellHooks(
                tabRoutes = ShellTab.routes,
                onOpenTab = { route, readonly ->
                    ShellTab.ofRoute(route)?.let {
                        routing.navTickets.onTab(it.route)
                        // 剪贴板（APP-07，A-R1-03）：在提交 Tab 切换的同步出口先告知剪贴板（作废首页提示条与首页发起的 parse），再改选中项；
                        // 不等下一次重组的 SideEffect
                        clipboard?.onShell(it.route, privacy.conclusion == PrivacyState.BASIC_MODE)
                        // 「我的」（APP-12 R2-01）：同一同步出口先撤销「我的」这一次进入的读取许可与在途 getMe，再改选中项
                        me?.onTab(it.route)
                        // 首页（APP-11，共性第 22、28 条）：同一同步出口先写目标 Tab（轮播暂停、弹窗等待），再改选中项
                        home?.onTab(it.route)
                        // Agent 页（APP-10，共性第 2 条）：同一同步出口先写目标 Tab（离开即撤销本次进入世代、关连接、停计时），再改选中项
                        agent?.onTab(it.route)
                        selection = TabSelection(it, readonly)
                    }
                },
                // Router 打开 OrderList {scope}：切 Tab 之前把参数交给订单列表（切到对应子 Tab，APP-08）
                onTabParams = { route, params -> if (route == ShellTab.ORDERS.route) orders?.requestScope(params) },
                onRequestConsent = { privacy.requestFullFunction() },
                pageAllowed = { page -> stillAllowedWithUpdate(page, routing.guardState()) },
                onBackStack = { entries ->
                    login?.followBackStack(entries)
                    // 搜索、详情页出栈的同步出口：立即作废其在途请求（APP-06a，APP-05a 共性第 9 条）
                    search?.followBackStack(entries)
                    // 订单详情、发起购买页的待跟单卡出栈的同步出口：立即作废其在途请求（APP-08）
                    orders?.followBackStack(entries)
                    // 钱包类页面出栈的同步出口：立即作废其读请求（APP-09；提交、确认、放弃不取消，未决记录兜底）
                    wallet?.followBackStack(entries)
                    // 「我的」类页面出栈的同步出口：立即作废读请求（APP-12）；导航票据的栈顶（10006 落点，G09）
                    me?.followBackStack(entries)
                    // 首页（APP-11）：首页之上有没有别的页面（可见性）；HomePreview 实例出栈的同步出口
                    home?.followBackStack(entries)
                    // Agent 页（APP-10）：Tab 外壳之上有没有别的页面（被盖住即不可见：关连接、停计时）
                    agent?.followBackStack(entries)
                    // 剪贴板（APP-07）：离开首页根页即作废未点击的提示条与首页发起的 parse；出栈的网页容器的抑制随之失效
                    clipboard?.let { followClipboard(it, entries) }
                },
            )
        }
    val activity = LocalActivity.current
    // 剪贴板（APP-07）：选中的 Tab 与基本模式页的兜底同步（隐私结论变化、恢复保存的选中项等）；用户点 Tab 已在 onOpenTab 同步告知（A-R1-03）
    if (clipboard != null) {
        val basicShell = conclusion == PrivacyState.BASIC_MODE
        // 外壳的组合进出（Activity 重建、进程恢复）：重新同步 Tab 与回退栈之前，冷启动收到的系统分享不处理（FU-CLIP R1-01）。
        // DisposableEffect 先于同一次组合的 SideEffect 执行
        DisposableEffect(clipboard) {
            clipboard.onShellAttached()
            onDispose { clipboard.onShellDetached() }
        }
        SideEffect { clipboard.onShell(selection.tab.route, basicShell) }
        // G22：用途弹层排在可关闭的更新提示与强更之后——它们晚到时用途弹层让位，更新提示关掉后同一轮接着出（G31 修正）
        DisposableEffect(clipboard, update) {
            clipboard.otherDialogShowing = { update?.let { it.ui.value.state.showPrompt } == true }
            onDispose { clipboard.otherDialogShowing = { false } }
        }
        LaunchedEffect(clipboard, updateDialog, updateUi?.status) { clipboard.onDialogsChanged() }
    }

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

    // 「我的」（APP-12）：10006 落点、退出后的落点、只能登录访问的页面在会话换代时出栈，都经这个 NavController；导航票据的选中 Tab
    if (me != null) {
        DisposableEffect(me, navController) {
            me.attach(navController)
            onDispose { me.detach(navController) }
        }
    }
    SideEffect { routing.navTickets.onTab(selection.tab.route) }
    // Agent 页：恢复保存的选中项（Activity 重建、进程恢复）等不经 onOpenTab 的情形兜底同步（用户点 Tab 已在同步出口告知）
    if (agent != null) SideEffect { agent.onTab(selection.tab.route) }
    // APP-10b G32 / G56：agent.entry.visible=false → AI 助手 Tab 换「搜索」、首页【问 AI】隐藏；停在 Agent Tab 时回首页
    val noAgentEntry = remember { MutableStateFlow(true) }
    val agentEntryVisible by (agent?.entryVisibleState ?: noAgentEntry).collectAsStateWithLifecycle()
    if (!agentEntryVisible && selection.tab == ShellTab.AGENT) {
        SideEffect {
            agent?.onTab(ShellTab.HOME.route)
            selection = guardedSelection(ShellTab.HOME, routing.guardState())
        }
    }

    // 钱包（APP-09）：提交成功替换栈顶、收款账号保存后出栈都经这个 NavController
    if (wallet != null) {
        DisposableEffect(wallet, navController) {
            wallet.attach(navController)
            onDispose { wallet.detach(navController) }
        }
    }

    // 登录（APP-05a）：执行器关闭登录页、恢复待办都经这个 NavController
    if (login != null) {
        DisposableEffect(login, navController) {
            login.attach(navController)
            onDispose { login.detach(navController) }
        }
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
                    agentAsSearch = !agentEntryVisible,
                    onSearch = { routing.router.open(inAppRequest(SEARCH_ROUTE)) },
                    tabRoot = { tab ->
                        TabRoot(
                            tab = tab,
                            readonly = selection.readonly,
                            basicMode = conclusion == PrivacyState.BASIC_MODE,
                            registry = registry,
                            routing = routing,
                            onEnableFullFunction = { privacy.requestFullFunction() },
                            clipboard = clipboard,
                            wallet = wallet,
                            me = me,
                            home = home,
                            agent = agent,
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
    // 登录成功后的邀请绑定结果（LoginInviteBound / LoginInviteFailed）：显示在恢复的页面之上
    if (login != null && !showPrompt) LoginNoticeHost(login) { routing.router.open(inAppRequest(ME_ROUTE)) }
    // 剪贴板 parse / 手动粘贴的结果（轻提示、30132【用商品名搜索】、多卡弹层）：只在发起页仍是当前页时显示
    if (clipboard != null && !showPrompt) ClipboardResultHost(clipboard)
    // 剪贴板首次用途说明弹层（FU-AND-0722 R02）：排在首启隐私弹窗、强更、更新提示之后（G22）——它们在时不组合；状态上的让位与同轮恢复见 onDialogsChanged
    val consentNoticeUnblocked = !updateDialog && topRoute != RouteNames.FORCE_UPDATE
    if (clipboard != null && !showPrompt && consentNoticeUnblocked) ClipboardConsentHost(clipboard)
    // 统一客服出口的半屏、撤回同意落盘失败的提示（APP-12）
    if (me != null && !showPrompt) MeOverlayHost(me)
    // APP-13a G01 ③：10007 cooling 想打开不可用的 DeleteAccount → 只提示 error.10007（不进升级页、不导航）
    if (!showPrompt) DeletionPromptHost(routing)
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
    clipboard: AppClipboard?,
    wallet: AppWallet?,
    me: AppMe?,
    home: AppHome?,
    agent: AppAgent?,
) {
    val destination = RouteDestination(tab.route, EMPTY_PARAMS, readonly = readonly)
    // 全局置灰只作用于首页根视图（含固定区与 SDUI 区，03 §6.2 第 8 步，G27）；配置变化即时应用或移除
    val noGray = remember { MutableStateFlow(false) }
    val gray by (home?.grayscale ?: noGray).collectAsStateWithLifecycle()
    if (tab == ShellTab.HOME && basicMode) {
        Box(modifier = Modifier.fillMaxSize().homeGrayscale(gray)) {
            BasicModePage(
                onSearch = { routing.router.open(inAppRequest(SEARCH_ROUTE)) },
                onSettings = { routing.router.open(inAppRequest(SETTINGS_ROUTE)) },
                onEnableFullFunction = onEnableFullFunction,
            ) { registry.Render(destination) }
        }
    } else if (tab == ShellTab.HOME) {
        // 首页 Tab 顶部的原生固定区（G26）：搜索入口与【问 AI】（经 Router 打开 Search / AgentChat）、剪贴板区（APP-07：提示条、粘贴按钮、
        // 「粘贴链接，先查返利」卡），下方是首页 SDUI 区（APP-11）
        Box(modifier = Modifier.fillMaxSize().homeGrayscale(gray)) {
            HomeWithSearch(
                onSearch = { routing.router.open(inAppRequest(SEARCH_ROUTE)) },
                header = { clipboard?.let { HomeClipboardArea(it) } },
                // G56（FU-HM-0715 口径）：入口开关关闭时【问 AI】一并隐藏
                onAskAi =
                    home?.takeIf { agent?.entryVisible() != false }?.let {
                        {
                            routing.router.open(
                                inAppRequest(ShellTab.AGENT.route),
                            )
                        }
                    },
            ) { registry.Render(destination) }
        }
    } else if (tab == ShellTab.AGENT && agent != null) {
        // Agent 页（APP-10）：控制器在 AppAgent 里按身份建立与作废，切 Tab 不销毁对话（只撤销本次进入世代）
        AgentRoute(agent, readonly)
    } else if (tab == ShellTab.ME && me != null) {
        // 「我的」页正文（APP-12）：身份卡、余额卡、宫格（授权管理、设置、钱包都在宫格里）
        MeTabRoute(me)
    } else if (tab == ShellTab.ME) {
        // 没有接「我的」（只测外壳与更早任务的单测）：保留更早任务的入口行（授权管理 A-R1-14、设置、钱包冒烟入口）
        MeWithEntries(
            onAuthManage = { openAuthManage(routing) },
            onSettings = { routing.router.open(inAppRequest(SETTINGS_ROUTE)) },
            // 「我的」页正文属 APP-12：先放【钱包】冒烟入口（APP-09，钱包不是 Tab，是从「我的」进入的普通路由）
            onWallet = {
                wallet?.open(
                    WalletRoutes.WALLET,
                    JsonObject(emptyMap()),
                ) ?: routing.router.open(inAppRequest(WalletRoutes.WALLET))
            },
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

/**
 * 隐私弹窗里的协议链接（只读打开 Agreement）。强更状态下（确需强更时强更页总在最下层，弹窗叠在它上面）带强更来源标记：Router 只放行
 * 从强更页进入的 PrivacyCenter、Agreement、Login、DeleteAccount，不带标记会被重定向回强更页、协议打不开（F-ALL-06，03 §4.1）。
 */
internal fun openAgreement(
    routing: AppRouting,
    link: AgreementLink,
): GuardDecision {
    val state = routing.guardState()
    val fromForceUpdate = routing.update.value == UpdateCheck.BELOW_MIN
    return routing.router.open(
        RouteRequest(AGREEMENT_ROUTE, JsonObject(mapOf("type" to JsonPrimitive(link.type))), RouteSource.IN_APP),
        if (fromForceUpdate) state.copy(fromForceUpdatePage = true) else state,
    )
}

internal fun inAppRequest(route: String): RouteRequest = RouteRequest(route, JsonObject(emptyMap()), RouteSource.IN_APP)

internal const val AGREEMENT_ROUTE = "Agreement"
internal const val PRIVACY_CENTER_ROUTE = "PrivacyCenter"
private const val SEARCH_ROUTE = "Search"
private const val PRODUCT_DETAIL_ROUTE = "ProductDetail"
private const val SETTINGS_ROUTE = "Settings"
private const val AUTH_MANAGE_ROUTE = "AuthManage"
private const val ORDER_DETAIL_ROUTE = "OrderDetail"
private const val ME_ROUTE = "Me"
private const val EMPTY_PARAMS = "{}"

/** 设置页的剪贴板开关一行（APP-07）：只在已同意隐私政策时显示（游客也显示）；基本模式、撤回同意后不显示。 */
@Composable
private fun ClipboardSettingsSlot(
    clipboard: AppClipboard,
    privacy: AppPrivacy,
) {
    val state by privacy.state.collectAsStateWithLifecycle()
    if (state.toRouterPrivacy() == PrivacyState.CONSENTED) ClipboardSettingsRow(clipboard)
}

/** 回退栈 → 剪贴板的「当前前台界面」：栈顶路由名（起点为 null）、栈顶条目与全部条目（主线程、同一条消息）。 */
internal fun followClipboard(
    clipboard: AppClipboard,
    entries: List<NavBackStackEntry>,
) {
    val pages = entries.filter { it.destination !is NavGraph }
    val top = pages.lastOrNull() ?: return
    val name =
        when {
            top.destination.hasRoute(HomeDestination::class) -> null
            top.destination.hasRoute(RouteDestination::class) -> top.toRoute<RouteDestination>().name
            else -> UPGRADE_PAGE
        }
    clipboard.onBackStack(name, top.id, pages.map { it.id }.toSet())
}

/** 升级提示页（没有路由名，TODO(N-14)）。 */
private const val UPGRADE_PAGE = "UpgradePrompt"

/**
 * routes.json 里 kind=h5 的路由（FindOrder、Ledger、Help 等）→ 可信容器按 h5_base_url + h5_path 加载（[H5RoutePage]）；没有接线（只测外壳的单测）
 * 时是占位页。
 */
private fun walletRoutePages(
    wallet: AppWallet?,
    onBack: () -> Unit,
): Map<String, @Composable (RouteDestination) -> Unit> =
    WALLET_ROUTES.associateWith { _ ->
        { page: RouteDestination ->
            if (wallet != null) {
                val entryId = (LocalLifecycleOwner.current as? NavBackStackEntry)?.id
                WalletRoute(wallet, page, entryId, onBack)
            } else {
                PlaceholderRoutePage(page)
            }
        }
    }

private fun h5RoutePages(
    web: AppWeb?,
    routing: AppRouting,
    onClose: () -> Unit,
): Map<String, @Composable (RouteDestination) -> Unit> =
    com.couli.generated.Routes.all.values
        .filter { it.kind == com.couli.generated.RouteKind.H5 }
        .associate { spec ->
            spec.name to { page: RouteDestination ->
                web?.let { H5RoutePage(page, spec, it, routing, onClose) } ?: PlaceholderRoutePage(page)
            }
        }

/** 10007 冷静期提示（G01 ③）：路由层拦下不可用的 DeleteAccount 后，在当前界面轻提示一次 error.10007。 */
@Composable
internal fun DeletionPromptHost(routing: AppRouting) {
    val id by routing.deletionPrompt.collectAsStateWithLifecycle()
    var shown by androidx.compose.runtime.saveable
        .rememberSaveable { androidx.compose.runtime.mutableLongStateOf(0L) }
    if (id == 0L || id == shown) return
    androidx.compose.runtime.LaunchedEffect(id) {
        kotlinx.coroutines.delay(DELETION_PROMPT_MS)
        shown = id
    }
    androidx.compose.foundation.layout.Box(
        modifier =
            androidx.compose.ui.Modifier
                .fillMaxSize(),
        contentAlignment = androidx.compose.ui.Alignment.TopCenter,
    ) {
        com.couli.core.designsystem.CouliToast(
            message =
                com.couli.generated.DefaultTexts
                    .text("error.10007")
                    .orEmpty(),
            icon = null,
            announcementId = id,
            modifier =
                androidx.compose.ui.Modifier
                    .padding(top = com.couli.core.designsystem.CouliSpacing.space12)
                    .testTag(DELETION_PROMPT_TAG),
        )
    }
}

/** 10007 提示的测试标记。 */
const val DELETION_PROMPT_TAG = "deletion_prompt_10007"

private const val DELETION_PROMPT_MS = 2_000L
