package com.couli.core.network

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * D10-1658 ④（FU-AND-STEPUP-SUMMARY 第二轮）：x-auth=optional 的接口有令牌就带、从不等待不拦截——会话读不出时未登录的登录发码立即发出（不带令牌）；
 * 只有二次验证面板的 step_up 发码（[ApiCall.requiresSession]）按需要会话处理：读不出本地拒绝、不发出。
 */
class OptionalAuthSessionTest {
    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"code":0,"msg":"","data":{"resend_after_sec":60,"expires_in_sec":300},"trace_id":"$TRACE"}""")
            }
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private class FixedDevice : DeviceCredentials {
        override val deviceId: String? = "device-fixture"
        override val installSecret: String? = "test-only.install.device-a"
    }

    /** 读取可控失败的会话存储（同 KeystoreSessionTokens 的读不出）。 */
    private class UnreadableTokens(
        val inner: MemorySessionTokens,
    ) : SessionTokens by inner,
        SessionReader {
        @Volatile
        var unreadable = true

        override fun readSession(): SessionRead =
            if (unreadable) SessionRead.Unreadable(inner.generation) else SessionRead.Present(inner.snapshot())

        override fun snapshot(): SessionSnapshot =
            if (unreadable) SessionSnapshot(null, null, null, null, inner.generation) else inner.snapshot()
    }

    private fun client(tokens: SessionTokens) =
        ApiClient(
            identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.2.0", build = "1", channel = "official"),
            baseUrl = { server.url("/").toString() },
            context = { FULL },
            device = FixedDevice(),
            tokens = tokens,
            pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
            http = OkHttpClient(),
            sleeper = {},
            retryPolicy = { RetryPolicy.forOperation(it).copy(timeoutMillis = 2_000) },
        )

    private fun unreadable() = UnreadableTokens(MemorySessionTokens("access-A", "refresh-A", SessionScopeRaw.FULL, "account-A"))

    @Test
    fun unreadableSessionStillSendsTheLoginCodeImmediatelyWithoutToken() {
        val call = ApiCall("sendSmsCode", body = """{"phone":"13800005678","purpose":"login"}""", sendsWhenIdentityUnknown = true)
        val result = client(unreadable()).execute(call)

        assertTrue(result.toString(), result is ApiResult.Success)
        val sent = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/v1/auth/sms-codes", sent.path)
        assertNull("读不出就不带令牌", sent.getHeader(HeaderNames.AUTHORIZATION))
    }

    @Test
    fun unreadableSessionStillSendsTheBindCode() {
        val call = ApiCall("sendSmsCode", body = """{"phone":"13800005678","purpose":"bind"}""", sendsWhenIdentityUnknown = true)
        val result = client(unreadable()).execute(call)
        assertTrue(result.toString(), result is ApiResult.Success)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun stepUpSendFromThePanelNeedsAReadySession() {
        val result =
            client(unreadable()).execute(
                ApiCall("sendSmsCode", body = """{"purpose":"step_up","action":"withdraw"}""", requiresSession = true),
            )
        assertEquals(ApiResult.LocalReject(LocalRejectReason.SESSION_UNREADABLE), result)
        assertEquals("不发出", 0, server.requestCount)
    }

    @Test
    fun readableSessionCarriesTheTokenOnOptionalSends() {
        val tokens = unreadable().also { it.unreadable = false }
        val api = client(tokens)
        assertTrue(api.execute(ApiCall("sendSmsCode", body = """{"phone":"13800005678","purpose":"login"}""")) is ApiResult.Success)
        assertEquals("有令牌就带", "Bearer access-A", server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader(HeaderNames.AUTHORIZATION))
        val stepUp = ApiCall("sendSmsCode", body = """{"purpose":"step_up","action":"withdraw"}""", requiresSession = true)
        assertTrue(api.execute(stepUp) is ApiResult.Success)
        assertEquals("Bearer access-A", server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader(HeaderNames.AUTHORIZATION))
    }

    private companion object {
        const val TRACE = "0199a3b4-5c6d-7e8f-9a0b-1c2d3e4f5a6b"
        val FULL = NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, update = UpdateRaw.SUPPORTED)
    }
}
