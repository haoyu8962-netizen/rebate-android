package com.couli.app.followup

import com.couli.core.clientcore.PrivacyStateRaw
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiClient
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.ClientIdentity
import com.couli.core.network.CredentialReadBarrier
import com.couli.core.network.DeviceCredentialStore
import com.couli.core.network.DeviceCredentials
import com.couli.core.network.DeviceRegistry
import com.couli.core.network.InFlightCalls
import com.couli.core.network.LocalRejectReason
import com.couli.core.network.MemoryPendingKeyBackend
import com.couli.core.network.MemorySessionTokens
import com.couli.core.network.NetworkContext
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PendingTarget
import com.couli.core.network.PrivacyRaw
import com.couli.core.network.RetryPolicy
import com.couli.core.network.SessionRead
import com.couli.core.network.SessionReader
import com.couli.core.network.SessionScopeRaw
import com.couli.core.network.SessionSnapshot
import com.couli.core.network.SessionTokens
import com.couli.core.network.StoredDevice
import com.couli.core.network.UpdateRaw
import com.couli.core.privacy.PrivacyGate
import com.couli.core.privacy.PrivacyStore
import com.couli.feature.login.LoginHost
import com.couli.feature.login.LoginService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
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
 * FU-AND-0714 第 2 项（F-X-03 Android）：「状态先发布、屏障后竖」时序。三条路径——切环境、撤回同意、退出登录——对外可见的状态（switching、
 * 撤回标记、会话结束）一发布，第一个清理器还没运行时，旧请求恢复、新请求发起、会话查询、设备凭据入口都**零读取**（令牌 access / refresh /
 * scope / account 与设备 device_id / install_secret）、**零发送**；确认 / 放弃上一笔保留 HEAD 的拒绝分类（F-X-03 补充 ①–⑤）。
 *
 * 读取计数从状态发布之后开始算；令牌经 [CountingTokens]（数到每一次读到存储的读取），设备凭据经 [CountingDevice] / [CountingStore]。
 */
class FollowupAnd0714TimingTest {
    private lateinit var server: MockWebServer
    private val requests = AtomicInteger()
    private val paths = java.util.Collections.synchronizedList(mutableListOf<String>())

    @Volatile private var disconnectMe = false

    @Volatile private var logoutHold: CountDownLatch? = null

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                @Suppress("ReturnCount") // 按路径逐项
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.incrementAndGet()
                    paths += request.path.orEmpty()
                    if (request.path == "/v1/me" &&
                        disconnectMe
                    ) {
                        return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    }
                    if (request.path == "/v1/auth/logout") logoutHold?.await(WAIT_SECONDS, TimeUnit.SECONDS)
                    if (request.path == "/v1/withdrawals") return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
                    return envelope(0, """{"user_id":"account-A","phone_bound":true}""")
                }
            }
        server.start()
    }

    @After
    fun stop() {
        logoutHold?.countDown()
        server.shutdown()
    }

    private fun envelope(
        code: Int,
        data: String,
    ) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"code":$code,"msg":"","data":$data,"trace_id":"trace-0123456789"}""")

    /** 数到每一次读存储（令牌值、作用域、账号、整组读取）；世代、登录世代是内存计数，不算读取。 */
    private class CountingTokens(
        val inner: MemorySessionTokens,
    ) : SessionTokens,
        SessionReader {
        val reads = AtomicInteger()

        /** 删除「写盘失败」：世代照样前进、令牌留在存储里（KeystoreSessionTokens 的写入失败形态）。 */
        @Volatile var clearFails = false

        override val accessToken: String? get() = count().accessToken
        override val refreshToken: String? get() = count().refreshToken
        override val scope: String? get() = count().scope
        override val userId: String? get() = count().userId
        override val generation: Long get() = inner.generation + bumped
        override val loginEpoch: Long get() = inner.loginEpoch + bumped

        @Volatile private var bumped = 0L

        private fun count(): SessionSnapshot {
            reads.incrementAndGet()
            return inner.snapshot().copy(generation = generation)
        }

        override fun snapshot(): SessionSnapshot = count()

        override fun readSession(): SessionRead = SessionRead.Present(count())

        override fun save(
            accessToken: String,
            refreshToken: String,
            scope: String,
            userId: String?,
        ) = inner.save(accessToken, refreshToken, scope, userId)

        override fun clear() {
            if (clearFails) {
                bumped += 1
                error("写入加密存储失败")
            }
            inner.clear()
        }

        override fun replaceIf(
            expectedGeneration: Long,
            accessToken: String,
            refreshToken: String,
            scope: String,
        ): Boolean = generation == expectedGeneration && inner.replaceIf(inner.generation, accessToken, refreshToken, scope)

        override fun clearIf(expectedGeneration: Long): Boolean {
            if (generation != expectedGeneration) return false
            clear()
            return true
        }
    }

    private class CountingDevice : DeviceCredentials {
        val reads = AtomicInteger()
        override val deviceId: String? get() = "device-fixture".also { reads.incrementAndGet() }
        override val installSecret: String? get() = "test-only.install.device-a".also { reads.incrementAndGet() }
    }

    private class CountingStore(
        @Volatile var stored: StoredDevice = StoredDevice.Present("device-fixture", "test-only.install.device-a"),
    ) : DeviceCredentialStore {
        val reads = AtomicInteger()

        override fun read(): StoredDevice = stored.also { reads.incrementAndGet() }

        override fun save(
            deviceId: String,
            installSecret: String,
        ) {
            stored = StoredDevice.Present(deviceId, installSecret)
        }

        override fun clear() {
            stored = StoredDevice.None
        }
    }

    private class MemoryStore : PrivacyStore {
        override var decisionRaw: String = "consented"
        override var consentedVersion: String? = "1"
        override var consentedAtMillis: Long? = 1L
        override var basicMode: Boolean = false
        override var withdrawalCleanupPending: Boolean = false
    }

    private val tokens = CountingTokens(MemorySessionTokens("access-A", "refresh-A", SessionScopeRaw.FULL, userId = "account-A"))
    private val backend = MemoryPendingKeyBackend()
    private val pending = PendingKeyStore(backend) { 0L }
    private val inFlight = InFlightCalls()
    private val barrier = CredentialReadBarrier()
    private val gate = EnvironmentGate { EnvironmentStartupReconciler.Outcome.UNCHANGED }.apply { settleAtStartup() }
    private val privacy = PrivacyGate(MemoryStore(), { 0L }, "1").apply { credentialBarrier = barrier::raise }
    private val device = CountingDevice()

    /** 退避里要做的事（[ApiClient] 的 sleeper：旧请求断连后在这里等）。 */
    @Volatile private var duringBackoff: () -> Unit = {}

    private fun context() =
        NetworkContext(
            // 最坏情况：基本模式还没发布（隐私结论仍是 full），只靠待清理标记与环境闸门
            privacy = PrivacyRaw.FULL,
            environmentSettled = gate.isSettled,
            credentialsReadable = !privacy.credentialAccess.cleanupPending,
            update = UpdateRaw.SUPPORTED,
        )

    private fun client(devices: DeviceCredentials = device) =
        ApiClient(
            identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.2.0", build = "1", channel = "official"),
            baseUrl = { if (gate.isSettled) server.url("/").toString() else null },
            context = ::context,
            device = devices,
            tokens = tokens,
            pendingKeys = pending,
            http = OkHttpClient(),
            sleeper = { duringBackoff() },
            retryPolicy = { RetryPolicy.forOperation(it).copy(timeoutMillis = 3_000) },
            inFlight = inFlight,
            readBarrier = barrier,
        )

    /** 与 AppModule 的环境切换监听同一做法：屏障里关闸门（switching 对外可见）；第一个清理器（作废在途）此处**不**运行。 */
    private fun publishSwitching() = barrier.raise { gate.closeForSwitch() }

    private fun resetCounts() {
        tokens.reads.set(0)
        device.reads.set(0)
        requests.set(0)
        paths.clear()
    }

    /** 旧请求：发布之前开始的 getMe，断连后停在退避里；退避期间发布状态，恢复后零读取、零发送。 */
    private fun oldRequestResumesAfter(publish: () -> Unit): ApiResult {
        val api = client()
        disconnectMe = true
        duringBackoff = {
            publish()
            resetCounts()
        }
        return api.execute(ApiCall("getMe"))
    }

    // ---------------------------------------------------------------- 切环境

    @Test
    fun fx03_switch_gate_closed_first_cleaner_not_run_old_request_reads_and_sends_nothing() {
        val result = oldRequestResumesAfter(::publishSwitching)

        assertEquals(ApiResult.LocalReject(LocalRejectReason.ENVIRONMENT_UNSETTLED), result)
        assertEquals("令牌零读取", 0, tokens.reads.get())
        assertEquals("设备凭据零读取", 0, device.reads.get())
        assertEquals("零发送", 0, requests.get())
        assertEquals("第一个清理器没运行：环境世代没变", CallEpochs(0, 0), inFlight.epochs())
    }

    @Test
    fun fx03_switch_window_new_request_and_session_queries_read_nothing() {
        val api = client()
        assertEquals("account-A", api.activeUserId())
        publishSwitching()
        resetCounts()

        assertEquals(ApiResult.LocalReject(LocalRejectReason.ENVIRONMENT_UNSETTLED), api.execute(ApiCall("getMe")))
        assertEquals(
            "点击时固定的账号仍按 HEAD 先核对账号",
            ApiResult.LocalReject(LocalRejectReason.ACCOUNT_MISMATCH),
            api.execute(ApiCall("getMe", expectedAccount = "account-B")),
        )
        assertNull(api.activeUserId())
        assertNull(api.activeSessionScope())
        assertNull(api.pendingFor("createWithdrawal"))
        api.onUpdateStateChanged(UpdateRaw.SUPPORTED)
        assertEquals(0, tokens.reads.get())
        assertEquals(0, device.reads.get())
        assertEquals(0, requests.get())

        // 切换结束（闸门重新打开）：照常读、照常发
        gate.reopenAfterSwitch()
        assertEquals("account-A", api.activeUserId())
        assertTrue(api.execute(ApiCall("getMe")) is ApiResult.Success)
        assertTrue(tokens.reads.get() > 0)
        assertEquals(1, requests.get())
    }

    // ---------------------------------------------------------------- 撤回同意

    @Test
    fun fx03_withdraw_marker_published_first_cleaner_not_run_old_request_reads_and_sends_nothing() {
        var cleanups = 0
        // 清理器一个都没跑（只记次数，失败返回：待清理标记保留）；隐私结论仍是 full（还没发布基本模式）
        privacy.withdrawalCleanup = {
            cleanups += 1
            error("cleaners not run yet")
        }
        val result = oldRequestResumesAfter { runCatching { privacy.withdraw() } }

        assertEquals(1, cleanups)
        assertTrue(privacy.credentialAccess.cleanupPending)
        assertTrue("按 HEAD：基本模式匿名白名单之外本地拒绝", result is ApiResult.LocalReject)
        assertEquals(0, tokens.reads.get())
        assertEquals(0, device.reads.get())
        assertEquals(0, requests.get())
        assertEquals("第一个清理器没运行：隐私世代没变", CallEpochs(0, 0), inFlight.epochs())
    }

    @Test
    fun fx03_withdraw_cancelled_logout_resumes_without_reading_tokens() {
        val api = client()
        val hold = CountDownLatch(1).also { logoutHold = it }
        val service = LoginService(api, tokens, NoopHost())
        // 撤回同意：竖屏障 → 第一个清理器作废并取消在途（logout 请求随之结束）→ 令牌清理器清掉令牌 → 之后的清理器失败（待清理保留）
        privacy.withdrawalCleanup = {
            inFlight.onPrivacyRevoked()
            tokens.clear()
            error("later cleaner failed")
        }
        val running = CompletableFuture.supplyAsync { runBlocking { service.logout() } }
        awaitPath("/v1/auth/logout")
        runCatching { privacy.withdraw() }
        resetCounts()
        hold.countDown()
        val outcome = running.get(WAIT_SECONDS, TimeUnit.SECONDS)

        assertTrue("本机已回到游客", outcome)
        assertEquals("恢复之后零读取令牌（不再探查原会话还在不在）", 0, tokens.reads.get())
        assertEquals("不再发出", 0, requests.get())
    }

    @Test
    fun fx03_switch_cancelled_logout_resumes_clears_by_epoch_without_reading_tokens() {
        val api = client()
        val hold = CountDownLatch(1).also { logoutHold = it }
        val service = LoginService(api, tokens, NoopHost())
        val running = CompletableFuture.supplyAsync { runBlocking { service.logout() } }
        awaitPath("/v1/auth/logout")
        // AppModule 的切换监听：屏障里关闸门，同一同步区作废在途（logout 请求随之取消）；清理器还没运行（令牌仍在）
        publishSwitching()
        inFlight.onEnvironmentSwitching()
        resetCounts()
        hold.countDown()
        val outcome = running.get(WAIT_SECONDS, TimeUnit.SECONDS)

        assertTrue(outcome)
        assertEquals("按登录世代清会话（只写不读）", 0, tokens.reads.get())
        assertNull("令牌已清", tokens.inner.accessToken)
        assertEquals(0, requests.get())
    }

    // ---------------------------------------------------------------- 退出登录

    @Test
    fun fx03_logout_session_cleared_then_published_old_request_reads_no_token_and_sends_nothing() {
        val api = client()
        val epoch = tokens.loginEpoch
        val result =
            oldRequestResumesAfter {
                // LoginService.finishLogout 的本机收尾：先按世代删令牌（屏障），同一把锁里才发布「会话结束」
                api.clearSessionIf(epoch) { paths += "session-ended" }
            }
        assertTrue("本地结束", result is ApiResult.LocalReject)
        assertEquals("零发送", 0, requests.get())
        assertNull("读到的都是已清空的存储", tokens.inner.accessToken)
    }

    // ---------------------------------------------------------------- 设备凭据入口（③）

    @Test
    fun fx03_switch_and_withdraw_windows_real_device_registry_reads_nothing() {
        for (loadedBefore in listOf(false, true)) {
            for (path in listOf("switch", "withdraw")) {
                val localGate = EnvironmentGate { EnvironmentStartupReconciler.Outcome.UNCHANGED }.apply { settleAtStartup() }
                val localBarrier = CredentialReadBarrier()
                val localPrivacy = PrivacyGate(MemoryStore(), { 0L }, "1").apply { credentialBarrier = localBarrier::raise }
                localPrivacy.withdrawalCleanup = { error("cleaners not run yet") }
                val store = CountingStore()
                val registry =
                    DeviceRegistry(
                        platform = "android",
                        idReader = { "0123456789abcdef" },
                        store = store,
                        privacy = {
                            if (localPrivacy.credentialAccess.cleanupPending) PrivacyStateRaw.BASIC_MODE else PrivacyStateRaw.CONSENTED
                        },
                        client = { error("registration must not reach the network") },
                        epochs = { CallEpochs(0, 0) },
                        executor = Executor { it.run() },
                        environmentSettled = { localGate.isSettled },
                        readBarrier = localBarrier,
                    )
                if (loadedBefore) assertEquals("device-fixture", registry.deviceId)
                if (path == "switch") localBarrier.raise { localGate.closeForSwitch() } else runCatching { localPrivacy.withdraw() }
                store.reads.set(0)

                assertNull(registry.deviceId)
                assertNull(registry.installSecret)
                assertFalse(registry.registered)
                assertFalse("不读、不注册（交给退避）", registry.ensureRegistered())
                assertFalse(registry.reRegister("device-fixture"))
                registry.recoverAfterRejection("device-fixture")
                assertEquals("$path loaded=$loadedBefore：存储零读取", 0, store.reads.get())
                assertEquals("凭据没被删", StoredDevice.Present("device-fixture", "test-only.install.device-a"), store.stored)
            }
        }
    }

    // ---------------------------------------------------------------- 确认 / 放弃上一笔（④⑤）

    private fun unknownWithdrawal(api: ApiClient): String {
        val failure = api.execute(ApiCall("createWithdrawal", body = """{"amount_fen":1000}""")) as ApiResult.TransportFailure
        assertTrue(failure.pendingKeyUnknown)
        return checkNotNull(failure.idempotencyKey)
    }

    private fun ownership() = CallOwnership(CallEpochs(0, 0), stillOwned = { true })

    @Test
    fun fx03_unsettled_confirm_and_abandon_keep_head_reasons_with_zero_reads() {
        val api = client()
        val key = unknownWithdrawal(api)
        val cases =
            listOf(
                "missing" to PendingTarget("account-A", "key-already-ended", ownership()),
                "mismatch" to PendingTarget("account-B", key, ownership()),
            )
        // 基线（环境就绪：这几种都在读到会话后、发出前就本地拒绝，与 HEAD 在环境未就绪时的结论相同）
        val baseline =
            cases.associate { (name, target) ->
                name to (api.confirmPending("createWithdrawal", null, target) to api.abandonPending("createWithdrawal", target))
            }
        assertEquals(LocalRejectReason.PENDING_KEY_UNRESOLVED, (baseline.getValue("missing").first as ApiResult.LocalReject).reason)
        assertEquals(LocalRejectReason.ACCOUNT_MISMATCH, (baseline.getValue("mismatch").first as ApiResult.LocalReject).reason)

        publishSwitching()
        resetCounts()
        cases.forEach { (name, target) ->
            assertEquals(name, baseline.getValue(name).first, api.confirmPending("createWithdrawal", null, target))
            assertEquals(name, baseline.getValue(name).second, api.abandonPending("createWithdrawal", target))
        }
        // 有效记录：HEAD 会置为在途、发出前被 environment_unsettled 拒绝并退回待确认；这里不占用、不写，结论相同
        val valid = PendingTarget("account-A", key, ownership())
        assertEquals(ApiResult.LocalReject(LocalRejectReason.ENVIRONMENT_UNSETTLED), api.confirmPending("createWithdrawal", null, valid))
        assertEquals(ApiResult.LocalReject(LocalRejectReason.ENVIRONMENT_UNSETTLED), api.abandonPending("createWithdrawal", valid))
        assertEquals(PendingKeyStore.UNKNOWN, pending.state(key, "account-A").stateRaw)
        assertEquals(0, tokens.reads.get())
        assertEquals(0, device.reads.get())
        assertEquals(0, requests.get())
    }

    @Test
    fun fx03_tokens_cleared_pending_cleaner_not_run_confirm_and_abandon_keep_head_reason_with_zero_reads() {
        val api = client()
        val key = unknownWithdrawal(api)
        val target = PendingTarget("account-A", key, ownership())
        // 基线：环境就绪、令牌已清（游客）→ HEAD 的分类（Android 的游客在 ownedPending 第一步就是 pending_key_unresolved）
        val baselineTokens = MemorySessionTokens()
        val baselineApi =
            ApiClient(
                identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.2.0", build = "1", channel = "official"),
                baseUrl = { server.url("/").toString() },
                context = { NetworkContext(PrivacyRaw.FULL, true, true, UpdateRaw.SUPPORTED) },
                device = device,
                tokens = baselineTokens,
                pendingKeys = pending,
                http = OkHttpClient(),
                sleeper = {},
            )
        val baseline =
            baselineApi.confirmPending("createWithdrawal", null, target) to baselineApi.abandonPending("createWithdrawal", target)
        assertEquals(ApiResult.LocalReject(LocalRejectReason.PENDING_KEY_UNRESOLVED), baseline.first)

        for (quarantined in listOf(false, true)) {
            tokens.inner.save("access-A", "refresh-A", SessionScopeRaw.FULL, "account-A")
            assertEquals("切换前读到过会话（已绑定 account-A）", "account-A", api.activeUserId())
            publishSwitching()
            // 切换的令牌清理器已运行（或删除写盘失败、世代前进进入隔离），未决存储的清理器还没运行：记录仍在
            tokens.clearFails = quarantined
            runCatching { tokens.clear() }
            tokens.clearFails = false
            resetCounts()

            assertEquals("quarantined=$quarantined", baseline.first, api.confirmPending("createWithdrawal", null, target))
            assertEquals("quarantined=$quarantined", baseline.second, api.abandonPending("createWithdrawal", target))
            assertEquals(0, tokens.reads.get())
            assertEquals(0, device.reads.get())
            assertEquals(0, requests.get())
            assertEquals(PendingKeyStore.UNKNOWN, pending.state(key, "account-A").stateRaw)
            gate.reopenAfterSwitch()
        }
    }

    // ---------------------------------------------------------------- 工具

    private fun awaitPath(path: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (path !in paths.toList()) {
            check(System.nanoTime() < deadline) { "等不到 $path" }
            Thread.sleep(POLL_MS)
        }
    }

    private class NoopHost : LoginHost {
        override fun updateState(): String = "supported"

        override fun hasPendingAction(): Boolean = false

        override fun closeLogin() = Unit

        override fun resumePending() = Unit

        override fun discardPending() = Unit

        override fun enterForceUpdate() = Unit

        override fun sessionChanged() = Unit

        override fun sessionSynced() = Unit

        override fun sessionEnded() = Unit
    }

    private companion object {
        const val WAIT_SECONDS = 10L
        const val POLL_MS = 10L
    }
}
