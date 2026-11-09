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
import com.couli.app.update.StoreAccess
import com.couli.core.clipboard.ClipRead
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
import com.couli.feature.login.PendingInviteCode
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
 * FU-AND-0722：走真实 AppShell / 设置行 / 隐私通知 / 生命周期入口。FU-X-CLIP-DEFAULT-ON 起用途说明弹层与功能同意相关用例已删除或改写（test-change 清单见
 * NATIVE/FU-X-CLIP-DEFAULT-ON/tests-android-handback.md §5），本文件留开关、写盘失败与撤回行为。
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
                    pendingInvite = PendingInviteCode(),
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
                        onAutoReadDisabled = { offObserved += !clipboard.autoSwitch.value },
                        onImportConsentWithdrawn = { withdrawalObserved += true },
                        workScope = scope,
                        pendingInvite = login.pendingInvite,
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
                        onAutoReadDisabled = { offObserved += !store.isAutoSwitchOn() },
                        onImportConsentWithdrawn = { withdrawalObserved += true },
                        workScope = scope,
                        pendingInvite = login.pendingInvite,
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
    fun g20_switchOffNotifiesImmediatelyAndReenableIsDirect() {
        reopen().setOn()
        launch()
        settings()
        val before = reopen().snapshot()
        click(ClipboardScreens.SETTINGS_ROW)
        val off = reopen().snapshot()
        val offNotifications = offObserved.toList()
        assertEquals(false, off.enabled)
        assertEquals(listOf(true), offNotifications)
        composeRule.onNodeWithTag(ClipboardScreens.SETTINGS_ROW).assertIsOff()
        now += 10_000L
        click(ClipboardScreens.SETTINGS_ROW)
        composeRule.onNodeWithTag(NOTICE).assertDoesNotExist()
        composeRule.onNodeWithTag(ClipboardScreens.SETTINGS_ROW).assertIsOn()
        val afterReenable = offObserved.toList()
        assertEquals(listOf(true), afterReenable)
        assertEquals(before, reopen().snapshot())
        assertNoReadRequestOrCard()
    }

    @Test
    fun g20_switchOffPublishesTheDisabledStateAndNotificationBeforeReturning() {
        reopen().setOn()
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

    private fun assertReadsOrdinaryTextOnNextForeground() {
        background()
        system.content = ClipRead.Text("今天下午三点在楼下见面")
        foreground()
        val reads = system.reads
        val requests = api.count()
        val writes = system.writes
        assertEquals("下一轮确实读取", 1, reads)
        assertEquals("普通文本不上传", 0, requests)
        assertEquals(0, writes)
        composeRule.onNodeWithTag(NOTICE).assertDoesNotExist()
    }

    /** C9：政策升级未决定期间不读；同意后开关原值不变，不算撤回、不算用户关闭（基线 r05 去掉同意版本 / 队列断言）。 */
    @Test
    fun r05_privacyPolicyUpgradeDoesNotChangeTheUserSwitch() {
        reopen().setOn()
        val original = reopen().snapshot()
        launch(privacyVersion = "2")
        assertTrue(privacy.promptShowing)
        foreground()
        assertTrue("政策升级不是撤回", withdrawalObserved.isEmpty())
        assertEquals(original, reopen().snapshot())
        assertNoReadRequestOrCard()
        composeRule.runOnUiThread { privacy.agree() }
        settle()
        assertEquals("2", privacy.consentedVersion())
        assertEquals(original, reopen().snapshot())
        assertTrue(reopen().isAutoSwitchOn())
        assertTrue(withdrawalObserved.isEmpty())
        assertTrue("政策升级不冒充用户关闭", offObserved.isEmpty())
    }

    /** C5：用户已关闭的设备经政策升级并同意后仍关闭、不读。 */
    @Test
    fun c5_privacyPolicyUpgradeKeepsUserOff() {
        reopen().setOff()
        launch(privacyVersion = "2")
        assertTrue(privacy.promptShowing)
        foreground()
        assertNoReadRequestOrCard()
        composeRule.runOnUiThread { privacy.agree() }
        settle()
        background()
        foreground()
        assertNoReadRequestOrCard()
        assertEquals(false, reopen().snapshot().enabled)
        assertFalse(clipboard.autoSwitch.value)
    }

    /** C9：运行中政策复核转未决定：不读，不算撤回，也不冒充用户关闭（基线 g29 去掉同意版本 / 队列断言）。 */
    @Test
    fun g29_runtimePolicyRecheckToUndecidedStopsReadingAndKeepsTheSwitch() {
        reopen().setOn()
        val original = reopen().snapshot()
        val privacyStore = MemoryPrivacy("consented").apply { consentedVersion = "2" }
        launch(privacyVersion = "2", privacyStore = privacyStore)
        assertEquals(PrivacyState.CONSENTED, privacy.conclusion)
        assertFalse(privacy.promptShowing)
        assertTrue(clipboard.autoSwitch.value)

        val conclusions = mutableListOf<PrivacyState>()
        composeRule.runOnUiThread {
            privacy.onConclusionChanged { conclusions += it }
            // 包内版本不可热改：从公开 PrivacyStore 端口注入旧版记录，再用生产入口复核。
            // 保留运行中的 AppPrivacy / AppClipboard 和 connect 订阅，不能重建成冷启动未决定。
            privacyStore.consentedVersion = "1"
            privacy.coldStart()
        }
        settle()
        assertEquals(listOf(PrivacyState.NOT_DECIDED), conclusions.toList())
        assertEquals(PrivacyState.NOT_DECIDED, privacy.conclusion)
        assertTrue(privacy.promptShowing)
        assertEquals(original, reopen().snapshot())
        assertTrue(clipboard.autoSwitch.value)
        assertTrue("运行中转为未决定不是撤回", withdrawalObserved.isEmpty())
        assertTrue("政策复核不应冒充用户关闭开关", offObserved.isEmpty())
        foreground()
        composeRule.onNodeWithTag(NOTICE).assertDoesNotExist()
        assertNoReadRequestOrCard()
    }

    /** C9：首启隐私未同意零读取；同意后默认开启，下一轮读取（基线 g22_firstPrivacyPrompt 去掉弹层出现的断言）。 */
    @Test
    fun g22_firstPrivacyPromptBlocksReadingThenNextForegroundReads() {
        launch(decision = "not_decided")
        foreground()
        assertTrue("正对照：首启隐私闸门确实处于弹窗中", privacy.promptShowing)
        composeRule.onNodeWithTag(NOTICE).assertDoesNotExist()
        assertNoReadRequestOrCard()
        composeRule.runOnUiThread { privacy.agree() }
        settle()
        assertReadsOrdinaryTextOnNextForeground()
    }

    /** C9：拒绝升级后的政策进入基本模式：不读、不算撤回、开关原值不变；之后同意恢复读取（基线 g33 去掉同意版本 / 队列断言）。 */
    @Test
    fun g33_rejectUpgradedPrivacyKeepsTheSwitchAndAcceptingLaterRestoresReading() {
        reopen().setOn()
        val original = reopen().snapshot()
        launch(privacyVersion = "2")
        assertEquals(PrivacyState.NOT_DECIDED, privacy.conclusion)
        click(PRIVACY_DISAGREE_TAG)
        click(PRIVACY_STILL_DISAGREE_TAG)
        assertEquals(PrivacyState.BASIC_MODE, privacy.conclusion)
        assertTrue(withdrawalObserved.isEmpty())
        assertEquals(original, reopen().snapshot())
        foreground()
        composeRule.onNodeWithTag(NOTICE).assertDoesNotExist()
        assertNoReadRequestOrCard()
        composeRule.runOnUiThread { privacy.requestFullFunction() }
        settle()
        click(PRIVACY_AGREE_TAG)
        assertEquals("2", privacy.consentedVersion())
        assertEquals(original, reopen().snapshot())
        assertTrue(reopen().isAutoSwitchOn())
        assertTrue(withdrawalObserved.isEmpty())
        assertReadsOrdinaryTextOnNextForeground()
    }

    /** C9：首启拒绝进入基本模式：不算撤回、本机开关不变、不读（基线 g33_first 原样）。 */
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
        assertTrue(withdrawalObserved.isEmpty())
        assertEquals(before, reopen().snapshot())
        foreground()
        assertNoReadRequestOrCard()
    }

    /** C7（§3 第 2 条）× G42：关闭写盘失败排队中，撤回清理 cleaner 确认落盘后，回前台补写与到期重试都必须留下「关闭」（基线 g42 clearHashes 判据随 C7 反转）。 */
    @Test
    fun g42_failedCloseSurvivesClearHashesThroughForegroundFlushAndTimers() {
        ProductionImportPreferences0722Harness(context).use { h ->
            val initial = h.preferences.importPreferences(clock = { now })
            assertTrue(initial.setOn())
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
            assertEquals("撤回清理不得丢掉用户的关闭", false, afterForeground.snapshot().enabled)
            assertFalse(afterForeground.isAutoSwitchOn())
            settleDefaultImportRetries0722(h.preferences)
            val afterTimers = h.restartFromDisk().importPreferences(clock = { now })
            assertEquals(false, afterTimers.snapshot().enabled)
            assertFalse(afterTimers.isAutoSwitchOn())
            assertNoReadRequestOrCard()
        }
    }

    /** C7 × F09：排队中的关闭重试遇到真实 cleaner：重试后磁盘仍是关闭（基线 production cleaner 判据随 C7 反转）。 */
    @Test
    fun productionCleanerKeepsQueuedCloseSoRestartStaysOff() {
        ProductionImportPreferences0722Harness(context).use { h ->
            val view = h.preferences.importPreferences(clock = { now })
            assertTrue(view.setOn())
            h.flaky.failFromNextCommit()
            assertFalse(view.setOff())
            assertTrue(h.preferences.pendingImportWriteRetries > 0)
            h.flaky.recover()
            h.preferences.clearHashes()
            settleDefaultImportRetries0722(h.preferences)
            val restarted = h.restartFromDisk().importPreferences(clock = { now })
            assertEquals(false, restarted.snapshot().enabled)
            assertFalse(restarted.isAutoSwitchOn())
        }
    }

    /** C7（变更记录 §3 第 2 条）：从未选择：撤回不读；重新同意后默认开启，回前台即读。 */
    @Test
    fun c7_withdrawThenReconsentNeverTouchedReadsAgainWithoutNotice() {
        launch()
        system.content = ClipRead.Text("今天下午三点在楼下见面")
        foreground()
        assertEquals("已同意：回前台读", 1, system.reads)
        composeRule.runOnUiThread { privacy.withdraw() }
        settle()
        assertEquals("真实撤回通知一次（正对照）", listOf(true), withdrawalObserved.toList())
        background()
        foreground()
        assertEquals("基本模式不读", 1, system.reads)
        composeRule.runOnUiThread {
            privacy.requestFullFunction()
            privacy.agree()
        }
        settle()
        background()
        foreground()
        assertEquals("重新同意后回到默认开启、回前台读", 2, system.reads)
        assertTrue(clipboard.autoSwitch.value)
        composeRule.onNodeWithTag(NOTICE).assertDoesNotExist()
        assertEquals("不写 clipboard_auto_import、不上传", 0, api.count())
    }

    /** C7（§3 第 2 条）：用户此前已关闭：撤回后重新同意仍关闭。 */
    @Test
    fun c7_withdrawKeepsUserOffThroughReconsent() {
        launch()
        settings()
        click(ClipboardScreens.SETTINGS_ROW)
        assertFalse(clipboard.autoSwitch.value)
        composeRule.runOnUiThread { nav.popBackStack() } // 回首页根页：后面的「回前台不读」才有判别力
        settle()
        composeRule.runOnUiThread { privacy.withdraw() }
        settle()
        assertFalse("撤回不清关闭态", reopen().isAutoSwitchOn())
        composeRule.runOnUiThread {
            privacy.requestFullFunction()
            privacy.agree()
        }
        settle()
        background()
        foreground()
        assertNoReadRequestOrCard()
        assertFalse("重新同意后用户的关闭仍保留", clipboard.autoSwitch.value)
    }

    @Test
    fun r07_guestChoiceSurvivesLoginLogoutAndAnotherAccountWithoutMergingReports() {
        launch()
        settings()
        click(ClipboardScreens.SETTINGS_ROW)
        val original = reopen().snapshot()
        assertEquals(false, original.enabled)
        // AppLogin 对会话变化使用的同一 routing 入口；epoch 区分第一个和第二个账号。
        for (session in listOf(SessionState.PHONE_BOUND, SessionState.GUEST, SessionState.PHONE_BOUND)) {
            composeRule.runOnUiThread {
                sessionEpoch += 1L
                routing.updateSession(session, resumePending = false)
            }
            settle()
            assertEquals(original, reopen().snapshot())
            assertFalse(clipboard.autoSwitch.value)
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
    fun r08_settingsRowRendersTheDraftDictionaryTextAndTogglesWithoutNotice() {
        launch()
        settings()
        composeRule.onNodeWithText(ClipboardDraftTexts.text("settings.clipboard_auto")).assertIsDisplayed()
        composeRule.onNodeWithText(ClipboardDraftTexts.text("settings.clipboard_auto.desc")).assertIsDisplayed()
        click(ClipboardScreens.SETTINGS_ROW)
        composeRule.onNodeWithTag(NOTICE).assertDoesNotExist()
        assertNoReadRequestOrCard()
    }

    @Test
    fun r03_failedEnableDoesNotPublishOnOrReadFromVolatilePreferences() {
        val harness = ImportWriteFailure0722Harness(clock = { now })
        try {
            assertTrue(harness.store.setOff())
            harness.backend.failFromNextWrite()
            launch(importStore = harness.store)
            settings()
            click(ClipboardScreens.SETTINGS_ROW)
            composeRule.onNodeWithTag(NOTICE).assertDoesNotExist()
            assertFalse("写盘失败：开关仍关（G35）", clipboard.autoSwitch.value)
            assertFalse(store.isAutoSwitchOn())
            composeRule.runOnUiThread { nav.popBackStack() }
            settle()
            background()
            foreground()
            assertNoReadRequestOrCard()
            // 崩溃重建只读磁盘；不能把 commit 失败后的内存副本当已落盘。
            assertEquals(false, harness.restartFromDisk().snapshot().enabled)
        } finally {
            harness.close()
        }
    }

    /** C5（Codex 评审 r1 S1-2，杀 M10）：设置行关闭后立即从磁盘重建，不回前台、不推进重试：仍关闭。 */
    @Test
    fun c5_settingsOffIsDurableBeforeAnyForegroundOrRetry() {
        ProductionImportPreferences0722Harness(context).use { h ->
            launch(productionPrefs = h.preferences)
            settings()
            click(ClipboardScreens.SETTINGS_ROW)
            composeRule.onNodeWithTag(ClipboardScreens.SETTINGS_ROW).assertIsOff()
            val restarted = h.restartFromDisk()
            assertEquals("重建后仍关闭", false, restarted.importPreferences(clock = { now }).snapshot().enabled)
            assertFalse(restarted.autoDetect)
        }
    }

    @Test
    fun productionDefaultCloseFailureNotifiesOnceAndDefaultRetryKeepsDiskRestartOff() {
        ProductionImportPreferences0722Harness(context).use { h ->
            h.preferences.importPreferences(clock = { now }).setOn()
            launch(productionPrefs = h.preferences)
            settings()
            foreground()
            h.flaky.failOnlyNextCommit()
            click(ClipboardScreens.SETTINGS_ROW)
            assertTrue("正对照：生产适配器确实遇到了 commit=false", h.flaky.failures > 0)
            val enabled = clipboard.autoSwitch.value
            val notifications = offObserved.toList()
            assertFalse(enabled)
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
            assertFalse(restarted.isAutoSwitchOn())
            val afterRetries = offObserved.toList()
            assertEquals(listOf(true), afterRetries)
        }
    }

    @Test
    fun g42_foregroundImmediatelyFlushesFailedSettingsCloseBeforeBackoff() {
        ProductionImportPreferences0722Harness(context).use { h ->
            assertTrue(h.preferences.importPreferences(clock = { now }).setOn())
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
            assertFalse(restarted.isAutoSwitchOn())
            // 先证明回前台已落盘，再结算原排队任务，不能靠原退避任务使本例假绿。
            settleDefaultImportRetries0722(h.preferences)
            assertEquals(listOf(true), offObserved.toList())
            assertNoReadRequestOrCard()
        }
    }

    @Test
    fun g42_foregroundCannotReplayFailedCloseAfterUserReenables() {
        ProductionImportPreferences0722Harness(context).use { h ->
            val initial = h.preferences.importPreferences(clock = { now })
            assertTrue(initial.setOn())
            launch(productionPrefs = h.preferences)
            settings()
            foreground()
            h.flaky.failFromNextCommit()
            click(ClipboardScreens.SETTINGS_ROW)
            assertTrue(h.flaky.failures > 0)
            composeRule.onNodeWithTag(ClipboardScreens.SETTINGS_ROW).assertIsOff()
            h.flaky.recover()
            now += 10_000L
            // 真设置行重新开启。
            click(ClipboardScreens.SETTINGS_ROW)
            composeRule.onNodeWithTag(ClipboardScreens.SETTINGS_ROW).assertIsOn()
            background()
            foregroundWithoutAdvancingBackoff()
            val afterForeground = h.restartFromDisk().importPreferences(clock = { now })
            assertEquals(true, afterForeground.snapshot().enabled)
            assertTrue(afterForeground.isAutoSwitchOn())
            // 再交付任何旧定时重试，两条补写路径都不能覆盖最新的“开”。
            settleDefaultImportRetries0722(h.preferences)
            val afterTimers = h.restartFromDisk().importPreferences(clock = { now })
            assertEquals(true, afterTimers.snapshot().enabled)
            assertTrue(afterTimers.isAutoSwitchOn())
            assertNoReadRequestOrCard() // 保持在设置页，读取禁区不因重新开启而改变。
        }
    }

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
            assertTrue(existing.setOff())
            launch(productionPrefs = h.preferences)
            settings()
            h.flaky.failFromNextCommit()
            click(ClipboardScreens.SETTINGS_ROW)
            assertTrue(h.flaky.failures > 0)
            assertFalse(clipboard.autoSwitch.value)
            assertFalse(existing.isAutoSwitchOn())
            assertFalse(h.preferences.importPreferences(clock = { now }).isAutoSwitchOn())
            assertFalse(h.preferences.autoDetect)
            h.flaky.recover()
            now += 10_000L
            settleDefaultImportRetries0722(h.preferences)
            settle()
            assertFalse(clipboard.autoSwitch.value)
            val onDisk = h.inspectDisk().importPreferences(clock = { now })
            assertEquals(false, onDisk.snapshot().enabled)
            assertNoReadRequestOrCard()
            // 用户再点一次才开启。
            click(ClipboardScreens.SETTINGS_ROW)
            assertTrue(clipboard.autoSwitch.value)
            val restarted = h.restartFromDisk().importPreferences(clock = { now })
            assertTrue(restarted.isAutoSwitchOn())
            assertNoReadRequestOrCard()
        }
    }

    @Test
    fun productionViewsSharePendingCloseSoOldRetryCannotOverwriteAnotherViewsEnable() {
        ProductionImportPreferences0722Harness(context).use { h ->
            val first = h.preferences.importPreferences(clock = { now })
            val second = h.preferences.importPreferences(clock = { now })
            assertTrue(first.setOn())
            h.flaky.failFromNextCommit()
            assertFalse(first.setOff())
            assertTrue(h.preferences.pendingImportWriteRetries > 0)
            h.flaky.recover()
            // F08：在另一生产视图里成功开启，然后才让旧视图的默认重试晚到。
            assertTrue(second.setOn())
            settleDefaultImportRetries0722(h.preferences)
            val restarted = h.restartFromDisk().importPreferences(clock = { now })
            assertEquals(true, restarted.snapshot().enabled)
            assertTrue(restarted.isAutoSwitchOn())
        }
    }

    @Test
    fun g20_productionShortcutFailedEnableStaysOffAndNextClickSucceedsWithoutNotice() {
        ProductionImportPreferences0722Harness(context).use { h ->
            val initial = h.preferences.importPreferences(clock = { now })
            assertTrue(initial.setOff())
            launch(productionPrefs = h.preferences)
            settings()
            h.flaky.failFromNextCommit()
            click(ClipboardScreens.SETTINGS_ROW)
            assertTrue(h.flaky.failures > 0)
            composeRule.onNodeWithTag(NOTICE).assertDoesNotExist()
            composeRule.onNodeWithTag(ClipboardScreens.SETTINGS_ROW).assertIsOff()
            assertFalse(clipboard.autoSwitch.value)
            assertNoReadRequestOrCard()
            h.flaky.recover()
            now += 10_000L
            settleDefaultImportRetries0722(h.preferences)
            settle()
            val onDisk = h.inspectDisk().importPreferences(clock = { now })
            assertEquals(false, onDisk.snapshot().enabled)
            assertFalse(clipboard.autoSwitch.value)
            click(ClipboardScreens.SETTINGS_ROW)
            composeRule.onNodeWithTag(NOTICE).assertDoesNotExist()
            composeRule.onNodeWithTag(ClipboardScreens.SETTINGS_ROW).assertIsOn()
            val restarted = h.restartFromDisk().importPreferences(clock = { now })
            assertEquals(true, restarted.snapshot().enabled)
            assertNoReadRequestOrCard()
        }
    }

    private companion object {
        // 已移除的用途说明弹层的标记（C2）：只用于「不存在」断言，防回归。
        const val NOTICE = "clipboard_consent_notice"

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
