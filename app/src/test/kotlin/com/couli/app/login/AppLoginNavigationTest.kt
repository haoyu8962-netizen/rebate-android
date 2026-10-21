package com.couli.app.login

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.viewModelScope
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
import com.couli.core.network.ApiClient
import com.couli.core.network.ClientIdentity
import com.couli.core.network.DeviceCredentials
import com.couli.core.network.MemoryPendingKeyBackend
import com.couli.core.network.MemorySessionTokens
import com.couli.core.network.NetworkContext
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PrivacyRaw
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
import com.couli.feature.login.LoginOutcome
import com.couli.feature.login.PendingInviteCode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
 * 登录与真实导航（真实 AppShell + NavHost + AppRouting + AppLogin，接线同 AppModule；本地假服务 MockWebServer）：
 * - 共性第 2 条：登录成功时执行器用生产的 closeLogin 把 Login 从回退栈移除，再交 Router 恢复待办（OrderList），不用替身重置栈；
 *   受限登录同样先移除 Login，再进强更；
 * - 共性第 9 条：登录页一出栈（回退栈更新的同步出口）就作废本次尝试：在途登录请求取消，迟到的响应不保存会话、不恢复待办；
 * - 登出后回到游客：守卫的会话状态变回 guest，再点需登录入口又进登录页。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class AppLoginNavigationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = 5_000_000L
    private lateinit var server: MockWebServer
    private lateinit var routing: AppRouting
    private lateinit var login: AppLogin
    private lateinit var navController: NavHostController
    private val tokens = MemorySessionTokens()
    private var updateCheck = UpdateCheck.OK
    private var markedBelow = 0

    @Volatile
    private var answer: (RecordedRequest) -> MockResponse = { loggedIn("full") }

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
        val api =
            ApiClient(
                identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.0.0", build = "1", channel = "official"),
                baseUrl = { server.url("/").toString() },
                context = { NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, UpdateRaw.SUPPORTED) },
                device = FixtureDevice,
                tokens = tokens,
                pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
            )
        composeRule.runOnUiThread {
            login =
                AppLogin(api, tokens, routing, updateCheck = {
                    updateCheck
                }, markBelow = { markedBelow++ }, legal = LegalVersions(1, 1), pendingInvite = PendingInviteCode())
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

    @After
    fun tearDown() = server.shutdown()

    @SuppressLint("RestrictedApi")
    private fun routes(): List<String> =
        navController.currentBackStack.value
            .filter { it.destination.hasRoute(RouteDestination::class) }
            .map { it.toRoute<RouteDestination>().name }

    private fun openOrders() {
        composeRule.runOnUiThread { routing.router.open(RouteRequest("OrderList", JsonObject(emptyMap()), RouteSource.IN_APP)) }
        composeRule.waitForIdle()
    }

    private fun loginNow(restricted: Boolean = false): LoginOutcome {
        var outcome: LoginOutcome? = null
        composeRule.runOnUiThread {
            outcome = runBlocking { login.service.login("13800138000", "123456", now, restricted) }
        }
        composeRule.waitForIdle()
        return checkNotNull(outcome)
    }

    @Test
    fun successfulLoginRemovesLoginWithTheProductionNavigatorAndResumesThePendingPage() {
        openOrders()
        assertEquals("游客点需登录入口 → 登录页，登记待办", listOf(RouteNames.LOGIN), routes())
        assertTrue(routing.router.hasPendingAction)

        val outcome = loginNow()

        assertEquals(LoginOutcome.LOGGED_IN, outcome.stateRaw)
        assertFalse("Login 已出栈", RouteNames.LOGIN in routes())
        val resumed = checkNotNull(routing.router.lastOutcome.value)
        assertEquals("Router 恢复了原意图（OrderList 是订单 Tab，切到该 Tab）", "OrderList", resumed.request.route)
        assertEquals(RouteSource.PENDING_ACTION.raw, resumed.request.source)
        assertTrue(resumed.decision.opensPage)
        assertFalse("待办已执行并删除", routing.router.hasPendingAction)
        assertEquals(SessionState.PHONE_BOUND, routing.session.value)
    }

    @Test
    fun restrictedLoginRemovesLoginBeforeEnteringForceUpdate() {
        updateCheck = UpdateCheck.BELOW_MIN
        answer = { loggedIn("deletion_only") }
        composeRule.runOnUiThread {
            routing.router.open(
                RouteRequest(RouteNames.LOGIN, JsonObject(emptyMap()), RouteSource.IN_APP),
                routing.guardState().copy(fromForceUpdatePage = true),
            )
        }
        composeRule.waitForIdle()
        assertTrue(RouteNames.LOGIN in routes())

        val outcome = loginNow(restricted = true)

        assertEquals(LoginOutcome.LOGGED_IN, outcome.stateRaw)
        assertFalse("受限登录成功后 Login 同样出栈", RouteNames.LOGIN in routes())
        assertEquals(SessionState.RESTRICTED, routing.session.value)
    }

    @Test
    fun app17cLeavingAndReenteringRealLoginStackKeepsPendingCode() {
        login.pendingInvite.set("K7M2QX")
        openOrders()
        assertTrue(RouteNames.LOGIN in routes())
        composeRule.runOnUiThread { login.pageConfig(false).actions.onClose() }
        composeRule.waitForIdle()
        assertFalse(RouteNames.LOGIN in routes())
        assertEquals("离开登录页不清码", "K7M2QX", login.pendingInvite.peek())
        openOrders()
        assertTrue(RouteNames.LOGIN in routes())
        val model =
            com.couli.feature.login
                .LoginViewModel(login.pageConfig(false))
        try {
            assertEquals("K7M2QX", model.pendingInviteRow?.code)
        } finally {
            model.viewModelScope.cancel()
        }
    }

    @Test
    fun leavingTheLoginPageInvalidatesTheAttemptAndItsLateResponseSavesNothing() {
        openOrders()
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        answer = {
            arrived.countDown()
            release.await(WAIT_S, TimeUnit.SECONDS)
            loggedIn("full")
        }
        var outcome: LoginOutcome? = null
        val worker =
            Thread {
                outcome = runBlocking { login.service.login("13800138000", "123456", now, restricted = false) }
            }.apply { start() }
        assertTrue(arrived.await(WAIT_S, TimeUnit.SECONDS))
        // 用户返回：Login 出栈（回退栈一更新就作废尝试，共性第 9 条）
        composeRule.runOnUiThread { navController.popBackStack() }
        composeRule.waitForIdle()
        release.countDown()
        worker.join(TimeUnit.SECONDS.toMillis(WAIT_S))

        assertEquals(LoginOutcome.STALE, checkNotNull(outcome).stateRaw)
        assertNull("迟到的登录响应不保存会话", tokens.accessToken)
        assertTrue("待办没有被恢复", routing.router.hasPendingAction)
        assertEquals(SessionState.GUEST, routing.session.value)
    }

    @Test
    fun logoutReturnsToGuestAndTheLoginEntryIsRequiredAgain() {
        openOrders()
        loginNow()
        answer = { MockResponse().setHeader("Content-Type", "application/json").setBody("""{"code":0,"msg":"","data":{}}""") }
        // 登出在 App 级作用域（主线程）里执行：从主线程发起、等它结束（不在主线程上阻塞等待）
        var done: Boolean? = null
        composeRule.runOnUiThread { CoroutineScope(Dispatchers.Main.immediate).launch { done = login.logout() } }
        idleUntil { done != null }

        assertEquals(true, done)
        assertNull(tokens.accessToken)
        assertEquals(SessionState.GUEST, routing.session.value)
        openOrders()
        assertEquals(RouteNames.LOGIN, routes().last())
    }

    private fun loggedIn(scope: String) =
        MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(
                """{"code":0,"msg":"","data":{"user_id":"user-one","is_new_user":false,"tokens":{"access_token":"access-one",""" +
                    """"access_expires_at":"2099-01-01T00:00:00+08:00","refresh_token":"refresh-one",""" +
                    """"refresh_expires_at":"2099-01-01T00:00:00+08:00","session_scope":"$scope"}},"trace_id":"trace-000001"}""",
            )

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
        const val WAIT_S = 5L
        const val IDLE_POLL_MS = 10L
    }
}
