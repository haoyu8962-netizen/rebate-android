package com.couli.core.network

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * FU-AND-0710，APP-07 共性第 29 条：整体 10405 的 parse 响应也受归属约束。网络线程核对通过后，强更副作用（App 切到主线程后
 * 调 AppUpdate.on10405、Gate 落盘）执行之前要能再复核一次：期间页面离开 / 切 Tab / 撤回同意 / 切环境的，不进强更。
 */
class FollowupAnd0710ForceUpdateTest {
    private lateinit var server: MockWebServer
    private val inFlight = InFlightCalls()

    /** 收到的强更通知（最低版本、复核）——模拟 App 的 main.post：先记下，稍后在「主线程」执行。 */
    private val posted = mutableListOf<Pair<String?, () -> Boolean>>()
    private val plain = mutableListOf<String?>()

    @Volatile
    private var owned = true

    private class FixedDevice : DeviceCredentials {
        override val deviceId: String? = "device-fixture"
        override val installSecret: String? = "test-only.install.device-a"
    }

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() = server.shutdown()

    private fun client(checked: Boolean = true) =
        ApiClient(
            identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.2.0", build = "1", channel = "official"),
            baseUrl = { server.url("/").toString() },
            context = {
                NetworkContext(
                    PrivacyRaw.FULL,
                    environmentSettled = true,
                    credentialsReadable = true,
                    update = UpdateRaw.SUPPORTED,
                )
            },
            device = FixedDevice(),
            tokens = MemorySessionTokens("access-A", "refresh-A", SessionScopeRaw.FULL, userId = "account-A"),
            pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
            hooks =
                if (checked) {
                    NetworkHooks(onForceUpdate = { plain += it }, onForceUpdateChecked = { min, valid -> posted += min to valid })
                } else {
                    NetworkHooks(onForceUpdate = { plain += it })
                },
            inFlight = inFlight,
        )

    private fun whole10405() =
        MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("""{"code":10405,"msg":"","data":{"min_supported_version":"9.0.0"},"trace_id":"trace-0123456789"}""")

    private fun parse(api: ApiClient) =
        api.execute(
            ApiCall(
                "parseInput",
                body = """{"scene":"clipboard","text":"https://e.tb.cn/h.Demo01"}""",
                ownership = CallOwnership(inFlight.epochs()) { owned },
            ),
        )

    /** 「主线程」执行先前记下的强更：复核通过才算进强更（与 AppModule 的接线同一写法）。 */
    private fun runPosted(): List<String?> = posted.filter { it.second() }.map { it.first }.also { posted.clear() }

    @Test
    fun lesson29_aWhole10405ParseInvalidatedBeforeTheMainThreadRunsDoesNotForceUpdate() {
        val api = client()
        // 对照：归属一直有效 → 主线程照常进强更（带回最低版本）
        server.enqueue(whole10405())
        parse(api)
        assertEquals(listOf<String?>("9.0.0"), runPosted())

        // 网络线程核对时仍有效，切到主线程之前离页（stillOwned 变 false）→ 不进强更
        server.enqueue(whole10405())
        parse(api)
        assertEquals("网络线程核对通过时交出强更", 1, posted.size)
        owned = false
        assertTrue("离页后的迟到整体 10405 不进强更", runPosted().isEmpty())

        // 切到主线程之前撤回同意 / 切环境（隐私、环境世代换代）→ 不进强更
        owned = true
        server.enqueue(whole10405())
        parse(api)
        inFlight.onPrivacyRevoked()
        assertTrue(runPosted().isEmpty())
        server.enqueue(whole10405())
        owned = true
        parse(api)
        inFlight.onEnvironmentSwitching()
        assertTrue(runPosted().isEmpty())

        // 响应到达时已失效：网络线程就不交出（原有行为）
        owned = false
        server.enqueue(whole10405())
        parse(api)
        assertTrue(posted.isEmpty())
        assertTrue("有带复核的入口就不再调旧入口", plain.isEmpty())
    }

    @Test
    fun lesson29_withoutTheCheckedHookThePlainHookStillReceivesTheForceUpdate() {
        server.enqueue(whole10405())
        parse(client(checked = false))
        assertEquals(listOf<String?>("9.0.0"), plain)
        assertFalse(posted.isNotEmpty())
    }
}
