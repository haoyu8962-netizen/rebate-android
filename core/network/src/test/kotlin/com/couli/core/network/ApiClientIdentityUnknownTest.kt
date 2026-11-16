package com.couli.core.network

import com.couli.core.clientcore.ConfigReply
import java.util.Collections
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * FU-AND-STEPUP-SUMMARY 推送前补单（独立写手，先红）：03 §4.2「可选登录接口的发送口径」在安卓网络层的落地。
 *
 * - 第一条：有令牌带上发；确认游客（本机没有会话）不带令牌发；身份未知（本机存着会话但这一刻读不出 = [SessionRead.Unreadable]，
 *   或读得出却没有 access_token = 只有 refresh）默认不发、本地拒绝（session_unreadable）；只有调用点显式声明的才不带令牌发出。
 * - 第二条：转链、报价、搜索、商品详情、页面与卡片数据、Agent 接口，以及其余类型的同意记录（AI、实名、个性化等）都不声明，身份未知时不发。
 * - 第四条：purpose=step_up 的发码必须带本人令牌，没有令牌就不发。
 * - 第五条：固定账号的核对——发送前核对「实际带了这个账号的令牌」，对不上本地拒绝、不降成匿名；对所有鉴权级别都核，每次重试重新核。
 *
 * 本文件只用现有 API（不依赖新开关 `sendsWhenIdentityUnknown`），走真实 [ApiClient] → MockWebServer；零发送一律断言服务端收到 0 个请求。
 * 「声明了照发」的五类直接构造 ApiCall 的用例在 [ApiClientIdentityUnknownDeclaredTest]（依赖新开关名）。
 * 隔离（RawRead.Quarantined）归哪一档不由本文件假设，没有它的用例。
 */
class ApiClientIdentityUnknownTest {
    private lateinit var server: MockWebServer
    private val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())

    /** 不为 null 时按它回答（缺省一律 code=0）。 */
    @Volatile private var answer: ((RecordedRequest) -> MockResponse)? = null

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    return answer?.invoke(request) ?: ok()
                }
            }
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    // ---------------------------------------------------------------- 身份未知 + 不声明的请求：本地拒绝、零发送

    @Test
    fun unreadableSessionRejectsParseInputLocallyAndSendsNothing() {
        val result = client(unreadable()).execute(parseInput())

        assertEquals(ApiResult.LocalReject(LocalRejectReason.SESSION_UNREADABLE), result)
        assertNothingSent()
    }

    @Test
    fun refreshOnlySessionRejectsParseInputLocallyAndSendsNothing() {
        val result = client(refreshOnly()).execute(parseInput())

        assertEquals("有 userId、只有 refresh 没有 access 也是身份未知", ApiResult.LocalReject(LocalRejectReason.SESSION_UNREADABLE), result)
        assertNothingSent()
    }

    @Test
    fun unreadableSessionRejectsOpenLinkLocallyAndSendsNothing() {
        val result = client(unreadable()).execute(openLink())

        assertEquals(ApiResult.LocalReject(LocalRejectReason.SESSION_UNREADABLE), result)
        assertNothingSent()
    }

    @Test
    fun refreshOnlySessionRejectsOpenLinkLocallyAndSendsNothing() {
        val result = client(refreshOnly()).execute(openLink())

        assertEquals(ApiResult.LocalReject(LocalRejectReason.SESSION_UNREADABLE), result)
        assertNothingSent()
    }

    @Test
    fun unreadableSessionRejectsTheAiConsentLocallyAndSendsNothing() {
        val result = client(unreadable()).execute(consent(AI_CONSENT, accepted = true))

        assertEquals(ApiResult.LocalReject(LocalRejectReason.SESSION_UNREADABLE), result)
        assertNothingSent()
    }

    @Test
    fun refreshOnlySessionRejectsTheAiConsentLocallyAndSendsNothing() {
        val result = client(refreshOnly()).execute(consent(AI_CONSENT, accepted = true))

        assertEquals(ApiResult.LocalReject(LocalRejectReason.SESSION_UNREADABLE), result)
        assertNothingSent()
    }

    @Test
    fun unreadableSessionRejectsEveryOtherConsentTypeThatIsNotThePrivacyWithdrawal() {
        val api = client(unreadable())
        val undeclared =
            listOf(
                consent(AI_CONSENT, accepted = false),
                consent("id_verification", accepted = true),
                consent("personalization", accepted = false),
                // 只有 type=privacy 且 accepted=false 才声明：同意隐私政策（accepted=true）不在名单里
                consent("privacy", accepted = true),
            )
        for (call in undeclared) {
            assertEquals(call.body, ApiResult.LocalReject(LocalRejectReason.SESSION_UNREADABLE), api.execute(call))
        }
        assertNothingSent()
    }

    @Test
    fun unreadableSessionRejectsSearchProductPageAndAgentRequests() {
        val api = client(unreadable())
        val undeclared =
            listOf(
                ApiCall("searchProducts", query = "q=phone&platform=taobao"),
                ApiCall("getProduct", pathParams = mapOf("product_key" to "tb_1001")),
                ApiCall("getLink", pathParams = mapOf("link_id" to LINK_ID)),
                ApiCall("getPage", pathParams = mapOf("page_key" to "home")),
                ApiCall("listPageSectionItems", pathParams = mapOf("page_key" to "home", "section_id" to "s1")),
                ApiCall("createAgentSession", body = "{}"),
            )
        for (call in undeclared) {
            assertEquals(call.operationId, ApiResult.LocalReject(LocalRejectReason.SESSION_UNREADABLE), api.execute(call))
        }
        assertNothingSent()
    }

    @Test
    fun refreshOnlySessionRejectsSearchProductPageAndAgentRequests() {
        val api = client(refreshOnly())
        val undeclared =
            listOf(
                ApiCall("searchProducts", query = "q=phone&platform=taobao"),
                ApiCall("getProduct", pathParams = mapOf("product_key" to "tb_1001")),
                ApiCall("getPage", pathParams = mapOf("page_key" to "home")),
                ApiCall("createAgentSession", body = "{}"),
            )
        for (call in undeclared) {
            assertEquals(call.operationId, ApiResult.LocalReject(LocalRejectReason.SESSION_UNREADABLE), api.execute(call))
        }
        assertNothingSent()
    }

    /** 第一条「只有调用点显式声明的才照发」：没声明的登录发码（不带开关直接构造）在身份未知时同样不发。 */
    @Test
    fun anUndeclaredLoginCodeIsRejectedWhenTheIdentityIsUnknown() {
        val result = client(unreadable()).execute(ApiCall("sendSmsCode", body = LOGIN_CODE_BODY))

        assertEquals(ApiResult.LocalReject(LocalRejectReason.SESSION_UNREADABLE), result)
        assertNothingSent()
    }

    // ---------------------------------------------------------------- step_up 发码、非登录的授权尝试：不声明

    @Test
    fun unreadableSessionRejectsTheStepUpCodeEvenWithoutThePanelFlag() {
        val result = client(unreadable()).execute(ApiCall("sendSmsCode", body = STEP_UP_CODE_BODY))

        assertEquals(ApiResult.LocalReject(LocalRejectReason.SESSION_UNREADABLE), result)
        assertNothingSent()
    }

    @Test
    fun refreshOnlySessionRejectsTheStepUpCodeFromThePanel() {
        val call = ApiCall("sendSmsCode", body = STEP_UP_CODE_BODY, requiresSession = true)
        val result = client(refreshOnly()).execute(call)

        assertEquals("step_up 必须带本人令牌：没有 access 就不发", ApiResult.LocalReject(LocalRejectReason.SESSION_UNREADABLE), result)
        assertNothingSent()
    }

    @Test
    fun unreadableSessionRejectsNonLoginOauthAttempts() {
        val api = client(unreadable())
        for (purpose in listOf("step_up", "payout_bind", "reverify")) {
            val call = ApiCall("createOauthAttempt", body = """{"provider":"wechat","purpose":"$purpose"}""")
            assertEquals(purpose, ApiResult.LocalReject(LocalRejectReason.SESSION_UNREADABLE), api.execute(call))
        }
        assertNothingSent()
    }

    @Test
    fun refreshOnlySessionRejectsTheStepUpOauthAttempt() {
        val call = ApiCall("createOauthAttempt", body = """{"provider":"wechat","purpose":"step_up"}""")

        assertEquals(ApiResult.LocalReject(LocalRejectReason.SESSION_UNREADABLE), client(refreshOnly()).execute(call))
        assertNothingSent()
    }

    // ---------------------------------------------------------------- 确认游客：不带令牌照发

    @Test
    fun confirmedGuestSendsParseInputAnonymously() {
        val result = client(MemorySessionTokens()).execute(parseInput())

        assertTrue(result.toString(), result is ApiResult.Success)
        val sent = requests.single()
        assertEquals(PARSE_PATH, sent.path)
        assertNull("确认游客不带令牌", sent.getHeader(HeaderNames.AUTHORIZATION))
    }

    @Test
    fun confirmedGuestSendsTheAiConsentAnonymouslyAsADeviceRecord() {
        val result = client(MemorySessionTokens()).execute(consent(AI_CONSENT, accepted = true))

        assertTrue(result.toString(), result is ApiResult.Success)
        val sent = requests.single()
        assertEquals(CONSENTS_PATH, sent.path)
        assertNull(sent.getHeader(HeaderNames.AUTHORIZATION))
        assertEquals("设备级记录带设备标识", DEVICE_ID, sent.getHeader(HeaderNames.DEVICE_ID))
    }

    // ---------------------------------------------------------------- 有令牌：带上发；会话恢复后同一请求带令牌发出

    @Test
    fun readableSessionCarriesTheTokenOnParseInput() {
        val tokens = ControlledTokens(MemorySessionTokens(ACCESS_A, REFRESH_A, SessionScopeRaw.FULL, ACCOUNT_A))
        val result = client(tokens).execute(parseInput())

        assertTrue(result.toString(), result is ApiResult.Success)
        assertEquals(BEARER_A, requests.single().getHeader(HeaderNames.AUTHORIZATION))
    }

    @Test
    fun afterTheUnreadableSessionRecoversTheSameRequestGoesOutWithTheToken() {
        val tokens = unreadable()
        val api = client(tokens)
        val call = parseInput()
        assertEquals(ApiResult.LocalReject(LocalRejectReason.SESSION_UNREADABLE), api.execute(call))
        assertNothingSent()

        tokens.unreadable = false
        val result = api.execute(call)

        assertTrue(result.toString(), result is ApiResult.Success)
        assertEquals(1, requests.size)
        assertEquals("恢复后带本人令牌", BEARER_A, requests.single().getHeader(HeaderNames.AUTHORIZATION))
    }

    @Test
    fun afterTheAccessTokenIsBackTheSameRequestGoesOutWithTheToken() {
        val tokens = refreshOnly()
        val api = client(tokens)
        val call = consent(AI_CONSENT, accepted = true)
        assertEquals(ApiResult.LocalReject(LocalRejectReason.SESSION_UNREADABLE), api.execute(call))
        assertNothingSent()

        tokens.inner.save("access-A2", "refresh-A2", SessionScopeRaw.FULL, ACCOUNT_A)
        val result = api.execute(call)

        assertTrue(result.toString(), result is ApiResult.Success)
        assertEquals(1, requests.size)
        assertEquals("Bearer access-A2", requests.single().getHeader(HeaderNames.AUTHORIZATION))
    }

    // ---------------------------------------------------------------- 取配置的真实调用点（ApiConfigFetcher）：声明照发，不带令牌

    @Test
    fun configFetcherStillFetchesTheConfigWithoutTokenWhenTheSessionIsUnreadable() {
        val api = client(unreadable())
        answer = { ok("""{"features":{}}""") }

        val reply = ApiConfigFetcher({ api }).fetchNow(null)

        assertEquals("取配置是启动要用到的请求，不能被读不出的旧会话挡住", ConfigReply.OK, reply.kindRaw)
        val sent = requests.single()
        assertEquals("/v1/config", sent.path)
        assertNull(sent.getHeader(HeaderNames.AUTHORIZATION))
    }

    @Test
    fun configFetcherStillFetchesTheConfigWithoutTokenWhenOnlyTheRefreshTokenIsLeft() {
        val api = client(refreshOnly())
        answer = { ok("""{"features":{}}""") }

        val reply = ApiConfigFetcher({ api }).fetchNow(null)

        assertEquals(ConfigReply.OK, reply.kindRaw)
        assertNull(requests.single().getHeader(HeaderNames.AUTHORIZATION))
    }

    // ---------------------------------------------------------------- S3-4：固定账号核对「实际带了这个账号的令牌」

    @Test
    fun pinnedAccountWithUserIdButNoAccessTokenIsAccountMismatchAndSendsNothing() {
        val result = client(refreshOnly()).execute(parseInput(expectedAccount = ACCOUNT_A))

        assertEquals("账号一致但没带上该账号的令牌：不降成匿名", ApiResult.LocalReject(LocalRejectReason.ACCOUNT_MISMATCH), result)
        assertNothingSent()
    }

    @Test
    fun pinnedAccountWhoseTokenBelongsToAnotherAccountIsRejectedAndSendsNothing() {
        val tokens = ControlledTokens(MemorySessionTokens("access-B", "refresh-B", SessionScopeRaw.FULL, ACCOUNT_B))
        val result = client(tokens).execute(parseInput(expectedAccount = ACCOUNT_A))

        assertEquals(ApiResult.LocalReject(LocalRejectReason.ACCOUNT_MISMATCH), result)
        assertNothingSent()
    }

    /** 每次重试重新核、对所有鉴权级别都核：首发带了 A 的令牌；退避期间 access 没了（同一会话世代、账号仍是 A），重试不得匿名发出。 */
    @Test
    fun pinnedAccountIsCheckedAgainOnTheAutomaticRetryAndALostAccessTokenStopsIt() {
        val tokens = ControlledTokens(MemorySessionTokens(ACCESS_A, REFRESH_A, SessionScopeRaw.FULL, ACCOUNT_A))
        answer = { _ -> if (requests.size == 1) MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) else ok() }
        val api = client(tokens, timeoutMillis = SHORT_TIMEOUT_MS, sleeper = { tokens.dropAccess = true })

        val result = api.execute(ApiCall("getMe", expectedAccount = ACCOUNT_A))

        assertEquals(ApiResult.LocalReject(LocalRejectReason.ACCOUNT_MISMATCH), result)
        assertEquals("重试没有发出", 1, requests.size)
        assertEquals("首发带的是 A 的令牌", BEARER_A, requests.single().getHeader(HeaderNames.AUTHORIZATION))
    }

    @Test
    fun pinnedAccountIsCheckedAgainOnTheAutomaticRetryAndAnotherAccountsTokenStopsIt() {
        val tokens = ControlledTokens(MemorySessionTokens(ACCESS_A, REFRESH_A, SessionScopeRaw.FULL, ACCOUNT_A))
        answer = { _ -> if (requests.size == 1) MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) else ok() }
        val otherAccount = SessionSnapshot("access-B", "refresh-B", SessionScopeRaw.FULL, ACCOUNT_B, 0L)
        val api = client(tokens, timeoutMillis = SHORT_TIMEOUT_MS, sleeper = { tokens.other = otherAccount })

        val result = api.execute(ApiCall("getPage", pathParams = mapOf("page_key" to "home"), expectedAccount = ACCOUNT_A))

        assertTrue("令牌属另一账号：本地拒绝 $result", result is ApiResult.LocalReject)
        assertEquals("重试没有发出", 1, requests.size)
        assertEquals(BEARER_A, requests.single().getHeader(HeaderNames.AUTHORIZATION))
    }

    // ---------------------------------------------------------------- 假件

    private fun assertNothingSent() {
        assertEquals("传输层收到 0 个请求", 0, server.requestCount)
        assertTrue(requests.isEmpty())
    }

    private fun client(
        tokens: SessionTokens,
        timeoutMillis: Long = LOCAL_BUDGET_MS,
        sleeper: (Long) -> Unit = {},
    ) = ApiClient(
        identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.2.0", build = "1", channel = "official"),
        baseUrl = { server.url("/").toString() },
        context = { FULL },
        device = FixedDevice,
        tokens = tokens,
        pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
        http = OkHttpClient(),
        sleeper = sleeper,
        retryPolicy = { RetryPolicy.forOperation(it).copy(timeoutMillis = timeoutMillis) },
    )

    private fun unreadable() =
        ControlledTokens(MemorySessionTokens(ACCESS_A, REFRESH_A, SessionScopeRaw.FULL, ACCOUNT_A)).also { it.unreadable = true }

    /** 读得出、有 userId，但只有 refresh_token 没有 access_token。 */
    private fun refreshOnly() = ControlledTokens(MemorySessionTokens(null, REFRESH_A, SessionScopeRaw.FULL, ACCOUNT_A))

    private fun parseInput(expectedAccount: String? = null) =
        ApiCall("parseInput", body = """{"text":"https://m.tb.cn/h.abc","scene":"clipboard"}""", expectedAccount = expectedAccount)

    private fun openLink() = ApiCall("openLink", pathParams = mapOf("link_id" to LINK_ID), body = """{"installed":"yes"}""")

    private fun consent(
        type: String,
        accepted: Boolean,
    ) = ApiCall(
        "recordConsent",
        body = """{"type":"$type","version":1,"accepted":$accepted,"channel":"settings","client_at":"2026-11-16T10:00:00+08:00"}""",
    )

    private fun ok(data: String = "{}") =
        MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("""{"code":0,"msg":"","data":$data,"trace_id":"$TRACE"}""")

    private object FixedDevice : DeviceCredentials {
        override val deviceId: String = DEVICE_ID
        override val installSecret: String = "test-only.install.device-a"
    }

    /**
     * 可控的会话存储（同 KeystoreSessionTokens 的 [SessionReader]）：[unreadable] 读不出（[SessionRead.Unreadable]）；[dropAccess] 读得出但
     * access_token 为空（会话世代不变）；[other] 读出的是另一份会话（会话世代不变）。三者都不改 [inner]。
     */
    private class ControlledTokens(
        val inner: MemorySessionTokens,
    ) : SessionTokens by inner,
        SessionReader {
        @Volatile var unreadable = false

        @Volatile var dropAccess = false

        @Volatile var other: SessionSnapshot? = null

        private fun view(): SessionSnapshot {
            val stored = inner.snapshot()
            val shown = other?.copy(generation = stored.generation) ?: stored
            return if (dropAccess) shown.copy(accessToken = null) else shown
        }

        override fun readSession(): SessionRead = if (unreadable) SessionRead.Unreadable(inner.generation) else SessionRead.Present(view())

        override fun snapshot(): SessionSnapshot = if (unreadable) SessionSnapshot(null, null, null, null, inner.generation) else view()

        override val accessToken: String? get() = snapshot().accessToken
        override val refreshToken: String? get() = snapshot().refreshToken
        override val scope: String? get() = snapshot().scope
        override val userId: String? get() = snapshot().userId
    }

    private companion object {
        const val TRACE = "0199a3b4-5c6d-7e8f-9a0b-1c2d3e4f5a6b"
        const val DEVICE_ID = "device-fixture"
        const val ACCOUNT_A = "account-A"
        const val ACCOUNT_B = "account-B"
        const val ACCESS_A = "access-A"
        const val REFRESH_A = "refresh-A"
        const val BEARER_A = "Bearer access-A"
        const val LINK_ID = "lnk_0001"
        const val AI_CONSENT = "ai_third_party"
        const val PARSE_PATH = "/v1/inputs/parse"
        const val CONSENTS_PATH = "/v1/consents"
        const val LOGIN_CODE_BODY = """{"phone":"13800005678","purpose":"login"}"""
        const val STEP_UP_CODE_BODY = """{"purpose":"step_up","action":"withdraw"}"""
        const val LOCAL_BUDGET_MS = 30_000L
        const val SHORT_TIMEOUT_MS = 300L
        val FULL = NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, update = UpdateRaw.SUPPORTED)
    }
}
