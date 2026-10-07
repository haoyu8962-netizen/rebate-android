package com.couli.app.routing

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.couli.app.BuildConfig
import com.couli.app.privacy.ConsentEvent
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

/**
 * AppRouting 需要的两个隐私状态源（APP-02b；生产由 AppModule 接 AppPrivacy）：
 * @property promptShowing 首启弹窗 / 二次说明正在显示（含基本模式补同意，R1-05）；
 * @property credentialsReadable 会话凭据可以读（撤回同意的本地清理已完成，R1-01）；每次判定会话前调用，未完成时它会先重试清理。
 */
class RoutingPrivacyHooks(
    val promptShowing: () -> Boolean = { false },
    val credentialsReadable: () -> Boolean = { true },
)

/** Router 的目标网页白名单（03 §4.4「WebPage」「ExternalPage」）。 */
data class WebTargets(
    val externalHosts: List<String> = emptyList(),
    val bridgeOrigins: List<String> = emptyList(),
)

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
 * 隐私闸门已接真实的 PrivacyGate（APP-02b，经 AppPrivacy）；强更检查接 UpdateGate（APP-02c，经 AppUpdate 写 [update]）；
 * 其余状态源先用占位值：会话 TODO(APP-05)、推送回查请求 TODO(APP-03)。环境闸门用真实的 [EnvironmentGate]。
 *
 * 强更（APP-02c）：检查进行中（CHECKING）时，实时判定（App 内操作、前置完成后的恢复）把「检查中」交给守卫：外部与自动来源
 * defer（update_checking），App 内操作不等（F-ALL-03）；启动意图与冷启动恢复由排队器等满 5 秒按 unknown 交出。
 * 进入 below_min 时排一条恢复（F-AND-07：不套热启动丢弃规则），守卫给出 ForceUpdate 并删除持久化的待办。
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
 *
 * APP-02b 修复轮：
 * - [RoutingPrivacyHooks.promptShowing]：首启弹窗 / 二次说明正在显示（含基本模式补同意）时，运行中收到的深链与推送丢弃
 *   （03 §4.4，R1-05）；[RoutingPrivacyHooks.credentialsReadable]：撤回同意的本地清理没完成时不读会话凭据，按游客（R1-01）；
 * - [onConsentEvent]：主动同意 = 完成一个前置步骤：续时，并在启动闸门允许时以 enterPrerequisite = true 恢复一次
 *   （进入仍缺的前置页，R1-03）；明确【仍不同意】→ 丢弃本次登记的待办（R1-04）。
 */
@Suppress("TooManyFunctions") // 启动意图、状态源与会话（APP-05a updateSession）的入口集中在一处
class AppRouting
    @Suppress("LongParameterList") // 环境闸门、深链配置、时钟与三组状态源初值逐项注入（单测替身）
    constructor(
        context: Context,
        private val environmentGate: EnvironmentGate,
        private val deepLinkConfig: DeepLinkConfig = variantDeepLinkConfig(),
        private val clock: () -> Long = System::currentTimeMillis,
        initialPrivacy: PrivacyState = PrivacyState.CONSENTED,
        private val privacyHooks: RoutingPrivacyHooks = RoutingPrivacyHooks(),
        initialUpdate: UpdateCheck = UpdateCheck.OK,
    ) {
        /**
         * 隐私闸门的结论（APP-02b）：生产由 AppModule 用 AppPrivacy 的当前结论构造，并在每次结论变化时写入（AppPrivacy.onConclusionChanged）；
         * 变化即触发 [Router.onPrivacyChanged]、重排 pending_action 恢复并 drain。缺省值只给单测用。
         */
        val privacy = MutableStateFlow(initialPrivacy)

        /**
         * 强更检查的结论（APP-02c）：生产由 AppModule 用 AppUpdate 的当前结论构造，并在每次变化时写入；变化即重排
         * pending_action 恢复并 drain。缺省值只给单测用。
         */
        val update = MutableStateFlow(initialUpdate)

        /**
         * 守卫的会话状态（APP-05a：生产由 AppLogin 按 ApiClient 的有效会话现算并经 [updateSession] 写入；单测可直接写）。
         * 由已登录变为游客时调 [Router.onSessionEnded]。
         */
        val session = MutableStateFlow(SessionState.GUEST)

        /** 已处理过的会话状态（[updateSession] 同步处理过的变化，收集器不再重复处理）。只在主线程读写。 */
        private var handledSession: SessionState = session.value

        /**
         * 目标网页白名单的来源（APP-04）：ExternalPage 的 external_hosts、WebPage 的可信 H5 源，生产由 AppModule 接到生效配置
         * （current → LKG → 包内）。缺省空 = 全拒。
         */
        var webTargets: () -> WebTargets = { WebTargets() }

        private val mutableTrace = MutableStateFlow<RoutingTrace?>(null)
        val lastTrace: StateFlow<RoutingTrace?> = mutableTrace.asStateFlow()

        private val queue = StartupIntentQueue()

        /** 下一次交出 ResumePending 时是否进入仍缺的前置页：用户刚主动完成一个前置步骤（同意）时为 true（R1-03）。 */
        private var enterPrerequisiteOnResume = false
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
            fun scheduleResume() = queue.offer(StartupIntent.ResumePending, coldStart = false, readiness, clock())

            // 冷启动：隐私不是已同意时先删 Agent 卡片登记的记录（BR-ID-10 Agent 卡片 ②），再排一次恢复
            router.onPrivacyChanged()
            queue.offer(StartupIntent.ResumePending, coldStart = true, readiness, clock())
            scope.launch {
                privacy.drop(1).collect {
                    router.onPrivacyChanged()
                    scheduleResume()
                    drain()
                }
            }
            scope.launch {
                session.drop(1).collect { current ->
                    if (current == handledSession) return@collect
                    val ended = handledSession != SessionState.GUEST && current == SessionState.GUEST
                    handledSession = current
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

        /**
         * 首启弹窗 / 二次说明里的明确决定：
         * - [ConsentEvent.AGREED]（R1-03）：完成了一个前置步骤——立即为未过期的待办续时（BR-ID-10），再排一次恢复；
         *   启动闸门（环境、强更、外壳、没有弹窗）允许时交出，以 enterPrerequisite = true 恢复：能打开就打开，仍缺登录等前置
         *   就进入那个前置页。冷启动、状态变化触发的自动恢复仍是 enterPrerequisite = false；
         * - [ConsentEvent.DECLINED]（R1-04）：丢弃本次补同意登记的待办；弹窗已关，排着的意图可以交出。
         */
        fun onConsentEvent(event: ConsentEvent) {
            when (event) {
                ConsentEvent.AGREED -> {
                    router.onPrerequisiteCompleted(resume = false)
                    enterPrerequisiteOnResume = true
                    queue.offer(StartupIntent.ResumePending, coldStart = false, readiness, clock())
                }

                ConsentEvent.DECLINED -> {
                    router.onConsentDeclined()
                    enterPrerequisiteOnResume = false
                }
            }
            drain()
        }

        /**
         * 会话变化（APP-05a，主线程，同步处理）：写入守卫的会话状态；由已登录变为游客时 [Router.onSessionEnded]。
         * [resumePending] = true 时按当前状态排一次 pending_action 自动恢复（enterPrerequisite = false）；false 时只同步状态
         * （APP-05a 共性第 10 条：已作废的登录尝试只能通知会话变了，不能顺带恢复待办）。两种都 drain。
         */
        fun updateSession(
            state: SessionState,
            resumePending: Boolean,
        ) {
            val ended = handledSession != SessionState.GUEST && state == SessionState.GUEST
            handledSession = state
            session.value = state
            if (ended) router.onSessionEnded()
            if (resumePending) queue.offer(StartupIntent.ResumePending, coldStart = false, readiness, clock())
            drain()
        }

        /** 切换账号（不经过游客态）：TODO(APP-05b) 会话层切换账号时调用。 */
        fun onAccountSwitched() = router.onSessionEnded()

        /** 切换环境：由环境切换清理入口调用（AppModule 登记）。 */
        fun onEnvironmentSwitched() = router.onEnvironmentChanged()

        fun guardState(updateState: UpdateState = update.value.toLiveUpdateState()): GuardState =
            GuardState(
                privacy = privacy.value,
                update = updateState,
                // 撤回同意的本地清理没完成：不读任何会话凭据，按游客（R1-01）
                session = if (privacyHooks.credentialsReadable()) session.value else SessionState.GUEST,
                environmentSettled = environmentGate.isSettled,
                appVersion = BuildConfig.VERSION_NAME,
                isDebugBuild = BuildConfig.DEBUG_FEATURES,
                // TODO(APP-05): 实名状态来自会话。external_hosts、可信 H5 源来自生效配置（APP-04，[webTargets]）。
                realnameVerified = false,
                externalHosts = webTargets().externalHosts,
                bridgeOrigins = webTargets().bridgeOrigins,
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
            queue.offer(StartupIntent.Link(request), coldStart, readiness, clock())
            drain()
        }

        /** 推送点击（只带消息编号）。TODO(APP-03): 推送 SDK 接入后由点击回调调用。 */
        fun onPushClicked(
            messageId: String,
            coldStart: Boolean,
        ) {
            queue.offer(StartupIntent.Push(messageId), coldStart, readiness, clock())
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
            queue.drain(readiness, clock()).forEach { released ->
                when (val intent = released.intent) {
                    is StartupIntent.Link -> {
                        router.open(intent.request, guardState(released.update))
                    }

                    is StartupIntent.Push -> {
                        openPush(intent.messageId, released.update)
                    }

                    StartupIntent.ResumePending -> {
                        val enter = enterPrerequisiteOnResume
                        enterPrerequisiteOnResume = false
                        router.resumePending(guardState(released.update), enterPrerequisite = enter)
                    }
                }
            }
            // 只有等强更超时才需要定时器；到点前至少等 1 毫秒，不会零延迟反复调度
            queue.nextDeadline(readiness)?.let { deadline ->
                handler.postDelayed(drainRunnable, (deadline - clock()).coerceAtLeast(1L))
            }
        }

        /**
         * 回查结果回来时由 Router 按当时最新的状态重新判定（不沿用点击时的状态）；最新状态仍在检查时用交出时的状态
         * （等满 5 秒 → unknown），不再按 update_checking 等。
         */
        private fun openPush(
            messageId: String,
            updateState: UpdateState,
        ) {
            scope.launch { router.openPush(messageId, pushLanding, releasedUpdate = updateState) }
        }

        private val readiness: StartupReadiness
            get() =
                StartupReadiness(
                    environmentSettled = environmentGate.isSettled,
                    privacy = privacy.value,
                    update = update.value,
                    shellReady = navigator != null,
                    consentPromptShowing = privacyHooks.promptShowing(),
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
