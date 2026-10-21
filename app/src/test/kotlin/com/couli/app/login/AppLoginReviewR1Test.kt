package com.couli.app.login

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import com.couli.app.shell.AppShell
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.network.ApiCall
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
import com.couli.core.router.RouteRequest
import com.couli.core.router.RouteSource
import com.couli.core.router.SessionState
import com.couli.core.router.SharedPreferencesPendingBackend
import com.couli.core.router.UpdateCheck
import com.couli.feature.login.LegalVersions
import com.couli.feature.login.PendingInviteCode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Android 第 1 轮评审 R1-01、R1-02，经真实 AppShell + NavHost + AppRouting + AppLogin（接线同 AppModule：ApiClient 的
 * NetworkHooks.onSessionStored 切到主线程交给 [AppLogin.onSessionStored]）与本地假服务 MockWebServer 复现：
 * - R1-01（共性第 13 条）：设置页确认退出、logout 请求还慢着时就返回离开设置页 → 本机会话与待办照样清理，回到游客；
 * - R1-02（共性第 14 条）：当前是受限会话（deletion_only）、保留着待办，版本检查变为 supported 后网络层异步刷新成 full →
 *   保存完成的会话通知让 Router 同步为已登录，并经守卫恢复保留的待办。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class AppLoginReviewR1Test {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = 5_000_000L
    private lateinit var server: MockWebServer
    private lateinit var routing: AppRouting
    private lateinit var login: AppLogin
    private lateinit var api: ApiClient
    private lateinit var navController: NavHostController
    private val tokens = MemorySessionTokens()

    @Volatile
    private var answer: (RecordedRequest) -> MockResponse = { envelope("{}") }

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
                override fun dispatch(request: RecordedRequest) = answer(request)
            }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    /** 按 AppModule 的接线启动外壳；[session] 是启动时已保存的会话作用域（null = 游客）。 */
    private fun launch(session: String?) {
        session?.let { tokens.save("access-one", "refresh-one", it, "user-one") }
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

    @SuppressLint("RestrictedApi")
    private fun routes(): List<String> =
        navController.currentBackStack.value
            .filter { it.destination.hasRoute(RouteDestination::class) }
            .map { it.toRoute<RouteDestination>().name }

    private fun open(route: String) {
        composeRule.runOnUiThread { routing.router.open(RouteRequest(route, JsonObject(emptyMap()), RouteSource.IN_APP)) }
        composeRule.waitForIdle()
    }

    @Test
    fun leavingSettingsWhileTheLogoutRequestIsSlowStillReturnsToGuest() {
        launch(SessionScopeRaw.FULL)
        // APP-05b R1-01：冷启动时本机不知道这个会话的 phone_bound（假服务的 getMe 不带 phone_bound）→ 按已登录未绑手机，不再当已绑
        assertEquals(SessionState.LOGGED_IN, routing.session.value)
        // APP-05b R1-01：冷启动会先查一次 getMe；等它被假服务应答之后再换应答（否则它会被当成 logout 请求）
        idleUntil { server.requestCount >= 1 }
        open(SETTINGS)
        assertEquals(SETTINGS, routes().last())
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        answer = {
            arrived.countDown()
            release.await(WAIT_S, TimeUnit.SECONDS)
            envelope("{}")
        }

        composeRule.onNodeWithTag(SETTINGS_LOGOUT_TAG).performClick()
        val confirmText = LoginDraftTexts.text("settings.logout.confirm_ok")
        composeRule
            .onAllNodesWithText(confirmText)
            .let { nodes -> nodes[nodes.fetchSemanticsNodes().size - 1] }
            .performClick()
        assertTrue("logout 请求已发出", arrived.await(WAIT_S, TimeUnit.SECONDS))

        // 请求还慢着：用户返回离开设置页（设置页的协程作用域随之取消）
        composeRule.runOnUiThread { navController.popBackStack() }
        composeRule.waitForIdle()
        assertFalse(SETTINGS in routes())
        release.countDown()

        idleUntil { routing.session.value == SessionState.GUEST }
        assertNull("本机会话已清除", tokens.accessToken)
        open("OrderList")
        assertEquals("回到游客：需登录入口又进登录页", RouteNames.LOGIN, routes().last())
    }

    @Test
    fun restrictedSessionRefreshedToFullSyncsTheRouterAndResumesTheHeldPendingAction() {
        launch(SessionScopeRaw.DELETION_ONLY)
        assertEquals(SessionState.RESTRICTED, routing.session.value)
        // 受限会话下点需登录入口：要求登录，登记待办；用户先关掉登录页（待办保留）
        open("OrderList")
        assertEquals(RouteNames.LOGIN, routes().last())
        composeRule.runOnUiThread { navController.popBackStack() }
        composeRule.waitForIdle()
        assertTrue(routing.router.hasPendingAction)

        // 运营下调最低版本：版本检查变为 supported → 网络层异步刷新受限会话，服务端换发 full
        answer = { request -> if (request.path == REFRESH) envelope("{${pair("two", SessionScopeRaw.FULL)}}") else envelope("{}") }
        api.onUpdateStateChanged(UpdateRaw.SUPPORTED)

        // APP-05b R1-01：冷启动的受限会话刷新成 full 之后 phone_bound 仍未知（getMe 不带 phone_bound）→ 已登录未绑手机，不再当已绑
        idleUntil { routing.session.value == SessionState.LOGGED_IN }
        composeRule.waitForIdle()
        assertEquals(SessionScopeRaw.FULL, tokens.scope)
        val resumed = checkNotNull(routing.router.lastOutcome.value)
        assertEquals("经守卫恢复了保留的待办", "OrderList", resumed.request.route)
        assertEquals(RouteSource.PENDING_ACTION.raw, resumed.request.source)
        assertTrue(resumed.decision.opensPage)
        assertFalse("待办已执行并删除", routing.router.hasPendingAction)
    }

    /**
     * APP-05a 共性第 18 条（iOS 第 1 轮评审追加）：从生产网络层回调触发——已登录时请求收到 10002、刷新被拒（10404）→ 网络层清会话并发出
     * clear_session_login → 经 Router 打开 Login，并提示一次会话失效（error.10404）。不经调试入口。
     */
    @Test
    fun aSessionClearedByTheNetworkOpensLoginThroughTheRouterWithTheExpiredNotice() {
        launch(SessionScopeRaw.FULL)
        answer = { request -> if (request.path == REFRESH) code(10404) else code(10002) }
        val call = Thread { api.execute(ApiCall("getMe")) }.apply { start() }
        call.join(TimeUnit.SECONDS.toMillis(WAIT_S))

        idleUntil { routes().lastOrNull() == RouteNames.LOGIN }
        assertEquals(SessionState.GUEST, routing.session.value)
        assertNull(tokens.accessToken)
        composeRule.onNodeWithText(LoginDraftTexts.text("error.10404")).assertExists()
        assertEquals(
            "Router 记下的是经守卫打开的 Login",
            RouteNames.LOGIN,
            routing.router.lastOutcome.value
                ?.request
                ?.route,
        )
    }

    /**
     * APP-05a 共性第 19 条（iOS 第 1 轮评审追加）：受限会话刷新成 full 时按触发来源区分——
     * ① 登录尝试触发的复查（deletion_only + supported）进行中被取消（登录页出栈）：只同步状态，不恢复待办；
     * ② 之后由版本恢复触发的刷新把同一会话刷新成 full：会话世代仍有效，经启动协调与 Router 守卫恢复待办。
     */
    @Test
    fun onlyARefreshNotTriggeredByACancelledLoginResumesTheHeldPendingAction() {
        launch(null)
        open("OrderList")
        assertEquals(RouteNames.LOGIN, routes().last())
        val refreshing = CountDownLatch(1)
        val release = CountDownLatch(1)
        answer = { request ->
            when (request.path) {
                REFRESH -> {
                    refreshing.countDown()
                    release.await(WAIT_S, TimeUnit.SECONDS)
                    // 被取消的复查没有拿到结果（断连）：会话仍是受限的
                    MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST)
                }

                else -> {
                    envelope("""{"user_id":"user-one","is_new_user":false,"tokens":{${pair("one", SessionScopeRaw.DELETION_ONLY)}}}""")
                }
            }
        }
        var outcome: com.couli.feature.login.LoginOutcome? = null
        composeRule.runOnUiThread {
            CoroutineScope(Dispatchers.Main.immediate).launch {
                outcome = login.service.login("13800138000", "123456", now, "", restricted = false)
            }
        }
        idleUntil { refreshing.count == 0L }
        // 复查进行中：用户返回，登录页出栈（作废这次尝试）
        composeRule.runOnUiThread { navController.popBackStack() }
        composeRule.waitForIdle()
        release.countDown()
        idleUntil { outcome != null }
        assertEquals(com.couli.feature.login.LoginOutcome.STALE, outcome?.stateRaw)
        composeRule.waitForIdle()
        assertTrue("被取消的复查不恢复待办", routing.router.hasPendingAction)
        assertTrue(
            routing.router.lastOutcome.value
                ?.request
                ?.source != RouteSource.PENDING_ACTION.raw,
        )
        assertEquals("复查没有完成：仍是受限会话", SessionState.RESTRICTED, routing.session.value)

        // 版本恢复（unknown → supported）触发的刷新：同一会话刷新成 full → 经守卫恢复待办
        answer = { request -> if (request.path == REFRESH) envelope("{${pair("three", SessionScopeRaw.FULL)}}") else envelope("{}") }
        api.onUpdateStateChanged(UpdateRaw.UNKNOWN)
        api.onUpdateStateChanged(UpdateRaw.SUPPORTED)
        idleUntil { routing.session.value == SessionState.PHONE_BOUND && !routing.router.hasPendingAction }
        val resumed = checkNotNull(routing.router.lastOutcome.value)
        assertEquals("OrderList", resumed.request.route)
        assertEquals(RouteSource.PENDING_ACTION.raw, resumed.request.source)
    }

    private fun code(value: Int) =
        MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("""{"code":$value,"msg":"","data":null,"trace_id":"trace-000002"}""")

    private fun envelope(data: String) =
        MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("""{"code":0,"msg":"","data":$data,"trace_id":"trace-000001"}""")

    private fun pair(
        tag: String,
        scope: String,
    ) = """"access_token":"access-$tag","access_expires_at":"2099-01-01T00:00:00+08:00",""" +
        """"refresh_token":"refresh-$tag","refresh_expires_at":"2099-01-01T00:00:00+08:00","session_scope":"$scope""""

    private object FixtureDevice : DeviceCredentials {
        override val deviceId: String = "device-fixture"
        override val installSecret: String = "install-fixture"
    }

    /** 等主线程上的任务（网络回调切回主线程、App 级作用域里的登出）跑完：Robolectric 的主线程 Looper 是暂停模式，逐轮推进。 */
    private fun idleUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S)
        while (!condition() && System.nanoTime() < deadline) {
            org.robolectric.Shadows
                .shadowOf(android.os.Looper.getMainLooper())
                .idle()
            composeRule.waitForIdle()
            Thread.sleep(IDLE_POLL_MS)
        }
        assertTrue(condition())
    }

    private companion object {
        const val SETTINGS = "Settings"
        const val REFRESH = "/v1/auth/refresh"
        const val WAIT_S = 5L
        const val IDLE_POLL_MS = 10L
    }
}
