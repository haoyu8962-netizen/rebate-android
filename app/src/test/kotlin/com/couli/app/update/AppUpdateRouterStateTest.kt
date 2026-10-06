package com.couli.app.update

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.app.routing.AppRouting
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.router.EntryGuard
import com.couli.core.router.GuardKind
import com.couli.core.router.GuardReason
import com.couli.core.router.PrivacyState
import com.couli.core.router.RouteRequest
import com.couli.core.router.RouteSource
import com.couli.core.router.SharedPreferencesPendingBackend
import com.couli.core.router.StartupIntent
import com.couli.core.router.StartupIntentQueue
import com.couli.core.router.StartupReadiness
import com.couli.core.router.UpdateCheck
import com.couli.core.update.MemoryUpdateStore
import com.couli.core.update.UpdateCheckOutcome
import com.couli.core.update.UpdateCheckResult
import com.couli.core.update.UpdateGate
import com.couli.core.update.UpdateStatus
import com.couli.core.update.UpdateStoreListing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * F-AND-08：本进程已有成功结论（ok）后回前台复查，复查进行中 Router 按 checking——外部入口与待办恢复 defer（update_checking），
 * 不用本进程旧的 ok 放行；App 内操作不等。
 * F-AND-09（APP-02c rule-gaps 编排者裁定）：复查失败后界面可保留旧结论，但交给 Router 的是 unknown（外部入口受未知限制），
 * 直到下一次成功检查。below_min 不受影响。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class AppUpdateRouterStateTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var now = 10_000_000L
    private val answers = ArrayDeque<CompletableDeferred<UpdateCheckOutcome>>()
    private val bridgeAgentChat = RouteRequest("AgentChat", JsonObject(emptyMap()), RouteSource.BRIDGE)
    private val inAppOrders = RouteRequest("OrderList", JsonObject(emptyMap()), RouteSource.IN_APP)

    private object NoStores : StoreAccess {
        override val installSource: String? = null

        override fun canOpen(storeKey: String) = false

        override fun open(storeKey: String) = false
    }

    @Before
    fun clearPending() {
        SharedPreferencesPendingBackend(context).delete()
    }

    private fun result(min: String) =
        UpdateCheckResult(
            minSupported = min,
            recommended = min,
            latest = "1.2.0",
            defaultStore = "huawei",
            stores = listOf(UpdateStoreListing("huawei", "1.2.0")),
        )

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun answerNext(outcome: UpdateCheckOutcome) {
        answers.removeFirst().complete(outcome)
        idle()
    }

    private fun wire(): Pair<AppUpdate, AppRouting> {
        val store = MemoryUpdateStore()
        val gate =
            UpdateGate(store, clock = { now }, appVersion = "1.0.0", platform = "android", channel = "official", installSource = null)
        val update =
            AppUpdate(
                gate = gate,
                store = store,
                checker = { CompletableDeferred<UpdateCheckOutcome>().also { answers.addLast(it) }.await() },
                stores = NoStores,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                canCheck = { true },
                clock = { now },
            )
        update.coldStart()
        val environment = EnvironmentGate { EnvironmentStartupReconciler.Outcome.UNCHANGED }.also { it.settleAtStartup() }
        val routing =
            AppRouting(context, environment, clock = { now }, initialPrivacy = PrivacyState.CONSENTED, initialUpdate = update.routerUpdate)
        update.onRouterUpdate { routing.update.value = it }
        return update to routing
    }

    private fun AppRouting.decide(request: RouteRequest) = EntryGuard.decide(request, guardState())

    @Test
    fun foregroundRecheckInFlightIsCheckingAndFailureIsUnknownForRouter() {
        val (update, routing) = wire()
        assertEquals(UpdateCheck.CHECKING, routing.update.value)
        answerNext(UpdateCheckOutcome.Success(result("1.0.0")))
        assertEquals(UpdateCheck.OK, routing.update.value)

        // 回前台、超过复查间隔：复查进行中
        now += (UpdateGate.DEFAULT_RECHECK_INTERVAL_SEC + 1) * 1000L
        update.onForeground()
        assertEquals(UpdateCheck.CHECKING, routing.update.value)
        assertEquals("界面仍显示已知结论", UpdateStatus.OK, update.ui.value.status)
        val waiting = routing.decide(bridgeAgentChat)
        assertEquals(GuardKind.DEFER, waiting.kind)
        assertEquals(GuardReason.UPDATE_CHECKING, waiting.reason)
        assertNotEquals("App 内操作不等", GuardReason.UPDATE_CHECKING, routing.decide(inAppOrders).reason)

        // 复查失败：界面保留旧结论，Router 拿到 unknown，外部入口不按旧 ok 放行
        answerNext(UpdateCheckOutcome.Failure)
        assertEquals(UpdateStatus.OK, update.ui.value.status)
        assertEquals(UpdateCheck.UNKNOWN, routing.update.value)
        assertEquals(GuardReason.UPDATE_UNKNOWN_BLOCKED, routing.decide(bridgeAgentChat).reason)

        // 下一次回前台（上次失败，不看间隔）再查；成功后恢复 ok
        update.onForeground()
        assertEquals(UpdateCheck.CHECKING, routing.update.value)
        answerNext(UpdateCheckOutcome.Success(result("1.0.0")))
        assertEquals(UpdateCheck.OK, routing.update.value)
        assertNotEquals(GuardKind.DEFER, routing.decide(bridgeAgentChat).kind)
    }

    @Test
    fun knownBelowMinStaysForceUpdateDuringAndAfterFailedRecheck() {
        val (update, routing) = wire()
        answerNext(UpdateCheckOutcome.Success(result("1.1.0")))
        assertEquals(UpdateCheck.BELOW_MIN, routing.update.value)

        now += (UpdateGate.DEFAULT_RECHECK_INTERVAL_SEC + 1) * 1000L
        update.onForeground()
        assertEquals(UpdateCheck.BELOW_MIN, routing.update.value)
        answerNext(UpdateCheckOutcome.Failure)
        assertEquals(UpdateCheck.BELOW_MIN, routing.update.value)
    }

    /**
     * F-ALL-07：同一次版本检查在途时再次回前台，不重发检查、不重置检查起点，启动意图的 5 秒等待从第一次观察到「检查中」起算。
     * 冷启动检查在途时回前台同样如此。
     */
    @Test
    fun foregroundAgainWhileTheSameCheckIsInFlightKeepsTheWaitAndSendsNoSecondCheck() {
        val (update, routing) = wire()
        val published = mutableListOf<UpdateCheck>()
        update.onRouterUpdate { published += it }
        // 冷启动检查在途时回前台：不重发
        update.onForeground()
        assertEquals(1, answers.size)
        assertEquals(UpdateCheck.CHECKING, routing.update.value)
        answerNext(UpdateCheckOutcome.Success(result("1.0.0")))
        published.clear()

        // 回前台、超过复查间隔：发出一次复查
        now += (UpdateGate.DEFAULT_RECHECK_INTERVAL_SEC + 1) * 1000L
        update.onForeground()
        assertEquals(1, answers.size)
        val queue = StartupIntentQueue()
        val t0 = now

        fun readiness() = StartupReadiness(environmentSettled = true, privacy = PrivacyState.CONSENTED, update = routing.update.value)
        assertTrue(queue.offer(StartupIntent.Link(inAppOrders), coldStart = true, readiness = readiness(), now = now))
        assertTrue(queue.drain(readiness(), now).isEmpty())
        assertEquals(t0 + StartupIntentQueue.UPDATE_WAIT_MILLIS, queue.nextDeadline(readiness()))

        // 3 秒后再次回前台：同一次检查仍在途
        now += 3_000L
        update.onForeground()
        assertEquals("no second check request", 1, answers.size)
        assertEquals(UpdateCheck.CHECKING, routing.update.value)
        assertEquals("router state not re-published (no checking restart)", listOf(UpdateCheck.CHECKING), published)
        assertTrue(queue.drain(readiness(), now).isEmpty())
        assertEquals(
            "wait still counts from the first observation",
            t0 + StartupIntentQueue.UPDATE_WAIT_MILLIS,
            queue.nextDeadline(readiness()),
        )

        // 第一次观察起满 5 秒：按 unknown 交出
        now = t0 + StartupIntentQueue.UPDATE_WAIT_MILLIS
        assertEquals(1, queue.drain(readiness(), now).size)
        answerNext(UpdateCheckOutcome.Success(result("1.0.0")))
        assertEquals(UpdateCheck.OK, routing.update.value)
        assertTrue(answers.isEmpty())
    }
}
