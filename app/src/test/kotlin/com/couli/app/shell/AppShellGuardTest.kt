package com.couli.app.shell

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.privacy.AppPrivacy
import com.couli.app.routing.AppRouting
import com.couli.app.routing.HomeDestination
import com.couli.app.routing.ROUTE_PAGE_TAG_PREFIX
import com.couli.app.routing.RouteDestination
import com.couli.app.routing.RoutingPrivacyHooks
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.privacy.PrivacyGate
import com.couli.core.privacy.PrivacyStore
import com.couli.core.privacy.RetryScheduler
import com.couli.core.router.PendingActionStore
import com.couli.core.router.PrivacyState
import com.couli.core.router.RouteRequest
import com.couli.core.router.RouteSource
import com.couli.core.router.SessionState
import com.couli.core.router.SharedPreferencesPendingBackend
import com.couli.core.router.UpdateCheck
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * APP-02b Android 评审第 1 轮的外壳用例（真实 AppShell + AppPrivacy + AppRouting，接线同 AppModule）：
 * - R1-02：Tab 点击服从守卫，要登录 → 去 Login 并登记 pending_action，不选中受限 Tab；
 * - R1-06：已同意停在 AgentChat（上面还压着一页）时撤回同意 → 清空导航栈、回到基本模式页；
 * - R1-07：Router 以只读打开 Tab 根路由（强更未知恢复 OrderList）时，Tab 根页保留只读标记；
 * - R2-02：只读 Tab 经外壳重建（Activity 重建、字号变化，StateRestorationTester 模拟保存→销毁→恢复）后仍只读，
 *   只有用户重新点 Tab 才按 Router 新的结论解除。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class AppShellGuardTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = 5_000_000L

    private class MemoryStore : PrivacyStore {
        override var decisionRaw: String = "consented"
        override var consentedVersion: String? = "1"
        override var consentedAtMillis: Long? = 0L
        override var basicMode: Boolean = false
    }

    private lateinit var privacy: AppPrivacy
    private lateinit var routing: AppRouting
    private lateinit var navController: NavHostController
    private lateinit var restoration: StateRestorationTester

    @Before
    fun setUp() {
        SharedPreferencesPendingBackend(context).delete()
        val gate =
            PrivacyGate(MemoryStore(), clock = { now }, bundledPolicyVersion = "1").apply {
                withdrawalCleanup = {}
                retryScheduler = RetryScheduler { _, _ -> }
            }
        privacy = AppPrivacy(gate, environmentSettled = { true }, clock = { now }).also { it.coldStart() }
        val environment = EnvironmentGate { EnvironmentStartupReconciler.Outcome.UNCHANGED }.also { it.settleAtStartup() }
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
            )
        privacy.onConclusionChanged { routing.privacy.value = it }
        privacy.onConsentEvent(routing::onConsentEvent)
        restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            navController = rememberNavController()
            AppShell(
                routing = routing,
                privacy = privacy,
                homeContent = { Box(Modifier.fillMaxSize().testTag(HOME_TAG)) },
                navController = navController,
            )
        }
        composeRule.waitForIdle()
        assertEquals(PrivacyState.CONSENTED, routing.privacy.value)
    }

    private fun page(route: String) = composeRule.onNodeWithTag(ROUTE_PAGE_TAG_PREFIX + route)

    @Test
    fun guestTappingOrdersGoesToLoginAndRegistersPending() {
        composeRule.onNodeWithText(ShellDraftTexts.TAB_ORDERS).performClick()
        composeRule.waitForIdle()
        page("Login").assertIsDisplayed()
        page("OrderList").assertDoesNotExist()
        val pending = PendingActionStore(SharedPreferencesPendingBackend(context), { now }).load()
        assertEquals("OrderList", (pending?.params?.get("route") as? JsonPrimitive)?.content)

        // 从登录页返回：仍在首页 Tab，没有选中受限的订单 Tab
        composeRule.runOnIdle { navController.popBackStack() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HOME_TAG).assertIsDisplayed()
        page("OrderList").assertDoesNotExist()
    }

    @Test
    fun withdrawWhileOnAgentChatClearsStackAndShowsBasicModePage() {
        composeRule.onNodeWithText(ShellDraftTexts.TAB_AGENT).performClick()
        composeRule.waitForIdle()
        page("AgentChat").assertIsDisplayed()
        composeRule.runOnIdle {
            routing.router.open(RouteRequest("Settings", JsonObject(emptyMap()), RouteSource.IN_APP))
        }
        composeRule.waitForIdle()
        page("Settings").assertIsDisplayed()

        composeRule.runOnIdle { privacy.withdraw() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(BASIC_MODE_PAGE_TAG).assertIsDisplayed()
        page("AgentChat").assertDoesNotExist()
        page("Settings").assertDoesNotExist()
        composeRule.runOnIdle {
            assertTrue("导航栈清到外壳起点", navController.currentBackStackEntry?.destination?.hasRoute(HomeDestination::class) == true)
            assertEquals("栈里不再有任何推入的页面", null, navController.previousBackStackEntry)
        }
    }

    /** R1-06（恢复的导航状态）：栈里一页在显示时重新过守卫，已不再允许就清栈回到起点，不渲染。 */
    @Test
    fun stalePageIsReguardedWhenItBecomesVisibleAgain() {
        routing.session.value = SessionState.LOGGED_IN
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            routing.router.open(RouteRequest("OrderDetail", JsonObject(mapOf("order_id" to JsonPrimitive("o-1"))), RouteSource.IN_APP))
            routing.router.open(RouteRequest("Settings", JsonObject(emptyMap()), RouteSource.IN_APP))
        }
        composeRule.waitForIdle()
        page("Settings").assertIsDisplayed()

        // 会话没了（等同于进程重建后恢复出旧栈、状态已变）：返回到 OrderDetail 时守卫要求登录 → 不渲染，清栈
        routing.session.value = SessionState.GUEST
        composeRule.runOnIdle { navController.popBackStack() }
        composeRule.waitForIdle()
        page("OrderDetail").assertDoesNotExist()
        composeRule.onNodeWithTag(HOME_TAG).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(null, navController.previousBackStackEntry) }
    }

    @Test
    fun readonlyTabRootFromRouterKeepsTheReadonlyFlag() {
        routing.session.value = SessionState.LOGGED_IN
        routing.update.value = UpdateCheck.UNKNOWN
        composeRule.waitForIdle()
        PendingActionStore(SharedPreferencesPendingBackend(context), { now })
            .save(PendingActionStore.OPEN_ROUTE, JsonObject(mapOf("route" to JsonPrimitive("OrderList"))), now, RouteSource.IN_APP.raw)
        composeRule.runOnIdle { routing.router.resumePending(enterPrerequisite = false) }
        composeRule.waitForIdle()
        page("OrderList").assertIsDisplayed()
        composeRule.onNodeWithText("readonly").assertIsDisplayed()
        composeRule.runOnIdle {
            assertTrue("Tab 根路由不另推一页", navController.currentBackStackEntry?.destination?.hasRoute(HomeDestination::class) == true)
        }
    }

    @Test
    fun readonlyTabSurvivesShellRecreationUntilTheUserTapsTheTabAgain() {
        routing.session.value = SessionState.LOGGED_IN
        routing.update.value = UpdateCheck.UNKNOWN
        composeRule.waitForIdle()
        PendingActionStore(SharedPreferencesPendingBackend(context), { now })
            .save(PendingActionStore.OPEN_ROUTE, JsonObject(mapOf("route" to JsonPrimitive("OrderList"))), now, RouteSource.IN_APP.raw)
        composeRule.runOnIdle { routing.router.resumePending(enterPrerequisite = false) }
        composeRule.waitForIdle()
        page("OrderList").assertIsDisplayed()
        composeRule.onNodeWithText("readonly").assertIsDisplayed()

        // 模拟 Activity 重建（字号变化等）：保存状态 → 销毁外壳 → 用保存的状态重建。强更仍 UNKNOWN，IN_APP 重新判定会放行，
        // 但恢复不是新的点击，保存的只读标记必须原样保留
        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        page("OrderList").assertIsDisplayed()
        composeRule.onNodeWithText("readonly").assertIsDisplayed()

        // 再重建一次也一样（重建不会逐次放宽）
        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("readonly").assertIsDisplayed()

        // 用户重新点订单 Tab：Router 按新的打开结果（UNKNOWN 下原生主动操作放行）解除只读
        composeRule.onNodeWithText(ShellDraftTexts.TAB_ORDERS).performClick()
        composeRule.waitForIdle()
        page("OrderList").assertIsDisplayed()
        composeRule.onNodeWithText("readonly").assertDoesNotExist()
    }

    /** R2-02 的纯函数部分：恢复只收紧不放宽；守卫已不允许该 Tab 时照旧回首页。 */
    @Test
    fun tightenedSelectionNeverLoosensASavedReadonlyFlag() {
        routing.session.value = SessionState.LOGGED_IN
        routing.update.value = UpdateCheck.UNKNOWN
        val state = routing.guardState()
        assertEquals(TabSelection(ShellTab.ORDERS, readonly = false), guardedSelection(ShellTab.ORDERS, state))
        assertEquals(TabSelection(ShellTab.ORDERS, readonly = true), tightenedSelection(TabSelection(ShellTab.ORDERS, true), state))
        assertEquals(TabSelection(ShellTab.ORDERS, readonly = false), tightenedSelection(TabSelection(ShellTab.ORDERS, false), state))
        val guest = state.copy(session = SessionState.GUEST.raw)
        assertEquals(ShellTab.HOME, tightenedSelection(TabSelection(ShellTab.ORDERS, true), guest).tab)
    }

    private companion object {
        const val HOME_TAG = "test_home"
    }
}
