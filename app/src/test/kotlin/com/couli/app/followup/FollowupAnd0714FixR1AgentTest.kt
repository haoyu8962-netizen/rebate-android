package com.couli.app.followup

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.agent.AppAgent
import com.couli.app.privacy.AppPrivacy
import com.couli.app.routing.AppRouting
import com.couli.app.routing.RoutingPrivacyHooks
import com.couli.core.agent.AgentCall
import com.couli.core.agent.AgentContractPort
import com.couli.core.agent.AgentHttpResult
import com.couli.core.agent.AgentPhase
import com.couli.core.agent.AgentStreamHandle
import com.couli.core.agent.AgentStreamSink
import com.couli.core.agent.AgentTimer
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
import com.couli.core.network.SessionSnapshot
import com.couli.core.network.SessionTokens
import com.couli.core.network.UpdateRaw
import com.couli.core.privacy.PrivacyGate
import com.couli.core.privacy.PrivacyStore
import com.couli.core.privacy.RetryScheduler
import com.couli.core.router.SessionState
import com.couli.core.router.UpdateCheck
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * FU-AND-0714 第 1 轮评审 R1-04 的 App 接线：AppAgent.identity() 在已登录、账号短暂读不出时给出 level=member、account=null 的身份；按它建的控制器
 * 是可恢复的初始化失败（取会话、建会话、消息流、记录同意零调用，canSend=false）；同一登录世代里账号恢复后点【重试】，AppAgent 同步作废旧控制器并按
 * 新身份重建，照常取会话。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class FollowupAnd0714FixR1AgentTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** 账号是否读得出可控的令牌（同一登录世代里切换）。 */
    private class FlakyAccount(
        private val inner: MemorySessionTokens,
    ) : SessionTokens by inner {
        @Volatile var hidden = true

        override val userId: String? get() = if (hidden) null else inner.userId

        override fun snapshot(): SessionSnapshot = inner.snapshot().let { if (hidden) it.copy(userId = null) else it }
    }

    private class Port : AgentContractPort {
        var currents = 0
        var creates = 0
        var streams = 0

        override suspend fun createSession(call: AgentCall): AgentHttpResult {
            if (!call.sendAllowed()) return AgentHttpResult.notSentResult()
            creates += 1
            return ok(JsonObject(mapOf("session" to JsonObject(mapOf("id" to JsonPrimitive("s-1"))))))
        }

        override suspend fun currentSession(call: AgentCall): AgentHttpResult {
            currents += 1
            return ok(JsonObject(mapOf("session" to JsonNull)))
        }

        override fun sendMessage(
            sessionId: String,
            body: String,
            call: AgentCall,
            sink: AgentStreamSink,
        ): AgentStreamHandle {
            streams += 1
            return object : AgentStreamHandle {
                override fun cancel() = Unit
            }
        }

        override suspend fun cancelRun(
            runId: String,
            call: AgentCall,
        ): AgentHttpResult = ok(JsonObject(emptyMap()))

        override fun cancelOwned(identityKey: Any) = Unit

        private fun ok(data: JsonObject) = AgentHttpResult(true, 200, 0, "ok", data, "trace-000123", null)
    }

    private class MemoryStore : PrivacyStore {
        override var decisionRaw: String = "consented"
        override var consentedVersion: String? = "3"
        override var consentedAtMillis: Long? = 0L
        override var basicMode: Boolean = false
        override var withdrawalCleanupPending: Boolean = false
    }

    private object Device : DeviceCredentials {
        override val deviceId: String = "device-fixture"
        override val installSecret: String = "install-fixture"
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    @Suppress("LongMethod") // 与 AppModule 同一套接线逐项组装，之后按失败 → 重试 → 重建依次断言
    fun r1_04_app_agent_member_with_unreadable_account_builds_recoverable_failure_and_retry_rebuilds_identity() {
        val gate =
            PrivacyGate(MemoryStore(), clock = { 0L }, bundledPolicyVersion = "3").apply {
                retryScheduler = RetryScheduler { _, _ -> }
            }
        val privacy = AppPrivacy(gate, environmentSettled = { true }, clock = { 0L }).also { it.coldStart() }
        val environment = EnvironmentGate { EnvironmentStartupReconciler.Outcome.UNCHANGED }.also { it.settleAtStartup() }
        val routing =
            AppRouting(
                context,
                environment,
                clock = { 0L },
                initialPrivacy = privacy.conclusion,
                privacyHooks = RoutingPrivacyHooks(promptShowing = { false }, credentialsReadable = { true }),
                initialUpdate = UpdateCheck.OK,
            )
        val tokens = FlakyAccount(MemorySessionTokens("access-a", "refresh-a", "full", "user-a"))
        val api =
            ApiClient(
                identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.0.0", build = "1", channel = "official"),
                baseUrl = { "https://api.example.test/" },
                context = { NetworkContext(PrivacyRaw.FULL, true, true, UpdateRaw.SUPPORTED) },
                device = Device,
                tokens = tokens,
                pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
                sleeper = {},
            )
        val port = Port()
        routing.updateSession(SessionState.PHONE_BOUND, resumePending = false)
        val agent =
            AppAgent(
                client = api,
                tokens = tokens,
                routing = routing,
                privacy = privacy,
                port = { port },
                envKey = { "test" },
                configData = { null },
                mask = MutableStateFlow(false),
                track = {},
                serverForced = {},
                trade = null,
                timer = AgentTimer { _, _ -> {} },
            ).also { it.connect() }
        agent.onTab(AppAgent.AGENT_ROUTE)

        assertNull("已登录、账号读不出", api.activeUserId())
        val failed = agent.ensure()
        idle()
        assertEquals("member", failed.boundIdentity.level)
        assertNull(failed.boundIdentity.account)
        assertEquals("可恢复的初始化失败", AgentPhase.LOAD_FAILED, failed.currentPhase)
        assertFalse(failed.canSend)
        assertFalse(failed.send(failed.snapshot.value.page, "帮我找纯牛奶"))
        failed.newChat(failed.snapshot.value.page)
        idle()
        assertEquals("取会话、建会话、消息流零调用", 0, port.currents + port.creates + port.streams)

        // 账号仍读不出：【重试】清空并按此刻的身份重建（仍是失败），依旧零调用
        failed.retryLoad(failed.snapshot.value.page)
        idle()
        val again = agent.controller.value!!
        assertNotSame("清空并重建", failed, again)
        assertEquals(AgentPhase.LOAD_FAILED, again.currentPhase)
        assertEquals(0, port.currents + port.creates + port.streams)

        // 同一登录世代里账号读得出了：【重试】按新身份重建，照常取会话
        tokens.hidden = false
        again.retryLoad(again.snapshot.value.page)
        idle()
        val rebuilt = agent.controller.value!!
        assertNotSame(again, rebuilt)
        assertEquals("user-a", rebuilt.boundIdentity.account)
        assertEquals(1, port.currents)
        assertEquals(AgentPhase.READY, rebuilt.currentPhase)
        // 旧控制器的【重试】不再起作用
        again.retryLoad(again.snapshot.value.page)
        idle()
        assertSame(rebuilt, agent.controller.value)
    }
}
