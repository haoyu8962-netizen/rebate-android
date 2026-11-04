package com.couli.feature.home

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.home.HomeCall
import com.couli.core.home.HomeContractPort
import com.couli.core.home.HomeErrors
import com.couli.core.home.HomeIdentity
import com.couli.core.home.HomeLevel
import com.couli.core.home.HomeLkgStore
import com.couli.core.home.HomeShowing
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.ErrorEffect
import com.couli.core.network.ErrorEffectKind
import com.couli.core.network.LocalRejectReason
import com.couli.core.network.ReplayMode
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
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
 * FU-X-HOME-PAGE-API-android 测试 v1（独立测试作者）：首页控制器在契约页面上的取数调度与错误映射（brief §3「纯逻辑」；03 §6.2 第 3、4 条；D10-607 D4–D6）。
 *
 * - 卡片自取数：product_pool（scroll / grid）与 union_material（feed）各发一次 listSectionItems（首屏不带游标）；静态卡、公告 api 卡不走它
 *   （公告仍是 listArticles）；商品池卡只取一页（D6：有 next_cursor 也不自动翻页）；信息流触底带回 next_cursor。
 * - D4：只在卡片还没有任何商品时，对「items 空但带游标」自己接着取，最多 3 次（首取 1 次 + 接着取 3 次）；已有商品时不自己接着取。
 * - D5：页面 30701 → 有 LKG 显示 LKG、没有则 1.5 秒后包内默认，不新造空态、不写 LKG、冷启动不提示、不进强更；下拉刷新失败沿用现有提示（HomeErrors）。
 * - 网络失败沿用现有错误态；单卡 30701 / 断网只影响这一张。
 * 端口用只记录的替身（底版 HomeContractPort 接口，底版能编译、按行为红）；端口自身的请求形状在 core:home HomePageApiPortTest、真实传输在 app 的 testDebug。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
@Suppress("LargeClass", "TooManyFunctions")
class HomePageApiControllerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** 按「section|cursor」回答；未登记的组合按断网（不让未预期的请求悄悄成功）。只记录、不联网。 */
    private class ScriptPort(
        var page: () -> ApiResult = { HomeTestKit.ok(contractPage()) },
    ) : HomeContractPort {
        val pageCalls = mutableListOf<HomeCall>()
        val itemCalls = mutableListOf<String>()
        val itemAnonymous = mutableListOf<Boolean>()
        val replies = mutableMapOf<String, ApiResult>()

        override suspend fun getPage(
            pageKey: String,
            call: HomeCall,
        ): ApiResult {
            if (!call.allowed()) return ApiResult.LocalReject(LocalRejectReason.CALL_CANCELLED)
            assertEquals("home", pageKey)
            pageCalls += call
            return page()
        }

        override suspend fun listSectionItems(
            pageKey: String,
            sectionId: String,
            cursor: String?,
            call: HomeCall,
        ): ApiResult {
            if (!call.allowed()) return ApiResult.LocalReject(LocalRejectReason.CALL_CANCELLED)
            assertEquals("home", pageKey)
            val key = "$sectionId|${cursor.orEmpty()}"
            itemCalls += key
            itemAnonymous += call.anonymous
            return replies[key] ?: OFFLINE
        }

        fun callsFor(section: String): List<String> = itemCalls.filter { it.startsWith("$section|") }
    }

    private class Rig(
        val port: ScriptPort = ScriptPort(),
        val host: FakeHost = FakeHost(),
        val articles: FakeArticles = FakeArticles(),
        val lkg: HomeLkgStore? = null,
        identity: HomeIdentity = FakeHost.IDENTITY,
    ) {
        var now = HomeTestKit.NOON
        val timers = ManualTimers { now }
        val controller =
            HomeController(
                identity,
                testDeps(host = host, port = port, api = articles, lkg = lkg, timers = timers, clock = { now }),
            )

        fun fire1500() {
            now += HomeController.COLD_WAIT_MS
            timers.fire(now)
        }

        val state: HomeUiState get() = controller.state.value

        fun card(id: String): HomeCardUi? = state.cards.firstOrNull { it.id == id }

        fun feed(): HomeCardUi.Feed? = card("feed") as? HomeCardUi.Feed
    }

    private fun defaultReplies(port: ScriptPort) {
        port.replies["demo-today|"] = HomeTestKit.items(0, 3, "today-p2")
        port.replies["demo-grid|"] = HomeTestKit.items(10, 4, "grid-p2")
        port.replies["feed|"] = HomeTestKit.items(20, 4, "f2")
        port.replies["feed|f2"] = HomeTestKit.items(24, 2, null)
    }

    private fun store(): HomeLkgStore = HomeLkgStore(File(context.noBackupFilesDir, "homeapi-${System.nanoTime()}"), MemoryFlag())

    // ---------------------------------------------------------------- 取数调度

    @Test
    fun e01ColdStartFetchesThePageThenEachProductSourceCardItself() {
        val rig = Rig()
        defaultReplies(rig.port)
        rig.controller.start()
        idle()
        assertEquals("取页恰好 1 次", 1, rig.port.pageCalls.size)
        assertEquals(
            "只有商品池两张卡与信息流各取数一次、首屏不带游标，按服务端顺序；静态卡、公告 api 卡不走 listPageSectionItems",
            listOf("demo-today|", "demo-grid|", "feed|"),
            rig.port.itemCalls,
        )
        assertEquals("公告 api 卡仍走 listArticles 一次", listOf("listArticles"), rig.articles.calls.map { it.operationId })
        assertEquals(HomeShowing.NETWORK, rig.state.showing)
        assertEquals(41, rig.state.pageVersion)
        val scroll = rig.card("demo-today") as? HomeCardUi.ProductScroll
        val grid = rig.card("demo-grid") as? HomeCardUi.ProductGrid
        assertNotNull("商品池横滑卡显示", scroll)
        assertNotNull("商品池宫格卡显示", grid)
        assertEquals("按服务端顺序、不截断", listOf("test:p0", "test:p1", "test:p2"), scroll!!.items.map { it.key })
        assertEquals(4, grid!!.items.size)
        assertEquals("信息流显示（契约 channels 形状）", 4, rig.feed()?.items?.size)
        assertEquals("卡片顺序照服务端", listOf("acts", "notice", "demo-today", "demo-grid", "feed"), rig.state.cards.map { it.id })
        assertNull("无提示", rig.state.toast)
        assertEquals(0, rig.host.forced)
    }

    @Test
    fun e02ProductPoolCardsShowOnePageAndNeverPageOn() {
        val rig = Rig()
        defaultReplies(rig.port)
        rig.controller.start()
        idle()
        rig.fire1500()
        idle()
        assertEquals("商品池横滑只取一页（D6），有 next_cursor 也不翻页", listOf("demo-today|"), rig.port.callsFor("demo-today"))
        assertEquals("商品池宫格只取一页（D6）", listOf("demo-grid|"), rig.port.callsFor("demo-grid"))
    }

    @Test
    fun e03FeedPagesOnWithTheReturnedCursorUntilItIsNull() {
        val rig = Rig()
        defaultReplies(rig.port)
        rig.controller.start()
        idle()
        val first = requireNotNull(rig.feed()) { "信息流应显示" }
        assertTrue(first.hasMore)
        rig.controller.loadMore(first.ticket)
        idle()
        assertEquals("触底带回 next_cursor（原样）", listOf("feed|", "feed|f2"), rig.port.callsFor("feed"))
        val second = requireNotNull(rig.feed())
        assertEquals(6, second.items.size)
        assertFalse("next_cursor 为 null 后没有更多", second.hasMore)
        rig.controller.loadMore(second.ticket)
        idle()
        assertEquals("没有游标不再请求", 2, rig.port.callsFor("feed").size)
    }

    @Test
    fun e04EmptyFirstFeedPageWithACursorFetchesOnByItself() {
        val rig = Rig(ScriptPort { HomeTestKit.ok(feedOnlyPage()) })
        rig.port.replies["feed|"] = empty("c2")
        rig.port.replies["feed|c2"] = HomeTestKit.items(0, 3, null)
        rig.controller.start()
        idle()
        assertEquals("契约：items 空而带游标表示接着取（不等触底：卡片没显示，用户无从触底）", listOf("feed|", "feed|c2"), rig.port.itemCalls)
        assertEquals("取到后信息流显示", 3, rig.feed()?.items?.size)
    }

    @Test
    fun e05EmptyPagesWithACursorFetchOnAtMostThreeTimes() {
        val rig = Rig(ScriptPort { HomeTestKit.ok(feedOnlyPage()) })
        rig.port.replies["feed|"] = empty("c2")
        rig.port.replies["feed|c2"] = empty("c3")
        rig.port.replies["feed|c3"] = empty("c4")
        rig.port.replies["feed|c4"] = empty("c5")
        rig.port.replies["feed|c5"] = HomeTestKit.items(0, 3, null)
        rig.controller.start()
        idle()
        rig.fire1500()
        idle()
        assertEquals("D4：首取 1 次 + 接着取恰好 3 次，第 4 次（c5）不取", listOf("feed|", "feed|c2", "feed|c3", "feed|c4"), rig.port.itemCalls)
        assertNull("仍没有商品：信息流卡隐藏", rig.feed())
        assertNull("不提示", rig.state.toast)
        assertEquals(HomeShowing.NETWORK, rig.state.showing)
    }

    @Test
    fun e06EmptyLaterPageWithACursorWaitsForLoadMoreWhenItemsAreShown() {
        val rig = Rig(ScriptPort { HomeTestKit.ok(feedOnlyPage()) })
        rig.port.replies["feed|"] = HomeTestKit.items(0, 2, "c2")
        rig.port.replies["feed|c2"] = empty("c3")
        rig.port.replies["feed|c3"] = HomeTestKit.items(2, 2, null)
        rig.controller.start()
        idle()
        rig.controller.loadMore(requireNotNull(rig.feed()).ticket)
        idle()
        assertEquals("已有商品：空页带游标不自己接着取（D4 只在还没有商品时）", listOf("feed|", "feed|c2"), rig.port.itemCalls)
        val kept = requireNotNull(rig.feed())
        assertEquals("已显示的商品保留", 2, kept.items.size)
        assertTrue("仍有更多（游标 c3）", kept.hasMore)
        rig.controller.loadMore(kept.ticket)
        idle()
        assertEquals("下一次触底才取 c3", listOf("feed|", "feed|c2", "feed|c3"), rig.port.itemCalls)
        assertEquals(4, rig.feed()?.items?.size)
    }

    @Test
    fun e07EmptyFirstProductPoolPageWithACursorAlsoFetchesOnThenStopsAtOnePage() {
        val rig = Rig(ScriptPort { HomeTestKit.ok(scrollOnlyPage()) })
        rig.port.replies["demo-today|"] = empty("c2")
        rig.port.replies["demo-today|c2"] = HomeTestKit.items(0, 2, "c3")
        rig.controller.start()
        idle()
        rig.fire1500()
        idle()
        assertEquals("D4 同样适用于商品池卡；拿到一页商品后不再翻页（D6）", listOf("demo-today|", "demo-today|c2"), rig.port.itemCalls)
        assertEquals(2, (rig.card("demo-today") as? HomeCardUi.ProductScroll)?.items?.size)
    }

    @Test
    fun e08EmptySourcesHideOnlyTheirOwnCard() {
        val rig = Rig()
        defaultReplies(rig.port)
        rig.port.replies["demo-grid|"] = empty(null)
        rig.port.replies["feed|"] = empty(null)
        rig.controller.start()
        idle()
        assertNull("宫格空：隐藏", rig.card("demo-grid"))
        assertNull("信息流空且没有游标：隐藏", rig.feed())
        assertNotNull("别的卡不受影响", rig.card("demo-today"))
        assertNotNull(rig.card("acts"))
        assertEquals("空且没有游标不接着取", listOf("demo-today|", "demo-grid|", "feed|"), rig.port.itemCalls)
        assertNull(rig.state.toast)
    }

    @Test
    fun e09OneCard30701OrNetworkFailureDoesNotBreakThePage() {
        val rig = Rig()
        defaultReplies(rig.port)
        rig.port.replies["demo-grid|"] = failure(30701)
        rig.port.replies["feed|"] = OFFLINE
        rig.controller.start()
        idle()
        assertEquals("整页照常（网络版）", HomeShowing.NETWORK, rig.state.showing)
        assertNull("单卡 30701：只隐藏这一张", rig.card("demo-grid"))
        assertNotNull("别的卡照常", rig.card("demo-today"))
        assertNull("单卡失败不弹提示", rig.state.toast)
        assertEquals("单卡 30701 不当强更 / 崩溃", 0, rig.host.forced)
        assertFalse(rig.state.loadFailed)
    }

    // ---------------------------------------------------------------- 页面级错误（D5、网络错误）

    @Test
    fun e10Page30701ColdStartWithoutLkgShowsTheBundledDefaultQuietly() {
        val lkg = store()
        val rig = Rig(ScriptPort { failure(30701) }, lkg = lkg)
        rig.controller.start()
        idle()
        assertEquals("1.5 秒内先不显示（不新造空态）", HomeShowing.NONE, rig.state.showing)
        assertTrue(rig.state.cards.isEmpty())
        rig.fire1500()
        idle()
        assertEquals("30701 无 LKG → 包内默认（D5）", HomeShowing.DEFAULT, rig.state.showing)
        assertEquals("default_icons", rig.card("default_icons")?.id)
        assertTrue("包内默认没有商品卡：不取卡", rig.port.itemCalls.isEmpty())
        assertNull("30701 不写 LKG", lkg.read(FakeHost.IDENTITY.scope))
        assertNull("冷启动不提示", rig.state.toast)
        assertEquals("30701 不当强更", 0, rig.host.forced)
        assertFalse("不进 StateLoadFailed", rig.state.loadFailed)
    }

    @Test
    fun e11Page30701KeepsTheLkgAndPullRefreshReportsOnceWithTheExistingMessage() {
        val lkg = store()
        val lkgText = contractPage(version = 40).toString()
        assertTrue(lkg.write(lkg.ticket(FakeHost.IDENTITY.scope), lkgText))
        val rig = Rig(ScriptPort { failure(30701) }, lkg = lkg)
        defaultReplies(rig.port)
        rig.controller.start()
        idle()
        assertEquals("有 LKG：照显 LKG（D5）", HomeShowing.LKG, rig.state.showing)
        assertEquals(40, rig.state.pageVersion)
        assertNull("冷启动 30701 不提示", rig.state.toast)
        rig.controller.refresh(rig.state.render)
        idle()
        assertEquals("下拉刷新 30701：保留当前屏", HomeShowing.LKG, rig.state.showing)
        assertEquals(40, rig.state.pageVersion)
        assertEquals("提示一次，文案沿用现有错误映射（不新造）", HomeErrors.failure(failure(30701)).message, rig.state.toast?.message)
        assertFalse("刷新结束", rig.state.refreshing)
        assertEquals("不改写 LKG", lkgText, lkg.read(FakeHost.IDENTITY.scope))
        assertEquals(0, rig.host.forced)
        assertEquals(2, rig.port.pageCalls.size)
    }

    @Test
    fun e12NetworkFailureUsesTheExistingFallbackAndPullRefreshReportsOnce() {
        val rig = Rig(ScriptPort { OFFLINE })
        rig.controller.start()
        idle()
        assertNull(rig.state.toast)
        rig.fire1500()
        idle()
        assertEquals("断网冷启动：1.5 秒后包内默认，不提示", HomeShowing.DEFAULT, rig.state.showing)
        assertNull(rig.state.toast)
        rig.controller.refresh(rig.state.render)
        idle()
        assertEquals(HomeShowing.DEFAULT, rig.state.showing)
        assertEquals("下拉刷新断网提示一次（现有通用错误文案）", HomeErrors.failure(OFFLINE).message, rig.state.toast?.message)
        assertFalse(rig.state.refreshing)
    }

    @Test
    fun e13PullRefreshReplacesWithTheNewPageAndRefetchesItsCards() {
        val rig = Rig()
        defaultReplies(rig.port)
        rig.controller.start()
        idle()
        rig.port.page = { HomeTestKit.ok(scrollOnlyPage(version = 42)) }
        rig.port.replies["demo-today|"] = HomeTestKit.items(30, 2, null)
        rig.controller.refresh(rig.state.render)
        idle()
        assertEquals(42, rig.state.pageVersion)
        assertEquals("换成新页面并按新页面重新取卡", listOf("demo-today|", "demo-grid|", "feed|", "demo-today|"), rig.port.itemCalls)
        assertEquals(listOf("test:p30", "test:p31"), (rig.card("demo-today") as? HomeCardUi.ProductScroll)?.items?.map { it.key })
        assertNull(rig.feed())
    }

    @Test
    fun e14BasicModeHomeStillFetchesThePageAndCardsAnonymously() {
        val basic = HomeIdentity(HomeLevel.BASIC, null, 0L, CallEpochs(0, 0), "test")
        val host = FakeHost(basic)
        val rig = Rig(host = host, identity = basic)
        defaultReplies(rig.port)
        rig.controller.start()
        idle()
        assertEquals("基本模式照样取页", listOf(true), rig.port.pageCalls.map { it.anonymous })
        assertEquals("基本模式照样取卡", listOf("demo-today|", "demo-grid|", "feed|"), rig.port.itemCalls)
        assertTrue("全部匿名", rig.port.itemAnonymous.all { it })
        assertNotNull(rig.card("demo-today"))
    }

    /** 目录外的待清理标记（内存）。 */
    private class MemoryFlag : HomeLkgStore.PendingFlag {
        private val set = mutableSetOf<String>()

        override fun entries(): Set<String> = set.toSet()

        override fun add(entry: String): Boolean = set.add(entry).let { true }

        override fun remove(entry: String): Boolean = set.remove(entry).let { true }
    }

    companion object {
        private val OFFLINE: ApiResult = ApiResult.TransportFailure("disconnect", null, null, false)

        private fun failure(code: Int): ApiResult =
            ApiResult.Failure(
                code,
                "",
                JsonObject(emptyMap()),
                "trace-homeapi-$code",
                ErrorEffect(code, ErrorEffectKind.TOAST, ReplayMode.NONE, false),
                null,
            )

        private fun empty(next: String?): ApiResult =
            HomeTestKit.ok(JsonObject(mapOf("items" to JsonArray(emptyList()), "next_cursor" to (next?.let(::JsonPrimitive) ?: JsonNull))))

        private const val ACTS =
            """{"id":"acts","type":"activity_entry","version":1,"props":{},""" +
                """"data_source":{"kind":"static","items":[{"title":"t","action":{"route":"Help"}}]}}"""
        private const val NOTICE =
            """{"id":"notice","type":"notice_bar","version":1,"props":{"mode":"scroll"},"data_source":{"kind":"api"}}"""
        private const val SCROLL =
            """{"id":"demo-today","type":"product_scroll","version":1,"props":{"title":"today"},""" +
                """"data_source":{"kind":"product_pool","limit":10,"pool_id":"01a1a33a-f20f-7c9d-84c7-28db459b845f"}}"""
        private const val GRID =
            """{"id":"demo-grid","type":"product_grid","version":1,"props":{"title":"grid","columns":2},""" +
                """"data_source":{"kind":"product_pool","limit":20,"pool_id":"01a1a33a-f20f-7c9d-84c7-28db459b845f"}}"""

        /** 契约形状的信息流（只有 channels，00deded0ef6f home-schema.json）。 */
        private const val FEED =
            """{"id":"feed","type":"feed_infinite","version":1,"data_source":{"kind":"union_material",""" +
                """"channels":[{"platform":"taobao","channel_id":"demo_channel_1"},{"platform":"jd","channel_id":"demo_channel_2"}],""" +
                """"mix":"interleave"}}"""

        private fun page(
            version: Int,
            vararg sections: String,
        ): JsonObject =
            Json
                .parseToJsonElement("""{"page_key":"home","version":$version,"ttl_sec":300,"sections":[${sections.joinToString(",")}]}""")
                as JsonObject

        fun contractPage(version: Int = 41): JsonObject = page(version, ACTS, NOTICE, SCROLL, GRID, FEED)

        fun feedOnlyPage(): JsonObject = page(43, FEED)

        fun scrollOnlyPage(version: Int = 44): JsonObject = page(version, SCROLL)
    }
}
