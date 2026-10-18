package com.couli.app.clipboard

import android.app.Application
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.login.AppLogin
import com.couli.app.login.SETTINGS_PAGE_TAG
import com.couli.app.privacy.AppPrivacy
import com.couli.app.routing.AppRouting
import com.couli.app.routing.RouteDestination
import com.couli.app.routing.RoutingPrivacyHooks
import com.couli.app.shell.AppShell
import com.couli.app.shell.PRIVACY_AGREE_TAG
import com.couli.app.shell.PRIVACY_DISAGREE_TAG
import com.couli.app.shell.PRIVACY_STILL_DISAGREE_TAG
import com.couli.app.update.AppUpdate
import com.couli.app.update.FORCE_UPDATE_PAGE_TAG
import com.couli.app.update.StoreAccess
import com.couli.app.update.UPDATE_PROMPT_TAG
import com.couli.app.update.UpdateTexts
import com.couli.core.clipboard.ClipRead
import com.couli.core.clipboard.ClipboardSensitive
import com.couli.core.clipboard.ImportConsentRecord
import com.couli.core.clipboard.ImportPreferenceStore
import com.couli.core.clipboard.SystemClipboard
import com.couli.core.config.CouliPreferenceNamespaces
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiClient
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.ClientIdentity
import com.couli.core.network.MemoryPendingKeyBackend
import com.couli.core.network.MemorySessionTokens
import com.couli.core.network.NetworkContext
import com.couli.core.network.PageApi
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PrivacyRaw
import com.couli.core.network.UnregisteredDevice
import com.couli.core.network.UpdateRaw
import com.couli.core.privacy.PrivacyGate
import com.couli.core.privacy.PrivacyStore
import com.couli.core.privacy.RetryScheduler
import com.couli.core.router.PrivacyState
import com.couli.core.router.SessionState
import com.couli.core.router.UpdateCheck
import com.couli.core.update.MemoryUpdateStore
import com.couli.core.update.UpdateCheckOutcome
import com.couli.core.update.UpdateCheckResult
import com.couli.core.update.UpdateGate
import com.couli.core.update.UpdateStoreListing
import com.couli.feature.login.LegalVersions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * FU-AND-0722：走真实 AppShell / 设置行 / 隐私通知 / 生命周期入口。
 * 不复制弹层调度，不由测试调用 enable 来代替用户点击；预置同意仅用于测试升级、失效、撤回的已有用户场景。
 * 网络替身在 PageApi.send 入口同步计数；所有零次断言都在 UI 与注入 scope 的有限任务结算之后。
 * 新接口集中在本文件与 ImportPreferenceStore0722Test；签名见 tests-handback.md。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
@Suppress("TooManyFunctions", "LargeClass", "LongMethod") // 同一真实入口逐条覆盖 R01、R03–R08、G20、G22；夹具逐项接线
class ClipboardConsent0722IntegrationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val work = SupervisorJob()
    private val scope = CoroutineScope(work + Dispatchers.Main.immediate)
    private val loginScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val updateWork = SupervisorJob()
    private val updateScope = CoroutineScope(updateWork + Dispatchers.Main.immediate)
    private var update: AppUpdate? = null
    private var pendingUpdate: ControlledUpdateCheck? = null
    private var now = 1_800_000_000_123L
    private var config = config("purpose-02")
    private var sessionEpoch = 0L
    private lateinit var privacy: AppPrivacy
    private lateinit var routing: AppRouting
    private lateinit var clipboard: AppClipboard
    private lateinit var login: AppLogin
    private lateinit var nav: NavHostController
    private lateinit var store: ImportPreferenceStore
    private val system = CountingClipboard()
    private val api = RecordingApi()
    private val offObserved = mutableListOf<Boolean>()
    private val withdrawalObserved = mutableListOf<Boolean>()
    private val raw get() = context.getSharedPreferences(CouliPreferenceNamespaces.CLIPBOARD, Context.MODE_PRIVATE)

    private class CountingClipboard : SystemClipboard {
        var reads = 0
        var writes = 0
        var content: ClipRead = ClipRead.Text("https://e.tb.cn/h.6AbCdEf?tk=Example123")

        override fun read(): ClipRead {
            reads += 1
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

    private class RecordingApi : PageApi {
        private val calls = mutableListOf<ApiCall>()

        override suspend fun send(call: ApiCall): ApiResult {
            synchronized(calls) { calls += call }
            return ApiResult.Success(null, "fixture-0722", null)
        }

        fun count(): Int = synchronized(calls) { calls.size }

        override fun cancelOwned(owner: CallOwnership) = Unit

        override fun epochs(): CallEpochs = CallEpochs(0L, 0L)
    }

    private class MemoryPrivacy(
        override var decisionRaw: String,
    ) : PrivacyStore {
        override var consentedVersion: String? = "1"
        override var consentedAtMillis: Long? = 1L
        override var basicMode: Boolean = decisionRaw == "basic_mode"
    }

    /** 在真实 checker 的 await 边界暂停，结算时只允许这一项已确认暂停的工作未完成。 */
    private class ControlledUpdateCheck {
        private val reply = CompletableDeferred<UpdateCheckOutcome>()
        var awaitingReply = false
            private set

        suspend fun check(): UpdateCheckOutcome {
            awaitingReply = true
            return try {
                reply.await()
            } finally {
                awaitingReply = false
            }
        }

        fun complete(result: UpdateCheckResult) {
            kotlin.check(reply.complete(UpdateCheckOutcome.Success(result)))
        }
    }

    @Before
    fun clear() {
        assertTrue(raw.edit().clear().commit())
    }

    @After
    fun close() {
        scope.cancel()
        loginScope.cancel()
        updateScope.cancel()
    }

    private fun reopen(): ImportPreferenceStore = ClipboardPreferences(context, SampleCipher).importPreferences(clock = { now })

    @Suppress("LongParameterList") // 生产接线依赖逐项可替换，沿用 AppWalletIntegrationTest 的夹具先例。
    private fun launch(
        decision: String = "consented",
        privacyVersion: String = "1",
        updateResult: UpdateCheckResult? = null,
        privacyStore: PrivacyStore = MemoryPrivacy(decision),
        lateUpdate: ControlledUpdateCheck? = null,
        importStore: ImportPreferenceStore? = null,
        productionPrefs: ClipboardPreferences? = null,
    ) {
        composeRule.runOnUiThread {
            val gate = PrivacyGate(privacyStore, clock = { now }, bundledPolicyVersion = privacyVersion)
            gate.retryScheduler = RetryScheduler { _, _ -> }
            // 此夹具没有设备/登录凭据；剪贴板清理必须由下面的生产 connect 订阅触发。
            // clearHashes 的真实撤回清理项另由 Store0722Test 单独验证，避免在此替产品补接线。
            gate.withdrawalCleanup = {}
            privacy = AppPrivacy(gate, environmentSettled = { true }, clock = { now }).also { it.coldStart() }
            val environment = EnvironmentGate { EnvironmentStartupReconciler.Outcome.UNCHANGED }.also { it.settleAtStartup() }
            routing =
                AppRouting(
                    context,
                    environment,
                    clock = { now },
                    initialPrivacy = privacy.conclusion,
                    privacyHooks = RoutingPrivacyHooks(promptShowing = { privacy.promptShowing }),
                )
            privacy.onConclusionChanged { routing.privacy.value = it }
            // AppShell 的既有 SettingsRoute 需要 AppLogin 才会挂真实设置槽。
            // 本组不执行登录网络流程；不给 baseUrl，确保这个占位依赖绝不会联网。
            val tokens = MemorySessionTokens()
            val loginClient =
                ApiClient(
                    identity = ClientIdentity("couli", "android", "1.0.0", "1", "official"),
                    baseUrl = { null },
                    context = { NetworkContext(PrivacyRaw.FULL, true, true, UpdateRaw.SUPPORTED) },
                    device = UnregisteredDevice,
                    tokens = tokens,
                    pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { 0L },
                )
            login =
                AppLogin(
                    loginClient,
                    tokens,
                    routing,
                    updateCheck = { UpdateCheck.OK },
                    markBelow = {},
                    legal = LegalVersions(1, 1),
                    sessionScope = loginScope,
                )
            store = importStore ?: productionPrefs?.importPreferences(clock = { now }) ?: reopen()
            clipboard =
                if (productionPrefs != null) {
                    // M3：刻意不传 importPreferences，保留 AppClipboard → prefs.importPreferences 的默认装配。
                    AppClipboard(
                        system = system,
                        api = api,
                        routing = routing,
                        privacy = { privacy.conclusion },
                        promptShowing = { privacy.promptShowing },
                        configData = { config },
                        prefs = productionPrefs,
                        sessionEpoch = { sessionEpoch },
                        clock = { now },
                        randomSalt = { SAMPLE_SALT.copyOf() },
                        onAutoReadDisabled = { offObserved += !clipboard.autoReadAllowed() },
                        onImportConsentWithdrawn = { withdrawalObserved += (store.snapshot().enabled == null) },
                        workScope = scope,
                    )
                } else {
                    AppClipboard(
                        system = system,
                        api = api,
                        routing = routing,
                        privacy = { privacy.conclusion },
                        promptShowing = { privacy.promptShowing },
                        configData = { config },
                        prefs = ClipboardPreferences(context, SampleCipher),
                        sessionEpoch = { sessionEpoch },
                        clock = { now },
                        randomSalt = { SAMPLE_SALT.copyOf() },
                        importPreferences = store,
                        onAutoReadDisabled = { offObserved += !store.isAutoReadAllowed("purpose-02") },
                        onImportConsentWithdrawn = { withdrawalObserved += (store.snapshot().enabled == null) },
                        workScope = scope,
                    )
                }
            // 与 AppModule 相同的生产订阅入口；不另加测试版清理/弹层监听器。
            clipboard.connect(privacy, routing, cleaners = null)
            pendingUpdate = lateUpdate
            if (updateResult != null || lateUpdate != null) {
                val updateStore = MemoryUpdateStore()
                val updateGate =
                    UpdateGate(
                        updateStore,
                        clock = { now },
                        appVersion = "1.0.0",
                        platform = "android",
                        channel = "official",
                        installSource = "huawei",
                    )
                update =
                    AppUpdate(
                        gate = updateGate,
                        store = updateStore,
                        checker = { lateUpdate?.check() ?: UpdateCheckOutcome.Success(checkNotNull(updateResult)) },
                        stores =
                            object : StoreAccess {
                                override val installSource: String = "huawei"

                                override fun canOpen(storeKey: String): Boolean = false

                                override fun open(storeKey: String): Boolean = false
                            },
                        scope = updateScope,
                        canCheck = { true },
                        clock = { now },
                    ).also {
                        it.onRouterUpdate { state -> routing.update.value = state }
                        it.coldStart()
                    }
            }
        }
        composeRule.setContent {
            nav = rememberNavController()
            AppShell(
                routing = routing,
                privacy = privacy,
                homeContent = { Box(Modifier.fillMaxSize()) },
                navController = nav,
                login = login,
                clipboard = clipboard,
                update = update,
            )
        }
        settle()
    }

    /** UI 先交付事件，再等待本次有限工作全部完成；超时由 waitUntil 抛错，绝不吞掉。 */
    private fun settle() {
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            shadowOf(Looper.getMainLooper()).idle()
            val activeUpdateJobs = updateWork.children.count { it.isActive }
            val updateSettled = activeUpdateJobs == 0 || (activeUpdateJobs == 1 && pendingUpdate?.awaitingReply == true)
            work.children.none { it.isActive } && updateSettled
        }
        composeRule.waitForIdle()
    }

    private fun foreground(cold: Boolean = false) {
        composeRule.runOnUiThread {
            clipboard.onForeground(cold = cold, hasFocus = false)
            clipboard.onWindowFocus(true)
        }
        settle()
    }

    private fun background() {
        composeRule.runOnUiThread { clipboard.onBackground() }
        settle()
    }

    private fun settings() {
        composeRule.runOnUiThread { nav.navigate(RouteDestination("Settings", "{}", readonly = false)) }
        settle()
        composeRule.onNodeWithTag(ClipboardScreens.SETTINGS_ROW).assertIsDisplayed()
    }

    private fun click(tag: String) {
        composeRule.onNodeWithTag(tag).performClick()
        settle()
    }

    private fun assertNoReadRequestOrCard() {
        settle()
        val reads = system.reads
        val writes = system.writes
        val requests = api.count()
        val result = clipboard.result.value
        val oldPrompt = clipboard.prompt.value
        assertEquals(0, reads)
        assertEquals(0, writes)
        assertEquals("0722 不调用 parse，也不调用 consents 网络端口", 0, requests)
        assertNull(result)
        assertNull(oldPrompt)
        composeRule.onNodeWithTag(ClipboardScreens.CARD_LIST).assertDoesNotExist()
    }

    @Test
    fun r01_freshConsentedGuestDoesNotReadWhilePurposeNoticeIsUnanswered() {
        launch()
        foreground(cold = true)
        composeRule.onNodeWithTag(NOTICE).assertIsDisplayed()
        val state = reopen().snapshot()
        assertNull(state.enabled)
        assertFalse(clipboard.autoSwitch.value)
        assertNoReadRequestOrCard()
    }

    @Test
    fun r01_upgradeFromLegacyTrueStillDoesNotReadAndDeletesTheOldKey() {
        assertTrue(raw.edit().putBoolean("device_auto_detect", true).commit())
        launch()
        foreground(cold = true)
        val state = reopen().snapshot()
        val legacyExists = raw.contains("device_auto_detect")
        assertNull(state.enabled)
        assertFalse(legacyExists)
        assertFalse(clipboard.autoSwitch.value)
        assertNoReadRequestOrCard()
    }

    @Test
    fun r01_validPurposeConsentIsThePositiveControlForAutomaticReading() {
        reopen().enable("purpose-02")
        system.content = ClipRead.Text("今天下午三点在楼下见面")
        launch()
        foreground(cold = true)
        val reads = system.reads
        val requests = api.count()
        assertEquals("相同生命周期条件，有效功能同意才允许读取", 1, reads)
        assertEquals("普通文本不上传", 0, requests)
        composeRule.onNodeWithTag(NOTICE).assertDoesNotExist()
    }

    @Test
    fun r03_settingsEnableRequiresNoticeThenPersistsClickTimeAndQueuesWithoutNetwork() {
        launch()
        settings()
        composeRule.onNodeWithTag(ClipboardScreens.SETTINGS_ROW).assertIsOff()
        click(ClipboardScreens.SETTINGS_ROW)
        composeRule.onNodeWithTag(NOTICE).assertIsDisplayed()
        assertNull(reopen().snapshot().enabled)
        assertNoReadRequestOrCard()
        // 时间在打开弹层之后推进：记录的应是点击同意时刻。
        now += 12_345L
        val acceptedAt = now
        click(ENABLE)
        val rebuilt = reopen()
        val state = rebuilt.snapshot()
        val pending = rebuilt.consentQueue.pending()
        assertEquals(true, state.enabled)
        assertEquals("purpose-02", state.consentVersion)
        assertEquals(acceptedAt, state.consentedAtMillis)
        assertTrue(rebuilt.isAutoReadAllowed("purpose-02"))
        assertEquals(1, pending.size)
        assertEquals("clipboard_auto_import", pending.single().type)
        assertEquals("device", pending.single().subject)
        assertEquals("purpose-02", pending.single().version)
        assertEquals(acceptedAt, pending.single().acceptedAtMillis)
        composeRule.onNodeWithTag(ClipboardScreens.SETTINGS_ROW).assertIsOn()
        composeRule.onNodeWithTag(NOTICE).assertDoesNotExist()
        assertNoReadRequestOrCard()
        composeRule.runOnUiThread { assertTrue(nav.popBackStack()) }
        settle()
        assertTrue(clipboard.homeRoot())
        assertReadsOrdinaryTextOnNextForeground()
    }

    @Test
    fun r04_notNowDoesNotRepeatOnForegroundButSettingsCanAskAgain() {
        launch()
        foreground()
        click(NOT_NOW)
        background()
        foreground()
        val rebuilt = reopen()
        val state = rebuilt.snapshot()
        val pending = rebuilt.consentQueue.pending()
        assertNull(state.enabled)
        assertNull(state.consentVersion)
        assertTrue(rebuilt.hasSeenNotice("purpose-02"))
        assertTrue(pending.isEmpty())
        composeRule.onNodeWithTag(NOTICE).assertDoesNotExist()
        assertNoReadRequestOrCard()
        settings()
        click(ClipboardScreens.SETTINGS_ROW)
        composeRule.onNodeWithTag(NOTICE).assertIsDisplayed()
        click(NOT_NOW)
        composeRule.onNodeWithTag(ClipboardScreens.SETTINGS_ROW).assertIsOff()
        assertNull(reopen().snapshot().enabled)
        assertNoReadRequestOrCard()
    }

    @Test
    fun r05_configVersionChangeKeepsSwitchOnButBlocksReadingAndShowsNoticeAgain() {
        reopen().enable("purpose-02")
        launch()
        val before = reopen().snapshot()
        composeRule.runOnUiThread { config = config("purpose-03") }
        foreground()
        val rebuilt = reopen()
        val allowed = rebuilt.isAutoReadAllowed("purpose-03")
        val seen = rebuilt.hasSeenNotice("purpose-03")
        assertEquals(before.enabled, rebuilt.snapshot().enabled)
        assertTrue(clipboard.autoSwitch.value)
        assertFalse(allowed)
        assertFalse(seen)
        composeRule.onNodeWithTag(NOTICE).assertIsDisplayed()
        assertNoReadRequestOrCard()
    }

    @Test
    fun g47_freshMissingVersionDoesNotShowNoticeOrReadUntilVersionArrives() {
        config = Json.parseToJsonElement("{\"clipboard\":{}}") as JsonObject
        launch()
        foreground(cold = true)
        assertMissingVersionBlocksNoticeAndRead()
        val before = reopen().snapshot()
        assertNull(before.enabled)
        assertNull(before.consentVersion)
        assertNull(before.consentedAtMillis)
        assertNull(before.noticeSeenVersion)
        assertTrue(reopen().consentQueue.pending().isEmpty())
        background()
        composeRule.runOnUiThread { config = config("purpose-02") }
        foreground()
        composeRule.onNodeWithTag(NOTICE).assertIsDisplayed()
        assertNoReadRequestOrCard()
        now += 10_000L
        click(ENABLE)
        val accepted = reopen()
        assertEquals("purpose-02", accepted.snapshot().consentVersion)
        assertEquals(now, accepted.snapshot().consentedAtMillis)
        assertEquals(listOf("purpose-02"), accepted.consentQueue.pending().map { it.version })
        assertNoReadRequestOrCard() // G21：有版本后仍须等同意后的下一轮才读。
        assertReadsOrdinaryTextOnNextForeground()
    }

    @Test
    fun g47_missingVersionBlocksExistingOneConsentAndSameVersionRestoresReading() {
        assertTrue(reopen().enable("1"))
        val original = reopen().snapshot()
        val queue = reopen().consentQueue.pending()
        config = config("1")
        launch()
        assertTrue(clipboard.autoReadAllowed())
        background()
        composeRule.runOnUiThread { config = Json.parseToJsonElement("{\"clipboard\":{}}") as JsonObject }
        foreground()
        assertMissingVersionBlocksNoticeAndRead()
        assertEquals(original, reopen().snapshot())
        assertEquals(queue, reopen().consentQueue.pending())
        composeRule.runOnUiThread { config = config("1") }
        // 同版本重新下发后直接按原同意比对，不重弹、不重记同意或上报队列。
        assertReadsOrdinaryTextOnNextForeground()
        assertEquals(original, reopen().snapshot())
        assertEquals(queue, reopen().consentQueue.pending())
    }

    @Test
    fun g47_missingVersionThenDifferentVersionRequiresNewNoticeAndConsent() {
        assertTrue(reopen().enable("purpose-01"))
        val original = reopen().snapshot()
        val queue = reopen().consentQueue.pending()
        config = Json.parseToJsonElement("{\"clipboard\":{}}") as JsonObject
        launch()
        foreground()
        assertMissingVersionBlocksNoticeAndRead()
        assertEquals(original, reopen().snapshot())
        assertEquals(queue, reopen().consentQueue.pending())
        background()
        composeRule.runOnUiThread { config = config("purpose-02") }
        foreground()
        composeRule.onNodeWithTag(NOTICE).assertIsDisplayed()
        assertEquals(original, reopen().snapshot())
        assertEquals(queue, reopen().consentQueue.pending())
        assertNoReadRequestOrCard()
        now += 10_000L
        click(ENABLE)
        val accepted = reopen()
        assertEquals("purpose-02", accepted.snapshot().consentVersion)
        assertEquals(now, accepted.snapshot().consentedAtMillis)
        assertEquals(queue + ImportConsentRecord("clipboard_auto_import", "purpose-02", now), accepted.consentQueue.pending())
        assertNoReadRequestOrCard()
        assertReadsOrdinaryTextOnNextForeground()
    }

    @Test
    fun g47_settingsCanTurnOnWithoutVersionButCannotInventConsentOrQueueEntry() {
        config = Json.parseToJsonElement("{\"clipboard\":{}}") as JsonObject
        launch()
        settings()
        composeRule.onNodeWithTag(ClipboardScreens.SETTINGS_ROW).assertIsOff()
        click(ClipboardScreens.SETTINGS_ROW)
        composeRule.onNodeWithTag(ClipboardScreens.SETTINGS_ROW).assertIsOn()
        assertMissingVersionBlocksNoticeAndRead()
        val stored = reopen()
        val choice = stored.snapshot()
        assertEquals(true, choice.enabled)
        assertNull(choice.consentVersion)
        assertNull(choice.consentedAtMillis)
        assertNull(choice.noticeSeenVersion)
        assertTrue(stored.consentQueue.pending().isEmpty())
        composeRule.runOnUiThread { assertTrue(nav.popBackStack()) }
        settle()
        assertTrue(clipboard.homeRoot())
        background()
        foreground()
        assertMissingVersionBlocksNoticeAndRead()
        assertEquals(choice, reopen().snapshot())
        assertTrue(reopen().consentQueue.pending().isEmpty())
        background()
        composeRule.runOnUiThread { config = config("purpose-02") }
        foreground()
        composeRule.onNodeWithTag(NOTICE).assertIsDisplayed()
        assertEquals(choice, reopen().snapshot())
        assertTrue(reopen().consentQueue.pending().isEmpty())
        assertNoReadRequestOrCard()
    }

    private fun assertMissingVersionBlocksNoticeAndRead() {
        assertNoReadRequestOrCard() // 先结算 UI / scope，再检查弹层和读取门槛。
        composeRule.onNodeWithTag(NOTICE).assertDoesNotExist()
        val allowed = clipboard.autoReadAllowed()
        assertFalse(allowed)
    }

    @Test
    fun r05_privacyPolicyUpgradeDoesNotEraseIndependentPurposeConsent() {
        reopen().enable("purpose-02")
        val original = reopen().snapshot()
        val pending = reopen().consentQueue.pending()
        launch(privacyVersion = "2")
        assertTrue(privacy.promptShowing)
        foreground()
        val notifications = withdrawalObserved.toList()
        assertTrue("政策升级不是撤回", notifications.isEmpty())
        assertEquals(original, reopen().snapshot())
        assertNoReadRequestOrCard()
        composeRule.runOnUiThread { privacy.agree() }
        settle()
        assertEquals("2", privacy.consentedVersion())
        assertEquals(original, reopen().snapshot())
        assertEquals(pending, reopen().consentQueue.pending())
        assertTrue(reopen().isAutoReadAllowed("purpose-02"))
        val afterAgreement = withdrawalObserved.toList()
        assertTrue(afterAgreement.isEmpty())
    }

    @Test
    fun g29_runtimePolicyRecheckToUndecidedPreservesPurposeConsentAndDoesNotNotifyWithdrawal() {
        reopen().enable("purpose-02")
        val original = reopen().snapshot()
        val pending = reopen().consentQueue.pending()
        val privacyStore = MemoryPrivacy("consented").apply { consentedVersion = "2" }
        launch(privacyVersion = "2", privacyStore = privacyStore)
        assertEquals(PrivacyState.CONSENTED, privacy.conclusion)
        assertFalse(privacy.promptShowing)
        assertTrue(clipboard.autoSwitch.value)
        assertTrue(store.isAutoReadAllowed("purpose-02"))

        val conclusions = mutableListOf<PrivacyState>()
        composeRule.runOnUiThread {
            privacy.onConclusionChanged { conclusions += it }
            // 包内版本不可热改：从公开 PrivacyStore 端口注入旧版记录，再用生产入口复核。
            // 保留运行中的 AppPrivacy / AppClipboard 和 connect 订阅，不能重建成冷启动未决定。
            privacyStore.consentedVersion = "1"
            privacy.coldStart()
        }
        settle()
        val changes = conclusions.toList()
        val notifications = withdrawalObserved.toList()
        val offNotifications = offObserved.toList()
        assertEquals(listOf(PrivacyState.NOT_DECIDED), changes)
        assertEquals(PrivacyState.NOT_DECIDED, privacy.conclusion)
        assertTrue(privacy.promptShowing)
        assertEquals(original, reopen().snapshot())
        assertEquals(pending, reopen().consentQueue.pending())
        assertTrue(clipboard.autoSwitch.value)
        assertTrue(store.isAutoReadAllowed("purpose-02"))
        assertTrue("运行中转为未决定不是撤回", notifications.isEmpty())
        assertTrue("政策复核不应冒充用户关闭开关", offNotifications.isEmpty())
        foreground()
        composeRule.onNodeWithTag(NOTICE).assertDoesNotExist()
        assertNoReadRequestOrCard()
    }

    @Test
    fun g20_switchOffNotifiesImmediatelyAndValidReenableSkipsNoticeAndReporting() {
        reopen().enable("purpose-02")
        launch()
        settings()
        val before = reopen().snapshot()
        val pending = reopen().consentQueue.pending()
        click(ClipboardScreens.SETTINGS_ROW)
        val off = reopen().snapshot()
        val offNotifications = offObserved.toList()
        assertEquals(false, off.enabled)
        assertEquals(listOf(true), offNotifications)
        composeRule.onNodeWithTag(ClipboardScreens.SETTINGS_ROW).assertIsOff()
        assertEquals(pending, reopen().consentQueue.pending())
        now += 10_000L
        click(ClipboardScreens.SETTINGS_ROW)
        composeRule.onNodeWithTag(NOTICE).assertDoesNotExist()
        composeRule.onNodeWithTag(ClipboardScreens.SETTINGS_ROW).assertIsOn()
        val afterReenable = offObserved.toList()
        assertEquals(listOf(true), afterReenable)
        assertEquals(before, reopen().snapshot())
        assertEquals(pending, reopen().consentQueue.pending())
        assertNoReadRequestOrCard()
    }

    @Test
    fun g20_expiredConsentCannotUseTheReenableShortcut() {
        reopen().apply {
            enable("purpose-01")
            setOff()
        }
        launch()
        settings()
        click(ClipboardScreens.SETTINGS_ROW)
        composeRule.onNodeWithTag(NOTICE).assertIsDisplayed()
        val state = reopen().snapshot()
        assertEquals(false, state.enabled)
        assertFalse("弹层未同意前不能写入当前版本", state.consentVersion == "purpose-02")
        assertNoReadRequestOrCard()
    }

    @Test
    fun g20_switchOffPublishesTheDisabledStateAndNotificationBeforeReturning() {
        reopen().enable("purpose-02")
        launch()
        composeRule.runOnUiThread {
            clipboard.setAutoSwitch(false)
            // G20 明确要求立即生效；此处验证同步通知契约，不限定协程何时开始读取。
            val enabled = clipboard.autoSwitch.value
            val notifications = offObserved.toList()
            assertFalse(enabled)
            assertEquals(listOf(true), notifications)
        }
        foreground()
        assertNoReadRequestOrCard()
    }

    @Test
    fun r06_privacyWithdrawalViaProductionSubscriptionClearsStateAndNotifies() {
        reopen().enable("purpose-02")
        launch()
        val before = reopen().consentQueue.pending()
        assertEquals(1, before.size)
        composeRule.runOnUiThread { privacy.withdraw() }
        settle()
        val rebuilt = reopen()
        val state = rebuilt.snapshot()
        val pending = rebuilt.consentQueue.pending()
        val notifications = withdrawalObserved.toList()
        assertNull(state.enabled)
        assertNull(state.consentVersion)
        assertNull(state.consentedAtMillis)
        assertNull(state.noticeSeenVersion)
        assertNoNewImportConsents(before, pending)
        assertEquals(listOf(true), notifications)
        assertFalse(clipboard.autoSwitch.value)
        foreground()
        assertNoReadRequestOrCard()
        composeRule.runOnUiThread {
            privacy.requestFullFunction()
            privacy.agree()
        }
        settle()
        background()
        foreground()
        composeRule.onNodeWithTag(NOTICE).assertIsDisplayed()
        assertFalse(clipboard.autoSwitch.value)
        assertNull(reopen().snapshot().enabled)
        val afterPrivacyAgreement = reopen().consentQueue.pending()
        assertNoNewImportConsents(before, afterPrivacyAgreement)
        assertNoReadRequestOrCard()
    }

    @Test
    fun r07_guestChoiceSurvivesLoginLogoutAndAnotherAccountWithoutMergingReports() {
        launch()
        settings()
        click(ClipboardScreens.SETTINGS_ROW)
        click(ENABLE)
        val original = reopen().snapshot()
        val pending = reopen().consentQueue.pending()
        // AppLogin 对会话变化使用的同一 routing 入口；epoch 区分第一个和第二个账号。
        for (session in listOf(SessionState.PHONE_BOUND, SessionState.GUEST, SessionState.PHONE_BOUND)) {
            composeRule.runOnUiThread {
                sessionEpoch += 1L
                routing.updateSession(session, resumePending = false)
            }
            settle()
            assertEquals(original, reopen().snapshot())
            assertEquals(pending, reopen().consentQueue.pending())
            assertTrue(clipboard.autoSwitch.value)
        }
        assertNoReadRequestOrCard()
    }

    @Test
    fun r07_basicModeHasNoSettingsRowAndDoesNotRead() {
        launch(decision = "basic_mode")
        // 与基本模式 EntryGuard 的 OPEN_READONLY 一致，否则外壳会判定旧页不可保留并清栈。
        composeRule.runOnUiThread { nav.navigate(RouteDestination("Settings", "{}", readonly = true)) }
        settle()
        composeRule.onNodeWithTag(SETTINGS_PAGE_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(ClipboardScreens.SETTINGS_ROW).assertDoesNotExist()
        foreground()
        composeRule.onNodeWithTag(NOTICE).assertDoesNotExist()
        assertNoReadRequestOrCard()
    }

    @Test
    fun g22_firstPrivacyPromptWinsAndPurposeNoticeCanAppearAfterPrivacyIsResolved() {
        launch(decision = "not_decided")
        foreground()
        assertTrue("正对照：首启隐私闸门确实处于弹窗中", privacy.promptShowing)
        composeRule.onNodeWithTag(NOTICE).assertDoesNotExist()
        assertNoReadRequestOrCard()
        composeRule.runOnUiThread { privacy.agree() }
        settle()
        background()
        foreground()
        composeRule.onNodeWithTag(NOTICE).assertIsDisplayed()
        assertNoReadRequestOrCard()
    }

    @Test
    fun r08_settingsAndPurposeNoticeRenderTheDraftDictionaryText() {
        launch()
        settings()
        composeRule.onNodeWithText(ClipboardDraftTexts.text("settings.clipboard_auto")).assertIsDisplayed()
        composeRule.onNodeWithText(ClipboardDraftTexts.text("settings.clipboard_auto.desc")).assertIsDisplayed()
        click(ClipboardScreens.SETTINGS_ROW)
        for (key in listOf("title", "body", "enable", "not_now")) {
            composeRule.onNodeWithText(ClipboardDraftTexts.text("clipboard.consent.$key")).assertIsDisplayed()
        }
        assertNoReadRequestOrCard()
    }

    private fun assertReadsOrdinaryTextOnNextForeground() {
        background()
        system.content = ClipRead.Text("今天下午三点在楼下见面")
        foreground()
        val reads = system.reads
        val requests = api.count()
        val writes = system.writes
        assertEquals("界面开启后的下一轮确实读取", 1, reads)
        assertEquals("普通文本不上传", 0, requests)
        assertEquals(0, writes)
        composeRule.onNodeWithTag(NOTICE).assertDoesNotExist()
    }

    @Test
    fun g21_homeNoticeEnableSkipsThisRoundButReadsOnTheNextForeground() {
        launch()
        foreground(cold = true)
        composeRule.onNodeWithTag(NOTICE).assertIsDisplayed()
        click(ENABLE)
        val pending = reopen().consentQueue.pending()
        assertEquals(1, pending.size)
        assertEquals("purpose-02", pending.single().version)
        assertNoReadRequestOrCard()
        assertReadsOrdinaryTextOnNextForeground()
    }

    @Test
    fun g47_absentClipboardConfigBlocksExistingVersionOneConsent() {
        assertTrue(reopen().enable("1"))
        val original = reopen().snapshot()
        val queue = reopen().consentQueue.pending()
        config = JsonObject(emptyMap())
        system.content = ClipRead.Text("今天下午三点在楼下见面")
        launch()
        foreground(cold = true)
        assertMissingVersionBlocksNoticeAndRead()
        assertEquals(original, reopen().snapshot())
        assertEquals(queue, reopen().consentQueue.pending())
    }

    @Test
    fun r02_sensitiveRoutesNeverShowPurposeNoticeAndHomeRemainsEligible() {
        launch()
        for (route in listOf("Withdraw", "Login", "FindOrder")) {
            background()
            composeRule.runOnUiThread { clipboard.onBackStack(route, "sensitive-$route", setOf("sensitive-$route")) }
            settle()
            assertFalse("敏感页确实不在首页根页", clipboard.homeRoot())
            foreground()
            composeRule.onNodeWithTag(NOTICE).assertDoesNotExist()
            assertNoReadRequestOrCard()
        }
        // 在后台切回首页再开启新轮次；不限定敏感页离开后同一轮补不补弹。
        background()
        composeRule.runOnUiThread { clipboard.onBackStack(null, "home-control", setOf("home-control")) }
        settle()
        assertTrue(clipboard.homeRoot())
        foreground()
        composeRule.onNodeWithTag(NOTICE).assertIsDisplayed()
        assertNoReadRequestOrCard()
    }

    @Test
    fun g27_systemBackActsLikeNotNowAndDoesNotRepeatOnNextForeground() {
        launch()
        foreground(cold = true)
        composeRule.onNodeWithTag(NOTICE).assertIsDisplayed()
        composeRule.runOnUiThread {
            val dialog =
                org.robolectric.shadows.ShadowDialog
                    .getLatestDialog() as androidx.activity.ComponentDialog
            dialog.onBackPressedDispatcher.onBackPressed()
        }
        settle()
        val rebuilt = reopen()
        val state = rebuilt.snapshot()
        val pending = rebuilt.consentQueue.pending()
        assertTrue(rebuilt.hasSeenNotice("purpose-02"))
        assertNull(state.enabled)
        assertNull(state.consentVersion)
        assertTrue(pending.isEmpty())
        composeRule.onNodeWithTag(NOTICE).assertDoesNotExist()
        assertNoReadRequestOrCard()
        background()
        foreground()
        composeRule.onNodeWithTag(NOTICE).assertDoesNotExist()
        assertNoReadRequestOrCard()
    }

    @Test
    fun g22_forceUpdatePagePreventsPurposeNotice() {
        launch(updateResult = updateResult(min = "1.1.0"))
        composeRule.onNodeWithTag(FORCE_UPDATE_PAGE_TAG).assertIsDisplayed()
        assertEquals(UpdateCheck.BELOW_MIN, routing.update.value)
        foreground(cold = true)
        composeRule.onNodeWithTag(NOTICE).assertDoesNotExist()
        assertNoReadRequestOrCard()
    }

    @Test
    fun g31_optionalUpdatePromptDismissalShowsPurposeNoticeInTheSameForeground() {
        launch(updateResult = updateResult(min = "1.0.0"))
        composeRule.onNodeWithTag(UPDATE_PROMPT_TAG).assertExists()
        assertTrue(
            checkNotNull(update)
                .ui.value.state.showPrompt,
        )
        foreground(cold = true)
        composeRule.onNodeWithTag(NOTICE).assertDoesNotExist()
        assertNoReadRequestOrCard()
        composeRule.onNodeWithText(UpdateTexts.text(UpdateTexts.LATER)).performClick()
        settle()
        composeRule.onNodeWithTag(UPDATE_PROMPT_TAG).assertDoesNotExist()
        assertTrue(clipboard.homeRoot())
        // G31 修正：不退后台、不再补焦点或重查，生产更新订阅须在同轮继续用途说明。
        composeRule.onNodeWithTag(NOTICE).assertIsDisplayed()
        assertNoReadRequestOrCard()
    }

    @Test
    fun g33_rejectUpgradedPrivacyKeepsPurposeConsentAndAcceptingLaterRestoresReading() {
        reopen().enable("purpose-02")
        val original = reopen().snapshot()
        val pending = reopen().consentQueue.pending()
        launch(privacyVersion = "2")
        assertEquals(PrivacyState.NOT_DECIDED, privacy.conclusion)
        click(PRIVACY_DISAGREE_TAG)
        click(PRIVACY_STILL_DISAGREE_TAG)
        assertEquals(PrivacyState.BASIC_MODE, privacy.conclusion)
        val notifications = withdrawalObserved.toList()
        assertTrue(notifications.isEmpty())
        assertEquals(original, reopen().snapshot())
        assertEquals(pending, reopen().consentQueue.pending())
        foreground()
        composeRule.onNodeWithTag(NOTICE).assertDoesNotExist()
        assertNoReadRequestOrCard()
        composeRule.runOnUiThread { privacy.requestFullFunction() }
        settle()
        click(PRIVACY_AGREE_TAG)
        assertEquals("2", privacy.consentedVersion())
        assertEquals(original, reopen().snapshot())
        assertEquals(pending, reopen().consentQueue.pending())
        assertTrue(reopen().isAutoReadAllowed("purpose-02"))
        val afterAgreement = withdrawalObserved.toList()
        assertTrue(afterAgreement.isEmpty())
        assertReadsOrdinaryTextOnNextForeground()
    }

    @Test
    fun g33_firstPrivacyRejectionDoesNotEmitWithdrawal() {
        val fresh =
            MemoryPrivacy("not_decided").apply {
                consentedVersion = null
                consentedAtMillis = null
            }
        launch(decision = "not_decided", privacyStore = fresh)
        assertNull(privacy.consentedVersion())
        val before = reopen().snapshot()
        click(PRIVACY_DISAGREE_TAG)
        click(PRIVACY_STILL_DISAGREE_TAG)
        assertEquals(PrivacyState.BASIC_MODE, privacy.conclusion)
        val notifications = withdrawalObserved.toList()
        assertTrue(notifications.isEmpty())
        assertEquals(before, reopen().snapshot())
        foreground()
        assertNoReadRequestOrCard()
    }

    @Test
    fun g30_explicitlyOffUserIsPromptedOnNextForegroundWhenPurposeVersionChanges() {
        reopen().apply {
            enable("purpose-02")
            setOff()
        }
        launch()
        foreground()
        // 同版本明确关闭不重复问；版本变化才重新问。
        composeRule.onNodeWithTag(NOTICE).assertDoesNotExist()
        assertNoReadRequestOrCard()
        background()
        composeRule.runOnUiThread { config = config("purpose-03") }
        assertTrue(clipboard.homeRoot())
        foreground()
        composeRule.onNodeWithTag(NOTICE).assertIsDisplayed()
        assertEquals(false, reopen().snapshot().enabled)
        assertFalse(clipboard.autoSwitch.value)
        assertNoReadRequestOrCard()
    }

    @Test
    fun r03_failedEnableDoesNotPublishOnOrReadFromVolatilePreferences() {
        val harness = ImportWriteFailure0722Harness(clock = { now })
        try {
            harness.backend.failFromNextWrite()
            launch(importStore = harness.store)
            foreground()
            composeRule.onNodeWithTag(NOTICE).assertIsDisplayed()
            click(ENABLE)
            val enabled = clipboard.autoSwitch.value
            val allowed = store.isAutoReadAllowed("purpose-02")
            assertFalse(enabled)
            assertFalse(allowed)
            background()
            foreground()
            assertNoReadRequestOrCard()
            // 崩溃重建只读磁盘；不能把 commit 失败后的内存副本当已落盘。
            val restarted = harness.restartFromDisk()
            assertFalse(restarted.isAutoReadAllowed("purpose-02"))
            assertNull(restarted.snapshot().consentVersion)
            val pending = restarted.consentQueue.pending()
            assertTrue(pending.isEmpty())
        } finally {
            harness.close()
        }
    }

    @Test
    fun g22_lateForceUpdateRetractsForegroundPurposeNotice() = lateForceUpdateRetractsNotice(fromSettings = false)

    @Test
    fun g22_lateForceUpdateRetractsSettingsPurposeNotice() = lateForceUpdateRetractsNotice(fromSettings = true)

    private fun lateForceUpdateRetractsNotice(fromSettings: Boolean) {
        val check = ControlledUpdateCheck()
        launch(lateUpdate = check)
        assertTrue(check.awaitingReply)
        if (fromSettings) showPurposeNotice(fromSettings = true) else foreground()
        // 首页允许等更新检查结果再弹；若已经显示，交付结果后的消失断言仍会检查让位。
        assertNoReadRequestOrCard()
        composeRule.runOnUiThread { check.complete(updateResult(min = "1.1.0")) }
        settle()
        assertFalse(check.awaitingReply)
        assertEquals(UpdateCheck.BELOW_MIN, routing.update.value)
        composeRule.onNodeWithTag(FORCE_UPDATE_PAGE_TAG).assertIsDisplayed()
        assertNoticeYieldedWithoutChoosing()
    }

    @Test
    fun g22_lateOptionalUpdateRetractsForegroundNoticeAndResumesInSameRound() = lateOptionalUpdateRetractsNotice(fromSettings = false)

    @Test
    fun g22_lateOptionalUpdateRetractsSettingsPurposeNotice() = lateOptionalUpdateRetractsNotice(fromSettings = true)

    private fun lateOptionalUpdateRetractsNotice(fromSettings: Boolean) {
        val check = ControlledUpdateCheck()
        launch(lateUpdate = check)
        assertTrue(check.awaitingReply)
        if (fromSettings) showPurposeNotice(fromSettings = true) else foreground()
        // 首页允许等更新检查结果再弹；若已经显示，交付结果后的消失断言仍会检查让位。
        assertNoReadRequestOrCard()
        composeRule.runOnUiThread { check.complete(updateResult(min = "1.0.0")) }
        settle()
        assertFalse(check.awaitingReply)
        composeRule.onNodeWithTag(UPDATE_PROMPT_TAG).assertIsDisplayed()
        assertNoticeYieldedWithoutChoosing()
        composeRule.onNodeWithText(UpdateTexts.text(UpdateTexts.LATER)).performClick()
        settle()
        composeRule.onNodeWithTag(UPDATE_PROMPT_TAG).assertDoesNotExist()
        if (!fromSettings) {
            assertTrue(clipboard.homeRoot())
            // 已显示的用途说明被暂时遮让后，同轮恢复；不是新的自动读取轮次。
            composeRule.onNodeWithTag(NOTICE).assertIsDisplayed()
            click(ENABLE)
            assertEquals(1, reopen().consentQueue.pending().size)
            assertNoReadRequestOrCard()
            assertReadsOrdinaryTextOnNextForeground()
        } else {
            // G41：设置来源被更新提示收起后，不自动恢复，必须由用户再点。
            composeRule.onNodeWithTag(NOTICE).assertDoesNotExist()
            assertNoReadRequestOrCard()
        }
    }

    @Test
    fun g22_sensitiveOverlayRetractsForegroundPurposeNotice() = sensitiveOverlayRetractsNotice(fromSettings = false)

    @Test
    fun g22_sensitiveOverlayRetractsSettingsPurposeNotice() = sensitiveOverlayRetractsNotice(fromSettings = true)

    private fun sensitiveOverlayRetractsNotice(fromSettings: Boolean) {
        launch()
        showPurposeNotice(fromSettings)
        composeRule.runOnUiThread {
            clipboard.overlay = { ClipboardSensitive.OVERLAY_AUTH_SHEET }
            clipboard.onSensitiveOverlay()
        }
        settle()
        assertNoticeYieldedWithoutChoosing()
        composeRule.runOnUiThread {
            clipboard.overlay = { null }
            clipboard.onDialogsChanged()
        }
        settle()
        assertSameRoundNoticeRecovery(fromSettings)
    }

    @Test
    fun g22_withdrawPageRetractsForegroundPurposeNoticeWithoutMarkingSeen() = sensitivePageRetractsNotice(fromSettings = false)

    @Test
    fun g22_withdrawPageRetractsSettingsPurposeNoticeWithoutMarkingSeen() = sensitivePageRetractsNotice(fromSettings = true)

    private fun sensitivePageRetractsNotice(fromSettings: Boolean) {
        launch()
        showPurposeNotice(fromSettings)
        composeRule.runOnUiThread { clipboard.onBackStack("Withdraw", "sensitive-w", setOf("sensitive-w")) }
        settle()
        assertFalse(clipboard.homeRoot())
        assertNoticeYieldedWithoutChoosing()
        // G41：不退后台，不再补焦点；回首页根页的生产栈通知就是本轮解除阻挡。
        composeRule.runOnUiThread { clipboard.onBackStack(null, "home-return", setOf("home-return")) }
        settle()
        assertTrue(clipboard.homeRoot())
        assertSameRoundNoticeRecovery(fromSettings)
    }

    private fun assertSameRoundNoticeRecovery(fromSettings: Boolean) {
        if (fromSettings) {
            composeRule.onNodeWithTag(NOTICE).assertDoesNotExist()
        } else {
            composeRule.onNodeWithTag(NOTICE).assertIsDisplayed()
        }
        val seen = store.hasSeenNotice("purpose-02")
        val pending = store.consentQueue.pending()
        assertFalse(seen)
        assertTrue(pending.isEmpty())
        assertNoReadRequestOrCard()
    }

    @Test
    fun productionDefaultCloseFailureNotifiesOnceAndDefaultRetryKeepsDiskRestartOff() {
        ProductionImportPreferences0722Harness(context).use { h ->
            h.preferences.importPreferences(clock = { now }).enable("purpose-02")
            launch(productionPrefs = h.preferences)
            settings()
            foreground()
            h.flaky.failOnlyNextCommit()
            click(ClipboardScreens.SETTINGS_ROW)
            assertTrue("正对照：生产适配器确实遇到了 commit=false", h.flaky.failures > 0)
            val enabled = clipboard.autoSwitch.value
            val allowed = clipboard.autoReadAllowed()
            val notifications = offObserved.toList()
            assertFalse(enabled)
            assertFalse(allowed)
            assertEquals(listOf(true), notifications)
            composeRule.runOnUiThread { nav.popBackStack() }
            settle()
            background()
            foreground()
            assertNoReadRequestOrCard()
            // 推进默认生产调度，既不注入 store，也不注入 scheduleWriteRetry，更不补调 setOff。
            settleDefaultImportRetries0722(h.preferences)
            settle()
            val committed = ClipboardPreferences(h.flaky.durable, SampleCipher).importPreferences(clock = { now })
            assertEquals(false, committed.snapshot().enabled)
            val restarted = h.restartFromDisk().importPreferences(clock = { now })
            assertEquals(false, restarted.snapshot().enabled)
            assertFalse(restarted.isAutoReadAllowed("purpose-02"))
            val afterRetries = offObserved.toList()
            assertEquals(listOf(true), afterRetries)
        }
    }

    @Test
    fun g42_foregroundImmediatelyFlushesFailedSettingsCloseBeforeBackoff() {
        ProductionImportPreferences0722Harness(context).use { h ->
            assertTrue(h.preferences.importPreferences(clock = { now }).enable("purpose-02"))
            launch(productionPrefs = h.preferences)
            settings()
            foreground()
            h.flaky.failFromNextCommit()
            click(ClipboardScreens.SETTINGS_ROW)
            background()
            assertTrue(h.flaky.failures > 0)
            val diskBeforeRecovery = h.inspectDisk().importPreferences(clock = { now })
            assertEquals(true, diskBeforeRecovery.snapshot().enabled)
            shadowOf(Looper.getMainLooper()).idle() // 仍失败时先结算当前已到期的任务。
            h.flaky.recover()
            foregroundWithoutAdvancingBackoff()
            val restarted = h.restartFromDisk().importPreferences(clock = { now })
            assertEquals(false, restarted.snapshot().enabled)
            assertFalse(restarted.isAutoReadAllowed("purpose-02"))
            // 先证明回前台已落盘，再结算原排队任务，不能靠原退避任务使本例假绿。
            settleDefaultImportRetries0722(h.preferences)
            assertEquals(listOf(true), offObserved.toList())
            assertNoReadRequestOrCard()
        }
    }

    @Test
    fun g42_foregroundImmediatelyFlushesFailedEnqueueBeforeBackoff() {
        ProductionImportPreferences0722Harness(context).use { h ->
            val initial = h.preferences.importPreferences(clock = { now })
            assertTrue(initial.enable("purpose-02"))
            assertTrue(initial.setOff())
            val committed = initial.consentQueue.pending()
            launch(productionPrefs = h.preferences)
            foreground()
            background()
            val extra = ImportConsentRecord("clipboard_auto_import", "purpose-01", now - 10_000L)
            h.flaky.failFromNextCommit()
            assertFalse(store.consentQueue.enqueue(extra))
            val diskBeforeRecovery = h.inspectDisk().importPreferences(clock = { now })
            assertEquals(committed, diskBeforeRecovery.consentQueue.pending())
            shadowOf(Looper.getMainLooper()).idle()
            h.flaky.recover()
            foregroundWithoutAdvancingBackoff()
            val restarted = h.restartFromDisk().importPreferences(clock = { now })
            assertEquals(committed + extra, restarted.consentQueue.pending())
            assertEquals(false, restarted.snapshot().enabled)
            settleDefaultImportRetries0722(h.preferences)
            assertNoReadRequestOrCard()
        }
    }

    @Test
    fun g42_foregroundCannotReplayFailedCloseAfterUserReenables() {
        ProductionImportPreferences0722Harness(context).use { h ->
            val initial = h.preferences.importPreferences(clock = { now })
            assertTrue(initial.enable("purpose-02"))
            val original = initial.snapshot()
            val queue = initial.consentQueue.pending()
            launch(productionPrefs = h.preferences)
            settings()
            foreground()
            h.flaky.failFromNextCommit()
            click(ClipboardScreens.SETTINGS_ROW)
            assertTrue(h.flaky.failures > 0)
            composeRule.onNodeWithTag(ClipboardScreens.SETTINGS_ROW).assertIsOff()
            h.flaky.recover()
            now += 10_000L
            // G20：真设置行重新开启，仍沿用原同意时刻和队列。
            click(ClipboardScreens.SETTINGS_ROW)
            composeRule.onNodeWithTag(ClipboardScreens.SETTINGS_ROW).assertIsOn()
            background()
            foregroundWithoutAdvancingBackoff()
            val afterForeground = h.restartFromDisk().importPreferences(clock = { now })
            assertEquals(original, afterForeground.snapshot())
            assertEquals(queue, afterForeground.consentQueue.pending())
            assertTrue(afterForeground.isAutoReadAllowed("purpose-02"))
            // 再交付任何旧定时重试，两条补写路径都不能覆盖最新的“开”。
            settleDefaultImportRetries0722(h.preferences)
            val afterTimers = h.restartFromDisk().importPreferences(clock = { now })
            assertEquals(original, afterTimers.snapshot())
            assertEquals(queue, afterTimers.consentQueue.pending())
            assertTrue(afterTimers.isAutoReadAllowed("purpose-02"))
            assertNoReadRequestOrCard() // 保持在设置页，读取禁区不因功能重新开启而改变。
        }
    }

    @Test
    fun g42_foregroundCannotReplayFailedCloseAfterClearHashes() {
        ProductionImportPreferences0722Harness(context).use { h ->
            val initial = h.preferences.importPreferences(clock = { now })
            assertTrue(initial.enable("purpose-02"))
            val queue = initial.consentQueue.pending()
            launch(productionPrefs = h.preferences)
            settings()
            foreground()
            h.flaky.failFromNextCommit()
            click(ClipboardScreens.SETTINGS_ROW)
            assertTrue(h.flaky.failures > 0)
            h.flaky.recover()
            // 使用真实 cleaner；不在测试里删内部键或补调 store 的清理入口。
            composeRule.runOnUiThread { h.preferences.clearHashes() }
            background()
            foregroundWithoutAdvancingBackoff()
            val afterForeground = h.restartFromDisk().importPreferences(clock = { now })
            val cleared = afterForeground.snapshot()
            assertNull(cleared.enabled)
            assertNull(cleared.consentVersion)
            assertNull(cleared.consentedAtMillis)
            assertNull(cleared.noticeSeenVersion)
            assertNull(cleared.deniedAtMillis)
            assertFalse(afterForeground.isAutoReadAllowed("purpose-02"))
            assertEquals(queue, afterForeground.consentQueue.pending()) // G26：历史同意记录保留。
            settleDefaultImportRetries0722(h.preferences)
            val afterTimers = h.restartFromDisk().importPreferences(clock = { now })
            assertEquals(cleared, afterTimers.snapshot())
            assertEquals(queue, afterTimers.consentQueue.pending())
            assertFalse(afterTimers.isAutoReadAllowed("purpose-02"))
            assertNoReadRequestOrCard()
        }
    }

    /** 交付生产前台事件及当前时刻已就绪的任务，允许异步投递；不推进退避计时器。 */
    private fun foregroundWithoutAdvancingBackoff() {
        val autoAdvance = composeRule.mainClock.autoAdvance
        composeRule.mainClock.autoAdvance = false
        try {
            val before = SystemClock.uptimeMillis()
            composeRule.runOnUiThread {
                clipboard.onForeground(cold = false, hasFocus = true)
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("不能等到原退避定时器到期才判成功", before, SystemClock.uptimeMillis())
        } finally {
            composeRule.mainClock.autoAdvance = autoAdvance
        }
    }

    @Test
    fun productionDefaultFailedEnableStaysOffAcrossViewsAndSecondClickCanSucceed() {
        ProductionImportPreferences0722Harness(context).use { h ->
            // 在失败前先创建旧视图，失败后再创建新视图，两者都必须拒绝未提交的开启。
            val existing = h.preferences.importPreferences(clock = { now })
            launch(productionPrefs = h.preferences)
            settings()
            click(ClipboardScreens.SETTINGS_ROW)
            h.flaky.failFromNextCommit()
            click(ENABLE)
            assertTrue(h.flaky.failures > 0)
            val enabled = clipboard.autoSwitch.value
            val allowed = clipboard.autoReadAllowed()
            val existingAllowed = existing.isAutoReadAllowed("purpose-02")
            val freshAllowed = h.preferences.importPreferences(clock = { now }).isAutoReadAllowed("purpose-02")
            val compatibilityOn = h.preferences.autoDetect
            assertFalse(enabled)
            assertFalse(allowed)
            assertFalse(existingAllowed)
            assertFalse(freshAllowed)
            assertFalse(compatibilityOn)
            h.flaky.recover()
            now += 10_000L
            settleDefaultImportRetries0722(h.preferences)
            settle()
            assertFalse(clipboard.autoReadAllowed())
            // 无关成功提交不可把失败的授权和队列夹带落盘。
            existing.markNoticeSeen("purpose-02")
            val diskAfterRollback = h.inspectDisk().importPreferences(clock = { now })
            assertNull(diskAfterRollback.snapshot().consentVersion)
            val failedQueue = diskAfterRollback.consentQueue.pending()
            assertTrue(failedQueue.isEmpty())
            assertNoReadRequestOrCard()
            // 接受失败弹层仍在，或已关闭后可重新打开；都必须由用户再点一次。
            if (composeRule.onAllNodesWithTag(ENABLE).fetchSemanticsNodes().isEmpty()) click(ClipboardScreens.SETTINGS_ROW)
            click(ENABLE)
            assertTrue(clipboard.autoSwitch.value)
            val restarted = h.restartFromDisk().importPreferences(clock = { now })
            assertTrue(restarted.isAutoReadAllowed("purpose-02"))
            assertEquals(now, restarted.snapshot().consentedAtMillis)
            assertEquals(1, restarted.consentQueue.pending().size)
            assertEquals(
                now,
                restarted.consentQueue
                    .pending()
                    .single()
                    .acceptedAtMillis,
            )
            assertNoReadRequestOrCard()
        }
    }

    @Test
    fun productionViewsSharePendingCloseSoOldRetryCannotOverwriteAnotherViewsEnable() {
        ProductionImportPreferences0722Harness(context).use { h ->
            val first = h.preferences.importPreferences(clock = { now })
            val second = h.preferences.importPreferences(clock = { now })
            assertTrue(first.enable("purpose-02"))
            val original = first.snapshot()
            val queue = first.consentQueue.pending()
            h.flaky.failFromNextCommit()
            assertFalse(first.setOff())
            assertTrue(h.preferences.pendingImportWriteRetries > 0)
            h.flaky.recover()
            // F08：在另一生产视图里成功开启，然后才让旧视图的默认重试晚到。
            assertTrue(second.enable("purpose-02"))
            settleDefaultImportRetries0722(h.preferences)
            val restarted = h.restartFromDisk().importPreferences(clock = { now })
            assertEquals(original, restarted.snapshot())
            assertTrue(restarted.isAutoReadAllowed("purpose-02"))
            assertEquals(queue, restarted.consentQueue.pending())
        }
    }

    @Test
    fun productionCleanerInvalidatesQueuedCloseSoRetryCannotRecreateWithdrawnChoice() {
        ProductionImportPreferences0722Harness(context).use { h ->
            val view = h.preferences.importPreferences(clock = { now })
            assertTrue(view.enable("purpose-02"))
            val queue = view.consentQueue.pending()
            h.flaky.failFromNextCommit()
            assertFalse(view.setOff())
            assertTrue(h.preferences.pendingImportWriteRetries > 0)
            h.flaky.recover()
            // F09：真实 cleaner 先确认清盘，排队中的关闭意图随之作废。
            h.preferences.clearHashes()
            settleDefaultImportRetries0722(h.preferences)
            val restarted = h.restartFromDisk().importPreferences(clock = { now })
            val state = restarted.snapshot()
            assertNull(state.enabled)
            assertNull(state.consentVersion)
            assertNull(state.consentedAtMillis)
            assertNull(state.noticeSeenVersion)
            assertFalse(restarted.isAutoReadAllowed("purpose-02"))
            assertEquals(queue, restarted.consentQueue.pending())
        }
    }

    @Test
    fun g20_productionShortcutFailedEnableStaysOffAndNextClickUsesExistingConsent() {
        ProductionImportPreferences0722Harness(context).use { h ->
            val initial = h.preferences.importPreferences(clock = { now })
            assertTrue(initial.enable("purpose-02"))
            assertTrue(initial.setOff())
            val originalOff = initial.snapshot()
            val queue = initial.consentQueue.pending()
            launch(productionPrefs = h.preferences)
            settings()
            h.flaky.failFromNextCommit()
            click(ClipboardScreens.SETTINGS_ROW)
            assertTrue(h.flaky.failures > 0)
            composeRule.onNodeWithTag(NOTICE).assertDoesNotExist()
            composeRule.onNodeWithTag(ClipboardScreens.SETTINGS_ROW).assertIsOff()
            val enabled = clipboard.autoSwitch.value
            val allowed = clipboard.autoReadAllowed()
            assertFalse(enabled)
            assertFalse(allowed)
            assertNoReadRequestOrCard()
            h.flaky.recover()
            now += 10_000L
            settleDefaultImportRetries0722(h.preferences)
            settle()
            assertEquals(originalOff, h.inspectDisk().importPreferences(clock = { now }).snapshot())
            assertFalse(clipboard.autoSwitch.value)
            click(ClipboardScreens.SETTINGS_ROW)
            composeRule.onNodeWithTag(NOTICE).assertDoesNotExist()
            composeRule.onNodeWithTag(ClipboardScreens.SETTINGS_ROW).assertIsOn()
            val restarted = h.restartFromDisk().importPreferences(clock = { now })
            assertEquals(originalOff.copy(enabled = true), restarted.snapshot())
            assertEquals(queue, restarted.consentQueue.pending())
            assertNoReadRequestOrCard()
        }
    }

    private fun showPurposeNotice(fromSettings: Boolean) {
        if (fromSettings) settings()
        foreground()
        if (fromSettings) click(ClipboardScreens.SETTINGS_ROW)
        composeRule.onNodeWithTag(NOTICE).assertIsDisplayed()
    }

    private fun assertNoticeYieldedWithoutChoosing() {
        settle()
        composeRule.onNodeWithTag(NOTICE).assertDoesNotExist()
        val seen = store.hasSeenNotice("purpose-02")
        val pending = store.consentQueue.pending()
        assertFalse("排序让位不是暂不开启，不能记 seen", seen)
        assertNull(store.snapshot().enabled)
        assertTrue(pending.isEmpty())
        assertNoReadRequestOrCard()
    }

    private companion object {
        // 与已有结果 Toast 的 clipboard_notice 区分；具体常量归属不作要求。
        const val NOTICE = "clipboard_consent_notice"
        const val ENABLE = "clipboard_consent_enable"
        const val NOT_NOW = "clipboard_consent_not_now"

        fun updateResult(min: String): UpdateCheckResult =
            UpdateCheckResult(
                minSupported = min,
                recommended = "1.1.0",
                latest = "1.2.0",
                defaultStore = "huawei",
                stores = listOf(UpdateStoreListing("huawei", "1.2.0")),
            )

        fun config(version: String): JsonObject =
            Json.parseToJsonElement("{\"clipboard\":{\"consent_version\":\"$version\"}}") as JsonObject
    }
}
