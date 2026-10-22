package com.couli.app.me

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.swipeUp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.FlowWait
import com.couli.app.clipboard.AppClipboard
import com.couli.app.clipboard.ClipboardPreferences
import com.couli.app.clipboard.ClipboardScreens
import com.couli.app.clipboard.SAMPLE_SALT
import com.couli.app.clipboard.SampleCipher
import com.couli.app.login.AppLogin
import com.couli.app.privacy.AppPrivacy
import com.couli.app.routing.AppRouting
import com.couli.app.routing.RouteDestination
import com.couli.app.routing.RoutingPrivacyHooks
import com.couli.app.shell.AppShell
import com.couli.app.shell.BASIC_MODE_PAGE_TAG
import com.couli.app.web.bridgeGetMe
import com.couli.core.clientcore.AmountMaskStore
import com.couli.core.clientcore.ConfigReply
import com.couli.core.clientcore.ConfigStore
import com.couli.core.clipboard.ClipRead
import com.couli.core.clipboard.SystemClipboard
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.designsystem.CouliAnnouncer
import com.couli.core.designsystem.LocalCouliAnnouncer
import com.couli.core.me.MeDraftTexts
import com.couli.core.me.MeLocalStore
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
import com.couli.core.push.PushTokenSource
import com.couli.core.router.PrivacyState
import com.couli.core.router.RouteRequest
import com.couli.core.router.RouteSource
import com.couli.core.router.SessionState
import com.couli.core.router.UpdateCheck
import com.couli.feature.login.LegalVersions
import com.couli.feature.login.PendingInviteCode
import com.couli.feature.me.MeTags
import com.couli.feature.me.NicknameBackend
import com.couli.feature.me.NotifyBackend
import java.net.URLDecoder
import java.time.OffsetDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 「我的」、设置、隐私中心、关于、封禁说明与申诉、推送上报、统一客服出口的本地假服务集成测试（APP-12 brief §2.17）：真实 AppShell + NavHost + AppRouting + AppPrivacy
 * + AppLogin + AppMe + ApiClient（只把传输层换成 MockWebServer），页面经 Router 打开、经页面控件操作。昵称、邀请码照 sample-data（凑狸用户、小狸爱买买、K7M3QX），
 * 令牌、账号、客服链接都是明显的测试值。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("LargeClass", "TooManyFunctions", "LongMethod") // 验收场景逐条一个用例
class AppMeIntegrationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule val testName = TestName()
    private val visualTransport get() = testName.methodName.startsWith("visual")
    private val visualAnnouncements = mutableListOf<String>()
    private val visualA11y by lazy { MeVisualAnnouncementProbe(composeRule) }

    @Volatile private var visualReadFailure = false

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = OffsetDateTime.parse("2026-10-10T12:00:00+08:00").toInstant().toEpochMilli()

    @Volatile private var clock = now
    private lateinit var server: MockWebServer
    private lateinit var routing: AppRouting
    private lateinit var navController: NavHostController
    private lateinit var login: AppLogin
    private lateinit var api: ApiClient
    private lateinit var me: AppMe
    private lateinit var privacy: AppPrivacy
    private lateinit var tokens: MemorySessionTokens
    private lateinit var local: MeLocalStore
    private lateinit var cleaners: CredentialCleanerRegistry
    private val inFlight = InFlightCalls()
    private val system = FakeClipboard()
    private val ports = FakeSystem()
    private val push = FakePushSource()
    private var forced = 0
    private var update = UpdateCheck.OK
    private val requests = mutableListOf<Recorded>()
    private val answers = mutableMapOf<String, (RecordedRequest) -> MockResponse>()

    // 假服务的状态
    @Volatile private var risk = "normal"

    @Volatile private var reason: String? = null

    @Volatile private var frozenUntil: String? = null

    @Volatile private var nickname = "凑狸用户"

    @Volatile private var inviteCode: String? = "K7M3QX"

    @Volatile private var inviterBound = true

    @Volatile private var appeals: List<JsonObject> = emptyList()

    @Volatile private var withdrawals = """{"items":[],"next_cursor":null}"""

    @Volatile private var kfUrl: String? = KF_URL

    private val nicknameBackend = FakeNickname()

    private class Recorded(
        val method: String,
        val path: String,
        val body: String,
        val headers: Map<String, String>,
    ) {
        fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

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

    private inner class FakeSystem : MeSystemPorts {
        var status = "not_asked"
        var deniedAt: Long? = null
        var wechat = "unknown"
        var openOk = true
        val opened = mutableListOf<String>()
        var settingsOpened = 0
        var bytes = 4096L
        var deviceId: String? = "device-fixture-ABC123"

        override fun pushStatus(): String = status

        override fun pushDeniedAt(): Long? = deniedAt

        override fun openNotificationSettings() {
            settingsOpened += 1
        }

        override fun openAppSettings() {
            settingsOpened += 1
        }

        override fun openExternal(url: String): Boolean {
            opened += url
            return openOk
        }

        override fun wechatInstalled(): String = wechat

        override fun cacheBytes(): Long = bytes

        override fun clearCache(): Boolean {
            bytes = 0
            return true
        }

        var webOk = true
        var webClears = 0

        override fun clearWebCache(): Boolean {
            webClears += 1
            return webOk
        }

        override fun deviceId(): String? = deviceId

        override fun osVersion(): String = "TEST_OS"

        override fun model(): String = "TEST_MODEL"
    }

    private class FakePushSource : PushTokenSource {
        var token: String? = "test-push-token-A"
        val listeners = mutableListOf<() -> Unit>()

        override fun start() = Unit

        override fun stop() {
            token = null
        }

        override fun current(): String? = token

        override fun subscribe(listener: () -> Unit): () -> Unit {
            listeners += listener
            return { listeners -= listener }
        }

        fun change(value: String) {
            token = value
            listeners.toList().forEach { it() }
        }
    }

    /** 改昵称的待定 operation（G01）：每月 3 次后 30415；含「敏感」→ 20001 nickname_sensitive；执行层先核对 allowed。 */
    private inner class FakeNickname : NicknameBackend {
        var saves = 0
        var calls = 0
        var gate: CountDownLatch? = null

        override suspend fun save(
            account: String,
            nickname: String,
            allowed: () -> Boolean,
        ): ApiResult {
            calls += 1
            if (!allowed()) return ApiResult.LocalReject(LocalRejectReason.CALL_CANCELLED)
            gate?.let { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { it.await(WAIT_S, TimeUnit.SECONDS) } }
            return when {
                nickname.contains("敏感") -> {
                    failure(20001, JsonObject(mapOf("reason" to JsonPrimitive("nickname_sensitive"))))
                }

                nickname.contains("限流") -> {
                    failure(42901)
                }

                saves >= 3 -> {
                    failure(30415)
                }

                else -> {
                    saves += 1
                    this@AppMeIntegrationTest.nickname = nickname
                    ApiResult.Success(JsonObject(emptyMap()), "trace-nick", null)
                }
            }
        }

        private fun failure(
            code: Int,
            data: JsonObject = JsonObject(emptyMap()),
        ): ApiResult =
            ApiResult.Failure(
                code,
                "",
                data,
                "trace-000$code",
                ErrorEffect(
                    code,
                    if (code == 42901) ErrorEffectKind.DISABLE_BUTTON else ErrorEffectKind.TOAST,
                    ReplayMode.NONE,
                    false,
                    disableMillis = if (code == 42901) 30_000L else null,
                ),
                null,
            )
    }

    private val notifyBackend =
        object : NotifyBackend {
            var service = true

            override suspend fun get(
                account: String,
                allowed: () -> Boolean,
            ): ApiResult = ApiResult.Success(JsonObject(mapOf("service" to JsonPrimitive(service))), null, null)

            override suspend fun put(
                account: String,
                service: Boolean,
                allowed: () -> Boolean,
            ): ApiResult {
                if (!allowed()) return ApiResult.LocalReject(LocalRejectReason.CALL_CANCELLED)
                return ApiResult.TransportFailure("disconnect", null, null, false)
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
        listOf(MeLocalStore.NAMESPACE, MeLocalStore.PENDING_NAMESPACE, "couli_amount_mask").forEach {
            context
                .getSharedPreferences(it, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty().substringBefore('?')
                    val recorded =
                        Recorded(
                            request.method.orEmpty(),
                            URLDecoder.decode(path, "UTF-8"),
                            request.body.readUtf8(),
                            request.headers.toMap(),
                        )
                    synchronized(requests) { requests += recorded }
                    val handler = synchronized(answers) { answers[path] }
                    return handler?.invoke(request) ?: defaultAnswer(recorded)
                }
            }
        if (!visualTransport) server.start()
    }

    @After
    fun tearDown() {
        if (!visualTransport) server.shutdown()
    }

    // ---------------------------------------------------------------- 搭建（与 AppModule 同一套接线）

    private fun launch(
        user: String? = "user-one",
        decision: String = "consented",
        restorer: StateRestorationTester? = null,
        adapters: List<com.couli.feature.login.ThirdPartyAuthAdapter>? = null,
    ) {
        val gate =
            PrivacyGate(MemoryStore(decision), clock = { clock }, bundledPolicyVersion = "3").apply {
                retryScheduler = RetryScheduler { _, _ -> }
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
                initialUpdate = update,
            )
        privacy.onConclusionChanged { routing.privacy.value = it }
        local = MeLocalStore(context)
        val main = android.os.Handler(Looper.getMainLooper())
        val api =
            ApiClient(
                identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.0.0", build = "1", channel = "official"),
                baseUrl = ::visualBaseUrl,
                http = visualClient(),
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
                        personalizationOff = local.personalizationOffNow(),
                    )
                },
                device = Device,
                tokens = tokens,
                pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { clock },
                hooks =
                    NetworkHooks(
                        onSessionStored = { main.post { if (::me.isInitialized) me.syncIdentity() } },
                        navigationTicket = { routing.navTickets.ticket() },
                        onRiskNotice = { ticket, valid -> main.post { me.on10006(ticket, valid) } },
                    ),
                inFlight = inFlight,
                sleeper = {},
            )
        this.api = api
        val store =
            ConfigStore(
                readFile = { null },
                writeFile = {},
                fetcher = { _, done -> done(ConfigReply(ConfigReply.OK, config(), null)) },
                clock = { clock },
                bundled = config(),
            )
        lateinit var clipboard: AppClipboard
        composeRule.runOnUiThread {
            login =
                AppLogin(
                    api,
                    tokens,
                    routing,
                    updateCheck = { update },
                    markBelow = { forced += 1 },
                    legal = LegalVersions(3, 2),
                    adapters =
                        adapters ?: listOf(
                            com.couli.feature.login
                                .UnavailableWechatAdapter(),
                        ),
                    pendingInvite = PendingInviteCode(),
                )
            login.syncSession()
            clipboard =
                AppClipboard(
                    system = system,
                    api =
                        com.couli.core.network
                            .ApiClientPageApi(api),
                    routing = routing,
                    privacy = { privacy.conclusion },
                    promptShowing = { privacy.promptShowing },
                    configData = { Json.parseToJsonElement(config()) as JsonObject },
                    prefs = ClipboardPreferences(context, SampleCipher),
                    sessionEpoch = { tokens.loginEpoch },
                    clock = { clock },
                    randomSalt = { SAMPLE_SALT.copyOf() },
                    pendingInvite = login.pendingInvite,
                )
            me =
                AppMe(
                    client = api,
                    tokens = tokens,
                    routing = routing,
                    privacy = privacy,
                    store = store,
                    login = { login },
                    updateCheck = { update },
                    serverForced = { forced += 1 },
                    writer = { text -> clipboard.writer.write(text) },
                    local = local,
                    mask = AmountMaskStore(context),
                    system = ports,
                    pushSource = push,
                    nickname = nicknameBackend,
                    notify = notifyBackend,
                    clock = { clock },
                ).also { it.connect { listener -> updateListeners += listener } }
            cleaners =
                CredentialCleanerRegistry().apply {
                    register("in_flight_calls") { inFlight.onPrivacyRevoked() }
                    register(CredentialCleanerRegistry.TOKENS) { tokens.clear() }
                    register(CredentialCleanerRegistry.DEVICE_ID) { ports.deviceId = null }
                    register(CredentialCleanerRegistry.INSTALL_SECRET) {}
                    register(MeLocalStore.CLEANER) { local.clearAll() }
                    register(AppMe.ME_SESSION_CLEANER) { me.clearForConsentRevoked() }
                }
            gate.withdrawalCleanup = { cleaners.cleanAll() }
            login.customerService = me.cs::open
        }
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            CompositionLocalProvider(
                LocalCouliAnnouncer provides
                    if (visualTransport) {
                        CouliAnnouncer {
                            visualAnnouncements += it
                            visualA11y.announcer.announce(it)
                        }
                    } else {
                        LocalCouliAnnouncer.current
                    },
            ) {
                navController = rememberNavController()
                AppShell(
                    routing = routing,
                    privacy = privacy,
                    homeContent = { Box(Modifier.fillMaxSize()) },
                    navController = navController,
                    login = login,
                    clipboard = clipboard,
                    me = me,
                )
            }
        }
        if (restorer != null) restorer.setContent(content) else composeRule.setContent(content)
        settle()
    }

    private val updateListeners = mutableListOf<(UpdateCheck) -> Unit>()

    private fun setUpdate(value: UpdateCheck) {
        update = value
        composeRule.runOnUiThread {
            routing.update.value = value
            updateListeners.forEach { it(value) }
        }
        settle()
    }

    private fun config(): String {
        val kf = kfUrl?.let { """{"url":"$it"}""" } ?: "null"
        return """{"kf":$kf,"legal":{"privacy":{"version":3,"min_version":3},"agreement":{"version":2}},""" +
            """"agent":{"filing_text":"测试模型名称\n测试登记编号：TEST-001"},"dict_version":"CONFIG_123","h5_release":{"version":"HOME_456"},""" +
            """"features":{"flags":{"earnings.dashboard.visible":false}}}"""
    }

    // ---------------------------------------------------------------- 假服务

    private fun meJson(user: String): String {
        val code = inviteCode?.let { "\"$it\"" } ?: "null"
        val reasonJson = reason?.let { "\"$it\"" } ?: "null"
        val until = frozenUntil?.let { "\"$it\"" } ?: "null"
        return """{"user_id":"$user","nickname":"$nickname","avatar_url":"","invite_code":$code,"inviter_bound":$inviterBound,""" +
            """"invite_backfill":{"eligible":false,"deadline_at":null},"identity_level":"normal","phone_bound":true,""" +
            """"balance":{"available_fen":1234,"withdrawable_fen":1234,"estimated_total_fen":5678},"union_bindings":[],""" +
            """"realname_status":"verified","need_reconsent":false,""" +
            """"risk":{"state":"$risk","reason_category":$reasonJson,"frozen_until":$until}}"""
    }

    private fun appeal(
        id: String,
        status: String,
        content: String = "测试申诉，请核查。",
    ): JsonObject =
        Json
            .parseToJsonElement(
                """{"appeal_id":"$id","target_type":"account","target_id":null,"status":"$status","content":"$content",""" +
                    """"created_at":"2026-10-10T10:00:00+08:00",""" +
                    """"closed_at":${if (status == "processing") "null" else "\"2026-10-11T15:00:00+08:00\""}}""",
            ).jsonObject

    private fun defaultAnswer(request: Recorded): MockResponse {
        val user = request.header("Authorization")?.removePrefix("Bearer access-")
        return when {
            request.path == "/v1/me" -> envelope(meJson(user ?: "guest"))
            request.path == "/v1/me/appeals" && request.method == "POST" -> submitAppeal(request)
            request.path == "/v1/me/appeals" -> envelope("""{"items":${JsonArray(appeals)},"next_cursor":null}""")
            request.path == "/v1/withdrawals" -> envelope(withdrawals)
            else -> envelope("{}")
        }
    }

    private fun submitAppeal(request: Recorded): MockResponse {
        val existing = appeals.firstOrNull { it["status"]?.jsonPrimitive?.content == "processing" }
        if (existing != null) return envelope(existing.toString())
        val content =
            Json
                .parseToJsonElement(request.body)
                .jsonObject["content"]
                ?.jsonPrimitive
                ?.content
                .orEmpty()
        val created = appeal("appeal-new", "processing", content)
        appeals = listOf(created) + appeals
        risk = "appealing"
        return envelope(created.toString())
    }

    private fun envelope(
        data: String,
        code: Int = 0,
        trace: String = "trace-000123",
    ) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"code":$code,"msg":"","data":$data,"trace_id":"$trace"}""")

    // ---------------------------------------------------------------- 工具

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
        if (!condition()) {
            println(
                "DEBUG-TREE\n" +
                    runCatching { composeRule.onRoot(useUnmergedTree = true).printToString(maxDepth = Int.MAX_VALUE) }.getOrNull(),
            )
        }
        assertTrue("栈 ${runCatching { stack() }.getOrNull()}，请求 ${lines()}", condition())
    }

    /**
     * FU-AND-0716 窄修二：「我的」资料已为当前身份读到。设置页的昵称行先于 getMe 结算渲染出来，但点它要 meOf(当前身份) 有值才进昵称子页；原写法只等行出现，
     * getMe 晚到时点击不受理，随后输入找不到输入框。
     */
    private fun meLoaded(): Boolean = me.deps.repo.meOf(me.identity.value) != null

    /** Me 服务作用域（AppMe 的会话级作用域，Main.immediate）里此刻在跑的协程（含常驻的订阅）。 */
    private fun meJobs(): Set<Job> =
        me.deps.service.coroutineContext[Job]!!
            .children
            .filter { it.isActive }
            .toSet()

    /**
     * FU-AND-0716 窄修二：[before] 之后在 Me 服务作用域里起的协程都已结束——读取 / 保存的续体在协程里跑完结论（落地、按序号丢弃）才结束；常驻订阅在 [before]
     * 里，不算。不看网络层是否空闲。
     */
    private fun meWorkSince(before: Set<Job>) =
        FlowWait.until(::settle, { "Me 作用域仍有新起的协程在跑；请求 ${lines()}" }) {
            me.deps.service.coroutineContext[Job]!!
                .children
                .none { it.isActive && it !in before }
        }

    private fun lines(): List<String> = synchronized(requests) { requests.map { it.method + " " + it.path } }

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
        val node = nodes[nodes.fetchSemanticsNodes().size - 1]
        runCatching { node.performScrollTo() }
        node.performClick()
        settle()
    }

    private fun input(
        tag: String,
        value: String,
    ) {
        composeRule.onNodeWithTag(tag, useUnmergedTree = true).performTextReplacement(value)
        settle()
    }

    private fun count(
        method: String,
        path: String,
    ): Int = synchronized(requests) { requests.count { it.method == method && it.path == path } }

    private fun all(
        method: String,
        path: String,
    ): List<Recorded> = synchronized(requests) { requests.filter { it.method == method && it.path == path } }

    private fun open(route: String) {
        composeRule.runOnUiThread { routing.router.open(RouteRequest(route, JsonObject(emptyMap()), RouteSource.IN_APP)) }
        settle()
    }

    @SuppressLint("RestrictedApi")
    private fun stack(): List<String> =
        navController.currentBackStack.value
            .filter { it.destination.hasRoute(RouteDestination::class) }
            .map { it.toRoute<RouteDestination>().name }

    private fun top(): String? = stack().lastOrNull()

    private fun switchSession(user: String?) {
        composeRule.runOnUiThread {
            if (user == null) tokens.clear() else tokens.save("access-$user", "refresh-$user", "full", user)
            login.syncSession()
            me.syncIdentity()
        }
        settle()
    }

    private fun t(key: String): String = MeDraftTexts.text(key)

    private fun body(recorded: Recorded): JsonObject = Json.parseToJsonElement(recorded.body).jsonObject

    // ---------------------------------------------------------------- AC-S1-77：游客、个性化、撤回、基本模式

    /** 新视觉用例仅替换 HTTP I/O：真实 ApiClient / AppMe / AppLogin / 清理器 / Router / 统一剪贴板仍参与。 */
    private fun visualBaseUrl() = if (visualTransport) "https://me-fixture.invalid/" else server.url("/").toString()

    private fun visualClient() = if (visualTransport) visualHttp() else OkHttpClient()

    private fun visualHttp(): OkHttpClient =
        OkHttpClient
            .Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val buffer = Buffer()
                request.body?.writeTo(buffer)
                val recorded = Recorded(request.method, request.url.encodedPath, buffer.readUtf8(), request.headers.toMap())
                synchronized(requests) { requests += recorded }
                val response =
                    if (recorded.path == "/v1/me" && visualReadFailure) {
                        envelope("null", 50001, "me-visual-a1b2c3")
                    } else {
                        defaultAnswer(recorded)
                    }
                Response
                    .Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .headers(response.headers)
                    .body(response.getBody()!!.readUtf8().toResponseBody())
                    .build()
            }.build()

    private fun visualQueue(errorFirst: Boolean = false) {
        launch()
        open("Me")
        idleUntil { meLoaded() && has(MeTags.ME_COPY) }
        visualAnnouncements.clear()
        composeRule.mainClock.autoAdvance = false
        if (!errorFirst) composeRule.onNodeWithTag(MeTags.ME_COPY, useUnmergedTree = true).performClick()
        visualReadFailure = true
        composeRule.runOnUiThread { checkNotNull(me.deps.tab.bound).refresh() }
        idleUntil { me.deps.repo.state.value.error != null }
        composeRule.mainClock.advanceTimeByFrame()
        settle()
        if (errorFirst) {
            assertTrue(
                composeRule
                    .onNodeWithTag(MeTags.TOAST, useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .meNoticeText()
                    .contains("a1b2c3"),
            )
            composeRule.onNodeWithTag(MeTags.ME_COPY, useUnmergedTree = true).performClick()
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
            assertNotNull(
                "复制已通过统一写入口成功，结果仍在排队",
                me.deps.tab.bound!!
                    .toast.value,
            )
        }
    }

    @Test
    fun visualGm14RealPageQueuesRefreshBeforeUnifiedCopy() {
        visualQueue()
        assertEquals("同帧只能一条轻提示", 1, composeRule.onAllNodesWithTag(MeTags.TOAST, useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(1, system.writes)
        assertEquals("K7M3QX", system.content)
        assertEquals(1, visualA11y.messages.size)
        assertTrue(visualA11y.messages.single().contains("a1b2c3"))
        composeRule.mainClock.advanceTimeBy(2_100)
        settle()
        assertEquals(listOf(visualA11y.messages.first(), t("invite.copy_code.done")), visualA11y.messages)
        composeRule.mainClock.advanceTimeBy(2_100)
        settle()
        assertFalse(has(MeTags.TOAST))
        assertEquals(2, visualA11y.messages.size)
    }

    @Test
    fun visualGm14AccountSwitchInvalidatesQueuedCopyThroughSessionSync() {
        visualQueue(errorFirst = true)
        val old = me.deps.tab.bound
        visualReadFailure = false
        visualTransition().check(t("invite.copy_code.done"), finished = { me.identity.value.account == "user-two" }) {
            // switchSession 原辅助会 settle；这里仍走相同真实入口，但检查从操作前覆盖到每一帧。
            tokens.save("access-user-two", "refresh-user-two", "full", "user-two")
            login.syncSession()
            me.syncIdentity()
        }
        assertFalse(old!!.alive())
        assertEquals("user-two", me.identity.value.account)
        assertFalse(has(MeTags.TOAST))
        assertEquals(1, system.writes)
        assertEquals("K7M3QX", system.content)
    }

    @Test
    fun visualGm14LogoutEntryInvalidatesQueuedCopyAndKeepsGuest() {
        visualQueue(errorFirst = true)
        val old = me.deps.tab.bound
        val identity = me.identity.value
        visualReadFailure = false
        // 与设置确认按钮相同的真实 AppMe 出口，不在测试宿主里手动清队列。
        visualTransition().check(t("invite.copy_code.done"), finished = { routing.session.value == SessionState.GUEST }) {
            me.logout(identity) { true }
        }
        assertFalse(old!!.alive())
        assertTrue(has(MeTags.ME_GUEST))
        assertFalse(has(MeTags.TOAST))
        assertEquals(1, count("POST", "/v1/auth/logout"))
        assertEquals(1, system.writes)
    }

    @Test
    fun visualGm14WithdrawConsentEntryInvalidatesQueueThroughRegisteredCleaners() {
        visualQueue(errorFirst = true)
        val old = me.deps.tab.bound
        visualReadFailure = false
        visualTransition().check(t("invite.copy_code.done"), finished = { privacy.conclusion == PrivacyState.BASIC_MODE }) {
            me.withdrawConsent(me.identity.value)
        }
        assertFalse(old!!.alive())
        assertNull(tokens.accessToken)
        assertNull(me.deps.repo.state.value.me)
        assertFalse(has(MeTags.TOAST))
        val records = all("POST", "/v1/consents").filter { body(it)["type"]?.jsonPrimitive?.content == "privacy" }
        assertEquals(1, records.size)
        assertEquals("false", body(records.single())["accepted"]!!.jsonPrimitive.content)
        assertEquals(1, system.writes)
    }

    private fun visualTransition() = MeVisualTransitionProbe(composeRule, visualAnnouncements) { me.identity.value.toString() }

    @Test
    fun ac77_guestMeAndSettingsShowOnlyGuestItemsAndNeverAskForMe() {
        launch(user = null)
        open("Me")
        idleUntil { has(MeTags.ME_GUEST) }
        assertFalse("游客没有铃铛", has(MeTags.ME_BELL))
        assertFalse("游客没有余额卡", has(MeTags.ME_BALANCE))
        assertEquals("宫格只有帮助中心、设置", 1, text(t("me.grid.help")))
        assertEquals(0, text(t("me.grid.wallet")))
        assertEquals("游客不请求 /v1/me", 0, count("GET", "/v1/me"))

        clickText(t("me.grid.settings"))
        idleUntil { top() == "Settings" }
        listOf("settings.privacy_center", "settings.agreement", "settings.about", "settings.clear_cache", "settings.help").forEach {
            assertTrue(it, has(MeTags.SETTINGS_ROW_PREFIX + it))
        }
        assertTrue("Android 有剪贴板开关", has(ClipboardScreens.SETTINGS_ROW))
        listOf("settings.nickname", "settings.phone", "settings.notify", "settings.reset_tip").forEach {
            assertFalse("游客不显示（不是置灰）$it", has(MeTags.SETTINGS_ROW_PREFIX + it))
        }
        assertFalse(has(MeTags.SETTINGS_LOGOUT))
        assertTrue(has(MeTags.SETTINGS_FOOTER))
        // 四个页面都能打开
        click(MeTags.SETTINGS_ROW_PREFIX + "settings.privacy_center")
        idleUntil { top() == "PrivacyCenter" && has(MeTags.PRIVACY_WITHDRAW) }
        assertTrue("游客可撤回、可切个性化", has(MeTags.PRIVACY_SWITCH))
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        click(MeTags.SETTINGS_ROW_PREFIX + "settings.about")
        idleUntil { top() == "About" && has(MeTags.ABOUT_COPY) }
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        click(MeTags.SETTINGS_ROW_PREFIX + "settings.help")
        idleUntil { top() == "Help" }
        assertTrue("全程无需登录的 10001", lines().none { it.contains("/v1/me") })
    }

    @Test
    fun ac77_personalizationOffAddsTheHeaderAndRecordsConsent() {
        launch()
        open("PrivacyCenter")
        idleUntil { has(MeTags.PRIVACY_SWITCH) }
        click(MeTags.PRIVACY_SWITCH)
        idleUntil { count("POST", "/v1/consents") == 1 }
        val consent = body(all("POST", "/v1/consents").single())
        assertEquals("personalization", consent["type"]!!.jsonPrimitive.content)
        assertEquals("false", consent["accepted"]!!.jsonPrimitive.content)
        assertEquals("privacy_center", consent["channel"]!!.jsonPrimitive.content)
        assertEquals("3", consent["version"]!!.jsonPrimitive.content)
        assertFalse("本机先保存", local.personalizationOn.value)
        // 关闭之后的请求带 X-Personalization: off
        // FU-AND-0716 窄修二：冷启动那次 getMe 已经满足「≥ 1」，原写法会读到关闭之前的那次请求；改等进「我的」之后新发的那次
        val mesBefore = count("GET", "/v1/me")
        open("Me")
        idleUntil { count("GET", "/v1/me") > mesBefore }
        assertEquals("off", all("GET", "/v1/me").last().header("X-Personalization"))
    }

    @Test
    fun ac77_personalizationFailureKeepsTheChoiceAndShowsOneNotice() {
        launch()
        answers["/v1/consents"] = { envelope("null", code = 50001) }
        open("PrivacyCenter")
        idleUntil { has(MeTags.PRIVACY_SWITCH) }
        click(MeTags.PRIVACY_SWITCH)
        idleUntil { text(t("privacy_center.personalization_failed")) == 1 }
        assertFalse("不回滚开关（用户意愿优先）", local.personalizationOn.value)
    }

    @Test
    fun ac77_withdrawConfirmCancelIsPrimaryAndConfirmEntersBasicModeWithADeviceRecord() {
        launch()
        open("PrivacyCenter")
        idleUntil { has(MeTags.PRIVACY_WITHDRAW) }
        click(MeTags.PRIVACY_WITHDRAW)
        // 【取消】是主按钮：点了不撤回
        clickText(t("privacy.withdraw.confirm.cancel"))
        assertEquals(0, count("POST", "/v1/consents"))
        assertEquals(PrivacyState.CONSENTED, privacy.conclusion)

        // 系统返回 = 取消
        click(MeTags.PRIVACY_WITHDRAW)
        composeRule.runOnUiThread {
            androidx.test.core.app.ApplicationProvider
                .getApplicationContext<Application>()
        }
        clickText(t("privacy.withdraw.confirm.cancel"))
        assertEquals(PrivacyState.CONSENTED, privacy.conclusion)

        local.setPersonalization(false, clock)
        click(MeTags.PRIVACY_WITHDRAW)
        click(MeTags.WITHDRAW_DIALOG)
        idleUntil { privacy.conclusion == PrivacyState.BASIC_MODE }
        val record = all("POST", "/v1/consents").single { body(it)["type"]!!.jsonPrimitive.content == "privacy" }
        val sent = body(record)
        assertEquals("false", sent["accepted"]!!.jsonPrimitive.content)
        assertEquals("privacy_center", sent["channel"]!!.jsonPrimitive.content)
        idleUntil { has(BASIC_MODE_PAGE_TAG) }
        assertNull("本机令牌已清", tokens.accessToken)
        assertTrue("清理名单含本任务新增项", cleaners.registeredNames().containsAll(listOf(MeLocalStore.CLEANER, AppMe.ME_SESSION_CLEANER)))
        assertTrue("设备级偏好已清（个性化回到缺省）", local.personalizationOn.value)
        // 推送上报不再发出
        val before = count("POST", "/v1/devices/push-token")
        composeRule.runOnUiThread { push.change("test-push-token-B") }
        settle()
        assertEquals(before, count("POST", "/v1/devices/push-token"))
    }

    @Test
    fun ac77_offlineWithdrawStillEntersBasicMode() {
        launch()
        answers["/v1/consents"] = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START) }
        open("PrivacyCenter")
        idleUntil { has(MeTags.PRIVACY_WITHDRAW) }
        click(MeTags.PRIVACY_WITHDRAW)
        click(MeTags.WITHDRAW_DIALOG)
        idleUntil { privacy.conclusion == PrivacyState.BASIC_MODE }
        assertNull(tokens.accessToken)
    }

    @Test
    fun ac77_basicModeHasFiveSettingsItemsReadOnlyPrivacyCenterAndAnonymousRequestsOnly() {
        launch(user = "user-one", decision = "basic_mode")
        open("Settings")
        idleUntil { top() == "Settings" }
        listOf("settings.privacy_center", "settings.agreement", "settings.about", "settings.help").forEach {
            assertTrue(it, has(MeTags.SETTINGS_ROW_PREFIX + it))
        }
        assertTrue(has(MeTags.SETTINGS_ENABLE_FULL))
        assertFalse("基本模式不显示剪贴板开关", has(ClipboardScreens.SETTINGS_ROW))
        assertFalse(has(MeTags.SETTINGS_ROW_PREFIX + "settings.clear_cache"))
        click(MeTags.SETTINGS_ROW_PREFIX + "settings.privacy_center")
        idleUntil { has(MeTags.PRIVACY_ENABLE_FULL) }
        assertFalse(has(MeTags.PRIVACY_SWITCH))
        assertFalse(has(MeTags.PRIVACY_WITHDRAW))
        assertTrue("系统权限一节基本模式同样能看（AC-S1-135）", has(MeTags.PRIVACY_PERMISSIONS))
        assertEquals(
            "没有发 getMe、recordConsent、推送上报",
            0,
            lines().count {
                it.contains("/v1/me") || it.contains("/v1/consents") ||
                    it.contains("push-token")
            },
        )
        synchronized(requests) {
            requests.forEach {
                assertNull("基本模式不带 Authorization", it.header("Authorization"))
                assertNull("基本模式不带 X-Device-Id", it.header("X-Device-Id"))
                assertNull("基本模式不带签名", it.header("X-Sign"))
            }
        }
        click(MeTags.PRIVACY_ENABLE_FULL)
        assertTrue("【开启完整功能】重新展示首启弹窗", privacy.promptShowing)
    }

    // ---------------------------------------------------------------- AC-S1-60：封禁 / 冻结说明与申诉

    @Test
    fun ac60_frozenShowsReasonAndRecoveryTimeWithoutDeadline() {
        risk = "frozen"
        reason = "abnormal_trade"
        frozenUntil = "2026-10-15T00:00:00+08:00"
        launch()
        open("RiskNotice")
        idleUntil { has(MeTags.RISK_UNTIL) }
        assertEquals(1, text(t("risk_reason.abnormal_trade")))
        assertEquals(1, text("10-15 00:00"))
        assertTrue("冻结有返回", spoken(t("me.back")) >= 1)
        assertTrue(has(MeTags.RISK_PRIMARY))
        assertTrue(text(t("risk.submit")) >= 1)
        assertEquals("不显示处理时限", 0, text("工作日") + text("截止"))
    }

    @Test
    fun ac60_bannedVia10006LandsOnASingleRiskNoticeAndAppealGoesToProgress() {
        risk = "banned"
        reason = "malicious_rights"
        withdrawals =
            """{"items":[{"withdrawal_id":"w1","status":"PENDING_REVIEW","review_mode":"manual",""" +
            """"amount_fen":1000,"fee_fen":0,"net_fen":1000,"tax_fen":0,""" +
            """"reason_code":null,"fail_action":null,""" +
            """"payout_channel":"alipay","payout_channel_name":"支付宝","masked_account":"138****5678",""" +
            """"created_at":"2026-10-10T09:00:00+08:00"}],"next_cursor":null}"""
        launch()
        // 任一请求得到 10006 → 统一落到 RiskNotice（只开一个）
        var served = 0
        answers["/v1/me/tips/jump_tip"] = {
            served += 1
            envelope("null", code = 10006)
        }
        open("Settings")
        idleUntil { has(MeTags.SETTINGS_ROW_PREFIX + "settings.reset_tip") }
        click(MeTags.SETTINGS_ROW_PREFIX + "settings.reset_tip")
        idleUntil { top() == "RiskNotice" && has(MeTags.RISK_REASON) }
        assertEquals("栈里只留它", listOf("RiskNotice"), stack())
        assertEquals(1, text(t("risk_reason.malicious_rights")))
        assertEquals(1, text(t("risk.limit.long")))
        assertEquals("封禁无返回", 0, spoken(t("me.back")))
        assertTrue(has(MeTags.RISK_CS) && has(MeTags.RISK_LOGOUT))
        idleUntil { has(MeTags.RISK_WITHDRAWALS) }
        assertEquals("进行中的提现 ¥10 · 审核中", 1, text("¥10"))
        // 再来一个 10006：栈顶已是 RiskNotice，不重复打开
        composeRule.runOnUiThread { me.on10006(routing.navTickets.ticket()) { true } }
        settle()
        assertEquals(listOf("RiskNotice"), stack())

        click(MeTags.RISK_PRIMARY)
        idleUntil { top() == "Appeal" && has(MeTags.APPEAL_INPUT) }
        composeRule.onNodeWithTag(MeTags.APPEAL_SUBMIT, useUnmergedTree = true).assertIsNotEnabled()
        input(MeTags.APPEAL_INPUT, "测试申诉，请核查。")
        click(MeTags.APPEAL_SUBMIT)
        idleUntil { has(MeTags.APPEAL_PROGRESS) }
        val submitted = all("POST", "/v1/me/appeals").single()
        assertEquals(setOf("target_type", "content"), body(submitted).keys)
        assertTrue("带 Idempotency-Key", submitted.header("Idempotency-Key") != null)
        assertEquals("本页切到进度态，不新开页", listOf("RiskNotice", "Appeal"), stack())
        assertFalse("处理中不能再提交", has(MeTags.APPEAL_SUBMIT))
    }

    @Test
    fun ac60_upheldShowsTheLineAndAppealAgain() {
        risk = "banned"
        reason = "account_security"
        appeals = listOf(appeal("appeal-old", "upheld"))
        launch()
        open("RiskNotice")
        idleUntil { has(MeTags.RISK_UPHELD) }
        assertTrue(text(t("risk.upheld")) >= 1)
        assertTrue(text(t("risk.submit")) >= 1)
        click(MeTags.RISK_PRIMARY)
        idleUntil { has(MeTags.APPEAL_AGAIN) }
        click(MeTags.APPEAL_AGAIN)
        idleUntil { has(MeTags.APPEAL_INPUT) }
    }

    @Test
    fun ac60_unknownReasonCategoryHidesTheReasonRow() {
        risk = "frozen"
        reason = "new_category"
        launch()
        open("RiskNotice")
        idleUntil { has(MeTags.RISK_PRIMARY) }
        assertFalse(has(MeTags.RISK_REASON))
        assertEquals("不显示原始编码", 0, text("new_category"))
    }

    @Test
    fun ac60_forceUpdateShowingSuppresses10006() {
        launch()
        open("Settings")
        idleUntil { top() == "Settings" }
        setUpdate(UpdateCheck.BELOW_MIN)
        composeRule.runOnUiThread { me.on10006(routing.navTickets.ticket()) { true } }
        settle()
        assertFalse("强更优先", "RiskNotice" in stack())
    }

    @Test
    fun ac60_lateTenThousandSixAfterUserNavigatedIsIgnored() {
        launch()
        open("Settings")
        idleUntil { top() == "Settings" }
        val ticket = composeRule.runOnUiThread { routing.navTickets.ticket() }
        open("About")
        idleUntil { top() == "About" }
        composeRule.runOnUiThread { me.on10006(ticket) { true } }
        settle()
        assertEquals("发起之后用户已导航：不覆盖后续导航", "About", top())
        composeRule.runOnUiThread { me.on10006(null) { true } }
        settle()
        assertEquals("空票据不放行", "About", top())
    }

    // ---------------------------------------------------------------- AC-S1-61：昵称

    @Test
    fun ac61_threeSavesThenLimitAndSensitiveAndDisabledInputs() {
        launch()
        open("Me")
        idleUntil { has(MeTags.ME_NICKNAME) }
        open("Settings")
        idleUntil { has(MeTags.SETTINGS_ROW_PREFIX + "settings.nickname") && meLoaded() }
        listOf("小狸爱买买", "小狸二号", "小狸三号").forEach { name ->
            click(MeTags.SETTINGS_ROW_PREFIX + "settings.nickname")
            idleUntil { has(MeTags.NICKNAME_INPUT) }
            input(MeTags.NICKNAME_INPUT, name)
            click(MeTags.NICKNAME_SAVE)
            idleUntil { !has(MeTags.NICKNAME_PAGE) }
            idleUntil {
                me.deps.repo.state.value.me
                    ?.nickname == name
            }
        }
        assertEquals(1, text(t("nickname.saved")))
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        idleUntil { text("小狸三号") >= 1 }
        open("Settings")
        click(MeTags.SETTINGS_ROW_PREFIX + "settings.nickname")
        idleUntil { has(MeTags.NICKNAME_INPUT) }
        input(MeTags.NICKNAME_INPUT, "小狸四号")
        click(MeTags.NICKNAME_SAVE)
        idleUntil { has(MeTags.NICKNAME_ERROR) && text(dict("error.30415")) >= 1 }
        assertEquals("保留输入", "小狸四号", fieldText(MeTags.NICKNAME_INPUT))
        input(MeTags.NICKNAME_INPUT, "敏感词测试")
        click(MeTags.NICKNAME_SAVE)
        idleUntil { text(dict("error.20001.nickname_sensitive")) >= 1 }
        input(MeTags.NICKNAME_INPUT, "   ")
        composeRule.onNodeWithTag(MeTags.NICKNAME_SAVE, useUnmergedTree = true).assertIsNotEnabled()
        input(MeTags.NICKNAME_INPUT, "abcdefghijklmnopq")
        composeRule.onNodeWithTag(MeTags.NICKNAME_SAVE, useUnmergedTree = true).assertIsNotEnabled()
        input(MeTags.NICKNAME_INPUT, "👨‍👩‍👧abcdefghijklmno")
        composeRule.onNodeWithTag(MeTags.NICKNAME_SAVE, useUnmergedTree = true).assertIsEnabled()
        assertEquals("字素计数：ZWJ 一家人算 1", 1, text("16/16"))
        assertEquals("没有头像上传入口", 0, text("头像") - text(t("nickname.hint")).coerceAtMost(text("头像")))
    }

    @Test
    fun ac61_rateLimitedSaveDisablesTheButtonAndTheExecutionLayerBlocks() {
        launch()
        open("Settings")
        idleUntil { has(MeTags.SETTINGS_ROW_PREFIX + "settings.nickname") && meLoaded() }
        click(MeTags.SETTINGS_ROW_PREFIX + "settings.nickname")
        input(MeTags.NICKNAME_INPUT, "限流测试")
        click(MeTags.NICKNAME_SAVE)
        idleUntil { me.gates.nickname.active }
        input(MeTags.NICKNAME_INPUT, "小狸爱买买")
        composeRule.onNodeWithTag(MeTags.NICKNAME_SAVE, useUnmergedTree = true).assertIsNotEnabled()
        val calls = nicknameBackend.calls
        composeRule.runOnUiThread {
            val probe = me.gates.nickname.probe()
            assertFalse("执行层同样拦下", probe.allowed())
        }
        assertEquals(calls, nicknameBackend.calls)
    }

    @Test
    fun ac61_aMeReadSentBeforeTheSaveDoesNotOverwriteTheNewNickname() {
        launch()
        open("Settings")
        idleUntil { has(MeTags.SETTINGS_ROW_PREFIX + "settings.nickname") && meLoaded() }
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        // 保存之前发出的读取：服务端当时的昵称还是旧的（应答内容在发出时就定下）
        val stale = meJson("user-one").replace("凑狸用户", "旧昵称")
        answers["/v1/me"] = {
            arrived.countDown()
            release.await(WAIT_S, TimeUnit.SECONDS)
            envelope(stale)
        }
        val jobsBefore = meJobs()
        composeRule.runOnUiThread { me.deps.repo.refresh(me.identity.value, { true }) }
        assertTrue(arrived.await(WAIT_S, TimeUnit.SECONDS))
        answers.remove("/v1/me")
        click(MeTags.SETTINGS_ROW_PREFIX + "settings.nickname")
        input(MeTags.NICKNAME_INPUT, "小狸爱买买")
        click(MeTags.NICKNAME_SAVE)
        idleUntil {
            me.deps.repo.state.value.me
                ?.nickname == "小狸爱买买"
        }
        release.countDown()
        Thread.sleep(LATE_MS)
        settle()
        // FU-AND-0716 窄修二：放行的旧读取要等它连同业务续体跑完（它在 Me 服务作用域里的协程结束：按序号判为被取代、不写出），才核对「不覆盖」
        meWorkSince(jobsBefore)
        assertEquals(
            "旧读取晚到不覆盖",
            "小狸爱买买",
            me.deps.repo.state.value.me
                ?.nickname,
        )
    }

    // ---------------------------------------------------------------- AC-S1-62：邀请

    @Test
    fun ac62_inviteBlockInviterTextAndCopyThroughTheUnifiedWriter() {
        launch()
        open("Me")
        idleUntil { has(MeTags.ME_INVITE_CODE) }
        assertEquals(1, text("K7M3QX"))
        assertEquals(1, text(t("me.inviter_bound")))
        click(MeTags.ME_COPY)
        assertEquals("K7M3QX", system.content)
        assertEquals(1, system.writes)
        idleUntil { text(t("invite.copy_code.done")) == 1 }
        assertEquals("不显示等级与邀请人信息", 0, text("等级"))

        inviterBound = false
        inviteCode = null
        composeRule.runOnUiThread { me.deps.repo.refresh(me.identity.value, { true }) }
        idleUntil { text(t("me.inviter_unbound")) == 1 }
        assertFalse("invite_code=null 不显示邀请码块", has(MeTags.ME_INVITE_CODE))
    }

    @Test
    fun ac62_meReadInFlightWhileSwitchingAccountsNeverShowsTheOldAccount() {
        launch()
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        answers["/v1/me"] = {
            arrived.countDown()
            release.await(WAIT_S, TimeUnit.SECONDS)
            envelope(meJson("user-one"))
        }
        open("Me")
        assertTrue(arrived.await(WAIT_S, TimeUnit.SECONDS))
        answers["/v1/me"] = { envelope(meJson("user-two").replace("凑狸用户", "小狸爱买买")) }
        switchSession("user-two")
        release.countDown()
        idleUntil {
            me.deps.repo.state.value.me
                ?.userId == "user-two"
        }
        Thread.sleep(LATE_MS)
        settle()
        assertEquals(
            "user-two",
            me.deps.repo.state.value.me
                ?.userId,
        )
        assertEquals(0, text("凑狸用户"))
    }

    // ---------------------------------------------------------------- 迟到与作废

    @Test
    fun late_sessionInvalidThenLoginBRemovesOldRiskNoticeAndAppeal() {
        risk = "frozen"
        reason = "abnormal_trade"
        launch()
        open("RiskNotice")
        idleUntil { has(MeTags.RISK_PRIMARY) }
        click(MeTags.RISK_PRIMARY)
        idleUntil { has(MeTags.APPEAL_INPUT) }
        input(MeTags.APPEAL_INPUT, "账号 A 的申诉内容")
        switchSession(null)
        switchSession("user-two")
        assertFalse("只能登录访问的页面已移出导航栈", "RiskNotice" in stack() || "Appeal" in stack())
        assertEquals("看不到 A 的申诉内容", 0, text("账号 A 的申诉内容"))
    }

    @Test
    fun late_appealSubmitInFlightThenLeavingKeepsTheWriteButNeverTouchesTheOldPage() {
        risk = "frozen"
        launch()
        open("Appeal")
        idleUntil { has(MeTags.APPEAL_INPUT) }
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        answers["/v1/me/appeals"] = { request ->
            if (request.method == "POST") {
                arrived.countDown()
                release.await(WAIT_S, TimeUnit.SECONDS)
                envelope(appeal("appeal-late", "processing").toString())
            } else {
                envelope("""{"items":[],"next_cursor":null}""")
            }
        }
        input(MeTags.APPEAL_INPUT, "测试申诉，请核查。")
        click(MeTags.APPEAL_SUBMIT)
        assertTrue(arrived.await(WAIT_S, TimeUnit.SECONDS))
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        release.countDown()
        Thread.sleep(LATE_MS)
        settle()
        assertFalse("已出栈的页面不再出现", "Appeal" in stack())
        assertEquals("写请求没有被取消", 1, count("POST", "/v1/me/appeals"))
    }

    // ---------------------------------------------------------------- AC-S1-76：推送上报

    @Test
    fun ac76_coldStartReportsOnceAfterTheVersionCheckAndTokenChangesInFlightReportTheLatest() {
        update = UpdateCheck.CHECKING
        launch()
        assertEquals("版本检查前不报", 0, count("POST", "/v1/devices/push-token"))
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        answers["/v1/devices/push-token"] = {
            arrived.countDown()
            release.await(WAIT_S, TimeUnit.SECONDS)
            envelope("{}")
        }
        setUpdate(UpdateCheck.OK)
        assertTrue(arrived.await(WAIT_S, TimeUnit.SECONDS))
        val first = body(all("POST", "/v1/devices/push-token").single())
        assertEquals("mobpush", first["provider"]!!.jsonPrimitive.content)
        assertEquals("test-push-token-A", first["token"]!!.jsonPrimitive.content)
        answers.remove("/v1/devices/push-token")
        composeRule.runOnUiThread {
            push.change("test-push-token-B")
            push.change("test-push-token-C")
        }
        settle()
        assertEquals("同一时刻只一条", 1, count("POST", "/v1/devices/push-token"))
        release.countDown()
        idleUntil { count("POST", "/v1/devices/push-token") == 2 }
        Thread.sleep(LATE_MS)
        settle()
        assertEquals("只补发最新值", 2, count("POST", "/v1/devices/push-token"))
        assertEquals("test-push-token-C", body(all("POST", "/v1/devices/push-token").last())["token"]!!.jsonPrimitive.content)
        assertTrue("诊断信息：本次运行已上报", me.push.reportedThisRun)
    }

    @Test
    fun ac76_loginSuccessReportsWithTheNewSessionAndOldResponsesChangeNothing() {
        launch(user = null)
        assertEquals(0, count("POST", "/v1/devices/push-token"))
        // A 登录成功（新会话保存之后、登录完成通知）
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        answers["/v1/devices/push-token"] = {
            arrived.countDown()
            release.await(WAIT_S, TimeUnit.SECONDS)
            envelope("{}")
        }
        composeRule.runOnUiThread {
            tokens.save("access-user-a", "refresh-user-a", "full", "user-a")
            login.loginCompleted(tokens.loginEpoch)
            login.syncSession()
            me.syncIdentity()
        }
        assertTrue(arrived.await(WAIT_S, TimeUnit.SECONDS))
        assertEquals("Bearer access-user-a", all("POST", "/v1/devices/push-token").single().header("Authorization"))
        answers.remove("/v1/devices/push-token")
        // A 在途时换登 B
        composeRule.runOnUiThread {
            tokens.save("access-user-b", "refresh-user-b", "full", "user-b")
            login.loginCompleted(tokens.loginEpoch)
            login.syncSession()
            me.syncIdentity()
        }
        idleUntil { all("POST", "/v1/devices/push-token").any { it.header("Authorization") == "Bearer access-user-b" } }
        release.countDown()
        Thread.sleep(LATE_MS)
        settle()
        assertEquals("A 的响应不触发任何补发", 2, count("POST", "/v1/devices/push-token"))
    }

    @Test
    fun ac76_noReportForLogoutDeletionOnlyBasicNoTokenAndTenFourOFiveGoesToForceUpdate() {
        push.token = null
        launch()
        settle()
        assertEquals("没有令牌 0 次", 0, count("POST", "/v1/devices/push-token"))
        answers["/v1/devices/push-token"] = { envelope("""{"min_supported_version":"9.0.0"}""", code = 10405) }
        composeRule.runOnUiThread { push.change("test-push-token-A") }
        idleUntil { forced >= 1 }
        // deletion_only 不报
        answers.remove("/v1/devices/push-token")
        val before = count("POST", "/v1/devices/push-token")
        composeRule.runOnUiThread {
            tokens.save("access-user-d", "refresh-user-d", "deletion_only", "user-d")
            login.loginCompleted(tokens.loginEpoch)
            login.syncSession()
            me.syncIdentity()
        }
        settle()
        assertEquals("deletion_only 不报", before, count("POST", "/v1/devices/push-token"))
        // 退出后不报
        switchSession(null)
        composeRule.runOnUiThread { push.change("test-push-token-Z") }
        settle()
        assertEquals("退出后不报", before, count("POST", "/v1/devices/push-token"))
    }

    // ---------------------------------------------------------------- AC-S1-84：引导条与通知设置

    @Test
    fun ac84_guideBarFollowsTheFortyEightHourAndSevenDayClock() {
        ports.status = "denied"
        ports.deniedAt = now - 47 * HOUR_MS
        launch()
        open("Me")
        idleUntil { has(MeTags.ME_NICKNAME) }
        assertFalse("拒绝后 48 小时内不显示", has(MeTags.ME_GUIDE_BAR))
        clock = now + 2 * HOUR_MS
        open("Settings")
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        idleUntil { has(MeTags.ME_GUIDE_BAR) }
        click(MeTags.ME_GUIDE_BAR)
        assertEquals("只跳系统通知设置，不出系统授权框", 1, ports.settingsOpened)
        assertEquals(1, spoken(t("me.guide_bar.close")))
        click(MeTags.ME_GUIDE_CLOSE)
        assertFalse(has(MeTags.ME_GUIDE_BAR))
        clock += 6 * DAY_MS
        val sixDays = meJobs()
        open("Settings")
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        // FU-AND-0716 窄修六：回到「我的」时的读取在后台，引导条要等资料与通知状态落地才决定；等它结束再核对「不显示」
        meWorkSince(sixDays)
        assertFalse("关闭后 6 天不显示", has(MeTags.ME_GUIDE_BAR))
        clock += DAY_MS
        open("Settings")
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        idleUntil { has(MeTags.ME_GUIDE_BAR) }
        ports.status = "granted"
        val granted = meJobs()
        open("Settings")
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        meWorkSince(granted)
        assertFalse("系统已允许时不显示", has(MeTags.ME_GUIDE_BAR))
    }

    @Test
    fun ac84_notifySettingsShowsTheSystemBarAndRevertsAFailedSwitch() {
        ports.status = "denied"
        launch()
        open("Settings")
        idleUntil { has(MeTags.SETTINGS_ROW_PREFIX + "settings.notify") }
        click(MeTags.SETTINGS_ROW_PREFIX + "settings.notify")
        idleUntil { has(MeTags.NOTIFY_SWITCH) }
        assertTrue(has(MeTags.NOTIFY_BAR))
        click(MeTags.NOTIFY_GO_SETTINGS)
        assertEquals(1, ports.settingsOpened)
        click(MeTags.NOTIFY_SWITCH)
        idleUntil { text(t("notify.switch_failed")) == 1 }
        val checked =
            composeRule
                .onNodeWithTag(
                    MeTags.NOTIFY_SWITCH,
                    useUnmergedTree = true,
                ).fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.ToggleableState)
        assertEquals("失败回到切换前的值", androidx.compose.ui.state.ToggleableState.On, checked)
    }

    // ---------------------------------------------------------------- AC-S1-88 ④ / 134 ④：客服

    @Test
    fun ac88_wechatMissingCopiesTheRawLinkAndGoesToHelp() {
        ports.wechat = "no"
        launch()
        open("Me")
        idleUntil { has(MeTags.ME_NICKNAME) }
        clickText(t("me.grid.cs"))
        idleUntil { has(MeTags.CS_COPY) }
        assertEquals("未装不直接打开", 0, ports.opened.size)
        click(MeTags.CS_COPY)
        assertEquals(KF_URL, system.content)
        idleUntil { text(t("cs.copy_link.done")) == 1 }
        click(MeTags.CS_HELP)
        idleUntil { top() == "Help" }
    }

    @Test
    fun ac88_unknownHandsOffAndAFailedOpenShowsOpenFailed() {
        ports.openOk = false
        launch()
        composeRule.runOnUiThread { me.cs.open("Me") }
        settle()
        idleUntil { has(MeTags.CS_OPEN) }
        click(MeTags.CS_OPEN)
        idleUntil { has(MeTags.CS_RETRY) && has(MeTags.CS_COPY) }
        assertEquals(KF_URL, ports.opened.single())
    }

    @Test
    fun ac134_twoAccountsOpenTheSameLinkWithoutAnyAccountMark() {
        launch()
        composeRule.runOnUiThread { me.cs.open("Me") }
        settle()
        click(MeTags.CS_OPEN)
        switchSession("user-two")
        composeRule.runOnUiThread { me.cs.open("Me") }
        settle()
        click(MeTags.CS_OPEN)
        assertEquals(2, ports.opened.size)
        assertEquals("逐字相同", ports.opened[0], ports.opened[1])
        listOf("user-one", "user-two", "device-fixture", "K7M3QX", "138", "token", "Me").forEach {
            assertFalse("链接不带账号标识 $it", ports.opened[0].contains(it))
        }
    }

    // ---------------------------------------------------------------- AC-S1-136 / 137：诊断信息与清除缓存

    @Test
    fun ac136_diagnosticsContainOnlyWhitelistedFields() {
        launch()
        open("About")
        idleUntil { has(MeTags.ABOUT_COPY) }
        assertTrue("AI 公示原文", has(MeTags.ABOUT_FILING))
        click(MeTags.ABOUT_COPY)
        val copied = system.content.orEmpty()
        assertTrue(copied.contains("ABC123"))
        assertTrue(copied.contains("CONFIG_123") && copied.contains("HOME_456") && copied.contains("TEST_MODEL"))
        listOf("device-fixture", "access-", "refresh-", "install-fixture", "user-one", "test-push-token").forEach {
            assertFalse("不含 $it", copied.contains(it))
        }
        idleUntil { text(dict("about.copy_diagnostics.done")) == 1 }
    }

    @Test
    fun ac137_clearCacheCancelsOrClearsOnlyTheCache() {
        launch()
        local.setPersonalization(false, clock)
        open("Settings")
        idleUntil { text("4 KB") == 1 }
        click(MeTags.SETTINGS_ROW_PREFIX + "settings.clear_cache")
        clickText(dict("settings.clear_cache.confirm_cancel"))
        assertEquals(4096L, ports.bytes)
        click(MeTags.SETTINGS_ROW_PREFIX + "settings.clear_cache")
        clickText(dict("settings.clear_cache.confirm_ok"))
        idleUntil { text(dict("settings.clear_cache.done")) == 1 }
        idleUntil { text("0 KB") == 1 }
        assertEquals("仍登录", SessionState.PHONE_BOUND == routing.session.value || SessionState.LOGGED_IN == routing.session.value, true)
        assertFalse("个性化仍关闭", local.personalizationOn.value)
    }

    // ---------------------------------------------------------------- 退出登录

    @Test
    fun logout_cancelKeepsTheSessionAndConfirmLandsOnMeGuestWithoutPushReports() {
        launch()
        open("Settings")
        idleUntil { has(MeTags.SETTINGS_LOGOUT) }
        click(MeTags.SETTINGS_LOGOUT)
        clickText(dict("settings.logout.confirm_cancel"))
        assertEquals(0, count("POST", "/v1/auth/logout"))
        click(MeTags.SETTINGS_LOGOUT)
        click(MeTags.LOGOUT_DIALOG)
        idleUntil { routing.session.value == SessionState.GUEST }
        idleUntil { has(MeTags.ME_GUEST) }
        val before = count("POST", "/v1/devices/push-token")
        composeRule.runOnUiThread { push.change("test-push-token-Q") }
        settle()
        assertEquals("退出后不报", before, count("POST", "/v1/devices/push-token"))
    }

    // ---------------------------------------------------------------- Android 第 1 轮评审（android-review-r1.md）

    /** R1-01（S0）：撤回请求挂起时登录新会话，推送上报零发送；撤回完成后仍为零（基本模式）。 */
    @Test
    fun r1_01_loginWhileWithdrawIsPendingNeverReportsThePushToken() {
        launch(user = null)
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        answers["/v1/consents"] = {
            arrived.countDown()
            release.await(WAIT_S, TimeUnit.SECONDS)
            envelope("{}")
        }
        open("PrivacyCenter")
        idleUntil { has(MeTags.PRIVACY_WITHDRAW) }
        click(MeTags.PRIVACY_WITHDRAW)
        click(MeTags.WITHDRAW_DIALOG)
        assertTrue("撤回的服务端记录已发出并挂起", arrived.await(WAIT_S, TimeUnit.SECONDS))
        // 撤回进行中：登录成功（新会话保存之后的登录完成通知）、令牌变化
        composeRule.runOnUiThread {
            tokens.save("access-user-a", "refresh-user-a", "full", "user-a")
            login.loginCompleted(tokens.loginEpoch)
            login.syncSession()
            me.syncIdentity()
        }
        settle()
        composeRule.runOnUiThread { push.change("test-push-token-B") }
        Thread.sleep(LATE_MS)
        settle()
        assertEquals("撤回进行中，新会话也不上报", 0, count("POST", "/v1/devices/push-token"))
        assertTrue("撤回停报闸门仍在", me.push.withdrawHalted)
        release.countDown()
        idleUntil { privacy.conclusion == PrivacyState.BASIC_MODE }
        Thread.sleep(LATE_MS)
        settle()
        assertEquals("撤回完成后（基本模式）仍不上报", 0, count("POST", "/v1/devices/push-token"))
        assertFalse("撤回结束后闸门解除", me.push.withdrawHalted)
    }

    /** R1-01 对照：撤回正常完成后闸门解除；重新同意并登录后照常上报（闸门不会卡死）。 */
    @Test
    fun r1_01_control_afterWithdrawCompletesReconsentAndLoginReportsAgain() {
        launch(user = null)
        open("PrivacyCenter")
        idleUntil { has(MeTags.PRIVACY_WITHDRAW) }
        click(MeTags.PRIVACY_WITHDRAW)
        click(MeTags.WITHDRAW_DIALOG)
        idleUntil { privacy.conclusion == PrivacyState.BASIC_MODE }
        assertFalse(me.push.withdrawHalted)
        assertEquals(0, count("POST", "/v1/devices/push-token"))
        composeRule.runOnUiThread {
            privacy.requestFullFunction()
            privacy.agree()
        }
        settle()
        idleUntil { privacy.conclusion == PrivacyState.CONSENTED }
        push.token = "test-push-token-C"
        composeRule.runOnUiThread {
            tokens.save("access-user-a", "refresh-user-a", "full", "user-a")
            login.loginCompleted(tokens.loginEpoch)
            login.syncSession()
            me.syncIdentity()
        }
        idleUntil { count("POST", "/v1/devices/push-token") == 1 }
        assertEquals("Bearer access-user-a", all("POST", "/v1/devices/push-token").single().header("Authorization"))
    }

    /** R1-02：设置页退出确认框——取消后重放旧闭包、重新打开后重放旧闭包都不退出；当前确认框照常生效。 */
    @Test
    fun r1_02_staleLogoutConfirmOnSettingsIsIgnoredAfterCancelAndReopen() {
        launch()
        open("Settings")
        idleUntil { has(MeTags.SETTINGS_LOGOUT) }
        click(MeTags.SETTINGS_LOGOUT)
        val stale = clickAction(MeTags.LOGOUT_DIALOG)
        clickText(dict("settings.logout.confirm_cancel"))
        assertFalse(has(MeTags.LOGOUT_DIALOG))
        composeRule.runOnUiThread { stale() }
        settle()
        assertEquals("取消后旧闭包不退出", 0, count("POST", "/v1/auth/logout"))
        assertTrue(tokens.accessToken != null)
        click(MeTags.SETTINGS_LOGOUT)
        idleUntil { has(MeTags.LOGOUT_DIALOG) }
        composeRule.runOnUiThread { stale() }
        settle()
        assertEquals("重新打开后旧闭包不退出", 0, count("POST", "/v1/auth/logout"))
        assertTrue("当前确认框还在", has(MeTags.LOGOUT_DIALOG))
        click(MeTags.LOGOUT_DIALOG)
        idleUntil { routing.session.value == SessionState.GUEST }
    }

    /** R1-02 同类：清缓存、隐私中心撤回、RiskNotice 与申诉页的退出确认框，旧闭包一律作废。 */
    @Test
    fun r1_02_staleConfirmsOnClearCacheWithdrawRiskNoticeAndAppealAreIgnored() {
        risk = "banned"
        appeals = listOf(appeal("appeal-old", "upheld"))
        launch()
        open("Settings")
        idleUntil { has(MeTags.SETTINGS_ROW_PREFIX + "settings.clear_cache") }
        click(MeTags.SETTINGS_ROW_PREFIX + "settings.clear_cache")
        val clear = clickAction(MeTags.CLEAR_DIALOG)
        clickText(dict("settings.clear_cache.confirm_cancel"))
        composeRule.runOnUiThread { clear() }
        settle()
        assertEquals("取消后旧闭包不清缓存", 0, ports.webClears)
        assertEquals(4096L, ports.bytes)

        open("PrivacyCenter")
        idleUntil { has(MeTags.PRIVACY_WITHDRAW) }
        click(MeTags.PRIVACY_WITHDRAW)
        val withdraw = clickAction(MeTags.WITHDRAW_DIALOG)
        clickText(t("privacy.withdraw.confirm.cancel"))
        composeRule.runOnUiThread { withdraw() }
        settle()
        click(MeTags.PRIVACY_WITHDRAW)
        idleUntil { has(MeTags.WITHDRAW_DIALOG) }
        composeRule.runOnUiThread { withdraw() }
        settle()
        assertEquals("旧闭包不撤回", PrivacyState.CONSENTED, privacy.conclusion)
        assertEquals(0, count("POST", "/v1/consents"))
        clickText(t("privacy.withdraw.confirm.cancel"))

        open("RiskNotice")
        idleUntil { has(MeTags.RISK_LOGOUT) }
        click(MeTags.RISK_LOGOUT)
        val riskLogout = clickAction(MeTags.RISK_LOGOUT_DIALOG)
        clickText(dict("settings.logout.confirm_cancel"))
        composeRule.runOnUiThread { riskLogout() }
        settle()
        click(MeTags.RISK_PRIMARY)
        idleUntil { has(MeTags.APPEAL_LOGOUT) }
        click(MeTags.APPEAL_LOGOUT)
        val appealLogout = clickAction(MeTags.APPEAL_LOGOUT_DIALOG)
        clickText(dict("settings.logout.confirm_cancel"))
        composeRule.runOnUiThread { appealLogout() }
        settle()
        click(MeTags.APPEAL_LOGOUT)
        idleUntil { has(MeTags.APPEAL_LOGOUT_DIALOG) }
        composeRule.runOnUiThread {
            riskLogout()
            appealLogout()
        }
        settle()
        assertEquals("旧闭包都不退出", 0, count("POST", "/v1/auth/logout"))
        assertTrue(tokens.accessToken != null)
    }

    /** R1-03：「我的」发起的 getMe 在等令牌刷新时切到其他 Tab：刷新结束后不重放、不结算到共享快照。 */
    @Test
    fun r1_03_meReadWaitingForTokenRefreshIsDroppedAfterLeavingTheTab() {
        launch()
        val baseline = loginCheckDone()
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        var first = true
        answers["/v1/me"] = {
            if (first) {
                first = false
                envelope("null", code = 10002)
            } else {
                envelope(meJson("user-one"))
            }
        }
        answers["/v1/auth/refresh"] = {
            arrived.countDown()
            release.await(WAIT_S, TimeUnit.SECONDS)
            envelope("{${pair("user-one-r")}}")
        }
        open("Me")
        assertTrue("getMe 收到 10002、在等刷新", arrived.await(WAIT_S, TimeUnit.SECONDS))
        open("OrderList")
        idleUntil { !has(MeTags.ME_PAGE) }
        release.countDown()
        Thread.sleep(LATE_MS)
        settle()
        assertEquals("离开 Tab 后刷新结束不再重放 getMe", baseline + 1, count("GET", "/v1/me"))
        assertNull("不结算到共享快照", me.deps.repo.state.value.me)
        assertFalse("没有限流状态", me.gates.read.active)
    }

    /** R1-03 同类：设置页发起的 getMe 在途时出栈，结果不结算。 */
    @Test
    fun r1_03_settingsReadInFlightIsDroppedWhenSettingsIsPopped() {
        launch()
        loginCheckDone()
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        answers["/v1/me"] = {
            arrived.countDown()
            release.await(WAIT_S, TimeUnit.SECONDS)
            envelope(meJson("user-one"))
        }
        open("Settings")
        assertTrue(arrived.await(WAIT_S, TimeUnit.SECONDS))
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        release.countDown()
        Thread.sleep(LATE_MS)
        settle()
        assertNull("出栈后的读取结果不结算", me.deps.repo.state.value.me)
    }

    /** R1-04：RiskNotice 的申诉摘要读取收到 42901：启动共用读取禁用期；回到本页在禁用期内不再发任何读取，重试置灰。 */
    @Test
    fun r1_04_riskNoticeExtraReadsHonorRetryAfter() {
        risk = "banned"
        launch()
        answers["/v1/me/appeals"] = { rate() }
        open("RiskNotice")
        idleUntil { has(MeTags.RISK_APPEALS_ERROR) }
        assertTrue("42901 启动读取禁用期", me.gates.read.active)
        composeRule.onNodeWithTag(MeTags.RISK_APPEALS_RETRY, useUnmergedTree = true).assertIsNotEnabled()
        val before = lines().size
        // 离开再回到本页（ON_RESUME 重新读取）：禁用期内一条都不发
        open("About")
        idleUntil { top() == "About" }
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        idleUntil { top() == "RiskNotice" }
        Thread.sleep(LATE_MS)
        settle()
        assertEquals("禁用期内不发：${lines().drop(before)}", 0, lines().drop(before).count { it.startsWith("GET ") })
    }

    /** R1-04 同类：提现读取收到 42901 同样启动禁用期。 */
    @Test
    fun r1_04_riskNoticeWithdrawalsRateLimitStartsTheReadGate() {
        risk = "frozen"
        launch()
        answers["/v1/withdrawals"] = { rate() }
        open("RiskNotice")
        idleUntil { has(MeTags.RISK_WITHDRAWALS_ERROR) }
        assertTrue(me.gates.read.active)
        composeRule.onNodeWithTag(MeTags.RISK_WITHDRAWALS_RETRY, useUnmergedTree = true).assertIsNotEnabled()
    }

    /** R1-05：申诉页 getMe 收到 42901：先处理限流，不再续发 listAppeals。 */
    @Test
    fun r1_05_appealHandlesTheGetMeRateLimitBeforeSendingTheList() {
        risk = "frozen"
        launch()
        loginCheckDone()
        answers["/v1/me"] = { rate() }
        open("Appeal")
        idleUntil { has(MeTags.APPEAL_LOAD_ERROR) }
        assertTrue("getMe 的 42901 已启动禁用期", me.gates.read.active)
        assertEquals("不续发 listAppeals", 0, count("GET", "/v1/me/appeals"))
    }

    /** R1-05：getMe 成功、listAppeals 收到 42901：这个响应的限流同样生效，页面显示限流提示。 */
    @Test
    fun r1_05_appealListRateLimitIsNotDropped() {
        risk = "frozen"
        launch()
        answers["/v1/me/appeals"] = { rate() }
        open("Appeal")
        idleUntil { has(MeTags.APPEAL_LOAD_ERROR) }
        assertTrue(me.gates.read.active)
        assertEquals(1, text(dict("error.42901")))
    }

    /** R1-06：设置页没有 Me 快照时 getMe 失败：账号资料区显示统一错误、trace6 与【重试】，公共入口照常；重试成功后显示昵称。 */
    @Test
    fun r1_06_settingsShowsTheMeLoadErrorWithTrace6AndRetry() {
        launch()
        loginCheckDone()
        answers["/v1/me"] = { envelope("null", code = 50001, trace = "trace-ABC123456") }
        open("Settings")
        idleUntil { has(MeTags.SETTINGS_ME_ERROR) }
        assertTrue("带 trace6", text("123456") >= 1)
        assertTrue("公共设置入口保留", has(MeTags.SETTINGS_ROW_PREFIX + "settings.privacy_center"))
        answers.remove("/v1/me")
        clickText(t("me.retry"))
        idleUntil { !has(MeTags.SETTINGS_ME_ERROR) && text("凑狸用户") >= 1 }
        // 已有资料时刷新失败：保留资料并提示一次（trace6）
        answers["/v1/me"] = { envelope("null", code = 50001, trace = "trace-ZZZ654321") }
        composeRule.runOnUiThread { me.deps.repo.refresh(me.identity.value, { true }) }
        idleUntil { text("654321") >= 1 }
        assertTrue(text("凑狸用户") >= 1)
    }

    /** R1-06：读取禁用期内设置页的【重试】置灰。 */
    @Test
    fun r1_06_settingsRetryIsGatedByTheReadGate() {
        launch()
        val baseline = loginCheckDone()
        answers["/v1/me"] = { rate() }
        open("Settings")
        idleUntil { has(MeTags.SETTINGS_ME_ERROR) }
        assertTrue(me.gates.read.active)
        assertEquals(baseline + 1, count("GET", "/v1/me"))
        composeRule.onNodeWithText(t("me.retry")).assertIsNotEnabled()
        // 回到本页（ON_RESUME）：禁用期内不再发
        open("About")
        idleUntil { top() == "About" }
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        idleUntil { top() == "Settings" }
        assertEquals("禁用期内不再发 getMe", baseline + 1, count("GET", "/v1/me"))
    }

    /** R1-07：申诉摘要与提现读取失败各自显示统一错误与 trace6，重试只重取对应区域。 */
    @Test
    fun r1_07_riskNoticeSectionFailuresShowErrorsAndRetryOnlyThatSection() {
        risk = "banned"
        appeals = listOf(appeal("appeal-old", "upheld"))
        withdrawals =
            """{"items":[{"withdrawal_id":"w1","status":"PENDING_REVIEW","review_mode":"manual",""" +
            """"amount_fen":1000,"fee_fen":0,"net_fen":1000,"tax_fen":0,""" +
            """"reason_code":null,"fail_action":null,""" +
            """"payout_channel":"alipay","payout_channel_name":"支付宝","masked_account":"138****5678",""" +
            """"created_at":"2026-10-10T09:00:00+08:00"}],"next_cursor":null}"""
        launch()
        answers["/v1/me/appeals"] = { envelope("null", code = 50001, trace = "trace-AAA111111") }
        answers["/v1/withdrawals"] = { envelope("null", code = 50001, trace = "trace-BBB222222") }
        open("RiskNotice")
        idleUntil { has(MeTags.RISK_APPEALS_ERROR) && has(MeTags.RISK_WITHDRAWALS_ERROR) }
        assertFalse("失败不当成无申诉：不显示旧结论", has(MeTags.RISK_UPHELD))
        assertTrue(text("111111") >= 1 && text("222222") >= 1)
        val me0 = count("GET", "/v1/me")
        val appeals0 = count("GET", "/v1/me/appeals")
        answers.remove("/v1/withdrawals")
        click(MeTags.RISK_WITHDRAWALS_RETRY)
        idleUntil { has(MeTags.RISK_WITHDRAWALS) }
        assertEquals("只重取提现", me0, count("GET", "/v1/me"))
        assertEquals(appeals0, count("GET", "/v1/me/appeals"))
        assertTrue("申诉区仍是错误", has(MeTags.RISK_APPEALS_ERROR))
        answers.remove("/v1/me/appeals")
        click(MeTags.RISK_APPEALS_RETRY)
        idleUntil { has(MeTags.RISK_UPHELD) && !has(MeTags.RISK_APPEALS_ERROR) }
        assertEquals("只重取申诉摘要", me0, count("GET", "/v1/me"))
    }

    /** R1-08：配置重建（StateRestorationTester 模拟 Activity 重建）后昵称子页与草稿保留；真正出栈后清空。 */
    @Test
    fun r1_08_configurationRebuildKeepsTheNicknameDraft() {
        val restorer = StateRestorationTester(composeRule)
        launch(restorer = restorer)
        open("Settings")
        idleUntil { has(MeTags.SETTINGS_ROW_PREFIX + "settings.nickname") && meLoaded() }
        click(MeTags.SETTINGS_ROW_PREFIX + "settings.nickname")
        input(MeTags.NICKNAME_INPUT, "小狸草稿")
        restorer.emulateSavedInstanceStateRestore()
        settle()
        idleUntil { has(MeTags.NICKNAME_INPUT) }
        assertEquals("重建后草稿保留", "小狸草稿", fieldText(MeTags.NICKNAME_INPUT))
        assertEquals(listOf("Settings"), stack().filter { it != "Home" })
        // 真正出栈：草稿清空，重新进入从原昵称开始
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        open("Settings")
        click(MeTags.SETTINGS_ROW_PREFIX + "settings.nickname")
        idleUntil { has(MeTags.NICKNAME_INPUT) }
        assertEquals("凑狸用户", fieldText(MeTags.NICKNAME_INPUT))
    }

    /** R1-08：配置重建后申诉输入保留。 */
    @Test
    fun r1_08_configurationRebuildKeepsTheAppealInput() {
        risk = "frozen"
        val restorer = StateRestorationTester(composeRule)
        launch(restorer = restorer)
        open("Appeal")
        idleUntil { has(MeTags.APPEAL_INPUT) }
        input(MeTags.APPEAL_INPUT, "测试申诉，请核查。")
        restorer.emulateSavedInstanceStateRestore()
        settle()
        idleUntil { has(MeTags.APPEAL_INPUT) }
        assertEquals("测试申诉，请核查。", fieldText(MeTags.APPEAL_INPUT))
    }

    /** R1-09：昵称字段错误、申诉字段错误之后焦点落在错误提示上（真正可聚焦的目标）。 */
    @Test
    fun r1_09_fieldErrorsMoveFocusToTheErrorText() {
        risk = "frozen"
        launch()
        open("Settings")
        idleUntil { has(MeTags.SETTINGS_ROW_PREFIX + "settings.nickname") && meLoaded() }
        click(MeTags.SETTINGS_ROW_PREFIX + "settings.nickname")
        input(MeTags.NICKNAME_INPUT, "敏感词测试")
        click(MeTags.NICKNAME_SAVE)
        idleUntil { has(MeTags.NICKNAME_ERROR) }
        settle()
        composeRule.onNodeWithTag(MeTags.NICKNAME_ERROR, useUnmergedTree = true).assertIsFocused()

        answers["/v1/me/appeals"] = { request ->
            if (request.method == "POST") {
                envelope("""{"reason":"content_invalid"}""", code = 20001)
            } else {
                envelope("""{"items":[],"next_cursor":null}""")
            }
        }
        open("Appeal")
        idleUntil { has(MeTags.APPEAL_INPUT) }
        input(MeTags.APPEAL_INPUT, "测试申诉，请核查。")
        click(MeTags.APPEAL_SUBMIT)
        idleUntil { has(MeTags.APPEAL_ERROR) }
        settle()
        composeRule.onNodeWithTag(MeTags.APPEAL_ERROR, useUnmergedTree = true).assertIsFocused()
    }

    /** R1-10：清缓存先清 WebView 资源缓存、再清缓存目录，都完成后才提示；WebView 清除失败按失败提示。 */
    @Test
    fun r1_10_clearCacheAlsoClearsTheWebViewCacheBeforeReporting() {
        launch()
        open("Settings")
        idleUntil { text("4 KB") == 1 }
        click(MeTags.SETTINGS_ROW_PREFIX + "settings.clear_cache")
        click(MeTags.CLEAR_DIALOG)
        idleUntil { text(dict("settings.clear_cache.done")) == 1 }
        assertEquals("WebView 缓存清了一次", 1, ports.webClears)
        assertTrue("仍登录", tokens.accessToken != null)
        ports.webOk = false
        ports.bytes = 2048L
        Thread.sleep(2_100L)
        settle()
        click(MeTags.SETTINGS_ROW_PREFIX + "settings.clear_cache")
        click(MeTags.CLEAR_DIALOG)
        idleUntil { ports.webClears == 2 }
        settle()
        assertEquals("WebView 清除失败不报已清除", 0, text(dict("settings.clear_cache.done")))
    }

    /** R1-11：200% 字号下把「我的」滚到下方，切到订单 Tab 再回来，滚动位置保留。 */
    @Test
    fun r1_11_switchingTabsKeepsTheMeScrollPosition() {
        org.robolectric.RuntimeEnvironment.setFontScale(2f)
        launch()
        open("Me")
        idleUntil { has(MeTags.ME_NICKNAME) }
        composeRule.onNodeWithTag(MeTags.ME_SCROLL, useUnmergedTree = true).performTouchInput { swipeUp() }
        settle()
        val before = scrollValue()
        assertTrue("已滚到下方：$before", before > 0f)
        open("OrderList")
        idleUntil { !has(MeTags.ME_PAGE) }
        open("Me")
        idleUntil { has(MeTags.ME_SCROLL) }
        assertEquals("切 Tab 往返保留滚动位置", before, scrollValue())
        // 换账号：不带上一个账号的位置
        switchSession("user-two")
        idleUntil { has(MeTags.ME_SCROLL) }
        assertEquals(0f, scrollValue())
    }

    // ---------------------------------------------------------------- 第 2 轮评审（android-review-r2.md）

    /**
     * R2-01：「我的」的 getMe 在等令牌刷新；经 Router 切到订单 Tab（外壳已改选中项）但主线程还没重组时释放刷新屏障——同步出口已撤销读取许可，网络层不重放、
     * 结果不结算到共享快照。
     */
    @Test
    fun r2_01_tabSwitchRevokesTheMeReadBeforeRecomposition() {
        launch()
        val baseline = loginCheckDone()
        val release = holdMeBehindTokenRefresh()
        open("Me")
        assertTrue("getMe 收到 10002、在等刷新", refreshArrived.await(WAIT_S, TimeUnit.SECONDS))
        var committed = false
        composeRule.runOnUiThread {
            routing.router.open(RouteRequest("OrderList", JsonObject(emptyMap()), RouteSource.IN_APP))
            // 导航已提交（Tab 根仍是「我的」的组合，onDispose 还没跑）：此刻释放屏障，主线程继续占住，网络层自己完成刷新与重放判断
            committed = routing.navTickets.ticket()?.tab == "OrderList"
            release.countDown()
            Thread.sleep(REPLAY_MS)
        }
        settle()
        Thread.sleep(LATE_MS)
        settle()
        assertTrue("切 Tab 已在同步出口提交", committed)
        assertEquals("刷新结束后不重放已离开的读取：${lines()}", baseline + 1, count("GET", "/v1/me"))
        assertNull("不结算到共享快照", me.deps.repo.state.value.me)
        assertFalse(has(MeTags.ME_PAGE))
    }

    /** R2-01 同类：「我的」的 getMe 在等刷新时经 Router 进入设置页（回退栈压入下一页的同步出口），同样在重组之前撤销；设置页自己的读取照常。 */
    @Test
    fun r2_01_pushingSettingsRevokesTheMeReadBeforeRecomposition() {
        launch()
        val baseline = loginCheckDone()
        val release = holdMeBehindTokenRefresh()
        open("Me")
        assertTrue(refreshArrived.await(WAIT_S, TimeUnit.SECONDS))
        composeRule.runOnUiThread {
            routing.router.open(RouteRequest("Settings", JsonObject(emptyMap()), RouteSource.IN_APP))
            release.countDown()
            Thread.sleep(REPLAY_MS)
        }
        idleUntil { top() == "Settings" && text("凑狸用户") >= 1 }
        Thread.sleep(LATE_MS)
        settle()
        assertEquals("「我的」的读取不重放，只多设置页自己的一次：${lines()}", baseline + 2, count("GET", "/v1/me"))
        // 返回「我的」：按新的一次进入重新读取
        composeRule.runOnUiThread { navController.popBackStack() }
        idleUntil { has(MeTags.ME_NICKNAME) }
    }

    private val refreshArrived = CountDownLatch(1)

    /** 第一次 getMe 回 10002，令牌刷新挂起直到返回的闸门放开；之后的 getMe 正常。 */
    private fun holdMeBehindTokenRefresh(): CountDownLatch {
        val release = CountDownLatch(1)
        var first = true
        answers["/v1/me"] = {
            if (first) {
                first = false
                envelope("null", code = 10002)
            } else {
                envelope(meJson("user-one"))
            }
        }
        answers["/v1/auth/refresh"] = {
            refreshArrived.countDown()
            release.await(WAIT_S, TimeUnit.SECONDS)
            envelope("{${pair("user-one-r")}}")
        }
        return release
    }

    /** R2-02：提交页回前台触发的读取（旧列表）晚于提交成功返回：不覆盖进度，页面不回到提交表单。 */
    @Test
    fun r2_02_staleAppealListArrivingAfterSubmitSuccessDoesNotOverwriteProgress() {
        risk = "frozen"
        launch()
        open("Appeal")
        idleUntil { has(MeTags.APPEAL_INPUT) }
        input(MeTags.APPEAL_INPUT, "测试申诉，请核查。")
        val listArrived = CountDownLatch(1)
        val releaseList = CountDownLatch(1)
        answers["/v1/me/appeals"] = { request ->
            if (request.method == "POST") {
                val created = appeal("appeal-new", "processing")
                appeals = listOf(created)
                risk = "appealing"
                envelope(created.toString())
            } else {
                // 提交之前发出的读取：带着提交前的空列表，挂到提交成功之后才返回
                listArrived.countDown()
                releaseList.await(WAIT_S, TimeUnit.SECONDS)
                envelope("""{"items":[],"next_cursor":null}""")
            }
        }
        // 回前台（ON_RESUME）：保留 CONTENT 与可提交表单，再取一次
        open("About")
        idleUntil { top() == "About" }
        composeRule.runOnUiThread { navController.popBackStack() }
        idleUntil { listArrived.count == 0L }
        idleUntil { top() == "Appeal" && has(MeTags.APPEAL_SUBMIT) }
        click(MeTags.APPEAL_SUBMIT)
        idleUntil { has(MeTags.APPEAL_PROGRESS) }
        releaseList.countDown()
        Thread.sleep(LATE_MS)
        settle()
        assertTrue("旧列表不覆盖进度", has(MeTags.APPEAL_PROGRESS))
        assertFalse("不回到提交表单", has(MeTags.APPEAL_SUBMIT) || has(MeTags.APPEAL_INPUT))
        // 之后的刷新绑定新世代：再回前台照常取到进度
        answers.remove("/v1/me/appeals")
        open("About")
        idleUntil { top() == "About" }
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        idleUntil { top() == "Appeal" && has(MeTags.APPEAL_PROGRESS) }
    }

    /** R2-03：账号 A 因 10006 进入 RiskNotice 后退出、登录账号 B：B 的冻结申诉（appealing）按冻结版式显示，不套用 A 的封禁依据。 */
    @Test
    fun r2_03_the10006LayoutMarkerDoesNotCarryOverToAnotherAccount() {
        risk = "appealing"
        launch()
        loginCheckDone()
        open("Me")
        idleUntil { has(MeTags.ME_NICKNAME) }
        composeRule.runOnUiThread { me.on10006(routing.navTickets.ticket()) { true } }
        idleUntil { top() == "RiskNotice" && has(MeTags.RISK_TITLE) }
        assertEquals("A 因 10006 进入：封禁的申诉处理中", 1, text(t("risk.banned.title")))
        assertTrue(has(MeTags.RISK_LIMIT))
        assertTrue(me.enteredBy10006(me.identity.value))

        switchSession("user-two")
        idleUntil { top() != "RiskNotice" }
        assertFalse("换账号即清除", me.enteredBy10006(me.identity.value))
        open("RiskNotice")
        idleUntil { has(MeTags.RISK_TITLE) }
        assertEquals("B 是冻结的申诉处理中", 1, text(t("risk.frozen.title")))
        assertEquals(0, text(t("risk.banned.title")))
        assertFalse("不显示长期限制", has(MeTags.RISK_LIMIT))
        assertTrue("冻结有返回", spoken(t("me.back")) >= 1)
    }

    /** R2-03 同类：退出再登回同一账号是新的登录世代，10006 依据同样不保留。 */
    @Test
    fun r2_03_logoutAndLoginAgainClearsThe10006Marker() {
        launch()
        loginCheckDone()
        composeRule.runOnUiThread { me.on10006(routing.navTickets.ticket()) { true } }
        idleUntil { top() == "RiskNotice" }
        assertTrue(me.enteredBy10006(me.identity.value))
        switchSession(null)
        switchSession("user-one")
        assertFalse(me.enteredBy10006(me.identity.value))
    }

    /** R2-04：RiskNotice 已有内容 → 申诉页读取收到 42901 → 返回 RiskNotice：保留内容，但明确标示没有刷新、显示 42901，【重试】受同一截止时间控制。 */
    @Test
    fun r2_04_riskNoticeShowsTheReadRateLimitEvenWithContent() {
        risk = "banned"
        launch()
        loginCheckDone()
        open("RiskNotice")
        idleUntil { has(MeTags.RISK_TITLE) && has(MeTags.RISK_PRIMARY) }
        answers["/v1/me"] = { rate() }
        click(MeTags.RISK_PRIMARY)
        idleUntil { top() == "Appeal" && has(MeTags.APPEAL_LOAD_ERROR) }
        assertTrue(me.gates.read.active)
        val before = lines().size
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        idleUntil { top() == "RiskNotice" && has(MeTags.RISK_REFRESH_ERROR) }
        assertTrue("保留上次读到的内容", has(MeTags.RISK_TITLE))
        assertTrue("明确标示没有刷新", text(t("risk.refresh_failed")) >= 1)
        assertTrue("显示 42901", text(dict("error.42901")) >= 1)
        composeRule.onNodeWithTag(MeTags.RISK_REFRESH_RETRY, useUnmergedTree = true).assertIsNotEnabled()
        assertEquals("禁用期内不发：${lines().drop(before)}", 0, lines().drop(before).count { it.startsWith("GET ") })
        // 截止时间过了：重试可用，刷新成功后标示消失
        answers.remove("/v1/me")
        composeRule.runOnUiThread { me.gates.read.reset() }
        settle()
        click(MeTags.RISK_REFRESH_RETRY)
        idleUntil { !has(MeTags.RISK_REFRESH_ERROR) && has(MeTags.RISK_TITLE) }
    }

    /** R2-04 同类：已有内容时 getMe 返回 5xxxx：同样保留内容并标示没有刷新（带 trace6）。 */
    @Test
    fun r2_04_riskNoticeRefreshFailureWithContentIsMarked() {
        risk = "frozen"
        launch()
        loginCheckDone()
        open("RiskNotice")
        idleUntil { has(MeTags.RISK_TITLE) }
        answers["/v1/me"] = { envelope("null", code = 50001, trace = "trace-ABC654321") }
        open("About")
        idleUntil { top() == "About" }
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        idleUntil { top() == "RiskNotice" && has(MeTags.RISK_REFRESH_ERROR) }
        assertTrue(has(MeTags.RISK_TITLE))
        assertTrue("带 trace6", text("654321") >= 1)
    }

    /** R2-05：冷启动校验 phone_bound 的 getMe 收到 42901：启动的是与「我的」共用的截止时间，随即进入「我的」不再请求，显示 42901。 */
    @Test
    fun r2_05_coldStartPhoneBoundRateLimitGatesTheMePage() {
        answers["/v1/me"] = { rate() }
        launch()
        idleUntil { count("GET", "/v1/me") >= 1 }
        Thread.sleep(LATE_MS)
        settle()
        // RC-02：请求记下不等于 42901 已结算；等冷启动补查连同限流结算结束
        coldStartCheckSettled()
        assertEquals(1, count("GET", "/v1/me"))
        assertTrue("登录校验的 42901 启动 Me 读取禁用期", me.gates.read.active)
        answers.remove("/v1/me")
        open("Me")
        idleUntil { has(MeTags.ME_ERROR) }
        assertTrue(text(dict("error.42901")) >= 1)
        assertEquals("同一截止时间内「我的」不再请求", 1, count("GET", "/v1/me"))
    }

    /**
     * FU-AND-0716 RC-02：冷启动补查的 42901 被服务端挂住（请求已记下、应答未出）。先跑一遍原写法（等请求记下 → 睡 LATE_MS → 固定轮次），断言此时禁用期
     * 还没启动（原写法在这里就断言 active 了）；再在 [coldStartCheckSettled] 第一次核对时放行（显式握手），返回后断言禁用期已启动、「我的」不再请求。
     */
    @Test
    fun fuand0716_rc02_aHeldColdStart42901IsAwaitedBeforeAssertingTheMeReadGate() {
        val arrived = CountDownLatch(1)
        val held = CountDownLatch(1)
        answers["/v1/me"] = {
            arrived.countDown()
            held.await(FlowWait.CEILING_MS, TimeUnit.MILLISECONDS)
            rate()
        }
        try {
            launch()
            assertTrue("冷启动补查已到服务端、应答挂住", arrived.await(FlowWait.CEILING_MS, TimeUnit.MILLISECONDS))
            // 原写法
            idleUntil { count("GET", "/v1/me") >= 1 }
            Thread.sleep(LATE_MS)
            settle()
            assertEquals(1, count("GET", "/v1/me"))
            assertFalse("挂住期间固定等待之后禁用期还没启动", me.gates.read.active)
            assertFalse("挂住期间补查还没结束", login.phoneChecksSettled())
            var released = false
            coldStartCheckSettled {
                if (!released) {
                    released = true
                    held.countDown()
                }
            }
            assertTrue("等待开始轮询后才放行", released)
            assertEquals(1, count("GET", "/v1/me"))
            assertTrue("登录校验的 42901 启动 Me 读取禁用期", me.gates.read.active)
            answers.remove("/v1/me")
            open("Me")
            idleUntil { has(MeTags.ME_ERROR) }
            assertTrue(text(dict("error.42901")) >= 1)
            assertEquals("同一截止时间内「我的」不再请求", 1, count("GET", "/v1/me"))
        } finally {
            held.countDown()
        }
    }

    /** R2-05 反方向：「我的」读取的禁用期内，登录侧的 phone_bound 校验在发送前被同一截止时间拦下。 */
    @Test
    fun r2_05_loginPhoneBoundCheckHonorsTheMeReadDeadline() {
        launch()
        loginCheckDone()
        answers["/v1/me"] = { rate() }
        open("Me")
        idleUntil { has(MeTags.ME_ERROR) }
        assertTrue(me.gates.read.active)
        val n = count("GET", "/v1/me")
        val known = kotlinx.coroutines.runBlocking { login.oauth.refreshMe() }
        assertFalse("禁用期内不发、按没查清", known)
        assertEquals("登录侧没有发 getMe", n, count("GET", "/v1/me"))
    }

    // ---------------------------------------------------------------- R3：getMe 截止时间跨模块共享、限流结算复核、申诉成功恢复页面

    /** 与 AppModule 相同接线的钱包（实名查询的 getMe 接共享 Me 读取截止时间）。 */
    private fun wallet(): com.couli.app.wallet.AppWallet {
        lateinit var wallet: com.couli.app.wallet.AppWallet
        composeRule.runOnUiThread {
            wallet =
                com.couli.app.wallet.AppWallet(
                    api =
                        com.couli.feature.wallet
                            .ApiClientWalletApi(api),
                    store = null,
                    routing = routing,
                    sessionEpoch = { tokens.loginEpoch },
                    account = { api.activeUserId() },
                    writer = { false },
                    forceUpdate = {},
                    cache = null,
                    mask = null,
                    realname = null,
                    labor = null,
                    stepUpPhone = { null },
                    meRead = { login.meReadGate },
                )
        }
        return wallet
    }

    /** 与 AppWeb.Services.user 相同的 getMe（桥调用一直有效）。 */
    private fun bridgeGetUser(): ApiResult =
        kotlinx.coroutines.runBlocking {
            bridgeGetMe(api, login.meReadGate, tokens, inFlight) { true }
        }

    /** 「我的」读取收到 42901：共享截止时间已启动，返回此刻的 GET /v1/me 条数。 */
    private fun meRateLimited(): Int {
        launch()
        loginCheckDone()
        answers["/v1/me"] = { rate() }
        open("Me")
        idleUntil { has(MeTags.ME_ERROR) }
        assertTrue(me.gates.read.active)
        answers.remove("/v1/me")
        return count("GET", "/v1/me")
    }

    /** R3-01 Me → 钱包：「我的」getMe 的 42901 禁用期内，钱包实名查询（同为 getMe）不发送。 */
    @Test
    fun r3_01_meRateLimitGatesTheWalletRealnameQuery() {
        val n = meRateLimited()
        val wallet = wallet()
        composeRule.runOnUiThread { wallet.refreshRealname() }
        Thread.sleep(LATE_MS)
        settle()
        assertEquals("钱包实名查询没有发 getMe", n, count("GET", "/v1/me"))
    }

    /** R3-01 Me → 桥：「我的」getMe 的 42901 禁用期内，桥 auth.getUser 不发送（本地拒绝，按 90500 结束）。 */
    @Test
    fun r3_01_meRateLimitGatesTheBridgeGetUser() {
        val n = meRateLimited()
        val result = bridgeGetUser()
        assertTrue("本地拒绝：$result", result is ApiResult.LocalReject)
        assertEquals("桥没有发 getMe", n, count("GET", "/v1/me"))
    }

    /** R3-01 钱包 → Me：钱包实名查询收到 42901，启动的是共享截止时间；随即进入「我的」不再请求，显示 42901。 */
    @Test
    fun r3_01_walletRealnameRateLimitGatesTheMePage() {
        launch()
        loginCheckDone()
        val wallet = wallet()
        answers["/v1/me"] = { rate() }
        val before = count("GET", "/v1/me")
        composeRule.runOnUiThread { wallet.refreshRealname() }
        idleUntil { count("GET", "/v1/me") == before + 1 && me.gates.read.active }
        answers.remove("/v1/me")
        open("Me")
        idleUntil { has(MeTags.ME_ERROR) }
        assertTrue(text(dict("error.42901")) >= 1)
        assertEquals("同一截止时间内「我的」不再请求", before + 1, count("GET", "/v1/me"))
    }

    /** R3-01 桥 → Me：桥 auth.getUser 收到 42901，启动共享截止时间；「我的」与钱包实名查询都不再请求。 */
    @Test
    fun r3_01_bridgeGetUserRateLimitGatesMeAndWallet() {
        launch()
        loginCheckDone()
        answers["/v1/me"] = { rate() }
        val before = count("GET", "/v1/me")
        bridgeGetUser()
        settle()
        assertTrue("桥的 42901 启动 Me 读取禁用期", me.gates.read.active)
        answers.remove("/v1/me")
        open("Me")
        idleUntil { has(MeTags.ME_ERROR) }
        val wallet = wallet()
        composeRule.runOnUiThread { wallet.refreshRealname() }
        Thread.sleep(LATE_MS)
        settle()
        assertEquals("「我的」与钱包都没有再发", before + 1, count("GET", "/v1/me"))
    }

    /** R3-02：账号 A 登录侧 getMe 的 42901 已返回、主线程结算之前切到账号 B：旧结果不限流 B，B 的「我的」照常读取。 */
    @Test
    fun r3_02_staleRateLimitSettledAfterAccountSwitchDoesNotGateTheNewSession() {
        launch()
        loginCheckDone()
        answers["/v1/me"] = { rate() }
        val before = count("GET", "/v1/me")
        lateinit var stale: Job
        composeRule.runOnUiThread {
            stale =
                kotlinx.coroutines
                    .CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate)
                    .launch { login.oauth.refreshMe() }
        }
        // 只等网络结果返回（IO 线程），不推进主线程：续体停在结算之前
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S)
        while (count("GET", "/v1/me") == before && System.nanoTime() < deadline) Thread.sleep(POLL_MS)
        Thread.sleep(LATE_MS)
        assertEquals(before + 1, count("GET", "/v1/me"))
        assertFalse("主线程还没结算", me.gates.read.active)
        answers.remove("/v1/me")
        switchSession("user-two")
        Thread.sleep(LATE_MS)
        settle()
        // FU-AND-0716 窄修二：旧会话那次 refreshMe 的续体（42901 结算）要真的跑完，「不写入」才有意义；原写法固定时长 + 固定轮次后就核对，续体晚到时核对的是它之前
        FlowWait.until(::settle, { "旧会话的 refreshMe 还没跑完；请求 ${lines()}" }) { stale.isCompleted }
        assertFalse("旧会话的 42901 不写入新会话的截止时间", me.gates.read.active)
        val n = count("GET", "/v1/me")
        open("Me")
        idleUntil { count("GET", "/v1/me") > n && has(MeTags.ME_SCROLL) && !has(MeTags.ME_ERROR) }
    }

    /** R3-03：回前台的刷新先以 5xxxx 失败、提交随后成功：一次性回到内容态，显示申诉进度，不停在加载失败页。 */
    @Test
    fun r3_03_submitSuccessAfterAFailedRefreshShowsTheProgress() {
        risk = "frozen"
        launch()
        open("Appeal")
        idleUntil { has(MeTags.APPEAL_INPUT) }
        input(MeTags.APPEAL_INPUT, "测试申诉，请核查。")
        val meArrived = CountDownLatch(1)
        val releaseMe = CountDownLatch(1)
        val postArrived = CountDownLatch(1)
        val releasePost = CountDownLatch(1)
        answers["/v1/me"] = {
            meArrived.countDown()
            releaseMe.await(WAIT_S, TimeUnit.SECONDS)
            envelope("null", code = 50001, trace = "trace-BBB222222")
        }
        answers["/v1/me/appeals"] = { request ->
            if (request.method == "POST") {
                postArrived.countDown()
                releasePost.await(WAIT_S, TimeUnit.SECONDS)
                val created = appeal("appeal-new", "processing")
                appeals = listOf(created)
                risk = "appealing"
                envelope(created.toString())
            } else {
                envelope("""{"items":${JsonArray(appeals)},"next_cursor":null}""")
            }
        }
        // 回前台（ON_RESUME）：保留 CONTENT 与可提交表单，再取一次（getMe 挂住）
        open("About")
        idleUntil { top() == "About" }
        composeRule.runOnUiThread { navController.popBackStack() }
        idleUntil { meArrived.count == 0L }
        idleUntil { top() == "Appeal" && has(MeTags.APPEAL_SUBMIT) }
        click(MeTags.APPEAL_SUBMIT)
        idleUntil { postArrived.count == 0L }
        // 刷新先失败：页面进入加载失败
        releaseMe.countDown()
        idleUntil { has(MeTags.APPEAL_LOAD_ERROR) }
        // 提交随后成功：恢复内容态、清掉加载错误、显示返回的申诉单进度
        releasePost.countDown()
        idleUntil { has(MeTags.APPEAL_PROGRESS) && !has(MeTags.APPEAL_LOAD_ERROR) }
        assertFalse("不回到提交表单", has(MeTags.APPEAL_SUBMIT) || has(MeTags.APPEAL_INPUT))
        assertEquals(1, count("POST", "/v1/me/appeals"))
    }

    /** 冷启动时 AppLogin 查 phone_bound 的那次 getMe（APP-05b）已结束：返回此刻的 GET /v1/me 条数，作为本页读取的基数。 */
    private fun loginCheckDone(): Int {
        idleUntil { count("GET", "/v1/me") >= 1 }
        Thread.sleep(LATE_MS)
        settle()
        coldStartCheckSettled()
        return count("GET", "/v1/me")
    }

    /**
     * FU-AND-0716 RC-02：冷启动的 phone_bound 补查已发起、且连同收尾（应答回来后的限流结算、守卫同步）都已结束（AppLogin 补查任务）。原写法只等假服务记下
     * GET /v1/me 再睡固定时长 + 固定轮次：请求计数在生成应答之前就加了，应答或业务续体晚到时，限流 / 页面状态还没结算就断言。[onPoll] 每轮核对前调用
     * （测试用它显式放行挂住的应答）。
     */
    private fun coldStartCheckSettled(onPoll: () -> Unit = {}) =
        FlowWait.until(::settle, { "冷启动补查未结束：started=${login.phoneChecksStarted}；请求 ${lines()}" }) {
            onPoll()
            login.phoneChecksStarted > 0 && login.phoneChecksSettled()
        }

    private fun scrollValue(): Float =
        composeRule
            .onNodeWithTag(MeTags.ME_SCROLL, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
            .value()

    /** 取页面控件当前的点击回调（确认框关闭后仍可重放，用来验证旧闭包作废）。 */
    private fun clickAction(tag: String): () -> Unit {
        val action =
            composeRule
                .onNodeWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .action!!
        return { action() }
    }

    private fun rate(): MockResponse = envelope("null", code = 42901, trace = "trace-000429").setHeader("Retry-After", "60")

    private fun pair(tag: String) =
        """"access_token":"access-$tag","access_expires_at":"2099-01-01T00:00:00+08:00",""" +
            """"refresh_token":"refresh-$tag","refresh_expires_at":"2099-01-01T00:00:00+08:00","session_scope":"full""""

    // ---------------------------------------------------------------- 工具

    private fun fieldText(tag: String): String =
        composeRule
            .onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.EditableText)
            ?.text
            .orEmpty()

    private fun dict(key: String): String =
        MeDraftTexts.text(key).ifEmpty {
            com.couli.app.login.LoginDraftTexts
                .text(key)
        }

    // ---------------------------------------------------------------- FU-AND-0717 F-X-14

    /** 立即带回凭证（state = nonce）的假微信适配器（第三方登录经真实登录页、OAuthLoginService 与 ApiClient）。 */
    private object InstantWechat : com.couli.feature.login.ThirdPartyAuthAdapter {
        override val provider: String = com.couli.feature.login.OAuthProvider.WECHAT

        override fun adapterAvailable(): Boolean = true

        override fun installed(): Boolean? = null

        override suspend fun authorize(nonce: String) =
            com.couli.feature.login.ThirdPartyAuthResult(
                com.couli.feature.login.ThirdPartyAuthKind.CREDENTIAL,
                code = "code-placeholder",
                state = nonce,
            )
    }

    private fun waitFor(
        what: String,
        condition: () -> Boolean,
    ) = com.couli.app.FlowWait
        .until(::settle, { "$what；栈 ${runCatching { stack() }.getOrNull()}，请求 ${lines()}" }, condition = condition)

    private fun tokenPair(
        user: String,
        scope: String,
    ): String =
        "\"access_token\":\"access-$user\",\"access_expires_at\":\"2099-01-01T00:00:00+08:00\",\"refresh_token\":\"refresh-$user\"," +
            "\"refresh_expires_at\":\"2099-01-01T00:00:00+08:00\",\"session_scope\":\"$scope\""

    /**
     * 游客先打开「我的」（预先建好 Tab）→ 点【登录 / 注册】→ 登录页点微信 → 协议确认框【同意并继续】：第三方老用户 [user] 登录，服务端给 [scope]
     * （受限会话复查的刷新也给 [scope]，BR-ID-01）。等登录响应保存、登录页关闭。
     */
    private fun thirdPartyLoginFromMeTab(
        user: String,
        scope: String,
    ) {
        answers["/v1/auth/oauth-attempts"] = {
            envelope("""{"attempt_id":"attempt-1","nonce":"nonce-placeholder-0001","expire_at":"2099-01-01T00:00:00+08:00"}""")
        }
        answers["/v1/auth/login/wechat"] = { envelope("""{"user_id":"$user","is_new_user":false,"tokens":{${tokenPair(user, scope)}}}""") }
        answers["/v1/auth/refresh"] = { envelope("{${tokenPair(user, scope)}}") }
        launch(user = null, adapters = listOf(InstantWechat))
        open("Me")
        waitFor("「我的」游客态") { has(MeTags.ME_GUEST) && has(MeTags.ME_LOGIN) }
        assertEquals("游客不读 getMe", 0, count("GET", "/v1/me"))
        click(MeTags.ME_LOGIN)
        waitFor("登录页第三方入口") { top() == "Login" && has(com.couli.feature.login.LoginTags.OAUTH_PREFIX + "wechat") }
        click(com.couli.feature.login.LoginTags.OAUTH_PREFIX + "wechat")
        val ok =
            com.couli.app.login.LoginDraftTexts
                .text("login.agree_confirm.btn_login")
        waitFor("协议确认框") { text(ok) > 0 }
        clickText(ok)
        waitFor("第三方登录完成、登录页关闭") { count("POST", "/v1/auth/login/wechat") == 1 && tokens.scope == scope && "Login" !in stack() }
    }

    /** 完整状态同步（登录与「我的」两处同步出口，同 AppModule 的会话通知），并等「我的」与登录流程起的任务都跑完。 */
    private fun fullStateSync() {
        val before = meJobs()
        composeRule.runOnUiThread {
            login.syncSession()
            me.syncIdentity()
        }
        meWorkSince(before)
        waitFor("登录流程的 phone_bound 补查已结束") { login.phoneChecksSettled() }
    }

    /**
     * F-X-14（鸿蒙 FU-HM-0721 R1-01 同类）：游客先建好「我的」Tab → 第三方老账号受限登录（deletion_only，复查刷新后仍受限）→ 完整状态同步：
     * Tab 已按新会话重建（会员身份、账号 = 登录账号、执行器绑定新身份并经过 ON_RESUME），但自动同步不发 getMe（零请求、快照仍空）；页面主动读取
     * （下拉 / 【重试】同一入口）仍可发一次（契约允许 deletion_only 读 getMe）。
     */
    @Test
    fun fuand0717_x14_aPrecreatedMeTabDoesNotAutoReadGetMeAfterARestrictedThirdPartyLogin() {
        thirdPartyLoginFromMeTab("user-two", "deletion_only")
        fullStateSync()
        waitFor("「我的」按受限会话重建（会员身份、登录账号、执行器已绑新身份）") {
            has(MeTags.ME_PAGE) && me.identity.value.member && me.identity.value.account == "user-two" &&
                me.deps.tab.bound
                    ?.identity == me.identity.value
        }
        // 再走一次完整同步：会话变化之后的所有自动同步入口都已经过，结论（执行器、快照）都已交回之后才核对「没发」
        fullStateSync()
        val tab = me.deps.tab.bound
        assertNotNull(tab)
        composeRule.runOnUiThread { assertFalse("受限会话下自动同步不读", tab!!.autoRefresh()) }
        assertEquals("deletion_only", tokens.scope)
        val snapshot = me.deps.repo.state.value
        assertEquals("自动同步没有起读取", com.couli.feature.me.MeSnapshot.IDLE, snapshot.phase)
        assertNull("快照没有资料", snapshot.me)
        assertEquals("受限登录后自动同步不请求 getMe：${lines()}", 0, count("GET", "/v1/me"))
        // 页面主动读取不拦
        composeRule.runOnUiThread { assertTrue(tab!!.refresh()) }
        waitFor("主动读取发出一次 getMe 并落地") { count("GET", "/v1/me") == 1 && me.deps.repo.meOf(me.identity.value) != null }
    }

    /** F-X-14 对照：同样先建好 Tab，第三方老用户拿到 full：Tab 的自动同步照常读 getMe（加上登录流程补查 phone_bound 那一次），显示登录账号的资料。 */
    @Test
    fun fuand0717_x14_control_aFullThirdPartyLoginStillAutoLoadsTheMeTab() {
        thirdPartyLoginFromMeTab("user-two", "full")
        fullStateSync()
        waitFor("「我的」按新会话自动读到资料") {
            has(MeTags.ME_PAGE) && me.identity.value.account == "user-two" && me.deps.repo.meOf(me.identity.value) != null
        }
        assertEquals("full", tokens.scope)
        assertTrue("登录流程补查 + Tab 自动同步：${lines()}", count("GET", "/v1/me") >= 2)
    }

    private companion object {
        const val KF_URL = "https://example.test/kf?source=config"
        const val WAIT_S = 10L
        const val POLL_MS = 20L
        const val SETTLE_ROUNDS = 5
        const val LATE_MS = 300L
        const val REPLAY_MS = 1_000L
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 24 * HOUR_MS
    }
}
