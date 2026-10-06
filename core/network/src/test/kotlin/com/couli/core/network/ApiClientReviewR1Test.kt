package com.couli.core.network

import com.couli.core.generated.ApiOperation
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * APP-03a Android 第 1 轮评审（android-review-r1.md R1-01..R1-15）的复现测试：每条按评审写的失败场景构造，对本地假服务
 * （MockWebServer）发请求。登录与刷新响应取自契约快照 openapi 的示例（LoginResponse、TokenPairResponse）。
 */
@Suppress("LargeClass") // 15 条评审项各自的复现场景
class ApiClientReviewR1Test {
    private lateinit var server: MockWebServer
    private val forceUpdates = mutableListOf<String?>()
    private val effects = mutableListOf<ErrorEffect>()

    @Volatile
    private var state = FULL_SUPPORTED

    @Before
    fun start() {
        server = MockWebServer()
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

    private object PoisonDevice : DeviceCredentials {
        override val deviceId: String? get() = error("device_id must not be read")
        override val installSecret: String? get() = error("install_secret must not be read")
    }

    /** 委托给内存实现；可让刷新写入抛错（模拟 Keystore / commit 失败），并计数实际清除。 */
    private class ProbeTokens(
        val inner: MemorySessionTokens,
    ) : SessionTokens by inner {
        @Volatile
        var failReplace = false
        val clears = AtomicInteger()

        override fun replaceIf(
            expectedGeneration: Long,
            accessToken: String,
            refreshToken: String,
            scope: String,
        ): Boolean {
            check(!failReplace) { "写入加密存储失败" }
            return inner.replaceIf(expectedGeneration, accessToken, refreshToken, scope)
        }

        override fun clear() {
            clears.incrementAndGet()
            inner.clear()
        }

        override fun clearIf(expectedGeneration: Long): Boolean {
            if (inner.generation != expectedGeneration) return false
            clear()
            return true
        }
    }

    private class FailingBackend : PendingKeyBackend by MemoryPendingKeyBackend() {
        override fun write(record: PendingKeyRecord) = error("写入加密存储失败")
    }

    @Suppress("LongParameterList")
    private fun client(
        tokens: SessionTokens = MemorySessionTokens("old-access", "refresh-one", SessionScopeRaw.FULL, userId = "account-A"),
        device: DeviceCredentials = FixedDevice(),
        pending: PendingKeyStore = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
        sleeper: (Long) -> Unit = {},
        retryPolicy: (ApiOperation) -> RetryDecision = { RetryPolicy.forOperation(it).copy(timeoutMillis = 2_000) },
        baseUrl: () -> String? = { server.url("/").toString() },
    ) = ApiClient(
        identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.2.0", build = "1", channel = "official"),
        baseUrl = baseUrl,
        context = { state },
        device = device,
        tokens = tokens,
        pendingKeys = pending,
        hooks = NetworkHooks(onForceUpdate = { forceUpdates += it }, onEffect = { effects += it }),
        sleeper = sleeper,
        retryPolicy = retryPolicy,
    )

    private fun envelope(
        code: Int,
        data: String = "null",
    ) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"code":$code,"msg":"","data":$data,"trace_id":"trace-0123456789"}""")

    private fun json(text: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(text)

    private fun refreshPair(
        access: String,
        scope: String = SessionScopeRaw.FULL,
    ) = envelope(
        0,
        """{"access_token":"$access","access_expires_at":"2026-10-02T13:30:00+08:00","refresh_token":"refresh-$access",""" +
            """"refresh_expires_at":"2026-11-01T11:30:00+08:00","session_scope":"$scope"}""",
    )

    // ---------- R1-01 ----------

    @Test
    fun r101_withdrawalDuringReadBackoffRejectsRetryInsteadOfResendingOldCredentials() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(envelope(0, "{}"))
        val fast = { operation: ApiOperation -> RetryPolicy.forOperation(operation).copy(timeoutMillis = 300) }
        // 退避 500ms 期间撤回同意（基本模式）：getMe 不在匿名白名单
        val api = client(retryPolicy = fast, sleeper = { state = BASIC })
        val result = api.execute(ApiCall("getMe"))

        assertEquals(ApiResult.LocalReject(LocalRejectReason.BASIC_MODE_DENIED), result)
        assertEquals(1, server.requestCount)
        assertEquals("Bearer old-access", server.takeRequest().getHeader(HeaderNames.AUTHORIZATION))
    }

    @Test
    fun r101_retryOfWhitelistedReadIsRebuiltWithoutCapturedCredentials() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(envelope(0, "{}"))
        val fast = { operation: ApiOperation -> RetryPolicy.forOperation(operation).copy(timeoutMillis = 300) }
        val api = client(retryPolicy = fast, sleeper = { state = FULL_SUPPORTED.copy(credentialsReadable = false) })

        assertTrue(api.execute(ApiCall("getConfig")) is ApiResult.Success)
        val first = server.takeRequest()
        val retried = server.takeRequest()
        assertEquals("Bearer old-access", first.getHeader(HeaderNames.AUTHORIZATION))
        assertEquals("device-fixture", first.getHeader(HeaderNames.DEVICE_ID))
        assertNull(retried.getHeader(HeaderNames.AUTHORIZATION))
        assertNull(retried.getHeader(HeaderNames.DEVICE_ID))
        assertNotEquals(first.getHeader(HeaderNames.TRACE_ID), retried.getHeader(HeaderNames.TRACE_ID))
    }

    // ---------- R1-02 ----------

    @Test
    fun r102_unfinishedCleanupRejectsDeviceRegistrationAndOtherNonWhitelistedRequests() {
        state = FULL_SUPPORTED.copy(credentialsReadable = false)
        val api = client(tokens = MemorySessionTokens(), device = PoisonDevice)
        val registration = ApiCall("registerDevice", body = """{"device_hash":"stale-device-hash","platform":"android"}""")

        assertEquals(ApiResult.LocalReject(LocalRejectReason.BASIC_MODE_DENIED), api.execute(registration))
        assertEquals(ApiResult.LocalReject(LocalRejectReason.BASIC_MODE_DENIED), api.execute(ApiCall("checkAppVersion")))
        assertEquals(ApiResult.LocalReject(LocalRejectReason.BASIC_MODE_DENIED), api.execute(ApiCall("listOrders")))
        assertEquals(0, server.requestCount)
    }

    // ---------- R1-03 ----------

    @Test
    fun r103_postOn503WithRetryAfterZeroReachesServerOnce() {
        server.enqueue(envelope(50001).setResponseCode(503).setHeader("Retry-After", "0"))
        server.enqueue(envelope(0, "{}"))
        val result = client().execute(ApiCall("dismissPendingTrack", pathParams = mapOf("link_id" to "abc"), body = "{}"))

        assertEquals(50001, (result as ApiResult.Failure).code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun r103_stepUpWriteOn503WithRetryAfterZeroIsSubmittedOnce() {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("<html>busy</html>"))
        server.enqueue(envelope(0, "{}"))
        val pending = PendingKeyStore(MemoryPendingKeyBackend()) { 0L }
        val result = client(pending = pending).execute(ApiCall("createWithdrawal", body = """{"amount_cent":1000}""", stepUpToken = "t"))

        val failure = result as ApiResult.TransportFailure
        assertEquals(HttpTransport.KIND_NO_ENVELOPE, failure.kind)
        assertEquals(503, failure.httpStatus)
        assertTrue(failure.pendingKeyUnknown)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun r103_getOn503WithRetryAfterZeroIsNotResentByOkHttpOnlyByTheRebuiltReadRetry() {
        server.enqueue(envelope(50001).setResponseCode(503).setHeader("Retry-After", "0"))
        server.enqueue(envelope(0, "{}"))
        val sleeps = mutableListOf<Long>()
        assertTrue(client(sleeper = { sleeps += it }).execute(ApiCall("getMe")) is ApiResult.Success)

        // 第二次是 ApiClient 自己的读重试（退避后重新组头），不是 OkHttp 复用旧请求
        assertEquals(2, server.requestCount)
        assertEquals(listOf(RetryPolicy.READ_BACKOFF_MILLIS), sleeps)
        assertNotEquals(server.takeRequest().getHeader(HeaderNames.TRACE_ID), server.takeRequest().getHeader(HeaderNames.TRACE_ID))
    }

    @Test
    fun r103_writeRedirectIsNotFollowed() {
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "/v1/withdrawals"))
        server.enqueue(envelope(0, "{}"))
        val result = client().execute(ApiCall("dismissPendingTrack", pathParams = mapOf("link_id" to "abc"), body = "{}"))

        assertEquals(307, (result as ApiResult.TransportFailure).httpStatus)
        assertEquals(1, server.requestCount)
    }

    // ---------- R1-05 ----------

    @Test
    fun r105_basicModeRedirectToNonWhitelistedPathIsNotFollowed() {
        state = BASIC
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/v1/orders"))
        server.enqueue(envelope(0, "{}"))
        val result = client(device = PoisonDevice).execute(ApiCall("getConfig"))

        assertEquals(302, (result as ApiResult.TransportFailure).httpStatus)
        assertEquals(1, server.requestCount)
        assertEquals("/v1/config", server.takeRequest().path)
    }

    @Test
    fun r105_restrictedSessionRedirectIsNotFollowed() {
        val tokens = MemorySessionTokens("restricted", "refresh-one", SessionScopeRaw.DELETION_ONLY)
        state = FULL_SUPPORTED.copy(update = UpdateRaw.BELOW)
        server.enqueue(refreshPair("restricted-two", SessionScopeRaw.DELETION_ONLY))
        server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", "/v1/orders"))
        server.enqueue(envelope(0, "{}"))
        val result = client(tokens = tokens).execute(ApiCall("getDeletion"))

        assertEquals(301, (result as ApiResult.TransportFailure).httpStatus)
        assertEquals(2, server.requestCount)
    }

    // ---------- R1-04 ----------

    @Test
    fun r104_loginResponseExampleStoresNestedTokensAndUserId() {
        server.enqueue(json(example("/v1/auth/login/sms", "post")))
        val tokens = MemorySessionTokens()
        val result = client(tokens = tokens).execute(ApiCall("loginBySms", body = """{"phone":"13800000000","code":"123456"}"""))

        assertTrue(result is ApiResult.Success)
        assertEquals("example-access-token-one", tokens.accessToken)
        assertEquals("example-refresh-token-one", tokens.refreshToken)
        assertEquals(SessionScopeRaw.FULL, tokens.scope)
        assertEquals("0199a3b4-5c6d-7e8f-9a0b-1c2d3e4f5a61", tokens.userId)
        assertTrue(forceUpdates.isEmpty())
    }

    @Test
    fun r104_restrictedLoginExampleWhileUpdateUnknownStoresScopeAndEntersForceUpdate() {
        state = FULL_SUPPORTED.copy(update = UpdateRaw.UNKNOWN)
        val restricted = example("/v1/auth/login/sms", "post").replace("\"session_scope\":\"full\"", "\"session_scope\":\"deletion_only\"")
        assertTrue(restricted.contains("deletion_only"))
        server.enqueue(json(restricted))
        val tokens = MemorySessionTokens()
        val result = client(tokens = tokens).execute(ApiCall("loginByWechat", body = """{"attempt_id":"a","code":"c"}"""))

        assertTrue(result is ApiResult.Success)
        assertEquals(SessionScopeRaw.DELETION_ONLY, tokens.scope)
        assertEquals("example-access-token-one", tokens.accessToken)
        assertEquals(listOf<String?>(null), forceUpdates)
    }

    @Test
    fun r104_refreshResponseExampleStoresFlatTokensAndKeepsUserId() {
        server.enqueue(envelope(10002))
        server.enqueue(json(example("/v1/auth/refresh", "post")))
        server.enqueue(envelope(0, "{}"))
        val tokens = MemorySessionTokens("old-access", "refresh-one", SessionScopeRaw.FULL, userId = "account-A")
        assertTrue(client(tokens = tokens).execute(ApiCall("getMe")) is ApiResult.Success)

        assertEquals("example-access-token-two", tokens.accessToken)
        assertEquals("example-refresh-token-two", tokens.refreshToken)
        assertEquals("account-A", tokens.userId)
        server.takeRequest()
        server.takeRequest()
        assertEquals("Bearer example-access-token-two", server.takeRequest().getHeader(HeaderNames.AUTHORIZATION))
    }

    // ---------- R1-06 ----------

    @Test
    fun r106_late10002ForAlreadyRefreshedTokenReusesTheResult() {
        val lateArrived = CountDownLatch(1)
        val firstDone = CountDownLatch(1)
        val refreshes = AtomicInteger()
        server.dispatcher =
            dispatcher { request ->
                when {
                    request.path == "/v1/auth/refresh" -> {
                        refreshes.incrementAndGet()
                        refreshPair("new-access")
                    }

                    request.getHeader(HeaderNames.AUTHORIZATION) != "Bearer old-access" -> {
                        envelope(0, "{}")
                    }

                    request.path == "/v1/me?who=late" -> {
                        lateArrived.countDown()
                        firstDone.await(5, TimeUnit.SECONDS)
                        envelope(10002)
                    }

                    else -> {
                        envelope(10002)
                    }
                }
            }
        val tokens = MemorySessionTokens("old-access", "refresh-one", SessionScopeRaw.FULL)
        val api = client(tokens = tokens)
        val pool = Executors.newSingleThreadExecutor()
        val late = pool.submit<ApiResult> { api.execute(ApiCall("getMe", query = "who=late")) }
        assertTrue(lateArrived.await(5, TimeUnit.SECONDS))
        assertTrue(api.execute(ApiCall("getMe", query = "who=first")) is ApiResult.Success)
        firstDone.countDown()
        val lateResult = late.get(10, TimeUnit.SECONDS)
        pool.shutdown()

        assertTrue(lateResult.toString(), lateResult is ApiResult.Success)
        assertEquals(1, refreshes.get())
        assertEquals(1, api.refreshCoordinator.refreshCalls)
    }

    @Test
    fun r106_late10002AfterFailedRefreshClearsOnlyOnceAndKeepsANewLogin() {
        val lateArrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        val refreshes = AtomicInteger()
        server.dispatcher =
            dispatcher { request ->
                when (request.path) {
                    "/v1/auth/refresh" -> {
                        refreshes.incrementAndGet()
                        envelope(10404)
                    }

                    "/v1/me?who=late" -> {
                        lateArrived.countDown()
                        release.await(5, TimeUnit.SECONDS)
                        envelope(10002)
                    }

                    else -> {
                        envelope(10002)
                    }
                }
            }
        val tokens = ProbeTokens(MemorySessionTokens("old-access", "refresh-one", SessionScopeRaw.FULL))
        val api = client(tokens = tokens)
        val pool = Executors.newSingleThreadExecutor()
        val late = pool.submit<ApiResult> { api.execute(ApiCall("getMe", query = "who=late")) }
        assertTrue(lateArrived.await(5, TimeUnit.SECONDS))
        val first = api.execute(ApiCall("getMe", query = "who=first")) as ApiResult.Failure
        assertEquals(ErrorEffectKind.CLEAR_SESSION_LOGIN, first.effect.kind)
        // 用户随即重新登录
        tokens.save("fresh-login", "fresh-refresh", SessionScopeRaw.FULL, "account-A")
        release.countDown()
        val lateResult = late.get(10, TimeUnit.SECONDS) as ApiResult.Failure
        pool.shutdown()

        assertEquals(1, refreshes.get())
        assertEquals(1, tokens.clears.get())
        assertEquals("fresh-login", tokens.accessToken)
        // 晚到的 10002 属于已被清掉的那一代会话：复用那次刷新的失败结果，不再刷新、不清新会话；也不替重新登录后的新会话重放
        // （lessons 14），按通用错误态结束
        assertEquals(ErrorEffectKind.GENERIC_ERROR, lateResult.effect.kind)
        assertEquals(ReplayMode.NONE, lateResult.effect.replay)
        assertEquals("late + first + refresh, no replay", 3, server.requestCount)
    }

    // ---------- R1-07 ----------

    @Test
    fun r107_lateRefreshResponseAfterWithdrawalDoesNotRestoreSession() {
        val refreshArrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher =
            dispatcher { request ->
                when (request.path) {
                    "/v1/auth/refresh" -> {
                        refreshArrived.countDown()
                        release.await(5, TimeUnit.SECONDS)
                        refreshPair("resurrected")
                    }

                    else -> {
                        envelope(10002)
                    }
                }
            }
        val tokens = ProbeTokens(MemorySessionTokens("old-access", "refresh-one", SessionScopeRaw.FULL))
        val api = client(tokens = tokens)
        val pool = Executors.newSingleThreadExecutor()
        val call = pool.submit<ApiResult> { api.execute(ApiCall("getMe")) }
        assertTrue(refreshArrived.await(5, TimeUnit.SECONDS))
        // 撤回同意：清理项清掉令牌，凭据随即不可读
        tokens.clear()
        state = BASIC
        release.countDown()
        val result = call.get(10, TimeUnit.SECONDS)
        pool.shutdown()

        assertTrue(result.toString(), result is ApiResult.Failure)
        assertNull(tokens.inner.accessToken)
        assertNull(tokens.inner.refreshToken)
        assertEquals(1, tokens.clears.get())
    }

    @Test
    fun r107_lateRefreshResponseAfterEnvironmentSwitchAndNewLoginIsDiscarded() {
        val refreshArrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher =
            dispatcher { request ->
                when (request.path) {
                    "/v1/auth/refresh" -> {
                        refreshArrived.countDown()
                        release.await(5, TimeUnit.SECONDS)
                        refreshPair("old-environment")
                    }

                    else -> {
                        envelope(10002)
                    }
                }
            }
        val tokens = ProbeTokens(MemorySessionTokens("old-access", "refresh-one", SessionScopeRaw.FULL))
        val api = client(tokens = tokens)
        val pool = Executors.newSingleThreadExecutor()
        val call = pool.submit<ApiResult> { api.execute(ApiCall("getMe")) }
        assertTrue(refreshArrived.await(5, TimeUnit.SECONDS))
        // 切换环境的清理清掉令牌，之后在新环境登录
        tokens.clear()
        tokens.save("new-environment", "new-refresh", SessionScopeRaw.FULL, "account-B")
        release.countDown()
        call.get(10, TimeUnit.SECONDS)
        pool.shutdown()

        assertEquals("new-environment", tokens.accessToken)
        assertEquals("new-refresh", tokens.refreshToken)
        assertEquals(1, tokens.clears.get())
    }

    // ---------- R1-08 ----------

    @Test
    fun r108_refreshStorageFailureSettlesTheWindowAndNextRefreshCanStart() {
        server.enqueue(envelope(10002))
        server.enqueue(refreshPair("unsaved"))
        val tokens = ProbeTokens(MemorySessionTokens("old-access", "refresh-one", SessionScopeRaw.FULL))
        tokens.failReplace = true
        val api = client(tokens = tokens)
        val started = System.nanoTime()
        val failure = api.execute(ApiCall("getMe")) as ApiResult.Failure
        val waitedMillis = (System.nanoTime() - started) / 1_000_000

        assertEquals(ErrorEffectKind.CLEAR_SESSION_LOGIN, failure.effect.kind)
        assertTrue("waiters must not hang until the 30s timeout: $waitedMillis", waitedMillis < 10_000)
        assertFalse(api.refreshCoordinator.refreshing)
        assertNull(tokens.accessToken)

        // 重新登录后，新的 10002 能再发起刷新（窗口没有卡死）
        tokens.failReplace = false
        tokens.save("second-access", "second-refresh", SessionScopeRaw.FULL)
        server.enqueue(envelope(10002))
        server.enqueue(refreshPair("third-access"))
        server.enqueue(envelope(0, "{}"))
        assertTrue(api.execute(ApiCall("getMe")) is ApiResult.Success)
        assertEquals(2, api.refreshCoordinator.refreshCalls)
    }

    @Test
    fun r108_coordinatorNotifiesWaitersEvenWhenClearingThrows() {
        var completion: ((String?) -> Unit)? = null
        val failed = mutableListOf<RefreshRequest>()
        val heard = mutableListOf<String?>()
        val coordinator =
            RefreshCoordinator(
                refresh = { completion = it },
                replay = { _, _ -> },
                clearSession = { error("commit failed") },
                clock = { 0L },
                onFailure = { failed += it },
            )
        val operation =
            checkNotNull(
                com.couli.core.generated.ApiOperations
                    .byId("getMe"),
            )
        coordinator.run(listOf(RefreshRequest("r", operation, null, "")), forceUpdate = false)
        coordinator.awaitRefresh(forceUpdate = false) { heard += it }
        checkNotNull(completion).invoke(null)

        assertEquals(1, failed.size)
        assertEquals(listOf<String?>(null), heard)
        assertFalse(coordinator.refreshing)
    }

    @Test
    fun r108_coordinatorSettlesWhenStartingTheRefreshThrows() {
        val coordinator =
            RefreshCoordinator(refresh = { error("executor rejected") }, replay = { _, _ -> }, clearSession = {}, clock = { 0L })
        val heard = mutableListOf<String?>()
        coordinator.awaitRefresh(forceUpdate = false) { heard += it }
        assertEquals(listOf<String?>(null), heard)
        assertFalse(coordinator.refreshing)
    }

    // ---------- R1-09 ----------

    @Test
    fun r109_refreshOnlyFailureEndsTheCallWithClearSessionLogin() {
        val tokens = MemorySessionTokens("access", "refresh-one", SessionScopeRaw.FULL)
        val api = client(tokens = tokens)
        // 冷启动屏障在 full 会话下已过；之后拿到受限会话（如升级前登录）
        server.enqueue(envelope(0, "{}"))
        assertTrue(api.execute(ApiCall("getMe")) is ApiResult.Success)
        tokens.save("restricted", "refresh-r", SessionScopeRaw.DELETION_ONLY)
        server.enqueue(envelope(10405, """{"min_supported_version":"1.0.0"}"""))
        server.enqueue(envelope(10404))
        val failure = api.execute(ApiCall("getDeletion")) as ApiResult.Failure

        assertEquals(ErrorEffectKind.CLEAR_SESSION_LOGIN, failure.effect.kind)
        assertEquals("Login", failure.effect.route)
        assertNull(tokens.accessToken)
        assertTrue(forceUpdates.isEmpty())
        assertEquals(failure.effect, effects.last())
    }

    @Test
    fun r109_barrierRefreshFailureDoesNotSendTheOriginalRequest() {
        server.enqueue(envelope(10404))
        server.enqueue(envelope(0, "{}"))
        val tokens = MemorySessionTokens("restricted", "refresh-one", SessionScopeRaw.DELETION_ONLY)
        val result = client(tokens = tokens).execute(ApiCall("getMe"))

        val ended = result as ApiResult.SessionEnded
        assertEquals(ErrorEffectKind.CLEAR_SESSION_LOGIN, ended.effect.kind)
        assertEquals("Login", ended.effect.route)
        assertEquals(1, server.requestCount)
        assertEquals("/v1/auth/refresh", server.takeRequest().path)
        assertNull(tokens.accessToken)
    }

    @Test
    fun r109_barrierRefreshFailureDuringForceUpdateStaysOnForceUpdate() {
        state = FULL_SUPPORTED.copy(update = UpdateRaw.BELOW)
        server.enqueue(envelope(10404))
        val tokens = MemorySessionTokens("restricted", "refresh-one", SessionScopeRaw.DELETION_ONLY)
        val ended = client(tokens = tokens).execute(ApiCall("getDeletion")) as ApiResult.SessionEnded

        assertEquals(ErrorEffectKind.CLEAR_SESSION_STAY_FORCE_UPDATE, ended.effect.kind)
        assertEquals("ForceUpdate", ended.effect.route)
        assertEquals(1, server.requestCount)
    }

    // ---------- R1-10 ----------

    @Test
    fun r110_coldStartRestrictedSessionRefreshesBeforeRestrictedTableRequests() {
        val refreshArrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        val refreshes = AtomicInteger()
        server.dispatcher =
            dispatcher { request ->
                if (request.path == "/v1/auth/refresh") {
                    refreshes.incrementAndGet()
                    refreshArrived.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    refreshPair("full-access")
                } else {
                    assertEquals("Bearer full-access", request.getHeader(HeaderNames.AUTHORIZATION))
                    envelope(0, "{}")
                }
            }
        val tokens = MemorySessionTokens("restricted", "refresh-one", SessionScopeRaw.DELETION_ONLY)
        val api = client(tokens = tokens)
        val pool = Executors.newFixedThreadPool(3)
        // getMe、getDeletion 都在受限会话表里：照样等屏障
        val calls = listOf("getMe", "getDeletion", "getMe").map { id -> pool.submit<ApiResult> { api.execute(ApiCall(id)) } }
        assertTrue(refreshArrived.await(5, TimeUnit.SECONDS))
        Thread.sleep(200)
        assertEquals("other requests wait for the barrier", 1, server.requestCount)
        release.countDown()
        val results = calls.map { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()

        assertTrue(results.toString(), results.all { it is ApiResult.Success })
        assertEquals(1, refreshes.get())
        assertEquals(SessionScopeRaw.FULL, tokens.scope)
    }

    @Test
    fun r110_updateRecoveringToSupportedRefreshesRestrictedSessionFirst() {
        state = FULL_SUPPORTED.copy(update = UpdateRaw.BELOW)
        val tokens = MemorySessionTokens("restricted", "refresh-one", SessionScopeRaw.DELETION_ONLY)
        val api = client(tokens = tokens)
        // 冷启动屏障：仍低于最低版本，拿回受限会话
        server.enqueue(refreshPair("restricted-two", SessionScopeRaw.DELETION_ONLY))
        server.enqueue(envelope(0, "{}"))
        assertTrue(api.execute(ApiCall("getDeletion")) is ApiResult.Success)
        // 升级后强更检查结论为受支持：先刷新，再发 getMe
        state = FULL_SUPPORTED
        server.enqueue(refreshPair("full-access"))
        server.enqueue(envelope(0, "{}"))
        assertTrue(api.execute(ApiCall("getMe")) is ApiResult.Success)

        val paths = List(4) { server.takeRequest() }
        assertEquals(listOf("/v1/auth/refresh", "/v1/me/deletion", "/v1/auth/refresh", "/v1/me"), paths.map { it.path })
        assertEquals("Bearer full-access", paths[3].getHeader(HeaderNames.AUTHORIZATION))
        assertEquals(SessionScopeRaw.FULL, tokens.scope)
    }

    @Test
    fun r110_unknownToSupportedAlsoRefreshes() {
        state = FULL_SUPPORTED.copy(update = UpdateRaw.UNKNOWN)
        val tokens = MemorySessionTokens("full", "refresh-one", SessionScopeRaw.FULL)
        val api = client(tokens = tokens)
        server.enqueue(envelope(0, "{}"))
        assertTrue(api.execute(ApiCall("getMe")) is ApiResult.Success)
        tokens.save("restricted", "refresh-r", SessionScopeRaw.DELETION_ONLY)
        state = FULL_SUPPORTED
        server.enqueue(refreshPair("full-two"))
        server.enqueue(envelope(0, "{}"))
        assertTrue(api.execute(ApiCall("getMe")) is ApiResult.Success)
        server.takeRequest()
        assertEquals("/v1/auth/refresh", server.takeRequest().path)
        assertEquals("Bearer full-two", server.takeRequest().getHeader(HeaderNames.AUTHORIZATION))
    }

    // ---------- R1-11 ----------

    @Test
    fun r111_stepUpOperationWithoutSessionAccountIsRejectedLocally() {
        val pending = PendingKeyStore(MemoryPendingKeyBackend()) { 0L }
        val tokens = MemorySessionTokens("access", "refresh", SessionScopeRaw.FULL, userId = null)
        val result = client(tokens = tokens, pending = pending).execute(ApiCall("createWithdrawal", body = "{}", stepUpToken = "t"))

        assertEquals(ApiResult.LocalReject(LocalRejectReason.ACCOUNT_UNKNOWN), result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun r111_pendingRecordWriteFailureBlocksSending() {
        val pending = PendingKeyStore(FailingBackend()) { 0L }
        val result = client(pending = pending).execute(ApiCall("createWithdrawal", body = "{}", stepUpToken = "t"))

        assertEquals(ApiResult.LocalReject(LocalRejectReason.PENDING_STORE_FAILED), result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun r111_accountComesFromTheLoginResponseUserId() {
        server.enqueue(json(example("/v1/auth/login/sms", "post")))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val backend = MemoryPendingKeyBackend()
        val pending = PendingKeyStore(backend) { 0L }
        val api = client(tokens = MemorySessionTokens(), pending = pending)
        assertTrue(api.execute(ApiCall("loginBySms", body = "{}")) is ApiResult.Success)
        val failure =
            api.execute(
                ApiCall("createWithdrawal", body = """{"amount_cent":1}""", stepUpToken = "t"),
            ) as ApiResult.TransportFailure

        val account = "0199a3b4-5c6d-7e8f-9a0b-1c2d3e4f5a61"
        assertTrue(failure.pendingKeyUnknown)
        assertEquals(account, backend.read(account, checkNotNull(failure.idempotencyKey))?.account)
        // 换新键再提交被挡（未决键没有被绕过）
        assertEquals(
            ApiResult.LocalReject(LocalRejectReason.PENDING_KEY_UNRESOLVED),
            api.execute(ApiCall("createWithdrawal", body = """{"amount_cent":1}""", stepUpToken = "t")),
        )
        assertEquals(2, server.requestCount)
    }

    // ---------- R1-12 ----------

    @Test
    fun r112_stepUpRequiredAfterUnknownKeepsSameKeyReplayAndConfirmCarriesNewToken() {
        val pending = PendingKeyStore(MemoryPendingKeyBackend()) { 0L }
        val api = client(pending = pending)
        val body = """{"amount_cent":1000}"""
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val key =
            checkNotNull(
                (
                    api.execute(
                        ApiCall("createWithdrawal", body = body, stepUpToken = "expired"),
                    ) as ApiResult.TransportFailure
                ).idempotencyKey,
            )

        server.enqueue(envelope(10003))
        val stepUp = api.confirmPending("createWithdrawal") as ApiResult.Failure
        assertEquals(ErrorEffectKind.STEP_UP, stepUp.effect.kind)
        assertEquals(ReplayMode.SAME_KEY, stepUp.effect.replay)
        assertTrue(stepUp.effect.pendingKeyUnknown)
        assertFalse(stepUp.effect.registerPending)
        assertEquals(PendingKeyStore.UNKNOWN, pending.state(key).stateRaw)

        server.enqueue(envelope(0, """{"withdrawal_id":"w1"}"""))
        assertTrue(api.confirmPending("createWithdrawal", stepUpToken = "fresh-token") is ApiResult.Success)
        assertEquals(PendingKeyStore.DONE, pending.state(key).stateRaw)
        server.takeRequest()
        server.takeRequest()
        val resent = server.takeRequest()
        assertEquals(key, resent.getHeader(HeaderNames.IDEMPOTENCY_KEY))
        assertEquals("fresh-token", resent.getHeader(HeaderNames.STEP_UP_TOKEN))
        assertEquals(body, resent.body.readUtf8())
    }

    // ---------- R1-13 ----------

    @Test
    fun r113_locallyRejectedConfirmReturnsTheKeyToUnknown() {
        val pending = PendingKeyStore(MemoryPendingKeyBackend()) { 0L }
        val api = client(pending = pending)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val key =
            checkNotNull((api.execute(ApiCall("createWithdrawal", body = "{}")) as ApiResult.TransportFailure).idempotencyKey)

        state = FULL_SUPPORTED.copy(environmentSettled = false)
        assertEquals(ApiResult.LocalReject(LocalRejectReason.ENVIRONMENT_UNSETTLED), api.confirmPending("createWithdrawal"))
        assertEquals(PendingKeyStore.UNKNOWN, pending.state(key).stateRaw)

        state = FULL_SUPPORTED
        server.enqueue(envelope(40901))
        assertTrue(api.abandonPending("createWithdrawal") is ApiResult.Failure)
        assertEquals(PendingKeyStore.UNKNOWN, pending.state(key).stateRaw)
        server.enqueue(envelope(0, "{}"))
        assertTrue(api.confirmPending("createWithdrawal") is ApiResult.Success)
        assertEquals(PendingKeyStore.DONE, pending.state(key).stateRaw)
    }

    @Test
    fun r113_confirmWithoutRegisteredDeviceForSignedOperationReturnsToUnknown() {
        val pending = PendingKeyStore(MemoryPendingKeyBackend()) { 0L }
        val api = client(pending = pending)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val key = checkNotNull((api.execute(ApiCall("requestDeletion", body = "{}")) as ApiResult.TransportFailure).idempotencyKey)

        // 设备凭据已丢（重新注册前）：签名接口本地拒绝
        val unregistered = client(pending = pending, device = UnregisteredDevice)
        assertEquals(ApiResult.LocalReject(LocalRejectReason.DEVICE_NOT_READY), unregistered.confirmPending("requestDeletion"))
        assertEquals(PendingKeyStore.UNKNOWN, pending.state(key).stateRaw)
        assertEquals(1, server.requestCount)
    }

    // ---------- R1-14 ----------

    @Test
    fun r114_abandonBlockedByLoginOnlyGuidesAndKeepsTheKeyPending() {
        val cases = listOf(10001 to ErrorEffectKind.GUIDE_ONLY, 10004 to ErrorEffectKind.GUIDE_ONLY, 10005 to ErrorEffectKind.GUIDE_ONLY)
        cases.forEach { (code, kind) ->
            val pending = PendingKeyStore(MemoryPendingKeyBackend()) { 0L }
            val api = client(pending = pending)
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            val key =
                checkNotNull((api.execute(ApiCall("createWithdrawal", body = "{}")) as ApiResult.TransportFailure).idempotencyKey)
            val before = server.requestCount

            server.enqueue(envelope(code, """{"consent_type":"privacy"}"""))
            val failure = api.abandonPending("createWithdrawal") as ApiResult.Failure
            assertEquals("$code", kind, failure.effect.kind)
            assertFalse("$code", failure.effect.registerPending)
            assertEquals("$code", ReplayMode.NONE, failure.effect.replay)
            assertTrue("$code", failure.effect.pendingKeyUnknown)
            assertEquals("$code", PendingKeyStore.UNKNOWN, pending.state(key).stateRaw)
            assertEquals("$code: no automatic replay", before + 1, server.requestCount)
        }
    }

    // ---------- R1-15 ----------

    @Test
    fun r115_foregroundRestoreDoesNotUnlockARequestStillInFlight() {
        val confirmArrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sends = AtomicInteger()
        server.dispatcher =
            dispatcher { _ ->
                when (sends.incrementAndGet()) {
                    1 -> {
                        MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
                    }

                    else -> {
                        confirmArrived.countDown()
                        release.await(5, TimeUnit.SECONDS)
                        envelope(0, "{}")
                    }
                }
            }
        val pending = PendingKeyStore(MemoryPendingKeyBackend()) { 0L }
        val api = client(pending = pending)
        val key = checkNotNull((api.execute(ApiCall("createWithdrawal", body = "{}")) as ApiResult.TransportFailure).idempotencyKey)
        val pool = Executors.newSingleThreadExecutor()
        val confirming = pool.submit<ApiResult> { api.confirmPending("createWithdrawal") }
        assertTrue(confirmArrived.await(5, TimeUnit.SECONDS))

        // 同一进程切回前台：按约定恢复记录
        assertTrue(api.restorePending())
        assertEquals(PendingKeyStore.IN_FLIGHT, pending.state(key).stateRaw)
        assertEquals(ApiResult.LocalReject(LocalRejectReason.PENDING_KEY_UNRESOLVED), api.confirmPending("createWithdrawal"))
        assertEquals(ApiResult.LocalReject(LocalRejectReason.PENDING_KEY_UNRESOLVED), api.abandonPending("createWithdrawal"))

        release.countDown()
        assertTrue(confirming.get(10, TimeUnit.SECONDS) is ApiResult.Success)
        pool.shutdown()
        assertEquals(PendingKeyStore.DONE, pending.state(key).stateRaw)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun r115_processRestartStillTurnsLeftoverInFlightIntoUnknown() {
        val backend = MemoryPendingKeyBackend()
        val before = PendingKeyStore(backend) { 0L }
        before.coldStart("account-A")
        assertTrue(before.beforeSend("createWithdrawal", "key", "{}", "account-A"))
        before.coldStart("account-A")
        assertEquals(PendingKeyStore.IN_FLIGHT, before.state("key").stateRaw)

        val restarted = PendingKeyStore(backend) { 0L }
        restarted.coldStart("account-A")
        assertEquals(PendingKeyStore.UNKNOWN, restarted.state("key").stateRaw)
    }

    private fun dispatcher(respond: (RecordedRequest) -> MockResponse) =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = respond(request)
        }

    /** 契约快照 openapi 里某个操作 200 响应的示例（原样文本）。 */
    private fun example(
        path: String,
        method: String,
    ): String {
        val start = File(checkNotNull(System.getProperty("user.dir"))).canonicalFile
        val root = generateSequence(start) { it.parentFile }.first { File(it, "contract.lock").isFile }
        val openapi = Json.parseToJsonElement(File(root, "contracts/snapshot/contracts/openapi.json").readText()).jsonObject
        val example =
            openapi
                .getValue("paths")
                .jsonObject
                .getValue(path)
                .jsonObject
                .getValue(method)
                .jsonObject
                .getValue("responses")
                .jsonObject
                .getValue("200")
                .jsonObject
                .getValue("content")
                .jsonObject
                .getValue("application/json")
                .jsonObject
                .getValue("example")
        assertNotNull(example)
        assertEquals(JsonPrimitive(0), (example as JsonObject)["code"])
        return example.toString()
    }

    private companion object {
        val FULL_SUPPORTED =
            NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, update = UpdateRaw.SUPPORTED)
        val BASIC = NetworkContext(PrivacyRaw.BASIC, environmentSettled = true, credentialsReadable = true, update = UpdateRaw.UNKNOWN)
    }
}
