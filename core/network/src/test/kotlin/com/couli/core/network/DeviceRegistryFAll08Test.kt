package com.couli.core.network

import com.couli.core.clientcore.ClientPlatformRaw
import com.couli.core.clientcore.PrivacyStateRaw
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
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
 * F-ALL-08：APP-03b 三端共性第 21、22、24、25、27 条在 Android 的核对。
 * - 21：10402 后被拒凭据立即隔离；注册请求不带旧设备头；
 * - 22：同一窗口的等待者复用结果（含失败），结算在注册锁内完成，退避通知只发一次；
 * - 24：单飞窗口（注册锁）绑定清理世代：清理之后旧等待者按取消结算，不注册、不记结果；
 * - 25：有效世代的 10402——含重放里再次收到的——都隔离这次请求所用的凭据；重放预算用完时按清理世代登记一次恢复，自动启动唯一的
 *   退避注册链（APP-04 Android R1-02）；
 * - 27：退避注册与 10402 窗口共用同一把注册锁与同一份被拒凭据状态；进锁后先查被拒凭据是否已被替换，退避注册成功后晚到或还在
 *   等锁的 10402 等待者直接复用，不再注册。
 */
class DeviceRegistryFAll08Test {
    private lateinit var server: MockWebServer
    private val pool = Executors.newCachedThreadPool()
    private val inFlight = InFlightCalls()
    private val store = MemoryDeviceCredentialStore()
    private val registrations = AtomicInteger()
    private val registerRequests = mutableListOf<RecordedRequest>()
    private val sign = vector()

    @Volatile
    private var registerReply: (Int) -> MockResponse = { n -> registered("device-$n") }

    @Volatile
    private var otherReply: (RecordedRequest) -> MockResponse = { envelope(0, "{}") }

    private lateinit var api: ApiClient
    private lateinit var registry: DeviceRegistry

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path == "/v1/devices") {
                        synchronized(registerRequests) { registerRequests += request }
                        registerReply(registrations.incrementAndGet())
                    } else {
                        otherReply(request)
                    }
            }
        server.start()
        registry =
            DeviceRegistry(
                platform = ClientPlatformRaw.ANDROID,
                idReader = { ANDROID_ID },
                store = store,
                privacy = { PrivacyStateRaw.CONSENTED },
                client = { api },
                epochs = inFlight::epochs,
                executor = { it.run() },
            )
        api =
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
                device = registry,
                tokens = MemorySessionTokens("old-access", "refresh-one", SessionScopeRaw.FULL, userId = "account-A"),
                pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
                hooks =
                    NetworkHooks(
                        reRegisterDevice = registry::reRegister,
                        quarantineDevice = registry::quarantine,
                        recoverDevice = registry::recoverAfterRejection,
                    ),
                clock = {
                    sign
                        .getValue("timestamp")
                        .jsonPrimitive.content
                        .toLong() * MILLIS
                },
                sleeper = {},
                nonce = { sign.getValue("nonce").jsonPrimitive.content },
                inFlight = inFlight,
            )
    }

    @After
    fun stop() {
        pool.shutdownNow()
        server.shutdown()
    }

    private fun consent(): Boolean {
        var outcome: Boolean? = null
        registry.registerAsync { outcome = it }
        return checkNotNull(outcome)
    }

    private fun openLink(): ApiResult =
        api.execute(
            ApiCall(
                "openLink",
                pathParams = mapOf("link_id" to "abc"),
                query = "src=agent",
                body = sign.getValue("body_utf8").jsonPrimitive.content,
            ),
        )

    @Test
    fun lesson21_rejectedCredentialIsQuarantinedBeforeTheSingleFlightLockIsTaken() {
        assertTrue(consent())
        assertEquals("device-1", registry.deviceId)
        // 重新注册的应答被挡住：单飞锁被这次注册占着
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        registerReply = { n ->
            entered.countDown()
            gate.await(5, TimeUnit.SECONDS)
            registered("device-$n")
        }
        otherReply = { request -> if (request.getHeader(HeaderNames.DEVICE_ID) == "device-1") envelope(10402) else envelope(0, "{}") }
        val first = pool.submit<ApiResult> { openLink() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        assertNull("锁被占着时，被拒的 device_id 已不可读", registry.deviceId)
        assertNull("注册请求不带旧设备头", registerRequests.last().getHeader(HeaderNames.DEVICE_ID))

        gate.countDown()
        assertTrue(first.get(10, TimeUnit.SECONDS) is ApiResult.Success)
        assertEquals("device-2", registry.deviceId)
    }

    /**
     * 第 25 条（补全：APP-04 Android 第 1 轮评审 R1-02、共性第 34 条）：启动注册成功 → 普通请求 10402 → 重新注册成功 → 重放再次 10402。
     * 重放预算已用完，这次调用不再重新注册；被隔离的凭据按清理世代登记**一次**恢复，自动启动退避注册链（onCredentialsLost →
     * PrivacyGate.restartDeviceRegistration，这里接成 registerAsync 代替 PrivacyGate 的设备注册步骤）——测试不手动补调注册。
     */
    @Test
    fun lesson25_replayRejectedAgainQuarantinesAndStartsTheSingleBackoffRecoveryAutomatically() {
        assertTrue(consent())
        val lost = AtomicInteger()
        val recovered = AtomicInteger()
        registry.onCredentialsLost = {
            lost.incrementAndGet()
            // App 的接线：PrivacyGate 从第 1 次开始按退避注册（设备注册步骤 = registerAsync）
            registry.registerAsync { ok -> if (ok) recovered.incrementAndGet() }
        }
        otherReply =
            { request ->
                if (request.getHeader(HeaderNames.DEVICE_ID) in
                    setOf("device-1", "device-2")
                ) {
                    envelope(10402)
                } else {
                    envelope(0, "{}")
                }
            }

        val result = openLink() as ApiResult.Failure

        assertEquals(10402, result.code)
        assertEquals("恢复链只启动一次", 1, lost.get())
        assertEquals("退避注册自动完成（首次注册 + 重新注册 + 恢复注册）", 3, registrations.get())
        assertEquals(1, recovered.get())
        assertEquals("恢复后设备签名能力可用", "device-3", registry.deviceId)
        assertTrue("之后的签名请求照常发出", openLink() is ApiResult.Success)

        // 同一被拒凭据晚到的再一次通知不再起第二条链
        registry.recoverAfterRejection("device-2")
        assertEquals(1, lost.get())
    }

    @Test
    fun lesson25_recoveryIsNotRegisteredWhenTheCredentialWasAlreadyReplacedOrTheGenerationIsGone() {
        assertTrue(consent())
        val lost = AtomicInteger()
        registry.onCredentialsLost = { lost.incrementAndGet() }
        // 凭据还在（没被隔离）：不登记
        registry.recoverAfterRejection("device-1")
        assertEquals(0, lost.get())
        // 撤回 / 切换环境之后：旧的通知不登记
        registry.quarantine("device-1")
        registry.clear()
        registry.recoverAfterRejection("device-1")
        assertEquals(0, lost.get())
    }

    @Test
    fun lesson25_staleGeneration10402DoesNotQuarantineTheCurrentCredential() {
        assertTrue(consent())
        val before = inFlight.epochs()
        otherReply = { request ->
            // 响应到达前切换了环境世代：这是旧世代的 10402
            inFlight.onEnvironmentSwitching()
            if (request.getHeader(HeaderNames.DEVICE_ID) == "device-1") envelope(10402) else envelope(0, "{}")
        }
        openLink()
        assertTrue(before != inFlight.epochs())
        assertEquals("旧世代的 10402 不注册", 1, registrations.get())
    }

    @Test
    fun lesson24_waiterFromAClearedGenerationSettlesAsCancelledAndRecordsNothing() {
        assertTrue(consent())
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        registerReply = { n ->
            entered.countDown()
            gate.await(5, TimeUnit.SECONDS)
            registered("device-$n")
        }
        // 第一个调用占住单飞锁（在注册请求里），第二个以同一个被拒 id 在锁外等
        val holder = pool.submit<Boolean> { registry.reRegister("device-1") }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val waiter = pool.submit<Boolean> { registry.reRegister("device-1") }
        Thread.sleep(WAIT_MS)
        // 撤回同意 / 切换环境的清理：清理世代加一
        registry.clear()
        gate.countDown()

        assertFalse("旧世代的持有者结果不生效", holder.get(10, TimeUnit.SECONDS))
        assertFalse("旧世代的等待者按取消结算", waiter.get(10, TimeUnit.SECONDS))
        assertEquals("等待者没有再注册一次", 2, registrations.get())
        assertNull("旧世代的注册结果没有写进新世代", registry.deviceId)
        assertTrue("存储里也没有", store.stored == StoredDevice.None)
    }

    /**
     * 22：同一被拒凭据的 10402 窗口——复用已有结果、排队、结算在同一把注册锁里完成；窗口结束时先存下不可变的结果，再放开锁唤醒
     * 等待者。等待者一律复用这一次的结果（失败也复用），不再注册；退避通知（onCredentialsLost）只发一次。
     */
    @Test
    fun lesson22_waitersOfOneWindowReuseItsFailedResultAndTheBackoffNoticeFiresOnce() {
        assertTrue(consent())
        val lost = AtomicInteger()
        registry.onCredentialsLost = { lost.incrementAndGet() }
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        registerReply = { _ ->
            entered.countDown()
            gate.await(5, TimeUnit.SECONDS)
            MockResponse().setResponseCode(SERVICE_UNAVAILABLE)
        }
        val holder = pool.submit<Boolean> { registry.reRegister("device-1") }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val waiters = (1..WAITERS).map { pool.submit<Boolean> { registry.reRegister("device-1") } }
        Thread.sleep(WAIT_MS)
        gate.countDown()

        assertFalse(holder.get(10, TimeUnit.SECONDS))
        waiters.forEach { assertFalse("等待者复用失败结果", it.get(10, TimeUnit.SECONDS)) }
        assertEquals("窗口里只注册了 1 次（首次注册 + 1）", 2, registrations.get())
        assertEquals("退避通知只发一次", 1, lost.get())
    }

    @Test
    fun lesson27_waiterBlockedBehindBackoffRegistrationReusesItsResult() {
        assertTrue(consent())
        // 10402 隔离了 device-1：退避注册（PrivacyGate 的设备注册步骤）开始重新注册，注册请求被挡住、占着注册锁
        registry.quarantine("device-1")
        assertNull(registry.deviceId)
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        registerReply = { n ->
            entered.countDown()
            gate.await(5, TimeUnit.SECONDS)
            registered("device-$n")
        }
        val backoff = pool.submit<Boolean> { registry.ensureRegistered() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        // 同一个被拒凭据的 10402 窗口在锁外等
        val waiter = pool.submit<Boolean> { registry.reRegister("device-1") }
        Thread.sleep(WAIT_MS)
        gate.countDown()

        assertTrue(backoff.get(10, TimeUnit.SECONDS))
        assertTrue("等待者复用退避注册的结果", waiter.get(10, TimeUnit.SECONDS))
        assertEquals("没有再注册一次（首次 + 退避）", 2, registrations.get())
        assertEquals("device-2", registry.deviceId)
    }

    @Test
    fun lesson27_late10402AfterBackoffReplacedTheCredentialDoesNotRegisterAgain() {
        assertTrue(consent())
        registry.quarantine("device-1")
        assertTrue("退避注册成功", registry.ensureRegistered())
        assertEquals("device-2", registry.deviceId)

        // 晚到的、带旧 device-1 的 10402：进锁后发现被拒凭据已被替换，直接成功
        assertTrue(registry.reRegister("device-1"))

        assertEquals(2, registrations.get())
        assertEquals("当前凭据不受晚到的 10402 影响", "device-2", registry.deviceId)
    }

    private fun envelope(
        code: Int,
        data: String = "null",
    ) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"code":$code,"msg":"","data":$data,"trace_id":"trace-0123456789"}""")

    private fun registered(deviceId: String) =
        envelope(0, """{"device_id":"$deviceId","install_secret":"${sign.getValue("install_secret").jsonPrimitive.content}"}""")

    private companion object {
        const val MILLIS = 1000L
        const val WAIT_MS = 200L
        const val WAITERS = 3
        const val SERVICE_UNAVAILABLE = 503

        val ANDROID_ID: String = hashCase().getValue("input").jsonPrimitive.content

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
