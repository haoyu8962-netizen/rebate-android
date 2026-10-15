package com.couli.app.orders

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.enableSavedStateHandles
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.AppModule
import com.couli.app.login.AppLogin
import com.couli.app.orderCacheStore
import com.couli.app.orderRestorePendingFlag
import com.couli.app.orderRestoreStore
import com.couli.app.privacy.AppPrivacy
import com.couli.app.routing.AppRouting
import com.couli.app.routing.RouteDestination
import com.couli.app.routing.RoutingPrivacyHooks
import com.couli.app.shell.AppShell
import com.couli.app.web.BridgeHostRegistry
import com.couli.core.clientcore.AmountMaskStore
import com.couli.core.clientcore.ClientPlatformRaw
import com.couli.core.clientcore.ConfigReply
import com.couli.core.clientcore.ConfigStore
import com.couli.core.clientcore.PrivacyStateRaw
import com.couli.core.clientcore.TrackerQueue
import com.couli.core.clipboard.ClipRead
import com.couli.core.clipboard.ClipboardHashStore
import com.couli.core.clipboard.ClipboardWriteLedger
import com.couli.core.clipboard.ClipboardWriter
import com.couli.core.clipboard.SystemClipboard
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.network.ApiClient
import com.couli.core.network.ApiClientPageApi
import com.couli.core.network.CallEpochs
import com.couli.core.network.ClientIdentity
import com.couli.core.network.DeviceCredentials
import com.couli.core.network.DeviceRegistry
import com.couli.core.network.InFlightCalls
import com.couli.core.network.MemoryDeviceCredentialStore
import com.couli.core.network.MemoryPendingKeyBackend
import com.couli.core.network.MemorySessionTokens
import com.couli.core.network.NetworkContext
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PrivacyRaw
import com.couli.core.network.SecretCipher
import com.couli.core.network.UpdateRaw
import com.couli.core.orders.OrderRestoreRecord
import com.couli.core.orders.OrderRestoreStore
import com.couli.core.orders.OrderStaleCache
import com.couli.core.orders.PendingClosedAttempts
import com.couli.core.orders.PendingTrackTags
import com.couli.core.privacy.AndroidPermissionRequester
import com.couli.core.privacy.AndroidPermissionSystem
import com.couli.core.privacy.PermissionActivityHost
import com.couli.core.privacy.PermissionPrompt
import com.couli.core.privacy.PermissionPrompts
import com.couli.core.privacy.PrivacyGate
import com.couli.core.privacy.PrivacyStore
import com.couli.core.privacy.RetryScheduler
import com.couli.core.router.PrivacyState
import com.couli.core.router.RouteRequest
import com.couli.core.router.RouteSource
import com.couli.core.router.SessionState
import com.couli.core.router.SharedPreferencesPendingBackend
import com.couli.core.router.UpdateCheck
import com.couli.feature.login.LegalVersions
import com.couli.feature.orders.OrderTags
import com.couli.generated.DefaultTexts
import java.io.File
import java.net.URLDecoder
import java.time.OffsetDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Provider
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 订单的本地假服务集成测试（APP-08 brief §2.12）：真实 AppShell + NavHost + AppRouting + AppPrivacy + AppLogin + AppOrders + ApiClient，
 * 本地假服务 MockWebServer（数据照设计稿 sample-data §12.2、§12.3），计数的假系统剪贴板经 APP-07 的统一写入口。页面经 Router 打开、经页面
 * 控件操作，不直接调执行器的内部函数。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
@Suppress("LargeClass", "TooManyFunctions") // 验收场景逐条一个用例
class AppOrdersIntegrationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = OffsetDateTime.parse("2026-10-09T10:00:00+08:00").toInstant().toEpochMilli()
    private lateinit var server: MockWebServer
    private lateinit var routing: AppRouting
    private lateinit var navController: NavHostController
    private lateinit var login: AppLogin
    private lateinit var orders: AppOrders
    private lateinit var tokens: MemorySessionTokens
    private val inFlight = InFlightCalls()
    private val system = FakeClipboard()
    private var ledgerWrites = true
    private val ledger = ClipboardWriteLedger(store = LedgerStore())
    private val host = FakeHost()
    private var forcedUpdates = 0
    private val requests = mutableListOf<Recorded>()

    /** 路径（不含查询串，完全相同；以「/」结尾的按前缀）→ 回答；没有登记的按默认样例回答。 */
    private val answers = mutableMapOf<String, (RecordedRequest) -> MockResponse>()

    @Volatile
    private var hold: CountDownLatch? = null

    /** 假服务每个响应带的 Date 头（FU-AND-0710，共性第 34 条）；null 为不带。 */
    @Volatile
    private var serverDate: String? = null

    /** 与 AppModule 同一套切环境清理注册（FU-AND-0710，共性第 38、43 条）。 */
    private val envCleaners =
        com.couli.core.config
            .EnvironmentSwitchCleanerRegistry()
    private lateinit var appPrivacy: AppPrivacy

    private class Recorded(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val body: String,
    )

    private class FakeClipboard : SystemClipboard {
        var writes = 0
        var content: String? = null

        override fun read(): ClipRead = content?.let { ClipRead.Text(it) } ?: ClipRead.Unavailable

        override fun write(
            text: String,
            label: String,
        ): Boolean {
            writes += 1
            content = text
            return true
        }
    }

    private inner class LedgerStore : ClipboardHashStore {
        private val values = mutableMapOf<String, String>()

        override fun read(key: String): String? = values[key]

        override fun write(
            key: String,
            value: String?,
        ): Boolean {
            if (!ledgerWrites) return false
            if (value == null) values.remove(key) else values[key] = value
            return true
        }
    }

    private class FakeHost : PermissionActivityHost {
        var requests = 0

        /** 弹系统框那一刻屏幕上的权限浮层（共性第 4 条：通知行发起的申请不再叠一层用途说明）。 */
        val promptsAtRequest = mutableListOf<PermissionPrompt?>()
        var prompts: PermissionPrompts? = null

        override fun shouldShowRationale(androidPermission: String): Boolean = false

        override suspend fun request(androidPermission: String): Boolean {
            requests += 1
            promptsAtRequest += prompts?.current?.value
            return false
        }
    }

    private class MemoryStore : PrivacyStore {
        override var decisionRaw: String = "consented"
        override var consentedVersion: String? = "1"
        override var consentedAtMillis: Long? = 0L
        override var basicMode: Boolean = false
    }

    private object FixtureDevice : DeviceCredentials {
        override val deviceId: String = "device-fixture"
        override val installSecret: String = "install-fixture"
    }

    @Before
    fun setUp() {
        SharedPreferencesPendingBackend(context).delete()
        context
            .getSharedPreferences("couli_permission", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context
            .getSharedPreferences("couli_amount_mask", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context
            .getSharedPreferences("couli_order_cache_pending", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        runCatching { orderCacheStore(context).clearAll() }
        AppOrders.restoreRoot(context).setWritable(true)
        runCatching { orderRestoreStore(context).clearAll() }
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val full = request.path.orEmpty()
                    val path = full.substringBefore('?')
                    val query =
                        full.substringAfter('?', "").split('&').filter { it.contains('=') }.associate {
                            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
                        }
                    synchronized(requests) { requests += Recorded(request.method.orEmpty(), path, query, request.body.readUtf8()) }
                    // 回答在等待之前选定（迟到的响应仍是它发出时的回答）
                    val handler =
                        synchronized(answers) {
                            answers.entries
                                .firstOrNull {
                                    path == it.key ||
                                        (it.key.endsWith("/") && path.startsWith(it.key))
                                }?.value
                        }
                    if (path == LIST_PATH) hold?.await(WAIT_S, TimeUnit.SECONDS)
                    val answer = handler?.invoke(request) ?: defaultAnswer(path, query)
                    return serverDate?.let { answer.setHeader("Date", it) } ?: answer
                }
            }
        server.start()
    }

    @After
    fun tearDown() {
        AppOrders.restoreRoot(context).setWritable(true)
        server.shutdown()
    }

    // ---------------------------------------------------------------- 搭建（与 AppModule 同一套接线）

    @Suppress("LongMethod", "LongParameterList") // 各场景的接线开关（配置、系统版本、通知、内容、H5 容器、配置拉取）
    private fun launch(
        config: String = CONFIG,
        sdkInt: Int = 33,
        notificationsEnabled: Boolean = false,
        content: (@androidx.compose.runtime.Composable () -> Unit)? = null,
        withWeb: Boolean = false,
        configFetcher: ((String?, (ConfigReply) -> Unit) -> Unit)? = null,
        serverClock: Boolean = false,
        /** 撤回同意走 AppModule 的真实凭据清理器（含订单页恢复记录，R2-02）。 */
        moduleCleaners: Boolean = false,
    ) {
        val gate =
            PrivacyGate(MemoryStore(), clock = { now }, bundledPolicyVersion = "1").apply {
                withdrawalCleanup = if (moduleCleaners) ({ withdrawalCleaners().cleanAll() }) else ({ inFlight.onPrivacyRevoked() })
                retryScheduler = RetryScheduler { _, _ -> }
            }
        val privacy = AppPrivacy(gate, environmentSettled = { true }, clock = { now }).also { it.coldStart() }
        appPrivacy = privacy
        val environment = EnvironmentGate { EnvironmentStartupReconciler.Outcome.UNCHANGED }.also { it.settleAtStartup() }
        tokens = MemorySessionTokens("access-one", "refresh-one", "full", "user-one")
        routing =
            AppRouting(
                context,
                environment,
                clock = { now },
                initialPrivacy = privacy.conclusion,
                privacyHooks = RoutingPrivacyHooks(promptShowing = { privacy.promptShowing }),
            )
        val api = apiClient()
        val store =
            ConfigStore(
                readFile = { null },
                writeFile = {},
                fetcher = configFetcher ?: { _, done -> done(ConfigReply(ConfigReply.OK, config, null)) },
                clock = { now },
                bundled = config,
            )
        val permissionSystem =
            AndroidPermissionSystem(
                context,
                context.getSharedPreferences("couli_permission", Context.MODE_PRIVATE),
                sdkInt = sdkInt,
                declared = { true },
                granted = { false },
                notificationsEnabled = { notificationsEnabled },
            ).also { it.host = host }
        val prompts = PermissionPrompts().also { host.prompts = it }
        val permissions =
            AndroidPermissionRequester(
                permissionSystem,
                prompts,
                context.getSharedPreferences("couli_permission", Context.MODE_PRIVATE),
            )
        composeRule.runOnUiThread {
            login = AppLogin(api, tokens, routing, updateCheck = { UpdateCheck.OK }, markBelow = {}, legal = LegalVersions(1, 1))
            login.syncSession()
            orders =
                AppOrders(
                    api = ApiClientPageApi(api),
                    store = store,
                    routing = routing,
                    sessionEpoch = { tokens.loginEpoch },
                    account = { api.activeUserId() },
                    writer = { text -> ClipboardWriter(system, ledger).write(text) },
                    permissions = permissions,
                    notificationsEnabled = { notificationsEnabled },
                    forceUpdate = { forcedUpdates += 1 },
                    cache = orderCacheStore(context),
                    mask = AmountMaskStore(context),
                    sdkInt = sdkInt,
                    clock = { now },
                    // 与 AppModule 同一个展示时钟（服务端 Date 校准，共性第 34 条）；不测它时固定为 now
                    displayClock = if (serverClock) api::serverNow else ({ now }),
                    // 与 AppModule 同一个恢复记录落盘（R1-03、R2-02）
                    restoreStore = orderRestoreStore(context),
                ).also {
                    it.connectSession()
                    it.connectCleanup(privacy, envCleaners)
                }
        }
        val web = if (withWeb) appWeb(api, store, privacy, permissions) else null
        composeRule.setContent {
            hostView = androidx.compose.ui.platform.LocalView.current
            if (content != null) {
                content()
                return@setContent
            }
            androidx.compose.runtime.key(relaunchCounter.intValue) {
                navController = rememberNavController()
                AppShell(
                    routing = routing,
                    privacy = privacy,
                    homeContent = { Box(Modifier.fillMaxSize()) },
                    navController = navController,
                    login = login,
                    orders = orders,
                    web = web,
                )
            }
        }
        composeRule.waitForIdle()
    }

    /** 两类 WebView 容器的接线（与 AppModule 同一个 AppWeb；强更检查不发，剪贴板不用）。 */
    private fun appWeb(
        api: ApiClient,
        store: ConfigStore,
        privacy: AppPrivacy,
        permissions: AndroidPermissionRequester,
    ): com.couli.app.web.AppWeb {
        val updateStore =
            com.couli.core.update
                .MemoryUpdateStore()
        val update =
            com.couli.app.update.AppUpdate(
                gate =
                    com.couli.core.update.UpdateGate(
                        updateStore,
                        clock = { now },
                        appVersion = "1.0.0",
                        platform = "android",
                        channel = "official",
                        installSource = null,
                    ),
                store = updateStore,
                checker = { kotlinx.coroutines.CompletableDeferred<com.couli.core.update.UpdateCheckOutcome>().await() },
                stores =
                    object : com.couli.app.update.StoreAccess {
                        override val installSource: String? = null

                        override fun canOpen(storeKey: String) = false

                        override fun open(storeKey: String) = false
                    },
                scope =
                    kotlinx.coroutines.CoroutineScope(
                        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate,
                    ),
                canCheck = { false },
                clock = { now },
            )
        return com.couli.app.web.AppWeb(
            context,
            com.couli.app.clientcore
                .AppConfig(store) { true }
                .also { it.startIfReady() },
            Provider { api },
            routing,
            privacy,
            update,
            Provider { noTracker() },
            permissions,
            BridgeHostRegistry(),
            tokens,
            inFlight,
            Provider { login },
            Provider { error("no clipboard") },
        )
    }

    /** 宿主视图（找出页面里的 WebView）。 */
    private var hostView: android.view.View? = null

    /** 当前页面里所有 WebView 实际加载的地址（Robolectric 记录的最后一次 loadUrl）。 */
    private fun loadedUrls(): List<String?> {
        val out = mutableListOf<String?>()

        fun walk(view: android.view.View) {
            if (view is android.webkit.WebView) out += shadowOf(view).lastLoadedUrl
            if (view is android.view.ViewGroup) (0 until view.childCount).forEach { walk(view.getChildAt(it)) }
        }
        composeRule.runOnUiThread { hostView?.rootView?.let(::walk) }
        return out
    }

    /** 真实点击 [tag] → 栈顶是 H5 路由 [route]、参数为 [expected]，可信容器实际加载 [url]；占位页不算落点。 */
    private fun opensH5(
        tag: String,
        route: String,
        expected: Map<String, String>,
        url: String,
    ) {
        landsOn(tag, route, expected, back = false)
        idleUntil { loadedUrls().contains(url) }
        assertEquals("$tag 的容器加载地址", listOf(url), loadedUrls())
        assertEquals(
            "$tag 不是占位页",
            0,
            composeRule.onAllNodesWithTag(com.couli.app.routing.ROUTE_PAGE_TAG_PREFIX + route).fetchSemanticsNodes().size,
        )
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        idleUntil { loadedUrls().isEmpty() }
    }

    private fun apiClient(): ApiClient =
        ApiClient(
            identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.0.0", build = "1", channel = "official"),
            baseUrl = { server.url("/").toString() },
            context = { NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, UpdateRaw.SUPPORTED) },
            device = FixtureDevice,
            tokens = tokens,
            pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
            inFlight = inFlight,
            sleeper = {},
        )

    private fun open(
        route: String,
        params: Map<String, String> = emptyMap(),
    ) {
        composeRule.runOnUiThread {
            routing.router.open(RouteRequest(route, JsonObject(params.mapValues { JsonPrimitive(it.value) }), RouteSource.IN_APP))
        }
        composeRule.waitForIdle()
    }

    private fun openOrders(params: Map<String, String> = emptyMap()) {
        open("OrderList", params)
        idleUntil { listRequests().isNotEmpty() && composeRule.onAllNodesWithTag(OrderTags.LIST_PAGE).fetchSemanticsNodes().isNotEmpty() }
        settle()
    }

    private fun settle() {
        repeat(SETTLE_ROUNDS) {
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.waitForIdle()
            Thread.sleep(POLL_MS)
        }
    }

    private fun idleUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.waitForIdle()
            Thread.sleep(POLL_MS)
        }
        assertTrue(condition())
    }

    private fun listRequests(): List<Map<String, String>> =
        synchronized(requests) { requests.filter { it.path == LIST_PATH }.map { it.query } }

    private fun count(path: String): Int = synchronized(requests) { requests.count { it.path == path } }

    private fun texts(substring: String): Int = composeRule.onAllNodesWithText(substring, substring = true).fetchSemanticsNodes().size

    @SuppressLint("RestrictedApi")
    private fun top(): RouteDestination? =
        navController.currentBackStack.value
            .lastOrNull { it.destination.hasRoute(RouteDestination::class) }
            ?.toRoute<RouteDestination>()

    private fun click(tag: String) {
        composeRule.onNodeWithTag(tag, useUnmergedTree = true).performClick()
        settle()
    }

    private fun scrollTo(tag: String) {
        composeRule.onNodeWithTag(OrderTags.LIST).performScrollToNode(hasTestTag(tag))
        settle()
    }

    private fun search(text: String) {
        composeRule.onNodeWithTag(OrderTags.SEARCH).performTextReplacement(text)
        composeRule.onNodeWithTag(OrderTags.SEARCH).performImeAction()
        settle()
    }

    // ---------------------------------------------------------------- AC-S1-103 / 106 ①

    @Test
    fun ac_s1_103_groupFilterAndSearchOnlySendParamsAndShowServerResults() {
        launch()
        openOrders()
        assertEquals(mapOf("scope" to "self"), listRequests().last())
        // 分组只传 status_group，客户端不过滤：假服务故意回一张「预估中」的卡，页面照样显示
        synchronized(answers) { answers[LIST_PATH] = { envelope(listData(listOf(self1()))) } }
        click(OrderTags.GROUP_PREFIX + "credited")
        assertEquals(mapOf("scope" to "self", "status_group" to "credited"), listRequests().last())
        composeRule.onNodeWithTag(OrderTags.CARD_PREFIX + "ord-self-1").assertExists()
        composeRule.onNodeWithTag(OrderTags.GROUP_PREFIX + "credited").assertIsSelected()
        synchronized(answers) { answers.remove(LIST_PATH) }
        // 平台 + 月份叠加
        click(OrderTags.FILTER_PLATFORM)
        click(OrderTags.FILTER_OPTION_PREFIX + "taobao")
        click(OrderTags.FILTER_MONTH)
        click(OrderTags.FILTER_OPTION_PREFIX + "2026-10")
        assertEquals(
            mapOf("scope" to "self", "status_group" to "credited", "platform" to "taobao", "paid_month" to "2026-10"),
            listRequests().last(),
        )
        // 自购搜完整单号、前 10 位、标题词：各发 1 次 q
        val before = listRequests().size
        search("3741234567890123456")
        search("3741234567")
        search("纯牛奶")
        val sent = listRequests().drop(before).map { it["q"] }
        assertEquals(listOf("3741234567890123456", "3741234567", "纯牛奶"), sent)
        // 切到分享：分组回到全部、其余条件清空，搜索框提示为「搜商品名」
        click(OrderTags.TAB_SHARE)
        assertEquals(mapOf("scope" to "share"), listRequests().last())
        composeRule.onNodeWithTag(OrderTags.GROUP_PREFIX + "all").assertIsSelected()
        composeRule.onNodeWithText("搜商品名").assertExists()
        composeRule.onNodeWithTag(OrderTags.TAB_SHARE).assertIsSelected()
    }

    @Test
    fun ac_s1_106_shareRowsHaveNoCopyAndLongPressWritesNothing() {
        launch()
        openOrders(mapOf("scope" to "share"))
        assertEquals("share", listRequests().last()["scope"])
        composeRule.onNodeWithTag(OrderTags.ORDER_NO_PREFIX + "ord-share-1", useUnmergedTree = true).assertExists()
        assertEquals(
            "分享单没有复制按钮",
            0,
            composeRule.onAllNodesWithTag(OrderTags.COPY_PREFIX + "ord-share-1", useUnmergedTree = true).fetchSemanticsNodes().size,
        )
        composeRule.onNodeWithTag(OrderTags.ORDER_NO_PREFIX + "ord-share-1", useUnmergedTree = true).performTouchInput { longClick() }
        settle()
        assertEquals("长按不出复制菜单", 0, texts("复制"))
        assertEquals("系统剪贴板写入次数为 0", 0, system.writes)
        // 长按整卡按点击打开详情（分享单详情同样没有复制）：回到列表
        if (top()?.name == "OrderDetail") {
            composeRule.runOnUiThread { navController.popBackStack() }
            settle()
        }
        // 脱敏号原样显示，读屏读成「星号」
        val node = composeRule.onNodeWithTag(OrderTags.ORDER_NO_PREFIX + "ord-share-1", useUnmergedTree = true).fetchSemanticsNode()
        assertEquals("订单号 3712星号星号星号星号67", node.config.getOrNull(SemanticsProperties.ContentDescription)?.single())
        // 分享单搜单号：只发 q，结果只按假服务（空）显示，并带「分享订单只按商品名搜索」
        synchronized(answers) { answers[LIST_PATH] = { envelope(listData(emptyList())) } }
        search("3712345678901234567")
        assertEquals(mapOf("scope" to "share", "q" to "3712345678901234567"), listRequests().last())
        composeRule.onNodeWithText("分享订单只按商品名搜索").assertExists()
        composeRule.onNodeWithText("没有找到相关订单").assertExists()
    }

    @Test
    fun selfCopyGoesThroughTheWriterAndLedger() {
        launch()
        openOrders()
        click(OrderTags.COPY_PREFIX + "ord-self-1")
        assertEquals(1, system.writes)
        assertEquals("3741234567890123456", system.content)
        assertTrue("统一写入口记了自写 hash", ledger.contains("3741234567890123456"))
        composeRule.onNodeWithText("已复制订单号").assertExists()
        // 记 hash 失败：不写系统剪贴板，提示失败
        ledgerWrites = false
        click(OrderTags.COPY_PREFIX + "ord-self-1")
        assertEquals(1, system.writes)
        composeRule.onNodeWithText("复制失败，请重试").assertExists()
    }

    // ---------------------------------------------------------------- AC-S1-104

    @Test
    fun ac_s1_104_viewProductOpensProductDetailWithOnlyPlatformAndKey() {
        launch()
        openOrders()
        open("OrderDetail", mapOf("order_id" to "ord-o7"))
        idleUntil {
            composeRule
                .onAllNodesWithTag(
                    OrderTags.DETAIL_VIEW_PRODUCT,
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
        val buttons =
            composeRule
                .onAllNodesWithTag(
                    OrderTags.DETAIL_ACTION_PREFIX + "withdraw",
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
                .size
        click(OrderTags.DETAIL_VIEW_PRODUCT)
        idleUntil { top()?.name == "ProductDetail" }
        val params = Json.parseToJsonElement(top()!!.paramsJson).jsonObject
        assertEquals(setOf("platform", "product_key"), params.keys)
        assertEquals("tb:9007", (params["product_key"] as JsonPrimitive).content)
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        for (id in listOf("ord-o8", "ord-o10")) {
            open("OrderDetail", mapOf("order_id" to id))
            idleUntil { composeRule.onAllNodesWithTag(OrderTags.DETAIL_STATUS, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(
                "$id 没有「查看商品」",
                0,
                composeRule.onAllNodesWithTag(OrderTags.DETAIL_VIEW_PRODUCT, useUnmergedTree = true).fetchSemanticsNodes().size,
            )
            assertEquals(
                "按钮数不因「查看商品」变化",
                buttons,
                composeRule
                    .onAllNodesWithTag(
                        OrderTags.DETAIL_ACTION_PREFIX + "withdraw",
                        useUnmergedTree = true,
                    ).fetchSemanticsNodes()
                    .size,
            )
            composeRule.runOnUiThread { navController.popBackStack() }
            settle()
        }
    }

    // ---------------------------------------------------------------- AC-S1-27 / BR-TEXT-02

    @Test
    @Suppress("LongMethod") // 状态与原因逐项断言
    fun ac_s1_27_statusesReasonsAndButtons() {
        launch()
        synchronized(answers) { answers[LIST_PATH] = { envelope(listData(allStatuses())) } }
        openOrders()
        for (label in listOf("已付定金", "已付款，返利待确认", "已收货，等待联盟结算", "入账核对中", "售后处理中，入账暂停", "本单无返利", "已结算", "已失效", "已扣回", "状态更新中")) {
            scrollTo(OrderTags.CARD_PREFIX + "st-" + label.hashCode())
            assertTrue("列表有「$label」", texts(label) > 0)
        }
        assertForbiddenWordsAbsent()
        val cases =
            mapOf(
                "ord-refund" to Triple("订单已退款或取消", "退款或取消后平台不再支付返利", emptyList<String>()),
                "ord-rights" to Triple("发起售后维权，返利取消", "售后成功后平台不再支付这笔返利", emptyList()),
                "ord-blacklist" to Triple("订单未通过安全校验", "如有疑问可提交申诉", listOf("appeal")),
                "ord-punish" to Triple("订单被联盟判定违规", "如有疑问可联系客服", listOf("contact_cs")),
                "ord-zero" to Triple("返利已取消", "平台将这笔订单的返利调整为 0，平台未说明原因", listOf("contact_cs")),
                "ord-clawed-punish" to Triple("订单被联盟判定违规", "已从余额扣除", listOf("contact_cs", "ledger")),
            )
        for ((id, expected) in cases) {
            open("OrderDetail", mapOf("order_id" to id))
            idleUntil { composeRule.onAllNodesWithTag(OrderTags.DETAIL_REASON, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            assertTrue("$id 标题", texts(expected.first) > 0)
            assertTrue("$id 说明", texts(expected.second) > 0)
            for (action in listOf("appeal", "contact_cs", "ledger", "withdraw")) {
                val shown =
                    composeRule
                        .onAllNodesWithTag(
                            OrderTags.DETAIL_ACTION_PREFIX + action,
                            useUnmergedTree = true,
                        ).fetchSemanticsNodes()
                        .size
                assertEquals("$id 按钮 $action", if (action in expected.third) 1 else 0, shown)
            }
            assertForbiddenWordsAbsent()
            composeRule.runOnUiThread { navController.popBackStack() }
            settle()
        }
        // CLAWED_BACK + PUNISH：【联系客服】主按钮在前、【查看流水】在后，联系客服经 Help（G09）
        open("OrderDetail", mapOf("order_id" to "ord-clawed-punish"))
        idleUntil {
            composeRule
                .onAllNodesWithTag(
                    OrderTags.DETAIL_ACTION_PREFIX + "contact_cs",
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
        val primary =
            composeRule
                .onNodeWithTag(OrderTags.DETAIL_ACTION_PREFIX + "contact_cs")
                .fetchSemanticsNode()
                .boundsInRoot.left
        val second =
            composeRule
                .onNodeWithTag(OrderTags.DETAIL_ACTION_PREFIX + "ledger")
                .fetchSemanticsNode()
                .boundsInRoot.left
        assertTrue(primary < second)
        click(OrderTags.DETAIL_ACTION_PREFIX + "ledger")
        idleUntil { top()?.name != "OrderDetail" }
    }

    private fun assertForbiddenWordsAbsent() {
        for (word in listOf("佣金", "待结算", "结算中")) assertEquals("不出现「$word」", 0, texts(word))
    }

    // ---------------------------------------------------------------- AC-S1-73 / BR-TEXT-04 / 预售

    @Test
    fun ac_s1_73_historyHintOnlyWhenServerGivesTheDateAndDetail30701IsEmpty() {
        launch()
        synchronized(answers) { answers[LIST_PATH] = { envelope(listData(listOf(self1()), earliest = "2025-10-12")) } }
        openOrders()
        scrollTo(OrderTags.HISTORY_HINT)
        composeRule.onNodeWithText("只显示 2025-10-12 之后的订单，更早的订单请联系客服").assertExists()
        synchronized(answers) { answers[LIST_PATH] = { envelope(listData(listOf(self1()))) } }
        click(OrderTags.GROUP_PREFIX + "estimating")
        assertEquals(0, composeRule.onAllNodesWithTag(OrderTags.HISTORY_HINT).fetchSemanticsNodes().size)
        open("OrderDetail", mapOf("order_id" to "ord-missing"))
        idleUntil { composeRule.onAllNodesWithTag(OrderTags.DETAIL_EMPTY).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("内容不存在或已下线").assertExists()
        assertEquals("30701 不重试", 1, count("$LIST_PATH/ord-missing"))
    }

    @Test
    fun brText04_creditPeriodOnlyForWaitingAndCrossYear() {
        launch()
        openOrders()
        val expected =
            mapOf(
                "ord-waiting" to "预计 11 月结算",
                "ord-waiting-2027" to "预计 2027 年 1 月结算",
            )
        for ((id, text) in expected) {
            open("OrderDetail", mapOf("order_id" to id))
            idleUntil { composeRule.onAllNodesWithTag(OrderTags.DETAIL_STATUS).fetchSemanticsNodes().isNotEmpty() }
            assertTrue("$id 显示 $text", texts(text) > 0)
            composeRule.runOnUiThread { navController.popBackStack() }
            settle()
        }
        for (id in listOf("ord-crediting", "ord-reviewing", "ord-rights-pending", "ord-waiting-null")) {
            open("OrderDetail", mapOf("order_id" to id))
            idleUntil { composeRule.onAllNodesWithTag(OrderTags.DETAIL_STATUS).fetchSemanticsNodes().isNotEmpty() }
            assertEquals("$id 不显示月份", 0, texts(" 月结算"))
            composeRule.runOnUiThread { navController.popBackStack() }
            settle()
        }
    }

    @Test
    fun presaleShowsDepositAndTimelineStartsWithDeposit() {
        launch()
        synchronized(answers) { answers[LIST_PATH] = { envelope(listData(listOf(presale(3000), presale(null, "ord-presale-null")))) } }
        openOrders()
        composeRule.onNodeWithTag(OrderTags.CARD_PREFIX + "ord-presale").assert(hasTextInside("已付定金，30 元"))
        composeRule.onNodeWithTag(OrderTags.CARD_PREFIX + "ord-presale-null").assert(hasTextInside("已付定金"))
        assertFalse(
            "pay_amount_fen 为 null 不显示金额",
            composeRule
                .onNodeWithTag(
                    OrderTags.CARD_PREFIX + "ord-presale-null",
                ).fetchSemanticsNode()
                .textsInside()
                .any {
                    it.contains(" 元")
                },
        )
        open("OrderDetail", mapOf("order_id" to "ord-presale"))
        idleUntil { composeRule.onAllNodesWithTag(OrderTags.DETAIL_TIMELINE).fetchSemanticsNodes().isNotEmpty() }
        val timeline =
            composeRule
                .onNodeWithTag(OrderTags.DETAIL_TIMELINE)
                .fetchSemanticsNode()
                .textsInside()
                .drop(1)
        assertEquals("付定金", timeline.first())
        assertTrue(timeline.contains("付尾款"))
        assertFalse("预售单不写「付款」", timeline.contains("付款"))
        assertFalse(
            "不显示返利金额",
            composeRule
                .onNodeWithTag(OrderTags.DETAIL_AMOUNTS)
                .fetchSemanticsNode()
                .textsInside()
                .any { it.contains("预估返") },
        )
    }

    // ---------------------------------------------------------------- AC-S1-25 / AC-S1-84（待跟单卡）

    @Test
    fun ac_s1_25_pendingCardsFilterDismissedAndDismissSendsAttempt() {
        launch()
        synchronized(answers) { answers[PENDING_PATH] = { envelope(pendingTwo()) } }
        openOrders()
        idleUntil { composeRule.onAllNodesWithTag(PendingTrackTags.CARD).fetchSemanticsNodes().size == 1 }
        composeRule.onNodeWithText("在淘宝下单后，订单通常会在一段时间内同步到这里，同步可能有延迟").assertExists()
        // show_claim_entry=true 时有卡内找回入口
        composeRule.onNodeWithTag(PendingTrackTags.CLAIM, useUnmergedTree = true).assertExists()
        synchronized(answers) { answers[DISMISS_PREFIX] = { envelope("{}") } }
        click(PendingTrackTags.CLOSE)
        idleUntil { composeRule.onAllNodesWithTag(PendingTrackTags.CARD).fetchSemanticsNodes().isEmpty() }
        val dismiss = synchronized(requests) { requests.single { it.path.startsWith(DISMISS_PREFIX) } }
        assertEquals("POST", dismiss.method)
        assertEquals("$DISMISS_PREFIX" + "lnk-tb/dismiss", dismiss.path)
        assertEquals("att-tb-2", (Json.parseToJsonElement(dismiss.body).jsonObject["attempt_id"] as JsonPrimitive).content)
    }

    @Test
    fun pendingClaimHiddenWhenServerSaysNo() {
        launch()
        synchronized(answers) {
            answers[PENDING_PATH] = { envelope("""{"items":[${track("lnk-jd", "att-jd", "jd", claim = false, closed = false)}]}""") }
        }
        openOrders()
        idleUntil { composeRule.onAllNodesWithTag(PendingTrackTags.CARD).fetchSemanticsNodes().size == 1 }
        assertEquals(0, composeRule.onAllNodesWithTag(PendingTrackTags.CLAIM, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun pendingDismiss20001RefetchesAnd10405ForcesUpdate() {
        launch()
        synchronized(answers) {
            answers[PENDING_PATH] = { envelope(pendingTwo()) }
            answers[DISMISS_PREFIX] = { envelope("null", code = 20001).setResponseCode(400) }
        }
        openOrders()
        idleUntil { composeRule.onAllNodesWithTag(PendingTrackTags.CARD).fetchSemanticsNodes().size == 1 }
        val fetched = count(PENDING_PATH)
        click(PendingTrackTags.CLOSE)
        idleUntil { count(PENDING_PATH) == fetched + 1 }
        synchronized(answers) {
            answers[DISMISS_PREFIX] =
                { envelope("""{"min_supported_version":"9.0.0"}""", code = 10405).setResponseCode(426) }
        }
        click(PendingTrackTags.CLOSE)
        idleUntil { forcedUpdates > 0 }
    }

    @Test
    fun ac_s1_84_notificationRowOnlyUntilTappedAndNoSystemDialogBeforeTap() {
        launch(sdkInt = 33, notificationsEnabled = false)
        synchronized(answers) { answers[PENDING_PATH] = { envelope(pendingTwo()) } }
        openOrders()
        idleUntil { composeRule.onAllNodesWithTag(PendingTrackTags.NOTIFY_ROW, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("开启通知，跟单成功、返利结算和提现到账时会及时提醒你").assertExists()
        assertEquals("不点不弹系统框", 0, host.requests)
        click(PendingTrackTags.NOTIFY)
        idleUntil { composeRule.onAllNodesWithTag(PendingTrackTags.NOTIFY_ROW, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        assertEquals("点了才弹 1 次", 1, host.requests)
        // 之后再拉卡也不再出现
        composeRule.onNodeWithTag(OrderTags.TAB_SHARE).performClick()
        settle()
        composeRule.onNodeWithTag(OrderTags.TAB_SELF).performClick()
        settle()
        assertEquals(0, composeRule.onAllNodesWithTag(PendingTrackTags.NOTIFY_ROW, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun noNotificationRowBelowAndroid13() {
        launch(sdkInt = 32, notificationsEnabled = false)
        synchronized(answers) { answers[PENDING_PATH] = { envelope(pendingTwo()) } }
        openOrders()
        idleUntil { composeRule.onAllNodesWithTag(PendingTrackTags.CARD).fetchSemanticsNodes().size == 1 }
        assertEquals(0, composeRule.onAllNodesWithTag(PendingTrackTags.NOTIFY_ROW, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun g07_launchingPageFetchesCardsOnlyAfterReturningFromTheJump() {
        launch()
        synchronized(answers) { answers[PENDING_PATH] = { envelope(pendingTwo()) } }
        lateinit var page: AppOrders.PendingPage
        composeRule.runOnUiThread { page = orders.pendingFor("product-entry")!! }
        val before = count(PENDING_PATH)
        composeRule.runOnUiThread { page.jumped("taobao") }
        settle()
        assertEquals("外跳执行时还不拉", before, count(PENDING_PATH))
        composeRule.runOnUiThread { page.onStart() }
        settle()
        assertEquals("没离开过（没有 ON_STOP）不拉", before, count(PENDING_PATH))
        composeRule.runOnUiThread {
            page.onStop()
            page.onStart()
        }
        idleUntil {
            page.service.state.value.cards
                .isNotEmpty()
        }
        assertEquals(before + 1, count(PENDING_PATH))
        assertEquals(
            "只显示同平台那一张",
            listOf("taobao"),
            page.service.state.value.cards
                .map { it.platform },
        )
        composeRule.runOnUiThread { orders.followBackStackIds(emptySet()) }
    }

    // ---------------------------------------------------------------- TECH-20（断网缓存）

    @Test
    fun tech20_coldStartOfflineShowsCacheThenRefreshClearsBanner() {
        launch()
        openOrders()
        val store = orderCacheStore(context)
        assertTrue("默认视图第一页落盘", store.readList("user-one", "self") != null)
        // 冷启动断网：新的组合（新的 ViewModel）
        synchronized(answers) { answers[LIST_PATH] = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START) } }
        composeRule.runOnUiThread { routing.router.open(RouteRequest("Home", JsonObject(emptyMap()), RouteSource.IN_APP)) }
        relaunchOrdersOnly()
        idleUntil { composeRule.onAllNodesWithTag(OrderTags.STALE).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(texts("网络不可用，以下为") > 0)
        composeRule.onNodeWithTag(OrderTags.CARD_PREFIX + "ord-self-1").assertExists()
        // 恢复网络、下拉刷新成功：提示条消失
        synchronized(answers) { answers.remove(LIST_PATH) }
        refreshList()
        idleUntil { composeRule.onAllNodesWithTag(OrderTags.STALE).fetchSemanticsNodes().isEmpty() }
    }

    /**
     * R3-02（共性第 28 条）：退出时缓存目录局部故障（目录内标记与删除同时失败）→ 故障期间同账号重新登录 → 进程被杀、故障解除后重启
     * （新的 Store 实例，接 App 的 SharedPreferences 待清理状态）→ 断网读取：读不到退出前的订单。
     */
    @Test
    fun r3_02_failedLogoutClearThenSameAccountReloginAndRestartNeverReadsOldOrders() {
        launch()
        openOrders()
        assertTrue("前提：默认视图已落盘", orderCacheStore(context).readList("user-one", "self") != null)
        val root = AppOrders.cacheRoot(context)
        val accountDir = root.listFiles()!!.first { it.isDirectory }
        root.setWritable(false)
        accountDir.setWritable(false)
        try {
            // 退出：清理失败（被吞掉，只留内存与缓存目录之外的待清理状态）
            switchSession(null)
            assumeTrue("本机能模拟目录不可写（非 root 运行）", cacheFiles().any { it.name.startsWith("list_") })
            assertFalse("前提：目录内的标记也没写进去", File(root, "pending_clear").exists())
            // 故障期间同一账号重新登录
            switchSession("user-one")
        } finally {
            accountDir.setWritable(true)
            root.setWritable(true)
        }
        // 进程被杀、故障解除后重启：新实例（内存状态丢失），与 AppModule 同一份 SharedPreferences 待清理状态
        val restarted = OrderStaleCache.Store(root, com.couli.app.orderCachePendingFlag(context))
        restarted.reconcileOwner("user-one")
        assertNull("重启后断网读不到退出前的订单", restarted.readList("user-one", "self"))
        assertFalse("旧缓存文件已删除", cacheFiles().any { it.name.startsWith("list_") })
    }

    @Test
    fun tech20_filteredQueryAnd5xxxxNeverUseCache() {
        launch()
        openOrders()
        synchronized(answers) { answers[LIST_PATH] = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START) } }
        click(OrderTags.GROUP_PREFIX + "credited")
        idleUntil { composeRule.onAllNodesWithTag(OrderTags.ERROR).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, composeRule.onAllNodesWithTag(OrderTags.STALE).fetchSemanticsNodes().size)
        assertTrue(texts("网络不可用，请检查网络后重试") > 0)
        synchronized(answers) { answers[LIST_PATH] = { envelope("null", code = 50001, trace = "trace-00a1b2c3") } }
        click(OrderTags.GROUP_PREFIX + "all")
        idleUntil { texts("出了点问题，请稍后再试（a1b2c3）") > 0 }
        assertEquals("5xxxx 不用缓存冒充", 0, composeRule.onAllNodesWithTag(OrderTags.STALE).fetchSemanticsNodes().size)
    }

    @Test
    fun tech20_cacheDeletedOnLogoutAccountSwitchConsentAndEnvSwitch() {
        launch()
        openOrders()
        val store = orderCacheStore(context)
        assertTrue(store.readList("user-one", "self") != null)
        // 登出 → 再登录另一个账号：旧账号的缓存删除，新账号断网看到网络错误态而不是旧数据
        composeRule.runOnUiThread {
            tokens.clear()
            login.syncSession()
        }
        settle()
        assertNull(store.readList("user-one", "self"))
        assertFalse(cacheFiles().any { it.name.startsWith("list_") })
        composeRule.runOnUiThread {
            tokens.save("access-two", "refresh-two", "full", "user-two")
            login.syncSession()
        }
        synchronized(answers) { answers[LIST_PATH] = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START) } }
        settle()
        open("OrderList")
        idleUntil { composeRule.onAllNodesWithTag(OrderTags.ERROR).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, composeRule.onAllNodesWithTag(OrderTags.CARD_PREFIX + "ord-self-1").fetchSemanticsNodes().size)
        // 撤回同意、切换环境：AppModule 的两个清理注册表都删订单缓存
        store.writeList(
            "user-two",
            "self",
            com.couli.core.orders
                .OrderListData(emptyList(), null, null),
            now,
        )
        assertTrue(cacheFiles().isNotEmpty())
        runCatching { withdrawalCleaners().cleanAll() }
        assertTrue(cacheFiles().isEmpty())
        store.writeList(
            "user-two",
            "self",
            com.couli.core.orders
                .OrderListData(emptyList(), null, null),
            now,
        )
        // 其余清理项在这个测试里没有真实对象（会抛错），注册表照样执行全部清理项、最后抛第一个错误
        runCatching {
            environmentCleaners().cleanAll(
                com.couli.core.config.AppEnvironment.TEST,
                com.couli.core.config.AppEnvironment.STAGING,
            )
        }
        assertTrue(cacheFiles().isEmpty())
    }

    // ---------------------------------------------------------------- 迟到与作废、42901

    @Test
    fun lateListResponseAfterScopeSwitchIsDropped() {
        launch()
        openOrders()
        hold = CountDownLatch(1)
        synchronized(answers) { answers[LIST_PATH] = { envelope(listData(listOf(self1("ord-late")))) } }
        composeRule.onNodeWithTag(OrderTags.GROUP_PREFIX + "credited").performClick()
        Thread.sleep(LATE_MS)
        synchronized(answers) { answers[LIST_PATH] = { envelope(listData(listOf(share1()))) } }
        composeRule.onNodeWithTag(OrderTags.TAB_SHARE).performClick()
        hold?.countDown()
        hold = null
        settle()
        Thread.sleep(LATE_MS)
        settle()
        assertEquals("旧轮次的响应不覆盖新结果", 0, composeRule.onAllNodesWithTag(OrderTags.CARD_PREFIX + "ord-late").fetchSemanticsNodes().size)
        composeRule.onNodeWithTag(OrderTags.CARD_PREFIX + "ord-share-1").assertExists()
    }

    @Test
    fun detailPoppedWhileLoadingChangesNothing() {
        launch()
        openOrders()
        val gate = CountDownLatch(1)
        synchronized(answers) {
            answers["$LIST_PATH/ord-o7"] = {
                gate.await(WAIT_S, TimeUnit.SECONDS)
                envelope(detail("ord-o7", "CREDITED", productKey = "tb:9007", actual = 310))
            }
        }
        open("OrderDetail", mapOf("order_id" to "ord-o7"))
        idleUntil { count("$LIST_PATH/ord-o7") == 1 }
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        gate.countDown()
        settle()
        Thread.sleep(LATE_MS)
        settle()
        assertEquals("OrderList", top()?.name ?: "OrderList")
        assertEquals(0, composeRule.onAllNodesWithTag(OrderTags.DETAIL_PAGE).fetchSemanticsNodes().size)
        assertEquals("出栈后没有任何复制", 0, system.writes)
    }

    @Test
    fun rateLimitDisablesRefreshFiltersAndPaging() {
        launch()
        openOrders()
        synchronized(
            answers,
        ) { answers[LIST_PATH] = { envelope("null", code = 42901).setResponseCode(429).setHeader("Retry-After", "60") } }
        click(OrderTags.GROUP_PREFIX + "credited")
        idleUntil { texts("操作太频繁，请稍后再试") > 0 }
        val sent = listRequests().size
        composeRule.onNodeWithTag(OrderTags.GROUP_PREFIX + "estimating").assertIsNotEnabled()
        composeRule.onNodeWithTag(OrderTags.FILTER_PLATFORM).assertIsNotEnabled()
        composeRule.onNodeWithTag(OrderTags.SEARCH).assertIsNotEnabled()
        // 执行层也拦：直接调用刷新入口不发请求
        refreshList()
        settle()
        assertEquals(sent, listRequests().size)
    }

    // ---------------------------------------------------------------- 比价说明

    @Test
    fun priceCompareHintAndHelpLinkOnlyWhenConfigured() {
        launch(config = CONFIG_WITH_HELP)
        openOrders()
        open("OrderDetail", mapOf("order_id" to "ord-compare"))
        idleUntil { composeRule.onAllNodesWithTag(OrderTags.DETAIL_HELP, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("这笔订单被平台判为比价订单，没有返利").assertExists()
        click(OrderTags.DETAIL_HELP)
        idleUntil { top()?.name == "WebPage" || top()?.name == "Help" }
        assertTrue(top()!!.paramsJson.contains("price-compare"))
    }

    @Test
    fun priceCompareHelpHiddenWithoutConfig() {
        launch(config = CONFIG)
        openOrders()
        open("OrderDetail", mapOf("order_id" to "ord-compare"))
        idleUntil { composeRule.onAllNodesWithTag(OrderTags.DETAIL_REASON, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("这笔订单被平台判为比价订单，没有返利").assertExists()
        assertEquals(0, composeRule.onAllNodesWithTag(OrderTags.DETAIL_HELP, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun amountMaskHidesRebatesButNotPaidAmount() {
        launch()
        openOrders()
        click(OrderTags.MASK)
        val card = composeRule.onNodeWithTag(OrderTags.CARD_PREFIX + "ord-self-1").fetchSemanticsNode().textsInside()
        assertTrue("实付不隐藏", card.contains("实付金额，45.6 元"))
        assertFalse("预估返隐藏", card.any { it.contains("2.34") })
        assertTrue(card.contains("预估返，金额已隐藏"))
    }

    // ---------------------------------------------------------------- 第 1 轮评审修复（android-review-r1 R1-01..09，共性第 1–12 条）

    /** R1-01（共性第 1 条）：A 的详情在栈里（被别的页盖住）→ 会话换成 B → 返回详情：不得再看到 A 的订单号与金额，旧【复制】也不能写入。 */
    @Test
    fun r1_01_detailOfAccountAIsClearedWhenTheSessionSwitchesToB() {
        launch()
        openOrders()
        open("OrderDetail", mapOf("order_id" to "ord-o7"))
        idleUntil { composeRule.onAllNodesWithTag(OrderTags.DETAIL_ORDER_NO, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("A 的完整单号在详情里", texts("3741234567890123456") > 0)
        // 详情被盖住（「查看商品」→ ProductDetail），这时会话直接换成同样已登录的 B（网络层的会话通知同步交给 AppOrders，与 AppModule 同一入口）
        click(OrderTags.DETAIL_VIEW_PRODUCT)
        idleUntil { top()?.name == "ProductDetail" }
        synchronized(answers) { answers["$LIST_PATH/ord-o7"] = { envelope("null", code = 30701).setResponseCode(404) } }
        composeRule.runOnUiThread {
            tokens.save("access-two", "refresh-two", "full", "user-two")
            login.syncSession()
            orders.onSessionChanged()
        }
        settle()
        // 返回详情
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        idleUntil { top()?.name == "OrderDetail" }
        assertEquals("返回后不再显示 A 的订单号", 0, texts("3741234567890123456"))
        assertEquals("不再显示 A 的金额", 0, texts("3.1 元"))
        assertEquals(
            "旧的【复制】不在了",
            0,
            composeRule.onAllNodesWithTag(OrderTags.DETAIL_COPY, useUnmergedTree = true).fetchSemanticsNodes().size,
        )
        idleUntil { composeRule.onAllNodesWithTag(OrderTags.DETAIL_EMPTY).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("按 B 的会话重新请求了一次（服务端判归属，30701 空态）", 2, count("$LIST_PATH/ord-o7"))
        assertEquals("没有任何复制", 0, system.writes)
    }

    /** R1-02（共性第 2 条）：待跟单查询收到 42901 → 列表的刷新、筛选、搜索与详情请求都被同一份禁用期拦住，重进详情也不能绕过。 */
    @Test
    fun r1_02_pendingTracksRateLimitBlocksListAndDetailToo() {
        launch()
        synchronized(answers) {
            answers[PENDING_PATH] = { envelope("null", code = 42901).setResponseCode(429).setHeader("Retry-After", "60") }
        }
        openOrders()
        idleUntil { count(PENDING_PATH) >= 1 }
        settle()
        val sentList = listRequests().size
        val sentPending = count(PENDING_PATH)
        composeRule.onNodeWithTag(OrderTags.GROUP_PREFIX + "credited").assertIsNotEnabled()
        composeRule.onNodeWithTag(OrderTags.FILTER_PLATFORM).assertIsNotEnabled()
        composeRule.onNodeWithTag(OrderTags.SEARCH).assertIsNotEnabled()
        // 执行层：下拉刷新的入口不发列表与待跟单请求
        refreshList()
        assertEquals("列表不发", sentList, listRequests().size)
        assertEquals("待跟单查询自身也被拦", sentPending, count(PENDING_PATH))
        // 详情：禁用期内不发 getOrder，显示限流错误态、【重试】置灰；重进一次同样不发
        repeat(2) {
            open("OrderDetail", mapOf("order_id" to "ord-o7"))
            idleUntil { composeRule.onAllNodesWithTag(OrderTags.DETAIL_ERROR).fetchSemanticsNodes().isNotEmpty() }
            assertEquals("详情不发请求", 0, count("$LIST_PATH/ord-o7"))
            assertTrue(texts("操作太频繁，请稍后再试") > 0)
            composeRule.onNode(hasText("重试") and hasClickAction()).assertIsNotEnabled()
            composeRule.runOnUiThread { navController.popBackStack() }
            settle()
        }
    }

    /** R1-02：列表收到 42901 → 待跟单卡的关闭按钮、整页错误态的【重试】同步置灰，点关闭不发请求。 */
    @Test
    fun r1_02_listRateLimitDisablesCardCloseAndPageRetry() {
        launch()
        synchronized(answers) { answers[PENDING_PATH] = { envelope(pendingTwo()) } }
        openOrders()
        idleUntil { composeRule.onAllNodesWithTag(PendingTrackTags.CARD).fetchSemanticsNodes().size == 1 }
        synchronized(answers) {
            answers[LIST_PATH] = { envelope("null", code = 42901).setResponseCode(429).setHeader("Retry-After", "60") }
            answers[DISMISS_PREFIX] = { envelope("{}") }
        }
        click(OrderTags.GROUP_PREFIX + "credited")
        idleUntil { composeRule.onAllNodesWithTag(OrderTags.ERROR).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNode(hasText("重试") and hasClickAction()).assertIsNotEnabled()
        composeRule.onNodeWithTag(PendingTrackTags.CLOSE).assertIsNotEnabled()
        composeRule.onNodeWithTag(PendingTrackTags.CLOSE).performClick()
        settle()
        assertEquals("关闭不发请求", 0, count("$DISMISS_PREFIX" + "lnk-tb/dismiss"))
    }

    /** R1-03（共性第 7 条）：第二页在途时下拉刷新、刷新又失败 → 不卡在「加载中」，旧轮次的游标恢复，还能继续翻页。 */
    @Test
    fun r1_03_refreshDuringPagingThenFailureKeepsPagingUsable() {
        val pageTwo = CountDownLatch(1)
        launch()
        synchronized(answers) {
            answers[LIST_PATH] = { request ->
                if (request.path.orEmpty().contains("cursor=c2")) {
                    pageTwo.await(WAIT_S, TimeUnit.SECONDS)
                    envelope(listData(listOf(self1("ord-page2"))))
                } else {
                    envelope(listData(listOf(self1()), cursor = "c2"))
                }
            }
        }
        openOrders()
        // 只有一张卡：第一屏就触底，自动翻第二页（被假服务挡住，在途）
        idleUntil { listRequests().any { it["cursor"] == "c2" } }
        composeRule.onNodeWithText("加载中").assertExists()
        // 下拉刷新，刷新失败（50001）
        synchronized(answers) {
            val paging = answers.getValue(LIST_PATH)
            answers[LIST_PATH] = { request ->
                if (request.path.orEmpty().contains(
                        "cursor=c2",
                    )
                ) {
                    paging(request)
                } else {
                    envelope("null", code = 50001, trace = "trace-00abcdef")
                }
            }
        }
        refreshList()
        pageTwo.countDown()
        settle()
        Thread.sleep(LATE_MS)
        settle()
        composeRule.onNodeWithTag(OrderTags.CARD_PREFIX + "ord-self-1").assertExists()
        assertEquals("旧轮次的第二页不追加", 0, composeRule.onAllNodesWithTag(OrderTags.CARD_PREFIX + "ord-page2").fetchSemanticsNodes().size)
        assertEquals("不再卡在「加载中」", 0, texts("加载中"))
        composeRule.onNodeWithTag(OrderTags.LOAD_MORE).assertExists()
        // 还能继续翻页：页面的加载更多入口带着恢复的游标发出第二页
        val before = listRequests().count { it["cursor"] == "c2" }
        var started = false
        composeRule.runOnUiThread { started = (orders.listHandle() as com.couli.feature.orders.OrderListViewModel).controller.loadMore() }
        assertTrue("加载更多没有被拒绝", started)
        idleUntil { listRequests().count { it["cursor"] == "c2" } == before + 1 }
        idleUntil { composeRule.onAllNodesWithTag(OrderTags.CARD_PREFIX + "ord-page2").fetchSemanticsNodes().isNotEmpty() }
    }

    /** R1-04（共性第 8 条）：已有订单时刷新返回 5xxxx：轻提示带 trace6（未收录的 5xxxx 回落 error.50001 同样带）。 */
    @Test
    fun r1_04_refreshFailureToastKeepsTrace6() {
        launch()
        openOrders()
        synchronized(answers) { answers[LIST_PATH] = { envelope("null", code = 50001, trace = "trace-00abcdef") } }
        refreshList()
        idleUntil { composeRule.onAllNodesWithTag(OrderTags.TOAST).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag(OrderTags.CARD_PREFIX + "ord-self-1").assertExists()
        assertTrue("轻提示带 trace6", texts("出了点问题，请稍后再试（abcdef）") > 0)
        synchronized(answers) { answers[LIST_PATH] = { envelope("null", code = 50977, trace = "trace-00123abc") } }
        refreshList()
        idleUntil { texts("出了点问题，请稍后再试（123abc）") > 0 }
    }

    /** R1-05（共性第 9 条）：关闭待跟单卡返回 5xxxx：错误文案 + trace6，不是网络错误；卡片保留。 */
    @Test
    fun r1_05_dismissServerErrorShowsErrorWithTrace6NotNetwork() {
        launch()
        synchronized(answers) {
            answers[PENDING_PATH] = { envelope(pendingTwo()) }
            answers[DISMISS_PREFIX] = { envelope("null", code = 50401, trace = "trace-00fedcba").setResponseCode(504) }
        }
        openOrders()
        idleUntil { composeRule.onAllNodesWithTag(PendingTrackTags.CARD).fetchSemanticsNodes().size == 1 }
        click(PendingTrackTags.CLOSE)
        idleUntil { texts("fedcba") > 0 }
        assertEquals("不是网络错误", 0, texts("网络不可用"))
        composeRule.onNodeWithTag(PendingTrackTags.CARD).assertExists()
        // 未收录的 5xxxx：回落 error.50001 + trace6
        synchronized(
            answers,
        ) { answers[DISMISS_PREFIX] = { envelope("null", code = 50988, trace = "trace-00c0ffee").setResponseCode(500) } }
        click(PendingTrackTags.CLOSE)
        idleUntil { texts("出了点问题，请稍后再试（c0ffee）") > 0 }
        assertEquals(0, texts("网络不可用"))
    }

    /** R1-06（共性第 3 条）：关闭之前发出、之后才到的 pending-tracks 旧快照不得让已关闭的卡重新出现；同一 link 的新尝试照常显示。 */
    @Test
    fun r1_06_lateSnapshotDoesNotResurrectADismissedCard() {
        launch()
        synchronized(answers) { answers[PENDING_PATH] = { envelope(pendingTwo()) } }
        openOrders()
        idleUntil { composeRule.onAllNodesWithTag(PendingTrackTags.CARD).fetchSemanticsNodes().size == 1 }
        val late = CountDownLatch(1)
        synchronized(answers) {
            answers[PENDING_PATH] = {
                late.await(WAIT_S, TimeUnit.SECONDS)
                envelope(pendingTwo())
            }
            answers[DISMISS_PREFIX] = { envelope("{}") }
        }
        val fetched = count(PENDING_PATH)
        refreshList()
        idleUntil { count(PENDING_PATH) == fetched + 1 }
        click(PendingTrackTags.CLOSE)
        idleUntil { composeRule.onAllNodesWithTag(PendingTrackTags.CARD).fetchSemanticsNodes().isEmpty() }
        late.countDown()
        settle()
        Thread.sleep(LATE_MS)
        settle()
        assertEquals("旧快照不让关闭的卡复活", 0, composeRule.onAllNodesWithTag(PendingTrackTags.CARD).fetchSemanticsNodes().size)
        // 同一 link 的新尝试照常显示
        synchronized(answers) {
            answers[PENDING_PATH] = {
                envelope("""{"items":[${track("lnk-tb", "att-tb-3", "taobao", claim = false, closed = false)}]}""")
            }
        }
        refreshList()
        idleUntil { composeRule.onAllNodesWithTag(PendingTrackTags.CARD).fetchSemanticsNodes().size == 1 }
    }

    /** R1-07（共性第 10 条）：进程回收后恢复，目标订单在第二页：不丢弃恢复任务，按保存的条件翻到目标页后一次性让目标卡顶对齐。 */
    @Test
    fun r1_07_processRestoreLoadsTheTargetPageThenTopAlignsTheTargetCard() {
        val seed =
            android.os.Bundle().apply {
                putString("orders.scope", "self")
                putString("orders.group", "all")
                putString("orders.input", "")
                putInt("orders.anchor_index", 26)
                putInt("orders.anchor_offset", 0)
                putString("orders.anchor_order_id", "ord-25")
                putString("orders.owner", ownerOf("user-one"))
            }
        synchronized(answers) {
            answers[LIST_PATH] = { request ->
                val second = request.path.orEmpty().contains("cursor=c2")
                val ids = if (second) 20 until 40 else 0 until 20
                envelope(listData(ids.map { self1("ord-$it") }, cursor = if (second) null else "c2"))
            }
        }
        launchRestored(RestoredOwner(seed))
        idleUntil { composeRule.onAllNodesWithTag(OrderTags.CARD_PREFIX + "ord-25").fetchSemanticsNodes().isNotEmpty() }
        settle()
        assertEquals("按保存的条件取第一页、再翻到目标页", listOf(null, "c2"), listRequests().map { it["cursor"] })
        val list = composeRule.onNodeWithTag(OrderTags.LIST).fetchSemanticsNode().boundsInRoot
        val target = composeRule.onNodeWithTag(OrderTags.CARD_PREFIX + "ord-25").fetchSemanticsNode().boundsInRoot
        val padding =
            with(composeRule.density) {
                com.couli.core.designsystem.CouliSpacing.space4
                    .toPx()
            }
        assertTrue("目标卡顶对齐（顶 ${target.top}，列表顶 ${list.top}）", kotlin.math.abs(target.top - list.top) <= padding + 1f)
    }

    /** R1-08（共性第 11 条）：320dp + 200% 字号，已选「拼多多」+「2026 年 10 月」：眼睛按钮保住完整 48 命中区，筛选与箭头不被挤出。 */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE) // 真实测量文字宽度（默认的图形模式下文字宽度近似为 0，挤不出问题）
    @Config(qualifiers = "w320dp-h844dp-xxhdpi", fontScale = 2f)
    fun r1_08_narrowLargeTextSelectedFiltersKeepTheMaskTarget() {
        launch()
        openOrders()
        click(OrderTags.FILTER_PLATFORM)
        composeRule.onNodeWithTag(OrderTags.FILTER_OPTION_PREFIX + "pdd").performScrollTo().performClick()
        settle()
        click(OrderTags.FILTER_MONTH)
        composeRule.onNodeWithTag(OrderTags.FILTER_OPTION_PREFIX + "2026-10").performScrollTo().performClick()
        settle()
        assertEquals(mapOf("scope" to "self", "platform" to "pdd", "paid_month" to "2026-10"), listRequests().last())
        val mask = composeRule.onNodeWithTag(OrderTags.MASK).fetchSemanticsNode().boundsInRoot
        val month = composeRule.onNodeWithTag(OrderTags.FILTER_MONTH).fetchSemanticsNode().boundsInRoot
        val platform = composeRule.onNodeWithTag(OrderTags.FILTER_PLATFORM).fetchSemanticsNode().boundsInRoot
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val min = with(composeRule.density) { 48.dp.toPx() }
        assertTrue("眼睛按钮宽 ${mask.width} ≥ 48dp", mask.width >= min - 1f)
        assertTrue("眼睛按钮高 ${mask.height} ≥ 48dp", mask.height >= min - 1f)
        assertTrue("眼睛按钮在屏内", mask.right <= root.right + 1f)
        for (button in listOf(month, platform)) {
            assertTrue("筛选按钮不压住眼睛按钮", button.right <= mask.left + 1f || button.bottom <= mask.top + 1f)
            assertTrue("筛选按钮在屏内", button.right <= root.right + 1f)
        }
        composeRule.onNodeWithText("拼多多").assertExists()
        composeRule.onNodeWithText("2026 年 10 月").assertExists()
    }

    /** R1-09（共性第 12 条）：订单页每个跳转入口真实点击后的精确落点路由与参数。 */
    @Test
    @Suppress("LongMethod") // 入口逐个点
    fun r1_09_everyOrderEntryLandsOnTheExactRouteWithExactParams() {
        launch(config = CONFIG_WITH_HELP)
        synchronized(answers) {
            answers[PENDING_PATH] = { envelope(pendingTwo()) }
            answers["$LIST_PATH/ord-reauth"] = { envelope(detail("ord-reauth", "INVALID", reason = "RIGHTS", actions = listOf("REAUTH"))) }
            answers["$LIST_PATH/ord-appealing"] = {
                envelope(
                    detail("ord-appealing", "INVALID", reason = "BLACKLIST", actions = listOf("APPEAL"))
                        .replace("\"appeal_pending\":false", "\"appeal_pending\":true"),
                )
            }
        }
        openOrders()
        // 标题右上「未跟单？去找回」→ FindOrder（H5，无参数）
        landsOn(OrderTags.FIND_ENTRY, "FindOrder", emptyMap())
        // 待跟单卡内「未跟单？去找回」→ FindOrder
        idleUntil { composeRule.onAllNodesWithTag(PendingTrackTags.CLAIM, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        landsOn(PendingTrackTags.CLAIM, "FindOrder", emptyMap())
        // 整卡 → OrderDetail {order_id}
        landsOn(OrderTags.CARD_PREFIX + "ord-self-1", "OrderDetail", mapOf("order_id" to "ord-self-1"), back = false)
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        val details =
            listOf(
                Triple("ord-clawed-punish", "ledger", "Ledger" to emptyMap<String, String>()),
                Triple("ord-clawed-punish", "contact_cs", "Help" to emptyMap()),
                Triple("ord-blacklist", "appeal", "Appeal" to emptyMap()),
                Triple("ord-reauth", "reauth", "AuthManage" to emptyMap()),
            )
        for ((id, action, expected) in details) {
            open("OrderDetail", mapOf("order_id" to id))
            idleUntil {
                composeRule
                    .onAllNodesWithTag(
                        OrderTags.DETAIL_ACTION_PREFIX + action,
                        useUnmergedTree = true,
                    ).fetchSemanticsNodes()
                    .isNotEmpty()
            }
            landsOn(OrderTags.DETAIL_ACTION_PREFIX + action, expected.first, expected.second)
            composeRule.runOnUiThread { navController.popBackStack() }
            settle()
        }
        // 【去提现】→ Withdraw（auth=realname）：页面经 Router 请求的是 Withdraw {}（不带任何订单信息）；手机号未确认的会话由守卫先进 BindPhone
        // （落点精确到守卫页；实名状态来源未定，TODO(N-14)）
        open("OrderDetail", mapOf("order_id" to "ord-o8"))
        idleUntil {
            composeRule
                .onAllNodesWithTag(
                    OrderTags.DETAIL_ACTION_PREFIX + "withdraw",
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
        landsOn(OrderTags.DETAIL_ACTION_PREFIX + "withdraw", "BindPhone", emptyMap(), back = false)
        val outcome = routing.router.lastOutcome.value!!
        assertEquals("页面请求的路由", "Withdraw", outcome.request.route)
        assertEquals("页面请求的参数", JsonObject(emptyMap()), outcome.request.params)
        assertEquals("守卫落点", "BindPhone", outcome.decision.route)
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        // 「申诉中」块【查看申诉进度】→ Appeal
        open("OrderDetail", mapOf("order_id" to "ord-appealing"))
        idleUntil { composeRule.onAllNodesWithText("查看申诉进度", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("查看申诉进度", useUnmergedTree = true).performClick()
        settle()
        idleUntil { top()?.name == "Appeal" }
        assertEquals(emptyMap<String, String>(), params())
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        // 比价「查看说明」→ Help {article_id}；「查看商品」→ ProductDetail {platform, product_key}
        open("OrderDetail", mapOf("order_id" to "ord-compare"))
        idleUntil { composeRule.onAllNodesWithTag(OrderTags.DETAIL_HELP, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        landsOn(OrderTags.DETAIL_HELP, "Help", mapOf("article_id" to "price-compare"))
        landsOn(OrderTags.DETAIL_VIEW_PRODUCT, "ProductDetail", mapOf("platform" to "taobao", "product_key" to "tb:sampleA"))
    }

    /** 共性第 4 条：点通知行【开启通知】直接进系统申请，不再叠一层用途说明浮层。 */
    @Test
    fun lesson4_notificationRowGoesStraightToTheSystemDialog() {
        launch(sdkInt = 33, notificationsEnabled = false)
        synchronized(answers) { answers[PENDING_PATH] = { envelope(pendingTwo()) } }
        openOrders()
        idleUntil { composeRule.onAllNodesWithTag(PendingTrackTags.NOTIFY, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        click(PendingTrackTags.NOTIFY)
        idleUntil { host.requests == 1 }
        assertEquals("弹系统框时没有用途说明浮层", listOf<PermissionPrompt?>(null), host.promptsAtRequest)
    }

    /** 共性第 5 条：详情金额格横排时左实付、右返利（与列表卡一致）。 */
    @Test
    fun lesson5_detailAmountsPutPaidLeftAndRebateRight() {
        launch()
        openOrders()
        open("OrderDetail", mapOf("order_id" to "ord-self-1"))
        idleUntil { composeRule.onAllNodesWithTag(OrderTags.DETAIL_AMOUNTS).fetchSemanticsNodes().isNotEmpty() }
        val paid = amountLabel("实付金额")
        val rebate = amountLabel("预估返")
        assertTrue("横排：实付在左（${paid.left}）、返利在右（${rebate.left}）", paid.left < rebate.left)
    }

    /** 共性第 5 条：200% 字号纵排时返利在上、实付在下（详情）。 */
    @Test
    @Config(qualifiers = "w390dp-h844dp-xxhdpi", fontScale = 2f)
    fun lesson5_detailAmountsStackRebateAboveAtLargeText() {
        launch()
        openOrders()
        open("OrderDetail", mapOf("order_id" to "ord-self-1"))
        idleUntil { composeRule.onAllNodesWithTag(OrderTags.DETAIL_AMOUNTS).fetchSemanticsNodes().isNotEmpty() }
        val paid = amountLabel("实付金额")
        val rebate = amountLabel("预估返")
        assertTrue("纵排：返利在上（${rebate.top}）、实付在下（${paid.top}）", rebate.top < paid.top)
    }

    /** 共性第 13 条：列表请求在途时别处触发 42901 → 网络层的 GET 自动重试（断连重试 1 次）在实际写出前被禁用期拦住，页面显示限流态。 */
    @Test
    fun lesson13_inFlightAutoRetryStopsOnceTheSharedRateLimitStarts() {
        launch()
        openOrders()
        val held = CountDownLatch(1)
        synchronized(answers) {
            answers[LIST_PATH] = {
                held.await(WAIT_S, TimeUnit.SECONDS)
                MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
            }
            answers["$LIST_PATH/ord-limit"] = { envelope("null", code = 42901).setResponseCode(429).setHeader("Retry-After", "60") }
        }
        click(OrderTags.GROUP_PREFIX + "credited")
        idleUntil { listRequests().count { it["status_group"] == "credited" } == 1 }
        // 列表请求在途时，详情请求收到 42901：会话级禁用期开始
        open("OrderDetail", mapOf("order_id" to "ord-limit"))
        idleUntil { composeRule.onAllNodesWithTag(OrderTags.DETAIL_ERROR).fetchSemanticsNodes().isNotEmpty() }
        held.countDown()
        settle()
        Thread.sleep(LATE_MS)
        settle()
        assertEquals("断连后的自动重试没有再发包", 1, listRequests().count { it["status_group"] == "credited" })
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        idleUntil { composeRule.onAllNodesWithTag(OrderTags.ERROR).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("列表显示限流态而不是停在加载中", texts("操作太频繁，请稍后再试") > 0)
    }

    /**
     * R3-01（共性第 27 条）：关闭待跟单卡的请求已发出、回包未到时，另一请求收到 42901 开始共享禁用期 → 已发出的关闭不被取消，
     * 成功照常隐藏卡片并记下 attempt_id（之后旧快照 dismissed=false 也不再显示）。
     */
    @Test
    fun r3_01_dismissInFlightStillSucceedsWhenAnotherRequestStartsTheRateLimit() {
        val dismissed = CountDownLatch(1)
        dismissInFlightWhileRateLimitStarts(dismissed) { envelope("{}") }
        idleUntil { composeRule.onAllNodesWithTag(PendingTrackTags.CARD).fetchSemanticsNodes().isEmpty() }
        assertTrue("记下已关闭的尝试", PendingClosedAttempts.key("lnk-tb", "att-tb-2") in orders.closedAttempts.keys.value)
        assertEquals("关闭只发了一次", 1, count("$DISMISS_PREFIX" + "lnk-tb/dismiss"))
        // 禁用期结束后旧快照（dismissed=false）再到：同一尝试不得重新出现
        composeRule.runOnUiThread { orders.rateGate.reset() }
        val fetched = count(PENDING_PATH)
        refreshList()
        idleUntil { count(PENDING_PATH) > fetched }
        settle()
        assertEquals(0, composeRule.onAllNodesWithTag(PendingTrackTags.CARD).fetchSemanticsNodes().size)
    }

    /** R3-01：关闭在途时另一请求触发 42901 → 关闭回包是 10405，仍进入已有的强更分流（不被当作作废丢掉）。 */
    @Test
    fun r3_01_dismissInFlightStill10405ForcesUpdateDuringTheRateLimit() {
        dismissInFlightWhileRateLimitStarts(CountDownLatch(1)) {
            envelope("""{"min_supported_version":"9.0.0"}""", code = 10405).setResponseCode(426)
        }
        idleUntil { forcedUpdates > 0 }
    }

    /** 待跟单卡的关闭请求在途（被假服务挡住）→ 详情请求收到 42901（会话级禁用期开始，等过网络层的在途核对间隔）→ 放行关闭的回包 [answer]。 */
    private fun dismissInFlightWhileRateLimitStarts(
        release: CountDownLatch,
        answer: () -> MockResponse,
    ) {
        launch()
        synchronized(answers) { answers[PENDING_PATH] = { envelope(pendingTwo()) } }
        openOrders()
        idleUntil { composeRule.onAllNodesWithTag(PendingTrackTags.CARD).fetchSemanticsNodes().size == 1 }
        synchronized(answers) {
            answers[DISMISS_PREFIX] = {
                release.await(WAIT_S, TimeUnit.SECONDS)
                answer()
            }
            answers["$LIST_PATH/ord-limit"] = { envelope("null", code = 42901).setResponseCode(429).setHeader("Retry-After", "60") }
        }
        click(PendingTrackTags.CLOSE)
        idleUntil { count("$DISMISS_PREFIX" + "lnk-tb/dismiss") == 1 }
        open("OrderDetail", mapOf("order_id" to "ord-limit"))
        idleUntil { composeRule.onAllNodesWithTag(OrderTags.DETAIL_ERROR).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("禁用期已开始", orders.rateGate.active)
        // 超过网络层在途核对间隔（50ms）数倍：若禁用期进了归属，在途的关闭此时已被取消
        Thread.sleep(LATE_MS)
        release.countDown()
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
    }

    /** 共性第 14 条：关闭在途期间另一轮查询去掉又重建了同一尝试的卡片，按钮仍忙碌；关闭失败后清掉忙碌，用户能再点。 */
    @Test
    fun lesson14_busyFollowsTheAttemptAcrossRefetchesAndClearsAfterFailure() {
        launch()
        synchronized(answers) { answers[PENDING_PATH] = { envelope(pendingTwo()) } }
        openOrders()
        idleUntil { composeRule.onAllNodesWithTag(PendingTrackTags.CARD).fetchSemanticsNodes().size == 1 }
        val dismiss = CountDownLatch(1)
        synchronized(answers) {
            answers[DISMISS_PREFIX] = {
                dismiss.await(WAIT_S, TimeUnit.SECONDS)
                envelope("null", code = 50001, trace = "trace-00beef01").setResponseCode(500)
            }
        }
        click(PendingTrackTags.CLOSE)
        idleUntil { count("$DISMISS_PREFIX" + "lnk-tb/dismiss") == 1 }
        composeRule.onNodeWithTag(PendingTrackTags.CLOSE).assertIsNotEnabled()
        // 另一轮查询先没有这张卡、再一轮又重建了它：按钮仍是忙碌（不能重复发）
        synchronized(answers) { answers[PENDING_PATH] = { envelope("""{"items":[]}""") } }
        refreshList()
        idleUntil { composeRule.onAllNodesWithTag(PendingTrackTags.CARD).fetchSemanticsNodes().isEmpty() }
        synchronized(answers) { answers[PENDING_PATH] = { envelope(pendingTwo()) } }
        refreshList()
        idleUntil { composeRule.onAllNodesWithTag(PendingTrackTags.CARD).fetchSemanticsNodes().size == 1 }
        composeRule.onNodeWithTag(PendingTrackTags.CLOSE).assertIsNotEnabled()
        // 关闭失败：忙碌清掉，能再点
        dismiss.countDown()
        idleUntil { texts("beef01") > 0 }
        composeRule.onNodeWithTag(PendingTrackTags.CLOSE).assertIsEnabled()
    }

    /** 共性第 15 条：列表关闭成功后，商品详情页（另一显示位置）此前发出、之后才到的查询不得让这张卡在那里复活。 */
    @Test
    fun lesson15_closedAttemptIsSharedAcrossDisplayPositions() {
        launch()
        synchronized(answers) { answers[PENDING_PATH] = { envelope(pendingTwo()) } }
        openOrders()
        idleUntil { composeRule.onAllNodesWithTag(PendingTrackTags.CARD).fetchSemanticsNodes().size == 1 }
        lateinit var page: AppOrders.PendingPage
        composeRule.runOnUiThread { page = orders.pendingFor("product-entry")!! }
        val late = CountDownLatch(1)
        synchronized(answers) {
            answers[PENDING_PATH] = {
                late.await(WAIT_S, TimeUnit.SECONDS)
                envelope(pendingTwo())
            }
            answers[DISMISS_PREFIX] = { envelope("{}") }
        }
        val fetched = count(PENDING_PATH)
        composeRule.runOnUiThread {
            page.jumped("taobao")
            page.onStop()
            page.onStart()
        }
        idleUntil { count(PENDING_PATH) == fetched + 1 }
        click(PendingTrackTags.CLOSE)
        idleUntil { composeRule.onAllNodesWithTag(PendingTrackTags.CARD).fetchSemanticsNodes().isEmpty() }
        late.countDown()
        settle()
        Thread.sleep(LATE_MS)
        settle()
        assertEquals(
            "商品详情页的旧快照不让已关闭的尝试复活",
            emptyList<String>(),
            page.service.state.value.cards
                .map { it.attemptId },
        )
        composeRule.runOnUiThread { orders.followBackStackIds(emptySet()) }
    }

    /** 共性第 16 条：已收录但文案里没有 {trace6} 的 5xxxx（50301、50303）也由统一错误消息独立追加 trace6；填不上的占位符不露出。 */
    @Test
    fun lesson16_trace6IsAppendedEvenWithoutAPlaceholder() {
        launch()
        synchronized(answers) { answers[PENDING_PATH] = { envelope(pendingTwo()) } }
        openOrders()
        synchronized(answers) { answers[LIST_PATH] = { envelope("null", code = 50301, trace = "trace-00d00d01").setResponseCode(503) } }
        refreshList()
        idleUntil { texts("（d00d01）") > 0 }
        assertEquals("不露出占位符", 0, texts("{platform_name}"))
        idleUntil { composeRule.onAllNodesWithTag(PendingTrackTags.CARD).fetchSemanticsNodes().size == 1 }
        synchronized(
            answers,
        ) { answers[DISMISS_PREFIX] = { envelope("null", code = 50303, trace = "trace-00d00d03").setResponseCode(503) } }
        click(PendingTrackTags.CLOSE)
        idleUntil { texts("暂时无法确认价格，请稍后再试（d00d03）") > 0 }
    }

    /** 共性第 17 条：恢复进行中（目标页还没到）持久化锚点保持离开前的那张卡，不被恢复途中的滚动覆盖；对齐完成后才更新。 */
    @Test
    fun lesson17_anchorIsFrozenWhileRestoringAndUpdatedAfterAlignment() {
        val seed =
            android.os.Bundle().apply {
                putString("orders.scope", "self")
                putString("orders.group", "all")
                putString("orders.input", "")
                putInt("orders.anchor_index", 26)
                putInt("orders.anchor_offset", 0)
                putString("orders.anchor_order_id", "ord-25")
                putString("orders.owner", ownerOf("user-one"))
            }
        val pageTwo = CountDownLatch(1)
        synchronized(answers) {
            answers[LIST_PATH] = { request ->
                val second = request.path.orEmpty().contains("cursor=c2")
                if (second) pageTwo.await(WAIT_S, TimeUnit.SECONDS)
                val ids = if (second) 20 until 40 else 0 until 20
                envelope(listData(ids.map { self1("ord-$it") }, cursor = if (second) null else "c2"))
            }
        }
        launchRestored(RestoredOwner(seed))
        idleUntil { listRequests().any { it["cursor"] == "c2" } }
        settle()
        val model = orders.listHandle() as com.couli.feature.orders.OrderListViewModel
        assertEquals("恢复途中锚点不被第一页的顶部位置覆盖", "ord-25", model.anchor.orderId)
        assertEquals(26, model.anchor.index)
        pageTwo.countDown()
        idleUntil { composeRule.onAllNodesWithTag(OrderTags.CARD_PREFIX + "ord-25").fetchSemanticsNodes().isNotEmpty() }
        settle()
        assertEquals("对齐完成后锚点更新为落点", "ord-25", model.anchor.orderId)
        assertEquals(26, model.anchor.index)
    }

    // ---------------------------------------------------------------- 第 2 轮评审

    /**
     * R2-01（S0，共性第 19 条）：A 用完整自购单号搜索、开着筛选半屏，会话失效后登录 B，再返回订单 Tab：搜索框、半屏、请求里都不再有 A 的单号；
     * 页面被系统回收后重建（保存状态 → 新实例恢复），也恢复不出 A 的单号。
     */
    @Test
    fun r2_01_fullOrderNoSearchedByAIsGoneAfterBLogsInAndAfterRecreation() {
        launchRestored(RestoredOwner(android.os.Bundle()))
        idleUntil { listRequests().isNotEmpty() }
        settle()
        search(A_ORDER_NO)
        assertEquals(A_ORDER_NO, searchText())
        assertEquals(A_ORDER_NO, listRequests().last()["q"])
        click(OrderTags.FILTER_PLATFORM)
        idleUntil { composeRule.onAllNodesWithTag(OrderTags.FILTER_OPTION_PREFIX + "taobao").fetchSemanticsNodes().isNotEmpty() }
        // A 的会话失效 → 登录 B（B 的列表为空：页面上任何位置出现 A 的单号都只能是残留）
        synchronized(answers) { answers[LIST_PATH] = { envelope(listData(emptyList())) } }
        switchSession(null)
        val before = listRequests().size
        switchSession("user-two")
        idleUntil { listRequests().size > before }
        settle()
        assertEquals("返回后搜索框是空的", "", searchText())
        assertEquals("页面上没有 A 的完整单号", 0, texts(A_ORDER_NO))
        assertEquals(
            "A 时打开的筛选半屏已关闭",
            0,
            composeRule.onAllNodesWithTag(OrderTags.FILTER_OPTION_PREFIX + "taobao").fetchSemanticsNodes().size,
        )
        assertTrue("B 的请求不带 A 的搜索词", listRequests().drop(before).none { it.containsKey("q") })
        // 页面被系统回收后重建：保存的状态里没有 A 的单号
        val beforeRecreate = listRequests().size
        recreateRestored()
        idleUntil { listRequests().size > beforeRecreate }
        settle()
        assertEquals("重建后搜索框是空的", "", searchText())
        assertEquals("重建后页面上没有 A 的完整单号", 0, texts(A_ORDER_NO))
        assertTrue("重建后的请求不带 A 的搜索词", listRequests().drop(beforeRecreate).none { it.containsKey("q") })
    }

    /** R2-01（共性第 19 条）：保存状态绑定账号归属，恢复前校验——别的账号保存的搜索词、筛选、锚点与半屏一律不恢复。 */
    @Test
    fun r2_01_savedStateOfAnotherAccountIsNotRestored() {
        val seed =
            android.os.Bundle().apply {
                putString("orders.scope", "self")
                putString("orders.group", "all")
                putString("orders.platform", "taobao")
                putString("orders.q", A_ORDER_NO)
                putString("orders.input", A_ORDER_NO)
                putString("orders.sheet", "PLATFORM")
                putInt("orders.anchor_index", 3)
                putInt("orders.anchor_offset", 0)
                putString("orders.anchor_order_id", "ord-self-1")
                putString("orders.owner", ownerOf("user-zero"))
            }
        synchronized(answers) { answers[LIST_PATH] = { envelope(listData(emptyList())) } }
        launchRestored(RestoredOwner(seed))
        idleUntil { listRequests().isNotEmpty() }
        settle()
        assertEquals("搜索框不恢复别的账号的输入", "", searchText())
        assertEquals("按默认条件取第一页", listOf(mapOf("scope" to "self")), listRequests())
        assertEquals("别的账号的半屏不恢复", 0, composeRule.onAllNodesWithTag(OrderTags.FILTER_OPTION_PREFIX + "taobao").fetchSemanticsNodes().size)
        val model = orders.listHandle() as com.couli.feature.orders.OrderListViewModel
        assertNull("别的账号的滚动锚点不恢复", model.pendingRestore)
        assertEquals(0, texts(A_ORDER_NO))
    }

    /** R2-04（共性第 21 条）：恢复等待版本检查期间用户改了平台筛选，等待结束后不得用保存的旧条件覆盖。 */
    @Test
    fun r2_04_platformChosenWhileTheRestoreWaitsIsNotOverwritten() {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        launchRestored(RestoredOwner(restoreSeed()), restoreGate = { gate.await() })
        settle()
        assertTrue("等待期间不发请求", listRequests().isEmpty())
        click(OrderTags.FILTER_PLATFORM)
        composeRule.onNodeWithTag(OrderTags.FILTER_OPTION_PREFIX + "pdd").performScrollTo().performClick()
        settle()
        assertEquals(mapOf("scope" to "self", "platform" to "pdd"), listRequests().last())
        val sent = listRequests().size
        composeRule.runOnUiThread { gate.complete(Unit) }
        settle()
        Thread.sleep(LATE_MS)
        settle()
        assertEquals("等待结束后不再按旧条件重发", sent, listRequests().size)
        assertEquals(mapOf("scope" to "self", "platform" to "pdd"), listRequests().last())
        val filter = composeRule.onNodeWithTag(OrderTags.FILTER_PLATFORM).fetchSemanticsNode().textsInside()
        assertTrue("平台筛选显示拼多多：$filter", filter.any { it.contains("拼多多") })
        assertFalse("页面没有退回旧筛选：$filter", filter.any { it.contains("淘宝") })
    }

    /** R2-04（共性第 21 条）：恢复等待期间提交搜索，搜索建立在已恢复的条件上；等待结束后不被旧条件覆盖。 */
    @Test
    fun r2_04_searchSubmittedWhileTheRestoreWaitsIsNotOverwritten() {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        launchRestored(RestoredOwner(restoreSeed()), restoreGate = { gate.await() })
        settle()
        assertTrue("等待期间不发请求", listRequests().isEmpty())
        search("纯牛奶")
        assertEquals(mapOf("scope" to "self", "platform" to "taobao", "q" to "纯牛奶"), listRequests().last())
        val sent = listRequests().size
        composeRule.runOnUiThread { gate.complete(Unit) }
        settle()
        Thread.sleep(LATE_MS)
        settle()
        assertEquals("等待结束后不再按旧条件重发", sent, listRequests().size)
        assertEquals("纯牛奶", searchText())
    }

    /** 共性第 19、21 条：恢复等待期间会话换成 B，等待结束后按 B 的默认条件打开，不带 A 保存的搜索词。 */
    @Test
    fun r2_04_sessionSwitchWhileTheRestoreWaitsDropsTheSavedConditions() {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val seed =
            restoreSeed().apply {
                putString("orders.q", A_ORDER_NO)
                putString("orders.input", A_ORDER_NO)
            }
        launchRestored(RestoredOwner(seed), restoreGate = { gate.await() })
        settle()
        assertEquals(A_ORDER_NO, searchText())
        switchSession("user-two")
        assertEquals("换代时搜索框立即清空", "", searchText())
        composeRule.runOnUiThread { gate.complete(Unit) }
        idleUntil { listRequests().isNotEmpty() }
        settle()
        assertEquals(listOf(mapOf("scope" to "self")), listRequests())
        assertEquals("", searchText())
    }

    /**
     * FU-AND-0715 第 5 项（F-AND-30）：A 会话的订单列表进程恢复停在恢复等待 → 会话存储换成 B、会话通知还没执行（换号窗口）→ 等待结束：零订单请求、
     * 待跟单也不拉（不按新会话默认条件先请求一次），A 保存的搜索词已清；通知到达后按 B 的默认条件只请求一次。
     */
    @Test
    fun fand30_restoreWaitEndingInsideTheSwitchWindowSendsNoOrderRequest() {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val seed =
            restoreSeed().apply {
                putString("orders.q", A_ORDER_NO)
                putString("orders.input", A_ORDER_NO)
            }
        launchRestored(RestoredOwner(seed), restoreGate = { gate.await() })
        settle()
        assertEquals(A_ORDER_NO, searchText())
        assertTrue("等待期间不发请求", listRequests().isEmpty())
        val pendingBefore = count(PENDING_PATH)
        // 会话存储换成 B，通知还没执行；随后恢复等待结束
        composeRule.runOnUiThread { tokens.save("access-two", "refresh-two", "full", "user-two") }
        composeRule.runOnUiThread { gate.complete(Unit) }
        settle()
        Thread.sleep(LATE_MS)
        settle()
        assertTrue("换号窗口内恢复：0 个订单请求 ${listRequests()}", listRequests().isEmpty())
        assertEquals("待跟单也不拉", pendingBefore, count(PENDING_PATH))
        assertEquals("A 保存的搜索词已清", "", searchText())
        // 通知到达：按 B 的默认条件只请求一次
        composeRule.runOnUiThread {
            login.syncSession()
            orders.onSessionChanged()
        }
        idleUntil { listRequests().isNotEmpty() }
        settle()
        Thread.sleep(LATE_MS)
        settle()
        assertEquals(listOf(mapOf("scope" to "self")), listRequests())
        assertEquals("", searchText())
    }

    /**
     * FU-AND-0715 第 5 项（F-AND-30）：待跟单 refresh 由调用方传入固定世代——发起页外跳时捕获的世代、列表的固定归属世代已不是当前会话（会话存储已换号、
     * 通知还没到）时一律不发。
     */
    @Test
    fun fand30_pendingRefreshWithAStaleCallerEpochSendsNothing() {
        launch()
        synchronized(answers) { answers[PENDING_PATH] = { envelope(pendingTwo()) } }
        openOrders()
        idleUntil { count(PENDING_PATH) > 0 }
        settle()
        val page = orders.listHandle()?.pending
        assertNotNull(page)
        val stale = page!!.currentTicket().epoch
        val before = count(PENDING_PATH)
        composeRule.runOnUiThread { tokens.save("access-two", "refresh-two", "full", "user-two") }
        composeRule.runOnUiThread { page.refresh(stale) }
        settle()
        Thread.sleep(LATE_MS)
        settle()
        assertEquals("调用方的固定世代已不是当前会话：零请求", before, count(PENDING_PATH))
        composeRule.runOnUiThread { page.refresh(page.currentTicket().epoch) }
        idleUntil { count(PENDING_PATH) == before + 1 }
    }

    /**
     * R2-02（共性第 12、18、22 条）：H5 入口从真实按钮点击出发，落到可信容器，按配置的 h5_base_url + routes.json 的 h5_path（参数走查询串）
     * 加载；占位页不算成功落点。
     */
    @Test
    @Suppress("LongMethod") // 入口逐个点
    fun r2_02_h5EntriesLoadTheContractUrlInTheTrustedContainer() {
        launch(config = CONFIG_H5, withWeb = true)
        synchronized(answers) { answers[PENDING_PATH] = { envelope(pendingTwo()) } }
        openOrders()
        // 标题右上「未跟单？去找回」、待跟单卡内「未跟单？去找回」→ FindOrder（h5 /find-order，无参数）
        opensH5(OrderTags.FIND_ENTRY, "FindOrder", emptyMap(), "https://h5.example.test/find-order")
        idleUntil { composeRule.onAllNodesWithTag(PendingTrackTags.CLAIM, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        opensH5(PendingTrackTags.CLAIM, "FindOrder", emptyMap(), "https://h5.example.test/find-order")
        // 详情【查看流水】→ Ledger（h5 /ledger）；【联系客服】→ Help（h5 /help，不带 article_id）
        for ((action, target) in listOf("ledger" to ("Ledger" to "/ledger"), "contact_cs" to ("Help" to "/help"))) {
            open("OrderDetail", mapOf("order_id" to "ord-clawed-punish"))
            idleUntil {
                composeRule
                    .onAllNodesWithTag(
                        OrderTags.DETAIL_ACTION_PREFIX + action,
                        useUnmergedTree = true,
                    ).fetchSemanticsNodes()
                    .isNotEmpty()
            }
            opensH5(OrderTags.DETAIL_ACTION_PREFIX + action, target.first, emptyMap(), "https://h5.example.test" + target.second)
            composeRule.runOnUiThread { navController.popBackStack() }
            settle()
        }
        // 比价「查看说明」→ Help {article_id}：地址带上 article_id
        open("OrderDetail", mapOf("order_id" to "ord-compare"))
        idleUntil { composeRule.onAllNodesWithTag(OrderTags.DETAIL_HELP, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        opensH5(
            OrderTags.DETAIL_HELP,
            "Help",
            mapOf("article_id" to "price-compare"),
            "https://h5.example.test/help?article_id=price-compare",
        )
    }

    /** 本账号（user-one）离开前保存的状态：自购、平台淘宝。 */
    private fun restoreSeed() =
        android.os.Bundle().apply {
            putString("orders.scope", "self")
            putString("orders.group", "all")
            putString("orders.platform", "taobao")
            putString("orders.input", "")
            putString("orders.owner", ownerOf("user-one"))
        }

    /**
     * 共性第 24 条：恢复对齐进行中（目标页还没回来）用户换了条件 → 旧恢复作废；目标页迟到后不滚动、不把目标卡写成锚点。
     * （同一次挂起内的竞态无法在测试里稳定复现；这里覆盖条件变化后不再执行旧对齐。）
     */
    @Test
    fun lesson24_alignmentAfterTheConditionsChangedNeitherScrollsNorPersists() {
        val seed =
            android.os.Bundle().apply {
                putString("orders.scope", "self")
                putString("orders.group", "all")
                putString("orders.input", "")
                putInt("orders.anchor_index", 26)
                putInt("orders.anchor_offset", 0)
                putString("orders.anchor_order_id", "ord-25")
                putString("orders.owner", ownerOf("user-one"))
            }
        val pageTwo = CountDownLatch(1)
        synchronized(answers) {
            answers[LIST_PATH] = { request ->
                val second = request.path.orEmpty().contains("cursor=c2")
                if (second) pageTwo.await(WAIT_S, TimeUnit.SECONDS)
                val ids = if (second) 20 until 40 else 0 until 20
                envelope(listData(ids.map { self1("ord-$it") }, cursor = if (second) null else "c2"))
            }
        }
        launchRestored(RestoredOwner(seed))
        idleUntil { listRequests().any { it["cursor"] == "c2" } }
        settle()
        val model = orders.listHandle() as com.couli.feature.orders.OrderListViewModel
        click(OrderTags.GROUP_PREFIX + "credited")
        pageTwo.countDown()
        settle()
        Thread.sleep(LATE_MS)
        settle()
        assertNull("换条件后恢复作废", model.pendingRestore)
        assertTrue("锚点不是旧恢复的目标卡", model.anchor.orderId != "ord-25")
        assertEquals(0, composeRule.onAllNodesWithTag(OrderTags.CARD_PREFIX + "ord-25").fetchSemanticsNodes().size)
    }

    /** 共性第 25 条：列表卡点过【开启通知】→ 商品详情页（另一显示位置）的通知行立即消失；那里旧画面上的按钮执行前复核，不再申请第二次。 */
    @Test
    fun lesson25_notificationTapIsSyncedToEveryPositionAndRecheckedBeforeRunning() {
        launch(sdkInt = 33, notificationsEnabled = false)
        synchronized(answers) { answers[PENDING_PATH] = { envelope(pendingTwo()) } }
        openOrders()
        idleUntil { composeRule.onAllNodesWithTag(PendingTrackTags.NOTIFY, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        lateinit var page: AppOrders.PendingPage
        composeRule.runOnUiThread {
            page = orders.pendingFor("product-entry")!!
            page.jumped("taobao")
            page.onStop()
            page.onStart()
        }
        idleUntil {
            page.service.state.value.cards
                .any { it.notificationRow }
        }
        val staleCard =
            page.service.state.value.cards
                .first { it.notificationRow }
        click(PendingTrackTags.NOTIFY)
        idleUntil { host.requests == 1 }
        settle()
        assertTrue(
            "商品详情页的通知行立即去掉",
            page.service.state.value.cards
                .none { it.notificationRow },
        )
        composeRule.runOnUiThread { page.service.requestNotification(staleCard) }
        settle()
        assertEquals("旧画面上的按钮执行前复核：不再申请", 1, host.requests)
        composeRule.runOnUiThread { orders.followBackStackIds(emptySet()) }
    }

    /** 共性第 26 条：详情命中断网缓存时有【重试】；仍断网保持缓存；42901 禁用期内置灰；恢复后刷新成功、提示条消失。 */
    @Test
    fun lesson26_staleDetailHasARefreshEntry() {
        launch()
        openOrders()
        open("OrderDetail", mapOf("order_id" to "ord-self-1"))
        idleUntil { composeRule.onAllNodesWithTag(OrderTags.DETAIL_AMOUNTS).fetchSemanticsNodes().isNotEmpty() }
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        synchronized(answers) { answers["$LIST_PATH/ord-self-1"] = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START) } }
        open("OrderDetail", mapOf("order_id" to "ord-self-1"))
        idleUntil { composeRule.onAllNodesWithTag(OrderTags.DETAIL_STALE).fetchSemanticsNodes().isNotEmpty() }
        val before = count("$LIST_PATH/ord-self-1")
        click(OrderTags.DETAIL_STALE_RETRY)
        idleUntil { count("$LIST_PATH/ord-self-1") > before }
        idleUntil { composeRule.onAllNodesWithTag(OrderTags.DETAIL_STALE).fetchSemanticsNodes().isNotEmpty() }
        // 会话级禁用期内置灰
        composeRule.runOnUiThread { orders.rateGate.start(60_000L) }
        settle()
        composeRule.onNodeWithTag(OrderTags.DETAIL_STALE_RETRY).assertIsNotEnabled()
        composeRule.runOnUiThread { orders.rateGate.reset() }
        settle()
        synchronized(answers) { answers.remove("$LIST_PATH/ord-self-1") }
        click(OrderTags.DETAIL_STALE_RETRY)
        idleUntil { composeRule.onAllNodesWithTag(OrderTags.DETAIL_STALE).fetchSemanticsNodes().isEmpty() }
        composeRule.onNodeWithTag(OrderTags.DETAIL_AMOUNTS).assertExists()
    }

    private fun launchRestored(
        owner: RestoredOwner,
        restoreGate: (suspend () -> Unit)? = null,
        moduleCleaners: Boolean = false,
    ) {
        restoredOwner.value = owner
        launch(
            moduleCleaners = moduleCleaners,
            content = {
                // 订单页还没创建（停在别的 Tab）：不组合列表
                val current = restoredOwner.value
                if (restoredVisible.value) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner provides current,
                        androidx.lifecycle.compose.LocalLifecycleOwner provides current,
                    ) {
                        val base = orders.listConfig(RouteDestination("OrderList", "{}", false))
                        com.couli.feature.orders.OrderListScreen(
                            com.couli.feature.orders.OrderListConfig(
                                api = base.api,
                                env = base.env,
                                texts = base.texts,
                                cache = base.cache,
                                mask = base.mask,
                                pending = base.pending,
                                clock = base.clock,
                                bindController = base.bindController,
                                restoreGate = restoreGate,
                                rateGate = base.rateGate,
                                closedAttempts = base.closedAttempts,
                                // 进程重建的用例换成新实例（内存里的待清理状态随进程丢失）；平时与 AppModule 同一个
                                restoreStore = restoreStoreOverride ?: base.restoreStore,
                            ),
                        )
                    }
                }
            },
        )
    }

    /** 进程重建后的恢复记录落盘（新实例）；null 用 AppModule 的那一个。 */
    private var restoreStoreOverride: OrderRestoreStore? = null

    /** 恢复测试里订单页是否在组合中（false = 进程恢复后停在别的 Tab，订单 ViewModel 还没创建）。 */
    private val restoredVisible = androidx.compose.runtime.mutableStateOf(true)

    private fun amountLabel(text: String): androidx.compose.ui.geometry.Rect =
        composeRule
            .onAllNodes(
                hasText(text) and
                    androidx.compose.ui.test
                        .hasAnyAncestor(hasTestTag(OrderTags.DETAIL_AMOUNTS)),
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
            .first()
            .boundsInRoot

    /**
     * R3-03（共性第 29 条）：没有 LKG、冷启动配置请求慢 → 先点「未跟单？去找回」进 FindOrder，地址拼不出、可信源为空，显示加载失败 → 配置随后
     * 返回合法的 h5_base_url 与 bridge_origins → 在原页面点【重试】：按生效配置重新生成地址并加载成功（不必出栈重进）。
     */
    @Test
    fun r3_03_h5RetryAfterLateConfigLoadsInTheSamePage() {
        var deliver: ((ConfigReply) -> Unit)? = null
        launch(config = CONFIG, withWeb = true, configFetcher = { _, done -> deliver = done })
        openOrders()
        landsOn(OrderTags.FIND_ENTRY, "FindOrder", emptyMap(), back = false)
        val failedText = DefaultTexts.text("h5.load_failed").orEmpty()
        idleUntil { texts(failedText) > 0 }
        assertFalse("配置未到时不加载任何地址", loadedUrls().any { !it.isNullOrEmpty() })
        // 配置随后到达（合法的 h5_base_url 与 bridge_origins）
        assertTrue("前提：配置请求在途", deliver != null)
        composeRule.runOnUiThread { deliver!!(ConfigReply(ConfigReply.OK, CONFIG_H5, null)) }
        settle()
        // 原页面点【重试】
        composeRule
            .onNode(hasText(DefaultTexts.text("external_page.retry").orEmpty()) and hasClickAction())
            .performClick()
        settle()
        idleUntil { loadedUrls().contains("https://h5.example.test/find-order") }
        assertEquals("仍是原页面", "FindOrder", top()?.name)
        assertEquals("加载失败页已消失", 0, texts(failedText))
    }

    // ---------------------------------------------------------------- FU-AND-0710：APP-08 共性第 30–43 条

    /** 第 30 条：下拉刷新在途时页面出栈（执行器 leave）：同步结束「刷新中」，不依赖已被取消、只会被丢弃的响应。 */
    @Test
    fun fu0710_l30_leaveEndsRefreshingSynchronously() {
        launch()
        openOrders()
        hold = CountDownLatch(1)
        val model = orders.listHandle() as com.couli.feature.orders.OrderListViewModel
        composeRule.runOnUiThread { model.refresh() }
        assertTrue("前提：刷新中", model.controller.state.value.refreshing)
        var after = true
        composeRule.runOnUiThread {
            model.controller.leave()
            after = model.controller.state.value.refreshing
        }
        assertFalse("leave 同步结束刷新中", after)
        hold?.countDown()
        settle()
        assertFalse(model.controller.state.value.refreshing)
    }

    /** 第 31 条：卡内【开启通知】的申请带点击时的有效性：失效的点击不记「点过」、不弹系统框。 */
    @Test
    fun fu0710_l31_anInvalidNotificationClickNeitherRecordsNorRequests() {
        launch(sdkInt = 33, notificationsEnabled = false)
        val permissions =
            AndroidPermissionRequester(
                AndroidPermissionSystem(
                    context,
                    context.getSharedPreferences("couli_permission", Context.MODE_PRIVATE),
                    sdkInt = 33,
                    declared = { true },
                    granted = { false },
                    notificationsEnabled = { false },
                ),
                PermissionPrompts(),
                context.getSharedPreferences("couli_permission", Context.MODE_PRIVATE),
            )
        composeRule.runOnUiThread {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate).launch {
                orders.pendingEnv.requestNotification { false }
            }
        }
        settle()
        assertFalse("失效的点击不记「点过」", permissions.hasLocalRecord(com.couli.core.clientcore.PermissionTypeRaw.PUSH))
        assertEquals("不弹系统框", 0, host.requests)
    }

    /**
     * 第 42 条：待跟单卡的关闭与【开启通知】用渲染时的票据。商品详情页显示卡片 → 会话失效后同账号重新登录、重新拉到同一张卡 → 旧画面上的回调
     * （旧票据）不发关闭请求、不记「点过」、不弹框；新票据照常。
     */
    @Test
    fun fu0710_l42_staleCardCallbacksAfterSameAccountReloginDoNothing() {
        launch(sdkInt = 33, notificationsEnabled = false)
        synchronized(answers) { answers[PENDING_PATH] = { envelope(pendingTwo()) } }
        lateinit var page: AppOrders.PendingPage
        composeRule.runOnUiThread {
            page = orders.pendingFor("product-entry")!!
            page.jumped("taobao")
            page.onStop()
            page.onStart()
        }
        idleUntil {
            page.service.state.value.cards
                .any { it.notificationRow }
        }
        val card =
            page.service.state.value.cards
                .single()
        val oldTicket = page.service.state.value.ticket
        composeRule.runOnUiThread {
            tokens.clear()
            login.syncSession()
            tokens.save("access-one-b", "refresh-one-b", "full", "user-one")
            login.syncSession()
        }
        settle()
        composeRule.runOnUiThread { page.service.refresh(page.service.currentTicket().epoch, "taobao") }
        idleUntil {
            page.service.state.value.cards
                .isNotEmpty()
        }
        assertEquals(
            "同一张卡重新出现",
            card,
            page.service.state.value.cards
                .single(),
        )
        assertTrue("前提：票据已换", oldTicket != page.service.state.value.ticket)
        val dismissBefore = synchronized(requests) { requests.count { it.method == "POST" && it.path.startsWith(DISMISS_PREFIX) } }
        var sent = true
        composeRule.runOnUiThread {
            sent = page.service.dismiss(card, oldTicket)
            page.service.requestNotification(card, oldTicket)
        }
        settle()
        assertFalse("旧票据的关闭不发", sent)
        assertEquals(dismissBefore, synchronized(requests) { requests.count { it.method == "POST" && it.path.startsWith(DISMISS_PREFIX) } })
        assertEquals("旧票据不弹系统框", 0, host.requests)
        assertTrue(
            "旧票据不记「点过」：通知行还在",
            page.service.state.value.cards
                .single()
                .notificationRow,
        )
        // 新票据照常
        val fresh = page.service.state.value.ticket
        composeRule.runOnUiThread { page.service.requestNotification(card, fresh) }
        idleUntil { host.requests == 1 }
        composeRule.runOnUiThread { orders.followBackStackIds(emptySet()) }
    }

    /** 第 34 条：订单相对时间用服务端 Date 校准后的时钟（偏差超过 5 分钟）：服务端说今天是 10-08，付款 10-08 09:05 显示「今天」。 */
    @Test
    fun fu0710_l34_relativeTimeUsesTheServerCalibratedClock() {
        serverDate = "Thu, 08 Oct 2026 02:00:00 GMT"
        launch(serverClock = true)
        openOrders()
        idleUntil { texts("${com.couli.core.designsystem.PriceTerms.TODAY} 09:05") > 0 }
        assertEquals(0, texts("${com.couli.core.designsystem.PriceTerms.YESTERDAY} 09:05"))
    }

    /** 第 37 条：订单列表滚到第 8 条 → 切到首页 Tab → 回订单 Tab：仍停在原来的位置，锚点不被首次排版覆盖。 */
    @Test
    fun fu0710_l37_switchingMainTabsKeepsTheScrollPosition() {
        synchronized(answers) { answers[LIST_PATH] = { envelope(listData((1..20).map { self1("ord-$it") }, cursor = null)) } }
        launch()
        openOrders()
        idleUntil { composeRule.onAllNodesWithTag(OrderTags.CARD_PREFIX + "ord-1").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag(OrderTags.LIST).performScrollToNode(hasTestTag(OrderTags.CARD_PREFIX + "ord-8"))
        settle()
        val model = orders.listHandle() as com.couli.feature.orders.OrderListViewModel
        val before = model.anchor
        assertTrue("前提：已经滚下去", before.index > 1)
        open("Home")
        settle()
        open("OrderList")
        settle()
        assertEquals("锚点不被首次排版覆盖", before, model.anchor)
        idleUntil { composeRule.onAllNodesWithTag(OrderTags.CARD_PREFIX + before.orderId).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(
            "第一张可见订单不变",
            0,
            composeRule.onAllNodesWithTag(OrderTags.CARD_PREFIX + "ord-1").fetchSemanticsNodes().size,
        )
    }

    /**
     * 第 38、43 条：撤回同意 / 切换环境时，商品详情与搜索页上已显示的待跟单卡清空、在途请求作废、旧外跳记录丢弃；之后再外跳回来照常出卡。
     */
    @Test
    fun fu0710_l38_withdrawalAndEnvSwitchClearLaunchingPageCardsAndTheyWorkAgainAfterwards() {
        launch()
        synchronized(answers) { answers[PENDING_PATH] = { envelope(pendingTwo()) } }
        lateinit var page: AppOrders.PendingPage

        fun showCard() {
            composeRule.runOnUiThread {
                page.jumped("taobao")
                page.onStop()
                page.onStart()
            }
            idleUntil {
                page.service.state.value.cards
                    .isNotEmpty()
            }
        }
        composeRule.runOnUiThread { page = orders.pendingFor("product-entry")!! }
        showCard()
        // 切环境（清理注册表的同步出口；不等会话通知）
        composeRule.runOnUiThread {
            envCleaners.cleanAll(com.couli.core.config.AppEnvironment.TEST, com.couli.core.config.AppEnvironment.STAGING)
        }
        assertTrue(
            "切环境：卡片立即清空",
            page.service.state.value.cards
                .isEmpty(),
        )
        // 切走之前点过购买、清理之后才回来：不按旧外跳拉卡
        composeRule.runOnUiThread { page.jumped("taobao") }
        composeRule.runOnUiThread {
            envCleaners.cleanAll(com.couli.core.config.AppEnvironment.TEST, com.couli.core.config.AppEnvironment.STAGING)
        }
        val before = count(PENDING_PATH)
        composeRule.runOnUiThread {
            page.onStop()
            page.onStart()
        }
        settle()
        assertEquals("旧外跳记录已丢弃", before, count(PENDING_PATH))
        // 新环境里再外跳回来：执行器照常出卡
        showCard()
        // 撤回同意（隐私结论不再是已同意）
        composeRule.runOnUiThread { appPrivacy.withdraw() }
        settle()
        assertTrue(
            "撤回同意：卡片清空",
            page.service.state.value.cards
                .isEmpty(),
        )
        composeRule.runOnUiThread { orders.followBackStackIds(emptySet()) }
    }

    /**
     * 第 36 条：完整订单号搜索之后切换环境（清理注册表的同步出口，不等会话通知）：搜索框、请求与页面保存状态里都不再有这个单号，系统回收后重建也
     * 恢复不出来；撤回同意同样。Android 的页面恢复记录只在 SavedStateHandle（系统托管，App 不另落盘），按账号哈希归属。
     */
    @Test
    fun fu0710_l36_fullOrderNoSearchIsDroppedByEnvSwitchAndWithdrawalCleanup() {
        launchRestored(RestoredOwner(android.os.Bundle()))
        idleUntil { listRequests().isNotEmpty() }
        settle()
        search(A_ORDER_NO)
        assertEquals(A_ORDER_NO, searchText())
        // 清理之后的列表为空：页面上任何位置出现这个单号都只能是残留
        synchronized(answers) { answers[LIST_PATH] = { envelope(listData(emptyList())) } }
        val before = listRequests().size
        composeRule.runOnUiThread {
            envCleaners.cleanAll(com.couli.core.config.AppEnvironment.TEST, com.couli.core.config.AppEnvironment.STAGING)
        }
        assertEquals("切环境同步清空搜索框", "", (orders.listHandle() as com.couli.feature.orders.OrderListViewModel).input.value)
        idleUntil { listRequests().size > before }
        settle()
        assertEquals("", searchText())
        assertEquals("页面上没有完整单号", 0, texts(A_ORDER_NO))
        assertTrue("之后的请求不带这个搜索词", listRequests().drop(before).none { it.containsKey("q") })
        val beforeRecreate = listRequests().size
        recreateRestored()
        idleUntil { listRequests().size > beforeRecreate }
        settle()
        assertEquals("重建后搜索框是空的", "", searchText())
        assertTrue("重建后的请求不带这个搜索词", listRequests().drop(beforeRecreate).none { it.containsKey("q") })
        // 撤回同意：同样同步清掉
        search(A_ORDER_NO)
        assertEquals(A_ORDER_NO, searchText())
        composeRule.runOnUiThread { appPrivacy.withdraw() }
        assertEquals("撤回同意同步清空搜索框", "", (orders.listHandle() as com.couli.feature.orders.OrderListViewModel).input.value)
    }

    /**
     * 第 41 条：列表卡【复制订单号】的回调用渲染时的轮次，执行前核对会话世代：会话已换代、作废通知还没到时执行旧回调，零写入；轮次变了的旧回调同样零写入。
     */
    @Test
    fun fu0710_l41_staleListCopyCallbacksWriteNothing() {
        launch()
        openOrders()
        val model = orders.listHandle() as com.couli.feature.orders.OrderListViewModel
        val round = model.controller.state.value.round
        val writes = system.writes
        var copied = true
        composeRule.runOnUiThread {
            // 会话换代（同账号重新登录），列表还没收到通知：旧按钮执行
            tokens.save("access-one-b", "refresh-one-b", "full", "user-one")
            copied = model.controller.copy("ord-self-1", A_ORDER_NO, round)
        }
        assertFalse(copied)
        assertEquals("零写入", writes, system.writes)
        // 会话通知随后到达（AppModule 经网络层的会话通知接到 AppOrders）：按新会话重取，开新一轮
        composeRule.runOnUiThread { orders.onSessionChanged() }
        settle()
        idleUntil { composeRule.onAllNodesWithTag(OrderTags.CARD_PREFIX + "ord-self-1").fetchSemanticsNodes().isNotEmpty() }
        val fresh = model.controller.state.value.round
        assertTrue("前提：新一轮", fresh != round)
        composeRule.runOnUiThread { copied = model.controller.copy("ord-self-1", A_ORDER_NO, round) }
        assertFalse("上一轮的旧回调", copied)
        assertEquals(writes, system.writes)
        composeRule.runOnUiThread { copied = model.controller.copy("ord-self-1", A_ORDER_NO, fresh) }
        assertTrue("当前轮次照常", copied)
        assertEquals(writes + 1, system.writes)
    }

    // ---------------------------------------------------------------- FU-AND-0710 第 1 轮评审修复

    /**
     * R1-01（S0，共性第 44 条）：A 的列表已有数据与下一页游标 → 会话存储换成 B，但会话通知（AppModule 经 main.post 投递）还没执行 → 迟到的翻页
     * 回调进来：先作废 A 的列表（不经 begin() 把 A 的数据接到 B），A 的旧复制回调零写入 → 会话通知随后到达：按「最近处理的会话通知」判断仍要
     * 重置一次（重新取第一页），列表里不再有 A 的数据。
     */
    @Test
    fun r1_01_lateLoadMoreAfterTheSessionStoreSwitchesDoesNotRebindTheOldList() {
        synchronized(answers) { answers[LIST_PATH] = { envelope(listData(listOf(self1()), cursor = "cursor-2")) } }
        launch()
        openOrders()
        idleUntil { composeRule.onAllNodesWithTag(OrderTags.CARD_PREFIX + "ord-self-1").fetchSemanticsNodes().isNotEmpty() }
        val model = orders.listHandle() as com.couli.feature.orders.OrderListViewModel
        // 渲染时取出的普通值（复制回调捕获的那一份）
        val identity = model.controller.state.value.identity
        val round = model.controller.state.value.round
        assertTrue("前提：已显示 A 的数据", identity != null)
        assertFalse("前提：还有下一页", model.controller.state.value.endReached)
        // B 的列表为空：页面上任何位置出现 A 的单号都只能是残留
        synchronized(answers) { answers[LIST_PATH] = { envelope(listData(emptyList())) } }
        val writes = system.writes
        var copied = true
        var itemsAfterLoadMore = -1
        var identityAfterLoadMore: com.couli.feature.orders.OrderListIdentity? = identity
        composeRule.runOnUiThread {
            // 会话存储换成 B，通知还没执行
            tokens.save("access-two", "refresh-two", "full", "user-two")
            // 迟到的翻页回调
            model.controller.loadMore()
            itemsAfterLoadMore = model.controller.state.value.items.size
            identityAfterLoadMore = model.controller.state.value.identity
            // A 的旧复制回调
            copied = model.controller.copy("ord-self-1", A_ORDER_NO, round, identity)
        }
        assertEquals("翻页入口先作废 A 的列表", 0, itemsAfterLoadMore)
        assertTrue("旧数据的身份不再是当前身份", identityAfterLoadMore != identity)
        assertFalse("旧复制回调不写", copied)
        assertEquals("零写入", writes, system.writes)
        settle()
        val beforeNotice = listRequests().size
        // 会话通知到达（AppModule 经会话通知调到 AppOrders）
        composeRule.runOnUiThread {
            login.syncSession()
            orders.onSessionChanged()
        }
        idleUntil { listRequests().size > beforeNotice }
        settle()
        assertTrue(
            "通知到后列表里没有 A 的数据",
            model.controller.state.value.items
                .isEmpty(),
        )
        assertEquals("页面上没有 A 的完整单号", 0, texts(A_ORDER_NO))
        composeRule.runOnUiThread { copied = model.controller.copy("ord-self-1", A_ORDER_NO, round, identity) }
        assertFalse(copied)
        assertEquals("零写入", writes, system.writes)
    }

    /**
     * R1-04（共性第 42、46 条）：订单列表自购子 Tab 显示待跟单卡（渲染时的票据交给关闭与【开启通知】回调）→ 切到分享子 Tab（clear）→ 切回自购、
     * 取回同一张卡（相同 link_id / attempt_id）→ 执行旧画面上的回调（旧票据）：不发关闭请求、不记「点过」、不弹系统框；新票据照常。
     */
    @Test
    fun r1_04_staleListCardCallbacksAfterSelfShareSelfDoNothing() {
        launch(sdkInt = 33, notificationsEnabled = false)
        synchronized(answers) { answers[PENDING_PATH] = { envelope(pendingTwo()) } }
        openOrders()
        idleUntil { composeRule.onAllNodesWithTag(PendingTrackTags.NOTIFY_ROW, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val model = orders.listHandle() as com.couli.feature.orders.OrderListViewModel
        val service = model.pending!!
        val card =
            service.state.value.cards
                .single()
        // 组合时取出的那份票据（PendingTrackCards 交给回调的普通值）
        val oldTicket = service.state.value.ticket
        composeRule.onNodeWithTag(OrderTags.TAB_SHARE).performClick()
        settle()
        assertTrue(
            "分享子 Tab 不显示卡",
            service.state.value.cards
                .isEmpty(),
        )
        composeRule.onNodeWithTag(OrderTags.TAB_SELF).performClick()
        settle()
        idleUntil {
            service.state.value.cards
                .isNotEmpty()
        }
        assertEquals(
            "取回同一张卡（相同 link_id / attempt_id）",
            card,
            service.state.value.cards
                .single(),
        )
        assertTrue("前提：票据已换", oldTicket != service.state.value.ticket)
        val dismissBefore = synchronized(requests) { requests.count { it.method == "POST" && it.path.startsWith(DISMISS_PREFIX) } }
        var sent = true
        composeRule.runOnUiThread {
            sent = service.dismiss(card, oldTicket)
            service.requestNotification(card, oldTicket)
        }
        settle()
        assertFalse("旧回调不发关闭", sent)
        assertEquals(dismissBefore, synchronized(requests) { requests.count { it.method == "POST" && it.path.startsWith(DISMISS_PREFIX) } })
        assertEquals("旧回调不弹系统框", 0, host.requests)
        assertTrue(
            "旧回调不记「点过」：通知行还在",
            service.state.value.cards
                .single()
                .notificationRow,
        )
        // 新票据照常
        val fresh = service.state.value.ticket
        composeRule.runOnUiThread { service.requestNotification(card, fresh) }
        idleUntil { host.requests == 1 }
    }

    /**
     * R1-01 排查（待跟单卡）：会话存储换代、通知还没到时按通知权限重算（recompute 重新发布）：旧卡片的票据仍是拉到它们的那一代会话，渲染出的
     * 回调执行时与当前会话比较不通过，不发关闭请求。
     */
    @Test
    fun r1_01_pendingCardsRepublishedBeforeTheSessionNoticeKeepTheirOldEpoch() {
        launch()
        synchronized(answers) { answers[PENDING_PATH] = { envelope(pendingTwo()) } }
        lateinit var page: AppOrders.PendingPage
        composeRule.runOnUiThread {
            page = orders.pendingFor("product-entry")!!
            page.jumped("taobao")
            page.onStop()
            page.onStart()
        }
        idleUntil {
            page.service.state.value.cards
                .isNotEmpty()
        }
        val card =
            page.service.state.value.cards
                .single()
        val dismissBefore = synchronized(requests) { requests.count { it.method == "POST" && it.path.startsWith(DISMISS_PREFIX) } }
        var sent = true
        composeRule.runOnUiThread {
            tokens.save("access-two", "refresh-two", "full", "user-two")
            page.service.recompute()
            // 重新发布之后渲染时取出的票据
            sent = page.service.dismiss(card, page.service.state.value.ticket)
        }
        settle()
        assertFalse("旧会话的卡片不发关闭", sent)
        assertEquals(dismissBefore, synchronized(requests) { requests.count { it.method == "POST" && it.path.startsWith(DISMISS_PREFIX) } })
        composeRule.runOnUiThread { orders.followBackStackIds(emptySet()) }
    }

    /**
     * R1-03（共性第 36、40、45 条）：A 用完整订单号搜索 → 切到别的 Tab、系统保存状态并回收进程 → 恢复后订单页还没创建时撤回同意 → 补同意、同账号
     * 重新登录 → 打开订单：系统保存状态里那份没消费的记录因清理世代已推进而不恢复，搜索框与查询条件都是空的。
     */
    @Test
    fun r1_03_withdrawalBeforeTheOrdersPageExistsInvalidatesTheUnconsumedRestoreRecord() {
        launchRestored(RestoredOwner(android.os.Bundle()), moduleCleaners = true)
        idleUntil { listRequests().isNotEmpty() }
        settle()
        search(A_ORDER_NO)
        assertEquals(A_ORDER_NO, searchText())
        // 切走、进程被回收：系统留下保存状态，订单 ViewModel 不再存在
        lateinit var state: android.os.Bundle
        composeRule.runOnUiThread {
            val old = restoredOwner.value
            state = old.save()
            old.viewModelStore.clear()
            restoredVisible.value = false
        }
        settle()
        // 订单页还没创建时撤回同意（清理出口同步执行）
        composeRule.runOnUiThread { appPrivacy.withdraw() }
        settle()
        // 补同意、同账号重新登录
        composeRule.runOnUiThread {
            appPrivacy.requestFullFunction()
            appPrivacy.agree()
            tokens.save("access-one-c", "refresh-one-c", "full", "user-one")
            login.syncSession()
            orders.onSessionChanged()
        }
        settle()
        // 打开订单：用回收前保存的那份状态重建
        val beforeReopen = listRequests().size
        composeRule.runOnUiThread {
            restoredOwner.value = RestoredOwner(android.os.Bundle(), state)
            restoredVisible.value = true
        }
        settle()
        idleUntil { composeRule.onAllNodesWithTag(OrderTags.SEARCH).fetchSemanticsNodes().isNotEmpty() }
        val model = orders.listHandle() as com.couli.feature.orders.OrderListViewModel
        assertEquals("搜索框不恢复旧搜索词", "", searchText())
        assertEquals("", model.input.value)
        assertEquals("查询条件不恢复旧搜索词", null, model.controller.state.value.conditions.q)
        assertTrue("重开后的请求不带旧搜索词", listRequests().drop(beforeReopen).none { it.containsKey("q") })
        assertTrue("撤回时恢复记录已删除", restoreFilesWith(A_ORDER_NO).isEmpty())
    }

    /** R1-03 对照：同样回收、没有撤回，恢复照常（清理世代没变）。 */
    @Test
    fun r1_03_controlWithoutCleanupTheRecordIsRestored() {
        launchRestored(RestoredOwner(android.os.Bundle()), moduleCleaners = true)
        idleUntil { listRequests().isNotEmpty() }
        settle()
        search(A_ORDER_NO)
        lateinit var state: android.os.Bundle
        composeRule.runOnUiThread {
            val old = restoredOwner.value
            state = old.save()
            old.viewModelStore.clear()
            restoredVisible.value = false
        }
        settle()
        composeRule.runOnUiThread {
            restoredOwner.value = RestoredOwner(android.os.Bundle(), state)
            restoredVisible.value = true
        }
        settle()
        idleUntil { composeRule.onAllNodesWithTag(OrderTags.SEARCH).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(A_ORDER_NO, searchText())
    }

    // ---------------------------------------------------------------- FU-AND-0710 第 2 轮评审修复

    /**
     * R2-01（S0，APP-09 共性第 63、65 条）：保留 A 页面真实的旧回调（页面根语义属性上本次组合交给搜索框、筛选、子 Tab、刷新、重试、复制、锚点等
     * 控件的同一批对象）→ 换成 B、页面按 B 重组 → 执行全部旧回调：搜索框与内存不写入、不发任何请求、剪贴板零写入、恢复记录与系统保存状态里没有
     * A 的单号；重建后也恢复不出。当前组合的新回调照常。
     */
    @Test
    @Suppress("LongMethod") // 旧回调逐个执行、逐项断言
    fun r2b_01_staleCallbacksAfterAccountSwitchWriteNothingAndSendNothing() {
        launchRestored(RestoredOwner(android.os.Bundle()))
        idleUntil { composeRule.onAllNodesWithTag(OrderTags.CARD_PREFIX + "ord-self-1").fetchSemanticsNodes().isNotEmpty() }
        settle()
        val old = pageActions()
        val model = orders.listHandle() as com.couli.feature.orders.OrderListViewModel
        val round = model.controller.state.value.round
        val identity = model.controller.state.value.identity
        synchronized(answers) { answers[LIST_PATH] = { envelope(listData(emptyList())) } }
        switchSession("user-two")
        idleUntil { pageActions().ticket != old.ticket }
        settle()
        val requestsBefore = synchronized(requests) { requests.size }
        val writes = system.writes
        composeRule.runOnUiThread {
            old.onInput(A_ORDER_NO)
            old.onSubmit()
            old.onPlatform("taobao")
            old.onMonth("2026-09")
            old.onGroup("credited")
            old.onScope("share")
            old.onSheet(com.couli.feature.orders.OrderSheet.PLATFORM)
            old.onRefresh()
            old.onRetry()
            old.onLoadMore()
            old.onCopy("ord-self-1", A_ORDER_NO, round, identity)
            old.onScrolled(
                com.couli.feature.orders
                    .OrderAnchor(3, 0, "ord-self-1"),
            )
            old.onOpen("ord-self-1")
        }
        settle()
        assertEquals("搜索框不写入 A 的单号", "", searchText())
        assertEquals("", model.input.value)
        assertNull("旧回调不打开半屏", model.sheet.value)
        assertEquals("条件没被旧回调改动", com.couli.core.orders.OrderQuery.SELF, model.controller.state.value.scope)
        assertNull(model.controller.state.value.conditions.q)
        assertNull(model.controller.state.value.conditions.platform)
        assertEquals("不发任何请求", requestsBefore, synchronized(requests) { requests.size })
        assertEquals("剪贴板零写入", writes, system.writes)
        assertTrue("恢复记录里没有 A 的单号", restoreFilesWith(A_ORDER_NO).isEmpty())
        assertTrue("恢复记录里没有 A 的锚点订单", restoreFilesWith("ord-self-1").isEmpty())
        assertFalse(
            "系统保存状态里没有 A 的单号",
            restoredOwner.value
                .save()
                .toString()
                .contains(A_ORDER_NO),
        )
        val beforeRecreate = listRequests().size
        recreateRestored()
        idleUntil { listRequests().size > beforeRecreate }
        settle()
        assertEquals("重建后也恢复不出", "", searchText())
        assertTrue(listRequests().drop(beforeRecreate).none { it.containsKey("q") })
        // 当前组合的新回调照常写入并进恢复记录
        val fresh = pageActions()
        composeRule.runOnUiThread { fresh.onInput("纯牛奶") }
        settle()
        assertEquals("纯牛奶", searchText())
        assertTrue(restoreFilesWith("纯牛奶").isNotEmpty())
    }

    /** R2-01：同一账号、会话世代不变的清理（切环境 / 撤回的同步通知）之后，旧页面的输入与提交回调同样失效。 */
    @Test
    fun r2b_01_staleInputCallbacksAfterCleanupOfTheSameAccountDoNothing() {
        launchRestored(RestoredOwner(android.os.Bundle()))
        idleUntil { listRequests().isNotEmpty() }
        settle()
        val old = pageActions()
        composeRule.runOnUiThread { orders.onCleanup() }
        idleUntil { pageActions().ticket != old.ticket }
        settle()
        val before = listRequests().size
        composeRule.runOnUiThread {
            old.onInput(A_ORDER_NO)
            old.onSubmit()
        }
        settle()
        assertEquals("", searchText())
        assertEquals("不发请求", before, listRequests().size)
        assertTrue(restoreFilesWith(A_ORDER_NO).isEmpty())
    }

    /**
     * R2-02（S1）：撤回的基本模式与待清理标记已落盘，但隐私结论通知发出前进程被杀（AppOrders 没收到任何通知）→ 下次启动 PrivacyGate 经 AppModule
     * 的凭据清理器补清理：订单页恢复记录被删除、补清理计入成功 → 同账号回到订单页恢复不出旧搜索词。
     */
    @Test
    fun r2b_02_withdrawalPersistedButKilledBeforeTheNoticeIsCleanedAtStartup() {
        launchRestored(RestoredOwner(android.os.Bundle()))
        idleUntil { listRequests().isNotEmpty() }
        settle()
        search(A_ORDER_NO)
        val state = killOrders()
        assertTrue("前提：恢复记录在盘上", restoreFilesWith(A_ORDER_NO).isNotEmpty())
        assertFalse("前提：系统保存状态里只有引用", state.toString().contains(A_ORDER_NO))
        // 下次启动：撤回事务已落盘（待清理 + 基本模式），AppOrders 没收到通知
        val store = PendingWithdrawalStore()
        PrivacyGate(store, clock = { now }, bundledPolicyVersion = "1")
            .apply {
                retryScheduler = RetryScheduler { _, _ -> }
                withdrawalCleanup = { withdrawalCleaners().cleanAll() }
            }.coldStart()
        assertTrue("启动补清理删掉恢复记录", restoreFilesWith(A_ORDER_NO).isEmpty())
        assertFalse("补清理计入成功、去掉待清理", store.withdrawalCleanupPending)
        assertReopenedWithoutTheOldSearch(state)
    }

    /** R2-02：启动时的环境对账（上次生效的环境与本次不同）经 AppModule 的环境清理器删除恢复记录，对账计入成功。 */
    @Test
    fun r2b_02_startupEnvironmentReconcileDeletesTheRestoreRecord() {
        launchRestored(RestoredOwner(android.os.Bundle()))
        idleUntil { listRequests().isNotEmpty() }
        settle()
        search(A_ORDER_NO)
        val state = killOrders()
        assertTrue(restoreFilesWith(A_ORDER_NO).isNotEmpty())
        val reconciler = EnvironmentStartupReconciler(context, workingEnvironmentCleaners())
        reconciler.markApplied(com.couli.core.config.AppEnvironment.STAGING)
        val outcome = reconciler.reconcile(com.couli.core.config.AppEnvironment.TEST)
        assertEquals(EnvironmentStartupReconciler.Outcome.CLEANED_AND_RECORDED, outcome)
        assertTrue("环境对账删掉恢复记录", restoreFilesWith(A_ORDER_NO).isEmpty())
        assertReopenedWithoutTheOldSearch(state)
    }

    /**
     * R1-03（S1）：撤回时恢复记录删不掉 → 清理器抛错上报、待清理落到记录目录之外 → 进程被杀、重建（新实例，内存状态丢失）→ 同账号回到订单页：
     * 盘上旧记录还在，但恢复不出、也不写新记录 → 删除恢复后下一次读取先补清理，旧记录删除。
     */
    @Test
    fun r2b_03_deleteFailureKeepsTheRecordUnrestorableAcrossProcessRebuild() {
        launchRestored(RestoredOwner(android.os.Bundle()))
        idleUntil { listRequests().isNotEmpty() }
        settle()
        search(A_ORDER_NO)
        val state = killOrders()
        val root = AppOrders.restoreRoot(context)
        root.setWritable(false)
        assumeTrue("文件系统按目录权限拒绝删除", !root.canWrite())
        val failure = runCatching { withdrawalCleaners().cleanAll() }.exceptionOrNull()
        assertTrue("删除失败上报给清理器", failure is java.io.IOException)
        assertTrue("前提：旧记录删不掉、仍在盘上", restoreFilesWith(A_ORDER_NO).isNotEmpty())
        // 进程重建：新的落盘实例
        restoreStoreOverride = OrderRestoreStore(root, orderRestorePendingFlag(context))
        assertReopenedWithoutTheOldSearch(state)
        assertTrue("旧记录仍在盘上（删不掉），只是恢复不出", restoreFilesWith(A_ORDER_NO).isNotEmpty())
        root.setWritable(true)
        // 删除恢复：下一次读取前补清理
        assertNull(
            OrderRestoreStore(root, orderRestorePendingFlag(context)).read(
                java.util.UUID
                    .randomUUID()
                    .toString(),
            ),
        )
        assertTrue("补清理删掉旧记录", restoreFilesWith(A_ORDER_NO).isEmpty())
    }

    /** 本次组合交给列表页控件的回调（页面根节点的语义属性）。 */
    private fun pageActions(): com.couli.feature.orders.OrderListActions =
        composeRule
            .onNodeWithTag(OrderTags.LIST_PAGE)
            .fetchSemanticsNode()
            .config[com.couli.feature.orders.OrderListActions.Key]

    /** 恢复记录目录里内容含 [text] 的文件。 */
    private fun restoreFilesWith(text: String): List<File> =
        AppOrders
            .restoreRoot(context)
            .walkTopDown()
            .filter { it.isFile && runCatching { it.readText() }.getOrDefault("").contains(text) }
            .toList()

    /** 切走、进程被回收：系统留下保存状态，订单 ViewModel 不再存在。 */
    private fun killOrders(): android.os.Bundle {
        lateinit var state: android.os.Bundle
        composeRule.runOnUiThread {
            val old = restoredOwner.value
            state = old.save()
            old.viewModelStore.clear()
            restoredVisible.value = false
        }
        settle()
        return state
    }

    /** 用回收前保存的状态重开订单页：搜索框、查询条件、请求都没有旧搜索词。 */
    private fun assertReopenedWithoutTheOldSearch(state: android.os.Bundle) {
        val beforeReopen = listRequests().size
        composeRule.runOnUiThread {
            restoredOwner.value = RestoredOwner(android.os.Bundle(), state)
            restoredVisible.value = true
        }
        settle()
        idleUntil { composeRule.onAllNodesWithTag(OrderTags.SEARCH).fetchSemanticsNodes().isNotEmpty() }
        val model = orders.listHandle() as com.couli.feature.orders.OrderListViewModel
        assertFalse("没有恢复", model.restored)
        assertEquals("搜索框不恢复旧搜索词", "", searchText())
        assertNull("查询条件不恢复旧搜索词", model.controller.state.value.conditions.q)
        assertTrue("重开后的请求不带旧搜索词", listRequests().drop(beforeReopen).none { it.containsKey("q") })
    }

    /** 撤回事务已落盘（待清理 + 基本模式）的隐私存储。 */
    private class PendingWithdrawalStore : PrivacyStore {
        override var decisionRaw: String = "basic_mode"
        override var consentedVersion: String? = null
        override var consentedAtMillis: Long? = null
        override var basicMode: Boolean = true
        override var withdrawalCleanupPending: Boolean = true
    }

    /** 与 AppModule 同一份环境清理注册（各清理项都给真实对象）。 */
    private fun workingEnvironmentCleaners() =
        AppModule.environmentSwitchCleaners(
            context,
            MemorySessionTokens(),
            NoCipher,
            inFlight,
            Provider { noDevice() },
            Provider {
                com.couli.app.clientcore.AppConfig(
                    ConfigStore(
                        readFile = { null },
                        writeFile = {},
                        fetcher = { _, done -> done(ConfigReply(ConfigReply.OK, CONFIG, null)) },
                        clock = { now },
                        bundled = CONFIG,
                    ),
                ) { true }
            },
            Provider { noTracker() },
            Provider { EnvironmentGate { EnvironmentStartupReconciler.Outcome.UNCHANGED } },
            Provider { error("no privacy gate") },
            BridgeHostRegistry(),
        )

    /** 真实点击 [tag] → 栈顶路由名与参数精确等于 [route] / [expected]；[back] 时再返回。 */
    private fun landsOn(
        tag: String,
        route: String,
        expected: Map<String, String>,
        back: Boolean = true,
    ) {
        val before = top()
        val node = composeRule.onNodeWithTag(tag, useUnmergedTree = true)
        // 在滚动容器里的先滚进可见区（顶栏、底部按钮栏不在滚动容器里）
        runCatching { node.performScrollTo() }
        node.performClick()
        settle()
        idleUntil { top() != before }
        assertEquals("$tag 的落点", route, top()?.name)
        assertEquals("$tag 的参数", expected, params())
        if (back) {
            composeRule.runOnUiThread { navController.popBackStack() }
            settle()
        }
    }

    private fun params(): Map<String, String> =
        Json
            .parseToJsonElement(top()!!.paramsJson)
            .jsonObject
            .mapValues { (it.value as JsonPrimitive).content }

    /** 进程被系统回收后恢复的页面持有者：SavedStateHandle 里是离开前保存的条件与锚点（默认参数即恢复的状态）。 */
    private class RestoredOwner(
        args: android.os.Bundle,
        /** 上一个实例 [save] 出来的状态（进程被回收后系统交回的那份）；null 为全新。 */
        restored: android.os.Bundle? = null,
    ) : androidx.lifecycle.ViewModelStoreOwner,
        androidx.savedstate.SavedStateRegistryOwner,
        androidx.lifecycle.HasDefaultViewModelProviderFactory {
        private val registry = androidx.lifecycle.LifecycleRegistry.createUnsafe(this)
        private val controller = androidx.savedstate.SavedStateRegistryController.create(this)
        override val viewModelStore = androidx.lifecycle.ViewModelStore()
        override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
        override val savedStateRegistry: androidx.savedstate.SavedStateRegistry get() = controller.savedStateRegistry
        override val defaultViewModelProviderFactory: androidx.lifecycle.ViewModelProvider.Factory =
            androidx.lifecycle.ViewModelProvider.NewInstanceFactory()
        override val defaultViewModelCreationExtras: androidx.lifecycle.viewmodel.CreationExtras =
            androidx.lifecycle.viewmodel.MutableCreationExtras().apply {
                set(androidx.lifecycle.SAVED_STATE_REGISTRY_OWNER_KEY, this@RestoredOwner)
                set(androidx.lifecycle.VIEW_MODEL_STORE_OWNER_KEY, this@RestoredOwner)
                set(androidx.lifecycle.DEFAULT_ARGS_KEY, seededArgs(args))
            }

        /** 进程被回收前系统保存的状态（SavedStateHandle 随之写出）。 */
        fun save(): android.os.Bundle = android.os.Bundle().also { controller.performSave(it) }

        init {
            controller.performAttach()
            controller.performRestore(restored)
            enableSavedStateHandles()
            registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
        }
    }

    // ---------------------------------------------------------------- 工具

    /** 恢复测试里当前的页面持有者（换成新实例 = 进程被回收后重建）。 */
    private val restoredOwner = androidx.compose.runtime.mutableStateOf(RestoredOwner(android.os.Bundle()))

    /** 进程被回收后重建：保存当前持有者的状态、清掉旧实例（ViewModel 随之清理），用保存的状态建新持有者重新组合。 */
    private fun recreateRestored() {
        composeRule.runOnUiThread {
            val old = restoredOwner.value
            val state = old.save()
            old.viewModelStore.clear()
            restoredOwner.value = RestoredOwner(android.os.Bundle(), state)
        }
        settle()
    }

    /** 搜索框里显示的文字。 */
    private fun searchText(): String =
        composeRule
            .onNodeWithTag(OrderTags.SEARCH)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.EditableText)
            ?.text
            .orEmpty()

    // ---------------------------------------------------------------- FU-AND-0710 第 3 轮评审修复

    /**
     * R3-02（APP-08 共性第 44 条、APP-09 共性第 63 条）：A 的详情错误态（50001）→ 会话存储换成 B，但会话通知（AppModule 经 main.post 投递）
     * 还没执行，页面没有重组 → 点画面上真实的【重试】（A 渲染时的旧回调）：零请求；通知到达后（清空旧页面的会话出口）才按 B 重新请求。
     */
    @Test
    fun r3_02_oldDetailErrorRetryBeforeTheSessionNoticeSendsNothing() {
        launch()
        openOrders()
        synchronized(answers) { answers["$LIST_PATH/ord-o7"] = { envelope("null", code = 50001).setResponseCode(500) } }
        open("OrderDetail", mapOf("order_id" to "ord-o7"))
        idleUntil { composeRule.onAllNodesWithTag(OrderTags.DETAIL_ERROR).fetchSemanticsNodes().isNotEmpty() }
        settle()
        val before = count("$LIST_PATH/ord-o7")
        assertTrue("前提：A 已请求过详情", before >= 1)
        // 会话存储换成 B，通知还没执行
        composeRule.runOnUiThread { tokens.save("access-two", "refresh-two", "full", "user-two") }
        composeRule
            .onNode(
                hasText("重试") and hasClickAction() and
                    androidx.compose.ui.test
                        .hasAnyAncestor(
                            androidx.compose.ui.test
                                .hasTestTag(OrderTags.DETAIL_ERROR),
                        ),
            ).performClick()
        settle()
        Thread.sleep(LATE_MS)
        settle()
        assertEquals("旧【重试】回调不以 B 的会话请求 A 页面的订单", before, count("$LIST_PATH/ord-o7"))
        assertTrue(
            "旧回调执行前即返回：页面不被改成加载中",
            composeRule.onAllNodesWithTag(OrderTags.DETAIL_ERROR).fetchSemanticsNodes().isNotEmpty(),
        )
        // 通知到达：清空旧页面的会话出口按 B 重新绑定并请求一次
        composeRule.runOnUiThread {
            login.syncSession()
            orders.onSessionChanged()
        }
        idleUntil { count("$LIST_PATH/ord-o7") == before + 1 }
    }

    /** R3-02：A 的断网缓存态（内容态）→ 会话存储换成 B、通知未到 → 点画面上真实的缓存态刷新【重试】：零请求，复制等按钮也失效。 */
    @Test
    fun r3_02_oldStaleDetailRefreshBeforeTheSessionNoticeSendsNothing() {
        launch()
        openOrders()
        open("OrderDetail", mapOf("order_id" to "ord-self-1"))
        idleUntil { composeRule.onAllNodesWithTag(OrderTags.DETAIL_AMOUNTS).fetchSemanticsNodes().isNotEmpty() }
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        synchronized(answers) { answers["$LIST_PATH/ord-self-1"] = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START) } }
        open("OrderDetail", mapOf("order_id" to "ord-self-1"))
        idleUntil { composeRule.onAllNodesWithTag(OrderTags.DETAIL_STALE).fetchSemanticsNodes().isNotEmpty() }
        settle()
        val before = count("$LIST_PATH/ord-self-1")
        val writes = system.writes
        composeRule.runOnUiThread { tokens.save("access-two", "refresh-two", "full", "user-two") }
        composeRule.onNodeWithTag(OrderTags.DETAIL_COPY, useUnmergedTree = true).performClick()
        composeRule.onNodeWithTag(OrderTags.DETAIL_STALE_RETRY, useUnmergedTree = true).performClick()
        settle()
        Thread.sleep(LATE_MS)
        settle()
        assertEquals("旧刷新回调零请求", before, count("$LIST_PATH/ord-self-1"))
        assertEquals("旧复制回调零写入", writes, system.writes)
        assertTrue(
            "旧回调执行前即返回：页面不被改成加载中",
            composeRule.onAllNodesWithTag(OrderTags.DETAIL_STALE).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    // ---------------------------------------------------------------- FU-AND-0710 第 3 轮定向复核修复（RC-01）

    /**
     * RC-01（APP-08 共性第 21 条、APP-09 共性第 65 条）：A 会话下进程恢复的详情 ViewModel 停在 restoreGate → 会话存储换成 B，会话通知还没执行
     * → 恢复等待结束：零请求（不以 B 的会话请求 A 页面的 order_id，也不按 A 的旧身份发出），页面状态不被改写；通知到达后（清空旧页面的会话
     * 出口）才按 B 重新绑定并请求一次。
     */
    @Test
    fun rc01_restoredDetailWaitEndingAfterTheStoreSwitchBeforeTheNoticeSendsNothing() {
        launch()
        openOrders()
        settle()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val path = "$LIST_PATH/ord-o7"
        val before = count(path)
        lateinit var model: com.couli.feature.orders.OrderDetailViewModel
        composeRule.runOnUiThread {
            val base = orders.detailConfig(RouteDestination("OrderDetail", """{"order_id":"ord-o7"}""", false), RC01_ENTRY) {}!!
            val config =
                com.couli.feature.orders.OrderDetailConfig(
                    api = base.api,
                    env = base.env,
                    texts = base.texts,
                    cache = base.cache,
                    mask = base.mask,
                    orderId = base.orderId,
                    onBack = base.onBack,
                    bindLeave = base.bindLeave,
                    clock = base.clock,
                    restoreGate = { gate.await() },
                    rateGate = base.rateGate,
                    bindSession = base.bindSession,
                )
            // 进程恢复：保存状态里已有「开始过」标记 → 先等 restoreGate
            model =
                com.couli.feature.orders.OrderDetailViewModel(
                    config,
                    androidx.lifecycle.SavedStateHandle(mapOf("order_detail.started" to true)),
                )
        }
        settle()
        assertEquals("前提：恢复等待期间不发请求", before, count(path))
        // 会话存储换成 B，通知还没执行；随后恢复等待结束
        composeRule.runOnUiThread { tokens.save("access-two", "refresh-two", "full", "user-two") }
        composeRule.runOnUiThread { gate.complete(Unit) }
        settle()
        Thread.sleep(LATE_MS)
        settle()
        assertEquals("恢复等待结束：会话存储已换号而通知未到 → 零请求", before, count(path))
        assertNull("等待结束后没有按任何身份发起加载（状态未被绑定）", model.controller.state.value.owner)
        // 通知到达：清空旧页面的会话出口按 B 重新绑定并请求一次
        composeRule.runOnUiThread {
            login.syncSession()
            orders.onSessionChanged()
        }
        idleUntil { count(path) == before + 1 }
        settle()
        assertEquals("只请求一次", before + 1, count(path))
        composeRule.runOnUiThread { model.controller.leave() }
    }

    /**
     * RC-01 同机制（待跟单发起页）：A 会话下外跳 → 会话存储换成 B、通知未到 → 回到前台：不按 A 的外跳记录替 B 拉卡（零请求）；通知到达后再回前台
     * 也不再按旧外跳拉。
     */
    @Test
    fun rc01_jumpReturnAfterTheStoreSwitchBeforeTheNoticeFetchesNoCards() {
        launch()
        synchronized(answers) { answers[PENDING_PATH] = { envelope(pendingTwo()) } }
        lateinit var page: AppOrders.PendingPage
        composeRule.runOnUiThread { page = orders.pendingFor("product-entry")!! }
        composeRule.runOnUiThread { page.jumped("taobao") }
        settle()
        val before = count(PENDING_PATH)
        composeRule.runOnUiThread { tokens.save("access-two", "refresh-two", "full", "user-two") }
        composeRule.runOnUiThread {
            page.onStop()
            page.onStart()
        }
        settle()
        Thread.sleep(LATE_MS)
        settle()
        assertEquals("外跳等待结束：会话存储已换号而通知未到 → 零请求", before, count(PENDING_PATH))
        composeRule.runOnUiThread {
            login.syncSession()
            orders.onSessionChanged()
        }
        settle()
        composeRule.runOnUiThread {
            page.onStop()
            page.onStart()
        }
        settle()
        assertEquals("旧外跳记录已丢弃", before, count(PENDING_PATH))
        composeRule.runOnUiThread { orders.followBackStackIds(emptySet()) }
    }

    /** 会话换成 [user]（null = 失效 / 退出）：Router 会话状态与网络层会话通知都交给 AppOrders（与 AppModule 同一入口）。 */
    private fun switchSession(user: String?) {
        composeRule.runOnUiThread {
            if (user == null) tokens.clear() else tokens.save("access-$user", "refresh-$user", "full", user)
            login.syncSession()
            orders.onSessionChanged()
        }
        settle()
    }

    private fun ownerOf(user: String): String = OrderStaleCache.Store.hash(user)

    /** 重新组合一份（新的 NavController → 新的 Home 条目与列表 ViewModel，内存里没有上次的结果），模拟冷启动后打开订单页。 */
    private fun relaunchOrdersOnly() {
        composeRule.runOnUiThread { relaunchCounter.intValue += 1 }
        composeRule.waitForIdle()
        open("OrderList")
    }

    private val relaunchCounter = androidx.compose.runtime.mutableIntStateOf(0)

    /** 下拉刷新的同一个入口（PullToRefreshBox 的 onRefresh → OrderListViewModel.refresh；手势在 Robolectric 下不稳定）。 */
    private fun refreshList() {
        composeRule.runOnUiThread { orders.listHandle()?.refresh() }
        settle()
    }

    private fun cacheFiles(): List<File> =
        AppOrders
            .cacheRoot(context)
            .walkTopDown()
            .filter { it.isFile }
            .toList()

    private fun withdrawalCleaners() =
        AppModule.credentialCleaners(
            context,
            MemorySessionTokens(),
            inFlight,
            Provider { noDevice() },
            Provider { noTracker() },
            BridgeHostRegistry(),
        )

    private fun environmentCleaners() =
        AppModule.environmentSwitchCleaners(
            context,
            MemorySessionTokens(),
            NoCipher,
            inFlight,
            Provider { noDevice() },
            Provider { error("no config") },
            Provider { noTracker() },
            Provider { EnvironmentGate { EnvironmentStartupReconciler.Outcome.UNCHANGED } },
            Provider { error("no privacy gate") },
            BridgeHostRegistry(),
        )

    private object NoCipher : SecretCipher {
        override fun encrypt(plain: ByteArray): ByteArray = plain

        override fun decrypt(sealed: ByteArray): ByteArray = sealed
    }

    private fun noDevice() =
        DeviceRegistry(
            platform = ClientPlatformRaw.ANDROID,
            idReader = { null },
            store = MemoryDeviceCredentialStore(),
            privacy = { PrivacyStateRaw.CONSENTED },
            client = { error("no network") },
            epochs = { CallEpochs(0, 0) },
        )

    private fun noTracker() =
        TrackerQueue(
            readFile = { null },
            writeFile = {},
            sink = { false },
            privacy = PrivacyStateRaw.CONSENTED,
            userId = { null },
            deviceId = "device-fixture",
            platform = ClientPlatformRaw.ANDROID,
            appVersion = "1.0.0",
            sessionId = "session-test",
            clock = { now },
            canUpload = { false },
        )

    private fun hasTextInside(text: String) = SemanticsMatcher("has text $text") { node -> node.textsInside().any { it.contains(text) } }

    private fun androidx.compose.ui.semantics.SemanticsNode.textsInside(): List<String> {
        val out = mutableListOf<String>()

        fun walk(node: androidx.compose.ui.semantics.SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.let { out += it }
            node.children.forEach(::walk)
        }
        walk(this)
        return out
    }

    // ---------------------------------------------------------------- 假数据（sample-data §12.2、§12.3）

    private fun defaultAnswer(
        path: String,
        query: Map<String, String>,
    ): MockResponse =
        when {
            path == LIST_PATH -> envelope(listData(if (query["scope"] == "share") listOf(share1()) else listOf(self1())))
            path == PENDING_PATH -> envelope("""{"items":[]}""")
            path.startsWith("$LIST_PATH/") -> detailAnswer(path.removePrefix("$LIST_PATH/"))
            path.startsWith("/v1/products/") -> envelope("null", code = 30143)
            else -> envelope("null")
        }

    @Suppress("CyclomaticComplexMethod")
    private fun detailAnswer(id: String): MockResponse =
        when (id) {
            "ord-o7" -> envelope(detail(id, "CREDITED", productKey = "tb:9007", actual = 310))
            "ord-o8" -> envelope(detail(id, "CREDITED", productKey = null, actual = 310))
            "ord-o10" -> envelope(detail(id, "CREDITED", productKey = null, actual = 310, other = true))
            "ord-refund" -> envelope(detail(id, "INVALID", reason = "REFUND"))
            "ord-rights" -> envelope(detail(id, "INVALID", reason = "RIGHTS"))
            "ord-blacklist" -> envelope(detail(id, "INVALID", reason = "BLACKLIST", actions = listOf("APPEAL")))
            "ord-punish" -> envelope(detail(id, "INVALID", reason = "PUNISH", actions = listOf("CONTACT_CS")))
            "ord-zero" -> envelope(detail(id, "INVALID", reason = "COMMISSION_ZERO", actions = listOf("CONTACT_CS")))
            "ord-clawed-punish" -> envelope(detail(id, "CLAWED_BACK", reason = "PUNISH", actions = listOf("CONTACT_CS"), clawback = 320))
            "ord-waiting" -> envelope(detail(id, "WAITING", period = "2026-11"))
            "ord-waiting-2027" -> envelope(detail(id, "WAITING", period = "2027-01"))
            "ord-waiting-null" -> envelope(detail(id, "WAITING", period = null))
            "ord-crediting" -> envelope(detail(id, "CREDITING", period = "2026-11"))
            "ord-reviewing" -> envelope(detail(id, "REVIEWING", period = "2026-11"))
            "ord-rights-pending" -> envelope(detail(id, "RIGHTS_PENDING", period = "2026-11"))
            "ord-presale" -> envelope(presaleDetail())
            "ord-compare" -> envelope(detail(id, "NO_REBATE", priceCompare = true))
            "ord-missing" -> envelope("null", code = 30701).setResponseCode(404)
            "ord-share-1" -> envelope(share1().removeSuffix("}") + SHARE_DETAIL_TAIL)
            else -> envelope(detail(id, "PAID"))
        }

    @Suppress("LongParameterList") // 样例行的字段逐项
    private fun summary(
        id: String,
        status: String,
        orderNo: String? = "3741234567890123456",
        masked: String? = null,
        title: String? = "样例商品 A：纯牛奶 250ml×12 盒",
        pay: Long? = 4560,
        est: Long? = 234,
        reason: String? = null,
        other: Boolean = false,
        paidAt: String? = "2026-10-08T09:05:00+08:00",
    ): String {
        val number = if (orderNo != null) """"order_no":"$orderNo"""" else """"masked_order_no":"$masked""""
        return """{"order_id":"$id","platform":"taobao","title":${title?.let { "\"$it\"" } ?: "null"},"image_url":null,""" +
            """"pay_amount_fen":${pay ?: "null"},"quantity":2,$number,"display_status":"$status","reason":${reason?.let {
                "\"$it\""
            } ?: "null"},""" +
            """"est_rebate_fen":${est ?: "null"},"paid_at":${paidAt?.let { "\"$it\"" } ?: "null"},"is_other_product":$other}"""
    }

    private fun self1(id: String = "ord-self-1") = summary(id, "PAID")

    private fun share1() =
        summary("ord-share-1", "PAID", orderNo = null, masked = "3712****67", title = "样例商品 E：保温杯 500ml", pay = 5900, est = 180)

    private fun presale(
        pay: Long?,
        id: String = "ord-presale",
    ) = summary(id, "DEPOSIT_PAID", orderNo = "3798765432109876543", title = "样例商品 B：预售电饭煲 4L", pay = pay, est = null, paidAt = null)

    private fun allStatuses(): List<String> =
        listOf(
            "DEPOSIT_PAID" to "已付定金",
            "PAID" to "已付款，返利待确认",
            "WAITING" to "已收货，等待联盟结算",
            "CREDITING" to "入账核对中",
            "RIGHTS_PENDING" to "售后处理中，入账暂停",
            "NO_REBATE" to "本单无返利",
            "CREDITED" to "已结算",
            "INVALID" to "已失效",
            "CLAWED_BACK" to "已扣回",
            "FUTURE_STATUS" to "状态更新中",
        ).map { (status, label) -> summary("st-" + label.hashCode(), status) }

    private fun listData(
        items: List<String>,
        earliest: String? = null,
        cursor: String? = null,
    ) = """{"items":[${items.joinToString(",")}],"next_cursor":${cursor?.let { "\"$it\"" } ?: "null"},""" +
        """"earliest_visible_date":${earliest?.let { "\"$it\"" } ?: "null"}}"""

    @Suppress("LongParameterList")
    private fun detail(
        id: String,
        status: String,
        productKey: String? = "tb:sampleA",
        actual: Long? = null,
        clawback: Long? = null,
        reason: String? = null,
        actions: List<String> = emptyList(),
        period: String? = null,
        priceCompare: Boolean? = false,
        other: Boolean = false,
    ): String {
        val base =
            summary(
                id,
                status,
                reason = reason,
                other = other,
                title = if (other) null else "样例商品 A：纯牛奶 250ml×12 盒",
            ).removeSuffix("}")
        val timeline =
            """[{"node":"paid","at":"2026-10-08T09:05:00+08:00","period":null},{"node":"received","at":null,"period":null},""" +
                """{"node":"credit_expected","at":null,"period":${period?.let {
                    "\"$it\""
                } ?: "null"}},{"node":"credited","at":null,"period":null}]"""
        return base + ""","product_key":${if (other) "null" else productKey?.let { "\"$it\"" } ?: "null"},""" +
            """"reason_action":[${actions.joinToString(",") { "\"$it\"" }}],"timeline":$timeline,""" +
            """"expected_credit_period":${period?.let { "\"$it\"" } ?: "null"},"credit_overdue":false,""" +
            """"actual_fen":${actual ?: "null"},"clawback_fen":${clawback ?: "null"},"appeal_pending":false,""" +
            """"is_price_compare":${priceCompare ?: "null"}}"""
    }

    private fun presaleDetail(): String {
        val base = presale(3000).removeSuffix("}")
        return base + ""","product_key":"tb:sampleB","reason_action":[],"timeline":[""" +
            """{"node":"deposit_paid","at":"2026-10-01T20:00:00+08:00","period":null},{"node":"final_paid","at":null,"period":null},""" +
            """{"node":"received","at":null,"period":null},{"node":"credit_expected","at":null,"period":null},""" +
            """{"node":"credited","at":null,"period":null}],"expected_credit_period":null,"credit_overdue":false,""" +
            """"actual_fen":null,"clawback_fen":null,"appeal_pending":false,"is_price_compare":false}"""
    }

    private fun track(
        link: String,
        attempt: String,
        platform: String,
        claim: Boolean,
        closed: Boolean,
    ) = """{"link_id":"$link","attempt_id":"$attempt","platform":"$platform","jumped_at":"2026-10-09T09:00:00+08:00",""" +
        """"show_claim_entry":$claim,"dismissed":$closed}"""

    /** 两个平台两项，京东那项已关闭：只显示淘宝一张。 */
    private fun pendingTwo() =
        """{"items":[${track("lnk-tb", "att-tb-2", "taobao", claim = true, closed = false)},""" +
            """${track("lnk-jd", "att-jd-1", "jd", claim = false, closed = true)}]}"""

    private fun envelope(
        data: String,
        code: Int = 0,
        trace: String = "trace-000123",
    ) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"code":$code,"msg":"","data":$data,"trace_id":"$trace"}""")

    private companion object {
        const val LIST_PATH = "/v1/orders"

        /** A 的完整自购订单号（样例数据）。 */
        const val A_ORDER_NO = "3741234567890123456"
        const val PENDING_PATH = "/v1/orders/pending-tracks"
        const val DISMISS_PREFIX = "/v1/orders/pending-tracks/"
        const val WAIT_S = 5L
        const val POLL_MS = 10L
        const val SETTLE_ROUNDS = 5
        const val LATE_MS = 200L

        /** RC-01 用例里进程恢复的详情页的回退栈条目。 */
        const val RC01_ENTRY = "rc01-restored-detail"
        const val SHARE_DETAIL_TAIL =
            ""","product_key":"tb:sampleE","reason_action":[],"timeline":[],"expected_credit_period":null,"credit_overdue":false,""" +
                """"actual_fen":null,"clawback_fen":null,"appeal_pending":false,"is_price_compare":false}"""
        const val CONFIG = """{"features":{"flags":{},"platform_status":{"taobao":"on"}}}"""
        const val CONFIG_H5 =
            """{"features":{"flags":{},"platform_status":{"taobao":"on"}},""" +
                """"h5_base_url":"https://h5.example.test/","bridge_origins":["https://h5.example.test"],""" +
                """"help_links":{"price_compare":{"route":"Help","params":{"article_id":"price-compare"}}}}"""
        const val CONFIG_WITH_HELP =
            """{"features":{"flags":{},"platform_status":{"taobao":"on"}},""" +
                """"help_links":{"price_compare":{"route":"Help","params":{"article_id":"price-compare"}}}}"""
    }
}

/**
 * 旧格式的恢复种子（个人数据直接放在系统保存状态里，含 orders.owner）→ 本轮的格式：个人数据写进与 AppModule 同一个恢复记录落盘，系统保存状态只留
 * 随机引用与不含个人数据的值（R1-03）。原有恢复用例的种子不用改写。
 */
private fun seededArgs(args: android.os.Bundle): android.os.Bundle {
    if (!args.containsKey("orders.owner")) return args
    val store = orderRestoreStore(ApplicationProvider.getApplicationContext())
    val ref = store.newRef()
    store.write(
        ref,
        store.epoch,
        OrderRestoreRecord(
            owner = args.getString("orders.owner"),
            platform = args.getString("orders.platform"),
            month = args.getString("orders.month"),
            q = args.getString("orders.q"),
            input = args.getString("orders.input").orEmpty(),
            anchorOrderId = args.getString("orders.anchor_order_id"),
        ),
    )
    return android.os.Bundle(args).apply {
        listOf("orders.owner", "orders.platform", "orders.month", "orders.q", "orders.input", "orders.anchor_order_id").forEach(::remove)
        putString("orders.restore_ref", ref)
    }
}
