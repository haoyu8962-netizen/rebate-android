package com.couli.core.network

import com.couli.core.generated.ApiOperation
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ApiClient 对本地假服务（MockWebServer）的集成测试（任务书 §5 第 2 条）：并发 10002 单飞刷新并全部重放、读接口超时重试
 * 1 次而写接口不重试、基本模式与撤回清理未完成时不带凭据且非白名单本地拒绝、签名请求的 X-Sign 与共用向量一致、受限会话、
 * 未决键、10405 与刷新失败。响应外壳按契约 `{code, msg, data, trace_id}`。
 */
class ApiClientIntegrationTest {
    private lateinit var server: MockWebServer
    private val sleeps = mutableListOf<Long>()
    private val forceUpdates = mutableListOf<String?>()
    private val effects = mutableListOf<ErrorEffect>()

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private class FixedDevice(
        override val deviceId: String? = "device-fixture",
        override val installSecret: String? = "test-only.install.device-a",
    ) : DeviceCredentials

    /** 读到即失败：证明凭据不可读时根本不读。 */
    private object PoisonDevice : DeviceCredentials {
        override val deviceId: String? get() = error("device_id must not be read")
        override val installSecret: String? get() = error("install_secret must not be read")
    }

    private object PoisonTokens : SessionTokens {
        override val accessToken: String? get() = error("access_token must not be read")
        override val refreshToken: String? get() = error("refresh_token must not be read")
        override val scope: String? get() = error("scope must not be read")
        override val userId: String? get() = error("user_id must not be read")
        override val generation: Long get() = error("generation must not be read")

        override fun snapshot(): SessionSnapshot = error("session must not be read")

        override fun save(
            accessToken: String,
            refreshToken: String,
            scope: String,
            userId: String?,
        ) = error("must not save")

        override fun clear() = Unit

        override fun replaceIf(
            expectedGeneration: Long,
            accessToken: String,
            refreshToken: String,
            scope: String,
        ): Boolean = error("must not save")

        override fun clearIf(expectedGeneration: Long): Boolean = false
    }

    @Suppress("LongParameterList")
    private fun client(
        tokens: SessionTokens = MemorySessionTokens("old-access", "refresh-one", SessionScopeRaw.FULL, userId = "account-A"),
        device: DeviceCredentials = FixedDevice(),
        context: NetworkContext =
            NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, update = UpdateRaw.SUPPORTED),
        pending: PendingKeyStore = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
        clock: () -> Long = { 1_790_661_600_000L },
        nonce: () -> String = { "b5a8f07b9dda63c3f78ed24c341d25b3" },
        retryPolicy: (ApiOperation) -> RetryDecision = RetryPolicy::forOperation,
    ) = ApiClient(
        identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.2.0", build = "1", channel = "official"),
        baseUrl = { server.url("/").toString() },
        context = { context },
        device = device,
        tokens = tokens,
        pendingKeys = pending,
        hooks = NetworkHooks(onForceUpdate = { forceUpdates += it }, onEffect = { effects += it }),
        clock = clock,
        sleeper = { sleeps += it },
        nonce = nonce,
        retryPolicy = retryPolicy,
    )

    private fun envelope(
        code: Int,
        data: String = "null",
    ) = MockResponse()
        .setHeader(
            "Content-Type",
            "application/json",
        ).setBody("""{"code":$code,"msg":"","data":$data,"trace_id":"trace-0123456789"}""")

    private fun tokenPair(
        access: String,
        scope: String = SessionScopeRaw.FULL,
    ) = envelope(
        0,
        """{"access_token":"$access","access_expires_at":"2026-10-02T13:30:00+08:00","refresh_token":"refresh-two",""" +
            """"refresh_expires_at":"2026-11-01T11:30:00+08:00","session_scope":"$scope"}""",
    )

    @Test
    fun fiveConcurrent10002ShareOneRefreshAndAllReplay() {
        val arrivedWithOldToken = CountDownLatch(5)
        val refreshes = AtomicInteger()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when (request.path) {
                        "/v1/auth/refresh" -> {
                            refreshes.incrementAndGet()
                            assertEquals("""{"refresh_token":"refresh-one"}""", request.body.readUtf8())
                            assertNotNull(request.getHeader(HeaderNames.SIGN))
                            tokenPair("new-access").setBodyDelay(300, TimeUnit.MILLISECONDS)
                        }

                        "/v1/me" -> {
                            if (request.getHeader(HeaderNames.AUTHORIZATION) == "Bearer old-access") {
                                arrivedWithOldToken.countDown()
                                arrivedWithOldToken.await(5, TimeUnit.SECONDS)
                                envelope(10002)
                            } else {
                                assertEquals("Bearer new-access", request.getHeader(HeaderNames.AUTHORIZATION))
                                envelope(0, """{"nickname":"fixture"}""")
                            }
                        }

                        else -> {
                            envelope(50001)
                        }
                    }
            }
        val tokens = MemorySessionTokens("old-access", "refresh-one", SessionScopeRaw.FULL)
        val api = client(tokens = tokens)
        val pool = Executors.newFixedThreadPool(5)
        val results = (1..5).map { pool.submit<ApiResult> { api.execute(ApiCall("getMe")) } }.map { it.get(20, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1, refreshes.get())
        assertEquals(1, api.refreshCoordinator.refreshCalls)
        assertTrue(results.toString(), results.all { it is ApiResult.Success })
        assertEquals("new-access", tokens.accessToken)
        assertEquals("refresh-two", tokens.refreshToken)
        assertEquals(11, server.requestCount)
    }

    @Test
    fun refreshFailureClearsSessionAndGuidesToLogin() {
        server.enqueue(envelope(10002))
        server.enqueue(envelope(10404))
        val tokens = MemorySessionTokens("old-access", "refresh-one", SessionScopeRaw.FULL)
        val result = client(tokens = tokens).execute(ApiCall("getMe"))

        val failure = result as ApiResult.Failure
        assertEquals(ErrorEffectKind.CLEAR_SESSION_LOGIN, failure.effect.kind)
        assertEquals("Login", failure.effect.route)
        assertNull(tokens.accessToken)
        assertNull(tokens.refreshToken)
    }

    @Test
    fun refreshFailureDuringForceUpdateStaysOnForceUpdate() {
        server.enqueue(envelope(10002))
        server.enqueue(envelope(10404))
        val tokens = MemorySessionTokens("old-access", "refresh-one", SessionScopeRaw.FULL)
        val below = NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, update = UpdateRaw.BELOW)
        val failure = client(tokens = tokens, context = below).execute(ApiCall("getDeletion")) as ApiResult.Failure
        assertEquals(ErrorEffectKind.CLEAR_SESSION_STAY_FORCE_UPDATE, failure.effect.kind)
        assertNull(tokens.accessToken)
    }

    @Test
    fun readTimeoutRetriesOnceAfterBackoff() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(envelope(0, "{}"))
        val fast = { operation: ApiOperation -> RetryPolicy.forOperation(operation).copy(timeoutMillis = 300) }
        val result = client(retryPolicy = fast).execute(ApiCall("getMe"))

        assertTrue(result.toString(), result is ApiResult.Success)
        assertEquals(2, server.requestCount)
        assertEquals(listOf(RetryPolicy.READ_BACKOFF_MILLIS), sleeps)
    }

    @Test
    fun readDisconnectRetriesOnce() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(envelope(0, "{}"))
        val result = client().execute(ApiCall("searchProducts", query = "q=tissue"))
        assertTrue(result.toString(), result is ApiResult.Success)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun writeTimeoutIsNotRetried() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(envelope(0, "{}"))
        val fast = { operation: ApiOperation -> RetryPolicy.forOperation(operation).copy(timeoutMillis = 300) }
        val result = client(retryPolicy = fast).execute(ApiCall("dismissPendingTrack", pathParams = mapOf("link_id" to "abc")))

        assertEquals(HttpTransport.KIND_TIMEOUT, (result as ApiResult.TransportFailure).kind)
        assertEquals(1, server.requestCount)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun basicModeStripsCredentialsAndRejectsNonWhitelistLocally() {
        server.enqueue(envelope(0, "{}"))
        val basic = NetworkContext(PrivacyRaw.BASIC, environmentSettled = true, credentialsReadable = true, update = UpdateRaw.UNKNOWN)
        val api = client(tokens = PoisonTokens, device = PoisonDevice, context = basic)

        assertTrue(api.execute(ApiCall("getConfig")) is ApiResult.Success)
        val sent = server.takeRequest()
        listOf(HeaderNames.AUTHORIZATION, HeaderNames.DEVICE_ID, HeaderNames.TIMESTAMP, HeaderNames.NONCE, HeaderNames.SIGN).forEach {
            assertNull("basic mode leaked $it", sent.getHeader(it))
        }
        HeaderNames.ANONYMOUS.forEach { assertNotNull(it, sent.getHeader(it)) }

        listOf("listOrders", "checkAppVersion", "registerDevice").forEach {
            assertEquals(ApiResult.LocalReject(LocalRejectReason.BASIC_MODE_DENIED), api.execute(ApiCall(it)))
        }
        assertEquals(
            ApiResult.LocalReject(LocalRejectReason.BASIC_MODE_DENIED),
            api.execute(ApiCall("openLink", mapOf("link_id" to "abc"))),
        )
        assertEquals(1, server.requestCount)
    }

    @Test
    fun unfinishedWithdrawalCleanupNeverReadsCredentials() {
        server.enqueue(envelope(0, "{}"))
        val unreadable =
            NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = false, update = UpdateRaw.SUPPORTED)
        val api = client(tokens = PoisonTokens, device = PoisonDevice, context = unreadable)
        assertTrue(api.execute(ApiCall("getConfig")) is ApiResult.Success)
        assertNull(server.takeRequest().getHeader(HeaderNames.AUTHORIZATION))
        // R1-02：与基本模式同一个匿名白名单
        assertEquals(
            ApiResult.LocalReject(LocalRejectReason.BASIC_MODE_DENIED),
            api.execute(ApiCall("openLink", mapOf("link_id" to "abc"))),
        )
        assertEquals(1, server.requestCount)
    }

    @Test
    fun environmentNotSettledSendsNothing() {
        val blocked = NetworkContext(PrivacyRaw.FULL, environmentSettled = false, credentialsReadable = true, update = UpdateRaw.SUPPORTED)
        assertEquals(
            ApiResult.LocalReject(LocalRejectReason.ENVIRONMENT_UNSETTLED),
            client(context = blocked).execute(ApiCall("getConfig")),
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun signedRequestMatchesSharedVector() {
        val case = signVector(0)
        server.enqueue(envelope(0, "{}"))
        val api =
            client(
                clock = { case.text("timestamp").toLong() * 1000 },
                nonce = { case.text("nonce") },
                device = FixedDevice(installSecret = case.text("install_secret")),
            )
        val result =
            api.execute(
                ApiCall("openLink", pathParams = mapOf("link_id" to "abc"), query = "src=agent", body = case.text("body_utf8")),
            )

        assertTrue(result.toString(), result is ApiResult.Success)
        val sent = server.takeRequest()
        assertEquals(case.text("path"), sent.path)
        assertEquals(case.text("timestamp"), sent.getHeader(HeaderNames.TIMESTAMP))
        assertEquals(case.text("nonce"), sent.getHeader(HeaderNames.NONCE))
        assertEquals(case.text("expected_sign"), sent.getHeader(HeaderNames.SIGN))
        assertEquals(case.text("body_utf8"), sent.body.readUtf8())
        assertNotNull(sent.getHeader(HeaderNames.IDEMPOTENCY_KEY))
        assertEquals("Bearer old-access", sent.getHeader(HeaderNames.AUTHORIZATION))
        assertEquals("device-fixture", sent.getHeader(HeaderNames.DEVICE_ID))
    }

    @Test
    fun restrictedSessionOnlySendsTheRestrictedTable() {
        // 冷启动屏障（R1-10）：存储的会话是 deletion_only，先刷新一次；强更状态下仍拿回 deletion_only
        server.enqueue(tokenPair("restricted-two", SessionScopeRaw.DELETION_ONLY))
        server.enqueue(envelope(0, "{}"))
        val tokens = MemorySessionTokens("restricted", "refresh-one", SessionScopeRaw.DELETION_ONLY)
        val below = NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, update = UpdateRaw.BELOW)
        val api = client(tokens = tokens, context = below)

        assertEquals(ApiResult.LocalReject(LocalRejectReason.SESSION_SCOPE_DENIED), api.execute(ApiCall("listOrders")))
        assertEquals(ApiResult.LocalReject(LocalRejectReason.SESSION_SCOPE_DENIED), api.execute(ApiCall("reportPushToken")))
        // 按请求体才接受：只有 action=account_deletion 的二次验证
        val withdrawStepUp = """{"purpose":"step_up","action":"withdraw","phone":"13800000000"}"""
        assertEquals(
            ApiResult.LocalReject(LocalRejectReason.SESSION_SCOPE_DENIED),
            api.execute(ApiCall("sendSmsCode", body = withdrawStepUp)),
        )
        assertTrue(api.execute(ApiCall("getDeletion")) is ApiResult.Success)
        assertEquals("/v1/auth/refresh", server.takeRequest().path)
        assertEquals("Bearer restricted-two", server.takeRequest().getHeader(HeaderNames.AUTHORIZATION))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun restrictedTokenAfterUpgradeRefreshesBeforeOtherRequests() {
        server.enqueue(tokenPair("full-access"))
        server.enqueue(envelope(0, "{}"))
        val tokens = MemorySessionTokens("restricted", "refresh-one", SessionScopeRaw.DELETION_ONLY)
        val result = client(tokens = tokens).execute(ApiCall("listOrders"))

        assertTrue(result.toString(), result is ApiResult.Success)
        assertEquals("/v1/auth/refresh", server.takeRequest().path)
        assertEquals("Bearer full-access", server.takeRequest().getHeader(HeaderNames.AUTHORIZATION))
        assertEquals(SessionScopeRaw.FULL, tokens.scope)
    }

    @Test
    fun loginReturningDeletionOnlyWhileUpdateUnknownEntersForceUpdate() {
        server.enqueue(
            envelope(
                0,
                """{"user_id":"user-1","is_new_user":false,"tokens":{"access_token":"restricted",""" +
                    """"access_expires_at":"2026-10-02T11:30:00+08:00","refresh_token":"refresh-r",""" +
                    """"refresh_expires_at":"2026-11-01T09:30:00+08:00","session_scope":"deletion_only"}}""",
            ),
        )
        val tokens = MemorySessionTokens()
        val unknown = NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, update = UpdateRaw.UNKNOWN)
        val result =
            client(
                tokens = tokens,
                context = unknown,
            ).execute(ApiCall("loginBySms", body = """{"phone":"13800000000","code":"123456"}"""))

        assertTrue(result is ApiResult.Success)
        assertEquals(SessionScopeRaw.DELETION_ONLY, tokens.scope)
        assertEquals(listOf<String?>(null), forceUpdates)
    }

    @Test
    fun forceUpdateCallsUpdateAndDoesNotReplay() {
        server.enqueue(envelope(10405, """{"min_supported_version":"1.3.0"}"""))
        val failure = client().execute(ApiCall("openLink", mapOf("link_id" to "abc"), body = "{}")) as ApiResult.Failure
        assertEquals(ErrorEffectKind.FORCE_UPDATE, failure.effect.kind)
        assertEquals(listOf<String?>("1.3.0"), forceUpdates)
        assertEquals(1, server.requestCount)
        assertEquals(listOf(failure.effect), effects)
    }

    @Test
    fun unknownStepUpKeyBlocksNewSubmissionAndConfirmReusesKeyAndBody() {
        val pending = PendingKeyStore(MemoryPendingKeyBackend()) { 0L }
        pending.coldStart("account-A")
        val api = client(pending = pending)
        val body = """{"amount_cent":1000}"""
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val first =
            api.execute(
                ApiCall("createWithdrawal", body = body, stepUpToken = "step-up"),
            ) as ApiResult.TransportFailure
        assertTrue(first.pendingKeyUnknown)
        val key = checkNotNull(first.idempotencyKey)
        assertEquals(PendingKeyStore.UNKNOWN, pending.state(key).stateRaw)

        // 结果未知期间新的提交（新键）本地拒绝
        assertEquals(
            ApiResult.LocalReject(LocalRejectReason.PENDING_KEY_UNRESOLVED),
            api.execute(ApiCall("createWithdrawal", body = """{"amount_cent":2000}""")),
        )

        // 10001 等前置码不结束未知键，Effect 不换新键
        server.enqueue(envelope(10001))
        val guided = api.confirmPending("createWithdrawal") as ApiResult.Failure
        assertTrue(guided.effect.pendingKeyUnknown)
        assertEquals(ReplayMode.NONE, guided.effect.replay)
        assertEquals(PendingKeyStore.UNKNOWN, pending.state(key).stateRaw)

        server.enqueue(envelope(0, """{"withdrawal_id":"w1"}"""))
        assertTrue(api.confirmPending("createWithdrawal") is ApiResult.Success)
        assertEquals(PendingKeyStore.DONE, pending.state(key).stateRaw)

        server.takeRequest()
        repeat(2) {
            val resent = server.takeRequest()
            assertEquals(key, resent.getHeader(HeaderNames.IDEMPOTENCY_KEY))
            assertEquals(body, resent.body.readUtf8())
        }
    }

    @Test
    fun abandonCallsTheAbandonEndpointAndEndsTheKeyOnlyWhenAbandoned() {
        val pending = PendingKeyStore(MemoryPendingKeyBackend()) { 0L }
        pending.coldStart("account-A")
        val api = client(pending = pending)
        server.enqueue(envelope(0, "{}").setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val fastApi = client(pending = pending, retryPolicy = { RetryPolicy.forOperation(it).copy(timeoutMillis = 300) })
        val key =
            checkNotNull(
                (
                    fastApi.execute(
                        ApiCall("requestDeletion", body = "{}"),
                    ) as ApiResult.TransportFailure
                ).idempotencyKey,
            )

        server.enqueue(envelope(40901))
        api.abandonPending("requestDeletion")
        assertEquals(PendingKeyStore.UNKNOWN, pending.state(key).stateRaw)

        server.enqueue(envelope(0, """{"outcome":"abandoned","original":null}"""))
        assertEquals(ApiResult.Abandoned(key, TEST_TRACE_ID), api.abandonPending("requestDeletion"))
        assertEquals(PendingKeyStore.DONE, pending.state(key).stateRaw)

        server.takeRequest()
        val abandon = server.takeRequest()
        assertEquals("/v1/idempotency-keys/abandon", abandon.path)
        assertNull(abandon.getHeader(HeaderNames.IDEMPOTENCY_KEY))
        val sentBody = Json.parseToJsonElement(abandon.body.readUtf8()).jsonObject
        assertEquals("account_deletion", sentBody.text("action"))
        assertEquals(key, sentBody.text("idempotency_key"))
    }

    @Test
    fun invalidSignatureWithSkewedClockResignsOnce() {
        server.enqueue(envelope(10401).setHeader("Date", "Wed, 30 Sep 2026 06:00:00 GMT"))
        server.enqueue(envelope(0, "{}"))
        val nonces = ArrayDeque(listOf("00000000000000000000000000000001", "00000000000000000000000000000002"))
        val api = client(clock = { 0L }, nonce = { nonces.removeFirst() })
        assertTrue(api.execute(ApiCall("openLink", mapOf("link_id" to "abc"), body = "{}")) is ApiResult.Success)
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("0", first.getHeader(HeaderNames.TIMESTAMP))
        assertFalse(second.getHeader(HeaderNames.TIMESTAMP) == "0")
        assertEquals(first.getHeader(HeaderNames.IDEMPOTENCY_KEY), second.getHeader(HeaderNames.IDEMPOTENCY_KEY))
        assertFalse(first.getHeader(HeaderNames.NONCE) == second.getHeader(HeaderNames.NONCE))
    }

    @Test
    fun gatewayErrorPageIsNoEnvelope() {
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>bad gateway</html>"))
        val result = client().execute(ApiCall("createWithdrawal", body = "{}")) as ApiResult.TransportFailure
        assertEquals(HttpTransport.KIND_NO_ENVELOPE, result.kind)
        assertEquals(502, result.httpStatus)
        assertEquals(1, server.requestCount)
    }

    private fun signVector(index: Int): JsonObject {
        val start = File(checkNotNull(System.getProperty("user.dir"))).canonicalFile
        val root = generateSequence(start) { it.parentFile }.first { File(it, "contract.lock").isFile }
        val all = Json.parseToJsonElement(File(root, "contracts/snapshot/specs/request-sign.vectors.json").readText()).jsonObject
        return all.getValue("valid_cases").jsonArray[index].jsonObject
    }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
}

private const val TEST_TRACE_ID = "trace-0123456789"
