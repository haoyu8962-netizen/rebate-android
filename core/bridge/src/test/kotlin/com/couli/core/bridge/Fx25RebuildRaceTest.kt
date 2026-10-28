package com.couli.core.bridge

import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v5（评审 v2 r2）：
 * - S1-01：判定落在「协调器锁内选定 rebuild 目标并释放锁之后、页面 rebuild() 读隔离序号之前」；
 * - S2-4：事务在跑时同账号重新保存会话，不作废这一轮、不额外等待。
 *
 * S1-01 的页面替身 [SequencedPage] 按 TrustedWebPage 公开的隔离协议建模（不是实现建议）：isolate 先写隔离位与序号、再隔离当前控制器；
 * rebuild 的比较基准优先取协调器在选定目标时给的（协调器若调用可选入口 `beginRebuild`），否则在 rebuild() 入口读；新控制器发布后序号
 * 与基准不等就补隔离、不加载、回 false。替身用动态代理实现 [TrustedAuthDocument]，所以接口加不加 `beginRebuild`、返回 Unit 还是 Long
 * 都能编译，不限定修法（协调器在锁内取基准，或被作废后对本批再补一次隔离，都可以）——但「新控制器先发出加载、再被隔离」不算过（I1：
 * 隔离中拦截新加载）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Fx25RebuildRaceTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private fun TestScope.fixture(mode: Fx25StoreMode) = Fx25AuthFixture.create(this, temporary.newFolder(), mode)

    /** 另一个线程上的判定方式。 */
    private enum class Judgement { OBSERVE_GUEST, SESSION_SAVE_OTHER_ACCOUNT }

    /**
     * S1-01：A→B 那一轮里，协调器选定本页为 rebuild 目标并释放锁，子任务已开始；就在页面 rebuild() 入口、读隔离序号之前，另一个真实线程
     * 判定再次切号（游客 observe，或另一账号 beforeSessionSave）。测试线程 join 后不推进调度器即检查，然后走完新判定的事务。
     * 断言：竞态里建出的控制器被隔离、没有发出加载、没有令牌、回 false；拆它时它已隔离；新判定的 A 不投给它；页面保持隔离、标记仍在，
     * 直到新判定的事务在（P：又换过一次的）新存储上重建才解除。
     * 当前实现（r2）应红：基准在 rebuild() 入口才读，已含这次增量，新控制器不补隔离并照常加载（`raced.loads`、`raced.isolated` 处红）。
     */
    @Test
    fun i1JudgementAfterRebuildSelectionBeforeRebuildEntryFencesTheNewController() =
        runTest {
            for (mode in Fx25StoreMode.entries) {
                for (judgement in Judgement.entries) {
                    fixture(mode).use { f -> judgementBeforeRebuildEntry(f, mode, judgement) }
                }
            }
        }

    private fun TestScope.judgementBeforeRebuildEntry(
        f: Fx25AuthFixture,
        mode: Fx25StoreMode,
        judgement: Judgement,
    ) {
        val label = "$mode/$judgement"
        f.baseline()
        val page = SequencedPage()
        f.sync.attach(page.document)
        assertFalse("与归属一致：登记即可用", page.isolated)
        page.selfLoad(f.store.current())
        var judged = false
        page.beforeRebuildEntry = {
            judged = true
            onOtherThread(label) {
                when (judgement) {
                    Judgement.OBSERVE_GUEST -> f.change(Fx25AuthFixture.GUEST)
                    Judgement.SESSION_SAVE_OTHER_ACCOUNT -> f.sync.beforeSessionSave(OTHER.account)
                }
            }
        }
        f.change()
        f.acknowledgeEvents()
        f.retireAll()
        f.rotate()
        runCurrent()
        assertTrue("$label：判定已注入在 rebuild() 入口", judged)
        val raced = checkNotNull(page.racedController) { "$label：rebuild 入口之后页面建了新控制器" }
        val raceStore = raced.store
        assertTrue("$label：竞态里建出的控制器必须被隔离（无文档也不行）", raced.isolated)
        assertEquals("$label：竞态里建出的控制器不得发出加载", 0, raced.loads)
        assertFalse("$label：竞态里建出的文档不得拿到令牌", raced.bound)
        assertFalse("$label：竞态那次 rebuild 不得回 true", page.rebuildResults.firstOrNull() == true)
        assertTrue("$label：页面保持隔离", page.isolated)
        assertNotNull("$label：新判定的待清标记仍在", f.journal.peek())

        if (judgement == Judgement.SESSION_SAVE_OTHER_ACCOUNT) f.change(OTHER)
        f.completeTransaction()
        assertEquals("$label：新判定的事务结算成功", true, f.settled())
        assertEquals("$label：拆竞态那份时它已隔离（retire 只在隔离之后调用）", 0, page.retiredUnisolated)
        assertEquals("$label：新判定的 A 不投给竞态里建出的文档", 0, raced.events)
        assertEquals("$label：竞态那份始终没加载", 0, raced.loads)
        assertTrue("$label：竞态那份已被拆掉", raced.retired)
        val rebuilt = checkNotNull(page.controller) { "$label：最终在新存储上重建" }
        assertTrue("$label：重建在新控制器上", rebuilt !== raced)
        assertEquals("$label：重建用当前活动存储", f.store.current(), rebuilt.store)
        if (mode == Fx25StoreMode.PROFILE) assertNotEquals("$label：P 路径在竞态之后又换过一次存储", raceStore, rebuilt.store)
        assertEquals("$label：新文档加载一次", 1, rebuilt.loads)
        assertFalse("$label：新存储上重建后才解除", page.isolated)
        assertNull(f.journal.peek())
        val expectedOwner = if (judgement == Judgement.OBSERVE_GUEST) null else OTHER.account
        assertEquals(expectedOwner, f.owner.peek()?.account)
        assertTrue("$label：所有加载都发生在未隔离的控制器上", page.loadsWhileIsolated == 0)
    }

    /**
     * S2-4：A→B 事务在跑（标记已写、A 已发出未确认）时，同一账号 B 再保存一次会话（beforeSessionSave(B)），随后快照与当前完全一致
     * （observe(B)，判定 NONE）。这一轮不能被作废：之后按正常节奏推进（不推进超过单步上限的虚拟时间），事务即结算、只拆一次、
     * 只换一次存储、只发一次 A。当前实现应红：same && dirty 也推进 epoch 并取消在跑的一轮，随后只能等 kick（单步上限）后重跑，
     * `settled()` 处为 null。
     * 另测同账号换了登录世代（observe(B′)）：只做 A，这一轮照常重跑并在单步上限内结算。
     */
    @Test
    fun s24SameAccountSessionSaveDuringTransactionNeitherSupersedesNorDelaysIt() =
        runTest {
            for (mode in Fx25StoreMode.entries) {
                for (newEpoch in listOf(false, true)) {
                    fixture(mode).use { f ->
                        val label = "$mode/newEpoch=$newEpoch"
                        f.baseline()
                        val page = f.page()
                        val rotations = { if (mode == Fx25StoreMode.PROFILE) f.profiles.rotations.size else f.cleaner.requests.size }
                        val rotationsBefore = rotations()
                        f.change()
                        runCurrent()
                        assertEquals("$label：A 已发出、尚未确认", 1, page.events.size)
                        val start = currentTime
                        assertTrue("$label：同账号保存会话，标记已在，返回 true", f.sync.beforeSessionSave(Fx25AuthFixture.B.account))
                        assertNotNull("$label：标记仍在", f.journal.peek())
                        assertTrue("$label：仍隔离", page.isolated)
                        f.change(if (newEpoch) B_NEW_EPOCH else Fx25AuthFixture.B)
                        repeat(STEPS) {
                            f.acknowledgeEvents()
                            f.retireAll()
                        }
                        f.rotate()
                        f.rebuildAll()
                        assertEquals("$label：这一轮不被作废，按正常节奏结算", true, f.settled())
                        assertTrue(
                            "$label：不额外等待（用时 ${currentTime - start} ms，单步上限 ${Fx25AuthFixture.TIMEOUT_MS} ms）",
                            currentTime - start < Fx25AuthFixture.TIMEOUT_MS,
                        )
                        assertFalse("$label：新存储上重建后解除", page.isolated)
                        assertNull(f.journal.peek())
                        assertEquals(Fx25AuthFixture.B.account, f.owner.peek()?.account)
                        assertEquals("$label：只拆一次", 1, page.retires.size)
                        assertEquals("$label：只建一次", 1, page.rebuilds.size)
                        if (mode == Fx25StoreMode.PROFILE) assertEquals("$label：只换一次存储", rotationsBefore + 1, rotations())
                        if (!newEpoch) assertEquals("$label：同一快照不重复 A", 1, page.events.size)
                    }
                }
            }
        }

    /** 在另一个真实线程上执行 [action]，调用线程等它同步返回（有上限，满足即返回，不靠 sleep）；异常带回调用线程。 */
    private fun onOtherThread(
        label: String,
        action: () -> Unit,
    ) {
        var failure: Throwable? = null
        val judge = Thread { failure = runCatching(action).exceptionOrNull() }
        judge.start()
        judge.join(JOIN_MS)
        assertFalse("$label：判定线程同步返回", judge.isAlive)
        failure?.let { throw AssertionError("$label：判定线程失败", it) }
    }

    /** 一份控制器（WebView + 宿主）的替身：只记隔离、加载、令牌、拆除与收到的 A。 */
    private class Controller(
        val store: String,
    ) {
        @Volatile var isolated = false
        var loads = 0
        var bound = false
        var retired = false
        var events = 0
    }

    /** 按 TrustedWebPage 的隔离协议建模的页面槽位（见类注释）。协调器回调可能在判定线程上（isolate），其余在测试线程。 */
    private class SequencedPage {
        @Volatile var isolated = false

        @Volatile private var isolations = 0L

        @Volatile private var baseline: Long? = null

        @Volatile var controller: Controller? = null
            private set
        var racedController: Controller? = null
            private set
        var beforeRebuildEntry: (() -> Unit)? = null
        val rebuildResults = mutableListOf<Boolean>()
        var retiredUnisolated = 0
            private set
        var loadsWhileIsolated = 0
            private set

        val document: TrustedAuthDocument =
            Proxy.newProxyInstance(
                TrustedAuthDocument::class.java.classLoader,
                arrayOf(TrustedAuthDocument::class.java),
            ) { proxy, method, args -> dispatch(proxy, method, args ?: emptyArray()) } as TrustedAuthDocument

        fun selfLoad(store: String) {
            controller = Controller(store).also { load(it) }
        }

        private fun load(target: Controller) {
            if (target.isolated) loadsWhileIsolated += 1
            target.loads += 1
            target.bound = !target.isolated
        }

        @Suppress("UNCHECKED_CAST", "CyclomaticComplexMethod") // 动态代理：按方法名分派；回调参数是 Kotlin 函数类型
        private fun dispatch(
            proxy: Any,
            method: Method,
            args: Array<out Any?>,
        ): Any? =
            when (method.name) {
                "getCurrentTrusted" -> {
                    controller != null
                }

                "getTokenDelivered" -> {
                    controller?.bound == true
                }

                "getDestroyed" -> {
                    false
                }

                "isolate" -> {
                    isolate()
                }

                "notifyAuthChanged" -> {
                    controller?.let { it.events += 1 }
                    (args[1] as (Boolean) -> Unit)(true)
                }

                "retire" -> {
                    retire(args[0] as (Boolean) -> Unit)
                }

                "rebuild" -> {
                    rebuild(args[0] as String, args[1] as (Boolean) -> Unit)
                }

                "releaseAfterRebuild" -> {
                    isolated = false
                }

                "beginRebuild" -> {
                    val seen = isolations
                    baseline = seen
                    if (method.returnType == java.lang.Long.TYPE || method.returnType == Long::class.javaObjectType) seen else null
                }

                "equals" -> {
                    proxy === args[0]
                }

                "hashCode" -> {
                    System.identityHashCode(proxy)
                }

                "toString" -> {
                    "SequencedPage"
                }

                else -> {
                    error("页面替身不认识的协调器调用：${method.name}")
                }
            }

        private fun isolate() {
            isolated = true
            isolations += 1
            controller?.isolated = true
        }

        private fun retire(completed: (Boolean) -> Unit) {
            controller?.let { current ->
                if (!current.isolated) retiredUnisolated += 1
                current.retired = true
                current.bound = false
            }
            controller = null
            completed(true)
        }

        private fun rebuild(
            store: String,
            completed: (Boolean) -> Unit,
        ) {
            beforeRebuildEntry?.let { hook ->
                beforeRebuildEntry = null
                hook()
                val seen = baseline ?: isolations
                baseline = null
                racedController = build(store, seen)
                finish(checkNotNull(racedController), seen, completed)
                return
            }
            val seen = baseline ?: isolations
            baseline = null
            finish(build(store, seen), seen, completed)
        }

        private fun build(
            store: String,
            seen: Long,
        ): Controller {
            val created = Controller(store)
            controller = created
            if (isolations != seen) created.isolated = true
            return created
        }

        private fun finish(
            created: Controller,
            seen: Long,
            completed: (Boolean) -> Unit,
        ) {
            if (isolations != seen || created.isolated) {
                rebuildResults += false
                completed(false)
                return
            }
            load(created)
            rebuildResults += true
            completed(true)
        }
    }

    private companion object {
        /** 判定线程同步返回的等待上限（满足即返回）。 */
        const val JOIN_MS = 10_000L

        /** 同账号换了登录世代时会多一条 A：确认 / 拆除多推进几次（每次只在测试线程上推进当前可运行的任务）。 */
        const val STEPS = 3
        val OTHER = WebAuthIdentity(true, "user-c", 4L)
        val B_NEW_EPOCH = WebAuthIdentity(true, "user-b", 9L)
    }
}
