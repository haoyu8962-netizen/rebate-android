package com.couli.app.agent

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.login.AppLogin
import com.couli.app.me.AppMe
import com.couli.app.me.MeSystemPorts
import com.couli.app.privacy.AppPrivacy
import com.couli.app.routing.AppRouting
import com.couli.app.routing.RouteDestination
import com.couli.app.routing.RoutingPrivacyHooks
import com.couli.app.shell.AppShell
import com.couli.core.agent.AgentController
import com.couli.core.agent.AgentPhase
import com.couli.core.agent.AgentStatus
import com.couli.core.agent.AgentTimer
import com.couli.core.agent.ApiAgentContractPort
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.designsystem.CouliTheme
import com.couli.core.me.MeLocalStore
import com.couli.core.network.ApiClient
import com.couli.core.network.ClientIdentity
import com.couli.core.network.DeviceCredentials
import com.couli.core.network.InFlightCalls
import com.couli.core.network.MemoryPendingKeyBackend
import com.couli.core.network.MemorySessionTokens
import com.couli.core.network.NetworkContext
import com.couli.core.network.NetworkHooks
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PrivacyRaw
import com.couli.core.network.UpdateRaw
import com.couli.core.privacy.CredentialCleanerRegistry
import com.couli.core.privacy.PrivacyGate
import com.couli.core.privacy.PrivacyStore
import com.couli.core.privacy.RetryScheduler
import com.couli.core.push.PushTokenSource
import com.couli.core.router.PrivacyState
import com.couli.core.router.RouteRequest
import com.couli.core.router.RouteSource
import com.couli.core.router.SessionState
import com.couli.core.router.UpdateCheck
import com.couli.feature.login.LegalVersions
import java.net.URLDecoder
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * APP-10b 抓包集成测试（brief §0 红线第 1–3 条、§2 第 15 条）：真实 AppShell + NavHost + AppRouting + AppPrivacy + AppLogin + AppMe + AppAgent + 真实端口
 * ApiAgentContractPort + 真实 ApiClient 流式通道（OkHttp ResponseBody.source() 逐块读）对本地假服务 MockWebServer；页面经 Router 打开。断言按服务端收到的请求
 * （顺序、Authorization、次数）与端口交付计数：A 的流 / 历史在换号后 0 帧进入 B；退出登录 / 撤回 AI 同意 / 撤回隐私各 1 次 cancel、早于流程自己的请求、带原令牌、
 * 0 次刷新；切环境只停流不发；【停止】只发 1 次；字节级 UTF-8、70 KiB 单帧、401 帧在真实流上 limited；Release 开放与入口开关。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1100dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("LargeClass", "TooManyFunctions")
class AgentTransportIntegrationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Volatile private var clock = OffsetDateTime.parse("2026-10-14T10:00:00+08:00").toInstant().toEpochMilli()
    private lateinit var server: MockWebServer
    private lateinit var routing: AppRouting
    private lateinit var privacy: AppPrivacy
    private lateinit var tokens: MemorySessionTokens
    private lateinit var api: ApiClient
    private lateinit var login: AppLogin
    private lateinit var me: AppMe
    private lateinit var agent: AppAgent
    private lateinit var port: ApiAgentContractPort
    private lateinit var navController: NavHostController
    private val inFlight = InFlightCalls()
    private val timer = Timer()
    private val config = MutableStateFlow<JsonObject?>(null)

    /** 服务端收到的请求（顺序、方法、路径、Authorization）。 */
    private val requests = CopyOnWriteArrayList<Recorded>()

    /** 流式回答（缺省：一轮慢流，meta + card 先到，done 很晚才到）。 */
    @Volatile private var streamAnswer: () -> MockResponse = { slowStream() }

    @Volatile private var currentAnswer: (Recorded) -> MockResponse = { envelope("""{"session":null}""") }

    @Volatile private var historyAnswer: (Recorded) -> MockResponse = { envelope(emptyPage()) }

    @Volatile private var cancelAnswer: () -> MockResponse = { envelope("{}") }

    @Volatile private var feedbackAnswer: () -> MockResponse = { envelope("{}") }

    /** 不为 null 时 logout 停在服务端等它放行（身份变更在途）。 */
    @Volatile private var logoutHold: CountDownLatch? = null

    /** 不为 null 时 /v1/consents 停在服务端等它放行（撤回 AI 同意 / 撤回隐私的自身请求在途，R2-03）。 */
    @Volatile private var consentsHold: CountDownLatch? = null

    /** cancel 请求「连接建立」的延迟（毫秒，真实时间；应用拦截器在写出前的准入之前睡眠，R2-03）。0 = 不延迟。 */
    @Volatile private var cancelConnectDelayMs = 0L

    private class Recorded(
        val method: String,
        val path: String,
        val query: String?,
        val auth: String?,
        val body: String,
    ) {
        override fun toString(): String = "$method $path"
    }

    private inner class Timer : AgentTimer {
        private val pending = CopyOnWriteArrayList<Triple<Long, () -> Unit, BooleanArray>>()

        override fun after(
            delayMs: Long,
            block: () -> Unit,
        ): () -> Unit {
            val cancelled = BooleanArray(1)
            pending += Triple(clock + delayMs, block, cancelled)
            return { cancelled[0] = true }
        }

        fun fire() {
            while (true) {
                val due = pending.firstOrNull { !it.third[0] && it.first <= clock } ?: return
                pending -= due
                due.second()
            }
        }
    }

    private class MemoryStore : PrivacyStore {
        override var decisionRaw: String = "consented"
        override var consentedVersion: String? = "3"
        override var consentedAtMillis: Long? = 0L
        override var basicMode: Boolean = false
        override var withdrawalCleanupPending: Boolean = false
    }

    private object Device : DeviceCredentials {
        override val deviceId: String = "device-fixture-ABC123"
        override val installSecret: String = "install-fixture"
    }

    private object NoSystem : MeSystemPorts {
        override fun pushStatus(): String = "not_asked"

        override fun pushDeniedAt(): Long? = null

        override fun openNotificationSettings() = Unit

        override fun openAppSettings() = Unit

        override fun openExternal(url: String): Boolean = false

        override fun wechatInstalled(): String = "unknown"

        override fun cacheBytes(): Long = 0L

        override fun clearCache(): Boolean = true

        override fun clearWebCache(): Boolean = true

        override fun deviceId(): String = "device-fixture-ABC123"

        override fun osVersion(): String = "TEST_OS"

        override fun model(): String = "TEST_MODEL"
    }

    private object NoPush : PushTokenSource {
        override fun start() = Unit

        override fun stop() = Unit

        override fun current(): String? = null

        override fun subscribe(listener: () -> Unit): () -> Unit = {}
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val full = request.path.orEmpty()
                    val path = URLDecoder.decode(full.substringBefore('?'), "UTF-8")
                    val r =
                        Recorded(
                            request.method.orEmpty(),
                            path,
                            full.substringAfter('?', ""),
                            request.getHeader("Authorization"),
                            request.body.readUtf8(),
                        )
                    requests += r
                    return when {
                        r.method == "GET" && path == "/v1/agent/sessions/current" -> {
                            currentAnswer(r)
                        }

                        r.method == "GET" && path.endsWith("/messages") -> {
                            historyAnswer(r)
                        }

                        r.method == "POST" && path.endsWith("/messages") -> {
                            streamAnswer()
                        }

                        r.method == "POST" && path == "/v1/agent/sessions" -> {
                            envelope("""{"session_id":"s-1","started_at":"$NOW","last_active_at":"$NOW"}""")
                        }

                        path.startsWith("/v1/agent/runs/") -> {
                            cancelAnswer()
                        }

                        path.startsWith("/v1/agent/messages/") -> {
                            feedbackAnswer()
                        }

                        path == "/v1/auth/logout" -> {
                            logoutHold?.await(WAIT_S, TimeUnit.SECONDS)
                            envelope("{}")
                        }

                        path == "/v1/consents" -> {
                            consentsHold?.await(WAIT_S, TimeUnit.SECONDS)
                            envelope("{}")
                        }

                        path == "/v1/auth/refresh" -> {
                            error("不应刷新")
                        }

                        r.method == "GET" && path.startsWith("/v1/products/") -> {
                            envelope(PRODUCT_CARD)
                        }

                        r.method == "GET" && path == "/v1/orders/ord-1" -> {
                            envelope(ORDER_DETAIL)
                        }

                        else -> {
                            envelope("{}")
                        }
                    }
                }
            }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    /** 商品详情与订单详情（R1-04）：[launch] 带 withDetails 时接上，与 AppModule 一样把 agent 交给它们。 */
    private var search: com.couli.app.search.AppSearch? = null
    private var orders: com.couli.app.orders.AppOrders? = null

    @Suppress("LongMethod", "CyclomaticComplexMethod") // 与 AppModule 同一套接线逐项组装
    private fun launch(
        user: String? = USER_A,
        withDetails: Boolean = false,
    ) {
        val gate =
            PrivacyGate(MemoryStore(), clock = { clock }, bundledPolicyVersion = "3").apply {
                retryScheduler =
                    RetryScheduler { _, _ -> }
            }
        privacy = AppPrivacy(gate, environmentSettled = { true }, clock = { clock }).also { it.coldStart() }
        val environment = EnvironmentGate { EnvironmentStartupReconciler.Outcome.UNCHANGED }.also { it.settleAtStartup() }
        tokens = if (user == null) MemorySessionTokens() else MemorySessionTokens("access-$user", "refresh-$user", "full", user)
        routing =
            AppRouting(
                context,
                environment,
                clock = { clock },
                initialPrivacy = privacy.conclusion,
                privacyHooks =
                    RoutingPrivacyHooks(promptShowing = {
                        privacy.promptShowing
                    }, credentialsReadable = { privacy.credentialAccess.readable() }),
                initialUpdate = UpdateCheck.OK,
            )
        privacy.onConclusionChanged { routing.privacy.value = it }
        val main = Handler(Looper.getMainLooper())
        api =
            ApiClient(
                identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.0.0", build = "1", channel = "official"),
                baseUrl = { server.url("/").toString() },
                context = {
                    NetworkContext(
                        when (privacy.conclusion) {
                            PrivacyState.CONSENTED -> PrivacyRaw.FULL
                            PrivacyState.BASIC_MODE -> PrivacyRaw.BASIC
                            PrivacyState.NOT_DECIDED -> PrivacyRaw.UNDECIDED
                        },
                        environmentSettled = true,
                        credentialsReadable = !privacy.credentialAccess.cleanupPending,
                        UpdateRaw.SUPPORTED,
                    )
                },
                device = Device,
                tokens = tokens,
                pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { clock },
                hooks =
                    NetworkHooks(
                        onEffect = { effect -> main.post { if (::login.isInitialized) login.onNetworkEffect(effect) } },
                        onSessionStored = { generation -> main.post { if (::login.isInitialized) login.onSessionStored(generation) } },
                    ),
                inFlight = inFlight,
                sleeper = {},
                // R2-03：cancel 的「连接建立」延迟（应用拦截器先于写出前准入的网络拦截器执行）
                http =
                    OkHttpClient
                        .Builder()
                        .addInterceptor { chain ->
                            val delay = cancelConnectDelayMs
                            if (delay > 0 &&
                                chain
                                    .request()
                                    .url.encodedPath
                                    .startsWith("/v1/agent/runs/")
                            ) {
                                Thread.sleep(delay)
                            }
                            chain.proceed(chain.request())
                        }.build(),
            )
        var built: AppAgent? = null
        port = ApiAgentContractPort(api, { runnable -> main.post(runnable) }, allowed = { built?.requestsAllowed() == true })
        composeRule.runOnUiThread {
            routing.updateSession(if (user == null) SessionState.GUEST else SessionState.PHONE_BOUND, resumePending = false)
            login = AppLogin(api, tokens, routing, updateCheck = { UpdateCheck.OK }, markBelow = {}, legal = LegalVersions(3, 2))
            login.syncSession()
            me =
                AppMe(
                    client = api,
                    tokens = tokens,
                    routing = routing,
                    privacy = privacy,
                    store = null,
                    login = { login },
                    updateCheck = { UpdateCheck.OK },
                    serverForced = {},
                    writer = { false },
                    local = MeLocalStore(context),
                    mask = null,
                    system = NoSystem,
                    pushSource = NoPush,
                    nickname = null,
                    notify = null,
                    clock = { clock },
                )
            agent =
                AppAgent(
                    client = api,
                    tokens = tokens,
                    routing = routing,
                    privacy = privacy,
                    port = { port },
                    envKey = { "test" },
                    configData = { config.value },
                    mask = MutableStateFlow(false),
                    track = {},
                    serverForced = {},
                    trade = null,
                    clock = { clock },
                    timer = timer,
                ).also {
                    it.connect()
                    it.connectConfig(config)
                    built = it
                }
            if (withDetails) wireDetails()
            me.beginAgentIdentityChange = agent::beginIdentityChange
            me.endAgentIdentityChange = agent::endIdentityChange
            val cleaners =
                CredentialCleanerRegistry().apply {
                    register("in_flight_calls") { inFlight.onPrivacyRevoked() }
                    register(AppAgent.AGENT_CONTROLLER_CLEANER) { agent.onConsentWithdrawing() }
                    register(CredentialCleanerRegistry.TOKENS) { tokens.clear() }
                    register(CredentialCleanerRegistry.DEVICE_ID) {}
                    register(CredentialCleanerRegistry.INSTALL_SECRET) {}
                }
            gate.withdrawalCleanup = { cleaners.cleanAll() }
        }
        composeRule.setContent {
            navController = rememberNavController()
            CouliTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    AppShell(
                        routing = routing,
                        privacy = privacy,
                        homeContent = {},
                        navController = navController,
                        me = me,
                        login = login,
                        agent = agent,
                        search = search,
                        orders = orders,
                    )
                }
            }
        }
        settle()
    }

    /** 商品详情与订单详情（R1-04）：与 AppModule 一样把 agent 交给 AppSearch / AppOrders。 */
    private fun wireDetails() {
        val store =
            com.couli.core.clientcore.ConfigStore(
                readFile = { null },
                writeFile = {},
                fetcher = { _, done ->
                    done(
                        com.couli.core.clientcore.ConfigReply(
                            com.couli.core.clientcore.ConfigReply.OK,
                            DETAIL_CONFIG,
                            null,
                        ),
                    )
                },
                clock = { clock },
                bundled = DETAIL_CONFIG,
            )
        val pageApi =
            com.couli.core.network
                .ApiClientPageApi(api)
        search =
            com.couli.app.search
                .AppSearch(api, store, routing, privacy = { privacy.conclusion }, api = pageApi)
                .also { it.agent = agent }
        orders =
            com.couli.app.orders
                .AppOrders(
                    api = pageApi,
                    store = store,
                    routing = routing,
                    sessionEpoch = { tokens.loginEpoch },
                    account = { api.activeUserId() },
                    writer = { false },
                    permissions = null,
                    notificationsEnabled = { false },
                    forceUpdate = {},
                    cache = null,
                    mask = null,
                    clock = { clock },
                ).also { it.agent = agent }
    }

    // ---------------------------------------------------------------- 工具

    private fun open(route: String) {
        composeRule.runOnUiThread { routing.router.open(RouteRequest(route, JsonObject(emptyMap()), RouteSource.IN_APP)) }
        settle()
    }

    private fun openWith(
        route: String,
        params: Map<String, String>,
    ) {
        composeRule.runOnUiThread {
            routing.router.open(RouteRequest(route, JsonObject(params.mapValues { JsonPrimitive(it.value) }), RouteSource.IN_APP))
        }
        settle()
    }

    private fun tagged(tag: String): Boolean = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun settle() {
        repeat(ROUNDS) {
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.waitForIdle()
            Thread.sleep(POLL_MS)
        }
    }

    private fun idleUntil(
        message: String = "",
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S)
        while (!condition() && System.nanoTime() < deadline) settle()
        assertTrue("$message 请求 ${requests.toList()}", condition())
    }

    private fun controller(): AgentController = agent.controller.value!!

    /** 「我的」服务作用域（AppMe 的会话级作用域）里此刻在跑的协程（含常驻订阅）。 */
    private fun meServiceJobs(): Set<kotlinx.coroutines.Job> =
        me.deps.service.coroutineContext[kotlinx.coroutines.Job]!!
            .children
            .filter { it.isActive }
            .toSet()

    /** 会话页控制器已建好、且这次历史 / 会话读取已有结论（不在 IDLE、LOADING）。 */
    private fun loadSettled(): Boolean {
        val phase = agent.controller.value?.currentPhase ?: return false
        return phase != AgentPhase.LOADING && phase != AgentPhase.IDLE
    }

    private fun send(text: String = "帮我找纯牛奶") {
        // FU-AND-0716 窄修二：进会话页后历史读取在后台（LOADING 时不受理发送）；先等这次加载有结论，原写法 open + 固定轮次后直接发，历史晚到即「没有受理」
        idleUntil("会话加载结束") { loadSettled() }
        composeRule.runOnUiThread {
            val c = controller()
            check(c.send(c.snapshot.value.page, text)) { "没有受理：${c.currentPhase}" }
        }
        settle()
    }

    private fun tree(): String = composeRule.onRoot(useUnmergedTree = true).printToString()

    private fun backStackRoutes(): List<String> =
        navController.currentBackStack.value.mapNotNull { e ->
            e
                .takeIf {
                    it.destination.hasRoute(
                        RouteDestination::class,
                    )
                }?.let { runCatching { it.toRoute<RouteDestination>().name }.getOrNull() }
        }

    private fun hasText(text: String): Boolean =
        composeRule.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun count(path: String): Int = requests.count { it.path.startsWith(path) }

    private fun index(path: String): Int = requests.indexOfFirst { it.path.startsWith(path) }

    private fun envelope(
        data: String,
        code: Int = 0,
        status: Int = 200,
    ) = MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"code":$code,"msg":"","data":$data,"trace_id":"0199a3b4-5c6d-7e8f-9a0b-1c2d3e4f5a6b"}""")

    private fun emptyPage(next: String? = null) = """{"items":[],"next_cursor":${next?.let { "\"$it\"" } ?: "null"},"expired":false}"""

    private fun sse(
        text: String,
        chunk: Int = 1024,
    ) = MockResponse()
        .setHeader("Content-Type", "text/event-stream; charset=utf-8")
        .setHeader("X-Trace-Id", "0199a3b4-5c6d-7e8f-9a0b-1c2d3e4f5a6b")
        .setChunkedBody(text, chunk)

    /** 一轮慢流：meta + 一张卡先到，之后的文字很慢（作废发生在它们到达之前）。 */
    private fun slowStream(): MockResponse {
        val head = frame("meta", 1, META_DATA) + frame("card", 2, CARD_DATA)
        val tail =
            (3..40).joinToString("") { frame("text.delta", it, """{"seq":$it,"delta":"A的回复第${it}段"}""") } +
                frame("done", 41, """{"finish_reason":"stop","quota_left":7}""")
        return sse(head + tail).throttleBody(head.toByteArray().size.toLong(), THROTTLE_MS, TimeUnit.MILLISECONDS)
    }

    private fun frame(
        event: String,
        id: Int,
        data: String,
    ) = "event: $event\nid: $id\ndata: $data\n\n"

    // ---------------------------------------------------------------- 红线 1：A 的流 / 历史在换号后 0 帧进入 B

    @Test
    @Suppress("LongMethod") // 步骤逐项
    fun aStreamAndInFlightHistoryNeverReachBAfterSessionLossAndRelogin() {
        val older = CountDownLatch(1)
        currentAnswer =
            { r ->
                if (r.auth ==
                    "Bearer access-$USER_A"
                ) {
                    envelope("""{"session":{"session_id":"s-A","started_at":"$NOW","last_active_at":"$NOW"}}""")
                } else {
                    envelope("""{"session":null}""")
                }
            }
        historyAnswer = { r ->
            if (r.query.orEmpty().contains("cursor")) {
                older.await(WAIT_S, TimeUnit.SECONDS)
                envelope(
                    """{"items":[{"message_id":"019a0000-0000-7000-8000-000000000901","role":"user",""" +
                        """"created_at":"$NOW","text":"A的旧问题","cards":[],"finish_reason":null}],"next_cursor":null,""" +
                        """"expired":false}""",
                )
            } else {
                envelope(emptyPage("c2"))
            }
        }
        launch()
        open("AgentChat")
        send()
        idleUntil("A 的卡片先到") {
            controller()
                .snapshot.value.turns
                .singleOrNull()
                ?.cards
                ?.isNotEmpty() == true
        }
        // 一个历史页在途
        composeRule.runOnUiThread { controller().loadEarlier(controller().snapshot.value.page) }
        idleUntil("更早一页在途") { requests.any { it.query.orEmpty().contains("cursor") } }
        val a = controller()
        val deliveredAtLoss = port.delivered
        // A 会话失效 → 登录 B
        composeRule.runOnUiThread {
            tokens.clear()
            routing.updateSession(SessionState.GUEST, resumePending = false)
            tokens.save("access-$USER_B", "refresh-$USER_B", "full", USER_B)
            routing.updateSession(SessionState.PHONE_BOUND, resumePending = false)
        }
        settle()
        older.countDown()
        settle()
        open("AgentChat")
        idleUntil("B 的控制器就绪") { agent.controller.value?.currentPhase == AgentPhase.READY && agent.controller.value !== a }
        Thread.sleep(SLOW_MS)
        settle()
        assertTrue(a.isDisposed)
        // 抓包断言：作废之后旧连接上到达的字节 0 帧被交给 Reducer；A 的文字 / 卡片 / done / 历史都不进 B
        assertEquals(deliveredAtLoss, port.delivered)
        val b = controller()
        assertTrue(
            b.snapshot.value.turns
                .isEmpty(),
        )
        assertNull(b.snapshot.value.quotaLeft)
        assertFalse(hasText("A的回复"))
        assertFalse(hasText("A的旧问题"))
        // 会话失效、被动换号：不发 cancel；B 的请求带 B 的令牌
        assertEquals(0, count("/v1/agent/runs/"))
        assertTrue(requests.last { it.path == "/v1/agent/sessions/current" }.auth == "Bearer access-$USER_B")
    }

    // ---------------------------------------------------------------- 红线 2：身份事件的 cancel（G01、共性第 11、23 条）

    @Test
    fun logoutSendsOneSingleShotCancelBeforeLogoutWithTheOriginalTokenAndNoRefresh() {
        cancelAnswer = { envelope("null", 10002, 401) }
        launch()
        open("AgentChat")
        send()
        idleUntil("meta 已到") { controller().activeRun != null && controller().streamStates.single().runId != null }
        val meJobs = meServiceJobs()
        composeRule.runOnUiThread { me.logout(me.identity.value) { true } }
        idleUntil("logout 已发") { index("/v1/auth/logout") >= 0 }
        settle()
        // FU-AND-0716 窄修四：logout 记下时应答还没回来，身份变更仍在途；等 me.logout 起的协程（cancel → logout → 收尾）都结束再核对
        idleUntil("退出结算") { meServiceJobs().none { it !in meJobs } }
        assertEquals(1, count("/v1/agent/runs/"))
        val cancel = requests.first { it.path.startsWith("/v1/agent/runs/") }
        assertEquals("/v1/agent/runs/019a0000-0000-7000-8000-000000000121/cancel", cancel.path)
        assertEquals("Bearer access-$USER_A", cancel.auth)
        assertTrue(index("/v1/agent/runs/") < index("/v1/auth/logout"))
        assertEquals(0, count("/v1/auth/refresh"))
        assertEquals(0, agent.identityChange.let { if (it.active) 1 else 0 })
    }

    @Test
    fun aiConsentWithdrawSendsCancelBeforeRecordConsentFalseAndRebuildsEmpty() {
        launch()
        open("AgentChat")
        send()
        idleUntil("meta 已到") { controller().streamStates.single().runId != null }
        val old = controller()
        composeRule.runOnUiThread { agent.withdrawConsent(agent.openWithdrawConfirm { true }!!, { true }) {} }
        idleUntil("撤回已发") { requests.any { it.path == "/v1/consents" } }
        idleUntil("重建") { agent.controller.value != null && agent.controller.value !== old }
        assertEquals(1, count("/v1/agent/runs/"))
        assertTrue(index("/v1/agent/runs/") < index("/v1/consents"))
        assertEquals("Bearer access-$USER_A", requests.first { it.path.startsWith("/v1/agent/runs/") }.auth)
        assertTrue(old.isDisposed)
        assertTrue(
            controller()
                .snapshot.value.turns
                .isEmpty(),
        )
    }

    @Test
    fun privacyWithdrawSendsCancelInFullModeBeforeTheDeviceRecordAndCleanup() {
        launch()
        open("AgentChat")
        send()
        idleUntil("meta 已到") { controller().streamStates.single().runId != null }
        composeRule.runOnUiThread { me.withdrawConsent(me.identity.value) }
        idleUntil("撤回完成") { privacy.conclusion == PrivacyState.BASIC_MODE }
        settle()
        assertEquals(1, count("/v1/agent/runs/"))
        val consents = index("/v1/consents")
        assertTrue(consents < 0 || index("/v1/agent/runs/") < consents)
        // 基本模式之后不再发任何 /v1/agent/*（BR-ID-11）
        val after = requests.size
        open("AgentChat")
        Thread.sleep(SLOW_MS)
        settle()
        assertEquals(0, requests.drop(after).count { it.path.startsWith("/v1/agent/") })
    }

    @Test
    fun environmentSwitchOnlyStopsTheStreamAndSendsNoCancel() {
        launch()
        open("AgentChat")
        send()
        idleUntil("meta 已到") { controller().streamStates.single().runId != null }
        val delivered = port.delivered
        composeRule.runOnUiThread {
            inFlight.onEnvironmentSwitching()
            agent.onEnvironmentSwitching()
        }
        Thread.sleep(SLOW_MS)
        settle()
        assertEquals(delivered, port.delivered)
        assertEquals(0, count("/v1/agent/runs/"))
    }

    /** 已从 IO 线程排进主线程队列、但还没交付的块：作废后一律丢弃（不进 StreamClient，dropped 计数），变异验证 M2 补的用例。 */
    @Test
    fun chunksQueuedBeforeInvalidationAreDroppedNotDelivered() {
        launch()
        open("AgentChat")
        send()
        idleUntil("meta 已到") { controller().streamStates.single().runId != null }
        val delivered = port.delivered
        val dropped = port.dropped
        // 不推进主线程：等下一批字节由 IO 线程排进主线程队列
        Thread.sleep(THROTTLE_MS + SLOW_MS)
        // Robolectric 的测试线程即主线程：在队列里的块执行之前同步作废
        inFlight.onEnvironmentSwitching()
        agent.onEnvironmentSwitching()
        settle()
        assertEquals(delivered, port.delivered)
        assertTrue("队列里的块应被丢弃：dropped=${port.dropped}", port.dropped > dropped)
    }

    // ---------------------------------------------------------------- 停止只发 1 次（§0 第 2 条）

    @Test
    fun stopSendsExactlyOneCancelThroughTheRealPipeline() {
        launch()
        open("AgentChat")
        send()
        idleUntil("meta 已到") { controller().streamStates.single().runId != null }
        composeRule.runOnUiThread {
            val run = controller().snapshot.value.stop!!
            controller().stop(run)
            controller().stop(run)
        }
        idleUntil("cancel 已发") { count("/v1/agent/runs/") == 1 }
        Thread.sleep(SLOW_MS)
        settle()
        assertEquals(1, count("/v1/agent/runs/"))
        assertEquals(AgentStatus.CANCELLED, controller().streamStates.single().status)
        assertNull(requests.first { it.path.startsWith("/v1/agent/runs/") }.body.takeIf { it.isNotEmpty() })
    }

    // ---------------------------------------------------------------- AC-S1-174 在真实流上

    @Test
    fun byteChunkedUtf8AndLimitsWorkOnTheRealStream() {
        val text = "中文😀混排，emoji 🧪 也对"
        streamAnswer =
            {
                sse(
                    frame("meta", 1, META_DATA) + frame("text.delta", 2, """{"seq":2,"delta":"$text"}""") +
                        frame("done", 3, """{"finish_reason":"stop","quota_left":5}"""),
                    chunk = 3,
                )
            }
        launch()
        open("AgentChat")
        send()
        idleUntil("流结束") { controller().streamStates.single().status == AgentStatus.DONE }
        assertEquals(text, controller().streamStates.single().text)
        // ⑤ 单帧 70 KiB → limited（不更新额度）
        streamAnswer = { sse(frame("meta", 1, META_DATA) + frame("text.delta", 2, """{"seq":2,"delta":"${"x".repeat(LARGE)}"}""")) }
        send("第二条")
        idleUntil("70 KiB limited") { controller().streamStates.last().status == AgentStatus.LIMITED }
        // ⑥ 401 条 1 字 text.delta → 第 401 帧起不渲染、limited
        streamAnswer = {
            val deltas = (2..402).joinToString("") { frame("text.delta", it, """{"seq":$it,"delta":"字"}""") }
            sse(frame("meta", 1, META_DATA) + deltas + frame("done", 403, """{"finish_reason":"stop","quota_left":5}"""))
        }
        send("第三条")
        idleUntil("401 帧 limited") { controller().streamStates.last().status == AgentStatus.LIMITED }
        assertEquals(
            400,
            controller()
                .streamStates
                .last()
                .text.length,
        )
        assertEquals(5L, controller().snapshot.value.quotaLeft)
    }

    // ---------------------------------------------------------------- Release 开放与入口开关（G32、G56）

    @Test
    fun entryHiddenSwapsTheTabForSearchShowsUnavailableAndSendsNoAgentRequest() {
        config.value =
            JsonObject(mapOf("features" to JsonObject(mapOf("flags" to JsonObject(mapOf("agent.entry.visible" to JsonPrimitive(false)))))))
        launch()
        open("AgentChat")
        Thread.sleep(SLOW_MS)
        settle()
        assertEquals(0, count("/v1/agent/"))
        assertEquals(AgentPhase.UNAVAILABLE, controller().currentPhase)
        assertFalse(agent.askAvailable())
        assertFalse(agent.withdrawAvailable())
        assertTrue(tree().contains("搜索"))
        // 开关打开：立即重建并按正常进入取当前会话
        config.value = null
        settle()
        open("AgentChat")
        idleUntil("恢复后取会话") { count("/v1/agent/sessions/current") == 1 }
    }

    /**
     * R1-04（编排者补充 2、本轮裁定第 1 条，共性第 39 条）：`agent.entry.visible=false` → 商品详情、订单详情都不画【问问 AI】（不回退成直接打开 AgentChat），
     * 0 个 agent 请求；配置运行中改成可见 → 已打开的详情页立即画出入口；再关掉 → 立即消失。
     */
    @Test
    fun entryHiddenDetailPagesDrawNoAskAiFollowConfigAndSendNoAgentRequest() {
        config.value = HIDDEN
        launch(withDetails = true)
        openWith("ProductDetail", mapOf("platform" to "taobao", "product_key" to "tb:1"))
        idleUntil("商品详情已加载") { tagged(com.couli.feature.product.ProductTags.BUY) }
        assertFalse("入口关闭：商品详情不画【问问 AI】", tagged(com.couli.feature.product.ProductTags.ASK_AI))
        openWith("OrderDetail", mapOf("order_id" to "ord-1"))
        idleUntil("订单详情已加载") { tagged(com.couli.feature.orders.OrderTags.DETAIL_AMOUNTS) }
        assertFalse("入口关闭：订单详情不画【问问 AI】", tagged(com.couli.feature.orders.OrderTags.ASK_AI))
        assertEquals(0, count("/v1/agent/"))
        // 运行中打开：订单详情（当前页）立即画出入口
        config.value = null
        idleUntil("入口打开后订单详情画出") { tagged(com.couli.feature.orders.OrderTags.ASK_AI) }
        // 再关掉：立即消失，商品详情（回退栈里）回到前台也没有
        config.value = HIDDEN
        idleUntil("入口关闭后订单详情消失") { !tagged(com.couli.feature.orders.OrderTags.ASK_AI) }
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        idleUntil("回到商品详情") { tagged(com.couli.feature.product.ProductTags.BUY) }
        assertFalse(tagged(com.couli.feature.product.ProductTags.ASK_AI))
        assertEquals(0, count("/v1/agent/"))
    }

    @Test
    fun entryVisibleProductDetailDrawsAskAiAndRemovesItWhenConfigTurnsOff() {
        launch(withDetails = true)
        openWith("ProductDetail", mapOf("platform" to "taobao", "product_key" to "tb:1"))
        idleUntil("商品详情已加载") { tagged(com.couli.feature.product.ProductTags.BUY) }
        assertTrue(tagged(com.couli.feature.product.ProductTags.ASK_AI))
        // 运行中关掉：已打开的商品详情立即不画
        config.value = HIDDEN
        idleUntil("入口关闭后商品详情消失") { !tagged(com.couli.feature.product.ProductTags.ASK_AI) }
        assertEquals(0, count("/v1/agent/"))
    }

    @Test
    fun agentDisabledShowsUnavailableAndWithdrawRowHidden() {
        config.value = JsonObject(mapOf("agent" to JsonObject(mapOf("enabled" to JsonPrimitive(false)))))
        launch()
        open("AgentChat")
        Thread.sleep(SLOW_MS)
        settle()
        assertEquals(0, count("/v1/agent/"))
        assertEquals(AgentPhase.UNAVAILABLE, controller().currentPhase)
        assertFalse(agent.withdrawAvailable())
        assertTrue(
            composeRule
                .onAllNodesWithTag(
                    com.couli.feature.agent.AgentTags.UNAVAILABLE,
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
                .isNotEmpty(),
        )
    }

    @Test
    fun historyEarningsCardRendersHistoryShapeWithoutAmountsOrWalletRequests() {
        currentAnswer = { envelope("""{"session":{"session_id":"s-H","started_at":"$NOW","last_active_at":"$NOW"}}""") }
        historyAnswer = {
            envelope(
                """{"items":[{"message_id":"019a0000-0000-7000-8000-000000000551","role":"user",""" +
                    """"created_at":"$NOW","text":"我现在有多少收益","cards":[],"finish_reason":null},""" +
                    """{"message_id":"019a0000-0000-7000-8000-000000000552","role":"assistant",""" +
                    """"created_at":"$NOW","text":"你的收益情况见下方卡片，以钱包页为准。","cards":[""" +
                    """{"card_id":"c2","type":"earnings_summary","schema_version":1,""" +
                    """"data":{"as_of":"2026-10-14T10:01:03+08:00","withdrawable_fen":1000,""" +
                    """"estimated_total_fen":5678,""" +
                    """"next_credit_period":"2026-11","credit_overdue":false,""" +
                    """"latest_withdrawal":{"title_key":"withdrawal_status.PENDING_REVIEW.label",""" +
                    """"amount_fen":2000},""" +
                    """"actions":[{"route":"Wallet","text_key":"agent.earnings.open_wallet"},""" +
                    """{"route":"WithdrawRecords","text_key":"agent.earnings.open_records"}]},""" +
                    """"fallback_text":"请到钱包页查看收益与提现进度。"}],"finish_reason":"stop"}],""" +
                    """"next_cursor":null,"expired":false}""",
            )
        }
        launch()
        open("AgentChat")
        idleUntil("历史恢复") {
            controller()
                .snapshot.value.turns
                .isNotEmpty()
        }
        assertTrue(
            hasText(
                com.couli.core.agent.AgentDraftTexts
                    .text("agent.earnings.history"),
            ),
        )
        assertFalse(hasText("10.00"))
        assertFalse(hasText("56.78"))
        assertFalse(hasText("20.00"))
        assertEquals(0, count("/v1/wallet"))
        assertEquals(1, count("/v1/agent/sessions/s-H/messages"))
        assertTrue(
            requests
                .first { it.path.endsWith("/messages") }
                .query
                .orEmpty()
                .contains("limit=20"),
        )
    }

    // ---------------------------------------------------------------- 应用级：身份变更在途、AI 同意失效、闸门保留（共性第 12、28、33、35 条）

    @Test
    fun identityChangeInFlightBlocksRebuildAndAdmissionUntilLogoutSettles() {
        launch()
        open("AgentChat")
        send()
        idleUntil("meta 已到") { controller().streamStates.single().runId != null }
        logoutHold = CountDownLatch(1)
        composeRule.runOnUiThread { me.logout(me.identity.value) { true } }
        idleUntil("logout 在途") { index("/v1/auth/logout") >= 0 }
        assertTrue(agent.identityChange.active)
        assertFalse(agent.requestsAllowed())
        val before = count("/v1/agent/sessions/current")
        open("AgentChat")
        settle()
        // 在途期间不重建可用的控制器、不准入 current / 历史 / 发消息（页面只显示空白的挂起态）
        assertTrue(
            agent.controller.value
                ?.currentPhase
                .let { it == null || it == AgentPhase.BLOCKED },
        )
        assertEquals(before, count("/v1/agent/sessions/current"))
        logoutHold!!.countDown()
        idleUntil("退出落定后重建") { !agent.identityChange.active && agent.controller.value != null }
        open("AgentChat")
        idleUntil("游客取会话") { requests.count { it.path == "/v1/agent/sessions/current" && it.auth == null } >= 1 }
    }

    @Test
    fun aiConsentLapseFromFeedbackSurvivesPageRebuildUntilReconsent() {
        streamAnswer =
            {
                sse(
                    frame("meta", 1, META_DATA) + frame("text.delta", 2, """{"seq":2,"delta":"好的"}""") +
                        frame("done", 3, """{"finish_reason":"stop","quota_left":5}"""),
                )
            }
        launch()
        open("AgentChat")
        send()
        idleUntil("回复结束") {
            controller()
                .snapshot.value.turns
                .singleOrNull()
                ?.actions != null
        }
        feedbackAnswer = { envelope("""{"consent_type":"ai_third_party"}""", 10004, 403) }
        composeRule.runOnUiThread {
            controller().feedback(
                controller()
                    .snapshot.value.turns
                    .single()
                    .actions!!
                    .ticket,
                "up",
            )
        }
        idleUntil("同意失效") { agent.consentLapse.blocks(agent.identity().ownerKey) }
        // 同归属的页面实例重建：一开始就是「需要重新同意」，0 次取会话 / 历史
        val before = count("/v1/agent/sessions/current")
        composeRule.runOnUiThread { agent.onPageDestroyed() }
        open("AgentChat")
        settle()
        assertEquals(AgentPhase.CONSENT_DECLINED, controller().currentPhase)
        assertEquals(before, count("/v1/agent/sessions/current"))
        val sends = requests.count { it.method == "POST" && it.path.endsWith("/messages") }
        composeRule.runOnUiThread { assertFalse(controller().send(controller().snapshot.value.page, "再发")) }
        settle()
        assertEquals(sends, requests.count { it.method == "POST" && it.path.endsWith("/messages") })
        // 只有同一归属重新同意记录成功才解除
        composeRule.runOnUiThread { controller().consentReopen(controller().snapshot.value.page) }
        settle()
        composeRule.runOnUiThread { controller().consentAgree(controller().snapshot.value.consent!!) }
        idleUntil("重新同意成功") { !agent.consentLapse.blocks(agent.identity().ownerKey) }
        assertTrue(agent.requestsAllowed())
    }

    @Test
    fun readGateSurvivesSamePageRebuildAndClearsOnOwnerChange() {
        currentAnswer = { envelope("null", 42901, 429).setHeader("Retry-After", "12") }
        launch()
        open("AgentChat")
        idleUntil("42901") { count("/v1/agent/sessions/current") == 1 && agent.gates.read.active }
        composeRule.runOnUiThread { agent.onPageDestroyed() }
        open("AgentChat")
        settle()
        assertEquals(1, count("/v1/agent/sessions/current"))
        assertTrue(agent.gates.read.remainingMillis() > 0)
        // 归属改变（换号）：清掉旧闸门，按新归属取会话
        currentAnswer = { envelope("""{"session":null}""") }
        composeRule.runOnUiThread {
            tokens.clear()
            routing.updateSession(SessionState.GUEST, resumePending = false)
        }
        open("AgentChat")
        idleUntil("新归属取会话") { count("/v1/agent/sessions/current") == 2 }
        // FU-AND-0716 窄修二：请求到了假服务不等于页面已处理应答；等这次会话读取有结论（离开 LOADING）再核对
        idleUntil("新归属的会话读取结算") { controller().currentPhase != AgentPhase.LOADING }
        assertFalse(agent.gates.read.active)
        assertEquals(AgentPhase.READY, controller().currentPhase)
    }

    // ---------------------------------------------------------------- 第 2 轮修复（android-review-r2）

    /** R2-02：已登录身份 meta 前停止 → HTTP JSON 10001：公共管线结算原登录世代的会话失效、同步通知身份变化、停止所属 Agent 工作；已停止的这一轮不导航、不弹层。 */
    @Test
    fun fixR2StopBeforeMetaThenHttp10001EndsTheOriginalLoginGenerationWithoutNavigating() {
        val release = CountDownLatch(1)
        streamAnswer = {
            release.await(WAIT_S, TimeUnit.SECONDS)
            envelope("null", 10001, 401)
        }
        launch()
        open("AgentChat")
        send()
        idleUntil("流请求已到") { requests.any { it.method == "POST" && it.path.endsWith("/messages") } }
        val a = controller()
        composeRule.runOnUiThread { a.stop(a.snapshot.value.stop!!) }
        settle()
        assertEquals(
            com.couli.core.agent.AgentDraftTexts
                .text("agent.draft.stopping"),
            a.snapshot.value.turns
                .single()
                .statusText,
        )
        val routesBefore = backStackRoutes()
        val sentBefore = requests.size
        release.countDown()
        idleUntil("原世代会话失效") { tokens.accessToken == null }
        settle()
        assertEquals(SessionState.GUEST, routing.session.value)
        assertTrue(a.isDisposed)
        assertFalse(a.canSend)
        // 不导航、不弹层；meta 之前没有 run_id，不发 cancel
        assertEquals(routesBefore, backStackRoutes())
        assertFalse(backStackRoutes().contains("Login"))
        assertEquals(0, count("/v1/agent/runs/"))
        // 新的控制器按游客身份重建，不带原令牌
        val b = agent.controller.value
        assertTrue(b == null || b !== a)
        assertTrue(requests.drop(sentBefore).none { it.auth == "Bearer access-$USER_A" })
    }

    /**
     * R2-03：cancel 的连接建立超过 2 秒 → 等写出超时 → 流程转入自身请求之前原子撤销尚未写出的 cancel 准入并取消它的网络调用：之后连接建立好也不再写出，
     * 服务端收不到晚于自身请求的 cancel。[begin] 发起身份事件，[ownRequest] 是该流程自己的请求路径（服务端挂住，原身份与世代此刻未变）。
     */
    private fun lateCancelIsRevokedBeforeTheOwnRequest(
        ownRequest: String,
        begin: () -> Unit,
    ) {
        launch()
        open("AgentChat")
        send()
        idleUntil("meta 已到") { controller().streamStates.single().runId != null }
        cancelConnectDelayMs = CONNECT_DELAY_MS
        composeRule.runOnUiThread { begin() }
        settle()
        assertEquals(1, agent.ownershipCancels)
        assertEquals(-1, index(ownRequest))
        // 等写出的上限（2 秒，主线程虚拟时钟）到：撤销准入后继续自身请求
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_100))
        idleUntil("自身请求已发") { index(ownRequest) >= 0 }
        // 连接建立好之后（真实时间）：已撤销的 cancel 不写出
        Thread.sleep(CONNECT_DELAY_MS + SLOW_MS)
        settle()
        assertEquals("迟到的 cancel 不得写出：${requests.toList()}", 0, count("/v1/agent/runs/"))
    }

    @Test
    fun fixR2LogoutCancelConnectingOverTwoSecondsIsRevokedAndNeverWrittenAfterLogout() {
        logoutHold = CountDownLatch(1)
        try {
            lateCancelIsRevokedBeforeTheOwnRequest("/v1/auth/logout") { me.logout(me.identity.value) { true } }
        } finally {
            logoutHold?.countDown()
        }
        settle()
        // FU-AND-0716 窄修六：放行后 logout 应答还在后台；等身份变更收尾（迟到的 cancel 若会写出，只能在这之前）再核对
        idleUntil("身份变更收尾") { !agent.identityChange.active }
        assertEquals(0, count("/v1/agent/runs/"))
    }

    @Test
    fun fixR2AiConsentWithdrawCancelConnectingOverTwoSecondsIsRevokedAndNeverWrittenAfterRecordConsent() {
        consentsHold = CountDownLatch(1)
        try {
            lateCancelIsRevokedBeforeTheOwnRequest(
                "/v1/consents",
            ) { agent.withdrawConsent(agent.openWithdrawConfirm { true }!!, { true }) {} }
        } finally {
            consentsHold?.countDown()
        }
        settle()
        // FU-AND-0716 窄修六：放行后同意记录应答还在后台；等身份变更收尾再核对
        idleUntil("身份变更收尾") { !agent.identityChange.active }
        assertEquals(0, count("/v1/agent/runs/"))
    }

    @Test
    fun fixR2PrivacyWithdrawCancelConnectingOverTwoSecondsIsRevokedAndNeverWrittenAfterTheDeviceRecord() {
        consentsHold = CountDownLatch(1)
        try {
            lateCancelIsRevokedBeforeTheOwnRequest("/v1/consents") { me.withdrawConsent(me.identity.value) }
        } finally {
            consentsHold?.countDown()
        }
        settle()
        // FU-AND-0716 窄修六：放行后同意记录应答还在后台；等身份变更收尾再核对
        idleUntil("身份变更收尾") { !agent.identityChange.active }
        assertEquals(0, count("/v1/agent/runs/"))
    }

    private companion object {
        const val USER_A = "user-a"
        const val USER_B = "user-b"
        const val NOW = "2026-10-14T10:00:00+08:00"
        const val META_DATA =
            """{"session_id":"019a0000-0000-7000-8000-000000000001","run_id":"019a0000-0000-7000-8000-000000000121",""" +
                """"message_id":"019a0000-0000-7000-8000-000000000221","prompt_version":"demo-prompt-v1",""" +
                """"model_label":"demo","ai_label":"内容由 AI 生成，仅供参考"}"""
        const val CARD_DATA =
            """{"seq":2,"card_id":"c1","type":"page_guide","schema_version":1,""" +
                """"data":{"route":"WithdrawRecords","text_key":"agent.guide.WithdrawRecords"},""" +
                """"fallback_text":"提现进度在提现记录里查看。"}"""
        val HIDDEN =
            JsonObject(
                mapOf(
                    "features" to JsonObject(mapOf("flags" to JsonObject(mapOf("agent.entry.visible" to JsonPrimitive(false))))),
                ),
            )
        const val DETAIL_CONFIG = """{"features":{"flags":{},"platform_status":{"taobao":"on"}}}"""
        const val PRODUCT_CARD =
            """{"card_id":"c1","product_key":"tb:1","platform":"taobao","title":"milk","image":null,""" +
                """"price_fen":5678,"coupon_fen":1000,"final_price_fen":4678,"est_net_price_fen":4555,"rebate_min_fen":123,""" +
                """"rebate_max_fen":234,"rebate_basis":"normal","is_presale":false,""" +
                """"link_id":"link-1","cta":{"text_key":"btn.buy.coupon"},""" +
                """"stale":false,"age_sec":12,"quoted_at":"2026-10-02T09:30:00+08:00",""" +
                """"disclaimer_keys":["price_basis","rebate_estimate"],"availability":"ok"}"""
        const val ORDER_DETAIL =
            """{"order_id":"ord-1","platform":"taobao","title":"样例商品 A","image_url":null,"pay_amount_fen":4560,"quantity":2,""" +
                """"order_no":"3741234567890123456","display_status":"PAID","reason":null,"est_rebate_fen":234,""" +
                """"paid_at":"2026-10-08T09:05:00+08:00","is_other_product":false,"product_key":"tb:sampleA","reason_action":[],""" +
                """"timeline":[{"node":"paid","at":"2026-10-08T09:05:00+08:00","period":null},""" +
                """{"node":"received","at":null,"period":null},""" +
                """{"node":"credit_expected","at":null,"period":null},{"node":"credited","at":null,"period":null}],""" +
                """"expected_credit_period":null,"credit_overdue":false,"actual_fen":null,"clawback_fen":null,"appeal_pending":false,""" +
                """"is_price_compare":false}"""
        const val ROUNDS = 3
        const val POLL_MS = 20L
        const val WAIT_S = 15L
        const val THROTTLE_MS = 3_000L
        const val SLOW_MS = 600L
        const val CONNECT_DELAY_MS = 3_000L
        const val LARGE = 70 * 1024
    }
}
