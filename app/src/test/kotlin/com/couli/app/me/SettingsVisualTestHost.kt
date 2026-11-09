package com.couli.app.me

import android.content.Context
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.couli.app.account.AppAccountTexts
import com.couli.app.clipboard.AppClipboard
import com.couli.app.clipboard.ClipboardPreferences
import com.couli.app.clipboard.ClipboardSettingsRow
import com.couli.app.clipboard.SampleCipher
import com.couli.app.clipboard.connect
import com.couli.app.clipboard.regressionImportPreferences
import com.couli.app.home.HomeVisualClipboard
import com.couli.app.home.HomeVisualPrivacyStore
import com.couli.app.privacy.AppPrivacy
import com.couli.app.routing.AppRouting
import com.couli.app.routing.RoutingPrivacyHooks
import com.couli.core.config.CouliPreferenceNamespaces
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.LocalCouliAnnouncer
import com.couli.core.me.MeLevel
import com.couli.core.me.MeLocalStore
import com.couli.core.me.account.AccountShared
import com.couli.core.privacy.PrivacyGate
import com.couli.core.privacy.RetryScheduler
import com.couli.core.router.UpdateCheck
import com.couli.core.wallet.StepUpTokenCache
import com.couli.core.wallet.WalletRateGate
import com.couli.core.wallet.stepup.SmsDeadlines
import com.couli.core.wallet.stepup.StepUpDeps
import com.couli.feature.login.PendingInviteCode
import com.couli.feature.me.AccountDeps
import com.couli.feature.me.MeDeps
import com.couli.feature.me.MeGates
import com.couli.feature.me.MeTags
import com.couli.feature.me.SettingsConfig
import com.couli.feature.me.SettingsScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.robolectric.Shadows.shadowOf

/**
 * 设置一族的真实页面宿主：SettingsScreen → SettingsController / NicknameController / NotifySettingsController → MeRepository → DS；
 * 剪贴板行是真实 ClipboardSettingsRow + 真实 AppClipboard（本机偏好；用途说明弹层随 C2 删除），注销 / 换号接线是真实 AccountDeps。
 * 只替换传输层（[api]）、环境出口（[env]、[accountEnv]）与两个 Debug 待定后端（[nickname]、[notify]）。顶部 54dp 是状态栏占位。
 */
@Suppress("TooManyFunctions") // 宿主逐项给真实入口
internal class SettingsVisualTestHost(
    private val rule: ComposeContentTestRule,
) : AutoCloseable {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val service = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val env = SettingsVisualEnv()
    val api = SettingsVisualApi()
    val nickname = SettingsVisualNickname()
    val notify = SettingsVisualNotify()
    val accountEnv = SettingsVisualAccountEnv(env)
    val announcements = MeVisualAnnouncementProbe(rule)
    val spoken get() = announcements.messages
    lateinit var deps: MeDeps
    lateinit var account: AccountDeps
    lateinit var clipboard: AppClipboard
    var backs = 0
    var scrimSamples = false
    var level: String = MeLevel.MEMBER
        private set
    val palette = SettingsVisualPalette()

    @Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod")
    fun launch(
        session: String = "settings_member",
        public: Boolean = true,
        dark: Boolean = false,
        fontScale: Float = 1f,
        statusBar: Boolean = true,
        debugNotify: Boolean = true,
        nicknameEnabled: Boolean = true,
        profile: String = "settings_member",
        pendingSettingsToast: String? = null,
    ) {
        level = SettingsVisualFixtures.level(session)
        val consented = SettingsVisualFixtures.consented(session)
        env.identityFlow.value =
            when (level) {
                MeLevel.MEMBER -> SettingsVisualEnv.member()
                MeLevel.GUEST -> SettingsVisualEnv.guest()
                else -> SettingsVisualEnv.basic()
            }
        accountEnv.accountIdentityFlow.value = accountEnv.accountIdentityFlow.value.copy(me = env.identity())
        env.routes = SettingsVisualFixtures.routes(public)
        env.cache = SettingsVisualFixtures.cacheBytes(session)
        api.answers.getOrPut(SettingsVisualApi.GET_ME) { SettingsVisualFixtures.me(profile) }
        api.answers.getOrPut(SettingsVisualApi.RESET_TIP) { SettingsVisualFixtures.ok() }
        connectClipboard(consented)
        val clock = { SettingsVisualFixtures.now }
        deps =
            MeDeps(
                api,
                env,
                MeGates(service, clock = clock),
                AppMeTexts,
                MeLocalStore(context).also { it.clearAll() },
                nickname = if (nicknameEnabled) nickname else null,
                notify = if (debugNotify) notify else null,
                clock = clock,
                service = service,
            )
        val stepUp =
            StepUpDeps(
                api = api,
                env = accountEnv,
                smsGate = WalletRateGate(service, clock),
                verifyGate = WalletRateGate(service, clock),
                sms = SmsDeadlines(),
                tokens = StepUpTokenCache(),
                clock = clock,
                service = service,
                texts = AppAccountTexts,
            )
        account =
            AccountDeps(
                me = deps,
                api = SettingsVisualAccountApi(api),
                env = accountEnv,
                stepUp = stepUp,
                shared = AccountShared(service, clock) { _, _ -> true },
                texts = AppAccountTexts,
                walletRead = WalletRateGate(service, clock),
                deletionRead = WalletRateGate(service, clock),
                clock = clock,
                service = service,
            )
        pendingSettingsToast?.let { account.notices.toSettings(accountEnv.accountIdentity(), it) }
        val row = clipboard
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalCouliAnnouncer provides announcements.announcer,
            ) {
                CouliTheme(darkTheme = dark) {
                    palette.Capture()
                    Box(Modifier.fillMaxSize().background(CouliColors.backgroundCanvas)) {
                        Column(Modifier.fillMaxSize()) {
                            if (statusBar) {
                                Row(Modifier.fillMaxWidth().height(54.dp)) {
                                    if (scrimSamples) {
                                        Box(Modifier.size(16.dp).background(CouliColors.backgroundCanvas).testTag(SCRIM_CANVAS))
                                        Box(Modifier.size(16.dp).background(Color.White).testTag(SCRIM_WHITE))
                                    }
                                }
                            }
                            SettingsScreen(
                                SettingsConfig(
                                    deps = deps,
                                    entryId = "settings-visual-entry",
                                    onBack = { backs += 1 },
                                    clipboardRow = if (consented) ({ ClipboardSettingsRow(row) }) else null,
                                    account = account,
                                ),
                            )
                        }
                    }
                }
            }
        }
        idle()
        if (level == MeLevel.MEMBER && api.hold == null &&
            api.answers[SettingsVisualApi.GET_ME] is com.couli.core.network.ApiResult.Success
        ) {
            settle("昵称值与缓存大小已显示") { count(nicknameValue()) >= 1 && cacheShown() }
        } else if (level != MeLevel.BASIC) {
            settle("缓存大小已显示") { cacheShown() }
        }
    }

    private fun nicknameValue(): String =
        com.couli.core.me.MeJson
            .me(api.answers[SettingsVisualApi.GET_ME].let { (it as? com.couli.core.network.ApiResult.Success)?.data })
            ?.nickname ?: SettingsVisualFixtures.original

    private fun cacheShown(): Boolean =
        env.cache == null || count(
            com.couli.feature.me
                .cacheSize(env.cache!!),
        ) >= 1

    private fun connectClipboard(consented: Boolean) {
        context
            .getSharedPreferences(CouliPreferenceNamespaces.CLIPBOARD, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val decision = if (consented) "consented" else "basic_mode"
        val gate = PrivacyGate(HomeVisualPrivacyStore(decision), { SettingsVisualFixtures.now }, bundledPolicyVersion = "3")
        gate.retryScheduler = RetryScheduler { _, _ -> }
        val privacy = AppPrivacy(gate, environmentSettled = { true }, clock = { SettingsVisualFixtures.now }).also { it.coldStart() }
        val environment = EnvironmentGate { EnvironmentStartupReconciler.Outcome.UNCHANGED }.also { it.settleAtStartup() }
        val routing =
            AppRouting(
                context,
                environment,
                initialPrivacy = privacy.conclusion,
                initialUpdate = UpdateCheck.OK,
                privacyHooks = RoutingPrivacyHooks(promptShowing = { privacy.promptShowing }),
            )
        privacy.onConclusionChanged { routing.privacy.value = it }
        rule.runOnUiThread {
            clipboard =
                AppClipboard(
                    system = HomeVisualClipboard(),
                    api = api,
                    routing = routing,
                    privacy = { privacy.conclusion },
                    promptShowing = { privacy.promptShowing },
                    configData = { SettingsVisualFixtures.clipboardConfig },
                    prefs = ClipboardPreferences(context, SampleCipher),
                    pendingInvite = PendingInviteCode(),
                    // 从未选择（默认开启，BR-ID-16 2026-10-30）
                    importPreferences = regressionImportPreferences(context, { SettingsVisualFixtures.now }, null),
                    clock = { SettingsVisualFixtures.now },
                    randomSalt = { ByteArray(32) { it.toByte() } },
                    workScope = work,
                )
            clipboard.connect(privacy, routing, cleaners = null)
        }
    }

    // ---------------------------------------------------------------- 操作（真实触摸）

    fun node(tag: String): SemanticsNodeInteraction = rule.onNodeWithTag(tag, useUnmergedTree = true)

    fun has(tag: String): Boolean = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    fun count(text: String): Int = rule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().size

    /** 真实触摸点一次；只有可滚动祖先时才先滚到可见（不可滚的半屏、弹窗里不调 performScrollTo）。 */
    fun tap(node: SemanticsNodeInteraction) {
        if (node.insideScroll()) node.performScrollTo()
        node.performTouchInput { click(center) }
        idle()
    }

    fun tap(tag: String) = tap(node(tag))

    fun row(key: String): SemanticsNodeInteraction = node(MeTags.SETTINGS_ROW_PREFIX + key)

    fun tapRow(key: String) = tap(row(key))

    fun openNickname() {
        tapRow("settings.nickname")
        settle("修改昵称子页") { has(MeTags.NICKNAME_PAGE) }
    }

    fun openNotify() {
        tapRow("settings.notify")
        settle("通知设置子页") { has(MeTags.NOTIFY_PAGE) }
        if (deps.notify != null && notify.getAnswer == null) settle("服务类推送开关") { has(MeTags.NOTIFY_SWITCH) }
    }

    /** 昵称输入：取「带标记或在标记之下、可设置文字」的那一个节点（CouliTextField 的标记只能挂外层）。 */
    fun editable(): SemanticsNodeInteraction =
        rule.onNode(
            hasSetTextAction() and (hasTestTag(MeTags.NICKNAME_INPUT) or hasAnyAncestor(hasTestTag(MeTags.NICKNAME_INPUT))),
            useUnmergedTree = true,
        )

    fun type(value: String) {
        editable().performTextReplacement(value)
        idle()
    }

    fun idle() {
        repeat(4) {
            shadowOf(Looper.getMainLooper()).idle()
            rule.waitForIdle()
        }
    }

    /** IO 线程上的缓存大小读取 / 清除要等回到主线程；有限次轮询，超时报出等的是什么。 */
    fun settle(
        what: String,
        condition: () -> Boolean,
    ) {
        repeat(SETTLE_ROUNDS) {
            idle()
            if (condition()) return
            Thread.sleep(SETTLE_SLEEP_MS)
        }
        error("等待超时：$what")
    }

    override fun close() {
        rule.runOnUiThread {
            service.cancel()
            work.cancel()
        }
    }

    companion object {
        const val SCRIM_CANVAS = "settings_visual_scrim_canvas"
        const val SCRIM_WHITE = "settings_visual_scrim_white"
        private const val SETTLE_ROUNDS = 200
        private const val SETTLE_SLEEP_MS = 10L
    }
}

/** 只滚可滚的：祖先里有 ScrollBy 才 performScrollTo（APP-09v-e v2 的判据）。 */
internal fun SemanticsNodeInteraction.insideScroll(): Boolean =
    generateSequence(fetchSemanticsNode().parent) { it.parent }.any { it.config.contains(SemanticsActions.ScrollBy) }

/** 渲染时的令牌色（浅 / 深各取一次；页面不写色值，测试也只比令牌）。 */
internal class SettingsVisualPalette {
    var canvas = Color.Unspecified
    var surface = Color.Unspecified
    var muted = Color.Unspecified
    var placeholder = Color.Unspecified
    var primary = Color.Unspecified
    var secondary = Color.Unspecified
    var link = Color.Unspecified
    var success = Color.Unspecified
    var warningText = Color.Unspecified
    var warningBackground = Color.Unspecified
    var error = Color.Unspecified
    var subtle = Color.Unspecified
    var control = Color.Unspecified
    var scrim = Color.Unspecified
    var brand = Color.Unspecified
    var checked = Color.Unspecified

    @androidx.compose.runtime.Composable
    @androidx.compose.runtime.ReadOnlyComposable
    fun Capture() {
        canvas = CouliColors.backgroundCanvas
        surface = CouliColors.backgroundSurface
        muted = CouliColors.backgroundMuted
        placeholder = CouliColors.backgroundPlaceholder
        primary = CouliColors.textPrimary
        secondary = CouliColors.textSecondary
        link = CouliColors.textLink
        success = CouliColors.statusSuccessText
        warningText = CouliColors.statusWarningText
        warningBackground = CouliColors.statusWarningBackground
        error = CouliColors.statusErrorText
        subtle = CouliColors.borderSubtle
        control = CouliColors.borderControl
        scrim = CouliColors.overlayScrim
        brand = CouliColors.brandPrimary
        checked = CouliColors.controlChecked
    }
}
