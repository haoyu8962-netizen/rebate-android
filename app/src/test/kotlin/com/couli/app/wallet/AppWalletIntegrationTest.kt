package com.couli.app.wallet

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.lifecycle.SavedStateHandle
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
import com.couli.core.network.ApiResult
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
import com.couli.core.router.RouteRequest
import com.couli.core.router.RouteSource
import com.couli.core.router.UpdateCheck
import com.couli.core.wallet.WalletStaleCache
import com.couli.feature.login.LegalVersions
import com.couli.feature.wallet.ApiClientWalletApi
import com.couli.feature.wallet.LaborBackend
import com.couli.feature.wallet.RealNameBackend
import com.couli.feature.wallet.WalletApi
import com.couli.feature.wallet.WalletTags
import com.couli.feature.wallet.WithdrawViewModel
import java.net.URLDecoder
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 钱包类页面的本地假服务集成测试（APP-09 brief §2.16）：真实 AppShell + NavHost + AppRouting + AppPrivacy + AppLogin + AppWallet + ApiClient（签名、
 * step-up、幂等、未决键都走网络层真实管线，只把传输层换成 MockWebServer），数据照设计稿 sample-data §12。页面经 Router 打开、经页面控件操作，
 * 不直接调执行器的内部函数。测试里的姓名、身份证号、卡号、登录号都是明显的测试值。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("LargeClass", "TooManyFunctions") // 验收场景逐条一个用例
class AppWalletIntegrationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = OffsetDateTime.parse("2026-10-12T09:45:00+08:00").toInstant().toEpochMilli()
    private lateinit var server: MockWebServer
    private lateinit var routing: AppRouting
    private lateinit var navController: NavHostController
    private lateinit var login: AppLogin
    private lateinit var wallet: AppWallet
    private lateinit var tokens: MemorySessionTokens
    private lateinit var mask: AmountMaskStore
    private val pendingBackend = MemoryPendingKeyBackend()
    private val inFlight = InFlightCalls()

    /** 钱包端口上每次调用连同调用方回调处理的结算记录（F-AND-36 R1-02：迟到响应的核对等它，不看网络层空闲）。 */
    private val flows = FlowLedger()
    private val system = FakeClipboard()
    private val ledger = ClipboardWriteLedger(store = LedgerStore())
    private val tracked = mutableListOf<ClientEvent>()
    private var forcedUpdates = 0
    private val requests = mutableListOf<Recorded>()

    /** 经 LocalCouliAnnouncer 的一次性播报。 */
    private val announced = mutableListOf<String>()

    private fun announcedCount(text: String): Int = synchronized(announced) { announced.count { it == text } }

    /** 路径（不含查询串）→ 回答；没有登记的按默认样例回答。 */
    private val answers = mutableMapOf<String, (RecordedRequest) -> MockResponse>()

    /** 假服务的状态（sample-data §12）。 */
    @Volatile private var summary = SUMMARY

    @Volatile private var payoutBound = true

    @Volatile private var laborSigned = true

    private class Recorded(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val body: String,
        val headers: Map<String, String>,
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
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val full = request.path.orEmpty()
                    val path = full.substringBefore('?')
                    val query =
                        full.substringAfter('?', "").split('&').filter { it.contains('=') }.associate {
                            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
                        }
                    val recorded = Recorded(request.method.orEmpty(), path, query, request.body.readUtf8(), request.headers.toMap())
                    synchronized(requests) { requests += recorded }
                    val handler = synchronized(answers) { answers[path] }
                    return handler?.invoke(request) ?: defaultAnswer(recorded)
                }
            }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    // ---------------------------------------------------------------- 搭建（与 AppModule 同一套接线）

    @Suppress("LongMethod") // 与 AppModule 同一套接线逐项搭建
    private fun launch(
        config: String = CONFIG,
        realname: RealNameBackend? = FakeRealName(),
        labor: LaborBackend? = FakeLabor(),
        phone: (String) -> String? = { "13800005678" },
        wrapApi: (WalletApi) -> WalletApi = { it },
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
        val api = apiClient()
        val store =
            ConfigStore(
                readFile = { null },
                writeFile = {},
                fetcher = { _, done -> done(ConfigReply(ConfigReply.OK, config, null)) },
                clock = { now },
                bundled = config,
            )
        mask = AmountMaskStore(context)
        composeRule.runOnUiThread {
            login = AppLogin(api, tokens, routing, updateCheck = { UpdateCheck.OK }, markBelow = {}, legal = LegalVersions(1, 1))
            login.syncSession()
            wallet =
                AppWallet(
                    api = flows.wallet(wrapApi(ApiClientWalletApi(api))),
                    store = store,
                    routing = routing,
                    sessionEpoch = { tokens.loginEpoch },
                    account = { api.activeUserId() },
                    writer = { text -> ClipboardWriter(system, ledger).write(text) },
                    forceUpdate = { forcedUpdates += 1 },
                    cache = walletCacheStore(context),
                    mask = mask,
                    realname = realname,
                    labor = labor,
                    stepUpPhone = phone,
                    track = { synchronized(tracked) { tracked += it } },
                    clock = { now },
                ).also { it.connectSession() }
        }
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
                    homeContent = { Box(Modifier.fillMaxSize()) },
                    navController = navController,
                    login = login,
                    wallet = wallet,
                )
            }
        }
        composeRule.waitForIdle()
        // 冷启动查清 phone_bound 与 realname_status（AppLogin、AppWallet 各取一次 /v1/me）。FU-AND-0716 窄修二：两次都要等到——原来只等 AppWallet 那次，
        // AppLogin 那次（补查任务）晚到时会话还停在 LOGGED_IN，随后打开手机号级页面被守卫拦下（延迟注入下栈为空）
        idleUntil { wallet.realnameVerified() && login.phoneChecksStarted > 0 && login.phoneChecksSettled() }
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

    private fun open(
        route: String,
        params: Map<String, String> = emptyMap(),
    ) {
        composeRule.runOnUiThread { wallet.open(route, JsonObject(params.mapValues { JsonPrimitive(it.value) })) }
        settle()
    }

    private fun settle() {
        repeat(SETTLE_ROUNDS) {
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.waitForIdle()
            Thread.sleep(POLL_MS)
        }
    }

    /** 推进主线程时钟（估算的 0.5 秒停顿、倒计时）。 */
    private fun advance(millis: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
        settle()
    }

    /** 字段错误的移焦点在组合的协程里等一小段（测试时钟）。 */
    private fun focusWait() {
        composeRule.mainClock.advanceTimeBy(FOCUS_WAIT_MS)
        settle()
    }

    private fun pump() {
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()
    }

    /** F-AND-36：等流程交回结论（原为最多 8 秒的轮询，负载 >50 时冷启动页面等待超时），见 [FlowWait]；超时打印页面树与现场。 */
    private fun idleUntil(condition: () -> Boolean) {
        try {
            FlowWait.until(::pump, { "栈顶 ${runCatching { stack() }.getOrNull()}，请求 ${requestLines()}" }, condition = condition)
        } catch (timeout: AssertionError) {
            val tree = runCatching { composeRule.onRoot(useUnmergedTree = true).printToString(maxDepth = Int.MAX_VALUE) }.getOrNull()
            println("DEBUG-TREE\n$tree")
            throw timeout
        }
    }

    private fun isMe(call: ApiCall): Boolean = call.operationId == GET_ME_OPERATION

    /**
     * 迟到响应的核对之前：等满足 [matches] 的钱包调用至少 [count] 次连同调用方的回调处理都已结束（[FlowLedger]，F-AND-36 R1-02），代替原来的
     * 「睡 LATE_MS + 300 毫秒」与 r0 的「网络层空闲」——高负载下迟到的那次可能晚于固定时长才到；网络层注销在途记录时业务回调可能还没执行。
     */
    private fun lateResponsesSettled(
        count: Int,
        matches: (ApiCall) -> Boolean,
    ) {
        FlowWait.settled(flows, count, ::pump, { "请求 ${requestLines()}" }, matches)
        settle()
    }

    private fun isRules(call: ApiCall): Boolean = call.operationId == RULES_OPERATION

    /**
     * FU-AND-0716 R3-01 残留：负向核对（「不发请求」「不显示」「不跳转」）之前，等此刻已发起的钱包调用全部连同调用方的回调都已结算
     * （[FlowLedger]），代替原来「固定轮次空转后直接断言」——在途的估算、返回页面时的重读迟于固定轮次结算时，原写法会在它们处理之前就核对。
     */
    private fun walletCallsSettled() {
        val target = flows.started { true }
        FlowWait.settled(flows, target, ::pump, { "请求 ${requestLines()}" }) { true }
        settle()
    }

    /**
     * FU-AND-0716 R3-01：提交结算（onSettled）之后的 rules 读取失败落地之前不断言。先等 [startedBefore] 之后至少又发起了一次 rules，再等此刻已发起的
     * rules 全部连同提现页的业务回调结算（[FlowLedger]），最后等读取失败的页内提示出现。[onPoll] 在每轮核对结算前调用（测试用它显式放行挂住的响应）。
     */
    private fun followUpRulesFailureSettled(
        startedBefore: Int,
        onPoll: () -> Unit = {},
    ) {
        rulesSettledSince(startedBefore, onPoll)
        idleUntil { has(WalletTags.WITHDRAW_READ_ERROR) }
    }

    /** [startedBefore] 之后至少又发起了一次 rules，且此刻已发起的 rules 都已连同提现页的业务回调结算（[FlowLedger]）。 */
    private fun rulesSettledSince(
        startedBefore: Int,
        onPoll: () -> Unit = {},
    ) {
        idleUntil { flows.started(::isRules) > startedBefore }
        val target = flows.started(::isRules)
        FlowWait.until(::pump, { "rules 已结算 ${flows.settled(::isRules)}/$target；请求 ${requestLines()}" }) {
            onPoll()
            flows.settled(::isRules) >= target
        }
    }

    private fun isSubmit(call: ApiCall): Boolean = call.operationId == WITHDRAW_OPERATION

    /**
     * FU-AND-0716 RC-01：再次提交得到 20001 之后、推进焦点 / 播报等待之前：先等 [startedBefore] 之后又发起了一次提交，再等此刻已发起的提交全部连同
     * 提现页的业务回调结算（[FlowLedger]：FIELD_ERROR 分支在这条回调里换新字段错误事件），最后等字段错误提示重新出现在页面上（提交时已清掉）。
     * [onPoll] 在每轮核对结算前调用（测试用它显式放行挂住的响应）。
     */
    private fun secondFieldErrorLanded(
        startedBefore: Int,
        onPoll: () -> Unit = {},
    ) {
        idleUntil { flows.started(::isSubmit) > startedBefore }
        val target = flows.started(::isSubmit)
        FlowWait.until(::pump, { "提交已结算 ${flows.settled(::isSubmit)}/$target；请求 ${requestLines()}" }) {
            onPoll()
            flows.settled(::isSubmit) >= target
        }
        idleUntil { has(WalletTags.WITHDRAW_FIELD_ERROR) }
    }

    private fun requestLines(): List<String> = synchronized(requests) { requests.map { it.method + " " + it.path } }

    private fun has(tag: String): Boolean = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun spoken(substring: String): Int =
        composeRule
            .onAllNodes(
                androidx.compose.ui.test
                    .hasContentDescription(substring, substring = true),
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
            .size

    private fun texts(substring: String): Int =
        composeRule.onAllNodesWithText(substring, substring = true, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun click(tag: String) {
        val node = composeRule.onNodeWithTag(tag, useUnmergedTree = true)
        // 在可滚动内容里的先滚到可见；底部固定操作区里的直接点
        runCatching { node.performScrollTo() }
        node.performClick()
        settle()
    }

    private fun input(
        tag: String,
        text: String,
    ) {
        composeRule.onNodeWithTag(tag, useUnmergedTree = true).performTextReplacement(text)
        settle()
    }

    private fun fieldText(tag: String): String =
        composeRule
            .onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.EditableText)
            ?.text
            .orEmpty()

    private fun count(
        method: String,
        path: String,
    ): Int = synchronized(requests) { requests.count { it.method == method && it.path == path } }

    private fun last(
        method: String,
        path: String,
    ): Recorded? = synchronized(requests) { requests.lastOrNull { it.method == method && it.path == path } }

    private fun all(
        method: String,
        path: String,
    ): List<Recorded> = synchronized(requests) { requests.filter { it.method == method && it.path == path } }

    @SuppressLint("RestrictedApi")
    private fun top(): RouteDestination? =
        navController.currentBackStack.value
            .lastOrNull { it.destination.hasRoute(RouteDestination::class) }
            ?.toRoute<RouteDestination>()

    @SuppressLint("RestrictedApi")
    private fun stack(): List<String> =
        navController.currentBackStack.value
            .filter { it.destination.hasRoute(RouteDestination::class) }
            .map { it.toRoute<RouteDestination>().name }

    private fun openWithdraw() {
        open("Withdraw")
        idleUntil { has(WalletTags.WITHDRAW_SUBMIT) }
    }

    /** 输入金额并等过 0.5 秒停顿，估算回来。 */
    private fun typeAmount(text: String) {
        input(WalletTags.WITHDRAW_INPUT, text)
        advance(ESTIMATE_WAIT_MS)
        idleUntil { has(WalletTags.WITHDRAW_ESTIMATE) || !has(WalletTags.WITHDRAW_CALCULATING) }
    }

    /**
     * FU-AND-0716 窄修二：二次验证面板已出现且这次发码已有结论（离开 SENDING，「正在发送验证码…」不在）。面板一出现就处于 SENDING，发码请求与应答都在后台；
     * 只等面板出现就核对发码请求、「已发送」文案，或输码后点【验证并提交】（canVerify 要求 READY，SENDING 时点击不受理），发码晚到时会提前核对或白点。
     */
    private fun stepUpReady(): Boolean = has(WalletTags.STEP_UP_CODE) && texts(STEP_UP_SENDING_TEXT) == 0

    /** 走完二次验证面板（输入验证码、点【验证并提交】）。 */
    private fun passStepUp(code: String = "123456") {
        idleUntil { stepUpReady() }
        input(WalletTags.STEP_UP_CODE, code)
        click(WalletTags.STEP_UP_SUBMIT)
    }

    private fun body(recorded: Recorded?): JsonObject = Json.parseToJsonElement(recorded!!.body).jsonObject

    private fun header(
        recorded: Recorded?,
        name: String,
    ): String? =
        recorded
            ?.headers
            ?.entries
            ?.firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value

    // ---------------------------------------------------------------- AC-S2-33 钱包

    @Test
    fun ac_s2_33_walletShowsServerFieldsWithThreeSeparateProgressItems() {
        summary = SUMMARY.replace("\"estimated_total_fen\":1019", "\"estimated_total_fen\":1019")
        launch()
        open("Wallet")
        idleUntil { has(WalletTags.WALLET_HERO) }
        assertEquals(1, count("GET", SUMMARY_PATH))
        assertTrue(spoken("可提现余额，10 元") > 0)
        assertTrue("冻结中 ¥2", spoken("冻结中，2 元") > 0)
        assertTrue("已提现 ¥0", spoken("已提现，0 元") > 0)
        assertTrue("预估收益只取 estimated_total_fen", spoken("预估收益，10.19 元") > 0)
        assertTrue("其中已收货", texts("其中已收货 ¥7.5") > 0)
        assertTrue("最早预计", texts("最早预计 11 月结算") > 0)
        assertTrue("暂缓入账", texts("其中 ¥4.5 暂缓入账") > 0)
        assertEquals("不出现「待入账」", 0, texts("待入账"))
        assertEquals("不出现「累计」", 0, texts("累计"))
        composeRule.onNodeWithTag(WalletTags.WALLET_WITHDRAW, useUnmergedTree = true).assertIsEnabled()
        // 抓包：改 estimated_fen / pending_credit_fen 不影响「预估收益」（客户端不相加）
        summary =
            SUMMARY
                .replace(
                    "\"estimated_fen\":269",
                    "\"estimated_fen\":1",
                ).replace("\"pending_credit_fen\":750", "\"pending_credit_fen\":1")
        composeRule.runOnUiThread { routing.router.open(RouteRequest("Home", JsonObject(emptyMap()), RouteSource.IN_APP)) }
        settle()
        open("Wallet")
        idleUntil { texts("其中已收货 ¥0.01") > 0 }
        assertTrue(spoken("预估收益，10.19 元") > 0)
    }

    @Test
    fun ac_s2_33_bcWithdrawButtonDisabledByNegativeAndFeatureOffButNotByRisk() {
        summary = SUMMARY.replace("\"risk_paused_reason\":null", "\"risk_paused_reason\":\"fixture_unlisted_reason\"")
        // 风控冻结时 rules 给 can_withdraw=false、block_code=30303（account_frozen）
        synchronized(answers) {
            answers[RULES_PATH] = {
                envelope(
                    """{"can_withdraw":false,"block_code":30303,"block_reason":"account_frozen","max_withdrawable_fen":0,""" +
                        """"withdrawable_fen":1000,"daily_remaining":1,"monthly_remaining":10,"est_fee_fen":null,"est_tax_fen":null,""" +
                        """"est_net_fen":null,"quick_amounts_fen":[]}""",
                )
            }
        }
        launch()
        open("Wallet")
        idleUntil { has(WalletTags.WALLET_RISK) }
        assertTrue("提示条不显示原因码原文", texts("fixture_unlisted_reason") == 0)
        assertTrue(texts("提现已暂停") > 0)
        // G11 更正（R1-11）：风控冻结不置灰，点了进提现页，由 rules 的 30303 account_frozen 提示，不能提交
        composeRule.onNodeWithTag(WalletTags.WALLET_WITHDRAW, useUnmergedTree = true).assertIsEnabled()
        click(WalletTags.WALLET_WITHDRAW)
        idleUntil { top()?.name == "Withdraw" && has(WalletTags.WITHDRAW_GUIDE) }
        assertTrue(texts("提现已暂停，如有疑问请联系客服") > 0)
        composeRule.onNodeWithTag(WalletTags.WITHDRAW_SUBMIT, useUnmergedTree = true).assertIsNotEnabled()
        assertEquals(0, count("POST", WITHDRAW_PATH))
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        synchronized(answers) { answers.remove(RULES_PATH) }
        // 提示条可点：经 Router 打开 RiskNotice
        click(WalletTags.WALLET_RISK)
        assertEquals("RiskNotice", top()?.name)
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        // b：待抵扣
        summary =
            SUMMARY.replace("\"negative_fen\":0", "\"negative_fen\":200").replace("\"withdrawable_fen\":1000", "\"withdrawable_fen\":0")
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        open("Wallet")
        idleUntil { has(WalletTags.WALLET_NEGATIVE) }
        assertTrue(texts("待抵扣 ¥2，后续返利将优先抵扣") > 0)
        composeRule.onNodeWithTag(WalletTags.WALLET_WITHDRAW, useUnmergedTree = true).assertIsNotEnabled()
        // c：withdraw_enabled=false 显示 error.30306
        summary = SUMMARY.replace("\"withdraw_enabled\":true", "\"withdraw_enabled\":false")
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        open("Wallet")
        idleUntil { has(WalletTags.WALLET_DISABLED_REASON) }
        assertTrue(texts("提现功能暂时关闭，请稍后再试") > 0)
        composeRule.onNodeWithTag(WalletTags.WALLET_WITHDRAW, useUnmergedTree = true).assertIsNotEnabled()
    }

    @Test
    fun walletEntriesOpenExactRoutesAndEarningsHiddenWhileSinceIsNull() {
        launch(config = CONFIG_EARNINGS_ON)
        open("Wallet")
        idleUntil { has(WalletTags.WALLET_HERO) }
        // G20：flag 为 true，但 Earnings 的 since 三端都为 null → 入口不显示
        assertEquals(0, texts("收益看板"))
        click(WalletTags.WALLET_WITHDRAW)
        assertEquals("Withdraw", top()?.name)
        assertEquals("{}", top()?.paramsJson)
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        composeRule.onNodeWithText("提现记录", useUnmergedTree = true).performClick()
        settle()
        assertEquals("WithdrawRecords", top()?.name)
        assertEquals("{}", top()?.paramsJson)
    }

    // ---------------------------------------------------------------- AC-S1-105 金额隐藏

    @Test
    fun ac_s1_105_eyeMasksEveryWalletAmountAndSharesPreferenceWithOrders() {
        launch()
        open("Wallet")
        idleUntil { has(WalletTags.WALLET_HERO) }
        val before = count("GET", SUMMARY_PATH)
        composeRule.onNodeWithContentDescription("隐藏金额").performClick()
        settle()
        assertTrue(mask.hidden.value)
        assertTrue("主卡 ****", texts("****") >= 1)
        assertTrue("冻结中、已提现、预估收益读「金额已隐藏」", spoken("金额已隐藏") >= 4)
        assertEquals("金额不再读出", 0, spoken("10.19 元"))
        assertEquals("可提现金额不再读出", 0, spoken("可提现余额，10 元"))
        assertTrue("月份行照常显示", texts("最早预计 11 月结算") > 0)
        // 订单页的偏好是同一份（另一个实例读到同一个值）；开关不上传、不重新请求
        assertTrue(AmountMaskStore(context).hidden.value)
        assertEquals(before, count("GET", SUMMARY_PATH))
        // 换账号仍隐藏（本机偏好，不随账号）
        switchSession("user-two")
        assertTrue(mask.hidden.value)
    }

    // ---------------------------------------------------------------- 提现：估算、二次验证、成功去向

    @Test
    fun ac_s2_80_estimateOnlyAfterPauseAndSubmitWithStepUpReplaysSameKey() {
        launch()
        openWithdraw()
        assertTrue("页头 withdrawable_fen", texts("可提现余额 ¥12.34") > 0)
        assertTrue(texts("今日还可提 1 次，本月还可提 10 次") > 0)
        assertTrue("上限行", texts("本次最多可提 ¥12") > 0)
        val rulesBefore = all("GET", RULES_PATH).size
        // 输入变化立即「计算中…」，0.5 秒内不发估算请求
        input(WalletTags.WITHDRAW_INPUT, "10")
        assertTrue(has(WalletTags.WITHDRAW_CALCULATING))
        assertEquals(rulesBefore, all("GET", RULES_PATH).size)
        advance(ESTIMATE_WAIT_MS)
        idleUntil { has(WalletTags.WITHDRAW_ESTIMATE) }
        assertEquals("1000", all("GET", RULES_PATH).last().query["amount_fen"])
        assertTrue(texts("预计到账 ¥10，以实际到账为准") > 0)
        // 清空：隐藏且不发请求
        val afterEstimate = all("GET", RULES_PATH).size
        input(WalletTags.WITHDRAW_INPUT, "")
        advance(ESTIMATE_WAIT_MS)
        walletCallsSettled()
        assertFalse(has(WalletTags.WITHDRAW_ESTIMATE))
        assertEquals(afterEstimate, all("GET", RULES_PATH).size)
        // 提交：第一次 10003 → 二次验证面板（发码带 purpose=step_up、action=withdraw），通过后沿用原键、原体重放
        typeAmount("10")
        click(WalletTags.WITHDRAW_SUBMIT)
        idleUntil { stepUpReady() }
        val sms = body(last("POST", SMS_PATH))
        assertEquals("step_up", sms["purpose"]?.jsonPrimitive?.content)
        assertEquals("withdraw", sms["action"]?.jsonPrimitive?.content)
        assertTrue(texts("验证码已发送至你绑定的手机号") > 0)
        assertEquals(
            "面板不显示号码",
            0,
            composeRule
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasText("138", substring = true) and
                        androidx.compose.ui.test
                            .hasAnyAncestor(
                                androidx.compose.ui.test
                                    .hasTestTag(WalletTags.STEP_UP_SHEET),
                            ),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
                .size,
        )
        passStepUp()
        idleUntil { top()?.name == "WithdrawRecords" }
        val submits = all("POST", WITHDRAW_PATH)
        assertEquals(2, submits.size)
        assertEquals("沿用原键", header(submits[0], "Idempotency-Key"), header(submits[1], "Idempotency-Key"))
        assertEquals("原请求体", submits[0].body, submits[1].body)
        assertEquals("""{"amount_fen":1000}""", submits[1].body)
        assertNull(header(submits[0], "X-Step-Up-Token"))
        assertNotNull(header(submits[1], "X-Step-Up-Token"))
        assertEquals("X-Step-Up-Token 不进请求体", false, submits[1].body.contains("token"))
        // 成功：以 WithdrawRecords {withdrawal_id} 替换提现页（返回时回到钱包），这一单在最前并展开
        assertEquals("""{"withdrawal_id":"W2026101200101"}""", top()?.paramsJson)
        assertFalse("Withdraw 已被替换", stack().contains("Withdraw"))
        idleUntil { has(WalletTags.RECORD_DETAIL_PREFIX + "W2026101200101") }
        assertTrue(pendingBackend.list("user-one").isEmpty())
    }

    @Test
    fun ac_s2_62_unknownResultEntersPendingConfirmAndConfirmResendsOriginalKeyAndBody() {
        launch()
        openWithdraw()
        typeAmount("10")
        // 有效 token（先走一次面板），第一次提交断连（结果未知）
        synchronized(answers) { answers[WITHDRAW_PATH] = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) } }
        click(WalletTags.WITHDRAW_SUBMIT)
        idleUntil { has(WalletTags.PENDING_CARD) }
        assertTrue(texts("上一次提交的结果还没有确认") > 0)
        assertTrue("{amount} 取未决记录", texts("上一笔提现 ¥10 还没有收到结果") > 0)
        composeRule.onNodeWithTag(WalletTags.WITHDRAW_SUBMIT, useUnmergedTree = true).assertIsNotEnabled()
        val first = last("POST", WITHDRAW_PATH)
        // 不自动重发；跨 24 小时、跨午夜都不解除（记录不按时间过期）
        advance(TimeUnit.HOURS.toMillis(25))
        assertEquals(1, all("POST", WITHDRAW_PATH).size)
        assertTrue(has(WalletTags.PENDING_CARD))
        // 确认：原键、原体重发（这次 10003 → 面板 → 带 token 再确认）
        synchronized(answers) { answers.remove(WITHDRAW_PATH) }
        click(WalletTags.PENDING_CONFIRM)
        passStepUp()
        idleUntil { top()?.name == "WithdrawRecords" }
        val confirms = all("POST", WITHDRAW_PATH).drop(1)
        assertTrue(confirms.isNotEmpty())
        confirms.forEach {
            assertEquals(header(first, "Idempotency-Key"), header(it, "Idempotency-Key"))
            assertEquals(first?.body, it.body)
        }
    }

    @Test
    fun ac_s2_62_abandonAsksTwiceThenCallsAbandonAndRestoresForm() {
        launch()
        openWithdraw()
        typeAmount("10")
        synchronized(answers) { answers[WITHDRAW_PATH] = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) } }
        click(WalletTags.WITHDRAW_SUBMIT)
        passStepUpWhenShown { has(WalletTags.PENDING_CARD) }
        idleUntil { has(WalletTags.PENDING_CARD) }
        val key = header(last("POST", WITHDRAW_PATH), "Idempotency-Key")
        click(WalletTags.PENDING_ABANDON)
        idleUntil { has(WalletTags.ABANDON_DIALOG) }
        assertEquals("二次确认之前不调作废接口", 0, count("POST", ABANDON_PATH))
        composeRule.onNodeWithText("确定放弃", useUnmergedTree = true).performClick()
        settle()
        idleUntil { !has(WalletTags.PENDING_CARD) }
        val abandon = body(last("POST", ABANDON_PATH))
        assertEquals("withdraw", abandon["action"]?.jsonPrimitive?.content)
        assertEquals(key, abandon["idempotency_key"]?.jsonPrimitive?.content)
        assertTrue(pendingBackend.list("user-one").isEmpty())
        // 表单恢复可用，下次提交用新键
        synchronized(answers) { answers.remove(WITHDRAW_PATH) }
        typeAmount("5")
        click(WalletTags.WITHDRAW_SUBMIT)
        passStepUpWhenShown { top()?.name == "WithdrawRecords" }
        idleUntil { top()?.name == "WithdrawRecords" }
        assertTrue(header(last("POST", WITHDRAW_PATH), "Idempotency-Key") != key)
    }

    @Test
    fun ac_s2_62_pendingIsPerAccountAndSurvivesSwitchingBack() {
        launch()
        openWithdraw()
        typeAmount("10")
        synchronized(answers) { answers[WITHDRAW_PATH] = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) } }
        click(WalletTags.WITHDRAW_SUBMIT)
        passStepUpWhenShown { has(WalletTags.PENDING_CARD) }
        idleUntil { has(WalletTags.PENDING_CARD) }
        val sent = all("POST", WITHDRAW_PATH).size
        // 换账号：上一个账号的键不发、不展示；已输入的金额清空
        switchSession("user-two")
        idleUntil { !has(WalletTags.PENDING_CARD) && has(WalletTags.WITHDRAW_SUBMIT) }
        assertEquals("", fieldText(WalletTags.WITHDRAW_INPUT))
        assertEquals(sent, all("POST", WITHDRAW_PATH).size)
        // 原账号再登录：恢复待确认
        switchSession("user-one")
        idleUntil { has(WalletTags.PENDING_CARD) }
        assertEquals(sent, all("POST", WITHDRAW_PATH).size)
    }

    @Test
    fun ac_s2_62_laborPrerequisiteReturnsToSameWithdrawWithoutSubmitting() {
        laborSigned = false
        launch()
        openWithdraw()
        assertTrue(has(WalletTags.WITHDRAW_GUIDE))
        assertTrue(texts("请先阅读并同意相关授权") > 0)
        composeRule.onNodeWithTag(WalletTags.WITHDRAW_SUBMIT, useUnmergedTree = true).assertIsNotEnabled()
        // G29：非 30304 的引导时金额框仍可输入
        input(WalletTags.WITHDRAW_INPUT, "5")
        assertEquals("5", fieldText(WalletTags.WITHDRAW_INPUT))
        // 用户点按钮才打开前置页（不自动跳）
        click(WalletTags.WITHDRAW_GUIDE_BUTTON)
        assertEquals("LaborAgreement", top()?.name)
        idleUntil { has(WalletTags.LABOR_TEXT) }
        // 勾选默认不勾，勾选后【同意并签署】可用
        composeRule.onNodeWithTag(WalletTags.LABOR_SIGN, useUnmergedTree = true).assertIsNotEnabled()
        click(WalletTags.LABOR_CHECK)
        click(WalletTags.LABOR_SIGN)
        idleUntil { top()?.name == "Withdraw" }
        // 回到同一实例：金额仍在、立即重取 rules、0 次提交请求
        assertEquals("5", fieldText(WalletTags.WITHDRAW_INPUT))
        idleUntil { !has(WalletTags.WITHDRAW_GUIDE) }
        assertEquals(0, count("POST", WITHDRAW_PATH))
        composeRule.onNodeWithTag(WalletTags.WITHDRAW_SUBMIT, useUnmergedTree = true).assertIsEnabled()
    }

    @Test
    fun ac_s2_62_submitPrerequisite10004GuidesWithoutReplayAndNextSubmitUsesNewKey() {
        launch()
        openWithdraw()
        typeAmount("10")
        synchronized(answers) {
            answers[WITHDRAW_PATH] = { envelope("""{"consent_type":"labor_agreement"}""", code = 10004) }
        }
        click(WalletTags.WITHDRAW_SUBMIT)
        idleUntil { top()?.name == "LaborAgreement" }
        val firstKey = header(last("POST", WITHDRAW_PATH), "Idempotency-Key")
        assertEquals(1, count("POST", WITHDRAW_PATH))
        synchronized(answers) { answers.remove(WITHDRAW_PATH) }
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        walletCallsSettled()
        assertEquals("补办完不自动提交", 1, count("POST", WITHDRAW_PATH))
        assertEquals("10", fieldText(WalletTags.WITHDRAW_INPUT))
        click(WalletTags.WITHDRAW_SUBMIT)
        passStepUpWhenShown { top()?.name == "WithdrawRecords" }
        idleUntil { top()?.name == "WithdrawRecords" }
        assertTrue("再点用新键", header(all("POST", WITHDRAW_PATH)[1], "Idempotency-Key") != firstKey)
    }

    /**
     * FU-AND-0716 R2-01：提交的 10003 响应被服务端挂住、晚于「点击 + 固定 5 轮空转」才到达时，[passStepUpWhenShown] 仍等到面板出现并通过它，
     * 不靠轮次。显式握手：先确认挂住期间固定空转之后面板确实还没出现（旧的 passStepUpIfShown 在这里检查不到面板就返回），再在等待已开始轮询
     * （[done] 第一次被调用）时放行响应。时间上限（CEILING_MS）只用于失败退出。
     */
    @Test
    fun fuand0716_r2_01_aStepUpAnswerHeldPastTheFixedRoundsIsStillHandled() {
        launch()
        openWithdraw()
        typeAmount("10")
        val arrived = CountDownLatch(1)
        val held = CountDownLatch(1)
        synchronized(answers) {
            answers[WITHDRAW_PATH] = { req ->
                if (req.method == "POST" && req.getHeader("X-Step-Up-Token") == null) {
                    arrived.countDown()
                    held.await(FlowWait.CEILING_MS, TimeUnit.MILLISECONDS)
                    envelope("null", code = 10003)
                } else if (req.method == "POST") {
                    submitAnswer(last("POST", WITHDRAW_PATH)!!)
                } else {
                    defaultAnswer(last(req.method.orEmpty(), WITHDRAW_PATH)!!)
                }
            }
        }
        try {
            click(WalletTags.WITHDRAW_SUBMIT)
            assertTrue("提交已到服务端、响应挂住", arrived.await(FlowWait.CEILING_MS, TimeUnit.MILLISECONDS))
            settle()
            assertFalse("挂住期间固定空转之后面板还没出现", has(WalletTags.STEP_UP_CODE))
            var released = false
            passStepUpWhenShown {
                if (!released) {
                    released = true
                    held.countDown()
                }
                top()?.name == "WithdrawRecords"
            }
            idleUntil { top()?.name == "WithdrawRecords" }
            assertTrue("等待开始轮询后才放行", released)
            val posts = all("POST", WITHDRAW_PATH)
            assertEquals("一次无 token、一次带 token", 2, posts.size)
            assertNull(header(posts[0], "X-Step-Up-Token"))
            assertNotNull("面板已被通过、带 token 重发", header(posts[1], "X-Step-Up-Token"))
        } finally {
            held.countDown()
        }
    }

    @Test
    fun retryAfterDisablesSubmitAndBlocksAtExecutionLayer() {
        launch()
        openWithdraw()
        typeAmount("10")
        synchronized(answers) {
            answers[WITHDRAW_PATH] =
                { envelope("null", code = 42901).setResponseCode(429).setHeader("Retry-After", "12") }
        }
        click(WalletTags.WITHDRAW_SUBMIT)
        passStepUpWhenShown { has(WalletTags.WITHDRAW_ERROR) }
        idleUntil { has(WalletTags.WITHDRAW_ERROR) }
        assertTrue(texts("操作太频繁，请稍后再试") > 0)
        composeRule.onNodeWithTag(WalletTags.WITHDRAW_SUBMIT, useUnmergedTree = true).assertIsNotEnabled()
        val sent = all("POST", WITHDRAW_PATH).size
        // 执行层：禁用期内不发包（直接从执行器入口也拦下）
        composeRule.runOnUiThread { assertTrue(wallet.gates.withdraw.active) }
        assertEquals(sent, all("POST", WITHDRAW_PATH).size)
    }

    @Test
    fun ac_s2_80_serverErrorShowsTrace6AndRiskBlockedShowsDialog() {
        launch()
        openWithdraw()
        typeAmount("10")
        synchronized(answers) { answers[WITHDRAW_PATH] = { envelope("null", code = 50301, trace = "trace-abc123456") } }
        click(WalletTags.WITHDRAW_SUBMIT)
        passStepUpWhenShown { has(WalletTags.WITHDRAW_ERROR) }
        idleUntil { has(WalletTags.WITHDRAW_ERROR) }
        assertTrue("文案无占位符也独立追加 trace6", texts("123456") > 0)
        synchronized(answers) { answers[WITHDRAW_PATH] = { envelope("""{"risk_msg_code":"fixture"}""", code = 44001) } }
        click(WalletTags.WITHDRAW_SUBMIT)
        passStepUpWhenShown { texts("操作未通过安全校验") > 0 }
        idleUntil { texts("操作未通过安全校验") > 0 }
        assertTrue(texts("这笔提现没有提交，金额没有冻结") > 0)
        assertEquals("金额保留在本页", "10", fieldText(WalletTags.WITHDRAW_INPUT))
        composeRule.onNodeWithText("联系客服申诉", useUnmergedTree = true).performClick()
        settle()
        assertEquals("Help", top()?.name)
    }

    // ---------------------------------------------------------------- AC-S1-81 ⑫ 资金与实名页不接受参数

    @Test
    fun ac_s1_81_fundPagesDropParamsWithoutPrefillAndTrackCountOnly() {
        launch()
        open("Withdraw", mapOf("amount_fen" to "500", "unknown" to "x"))
        idleUntil { has(WalletTags.WITHDRAW_SUBMIT) }
        assertEquals("Withdraw", top()?.name)
        assertEquals("{}", top()?.paramsJson)
        assertEquals("没有任何字段被预填", "", fieldText(WalletTags.WITHDRAW_INPUT))
        val event = synchronized(tracked) { tracked.last() }
        assertEquals("route_params_dropped", event.name)
        assertEquals("Withdraw", event.fields["route"]?.content)
        assertEquals(2L, event.fields["dropped_count"]?.content?.toLong())
        assertFalse("不记参数内容", event.fields.values.any { it.content == "500" })
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        open("RealName", mapOf("name" to "测试三"))
        idleUntil { has(WalletTags.REALNAME_NAME) }
        assertEquals("", fieldText(WalletTags.REALNAME_NAME))
    }

    // ---------------------------------------------------------------- 提现记录

    @Test
    fun ac_s2_20_recordsGroupByMonthAndCopyTradeNoThroughWriter() {
        launch()
        open("WithdrawRecords", mapOf("withdrawal_id" to "W2026092700002"))
        idleUntil { has(WalletTags.RECORD_DETAIL_PREFIX + "W2026092700002") }
        assertTrue(texts("2026 年 10 月") > 0)
        assertTrue(texts("2026 年 9 月") > 0)
        assertTrue(texts("审核中") > 0)
        assertTrue(texts("打款未成功") > 0)
        assertTrue("PAID_API 副文案", texts("已转入支付宝 138****5678，到账 ¥5") > 0)
        assertTrue("FAILED 原因；退回", texts("收款支付宝账号状态异常；¥8 已退回可提现余额，可修改收款账号后重新提现") > 0)
        click(WalletTags.RECORD_COPY)
        assertEquals(1, system.writes)
        assertEquals("20260928200040011100", system.content)
        assertTrue(ledger.contains("20260928200040011100"))
        assertTrue(texts("已复制交易流水号") > 0)
        // FAILED + change_payout_account：展开后【修改收款账号】→ PayoutAccount
        composeRule.onNodeWithTag(WalletTags.RECORD_PREFIX + "W2026100800003", useUnmergedTree = true).performScrollTo()
        composeRule.onNodeWithText("提现 ¥8", useUnmergedTree = true).performClick()
        settle()
        idleUntil { has(WalletTags.RECORD_CHANGE_ACCOUNT) }
        click(WalletTags.RECORD_CHANGE_ACCOUNT)
        assertEquals("PayoutAccount", top()?.name)
    }

    // ---------------------------------------------------------------- 收款账号

    @Test
    fun ac_s2_54_firstBindShowsFormNeedsStepUpAndKeepsInputOnFieldError() {
        payoutBound = false
        launch()
        open("PayoutAccount")
        idleUntil { has(WalletTags.PAYOUT_SAVE) }
        click(WalletTags.PAYOUT_METHOD_BANK)
        input(WalletTags.PAYOUT_CARD, "6222 0000 0000 0009")
        input(WalletTags.PAYOUT_BANK_NAME, "示例银行")
        input(WalletTags.PAYOUT_PAYEE, "测试三")
        synchronized(answers) {
            answers[PAYOUT_PATH] =
                { req -> if (req.method == "PUT") envelope("""{"fields":["card_no"]}""", code = 20001) else payoutAnswer() }
        }
        click(WalletTags.PAYOUT_SAVE)
        passStepUpWhenShown { texts("填写内容有误，请检查") > 0 }
        idleUntil { texts("填写内容有误，请检查") > 0 }
        assertEquals("保留输入", "6222 0000 0000 0009", fieldText(WalletTags.PAYOUT_CARD))
        val put = body(last("PUT", PAYOUT_PATH))
        assertEquals("bank_card", put["payout_method"]?.jsonPrimitive?.content)
        assertEquals("示例银行", put["bank_name"]?.jsonPrimitive?.content)
        // 首次绑定也弹二次验证（第一次 PUT 没带 token）
        assertNull(header(all("PUT", PAYOUT_PATH).first(), "X-Step-Up-Token"))
        synchronized(answers) { answers.remove(PAYOUT_PATH) }
        click(WalletTags.PAYOUT_SAVE)
        passStepUpWhenShown { has(WalletTags.PAYOUT_BOUND) }
        idleUntil { has(WalletTags.PAYOUT_BOUND) }
        assertTrue("只显示服务端的脱敏串", texts("尾号 0009") > 0)
        assertEquals("完整卡号不再显示", 0, texts("6222 0000 0000 0009"))
    }

    // ---------------------------------------------------------------- 实名（G01）

    @Test
    fun realnameUnavailableInReleaseShapeAndConsentDefaultUncheckedInDebugShape() {
        launch(realname = null)
        open("RealName")
        idleUntil { has(WalletTags.REALNAME_SUBMIT) }
        assertTrue(texts("实名认证暂未开放，请稍后再试") > 0)
        input(WalletTags.REALNAME_NAME, "测试三")
        input(WalletTags.REALNAME_ID, "00000000000000000x")
        composeRule.onNodeWithTag(WalletTags.REALNAME_SUBMIT, useUnmergedTree = true).assertIsNotEnabled()
        assertEquals("小写 x 转大写", "00000000000000000X", fieldText(WalletTags.REALNAME_ID))
    }

    // ---------------------------------------------------------------- TECH-20 断网缓存

    @Test
    fun tech20_staleCacheOnlyOnTransportFailureAndWithdrawDisabled() {
        launch()
        open("Wallet")
        idleUntil { has(WalletTags.WALLET_HERO) }
        assertNotNull(walletCacheStore(context).read("user-one"))
        synchronized(answers) { answers[SUMMARY_PATH] = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START) } }
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        open("Wallet")
        idleUntil { has(WalletTags.WALLET_STALE) }
        assertTrue(texts("网络不可用，以下为") > 0)
        composeRule.onNodeWithTag(WalletTags.WALLET_WITHDRAW, useUnmergedTree = true).assertIsNotEnabled()
        // 5xxxx 不用缓存：错误态带 trace6
        synchronized(answers) { answers[SUMMARY_PATH] = { envelope("null", code = 50001, trace = "trace-zz9876") } }
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        open("Wallet")
        idleUntil { has(WalletTags.WALLET_ERROR) }
        assertTrue(texts("ZZ9876".lowercase()) > 0 || texts("zz9876") > 0)
        // 换账号：缓存被删
        switchSession("user-two")
        assertNull(walletCacheStore(context).read("user-one"))
    }

    // ---------------------------------------------------------------- 会话换代清空

    @Test
    fun sessionChangeClearsShownAmountsAndFilledPayoutForm() {
        payoutBound = false
        launch()
        open("PayoutAccount")
        idleUntil { has(WalletTags.PAYOUT_LOGON) }
        input(WalletTags.PAYOUT_LOGON, "fixture@example.com")
        input(WalletTags.PAYOUT_PAYEE, "测试三")
        // A 会话失效 → 登录 B → 返回旧页面：不见 A 的已填内容
        switchSession(null)
        switchSession("user-two")
        idleUntil { has(WalletTags.PAYOUT_LOGON) }
        assertEquals("", fieldText(WalletTags.PAYOUT_LOGON))
        assertEquals("", fieldText(WalletTags.PAYOUT_PAYEE))
    }

    // ---------------------------------------------------------------- AC-S2-80 ⑩–⑫ 估算的归属

    @Test
    fun ac_s2_80_lateEstimateForOldAmountNeverOverwritesCurrent() {
        launch()
        openWithdraw()
        synchronized(answers) {
            answers[RULES_PATH] = { req ->
                val amount = req.requestUrl?.queryParameter("amount_fen")?.toLong()
                val response = envelope(rules(amount))
                if (amount == SLOW_AMOUNT) response.setBodyDelay(LATE_MS, TimeUnit.MILLISECONDS) else response
            }
        }
        input(WalletTags.WITHDRAW_INPUT, "4")
        advance(ESTIMATE_WAIT_MS)
        input(WalletTags.WITHDRAW_INPUT, "10")
        advance(ESTIMATE_WAIT_MS)
        idleUntil { texts("预计到账 ¥10，以实际到账为准") > 0 }
        // 4 元那次迟到的响应：序号、金额都对不上，不覆盖（等提现页处理完这次估算响应再核对）
        lateResponsesSettled(1) { it.operationId == RULES_OPERATION && it.query == "amount_fen=$SLOW_AMOUNT" }
        assertTrue(texts("预计到账 ¥10，以实际到账为准") > 0)
        assertEquals(0, texts("预计到账 ¥4，"))
        // ⑪ 清空：在途的估算回来也不显示
        input(WalletTags.WITHDRAW_INPUT, "")
        settle()
        walletCallsSettled()
        assertFalse(has(WalletTags.WITHDRAW_ESTIMATE))
    }

    @Test
    fun stepUpWrongCodeClearsInputAndClosingEndsKeyWithoutSubmitting() {
        launch()
        openWithdraw()
        typeAmount("10")
        click(WalletTags.WITHDRAW_SUBMIT)
        idleUntil { stepUpReady() }
        // 不满 6 位不能验证
        input(WalletTags.STEP_UP_CODE, "12345")
        composeRule.onNodeWithTag(WalletTags.STEP_UP_SUBMIT, useUnmergedTree = true).assertIsNotEnabled()
        input(WalletTags.STEP_UP_CODE, "000000")
        click(WalletTags.STEP_UP_SUBMIT)
        idleUntil { has(WalletTags.STEP_UP_ERROR) }
        assertTrue(texts("验证码错误，请重新输入") > 0)
        assertEquals("清空输入", "", fieldText(WalletTags.STEP_UP_CODE))
        assertEquals(1, count("POST", WITHDRAW_PATH))
        // 关闭面板：回到编辑态，键不用了（下次新键）
        composeRule
            .onNode(
                androidx.compose.ui.test
                    .hasContentDescription("关闭") and
                    androidx.compose.ui.test
                        .hasAnyAncestor(
                            androidx.compose.ui.test
                                .hasTestTag(WalletTags.STEP_UP_SHEET),
                        ),
                useUnmergedTree = true,
            ).performClick()
        settle()
        assertFalse(has(WalletTags.STEP_UP_SHEET))
        val key = header(last("POST", WITHDRAW_PATH), "Idempotency-Key")
        click(WalletTags.WITHDRAW_SUBMIT)
        idleUntil { stepUpReady() }
        assertTrue(header(last("POST", WITHDRAW_PATH), "Idempotency-Key") != key)
    }

    /** 共性第 50 条：成功后的出栈 / 替换只作用于发起页的那一个导航实例（回退栈条目），不按路由名。 */
    @Test
    fun lesson50_successPopAndReplaceTargetOnlyTheirOwnEntry() {
        launch()
        openWithdraw()
        val withdrawEntry = navController.currentBackStackEntry!!.id
        open("PayoutAccount")
        idleUntil { has(WalletTags.PAYOUT_PAGE) }
        val payoutEntry = navController.currentBackStackEntry!!.id
        val before = stack()
        composeRule.runOnUiThread {
            assertFalse("不是发起页的条目：不出栈", wallet.env.payoutSaved("entry-not-this-page"))
            // 提现页不在栈顶：它的成功替换不动栈顶的收款账号页
            wallet.env.replaceTop("WithdrawRecords", JsonObject(emptyMap()), withdrawEntry)
        }
        settle()
        assertEquals(before, stack())
        composeRule.runOnUiThread { assertTrue(wallet.env.payoutSaved(payoutEntry)) }
        settle()
        assertEquals("Withdraw", top()?.name)
        assertEquals(withdrawEntry, navController.currentBackStackEntry!!.id)
    }

    /**
     * Android 第 2 轮 R2-11（共性第 48 条，AdaptSheetRules、A11yFocusStepUp）：资金半屏禁止遮罩与下滑关闭，但系统返回关闭最上层半屏，走与关闭按钮
     * 相同的出口（首次提交回到编辑态、本次键不用了）。
     */
    @Test
    fun r2_11_systemBackClosesStepUpSheetThroughCloseExit() {
        launch()
        openWithdraw()
        typeAmount("10")
        click(WalletTags.WITHDRAW_SUBMIT)
        idleUntil { stepUpReady() }
        val key = header(last("POST", WITHDRAW_PATH), "Idempotency-Key")
        composeRule.runOnUiThread {
            val dialog =
                org.robolectric.shadows.ShadowDialog
                    .getLatestDialog() as androidx.activity.ComponentDialog
            dialog.onBackPressedDispatcher.onBackPressed()
        }
        settle()
        idleUntil { !has(WalletTags.STEP_UP_SHEET) }
        assertEquals("关闭不发请求", 1, count("POST", WITHDRAW_PATH))
        composeRule.onNodeWithTag(WalletTags.WITHDRAW_SUBMIT, useUnmergedTree = true).assertIsEnabled()
        click(WalletTags.WITHDRAW_SUBMIT)
        idleUntil { stepUpReady() }
        assertTrue("与关闭按钮同一出口：下次用新键", header(last("POST", WITHDRAW_PATH), "Idempotency-Key") != key)
    }

    @Test
    fun ac_s2_62_16_confirmGetting3xxxxEndsKeyShowsErrorAndRestoresSubmit() {
        launch()
        openWithdraw()
        typeAmount("10")
        synchronized(answers) { answers[WITHDRAW_PATH] = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) } }
        click(WalletTags.WITHDRAW_SUBMIT)
        passStepUpWhenShown { has(WalletTags.PENDING_CARD) }
        idleUntil { has(WalletTags.PENDING_CARD) }
        synchronized(answers) { answers[WITHDRAW_PATH] = { envelope("""{"reason":"daily_count"}""", code = 30303) } }
        click(WalletTags.PENDING_CONFIRM)
        passStepUpWhenShown { !has(WalletTags.PENDING_CARD) }
        idleUntil { !has(WalletTags.PENDING_CARD) }
        assertTrue("fallback 文案（G06）", texts("今日提现次数已用完，请明天再试") > 0)
        assertTrue(pendingBackend.list("user-one").isEmpty())
        composeRule.onNodeWithTag(WalletTags.WITHDRAW_SUBMIT, useUnmergedTree = true).assertIsEnabled()
    }

    @Test
    fun g05_payoutSavedFromWithdrawPopsBackToSameWithdraw() {
        payoutBound = false
        launch()
        openWithdraw()
        input(WalletTags.WITHDRAW_INPUT, "5")
        // 收款账号行没有（未绑定）；打开收款账号页（同 30305 的引导去向）
        open("PayoutAccount")
        idleUntil { has(WalletTags.PAYOUT_LOGON) }
        input(WalletTags.PAYOUT_LOGON, "fixture@example.com")
        input(WalletTags.PAYOUT_PAYEE, "测试三")
        synchronized(answers) {
            answers[PAYOUT_PATH] = { req ->
                if (req.method == "PUT" && req.getHeader("X-Step-Up-Token") != null) {
                    payoutBound = true
                    envelope(
                        """{"payout_method":"alipay","masked_account":"fi***@example.com",""" +
                            """"masked_payee_name":"**三","change_remaining_this_month":1}""",
                    )
                } else if (req.method == "PUT") {
                    envelope("null", code = 10003)
                } else {
                    payoutAnswer()
                }
            }
        }
        click(WalletTags.PAYOUT_SAVE)
        passStepUpWhenShown { top()?.name == "Withdraw" }
        idleUntil { top()?.name == "Withdraw" }
        assertEquals("回到同一实例，金额仍在", "5", fieldText(WalletTags.WITHDRAW_INPUT))
        assertEquals(0, count("POST", WITHDRAW_PATH))
        val put = body(last("PUT", PAYOUT_PATH))
        assertEquals("fixture@example.com", put["alipay_logon_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun savedAmountRestoresOnlyForSameAccount() {
        launch()
        val owner = WalletStaleCache.Store.hash("user-one")
        composeRule.runOnUiThread {
            val config = wallet.config(RouteDestination("Withdraw", "{}", false), null) {}
            val same = WithdrawViewModel(config, SavedStateHandle(mapOf("withdraw.amount" to "7.5", "withdraw.owner" to owner)))
            assertEquals("7.5", same.controller.state.value.amountText)
            same.controller.leave()
            val other =
                WithdrawViewModel(
                    config,
                    SavedStateHandle(mapOf("withdraw.amount" to "7.5", "withdraw.owner" to WalletStaleCache.Store.hash("user-two"))),
                )
            assertEquals("别的账号保存的金额不恢复", "", other.controller.state.value.amountText)
            other.controller.leave()
        }
    }

    // ---------------------------------------------------------------- APP-09 三端共性第 1–12 条

    @Test
    fun lesson1_settledInFlightSubmitNotifiesTheCurrentPage() {
        launch()
        openWithdraw()
        typeAmount("10")
        // 提交先过二次验证；重放的那次延迟后回网关错误页（结果未知）
        click(WalletTags.WITHDRAW_SUBMIT)
        idleUntil { stepUpReady() }
        synchronized(answers) {
            answers[WITHDRAW_PATH] = {
                MockResponse().setResponseCode(502).setBody("<html>bad gateway</html>").setBodyDelay(LATE_MS, TimeUnit.MILLISECONDS)
            }
        }
        input(WalletTags.STEP_UP_CODE, "123456")
        click(WalletTags.STEP_UP_SUBMIT)
        // FU-AND-0716 窄修二：验证在后台，通过后才发原键重放；原写法点完固定轮次就离页，验证晚到时重放根本没发出。等重放已经发起（在途）再离开
        idleUntil { flows.started(::isSubmit) >= 2 }
        // 在途时离开提现页，再进一个新的提现页
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        openWithdraw()
        // 旧页面的迟到结算通知当前页：按本机未决记录进入待确认（不由旧页面直接改新页面）
        idleUntil {
            has(WalletTags.PENDING_CARD) &&
                texts("上一笔提现 ¥10") > 0 &&
                composeRule
                    .onNodeWithTag(WalletTags.PENDING_CONFIRM, useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .config
                    .getOrNull(SemanticsProperties.Disabled) == null
        }
        composeRule.onNodeWithTag(WalletTags.PENDING_CONFIRM, useUnmergedTree = true).assertIsEnabled()
    }

    @Test
    fun lesson2_pendingCardStaysUsableWhenRulesFail() {
        launch()
        openWithdraw()
        typeAmount("10")
        synchronized(answers) { answers[WITHDRAW_PATH] = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) } }
        click(WalletTags.WITHDRAW_SUBMIT)
        passStepUpWhenShown { has(WalletTags.PENDING_CARD) }
        idleUntil { has(WalletTags.PENDING_CARD) }
        // 换一个新的提现页，rules 读取失败：整页加载失败，但待确认卡照常可确认 / 放弃
        synchronized(answers) { answers[RULES_PATH] = { envelope("null", code = 50001, trace = "trace-rr4455") } }
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        open("Withdraw")
        idleUntil { has(WalletTags.WITHDRAW_LOAD_ERROR) && has(WalletTags.PENDING_CARD) }
        composeRule.onNodeWithTag(WalletTags.PENDING_CONFIRM, useUnmergedTree = true).assertIsEnabled()
        composeRule.onNodeWithTag(WalletTags.PENDING_ABANDON, useUnmergedTree = true).assertIsEnabled()
        assertTrue("加载失败带 trace6", texts("rr4455") > 0)
    }

    @Test
    fun lesson3_readErrorsAreUnifiedForFocusDetailAndPayoutLine() {
        launch()
        synchronized(answers) { answers["$WITHDRAW_PATH/W2026092700002"] = { envelope("null", code = 50001, trace = "trace-ff1122") } }
        open("WithdrawRecords", mapOf("withdrawal_id" to "W2026092700002"))
        idleUntil { texts("ff1122") > 0 }
        // 30701 静默
        synchronized(answers) { answers["$WITHDRAW_PATH/W-none"] = { envelope("null", code = 30701) } }
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        open("WithdrawRecords", mapOf("withdrawal_id" to "W-none"))
        idleUntil { has(WalletTags.RECORDS_LIST) }
        assertEquals(0, texts("内容不存在"))
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        // 收款账号行读取 42901：更新共享截止时间并提示
        synchronized(answers) {
            answers[PAYOUT_PATH] = { envelope("null", code = 42901).setResponseCode(429).setHeader("Retry-After", "30") }
        }
        openWithdraw()
        idleUntil { has(WalletTags.WITHDRAW_READ_ERROR) }
        composeRule.runOnUiThread { assertTrue(wallet.gates.read.active) }
    }

    @Test
    fun lesson6_smsCountdownIsSharedAcrossReopenedPanels() {
        launch()
        openWithdraw()
        typeAmount("10")
        click(WalletTags.WITHDRAW_SUBMIT)
        idleUntil { stepUpReady() }
        assertEquals(1, count("POST", SMS_PATH))
        composeRule
            .onNode(
                androidx.compose.ui.test
                    .hasContentDescription("关闭") and
                    androidx.compose.ui.test
                        .hasAnyAncestor(
                            androidx.compose.ui.test
                                .hasTestTag(WalletTags.STEP_UP_SHEET),
                        ),
                useUnmergedTree = true,
            ).performClick()
        settle()
        // 重开面板：同一账号、号码、action 的截止时间没到，不再发码，恢复倒计时
        click(WalletTags.WITHDRAW_SUBMIT)
        idleUntil { stepUpReady() }
        assertEquals(1, count("POST", SMS_PATH))
        assertTrue(texts("秒后重新获取") > 0)
    }

    @Test
    fun lesson7_refreshRefetchesExpandedDetailAndDropsStaleOne() {
        launch()
        open("WithdrawRecords")
        idleUntil { has(WalletTags.RECORD_PREFIX + "W2026101200001") }
        composeRule.onNodeWithText("提现 ¥10", useUnmergedTree = true).performClick()
        settle()
        idleUntil { has(WalletTags.RECORD_DETAIL_PREFIX + "W2026101200001") }
        val before = count("GET", "$WITHDRAW_PATH/W2026101200001")
        // 服务端这单变成已到账：刷新后列表与展开的详情都按本轮更新（旧详情不覆盖）
        val paid = RECORDS.first().replace("PENDING_REVIEW", "PAID_API")
        synchronized(answers) {
            answers[WITHDRAW_PATH] = { envelope("""{"items":[$paid],"next_cursor":null}""") }
            answers["$WITHDRAW_PATH/W2026101200001"] = { envelope(paid.replace("}", ""","channel_order_id":"fixture-trade-01"}""")) }
        }
        composeRule.runOnUiThread {
            assertTrue(
                wallet
                    .config(RouteDestination("WithdrawRecords", "{}", false), null) {}
                    .deps.gates.read.active
                    .not(),
            )
        }
        // 回到本页即刷新（与下拉同一入口）
        open("Wallet")
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        idleUntil { count("GET", "$WITHDRAW_PATH/W2026101200001") > before && texts("fixture-trade-01") > 0 }
        assertTrue(texts("已到账") > 0)
    }

    @Test
    fun lesson10_featureOffBlockSurvivesFailedRulesRead() {
        launch()
        openWithdraw()
        typeAmount("10")
        synchronized(answers) {
            answers[WITHDRAW_PATH] = { envelope("null", code = 30306) }
            answers[RULES_PATH] = { envelope("null", code = 50001) }
        }
        val rulesBefore = flows.started(::isRules)
        click(WalletTags.WITHDRAW_SUBMIT)
        passStepUpWhenShown { texts("提现功能暂时关闭，请稍后再试") > 0 }
        idleUntil { texts("提现功能暂时关闭，请稍后再试") > 0 }
        // R3-01：文案来自提交的 30306；随后 onSettled 发起的那次 rules 要等它连同业务回调结束、读取失败落到页内提示，才能断言、撤替身
        followUpRulesFailureSettled(rulesBefore)
        // rules 读取失败不恢复旧的允许状态
        composeRule.onNodeWithTag(WalletTags.WITHDRAW_SUBMIT, useUnmergedTree = true).assertIsNotEnabled()
        // 之后成功取得、明确允许的 rules 才解除
        synchronized(answers) { answers.remove(RULES_PATH) }
        val allowedBefore = flows.started(::isRules)
        typeAmount("5")
        rulesSettledSince(allowedBefore)
        composeRule.onNodeWithTag(WalletTags.WITHDRAW_SUBMIT, useUnmergedTree = true).assertIsEnabled()
    }

    /**
     * FU-AND-0716 R3-01：提交返回 30306 之后，onSettled 发起的 rules 被服务端挂住、晚于「文案出现 + 固定 5 轮空转」才失败时，
     * [followUpRulesFailureSettled] 仍等到这次 rules 连同业务回调结束、读取失败落地，才让后面的断言执行。显式握手：先确认挂住期间固定空转之后
     * 读取失败确实还没落地（原写法在这里就已断言按钮禁用、撤掉失败替身），再在等待开始轮询结算时放行响应。时间上限（CEILING_MS）只用于失败退出。
     */
    @Test
    fun fuand0716_r3_01_theFollowUpRulesFailureHeldPastTheFixedRoundsIsAwaitedBeforeAsserting() {
        launch()
        openWithdraw()
        typeAmount("10")
        val arrived = CountDownLatch(1)
        val held = CountDownLatch(1)
        synchronized(answers) {
            answers[WITHDRAW_PATH] = { envelope("null", code = 30306) }
            answers[RULES_PATH] = {
                arrived.countDown()
                held.await(FlowWait.CEILING_MS, TimeUnit.MILLISECONDS)
                envelope("null", code = 50001)
            }
        }
        try {
            val rulesBefore = flows.started(::isRules)
            click(WalletTags.WITHDRAW_SUBMIT)
            passStepUpWhenShown { texts("提现功能暂时关闭，请稍后再试") > 0 }
            idleUntil { texts("提现功能暂时关闭，请稍后再试") > 0 }
            assertTrue("后续 rules 已到服务端、响应挂住", arrived.await(FlowWait.CEILING_MS, TimeUnit.MILLISECONDS))
            settle()
            val settledBefore = flows.settled(::isRules)
            assertFalse("挂住期间固定空转之后读取失败还没落地", has(WalletTags.WITHDRAW_READ_ERROR))
            assertTrue("挂住期间后续 rules 还没结算", settledBefore < flows.started(::isRules))
            var released = false
            followUpRulesFailureSettled(rulesBefore) {
                if (!released) {
                    released = true
                    held.countDown()
                }
            }
            assertTrue("等待开始轮询后才放行", released)
            assertTrue("后续 rules 已结算", flows.settled(::isRules) > settledBefore)
            assertTrue("读取失败已落地", has(WalletTags.WITHDRAW_READ_ERROR))
            composeRule.onNodeWithTag(WalletTags.WITHDRAW_SUBMIT, useUnmergedTree = true).assertIsNotEnabled()
            synchronized(answers) { answers.remove(RULES_PATH) }
            val allowedBefore = flows.started(::isRules)
            typeAmount("5")
            rulesSettledSince(allowedBefore)
            composeRule.onNodeWithTag(WalletTags.WITHDRAW_SUBMIT, useUnmergedTree = true).assertIsEnabled()
        } finally {
            held.countDown()
        }
    }

    @Test
    fun lesson12_neverUnknownKeyEndsOn42901And40901() {
        launch()
        openWithdraw()
        typeAmount("10")
        synchronized(answers) { answers[WITHDRAW_PATH] = { envelope("null", code = 40901) } }
        click(WalletTags.WITHDRAW_SUBMIT)
        passStepUpWhenShown { texts("正在处理，请稍候") > 0 }
        idleUntil { texts("正在处理，请稍候") > 0 }
        val first = header(last("POST", WITHDRAW_PATH), "Idempotency-Key")
        synchronized(answers) { answers.remove(WITHDRAW_PATH) }
        click(WalletTags.WITHDRAW_SUBMIT)
        passStepUpWhenShown { top()?.name == "WithdrawRecords" }
        idleUntil { top()?.name == "WithdrawRecords" }
        assertTrue("40901 后下次点击用新键", header(last("POST", WITHDRAW_PATH), "Idempotency-Key") != first)
        assertTrue(pendingBackend.list("user-one").isEmpty())
    }

    @Test
    fun lesson9_olderRealnameQueryNeverOverwritesNewerConclusion() {
        launch()
        val calls =
            java.util.concurrent.atomic
                .AtomicInteger()
        synchronized(answers) {
            answers["/v1/me"] = {
                if (calls.incrementAndGet() == 1) {
                    // 先发的那次慢、而且回「未实名」
                    envelope("""{"user_id":"user-one","phone_bound":true,"realname_status":"none"}""")
                        .setBodyDelay(LATE_MS, TimeUnit.MILLISECONDS)
                } else {
                    envelope("""{"user_id":"user-one","phone_bound":true,"realname_status":"verified"}""")
                }
            }
        }
        val before = count("GET", "/v1/me")
        val base = flows.started(::isMe)
        composeRule.runOnUiThread { wallet.refreshRealname() }
        idleUntil { count("GET", "/v1/me") > before }
        composeRule.runOnUiThread { wallet.refreshRealname() }
        // 两次查询都交回 AppWallet 且结论处理完（旧的那次被取代、作废）
        lateResponsesSettled(base + 2, ::isMe)
        composeRule.runOnUiThread { assertTrue("最新一次的结论不被旧查询覆盖", wallet.realnameVerified()) }
    }

    // ---------------------------------------------------------------- 第 1 轮评审（android-review-r1.md）

    /** R1-13：提现页 20001 → 滚到首个错误提示、焦点移到提示上、播报一次，输入保留。 */
    @Test
    fun r1_13_withdrawFieldErrorScrollsFocusesAndAnnouncesOnce() {
        launch()
        openWithdraw()
        typeAmount("10")
        synchronized(answers) {
            answers[WITHDRAW_PATH] = { req ->
                if (req.method == "POST") {
                    envelope("""{"fields":["amount_fen"]}""", code = 20001)
                } else {
                    envelope("""{"items":[],"next_cursor":null}""")
                }
            }
        }
        click(WalletTags.WITHDRAW_SUBMIT)
        idleUntil { has(WalletTags.WITHDRAW_FIELD_ERROR) }
        focusWait()
        composeRule.onNodeWithTag(WalletTags.WITHDRAW_FIELD_ERROR, useUnmergedTree = true).assertIsFocused()
        val text =
            composeRule
                .onNodeWithTag(WalletTags.WITHDRAW_FIELD_ERROR, useUnmergedTree = true)
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.Text)
                ?.joinToString("")
                .orEmpty()
        assertTrue(text.isNotEmpty())
        assertEquals("同一次错误只播报一次", 1, announcedCount(text))
        assertEquals("保留输入", "10", fieldText(WalletTags.WITHDRAW_INPUT))
        // 再次提交得到同样的错误：新的错误事件，再播报一次
        val submitsBefore = flows.started(::isSubmit)
        click(WalletTags.WITHDRAW_SUBMIT)
        // RC-01：第二次的字段错误与播报要等网络返回 20001。先等这次提交连同提现页业务回调结算、新的字段错误事件落到页面，再推进焦点 / 播报的等待
        secondFieldErrorLanded(submitsBefore)
        focusWait()
        assertEquals(2, announcedCount(text))
    }

    /**
     * FU-AND-0716 RC-01：r1_13 第二次提交的 20001 被服务端挂住、晚于「点击 + focusWait 的固定轮次」才返回时，[secondFieldErrorLanded] 仍等到这次提交
     * 连同业务回调结算、新的字段错误落地，才让焦点 / 播报等待与「恰好 2 次」的断言执行。显式握手：先确认挂住期间原写法的固定等待之后播报仍只有 1 次、
     * 字段错误还没回来（原写法在这里就已断言 2 次），再在等待第一次核对结算时放行响应。时间上限（CEILING_MS）只用于失败退出。
     */
    @Test
    fun fuand0716_rc01_theSecondFieldErrorHeldPastTheFixedRoundsIsAwaitedBeforeCountingAnnouncements() {
        launch()
        openWithdraw()
        typeAmount("10")
        val posts = AtomicInteger()
        val arrived = CountDownLatch(1)
        val held = CountDownLatch(1)
        synchronized(answers) {
            answers[WITHDRAW_PATH] = { req ->
                if (req.method == "POST") {
                    if (posts.incrementAndGet() == 2) {
                        arrived.countDown()
                        held.await(FlowWait.CEILING_MS, TimeUnit.MILLISECONDS)
                    }
                    envelope("""{"fields":["amount_fen"]}""", code = 20001)
                } else {
                    envelope("""{"items":[],"next_cursor":null}""")
                }
            }
        }
        try {
            click(WalletTags.WITHDRAW_SUBMIT)
            idleUntil { has(WalletTags.WITHDRAW_FIELD_ERROR) }
            focusWait()
            val text =
                composeRule
                    .onNodeWithTag(WalletTags.WITHDRAW_FIELD_ERROR, useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .config
                    .getOrNull(SemanticsProperties.Text)
                    ?.joinToString("")
                    .orEmpty()
            assertEquals("第一次错误播报一次", 1, announcedCount(text))
            val submitsBefore = flows.started(::isSubmit)
            click(WalletTags.WITHDRAW_SUBMIT)
            assertTrue("第二次提交已到服务端、响应挂住", arrived.await(FlowWait.CEILING_MS, TimeUnit.MILLISECONDS))
            // 原写法：点击后只 focusWait（推进 Compose 时钟 + 固定 5 轮）就断言播报 2 次
            focusWait()
            val settledBefore = flows.settled(::isSubmit)
            assertEquals("挂住期间固定等待之后还没有第二次播报", 1, announcedCount(text))
            assertFalse("挂住期间字段错误还没回来（提交时已清掉）", has(WalletTags.WITHDRAW_FIELD_ERROR))
            assertTrue("挂住期间第二次提交还没结算", settledBefore < flows.started(::isSubmit))
            var released = false
            secondFieldErrorLanded(submitsBefore) {
                if (!released) {
                    released = true
                    held.countDown()
                }
            }
            assertTrue("等待开始轮询后才放行", released)
            assertTrue("第二次提交已结算", flows.settled(::isSubmit) > settledBefore)
            focusWait()
            composeRule.onNodeWithTag(WalletTags.WITHDRAW_FIELD_ERROR, useUnmergedTree = true).assertIsFocused()
            assertEquals(2, announcedCount(text))
            assertEquals("保留输入", "10", fieldText(WalletTags.WITHDRAW_INPUT))
        } finally {
            held.countDown()
        }
    }

    /** R1-13：收款账号表单本地校验与 20001 → 首个错误（按显示顺序）获得焦点并播报一次；错误字段在滚动区域外也滚到可见。 */
    @Test
    fun r1_13_payoutFieldErrorFocusesFirstErrorOnly() {
        payoutBound = false
        launch()
        open("PayoutAccount")
        idleUntil { has(WalletTags.PAYOUT_SAVE) }
        click(WalletTags.PAYOUT_METHOD_BANK)
        // 卡号、开户行、姓名都空：本地校验不过，首个错误是卡号
        click(WalletTags.PAYOUT_SAVE)
        val first = WalletTags.PAYOUT_FIELD_ERROR_PREFIX + "card_no"
        idleUntil { has(first) }
        focusWait()
        composeRule.onNodeWithTag(first, useUnmergedTree = true).assertIsFocused()
        composeRule.onNodeWithTag(WalletTags.PAYOUT_FIELD_ERROR_PREFIX + "payee_name", useUnmergedTree = true).assertIsNotFocused()
        assertEquals("多个错误字段只播报一次", 1, synchronized(announced) { announced.size })
        // 服务端 20001（姓名）：焦点移到姓名的错误提示
        input(WalletTags.PAYOUT_CARD, "6222 0000 0000 0009")
        input(WalletTags.PAYOUT_BANK_NAME, "示例银行")
        input(WalletTags.PAYOUT_PAYEE, "测试三")
        synchronized(answers) {
            answers[PAYOUT_PATH] =
                { req -> if (req.method == "PUT") envelope("""{"fields":["payee_name"]}""", code = 20001) else payoutAnswer() }
        }
        click(WalletTags.PAYOUT_SAVE)
        val payee = WalletTags.PAYOUT_FIELD_ERROR_PREFIX + "payee_name"
        idleUntil { has(payee) }
        focusWait()
        composeRule.onNodeWithTag(payee, useUnmergedTree = true).assertIsFocused()
        assertEquals(2, synchronized(announced) { announced.size })
        assertEquals("保留输入", "测试三", fieldText(WalletTags.PAYOUT_PAYEE))
    }

    /** 共性第 36 条：收款方式组的 20001 错误可见、关联到选项的无障碍说明，并有实际的焦点目标。 */
    @Test
    fun lesson36_payoutMethodErrorVisibleDescribedAndFocusable() {
        payoutBound = false
        launch()
        open("PayoutAccount")
        idleUntil { has(WalletTags.PAYOUT_SAVE) }
        input(WalletTags.PAYOUT_LOGON, "zhangsan@example.com")
        input(WalletTags.PAYOUT_PAYEE, "测试三")
        synchronized(answers) {
            answers[PAYOUT_PATH] =
                { req -> if (req.method == "PUT") envelope("""{"fields":["payout_method"]}""", code = 20001) else payoutAnswer() }
        }
        click(WalletTags.PAYOUT_SAVE)
        val tag = WalletTags.PAYOUT_FIELD_ERROR_PREFIX + "payout_method"
        idleUntil { has(tag) }
        focusWait()
        composeRule.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed().assertIsFocused()
        val described =
            composeRule
                .onNodeWithTag(WalletTags.PAYOUT_METHOD_ALIPAY, useUnmergedTree = true)
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.Error)
        assertNotNull("选项带错误说明", described)
    }

    /** R1-10：打开提现前 getMe 返回 50001 → 不导航，发起页显示带 trace6 的错误与【重试】；重试成功后才打开。 */
    @Test
    fun r1_10_getMeServerErrorShowsTrace6AndRetryInsteadOfNavigating() {
        launch()
        open("Wallet")
        idleUntil { has(WalletTags.WALLET_WITHDRAW) }
        composeRule.runOnUiThread { wallet.forgetRealname() }
        synchronized(answers) { answers["/v1/me"] = { envelope("null", code = 50001, trace = "trace-me-ABC999") } }
        click(WalletTags.WALLET_WITHDRAW)
        idleUntil { has(WalletTags.OPEN_ERROR) }
        assertEquals("不当成未实名继续导航", "Wallet", top()?.name)
        assertTrue("显示 trace6", texts("ABC999") > 0)
        synchronized(answers) { answers.remove("/v1/me") }
        click(WalletTags.OPEN_ERROR_RETRY)
        idleUntil { top()?.name == "Withdraw" }
        assertFalse(has(WalletTags.OPEN_ERROR))
    }

    /** R1-09：实名查询返回前发起页已出栈，或被更新的查询取代 → 不再跳转。 */
    @Test
    fun r1_09_realnameNavigationBoundToOriginAndLatestQuery() {
        launch()
        open("Wallet")
        idleUntil { has(WalletTags.WALLET_WITHDRAW) }
        composeRule.runOnUiThread { wallet.forgetRealname() }
        synchronized(answers) {
            answers["/v1/me"] = {
                envelope("""{"user_id":"user-one","phone_bound":true,"realname_status":"verified"}""")
                    .setBodyDelay(LATE_MS, TimeUnit.MILLISECONDS)
            }
        }
        // ① 点【提现】后返回上一页：查询回来不再打开资金页
        val first = flows.started(::isMe)
        click(WalletTags.WALLET_WITHDRAW)
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        val afterPop = stack()
        lateResponsesSettled(first + 1, ::isMe)
        assertEquals("发起页出栈后不跳转", afterPop, stack())
        assertFalse(stack().contains("Withdraw"))
        // ② 点【提现】后被更新的查询取代：旧 continuation 不执行
        open("Wallet")
        idleUntil { has(WalletTags.WALLET_WITHDRAW) }
        composeRule.runOnUiThread { wallet.forgetRealname() }
        val second = flows.started(::isMe)
        click(WalletTags.WALLET_WITHDRAW)
        composeRule.runOnUiThread { wallet.refreshRealname() }
        lateResponsesSettled(second + 2, ::isMe)
        assertEquals("Wallet", top()?.name)
        composeRule.runOnUiThread { assertTrue(wallet.realnameVerified()) }
    }

    /** 共性第 11、33 条：常规高度时三段布局（中段滚动、底部操作区固定），不降级。 */
    @Test
    fun lesson33_stepUpSheetKeepsThreePartsWhenItFits() {
        launch()
        openWithdraw()
        typeAmount("10")
        click(WalletTags.WITHDRAW_SUBMIT)
        idleUntil { stepUpReady() }
        assertTrue(has(WalletTags.SHEET_MIDDLE))
        assertFalse(has(WalletTags.SHEET_FALLBACK))
        composeRule.onNodeWithTag(WalletTags.STEP_UP_SUBMIT, useUnmergedTree = true).assertIsDisplayed()
    }

    /** 共性第 33 条：短窗口 + 200% 字号，三段最小高放不下 → 整块滚动降级，标题、关闭与【验证并提交】都可达。 */
    @Test
    @Config(qualifiers = "w320dp-h360dp-xxhdpi", fontScale = 2.0f)
    fun lesson33_stepUpSheetFallsBackToWholeScrollOnShortWindowLargeText() {
        launch()
        openWithdraw()
        typeAmount("10")
        click(WalletTags.WITHDRAW_SUBMIT)
        idleUntil { stepUpReady() }
        idleUntil { has(WalletTags.SHEET_FALLBACK) }
        composeRule.onNodeWithTag(WalletTags.STEP_UP_CLOSE, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(WalletTags.STEP_UP_SUBMIT, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        input(WalletTags.STEP_UP_CODE, "123456")
        click(WalletTags.STEP_UP_SUBMIT)
        idleUntil { top()?.name == "WithdrawRecords" }
    }

    // ---------------------------------------------------------------- Android 第 3 轮（共性第 53、55–57 条）的 App 接线

    /** R3-02（共性第 53 条）：提现成功后，仍在栈里的钱包页收到 AppWallet 按会话发出的失效通知，不等回流就重取摘要。 */
    @Test
    fun r3_02_withdrawSuccessRefreshesWalletStillInStack() {
        launch()
        open("Wallet")
        idleUntil { has(WalletTags.WALLET_WITHDRAW) }
        click(WalletTags.WALLET_WITHDRAW)
        idleUntil { has(WalletTags.WITHDRAW_SUBMIT) }
        typeAmount("10")
        val before = count("GET", SUMMARY_PATH)
        click(WalletTags.WITHDRAW_SUBMIT)
        passStepUpWhenShown { top()?.name == "WithdrawRecords" }
        idleUntil { top()?.name == "WithdrawRecords" }
        idleUntil { count("GET", SUMMARY_PATH) > before }
        assertEquals("钱包页不在栈顶也已重取", "WithdrawRecords", top()?.name)
    }

    /** R3-04（共性第 55 条）：记录页经真实 OnReturn 回流时共享限流中，保留列表并显示 42901 错误条，【重试】置灰。 */
    @Test
    fun r3_04_recordsReturnWhileGatedShowsRefreshErrorThroughOnReturn() {
        launch()
        open("WithdrawRecords")
        idleUntil { has(WalletTags.RECORD_PREFIX + "W2026101200001") }
        open("PayoutAccount")
        idleUntil { has(WalletTags.PAYOUT_PAGE) }
        val lists = count("GET", WITHDRAW_PATH)
        composeRule.runOnUiThread { wallet.gates.read.start(60_000) }
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        idleUntil { has(WalletTags.RECORDS_REFRESH_ERROR) }
        assertTrue("保留旧列表", has(WalletTags.RECORD_PREFIX + "W2026101200001"))
        assertEquals("限流中不发", lists, count("GET", WITHDRAW_PATH))
        composeRule.onNodeWithTag(WalletTags.RECORDS_REFRESH_ERROR + "_retry", useUnmergedTree = true).assertIsNotEnabled()
    }

    /** R3-06（共性第 56 条）：实名前置查询被共享 read 闸门在发送前拦下，发起页显示 42901 与置灰的【重试】，不导航。 */
    @Test
    fun r3_06_realnameQueryBlockedByReadGateShowsErrorWithGatedRetry() {
        launch()
        open("Wallet")
        idleUntil { has(WalletTags.WALLET_WITHDRAW) }
        val queries = count("GET", "/v1/me")
        composeRule.runOnUiThread {
            wallet.forgetRealname()
            wallet.gates.read.start(60_000)
        }
        click(WalletTags.WALLET_WITHDRAW)
        idleUntil { has(WalletTags.OPEN_ERROR) }
        assertEquals("Wallet", top()?.name)
        assertEquals("没有发出", queries, count("GET", "/v1/me"))
        composeRule.onNodeWithTag(WalletTags.OPEN_ERROR_RETRY, useUnmergedTree = true).assertIsNotEnabled()
    }

    /**
     * 定向复核 RC-04（共性第 62 条）：实名前置查询被共享 read 闸门在发送前拒绝，Retry-After 在结果交付主线程之前到期——仍按拒绝原因在发起页给 42901，
     * 不静默；【重试】按当前截止时间可用。
     */
    @Test
    fun rc04_realnameQueryRejectedThenGateExpiresStillShowsError() {
        var expireOnReject = false
        launch(
            wrapApi = { real ->
                object : WalletApi by real {
                    override suspend fun send(call: ApiCall): ApiResult =
                        real.send(call).also {
                            // 网络层已按发送前闸门拒绝；交付给 AppWallet 之前禁用期到期
                            if (call.operationId == "getMe" && expireOnReject) wallet.gates.read.reset()
                        }
                }
            },
        )
        open("Wallet")
        idleUntil { has(WalletTags.WALLET_WITHDRAW) }
        val queries = count("GET", "/v1/me")
        composeRule.runOnUiThread {
            wallet.forgetRealname()
            wallet.gates.read.start(60_000)
            expireOnReject = true
        }
        click(WalletTags.WALLET_WITHDRAW)
        idleUntil { has(WalletTags.OPEN_ERROR) }
        assertEquals("Wallet", top()?.name)
        assertEquals("没有发出", queries, count("GET", "/v1/me"))
        assertFalse(wallet.gates.read.active)
    }

    /** 定向复核 RC-03（共性第 61 条）：AppWallet 的失效通知原样带调用方固定的账号与世代，不在出口重读当前会话。 */
    @Test
    fun rc03_walletDirtyNoticeCarriesGivenSession() {
        launch()
        val got = mutableListOf<Pair<String?, Long>>()
        val collector =
            CoroutineScope(Dispatchers.Unconfined).launch {
                wallet.deps.walletDirty.dirty
                    .collect { got += it }
            }
        composeRule.runOnUiThread { wallet.env.markWalletDirty("user-fixed", 4242L) }
        collector.cancel()
        assertEquals(listOf<Pair<String?, Long>>("user-fixed" to 4242L), got)
    }

    /** R3-07（共性第 57 条）：实名查询在途时发起页 ON_PAUSE（仍是 STARTED）→ 响应回来不导航；暂停后又回到前台也不恢复原许可。 */
    @Test
    @SuppressLint("RestrictedApi")
    fun r3_07_realnameNavigationVoidedByOriginPause() {
        launch()
        open("Wallet")
        idleUntil { has(WalletTags.WALLET_WITHDRAW) }
        val entry = navController.currentBackStackEntry!!
        synchronized(answers) {
            answers["/v1/me"] = {
                envelope("""{"user_id":"user-one","phone_bound":true,"realname_status":"verified"}""")
                    .setBodyDelay(LATE_MS, TimeUnit.MILLISECONDS)
            }
        }
        // ① ON_PAUSE 之后、ON_STOP 之前（STARTED）响应回来
        composeRule.runOnUiThread { wallet.forgetRealname() }
        val paused = flows.started(::isMe)
        click(WalletTags.WALLET_WITHDRAW)
        composeRule.runOnUiThread { entry.maxLifecycle = androidx.lifecycle.Lifecycle.State.STARTED }
        lateResponsesSettled(paused + 1, ::isMe)
        assertEquals("后台不打开资金页", "Wallet", top()?.name)
        composeRule.runOnUiThread { entry.maxLifecycle = androidx.lifecycle.Lifecycle.State.RESUMED }
        settle()
        walletCallsSettled()
        assertEquals("Wallet", top()?.name)
        // ② 暂停后在响应前回到前台：原许可已作废
        composeRule.runOnUiThread { wallet.forgetRealname() }
        val resumed = flows.started(::isMe)
        click(WalletTags.WALLET_WITHDRAW)
        composeRule.runOnUiThread {
            entry.maxLifecycle = androidx.lifecycle.Lifecycle.State.STARTED
            entry.maxLifecycle = androidx.lifecycle.Lifecycle.State.RESUMED
        }
        lateResponsesSettled(resumed + 1, ::isMe)
        assertEquals("Wallet", top()?.name)
    }

    // ---------------------------------------------------------------- 工具

    /**
     * F-AND-31（FU-AND-0714）/ FU-AND-0716 R2-01：显式等待「二次验证面板出现，或已经到达本次操作的终态 [done]」，面板出现就通过它。
     * 原来的 passStepUpIfShown 只停 5 轮主线程空转就判断「面板有没有出现」，高负载下 10003 的响应晚于这几轮到达时会漏过面板，之后一直等不到终态
     * （等页面超时）；第 2 轮修复已逐处换成本函数并删掉旧函数，[done] 取各处原来紧随其后的那条终态等待。
     */
    private fun passStepUpWhenShown(done: () -> Boolean) {
        idleUntil { has(WalletTags.STEP_UP_CODE) || done() }
        if (has(WalletTags.STEP_UP_CODE)) passStepUp()
    }

    private fun switchSession(user: String?) {
        composeRule.runOnUiThread {
            if (user == null) tokens.clear() else tokens.save("access-$user", "refresh-$user", "full", user)
            login.syncSession()
            wallet.onSessionChanged()
        }
        settle()
        if (user != null) idleUntil { wallet.realnameVerified() }
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onNodeWithContentDescription(text: String) =
        onNode(
            androidx.compose.ui.test
                .hasContentDescription(text),
            useUnmergedTree = true,
        )

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onNodeWithText(
        text: String,
        useUnmergedTree: Boolean,
    ) = onNode(
        androidx.compose.ui.test
            .hasText(text),
        useUnmergedTree = useUnmergedTree,
    )

    /** 实名的假实现（G01：契约没有接口）。 */
    private class FakeRealName : RealNameBackend {
        override suspend fun submit(
            name: String,
            idNo: String,
            idempotencyKey: String,
        ): ApiResult = ApiResult.Success(JsonObject(emptyMap()), "trace-realname", idempotencyKey)
    }

    /** 劳务协议的假实现（G02）。 */
    private inner class FakeLabor : LaborBackend {
        override suspend fun current(): ApiResult =
            ApiResult.Success(
                JsonObject(
                    mapOf(
                        "version" to JsonPrimitive("v1"),
                        "text" to JsonPrimitive("测试协议正文"),
                        "text_sha256" to JsonPrimitive("fixture-sha"),
                        "need_sign" to JsonPrimitive(!laborSigned),
                    ),
                ),
                "trace-labor",
                null,
            )

        override suspend fun sign(
            version: String,
            textSha256: String,
        ): ApiResult {
            laborSigned = true
            return ApiResult.Success(JsonObject(emptyMap()), "trace-labor", null)
        }
    }

    // ---------------------------------------------------------------- 假服务（sample-data §12）

    private val issued = java.util.concurrent.ConcurrentHashMap<String, String>()

    @Suppress("CyclomaticComplexMethod")
    private fun defaultAnswer(request: Recorded): MockResponse =
        when {
            request.path == "/v1/me" -> {
                envelope("""{"user_id":"user-one","phone_bound":true,"realname_status":"verified"}""")
            }

            request.path == SUMMARY_PATH -> {
                envelope(summary)
            }

            request.path == RULES_PATH -> {
                envelope(rules(request.query["amount_fen"]?.toLong()))
            }

            request.path == WITHDRAW_PATH && request.method == "POST" -> {
                submitAnswer(request)
            }

            request.path == WITHDRAW_PATH -> {
                envelope("""{"items":[${RECORDS.joinToString(",")}],"next_cursor":null}""")
            }

            request.path.startsWith("$WITHDRAW_PATH/") -> {
                val id = request.path.removePrefix("$WITHDRAW_PATH/")
                (RECORDS + RECORDS.first().replace("W2026101200001", "W2026101200101"))
                    .firstOrNull {
                        it.contains("\"$id\"")
                    }?.let { envelope(it.replace("}", ""","channel_order_id":${tradeNo(id)}}""")) }
                    ?: envelope("null", code = 30701)
            }

            request.path == PAYOUT_PATH && request.method == "PUT" -> {
                if (header(request, "X-Step-Up-Token") == null) {
                    envelope("null", code = 10003)
                } else {
                    payoutBound = true
                    envelope(
                        """{"payout_method":"bank_card","masked_account":"尾号 0009","bank_name":"示例银行",""" +
                            """"masked_payee_name":"**三","change_remaining_this_month":1}""",
                    )
                }
            }

            request.path == PAYOUT_PATH -> {
                payoutAnswer()
            }

            request.path == SMS_PATH -> {
                envelope("""{"resend_after_sec":60,"expires_in_sec":300}""")
            }

            request.path == STEP_UP_PATH -> {
                if (body(request)["code"]?.jsonPrimitive?.content == "123456") {
                    envelope("""{"step_up_token":"fixture-step-up-token","expire_at":"2026-10-12T09:50:00+08:00"}""")
                } else {
                    envelope("null", code = 20002)
                }
            }

            request.path == ABANDON_PATH -> {
                val key = body(request)["idempotency_key"]?.jsonPrimitive?.content.orEmpty()
                issued[key]?.let { envelope("""{"outcome":"completed","original":{"code":0,"msg":"","data":$it}}""") }
                    ?: envelope("""{"outcome":"abandoned","original":null}""")
            }

            else -> {
                envelope("null")
            }
        }

    private fun submitAnswer(request: Recorded): MockResponse {
        if (header(request, "X-Step-Up-Token") == null) return envelope("null", code = 10003)
        val data = RECORDS.first().replace("W2026101200001", "W2026101200101")
        header(request, "Idempotency-Key")?.let { issued[it] = data }
        return envelope(data)
    }

    private fun payoutAnswer(): MockResponse =
        if (payoutBound) {
            envelope(
                """{"payout_method":"alipay","masked_account":"138****5678","masked_payee_name":"**三","change_remaining_this_month":2}""",
            )
        } else {
            envelope("{}")
        }

    private fun rules(amount: Long?): String {
        val block = if (laborSigned) "null" else "10004"
        val can = laborSigned
        val est = if (can && amount != null) amount.toString() else "null"
        val zero = if (est == "null") "null" else "0"
        val quick = if (can) "[500,1000]" else "[]"
        return """{"can_withdraw":$can,"block_code":$block,"block_reason":null,"max_withdrawable_fen":${if (can) 1200 else 0},""" +
            """"withdrawable_fen":1234,"daily_remaining":1,"monthly_remaining":10,""" +
            """"est_fee_fen":$zero,"est_tax_fen":$zero,"est_net_fen":$est,""" +
            """"quick_amounts_fen":$quick}"""
    }

    private fun tradeNo(id: String): String = if (id == "W2026092700002") "\"20260928200040011100\"" else "null"

    private fun envelope(
        data: String,
        code: Int = 0,
        trace: String = "trace-000123",
    ) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"code":$code,"msg":"","data":$data,"trace_id":"$trace"}""")

    private companion object {
        const val SUMMARY_PATH = "/v1/wallet/summary"
        const val RULES_PATH = "/v1/withdrawals/rules"
        const val WITHDRAW_PATH = "/v1/withdrawals"
        const val PAYOUT_PATH = "/v1/me/payout-account"
        const val SMS_PATH = "/v1/auth/sms-codes"
        const val STEP_UP_SENDING_TEXT = "正在发送验证码…"
        const val STEP_UP_PATH = "/v1/auth/step-up"
        const val ABANDON_PATH = "/v1/idempotency-keys/abandon"
        const val POLL_MS = 10L
        const val SETTLE_ROUNDS = 5
        const val ESTIMATE_WAIT_MS = 600L
        const val SLOW_AMOUNT = 400L
        const val GET_ME_OPERATION = "getMe"
        const val RULES_OPERATION = "getWithdrawRules"
        const val WITHDRAW_OPERATION = "createWithdrawal"
        const val LATE_MS = 800L
        const val FOCUS_WAIT_MS = 300L

        /** AC-S2-33：可提现 ¥10、冻结 ¥2、已提现 ¥0、预估 ¥10.19、已收货 ¥7.5、暂缓 ¥4.5、最早 11 月。 */
        const val SUMMARY =
            """{"available_fen":1000,"withdrawable_fen":1000,"negative_fen":0,"frozen_fen":200,"estimated_total_fen":1019,""" +
                """"estimated_fen":269,"pending_credit_fen":750,"pending_credit_paused_fen":450,"next_credit_period":"2026-11",""" +
                """"credit_overdue":false,"withdrawn_fen":0,"risk_paused_reason":null,"withdraw_enabled":true}"""

        val RECORDS =
            listOf(
                record("W2026101200001", "PENDING_REVIEW", 1000, "2026-10-12T10:00:00+08:00", null, null),
                record(
                    "W2026100800003",
                    "FAILED",
                    800,
                    "2026-10-08T11:30:00+08:00",
                    "\"PAYEE_ACCOUNT_STATUS_ERROR\"",
                    "\"change_payout_account\"",
                ),
                record("W2026092700002", "PAID_API", 500, "2026-09-27T15:20:00+08:00", null, null),
                record("W2026092500001", "REJECTED", 600, "2026-09-25T10:00:00+08:00", null, null),
            )

        @Suppress("LongParameterList")
        fun record(
            id: String,
            status: String,
            amount: Long,
            created: String,
            reason: String?,
            failAction: String?,
        ) = """{"withdrawal_id":"$id","status":"$status","review_mode":"manual","amount_fen":$amount,"fee_fen":0,"tax_fen":0,""" +
            """"net_fen":$amount,"payout_channel":"alipay","payout_channel_name":"支付宝","masked_account":"138****5678",""" +
            """"reason_code":${reason ?: "null"},"fail_action":${failAction ?: "null"},"created_at":"$created"}"""

        const val CONFIG = """{"features":{"flags":{},"platform_status":{"taobao":"on"}}}"""
        const val CONFIG_EARNINGS_ON = """{"features":{"flags":{"earnings.dashboard.visible":true},"platform_status":{"taobao":"on"}}}"""
    }
}
