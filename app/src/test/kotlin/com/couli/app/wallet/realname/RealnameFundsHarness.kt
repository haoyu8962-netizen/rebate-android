package com.couli.app.wallet.realname

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.FlowLedger
import com.couli.app.FlowWait
import com.couli.app.login.AppLogin
import com.couli.app.privacy.AppPrivacy
import com.couli.app.routing.AppRouting
import com.couli.app.routing.RouteDestination
import com.couli.app.routing.RoutingPrivacyHooks
import com.couli.app.shell.AppShell
import com.couli.app.wallet.AppWallet
import com.couli.app.wallet.visual.WithdrawVisualFixtures
import com.couli.app.walletCacheStore
import com.couli.core.clientcore.AmountMaskStore
import com.couli.core.clientcore.ClientEvent
import com.couli.core.clientcore.ConfigReply
import com.couli.core.clientcore.ConfigStore
import com.couli.core.clipboard.ClipRead
import com.couli.core.clipboard.ClipboardHashStore
import com.couli.core.clipboard.ClipboardWriteLedger
import com.couli.core.clipboard.ClipboardWriter
import com.couli.core.clipboard.SystemClipboard
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiClient
import com.couli.core.network.ClientIdentity
import com.couli.core.network.DeviceCredentials
import com.couli.core.network.InFlightCalls
import com.couli.core.network.MemoryPendingKeyBackend
import com.couli.core.network.MemorySessionTokens
import com.couli.core.network.NetworkContext
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PrivacyRaw
import com.couli.core.network.UpdateRaw
import com.couli.core.privacy.PrivacyGate
import com.couli.core.privacy.PrivacyStore
import com.couli.core.privacy.RetryScheduler
import com.couli.core.router.UpdateCheck
import com.couli.feature.login.LegalVersions
import com.couli.feature.login.PendingInviteCode
import com.couli.feature.wallet.ApiClientWalletApi
import com.couli.feature.wallet.LaborBackend
import com.couli.feature.wallet.RemoteRealNameBackend
import com.couli.feature.wallet.WalletApi
import com.couli.feature.wallet.WalletTags
import java.io.File
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FU-AND-REALNAME-API 测试 v1 公共底座（对应 iOS RealnameFundsTestCase）：真实 AppShell + NavHost + AppRouting + AppPrivacy + AppLogin + AppWallet +
 * ApiClient（签名、版本门槛、会话都走网络层真实管线，只把传输层换成 MockWebServer）。实名后端按生产形状接 [RemoteRealNameBackend]，劳务后端按 Release 形态为 null。
 * 入口走「钱包 → 提现被守卫送进 RealName」（向量 harness）。姓名、身份证号只用向量里的测试值与明显的假值。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class RealnameFundsHarness {
    @get:Rule
    val composeRule = createComposeRule()

    protected val context: Context = ApplicationProvider.getApplicationContext()
    protected var now = OffsetDateTime.parse("2026-11-08T09:45:00+08:00").toInstant().toEpochMilli()
    protected lateinit var server: MockWebServer
    protected lateinit var routing: AppRouting
    protected lateinit var navController: NavHostController
    protected lateinit var login: AppLogin
    protected lateinit var wallet: AppWallet
    protected lateinit var tokens: MemorySessionTokens
    protected lateinit var api: ApiClient
    protected var forcedUpdates = 0
    private val pendingBackend = MemoryPendingKeyBackend()
    private val inFlight = InFlightCalls()
    private val flows = FlowLedger()
    private val system = FakeClipboard()
    private val ledger = ClipboardWriteLedger(store = LedgerStore())
    protected val tracked = mutableListOf<ClientEvent>()
    protected val requests = mutableListOf<Recorded>()

    /** 第 n 次（从 0 起）POST /v1/me/realname 的回答；缺省回 code 0。 */
    @Volatile
    protected var submitReply: (Int) -> MockResponse = { success() }

    /** POST /v1/consents 的回答；缺省 code 0。 */
    @Volatile
    protected var consentReply: () -> MockResponse = { envelope("null") }

    /** 实名提交被接受（code 0）后 GET /v1/me 回 verified。 */
    @Volatile
    protected var verifiedOnServer = false

    /** GET /v1/me 的覆盖回答（复查失败等）；缺省由 [verifiedOnServer] 决定。 */
    @Volatile
    protected var meReply: (() -> MockResponse)? = null

    /** GET /v1/withdrawals/rules 的回答。 */
    @Volatile
    protected var rulesReply: () -> MockResponse = { envelope(RULES_OK) }

    /** 非 null 时 POST /v1/auth/refresh 卡在这把闩上（测试释放后才回新令牌）：用来把调用停在网络层的恢复等待里。 */
    @Volatile
    protected var refreshHold: CountDownLatch? = null

    /** 其他路径的覆盖回答（路径不含查询串，不回答的走默认）。 */
    protected val answers = mutableMapOf<String, (RecordedRequest) -> MockResponse>()

    class Recorded(
        val method: String,
        val path: String,
        val body: String,
        val headers: Map<String, String>,
        val query: String?,
    ) {
        fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

        fun json(): JsonObject = Json.parseToJsonElement(body).jsonObject

        fun text(name: String): String = json().getValue(name).jsonPrimitive.content
    }

    private class FakeClipboard : SystemClipboard {
        var content: String? = null

        override fun read(): ClipRead = content?.let { ClipRead.Text(it) } ?: ClipRead.Unavailable

        override fun write(
            text: String,
            label: String,
        ): Boolean {
            content = text
            return true
        }
    }

    private class LedgerStore : ClipboardHashStore {
        private val values = mutableMapOf<String, String>()

        override fun read(key: String): String? = values[key]

        override fun write(
            key: String,
            value: String?,
        ): Boolean {
            if (value == null) values.remove(key) else values[key] = value
            return true
        }
    }

    private class MemoryStore : PrivacyStore {
        override var decisionRaw: String = "consented"
        override var consentedVersion: String? = CONSENTED_VERSION.toString()
        override var consentedAtMillis: Long? = 0L
        override var basicMode: Boolean = false
    }

    private object FixtureDevice : DeviceCredentials {
        override val deviceId: String = "device-fixture"
        override val installSecret: String = "install-fixture"
    }

    @Before
    fun startServer() {
        listOf("couli_amount_mask", "couli_wallet_cache_pending").forEach {
            context
                .getSharedPreferences(it, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
        runCatching { walletCacheStore(context).clearAll() }
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                @Suppress("ReturnCount") // 按路径逐项回答
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val full = request.path.orEmpty()
                    val path = full.substringBefore('?')
                    val query = full.substringAfter('?', "").ifEmpty { null }
                    val recorded = Recorded(request.method.orEmpty(), path, request.body.readUtf8(), request.headers.toMap(), query)
                    val index = synchronized(requests) { requests.count { it.method == "POST" && it.path == SUBMIT_PATH } }
                    synchronized(requests) { requests += recorded }
                    if (recorded.method == "POST" && path == SUBMIT_PATH) {
                        val reply = submitReply(index)
                        val replied = reply.getBody()?.readUtf8().orEmpty()
                        if (replied.contains("\"code\":0,")) verifiedOnServer = true
                        return reply
                    }
                    if (recorded.method == "POST" && path == CONSENT_PATH) return consentReply()
                    if (recorded.method == "GET" && path == ME_PATH) return meReply?.invoke() ?: meAnswer()
                    if (path == REFRESH_PATH) {
                        refreshHold?.await(REFRESH_HOLD_SECONDS, TimeUnit.SECONDS)
                        return envelope(REFRESHED_TOKENS)
                    }
                    if (path == RULES_PATH) return rulesReply()
                    if (path == SUMMARY_PATH) return envelope(SUMMARY)
                    synchronized(answers) { answers[path] }?.let { return it(request) }
                    return envelope("null")
                }
            }
        server.start()
    }

    @After
    fun stopServer() {
        refreshHold?.countDown()
        server.shutdown()
    }

    protected fun meAnswer(): MockResponse =
        envelope("""{"user_id":"user-one","phone_bound":true,"realname_status":"${if (verifiedOnServer) "verified" else "none"}"}""")

    protected fun success(masked: String = "**甲"): MockResponse = envelope("""{"realname_status":"verified","masked_name":"$masked"}""")

    protected fun envelope(
        data: String,
        code: Int = 0,
        trace: String = TRACE,
    ): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("""{"code":$code,"msg":"","data":$data,"trace_id":"$trace"}""")

    protected fun disconnect(): MockResponse = MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)

    /** 把向量的一条 reply 译成假服务回答。 */
    protected fun replyOf(reply: JsonObject): MockResponse {
        if (reply["transport"]?.jsonPrimitive?.content == "disconnect") return disconnect()
        val rawCode = reply.getValue("code").jsonPrimitive.content
        val code = rawCode.toInt()
        val data = reply["data"]?.takeIf { it !is JsonNull }?.toString() ?: "null"
        val response = envelope(data, code = code)
        (reply["headers"] as? JsonObject)?.forEach { (name, value) -> response.setHeader(name, value.jsonPrimitive.content) }
        return response
    }

    // ---------------------------------------------------------------- 搭建（与 AppModule 同一套接线）

    /** 生产形状的 [wallet]：实名后端是真实实现，劳务后端 null（Release 形态）。[config] 里带 legal.privacy.version。 */
    @Suppress("LongMethod") // 与 AppModule 同一套接线逐项搭建
    protected fun launch(
        config: String = CONFIG,
        labor: LaborBackend? = null,
        consentedVersion: () -> Int? = { CONSENTED_VERSION },
    ) {
        val gate =
            PrivacyGate(MemoryStore(), clock = { now }, bundledPolicyVersion = "1").apply {
                withdrawalCleanup = { inFlight.onPrivacyRevoked() }
                retryScheduler = RetryScheduler { _, _ -> }
            }
        val privacy = AppPrivacy(gate, environmentSettled = { true }, clock = { now }).also { it.coldStart() }
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
        api = apiClient()
        val store =
            ConfigStore(
                readFile = { null },
                writeFile = {},
                fetcher = { _, done -> done(ConfigReply(ConfigReply.OK, config, null)) },
                clock = { now },
                bundled = config,
            )
        val walletApi: WalletApi = flows.wallet(ApiClientWalletApi(api))
        composeRule.runOnUiThread {
            login =
                AppLogin(
                    api,
                    tokens,
                    routing,
                    updateCheck = { UpdateCheck.OK },
                    markBelow = {},
                    legal = LegalVersions(CONSENTED_VERSION, CONSENTED_VERSION),
                    pendingInvite = PendingInviteCode(),
                )
            login.syncSession()
            wallet =
                AppWallet(
                    api = walletApi,
                    store = store,
                    routing = routing,
                    sessionEpoch = { tokens.loginEpoch },
                    account = { api.activeUserId() },
                    writer = { text -> ClipboardWriter(system, ledger).write(text) },
                    forceUpdate = { forcedUpdates += 1 },
                    cache = walletCacheStore(context),
                    mask = AmountMaskStore(context),
                    realname =
                        RemoteRealNameBackend(
                            api = walletApi,
                            configData = { store.snapshots.value?.data },
                            consentedVersion = consentedVersion,
                        ),
                    labor = labor,
                    stepUpPhone = { "13800005678" },
                    track = { synchronized(tracked) { tracked += it } },
                    clock = { now },
                    sessionScope = { api.activeSessionScope() ?: "full" },
                ).also { it.connectSession() }
        }
        composeRule.setContent {
            navController = rememberNavController()
            AppShell(
                routing = routing,
                privacy = privacy,
                homeContent = { Box(Modifier.fillMaxSize()) },
                navController = navController,
                login = login,
                wallet = wallet,
            )
        }
        composeRule.waitForIdle()
        idleUntil { meGets() >= 2 && login.phoneChecksStarted > 0 && login.phoneChecksSettled() }
    }

    private fun apiClient(): ApiClient =
        ApiClient(
            identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.0.0", build = "1", channel = "official"),
            baseUrl = { server.url("/").toString() },
            context = { NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, UpdateRaw.SUPPORTED) },
            device = FixtureDevice,
            tokens = tokens,
            pendingKeys = PendingKeyStore(pendingBackend) { now },
            inFlight = inFlight,
            sleeper = {},
        )

    // ---------------------------------------------------------------- 入口与操作

    protected fun open(route: String) {
        composeRule.runOnUiThread { wallet.open(route, JsonObject(emptyMap())) }
        settle()
    }

    /** 向量 harness：钱包 → 提现，被守卫送进 RealName。 */
    protected fun enterRealNameFromWallet() {
        open("Wallet")
        idleUntil { has(WalletTags.WALLET_WITHDRAW) }
        click(WalletTags.WALLET_WITHDRAW)
        idleUntil { has(WalletTags.REALNAME_NAME) }
        assertTrue("守卫把未实名用户送进 RealName", top()?.name == "RealName")
    }

    /** 填 request.normalize[0] 的输入值（首尾空白、小写 x 原样输入）。 */
    protected fun fillVectorForm() {
        input(WalletTags.REALNAME_NAME, typedName)
        input(WalletTags.REALNAME_ID, typedId)
    }

    protected fun fillForm(
        name: String,
        idNo: String,
    ) {
        input(WalletTags.REALNAME_NAME, name)
        input(WalletTags.REALNAME_ID, idNo)
    }

    /** 【提交认证】→ 勾选 → 【同意并继续】，等第 [expected] 次实名提交发出（consent 成功后）。 */
    protected fun submitAndAgree(expected: Int = submits().size + 1) {
        val beforeConsents = consents().size
        click(WalletTags.REALNAME_SUBMIT)
        idleUntil { has(WalletTags.CONSENT_SHEET) }
        toggleAndAgree()
        // 同意记录先发（不判定次数以外的内容），之后等实名提交
        idleUntil(SEND_CEILING_MS) { consents().size > beforeConsents || submits().size >= expected }
        idleUntil(SEND_CEILING_MS) { submits().size >= expected }
    }

    protected fun toggleAndAgree() {
        click(WalletTags.CONSENT_CHECK)
        click(WalletTags.CONSENT_OK)
    }

    /** 表单不变再提交（有半屏就勾选同意；实现沿用同意则只等下一次提交发出）。 */
    protected fun resubmit() {
        val expected = submits().size + 1
        idleUntil { submitEnabled() || has(WalletTags.CONSENT_SHEET) }
        if (!has(WalletTags.CONSENT_SHEET)) {
            click(WalletTags.REALNAME_SUBMIT)
            idleUntil { has(WalletTags.CONSENT_SHEET) || submits().size >= expected }
        }
        if (has(WalletTags.CONSENT_SHEET)) toggleAndAgree()
        idleUntil(SEND_CEILING_MS) { submits().size >= expected }
    }

    protected fun submitEnabled(): Boolean =
        has(WalletTags.REALNAME_SUBMIT) &&
            composeRule
                .onNodeWithTag(WalletTags.REALNAME_SUBMIT, useUnmergedTree = true)
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.Disabled) == null

    protected fun nameFieldEnabled(): Boolean =
        composeRule
            .onNodeWithTag(WalletTags.REALNAME_NAME, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.Disabled) == null

    /** 观察窗：实际时间与（Robolectric）主线程时间都前进 [millis]，服务端、IO 线程、动画照常运行（向量「1 秒观察窗」、40901 退避）。 */
    protected fun observe(millis: Long) {
        var left = millis
        while (left > 0) {
            now += OBSERVE_STEP_MS
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(OBSERVE_STEP_MS))
            composeRule.waitForIdle()
            Thread.sleep(OBSERVE_STEP_MS)
            left -= OBSERVE_STEP_MS
        }
        settle()
    }

    protected fun settle() {
        repeat(SETTLE_ROUNDS) {
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.waitForIdle()
            Thread.sleep(POLL_MS)
        }
    }

    protected fun pump() {
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()
    }

    /** 等 [operationId] 此刻已发起的钱包调用全部结束（含 ApiClient 内部的刷新后重放）并交回调用方。 */
    protected fun awaitCallsSettled(operationId: String) {
        val matches = { call: ApiCall -> call.operationId == operationId }
        FlowWait.settled(flows, flows.started(matches), ::pump, { "请求 ${requestLines()}" }, matches)
        settle()
    }

    /** 等 GET /v1/me 的次数稳定（登录校验与实名查询都发 getMe，不走同一本账）。 */
    protected fun stableMeGets(): Int {
        var last = meGets()
        var quiet = 0
        idleUntil {
            Thread.sleep(STABLE_POLL_MS)
            val current = meGets()
            if (current == last) quiet += 1 else quiet = 0
            last = current
            quiet >= STABLE_ROUNDS
        }
        return last
    }

    protected fun idleUntil(
        ceilingMs: Long = WAIT_CEILING_MS,
        condition: () -> Boolean,
    ) {
        FlowWait.until(::pump, { "栈顶 ${runCatching { stack() }.getOrNull()}，请求 ${requestLines()}" }, ceilingMs, condition)
    }

    // ---------------------------------------------------------------- 观察

    private fun requestLines(): List<String> = synchronized(requests) { requests.map { it.method + " " + it.path } }

    protected fun has(tag: String): Boolean = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    protected fun texts(substring: String): Int =
        composeRule.onAllNodesWithText(substring, substring = true, useUnmergedTree = true).fetchSemanticsNodes().size

    protected fun click(tag: String) {
        val node = composeRule.onNodeWithTag(tag, useUnmergedTree = true)
        runCatching { node.performScrollTo() }
        node.performClick()
        settle()
    }

    protected fun input(
        tag: String,
        text: String,
    ) {
        composeRule.onNodeWithTag(tag, useUnmergedTree = true).performTextReplacement(text)
        settle()
    }

    protected fun fieldText(tag: String): String =
        composeRule
            .onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.EditableText)
            ?.text
            .orEmpty()

    /** 错误条整句文案；没有错误条为 null。 */
    protected fun errorBar(): String? {
        if (!has(WalletTags.REALNAME_ERROR)) return null
        return composeRule
            .onAllNodes(hasAnyAncestor(hasTestTag(WalletTags.REALNAME_ERROR)), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
            .joinToString("") { it.text }
    }

    protected fun recorded(
        method: String,
        path: String,
    ): List<Recorded> = synchronized(requests) { requests.filter { it.method == method && it.path == path } }

    protected fun submits(): List<Recorded> = recorded("POST", SUBMIT_PATH)

    protected fun consents(): List<Recorded> = recorded("POST", CONSENT_PATH)

    protected fun meGets(): Int = recorded("GET", ME_PATH).size

    @SuppressLint("RestrictedApi")
    protected fun top(): RouteDestination? =
        navController.currentBackStack.value
            .lastOrNull { it.destination.hasRoute(RouteDestination::class) }
            ?.toRoute<RouteDestination>()

    @SuppressLint("RestrictedApi")
    protected fun stack(): List<String> =
        navController.currentBackStack.value
            .filter { it.destination.hasRoute(RouteDestination::class) }
            .map { it.toRoute<RouteDestination>().name }

    protected fun switchSession(user: String?) {
        composeRule.runOnUiThread {
            if (user == null) tokens.clear() else tokens.save("access-$user", "refresh-$user", "full", user)
            login.syncSession()
            wallet.onSessionChanged()
        }
        settle()
    }

    protected fun realnameVerified(): Boolean {
        var value = false
        composeRule.runOnUiThread { value = wallet.realnameVerified() }
        return value
    }

    /** 键格式（向量 request.submit.idempotencyKeyPattern）。 */
    protected fun assertKeyShape(key: String?) {
        assertTrue("幂等键存在且符合契约格式", key != null && KEY_PATTERN.matches(key))
    }

    /** 字典原文（不带变量）。 */
    protected fun dict(key: String): String = wallet.deps.texts.text(key)

    companion object {
        val vectorsFile: File get() = File(WithdrawVisualFixtures.root, "specs/realname-submit-vectors.json")
        val vectors: JsonObject by lazy { Json.parseToJsonElement(vectorsFile.readText()).jsonObject }
        val cases: List<JsonObject> by lazy { vectors.getValue("cases").jsonArray.map { it.jsonObject } }
        private val normalize: JsonObject by lazy {
            val request = vectors.getValue("request").jsonObject
            request.getValue("normalize").jsonArray[0].jsonObject
        }
        val typedName: String get() = normalize.getValue("typedName").jsonPrimitive.content
        val typedId: String get() = normalize.getValue("typedId").jsonPrimitive.content
        val normalizedName: String get() = normalize.getValue("name").jsonPrimitive.content
        val normalizedId: String get() = normalize.getValue("id_no").jsonPrimitive.content
        val KEY_PATTERN = Regex("^[A-Za-z0-9_-]{8,64}$")

        const val SUBMIT_PATH = "/v1/me/realname"
        const val CONSENT_PATH = "/v1/consents"
        const val ME_PATH = "/v1/me"
        const val SUMMARY_PATH = "/v1/wallet/summary"
        const val RULES_PATH = "/v1/withdrawals/rules"
        const val WITHDRAW_PATH = "/v1/withdrawals"
        const val PAYOUT_PATH = "/v1/me/payout-account"
        const val STEP_UP_PATH = "/v1/auth/step-up"
        const val SMS_PATH = "/v1/auth/sms-codes"
        const val ABANDON_PATH = "/v1/idempotency-keys/abandon"
        const val REFRESH_PATH = "/v1/auth/refresh"
        const val REFRESH_HOLD_SECONDS = 20L
        const val STABLE_POLL_MS = 100L
        const val STABLE_ROUNDS = 5
        const val REFRESHED_TOKENS =
            """{"access_token":"access-two","access_expires_at":"2026-11-08T13:30:00+08:00","refresh_token":"refresh-two",""" +
                """"refresh_expires_at":"2026-12-08T11:30:00+08:00","session_scope":"full"}"""
        const val TRACE = "trace-zz9876"
        const val POLL_MS = 10L

        /** 等界面结论的上限（同 FlowWait.CEILING_MS，高负载下冷启动要留余量）。 */
        const val WAIT_CEILING_MS = 30_000L

        /** 点击后等请求发出的上限（发不出来就判红）。 */
        const val SEND_CEILING_MS = 15_000L
        const val SETTLE_ROUNDS = 5
        const val OBSERVE_STEP_MS = 50L
        const val OBSERVATION_WINDOW_MS = 1_000L

        /** 配置里的隐私政策版本（/v1/config.legal.privacy.version，D10-887 ①）；与本机已同意版本 [CONSENTED_VERSION] 不同，用来区分取值来源。 */
        const val CONFIG_PRIVACY_VERSION = 7

        /** 本机已同意的隐私政策版本（测试里 PrivacyGate 的 consentedVersion）。 */
        const val CONSENTED_VERSION = 1

        const val CONFIG =
            """{"features":{"flags":{},"platform_status":{"taobao":"on"}},"legal":{"privacy":{"version":$CONFIG_PRIVACY_VERSION},""" +
                """"agreement":{"version":3}}}"""
        const val CONFIG_NO_LEGAL = """{"features":{"flags":{},"platform_status":{"taobao":"on"}}}"""
        const val SUMMARY =
            """{"available_fen":1000,"withdrawable_fen":1000,"negative_fen":0,"frozen_fen":200,"estimated_total_fen":1019,""" +
                """"estimated_fen":269,"pending_credit_fen":750,"pending_credit_paused_fen":450,"next_credit_period":"2026-11",""" +
                """"credit_overdue":false,"withdrawn_fen":0,"risk_paused_reason":null,"withdraw_enabled":true}"""
        const val RULES_OK =
            """{"can_withdraw":true,"block_code":null,"block_reason":null,"max_withdrawable_fen":1200,"withdrawable_fen":1234,""" +
                """"daily_remaining":1,"monthly_remaining":10,"est_fee_fen":null,"est_tax_fen":null,"est_net_fen":null,""" +
                """"quick_amounts_fen":[500,1000]}"""

        /** 劳务协议 / 打款未就绪：rules 回 can_withdraw=false、block_code=10004（D10-1002 前提：Release 形态）。 */
        const val RULES_BLOCKED_LABOR =
            """{"can_withdraw":false,"block_code":10004,"block_reason":null,"max_withdrawable_fen":0,"withdrawable_fen":1234,""" +
                """"daily_remaining":1,"monthly_remaining":10,"est_fee_fen":null,"est_tax_fen":null,"est_net_fen":null,""" +
                """"quick_amounts_fen":[]}"""

        /** 改表单用的值（明显的假值，不是真实号段）。 */
        const val EDITED_NAME = "测试实名乙"
        const val EDITED_ID = "00000000000000009X"
    }
}
