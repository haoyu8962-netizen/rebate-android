package com.couli.core.network

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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * APP-13a 共性第 7 条、G58、编排者修正：requestDeletion / bindPhone 从未结果未知的键收到非终结响应（10007、5xxxx、10405、表外码）保留为 held，
 * 下一次只放行同一个键（原键原体）；42901、40901、20001–20003、44001、10001 / 10004 / 10005 之后键不再使用；提现、收款账号保持原口径。
 * 同时核对无请求体的签名 POST（申请注销、撤回注销）不抛错、照常发出（共性第 1 条）。存储与请求头一起断言。
 */
class App13aHeldKeyTest {
    private lateinit var server: MockWebServer
    private val tokens = MemorySessionTokens("access-A", "refresh-A", SessionScopeRaw.FULL, userId = "account-A")
    private val backend = MemoryPendingKeyBackend()
    private val pending = PendingKeyStore(backend) { 0L }
    private val requests = mutableListOf<RecordedRequest>()
    private val sent = AtomicInteger()

    @Volatile private var answer: (RecordedRequest) -> MockResponse = { envelope(0, "{}") }

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    synchronized(requests) { requests += request }
                    sent.incrementAndGet()
                    return answer(request)
                }
            }
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

    private fun lastKey(): String? = synchronized(requests) { requests.last().getHeader(HeaderNames.IDEMPOTENCY_KEY) }

    private fun deletion(
        api: ApiClient,
        key: String,
    ): ApiResult = api.execute(ApiCall("requestDeletion", idempotencyKey = key))

    private fun bind(
        api: ApiClient,
        key: String,
        body: String = """{"phone":"13900001234","code":"123456"}""",
    ): ApiResult = api.execute(ApiCall("bindPhone", body = body, idempotencyKey = key))

    @Test
    fun emptyBodySignedPostIsSentWithSignature() {
        val api = client()
        answer = { envelope(0, """{"status":"cooling"}""") }
        val result = deletion(api, "key-empty")
        assertTrue(result is ApiResult.Success)
        val request = synchronized(requests) { requests.single() }
        assertEquals("/v1/me/deletion", request.path)
        assertEquals(0L, request.bodySize)
        assertNotNull("无请求体也带签名", request.getHeader(HeaderNames.SIGN))
        assertEquals(RequestSigner.sha256Hex(ByteArray(0)).length, 64)
        // 撤回注销：不签名、无请求体，照常发出（不当成已取消）
        val cancel = api.execute(ApiCall("cancelDeletion"))
        assertTrue(cancel is ApiResult.Success)
        assertEquals(2, sent.get())
    }

    @Test
    fun strictOperationKeepsHeldAfter50401AndOnlySameKeyMaySend() {
        val api = client()
        answer = { envelope(50401) }
        assertTrue(deletion(api, "key-1") is ApiResult.Failure)
        val record = pending.pendingFor("requestDeletion", "account-A")
        assertEquals("记录保留", PendingKeyStore.HELD, record?.stateRaw)
        assertEquals("key-1", record?.key)
        assertFalse("held 不是结果未知", record!!.everUnknown)
        // 换新键被拒（不发）
        val before = sent.get()
        assertEquals(ApiResult.LocalReject(LocalRejectReason.PENDING_KEY_UNRESOLVED), deletion(api, "key-2"))
        assertEquals(before, sent.get())
        // 同一个键再发：放行，请求头同键；成功后结束
        answer = { envelope(0, """{"status":"cooling"}""") }
        assertTrue(deletion(api, "key-1") is ApiResult.Success)
        assertEquals("key-1", lastKey())
        assertNull(pending.pendingFor("requestDeletion", "account-A"))
    }

    @Test
    fun heldKeyCannotBeConfirmedOrAbandoned() {
        val api = client()
        answer = { envelope(10007, """{"stage":"cooling"}""") }
        deletion(api, "key-h")
        assertEquals(PendingKeyStore.HELD, pending.state("key-h", "account-A").stateRaw)
        assertFalse(pending.confirm("key-h", "c", "account-A"))
        assertFalse(pending.abandon("key-h", "c", "account-A"))
        assertFalse(pending.wouldStartUserAction("key-h", "account-A"))
    }

    @Test
    fun heldReleaseReturnsToHeldNotDeleted() {
        val store = PendingKeyStore(MemoryPendingKeyBackend()) { 0L }
        assertTrue(store.beforeSend("bindPhone", "k", "{}", "a", holder = "h1"))
        store.onResponse("k", "envelope_10405", "h1")
        assertEquals(PendingKeyStore.HELD, store.state("k", "a").stateRaw)
        // 再次提交同键：在途
        assertTrue(store.prepareSend("bindPhone", "k", "{}", "a", holder = "h2"))
        assertEquals(PendingKeyStore.IN_FLIGHT, store.state("k", "a").stateRaw)
        // 这次没发出就结束：回到 held
        store.release("k", "h2")
        assertEquals(PendingKeyStore.HELD, store.state("k", "a").stateRaw)
        // 别的键被拒
        assertFalse(store.prepareSend("bindPhone", "k2", "{}", "a", holder = "h3"))
        // 冷启动：held 保持 held
        store.signOut()
        store.coldStart("a")
        assertEquals(PendingKeyStore.HELD, store.state("k", "a").stateRaw)
    }

    @Test
    fun endingCodesDropStrictKey() {
        listOf(42901, 40901, 20001, 20002, 20003, 44001, 10001, 10004, 10005).forEach { code ->
            val store = PendingKeyStore(MemoryPendingKeyBackend()) { 0L }
            assertTrue(store.beforeSend("bindPhone", "k$code", "{}", "a", holder = "h"))
            store.onResponse("k$code", "envelope_$code", "h")
            assertEquals("$code 之后键不再使用", PendingKeyStore.NONE, store.state("k$code", "a").stateRaw)
        }
        listOf(10007, 50001, 50401, 10405, 10003, 10999, 20999, 40999).forEach { code ->
            val store = PendingKeyStore(MemoryPendingKeyBackend()) { 0L }
            assertTrue(store.beforeSend("requestDeletion", "k$code", "", "a", holder = "h"))
            store.onResponse("k$code", "envelope_$code", "h")
            assertEquals("$code 之后保留", PendingKeyStore.HELD, store.state("k$code", "a").stateRaw)
        }
        // 终结：成功、3xxxx、20903
        listOf("envelope_success", "envelope_30416", "envelope_20903").forEach { kind ->
            val store = PendingKeyStore(MemoryPendingKeyBackend()) { 0L }
            assertTrue(store.beforeSend("requestDeletion", "k", "", "a", holder = "h"))
            store.onResponse("k", kind, "h")
            assertEquals(PendingKeyStore.DONE, store.state("k", "a").stateRaw)
        }
    }

    @Test
    fun everUnknownStrictKeyStaysUnknown() {
        val store = PendingKeyStore(MemoryPendingKeyBackend()) { 0L }
        assertTrue(store.beforeSend("requestDeletion", "k", "", "a", holder = "h"))
        store.onResponse("k", PendingKeyStore.NO_ENVELOPE, "h")
        assertTrue(store.confirm("k", "c", "a"))
        store.onResponse("k", "envelope_50401", "c")
        assertEquals(PendingKeyStore.UNKNOWN, store.state("k", "a").stateRaw)
        assertTrue(store.confirm("k", "c2", "a"))
        store.onResponse("k", "envelope_42901", "c2")
        assertEquals("出现过结果未知的不因 42901 结束", PendingKeyStore.UNKNOWN, store.state("k", "a").stateRaw)
    }

    @Test
    fun nonStrictOperationsKeepOriginalRule() {
        val api = client()
        answer = { r -> if (r.path == "/v1/withdrawals") envelope(50401) else envelope(0, "{}") }
        api.execute(ApiCall("createWithdrawal", body = """{"amount_fen":1000}""", idempotencyKey = "w-1"))
        assertNull("提现从未未知的 5xxxx 仍删除记录", pending.pendingFor("createWithdrawal", "account-A"))
        assertFalse(PendingKeyStore.holdsKey("savePayoutAccount", "envelope_50401"))
    }

    @Test
    fun strictTimeoutStillUnknown() {
        val api = client()
        answer = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START) }
        val result = bind(api, "b-1")
        assertTrue(result is ApiResult.TransportFailure)
        assertEquals(PendingKeyStore.UNKNOWN, pending.state("b-1", "account-A").stateRaw)
        assertEquals("""{"phone":"13900001234","code":"123456"}""", pending.pendingFor("bindPhone", "account-A")?.body)
    }
}
