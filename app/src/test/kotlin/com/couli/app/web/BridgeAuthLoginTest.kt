package com.couli.app.web

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
import com.couli.core.bridge.BridgeCallValidity
import com.couli.core.bridge.BridgeCallerState
import com.couli.core.bridge.BridgeHost
import com.couli.core.bridge.BridgeOutcome
import com.couli.core.bridge.BridgePrivacy
import com.couli.core.bridge.BridgeProtocol
import com.couli.core.bridge.BridgeServices
import com.couli.core.bridge.BridgeUi
import com.couli.core.bridge.BridgeUpdateState
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.network.ApiClient
import com.couli.core.network.ClientIdentity
import com.couli.core.network.DeviceCredentials
import com.couli.core.network.MemoryPendingKeyBackend
import com.couli.core.network.MemorySessionTokens
import com.couli.core.network.NetworkContext
import com.couli.core.network.NetworkHooks
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PrivacyRaw
import com.couli.core.network.SessionScopeRaw
import com.couli.core.network.UpdateRaw
import com.couli.core.privacy.PrivacyGate
import com.couli.core.privacy.PrivacyStore
import com.couli.core.privacy.RetryScheduler
import com.couli.core.router.RouteNames
import com.couli.core.router.SessionState
import com.couli.core.router.SharedPreferencesPendingBackend
import com.couli.core.router.UpdateCheck
import com.couli.feature.login.LegalVersions
import com.couli.feature.login.LoginOutcome
import com.couli.feature.login.PendingInviteCode
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

/**
 * Android 第 2 轮评审 R2-01（APP-04 共性第 66 条；bridge.schema.json auth.login「Opens Login; resolves when the user finishes or
 * leaves」）：真实 BridgeHost → [BridgeLogin]（AppWeb 的 auth.login 同一实现）→ AppRouting / AppShell / AppLogin → 本地假服务。
 * - 桥登录成功：登录页打开期间不回复；用户完成短信登录、会话已保存之后才以 logged_in = true 回复——本次登录引起的会话变化不把调用作废；
 * - 桥登录取消：用户关掉登录页之后才以 logged_in = false 回复。
 * - 第 3 轮评审 R3-01：同一宿主取消后再次 auth.login（中间没有别的跳转，Router 的结论与上次相同）仍等这次的登录页结束才回复。
 * - APP-05b R1-06：未绑手机的有效 full 会话（LOGGED_IN）算已登录。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class BridgeAuthLoginTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = 5_000_000L
    private lateinit var server: MockWebServer
    private lateinit var routing: AppRouting
    private lateinit var login: AppLogin
    private lateinit var api: ApiClient
    private lateinit var host: BridgeHost
    private lateinit var navController: NavHostController
    private val tokens = MemorySessionTokens()
    private val replies: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private class MemoryStore : PrivacyStore {
        override var decisionRaw: String = "consented"
        override var consentedVersion: String? = "1"
        override var consentedAtMillis: Long? = 0L
        override var basicMode: Boolean = false
    }

    @Before
    fun setUp() {
        SharedPreferencesPendingBackend(context).delete()
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) =
                    envelope("""{"user_id":"user-one","is_new_user":false,"tokens":{${pair()}}}""")
            }
        server.start()
        launch()
    }

    @After
    fun tearDown() = server.shutdown()

    /** 按 AppModule 的接线启动外壳（游客），再建一个可信容器的 BridgeHost，auth.login 走 AppWeb 同一实现。 */
    private fun launch() {
        val gate =
            PrivacyGate(MemoryStore(), clock = { now }, bundledPolicyVersion = "1").apply {
                withdrawalCleanup = {}
                retryScheduler = RetryScheduler { _, _ -> }
            }
        val privacy = AppPrivacy(gate, environmentSettled = { true }, clock = { now }).also { it.coldStart() }
        val environment = EnvironmentGate { EnvironmentStartupReconciler.Outcome.UNCHANGED }.also { it.settleAtStartup() }
        routing =
            AppRouting(
                context,
                environment,
                clock = { now },
                initialPrivacy = privacy.conclusion,
                privacyHooks = RoutingPrivacyHooks(promptShowing = { privacy.promptShowing }),
            )
        val main = Handler(Looper.getMainLooper())
        api =
            ApiClient(
                identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.0.0", build = "1", channel = "official"),
                baseUrl = { server.url("/").toString() },
                context = { NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, UpdateRaw.SUPPORTED) },
                device = FixtureDevice,
                tokens = tokens,
                pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
                hooks =
                    NetworkHooks(
                        onEffect = { effect -> main.post { login.onNetworkEffect(effect) } },
                        onSessionStored = { generation -> main.post { login.onSessionStored(generation) } },
                    ),
                sleeper = {},
            )
        composeRule.runOnUiThread {
            login =
                AppLogin(api, tokens, routing, updateCheck = {
                    UpdateCheck.OK
                }, markBelow = {}, legal = LegalVersions(1, 1), pendingInvite = PendingInviteCode())
            host = bridgeHost()
        }
        composeRule.setContent {
            navController = rememberNavController()
            AppShell(
                routing = routing,
                privacy = privacy,
                homeContent = { Box(Modifier.fillMaxSize()) },
                navController = navController,
                login = login,
            )
        }
        composeRule.waitForIdle()
    }

    /** 可信容器的 BridgeHost：auth.login 走 AppWeb 同一实现（[BridgeLogin]）。 */
    private fun bridgeHost(): BridgeHost {
        val bridgeLogin = BridgeLogin({ login }, routing) { 0L }
        val services =
            object : BridgeServices by NoServices {
                override suspend fun login(update: String): BridgeOutcome = bridgeLogin.open(update).first

                override suspend fun login(
                    update: String,
                    validity: BridgeCallValidity,
                    opening: suspend (suspend () -> BridgeOutcome) -> BridgeOutcome,
                ): BridgeOutcome = bridgeLogin.run(update, validity, opening)
            }
        return BridgeHost(
            protocol = { BridgeProtocol(setOf(ORIGIN), JsonObject(emptyMap()), emptySet()) },
            caller = {
                BridgeCallerState(
                    BridgePrivacy.CONSENTED,
                    accountKey = api.activeUserId(),
                    update = BridgeUpdateState.SUPPORTED,
                    sessionEpoch = tokens.loginEpoch,
                )
            },
            services = services,
            ui = NoUi,
            awaitUpdate = { BridgeUpdateState.SUPPORTED },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
    }

    @SuppressLint("RestrictedApi")
    private fun routes(): List<String> =
        navController.currentBackStack.value
            .filter { it.destination.hasRoute(RouteDestination::class) }
            .map { it.toRoute<RouteDestination>().name }

    private fun callAuthLogin(id: String = "login-1") {
        composeRule.runOnUiThread {
            host.handle("""{"v":1,"id":"$id","method":"auth.login","params":{}}""", ORIGIN, true, false) { replies += it }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun bridgeLoginResolvesTrueOnlyAfterTheSmsLoginSavedTheSession() {
        assertEquals(SessionState.GUEST, routing.session.value)
        callAuthLogin()
        assertEquals("打开了登录页", RouteNames.LOGIN, routes().last())
        settleMain()
        assertTrue("登录页还开着：不回复", replies.isEmpty())

        // 用户在登录页完成短信登录（真实执行器：保存会话 → 登录后计划 → 关闭登录页）
        var outcome: LoginOutcome? = null
        composeRule.runOnUiThread {
            CoroutineScope(Dispatchers.Main.immediate).launch {
                outcome = login.service.login("13800138000", "123456", now, restricted = false)
            }
        }
        idleUntil { replies.isNotEmpty() && outcome != null }

        assertEquals(LoginOutcome.LOGGED_IN, outcome?.stateRaw)
        assertEquals("会话已保存", "access-one", tokens.accessToken)
        val reply = Json.parseToJsonElement(replies.single()).jsonObject
        assertEquals("本次登录引起的会话变化不把调用作废", 0, reply.getValue("code").jsonPrimitive.int)
        assertTrue(
            reply
                .getValue("data")
                .jsonObject
                .getValue("logged_in")
                .jsonPrimitive.boolean,
        )
        assertFalse("登录页已关闭", RouteNames.LOGIN in routes())
    }

    @Test
    fun bridgeLoginResolvesFalseOnlyAfterTheUserLeavesTheLoginPage() {
        callAuthLogin()
        assertEquals(RouteNames.LOGIN, routes().last())
        settleMain()
        assertTrue("登录页还开着：不回复", replies.isEmpty())

        // 用户关掉登录页（不登录）
        composeRule.runOnUiThread { navController.popBackStack() }
        idleUntil { replies.isNotEmpty() }

        val reply = Json.parseToJsonElement(replies.single()).jsonObject
        assertEquals(0, reply.getValue("code").jsonPrimitive.int)
        assertFalse(
            reply
                .getValue("data")
                .jsonObject
                .getValue("logged_in")
                .jsonPrimitive.boolean,
        )
        assertEquals(SessionState.GUEST, routing.session.value)
    }

    @Test
    fun bridgeLoginRetriedAfterCancelWaitsForTheSecondLoginPage() {
        callAuthLogin("login-1")
        assertEquals(RouteNames.LOGIN, routes().last())
        settleMain()
        composeRule.runOnUiThread { navController.popBackStack() }
        idleUntil { replies.isNotEmpty() }
        assertFalse(loggedIn(replies.single()))

        // 同一 H5 再次调用 auth.login：中间没有别的跳转，Router 给出与上次相同的结论
        callAuthLogin("login-2")
        assertEquals("第二次也打开了登录页", RouteNames.LOGIN, routes().last())
        settleMain()
        assertEquals("第二次登录完成前不回复", 1, replies.size)

        var outcome: LoginOutcome? = null
        composeRule.runOnUiThread {
            CoroutineScope(Dispatchers.Main.immediate).launch {
                outcome = login.service.login("13800138000", "123456", now, restricted = false)
            }
        }
        idleUntil { replies.size == 2 && outcome != null }

        assertEquals(LoginOutcome.LOGGED_IN, outcome?.stateRaw)
        val reply = Json.parseToJsonElement(replies.last()).jsonObject
        assertEquals("login-2", reply.getValue("id").jsonPrimitive.content)
        assertEquals(0, reply.getValue("code").jsonPrimitive.int)
        assertTrue("保存会话后 logged_in = true", loggedIn(replies.last()))
    }

    /**
     * APP-05b 共性第 18 条（R1-06）：有效的 full 会话、守卫状态是 LOGGED_IN（未绑手机：第三方新号跳过引导、或冷启动 phone_bound 未知 /
     * 查到 false）时，可信 H5 调 auth.login → 不打开登录页，直接 logged_in = true。
     */
    @Test
    fun bridgeLoginWithAValidUnboundFullSessionResolvesTrueWithoutOpeningLogin() {
        composeRule.runOnUiThread {
            tokens.save("access-one", "refresh-one", SessionScopeRaw.FULL, "user-one")
            login.phoneBinding.set(tokens.loginEpoch, false)
            login.syncSession()
        }
        composeRule.waitForIdle()
        assertEquals(SessionState.LOGGED_IN, routing.session.value)

        callAuthLogin()
        idleUntil { replies.isNotEmpty() }

        assertFalse("不打开登录页", RouteNames.LOGIN in routes())
        val reply = Json.parseToJsonElement(replies.single()).jsonObject
        assertEquals(0, reply.getValue("code").jsonPrimitive.int)
        assertTrue("有效 full 会话算已登录", loggedIn(replies.single()))
    }

    private fun loggedIn(raw: String): Boolean =
        Json
            .parseToJsonElement(raw)
            .jsonObject
            .getValue("data")
            .jsonObject
            .getValue("logged_in")
            .jsonPrimitive.boolean

    /** 推进主线程几轮（给「提前回复」的实现机会暴露出来）。 */
    private fun settleMain() {
        repeat(SETTLE_ROUNDS) {
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.waitForIdle()
            Thread.sleep(IDLE_POLL_MS)
        }
    }

    private fun idleUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.waitForIdle()
            Thread.sleep(IDLE_POLL_MS)
        }
        assertTrue(condition())
    }

    private fun envelope(data: String) =
        MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("""{"code":0,"msg":"","data":$data,"trace_id":"trace-000001"}""")

    private fun pair() =
        """"access_token":"access-one","access_expires_at":"2099-01-01T00:00:00+08:00",""" +
            """"refresh_token":"refresh-one","refresh_expires_at":"2099-01-01T00:00:00+08:00","session_scope":"${SessionScopeRaw.FULL}""""

    private object FixtureDevice : DeviceCredentials {
        override val deviceId: String = "device-fixture"
        override val installSecret: String = "install-fixture"
    }

    private object NoUi : BridgeUi {
        override fun toast(
            text: String,
            long: Boolean,
        ) = Unit

        override fun showLoading(text: String?) = Unit

        override fun hideLoading() = Unit

        override fun setNavBar(params: JsonObject) = true

        override fun close() = Unit
    }

    private object NoServices : BridgeServices {
        override fun env() = JsonObject(emptyMap())

        override fun config(keys: List<String>) = BridgeOutcome.Ok()

        override suspend fun user(validity: BridgeCallValidity) = BridgeOutcome.Ok()

        override suspend fun h5Token(
            readOnly: Boolean,
            validity: BridgeCallValidity,
        ) = BridgeOutcome.Ok()

        override suspend fun login(update: String) = BridgeOutcome.Ok()

        override suspend fun signedRequest(
            method: String,
            path: String,
            body: JsonElement?,
            validity: BridgeCallValidity,
        ) = BridgeOutcome.Ok()

        override suspend fun navOpen(
            route: String,
            params: JsonObject,
            update: String,
        ) = BridgeOutcome.Ok()

        override fun clipboardWrite(text: String) = BridgeOutcome.Ok()

        override suspend fun clipboardRead() = BridgeOutcome.Ok()

        override fun setClipboardAutoDetect(enabled: Boolean) = BridgeOutcome.Ok()

        override suspend fun pushStatus() = BridgeOutcome.Ok()

        override suspend fun requestPermission(
            type: String,
            validity: BridgeCallValidity,
        ) = BridgeOutcome.Ok()

        override fun track(
            name: String,
            props: JsonObject,
        ) = BridgeOutcome.Ok()

        override fun reportRejected(
            method: String,
            code: Int,
        ) = Unit
    }

    private companion object {
        const val ORIGIN = "https://h5.example.test"
        const val WAIT_S = 5L
        const val IDLE_POLL_MS = 10L
        const val SETTLE_ROUNDS = 5
    }
}
