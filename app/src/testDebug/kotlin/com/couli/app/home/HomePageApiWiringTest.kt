package com.couli.app.home

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.DebugMockGate
import com.couli.app.RawFakeServerSwitch
import com.couli.app.buildDefaultEnvironment
import com.couli.core.clientcore.BundledHome
import com.couli.core.config.AppEnvironment
import com.couli.core.config.EnvironmentSwitchCleanerRegistry
import com.couli.core.config.EnvironmentSwitcher
import com.couli.core.designsystem.ProductCardTexts
import com.couli.core.home.ApiClientHomeContractPort
import com.couli.core.home.HomeCall
import com.couli.core.home.HomeContractPort
import com.couli.core.home.HomeIdentity
import com.couli.core.home.HomeLevel
import com.couli.core.home.RouteTarget
import com.couli.core.network.ApiClient
import com.couli.core.network.ApiClientPageApi
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.ClientIdentity
import com.couli.core.network.DeviceCredentials
import com.couli.core.network.MemoryPendingKeyBackend
import com.couli.core.network.MemorySessionTokens
import com.couli.core.network.NetworkContext
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PrivacyRaw
import com.couli.core.network.UpdateRaw
import com.couli.core.wallet.WalletRateGate
import com.couli.feature.home.HomeCardUi
import com.couli.feature.home.HomeController
import com.couli.feature.home.HomeDeps
import com.couli.feature.home.HomeHost
import com.couli.feature.home.HomeSignals
import com.couli.feature.home.HomeTimers
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * FU-X-HOME-PAGE-API-android 测试 v1（独立测试作者）：生产入口（brief §3「生产入口」；integ-android-report §1 第 3 项、§2 B1）。
 *
 * 底版现象：debugFeatures 的 `variantHomePort(context)` = DebugFakeHome，假服务关着时回本地断网、不发请求；release 恒为 null。所以 staging 包首页不发
 * GET /v1/pages/home，演示首页上不了屏。本文件钉住：
 * - debug / staging（debugFeatures）：`variantHomePort(context, real)` 每次调用现判统一闸门 [DebugMockGate]（假服务开关文件 + 环境允许 MOCK_DATA + 环境就绪）：
 *   **关着（staging 默认）走 real**、开着（测试参数）才走 DebugFakeHome；
 * - 端到端：真实 ApiClient（传输层换 MockWebServer）+ [ApiClientHomeContractPort] + 首页控制器，默认（开关关）发 `GET /v1/pages/home` 与各商品卡
 *   `GET /v1/pages/home/sections/{id}/items`（ops 07:14 回读的 staging home v1 形状），不签名、不带 Idempotency-Key、首屏无查询参数；基本模式照发、匿名；
 * - AppModule 把真实端口交给 variantHomePort；release 的 variantHomePort 原样返回 real（源码核对：testDebug 编不到 release 源码集，同 StagingStreamReleaseSourceTest）。
 * 测试参数：既有首页用例（AppHomeIntegrationTest、HomeVisual*、HomePopup*）自己注入端口替身，不依赖假服务默认拦截；要用 DebugFakeHome 的冒烟 / 截图须显式打开
 * 假服务开关（Debug panel 或 [DebugMockGate.setSwitch]）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
@Suppress("LargeClass", "TooManyFunctions")
class HomePageApiWiringTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val switcher by lazy { EnvironmentSwitcher(context, buildDefaultEnvironment(), EnvironmentSwitchCleanerRegistry()) }
    private val ready = DebugMockGate.environmentReady
    private lateinit var server: MockWebServer
    private val requests = mutableListOf<RecordedRequest>()

    @Volatile private var pageReply: () -> MockResponse = { envelope(STAGING_HOME_V1) }

    /** 只记录的「真实端口」替身（选择器测试用）。 */
    private class RecordingPort : HomeContractPort {
        val calls = mutableListOf<String>()

        override suspend fun getPage(
            pageKey: String,
            call: HomeCall,
        ): ApiResult {
            calls += "page:$pageKey"
            return ApiResult.Success(Json.parseToJsonElement(STAGING_HOME_V1), "real-000001", null)
        }

        override suspend fun listSectionItems(
            pageKey: String,
            sectionId: String,
            cursor: String?,
            call: HomeCall,
        ): ApiResult {
            calls += "items:$sectionId|${cursor.orEmpty()}"
            return ApiResult.Success(Json.parseToJsonElement(ITEMS), "real-000002", null)
        }
    }

    @Before
    fun setUp() {
        DebugMockGate.environmentReady = { true }
        setSwitch(false)
        switcher.switchTo(AppEnvironment.TEST)
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    synchronized(requests) { requests += request }
                    val path = request.path.orEmpty().substringBefore('?')
                    return when {
                        path == "/v1/pages/home" -> pageReply()
                        path.startsWith("/v1/pages/home/sections/") && path.endsWith("/items") -> envelope(ITEMS)
                        path == "/v1/articles" -> envelope("""{"items":[],"next_cursor":null}""")
                        else -> envelope("{}")
                    }
                }
            }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        setSwitch(false)
        switcher.switchTo(AppEnvironment.TEST)
        DebugMockGate.environmentReady = ready
    }

    @OptIn(RawFakeServerSwitch::class)
    private fun setSwitch(on: Boolean) {
        DebugMockGate.setSwitch(context, on)
    }

    private val call = HomeCall(anonymous = false, expectedAccount = null, allowed = { true }, stillOwned = { true })

    // ---------------------------------------------------------------- 选择器（debugFeatures）

    @Test
    fun s01FakeServerOffUsesTheRealPort() {
        val real = RecordingPort()
        val port = variantHomePort(context, real)
        val page = runBlocking { port.getPage("home", call) }
        runBlocking { port.listSectionItems("home", "demo-today", null, call) }
        assertEquals("假服务开关关着（staging 默认）：走真实端口", listOf("page:home", "items:demo-today|"), real.calls)
        assertTrue("真实端口的结果原样交回", page is ApiResult.Success)
    }

    @Test
    fun s02FakeServerOnUsesTheFakeAndTheRealPortIsNotTouched() {
        setSwitch(true)
        val real = RecordingPort()
        val port = variantHomePort(context, real)
        val page = runBlocking { port.getPage("home", call) }
        // 回答来自假服务（进程内唯一的 DebugFakeHome；Robolectric 多用例共用进程时它可能按旧 Application 回断网，这里只核对来源）
        assertFalse("开关开着（测试参数）：不得是真实端口的回答：$page", (page as? ApiResult.Success)?.traceId == "real-000001")
        assertTrue("真实端口一次也不调", real.calls.isEmpty())
    }

    @Test
    fun s03TheChoiceIsMadeAtEachCallNotOnce() {
        val real = RecordingPort()
        val port = variantHomePort(context, real)
        setSwitch(true)
        runBlocking { port.getPage("home", call) }
        assertTrue(real.calls.isEmpty())
        setSwitch(false)
        runBlocking { port.getPage("home", call) }
        assertEquals("关掉开关后同一个端口改走真实", listOf("page:home"), real.calls)
        setSwitch(true)
        switcher.switchTo(AppEnvironment.PROD)
        runBlocking { port.getPage("home", call) }
        assertEquals("prod 不允许 MOCK_DATA：开关在也走真实（F-X-02）", listOf("page:home", "page:home"), real.calls)
    }

    // ---------------------------------------------------------------- 端到端（真实 ApiClient → MockWebServer）

    private fun client(
        privacy: String = PrivacyRaw.FULL,
        tokens: MemorySessionTokens = MemorySessionTokens(),
    ) = ApiClient(
        identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.0.0", build = "1", channel = "official"),
        baseUrl = { server.url("/").toString() },
        context = { NetworkContext(privacy, environmentSettled = true, credentialsReadable = true, UpdateRaw.SUPPORTED) },
        device = Device,
        tokens = tokens,
        pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { NOON },
        sleeper = {},
    )

    private class Host(
        val id: HomeIdentity,
    ) : HomeHost {
        var forced = 0

        override fun identity(): HomeIdentity = id

        override fun open(
            target: RouteTarget,
            spm: String,
        ) = Unit

        override fun forceUpdate() {
            forced += 1
        }

        override fun reportCardError(
            type: String,
            version: Int?,
            pageVersion: Int,
            reason: String,
        ) = Unit
    }

    private class Signals : HomeSignals {
        override val visible: StateFlow<Boolean> = MutableStateFlow(true)
        override val screenReader: StateFlow<Boolean> = MutableStateFlow(false)

        override fun blockers(): Set<String> = emptySet()

        override val blockerChanges: StateFlow<Long> = MutableStateFlow(0L)
    }

    private fun controller(
        api: ApiClient,
        identity: HomeIdentity,
    ): HomeController {
        val pages = ApiClientPageApi(api)
        val deps =
            HomeDeps(
                host = Host(identity),
                signals = Signals(),
                port = variantHomePort(context, ApiClientHomeContractPort(pages)),
                api = pages,
                lkg = null,
                prefs = null,
                gate = WalletRateGate(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) { NOON },
                images = null,
                timers = HomeTimers { _, _ -> {} },
                clock = { NOON },
                bundled = BundledHome::pageJson,
                platform = "android",
                appVersion = "1.0.0",
                cardTexts = ProductCardTexts { "" },
                io = Dispatchers.Main.immediate,
            )
        return HomeController(identity, deps)
    }

    private fun recorded(): List<RecordedRequest> = synchronized(requests) { requests.toList() }

    private fun homeRequests(): List<RecordedRequest> = recorded().filter { it.path.orEmpty().startsWith("/v1/pages/") }

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(POLL_MS)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun s05DefaultStagingHomeSendsGetPageAndEachCardsItemsToTheServer() {
        val home = controller(client(), GUEST)
        home.start()
        awaitUntil { homeRequests().size >= 3 && home.state.value.cards.size >= 2 }
        val sent = homeRequests()
        assertEquals("默认（假服务关）：先 GET /v1/pages/home", "/v1/pages/home", sent.first().path)
        assertEquals(
            "再按卡片配置各取一次两张商品池卡（两张卡并发，到达顺序不定），首屏不带任何查询参数",
            setOf("/v1/pages/home/sections/demo-today/items", "/v1/pages/home/sections/demo-grid/items"),
            sent.drop(1).map { it.path }.toSet(),
        )
        assertEquals("取页 1 次 + 取卡 2 次", 3, sent.size)
        sent.forEach { request ->
            assertEquals("GET", request.method)
            assertNull("不签名：${request.path}", request.getHeader("X-Sign"))
            assertNull("读接口不带 Idempotency-Key：${request.path}", request.getHeader("Idempotency-Key"))
            assertEquals("android", request.getHeader("X-Platform"))
            assertEquals(0L, request.bodySize)
        }
        assertEquals("staging 演示首页上屏（网络版 v1）", 1, home.state.value.pageVersion)
        val cards = home.state.value.cards
        assertNotNull("演示 · 今日推荐 显示真实商品", cards.firstOrNull { it.id == "demo-today" } as? HomeCardUi.ProductScroll)
        assertNotNull(cards.firstOrNull { it.id == "demo-grid" } as? HomeCardUi.ProductGrid)
        home.invalidate()
    }

    @Test
    fun s06MemberRequestsCarryTheTokenAndDeviceIdForGrayBucketing() {
        val tokens = MemorySessionTokens("access-u1", "refresh-u1", "full", "u1")
        val home = controller(client(tokens = tokens), HomeIdentity(HomeLevel.MEMBER, "u1", 0L, CallEpochs(0, 0), "test"))
        home.start()
        awaitUntil { homeRequests().size >= 3 }
        val sent = homeRequests()
        assertEquals(3, sent.size)
        sent.forEach { request ->
            assertEquals("会员带 Bearer（x-auth optional，卡片按当前用户计算）", "Bearer access-u1", request.getHeader("Authorization"))
            assertEquals("带 X-Device-Id（按设备分桶读灰度版本）", Device.deviceId, request.getHeader("X-Device-Id"))
        }
        home.invalidate()
    }

    @Test
    fun s07BasicModeHomeIsFetchedAnonymously() {
        val basic = HomeIdentity(HomeLevel.BASIC, null, 0L, CallEpochs(0, 0), "test")
        val home = controller(client(privacy = PrivacyRaw.BASIC), basic)
        home.start()
        awaitUntil { homeRequests().size >= 3 }
        val sent = homeRequests()
        assertEquals("基本模式照样发出取页与取卡（白名单含两接口）", 3, sent.size)
        sent.forEach { request ->
            assertNull("基本模式不带 Authorization", request.getHeader("Authorization"))
            assertNull("基本模式不带 X-Device-Id", request.getHeader("X-Device-Id"))
        }
        home.invalidate()
    }

    @Test
    fun s08ServerPage30701FallsBackQuietlyWithoutForceUpdate() {
        pageReply = {
            MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"code":30701,"msg":"","data":null,"trace_id":"trace-030701"}""")
        }
        val host = Host(GUEST)
        val pages = ApiClientPageApi(client())
        val home =
            HomeController(
                GUEST,
                HomeDeps(
                    host = host,
                    signals = Signals(),
                    port = variantHomePort(context, ApiClientHomeContractPort(pages)),
                    api = pages,
                    lkg = null,
                    prefs = null,
                    gate = WalletRateGate(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) { NOON },
                    images = null,
                    timers = HomeTimers { _, _ -> {} },
                    clock = { NOON },
                    bundled = BundledHome::pageJson,
                    platform = "android",
                    appVersion = "1.0.0",
                    cardTexts = ProductCardTexts { "" },
                    io = Dispatchers.Main.immediate,
                ),
            )
        home.start()
        awaitUntil { homeRequests().isNotEmpty() }
        Thread.sleep(POLL_MS * 4)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("30701 只取页、不取卡", listOf("/v1/pages/home"), homeRequests().map { it.path })
        assertEquals("30701 不当强更", 0, host.forced)
        assertNull("冷启动不提示", home.state.value.toast)
        assertFalse(home.state.value.loadFailed)
        home.invalidate()
    }

    // ---------------------------------------------------------------- 源码核对（AppModule、release）

    private val root: File by lazy {
        val start = File(checkNotNull(System.getProperty("user.dir"))).canonicalFile
        generateSequence(start) { it.parentFile }.first { File(it, "contract.lock").isFile }
    }

    /** 去掉块注释、行注释与全部空白。 */
    private fun code(path: String) =
        File(root, path)
            .readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")
            .replace(Regex("""\s+"""), "")

    @Test
    fun w01AppModuleHandsTheRealContractPortToTheVariant() {
        val module = code("app/src/main/kotlin/com/couli/app/AppModule.kt")
        assertTrue(
            "AppModule.appHome 的 port 必须是 variantHomePort(context, ApiClientHomeContractPort(…))（真实端口经当前环境的 ApiClient）",
            Regex("""port=variantHomePort\(context,(com\.couli\.core\.home\.)?ApiClientHomeContractPort\(""").containsMatchIn(module),
        )
        assertFalse("不得再有只给 context 的旧接线（只用本机假服务）", module.contains("variantHomePort(context)"))
    }

    @Test
    fun w02ReleaseVariantReturnsTheRealPortAsIs() {
        val release = code("app/src/release/kotlin/com/couli/app/home/VariantHome.kt")
        val body =
            Regex(RELEASE_SIGNATURE + """(.*?)(?=@Suppress|internalfun|privatefun|fun|$)""")
                .find(release)
                ?.groupValues
                ?.get(1)
        assertEquals("release 的 variantHomePort(context, real) 只能原样返回 real（正式构建走真实接口）", "real", body)
        val sources = File(root, "app/src/release").walkTopDown().filter { it.isFile && it.extension == "kt" }
        assertTrue("release 源码集不得出现首页假服务", sources.none { it.readText().contains("DebugFakeHome") })
    }

    private object Device : DeviceCredentials {
        override val deviceId: String = "device-fixture-HOME01"
        override val installSecret: String = "install-fixture"
    }

    companion object {
        private const val NOON = 1_760_068_800_000L
        private const val RELEASE_SIGNATURE =
            """internalfunvariantHomePort\(context:Context,real:HomeContractPort,?\):HomeContractPort\??="""
        private const val WAIT_S = 10L
        private const val POLL_MS = 5L
        private val GUEST = HomeIdentity(HomeLevel.GUEST, null, 0L, CallEpochs(0, 0), "test")

        /** ops 2026-11-04 07:14 回读的 staging home v1（两张 product_pool 区块；标题换成 ASCII 以免触发中文字面量规则无关的噪声）。 */
        const val STAGING_HOME_V1: String =
            """{"page_key":"home","version":1,"ttl_sec":300,"sections":[""" +
                """{"id":"demo-today","type":"product_scroll","version":1,"props":{"title":"demo today"},""" +
                """"data_source":{"kind":"product_pool","limit":10,"pool_id":"01a1a33a-f20f-7c9d-84c7-28db459b845f"}},""" +
                """{"id":"demo-grid","type":"product_grid","version":1,"props":{"title":"demo grid","columns":2},""" +
                """"data_source":{"kind":"product_pool","limit":20,"pool_id":"01a1a33a-f20f-7c9d-84c7-28db459b845f"}}]}"""

        const val ITEMS: String =
            """{"items":[{"product_key":"pdd:p1","item_ref":"v1.pdd.p1.sig","platform":"pdd","title":"demo item",""" +
                """"image":"https://img.example.test/p/1.png","shop_name":"shop","price_fen":3811,"coupon_fen":0,""" +
                """"final_price_fen":3811,"est_net_price_fen":3764,"rebate_min_fen":47,"rebate_max_fen":47,"rebate_basis":"normal",""" +
                """"benefit_tags":[],"is_presale":false,"link_id":"link.p1","cta":{"text_key":"btn.buy"},""" +
                """"quoted_at":"2026-11-04T07:16:00+08:00","stale":false,"age_sec":30,"source":"pdd_union",""" +
                """"disclaimer_keys":["price_basis.general","rebate_estimate"],"availability":"ok","ad_label":"ad"}],"next_cursor":"p2"}"""

        private fun envelope(data: String) =
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"code":0,"msg":"","data":$data,"trace_id":"trace-000123"}""")
    }
}
