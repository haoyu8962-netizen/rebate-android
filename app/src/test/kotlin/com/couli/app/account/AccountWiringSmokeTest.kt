package com.couli.app.account

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
import com.couli.app.me.AppMe
import com.couli.app.me.MeSystemPorts
import com.couli.app.privacy.AppPrivacy
import com.couli.app.routing.AppRouting
import com.couli.app.routing.RouteDestination
import com.couli.app.routing.RoutingPrivacyHooks
import com.couli.app.shell.AppShell
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
import com.couli.core.router.RouteRequest
import com.couli.core.router.RouteSource
import com.couli.core.router.UpdateCheck
import com.couli.feature.login.LegalVersions
import com.couli.feature.login.PendingInviteCode
import com.couli.feature.me.AccountTags
import com.couli.feature.me.MeTags
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * APP-13a 接线冒烟（第 3 阶段）：真实 AppShell + AppRouting + AppLogin + AppMe + AppAccount + ApiClient（传输层换成 MockWebServer）。
 * - DeleteAccount 经真实 Router 打开（Debug 路由可用性覆盖开），参数被丢弃；覆盖关时 App 内打开只提示 error.10007、不导航（G01 ③）；
 * - 设置页「注销账号」行在覆盖开 / 关时显示 / 隐藏（G01 ①）。
 * 全面集成测试在下一阶段。令牌、账号都是明显的测试值。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccountWiringSmokeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer
    private lateinit var routing: AppRouting
    private lateinit var navController: NavHostController
    private lateinit var me: AppMe
    private lateinit var account: AppAccount
    private val requests = mutableListOf<String>()

    @Volatile private var override: Set<String> = emptySet()

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
        listOf(MeLocalStore.NAMESPACE, MeLocalStore.PENDING_NAMESPACE).forEach {
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
                    val data =
                        when (path) {
                            "/v1/me" -> {
                                """{"user_id":"user-one","nickname":"凑狸用户","avatar_url":"","invite_code":null,""" +
                                    """"inviter_bound":true,"invite_backfill":{"eligible":false,"deadline_at":null},""" +
                                    """"identity_level":"normal","phone_bound":true,""" +
                                    """"balance":{"available_fen":1234,"withdrawable_fen":1234,""" +
                                    """"estimated_total_fen":5678},"union_bindings":[],""" +
                                    """"realname_status":"verified","need_reconsent":false,""" +
                                    """"risk":{"state":"normal","reason_category":null,"frozen_until":null}}"""
                            }

                            "/v1/me/deletion" -> {
                                "null"
                            }

                            "/v1/wallet/summary" -> {
                                """{"available_fen":1234,"withdrawable_fen":1234,"negative_fen":0,"frozen_fen":0,""" +
                                    """"estimated_total_fen":5678,"estimated_fen":2222,"pending_credit_fen":0,""" +
                                    """"pending_credit_paused_fen":0,"next_credit_period":null,""" +
                                    """"credit_overdue":false,"withdrawn_fen":0,"risk_paused_reason":null,"withdraw_enabled":true}"""
                            }

                            else -> {
                                "{}"
                            }
                        }
                    return MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"code":0,"msg":"","data":$data,"trace_id":"trace-000123"}""")
                }
            }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    @Suppress("LongMethod") // 外壳与各模块逐项接线
    private fun launch() {
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
            ).also { it.availabilityOverrides = { override } }
        val inFlight = InFlightCalls()
        val api =
            ApiClient(
                identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.0.0", build = "1", channel = "official"),
                baseUrl = { server.url("/").toString() },
                context = { NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, UpdateRaw.SUPPORTED) },
                device = Device,
                tokens = tokens,
                pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { NOW },
                hooks = NetworkHooks(navigationTicket = { routing.navTickets.ticket() }),
                inFlight = inFlight,
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
        lateinit var login: AppLogin
        composeRule.runOnUiThread {
            login =
                AppLogin(api, tokens, routing, updateCheck = {
                    UpdateCheck.OK
                }, markBelow = {}, legal = LegalVersions(3, 2), pendingInvite = PendingInviteCode())
            login.syncSession()
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
            account =
                AppAccount(
                    client = api,
                    tokens = tokens,
                    routing = routing,
                    me = me,
                    wallet = null,
                    login = login,
                    stepUpPhone = { "13800005678" },
                    updateCheck = { UpdateCheck.OK },
                    serverForced = {},
                    clock = { NOW },
                )
            me.account = account
            account.sync()
        }
        composeRule.setContent {
            navController = rememberNavController()
            AppShell(
                routing = routing,
                privacy = privacy,
                homeContent = { Box(Modifier.fillMaxSize()) },
                navController = navController,
                login = login,
                me = me,
            )
        }
        settle()
    }

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
        assertTrue("栈 ${stack()}，请求 ${synchronized(requests) { requests.toList() }}", condition())
    }

    private fun has(tag: String): Boolean = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @SuppressLint("RestrictedApi")
    private fun stack(): List<String> =
        navController.currentBackStack.value
            .filter { it.destination.hasRoute(RouteDestination::class) }
            .map { it.toRoute<RouteDestination>().name }

    private fun open(
        route: String,
        params: JsonObject = JsonObject(emptyMap()),
    ) {
        composeRule.runOnUiThread { routing.router.open(RouteRequest(route, params, RouteSource.IN_APP)) }
        settle()
    }

    @Test
    fun deleteAccountOpensThroughTheRealRouterWithParametersDroppedWhenAvailable() {
        override = setOf("DeleteAccount")
        launch()
        open("DeleteAccount", JsonObject(mapOf("phone" to JsonPrimitive("13900001234"))))
        idleUntil { stack().lastOrNull() == "DeleteAccount" && has(AccountTags.DELETE_SUBMIT) }
        val outcome = routing.router.lastOutcome.value
        assertEquals(GuardKind.OPEN, outcome?.decision?.kind)
        assertTrue("参数被丢弃", outcome?.decision?.params?.isEmpty() == true)
        assertEquals(1, outcome?.decision?.paramsDropped)
        // 进页取进度、钱包摘要；不发 requestDeletion
        idleUntil { synchronized(requests) { "GET /v1/me/deletion" in requests && "GET /v1/wallet/summary" in requests } }
        assertFalse(synchronized(requests) { requests.any { it == "POST /v1/me/deletion" } })
    }

    @Test
    fun anUnavailableDeleteAccountOnlyPromptsAndDoesNotNavigate() {
        override = emptySet()
        launch()
        val before = stack()
        val prompts = routing.deletionPrompt.value
        open("DeleteAccount")
        assertEquals("不导航（不进升级页）", before, stack())
        assertEquals(prompts + 1, routing.deletionPrompt.value)
    }

    @Test
    fun theSettingsDeletionRowFollowsRouteAvailability() {
        override = emptySet()
        launch()
        open("Settings")
        idleUntil { has(MeTags.SETTINGS_PAGE) && has(MeTags.SETTINGS_ROW_PREFIX + "settings.phone") }
        assertFalse("since=null 时不显示", has(DELETE_ROW))
        composeRule.runOnUiThread { navController.popBackStack() }
        override = setOf("DeleteAccount")
        open("Settings")
        idleUntil { has(DELETE_ROW) }
        composeRule.onNodeWithTag(DELETE_ROW, useUnmergedTree = true).performScrollTo().performClick()
        settle()
        idleUntil { stack().lastOrNull() == "DeleteAccount" }
    }

    private companion object {
        const val NOW = 1_791_000_000_000L
        const val WAIT_S = 10L
        const val POLL_MS = 20L
        const val SETTLE_ROUNDS = 5
        const val DELETE_ROW = MeTags.SETTINGS_ROW_PREFIX + "settings.delete_account"
    }
}
