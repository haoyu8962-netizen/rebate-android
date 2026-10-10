package com.couli.core.network

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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * APP-09 Android 第 1 轮评审 R1-01、R1-02（APP-09 共性第 14、15 条）：【确认上一笔】/【放弃上一笔】从点击处带上账号、原键与归属（[PendingTarget]），
 * 网络层在读取记录、占用记录与实际发送（含管线内部续发）前都核对；不按 operationId 改选当前账号的另一条记录。本地阻断时原键保持待确认。
 */
class ApiClientPendingTargetTest {
    private lateinit var server: MockWebServer
    private val tokens = MemorySessionTokens("access-A", "refresh-A", SessionScopeRaw.FULL, userId = "account-A")
    private val backend = MemoryPendingKeyBackend()
    private val pending = PendingKeyStore(backend) { 0L }

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() = server.shutdown()

    private class FixedDevice : DeviceCredentials {
        override val deviceId: String? = "device-fixture"
        override val installSecret: String? = "test-only.install.device-a"
    }

    private fun client() =
        ApiClient(
            identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.2.0", build = "1", channel = "official"),
            baseUrl = { server.url("/").toString() },
            context = { NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, UpdateRaw.SUPPORTED) },
            device = FixedDevice(),
            tokens = tokens,
            pendingKeys = pending,
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

    private val withdrawals = AtomicInteger()
    private val abandons = AtomicInteger()

    /** 提现提交一律断连（结果未知）；[then] 回答之后的提现与其他请求。 */
    private fun serve(then: (RecordedRequest) -> MockResponse = { envelope(0, "{}") }) {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/v1/idempotency-keys/abandon") abandons.incrementAndGet()
                    if (request.path == "/v1/withdrawals" && withdrawals.incrementAndGet() <= unknownCount) {
                        return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
                    }
                    return then(request)
                }
            }
    }

    @Volatile private var unknownCount = 0

    private fun unknownWithdrawal(api: ApiClient): String {
        unknownCount += 1
        val failure = api.execute(ApiCall("createWithdrawal", body = """{"amount_fen":1000}""")) as ApiResult.TransportFailure
        assertTrue(failure.pendingKeyUnknown)
        return checkNotNull(failure.idempotencyKey)
    }

    private fun ownership(
        stillOwned: () -> Boolean = { true },
        sendAllowed: () -> Boolean = { true },
    ) = CallOwnership(CallEpochs(0, 0), sendAllowed = sendAllowed, stillOwned = stillOwned)

    @Test
    fun r1_01_clickedAccountAKeyNeverConfirmsOrAbandonsAccountBRecord() {
        serve()
        val api = client()
        val keyA = unknownWithdrawal(api)
        // 点击之后、执行之前切到账号 B，而 B 也有同类的未决记录
        tokens.clear()
        tokens.save("access-B", "refresh-B", SessionScopeRaw.FULL, "account-B")
        val keyB = unknownWithdrawal(api)
        val sent = withdrawals.get()
        val target = PendingTarget("account-A", keyA, ownership())

        assertEquals(ApiResult.LocalReject(LocalRejectReason.ACCOUNT_MISMATCH), api.confirmPending("createWithdrawal", null, target))
        assertEquals(ApiResult.LocalReject(LocalRejectReason.ACCOUNT_MISMATCH), api.abandonPending("createWithdrawal", target))
        assertEquals("没有替 B 发出确认", sent, withdrawals.get())
        assertEquals("没有替 B 发出作废", 0, abandons.get())
        assertEquals(PendingKeyStore.UNKNOWN, pending.state(keyB, "account-B").stateRaw)
        assertEquals(PendingKeyStore.UNKNOWN, pending.state(keyA, "account-A").stateRaw)
    }

    @Test
    fun r1_01_recordReplacedByAnotherKeyIsNotTouched() {
        serve()
        val api = client()
        val key = unknownWithdrawal(api)
        val sent = withdrawals.get()
        val stale = PendingTarget("account-A", "key-already-ended", ownership())

        assertEquals(ApiResult.LocalReject(LocalRejectReason.PENDING_KEY_UNRESOLVED), api.confirmPending("createWithdrawal", null, stale))
        assertEquals(ApiResult.LocalReject(LocalRejectReason.PENDING_KEY_UNRESOLVED), api.abandonPending("createWithdrawal", stale))
        assertEquals(sent, withdrawals.get())
        assertEquals(0, abandons.get())
        assertEquals(PendingKeyStore.UNKNOWN, pending.state(key, "account-A").stateRaw)
    }

    @Test
    fun r1_01_sessionChangedAfterClickBlocksBeforeReadingRecord() {
        serve()
        val api = client()
        val key = unknownWithdrawal(api)
        val sent = withdrawals.get()
        val target = PendingTarget("account-A", key, ownership(stillOwned = { false }))

        assertEquals(ApiResult.LocalReject(LocalRejectReason.CALL_CANCELLED), api.confirmPending("createWithdrawal", null, target))
        assertEquals(ApiResult.LocalReject(LocalRejectReason.CALL_CANCELLED), api.abandonPending("createWithdrawal", target))
        assertEquals(sent, withdrawals.get())
        assertEquals(PendingKeyStore.UNKNOWN, pending.state(key, "account-A").stateRaw)
    }

    @Test
    fun r1_02_sharedDeadlineBlocksFirstSendAndKeepsKeyPending() {
        serve()
        val api = client()
        val key = unknownWithdrawal(api)
        val sent = withdrawals.get()
        val target = PendingTarget("account-A", key, ownership(sendAllowed = { false }))

        assertEquals(ApiResult.LocalReject(LocalRejectReason.CALL_CANCELLED), api.confirmPending("createWithdrawal", null, target))
        assertEquals(ApiResult.LocalReject(LocalRejectReason.CALL_CANCELLED), api.abandonPending("createWithdrawal", target))
        assertEquals(sent, withdrawals.get())
        assertEquals(0, abandons.get())
        assertEquals("本地阻断：原键保持待确认", PendingKeyStore.UNKNOWN, pending.state(key, "account-A").stateRaw)
    }

    @Test
    fun r1_02_sharedDeadlineAlsoBlocksPipelineResendAfterRefresh() {
        val allowed = AtomicBoolean(true)
        val refreshed = AtomicInteger()
        serve { request ->
            when (request.path) {
                "/v1/withdrawals" -> {
                    // 确认得到 10002：网络层会刷新令牌后沿用原键续发；此时另一请求收到 42901，共享截止时间生效
                    allowed.set(false)
                    envelope(10002)
                }

                "/v1/auth/refresh" -> {
                    refreshed.incrementAndGet()
                    envelope(
                        0,
                        """{"access_token":"access-A2","access_expires_at":"2026-10-02T13:30:00+08:00","refresh_token":"refresh-A2",""" +
                            """"refresh_expires_at":"2026-11-01T11:30:00+08:00","session_scope":"full"}""",
                    )
                }

                else -> {
                    envelope(0, "{}")
                }
            }
        }
        val api = client()
        val key = unknownWithdrawal(api)
        val target = PendingTarget("account-A", key, ownership(sendAllowed = { allowed.get() }))

        val result = api.confirmPending("createWithdrawal", null, target)
        assertTrue("续发被发送前闸门拦下：$result", result !is ApiResult.Success)
        assertEquals("首次提交 + 确认各一次，刷新后的续发没有写出", 2, withdrawals.get())
        assertEquals(PendingKeyStore.UNKNOWN, pending.state(key, "account-A").stateRaw)
    }

    // ---------------------------------------------------------------- Android 第 2 轮 R2-01（共性第 39 条）

    @Test
    fun r2_01_firstSubmitClickedByAQueuedWhileSessionBecomesBIsNotSentNorRegisteredToB() {
        serve()
        val api = client()
        // A 点击【提交】：账号在点击处固定；请求排队期间会话换成 B
        val clicked =
            ApiCall(
                "createWithdrawal",
                body = """{"amount_fen":1000}""",
                summary = """{"amount_fen":1000}""",
                expectedAccount = "account-A",
            )
        tokens.clear()
        tokens.save("access-B", "refresh-B", SessionScopeRaw.FULL, "account-B")

        assertEquals(ApiResult.LocalReject(LocalRejectReason.ACCOUNT_MISMATCH), api.execute(clicked))
        assertEquals("没有带 B 的凭据发出", 0, withdrawals.get())
        assertTrue("没有登记到 B 的未决记录", backend.list("account-B").isEmpty())
        assertTrue(backend.list("account-A").isEmpty())

        // 同一账号仍在会话中：照常发送并按 A 登记
        tokens.clear()
        tokens.save("access-A", "refresh-A", SessionScopeRaw.FULL, "account-A")
        assertTrue(api.execute(clicked) is ApiResult.Success)
        assertEquals(1, withdrawals.get())
    }

    @Test
    fun r2_01_stepUpReplayClickedByAAlsoStopsWhenSessionBecameB() {
        serve()
        val api = client()
        tokens.clear()
        tokens.save("access-B", "refresh-B", SessionScopeRaw.FULL, "account-B")
        val replay =
            ApiCall(
                "createWithdrawal",
                body = """{"amount_fen":1000}""",
                idempotencyKey = "key-from-a",
                stepUpToken = "fixture-step-up-token",
                expectedAccount = "account-A",
            )
        assertEquals(ApiResult.LocalReject(LocalRejectReason.ACCOUNT_MISMATCH), api.execute(replay))
        assertEquals(0, withdrawals.get())
        assertTrue(backend.list("account-B").isEmpty())
    }
}
