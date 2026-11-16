package com.couli.app

import android.os.Looper
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiClient
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.ClientIdentity
import com.couli.core.network.DeviceCredentials
import com.couli.core.network.InFlightCalls
import com.couli.core.network.MemoryPendingKeyBackend
import com.couli.core.network.MemorySessionTokens
import com.couli.core.network.NetworkContext
import com.couli.core.network.PageApi
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PrivacyRaw
import com.couli.core.network.SessionRead
import com.couli.core.network.SessionReader
import com.couli.core.network.SessionSnapshot
import com.couli.core.network.SessionTokens
import com.couli.core.network.UpdateRaw
import java.util.Collections
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf

/**
 * FU-AND-STEPUP-SUMMARY 调用点级用例（第二位独立写手）的共用替身：会话存储、真实 [ApiClient] 的构造、假服务的记录、请求入口的拦截与主线程等待。
 * 只换系统 I/O 与网络末端；ApiClient 的三档判定、固定账号核对、写出前守卫都是生产代码。
 */
internal object CallSiteIdentityPin {
    const val ACCOUNT_A = "account-A"
    const val ACCESS_A = "access-A"
    const val REFRESH_A = "refresh-A"
    const val ACCOUNT_B = "account-B"
    const val ACCESS_B = "access-B"
    const val REFRESH_B = "refresh-B"
    const val DEVICE_ID = "device-fixture"
    const val WAIT_MS = 10_000L
    const val POLL_MS = 10L

    /** 会员 A 的会话（可读、有 access）。 */
    fun memberA(): CallSiteTokens = CallSiteTokens(MemorySessionTokens(ACCESS_A, REFRESH_A, "full", ACCOUNT_A))

    /** 确认游客（本机没有会话）。 */
    fun guest(): CallSiteTokens = CallSiteTokens(MemorySessionTokens())

    fun client(
        server: MockWebServer,
        tokens: SessionTokens,
        inFlight: InFlightCalls = InFlightCalls(),
    ): ApiClient =
        ApiClient(
            identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.0.0", build = "1", channel = "official"),
            baseUrl = { server.url("/").toString() },
            context = { NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, UpdateRaw.SUPPORTED) },
            device = Device,
            tokens = tokens,
            pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
            inFlight = inFlight,
            sleeper = {},
        )

    /** 主线程（Robolectric 主 Looper）一边排空一边等 [done]；网络在真实 IO 线程上，所以要真的等。 */
    fun idleUntil(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            assertTrue("等待超时", System.currentTimeMillis() < deadline)
            Thread.sleep(POLL_MS)
        }
    }

    /** 收尾：再排空几轮，让迟到的回调落定（负向断言之前用）。 */
    fun settle() {
        repeat(SETTLE_ROUNDS) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(POLL_MS)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private const val SETTLE_ROUNDS = 20

    private object Device : DeviceCredentials {
        override val deviceId: String = DEVICE_ID
        override val installSecret: String = "install-fixture"
    }
}

/**
 * 可控的会话存储（同 KeystoreSessionTokens 实现 [SessionReader]）：[unreadable] 时这一刻读不出；[refreshOnly] 时读得出、有 userId 却没有 access。
 * 换成游客 / 换账号直接改 [inner]（clear / save，与生产一样推进会话世代与登录世代）。
 */
internal class CallSiteTokens(
    val inner: MemorySessionTokens,
) : SessionTokens by inner,
    SessionReader {
    @Volatile var unreadable = false

    @Volatile var refreshOnly = false

    override fun readSession(): SessionRead = if (unreadable) SessionRead.Unreadable(inner.generation) else SessionRead.Present(snapshot())

    override fun snapshot(): SessionSnapshot {
        val read = inner.snapshot()
        return when {
            unreadable -> SessionSnapshot(null, null, null, null, read.generation)
            refreshOnly -> read.copy(accessToken = null)
            else -> read
        }
    }

    override val accessToken: String? get() = snapshot().accessToken
    override val refreshToken: String? get() = snapshot().refreshToken
    override val scope: String? get() = snapshot().scope
    override val userId: String? get() = snapshot().userId
}

/** 假服务：记下每个到达传输层的请求（方法、路径、头、体），按 [answer] 回答。 */
internal class CallSiteServer(
    private val answer: (RecordedRequest) -> String,
) : Dispatcher() {
    val received: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())

    /** 读过的请求体（RecordedRequest.body 只能读一次）。 */
    val bodies: MutableMap<RecordedRequest, String> = Collections.synchronizedMap(mutableMapOf())

    override fun dispatch(request: RecordedRequest): MockResponse {
        bodies[request] = request.body.readUtf8()
        received += request
        return MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("""{"code":0,"msg":"","data":${answer(request)},"trace_id":"trace-000777"}""")
    }

    fun to(pathPrefix: String): List<RecordedRequest> =
        synchronized(received) {
            received.filter { it.path.orEmpty().startsWith(pathPrefix) }
        }
}

/**
 * 页面交给网络层的入口（PageApi）：记下每次调用与结果；[beforeSend] 在调用点已经取定账号、请求还没交给 ApiClient 的这一刻执行
 * （「点击之后、派发之前」换会话就在这里）。
 */
internal class CallSitePageApi(
    private val inner: PageApi,
    @Volatile var beforeSend: (ApiCall) -> Unit = {},
) : PageApi {
    val calls: MutableList<ApiCall> = Collections.synchronizedList(mutableListOf())
    val results: MutableList<Pair<ApiCall, ApiResult>> = Collections.synchronizedList(mutableListOf())

    override suspend fun send(call: ApiCall): ApiResult {
        calls += call
        beforeSend(call)
        return inner.send(call).also { results += call to it }
    }

    override fun cancelOwned(owner: CallOwnership) = inner.cancelOwned(owner)

    override fun epochs(): CallEpochs = inner.epochs()

    fun resultOf(operation: String): ApiResult? =
        synchronized(results) {
            results.firstOrNull { it.first.operationId == operation }?.second
        }

    fun callOf(operation: String): ApiCall? = synchronized(calls) { calls.firstOrNull { it.operationId == operation } }
}
