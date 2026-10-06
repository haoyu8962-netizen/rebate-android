package com.couli.app.shell

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.privacy.AppPrivacy
import com.couli.app.routing.AppRouting
import com.couli.app.routing.FORCE_UPDATE_ENTRY_TAG
import com.couli.app.routing.ROUTE_PAGE_TAG_PREFIX
import com.couli.app.routing.RouteDestination
import com.couli.app.routing.RoutingPrivacyHooks
import com.couli.app.update.AppUpdate
import com.couli.app.update.FORCE_UPDATE_PAGE_TAG
import com.couli.app.update.FORCE_UPDATE_PRIVACY_TAG
import com.couli.app.update.StoreAccess
import com.couli.app.update.variantUpdateStore
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.privacy.PrivacyGate
import com.couli.core.privacy.PrivacyStore
import com.couli.core.privacy.RetryScheduler
import com.couli.core.router.EntryGuard
import com.couli.core.router.GuardKind
import com.couli.core.router.GuardReason
import com.couli.core.router.PendingActionStore
import com.couli.core.router.PrivacyState
import com.couli.core.router.RouteNames
import com.couli.core.router.RouteRequest
import com.couli.core.router.RouteSource
import com.couli.core.router.SharedPreferencesPendingBackend
import com.couli.core.router.UpdateCheck
import com.couli.core.update.MemoryUpdateStore
import com.couli.core.update.SharedPreferencesUpdateStore
import com.couli.core.update.UpdateCheckOutcome
import com.couli.core.update.UpdateCheckResult
import com.couli.core.update.UpdateChecker
import com.couli.core.update.UpdateGate
import com.couli.core.update.UpdateStore
import com.couli.core.update.UpdateStoreListing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * APP-02c Android 评审第 1 轮的强更外壳与接线用例（真实 AppShell + AppPrivacy + AppRouting + AppUpdate，接线同 AppModule）：
 * - R1-01（S0）：已知强更 + 隐私需要重新同意 + 没有待办 → 【不同意】→【仍不同意】进入基本模式，强更页仍在最上层；
 * - R1-02：缓存 ok、冷启动检查中 → Router 对外部来源 defer（update_checking）；本次失败 → Router 拿到 unknown，
 *   桥打开 AgentChat 被拒（update_unknown_blocked），不恢复旧 ok；
 * - R1-03：debug / staging 的版本检查结论不写进 Release 读取的 couli.update；
 * - R1-05：强更页「隐私政策」进 PrivacyCenter（占位页带强更状态标记），返回回到强更页。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class AppShellUpdateTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = 5_000_000L

    private class MemoryPrivacyStore(
        override var consentedVersion: String?,
    ) : PrivacyStore {
        override var decisionRaw: String = "consented"
        override var consentedAtMillis: Long? = 0L
        override var basicMode: Boolean = false
    }

    private object NoStores : StoreAccess {
        override val installSource: String? = null

        override fun canOpen(storeKey: String) = false

        override fun open(storeKey: String) = false
    }

    private lateinit var privacy: AppPrivacy
    private lateinit var routing: AppRouting
    private lateinit var update: AppUpdate
    private lateinit var navController: NavHostController

    @Before
    fun clearPending() {
        SharedPreferencesPendingBackend(context).delete()
    }

    private fun checkResult(min: String) =
        UpdateCheckResult(
            minSupported = min,
            recommended = min,
            latest = "1.2.0",
            defaultStore = "huawei",
            stores = listOf(UpdateStoreListing("huawei", "1.2.0")),
        )

    private fun newGate(store: UpdateStore) =
        UpdateGate(store, clock = { now }, appVersion = "1.0.0", platform = "android", channel = "official", installSource = null)

    /** 上一次进程留下的结论（成功检查），再冷启动。 */
    private fun cachedStore(min: String): MemoryUpdateStore = MemoryUpdateStore().also { newGate(it).apply(checkResult(min), now) }

    /** 接线同 AppModule / CouliApplication：隐私冷启动 → 强更冷启动（同意后才查）→ Router 订阅两者。 */
    private fun wire(
        consentedVersion: String,
        store: MemoryUpdateStore,
        checker: UpdateChecker,
    ) {
        val gate =
            PrivacyGate(MemoryPrivacyStore(consentedVersion), clock = { now }, bundledPolicyVersion = "1").apply {
                withdrawalCleanup = {}
                retryScheduler = RetryScheduler { _, _ -> }
            }
        privacy = AppPrivacy(gate, environmentSettled = { true }, clock = { now }).also { it.coldStart() }
        val environment = EnvironmentGate { EnvironmentStartupReconciler.Outcome.UNCHANGED }.also { it.settleAtStartup() }
        update =
            AppUpdate(
                gate = newGate(store),
                store = store,
                checker = checker,
                stores = NoStores,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                canCheck = { privacy.conclusion == PrivacyState.CONSENTED },
                clock = { now },
            )
        privacy.onConclusionChanged { update.onCheckAllowedChanged() }
        update.coldStart()
        routing =
            AppRouting(
                context,
                environment,
                clock = { now },
                initialPrivacy = privacy.conclusion,
                privacyHooks =
                    RoutingPrivacyHooks(
                        promptShowing = { privacy.promptShowing },
                        credentialsReadable = { privacy.credentialAccess.readable() },
                    ),
                initialUpdate = update.routerUpdate,
            )
        update.onRouterUpdate { routing.update.value = it }
        privacy.onConclusionChanged { routing.privacy.value = it }
        privacy.onConsentEvent(routing::onConsentEvent)
    }

    private fun showShell() {
        composeRule.setContent {
            navController = rememberNavController()
            AppShell(
                routing = routing,
                privacy = privacy,
                homeContent = { Box(Modifier.fillMaxSize().testTag(HOME_TAG)) },
                navController = navController,
                update = update,
            )
        }
        composeRule.waitForIdle()
    }

    private fun topRoute(): String? = runCatching { navController.currentBackStackEntry?.toRoute<RouteDestination>()?.name }.getOrNull()

    @Test
    fun stillDisagreeIntoBasicModeKeepsForceUpdateOnTop() {
        // 已知强更（上次成功检查 min 1.1.0 > 1.0.0）、隐私政策升级要重新同意（同意的是版本 0，包内是 1）、没有 pending_action
        wire(consentedVersion = "0", store = cachedStore("1.1.0"), checker = { UpdateCheckOutcome.Failure })
        showShell()
        assertEquals(PrivacyState.NOT_DECIDED, privacy.conclusion)
        assertEquals(UpdateCheck.BELOW_MIN, routing.update.value)
        composeRule.onNodeWithTag(FORCE_UPDATE_PAGE_TAG).assertIsDisplayed()

        composeRule.runOnIdle { privacy.disagree() }
        composeRule.waitForIdle()
        composeRule.runOnIdle { privacy.stillDisagree() }
        composeRule.waitForIdle()

        assertEquals(PrivacyState.BASIC_MODE, privacy.conclusion)
        assertNull("没有待办", PendingActionStore(SharedPreferencesPendingBackend(context), { now }).load())
        composeRule.onNodeWithTag(FORCE_UPDATE_PAGE_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(BASIC_MODE_PAGE_TAG).assertDoesNotExist()
        composeRule.runOnIdle { assertEquals("强更页在最上层", RouteNames.FORCE_UPDATE, topRoute()) }

        // 之后任何清栈（这里模拟再次清到起点）都会把强更页重新推回最上层
        composeRule.runOnIdle { navController.popBackStack() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(FORCE_UPDATE_PAGE_TAG).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(RouteNames.FORCE_UPDATE, topRoute()) }
    }

    @Test
    fun forceUpdatePrivacyPolicyOpensPrivacyCenterWithForceUpdateMarker() {
        wire(consentedVersion = "1", store = cachedStore("1.1.0"), checker = { UpdateCheckOutcome.Failure })
        showShell()
        composeRule.onNodeWithTag(FORCE_UPDATE_PAGE_TAG).assertIsDisplayed()

        composeRule.onNodeWithTag(FORCE_UPDATE_PRIVACY_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ROUTE_PAGE_TAG_PREFIX + "PrivacyCenter").assertIsDisplayed()
        composeRule.onNodeWithTag(FORCE_UPDATE_ENTRY_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(ROUTE_PAGE_TAG_PREFIX + "Agreement").assertDoesNotExist()

        composeRule.runOnIdle { navController.popBackStack() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(FORCE_UPDATE_PAGE_TAG).assertIsDisplayed()
    }

    @Test
    fun coldStartFailureWithCachedOkGivesRouterUnknownNotOk() {
        val answer = CompletableDeferred<UpdateCheckOutcome>()
        wire(consentedVersion = "1", store = cachedStore("1.0.0"), checker = { answer.await() })
        val agentChat = RouteRequest("AgentChat", JsonObject(emptyMap()), RouteSource.BRIDGE)

        // 检查进行中：缓存的 ok 不作数，外部来源等待
        assertEquals(UpdateCheck.CHECKING, routing.update.value)
        val waiting = EntryGuard.decide(agentChat, routing.guardState())
        assertEquals(GuardKind.DEFER, waiting.kind)
        assertEquals(GuardReason.UPDATE_CHECKING, waiting.reason)

        // 本次失败：按 unknown，不恢复旧 ok
        answer.complete(UpdateCheckOutcome.Failure)
        composeRule.waitForIdle()
        assertEquals(UpdateCheck.UNKNOWN, routing.update.value)
        val decision = EntryGuard.decide(agentChat, routing.guardState())
        assertEquals(GuardReason.UPDATE_UNKNOWN_BLOCKED, decision.reason)
        assertFalse(decision.kind == GuardKind.OPEN || decision.kind == GuardKind.OPEN_READONLY)
    }

    @Test
    fun debugVariantResultsNeverReachTheReleaseStore() {
        val releaseStore = SharedPreferencesUpdateStore(context)
        releaseStore.save(releaseStore.load().copy(result = null, forcedBy10405 = false))
        val debugStore = variantUpdateStore(context)
        newGate(debugStore).apply(checkResult("1.2.0"), now)
        newGate(debugStore).on10405("1.2.0", now)

        val release = releaseStore.load()
        assertNull("Release 读不到调试假结果", release.result)
        assertFalse(release.forcedBy10405)
        assertEquals("调试存储里确有假的强更结论", "1.2.0", debugStore.load().result?.minSupported)
        assertEquals("Release 的 Gate 不被拦", "unknown", newGate(releaseStore).state(now).statusRaw)
    }

    private companion object {
        const val HOME_TAG = "test_home"
    }
}
