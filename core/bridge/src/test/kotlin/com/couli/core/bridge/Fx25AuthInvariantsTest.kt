package com.couli.core.bridge

import com.couli.core.network.MemorySessionTokens
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * v3 不变式（D10-309 / D10-310）：先拆后换存储再建；P / F 两条路径各跑一遍同一组断言。
 * 夹具在 rotate / 清理入口断言「全部旧页已拆」，在 rebuild 入口断言「标记已删、归属已写、存储已换」，故每个走完事务的用例都同时守这两条顺序。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
@Suppress("LargeClass") // 不变式逐条一个用例
class Fx25AuthInvariantsTest(
    private val mode: Fx25StoreMode,
) {
    @get:Rule
    val temporary = TemporaryFolder()

    private fun TestScope.fixture(
        dir: File = temporary.newFolder(),
        profiles: Fx25ProfileStore? = null,
        seedOwner: Boolean = true,
        ownerUnreadableAtStart: Boolean = false,
    ) = Fx25AuthFixture.create(this, dir, mode, profiles, seedOwner, ownerUnreadableAtStart)

    private fun Fx25AuthFixture.rotationRequests(): Int =
        if (mode == Fx25StoreMode.PROFILE) profiles.rotations.size else cleaner.requests.size

    private fun Fx25AuthFixture.firstRotationStep(): Int = trace.indexOfFirst { it == "store:rotate" || it.startsWith("clear:start:") }

    @Test
    fun i1IsolationAndDurableIntentExistBeforeObserveReturns() =
        runTest {
            fixture().use { f ->
                f.baseline()
                val pages = listOf(f.page(), f.page("under-login"), f.page("background"))
                f.journal.beforeMark = { assertTrue("持久 I/O 入口前已隔离全体文档", pages.all { it.isolated }) }
                f.change()
                // 刻意不 runCurrent：延迟到 launch / 下个主线程消息才隔离必须红。
                assertTrue(pages.all { it.isolated })
                assertEquals(Fx25AuthFixture.A, f.journal.peek())
                f.completeTransaction()
                assertTrue(pages.none { it.isolated })
                val mark = f.trace.indexOf("journal:mark")
                val firstRetire = f.trace.indexOfFirst { it.endsWith(":retire") }
                val lastRetired = f.trace.indexOfLast { it.endsWith(":retired") }
                val rotation = f.firstRotationStep()
                val ownerWrite = f.trace.indexOf("owner:write:user-b")
                val clear = f.trace.indexOf("journal:clear")
                val firstRebuild = f.trace.indexOfFirst { it.contains(":rebuild:") }
                assertTrue(f.trace.toString(), mark in 0 until firstRetire)
                assertTrue("全部拆除确认先于任何换存储 / 清理：${f.trace}", lastRetired in 0 until rotation)
                assertTrue("换存储 → 写归属 → 删标记 → 重建：${f.trace}", rotation < ownerWrite && ownerWrite < clear && clear < firstRebuild)
                assertEquals(Fx25AuthFixture.B.account, f.owner.peek()?.account)
                assertNull(f.journal.peek())
            }
        }

    @Test
    fun i1OneUnconfirmedRetireBlocksEveryRotateOrClear() =
        runTest {
            fixture().use { f ->
                f.baseline()
                val first = f.page()
                val second = f.page("covered")
                f.change()
                f.acknowledgeEvents()
                assertEquals(1, first.retires.size)
                assertEquals(1, second.retires.size)
                first.retires.single().complete(true)
                runCurrent()
                advanceTimeBy(ClearingTrustedWebStore.GRACE_MS + 1)
                runCurrent()
                assertEquals("还有一页没确认销毁，不得换存储或清理", 0, f.rotationRequests())
                assertTrue(second.rebuilds.isEmpty() && first.rebuilds.isEmpty())
                second.retires.single().complete(true)
                runCurrent()
                f.completeTransaction()
                assertTrue(f.pages.none { it.isolated })
            }
        }

    @Test
    fun i1RebuildHappensOnTheFreshStoreOnlyAfterSuccessfulRotation() =
        runTest {
            fixture().use { f ->
                f.baseline()
                val page = f.page()
                val before = f.store.current()
                f.change()
                f.acknowledgeEvents()
                f.retireAll()
                runCurrent()
                assertTrue("换存储结算前不重建", page.rebuilds.isEmpty())
                f.rotate()
                f.rebuildAll()
                val rebuild = page.rebuilds.single()
                assertEquals(f.store.current(), rebuild.store)
                if (mode == Fx25StoreMode.PROFILE) {
                    assertNotEquals("P 路径：新配置文件", before, rebuild.store)
                } else {
                    assertEquals("F 路径：仍是默认配置文件（已清空）", ClearingTrustedWebStore.DEFAULT_STORE, rebuild.store)
                }
                assertFalse(page.isolated)
            }
        }

    @Test
    fun i7AllMountedDocumentsReceiveExactlyOneMinimalPayloadBeforeRetire() =
        runTest {
            fixture().use { f ->
                f.baseline()
                val pages = listOf(f.page(), f.page("under-login"), f.page("background"))
                f.change()
                f.change()
                f.completeTransaction()
                val expected = buildJsonObject { put("logged_in", true) }
                pages.forEach {
                    assertEquals(listOf(expected), it.events)
                    assertTrue("${it.name}：A 先于拆", f.trace.indexOf("${it.name}:event") < f.trace.indexOf("${it.name}:retire"))
                }
                f.change()
                runCurrent()
                pages.forEach { assertEquals(1, it.retires.size) }
            }
        }

    /** D10-310 (1)：A 限时；未确认也继续拆（旧文档留着才是隐私风险），只记埋点。 */
    @Test
    fun i7UnconfirmedAuthEventTimesOutAndTeardownContinues() =
        runTest {
            fixture().use { f ->
                f.baseline()
                val page = f.page()
                f.change()
                runCurrent()
                assertEquals(1, page.events.size)
                assertTrue("A 在限时内未确认前先不拆", page.retires.isEmpty())
                advanceTimeBy(Fx25AuthFixture.TIMEOUT_MS + 1)
                runCurrent()
                assertEquals("A 超时后无论成败都继续拆", 1, page.retires.size)
                assertTrue(TrustedAuthSync.METRIC_AUTH_UNCONFIRMED in f.metrics)
                f.completeTransaction()
                assertFalse(page.isolated)
                assertEquals(1, page.events.size)
            }
        }

    @Test
    fun i7ExplicitAuthScriptFailureDoesNotHoldOldDocumentAlive() =
        runTest {
            fixture().use { f ->
                f.baseline()
                val page = f.page()
                f.change()
                f.acknowledgeEvents(success = false)
                assertEquals("A 明确失败不等满限时、立即拆", 1, page.retires.size)
                assertTrue(TrustedAuthSync.METRIC_AUTH_UNCONFIRMED in f.metrics)
                f.completeTransaction()
                assertFalse(page.isolated)
                assertEquals("失败的 A 不在新文档上补发", 1, page.events.size)
            }
        }

    @Test
    fun i7LogoutAndSubsequentLoginAreTwoTransactions() =
        runTest {
            fixture().use { f ->
                f.baseline()
                val page = f.page()
                f.change(Fx25AuthFixture.GUEST)
                f.completeTransaction()
                assertEquals(null, f.owner.peek()?.account)
                f.change(Fx25AuthFixture.B)
                f.completeTransaction()
                assertEquals(
                    listOf(buildJsonObject { put("logged_in", false) }, buildJsonObject { put("logged_in", true) }),
                    page.events,
                )
                assertEquals(2, page.retires.size)
                assertEquals(2, page.rebuilds.size)
                if (mode == Fx25StoreMode.PROFILE) {
                    assertEquals(2, f.profiles.rotations.size)
                } else {
                    TrustedWebDataKind.entries.forEach { kind -> assertEquals(2, f.cleaner.requests.count { it.kind == kind }) }
                }
            }
        }

    @Test
    fun i7RealTokenRotationAndScopePromotionAreNotIdentityChanges() =
        runTest {
            for (initialScope in listOf("full", "deletion_only")) {
                fixture().use { f ->
                    val tokens = MemorySessionTokens("access-old", "refresh-old", initialScope, "user-a")

                    fun identity() = WebAuthIdentity(true, tokens.userId, tokens.loginEpoch)
                    f.baseline(identity())
                    val page = f.page()
                    val epoch = tokens.loginEpoch
                    assertTrue(tokens.replaceIf(tokens.generation, "access-new", "refresh-new", "full"))
                    assertNotEquals("access-old", tokens.accessToken)
                    assertNotEquals("refresh-old", tokens.refreshToken)
                    assertEquals("full", tokens.scope)
                    assertEquals(epoch, tokens.loginEpoch)
                    f.change(identity())
                    runCurrent()
                    assertTrue(page.events.isEmpty())
                    assertTrue(page.retires.isEmpty())
                    assertFalse(page.isolated)
                    assertEquals(0, f.rotationRequests())
                    assertNull(f.journal.peek())
                }
            }
        }

    @Test
    fun i7SameAccountNewEpochNotifiesWithoutIsolationOrTeardown() =
        runTest {
            fixture().use { f ->
                f.baseline()
                val page = f.page()
                f.change(Fx25AuthFixture.A.copy(loginEpoch = 2L))
                f.acknowledgeEvents()
                assertEquals(listOf(buildJsonObject { put("logged_in", true) }), page.events)
                assertFalse(page.isolated)
                assertTrue(page.retires.isEmpty())
                assertEquals(0, f.rotationRequests())
            }
        }

    @Test
    fun i7UnknownSnapshotDoesNotEraseLastConfirmedIdentity() =
        runTest {
            fixture().use { f ->
                f.baseline()
                val page = f.page()
                f.change(Fx25AuthFixture.UNKNOWN)
                runCurrent()
                assertTrue(page.events.isEmpty())
                assertFalse(page.isolated)
                f.change()
                assertTrue(page.isolated)
                f.completeTransaction()
                assertEquals(1, page.events.size)
                assertEquals(1, page.rebuilds.size)
            }
        }

    /** S0-02：协调器不建手动基线，基线来自持久的归属记录；进程内第一次身份事件是退出登录也必须清。 */
    @Test
    fun i3FirstLogoutInProcessWithoutManualBaselineIsComparedWithOwnerRecord() =
        runTest {
            for (logout in listOf<(Fx25AuthFixture) -> Unit>({ it.change(Fx25AuthFixture.GUEST) }, { it.cleared("logout") })) {
                fixture().use { f ->
                    val page = f.page("opened-before-first-snapshot")
                    assertTrue("身份未比对前首个可信页隔离等待、不加载", page.isolated)
                    assertNull(page.alive)
                    logout(f)
                    assertTrue(page.isolated)
                    assertNotNull("进程内首次退出登录不能被当成基线吞掉", f.journal.peek())
                    f.completeTransaction()
                    assertTrue("退出登录必须换存储 / 清理", f.rotationRequests() > 0)
                    assertTrue("没有加载过的文档不收 A", page.events.isEmpty())
                    assertEquals(null, f.owner.peek()?.account)
                    assertEquals(f.store.current(), page.alive)
                    assertFalse(page.isolated)
                }
            }
        }

    @Test
    fun i3SnapshotMatchingOwnerRecordLoadsWaitingPagesWithoutTeardown() =
        runTest {
            fixture().use { f ->
                val page = f.page("cold")
                assertTrue(page.isolated)
                f.baseline()
                assertEquals(1, page.rebuilds.size)
                assertEquals(f.store.current(), page.alive)
                assertTrue(page.retires.isEmpty())
                assertNull(f.journal.peek())
            }
        }

    @Test
    fun i3OwnerMismatchOrUnreadableOwnerAtStartupCleansWithoutAuthEvent() =
        runTest {
            for (unreadable in listOf(false, true)) {
                fixture(ownerUnreadableAtStart = unreadable).use { f ->
                    val page = f.page("cold")
                    // 不符：归属 A、快照 B；读不出：按不符（快照仍为 A）
                    f.change(if (unreadable) Fx25AuthFixture.A else Fx25AuthFixture.B)
                    f.owner.failRead = false
                    assertNotNull("归属不符 / 读不出必须进补清事务", f.journal.peek())
                    f.completeTransaction()
                    assertTrue(f.rotationRequests() > 0)
                    assertTrue("补清不是身份事件", page.events.isEmpty())
                    assertFalse(page.isolated)
                }
            }
        }

    @Test
    fun i3MissingOwnerRecordOnFirstInstallWritesBaselineWithoutTeardown() =
        runTest {
            fixture(seedOwner = false).use { f ->
                assertNull(f.owner.peek())
                f.baseline()
                assertEquals(Fx25AuthFixture.A.account, f.owner.peek()?.account)
                assertEquals(0, f.rotationRequests())
            }
        }

    /** S1-01：保存新会话之前先持久写待清标记；即使随后在通知协调器前被杀，重启也补清。 */
    @Test
    fun i3BeforeSessionSaveWritesMarkerBeforeTheNewSessionIsStored() =
        runTest {
            val dir = temporary.newFolder()
            val profiles = Fx25ProfileStore(mutableListOf())
            fixture(dir, profiles).use { f ->
                f.baseline()
                val page = f.page()
                assertTrue("同账号不需要标记", f.sync.beforeSessionSave(Fx25AuthFixture.A.account))
                assertNull(f.journal.peek())
                assertFalse(page.isolated)
                assertTrue(f.sync.beforeSessionSave(Fx25AuthFixture.B.account))
                assertEquals("返回前已持久写入旧归属", Fx25AuthFixture.A, f.journal.peek())
                assertTrue("返回前已隔离", page.isolated)
                // 进程在会话写盘之后、onSessionStored 钩子之前被杀：不推进任何事务。
            }
            fixture(dir, profiles).use { after ->
                val cold = after.page("cold")
                assertTrue(cold.isolated)
                after.change(Fx25AuthFixture.B)
                after.completeTransaction()
                assertTrue(after.rotationRequests() > 0)
                assertTrue(cold.events.isEmpty())
                assertFalse(cold.isolated)
                assertEquals(Fx25AuthFixture.B.account, after.owner.peek()?.account)
            }
        }

    @Test
    fun i3BeforeSessionSaveMarkerFailureIsReportedAndStillIsolates() =
        runTest {
            fixture().use { f ->
                f.baseline()
                val page = f.page()
                f.journal.failMark = true
                assertFalse("标记没写成必须如实返回", f.sync.beforeSessionSave(Fx25AuthFixture.B.account))
                assertTrue(page.isolated)
            }
        }

    @Test
    fun i3RestartReopensDurableIntentBeforeAnyTrustedLoadWithoutAuthEvent() =
        runTest {
            val dir = temporary.newFolder()
            val profiles = Fx25ProfileStore(mutableListOf())
            fixture(dir, profiles).use { before ->
                before.baseline()
                before.page()
                before.change()
                before.acknowledgeEvents()
            }
            fixture(dir, profiles).use { after ->
                val page = after.page("cold-start")
                assertTrue("attach 返回前已隔离", page.isolated)
                after.change(Fx25AuthFixture.B)
                runCurrent()
                assertTrue(page.rebuilds.isEmpty())
                after.completeTransaction()
                assertTrue(page.events.isEmpty())
                assertEquals(1, page.rebuilds.size)
                assertFalse(page.isolated)
                assertNull(after.journal.peek())
            }
        }

    @Test
    fun i3JournalWriteOrClearFailureNeverRebuilds() =
        runTest {
            for (failMark in listOf(true, false)) {
                fixture().use { f ->
                    f.baseline()
                    val page = f.page()
                    f.journal.failMark = failMark
                    f.journal.failClear = !failMark
                    f.change()
                    assertTrue(page.isolated)
                    f.completeTransaction()
                    assertTrue(page.rebuilds.isEmpty())
                    assertTrue(page.isolated)
                    if (failMark) assertEquals("标记写成之前不换存储", 0, f.rotationRequests())
                    assertEquals(false, f.settled())
                }
            }
        }

    @Test
    fun i3OwnerRecordWriteFailureNeverRebuildsAndRetryRecovers() =
        runTest {
            fixture().use { f ->
                f.baseline()
                val page = f.page()
                f.owner.failWrite = true
                f.change()
                f.completeTransaction()
                assertTrue(page.rebuilds.isEmpty())
                assertTrue(page.isolated)
                assertNotNull(f.journal.peek())
                f.owner.failWrite = false
                f.sync.retry()
                f.completeTransaction()
                assertFalse(page.isolated)
                assertEquals(Fx25AuthFixture.B.account, f.owner.peek()?.account)
            }
        }

    @Test
    fun i4NewPagesDuringTransactionAndAfterFailureStayBlockedAndAllRecoverTogether() =
        runTest {
            fixture().use { f ->
                f.baseline()
                val original = f.page()
                f.change()
                f.acknowledgeEvents()
                val during = f.page("opened-during-teardown")
                assertTrue(during.isolated)
                f.retireAll()
                f.rotate(success = false)
                runCurrent()
                val afterFailure = f.page("opened-after-failure")
                assertTrue(afterFailure.isolated)
                assertTrue(f.pages.all { it.rebuilds.isEmpty() && it.isolated })
                f.sync.retry()
                f.completeTransaction()
                listOf(original, during, afterFailure).forEach {
                    assertEquals("${it.name} 同一轮在新存储上恢复", 1, it.rebuilds.size)
                    assertEquals(f.store.current(), it.alive)
                }
                assertTrue(f.pages.none { it.isolated })
                assertEquals("重试不能重复 A", 1, original.events.size)
            }
        }

    @Test
    fun i5ExplicitClearWorksWithClosedCredentialGateAndNoReadableBaseline() =
        runTest {
            for (reason in listOf("environment_switch", "privacy_withdrawal", "logout", "account_deleted", "10404", "refresh_rejected")) {
                fixture().use { f ->
                    val page = f.page(reason)
                    f.sync.observe(Fx25AuthFixture.UNKNOWN)
                    f.cleared(reason)
                    assertTrue("$reason 不得因 unknown 被吞", page.isolated)
                    assertNotNull(f.journal.peek())
                    f.completeTransaction()
                    assertTrue(f.rotationRequests() > 0)
                    assertEquals(f.store.current(), page.alive)
                    assertFalse(page.isolated)
                }
            }
        }

    @Test
    fun i5ExplicitClearOfLoadedSessionNotifiesLoggedOutOnce() =
        runTest {
            fixture().use { f ->
                f.baseline()
                val page = f.page()
                f.cleared("logout")
                f.completeTransaction()
                assertEquals(listOf(buildJsonObject { put("logged_in", false) }), page.events)
                assertEquals(1, page.rebuilds.size)
            }
        }

    @Test
    fun i7IneligibleDocumentsNeverReceiveAuthEvent() =
        runTest {
            fixture().use { f ->
                f.baseline()
                val untrusted = f.page("revoked-origin").apply { currentTrusted = false }
                val noToken = f.page("token-not-delivered").apply { tokenDelivered = false }
                val destroyed = f.page("destroyed").apply { closeByUser() }
                f.change()
                f.completeTransaction()
                listOf(untrusted, noToken, destroyed).forEach { assertTrue(it.events.isEmpty()) }
                assertTrue(destroyed.retires.isEmpty() && destroyed.rebuilds.isEmpty())
            }
        }

    /** 06:03 提醒：第一个协调器判不符、补清进行中再建第二个；第二个的页在补清完成前保持隔离，不得把「跳过核对」当「一致」。 */
    @Test
    fun i3SecondCoordinatorKeepsPagesIsolatedUntilFirstReconciliationCleanupCompletes() =
        runTest {
            val dir = temporary.newFolder()
            val profiles = Fx25ProfileStore(mutableListOf())
            fixture(dir, profiles).use { first ->
                val firstPage = first.page("first")
                first.change(Fx25AuthFixture.B)
                first.acknowledgeEvents()
                assertNotNull(first.journal.peek())
                fixture(dir, profiles).use { second ->
                    val secondPage = second.page("second")
                    assertTrue("第一个已判不符、补清未完成：第二个的页 attach 即隔离", secondPage.isolated)
                    second.change(Fx25AuthFixture.B)
                    runCurrent()
                    assertTrue(secondPage.isolated)
                    assertNull("补清完成前第二个的页不加载", secondPage.alive)
                    first.completeTransaction()
                    assertFalse(firstPage.isolated)
                    second.sync.retry()
                    second.completeTransaction()
                    assertFalse(secondPage.isolated)
                    assertEquals(Fx25AuthFixture.B.account, second.owner.peek()?.account)
                }
            }
        }

    /** D10-310 (4)：冷启动快照久未知，隔离等待有上限，之后按失败结算（外层显示可重试错误层），不加载。 */
    @Test
    fun i3UnknownSnapshotWaitIsCappedThenRetryable() =
        runTest {
            fixture().use { f ->
                val page = f.page("cold-unknown")
                var settled: Boolean? = null
                f.sync.whenSettled { settled = it }
                runCurrent()
                assertNull("上限内仍在等待", settled)
                advanceTimeBy(Fx25AuthFixture.TIMEOUT_MS + 1)
                runCurrent()
                assertEquals("未知超过上限按失败结算", false, settled)
                assertTrue(TrustedAuthSync.METRIC_IDENTITY_UNKNOWN in f.metrics)
                assertTrue(page.isolated)
                assertNull(page.alive)
                assertTrue(page.rebuilds.isEmpty())
                f.sync.retry()
                f.baseline()
                assertFalse(page.isolated)
                assertEquals(true, f.settled())
            }
        }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun modes(): List<Array<Any>> = Fx25StoreMode.entries.map { arrayOf(it) }
    }
}
