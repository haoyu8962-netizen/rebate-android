package com.couli.app.web

import com.couli.core.bridge.BridgeCallValidity
import com.couli.core.bridge.BridgeCallerState
import com.couli.core.bridge.BridgeCode
import com.couli.core.bridge.BridgeHost
import com.couli.core.bridge.BridgeOutcome
import com.couli.core.bridge.BridgePrivacy
import com.couli.core.bridge.BridgeProtocol
import com.couli.core.bridge.BridgeServices
import com.couli.core.bridge.BridgeUi
import com.couli.core.bridge.BridgeUpdateState
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiClient
import com.couli.core.network.ApiResult
import com.couli.core.network.ClientIdentity
import com.couli.core.network.DeviceCredentials
import com.couli.core.network.MemoryPendingKeyBackend
import com.couli.core.network.MemorySessionTokens
import com.couli.core.network.NetworkContext
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PrivacyRaw
import com.couli.core.network.SessionScopeRaw
import com.couli.core.network.UpdateRaw
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Android 第 2 轮评审 R2-01 / 共性第 45 条：真实 BridgeHost → 与 AppWeb.Services 相同的归属映射（[bridgeOwnership]）→ 真实
 * ApiClient → MockWebServer。服务端对 getMe 一直不回包：
 * - 原生在契约 timeout_ms（auth.getUser 为 10000 毫秒）附近以 90003 结算，不等阻塞中的 HTTP 与退避；
 * - OkHttp 的 Call 到截止时间即被取消，之后不再退避重发：服务端自始至终只收到 1 个请求。
 * 修复前：第一次请求 10 秒超时后 ApiClient 仍退避 500 毫秒发出第二次请求，原生约 20.5 秒才回 90003。
 */
class BridgeCallDeadlineTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ApiClient
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun start() {
        server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        api =
            ApiClient(
                identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.2.0", build = "1", channel = "official"),
                baseUrl = { server.url("/").toString() },
                context = { NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, UpdateRaw.SUPPORTED) },
                device = FixedDevice,
                tokens = MemorySessionTokens("access-A", "refresh-A", SessionScopeRaw.FULL, userId = "account-A"),
                pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
            )
    }

    @After
    fun stop() {
        scope.cancel()
        server.shutdown()
    }

    @Test
    fun aHangingGetUserSettlesAtTheContractDeadlineAndIsNeverResent() {
        val host = BridgeHost(PROTOCOL, { CALLER }, Services(api), NoUi, { BridgeUpdateState.SUPPORTED }, scope)
        val replied = CountDownLatch(1)
        var reply = ""
        val started = System.nanoTime()

        host.handle("""{"v":1,"id":"u-1","method":"auth.getUser","params":{}}""", ORIGIN, true, false) {
            reply = it
            replied.countDown()
        }

        assertTrue("原生没有在截止时间附近结算", replied.await(CONTRACT_TIMEOUT_MS + SLACK_MS, TimeUnit.MILLISECONDS))
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue("不早于契约期限结算：$elapsed", elapsed >= CONTRACT_TIMEOUT_MS - SLACK_MS)
        assertEquals(BridgeCode.TIMEOUT, code(reply))
        // 截止之后再等过一个退避周期以上：不得出现第二个请求
        Thread.sleep(AFTER_DEADLINE_MS)
        assertEquals("截止后不再发出第二个请求", 1, server.requestCount)
    }

    /** 与 AppWeb.Services.user 相同的调用方式：归属经 [bridgeOwnership] 映射（截止时间一并交给 ApiClient）。 */
    private class Services(
        private val api: ApiClient,
    ) : BridgeServices {
        override suspend fun user(validity: BridgeCallValidity): BridgeOutcome =
            when (api.send(ApiCall("getMe", ownership = bridgeOwnership(api.inFlight, validity)))) {
                is ApiResult.Success -> BridgeOutcome.Ok()
                else -> BridgeOutcome.Fail(BridgeCode.TIMEOUT)
            }

        override fun env() = JsonObject(emptyMap())

        override fun config(keys: List<String>) = BridgeOutcome.Ok()

        override suspend fun h5Token(
            readOnly: Boolean,
            validity: BridgeCallValidity,
        ) = BridgeOutcome.Ok()

        override suspend fun login(update: String) = BridgeOutcome.Ok()

        override suspend fun signedRequest(
            method: String,
            path: String,
            body: JsonElement?,
            validity: BridgeCallValidity,
        ) = BridgeOutcome.Ok()

        override suspend fun navOpen(
            route: String,
            params: JsonObject,
            update: String,
        ) = BridgeOutcome.Ok()

        override fun clipboardWrite(text: String) = BridgeOutcome.Ok()

        override suspend fun clipboardRead() = BridgeOutcome.Ok()

        override fun setClipboardAutoDetect(enabled: Boolean) = BridgeOutcome.Ok()

        override suspend fun pushStatus() = BridgeOutcome.Ok()

        override suspend fun requestPermission(
            type: String,
            validity: BridgeCallValidity,
        ) = BridgeOutcome.Ok()

        override fun track(
            name: String,
            props: JsonObject,
        ) = BridgeOutcome.Ok()

        override fun reportRejected(
            method: String,
            code: Int,
        ) = Unit
    }

    private object NoUi : BridgeUi {
        override fun toast(
            text: String,
            long: Boolean,
        ) = Unit

        override fun showLoading(text: String?) = Unit

        override fun hideLoading() = Unit

        override fun setNavBar(params: JsonObject) = true

        override fun close() = Unit
    }

    private object FixedDevice : DeviceCredentials {
        override val deviceId: String? = "device-fixture"
        override val installSecret: String? = "test-only.install.device-a"
    }

    private companion object {
        const val ORIGIN = "https://h5.couli.example"
        const val CONTRACT_TIMEOUT_MS = 10_000L
        const val SLACK_MS = 1_500L
        const val AFTER_DEADLINE_MS = 2_000L
        val PROTOCOL = { BridgeProtocol(setOf(ORIGIN), JsonObject(emptyMap()), emptySet()) }
        val CALLER = BridgeCallerState(BridgePrivacy.CONSENTED, accountKey = "account-A", update = BridgeUpdateState.SUPPORTED)

        fun code(reply: String): Int =
            Json
                .parseToJsonElement(reply)
                .jsonObject
                .getValue("code")
                .jsonPrimitive.int
    }
}
