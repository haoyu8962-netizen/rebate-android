package com.couli.feature.home

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.home.HomeCall
import com.couli.core.home.HomeContractPort
import com.couli.core.home.HomeLkgScope
import com.couli.core.home.HomeLkgStore
import com.couli.core.network.ApiResult
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 首页控制器的票据与世代（共性第 1、2、17 条；APP-09 第 63 条：保留真实页面的旧回调，换代后再执行）、请求序号（brief §0「只收最新一次」）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class HomeControllerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun page(
        version: Int,
        id: String,
    ): JsonObject =
        JsonObject(
            mapOf(
                "page_key" to JsonPrimitive("home"),
                "version" to JsonPrimitive(version),
                "sections" to
                    JsonArray(
                        listOf(
                            kotlinx.serialization.json.Json.parseToJsonElement(
                                """{"id":"$id","type":"activity_entry","version":1,"props":{},""" +
                                    """"data_source":{"kind":"static","items":[{"title":"t","action":{"route":"Help"}}]}}""",
                            ),
                        ),
                    ),
            ),
        )

    /** 每次调用一个可控的回答。 */
    private class QueuePort : HomeContractPort {
        val replies = ArrayDeque<CompletableDeferred<ApiResult>>()

        fun next(): CompletableDeferred<ApiResult> = CompletableDeferred<ApiResult>().also { replies.addLast(it) }

        override suspend fun getPage(
            pageKey: String,
            call: HomeCall,
        ): ApiResult = replies.removeFirst().await()

        override suspend fun listSectionItems(
            pageKey: String,
            sectionId: String,
            cursor: String?,
            call: HomeCall,
        ): ApiResult = HomeTestKit.items(0, 1, null)
    }

    @Test
    fun staleTapFromOldRenderIsRejectedAfterReplacement() {
        val host = FakeHost()
        val port = FakePort(page = { HomeTestKit.ok(page(1, "first")) })
        val controller = HomeController(FakeHost.IDENTITY, testDeps(host = host, port = port))
        controller.start()
        idle()
        val oldTap =
            (
                controller.state.value.cards
                    .single() as HomeCardUi.Activity
            ).cells.single().tap
        port.page = { HomeTestKit.ok(page(2, "second")) }
        controller.refresh(controller.state.value.render)
        idle()
        assertEquals(
            "second",
            controller.state.value.cards
                .single()
                .id,
        )
        controller.open(oldTap)
        assertTrue(host.opened.isEmpty())
        // 新票据照常
        controller.open(
            (
                controller.state.value.cards
                    .single() as HomeCardUi.Activity
            ).cells.single().tap,
        )
        assertEquals(1, host.opened.size)
    }

    @Test
    fun superseededColdFetchOnlyWritesLkgAndNeverReplacesPullResult() {
        val root = File(context.noBackupFilesDir, "ctl-${System.nanoTime()}")
        val lkg = HomeLkgStore(root, InMemoryFlag())
        val port = QueuePort()
        val cold = port.next()
        val clock = { HomeTestKit.NOON }
        val timers = ManualTimers(clock)
        val controller = HomeController(FakeHost.IDENTITY, testDeps(port = port, lkg = lkg, timers = timers))
        controller.start()
        idle()
        timers.fire(HomeTestKit.NOON + 1_500)
        idle()
        val pull = port.next()
        controller.refresh(controller.state.value.render)
        pull.complete(HomeTestKit.ok(page(9, "pulled")))
        idle()
        assertEquals(
            "pulled",
            controller.state.value.cards
                .single()
                .id,
        )
        // 下拉刷新发出之前在途的冷启动拉取迟到：不替换当前屏，也不覆盖更新的 LKG
        cold.complete(HomeTestKit.ok(page(8, "cold")))
        idle()
        assertEquals(
            "pulled",
            controller.state.value.cards
                .single()
                .id,
        )
        assertTrue(lkg.read(HomeLkgScope.of("guest", null, "test"))!!.contains("pulled"))
    }

    @Test
    fun coldFetchArrivingBeforePullResultIsNotTakenAsThePullResult() {
        val port = QueuePort()
        val cold = port.next()
        val clock = { HomeTestKit.NOON }
        val timers = ManualTimers(clock)
        val controller = HomeController(FakeHost.IDENTITY, testDeps(port = port, timers = timers))
        controller.start()
        idle()
        timers.fire(HomeTestKit.NOON + 1_500)
        idle()
        assertEquals("default", controller.state.value.showing)
        controller.interacted(controller.state.value.render)
        val pull = port.next()
        controller.refresh(controller.state.value.render)
        // 冷启动拉取先到：只写 LKG，不当作下拉刷新的结果替换当前屏
        cold.complete(HomeTestKit.ok(page(8, "cold")))
        idle()
        assertEquals("default", controller.state.value.showing)
        assertTrue(controller.state.value.refreshing)
        pull.complete(HomeTestKit.ok(page(9, "pulled")))
        idle()
        assertEquals(
            "pulled",
            controller.state.value.cards
                .single()
                .id,
        )
    }

    @Test
    fun popupTicketsOnlyCloseTheirOwnInstanceAndInvalidateDropsPopupWithoutCount() {
        val prefs =
            com.couli.core.home
                .HomePrefsStore(context, "home_ctl_${System.nanoTime()}")
        val controller =
            HomeController(
                FakeHost.IDENTITY,
                testDeps(port = FakePort(page = { HomeTestKit.ok(HomeTestKit.golden("popup")) }), prefs = prefs),
            )
        controller.start()
        idle()
        val shown = assertNotNull(controller.state.value.popup).let { controller.state.value.popup!! }
        controller.closePopup(shown.ticket.copy(serial = shown.ticket.serial + 1))
        assertNotNull(controller.state.value.popup)
        // 还没真正显示（对话框未组合，没调 popupShown）就作废：不计数
        controller.invalidate()
        assertNull(controller.state.value.popup)
        assertEquals(0, prefs.shownToday("popup", HomeTestKit.NOON))
        controller.popupShown(shown.ticket)
        assertEquals(0, prefs.shownToday("popup", HomeTestKit.NOON))
    }

    @Test
    fun pullRefreshNeverSchedulesANewPopup() {
        val port = FakePort(page = { HomeTestKit.ok(page(1, "plain")) })
        val controller = HomeController(FakeHost.IDENTITY, testDeps(port = port))
        controller.start()
        idle()
        port.page = { HomeTestKit.ok(HomeTestKit.golden("popup")) }
        controller.refresh(controller.state.value.render)
        idle()
        assertNull(controller.state.value.popup)
    }

    @Test
    fun releaseWithoutPortShowsDefaultImmediatelyAndHidesPoolCards() {
        val controller =
            HomeController(
                FakeHost.IDENTITY,
                testDeps(port = null, bundled = { HomeTestKit.golden("product_grid").toString() }),
            )
        controller.start()
        idle()
        assertEquals("default", controller.state.value.showing)
        assertTrue(
            controller.state.value.cards
                .isEmpty(),
        )
        assertFalse(controller.state.value.refreshing)
    }

    private class InMemoryFlag : HomeLkgStore.PendingFlag {
        private val set = mutableSetOf<String>()

        override fun entries(): Set<String> = set.toSet()

        override fun add(entry: String): Boolean = set.add(entry).let { true }

        override fun remove(entry: String): Boolean = set.remove(entry).let { true }
    }
}
