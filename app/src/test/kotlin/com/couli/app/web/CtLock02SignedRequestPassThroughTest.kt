package com.couli.app.web

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.clientcore.AppConfig
import com.couli.app.privacy.AppPrivacy
import com.couli.app.routing.AppRouting
import com.couli.app.routing.RoutingPrivacyHooks
import com.couli.app.update.AppUpdate
import com.couli.app.update.StoreAccess
import com.couli.core.bridge.BridgeHost
import com.couli.core.bridge.BridgeUi
import com.couli.core.clientcore.ClientPlatformRaw
import com.couli.core.clientcore.ConfigReply
import com.couli.core.clientcore.ConfigStore
import com.couli.core.clientcore.PrivacyStateRaw
import com.couli.core.clientcore.TrackerQueue
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.network.ApiClient
import com.couli.core.network.ClientIdentity
import com.couli.core.network.DeviceCredentials
import com.couli.core.network.InFlightCalls
import com.couli.core.network.MemoryPendingKeyBackend
import com.couli.core.network.MemorySessionTokens
import com.couli.core.network.NetworkContext
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PrivacyRaw
import com.couli.core.network.UpdateRaw
import com.couli.core.privacy.AndroidPermissionRequester
import com.couli.core.privacy.AndroidPermissionSystem
import com.couli.core.privacy.PermissionPrompts
import com.couli.core.privacy.PrivacyGate
import com.couli.core.privacy.PrivacyStore
import com.couli.core.privacy.RetryScheduler
import com.couli.core.router.PrivacyState
import com.couli.core.router.UpdateCheck
import com.couli.core.update.MemoryUpdateStore
import com.couli.core.update.UpdateCheckOutcome
import com.couli.core.update.UpdateCheckResult
import com.couli.core.update.UpdateGate
import com.couli.core.update.UpdateStore
import com.couli.core.update.UpdateStoreListing
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * CT-LOCK-02 编排裁定（契约即白名单，不在原生侧拦 createOrderClaim）：经真实桥入口（AppWeb.newHost → BridgeHost.handle →
 * AppWeb.Services.signedRequest → ApiClient）调 net.signedRequest POST /v1/orders/claims（已登录、已同意、有手势、可信来源）：
 * 放行，假服务只收到 1 次 POST /v1/orders/claims，桥回 0；不在 signed_paths 的路径仍是协议层 90403、不发。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class CtLock02SignedRequestPassThroughTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = 1_800_000_000_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val inFlight = InFlightCalls()
    private val registry = BridgeHostRegistry()
    private lateinit var server: MockWebServer
    private lateinit var host: BridgeHost
    private lateinit var update: AppUpdate

    private fun newGate(store: UpdateStore) =
        UpdateGate(store, clock = { now }, appVersion = "1.0.0", platform = "android", channel = "official", installSource = null)

    private fun checkResult() =
        UpdateCheckResult(
            minSupported = "1.0.0",
            recommended = "1.0.0",
            latest = "1.0.0",
            defaultStore = "huawei",
            stores = listOf(UpdateStoreListing("huawei", "1.0.0")),
        )

    private class MemoryStore : PrivacyStore {
        override var decisionRaw: String = "consented"
        override var consentedVersion: String? = "1"
        override var consentedAtMillis: Long? = 0L
        override var basicMode: Boolean = false
    }

    private object NoUi : BridgeUi {
        override fun toast(
            text: String,
            long: Boolean,
        ) = Unit

        override fun showLoading(text: String?) = Unit

        override fun hideLoading() = Unit

        override fun setNavBar(params: JsonObject) = true

        override fun close() = Unit
    }

    private object FixtureDevice : DeviceCredentials {
        override val deviceId: String = "device-fixture"
        override val installSecret: String = "install-fixture"
    }

    @Before
    @Suppress("LongMethod") // 与 AppModule 同一套接线
    fun setUp() {
        server = MockWebServer().apply { start() }
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody("""{"code":0,"msg":"","data":{},"trace_id":"t"}""")
            }
        val gate =
            PrivacyGate(MemoryStore(), clock = { now }, bundledPolicyVersion = "1").apply {
                retryScheduler = RetryScheduler { _, _ -> }
            }
        val privacy = AppPrivacy(gate, environmentSettled = { true }, clock = { now }).also { it.coldStart() }
        val environment = EnvironmentGate { EnvironmentStartupReconciler.Outcome.UNCHANGED }.also { it.settleAtStartup() }
        val routing =
            AppRouting(
                context,
                environment,
                clock = { now },
                initialPrivacy = privacy.conclusion,
                privacyHooks = RoutingPrivacyHooks(promptShowing = { privacy.promptShowing }),
            )
        val tokens = MemorySessionTokens("access-one", "refresh-one", "full", "user-one")
        val api =
            ApiClient(
                identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.0.0", build = "1", channel = "official"),
                baseUrl = { server.url("/").toString() },
                context = {
                    NetworkContext(
                        if (privacy.conclusion == PrivacyState.CONSENTED) PrivacyRaw.FULL else PrivacyRaw.BASIC,
                        environmentSettled = true,
                        credentialsReadable = true,
                        UpdateRaw.SUPPORTED,
                    )
                },
                device = FixtureDevice,
                tokens = tokens,
                pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
                inFlight = inFlight,
                sleeper = {},
            )
        val store =
            ConfigStore(
                readFile = { null },
                writeFile = {},
                fetcher = { _, done -> done(ConfigReply(ConfigReply.OK, CONFIG, null)) },
                clock = { now },
                bundled = CONFIG,
            )
        // 本进程一次成功检查（最低版本 1.0.0）：强更状态为 OK，桥的强更守卫不拦写操作（否则一律 90500，测不到代发这一步）
        val updateStore = MemoryUpdateStore()
        update =
            AppUpdate(
                gate = newGate(updateStore),
                store = updateStore,
                checker = { UpdateCheckOutcome.Success(checkResult()) },
                stores =
                    object : StoreAccess {
                        override val installSource: String? = null

                        override fun canOpen(storeKey: String) = false

                        override fun open(storeKey: String) = false
                    },
                scope = scope,
                canCheck = { true },
                clock = { now },
            ).also { it.coldStart() }
        val permissionPrefs = context.getSharedPreferences("couli_permission", Context.MODE_PRIVATE)
        val permissions =
            AndroidPermissionRequester(
                AndroidPermissionSystem(context, permissionPrefs, sdkInt = 33, declared = { true }, granted = { false }),
                PermissionPrompts(),
                permissionPrefs,
            )
        val web =
            AppWeb(
                context,
                AppConfig(store) { true }.also { it.startIfReady() },
                Provider { api },
                routing,
                privacy,
                update,
                Provider { tracker() },
                permissions,
                registry,
                tokens,
                inFlight,
                Provider { error("signedRequest 不登录") },
                Provider { error("signedRequest 不碰剪贴板") },
            )
        host = web.newHost(NoUi, scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun tracker() =
        TrackerQueue(
            readFile = { null },
            writeFile = {},
            sink = { false },
            privacy = PrivacyStateRaw.CONSENTED,
            userId = { null },
            deviceId = "device-fixture",
            platform = ClientPlatformRaw.ANDROID,
            appVersion = "1.0.0",
            sessionId = "session-test",
            clock = { now },
            canUpload = { false },
        )

    private fun signedRequest(
        path: String,
        id: String,
        method: String = "POST",
    ): Int {
        var reply: String? = null
        val message =
            """{"v":1,"id":"$id","method":"net.signedRequest","params":{"method":"$method","path":"$path","body":{"order_no":"123"}}}"""
        host.handle(message, ORIGIN, true, true) { reply = it }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S)
        while (reply == null && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(POLL_MS)
        }
        val text = checkNotNull(reply) { "没有回复" }
        return Json
            .parseToJsonElement(text)
            .jsonObject
            .getValue("code")
            .jsonPrimitive.int
    }

    private fun awaitUpdateOk() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S)
        while (update.routerUpdate != UpdateCheck.OK && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(POLL_MS)
        }
        assertEquals(UpdateCheck.OK, update.routerUpdate)
    }

    private fun snapshot(name: String): JsonObject {
        val start = File(checkNotNull(System.getProperty("user.dir"))).canonicalFile
        val repo = checkNotNull(generateSequence(start) { it.parentFile }.firstOrNull { File(it, "contract.lock").isFile })
        return Json.parseToJsonElement(File(repo, "contracts/snapshot/contracts/$name").readText()).jsonObject
    }

    /** 快照 signed_paths ∩ openapi x-signed：`METHOD path`（只读快照文件）。 */
    private fun expectedSigned(clientOperations: Map<String, Boolean>): Set<String> =
        snapshot("bridge.schema.json")
            .getValue("signed_paths")
            .jsonArray
            .map {
                it.jsonObject
                    .getValue("method")
                    .jsonPrimitive.content + " " +
                    it.jsonObject
                        .getValue("path")
                        .jsonPrimitive.content
            }.filter { clientOperations[it] == true }
            .toSet()

    /** 快照 openapi 的客户端接口（不含 /admin/）：`METHOD path` → x-signed。 */
    private fun clientOperations(): Map<String, Boolean> =
        snapshot("openapi.json")
            .getValue("paths")
            .jsonObject
            .filterKeys { !it.startsWith("/admin/") }
            .flatMap { (path, item) ->
                item.jsonObject.entries
                    .filter { (it.value as? JsonObject)?.containsKey("operationId") == true }
                    .map { (method, op) -> method.uppercase() + " " + path to (op.jsonObject["x-signed"]?.jsonPrimitive?.boolean ?: false) }
            }.toMap()

    /**
     * 复审 r1：把快照里每个客户端接口（签名与否）都当候选，经真实桥入口逐个代发；真正到达假服务的 `METHOD path` 集合必须等于
     * 快照 signed_paths ∩ openapi x-signed。白名单外的候选不回成功、服务端零请求。
     */
    @Test
    fun onlyContractSignedOperationsReachTheServerThroughTheRealBridge() {
        awaitUpdateOk()
        val operations = clientOperations()
        val expected = expectedSigned(operations)
        assertTrue("前提：快照 signed_paths 非空且是签名接口", expected.isNotEmpty())
        operations.keys.sorted().forEachIndexed { index, key ->
            val before = server.requestCount
            val code = signedRequest(key.substringAfter(' '), "s-$index", key.substringBefore(' '))
            if (key in expected) {
                assertEquals("放行：$key", OK, code)
                assertEquals("放行且只发 1 次：$key", before + 1, server.requestCount)
            } else {
                assertNotEquals("白名单外不回成功：$key", OK, code)
                assertEquals("白名单外零请求：$key", before, server.requestCount)
            }
        }
        val sent = List(server.requestCount) { checkNotNull(server.takeRequest(WAIT_S, TimeUnit.SECONDS)) }
        assertEquals(expected, sent.map { it.method + " " + it.requestUrl?.encodedPath }.toSet())
    }

    @Test
    fun orderClaimIsSentOnceThroughTheBridge() {
        awaitUpdateOk()
        assertEquals("POST /v1/orders/claims", OK, signedRequest("/v1/orders/claims", "c-1"))
        assertEquals("outside signed_paths", NOT_ALLOWED, signedRequest("/v1/links/convert", "c-2"))
        assertEquals("only one request reached the server", 1, server.requestCount)
        val recorded = checkNotNull(server.takeRequest(WAIT_S, TimeUnit.SECONDS))
        assertEquals("POST", recorded.method)
        assertEquals("/v1/orders/claims", recorded.requestUrl?.encodedPath)
    }

    private companion object {
        const val ORIGIN = "https://h5.example.test"
        const val CONFIG = """{"bridge_origins":["https://h5.example.test"],"features":{"flags":{}}}"""
        const val OK = 0
        const val NOT_ALLOWED = 90403
        const val WAIT_S = 5L
        const val POLL_MS = 10L
    }
}
