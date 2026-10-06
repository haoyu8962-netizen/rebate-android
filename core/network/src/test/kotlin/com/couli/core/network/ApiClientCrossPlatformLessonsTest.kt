package com.couli.core.network

import com.couli.core.generated.ApiOperation
import com.couli.core.generated.ApiOperations
import com.couli.generated.ErrorCodes
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.JsonObject
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
 * cross-platform-r1-lessons.md 第 14–21 条（鸿蒙第 1 轮评审的三端共性问题）在 Android 端的复现测试：对本地假服务
 * （MockWebServer）发请求，按各条的失败场景构造。
 */
@Suppress("LargeClass") // 8 条共性问题各自的复现场景
class ApiClientCrossPlatformLessonsTest {
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

    /** 清会话写盘失败（Keystore / commit 抛错）。 */
    private class ThrowingClearTokens(
        val inner: MemorySessionTokens,
    ) : SessionTokens by inner {
        val clearAttempts = AtomicInteger()

        override fun clear() {
            clearAttempts.incrementAndGet()
            error("写入加密存储失败")
        }

        override fun clearIf(expectedGeneration: Long): Boolean {
            clearAttempts.incrementAndGet()
            error("写入加密存储失败")
        }
    }

    @Suppress("LongParameterList")
    private fun client(
        tokens: SessionTokens = MemorySessionTokens("access-A", "refresh-A", SessionScopeRaw.FULL, userId = "account-A"),
        pending: PendingKeyStore = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
        hooks: NetworkHooks = NetworkHooks(onForceUpdate = { forceUpdates += it }, onEffect = { effects += it }),
        sleeper: (Long) -> Unit = {},
        retryPolicy: (ApiOperation) -> RetryDecision = { RetryPolicy.forOperation(it).copy(timeoutMillis = 2_000) },
    ) = ApiClient(
        identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.2.0", build = "1", channel = "official"),
        baseUrl = { server.url("/").toString() },
        context = { state },
        device = FixedDevice(),
        tokens = tokens,
        pendingKeys = pending,
        hooks = hooks,
        sleeper = sleeper,
        retryPolicy = retryPolicy,
    )

    private fun envelope(
        code: Int,
        data: String = "null",
    ) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"code":$code,"msg":"","data":$data,"trace_id":"trace-0123456789"}""")

    private fun refreshPair(
        access: String,
        scope: String = SessionScopeRaw.FULL,
    ) = envelope(
        0,
        """{"access_token":"$access","access_expires_at":"2026-10-02T13:30:00+08:00","refresh_token":"refresh-$access",""" +
            """"refresh_expires_at":"2026-11-01T11:30:00+08:00","session_scope":"$scope"}""",
    )

    private fun dispatcher(respond: (RecordedRequest) -> MockResponse) =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = respond(request)
        }

    private fun requests(): List<RecordedRequest> = List(server.requestCount) { server.takeRequest(1, TimeUnit.SECONDS)!! }

    /** 首次提交断连 → 结果未知，返回键。 */
    private fun unknownWithdrawal(api: ApiClient): String {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val failure = api.execute(ApiCall("createWithdrawal", body = """{"amount_cent":1000}""")) as ApiResult.TransportFailure
        assertTrue(failure.pendingKeyUnknown)
        return checkNotNull(failure.idempotencyKey)
    }

    // ---------- 14：请求绑定账号与会话世代 ----------

    @Test
    fun lesson14_late10002AfterAccountSwitchIsNotReplayedForTheNewAccount() {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher =
            dispatcher { request ->
                if (request.getHeader(HeaderNames.AUTHORIZATION) == "Bearer access-A") {
                    arrived.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    envelope(10002)
                } else {
                    envelope(0, "{}")
                }
            }
        val tokens = MemorySessionTokens("access-A", "refresh-A", SessionScopeRaw.FULL, userId = "account-A")
        val api = client(tokens = tokens)
        val pool = Executors.newSingleThreadExecutor()
        val call = pool.submit<ApiResult> { api.execute(ApiCall("getMe")) }
        assertTrue(arrived.await(5, TimeUnit.SECONDS))
        // A 退出，B 登录
        tokens.clear()
        tokens.save("access-B", "refresh-B", SessionScopeRaw.FULL, "account-B")
        release.countDown()
        val failure = call.get(10, TimeUnit.SECONDS) as ApiResult.Failure
        pool.shutdown()

        assertEquals(ErrorEffectKind.GENERIC_ERROR, failure.effect.kind)
        assertEquals(ReplayMode.NONE, failure.effect.replay)
        assertEquals("only the original request; no refresh, no replay with B's token", 1, server.requestCount)
        assertEquals(0, api.refreshCoordinator.refreshCalls)
        assertEquals("access-B", tokens.accessToken)
    }

    @Test
    fun lesson14_refreshedOutcomeIsNotReusedOnceAnotherAccountLoggedIn() {
        val lateArrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher =
            dispatcher { request ->
                when {
                    request.path == "/v1/auth/refresh" -> {
                        refreshPair("access-A2")
                    }

                    request.path == "/v1/me?who=late" -> {
                        lateArrived.countDown()
                        release.await(5, TimeUnit.SECONDS)
                        envelope(10002)
                    }

                    request.getHeader(HeaderNames.AUTHORIZATION) == "Bearer access-A" -> {
                        envelope(10002)
                    }

                    else -> {
                        envelope(0, "{}")
                    }
                }
            }
        val tokens = MemorySessionTokens("access-A", "refresh-A", SessionScopeRaw.FULL, userId = "account-A")
        val api = client(tokens = tokens)
        val pool = Executors.newSingleThreadExecutor()
        val late = pool.submit<ApiResult> { api.execute(ApiCall("getMe", query = "who=late")) }
        assertTrue(lateArrived.await(5, TimeUnit.SECONDS))
        // A 的另一个请求触发刷新并重放成功（access-A → access-A2）
        assertTrue(api.execute(ApiCall("getMe", query = "who=first")) is ApiResult.Success)
        // 之后 B 登录；A 的迟到 10002 不能复用 A2，也不能用 B 的令牌重放
        tokens.save("access-B", "refresh-B", SessionScopeRaw.FULL, "account-B")
        release.countDown()
        val lateResult = late.get(10, TimeUnit.SECONDS) as ApiResult.Failure
        pool.shutdown()

        assertEquals(ErrorEffectKind.GENERIC_ERROR, lateResult.effect.kind)
        val sent = requests()
        assertEquals(listOf("/v1/me?who=late", "/v1/me?who=first", "/v1/auth/refresh", "/v1/me?who=first"), sent.map { it.path })
        assertTrue(sent.none { it.getHeader(HeaderNames.AUTHORIZATION) == "Bearer access-B" })
        assertEquals("access-B", tokens.accessToken)
    }

    @Test
    fun lesson14_replayAfterDeviceReRegistrationIsDroppedWhenTheSessionChanged() {
        val tokens = MemorySessionTokens("access-A", "refresh-A", SessionScopeRaw.FULL, userId = "account-A")
        val hooks =
            NetworkHooks(
                reRegisterDevice = {
                    // 重新注册期间切换了账号
                    tokens.save("access-B", "refresh-B", SessionScopeRaw.FULL, "account-B")
                    true
                },
            )
        server.enqueue(envelope(10402))
        server.enqueue(envelope(0, "{}"))
        val result = client(tokens = tokens, hooks = hooks).execute(ApiCall("getMe"))

        assertEquals(ApiResult.LocalReject(LocalRejectReason.SESSION_CHANGED), result)
        assertEquals(1, server.requestCount)
    }

    // ---------- 15：未决键核对账号 ----------

    @Test
    fun lesson15_otherAccountsPendingRecordIsNeitherShownNorSent() {
        val tokens = MemorySessionTokens("access-A", "refresh-A", SessionScopeRaw.FULL, userId = "account-A")
        val pending = PendingKeyStore(MemoryPendingKeyBackend()) { 0L }
        val api = client(tokens = tokens, pending = pending)
        val key = unknownWithdrawal(api)
        assertEquals(key, api.pendingFor("createWithdrawal")?.key)

        // B 登录（还没调 restorePending）：A 的记录不显示、不能确认或放弃
        tokens.save("access-B", "refresh-B", SessionScopeRaw.FULL, "account-B")
        assertNull(api.pendingFor("createWithdrawal"))
        assertEquals(ApiResult.LocalReject(LocalRejectReason.PENDING_KEY_UNRESOLVED), api.confirmPending("createWithdrawal"))
        assertEquals(ApiResult.LocalReject(LocalRejectReason.PENDING_KEY_UNRESOLVED), api.abandonPending("createWithdrawal"))
        assertEquals(1, server.requestCount)
        // Store 已按 B 的会话恢复（R3-04）；A 名下的记录原样保持待确认
        assertEquals(PendingKeyStore.UNKNOWN, pending.state(key, "account-A").stateRaw)
    }

    @Test
    fun lesson15_accountSwitchBetweenConfirmAndSendIsRejectedAndTheKeyStaysPending() {
        val tokens = MemorySessionTokens("access-A", "refresh-A", SessionScopeRaw.FULL, userId = "account-A")
        val inner = MemoryPendingKeyBackend()
        // 用户点【确认】的瞬间（记录置为 in_flight）会话被换成 B
        val switching =
            object : PendingKeyBackend by inner {
                override fun write(record: PendingKeyRecord) {
                    inner.write(record)
                    if (record.stateRaw == PendingKeyStore.IN_FLIGHT && record.everUnknown) {
                        tokens.save("access-B", "refresh-B", SessionScopeRaw.FULL, "account-B")
                    }
                }
            }
        val pending = PendingKeyStore(switching) { 0L }
        val api = client(tokens = tokens, pending = pending)
        val key = unknownWithdrawal(api)

        assertEquals(ApiResult.LocalReject(LocalRejectReason.ACCOUNT_MISMATCH), api.confirmPending("createWithdrawal"))
        assertEquals("A's body is never sent under B's session", 1, server.requestCount)
        assertEquals(PendingKeyStore.UNKNOWN, inner.read("account-A", key)?.stateRaw)
        assertTrue(inner.list("account-B").isEmpty())
    }

    @Test
    fun lesson15_responseSettlesTheOriginalAccountsRecordAfterAnAccountSwitch() {
        val tokens = MemorySessionTokens("access-A", "refresh-A", SessionScopeRaw.FULL, userId = "account-A")
        val backend = MemoryPendingKeyBackend()
        val pending = PendingKeyStore(backend) { 0L }
        val api = client(tokens = tokens, pending = pending)
        val key = unknownWithdrawal(api)

        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher =
            dispatcher { _ ->
                arrived.countDown()
                release.await(5, TimeUnit.SECONDS)
                envelope(0, """{"withdrawal_id":"w1"}""")
            }
        val pool = Executors.newSingleThreadExecutor()
        val confirming = pool.submit<ApiResult> { api.confirmPending("createWithdrawal") }
        assertTrue(arrived.await(5, TimeUnit.SECONDS))
        // 确认请求在途时切换到 B，并按 B 恢复未决记录
        tokens.save("access-B", "refresh-B", SessionScopeRaw.FULL, "account-B")
        assertTrue(api.restorePending())
        release.countDown()
        assertTrue(confirming.get(10, TimeUnit.SECONDS) is ApiResult.Success)
        pool.shutdown()

        assertNull("A's record is settled (deleted), not left in_flight", backend.read("account-A", key))
    }

    // ---------- 16：刷新收尾顺序；失败时等待者不带旧令牌发出 ----------

    @Test
    fun lesson16_failedRefreshNeverLetsWaitersSendTheOldToken() {
        val refreshes = AtomicInteger()
        server.dispatcher =
            dispatcher { request ->
                if (request.path == "/v1/auth/refresh") {
                    refreshes.incrementAndGet()
                    Thread.sleep(200)
                    envelope(10404)
                } else {
                    envelope(10002)
                }
            }
        val tokens = MemorySessionTokens("access-A", "refresh-A", SessionScopeRaw.FULL, userId = "account-A")
        val api = client(tokens = tokens)
        val pool = Executors.newFixedThreadPool(5)
        val calls = List(5) { pool.submit<ApiResult> { api.execute(ApiCall("getMe")) } }
        val results = calls.map { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1, refreshes.get())
        assertTrue(results.toString(), results.all { (it as ApiResult.Failure).effect.kind == ErrorEffectKind.CLEAR_SESSION_LOGIN })
        assertEquals("5 originals + 1 refresh, no replay", 6, server.requestCount)
        assertNull(tokens.accessToken)
    }

    @Test
    fun lesson16_barrierWaitersAreNotSentWhenTheBarrierRefreshFails() {
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
        val tokens = MemorySessionTokens("restricted", "refresh-r", SessionScopeRaw.DELETION_ONLY, userId = "account-A")
        val api = client(tokens = tokens)
        val pool = Executors.newFixedThreadPool(3)
        val calls = listOf("getMe", "getDeletion", "getWalletSummary").map { id -> pool.submit<ApiResult> { api.execute(ApiCall(id)) } }
        assertTrue(refreshArrived.await(5, TimeUnit.SECONDS))
        release.countDown()
        val results = calls.map { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()

        assertTrue(results.toString(), results.all { it is ApiResult.SessionEnded })
        assertEquals(1, server.requestCount)
        assertNull(tokens.accessToken)
    }

    // ---------- 17：失败路径的回调抛错也结算每个等待者 ----------

    @Test
    fun lesson17_clearAndEffectCallbacksThrowingStillSettleEveryWaiterOnce() {
        server.dispatcher =
            dispatcher { request ->
                if (request.path == "/v1/auth/refresh") {
                    Thread.sleep(200)
                    envelope(10404)
                } else {
                    envelope(10002)
                }
            }
        val tokens = ThrowingClearTokens(MemorySessionTokens("access-A", "refresh-A", SessionScopeRaw.FULL, userId = "account-A"))
        val hooks = NetworkHooks(onEffect = { error("page callback crashed") })
        val api = client(tokens = tokens, hooks = hooks)
        val pool = Executors.newFixedThreadPool(4)
        val started = System.nanoTime()
        val calls = List(4) { pool.submit<Throwable?> { runCatching { api.execute(ApiCall("getMe")) }.exceptionOrNull() } }
        val outcomes = calls.map { it.get(15, TimeUnit.SECONDS) }
        val waitedMillis = (System.nanoTime() - started) / 1_000_000
        pool.shutdown()

        // 页面回调抛的错原样抛给调用方；刷新窗口照样结算，没有等待者卡到 30 秒超时
        assertTrue(outcomes.toString(), outcomes.all { it is IllegalStateException })
        assertTrue("waited $waitedMillis ms", waitedMillis < 10_000)
        assertFalse(api.refreshCoordinator.refreshing)
        assertEquals(1, api.refreshCoordinator.refreshCalls)
        assertTrue(tokens.clearAttempts.get() >= 1)
        // 清会话写盘失败：会话隔离，之后不再带旧令牌
        server.dispatcher = dispatcher { _ -> envelope(0, "{}") }
        val before = server.requestCount
        runCatching { api.execute(ApiCall("getMe")) }
        assertEquals(before + 1, server.requestCount)
        val last = requests().last()
        assertNull(last.getHeader(HeaderNames.AUTHORIZATION))
    }

    @Test
    fun lesson17_coordinatorSettlesWaitersWhenTheForceUpdateProbeThrows() {
        var completion: ((String?) -> Unit)? = null
        val heard = mutableListOf<String?>()
        val failed = mutableListOf<RefreshRequest>()
        val coordinator =
            RefreshCoordinator(
                refresh = { completion = it },
                replay = { _, _ -> },
                clearSession = {},
                clock = { error("clock failed") },
                onFailure = { failed += it },
                forceUpdateNow = { error("state unreadable") },
            )
        val operation = checkNotNull(ApiOperations.byId("getMe"))
        coordinator.run(listOf(RefreshRequest("a", operation, null, "")), forceUpdate = false)
        coordinator.awaitRefresh(forceUpdate = false) { heard += it }
        checkNotNull(completion).invoke(null)
        checkNotNull(completion).invoke(null)

        assertEquals(1, failed.size)
        assertEquals(listOf<String?>(null), heard)
        assertFalse(coordinator.refreshing)
    }

    // ---------- 18：逻辑调用结束时统一结算未决记录 ----------

    @Test
    fun lesson18_firstSubmissionThrowingAfterRegistrationDoesNotLeaveTheKeyInFlight() {
        val backend = MemoryPendingKeyBackend()
        val pending = PendingKeyStore(backend) { 0L }
        // 登记之后、发出之前抛错（这里让注入的重试策略抛错）
        val api = client(pending = pending, retryPolicy = { error("policy unavailable") })

        assertTrue(runCatching { api.execute(ApiCall("createWithdrawal", body = "{}")) }.isFailure)
        assertEquals(0, server.requestCount)
        assertTrue("never-unknown key is dropped, not stuck in_flight", backend.list("account-A").isEmpty())
        // 下一次提交不被一条永久在途的记录挡住
        server.enqueue(envelope(0, "{}"))
        assertTrue(client(pending = pending).execute(ApiCall("createWithdrawal", body = "{}")) is ApiResult.Success)
    }

    @Test
    fun lesson18_confirmThrowingAfterRegistrationReturnsTheKeyToUnknown() {
        val pending = PendingKeyStore(MemoryPendingKeyBackend()) { 0L }
        val key = unknownWithdrawal(client(pending = pending))
        val throwing = client(pending = pending, retryPolicy = { error("policy unavailable") })

        assertTrue(runCatching { throwing.confirmPending("createWithdrawal") }.isFailure)
        assertEquals(PendingKeyStore.UNKNOWN, pending.state(key).stateRaw)
        server.enqueue(envelope(0, "{}"))
        assertTrue(client(pending = pending).confirmPending("createWithdrawal") is ApiResult.Success)
        assertEquals(PendingKeyStore.DONE, pending.state(key).stateRaw)
    }

    @Test
    fun lesson18_sentAttemptWithoutEnvelopeThenLocallyRejectedRetryIsUnknown() {
        // 注入的重试策略让写接口也重试一次：第一次已发出且超时，重试前被本地拒绝 → 结果未知，不是「没发出」
        val retryAll = { operation: ApiOperation ->
            RetryPolicy.forOperation(operation).copy(autoRetries = 1, backoffMillis = 0, timeoutMillis = 300)
        }
        val backend = MemoryPendingKeyBackend()
        val pending = PendingKeyStore(backend) { 0L }
        val api = client(pending = pending, retryPolicy = retryAll, sleeper = { state = FULL_SUPPORTED.copy(environmentSettled = false) })
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        assertEquals(
            ApiResult.LocalReject(LocalRejectReason.ENVIRONMENT_UNSETTLED),
            api.execute(ApiCall("createWithdrawal", body = "{}")),
        )
        val records = backend.list("account-A")
        assertEquals(listOf(PendingKeyStore.UNKNOWN), records.map { it.stateRaw })
    }

    // ---------- 19：受限会话屏障自动接入强更状态恢复 ----------

    @Test
    fun lesson19_updateRecoveryNotifiedBetweenRequestsStillRaisesTheBarrier() {
        val tokens = MemorySessionTokens("access-A", "refresh-A", SessionScopeRaw.FULL, userId = "account-A")
        val api = client(tokens = tokens)
        server.enqueue(envelope(0, "{}"))
        assertTrue(api.execute(ApiCall("getMe")) is ApiResult.Success)
        // 之后拿到受限会话；两次请求之间强更状态经历 below → supported（只有状态源通知，没有请求观察到 below）
        tokens.save("restricted", "refresh-r", SessionScopeRaw.DELETION_ONLY, "account-A")
        api.onUpdateStateChanged(UpdateRaw.BELOW)
        api.onUpdateStateChanged(UpdateRaw.SUPPORTED)
        server.enqueue(refreshPair("full-two"))
        server.enqueue(envelope(0, "{}"))
        assertTrue(api.execute(ApiCall("getWalletSummary")) is ApiResult.Success)

        val sent = requests()
        assertEquals(listOf("/v1/me", "/v1/auth/refresh", "/v1/wallet/summary"), sent.map { it.path })
        assertEquals("Bearer full-two", sent[2].getHeader(HeaderNames.AUTHORIZATION))
    }

    @Test
    fun lesson19_freshLoginDoesNotTriggerTheColdStartBarrier() {
        val tokens = MemorySessionTokens()
        val api = client(tokens = tokens)
        state = FULL_SUPPORTED
        val login =
            envelope(
                0,
                """{"user_id":"account-A","is_new_user":false,"tokens":{"access_token":"restricted-login",""" +
                    """"access_expires_at":"2026-10-02T13:30:00+08:00","refresh_token":"refresh-login",""" +
                    """"refresh_expires_at":"2026-11-01T11:30:00+08:00","session_scope":"deletion_only"}}""",
            )
        server.enqueue(login)
        server.enqueue(envelope(0, "{}"))
        assertTrue(api.execute(ApiCall("loginBySms", body = "{}")) is ApiResult.Success)
        assertTrue(api.execute(ApiCall("getDeletion")) is ApiResult.Success)

        assertEquals(listOf("/v1/auth/login/sms", "/v1/me/deletion"), requests().map { it.path })
    }

    // ---------- 20：刷新失败的去向按结算那一刻的强更状态 ----------

    @Test
    fun lesson20_refreshFailureUsesTheForceUpdateStateAtSettlement() {
        server.dispatcher =
            dispatcher { request ->
                if (request.path == "/v1/auth/refresh") {
                    // 刷新途中强更检查得出「低于」
                    state = FULL_SUPPORTED.copy(update = UpdateRaw.BELOW)
                    envelope(10404)
                } else {
                    envelope(10002)
                }
            }
        val api = client()
        val failure = api.execute(ApiCall("getMe")) as ApiResult.Failure

        assertEquals(ErrorEffectKind.CLEAR_SESSION_STAY_FORCE_UPDATE, failure.effect.kind)
        assertEquals("ForceUpdate", failure.effect.route)
        assertEquals(ErrorEffectKind.CLEAR_SESSION_STAY_FORCE_UPDATE.raw, api.refreshCoordinator.effectRaw)
    }

    @Test
    fun lesson20_barrierFailureUsesTheStateAtSettlementToo() {
        state = FULL_SUPPORTED.copy(update = UpdateRaw.BELOW)
        server.dispatcher =
            dispatcher { _ ->
                // 刷新途中复查得出已受支持（如最低版本被调低）
                state = FULL_SUPPORTED
                envelope(10404)
            }
        val tokens = MemorySessionTokens("restricted", "refresh-r", SessionScopeRaw.DELETION_ONLY, userId = "account-A")
        val ended = client(tokens = tokens).execute(ApiCall("getDeletion")) as ApiResult.SessionEnded

        assertEquals(ErrorEffectKind.CLEAR_SESSION_LOGIN, ended.effect.kind)
        assertEquals("Login", ended.effect.route)
    }

    // ---------- 21：有明确动作的错误码不降成 Toast ----------

    private fun map(
        code: Int,
        operationId: String = "openLink",
        fromProductCard: Boolean = true,
    ) = ErrorActionMapper.map(
        ErrorActionInput(
            code = code,
            data = JsonObject(emptyMap()),
            operation = checkNotNull(ApiOperations.byId(operationId)),
            headers = emptyMap(),
            isReplay = false,
            state = ErrorActionState(UpdateRaw.SUPPORTED, SessionScopeRaw.FULL, "1.2.0"),
            fromProductCard = fromProductCard,
        ),
    )

    @Test
    fun lesson21_expiredLinkRefetchesTheCardButLinkLandingShowsEmptyState() {
        val card = map(30144)
        assertEquals(ErrorEffectKind.REFETCH_PRODUCT, card.kind)
        assertEquals(ReplayMode.NONE, card.replay)
        val landing = map(30144, fromProductCard = false)
        assertEquals(ErrorEffectKind.PAGE_ACTION, landing.kind)
        assertEquals(ReplayMode.NONE, landing.replay)
    }

    @Test
    fun lesson21_withdrawalButtonIsDisabledAndInFlightConflictKeepsLoading() {
        assertEquals(ErrorEffectKind.DISABLE_BUTTON, map(30306, "createWithdrawal").kind)
        assertEquals(ErrorEffectKind.KEEP_LOADING, map(40901, "createWithdrawal").kind)
    }

    @Test
    fun lesson21_cardContextFlowsFromTheCallIntoTheMapper() {
        server.enqueue(envelope(30144))
        server.enqueue(envelope(30144))
        val api = client()
        val card = api.execute(ApiCall("openLink", pathParams = mapOf("link_id" to "l1"), body = "{}")) as ApiResult.Failure
        val landing =
            api.execute(
                ApiCall("openLink", pathParams = mapOf("link_id" to "l1"), body = "{}", fromProductCard = false),
            ) as ApiResult.Failure
        assertEquals(ErrorEffectKind.REFETCH_PRODUCT, card.effect.kind)
        assertEquals(ErrorEffectKind.PAGE_ACTION, landing.effect.kind)
        assertEquals("no automatic retry", 2, server.requestCount)
    }

    /** error-codes 里动作只是提示的码；其余已分配、未废弃的码一律不得降成 Toast。 */
    @Test
    fun lesson21_onlyPromptOnlyCodesMapToToast() {
        val toasts =
            ErrorCodes.all.values
                .filter { !it.isDeprecated }
                .map { it.code }
                .filter { map(it).kind == ErrorEffectKind.TOAST }
                .toSet()
        val unexpected = toasts - PROMPT_ONLY_CODES
        assertTrue("codes with a concrete action fell back to toast: $unexpected", unexpected.isEmpty())
        assertNotEquals(ErrorEffectKind.TOAST, map(30132).kind)
        assertNotEquals(ErrorEffectKind.TOAST, map(30408).kind)
    }

    private companion object {
        val FULL_SUPPORTED =
            NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, update = UpdateRaw.SUPPORTED)

        /**
         * error-codes 的 action 只是提示（「仅提示」「提示重新输入」「按 data.reason 提示」「不透露对方…」、
         * 10401 不重放只埋点、20901 / 20903 由用户重新提交）的码。20001（字段提示）、20004（重新授权引导）有明确动作，
         * 不在此列（A-R2-08）。
         */
        val PROMPT_ONLY_CODES =
            setOf(
                10401,
                10403,
                20002,
                20003,
                20901,
                20903,
                30131,
                30201,
                30202,
                30203,
                30204,
                30301,
                30303,
                30401,
                30402,
                30403,
                30404,
                30405,
                30406,
                30407,
                30409,
                30410,
                30412,
                30414,
                30415,
                30503,
                30601,
                30603,
                30604,
                30801,
                30805,
                30806,
                44002,
            )
    }
}
