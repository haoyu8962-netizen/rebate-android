@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.couli.feature.login

import androidx.lifecycle.viewModelScope
import com.couli.core.network.ApiClient
import com.couli.core.network.ClientIdentity
import com.couli.core.network.DeviceCredentials
import com.couli.core.network.LocalExchange
import com.couli.core.network.MemoryPendingKeyBackend
import com.couli.core.network.MemorySessionTokens
import com.couli.core.network.NetworkContext
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PrivacyRaw
import com.couli.core.network.SessionTokens
import com.couli.core.network.UpdateRaw
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D10-1978 ②：真实 ApiClient 将保存异常转为 SessionNotSaved(session_save_failed)，再经真实 Service → ViewModel。
 * 这里只钉登录页结果消费；屏障窗口的结果类别由 core/network fix8 覆盖。
 * 对照：session_changed 属于两个 Service 的 STALE_REASONS，ViewModel 仍静默，本文件不改变该规则。
 */
class LoginSessionNotSavedFix9Test {
    @Test
    fun smsSessionSaveFailedShowsGenericErrorStaysOnCodeAndRetrySucceeds() {
        SessionSaveFixture().use { f ->
            f.model.onPhoneChange("13800138000")
            f.model.onGetCode()
            f.model.onConfirmAgree()
            f.settle()
            assertEquals(LoginStep.CODE, f.model.state.value.step)
            val toastId = f.model.state.value.toastId
            val before = f.tokens.snapshot()

            f.model.onCodeChange("123456")
            f.settle()
            f.assertFailed(LoginStep.CODE, toastId)
            assertEquals(before, f.tokens.snapshot())
            assertEquals("123456", f.model.state.value.code)

            f.tokens.failSave = false
            // 满六位同值事件不会再提交；删除一位再补回，重试相同号码、相同验证码。
            f.model.onCodeChange("12345")
            f.model.onCodeChange("123456")
            f.settle()
            f.assertSucceeded("sms")
        }
    }

    @Test
    fun oauthSessionSaveFailedShowsGenericErrorStaysOnPhoneAndRetrySucceeds() {
        SessionSaveFixture().use { f ->
            val toastId = f.model.state.value.toastId
            val before = f.tokens.snapshot()
            f.model.onThirdParty("wechat")
            f.model.onConfirmAgree()
            f.settle()
            f.assertFailed(LoginStep.PHONE, toastId)
            assertEquals(before, f.tokens.snapshot())

            f.tokens.failSave = false
            f.model.onThirdParty("wechat")
            f.settle()
            f.assertSucceeded("wechat")
        }
    }
}

/** 沿用 Login17aFixture 的测试 Main、真实服务装配、key 文案替身和按动作完成结算；LocalExchange 避免监听端口。 */
private class SessionSaveFixture : AutoCloseable {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val tokens = SessionSaveTokens()
    private val host = SessionSaveHost()
    private val completed = mutableListOf<String>()

    init {
        Dispatchers.setMain(dispatcher)
    }

    private val api =
        ApiClient(
            identity = ClientIdentity("couli", "android", "1.0.0", "1", "official"),
            baseUrl = { "https://login-fix9.invalid/" },
            context = { NetworkContext(PrivacyRaw.FULL, true, true, UpdateRaw.SUPPORTED) },
            device =
                object : DeviceCredentials {
                    override val deviceId = "device-fix9"
                    override val installSecret = "test-only-fix9"
                },
            tokens = tokens,
            pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { NOW },
            sleeper = {},
            local = SessionSaveExchange,
        )
    private val service = LoginService(api, tokens, host, sessionScope = scope, pendingInvite = PendingInviteCode(), clock = { NOW })
    private val oauth = OAuthLoginService(api, tokens, service, listOf(SessionSaveAdapter), PhoneBindingState(), followUpScope = scope)
    val model =
        LoginViewModel(
            LoginPageConfig(
                service,
                SessionSaveTexts,
                restricted = false,
                oauth = oauth,
                thirdParty = listOf("wechat"),
                clock = { NOW },
                actions =
                    LoginPageActions(
                        onClose = { host.events += "pageClose" },
                        onForceUpdate = { host.events += "pageForceUpdate" },
                        onLoggedIn = { completed += "sms:${it.stateRaw}" },
                        onOAuthLoggedIn = { completed += "wechat:${it.stateRaw}" },
                    ),
            ),
        )
    private val ticker: Set<Job>

    init {
        scheduler.runCurrent()
        ticker =
            model.viewModelScope.coroutineContext[Job]!!
                .children
                .toSet()
    }

    fun settle() =
        runBlocking {
            withTimeout(5_000) {
                do {
                    scheduler.runCurrent()
                    val state = model.state.value
                    val actionsDone =
                        model.viewModelScope.coroutineContext[Job]!!
                            .children
                            .none { it !in ticker && !it.isCompleted }
                    val idle = !state.sending && !state.loggingIn && state.oauthBusy == null
                    if (idle && actionsDone) return@withTimeout
                    delay(1)
                } while (true)
            }
        }

    fun assertFailed(
        step: LoginStep,
        previousToastId: Int,
    ) {
        val state = model.state.value
        assertEquals(step, state.step)
        assertEquals("error.50001", LoginService.GENERIC_KEY)
        // 文案替身返回 key；trace_id 为空，提示无需附加追踪号。
        assertEquals(LoginService.GENERIC_KEY, state.toast)
        assertEquals(previousToastId + 1, state.toastId)
        assertFalse(state.loggingIn)
        assertFalse(service.busy)
        assertFalse(oauth.busy)
        assertEquals(1, tokens.saveAttempts)
        assertEquals(0, tokens.clearAttempts)
        assertTrue("失败不得完成登录、关闭页面、导航或结束会话", host.events.isEmpty())
        assertTrue(completed.isEmpty())
    }

    fun assertSucceeded(method: String) {
        assertEquals(2, tokens.saveAttempts)
        assertEquals(0, tokens.clearAttempts)
        assertEquals("access-fix9", tokens.accessToken)
        assertEquals("refresh-fix9", tokens.refreshToken)
        assertEquals("user-fix9", tokens.userId)
        assertEquals(listOf("$method:${LoginOutcome.LOGGED_IN}"), completed)
        // 本夹具没有 pending_action；两种登录都完成、关页并通知会话变化，不进入暂缓待办分支。
        assertEquals(listOf("completed", "close", "changed"), host.events)
    }

    override fun close() {
        model.viewModelScope.cancel()
        scope.cancel()
        scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}

private class SessionSaveTokens(
    private val memory: MemorySessionTokens = MemorySessionTokens(),
) : SessionTokens by memory {
    var failSave = true
    var saveAttempts = 0
    var clearAttempts = 0

    override fun saveIf(
        expectedGeneration: Long,
        accessToken: String,
        refreshToken: String,
        scope: String,
        userId: String?,
    ): Boolean {
        saveAttempts += 1
        check(!failSave) { "测试：会话保存失败" }
        return memory.saveIf(expectedGeneration, accessToken, refreshToken, scope, userId)
    }

    override fun clear() {
        clearAttempts += 1
        memory.clear()
    }

    override fun clearIf(expectedGeneration: Long): Boolean {
        clearAttempts += 1
        return memory.clearIf(expectedGeneration)
    }
}

private class SessionSaveHost : LoginHost {
    val events = mutableListOf<String>()

    override fun updateState() = AfterLoginUpdate.SUPPORTED

    override fun hasPendingAction() = false

    override fun closeLogin() {
        events += "close"
    }

    override fun resumePending() {
        events += "resume"
    }

    override fun discardPending() {
        events += "discard"
    }

    override fun enterForceUpdate() {
        events += "forceUpdate"
    }

    override fun sessionChanged() {
        events += "changed"
    }

    override fun sessionSynced() {
        events += "synced"
    }

    override fun sessionEnded() {
        events += "ended"
    }

    override fun prerequisiteDoneHeld() {
        events += "held"
    }

    override fun loginCompleted(loginEpoch: Long) {
        events += "completed"
    }
}

private object SessionSaveTexts : LoginTexts {
    override fun text(key: String) = key

    override fun format(
        key: String,
        vars: Map<String, String>,
    ) = key
}

private object SessionSaveAdapter : ThirdPartyAuthAdapter {
    override val provider = "wechat"

    override fun adapterAvailable() = true

    override fun installed() = true

    override suspend fun authorize(nonce: String) = ThirdPartyAuthResult(ThirdPartyAuthKind.CREDENTIAL, code = "code-fix9", state = nonce)
}

private object SessionSaveExchange : LocalExchange {
    override fun handles(request: Request) = true

    override fun answer(
        request: Request,
        canceled: () -> Boolean,
    ): Response {
        val data =
            when (request.url.encodedPath) {
                "/v1/auth/sms-codes" -> {
                    """{"resend_after_sec":60,"expires_in_sec":300}"""
                }

                "/v1/auth/oauth-attempts" -> {
                    """{"attempt_id":"attempt-fix9","nonce":"nonce-fix9","expire_at":"2099-01-01T00:00:00Z"}"""
                }

                "/v1/auth/login/sms", "/v1/auth/login/wechat" -> {
                    """{"user_id":"user-fix9","is_new_user":true,"tokens":{"access_token":"access-fix9",""" +
                        """"refresh_token":"refresh-fix9","session_scope":"full"}}"""
                }

                else -> {
                    error("非预期请求：${request.url.encodedPath}")
                }
            }
        return Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("""{"code":0,"msg":"","data":$data}""".toResponseBody(null))
            .build()
    }
}
