package com.couli.core.network

import java.util.Collections
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F-AND-14（APP-05a 共性第 26 条）：清会话类 Effect（clear_session_login）所属的会话世代由网络层在确定清理已发生的那一刻、同一同步区里
 * 定下并随 Effect 传出（[ErrorEffect.sessionEpoch]），App 不在回调时读「当前世代」补作归属；清理没有发生（旧世代的响应）就不发清会话
 * 通知。
 */
class ClearSessionEffectEpochTest {
    private lateinit var server: MockWebServer
    private val tokens = MemorySessionTokens("access-A", "refresh-A", SessionScopeRaw.FULL, "account-A")
    private val effects: MutableList<ErrorEffect> = Collections.synchronizedList(mutableListOf())

    @Volatile
    private var answer: (RecordedRequest) -> MockResponse = { envelope(0) }

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = answer(request)
            }
        server.start()
    }

    @After
    fun stop() = server.shutdown()

    private fun client() =
        ApiClient(
            identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.2.0", build = "1", channel = "official"),
            baseUrl = { server.url("/").toString() },
            context = { NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, UpdateRaw.SUPPORTED) },
            device = FixedDevice,
            tokens = tokens,
            pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
            http = OkHttpClient(),
            hooks = NetworkHooks(onEffect = { effects += it }),
            sleeper = {},
        )

    @Test
    fun a10404ClearCarriesTheEpochFixedWhenTheSessionWasCleared() {
        val api = client()
        answer = { envelope(10404) }
        val failure = api.execute(ApiCall("getMe")) as ApiResult.Failure
        assertEquals(ErrorEffectKind.CLEAR_SESSION_LOGIN, failure.effect.kind)
        assertNull(tokens.accessToken)
        assertEquals("清理之后的登录世代", tokens.loginEpoch, failure.effect.sessionEpoch)
        assertEquals(failure.effect, effects.single())
    }

    @Test
    fun aRefreshFailureCarriesTheEpochOfTheClear() {
        val api = client()
        answer = { request -> if (request.path == REFRESH) envelope(10404) else envelope(10002) }
        val first = api.execute(ApiCall("getMe")) as ApiResult.Failure
        assertEquals(ErrorEffectKind.CLEAR_SESSION_LOGIN, first.effect.kind)
        val cleared = tokens.loginEpoch
        assertEquals(cleared, first.effect.sessionEpoch)

        assertEquals(first.effect, effects.single { it.kind == ErrorEffectKind.CLEAR_SESSION_LOGIN })
    }

    @Test
    fun a10404OfAnOlderSessionClearsNothingAndSendsNoClearEffect() {
        val api = client()
        // 请求带着 A 的令牌发出；响应回来之前已经登录了 B（会话换代）
        answer = {
            tokens.save("access-B", "refresh-B", SessionScopeRaw.FULL, "account-B")
            envelope(10404)
        }
        val failure = api.execute(ApiCall("getMe")) as ApiResult.Failure
        assertEquals("B 没有被旧响应清掉", "access-B", tokens.accessToken)
        assertTrue(
            "旧世代的 10404 不发清会话通知",
            effects.none { it.kind == ErrorEffectKind.CLEAR_SESSION_LOGIN || it.kind == ErrorEffectKind.CLEAR_SESSION_STAY_FORCE_UPDATE },
        )
        assertNull(failure.effect.sessionEpoch)
        assertNotNull(failure.effect)
    }

    private object FixedDevice : DeviceCredentials {
        override val deviceId: String? = "device-fixture"
        override val installSecret: String? = "test-only.install.device-a"
    }

    private fun envelope(code: Int) =
        MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("""{"code":$code,"msg":"","data":null,"trace_id":"trace-0123456789"}""")

    private companion object {
        const val REFRESH = "/v1/auth/refresh"
    }
}
