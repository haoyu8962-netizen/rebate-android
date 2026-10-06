package com.couli.app.routing

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.router.PrivacyState
import com.couli.core.router.UpdateCheck
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * R1-01（S0）：外部 App 显式启动导出的 Activity，不带 data，只带 Navigation 的框架 extras
 * （deepLinkIds = [导航图, RouteDestination]、deepLinkExtras = {name=Withdraw, paramsJson, readonly=false}）。
 * 对照组证明这个 Intent 确实能让「裸」NavHost 直达 Withdraw；[AppNavHost] 在设置导航图前清洗 Intent，停在首页。
 * 清洗与允许名单在 main 源码集，release 的允许名单为空（[releaseAllowlistKeepsNothing] 用同一份空名单验证）。
 *
 * R1-07：闸门长期未就绪时排队器不在主线程零延迟循环。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class LaunchIntentNavigationTest {
    private val navIdsKey = "android-support-nav:controller:deepLinkIds"
    private val navExtrasKey = "android-support-nav:controller:deepLinkExtras"

    /** 与 AppNavHost 同形状的「裸」导航图（不清洗 Intent）。 */
    private fun ComponentActivity.rawNavHost(onController: (NavHostController) -> Unit) {
        setContent {
            val controller = rememberNavController()
            onController(controller)
            NavHost(navController = controller, startDestination = HomeDestination) {
                composable<HomeDestination> { Text("home") }
                composable<RouteDestination> { entry -> Text(entry.toRoute<RouteDestination>().name) }
                composable<UpgradeDestination> { Text("upgrade") }
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** 取导航图与 RouteDestination 的 id（评审场景里的 [0, 394850480]）。 */
    private fun destinationIds(): IntArray {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java, Intent()).setup().get()
        var controller: NavHostController? = null
        activity.rawNavHost { controller = it }
        val graph = requireNotNull(controller).graph
        val route = requireNotNull(graph.findNode<RouteDestination>())
        return intArrayOf(graph.id, route.id)
    }

    private fun forgedIntent(ids: IntArray): Intent =
        Intent(Intent.ACTION_MAIN).apply {
            putExtra(navIdsKey, ids)
            putExtra(
                navExtrasKey,
                Bundle().apply {
                    putString("name", "Withdraw")
                    putString("paramsJson", "{\"amount\":10000}")
                    putBoolean("readonly", false)
                },
            )
        }

    private fun gate(settled: Boolean): EnvironmentGate =
        EnvironmentGate { EnvironmentStartupReconciler.Outcome.CLEANED_AND_RECORDED }.also { if (settled) it.settleAtStartup() }

    @Test
    fun forgedNavigationExtrasReachWithdrawWithoutSanitizing() {
        val ids = destinationIds()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java, forgedIntent(ids)).setup().get()
        var controller: NavHostController? = null
        activity.rawNavHost { controller = it }
        val entry = requireNotNull(controller).currentBackStackEntry
        assertTrue(
            "对照组：裸 NavHost 被框架 extras 直接带到 RouteDestination",
            entry
                ?.destination
                ?.route
                .orEmpty()
                .contains("RouteDestination"),
        )
        assertEquals("Withdraw", entry?.toRoute<RouteDestination>()?.name)
    }

    @Test
    fun appNavHostIgnoresForgedNavigationExtras() {
        val ids = destinationIds()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java, forgedIntent(ids)).setup().get()
        val routing = AppRouting(activity, gate(settled = true))
        var controller: NavHostController? = null
        activity.setContent {
            val navController = rememberNavController()
            controller = navController
            AppNavHost(routing = routing, home = { Text("home") }, navController = navController, launchExtraAllowlist = emptySet())
        }
        shadowOf(Looper.getMainLooper()).idle()
        val entry = requireNotNull(controller).currentBackStackEntry
        assertTrue(
            "停在首页",
            entry
                ?.destination
                ?.route
                .orEmpty()
                .contains("HomeDestination"),
        )
        assertEquals(1, requireNotNull(controller).currentBackStack.value.count { it.destination.route != null })
        assertNull("Activity 的 Intent 已不带框架导航 extras", activity.intent.extras?.getIntArray(navIdsKey))
        assertNull(activity.intent.data)
        assertNull("Router 没有收到任何请求", routing.lastTrace.value)
    }

    @Test
    fun sanitizerKeepsOnlyAllowlistedStringExtras() {
        val raw =
            forgedIntent(intArrayOf(0, 1)).apply {
                data = Uri.parse("couli-dev://r/Withdraw")
                putExtra("couli.catalog", "index")
                putExtra("android-support-nav:controller:deepLinkIntent", Intent())
                putExtra("other", "x")
            }
        val clean = LaunchIntentSanitizer.sanitize(raw, setOf("couli.catalog", navIdsKey))
        assertNull(clean.data)
        assertEquals("index", clean.getStringExtra("couli.catalog"))
        assertEquals(setOf("couli.catalog"), clean.extras?.keySet())
        assertEquals(Intent.ACTION_MAIN, clean.action)
    }

    @Test
    fun releaseAllowlistKeepsNothing() {
        val raw = forgedIntent(intArrayOf(0, 1)).apply { putExtra("couli.catalog", "index") }
        val clean = LaunchIntentSanitizer.sanitize(raw, emptySet())
        assertTrue(clean.extras == null || clean.extras?.isEmpty == true)
        assertNotNull(LaunchIntentSanitizer.sanitize(null, emptySet()))
    }

    @Test
    fun queueDoesNotSpinWhileGatesAreClosed() {
        val context = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        // 环境闸门一直未 settled、隐私未决、强更检查中、外壳未就绪
        val routing = AppRouting(context, gate(settled = false), clock = { 0L })
        routing.privacy.value = PrivacyState.NOT_DECIDED
        routing.update.value = UpdateCheck.CHECKING
        val looper = shadowOf(Looper.getMainLooper())
        looper.idle()
        routing.onLaunchIntent("couli-dev://r/Search?q=a", coldStart = true)
        val afterOffer = routing.drainCount
        looper.idleFor(Duration.ofSeconds(60))
        assertEquals("60 秒内没有因过期时刻反复 drain", afterOffer, routing.drainCount)
        assertFalse("主线程上没有排着的 drain", looper.nextScheduledTaskTime > Duration.ZERO)
    }
}
