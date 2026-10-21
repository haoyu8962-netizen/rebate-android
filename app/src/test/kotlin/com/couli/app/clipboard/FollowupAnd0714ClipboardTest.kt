package com.couli.app.clipboard

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.home.HomeRoute
import com.couli.app.home.clipboardTestHome
import com.couli.app.privacy.AppPrivacy
import com.couli.app.routing.AppRouting
import com.couli.app.routing.RouteDestination
import com.couli.app.routing.RoutingPrivacyHooks
import com.couli.app.search.AppSearch
import com.couli.app.shell.AppShell
import com.couli.core.clientcore.ConfigReply
import com.couli.core.clientcore.ConfigStore
import com.couli.core.clipboard.ClipRead
import com.couli.core.clipboard.ClipboardFingerprint
import com.couli.core.clipboard.ClipboardRecords
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
import com.couli.core.network.ClientIdentity
import com.couli.core.network.DeviceCredentials
import com.couli.core.network.InFlightCalls
import com.couli.core.network.MemoryPendingKeyBackend
import com.couli.core.network.MemorySessionTokens
import com.couli.core.network.NetworkContext
import com.couli.core.network.PageApi
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PrivacyRaw
import com.couli.core.network.UpdateRaw
import com.couli.core.privacy.PrivacyGate
import com.couli.core.privacy.PrivacyStore
import com.couli.core.privacy.RetryScheduler
import com.couli.core.router.PrivacyState
import com.couli.core.router.RouteRequest
import com.couli.core.router.RouteSource
import com.couli.core.router.SharedPreferencesPendingBackend
import com.couli.feature.login.PendingInviteCode
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

/**
 * FU-AND-0714 第 6 项（F-HM-26 的 Android 核对，FU-CLIP 共性第 9 条）：暂存的系统分享在发布提示之前按**当前**规则再校验候选（已有用例
 * FuClipIntegrationTest.c6_lesson9_aPendingShareIsRecheckedAgainstTightenedRules 覆盖「不收通知、发布时复核」）；这里补「配置收紧的通知到达时同步
 * 检查暂存」：收紧时就丢弃，之后配置又放开也不再出这次分享。夹具与 FuClipIntegrationTest 相同（复制，不改原文件）。
 *
 * 原夹具说明——FU-CLIP（剪贴板补齐）的本地假服务集成测试：真实 AppShell + NavHost + AppRouting + AppPrivacy + AppSearch + ApiClient + MockWebServer，计数的假系统
 * 剪贴板，真实的偏好文件（盐经样例加密）。生命周期经 MainActivity 的同一组入口；系统分享经接收 Activity 调的同一个入口 [AppClipboard.receiveShare]。
 * 每个用例都断言系统剪贴板写入次数（识别、提示、上传、分享导入的前后都是 0）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
@Suppress("LargeClass", "TooManyFunctions", "UnusedPrivateMember", "UnusedPrivateProperty") // 夹具与 FuClipIntegrationTest 相同
class FollowupAnd0714ClipboardTest {
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
    private val system = FakeClipboard()
    private val inFlight = InFlightCalls()
    private var sessionEpoch = 0L
    private val requests = mutableListOf<Pair<String, String>>()
    private val announced = mutableListOf<String>()
    private var configJson = CONFIG
    private val cipher = SampleCipherImpl()

    /** 第 2 轮评审 R2-05：剪贴板的规则取自真实 ConfigStore 的生效快照（与 AppModule 同一接法）；false 时沿用 [configJson]（原夹具）。 */
    private var storeBacked = false
    private lateinit var configStore: ConfigStore

    /** 依次停在闸门的 parse 回答（不为空时优先于假服务）。 */
    private val gatedQueue = ArrayDeque<CompletableDeferred<ApiResult>>()

    private inner class GatedPageApi(
        private val delegate: PageApi,
    ) : PageApi by delegate {
        override suspend fun send(call: ApiCall): ApiResult {
            if (call.operationId == "parseInput") synchronized(requests) { requests += PARSE_PATH to call.body.orEmpty() }
            gatedQueue.removeFirstOrNull()?.let { reply ->
                return reply.await()
            }
            return delegate.send(call)
        }
    }

    private class MemoryStore(
        override var decisionRaw: String = "consented",
    ) : PrivacyStore {
        override var consentedVersion: String? = "1"
        override var consentedAtMillis: Long? = 0L
        override var basicMode: Boolean = decisionRaw == "basic_mode"
    }

    /** 计数的假系统剪贴板：读取次数、写入次数。 */
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
                        path.startsWith(PARSE_PATH) -> envelope(0, singleCard())

                        path.startsWith(
                            SEARCH_PATH,
                        ) -> envelope(0, """{"items":[],"next_cursor":null,"has_more":false,"fallback_items":[]}""")

                        else -> envelope(0, "null")
                    }
                }
            }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun prefs() = context.getSharedPreferences(CouliPreferenceNamespaces.CLIPBOARD, Context.MODE_PRIVATE)

    /** 与 AppModule 同一套接线的剪贴板模块（进程恢复的用例另建一个新实例）。 */
    private lateinit var pageApi: PageApi

    private fun newClipboard(): AppClipboard =
        AppClipboard(
            system = system,
            api = pageApi,
            routing = routing,
            privacy = { privacy.conclusion },
            promptShowing = { privacy.promptShowing },
            configData = { if (storeBacked) configStore.snapshots.value.data else Json.parseToJsonElement(configJson) as JsonObject },
            prefs = ClipboardPreferences(context, cipher),
            importPreferences = importPreferences,
            sessionEpoch = { sessionEpoch },
            clock = { now },
            randomSalt = { SAMPLE_SALT.copyOf() },
            pendingInvite = PendingInviteCode(),
        ).also { it.connect(privacy, routing, cleaners = null) }

    @Suppress("LongMethod") // 与 AppModule 同一套接线
    private fun launch(
        decision: String = "consented",
        restoration: StateRestorationTester? = null,
    ) {
        importPreferences = regressionImportPreferences(context, { now }, true.takeIf { decision == "consented" })
        val gate =
            PrivacyGate(MemoryStore(decision), clock = { now }, bundledPolicyVersion = "1").apply {
                // 撤回同意的本地清理（AppModule 的 CredentialCleanerRegistry 同一个清理项）
                withdrawalCleanup = {
                    inFlight.onPrivacyRevoked()
                    ClipboardPreferences(context).clearHashes()
                }
                retryScheduler = RetryScheduler { _, _ -> }
            }
        privacy = AppPrivacy(gate, environmentSettled = { true }, clock = { now }).also { it.coldStart() }
        val environment = EnvironmentGate { EnvironmentStartupReconciler.Outcome.UNCHANGED }.also { it.settleAtStartup() }
        routing =
            AppRouting(
                context,
                environment,
                clock = { now },
                initialPrivacy = privacy.conclusion,
                privacyHooks = RoutingPrivacyHooks(promptShowing = { privacy.promptShowing }),
            )
        privacy.onConclusionChanged { routing.privacy.value = it }
        val tokens = MemorySessionTokens()
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
                fetcher = { _, done -> done(ConfigReply(ConfigReply.OK, configJson, null)) },
                clock = { now },
                bundled = configJson,
            )
        configStore = store
        pageApi = GatedPageApi(ApiClientPageApi(api))
        composeRule.runOnUiThread { clipboard = newClipboard() }
        val search = AppSearch(api, store, routing, privacy = { privacy.conclusion }, trade = null, clipboard = clipboard)
        val home = composeRule.runOnUiThread { clipboardTestHome(api, tokens, routing, privacy) }
        val content: @androidx.compose.runtime.Composable () -> Unit = {
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
                    clipboard = clipboard,
                )
            }
        }
        if (restoration != null) restoration.setContent(content) else composeRule.setContent(content)
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

    /** 系统分享到达（接收 Activity 的 onCreate 调的同一个入口）。 */
    private fun share(
        text: String?,
        mime: String = "text/plain",
    ): Boolean {
        var kept = false
        composeRule.runOnUiThread { kept = clipboard.receiveShare(mime, text) }
        composeRule.waitForIdle()
        return kept
    }

    private fun copy(text: String) {
        system.content = ClipRead.Text(text)
    }

    private fun routes(): List<String> =
        navController.currentBackStack.value
            .filter { it.destination.hasRoute(RouteDestination::class) }
            .map { it.toRoute<RouteDestination>().name }

    private fun parseBodies(): List<JsonObject> =
        synchronized(requests) {
            requests.filter { it.first.startsWith(PARSE_PATH) }.map { Json.parseToJsonElement(it.second) as JsonObject }
        }

    private fun JsonObject.text(key: String): String = (get(key) as JsonPrimitive).content

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

    private fun openRoute(route: String) {
        composeRule.runOnUiThread { routing.router.open(RouteRequest(route, JsonObject(emptyMap()), RouteSource.IN_APP)) }
        composeRule.waitForIdle()
    }

    private fun tapPrompt() {
        composeRule.onNodeWithTag(ClipboardUi.PROMPT_ACTION).performClick()
        composeRule.waitForIdle()
    }

    /** 存储（偏好文件全部键值）与读屏播报里都搜不到原文片段。 */
    private fun assertNoRaw(vararg fragments: String) {
        val stored = prefs().all.entries.joinToString("|") { "${it.key}=${it.value}" }
        val spoken = synchronized(announced) { announced.joinToString("|") }
        fragments.forEach {
            assertFalse("本机存储里不能有原文片段「$it」", stored.contains(it))
            assertFalse("读屏播报里不能有原文片段「$it」", spoken.contains(it))
        }
    }

    /** 界面上所有节点的文字与读屏描述。 */
    private fun screenTexts(): String {
        val out = StringBuilder()

        fun walk(node: androidx.compose.ui.semantics.SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { out.append(it.text).append('|') }
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.forEach { out.append(it).append('|') }
            node.children.forEach(::walk)
        }
        walk(composeRule.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        return out.toString()
    }

    // ================================================================ C2 + C8：白名单与口令

    // ================================================================ C3：加盐指纹

    // ================================================================ C6：系统分享

    private fun launchConsentedOnLogin() {
        composeRule.runOnUiThread {
            privacy.requestFullFunction()
            privacy.agree()
        }
        composeRule.waitForIdle()
        foreground()
        openRoute("Login")
        assertEquals("Login", routes().last())
        assertFalse("停在敏感页丢弃", share("https://m.tb.cn/h.x"))
        assertEquals("不跳走", "Login", routes().last())
        assertFalse(promptShown())
        assertEquals(0, parseBodies().size)
    }

    // ================================================================ 第 1 轮评审修复（R1-01～R1-03）

    /**
     * 记下每一次出现的提示条编号与出现那一刻的回退栈（Unconfined：在同一条主线程消息里出现、随即被回退栈同步清掉的提示条也记得到）。
     * 只记当前 [clipboard] 实例；用完 cancel。
     */
    private fun recordPrompts(): Pair<MutableList<Pair<Long, List<String>>>, Job> {
        val seen = mutableListOf<Pair<Long, List<String>>>()
        val board = clipboard
        val job =
            CoroutineScope(Dispatchers.Unconfined).launch {
                board.prompt.collect { p -> if (p != null && seen.none { it.first == p.id }) seen += p.id to routes() }
            }
        return seen to job
    }

    // ================================================================ C7

    // ---------------------------------------------------------------- 样例

    private fun singleCard(): String =
        """{"results":[{"hit":{"platform":"taobao","kind":"url","raw":"https://m.tb.cn/h.x"},"card":""" +
            """{"product_key":"tb:7Kq9LmN3pQ","item_ref":"v1.ref.sig","platform":"taobao","shop_type":"tmall","title":"降噪蓝牙耳机",""" +
            """"image":null,"shop_name":"示例旗舰店","price_fen":3990,"coupon_fen":1000,"final_price_fen":2990,""" +
            """"est_net_price_fen":2721,"rebate_min_fen":269,"rebate_max_fen":269,"rebate_basis":"normal","benefit_tags":[],""" +
            """"spec_text":null,"is_presale":false,"link_id":"0199a3b4-5c6d-7e8f-9a0b-1c2d3e4f5a70",""" +
            """"cta":{"text_key":"btn.buy.coupon"},""" +
            """"quoted_at":"2026-10-02T09:30:00+08:00","stale":false,"age_sec":12,"source":"taobao_union",""" +
            """"disclaimer_keys":["price_basis"],"ad_label":null,"availability":"ok"}}]}"""

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

    @Test
    fun fhm26_staged_share_is_dropped_when_rules_tighten_even_if_config_is_restored_before_prompt() {
        launch()
        foreground(cold = true)
        background()
        share("https://m.tb.cn/h.x 「耳机」")
        // 配置收紧的通知到达（配置快照换了）：同步检查暂存的分享，候选已不被允许 → 丢弃
        configJson = CONFIG.replace(""""flags":{}""", """"flags":{"clipboard.enabled.taobao":false}""")
        composeRule.runOnUiThread { clipboard.onRulesChanged() }
        // 之后配置又放开（恢复）：已丢弃的分享不再复活
        configJson = CONFIG
        composeRule.runOnUiThread { clipboard.onRulesChanged() }
        foreground()
        composeRule.waitForIdle()
        assertFalse("收紧时已丢弃，放开后也不出", promptShown())
        assertEquals(0, parseBodies().size)
        assertEquals("全程不写系统剪贴板", 0, system.writes)
    }

    @Test
    fun fhm26_control_staged_share_without_tightening_prompts_on_return() {
        launch()
        foreground(cold = true)
        background()
        share("https://m.tb.cn/h.x 「耳机」")
        configJson = CONFIG
        composeRule.runOnUiThread { clipboard.onRulesChanged() }
        foreground()
        idleUntil { promptShown() }
        assertTrue("规则没收紧：照常出分享提示", promptShown())
        assertEquals(0, system.writes)
    }

    // ================================================================ 第 2 轮评审 R2-05：真实 ConfigStore 接线下的收紧通知

    /** 按 AppModule 接线：规则表 + 购买状态两条配置通知（都经真实 ConfigStore）。 */
    private fun launchStoreBacked() {
        storeBacked = true
        launch()
        composeRule.runOnUiThread {
            clipboard.connectRules(configStore)
            clipboard.connectPurchaseStatus(null, configStore)
        }
        composeRule.waitForIdle()
    }

    /**
     * 后台线程（配置拉取线程）连续发布 [configs]（每份一次拉取成功），期间界面调度器暂停：主线程的消息一条都不执行，状态流的收集方只能在恢复后
     * 看到合并后的最新快照。
     */
    private fun publishInBackground(vararg configs: String) {
        shadowOf(Looper.getMainLooper()).pause()
        val fetcher =
            Thread {
                configs.forEach {
                    configJson = it
                    configStore.onForeground()
                }
            }
        fetcher.start()
        fetcher.join(TimeUnit.SECONDS.toMillis(WAIT_S))
        assertFalse(fetcher.isAlive)
        assertEquals("前提：最新快照是最后一份", Json.parseToJsonElement(configs.last()), configStore.snapshots.value.data)
    }

    private val tightened = CONFIG.replace(""""flags":{}""", """"flags":{"clipboard.enabled.taobao":false}""")

    @Test
    fun r2_05_staged_share_is_dropped_when_real_config_store_turns_platform_off_then_on_while_ui_dispatcher_is_paused() {
        launchStoreBacked()
        foreground(cold = true)
        background()
        assertTrue(share("https://m.tb.cn/h.x 「耳机」"))
        // 淘宝识别先关闭、立即又开启（两次拉取都在后台线程完成，界面调度器暂停中）
        publishInBackground(tightened, CONFIG)
        // 恢复界面调度：状态流只剩「开启」，但收紧那一份已逐份记下，放宽之前先作废了暂存分享
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()
        foreground()
        composeRule.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("曾经收紧过：暂存分享作废，配置放开后也不出", promptShown())
        assertFalse(clipboard.sharePending)
        assertEquals(0, parseBodies().size)
        assertEquals("全程不写系统剪贴板", 0, system.writes)
    }

    @Test
    fun r2_05_present_share_recheck_sees_the_tightening_even_before_the_ui_dispatcher_resumes() {
        launchStoreBacked()
        foreground(cold = true)
        background()
        assertTrue(share("https://m.tb.cn/h.x 「耳机」"))
        publishInBackground(tightened, CONFIG)
        // 界面调度器仍暂停（逐份应用的消息还没执行）：直接回前台，发布前复核也要看到曾经的收紧
        composeRule.runOnUiThread {
            clipboard.onForeground(false, hasFocus = false)
            clipboard.onWindowFocus(true)
        }
        assertFalse("发布前复核看到了曾经的收紧", promptShown())
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()
        assertFalse(promptShown())
        assertEquals(0, system.writes)
    }

    @Test
    fun r2_05_control_share_arriving_after_off_then_on_and_unrelated_config_changes_still_prompt() {
        launchStoreBacked()
        foreground(cold = true)
        background()
        // 收紧又放开都发生在分享到达之前：之后到达的分享照常出（只作废收紧之前取定的候选）
        publishInBackground(tightened, CONFIG)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(share("https://m.tb.cn/h.x 「耳机」"))
        // 与规则无关的配置变化（不收紧）不作废
        publishInBackground(CONFIG.replace("share.example.test", "share2.example.test"), CONFIG)
        shadowOf(Looper.getMainLooper()).idle()
        foreground()
        idleUntil { promptShown() }
        assertTrue("规则没在分享之后收紧：照常出分享提示", promptShown())
        assertEquals(0, system.writes)
    }

    private companion object {
        const val PARSE_PATH = "/v1/inputs/parse"
        const val SEARCH_PATH = "/v1/products/search"
        const val LINK = "https://e.tb.cn/h.AbCdEf?tk=Xy12"
        const val CONFIG =
            """{"clipboard":{"consent_version":"1"},""" +
                """"share_domains":["share.example.test"],"features":{"flags":{},"platform_status":{"taobao":"on","jd":"on"},""" +
                """"search_status":{"taobao":"on","jd":"on"}}}"""
        const val WAIT_S = 5L
        const val POLL_MS = 10L
        const val LATE_MS = 200L
    }
}
