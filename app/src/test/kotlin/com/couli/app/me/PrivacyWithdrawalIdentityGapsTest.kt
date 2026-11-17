package com.couli.app.me

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.CallSiteIdentityPin
import com.couli.app.CallSiteServer
import com.couli.core.agent.AgentCall
import com.couli.core.agent.AgentCancelLedger
import com.couli.core.agent.AgentContractPort
import com.couli.core.agent.AgentHttpResult
import com.couli.core.agent.AgentOwnedRun
import com.couli.core.agent.AgentOwnershipDecision
import com.couli.core.agent.AgentOwnershipHandlers
import com.couli.core.agent.AgentOwnershipStop
import com.couli.core.agent.ApiAgentContractPort
import com.couli.core.network.HeaderNames
import com.couli.core.router.PrivacyState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 任务书第 1 处；03 §4.2 第五条：「撤回不固定账号、只固定会话世代」「中途换了人或已退出……撤回同样不发」。
 * 用真实 AgentOwnershipStop 的写出信号暂停在确认之后；不靠睡眠碰竞态，也不在 api.send 时才换号。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class PrivacyWithdrawalIdentityGapsTest {
    private lateinit var server: MockWebServer
    private lateinit var world: IdentityGapsConsentSupport
    private lateinit var me: AppMe
    private val stopScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val releaseCancel = CompletableDeferred<Unit>()
    private var cancelEntered = false
    private val observedCredentials = mutableListOf<List<String?>>()
    private val service =
        CallSiteServer { request ->
            if (request.path == CONSENTS) {
                world.events += "send_privacy"
                observedCredentials += listOf(world.tokens.inner.accessToken, world.device.deviceId, world.device.installSecret)
            }
            "null"
        }

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher = service
        server.start()
        world = IdentityGapsConsentSupport(server)
        me = world.me()
        val delegate = ApiAgentContractPort(world.client, { it.run() })
        val port =
            object : AgentContractPort by delegate {
                override suspend fun cancelRun(
                    runId: String,
                    call: AgentCall,
                ): AgentHttpResult {
                    cancelEntered = true
                    releaseCancel.await()
                    // 确定不写 cancel 也会结束等待；本用例只统计真实隐私撤回的 HTTP 写出。
                    call.onWritten?.invoke(false)
                    return AgentHttpResult.notSentResult()
                }
            }
        val stop = AgentOwnershipStop(AgentCancelLedger(), AgentOwnershipHandlers(), stopScope)
        me.beginAgentIdentityChange = {
            val call = AgentCall("confirmed-owner", anonymous = false, sendAllowed = { true }, owner = { true })
            stop.dispatch(AgentOwnershipDecision(AgentOwnedRun("run-before-withdraw", call, "confirmed-owner"), true), port)
        }
        CallSiteIdentityPin.settle()
    }

    @After
    fun stop() {
        releaseCancel.complete(Unit)
        stopScope.cancel()
        server.shutdown()
    }

    // 第 1 处 / 第五条「中途换了人……撤回同样不发」。
    @Test
    fun accountSwitchWhileAgentStopsSendsNoPrivacyWithdrawal() {
        confirmAndPause()
        world.tokens.inner.save("access-B", "refresh-B", "full", "account-B")
        finish()
        assertEquals("确认后换号：撤回请求零发送", 0, service.to(CONSENTS).size)
    }

    // 第 1 处 / 第五条「或已退出……撤回同样不发」。
    @Test
    fun logoutWhileAgentStopsSendsNoPrivacyWithdrawal() {
        confirmAndPause()
        world.tokens.inner.clear()
        finish()
        assertEquals("确认后退出：撤回请求零发送", 0, service.to(CONSENTS).size)
    }

    // 第 1 处 / 第五条只固定会话世代；第一条「有令牌——带上发」；任务书保持先发后清理。
    @Test
    fun unchangedEpochSendsWithAuthorizationBeforeEveryCredentialIsCleared() {
        confirmAndPause()
        finish()
        assertSentBeforeCleanup("Bearer ${CallSiteIdentityPin.ACCESS_A}")
    }

    // 第 1 处 / 第二、三条隐私撤回是设备主体，身份未知照发；第五条不固定账号。
    @Test
    fun unchangedEpochBecomingUnreadableStillSendsWithDeviceIdBeforeCleanup() {
        confirmAndPause()
        val epoch = world.tokens.loginEpoch
        world.tokens.unreadable = true
        assertEquals(epoch, world.tokens.loginEpoch)
        finish()
        assertSentBeforeCleanup(null)
    }

    private fun confirmAndPause() {
        me.withdrawConsent(me.identity.value)
        assertTrue("已到 Agent 停流等待窗口", cancelEntered)
        assertEquals(0, service.to(CONSENTS).size)
        assertTrue("等待期间不先清凭据", world.events.isEmpty())
        assertEquals(PrivacyState.CONSENTED, world.privacy.conclusion)
    }

    private fun finish() {
        releaseCancel.complete(Unit)
        CallSiteIdentityPin.idleUntil { world.privacy.conclusion == PrivacyState.BASIC_MODE }
        CallSiteIdentityPin.settle()
        assertNull(world.tokens.inner.accessToken)
        assertNull(world.device.deviceId)
        assertNull(world.device.installSecret)
    }

    private fun assertSentBeforeCleanup(authorization: String?) {
        val record = service.to(CONSENTS).single()
        assertEquals(authorization, record.getHeader(HeaderNames.AUTHORIZATION))
        assertEquals(CallSiteIdentityPin.DEVICE_ID, record.getHeader(HeaderNames.DEVICE_ID))
        val body = Json.parseToJsonElement(checkNotNull(service.bodies[record])) as JsonObject
        assertEquals(JsonPrimitive("privacy"), body["type"])
        assertEquals(JsonPrimitive(false), body["accepted"])
        assertEquals(
            listOf(listOf(CallSiteIdentityPin.ACCESS_A, CallSiteIdentityPin.DEVICE_ID, "install-fixture")),
            observedCredentials,
        )
        assertEquals(listOf("send_privacy", "clear_tokens", "clear_device_id", "clear_install_secret"), world.events.toList())
    }

    private companion object {
        const val CONSENTS = "/v1/consents"
    }
}
