package com.couli.core.router

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Router 执行结论的落地方（App 实现：Navigation Compose 推页面、弹隐私弹窗等）。
 * Router 不 import 任何 Feature 类型：页面按路由名由 App 的注册表映射到各 Feature 暴露的入口（03 §3.1）。
 */
interface RouteNavigator {
    /** 推入 [route]。[readonly] = 只读打开（基本模式、强更未知）：页面不得自动发起写请求、授权或发送 AgentChat context。 */
    fun open(
        route: String,
        params: JsonObject,
        readonly: Boolean,
    )

    /** 未知路由或 since 过高：「请升级 App」提示（没有契约路由名，TODO(N-14) rule-gaps G03）。 */
    fun showUpgradePrompt(request: RouteRequest)

    /** 需要先补同意（隐私弹窗，02b 接入）。 */
    fun requestConsent(request: RouteRequest)

    /** 不打开（拒绝或等待）：冷启动停在首页、前台停在当前页；记埋点 route_entry_blocked（只记路由名与入口类别）。 */
    fun blocked(
        request: RouteRequest,
        decision: GuardDecision,
    )
}

/** 一次 open 的记录（调试与测试观察用）。 */
data class RouteOutcome(
    val request: RouteRequest,
    val decision: GuardDecision,
)

/**
 * 同一个入口服务原生页面、首页卡片、JSBridge nav.open、推送、深链、pending_action 恢复与 Agent 卡片（03 §4.4）：
 * [open] = [EntryGuard.decide] → 按结论推页面 / 登记 pending_action 并进前置页 / 升级提示 / 补同意 / 不打开。
 *
 * 代次（R1-03）：退出登录、切换账号、切换环境都让代次加一；推送回查发起时记下代次，结果回来时代次已变即丢弃，
 * 不拿上一个账号或环境的回查结果打开页面。
 */
class Router(
    private val state: () -> GuardState,
    private val pendingStore: PendingActionStore,
    private val navigator: RouteNavigator,
) {
    private val mutableLastOutcome = MutableStateFlow<RouteOutcome?>(null)

    private val generationCounter = AtomicLong(0L)

    /** 最近一次 open 的结论。 */
    val lastOutcome: StateFlow<RouteOutcome?> = mutableLastOutcome.asStateFlow()

    /** 当前的会话 / 环境代次。 */
    val generation: Long get() = generationCounter.get()

    /** [guardState] 缺省取当前状态；启动意图放行时由排队器给出等待超时后的强更状态（unknown）。 */
    fun open(
        request: RouteRequest,
        guardState: GuardState = state(),
    ): GuardDecision {
        dropAgentPendingIfPrivacyBlocked(guardState)
        val decision = EntryGuard.decide(request, guardState)
        PendingRegistration.payload(request, decision, guardState)?.let { payload ->
            // 含令牌、验证码的参数 save 返回 false：不登记，前置完成后不自动打开。
            pendingStore.save(PendingActionStore.OPEN_ROUTE, payload, origin = request.source)
        }
        dispatch(request, decision)
        return decision
    }

    /**
     * 推送点击（只带消息编号）。
     * 1. 回查之前先过前三道闸门（环境、隐私、强更）：环境未就绪、隐私未决或基本模式、低于最低版本时**不发回查**，
     *    直接按闸门结论处理（等待 / 丢弃 / ForceUpdate）；
     * 2. 未登录或受限会话：不回查，登记「打开 Messages」后进登录页（BR-ID-10「推送点击的落点」）；
     * 3. 回查是异步的：发起时记下代次，结果回来时代次已变（期间退出登录、换账号、换环境）→ 丢弃，返回 null；
     *    否则用**当时最新**的状态重新判定（期间进入强更等都生效），不沿用点击时的结论。
     */
    suspend fun openPush(
        messageId: String,
        landing: PushLanding,
    ): GuardDecision? {
        val startGeneration = generation
        val before = state()
        val messages = PushLanding.messagesRequest()
        val blocked = EntryGuard.preconditions(messages, before)
        if (blocked != null) {
            dropAgentPendingIfPrivacyBlocked(before)
            dispatch(messages, blocked)
            return blocked
        }
        val result = landing.resolve(messageId, before.sessionState)
        val target = (result as? PushLandingResult.Open)?.request ?: messages
        // 代次不符：回查发起后退出登录、换了账号或环境，丢弃结果
        return if (generation == startGeneration) open(target) else null
    }

    /**
     * 恢复 pending_action（同意、登录、绑手机、实名等前置完成后由调用方调）。有效记录以 pending_action 来源再过一次守卫：
     * - 打开了、或被拒 / 升级 / 强更（不再能执行）→ 删除；
     * - 仍缺前置（登录、绑手机、实名）→ 保留（计时照旧）并进入那个前置页；要补同意或等待 → 保留；
     * - Agent 卡片登记的记录遇到隐私阻断（未同意或基本模式）→ 直接删除，不自动执行，由用户再点（BR-ID-10 Agent 卡片 ②）；
     * - 敏感动作（[noPendingRoutes]，BR-ID-10「敏感操作不登记」）的记录（旧版本留下的）→ 删除，不执行。
     *
     * @param guardState 缺省取当前状态；冷启动恢复由排队器给出强更等待超时后的状态（unknown）。
     * @param enterPrerequisite 仍缺前置时是否进入前置页。用户刚完成一个前置步骤时为 true；冷启动或状态变化触发的自动
     *   恢复为 false——按 BR-ID-10「前置未满足则保留，继续等待前置步骤」只保留记录，不自动拉起登录等页面（R2-02）。
     */
    fun resumePending(
        guardState: GuardState = state(),
        enterPrerequisite: Boolean = true,
    ): GuardDecision? {
        val current = guardState
        dropAgentPendingIfPrivacyBlocked(current)
        val pending = pendingStore.load()?.takeIf { it.action == PendingActionStore.OPEN_ROUTE }
        val route = (pending?.params?.get(KEY_ROUTE) as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (pending == null || route == null || route in noPendingRoutes) {
            // 记录形状不对（没有路由名）或是不该登记的敏感动作：删除，不执行。
            if (pending != null) pendingStore.clear()
            return null
        }
        val params = pending.params[KEY_PARAMS] as? JsonObject ?: JsonObject(emptyMap())
        val request = RouteRequest(route, params, RouteSource.PENDING_ACTION)
        val decision = EntryGuard.decide(request, current)
        val keep = decision.kind in KEEP_PENDING_KINDS
        if (!keep) pendingStore.clear()
        // 仍缺前置时不重复登记（不改计时），只进入前置页；要补同意、等待时只记结论。
        if (keep && (decision.kind !in PREREQUISITE_KINDS || !enterPrerequisite)) {
            mutableLastOutcome.value = RouteOutcome(request, decision)
        } else {
            dispatch(request, decision)
        }
        return decision
    }

    /** 完成了一个前置步骤（同意、登录、绑手机、实名、授权）：重置计时后尝试恢复。 */
    fun onPrerequisiteCompleted(): GuardDecision? {
        // 先按当前隐私删 Agent 记录，再续时：被删的记录不会被续活
        dropAgentPendingIfPrivacyBlocked(state())
        pendingStore.markStepDone()
        return resumePending()
    }

    /** 退出登录、切换账号：清除 pending_action（BR-ID-10），代次加一（进行中的推送回查结果作废）。 */
    fun onSessionEnded() {
        generationCounter.incrementAndGet()
        pendingStore.clear()
    }

    /** 切换环境（或环境闸门重新对账）：代次加一，清除 pending_action。 */
    fun onEnvironmentChanged() {
        generationCounter.incrementAndGet()
        pendingStore.clear()
    }

    /**
     * 隐私状态变化时调用（冷启动隐私闸门、撤回同意、进入基本模式）：一旦不是已同意，立即删除 Agent 卡片登记的记录，
     * 之后补同意也不补执行（R1-05）。
     */
    fun onPrivacyChanged() = dropAgentPendingIfPrivacyBlocked(state())

    private fun dropAgentPendingIfPrivacyBlocked(guardState: GuardState) {
        if (guardState.privacyState == PrivacyState.CONSENTED) return
        if (pendingStore.load()?.origin == RouteSource.AGENT_CARD.raw) pendingStore.clear()
    }

    private fun dispatch(
        request: RouteRequest,
        decision: GuardDecision,
    ) {
        mutableLastOutcome.value = RouteOutcome(request, decision)
        val route = decision.route.orEmpty()
        when (decision.kind) {
            GuardKind.OPEN -> {
                navigator.open(route, decision.params, readonly = false)
            }

            GuardKind.OPEN_READONLY -> {
                navigator.open(route, decision.params, readonly = true)
            }

            // 前置页、强更页不带参数。
            GuardKind.REQUIRE_LOGIN, GuardKind.REQUIRE_BIND_PHONE, GuardKind.REQUIRE_REALNAME, GuardKind.FORCE_UPDATE -> {
                navigator.open(route, JsonObject(emptyMap()), readonly = false)
            }

            GuardKind.UPGRADE -> {
                navigator.showUpgradePrompt(request)
            }

            GuardKind.REQUIRE_CONSENT -> {
                navigator.requestConsent(request)
            }

            GuardKind.REJECT, GuardKind.DEFER -> {
                navigator.blocked(request, decision)
            }
        }
    }

    companion object {
        private const val KEY_ROUTE = PendingRegistration.KEY_ROUTE
        private const val KEY_PARAMS = PendingRegistration.KEY_PARAMS

        /**
         * 不登记 pending_action 的敏感动作（BR-ID-10「敏感操作不登记」，编排者本轮更正）：提现、收款账号、劳务协议、实名、注销。
         * 前置完成后由用户自己再进入，不自动打开。
         */
        val noPendingRoutes: Set<String> = setOf("Withdraw", "PayoutAccount", "LaborAgreement", "RealName", "DeleteAccount")

        private val PREREQUISITE_KINDS = PendingRegistration.PREREQUISITE_KINDS

        private val KEEP_PENDING_KINDS = PREREQUISITE_KINDS + setOf(GuardKind.REQUIRE_CONSENT, GuardKind.DEFER)
    }
}
