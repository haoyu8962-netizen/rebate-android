package com.couli.app.me

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.CallSiteIdentityPin
import com.couli.app.CallSiteIdentityPin.ACCESS_A
import com.couli.app.CallSiteServer
import com.couli.app.CallSiteTokens
import com.couli.app.agent.AppAgent
import com.couli.app.privacy.AppPrivacy
import com.couli.app.routing.AppRouting
import com.couli.core.agent.ApiAgentContractPort
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.me.MeLocalStore
import com.couli.core.network.ApiClient
import com.couli.core.network.HeaderNames
import com.couli.core.privacy.PrivacyGate
import com.couli.core.privacy.PrivacyStore
import com.couli.core.privacy.RetryScheduler
import com.couli.core.push.PushTokenSource
import com.couli.core.router.PrivacyState
import com.couli.core.router.SessionState
import com.couli.core.router.UpdateCheck
import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * FU-AND-STEPUP-SUMMARY 调用点级用例（第二位独立写手，补评审 S2-1）：03 §4.2 第一、二条在两处同意记录真实调用点上的落地。
 *
 * - 隐私中心撤回隐私同意（AppMe.withdrawConsent → AppMe.kt:433-442 的 recordConsent，请求体 type=privacy、accepted=false，在声明名单里）：
 *   身份未知（会员界面、本机会话这一刻读不出）照发，不带 Authorization，带 X-Device-Id；有令牌时照常带上（对照）。
 *   同一调用点「accepted=true 的隐私同意不声明」造不出来：请求体只来自 PrivacyWithdrawFlow.request（core/me MePresentation.kt:495-508），
 *   accepted 写死为 false，这个入口没有 accepted=true 的路径，所以不写该条（交回里说明）。
 * - AI 同意（设置页【撤回 AI 服务同意】→ AppAgent.withdrawConsent → AppAgent.kt:690-709 的 recordConsent，type=ai_third_party，不在名单里）：
 *   身份未知时本地拒、零发送；确认游客匿名照发（对照，证明这条路径确实会发出）。
 *
 * 装配取已有集成测试（AppMeIntegrationTest、AgentTransportIntegrationTest、AppAgentIntegrationTest）里 AppMe / AppAgent 的同一套构造，
 * 不起 Compose：两个入口都是确认后的同步入口（页面按钮直接调它们），真实 ApiClient → MockWebServer。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class ConsentCallSiteIdentityPinTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer
    private val service = CallSiteServer { "null" }

    @Before
    fun start() {
        listOf(MeLocalStore.NAMESPACE, MeLocalStore.PENDING_NAMESPACE).forEach {
            context
                .getSharedPreferences(it, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
        server = MockWebServer()
        server.dispatcher = service
        server.start()
    }

    @After
    fun stop() = server.shutdown()

    // ---------------------------------------------------------------- 隐私中心撤回隐私同意（AppMe.kt:433-442）

    @Test
    fun privacyWithdrawalWithAnUnreadableSessionIsStillSentAnonymouslyWithTheDeviceId() {
        val tokens = CallSiteIdentityPin.memberA().also { it.unreadable = true }
        val record = withdrawPrivacy(tokens)

        assertNull("身份未知照发时不带令牌", record.getHeader(HeaderNames.AUTHORIZATION))
        assertEquals("设备签名照旧：带 X-Device-Id", CallSiteIdentityPin.DEVICE_ID, record.getHeader(HeaderNames.DEVICE_ID))
    }

    @Test
    fun privacyWithdrawalWithOnlyARefreshTokenIsStillSentAnonymously() {
        val tokens = CallSiteIdentityPin.memberA().also { it.refreshOnly = true }
        val record = withdrawPrivacy(tokens)

        assertNull(record.getHeader(HeaderNames.AUTHORIZATION))
        assertEquals(CallSiteIdentityPin.DEVICE_ID, record.getHeader(HeaderNames.DEVICE_ID))
    }

    @Test
    fun privacyWithdrawalWithAReadableSessionCarriesTheToken() {
        val record = withdrawPrivacy(CallSiteIdentityPin.memberA())

        assertEquals("有令牌：带上发", "Bearer $ACCESS_A", record.getHeader(HeaderNames.AUTHORIZATION))
    }

    // ---------------------------------------------------------------- AI 同意（AppAgent.kt:690-709）

    @Test
    fun aiConsentWithAnUnreadableSessionIsRejectedLocallyAndSendsNothing() {
        val tokens = CallSiteIdentityPin.memberA().also { it.unreadable = true }
        withdrawAiConsent(tokens)

        assertEquals("AI 同意不声明：身份未知时传输层收到 0 个 consents", 0, service.to(CONSENTS).size)
    }

    @Test
    fun aiConsentWithOnlyARefreshTokenIsRejectedLocallyAndSendsNothing() {
        val tokens = CallSiteIdentityPin.memberA().also { it.refreshOnly = true }
        withdrawAiConsent(tokens)

        assertEquals(0, service.to(CONSENTS).size)
    }

    @Test
    fun aiConsentOfAConfirmedGuestIsSentAnonymously() {
        withdrawAiConsent(CallSiteIdentityPin.guest())

        val record = service.to(CONSENTS).single()
        val body = Json.parseToJsonElement(checkNotNull(service.bodies[record])) as JsonObject
        assertEquals(JsonPrimitive("ai_third_party"), body["type"])
        assertEquals(JsonPrimitive(false), body["accepted"])
        assertNull("确认游客不带令牌", record.getHeader(HeaderNames.AUTHORIZATION))
    }

    // ---------------------------------------------------------------- 装配

    private class World(
        val tokens: CallSiteTokens,
        val client: ApiClient,
        val routing: AppRouting,
        val privacy: AppPrivacy,
    )

    /** 与 AppModule 同一套的会话 / 隐私 / 路由 / ApiClient；界面登录态是会员（PHONE_BOUND）或游客。 */
    private fun world(
        tokens: CallSiteTokens,
        member: Boolean,
    ): World {
        val gate =
            PrivacyGate(MemoryStore(), clock = { NOW }, bundledPolicyVersion = "3").apply {
                retryScheduler = RetryScheduler { _, _ -> }
                // 撤回的本地清理（生产 CredentialCleanerRegistry 的令牌一项）
                withdrawalCleanup = { tokens.inner.clear() }
            }
        val privacy = AppPrivacy(gate, environmentSettled = { true }, clock = { NOW }).also { it.coldStart() }
        val routing =
            AppRouting(
                context,
                EnvironmentGate { EnvironmentStartupReconciler.Outcome.UNCHANGED }.also { it.settleAtStartup() },
                clock = { NOW },
                initialPrivacy = privacy.conclusion,
                initialUpdate = UpdateCheck.OK,
            )
        privacy.onConclusionChanged { routing.privacy.value = it }
        routing.updateSession(if (member) SessionState.PHONE_BOUND else SessionState.GUEST, resumePending = false)
        return World(tokens, CallSiteIdentityPin.client(server, tokens), routing, privacy)
    }

    /** 隐私中心【撤回】确认后的同步入口（与页面按钮同一个），等撤回落定，返回传输层收到的那一条隐私撤回记录。 */
    private fun withdrawPrivacy(tokens: CallSiteTokens): RecordedRequest {
        val w = world(tokens, member = true)
        val me =
            AppMe(
                client = w.client,
                tokens = w.tokens,
                routing = w.routing,
                privacy = w.privacy,
                store = null,
                login = { null },
                updateCheck = { UpdateCheck.OK },
                serverForced = {},
                writer = { false },
                local = MeLocalStore(context),
                mask = null,
                system = NoSystem,
                pushSource = NoPush,
                nickname = null,
                notify = null,
                clock = { NOW },
            )
        CallSiteIdentityPin.settle()
        me.withdrawConsent(me.identity.value)
        // 撤回请求先发出（等到结果或超时），之后才 privacy.withdraw() 进入基本模式：基本模式即请求已落定
        CallSiteIdentityPin.idleUntil { w.privacy.conclusion == PrivacyState.BASIC_MODE }
        CallSiteIdentityPin.settle()
        val records = service.to(CONSENTS)
        assertEquals("撤回隐私同意发出 1 次", 1, records.size)
        val body = Json.parseToJsonElement(checkNotNull(service.bodies[records.single()])) as JsonObject
        assertEquals(JsonPrimitive("privacy"), body["type"])
        assertEquals(JsonPrimitive(false), body["accepted"])
        return records.single()
    }

    /** 设置页【撤回 AI 服务同意】：打开确认框 → 确认（与页面同一组入口），等撤回流程落定（AppAgent 记下结论日志）。 */
    private fun withdrawAiConsent(tokens: CallSiteTokens) {
        val w = world(tokens, member = false)
        val logs: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val main = Handler(Looper.getMainLooper())
        lateinit var agent: AppAgent
        val port = ApiAgentContractPort(w.client, { runnable -> main.post(runnable) }, allowed = { agent.requestsAllowed() })
        agent =
            AppAgent(
                client = w.client,
                tokens = w.tokens,
                routing = w.routing,
                privacy = w.privacy,
                port = { port },
                envKey = { "test" },
                configData = { null },
                mask = MutableStateFlow(false),
                track = {},
                serverForced = {},
                trade = null,
                clock = { NOW },
                log = { logs += it },
            ).also { it.connect() }
        CallSiteIdentityPin.settle()
        val confirm = agent.openWithdrawConfirm { true }
        assertNotNull("撤回入口可用", confirm)
        agent.withdrawConsent(checkNotNull(confirm), { true }) {}
        CallSiteIdentityPin.idleUntil { synchronized(logs) { logs.any { it.startsWith("agent consent withdraw ok=") } } }
        CallSiteIdentityPin.settle()
        assertTrue("撤回流程已落定", !agent.withdrawing.value)
    }

    private class MemoryStore : PrivacyStore {
        override var decisionRaw: String = "consented"
        override var consentedVersion: String? = "3"
        override var consentedAtMillis: Long? = 0L
        override var basicMode: Boolean = false
        override var withdrawalCleanupPending: Boolean = false
    }

    private object NoSystem : MeSystemPorts {
        override fun pushStatus(): String = "not_asked"

        override fun pushDeniedAt(): Long? = null

        override fun openNotificationSettings() = Unit

        override fun openAppSettings() = Unit

        override fun openExternal(url: String): Boolean = false

        override fun wechatInstalled(): String = "unknown"

        override fun cacheBytes(): Long? = null

        override fun clearCache(): Boolean = true

        override fun clearWebCache(): Boolean = true

        override fun deviceId(): String = CallSiteIdentityPin.DEVICE_ID

        override fun osVersion(): String = "TEST_OS"

        override fun model(): String = "TEST_MODEL"
    }

    private object NoPush : PushTokenSource {
        override fun start() = Unit

        override fun stop() = Unit

        override fun current(): String? = null

        override fun subscribe(listener: () -> Unit): () -> Unit = {}
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val CONSENTS = "/v1/consents"
    }
}
