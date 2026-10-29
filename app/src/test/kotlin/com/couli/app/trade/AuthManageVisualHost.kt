@file:Suppress("MagicNumber")

package com.couli.app.trade

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText as textMatcher
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import com.couli.app.product.productPixels
import com.couli.app.routing.AppRouting
import com.couli.app.search.AppSearch
import com.couli.core.clientcore.ClientPlatformRaw
import com.couli.core.clientcore.ConfigStore
import com.couli.core.clientcore.PrivacyStateRaw
import com.couli.core.clientcore.TrackerQueue
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.designsystem.CouliAnnouncer
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.LocalCouliAnnouncer
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiClient
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.ClientIdentity
import com.couli.core.network.MemoryPendingKeyBackend
import com.couli.core.network.MemorySessionTokens
import com.couli.core.network.NetworkContext
import com.couli.core.network.PageApi
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PrivacyRaw
import com.couli.core.network.UnregisteredDevice
import com.couli.core.network.UpdateRaw
import com.couli.core.router.PrivacyState
import com.couli.core.router.SessionState
import com.couli.core.tradejump.BaichuanAdapter
import com.couli.core.tradejump.BaichuanAuthResult
import com.couli.core.tradejump.BaichuanOpenResult
import com.couli.feature.trade.AuthCredential
import com.couli.feature.trade.AuthManageTags
import com.couli.feature.trade.AuthStepResult
import com.couli.feature.trade.JumpPorts
import com.couli.feature.trade.TradeKeys
import com.couli.feature.trade.TradeOperations
import com.couli.feature.trade.WebCodeAuthorizer
import java.util.TimeZone
import javax.inject.Provider
import kotlinx.coroutines.CompletableDeferred
import org.robolectric.Shadows.shadowOf

/**
 * APP-06w 授权管理的真实页面宿主（brief-android §4、gaps G-A12）：真 AppTrade（真 TradeJumpExecutor / AuthManageController 工厂 /
 * customerService 出口）+ 真 AppSearch（bindPageLeave）+ 正式 AuthManageRoute → 真 AuthManageScreen / AuthManageViewModel。只替换：
 * - 传输层 [api]（PageApi：记录每次 ApiCall、幂等键、installed 查询；列表用「持续应答」，写请求可挂起后由测试放行，lessons「页面进入多次读取」）；
 * - 授权 / 外跳端口 [ports]（web_code 授权结果可注入、可挂起；百川不可用；外跳只记账不真跳）；
 * - 页面持有者 [entry]（代替 NavBackStackEntry：Lifecycle 推 ON_STOP / ON_START，[pop] = 出栈清 ViewModelStore → onCleared → leave()）。
 * 埋点出口计数 [linkJumps]（AppTrade 的 LinkJumpSink → tracker.get() 每上报一次 +1）。顶部 54dp 是状态栏占位（画板顶部 54）。
 * 测试里不另画假页面（APP-06v G-D11 先例）。不依赖本任务新增的任何产品符号（新标记、新视图件只经源码扫描与反射核）。
 */
@Suppress("TooManyFunctions") // 宿主逐项给真实入口与量具
internal class AuthManageVisualHost(
    private val rule: ComposeContentTestRule,
) : AutoCloseable {
    private val oldZone = TimeZone.getDefault()
    private val context: Context = ApplicationProvider.getApplicationContext()
    val api = AuthManageVisualApi()
    val ports = AuthManageVisualPorts()
    val palette = AuthManageVisualPalette()
    private val entryState = mutableStateOf(AuthManageVisualEntry())

    /** 当前页面持有者（每次 [launch] 新建一个 = 新回退栈条目）。 */
    val entry: AuthManageVisualEntry get() = entryState.value
    private var contentSet = false
    val spoken = mutableListOf<String>()
    var backs = 0
    var linkJumps = 0
    val contacts = mutableListOf<String>()
    private val shown = mutableStateOf(true)
    private val gate = EnvironmentGate { EnvironmentStartupReconciler.Outcome.CLEANED_AND_RECORDED }.also { it.settleAtStartup() }
    private val routing =
        AppRouting(context, gate).also {
            it.privacy.value = PrivacyState.CONSENTED
            it.session.value = SessionState.PHONE_BOUND
        }
    private val store =
        ConfigStore(
            { null },
            {},
            { _, _ -> },
            { AuthManageVisualFixtures.now },
            """{"features":{"flags":{},"platform_status":{"taobao":"on","jd":"on","pdd":"on"}}}""",
        )
    private val client =
        ApiClient(
            identity = ClientIdentity("couli", "android", "1.0.0", "1", "official"),
            baseUrl = { "https://api.example.test/" },
            context = { NetworkContext(PrivacyRaw.FULL, true, true, UpdateRaw.SUPPORTED) },
            device = UnregisteredDevice,
            tokens = MemorySessionTokens(),
            pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
            sleeper = {},
        )
    private val queue =
        TrackerQueue(
            readFile = { null },
            writeFile = {},
            sink = { _: List<String> -> false },
            privacy = PrivacyStateRaw.CONSENTED,
            userId = { null },
            deviceId = "fixture-device",
            platform = ClientPlatformRaw.ANDROID,
            appVersion = "1.0.0",
            sessionId = "fixture-session",
            clock = { AuthManageVisualFixtures.now },
            canUpload = { false },
        )
    val trade =
        AppTrade(
            api,
            store,
            routing,
            Provider {
                linkJumps += 1
                queue
            },
            ports,
            ports,
            ports,
        ).also { it.customerService = { from -> contacts += from } }
    private val search = AppSearch(client, store, routing, { PrivacyState.CONSENTED }, api = api, trade = trade)
    private val announcer = CouliAnnouncer { spoken += it }

    // ---------------------------------------------------------------- 进入

    /**
     * 按 fixture 的 bindings 组进页（[bindings] 为 null = 列表挂起，造「加载中」）。同一测试里再次调用 = 新回退栈条目重新打开本页
     * （setContent 每个测试只能调一次；深色 / 字号以第一次为准）。
     */
    fun launch(
        bindings: String? = "normal",
        dark: Boolean = false,
        fontScale: Float = 1f,
        wait: Boolean = true,
    ) {
        TimeZone.setDefault(TimeZone.getTimeZone(AuthManageVisualFixtures.zone))
        if (bindings != null) api.sticky[LIST] = AuthManageVisualFixtures.list(bindings)
        if (contentSet) {
            rule.runOnUiThread {
                if (!entry.destroyed) entry.destroy()
                entryState.value = AuthManageVisualEntry()
                shown.value = true
            }
        } else {
            contentSet = true
            rule.setContent {
                val density = LocalDensity.current
                val owner = entryState.value
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale),
                    LocalCouliAnnouncer provides announcer,
                ) {
                    CouliTheme(darkTheme = dark) {
                        palette.Capture()
                        Column(Modifier.fillMaxSize().background(CouliColors.backgroundCanvas)) {
                            Spacer(Modifier.fillMaxWidth().height(STATUS_BAR.dp))
                            if (shown.value) {
                                key(owner) {
                                    CompositionLocalProvider(
                                        LocalViewModelStoreOwner provides owner,
                                        LocalLifecycleOwner provides owner,
                                    ) {
                                        Box(Modifier.fillMaxWidth().weight(1f)) {
                                            AuthManageRoute(trade, search, onBack = { backs += 1 })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        idle()
        if (wait && bindings != null) settle("列表已显示") { has(AuthManageTags.ROW_PREFIX + "taobao") }
    }

    /** 列表加载失败进页（[result] 为整次持续应答）。 */
    fun launchListError(
        result: ApiResult,
        dark: Boolean = false,
        fontScale: Float = 1f,
    ) {
        api.sticky[LIST] = result
        launch(bindings = null, dark = dark, fontScale = fontScale, wait = false)
        settle("列表错误态") { has(AuthManageTags.RETRY) }
    }

    /** 把列表持续应答换成另一组（回前台 / 授权成功后的刷新读到它）。 */
    fun nextList(bindings: String) {
        api.sticky[LIST] = AuthManageVisualFixtures.list(bindings)
    }

    // ---------------------------------------------------------------- 节点

    fun node(tag: String): SemanticsNodeInteraction = rule.onNodeWithTag(tag, useUnmergedTree = true)

    fun has(tag: String): Boolean = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    fun text(key: String): String = trade.texts.text(key)

    fun name(platform: String): String = text(TradeKeys.platform(platform))

    fun row(platform: String): String = AuthManageTags.ROW_PREFIX + platform

    fun grantTag(platform: String): String = AuthManageTags.AUTHORIZE_PREFIX + platform

    /** 行块（ROW_PREFIX 标记）之下文字恰为 [value] 的未合并节点。 */
    fun inRow(
        platform: String,
        value: String,
    ): SemanticsNodeInteraction =
        rule.onNode(textMatcher(value) and (hasTestTag(row(platform)) or hasAnyAncestor(hasTestTag(row(platform)))), useUnmergedTree = true)

    fun countInRow(
        platform: String,
        value: String,
    ): Int =
        rule
            .onAllNodes(
                textMatcher(value) and (hasTestTag(row(platform)) or hasAnyAncestor(hasTestTag(row(platform)))),
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
            .size

    /** 页面任意处文字恰为 [value]（[substring] 为 true 时包含）的未合并节点个数。 */
    fun countText(
        value: String,
        substring: Boolean = false,
    ): Int = rule.onAllNodes(textMatcher(value, substring = substring), useUnmergedTree = true).fetchSemanticsNodes().size

    fun textNode(
        value: String,
        substring: Boolean = false,
    ): SemanticsNodeInteraction = rule.onNode(textMatcher(value, substring = substring), useUnmergedTree = true)

    /** NavBar 返回：可点 ∧ 自身或后代有「返回」描述（APP-12w / APP-13v 测试 T 系列口径）。 */
    fun back(): SemanticsNodeInteraction {
        val label = text(BACK_KEY)
        return rule.onNode(
            hasClickAction() and (hasContentDescription(label) or hasAnyDescendant(hasContentDescription(label))),
            useUnmergedTree = true,
        )
    }

    /** 授权按钮里的文字节点。 */
    fun grantText(platform: String): SemanticsNodeInteraction =
        rule.onNode(textMatcher(text(GRANT_KEY)) and hasAnyAncestor(hasTestTag(grantTag(platform))), useUnmergedTree = true)

    /** 全部可点节点（合并树），按从上到下、从左到右。 */
    fun clickables(): List<SemanticsNode> =
        rule
            .onAllNodes(hasClickAction(), useUnmergedTree = false)
            .fetchSemanticsNodes()
            .sortedWith(compareBy({ it.boundsInRoot.top }, { it.boundsInRoot.left }))

    /** 合并树按遍历顺序展开、只留读屏会读出的节点（读屏顺序近似；不代替实机 TalkBack）。 */
    fun readOrder(): List<SemanticsNode> {
        fun flat(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap { flat(it) }
        return flat(rule.onRoot(useUnmergedTree = false).fetchSemanticsNode()).filter { spokenOf(it).isNotEmpty() }
    }

    fun spokenOf(n: SemanticsNode): List<String> =
        n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            n.config
                .getOrNull(SemanticsProperties.Text)
                .orEmpty()
                .map { it.text } +
            listOfNotNull(n.config.getOrNull(SemanticsProperties.StateDescription))

    fun pixels(): Bitmap = rule.productPixels()

    fun density(): Float =
        rule
            .onRoot()
            .fetchSemanticsNode()
            .layoutInfo.density.density

    fun rootWidth(): Float =
        rule
            .onRoot()
            .fetchSemanticsNode()
            .boundsInRoot.width

    // ---------------------------------------------------------------- 操作（真实触摸）

    /** 真实触摸点一次；只有可滚动祖先时才先滚到可见（lessons：tap() 只对可滚节点 performScrollTo）。 */
    fun tap(node: SemanticsNodeInteraction) {
        if (node.insideScroll()) node.performScrollTo()
        node.performTouchInput { click(center) }
        idle()
    }

    fun tap(tag: String) = tap(node(tag))

    private fun SemanticsNodeInteraction.insideScroll(): Boolean {
        val id = fetchSemanticsNode().id
        return rule
            .onAllNodes(hasScrollAction() and hasAnyDescendant(SemanticsMatcher("节点 $id") { it.id == id }), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()
    }

    /** 节点上此刻注册的点击回调（「出栈 / 换状态后再调旧回调」的零动作断言；Compose 语义点击转发到节点当前 onClick，APP-12w-c v2 T4）。 */
    fun clickAction(node: SemanticsNodeInteraction): () -> Boolean {
        val action = node.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        return { action() }
    }

    /** 放行最近一次挂起的 [op] 请求。 */
    fun complete(
        op: String,
        result: ApiResult,
    ) {
        rule.runOnUiThread { api.held(op).result.complete(result) }
        idle()
    }

    fun stop() {
        rule.runOnUiThread { entry.stop() }
        idle()
    }

    fun start() {
        rule.runOnUiThread { entry.start() }
        idle()
    }

    /** 出栈：撤下页面并清掉持有者的 ViewModelStore（NavBackStackEntry 出栈同口径：onCleared → controller.leave()）。 */
    fun pop() {
        rule.runOnUiThread {
            shown.value = false
            entry.destroy()
        }
        idle()
    }

    /** 配置变化同口径：页面撤下再组合，持有者（ViewModelStore）不变。 */
    fun recompose() {
        rule.runOnUiThread { shown.value = false }
        idle()
        rule.runOnUiThread { shown.value = true }
        idle()
    }

    fun systemBack() {
        val view = (rule.onRoot().fetchSemanticsNode().root as ViewRootForTest).view
        val activity =
            generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
                .filterIsInstance<ComponentActivity>()
                .first()
        rule.runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
        idle()
    }

    /**
     * 轻提示类场景：先停主时钟自动推进再触发（否则空转会把 2.5 秒的消费 delay 一并推完），之后逐帧推进到提示出现。
     * 返回时主时钟仍是手动推进。
     */
    fun toastAfter(
        message: String,
        substring: Boolean = false,
        trigger: () -> Unit,
    ) {
        rule.mainClock.autoAdvance = false
        trigger()
        repeat(TOAST_FRAMES) {
            if (countText(message, substring) > 0) return
            rule.mainClock.advanceTimeByFrame()
            idle()
        }
        error("轻提示没出现：$message")
    }

    /** 主时钟手动推进 [millis]（轻提示消费、焦点前推进时钟）。 */
    fun advance(millis: Long) {
        rule.mainClock.advanceTimeBy(millis)
        idle()
    }

    fun idle() {
        repeat(IDLE_ROUNDS) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!rule.mainClock.autoAdvance) rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
        }
    }

    fun settle(
        what: String,
        condition: () -> Boolean,
    ) {
        repeat(SETTLE_ROUNDS) {
            idle()
            if (condition()) return
            Thread.sleep(SETTLE_SLEEP_MS)
        }
        error("等待超时：$what")
    }

    /** 42901 到期恢复：真实墙钟与 Looper 时钟都推进（执行器的闸门按墙钟现算，计时协程在主线程 Handler 上）。 */
    fun waitRetryAfter(
        what: String,
        condition: () -> Boolean,
    ) {
        repeat(RECOVER_ROUNDS) {
            Thread.sleep(SETTLE_SLEEP_MS * 5)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(RECOVER_STEP_MS))
            idle()
            if (condition()) return
        }
        error("限流没有到期恢复：$what")
    }

    override fun close() {
        rule.runOnUiThread {
            api.releaseAll()
            ports.hold?.complete(AuthStepResult.Cancelled)
            if (!entry.destroyed) entry.destroy()
        }
        TimeZone.setDefault(oldZone)
    }

    companion object {
        const val STATUS_BAR = 54
        const val LIST = TradeOperations.LIST_UNION_BINDINGS
        const val GRANT = TradeOperations.GET_UNION_AUTH_URL
        const val BIND = TradeOperations.BIND_UNION
        const val OPEN = TradeOperations.OPEN_LINK
        const val BACK_KEY = "product.back"
        const val GRANT_KEY = "auth_manage.action.authorize"
        const val AUTHORIZED_KEY = "auth_manage.status.authorized"
        const val UNAUTHORIZED_KEY = "auth_manage.status.unauthorized"
        const val REBIND_KEY = "auth_manage.rebind_note"
        const val BLOCKED_KEY = "error.30153"
        const val CONTACT_KEY = "trade.contact"
        const val TITLE_KEY = "auth_manage.title"
        const val RETRY_KEY = "external_page.retry"
        const val LOADING_KEY = "search.loading"
        private const val IDLE_ROUNDS = 4
        private const val SETTLE_ROUNDS = 200
        private const val SETTLE_SLEEP_MS = 10L
        private const val TOAST_FRAMES = 20
        private const val RECOVER_ROUNDS = 160
        private const val RECOVER_STEP_MS = 100L
    }
}

/** 传输层替身：记录每次 ApiCall；排队应答 → 持续应答 → 挂起（由测试放行）。 */
internal class AuthManageVisualApi : PageApi {
    class Sent(
        val call: ApiCall,
        val result: CompletableDeferred<ApiResult>,
    ) {
        var held = false
    }

    val calls = mutableListOf<Sent>()
    val cancelled = mutableListOf<CallOwnership>()
    val sticky = mutableMapOf<String, ApiResult>()
    private val queued = mutableMapOf<String, ArrayDeque<ApiResult>>()

    fun queue(
        op: String,
        vararg results: ApiResult,
    ) {
        queued.getOrPut(op) { ArrayDeque() }.addAll(results)
    }

    override suspend fun send(call: ApiCall): ApiResult {
        val sent = Sent(call, CompletableDeferred())
        calls += sent
        val ready = queued[call.operationId]?.removeFirstOrNull() ?: sticky[call.operationId]
        sent.held = ready == null
        return ready ?: sent.result.await()
    }

    override fun epochs(): CallEpochs = CallEpochs(0, 0)

    override fun cancelOwned(owner: CallOwnership) {
        cancelled += owner
    }

    fun count(op: String): Int = calls.count { it.call.operationId == op }

    fun of(op: String): List<ApiCall> = calls.filter { it.call.operationId == op }.map { it.call }

    fun held(op: String): Sent = calls.last { it.call.operationId == op && it.held && !it.result.isCompleted }

    fun releaseAll() {
        calls.filter { it.held && !it.result.isCompleted }.forEach { it.result.cancel() }
    }
}

/** 授权 / 外跳端口替身：只记账，绝不发真实外跳；web_code 授权结果可注入或挂起。百川不可用（不支持 sdk_token）。 */
internal class AuthManageVisualPorts :
    JumpPorts,
    BaichuanAdapter,
    WebCodeAuthorizer {
    var installation = "unknown"
    var step: AuthStepResult = AuthStepResult.Credential(AuthCredential.WebCode("fixture-code"))
    var hold: CompletableDeferred<AuthStepResult>? = null
    val webGrants = mutableListOf<Pair<String, String>>()
    val browser = mutableListOf<String>()
    val apps = mutableListOf<String>()
    val copies = mutableListOf<String>()
    var sdkGrants = 0
    var sdkOpens = 0

    /** 一切外跳 / 授权拉起的总次数。 */
    val launches: Int get() = webGrants.size + browser.size + apps.size + sdkGrants + sdkOpens

    override fun installed(platform: String): String = installation

    override fun launchApp(
        type: String,
        value: String,
    ): Boolean {
        apps += "$type:$value"
        return true
    }

    override fun openBrowser(url: String): Boolean {
        browser += url
        return true
    }

    override fun copy(text: String): Boolean {
        copies += text
        return true
    }

    override val ready = false
    override val supportsSdkToken = false

    // 返回 true：不让 AppTrade 启动时补报「百川初始化失败」埋点，link_jump 计数只算本页
    override fun initialize(): Boolean = true

    override fun retryInitIfFailed(): Boolean = false

    override fun open(value: String): BaichuanOpenResult {
        sdkOpens += 1
        return BaichuanOpenResult.INIT_FAILED
    }

    override suspend fun authorize(): BaichuanAuthResult {
        sdkGrants += 1
        return BaichuanAuthResult.Failed
    }

    override val available = true

    override suspend fun authorize(
        authUrl: String,
        state: String,
    ): AuthStepResult {
        webGrants += authUrl to state
        return hold?.await() ?: step
    }
}

/** 页面持有者（代替 NavBackStackEntry）：Lifecycle + ViewModelStore。只在主线程用。 */
internal class AuthManageVisualEntry :
    LifecycleOwner,
    ViewModelStoreOwner {
    private val registry = LifecycleRegistry.createUnsafe(this)
    override val viewModelStore = ViewModelStore()
    override val lifecycle: Lifecycle get() = registry
    var destroyed = false
        private set

    init {
        registry.currentState = Lifecycle.State.RESUMED
    }

    /** 进入后台 / 被覆盖：ON_PAUSE → ON_STOP。 */
    fun stop() {
        registry.currentState = Lifecycle.State.CREATED
    }

    /** 回到前台：ON_START → ON_RESUME。 */
    fun start() {
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        destroyed = true
        registry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}

/** 渲染时的令牌色（页面不写色值，测试也只比令牌；浅 / 深各取一次）。 */
internal class AuthManageVisualPalette {
    var canvas = Color.Unspecified
    var surface = Color.Unspecified
    var primary = Color.Unspecified
    var secondary = Color.Unspecified
    var subtle = Color.Unspecified
    var control = Color.Unspecified
    var errorText = Color.Unspecified
    var errorBackground = Color.Unspecified
    var warningText = Color.Unspecified
    var successText = Color.Unspecified
    var buttonPrimary = Color.Unspecified
    var buttonPrimaryText = Color.Unspecified
    var buttonDisabled = Color.Unspecified

    @Composable
    @ReadOnlyComposable
    fun Capture() {
        canvas = CouliColors.backgroundCanvas
        surface = CouliColors.backgroundSurface
        primary = CouliColors.textPrimary
        secondary = CouliColors.textSecondary
        subtle = CouliColors.borderSubtle
        control = CouliColors.borderControl
        errorText = CouliColors.statusErrorText
        errorBackground = CouliColors.statusErrorBackground
        warningText = CouliColors.statusWarningText
        successText = CouliColors.statusSuccessText
        buttonPrimary = CouliColors.buttonPrimaryDefaultBackground
        buttonPrimaryText = CouliColors.buttonPrimaryDefaultText
        buttonDisabled = CouliColors.buttonPrimaryDisabledBackground
    }
}
