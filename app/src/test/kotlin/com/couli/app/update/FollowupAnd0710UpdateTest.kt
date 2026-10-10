package com.couli.app.update

import android.app.Application
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.network.ApiClient
import com.couli.core.network.ApiUpdateChecker
import com.couli.core.network.ClientIdentity
import com.couli.core.network.DeviceCredentials
import com.couli.core.network.MemoryPendingKeyBackend
import com.couli.core.network.MemorySessionTokens
import com.couli.core.network.NetworkContext
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PrivacyRaw
import com.couli.core.network.UpdateRaw
import com.couli.core.update.MemoryUpdateStore
import com.couli.core.update.UpdateCheckOutcome
import com.couli.core.update.UpdateConfigReader
import com.couli.core.update.UpdateGate
import com.couli.core.update.UpdateStatus
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
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
 * FU-AND-0710：F-X-01（Android）真实版本检查器 + F-AND-24（在途补查时点【重试】不被吞）。
 * 真实 [ApiClient] + [ApiUpdateChecker] + [AppUpdate]，本地假服务 MockWebServer，不连真实后端。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class FollowupAnd0710UpdateTest {
    private lateinit var server: MockWebServer
    private var privacy = PrivacyRaw.FULL
    private var allowed = true
    private var now = 10_000_000L
    private var configInterval: Int? = null

    /** 依次回答的响应；为空时回「强更」样例。 */
    private val answers = ArrayDeque<MockResponse>()

    @Volatile
    private var hold: CountDownLatch? = null

    private class Stores(
        private val openable: Set<String>,
    ) : StoreAccess {
        val opened = mutableListOf<String>()
        override val installSource: String? = null

        override fun canOpen(storeKey: String) = storeKey in openable

        override fun open(storeKey: String): Boolean {
            opened += storeKey
            return true
        }
    }

    private object FixedDevice : DeviceCredentials {
        override val deviceId: String? = "device-fixture"
        override val installSecret: String? = "test-only.install.device-a"
    }

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    hold?.await(WAIT_SECONDS, TimeUnit.SECONDS)
                    return synchronized(answers) { answers.removeFirstOrNull() } ?: check(min = "9.0.0")
                }
            }
        server.start()
    }

    @After
    fun stop() {
        hold?.countDown()
        server.shutdown()
    }

    private fun api() =
        ApiClient(
            identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.0.0", build = "1", channel = "official"),
            baseUrl = { server.url("/").toString() },
            context = { NetworkContext(privacy, environmentSettled = true, credentialsReadable = true, UpdateRaw.UNKNOWN) },
            device = FixedDevice,
            tokens = MemorySessionTokens(),
            pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
        )

    private fun checker(client: ApiClient = api()) = ApiUpdateChecker({ client }, { allowed }, { configInterval })

    private fun update(
        checker: ApiUpdateChecker,
        stores: StoreAccess = Stores(emptySet()),
    ): AppUpdate {
        val store = MemoryUpdateStore()
        val gate =
            UpdateGate(store, clock = { now }, appVersion = "1.0.0", platform = "android", channel = "official", installSource = null)
        return AppUpdate(
            gate = gate,
            store = store,
            checker = checker,
            stores = stores,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            canCheck = { allowed },
            clock = { now },
            recheckIntervalSec = { configInterval },
        )
    }

    /** 契约示例形状的 check.data（字段以 openapi AppVersionCheck 为准）。 */
    private fun check(
        min: String?,
        listed: String = "9.0.0",
    ): MockResponse {
        val minJson = min?.let { "\"$it\"" } ?: "null"
        val data =
            """{"latest_version":"9.0.0","min_supported_version":$minJson,"recommended_version":$minJson,""" +
                """"update_title":"t","update_notes":"n","store_url":"https://store.example.test/app/couli","default_store":"huawei",""" +
                """"stores":[{"store":"huawei","listed_version":"$listed"}]}"""
        return envelope(0, data)
    }

    private fun envelope(
        code: Int,
        data: String,
    ) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"code":$code,"msg":"","data":$data,"trace_id":"trace-0123456789"}""")

    private fun answer(response: MockResponse) = synchronized(answers) { answers.addLast(response) }

    /** 主线程空转直到条件成立（检查在 IO 线程、结果回主线程）。 */
    private fun awaitMain(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (!condition()) {
            assertTrue("等待超时：$what", System.nanoTime() < deadline)
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(POLL_MILLIS)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun awaitRequests(count: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (server.requestCount < count) {
            assertTrue("等待第 $count 个请求超时", System.nanoTime() < deadline)
            Thread.sleep(POLL_MILLIS)
        }
    }

    @Test
    fun fx01_forcedEntryWithoutResultSendsOneRealCheckAndReusesInFlight() {
        val checker = checker()
        val update = update(checker)
        answer(envelope(ERROR_CODE, "null"))
        update.coldStart()
        awaitMain("冷启动检查失败") { !checker.isRunning && update.ui.value.status == UpdateStatus.UNKNOWN }
        assertEquals(1, server.requestCount)
        val first = server.takeRequest()
        assertEquals("GET", first.method)
        assertEquals("/v1/app-versions/check", first.requestUrl?.encodedPath)
        assertNull("契约没有 query", first.requestUrl?.query)

        hold = CountDownLatch(1)
        update.onServerForced()
        assertEquals(UpdateStatus.BELOW_MIN, update.ui.value.status)
        awaitRequests(2)
        update.on10405(null)
        update.on10405("9.0.0")
        update.recheck()
        Thread.sleep(POLL_MILLIS * 3)
        assertEquals("补查在途：再进强更、【重试】都复用，不另发", 2, server.requestCount)
        assertEquals(2, checker.requests)
        hold?.countDown()
        awaitMain("补查结束") { !checker.isRunning && update.ui.value.result != null }
        assertEquals(UpdateStatus.BELOW_MIN, update.ui.value.status)
        assertEquals(
            "9.0.0",
            update.ui.value.result
                ?.minSupported,
        )
        assertEquals(2, server.requestCount)
    }

    @Test
    fun fx01_notConsentedBasicModeOrUnsettledNeverSend() {
        allowed = false
        val update = update(checker())
        update.coldStart()
        update.recheck(thenPickStore = true)
        update.onServerForced()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, server.requestCount)

        // 检查器自己的闸门：AppUpdate 之外直接调用也不发
        val direct = checker()
        assertEquals(UpdateCheckOutcome.Failure, runBlocking { direct.check() })
        assertEquals(0, direct.requests)

        // 纵深：网络层在基本模式本地拒绝 checkAppVersion
        allowed = true
        privacy = PrivacyRaw.BASIC
        assertEquals(UpdateCheckOutcome.Failure, runBlocking { checker().check() })
        assertEquals(0, server.requestCount)
    }

    @Test
    fun fx01_failuresAreUnknownAndDoNotBlock() {
        val missingMinimum =
            """{"latest_version":"9.0.0","recommended_version":null,"stores":[{"store":"huawei","listed_version":"9.0.0"}]}"""
        listOf(
            envelope(ERROR_CODE, "null"),
            envelope(0, missingMinimum),
            MockResponse().setResponseCode(HTTP_NOT_FOUND).setBody("not found"),
        ).forEach { response ->
            answer(response)
            val checker = checker()
            val update = update(checker)
            update.coldStart()
            awaitMain("检查结束") { !checker.isRunning && update.ui.value.status != UpdateStatus.CHECKING }
            assertEquals("失败按 unknown，不误拦", UpdateStatus.UNKNOWN, update.ui.value.status)
            assertNull(update.ui.value.result)
        }
        // 对照：min 为 null（没设最低版本）是合法响应，不拦
        answer(check(min = null))
        val checker = checker()
        val update = update(checker)
        update.coldStart()
        awaitMain("检查结束") { update.ui.value.result != null }
        assertEquals(UpdateStatus.OK, update.ui.value.status)
    }

    /** F-AND-24：自动补查在途时点【去更新】（没有商店列表 → unavailable）再点【重试】：不另发，在途检查结束后选店。 */
    @Test
    fun fAnd24_retryWhileInFlightIsNotSwallowed() {
        val stores = Stores(setOf("huawei"))
        val checker = checker()
        val update = update(checker, stores)
        answer(envelope(ERROR_CODE, "null"))
        update.coldStart()
        awaitMain("冷启动检查失败") { !checker.isRunning && update.ui.value.status == UpdateStatus.UNKNOWN }

        hold = CountDownLatch(1)
        update.onServerForced()
        awaitRequests(2)
        update.goToStore()
        assertTrue("没有商店列表：显示 unavailable 与【重试】", update.ui.value.forceUnavailable)
        update.recheck(thenPickStore = true)
        assertTrue("点击即显示检查中", update.ui.value.working)
        assertFalse(update.ui.value.forceUnavailable)
        hold?.countDown()
        awaitMain("补查结束") { !checker.isRunning && !update.ui.value.working }
        assertEquals("在途检查结束后选店（不被吞）", listOf("huawei"), stores.opened)
        assertEquals("复用在途，不另发", 2, server.requestCount)
    }

    /** F-AND-24：在途检查失败 → 显示 unavailable 与【重试】；再点【重试】真正重发并选店。 */
    @Test
    fun fAnd24_retryAfterInFlightFailureReallyResends() {
        val stores = Stores(setOf("huawei"))
        val checker = checker()
        val update = update(checker, stores)
        answer(envelope(ERROR_CODE, "null"))
        update.coldStart()
        awaitMain("冷启动检查失败") { !checker.isRunning }

        hold = CountDownLatch(1)
        answer(envelope(ERROR_CODE, "null"))
        update.onServerForced()
        awaitRequests(2)
        update.recheck(thenPickStore = true)
        hold?.countDown()
        awaitMain("补查失败") { !checker.isRunning && !update.ui.value.working }
        assertTrue("失败后给出 unavailable 与【重试】，不是无反应", update.ui.value.forceUnavailable)
        assertEquals(2, server.requestCount)

        hold = null
        update.recheck(thenPickStore = true)
        awaitMain("重发结束") { !checker.isRunning && !update.ui.value.working }
        assertEquals("失败后再点【重试】真正重发", 3, server.requestCount)
        assertEquals(listOf("huawei"), stores.opened)
    }

    /** 回前台复查间隔读当前生效配置：成功时 1800、之后配置改成 60 → 61 秒回前台就查（旧逻辑按 1800 不查）。 */
    @Test
    fun fx01_foregroundIntervalFollowsCurrentConfig() {
        configInterval = SHORT_LONG
        val checker = checker()
        val update = update(checker)
        answer(check(min = "1.0.0"))
        update.coldStart()
        awaitMain("冷启动成功") { update.ui.value.result != null && !checker.isRunning }
        assertEquals(
            SHORT_LONG,
            update.ui.value.result
                ?.recheckIntervalSec,
        )

        configInterval = SHORT
        now += SHORT * MILLIS
        update.onForeground()
        assertEquals("60 秒整不查", 1, server.requestCount)
        now += MILLIS
        answer(check(min = "1.0.0"))
        update.onForeground()
        awaitRequests(2)
        awaitMain("复查结束") { !checker.isRunning }

        // 反向：成功时 60、配置改成 1800 → 61 秒不查（旧逻辑按 60 就查）
        configInterval = SHORT_LONG
        now += (SHORT + 1) * MILLIS
        update.onForeground()
        assertEquals(2, server.requestCount)
        now += SHORT_LONG * MILLIS
        answer(check(min = "1.0.0"))
        update.onForeground()
        awaitRequests(3)
        awaitMain("复查结束") { !checker.isRunning }

        // 配置读不出：回到成功结果里记下的间隔
        configInterval = null
        assertEquals(
            SHORT_LONG,
            update.ui.value.result
                ?.recheckIntervalSec,
        )
    }

    @Test
    fun fx01_intervalFromConfig() {
        fun parse(text: String) = UpdateConfigReader.recheckIntervalSec(Json.parseToJsonElement(text).jsonObject)
        assertEquals(SHORT, parse("""{"app_update":{"recheck_interval_sec":60}}"""))
        assertNull(parse("""{"app_update":{"recheck_interval_sec":0}}"""))
        assertNull(parse("""{"app_update":{"recheck_interval_sec":"60"}}"""))
        assertNull(parse("""{"app_update":{}}"""))
        assertNull(parse("""{}"""))
        assertNull(UpdateConfigReader.recheckIntervalSec(null))
    }

    // ---------------------------------------------------------------- 第 1 轮评审 R1-02

    /**
     * 结果交付前失效的检查（R1-02）：冷启动检查在途时点【重试】（检查后选店）→ 响应（强更样例）已在 IO 线程处理完、等着回主线程 → 这时撤回同意
     * （[withdraw] 为 true：allowed 变 false 且隐私世代加 1）或切换环境（false：只有环境世代加 1，allowed 仍为真）→ 主线程恢复：结果作废，
     * 不落盘、不进强更、不选店，「检查中」结束。
     */
    private fun staleDelivery(withdraw: Boolean) {
        val client = api()
        val live =
            java.util.concurrent.atomic
                .AtomicBoolean(true)
        val checker =
            ApiUpdateChecker({ client }, { live.get() }, { null })
        val stores = Stores(setOf("huawei"))
        val store = MemoryUpdateStore()
        val gate =
            UpdateGate(store, clock = { now }, appVersion = "1.0.0", platform = "android", channel = "official", installSource = null)
        val update =
            AppUpdate(
                gate = gate,
                store = store,
                checker = checker,
                stores = stores,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                canCheck = { live.get() },
                clock = { now },
            )
        hold = CountDownLatch(1)
        update.coldStart()
        awaitRequests(1)
        update.recheck(thenPickStore = true)
        shadowOf(Looper.getMainLooper()).idle()
        hold?.countDown()
        // 响应在网络线程处理完、恢复投递到主线程（主消息队列出现待执行消息）；期间主线程不空转
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (shadowOf(Looper.getMainLooper()).isIdle) {
            assertTrue("等待响应投递到主线程超时", System.nanoTime() < deadline)
            Thread.sleep(POLL_MILLIS)
        }
        if (withdraw) {
            live.set(false)
            client.inFlight.onPrivacyRevoked()
        } else {
            client.inFlight.onEnvironmentSwitching()
        }
        awaitMain("检查结束") { !checker.isRunning && !update.ui.value.working }
        assertEquals(1, server.requestCount)
        assertNull("失效结果不落盘", store.load().result)
        assertFalse("不进强更", update.ui.value.status == UpdateStatus.BELOW_MIN)
        assertTrue("不选店", stores.opened.isEmpty())
        assertFalse("检查中已结束", update.ui.value.status == UpdateStatus.CHECKING)
    }

    @Test
    fun r1_02_resultWaitingForTheMainThreadIsDroppedAfterWithdrawal() = staleDelivery(withdraw = true)

    @Test
    fun r1_02_resultWaitingForTheMainThreadIsDroppedAfterEnvironmentSwitch() = staleDelivery(withdraw = false)

    /** 对照（R1-02 不误伤）：同样的时序但没有撤回、没有切环境，结果照常落盘、进强更并选店。 */
    @Test
    fun r1_02_controlTheSameTimingWithoutInvalidationStillApplies() {
        val stores = Stores(setOf("huawei"))
        val checker = checker()
        val store = MemoryUpdateStore()
        val update =
            AppUpdate(
                gate =
                    UpdateGate(
                        store,
                        clock = { now },
                        appVersion = "1.0.0",
                        platform = "android",
                        channel = "official",
                        installSource = null,
                    ),
                store = store,
                checker = checker,
                stores = stores,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                canCheck = { allowed },
                clock = { now },
            )
        hold = CountDownLatch(1)
        update.coldStart()
        awaitRequests(1)
        update.recheck(thenPickStore = true)
        hold?.countDown()
        awaitMain("检查结束") { !checker.isRunning && !update.ui.value.working }
        assertEquals("9.0.0", store.load().result?.minSupported)
        assertEquals(UpdateStatus.BELOW_MIN, update.ui.value.status)
        assertEquals(listOf("huawei"), stores.opened)
    }

    // ---------------------------------------------------------------- 第 3 轮评审 R3-01

    private class ConsentedStore : com.couli.core.privacy.PrivacyStore {
        override var decisionRaw: String = "consented"
        override var consentedVersion: String? = "1"
        override var consentedAtMillis: Long? = 0L
        override var basicMode: Boolean = false
    }

    /** 与 AppModule 同一套接线：真实 AppPrivacy（撤回清理作废在途请求）→ canCheck / 网络层隐私头 → 真实 ApiUpdateChecker → AppUpdate。 */
    private class Wired(
        val privacy: com.couli.app.privacy.AppPrivacy,
        val checker: ApiUpdateChecker,
        val update: AppUpdate,
        val store: MemoryUpdateStore,
    )

    private fun wired(): Wired {
        lateinit var client: ApiClient
        val gate =
            com.couli.core.privacy.PrivacyGate(ConsentedStore(), clock = { now }, bundledPolicyVersion = "1").apply {
                withdrawalCleanup = { client.inFlight.onPrivacyRevoked() }
                retryScheduler =
                    com.couli.core.privacy
                        .RetryScheduler { _, _ -> }
            }
        val appPrivacy =
            com.couli.app.privacy
                .AppPrivacy(gate, environmentSettled = { true }, clock = { now })
                .also { it.coldStart() }
        val consented = { appPrivacy.conclusion == com.couli.core.router.PrivacyState.CONSENTED }
        client =
            ApiClient(
                identity = ClientIdentity(appId = "couli", platform = "android", appVersion = "1.0.0", build = "1", channel = "official"),
                baseUrl = { server.url("/").toString() },
                context = {
                    NetworkContext(
                        if (consented()) PrivacyRaw.FULL else PrivacyRaw.BASIC,
                        environmentSettled = true,
                        credentialsReadable = true,
                        UpdateRaw.UNKNOWN,
                    )
                },
                device = FixedDevice,
                tokens = MemorySessionTokens(),
                pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
            )
        val checker = ApiUpdateChecker({ client }, consented, { null })
        val store = MemoryUpdateStore()
        val update =
            AppUpdate(
                gate =
                    UpdateGate(
                        store,
                        clock = { now },
                        appVersion = "1.0.0",
                        platform = "android",
                        channel = "official",
                        installSource = null,
                    ),
                store = store,
                checker = checker,
                stores = Stores(emptySet()),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                canCheck = consented,
                clock = { now },
            )
        // 与 AppModule 相同：隐私结论变化 → onCheckAllowedChanged
        appPrivacy.onConclusionChanged { update.onCheckAllowedChanged() }
        return Wired(appPrivacy, checker, update, store)
    }

    /**
     * R3-01：冷启动检查在途 → 撤回同意 → 检查作废（不落盘、结束检查中）→ 同进程不切后台、在前台开启完整功能并重新同意 → 发出一次新检查，
     * 结果照常生效（不再停在 unknown）。
     */
    @Test
    fun r3_01_invalidatedColdStartCheckIsReissuedAfterForegroundReconsent() {
        val w = wired()
        hold = CountDownLatch(1)
        w.update.coldStart()
        awaitRequests(1)
        w.privacy.withdraw()
        // 旧响应（强更样例）随后到达也只会作废
        hold?.countDown()
        hold = null
        awaitMain("旧检查作废退出") { !w.checker.isRunning && w.update.ui.value.status != UpdateStatus.CHECKING }
        assertNull("作废结果不落盘", w.store.load().result)
        assertEquals("撤回期间不补发", 1, server.requestCount)

        w.privacy.requestFullFunction()
        w.privacy.agree()
        awaitMain("重新同意后的新检查生效") { w.store.load().result != null && !w.checker.isRunning }
        assertEquals("重新同意后发出一次新检查", 2, server.requestCount)
        assertEquals(2, w.checker.requests)
        assertEquals(UpdateStatus.BELOW_MIN, w.update.ui.value.status)
        assertEquals(com.couli.core.router.UpdateCheck.BELOW_MIN, w.update.routerUpdate)
    }

    /**
     * R3-01：冷启动检查在途 → 撤回 → 旧检查还没退出时已重新同意 → 旧检查退出（作废，不复用其结果）后补发一次新检查；旧响应即使是强更样例也不落盘，
     * 落盘的是新检查的结果。
     */
    @Test
    fun r3_01_reconsentBeforeTheOldCheckExitsReissuesAfterItExits() {
        val w = wired()
        answer(check(min = "9.0.0"))
        answer(check(min = null))
        hold = CountDownLatch(1)
        w.update.coldStart()
        awaitRequests(1)
        // 旧检查卡在服务端：撤回只改世代、清理在途登记；旧检查的协程此刻还没退出
        val latch = hold
        hold = null
        w.privacy.withdraw()
        w.privacy.requestFullFunction()
        w.privacy.agree()
        assertEquals("旧检查还没退出时不另发", 1, server.requestCount)
        latch?.countDown()
        awaitMain("补发的新检查生效") { w.store.load().result != null && !w.checker.isRunning }
        assertEquals("旧检查退出后补发一次", 2, server.requestCount)
        assertNull(
            "落盘的是新检查的结果（不复用作废的强更样例）",
            w.store
                .load()
                .result
                ?.minSupported,
        )
        assertEquals(UpdateStatus.OK, w.update.ui.value.status)
    }

    private companion object {
        const val WAIT_SECONDS = 10L
        const val POLL_MILLIS = 20L
        const val ERROR_CODE = 40001
        const val HTTP_NOT_FOUND = 404
        const val SHORT = 60
        const val SHORT_LONG = 1800
        const val MILLIS = 1000L
    }
}
