package com.couli.core.bridge

import java.lang.ref.WeakReference
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** v3 恢复：拆超时、rebuild 失败重跑整事务、迟到回调、旧配置文件删不掉留待冷启动、强持有。 */
@OptIn(ExperimentalCoroutinesApi::class)
class Fx25AuthRecoveryTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private fun kotlinx.coroutines.test.TestScope.fixture(
        mode: Fx25StoreMode = Fx25StoreMode.PROFILE,
        profiles: Fx25ProfileStore? = null,
        dir: java.io.File = temporary.newFolder(),
    ) = Fx25AuthFixture.create(this, dir, mode, profiles)

    /** 变异 19：rebuild 失败后的重试必须重跑整事务（拆半成品 → 再换存储 → 再建），不能只重载。 */
    @Test
    fun i1FailedRebuildRetryRerunsTheWholeTransaction() =
        runTest {
            for (mode in Fx25StoreMode.entries) {
                fixture(mode).use { f ->
                    f.baseline()
                    val page = f.page()
                    f.change()
                    f.acknowledgeEvents()
                    f.retireAll()
                    f.rotate()
                    f.rebuildAll(success = false)
                    assertTrue(page.isolated)
                    assertEquals(false, f.settled())
                    val rotationsBefore = if (mode == Fx25StoreMode.PROFILE) f.profiles.rotations.size else f.cleaner.requests.size
                    f.sync.retry()
                    f.completeTransaction()
                    assertEquals("重试先拆掉失败的那份", 2, page.retires.size)
                    val rotationsAfter = if (mode == Fx25StoreMode.PROFILE) f.profiles.rotations.size else f.cleaner.requests.size
                    assertTrue("重试再换一次存储：$mode", rotationsAfter > rotationsBefore)
                    assertEquals(2, page.rebuilds.size)
                    assertFalse(page.isolated)
                    assertEquals("重试不重复 A", 1, page.events.size)
                }
            }
        }

    @Test
    fun i1PreviousRebuildCallbackCannotUnlockNewerIdentityTransaction() =
        runTest {
            fixture().use { f ->
                f.baseline()
                val page = f.page()
                f.change()
                f.acknowledgeEvents()
                f.retireAll()
                f.rotate()
                runCurrent()
                val stale = page.rebuilds.single()
                f.change(Fx25AuthFixture.A)
                stale.completion.complete(true)
                runCurrent()
                assertTrue("B 文档的迟到完成不能解锁 A 的新事务", page.isolated)
                assertNotNull(f.journal.peek())
                f.completeTransaction()
                assertFalse(page.isolated)
                assertEquals(2, page.rebuilds.size)
                assertEquals(Fx25AuthFixture.A.account, f.owner.peek()?.account)
            }
        }

    /**
     * 评审 v2 r1 S1-01 的协调器一半：页面槽位要能把隔离补给「正在建」的控制器，前提是协调器在**判定线程上、同步地**对每个登记页
     * 都再调一次 isolate()——包括还没加载过文档的新页（load 路径）和 rebuild 正在进行中的页（rebuild 路径），不能因为「已在事务中 /
     * 已标隔离」就跳过。判定放在另一个真实线程上执行，测试线程 join 后、不推进调度器即检查。页面那一半（新控制器确实被隔离、不加载、
     * 不交令牌、拦请求，直到新存储上重建）由设备用例 W.i1JudgementRacingControllerCreationOn{Load,Rebuild}… 用工厂钩子 + 闩锁证明
     * （TrustedWebPage 依赖真实 WebView，feature:web 没有 Robolectric，JVM 上无法构造）。
     */
    @Test
    fun i1JudgementFromAnotherThreadReisolatesFreshAndRebuildingPagesSynchronously() =
        runTest {
            fixture().use { f ->
                f.baseline()
                val rebuilding = f.page("rebuilding")
                f.change()
                f.acknowledgeEvents()
                f.retireAll()
                f.rotate()
                runCurrent()
                assertEquals("rebuild 已发出、尚未完成", 1, rebuilding.rebuilds.size)
                val fresh = f.page("fresh-during-rebuild")
                val before = f.trace.count { it == "rebuilding:isolate" } to f.trace.count { it == "fresh-during-rebuild:isolate" }
                var failure: Throwable? = null
                val judge =
                    Thread {
                        failure = runCatching { f.change(Fx25AuthFixture.GUEST) }.exceptionOrNull()
                    }
                judge.start()
                judge.join(JOIN_MS)
                assertFalse("判定线程同步返回", judge.isAlive)
                assertNull(failure)
                val after = f.trace.count { it == "rebuilding:isolate" } to f.trace.count { it == "fresh-during-rebuild:isolate" }
                assertTrue("rebuild 中的页在判定线程上同步再隔离一次", after.first > before.first)
                assertTrue("事务中新登记的页在判定线程上同步再隔离一次", after.second >= maxOf(before.second, 1))
                assertTrue(rebuilding.isolated && fresh.isolated)
                rebuilding.rebuilds
                    .single()
                    .completion
                    .complete(true)
                runCurrent()
                assertTrue("旧一轮 rebuild 的迟到成功不能解除新判定的隔离", rebuilding.isolated)
                f.completeTransaction()
                assertFalse(rebuilding.isolated)
                assertFalse(fresh.isolated)
                assertEquals(null, f.owner.peek()?.account)
            }
        }

    /** 变异 3：不等 destroy 确认（retire 立即算成功）。拆超时 = 失败：保持隔离、不换存储、标记保留。 */
    @Test
    fun i1RetireTimeoutKeepsIsolationAndNeverRotates() =
        runTest {
            for (mode in Fx25StoreMode.entries) {
                fixture(mode).use { f ->
                    f.baseline()
                    val page = f.page()
                    f.change()
                    f.acknowledgeEvents()
                    assertEquals(1, page.retires.size)
                    advanceTimeBy(Fx25AuthFixture.BEYOND_ANY_STEP_MS)
                    runCurrent()
                    assertEquals(false, f.settled())
                    assertTrue(page.isolated)
                    assertEquals(0, f.profiles.rotations.size + f.cleaner.requests.size)
                    assertNotNull(f.journal.peek())
                    page.retires.single().complete(true)
                    runCurrent()
                    advanceTimeBy(ClearingTrustedWebStore.GRACE_MS + 1)
                    runCurrent()
                    assertEquals("超时后迟到的销毁确认不能续跑旧一轮", 0, f.profiles.rotations.size + f.cleaner.requests.size)
                    assertTrue(page.rebuilds.isEmpty())
                    f.sync.retry()
                    f.completeTransaction()
                    assertFalse(page.isolated)
                }
            }
        }

    @Test
    fun i3UnreadablePersistentMarkerCannotBeTreatedAsNoPendingTransaction() =
        runTest {
            val dir = temporary.newFolder()
            val journal = Fx25Journal(java.io.File(dir, "pending.json"))
            journal.markPending(Fx25AuthFixture.A)
            journal.failRead = true
            Fx25AuthFixture(this, journal, Fx25OwnerRecord(java.io.File(dir, "owner.json"), journal.trace)).use { f ->
                f.owner.write(WebDataOwner(Fx25AuthFixture.B.account))
                val cold = f.page("cold-unreadable-journal")
                f.change(Fx25AuthFixture.B)
                runCurrent()
                assertTrue(cold.isolated)
                assertTrue(cold.rebuilds.isEmpty())
                assertTrue(cold.events.isEmpty())
                journal.failRead = false
                f.sync.retry()
                f.completeTransaction()
                assertTrue("读不出的标记按有待清理：必须换存储", f.profiles.rotations.isNotEmpty())
                assertFalse(cold.isolated)
            }
        }

    @Test
    fun i4ClosingOnePageDuringTransactionDoesNotLoseOtherPagesRecovery() =
        runTest {
            fixture().use { f ->
                f.baseline()
                val gone = f.page("closed")
                val remaining = f.page("background")
                f.change()
                f.acknowledgeEvents()
                gone.closeByUser()
                f.completeTransaction()
                assertTrue(gone.rebuilds.isEmpty())
                assertEquals(1, remaining.rebuilds.size)
                assertFalse(remaining.isolated)
            }
        }

    /** D10-310 (5)：deleteProfile 抛「仍在使用」不阻塞事务，留待冷启动删除并记埋点；冷启动在加载前删掉残留。 */
    @Test
    fun i7OldProfileDeleteFailureIsDeferredToColdStartWithoutBlocking() =
        runTest {
            val profiles = Fx25ProfileStore(mutableListOf())
            val dir = temporary.newFolder()
            val old = profiles.current()
            fixture(profiles = profiles, dir = dir).use { f ->
                f.baseline()
                val page = f.page()
                profiles.inUse += old
                f.change()
                f.completeTransaction()
                assertEquals("删不掉不阻塞", true, f.settled())
                assertFalse(page.isolated)
                assertTrue("尝试过删除旧配置文件", profiles.discards.isNotEmpty())
                assertTrue(old in profiles.retired)
                assertTrue(TrustedAuthSync.METRIC_DISCARD_DEFERRED in f.metrics)
            }
            profiles.inUse.clear()
            fixture(profiles = profiles, dir = dir).use { cold ->
                runCurrent()
                assertTrue("冷启动删除残留", old !in profiles.retired)
                assertNull(profiles.data[old])
                assertTrue(old !in profiles.data.keys)
                cold.baseline(Fx25AuthFixture.B)
            }
        }

    @Test
    fun i7SuccessfulTransactionDiscardsTheRetiredProfile() =
        runTest {
            val profiles = Fx25ProfileStore(mutableListOf())
            fixture(profiles = profiles).use { f ->
                f.baseline()
                f.page()
                val old = profiles.current()
                f.change()
                f.completeTransaction()
                assertTrue("旧配置文件在其页全部销毁后删除", old !in profiles.data.keys)
                assertTrue(profiles.retired.isEmpty())
            }
        }

    /**
     * 强持有：拆除进行中协调器必须强引用该页直到回调结束（iOS r2 S1：只弱引用时释放触发的 pagehide 无人等）。测试侧只留弱引用，
     * 拆除回调由协调器自己给出、测试原样保存（不捕获页面）。
     */
    @Test
    fun i1DocumentIsStronglyHeldUntilRetireSettles() =
        runTest {
            fixture().use { f ->
                f.baseline()
                val retires = mutableListOf<(Boolean) -> Unit>()
                var document: BareDocument? = BareDocument(retires)
                f.sync.attach(checkNotNull(document)) // 登记句柄不保存：它会间接引用该页
                val weak = WeakReference(document)
                f.change()
                runCurrent()
                advanceTimeBy(Fx25AuthFixture.TIMEOUT_MS / 2)
                runCurrent()
                assertEquals("A 已确认，开始拆", 1, retires.size)
                @Suppress("UNUSED_VALUE")
                document = null
                repeat(GC_ROUNDS) {
                    System.gc()
                    runCurrent()
                }
                assertNotNull("拆除确认前协调器仍强持有该页", weak.get())
                retires.single()(true)
                runCurrent()
            }
        }

    /** 不捕获任何测试状态的最小页面：A 立即确认，拆除回调交给测试保存。 */
    private class BareDocument(
        private val retires: MutableList<(Boolean) -> Unit>,
    ) : TrustedAuthDocument {
        override val currentTrusted = true
        override val tokenDelivered = true
        override val destroyed = false

        override fun isolate() = Unit

        override fun notifyAuthChanged(
            data: kotlinx.serialization.json.JsonObject,
            completed: (Boolean) -> Unit,
        ) = completed(true)

        override fun retire(completed: (Boolean) -> Unit) {
            retires += completed
        }

        override fun rebuild(
            store: String,
            completed: (Boolean) -> Unit,
        ) = completed(true)

        override fun releaseAfterRebuild() = Unit
    }

    private companion object {
        const val GC_ROUNDS = 5

        /** 判定线程同步返回的等待上限（满足即返回）。 */
        const val JOIN_MS = 10_000L
    }
}
