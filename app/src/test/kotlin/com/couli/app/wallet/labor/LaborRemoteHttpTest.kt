package com.couli.app.wallet.labor

import com.couli.core.network.ApiClient
import com.couli.core.network.ApiResult
import com.couli.core.network.ClientIdentity
import com.couli.core.network.DeviceCredentials
import com.couli.core.network.MemoryPendingKeyBackend
import com.couli.core.network.MemorySessionTokens
import com.couli.core.network.NetworkContext
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PrivacyRaw
import com.couli.core.network.UpdateRaw
import com.couli.feature.wallet.ApiClientWalletApi
import com.couli.feature.wallet.LaborBackend
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * FU-AND-LABOR-AGREEMENT-API：RemoteLaborBackend 经生产形状的 [ApiClientWalletApi] + 真实 [ApiClient]（签名、登录态、幂等键都走网络层真实管线，
 * 只把传输层换成 MockWebServer）发出的线上请求：方法、路径、Bearer、签名三件套、Idempotency-Key 与请求体；同一次签署断连后重试用同一个键。
 */
class LaborRemoteHttpTest {
    private lateinit var server: MockWebServer
    private val tokens = MemorySessionTokens("access-one", "refresh-one", "full", "user-one")
    private val requests = CopyOnWriteArrayList<Seen>()
    private val fetched = CopyOnWriteArrayList<String>()
    private val expectedShas = CopyOnWriteArrayList<String>()
    private val issued = CopyOnWriteArrayList<String>()

    /** 第 n 次（从 0 起）签署的回答；缺省 code 0、data {}。 */
    @Volatile
    private var signReply: (Int) -> MockResponse = { envelope("{}") }

    private class Seen(
        val method: String,
        val path: String,
        val query: String?,
        val body: String,
        val headers: Map<String, String>,
    ) {
        fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

        fun json(): JsonObject = Json.parseToJsonElement(body).jsonObject
    }

    private object FixtureDevice : DeviceCredentials {
        override val deviceId: String = "device-fixture"
        override val installSecret: String = "install-fixture"
    }

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val full = request.path.orEmpty()
                    val path = full.substringBefore('?')
                    val seen =
                        Seen(
                            request.method.orEmpty(),
                            path,
                            full.substringAfter('?', "").ifEmpty { null },
                            request.body.readUtf8(),
                            request.headers.toMap(),
                        )
                    val index = requests.count { it.method == "POST" && it.path == LaborContract.SIGN_PATH }
                    requests += seen
                    return when {
                        seen.method == "GET" && path == LaborContract.GET_PATH -> envelope(LaborContract.needSign.toString())
                        seen.method == "POST" && path == LaborContract.SIGN_PATH -> signReply(index)
                        else -> envelope("null")
                    }
                }
            }
        server.start()
    }

    @After
    fun stop() = server.shutdown()

    private fun envelope(
        data: String,
        code: Int = 0,
    ): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("""{"code":$code,"msg":"","data":$data,"trace_id":"${LaborContract.TRACE}"}""")

    private fun client(): ApiClient =
        ApiClient(
            identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.0.0", build = "1", channel = "official"),
            baseUrl = { server.url("/").toString() },
            context = { NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, UpdateRaw.SUPPORTED) },
            device = FixtureDevice,
            tokens = tokens,
            pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { NOW },
            sleeper = {},
        )

    private fun backend(): LaborBackend {
        val fetch: suspend (String, String) -> String? = { url, expectedSha256 ->
            fetched += url
            expectedShas += expectedSha256
            LaborContract.TEXT
        }
        val newKey: () -> String = { "labor-key-%04d".format(issued.size + 1).also { issued += it } }
        return LaborRemoteReflect.backend(ApiClientWalletApi(client()), fetch, { "user-one" }, newKey)
    }

    private fun seen(
        method: String,
        path: String,
    ): List<Seen> = requests.filter { it.method == method && it.path == path }

    @Test fun currentSendsGetWithBearerAndNoKey() {
        val backend = backend()
        val result = runBlocking { backend.current() }
        val get = seen("GET", LaborContract.GET_PATH).single()
        assertTrue("登录态 Bearer", get.header("Authorization").orEmpty().startsWith("Bearer "))
        assertFalse("带 X-Device-Id", get.header("X-Device-Id").isNullOrEmpty())
        assertNull("读取不带幂等键", get.header("Idempotency-Key"))
        assertNull("没有查询串", get.query)
        assertTrue("没有请求体", get.body.isEmpty())
        assertEquals("全文按 text_url 取", listOf(LaborContract.textUrl), fetched.toList())
        assertEquals("取文时显式带上服务端给的 text_sha256", listOf(LaborContract.sha), expectedShas.toList())
        assertTrue("读取成功", result is ApiResult.Success)
    }

    @Test fun signSendsSignedIdempotentPostWithContractBody() {
        val backend = backend()
        val result = runBlocking { backend.sign(LaborContract.version.toString(), LaborContract.sha) }
        assertTrue("签署成功", result is ApiResult.Success)
        val post = seen("POST", LaborContract.SIGN_PATH).single()
        assertTrue("登录态 Bearer", post.header("Authorization").orEmpty().startsWith("Bearer "))
        assertTrue("X-Sign 是 64 位小写十六进制", SIGN.matches(post.header("X-Sign").orEmpty()))
        assertTrue("X-Timestamp 是 10 位秒", TIMESTAMP.matches(post.header("X-Timestamp").orEmpty()))
        assertTrue("X-Nonce 是 32 位小写十六进制", NONCE.matches(post.header("X-Nonce").orEmpty()))
        val key = post.header("Idempotency-Key")
        assertTrue("幂等键符合契约格式", key != null && LaborContract.KEY_PATTERN.matches(key))
        assertEquals("幂等键是后端给的那一把（不是网络层临时补的）", issued.first(), key)
        assertNull("没有查询串", post.query)
        val body = post.json()
        assertEquals(setOf("version", "text_sha256", "agreed"), body.keys)
        val version = body.getValue("version") as JsonPrimitive
        assertFalse("version 是整数", version.isString)
        assertEquals(LaborContract.version.toString(), version.content)
        assertEquals(LaborContract.sha, (body.getValue("text_sha256") as JsonPrimitive).content)
        val agreed = body.getValue("agreed") as JsonPrimitive
        assertFalse("agreed 是布尔", agreed.isString)
        assertEquals("true", agreed.content)
    }

    @Test fun retryAfterDisconnectReusesKeyAndBody() {
        signReply = { n -> if (n == 0) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) else envelope("{}") }
        val backend = backend()
        val first = runBlocking { backend.sign(LaborContract.version.toString(), LaborContract.sha) }
        assertTrue("首发断连：结果未知", first is ApiResult.TransportFailure)
        val second = runBlocking { backend.sign(LaborContract.version.toString(), LaborContract.sha) }
        assertTrue("重试成功", second is ApiResult.Success)
        val posts = seen("POST", LaborContract.SIGN_PATH)
        assertTrue("至少发出两次", posts.size >= 2)
        assertEquals("同一次签署的重试全程一个幂等键", 1, posts.map { it.header("Idempotency-Key") }.toSet().size)
        assertEquals("请求体不变", 1, posts.map { it.json() }.toSet().size)
    }

    @Test fun businessErrorsComeBackUnchanged() {
        listOf(
            10004 to """{"consent_type":"labor_agreement"}""",
            20001 to """{"fields":["text_sha256","agreed"]}""",
        ).forEach { (code, data) ->
            signReply = { envelope(data, code) }
            val result = runBlocking { backend().sign(LaborContract.version.toString(), LaborContract.sha) }
            val failure = result as? ApiResult.Failure
            assertEquals("$code 原样上抛", code, failure?.code)
            assertEquals("$code 的 data 原样", Json.parseToJsonElement(data), failure?.data)
        }
    }

    private companion object {
        const val NOW = 1_762_566_300_000L
        val SIGN = Regex("^[0-9a-f]{64}$")
        val TIMESTAMP = Regex("^[0-9]{10}$")
        val NONCE = Regex("^[0-9a-f]{32}$")
    }
}
