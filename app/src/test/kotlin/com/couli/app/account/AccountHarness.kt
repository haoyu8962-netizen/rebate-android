// 假服务的码号、金额、号码都是契约取值或明显的测试值（13800005678 / 13900001234），按值直接写
@file:Suppress("MagicNumber")

package com.couli.app.account

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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
import com.couli.app.login.AppLogin
import com.couli.app.me.AppMe
import com.couli.app.me.MeSystemPorts
import com.couli.app.privacy.AppPrivacy
import com.couli.app.routing.AppRouting
import com.couli.app.routing.RouteDestination
import com.couli.app.routing.RoutingPrivacyHooks
import com.couli.app.shell.AppShell
import com.couli.app.update.AppUpdate
import com.couli.app.update.StoreAccess
import com.couli.core.clientcore.ConfigReply
import com.couli.core.clientcore.ConfigStore
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.me.MeLocalStore
import com.couli.core.me.account.AccountDraftTexts
import com.couli.core.network.ApiClient
import com.couli.core.network.ClientIdentity
import com.couli.core.network.DeviceCredentials
import com.couli.core.network.InFlightCalls
import com.couli.core.network.MemoryPendingKeyBackend
import com.couli.core.network.MemorySessionTokens
import com.couli.core.network.NetworkContext
import com.couli.core.network.NetworkHooks
import com.couli.core.network.PendingKeyRecord
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PrivacyRaw
import com.couli.core.network.UpdateRaw
import com.couli.core.privacy.PrivacyGate
import com.couli.core.privacy.PrivacyStore
import com.couli.core.privacy.RetryScheduler
import com.couli.core.push.PushTokenSource
import com.couli.core.router.RouteRequest
import com.couli.core.router.RouteSource
import com.couli.core.router.UpdateCheck
import com.couli.core.update.MemoryUpdateStore
import com.couli.core.update.UpdateCheckOutcome
import com.couli.core.update.UpdateGate
import com.couli.feature.login.LegalVersions
import com.couli.feature.me.AccountTags
import com.couli.feature.me.MeTags
import java.net.URLDecoder
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.robolectric.Shadows.shadowOf

/**
 * APP-13a 集成测试的搭法（brief §2 第 13 项）：真实 AppShell + NavHost + AppRouting + AppPrivacy + AppLogin + AppMe + AppAccount + AppUpdate + ApiClient，
 * 只把传输层换成 MockWebServer（[serverState] 是可编程的本地假服务：注销进度、钱包摘要、phone_bound、二次验证、幂等结果、作废）。页面经真实 Router 打开、
 * 经页面控件操作；抓包断言用 [requests]。账号、令牌、号码都是明显的测试值。
 */
@Suppress("TooManyFunctions", "LargeClass", "UnnecessaryAbstractClass")
abstract class AccountHarness {
    @get:Rule
    val composeRule = createComposeRule()

    protected val context: Context = ApplicationProvider.getApplicationContext()

    @Volatile protected var now: Long = OffsetDateTime.parse("2026-10-14T12:00:00+08:00").toInstant().toEpochMilli()

    protected lateinit var server: MockWebServer
    protected lateinit var routing: AppRouting
    protected lateinit var navController: NavHostController
    protected lateinit var login: AppLogin
    protected lateinit var api: ApiClient
    protected lateinit var me: AppMe
    protected lateinit var account: AppAccount
    protected lateinit var privacy: AppPrivacy
    protected lateinit var tokens: MemorySessionTokens
    protected lateinit var update: AppUpdate

    /** 未决记录的持久化（冷启动用同一个后端重建 ApiClient）。 */
    protected var pendingBackend = MemoryPendingKeyBackend()
    protected lateinit var pendingStore: PendingKeyStore
    protected val inFlight = InFlightCalls()

    /** 路由可用性覆盖（Debug 开关的测试替身；缺省打开 DeleteAccount，G01 的测试单独关掉）。 */
    @Volatile protected var override: Set<String> = setOf(DELETE_ACCOUNT)

    /** 本机按账号归属给的发码号码（G02；null = Release 取不到）。 */
    @Volatile protected var ownedPhone: String? = PHONE_OLD

    /** 网络层看到的强更状态（受限会话按它决定是否刷新）。 */
    @Volatile protected var networkUpdate: String = UpdateRaw.SUPPORTED

    /** 推送令牌上报的请求（登录后不上报，AC-S1-83）。 */
    protected val pushSource = TestPush()

    // ---------------------------------------------------------------- 假服务

    protected class Recorded(
        val method: String,
        val path: String,
        val body: String,
        val headers: Map<String, String>,
    ) {
        fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

        val key: String? get() = header("Idempotency-Key")
        val stepUp: String? get() = header("X-Step-Up-Token")
        val user: String? get() = header("Authorization")?.removePrefix("Bearer access-")

        fun json(): JsonObject = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrDefault(JsonObject(emptyMap()))

        override fun toString(): String = "$method $path"
    }

    /** 可编程的假服务状态（按账号的注销进度、负余额、phone_bound、二次验证要求、幂等结果）。 */
    protected inner class ServerState {
        @Volatile var deletion: String = "null"

        @Volatile var negativeFen: Long = 0

        @Volatile var phoneBound: Boolean = true

        /** Me.risk.state（banned 时 RiskNotice 封禁态底部有「注销账号」）。 */
        @Volatile var risk: String = "normal"

        /** requestDeletion / bindPhone（更换）没带有效 token 回 10003。 */
        @Volatile var requireStepUp: Boolean = true

        val validTokens: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

        /** 键 → (code, data)：同一 Idempotency-Key 回同一结果；作废后为 20903。 */
        val results: MutableMap<String, Pair<Int, String>> = java.util.concurrent.ConcurrentHashMap()

        var tokenSerial = 0
    }

    protected val serverState = ServerState()
    protected val requests = mutableListOf<Recorded>()

    /** 轻提示与 polite 播报（LocalCouliAnnouncer 的测试替身）：一次性提示只出现两秒，按播报断言。 */
    protected val announced = mutableListOf<String>()

    protected fun announcedText(text: String): Boolean = synchronized(announced) { announced.any { it.contains(text) } }

    /** 一次性的覆盖回答（"METHOD path" → 队列）：用完回到缺省回答。 */
    private val queued = mutableMapOf<String, ArrayDeque<(Recorded) -> MockResponse>>()

    protected fun enqueue(
        method: String,
        path: String,
        answer: (Recorded) -> MockResponse,
    ) {
        synchronized(queued) { queued.getOrPut("$method $path") { ArrayDeque() }.addLast(answer) }
    }

    @Suppress("LongParameterList")
    protected fun enqueueCode(
        method: String,
        path: String,
        code: Int,
        data: String = "null",
        headers: Map<String, String> = emptyMap(),
        trace: String = "trace-123456",
    ) = enqueue(method, path) { envelope(data, code, trace).apply { headers.forEach { (k, v) -> setHeader(k, v) } } }

    /** 没有统一外壳：传输层断开（结果未知）。 */
    protected fun enqueueTimeout(
        method: String,
        path: String,
        accept: (() -> Unit)? = null,
    ) = enqueue(method, path) {
        accept?.invoke()
        MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
    }

    protected fun envelope(
        data: String,
        code: Int = 0,
        trace: String = "trace-000123",
    ): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("""{"code":$code,"msg":"","data":$data,"trace_id":"$trace"}""")

    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount", "ComplexCondition") // 假服务按路径逐项回答
    private fun defaultAnswer(request: Recorded): MockResponse {
        val st = serverState
        val user = request.user ?: "guest"
        // access_token 过期：刷新之前带令牌的请求一律 10002（刷新成功后解除）
        if (accessExpired && request.user != null && request.path != "/v1/auth/refresh") return envelope("null", 10002)
        // 封禁（BR-ID-31）：白名单之外的接口回 10006
        if (st.risk == "banned" && request.user != null && request.path != "/v1/me" &&
            BANNED_WHITELIST.none { request.path == it || request.path.startsWith("$it/") }
        ) {
            return envelope("null", 10006)
        }
        return when {
            request.path == "/v1/me" -> {
                // 第 2 轮 R2-03：需要时让 getMe 停在服务端，放行后按那时的 phoneBound 回答
                meHold?.await(WAIT_S, TimeUnit.SECONDS)
                envelope(meJson(user, st.phoneBound, st.risk))
            }

            request.path == "/v1/me/deletion" && request.method == "GET" -> {
                envelope(st.deletion)
            }

            request.path == "/v1/wallet/summary" -> {
                envelope(walletJson(st.negativeFen))
            }

            request.path == "/v1/auth/sms-codes" -> {
                envelope("""{"resend_after_sec":60,"expires_in_sec":300}""")
            }

            request.path == "/v1/auth/step-up" -> {
                val body = request.json()
                val action = (body["action"] as? JsonPrimitive)?.content
                if ((body["code"] as? JsonPrimitive)?.content == CODE_OK) {
                    val token = "tok-$action-${++st.tokenSerial}"
                    st.validTokens += token
                    envelope("""{"step_up_token":"$token","expire_at":"2099-01-01T00:05:00+08:00"}""")
                } else {
                    envelope("null", 20002)
                }
            }

            request.path == "/v1/me/deletion" && request.method == "POST" -> {
                val key = request.key.orEmpty()
                st.results[key]?.let { return envelope(it.second, it.first) }
                if (st.requireStepUp && request.stepUp !in st.validTokens) return envelope("null", 10003)
                val cooling = deletionJson("cooling", canCancel = true)
                st.results[key] = 0 to cooling
                st.deletion = cooling
                envelope(cooling)
            }

            request.path == "/v1/me/deletion/cancel" -> {
                val cancelled = deletionJson("cancelled", canCancel = false, reason = "user")
                st.deletion = cancelled
                envelope(cancelled)
            }

            request.path == "/v1/me/phone" -> {
                val key = request.key.orEmpty()
                st.results[key]?.let { return envelope(it.second, it.first) }
                if (st.phoneBound && st.requireStepUp && request.stepUp !in st.validTokens) return envelope("null", 10003)
                val code = (request.json()["code"] as? JsonPrimitive)?.content
                if (code != CODE_OK) return envelope("null", 20002)
                st.results[key] = 0 to "{}"
                st.phoneBound = true
                envelope("{}")
            }

            request.path == "/v1/idempotency-keys/abandon" -> {
                val key = (request.json()["idempotency_key"] as? JsonPrimitive)?.content.orEmpty()
                val done = st.results[key]
                if (done == null) {
                    st.results[key] = 20903 to "null"
                    envelope("""{"outcome":"abandoned","original":null}""")
                } else {
                    envelope("""{"outcome":"completed","original":{"code":${done.first},"msg":"","data":${done.second}}}""")
                }
            }

            request.path == "/v1/auth/login/sms" -> {
                envelope(
                    """{"user_id":"$loginUser","is_new_user":false,"tokens":{"access_token":"access-$loginUser",""" +
                        """"access_expires_at":"2099-01-01T00:00:00+08:00","refresh_token":"refresh-$loginUser",""" +
                        """"refresh_expires_at":"2099-01-01T00:00:00+08:00","session_scope":"$loginScope"}}""",
                )
            }

            request.path == "/v1/auth/refresh" -> {
                accessExpired = false
                envelope(
                    """{"access_token":"access-$user","access_expires_at":"2099-01-01T00:00:00+08:00","refresh_token":"refresh-$user",""" +
                        """"refresh_expires_at":"2099-01-01T00:00:00+08:00","session_scope":"$refreshScope"}""",
                )
            }

            else -> {
                envelope("{}")
            }
        }
    }

    /** 不为 null 时 GET /v1/me 都等它放行（第 2 轮 R2-03：补查在途时覆盖页面）。 */
    @Volatile protected var meHold: java.util.concurrent.CountDownLatch? = null

    /** 短信登录返回的账号与作用域（强更页受限登录，BR-ID-01）。 */
    @Volatile protected var loginUser: String = USER_A

    @Volatile protected var loginScope: String = "deletion_only"

    /** access_token 已过期（AC-S1-83 access 过期组）：刷新之前带令牌的请求回 10002。 */
    @Volatile protected var accessExpired: Boolean = false

    /** 刷新令牌返回的作用域（AC-S1-83 access 过期组）。 */
    @Volatile protected var refreshScope: String = "full"

    protected fun deletionJson(
        status: String,
        canCancel: Boolean,
        reason: String? = null,
        applyAt: String = "2026-10-14T10:00:00+08:00",
        coolingUntil: String = "2026-10-21T10:00:00+08:00",
    ): String {
        val cancelledAt = if (status == "cancelled") "\"2026-10-15T09:00:00+08:00\"" else "null"
        return """{"status":"$status","apply_at":"$applyAt","cooling_until":"$coolingUntil","can_cancel":$canCancel,""" +
            """"cancelled_at":$cancelledAt,"cancel_reason":${reason?.let { "\"$it\"" } ?: "null"},"processed_at":null}"""
    }

    private fun walletJson(negative: Long): String {
        val withdrawable = if (negative > 0) 0 else 1234
        return """{"available_fen":${if (negative > 0) -negative else 1234},"withdrawable_fen":$withdrawable,"negative_fen":$negative,""" +
            """"frozen_fen":0,"estimated_total_fen":5678,"estimated_fen":2222,"pending_credit_fen":0,"pending_credit_paused_fen":0,""" +
            """"next_credit_period":null,"credit_overdue":false,"withdrawn_fen":0,"risk_paused_reason":null,"withdraw_enabled":true}"""
    }

    private fun meJson(
        user: String,
        phoneBound: Boolean,
        risk: String,
    ): String {
        val reason = if (risk == "normal") "null" else "\"malicious_rights\""
        return """{"user_id":"$user","nickname":"凑狸用户","avatar_url":"","invite_code":null,"inviter_bound":true,""" +
            """"invite_backfill":{"eligible":false,"deadline_at":null},"identity_level":"normal","phone_bound":$phoneBound,""" +
            """"balance":{"available_fen":1234,"withdrawable_fen":1234,"estimated_total_fen":5678},"union_bindings":[],""" +
            """"realname_status":"verified","need_reconsent":false,"risk":{"state":"$risk","reason_category":$reason,""" +
            """"frozen_until":null}}"""
    }

    // ---------------------------------------------------------------- 搭建

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

    protected class TestPush : PushTokenSource {
        override fun start() = Unit

        override fun stop() = Unit

        override fun current(): String = "test-push-token-A"

        override fun subscribe(listener: () -> Unit): () -> Unit = {}
    }

    private class NoStores : StoreAccess {
        override val installSource: String? = null

        override fun canOpen(storeKey: String) = false

        override fun open(storeKey: String): Boolean = false
    }

    /** 注销 / 换号页经 AccountEnv.forceUpdate 进强更的次数（R1-07：只在网络层判定 FORCE_UPDATE 时才调）。 */
    @Volatile protected var accountForcedCalls = 0

    /** 客服出口被调用的次数（统一客服出口，APP-12）。 */
    protected var csOpened = 0

    @Before
    fun startServer() {
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
                    val path = URLDecoder.decode(request.path.orEmpty().substringBefore('?'), "UTF-8")
                    val recorded = Recorded(request.method.orEmpty(), path, request.body.readUtf8(), request.headers.toMap())
                    synchronized(requests) { requests += recorded }
                    val one = synchronized(queued) { queued["${recorded.method} $path"]?.removeFirstOrNull() }
                    return one?.invoke(recorded) ?: defaultAnswer(recorded)
                }
            }
        server.start()
    }

    @After
    fun stopServer() = server.shutdown()

    /**
     * 搭起整套（与 AppModule 同一套接线）。[user] 为 null = 游客；[scope] 会话作用域；[forced] = 冷启动即强更（AppUpdate 服务端判定）。
     */
    @Suppress("LongMethod")
    protected fun launch(
        user: String? = USER_A,
        scope: String = "full",
        forced: Boolean = false,
        decision: String = "consented",
    ) {
        val gate =
            PrivacyGate(MemoryStore(decision), clock = { now }, bundledPolicyVersion = "3").apply {
                retryScheduler = RetryScheduler { _, _ -> }
            }
        privacy = AppPrivacy(gate, environmentSettled = { true }, clock = { now }).also { it.coldStart() }
        val environment = EnvironmentGate { EnvironmentStartupReconciler.Outcome.UNCHANGED }.also { it.settleAtStartup() }
        tokens = if (user == null) MemorySessionTokens() else MemorySessionTokens("access-$user", "refresh-$user", scope, user)
        val updateStore = MemoryUpdateStore()
        update =
            AppUpdate(
                gate =
                    UpdateGate(
                        updateStore,
                        clock = { now },
                        appVersion = "1.0.0",
                        platform = "android",
                        channel = "official",
                        installSource = null,
                    ),
                store = updateStore,
                checker = { CompletableDeferred<UpdateCheckOutcome>().await() },
                stores = NoStores(),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                canCheck = { false },
                clock = { now },
            )
        if (forced) {
            update.onServerForced()
            networkUpdate = UpdateRaw.BELOW
        }
        routing =
            AppRouting(
                context,
                environment,
                clock = { now },
                initialPrivacy = privacy.conclusion,
                privacyHooks = RoutingPrivacyHooks(promptShowing = { privacy.promptShowing }, credentialsReadable = { true }),
                initialUpdate = update.routerUpdate,
            ).also { it.availabilityOverrides = { override } }
        privacy.onConclusionChanged { routing.privacy.value = it }
        update.onRouterUpdate { routing.update.value = it }
        buildClient()
        val store =
            ConfigStore(
                readFile = { null },
                writeFile = {},
                fetcher = { _, done -> done(ConfigReply(ConfigReply.OK, "{}", null)) },
                clock = { now },
                bundled = "{}",
            )
        composeRule.runOnUiThread {
            login =
                AppLogin(
                    api,
                    tokens,
                    routing,
                    updateCheck = { update.routerUpdate },
                    markBelow = { update.onServerForced() },
                    legal = LegalVersions(3, 2),
                )
            login.syncSession()
            me =
                AppMe(
                    client = api,
                    tokens = tokens,
                    routing = routing,
                    privacy = privacy,
                    store = store,
                    login = { login },
                    updateCheck = { update.routerUpdate },
                    serverForced = { update.onServerForced() },
                    writer = { false },
                    local = MeLocalStore(context),
                    mask = null,
                    system = NoSystem,
                    pushSource = pushSource,
                    nickname = null,
                    notify = null,
                    clock = { now },
                ).also { it.connect { listener -> update.onRouterUpdate(listener) } }
            account =
                AppAccount(
                    client = api,
                    tokens = tokens,
                    routing = routing,
                    me = me,
                    wallet = null,
                    login = login,
                    stepUpPhone = { ownedPhone },
                    updateCheck = { update.routerUpdate },
                    serverForced = {
                        accountForcedCalls += 1
                        update.onServerForced()
                    },
                    clock = { now },
                )
            me.account = account
            account.sync()
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
                    update = update,
                    login = login,
                    me = me,
                )
            }
        }
        settle()
    }

    private val main = android.os.Handler(Looper.getMainLooper())

    private fun buildClient() {
        pendingStore = PendingKeyStore(pendingBackend) { now }
        api =
            ApiClient(
                identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.0.0", build = "1", channel = "official"),
                baseUrl = { server.url("/").toString() },
                context = {
                    NetworkContext(
                        PrivacyRaw.FULL,
                        environmentSettled = true,
                        credentialsReadable = true,
                        networkUpdate,
                    )
                },
                device = Device,
                tokens = tokens,
                pendingKeys = pendingStore,
                hooks =
                    NetworkHooks(
                        // 与 AppModule 同一套：整体 10405 进强更、刷新失败清会话同步守卫、会话保存后同步登录与「我的」
                        onForceUpdateChecked = { min, stillValid -> main.post { if (stillValid()) update.on10405(min) } },
                        onEffect = { effect -> main.post { if (::login.isInitialized) login.onNetworkEffect(effect) } },
                        onSessionStored = { generation ->
                            main.post {
                                if (::login.isInitialized) login.onSessionStored(generation)
                                if (::me.isInitialized) me.syncIdentity()
                            }
                        },
                        navigationTicket = { routing.navTickets.ticket() },
                        onRiskNotice = { ticket, valid -> main.post { if (::me.isInitialized) me.on10006(ticket, valid) } },
                    ),
                inFlight = inFlight,
                sleeper = {},
            )
    }

    // ---------------------------------------------------------------- 工具

    protected fun settle() {
        repeat(SETTLE_ROUNDS) {
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.waitForIdle()
            Thread.sleep(POLL_MS)
        }
    }

    protected fun idleUntil(
        message: String = "",
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S)
        while (!condition() && System.nanoTime() < deadline) settle()
        assertTrue("$message 栈 ${stack()}，请求 ${all()}", condition())
    }

    protected fun has(tag: String): Boolean = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    protected fun hasText(text: String): Boolean =
        composeRule.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    protected fun textOf(tag: String): String =
        composeRule
            .onAllNodesWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .firstOrNull()
            ?.config
            ?.getOrNull(SemanticsProperties.Text)
            ?.joinToString("") { it.text }
            .orEmpty()

    protected fun enabled(tag: String): Boolean =
        composeRule
            .onAllNodesWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .firstOrNull()
            ?.let { it.config.getOrNull(SemanticsProperties.Disabled) == null && it.config.getOrNull(SemanticsActions.OnClick) != null } ==
            true

    protected fun click(tag: String) {
        val node = composeRule.onNodeWithTag(tag, useUnmergedTree = true)
        // 不在滚动容器里的控件（强更页次要入口、底部固定区）直接点
        runCatching { node.performScrollTo() }
        node.performClick()
        settle()
    }

    /** 不滚动直接点（底部固定区、弹窗里的控件）。 */
    protected fun tap(tag: String) {
        composeRule.onNodeWithTag(tag, useUnmergedTree = true).performClick()
        settle()
    }

    protected fun type(
        tag: String,
        text: String,
    ) {
        composeRule.onNodeWithTag(tag, useUnmergedTree = true).performTextReplacement(text)
        settle()
    }

    @SuppressLint("RestrictedApi")
    protected fun stack(): List<String> =
        navController.currentBackStack.value
            .filter { it.destination.hasRoute(RouteDestination::class) }
            .map { it.toRoute<RouteDestination>().name }

    protected fun top(): String? = stack().lastOrNull()

    protected fun all(): List<Recorded> = synchronized(requests) { requests.toList() }

    protected fun all(
        method: String,
        path: String,
    ): List<Recorded> = all().filter { it.method == method && it.path == path }

    protected fun open(
        route: String,
        params: JsonObject = JsonObject(emptyMap()),
    ) {
        composeRule.runOnUiThread { routing.router.open(RouteRequest(route, params, RouteSource.IN_APP)) }
        settle()
    }

    /** 从设置页进注销页（来源 settings，经真实设置行与 Router）。 */
    protected fun openDeleteFromSettings() {
        open(SETTINGS)
        idleUntil("设置页注销行") { has(DELETE_ROW) }
        click(DELETE_ROW)
        idleUntil("注销页") { top() == DELETE_ACCOUNT && has(AccountTags.DELETE_PAGE) }
        idleUntil("注销页读取完成") {
            has(AccountTags.DELETE_SUBMIT) || has(AccountTags.DELETE_COOLING) || has(AccountTags.DELETE_LOAD_ERROR) ||
                has(AccountTags.DELETE_PROCESSING)
        }
    }

    /** 从设置页进换号子页（经真实手机号行）。 */
    protected fun openPhoneChange() {
        open(SETTINGS)
        idleUntil("设置页手机号行") { has(PHONE_ROW) && enabled(PHONE_ROW) }
        click(PHONE_ROW)
        idleUntil("换号子页") { has(AccountTags.PHONE_PAGE) }
    }

    /** 第 1 步：发码 → 输入 → 下一步，进第 2 步。 */
    protected fun passStep1() {
        click(AccountTags.PHONE_STEP1_SEND)
        idleUntil("第 1 步发码") { all("POST", SMS).isNotEmpty() }
        type(AccountTags.PHONE_STEP1_CODE, CODE_OK)
        tap(AccountTags.PHONE_STEP1_NEXT)
        idleUntil("进第 2 步") { has(AccountTags.PHONE_STEP2) }
    }

    /** 第 2 步：输入新号、发码、输入验证码。 */
    protected fun fillStep2(
        phone: String = PHONE_NEW,
        code: String = CODE_OK,
    ) {
        type(AccountTags.PHONE_NEW, phone)
        click(AccountTags.PHONE_STEP2_SEND)
        type(AccountTags.PHONE_STEP2_CODE, code)
    }

    /** 二次验证面板：输入验证码并验证。 */
    protected fun passStepUpSheet(code: String = CODE_OK) {
        idleUntil("二次验证面板") { has(STEP_UP_SHEET) }
        type(STEP_UP_CODE, code)
        tap(STEP_UP_SUBMIT)
    }

    /** 经真实登录页短信登录（测试号 13800005678 / 222222）。 */
    protected fun loginThroughPage() {
        idleUntil("登录页") { has(com.couli.feature.login.LoginTags.PHONE) }
        type(com.couli.feature.login.LoginTags.PHONE, PHONE_OLD)
        if (!has(com.couli.feature.login.LoginTags.CODE)) {
            click(com.couli.feature.login.LoginTags.AGREE)
            click(com.couli.feature.login.LoginTags.GET_CODE)
            if (has(com.couli.feature.login.LoginTags.CONFIRM_OK)) tap(com.couli.feature.login.LoginTags.CONFIRM_OK)
        }
        idleUntil("验证码输入") { has(com.couli.feature.login.LoginTags.CODE) }
        type(com.couli.feature.login.LoginTags.CODE, "222222")
        settle()
        // 输满 6 位自动登录；没有自动发出时才点【登录】（再点会作废在途的那一次）
        if (all("POST", "/v1/auth/login/sms").isEmpty() && has(com.couli.feature.login.LoginTags.SUBMIT)) {
            tap(com.couli.feature.login.LoginTags.SUBMIT)
        }
        idleUntil("登录完成") { all("POST", "/v1/auth/login/sms").isNotEmpty() && tokens.accessToken != null }
    }

    /** 切换会话（会话失效 / 换账号 / 同账号换作用域）：令牌整组替换，登录与「我的」同步。 */
    protected fun switchSession(
        user: String?,
        scope: String = "full",
    ) {
        composeRule.runOnUiThread {
            if (user == null) tokens.clear() else tokens.save("access-$user", "refresh-$user", scope, user)
            login.syncSession()
            me.syncIdentity()
        }
        settle()
    }

    /**
     * 进程被杀后的冷启动：上一个进程留下的在途记录（[PendingKeyStore.IN_FLIGHT]）直接写进同一个持久化后端，[launch] 之后网络层按账号恢复为结果未知。
     */
    @Suppress("LongParameterList")
    protected fun seedInFlight(
        operationId: String,
        key: String,
        body: String,
        summary: String,
        account: String = USER_A,
        createdAt: Long = now,
    ) {
        pendingBackend.write(PendingKeyRecord(key, operationId, body, account, summary, PendingKeyStore.IN_FLIGHT, false, createdAt))
    }

    protected fun records(account: String = USER_A): List<PendingKeyRecord> = pendingBackend.list(account)

    protected fun t(key: String): String = AccountDraftTexts.text(key)

    protected fun body(recorded: Recorded): JsonObject = recorded.json()

    companion object {
        const val USER_A = "user-a"
        const val USER_B = "user-b"
        const val DELETE_ACCOUNT = "DeleteAccount"
        const val SETTINGS = "Settings"
        const val FORCE_UPDATE = "ForceUpdate"
        const val CODE_OK = "123456"
        const val PHONE_OLD = "13800005678"
        const val PHONE_NEW = "13900001234"
        const val DELETION = "/v1/me/deletion"
        const val CANCEL = "/v1/me/deletion/cancel"
        const val BIND = "/v1/me/phone"
        const val SMS = "/v1/auth/sms-codes"
        const val STEP_UP = "/v1/auth/step-up"
        const val ABANDON = "/v1/idempotency-keys/abandon"
        const val WALLET = "/v1/wallet/summary"
        const val ME = "/v1/me"
        const val DELETE_ROW = MeTags.SETTINGS_ROW_PREFIX + "settings.delete_account"
        const val PHONE_ROW = MeTags.SETTINGS_ROW_PREFIX + "settings.phone"
        const val STEP_UP_SHEET = "step_up_sheet"
        const val STEP_UP_CODE = "step_up_code"
        const val STEP_UP_SUBMIT = "step_up_submit"
        const val STEP_UP_CLOSE = "step_up_close"
        const val WAIT_S = 10L
        const val POLL_MS = 15L
        const val SETTLE_ROUNDS = 4
        const val DAY_MS = 86_400_000L

        /** BR-ID-31 的 10006 白名单（注销三接口、作废、二次验证与发码、Me、配置、刷新）。 */
        val BANNED_WHITELIST =
            listOf(
                "/v1/me/deletion",
                "/v1/idempotency-keys/abandon",
                "/v1/auth/step-up",
                "/v1/auth/refresh",
                "/v1/config",
                "/v1/me/appeals",
                "/v1/withdrawals",
            )
    }
}
