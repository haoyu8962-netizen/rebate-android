package com.couli.app.me

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
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.login.AppLogin
import com.couli.app.privacy.AppPrivacy
import com.couli.app.routing.AppRouting
import com.couli.app.routing.RouteDestination
import com.couli.app.routing.RoutingPrivacyHooks
import com.couli.app.shell.AppShell
import com.couli.app.wallet.AppWallet
import com.couli.core.clientcore.ConfigReply
import com.couli.core.clientcore.ConfigStore
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
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
import com.couli.core.privacy.PrivacyGate
import com.couli.core.privacy.PrivacyStore
import com.couli.core.privacy.RetryScheduler
import com.couli.core.push.NoPushTokenSource
import com.couli.core.router.GuardKind
import com.couli.core.router.RouteOutcome
import com.couli.core.router.RouteRequest
import com.couli.core.router.RouteSource
import com.couli.core.router.SessionState
import com.couli.core.router.UpdateCheck
import com.couli.core.wallet.WalletRoutes
import com.couli.feature.login.LegalVersions
import com.couli.feature.login.PendingInviteCode
import com.couli.feature.me.MeTags
import com.couli.feature.wallet.ApiClientWalletApi
import com.couli.feature.wallet.WalletTags
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FU-AND-WITHDRAW-DIRECT（D10-2714 ①）App 层：真实 AppShell + AppRouting + AppLogin + AppMe + AppWallet + ApiClient（传输层换成 MockWebServer）。
 * 「我的」【提现】打开 Withdraw 走和钱包【提现】同一条打开路径（AppWallet.open：本会话没查过实名先取 GET /v1/me，再经 Router 以 App 内来源打开，守卫按查到的
 * 状态放行或去 RealName），不另写一套前置判断；提现页的 rules 前置照常生效。
 *
 * 接线（[bindMeToWallet]）：生产在 AppModule.appMe 里把 AppMe 接到 AppWallet 的打开入口（实现方定名：AppMe 上参数为 AppWallet、或为
 * (String, JsonObject) -> Unit 的公开 setter / 方法），本测试按同样的形状接；实现若改由 AppWallet 在 connectSession 里自己登记，这里接不到也不影响。
 * 00bdbaf 上 AppMe 没有这类入口、执行器仍打开 Wallet：每个用例都在「Router 收到的是 Withdraw / 不进钱包」的断言上变红（不靠缺类、缺方法）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("TooManyFunctions") // 验收场景逐条一个用例
class MeWithdrawDirectAppTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer
    private lateinit var routing: AppRouting
    private lateinit var navController: NavHostController
    private lateinit var login: AppLogin
    private lateinit var me: AppMe
    private lateinit var wallet: AppWallet
    private val requests = mutableListOf<String>()

    /** 冷启动的 GET /v1/me（AppLogin 的 phone_bound 补查、AppWallet 的实名查询）挂住，直到用例放行或测试结束。 */
    private val coldStart = CountDownLatch(1)

    @Volatile private var holdMe = false

    @Volatile private var realname = "verified"

    @Volatile private var meDelayMs = 0L

    @Volatile private var meFailure: Int? = null

    @Volatile private var rules = RULES_OK

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
                    synchronized(requests) { requests += request.method + " " + path }
                    if (path == ME_PATH && holdMe) coldStart.await(HOLD_S, TimeUnit.SECONDS)
                    return when (path) {
                        ME_PATH -> {
                            meFailure?.let { envelope("null", code = it, trace = FAILED_TRACE) }
                                ?: envelope(meJson()).setBodyDelay(meDelayMs, TimeUnit.MILLISECONDS)
                        }

                        SUMMARY_PATH -> {
                            envelope(SUMMARY)
                        }

                        RULES_PATH -> {
                            envelope(rules)
                        }

                        PAYOUT_PATH -> {
                            envelope(PAYOUT)
                        }

                        WITHDRAWALS_PATH -> {
                            envelope("""{"items":[],"next_cursor":null}""")
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
    fun tearDown() {
        coldStart.countDown()
        server.shutdown()
    }

    // ---------------------------------------------------------------- 搭建（与 AppModule 同一套接线）

    @Suppress("LongMethod") // 外壳与各模块逐项接线
    private fun launch(waitColdStart: Boolean = true) {
        val gate =
            PrivacyGate(MemoryStore(), clock = { NOW }, bundledPolicyVersion = "3").apply {
                retryScheduler = RetryScheduler { _, _ -> }
            }
        val privacy = AppPrivacy(gate, environmentSettled = { true }, clock = { NOW }).also { it.coldStart() }
        val environment = EnvironmentGate { EnvironmentStartupReconciler.Outcome.UNCHANGED }.also { it.settleAtStartup() }
        val tokens = MemorySessionTokens("access-user-one", "refresh-user-one", "full", "user-one")
        routing =
            AppRouting(
                context,
                environment,
                clock = { NOW },
                initialPrivacy = privacy.conclusion,
                privacyHooks = RoutingPrivacyHooks(promptShowing = { privacy.promptShowing }, credentialsReadable = { true }),
                initialUpdate = UpdateCheck.OK,
            )
        val api =
            ApiClient(
                identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.0.0", build = "1", channel = "official"),
                baseUrl = { server.url("/").toString() },
                context = { NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, UpdateRaw.SUPPORTED) },
                device = Device,
                tokens = tokens,
                pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { NOW },
                hooks = NetworkHooks(navigationTicket = { routing.navTickets.ticket() }),
                inFlight = InFlightCalls(),
                sleeper = {},
            )
        val store =
            ConfigStore(
                readFile = { null },
                writeFile = {},
                fetcher = { _, done -> done(ConfigReply(ConfigReply.OK, "{}", null)) },
                clock = { NOW },
                bundled = "{}",
            )
        composeRule.runOnUiThread {
            login =
                AppLogin(api, tokens, routing, updateCheck = {
                    UpdateCheck.OK
                }, markBelow = {}, legal = LegalVersions(3, 2), pendingInvite = PendingInviteCode())
            login.syncSession()
            wallet =
                AppWallet(
                    api = ApiClientWalletApi(api),
                    store = store,
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
                    phoneBound = { account -> login.phoneBound(account) },
                    clock = { NOW },
                    meRead = { login.meReadGate },
                    sessionScope = { api.activeSessionScope() ?: "full" },
                ).also { it.connectSession() }
            me =
                AppMe(
                    client = api,
                    tokens = tokens,
                    routing = routing,
                    privacy = privacy,
                    store = store,
                    login = { login },
                    updateCheck = { UpdateCheck.OK },
                    serverForced = {},
                    writer = { false },
                    local = MeLocalStore(context),
                    mask = null,
                    system = NoSystem,
                    pushSource = NoPushTokenSource(),
                    nickname = null,
                    notify = null,
                    clock = { NOW },
                ).also { it.connect { } }
            wallet.customerService = me.cs::open
            bindMeToWallet()
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
                me = me,
            )
        }
        settle()
        if (waitColdStart) {
            // 冷启动查清 phone_bound 与 realname_status（AppLogin、AppWallet 各取一次 /v1/me）
            idleUntil { login.phoneChecksStarted > 0 && login.phoneChecksSettled() && wallet.realnameVerified() }
        }
    }

    /**
     * 「我的」→ AppWallet 打开路径的接线，照 AppModule.appMe 的形状接：AppMe 上参数为 AppWallet 的公开方法，或参数为 (String, JsonObject) -> Unit 的公开
     * setter。00bdbaf 上一个都没有（返回 0，不断言），用例由行为断言判红。
     */
    private fun bindMeToWallet(): Int {
        val opener: (String, JsonObject) -> Unit = { route, params -> wallet.open(route, params) }
        var bound = 0
        AppMe::class.java.methods
            .filter { it.declaringClass == AppMe::class.java && it.parameterTypes.size == 1 }
            .forEach { method ->
                val type = method.parameterTypes.single()
                if (type == AppWallet::class.java) {
                    method.invoke(me, wallet)
                    bound += 1
                } else if (type == Function2::class.java && method.name.startsWith("set")) {
                    method.invoke(me, opener)
                    bound += 1
                }
            }
        return bound
    }

    // ---------------------------------------------------------------- 工具

    private fun settle() {
        repeat(SETTLE_ROUNDS) {
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.waitForIdle()
            Thread.sleep(POLL_MS)
        }
    }

    private fun pump() {
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()
        Thread.sleep(POLL_MS)
    }

    private fun idleUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S)
        while (!condition() && System.nanoTime() < deadline) pump()
        assertTrue("栈 ${stack()}，最近一次路由 ${outcome()?.request?.route}，请求 ${lines()}", condition())
    }

    private fun lines(): List<String> = synchronized(requests) { requests.toList() }

    private fun count(path: String): Int = synchronized(requests) { requests.count { it == "GET $path" } }

    private fun has(tag: String): Boolean = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun text(substring: String): Int =
        composeRule.onAllNodesWithText(substring, substring = true, useUnmergedTree = true).fetchSemanticsNodes().size

    @SuppressLint("RestrictedApi")
    private fun stack(): List<String> =
        navController.currentBackStack.value
            .filter { it.destination.hasRoute(RouteDestination::class) }
            .map { it.toRoute<RouteDestination>().name }

    private fun top(): String? = stack().lastOrNull()

    private fun outcome(): RouteOutcome? = routing.router.lastOutcome.value

    private fun open(route: String) {
        composeRule.runOnUiThread { routing.router.open(RouteRequest(route, JsonObject(emptyMap()), RouteSource.IN_APP)) }
        settle()
    }

    /** 进「我的」，等余额卡【提现】与本身份的 Me 快照都在。 */
    private fun openMe() {
        open(ME_ROUTE)
        idleUntil { has(MeTags.ME_WITHDRAW) && me.deps.repo.meOf(me.identity.value) != null }
    }

    /** 点一次「我的」【提现】（不额外推进：后续由各用例按条件等）。 */
    private fun tapMeWithdraw() {
        val node = composeRule.onNodeWithTag(MeTags.ME_WITHDRAW, useUnmergedTree = true)
        runCatching { node.performScrollTo() }
        node.performClick()
    }

    private fun intoWallet(): Boolean = outcome()?.request?.route == WalletRoutes.WALLET || WalletRoutes.WALLET in stack()

    /** 提现页上 rules 拦截的结论：引导区里的文字 + 【提交】是否可用。 */
    private fun withdrawVerdict(): List<String> {
        val inGuide = hasTestTag(WalletTags.WITHDRAW_GUIDE) or hasAnyAncestor(hasTestTag(WalletTags.WITHDRAW_GUIDE))
        val guide =
            composeRule
                .onAllNodes(inGuide, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
        val submit = composeRule.onNodeWithTag(WalletTags.WITHDRAW_SUBMIT, useUnmergedTree = true).fetchSemanticsNode()
        return guide + "submit_enabled=${SemanticsProperties.Disabled !in submit.config}"
    }

    // ---------------------------------------------------------------- 用例

    /** 本会话还没查过实名：「我的」【提现】先经 AppWallet 取 GET /v1/me，查回来已实名再打开 Withdraw（不进钱包、查询回来之前不导航）。 */
    @Test
    fun unknownRealnameQueriesMeFirstThenOpensWithdraw() {
        launch()
        openMe()
        composeRule.runOnUiThread { wallet.forgetRealname() }
        meDelayMs = LATE_MS
        val queries = count(ME_PATH)
        val before = outcome()
        tapMeWithdraw()
        idleUntil { count(ME_PATH) > queries || outcome() !== before }
        assertFalse("「我的」【提现】不再经钱包", intoWallet())
        assertTrue("本会话没查过实名：先取 GET /v1/me（与钱包【提现】同一条 AppWallet 打开路径）", count(ME_PATH) > queries)
        assertFalse("实名查询回来之前不打开资金页", WalletRoutes.WITHDRAW in stack())
        idleUntil { top() == WalletRoutes.WITHDRAW }
        assertEquals(WalletRoutes.WITHDRAW, outcome()?.request?.route)
        assertEquals("经 Router 以 App 内来源打开、守卫放行", GuardKind.OPEN, outcome()?.decision?.kind)
        assertEquals(RouteSource.IN_APP, outcome()?.request?.source)
        assertFalse(intoWallet())
        composeRule.runOnUiThread { assertTrue("查到的实名结论记入本会话", wallet.realnameVerified()) }
    }

    /** 查到未实名：照现有行为由守卫引导去 RealName，不打开提现页、不进钱包。 */
    @Test
    fun unverifiedRealnameIsGuidedToRealName() {
        launch()
        openMe()
        composeRule.runOnUiThread { wallet.forgetRealname() }
        realname = "none"
        val queries = count(ME_PATH)
        val before = outcome()
        tapMeWithdraw()
        idleUntil { outcome() !== before }
        assertFalse("「我的」【提现】不再经钱包", intoWallet())
        assertEquals("Router 收到的是 Withdraw", WalletRoutes.WITHDRAW, outcome()?.request?.route)
        assertEquals("守卫按查到的未实名引导实名", GuardKind.REQUIRE_REALNAME, outcome()?.decision?.kind)
        idleUntil { top() == WalletRoutes.REAL_NAME }
        assertTrue("先查了 GET /v1/me", count(ME_PATH) > queries)
        assertFalse(WalletRoutes.WITHDRAW in stack())
        composeRule.runOnUiThread { assertFalse(wallet.realnameVerified()) }
    }

    /**
     * 冷启动（iOS FU-IOS-PHONE-BINDING-COLDSTART 同类）：phone_bound 与实名都还没查清（冷启动的两次 GET /v1/me 挂住）时，首点「我的」【提现】就有反应——
     * Router 收到 Withdraw 并给出导航类结论（打开或前置引导），不静默、不要第二次点。
     */
    @Test
    fun coldStartFirstTapWhilePhoneAndRealnameUnknownIsNotSilent() {
        holdMe = true
        launch(waitColdStart = false)
        idleUntil { count(ME_PATH) >= COLD_START_QUERIES }
        holdMe = false
        assertEquals("前提：phone_bound 还没查清", SessionState.LOGGED_IN, routing.session.value)
        composeRule.runOnUiThread { assertFalse("前提：实名还没查清", wallet.realnameVerified()) }
        openMe()
        val before = outcome()
        tapMeWithdraw()
        idleUntil { outcome() !== before }
        val first = outcome()
        assertNotNull(first)
        assertEquals("首点就交给 Router 打开提现页（不是钱包）", WalletRoutes.WITHDRAW, first?.request?.route)
        assertTrue("首点有反应：导航到提现页或前置引导页，不静默（${first?.decision?.kind}）", first?.decision?.kind?.let { it in NAVIGATING } == true)
        assertFalse(intoWallet())
        coldStart.countDown()
    }

    /** 实名前置查询失败（50001）：不当成未实名继续导航、不进钱包，发起页（「我的」）给出带 trace6 的错误，不静默。 */
    @Test
    fun realnameQueryFailureFromMeShowsAnErrorInsteadOfSilence() {
        launch()
        openMe()
        composeRule.runOnUiThread { wallet.forgetRealname() }
        meFailure = SERVER_ERROR
        val queries = count(ME_PATH)
        val before = outcome()
        tapMeWithdraw()
        idleUntil { has(WalletTags.OPEN_ERROR) || text(FAILED_TRACE6) > 0 || outcome() !== before }
        assertFalse("「我的」【提现】不再经钱包", intoWallet())
        assertSame("查询失败：不导航（也不当成未实名去 RealName）", before, outcome())
        assertTrue("先查了 GET /v1/me", count(ME_PATH) > queries)
        assertTrue("「我的」上给出带 trace6 的错误，不静默", has(WalletTags.OPEN_ERROR) || text(FAILED_TRACE6) > 0)
    }

    /**
     * 提现页 rules 前置的 1 条集成断言（不重写提现页已有测试）：rules 给 30303 account_frozen 时，从「我的」进入与从钱包进入的拦截结果一致
     * （引导区文字、【提交】置灰、各自只取一次 rules）。
     */
    @Test
    fun rulesBlockFromMeMatchesRulesBlockFromWallet() {
        rules = RULES_FROZEN
        launch()
        openMe()
        val fromMeBefore = count(RULES_PATH)
        val before = outcome()
        tapMeWithdraw()
        idleUntil { outcome() !== before }
        assertEquals("从「我的」直达提现页", WalletRoutes.WITHDRAW, outcome()?.request?.route)
        idleUntil { top() == WalletRoutes.WITHDRAW && has(WalletTags.WITHDRAW_GUIDE) && has(WalletTags.WITHDRAW_SUBMIT) }
        val fromMe = withdrawVerdict()
        val fromMeRules = count(RULES_PATH) - fromMeBefore
        composeRule.runOnUiThread { navController.popBackStack() }
        settle()
        open(WalletRoutes.WALLET)
        idleUntil { has(WalletTags.WALLET_WITHDRAW) }
        val fromWalletBefore = count(RULES_PATH)
        composeRule.onNodeWithTag(WalletTags.WALLET_WITHDRAW, useUnmergedTree = true).performClick()
        idleUntil { top() == WalletRoutes.WITHDRAW && has(WalletTags.WITHDRAW_GUIDE) && has(WalletTags.WITHDRAW_SUBMIT) }
        val fromWallet = withdrawVerdict()
        assertEquals("rules 拦截结果与从钱包进入一致", fromWallet, fromMe)
        assertEquals("各自只取一次 rules（「我的」不预取）", count(RULES_PATH) - fromWalletBefore, fromMeRules)
        assertTrue("引导区有文字", fromMe.size > 1)
        assertEquals("冻结时【提交】置灰", "submit_enabled=false", fromMe.last())
    }

    private fun meJson(): String =
        """{"user_id":"user-one","nickname":"凑狸用户","avatar_url":"","invite_code":null,""" +
            """"inviter_bound":true,"invite_backfill":{"eligible":false,"deadline_at":null},""" +
            """"identity_level":"normal","phone_bound":true,""" +
            """"balance":{"available_fen":1234,"withdrawable_fen":1234,"estimated_total_fen":5678},"union_bindings":[],""" +
            """"realname_status":"$realname","need_reconsent":false,""" +
            """"risk":{"state":"normal","reason_category":null,"frozen_until":null}}"""

    private fun envelope(
        data: String,
        code: Int = 0,
        trace: String = "trace-000123",
    ) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"code":$code,"msg":"","data":$data,"trace_id":"$trace"}""")

    private companion object {
        const val NOW = 1_791_000_000_000L
        const val WAIT_S = 10L
        const val HOLD_S = 8L
        const val POLL_MS = 20L
        const val SETTLE_ROUNDS = 5
        const val LATE_MS = 2_000L
        const val COLD_START_QUERIES = 2
        const val SERVER_ERROR = 50001
        const val ME_ROUTE = "Me"
        const val ME_PATH = "/v1/me"
        const val SUMMARY_PATH = "/v1/wallet/summary"
        const val RULES_PATH = "/v1/withdrawals/rules"
        const val PAYOUT_PATH = "/v1/me/payout-account"
        const val WITHDRAWALS_PATH = "/v1/withdrawals"
        const val FAILED_TRACE = "trace-me-ABC999"
        const val FAILED_TRACE6 = "ABC999"

        /** 有反应的守卫结论：打开，或进入前置引导页（登录 / 绑手机 / 实名）。 */
        val NAVIGATING =
            setOf(GuardKind.OPEN, GuardKind.REQUIRE_LOGIN, GuardKind.REQUIRE_BIND_PHONE, GuardKind.REQUIRE_REALNAME)

        const val SUMMARY =
            """{"available_fen":1234,"withdrawable_fen":1234,"negative_fen":0,"frozen_fen":0,""" +
                """"estimated_total_fen":5678,"estimated_fen":2222,"pending_credit_fen":0,""" +
                """"pending_credit_paused_fen":0,"next_credit_period":null,""" +
                """"credit_overdue":false,"withdrawn_fen":0,"risk_paused_reason":null,"withdraw_enabled":true}"""

        const val PAYOUT =
            """{"payout_method":"alipay","masked_account":"138****5678","masked_payee_name":"**三","change_remaining_this_month":2}"""

        const val RULES_OK =
            """{"can_withdraw":true,"block_code":null,"block_reason":null,"max_withdrawable_fen":1200,""" +
                """"withdrawable_fen":1234,"daily_remaining":1,"monthly_remaining":10,""" +
                """"est_fee_fen":null,"est_tax_fen":null,"est_net_fen":null,"quick_amounts_fen":[500,1000]}"""

        /** 风控冻结：rules 给 can_withdraw=false、block_code=30303（account_frozen）。 */
        const val RULES_FROZEN =
            """{"can_withdraw":false,"block_code":30303,"block_reason":"account_frozen","max_withdrawable_fen":0,""" +
                """"withdrawable_fen":1234,"daily_remaining":1,"monthly_remaining":10,"est_fee_fen":null,"est_tax_fen":null,""" +
                """"est_net_fen":null,"quick_amounts_fen":[]}"""
    }
}
