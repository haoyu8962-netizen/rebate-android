package com.couli.feature.web

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.couli.core.bridge.BridgeHost
import com.couli.core.bridge.BridgeUi
import com.couli.core.bridge.ExternalNavDecision
import com.couli.core.bridge.ExternalNavPolicy
import com.couli.core.bridge.TrustedAuthSync
import com.couli.core.clientcore.ClientEvent
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliIconButton
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.ErrorState
import com.couli.core.designsystem.StateAction
import com.couli.generated.DefaultTexts
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 页面标记（UI 测试用）。 */
const val TRUSTED_WEB_TAG = "trusted_web_page"
const val EXTERNAL_WEB_TAG = "external_web_page"
const val EXTERNAL_SEARCH_TAG = "external_web_search"
const val TITLE_BAR_TAG = "web_title_bar"
const val CLOSE_TAG = "web_close"
const val FULLSCREEN_TAG = "external_web_fullscreen"
const val BACK_TAG = "web_back"
private const val TRUSTED_HOLDER_KEY = "couli_trusted_web"
private const val EXTERNAL_HOLDER_KEY = "couli_external_web"
private const val BACK_LABEL_KEY = "external_page.back"

private fun text(key: String): String = DefaultTexts.text(key).orEmpty()

/** 可信容器一个路由实例保留的状态（[WebContainerHolder]）。 */
internal class TrustedWebHolder : WebContainerHolder() {
    /** ui.setNavBar 的全部参数（第 23 条）；H5 设过标题后不再用页面 <title> 覆盖。 */
    val navBar = NavBarState()
    var pageTitle by mutableStateOf("")
    var loading by mutableStateOf<String?>(null)
    var controller by mutableStateOf<TrustedWebViewController?>(null)
    var onOpenExternal: (String) -> Unit = {}

    /** 当前这份目标的可信页面槽位（F-X-25 v2：切号时槽位不变，里面的 WebView 与控制器换新）。 */
    var page: TrustedWebPage? = null

    /** 正在第一次建页面（组合里）：这时交来的 WebView 直接由 AndroidView 取走，不必再推进代次。 */
    private var creating = false

    /** 当前这份 WebView 用的地址与可信配置（第一次进入组合时取；首次没加载成、重试时换成重新生成的一份，R3-03）。 */
    var target: TrustedWebTarget? = null

    /** 当前 WebView 是否已按可信源开始加载过（没有的话重试要换新地址与配置重建，而不是 reload 空页）。 */
    var loaded = false

    /**
     * WebView 的代次：重试时换新地址与配置要重建页面；切号时页面槽位拆掉旧 WebView、在新存储上新建（被盖住时在后台建好）。代次一变
     * AndroidView 随之重建，挂上槽位此刻的那份 WebView（拆后到建前是空白占位）。
     */
    var generation by mutableIntStateOf(0)

    /** 取这个实例的页面槽位（第一次时用 [create] 建）。 */
    fun obtainPage(
        context: Context,
        create: (Context) -> TrustedWebPage,
    ): TrustedWebPage {
        val wrapped = contextFor(context)
        page?.let { return it }
        creating = true
        try {
            return create(wrapped).also { page = it }
        } finally {
            creating = false
        }
    }

    /** 槽位此刻要显示的视图：当前 WebView（先从上一次组合的位置摘下），没有时是空白占位。 */
    fun currentView(context: Context): View = page?.webView?.also { it.detach() } ?: View(context)

    /** 槽位换了 WebView（切号新建、拆除）：控制器跟着换，界面按新代次重挂。 */
    fun onPageChanged(current: TrustedWebPage) {
        if (page !== current) return
        controller = current.controller
        if (!creating) generation += 1
    }

    /** 换一份 [fresh] 目标重建页面（拆掉旧的；不是出栈拆除，容器实例仍在）。 */
    fun rebuild(fresh: TrustedWebTarget) {
        target = fresh
        controller = null
        loaded = false
        replaceWebView()
        page = null
        generation += 1
    }
}

/** 可信容器一次加载的目标：地址与可信配置（H5 路由的地址由生效配置拼出，重试时重新生成，R3-03）。 */
data class TrustedWebTarget(
    val url: String,
    val config: TrustedWebConfig,
)

/**
 * WebPage：可信容器页面（原生标题栏，H5 可经 ui.setNavBar 改标题与显隐）。[hostFactory] 用容器的 [BridgeUi] 和容器实例自己的协程
 * 作用域（随路由出栈取消，被盖住时不取消）建宿主；返回 null 时不注入桥。地址不在可信源里时不加载，直接显示错误页。
 *
 * WebView 始终在同一个组合位置（R1-04、共性第 36 条）：immersive 只改内容区的上边距（标题栏浮在页面上或占位），错误页与加载提示
 * 都是盖在 WebView 上的覆盖层，切换时不重建 WebView、不丢地址、历史与表单。页面槽位（[TrustedWebPage]）与其 WebView、控制器归
 * [TrustedWebHolder]，出栈时拆除。
 */
@Composable
@Suppress("CyclomaticComplexMethod", "LongMethod", "LongParameterList") // 标题栏的各个 setNavBar 参数、覆盖层与容器实例的保留
fun TrustedWebScreen(
    url: String,
    config: TrustedWebConfig,
    hostFactory: (BridgeUi, CoroutineScope) -> BridgeHost?,
    onOpenExternal: (String) -> Unit,
    onClose: () -> Unit,
    /**
     * 【重试】时重新读取生效配置、重新生成地址（R3-03、共性第 29 条）；null 为沿用 [url] 与 [config]。首次没加载成的，用新的一份重建
     * WebView 并明确加载新地址；已加载过的照旧 reload。https、白名单与桥的可信源仍按新配置校验。
     */
    retarget: (() -> TrustedWebTarget)? = null,
    /**
     * 切号协调器（F-X-25）：页面槽位建好即登记（被盖住、退出组合不注销，随容器出栈拆除才注销）。生产的可信会话容器必须传 App 的那一份；
     * 身份未比对或存在待清理时首次加载由它在存储就绪后发起，补清失败显示错误页，【重试】先补清再加载。
     */
    authSync: TrustedAuthSync? = null,
    /**
     * 可信 WebView 工厂（F-X-25 D10-310 (6)）：生产的可信会话容器必须传 App 的那一份（建好即挂在当前可信存储上）；null 为默认配置文件
     * （不涉及会话的独立容器、既有测试，WebView 由 [LocalWebViewFactory] 建）。
     */
    webViews: TrustedWebViewFactory? = null,
) {
    val holder: TrustedWebHolder = viewModel(key = TRUSTED_HOLDER_KEY)
    if (holder.target == null) holder.target = TrustedWebTarget(url, config)
    BindToBackStackEntry(holder)
    holder.onClose = onClose
    holder.onOpenExternal = onOpenExternal
    val navBar = holder.navBar
    val base = LocalWebViewFactory.current
    // 会话容器（交了协调器）必须同时交可信工厂，不静默落回默认配置文件（评审 v2 r1 S2-06）
    require((authSync == null) == (webViews == null)) { "TrustedWebScreen: authSync and webViews must be passed together" }
    val factory = webViews ?: remember(base) { TrustedWebViewFactory.defaultProfile(base) }
    BackHandler { holder.controller?.takeIf { it.canGoBack() }?.goBack() ?: holder.close() }
    LifecycleEvents(onResume = { holder.controller?.onResume() }, onPause = { holder.controller?.onPause() })
    StatusBarAppearance(navBar.statusBarStyle)
    LaunchedEffect(holder.controller, navBar.bounce) { navBar.bounce?.let { holder.controller?.setBounce(it) } }
    val insets = if (navBar.hideBottomSafeArea) WindowInsets.safeDrawing.only(TOP_AND_SIDES) else WindowInsets.safeDrawing
    val density = LocalDensity.current
    var barHeight by remember { mutableStateOf(0.dp) }
    val contentTop = if (navBar.visible && !navBar.immersive) barHeight else 0.dp
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(CouliColors.backgroundCanvas)
                .windowInsetsPadding(insets)
                .testTag(TRUSTED_WEB_TAG),
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(top = contentTop)) {
            if (!holder.tornDown) {
                key(holder.generation) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            val target = holder.target ?: TrustedWebTarget(url, config)
                            holder.obtainPage(context) { wrapped ->
                                createTrustedPage(wrapped, holder, target, hostFactory, factory, authSync)
                            }
                            holder.currentView(context)
                        },
                    )
                }
            }
            if (holder.failed) {
                ErrorState(
                    modifier = Modifier.fillMaxSize().background(CouliColors.backgroundCanvas),
                    title = text("h5.load_failed"),
                    retry =
                        StateAction(text("external_page.retry")) { retry(holder, retarget) },
                )
            }
            holder.loading?.let { LoadingOverlay(it) }
        }
        if (navBar.visible) {
            // 沉浸：页面铺到标题栏下面，标题栏浮在页面之上（缺省透明，H5 可经 bg_color / bg_gradient 着色）；非沉浸时内容区让出标题栏高度
            Box(modifier = Modifier.fillMaxWidth().onSizeChanged { barHeight = with(density) { it.height.toDp() } }) {
                TitleRow(
                    title = navBar.title ?: holder.pageTitle,
                    subtitle = null,
                    onClose = holder::close.takeUnless { navBar.hideClose },
                    background = navBar.background.takeIf { it.isNotEmpty() } ?: if (navBar.immersive) emptyList() else DEFAULT_BAR,
                    textColor = navBar.textColor ?: CouliColors.textPrimary,
                )
            }
        }
    }
}

/** 建这个实例的页面槽位（只在第一次进入组合时）：WebView 与可信控制器由槽位经 [webViews] 建；拆除动作登记进 [holder]。 */
@Suppress("LongParameterList")
private fun createTrustedPage(
    context: Context,
    holder: TrustedWebHolder,
    target: TrustedWebTarget,
    hostFactory: (BridgeUi, CoroutineScope) -> BridgeHost?,
    webViews: TrustedWebViewFactory,
    authSync: TrustedAuthSync?,
): TrustedWebPage {
    val url = target.url
    val config = target.config
    val ui =
        ContainerUi(
            toast = { message, long -> Toast.makeText(context, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show() },
            loading = { holder.loading = it },
            navBar = holder.navBar::apply,
            close = holder::close,
        )
    val callbacks =
        object : TrustedWebCallbacks {
            override fun onOpenExternal(url: String) = holder.onOpenExternal(url)

            override fun onLoadFailed() {
                holder.failed = true
            }

            override fun onTitle(title: String) {
                holder.pageTitle = title
            }

            override fun onAuthRecovered() {
                // 补清成功、已在新存储上建好新账号的文档：收起补清失败时的错误页
                holder.failed = false
                holder.loaded = true
            }
        }
    var created: TrustedWebPage? = null
    val page =
        TrustedWebPage(context, webViews, config, { hostFactory(ui, holder.viewModelScope) }, callbacks, authSync) {
            created?.let(holder::onPageChanged)
        }
    created = page
    page.onRetired = { holder.onPageChanged(page) }
    holder.page = page
    holder.controller = page.controller
    holder.onTeardown(
        invalidate = {
            page.webView?.let { web -> (web.parent as? android.view.ViewGroup)?.removeView(web) }
            page.invalidateCalls()
        },
        destroy = page::destroy,
    )
    holder.failed = !config.allows(url)
    holder.loaded = page.load(url)
    holder.controller = page.controller
    return page
}

/**
 * 【重试】（R3-03、F-AND-26）：每次都按重新读取的生效配置生成新的一份（[retarget]）并与当前这份比较——
 * - 首次没加载成的（地址拼不出、可信源还没下发），或已发起加载但新配置的地址 / 可信配置变了（如 LKG 的旧基址打不开、随后新配置换了基址），
 *   按新的一份校验后重建 WebView 与控制器并明确加载新地址（桥的可信源规则在建控制器时装上）；
 * - 地址与可信配置都没变的，按当前这份再判一次后 reload。
 * 新的一份不可信（不是 https、不在白名单）时继续显示加载失败，不加载。
 * 切号隔离中的（补清 / 身份比对失败，F-X-25 I3）：先交协调器补清，成功后由它新建并加载入口地址；不在这里直接加载或重建。
 */
private fun retry(
    holder: TrustedWebHolder,
    retarget: (() -> TrustedWebTarget)?,
) {
    val blocked = holder.page?.takeIf { it.authBlocked }
    if (blocked != null) {
        holder.failed = false
        blocked.retryAuth()
    } else {
        retryLoad(holder, retarget)
    }
}

private fun retryLoad(
    holder: TrustedWebHolder,
    retarget: (() -> TrustedWebTarget)?,
) {
    val current = holder.target ?: return
    val fresh = retarget?.invoke()
    if (fresh != null && (!holder.loaded || fresh != current)) {
        holder.failed = !fresh.config.allows(fresh.url)
        holder.rebuild(fresh)
        return
    }
    holder.failed = !current.config.allows(current.url)
    if (!holder.failed) holder.controller?.reload()
}

/** 页面交来的全屏视图（WebChromeClient.onShowCustomView）与退出回调。 */
internal class FullscreenView(
    val view: View,
    val exit: () -> Unit,
)

/** 全屏期间隐藏系统栏（可滑出），退出后恢复。 */
@Composable
private fun SystemBarsHidden(hidden: Boolean) {
    val view = LocalView.current
    DisposableEffect(hidden, view) {
        val window = view.context.findActivity()?.window
        if (!hidden || window == null) {
            onDispose { }
        } else {
            val insets = WindowCompat.getInsetsController(window, view)
            insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insets.hide(WindowInsetsCompat.Type.systemBars())
            onDispose { insets.show(WindowInsetsCompat.Type.systemBars()) }
        }
    }
}

/** 状态栏图标深浅（ui.setNavBar status_bar_style：light = 浅色图标、dark = 深色图标）；离开页面时恢复。 */
@Composable
private fun StatusBarAppearance(style: String?) {
    val view = LocalView.current
    DisposableEffect(style, view) {
        val window = view.context.findActivity()?.window
        if (style == null || window == null) {
            onDispose { }
        } else {
            val insets = WindowCompat.getInsetsController(window, view)
            val before = insets.isAppearanceLightStatusBars
            insets.isAppearanceLightStatusBars = style == STYLE_DARK
            onDispose { insets.isAppearanceLightStatusBars = before }
        }
    }
}

internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private const val STYLE_DARK = "dark"
private const val DOWNLOAD_BLOCKED = "external_page.download_blocked"
private const val DOWNLOAD_UNSUPPORTED = "external_page.download_unsupported"
private val TOP_AND_SIDES = WindowInsetsSides.Top + WindowInsetsSides.Horizontal
private val DEFAULT_BAR: List<Color>
    @androidx.compose.runtime.Composable @androidx.compose.runtime.ReadOnlyComposable
    get() = listOf(CouliColors.backgroundCanvas)

/** 第三方页容器一个路由实例保留的状态（[WebContainerHolder]）。 */
internal class ExternalWebHolder : WebContainerHolder() {
    var host by mutableStateOf("")
    var browserExit by mutableStateOf(false)
    var notice by mutableStateOf<String?>(null)

    /** 平台页面被拦时的提示带「去搜索」（BR-ATTR-29 ②(b)、共性第 21、31 条）：主框架、子框架、新窗口、初始地址被拦都提供。 */
    var searchOffered by mutableStateOf(false)
    var controller by mutableStateOf<ExternalWebViewController?>(null)

    /** 页面的全屏请求（第 25、42 条）：页面交来的全屏视图与退出回调；标题栏不因此隐藏。 */
    var fullscreen by mutableStateOf<FullscreenView?>(null)
    var onProductIntent: (url: String, platform: String?, showUnresolved: () -> Unit) -> Unit = { _, _, _ -> }
    var browserAvailable: Boolean = true

    /** 页内返回（标题栏左侧与系统返回共用）：网页有历史先后退，没有再关闭容器（03 §5.1、共性第 11、38 条）。 */
    fun back() {
        val current = controller
        if (current != null && current.canGoBack()) current.goBack() else close()
    }
}

/**
 * ExternalPage：第三方页容器页面。标题栏左侧【返回】（有网页历史先后退，没有就关闭容器），中间固定显示当前主框架的主机名
 * （[subtitle] 只作副标题），右侧【关闭】直接关闭整个容器（R1-08、共性第 38 条，03 §5.1）；【在浏览器打开】只在 can_open_in_browser
 * 时显示，点击时交出当前主框架地址（再判一次）。拦下、下载、商品识别失败的提示画在容器自己的提示层，不画进页面。
 * WebView 始终在同一个组合位置，错误页是覆盖层（R1-04）；WebView 与控制器归 [ExternalWebHolder]，出栈时拆除（共性第 28 条）。
 */
@Composable
@Suppress("LongParameterList", "CyclomaticComplexMethod", "LongMethod") // 判定、规则、开关、埋点逐项注入；提示、全屏、配色分支
fun ExternalWebScreen(
    url: String,
    subtitle: String?,
    navStyle: String?,
    policy: ExternalNavPolicy,
    linkPatterns: () -> JsonObject?,
    flags: () -> ExternalNavFlags,
    track: (ClientEvent) -> Unit,
    appVersion: String,
    onProductIntent: (url: String, platform: String?, showUnresolved: () -> Unit) -> Unit,
    onOpenBrowser: (String) -> Unit,
    onSearch: () -> Unit,
    onClose: () -> Unit,
    browserAvailable: Boolean = true,
) {
    val holder: ExternalWebHolder = viewModel(key = EXTERNAL_HOLDER_KEY)
    BindToBackStackEntry(holder)
    holder.onClose = onClose
    holder.onProductIntent = onProductIntent
    holder.browserAvailable = browserAvailable
    val factory = LocalWebViewFactory.current
    BackHandler {
        val current = holder.fullscreen
        if (current != null) current.exit() else holder.back()
    }
    SystemBarsHidden(holder.fullscreen != null)
    // nav_style（第 24 条，03 §5.1）：标题栏配色 light（缺省）/ dark；不能隐藏标题栏、主机名、返回与关闭
    val dark = navStyle == STYLE_DARK
    val barText = if (dark) CouliColors.textInverse else CouliColors.textPrimary
    Box(modifier = Modifier.fillMaxSize().background(CouliColors.backgroundCanvas).testTag(EXTERNAL_WEB_TAG)) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            // 标题栏（主机名、返回、关闭）始终在（共性第 42 条）：onShowCustomView 分不出是视频全屏还是任意元素（canvas、div）的
            // 全屏请求，第三方页又不能注入脚本去关 Fullscreen API，所以保守地对任何全屏请求都不隐藏标题栏，全屏内容只铺在标题栏下方
            run {
                TitleRow(
                    title = holder.host,
                    subtitle = subtitle,
                    onClose = holder::close,
                    background = listOf(if (dark) CouliColors.textPrimary else CouliColors.backgroundCanvas),
                    textColor = barText,
                    secondaryColor = if (dark) CouliColors.textInverse else CouliColors.textSecondary,
                    onBack = holder::back,
                ) {
                    if (holder.browserExit) {
                        TextButton(onClick = { holder.controller?.browserTarget()?.let(onOpenBrowser) }) {
                            Text(
                                text = text("external_page.open_in_browser"),
                                style = CouliTypography.footnote,
                                color = if (dark) CouliColors.textInverse else CouliColors.textLink,
                            )
                        }
                    }
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (!holder.tornDown) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            holder.obtain(context) { wrapped ->
                                val deps = ExternalDeps(policy, linkPatterns, flags, track, appVersion)
                                createExternalWebView(wrapped, holder, url, deps, factory)
                            }
                        },
                    )
                }
                if (holder.failed) {
                    ErrorState(
                        modifier = Modifier.fillMaxSize().background(CouliColors.backgroundCanvas),
                        title = text("external_page.load_failed"),
                        retry =
                            StateAction(text("external_page.retry")) {
                                holder.failed = false
                                holder.controller?.retry(url)
                            },
                    )
                }
                holder.fullscreen?.let { current ->
                    AndroidView(
                        modifier = Modifier.fillMaxSize().background(Color.Black).testTag(FULLSCREEN_TAG),
                        factory = { current.view },
                    )
                }
                holder.notice?.let { message ->
                    NoticeLayer(
                        message = message,
                        onSearch = onSearch.takeIf { holder.searchOffered },
                        onDismiss = { holder.notice = null },
                    )
                }
            }
        }
    }
}

/** ExternalWebViewController 的判定、规则、开关与埋点。 */
private class ExternalDeps(
    val policy: ExternalNavPolicy,
    val linkPatterns: () -> JsonObject?,
    val flags: () -> ExternalNavFlags,
    val track: (ClientEvent) -> Unit,
    val appVersion: String,
)

/** 建这个实例的 WebView 与第三方页控制器（只在第一次进入组合时）；拆除动作登记进 [holder]。 */
private fun createExternalWebView(
    context: Context,
    holder: ExternalWebHolder,
    url: String,
    deps: ExternalDeps,
    factory: (Context) -> WebView,
): WebView =
    factory(context).also { webView ->
        val callbacks =
            object : ExternalWebCallbacks {
                override fun onBlocked(
                    decision: ExternalNavDecision,
                    frame: NavFrame,
                ) {
                    // 主框架、子框架、新窗口被拦都给同一条提示与「去搜索」，原页面不动；重复的合并成一条（第 31 条）
                    holder.notice = text("external_page.union_host_blocked")
                    holder.searchOffered = true
                }

                override fun onProductIntent(
                    url: String,
                    platform: String?,
                ) = holder.onProductIntent(url, platform) {
                    holder.notice = text("external_page.product_unresolved")
                    holder.searchOffered = false
                }

                override fun onMainFrameChanged(
                    host: String,
                    canOpenInBrowser: Boolean,
                ) {
                    holder.host = host
                    // 本机没有能确定是浏览器的 App 时不提供这个出口（APP-04 三端共性第 9 条）
                    holder.browserExit = canOpenInBrowser && holder.browserAvailable
                }

                override fun onLoadFailed() {
                    holder.failed = true
                }

                override fun onDownloadBlocked() {
                    holder.searchOffered = false
                    holder.notice = text(if (holder.browserExit) DOWNLOAD_BLOCKED else DOWNLOAD_UNSUPPORTED)
                }

                override fun onUnavailable() {
                    holder.failed = true
                }

                override fun onEnterFullscreen(
                    view: View,
                    exit: () -> Unit,
                ) {
                    holder.fullscreen = FullscreenView(view, exit)
                }

                override fun onExitFullscreen() {
                    holder.fullscreen = null
                }
            }
        val controller =
            ExternalWebViewController(webView, deps.policy, deps.linkPatterns, deps.flags, deps.track, callbacks, deps.appVersion)
        holder.controller = controller
        holder.onTeardown(invalidate = webView::stopLoading, destroy = controller::destroy)
        controller.open(url)
    }

@Composable
@Suppress("LongParameterList")
private fun TitleRow(
    title: String,
    subtitle: String?,
    onClose: (() -> Unit)?,
    background: List<Color>,
    textColor: Color,
    secondaryColor: Color = CouliColors.textSecondary,
    onBack: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    val fill =
        when (background.size) {
            0 -> Modifier
            1 -> Modifier.background(background.first())
            else -> Modifier.background(Brush.horizontalGradient(background))
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(fill)
                .testTag(TITLE_BAR_TAG)
                .padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        onBack?.let { back ->
            // TODO(N-22)：文案字典没有「返回」的读屏名称键；图标按钮本身有按钮角色
            CouliIconButton(
                icon = CouliIcons.back,
                label = text(BACK_LABEL_KEY),
                onClick = back,
                modifier = Modifier.testTag(BACK_TAG),
                tint = textColor,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = CouliTypography.title, color = textColor, maxLines = 1)
            subtitle?.let { Text(text = it, style = CouliTypography.footnote, color = secondaryColor, maxLines = 1) }
        }
        trailing()
        onClose?.let { close ->
            TextButton(modifier = Modifier.testTag(CLOSE_TAG), onClick = close) {
                Text(text = text("external_page.close"), style = CouliTypography.footnote, color = textColor)
            }
        }
    }
}

/**
 * 容器自己的提示层。[onSearch] 不为 null 时提示文字本身就是「去搜索」的入口（文案字典里还没有单独的按钮键，TODO(N-22)；
 * external_page.union_host_blocked 的原文已经说明「可以在 App 内搜索商品」），点了经 Router 打开搜索页。
 */
@Composable
private fun NoticeLayer(
    message: String,
    onSearch: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(CouliColors.backgroundSurface)
                .padding(CouliSpacing.space4),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        if (onSearch == null) {
            Text(text = message, style = CouliTypography.body, color = CouliColors.textPrimary)
        } else {
            TextButton(modifier = Modifier.testTag(EXTERNAL_SEARCH_TAG), onClick = onSearch) {
                Text(text = message, style = CouliTypography.body, color = CouliColors.textLink)
            }
        }
        TextButton(onClick = onDismiss) {
            Text(text = text("external_page.close"), style = CouliTypography.footnote, color = CouliColors.textPrimary)
        }
    }
}

@Composable
private fun LoadingOverlay(message: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = CouliColors.brandPrimary)
        if (message.isNotEmpty()) Text(text = message, style = CouliTypography.footnote, color = CouliColors.textSecondary)
    }
}

@Composable
private fun LifecycleEvents(
    onResume: () -> Unit,
    onPause: () -> Unit,
) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> onResume()
                    Lifecycle.Event.ON_PAUSE -> onPause()
                    else -> Unit
                }
            }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

/** 容器自身的界面能力（ui.toast、ui.showLoading / hideLoading、ui.setNavBar、nav.close）。 */
private class ContainerUi(
    private val toast: (String, Boolean) -> Unit,
    private val loading: (String?) -> Unit,
    private val navBar: (JsonObject) -> Boolean,
    private val close: () -> Unit,
) : BridgeUi {
    override fun toast(
        text: String,
        long: Boolean,
    ) = toast.invoke(text, long)

    override fun showLoading(text: String?) = loading(text.orEmpty())

    override fun hideLoading() = loading(null)

    override fun setNavBar(params: JsonObject) = navBar(params)

    override fun close() = close.invoke()
}
