package com.couli.core.network

import com.couli.core.clientcore.ClientPlatformRaw
import com.couli.core.clientcore.PrivacyStateRaw
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
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
 * 设备注册对本地假服务的集成测试（APP-03b 任务书 §5 第 2 条）：同意之后注册设备、签名改用真实凭据且 X-Sign 与共用向量一致；
 * 10402 只重新注册 1 次再重放（并发也只注册 1 次）；无效标识不发注册请求；20001（device_hash）重读一次标识；撤回后晚到的注册结果
 * 丢弃；凭据读不出不重复注册；撤回清理未完成时 registerDevice 本地拒绝。
 */
class DeviceRegistryIntegrationTest {
    private lateinit var server: MockWebServer
    private val pool = Executors.newCachedThreadPool()
    private val inFlight = InFlightCalls()
    private val store = MemoryDeviceCredentialStore()
    private val registrations = AtomicInteger()
    private val idReads = AtomicInteger()
    private val registerBodies = mutableListOf<JsonObject>()
    private val registerRequests = mutableListOf<RecordedRequest>()

    @Volatile
    private var privacy = PrivacyStateRaw.CONSENTED

    @Volatile
    private var androidId: String? = ANDROID_ID

    @Volatile
    private var context = FULL

    /** /v1/devices 的应答（按第几次注册）。 */
    @Volatile
    private var registerReply: (Int) -> MockResponse = { n -> registered("device-$n", vector().text("install_secret")) }

    /** 其余接口的应答。 */
    @Volatile
    private var otherReply: (RecordedRequest) -> MockResponse = { envelope(0, "{}") }

    private val sign = vector()

    private lateinit var api: ApiClient
    private lateinit var registry: DeviceRegistry

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path == "/v1/devices") {
                        synchronized(registerBodies) {
                            registerRequests += request
                            registerBodies += Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        }
                        registerReply(registrations.incrementAndGet())
                    } else {
                        otherReply(request)
                    }
            }
        server.start()
        registry = registry()
        api = client(registry)
    }

    @After
    fun stop() {
        pool.shutdownNow()
        server.shutdown()
    }

    private fun registry(executor: Executor = Executor { it.run() }) =
        DeviceRegistry(
            platform = ClientPlatformRaw.ANDROID,
            idReader = {
                idReads.incrementAndGet()
                androidId
            },
            store = store,
            privacy = { privacy },
            client = { api },
            epochs = inFlight::epochs,
            executor = executor,
        )

    private fun client(device: DeviceRegistry) =
        ApiClient(
            identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.2.0", build = "1", channel = "official"),
            baseUrl = { server.url("/").toString() },
            context = { context },
            device = device,
            tokens = MemorySessionTokens("old-access", "refresh-one", SessionScopeRaw.FULL, userId = "account-A"),
            pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
            hooks = NetworkHooks(reRegisterDevice = device::reRegister),
            clock = { sign.text("timestamp").toLong() * MILLIS },
            sleeper = {},
            nonce = { sign.text("nonce") },
            inFlight = inFlight,
        )

    /** PrivacyGate.onConsented 的设备注册步骤（后台注册，回调是否成功）。 */
    private fun consentStep(): Boolean {
        var outcome: Boolean? = null
        registry.registerAsync { outcome = it }
        return checkNotNull(outcome)
    }

    private fun openLink(): ApiResult =
        api.execute(ApiCall("openLink", pathParams = mapOf("link_id" to "abc"), query = "src=agent", body = sign.text("body_utf8")))

    @Test
    fun consentRegistersDeviceAndSignedRequestsUseRealCredentialsMatchingTheSharedVector() {
        assertNull("同意前没有凭据", registry.deviceId)

        assertTrue(consentStep())

        assertEquals(1, registrations.get())
        val body = registerBodies.single()
        assertEquals("ANDROID_ID 的 device_hash 与 device-hash 向量一致", ANDROID_ID_HASH, body.text("device_hash"))
        assertEquals("android_id", body.text("id_source"))
        assertEquals(setOf("device_hash", "id_source"), body.keys)
        val registration = registerRequests.single()
        assertNull("注册请求不签名", registration.getHeader(HeaderNames.SIGN))
        assertNull("注册前没有 device_id", registration.getHeader(HeaderNames.DEVICE_ID))
        assertEquals("device-1", registry.deviceId)
        assertEquals("install_secret 存进安全存储", StoredDevice.Present("device-1", sign.text("install_secret")), store.stored)

        val result = openLink()

        assertTrue(result.toString(), result is ApiResult.Success)
        val sent = takeRequestTo("/v1/links/abc/open?src=agent")
        assertEquals("device-1", sent.getHeader(HeaderNames.DEVICE_ID))
        assertEquals(sign.text("expected_sign"), sent.getHeader(HeaderNames.SIGN))
        // 已注册：再次同意步骤直接成功，不重复注册
        assertTrue(consentStep())
        assertEquals(1, registrations.get())
    }

    @Test
    fun deviceRejectedWith10402ReRegistersOnceThenReplaysWithTheNewDevice() {
        assertTrue(consentStep())
        otherReply = { request -> if (request.getHeader(HeaderNames.DEVICE_ID) == "device-1") envelope(10402) else envelope(0, "{}") }

        val result = openLink()

        assertTrue(result.toString(), result is ApiResult.Success)
        assertEquals("只重新注册 1 次", 2, registrations.get())
        assertNull("重新注册不带被拒的 device_id", registerRequests.last().getHeader(HeaderNames.DEVICE_ID))
        assertEquals("device-2", registry.deviceId)
        val opens = requestsTo("/v1/links/abc/open?src=agent")
        assertEquals(listOf("device-1", "device-2"), opens.map { it.getHeader(HeaderNames.DEVICE_ID) })
        assertEquals("重放沿用原键", opens[0].getHeader(HeaderNames.IDEMPOTENCY_KEY), opens[1].getHeader(HeaderNames.IDEMPOTENCY_KEY))
    }

    @Test
    fun replayStillRejectedWith10402DoesNotReRegisterAgain() {
        assertTrue(consentStep())
        var lost = 0
        registry.onCredentialsLost = { lost += 1 }
        otherReply = { envelope(10402) }

        val result = openLink() as ApiResult.Failure

        assertEquals(10402, result.code)
        assertEquals("整次调用只重新注册 1 次", 2, registrations.get())
        assertEquals(2, requestsTo("/v1/links/abc/open?src=agent").size)
        assertEquals("重新注册本身成功，不算凭据丢失", 0, lost)
    }

    @Test
    fun concurrent10402ShareOneReRegistration() {
        assertTrue(consentStep())
        val rejected = CountDownLatch(CONCURRENT)
        val arrived = CountDownLatch(CONCURRENT)
        otherReply = { request ->
            if (request.getHeader(HeaderNames.DEVICE_ID) == "device-1") {
                // 三个请求都带旧 device_id 到达后才一起回 10402
                arrived.countDown()
                arrived.await(5, TimeUnit.SECONDS)
                rejected.countDown()
                envelope(10402)
            } else {
                envelope(0, "{}")
            }
        }
        // 重新注册的应答等到三个调用都收到 10402 之后才返回：后到的两个在单飞锁上等
        registerReply = { n ->
            rejected.await(5, TimeUnit.SECONDS)
            registered("device-$n", sign.text("install_secret"))
        }

        val futures = List(CONCURRENT) { pool.submit<ApiResult> { openLink() } }
        val results = futures.map { it.get(20, TimeUnit.SECONDS) }

        assertTrue(results.toString(), results.all { it is ApiResult.Success })
        assertEquals("首次注册 + 1 次重新注册", 2, registrations.get())
        assertEquals("device-2", registry.deviceId)
    }

    @Test
    fun invalidIdentifiersNeverSendARegistration() {
        for (invalid in listOf(null, "", "   ", "0000000000000000", "----", "9774d56d682e549c")) {
            androidId = invalid
            assertFalse(invalid.toString(), consentStep())
        }
        assertEquals("无效标识不发注册请求", 0, server.requestCount)
        assertNull(registry.deviceId)
    }

    @Test
    fun nothingIsReadOrSentBeforeConsent() {
        androidId = null
        var reads = 0
        val gated =
            DeviceRegistry(
                platform = ClientPlatformRaw.ANDROID,
                idReader = {
                    reads += 1
                    ANDROID_ID
                },
                store = store,
                privacy = { PrivacyStateRaw.BASIC_MODE },
                client = { api },
                epochs = inFlight::epochs,
                executor = { it.run() },
            )
        assertFalse(gated.ensureRegistered())
        assertFalse(gated.reRegister("device-1"))
        assertEquals("同意前不读设备标识", 0, reads)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun invalidHashRejectedWith20001RereadsTheIdentifierOnce() {
        registerReply = { n ->
            if (n == 1) envelope(20001, """{"fields":["device_hash"]}""") else registered("device-$n", sign.text("install_secret"))
        }
        val reads = mutableListOf("f0e1d2c3b4a59687", ANDROID_ID)
        androidId = null
        val rereading =
            DeviceRegistry(
                platform = ClientPlatformRaw.ANDROID,
                idReader = { reads.removeAt(0) },
                store = store,
                privacy = { privacy },
                client = { api },
                epochs = inFlight::epochs,
                executor = { it.run() },
            )
        api = client(rereading)

        assertTrue(rereading.ensureRegistered())

        assertEquals(2, registrations.get())
        assertEquals(ANDROID_ID_HASH, registerBodies[1].text("device_hash"))
        assertEquals("device-2", rereading.deviceId)
    }

    @Test
    fun rereadThatReturnsTheRejectedValueDoesNotResendIt() {
        registerReply = { envelope(20001, """{"fields":["device_hash"]}""") }

        assertFalse(consentStep())
        assertEquals("同一个被拒的值不再发", 1, registrations.get())
        // 之后的退避重试仍读到同一个值：不发
        assertFalse(consentStep())
        assertEquals(1, registrations.get())
    }

    @Test
    fun registrationResultArrivingAfterWithdrawalIsDiscarded() {
        registerReply = { n ->
            // 注册请求在途时撤回同意：清理先作废注册，再删存储
            registry.clear()
            registered("device-$n", sign.text("install_secret"))
        }

        assertFalse(consentStep())

        assertNull(registry.deviceId)
        assertEquals(StoredDevice.None, store.stored)
    }

    @Test
    fun unreadableCredentialsAreNotTreatedAsMissing() {
        store.stored = StoredDevice.Unreadable
        assertFalse(consentStep())
        assertEquals("读不出不重复注册出新设备", 0, server.requestCount)
        assertNull(registry.deviceId)
    }

    @Test
    fun registerDeviceIsRejectedLocallyWhileWithdrawalCleanupIsPending() {
        context = FULL.copy(credentialsReadable = false)
        assertFalse(consentStep())
        assertEquals("请求体带设备标识的 registerDevice 不发出（lessons 2）", 0, server.requestCount)
    }

    @Test
    fun clearRemovesCredentialsFromMemoryAndStorage() {
        assertTrue(consentStep())
        registry.clear()
        assertNull(registry.deviceId)
        assertNull(registry.installSecret)
        assertEquals(StoredDevice.None, store.stored)
    }

    // ---------- 第 1 轮评审（R1-02、R1-03、R1-04、R1-06）与 lessons 10 ----------

    @Test
    fun r1_02_lateRereadResultAfterClearDoesNotPoisonTheNewGeneration() {
        registerReply = { n ->
            if (n == 1) {
                // 旧注册已收到 20001、还没处理结果分支时切换环境（清理）
                registry.clear()
                envelope(20001, """{"fields":["device_hash"]}""")
            } else {
                registered("device-$n", sign.text("install_secret"))
            }
        }

        assertFalse(consentStep())
        assertEquals("失效的结果不重读、不继续注册", 1, registrations.get())

        // 新世代读到同一个有效 ANDROID_ID：照常注册，不被旧世代的 20001 拦住
        assertTrue(consentStep())
        assertEquals(2, registrations.get())
        assertEquals(ANDROID_ID_HASH, registerBodies[1].text("device_hash"))
        assertEquals("device-2", registry.deviceId)
    }

    @Test
    fun lessons10_resultArrivingAfterTheEnvironmentEpochChangedIsDiscardedOnEveryBranch() {
        registerReply = { n ->
            // 注册请求在途时开始切换环境（第一项清理：环境世代加 1）：第 1 次 20001、第 2 次成功都晚到
            if (n <= 2) inFlight.onEnvironmentSwitching()
            if (n == 1) envelope(20001, """{"fields":["device_hash"]}""") else registered("device-$n", sign.text("install_secret"))
        }

        assertFalse(consentStep())
        assertEquals("旧环境的 20001 不重读、不再发", 1, registrations.get())
        assertFalse(consentStep())
        assertNull("旧环境世代开始的注册结果不保存", registry.deviceId)
        assertEquals(StoredDevice.None, store.stored)
        // 新环境世代里同一个值照常发（旧世代没记下被拒哈希），结果保存
        assertTrue(consentStep())
        assertEquals(ANDROID_ID_HASH, registerBodies[2].text("device_hash"))
        assertEquals("device-3", registry.deviceId)
    }

    @Test
    fun r1_03_rereadValueAlsoRejectedIsNeverResentByBackoff() {
        val other = "f0e1d2c3b4a59687"
        val reads = ArrayDeque(listOf(ANDROID_ID, other))
        androidId = null
        val rereading =
            DeviceRegistry(
                platform = ClientPlatformRaw.ANDROID,
                idReader = { if (reads.size > 1) reads.removeFirst() else reads.first() },
                store = store,
                privacy = { privacy },
                client = { api },
                epochs = inFlight::epochs,
                executor = { it.run() },
            )
        api = client(rereading)
        registerReply = { envelope(20001, """{"fields":["device_hash"]}""") }

        assertFalse(rereading.ensureRegistered())
        assertEquals("A 被拒 → 重读 B → B 也被拒", 2, registrations.get())
        val sentB = registerBodies[1].text("device_hash")

        // 退避重试仍读到 B：不再带它
        assertFalse(rereading.ensureRegistered())
        assertFalse(rereading.ensureRegistered())
        assertEquals(2, registrations.get())
        assertEquals(1, registerBodies.count { it.text("device_hash") == sentB })
    }

    @Test
    fun r1_04_late10402ForTheSameRejectedDeviceReusesTheFailedReRegistration() {
        assertTrue(consentStep())
        var lost = 0
        registry.onCredentialsLost = { lost += 1 }
        otherReply = { request -> if (request.getHeader(HeaderNames.DEVICE_ID) == "device-1") envelope(10402) else envelope(0, "{}") }
        // 重新注册失败
        registerReply = { envelope(10001) }

        // 两个请求都带同一个旧 device_id 发出：第一个触发重新注册并失败，第二个的 10402 晚到
        val first = registry.reRegister("device-1")
        val late = registry.reRegister("device-1")

        assertFalse(first)
        assertFalse(late)
        assertEquals("首次注册 + 1 次重新注册，晚到的不再注册", 2, registrations.get())
        assertEquals("凭据丢失只报一次，之后交给退避流程", 1, lost)

        // 退避流程照常注册新设备
        registerReply = { n -> registered("device-$n", sign.text("install_secret")) }
        assertTrue(consentStep())
        assertEquals("device-3", registry.deviceId)
    }

    @Test
    fun r1_06_refreshRejectedWith10402ReRegistersThroughTheSameSingleFlightAndReplaysOnce() {
        assertTrue(consentStep())
        otherReply = { request ->
            when {
                request.path == "/v1/auth/refresh" && request.getHeader(HeaderNames.DEVICE_ID) == "device-1" -> {
                    envelope(10402)
                }

                request.path == "/v1/auth/refresh" -> {
                    envelope(0, """{"access_token":"new-access","refresh_token":"refresh-two","session_scope":"full"}""")
                }

                request.getHeader(HeaderNames.AUTHORIZATION) == "Bearer old-access" -> {
                    envelope(10002)
                }

                else -> {
                    envelope(0, "{}")
                }
            }
        }

        val result = api.execute(ApiCall("getMe"))

        assertTrue(result.toString(), result is ApiResult.Success)
        assertEquals("首次注册 + 1 次重新注册", 2, registrations.get())
        val refreshes = requestsTo("/v1/auth/refresh")
        assertEquals("刷新重新组头签名后只重放 1 次", listOf("device-1", "device-2"), refreshes.map { it.getHeader(HeaderNames.DEVICE_ID) })
        assertTrue("重放重新签名", refreshes.all { it.getHeader(HeaderNames.SIGN) != null })
        assertEquals("device-2", registry.deviceId)
    }

    @Test
    fun r1_06_refreshStillRejectedAfterReRegistrationEndsWithoutClearingTheSession() {
        assertTrue(consentStep())
        val tokens = MemorySessionTokens("old-access", "refresh-one", SessionScopeRaw.FULL, userId = "account-A")
        val sessionApi =
            ApiClient(
                identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.2.0", build = "1", channel = "official"),
                baseUrl = { server.url("/").toString() },
                context = { context },
                device = registry,
                tokens = tokens,
                pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
                hooks = NetworkHooks(reRegisterDevice = registry::reRegister),
                clock = { sign.text("timestamp").toLong() * MILLIS },
                sleeper = {},
                nonce = { sign.text("nonce") },
                inFlight = inFlight,
            )
        api = sessionApi
        otherReply = { request -> if (request.path == "/v1/auth/refresh") envelope(10402) else envelope(10002) }

        val result = sessionApi.execute(ApiCall("getMe"))

        assertTrue(result.toString(), result is ApiResult.TransportFailure)
        assertEquals("整次刷新只重新注册 1 次", 2, registrations.get())
        assertEquals(2, requestsTo("/v1/auth/refresh").size)
        assertEquals("设备问题不清会话", "old-access", tokens.accessToken)
    }

    /** 在后台线程上跑一次注册任务；[hooks] 控制暂停点。返回任务的 Future 与「已暂停」信号。 */
    private fun pausedRegistration(pauseAt: (pause: () -> Unit) -> DeviceRegistryHooks): Pair<Future<Boolean>, CountDownLatch> {
        val paused = CountDownLatch(1)
        val resume = CountDownLatch(1)
        registry.hooks =
            pauseAt {
                paused.countDown()
                resume.await(5, TimeUnit.SECONDS)
            }
        val task = pool.submit<Boolean> { registry.ensureRegistered() }
        assertTrue("注册任务到达暂停点", paused.await(5, TimeUnit.SECONDS))
        return task to resume
    }

    /** 撤回同意完成（AppModule 的撤回清理顺序）：进入基本模式 → 作废在途请求 → 清设备凭据。 */
    private fun withdrawConsent() {
        privacy = PrivacyStateRaw.BASIC_MODE
        context = FULL.copy(privacy = PrivacyRaw.BASIC)
        inFlight.onPrivacyRevoked()
        registry.clear()
    }

    /** 运行中切换环境（AppModule 的环境清理顺序）：作废在途请求 → 清设备凭据。新环境仍已同意、仍可发请求。 */
    private fun switchEnvironment() {
        inFlight.onEnvironmentSwitching()
        registry.clear()
    }

    /**
     * R2-01（S0）：注册任务通过上一次归属核对、还没读取 ANDROID_ID 时暂停 → 撤回同意完成（基本模式 + clear）→ 任务恢复。
     * 读前核对与读取在 clear 共用的同步区里：撤回完成后旧任务不再读取设备标识，也不发注册请求。
     */
    @Test
    fun r2_01_withdrawalCompletedBeforeTheReadMeansTheIdentifierIsNeverRead() {
        val (task, resume) = pausedRegistration { pause -> DeviceRegistryHooks(beforeIdRead = pause) }

        withdrawConsent()
        resume.countDown()

        assertFalse(task.get(5, TimeUnit.SECONDS))
        assertEquals("撤回完成后不读 ANDROID_ID（BR-ID-11）", 0, idReads.get())
        assertEquals(0, registrations.get())
        assertNull(registry.deviceId)
    }

    /**
     * R2-02：读取标识期间完成环境切换与 clear → 读取返回。读后在同步区里再核对归属，失效立即结束：旧任务的 device_hash 不发到新环境。
     */
    @Test
    fun r2_02_environmentSwitchDuringTheReadEndsTheTaskBeforeSending() {
        val (task, resume) = pausedRegistration { pause -> DeviceRegistryHooks(afterIdRead = pause) }

        switchEnvironment()
        resume.countDown()

        assertFalse(task.get(5, TimeUnit.SECONDS))
        assertEquals(1, idReads.get())
        assertEquals("旧任务不在新环境发注册请求", 0, registrations.get())
        assertNull(registry.deviceId)
    }

    /**
     * R2-02：读后核对已通过、交给 ApiClient 之前切换环境（只作废在途请求世代）→ 注册请求带着原始归属，ApiClient 按开始时的世代
     * 绑定并在写出前核对：call_cancelled，不会被重新绑定到新环境。
     */
    @Test
    fun r2_02_registrationCarriesItsOriginalEpochsSoApiClientNeverRebindsItToTheNewEnvironment() {
        val (task, resume) = pausedRegistration { pause -> DeviceRegistryHooks(beforeSend = pause) }

        inFlight.onEnvironmentSwitching()
        resume.countDown()

        assertFalse(task.get(5, TimeUnit.SECONDS))
        assertEquals("ApiClient 不为旧任务绑定新环境世代", 0, registrations.get())
        assertEquals(0, server.requestCount)
    }

    /** R2-02：同上，但只有设备注册的清理世代变了（clear）：ApiClient 写出前的守卫核对调用方归属，同样不发。 */
    @Test
    fun r2_02_sendGuardChecksTheRegistryTicketBeforeWriting() {
        val (task, resume) = pausedRegistration { pause -> DeviceRegistryHooks(beforeSend = pause) }

        registry.clear()
        resume.countDown()

        assertFalse(task.get(5, TimeUnit.SECONDS))
        assertEquals(0, registrations.get())
        assertEquals(0, server.requestCount)
    }

    private fun requestsTo(path: String): List<RecordedRequest> =
        List(server.requestCount) { server.takeRequest(1, TimeUnit.SECONDS)!! }.filter { it.path == path }

    private fun takeRequestTo(path: String): RecordedRequest = requestsTo(path).last()

    private fun envelope(
        code: Int,
        data: String = "null",
    ) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"code":$code,"msg":"","data":$data,"trace_id":"trace-0123456789"}""")

    private fun registered(
        deviceId: String,
        secret: String,
    ) = envelope(0, """{"device_id":"$deviceId","install_secret":"$secret"}""")

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private companion object {
        const val MILLIS = 1000L
        const val CONCURRENT = 3

        /** device-hash.vectors.json hash_cases 里的 ANDROID_ID 与它的 device_hash。 */
        val ANDROID_ID: String = hashCase().getValue("input").jsonPrimitive.content
        val ANDROID_ID_HASH: String = hashCase().getValue("device_hash").jsonPrimitive.content

        val FULL = NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, update = UpdateRaw.SUPPORTED)

        fun snapshotFile(name: String): JsonObject {
            val start = File(checkNotNull(System.getProperty("user.dir"))).canonicalFile
            val root = generateSequence(start) { it.parentFile }.first { File(it, "contract.lock").isFile }
            return Json.parseToJsonElement(File(root, "contracts/snapshot/specs/$name").readText()).jsonObject
        }

        fun hashCase(): JsonObject =
            snapshotFile("device-hash.vectors.json")
                .getValue("hash_cases")
                .jsonArray
                .map { it.jsonObject }
                .last { it.getValue("id_source").jsonPrimitive.content == "android_id" }

        fun vector(): JsonObject =
            snapshotFile("request-sign.vectors.json")
                .getValue("valid_cases")
                .jsonArray[0]
                .jsonObject
    }
}
