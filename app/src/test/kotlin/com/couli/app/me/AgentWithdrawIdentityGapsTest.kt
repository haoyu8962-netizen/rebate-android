package com.couli.app.me

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.CallSiteIdentityPin
import com.couli.app.CallSiteServer
import com.couli.app.agent.AgentWithdrawSettingsRow
import com.couli.app.agent.AppAgent
import com.couli.core.agent.AgentController
import com.couli.core.agent.AgentDraftTexts
import com.couli.core.agent.ApiAgentContractPort
import com.couli.core.designsystem.CouliTheme
import com.couli.core.router.SessionState
import com.couli.feature.agent.AgentTags
import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 任务书第 3 处；03 §4.2 第二条：「同意记录不发时界面提示稍后重试，不在本地当作已记录」。
 * 真实设置页组件 AgentWithdrawSettingsRow → AppAgent → ApiClient → MockWebServer；采用已有通用失败键 error.50001。
 * 沿用 ConsentCallSiteIdentityPinTest 的游客界面 + 本机旧会话读不出装配，让拒绝实际经过网络层身份未知判定。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentWithdrawIdentityGapsTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var server: MockWebServer
    private lateinit var world: IdentityGapsConsentSupport
    private lateinit var agent: AppAgent
    private val logs: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val service = CallSiteServer { "null" }
    private val failedText get() = AgentController.errorText(50001, "")
    private val doneText get() = AgentDraftTexts.text("agent.draft.withdraw.done")

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher = service
        server.start()
    }

    @After
    fun stop() = server.shutdown()

    // 第 3 处 / 第二条：存着会话但读不出，不发、提示、未记成功、可重试。
    @Test
    fun unreadableIdentityShowsFailureAndCanRetryWithoutRecordingWithdrawal() {
        launch(unreadable = true)
        assertRejectedAndRetryable()
    }

    // 第 3 处 / 第一条「读得出却没有 access_token」也是身份未知；第二条同样提示。
    @Test
    fun refreshOnlyIdentityShowsFailureAndCanRetryWithoutRecordingWithdrawal() {
        launch(refreshOnly = true)
        assertRejectedAndRetryable()
    }

    // 第 3 处对照 / 第一条有令牌照发：成功不出现失败提示，保留原有成功提示。
    @Test
    fun successfulWithdrawalShowsOnlySuccessAndAdvancesConsentGeneration() {
        launch()
        val before = agent.identity().aiConsent
        confirmWithdrawal()
        awaitOutcome(1)
        assertEquals(1, service.to(CONSENTS).size)
        assertEquals(before + 1, agent.identity().aiConsent)
        rule.onNodeWithText(doneText).assertIsDisplayed()
        rule.onNodeWithText(failedText).assertDoesNotExist()
    }

    private fun launch(
        unreadable: Boolean = false,
        refreshOnly: Boolean = false,
    ) {
        val tokens =
            CallSiteIdentityPin.memberA().also {
                it.unreadable = unreadable
                it.refreshOnly = refreshOnly
            }
        world = IdentityGapsConsentSupport(server, tokens)
        world.routing.updateSession(SessionState.GUEST, resumePending = false)
        val main = Handler(Looper.getMainLooper())
        val port = ApiAgentContractPort(world.client, { runnable -> main.post(runnable) }, allowed = { agent.requestsAllowed() })
        rule.runOnUiThread {
            agent =
                AppAgent(
                    client = world.client,
                    tokens = tokens,
                    routing = world.routing,
                    privacy = world.privacy,
                    port = { port },
                    envKey = { "test" },
                    configData = { null },
                    mask = MutableStateFlow(false),
                    track = {},
                    serverForced = {},
                    trade = null,
                    clock = { IdentityGapsConsentSupport.NOW },
                    log = { logs += it },
                ).also { it.connect() }
        }
        rule.setContent { CouliTheme { AgentWithdrawSettingsRow(agent) } }
        rule.waitForIdle()
    }

    private fun assertRejectedAndRetryable() {
        val before = agent.identity().aiConsent
        // 第二次仍身份未知：证明失败结算后入口确实可重试，不只是 enabled 的外观。
        repeat(2) { attempt ->
            if (attempt > 0) {
                // 提示在 2 秒后消失；有界推进测试时钟并确认旧提示已移除，再验证第二次的新提示。
                rule.mainClock.advanceTimeBy(2_100)
                rule.waitForIdle()
                rule.onNodeWithText(failedText).assertDoesNotExist()
            }
            confirmWithdrawal()
            awaitOutcome(attempt + 1)
            assertEquals(0, service.to(CONSENTS).size)
            assertEquals("未当成已经撤回", before, agent.identity().aiConsent)
            assertFalse(agent.withdrawing.value)
            rule.onNodeWithTag(AgentTags.WITHDRAW_ROW).assertIsEnabled()
            rule.onNodeWithText(doneText).assertDoesNotExist()
            rule.onNodeWithText(failedText).assertIsDisplayed()
        }
    }

    private fun confirmWithdrawal() {
        rule.onNodeWithTag(AgentTags.WITHDRAW_ROW).performClick()
        rule.onNodeWithTag(AgentTags.WITHDRAW_CONFIRM).performClick()
    }

    private fun awaitOutcome(expected: Int) {
        CallSiteIdentityPin.idleUntil {
            synchronized(logs) { logs.count { it.startsWith("agent consent withdraw ok=") } == expected }
        }
        rule.waitForIdle()
    }

    private companion object {
        const val CONSENTS = "/v1/consents"
    }
}
