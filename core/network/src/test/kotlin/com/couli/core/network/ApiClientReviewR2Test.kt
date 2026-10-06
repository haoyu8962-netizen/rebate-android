package com.couli.core.network

import com.couli.core.generated.ApiOperation
import com.couli.core.generated.ApiOperations
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * APP-03a Android 第 2 轮评审（android-review-r2.md R2-01..R2-08）、编排者裁定（30144）与 cross-platform-r1-lessons.md
 * 第 30–34 条的复现测试：按各条的失败场景对本地假服务（MockWebServer）发请求。另按「调用归属」统一机制，为每条自动续发路径
 * （GET 重试、10002 重放、10402 重放、10401 校时重签、确认未决）各补一个跨账号或撤回的用例。
 */
@Suppress("LargeClass") // 8 条评审项、裁定与 5 条共性问题各自的复现场景
class ApiClientReviewR2Test {
    private lateinit var server: MockWebServer
    private val forceUpdates = mutableListOf<String?>()
    private val effects = mutableListOf<ErrorEffect>()
    private val pool = Executors.newCachedThreadPool()

    @Volatile
    private var state = FULL_SUPPORTED

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        pool.shutdownNow()
        server.shutdown()
    }

    private class FixedDevice : DeviceCredentials {
        override val deviceId: String? = "device-fixture"
        override val installSecret: String? = "test-only.install.device-a"
    }

    private fun tokensA(scope: String = SessionScopeRaw.FULL) = MemorySessionTokens("access-A", "refresh-A", scope, userId = "account-A")

    /** A 退出、B 登录。 */
    private fun SessionTokens.switchToB() {
        clear()
        save("access-B", "refresh-B", SessionScopeRaw.FULL, "account-B")
    }

    @Suppress("LongParameterList")
    private fun client(
        tokens: SessionTokens = tokensA(),
        pending: PendingKeyStore = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
        hooks: NetworkHooks = NetworkHooks(onForceUpdate = { forceUpdates += it }, onEffect = { effects += it }),
        sleeper: (Long) -> Unit = {},
        retryPolicy: (ApiOperation) -> RetryDecision = { RetryPolicy.forOperation(it).copy(timeoutMillis = 2_000) },
        baseUrl: () -> String? = { server.url("/").toString() },
        http: OkHttpClient = OkHttpClient(),
        clock: () -> Long = System::currentTimeMillis,
    ) = ApiClient(
        identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.2.0", build = "1", channel = "official"),
        baseUrl = baseUrl,
        context = { state },
        device = FixedDevice(),
        tokens = tokens,
        pendingKeys = pending,
        hooks = hooks,
        http = http,
        clock = clock,
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

    private fun pair(
        access: String,
        scope: String = SessionScopeRaw.FULL,
    ) = """{"access_token":"$access","access_expires_at":"2026-10-02T13:30:00+08:00","refresh_token":"refresh-$access",""" +
        """"refresh_expires_at":"2026-11-01T11:30:00+08:00","session_scope":"$scope"}"""

    private fun refreshPair(
        access: String,
        scope: String = SessionScopeRaw.FULL,
    ) = envelope(0, pair(access, scope))

    private fun loginResponse(
        access: String,
        scope: String = SessionScopeRaw.FULL,
    ) = envelope(0, """{"user_id":"account-L","tokens":${pair(access, scope)}}""")

    private fun dispatcher(respond: (RecordedRequest) -> MockResponse) =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = respond(request)
        }

    private fun requests(): List<RecordedRequest> = List(server.requestCount) { server.takeRequest(1, TimeUnit.SECONDS)!! }

    private fun <T> Future<T>.result(): T = get(10, TimeUnit.SECONDS)

    private fun await(latch: CountDownLatch) = assertTrue("timed out", latch.await(5, TimeUnit.SECONDS))

    /** 首次提交 createWithdrawal 断连 → 结果未知，返回键（之后的请求交给 [then]）。 */
    private fun unknownWithdrawal(
        api: ApiClient,
        then: (RecordedRequest) -> MockResponse,
    ): String {
        val first = AtomicBoolean(true)
        server.dispatcher =
            dispatcher { request ->
                if (first.getAndSet(false)) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START) else then(request)
            }
        val failure = api.execute(ApiCall("createWithdrawal", body = """{"amount_cent":1000}""")) as ApiResult.TransportFailure
        assertTrue(failure.pendingKeyUnknown)
        return checkNotNull(failure.idempotencyKey)
    }

    // ---------- R2-01：撤回同意 / 切换环境立即取消在途请求；写出前再核对 ----------

    @Test
    fun r201_withdrawalWhileWaitingForTheConnectionCancelsTheCallBeforeAnythingIsWritten() {
        val connecting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slowConnect =
            OkHttpClient
                .Builder()
                .eventListener(
                    object : EventListener() {
                        override fun connectStart(
                            call: Call,
                            inetSocketAddress: InetSocketAddress,
                            proxy: Proxy,
                        ) {
                            connecting.countDown()
                            release.await(5, TimeUnit.SECONDS)
                        }
                    },
                ).build()
        server.dispatcher = dispatcher { envelope(0, "{}") }
        val tokens = tokensA()
        val api = client(tokens = tokens, http = slowConnect)
        val call = pool.submit<ApiResult> { api.execute(ApiCall("getMe")) }
        await(connecting)
        // 撤回同意：清理的第一步作废并取消在途请求，再清令牌
        api.onPrivacyRevoked()
        tokens.clear()
        state = BASIC
        release.countDown()

        assertEquals(ApiResult.LocalReject(LocalRejectReason.CALL_CANCELLED), call.result())
        assertEquals("nothing written to the server", 0, server.requestCount)
        assertEquals(0, api.inFlight.size)
    }

    @Test
    fun r201_environmentSwitchWhileWaitingForTheResponseCancelsWithoutRetry() {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher =
            dispatcher {
                arrived.countDown()
                release.await(5, TimeUnit.SECONDS)
                envelope(0, "{}")
            }
        val api = client()
        val call = pool.submit<ApiResult> { api.execute(ApiCall("getMe")) }
        await(arrived)
        val started = System.nanoTime()
        api.onEnvironmentSwitching()
        val result = call.result()
        release.countDown()

        assertEquals(HttpTransport.KIND_CANCELLED, (result as ApiResult.TransportFailure).kind)
        assertTrue("cancelled at once", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_500)
        assertEquals("no automatic retry after cancel", 1, server.requestCount)
    }

    @Test
    fun r201_networkInterceptorRechecksOwnershipAfterConnectingAndBeforeWriting() {
        val connected = CountDownLatch(1)
        val release = CountDownLatch(1)
        // 测试的网络拦截器排在 ApiClient 的之前：连接已建立、请求还没写出时停住
        val holdAfterConnect =
            OkHttpClient
                .Builder()
                .addNetworkInterceptor(
                    Interceptor { chain ->
                        connected.countDown()
                        release.await(5, TimeUnit.SECONDS)
                        chain.proceed(chain.request())
                    },
                ).build()
        server.dispatcher = dispatcher { envelope(0, "{}") }
        val tokens = tokensA()
        val api = client(tokens = tokens, http = holdAfterConnect)
        val call = pool.submit<ApiResult> { api.execute(ApiCall("getMe")) }
        await(connected)
        tokens.switchToB()
        release.countDown()

        assertEquals(ApiResult.LocalReject(LocalRejectReason.SESSION_CHANGED), call.result())
        assertEquals("A's prepared request with A's token is never written", 0, server.requestCount)
    }

    // ---------- R2-02：迟到的登录响应 ----------

    @Test
    fun r202_lateLoginResponseDoesNotOverwriteAnotherAccountsNewSession() {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher =
            dispatcher {
                arrived.countDown()
                release.await(5, TimeUnit.SECONDS)
                loginResponse("access-late")
            }
        val tokens = MemorySessionTokens()
        val api = client(tokens = tokens)
        val call = pool.submit<ApiResult> { api.execute(ApiCall("loginBySms", body = """{"phone":"13800000000","code":"1"}""")) }
        await(arrived)
        tokens.save("access-B", "refresh-B", SessionScopeRaw.FULL, "account-B")
        release.countDown()

        // 迟到的登录响应不保存，也不算登录成功（lessons 40）
        assertEquals(ApiResult.SessionNotSaved(LocalRejectReason.SESSION_CHANGED, "trace-0123456789"), call.result())
        assertEquals("access-B", tokens.accessToken)
        assertEquals("account-B", tokens.userId)
    }

    @Test
    fun r202_lateLoginResponseFromTheOldEnvironmentIsDiscarded() {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher =
            dispatcher {
                arrived.countDown()
                release.await(5, TimeUnit.SECONDS)
                loginResponse("access-old-env")
            }
        val base = AtomicReference(server.url("/").toString())
        val tokens = MemorySessionTokens()
        val api = client(tokens = tokens, baseUrl = { base.get() })
        val call = pool.submit<ApiResult> { api.execute(ApiCall("loginBySms", body = """{"phone":"13800000000","code":"1"}""")) }
        await(arrived)
        // 环境已切到另一个基址（会话本来就是空的：只靠环境核对丢弃）
        base.set("http://127.0.0.1:9/")
        release.countDown()

        assertEquals(ApiResult.SessionNotSaved(LocalRejectReason.CALL_CANCELLED, "trace-0123456789"), call.result())
        assertNull(tokens.accessToken)
    }

    // ---------- R2-03：刷新对象在入队时固定 ----------

    /** 刷新线程第一次读会话时，账号 B 恰好登录（模拟「刷新线程尚未执行时 B 登录」）。 */
    private class LoginOnRefreshThread(
        val inner: MemorySessionTokens,
    ) : SessionTokens by inner {
        private val fired = AtomicBoolean(false)

        override fun snapshot(): SessionSnapshot {
            if (Thread.currentThread().name == "couli-token-refresh" && !fired.getAndSet(true)) inner.switchToBInner()
            return inner.snapshot()
        }

        private fun MemorySessionTokens.switchToBInner() {
            clear()
            save("access-B", "refresh-B", SessionScopeRaw.FULL, "account-B")
        }
    }

    @Test
    fun r203_refreshQueuedForAccountANeverUsesOrClearsAccountBsSession() {
        server.dispatcher =
            dispatcher { request ->
                when {
                    request.path == "/v1/auth/refresh" -> refreshPair("access-A2")
                    request.getHeader(HeaderNames.AUTHORIZATION) == "Bearer access-A" -> envelope(10002)
                    else -> envelope(0, "{}")
                }
            }
        val tokens = LoginOnRefreshThread(tokensA())
        val api = client(tokens = tokens)
        val failure = api.execute(ApiCall("getMe")) as ApiResult.Failure

        val sent = requests()
        assertTrue("refresh was never sent with B's refresh token", sent.none { it.path == "/v1/auth/refresh" })
        assertTrue(sent.none { it.getHeader(HeaderNames.AUTHORIZATION) == "Bearer access-B" })
        assertEquals("access-B", tokens.accessToken)
        assertEquals("account-B", tokens.userId)
        assertEquals(ErrorEffectKind.GENERIC_ERROR, failure.effect.kind)
    }

    // ---------- R2-04 与「调用归属」：GET 自动重试 ----------

    @Test
    fun r204_readRetryAfterAccountSwitchDuringBackoffEndsTheOldCall() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(envelope(0, "{}"))
        val tokens = tokensA()
        val fast = { operation: ApiOperation -> RetryPolicy.forOperation(operation).copy(timeoutMillis = 300) }
        val api = client(tokens = tokens, retryPolicy = fast, sleeper = { tokens.switchToB() })

        assertEquals(ApiResult.LocalReject(LocalRejectReason.SESSION_CHANGED), api.execute(ApiCall("getMe", query = "order=a-1")))
        assertEquals("A's request is not resent with B's token", 1, server.requestCount)
    }

    @Test
    fun ownership_readRetryAfterWithdrawalDuringBackoffIsCancelled() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(envelope(0, "{}"))
        val fast = { operation: ApiOperation -> RetryPolicy.forOperation(operation).copy(timeoutMillis = 300) }
        lateinit var api: ApiClient
        api = client(retryPolicy = fast, sleeper = { api.onPrivacyRevoked() })

        // getConfig 在匿名白名单里：世代已变，照样不再续发
        assertEquals(ApiResult.LocalReject(LocalRejectReason.CALL_CANCELLED), api.execute(ApiCall("getConfig")))
        assertEquals(1, server.requestCount)
    }

    // ---------- 「调用归属」：10002 重放、10402 重放、10401 校时重签 ----------

    @Test
    fun ownership_withdrawalWhileWaitingForTheRefreshCancelsTheReplay() {
        val refreshArrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher =
            dispatcher { request ->
                if (request.path == "/v1/auth/refresh") {
                    refreshArrived.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    refreshPair("access-A2")
                } else {
                    envelope(10002)
                }
            }
        val tokens = tokensA()
        val api = client(tokens = tokens)
        val call = pool.submit<ApiResult> { api.execute(ApiCall("getMe")) }
        await(refreshArrived)
        api.onPrivacyRevoked()
        val result = call.result()
        release.countDown()

        assertEquals(ApiResult.LocalReject(LocalRejectReason.CALL_CANCELLED), result)
        assertEquals("original + refresh only; no replay", 2, server.requestCount)
        assertEquals("the cancelled refresh does not clear the session on its own", "access-A", tokens.accessToken)
        assertTrue(effects.none { it.kind == ErrorEffectKind.CLEAR_SESSION_LOGIN })
    }

    @Test
    fun ownership_deviceReRegistrationReplayIsNotSentForAnotherAccount() {
        server.enqueue(envelope(10402))
        server.enqueue(envelope(0, "{}"))
        val tokens = tokensA()
        val hooks =
            NetworkHooks(
                onEffect = { effects += it },
                reRegisterDevice = {
                    tokens.switchToB()
                    true
                },
            )
        val api = client(tokens = tokens, hooks = hooks)

        assertEquals(ApiResult.LocalReject(LocalRejectReason.SESSION_CHANGED), api.execute(ApiCall("getMe")))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun ownership_clockResignIsNotSentForAnotherAccount() {
        val tokens = tokensA()
        server.dispatcher =
            dispatcher {
                tokens.switchToB()
                envelope(10401).setHeader("Date", "Wed, 30 Sep 2026 06:00:00 GMT")
            }
        val api = client(tokens = tokens, clock = { 0L })

        val result = api.execute(ApiCall("openLink", mapOf("link_id" to "abc"), body = "{}"))
        assertEquals(ApiResult.LocalReject(LocalRejectReason.SESSION_CHANGED), result)
        assertEquals(1, server.requestCount)
    }

    // ---------- R2-05 与「调用归属」：未决键在整次逻辑调用期间保持占用 ----------

    @Test
    fun r205_pendingKeyStaysOccupiedWhileTheConfirmWaitsForTheRefresh() {
        val refreshArrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        val backend = MemoryPendingKeyBackend()
        val pending = PendingKeyStore(backend) { 0L }
        val api = client(pending = pending)
        val key =
            unknownWithdrawal(api) { request ->
                when {
                    request.path == "/v1/auth/refresh" -> {
                        refreshArrived.countDown()
                        release.await(5, TimeUnit.SECONDS)
                        refreshPair("access-A2")
                    }

                    request.getHeader(HeaderNames.AUTHORIZATION) == "Bearer access-A" -> {
                        envelope(10002)
                    }

                    else -> {
                        envelope(0, "{}")
                    }
                }
            }
        val confirming = pool.submit<ApiResult> { api.confirmPending("createWithdrawal") }
        await(refreshArrived)

        // 刷新期间：键仍在途，确认、放弃、新提交都不能开始
        assertEquals(PendingKeyStore.IN_FLIGHT, pending.state(key).stateRaw)
        assertEquals(ApiResult.LocalReject(LocalRejectReason.PENDING_KEY_UNRESOLVED), api.abandonPending("createWithdrawal"))
        assertEquals(ApiResult.LocalReject(LocalRejectReason.PENDING_KEY_UNRESOLVED), api.confirmPending("createWithdrawal"))
        assertEquals(
            ApiResult.LocalReject(LocalRejectReason.PENDING_KEY_UNRESOLVED),
            api.execute(ApiCall("createWithdrawal", body = """{"amount_cent":1}""")),
        )
        release.countDown()

        assertTrue(confirming.result() is ApiResult.Success)
        assertEquals(PendingKeyStore.DONE, pending.state(key).stateRaw)
        assertTrue("no abandon request went out", requests().none { it.path == "/v1/idempotency-keys/abandon" })
    }

    @Test
    fun r205_firstSubmissionKeepsItsRecordWhileRefreshing() {
        val refreshArrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher =
            dispatcher { request ->
                when {
                    request.path == "/v1/auth/refresh" -> {
                        refreshArrived.countDown()
                        release.await(5, TimeUnit.SECONDS)
                        refreshPair("access-A2")
                    }

                    request.getHeader(HeaderNames.AUTHORIZATION) == "Bearer access-A" -> {
                        envelope(10002)
                    }

                    else -> {
                        envelope(0, "{}")
                    }
                }
            }
        val pending = PendingKeyStore(MemoryPendingKeyBackend()) { 0L }
        val api = client(pending = pending)
        val first = pool.submit<ApiResult> { api.execute(ApiCall("createWithdrawal", body = """{"amount_cent":1000}""")) }
        await(refreshArrived)

        assertEquals(PendingKeyStore.IN_FLIGHT, pending.pendingFor("createWithdrawal")?.stateRaw)
        assertEquals(
            ApiResult.LocalReject(LocalRejectReason.PENDING_KEY_UNRESOLVED),
            api.execute(ApiCall("createWithdrawal", body = """{"amount_cent":2}""")),
        )
        release.countDown()
        assertTrue(first.result() is ApiResult.Success)
    }

    @Test
    fun r205_onlyTheHoldingCallCanSettleOrReleaseTheKey() {
        val store = PendingKeyStore(MemoryPendingKeyBackend()) { 0L }
        assertTrue(store.beforeSend("createWithdrawal", "k", "{}", "account-A", holder = "call-1"))
        store.release("k", "call-0")
        store.onResponse("k", PendingKeyStore.NO_ENVELOPE, "call-0")
        assertEquals(PendingKeyStore.IN_FLIGHT, store.state("k").stateRaw)
        assertFalse(
            "another call cannot take over the held key",
            store.prepareSend("createWithdrawal", "k", "{}", "account-A", "", "call-2"),
        )
        store.onResponse("k", PendingKeyStore.NO_ENVELOPE, "call-1")
        assertEquals(PendingKeyStore.UNKNOWN, store.state("k").stateRaw)
    }

    @Test
    fun ownership_confirmEndsWhenAnotherAccountLogsInDuringItsRefresh() {
        val refreshArrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        val backend = MemoryPendingKeyBackend()
        val pending = PendingKeyStore(backend) { 0L }
        val tokens = tokensA()
        val api = client(tokens = tokens, pending = pending)
        val key =
            unknownWithdrawal(api) { request ->
                when {
                    request.path == "/v1/auth/refresh" -> {
                        refreshArrived.countDown()
                        release.await(5, TimeUnit.SECONDS)
                        refreshPair("access-A2")
                    }

                    request.getHeader(HeaderNames.AUTHORIZATION) == "Bearer access-A" -> {
                        envelope(10002)
                    }

                    else -> {
                        envelope(0, "{}")
                    }
                }
            }
        val confirming = pool.submit<ApiResult> { api.confirmPending("createWithdrawal") }
        await(refreshArrived)
        tokens.switchToB()
        release.countDown()

        val failure = confirming.result() as ApiResult.Failure
        assertEquals(ErrorEffectKind.GENERIC_ERROR, failure.effect.kind)
        assertTrue(requests().none { it.getHeader(HeaderNames.AUTHORIZATION) == "Bearer access-B" })
        assertEquals("A's key is back to awaiting confirmation", PendingKeyStore.UNKNOWN, backend.read("account-A", key)?.stateRaw)
        assertEquals("access-B", tokens.accessToken)
    }

    // ---------- R2-06：作废接口 completed 按原操作给出结果 ----------

    @Test
    fun r206_completedAbandonExampleIsMappedAsTheOriginalBusinessFailure() {
        val pending = PendingKeyStore(MemoryPendingKeyBackend()) { 0L }
        val api = client(pending = pending)
        val key = unknownWithdrawal(api) { json(namedExample("/v1/idempotency-keys/abandon", "post", "completed")) }

        val failure = api.abandonPending("createWithdrawal") as ApiResult.Failure
        assertEquals(30412, failure.code)
        assertEquals("有进行中的提现，暂不能申请注销", failure.msg)
        assertEquals(key, failure.idempotencyKey)
        assertEquals("error.30412", failure.effect.textKey)
        assertEquals(map(30412, "createWithdrawal").copy(registerPending = false), failure.effect)
        assertTrue(effects.contains(failure.effect))
        assertEquals(PendingKeyStore.DONE, pending.state(key).stateRaw)
    }

    @Test
    fun r206_completedSuccessReturnsTheOriginalDataAndAbandonedIsItsOwnResult() {
        val pending = PendingKeyStore(MemoryPendingKeyBackend()) { 0L }
        val api = client(pending = pending)
        val key =
            unknownWithdrawal(api) {
                envelope(0, """{"outcome":"completed","original":{"code":0,"msg":"","data":{"withdrawal_id":"w-1"}}}""")
            }
        val success = api.abandonPending("createWithdrawal") as ApiResult.Success
        assertEquals(JsonPrimitive("w-1"), (success.data as JsonObject)["withdrawal_id"])
        assertEquals(key, success.idempotencyKey)

        val second = unknownWithdrawal(api) { json(namedExample("/v1/idempotency-keys/abandon", "post", "abandoned")) }
        assertEquals(ApiResult.Abandoned(second, "0199a3b4-5c6d-7e8f-9a0b-1c2d3e4f5a6b"), api.abandonPending("createWithdrawal"))
    }

    // ---------- R2-07：按处理响应那一刻的强更状态 ----------

    @Test
    fun r207_restrictedLoginAfterUpdateCheckTurnedUnknownEntersForceUpdate() {
        server.dispatcher =
            dispatcher {
                state = FULL_SUPPORTED.copy(update = UpdateRaw.UNKNOWN)
                loginResponse("restricted", SessionScopeRaw.DELETION_ONLY)
            }
        val api = client(tokens = MemorySessionTokens())

        assertTrue(api.execute(ApiCall("loginBySms", body = """{"phone":"13800000000","code":"1"}""")) is ApiResult.Success)
        assertEquals(listOf<String?>(null), forceUpdates)
    }

    @Test
    fun r207_restrictedRefreshAfterUpdateCheckTurnedUnknownEntersForceUpdate() {
        server.dispatcher =
            dispatcher { request ->
                when {
                    request.path == "/v1/auth/refresh" -> {
                        state = FULL_SUPPORTED.copy(update = UpdateRaw.UNKNOWN)
                        refreshPair("restricted-2", SessionScopeRaw.DELETION_ONLY)
                    }

                    request.getHeader(HeaderNames.AUTHORIZATION) == "Bearer access-A" -> {
                        envelope(10002)
                    }

                    else -> {
                        envelope(0, "{}")
                    }
                }
            }
        client().execute(ApiCall("requestDeletion", body = "{}"))

        assertEquals(listOf<String?>(null), forceUpdates)
    }

    // ---------- R2-08 与裁定：有明确动作的码 ----------

    private fun map(
        code: Int,
        operationId: String,
        data: String = "{}",
        fromProductCard: Boolean = true,
    ) = ErrorActionMapper.map(
        ErrorActionInput(
            code = code,
            data = Json.parseToJsonElement(data).jsonObject,
            operation = checkNotNull(ApiOperations.byId(operationId)),
            headers = emptyMap(),
            isReplay = false,
            state = ErrorActionState(UpdateRaw.SUPPORTED, SessionScopeRaw.FULL, "1.2.0"),
            fromProductCard = fromProductCard,
        ),
    )

    @Test
    fun r208_fieldErrorCarriesTheFieldsAndReasonText() {
        val effect = map(20001, "bindPhone", """{"fields":["phone"],"reason":"phone_invalid"}""")
        assertEquals(ErrorEffectKind.FIELD_ERROR, effect.kind)
        assertEquals(listOf("phone"), effect.fields)
        assertEquals("error.20001.phone_invalid", effect.textKey)
        assertEquals(ReplayMode.NONE, effect.replay)
        assertFalse(effect.registerPending)
    }

    @Test
    fun r208_expiredThirdPartyCredentialGuidesBackToLoginOrTheStepUpPanel() {
        val login = map(20004, "loginByWechat")
        assertEquals(ErrorEffectKind.REAUTHORIZE, login.kind)
        assertEquals("Login", login.route)
        assertEquals(ReplayMode.NONE, login.replay)
        val stepUp = map(20004, "stepUp", """{"reason":"identity_mismatch"}""")
        assertEquals(ErrorEffectKind.REAUTHORIZE, stepUp.kind)
        assertNull("back to the step-up panel", stepUp.route)
        assertEquals(ReplayMode.NONE, stepUp.replay)
        assertFalse(stepUp.registerPending)
    }

    @Test
    fun ruling30144_linkAndSharePageAlwaysShowTheEmptyStateWithLinkLandingText() {
        for (operation in listOf("getLink", "getSharePage")) {
            val effect = map(30144, operation, fromProductCard = true)
            assertEquals(operation, ErrorEffectKind.PAGE_ACTION, effect.kind)
            assertEquals(operation, "link_landing.invalid", effect.textKey)
            assertEquals(ReplayMode.NONE, effect.replay)
        }
        val landing = map(30144, "openLink", fromProductCard = false)
        assertEquals("link_landing.invalid", landing.textKey)
        val card = map(30144, "openLink")
        assertEquals(ErrorEffectKind.REFETCH_PRODUCT, card.kind)
        assertEquals("error.30144", card.textKey)
    }

    // ---------- lessons 30：屏障等待前绑定归属 ----------

    @Test
    fun lesson30_callWaitingOnTheRestrictedBarrierIsNotRebindToANewAccount() {
        val refreshArrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher =
            dispatcher { request ->
                if (request.path == "/v1/auth/refresh") {
                    refreshArrived.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    envelope(10404)
                } else {
                    envelope(0, "{}")
                }
            }
        val tokens = tokensA(SessionScopeRaw.DELETION_ONLY)
        val api = client(tokens = tokens)
        val call = pool.submit<ApiResult> { api.execute(ApiCall("getDeletion")) }
        await(refreshArrived)
        tokens.switchToB()
        release.countDown()

        assertEquals(ApiResult.LocalReject(LocalRejectReason.SESSION_CHANGED), call.result())
        assertEquals("only the barrier refresh", 1, server.requestCount)
        assertEquals("access-B", tokens.accessToken)
    }

    // ---------- lessons 31：屏障暂时失败按网络错误结束；状态通知立即刷新 ----------

    @Test
    fun lesson31_transientBarrierFailureEndsTheCallWithoutSendingTheRestrictedToken() {
        val refreshes = AtomicInteger()
        server.dispatcher =
            dispatcher { request ->
                when {
                    request.path == "/v1/auth/refresh" && refreshes.incrementAndGet() == 1 -> {
                        MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
                    }

                    request.path == "/v1/auth/refresh" -> {
                        refreshPair("access-A2")
                    }

                    else -> {
                        envelope(0, "{}")
                    }
                }
            }
        val tokens = tokensA(SessionScopeRaw.DELETION_ONLY)
        val api = client(tokens = tokens)

        val first = api.execute(ApiCall("getDeletion"))
        assertTrue("network error, not a session end: $first", first is ApiResult.TransportFailure)
        assertEquals("session kept after a transient failure", "access-A", tokens.accessToken)
        assertEquals("only the failed refresh", 1, server.requestCount)
        server.takeRequest()

        // 下一个请求：屏障重新到期，再刷新一次后用新令牌发出
        assertTrue(api.execute(ApiCall("getDeletion")) is ApiResult.Success)
        val sent = List(2) { checkNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
        assertEquals(listOf("/v1/auth/refresh", "/v1/me/deletion"), sent.map { it.path })
        assertEquals("Bearer access-A2", sent.last().getHeader(HeaderNames.AUTHORIZATION))
    }

    @Test
    fun lesson31_updateRecoveredToSupportedRefreshesImmediatelyOnTheNotification() {
        server.dispatcher = dispatcher { refreshPair("restricted-${server.requestCount}", SessionScopeRaw.DELETION_ONLY) }
        val api = client(tokens = tokensA(SessionScopeRaw.DELETION_ONLY))
        // 冷启动的屏障随第一次状态通知发起
        api.onUpdateStateChanged(UpdateRaw.BELOW)
        assertEquals("/v1/auth/refresh", server.takeRequest(5, TimeUnit.SECONDS)?.path)
        awaitRefreshSettled(api)
        // 强更恢复为受支持：通知一到就刷新，不等下一个请求
        api.onUpdateStateChanged(UpdateRaw.SUPPORTED)
        assertEquals("/v1/auth/refresh", server.takeRequest(5, TimeUnit.SECONDS)?.path)
        assertEquals(2, api.refreshCoordinator.refreshCalls)
    }

    private fun awaitRefreshSettled(api: ApiClient) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (api.refreshCoordinator.lastCompletedAtMillis == null || api.refreshCoordinator.refreshing) {
            assertTrue("refresh did not settle", System.nanoTime() < deadline)
            Thread.sleep(10)
        }
    }

    // ---------- lessons 32：先持久化再改内存 ----------

    private class SwitchableBackend(
        val inner: MemoryPendingKeyBackend = MemoryPendingKeyBackend(),
    ) : PendingKeyBackend by inner {
        @Volatile
        var failWrites = false

        override fun write(record: PendingKeyRecord) {
            check(!failWrites) { "写入加密存储失败" }
            inner.write(record)
        }
    }

    @Test
    fun lesson32_confirmWhoseInFlightWriteFailsStaysUnknownAndSaysSo() {
        val backend = SwitchableBackend()
        val pending = PendingKeyStore(backend) { 0L }
        val api = client(pending = pending)
        val key = unknownWithdrawal(api) { envelope(0, "{}") }
        backend.failWrites = true

        assertEquals(ApiResult.LocalReject(LocalRejectReason.PENDING_STORE_FAILED), api.confirmPending("createWithdrawal"))
        assertEquals(PendingKeyStore.UNKNOWN, pending.state(key).stateRaw)
        assertEquals(1, server.requestCount)
        backend.failWrites = false
        assertTrue("not stuck: the user can confirm again", api.confirmPending("createWithdrawal") is ApiResult.Success)
    }

    // ---------- lessons 34：旧世代的 10404 不动新会话 ----------

    @Test
    fun lesson34_late10404FromTheOldSessionDoesNotClearTheNewOne() {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher =
            dispatcher {
                arrived.countDown()
                release.await(5, TimeUnit.SECONDS)
                envelope(10404)
            }
        val tokens = tokensA()
        val api = client(tokens = tokens)
        val call = pool.submit<ApiResult> { api.execute(ApiCall("getMe")) }
        await(arrived)
        tokens.switchToB()
        release.countDown()

        val failure = call.result() as ApiResult.Failure
        assertEquals(ErrorEffectKind.GENERIC_ERROR, failure.effect.kind)
        assertNull(failure.effect.route)
        assertEquals("access-B", tokens.accessToken)
    }

    @Test
    fun lesson34_10404OfTheCurrentSessionStillClearsIt() {
        server.enqueue(envelope(10404))
        val tokens = tokensA()
        val failure = client(tokens = tokens).execute(ApiCall("getMe")) as ApiResult.Failure
        assertEquals(ErrorEffectKind.CLEAR_SESSION_LOGIN, failure.effect.kind)
        assertNull(tokens.accessToken)
    }

    /** openapi 示例（多个具名示例中的一个）。 */
    private fun namedExample(
        path: String,
        method: String,
        name: String,
    ): String {
        val start = File(checkNotNull(System.getProperty("user.dir"))).canonicalFile
        val root = generateSequence(start) { it.parentFile }.first { File(it, "contract.lock").isFile }
        val openapi = Json.parseToJsonElement(File(root, "contracts/snapshot/contracts/openapi.json").readText()).jsonObject
        return openapi
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
            .getValue("examples")
            .jsonObject
            .getValue(name)
            .jsonObject
            .getValue("value")
            .toString()
    }

    private companion object {
        val FULL_SUPPORTED =
            NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, update = UpdateRaw.SUPPORTED)
        val BASIC = NetworkContext(PrivacyRaw.BASIC, environmentSettled = true, credentialsReadable = true, update = UpdateRaw.UNKNOWN)
    }
}
