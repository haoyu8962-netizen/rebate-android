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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * APP-04 共性第 64 条（Android APP-05a 第 1 轮评审 R1-07）：共享的设备重新注册请求用自己的网络超时，不固定为发起者的截止时刻。
 * 场景：两个在途请求先后收到 10402。A 只剩 1 秒预算，先发起重新注册；注册请求写出之后，预算长得多的 B 加入同一窗口。
 * A 到期只结束 A 自己的等待（返回 false），底层注册请求不取消；服务端 2 秒多后回复，B 拿到成功、新凭据保存。
 */
class DeviceReRegisterSharedBudgetTest {
    private lateinit var server: MockWebServer
    private val pool = Executors.newCachedThreadPool()
    private val inFlight = InFlightCalls()
    private val store = MemoryDeviceCredentialStore()
    private val registerRequests = AtomicInteger()
    private val idReads = AtomicInteger()
    private val lost = AtomicInteger()
    private val cancelledPaths: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val sign = vector()

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

    private fun waitUntil(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SETTLE_MS)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(POLL_MS)
        assertTrue(what, condition())
    }

    @Test
    fun aLongerBudgetCallerJoiningAfterTheWriteKeepsTheSharedRegistrationAlive() {
        val written = CountDownLatch(1)
        laterRegisterReply = {
            written.countDown()
            registered("device-2").setBodyDelay(SERVER_DELAY_MS, TimeUnit.MILLISECONDS)
        }
        val startedA = System.nanoTime()
        val callerA = RecoveryCaller(startedA + TimeUnit.MILLISECONDS.toNanos(SHORT_BUDGET_MS)) { true }
        val first = pool.submit<Boolean> { registry.reRegister("device-1", callerA) }
        assertTrue("A 发起的注册请求已写出", written.await(SETTLE_MS, TimeUnit.MILLISECONDS))

        val callerB = RecoveryCaller(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LONG_BUDGET_MS)) { true }
        val second = pool.submit<Boolean> { registry.reRegister("device-1", callerB) }

        assertFalse("A 预算到了：A 自己按失败结束", first.get(SETTLE_MS, TimeUnit.MILLISECONDS))
        val elapsedA = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedA)
        assertTrue("A 在自己的预算附近结束等待（$elapsedA 毫秒），不陪着等共享请求", elapsedA < SERVER_DELAY_MS)
        assertFalse("B 仍有效：底层注册请求不取消：$cancelledPaths", DEVICES in cancelledPaths)

        assertTrue("B 拿到共享注册的成功结果", second.get(LONG_BUDGET_MS, TimeUnit.MILLISECONDS))
        assertEquals("device-2", registry.deviceId)
        assertEquals("只写出过一次重新注册（第 1 次是同意时）", 2, registerRequests.get())
        assertFalse(DEVICES in cancelledPaths)
        waitUntil("没有交给退避链") { lost.get() == 0 }
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
        const val MILLIS = 1000L
        const val SHORT_BUDGET_MS = 1_000L
        const val SERVER_DELAY_MS = 2_500L
        const val LONG_BUDGET_MS = 10_000L
        const val SETTLE_MS = 5_000L
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
