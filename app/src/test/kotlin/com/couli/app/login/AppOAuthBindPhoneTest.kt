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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.FlowWait
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
import com.couli.core.network.InFlightCalls
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
import com.couli.feature.login.BindPhoneTags
import com.couli.feature.login.LegalVersions
import com.couli.feature.login.LoginTags
import com.couli.feature.login.OAuthProvider
import com.couli.feature.login.ThirdPartyAuthAdapter
import com.couli.feature.login.ThirdPartyAuthKind
import com.couli.feature.login.ThirdPartyAuthResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
 * APP-05b 在真实 AppShell + NavHost + AppRouting + AppLogin（接线同 AppModule）上的流程，本地假服务 MockWebServer + 立即带回凭证的
 * 假微信适配器：
 * - 第三方新号：登录页关闭、弹绑手机引导，登录前的待办暂缓；【暂不】只恢复（共性第 3 条）；【去绑定】→ 绑定成功 → 已绑手机、经守卫
 *   恢复需要手机号的待办；
 * - 第三方老用户：phone_bound 查清前不恢复需要手机号的待办，查询失败保留待办、按未绑处理（共性第 2 条）；
 * - F-AND-14：会话失效 Effect 的归属来自网络层（之后已有新登录就不跳登录，第 26 条）；登出时原会话已被清掉，只清原会话的待办（第 25 条）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class AppOAuthBindPhoneTest {
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

    /** 网络层在途调用（接给 ApiClient；R1-02 起不再拿它推测流程结论）。 */
    private val inFlight = InFlightCalls()

    /** 主线程已处理完的网络层 Effect 数（F-AND-36：等清会话通知真正被处理，不靠固定轮次）。 */
    private val effectsHandled = AtomicInteger(0)

    @Volatile
    private var answer: (RecordedRequest) -> MockResponse = ::defaultAnswer

    @Volatile
    private var isNewUser = true

    /** 收到的 getMe 次数（冷启动的 phone_bound 查询等）。 */
    private val meRequests = AtomicInteger(0)

    @Volatile
    private var meAnswer: () -> MockResponse = { envelope("""{"user_id":"user-one","phone_bound":true}""") }

    private class MemoryStore : PrivacyStore {
        override var decisionRaw: String = "consented"
        override var consentedVersion: String? = "1"
        override var consentedAtMillis: Long? = 0L
        override var basicMode: Boolean = false
    }

    /** 立即带回凭证（state = nonce）的假微信适配器。 */
    private object InstantWechat : ThirdPartyAuthAdapter {
        override val provider: String = OAuthProvider.WECHAT

        override fun adapterAvailable(): Boolean = true

        override fun installed(): Boolean? = null

        override suspend fun authorize(nonce: String) =
            ThirdPartyAuthResult(ThirdPartyAuthKind.CREDENTIAL, code = "code-placeholder", state = nonce)
    }

    private fun defaultAnswer(request: RecordedRequest): MockResponse =
        when (request.path) {
            ATTEMPTS -> envelope("""{"attempt_id":"attempt-1","nonce":"nonce-placeholder-0001","expire_at":"2099-01-01T00:00:00+08:00"}""")
            WECHAT -> envelope("""{"user_id":"user-one","is_new_user":$isNewUser,"tokens":{${pair("one", SessionScopeRaw.FULL)}}}""")
            ME -> meAnswer()
            else -> envelope("{}")
        }

    @Before
    fun setUp() {
        SharedPreferencesPendingBackend(context).delete()
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == ME) meRequests.incrementAndGet()
                    return answer(request)
                }
            }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    @Suppress("LongMethod") // 与 AppModule 同一套接线逐项组装
    private fun launch(session: String?) {
        session?.let { tokens.save("access-zero", "refresh-zero", it, "user-zero") }
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
                inFlight = inFlight,
                hooks =
                    NetworkHooks(
                        onEffect = { effect ->
                            main.post {
                                login.onNetworkEffect(effect)
                                effectsHandled.incrementAndGet()
                            }
                        },
                        onSessionStored = { generation -> main.post { login.onSessionStored(generation) } },
                    ),
                sleeper = {},
            )
        composeRule.runOnUiThread {
            login =
                AppLogin(
                    api,
                    tokens,
                    routing,
                    updateCheck = { UpdateCheck.OK },
                    markBelow = {},
                    legal = LegalVersions(1, 1),
                    adapters = listOf(InstantWechat),
                )
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
        // R1-01：有会话的冷启动先查一次 getMe（phone_bound 未知）；等它到达假服务之后测试再换应答
        if (session != null) idleUntil { meRequests.get() >= 1 }
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

    /** 在登录页点微信按钮 → 协议确认框【同意并继续】→ 等登录页关闭（经 LoginViewModel，同生产）。 */
    private fun wechatLogin() {
        composeRule.onNodeWithTag(LoginTags.OAUTH_PREFIX + OAuthProvider.WECHAT).performScrollTo().performClick()
        composeRule
            .onAllNodesWithText(LoginDraftTexts.text("login.agree_confirm.ok"))
            .let { nodes -> nodes[nodes.fetchSemanticsNodes().size - 1] }
            .performClick()
        idleUntil { RouteNames.LOGIN !in routes() }
        composeRule.waitForIdle()
    }

    /** Router 最近一次是以 pending_action 来源打开了 [route]。 */
    private fun resumedPending(route: String): Boolean {
        val last = routing.router.lastOutcome.value ?: return false
        return last.request.route == route && last.request.source == RouteSource.PENDING_ACTION.raw && last.decision.opensPage
    }

    /** 共性第 28 条（FU-AND-0716 第 2 轮）：App 给受限登录（强更页进入）的页面配置同样带上可显示的第三方入口；邀请码来源仍不给。 */
    @Test
    fun fuand0716_28_theRestrictedLoginPageConfigKeepsTheVisibleThirdPartyProviders() {
        launch(session = null)
        composeRule.runOnUiThread {
            val restricted = login.pageConfig(restricted = true)
            assertEquals(listOf(OAuthProvider.WECHAT), restricted.thirdParty)
            assertTrue(restricted.restricted)
            assertEquals("受限登录不用邀请码来源", null, restricted.invite)
            assertEquals("与正常登录同一组第三方入口", login.pageConfig(restricted = false).thirdParty, restricted.thirdParty)
        }
    }

    @Test
    fun aNewThirdPartyUserSeesTheGuideAndSkippingOnlyResumesTheHeldPendingAction() {
        launch(null)
        open("OrderList")
        assertEquals(RouteNames.LOGIN, routes().last())
        wechatLogin()

        assertFalse("登录页已关闭", RouteNames.LOGIN in routes())
        assertEquals(SessionState.LOGGED_IN, routing.session.value)
        assertTrue(login.notice.value is LoginNotice.BindPhoneGuide)
        assertTrue("引导结束前待办暂缓", routing.router.hasPendingAction)
        assertFalse("OrderList" in routes())
        composeRule.onNodeWithText(LoginDraftTexts.text("invite.bind_phone_guide.title")).assertExists()

        composeRule.onNodeWithText(LoginDraftTexts.text("invite.bind_phone_guide.skip")).performClick()
        idleUntil { resumedPending("OrderList") }
        assertFalse(routing.router.hasPendingAction)
        assertEquals(
            RouteSource.PENDING_ACTION.raw,
            routing.router.lastOutcome.value
                ?.request
                ?.source,
        )
    }

    @Test
    fun goingFromTheGuideToBindPhoneAndBindingResumesAPhoneRequiredPendingAction() {
        launch(null)
        open(PHONE_ROUTE)
        assertEquals(RouteNames.LOGIN, routes().last())
        wechatLogin()
        assertTrue(routing.router.hasPendingAction)
        composeRule.onNodeWithText(LoginDraftTexts.text("invite.bind_phone_guide.go")).performClick()
        idleUntil { routes().lastOrNull() == RouteNames.BIND_PHONE }

        meAnswer = { envelope("""{"user_id":"user-one","phone_bound":true}""") }
        composeRule.onNodeWithTag(BindPhoneTags.PHONE).performTextInput("13800138000")
        composeRule.onNodeWithTag(BindPhoneTags.CODE).performTextInput("123456")
        composeRule.onNodeWithTag(BindPhoneTags.SUBMIT).performClick()

        idleUntil { resumedPending(PHONE_ROUTE) }
        assertFalse(RouteNames.BIND_PHONE in routes())
        assertEquals(SessionState.PHONE_BOUND, routing.session.value)
        assertFalse(routing.router.hasPendingAction)
    }

    @Test
    fun anExistingUserWhosePhoneBoundIsUnknownIsNotTreatedAsBound() {
        isNewUser = false
        meAnswer =
            {
                MockResponse()
                    .setHeader(
                        "Content-Type",
                        "application/json",
                    ).setBody("""{"code":50001,"msg":"","data":null,"trace_id":"trace-000003"}""")
            }
        launch(null)
        open(PHONE_ROUTE)
        wechatLogin()

        assertEquals("未知不当已绑", SessionState.LOGGED_IN, routing.session.value)
        // R1-03：查询失败解除暂缓，需要手机号的待办按未验证保留（不自动拉起绑手机页）
        idleUntil { !routing.router.pendingHeld }
        assertTrue("查询失败：待办保留", routing.router.hasPendingAction)
        assertFalse(resumedPending(PHONE_ROUTE))
        assertFalse(RouteNames.LOGIN in routes())
        assertFalse("不自动拉起绑手机页", RouteNames.BIND_PHONE in routes())
        assertTrue("老用户不弹绑手机引导", login.notice.value !is LoginNotice.BindPhoneGuide)
    }

    /** 共性第 14 条（R1-03）：游客打开 OrderList（只需登录）→ 第三方老用户登录成功、getMe 失败 → 解除暂缓，OrderList 照常经守卫恢复。 */
    @Test
    fun anExistingUserWhoseGetMeFailsStillResumesALoginLevelPendingAction() {
        isNewUser = false
        meAnswer = { code(50001) }
        launch(null)
        open("OrderList")
        assertEquals(RouteNames.LOGIN, routes().last())
        wechatLogin()

        idleUntil { resumedPending("OrderList") }
        assertFalse(routing.router.hasPendingAction)
        assertFalse(routing.router.pendingHeld)
        assertEquals("phone_bound 仍未知：不当已绑", SessionState.LOGGED_IN, routing.session.value)
    }

    /**
     * 共性第 13 条（R1-01）：冷启动后 phone_bound 还不知道（getMe 未回）时用户主动打开手机号级页面 → 守卫按已登录未绑手机，先进绑手机页
     * （登记待办），不直接打开；getMe 查清已绑后关掉绑手机页、经守卫打开原目标。
     */
    @Test
    fun coldStartOpeningAPhoneLevelPageBeforePhoneBoundIsKnownGoesThroughBindPhone() {
        val release = CountDownLatch(1)
        meAnswer = {
            release.await(FlowWait.CEILING_MS, TimeUnit.MILLISECONDS)
            envelope("""{"user_id":"user-zero","phone_bound":true}""")
        }
        try {
            launch(SessionScopeRaw.FULL)
            assertEquals("未知不当已绑", SessionState.LOGGED_IN, routing.session.value)
            open(PHONE_ROUTE)
            assertEquals(RouteNames.BIND_PHONE, routes().last())
            assertFalse("没有绕过手机号守卫", PHONE_ROUTE in routes())
            assertTrue(routing.router.hasPendingAction)
        } finally {
            release.countDown()
        }
        idleUntil { resumedPending(PHONE_ROUTE) && RouteNames.BIND_PHONE !in routes() }
        assertEquals(SessionState.PHONE_BOUND, routing.session.value)
        assertFalse(routing.router.hasPendingAction)
    }

    /** 共性第 13 条（R1-01）：冷启动查到未绑 → 主动打开手机号级页面停在绑手机页，不打开目标。 */
    @Test
    fun coldStartWithAnUnboundPhoneKeepsPhoneLevelPagesBehindBindPhone() {
        meAnswer = { envelope("""{"user_id":"user-zero","phone_bound":false}""") }
        launch(SessionScopeRaw.FULL)
        idleUntil { login.phoneBinding.get(tokens.loginEpoch) == false }
        open(PHONE_ROUTE)
        // F-AND-36：原为空转一轮；改为等守卫对这次打开交回结论
        idleUntil {
            routing.router.lastOutcome.value
                ?.request
                ?.route == PHONE_ROUTE
        }
        assertEquals(SessionState.LOGGED_IN, routing.session.value)
        assertEquals(RouteNames.BIND_PHONE, routes().last())
        assertFalse(PHONE_ROUTE in routes())
    }

    /** 共性第 13 条（R1-01）：冷启动查不到 phone_bound（getMe 失败）→ 按未验证：打开手机号级页面停在绑手机页（进页时再查一次）。 */
    @Test
    fun coldStartWhosePhoneBoundCannotBeReadKeepsPhoneLevelPagesBehindBindPhone() {
        meAnswer = { code(50001) }
        launch(SessionScopeRaw.FULL)
        // F-AND-36（R1-02）：原为空转一轮；改为等冷启动那次补查任务（getMe 失败连同收尾）结束
        phoneChecksSettled(1)
        open(PHONE_ROUTE)
        // 进页补查（第二次 getMe）的任务连同收尾都已结束
        idleUntil { meRequests.get() >= 2 }
        phoneChecksSettled(2)
        assertEquals(SessionState.LOGGED_IN, routing.session.value)
        assertEquals(RouteNames.BIND_PHONE, routes().last())
        assertFalse(PHONE_ROUTE in routes())
    }

    @Test
    fun anExistingUserWithABoundPhoneResumesThroughTheGuardOnceItIsKnown() {
        isNewUser = false
        launch(null)
        open(PHONE_ROUTE)
        wechatLogin()
        idleUntil { resumedPending(PHONE_ROUTE) }
        assertEquals(SessionState.PHONE_BOUND, routing.session.value)
        assertFalse(routing.router.hasPendingAction)
    }

    /**
     * APP-05b 共性第 8 条：冷启动（本进程没有这个会话的 phone_bound 记录）时恢复手机号级路由前先查 getMe；查到未绑 → 不恢复、待办保留，
     * 守卫按已登录未绑手机处理。
     */
    @Test
    fun coldStartChecksPhoneBoundBeforeResumingAPhoneLevelPendingAction() {
        val store =
            com.couli.core.router
                .PendingActionStore(SharedPreferencesPendingBackend(context)) { now }
        val params = JsonObject(mapOf("route" to kotlinx.serialization.json.JsonPrimitive(PHONE_ROUTE), "params" to JsonObject(emptyMap())))
        val requested = java.util.Collections.synchronizedList(mutableListOf<String>())
        answer = { request ->
            requested += request.path.orEmpty()
            defaultAnswer(request)
        }
        meAnswer = { envelope("""{"user_id":"user-zero","phone_bound":false}""") }
        launch(SessionScopeRaw.FULL)
        assertTrue(store.save(com.couli.core.router.PendingActionStore.OPEN_ROUTE, params, now))
        composeRule.runOnUiThread { routing.router.resumePending(enterPrerequisite = false) }
        idleUntil { ME in requested && routing.session.value == SessionState.LOGGED_IN }
        // F-AND-36（R1-02）：原为空转一轮；改为等发起的补查任务（getMe 连同守卫同步、恢复判定）都结束，再核对「不恢复」
        idleUntil { login.phoneChecksStarted >= 1 && login.phoneChecksSettled() }
        assertFalse("未绑：不恢复手机号级路由", resumedPending(PHONE_ROUTE))
        assertTrue("待办保留", routing.router.hasPendingAction)
    }

    /** F-AND-14（共性第 26 条）：清会话 Effect 送到主线程之前已经登录了新会话 → 这次失效不属于当前会话，不跳登录、不提示失效。 */
    @Test
    fun aClearEffectOfAnEarlierSessionDoesNotOpenLoginOverANewerOne() {
        launch(SessionScopeRaw.FULL)
        answer = { request -> if (request.path == "/v1/me") code(10404) else defaultAnswer(request) }
        val handledBefore = effectsHandled.get()
        val call = Thread { api.execute(ApiCall("getMe")) }.apply { start() }
        // F-AND-36：原为最多等 5 秒（到时不判失败）；改为等这次调用交回结论，上限内没回判失败
        call.join(FlowWait.CEILING_MS)
        assertFalse("getMe 调用在上限内交回结论", call.isAlive)
        assertNull(tokens.accessToken)
        // 新会话随后的 phone_bound 查询（R1-01）照常应答：只验证旧会话的失效通知不作用到新会话
        answer = ::defaultAnswer
        // 主线程还没处理网络层的通知：此时另一处保存了新登录
        tokens.save("access-new", "refresh-new", SessionScopeRaw.FULL, "user-new")
        // F-AND-36：原为空转 6 轮；改为等主线程真正处理完这次清会话通知，再等新会话的补查任务（若有）连同收尾结束（R1-02）
        idleUntil { effectsHandled.get() > handledBefore && login.phoneChecksSettled() }
        assertFalse("不跳登录", RouteNames.LOGIN in routes())
        assertTrue(composeRule.onAllNodesWithText(LoginDraftTexts.text("error.10404")).fetchSemanticsNodes().isEmpty())
    }

    /** F-AND-14（共性第 25 条）：登出等待中原会话被 10404 清掉、用户以游客新建了待办 → 登出收尾只清原会话的待办，新待办保留。 */
    @Test
    fun logoutAfterTheSessionWasClearedElsewhereKeepsTheNewPendingAction() {
        launch(SessionScopeRaw.FULL)
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        answer = { request ->
            when (request.path) {
                LOGOUT -> {
                    arrived.countDown()
                    release.await(FlowWait.CEILING_MS, TimeUnit.MILLISECONDS)
                    envelope("{}")
                }

                "/v1/me" -> {
                    code(10404)
                }

                else -> {
                    defaultAnswer(request)
                }
            }
        }
        var done: Boolean? = null
        composeRule.runOnUiThread { CoroutineScope(Dispatchers.Main.immediate).launch { done = login.logout() } }
        assertTrue(arrived.await(FlowWait.CEILING_MS, TimeUnit.MILLISECONDS))

        val me = Thread { api.execute(ApiCall("getMe")) }.apply { start() }
        me.join(FlowWait.CEILING_MS)
        assertFalse("getMe 调用在上限内交回结论", me.isAlive)
        idleUntil { routing.session.value == SessionState.GUEST }
        open("OrderList")
        assertTrue(routing.router.hasPendingAction)

        release.countDown()
        idleUntil { done != null }
        assertEquals(true, done)
        assertTrue("原会话被清掉之后新建的待办保留", routing.router.hasPendingAction)
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

    private fun pump() {
        org.robolectric.Shadows
            .shadowOf(android.os.Looper.getMainLooper())
            .idle()
        composeRule.waitForIdle()
    }

    private fun state(): String =
        "routes=${routes()} last=${routing.router.lastOutcome.value} pending=${routing.router.hasPendingAction} " +
            "held=${routing.router.pendingHeld} session=${routing.session.value} me=${meRequests.get()}"

    /** F-AND-36：等流程交回结论（原为最多 5 秒的轮询），见 [FlowWait]。 */
    private fun idleUntil(condition: () -> Boolean) = FlowWait.until(::pump, ::state, condition = condition)

    /**
     * F-AND-36（R1-02）：至少发起过 [count] 次 phone_bound 补查，且它们连同查询返回后的收尾（守卫同步、出栈、恢复待办）都已结束——等的是 AppLogin
     * 的补查任务本身，不是网络层空闲。
     */
    private fun phoneChecksSettled(count: Int) =
        FlowWait.until(::pump, { "${state()} checks=${login.phoneChecksStarted}" }) {
            login.phoneChecksStarted >= count && login.phoneChecksSettled()
        }

    private companion object {
        const val ATTEMPTS = "/v1/auth/oauth-attempts"
        const val WECHAT = "/v1/auth/login/wechat"
        const val ME = "/v1/me"
        const val LOGOUT = "/v1/auth/logout"
        const val PHONE_ROUTE = "InviteShare"
    }
}
