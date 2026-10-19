package com.couli.app.clipboard

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import com.couli.app.search.AppSearch
import com.couli.app.shell.AppShell
import com.couli.core.clientcore.ConfigReply
import com.couli.core.clientcore.ConfigStore
import com.couli.core.clipboard.ClipRead
import com.couli.core.clipboard.ClipboardFingerprint
import com.couli.core.clipboard.ClipboardRecords
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
import org.junit.Assert.assertArrayEquals
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
 * FU-CLIP（剪贴板补齐）的本地假服务集成测试：真实 AppShell + NavHost + AppRouting + AppPrivacy + AppSearch + ApiClient + MockWebServer，计数的假系统
 * 剪贴板，真实的偏好文件（盐经样例加密）。生命周期经 MainActivity 的同一组入口；系统分享经接收 Activity 调的同一个入口 [AppClipboard.receiveShare]。
 * 每个用例都断言系统剪贴板写入次数（识别、提示、上传、分享导入的前后都是 0）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
@Suppress("LargeClass", "TooManyFunctions") // 验收场景逐条一个用例
class FuClipIntegrationTest {
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
            configData = { Json.parseToJsonElement(configJson) as JsonObject },
            prefs = ClipboardPreferences(context, cipher),
            importPreferences = importPreferences,
            sessionEpoch = { sessionEpoch },
            clock = { now },
            randomSalt = { SAMPLE_SALT.copyOf() },
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
                tokens = MemorySessionTokens(),
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
        pageApi = GatedPageApi(ApiClientPageApi(api))
        composeRule.runOnUiThread { clipboard = newClipboard() }
        val search = AppSearch(api, store, routing, privacy = { privacy.conclusion }, trade = null, clipboard = clipboard)
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
                    homeContent = { Box(Modifier.fillMaxSize()) },
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
        composeRule.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::walk)
        return out.toString()
    }

    // ================================================================ C2 + C8：白名单与口令

    @Test
    fun c2_disguisedOrUnlistedLinksNeverPromptNorUpload() {
        launch()
        for (url in listOf("https://taobao.com.evil.cn/x", "https://taobao.com@evil.cn/x", "http://127.0.0.1/x", "https://example.com/x")) {
            copy("看看这个 $url 「样例标题」")
            background()
            foreground()
            assertFalse("$url 不出商品提示", promptShown())
            composeRule.runOnUiThread { clipboard.paste(ManualPasteAction.ENTRY_HOME) }
            composeRule.waitForIdle()
            assertNull("$url 无候选安静结束", clipboard.result.value)
        }
        assertEquals("parse 请求数 0", 0, parseBodies().size)
        assertEquals(0, system.writes)
    }

    /** 混排：请求体只含白名单链接；「」标题里夹带的非白名单链接让标题整段舍弃（共性第 5 条，S0）。 */
    @Test
    fun c2_lesson5_mixedTextUploadsOnlyWhitelistedLinksAndDropsATitleCarryingAPrivateLink() {
        launch()
        val raw = "私人 https://example.com/a?order=9 https://m.tb.cn/h.x 「查看订单 https://example.com/private?order=123」"
        copy(raw)
        foreground(cold = true)
        idleUntil { clipboard.result.value is ClipboardResult.CardList }
        assertFalse(promptShown())
        idleUntil { parseBodies().size == 1 }
        assertEquals("https://m.tb.cn/h.x", parseBodies().single().text("text"))
        assertEquals("clipboard", parseBodies().single().text("scene"))
        // 手动粘贴同一份
        background()
        foreground()
        composeRule.runOnUiThread { clipboard.paste(ManualPasteAction.ENTRY_HOME) }
        idleUntil { parseBodies().size == 2 }
        assertEquals("https://m.tb.cn/h.x", parseBodies()[1].text("text"))
        assertEquals(0, system.writes)
        assertNoRaw("example.com", "order=", "私人")
    }

    /** 纯口令：自动直接识别，请求体只含口令片段。 */
    @Test
    fun c2_c7_pureTaobaoTokenPromptsAsTaobaoTokenAndUploadsOnlyTheToken() {
        launch()
        copy("复制这段 ￥AbCdEfGh12￥ 打开手机淘宝")
        foreground(cold = true)
        idleUntil { clipboard.result.value is ClipboardResult.CardList }
        assertFalse(promptShown())
        assertEquals("无需点击已上传", 1, parseBodies().size)
        idleUntil { parseBodies().size == 1 }
        assertEquals("￥AbCdEfGh12￥", parseBodies().single().text("text"))
        assertEquals(0, system.writes)
    }

    /** clipboard.enabled.taobao=false：淘宝链接不提示、不上传，京东照常。 */
    @Test
    fun c2_k03_taobaoSwitchedOffByFlagStaysSilentWhileJdStillPrompts() {
        configJson = CONFIG.replace(""""flags":{}""", """"flags":{"clipboard.enabled.taobao":false}""")
        launch()
        copy("https://m.tb.cn/h.x 「耳机」")
        foreground(cold = true)
        assertFalse("淘宝被关", promptShown())
        composeRule.runOnUiThread { clipboard.paste(ManualPasteAction.ENTRY_HOME) }
        composeRule.waitForIdle()
        assertEquals("淘宝手动粘贴也不上传", 0, parseBodies().size)
        copy("https://u.jd.com/abc 「耳机」")
        background()
        foreground()
        idleUntil { clipboard.result.value is ClipboardResult.CardList }
        assertFalse(promptShown())
        idleUntil { parseBodies().size == 1 }
        assertEquals("https://u.jd.com/abc\n耳机", parseBodies().single().text("text"))
    }

    /** 规则表变化：已发出请求的候选若不再被允许，迟到响应不得呈现（§2.1 第 5 条）。 */
    @Test
    fun c2_rulesTighteningInvalidatesAnInFlightAutomaticResult() {
        launch()
        val reply = CompletableDeferred<ApiResult>()
        gatedQueue += reply
        copy("https://m.tb.cn/h.x")
        foreground(cold = true)
        idleUntil { parseBodies().size == 1 }
        configJson = CONFIG.replace(""""flags":{}""", """"flags":{"clipboard.enabled.taobao":false}""")
        composeRule.runOnUiThread { clipboard.onRulesChanged() }
        composeRule.runOnUiThread { reply.complete(ApiResult.Success(Json.parseToJsonElement(singleCard()), "trace-000123", null)) }
        idleUntil { !clipboard.busy.value }
        assertNull("规则收紧后在途卡作废", clipboard.result.value)
        assertFalse(promptShown())
        assertEquals(1, parseBodies().size)
    }

    // ================================================================ C3：加盐指纹

    @Test
    fun c3_selfWrittenContentIsNotPromptedAndStorageHoldsOnlyTheHmac() {
        launch()
        composeRule.runOnUiThread { assertTrue(clipboard.writer.write(LINK, "tpwd")) }
        assertEquals("自写走唯一写入口", 1, system.writes)
        foreground(cold = true)
        assertFalse("自写内容不提示", promptShown())
        assertEquals("自写内容不自动上传", 0, parseBodies().size)
        assertNull(clipboard.result.value)
        val hmac = ClipboardFingerprint(SAMPLE_SALT).hash(LINK)
        val stored = prefs().all.entries.joinToString("|") { "${it.key}=${it.value}" }
        assertTrue("存储里是本次盐的 HMAC 指纹", stored.contains(hmac))
        assertFalse("不是未加盐 SHA-256", stored.contains(ClipboardText.sha256(LINK)))
        assertTrue("盐以密文存放", prefs().contains(ClipboardPreferences.KEY_SALT))
        assertFalse("盐不是明文 Base64", stored.contains(android.util.Base64.encodeToString(SAMPLE_SALT, android.util.Base64.NO_WRAP)))
        assertNoRaw("e.tb.cn", "AbCdEf", "tk=Xy12")
    }

    @Test
    fun c3_withdrawDeletesSaltAndBothRecordsThenTheSameContentPromptsAgainAfterReconsent() {
        launch()
        copy(LINK)
        foreground(cold = true)
        idleUntil { clipboard.result.value is ClipboardResult.CardList }
        assertFalse(promptShown())
        composeRule.runOnUiThread { clipboard.consumeResult(checkNotNull(clipboard.result.value)) }
        composeRule.runOnUiThread { assertTrue(clipboard.writer.write("复制的口令样例", "tpwd")) }
        assertTrue(prefs().contains(ClipboardPreferences.KEY_SALT))
        composeRule.runOnUiThread { privacy.withdraw() }
        composeRule.waitForIdle()
        assertFalse("盐已删除", prefs().contains(ClipboardPreferences.KEY_SALT))
        assertTrue("两份记录与 saltId 都删除", prefs().all.keys.none { it.startsWith("hash.") })
        composeRule.runOnUiThread {
            privacy.requestFullFunction()
            privacy.agree()
            // R06 撤回已清用途同意；此 hash 回归显式恢复功能前提，按新口径直接出卡。
            importPreferences.enable("1")
            clipboard.setAutoSwitch(true)
        }
        composeRule.waitForIdle()
        assertEquals(PrivacyState.CONSENTED, privacy.conclusion)
        // 别的 App 再复制同一条链接（上面的自写把剪贴板换成了口令样例）
        copy(LINK)
        background()
        foreground()
        idleUntil { clipboard.result.value is ClipboardResult.CardList }
        assertEquals("再同意后同一候选重新识别", 2, parseBodies().size)
        assertEquals(1, system.writes)
    }

    @Test
    fun c3_aRotatedSaltNoLongerExcludesTheOldSelfWrite() {
        launch()
        composeRule.runOnUiThread { assertTrue(clipboard.writer.write(LINK, "tpwd")) }
        foreground(cold = true)
        assertFalse(promptShown())
        assertEquals("换盐前自写排除生效", 0, parseBodies().size)
        assertNull(clipboard.result.value)
        // 人为换盐（盐丢失后新建同理）：旧自写记录属于旧盐，整份作废
        assertTrue(ClipboardPreferences(context, cipher).salt.write(OTHER_SAMPLE_SALT))
        background()
        foreground()
        idleUntil { clipboard.result.value is ClipboardResult.CardList }
        assertEquals("旧自写记录不再排除", 1, parseBodies().size)
        val stored = prefs().all.entries.joinToString("|") { "${it.key}=${it.value}" }
        val persistedSalt = (ClipboardPreferences(context, cipher).salt.read() as ClipboardSaltRead.Present).salt
        assertArrayEquals(OTHER_SAMPLE_SALT, persistedSalt)
        assertTrue("商品摘要使用新的持久盐", stored.contains(ClipboardFingerprint(persistedSalt).hash(LINK)))
        assertFalse("不回退到无盐摘要", stored.contains(ClipboardText.sha256(LINK)))
        assertFalse("旧盐的指纹已清掉", stored.contains(ClipboardFingerprint(SAMPLE_SALT).hash(LINK)))
    }

    @Test
    fun c3_whenTheSaltIsUnavailableSelfWritesAreRefusedAndNoAutoUploadButManualPasteWorks() {
        launch()
        cipher.failing = true
        composeRule.runOnUiThread { assertFalse("自写记不上 → 不写系统剪贴板、返回失败", clipboard.writer.write(LINK, "tpwd")) }
        assertEquals(0, system.writes)
        copy(LINK)
        foreground(cold = true)
        // foreground 已结算主线程/Compose；API 在 send 入口记数，不能用提示条是否出现替代上传断言。
        assertTrue("盐不可用允许不读或只读不传", system.reads in 0..1)
        assertEquals("自动路径不能上传", 0, parseBodies().size)
        assertNull("自动路径没有结果卡", clipboard.result.value)
        val autoReads = system.reads
        composeRule.runOnUiThread { clipboard.paste(ManualPasteAction.ENTRY_HOME) }
        idleUntil { clipboard.result.value is ClipboardResult.CardList }
        assertEquals("这一次请求必须来自手动", 1, parseBodies().size)
        assertEquals(autoReads + 1, system.reads)
        assertFalse(
            "不回落到未加盐 hash",
            prefs()
                .all.values
                .joinToString()
                .contains(ClipboardText.sha256(LINK)),
        )
    }

    @Test
    fun c3_upgradeDropsTheOldUnsaltedKeysOnFirstRun() {
        prefs()
            .edit()
            .putString("hash." + ClipboardRecords.LEGACY_KEYS[0], ClipboardText.sha256(LINK))
            .putString("hash." + ClipboardRecords.LEGACY_KEYS[1], ClipboardText.sha256(LINK) + "@1")
            .commit()
        launch(decision = "basic_mode")
        assertTrue("未同意也删旧记录", ClipboardRecords.LEGACY_KEYS.none { prefs().contains("hash.$it") })
        assertFalse("基本模式不生成盐", prefs().contains(ClipboardPreferences.KEY_SALT))
    }

    // ================================================================ C6：系统分享

    @Test
    fun c6_c7_shareFromTheSearchPageReturnsHomeShowsThePlatformAndUploadsOnlyCandidatesAsShareExt() {
        launch()
        copy("剪贴板里是普通聊天文字，没有商品候选")
        foreground(cold = true)
        openRoute("Search")
        assertEquals("Search", routes().last())
        background()
        val readsBefore = system.reads
        assertTrue(share("【淘宝】分享给你 https://m.tb.cn/h.Demo01?tk=Sample 「降噪蓝牙耳机」 聊天样例13800138000 验证码482731"))
        foreground()
        assertTrue("经 Router 回到首页 Tab 根页", routes().isEmpty())
        assertTrue(clipboard.prompt.value?.share == true)
        composeRule.onNodeWithText("收到分享的淘宝商品，查返利？", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("淘宝商品链接 m.tb.cn/…", useUnmergedTree = true).assertIsDisplayed()
        val texts = screenTexts()
        assertTrue("整条读屏含平台", texts.contains("检测到淘宝商品链接，查返利"))
        assertFalse("不显示路径与查询", texts.contains("Demo01") || texts.contains("tk=Sample"))
        assertEquals("分享回前台不读剪贴板", readsBefore, system.reads)
        assertEquals("点击前请求数 0", 0, parseBodies().size)
        tapPrompt()
        idleUntil { parseBodies().size == 1 }
        val body = parseBodies().single()
        assertEquals("share_ext", body.text("scene"))
        assertEquals("https://m.tb.cn/h.Demo01?tk=Sample\n降噪蓝牙耳机", body.text("text"))
        idleUntil { routes().lastOrNull() == "ProductDetail" }
        assertEquals(0, system.writes)
        assertNoRaw("13800138000", "482731", "聊天样例", "Demo01")
    }

    @Test
    fun c6_nonProductShareShowsTheLocal30132WithTheTitleAndEmptyShareOnlyGoesHome() {
        launch()
        foreground(cold = true)
        share("这不是首行商品名 https://example.com/a 「保温杯 500ml」")
        assertTrue(clipboard.result.value is ClipboardResult.Unrecognized)
        assertEquals("保温杯 500ml", (clipboard.result.value as ClipboardResult.Unrecognized).prefill)
        share("首行文字\n第二行")
        assertNull("没有「」标题就留空，不拿首行", (clipboard.result.value as ClipboardResult.Unrecognized).prefill)
        // 标题里夹带非白名单链接 → 预填整段舍弃（共性第 8 条）
        share("看看 「订单 https://example.com/private?order=1」")
        assertNull((clipboard.result.value as ClipboardResult.Unrecognized).prefill)
        openRoute("Search")
        share("")
        assertTrue("空文本只回首页", routes().isEmpty())
        assertFalse(promptShown())
        assertEquals(0, parseBodies().size)
        assertEquals(0, system.writes)
    }

    @Test
    fun c6_k18_k19_basicModeAndSensitivePagesDropTheShareWithoutLeaving() {
        launch(decision = "basic_mode")
        assertFalse("基本模式丢弃", share("https://m.tb.cn/h.x"))
        assertFalse(clipboard.sharePending)
        launchConsentedOnLogin()
    }

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

    /** 共性第 2、7 条：后台暂存的分享带到达身份；真实换号作废，会话状态的恢复通知（登录世代没变）不作废。 */
    @Test
    fun c6_lesson7_aRealAccountSwitchDropsAPendingShareButASessionRestoreNoticeDoesNot() {
        launch()
        foreground(cold = true)
        background()
        share("https://m.tb.cn/h.x")
        composeRule.runOnUiThread { clipboard.onSessionChanged() }
        foreground()
        assertTrue("登录世代没变：照常出条", clipboard.prompt.value?.share == true)
        background()
        share("https://m.tb.cn/h.y")
        composeRule.runOnUiThread {
            sessionEpoch += 1
            clipboard.onSessionChanged()
        }
        foreground()
        assertFalse("换号：丢弃", promptShown())
        background()
        share("https://m.tb.cn/h.z")
        composeRule.runOnUiThread { inFlight.onEnvironmentSwitching() }
        foreground()
        assertFalse("切环境（隐私 / 环境世代变了）：丢弃", promptShown())
        assertEquals(0, parseBodies().size)
    }

    /** 冷启动：外壳还没告知当前界面之前到达的分享，等外壳与前台都就绪后才处理一次；之后重建 / 再回前台不重复处理。 */
    @Test
    fun c6_lesson7_k17_aColdStartShareWaitsForTheShellAndIsHandledExactlyOnce() {
        launch()
        // launch 已组合外壳：这里模拟「到达时在后台、前台未就绪」再回前台
        background()
        share("https://m.tb.cn/h.x")
        assertTrue(clipboard.sharePending)
        foreground()
        assertTrue(clipboard.prompt.value?.share == true)
        composeRule.runOnUiThread { clipboard.onPromptClose() }
        // 最近任务恢复、配置变更重建：MainActivity 再次走 onNewIntent / onStart，交接已取走，不重复处理
        composeRule.runOnUiThread { clipboard.onShareDelivered() }
        background()
        foreground()
        assertFalse("不重复出条", clipboard.prompt.value?.share == true)
    }

    /** 共性第 9 条：暂存分享在发布前按当前规则再校验，收紧后失效就丢弃。 */
    @Test
    fun c6_lesson9_aPendingShareIsRecheckedAgainstTightenedRules() {
        launch()
        foreground(cold = true)
        background()
        share("https://m.tb.cn/h.x 「耳机」")
        configJson = CONFIG.replace(""""flags":{}""", """"flags":{"clipboard.enabled.taobao":false}""")
        foreground()
        assertFalse("淘宝已被关：丢弃", promptShown())
        assertEquals(0, parseBodies().size)
    }

    /** K22：parse 在途时新分享忽略；只有未点击的提示条时新分享替换它。共享 busy（C5）：在途时分享条、粘贴入口、结果按钮都不读不发。 */
    @Test
    fun c5_c6_k22_parseInFlightIgnoresNewSharesAndBlocksEveryEntry() {
        launch()
        foreground(cold = true)
        share("https://m.tb.cn/h.x")
        val first = checkNotNull(clipboard.prompt.value).id
        share("https://u.jd.com/abc")
        val second = checkNotNull(clipboard.prompt.value)
        assertNotEquals("未点击的提示条被新分享替换", first, second.id)
        composeRule.onNodeWithText("京东商品链接 u.jd.com/…", useUnmergedTree = true).assertIsDisplayed()
        val reply = CompletableDeferred<ApiResult>()
        gatedQueue += reply
        tapPrompt()
        idleUntil { parseBodies().size == 1 }
        assertTrue(clipboard.busy.value)
        assertFalse("在途时新分享忽略", share("https://m.tb.cn/h.y"))
        copy(LINK)
        val reads = system.reads
        composeRule.runOnUiThread {
            clipboard.paste(ManualPasteAction.ENTRY_HOME)
            clipboard.onPromptAction(second.id)
        }
        composeRule.waitForIdle()
        assertEquals("在途时粘贴入口不读", reads, system.reads)
        assertEquals("在途时不再发", 1, parseBodies().size)
        composeRule.onNodeWithTag(ClipboardUi.HOME_PASTE_CARD).assertIsNotEnabled()
        composeRule.runOnUiThread { reply.complete(ApiResult.TransportFailure("io", null, null, false)) }
        idleUntil { !clipboard.busy.value }
    }

    /** 共性第 4 条：结果操作（用商品名搜索）在 parse 在途时同样不执行，界面同步置灰。 */
    @Test
    fun c5_lesson4_resultActionsHonourTheSharedBusy() {
        launch()
        foreground(cold = true)
        share("不是商品 「保温杯 500ml」")
        val result = clipboard.result.value as ClipboardResult.Unrecognized
        val reply = CompletableDeferred<ApiResult>()
        gatedQueue += reply
        copy(LINK)
        composeRule.onNodeWithTag(ClipboardUi.HOME_PASTE_CARD).performClick()
        idleUntil { parseBodies().size == 1 }
        assertTrue(clipboard.busy.value)
        composeRule.onNodeWithTag(ClipboardScreens.SEARCH_BY_NAME).assertIsNotEnabled()
        composeRule.runOnUiThread { clipboard.searchByName(result) }
        composeRule.waitForIdle()
        assertTrue("在途时不跳搜索", routes().isEmpty())
        composeRule.runOnUiThread { reply.complete(ApiResult.TransportFailure("io", null, null, false)) }
        idleUntil { !clipboard.busy.value }
    }

    /** 共性第 3 条：保留真实按钮的旧点击回调，提示条换成新分享后执行，无效果（不转调新提示）。 */
    @Test
    fun lesson3_anOldButtonCallbackRetainedAcrossAReplacementDoesNothing() {
        launch()
        foreground(cold = true)
        share("https://m.tb.cn/h.x")
        val oldClick =
            composeRule
                .onNodeWithTag(ClipboardUi.PROMPT_ACTION)
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action
        val oldClose =
            composeRule
                .onNodeWithTag(ClipboardUi.PROMPT_CLOSE)
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action
        share("https://u.jd.com/abc")
        val current = checkNotNull(clipboard.prompt.value)
        composeRule.runOnUiThread {
            checkNotNull(oldClick).invoke()
            checkNotNull(oldClose).invoke()
        }
        composeRule.waitForIdle()
        idleUntil { !clipboard.busy.value }
        assertEquals("旧点击不发请求", 0, parseBodies().size)
        assertEquals("旧关闭不清掉新提示", current.id, clipboard.prompt.value?.id)
    }

    /** 共性第 6 条：分享回首页后，外壳对旧页面（搜索页）迟到的回退栈 / Tab 通知不清掉首页的新提示。 */
    @Test
    fun lesson6_lateShellNoticesAfterReturningHomeKeepTheSharePrompt() {
        launch()
        foreground(cold = true)
        openRoute("Search")
        share("https://m.tb.cn/h.x")
        assertTrue(routes().isEmpty())
        val id = checkNotNull(clipboard.prompt.value).id
        composeRule.runOnUiThread {
            clipboard.onShell("Home", basicMode = false)
            clipboard.onBackStack(null, null, emptySet())
        }
        composeRule.waitForIdle()
        assertEquals(id, clipboard.prompt.value?.id)
    }

    // ================================================================ 第 1 轮评审修复（R1-01～R1-03）

    /**
     * R1-01：进程恢复到非首页 Tab（我的）后经系统分享冷启动——外壳先同步恢复后的 Tab 与回退栈、导航器接好之后才处理分享：经 Router 回到首页
     * Tab 根页出条，之后外壳的 Tab / 回退栈通知不清掉它。
     */
    @Test
    fun r1_01_aProcessRestoredOnTheMeTabShowsAColdShareOnHomeAndLaterShellNoticesKeepIt() {
        val restoration = StateRestorationTester(composeRule)
        launch(restoration = restoration)
        foreground(cold = true)
        openRoute("Me")
        assertFalse("停在「我的」Tab", clipboard.homeRoot())
        background()
        // 进程被回收后由系统分享拉起：新的剪贴板模块（外壳还没组合），接收 Activity 先到，MainActivity 的 onStart 先于首帧组合
        composeRule.runOnUiThread { clipboard = newClipboard() }
        assertTrue(share("【淘宝】分享给你 https://m.tb.cn/h.Demo01 「降噪蓝牙耳机」"))
        composeRule.runOnUiThread { clipboard.onForeground(cold = true, hasFocus = false) }
        assertTrue("外壳还没就绪：暂存", clipboard.sharePending)
        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        assertTrue("回到首页 Tab 根页", clipboard.homeRoot())
        val prompt = checkNotNull(clipboard.prompt.value) { "分享提示条出现在首页" }
        assertTrue(prompt.share)
        assertFalse(clipboard.sharePending)
        composeRule.runOnUiThread {
            clipboard.onShell("Home", basicMode = false)
            clipboard.onBackStack(null, null, emptySet())
        }
        composeRule.waitForIdle()
        assertEquals("后续外壳通知不清掉提示", prompt.id, clipboard.prompt.value?.id)
        composeRule.onNodeWithText("收到分享的淘宝商品，查返利？", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(0, parseBodies().size)
        assertEquals(0, system.writes)
    }

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

    /**
     * R2-01：进程恢复到首页 Tab 上推入的二级页（Search）后经系统分享冷启动。恢复后的外壳首帧先同步 Tab（缺省就是首页），回退栈要等 NavHost
     * 收集才同步：这一间隙里分享不被消费（否则会按「首页根页」当场出条，随即被恢复的 Search 栈顶清掉，用户停在搜索页、提示丢失）；回退栈同步后经
     * Router 回到首页 Tab 根页，提示条只出一次，出现时已在首页，之后外壳的通知不清掉它。
     */
    @Test
    fun r2_01_aProcessRestoredOnASearchPageHoldsAColdShareUntilTheBackStackSyncsThenPromptsOnceOnHome() {
        val restoration = StateRestorationTester(composeRule)
        launch(restoration = restoration)
        foreground(cold = true)
        openRoute("Search")
        assertEquals("首页 Tab 上推了搜索页", listOf("Search"), routes())
        background()
        // 进程被回收后由系统分享拉起：新的剪贴板模块，接收 Activity 先到，MainActivity 的 onStart 先于首帧组合
        composeRule.runOnUiThread { clipboard = newClipboard() }
        val (seen, job) = recordPrompts()
        try {
            assertTrue(share("【淘宝】分享给你 https://m.tb.cn/h.Demo01 「降噪蓝牙耳机」"))
            composeRule.runOnUiThread { clipboard.onForeground(cold = true, hasFocus = false) }
            assertTrue("外壳还没就绪：暂存", clipboard.sharePending)
            // 恢复后首帧的 SideEffect 先同步 Tab（首页、非基本模式），导航器已接好，回退栈还没同步
            composeRule.runOnUiThread { clipboard.onShell("Home", basicMode = false) }
            composeRule.waitForIdle()
            assertTrue("回退栈同步之前不消费分享", clipboard.sharePending)
            assertFalse(promptShown())
            assertTrue("同步之前没出过提示条", seen.isEmpty())
            assertEquals("同步之前不跳页", listOf("Search"), routes())
            restoration.emulateSavedInstanceStateRestore()
            composeRule.waitForIdle()
            assertTrue("回到首页 Tab 根页", clipboard.homeRoot())
            assertTrue("搜索页出栈", routes().isEmpty())
            val prompt = checkNotNull(clipboard.prompt.value) { "分享提示条出现在首页" }
            assertTrue(prompt.share)
            assertFalse(clipboard.sharePending)
            assertEquals("提示条只出一次", listOf(prompt.id), seen.map { it.first })
            assertEquals("出现那一刻已在首页（不是先在恢复的搜索页上出条）", emptyList<String>(), seen.single().second)
            composeRule.runOnUiThread {
                clipboard.onShell("Home", basicMode = false)
                clipboard.onBackStack(null, null, emptySet())
            }
            composeRule.waitForIdle()
            assertEquals("后续外壳通知不清掉提示", prompt.id, clipboard.prompt.value?.id)
            composeRule.onNodeWithText("收到分享的淘宝商品，查返利？", useUnmergedTree = true).assertIsDisplayed()
            background()
            foreground()
            assertEquals("再回前台不重复出条", 1, seen.size)
            assertEquals(0, parseBodies().size)
            assertEquals(0, system.writes)
        } finally {
            job.cancel()
        }
    }

    /**
     * R2-01：进程恢复到敏感页（Login）后经系统分享冷启动。到达时外壳还没组合、按缺省首页过闸门先暂存；回退栈同步出恢复的 Login 栈顶后复核
     * 敏感页（K19）：丢弃，不跳走，任何时刻都不出提示条（包括回退栈同步之前），之后离开登录页回首页也不补出。
     */
    @Test
    fun r2_01_aProcessRestoredOnTheLoginPageDropsAColdShareWithoutLeavingOrPrompting() {
        val restoration = StateRestorationTester(composeRule)
        launch(restoration = restoration)
        foreground(cold = true)
        openRoute("Login")
        assertEquals("停在登录页", listOf("Login"), routes())
        background()
        composeRule.runOnUiThread { clipboard = newClipboard() }
        val (seen, job) = recordPrompts()
        try {
            assertTrue("外壳还没组合：先暂存", share("【淘宝】分享给你 https://m.tb.cn/h.Demo01 「降噪蓝牙耳机」"))
            composeRule.runOnUiThread { clipboard.onForeground(cold = true, hasFocus = false) }
            assertTrue(clipboard.sharePending)
            restoration.emulateSavedInstanceStateRestore()
            composeRule.waitForIdle()
            assertFalse("恢复到敏感页：丢弃", clipboard.sharePending)
            assertEquals("不跳走", listOf("Login"), routes())
            assertFalse(promptShown())
            assertTrue("任何时刻都没出过提示条", seen.isEmpty())
            composeRule.runOnUiThread { navController.popBackStack() }
            composeRule.waitForIdle()
            assertTrue(routes().isEmpty())
            background()
            foreground()
            assertFalse("回首页后不补出", promptShown())
            assertTrue(seen.isEmpty())
            assertEquals(0, parseBodies().size)
            assertEquals(0, system.writes)
        } finally {
            job.cancel()
        }
    }

    /**
     * R1-02：后台收到的分享带到达时的页面票据；回前台之前入站路由 / 待办恢复把页面切到 Search（或敏感浮层出现），暂存同步作废：回前台不回首页、
     * 不出旧分享。
     */
    @Test
    fun r1_02_aPageChangeWhileTheShareWaitsInTheBackgroundDropsItBeforeForeground() {
        launch()
        foreground(cold = true)
        background()
        assertTrue(share("https://m.tb.cn/h.x 「耳机」"))
        // 后台期间的入站路由或待办恢复（经 Router）把页面切到 Search
        openRoute("Search")
        assertFalse("页面变化的同步出口作废暂存", clipboard.sharePending)
        foreground()
        assertEquals("不拉回首页", "Search", routes().last())
        assertFalse("不出旧分享", promptShown())
        // 敏感浮层出现：同样作废
        background()
        assertTrue(share("https://u.jd.com/abc"))
        composeRule.runOnUiThread { clipboard.onSensitiveOverlay() }
        assertFalse(clipboard.sharePending)
        foreground()
        assertEquals("Search", routes().last())
        assertFalse(promptShown())
        // 对照：没有页面变化的后台分享照常回首页出条（本次分享主动回首页的受控交接）
        background()
        assertTrue(share("https://m.tb.cn/h.y"))
        foreground()
        assertTrue(routes().isEmpty())
        assertTrue(clipboard.prompt.value?.share == true)
        assertEquals(0, parseBodies().size)
        assertEquals(0, system.writes)
    }

    /**
     * R1-03：首页保留游客的自动邀请提示时手动粘贴商品发起慢 parse；重组前到达的确认点击 / 排队的旧回调进入 onPromptAction，共享 busy 在邀请分支之前拦下：
     * 不预填邀请码、不打开登录页，在途识别不受影响。
     */
    @Test
    fun r1_03_anInvitePromptConfirmArrivingWhileAManualParseIsInFlightDoesNothing() {
        launch()
        copy("好友邀请你加入凑狸 https://share.example.test/i/K7M2QX")
        foreground(cold = true)
        val invite = checkNotNull(clipboard.prompt.value)
        assertEquals("K7M2QX", invite.inviteCode)
        val oldConfirm =
            composeRule
                .onNodeWithTag(ClipboardUi.PROMPT_ACTION)
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action
        val reply = CompletableDeferred<ApiResult>()
        gatedQueue += reply
        copy(LINK)
        composeRule.runOnUiThread { clipboard.paste(ManualPasteAction.ENTRY_HOME) }
        idleUntil { parseBodies().size == 1 }
        assertTrue(clipboard.busy.value)
        assertEquals("邀请提示仍在", invite.id, clipboard.prompt.value?.id)
        composeRule.runOnUiThread {
            checkNotNull(oldConfirm).invoke()
            clipboard.onPromptAction(invite.id)
        }
        composeRule.waitForIdle()
        assertFalse("不预填邀请码", clipboard.invitePrefill.pending)
        assertTrue("不打开登录页", routes().isEmpty())
        assertTrue("在途识别不被打断", clipboard.busy.value)
        assertEquals(1, parseBodies().size)
        composeRule.runOnUiThread { reply.complete(ApiResult.TransportFailure("io", null, null, false)) }
        idleUntil { !clipboard.busy.value }
        assertEquals(0, system.writes)
    }

    // ================================================================ C7

    @Test
    fun c7_autoDirectlyUploadsAllCandidatesWithoutDisplayingRawPaths() {
        launch()
        val raw = "看看 https://detail.tmall.com/item.htm?id=1&spm=Track https://u.jd.com/abc 「保温杯」"
        copy(raw)
        foreground(cold = true)
        idleUntil { clipboard.result.value is ClipboardResult.CardList }
        assertFalse(promptShown())
        val texts = screenTexts()
        assertTrue("遍历范围必须包含独立窗口里的商品卡", texts.contains("降噪蓝牙耳机"))
        assertFalse("不在界面泄漏路径与查询", texts.contains("item.htm") || texts.contains("spm=Track"))
        assertEquals("https://detail.tmall.com/item.htm?id=1&spm=Track\nhttps://u.jd.com/abc\n保温杯", parseBodies().single().text("text"))
        assertEquals(0, system.writes)
    }

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
    }
}
