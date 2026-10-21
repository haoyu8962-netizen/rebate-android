@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.couli.feature.login

import androidx.lifecycle.viewModelScope
import com.couli.core.network.ApiClient
import com.couli.core.network.ClientIdentity
import com.couli.core.network.DeviceCredentials
import com.couli.core.network.MemoryPendingKeyBackend
import com.couli.core.network.NetworkContext
import com.couli.core.network.NetworkHooks
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PrivacyRaw
import com.couli.core.network.UpdateRaw
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertTrue

/** 只替换 IO、时钟、存储；动作均调用生产 ViewModel。入口计数先于 ApiClient 的本地拒绝及 IO。 */
internal class Login17aFixture(
    restricted: Boolean = false,
    private val useTestMain: Boolean = true,
    private val pumpMain: () -> Unit = {},
) : AutoCloseable {
    val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    val scope = CoroutineScope(SupervisorJob() + if (useTestMain) dispatcher else Dispatchers.Main.immediate)
    var now = EPOCH
    val entries = AtomicInteger()
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    val logins = CopyOnWriteArrayList<HeldLogin17a>()
    val tokens = LoginTokens17a()
    val backend = MethodBackend17a()
    val methods = LastLoginMethodStore(backend)
    val adapter = LoginAdapter17a()
    var sendAnswer: () -> MockResponse = { sent() }

    /** 负向断言用即时应答让误提交也能结算；默认仍保持原有登录应答屏障。 */
    var loginAnswer: (() -> MockResponse)? = null
    var oauthAnswer: () -> MockResponse = { success() }
    val completed = mutableListOf<String>()
    private val server = MockWebServer()

    init {
        if (useTestMain) Dispatchers.setMain(dispatcher)
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    return when (request.path) {
                        SMS -> {
                            sendAnswer()
                        }

                        LOGIN -> {
                            HeldLogin17a(request)
                                .also {
                                    logins += it
                                    loginAnswer?.invoke()?.let(it::release)
                                }.await()
                        }

                        ATTEMPTS -> {
                            envelope(0, """{"attempt_id":"attempt-17a","nonce":"nonce-17a","expire_at":"2099-01-01T00:00:00Z"}""")
                        }

                        WECHAT -> {
                            oauthAnswer()
                        }

                        else -> {
                            envelope(0, "{}")
                        }
                    }
                }
            }
        server.start()
    }

    private val api =
        ApiClient(
            identity = ClientIdentity("couli", "android", "1.0.0", "1", "official"),
            baseUrl = { server.url("/").toString() },
            context = { NetworkContext(PrivacyRaw.FULL, true, true, UpdateRaw.SUPPORTED) },
            device =
                object : DeviceCredentials {
                    override val deviceId = "device-17a"
                    override val installSecret = "secret-17a"
                },
            tokens = tokens,
            pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { now },
            hooks =
                NetworkHooks(navigationTicket = {
                    entries.incrementAndGet()
                    null
                }),
            sleeper = {},
        )
    val service =
        LoginService(
            api,
            tokens,
            Login17aHost(restricted),
            sessionScope = scope,
            pendingInvite = PendingInviteCode(),
            clock = { now },
        ).also {
            it.lastLoginMethod = methods
        }
    val oauth = OAuthLoginService(api, tokens, service, listOf(adapter), PhoneBindingState(), followUpScope = scope)
    val config =
        LoginPageConfig(
            service,
            Keys,
            restricted,
            oauth = oauth,
            thirdParty = listOf("wechat"),
            clock = { now },
            actions =
                LoginPageActions(
                    onLoggedIn = { completed += it.stateRaw },
                    onOAuthLoggedIn = { completed += it.stateRaw },
                ),
        )
    val model = LoginViewModel(config)
    private val ticker: Set<Job>

    init {
        scheduler.runCurrent()
        ticker =
            model.viewModelScope.coroutineContext[Job]!!
                .children
                .toSet()
        model.onPhoneChange(PHONE)
    }

    /** 等所有页面动作协程结束；不把每秒刷新循环算作动作，也不按固定轮次空转后断言零请求。 */
    fun settle() =
        until("页面动作结算") {
            model.viewModelScope.coroutineContext[Job]!!
                .children
                .none { it !in ticker && it.isActive }
        }

    fun until(
        label: String,
        condition: () -> Boolean,
    ) = runBlocking {
        withTimeout(5_000) {
            do {
                scheduler.runCurrent()
                pumpMain()
                if (condition()) return@withTimeout
                delay(1) // 有完成条件和超时的轮询；不是固定等待后猜测任务完成。
            } while (true)
        }
        assertTrue(label, condition())
    }

    fun enterCode() {
        model.onGetCode()
        settle()
        model.onConfirmAgree()
        settle()
        check(model.state.value.step == LoginStep.CODE)
    }

    fun input(
        code: String,
        submits: Boolean,
    ) {
        val before = entries.get()
        model.onCodeChange(code)
        scheduler.runCurrent()
        if (submits) {
            until("自动登录到达真实服务") { entries.get() > before && logins.size > answered }
        } else {
            org.junit.Assert.assertEquals("输入事件处理完后不发请求", before, entries.get())
        }
    }

    private var answered = 0

    fun respond(
        code: Int,
        retryAfter: String? = null,
        sessionScope: String = "full",
    ) {
        until("应答前必须已收到请求") { logins.size > answered }
        val response = if (code == 0) success(sessionScope) else envelope(code)
        if (retryAfter != null) response.setResponseCode(429).setHeader("Retry-After", retryAfter)
        logins[answered++].release(response)
        settle()
    }

    fun advance(millis: Long) {
        now += millis
        model.onPageVisible()
        scheduler.runCurrent()
    }

    fun sends(): Int = requests.count { it.path == SMS }

    override fun close() {
        adapter.cancel()
        logins.forEach { it.release(envelope(20002)) }
        model.viewModelScope.cancel()
        scope.cancel()
        scheduler.runCurrent()
        server.shutdown()
        if (useTestMain) Dispatchers.resetMain()
    }

    object Keys : LoginTexts {
        override fun text(key: String) = key

        override fun format(
            key: String,
            vars: Map<String, String>,
        ) = key + vars.entries.joinToString()
    }

    companion object {
        const val EPOCH = 1_700_000_000_000L
        const val PHONE = "13800138000"
        const val SMS = "/v1/auth/sms-codes"
        const val LOGIN = "/v1/auth/login/sms"
        const val ATTEMPTS = "/v1/auth/oauth-attempts"
        const val WECHAT = "/v1/auth/login/wechat"

        fun envelope(
            code: Int,
            data: String = "null",
        ) = MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("""{"code":$code,"msg":"","data":$data,"trace_id":"trace-123456"}""")

        fun sent() = envelope(0, """{"resend_after_sec":60,"expires_in_sec":300}""")

        fun success(scope: String = "full") =
            envelope(
                0,
                """{"user_id":"user-17a","is_new_user":true,"tokens":{"access_token":"access-17a",""" +
                    """"access_expires_at":"2099-01-01T00:00:00Z","refresh_token":"refresh-17a",""" +
                    """"refresh_expires_at":"2099-01-01T00:00:00Z","session_scope":"$scope"}}""",
            )
    }
}

internal class HeldLogin17a(
    request: RecordedRequest,
) {
    val body: JsonObject = Json.parseToJsonElement(request.body.clone().readUtf8()).jsonObject
    private val released = CountDownLatch(1)

    @Volatile private var response = Login17aFixture.envelope(20002)

    fun release(value: MockResponse) {
        response = value
        released.countDown()
    }

    fun await(): MockResponse {
        check(released.await(10, TimeUnit.SECONDS)) { "未释放登录应答" }
        return response
    }
}
