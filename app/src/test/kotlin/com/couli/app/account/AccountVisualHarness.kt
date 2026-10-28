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
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText as textMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import com.couli.app.login.AppLogin
import com.couli.app.me.AppMe
import com.couli.app.me.MeSystemPorts
import com.couli.app.privacy.AppPrivacy
import com.couli.app.product.productPixels
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
import com.couli.feature.login.BindPhoneViewModel
import com.couli.feature.login.LegalVersions
import com.couli.feature.login.PendingInviteCode
import com.couli.feature.me.AccountTags
import com.couli.feature.me.MeTags
import java.net.URLDecoder
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import org.junit.Before
import org.junit.Rule
import org.robolectric.Shadows.shadowOf

/**
 * APP-13v 注销视觉测试的宿主（测试作者新增；逐行照抄 [AccountHarness] 的真链路搭法，AccountHarness 本身被三份已合并守卫冻结、不改）。差别只有：
 * ① setContent 外面包 [com.couli.core.designsystem.CouliTheme]（深色截图）与 LocalDensity（200% 字号），并在主题里取一次令牌色（[palette]）；
 * ② 时钟默认取 specs/account-visual-vectors.json 的 clock（2026-10-12 10:30 +08:00）；③ 钱包摘要可整体覆盖（[ServerState.wallet]）；
 * ④ 额外的量具入口（节点、点击动作、客服半屏序号、路由打开记录）。以下保留原注释：
 *
 * APP-13a 集成测试的搭法（brief §2 第 13 项）：真实 AppShell + NavHost + AppRouting + AppPrivacy + AppLogin + AppMe + AppAccount + AppUpdate + ApiClient，
 * 只把传输层换成 MockWebServer（[serverState] 是可编程的本地假服务：注销进度、钱包摘要、phone_bound、二次验证、幂等结果、作废）。页面经真实 Router 打开、
 * 经页面控件操作；抓包断言用 [requests]。账号、令牌、号码都是明显的测试值。
 */
@Suppress("TooManyFunctions", "LargeClass", "UnnecessaryAbstractClass")
abstract class AccountVisualHarness {
    @get:Rule
    val composeRule = createComposeRule()

    protected val context: Context = ApplicationProvider.getApplicationContext()

    @Volatile protected var now: Long = OffsetDateTime.parse(AccountVisualFixtures.clock).toInstant().toEpochMilli()

    protected lateinit var server: MockWebServer
    protected lateinit var routing: AppRouting
    protected lateinit var navController: NavHostController
    protected lateinit var lastLoginMethod: com.couli.feature.login.LastLoginMethodStore
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

        /** 钱包摘要整体覆盖（fixture wallet.*）；null = 按 [negativeFen] 生成。 */
        @Volatile var wallet: String? = null

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

    /**
     * 持续覆盖回答（"METHOD path" → 回答；测试 v2 T1）：设好之后**每一次**该请求都按它回答，直到测试结束。注销页进入时执行器创建即 load() 一次、
     * OnResumed 再 load() 一次，同类读取按序号只收最新——一次性回答落在被作废的那次读取上就看不到；进入期的读取失败 / 停住用它，不依赖读取次数。
     * 一次性队列（[enqueue]）先于它。
     */
    private val persistent = mutableMapOf<String, (Recorded) -> MockResponse>()

    protected fun answerAlways(
        method: String,
        path: String,
        answer: (Recorded) -> MockResponse,
    ) {
        synchronized(queued) { persistent["$method $path"] = answer }
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
                meHold?.await(com.couli.app.FlowWait.CEILING_MS, TimeUnit.MILLISECONDS)
                envelope(meJson(user, st.phoneBound, st.risk))
            }

            request.path == "/v1/me/deletion" && request.method == "GET" -> {
                envelope(st.deletion)
            }

            request.path == "/v1/wallet/summary" -> {
                envelope(st.wallet ?: walletJson(st.negativeFen))
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

    protected fun walletJson(negative: Long): String {
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
                    val one =
                        synchronized(queued) {
                            queued["${recorded.method} $path"]?.removeFirstOrNull() ?: persistent["${recorded.method} $path"]
                        }
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
        lastLoginMethod =
            com.couli.app.AppModule
                .lastLoginMethodStore(
                    context,
                    com.couli.core.privacy
                        .CredentialCleanerRegistry(),
                )
        lastLoginMethod.clear()
        composeRule.runOnUiThread {
            login =
                AppLogin(
                    api,
                    tokens,
                    routing,
                    updateCheck = { update.routerUpdate },
                    markBelow = { update.onServerForced() },
                    legal = LegalVersions(3, 2),
                    // FU-AND-0716 窄修四：与缺省值同一种作用域（SupervisorJob + Main.immediate），只是由测试持有，用来等会话级任务（绑定 / 确认）结束
                    sessionScope = loginSessionScope,
                    pendingInvite = PendingInviteCode(),
                    lastLoginMethod = lastLoginMethod,
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
            val density = androidx.compose.ui.platform.LocalDensity.current
            com.couli.core.designsystem.CouliTheme(darkTheme = dark) {
                palette.Capture()
                androidx.compose.runtime.CompositionLocalProvider(
                    com.couli.core.designsystem.LocalCouliAnnouncer provides
                        com.couli.core.designsystem
                            .CouliAnnouncer { text -> synchronized(announced) { announced += text } },
                    androidx.compose.ui.platform.LocalDensity provides
                        androidx.compose.ui.unit
                            .Density(density.density, fontScale),
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
        // F-AND-36（FU-AND-0716）：等流程交回结论，上限见 FlowWait（原为 10 秒，负载约 70 时 lesson19 等到期重取超时一次）
        com.couli.app.FlowWait
            .until(::settle, { "$message 栈 ${stack()}，请求 ${all()}" }, condition = condition)
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

    /** AppLogin 的会话级作用域（与生产缺省同构）：绑手机的绑定 / 确认等会话级任务在这里执行。 */
    protected val loginSessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** [loginSessionScope] 里此刻在跑的协程。 */
    protected fun loginJobs(): Set<Job> =
        loginSessionScope.coroutineContext[Job]!!
            .children
            .filter { it.isActive }
            .toSet()

    /** [before] 之后在 [loginSessionScope] 里起的协程都已结束（会话级任务连同收尾跑完）。 */
    protected fun loginWorkSince(
        before: Set<Job>,
        message: String,
    ) = idleUntil(message) {
        loginSessionScope.coroutineContext[Job]!!
            .children
            .none { it.isActive && it !in before }
    }

    /** 三个会话级作用域（AppLogin 会话、AppAccount、AppMe）在某一刻在跑的协程（含常驻订阅）。 */
    protected data class WorkSnapshot(
        val login: Set<Job>,
        val account: Set<Job>,
        val me: Set<Job>,
    )

    private fun active(scope: CoroutineScope): Set<Job> =
        scope.coroutineContext[Job]!!
            .children
            .filter { it.isActive }
            .toSet()

    /** FU-AND-0716 窄修五：动作前一刻的快照（绑定 / 确认 / 读取与其后的 getMe、共享收尾分别落在这三个作用域里）。 */
    protected fun workSnapshot(): WorkSnapshot =
        WorkSnapshot(
            login = active(loginSessionScope),
            account = active(account.deps.service),
            me = active(me.deps.service),
        )

    /** [before] 之后在三个作用域里起的协程是否还有在跑的。 */
    protected fun workPendingSince(before: WorkSnapshot): Boolean =
        (active(loginSessionScope) - before.login).isNotEmpty() ||
            (active(account.deps.service) - before.account).isNotEmpty() ||
            (active(me.deps.service) - before.me).isNotEmpty()

    /** [before] 之后在三个作用域里起的协程都已结束（含它们引起的后续读取与共享收尾）。[onPoll] 每轮核对前调用（测试用它显式放行挂住的应答）。 */
    protected fun workSince(
        before: WorkSnapshot,
        message: String,
        onPoll: () -> Unit = {},
    ) = idleUntil(message) {
        onPoll()
        !workPendingSince(before)
    }

    /** 账号服务作用域（AppAccount 的会话级作用域，Main.immediate）里此刻在跑的协程（含常驻订阅）。 */
    protected fun accountJobs(): Set<Job> =
        account.deps.service.coroutineContext[Job]!!
            .children
            .filter { it.isActive }
            .toSet()

    /**
     * FU-AND-0716 窄修三：[before] 之后在账号服务作用域里起的协程都已结束（提交 / 确认 / 读取的续体跑完结论才结束；常驻订阅在 [before] 里，不算）。
     * 只用在动作前一刻取的快照上（进页会起常驻协程，不能跨进页使用）。[onPoll] 每轮核对前调用。
     */
    protected fun accountWorkSince(
        before: Set<Job>,
        message: String,
        onPoll: () -> Unit = {},
    ) = idleUntil(message) {
        onPoll()
        account.deps.service.coroutineContext[Job]!!
            .children
            .none { it.isActive && it !in before }
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
        // FU-AND-0716 窄修二：发码请求、应答与页面结算都在后台；原写法点完固定轮次就输码，随后直接读「最后一次发码请求」，发码晚到时读到的是第 1 步那次。
        // 等页面离开「发送中」（点击即同步进入发送中、按钮置灰；结论落地后【获取验证码】进入倒计时，或失败后恢复可点）。同号仍在倒计时时点击不发码，
        // 这里立即成立
        idleUntil("第 2 步发码结算") { hasText(STEP2_COUNTDOWN_TEXT) || enabled(AccountTags.PHONE_STEP2_SEND) }
        type(AccountTags.PHONE_STEP2_CODE, code)
    }

    /**
     * FU-AND-0716 窄修二：二次验证面板已出现、且这次发码已有结论（离开 SENDING：「正在发送验证码…」不在）。面板一出现就在 SENDING，【验证】要 READY 才受理；
     * 原写法只等面板出现就输码点【验证】，发码晚到时点击白点、流程停住。
     */
    protected fun stepUpReady(): Boolean = has(STEP_UP_SHEET) && has(STEP_UP_CODE) && !hasText(STEP_UP_SENDING_TEXT)

    /**
     * FU-AND-0716 窄修二：勾选确认框并点【申请注销】。确认框与【申请注销】可点都要注销页的进度 / 资金 / 手机号读取已落地（waiverEnabled、
     * applyEnabledWhenChecked），读取在后台；原写法进页后固定轮次就勾选、点击，读取晚到时两次点击都落在置灰控件上，申请根本没发出。先等各自可点再点。
     */
    protected fun checkWaiverAndSubmit() {
        checkWaiver()
        idleUntil("【申请注销】可点") { enabled(AccountTags.DELETE_SUBMIT) }
        tap(AccountTags.DELETE_SUBMIT)
    }

    /** FU-AND-0716 窄修六：勾选确认框。它要注销页的进度 / 资金 / 手机号读取已落地才可点；原写法进页后固定轮次就点，读取晚到时点在置灰控件上。先等可点。 */
    protected fun checkWaiver() {
        idleUntil("确认框可勾选（注销页读取已落地）") { enabled(AccountTags.DELETE_WAIVER) }
        tap(AccountTags.DELETE_WAIVER)
    }

    /** 栈里最上面那个绑手机页的 ViewModel（与页面同一个实例）。 */
    protected fun bindPhonePage(): BindPhoneViewModel {
        lateinit var model: BindPhoneViewModel
        composeRule.runOnUiThread {
            val entry =
                navController.currentBackStack.value.last {
                    it.destination.hasRoute(RouteDestination::class) && it.toRoute<RouteDestination>().name == "BindPhone"
                }
            model = ViewModelProvider.create(entry)[BindPhoneViewModel::class]
        }
        return model
    }

    /**
     * FU-AND-0716 窄修二：绑手机页点【获取验证码】并等这次发码在页面结算（[com.couli.feature.login.BindPhoneUiState.resultSeq] 推进）。原写法点完固定轮次就输码，
     * 发码晚到时输满 6 位不自动提交、【提交】也未就绪，随后「假服务没收到绑定就点【提交】」白点，流程停住。
     */
    protected fun sendBindCode() {
        val model = bindPhonePage()
        val seq = model.state.value.resultSeq
        tap(com.couli.feature.login.BindPhoneTags.GET_CODE)
        idleUntil("绑手机发码结算") { model.state.value.resultSeq > seq }
    }

    /**
     * 绑手机页输满验证码后：没有自动提交时才点【提交】。FU-AND-0716 窄修二：「有没有在提交」看页面同步状态（submitting）与【提交】是否可点，
     * [requireNoRequest] 为 true 时另保留原条件「假服务还没收到绑定请求」；不再只凭固定轮次后假服务的记录决定（请求晚到会误判、多点一次）。
     */
    protected fun submitBindIfNotStarted(requireNoRequest: Boolean) {
        val submit = com.couli.feature.login.BindPhoneTags.SUBMIT
        val noRequest = !requireNoRequest || all("POST", BIND).isEmpty()
        val idle = !bindPhonePage().state.value.submitting
        val clickable = has(submit) && enabled(submit)
        if (noRequest && idle && clickable) tap(submit)
    }

    /** 二次验证面板：输入验证码并验证。 */
    protected fun passStepUpSheet(code: String = CODE_OK) {
        idleUntil("二次验证面板（发码已有结论）") { stepUpReady() }
        type(STEP_UP_CODE, code)
        tap(STEP_UP_SUBMIT)
    }

    /** 经真实登录页短信登录（测试号 13800005678 / 222222）。 */
    protected fun loginThroughPage() {
        idleUntil("登录页") { has(com.couli.feature.login.LoginTags.PHONE) }
        type(com.couli.feature.login.LoginTags.PHONE, PHONE_OLD)
        if (!has(com.couli.feature.login.LoginTags.CODE)) {
            click(com.couli.feature.login.LoginTags.GET_CODE)
            idleUntil("按发码动作显示确认框") { has(com.couli.feature.login.LoginTags.CONFIRM_PRIMARY) }
            check(hasText(t("login.agree_confirm.btn_get_code")))
            tap(com.couli.feature.login.LoginTags.CONFIRM_PRIMARY)
        }
        idleUntil("验证码输入") { has(com.couli.feature.login.LoginTags.CODE) }
        check(!has("login_submit") && !has(com.couli.feature.login.LoginTags.AGREE))
        type(com.couli.feature.login.LoginTags.CODE, "222222")
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

    // ---------------------------------------------------------------- APP-13v 追加（主题、字号、量具入口）

    /** 深色（DeleteAccountDark / DeleteAccountCoolingDark 与无稿深色抽查）；[launch] 之前设。 */
    protected var dark: Boolean = false

    /** 字号倍数（200% 档）；[launch] 之前设。 */
    protected var fontScale: Float = 1f

    /** 渲染时的令牌色（页面不写色值，测试也只比令牌）。 */
    protected val palette = AccountVisualPalette()

    protected fun node(tag: String): SemanticsNodeInteraction = composeRule.onNodeWithTag(tag, useUnmergedTree = true)

    protected fun nodes(tag: String): List<SemanticsNode> = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()

    /** 文字恰为 [text]（或包含，[substring]）的未合并节点。 */
    protected fun textNode(
        text: String,
        substring: Boolean = false,
    ): SemanticsNodeInteraction = composeRule.onNode(textMatcher(text, substring = substring), useUnmergedTree = true)

    protected fun textCount(
        text: String,
        substring: Boolean = false,
    ): Int = composeRule.onAllNodes(textMatcher(text, substring = substring), useUnmergedTree = true).fetchSemanticsNodes().size

    /** 节点上已注册的点击动作（「旧回调」取自真实页面，APP-12w-c 测试 v2 T4）。 */
    protected fun clickAction(node: SemanticsNodeInteraction): () -> Unit {
        val action =
            node
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsActions.OnClick)
                ?.action
        requireNotNull(action) { "节点没有点击动作" }
        return { action.invoke() }
    }

    /**
     * 渲染时的旧回调：先取 [tag] 节点的点击动作，再在**同一个主线程任务**里换账号（控制器的 identity.current 立即失效），紧接着调旧动作——两者之间不可能重组，
     * 调到的一定是渲染时捕获旧执行器 / 旧版本的 onClick（Compose 语义点击转发到节点「当前」onClick 的坑）。
     */
    protected fun invokeStaleAfterSwitch(
        tag: String,
        to: String = USER_B,
    ) {
        val stale = clickAction(node(tag))
        composeRule.runOnUiThread {
            tokens.save("access-$to", "refresh-$to", "full", to)
            stale()
            login.syncSession()
            me.syncIdentity()
        }
        settle()
    }

    /** 统一客服出口打开过的次数（CsSheet.serial 每次打开加 1）。 */
    protected fun csOpens(): Long =
        me.cs.sheet.value
            ?.serial ?: 0L

    /** 客服出口最后一次的发起页路由名。 */
    protected fun csFrom(): String? =
        me.cs.sheet.value
            ?.fromRoute

    /** 截至此刻 Router 记下的最后一次打开（每次真实打开都会换一个新的 RouteOutcome 实例）。 */
    protected fun lastRoute(): com.couli.core.router.RouteOutcome? = routing.router.lastOutcome.value

    /** 经 AppAccount 的真实来源交接打开注销页（风控 / 设置 / 强更三档来源，G-D12）。 */
    protected fun openDeleteFrom(source: String) {
        composeRule.runOnUiThread { account.openDeletionFrom(source) }
        settle()
        idleUntil("注销页") { top() == DELETE_ACCOUNT && has(AccountTags.DELETE_PAGE) }
        idleUntil("注销页读取完成") {
            has(AccountTags.DELETE_SUBMIT) || has(AccountTags.DELETE_COOLING) || has(AccountTags.DELETE_LOAD_ERROR) ||
                has(AccountTags.DELETE_PROCESSING)
        }
    }

    /** 等资金区读取落地（可提现 / 预估收益或资金错误出现）。 */
    protected fun awaitFunds() =
        idleUntil("资金区读取落地") {
            has(AccountTags.DELETE_WITHDRAWABLE) || has(AccountTags.DELETE_FUNDS_ERROR)
        }

    /** fixture 的注销进度报文（null = 从未申请）。 */
    protected fun deletionFixture(name: String): String = AccountVisualFixtures.deletion(name)

    /** fixture 的钱包摘要报文。 */
    protected fun walletFixture(name: String): String = AccountVisualFixtures.wallet(name)

    /** 种入注销未决（fixture pending.deletion：2026-10-12 10:00，无请求体）；冷启动后网络层按账号恢复为结果未知 → 待确认卡。 */
    protected fun seedDeletionPending(serial: String = "visual-deletion-1") =
        seedInFlight(
            AccountVisualFixtures.pendingOperation("deletion"),
            serial,
            "",
            AccountVisualFixtures.pendingSummary("deletion"),
            createdAt = AccountVisualFixtures.pendingCreatedAt("deletion"),
        )

    /** 根节点下的像素（产品量具，View.draw）。 */
    protected fun pixels(): android.graphics.Bitmap = composeRule.productPixels()

    /** 合并树全部节点（读屏视角）。 */
    protected fun mergedNodes(): List<SemanticsNode> =
        composeRule
            .onAllNodes(SemanticsMatcher("全部") { true }, useUnmergedTree = false)
            .fetchSemanticsNodes()

    /** 未合并树全部可点节点。 */
    protected fun clickableNodes(): List<SemanticsNode> =
        composeRule
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick), useUnmergedTree = true)
            .fetchSemanticsNodes()

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
        const val STEP_UP_SENDING_TEXT = "正在发送验证码…"
        const val STEP2_COUNTDOWN_TEXT = "秒后重新获取"
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
