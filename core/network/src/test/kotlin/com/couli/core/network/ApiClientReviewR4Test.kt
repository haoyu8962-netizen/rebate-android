package com.couli.core.network

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
 * APP-03a Android 第 4 轮评审（android-review-r4.md R4-01）：清会话写盘失败后的隔离重试只按被隔离的那一代清除，
 * 隔离已解除或会话已换代时不误删之后新登录的会话。
 */
class ApiClientReviewR4Test {
    private lateinit var server: MockWebServer
    private val pool = Executors.newCachedThreadPool()

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when (request.path) {
                        "/v1/auth/refresh" -> envelope(10404)
                        else -> envelope(if (failFirstCall.getAndSet(false)) 10002 else 0, "{}")
                    }
            }
    }

    @After
    fun stop() {
        pool.shutdownNow()
        server.shutdown()
    }

    private val failFirstCall = AtomicBoolean(true)

    private class FixedDevice : DeviceCredentials {
        override val deviceId: String? = "device-fixture"
        override val installSecret: String? = "test-only.install.device-a"
    }

    /** 清会话写盘可控失败的令牌存储（失败时世代照样前进，同 KeystoreSessionTokens）；记下成功的清除次数。 */
    private class FlakyClearTokens(
        val inner: MemorySessionTokens,
    ) : SessionTokens by inner {
        @Volatile
        var failClear = true
        val clears = AtomicInteger()

        override fun clear() {
            if (failClear) {
                inner.clear()
                inner.save("access-A", "refresh-A", SessionScopeRaw.FULL, "account-A") // 落盘失败：旧会话还在存储里
                error("写入加密存储失败")
            }
            clears.incrementAndGet()
            inner.clear()
        }

        override fun clearIf(expectedGeneration: Long): Boolean {
            if (inner.generation != expectedGeneration) return false
            clear()
            return true
        }
    }

    private fun client(tokens: SessionTokens) =
        ApiClient(
            identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.2.0", build = "1", channel = "official"),
            baseUrl = { server.url("/").toString() },
            context = { FULL_SUPPORTED },
            device = FixedDevice(),
            tokens = tokens,
            pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
            http = OkHttpClient(),
            sleeper = {},
            retryPolicy = { RetryPolicy.forOperation(it).copy(timeoutMillis = 2_000) },
        )

    private fun envelope(
        code: Int,
        data: String = "null",
    ) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"code":$code,"msg":"","data":$data,"trace_id":"trace-0123456789"}""")

    /** A 的 10002 → 刷新被拒 → 清会话写盘失败 → 会话隔离。 */
    private fun quarantinedClient(tokens: FlakyClearTokens): ApiClient {
        val api = client(tokens)
        val failure = api.execute(ApiCall("getMe")) as ApiResult.Failure
        assertEquals(ErrorEffectKind.CLEAR_SESSION_LOGIN, failure.effect.kind)
        assertEquals("A still on disk", "access-A", tokens.accessToken)
        return api
    }

    @Test
    fun r401_twoQuarantineRetriesInterleavedWithANewLoginKeepTheNewSession() {
        val tokens = FlakyClearTokens(MemorySessionTokens("access-A", "refresh-A", SessionScopeRaw.FULL, "account-A"))
        val api = quarantinedClient(tokens)
        tokens.failClear = false

        // T2 已读到隔离（锁外），在取得 refreshLock 之前暂停
        val t2Paused = CountDownLatch(1)
        val releaseT2 = CountDownLatch(1)
        val t2Name = "r401-t2"
        val pauseOnce = AtomicBoolean(true)
        api.beforeQuarantineRetry = {
            if (Thread.currentThread().name == t2Name && pauseOnce.getAndSet(false)) {
                t2Paused.countDown()
                releaseT2.await(5, TimeUnit.SECONDS)
            }
        }
        val t2 =
            pool.submit<ApiResult> {
                Thread.currentThread().name = t2Name
                api.execute(ApiCall("getMe"))
            }
        assertTrue("t2 did not reach the retry", t2Paused.await(5, TimeUnit.SECONDS))

        // T1 重试清理成功、解除隔离；随后 B 登录并保存会话
        api.execute(ApiCall("getMe"))
        assertEquals("T1 cleared A once", 1, tokens.clears.get())
        assertNull(tokens.accessToken)
        tokens.save("access-B", "refresh-B", SessionScopeRaw.FULL, "account-B")

        // T2 恢复：锁内重新检查隔离、按被隔离的世代清理，B 的新会话保留
        releaseT2.countDown()
        t2.get(15, TimeUnit.SECONDS)
        assertEquals("access-B", tokens.accessToken)
        assertEquals("refresh-B", tokens.refreshToken)
        assertEquals("account-B", tokens.userId)
        assertEquals("no second clear", 1, tokens.clears.get())
    }

    @Test
    fun r401_retryStopsWhenTheQuarantinedSessionWasAlreadyReplaced() {
        val tokens = FlakyClearTokens(MemorySessionTokens("access-A", "refresh-A", SessionScopeRaw.FULL, "account-A"))
        val api = quarantinedClient(tokens)
        tokens.failClear = false

        // 仍在隔离中，B 在别处登录（会话换代）：重试不清 B，直接解除隔离
        tokens.save("access-B", "refresh-B", SessionScopeRaw.FULL, "account-B")
        api.execute(ApiCall("getMe"))
        assertEquals("access-B", tokens.accessToken)
        assertEquals(0, tokens.clears.get())
        val last = List(server.requestCount) { server.takeRequest(1, TimeUnit.SECONDS)!! }.last()
        assertEquals("Bearer access-B", last.getHeader(HeaderNames.AUTHORIZATION))
    }

    private companion object {
        val FULL_SUPPORTED =
            NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, update = UpdateRaw.SUPPORTED)
    }
}
