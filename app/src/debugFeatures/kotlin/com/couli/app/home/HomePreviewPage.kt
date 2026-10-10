package com.couli.app.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import com.couli.app.debug.DebugPanel
import com.couli.app.debug.rememberEnvironmentSwitcher
import com.couli.app.routing.AppRouting
import com.couli.app.routing.RouteDestination
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonVariant
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliSheet
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.ErrorState
import com.couli.core.designsystem.StateAction
import com.couli.core.home.HomeDropped
import com.couli.core.home.HomeErrors
import com.couli.core.home.HomeIdentity
import com.couli.core.home.HomeJson
import com.couli.core.home.HomePreviewPolicy
import com.couli.core.home.RouteTarget
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallOwnership
import com.couli.core.network.PageApi
import com.couli.core.network.ResultSignals
import com.couli.core.network.percentEncode
import com.couli.core.router.RouteRequest
import com.couli.core.router.RouteSource
import com.couli.feature.home.HomeController
import com.couli.feature.home.HomeHost
import com.couli.feature.home.HomeListKeys
import com.couli.feature.home.HomeSduiScreen
import com.couli.feature.home.HomeSignals
import com.couli.generated.DefaultTexts
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** HomePreview 的唯一标记（release-scan 查；Release 制品里不得出现）。 */
internal const val HOME_PREVIEW_MARKER = "COULI_HOME_PREVIEW_APP11_7e2c4b"

private const val RETRY_KEY = "external_page.retry"

/** 预览页的测试标记。 */
internal object HomePreviewTags {
    const val PAGE = "home_preview_page"
    const val BAR = "home_preview_bar"
    const val EXIT = "home_preview_exit"
    const val EXPIRED = "home_preview_expired"
    const val SWITCH_ENV = "home_preview_switch_env"
    const val ERROR = "home_preview_error"
    const val INVALIDATED = "home_preview_invalidated"
    const val DROPPED = "home_preview_dropped"
    const val LOADING = "home_preview_loading"
    const val STATE = "home_preview_state"
    const val EXPIRED_EXIT = "home_preview_expired_exit"
}

/**
 * HomePreview 的文字（G24 ⑧）：只在调试源码集，不进用户侧字典；照 HomePreview / HomePreviewExpired 稿，「已作废」与错误态标题代拟。
 */
internal object HomePreviewTexts {
    const val BAR_TITLE = "草稿预览 · 仅测试包"
    const val EXIT = "退出预览"
    const val EXPIRED_TITLE = "换不到草稿"
    const val SWITCH_ENV = "切换环境"
    const val ERROR_TITLE = "加载失败"
    const val INVALIDATED = "预览已失效：会话、隐私或环境已变化，请退出后重新扫码"
    const val DROPPED_TITLE = "被丢弃的卡"
    const val CLOSE = "关闭"

    fun barLine(
        pageKey: String,
        env: String,
        version: Int?,
        validUntil: String?,
    ): String = listOfNotNull("页面 $pageKey", "环境 $env", version?.let { "草稿版本 v$it" }, validUntil?.let { "$it 前有效" }).joinToString(" · ")

    fun expiredBody(env: String): String = "预览令牌已过期（10 分钟内有效），或二维码不是在当前环境 $env 生成的"

    const val EXPIRED_TIP_1 = "过期了：在后台重新生成二维码，用手机相机再扫一次"
    const val EXPIRED_TIP_2 = "环境不对：先切到生成二维码的那个环境，再扫一次"
}

/** 预览实例的版式。 */
internal sealed interface PreviewScreen {
    data object Loading : PreviewScreen

    data class Draft(
        val controller: HomeController,
        val version: Int,
        val validUntil: String,
    ) : PreviewScreen

    data object Expired : PreviewScreen

    data class Failed(
        val message: String,
    ) : PreviewScreen

    data object Invalidated : PreviewScreen
}

/**
 * 一个 HomePreview 导航实例（G24，共性第 11–13 条）：创建时固定完整身份与导航实例票据（回退栈条目 id），之后只比较；每个实例各有自己的模型、控制器与
 * previewPage 请求，不共享静态可变状态。用**当前环境**调 previewPage（不因扫码切换环境）；token 不进日志、埋点、诊断、截图文字（这里只放进请求参数）。
 * 出栈在回退栈的同步出口里作废（[leave]）：取消 previewPage、作废控制器（图片回调、弹窗随之作废）。会话、隐私或环境变了 → 立即作废，只留【退出预览】。
 */
@Suppress("LongParameterList") // 固定身份的来源、请求、路由与参数逐项给
internal class HomePreviewSession(
    private val home: AppHome,
    private val api: PageApi,
    private val routing: AppRouting,
    val pageKey: String,
    private val token: String,
    val entryId: String?,
    val envName: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** 固定身份（构造时取一次）。 */
    val identity: HomeIdentity = home.identity()

    @Volatile var alive: Boolean = true
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow<PreviewScreen>(PreviewScreen.Loading)
    val screen: StateFlow<PreviewScreen> = mutable.asStateFlow()

    /** 首页三个接口共享的 42901 禁用期（R2-03）：错误态的【重试】随它置灰、到期恢复。 */
    val rateLimited: StateFlow<Boolean> get() = home.gate.blocked
    private val visible = MutableStateFlow(false)
    private var owner: CallOwnership? = null
    private val unsubscribe = mutableListOf<() -> Unit>()

    /** 本实例的信号：栈顶是本实例且 App 在前台才算可见；读屏与阻挡项与首页同一来源（共性第 18、20、21 条）。 */
    val signals: HomeSignals =
        object : HomeSignals {
            override val visible: StateFlow<Boolean> get() = this@HomePreviewSession.visible.asStateFlow()
            override val screenReader: StateFlow<Boolean> get() = home.screenReader

            override fun blockers(): Set<String> = home.blockers()

            override val blockerChanges: StateFlow<Long> get() = home.blockerChanges
        }

    private val host =
        object : HomeHost {
            override fun identity(): HomeIdentity = home.identity()

            override fun open(
                target: RouteTarget,
                spm: String,
            ) {
                routing.router.open(RouteRequest(target.route, target.routeParams, RouteSource.IN_APP))
            }

            // 10405：与正式首页同一个强更分流（R1-04，不留空实现）
            override fun forceUpdate() = home.forceUpdate()

            // 预览不发任何埋点（G24 ⑥）
            override fun reportCardError(
                type: String,
                version: Int?,
                pageVersion: Int,
                reason: String,
            ) = Unit
        }

    init {
        unsubscribe += home.addBackStackListener { ids -> if (entryId != null && entryId !in ids) leave() else syncVisible() }
        unsubscribe += home.addIdentityListener { if (alive && home.identity() != identity) invalidated() }
        scope.launch { home.foreground.collect { syncVisible() } }
        syncVisible()
    }

    private fun syncVisible() {
        visible.value = alive && entryId != null && home.backStackTop.value == entryId && home.foreground.value
    }

    /** 调 previewPage（当前环境）；42901 写进首页共享闸门，5xxxx 由统一错误消息函数追加 trace6。 */
    fun load() {
        if (!alive) return
        val probe = home.gate.probe()
        val call = CallOwnership(identity.epochs, sendAllowed = { probe.allowed() && alive }, stillOwned = { alive })
        owner = call
        mutable.value = PreviewScreen.Loading
        scope.launch {
            val result =
                api.send(
                    ApiCall(
                        PREVIEW_PAGE,
                        pathParams = mapOf("page_key" to pageKey),
                        query = "token=" + percentEncode(token),
                        ownership = call,
                        expectedAccount = identity.account,
                    ),
                )
            if (!alive || home.identity() != identity) return@launch
            val disable = ResultSignals.disableMillis(result)
            if (disable > 0) home.gate.start(disable)
            val data = (result as? ApiResult.Success)?.data as? JsonObject
            val page = data?.let { HomeJson.parse(it, AppHome.PLATFORM, home.appVersionName) }
            val code = (result as? ApiResult.Failure)?.code
            mutable.value =
                when {
                    page != null && page.valid -> draft(page)

                    HomePreviewPolicy.screen(code ?: 0) == HomePreviewPolicy.EXPIRED -> PreviewScreen.Expired

                    // 实际错误码与 trace_id 经首页同一个统一函数（R2-04：42901 不带编号，与首页各处一致）
                    else -> PreviewScreen.Failed(HomeErrors.failure(result, probe.gated(result)).message)
                }
        }
    }

    private fun draft(page: com.couli.core.home.HomePage): PreviewScreen {
        val controller = HomeController(identity, home.deps(host, signals, preview = true), preview = true)
        controller.showDraft(page)
        // 有效期按收到 token 的响应起算 10 分钟（拿不到确切签发时刻，G24 ⑤，代拟）
        val until = Instant.ofEpochMilli(clock() + VALID_MS).atZone(SHANGHAI).format(HH_MM)
        return PreviewScreen.Draft(controller, page.version, until)
    }

    /** 【重试】（错误态）：受同一截止时间约束。 */
    fun retry() {
        if (home.gate.active) {
            mutable.value = PreviewScreen.Failed(HomeErrors.message(HomeErrors.RATE_LIMITED, null))
            return
        }
        load()
    }

    private fun invalidated() {
        stopWork()
        mutable.value = PreviewScreen.Invalidated
    }

    private fun stopWork() {
        owner?.let(api::cancelOwned)
        owner = null
        (mutable.value as? PreviewScreen.Draft)?.controller?.invalidate()
        alive = false
        syncVisible()
    }

    /** 出栈（回退栈的同步出口）或页面销毁：作废 previewPage、控制器与订阅。 */
    fun leave() {
        if (alive) stopWork()
        unsubscribe.toList().forEach { it() }
        unsubscribe.clear()
        scope.cancel()
    }

    companion object {
        const val PREVIEW_PAGE = "previewPage"
        private const val VALID_MS = 10 * 60 * 1_000L
        private val SHANGHAI: ZoneId = ZoneId.of("Asia/Shanghai")
        private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}

/**
 * HomePreview 页面（HomePreview / HomePreviewExpired 稿）：顶部常驻预览条 + 与首页同一个渲染器 + 页底「被丢弃的卡与原因」；30701 → 过期 / 环境不符版式；
 * 只读：不写 LKG、计数、关闭记录，不发埋点。【退出预览】只退出本实例（[onExit] 只关这一个回退栈条目，不改原来的 Tab）。
 */
@Composable
internal fun HomePreviewRoute(
    home: AppHome,
    routing: AppRouting,
    destination: RouteDestination,
    onExit: (String?) -> Unit,
) {
    val entryId = (LocalLifecycleOwner.current as? NavBackStackEntry)?.id
    val context = LocalContext.current
    val params =
        remember(destination.paramsJson) {
            runCatching {
                kotlinx.serialization.json.Json
                    .parseToJsonElement(destination.paramsJson) as? JsonObject
            }.getOrNull()
        }
    val pageKey = (params?.get("page_key") as? JsonPrimitive)?.content.orEmpty()
    val token = (params?.get("token") as? JsonPrimitive)?.content.orEmpty()
    val session =
        remember(entryId, pageKey) {
            HomePreviewSession(home, home.pageApi, routing, pageKey, token, entryId, variantEnvironmentKey(context)).also { it.load() }
        }
    DisposableEffect(session) { onDispose { session.leave() } }
    val screen by session.screen.collectAsStateWithLifecycle()
    val rateLimited by session.rateLimited.collectAsStateWithLifecycle()
    var switching by remember { mutableStateOf(false) }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(CouliColors.backgroundCanvas)
                .statusBarsPadding()
                .testTag(HomePreviewTags.PAGE),
    ) {
        val draft = screen as? PreviewScreen.Draft
        PreviewBar(session, draft) { onExit(entryId) }
        when (val current = screen) {
            is PreviewScreen.Draft -> {
                // 草稿用首页同一个渲染器（自带滚动与 600dp 限宽居中）
                HomeSduiScreen(controller = current.controller, signals = session.signals, modifier = Modifier.weight(1f)) {
                    val dropped = current.controller.state.value.dropped
                    if (dropped.isNotEmpty()) item(key = HomeListKeys.extra("preview_dropped")) { DroppedList(dropped) }
                }
            }

            else -> {
                // 其余状态（加载、过期、失败、已作废）：预览条以下的剩余高度可滚动，内容限宽居中（R1-09，共性第 31 条）
                PreviewStateArea {
                    when (current) {
                        PreviewScreen.Loading -> {
                            Text(text = "", modifier = Modifier.testTag(HomePreviewTags.LOADING))
                        }

                        PreviewScreen.Expired -> {
                            Expired(session.envName, onExit = { onExit(entryId) }, onSwitch = { switching = true })
                        }

                        is PreviewScreen.Failed -> {
                            ErrorState(
                                title = HomePreviewTexts.ERROR_TITLE,
                                message = current.message,
                                retry = StateAction(DefaultTexts.text(RETRY_KEY).orEmpty(), enabled = !rateLimited) { session.retry() },
                                modifier = Modifier.fillMaxWidth().testTag(HomePreviewTags.ERROR),
                            )
                        }

                        PreviewScreen.Invalidated -> {
                            Text(
                                text = HomePreviewTexts.INVALIDATED,
                                style = CouliTypography.body,
                                color = CouliColors.textSecondary,
                                modifier = Modifier.padding(CouliSpacing.space4).testTag(HomePreviewTags.INVALIDATED),
                            )
                        }

                        is PreviewScreen.Draft -> {
                            Unit
                        }
                    }
                }
            }
        }
    }
    if (switching) {
        // 【切换环境】打开已有的调试环境切换入口（照已有流程清会话；切换后本实例按身份变化作废）
        val switcher = rememberEnvironmentSwitcher(context)
        var current by remember { mutableStateOf(switcher.current) }
        CouliSheet(title = HomePreviewTexts.SWITCH_ENV, onDismissRequest = { switching = false }, closeLabel = HomePreviewTexts.CLOSE) {
            DebugPanel(switcher = switcher, current = current, onSwitched = { current = it })
        }
    }
}

/** 预览条以下的剩余高度：可滚动；内容限宽 600dp 居中（大屏不铺满）。 */
@Composable
private fun ColumnScope.PreviewStateArea(content: @Composable () -> Unit) {
    Box(
        modifier =
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .testTag(HomePreviewTags.STATE),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(modifier = Modifier.widthIn(max = PREVIEW_MAX_WIDTH.dp).fillMaxWidth()) { content() }
    }
}

/** 预览内容的最大宽度（与首页一致）。 */
private const val PREVIEW_MAX_WIDTH = 600

/** 双按钮横排至少要这么宽；更窄或字号放大到 [STACK_FONT_SCALE] 以上时纵排。 */
private const val BUTTON_ROW_MIN_WIDTH = 360
private const val STACK_FONT_SCALE = 1.3f

@Composable
private fun PreviewBar(
    session: HomePreviewSession,
    draft: PreviewScreen.Draft?,
    onExit: () -> Unit,
) {
    // 常驻预览条：底色铺满，内层限宽居中
    Box(
        modifier = Modifier.fillMaxWidth().background(CouliColors.statusWarningBackground),
        contentAlignment = Alignment.TopCenter,
    ) {
        PreviewBarContent(session, draft, onExit)
    }
}

@Composable
private fun PreviewBarContent(
    session: HomePreviewSession,
    draft: PreviewScreen.Draft?,
    onExit: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .widthIn(max = PREVIEW_MAX_WIDTH.dp)
                .fillMaxWidth()
                .padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space2)
                .testTag(HomePreviewTags.BAR),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
    ) {
        Text(text = HomePreviewTexts.BAR_TITLE, style = CouliTextStyles.bodySemibold, color = CouliColors.statusWarningText)
        Text(
            text = HomePreviewTexts.barLine(session.pageKey, session.envName, draft?.version, draft?.validUntil),
            style = CouliTypography.caption,
            color = CouliColors.textPrimary,
        )
        CouliButton(
            text = HomePreviewTexts.EXIT,
            onClick = onExit,
            variant = CouliButtonVariant.SECONDARY,
            modifier = Modifier.testTag(HomePreviewTags.EXIT),
        )
    }
}

@Composable
private fun Expired(
    env: String,
    onExit: () -> Unit,
    onSwitch: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(CouliSpacing.space4).testTag(HomePreviewTags.EXPIRED),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        Text(text = HomePreviewTexts.EXPIRED_TITLE, style = CouliTypography.title, color = CouliColors.textPrimary)
        Text(text = HomePreviewTexts.expiredBody(env), style = CouliTypography.body, color = CouliColors.textSecondary)
        Text(text = HomePreviewTexts.EXPIRED_TIP_1, style = CouliTypography.footnote, color = CouliColors.textSecondary)
        Text(text = HomePreviewTexts.EXPIRED_TIP_2, style = CouliTypography.footnote, color = CouliColors.textSecondary)
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val stacked = maxWidth < BUTTON_ROW_MIN_WIDTH.dp || LocalDensity.current.fontScale > STACK_FONT_SCALE
            if (stacked) {
                // 窄屏、大字号：纵排，两个按钮都完整可点
                Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3)) {
                    CouliButton(
                        text = HomePreviewTexts.EXIT,
                        onClick = onExit,
                        modifier = Modifier.fillMaxWidth().testTag(HomePreviewTags.EXPIRED_EXIT),
                    )
                    CouliButton(
                        text = HomePreviewTexts.SWITCH_ENV,
                        onClick = onSwitch,
                        variant = CouliButtonVariant.SECONDARY,
                        modifier = Modifier.fillMaxWidth().testTag(HomePreviewTags.SWITCH_ENV),
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3)) {
                    CouliButton(
                        text = HomePreviewTexts.EXIT,
                        onClick = onExit,
                        modifier = Modifier.weight(1f).testTag(HomePreviewTags.EXPIRED_EXIT),
                    )
                    CouliButton(
                        text = HomePreviewTexts.SWITCH_ENV,
                        onClick = onSwitch,
                        variant = CouliButtonVariant.SECONDARY,
                        modifier = Modifier.weight(1f).testTag(HomePreviewTags.SWITCH_ENV),
                    )
                }
            }
        }
    }
}

@Composable
private fun DroppedList(dropped: List<HomeDropped>) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(CouliSpacing.space4).testTag(HomePreviewTags.DROPPED),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
    ) {
        Text(text = HomePreviewTexts.DROPPED_TITLE, style = CouliTextStyles.bodySemibold, color = CouliColors.textPrimary)
        dropped.forEach { drop ->
            Text(
                text = listOfNotNull(drop.id, drop.type, drop.reason).joinToString(" · "),
                style = CouliTypography.caption,
                color = CouliColors.textSecondary,
            )
        }
    }
}
