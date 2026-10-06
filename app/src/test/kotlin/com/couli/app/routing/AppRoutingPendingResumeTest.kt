package com.couli.app.routing

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.couli.core.config.EnvironmentGate
import com.couli.core.config.EnvironmentStartupReconciler
import com.couli.core.router.GuardDecision
import com.couli.core.router.GuardKind
import com.couli.core.router.GuardReason
import com.couli.core.router.PendingActionStore
import com.couli.core.router.PrivacyState
import com.couli.core.router.RouteNavigator
import com.couli.core.router.RouteRequest
import com.couli.core.router.RouteSource
import com.couli.core.router.SessionState
import com.couli.core.router.SharedPreferencesPendingBackend
import com.couli.core.router.UpdateCheck
import java.time.Duration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * R2-02：冷启动（进程重启）恢复持久化的 pending_action（BR-ID-10「冷启动时读取」）。
 * 第一个 [AppRouting] 实例登记记录（落 SharedPreferences），第二个实例模拟进程重启后的新进程：同一套就绪判定
 * （环境、隐私、强更、外壳）满足时恢复一次。
 *
 * R2-03：外壳晚于 5 秒就绪时，强更等待已经超时，就绪那一刻直接按 unknown 交出，不再等 5 秒。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class AppRoutingPendingResumeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val looper get() = shadowOf(Looper.getMainLooper())
    private var now = 1_000_000L
    private val orderDetail = RouteRequest("OrderDetail", JsonObject(mapOf("order_id" to JsonPrimitive("o-1"))), RouteSource.IN_APP)

    private class RecordingNavigator : RouteNavigator {
        val opened = mutableListOf<String>()
        val blocked = mutableListOf<GuardDecision>()

        override fun open(
            route: String,
            params: JsonObject,
            readonly: Boolean,
        ) {
            opened += route
        }

        override fun showUpgradePrompt(request: RouteRequest) {
            opened += "upgrade:${request.route}"
        }

        override fun requestConsent(request: RouteRequest) {
            opened += "consent:${request.route}"
        }

        override fun blocked(
            request: RouteRequest,
            decision: GuardDecision,
        ) {
            blocked += decision
        }
    }

    private fun gate(): EnvironmentGate =
        EnvironmentGate { EnvironmentStartupReconciler.Outcome.CLEANED_AND_RECORDED }.also { it.settleAtStartup() }

    private fun stored(): String? = SharedPreferencesPendingBackend(context).read()

    /** 上一个进程：游客点订单详情 → 需要登录，登记 pending_action（落盘）。 */
    private fun previousProcessRegistersPending() {
        val previous = AppRouting(context, gate(), clock = { now })
        val decision = previous.router.open(orderDetail)
        assertEquals(GuardKind.REQUIRE_LOGIN, decision.kind)
        assertNotNull("已落盘", stored())
    }

    /** 进程重启：新的 AppRouting 实例（新的 Router、Store，只共享 SharedPreferences）。 */
    private fun restartedProcess(): AppRouting = AppRouting(context, gate(), clock = { now })

    @Test
    fun coldStartResumesUnexpiredPendingOnceWhenReady() {
        previousProcessRegistersPending()
        now += 5 * 60_000L // 5 分钟后，进程被回收后冷启动
        val routing = restartedProcess()
        routing.session.value = SessionState.LOGGED_IN // 登录态恢复
        looper.idle()
        assertNotNull("外壳未就绪：不恢复，记录保留", stored())

        val navigator = RecordingNavigator()
        routing.attach(navigator)
        looper.idle()
        assertEquals(listOf("OrderDetail"), navigator.opened)
        assertNull("执行一次后删除", stored())
        assertEquals(
            RouteSource.PENDING_ACTION.raw,
            routing.router.lastOutcome.value
                ?.request
                ?.source,
        )

        // 外壳重组 / 重复 attach、状态再变化：不重复执行
        routing.detach(navigator)
        routing.attach(navigator)
        routing.update.value = UpdateCheck.BELOW_RECOMMENDED
        looper.idle()
        assertEquals(listOf("OrderDetail"), navigator.opened)
    }

    @Test
    fun coldStartDeletesExpiredPendingWithoutOpening() {
        previousProcessRegistersPending()
        now += 10 * 60_000L + 1 // 超过 10 分钟
        val routing = restartedProcess()
        routing.session.value = SessionState.LOGGED_IN
        val navigator = RecordingNavigator()
        routing.attach(navigator)
        looper.idle()
        assertTrue("过期：不执行", navigator.opened.isEmpty())
        assertNull("过期：删除", stored())
    }

    @Test
    fun coldStartKeepsPendingWhilePrerequisiteMissingThenResumesAfterLogin() {
        previousProcessRegistersPending()
        now += 60_000L
        val routing = restartedProcess() // 仍是游客
        val navigator = RecordingNavigator()
        routing.attach(navigator)
        looper.idle()
        assertTrue("前置未满足：不自动拉起登录页、不打开", navigator.opened.isEmpty())
        assertNotNull("前置未满足：保留", stored())
        assertEquals(
            GuardReason.LOGIN_REQUIRED,
            routing.router.lastOutcome.value
                ?.decision
                ?.reason,
        )

        // 前置条件变化（登录完成）后恢复一次
        now += 60_000L
        routing.session.value = SessionState.LOGGED_IN
        looper.idle()
        assertEquals(listOf("OrderDetail"), navigator.opened)
        assertNull(stored())
    }

    @Test
    fun coldStartWaitsForPrivacyAndUpdateBeforeResuming() {
        previousProcessRegistersPending()
        val routing = restartedProcess()
        routing.privacy.value = PrivacyState.NOT_DECIDED
        routing.update.value = UpdateCheck.CHECKING
        routing.session.value = SessionState.LOGGED_IN
        val navigator = RecordingNavigator()
        routing.attach(navigator)
        looper.idle()
        assertTrue("隐私未决：不恢复", navigator.opened.isEmpty())

        routing.privacy.value = PrivacyState.CONSENTED // t=0：隐私通过，强更等待起算
        looper.idle()
        assertTrue("强更检查中、未满 5 秒：不恢复", navigator.opened.isEmpty())
        assertNotNull(stored())

        now += 2_000L
        routing.update.value = UpdateCheck.OK
        looper.idle()
        assertEquals(listOf("OrderDetail"), navigator.opened)
        assertNull(stored())
    }

    @Test
    fun shellReadyAfterUpdateTimeoutReleasesAsUnknownImmediately() {
        previousProcessRegistersPending()
        val routing = restartedProcess()
        routing.session.value = SessionState.LOGGED_IN
        routing.update.value = UpdateCheck.CHECKING // t=0：环境与隐私已通过，强更开始检查；外壳未就绪
        looper.idle()
        assertNotNull(stored())

        now += 10_000L // t=10s：外壳才就绪，检查仍未返回
        val navigator = RecordingNavigator()
        routing.attach(navigator)
        val outcome = routing.router.lastOutcome.value
        assertEquals("就绪那一刻按 unknown 交出（不再等 5 秒）", GuardReason.UPDATE_UNKNOWN_READONLY, outcome?.decision?.reason)
        assertEquals(RouteSource.PENDING_ACTION.raw, outcome?.request?.source)
        assertEquals("unknown 下 OrderDetail 只读打开一次", listOf("OrderDetail"), navigator.opened)
        assertNull("执行后删除", stored())
        assertFalse("没有排着的 5 秒定时器", looper.nextScheduledTaskTime > Duration.ZERO)
    }

    @Test
    fun coldStartDropsAgentCardPendingWhenPrivacyUndecided() {
        val store = PendingActionStore(SharedPreferencesPendingBackend(context)) { now }
        val payload = JsonObject(mapOf("route" to JsonPrimitive("Wallet")))
        assertTrue(store.save(PendingActionStore.OPEN_ROUTE, payload, origin = RouteSource.AGENT_CARD.raw))
        val routing = restartedProcess()
        routing.privacy.value = PrivacyState.NOT_DECIDED
        routing.session.value = SessionState.LOGGED_IN
        val navigator = RecordingNavigator()
        routing.attach(navigator)
        routing.privacy.value = PrivacyState.CONSENTED
        looper.idle()
        assertTrue("Agent 卡片记录遇隐私阻断：同意后也不补执行", navigator.opened.isEmpty())
        assertNull(stored())
    }
}
