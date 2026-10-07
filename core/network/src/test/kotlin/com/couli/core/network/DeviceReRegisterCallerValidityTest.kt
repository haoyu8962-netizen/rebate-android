package com.couli.core.network

import com.couli.core.clientcore.ClientPlatformRaw
import com.couli.core.clientcore.PrivacyStateRaw
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.EventListener
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
 * APP-04 Android 第 3 轮评审 R3-03（共性第 58 条）：调用方有效性与剩余预算一路传进真实的 [DeviceRegistry.reRegister]——
 * 取得注册锁后、读取设备标识前、注册请求实际写出前都核对，写出之后失效即取消在途的注册请求。最后一个有效调用方失效后恢复链停止、
 * 不记结果；被拒凭据已隔离的仍交给唯一的退避链一次（onCredentialsLost）。共享注册仍有别的有效调用方时照常继续。
 * 场景与评审一致：唯一的桥调用（getMe 代 auth.getUser）收到 10002 → 共享刷新收到 10402 → 进入设备重新注册。
 */
class DeviceReRegisterCallerValidityTest {
    private lateinit var server: MockWebServer
    private val pool = Executors.newCachedThreadPool()
    private val inFlight = InFlightCalls()
    private val store = MemoryDeviceCredentialStore()
    private val registerRequests = AtomicInteger()
    private val idReads = AtomicInteger()
    private val lost = AtomicInteger()
    private val cancelledPaths: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val sign = vector()

    @Volatile
    private var pageOpen = true

    /** 第 1 次注册（同意隐私时）之后的注册应答。 */
    @Volatile
    private var laterRegisterReply: () -> MockResponse = { registered("device-2") }

    @Volatile
    private var otherReply: (RecordedRequest) -> MockResponse = { envelope(0) }

    private lateinit var api: ApiClient
    private lateinit var registry: DeviceRegistry

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path == DEVICES) {
                        if (registerRequests.incrementAndGet() == 1) registered("device-1") else laterRegisterReply()
                    } else {
                        otherReply(request)
                    }
            }
        server.start()
        registry =
            DeviceRegistry(
                platform = ClientPlatformRaw.ANDROID,
                idReader = {
                    idReads.incrementAndGet()
                    ANDROID_ID
                },
                store = store,
                privacy = { PrivacyStateRaw.CONSENTED },
                client = { api },
                epochs = inFlight::epochs,
                executor = { it.run() },
            )
        registry.onCredentialsLost = { lost.incrementAndGet() }
        // 记下被取消的 Call（「关联底层请求的取消」）
        val http =
            OkHttpClient
                .Builder()
                .eventListener(
                    object : EventListener() {
                        override fun canceled(call: Call) {
                            cancelledPaths += call.request().url.encodedPath
                        }
                    },
                ).build()
        api =
            ApiClient(
                identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.2.0", build = "1", channel = "official"),
                baseUrl = { server.url("/").toString() },
                context = {
                    NetworkContext(PrivacyRaw.FULL, environmentSettled = true, credentialsReadable = true, update = UpdateRaw.SUPPORTED)
                },
                device = registry,
                tokens = MemorySessionTokens("access-A", "refresh-A", SessionScopeRaw.FULL, userId = "account-A"),
                pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
                hooks =
                    NetworkHooks(
                        reRegisterDevice = registry::reRegister,
                        quarantineDevice = registry::quarantine,
                        recoverDevice = registry::recoverAfterRejection,
                    ),
                http = http,
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
        // 同意隐私：第 1 次注册拿到 device-1
        var consented: Boolean? = null
        registry.registerAsync { consented = it }
        assertEquals(true, consented)
        assertEquals("device-1", registry.deviceId)
    }

    @After
    fun stop() {
        pool.shutdownNow()
        server.shutdown()
    }

    /** 评审场景：getMe → 10002 → 共享刷新 → 10402 → 设备重新注册。 */
    private fun refreshMeetsDeviceRejection() {
        otherReply = { request -> if (request.path == REFRESH) envelope(DEVICE_REJECTED) else envelope(SESSION_EXPIRED) }
    }

    private fun waitUntil(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SETTLE_MS)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(POLL_MS)
        assertTrue(what, condition())
    }

    /** 超时：注册请求已写出、服务端不回；桥调用到期 → 在途的注册请求随之取消，不记结果，凭据恢复交给退避链一次。 */
    @Test
    fun theReRegistrationInFlightIsCancelledWhenTheBridgeCallTimesOut() {
        refreshMeetsDeviceRejection()
        laterRegisterReply = { MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BUDGET_MS)
        val started = System.nanoTime()

        val result = api.execute(ApiCall("getMe", ownership = CallOwnership(inFlight.epochs(), deadline) { true }))

        assertTrue("按失败结束：$result", result !is ApiResult.Success)
        waitUntil("注册请求在截止时刻附近被取消：$cancelledPaths") { DEVICES in cancelledPaths }
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue("取消发生在截止时刻附近（用时 $elapsed 毫秒）", elapsed < BUDGET_MS + SLACK_MS)
        assertEquals("重新注册请求写出过一次（第 1 次是同意时）", 2, registerRequests.get())
        waitUntil("被拒凭据已隔离：交给退避链一次") { lost.get() == 1 }
        assertNull("没有保存任何新凭据", registry.deviceId)
        // 没记结果：之后有效的调用方仍可再注册（不复用这次被取消的「失败」）
        laterRegisterReply = { registered("device-3") }
        assertTrue(registry.reRegister("device-1"))
        assertEquals("device-3", registry.deviceId)
        assertEquals("退避链只交一次", 1, lost.get())
    }

    /** 关闭页面：注册请求实际写出前（读标识、求哈希之后）页面关闭 → 不写出；恢复交给退避链一次。 */
    @Test
    fun pageClosedBeforeTheReRegistrationIsWrittenSendsNothing() {
        refreshMeetsDeviceRejection()
        registry.hooks = DeviceRegistryHooks(beforeSend = { pageOpen = false })

        val result = api.execute(ApiCall("getMe", ownership = CallOwnership(inFlight.epochs()) { pageOpen }))

        assertTrue("按失败结束：$result", result !is ApiResult.Success)
        waitUntil("被拒凭据已隔离：交给退避链一次") { lost.get() == 1 }
        assertEquals("重新注册请求没有写出（只有同意时那一次）", 1, registerRequests.get())
        assertNull(registry.deviceId)
    }

    /** 关闭页面：读取设备标识之前页面关闭（普通请求直接 10402）→ 不读标识、不写出。 */
    @Test
    fun pageClosedBeforeTheIdIsReadReadsNothing() {
        otherReply = { envelope(DEVICE_REJECTED) }
        registry.hooks = DeviceRegistryHooks(beforeIdRead = { pageOpen = false })
        val readsBefore = idReads.get()

        val result = api.execute(ApiCall("getMe", ownership = CallOwnership(inFlight.epochs()) { pageOpen }))

        assertTrue("按失败结束：$result", result !is ApiResult.Success)
        assertEquals("页面关闭后不再读设备标识", readsBefore, idReads.get())
        assertEquals(1, registerRequests.get())
        assertEquals("被拒凭据已隔离：交给退避链一次", 1, lost.get())
    }

    /** 取得注册锁时调用方已失效：不注册、不记结果；被拒凭据已隔离的交给退避链一次。 */
    @Test
    fun aCallerAlreadyGoneWhenTheLockIsTakenRegistersNothing() {
        assertFalse(registry.reRegister("device-1", RecoveryCaller { false }))
        assertFalse("截止时间已过同样不注册", registry.reRegister("device-1", RecoveryCaller(System.nanoTime() - 1) { true }))
        assertEquals(1, registerRequests.get())
        assertEquals("同一被拒凭据只交一次", 1, lost.get())
        assertTrue("没有记下失败结果：有效的调用方照常注册", registry.reRegister("device-1"))
        assertEquals("device-2", registry.deviceId)
    }

    /** 共享注册：持锁那次的调用方在写出前失效，但窗口里还有一个有效的等待者 → 照常写出，两边都复用成功的结果。 */
    @Test
    fun aSharedReRegistrationContinuesWhileAnotherCallerIsStillValid() {
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        registry.hooks =
            DeviceRegistryHooks(beforeSend = {
                entered.countDown()
                gate.await(SETTLE_MS, TimeUnit.MILLISECONDS)
            })
        val firstOpen =
            java.util.concurrent.atomic
                .AtomicBoolean(true)
        val first = pool.submit<Boolean> { registry.reRegister("device-1", RecoveryCaller { firstOpen.get() }) }
        assertTrue(entered.await(SETTLE_MS, TimeUnit.MILLISECONDS))
        val second = pool.submit<Boolean> { registry.reRegister("device-1", RecoveryCaller { true }) }
        Thread.sleep(WAIT_MS)
        firstOpen.set(false)
        gate.countDown()

        assertTrue(first.get(SETTLE_MS, TimeUnit.MILLISECONDS))
        assertTrue(second.get(SETTLE_MS, TimeUnit.MILLISECONDS))
        assertEquals("共享注册照常写出一次", 2, registerRequests.get())
        assertEquals("device-2", registry.deviceId)
        assertEquals(0, lost.get())
    }

    /**
     * APP-04 共性第 61 条（复用「进行中」窗口的分支）：注册请求已写出、服务端不回；第二个调用方复用这个进行中的窗口（登记为窗口调用方）。
     * 只有发起者失效时在途请求照常等待；窗口里两个调用方都失效后才取消底层注册请求，两边都按失败结束、不记结果，恢复交给退避链一次。
     */
    @Test
    fun aSharedReRegistrationIsCancelledOnlyWhenEveryCallerInTheWindowIsGone() {
        laterRegisterReply = { MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
        val entered = CountDownLatch(1)
        registry.hooks = DeviceRegistryHooks(beforeSend = { entered.countDown() })
        val firstOpen =
            java.util.concurrent.atomic
                .AtomicBoolean(true)
        val secondOpen =
            java.util.concurrent.atomic
                .AtomicBoolean(true)
        val first = pool.submit<Boolean> { registry.reRegister("device-1", RecoveryCaller { firstOpen.get() }) }
        assertTrue(entered.await(SETTLE_MS, TimeUnit.MILLISECONDS))
        val second = pool.submit<Boolean> { registry.reRegister("device-1", RecoveryCaller { secondOpen.get() }) }
        Thread.sleep(WAIT_MS)

        firstOpen.set(false)
        Thread.sleep(WAIT_MS * 2)
        assertFalse("还有有效的复用者：在途注册不取消", DEVICES in cancelledPaths)

        secondOpen.set(false)
        waitUntil("窗口里的调用方全部失效：取消底层注册请求") { DEVICES in cancelledPaths }
        assertFalse(first.get(SETTLE_MS, TimeUnit.MILLISECONDS))
        assertFalse(second.get(SETTLE_MS, TimeUnit.MILLISECONDS))
        assertEquals("只写出过一次重新注册（第 1 次是同意时）", 2, registerRequests.get())
        assertEquals("同一被拒凭据只交退避链一次", 1, lost.get())
        assertNull(registry.deviceId)
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
        const val DEVICES = "/v1/devices"
        const val REFRESH = "/v1/auth/refresh"
        const val SESSION_EXPIRED = 10002
        const val DEVICE_REJECTED = 10402
        const val MILLIS = 1000L
        const val BUDGET_MS = 800L
        const val SLACK_MS = 700L
        const val SETTLE_MS = 3_000L
        const val WAIT_MS = 200L
        const val POLL_MS = 20L

        val ANDROID_ID: String =
            snapshotFile("device-hash.vectors.json")
                .getValue("hash_cases")
                .jsonArray
                .map { it.jsonObject }
                .last { it.getValue("id_source").jsonPrimitive.content == "android_id" }
                .getValue("input")
                .jsonPrimitive.content

        fun snapshotFile(name: String): JsonObject {
            val start = File(checkNotNull(System.getProperty("user.dir"))).canonicalFile
            val root = generateSequence(start) { it.parentFile }.first { File(it, "contract.lock").isFile }
            return Json.parseToJsonElement(File(root, "contracts/snapshot/specs/$name").readText()).jsonObject
        }

        fun vector(): JsonObject =
            snapshotFile("request-sign.vectors.json")
                .getValue("valid_cases")
                .jsonArray[0]
                .jsonObject
    }
}
