package com.couli.feature.login

import com.couli.core.network.ApiClient
import com.couli.core.network.ClientIdentity
import com.couli.core.network.DeviceCredentials
import com.couli.core.network.MemoryPendingKeyBackend
import com.couli.core.network.MemorySessionTokens
import com.couli.core.network.NetworkContext
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PrivacyRaw
import com.couli.core.network.RetryPolicy
import com.couli.core.network.SessionScopeRaw
import com.couli.core.network.UpdateRaw
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import org.junit.Test

/**
 * APP-05a 本地假服务集成测试（任务书 §2.5）：真实 [ApiClient] + [LoginService] → MockWebServer（本机，不连任何真实地址）。
 * 覆盖发码与倒计时、42901 Retry-After、验证码错误 / 过期、登录后恢复 pending_action（先保存会话再交 Router）、deletion_only 且强更
 * 未知时进强更页、注销中账号、登出回游客，以及 APP-05a 共性第 1、3、4、5、10、11、12 条。
 */
class LoginServiceIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ApiClient
    private lateinit var service: LoginService
    private val tokens = MemorySessionTokens()
    private val host = RecordingHost()
    private var now = 1_700_000_000_000L
    private var update = UpdateRaw.SUPPORTED
    private val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())

    @Volatile
    private var answer: (RecordedRequest) -> MockResponse = { envelope(0, "{}") }

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    return answer(request)
                }
            }
        server.start()
        api =
            ApiClient(
                identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.0.0", build = "1", channel = "official"),
                baseUrl = { server.url("/").toString() },
                context = { NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, update = update) },
                device = FixtureDevice,
                tokens = tokens,
                pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
                sleeper = {},
                // F-AND-27（FU-AND-0714）：本机 MockWebServer 的回答不受网络影响，用例验证的也不是超时；生产的 10 秒单次预算是墙钟时间，
                // 负载 >130 时 JVM 调度把一次本机往返拖过 10 秒就成了 network_error。这里把预算放到 [LOCAL_BUDGET_MS]，等到回答为止；
                // 要验证「不等」的用例（取消、作废）本来就不靠超时结束
                retryPolicy = { RetryPolicy.forOperation(it).copy(timeoutMillis = LOCAL_BUDGET_MS) },
            )
        service = LoginService(api, tokens, host, LegalVersions(3, 2), pendingInvite = PendingInviteCode()) { now }
    }

    @After
    fun stop() = server.shutdown()

    // ---- 发码

    @Test
    fun sendCodeStartsTheSixtySecondCountdownAndTheSameNumberIsNotResent() =
        runBlocking {
            answer = { envelope(0, """{"resend_after_sec":60,"expires_in_sec":300}""") }
            val sent = service.sendCode("+86 138-0013-8000")
            assertEquals(SmsCountdown.STATE_SENT, sent.stateRaw)
            assertEquals(60, sent.countdownSec)
            assertEquals("规范化号码上送", "13800138000", body(requests.single())["phone"])

            now += 20_000
            val again = service.sendCode("13800138000")
            assertEquals("共性第 5 条：同号码倒计时内不再发出", LoginService.STATE_COUNTDOWN, again.stateRaw)
            assertEquals(40, again.countdownSec)
            assertEquals(1, requests.size)
            val entry = service.continueEntry("0086 13800138000")
            assertEquals("共性第 12 条：回到手机号页仍可继续输入已收到的码", PhoneEntry.CONTINUE, entry.kind)
        }

    @Test
    fun rateLimitedSendHonoursRetryAfterAndStillAllowsLoginWithTheReceivedCode() =
        runBlocking {
            answer = { envelope(0, """{"resend_after_sec":60,"expires_in_sec":300}""") }
            service.sendCode("13800138000")
            now += 61_000
            answer = { envelope(42901, "null").setHeader("Retry-After", "120") }
            val limited = service.sendCode("13800138000")
            assertEquals(SmsCountdown.STATE_RATE_LIMITED, limited.stateRaw)
            assertEquals(120, limited.countdownSec)
            assertEquals("error.42901", limited.textKey)
            val limits = LoginRateLimits().apply { limitSend(now, limited.countdownSec * 1000L) }
            assertTrue(limits.sendBlocked(now))
            assertFalse("共性第 11 条：发码限流不禁用登录", limits.loginBlocked(now))
            val entry = service.continueEntry("13800138000")
            assertEquals("共性第 12 条：限流期间仍可进入验证码步", PhoneEntry.CONTINUE, entry.kind)
            assertEquals("共性第 11 条：限流截止时间按号码维护，同号码不再发出", SmsCountdown.STATE_RATE_LIMITED, service.sendCode("13800138000").stateRaw)
            assertEquals(2, requests.size)

            answer = { loggedIn(SessionScopeRaw.FULL) }
            val outcome = service.login("13800138000", "123456", now, "", restricted = false)
            assertEquals(LoginOutcome.LOGGED_IN, outcome.stateRaw)
        }

    @Test
    fun invalidPhoneIsRejectedLocallyWithTheServerText() =
        runBlocking {
            val local = service.sendCode("12345678901")
            assertEquals("field_error", local.stateRaw)
            assertEquals("error.20001.phone_invalid", local.textKey)
            assertTrue("不发出", requests.isEmpty())
        }

    // ---- 登录错误

    @Test
    fun wrongAndExpiredCodesMapToTheirPageStates() =
        runBlocking {
            answer = { envelope(20002, "null") }
            val wrong = service.login("13800138000", "111111", now, "", restricted = false)
            assertEquals("code_error", wrong.stateRaw)
            assertEquals("error.20002", wrong.textKey)
            answer = { envelope(20003, "null") }
            val expired = service.login("13800138000", "111111", now, "", restricted = false)
            assertEquals(LoginErrorMapper.STATE_CODE_EXPIRED, expired.stateRaw)
            assertNull("没有保存会话", tokens.accessToken)
            assertTrue("没有通知 Router", host.events.isEmpty())
        }

    @Test
    fun anAccountBeingDeletedEndsInLoginDeletionProcessing() =
        runBlocking {
            answer = { envelope(10007, """{"stage":"processing"}""") }
            val outcome = service.login("13800138000", "123456", now, "", restricted = false)
            assertEquals(LoginErrorMapper.STATE_DELETION_PROCESSING, outcome.stateRaw)
            assertEquals("error.10007", outcome.textKey)
            assertNull(tokens.accessToken)
        }

    @Test
    fun loginWithoutConsentIsNotSent() =
        runBlocking {
            assertEquals(LoginOutcome.AGREEMENT_REQUIRED, service.login("13800138000", "123456", null, "", false).stateRaw)
            assertTrue(requests.isEmpty())
        }

    // ---- 登录成功

    @Test
    fun loginSavesTheSessionBeforeHandingPendingActionToTheRouter() =
        runBlocking {
            host.pending = true
            host.onEvent = { event -> if (event == "resume_pending") host.sessionAtResume = tokens.accessToken }
            answer = { loggedIn(SessionScopeRaw.FULL, invite = ""","invite_bind":{"result":"bound","code":null}""") }
            service.pendingInvite.set(" k7m3qx ")
            val outcome = service.login("13800138000", "123456", CONSENT_AT, "IGNORED", restricted = false)

            assertEquals(LoginOutcome.LOGGED_IN, outcome.stateRaw)
            assertEquals(listOf(AfterLoginAction.SAVE_SESSION, AfterLoginAction.RESUME_PENDING), outcome.actions)
            assertEquals("共性第 2 条：先关登录页，再通知会话，再交 Router", listOf("close_login", "session_changed", "resume_pending"), host.events)
            assertEquals("交 Router 时会话已保存", "access-one", host.sessionAtResume)
            assertEquals("bound", outcome.inviteResult)
            val sent = body(requests.single())
            assertEquals("""{"privacy":3,"agreement":2}""", sent["legal_versions"])
            assertEquals("2023-11-14T22:13:20Z", sent["consent_at"])
            assertEquals("待绑定码规范化后上送", "K7M3QX", sent["invite_code"])
            assertNull(service.pendingInvite.peek())
        }

    @Test
    fun restrictedLoginDoesNotSendTheInviteCode() =
        runBlocking {
            answer = { loggedIn(SessionScopeRaw.DELETION_ONLY) }
            update = UpdateRaw.BELOW
            host.update = AfterLoginUpdate.BELOW
            service.pendingInvite.set("K7M3QX")
            service.login("13800138000", "123456", now, "K7M3QX", restricted = true)
            assertEquals("K7M3QX", service.pendingInvite.peek())
            assertFalse(body(requests.single()).containsKey("invite_code"))
        }

    @Test
    fun deletionOnlyWhileUpdateIsUnknownDiscardsPendingAndEntersForceUpdate() =
        runBlocking {
            host.pending = true
            host.update = AfterLoginUpdate.UNKNOWN
            update = UpdateRaw.UNKNOWN
            answer = { loggedIn(SessionScopeRaw.DELETION_ONLY) }
            val outcome = service.login("13800138000", "123456", now, "", restricted = true)
            assertEquals(
                listOf(AfterLoginAction.SAVE_SESSION, AfterLoginAction.DISCARD_PENDING, AfterLoginAction.ENTER_FORCE_UPDATE),
                outcome.actions,
            )
            assertEquals(listOf("discard_pending", "close_login", "session_changed", "enter_force_update"), host.events)
            assertEquals(SessionScopeRaw.DELETION_ONLY, tokens.scope)
        }

    /** 共性第 4 条：deletion_only + supported → 先复查受限会话；复查后变成 full 才交 Router 恢复待办。 */
    @Test
    fun deletionOnlyWithSupportedUpdateRechecksAndResumesWhenTheSessionBecomesFull() =
        runBlocking {
            host.pending = true
            answer =
                { request -> if (request.path == REFRESH) refreshed(SessionScopeRaw.FULL) else loggedIn(SessionScopeRaw.DELETION_ONLY) }
            val outcome = service.login("13800138000", "123456", now, "", restricted = false)
            assertEquals(
                listOf(AfterLoginAction.SAVE_SESSION, AfterLoginAction.REFRESH_RESTRICTED, AfterLoginAction.RESUME_PENDING),
                outcome.actions,
            )
            assertEquals(SessionScopeRaw.FULL, tokens.scope)
            assertFalse("没有删待办", "discard_pending" in host.events)
            assertTrue("resume_pending" in host.events)
        }

    @Test
    fun deletionOnlyStillRestrictedAfterTheRecheckHoldsThePendingAction() =
        runBlocking {
            host.pending = true
            answer = { request ->
                if (request.path == REFRESH) refreshed(SessionScopeRaw.DELETION_ONLY) else loggedIn(SessionScopeRaw.DELETION_ONLY)
            }
            val outcome = service.login("13800138000", "123456", now, "", restricted = false)
            assertEquals(
                listOf(AfterLoginAction.SAVE_SESSION, AfterLoginAction.REFRESH_RESTRICTED, LoginService.HOLD_PENDING),
                outcome.actions,
            )
            assertFalse("discard_pending" in host.events)
            assertFalse("resume_pending" in host.events)
            assertTrue("关闭登录页", "close_login" in host.events)
        }

    /** 共性第 10 条：复查带本次尝试的归属；取消时结束等待，已作废的分支只同步会话、不恢复待办。 */
    @Test
    fun cancellingDuringTheRestrictedRecheckEndsTheWaitWithoutResumingPending() =
        runBlocking {
            host.pending = true
            val refreshing = CountDownLatch(1)
            answer = { request ->
                if (request.path == REFRESH) {
                    refreshing.countDown()
                    MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE)
                } else {
                    loggedIn(SessionScopeRaw.DELETION_ONLY)
                }
            }
            val started = System.nanoTime()
            val login = async(Dispatchers.Default) { service.login("13800138000", "123456", now, "", restricted = false) }
            assertTrue(withContext(Dispatchers.IO) { refreshing.await(WAIT_S, TimeUnit.SECONDS) })
            service.invalidateAttempt()
            val outcome = login.await()
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertEquals(LoginOutcome.STALE, outcome.stateRaw)
            assertTrue("等待随取消结束（$elapsed 毫秒）", elapsed < CANCEL_WITHIN_MS)
            assertEquals("只同步会话状态", listOf("session_synced"), host.events)
        }

    /** 共性第 1 条：换号、取消、页面出栈时作废尝试并取消请求；迟到的登录响应不保存会话、不通知 Router。 */
    @Test
    fun anInvalidatedAttemptIsCancelledAndItsLateResponseSavesNothing() =
        runBlocking {
            val arrived = CountDownLatch(1)
            val release = CountDownLatch(1)
            answer = {
                arrived.countDown()
                release.await(WAIT_S, TimeUnit.SECONDS)
                loggedIn(SessionScopeRaw.FULL)
            }
            val login = async(Dispatchers.Default) { service.login("13800138000", "123456", now, "", restricted = false) }
            assertTrue(withContext(Dispatchers.IO) { arrived.await(WAIT_S, TimeUnit.SECONDS) })
            service.invalidateAttempt()
            release.countDown()
            val outcome = login.await()
            assertEquals(LoginOutcome.STALE, outcome.stateRaw)
            assertNull("旧响应不保存会话", tokens.accessToken)
            assertTrue("不通知 Router、不恢复待办", host.events.isEmpty())
        }

    // ---- 登出

    @Test
    fun logoutRevokesTheSessionAndReturnsToGuest() =
        runBlocking {
            tokens.save("access-one", "refresh-one", SessionScopeRaw.FULL, "user-one")
            answer = { envelope(0, "{}") }
            assertTrue(service.logout())
            assertEquals("/v1/auth/logout", requests.single().path)
            assertNull(tokens.accessToken)
            assertEquals(listOf("session_ended", "session_changed"), host.events)
        }

    /** 共性第 3 条：logout 时会话已失效（10404 清掉了会话）仍要完成 Router.sessionEnded 这一套清理。 */
    @Test
    fun logoutWhoseSessionWasAlreadyClearedStillEndsTheRouterSession() =
        runBlocking {
            tokens.save("access-one", "refresh-one", SessionScopeRaw.FULL, "user-one")
            answer = { envelope(10404, "null") }
            assertTrue(service.logout())
            assertNull(tokens.accessToken)
            assertTrue("sessionEnded 照常执行", "session_ended" in host.events)
        }

    /**
     * 共性第 13 条（Android 第 1 轮评审 R1-01）：确认退出后 logout 请求还慢着，设置页就被关掉或重建（调用方的协程作用域取消）——登出在会话
     * 作用域里照常完成：本机会话按原世代清除，Router.sessionEnded 照样执行（待办与旧回查失效）。
     */
    @Test
    fun leavingTheSettingsPageWhileLogoutIsSlowStillFinishesTheLocalCleanup() =
        runBlocking {
            tokens.save("access-one", "refresh-one", SessionScopeRaw.FULL, "user-one")
            val arrived = CountDownLatch(1)
            val release = CountDownLatch(1)
            answer = {
                arrived.countDown()
                release.await(WAIT_S, TimeUnit.SECONDS)
                envelope(0, "{}")
            }
            val settingsPage = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            settingsPage.launch { service.logout() }
            assertTrue(withContext(Dispatchers.IO) { arrived.await(WAIT_S, TimeUnit.SECONDS) })
            // 用户返回离开设置页（或配置重建）：页面的作用域取消
            settingsPage.cancel()
            release.countDown()

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S)
            while ("session_changed" !in host.events && System.nanoTime() < deadline) delay(POLL_MS)
            assertNull("本机会话已清除", tokens.accessToken)
            assertEquals("Router.sessionEnded 照样执行", listOf("session_ended", "session_changed"), host.events.toList())
            assertFalse(service.loggingOutNow)
        }

    @Test
    fun logoutThatFindsANewSessionDoesNotClearIt() =
        runBlocking {
            tokens.save("access-one", "refresh-one", SessionScopeRaw.FULL, "user-one")
            answer = {
                tokens.save("access-two", "refresh-two", SessionScopeRaw.FULL, "user-two")
                envelope(0, "{}")
            }
            assertFalse(service.logout())
            assertEquals("新会话保留", "access-two", tokens.accessToken)
            assertEquals(listOf("session_changed"), host.events)
        }

    private fun body(request: RecordedRequest): Map<String, String> =
        Json.parseToJsonElement(request.body.readUtf8()).jsonObject.mapValues { (_, value) ->
            (value as? kotlinx.serialization.json.JsonPrimitive)?.content ?: value.toString()
        }

    private fun envelope(
        code: Int,
        data: String,
    ) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"code":$code,"msg":"","data":$data,"trace_id":"trace-000123"}""")

    private fun loggedIn(
        scope: String,
        invite: String = "",
    ) = envelope(0, """{"user_id":"user-one","is_new_user":true,"tokens":{${pair("one", scope)}}$invite}""")

    private fun refreshed(scope: String) = envelope(0, "{${pair("two", scope)}}")

    private fun pair(
        tag: String,
        scope: String,
    ) = """"access_token":"access-$tag","access_expires_at":"2099-01-01T00:00:00+08:00",""" +
        """"refresh_token":"refresh-$tag","refresh_expires_at":"2099-01-01T00:00:00+08:00","session_scope":"$scope""""

    /** 记录 LoginHost 每个出口的调用顺序（与 App 的 AppLogin 同一接口）。 */
    private class RecordingHost : LoginHost {
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        var pending = false
        var update = AfterLoginUpdate.SUPPORTED
        var sessionAtResume: String? = null
        var onEvent: (String) -> Unit = {}

        private fun record(event: String) {
            events += event
            onEvent(event)
        }

        override fun updateState() = update

        override fun hasPendingAction() = pending

        override fun closeLogin() = record("close_login")

        override fun resumePending() = record("resume_pending")

        override fun discardPending() {
            pending = false
            record("discard_pending")
        }

        override fun enterForceUpdate() = record("enter_force_update")

        override fun sessionChanged() = record("session_changed")

        override fun sessionSynced() = record("session_synced")

        override fun sessionEnded() = record("session_ended")
    }

    private object FixtureDevice : DeviceCredentials {
        override val deviceId: String = "device-fixture"
        override val installSecret: String = "install-fixture"
    }

    private companion object {
        const val REFRESH = "/v1/auth/refresh"
        const val CONSENT_AT = 1_700_000_000_000L
        const val WAIT_S = 5L
        const val CANCEL_WITHIN_MS = 3_000L
        const val POLL_MS = 20L
        const val LOCAL_BUDGET_MS = 120_000L
    }
}
