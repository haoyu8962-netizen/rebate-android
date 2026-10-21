package com.couli.feature.login

import com.couli.core.network.ApiClient
import com.couli.core.network.ClientIdentity
import com.couli.core.network.DeviceCredentials
import com.couli.core.network.MemoryPendingKeyBackend
import com.couli.core.network.MemorySessionTokens
import com.couli.core.network.NetworkContext
import com.couli.core.network.NetworkHooks
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PrivacyRaw
import com.couli.core.network.RetryPolicy
import com.couli.core.network.UpdateRaw
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertTrue

/** 真实 ApiClient。navigationTicket 是 send 的第一步，在本地拒绝和 IO 挂起之前，计数不会漏掉本地拒绝。 */
internal class PendingInvite17cFixture(
    scope: CoroutineScope,
) : AutoCloseable {
    val pending = PendingInviteCode()
    val tokens = MemorySessionTokens()
    val entries: MutableList<String?> = Collections.synchronizedList(mutableListOf())
    val bodies: MutableList<JsonObject> = Collections.synchronizedList(mutableListOf())
    var privacy = PrivacyRaw.FULL
    var observePendingAtEntry = true

    @Volatile var answer: () -> MockResponse = { envelope(20002) }
    private val holds = mutableListOf<CountDownLatch>()
    private val server =
        MockWebServer().apply {
            dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        bodies += Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        return answer()
                    }
                }
            start()
        }
    val api =
        ApiClient(
            identity = ClientIdentity("couli", "android", "1.0.0", "1", "official"),
            baseUrl = { server.url("/").toString() },
            context = { NetworkContext(privacy, true, true, UpdateRaw.SUPPORTED) },
            device =
                object : DeviceCredentials {
                    override val deviceId = "fixture-device"
                    override val installSecret = "fixture-secret"
                },
            tokens = tokens,
            pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { NOW },
            hooks =
                NetworkHooks(navigationTicket = {
                    entries += if (observePendingAtEntry) pending.peek() else null
                    null
                }),
            sleeper = {},
            retryPolicy = { RetryPolicy.forOperation(it).copy(timeoutMillis = 30_000L) },
        )
    val service = LoginService(api, tokens, Host, sessionScope = scope, pendingInvite = pending, clock = { NOW })

    suspend fun submit(
        restricted: Boolean = false,
        typed: String = "",
    ): LoginOutcome = service.login("13800138000", "123456", NOW, typed, restricted)

    fun hold(response: MockResponse): Pair<CompletableDeferred<Unit>, CountDownLatch> {
        val arrived = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        holds += release
        answer = {
            arrived.complete(Unit) // 假服务确实收到请求后才允许测试制造断连/在途换码。
            assertTrue("测试必须释放响应", release.await(30, TimeUnit.SECONDS))
            response
        }
        return arrived to release
    }

    fun code(index: Int): String? = bodies[index]["invite_code"]?.jsonPrimitive?.content

    override fun close() {
        holds.forEach { it.countDown() }
        server.shutdown()
    }

    private object Host : LoginHost {
        override fun updateState() = AfterLoginUpdate.SUPPORTED

        override fun hasPendingAction() = false

        override fun closeLogin() = Unit

        override fun resumePending() = Unit

        override fun discardPending() = Unit

        override fun enterForceUpdate() = Unit

        override fun sessionChanged() = Unit

        override fun sessionSynced() = Unit

        override fun sessionEnded() = Unit
    }

    companion object {
        const val NOW = 1_700_000_000_000L

        fun envelope(
            code: Int,
            data: String = "null",
        ): MockResponse =
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"code":$code,"msg":"","data":$data,"trace_id":"fixture-17c"}""")

        fun success(
            result: String = "bound",
            code: String = "null",
        ): MockResponse =
            envelope(
                0,
                """{"user_id":"fixture-user","is_new_user":true,"tokens":{"access_token":"fixture-access",""" +
                    """"access_expires_at":"2099-01-01T00:00:00Z","refresh_token":"fixture-refresh",""" +
                    """"refresh_expires_at":"2099-01-01T00:00:00Z","session_scope":"full"},""" +
                    """"invite_bind":{"result":"$result","code":$code}}""",
            )
    }
}
