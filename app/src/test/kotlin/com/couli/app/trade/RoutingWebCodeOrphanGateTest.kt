package com.couli.app.trade

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.routing.AppRouting
import com.couli.app.routing.RoutingPrivacyHooks
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.router.PrivacyState
import com.couli.core.router.SessionState
import com.couli.core.router.UpdateCheck
import com.couli.feature.trade.WebCodeOrphanStartup
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * FU-AND-TB-WEBCODE-B 修改 2（评审 r1 F1）：生产的启动闸门 [RoutingWebCodeOrphanGate] 直接读真实的 [AppRouting]（设计稿 §6.4 第 1 步；03 §4.4；
 * D10-3681 B-4）。虚拟时钟（runTest 的调度器），等待上限 5 秒：
 * - 版本检查仍在进行（CHECKING）等满 5 秒、或结果未知（UNKNOWN）→ VERSION_UNKNOWN（不是 READY）；5 秒内有结果就按结果判，不等满；
 * - 低于最低版本 → BELOW_MIN_VERSION；隐私不是已同意（基本模式；没结论等满 5 秒）→ BASIC_MODE；
 * - 没有会员会话（ApiClient 没有账号、守卫是游客 / 受限、撤回同意的清理没完成）→ LOGGED_OUT；ApiClient 有账号而守卫还是游客时先等会话恢复；
 * - 环境闸门没 settled（等满 5 秒）→ VERSION_UNKNOWN；
 * - 全部满足 → READY，不等。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class RoutingWebCodeOrphanGateTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var account: String? = "user-1"

    private fun environment(settled: Boolean = true): EnvironmentGate =
        EnvironmentGate { EnvironmentStartupReconciler.Outcome.UNCHANGED }.also { if (settled) it.settleAtStartup() }

    private fun routing(
        environment: EnvironmentGate = environment(),
        privacy: PrivacyState = PrivacyState.CONSENTED,
        update: UpdateCheck = UpdateCheck.OK,
        session: SessionState = SessionState.LOGGED_IN,
        hooks: RoutingPrivacyHooks = RoutingPrivacyHooks(),
    ): AppRouting =
        AppRouting(context, environment, initialPrivacy = privacy, privacyHooks = hooks, initialUpdate = update).also {
            it.session.value = session
        }

    private fun gate(routing: AppRouting) = RoutingWebCodeOrphanGate(routing, account = { account })

    /** 在虚拟时间 0 起问一次闸门（等待上限 [WAIT]），跑完当前能跑的。 */
    private fun TestScope.ask(routing: AppRouting): Deferred<WebCodeOrphanStartup> {
        val gate = gate(routing)
        return async { gate.await(WAIT) }.also { runCurrent() }
    }

    /** 不用等的情形：当场给出结论（虚拟时间没走）。 */
    private fun TestScope.assertAtOnce(
        name: String,
        expected: WebCodeOrphanStartup,
        routing: AppRouting,
    ) {
        val started = testScheduler.currentTime
        val running = ask(routing)
        assertTrue("$name：当场给出结论，不等", running.isCompleted)
        assertEquals("$name：当场给出结论，不等", started, testScheduler.currentTime)
        assertEquals(name, expected, running.getCompleted())
    }

    /** 要等的情形：5 秒内一直不给结论，等满 5 秒给出 [expected]。 */
    private fun TestScope.assertAfterTheFullWait(
        name: String,
        expected: WebCodeOrphanStartup,
        routing: AppRouting,
    ) {
        val running = ask(routing)
        advanceTimeBy(WAIT - 1)
        runCurrent()
        assertFalse("$name：5 秒之内还在等", running.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertTrue("$name：最多等 5 秒", running.isCompleted)
        assertEquals("$name：等满 5 秒后的结论", expected, running.getCompleted())
    }

    // ---------------------------------------------------------------- 版本检查（B-4）

    @Test
    fun g01_aVersionCheckStillRunningAfterFiveSecondsIsVersionUnknown() =
        runTest {
            // D10-3681 B-4：冷启动时版本检查 5 秒内仍未知 → 不是 READY（处理器据此不提交 bindings、丢弃回跳）
            assertAfterTheFullWait("CHECKING 一直不变", WebCodeOrphanStartup.VERSION_UNKNOWN, routing(update = UpdateCheck.CHECKING))
        }

    @Test
    fun g02_aFailedVersionCheckIsVersionUnknownWithoutWaiting() =
        runTest {
            assertAtOnce("检查失败（UNKNOWN）", WebCodeOrphanStartup.VERSION_UNKNOWN, routing(update = UpdateCheck.UNKNOWN))
        }

    @Test
    fun g03_aVersionCheckThatFinishesWithinFiveSecondsIsJudgedByItsResult() =
        runTest {
            val ok = routing(update = UpdateCheck.CHECKING)
            val toOk = ask(ok)
            advanceTimeBy(1_200L)
            ok.update.value = UpdateCheck.OK
            advanceTimeBy(POLL)
            runCurrent()
            assertTrue("检查有结果就不再等", toOk.isCompleted)
            assertTrue("没等满 5 秒：${testScheduler.currentTime}", testScheduler.currentTime < WAIT)
            assertEquals("检查结果 OK → READY", WebCodeOrphanStartup.READY, toOk.getCompleted())

            val failed = routing(update = UpdateCheck.CHECKING)
            val started = testScheduler.currentTime
            val toUnknown = ask(failed)
            advanceTimeBy(1_200L)
            failed.update.value = UpdateCheck.UNKNOWN
            advanceTimeBy(POLL)
            runCurrent()
            assertTrue("检查失败也不再等", toUnknown.isCompleted)
            assertTrue("没等满 5 秒", testScheduler.currentTime - started < WAIT)
            assertEquals("检查失败 → VERSION_UNKNOWN（不是 READY）", WebCodeOrphanStartup.VERSION_UNKNOWN, toUnknown.getCompleted())
        }

    @Test
    fun g04_belowTheMinimumVersionIsBelowMinVersion() =
        runTest {
            assertAtOnce("低于最低版本", WebCodeOrphanStartup.BELOW_MIN_VERSION, routing(update = UpdateCheck.BELOW_MIN))
        }

    @Test
    fun g05_belowRecommendedIsStillReady() =
        runTest {
            assertAtOnce("低于建议版本（不是强更）", WebCodeOrphanStartup.READY, routing(update = UpdateCheck.BELOW_RECOMMENDED))
        }

    // ---------------------------------------------------------------- 隐私

    @Test
    fun g06_basicModeIsBasicMode() =
        runTest {
            assertAtOnce("基本模式", WebCodeOrphanStartup.BASIC_MODE, routing(privacy = PrivacyState.BASIC_MODE))
        }

    @Test
    fun g07_privacyWithoutAConclusionWaitsFiveSecondsThenIsBasicMode() =
        runTest {
            assertAfterTheFullWait("隐私还没结论", WebCodeOrphanStartup.BASIC_MODE, routing(privacy = PrivacyState.NOT_DECIDED))
        }

    // ---------------------------------------------------------------- 会话

    @Test
    fun g08_noAccountOrAGuestOrRestrictedSessionIsLoggedOut() =
        runTest {
            account = null
            assertAtOnce("ApiClient 没有账号（守卫仍是已登录）", WebCodeOrphanStartup.LOGGED_OUT, routing(session = SessionState.LOGGED_IN))
            assertAtOnce("ApiClient 没有账号、守卫是游客", WebCodeOrphanStartup.LOGGED_OUT, routing(session = SessionState.GUEST))
            account = "user-1"
            assertAtOnce("守卫是受限会话", WebCodeOrphanStartup.LOGGED_OUT, routing(session = SessionState.RESTRICTED))
        }

    @Test
    fun g09_anAccountWhoseSessionIsNeverPublishedToTheGuardWaitsFiveSecondsThenIsLoggedOut() =
        runTest {
            assertAfterTheFullWait("ApiClient 有账号、守卫一直是游客", WebCodeOrphanStartup.LOGGED_OUT, routing(session = SessionState.GUEST))
        }

    @Test
    fun g10_anAccountWaitsForTheSessionToBeRestoredThenIsReady() =
        runTest {
            val restoring = routing(session = SessionState.GUEST)
            val running = ask(restoring)
            advanceTimeBy(300L)
            runCurrent()
            assertFalse("ApiClient 有账号、守卫还是游客：先等会话恢复，不判未登录", running.isCompleted)
            restoring.session.value = SessionState.LOGGED_IN
            advanceTimeBy(POLL)
            runCurrent()
            assertTrue("会话恢复了就不再等", running.isCompleted)
            assertTrue("没等满 5 秒：${testScheduler.currentTime}", testScheduler.currentTime < WAIT)
            assertEquals("会话恢复为已登录 → READY", WebCodeOrphanStartup.READY, running.getCompleted())
        }

    @Test
    fun g11_credentialsUnreadableDuringWithdrawalCleanupIsLoggedOut() =
        runTest {
            // 撤回同意的本地清理没完成：守卫按游客（R1-01），会话一直不算恢复
            val cleaning = routing(hooks = RoutingPrivacyHooks(credentialsReadable = { false }))
            assertAfterTheFullWait("撤回同意的清理没完成", WebCodeOrphanStartup.LOGGED_OUT, cleaning)
        }

    @Test
    fun g12_aPhoneBoundSessionIsReady() =
        runTest {
            assertAtOnce("已绑手机的会员会话", WebCodeOrphanStartup.READY, routing(session = SessionState.PHONE_BOUND))
        }

    // ---------------------------------------------------------------- 环境闸门

    @Test
    fun g13_anEnvironmentGateNeverSettledWaitsFiveSecondsThenIsVersionUnknown() =
        runTest {
            assertAfterTheFullWait("环境闸门没对账", WebCodeOrphanStartup.VERSION_UNKNOWN, routing(environment = environment(settled = false)))
        }

    @Test
    fun g14_anEnvironmentSwitchThatEndsWithinFiveSecondsIsReady() =
        runTest {
            val environment = environment().also { it.closeForSwitch() }
            val running = ask(routing(environment = environment))
            advanceTimeBy(800L)
            runCurrent()
            assertFalse("切换环境期间闸门关着：等", running.isCompleted)
            environment.reopenAfterSwitch()
            advanceTimeBy(POLL)
            runCurrent()
            assertTrue("闸门重新打开就不再等", running.isCompleted)
            assertEquals(WebCodeOrphanStartup.READY, running.getCompleted())
        }

    @Test
    fun g15_anEnvironmentSwitchStillInProgressAfterFiveSecondsIsVersionUnknown() =
        runTest {
            val environment = environment().also { it.closeForSwitch() }
            assertAfterTheFullWait("切换环境一直没结束", WebCodeOrphanStartup.VERSION_UNKNOWN, routing(environment = environment))
        }

    // ---------------------------------------------------------------- 全部满足

    @Test
    fun g16_everythingSettledIsReadyWithoutWaiting() =
        runTest {
            assertAtOnce("环境 settled、已同意、已登录、版本 OK", WebCodeOrphanStartup.READY, routing())
        }

    private companion object {
        /** 等待上限：与处理器问闸门时给的一致（WebCodeOrphanHandler.VERSION_WAIT_MILLIS，03 §4.4）。 */
        const val WAIT = 5_000L

        /** 闸门轮询环境闸门的间隔（RoutingWebCodeOrphanGate 的缺省值）。 */
        const val POLL = 100L
    }
}
