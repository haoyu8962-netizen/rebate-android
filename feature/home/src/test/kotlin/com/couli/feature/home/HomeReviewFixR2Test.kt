package com.couli.feature.home

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.LocalProductImageLoader
import com.couli.core.home.HomeErrors
import com.couli.core.home.HomeFailure
import com.couli.core.home.HomeLkgStore
import com.couli.core.network.ApiResult
import com.couli.core.network.ErrorEffect
import com.couli.core.network.ErrorEffectKind
import com.couli.core.network.ReplayMode
import java.io.File
import java.time.Duration
import java.util.concurrent.LinkedBlockingDeque
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 第 2 轮评审修复（android-review-r2.md R2-01、R2-03、R2-04；R2-02 在 app 模块的 AppHomeIntegrationTest）：每条按评审写的触发顺序构造。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h4000dp-xhdpi")
@Suppress("LargeClass", "TooManyFunctions")
class HomeReviewFixR2Test {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Volatile private var now = HomeTestKit.NOON
    private val clock: () -> Long = { now }

    private fun idle() {
        repeat(ROUNDS) {
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.waitForIdle()
        }
    }

    /** 时钟与主线程计时一起推进（共享禁用期的到期协程按主线程延时走）。 */
    private fun pass(ms: Long) {
        now += ms
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        idle()
    }

    private fun show(controller: HomeController) {
        composeRule.setContent {
            CompositionLocalProvider(LocalProductImageLoader provides HomeTestKit.images) {
                CouliTheme { HomeSduiScreen(controller = controller, signals = FakeSignals()) }
            }
        }
        idle()
    }

    /** StateLoadFailed 的【重试】按钮（ErrorState 里唯一可点的按钮）。 */
    private fun assertRetryEnabled(enabled: Boolean) {
        val retry =
            composeRule.onNode(
                androidx.compose.ui.test
                    .hasText(
                        com.couli.core.home.HomeDraftTexts
                            .text(com.couli.core.home.HomeDraftTexts.RETRY),
                    ) and
                    androidx.compose.ui.test
                        .hasClickAction(),
                useUnmergedTree = false,
            )
        if (enabled) retry.assertIsEnabled() else retry.assertIsNotEnabled()
    }

    private fun feed(controller: HomeController): HomeCardUi.Feed? =
        controller.state.value.cards
            .filterIsInstance<HomeCardUi.Feed>()
            .singleOrNull()

    // ---------------------------------------------------------------- R2-01

    /** 只排队、由测试决定执行顺序的 IO（模拟两次写入逆序拿到存储锁）。 */
    private class QueueDispatcher : CoroutineDispatcher() {
        val queue = LinkedBlockingDeque<Runnable>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            queue.add(block)
        }
    }

    /**
     * 同一控制器先接受冷启动结果 P1、再接受下拉刷新结果 P2，两次写入都排进 IO → P2 先执行（先拿到锁）、P1 后执行：P1 在锁内因接受顺序更早被拒，
     * 缓存仍是 P2；下一次冷启动显示 P2。
     */
    @Test
    fun r2_01_twoAcceptedLkgWritesTakingLockInReverseKeepNewestPage() {
        val root = File(context.noBackupFilesDir, "r2-lkg-${System.nanoTime()}")
        val store = HomeLkgStore(root, MemoryFlag())
        val io = QueueDispatcher()
        val port = FakePort(page = { HomeTestKit.ok(page(31, section("p1_act", "activity_entry", ds = ACTIVITY_DS))) })
        val controller = HomeController(FakeHost.IDENTITY, testDeps(port = port, lkg = store, io = io, clock = clock))
        controller.start()
        // 冷启动读 LKG（没有）
        io.queue.removeFirst().run()
        idle()
        assertEquals("network", controller.state.value.showing)
        assertEquals("P1 的写入已排队", 1, io.queue.size)
        port.page = { HomeTestKit.ok(page(32, section("p2_act", "activity_entry", ds = ACTIVITY_DS))) }
        controller.refresh(controller.state.value.render)
        idle()
        assertTrue(
            controller.state.value.cards
                .any { it.id == "p2_act" },
        )
        assertEquals("P2 的写入也已排队", 2, io.queue.size)
        // 逆序：P2 先拿到锁，P1 后拿到
        io.queue.removeLast().run()
        io.queue.removeLast().run()
        idle()
        val cached = store.read(FakeHost.IDENTITY.scope)
        assertNotNull(cached)
        assertTrue("缓存退回了 P1：$cached", cached!!.contains("p2_act"))
        assertFalse(cached.contains("p1_act"))
    }

    // ---------------------------------------------------------------- R2-03

    /**
     * 信息流已有数据且 hasMore → 公告请求返回 42901，共享禁用期开始 → 用户停在底部（触底项在组合中），loadMore 被禁用期拦下、不发 →
     * 原地等待：期间不发翻页；到期后触底项按原渲染票据再试，加载下一页。被 42901 拦下的公告首屏也在到期后重做。
     */
    @Test
    fun r2_03_feedBottomDuringRateLimitWaitsInPlaceAndLoadsOnExpiry() {
        val port =
            FakePort(
                page = {
                    HomeTestKit.ok(
                        page(31, section("feed", "feed_infinite", props = null, ds = FEED_DS), noticeApi("nb")),
                    )
                },
            )
        val articles = FakeArticles(reply = { failure(RATE_LIMITED) })
        val controller = HomeController(FakeHost.IDENTITY, testDeps(port = port, api = articles, clock = clock))
        controller.start()
        idle()
        assertEquals(listOf("feed|"), port.itemCalls)
        show(controller)
        // 触底项已在组合中，但禁用期内不发
        composeRule.onNodeWithTag(HomeTags.FEED_MORE, useUnmergedTree = true).assertExists()
        assertTrue(controller.state.value.rateLimited)
        pass(HALF_WAIT)
        assertEquals(listOf("feed|"), port.itemCalls)
        assertEquals(4, feed(controller)!!.items.size)
        // 到期：不用滚动、不用点，原地恢复
        articles.reply = { HomeTestKit.ok(HomeTestKit.notices) }
        pass(RETRY_AFTER_MS - HALF_WAIT)
        assertFalse(controller.state.value.rateLimited)
        assertEquals(listOf("feed|", "feed|p2"), port.itemCalls)
        assertEquals(6, feed(controller)!!.items.size)
        assertEquals("公告首屏到期重做", 2, articles.calls.size)
        assertTrue(
            controller.state.value.cards
                .any { it is HomeCardUi.Notice },
        )
    }

    /**
     * 首屏的商品池与信息流被本地限流拦下（没有发出）、商品池被服务端 42901 拦下：卡先隐藏、不算失败；到期后控制器重做这些首屏加载。
     */
    @Test
    fun r2_03_gatedFirstLoadsOfPoolsAndFeedResumeOnExpiry() {
        val replies = ArrayDeque(listOf(failure(RATE_LIMITED)))
        val port =
            FakePort(
                page = {
                    HomeTestKit.ok(
                        page(
                            31,
                            section("g1", "product_grid", props = """{"columns":2}""", ds = POOL_DS),
                            section("g2", "product_grid", props = """{"columns":2}""", ds = POOL_DS),
                            section("feed", "feed_infinite", props = null, ds = FEED_DS),
                        ),
                    )
                },
            ).apply {
                items = { section, cursor ->
                    if (section == "g1") replies.removeFirstOrNull() ?: HomeTestKit.items(0, 2, null) else HomeTestKit.items(0, 2, cursor)
                }
            }
        val controller = HomeController(FakeHost.IDENTITY, testDeps(port = port, clock = clock))
        controller.start()
        idle()
        // g1 收到服务端 42901 → g2、feed 在发送前被拦下（没有发出）
        assertEquals(listOf("g1|"), port.itemCalls)
        assertTrue(
            controller.state.value.cards
                .none { it.id in setOf("g1", "g2", "feed") },
        )
        assertNull("被本地限流拦下不算失败", controller.state.value.sectionErrors["g2"])
        pass(RETRY_AFTER_MS)
        assertEquals(listOf("feed|", "g1|", "g1|", "g2|"), port.itemCalls.sorted())
        assertTrue(
            controller.state.value.cards
                .map { it.id }
                .containsAll(listOf("g1", "g2", "feed")),
        )
    }

    /**
     * 翻页收到服务端 42901（带 trace_id）：底部显示 42901 文案（不带编号），【重试】在禁用期内置灰、到期恢复可点；点了加载下一页，已有商品保留。
     */
    @Test
    fun r2_03_r2_04_feedRetryFollowsRateLimitAndShows42901Text() {
        val port =
            FakePort(page = { HomeTestKit.ok(page(31, section("feed", "feed_infinite", props = null, ds = FEED_DS))) }).apply {
                items = { _, cursor -> if (cursor == null) HomeTestKit.items(0, 4, "p2") else failure(RATE_LIMITED, "trace-test-RL4290") }
            }
        val controller = HomeController(FakeHost.IDENTITY, testDeps(port = port, clock = clock))
        controller.start()
        show(controller)
        assertEquals(listOf("feed|", "feed|p2"), port.itemCalls)
        val error = composeRule.onNodeWithTag(HomeTags.FEED_ERROR, useUnmergedTree = true)
        error.assertExists()
        assertEquals(HomeErrors.message(HomeErrors.RATE_LIMITED, null), feed(controller)!!.error)
        assertEquals(HomeFailure(RATE_LIMITED, HomeErrors.RATE_LIMITED, null), controller.state.value.sectionErrors["feed"])
        composeRule.onNodeWithTag(HomeTags.FEED_RETRY, useUnmergedTree = true).assertIsNotEnabled()
        assertEquals(4, feed(controller)!!.items.size)
        pass(RETRY_AFTER_MS)
        composeRule.onNodeWithTag(HomeTags.FEED_RETRY, useUnmergedTree = true).assertIsEnabled()
        port.items = { _, _ -> HomeTestKit.items(4, 2, null) }
        composeRule.onNodeWithTag(HomeTags.FEED_RETRY, useUnmergedTree = true).performClick()
        idle()
        assertEquals(6, feed(controller)!!.items.size)
        assertNull(feed(controller)!!.error)
    }

    /** StateLoadFailed 的【重试】随禁用期置灰、到期恢复；文案是触发它的实际失败（R2-04）。 */
    @Test
    fun r2_03_r2_04_loadFailedRetryFollowsRateLimitAndShowsActualFailure() {
        val port = FakePort(page = { failure(RATE_LIMITED, "trace-test-LF4290") })
        val timers = ManualTimers(clock)
        val controller = HomeController(FakeHost.IDENTITY, testDeps(port = port, clock = clock, timers = timers, bundled = { null }))
        controller.start()
        idle()
        // 没有 LKG：等满 1.5 秒 → 包内默认也解码失败 → StateLoadFailed
        pass(COLD_WAIT_MS)
        timers.fire(now)
        show(controller)
        assertTrue(controller.state.value.loadFailed)
        assertEquals(HomeErrors.message(HomeErrors.RATE_LIMITED, null), controller.state.value.loadFailedMessage)
        assertTrue(controller.state.value.rateLimited)
        composeRule.onNodeWithTag(HomeTags.LOAD_FAILED, useUnmergedTree = true).assertExists()
        assertRetryEnabled(false)
        pass(RETRY_AFTER_MS - COLD_WAIT_MS)
        assertFalse(controller.state.value.rateLimited)
        assertRetryEnabled(true)
        // 再失败一次 5xxxx：文案带 trace6
        port.page = { failure(SERVER_ERROR, "trace-test-LF0001") }
        controller.retryLoadFailed(controller.state.value.render)
        idle()
        pass(COLD_WAIT_MS)
        timers.fire(now)
        idle()
        assertTrue(controller.state.value.loadFailed)
        assertTrue(
            controller.state.value.loadFailedMessage!!
                .contains("LF0001"),
        )
    }

    // ---------------------------------------------------------------- R2-04

    /**
     * listSectionItems 翻页返回 50001、trace_id 末六位 ABC123：底部经统一错误消息函数显示对应文案与 ABC123，已有商品与【重试】保留。
     */
    @Test
    fun r2_04_feedPageFailure5xxxxShowsActualTextWithTrace6() {
        val failed = failure(SERVER_ERROR, "trace-test-ABC123")
        val port =
            FakePort(page = { HomeTestKit.ok(page(31, section("feed", "feed_infinite", props = null, ds = FEED_DS))) }).apply {
                items = { _, cursor -> if (cursor == null) HomeTestKit.items(0, 4, "p2") else failed }
            }
        val controller = HomeController(FakeHost.IDENTITY, testDeps(port = port, clock = clock))
        controller.start()
        show(controller)
        val expected = HomeErrors.message(HomeErrors.keyOf(failed), "trace-test-ABC123")
        assertTrue(expected.contains("ABC123"))
        assertEquals(expected, feed(controller)!!.error)
        assertEquals(
            1,
            composeRule
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasText(expected),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
                .size,
        )
        composeRule.onNodeWithTag(HomeTags.FEED_RETRY, useUnmergedTree = true).assertIsEnabled()
        assertEquals(4, feed(controller)!!.items.size)
        assertEquals(HomeFailure(SERVER_ERROR, HomeErrors.keyOf(failed), "trace-test-ABC123"), controller.state.value.sectionErrors["feed"])
    }

    /** 商品池与公告失败：按 G03 / G10 隐藏卡片、不提示，但保留实际错误码与 trace_id（经统一文案函数可得带 trace6 的文案）。 */
    @Test
    fun r2_04_poolAndNoticeFailuresKeepActualCodeAndTrace() {
        val port =
            FakePort(
                page = {
                    HomeTestKit.ok(page(31, section("g", "product_grid", props = """{"columns":2}""", ds = POOL_DS), noticeApi("nb")))
                },
            ).apply { items = { _, _ -> failure(SERVER_ERROR, "trace-test-PL0001") } }
        val articles = FakeArticles(reply = { failure(SERVER_ERROR + 2, "trace-test-NT0002") })
        val controller = HomeController(FakeHost.IDENTITY, testDeps(port = port, api = articles, clock = clock))
        controller.start()
        idle()
        val errors = controller.state.value.sectionErrors
        assertEquals(SERVER_ERROR, errors["g"]!!.code)
        assertEquals("trace-test-PL0001", errors["g"]!!.traceId)
        assertTrue(errors["g"]!!.message.contains("PL0001"))
        assertEquals(SERVER_ERROR + 2, errors["nb"]!!.code)
        assertTrue(errors["nb"]!!.message.contains("NT0002"))
        assertTrue(
            controller.state.value.cards
                .isEmpty(),
        )
        // 下拉刷新失败的提示也经同一函数
        port.page = { failure(SERVER_ERROR, "trace-test-PR0001") }
        controller.refresh(controller.state.value.render)
        idle()
        assertEquals(
            HomeErrors.failure(failure(SERVER_ERROR, "trace-test-PR0001")).message,
            controller.state.value.toast
                ?.message,
        )
    }

    private class MemoryFlag : HomeLkgStore.PendingFlag {
        private val set = mutableSetOf<String>()

        override fun entries(): Set<String> = synchronized(set) { set.toSet() }

        override fun add(entry: String): Boolean = synchronized(set) { set.add(entry).let { true } }

        override fun remove(entry: String): Boolean = synchronized(set) { set.remove(entry).let { true } }
    }

    private companion object {
        const val ROUNDS = 4
        const val RATE_LIMITED = 42901
        const val SERVER_ERROR = 50001
        const val RETRY_AFTER_MS = 5_000L
        const val HALF_WAIT = 2_500L
        const val COLD_WAIT_MS = 1_500L
        const val ACTIVITY_DS = """{"kind":"static","items":[{"title":"测试活动","action":{"route":"Help"}}]}"""
        const val POOL_DS = """{"kind":"product_pool","pool_id":"p","limit":20}"""
        const val FEED_DS = """{"kind":"union_material","platforms":["taobao","jd","pdd"],"mix":"interleave"}"""

        fun section(
            id: String,
            type: String,
            props: String? = "{}",
            ds: String,
        ): JsonObject {
            val fields = linkedMapOf<String, kotlinx.serialization.json.JsonElement>()
            fields["id"] = JsonPrimitive(id)
            fields["type"] = JsonPrimitive(type)
            fields["version"] = JsonPrimitive(1)
            props?.let { fields["props"] = Json.parseToJsonElement(it) }
            fields["data_source"] = Json.parseToJsonElement(ds)
            return JsonObject(fields)
        }

        fun noticeApi(id: String): JsonObject = section(id, "notice_bar", props = """{"mode":"static"}""", ds = """{"kind":"api"}""")

        fun page(
            version: Int,
            vararg sections: JsonObject,
        ): JsonObject =
            JsonObject(
                mapOf(
                    "page_key" to JsonPrimitive("home"),
                    "version" to JsonPrimitive(version),
                    "sections" to JsonArray(sections.toList()),
                ),
            )

        fun failure(
            code: Int,
            trace: String = "trace-test-000777",
        ): ApiResult =
            ApiResult.Failure(
                code,
                "",
                JsonObject(emptyMap()),
                trace,
                ErrorEffect(code, ErrorEffectKind.TOAST, ReplayMode.NONE, registerPending = false),
                null,
            )
    }
}
