package com.couli.core.network

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
 * APP-03b 随任务处理的网络核心后续项：
 * - session_unreadable（编排者裁定，与 iOS 同名）：会话读取区分不存在、读取失败、损坏；读取失败时 x-auth 不是 none 的接口本地拒绝，
 *   不当成没有会话匿名发出，不刷新、不清会话；x-auth=none 的照常不带令牌发出。
 * - F-AND-10：清会话隔离只在确认旧会话已删、或已被成功保存的新会话替换后才解除；写入失败造成的世代变化继续阻断旧令牌
 *   （「清理失败 + 并发登录保存失败」交错）。
 */
class ApiClientSessionReadTest {
    private lateinit var server: MockWebServer

    @Volatile
    private var loginReply: MockResponse = loginTokens("access-B")

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when (request.path) {
                        "/v1/auth/refresh" -> envelope(10404)
                        "/v1/auth/login/sms" -> loginReply
                        else -> envelope(if (failFirstCall.getAndSet(false)) 10002 else 0, "{}")
                    }
            }
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private val failFirstCall = AtomicBoolean(false)

    private class FixedDevice : DeviceCredentials {
        override val deviceId: String? = "device-fixture"
        override val installSecret: String? = "test-only.install.device-a"
    }

    /** 读取可控失败的会话存储（实现 [SessionReader]，同 KeystoreSessionTokens）。 */
    private class UnreadableTokens(
        val inner: MemorySessionTokens,
    ) : SessionTokens by inner,
        SessionReader {
        @Volatile
        var unreadable = true
        val clears = AtomicInteger()

        override fun readSession(): SessionRead =
            if (unreadable) SessionRead.Unreadable(inner.generation) else SessionRead.Present(inner.snapshot())

        override fun snapshot(): SessionSnapshot =
            if (unreadable) SessionSnapshot(null, null, null, null, inner.generation) else inner.snapshot()

        override fun clear() {
            clears.incrementAndGet()
            inner.clear()
        }

        override fun clearIf(expectedGeneration: Long): Boolean {
            clears.incrementAndGet()
            return inner.clearIf(expectedGeneration)
        }
    }

    /**
     * 清会话与保存都可控失败（失败时世代照样前进、旧会话还在存储里，同 KeystoreSessionTokens）。
     */
    private class FlakyTokens(
        val inner: MemorySessionTokens,
    ) : SessionTokens by inner {
        @Volatile
        var failClear = true

        @Volatile
        var failSave = false
        val clears = AtomicInteger()

        private fun failWrite(): Nothing {
            val kept = inner.snapshot()
            // 落盘失败：世代前进，盘上仍是原来的会话
            inner.save(checkNotNull(kept.accessToken), checkNotNull(kept.refreshToken), checkNotNull(kept.scope), kept.userId)
            error("写入加密存储失败")
        }

        override fun clear() {
            if (failClear) failWrite()
            clears.incrementAndGet()
            inner.clear()
        }

        override fun clearIf(expectedGeneration: Long): Boolean {
            if (inner.generation != expectedGeneration) return false
            clear()
            return true
        }

        override fun saveIf(
            expectedGeneration: Long,
            accessToken: String,
            refreshToken: String,
            scope: String,
            userId: String?,
        ): Boolean {
            if (failSave) failWrite()
            return inner.saveIf(expectedGeneration, accessToken, refreshToken, scope, userId)
        }
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

    // ---------- session_unreadable ----------

    @Test
    fun unreadableSessionRejectsAuthRequiredAndOptionalLocally() {
        val tokens = UnreadableTokens(MemorySessionTokens("access-A", "refresh-A", SessionScopeRaw.FULL, "account-A"))
        val api = client(tokens)

        assertEquals(ApiResult.LocalReject(LocalRejectReason.SESSION_UNREADABLE), api.execute(ApiCall("getMe")))
        assertEquals("x-auth=optional 也拒绝", ApiResult.LocalReject(LocalRejectReason.SESSION_UNREADABLE), api.execute(ApiCall("getConfig")))
        assertEquals("不发出", 0, server.requestCount)
        assertEquals("不清会话", 0, tokens.clears.get())
        assertEquals("access-A", tokens.inner.accessToken)
    }

    @Test
    fun unreadableSessionStillSendsAuthNoneWithoutTokens() {
        val tokens = UnreadableTokens(MemorySessionTokens("access-A", "refresh-A", SessionScopeRaw.FULL, "account-A"))
        val result = client(tokens).execute(ApiCall("registerDevice", body = """{"device_hash":"00","id_source":"android_id"}"""))

        assertTrue(result.toString(), result is ApiResult.Success)
        val sent = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/v1/devices", sent.path)
        assertNull(sent.getHeader(HeaderNames.AUTHORIZATION))
    }

    @Test
    fun sessionReadableAgainSendsNormally() {
        val tokens = UnreadableTokens(MemorySessionTokens("access-A", "refresh-A", SessionScopeRaw.FULL, "account-A"))
        val api = client(tokens)
        assertTrue(api.execute(ApiCall("getMe")) is ApiResult.LocalReject)

        tokens.unreadable = false
        val result = api.execute(ApiCall("getMe"))

        assertTrue(result.toString(), result is ApiResult.Success)
        assertEquals("Bearer access-A", server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader(HeaderNames.AUTHORIZATION))
    }

    @Test
    fun pendingLookupThrowsInsteadOfReportingNoRecordWhenSessionUnreadable() {
        val tokens = UnreadableTokens(MemorySessionTokens("access-A", "refresh-A", SessionScopeRaw.FULL, "account-A"))
        val thrown = runCatching { client(tokens).pendingFor("createWithdrawal") }.exceptionOrNull()
        assertTrue(thrown.toString(), thrown is UnreadableRecordException)
    }

    // ---------- F-AND-10 ----------

    /** A 的 10002 → 刷新被拒 → 清会话写盘失败 → 会话隔离，A 仍在存储里。 */
    private fun quarantined(tokens: FlakyTokens): ApiClient {
        failFirstCall.set(true)
        val api = client(tokens)
        val failure = api.execute(ApiCall("getMe")) as ApiResult.Failure
        assertEquals(ErrorEffectKind.CLEAR_SESSION_LOGIN, failure.effect.kind)
        assertEquals("access-A", tokens.inner.accessToken)
        return api
    }

    @Test
    fun fand10_failedCleanupInterleavedWithAFailedLoginSaveKeepsBlockingTheOldToken() {
        val tokens = FlakyTokens(MemorySessionTokens("access-A", "refresh-A", SessionScopeRaw.FULL, "account-A"))
        val api = quarantined(tokens)
        val generationBefore = tokens.inner.generation

        // 清理仍失败时 B 登录，保存也失败：世代前进，但盘上还是 A
        tokens.failSave = true
        val login = api.execute(ApiCall("loginBySms", body = LOGIN_BODY))
        assertEquals(ApiResult.SessionNotSaved(LocalRejectReason.SESSION_SAVE_FAILED, TRACE), login)
        assertTrue("写入失败让世代前进", tokens.inner.generation > generationBefore)
        assertEquals("access-A", tokens.inner.accessToken)

        // 世代变了不等于旧会话已删：仍隔离，A 的令牌不带出
        api.execute(ApiCall("getMe"))
        assertNoRequestCarried("Bearer access-A", after = 1)

        // 删除终于成功：确认已删后才解除隔离
        tokens.failClear = false
        api.execute(ApiCall("getMe"))
        assertNull(tokens.inner.accessToken)
        assertEquals(1, tokens.clears.get())
        assertNoRequestCarried("Bearer access-A", after = 1)
    }

    @Test
    fun fand10_successfullySavedNewLoginLiftsTheQuarantineWithoutDeletingIt() {
        val tokens = FlakyTokens(MemorySessionTokens("access-A", "refresh-A", SessionScopeRaw.FULL, "account-A"))
        val api = quarantined(tokens)

        // 清理仍失败，但 B 的登录保存成功：已被新会话替换 → 解除隔离，B 不被删
        val login = api.execute(ApiCall("loginBySms", body = LOGIN_BODY))
        assertTrue(login.toString(), login is ApiResult.Success)
        api.execute(ApiCall("getMe"))

        assertEquals("access-B", tokens.inner.accessToken)
        assertEquals(0, tokens.clears.get())
        assertEquals("Bearer access-B", authorizations().last())
    }

    // ---------- APP-03b R1-09：Tracker 的 user_id 来源 ----------

    @Test
    fun r1_09_activeUserIdIsNullWhileTheSessionIsQuarantinedAndReturnsAfterANewSessionIsSaved() {
        val tokens = FlakyTokens(MemorySessionTokens("access-A", "refresh-A", SessionScopeRaw.FULL, "account-A"))
        val api = quarantined(tokens)
        assertEquals("存储里仍是 A（直接读令牌会带出旧账号）", "account-A", tokens.inner.snapshot().userId)

        assertNull("隔离期间不带 user_id", api.activeUserId())

        // 新会话成功保存后才恢复
        assertTrue(api.execute(ApiCall("loginBySms", body = LOGIN_BODY)) is ApiResult.Success)
        assertEquals("account-B", api.activeUserId())
    }

    @Test
    fun r1_09_activeUserIdIsNullWhenTheSessionIsUnreadable() {
        val tokens = UnreadableTokens(MemorySessionTokens("access-A", "refresh-A", SessionScopeRaw.FULL, "account-A"))
        assertNull(client(tokens).activeUserId())
        tokens.unreadable = false
        assertEquals("account-A", client(tokens).activeUserId())
    }

    private val seen = mutableListOf<String?>()

    private fun authorizations(): List<String?> {
        while (true) {
            val request = server.takeRequest(100, TimeUnit.MILLISECONDS) ?: break
            seen += request.getHeader(HeaderNames.AUTHORIZATION)
        }
        return seen.toList()
    }

    /** 第 [after] 个请求之后（第一个是触发隔离的 getMe，那时 A 还有效）没有请求带 [header]。 */
    private fun assertNoRequestCarried(
        header: String,
        after: Int,
    ) {
        val later = authorizations().drop(after)
        assertTrue(later.toString(), later.none { it == header })
    }

    private fun envelope(
        code: Int,
        data: String = "null",
    ) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"code":$code,"msg":"","data":$data,"trace_id":"$TRACE"}""")

    private fun loginTokens(access: String) =
        envelope(
            0,
            """{"user_id":"account-B","is_new_user":false,"tokens":{"access_token":"$access",""" +
                """"access_expires_at":"2026-10-02T11:30:00+08:00","refresh_token":"refresh-B",""" +
                """"refresh_expires_at":"2026-11-01T09:30:00+08:00","session_scope":"full"}}""",
        )

    private companion object {
        const val TRACE = "trace-0123456789"
        const val LOGIN_BODY = """{"phone":"13800000000","code":"123456"}"""
        val FULL = NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, update = UpdateRaw.SUPPORTED)
    }
}
