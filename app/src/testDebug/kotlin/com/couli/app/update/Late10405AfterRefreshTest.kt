package com.couli.app.update

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.AppModule
import com.couli.app.clipboard.ClipboardReviewCipher0724
import com.couli.app.routing.RouteDestination
import com.couli.app.shell.AppShell
import com.couli.core.network.ApiResult
import com.couli.core.router.UpdateCheck
import com.couli.core.update.UpdateStatus
import com.couli.feature.home.HomeController
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow

/** M1b：真实冷启动请求被下拉刷新取代，旧响应最后才返回；生产页面和网络接线均不替换。 */
@RunWith(AndroidJUnit4::class)
@Config(
    application = Application::class,
    sdk = [36],
    shadows = [Late10405Wire::class, Late10405PageCalls::class, Late10405Settlements::class, ClipboardReviewCipher0724::class],
)
class Late10405AfterRefreshTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var graph: SingleSettle10405Graph
    private lateinit var nav: NavHostController
    private lateinit var home: HomeController
    private var forceEntries = 0
    private val belowTransitions = mutableListOf<UpdateCheck>()

    @Before
    fun setUp() {
        SingleSettle10405ProcessState.reset()
        SingleSettle10405Wire.reset()
        Late10405Wire.held = Late10405Wire.HeldResponse(SingleSettle10405Graph.VERSION)
    }

    @After
    fun tearDown() {
        Late10405Wire.held.release.countDown()
        try {
            if (::graph.isInitialized) compose.runOnUiThread { graph.close() }
        } finally {
            SingleSettle10405ProcessState.reset()
            SingleSettle10405Wire.reset()
        }
    }

    @Test
    fun lateSupported10405AfterRefreshNeverEntersPageForceUpdate() = scenario(SingleSettle10405Graph.VERSION, forced = false)

    @Test
    fun lateBelowMinimum10405AfterRefreshSettlesOnceOnlyThroughNetwork() = scenario("999.0.0", forced = true)

    private fun launch() {
        compose.runOnUiThread { graph = SingleSettle10405Graph() }
        until("冷启动 phone_bound 检查完成") { graph.phoneChecksSettled() }
        compose.setContent {
            nav = rememberNavController()
            AppShell(routing = graph.routing, privacy = graph.privacy, homeContent = {}, navController = nav, update = graph.update)
        }
        compose.waitForIdle()
        compose.runOnUiThread {
            nav.addOnDestinationChangedListener { _, destination, _ ->
                if (destination.hasRoute(RouteDestination::class) &&
                    nav.currentBackStackEntry?.toRoute<RouteDestination>()?.name == "ForceUpdate"
                ) {
                    forceEntries += 1
                }
            }
            graph.update.onRouterUpdate { if (it == UpdateCheck.BELOW_MIN) belowTransitions += it }
            graph.home.ensure()
        }
        until("旧请求已在传输层挂起") { Late10405Wire.held.arrived.count == 0L }
        home = checkNotNull(graph.home.controller.value)
    }

    private fun scenario(
        minimum: String,
        forced: Boolean,
    ) {
        Late10405Wire.held = Late10405Wire.HeldResponse(minimum)
        launch()
        val settlements = Shadow.extract<Late10405Settlements>(home)
        val pageCalls = Shadow.extract<Late10405PageCalls>(graph.home)
        assertTrue("刷新前，旧请求还没返回到控制器", settlements.completed.isEmpty())
        compose.runOnUiThread { home.refresh(home.state.value.render) }
        until("刷新请求先成功结算，旧响应仍被扣住") { settlements.completed.size == 1 }
        val fresh = settlements.completed.single()
        assertEquals(2L, fresh.seq)
        assertTrue(fresh.pull)
        assertTrue(fresh.result is ApiResult.Success)
        assertTrue(fresh.owned)
        assertFalse(home.state.value.refreshing)
        assertEquals(1L, Late10405Wire.held.release.count)
        assertNotForced()
        val refreshed = home.state.value

        Late10405Wire.held.release.countDown()
        until("旧的 10405 已走完真实页面结算") { settlements.completed.size == 2 }
        val late = settlements.completed.last()
        assertEquals("明确覆盖 seq != pageSeq 的旧请求分支", listOf(2L, 1L), settlements.completed.map { it.seq })
        assertFalse(late.pull)
        assertTrue("旧请求没有因身份失效被丢弃", late.owned)
        assertTrue(late.result is ApiResult.Failure)
        assertEquals(10405, (late.result as ApiResult.Failure).code)
        assertSame("仍是同一个生产首页控制器", home, graph.home.controller.value)
        assertEquals("迟到失败不替换刷新后的页面", refreshed, home.state.value)
        assertEquals("M1b：页面层强更调用必须为 0", 0, pageCalls.forced)
        assertEquals("两笔首页请求；旧 10405 不重放", 2, Late10405Wire.held.requests.get())
        assertFalse("full 会话 10405 不刷新令牌", SingleSettle10405Wire.recorded().contains(SingleSettle10405Wire.REFRESH))
        if (forced) assertNetworkForcedOnce(minimum) else assertNotForced()
    }

    private fun assertNotForced() {
        assertEquals(UpdateStatus.OK, graph.update.ui.value.status)
        assertEquals(UpdateCheck.OK, graph.routing.update.value)
        assertEquals(0, forceEntries)
        assertTrue(belowTransitions.isEmpty())
        compose.onNodeWithTag(FORCE_UPDATE_PAGE_TAG).assertDoesNotExist()
        assertFalse(graph.disk.load().forcedByServer)
        assertFalse(graph.disk.load().forcedBy10405)
        assertTrue("检查全部写入历史，不能先强更后清掉", graph.writes.isEmpty())
        assertNotEquals(UpdateStatus.BELOW_MIN, graph.restartedGate().state().status)
    }

    private fun assertNetworkForcedOnce(minimum: String) {
        assertEquals(UpdateStatus.BELOW_MIN, graph.update.ui.value.status)
        assertEquals(UpdateCheck.BELOW_MIN, graph.routing.update.value)
        compose.onNodeWithTag(FORCE_UPDATE_PAGE_TAG).assertIsDisplayed()
        assertEquals(1, forceEntries)
        assertEquals(listOf(UpdateCheck.BELOW_MIN), belowTransitions)
        assertEquals("迟到 10405 由网络层恰好结算一次", 1, graph.writes.size)
        assertTrue(graph.disk.load().forcedBy10405)
        assertFalse("带 min 的网络结算不走无条件强更", graph.disk.load().forcedByServer)
        assertEquals(minimum, graph.disk.load().forcedMinimum)
        val stack = graph.writeStacks.single()
        assertTrue("实际经过 AppUpdate.on10405", stack.any { it.className == AppUpdate::class.java.name && it.methodName == "on10405" })
        assertTrue(
            "使用 AppModule.apiClient 的生产网络回调",
            stack.any { it.className.startsWith(AppModule::class.java.name) && it.methodName.contains("apiClient") },
        )
        assertEquals(UpdateStatus.BELOW_MIN, graph.restartedGate().state().status)
    }

    private fun until(
        label: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        do {
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            if (condition()) {
                shadowOf(Looper.getMainLooper()).idle()
                compose.waitForIdle()
                return
            }
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        assertTrue(label, condition())
    }
}
