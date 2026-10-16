package com.couli.app.home

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.privacy.AppPrivacy
import com.couli.app.routing.AppRouting
import com.couli.app.routing.RouteDestination
import com.couli.app.routing.RoutingPrivacyHooks
import com.couli.app.routing.UPGRADE_PAGE_TAG
import com.couli.app.shell.AppShell
import com.couli.app.shell.BASIC_MODE_PAGE_TAG
import com.couli.app.shell.HOME_ASK_AI_TAG
import com.couli.core.clientcore.ClientEvent
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.LocalProductImageLoader
import com.couli.core.designsystem.ProductCardTexts
import com.couli.core.designsystem.ProductImageLoader
import com.couli.core.home.HomeCall
import com.couli.core.home.HomeContractPort
import com.couli.core.home.HomeDraftTexts
import com.couli.core.home.HomeLevel
import com.couli.core.home.HomeLkgScope
import com.couli.core.home.HomeLkgStore
import com.couli.core.home.HomePrefsStore
import com.couli.core.home.NoticeFilter
import com.couli.core.home.SharedPreferencesPendingFlag
import com.couli.core.network.ApiClient
import com.couli.core.network.ApiResult
import com.couli.core.network.ClientIdentity
import com.couli.core.network.DeviceCredentials
import com.couli.core.network.ErrorEffect
import com.couli.core.network.ErrorEffectKind
import com.couli.core.network.InFlightCalls
import com.couli.core.network.LocalRejectReason
import com.couli.core.network.MemoryPendingKeyBackend
import com.couli.core.network.MemorySessionTokens
import com.couli.core.network.NetworkContext
import com.couli.core.network.NetworkHooks
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PrivacyRaw
import com.couli.core.network.ReplayMode
import com.couli.core.network.UpdateRaw
import com.couli.core.privacy.CredentialCleanerRegistry
import com.couli.core.privacy.PrivacyGate
import com.couli.core.privacy.PrivacyStore
import com.couli.core.privacy.RetryScheduler
import com.couli.core.router.PrivacyState
import com.couli.core.router.RouteSource
import com.couli.core.router.SessionState
import com.couli.core.router.UpdateCheck
import com.couli.feature.home.HomeTags
import com.couli.feature.home.HomeTimers
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import java.net.URLDecoder
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
 * 首页 SDUI 的本地假服务集成测试（APP-11 brief §2.19；共性：APP-11 第 1–22 条，APP-12 第 28、31、33、34 条，APP-09 第 39、63、65 条，APP-08 第 28 条）：
 * 真实 AppShell + NavHost + AppRouting + AppPrivacy + AppHome + 首页控制器与渲染器 + ApiClient（listArticles 的传输层换成 MockWebServer）；
 * 待定 operation 由替身端口回答；
 * 计时与时钟由测试推进。页面经 Router 打开、经页面控件操作；商品、价格、返利数字照 Main 稿或明显的测试值。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("LargeClass", "TooManyFunctions", "LongMethod")
class AppHomeIntegrationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val noon = OffsetDateTime.parse("2026-10-10T12:00:00+08:00").toInstant().toEpochMilli()

    @Volatile private var clock = noon
    private lateinit var server: MockWebServer
    private lateinit var routing: AppRouting
    private lateinit var navController: NavHostController
    private lateinit var privacy: AppPrivacy
    private lateinit var tokens: MemorySessionTokens

    /**
     * 会话读取可以单次失败的令牌存储（FU-AND-0717 第 3 轮 R2-06）：包住 [tokens]，[failReads] 里的读取序号（从 1 起）读不出（SessionRead.Unreadable），其余照读。
     * 没给时直接用 [tokens]。
     */
    private class FlakyTokens(
        private val inner: MemorySessionTokens,
    ) : com.couli.core.network.SessionTokens by inner,
        com.couli.core.network.PersistedLoginEpochs by inner,
        com.couli.core.network.SessionReader {
        @Volatile var reads = 0
        val failReads = mutableSetOf<Int>()

        override fun readSession(): com.couli.core.network.SessionRead {
            reads += 1
            return if (reads in failReads) {
                com.couli.core.network.SessionRead
                    .Unreadable(inner.generation)
            } else {
                com.couli.core.network.SessionRead
                    .Present(inner.snapshot())
            }
        }
    }

    private var flaky: FlakyTokens? = null

    /** 下一次 [launch] 用 [FlakyTokens] 包住令牌存储。 */
    private var flakyReads = false
    private lateinit var api: ApiClient
    lateinit var home: AppHome
    private lateinit var gate: PrivacyGate
    private lateinit var cleaners: CredentialCleanerRegistry
    private lateinit var lkgRoot: File
    private lateinit var lkg: HomeLkgStore
    private lateinit var prefs: HomePrefsStore
    private val inFlight = InFlightCalls()
    private val timers = Timers()
    private val events = mutableListOf<ClientEvent>()
    private val requests = mutableListOf<RecordedRequest>()
    private val articleReplies = mutableListOf<() -> MockResponse>()
    private val port = Port()
    private val images = Images()
    private var forced = 0
    private var bundled: () -> String? = {
        com.couli.core.clientcore.BundledHome
            .pageJson()
    }
    private var blockerOn = false

    /** 撤回清理执行到首页 LKG 那一项时，正式首页控制器是否还有效（R1-01：必须已作废）。 */
    private val aliveAtLkgClear = mutableListOf<Boolean?>()
    private var previewReply: () -> MockResponse = { envelope(PREVIEW_DRAFT) }
    private var http: okhttp3.OkHttpClient = okhttp3.OkHttpClient()

    // ---------------------------------------------------------------- 替身

    /** 手动推进的计时。 */
    private inner class Timers : HomeTimers {
        private val pending = mutableListOf<Triple<Long, () -> Unit, BooleanArray>>()

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

    /** 待定 operation（G02、G03）：按账号回答；可挂起在途；记录是否匿名与请求参数。 */
    private inner class Port : HomeContractPort {
        val pageCalls = mutableListOf<HomeCall>()
        val itemCalls = mutableListOf<String>()
        var page: (HomeCall) -> ApiResult = { ok(DEFAULT_TEST_PAGE) }
        var hold: CompletableDeferred<Unit>? = null
        var items: (String, String?) -> ApiResult = { section, _ -> itemsOf(section) }

        override suspend fun getPage(
            pageKey: String,
            call: HomeCall,
        ): ApiResult {
            if (!call.allowed()) return ApiResult.LocalReject(LocalRejectReason.CALL_CANCELLED)
            pageCalls += call
            val reply = page(call)
            hold?.await()
            return reply
        }

        override suspend fun listSectionItems(
            pageKey: String,
            sectionId: String,
            cursor: String?,
            call: HomeCall,
        ): ApiResult {
            if (!call.allowed()) return ApiResult.LocalReject(LocalRejectReason.CALL_CANCELLED)
            itemCalls += "$sectionId|${cursor.orEmpty()}"
            return items(sectionId, cursor)
        }
    }

    /** 图片（不连网）：可挂起、可失败。 */
    private inner class Images : ProductImageLoader {
        var fail = false
        var hold: CompletableDeferred<Unit>? = null
        var loads = 0

        override suspend fun load(url: String): ImageBitmap? {
            loads += 1
            hold?.await()
            if (fail || !url.startsWith("https://")) return null
            return Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    }

    private class MemoryStore(
        decision: String,
    ) : PrivacyStore {
        override var decisionRaw: String = decision
        override var consentedVersion: String? = "3"
        override var consentedAtMillis: Long? = 0L
        override var basicMode: Boolean = decision == "basic_mode"
        override var withdrawalCleanupPending: Boolean = false
    }

    private object Device : DeviceCredentials {
        override val deviceId: String = "device-fixture-ABC123"
        override val installSecret: String = "install-fixture"
    }

    @Before
    fun setUp() {
        listOf(HomePrefsStore.NAME, PENDING_NAME).forEach {
            context
                .getSharedPreferences(it, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
        lkgRoot = File(context.noBackupFilesDir, "home-lkg-${System.nanoTime()}")
        lkg = HomeLkgStore(lkgRoot, SharedPreferencesPendingFlag(context, PENDING_NAME))
        prefs = HomePrefsStore(context)
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    synchronized(requests) { requests += request }
                    val path = request.path.orEmpty().substringBefore('?')
                    return when {
                        path == "/v1/articles" -> {
                            synchronized(articleReplies) { articleReplies.removeFirstOrNull() }?.invoke()
                                ?: envelope(NOTICES)
                        }

                        path.endsWith("/preview") -> {
                            previewReply()
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

    // ---------------------------------------------------------------- 搭建（与 AppModule 同一套接线）

    private fun launch(
        user: String? = null,
        decision: String = "consented",
        lkgStore: HomeLkgStore = lkg,
        appVersion: String = "1.10.0",
        io: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Main.immediate,
    ) {
        gate =
            PrivacyGate(MemoryStore(decision), clock = { clock }, bundledPolicyVersion = "3").apply {
                retryScheduler = RetryScheduler { _, _ -> }
            }
        privacy = AppPrivacy(gate, environmentSettled = { true }, clock = { clock }).also { it.coldStart() }
        val environment = EnvironmentGate { EnvironmentStartupReconciler.Outcome.UNCHANGED }.also { it.settleAtStartup() }
        tokens = if (user == null) MemorySessionTokens() else MemorySessionTokens("access-$user", "refresh-$user", "full", user)
        if (flakyReads) flaky = FlakyTokens(tokens)
        val sessionTokens: com.couli.core.network.SessionTokens = flaky ?: tokens
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
        val main = android.os.Handler(Looper.getMainLooper())
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
                tokens = sessionTokens,
                pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { clock },
                hooks = NetworkHooks(onSessionStored = { main.post { if (::home.isInitialized) home.sync() } }),
                inFlight = inFlight,
                sleeper = {},
                http = http,
            )
        composeRule.runOnUiThread {
            routing.updateSession(if (user == null) SessionState.GUEST else SessionState.PHONE_BOUND, resumePending = false)
            home =
                AppHome(
                    client = api,
                    tokens = sessionTokens,
                    routing = routing,
                    privacy = privacy,
                    port = port,
                    lkg = lkgStore,
                    prefs = prefs,
                    images = images,
                    envKey = { ENV },
                    configData = { config },
                    track = { events += it },
                    serverForced = { forced += 1 },
                    cardTexts = CARD_TEXTS,
                    appVersion = appVersion,
                    clock = { clock },
                    timers = timers,
                    bundled = { bundled() },
                    io = io,
                ).also { it.connect() }
            home.addBlocker("update_prompt", { blockerOn })
            cleaners =
                CredentialCleanerRegistry().apply {
                    register("in_flight_calls") { inFlight.onPrivacyRevoked() }
                    register(CredentialCleanerRegistry.TOKENS) { tokens.clear() }
                    register(CredentialCleanerRegistry.DEVICE_ID) {}
                    register(CredentialCleanerRegistry.INSTALL_SECRET) {}
                    register(AppHome.HOME_CONTROLLER_CLEANER) { home.onConsentWithdrawing() }
                    register(AppHome.HOME_LKG_CLEANER) {
                        aliveAtLkgClear += home.controller.value?.alive
                        lkgStore.clearAll()
                    }
                    register(AppHome.HOME_PREFS_CLEANER) { prefs.clearAll() }
                }
            gate.withdrawalCleanup = { cleaners.cleanAll() }
        }
        composeRule.setContent { Shell() }
        settle()
    }

    @Composable
    private fun Shell() {
        navController = rememberNavController()
        androidx.compose.runtime.CompositionLocalProvider(LocalProductImageLoader provides images) {
            CouliTheme {
                AppShell(
                    routing = routing,
                    privacy = privacy,
                    homeContent = { HomeRoute(home) },
                    navController = navController,
                    home = home,
                )
            }
        }
    }

    private var config: JsonObject = JsonObject(emptyMap())

    // ---------------------------------------------------------------- AC-HOME-04：三级兜底

    @Test
    fun noLkgAnd500ShowsBundledDefaultAfter1500WithoutErrorPage() {
        port.page = { failure(50001) }
        launch()
        assertFalse(has(HomeTags.tap("default_icons", 1)))
        advance(1_499)
        assertFalse(has(HomeTags.tap("default_icons", 1)))
        advance(1)
        assertTrue(has(HomeTags.tap("default_icons", 1)))
        assertFalse(has(HomeTags.LOAD_FAILED))
        assertFalse(has(HomeTags.TOAST))
        assertEquals("1 (default)", home.currentVersion())
    }

    @Test
    fun noLkgNetworkWithin1500ShowsNetworkAndWritesLkg() {
        launch()
        assertTrue(has(HomeTags.tap("act", 1)))
        assertNotNull(lkg.read(scope(HomeLevel.GUEST)))
        advance(1_500)
        assertTrue(has(HomeTags.tap("act", 1)))
        assertEquals("21 (network)", home.currentVersion())
    }

    @Test
    fun lateNetworkReplacesDefaultOnceUnlessUserScrolled() {
        port.hold = CompletableDeferred()
        launch()
        advance(1_500)
        assertTrue(has(HomeTags.tap("default_icons", 1)))
        // 网络 2 秒到达、用户未交互 → 替换一次
        clock += 500
        port.hold!!.complete(Unit)
        settle()
        assertTrue(has(HomeTags.tap("act", 1)))
        assertFalse(has(HomeTags.tap("default_icons", 1)))
    }

    @Test
    fun lateNetworkAfterScrollOnlyWritesLkgAndNextColdStartShowsIt() {
        port.hold = CompletableDeferred()
        launch()
        advance(1_500)
        // 用户点过首页（交互）后再返回
        click(HomeTags.tap("default_icons", 4))
        back()
        port.hold!!.complete(Unit)
        settle()
        assertTrue(has(HomeTags.tap("default_icons", 1)))
        assertFalse(has(HomeTags.tap("act", 1)))
        assertNotNull(lkg.read(scope(HomeLevel.GUEST)))
        // 下次冷启动（新身份控制器）立即显示 LKG
        port.page = { failure(50001) }
        restart()
        assertTrue(has(HomeTags.tap("act", 1)))
    }

    @Test
    fun lkgShownImmediatelyNetworkOnlyWritesAndPullReplacesOr500KeepsScreenWithTrace6() {
        lkg.write(lkg.ticket(scope(HomeLevel.GUEST)), page(5, activity("lkg_act")).toString())
        port.page = { ok(page(6, activity("v6_act"))) }
        launch()
        // AC-HOME-01：运行中不换屏，网络版只写 LKG
        assertTrue(has(HomeTags.tap("lkg_act", 1)))
        assertFalse(has(HomeTags.tap("v6_act", 1)))
        assertTrue(lkg.read(scope(HomeLevel.GUEST))!!.contains("v6_act"))
        // 下拉刷新 → 替换
        port.page = { ok(page(7, activity("v7_act"))) }
        refresh()
        assertTrue(has(HomeTags.tap("v7_act", 1)))
        // 下拉 500 → 保留当前屏，提示带 trace6
        port.page = { failure(50001, trace = "trace-test-ABC123") }
        refresh()
        assertTrue(has(HomeTags.tap("v7_act", 1)))
        assertTrue(text("ABC123") > 0)
    }

    @Test
    fun coldStartAfterNewVersionShowsItOnRestart() {
        lkg.write(lkg.ticket(scope(HomeLevel.GUEST)), page(5, activity("lkg_act")).toString())
        port.page = { ok(page(6, activity("v6_act"))) }
        launch()
        assertTrue(has(HomeTags.tap("lkg_act", 1)))
        restart()
        assertTrue(has(HomeTags.tap("v6_act", 1)))
    }

    @Test
    fun corruptLkgIsTreatedAsNoLkg() {
        lkg.write(lkg.ticket(scope(HomeLevel.GUEST)), "{not json")
        port.page = { failure(50001) }
        launch()
        assertFalse(has(HomeTags.tap("default_icons", 1)))
        advance(1_500)
        assertTrue(has(HomeTags.tap("default_icons", 1)))
        assertNull(lkg.read(scope(HomeLevel.GUEST)))
    }

    @Test
    fun badBundledDefaultAndOfflineShowsLoadFailedWithRetry() {
        bundled = { "{\"version\":0}" }
        port.page = { ApiResult.TransportFailure("disconnect", null, null, false) }
        launch()
        advance(1_500)
        assertTrue(has(HomeTags.LOAD_FAILED))
        // 【重试】：网络恢复后按同一身份重新走三级兜底
        port.page = { ok(DEFAULT_TEST_PAGE) }
        clickText(HomeDraftTexts.text(HomeDraftTexts.RETRY))
        assertTrue(has(HomeTags.tap("act", 1)))
    }

    // ---------------------------------------------------------------- AC-HOME-05 / 06：解码隔离与按端门槛

    @Test
    fun mixedBadPageKeepsGoodCardsAndReportsOnlyDroppedOnce() {
        port.page = { ok(golden("mixed_bad")) }
        launch()
        assertTrue(has(HomeTags.tap("good_a", 1)))
        assertTrue(has(HomeTags.tap("good_b", 1)))
        val errors = events.filter { it.name == "sdui_card_error" }
        assertEquals(
            setOf("missing_version", "unsupported_version", "missing_field", "bad_action", "duplicate_id"),
            errors
                .map {
                    it.fields["reason"]!!.content
                }.toSet(),
        )
        assertEquals(5, errors.size)
        errors.forEach { event ->
            assertTrue(event.fields.keys.all { it in setOf("type", "version", "page_version", "reason") })
            assertFalse(event.fields.values.any { it.content.contains("http") || it.content.contains("测试") })
        }
        // 同一进程内同一（page_version、section id、reason）只发一次
        refresh()
        assertEquals(5, events.count { it.name == "sdui_card_error" })
    }

    @Test
    fun minAppVersionKeepsLowerAndEqualAndConditionIsNotEvaluated() {
        val condition =
            section("cond", "activity_entry", """{"kind":"static","items":[{"title":"测试条件卡","action":{"route":"Help"}}]}""")
                .plus("condition" to Json.parseToJsonElement("""{"login":"yes","has_order":"yes"}"""))
        val sections = golden("min_app_version").getValue("sections") as JsonArray
        port.page = { ok(page(14, *(sections.map { it.jsonObject } + JsonObject(condition)).toTypedArray())) }
        launch()
        assertTrue(has(HomeTags.tap("lower", 1)))
        assertTrue(has(HomeTags.tap("equal", 1)))
        assertFalse(has(HomeTags.tap("higher", 1)))
        // 游客也照常显示带 condition 的卡（不求值）
        assertTrue(has(HomeTags.tap("cond", 1)))
    }

    // ---------------------------------------------------------------- AC-HOME-02：公告条

    @Test
    fun noticeCloseIsRememberedAcrossRestartAndNewContentVersionReappears() {
        port.page = { ok(page(30, notice("nb", "n1", 1, closable = true, end = null))) }
        launch()
        assertTrue(has(close("nb", "n1")))
        assertTrue(spoken(HomeDraftTexts.text(HomeDraftTexts.NOTICE_CLOSE)) > 0)
        click(close("nb", "n1"))
        assertFalse(has(close("nb", "n1")))
        assertTrue(NoticeFilter.closeKey("nb", "n1", 1) in prefs.closedKeys())
        restart()
        assertFalse(has(close("nb", "n1")))
        // 清除缓存（应用缓存目录）不清关闭记录
        context.cacheDir.deleteRecursively()
        port.page = { ok(page(31, notice("nb", "n1", 2, closable = true, end = null))) }
        // 新内容版本：这次冷启动只写 LKG（G16 ④），下次冷启动出现
        restart()
        restart()
        assertTrue(has(close("nb", "n1")))
        // 撤回同意：关闭记录清空
        composeRule.runOnUiThread { privacy.withdraw() }
        settle()
        assertTrue(prefs.closedKeys().isEmpty())
    }

    @Test
    fun unclosableNoticeHidesAtEndWithoutRestartAndEmptyCardHides() {
        val end = "2026-10-10T12:10:00+08:00"
        port.page = { ok(page(32, notice("nb", "n2", 1, closable = false, end = end))) }
        launch()
        assertTrue(text("测试公告 n2") > 0)
        assertFalse(has(close("nb", "n2")))
        advance(9 * 60_000L)
        assertTrue(text("测试公告 n2") > 0)
        advance(60_000L)
        assertEquals(0, text("测试公告 n2"))
        assertFalse(has(HomeTags.CARD_PREFIX + "nb"))
    }

    @Test
    fun apiNoticeUsesListArticlesWithOnlyCategoryAndNoCredentials() {
        port.page = { ok(golden("notice_api")) }
        launch(user = "user-a")
        idleUntil { text("确认收货后，返利随联盟月度结算入账") > 0 }
        val article = synchronized(requests) { requests.single { it.path!!.startsWith("/v1/articles") } }
        assertEquals("category=notice", article.requestUrl!!.encodedQuery)
    }

    // ---------------------------------------------------------------- AC-HOME-03：推广标识、顺序、空数据源

    @Test
    fun adLabelOrderAndEmptySourceOnlyHidesThatCard() {
        val labelled =
            section("scroll", "product_scroll", """{"kind":"product_pool","pool_id":"x"}""", props = """{"title":"今日精选"}""", adLabel = "推广")
        val plain = section("grid", "product_grid", """{"kind":"product_pool","pool_id":"y"}""", props = """{"columns":2,"title":"超市百货"}""")
        val empty = section("empty", "product_grid", """{"kind":"product_pool","pool_id":"z"}""", props = """{"columns":2,"title":"空"}""")
        port.items =
            { sec, _ ->
                if (sec ==
                    "empty"
                ) {
                    ok(JsonObject(mapOf("items" to JsonArray(emptyList()), "next_cursor" to JsonNull)))
                } else {
                    itemsOf(sec)
                }
            }
        port.page = { ok(page(40, JsonObject(labelled), JsonObject(plain), JsonObject(empty), activity("act"))) }
        launch()
        assertTrue(has(HomeTags.CARD_PREFIX + "scroll"))
        idleUntil { has(HomeTags.CARD_PREFIX + "grid") }
        assertFalse(has(HomeTags.CARD_PREFIX + "empty"))
        // 其余卡照常（只隐藏这一张）；按服务端顺序
        assertEquals(
            listOf("scroll", "grid", "act"),
            home.controller.value!!
                .state.value.cards
                .map { it.id },
        )
        // 「推广」只在 ad_label 非 null 的卡上，原样
        val labels = composeRule.onAllNodesWithTag(HomeTags.AD_LABEL, useUnmergedTree = true).fetchSemanticsNodes().size
        assertTrue(labels in 1..3)
        // 服务端顺序：坑位 1、2、3 对应 p0、p1、p2
        click(HomeTags.tap("grid", 2))
        assertEquals("test:p1", lastParams()["product_key"]!!.jsonPrimitive())
        // 请求只带 section id 与游标（不带频道、排序参数）
        assertTrue(port.itemCalls.all { it.matches(Regex("[a-z]+\\|[a-z0-9]*")) })
    }

    // ---------------------------------------------------------------- AC-HOME-08：弹窗

    @Test
    fun popupCloseAppearsWithPopupAndOneTapCloses() {
        port.page = { ok(golden("popup")) }
        launch()
        assertTrue(has(HomeTags.POPUP_CLOSE))
        assertTrue(spoken(HomeDraftTexts.text(HomeDraftTexts.POPUP_CLOSE)) > 0)
        assertEquals(1, prefs.shownToday("popup", clock))
        click(HomeTags.POPUP_CLOSE)
        assertFalse(has(HomeTags.POPUP))
    }

    @Test
    fun popupBackPressClosesWithoutAnyPrecondition() {
        port.page = { ok(golden("popup")) }
        launch()
        assertTrue(has(HomeTags.POPUP))
        composeRule.runOnUiThread {
            (
                org.robolectric.shadows.ShadowDialog
                    .getLatestDialog() as androidx.activity.ComponentDialog
            ).onBackPressedDispatcher.onBackPressed()
        }
        settle()
        assertFalse(has(HomeTags.POPUP))
    }

    @Test
    fun popupDailyMaxSameDayAndNextDay() {
        port.page = { ok(golden("popup")) }
        launch()
        assertTrue(has(HomeTags.POPUP))
        click(HomeTags.POPUP_CLOSE)
        restart()
        assertFalse(has(HomeTags.POPUP))
        clock += TimeUnit.DAYS.toMillis(1)
        restart()
        assertTrue(has(HomeTags.POPUP))
    }

    @Test
    fun popupOutsideTimeWindowAndBasicModeAndImageFailureNeverShowOrCount() {
        port.page = { ok(golden("popup")) }
        clock = OffsetDateTime.parse("2026-10-10T22:00:00+08:00").toInstant().toEpochMilli()
        launch()
        assertFalse(has(HomeTags.POPUP))
        clock = noon
        images.fail = true
        restart()
        assertFalse(has(HomeTags.POPUP))
        assertEquals(0, prefs.shownToday("popup", clock))
    }

    @Test
    fun popupNotShownInBasicMode() {
        port.page = { ok(golden("popup")) }
        launch(decision = "basic_mode")
        assertTrue(has(BASIC_MODE_PAGE_TAG))
        assertFalse(has(HomeTags.POPUP))
        assertEquals(0, prefs.shownToday("popup", clock))
        // 基本模式：匿名请求、LKG 写在 basic 分区、不发埋点
        assertTrue(port.pageCalls.all { it.anonymous && it.expectedAccount == null })
        assertNotNull(lkg.read(scope(HomeLevel.BASIC)))
        assertTrue(events.isEmpty())
    }

    @Test
    fun popupWaitsForBlockersAndShowsWhenTheyEnd() {
        port.page = { ok(golden("popup")) }
        blockerOn = true
        launch()
        assertFalse(has(HomeTags.POPUP))
        blockerOn = false
        composeRule.runOnUiThread { home.onBlockersChanged() }
        settle()
        assertTrue(has(HomeTags.POPUP))
        // 已显示之后才出现的阻挡也让弹窗收起（共性第 21 条），不再弹
        blockerOn = true
        composeRule.runOnUiThread { home.onBlockersChanged() }
        settle()
        assertFalse(has(HomeTags.POPUP))
        blockerOn = false
        composeRule.runOnUiThread { home.onBlockersChanged() }
        advance(1_000)
        assertFalse(has(HomeTags.POPUP))
        assertEquals(1, prefs.shownToday("popup", clock))
    }

    @Test
    fun popupPendingImageThenTabSwitchThenImageArrivesDoesNotShow() {
        port.page = { ok(golden("popup")) }
        images.hold = CompletableDeferred()
        launch()
        // 打开挂起 → 切 Tab（同步出口先写目标值）→ 图片到达：不弹（共性第 22 条按真实顺序）
        composeRule.runOnUiThread { home.onTab("OrderList") }
        images.hold!!.complete(Unit)
        settle()
        assertFalse(has(HomeTags.POPUP))
        assertEquals(0, prefs.shownToday("popup", clock))
        // 回到首页：仍在冷启动阶段、条件满足才弹
        composeRule.runOnUiThread { home.onTab("Home") }
        settle()
        assertTrue(has(HomeTags.POPUP))
    }

    @Test
    fun popupImageOpensAuthSheetThroughRouterInApp() {
        port.page = { ok(golden("popup")) }
        launch()
        click(HomeTags.POPUP_IMAGE)
        val outcome = routing.router.lastOutcome.value!!
        assertEquals("AuthSheet", outcome.request.route)
        assertEquals(JsonObject(mapOf("platform" to JsonPrimitive("taobao"))), outcome.request.params)
        assertEquals(RouteSource.IN_APP.raw, outcome.request.source)
        assertFalse(has(HomeTags.POPUP))
    }

    @Test
    fun popupShowingThenSessionInvalidClosesWithoutExtraCount() {
        port.page = { ok(golden("popup")) }
        launch(user = "user-a")
        assertTrue(has(HomeTags.POPUP))
        assertEquals(1, prefs.shownToday("popup", clock))
        val pending = CompletableDeferred<Unit>()
        port.hold = pending
        switchUser(null)
        assertFalse(has(HomeTags.POPUP))
        pending.complete(Unit)
        settle()
        assertEquals(1, prefs.shownToday("popup", clock))
    }

    // ---------------------------------------------------------------- AC-S1-81 / 131 ①：动作经 Router

    @Test
    fun cardActionsReachRouterVerbatimAsInApp() {
        val items =
            """[{"title":"甲","action":{"route":"Search","params":{"q":"纸巾"}}},""" +
                """{"title":"乙","action":{"route":"Search","params":{"platform":"jd"}}},""" +
                """{"title":"丙","action":{"route":"ExternalPage",""" +
                """"params":{"url":"https://x.example.com/a?z=%2f&a=hello+world#part","title":"品牌活动说明",""" +
                """"nav_style":"dark"}}}]"""
        val notice =
            notice("nb", "n1", 1, closable = true, end = null, target = """{"route":"Notice","params":{"article_id":"test_notice_main"}}""")
        port.page = { ok(page(50, JsonObject(section("acts", "activity_entry", """{"kind":"static","items":$items}""")), notice)) }
        launch()
        click(HomeTags.tap("acts", 1))
        assertRoute("Search", """{"q":"纸巾"}""")
        back()
        click(HomeTags.tap("acts", 2))
        assertRoute("Search", """{"platform":"jd"}""")
        back()
        click(HomeTags.tap("nb", 1))
        assertRoute("Notice", """{"article_id":"test_notice_main"}""")
        back()
        click(HomeTags.tap("acts", 3))
        assertRoute("ExternalPage", """{"url":"https://x.example.com/a?z=%2f&a=hello+world#part","title":"品牌活动说明","nav_style":"dark"}""")
        val first =
            routing.router.lastOutcome.value!!
                .request.params
        back()
        // 另一个账号点同一张卡：url 逐字相同（不拼任何个人数据）
        switchUser("user-b")
        click(HomeTags.tap("acts", 3))
        assertEquals(
            first,
            routing.router.lastOutcome.value!!
                .request.params,
        )
    }

    @Test
    fun unknownRouteOpensUpgradeAndScriptOnlyActionsAreNotExecuted() {
        val items =
            """[{"title":"热销","action":{"route":"HotRankList","params":{}}},""" +
                """{"title":"脚本","action":{"js":"test_only","url":"https://x.example.com"}}]"""
        port.page = { ok(page(51, JsonObject(section("acts", "activity_entry", """{"kind":"static","items":$items}""")))) }
        launch()
        assertFalse(has(HomeTags.tap("acts", 2)))
        click(HomeTags.tap("acts", 1))
        assertTrue(has(UPGRADE_PAGE_TAG))
    }

    @Test
    fun productCardOpensProductDetailWithServerParams() {
        port.page = { ok(golden("product_grid")) }
        launch()
        click(HomeTags.tap("grid", 1))
        assertRoute("ProductDetail", """{"platform":"taobao","product_key":"test:p0","item_ref":"v1.test.p0.sig"}""")
    }

    @Test
    fun askAiInFixedAreaOpensAgentChatTab() {
        launch()
        assertTrue(has(HOME_ASK_AI_TAG))
        click(HOME_ASK_AI_TAG)
        assertEquals(
            "AgentChat",
            routing.router.lastOutcome.value!!
                .request.route,
        )
    }

    // ---------------------------------------------------------------- 迟到与作废、42901

    @Test
    fun accountAInFlightThenSwitchToBNeverWritesBOrShowsA() {
        lkg.write(lkg.ticket(scope(HomeLevel.MEMBER, "user-b")), page(60, activity("b_lkg")).toString())
        val held = CompletableDeferred<Unit>()
        port.hold = held
        port.page = { call -> ok(page(61, activity("for_" + (call.expectedAccount ?: "guest")))) }
        launch(user = "user-a")
        val controllerA = home.controller.value!!
        // A 会话失效 → 登录 B（真实页面接线）
        switchUser(null)
        // 旧控制器在同步出口里作废（不是只刷新展示）
        assertFalse(controllerA.alive)
        port.hold = null
        switchUser("user-b")
        assertTrue(has(HomeTags.tap("b_lkg", 1)) || has(HomeTags.tap("for_user-b", 1)))
        held.complete(Unit)
        settle()
        assertFalse(has(HomeTags.tap("for_user-a", 1)))
        assertFalse(lkg.read(scope(HomeLevel.MEMBER, "user-b"))!!.contains("for_user-a"))
        // A 的作用域 LKG 在登出时清掉
        assertNull(lkg.read(scope(HomeLevel.MEMBER, "user-a")))
    }

    /**
     * R2-02（共性第 33 条）：账号 A → 游客（排队清 A 的作用域，IO 被占住、清理还没执行）→ 重新登录 A → 清理迟到执行。
     * 新的 A 控制器要等清理完成、复核身份之后才建立并取票：它的冷启动结果与之后每次下拉刷新都照常写进 LKG，不会因迟到的清理推进世代而整个生命周期写不进。
     */
    @Test
    fun r2_02_lateScopeClearAfterAGuestAReturnStillLetsNewControllerPersistColdStartAndPull() {
        val executor =
            java.util.concurrent.Executors
                .newSingleThreadExecutor()
        try {
            port.page = { call -> ok(page(61, activity("cold_" + (call.expectedAccount ?: "guest")))) }
            launch(user = "user-a", io = executor.asCoroutineDispatcher())
            idleUntil { lkg.read(scope(HomeLevel.MEMBER, "user-a"))?.contains("cold_user-a") == true }
            val first = home.controller.value!!
            // 占住 IO：A → 游客时排进去的 clearScope(A) 先不执行
            val held = java.util.concurrent.CountDownLatch(1)
            executor.submit { held.await(WAIT_S, TimeUnit.SECONDS) }
            switchUser(null)
            assertFalse(first.alive)
            port.page = { call -> ok(page(62, activity("again_" + (call.expectedAccount ?: "guest")))) }
            switchUser("user-a")
            // 旧作用域的清理还没完成：这个作用域没有新控制器取票
            assertNull(home.controller.value)
            held.countDown()
            idleUntil {
                home.controller.value
                    ?.identity
                    ?.account == "user-a"
            }
            val second = home.controller.value!!
            assertTrue(second.alive)
            assertTrue(second.id != first.id)
            // 清理已执行（旧的 cold_ 页不在了），新控制器的冷启动结果落盘
            idleUntil { lkg.read(scope(HomeLevel.MEMBER, "user-a"))?.contains("again_user-a") == true }
            idleUntil { has(HomeTags.tap("again_user-a", 1)) }
            // 之后的下拉刷新也照常落盘
            port.page = { ok(page(63, activity("pulled_a"))) }
            refresh()
            idleUntil { lkg.read(scope(HomeLevel.MEMBER, "user-a"))?.contains("pulled_a") == true }
            assertEquals(second, home.controller.value)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun pullInFlightThenConsentWithdrawnDropsResponseAndShowsBasicHome() {
        launch(user = "user-a")
        val held = CompletableDeferred<Unit>()
        port.hold = held
        port.page = { ok(page(70, activity("late_pull"))) }
        refresh(settleAfter = false)
        port.hold = null
        port.page = { ok(DEFAULT_TEST_PAGE) }
        composeRule.runOnUiThread { privacy.withdraw() }
        settle()
        held.complete(Unit)
        settle()
        assertTrue(has(BASIC_MODE_PAGE_TAG))
        assertFalse(has(HomeTags.tap("late_pull", 1)))
        assertTrue(lkgRoot.walkTopDown().filter { it.isFile }.none { it.readText().contains("late_pull") })
    }

    @Test
    fun noticeCloseCallbackAfterAccountSwitchStillRecordsRenderedTriple() {
        port.page = { ok(page(80, notice("nb", "n1", 3, closable = true, end = null))) }
        launch(user = "user-a")
        val controller = home.controller.value!!
        val ticket =
            (
                controller.state.value.cards
                    .single() as com.couli.feature.home.HomeCardUi.Notice
            ).lines
                .single()
                .close!!
        switchUser("user-b")
        composeRule.runOnUiThread { controller.closeNotice(ticket) }
        assertTrue(NoticeFilter.closeKey("nb", "n1", 3) in prefs.closedKeys())
        // 撤回同意之后的旧回调不落盘
        composeRule.runOnUiThread { privacy.withdraw() }
        settle()
        composeRule.runOnUiThread { controller.closeNotice(ticket.copy(itemId = "n9")) }
        assertFalse(NoticeFilter.closeKey("nb", "n9", 3) in prefs.closedKeys())
    }

    @Test
    fun rateLimitDisablesPullAndBlocksAtExecutionLayer() {
        launch()
        port.page = { failure(42901) }
        refresh()
        assertTrue(
            text(
                com.couli.generated.DefaultTexts
                    .text("error.42901")!!,
            ) > 0,
        )
        val before = port.pageCalls.size
        port.page = { ok(DEFAULT_TEST_PAGE) }
        refresh()
        // 被本地限流拦下：不发包、结束刷新动画并提示一次
        assertEquals(before, port.pageCalls.size)
        assertFalse(
            home.controller.value!!
                .state.value.refreshing,
        )
        // 执行层：期间任何拉取都在发送前被拦
        assertTrue(home.gate.active)
        clock += 30_000
        refresh()
        assertEquals(before + 1, port.pageCalls.size)
    }

    @Test
    fun feedPageFailureStopsAtRetryRowAndRetryIsGated() {
        port.items = { sec, cursor -> if (sec == "feed" && cursor != null) failure(50001) else itemsOf(sec, cursor) }
        port.page = { ok(golden("feed_infinite")) }
        launch()
        idleUntil { has(HomeTags.FEED_RETRY) }
        port.items = { sec, cursor -> itemsOf(sec, cursor) }
        click(HomeTags.FEED_RETRY)
        assertFalse(has(HomeTags.FEED_RETRY))
        assertTrue(port.itemCalls.contains("feed|p2"))
    }

    // ---------------------------------------------------------------- 可见性、清理、诊断

    @Test
    fun visibilityRegistryCoexistsAndRebuildSyncsTrueState() {
        port.page = { ok(golden("popup")) }
        images.hold = CompletableDeferred()
        launch(user = "user-a")
        val seen = mutableListOf<Boolean>()
        composeRule.runOnUiThread {
            routing.router.open(
                com.couli.core.router
                    .RouteRequest("Search", JsonObject(emptyMap()), RouteSource.IN_APP),
            )
        }
        settle()
        assertFalse(home.signals.visible.value)
        // 重建（换账号）发生在首页被盖住时：新控制器按真实状态（不可见）同步
        switchUser("user-b")
        seen += home.signals.visible.value
        images.hold!!.complete(Unit)
        settle()
        assertFalse(has(HomeTags.POPUP))
        back()
        assertTrue(home.signals.visible.value)
        assertTrue(has(HomeTags.POPUP))
        assertEquals(listOf(false), seen)
    }

    @Test
    fun cleanersAreRegisteredForConsentAndClearAllHomeData() {
        launch()
        prefs.addClosed(prefs.ticket(), "k", 1L)
        prefs.markShown(prefs.ticket(), "popup", clock)
        assertTrue(AppHome.HOME_LKG_CLEANER in cleaners.registeredNames())
        assertTrue(AppHome.HOME_PREFS_CLEANER in cleaners.registeredNames())
        composeRule.runOnUiThread { privacy.withdraw() }
        settle()
        assertTrue(prefs.closedKeys().isEmpty())
        assertEquals(0, prefs.shownToday("popup", clock))
        assertNull(lkg.read(scope(HomeLevel.GUEST)))
    }

    @Test
    fun productionConsentCleanerRegistryListsHomeCleaners() {
        val registry =
            com.couli.app.AppModule.credentialCleaners(
                context,
                MemorySessionTokens(),
                InFlightCalls(),
                { error("not used") },
                { error("not used") },
                com.couli.app.web
                    .BridgeHostRegistry(),
            )
        assertTrue(AppHome.HOME_LKG_CLEANER in registry.registeredNames())
        assertTrue(AppHome.HOME_PREFS_CLEANER in registry.registeredNames())
        // R1-01：撤回入口先作废首页控制器，再清首页存储
        val names = registry.registeredNames()
        assertTrue(AppHome.HOME_CONTROLLER_CLEANER in names)
        assertTrue(names.indexOf(AppHome.HOME_CONTROLLER_CLEANER) < names.indexOf(AppHome.HOME_LKG_CLEANER))
        assertTrue(names.indexOf(AppHome.HOME_CONTROLLER_CLEANER) < names.indexOf(AppHome.HOME_PREFS_CLEANER))
        assertTrue(names.indexOf(AppHome.HOME_CONTROLLER_CLEANER) < names.indexOf(CredentialCleanerRegistry.TOKENS))
    }

    @Test
    fun lkgDeleteFailureKeepsFlagOutsideAndRestartDoesNotReadOldPage() {
        var deletable = false
        val broken =
            HomeLkgStore(lkgRoot, SharedPreferencesPendingFlag(context, PENDING_NAME), deleter = {
                deletable &&
                    it.deleteRecursively()
            })
        lkg.write(lkg.ticket(scope(HomeLevel.GUEST)), page(90, activity("old")).toString())
        assertTrue(runCatching { broken.clearAll() }.isFailure)
        port.page = { failure(50001) }
        launch(
            lkgStore =
                HomeLkgStore(lkgRoot, SharedPreferencesPendingFlag(context, PENDING_NAME), deleter = {
                    deletable &&
                        it.deleteRecursively()
                }),
        )
        assertFalse(has(HomeTags.tap("old", 1)))
        advance(1_500)
        assertTrue(has(HomeTags.tap("default_icons", 1)))
        deletable = true
        assertNull(lkg.read(scope(HomeLevel.GUEST)))
    }

    // ---------------------------------------------------------------- AC-S1-86 ③④：HomePreview（debug / staging）

    @Test
    fun deepLinkPreviewShowsDraftReadOnlyWithoutTokenInLogsOrEnvSwitch() {
        org.robolectric.shadows.ShadowLog
            .clear()
        launch()
        val lkgBefore = lkg.read(scope(HomeLevel.GUEST))
        val eventsBefore = events.size
        // 多余参数：已有深链解析整条按非法处理（不打开，rule-gaps A 段），不会把 host 一类参数带进预览
        composeRule.runOnUiThread {
            routing.onLaunchIntent("couli-dev://r/HomePreview?page_key=home&token=tok-SECRET-abc&host=other", coldStart = false)
        }
        settle()
        assertTrue(stack().isEmpty())
        composeRule.runOnUiThread {
            routing.onLaunchIntent("couli-dev://r/HomePreview?page_key=home&token=tok-SECRET-abc", coldStart = false)
        }
        idleUntil { has(HomePreviewTags.BAR) && has(HomeTags.tap("draft_icons", 1)) }
        assertEquals(listOf("HomePreview"), stack())
        val preview = synchronized(requests) { requests.single { it.path!!.contains("/preview") } }
        assertEquals("/v1/pages/home/preview?token=tok-SECRET-abc", preview.path)
        assertTrue(text("环境 test") > 0)
        assertTrue(text("草稿版本 v12") > 0)
        // 页底「被丢弃的卡与原因」；只读：LKG、计数、关闭记录、埋点都没变；不切换环境
        assertTrue(text("draft_v2 · banner · unsupported_version") > 0)
        assertEquals(lkgBefore, lkg.read(scope(HomeLevel.GUEST)))
        assertEquals(eventsBefore, events.size)
        assertTrue(prefs.closedKeys().isEmpty())
        assertEquals(0, prefs.shownToday("popup", clock))
        assertEquals(ENV, home.identity().env)
        assertTrue(
            org.robolectric.shadows.ShadowLog
                .getLogs()
                .none { it.msg.orEmpty().contains("tok-SECRET-abc") },
        )
    }

    @Test
    fun preview30701ShowsExpiredAndExitOnlyClosesThisInstance() {
        previewReply =
            {
                MockResponse()
                    .setHeader(
                        "Content-Type",
                        "application/json",
                    ).setBody("""{"code":30701,"msg":"","data":null,"trace_id":"trace-000123"}""")
            }
        launch()
        composeRule.runOnUiThread {
            routing.router.open(
                com.couli.core.router
                    .RouteRequest("Me", JsonObject(emptyMap()), RouteSource.IN_APP),
            )
        }
        settle()
        composeRule.runOnUiThread {
            routing.router.open(
                com.couli.core.router.RouteRequest(
                    "HomePreview",
                    Json.parseToJsonElement("""{"page_key":"home","token":"expired-1"}""").jsonObject,
                    RouteSource.IN_APP,
                ),
            )
        }
        idleUntil { has(HomePreviewTags.EXPIRED) }
        assertTrue(has(HomePreviewTags.SWITCH_ENV))
        click(HomePreviewTags.EXIT)
        assertTrue(stack().isEmpty())
        // 不改原来所在的 Tab（仍是「我的」，首页不可见）
        assertFalse(home.signals.visible.value)
    }

    @Test
    fun previewDraftPopupShowsOnceAndSessionChangeInvalidatesInstance() {
        previewReply = { envelope(PREVIEW_WITH_POPUP) }
        launch(user = "user-a")
        composeRule.runOnUiThread { routing.onLaunchIntent("couli-dev://r/HomePreview?page_key=home&token=tok-1", coldStart = false) }
        idleUntil { has(HomeTags.POPUP) }
        // 预览弹窗不计数
        assertEquals(0, prefs.shownToday("popup", clock))
        click(HomeTags.POPUP_CLOSE)
        assertFalse(has(HomeTags.POPUP))
        assertTrue(has(HomePreviewTags.PAGE))
        switchUser("user-b")
        idleUntil { has(HomePreviewTags.INVALIDATED) }
    }

    @Test
    fun previewLeaveIsSyncExitAndCancelsRequest() {
        val held = java.util.concurrent.CountDownLatch(1)
        previewReply = {
            held.await(WAIT_S, TimeUnit.SECONDS)
            envelope(PREVIEW_DRAFT)
        }
        launch()
        composeRule.runOnUiThread { routing.onLaunchIntent("couli-dev://r/HomePreview?page_key=home&token=tok-2", coldStart = false) }
        idleUntil { has(HomePreviewTags.PAGE) }
        back()
        held.countDown()
        settle()
        assertFalse(has(HomePreviewTags.PAGE))
        assertFalse(has(HomeTags.tap("draft_icons", 1)) && stack().isNotEmpty())
    }

    // ---------------------------------------------------------------- 第 1 轮修复（android-review-r1.md）

    /** R1-01：撤回入口先作废首页控制器，再清首页存储；之后照常按基本模式重建。 */
    @Test
    fun r1_01_withdrawalInvalidatesHomeControllerBeforeStorageCleanup() {
        launch(user = "user-a")
        val before = home.controller.value!!
        assertTrue(before.alive)
        composeRule.runOnUiThread { privacy.withdraw() }
        settle()
        assertEquals(listOf<Boolean?>(false), aliveAtLkgClear)
        assertFalse(before.alive)
        assertTrue(has(BASIC_MODE_PAGE_TAG))
    }

    /** R1-04：正式首页的商品池 / 信息流返回 10405 → 进已有的强更分流（待定端口不经 ApiClient）。 */
    @Test
    fun r1_04_homeSectionItems10405EntersForceUpdate() {
        port.page = { ok(golden("product_grid")) }
        port.items = { _, _ -> failure(10405) }
        launch()
        idleUntil { forced >= 1 }
        assertFalse(has(HomeTags.CARD_PREFIX + "grid"))
    }

    /** R1-04：HomePreview 的草稿数据源返回 10405 → 同一个强更分流（预览 host 不留空实现）。 */
    @Test
    fun r1_04_previewSectionItems10405EntersForceUpdate() {
        previewReply = { envelope(PREVIEW_WITH_GRID) }
        port.items = { _, _ -> failure(10405) }
        launch()
        composeRule.runOnUiThread { routing.onLaunchIntent("couli-dev://r/HomePreview?page_key=home&token=tok-10405", coldStart = false) }
        idleUntil { has(HomePreviewTags.BAR) && has(HomeTags.tap("draft_icons", 1)) }
        idleUntil { forced >= 1 }
    }

    /**
     * R2-03 / R2-04 同类（预览的错误态）：previewPage 返回 42901（带 trace_id、Retry-After 3 秒）→ 错误文案经统一函数（42901 不带编号），
     * 【重试】在共享禁用期内置灰、到期恢复；之后 5xxxx 的文案带 trace6。
     */
    @Test
    fun r2_previewErrorUsesUnifiedTextAndRetryFollowsRateLimit() {
        previewReply = {
            MockResponse()
                .setResponseCode(429)
                .setHeader("Content-Type", "application/json")
                .setHeader("Retry-After", "3")
                .setBody("""{"code":42901,"msg":"","data":null,"trace_id":"trace-pv-RL0001"}""")
        }
        launch()
        composeRule.runOnUiThread { routing.onLaunchIntent("couli-dev://r/HomePreview?page_key=home&token=tok-429", coldStart = false) }
        idleUntil { has(HomePreviewTags.ERROR) }
        val rateText =
            com.couli.core.home.HomeErrors
                .message(com.couli.core.home.HomeErrors.RATE_LIMITED, null)
        assertTrue(text(rateText) > 0)
        assertEquals(0, text("RL0001"))
        val retry =
            com.couli.generated.DefaultTexts
                .text("external_page.retry")!!
        composeRule
            .onNode(
                androidx.compose.ui.test
                    .hasText(retry) and
                    androidx.compose.ui.test
                        .hasClickAction(),
            ).assertIsNotEnabled()
        clock += 3_000
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(3_000))
        settle()
        composeRule
            .onNode(
                androidx.compose.ui.test
                    .hasText(retry) and
                    androidx.compose.ui.test
                        .hasClickAction(),
            ).assertIsEnabled()
        previewReply = {
            MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"code":50001,"msg":"","data":null,"trace_id":"trace-pv-SV0001"}""")
        }
        composeRule
            .onNode(
                androidx.compose.ui.test
                    .hasText(retry) and
                    androidx.compose.ui.test
                        .hasClickAction(),
            ).performClick()
        idleUntil { text("SV0001") > 0 }
    }

    /** R1-09：过期页在 320dp、200% 字号、短视口下，【切换环境】【退出预览】都能滚到并点到。 */
    @Test
    @Config(qualifiers = "w320dp-h480dp-xxhdpi", fontScale = 2f)
    fun r1_09_previewExpiredIsScrollableAtNarrowWidthDoubleFontShortViewport() {
        openExpiredPreview()
        val root = composeRule.onRoot().fetchSemanticsNode().size
        // 【退出预览】在过期内容里（预览条里另有一个，取最后一个）与【切换环境】
        listOf(HomePreviewTags.SWITCH_ENV, HomePreviewTags.EXPIRED_EXIT).forEach { tag ->
            val target = composeRule.onNodeWithTag(tag, useUnmergedTree = true)
            target.performScrollTo()
            settle()
            val bounds = target.fetchSemanticsNode().boundsInRoot
            assertTrue("$tag 在可见区域内：$bounds / $root", bounds.top >= 0f && bounds.bottom <= root.height + 1f && bounds.height > 0f)
        }
        // 窄屏大字号：双按钮纵排（【切换环境】占满内容宽度）
        val switch = composeRule.onNodeWithTag(HomePreviewTags.SWITCH_ENV, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val exit = composeRule.onNodeWithTag(HomePreviewTags.EXPIRED_EXIT, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("纵排：$exit / $switch", switch.top >= exit.bottom)
        // 两个按钮都可点（【切换环境】打开的调试面板要 Hilt，这里只核对可点，点击流程见已有的调试面板测试）
        listOf(HomePreviewTags.SWITCH_ENV, HomePreviewTags.EXPIRED_EXIT).forEach { tag ->
            assertTrue(
                composeRule
                    .onNodeWithTag(tag)
                    .fetchSemanticsNode()
                    .config
                    .contains(androidx.compose.ui.semantics.SemanticsActions.OnClick),
            )
        }
        click(HomePreviewTags.EXPIRED_EXIT)
        assertTrue(stack().isEmpty())
    }

    /** R1-09：大屏时预览条内层与过期内容限宽 600dp、居中。 */
    @Test
    @Config(qualifiers = "w1280dp-h800dp-mdpi")
    fun r1_09_previewExpiredAndBarAreWidthLimitedOnLargeScreen() {
        openExpiredPreview()
        val root = composeRule.onRoot().fetchSemanticsNode().size
        listOf(HomePreviewTags.EXPIRED, HomePreviewTags.BAR).forEach { tag ->
            val bounds = composeRule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag 宽 ${bounds.width}", bounds.width <= 600f + 1f)
            val center = (bounds.left + bounds.right) / 2f
            assertTrue("$tag 居中 $center", kotlin.math.abs(center - root.width / 2f) <= 2f)
        }
    }

    private fun openExpiredPreview() {
        previewReply = {
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"code":30701,"msg":"","data":null,"trace_id":"trace-000123"}""")
        }
        launch()
        composeRule.runOnUiThread {
            routing.router.open(
                com.couli.core.router.RouteRequest(
                    "HomePreview",
                    Json.parseToJsonElement("""{"page_key":"home","token":"expired-r1"}""").jsonObject,
                    RouteSource.IN_APP,
                ),
            )
        }
        idleUntil { has(HomePreviewTags.EXPIRED) }
    }

    // ---------------------------------------------------------------- F-X-02：调试替身的环境闸门（实际传输入口）

    @Test
    fun fakeHomeAnswersOnlyWhenEnvironmentAllowsMockData() {
        val switch = File(context.filesDir, "fake-switch-${System.nanoTime()}").apply { writeText("1") }
        var mock = false
        val fake =
            com.couli.app.DebugFakeHome(
                File(context.filesDir, "fake-home-state"),
                switchOn = { switch.exists() },
                mockAllowed = { mock },
            )
        http =
            okhttp3.OkHttpClient
                .Builder()
                .addInterceptor(com.couli.app.DebugFakeLoginServer(switch, fake))
                .build()
        launch()
        // prod（不允许 MOCK_DATA）：待定 operation 按断网，契约接口交给真实网络（这里是 MockWebServer）
        val call = HomeCall(anonymous = false, expectedAccount = null, allowed = { true }, stillOwned = { true })
        assertTrue(kotlinx.coroutines.runBlocking { fake.getPage("home", call) } is ApiResult.TransportFailure)
        assertTrue(kotlinx.coroutines.runBlocking { fake.listSectionItems("home", "grid", null, call) } is ApiResult.TransportFailure)
        val before = synchronized(requests) { requests.size }
        val viaNetwork =
            kotlinx.coroutines.runBlocking {
                home.pageApi.send(
                    com.couli.core.network
                        .ApiCall("listArticles", query = "category=notice"),
                )
            }
        assertTrue(viaNetwork is ApiResult.Success)
        assertEquals(before + 1, synchronized(requests) { requests.size })
        // 允许 MOCK_DATA：本机回答，不连任何地址
        mock = true
        assertTrue(kotlinx.coroutines.runBlocking { fake.getPage("home", call) } is ApiResult.Success)
        kotlinx.coroutines.runBlocking {
            home.pageApi.send(
                com.couli.core.network
                    .ApiCall("listArticles", query = "category=notice"),
            )
        }
        assertEquals(before + 1, synchronized(requests) { requests.size })
        switch.delete()
    }

    @Test
    fun basicModeListArticlesCarriesNoCredentialsOrDeviceId() {
        port.page = { ok(golden("notice_api")) }
        launch(decision = "basic_mode")
        idleUntil { synchronized(requests) { requests.any { it.path!!.startsWith("/v1/articles") } } }
        val article = synchronized(requests) { requests.first { it.path!!.startsWith("/v1/articles") } }
        assertNull(article.getHeader("Authorization"))
        assertNull(article.getHeader("X-Device-Id"))
        assertNull(article.getHeader("X-Sign"))
        assertTrue(events.isEmpty())
    }

    @Test
    fun tabSwitchSyncExitPausesBeforeSelectionAndDiagnosticsVersion() {
        launch()
        assertTrue(home.signals.visible.value)
        assertEquals("21 (network)", home.currentVersion())
        composeRule.runOnUiThread {
            routing.router.open(
                com.couli.core.router
                    .RouteRequest("OrderList", JsonObject(emptyMap()), RouteSource.IN_APP),
            )
            // 同一同步出口：选中项改之前首页已不可见（共性第 22、28 条）
            assertFalse(home.signals.visible.value)
        }
        settle()
        composeRule.runOnUiThread {
            routing.router.open(
                com.couli.core.router
                    .RouteRequest("Home", JsonObject(emptyMap()), RouteSource.IN_APP),
            )
        }
        settle()
        assertTrue(home.signals.visible.value)
        // 返回首页不重发拉取
        assertEquals(1, port.pageCalls.size)
    }

    // ---------------------------------------------------------------- 设计稿比对截图（写到 app/build/home-shots，不入库）

    @Test
    fun appLevelBoardsForDesignCompare() {
        val main =
            page(
                31,
                notice("notice", "n1", 1, closable = true, end = null),
                golden("banner").getValue("sections").let {
                    (it as JsonArray)[0].jsonObject
                },
            )
        val sections =
            (main.getValue("sections") as JsonArray) + (golden("default_home").getValue("sections") as JsonArray) +
                (golden("product_scroll").getValue("sections") as JsonArray) + (golden("product_grid").getValue("sections") as JsonArray)
        port.page = { ok(JsonObject(main + ("sections" to JsonArray(sections)))) }
        launch(user = "user-a")
        idleUntil { has(HomeTags.CARD_PREFIX + "scroll") }
        shot("Main-shell")
        composeRule.runOnUiThread { routing.onLaunchIntent("couli-dev://r/HomePreview?page_key=home&token=tok-shot", coldStart = false) }
        idleUntil { has(HomeTags.tap("draft_icons", 1)) }
        shot("HomePreview")
        back()
        previewReply =
            {
                MockResponse()
                    .setHeader(
                        "Content-Type",
                        "application/json",
                    ).setBody("""{"code":30701,"msg":"","data":null,"trace_id":"t"}""")
            }
        composeRule.runOnUiThread {
            routing.onLaunchIntent(
                "couli-dev://r/HomePreview?page_key=home&token=expired-shot",
                coldStart = false,
            )
        }
        idleUntil { has(HomePreviewTags.EXPIRED) }
        shot("HomePreviewExpired")
        back()
        composeRule.runOnUiThread { privacy.withdraw() }
        settle()
        idleUntil { has(BASIC_MODE_PAGE_TAG) }
        shot("BasicMode")
    }

    @Test
    fun loadFailedBoard() {
        bundled = { "{}" }
        port.page = { ApiResult.TransportFailure("disconnect", null, null, false) }
        launch()
        advance(1_500)
        assertTrue(has(HomeTags.LOAD_FAILED))
        shot("StateLoadFailed")
    }

    private fun shot(name: String) {
        val dir = File(System.getProperty("user.dir"), "build/home-shots").apply { mkdirs() }
        composeRule.onRoot().captureRoboImage(
            File(dir, "$name.png"),
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record),
        )
    }

    // ---------------------------------------------------------------- FU-AND-0717 第 1 轮修复 R1-04

    /**
     * 会员 LKG 按「账号 + 持久化登录世代」分区：先有 A/E1 的会员 LKG 文件，同账号重新登录进入 E2 并走真实首页同步——同步里先按已确认的身份快照对账，
     * 在任何读取之前 E1 的世代目录已被删掉；E2 读不到旧页面。对照：同一世代再同步不删。
     */
    @Test
    fun fuand0717_r1_04_aSameAccountReloginDeletesTheOldEpochMemberLkgOnSyncBeforeAnyRead() {
        lkg.bindLoginEpoch { tokens.persistedLoginEpoch() }
        launch(user = "user-a")
        val member = scope(HomeLevel.MEMBER, "user-a")
        val e1 = tokens.persistedLoginEpoch()!!
        assertTrue(lkg.write(lkg.ticket(member), """{"page":"e1"}"""))
        val e1Dir = File(File(lkgRoot, HomeLkgStore.digest(member)), "e-" + HomeLkgStore.digest(e1))
        assertTrue("前提：E1 的会员 LKG 在盘上", File(e1Dir, "page.json").exists())
        restart()
        assertTrue("同一世代再同步：不删", File(e1Dir, "page.json").exists())
        composeRule.runOnUiThread {
            tokens.save("access-user-a-2", "refresh-user-a-2", "full", "user-a")
            home.sync()
            // 同一条主线程消息里、控制器读取之前：旧世代目录已删
            assertFalse("E1 的世代目录已删", e1Dir.exists())
        }
        assertFalse(home.lkgReconcilePending)
        settle()
        assertNotEquals("""{"page":"e1"}""", lkg.read(member))
    }

    /**
     * FU-AND-0717 第 3 轮（第 2 轮评审 R2-06）：首页已有 A/E1 的控制器与会员 LKG；一次真实首页同步里，开头那一份快照确认 A/E1（世代对账保留文件），紧接着的
     * 读取单次失败——不得当成换号删掉 A 的会员作用域、不重建控制器。之后同步时这份快照本身读不出（未知）也不删。对照：确认换号才删。
     */
    @Test
    fun fuand0717_rework_r2_06_aFailedReadAfterTheSyncSnapshotNeverDeletesTheMemberLkg() {
        lkg.bindLoginEpoch { tokens.persistedLoginEpoch() }
        flakyReads = true
        launch(user = "user-a")
        val member = scope(HomeLevel.MEMBER, "user-a")
        assertTrue(lkg.write(lkg.ticket(member), """{"page":"e1"}"""))
        val memberDir = File(lkgRoot, HomeLkgStore.digest(member))
        assertTrue("前提：A 的会员 LKG 在盘上", memberDir.exists())
        val controller = home.controller.value
        val reads = flaky!!
        composeRule.runOnUiThread {
            // 这次同步的快照（下一次读取）成功，紧接着的一次读取失败
            reads.failReads += reads.reads + 2
            home.sync()
        }
        settle()
        assertTrue("A 的会员作用域还在", memberDir.exists())
        assertEquals("""{"page":"e1"}""", lkg.read(member))
        assertTrue("控制器没被当成换号重建", home.controller.value === controller && controller?.alive == true)
        assertFalse(home.lkgReconcilePending)
        composeRule.runOnUiThread {
            // 同步的那一份快照本身读不出（身份暂时未知）：不决策
            reads.failReads += reads.reads + 1
            home.sync()
        }
        settle()
        assertTrue("未知不删", memberDir.exists())
        assertTrue(home.controller.value === controller)
        // 同账号重新登录（本进程登录世代变了）而这时读不出身份：本代还没有已确认的账号——不得按「账号未知」把 A 的会员作用域当成换号删掉
        composeRule.runOnUiThread {
            (1..8).forEach { reads.failReads += reads.reads + it }
            tokens.save("access-user-a-2", "refresh-user-a-2", "full", "user-a")
            home.sync()
        }
        settle()
        assertTrue("换代而身份未知：不删", memberDir.exists())
        // 对照：读取恢复、确认换号（B）才删 A 的会员作用域
        reads.failReads.clear()
        switchUser("user-b")
        settle()
        assertFalse("确认换号：A 的会员作用域已删", memberDir.exists())
    }

    // ---------------------------------------------------------------- 工具

    private fun restart() {
        composeRule.runOnUiThread {
            home.controller.value?.invalidate()
            home.sync()
        }
        settle()
    }

    private fun switchUser(user: String?) {
        composeRule.runOnUiThread {
            if (user == null) tokens.clear() else tokens.save("access-$user", "refresh-$user", "full", user)
            routing.updateSession(if (user == null) SessionState.GUEST else SessionState.PHONE_BOUND, resumePending = false)
            home.sync()
        }
        settle()
    }

    private fun refresh(settleAfter: Boolean = true) {
        composeRule.runOnUiThread {
            val controller = home.controller.value!!
            controller.refresh(controller.state.value.render)
        }
        if (settleAfter) settle()
    }

    private fun advance(ms: Long) {
        clock += ms
        composeRule.runOnUiThread { timers.fire() }
        settle()
    }

    private fun back() {
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
    }

    private fun scope(
        level: String,
        user: String? = null,
    ): String = HomeLkgScope.of(level, user, ENV)

    private fun close(
        section: String,
        item: String,
    ) = "${HomeTags.NOTICE_CLOSE_PREFIX}${section}_$item"

    private fun assertRoute(
        route: String,
        params: String,
    ) {
        val outcome = routing.router.lastOutcome.value!!
        assertEquals(route, outcome.request.route)
        assertEquals(Json.parseToJsonElement(params), outcome.request.params)
        assertEquals(RouteSource.IN_APP.raw, outcome.request.source)
    }

    private fun lastParams(): JsonObject =
        routing.router.lastOutcome.value!!
            .request.params

    private fun JsonElement.jsonPrimitive(): String = (this as JsonPrimitive).content

    private fun settle() {
        repeat(SETTLE_ROUNDS) {
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.waitForIdle()
            Thread.sleep(POLL_MS)
        }
    }

    private fun idleUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S)
        while (!condition() && System.nanoTime() < deadline) settle()
        if (!condition()) {
            println(
                runCatching { composeRule.onRoot(useUnmergedTree = true).printToString(maxDepth = Int.MAX_VALUE) }.getOrNull(),
            )
        }
        assertTrue(condition())
    }

    private fun has(tag: String): Boolean = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun text(substring: String): Int =
        composeRule.onAllNodesWithText(substring, substring = true, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun spoken(substring: String): Int =
        composeRule.onAllNodes(hasContentDescription(substring, substring = true), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun click(tag: String) {
        val node = composeRule.onNodeWithTag(tag, useUnmergedTree = true)
        runCatching { node.performScrollTo() }
        node.performClick()
        settle()
    }

    private fun clickText(text: String) {
        val nodes = composeRule.onAllNodesWithText(text, useUnmergedTree = true)
        nodes[nodes.fetchSemanticsNodes().size - 1].performClick()
        settle()
    }

    @SuppressLint("RestrictedApi")
    private fun stack(): List<String> =
        navController.currentBackStack.value
            .filter { it.destination.hasRoute(RouteDestination::class) }
            .map { it.toRoute<RouteDestination>().name }

    private fun envelope(data: String) =
        MockResponse()
            .setHeader(
                "Content-Type",
                "application/json",
            ).setBody("""{"code":0,"msg":"","data":$data,"trace_id":"trace-000123"}""")

    companion object {
        const val ENV = "test"
        const val PENDING_NAME = "couli_home_lkg_pending_test"
        private const val SETTLE_ROUNDS = 4
        private const val POLL_MS = 5L
        private const val WAIT_S = 10L

        val CARD_TEXTS =
            ProductCardTexts { key -> mapOf("platform.taobao" to "淘宝", "platform.jd" to "京东", "platform.pdd" to "拼多多")[key].orEmpty() }

        fun ok(data: JsonElement): ApiResult = ApiResult.Success(data, "trace-test-000001", null)

        fun failure(
            code: Int,
            trace: String = "trace-test-000777",
        ): ApiResult =
            ApiResult.Failure(
                code,
                "",
                JsonObject(emptyMap()),
                trace,
                ErrorEffect(
                    code,
                    if (code == 42901) ErrorEffectKind.DISABLE_BUTTON else ErrorEffectKind.TOAST,
                    ReplayMode.NONE,
                    false,
                    disableMillis = if (code == 42901) 30_000L else null,
                ),
                null,
            )

        fun golden(name: String): JsonObject {
            val start = File(System.getProperty("user.dir") ?: ".").canonicalFile
            val root = generateSequence(start) { it.parentFile }.first { File(it, "contract.lock").isFile }
            return Json
                .parseToJsonElement(File(root, "specs/home-sdui-vectors.json").readText())
                .jsonObject
                .getValue("goldenPages")
                .jsonObject
                .getValue(name)
                .jsonObject
        }

        fun section(
            id: String,
            type: String,
            source: String,
            props: String = "{}",
            adLabel: String? = null,
        ): Map<String, JsonElement> =
            linkedMapOf(
                "id" to JsonPrimitive(id),
                "type" to JsonPrimitive(type),
                "version" to JsonPrimitive(1),
                "props" to Json.parseToJsonElement(props),
                "data_source" to Json.parseToJsonElement(source),
            ).also { map -> adLabel?.let { map["ad_label"] = JsonPrimitive(it) } }

        fun activity(id: String): JsonObject =
            JsonObject(section(id, "activity_entry", """{"kind":"static","items":[{"title":"测试 $id","action":{"route":"Help"}}]}"""))

        @Suppress("LongParameterList")
        fun notice(
            id: String,
            item: String,
            version: Int,
            closable: Boolean,
            end: String?,
            target: String = "null",
        ): JsonObject {
            val endJson = end?.let { "\"$it\"" } ?: "null"
            return JsonObject(
                section(
                    id,
                    "notice_bar",
                    """{"kind":"static"}""",
                    props =
                        """{"items":[{"item_id":"$item","content_version":$version,"text":"测试公告 $item","target":$target,""" +
                            """"closable":$closable,"start_at":"2026-10-01T00:00:00+08:00","end_at":$endJson}]}""",
                ),
            )
        }

        fun page(
            version: Int,
            vararg sections: JsonObject,
        ): JsonObject =
            JsonObject(
                mapOf(
                    "page_key" to JsonPrimitive("home"),
                    "version" to JsonPrimitive(version),
                    "sections" to JsonArray(sections.toList()),
                ),
            )

        val DEFAULT_TEST_PAGE: JsonObject = page(21, activity("act"))

        fun itemsOf(
            section: String,
            cursor: String? = null,
        ): ApiResult {
            val (range, next) =
                when {
                    section == "feed" && cursor == null -> (0 until 4) to "p2"
                    section == "feed" -> (4 until 6) to null
                    else -> (0 until 3) to null
                }
            val items =
                range.map { i ->
                    Json.parseToJsonElement(
                        """{"product_key":"test:p$i","item_ref":"v1.test.p$i.sig","platform":"taobao",""" +
                            """"title":"测试商品 $i","image":"https://img.example.test/p/$i.png",""" +
                            """"shop_name":"示例旗舰店","price_fen":2290,"coupon_fen":300,"final_price_fen":1990,""" +
                            """"est_net_price_fen":1880,"rebate_min_fen":110,""" +
                            """"rebate_max_fen":110,"rebate_basis":"normal","benefit_tags":[],"is_presale":false,""" +
                            """"link_id":"link.p$i","cta":{"text_key":"btn.buy"},""" +
                            """"quoted_at":"2026-10-10T10:00:00+08:00","stale":false,"age_sec":30,"source":"taobao_union",""" +
                            """"disclaimer_keys":["price_basis","rebate_estimate"],"availability":"ok"}""",
                    )
                }
            return ok(JsonObject(mapOf("items" to JsonArray(items), "next_cursor" to (next?.let(::JsonPrimitive) ?: JsonNull))))
        }

        const val PREVIEW_DRAFT =
            """{"page_key":"home","version":12,"sections":[""" +
                """{"id":"draft_icons","type":"icon_grid","version":1,"props":{"columns":4},"data_source":{"kind":"static","items":[""" +
                """{"icon":"builtin:guide","title":"新人指南","action":{"route":"Help"}}]}},""" +
                """{"id":"draft_v2","type":"banner","version":2,"props":{"height_ratio":0.4},""" +
                """"data_source":{"kind":"static","items":[]}}]}"""

        const val PREVIEW_WITH_GRID =
            """{"page_key":"home","version":14,"sections":[""" +
                """{"id":"draft_icons","type":"icon_grid","version":1,"props":{"columns":4},"data_source":{"kind":"static","items":[""" +
                """{"icon":"builtin:guide","title":"新人指南","action":{"route":"Help"}}]}},""" +
                """{"id":"draft_grid","type":"product_grid","version":1,"props":{"columns":2,"title":"草稿商品"},""" +
                """"data_source":{"kind":"product_pool","pool_id":"draft_pool","limit":20}}]}"""

        const val PREVIEW_WITH_POPUP =
            """{"page_key":"home","version":13,"sections":[""" +
                """{"id":"draft_icons","type":"icon_grid","version":1,"props":{"columns":4},"data_source":{"kind":"static","items":[""" +
                """{"icon":"builtin:guide","title":"新人指南","action":{"route":"Help"}}]}},""" +
                """{"id":"popup","type":"popup","version":1,"props":{"image":"https://img.example.com/p.png","daily_max":1,""" +
                """"time_window":["00:00","00:01"]},"data_source":{"kind":"static","items":[]}}]}"""

        const val NOTICES =
            """{"items":[{"article_id":"a1","category":"notice","title":"t","version":1,"published_at":"2026-10-01T00:00:00+08:00",""" +
                """"notice":{"item_id":"api1","content_version":1,"text":"确认收货后，返利随联盟月度结算入账","target":null,"closable":true,""" +
                """"start_at":"2026-10-01T00:00:00+08:00","end_at":null}}],"next_cursor":null}"""
    }
}
