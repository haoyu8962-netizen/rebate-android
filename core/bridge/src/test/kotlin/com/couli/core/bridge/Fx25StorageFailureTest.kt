package com.couli.core.bridge

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * F 路径矩阵（P09/P11，I2）：四类 × 第 1/2 轮 × false/抛错/无完成信号 × 单次/持续失败。旧页均已拆，故任一类没确认就不得 rebuild、
 * 标记保留、页面保持隔离；迟到的 true 不解锁；恢复成功后再失败不复用上一轮的成功位。P 路径矩阵见 [Fx25ProfileFailureTest]。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class Fx25StorageFailureTest(
    private val kind: TrustedWebDataKind,
    private val failingRound: Int,
    private val mode: String,
    private val persistent: Boolean,
) {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun i2NoUnconfirmedClearingCanRebuildAndRetryMustRemainFailClosed() =
        runTest {
            Fx25AuthFixture.create(this, temporary.newFolder(), Fx25StoreMode.CLEARING).use { f ->
                f.baseline()
                val page = f.page()
                if (failingRound == 2) {
                    f.change()
                    f.completeTransaction()
                }
                val previousRebuilds = page.rebuilds.size
                val previousEvents = page.events.size
                f.cleaner.values.keys
                    .forEach { f.cleaner.values[it] = "old-owner" }
                if (mode == "throw") f.cleaner.throwing = kind
                f.change(if (failingRound == 1) Fx25AuthFixture.B else Fx25AuthFixture.A)
                f.acknowledgeEvents()
                f.retireAll()
                val stalled = failTarget(f)
                assertBlocked(f, previousRebuilds)
                if (mode == "missing") {
                    // 过超时才交回的 true 不是成功，更不能解锁本轮。
                    stalled?.callback?.invoke(true)
                    runCurrent()
                    assertBlocked(f, previousRebuilds)
                }
                if (!persistent) f.cleaner.throwing = null
                f.sync.retry()
                if (persistent) {
                    f.acknowledgeEvents()
                    f.retireAll()
                    failTarget(f)
                    assertBlocked(f, previousRebuilds)
                    assertEquals(previousEvents + 1, page.events.size)
                } else {
                    f.completeTransaction()
                    assertEquals(previousRebuilds + 1, page.rebuilds.size)
                    assertEquals(previousEvents + 1, page.events.size)
                    // 恢复成功后再次失败，不能复用上轮的 settled / clear 成功位。
                    f.cleaner.values.keys
                        .forEach { f.cleaner.values[it] = "another-owner" }
                    if (mode == "throw") f.cleaner.throwing = kind
                    f.change(if (failingRound == 1) Fx25AuthFixture.A else Fx25AuthFixture.B)
                    f.acknowledgeEvents()
                    f.retireAll()
                    failTarget(f)
                    assertBlocked(f, previousRebuilds + 1)
                }
            }
        }

    private fun failTarget(f: Fx25AuthFixture): Fx25Cleaner.Request? {
        var target: Fx25Cleaner.Request? = null
        f.scope.advanceTimeBy(ClearingTrustedWebStore.GRACE_MS + 1)
        f.scope.runCurrent()
        repeat(TrustedWebDataKind.entries.size + 1) {
            f.cleaner.requests.filter { !it.completed }.toList().forEach { request ->
                if (request.kind == kind) {
                    target = request
                    if (mode == "false") f.cleaner.complete(request, false)
                } else {
                    f.cleaner.complete(request, true)
                }
            }
            f.scope.runCurrent()
        }
        if (mode != "throw") assertNotNull("必须请求专项 $kind 清理，不能漏项", target)
        f.scope.advanceTimeBy(Fx25AuthFixture.BEYOND_ANY_STEP_MS)
        f.scope.runCurrent()
        // 模拟底层已经结束，但 completion 丢失；后续重试不可把旧 callback 当新尝试。
        target?.completed = true
        return target
    }

    private fun assertBlocked(
        f: Fx25AuthFixture,
        previousRebuilds: Int,
    ) {
        assertNotNull("$kind/$mode 待清责任仍持久存在", f.journal.peek())
        assertTrue(f.pages.all { it.isolated })
        assertEquals(
            previousRebuilds,
            f.pages
                .single()
                .rebuilds.size,
        )
        assertEquals(false, f.settled())
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "F_{0}_round{1}_{2}_persistent{3}")
        fun failures(): List<Array<Any>> =
            TrustedWebDataKind.entries.flatMap { kind ->
                listOf(1, 2).flatMap { round ->
                    listOf("false", "throw", "missing").flatMap { mode ->
                        listOf(false, true).map { persistent -> arrayOf(kind, round, mode, persistent) }
                    }
                }
            }
    }
}
