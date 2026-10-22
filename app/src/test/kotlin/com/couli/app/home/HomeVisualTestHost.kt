package com.couli.app.home

import android.content.Context
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.couli.app.agent.AppAgent
import com.couli.app.clipboard.AppClipboard
import com.couli.app.clipboard.ClipboardPreferences
import com.couli.app.clipboard.SampleCipher
import com.couli.app.clipboard.connect
import com.couli.app.clipboard.regressionImportPreferences
import com.couli.app.privacy.AppPrivacy
import com.couli.app.routing.AppRouting
import com.couli.app.routing.RoutingPrivacyHooks
import com.couli.app.shell.AppShell
import com.couli.core.config.CouliPreferenceNamespaces
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.LocalProductImageLoader
import com.couli.core.designsystem.ProductImageLoader
import com.couli.core.home.HomeLevel
import com.couli.core.network.ApiClient
import com.couli.core.network.ClientIdentity
import com.couli.core.network.MemoryPendingKeyBackend
import com.couli.core.network.MemorySessionTokens
import com.couli.core.network.NetworkContext
import com.couli.core.network.PendingKeyStore
import com.couli.core.network.PrivacyRaw
import com.couli.core.network.UnregisteredDevice
import com.couli.core.network.UpdateRaw
import com.couli.core.privacy.PrivacyGate
import com.couli.core.privacy.RetryScheduler
import com.couli.core.router.PrivacyState
import com.couli.core.router.SessionState
import com.couli.core.router.UpdateCheck
import com.couli.feature.home.HomeTimers
import com.couli.feature.login.PendingInviteCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.robolectric.Shadows.shadowOf

/** 真正的 AppShell → HomeRoute → HomeSduiScreen，不在测试里重画一套首页。 */
internal class HomeVisualTestHost(
    private val rule: ComposeContentTestRule,
) : AutoCloseable {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val port = HomeVisualPort(HomeVisualFixtures.page("main_v3"))
    val api = HomeVisualApi()
    val system = HomeVisualClipboard()
    val tokens = MemorySessionTokens()
    var config = HomeVisualFixtures.config
    var images: ProductImageLoader = HomeVisualFixtures.images
    var overlay: @Composable () -> Unit = {}
    var surface = Color.Unspecified
        private set
    var border = Color.Unspecified
        private set
    var scrim = Color.Unspecified
        private set
    var aiBackground = Color.Unspecified
        private set
    private lateinit var agent: AppAgent
    lateinit var routing: AppRouting
        private set
    lateinit var home: AppHome
        private set
    lateinit var clipboard: AppClipboard
        private set
    private lateinit var privacy: AppPrivacy

    fun launch(
        level: String = HomeLevel.MEMBER,
        dark: Boolean = false,
        fontScale: Float = 1f,
        statusBar: Boolean = false,
        autoRead: Boolean? = false,
    ) {
        context
            .getSharedPreferences(CouliPreferenceNamespaces.CLIPBOARD, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val decision = if (level == HomeLevel.BASIC) "basic_mode" else "consented"
        val gate = PrivacyGate(HomeVisualPrivacyStore(decision), { HomeVisualFixtures.NOW }, bundledPolicyVersion = "3")
        gate.retryScheduler = RetryScheduler { _, _ -> }
        gate.withdrawalCleanup = { tokens.clear() }
        privacy = AppPrivacy(gate, environmentSettled = { true }, clock = { HomeVisualFixtures.NOW }).also { it.coldStart() }
        val environment = EnvironmentGate { EnvironmentStartupReconciler.Outcome.UNCHANGED }.also { it.settleAtStartup() }
        routing =
            AppRouting(
                context,
                environment,
                initialPrivacy = privacy.conclusion,
                initialUpdate = UpdateCheck.OK,
                privacyHooks = RoutingPrivacyHooks(promptShowing = { privacy.promptShowing }),
            )
        privacy.onConclusionChanged { routing.privacy.value = it }
        if (level == HomeLevel.MEMBER) tokens.save("visual-access", "visual-refresh", "full", "visual-user")
        rule.runOnUiThread {
            routing.updateSession(if (level == HomeLevel.MEMBER) SessionState.PHONE_BOUND else SessionState.GUEST, resumePending = false)
            connectHome()
            connectClipboard(autoRead)
        }
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalProductImageLoader provides images,
            ) {
                CouliTheme(darkTheme = dark) {
                    aiBackground = CouliColors.brandSubtle
                    surface = CouliColors.backgroundSurface
                    border = CouliColors.borderControl
                    scrim = CouliColors.overlayScrim
                    Column(Modifier.fillMaxSize().background(CouliColors.backgroundCanvas)) {
                        if (statusBar) Spacer(Modifier.height(54.dp))
                        AppShell(
                            routing = routing,
                            privacy = privacy,
                            homeContent = { HomeRoute(home) },
                            navController = rememberNavController(),
                            home = home,
                            clipboard = clipboard,
                            agent = agent,
                        )
                        overlay()
                    }
                }
            }
        }
        idle()
    }

    private fun connectHome() {
        val client =
            ApiClient(
                identity = ClientIdentity("couli", "android", "1.10.0", "1", "official"),
                baseUrl = { null },
                context = {
                    NetworkContext(
                        if (privacy.conclusion == PrivacyState.BASIC_MODE) PrivacyRaw.BASIC else PrivacyRaw.FULL,
                        environmentSettled = true,
                        credentialsReadable = true,
                        update = UpdateRaw.SUPPORTED,
                    )
                },
                device = UnregisteredDevice,
                tokens = tokens,
                pendingKeys = PendingKeyStore(MemoryPendingKeyBackend()) { HomeVisualFixtures.NOW },
            )
        agent =
            AppAgent(
                client = client,
                tokens = tokens,
                routing = routing,
                privacy = privacy,
                port = { null },
                envKey = { "test" },
                configData = { config },
                mask = MutableStateFlow(false),
                track = {},
                serverForced = {},
                trade = null,
            )
        home =
            AppHome(
                client = client,
                tokens = tokens,
                routing = routing,
                privacy = privacy,
                port = port,
                lkg = null,
                prefs = null,
                images = images,
                envKey = { "test" },
                configData = { config },
                track = {},
                serverForced = {},
                cardTexts = HomeVisualFixtures.cardTexts,
                appVersion = "1.10.0",
                clock = { HomeVisualFixtures.NOW },
                timers = HomeTimers { _, _ -> {} },
                bundled = { null },
                api = api,
                io = Dispatchers.Main.immediate,
            ).also { it.connect() }
    }

    private fun connectClipboard(autoRead: Boolean?) {
        clipboard =
            AppClipboard(
                system = system,
                api = api,
                routing = routing,
                privacy = { privacy.conclusion },
                promptShowing = { privacy.promptShowing },
                configData = { config },
                prefs = ClipboardPreferences(context, SampleCipher),
                pendingInvite = PendingInviteCode(),
                importPreferences = regressionImportPreferences(context, { HomeVisualFixtures.NOW }, autoRead),
                clock = { HomeVisualFixtures.NOW },
                randomSalt = { ByteArray(32) { it.toByte() } },
                workScope = work,
            )
        clipboard.connect(privacy, routing, cleaners = null)
    }

    fun consentToFullMode() {
        rule.runOnUiThread {
            privacy.requestFullFunction()
            privacy.agree()
        }
        idle()
    }

    fun withdrawToBasicMode() {
        rule.runOnUiThread { privacy.withdraw() }
        idle()
    }

    fun login() {
        rule.runOnUiThread {
            tokens.save("visual-access", "visual-refresh", "full", "visual-user")
            routing.updateSession(SessionState.PHONE_BOUND, resumePending = false)
            home.sync()
        }
        idle()
    }

    fun idle() {
        repeat(6) {
            shadowOf(Looper.getMainLooper()).idle()
            rule.waitForIdle()
        }
    }

    override fun close() {
        if (::home.isInitialized) rule.runOnUiThread { home.onConsentWithdrawing() }
        if (::agent.isInitialized) rule.runOnUiThread { agent.onConsentWithdrawing() }
        work.cancel()
    }
}
