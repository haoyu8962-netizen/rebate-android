package com.couli.core.home

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.clientcore.BundledHome
import java.io.File
import java.io.IOException
import java.time.OffsetDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 首页本机存储（G16 ①②、G11、G30；APP-03b 第 1 条，APP-08 第 20、28 条，共性第 8、9、19 条）与包内默认（G17）。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [36])
class HomeStoresTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var root: File

    /** 目录外的待清理标记（内存；可模拟写不进）。 */
    private class MemoryFlag : HomeLkgStore.PendingFlag {
        val set = mutableSetOf<String>()
        var writable = true
        var readable = true

        override fun entries(): Set<String>? = if (readable) set.toSet() else null

        override fun add(entry: String): Boolean = writable && set.add(entry).let { true }

        override fun remove(entry: String): Boolean = writable && set.remove(entry).let { true }
    }

    @Before
    fun setUp() {
        root = File(context.noBackupFilesDir, "home-store-test-${System.nanoTime()}")
        context
            .getSharedPreferences(HomePrefsStore.NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun lkgIsPartitionedByEnvironmentAndAccountScopeAndAtomic() {
        val store = HomeLkgStore(root, MemoryFlag())
        val a = HomeLkgScope.of(HomeLevel.MEMBER, "A", "prod")
        val b = HomeLkgScope.of(HomeLevel.MEMBER, "B", "prod")
        val guest = HomeLkgScope.of(HomeLevel.GUEST, null, "prod")
        val basicStaging = HomeLkgScope.of(HomeLevel.BASIC, null, "staging")
        assertTrue(store.write(store.ticket(a), """{"v":"A"}"""))
        assertTrue(store.write(store.ticket(guest), """{"v":"G"}"""))
        assertEquals("""{"v":"A"}""", store.read(a))
        assertNull(store.read(b))
        assertNull(store.read(basicStaging))
        // 目录名是摘要，不落明文账号（G30）
        assertTrue(root.walkTopDown().none { it.name.contains("A") && it.name != it.name.lowercase() })
        assertTrue(root.walkTopDown().filter { it.isFile }.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun logoutClearsOnlyOldScopeAndConsentClearsAllIncludingTemporaryFiles() {
        val store = HomeLkgStore(root, MemoryFlag())
        val a = HomeLkgScope.of(HomeLevel.MEMBER, "A", "prod")
        val guest = HomeLkgScope.of(HomeLevel.GUEST, null, "prod")
        store.write(store.ticket(a), "{}")
        store.write(store.ticket(guest), "{}")
        store.clearScope(a)
        assertNull(store.read(a))
        assertEquals("{}", store.read(guest))
        // 改名之前进程退出留下的临时文件也在清理范围
        val leftover = File(File(root, HomeLkgStore.digest(guest)), "page.json.tmp").apply { writeText("{}") }
        store.clearAll()
        assertFalse(leftover.exists())
        assertNull(store.read(guest))
        assertTrue(root.listFiles().isNullOrEmpty())
    }

    @Test
    fun failedDeleteKeepsPendingFlagOutsideAndRestartCompletesBeforeReading() {
        val flag = MemoryFlag()
        var deletable = false
        val a = HomeLkgScope.of(HomeLevel.MEMBER, "A", "prod")
        HomeLkgStore(root, flag).let { it.write(it.ticket(a), """{"v":1}""") }
        val broken = HomeLkgStore(root, flag, deleter = { deletable && it.deleteRecursively() })
        assertThrows { broken.clearAll() }
        // 标记在目录外保留；删不掉期间本进程不读回
        assertTrue(HomeLkgStore.ALL in flag.set)
        assertNull(broken.read(a))
        assertFalse(broken.write(broken.ticket(a), "{}"))
        // 「重启」：新实例读之前先核验标记；仍删不掉 → 不读回
        val restarted = HomeLkgStore(root, flag, deleter = { deletable && it.deleteRecursively() })
        assertNull(restarted.read(a))
        assertTrue(File(root, HomeLkgStore.digest(a)).exists())
        // 删除恢复：补清完成才清标记，之后才能写读
        deletable = true
        assertNull(restarted.read(a))
        assertTrue(flag.set.isEmpty())
        assertFalse(File(root, HomeLkgStore.digest(a)).exists())
        assertTrue(restarted.write(restarted.ticket(a), "{}"))
        assertEquals("{}", restarted.read(a))
    }

    @Test
    fun flagAndDeleteBothFailingThrowsToCoordinatorAndBlocksThisProcess() {
        val flag = MemoryFlag().apply { writable = false }
        val a = HomeLkgScope.of(HomeLevel.MEMBER, "A", "prod")
        HomeLkgStore(root, MemoryFlag()).let { it.write(it.ticket(a), "{}") }
        val store = HomeLkgStore(root, flag, deleter = { false })
        val error = runCatching { store.clearScope(a) }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(error!!.message!!.contains("pending flag not written"))
        assertNull(store.read(a))
    }

    @Test
    fun unlistableDirectoryIsIncompleteNotMissing() {
        val flag = MemoryFlag()
        val a = HomeLkgScope.of(HomeLevel.GUEST, null, "prod")
        HomeLkgStore(root, flag).let { it.write(it.ticket(a), "{}") }
        val store = HomeLkgStore(root, flag, lister = { null })
        assertThrows { store.clearAll() }
        assertTrue(HomeLkgStore.ALL in flag.set)
        // 目录不存在不算失败
        val missing = HomeLkgStore(File(root, "absent"), MemoryFlag(), lister = { null })
        missing.clearAll()
    }

    @Test
    fun unreadableFlagIsTreatedAsEverythingPending() {
        val flag = MemoryFlag()
        val a = HomeLkgScope.of(HomeLevel.GUEST, null, "prod")
        HomeLkgStore(root, flag).let { it.write(it.ticket(a), "{}") }
        flag.readable = false
        assertNull(HomeLkgStore(root, flag).read(a))
        assertFalse(File(root, HomeLkgStore.digest(a)).exists())
    }

    @Test
    fun noticeClosedRecordsAreDeviceLevelUnboundedAndStoredPerKey() {
        val prefs = HomePrefsStore(context)
        val keys = (1..3_000).map { NoticeFilter.closeKey("notice", "item-$it", 1) }
        keys.forEach { assertTrue(prefs.addClosed(prefs.ticket(), it, 1L)) }
        // 不按条数淘汰（BR-TEXT-13，共性第 9 条）；每条一个键，不会把单个值越写越大（共性第 19 条）
        assertEquals(keys.toSet(), HomePrefsStore(context).closedKeys())
        val raw = context.getSharedPreferences(HomePrefsStore.NAME, Context.MODE_PRIVATE).all
        assertTrue(raw.values.all { it is Long })
        // 同键去重
        prefs.addClosed(prefs.ticket(), keys.first(), 2L)
        assertEquals(3_000, prefs.closedKeys().size)
        prefs.clearAll()
        assertTrue(prefs.closedKeys().isEmpty())
    }

    @Test
    fun popupCountsFollowShanghaiNaturalDay() {
        val prefs = HomePrefsStore(context)
        val evening = ms("2026-10-10T23:30:00+08:00")
        val nextMorning = ms("2026-10-11T00:30:00+08:00")
        assertEquals(0, prefs.shownToday("popup", evening))
        assertTrue(prefs.markShown(prefs.ticket(), "popup", evening))
        assertEquals(1, prefs.shownToday("popup", evening))
        assertEquals(0, prefs.shownToday("other", evening))
        assertEquals(0, prefs.shownToday("popup", nextMorning))
        prefs.markShown(prefs.ticket(), "popup", nextMorning)
        assertEquals(1, prefs.shownToday("popup", nextMorning))
    }

    @Test
    fun bundledDefaultHomeIsTheVectorPageAndRenderable() {
        val vectors = Json.parseToJsonElement(specs().readText()).jsonObject
        val expected = vectors.getValue("goldenPages").jsonObject.getValue("default_home")
        val text = assertNotNull(BundledHome.pageJson()).let { BundledHome.pageJson()!! }
        assertEquals(expected, Json.parseToJsonElement(text))
        val page = HomeJson.parseText(text, "android", "1.0.0")!!
        assertTrue(page.renderable)
        assertNull(page.popup)
        // 不含 banner、公告、商品卡、popup，不引用网络图片（G17）
        assertEquals(listOf(HomeCardType.ICON_GRID, HomeCardType.ACTIVITY_ENTRY), page.kept.map { it.type })
        assertFalse(text.contains("http"))
    }

    @Test
    fun actionsIgnoreScriptFieldsAndImagesOnlyAcceptHttps() {
        val page =
            HomeJson.parseText(
                """{"page_key":"home","version":3,"sections":[
                {"id":"b","type":"banner","version":1,"props":{"height_ratio":0.5,"autoplay_ms":0},"data_source":{"kind":"static","items":[
                  {"image":"http://img.example.com/a.png","action":{"route":"Help"}},
                  {"image":"https://img.example.com/b.png","action":{"route":"Help","js":"x","url":"https://evil.example.com"}},
                  {"image":"javascript:alert(1)","action":{"route":"Help"}}]}},
                {"id":"i","type":"icon_grid","version":1,"props":{"columns":4},"data_source":{"kind":"static","items":[
                  {"icon":"builtin:unknown","title":"X","action":{"route":"Help"}},
                  {"icon":"file:///etc/passwd","title":"Y","action":{"route":"Help"}},
                  {"icon":"builtin:guide","title":"Z","action":{"route":"Help"}}]}},
                {"id":"n","type":"notice_bar","version":1,"props":{"items":[
                  {"item_id":"x","content_version":1,"text":"t","target":null,"closable":true,"start_at":"2026-10-01T00:00:00+08:00","end_at":null,"html":"<b>"},
                  {"item_id":"y","content_version":1,"text":"t","closable":true,"start_at":"2026-10-01T00:00:00+08:00","end_at":null}]},"data_source":{"kind":"static"}},
                {"id":"api_banner","type":"banner","version":1,"props":{"height_ratio":0.5},"data_source":{"kind":"api","path":"/v1/anything"}}
                ]}""",
                "android",
                "1.0.0",
            )!!
        val banner = page.cards[0] as HomeCard.Banner
        assertEquals(listOf("https://img.example.com/b.png"), banner.items.map { it.image })
        assertEquals(RouteTarget("Help", null), banner.items.single().action)
        assertEquals(listOf(HomeIcon.Builtin("guide")), (page.cards[1] as HomeCard.IconGrid).items.map { it.icon })
        // NoticeItem additionalProperties:false：多字段、缺 target 键都只丢这一条（H07）
        assertEquals(0, (page.cards[2] as HomeCard.NoticeBar).items.size)
        // api 来源只给公告条（其余按解码失败）
        assertEquals(HomeDropReason.BAD_VALUE, page.dropped.single { it.id == "api_banner" }.reason)
    }

    @Test
    fun sourceHasNoVersionEqualityComparison() {
        // AC-HOME-06：版本门槛只做「当前版本 ≥ 门槛」；源码里没有等值、不高于、提审版本一类的分支
        val repo = specs().parentFile!!.parentFile!!
        val sources =
            listOf("core/home/src/main", "feature/home/src/main", "app/src/main/kotlin/com/couli/app/home")
                .flatMap { File(repo, it).walkTopDown().filter { f -> f.extension == "kt" }.toList() }
        assertTrue(sources.isNotEmpty())
        val uses = sources.flatMap { file -> file.readLines().filter { it.contains("compareOrNull(") } }
        assertEquals(1, uses.size)
        val filter = File(repo, "core/home/src/main/kotlin/com/couli/core/home/HomeRules.kt").readText()
        assertTrue(filter.contains("return compared >= 0"))
        sources.forEach { file ->
            val text = file.readText()
            listOf(
                "compared == 0",
                "compared <= 0",
                "compared < 0",
                "appVersion ==",
                "== appVersion",
                "condition\"]",
                "\"login\"",
            ).forEach {
                assertFalse("${file.name} 含 $it", text.contains(it))
            }
        }
    }

    // ---------------------------------------------------------------- R1-01（S0）：写入世代票据

    /** 让一个线程停在 [Thread.State.BLOCKED]（等锁）。 */
    private fun awaitBlocked(thread: Thread) {
        val deadline =
            System.nanoTime() +
                java.util.concurrent.TimeUnit.SECONDS
                    .toNanos(WAIT_S)
        while (thread.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(1)
        assertEquals(Thread.State.BLOCKED, thread.state)
    }

    /**
     * 旧写入等锁 → 清理完成 → 旧写入继续：撤回同意时 A 的 IO 写入已经拿着旧票据在等存储锁；clearAll 删完、清掉待清理标记、释放锁之后，
     * 旧写入拿到锁也必须因世代不符被拒，A 的 page.json 不会复活。
     */
    @Test
    fun r1_01_lkgWriteWaitingForLockDuringClearAllDoesNotResurrectFile() {
        val a = HomeLkgScope.of(HomeLevel.MEMBER, "A", "prod")
        val deleting = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val store =
            HomeLkgStore(root, MemoryFlag(), deleter = { dir ->
                deleting.countDown()
                release.await(WAIT_S, java.util.concurrent.TimeUnit.SECONDS)
                dir.deleteRecursively() && !dir.exists()
            })
        val ticket = store.ticket(a)
        assertTrue(store.write(ticket, """{"v":"A1"}"""))
        val page = File(File(root, HomeLkgStore.digest(a)), "page.json")
        assertTrue(page.exists())
        var cleared: Throwable? = null
        val clearer = Thread { cleared = runCatching { store.clearAll() }.exceptionOrNull() }.apply { start() }
        assertTrue(deleting.await(WAIT_S, java.util.concurrent.TimeUnit.SECONDS))
        var written: Boolean? = null
        val writer = Thread { written = store.write(ticket, """{"v":"A2"}""") }.apply { start() }
        awaitBlocked(writer)
        release.countDown()
        clearer.join(WAIT_S * MILLIS)
        writer.join(WAIT_S * MILLIS)
        assertNull(cleared)
        assertEquals(false, written)
        assertFalse(page.exists())
        assertNull(store.read(a))
        // 清理之后取的新票据照常写
        assertTrue(store.write(store.ticket(a), """{"v":"A3"}"""))
    }

    /** 同上，退出登录 / 换账号的 clearScope：等锁的旧写入不复活，其它作用域的票据不受影响。 */
    @Test
    fun r1_01_lkgWriteWaitingForLockDuringClearScopeDoesNotResurrectFile() {
        val a = HomeLkgScope.of(HomeLevel.MEMBER, "A", "prod")
        val guest = HomeLkgScope.of(HomeLevel.GUEST, null, "prod")
        val deleting = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val store =
            HomeLkgStore(root, MemoryFlag(), deleter = { dir ->
                deleting.countDown()
                release.await(WAIT_S, java.util.concurrent.TimeUnit.SECONDS)
                dir.deleteRecursively() && !dir.exists()
            })
        val ticketA = store.ticket(a)
        val ticketGuest = store.ticket(guest)
        assertTrue(store.write(ticketA, "{}"))
        val clearer = Thread { store.clearScope(a) }.apply { start() }
        assertTrue(deleting.await(WAIT_S, java.util.concurrent.TimeUnit.SECONDS))
        var written: Boolean? = null
        val writer = Thread { written = store.write(ticketA, """{"v":"late"}""") }.apply { start() }
        awaitBlocked(writer)
        release.countDown()
        clearer.join(WAIT_S * MILLIS)
        writer.join(WAIT_S * MILLIS)
        assertEquals(false, written)
        assertNull(store.read(a))
        assertTrue(store.write(ticketGuest, "{}"))
    }

    /** 公告关闭记录、弹窗计数：等锁的旧写入在 clearAll 完成后继续，也必须被拒；清理器 new 的另一个实例同样推进世代。 */
    @Test
    fun r1_01_prefsWritesWaitingForLockDuringClearAllAreRejected() {
        val name = "home_prefs_r1_${System.nanoTime()}"
        val holding = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val home = HomePrefsStore(context, name)
        val cleaner =
            HomePrefsStore(context, name, beforeClearCommit = {
                holding.countDown()
                release.await(WAIT_S, java.util.concurrent.TimeUnit.SECONDS)
            })
        val ticket = home.ticket()
        assertTrue(home.addClosed(ticket, "k0", 1L))
        val clearer = Thread { cleaner.clearAll() }.apply { start() }
        assertTrue(holding.await(WAIT_S, java.util.concurrent.TimeUnit.SECONDS))
        val results = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        val closer = Thread { results["close"] = home.addClosed(ticket, "k1", 2L) }.apply { start() }
        val counter = Thread { results["count"] = home.markShown(ticket, "popup", 2L) }.apply { start() }
        awaitBlocked(closer)
        awaitBlocked(counter)
        release.countDown()
        listOf(clearer, closer, counter).forEach { it.join(WAIT_S * MILLIS) }
        assertEquals(mapOf("close" to false, "count" to false), results.toMap())
        assertTrue(home.closedKeys().isEmpty())
        assertEquals(0, home.shownToday("popup", 2L))
        // 新票据照常写
        assertTrue(home.addClosed(home.ticket(), "k2", 3L))
    }

    // ---------------------------------------------------------------- R2-01：接受顺序序号

    /**
     * 同一控制器先接受 P1、再接受 P2（各在接受时取序号），两次写入逆序拿到存储锁：P2 先落盘，P1 后到 → 在锁内被拒，缓存仍是 P2。
     * 较新的写入没落盘（失败）时不挡住较旧的；清理之后的新票据照常写。
     */
    @Test
    fun r2_01_olderAcceptedLkgWriteTakingLockLaterIsRejected() {
        val a = HomeLkgScope.of(HomeLevel.MEMBER, "A", "prod")
        val store = HomeLkgStore(root, MemoryFlag())
        val ticket = store.ticket(a)
        val p1 = store.reserve()
        val p2 = store.reserve()
        assertTrue(p2 > p1)
        assertTrue(store.write(ticket, p2, """{"v":"P2"}"""))
        assertFalse("较早接受的 P1 晚拿到锁，不能盖掉 P2", store.write(ticket, p1, """{"v":"P1"}"""))
        assertEquals("""{"v":"P2"}""", store.read(a))
        // 同一序号重放也不写
        assertFalse(store.write(ticket, p2, """{"v":"P2-again"}"""))
        // 跨控制器：之后（新控制器）接受的结果序号更大，照常写
        assertTrue(store.write(store.ticket(a), store.reserve(), """{"v":"P3"}"""))
        assertEquals("""{"v":"P3"}""", store.read(a))
    }

    /** 两个线程真实争锁：P2 的写入先进锁，P1 的写入在锁外等（BLOCKED），P2 落盘后 P1 拿到锁 → 被拒。 */
    @Test
    fun r2_01_reversedLockAcquisitionAcrossThreadsKeepsNewest() {
        val a = HomeLkgScope.of(HomeLevel.MEMBER, "A", "prod")
        val store = HomeLkgStore(root, MemoryFlag())
        val ticket = store.ticket(a)
        val p1 = store.reserve()
        val p2 = store.reserve()
        var first: Boolean? = null
        var second: Boolean? = null
        val writer2: Thread
        val writer1: Thread
        synchronized(store) {
            writer2 = Thread { first = store.write(ticket, p2, """{"v":"P2"}""") }.apply { start() }
            awaitBlocked(writer2)
        }
        writer2.join(WAIT_S * MILLIS)
        writer1 = Thread { second = store.write(ticket, p1, """{"v":"P1"}""") }.apply { start() }
        writer1.join(WAIT_S * MILLIS)
        assertEquals(true, first)
        assertEquals(false, second)
        assertEquals("""{"v":"P2"}""", store.read(a))
    }

    /** 读到坏 LKG 后删除：只删读到的那一份；读与删之间落盘了较新的结果 → 不删。 */
    @Test
    fun r2_01_discardOnlyRemovesTheCopyThatWasRead() {
        val a = HomeLkgScope.of(HomeLevel.MEMBER, "A", "prod")
        val store = HomeLkgStore(root, MemoryFlag())
        val ticket = store.ticket(a)
        assertTrue(store.write(ticket, "{bad"))
        val read = store.read(a)!!
        val discardOrder = store.reserve()
        assertTrue(store.write(ticket, store.reserve(), """{"v":"good"}"""))
        assertFalse(store.discard(ticket, discardOrder, read))
        assertEquals("""{"v":"good"}""", store.read(a))
        assertTrue(store.discard(ticket, store.reserve(), """{"v":"good"}"""))
        assertNull(store.read(a))
    }

    /** 关闭记录与弹窗计数：较早接受的写入晚到（逆序进锁）被拒；旧日期的计数不覆盖新日期。 */
    @Test
    fun r2_01_prefsWritesCarryAcceptanceOrder() {
        val name = "home_prefs_r2_${System.nanoTime()}"
        val prefs = HomePrefsStore(context, name)
        val ticket = prefs.ticket()
        val day1 = ms("2026-10-10T23:59:00+08:00")
        val day2 = ms("2026-10-11T00:01:00+08:00")
        val o1 = prefs.reserve()
        val o2 = prefs.reserve()
        assertTrue(prefs.markShown(ticket, o2, "pp", day2))
        assertFalse("旧日期的计数晚到，不能把新日期的计数改回去", prefs.markShown(ticket, o1, "pp", day1))
        assertEquals(1, prefs.shownToday("pp", day2))
        val c1 = prefs.reserve()
        val c2 = HomePrefsStore(context, name).reserve()
        assertTrue(HomePrefsStore(context, name).addClosed(ticket, c2, "k", 200L))
        assertFalse(prefs.addClosed(ticket, c1, "k", 100L))
        val raw = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        assertEquals(200L, raw.getLong("n:k", 0L))
        assertTrue(prefs.markShown(ticket, prefs.reserve(), "pp", day2))
        assertEquals(2, prefs.shownToday("pp", day2))
    }

    private fun specs(): File {
        val start = File(System.getProperty("user.dir") ?: ".").canonicalFile
        val root = generateSequence(start) { it.parentFile }.first { File(it, "contract.lock").isFile }
        return File(root, "specs/home-sdui-vectors.json")
    }

    private fun ms(iso: String): Long = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    private fun assertThrows(block: () -> Unit) {
        assertTrue(runCatching(block).isFailure)
    }

    private companion object {
        const val WAIT_S = 10L
        const val MILLIS = 1_000L
    }
}
