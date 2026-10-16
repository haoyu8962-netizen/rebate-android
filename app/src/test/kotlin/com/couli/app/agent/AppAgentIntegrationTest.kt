package com.couli.app.agent

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.swipeDown
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
import com.couli.app.trade.AndroidJumpPorts
import com.couli.app.trade.AppTrade
import com.couli.core.agent.AgentCall
import com.couli.core.agent.AgentContractPort
import com.couli.core.agent.AgentHttpResult
import com.couli.core.agent.AgentPhase
import com.couli.core.agent.AgentSse
import com.couli.core.agent.AgentStreamHandle
import com.couli.core.agent.AgentStreamSink
import com.couli.core.agent.AgentTimer
import com.couli.core.clientcore.ClientEvent
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.designsystem.CouliTheme
import com.couli.core.network.ApiClient
import com.couli.core.network.ApiClientPageApi
import com.couli.core.network.ClientIdentity
import com.couli.core.network.DeviceCredentials
import com.couli.core.network.InFlightCalls
import com.couli.core.network.MemoryPendingKeyBackend
import com.couli.core.network.MemorySessionTokens
import com.couli.core.network.NetworkContext
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PrivacyRaw
import com.couli.core.network.UpdateRaw
import com.couli.core.privacy.CredentialCleanerRegistry
import com.couli.core.privacy.PrivacyGate
import com.couli.core.privacy.PrivacyStore
import com.couli.core.privacy.RetryScheduler
import com.couli.core.router.GuardKind
import com.couli.core.router.PendingActionStore
import com.couli.core.router.PrivacyState
import com.couli.core.router.RouteRequest
import com.couli.core.router.RouteSource
import com.couli.core.router.SessionState
import com.couli.core.router.UpdateCheck
import com.couli.core.tradejump.UnavailableBaichuan
import com.couli.feature.agent.AgentTags
import com.couli.feature.trade.CardPatch
import com.couli.feature.trade.PurchasePending
import com.couli.feature.trade.PurchasePhase
import com.couli.feature.trade.TradeTags
import com.couli.feature.trade.UnavailableWebCodeAuthorizer
import java.io.File
import java.time.Duration
import java.time.OffsetDateTime
import javax.inject.Provider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
 * Agent 页的本地假服务集成测试（brief §2 第 15 条；红线 §0 第 1–3、6、7 条；APP-10 共性第 2、4、22 条；APP-12 第 2、18 条）：真实 AppShell + NavHost + AppRouting +
 * AppPrivacy + AppAgent + Agent 控制器与页面 + ApiClient（recordConsent 的传输层换成 MockWebServer）；待定 operation 由替身端口以 SSE 文本回放锁定契约的 fixture；
 * 计时由测试推进。页面经 Router 打开、经页面控件操作；数据只有契约 fixture 与合成数据。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h1100dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Suppress("LargeClass", "TooManyFunctions")
class AppAgentIntegrationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var clock = OffsetDateTime.parse("2026-10-06T10:00:00+08:00").toInstant().toEpochMilli()
    private lateinit var server: MockWebServer
    private lateinit var routing: AppRouting
    private lateinit var privacy: AppPrivacy
    private lateinit var gate: PrivacyGate
    private lateinit var tokens: MemorySessionTokens
    private lateinit var api: ApiClient
    private lateinit var agent: AppAgent
    private lateinit var navController: NavHostController
    private lateinit var cleaners: CredentialCleanerRegistry
    private val inFlight = InFlightCalls()
    private val port = Port()
    private val timer = Timer()
    private val mask = MutableStateFlow(false)
    private val events = mutableListOf<ClientEvent>()
    private val logs = mutableListOf<String>()
    private val consentBodies = mutableListOf<String>()
    private var env = "test"
    private var portEnabled = true

    /** openLink 的请求（路径与幂等键）与脚本化回答（R1-02 / R1-03）。 */
    private val openRequests = mutableListOf<Pair<String, String?>>()

    @Volatile
    private var openReply: () -> MockResponse = { json("""{"code":0,"msg":"","data":{},"trace_id":"trace-000123"}""") }

    private inner class Timer : AgentTimer {
        private val pending = mutableListOf<Triple<Long, () -> Unit, BooleanArray>>()

        override fun after(
            delayMs: Long,
            block: () -> Unit,
        ): () -> Unit {
            val cancelled = BooleanArray(1)
            pending += Triple(clock + delayMs, block, cancelled)
            return { cancelled[0] = true }
        }

        fun fire() {
            while (true) {
                val due = pending.firstOrNull { !it.third[0] && it.first <= clock } ?: return
                pending -= due
                due.second()
            }
        }
    }

    private class Stream(
        val sink: AgentStreamSink,
        val call: AgentCall,
    ) : AgentStreamHandle {
        var cancelled = false

        override fun cancel() {
            cancelled = true
        }
    }

    /** 待定 operation 的替身：记录每次实际发出；流由测试推送（fixture 原文转 SSE）。 */
    private inner class Port : AgentContractPort {
        val streams = mutableListOf<Stream>()
        var currents = 0
        var creates = 0
        var current: AgentHttpResult = ok(JsonObject(mapOf("session" to JsonNull)))

        override suspend fun createSession(call: AgentCall): AgentHttpResult {
            if (!call.sendAllowed()) return AgentHttpResult.notSentResult()
            creates += 1
            return ok(JsonObject(mapOf("session" to JsonObject(mapOf("id" to JsonPrimitive("s-$creates"))))))
        }

        override suspend fun currentSession(call: AgentCall): AgentHttpResult {
            if (!call.sendAllowed()) return AgentHttpResult.notSentResult()
            currents += 1
            return current
        }

        override fun sendMessage(
            sessionId: String,
            body: String,
            call: AgentCall,
            sink: AgentStreamSink,
        ): AgentStreamHandle = Stream(sink, call).also { if (call.sendAllowed()) streams += it }

        override suspend fun cancelRun(
            runId: String,
            call: AgentCall,
        ): AgentHttpResult = ok(JsonObject(emptyMap()))

        override fun cancelOwned(identityKey: Any) {
            streams.forEach { it.cancelled = true }
        }

        override suspend fun listMessages(
            sessionId: String,
            cursor: String?,
            limit: Int,
            call: AgentCall,
            autoRetry: Boolean,
        ): AgentHttpResult =
            if (call.sendAllowed()) {
                AgentHttpResult(
                    true,
                    200,
                    0,
                    "ok",
                    JsonObject(
                        mapOf(
                            "items" to kotlinx.serialization.json.JsonArray(emptyList()),
                            "next_cursor" to JsonNull,
                            "expired" to JsonPrimitive(false),
                        ),
                    ),
                    "trace",
                    null,
                )
            } else {
                AgentHttpResult.notSentResult()
            }

        override suspend fun setFeedback(
            messageId: String,
            value: String,
            call: AgentCall,
        ): AgentHttpResult = AgentHttpResult.notSentResult()

        override suspend fun report(
            messageId: String,
            reason: String,
            call: AgentCall,
        ): AgentHttpResult = AgentHttpResult.notSentResult()
    }

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

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path.orEmpty().startsWith("/v1/links/")) {
                        synchronized(openRequests) { openRequests += request.path.orEmpty() to request.getHeader("Idempotency-Key") }
                        return openReply()
                    }
                    if (request.path.orEmpty().startsWith("/v1/consents")) {
                        synchronized(consentBodies) {
                            consentBodies +=
                                request.body.readUtf8()
                        }
                    }
                    return MockResponse()
                        .setHeader(
                            "Content-Type",
                            "application/json",
                        ).setBody("""{"code":0,"msg":"","data":{},"trace_id":"trace-000123"}""")
                }
            }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    @Suppress("LongMethod") // 与 AppModule 同一套接线逐项组装
    private fun launch(
        user: String? = "user-a",
        withTrade: Boolean = false,
        extra: @Composable () -> Unit = {},
    ) {
        gate =
            PrivacyGate(MemoryStore(), clock = { clock }, bundledPolicyVersion = "3").apply { retryScheduler = RetryScheduler { _, _ -> } }
        privacy = AppPrivacy(gate, environmentSettled = { true }, clock = { clock }).also { it.coldStart() }
        val environment = EnvironmentGate { EnvironmentStartupReconciler.Outcome.UNCHANGED }.also { it.settleAtStartup() }
        tokens = if (user == null) MemorySessionTokens() else MemorySessionTokens("access-$user", "refresh-$user", "full", user)
        routing =
            AppRouting(
                context,
                environment,
                clock = { clock },
                initialPrivacy = privacy.conclusion,
                privacyHooks =
                    RoutingPrivacyHooks(promptShowing = {
                        privacy.promptShowing
                    }, credentialsReadable = { privacy.credentialAccess.readable() }),
                initialUpdate = UpdateCheck.OK,
            )
        privacy.onConclusionChanged { routing.privacy.value = it }
        api =
            ApiClient(
                identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.0.0", build = "1", channel = "official"),
                baseUrl = { server.url("/").toString() },
                context = {
                    NetworkContext(
                        when (privacy.conclusion) {
                            PrivacyState.CONSENTED -> PrivacyRaw.FULL
                            PrivacyState.BASIC_MODE -> PrivacyRaw.BASIC
                            PrivacyState.NOT_DECIDED -> PrivacyRaw.UNDECIDED
                        },
                        environmentSettled = true,
                        credentialsReadable = !privacy.credentialAccess.cleanupPending,
                        UpdateRaw.SUPPORTED,
                    )
                },
                device = Device,
                tokens = tokens,
                pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { clock },
                inFlight = inFlight,
                sleeper = {},
            )
        composeRule.runOnUiThread {
            routing.updateSession(if (user == null) SessionState.GUEST else SessionState.PHONE_BOUND, resumePending = false)
            agent =
                AppAgent(
                    client = api,
                    tokens = tokens,
                    routing = routing,
                    privacy = privacy,
                    port = { port.takeIf { portEnabled } },
                    envKey = { env },
                    configData = { null },
                    mask = mask,
                    track = { events += it },
                    serverForced = {},
                    trade =
                        if (withTrade) {
                            AppTrade(
                                api = ApiClientPageApi(api),
                                store = null,
                                routing = routing,
                                tracker = Provider { error("not used") },
                                baichuan = UnavailableBaichuan(),
                                webCode = UnavailableWebCodeAuthorizer,
                                ports = AndroidJumpPorts(context),
                            )
                        } else {
                            null
                        },
                    clock = { clock },
                    timer = timer,
                    log = { logs += it },
                ).also { it.connect() }
            cleaners =
                CredentialCleanerRegistry().apply {
                    register("in_flight_calls") { inFlight.onPrivacyRevoked() }
                    register(AppAgent.AGENT_CONTROLLER_CLEANER) { agent.onConsentWithdrawing() }
                    register(CredentialCleanerRegistry.TOKENS) { tokens.clear() }
                    register(CredentialCleanerRegistry.DEVICE_ID) {}
                    register(CredentialCleanerRegistry.INSTALL_SECRET) {}
                }
            gate.withdrawalCleanup = { cleaners.cleanAll() }
        }
        composeRule.setContent {
            navController = rememberNavController()
            CouliTheme {
                Column {
                    Box(modifier = Modifier.weight(1f)) {
                        AppShell(routing = routing, privacy = privacy, homeContent = {}, navController = navController, agent = agent)
                    }
                    extra()
                }
            }
        }
        settle()
        open("AgentChat")
    }

    // ---------------------------------------------------------------- 工具

    private fun open(route: String) {
        composeRule.runOnUiThread { routing.router.open(RouteRequest(route, JsonObject(emptyMap()), RouteSource.IN_APP)) }
        settle()
    }

    private fun settle() {
        repeat(ROUNDS) {
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.waitForIdle()
        }
    }

    private fun advance(ms: Long) {
        clock += ms
        composeRule.runOnUiThread { timer.fire() }
        settle()
    }

    private fun tree(): String = composeRule.onRoot(useUnmergedTree = true).printToString()

    private fun has(tag: String): Boolean = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun click(tag: String) {
        val node = composeRule.onNodeWithTag(tag, useUnmergedTree = true)
        runCatching { node.performScrollTo() }
        node.performClick()
        settle()
    }

    private fun send(text: String = "帮我找纯牛奶") {
        composeRule.runOnUiThread {
            val c = agent.controller.value!!
            check(c.send(c.snapshot.value.page, text)) { "没有受理：${c.currentPhase}" }
        }
        settle()
    }

    private fun push(
        lines: List<String>,
        stream: Stream = port.streams.last(),
        end: Boolean = true,
    ) {
        composeRule.runOnUiThread {
            stream.sink.onHeaders(200, mapOf("Content-Type" to "text/event-stream"))
            lines.forEach { stream.sink.onData(AgentSse.sseTextOfNdjsonLine(it)) }
            if (end) stream.sink.onEnd()
        }
        settle()
    }

    private fun topRoute(): String? =
        navController.currentBackStackEntry
            ?.takeIf { it.destination.hasRoute(RouteDestination::class) }
            ?.toRoute<RouteDestination>()
            ?.name

    // ---------------------------------------------------------------- 页面引导卡（AC-S1-142 ②、144 ④、145、153）

    @Test
    fun pageGuideOpensOnlyOnUserTapThroughRouterWithAgentCardSource() {
        launch()
        assertEquals(AgentPhase.READY, agent.controller.value!!.currentPhase)
        send("提现进度在哪看")
        push(fixtureLines("normal-page-guide"))
        // 卡片出现后 10 秒不点：不跳转
        advance(10_000)
        assertNull(topRoute())
        assertTrue(tree().contains("提现进度在提现记录里查看。"))
        click(AgentTags.guide("c1"))
        val outcome = routing.router.lastOutcome.value!!
        assertEquals("WithdrawRecords", outcome.request.route)
        assertEquals(JsonObject(emptyMap()), outcome.request.params)
        assertEquals(RouteSource.AGENT_CARD.raw, outcome.request.source)
        assertEquals(GuardKind.OPEN, outcome.decision.kind)
        assertEquals("WithdrawRecords", topRoute())
    }

    @Test
    fun guestTapOnWalletGuideGoesLoginWithAgentCardPendingThenOpensOnceAfterLogin() {
        launch(user = null)
        send("我的钱包在哪")
        push(listOf(META, card("""{"route":"Wallet","text_key":"agent.guide.Wallet"}"""), DONE))
        click(AgentTags.guide("c1"))
        assertEquals("Login", topRoute())
        val pending = routing.router.pendingAction(PendingActionStore.OPEN_ROUTE)
        assertNotNull(pending)
        assertEquals(RouteSource.AGENT_CARD.raw, pending!!.origin)
        assertEquals(JsonObject(mapOf("route" to JsonPrimitive("Wallet"))), pending.params.filterKeys { it == "route" }.let(::JsonObject))
        composeRule.runOnUiThread {
            tokens.save("access-b", "refresh-b", "full", "user-b")
            routing.updateSession(SessionState.PHONE_BOUND, resumePending = true)
        }
        settle()
        assertEquals("Wallet", topRoute())
        assertNull(routing.router.pendingAction(PendingActionStore.OPEN_ROUTE))
    }

    @Test
    fun blockedGuideRoutesHaveNoButtonAndReportOnce() {
        launch()
        send("设置在哪里")
        push(fixtureLines("unknown-card-page-guide"))
        assertFalse(has(AgentTags.guide("c1")))
        send("收款账号在哪")
        push(
            listOf(
                META.replace("000000000101", "000000000111"),
                card("""{"route":"PayoutAccount","text_key":"agent.guide.PayoutAccount"}"""),
                DONE,
            ),
        )
        send("钱包带参数")
        push(
            listOf(
                META.replace("000000000101", "000000000121"),
                card("""{"route":"Wallet","text_key":"agent.guide.Wallet","params":{}}"""),
                DONE,
            ),
        )
        assertFalse(has(AgentTags.guide("c1")))
        assertEquals(listOf("Settings", "PayoutAccount", "Wallet"), events.map { it.fields["route"]?.content })
        assertTrue(events.all { it.name == "route_entry_blocked" && it.fields["entry"]?.content == "in_app" })
        assertNull(topRoute())
    }

    // ---------------------------------------------------------------- 收益卡（AC-S1-148 ③④）

    @Test
    fun earningsButtonsAndMaskThroughRealPage() {
        launch()
        send("我赚了多少")
        push(fixtureLines("normal-earnings"))
        assertTrue(tree().contains("¥56.78"))
        mask.value = true
        settle()
        val masked = tree()
        listOf("¥10", "¥56.78", "¥20").forEach { assertFalse(it, masked.contains(it)) }
        assertTrue(masked.contains("11 月"))
        click(AgentTags.earningsButton("c1", "WithdrawRecords"))
        val outcome = routing.router.lastOutcome.value!!
        assertEquals("WithdrawRecords", outcome.request.route)
        assertTrue(outcome.request.params.isEmpty())
        assertEquals(RouteSource.AGENT_CARD.raw, outcome.request.source)
        // 金额不进日志、埋点
        val sink = (logs + events.map { it.fields.toString() }).joinToString("\n")
        listOf("1000", "5678", "2000").forEach { assertFalse(it, sink.contains(it)) }
    }

    // ---------------------------------------------------------------- 迟到与作废（brief §0 第 3 条，§2 第 15 条）

    @Test
    fun sessionInvalidThenLoginBMidStreamShowsNothingOfA() {
        launch()
        send("帮我找纯牛奶")
        val a = port.streams.last()
        push(fixtureLines("normal").take(6), a, end = false)
        assertTrue(tree().contains("演示纯牛奶"))
        // A 会话失效 → 登录 B → 回 Agent 页
        composeRule.runOnUiThread {
            tokens.clear()
            routing.updateSession(SessionState.GUEST, resumePending = false)
        }
        settle()
        assertTrue(a.cancelled)
        composeRule.runOnUiThread {
            tokens.save("access-b", "refresh-b", "full", "user-b")
            routing.updateSession(SessionState.PHONE_BOUND, resumePending = false)
        }
        settle()
        open("AgentChat")
        // A 的连接迟到的 text.delta / card / done
        composeRule.runOnUiThread { fixtureLines("normal").drop(6).forEach { a.sink.onData(AgentSse.sseTextOfNdjsonLine(it)) } }
        settle()
        val b = agent.controller.value!!
        assertTrue(
            b.snapshot.value.turns
                .isEmpty(),
        )
        assertNull(b.snapshot.value.quotaLeft)
        assertFalse(tree().contains("演示纯牛奶"))
        assertFalse(tree().contains("帮我找纯牛奶"))
        assertTrue(events.isEmpty())
    }

    @Test
    fun privacyWithdrawalStopsStreamSynchronouslyAndClearsConversation() {
        launch()
        send()
        val a = port.streams.last()
        push(fixtureLines("normal").take(6), a, end = false)
        val old = agent.controller.value!!
        composeRule.runOnUiThread { privacy.withdraw() }
        settle()
        assertTrue(a.cancelled)
        assertTrue(old.isDisposed)
        assertTrue(
            old.snapshot.value.turns
                .isEmpty(),
        )
        composeRule.runOnUiThread { fixtureLines("normal").drop(6).forEach { a.sink.onData(AgentSse.sseTextOfNdjsonLine(it)) } }
        settle()
        assertFalse(tree().contains("演示纯牛奶"))
    }

    @Test
    fun environmentSwitchAndNewChatDropLateFrames() {
        launch()
        send()
        val a = port.streams.last()
        push(fixtureLines("normal").take(3), a, end = false)
        composeRule.runOnUiThread {
            agent.onEnvironmentSwitching()
            env = "staging"
            agent.sync()
        }
        settle()
        assertTrue(a.cancelled)
        composeRule.runOnUiThread { fixtureLines("normal").drop(3).forEach { a.sink.onData(AgentSse.sseTextOfNdjsonLine(it)) } }
        settle()
        assertTrue(
            agent.controller.value!!
                .snapshot.value.turns
                .isEmpty(),
        )
        send()
        val b = port.streams.last()
        push(fixtureLines("normal").take(6), b, end = false)
        click(AgentTags.NEW_CHAT)
        assertTrue(b.cancelled)
        composeRule.runOnUiThread { fixtureLines("normal").drop(6).forEach { b.sink.onData(AgentSse.sseTextOfNdjsonLine(it)) } }
        settle()
        assertTrue(
            agent.controller.value!!
                .snapshot.value.turns
                .isEmpty(),
        )
        assertNull(
            agent.controller.value!!
                .snapshot.value.quotaLeft,
        )
    }

    // APP-10b G02（编排者补充第 2 条，03 §7.2 2026-10-14 更正）：原断言「切 Tab 即关连接、回来不续」改为切 Tab 保活（改动限于该裁定范围，原名
    // switchingTabClosesStreamAndComingBackDoesNotResume）：切走期间流照常、不重拉历史、不更新界面，回到 Tab 一次性应用
    @Test
    fun switchingTabKeepsStreamAndComingBackAppliesOnce() {
        launch()
        send()
        val a = port.streams.last()
        push(fixtureLines("normal").take(3), a, end = false)
        val currents = port.currents
        open("Home")
        assertFalse(a.cancelled)
        assertTrue(a.call.sendAllowed())
        // 切走期间到达的帧：并入内存，不交界面
        composeRule.runOnUiThread { fixtureLines("normal").drop(3).forEach { a.sink.onData(AgentSse.sseTextOfNdjsonLine(it)) } }
        settle()
        open("AgentChat")
        assertFalse(tree().contains("连接中断"))
        assertEquals(
            "done",
            agent.controller.value!!
                .snapshot.value.turns
                .single()
                .status,
        )
        assertEquals(1, port.streams.size)
        assertEquals(currents, port.currents)
    }

    // ---------------------------------------------------------------- 同意（BR-ID-14）与设置页撤回（G18）

    @Test
    fun aiConsentWithdrawFromSettingsRecordsFalseAndRebuildsEmpty() {
        launch()
        send()
        push(fixtureLines("normal"))
        val old = agent.controller.value!!
        // 显式等撤回流程交回结论（onText）：控制器在发请求之前就已作废，「请求到达 + 控制器已换」不代表响应已结算（FU-AND-0715：
        // 主线程在同一轮空闲里发出请求时，旧条件在响应回来之前就成立，477cc25 上同样失败）
        val outcome = mutableListOf<String>()
        composeRule.runOnUiThread { agent.withdrawConsent(agent.openWithdrawConfirm { true }!!, { true }) { outcome += it } }
        idleUntil { consentBodies.isNotEmpty() && agent.controller.value !== old && outcome.isNotEmpty() }
        val body = consentBodies.single()
        assertTrue(body, body.contains("\"type\":\"ai_third_party\""))
        assertTrue(body, body.contains("\"accepted\":false"))
        assertTrue(body, body.contains("\"channel\":\"privacy_center\""))
        assertTrue(old.isDisposed)
        assertEquals(1, agent.identity().aiConsent)
        assertTrue(
            agent.controller.value!!
                .snapshot.value.turns
                .isEmpty(),
        )
    }

    // ---------------------------------------------------------------- Release（G01）与进程被杀

    @Test
    fun withoutPortAgentIsUnavailableAndSendsNothing() {
        portEnabled = false
        launch()
        assertTrue(has(AgentTags.UNAVAILABLE))
        assertFalse(has(AgentTags.INPUT))
        assertEquals(0, port.currents + port.creates + port.streams.size)
        assertFalse(agent.withdrawAvailable())
    }

    @Test
    fun processDeathStartsFromEmptyConversation() {
        launch()
        send()
        push(fixtureLines("normal-earnings"))
        // 「新进程」：同样的接线重新构造（对话、卡片、金额只在内存）
        val fresh =
            composeRule.runOnUiThread<AppAgent> {
                AppAgent(api, tokens, routing, privacy, { port }, { env }, { null }, mask, {}, {}, null, clock = { clock }, timer = timer)
            }
        val c = composeRule.runOnUiThread<com.couli.core.agent.AgentController> { fresh.ensure() }
        assertTrue(
            c.snapshot.value.turns
                .isEmpty(),
        )
        assertFalse(c.liveEarningsShown)
    }

    // ---------------------------------------------------------------- Android 第 1 轮评审

    private fun loseSessionToGuest() {
        composeRule.runOnUiThread {
            tokens.clear()
            routing.updateSession(SessionState.GUEST, resumePending = false)
        }
        settle()
    }

    /**
     * 「不应再发请求」的核对之前：先等这次操作自己交回的结论 [decided]（F-AND-36 R1-02：拒绝已落定、或重放的那次响应已被执行器处理完——不看网络层
     * 是否空闲），再留原来的 100 毫秒负向观察窗（观察窗只可能让「没发出」的核对变弱，不会造成偶发失败）。
     */
    private fun quiet(decided: () -> Boolean) {
        idleUntil(decided)
        Thread.sleep(POLL_MS * 5)
        settle()
    }

    /** 购买执行器已处理完最近一次 open 的响应（离开加载态）。 */
    private fun replayAnswered(): Boolean {
        val phase =
            agent
                .purchaseController()
                ?.state
                ?.value
                ?.phase
        return phase != null && phase != PurchasePhase.LOADING
    }

    /** 撤回同意这次确认已落定：确认框实例已作废、没有在途的撤回流程。 */
    private fun withdrawDecided(): Boolean = agent.withdrawConfirm.value == null && !agent.withdrawing.value

    @Test
    fun r1_01_withdrawConfirmIsFixedAtOpenAndDestroyedWhenIdentityChanges() {
        launch(extra = { AgentWithdrawSettingsRow(agent) })
        click(AgentTags.WITHDRAW_ROW)
        assertTrue("确认框应已打开", has(AgentTags.WITHDRAW_CONFIRM))
        val stale = agent.withdrawConfirm.value!!
        // A 会话失效变成游客：旧确认框实例同步销毁（框消失）
        loseSessionToGuest()
        assertNull(agent.withdrawConfirm.value)
        assertFalse("身份变化后旧确认框不得仍在", has(AgentTags.WITHDRAW_CONFIRM))
        // 旧确认实例的回调（保留下来再执行）：提交前比较，失效直接拒绝，不发 recordConsent
        composeRule.runOnUiThread { agent.withdrawConsent(stale, { true }) {} }
        quiet(::withdrawDecided)
        assertTrue(consentBodies.isEmpty())
        // 登录 B 后重新打开、确认：按 B 打开时固定的身份发出一次
        composeRule.runOnUiThread {
            tokens.save("access-b", "refresh-b", "full", "user-b")
            routing.updateSession(SessionState.PHONE_BOUND, resumePending = false)
        }
        settle()
        click(AgentTags.WITHDRAW_ROW)
        val fresh = agent.withdrawConfirm.value!!
        assertTrue(fresh.id != stale.id)
        click(AgentTags.WITHDRAW_CONFIRM)
        idleUntil { consentBodies.isNotEmpty() }
        assertEquals(1, consentBodies.size)
        // 已用过的实例再提交：一次性编号已作废
        composeRule.runOnUiThread { agent.withdrawConsent(fresh, { true }) {} }
        quiet(::withdrawDecided)
        assertEquals(1, consentBodies.size)
    }

    @Test
    fun r1_01_submitOnlyComparesTheIdentityFixedWhenTheConfirmOpened() {
        launch(extra = { AgentWithdrawSettingsRow(agent) })
        click(AgentTags.WITHDRAW_ROW)
        assertNotNull(agent.withdrawConfirm.value)
        // 此刻身份已变（环境不同），但还没经过任何同步出口（确认框实例仍是当前那一个）：提交前比较固定身份，不重新绑定、直接拒绝
        env = "staging"
        click(AgentTags.WITHDRAW_CONFIRM)
        quiet(::withdrawDecided)
        assertTrue("旧身份打开的确认框不得以新身份发出撤回", consentBodies.isEmpty())
        assertNull(agent.withdrawConfirm.value)
    }

    private fun authSheetReply(): MockResponse =
        json(
            """{"code":30101,"msg":"","data":{"auth_url":"https://oauth.example.test/a","state":"st_01",""" +
                """"auth_methods":["web_code"]},"trace_id":"trace-000124"}""",
        )

    /** 点卡片里的购买按钮（不是卡片本身）。 */
    private fun clickBuy(tag: String = AgentTags.buy("c1", "c2")) {
        val node =
            composeRule.onNode(
                hasClickAction() and hasAnyAncestor(hasTestTag(tag)) and hasContentDescription("购买", substring = true),
                useUnmergedTree = true,
            )
        runCatching { node.performScrollTo() }
        node.performClick()
        settle()
    }

    private fun buyFirstProduct() {
        send()
        push(fixtureLines("normal"))
        val before = openRequests.size
        clickBuy()
        idleUntil {
            openRequests.size > before && agent
                .purchaseController()
                ?.state
                ?.value
                ?.phase != PurchasePhase.LOADING
        }
        settle()
    }

    @Test
    fun r1_02_identityChangeReleasesTheAgentPurchaseExecutorAndItsDialog() {
        launch(withTrade = true)
        openReply = ::authSheetReply
        buyFirstProduct()
        assertTrue("AuthSheet 应已弹出", has(TradeTags.AUTH_SHEET))
        val old = agent.purchaseController()!!
        // A 会话失效：执行器同步释放，确认框 / AuthSheet 随之消失
        loseSessionToGuest()
        assertNull(agent.purchaseController())
        assertFalse("身份变化后旧 AuthSheet 不得仍在", has(TradeTags.AUTH_SHEET))
        // 保留下来的旧执行器回调：已释放，不再发请求
        val before = openRequests.size
        composeRule.runOnUiThread {
            old.retryPurchase()
            old.buy(old.currentCard, "AgentChat.card.buy")
        }
        // 已释放的执行器在入口同步拒绝（不进加载态）
        quiet { old.state.value.phase != PurchasePhase.LOADING }
        assertEquals(before, openRequests.size)
    }

    @Test
    fun r1_02_newChatReleasesTheExecutorNotOnlyLoading() {
        launch(withTrade = true)
        openReply = ::authSheetReply
        buyFirstProduct()
        assertTrue(has(TradeTags.AUTH_SHEET))
        val first = agent.purchaseController()!!
        // 【新对话】：卡片动作世代推进 → 执行器同步释放（AuthSheet 不只是 LOADING 也要清掉）
        click(AgentTags.NEW_CHAT)
        assertNull(agent.purchaseController())
        assertFalse(has(TradeTags.AUTH_SHEET))
        // 新对话里再买：新的执行器
        buyFirstProduct()
        val second = agent.purchaseController()!!
        assertTrue(second !== first)
    }

    @Test
    fun r1_02_guestLoginResumesTheSamePurchaseOnceThroughThePendingAction() {
        launch(user = null, withTrade = true)
        openReply = { json("""{"code":10001,"msg":"","data":{},"trace_id":"trace-000125"}""") }
        buyFirstProduct()
        assertEquals("Login", topRoute())
        val key = openRequests.single().second
        assertNotNull(key)
        val executor = agent.purchaseController()!!
        openReply = { json("""{"code":0,"msg":"","data":{},"trace_id":"trace-000126"}""") }
        // 登录完成：游客 → 会员，执行器改绑到新控制器（不释放），回到 Agent 页后按待办自动重放一次，原键
        composeRule.runOnUiThread {
            tokens.save("access-b", "refresh-b", "full", "user-b")
            routing.updateSession(SessionState.PHONE_BOUND, resumePending = true)
        }
        settle()
        open("AgentChat")
        idleUntil { openRequests.size >= 2 }
        // 重放的那次响应已被执行器处理完（离开加载态）
        quiet { replayAnswered() }
        assertEquals(2, openRequests.size)
        assertEquals(key, openRequests.last().second)
        assertTrue(agent.purchaseController() === executor)
        assertNull(routing.router.pendingAction(PurchasePending.ACTION))
        // 新身份的对话从空开始
        assertTrue(
            agent.controller.value!!
                .snapshot.value.turns
                .isEmpty(),
        )
    }

    @Test
    fun r1_03_timeoutBannerRetriesWithTheSameIdempotencyKey() {
        launch(withTrade = true)
        openReply = { MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
        send()
        push(fixtureLines("normal"))
        clickBuy()
        idleUntil { openRequests.isNotEmpty() }
        assertFalse(has(TradeTags.TIMEOUT))
        // 8 秒没有响应：超时提示条出现在 Agent 页
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9))
        settle()
        assertTrue("Agent 页应显示购买超时提示条", has(TradeTags.TIMEOUT))
        val key = openRequests.single().second
        assertNotNull(key)
        // 【重试】：同一执行器、原幂等键
        click(TradeTags.TIMEOUT_RETRY)
        idleUntil { openRequests.size >= 2 }
        assertEquals(key, openRequests[1].second)
        // 再次超时后直接点同一张卡的购买：仍是原键（不 start 新键）
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9))
        settle()
        assertTrue(has(TradeTags.TIMEOUT))
        clickBuy()
        idleUntil { openRequests.size >= 3 }
        assertEquals(key, openRequests[2].second)
        assertEquals(openRequests[0].first, openRequests[2].first)
    }

    /**
     * Android R2-01：同一对话两轮回复里的商品 P1 / P2 外层 card_id 都是 c1、商品 card_id 都是 c2，link_id 分别 L1 / L2。超时后的原键重试只认
     * 这次尝试所属的那张逻辑卡片；点别的轮次的同编号卡用它自己的商品快照新建购买（新键），卡片回写落在原尝试那张卡上。
     */
    @Test
    @Suppress("LongMethod") // 一条完整的跨轮点击序列
    fun r2_01_timeoutThenSameCardIdInAnotherTurnBuysItsOwnLinkWithANewKey() {
        launch(withTrade = true)
        openReply = { MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
        send()
        push(fixtureLines("normal"))
        send("再找一款")
        push(secondTurnLines())
        val turns =
            agent.controller.value!!
                .snapshot.value.turns
        assertEquals(2, turns.size)
        val p1 =
            turns[0]
                .cards
                .first()
                .products
                .first()
        val p2 =
            turns[1]
                .cards
                .first()
                .products
                .first()
        assertEquals(
            turns[0]
                .cards
                .first()
                .card.cardId,
            turns[1]
                .cards
                .first()
                .card.cardId,
        )
        assertEquals(p1.itemCardId, p2.itemCardId)
        assertNotEquals(p1.buy!!.linkId, p2.buy!!.linkId)
        // ① 点 P1 → 8 秒无响应
        clickBuyIn(0)
        idleUntil { openRequests.size >= 1 }
        timeoutNow()
        val (pathL1, k1) = openRequests[0]
        assertTrue(pathL1.contains(LINK_1))
        assertNotNull(k1)
        // ② 再点 P1（同一次尝试所属的卡）：原路径、原键重试
        clickBuyIn(0)
        idleUntil { openRequests.size >= 2 }
        assertEquals(pathL1 to k1, openRequests[1])
        timeoutNow()
        // ③ 点第二轮的 P2：用 P2 的快照新建购买（L2、新键），不重发 L1
        clickBuyIn(1)
        idleUntil { openRequests.size >= 3 }
        val (pathL2, k2) = openRequests[2]
        assertTrue("应打开 P2 的 link_id：$pathL2", pathL2.contains(LINK_2))
        assertFalse(pathL2.contains(LINK_1))
        assertNotEquals(k1, k2)
        // 按钮状态只落在这次尝试所属的那张卡（P2），第一轮同编号的 P1 不显示加载
        composeRule.runOnUiThread {
            val c = agent.purchaseController()!!
            assertTrue(CardPatch.sameCard(agent.purchaseCardOf(c, p2), c.currentCard))
            assertFalse(CardPatch.sameCard(agent.purchaseCardOf(c, p1), c.currentCard))
        }
        timeoutNow()
        // ④ P2 超时后点 P1：P1 不属于这次尝试 → L1 新键（既不是 P2 的原键，也不是 P1 早先已被取代的 k1）
        clickBuyIn(0)
        idleUntil { openRequests.size >= 4 }
        val (path4, k4) = openRequests[3]
        assertTrue(path4.contains(LINK_1))
        assertNotEquals(k1, k4)
        assertNotEquals(k2, k4)
        timeoutNow()
        // ⑤ 点 P2 得到 30141：只有第二轮的 P2 回写为下架，第一轮同编号的 P1 不动
        openReply = { json("""{"code":30141,"msg":"","data":{},"trace_id":"trace-000127"}""") }
        clickBuyIn(1)
        idleUntil { openRequests.size >= 5 }
        assertTrue(openRequests[4].first.contains(LINK_2))
        assertNotEquals(k2, openRequests[4].second)
        idleUntil {
            agent
                .purchaseController()
                ?.state
                ?.value
                ?.phase != PurchasePhase.LOADING
        }
        settle()
        val after =
            agent.controller.value!!
                .snapshot.value.turns
        assertEquals(
            "off_shelf",
            availabilityOf(
                after[1]
                    .cards
                    .first()
                    .products
                    .first()
                    .json,
            ),
        )
        assertEquals(
            "ok",
            availabilityOf(
                after[0]
                    .cards
                    .first()
                    .products
                    .first()
                    .json,
            ),
        )
    }

    private fun availabilityOf(product: JsonObject): String? = (product["availability"] as? JsonPrimitive)?.content

    private fun timeoutNow() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9))
        settle()
        assertTrue("应显示购买超时提示条", has(TradeTags.TIMEOUT))
    }

    /** 点第 [turn] 轮回复里 c1#c2 商品的购买按钮（两轮的测试标签相同，用轮次标签区分）。 */
    private fun clickBuyIn(turn: Int) {
        val key =
            agent.controller.value!!
                .snapshot.value.turns[turn]
                .key
        val matcher =
            hasClickAction() and hasAnyAncestor(hasTestTag(AgentTags.buy("c1", "c2"))) and
                hasAnyAncestor(hasTestTag(AgentTags.turn(key))) and hasContentDescription("购买", substring = true)
        // 消息列表是懒加载的：先把那一轮滚进来
        composeRule.onNodeWithTag(AgentTags.LIST).performScrollToNode(hasTestTag(AgentTags.turn(key)))
        settle()
        val node = composeRule.onNode(matcher, useUnmergedTree = true)
        runCatching { node.performScrollTo() }
        node.performClick()
        settle()
    }

    @Test
    fun r1_08_tabSwitchKeepsTheReadingPositionAfterRealScroll() {
        launch()
        repeat(4) { i ->
            send("第 ${i + 1} 问：帮我找纯牛奶")
            push(fixtureLines("normal"))
        }
        val first =
            AgentTags.turn(
                agent.controller.value!!
                    .snapshot.value.turns
                    .first()
                    .key,
            )
        assertFalse("跟随中应已到底", has(first))
        repeat(SWIPES) {
            if (!has(first)) {
                composeRule.onNodeWithTag(AgentTags.LIST).performTouchInput { swipeDown() }
                settle()
            }
        }
        assertTrue("应已上滑到第一轮", has(first))
        open("Home")
        assertFalse(has(AgentTags.LIST))
        open("AgentChat")
        assertTrue("切 Tab 回来不得被跟随拉到底", has(first))
    }

    /** F-AND-36：等流程交回结论（原为固定 100 轮、约 2 秒的轮询，高负载下不够），见 [FlowWait]。 */
    private fun idleUntil(condition: () -> Boolean) =
        FlowWait.until(::settle, {
            "栈顶 ${runCatching { topRoute() }.getOrNull()}，打开 ${openRequests.size}，同意 ${consentBodies.size}"
        }, condition = condition)

    private companion object {
        const val ROUNDS = 4
        const val POLL_MS = 20L
        const val SWIPES = 30

        fun ok(data: JsonObject): AgentHttpResult = AgentHttpResult(true, 200, 0, "ok", data, "trace", null)

        fun json(body: String): MockResponse = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

        private val root: File by lazy {
            val start = File(System.getProperty("user.dir") ?: ".").canonicalFile
            generateSequence(start) { it.parentFile }.first { File(it, "contract.lock").isFile }
        }

        fun fixtureLines(name: String): List<String> =
            File(root, "contracts/snapshot/contracts/fixtures/agent-streams/$name.ndjson").readText().split('\n').filter { it.isNotBlank() }

        val META: String by lazy { fixtureLines("normal").first() }

        const val LINK_1 = "019a0000-0000-7000-8000-000000000301"
        const val LINK_2 = "019a0000-0000-7000-8000-000000000391"

        /** 第二轮回复：同一份 normal 流，换 run / message 编号与 c1#c2 的商品（product_key、标题、link_id 都不同，card_id 照旧）。 */
        fun secondTurnLines(): List<String> =
            fixtureLines("normal").map {
                it
                    .replace("019a0000-0000-7000-8000-000000000101", "019a0000-0000-7000-8000-000000000102")
                    .replace("019a0000-0000-7000-8000-000000000201", "019a0000-0000-7000-8000-000000000202")
                    .replace(LINK_1, LINK_2)
                    .replace("jd:demo-0001", "jd:demo-0091")
                    .replace("（样例 1）", "（样例 91）")
            }

        const val DONE = """{"event":"done","id":3,"data":{"finish_reason":"stop","quota_left":5}}"""

        fun card(data: String): String =
            """{"event":"card","id":2,"data":{"seq":2,"card_id":"c1","type":"page_guide","schema_version":1,"data":$data,""" +
                """"fallback_text":"这个我没法直接帮你打开。"}}"""
    }
}
