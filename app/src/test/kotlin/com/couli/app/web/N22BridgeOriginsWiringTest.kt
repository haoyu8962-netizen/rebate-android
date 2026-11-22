package com.couli.app.web

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.N22TestSigning
import com.couli.app.clientcore.AppConfig
import com.couli.app.privacy.AppPrivacy
import com.couli.app.routing.AppRouting
import com.couli.app.routing.RoutingPrivacyHooks
import com.couli.app.update.AppUpdate
import com.couli.app.update.StoreAccess
import com.couli.core.bridge.BridgeHost
import com.couli.core.bridge.BridgeUi
import com.couli.core.clientcore.BridgeOriginsLastGood
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
import com.couli.core.update.MemoryUpdateStore
import com.couli.core.update.UpdateCheckOutcome
import com.couli.core.update.UpdateCheckResult
import com.couli.core.update.UpdateGate
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
import kotlinx.serialization.json.int
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
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * N-22（D10-2539）App 接线：生产可信源只认验签通过的「上次可用白名单」。
 * - 端到端：真 ConfigStore + 生产同一个验签闭包（AppConfig.bridgeOriginsVerifier，注入测试钥、运行环境 test）+ 假网络回 /v1/config
 *   → AppWeb.trustedConfig() 与 newHost 的桥协议认该 origin；再回同版本但签名坏的 → 仍认（保留上次可用）；新装机只回旧数组形状 → 不认。
 * - 验签闭包每次现取运行环境 id。
 * - Release 的构建配置与源码集不含 staging 钥（照 StagingStreamReleaseSourceTest 读源码的写法）。
 * staging 构建钥表与 staging 实测在 app/src/testDebug 的 N22BridgeOriginsBuildKeysTest（要 debug 变体的 BuildConfig）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class N22BridgeOriginsWiringTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = 1_800_000_000_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val inFlight = InFlightCalls()
    private lateinit var server: MockWebServer
    private lateinit var store: ConfigStore
    private lateinit var web: AppWeb
    private lateinit var host: BridgeHost
    private var nextReply = ConfigReply(ConfigReply.FAILURE, null, null)
    private var callSequence = 0
    private var served = 0

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
    @Suppress("LongMethod") // 与 AppModule 同一套接线（照 CtLock02SignedRequestPassThroughTest）
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
        store =
            ConfigStore(
                readFile = { null },
                writeFile = {},
                fetcher = { _, done -> done(nextReply) },
                clock = { now },
                bundled = BUNDLED,
                verifyBridgeOrigins = N22TestSigning.verifier(),
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
        web =
            AppWeb(
                context,
                AppConfig(store) { true },
                Provider { api },
                routing,
                privacy,
                update,
                Provider { tracker() },
                permissions,
                BridgeHostRegistry(),
                tokens,
                inFlight,
                Provider { error("N-22 接线测试不登录") },
                Provider { error("N-22 接线测试不碰剪贴板") },
            )
        host = web.newHost(NoUi, scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun checkResult() =
        UpdateCheckResult(
            minSupported = "1.0.0",
            recommended = "1.0.0",
            latest = "1.0.0",
            defaultStore = "huawei",
            stores = listOf(UpdateStoreListing("huawei", "1.0.0")),
        )

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

    /** 假网络回一份 /v1/config 的 data。 */
    private fun serve(body: String) {
        nextReply = ConfigReply(ConfigReply.OK, body, "\"n22-${++served}\"")
        if (served == 1) store.onColdStart() else store.onForeground()
        assertEquals("前提：这份配置已生效", Json.parseToJsonElement(body), store.snapshots.value.data)
    }

    /** 经生产桥入口（AppWeb.newHost → BridgeHost.handle）从 [origin] 调一次只读的 app.getEnv，返回桥码。 */
    private fun getEnvFrom(origin: String): Int {
        var reply: String? = null
        val message = """{"v":1,"id":"n22-${++callSequence}","method":"app.getEnv","params":{}}"""
        host.handle(message, origin, true, true) { reply = it }
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

    private fun assertProductionTrusts(
        what: String,
        origins: Set<String>,
    ) {
        assertEquals("$what：ConfigStore.trustedOrigins", origins.toList(), store.trustedOrigins)
        assertEquals("$what：AppWeb.trustedConfig().trustedOrigins", origins, web.trustedConfig().trustedOrigins)
    }

    @Test
    fun e1SignedConfigFromTheNetworkIsTrustedAndSurvivesABadSignatureOfTheSameVersion() {
        serve(config(N22TestSigning.jws(listOf(ORIGIN), version = 3)))
        assertProductionTrusts("验过的白名单", setOf(ORIGIN))
        assertEquals("桥协议认该 origin", OK, getEnvFrom(ORIGIN))
        assertEquals("白名单以外的 origin 不认", NOT_ALLOWED, getEnvFrom(EVIL))

        // 同版本、签名坏、还多带一个 origin：这份配置照常生效，但白名单保留上次可用
        serve(config(N22TestSigning.jws(listOf(ORIGIN, EVIL), version = 3, badSignature = true), tag = "bad-signature"))
        assertProductionTrusts("签名坏的同版本", setOf(ORIGIN))
        assertEquals(3L, store.trustedOriginsVersion)
        assertEquals("仍认原 origin", OK, getEnvFrom(ORIGIN))
        assertEquals("坏签名里的 origin 不认", NOT_ALLOWED, getEnvFrom(EVIL))
    }

    @Test
    fun e2FreshInstallWithOnlyTheOldArrayShapeTrustsNothing() {
        serve("""{"bridge_origins":["$ORIGIN"],"features":{"flags":{}}}""")
        assertProductionTrusts("旧数组形状", emptySet())
        assertNull(store.trustedOriginsVersion)
        assertEquals("桥协议不认", NOT_ALLOWED, getEnvFrom(ORIGIN))
    }

    @Test
    fun e3BundledPlaceholderAloneTrustsNothing() {
        serve(BUNDLED)
        assertProductionTrusts("只有占位 jws", emptySet())
        assertEquals(NOT_ALLOWED, getEnvFrom(ORIGIN))
    }

    @Test
    fun v1VerifierReadsTheRuntimeEnvironmentOnEveryCall() {
        var env = "staging"
        val verify = AppConfig.bridgeOriginsVerifier(environmentId = { env }, keys = N22TestSigning.keys)
        val data = Json.parseToJsonElement(config(N22TestSigning.jws(listOf(ORIGIN), version = 2))).jsonObject
        assertNull("运行环境 staging：测试钥（env test）不适用", verify(data, null))
        env = N22TestSigning.ENV
        assertEquals("切到 test 后用新环境", BridgeOriginsLastGood(2L, listOf(ORIGIN)), verify(data, null))
        assertNull("回退", verify(data, 3L))
        assertEquals("版本相等不算回退", BridgeOriginsLastGood(2L, listOf(ORIGIN)), verify(data, 2L))
        val otherApp = AppConfig.bridgeOriginsVerifier(environmentId = { env }, keys = N22TestSigning.keys, appId = "couli-other")
        assertNull("app_id 不符", otherApp(data, null))
        assertEquals("X-App-Id 与 payload.app_id 同源", "couli", AppConfig.APP_ID)
    }

    @Test
    fun r1ReleaseBuildConfigAndSourcesCarryNoStagingKey() {
        val gradle = code(File(N22TestSigning.root, "app/build.gradle.kts").readText())
        val release = block(gradle, """getByName("release")""")
        assertTrue("release 有 CONFIG_SIGN_KEYS：$release", release.contains("\"CONFIG_SIGN_KEYS\""))
        val field = Regex("""buildConfigField\("String","CONFIG_SIGN_KEYS",(.*?)\)""").find(release)?.groupValues?.get(1)
        assertEquals("release 的 CONFIG_SIGN_KEYS 只取 configSignKeysProd", "\"\\\"\$configSignKeysProd\\\"\"", field)
        val prod = Regex("""valconfigSignKeysProd=(.*?)(?=val|buildTypes|$)""").find(gradle)?.groupValues?.get(1)
        assertTrue("找到 configSignKeysProd 的定义", prod != null)
        STAGING_MARKERS.forEach { marker ->
            assertFalse("release 块不得出现 $marker", release.contains(marker))
            assertFalse("configSignKeysProd 不得含 $marker：$prod", checkNotNull(prod).contains(marker))
        }
        File(N22TestSigning.root, "app/src/release").walkTopDown().filter { it.isFile }.forEach { file ->
            val text = file.readText()
            STAGING_MARKERS.forEach { assertFalse("${file.path} 不得含 $it", text.contains(it)) }
        }
    }

    /** 去掉块注释、行注释与全部空白。 */
    private fun code(text: String) =
        text
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")
            .replace(Regex("""\s+"""), "")

    /** [head] 之后第一个 `{` 起、括号配平的块（不含外层括号）。 */
    private fun block(
        compact: String,
        head: String,
    ): String {
        val start = compact.indexOf(head).also { assertTrue("找到 $head", it >= 0) }
        val open = compact.indexOf('{', start)
        var depth = 0
        for (i in open until compact.length) {
            when (compact[i]) {
                '{' -> depth += 1
                '}' -> if (--depth == 0) return compact.substring(open + 1, i)
            }
        }
        throw AssertionError("$head 的块没有配平")
    }

    private companion object {
        const val ORIGIN = "https://h5.example.test"
        const val EVIL = "https://evil.example.test"
        const val OK = 0
        const val NOT_ALLOWED = 90403
        const val WAIT_S = 5L
        const val POLL_MS = 10L

        /** 包内默认配置同形：bridge_origins 是占位 jws。 */
        const val BUNDLED = """{"bridge_origins":{"jws":"aGVhZGVy.cGF5bG9hZA.c2lnbmF0dXJl"},"features":{"flags":{}}}"""

        /** staging 钥在仓库里出现的样子：env 前缀、kid、SPKI 公钥点的十六进制片段（两段各取一截，跨行拼接时也能命中）。 */
        val STAGING_MARKERS =
            listOf(
                "staging:",
                "config-staging",
                "configSignKeysStaging",
                "configSignKeysNonRelease",
                "ae883f464888bece2a33c015a42e897e",
                "f359128f1ee420e841b4b286d06e4878",
            )

        fun config(
            jws: String,
            tag: String = "signed",
        ) = """{"config_version":"$tag","bridge_origins":{"jws":"$jws"},"features":{"flags":{}}}"""
    }
}
