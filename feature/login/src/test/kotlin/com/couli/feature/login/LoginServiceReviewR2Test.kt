package com.couli.feature.login

import com.couli.core.network.ApiCall
import com.couli.core.network.ApiClient
import com.couli.core.network.ApiResult
import com.couli.core.network.ClientIdentity
import com.couli.core.network.DeviceCredentials
import com.couli.core.network.MemoryPendingKeyBackend
import com.couli.core.network.MemorySessionTokens
import com.couli.core.network.NetworkContext
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PrivacyRaw
import com.couli.core.network.SessionScopeRaw
import com.couli.core.network.SessionSnapshot
import com.couli.core.network.SessionTokens
import com.couli.core.network.UpdateRaw
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
 * Android 第 2 轮评审（android-review-r2.md）落在登录执行器与网络层的两条，经真实 [ApiClient] + [LoginService] 与本地假服务复现：
 * - R2-02（共性第 22 条）：号码 A 发码已被服务端受理、响应还没回来时换号（旧请求被取消）→ 短信可能已送达；换回 A 仍可继续输入验证码，
 *   再次发码遇到 42901 也一样；旧请求不改当前页面。
 * - R2-03（共性第 23 条）：登出核对世代之后、删除会话之前插进一次新登录的保存 → 新会话不被登出删掉，Router 的清理也不作用于新会话。
 */
class LoginServiceReviewR2Test {
    private lateinit var server: MockWebServer
    private lateinit var api: ApiClient
    private lateinit var service: LoginService
    private val memory = MemorySessionTokens()
    private val tokens = InterleavingTokens(memory)
    private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val now = 1_700_000_000_000L
    private val holds = mutableListOf<CountDownLatch>()

    @Volatile
    private var answer: (RecordedRequest) -> MockResponse = { envelope(0, "{}") }

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = answer(request)
            }
        server.start()
        api =
            ApiClient(
                identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.0.0", build = "1", channel = "official"),
                baseUrl = { server.url("/").toString() },
                context = { NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, UpdateRaw.SUPPORTED) },
                device = FixtureDevice,
                tokens = tokens,
                pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
                sleeper = {},
            )
        service = LoginService(api, tokens, RecordingHost(events), LegalVersions(1, 1)) { now }
    }

    @After
    fun stop() {
        holds.forEach(CountDownLatch::countDown)
        server.shutdown()
    }

    private fun hold(): CountDownLatch = CountDownLatch(1).also { holds += it }

    // ---- R2-02

    @Test
    fun aSendAcceptedBeforeTheNumberChangedStillOffersTheCodeWhenSwitchingBackEvenUnderARateLimit() =
        runBlocking {
            val accepted = CountDownLatch(1)
            val release = hold()
            answer = {
                // 服务端已受理（请求已写出），响应还没回来
                accepted.countDown()
                release.await(WAIT_S, TimeUnit.SECONDS)
                envelope(0, """{"resend_after_sec":60,"expires_in_sec":300}""")
            }
            val first = async(Dispatchers.Default) { service.sendCode(PHONE_A) }
            assertTrue(withContext(Dispatchers.IO) { accepted.await(WAIT_S, TimeUnit.SECONDS) })

            // 用户改了号码：作废这次尝试，在途的发码请求随之取消
            service.invalidateAttempt()
            val stale = withTimeout(TimeUnit.SECONDS.toMillis(WAIT_S)) { first.await() }
            assertEquals("旧请求不改当前页面", LoginOutcome.STALE, stale.stateRaw)
            assertNull(stale.phone)
            assertEquals("号码 B 没有可继续的码", PhoneEntry.SEND, service.continueEntry(PHONE_B).kind)

            // 换回 A：短信可能已送达，可以继续输入
            val entry = service.continueEntry(PHONE_A)
            assertEquals("换回原号码：仍可继续输入验证码", PhoneEntry.CONTINUE, entry.kind)

            // 再次发码遇到 42901：发码入口禁用，已送达的码照样可以输入
            answer = { envelope(42901, "null").setResponseCode(429).setHeader("Retry-After", "60") }
            val limited = service.sendCode(PHONE_A)
            assertEquals(SmsCountdown.STATE_RATE_LIMITED, limited.stateRaw)
            assertEquals("限流期间仍可继续输入验证码", PhoneEntry.CONTINUE, service.continueEntry(PHONE_A).kind)
            assertTrue(service.issuedCodeRemainingSec(PHONE_A) > 0)
        }

    // ---- R2-03

    @Test
    fun aNewLoginSavedBetweenTheLogoutCheckAndTheDeleteIsNotCleared() =
        runBlocking {
            memory.save("access-one", "refresh-one", SessionScopeRaw.FULL, "user-one")
            val logoutArrived = CountDownLatch(1)
            val logoutRelease = hold()
            val loginArrived = CountDownLatch(1)
            val loginRelease = hold()
            answer = { request ->
                if (request.path == LOGOUT_PATH) {
                    logoutArrived.countDown()
                    logoutRelease.await(WAIT_S, TimeUnit.SECONDS)
                    envelope(0, "{}")
                } else {
                    loginArrived.countDown()
                    loginRelease.await(WAIT_S, TimeUnit.SECONDS)
                    envelope(0, """{"user_id":"user-two","is_new_user":false,"tokens":{${pair("two")}}}""")
                }
            }
            val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val logout = sessionScope.async { service.logout() }
            assertTrue(withContext(Dispatchers.IO) { logoutArrived.await(WAIT_S, TimeUnit.SECONDS) })

            // 登出还在等回包：另一处开始新登录，它的响应也在路上
            val loginResult = AtomicReference<ApiResult?>()
            val loginThread = Thread { loginResult.set(api.execute(ApiCall(LoginErrorMapper.LOGIN_BY_SMS, body = "{}"))) }
            loginThread.start()
            assertTrue(withContext(Dispatchers.IO) { loginArrived.await(WAIT_S, TimeUnit.SECONDS) })

            // 登出读取登录世代核对的那一刻：放行新登录的保存，给它时间插到「核对」与「删除」之间
            tokens.interleave {
                loginRelease.countDown()
                tokens.saved.await(INTERLEAVE_WAIT_MS, TimeUnit.MILLISECONDS)
            }
            logoutRelease.countDown()
            val loggedOut = withTimeout(TimeUnit.SECONDS.toMillis(WAIT_S)) { logout.await() }
            withContext(Dispatchers.IO) { loginThread.join(TimeUnit.SECONDS.toMillis(WAIT_S)) }

            assertTrue("插入点确实被触发", tokens.interleaved.get())
            val newLogin = loginResult.get()
            if (newLogin is ApiResult.Success) {
                // 新登录报告保存成功：它的会话必须还在，登出不能删它、也不能对它做 Router 清理
                assertEquals("新登录的会话没有被登出删掉", "access-two", memory.accessToken)
                assertFalse(loggedOut)
                assertFalse("Router 清理不作用于新会话", "session_ended" in events)
            } else {
                // 登出先完成：新登录因世代已变没有保存（页面按失败处理），本机回到游客
                assertNull(memory.accessToken)
                assertTrue(loggedOut)
                assertTrue("session_ended" in events)
            }
        }

    /** 委托给内存实现；[interleave] 之后第一次读登录世代时先执行插入动作（返回的是插入之前读到的值，模拟「先核对、后被插入」）。 */
    private class InterleavingTokens(
        private val delegate: MemorySessionTokens,
    ) : SessionTokens {
        private val armed = AtomicReference<(() -> Unit)?>(null)
        val interleaved = AtomicBoolean(false)
        val saved = CountDownLatch(1)

        fun interleave(action: () -> Unit) = armed.set(action)

        override val accessToken: String? get() = delegate.accessToken
        override val refreshToken: String? get() = delegate.refreshToken
        override val scope: String? get() = delegate.scope
        override val userId: String? get() = delegate.userId
        override val generation: Long get() = delegate.generation
        override val loginEpoch: Long
            get() {
                val value = delegate.loginEpoch
                armed.getAndSet(null)?.let { action ->
                    interleaved.set(true)
                    action()
                }
                return value
            }

        override fun snapshot(): SessionSnapshot = delegate.snapshot()

        override fun save(
            accessToken: String,
            refreshToken: String,
            scope: String,
            userId: String?,
        ) = delegate.save(accessToken, refreshToken, scope, userId)

        override fun clear() = delegate.clear()

        override fun replaceIf(
            expectedGeneration: Long,
            accessToken: String,
            refreshToken: String,
            scope: String,
        ) = delegate.replaceIf(expectedGeneration, accessToken, refreshToken, scope)

        override fun clearIf(expectedGeneration: Long) = delegate.clearIf(expectedGeneration)

        override fun saveIf(
            expectedGeneration: Long,
            accessToken: String,
            refreshToken: String,
            scope: String,
            userId: String?,
        ): Boolean = delegate.saveIf(expectedGeneration, accessToken, refreshToken, scope, userId).also { if (it) saved.countDown() }
    }

    private class RecordingHost(
        private val events: MutableList<String>,
    ) : LoginHost {
        override fun updateState() = AfterLoginUpdate.SUPPORTED

        override fun hasPendingAction() = false

        override fun closeLogin() {
            events += "close_login"
        }

        override fun resumePending() {
            events += "resume_pending"
        }

        override fun discardPending() {
            events += "discard_pending"
        }

        override fun enterForceUpdate() {
            events += "enter_force_update"
        }

        override fun sessionChanged() {
            events += "session_changed"
        }

        override fun sessionSynced() {
            events += "session_synced"
        }

        override fun sessionEnded() {
            events += "session_ended"
        }
    }

    private fun envelope(
        code: Int,
        data: String,
    ) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"code":$code,"msg":"","data":$data,"trace_id":"trace-000222"}""")

    private fun pair(tag: String) =
        """"access_token":"access-$tag","access_expires_at":"2099-01-01T00:00:00+08:00",""" +
            """"refresh_token":"refresh-$tag","refresh_expires_at":"2099-01-01T00:00:00+08:00","session_scope":"full""""

    private object FixtureDevice : DeviceCredentials {
        override val deviceId: String = "device-fixture"
        override val installSecret: String = "install-fixture"
    }

    private companion object {
        const val PHONE_A = "13800138000"
        const val PHONE_B = "13900139000"
        const val LOGOUT_PATH = "/v1/auth/logout"
        const val WAIT_S = 5L
        const val INTERLEAVE_WAIT_MS = 500L
    }
}
