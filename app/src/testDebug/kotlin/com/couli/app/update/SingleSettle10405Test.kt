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
import com.couli.core.router.UpdateCheck
import com.couli.core.update.UpdateStatus
import com.couli.feature.home.HomeController
import com.couli.feature.me.MeSnapshot
import com.couli.feature.me.MeTabController
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * FU-X-10405-SINGLE-SETTLE，D10-618 Q1 / 03 §4.2。
 * AppModule 的真实首页、AppMe、ApiClient、Router → 真实 AppUpdate / UpdateGate → AppShell 强更页。
 * 不用仅计数的 forceUpdate 替身：同时查实际导航、持久化写入历史、重建 Gate 与网络调用栈。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], shadows = [SingleSettle10405Wire::class, ClipboardReviewCipher0724::class])
@Suppress("TooManyFunctions") // 沿用 ClipboardFlow0724Test：15 个验收场景分别保留独立用例。
class SingleSettle10405Test {
    @get:Rule val compose = createComposeRule()
    private lateinit var graph: SingleSettle10405Graph
    private lateinit var nav: NavHostController
    private lateinit var home: HomeController
    private lateinit var me: MeTabController
    private var forceEntries = 0
    private val belowTransitions = mutableListOf<UpdateCheck>()

    private fun launch(initialScope: String) {
        compose.runOnUiThread { graph = SingleSettle10405Graph(initialScope) }
        until("冷启动的 phone_bound 补查完成") { graph.phoneChecksSettled() }
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
        }
        assertEquals(UpdateCheck.OK, graph.update.routerUpdate)
        assertTrue(graph.writes.isEmpty())
    }

    @Before
    fun setUp() {
        SingleSettle10405ProcessState.reset()
        SingleSettle10405Wire.reset()
    }

    @After
    fun tearDown() {
        try {
            if (::graph.isInitialized) compose.runOnUiThread { graph.close() }
        } finally {
            SingleSettle10405ProcessState.reset()
            SingleSettle10405Wire.reset()
        }
    }

    @Test fun homeEqualMinimumDoesNotForceOrPersist() = scenario(Target.HOME, SingleSettle10405Graph.VERSION)

    @Test fun homeLowerMinimumDoesNotForceOrPersist() = scenario(Target.HOME, "0.0.0")

    @Test fun homeBelowMinimumSettlesExactlyOnceThroughNetwork() = scenario(Target.HOME, "999.0.0", forced = true)

    @Test fun homeMissingMinimumStillSettlesOnceThroughNetwork() = scenario(Target.HOME, null, forced = true)

    @Test fun homeRestrictedSupportedRefreshesWithoutForceOrReplay() =
        scenario(
            Target.HOME,
            SingleSettle10405Graph.VERSION,
            restricted = true,
        )

    @Test fun homeRestrictedLowerMinimumRefreshesWithoutForceOrReplay() = scenario(Target.HOME, "0.0.0", restricted = true)

    @Test fun homeItemsEqualMinimumDoesNotForceOrPersist() = scenario(Target.ITEMS, SingleSettle10405Graph.VERSION)

    @Test fun homeItemsBelowMinimumSettlesExactlyOnceThroughNetwork() = scenario(Target.ITEMS, "999.0.0", forced = true)

    @Test fun homeItemsRestrictedSupportedRefreshesWithoutForceOrReplay() =
        scenario(Target.ITEMS, SingleSettle10405Graph.VERSION, restricted = true)

    @Test fun meEqualMinimumDoesNotForceOrPersist() = scenario(Target.ME, SingleSettle10405Graph.VERSION)

    @Test fun meLowerMinimumDoesNotForceOrPersist() = scenario(Target.ME, "0.0.0")

    @Test fun meBelowMinimumSettlesExactlyOnceThroughNetwork() = scenario(Target.ME, "999.0.0", forced = true)

    @Test fun meMissingMinimumStillSettlesOnceThroughNetwork() = scenario(Target.ME, null, forced = true)

    @Test fun meRestrictedSupportedRefreshesWithoutForceOrReplay() = scenario(Target.ME, SingleSettle10405Graph.VERSION, restricted = true)

    @Test fun meRestrictedMissingMinimumRefreshesWithoutForceOrReplay() = scenario(Target.ME, null, restricted = true)

    private fun scenario(
        target: Target,
        minimum: String?,
        forced: Boolean = false,
        restricted: Boolean = false,
    ) {
        val coldRestricted = restricted && target != Target.ME
        launch(if (coldRestricted) "deletion_only" else "full")
        warm(target)
        if (coldRestricted) {
            val startup = SingleSettle10405Wire.recorded()
            assertEquals("受限令牌冷启动先刷新一次", 1, startup.count { it == SingleSettle10405Wire.REFRESH })
            assertTrue("业务首页请求在刷新之后", startup.indexOf(SingleSettle10405Wire.REFRESH) < startup.indexOf(SingleSettle10405Wire.HOME))
            assertEquals("full", graph.tokens.scope)
            assertEquals("fixture-refreshed", graph.tokens.accessToken)
        }
        val baseline = SingleSettle10405Wire.recorded().size
        val epoch = graph.tokens.loginEpoch
        SingleSettle10405Wire.minimum = minimum
        SingleSettle10405Wire.failingPath = target.path
        SingleSettle10405Wire.withPool = target == Target.ITEMS
        if (restricted && target == Target.ME) {
            // full 会话的冷启动屏障已经走过；同一登录世代后续换成受限令牌。
            // getMe 在受限会话白名单内，因而真的收到 10405，再走 G07 的 REFRESH_ONLY（min 为空也必须刷新）。
            // 首页两个 operation 不在受限白名单内，首页用上面的冷启动先刷新路径，不能伪造不可达的请求。
            compose.runOnUiThread {
                val snapshot = graph.tokens.snapshot()
                check(graph.tokens.replaceIf(snapshot.generation, "fixture-restricted", "fixture-refresh-restricted", "deletion_only"))
            }
        }
        compose.runOnUiThread {
            when (target) {
                Target.HOME, Target.ITEMS -> home.refresh(home.state.value.render)
                Target.ME -> assertTrue("页面确实发起读取", me.refresh(pull = true))
            }
        }
        awaitSettlement(target, baseline)
        val sent = SingleSettle10405Wire.recorded().drop(baseline)
        assertEquals("10405 不自动重放原请求", 1, sent.count { it == target.path })
        if (restricted && target == Target.ME) {
            assertEquals(
                "先处理 10405，再单飞刷新一次",
                listOf(target.path, SingleSettle10405Wire.REFRESH),
                sent.filter {
                    it == target.path ||
                        it == SingleSettle10405Wire.REFRESH
                },
            )
            assertEquals("full", graph.tokens.scope)
            assertEquals("fixture-refreshed", graph.tokens.accessToken)
            assertEquals("刷新不算重新登录", epoch, graph.tokens.loginEpoch)
        } else {
            assertFalse("普通 10405 不刷新", sent.contains(SingleSettle10405Wire.REFRESH))
        }
        if (forced) assertForcedOnce(minimum) else assertNotForced()
    }

    private fun awaitSettlement(
        target: Target,
        baseline: Int,
    ) {
        until("10405 已返回并由页面结算，不能只等请求发出") {
            SingleSettle10405Wire.recorded().drop(baseline).contains(target.path) &&
                when (target) {
                    Target.HOME -> {
                        !home.state.value.refreshing
                    }

                    Target.ITEMS -> {
                        home.state.value.sectionErrors
                            .containsKey("pool")
                    }

                    Target.ME -> {
                        !graph.me.deps.repo.state.value.refreshing && graph.me.deps.repo.state.value.error != null
                    }
                }
        }
    }

    private fun warm(target: Target) {
        compose.runOnUiThread {
            if (target == Target.ME) {
                me = MeTabController(graph.me.deps, graph.me.computeIdentity())
                graph.me.deps.tab
                    .bind(me)
                assertTrue(me.refresh())
            } else {
                graph.home.ensure()
            }
        }
        until("真实控制器已收到成功的首页或我的数据") {
            if (target ==
                Target.ME
            ) {
                graph.me.deps.repo.state.value.phase == MeSnapshot.CONTENT
            } else {
                graph.home.controller.value
                    ?.state
                    ?.value
                    ?.pageVersion ==
                    1
            }
        }
        if (target != Target.ME) home = checkNotNull(graph.home.controller.value)
        assertEquals("成功预热期间没有强更写入", 0, graph.writes.size)
    }

    private fun assertNotForced() {
        assertEquals("不能被页面层改写网络层的结论", UpdateStatus.OK, graph.update.ui.value.status)
        assertEquals(UpdateCheck.OK, graph.routing.update.value)
        assertEquals("从未导航进强更页", 0, forceEntries)
        assertTrue(belowTransitions.isEmpty())
        compose.onNodeWithTag(FORCE_UPDATE_PAGE_TAG).assertDoesNotExist()
        assertFalse("forcedByServer 不落盘", graph.disk.load().forcedByServer)
        assertFalse(graph.disk.load().forcedBy10405)
        assertTrue("也不允许先写后清伪装成最终正确", graph.writes.isEmpty())
        assertNotEquals("重建 Gate 不得恢复强更", UpdateStatus.BELOW_MIN, graph.restartedGate().state().status)
    }

    private fun assertForcedOnce(minimum: String?) {
        assertEquals(UpdateStatus.BELOW_MIN, graph.update.ui.value.status)
        assertEquals(UpdateCheck.BELOW_MIN, graph.routing.update.value)
        compose.onNodeWithTag(FORCE_UPDATE_PAGE_TAG).assertIsDisplayed()
        assertEquals("实际强更导航一次", 1, forceEntries)
        assertEquals(listOf(UpdateCheck.BELOW_MIN), belowTransitions)
        // StateFlow / Router 会合并相同状态；单看导航次数抓不住两次 onServerForced，必须另查写入次数和来源。
        assertEquals("网络层结算恰好一次，页面不得再次落盘", 1, graph.writes.size)
        assertTrue(graph.disk.load().forcedBy10405)
        assertEquals("只有网络层收到未带 min 的 10405 才走 onServerForced", minimum == null, graph.disk.load().forcedByServer)
        assertEquals(minimum, graph.disk.load().forcedMinimum)
        assertTrue(
            "唯一写入必须经过真实 AppUpdate.on10405，不能删网络回调后让页面代办",
            graph.writeStacks.single().any { it.className == AppUpdate::class.java.name && it.methodName == "on10405" },
        )
        assertTrue(
            "写入来自 AppModule.apiClient 的生产回调，而非页面改调 on10405 代办",
            graph.writeStacks.single().any {
                it.className.startsWith(AppModule::class.java.name) && it.methodName.contains("apiClient")
            },
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
                // 页面条件在主线程变更；再排空生产 Handler，包含 ApiClient 的 onForceUpdateChecked。
                shadowOf(Looper.getMainLooper()).idle()
                compose.waitForIdle()
                return
            }
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        assertTrue(label, condition())
    }

    private enum class Target(
        val path: String,
    ) {
        HOME(SingleSettle10405Wire.HOME),
        ITEMS(SingleSettle10405Wire.ITEMS),
        ME(SingleSettle10405Wire.ME),
    }
}
