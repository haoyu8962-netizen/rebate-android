package com.couli.core.network

import com.couli.core.generated.ApiOperation
import com.couli.core.generated.ApiOperations
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy

/**
 * FU-AND-CONTRACT-LOCK-6c97062（D10-2888 / D10-2892）写测共用夹具：真实 [ApiClient] + 内存未决键存储，只把传输层换成 MockWebServer
 * （接线照 ApiClientReviewR1Test）。锁 6c97062 起「敏感操作」以生成物的 [ApiOperation.sensitiveOperation]（契约 x-sensitive-operation）为准：
 * 提现 createWithdrawal 是敏感操作但没有 x-step-up（stepUp = null），用来区分判定依据；对照用仍带 x-step-up 的 savePayoutAccount。
 */
internal class CtLock6c97062Fixture {
    val server = MockWebServer().apply { start() }
    val backend = MemoryPendingKeyBackend()
    val pending = PendingKeyStore(backend) { 0L }
    private val effectLog = mutableListOf<ErrorEffect>()
    val effects: List<ErrorEffect> get() = synchronized(effectLog) { effectLog.toList() }

    @Volatile
    var state = FULL_SUPPORTED

    val api =
        ApiClient(
            identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.2.0", build = "1", channel = "official"),
            baseUrl = { server.url("/").toString() },
            context = { state },
            device = FixedDevice,
            tokens = MemorySessionTokens("access-A", "refresh-A", SessionScopeRaw.FULL, userId = ACCOUNT),
            pendingKeys = pending,
            hooks = NetworkHooks(onEffect = { synchronized(effectLog) { effectLog += it } }),
            sleeper = {},
            retryPolicy = { RetryPolicy.forOperation(it).copy(timeoutMillis = TIMEOUT_MILLIS) },
        )

    fun close() = server.shutdown()

    /** 服务端已收到的下一个请求（等不到为 null）。 */
    fun nextRequest(): RecordedRequest? = server.takeRequest(TAKE_SECONDS, TimeUnit.SECONDS)

    /** 当前账号某个操作的未决记录（账号取自夹具会话）。 */
    fun record(operationId: String): PendingKeyRecord? = pending.pendingFor(operationId, ACCOUNT)

    /** 直接写一条「结果未知」的未决记录（升级前遗留、或上一个进程留下的；不经过网络层登记）。 */
    fun seedUnknown(
        operationId: String,
        key: String,
        body: String,
    ) = backend.write(
        PendingKeyRecord(
            key = key,
            operationId = operationId,
            body = body,
            account = ACCOUNT,
            stateRaw = PendingKeyStore.UNKNOWN,
            everUnknown = true,
        ),
    )

    private object FixedDevice : DeviceCredentials {
        override val deviceId: String? = "device-fixture"
        override val installSecret: String? = "test-only.install.device-a"
    }

    companion object {
        const val ACCOUNT = "account-A"
        const val WITHDRAW = "createWithdrawal"
        const val PAYOUT = "savePayoutAccount"
        const val APPEAL = "submitAppeal"
        const val ABANDON = "abandonIdempotencyKey"
        const val TRACE = "trace-0123456789"
        const val WITHDRAW_BODY = """{"amount_fen":1000}"""
        const val PAYOUT_BODY = """{"payout_method":"alipay","account":"fixture-account"}"""
        const val APPEAL_BODY = """{"content":"fixture appeal"}"""
        private const val TIMEOUT_MILLIS = 300L
        private const val TAKE_SECONDS = 5L

        val FULL_SUPPORTED =
            NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, update = UpdateRaw.SUPPORTED)

        /** 操作 → 用例里的请求体（提现、对照的收款账号、非敏感的申诉）。 */
        val BODIES = mapOf(WITHDRAW to WITHDRAW_BODY, PAYOUT to PAYOUT_BODY, APPEAL to APPEAL_BODY)

        fun operation(id: String): ApiOperation = checkNotNull(ApiOperations.byId(id)) { "生成物里没有 $id" }

        fun envelope(
            code: Int,
            data: String = "null",
        ): MockResponse =
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"code":$code,"msg":"","data":$data,"trace_id":"$TRACE"}""")

        /** 契约「结果未知」的三种传输情形（没有统一外壳）：超时、写出后断连、网关错误页（5xx 非外壳）。 */
        val UNKNOWN_OUTCOMES: List<Pair<String, () -> MockResponse>> =
            listOf(
                "timeout" to { MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) },
                "disconnect" to { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) },
                "gateway_5xx" to { MockResponse().setResponseCode(502).setBody("<html>bad gateway</html>") },
            )

        fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
    }
}
