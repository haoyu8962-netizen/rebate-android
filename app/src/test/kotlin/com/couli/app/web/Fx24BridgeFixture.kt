package com.couli.app.web

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.couli.app.clientcore.AppConfig
import com.couli.app.clipboard.AppClipboard
import com.couli.app.clipboard.ClipboardPreferences
import com.couli.app.clipboard.SAMPLE_SALT
import com.couli.app.clipboard.SampleCipher
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
import com.couli.core.clipboard.ClipRead
import com.couli.core.clipboard.SystemClipboard
import com.couli.core.config.CouliPreferenceNamespaces
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.network.ApiClient
import com.couli.core.network.ApiClientPageApi
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
import java.util.concurrent.TimeUnit
import javax.inject.Provider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf

/** F-X-24：沿用 CT-LOCK-02 假服务和 ClipboardBridgeReadTest 的真实 App 接线；所有替身只在测试源码集。 */
internal class Fx24BridgeFixture : AutoCloseable {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = 1_800_000_000_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val inFlight = InFlightCalls()
    private val registry = BridgeHostRegistry()
    private val tokens = MemorySessionTokens("access-one", "refresh-one", "full", "user-one")
    val server = MockWebServer()
    lateinit var host: BridgeHost
        private set
    private lateinit var update: AppUpdate
    private var pendingUpdate: CompletableDeferred<UpdateCheckOutcome>? = null
    lateinit var probe: Fx24BridgeProbe
        private set

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

    @Suppress("LongMethod") // 与 AppModule 同一套接线
    fun start() {
        server.start()
        context
            .getSharedPreferences(CouliPreferenceNamespaces.CLIPBOARD, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
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
        store =
            ConfigStore(
                readFile = { null },
                writeFile = {},
                fetcher = { _, done -> done(nextReply) },
                clock = { now },
                bundled = BASE_CONFIG,
            )
        val clipboard =
            AppClipboard(
                system = system,
                api = ApiClientPageApi(api),
                routing = routing,
                privacy = { privacy.conclusion },
                promptShowing = { privacy.promptShowing },
                configData = { store.snapshots.value.data },
                prefs = ClipboardPreferences(context, SampleCipher),
                sessionEpoch = { tokens.loginEpoch },
                clock = { now },
                randomSalt = { SAMPLE_SALT.copyOf() },
            )
        val tracker = tracker()
        // 本进程一次成功检查（最低版本 1.0.0）：强更状态为 OK，桥的强更守卫不拦写操作（否则一律 90500，测不到代发这一步）
        val updateStore = MemoryUpdateStore()
        update =
            AppUpdate(
                gate = newGate(updateStore),
                store = updateStore,
                checker = { pendingUpdate?.await() ?: UpdateCheckOutcome.Success(checkResult()) },
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
                Provider { tracker },
                permissions,
                registry,
                tokens,
                inFlight,
                Provider { error("F-X-24 不打开登录页") },
                Provider {
                    clipboardHandlerEntries += 1
                    clipboard
                },
            )
        host = web.newHost(NoUi, scope)
        probe = Fx24BridgeProbe(host)
        awaitUpdateOk()
    }

    override fun close() {
        if (::host.isInitialized) host.dispose()
        scope.cancel()
        server.shutdown()
    }

    private fun tracker() =
        TrackerQueue(
            readFile = { null },
            writeFile = { trackerDisk = it },
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

    fun call(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
    ): JsonObject = beginCall(method, params).awaitReply()

    fun clearSession() = tokens.clear()

    fun beginCall(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
    ): PendingCall {
        val id = "fx24-${++callSequence}"
        val pending = PendingCall(id, method)
        val message =
            buildJsonObject {
                put("v", 1)
                put("id", id)
                put("method", method)
                put("params", params)
            }.toString()
        host.handle(message, ORIGIN, true, true, reply = pending::receive)
        return pending
    }

    class PendingCall(
        private val id: String,
        private val method: String,
    ) {
        private val replies = mutableListOf<String>()
        val hasReply: Boolean get() = replies.isNotEmpty()

        fun receive(reply: String) {
            replies += reply
        }

        fun awaitReply(): JsonObject {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S)
            while (!hasReply && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idleFor(POLL_MS, TimeUnit.MILLISECONDS)
                Thread.sleep(POLL_MS)
            }
            assertEquals("$method 必须且只能回复一次", 1, replies.size)
            val result = Json.parseToJsonElement(replies.single()).jsonObject
            assertEquals("响应必须对应本次调用", id, result.getValue("id").jsonPrimitive.content)
            return result
        }
    }

    /** 本进程的真实 AppUpdate 发起复查，只有测试主动完成 deferred 才能结束检查。 */
    fun holdUpdateCheck() {
        check(pendingUpdate == null)
        pendingUpdate = CompletableDeferred()
        update.recheck()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(UpdateCheck.CHECKING, update.routerUpdate)
    }

    fun assertWaitingForUpdate(pending: PendingCall) {
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("调用必须已进入真实 AppWeb.awaitUpdate", 1, probe.updateWaitEntries)
        assertEquals(UpdateCheck.CHECKING, update.routerUpdate)
        assertFalse("检查器仍由测试挂起", checkNotNull(pendingUpdate).isCompleted)
        assertFalse("强更放行前不能提前结算", pending.hasReply)
        assertEquals(0, probe.signedRequestEntries)
        assertEquals(0, server.requestCount)
        assertTrue(bridgeEvents().isEmpty())
    }

    fun releaseUpdateCheck() {
        assertTrue(checkNotNull(pendingUpdate).complete(UpdateCheckOutcome.Success(checkResult())))
        awaitUpdateOk()
        pendingUpdate = null
    }

    private fun awaitUpdateOk() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S)
        while (update.routerUpdate != UpdateCheck.OK && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(POLL_MS)
        }
        assertEquals(UpdateCheck.OK, update.routerUpdate)
    }

    private lateinit var store: ConfigStore
    private var nextReply = ConfigReply(ConfigReply.OK, BASE_CONFIG, null)
    private var trackerDisk: String? = null
    private var callSequence = 0
    private var configSequence = 0
    val system = CountingClipboard()
    var clipboardHandlerEntries = 0
        private set
    val effectiveConfig: JsonObject get() = store.snapshots.value.data

    fun updateDisabled(vararg methods: String) {
        updateDisabledValue(JsonArray(methods.map(::JsonPrimitive)))
    }

    fun updateDisabledValue(value: JsonElement): JsonObject {
        val body = JsonObject(Json.parseToJsonElement(BASE_CONFIG).jsonObject + (KEY to value))
        nextReply = ConfigReply(ConfigReply.OK, body.toString(), "\"fx24-${++configSequence}\"")
        store.onForeground()
        assertEquals(body, store.snapshots.value.data)
        return effectiveConfig
    }

    fun refreshWithoutChangingConfig(kind: String) {
        nextReply = ConfigReply(kind, null, null)
        store.onForeground()
    }

    fun bridgeEvents(): List<JsonObject> =
        trackerDisk
            ?.let {
                Json.parseToJsonElement(it).jsonObject.getValue("events").jsonArray.map { item ->
                    Json
                        .parseToJsonElement(
                            item.jsonObject
                                .getValue("event")
                                .jsonPrimitive.content,
                        ).jsonObject
                }
            }.orEmpty()
            .filter { it["name"]?.jsonPrimitive?.content == "bridge_call" }

    class CountingClipboard : SystemClipboard {
        var reads = 0
            private set

        override fun read(): ClipRead {
            reads += 1
            return ClipRead.Text("https://m.tb.cn/h.fx24 「耳机」")
        }

        override fun write(
            text: String,
            label: String,
        ): Boolean = error("测试不应写入剪贴板")
    }

    companion object {
        const val CLIPBOARD = "clipboard.read"
        const val SIGNED_REQUEST = "net.signedRequest"
        const val WAIT_S = 5L
        private const val ORIGIN = "https://h5.example.test"
        private const val KEY = "bridge_disabled_methods"
        private const val BASE_CONFIG =
            """{"bridge_origins":["https://h5.example.test"],"share_domains":["share.example.test"],"features":{"flags":{}}}"""
        private const val POLL_MS = 10L

        // CreateOrderClaimRequest 的全部必填字段（platform / order_no / paid_date），不是只带订单号的残缺请求。
        val CLAIM_BODY: JsonObject =
            Json.parseToJsonElement("""{"platform":"taobao","order_no":"FX24-ORDER-1","paid_date":"2026-10-20"}""").jsonObject
        val CLAIM_PARAMS: JsonObject =
            buildJsonObject {
                put("method", "POST")
                put("path", "/v1/orders/claims")
                put("body", CLAIM_BODY)
            }
    }
}
