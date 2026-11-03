package com.couli.app.clipboard

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.FlowWait
import com.couli.app.home.HomeRoute
import com.couli.app.home.clipboardTestHome
import com.couli.app.login.AppLogin
import com.couli.app.privacy.AppPrivacy
import com.couli.app.routing.AppRouting
import com.couli.app.routing.RouteDestination
import com.couli.app.routing.RoutingPrivacyHooks
import com.couli.app.routing.WebTargets
import com.couli.app.search.AppSearch
import com.couli.app.shell.AppShell
import com.couli.app.shell.ShellDraftTexts
import com.couli.app.trade.AndroidJumpPorts
import com.couli.app.trade.AppTrade
import com.couli.core.clientcore.ConfigReply
import com.couli.core.clientcore.ConfigStore
import com.couli.core.clipboard.ClipRead
import com.couli.core.clipboard.ClipboardFingerprint
import com.couli.core.clipboard.ClipboardSaltRead
import com.couli.core.clipboard.ClipboardText
import com.couli.core.clipboard.ClipboardUi
import com.couli.core.clipboard.ImportPreferenceStore
import com.couli.core.clipboard.ManualPasteAction
import com.couli.core.clipboard.SystemClipboard
import com.couli.core.config.CouliPreferenceNamespaces
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiClient
import com.couli.core.network.ApiClientPageApi
import com.couli.core.network.ApiResult
import com.couli.core.network.CallOwnership
import com.couli.core.network.ClientIdentity
import com.couli.core.network.DeviceCredentials
import com.couli.core.network.ErrorEffect
import com.couli.core.network.ErrorEffectKind
import com.couli.core.network.InFlightCalls
import com.couli.core.network.MemoryPendingKeyBackend
import com.couli.core.network.MemorySessionTokens
import com.couli.core.network.NetworkContext
import com.couli.core.network.PageApi
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PrivacyRaw
import com.couli.core.network.ReplayMode
import com.couli.core.network.UpdateRaw
import com.couli.core.privacy.PrivacyGate
import com.couli.core.privacy.PrivacyStore
import com.couli.core.privacy.RetryScheduler
import com.couli.core.router.PrivacyState
import com.couli.core.router.RouteRequest
import com.couli.core.router.RouteSource
import com.couli.core.router.SessionState
import com.couli.core.router.SharedPreferencesPendingBackend
import com.couli.core.router.UpdateCheck
import com.couli.core.tradejump.UnavailableBaichuan
import com.couli.feature.home.HomeTags
import com.couli.feature.login.LegalVersions
import com.couli.feature.login.LoginTags
import com.couli.feature.login.PendingInviteCode
import com.couli.feature.search.SearchTags
import com.couli.feature.trade.TradeTags
import com.couli.feature.trade.UnavailableWebCodeAuthorizer
import com.couli.generated.DefaultTexts
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Provider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 剪贴板识别的本地假服务集成测试（APP-07 brief §2.11）：真实 AppShell + NavHost + AppRouting + AppPrivacy + AppSearch（详情）+ ApiClient，
 * 本地假服务 MockWebServer，计数的假系统剪贴板。生命周期经 MainActivity 用的同一组入口（onForeground / onWindowFocus / onBackground），
 * 跳转经 Router，不直接调内部函数。每个用例都断言系统剪贴板写入次数（识别、提示、上传、绑定的前后都是 0）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
@Suppress("LargeClass", "TooManyFunctions") // 验收场景逐条一个用例
class AppClipboardIntegrationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = 1_800_000_000_000L
    private lateinit var server: MockWebServer
    private lateinit var privacy: AppPrivacy
    private lateinit var routing: AppRouting
    private lateinit var clipboard: AppClipboard
    private lateinit var importPreferences: ImportPreferenceStore
    private lateinit var navController: NavHostController
    private lateinit var login: AppLogin
    private val system = FakeClipboard()
    private val inFlight = InFlightCalls()
    private var sessionEpoch = 0L
    private val requests = mutableListOf<Pair<String, String>>()

    /** 经 LocalCouliAnnouncer 的读屏播报（FU-AND-0710，共性第 33 条）。 */
    private val announced = mutableListOf<String>()

    /** 接了购买时的 AppTrade（FU-AND-0710，共性第 32 条：50301 覆盖）。 */
    private var tradeRef: AppTrade? = null

    @Volatile
    private var parseAnswer: () -> MockResponse = { envelope(0, singleCard()) }

    @Volatile
    private var hold: CountDownLatch? = null

    /** 搜索接口的回答（参数是请求路径）；为 null 时给一张耳机卡。 */
    @Volatile
    private var searchAnswer: ((String) -> MockResponse)? = null

    /** 最近一次剪贴板 parse 交给网络层的归属（A-R2-04：测试在后台线程上直接调它的核对）。 */
    @Volatile
    private var lastOwnership: CallOwnership? = null

    /** 界面状态源（首启弹窗、敏感浮层来源）在主线程以外被读到的次数（A-R2-04：网络线程只能读快照）。 */
    private val offMainUiReads = AtomicInteger()

    private fun countOffMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) offMainUiReads.incrementAndGet()
    }

    /**
     * 只给 A-R1-03 用：parse 的回答由测试在主线程上给出（[CompletableDeferred.complete] 在主线程同步恢复 parse 协程），以便把「响应到达」
     * 放进点 Tab 的同一条主线程消息里、下一次重组之前。为 null 时走真实 ApiClient + 本地假服务。
     */
    private var gated: CompletableDeferred<ApiResult>? = null

    /** R1-05：依次给每个 parse 的回答（不为空时优先于 [gated]）。 */
    private val gatedQueue = ArrayDeque<CompletableDeferred<ApiResult>>()
    private val activeApiCalls = AtomicInteger()
    private val requestEntries = AtomicInteger()
    private val clipboardWork = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun settleInviteAction() {
        FlowWait.until(pump = {
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.waitForIdle()
        }) {
            clipboardWork.coroutineContext[Job]!!.children.none { it.isActive }
        }
    }

    /** 剪贴板用的 PageApi：缺省原样交给 ApiClient；[gated] 不为 null 时这一次 parse 等测试给回答。 */
    private inner class GatedPageApi(
        private val delegate: PageApi,
    ) : PageApi by delegate {
        @Suppress("ReturnCount") // 依次闸门、单次闸门、原样转发
        override suspend fun send(call: ApiCall): ApiResult {
            requestEntries.incrementAndGet()
            activeApiCalls.incrementAndGet()
            try {
                if (call.operationId == "parseInput") synchronized(requests) { requests += PARSE_PATH to call.body.orEmpty() }
                lastOwnership = call.ownership
                // R1-05：按发出顺序依次停在闸门的回答（多次 parse 交错、乱序到达）
                gatedQueue.removeFirstOrNull()?.let { return it.await() }
                val reply = gated ?: return delegate.send(call)
                gated = null
                return reply.await()
            } finally {
                activeApiCalls.decrementAndGet()
            }
        }
    }

    private class MemoryStore(
        override var decisionRaw: String = "consented",
    ) : PrivacyStore {
        override var consentedVersion: String? = "1"
        override var consentedAtMillis: Long? = 0L
        override var basicMode: Boolean = decisionRaw == "basic_mode"
    }

    /** 计数的假系统剪贴板：读取次数（含非文本、null）、写入次数。 */
    private class FakeClipboard : SystemClipboard {
        var content: ClipRead = ClipRead.Unavailable
        var reads = 0
        var writes = 0

        override fun read(): ClipRead {
            reads += 1
            return content
        }

        override fun write(
            text: String,
            label: String,
        ): Boolean {
            writes += 1
            content = ClipRead.Text(text)
            return true
        }
    }

    @Before
    fun setUp() {
        SharedPreferencesPendingBackend(context).delete()
        prefs().edit().clear().commit()
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    if (!path.startsWith(PARSE_PATH)) synchronized(requests) { requests += path to request.body.readUtf8() }
                    return when {
                        path.startsWith(PARSE_PATH) -> {
                            hold?.let { assertTrue("测试响应闸门必须被释放", it.await(WAIT_S, TimeUnit.SECONDS)) }
                            parseAnswer()
                        }

                        path.startsWith(SEARCH_PATH) -> {
                            searchAnswer?.invoke(path) ?: envelope(
                                0,
                                """{"items":[${card("tb:7Kq9LmN3pQ", "降噪蓝牙耳机 5.3")}],"next_cursor":null,""" +
                                    """"has_more":false,"fallback_items":[]}""",
                            )
                        }

                        // 购买 open：淘宝未授权（30101）→ AuthSheet
                        path.startsWith(LINKS_PATH) -> {
                            envelope(30101, "{}").setResponseCode(422)
                        }

                        path.startsWith(SMS_PATH) -> {
                            envelope(0, """{"resend_after_sec":60,"expires_in_sec":300}""")
                        }

                        else -> {
                            envelope(0, "null")
                        }
                    }
                }
            }
        server.start()
    }

    @After
    fun tearDown() {
        clipboardWork.cancel()
        server.shutdown()
    }

    private fun prefs() = context.getSharedPreferences(CouliPreferenceNamespaces.CLIPBOARD, Context.MODE_PRIVATE)

    @Suppress("LongMethod") // 与 AppModule 同一套接线逐项搭起来
    private fun launch(
        decision: String = "consented",
        session: SessionState = SessionState.GUEST,
        withTrade: Boolean = false,
        config: String = CONFIG,
        autoReadEnabled: Boolean? = true,
    ) {
        importPreferences = regressionImportPreferences(context, { now }, autoReadEnabled.takeIf { decision == "consented" })
        val gate =
            PrivacyGate(MemoryStore(decision), clock = { now }, bundledPolicyVersion = "1").apply {
                // 撤回同意的本地清理（AppModule 的 CredentialCleanerRegistry）：作废在途请求、删 hash 记录
                withdrawalCleanup = {
                    inFlight.onPrivacyRevoked()
                    ClipboardPreferences(context).clearHashes()
                }
                retryScheduler = RetryScheduler { _, _ -> }
            }
        privacy = AppPrivacy(gate, environmentSettled = { true }, clock = { now }).also { it.coldStart() }
        val environment = EnvironmentGate { EnvironmentStartupReconciler.Outcome.UNCHANGED }.also { it.settleAtStartup() }
        // 已登录：本机有 full 会话（AppLogin 按令牌同步守卫的会话状态）
        val tokens =
            if (session ==
                SessionState.GUEST
            ) {
                MemorySessionTokens()
            } else {
                MemorySessionTokens("access-one", "refresh-one", "full", "user-one")
            }
        routing =
            AppRouting(
                context,
                environment,
                clock = { now },
                initialPrivacy = privacy.conclusion,
                privacyHooks = RoutingPrivacyHooks(promptShowing = { privacy.promptShowing }),
            )
        privacy.onConclusionChanged { routing.privacy.value = it }
        val api =
            ApiClient(
                identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.0.0", build = "1", channel = "official"),
                baseUrl = { server.url("/").toString() },
                context = {
                    NetworkContext(
                        if (privacy.conclusion == PrivacyState.CONSENTED) PrivacyRaw.FULL else PrivacyRaw.BASIC,
                        environmentSettled = true,
                        credentialsReadable = true,
                        UpdateRaw.SUPPORTED,
                    )
                },
                device = FixtureDevice,
                tokens = tokens,
                pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
                inFlight = inFlight,
                sleeper = {},
            )
        val store =
            ConfigStore(
                readFile = { null },
                writeFile = {},
                fetcher = { _, done -> done(ConfigReply(ConfigReply.OK, config, null)) },
                clock = { now },
                bundled = config,
            )
        composeRule.runOnUiThread {
            clipboard =
                AppClipboard(
                    system = system,
                    api = GatedPageApi(ApiClientPageApi(api)),
                    workScope = clipboardWork,
                    routing = routing,
                    privacy = { privacy.conclusion },
                    promptShowing = {
                        countOffMain()
                        privacy.promptShowing
                    },
                    configData = { Json.parseToJsonElement(config) as JsonObject },
                    // FU-CLIP C3：盐经 Keystore 包裹存储；Robolectric 没有 AndroidKeyStore，注入样例加密与样例盐字节（接线改动，见交回清单）
                    prefs = ClipboardPreferences(context, SampleCipher),
                    importPreferences = importPreferences,
                    sessionEpoch = { sessionEpoch },
                    clock = { now },
                    randomSalt = { SAMPLE_SALT.copyOf() },
                    pendingInvite = PendingInviteCode(),
                )
            clipboard.overlay = {
                countOffMain()
                null
            }
            clipboard.connect(privacy, routing, cleaners = null)
        }
        // 购买（AppModule 同一套接线）：AuthSheet 出现的同步出口作废剪贴板的在途 parse；浮层来源现取（A-R1-04）
        val trade =
            if (!withTrade) {
                null
            } else {
                AppTrade(
                    api = ApiClientPageApi(api),
                    store = store,
                    routing = routing,
                    tracker = Provider { error("购买测试不上报 link_jump") },
                    baichuan = UnavailableBaichuan(),
                    webCode = UnavailableWebCodeAuthorizer,
                    ports = AndroidJumpPorts(context, writer = clipboard.writer),
                ).also { trade ->
                    trade.onSensitiveOverlay = clipboard::onSensitiveOverlay
                    clipboard.overlay = {
                        countOffMain()
                        trade.sensitiveOverlay()
                    }
                }
            }
        tradeRef = trade
        // 与 AppModule 同一个接线：多卡弹层的商品卡按平台购买状态映射（APP-07 共性第 32 条）
        composeRule.runOnUiThread { clipboard.connectPurchaseStatus(trade, store) }
        val search = AppSearch(api, store, routing, privacy = { privacy.conclusion }, trade = trade, clipboard = clipboard)
        composeRule.runOnUiThread {
            login =
                AppLogin(api, tokens, routing, updateCheck = {
                    UpdateCheck.OK
                }, markBelow = {}, legal = LegalVersions(1, 1), pendingInvite = clipboard.pendingInvite)
            login.syncSession()
        }
        val home = composeRule.runOnUiThread { clipboardTestHome(api, tokens, routing, privacy) }
        composeRule.setContent {
            navController = rememberNavController()
            androidx.compose.runtime.CompositionLocalProvider(
                com.couli.core.designsystem.LocalCouliAnnouncer provides
                    com.couli.core.designsystem
                        .CouliAnnouncer { text -> synchronized(announced) { announced += text } },
            ) {
                AppShell(
                    routing = routing,
                    privacy = privacy,
                    homeContent = { HomeRoute(home) },
                    home = home,
                    navController = navController,
                    search = search,
                    login = login,
                    clipboard = clipboard,
                )
            }
        }
        composeRule.waitForIdle()
    }

    // ---------------------------------------------------------------- 生命周期（MainActivity 的同一组入口）

    private fun foreground(cold: Boolean = false) {
        composeRule.runOnUiThread {
            clipboard.onForeground(cold, hasFocus = false)
            clipboard.onWindowFocus(true)
        }
        composeRule.waitForIdle()
    }

    private fun background() {
        composeRule.runOnUiThread {
            clipboard.onWindowFocus(false)
            clipboard.onBackground()
        }
        composeRule.waitForIdle()
    }

    private fun copy(text: String) {
        // 别的 App 复制的内容（不经本 App 的写入口，不计入写入次数）
        system.content = ClipRead.Text(text)
    }

    @SuppressLint("RestrictedApi")
    private fun routes(): List<String> =
        navController.currentBackStack.value
            .filter { it.destination.hasRoute(RouteDestination::class) }
            .map { it.toRoute<RouteDestination>().name }

    private fun parseRequests(): List<String> =
        synchronized(requests) { requests.filter { it.first.startsWith(PARSE_PATH) }.map { it.second } }

    private fun productRequests(): Int = synchronized(requests) { requests.count { it.first.startsWith("/v1/products/") } }

    private fun promptShown(): Boolean = clipboard.prompt.value != null

    private fun idleUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.waitForIdle()
            Thread.sleep(POLL_MS)
        }
        assertTrue(condition())
    }

    private fun assertNoRawInStorage(vararg fragments: String) {
        val stored = prefs().all.values.joinToString("|") { it.toString() }
        fragments.forEach { assertFalse("本机存储里不能有原文片段「$it」", stored.contains(it)) }
    }

    // ---------------------------------------------------------------- AC-S1-03 a / e

    @Test
    fun ac_s1_03_a_productLinkDirectlyParsesAndShowsCurrentPageCard() {
        launch()
        val raw = "【淘宝】$LINK 「降噪蓝牙耳机」 我的私人备注：周五前买"
        copy(raw)
        foreground(cold = true)
        idleUntil { clipboard.result.value is ClipboardResult.CardList }
        composeRule.onNodeWithTag(ClipboardUi.PROMPT_BAR).assertDoesNotExist()
        assertEquals(1, system.reads)
        assertEquals(1, parseRequests().size)
        val body = Json.parseToJsonElement(parseRequests().single()) as JsonObject
        assertEquals(setOf("text", "scene"), body.keys)
        assertEquals("$LINK\n降噪蓝牙耳机", (body["text"] as JsonPrimitive).content)
        assertEquals("clipboard", (body["scene"] as JsonPrimitive).content)
        assertFalse(parseRequests().single().contains("私人备注"))
        assertFalse(RouteNames.PRODUCT_DETAIL_FOR_TEST in routes())
        val card = clipboard.result.value as ClipboardResult.CardList
        assertEquals(1, card.rows.size)
        composeRule.onNodeWithText("降噪蓝牙耳机 5.3", substring = true).assertIsDisplayed()
        assertEquals(0, productRequests())
        assertEquals(0, system.writes)
        assertEquals(ClipRead.Text(raw), system.content)
        assertNoRawInStorage("e.tb.cn", "降噪", "备注")
        // G07：去重输入只含按提取顺序去重后的候选，以换行连接；标题和包装不参与。
        // 此用例没有 writer.write，不能由自写 ledger 的 HMAC 冒充商品摘要。
        val summaryStorage =
            prefs()
                .all
                .filterKeys { it.startsWith("hash.") }
                .values
                .joinToString("|")
        val persistedSalt = (ClipboardPreferences(context, SampleCipher).salt.read() as ClipboardSaltRead.Present).salt
        assertArrayEquals(SAMPLE_SALT, persistedSalt)
        assertTrue("商品摘要必须用持久盐 HMAC", summaryStorage.contains(ClipboardFingerprint(persistedSalt).hash(LINK)))
        assertFalse("商品摘要不能回退到无盐 SHA-256", summaryStorage.contains(ClipboardText.sha256(LINK)))
        composeRule.runOnUiThread { clipboard.openCard(card, checkNotNull(card.rows.single().card)) }
        idleUntil { routes().lastOrNull() == RouteNames.PRODUCT_DETAIL_FOR_TEST }
        assertEquals("点卡才进详情，仍用种子", 0, productRequests())
    }

    // ---------------------------------------------------------------- b / c

    @Test
    fun ac_s1_03_b_ordinaryTextNeitherPromptsNorRequests() {
        launch()
        copy("今天下午三点在楼下见面")
        foreground()
        assertEquals(1, system.reads)
        assertFalse(promptShown())
        assertEquals(0, parseRequests().size)
        assertEquals(0, system.writes)
    }

    @Test
    fun ac_s1_03_c_unchangedContentIsNotPromptedAgain() {
        launch()
        copy(LINK)
        foreground(cold = true)
        idleUntil { clipboard.result.value is ClipboardResult.CardList }
        val first = checkNotNull(clipboard.result.value)
        composeRule.runOnUiThread { clipboard.consumeResult(first) }
        background()
        foreground()
        composeRule.waitForIdle()
        assertEquals(2, system.reads)
        assertNull(clipboard.result.value)
        assertFalse(promptShown())
        assertEquals("同候选 TTL 内不再上传", 1, parseRequests().size)
        assertEquals(0, system.writes)
    }

    // ---------------------------------------------------------------- d：敏感页面

    /** 停在敏感页（经真实导航）回前台不读；返回首页后失焦再得焦也不补读。 */
    private fun assertSensitiveRoutes(routesToVisit: List<String>) {
        copy(LINK)
        for (route in routesToVisit) {
            composeRule.runOnUiThread { navController.navigate(RouteDestination(route, "{}", readonly = false)) }
            composeRule.waitForIdle()
            assertEquals(route, routes().last())
            background()
            foreground()
            assertEquals("停在 $route 回前台不读", 0, system.reads)
            composeRule.runOnUiThread { navController.popBackStack() }
            composeRule.waitForIdle()
            // 回到首页后失焦再得焦（下拉通知栏）：不补出
            composeRule.runOnUiThread {
                clipboard.onWindowFocus(false)
                clipboard.onWindowFocus(true)
            }
            composeRule.waitForIdle()
            assertEquals("返回首页后也不补读", 0, system.reads)
            assertFalse(promptShown())
        }
        assertEquals(0, system.writes)
    }

    @Test
    fun ac_s1_03_d_guestOnLoginDoesNotReadAndHomeDoesNotCatchUp() {
        launch()
        assertSensitiveRoutes(listOf(RouteNames.LOGIN))
    }

    @Test
    fun ac_s1_03_d_memberOnFindOrderDoesNotReadAndHomeDoesNotCatchUp() {
        launch(session = SessionState.PHONE_BOUND)
        assertSensitiveRoutes(listOf("FindOrder"))
    }

    @Test
    fun ac_s1_03_d_withdrawOnTopDoesNotRead() {
        launch()
        copy(LINK)
        // Withdraw 要实名（守卫不让游客进），这里按外壳回退栈出口交给剪贴板的同一个输入：栈顶是 Withdraw
        composeRule.runOnUiThread { clipboard.onBackStack("Withdraw", "entry-withdraw", setOf("entry-withdraw")) }
        background()
        foreground()
        assertEquals(0, system.reads)
        composeRule.runOnUiThread { clipboard.onBackStack(null, "entry-home", setOf("entry-home")) }
        composeRule.runOnUiThread {
            clipboard.onWindowFocus(false)
            clipboard.onWindowFocus(true)
        }
        assertEquals("返回首页后也不补读", 0, system.reads)
        assertFalse(promptShown())
    }

    // ---------------------------------------------------------------- Android 开关、基本模式、未获焦点

    @Test
    fun android_switchOffReadsNothingUntilThePasteButtonIsTapped() {
        launch(autoReadEnabled = false)
        copy(LINK)
        foreground(cold = true)
        assertEquals("开关关闭：回前台不读", 0, system.reads)
        assertFalse(promptShown())
        composeRule.onAllNodesWithTag(ClipboardScreens.HOME_PASTE_BUTTON).assertCountEquals(1)
        composeRule.onNodeWithTag(ClipboardScreens.HOME_PASTE_BUTTON).assertIsDisplayed()

        composeRule.onNodeWithTag(ClipboardScreens.HOME_PASTE_BUTTON).performClick()
        idleUntil { clipboard.result.value is ClipboardResult.CardList }
        assertEquals("点了才读", 1, system.reads)
        assertEquals(1, parseRequests().size)
        assertEquals(0, system.writes)
    }

    @Test
    fun android_guestSwitchOffSurvivesLogin() {
        launch()
        composeRule.runOnUiThread { clipboard.setAutoSwitch(false) }
        composeRule.runOnUiThread {
            sessionEpoch += 1
            routing.updateSession(SessionState.PHONE_BOUND, resumePending = false)
        }
        composeRule.waitForIdle()
        assertFalse("登录后开关仍为关闭", clipboard.autoSwitch.value)
        assertFalse("设备级：新进程读本机记录也是关闭", ClipboardPreferences(context).autoDetect)
        assertFalse("桥的旧键不当作用户选择", prefs().contains("auto_detect"))
    }

    @Test
    fun android_basicModeNeverReadsAndSettingsHasNoSwitch() {
        launch(decision = "basic_mode")
        copy(LINK)
        foreground(cold = true)
        assertEquals("基本模式不读", 0, system.reads)
        composeRule.runOnUiThread { navController.navigate(RouteDestination(SETTINGS, "{}", readonly = false)) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ClipboardScreens.SETTINGS_ROW).assertDoesNotExist()
        assertEquals(0, system.writes)
    }

    @Test
    fun android_settingsShowsTheSwitchForAConsentedGuest() {
        launch(autoReadEnabled = null)
        composeRule.runOnUiThread { navController.navigate(RouteDestination(SETTINGS, "{}", readonly = false)) }
        composeRule.waitForIdle()
        // FU-AND-0722 G01：游客缺省关闭；点击开启先出用途弹层，交给新文件专项覆盖。
        composeRule.onNodeWithTag(ClipboardScreens.SETTINGS_ROW).assertIsDisplayed()
        assertFalse(clipboard.autoSwitch.value)
        assertFalse(ClipboardPreferences(context).autoDetect)
    }

    @Test
    fun android_nullWithoutFocusIsNotEmptyAndAFocusedNullReadUsesUpTheForeground() {
        launch()
        system.content = ClipRead.Unavailable
        val beforeHash = prefs().all.filterKeys { it.startsWith("hash.") }
        composeRule.runOnUiThread { clipboard.onForeground(cold = true, hasFocus = false) }
        composeRule.waitForIdle()
        assertEquals("真正未获焦：不读，等本轮第一次获焦", 0, system.reads)
        composeRule.runOnUiThread { clipboard.onWindowFocus(true) }
        composeRule.waitForIdle()
        assertEquals(1, system.reads)
        assertFalse(promptShown())
        assertEquals("null 不记 hash", beforeHash, prefs().all.filterKeys { it.startsWith("hash.") })
        // A-R1-01：已获焦读到 null 也算本次前台读过。之后剪贴板有了商品链接，下拉再收起通知栏（失焦再得焦）不再读
        copy(LINK)
        repeat(REFOCUS_TIMES) {
            composeRule.runOnUiThread {
                clipboard.onWindowFocus(false)
                clipboard.onWindowFocus(true)
            }
            composeRule.waitForIdle()
        }
        assertEquals("一次前台最多一次自动读取", 1, system.reads)
        assertFalse(promptShown())
        assertEquals(0, parseRequests().size)
        assertNull(clipboard.result.value)
        // 真正经过后台再回来：重新计
        background()
        foreground()
        assertEquals(2, system.reads)
        idleUntil { clipboard.result.value is ClipboardResult.CardList }
        assertFalse(promptShown())
        assertEquals(0, system.writes)
    }

    // ---------------------------------------------------------------- AC-S1-63 邀请口令

    @Test
    fun ac_s1_63_guestConfirmKeepsPendingWithoutOpeningLogin() {
        launch()
        copy("好友邀请你加入凑狸 https://share.example.test/i/K7M2QX")
        foreground(cold = true)
        assertEquals("K7M2QX", clipboard.prompt.value?.inviteCode)
        composeRule.onNodeWithText("检测到好友邀请码 K7M2QX，绑定为邀请人？").assertIsDisplayed()
        composeRule.onNodeWithTag(ClipboardUi.PROMPT_ACTION).performClick()
        composeRule.waitForIdle()
        settleInviteAction()
        assertEquals("PageApi 入口无请求，包括可能被本地拒绝的操作", 0, requestEntries.get())
        assertFalse("确认后不打开 Login", RouteNames.LOGIN in routes())
        assertTrue((clipboard.pendingInvite.peek() != null))
        assertEquals("游客只记待绑定码，不发任何请求", 0, synchronized(requests) { requests.size })
        assertEquals("K7M2QX", clipboard.pendingInvite.peek())
        assertEquals(0, system.writes)
    }

    @Test
    fun ac_s1_63_b_keywordInviteAlsoPrompts() {
        launch()
        copy("邀请码 K7M2QX")
        foreground()
        assertEquals("invite", clipboard.prompt.value?.kind)
    }

    @Test
    fun ac_s1_63_d_productWinsOverInvite() {
        launch()
        copy("$LINK 邀请码 K7M2QX")
        foreground()
        idleUntil { clipboard.result.value is ClipboardResult.CardList }
        assertNull("商品优先，不出邀请条", clipboard.prompt.value)
        assertEquals(1, parseRequests().size)
        assertFalse(parseRequests().single().contains("K7M2QX"))
    }

    @Test
    fun ac_s1_63_loggedInUsersGetNoInvitePrompt() {
        launch(session = SessionState.PHONE_BOUND)
        copy("邀请码 K7M2QX")
        foreground()
        // 已登录：可绑定只看 getMe（契约缺补填接口，G01），界面不出邀请条；本人码、已有上级同样不提示
        assertFalse(promptShown())
        assertEquals("不发 parse，也没有任何绑定请求", 0, synchronized(requests) { requests.count { !it.first.startsWith("/v1/me") } })
    }

    // ---------------------------------------------------------------- 迟到与作废

    private fun startParseThenHold() {
        hold = CountDownLatch(1)
        copy(LINK)
        foreground()
        idleUntil { clipboard.parseCount > 0 } // 0724：自动直接 parse，不点击商品提示条
        idleUntil { parseRequests().size == 1 }
    }

    private fun releaseAndAssertDropped() {
        hold?.countDown()
        idleUntil { activeApiCalls.get() == 0 && !clipboard.busy.value }
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()
        assertFalse("迟到的响应不跳详情", RouteNames.PRODUCT_DETAIL_FOR_TEST in routes())
        assertNull("不出卡、不提示", clipboard.result.value)
        assertFalse(promptShown())
        assertEquals(0, system.writes)
    }

    @Test
    fun late_backgroundDropsTheResponse() {
        launch()
        startParseThenHold()
        background()
        releaseAndAssertDropped()
    }

    /**
     * A-R1-03：点「AI 助手」Tab（真实 TabBar → Router → onOpenTab），在同一条主线程消息里、下一次重组之前 parse 响应就到了：
     * 作废必须已在点击的同步出口完成，响应只丢弃。
     */
    @Test
    fun late_tabClickDropsAResponseArrivingBeforeTheNextRecomposition() {
        launch()
        val reply = CompletableDeferred<ApiResult>()
        gated = reply
        copy(LINK)
        foreground()
        idleUntil { clipboard.parseCount == 1 }
        val tab = composeRule.onNodeWithText(ShellDraftTexts.TAB_AGENT).fetchSemanticsNode()
        composeRule.runOnUiThread {
            checkNotNull(tab.config[SemanticsActions.OnClick].action).invoke()
            assertFalse(clipboard.homeRoot())
            reply.complete(ApiResult.Success(Json.parseToJsonElement(singleCard()), "trace-000123", null))
        }
        idleUntil { !clipboard.busy.value }
        assertFalse(RouteNames.PRODUCT_DETAIL_FOR_TEST in routes())
        assertNull(clipboard.result.value)
        assertEquals(0, system.writes)
    }

    @Test
    fun late_enteringASecondLevelPageDropsTheResponse() {
        launch()
        startParseThenHold()
        // 经 Router 进二级页（回退栈变化的同步出口）：首页世代换代
        composeRule.runOnUiThread {
            routing.router.open(
                com.couli.core.router
                    .RouteRequest("Search", JsonObject(emptyMap()), com.couli.core.router.RouteSource.IN_APP),
            )
        }
        composeRule.waitForIdle()
        assertEquals("Search", routes().last())
        releaseAndAssertDropped()
    }

    @Test
    fun late_withdrawingConsentDropsTheResponseAndClearsRecords() {
        launch()
        startParseThenHold()
        val summary = ClipboardFingerprint(SAMPLE_SALT).hash(LINK)
        assertTrue(
            prefs()
                .all
                .filterKeys { it.startsWith("hash.") }
                .values
                .joinToString("|")
                .contains(summary),
        )
        composeRule.runOnUiThread { privacy.withdraw() }
        releaseAndAssertDropped()
        assertTrue("撤回同意清掉商品摘要", prefs().all.keys.none { it.startsWith("hash.") })
        assertTrue("撤回同意清掉自写记录", clipboard.ledger.hashes().isEmpty())
    }

    @Test
    fun late_switchingAccountDropsTheResponse() {
        launch()
        startParseThenHold()
        composeRule.runOnUiThread {
            sessionEpoch += 1
            routing.updateSession(SessionState.PHONE_BOUND, resumePending = false)
        }
        releaseAndAssertDropped()
    }

    @Test
    fun rateLimitDisablesEveryPasteEntry() {
        parseAnswer = { envelope(42901, "null").setResponseCode(429).setHeader("Retry-After", "7") }
        launch()
        copy(LINK)
        foreground()
        idleUntil { clipboard.parseCount > 0 } // 0724：自动直接 parse，不点击商品提示条
        idleUntil { clipboard.blocked.value }
        val reads = system.reads
        composeRule.onNodeWithTag(ClipboardScreens.HOME_PASTE_BUTTON).performClick()
        composeRule.waitForIdle()
        assertEquals("限流中点粘贴卡：不读、不发", reads, system.reads)
        assertEquals(1, parseRequests().size)
        assertEquals(0, system.writes)
    }

    // ---------------------------------------------------------------- App 自写

    @Test
    fun selfWrittenContentIsNotPrompted() {
        launch()
        // 复制口令（AndroidJumpPorts.copy_tpwd 经唯一写入口）
        AndroidJumpPorts(context, writer = clipboard.writer).copy("￥AbCdEf12￥ $LINK")
        foreground()
        assertFalse(promptShown())
        // 桥 clipboard.write（AppWeb 同一个写入口）
        clipboard.writer.write("邀请码 K7M2QX $LINK")
        background()
        foreground()
        assertFalse(promptShown())
        assertEquals("只有两次自写", 2, system.writes)
        assertEquals(0, parseRequests().size)
        assertNoRawInStorage("AbCdEf12", "K7M2QX")
    }

    // ---------------------------------------------------------------- 网页抑制（G06）

    @Test
    fun webSuppressionIsScopedToTheContainerAndLeavesTheSwitchAlone() {
        launch()
        copy(LINK)
        routing.webTargets = { WebTargets(bridgeOrigins = listOf(H5_ORIGIN)) }
        composeRule.runOnUiThread {
            navController.navigate(RouteDestination(RouteNames.WEB_PAGE, """{"url":"$H5_ORIGIN/activity"}""", readonly = false))
        }
        composeRule.waitForIdle()
        assertEquals(RouteNames.WEB_PAGE, routes().last())
        val entry = checkNotNull(navController.currentBackStackEntry).id
        composeRule.runOnUiThread { clipboard.setWebAutoDetect(entry, enabled = false) }
        background()
        foreground()
        assertEquals("抑制的网页在栈顶：不读", 0, system.reads)
        composeRule.runOnUiThread { navController.popBackStack() }
        composeRule.waitForIdle()
        background()
        foreground()
        assertEquals("网页关闭后下一次回前台恢复识别", 1, system.reads)
        idleUntil { clipboard.result.value is ClipboardResult.CardList }
        assertFalse(promptShown())
        assertTrue("设置页开关不受影响", clipboard.autoSwitch.value)
    }

    // ---------------------------------------------------------------- 结果映射

    @Test
    fun manyCardsAreListedInASheetOnTheHomePage() {
        parseAnswer = { envelope(0, twoResults()) }
        launch()
        copy(LINK)
        foreground()
        idleUntil { clipboard.parseCount > 0 } // 0724：自动直接 parse，不点击商品提示条
        idleUntil { clipboard.result.value is ClipboardResult.CardList }
        composeRule.onNodeWithTag(ClipboardScreens.CARD_LIST).assertIsDisplayed()
        composeRule.onNodeWithText("暂不支持这个链接或平台", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(ClipboardScreens.CARD_PREFIX + 0).performClick()
        idleUntil { routes().lastOrNull() == RouteNames.PRODUCT_DETAIL_FOR_TEST }
        assertEquals(0, productRequests())
    }

    @Test
    fun manualPasteWithoutCandidatesFinishesSilently() {
        launchForManualPaste()
        copy("￥12345678901￥「全脂纯牛奶 250ml」")
        pasteHome()
        idleUntil { system.reads == 1 && activeApiCalls.get() == 0 && !clipboard.busy.value }
        assertEquals("无候选不上传", 0, parseRequests().size)
        assertNull("无候选安静结束，不弹失败", clipboard.result.value)
        assertFalse("不擅自导航", "Search" in routes())
        assertEquals(0, system.writes)
    }

    // ---------------------------------------------------------------- 第 3 轮评审（A-R3-01：手动粘贴与自动识别同一套上传过滤）

    /** 已同意的游客关掉自动识别，手动粘贴纯邀请落地页：不发 parse，只本地抽码；确认后经 Router 预填登录页，全程不发请求。 */
    @Test
    fun manualPasteOfAPureInviteLinkKeepsPendingWithoutOpeningLogin() {
        launch(autoReadEnabled = false)
        copy("邀请码 K7M2QX https://share.example.test/i/K7M2QX")
        foreground(cold = true)
        assertEquals("开关关闭：回前台不读", 0, system.reads)
        composeRule.onNodeWithTag(ClipboardScreens.HOME_PASTE_BUTTON).performClick()
        composeRule.waitForIdle()
        idleUntil { activeApiCalls.get() == 0 && !clipboard.busy.value }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("邀请落地页不上传 parse", 0, parseRequests().size)
        assertEquals("邀请码只在本地抽取", "K7M2QX", (clipboard.result.value as ClipboardResult.InviteConfirm).code)
        composeRule.onNodeWithTag(ClipboardScreens.INVITE_CONFIRM).assertIsDisplayed()
        composeRule.onNodeWithText("检测到好友邀请码 K7M2QX，绑定为邀请人？").assertIsDisplayed()
        assertFalse("确认前不预填", (clipboard.pendingInvite.peek() != null))

        composeRule.onNodeWithTag(ClipboardUi.PROMPT_ACTION).performClick()
        composeRule.waitForIdle()
        settleInviteAction()
        assertEquals("PageApi 入口无请求，包括可能被本地拒绝的操作", 0, requestEntries.get())
        assertFalse("确认后不打开 Login", RouteNames.LOGIN in routes())
        assertEquals("K7M2QX", clipboard.pendingInvite.peek())
        assertEquals("游客只记待绑定码，不发任何请求", 0, synchronized(requests) { requests.size })
        assertEquals(0, system.writes)
    }

    /**
     * F-AND-22（APP-07 R3F-01、共性第 16 条）：手动粘贴邀请内容出确认条；再手动粘贴商品链接，parse 在途；旧 parse 返回 42901（禁用期启动、
     * 结果换成提示），界面重组之前旧确认条的点击才到：执行层核对共享限流与「该结果仍是当前结果」，不预填、不导航。
     * FU-AND-0710 R1-05 调整：商品 parse 在途时所有粘贴入口共享 busy（不读、不发），原先「parse 在途时再粘贴邀请内容」的顺序已不可能发生，
     * 改为先出确认条、再发商品 parse，验证的仍是同一件事（42901 替换结果之后、重组之前的旧确认点击零动作）。
     */
    @Test
    fun fAnd22_aConfirmArrivingBeforeTheNextFrameAfterARateLimitedParseDoesNothing() {
        launch()
        foreground(cold = true)
        copy("邀请码 K7M2QX https://share.example.test/i/K7M2QX")
        composeRule.runOnUiThread { clipboard.paste(ManualPasteAction.ENTRY_HOME) }
        composeRule.waitForIdle()
        val confirm = clipboard.result.value as ClipboardResult.InviteConfirm
        copy(LINK)
        val reply = CompletableDeferred<ApiResult>()
        gated = reply
        composeRule.runOnUiThread { clipboard.paste(ManualPasteAction.ENTRY_HOME) }
        composeRule.waitForIdle()
        assertEquals("parse 已发出（停在闸门）", 1, clipboard.parseCount)
        assertTrue("前提：确认条仍是当前结果", clipboard.result.value === confirm)
        var replaced = false
        var prefilled = true
        composeRule.runOnUiThread {
            val effect = ErrorEffect(RATE_LIMITED, ErrorEffectKind.DISABLE_BUTTON, ReplayMode.NONE, false, disableMillis = RATE_LIMIT_MS)
            reply.complete(ApiResult.Failure(RATE_LIMITED, "", JsonObject(emptyMap()), "trace-000429", effect, null))
            // 同一条消息里、下一次重组之前：旧确认条的按钮回调
            replaced = clipboard.blocked.value && clipboard.result.value !== confirm
            clipboard.confirmManualInvite(confirm)
            prefilled = (clipboard.pendingInvite.peek() != null)
        }
        assertTrue("前提：42901 已启动禁用期、结果已被替换", replaced)
        assertFalse("不预填", prefilled)
        composeRule.waitForIdle()
        assertFalse("不导航到登录页", RouteNames.LOGIN in routes())
        assertEquals(0, system.writes)
    }

    // ---------------------------------------------------------------- FU-AND-0710 第 1 轮评审 R1-05：共享 busy

    private fun transportFailure() = ApiResult.TransportFailure("io", null, null, false)

    /** R1-05：同页同目的手动任务在途时再次点击：不读、不发；结束后入口恢复。跨页有效点击由 ImportTiming0724Test 单独覆盖。 */
    @Test
    fun r1_05_samePageManualDoubleTapCannotReadOrParseAgain() {
        launchForManualPaste()
        copy(LINK)
        val reply = CompletableDeferred<ApiResult>()
        gatedQueue.addLast(reply)
        pasteHome()
        idleUntil { clipboard.parseCount == 1 }
        assertTrue(clipboard.busy.value)
        composeRule.onNodeWithTag(ClipboardScreens.HOME_PASTE_BUTTON).assertIsNotEnabled()
        val reads = system.reads
        pasteHome()
        assertEquals("同页同目的连点不能再次读", reads, system.reads)
        assertEquals(1, clipboard.parseCount)
        composeRule.runOnUiThread { reply.complete(transportFailure()) }
        idleUntil { !clipboard.busy.value }
        composeRule.onNodeWithTag(ClipboardScreens.HOME_PASTE_BUTTON).assertIsEnabled()
    }

    /** R1-05 反向：手动商品 parse 在途：已经退役的商品提示回调不得发请求，同页再次点击也不读不发。 */
    @Test
    fun r1_05_retiredProductPromptCallbackCannotStartAnotherParse() {
        launchForManualPaste()
        copy(LINK)
        val reply = CompletableDeferred<ApiResult>()
        gatedQueue.addLast(reply)
        pasteHome()
        idleUntil { clipboard.parseCount == 1 }
        assertNull("商品流程没有提示条", clipboard.prompt.value)
        val reads = system.reads
        composeRule.runOnUiThread {
            clipboard.onPromptAction(-1)
            clipboard.paste(ManualPasteAction.ENTRY_HOME)
        }
        composeRule.waitForIdle()
        assertEquals(reads, system.reads)
        assertEquals(1, clipboard.parseCount)
        composeRule.runOnUiThread { reply.complete(transportFailure()) }
        idleUntil { !clipboard.busy.value }
    }

    /**
     * R1-05：连续手动粘贴，响应乱序。第一次 parse（停在闸门 A）在途时再粘贴：共享 busy 下不读、不发；若第二次真的发出（busy 被去掉的变异），
     * 后发的先回（网络错误提示）、先发的迟到成功（单卡在原页出卡）也不得覆盖它——每次手动 parse 推进世代。两层分别断言，失败信息可区分。
     */
    @Test
    fun r1_05_consecutiveManualPastesNeverLetALateResultOverrideTheNewerOne() {
        launch()
        foreground(cold = true)
        copy(LINK)
        val first = CompletableDeferred<ApiResult>()
        val second = CompletableDeferred<ApiResult>()
        gatedQueue.addLast(first)
        gatedQueue.addLast(second)
        composeRule.runOnUiThread { clipboard.paste(ManualPasteAction.ENTRY_HOME) }
        composeRule.waitForIdle()
        val readsAfterFirst = system.reads
        composeRule.runOnUiThread { clipboard.paste(ManualPasteAction.ENTRY_HOME) }
        composeRule.waitForIdle()
        val sent = clipboard.parseCount
        val readsAfterSecond = system.reads
        composeRule.runOnUiThread { second.complete(transportFailure()) }
        composeRule.waitForIdle()
        val newer = clipboard.result.value
        composeRule.runOnUiThread { first.complete(ApiResult.Success(Json.parseToJsonElement(singleCard()), "trace-000123", null)) }
        composeRule.waitForIdle()
        if (sent == 2) {
            assertFalse("迟到的先发结果不覆盖后发结果：不跳详情", RouteNames.PRODUCT_DETAIL_FOR_TEST in routes())
            assertTrue("后发结果仍在", clipboard.result.value === newer && newer != null)
        }
        assertEquals("共享 busy：第二次粘贴不读剪贴板", readsAfterFirst, readsAfterSecond)
        assertEquals("共享 busy：第二次粘贴不发 parse", 1, sent)
    }

    // ---------------------------------------------------------------- FU-AND-0607：APP-07 共性第 17–20 条

    /** 手动粘贴、自动识别关掉、回到前台（parse 的归属要求在前台）。 */
    private fun launchForManualPaste(session: SessionState = SessionState.GUEST) {
        launch(session = session, autoReadEnabled = false)
        foreground(cold = true)
    }

    private fun pasteHome() {
        composeRule.runOnUiThread { clipboard.paste(ManualPasteAction.ENTRY_HOME) }
        composeRule.waitForIdle()
    }

    /** 第 17 条：G14：App 自写内容经手动入口照常识别，重复手动点击不受 TTL 影响。 */
    @Test
    fun lesson17_manualPasteIncludesSelfWrittenContentWithoutDedup() {
        launchForManualPaste()
        composeRule.runOnUiThread { assertTrue(clipboard.writer.write(LINK, "tpwd")) }
        pasteHome()
        idleUntil { clipboard.result.value is ClipboardResult.CardList }
        assertEquals("G14 手动识别自写内容", 1, parseRequests().size)
        val first = checkNotNull(clipboard.result.value)
        composeRule.runOnUiThread { clipboard.consumeResult(first) }
        pasteHome()
        idleUntil { clipboard.result.value is ClipboardResult.CardList }
        assertEquals("同内容手动再次识别", 2, parseRequests().size)
        assertEquals(1, system.writes)
    }

    /** 第 18 条：旧结果的回调（关闭、用商品名搜索）只认渲染时那一条，不清掉、不执行内容相同的新结果；提示条回调绑定提示编号。 */
    @Test
    fun lesson18_oldResultCallbacksDoNotActOnTheCurrentResult() {
        parseAnswer = { envelope(UNRECOGNIZED, "null") }
        launchForManualPaste()
        copy("$LINK 「全脂纯牛奶 250ml」看不懂的口令")
        pasteHome()
        idleUntil { clipboard.result.value is ClipboardResult.Unrecognized }
        val first = clipboard.result.value as ClipboardResult.Unrecognized
        copy("$LINK 「低脂酸奶 100g」另一段口令")
        pasteHome()
        idleUntil { clipboard.result.value.let { it is ClipboardResult.Unrecognized && it.id != first.id } }
        val second = clipboard.result.value as ClipboardResult.Unrecognized
        assertTrue("新结果已替换旧结果", first != second)
        composeRule.runOnUiThread {
            clipboard.consumeResult(first)
            clipboard.searchByName(first)
        }
        composeRule.waitForIdle()
        assertTrue("旧关闭回调不清掉新结果", clipboard.result.value === second)
        assertFalse("旧回调不按旧结果去搜索", "Search" in routes())
        val parseBeforeShare = parseRequests().size
        // 提示条：旧编号的回调不动当前分享提示
        composeRule.runOnUiThread { importPreferences.enable("1") }
        composeRule.runOnUiThread { clipboard.setAutoSwitch(true) }
        composeRule.runOnUiThread {
            clipboard.receiveShare("text/plain", LINK)
            clipboard.onShareDelivered()
        }
        composeRule.waitForIdle()
        val prompt = checkNotNull(clipboard.prompt.value)
        composeRule.runOnUiThread {
            clipboard.onPromptClose(prompt.id - 1)
            clipboard.onPromptAction(prompt.id - 1)
        }
        composeRule.waitForIdle()
        assertTrue("旧编号的关闭不清掉当前提示", clipboard.prompt.value?.id == prompt.id)
        assertEquals("旧编号的按钮不上传", parseBeforeShare, parseRequests().size)
    }

    /** 第 19 条：已登录用户粘贴纯邀请内容：不发 parse，不落入 30132「识别不了」，不预填。 */
    @Test
    fun lesson19_aSignedInPasteOfPureInviteContentStaysSilent() {
        launchForManualPaste(session = SessionState.PHONE_BOUND)
        copy("邀请码 K7M2QX")
        pasteHome()
        copy("https://share.example.test/i/K7M2QX")
        pasteHome()
        assertEquals(0, parseRequests().size)
        assertNull("不出「识别不了」", clipboard.result.value)
        assertFalse((clipboard.pendingInvite.peek() != null))
    }

    /** 第 20 条：parse 外层成功、单条结果 error_code=10405：主动进入强更分流；多结果里有 10405 同理。 */
    @Test
    fun lesson20_anInlineForceUpdateEntersTheForceUpdateFlow() {
        var forced = 0
        parseAnswer = { envelope(0, """{"results":[{"hit":{"platform":"taobao","kind":"url","raw":"$LINK"},"error_code":10405}]}""") }
        launchForManualPaste()
        composeRule.runOnUiThread { clipboard.onForceUpdate = { forced += 1 } }
        copy(LINK)
        pasteHome()
        idleUntil { forced == 1 }
        parseAnswer = {
            envelope(
                0,
                """{"results":[{"hit":{"platform":"taobao","kind":"url","raw":"$LINK"},"card":${card("tb:7Kq9LmN3pQ", "降噪蓝牙耳机 5.3")}},""" +
                    """{"hit":{"platform":"jd","kind":"url","raw":"https://u.jd.com/x"},"error_code":10405}]}""",
            )
        }
        copy("$LINK https://u.jd.com/x")
        pasteHome()
        idleUntil { forced == 2 }
    }

    // ---------------------------------------------------------------- FU-AND-0607 第 2 轮：F1-04、F1-05

    /** 一张卡 + 一条 42901 + 一条 30132（命中口令带「」标题）。 */
    private fun rateLimitedMixedResults(): String {
        val first = """{"hit":{"platform":"taobao","kind":"url","raw":"$LINK"},"card":${card("tb:7Kq9LmN3pQ", "降噪蓝牙耳机 5.3")}}"""
        val limited = """{"hit":{"platform":"jd","kind":"url","raw":"https://u.jd.com/x"},"error_code":42901}"""
        val unrec = """{"hit":{"platform":"taobao","kind":"tpwd","raw":"￥Zx8Cv7Bn6Mq￥「全脂纯牛奶 250ml」"},"error_code":30132}"""
        return """{"results":[$first,$limited,$unrec]}"""
    }

    /**
     * F1-04（APP-07 共性第 16 条）：多条结果里有 42901（共享禁用期已启动），同一弹层里的商品卡与 30132【用商品名搜索】界面置灰，
     * 执行层 openCard / searchByName 也不执行（不开详情、不预填搜索）。
     */
    @Test
    fun f1_04_everyResultButtonHonoursTheSharedRateLimit() {
        parseAnswer = { envelope(0, rateLimitedMixedResults()) }
        launch()
        copy(LINK)
        foreground()
        idleUntil { clipboard.parseCount > 0 } // 0724：自动直接 parse，不点击商品提示条
        idleUntil { clipboard.result.value is ClipboardResult.CardList }
        assertTrue("行内 42901 启动禁用期", clipboard.blocked.value)
        val list = clipboard.result.value as ClipboardResult.CardList
        composeRule.onNodeWithTag(ClipboardScreens.CARD_LIST).assertIsDisplayed()
        composeRule.onNodeWithTag(ClipboardScreens.CARD_PREFIX + 0).assertIsNotEnabled()
        composeRule.onNodeWithTag(ClipboardScreens.ROW_SEARCH_BY_NAME_PREFIX + 2).assertIsDisplayed().assertIsNotEnabled()
        val before = routes()
        composeRule.runOnUiThread {
            clipboard.openCard(list, checkNotNull(list.rows[0].card))
            clipboard.searchByName(list, list.rows[2])
        }
        composeRule.waitForIdle()
        assertEquals("执行层不开详情、不去搜索", before, routes())
        assertTrue("结果仍在（没有被按钮消费）", clipboard.result.value === list)
        assertEquals(0, productRequests())
    }

    /**
     * F1-05（APP-07 共性第 26 条）：内容完全相同的两次结果编号不同，StateFlow 不合并；第一次结果的关闭、【用商品名搜索】、邀请确认回调
     * 一律无效，只认第二次。
     */
    @Test
    fun f1_05_anIdenticalSecondResultInvalidatesTheFirstResultsCallbacks() {
        parseAnswer = { envelope(UNRECOGNIZED, "null") }
        launchForManualPaste()
        val text = "$LINK 「全脂纯牛奶 250ml」看不懂的口令"
        copy(text)
        pasteHome()
        idleUntil { clipboard.result.value is ClipboardResult.Unrecognized }
        val first = clipboard.result.value as ClipboardResult.Unrecognized
        copy(text)
        pasteHome()
        idleUntil { clipboard.result.value.let { it is ClipboardResult.Unrecognized && it.id != first.id } }
        val second = clipboard.result.value as ClipboardResult.Unrecognized
        assertEquals("内容相同", first.copy(id = second.id), second)
        assertTrue("编号不同：第二次发布没有被合并", first.id != second.id)
        composeRule.runOnUiThread {
            clipboard.consumeResult(first)
            clipboard.searchByName(first)
        }
        composeRule.waitForIdle()
        assertTrue("旧关闭回调不清掉内容相同的新结果", clipboard.result.value === second)
        assertFalse("旧【用商品名搜索】不执行", SEARCH in routes())

        // 邀请确认：同一个码的两段邀请文案连续粘贴，旧确认回调不预填，新的才预填（同一原文 24 小时内第二次手动粘贴按 BR-INV-04 静默，FU-X-INVITE-MANUAL-DEDUP）
        val invite = "邀请码 K7M2QX https://share.example.test/i/K7M2QX"
        copy(invite)
        pasteHome()
        val oldConfirm = clipboard.result.value as ClipboardResult.InviteConfirm
        copy("https://share.example.test/i/K7M2QX 邀请码 K7M2QX")
        pasteHome()
        val newConfirm = clipboard.result.value as ClipboardResult.InviteConfirm
        assertTrue(oldConfirm.id != newConfirm.id)
        composeRule.runOnUiThread { clipboard.confirmManualInvite(oldConfirm) }
        composeRule.waitForIdle()
        assertFalse("旧确认回调不预填", (clipboard.pendingInvite.peek() != null))
        assertTrue(clipboard.result.value === newConfirm)
        composeRule.runOnUiThread { clipboard.confirmManualInvite(newConfirm) }
        composeRule.waitForIdle()
        assertTrue("当前确认回调照常预填", (clipboard.pendingInvite.peek() != null))
    }

    /** SearchTyping 的「粘贴查返利」同一套过滤：纯邀请落地页不上传。 */
    @Test
    fun searchTypingPasteOfAPureInviteLinkNeverUploads() {
        launch()
        foreground(cold = true)
        composeRule.runOnUiThread { routing.router.open(RouteRequest(SEARCH, JsonObject(emptyMap()), RouteSource.IN_APP)) }
        composeRule.waitForIdle()
        copy("https://share.example.test/i/K7M2QX")
        composeRule.onNodeWithTag(ClipboardUi.PASTE_ENTRY).performClick()
        composeRule.waitForIdle()
        idleUntil { activeApiCalls.get() == 0 && !clipboard.busy.value }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("邀请落地页不上传 parse", 0, parseRequests().size)
        composeRule.onNodeWithTag(ClipboardScreens.INVITE_CONFIRM).assertIsDisplayed()
        assertEquals(0, system.writes)
    }

    /** 已登录用户手动粘贴纯邀请落地页：不上传 parse，按 G01 不出邀请确认、不发绑定请求。 */
    @Test
    fun memberManualPasteOfAPureInviteLinkNeitherUploadsNorPrompts() {
        launch(session = SessionState.PHONE_BOUND)
        foreground(cold = true)
        copy("https://share.example.test/i/K7M2QX")
        composeRule.onNodeWithTag(ClipboardScreens.HOME_PASTE_BUTTON).performClick()
        composeRule.waitForIdle()
        idleUntil { activeApiCalls.get() == 0 && !clipboard.busy.value }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("邀请落地页不上传 parse", 0, parseRequests().size)
        assertNull("已登录不出邀请确认（G01）", clipboard.result.value)
        assertEquals("没有任何绑定请求", 0, synchronized(requests) { requests.count { !it.first.startsWith("/v1/me") } })
    }

    /** 商品与邀请混合：只上传商品链接与「」标题，请求体里没有邀请落地页、邀请码与其余原文。 */
    @Test
    fun manualPasteOfProductAndInviteUploadsOnlyTheProductPart() {
        launch()
        // App 在前台（剪贴板还是空的：首页这次自动读取什么都不出）
        foreground(cold = true)
        copy("【淘宝】$LINK 「降噪蓝牙耳机」 好友邀请你 https://share.example.test/i/K7M2QX 邀请码 K7M2QX")
        composeRule.onNodeWithTag(ClipboardScreens.HOME_PASTE_BUTTON).performClick()
        idleUntil { clipboard.result.value is ClipboardResult.CardList }
        val body = parseRequests().single()
        assertEquals("只上传商品片段", "$LINK\n降噪蓝牙耳机", ((Json.parseToJsonElement(body) as JsonObject)["text"] as JsonPrimitive).content)
        assertFalse("请求体不含邀请落地页", body.contains("share.example.test"))
        assertFalse("请求体不含邀请码", body.contains("K7M2QX"))
        assertEquals(0, system.writes)
    }

    @Test
    fun emptyClipboardOnManualPasteTerminatesAndKeepsEntry() {
        launchForManualPaste()
        system.content = ClipRead.Unavailable
        pasteHome()
        idleUntil { system.reads == 1 && activeApiCalls.get() == 0 && !clipboard.busy.value }
        assertEquals(1, system.reads)
        assertEquals(0, parseRequests().size)
        assertNull(clipboard.result.value)
        assertFalse("空内容不自动导航", "Search" in routes())
        composeRule.onNodeWithTag(ClipboardUi.HOME_PASTE_CARD).assertIsDisplayed()
    }

    @Test
    fun serverErrorShowsTrace6() {
        parseAnswer = { envelope(50001, "null") }
        launch()
        copy(LINK)
        foreground()
        idleUntil { clipboard.parseCount > 0 } // 0724：自动直接 parse，不点击商品提示条
        idleUntil { clipboard.result.value is ClipboardResult.Notice }
        val notice = clipboard.result.value as ClipboardResult.Notice
        assertTrue("5xxxx 带 trace_id 末 6 位", notice.text.endsWith("（000123）"))
    }

    // ---------------------------------------------------------------- 第 1 轮评审（A-R1-02 / 04 / 05 / 06 / 07）

    private fun openSearchAndPaste(sent: () -> Boolean) {
        // App 在前台（剪贴板还是空的：首页这次自动读取什么都不出）
        foreground(cold = true)
        composeRule.runOnUiThread { routing.router.open(RouteRequest(SEARCH, JsonObject(emptyMap()), RouteSource.IN_APP)) }
        composeRule.waitForIdle()
        assertEquals(SEARCH, routes().last())
        copy(LINK)
        composeRule.onNodeWithTag(ClipboardUi.PASTE_ENTRY).performClick()
        idleUntil(sent)
    }

    private fun openSearchAndPasteThenHold() {
        hold = CountDownLatch(1)
        openSearchAndPaste { parseRequests().size == 1 }
    }

    /**
     * A-R1-02：SearchTyping 粘贴后 parse 在途，被另一页覆盖再返回同一个搜索页实例，之后响应才到：离开前发起的 parse 仍作废。
     * 响应由测试在回到搜索页之后给出（[gated]）：真实传输层每 50ms 轮询一次归属，覆盖得久时会先取消请求，这里固定成「回来之后才到」的时序。
     */
    @Test
    fun late_coveringThenReturningToTheSameSearchPageStillDropsTheResponse() {
        launch()
        val reply = CompletableDeferred<ApiResult>()
        gated = reply
        openSearchAndPaste { clipboard.parseCount == 1 }
        val searchEntry = checkNotNull(navController.currentBackStackEntry).id
        composeRule.runOnUiThread { routing.router.open(RouteRequest(SETTINGS, JsonObject(emptyMap()), RouteSource.IN_APP)) }
        composeRule.waitForIdle()
        assertEquals(SETTINGS, routes().last())
        composeRule.runOnUiThread { navController.popBackStack() }
        composeRule.waitForIdle()
        assertEquals("回到同一个搜索页实例", searchEntry, checkNotNull(navController.currentBackStackEntry).id)
        composeRule.runOnUiThread { reply.complete(ApiResult.Success(Json.parseToJsonElement(singleCard()), "trace-000123", null)) }
        composeRule.waitForIdle()
        assertFalse("迟到的响应不跳详情", RouteNames.PRODUCT_DETAIL_FOR_TEST in routes())
        assertNull("不出卡、不提示", clipboard.result.value)
        assertEquals(SEARCH, routes().last())
        assertEquals(0, system.writes)
    }

    /**
     * A-R1-04：SearchTyping 粘贴后 parse 在途，接着在同一搜索页搜索并点购买，出现 AuthSheet（发起页、会话、前台都没变）：迟到的 parse
     * 只丢弃；AuthSheet 关掉之后旧请求也不恢复。
     */
    @Test
    fun late_authSheetOnTheSamePageDropsTheResponseEvenAfterItCloses() {
        launch(session = SessionState.PHONE_BOUND, withTrade = true)
        openSearchAndPasteThenHold()
        composeRule.onNodeWithTag(SearchTags.INPUT).performTextInput("耳机")
        composeRule.onNodeWithTag(SearchTags.SUBMIT).performClick()
        idleUntil { composeRule.onAllNodesWithText("降噪蓝牙耳机 5.3", substring = true).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onAllNodesWithText("领券购买", substring = true).onFirst().performClick()
        idleUntil { composeRule.onAllNodesWithTag(TradeTags.AUTH_SHEET, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        // 关掉 AuthSheet：浮层关闭后也不能恢复离开前的 parse
        composeRule.onAllNodesWithContentDescription(AUTH_SHEET_CLOSE).onLast().performClick()
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithTag(TradeTags.AUTH_SHEET, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertEquals(SEARCH, routes().last())
        releaseAndAssertDropped()
    }

    /** A-R1-05：多条结果里 30132 给【用商品名搜索】（预填这一行的「」内文字）、5xxxx 带 trace6；卡片列表保留 trace_id。 */
    @Test
    fun manyResultsHandleEachRowErrorByItsCode() {
        parseAnswer = { envelope(0, mixedResults()) }
        launch()
        copy(LINK)
        foreground()
        idleUntil { clipboard.parseCount > 0 } // 0724：自动直接 parse，不点击商品提示条
        idleUntil { clipboard.result.value is ClipboardResult.CardList }
        val list = clipboard.result.value as ClipboardResult.CardList
        assertEquals("多结果保留 trace_id", "trace-000123", list.traceId)
        composeRule.onNodeWithTag(ClipboardScreens.CARD_LIST).assertIsDisplayed()
        composeRule.onNodeWithText("（000123）", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(ClipboardScreens.ROW_SEARCH_BY_NAME_PREFIX + 1).assertIsDisplayed()
        composeRule.onNodeWithTag(ClipboardScreens.ROW_SEARCH_BY_NAME_PREFIX + 2).assertDoesNotExist()
        composeRule.onNodeWithTag(ClipboardScreens.ROW_SEARCH_BY_NAME_PREFIX + 1).performClick()
        composeRule.waitForIdle()
        assertEquals(SEARCH, routes().last())
        val params = checkNotNull(navController.currentBackStackEntry).toRoute<RouteDestination>().paramsJson
        assertTrue("预填这一行的「」内文字", params.contains("全脂纯牛奶 250ml"))
        assertEquals(0, system.writes)
    }

    /** A-R1-05：多条结果里有一条 42901：启动共享禁用期（所有粘贴入口置灰，执行层不读）。 */
    @Test
    fun manyResultsWithARateLimitedRowStartTheSharedBlock() {
        parseAnswer = { envelope(0, rateLimitedRow()) }
        launch()
        copy(LINK)
        foreground()
        idleUntil { clipboard.parseCount > 0 } // 0724：自动直接 parse，不点击商品提示条
        idleUntil { clipboard.result.value is ClipboardResult.CardList }
        assertTrue("行内 42901 启动禁用期", clipboard.blocked.value)
        composeRule.runOnUiThread { clipboard.consumeResult(checkNotNull(clipboard.result.value)) }
        composeRule.waitForIdle()
        val reads = system.reads
        composeRule.onNodeWithTag(ClipboardScreens.HOME_PASTE_BUTTON).assertIsNotEnabled()
        composeRule.runOnUiThread { clipboard.paste(ManualPasteAction.ENTRY_HOME) }
        assertEquals("禁用期内执行层不读", reads, system.reads)
        assertEquals(1, parseRequests().size)
    }

    /** A-R1-07：24 小时内识别过 51 个不同候选，再复制第 1 个回前台不上传、不出卡（去重记录不被条数上限挤掉）。 */
    @Test
    fun dedupeHoldsFor24HoursBeyondFiftyRecords() {
        launch()
        repeat(FIFTY_ONE) { index ->
            copy("$LINK&n=$index")
            background()
            foreground()
            idleUntil { clipboard.result.value is ClipboardResult.CardList }
            assertEquals(index + 1, parseRequests().size)
            composeRule.runOnUiThread { clipboard.consumeResult(checkNotNull(clipboard.result.value)) }
        }
        copy("$LINK&n=0")
        background()
        foreground()
        composeRule.waitForIdle()
        assertNull(clipboard.result.value)
        assertFalse(promptShown())
        assertEquals("仍在 TTL 内的第 1 个不能被容量淘汰", FIFTY_ONE, parseRequests().size)
    }

    /** A-R1-08：大屏（最小宽度 ≥600dp）+ 200% 字号：首页剪贴板区限宽 600dp、水平居中，结果卡与粘贴入口可达。 */
    @Test
    @Config(qualifiers = "w1280dp-h800dp-mdpi", fontScale = 2f)
    fun largeScreenHomeClipboardAreaIsWidthLimitedCenteredAndReachable() {
        launchForManualPaste()
        val root = composeRule.onRoot().getUnclippedBoundsInRoot()
        val area = composeRule.onNodeWithTag(ClipboardScreens.HOME_AREA).getUnclippedBoundsInRoot()
        val width = area.right - area.left
        assertTrue("限宽 600dp：$width", width <= HOME_MAX_WIDTH)
        val leftGap = area.left
        val rightGap = root.right - area.right
        assertTrue("水平居中：左 $leftGap、右 $rightGap", (leftGap - rightGap).value in -1f..1f)
        composeRule.onNodeWithTag(HomeTags.LIST).assert(hasScrollAction())
        composeRule.onNodeWithTag(ClipboardScreens.HOME_PASTE_BUTTON).performScrollTo().assertIsDisplayed()
        copy(LINK)
        composeRule.onNodeWithTag(ClipboardScreens.HOME_PASTE_BUTTON).performScrollTo().performClick()
        idleUntil { clipboard.result.value is ClipboardResult.CardList }
        composeRule.onNodeWithTag(ClipboardScreens.CARD_LIST).assertIsDisplayed()
        composeRule.onNodeWithTag(ClipboardScreens.CARD_PREFIX + 0).assertIsDisplayed()
    }

    /** A-R1-08：窗口缩小（320×480dp）+ 200% 字号：整页同一列表可滚动，粘贴入口可滚到可见并点击，结果卡可见。 */
    @Test
    @Config(qualifiers = "w320dp-h480dp-mdpi", fontScale = 2f)
    fun smallWindowBigFontHomeClipboardAreaScrolls() {
        launchForManualPaste()
        composeRule.onNodeWithTag(HomeTags.LIST).assert(hasScrollAction())
        composeRule.onNodeWithTag(ClipboardScreens.HOME_PASTE_BUTTON).performScrollTo().assertIsDisplayed()
        copy(LINK)
        composeRule.onNodeWithTag(ClipboardScreens.HOME_PASTE_BUTTON).performClick()
        idleUntil { clipboard.result.value is ClipboardResult.CardList }
        composeRule.onNodeWithTag(ClipboardScreens.CARD_LIST).assertIsDisplayed()
        composeRule.onNodeWithTag(ClipboardScreens.CARD_PREFIX + 0).assertIsDisplayed()
    }

    // ---------------------------------------------------------------- 第 2 轮评审（A-R2-01 / 02 / 03 / 04）

    /** A-R2-01：整体失败是未收录的 5xxxx（文案键不存在）：回落 error.50001 的通用正文，再附 trace6，不会只剩「（000123）」。 */
    @Test
    fun unknownServerErrorFallsBackToTheGenericTextWithTrace6() {
        parseAnswer = { envelope(UNKNOWN_SERVER_CODE, "null") }
        launch()
        copy(LINK)
        foreground()
        idleUntil { clipboard.parseCount > 0 } // 0724：自动直接 parse，不点击商品提示条
        idleUntil { clipboard.result.value is ClipboardResult.Notice }
        val notice = clipboard.result.value as ClipboardResult.Notice
        val generic = ClipboardDraftTexts.text("error.50001")
        assertTrue("通用正文不为空", generic.isNotEmpty())
        assertTrue("通用正文 + trace6：${notice.text}", notice.text.startsWith(generic) && notice.text.endsWith("（000123）"))
        assertEquals(0, system.writes)
    }

    /** A-R2-01：单条结果的 error_code 是未收录的 5xxxx：同样回落通用正文并附 trace6。 */
    @Test
    fun unknownServerErrorOnASingleRowFallsBackToTheGenericText() {
        val row = """{"hit":{"platform":"jd","kind":"url","raw":"https://u.jd.com/x"},"error_code":$UNKNOWN_SERVER_CODE}"""
        parseAnswer = { envelope(0, """{"results":[$row]}""") }
        launch()
        copy(LINK)
        foreground()
        idleUntil { clipboard.parseCount > 0 } // 0724：自动直接 parse，不点击商品提示条
        idleUntil { clipboard.result.value is ClipboardResult.Notice }
        val notice = clipboard.result.value as ClipboardResult.Notice
        val generic = ClipboardDraftTexts.text("error.50001")
        assertTrue("通用正文 + trace6：${notice.text}", notice.text.startsWith(generic) && notice.text.endsWith("（000123）"))
    }

    /** A-R2-01：多结果里一条未收录的 5xxxx：该行回落通用正文并附本次 trace6。 */
    @Test
    fun unknownServerErrorInManyResultsFallsBackToTheGenericText() {
        parseAnswer = {
            val first = """{"hit":{"platform":"taobao","kind":"url","raw":"$LINK"},"card":${card("tb:7Kq9LmN3pQ", "降噪蓝牙耳机 5.3")}}"""
            val row = """{"hit":{"platform":"jd","kind":"url","raw":"https://u.jd.com/x"},"error_code":$UNKNOWN_SERVER_CODE}"""
            envelope(0, """{"results":[$first,$row]}""")
        }
        launch()
        copy(LINK)
        foreground()
        idleUntil { clipboard.parseCount > 0 } // 0724：自动直接 parse，不点击商品提示条
        idleUntil { clipboard.result.value is ClipboardResult.CardList }
        val list = clipboard.result.value as ClipboardResult.CardList
        val text = clipboard.rowText(list, list.rows[1])
        assertEquals(ClipboardDraftTexts.text("error.50001") + "（000123）", text)
        composeRule.onNodeWithText(text, substring = true).assertIsDisplayed()
    }

    /**
     * A-R2-02（AC-S1-08）：SearchTyping 粘贴后 parse 在途，用户接着输入商品名搜索、结果已出来，这时 parse 返回 30132：提示与
     * 【用商品名搜索】仍在本页显示（不依赖搜索阶段），点它把「」内文字填进本页搜索框并搜索，不另开一页。
     */
    @Test
    fun unrecognizedArrivingAfterAKeywordSearchStillShowsOnTheSearchPage() {
        val row = """{"hit":{"platform":"taobao","kind":"tpwd","raw":"￥Zx8Cv7Bn6Mq￥「全脂纯牛奶 250ml」"},"error_code":30132}"""
        parseAnswer = { envelope(0, """{"results":[$row]}""") }
        launch()
        openSearchAndPasteThenHold()
        val searchEntry = checkNotNull(navController.currentBackStackEntry).id
        composeRule.onNodeWithTag(SearchTags.INPUT).performTextInput("耳机")
        composeRule.onNodeWithTag(SearchTags.SUBMIT).performClick()
        idleUntil { composeRule.onAllNodesWithText("降噪蓝牙耳机 5.3", substring = true).fetchSemanticsNodes().isNotEmpty() }
        val searches = synchronized(requests) { requests.count { it.first.startsWith(SEARCH_PATH) } }
        hold?.countDown()
        idleUntil { clipboard.result.value is ClipboardResult.Unrecognized }
        composeRule.onNodeWithTag(ClipboardScreens.UNRECOGNIZED).assertIsDisplayed()
        composeRule.onNodeWithText("这个口令暂时识别不了").assertIsDisplayed()
        composeRule.onNodeWithTag(ClipboardScreens.SEARCH_BY_NAME).assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals("填进本页，不另开一页", searchEntry, checkNotNull(navController.currentBackStackEntry).id)
        composeRule.onNodeWithTag(SearchTags.INPUT).assert(hasText("全脂纯牛奶 250ml"))
        idleUntil { synchronized(requests) { requests.count { it.first.startsWith(SEARCH_PATH) } } > searches }
        composeRule.onNodeWithTag(ClipboardScreens.UNRECOGNIZED).assertDoesNotExist()
        assertEquals(0, system.writes)
    }

    /**
     * A-R2-03：搜淘宝返回 50304 search_disabled、京东仍可搜：回落到京东并出结果，提示「可以粘贴商品链接查返利」的同时粘贴入口独立显示；
     * 粘贴可用；42901 之后同一个入口置灰，执行层也不读（共享禁用期）。
     */
    @Test
    fun searchDisabledFallingBackToAnotherPlatformStillShowsThePasteEntry() {
        searchAnswer = { path ->
            if (path.contains("platform=taobao")) {
                envelope(SEARCH_DISABLED, """{"platform":"taobao","reason":"search_disabled"}""").setResponseCode(HTTP_UNAVAILABLE)
            } else {
                envelope(
                    0,
                    """{"items":[${card("jd:100200", "京东降噪耳机")}],"next_cursor":null,"has_more":false,"fallback_items":[]}""",
                )
            }
        }
        parseAnswer = { envelope(42901, "null").setResponseCode(429).setHeader("Retry-After", "60") }
        launch(config = TWO_PLATFORM_CONFIG)
        foreground(cold = true)
        composeRule.runOnUiThread { routing.router.open(RouteRequest(SEARCH, JsonObject(emptyMap()), RouteSource.IN_APP)) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SearchTags.INPUT).performTextInput("耳机")
        composeRule.onNodeWithTag(SearchTags.SUBMIT).performClick()
        idleUntil { composeRule.onAllNodesWithText("京东降噪耳机", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(
            "回落到京东",
            synchronized(requests) { requests.any { it.first.startsWith(SEARCH_PATH) && it.first.contains("platform=jd") } },
        )
        composeRule.onNodeWithTag(SearchTags.NOTICE).assertIsDisplayed()
        composeRule.onNodeWithTag(SearchTags.PASTE_ENTRY).assertIsDisplayed()
        copy(LINK)
        composeRule.onNodeWithTag(ClipboardUi.PASTE_ENTRY).performClick()
        idleUntil { clipboard.blocked.value }
        assertEquals(1, parseRequests().size)
        composeRule.onNodeWithTag(ClipboardUi.PASTE_ENTRY).assertIsNotEnabled()
        val reads = system.reads
        composeRule.runOnUiThread { clipboard.paste(ManualPasteAction.ENTRY_SEARCH_TYPING) }
        assertEquals("禁用期内执行层不读", reads, system.reads)
        assertEquals(1, parseRequests().size)
    }

    /**
     * A-R2-04：搜索页粘贴后 parse 在途（有购买执行器登记在 AppTrade），网络线程持续做归属复核，同时主线程把搜索页出栈（释放执行器、
     * 作废剪贴板）：后台复核不抛异常、不读界面状态源；出栈的同步出口返回后，后台读到的归属立即失效；之后到达的响应只丢弃。
     */
    @Test
    fun backgroundOwnershipChecksInterleavedWithPoppingTheSearchPage() {
        launch(withTrade = true)
        val reply = CompletableDeferred<ApiResult>()
        gated = reply
        openSearchAndPaste { clipboard.parseCount == 1 }
        val owner = checkNotNull(lastOwnership)
        val stop = AtomicBoolean(false)
        val checks = AtomicInteger()
        val failure = AtomicReference<Throwable?>(null)
        val checker =
            Thread {
                try {
                    while (!stop.get()) {
                        owner.stillOwned()
                        checks.incrementAndGet()
                    }
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }.apply { start() }
        idleUntil { checks.get() > CHECKS_BEFORE_POP }
        assertTrue("出栈前后台读到的仍有效", runOffMain { owner.stillOwned() })
        repeat(REFOCUS_TIMES) {
            composeRule.runOnUiThread { routing.router.open(RouteRequest(SETTINGS, JsonObject(emptyMap()), RouteSource.IN_APP)) }
            composeRule.runOnUiThread { navController.popBackStack() }
        }
        composeRule.runOnUiThread { navController.popBackStack() }
        assertFalse("出栈的同步出口之后，后台读到的归属已失效", runOffMain { owner.stillOwned() })
        composeRule.waitForIdle()
        stop.set(true)
        checker.join(TimeUnit.SECONDS.toMillis(WAIT_S))
        assertNull("后台复核不抛异常", failure.get())
        assertEquals("后台复核不读界面状态源", 0, offMainUiReads.get())
        composeRule.runOnUiThread { reply.complete(ApiResult.Success(Json.parseToJsonElement(singleCard()), "trace-000123", null)) }
        composeRule.waitForIdle()
        assertFalse("迟到的响应不跳详情", RouteNames.PRODUCT_DETAIL_FOR_TEST in routes())
        assertNull(clipboard.result.value)
        assertEquals(0, system.writes)
    }

    private fun runOffMain(block: () -> Boolean): Boolean {
        val out = AtomicBoolean()
        Thread { out.set(block()) }.apply {
            start()
            join(TimeUnit.SECONDS.toMillis(WAIT_S))
        }
        return out.get()
    }

    // ---------------------------------------------------------------- FU-AND-0710：APP-07 共性第 30、32、33 条

    /**
     * 第 30 条：整体 30132（data=null）用请求前本地抽出的「」标题预填【用商品名搜索】，标题本身以 http 开头也照样；服务端多给的 data.raw 不采用；
     * 没有「」标题时留空（不拿链接或首行）。手动粘贴与自动识别两条路径都一样。
     */
    @Test
    fun fu0710_l30_whole30132PrefillsTheLocallyExtractedTitle() {
        parseAnswer = { envelope(UNRECOGNIZED, "null") }
        launchForManualPaste()
        copy("$LINK 「HTTP转接头 USB3.0」")
        pasteHome()
        idleUntil { clipboard.result.value is ClipboardResult.Unrecognized }
        assertEquals("HTTP转接头 USB3.0", (clipboard.result.value as ClipboardResult.Unrecognized).prefill)

        parseAnswer = { envelope(UNRECOGNIZED, """{"raw":"服务端给的标题"}""") }
        copy("$LINK 「全脂纯牛奶 250ml」")
        var before = clipboard.result.value!!.id
        pasteHome()
        idleUntil { clipboard.result.value.let { it is ClipboardResult.Unrecognized && it.id != before } }
        assertEquals("不依赖契约外的 data.raw", "全脂纯牛奶 250ml", (clipboard.result.value as ClipboardResult.Unrecognized).prefill)

        parseAnswer = { envelope(UNRECOGNIZED, "null") }
        copy("看看 $LINK")
        before = clipboard.result.value!!.id
        pasteHome()
        idleUntil { clipboard.result.value.let { it is ClipboardResult.Unrecognized && it.id != before } }
        assertNull((clipboard.result.value as ClipboardResult.Unrecognized).prefill)
        composeRule.onNodeWithTag(ClipboardScreens.SEARCH_BY_NAME).performClick()
        composeRule.waitForIdle()
        assertEquals(SEARCH, routes().last())
        val params = checkNotNull(navController.currentBackStackEntry).toRoute<RouteDestination>().paramsJson
        assertFalse("没有标题时留空，不拿链接", params.contains("e.tb.cn"))
        assertNoRawInStorage("HTTP转接头", "全脂纯牛奶")
    }

    @Test
    fun fu0710_l30_automaticPathAlsoPrefillsTheLocallyExtractedTitle() {
        parseAnswer = { envelope(UNRECOGNIZED, "null") }
        launch()
        copy("$LINK 「HTTP转接头 USB3.0」")
        foreground()
        idleUntil { clipboard.parseCount > 0 } // 0724：自动直接 parse，不点击商品提示条
        idleUntil { clipboard.result.value is ClipboardResult.Unrecognized }
        assertEquals("HTTP转接头 USB3.0", (clipboard.result.value as ClipboardResult.Unrecognized).prefill)
    }

    /**
     * 第 32 条：多卡弹层的商品卡应用平台购买状态（与搜索卡同一映射）：not_launched 隐藏返利金额、显示「X返利即将开放」且购买不可点；
     * 50301 覆盖变成 maintenance 时按当前结果重新映射（金额回来、购买「稍后再试」不可点）；结果编号不变。
     */
    @Test
    fun fu0710_l32_cardListAppliesThePlatformPurchaseStatus() {
        parseAnswer = { envelope(0, twoResults()) }
        launch(
            withTrade = true,
            config = CONFIG.replace(""""platform_status":{"taobao":"on"}""", """"platform_status":{"taobao":"not_launched"}"""),
        )
        copy(LINK)
        foreground()
        idleUntil { clipboard.parseCount > 0 } // 0724：自动直接 parse，不点击商品提示条
        idleUntil { clipboard.result.value is ClipboardResult.CardList }
        val id = clipboard.result.value!!.id
        val card = hasTestTag(ClipboardScreens.CARD_PREFIX + 0)
        val notLaunched = "淘宝返利即将开放"
        idleUntil {
            composeRule.onAllNodes(hasText(notLaunched, substring = true) and hasAnyAncestor(card), true).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "not_launched 不显示返利金额",
            composeRule.onAllNodes(hasText("2.69", substring = true) and hasAnyAncestor(card), true).fetchSemanticsNodes().isEmpty(),
        )

        composeRule.runOnUiThread { tradeRef!!.markPlatformStatus("taobao", "maintenance") }
        composeRule.waitForIdle()
        idleUntil {
            composeRule
                .onAllNodes(
                    hasText("2.69", substring = true) and hasAnyAncestor(card),
                    true,
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
        assertTrue(
            "maintenance：不再是即将开放",
            composeRule.onAllNodes(hasText(notLaunched, substring = true) and hasAnyAncestor(card), true).fetchSemanticsNodes().isEmpty(),
        )
        assertEquals("结果编号不变", id, clipboard.result.value!!.id)
    }

    /** 第 33 条：连续两次同类结果（两次 30132、弹层开着时又来一组多卡）按结果编号各读一次；普通重组不重复读。 */
    @Test
    fun fu0710_l33_consecutiveSameKindResultsAreAnnouncedOncePerResult() {
        parseAnswer = { envelope(UNRECOGNIZED, "null") }
        launchForManualPaste()
        val unrecognized = DefaultTexts.text("error.30132").orEmpty()

        fun count(text: String) = synchronized(announced) { announced.count { it == text } }
        copy("$LINK 「全脂纯牛奶 250ml」看不懂的口令")
        pasteHome()
        idleUntil { count(unrecognized) == 1 }
        copy("$LINK 「低脂酸奶 100g」另一段口令")
        pasteHome()
        idleUntil { count(unrecognized) == 2 }
        idleUntil { activeApiCalls.get() == 0 && !clipboard.busy.value }
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()
        assertEquals("重组不重复读", 2, count(unrecognized))

        parseAnswer = { envelope(0, twoResults()) }
        val title = ClipboardDraftTexts.text("clipboard.card_list.title")
        copy(LINK)
        pasteHome()
        idleUntil { clipboard.result.value is ClipboardResult.CardList }
        composeRule.waitForIdle()
        assertEquals("弹层打开时由移焦读标题，不另外播报", 0, count(title))
        copy("$LINK ")
        pasteHome()
        idleUntil { count(title) == 1 }
    }

    // ---------------------------------------------------------------- 样例

    private fun singleCard(): String =
        """{"results":[{"hit":{"platform":"taobao","kind":"url","raw":"$LINK"},"card":${card("tb:7Kq9LmN3pQ", "降噪蓝牙耳机 5.3")}}]}"""

    private fun twoResults(): String {
        val first = """{"hit":{"platform":"taobao","kind":"url","raw":"$LINK"},"card":${card("tb:7Kq9LmN3pQ", "降噪蓝牙耳机 5.3")}}"""
        return """{"results":[$first,{"hit":{"platform":"jd","kind":"url","raw":"https://u.jd.com/x"},"error_code":30131}]}"""
    }

    /** 一张卡 + 一条 30132（命中口令带「」标题）+ 一条 50001。 */
    private fun mixedResults(): String {
        val first = """{"hit":{"platform":"taobao","kind":"url","raw":"$LINK"},"card":${card("tb:7Kq9LmN3pQ", "降噪蓝牙耳机 5.3")}}"""
        val unrec = """{"hit":{"platform":"taobao","kind":"tpwd","raw":"￥Zx8Cv7Bn6Mq￥「全脂纯牛奶 250ml」"},"error_code":30132}"""
        val server = """{"hit":{"platform":"jd","kind":"url","raw":"https://u.jd.com/x"},"error_code":50001}"""
        return """{"results":[$first,$unrec,$server]}"""
    }

    private fun rateLimitedRow(): String {
        val first = """{"hit":{"platform":"taobao","kind":"url","raw":"$LINK"},"card":${card("tb:7Kq9LmN3pQ", "降噪蓝牙耳机 5.3")}}"""
        return """{"results":[$first,{"hit":{"platform":"jd","kind":"url","raw":"https://u.jd.com/x"},"error_code":42901}]}"""
    }

    private fun card(
        key: String,
        title: String,
    ): String =
        """{"product_key":"$key","item_ref":"v1.ref.sig","platform":"taobao","shop_type":"tmall","title":"$title",""" +
            """"image":null,"shop_name":"示例旗舰店","price_fen":3990,"coupon_fen":1000,"final_price_fen":2990,""" +
            """"est_net_price_fen":2721,"rebate_min_fen":269,"rebate_max_fen":269,"rebate_basis":"normal","benefit_tags":["有券"],""" +
            """"spec_text":null,"is_presale":false,"link_id":"0199a3b4-5c6d-7e8f-9a0b-1c2d3e4f5a70",""" +
            """"cta":{"text_key":"btn.buy.coupon"},""" +
            """"quoted_at":"2026-10-02T09:30:00+08:00","stale":false,"age_sec":12,"source":"taobao_union",""" +
            """"disclaimer_keys":["price_basis"],"ad_label":null,"availability":"ok"}"""

    private fun envelope(
        code: Int,
        data: String,
    ) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"code":$code,"msg":"","data":$data,"trace_id":"trace-000123"}""")

    private object FixtureDevice : DeviceCredentials {
        override val deviceId: String = "device-fixture"
        override val installSecret: String = "install-fixture"
    }

    private object RouteNames {
        const val LOGIN = "Login"
        const val WEB_PAGE = "WebPage"
        const val PRODUCT_DETAIL_FOR_TEST = "ProductDetail"
    }

    private companion object {
        const val PARSE_PATH = "/v1/inputs/parse"
        const val SEARCH_PATH = "/v1/products/search"
        const val LINKS_PATH = "/v1/links/"
        const val SMS_PATH = "/v1/auth/sms-codes"
        const val SEARCH = "Search"
        const val FIFTY_ONE = 51
        val HOME_MAX_WIDTH = 600.dp
        const val AUTH_SHEET_CLOSE = "关闭"
        const val SETTINGS = "Settings"
        const val H5_ORIGIN = "https://h5.example.test"
        const val LINK = "https://e.tb.cn/h.AbCdEf?tk=Xy12"
        const val CONFIG =
            """{"clipboard":{"consent_version":"1"},""" +
                """"share_domains":["share.example.test"],"features":{"flags":{},"platform_status":{"taobao":"on"},""" +
                """"search_status":{"taobao":"on"}}}"""
        const val WAIT_S = 5L
        const val POLL_MS = 10L
        const val RATE_LIMITED = 42901
        const val UNRECOGNIZED = 30132
        const val RATE_LIMIT_MS = 60_000L
        const val REFOCUS_TIMES = 3
        const val UNKNOWN_SERVER_CODE = 59999
        const val SEARCH_DISABLED = 50304
        const val HTTP_UNAVAILABLE = 503
        const val CHECKS_BEFORE_POP = 100
        const val TWO_PLATFORM_CONFIG =
            """{"clipboard":{"consent_version":"1"},""" +
                """"share_domains":["share.example.test"],"features":{"flags":{},"platform_status":{"taobao":"on","jd":"on"},""" +
                """"search_status":{"taobao":"on","jd":"on"}}}"""
    }
}
