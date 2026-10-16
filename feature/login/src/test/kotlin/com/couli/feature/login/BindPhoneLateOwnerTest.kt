package com.couli.feature.login

import com.couli.core.network.ApiClient
import com.couli.core.network.ClientIdentity
import com.couli.core.network.DeviceCredentials
import com.couli.core.network.MemoryPendingKeyBackend
import com.couli.core.network.MemorySessionTokens
import com.couli.core.network.NetworkContext
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PrivacyRaw
import com.couli.core.network.SessionScopeRaw
import com.couli.core.network.UpdateRaw
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
 * FU-AND-0716 第 1 轮评审 R1-01（S0）：绑定 / 确认上一次提交成功后的 getMe（refreshMe）挂起期间，发起时的固定身份失效——退出后另一账号登录、
 * 或同账号同登录世代作用域从 full 变成 deletion_only（编排者补充，鸿蒙 RC-01 同类）——getMe 迟到返回后不得调用共享收尾 [BindPhoneHost.phoneBound]
 * （App 侧它会同步守卫并释放暂缓，新会话暂缓的购买待办随之重放）。对照：身份不变时照常调用一次，且带着发起时的固定身份。
 */
class BindPhoneLateOwnerTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ApiClient
    private lateinit var bind: BindPhoneService
    private val tokens = MemorySessionTokens("access-a", "refresh-a", SessionScopeRaw.FULL, USER_A)
    private val phoneBinding = PhoneBindingState()
    private val host = RecordingHost()
    private val now = 1_700_000_000_000L

    /** 收到 getMe 时放行前先等它；[meArrived] 在收到时倒数。 */
    @Volatile private var meHold: CountDownLatch? = null

    @Volatile private var meArrived = CountDownLatch(1)

    /** 下一次绑定提交断开连接（结果未知 → 待确认）。 */
    @Volatile private var dropNextBind = false

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when (request.path) {
                        ME -> {
                            meArrived.countDown()
                            meHold?.await(WAIT_S, TimeUnit.SECONDS)
                            envelope("""{"user_id":"$USER_A","phone_bound":true}""")
                        }

                        BIND -> {
                            if (dropNextBind) {
                                dropNextBind = false
                                MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                            } else {
                                envelope("{}")
                            }
                        }

                        else -> {
                            envelope("{}")
                        }
                    }
            }
        server.start()
        api =
            ApiClient(
                identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.0.0", build = "1", channel = "official"),
                baseUrl = { server.url("/").toString() },
                context = { NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, UpdateRaw.SUPPORTED) },
                device = FixtureDevice,
                tokens = tokens,
                pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
                sleeper = {},
            )
        val login = LoginService(api, tokens, host, LegalVersions(1, 1)) { now }
        bind = BindPhoneService(api, tokens, login, phoneBinding, host) { now }
    }

    @After
    fun stop() {
        meHold?.countDown()
        server.shutdown()
    }

    /** 另一账号 B 登录（新的登录世代）。 */
    private fun switchToB() = tokens.save("access-b", "refresh-b", SessionScopeRaw.FULL, USER_B)

    /** 同账号、同登录世代刷新成 deletion_only（作用域变化，不换登录世代）。 */
    private fun narrowToDeletionOnly() {
        val epoch = tokens.loginEpoch
        assertTrue(tokens.replaceIf(tokens.generation, "access-a2", "refresh-a2", SessionScopeRaw.DELETION_ONLY))
        assertEquals("同一登录世代", epoch, tokens.loginEpoch)
    }

    /** 首次绑定的提交（getMe 挂起），在 getMe 到达后执行 [during]，再放行；返回绑定结局。 */
    private fun bindWithMeHeld(during: () -> Unit): BindOutcome =
        runBlocking {
            val owner = bind.owner()
            meHold = CountDownLatch(1)
            val running = async(Dispatchers.Default) { bind.bind(PHONE, CODE, owner) }
            assertTrue("getMe 已到达", withContext(Dispatchers.IO) { meArrived.await(WAIT_S, TimeUnit.SECONDS) })
            during()
            meHold?.countDown()
            withTimeout(TimeUnit.SECONDS.toMillis(WAIT_S)) { running.await() }
        }

    /** 首次绑定断开 → 待确认；【确认上一次提交】的 getMe 挂起，到达后执行 [during]，再放行。 */
    private fun confirmWithMeHeld(during: () -> Unit) =
        runBlocking {
            val owner = bind.owner()
            dropNextBind = true
            assertEquals(BindOutcome.PENDING, bind.bind(PHONE, CODE, owner).actionRaw)
            val key = requireNotNull(bind.readPending()) { "首次提交断开后应有待确认记录" }.key
            meHold = CountDownLatch(1)
            val running = async(Dispatchers.Default) { bind.resolvePending(key, owner, abandon = false) }
            assertTrue("getMe 已到达", withContext(Dispatchers.IO) { meArrived.await(WAIT_S, TimeUnit.SECONDS) })
            during()
            meHold?.countDown()
            withTimeout(TimeUnit.SECONDS.toMillis(WAIT_S)) { running.await() }
        }

    @Test
    fun fuand0716_r1_01_aConfirmWhoseGetMeReturnsAfterAccountBLoggedInDoesNotRunTheSharedCompletion() {
        val outcome = confirmWithMeHeld(::switchToB)
        assertEquals("确认本身已成功", com.couli.core.me.account.PhoneChangeOutcome.DONE, outcome?.nextStateRaw)
        assertEquals("B 的会话不收到 A 的共享收尾", emptyList<BindPhoneOwner>(), host.bound)
    }

    @Test
    fun fuand0716_r1_01_aConfirmWhoseGetMeReturnsAfterTheSameSessionTurnedDeletionOnlyDoesNotRunTheSharedCompletion() {
        confirmWithMeHeld(::narrowToDeletionOnly)
        assertEquals("作用域变化即归属失效", emptyList<BindPhoneOwner>(), host.bound)
    }

    @Test
    fun fuand0716_r1_01_aBindWhoseGetMeReturnsAfterTheSameSessionTurnedDeletionOnlyDoesNotRunTheSharedCompletion() {
        bindWithMeHeld(::narrowToDeletionOnly)
        assertEquals(emptyList<BindPhoneOwner>(), host.bound)
    }

    @Test
    fun fuand0716_r1_01_aBindWhoseGetMeReturnsAfterAccountBLoggedInDoesNotRunTheSharedCompletion() {
        bindWithMeHeld(::switchToB)
        assertEquals(emptyList<BindPhoneOwner>(), host.bound)
    }

    /** 对照：身份一直没变，确认与绑定成功都照常做一次共享收尾，带的是发起时的固定身份。 */
    @Test
    fun fuand0716_r1_01_theSharedCompletionStillRunsOnceWithTheFixedOwnerWhenNothingChanged() {
        val owner = bind.owner()
        confirmWithMeHeld {}
        assertEquals(listOf(owner), host.bound)
        meArrived = CountDownLatch(1)
        bindWithMeHeld {}
        assertEquals(listOf(owner, owner), host.bound)
    }

    private class RecordingHost :
        LoginHost,
        BindPhoneHost {
        val bound: MutableList<BindPhoneOwner> = Collections.synchronizedList(mutableListOf())

        override fun updateState() = AfterLoginUpdate.SUPPORTED

        override fun hasPendingAction() = false

        override fun closeLogin() = Unit

        override fun resumePending() = Unit

        override fun discardPending() = Unit

        override fun enterForceUpdate() = Unit

        override fun sessionChanged() = Unit

        override fun sessionSynced() = Unit

        override fun sessionEnded() = Unit

        override fun phoneBound(owner: BindPhoneOwner) {
            bound += owner
        }
    }

    private object FixtureDevice : DeviceCredentials {
        override val deviceId: String = "device-fixture"
        override val installSecret: String = "install-fixture"
    }

    private companion object {
        const val ME = "/v1/me"
        const val BIND = "/v1/me/phone"
        const val USER_A = "user-a"
        const val USER_B = "user-b"
        const val PHONE = "13800138000"
        const val CODE = "123456"
        const val WAIT_S = 10L

        fun envelope(data: String) =
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"code":0,"msg":"","data":$data,"trace_id":"trace-000888"}""")
    }
}
