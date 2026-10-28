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
 * P 路径矩阵（I2）：失败点 {拆除（about:blank / destroy 确认）、换配置文件（getOrCreateProfile 抛错 / 记名写失败 = false）、
 * 新建（setProfile / 新文档）} × 第 1/2 轮 × false/抛错/无完成信号 × 单次/持续失败。失败一律：不解除隔离、没有成功的新文档；
 * 拆或换失败时待清标记保留且不建新页；迟到的 true 不解锁；单次失败后重试重跑整事务并恢复，之后再失败不复用上一轮成功位。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class Fx25ProfileFailureTest(
    private val step: String,
    private val failingRound: Int,
    private val mode: String,
    private val persistent: Boolean,
) {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun i2NoUnconfirmedProfileStepCanReleaseAndRetryMustRemainFailClosed() =
        runTest {
            Fx25AuthFixture.create(this, temporary.newFolder(), Fx25StoreMode.PROFILE).use { f ->
                f.baseline()
                val page = f.page()
                if (failingRound == 2) {
                    f.change()
                    f.completeTransaction()
                }
                val released = successfulRebuilds(page)
                arm(f, page, true)
                f.change(if (failingRound == 1) Fx25AuthFixture.B else Fx25AuthFixture.A)
                val stalled = fail(f, page)
                assertBlocked(f, page, released)
                if (mode == "missing") {
                    stalled?.complete(true)
                    runCurrent()
                    assertBlocked(f, page, released)
                }
                if (!persistent) arm(f, page, false)
                f.sync.retry()
                if (persistent) {
                    fail(f, page)
                    assertBlocked(f, page, released)
                } else {
                    f.completeTransaction()
                    assertEquals(released + 1, successfulRebuilds(page))
                    assertTrue(!page.isolated)
                    arm(f, page, true)
                    f.change(if (failingRound == 1) Fx25AuthFixture.A else Fx25AuthFixture.B)
                    fail(f, page)
                    assertBlocked(f, page, released + 1)
                }
            }
        }

    private fun successfulRebuilds(page: Fx25Document) = page.releases

    private fun arm(
        f: Fx25AuthFixture,
        page: Fx25Document,
        on: Boolean,
    ) {
        val throwing = on && mode == "throw"
        page.throwOnRetire = throwing && step == "retire"
        f.profiles.throwing = throwing && step == "rotate"
        page.throwOnRebuild = throwing && step == "rebuild"
    }

    /** 推进到失败点并让它失败（false）、抛错（已由 [arm] 注入）或永不回调；返回被丢掉的那个回调。 */
    private fun fail(
        f: Fx25AuthFixture,
        page: Fx25Document,
    ): Fx25Completion? {
        f.acknowledgeEvents()
        var target: Fx25Completion? = null
        if (step == "retire") {
            target = page.retires.lastOrNull()?.takeIf { !it.completed }
        } else {
            f.retireAll()
            if (step == "rotate") {
                target =
                    f.profiles.rotations
                        .lastOrNull()
                        ?.takeIf { !it.completed }
            } else {
                f.rotate()
                target =
                    page.rebuilds
                        .lastOrNull()
                        ?.completion
                        ?.takeIf { !it.completed }
            }
        }
        if (mode != "throw") assertNotNull("$step 必须真的发起", target)
        if (mode == "false") target?.complete(false)
        runCurrent(f)
        f.scope.advanceTimeBy(Fx25AuthFixture.BEYOND_ANY_STEP_MS)
        runCurrent(f)
        return if (mode == "missing") target else null
    }

    private fun runCurrent(f: Fx25AuthFixture) = f.scope.runCurrent()

    private fun assertBlocked(
        f: Fx25AuthFixture,
        page: Fx25Document,
        released: Int,
    ) {
        assertTrue("$step/$mode 保持隔离", page.isolated)
        assertEquals("$step/$mode 没有被解除隔离的新文档", released, page.releases)
        if (step != "rebuild") {
            assertNotNull("$step/$mode 待清责任仍持久存在", f.journal.peek())
            assertEquals("拆 / 换失败时不建新页", released, page.rebuilds.size)
        }
        assertEquals(false, f.settled())
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "P_{0}_round{1}_{2}_persistent{3}")
        fun failures(): List<Array<Any>> =
            listOf("retire", "rotate", "rebuild").flatMap { step ->
                listOf(1, 2).flatMap { round ->
                    listOf("false", "throw", "missing").flatMap { mode ->
                        listOf(false, true).map { persistent -> arrayOf(step, round, mode, persistent) }
                    }
                }
            }
    }
}
