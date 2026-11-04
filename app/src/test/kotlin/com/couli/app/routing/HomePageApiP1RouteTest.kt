package com.couli.app.routing

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.privacy.AppPrivacy
import com.couli.app.shell.AppShell
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.designsystem.CouliTheme
import com.couli.core.privacy.PrivacyGate
import com.couli.core.privacy.PrivacyStore
import com.couli.core.privacy.RetryScheduler
import com.couli.core.router.RouteRequest
import com.couli.core.router.RouteSource
import com.couli.core.router.SessionState
import com.couli.core.router.UpdateCheck
import com.couli.generated.Routes
import java.time.OffsetDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * FU-X-HOME-PAGE-API-android 测试 v1（独立测试作者）：P1 取 B（编排 2026-11-04 11:24；鸿蒙 10:41 / 11:18 同口径）。
 *
 * 升锁到 rebate-platform 00deded0ef6f 后，routes.json 新登记 InviteBackfill（:848 起，native、auth phone、since.android 1.0.0、entry in_app）与
 * InviteLink（native、auth none、since.android 1.0.0、entry deeplink、params {code}），Android 没有这两页。底版（a7c9f1f）里它们不在路由表 → 守卫按未知路由给
 * 升级提示；升锁后守卫放行 → NavHost 推 RouteDestination → [RoutePageRegistry] 没有页面 → [PlaceholderRoutePage]（main 源码集，Release 也编入，屏上是英文路由名）。
 * B：「契约已登记、本端没接页面」的原生路由按不可用处理，回落升级提示（与升锁前行为一致），不进占位页。
 * 对照：注册了页面的原生路由（Search）、h5 路由（Help）照常打开；since 为 null 的新路由（Favorites）仍是升级提示。
 * 外壳用只测外壳的接线（AppShell 各 Feature 为 null）：Search / Help 在注册表里，没接线时渲染占位页（本 harness 的既有做法），这里只看「打开了它」。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class HomePageApiP1RouteTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = OffsetDateTime.parse("2026-11-04T12:00:00+08:00").toInstant().toEpochMilli()
    private lateinit var routing: AppRouting

    private class MemoryStore : PrivacyStore {
        override var decisionRaw: String = "consented"
        override var consentedVersion: String? = "3"
        override var consentedAtMillis: Long? = 0L
        override var basicMode: Boolean = false
        override var withdrawalCleanupPending: Boolean = false
    }

    private fun launch() {
        val gate =
            PrivacyGate(MemoryStore(), clock = { clock }, bundledPolicyVersion = "3").apply {
                retryScheduler = RetryScheduler { _, _ -> }
            }
        val privacy = AppPrivacy(gate, environmentSettled = { true }, clock = { clock }).also { it.coldStart() }
        val environment = EnvironmentGate { EnvironmentStartupReconciler.Outcome.UNCHANGED }.also { it.settleAtStartup() }
        routing =
            AppRouting(
                context,
                environment,
                clock = { clock },
                initialPrivacy = privacy.conclusion,
                privacyHooks = RoutingPrivacyHooks(),
                initialUpdate = UpdateCheck.OK,
            )
        privacy.onConclusionChanged { routing.privacy.value = it }
        composeRule.runOnUiThread { routing.updateSession(SessionState.PHONE_BOUND, resumePending = false) }
        composeRule.setContent {
            CouliTheme {
                AppShell(routing = routing, privacy = privacy, homeContent = { Text("home") }, navController = rememberNavController())
            }
        }
        settle()
    }

    private fun settle() {
        repeat(SETTLE_ROUNDS) {
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.waitForIdle()
        }
    }

    private fun open(
        route: String,
        params: JsonObject = JsonObject(emptyMap()),
    ) {
        composeRule.runOnUiThread { routing.router.open(RouteRequest(route, params, RouteSource.IN_APP), routing.guardState()) }
        settle()
    }

    private fun has(tag: String): Boolean = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun placeholder(route: String) = ROUTE_PAGE_TAG_PREFIX + route

    @Test
    fun r01RoutesNewInTheLockWithoutAnAndroidPageAreRegisteredAndAvailableByVersion() {
        // 前提核对：新锁里这两个路由确已登记、since.android 已到（不是靠 since=null 挡住的）
        listOf("InviteBackfill", "InviteLink").forEach { name ->
            val spec = Routes.spec(name)
            assertTrue("$name 应在新锁路由表里（升锁 00deded0ef6f）", spec != null)
            assertTrue("$name 的 since.android 已登记", spec?.sinceAndroid != null)
        }
    }

    @Test
    fun r02InviteBackfillFromInsideTheAppGetsTheUpgradePromptNotThePlaceholder() {
        launch()
        open("InviteBackfill", JsonObject(mapOf("source" to JsonPrimitive("me"))))
        assertTrue("InviteBackfill 本端没有页面：回落升级提示（同升锁前）", has(UPGRADE_PAGE_TAG))
        assertFalse("不得进开发占位页（Release 也编入，显示英文路由名）", has(placeholder("InviteBackfill")))
    }

    @Test
    fun r03InviteLinkDeepLinkGetsTheUpgradePromptNotThePlaceholder() {
        launch()
        composeRule.runOnUiThread { routing.onLaunchIntent("couli-dev://r/InviteLink?code=AB12CD", coldStart = false) }
        settle()
        assertTrue("邀请链接深链进来：升级提示（同升锁前的未知路由）", has(UPGRADE_PAGE_TAG))
        assertFalse("不得进开发占位页", has(placeholder("InviteLink")))
    }

    @Test
    fun r04NativeRoutesWithAPageStillOpen() {
        launch()
        open("Search", JsonObject(mapOf("q" to JsonPrimitive("milk"))))
        assertTrue("注册了页面的原生路由照常打开（不得一并拦掉）", has(placeholder("Search")))
        assertFalse(has(UPGRADE_PAGE_TAG))
    }

    @Test
    fun r05H5RoutesStillOpen() {
        launch()
        open("Help")
        assertTrue("h5 路由不按「本端页面」名单处理，照常打开", has(placeholder("Help")))
        assertFalse(has(UPGRADE_PAGE_TAG))
    }

    @Test
    fun r06NewRoutesWithoutAnAndroidSinceStayTheUpgradePrompt() {
        launch()
        open("Favorites")
        assertTrue("since.android 为 null 的新路由仍是升级提示", has(UPGRADE_PAGE_TAG))
        assertFalse(has(placeholder("Favorites")))
    }

    private companion object {
        const val SETTLE_ROUNDS = 6
    }
}
