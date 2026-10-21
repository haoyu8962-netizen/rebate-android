package com.couli.app.clipboard

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
import com.couli.app.web.AppWeb
import com.couli.app.web.BridgeHostRegistry
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
import com.couli.core.update.MemoryUpdateStore
import com.couli.core.update.UpdateCheckOutcome
import com.couli.core.update.UpdateGate
import com.couli.feature.login.PendingInviteCode
import java.util.concurrent.TimeUnit
import javax.inject.Provider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * FU-CLIP C4：H5 经真实桥入口（AppWeb.newHost → BridgeHost.handle → AppWeb.Services.clipboardRead → AppClipboard.bridgeRead）调用 clipboard.read。
 * 只回本地候选；L2 拒绝（来源、子框架、手势、登录）时系统剪贴板读取次数为 0；调用期间失效（页面换文档 / 导航、撤回同意）按 90500 结算、不交回文本；
 * 回复与日志里搜不到原文片段。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class ClipboardBridgeReadTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = 1_800_000_000_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val system = CountingClipboard()
    private val inFlight = InFlightCalls()
    private val registry = BridgeHostRegistry()
    private lateinit var privacy: AppPrivacy
    private lateinit var clipboard: AppClipboard
    private lateinit var host: BridgeHost

    private class CountingClipboard : SystemClipboard {
        var content: ClipRead = ClipRead.Unavailable
        var reads = 0
        var writes = 0
        var onRead: () -> Unit = {}

        override fun read(): ClipRead {
            reads += 1
            onRead()
            return content
        }

        override fun write(
            text: String,
            label: String,
        ): Boolean {
            writes += 1
            return true
        }
    }

    private class MemoryStore(
        override var decisionRaw: String,
    ) : PrivacyStore {
        override var consentedVersion: String? = "1"
        override var consentedAtMillis: Long? = 0L
        override var basicMode: Boolean = decisionRaw == "basic_mode"
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

    @After
    fun tearDown() = scope.cancel()

    @Suppress("LongMethod") // 与 AppModule 同一套接线
    private fun launch(
        decision: String = "consented",
        loggedIn: Boolean = true,
    ) {
        context
            .getSharedPreferences(CouliPreferenceNamespaces.CLIPBOARD, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val gate =
            PrivacyGate(MemoryStore(decision), clock = { now }, bundledPolicyVersion = "1").apply {
                // AppModule 的撤回清理：作废全部容器里在途的桥调用、删盐与记录
                withdrawalCleanup = {
                    inFlight.onPrivacyRevoked()
                    registry.invalidateAll()
                    ClipboardPreferences(context).clearHashes()
                }
                retryScheduler = RetryScheduler { _, _ -> }
            }
        privacy = AppPrivacy(gate, environmentSettled = { true }, clock = { now }).also { it.coldStart() }
        val environment = EnvironmentGate { EnvironmentStartupReconciler.Outcome.UNCHANGED }.also { it.settleAtStartup() }
        val routing =
            AppRouting(
                context,
                environment,
                clock = { now },
                initialPrivacy = privacy.conclusion,
                privacyHooks = RoutingPrivacyHooks(promptShowing = { privacy.promptShowing }),
            )
        val tokens = if (loggedIn) MemorySessionTokens("access-one", "refresh-one", "full", "user-one") else MemorySessionTokens()
        val api =
            ApiClient(
                identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.0.0", build = "1", channel = "official"),
                baseUrl = { "https://api.example.test/" },
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
        clipboard =
            AppClipboard(
                system = system,
                api = ApiClientPageApi(api),
                routing = routing,
                privacy = { privacy.conclusion },
                promptShowing = { privacy.promptShowing },
                configData = { Json.parseToJsonElement(CONFIG) as JsonObject },
                prefs = ClipboardPreferences(context, SampleCipher),
                sessionEpoch = { tokens.loginEpoch },
                clock = { now },
                randomSalt = { SAMPLE_SALT.copyOf() },
                pendingInvite = PendingInviteCode(),
            )
        val updateStore = MemoryUpdateStore()
        val update =
            AppUpdate(
                gate =
                    UpdateGate(
                        updateStore,
                        clock = { now },
                        appVersion = "1.0.0",
                        platform = "android",
                        channel = "official",
                        installSource = null,
                    ),
                store = updateStore,
                checker = { CompletableDeferred<UpdateCheckOutcome>().await() },
                stores =
                    object : StoreAccess {
                        override val installSource: String? = null

                        override fun canOpen(storeKey: String) = false

                        override fun open(storeKey: String) = false
                    },
                scope = scope,
                canCheck = { false },
                clock = { now },
            )
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
                Provider { error("clipboard.read 不登录") },
                Provider { clipboard },
            )
        host = web.newHost(NoUi, scope)
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

    /** 经真实入口调一次 clipboard.read，返回回复的 JSON。 */
    private fun read(
        origin: String = ORIGIN,
        mainFrame: Boolean = true,
        gesture: Boolean = true,
    ): JsonObject {
        var reply: String? = null
        host.handle("""{"v":1,"id":"c-1","method":"clipboard.read","params":{}}""", origin, mainFrame, gesture) { reply = it }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S)
        while (reply == null && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(POLL_MS)
        }
        val text = checkNotNull(reply) { "没有回复" }
        replies += text
        return Json.parseToJsonElement(text).jsonObject
    }

    private val replies = mutableListOf<String>()

    private fun JsonObject.code(): Int = getValue("code").jsonPrimitive.int

    private fun JsonObject.text(): String? = (get("data") as? JsonObject)?.get("text")?.let { (it as JsonPrimitive).content }

    private fun assertNoRawAnywhere(vararg fragments: String) {
        val logs = ShadowLog.getLogs().joinToString("|") { it.msg.orEmpty() }
        val sent = replies.joinToString("|")
        fragments.forEach {
            assertFalse("回给 H5 的回复里不能有「$it」", sent.contains(it))
            assertFalse("日志里不能有「$it」", logs.contains(it))
        }
    }

    @Test
    fun c4_onlyTheWhitelistedLinkAndTheTitleAreReturned() {
        launch()
        system.content = ClipRead.Text("聊天样例 13800138000 验证码482731 https://m.tb.cn/h.x 「耳机」")
        val reply = read()
        assertEquals(0, reply.code())
        assertEquals("https://m.tb.cn/h.x\n耳机", reply.text())
        assertEquals(1, system.reads)
        assertEquals(0, system.writes)
        assertNoRawAnywhere("13800138000", "482731", "聊天样例")
    }

    @Test
    fun c4_chatInviteOrUnlistedOnlyReturnsAnEmptyString() {
        launch()
        for (raw in listOf(
            "今天下午三点见面，验证码482731。",
            "邀请码 K7M2QX https://share.example.test/i/K7M2QX",
            "https://example.com/a 「耳机 https://example.com/b」",
        )) {
            system.content = ClipRead.Text(raw)
            assertEquals("", read().text())
        }
        assertNoRawAnywhere("482731", "K7M2QX", "example.com")
    }

    @Test
    fun c4_l2RejectionsNeverTouchTheSystemClipboard() {
        launch()
        system.content = ClipRead.Text("https://m.tb.cn/h.x")
        assertEquals("非白名单来源", 90403, read(origin = "https://evil.example.test").code())
        assertEquals("子框架（含同源 iframe）", 90403, read(mainFrame = false).code())
        assertEquals("无手势", 90404, read(gesture = false).code())
        assertEquals(0, system.reads)
        launch(loggedIn = false)
        assertEquals("未登录", 90401, read().code())
        assertEquals("拒绝时系统剪贴板读取次数为 0", 0, system.reads)
    }

    /** K12：基本模式（未同意当前版本）在桥入口就按未登录拒绝，读取次数 0；纯逻辑层另有 consent 核对（向量 bridgeRead）。 */
    @Test
    fun c4_k12_basicModeDoesNotRead() {
        launch(decision = "basic_mode")
        system.content = ClipRead.Text("https://m.tb.cn/h.x")
        val reply = read()
        assertTrue(reply.code() != 0)
        assertNull(reply.text())
        assertEquals(0, system.reads)
    }

    /** K15：读到之后页面换文档 / 导航（容器作废在途调用）→ 90500，不交回任何文本。 */
    @Test
    fun c4_k15_navigationDuringTheCallReturnsNoText() {
        launch()
        system.content = ClipRead.Text("https://m.tb.cn/h.x 「耳机」")
        system.onRead = { host.invalidate() }
        val reply = read()
        assertEquals(90500, reply.code())
        assertNull(reply.text())
        assertEquals(1, system.reads)
        assertNoRawAnywhere("m.tb.cn", "耳机")
    }

    /** 读取期间撤回同意（撤回清理作废全部容器的在途调用）→ 90500，不交回文本。 */
    @Test
    fun c4_withdrawingConsentDuringTheCallReturnsNoText() {
        launch()
        system.content = ClipRead.Text("https://m.tb.cn/h.x")
        system.onRead = { privacy.withdraw() }
        val reply = read()
        assertEquals(90500, reply.code())
        assertNull(reply.text())
        assertNoRawAnywhere("m.tb.cn")
    }

    private companion object {
        const val ORIGIN = "https://h5.example.test"
        const val CONFIG =
            """{"bridge_origins":["https://h5.example.test"],"share_domains":["share.example.test"],"features":{"flags":{}}}"""
        const val WAIT_S = 5L
        const val POLL_MS = 10L
    }
}
