package com.couli.core.network

import com.couli.core.generated.ApiOperation
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * APP-03a Android 第 3 轮评审（android-review-r3.md R3-01..R3-07，R3-05 的存储部分在 SecureStorageCommitFailureTest）与
 * cross-platform-r1-lessons.md 第 36、37、39、40 条的复现测试：按各条的失败场景对本地假服务（MockWebServer）发请求。
 */
@Suppress("LargeClass") // 7 条评审项与 4 条共性问题各自的复现场景
class ApiClientReviewR3Test {
    private lateinit var server: MockWebServer
    private val forceUpdates = Collections.synchronizedList(mutableListOf<String?>())
    private val effects = Collections.synchronizedList(mutableListOf<ErrorEffect>())
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

    private fun SessionTokens.switchToB() {
        clear()
        save("access-B", "refresh-B", SessionScopeRaw.FULL, "account-B")
    }

    @Suppress("LongParameterList")
    private fun client(
        tokens: SessionTokens = tokensA(),
        pending: PendingKeyStore = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
        hooks: NetworkHooks = NetworkHooks(onForceUpdate = { forceUpdates += it }, onEffect = { effects += it }),
        retryPolicy: (ApiOperation) -> RetryDecision = { RetryPolicy.forOperation(it).copy(timeoutMillis = 2_000) },
        clock: () -> Long = System::currentTimeMillis,
    ) = ApiClient(
        identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.2.0", build = "1", channel = "official"),
        baseUrl = { server.url("/").toString() },
        context = { state },
        device = FixedDevice(),
        tokens = tokens,
        pendingKeys = pending,
        hooks = hooks,
        http = OkHttpClient(),
        clock = clock,
        sleeper = {},
        retryPolicy = retryPolicy,
    )

    private fun envelope(
        code: Int,
        data: String = "null",
    ) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"code":$code,"msg":"","data":$data,"trace_id":"trace-0123456789"}""")

    private fun pair(
        access: String,
        scope: String = SessionScopeRaw.FULL,
    ) = """{"access_token":"$access","access_expires_at":"2026-10-02T13:30:00+08:00","refresh_token":"refresh-$access",""" +
        """"refresh_expires_at":"2026-11-01T11:30:00+08:00","session_scope":"$scope"}"""

    private fun refreshPair(
        access: String,
        scope: String = SessionScopeRaw.FULL,
    ) = envelope(0, pair(access, scope))

    private fun skewed(code: Int) = envelope(code).setHeader("Date", "Wed, 30 Sep 2026 06:00:00 GMT")

    private fun dispatcher(respond: (RecordedRequest) -> MockResponse) =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = respond(request)
        }

    private fun requests(): List<RecordedRequest> = List(server.requestCount) { server.takeRequest(1, TimeUnit.SECONDS)!! }

    private fun <T> Future<T>.result(): T = get(15, TimeUnit.SECONDS)

    private fun await(latch: CountDownLatch) = assertTrue("timed out", latch.await(5, TimeUnit.SECONDS))

    private fun RecordedRequest.isRefresh() = path == "/v1/auth/refresh"

    private fun RecordedRequest.bearer() = getHeader(HeaderNames.AUTHORIZATION)?.removePrefix("Bearer ")

    private fun awaitRefreshSettled(api: ApiClient) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (api.refreshCoordinator.lastCompletedAtMillis == null || api.refreshCoordinator.refreshing) {
            assertTrue("refresh did not settle", System.nanoTime() < deadline)
            Thread.sleep(10)
        }
    }

    /** 首次提交 createWithdrawal 断连 → 结果未知，返回键（之后的请求交给 [then]）。 */
    private fun unknownWithdrawal(
        api: ApiClient,
        then: (RecordedRequest) -> MockResponse = { envelope(0, "{}") },
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

    // ---------- R3-01：刷新对象就是检查令牌时的那份快照 ----------

    /**
     * 令牌检查通过之后、刷新请求写出之前，账号 B 登录：第一次在「刷新发起的调用栈里」或「刷新线程上」读会话时换成 B
     * （旧实现在 RefreshCoordinator 发起刷新时重新读当前会话，读到的就是 B）。
     */
    private class LoginAfterTheCheck(
        val inner: MemorySessionTokens,
    ) : SessionTokens by inner {
        val armed = AtomicBoolean(false)
        private val fired = AtomicBoolean(false)

        override fun snapshot(): SessionSnapshot {
            val enqueuing = Thread.currentThread().stackTrace.any { it.methodName == "startRefresh" }
            val refreshThread = Thread.currentThread().name == "couli-token-refresh"
            val afterTheCheck = enqueuing || refreshThread
            if (armed.get() && afterTheCheck && !fired.getAndSet(true)) {
                inner.clear()
                inner.save("access-B", "refresh-B", SessionScopeRaw.FULL, "account-B")
            }
            return inner.snapshot()
        }
    }

    @Test
    fun r301_accountBLoggingInBetweenTheTokenCheckAndTheRefreshIsNeverRefreshedOrCleared() {
        val tokens = LoginAfterTheCheck(tokensA())
        server.dispatcher =
            dispatcher { request ->
                when {
                    request.isRefresh() -> {
                        refreshPair("access-X")
                    }

                    request.bearer() == "access-A" -> {
                        tokens.armed.set(true)
                        envelope(10002)
                    }

                    else -> {
                        envelope(0, "{}")
                    }
                }
            }
        val api = client(tokens = tokens)

        val failure = api.execute(ApiCall("getMe")) as ApiResult.Failure
        val sent = requests()
        assertTrue("B's refresh token is never sent", sent.none { it.isRefresh() && "refresh-B" in it.body.readUtf8() })
        assertEquals("access-B", tokens.accessToken)
        assertEquals("refresh-B", tokens.refreshToken)
        assertEquals("account-B", tokens.userId)
        assertEquals("A's call ends without touching B", ErrorEffectKind.GENERIC_ERROR, failure.effect.kind)
        assertTrue(effects.none { it.kind == ErrorEffectKind.CLEAR_SESSION_LOGIN || it.route == "Login" })
    }

    /** lessons 36：A 的刷新窗口在途时 B 的 10002 不加入它，等它结束后为 B 单独刷新、重放成功。 */
    @Test
    fun r301_lesson36_anotherGenerationsRefreshWindowIsNotSharedAndBGetsItsOwnRefresh() {
        val refreshA = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        val bWaiting = CountDownLatch(1)
        server.dispatcher =
            dispatcher { request ->
                val body = if (request.isRefresh()) request.body.readUtf8() else ""
                when {
                    request.isRefresh() && "refresh-A" in body -> {
                        refreshA.countDown()
                        releaseA.await(5, TimeUnit.SECONDS)
                        refreshPair("access-A2")
                    }

                    request.isRefresh() && "refresh-B" in body -> {
                        refreshPair("access-B2")
                    }

                    request.bearer() == "access-A" -> {
                        envelope(10002)
                    }

                    request.bearer() == "access-B" -> {
                        bWaiting.countDown()
                        envelope(10002)
                    }

                    else -> {
                        envelope(0, """{"who":"${request.bearer()}"}""")
                    }
                }
            }
        val tokens = tokensA()
        val api = client(tokens = tokens)
        val callA = pool.submit<ApiResult> { api.execute(ApiCall("getMe")) }
        await(refreshA)
        tokens.switchToB()
        val callB = pool.submit<ApiResult> { api.execute(ApiCall("getMe")) }
        await(bWaiting)
        Thread.sleep(200) // B 已拿到 10002、在等 A 的窗口结束
        releaseA.countDown()

        val b = callB.result()
        assertTrue("B is refreshed and replayed: $b", b is ApiResult.Success)
        assertEquals("access-B2", tokens.accessToken)
        assertEquals("account-B", tokens.userId)
        assertEquals(ErrorEffectKind.GENERIC_ERROR, (callA.result() as ApiResult.Failure).effect.kind)
        assertEquals(2, api.refreshCoordinator.refreshCalls)
        assertTrue(requests().none { it.bearer() == "access-A2" })
    }

    // ---------- R3-02：后台屏障的暂时失败按结果结算 ----------

    @Test
    fun r302_backgroundBarrierTransientFailureIsNotDroppedAndTheNextCallRefreshesFirst() {
        val refreshes = AtomicInteger()
        server.dispatcher =
            dispatcher { request ->
                when {
                    request.isRefresh() && refreshes.incrementAndGet() == 1 -> {
                        MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
                    }

                    request.isRefresh() -> {
                        refreshPair("access-A2", SessionScopeRaw.DELETION_ONLY)
                    }

                    else -> {
                        envelope(0, "{}")
                    }
                }
            }
        val tokens = tokensA(SessionScopeRaw.DELETION_ONLY)
        val api = client(tokens = tokens)
        // 状态通知主动发起屏障刷新，此时没有请求在等；刷新断连
        api.onUpdateStateChanged(UpdateRaw.SUPPORTED)
        awaitRefreshSettled(api)
        assertEquals(1, server.requestCount)
        assertEquals("access-A", tokens.accessToken)

        assertTrue(api.execute(ApiCall("getDeletion")) is ApiResult.Success)
        val sent = requests()
        assertEquals(listOf("/v1/auth/refresh", "/v1/auth/refresh", "/v1/me/deletion"), sent.map { it.path })
        assertTrue("the old restricted token is never sent", sent.none { it.bearer() == "access-A" })
        assertEquals("access-A2", sent.last().bearer())
    }

    // ---------- R3-03：旧账号的迟到前置错误 ----------

    @Test
    fun r303_lateLoginConsentAndBindPhoneFromTheOldAccountTriggerNothingForTheNewOne() {
        for (code in listOf(10001, 10004, 10005, 10003, 10007, 30101)) {
            effects.clear()
            val arrived = CountDownLatch(1)
            val release = CountDownLatch(1)
            server.dispatcher =
                dispatcher {
                    arrived.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    envelope(code, """{"consent_type":"privacy","stage":"cooling"}""")
                }
            val tokens = tokensA()
            val api = client(tokens = tokens)
            val call = pool.submit<ApiResult> { api.execute(ApiCall("getMe")) }
            await(arrived)
            tokens.switchToB()
            release.countDown()

            val failure = call.result() as ApiResult.Failure
            assertEquals("$code", ErrorEffectKind.GENERIC_ERROR, failure.effect.kind)
            assertFalse("$code", failure.effect.registerPending)
            assertEquals("$code", ReplayMode.NONE, failure.effect.replay)
            assertNull("$code", failure.effect.route)
            assertEquals("$code: only the stale effect is delivered", listOf(failure.effect), effects.toList())
            assertEquals("access-B", tokens.accessToken)
        }
    }

    @Test
    fun r303_prerequisiteOfTheCurrentSessionIsStillGuidedAndRegistered() {
        server.enqueue(envelope(10001))
        val failure = client().execute(ApiCall("getMe")) as ApiResult.Failure
        assertEquals(ErrorEffectKind.LOGIN, failure.effect.kind)
        assertTrue(failure.effect.registerPending)
        assertEquals(ReplayMode.SAME_KEY, failure.effect.replay)
    }

    @Test
    fun r303_anonymousCallsLate10001AfterSomeoneLoggedInDoesNotSendTheNewUserToLogin() {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher =
            dispatcher {
                arrived.countDown()
                release.await(5, TimeUnit.SECONDS)
                envelope(10001)
            }
        val tokens = MemorySessionTokens()
        val api = client(tokens = tokens)
        val call = pool.submit<ApiResult> { api.execute(ApiCall("openLink", mapOf("link_id" to "abc"), body = "{}")) }
        await(arrived)
        tokens.save("access-B", "refresh-B", SessionScopeRaw.FULL, "account-B")
        release.countDown()

        val failure = call.result() as ApiResult.Failure
        assertEquals(ErrorEffectKind.GENERIC_ERROR, failure.effect.kind)
        assertFalse(failure.effect.registerPending)
    }

    // ---------- R3-04：账号在读取之前确定 ----------

    /** 记下每次读取用的账号。 */
    private class ReadSpy(
        val inner: MemoryPendingKeyBackend = MemoryPendingKeyBackend(),
    ) : PendingKeyBackend by inner {
        val accounts = Collections.synchronizedList(mutableListOf<String>())

        override fun read(
            account: String,
            key: String,
        ): PendingKeyRecord? {
            accounts += account
            return inner.read(account, key)
        }

        override fun list(account: String): List<PendingKeyRecord> {
            accounts += account
            return inner.list(account)
        }
    }

    @Test
    fun r304_newAccountsQueriesNeverReadThePreviousAccountsRecords() {
        val backend = ReadSpy()
        val pending = PendingKeyStore(backend) { 0L }
        val tokens = tokensA()
        val api = client(tokens = tokens, pending = pending)
        val key = unknownWithdrawal(api)
        assertEquals("account-A", pending.currentAccount)
        // B 登录，还没调 restorePending：Store 里的当前账号仍是 A
        tokens.switchToB()
        backend.accounts.clear()

        assertNull(api.pendingFor("createWithdrawal"))
        assertEquals(ApiResult.LocalReject(LocalRejectReason.PENDING_KEY_UNRESOLVED), api.confirmPending("createWithdrawal"))
        assertEquals(ApiResult.LocalReject(LocalRejectReason.PENDING_KEY_UNRESOLVED), api.abandonPending("createWithdrawal"))
        assertTrue(
            "only B's records are read: ${backend.accounts}",
            backend.accounts.isNotEmpty() && backend.accounts.all { it == "account-B" },
        )
        assertEquals(PendingKeyStore.UNKNOWN, backend.inner.read("account-A", key)?.stateRaw)
        assertEquals(1, server.requestCount)
    }

    // ---------- R3-06：校时重签不占业务重放 ----------

    @Test
    fun r306_clockResignDoesNotUseUpTheReplayBudgetSoA10002IsStillRefreshed() {
        val opens = AtomicInteger()
        server.dispatcher =
            dispatcher { request ->
                when {
                    request.isRefresh() -> refreshPair("access-A2")
                    opens.incrementAndGet() == 1 -> skewed(10401)
                    request.bearer() == "access-A" -> envelope(10002)
                    else -> envelope(0, "{}")
                }
            }
        val tokens = tokensA()
        val api = client(tokens = tokens, clock = { 0L })

        val result = api.execute(ApiCall("openLink", mapOf("link_id" to "abc"), body = "{}"))
        assertTrue("resign → 10002 → refresh → replay: $result", result is ApiResult.Success)
        val sent = requests()
        assertEquals(listOf("/v1/links/abc/open", "/v1/links/abc/open", "/v1/auth/refresh", "/v1/links/abc/open"), sent.map { it.path })
        val keys = sent.filterNot { it.isRefresh() }.map { it.getHeader(HeaderNames.IDEMPOTENCY_KEY) }.toSet()
        assertEquals("same key throughout", 1, keys.size)
        assertEquals("access-A2", sent.last().bearer())
        assertEquals(1, api.refreshCoordinator.refreshCalls)
    }

    @Test
    fun r306_resignHappensAtMostOnce() {
        server.dispatcher = dispatcher { skewed(10401) }
        val result = client(clock = { 0L }).execute(ApiCall("openLink", mapOf("link_id" to "abc"), body = "{}"))
        assertTrue(result is ApiResult.Failure)
        assertEquals(2, server.requestCount)
    }

    // ---------- R3-07：刷新请求自身遇到可校时的 10401 ----------

    @Test
    fun r307_refreshRejectedWith10401AndASkewedDateIsResignedOnceInTheSameWindow() {
        val refreshes = AtomicInteger()
        server.dispatcher =
            dispatcher { request ->
                when {
                    request.isRefresh() && refreshes.incrementAndGet() == 1 -> skewed(10401)
                    request.isRefresh() -> refreshPair("access-A2")
                    request.bearer() == "access-A" -> envelope(10002)
                    else -> envelope(0, "{}")
                }
            }
        val tokens = tokensA()
        val api = client(tokens = tokens, clock = { 0L })

        val result = api.execute(ApiCall("getMe"))
        assertTrue("refresh resigned and the call replayed: $result", result is ApiResult.Success)
        assertEquals("access-A2", tokens.accessToken)
        assertEquals("one single-flight window", 1, api.refreshCoordinator.refreshCalls)
        val refreshesSent = requests().filter { it.isRefresh() }
        assertEquals(2, refreshesSent.size)
        assertNotEquals(refreshesSent[0].getHeader(HeaderNames.TIMESTAMP), refreshesSent[1].getHeader(HeaderNames.TIMESTAMP))
        assertNotEquals(refreshesSent[0].getHeader(HeaderNames.NONCE), refreshesSent[1].getHeader(HeaderNames.NONCE))
        assertTrue(effects.none { it.kind == ErrorEffectKind.CLEAR_SESSION_LOGIN })
    }

    @Test
    fun r307_refreshStill10401AfterTheResignIsRejected() {
        server.dispatcher =
            dispatcher { request ->
                if (request.isRefresh()) skewed(10401) else envelope(10002)
            }
        val tokens = tokensA()
        val failure = client(tokens = tokens, clock = { 0L }).execute(ApiCall("getMe")) as ApiResult.Failure
        assertEquals(ErrorEffectKind.CLEAR_SESSION_LOGIN, failure.effect.kind)
        assertEquals("original + refresh + one resigned refresh", 3, server.requestCount)
        assertNull(tokens.accessToken)
    }

    // ---------- lessons 37：本世代被刷新失败清掉之后的晚到 10002 ----------

    private class CountingTokens(
        val inner: MemorySessionTokens,
    ) : SessionTokens by inner {
        val clears = AtomicInteger()

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

    @Test
    fun lesson37_late10002AfterTheRefreshFailureReusesItWithoutASecondRefreshOrClear() {
        val slowArrived = CountDownLatch(1)
        val releaseSlow = CountDownLatch(1)
        server.dispatcher =
            dispatcher { request ->
                when {
                    request.isRefresh() -> {
                        envelope(10404)
                    }

                    request.path == "/v1/me/deletion" -> {
                        slowArrived.countDown()
                        releaseSlow.await(5, TimeUnit.SECONDS)
                        envelope(10002)
                    }

                    else -> {
                        envelope(10002)
                    }
                }
            }
        val tokens = CountingTokens(tokensA())
        val api = client(tokens = tokens)
        val slow = pool.submit<ApiResult> { api.execute(ApiCall("getDeletion")) }
        await(slowArrived)
        val first = api.execute(ApiCall("getMe")) as ApiResult.Failure
        assertEquals(ErrorEffectKind.CLEAR_SESSION_LOGIN, first.effect.kind)
        releaseSlow.countDown()

        val late = slow.result() as ApiResult.Failure
        assertEquals("reuses the session-cleared result", ErrorEffectKind.CLEAR_SESSION_LOGIN, late.effect.kind)
        assertEquals(1, api.refreshCoordinator.refreshCalls)
        assertEquals("cleared once", 1, tokens.clears.get())
    }

    @Test
    fun lesson37_late10002AfterTheUserLoggedOutIsSessionChangedNotSessionCleared() {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher =
            dispatcher {
                arrived.countDown()
                release.await(5, TimeUnit.SECONDS)
                envelope(10002)
            }
        val tokens = CountingTokens(tokensA())
        val api = client(tokens = tokens)
        val call = pool.submit<ApiResult> { api.execute(ApiCall("getMe")) }
        await(arrived)
        tokens.clear() // 用户退出
        release.countDown()

        val failure = call.result() as ApiResult.Failure
        assertEquals(ErrorEffectKind.GENERIC_ERROR, failure.effect.kind)
        assertNull(failure.effect.route)
        assertEquals(0, api.refreshCoordinator.refreshCalls)
        assertEquals(1, tokens.clears.get())
    }

    // ---------- lessons 39：未知键收到非终结的 2xxxx ----------

    @Test
    fun lesson39_unknownKeyGettingANonTerminal2xxxxShowsCannotConfirm() {
        val pending = PendingKeyStore(MemoryPendingKeyBackend()) { 0L }
        val api = client(pending = pending)
        val key = unknownWithdrawal(api) { envelope(20001, """{"fields":["amount_cent"]}""") }

        val failure = api.confirmPending("createWithdrawal") as ApiResult.Failure
        assertEquals(ErrorEffectKind.PAGE_ACTION, failure.effect.kind)
        assertEquals("pending_confirm.cannot_confirm", failure.effect.textKey)
        assertEquals(ReplayMode.NONE, failure.effect.replay)
        assertFalse(failure.effect.registerPending)
        assertTrue(failure.effect.pendingKeyUnknown)
        assertEquals(PendingKeyStore.UNKNOWN, pending.state(key, "account-A").stateRaw)
    }

    // ---------- lessons 40：登录令牌保存失败不算登录成功 ----------

    private class FailingSaveTokens(
        val inner: MemorySessionTokens = MemorySessionTokens(),
    ) : SessionTokens by inner {
        override fun saveIf(
            expectedGeneration: Long,
            accessToken: String,
            refreshToken: String,
            scope: String,
            userId: String?,
        ): Boolean = error("写入加密存储失败")
    }

    @Test
    fun lesson40_loginWhoseTokensCannotBeSavedIsNotALoginSuccess() {
        server.enqueue(envelope(0, """{"user_id":"account-L","tokens":${pair("access-L")}}"""))
        val tokens = FailingSaveTokens()
        val result = client(tokens = tokens).execute(ApiCall("loginBySms", body = """{"phone":"13800000000","code":"1"}"""))
        assertEquals(ApiResult.SessionNotSaved(LocalRejectReason.SESSION_SAVE_FAILED, "trace-0123456789"), result)
        assertNull(tokens.accessToken)
    }

    @Test
    fun lesson40_loginResponseWithoutTokensIsNotALoginSuccess() {
        server.enqueue(envelope(0, """{"user_id":"account-L"}"""))
        val result = client(tokens = MemorySessionTokens()).execute(ApiCall("loginBySms", body = """{"phone":"13800000000","code":"1"}"""))
        assertEquals(ApiResult.SessionNotSaved(LocalRejectReason.INVALID_SESSION_TOKENS, TEST_TRACE_ID), result)
    }

    private companion object {
        val FULL_SUPPORTED =
            NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, update = UpdateRaw.SUPPORTED)
    }
}

private const val TEST_TRACE_ID = "trace-0123456789"
