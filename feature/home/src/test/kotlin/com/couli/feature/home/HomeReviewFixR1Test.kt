package com.couli.feature.home

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.designsystem.CouliTheme
import com.couli.core.designsystem.LocalProductImageLoader
import com.couli.core.designsystem.ProductImageLoader
import com.couli.core.home.HomeCall
import com.couli.core.home.HomeContractPort
import com.couli.core.home.HomeDraftTexts
import com.couli.core.home.HomeLkgStore
import com.couli.core.home.HomePrefsStore
import com.couli.core.network.ApiResult
import com.couli.core.network.ErrorEffect
import com.couli.core.network.ErrorEffectKind
import com.couli.core.network.ReplayMode
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 第 1 轮评审修复（android-review-r1.md R1-01…R1-08 中 feature:home 能覆盖的部分；R1-04 预览接线与 R1-09 在 app 模块的集成测试）：
 * 每条都按评审写的触发顺序构造，保留旧回调 / 旧票据再执行。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w390dp-h4000dp-xhdpi")
@Suppress("LargeClass", "TooManyFunctions")
class HomeReviewFixR1Test {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun idle() {
        repeat(ROUNDS) {
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.waitForIdle()
        }
    }

    private fun until(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(POLL_MS)
        }
        assertTrue(condition())
    }

    private fun show(
        controller: HomeController,
        signals: HomeSignals,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalProductImageLoader provides HomeTestKit.images) {
                CouliTheme { HomeSduiScreen(controller = controller, signals = signals) }
            }
        }
        idle()
    }

    // ---------------------------------------------------------------- R1-01（S0）

    /** 一次只回答一个页面请求（可控时机）。 */
    private class HeldPort : HomeContractPort {
        val reply = CompletableDeferred<ApiResult>()
        var asked = false

        override suspend fun getPage(
            pageKey: String,
            call: HomeCall,
        ): ApiResult {
            asked = true
            return reply.await()
        }

        override suspend fun listSectionItems(
            pageKey: String,
            sectionId: String,
            cursor: String?,
            call: HomeCall,
        ): ApiResult = HomeTestKit.items(0, 1, null)
    }

    /**
     * 控制器层的「旧写入排队 → 清理完成 → 旧写入继续」：A 的页面通过归属检查、写盘任务已排进 IO（IO 被占住）→ 撤回同意的 clearAll 完成 → IO 放行，
     * 旧任务执行写入。票据是构造时固定的，清理推进了世代 → 被拒，A 的 page.json 不会复活。
     */
    @Test
    fun r1_01_queuedLkgWriteAfterClearAllNeverRecreatesFile() {
        val root = File(context.noBackupFilesDir, "r1-lkg-${System.nanoTime()}")
        val store = HomeLkgStore(root, MemoryFlag())
        val executor = Executors.newSingleThreadExecutor()
        try {
            val io = executor.asCoroutineDispatcher()
            val port = HeldPort()
            val controller = HomeController(FakeHost.IDENTITY, testDeps(port = port, lkg = store, io = io))
            controller.start()
            until { port.asked }
            // 占住 IO：之后的写盘任务排在它后面
            val gate = CountDownLatch(1)
            executor.submit { gate.await(WAIT_S, TimeUnit.SECONDS) }
            port.reply.complete(HomeTestKit.ok(page(section("act", "activity_entry", ds = ACTIVITY_DS))))
            idle()
            assertEquals("network", controller.state.value.showing)
            // 撤回同意：清理在锁内推进世代并删除（此时旧写入还没执行）
            store.clearAll()
            gate.countDown()
            executor.submit {}.get(WAIT_S, TimeUnit.SECONDS)
            idle()
            val file = File(File(root, HomeLkgStore.digest(FakeHost.IDENTITY.scope)), "page.json")
            assertFalse("旧写入让 LKG 复活了", file.exists())
            assertNull(store.read(FakeHost.IDENTITY.scope))
        } finally {
            executor.shutdownNow()
        }
    }

    /** 关闭记录与弹窗计数：清理器（另一个实例）clearAll 之后，旧控制器保留的关闭回调、显示回调都不再落盘。 */
    @Test
    fun r1_01_oldNoticeCloseAndPopupCountAfterClearAllAreDropped() {
        val name = "home_r1_prefs_${System.nanoTime()}"
        val prefs = HomePrefsStore(context, name)
        val notice =
            section(
                "nb",
                "notice_bar",
                props = """{"mode":"static","items":[${noticeItem("n1", closable = true, end = null)}]}""",
                ds = """{"kind":"static"}""",
            )
        val popupPage = page(notice, popupSection("pp"))
        val controller =
            HomeController(FakeHost.IDENTITY, testDeps(port = FakePort(page = { HomeTestKit.ok(popupPage) }), prefs = prefs))
        controller.start()
        idle()
        val close =
            (
                controller.state.value.cards
                    .single() as HomeCardUi.Notice
            ).lines
                .single()
                .close!!
        val popup = controller.state.value.popup!!
        HomePrefsStore(context, name).clearAll()
        controller.closeNotice(close)
        controller.popupShown(popup.ticket)
        assertTrue(prefs.closedKeys().isEmpty())
        assertEquals(0, prefs.shownToday("pp", HomeTestKit.NOON))
    }

    // ---------------------------------------------------------------- R1-02

    @Test
    fun r1_02_listKeysAreNamespacedAndLengthEncoded() {
        val ids =
            listOf("feed", "feed#0", "feed#retry", "feed#more", "disclaimer", "debug_entry", "preview_dropped", "card:4:feed", "a:b", "")
        val keys =
            ids.flatMap { id ->
                listOf(
                    HomeListKeys.card(id),
                    HomeListKeys.feedRow(id, 0),
                    HomeListKeys.feedRow(id, 1),
                    HomeListKeys.feedRetry(id),
                    HomeListKeys.feedMore(id),
                    HomeListKeys.extra(id),
                )
            } + HomeListKeys.DISCLAIMER
        // DISCLAIMER 就是 extra("disclaimer")，其余两两不同
        assertEquals(keys.size - 1, keys.toSet().size)
        assertNotEquals(HomeListKeys.card("feed#0"), HomeListKeys.feedRow("feed", 0))
        assertNotEquals(HomeListKeys.card("disclaimer"), HomeListKeys.DISCLAIMER)
        assertNotEquals(HomeListKeys.product(0, "k#1"), HomeListKeys.product(1, "k"))
    }

    /** 合法且不同的 section id 与客户端自造的键（信息流行、页底说明）同时在可见区域：不撞键、整页照常渲染。 */
    @Test
    fun r1_02_sectionIdsCollidingWithClientKeysStillRender() {
        val target =
            page(
                section("feed#0", "activity_entry", ds = ACTIVITY_DS),
                section("disclaimer", "activity_entry", ds = ACTIVITY_DS),
                section("feed", "feed_infinite", props = null, ds = FEED_DS),
                section("grid", "product_grid", props = """{"columns":2,"title":"网格"}""", ds = POOL_DS),
            )
        val signals = FakeSignals()
        val controller = HomeController(FakeHost.IDENTITY, testDeps(signals = signals, port = FakePort(page = { HomeTestKit.ok(target) })))
        controller.start()
        show(controller, signals)
        listOf("feed#0", "disclaimer", "feed", "grid").forEach { id ->
            assertTrue("卡 $id 不在", exists(hasTestTag(HomeTags.CARD_PREFIX + id)))
        }
        assertTrue(exists(hasTestTag(HomeTags.DISCLAIMER)))
        assertTrue(exists(hasTestTag(HomeTags.tap("feed#0", 1))))
    }

    /** 同一横滑卡里服务端重复的 product_key：按位置成键，不撞键。 */
    @Test
    fun r1_02_duplicateProductKeysInScrollCardStillRender() {
        val duplicate = HomeTestKit.product(0)
        val port =
            FakePort(page = { HomeTestKit.ok(page(section("s", "product_scroll", props = """{"title":"精选"}""", ds = POOL_DS))) }).apply {
                items = { _, _ ->
                    HomeTestKit.ok(
                        JsonObject(
                            mapOf(
                                "items" to JsonArray(listOf(duplicate, duplicate)),
                                "next_cursor" to kotlinx.serialization.json.JsonNull,
                            ),
                        ),
                    )
                }
            }
        val signals = FakeSignals()
        val controller = HomeController(FakeHost.IDENTITY, testDeps(signals = signals, port = port))
        controller.start()
        show(controller, signals)
        assertTrue(exists(hasTestTag(HomeTags.tap("s", 1))))
        assertTrue(exists(hasTestTag(HomeTags.tap("s", 2))))
    }

    // ---------------------------------------------------------------- R1-03

    private fun allTypesPage(label: String?): JsonObject =
        page(
            section("b", "banner", props = """{"height_ratio":0.4,"autoplay_ms":0}""", ds = BANNER_DS, adLabel = label),
            section("i", "icon_grid", props = """{"columns":4}""", ds = ICON_DS, adLabel = label),
            section("a", "activity_entry", ds = ACTIVITY_DS, adLabel = label),
            section(
                "n",
                "notice_bar",
                props = """{"mode":"static","items":[${noticeItem("n1", closable = true, end = null)}]}""",
                ds = """{"kind":"static"}""",
                adLabel = label,
            ),
            section("s", "product_scroll", props = """{"title":"精选"}""", ds = POOL_DS, adLabel = label),
            section("g", "product_grid", props = """{"columns":2,"title":"网格"}""", ds = POOL_DS, adLabel = label),
            section("f", "feed_infinite", props = null, ds = FEED_DS, adLabel = label),
            popupSection("p", adLabel = label),
        )

    /** 每种卡（含弹窗）section 级 ad_label 非 null：都传到渲染层，原样显示在这张卡里（AC-HOME-03，BR-TEXT-17）。 */
    @Test
    fun r1_03_everyCardTypeShowsSectionAdLabelVerbatim() {
        val label = " 推广Ａ "
        val signals = FakeSignals()
        val controller =
            HomeController(FakeHost.IDENTITY, testDeps(signals = signals, port = FakePort(page = { HomeTestKit.ok(allTypesPage(label)) })))
        controller.start()
        show(controller, signals)
        // 渲染层模型
        val cards =
            controller.state.value.cards
                .associateBy { it.id }
        assertEquals(label, (cards.getValue("i") as HomeCardUi.IconGrid).cells.single().adLabel)
        assertEquals(label, (cards.getValue("a") as HomeCardUi.Activity).cells.single().adLabel)
        assertEquals(label, (cards.getValue("n") as HomeCardUi.Notice).adLabel)
        assertEquals(
            label,
            controller.state.value.popup
                ?.adLabel,
        )
        // 逐卡在界面里
        listOf("b", "i", "a", "n", "s", "g", "f").forEach { id ->
            assertTrue(
                "卡 $id 没有推广角标",
                exists(
                    hasText(label) and hasAnyAncestor(hasTestTag(HomeTags.AD_LABEL)) and
                        hasAnyAncestor(hasTestTag(HomeTags.CARD_PREFIX + id)),
                ),
            )
        }
        assertTrue(
            "弹窗没有推广角标",
            exists(hasText(label) and hasAnyAncestor(hasTestTag(HomeTags.AD_LABEL)) and hasAnyAncestor(hasTestTag(HomeTags.POPUP))),
        )
    }

    /** 每种卡 ad_label 为 null：任何卡（含弹窗）都没有角标。 */
    @Test
    fun r1_03_nullAdLabelShowsNoBadgeOnAnyCard() {
        val signals = FakeSignals()
        val controller =
            HomeController(FakeHost.IDENTITY, testDeps(signals = signals, port = FakePort(page = { HomeTestKit.ok(allTypesPage(null)) })))
        controller.start()
        show(controller, signals)
        assertNotNull(controller.state.value.popup)
        listOf("b", "i", "a", "n", "s", "g", "f").forEach { id -> assertTrue("卡 $id 不在", exists(hasTestTag(HomeTags.CARD_PREFIX + id))) }
        assertFalse(exists(hasTestTag(HomeTags.AD_LABEL)))
    }

    /** 条目级优先（G22）：金刚区、双入口的条目 ad_label 非 null 时用条目的，否则用卡片级。 */
    @Test
    fun r1_03_itemLevelAdLabelWinsForIconAndActivity() {
        val icons =
            """{"kind":"static","items":[{"icon":"https://img.example.com/i1.png","title":"甲","ad_label":"条目甲",""" +
                """"action":{"route":"Help"}},""" +
                """{"icon":"https://img.example.com/i2.png","title":"乙","action":{"route":"Help"}}]}"""
        val activities =
            """{"kind":"static","items":[{"title":"甲","ad_label":"条目甲","action":{"route":"Help"}},""" +
                """{"title":"乙","action":{"route":"Help"}}]}"""
        val target =
            page(
                section("i", "icon_grid", props = """{"columns":4}""", ds = icons, adLabel = "推广"),
                section("a", "activity_entry", ds = activities, adLabel = "推广"),
            )
        val signals = FakeSignals()
        val controller = HomeController(FakeHost.IDENTITY, testDeps(signals = signals, port = FakePort(page = { HomeTestKit.ok(target) })))
        controller.start()
        show(controller, signals)
        val cards = controller.state.value.cards
        assertEquals(listOf("条目甲", "推广"), (cards[0] as HomeCardUi.IconGrid).cells.map { it.adLabel })
        assertEquals(listOf("条目甲", "推广"), (cards[1] as HomeCardUi.Activity).cells.map { it.adLabel })
        listOf("i", "a").forEach { id ->
            assertTrue(exists(hasText("条目甲") and hasAnyAncestor(hasTestTag(HomeTags.tap(id, 1)))))
            assertTrue(exists(hasText("推广") and hasAnyAncestor(hasTestTag(HomeTags.tap(id, 2)))))
        }
    }

    // ---------------------------------------------------------------- R1-04

    /** 商品池 / 信息流的待定端口返回 10405：归属复核之后进强更分流（不只是隐藏卡片或显示重试行）。 */
    @Test
    fun r1_04_sectionItems10405EntersForceUpdate() {
        val host = FakeHost()
        val port =
            FakePort(page = { HomeTestKit.ok(page(section("g", "product_grid", props = """{"columns":2}""", ds = POOL_DS))) }).apply {
                items = { _, _ -> failure(FORCE_UPDATE) }
            }
        val controller = HomeController(FakeHost.IDENTITY, testDeps(host = host, port = port))
        controller.start()
        idle()
        assertEquals(0, host.forced)
        // 已作废的实例迟到的 10405 不进强更（先复核归属）
        val late = CompletableDeferred<ApiResult>()
        val heldPort =
            object : HomeContractPort {
                override suspend fun getPage(
                    pageKey: String,
                    call: HomeCall,
                ): ApiResult = HomeTestKit.ok(page(section("g", "product_grid", props = """{"columns":2}""", ds = POOL_DS)))

                override suspend fun listSectionItems(
                    pageKey: String,
                    sectionId: String,
                    cursor: String?,
                    call: HomeCall,
                ): ApiResult = late.await()
            }
        val other = FakeHost()
        val stale = HomeController(FakeHost.IDENTITY, testDeps(host = other, port = heldPort))
        stale.start()
        idle()
        stale.invalidate()
        late.complete(failure(FORCE_UPDATE))
        idle()
        assertEquals(0, other.forced)
    }

    // ---------------------------------------------------------------- R1-05

    /** 冷启动 popup 图片还在加载 → 用户打开商品口径说明 → 图片返回：不叠弹；说明关闭后才弹。 */
    @Test
    fun r1_05_disclaimerNoteOpenBlocksPopupUntilClosed() {
        val gate = CompletableDeferred<Unit>()
        val popupImages = ProductImageLoader { url -> gate.await().let { HomeTestKit.images.load(url) } }
        val target = page(section("g", "product_grid", props = """{"columns":2,"title":"网格"}""", ds = POOL_DS), popupSection("pp"))
        val signals = FakeSignals()
        val prefs = HomePrefsStore(context, "home_r1_note_${System.nanoTime()}")
        val controller =
            HomeController(
                FakeHost.IDENTITY,
                testDeps(signals = signals, port = FakePort(page = { HomeTestKit.ok(target) }), images = popupImages, prefs = prefs),
            )
        controller.start()
        show(controller, signals)
        assertNull(controller.state.value.popup)
        // 打开第一张商品卡的口径说明
        composeRule
            .onAllNodes(hasContentDescription(INFO_LABEL, substring = true) and hasClickAction(), useUnmergedTree = true)[0]
            .performClick()
        idle()
        assertTrue(exists(hasText(NOTE_CLOSE)))
        // 图片现在才返回：说明还开着 → 不弹、不计数
        gate.complete(Unit)
        idle()
        assertNull(controller.state.value.popup)
        assertFalse(exists(hasTestTag(HomeTags.POPUP)))
        assertEquals(0, prefs.shownToday("pp", HomeTestKit.NOON))
        // 关掉说明 → 复核 → 弹出并计数
        composeRule.onAllNodes(hasText(NOTE_CLOSE) and hasClickAction())[0].performClick()
        idle()
        assertTrue(exists(hasTestTag(HomeTags.POPUP)))
        assertEquals(1, prefs.shownToday("pp", HomeTestKit.NOON))
    }

    /** 弹窗状态已生成、对话框还没组合时出现了阻挡（没有变化通知）：真正显示前复核，收起且不计数。 */
    @Test
    fun r1_05_popupShownRechecksBlockersBeforeCounting() {
        val signals = FakeSignals()
        val prefs = HomePrefsStore(context, "home_r1_recheck_${System.nanoTime()}")
        val controller =
            HomeController(
                FakeHost.IDENTITY,
                testDeps(
                    signals = signals,
                    port =
                        FakePort(page = {
                            HomeTestKit.ok(page(popupSection("pp")))
                        }),
                    prefs = prefs,
                ),
            )
        controller.start()
        idle()
        val ticket =
            controller.state.value.popup!!
                .ticket
        signals.blocked = setOf("sheet_open")
        controller.popupShown(ticket)
        assertNull(controller.state.value.popup)
        assertEquals(0, prefs.shownToday("pp", HomeTestKit.NOON))
    }

    // ---------------------------------------------------------------- R1-06

    /** 未计数的弹窗被阻挡收起后重开：新实例编号；保留的旧回调关不掉新弹窗、也不能替它计数或执行跳转。 */
    @Test
    fun r1_06_reshownPopupGetsNewInstanceTicketAndOldCallbacksAreIgnored() {
        val signals = FakeSignals()
        val host = FakeHost()
        val prefs = HomePrefsStore(context, "home_r1_serial_${System.nanoTime()}")
        val controller =
            HomeController(
                FakeHost.IDENTITY,
                testDeps(
                    host = host,
                    signals = signals,
                    port = FakePort(page = { HomeTestKit.ok(HomeTestKit.golden("popup")) }),
                    prefs = prefs,
                ),
            )
        controller.start()
        idle()
        val first =
            controller.state.value.popup!!
                .ticket
        signals.blocked = setOf("sheet_open")
        signals.changes.value += 1
        idle()
        assertNull(controller.state.value.popup)
        signals.blocked = emptySet()
        signals.changes.value += 1
        idle()
        val second =
            controller.state.value.popup!!
                .ticket
        assertNotEquals(first, second)
        assertNotEquals(first.serial, second.serial)
        // 旧对话框迟到的关闭、显示、点击回调
        controller.closePopup(first)
        controller.popupShown(first)
        controller.tapPopup(first)
        assertEquals(
            second,
            controller.state.value.popup
                ?.ticket,
        )
        assertEquals(0, prefs.shownToday("popup", HomeTestKit.NOON))
        assertTrue(host.opened.isEmpty())
        // 新实例自己的回调照常
        controller.popupShown(second)
        assertEquals(1, prefs.shownToday("popup", HomeTestKit.NOON))
        controller.tapPopup(second)
        assertEquals(1, host.opened.size)
        assertNull(controller.state.value.popup)
    }

    // ---------------------------------------------------------------- R1-07

    private fun scrollNotice(): JsonObject =
        page(
            section(
                "nb",
                "notice_bar",
                props =
                    """{"mode":"scroll","items":[${noticeItem("n1", closable = false, end = "2099-01-01T00:00:00+08:00")},""" +
                        """${noticeItem("n2", closable = true, end = null, target = """{"route":"Help"}""")}]}""",
                ds = """{"kind":"static"}""",
            ),
        )

    /** 滚动公告 + 读屏开启：停止自动播放的同时按顺序展开全部有效公告，第二条与它的跳转都在语义树里。 */
    @Test
    fun r1_07_screenReaderShowsEveryScrollNoticeInOrder() {
        val signals = FakeSignals().apply { readerFlow.value = true }
        val host = FakeHost()
        val controller =
            HomeController(
                FakeHost.IDENTITY,
                testDeps(host = host, signals = signals, port = FakePort(page = { HomeTestKit.ok(scrollNotice()) })),
            )
        controller.start()
        show(controller, signals)
        val first = nodes(hasText("测试公告 n1")).single().boundsInRoot
        val second = nodes(hasText("测试公告 n2")).single().boundsInRoot
        assertTrue(second.top >= first.bottom)
        composeRule.onNodeWithTag(HomeTags.tap("nb", 2), useUnmergedTree = true).performClick()
        idle()
        assertEquals(1, host.opened.size)
    }

    /** 读屏关闭：滚动模式照旧一次一条（不误改成静态）。 */
    @Test
    fun r1_07_withoutScreenReaderScrollNoticeShowsOneAtATime() {
        val signals = FakeSignals()
        val controller =
            HomeController(FakeHost.IDENTITY, testDeps(signals = signals, port = FakePort(page = { HomeTestKit.ok(scrollNotice()) })))
        controller.start()
        show(controller, signals)
        assertTrue(exists(hasText("测试公告 n1")))
        assertFalse(exists(hasText("测试公告 n2")))
    }

    // ---------------------------------------------------------------- R1-08

    /** 纯图片活动入口（只有 image + action）：整个可点入口读草稿表的通用名称；有标题的入口仍读标题。 */
    @Test
    fun r1_08_imageOnlyActivityEntryHasSpokenName() {
        val activities =
            """{"kind":"static","items":[{"image":"https://img.example.com/act.png","action":{"route":"Help"}},""" +
                """{"title":"有标题","action":{"route":"Help"}}]}"""
        val signals = FakeSignals()
        val controller =
            HomeController(
                FakeHost.IDENTITY,
                testDeps(
                    signals = signals,
                    port = FakePort(page = { HomeTestKit.ok(page(section("a", "activity_entry", ds = activities))) }),
                ),
            )
        controller.start()
        show(controller, signals)
        val generic = HomeDraftTexts.text(HomeDraftTexts.ACTIVITY_IMAGE)
        assertTrue(generic.isNotEmpty())
        val imageOnly = composeRule.onNodeWithTag(HomeTags.tap("a", 1)).fetchSemanticsNode()
        assertEquals(listOf(generic), imageOnly.config.getOrNull(SemanticsProperties.ContentDescription))
        assertTrue(imageOnly.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick))
        val titled = composeRule.onNodeWithTag(HomeTags.tap("a", 2)).fetchSemanticsNode()
        assertNull(titled.config.getOrNull(SemanticsProperties.ContentDescription))
        assertEquals("有标题", titled.config.getOrNull(SemanticsProperties.Text)?.joinToString())
    }

    // ---------------------------------------------------------------- 工具

    private fun nodes(matcher: SemanticsMatcher) = composeRule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()

    private fun exists(matcher: SemanticsMatcher): Boolean = nodes(matcher).isNotEmpty()

    private class MemoryFlag : HomeLkgStore.PendingFlag {
        private val set = mutableSetOf<String>()

        override fun entries(): Set<String> = synchronized(set) { set.toSet() }

        override fun add(entry: String): Boolean = synchronized(set) { set.add(entry).let { true } }

        override fun remove(entry: String): Boolean = synchronized(set) { set.remove(entry).let { true } }
    }

    private companion object {
        const val ROUNDS = 4
        const val WAIT_S = 10L
        const val POLL_MS = 5L
        const val FORCE_UPDATE = 10405
        const val INFO_LABEL = "价格与返利说明"
        const val NOTE_CLOSE = "知道了"
        const val ACTIVITY_DS = """{"kind":"static","items":[{"title":"测试活动","action":{"route":"Help"}}]}"""
        const val ICON_DS =
            """{"kind":"static","items":[{"icon":"https://img.example.com/i.png","title":"入口","action":{"route":"Help"}}]}"""
        const val BANNER_DS = """{"kind":"static","items":[{"image":"https://img.example.com/b.png","action":{"route":"Help"}}]}"""
        const val POOL_DS = """{"kind":"product_pool","pool_id":"p","limit":20}"""
        const val FEED_DS = """{"kind":"union_material","platforms":["taobao","jd","pdd"],"mix":"interleave"}"""

        fun section(
            id: String,
            type: String,
            props: String? = "{}",
            ds: String,
            adLabel: String? = null,
        ): JsonObject {
            val fields = linkedMapOf<String, kotlinx.serialization.json.JsonElement>()
            fields["id"] = JsonPrimitive(id)
            fields["type"] = JsonPrimitive(type)
            fields["version"] = JsonPrimitive(1)
            adLabel?.let { fields["ad_label"] = JsonPrimitive(it) }
            props?.let { fields["props"] = Json.parseToJsonElement(it) }
            fields["data_source"] = Json.parseToJsonElement(ds)
            return JsonObject(fields)
        }

        fun popupSection(
            id: String,
            adLabel: String? = null,
        ): JsonObject =
            section(
                id,
                "popup",
                props = """{"image":"https://img.example.com/popup.png","daily_max":3,"time_window":["09:00","22:00"]}""",
                ds = """{"kind":"static","items":[{"action":{"route":"Help"}}]}""",
                adLabel = adLabel,
            )

        fun noticeItem(
            item: String,
            closable: Boolean,
            end: String?,
            target: String = "null",
        ): String =
            """{"item_id":"$item","content_version":1,"text":"测试公告 $item","target":$target,"closable":$closable,""" +
                """"start_at":"2026-10-01T00:00:00+08:00","end_at":${end?.let { "\"$it\"" } ?: "null"}}"""

        fun page(vararg sections: JsonObject): JsonObject =
            JsonObject(
                mapOf(
                    "page_key" to JsonPrimitive("home"),
                    "version" to JsonPrimitive(31),
                    "sections" to JsonArray(sections.toList()),
                ),
            )

        fun failure(code: Int): ApiResult =
            ApiResult.Failure(
                code,
                "",
                JsonObject(emptyMap()),
                "trace-test-000777",
                ErrorEffect(code, ErrorEffectKind.TOAST, ReplayMode.NONE, registerPending = false),
                null,
            )
    }
}
