package com.couli.core.network

import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** R2 S2-2 / S2-3：发送前闸门的调用次数、拒绝记录与基本模式会话读取边界。 */
class SendGateIdentityGapsTest {
    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setBody("""{"code":0,"msg":"","data":{},"trace_id":"identity-gaps-fix4"}"""))
    }

    @After
    fun stop() {
        server.shutdown()
    }

    @Test
    fun unchangedLoginEpochCallsSendAllowedExactlyOnce() {
        val tokens = session()
        val epoch = tokens.loginEpoch
        val calls = AtomicInteger()
        val api = client(tokens)
        val result =
            api.execute(
                gatedConfig(api) {
                    calls.incrementAndGet()
                    true
                },
            )

        assertTrue("单次成功响应，不触发重试或刷新重放", result is ApiResult.Success)
        assertEquals(1, server.requestCount)
        assertEquals("测试全程未保存、清除或刷新会话", epoch, tokens.loginEpoch)
        assertEquals("世代不变的一次实际发送只核一次发送闸门", 1, calls.get())
    }

    @Test
    fun gateBlockedDuringAdmissionWithoutEpochChangeKeepsRejectionAndSendConsistent() {
        val tokens = AdmissionTokens(session())
        val epoch = tokens.loginEpoch
        val probe = RecordingSendProbe()
        val api = client(tokens)
        val result =
            api.execute(
                gatedConfig(api) {
                    probe.allowed().also {
                        if (probe.calls == 1) tokens.afterGateRead = { probe.limited = true }
                    }
                },
            )

        assertTrue("首次闸门必须放行", probe.firstAllowed)
        assertTrue("限流在闸门之后的 admission 会话读取中生效", tokens.changedInAdmission)
        assertTrue("准入期间共享闸门确已变成拒绝状态", probe.limited)
        assertEquals("改变限流不会推进登录世代", epoch, tokens.loginEpoch)
        assertProbeOutcome(probe, result)
    }

    @Test
    fun basicModeWithAlwaysAllowedSendGateNeverReadsSessionStorage() {
        val api = client(PoisonTokens, privacy = PrivacyRaw.BASIC)
        // 不覆盖 loginEpoch：沿用 SessionTokens 的缺省实现，任何读取都会触发 generation 的毒针。
        val result = api.execute(gatedConfig(api) { true })

        assertTrue("基本模式白名单请求带闸门也应正常发送，不能触发 PoisonTokens", result is ApiResult.Success)
        assertEquals(1, server.requestCount)
    }

    private fun assertProbeOutcome(
        probe: RecordingSendProbe,
        result: ApiResult,
    ) {
        val cancelled = result == ApiResult.LocalReject(LocalRejectReason.CALL_CANCELLED)
        assertEquals("记录了发送前限流拒绝，就必须返回 call_cancelled，不能丢掉拒绝继续写出", probe.rejected, cancelled)
        assertEquals("拒绝记录必须与真实 HTTP 写出一致", if (probe.rejected) 0 else 1, server.requestCount)
        if (!probe.rejected) assertTrue("未复核闸门时，首次放行的请求正常结算", result is ApiResult.Success)
    }

    private fun gatedConfig(
        api: ApiClient,
        allowed: () -> Boolean,
    ) = ApiCall(
        "getConfig",
        ownership = CallOwnership(api.inFlight.epochs(), sendAllowed = allowed) { true },
    )

    private fun client(
        tokens: SessionTokens,
        privacy: String = PrivacyRaw.FULL,
    ) = ApiClient(
        identity = ClientIdentity("couli", "android", "1.0.0", "1", "official"),
        baseUrl = { server.url("/").toString() },
        context = { NetworkContext(privacy, true, true, UpdateRaw.SUPPORTED) },
        device =
            object : DeviceCredentials {
                override val deviceId: String = "identity-gaps-device"
                override val installSecret: String = "identity-gaps-install"
            },
        tokens = tokens,
        pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
    )

    private fun session() = MemorySessionTokens("access-A", "refresh-A", SessionScopeRaw.FULL, "account-A")

    /** 首次闸门仅挂上钩子；之后的准入读取才改变限流，不改会话，不靠读取次数或 sleep 猜时序。 */
    private class AdmissionTokens(
        private val backing: SessionTokens,
    ) : SessionTokens by backing {
        var afterGateRead: (() -> Unit)? = null
        var changedInAdmission = false
            private set

        override fun snapshot(): SessionSnapshot {
            val change = afterGateRead
            afterGateRead = null
            if (change != null) {
                changedInAdmission =
                    Thread.currentThread().stackTrace.any {
                        it.className == ApiClient::class.java.name && it.methodName == "admission"
                    }
                change()
            }
            return backing.snapshot()
        }
    }

    /**
     * 照 core/wallet SendProbe.allowed 的语义：限流时返回 false，并留下本请求的 rejected 记录。
     * core:network 不反向依赖 core:wallet；这里只复制该有状态回调语义，不复制 ApiClient 的闸门判断。
     */
    private class RecordingSendProbe {
        var limited = false
        var rejected = false
            private set
        var calls = 0
            private set
        var firstAllowed = false
            private set

        fun allowed(): Boolean {
            calls += 1
            if (calls == 1) firstAllowed = !limited
            if (limited) {
                rejected = true
                return false
            }
            return true
        }
    }

    /** 沿用 ApiClientIntegrationTest.PoisonTokens：任何会话读取立即失败。 */
    private object PoisonTokens : SessionTokens {
        override val accessToken: String? get() = error("access_token must not be read")
        override val refreshToken: String? get() = error("refresh_token must not be read")
        override val scope: String? get() = error("scope must not be read")
        override val userId: String? get() = error("user_id must not be read")
        override val generation: Long get() = error("generation must not be read")

        override fun snapshot(): SessionSnapshot = error("session must not be read")

        override fun save(
            accessToken: String,
            refreshToken: String,
            scope: String,
            userId: String?,
        ) = error("must not save")

        override fun clear() = Unit

        override fun replaceIf(
            expectedGeneration: Long,
            accessToken: String,
            refreshToken: String,
            scope: String,
        ): Boolean = error("must not save")

        override fun clearIf(expectedGeneration: Long): Boolean = false
    }
}
