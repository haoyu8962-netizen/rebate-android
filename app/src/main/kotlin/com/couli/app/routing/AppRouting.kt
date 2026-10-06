package com.couli.app.routing

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.couli.app.BuildConfig
import com.couli.app.variantDeepLinkConfig
import com.couli.core.config.EnvironmentGate
import com.couli.core.router.DeepLinkConfig
import com.couli.core.router.DeepLinkParser
import com.couli.core.router.GuardDecision
import com.couli.core.router.GuardState
import com.couli.core.router.PendingActionStore
import com.couli.core.router.PrivacyState
import com.couli.core.router.PushLanding
import com.couli.core.router.PushLookupResult
import com.couli.core.router.RouteNavigator
import com.couli.core.router.RouteRequest
import com.couli.core.router.Router
import com.couli.core.router.SessionState
import com.couli.core.router.SharedPreferencesPendingBackend
import com.couli.core.router.StartupIntent
import com.couli.core.router.StartupIntentQueue
import com.couli.core.router.StartupReadiness
import com.couli.core.router.UpdateCheck
import com.couli.core.router.UpdateState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** 最近一次入站请求的处理结果（调试首屏显示，冒烟截图用）。 */
data class RoutingTrace(
    val source: String,
    val route: String?,
    val kind: String,
    val reason: String,
)

/**
 * App 的路由接线（APP-02a 最小外壳）：启动意图排队 → 统一入口守卫 → Navigation Compose。
 *
 * 状态源先用占位值，后续任务接入：隐私闸门 TODO(APP-02b)、强更检查 TODO(APP-02c)、会话 TODO(APP-05)、
 * 推送回查请求 TODO(APP-03)。环境闸门用真实的 [EnvironmentGate]。
 *
 * 状态源变化时（环境闸门、隐私、强更）重新 [drain]；隐私变化同时让 Router 删除 Agent 卡片的 pending_action（R1-05）；
 * 会话由已登录变为游客时调 [Router.onSessionEnded]（清 pending_action、作废进行中的推送回查，R1-03）。
 * 定时器只在「闸门已通过、强更仍在检查」时按 5 秒到点安排；闸门未就绪时不空转（R1-07）。
 *
 * 冷启动恢复 pending_action（BR-ID-10，R2-02）：构造时（每个进程一次，Hilt 单例）排一条 [StartupIntent.ResumePending]，
 * 会话、隐私、强更、环境变化后再排一条（队里最多一条），与深链、推送同一套就绪判定（环境 settled、隐私有结论、
 * 强更有结果或等满 5 秒按 unknown、外壳就绪）；交出时调 [Router.resumePending]（enterPrerequisite = false）：
 * 未过期且前置满足 → 打开一次并删除；过期 → 删除不执行；前置未满足 → 保留，不自动拉起前置页。
 * 外壳重组、重复 attach 只会 drain，不会再排恢复，记录打开后即删除，不会重复执行。
 */
class AppRouting(
    context: Context,
    private val environmentGate: EnvironmentGate,
    private val deepLinkConfig: DeepLinkConfig = variantDeepLinkConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    // TODO(APP-02b): 接隐私闸门；首启弹窗出结论前应为 NOT_DECIDED。
    val privacy = MutableStateFlow(PrivacyState.CONSENTED)

    // TODO(APP-02c): 接强更检查；冷启动检查有结果前应为 CHECKING。
    val update = MutableStateFlow(UpdateCheck.OK)

    // TODO(APP-05): 接会话。
    val session = MutableStateFlow(SessionState.GUEST)

    private val mutableTrace = MutableStateFlow<RoutingTrace?>(null)
    val lastTrace: StateFlow<RoutingTrace?> = mutableTrace.asStateFlow()

    private val queue = StartupIntentQueue()
    private val handler = Handler(Looper.getMainLooper())
    private val drainRunnable = Runnable { drain() }

    /** [drain] 被调用的次数（测试观察是否空转用）。 */
    var drainCount: Int = 0
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // TODO(APP-03): 换成 GET /v1/messages/{message_id}；现在回查一律失败（落到 Messages）。
    private val pushLanding = PushLanding(lookup = { PushLookupResult.Failed })
    private var navigator: RouteNavigator? = null

    val router: Router =
        Router(
            state = { guardState() },
            pendingStore = PendingActionStore(SharedPreferencesPendingBackend(context), clock),
            navigator = TracingNavigator(),
        )

    init {
        // 前置条件变化后再排一次 pending_action 恢复（队里已有则不重复）
        fun scheduleResume() = queue.offer(StartupIntent.ResumePending, coldStart = false, readiness(), clock())

        // 冷启动：隐私不是已同意时先删 Agent 卡片登记的记录（BR-ID-10 Agent 卡片 ②），再排一次恢复
        router.onPrivacyChanged()
        queue.offer(StartupIntent.ResumePending, coldStart = true, readiness(), clock())
        scope.launch {
            privacy.drop(1).collect {
                router.onPrivacyChanged()
                scheduleResume()
                drain()
            }
        }
        scope.launch {
            var previous = session.value
            session.drop(1).collect { current ->
                val ended = previous != SessionState.GUEST && current == SessionState.GUEST
                previous = current
                if (ended) router.onSessionEnded()
                scheduleResume()
                drain()
            }
        }
        scope.launch {
            merge(update.drop(1), environmentGate.state.drop(1)).collect {
                scheduleResume()
                drain()
            }
        }
    }

    /** 切换账号（不经过游客态）：TODO(APP-05) 会话层切换账号时调用。 */
    fun onAccountSwitched() = router.onSessionEnded()

    /** 切换环境：由环境切换清理入口调用（AppModule 登记）。 */
    fun onEnvironmentSwitched() = router.onEnvironmentChanged()

    fun guardState(updateState: UpdateState = update.value.toUpdateState()): GuardState =
        GuardState(
            privacy = privacy.value,
            update = updateState,
            session = session.value,
            environmentSettled = environmentGate.isSettled,
            appVersion = BuildConfig.VERSION_NAME,
            isDebugBuild = BuildConfig.DEBUG_FEATURES,
            // TODO(APP-05): 实名状态来自会话；TODO(APP-03): external_hosts、bridge_origins 来自 /v1/config（LKG）。
            realnameVerified = false,
            externalHosts = emptyList(),
            bridgeOrigins = emptyList(),
        )

    /** Activity 收到的 Intent：只认 data 里的深链（启动参数里的任何 route 都按深链校验，03 §4.4）。 */
    fun onLaunchIntent(
        data: String?,
        coldStart: Boolean,
    ) {
        if (data == null) return
        val request = DeepLinkParser.parse(data, deepLinkConfig)
        if (request == null) {
            mutableTrace.value = RoutingTrace(source = "deeplink", route = null, kind = "invalid", reason = "parse_failed")
            return
        }
        queue.offer(StartupIntent.Link(request), coldStart, readiness(), clock())
        drain()
    }

    /** 推送点击（只带消息编号）。TODO(APP-03): 推送 SDK 接入后由点击回调调用。 */
    fun onPushClicked(
        messageId: String,
        coldStart: Boolean,
    ) {
        queue.offer(StartupIntent.Push(messageId), coldStart, readiness(), clock())
        drain()
    }

    /** 导航外壳就绪（NavHost 已组合）。 */
    fun attach(navigator: RouteNavigator) {
        this.navigator = navigator
        drain()
    }

    fun detach(navigator: RouteNavigator) {
        if (this.navigator === navigator) this.navigator = null
    }

    /** 状态变化（环境闸门、隐私、强更）或等待到点时调用：把满足条件的启动意图交给 Router。 */
    fun drain() {
        drainCount++
        handler.removeCallbacks(drainRunnable)
        queue.drain(readiness(), clock()).forEach { released ->
            when (val intent = released.intent) {
                is StartupIntent.Link -> router.open(intent.request, guardState(released.update))
                is StartupIntent.Push -> openPush(intent.messageId, released.update)
                StartupIntent.ResumePending -> router.resumePending(guardState(released.update), enterPrerequisite = false)
            }
        }
        // 只有等强更超时才需要定时器；到点前至少等 1 毫秒，不会零延迟反复调度
        queue.nextDeadline(readiness())?.let { deadline ->
            handler.postDelayed(drainRunnable, (deadline - clock()).coerceAtLeast(1L))
        }
    }

    /** 回查结果回来时由 Router 按当时最新的状态重新判定（不沿用点击时的状态）。 */
    @Suppress("UnusedParameter") // 放行时的强更状态只决定何时放行；判定用回查结束时的最新状态
    private fun openPush(
        messageId: String,
        updateState: UpdateState,
    ) {
        scope.launch { router.openPush(messageId, pushLanding) }
    }

    private fun readiness(): StartupReadiness =
        StartupReadiness(
            environmentSettled = environmentGate.isSettled,
            privacy = privacy.value,
            update = update.value,
            shellReady = navigator != null,
        )

    /** 把 Router 的结论转给当前的 NavHost，并记一条调试轨迹。 */
    private inner class TracingNavigator : RouteNavigator {
        override fun open(
            route: String,
            params: JsonObject,
            readonly: Boolean,
        ) {
            trace()
            navigator?.open(route, params, readonly)
        }

        override fun showUpgradePrompt(request: RouteRequest) {
            trace()
            navigator?.showUpgradePrompt(request)
        }

        override fun requestConsent(request: RouteRequest) {
            trace()
            navigator?.requestConsent(request)
        }

        override fun blocked(
            request: RouteRequest,
            decision: GuardDecision,
        ) {
            trace()
            navigator?.blocked(request, decision)
        }

        private fun trace() {
            router.lastOutcome.value?.let { outcome ->
                mutableTrace.value =
                    RoutingTrace(
                        source = outcome.request.source,
                        route = outcome.request.route,
                        kind = outcome.decision.kindRaw,
                        reason = outcome.decision.reason,
                    )
            }
        }
    }
}
