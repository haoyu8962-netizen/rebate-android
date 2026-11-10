package com.couli.app.shell

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.privacy.AppPrivacy
import com.couli.app.routing.AppRouting
import com.couli.app.routing.ROUTE_PAGE_TAG_PREFIX
import com.couli.app.routing.RoutingPrivacyHooks
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.privacy.PrivacyGate
import com.couli.core.privacy.PrivacyStore
import com.couli.core.privacy.RetryScheduler
import com.couli.core.router.PrivacyState
import com.couli.core.router.RouteRequest
import com.couli.core.router.RouteSource
import com.couli.core.router.SessionState
import com.couli.core.router.SharedPreferencesPendingBackend
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * FU-AND-NAV-BAR 任务书 §3 第 3 条（外壳级）：底栏无状态，选中只跟 AppShell.selection（只由 Router 放行后的 onOpenTab 写入）。
 * 接线照 AppShellGuardTest（真实 AppShell + AppPrivacy + AppRouting）。现行实现绿，换 NavigationBar 后须保持绿（杀「本地选中态」）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class FuNavBarShellGuardTest {
    @get:Rule val composeRule = createComposeRule()

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
        composeRule.setContent {
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

    private fun tab(label: String) = composeRule.onNodeWithText(label)

    private fun page(route: String) = composeRule.onNodeWithTag(ROUTE_PAGE_TAG_PREFIX + route)

    @Test
    fun guestTapOnOrdersIsBlockedAndHomeStaysSelected() {
        tab(ShellDraftTexts.TAB_HOME).assertIsSelected()
        tab(ShellDraftTexts.TAB_ORDERS).assertIsNotSelected()

        tab(ShellDraftTexts.TAB_ORDERS).performClick()
        composeRule.waitForIdle()
        page("Login").assertIsDisplayed()

        composeRule.runOnIdle { navController.popBackStack() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HOME_TAG).assertIsDisplayed()
        tab(ShellDraftTexts.TAB_HOME).assertIsSelected()
        tab(ShellDraftTexts.TAB_ORDERS).assertIsNotSelected()
        tab(ShellDraftTexts.TAB_ME).assertIsNotSelected()
        tab(ShellDraftTexts.TAB_AGENT).assertIsNotSelected()
    }

    @Test
    fun selectionFollowsRouterOpenNotTheTapItself() {
        routing.session.value = SessionState.LOGGED_IN
        composeRule.waitForIdle()
        tab(ShellDraftTexts.TAB_HOME).assertIsSelected()

        // 不点底栏，只经 Router 打开 Tab 根路由（放行 → onOpenTab）：底栏选中随之变化
        composeRule.runOnIdle { routing.router.open(RouteRequest("OrderList", JsonObject(emptyMap()), RouteSource.IN_APP)) }
        composeRule.waitForIdle()
        page("OrderList").assertIsDisplayed()
        tab(ShellDraftTexts.TAB_ORDERS).assertIsSelected()
        tab(ShellDraftTexts.TAB_HOME).assertIsNotSelected()

        // 点放行的 Tab：经 Router 放行后切过去
        tab(ShellDraftTexts.TAB_HOME).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HOME_TAG).assertIsDisplayed()
        tab(ShellDraftTexts.TAB_HOME).assertIsSelected()
        tab(ShellDraftTexts.TAB_ORDERS).assertIsNotSelected()

        // 会话失效后再点订单：被拦去登录，返回后选中仍停在首页
        routing.session.value = SessionState.GUEST
        composeRule.waitForIdle()
        tab(ShellDraftTexts.TAB_ORDERS).performClick()
        composeRule.waitForIdle()
        page("Login").assertIsDisplayed()
        composeRule.runOnIdle { navController.popBackStack() }
        composeRule.waitForIdle()
        tab(ShellDraftTexts.TAB_HOME).assertIsSelected()
        tab(ShellDraftTexts.TAB_ORDERS).assertIsNotSelected()
    }

    private companion object {
        const val HOME_TAG = "fu_nav_bar_home"
    }
}
