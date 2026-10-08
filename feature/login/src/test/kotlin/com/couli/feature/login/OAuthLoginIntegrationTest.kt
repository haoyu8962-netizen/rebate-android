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
import com.couli.core.network.SessionSnapshot
import com.couli.core.network.SessionTokens
import com.couli.core.network.UpdateRaw
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * APP-05b 本地假服务集成测试（任务书 §2.6）：真实 [ApiClient] + [LoginService] + [OAuthLoginService] / [BindPhoneService]，MockWebServer
 * 当假服务，可控的假微信适配器。覆盖：第三方登录成功、取消、迟到回调丢弃（离页、超时）、新号进绑手机引导（待办暂缓）、老用户先查
 * phone_bound、绑手机成功、被占用进并号、并号确认 / 取消、登录中另一会话变化时不误存（APP-05b 共性第 1 条），以及 F-AND-14
 * （APP-05a 共性第 24、25 条）的可控交错。
 */
class OAuthLoginIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ApiClient
    private lateinit var login: LoginService
    private lateinit var oauth: OAuthLoginService
    private lateinit var bind: BindPhoneService
    private val memory = MemorySessionTokens()
    private val tokens = HookedTokens(memory)
    private val host = RecordingHost()
    private val adapter = ControllableAdapter()
    private val phoneBinding = PhoneBindingState()
    private val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())
    private val now = 1_700_000_000_000L

    @Volatile
    private var answer: (RecordedRequest) -> MockResponse = ::defaultAnswer

    @Volatile
    private var meBound = true

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    return answer(request)
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
        login = LoginService(api, tokens, host, LegalVersions(1, 1)) { now }
        oauth = OAuthLoginService(api, tokens, login, listOf(adapter), phoneBinding, LegalVersions(1, 1), timeoutMillis = TIMEOUT_MS)
        bind = BindPhoneService(api, tokens, login, phoneBinding, host) { now }
    }

    @After
    fun stop() {
        adapter.release(ThirdPartyAuthResult.of(ThirdPartyAuthKind.CANCELLED))
        server.shutdown()
    }

    private fun defaultAnswer(request: RecordedRequest): MockResponse =
        when (request.path) {
            ATTEMPTS -> envelope(0, """{"attempt_id":"attempt-1","nonce":"$NONCE","expire_at":"2099-01-01T00:00:00+08:00"}""")
            WECHAT -> envelope(0, loggedIn(isNew = false))
            ME -> envelope(0, """{"user_id":"user-one","phone_bound":$meBound}""")
            else -> envelope(0, "{}")
        }

    private fun paths(): List<String> = requests.toList().mapNotNull { it.path }

    // ---- 第三方登录

    @Test
    fun anExistingUserLogsInWithOnlyTheCredentialAndResumesAfterPhoneBoundIsKnown() =
        runBlocking {
            host.pending = true
            adapter.immediate = credential(NONCE)
            val outcome = oauth.loginWith(OAuthProvider.WECHAT, now)

            assertEquals(OAuthLoginOutcome.LOGGED_IN, outcome.stateRaw)
            assertEquals(OAuthLoginRoute.DONE, outcome.routeRaw)
            assertEquals("access-one", memory.accessToken)
            waitUntil { "held_ready" in host.events }
            assertEquals(listOf(ATTEMPTS, WECHAT, ME), paths())
            // BR-ID-04 细则：只上送 attempt_id 与授权 code（加协议版本与同意时刻），不带 open_id / nickname 等
            val body = Json.parseToJsonElement(requests[1].body.readUtf8()).jsonObject
            assertEquals(setOf("attempt_id", "code", "legal_versions", "consent_at"), body.keys)
            assertEquals("attempt-1", body.getValue("attempt_id").jsonPrimitive.content)
            val attempt = Json.parseToJsonElement(requests[0].body.readUtf8()).jsonObject
            assertEquals("login", attempt.getValue("purpose").jsonPrimitive.content)
            // 共性第 2、3 条：登录成功只重置计时、暂缓；phone_bound 查清后才经守卫恢复
            assertTrue(host.events.indexOf("prerequisite_done_held") < host.events.indexOf("held_ready"))
            assertFalse("登录时不顺带恢复", "session_changed" in host.events)
            assertEquals(true, phoneBinding.get(memory.loginEpoch))
            assertEquals(listOf("oauth-attempt-1"), oauth.session.submitted)
        }

    @Test
    fun anExistingUserWhosePhoneBoundCannotBeReadKeepsThePendingActionAndCountsAsUnbound() =
        runBlocking {
            host.pending = true
            adapter.immediate = credential(NONCE)
            answer = { request -> if (request.path == ME) envelope(50001, "null") else defaultAnswer(request) }
            val outcome = oauth.loginWith(OAuthProvider.WECHAT, now)

            assertEquals(OAuthLoginOutcome.LOGGED_IN, outcome.stateRaw)
            assertTrue(outcome.held)
            waitUntil { paths().contains(ME) }
            Thread.sleep(POLL_MS * 5)
            assertFalse("查询失败：不按已查清恢复", "held_ready" in host.events)
            // 共性第 14 条（R1-03）：解除这次登录的暂缓，交给守卫按「未验证」恢复
            waitUntil { "held_unverified" in host.events }
            assertTrue("未知不当已绑（共性第 2 条）", phoneBinding.treatAsUnbound(memory.loginEpoch))
        }

    /**
     * 共性第 12 条（R1-02）：第三方老用户登录后发出的 getMe 很慢、读到 phone_bound=false；期间用户绑定成功、新查询读到 true。旧的 false
     * 最后才到：不能覆盖绑定结果。
     */
    @Test
    fun aStaleFalseFromBeforeBindingArrivingLastDoesNotOverwriteTheBinding() =
        runBlocking {
            host.pending = true
            adapter.immediate = credential(NONCE)
            val firstMe = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val meCount = AtomicInteger(0)
            answer = { request ->
                when (request.path) {
                    ME -> {
                        if (meCount.incrementAndGet() == 1) {
                            firstMe.countDown()
                            releaseFirst.await(WAIT_S, TimeUnit.SECONDS)
                            envelope(0, """{"user_id":"user-one","phone_bound":false}""")
                        } else {
                            envelope(0, """{"user_id":"user-one","phone_bound":true}""")
                        }
                    }

                    else -> {
                        defaultAnswer(request)
                    }
                }
            }
            try {
                assertEquals(OAuthLoginOutcome.LOGGED_IN, oauth.loginWith(OAuthProvider.WECHAT, now).stateRaw)
                assertTrue(withContext(Dispatchers.IO) { firstMe.await(WAIT_S, TimeUnit.SECONDS) })
                val epoch = memory.loginEpoch

                assertEquals(BindPhoneAction.DONE, bind.bind(PHONE, "123456").actionRaw)
                assertEquals("绑定成功、新查询读到 true", true, phoneBinding.get(epoch))
            } finally {
                releaseFirst.countDown()
            }
            // 旧查询（绑定前发出）的 false 最后到达
            waitUntil { "held_unverified" in host.events || "held_ready" in host.events }
            Thread.sleep(POLL_MS * 5)
            assertEquals("旧的 false 被丢弃", true, phoneBinding.get(memory.loginEpoch))
            assertFalse(phoneBinding.treatAsUnbound(memory.loginEpoch))
        }

    @Test
    fun aNewThirdPartyUserGoesToTheBindPhoneGuideWithThePendingActionHeld() =
        runBlocking {
            host.pending = true
            adapter.immediate = credential(NONCE)
            answer = { request -> if (request.path == WECHAT) envelope(0, loggedIn(isNew = true)) else defaultAnswer(request) }
            val outcome = oauth.loginWith(OAuthProvider.WECHAT, now)

            assertEquals(OAuthLoginOutcome.LOGGED_IN, outcome.stateRaw)
            assertEquals(OAuthLoginRoute.BIND_PHONE_GUIDE, outcome.routeRaw)
            assertTrue(outcome.held)
            assertEquals(false, phoneBinding.get(memory.loginEpoch))
            assertEquals("新号不查 getMe", listOf(ATTEMPTS, WECHAT), paths())
            assertTrue("登录这一步完成：重置计时", "prerequisite_done_held" in host.events)
            assertFalse("引导结束前不恢复待办", "resume_pending" in host.events || "held_ready" in host.events)
        }

    @Test
    fun cancellingInTheProviderSubmitsNothing() =
        runBlocking {
            adapter.immediate = ThirdPartyAuthResult.of(ThirdPartyAuthKind.CANCELLED)
            val outcome = oauth.loginWith(OAuthProvider.WECHAT, now)
            assertEquals(OAuthLoginOutcome.CANCELLED, outcome.stateRaw)
            assertEquals(listOf(ATTEMPTS), paths())
            assertNull(memory.accessToken)
            assertTrue(oauth.session.submitted.isEmpty())
        }

    @Test
    fun aCallbackArrivingAfterLeavingTheLoginPageIsDroppedAndNeverSubmitted() =
        runBlocking {
            val running = async(Dispatchers.Default) { oauth.loginWith(OAuthProvider.WECHAT, now) }
            assertTrue(withContext(Dispatchers.IO) { adapter.launched.await(WAIT_S, TimeUnit.SECONDS) })
            // 用户离开登录页：作废尝试，等待立即结束（不等 SDK）
            login.invalidateAttempt()
            val outcome = withTimeout(TimeUnit.SECONDS.toMillis(WAIT_S)) { running.await() }
            assertEquals(LoginOutcome.STALE, outcome.stateRaw)
            // 回前台后 SDK 才回来：只记为丢弃
            adapter.release(credential(NONCE))
            waitUntil { oauth.session.dropped.isNotEmpty() }
            assertEquals(listOf(DroppedCallback("oauth-attempt-1", 1)), oauth.session.dropped)
            assertEquals(listOf(ATTEMPTS), paths())
            assertNull(memory.accessToken)
        }

    @Test
    fun aTimedOutAttemptDropsTheLateCallback() =
        runBlocking {
            val outcome = oauth.loginWith(OAuthProvider.WECHAT, now)
            assertEquals(OAuthLoginOutcome.TIMEOUT, outcome.stateRaw)
            assertEquals("error.20004", outcome.textKey)
            adapter.release(credential(NONCE))
            waitUntil { oauth.session.dropped.isNotEmpty() }
            assertEquals(listOf(ATTEMPTS), paths())
            assertNull(memory.accessToken)
        }

    /** APP-05b 共性第 1 条：授权等待期间保存了另一账号的会话 → 整个尝试终止，旧授权不提交、不覆盖新会话。 */
    @Test
    fun anotherSessionSavedWhileAuthorizingEndsTheAttemptWithoutOverwritingIt() =
        runBlocking {
            val running = async(Dispatchers.Default) { oauth.loginWith(OAuthProvider.WECHAT, now) }
            assertTrue(withContext(Dispatchers.IO) { adapter.launched.await(WAIT_S, TimeUnit.SECONDS) })
            memory.save("access-other", "refresh-other", SessionScopeRaw.FULL, "user-other")
            adapter.release(credential(NONCE))
            val outcome = withTimeout(TimeUnit.SECONDS.toMillis(WAIT_S)) { running.await() }

            assertEquals(LoginOutcome.STALE, outcome.stateRaw)
            assertEquals("没有发出登录请求", listOf(ATTEMPTS), paths())
            assertEquals("另一账号的会话没有被覆盖", "access-other", memory.accessToken)
            assertTrue(oauth.session.submitted.isEmpty())
        }

    @Test
    fun aStateThatDoesNotMatchTheNonceIsNotSubmitted() =
        runBlocking {
            adapter.immediate = credential("forged-state")
            val outcome = oauth.loginWith(OAuthProvider.WECHAT, now)
            assertEquals(OAuthLoginErrorAction.REAUTHORIZE, outcome.stateRaw)
            assertEquals(listOf(ATTEMPTS), paths())
        }

    @Test
    fun errorsMapToTheirPageActions() =
        runBlocking {
            adapter.immediate = credential(NONCE)
            answer = { request -> if (request.path == WECHAT) envelope(44001, "null") else defaultAnswer(request) }
            assertEquals(OAuthLoginErrorAction.RISK_BLOCKED, oauth.loginWith(OAuthProvider.WECHAT, now).stateRaw)

            answer = { request ->
                if (request.path ==
                    WECHAT
                ) {
                    envelope(42901, "null").setResponseCode(429).setHeader("Retry-After", "30")
                } else {
                    defaultAnswer(request)
                }
            }
            val limited = oauth.loginWith(OAuthProvider.WECHAT, now)
            assertEquals(OAuthLoginErrorAction.RETRY_AFTER, limited.stateRaw)
            assertEquals(30_000L, limited.disableMillis)

            answer = { request -> if (request.path == WECHAT) envelope(50305, """{"provider":"wechat"}""") else defaultAnswer(request) }
            val unavailable = oauth.loginWith(OAuthProvider.WECHAT, now)
            assertEquals(OAuthLoginErrorAction.PROVIDER_UNAVAILABLE, unavailable.stateRaw)
            assertEquals(OAuthProvider.WECHAT, unavailable.provider)
            assertNull(memory.accessToken)
        }

    @Test
    fun theReleaseAdapterIsNeverShownAndNeverSendsAnything() =
        runBlocking {
            val release = OAuthLoginService(api, tokens, login, listOf(UnavailableWechatAdapter()), phoneBinding)
            assertTrue(release.visibleProviders().isEmpty())
            assertEquals(OAuthLoginOutcome.UNAVAILABLE, release.loginWith(OAuthProvider.WECHAT, now).stateRaw)
            assertTrue(requests.isEmpty())
            assertEquals(listOf(OAuthProvider.WECHAT), oauth.visibleProviders())
            assertEquals(LoginOutcome.AGREEMENT_REQUIRED, oauth.loginWith(OAuthProvider.WECHAT, null).stateRaw)
        }

    // ---- 绑手机与并号

    @Test
    fun bindingSendsABindCodeThenRefreshesMeAndCompletesThePrerequisite() =
        runBlocking {
            memory.save("access-one", "refresh-one", SessionScopeRaw.FULL, "user-one")
            phoneBinding.set(memory.loginEpoch, false)
            answer = { request ->
                when (request.path) {
                    SMS -> envelope(0, """{"resend_after_sec":60,"expires_in_sec":300}""")
                    else -> defaultAnswer(request)
                }
            }
            val sent = bind.sendCode(PHONE)
            assertEquals(SmsCountdown.STATE_SENT, sent.stateRaw)
            val sms = Json.parseToJsonElement(requests.last().body.readUtf8()).jsonObject
            assertEquals("bind", sms.getValue("purpose").jsonPrimitive.content)
            assertTrue("与登录页共用同一号码的截止时间", login.resendRemainingSec(PHONE) > 0)

            val outcome = bind.bind(PHONE, "123456")
            assertEquals(BindPhoneAction.DONE, outcome.actionRaw)
            assertEquals(listOf(SMS, BIND, ME), paths())
            assertNull("首次绑定不带 step-up", requests[1].getHeader("X-Step-Up-Token"))
            assertEquals(true, phoneBinding.get(memory.loginEpoch))
            assertTrue("phone_bound" in host.events)
        }

    @Test
    fun anOccupiedMergeableNumberAsksForConfirmationAndConfirmingSendsNoMergeRequest() =
        runBlocking {
            memory.save("access-one", "refresh-one", SessionScopeRaw.FULL, "user-one")
            answer = { request ->
                if (request.path == BIND) {
                    envelope(30411, """{"reason":"mergeable","merge_ticket":"ticket-placeholder"}""")
                } else {
                    defaultAnswer(request)
                }
            }
            val outcome = bind.bind(PHONE, "123456")
            assertEquals(BindPhoneAction.MERGE_CONFIRM, outcome.actionRaw)
            assertEquals("error.30411.mergeable", outcome.textKey)
            assertEquals("138****8000", outcome.maskedPhone)
            assertTrue(bind.mergePending)

            // 确认：锁定契约没有并号接口——不伪造请求、不保存会话，明确「暂不可用」
            val before = requests.size
            assertEquals(MergeResult(MergeDecision.UNAVAILABLE, false), bind.decideMerge(MergeDecision.CONFIRM))
            assertEquals(before, requests.size)
            assertFalse(bind.mergePending)
            assertEquals("access-one", memory.accessToken)

            // 再来一次：取消 → 不并号
            bind.bind(PHONE, "123456")
            assertEquals(MergeResult(MergeDecision.CANCELLED, false), bind.decideMerge(MergeDecision.CANCEL))
            assertFalse("phone_bound" in host.events)
        }

    @Test
    fun anOccupiedNumberThatCannotMergeAndFieldErrorsPointAtTheirInputs() =
        runBlocking {
            memory.save("access-one", "refresh-one", SessionScopeRaw.FULL, "user-one")
            answer = { request -> if (request.path == BIND) envelope(30411, "null") else defaultAnswer(request) }
            val occupied = bind.bind(PHONE, "123456")
            assertEquals(BindPhoneAction.ERROR, occupied.actionRaw)
            assertEquals("error.30411", occupied.textKey)
            assertFalse(bind.mergePending)

            answer = { request -> if (request.path == BIND) envelope(20002, "null") else defaultAnswer(request) }
            assertEquals(LoginErrorMapper.FIELD_CODE, bind.bind(PHONE, "123456").field)
            answer = { request -> if (request.path == BIND) envelope(44001, "null") else defaultAnswer(request) }
            assertEquals("error.44001", bind.bind(PHONE, "123456").textKey)
            assertEquals(BindOutcome.PHONE_INVALID, bind.bind("12ab", "123456").actionRaw)
        }

    /**
     * 共性第 15 条（R1-04）：账号 A 点【绑定】后，请求已切到 IO 线程、还没写出时另一账号 B 的会话保存了 → 绑定请求不得发出（不能用 B 的
     * 令牌绑定 A 输入的手机号）。
     */
    @Test
    fun anotherAccountSavedAfterDispatchKeepsTheBindRequestFromGoingOut() =
        runBlocking {
            memory.save("access-one", "refresh-one", SessionScopeRaw.FULL, "user-one")
            phoneBinding.set(memory.loginEpoch, false)
            tokens.beforeSnapshot = { memory.save("access-two", "refresh-two", SessionScopeRaw.FULL, "user-two") }

            val outcome = bind.bind(PHONE, "123456")

            assertEquals(BindOutcome.STALE, outcome.actionRaw)
            assertFalse("绑定请求没有发出", paths().contains(BIND))
            assertEquals("access-two", memory.accessToken)
            assertFalse("phone_bound" in host.events)
            assertEquals(null, phoneBinding.get(memory.loginEpoch))
        }

    /** 共性第 15 条（R1-04）：发码同样在入口固定登录世代，派发后另一账号保存 → 不发出。 */
    @Test
    fun anotherAccountSavedAfterDispatchKeepsTheBindCodeFromGoingOut() =
        runBlocking {
            memory.save("access-one", "refresh-one", SessionScopeRaw.FULL, "user-one")
            tokens.beforeSnapshot = { memory.save("access-two", "refresh-two", SessionScopeRaw.FULL, "user-two") }

            val outcome = bind.sendCode(PHONE)

            assertEquals(BindOutcome.STALE, outcome.stateRaw)
            assertFalse("发码请求没有发出", paths().contains(SMS))
            assertEquals(0, login.resendRemainingSec(PHONE))
        }

    /** 共性第 10、16 条（R1-05）：发码收到 10007 stage=cooling → 结果带导航动作（DeleteAccount）；processing 只提示。 */
    @Test
    fun aBindCodeDuringDeletionCoolingKeepsTheNavigationAndProcessingOnlyShowsAMessage() =
        runBlocking {
            memory.save("access-one", "refresh-one", SessionScopeRaw.FULL, "user-one")
            answer = { request -> if (request.path == SMS) envelope(10007, """{"stage":"cooling"}""") else defaultAnswer(request) }
            val cooling = bind.sendCode(PHONE)
            assertEquals(BindOutcome.OPEN_ROUTE, cooling.stateRaw)
            assertEquals("DeleteAccount", cooling.route)

            answer = { request -> if (request.path == SMS) envelope(10007, """{"stage":"processing"}""") else defaultAnswer(request) }
            val processing = bind.sendCode(PHONE)
            assertEquals(null, processing.route)
            assertEquals("error.10007", processing.textKey)
        }

    // ---- F-AND-14：APP-05a 共性第 24、25 条

    /**
     * 共性第 24 条：尝试作废与会话保存共用同一同步区。在「核对尝试仍有效」之后、「写入令牌」的过程中发起作废：作废必须等保存完成后
     * 才生效（不能插在中间），这次登录按已作废处理——只同步会话状态，不关页、不恢复待办。
     */
    @Test
    fun invalidatingWhileTheLoginIsBeingSavedWaitsForTheSaveAndLeavesTheFlowStale() =
        runBlocking {
            host.pending = true
            adapter.immediate = credential(NONCE)
            val invalidated = AtomicBoolean(false)
            val done = CountDownLatch(1)
            val finishedDuringSave = AtomicBoolean(false)
            tokens.duringSave = {
                val thread =
                    Thread {
                        login.invalidateAttempt()
                        invalidated.set(true)
                        done.countDown()
                    }
                thread.start()
                thread.join(INTERLEAVE_MS)
                // 作废进不了同步区：保存期间它还没完成
                finishedDuringSave.set(invalidated.get())
                // 保存之后的第一次会话读取等作废完成（让「保存后被作废」这一交错确定地发生）
                tokens.afterSave = done
            }
            val outcome = oauth.loginWith(OAuthProvider.WECHAT, now)
            waitUntil { invalidated.get() }

            assertFalse("作废没有插进核对与写入之间", finishedDuringSave.get())
            assertEquals(LoginOutcome.STALE, outcome.stateRaw)
            assertTrue("session_synced" in host.events)
            assertFalse("close_login" in host.events || "held_ready" in host.events || "resume_pending" in host.events)
        }

    /**
     * 共性第 25 条：登出时原会话已被别处清掉（10404 等）→ Router 的清理只针对原会话（登出开始时的登录世代），不是此刻的世代。
     */
    @Test
    fun logoutAfterTheSessionWasAlreadyClearedEndsOnlyTheOriginalSession() =
        runBlocking {
            memory.save("access-one", "refresh-one", SessionScopeRaw.FULL, "user-one")
            val original = memory.loginEpoch
            val arrived = CountDownLatch(1)
            val release = CountDownLatch(1)
            answer = { request ->
                if (request.path == LOGOUT) {
                    arrived.countDown()
                    release.await(WAIT_S, TimeUnit.SECONDS)
                }
                envelope(0, "{}")
            }
            val running = async(Dispatchers.Default) { login.logout() }
            assertTrue(withContext(Dispatchers.IO) { arrived.await(WAIT_S, TimeUnit.SECONDS) })
            memory.clear() // 原会话在登出等待期间被 10404 清掉
            release.countDown()
            assertTrue(withTimeout(TimeUnit.SECONDS.toMillis(WAIT_S)) { running.await() })
            assertEquals(listOf("session_ended_through:$original"), host.events.filter { it.startsWith("session_ended") })
            assertTrue(memory.loginEpoch > original)
        }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(POLL_MS)
        assertTrue(condition())
    }

    /** 可控的假微信适配器：[immediate] 不为 null 时立即带回；否则等测试 [release]。 */
    private class ControllableAdapter : ThirdPartyAuthAdapter {
        override val provider: String = OAuthProvider.WECHAT

        @Volatile
        var immediate: ThirdPartyAuthResult? = null
        val launched = CountDownLatch(1)
        private val pending = AtomicReference(CompletableDeferred<ThirdPartyAuthResult>())

        fun release(result: ThirdPartyAuthResult) {
            pending.get().complete(result)
        }

        override fun adapterAvailable(): Boolean = true

        override fun installed(): Boolean? = null

        override suspend fun authorize(nonce: String): ThirdPartyAuthResult {
            launched.countDown()
            return immediate ?: pending.get().await()
        }
    }

    /** 委托给内存实现；[duringSave] 在登录保存（saveIf）进行中执行（此时 ApiClient 持有保存同步区）。 */
    private class HookedTokens(
        private val delegate: MemorySessionTokens,
    ) : SessionTokens by delegate {
        @Volatile
        var duringSave: (() -> Unit)? = null

        @Volatile
        var afterSave: CountDownLatch? = null

        /** 下一次读取会话（ApiClient 在 IO 线程上绑定调用归属时）之前执行一次：模拟派发之后、写出之前另一处保存了会话。 */
        @Volatile
        var beforeSnapshot: (() -> Unit)? = null

        override val loginEpoch: Long get() = delegate.loginEpoch

        override fun snapshot(): SessionSnapshot {
            beforeSnapshot?.also { beforeSnapshot = null }?.invoke()
            afterSave?.also { afterSave = null }?.await(WAIT_S, TimeUnit.SECONDS)
            return delegate.snapshot()
        }

        override fun saveIf(
            expectedGeneration: Long,
            accessToken: String,
            refreshToken: String,
            scope: String,
            userId: String?,
        ): Boolean {
            duringSave?.also { duringSave = null }?.invoke()
            return delegate.saveIf(expectedGeneration, accessToken, refreshToken, scope, userId)
        }
    }

    private class RecordingHost :
        LoginHost,
        BindPhoneHost {
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        var pending = false

        override fun updateState() = AfterLoginUpdate.SUPPORTED

        override fun hasPendingAction() = pending

        override fun closeLogin() {
            events += "close_login"
        }

        override fun resumePending() {
            events += "resume_pending"
        }

        override fun discardPending() {
            events += "discard_pending"
        }

        override fun enterForceUpdate() {
            events += "enter_force_update"
        }

        override fun sessionChanged() {
            events += "session_changed"
        }

        override fun sessionSynced() {
            events += "session_synced"
        }

        override fun sessionEnded() {
            events += "session_ended"
        }

        override fun sessionEndedThrough(loginEpoch: Long) {
            events += "session_ended_through:$loginEpoch"
        }

        override fun prerequisiteDoneHeld() {
            events += "prerequisite_done_held"
        }

        override fun heldPendingReady() {
            events += "held_ready"
        }

        override fun heldPendingUnverified() {
            events += "held_unverified"
        }

        override fun phoneBound() {
            events += "phone_bound"
        }
    }

    private object FixtureDevice : DeviceCredentials {
        override val deviceId: String = "device-fixture"
        override val installSecret: String = "install-fixture"
    }

    private companion object {
        const val ATTEMPTS = "/v1/auth/oauth-attempts"
        const val WECHAT = "/v1/auth/login/wechat"
        const val ME = "/v1/me"
        const val BIND = "/v1/me/phone"
        const val SMS = "/v1/auth/sms-codes"
        const val LOGOUT = "/v1/auth/logout"
        const val NONCE = "nonce-placeholder-0001"
        const val PHONE = "13800138000"
        const val WAIT_S = 5L
        const val POLL_MS = 20L
        const val TIMEOUT_MS = 300L
        const val INTERLEAVE_MS = 300L

        fun credential(state: String) = ThirdPartyAuthResult(ThirdPartyAuthKind.CREDENTIAL, code = "code-placeholder", state = state)

        fun envelope(
            code: Int,
            data: String,
        ) = MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("""{"code":$code,"msg":"","data":$data,"trace_id":"trace-000777"}""")

        fun loggedIn(isNew: Boolean) =
            """{"user_id":"user-one","is_new_user":$isNew,"tokens":{"access_token":"access-one",""" +
                """"access_expires_at":"2099-01-01T00:00:00+08:00","refresh_token":"refresh-one",""" +
                """"refresh_expires_at":"2099-01-01T00:00:00+08:00","session_scope":"full"}}"""
    }
}
