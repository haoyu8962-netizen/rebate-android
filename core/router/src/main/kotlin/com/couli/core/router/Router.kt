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
@Suppress("TooManyFunctions") // 统一入口、恢复与各类状态变化的入口集中在一处（APP-05a 加了待办查询与丢弃）
class Router(
    private val state: () -> GuardState,
    private val pendingStore: PendingActionStore,
    private val navigator: RouteNavigator,
) {
    private val mutableLastOutcome = MutableStateFlow<RouteOutcome?>(null)

    private val generationCounter = AtomicLong(0L)

    /**
     * 本进程里刚为「基本模式下点受限入口 → 补同意」登记了待办，隐私弹窗还没有结论（只在内存，不落盘）。
     * 为 true 时自动恢复遇到隐私阻断不删除这条待办（它正在等用户在弹窗里决定）；同意后按前置完成继续，
     * 仍不同意时 [onConsentDeclined] 删除。进程重启后为 false：基本模式下的自动恢复照常 reject 删除（F-ALL-04）。
     */
    @Volatile
    private var awaitingConsentDecision = false

    /**
     * 会话标签来源（APP-05a 共性第 25 条）：此刻的登录世代（App 给 SessionTokens.loginEpoch：登录、清除各加 1）。登记 pending_action
     * 时记下它，会话结束的清理只清原会话归属的待办。没有接（单测）时按旧口径整体清理。
     */
    @Volatile
    var sessionTag: (() -> Long)? = null

    /**
     * 待办暂缓（APP-05b 共性第 3、9 条）：第三方登录后等绑手机引导结束 / phone_bound 查清期间为 true。这是持续状态：所有自动恢复入口
     * （会话同步、版本检查完成、启动队列、受限会话升级）经 [resumePending] 时都只保留记录、不执行；只有调用方明确 [releaseHeldPending]
     * （引导跳过、关闭、绑定完成、查清）后才恢复。会话结束、切换环境、丢弃待办时一并解除。只在内存（进程重启后由冷启动的 phone_bound
     * 核对兜底，共性第 8 条）。
     */
    @Volatile
    var pendingHeld: Boolean = false
        private set

    /**
     * 恢复前的额外核对（APP-05b 共性第 8 条）：返回 false 时这次不恢复、保留记录（如手机号级路由要先查清当前会话的 phone_bound）。
     * 调用方查清后自己再触发恢复。没有接时不核对。
     */
    @Volatile
    var resumeGate: ((RouteRequest) -> Boolean)? = null

    /** 开始暂缓待办（不改计时）。 */
    fun holdPending() {
        pendingHeld = true
    }

    /** 明确释放暂缓（之后的恢复照常执行）。 */
    fun releaseHeldPending() {
        pendingHeld = false
    }

    /** 本进程里登记的 pending_action 与登记时的会话标签（只在内存；进程重启后归属未知，清理时按原会话的处理）。 */
    private var pendingTag: Pair<PendingAction, Long>? = null

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
            val saved = pendingStore.save(PendingActionStore.OPEN_ROUTE, payload, origin = request.source)
            if (saved) {
                awaitingConsentDecision = decision.kind == GuardKind.REQUIRE_CONSENT
                tagPending()
            }
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
     *
     * @param releasedUpdate 排队器交出这条推送时的强更状态（等满 5 秒仍在检查 → unknown）：最新状态仍是「检查中」时用它，
     *   不再按 update_checking 等待（等待已在排队器里做完，F-ALL-03）；最新状态已有结果时用最新状态。
     */
    suspend fun openPush(
        messageId: String,
        landing: PushLanding,
        releasedUpdate: UpdateState? = null,
    ): GuardDecision? {
        val startGeneration = generation
        val before = state().afterQueueWait(releasedUpdate)
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
        return if (generation == startGeneration) open(target, state().afterQueueWait(releasedUpdate)) else null
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
    @Suppress("CyclomaticComplexMethod", "ReturnCount") // 暂缓、核对、形状、守卫结论逐项判定
    fun resumePending(
        guardState: GuardState = state(),
        enterPrerequisite: Boolean = true,
    ): GuardDecision? {
        val current = guardState
        dropAgentPendingIfPrivacyBlocked(current)
        // 待办暂缓期间：任何恢复入口都只保留记录（共性第 9 条）
        if (pendingHeld) return null
        val pending = pendingStore.load()?.takeIf { it.action == PendingActionStore.OPEN_ROUTE }
        val route = (pending?.params?.get(KEY_ROUTE) as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (pending == null || route == null || route in noPendingRoutes) {
            // 记录形状不对（没有路由名）或是不该登记的敏感动作：删除，不执行。
            if (pending != null) pendingStore.clear()
            return null
        }
        val params = pending.params[KEY_PARAMS] as? JsonObject ?: JsonObject(emptyMap())
        val request = RouteRequest(route, params, RouteSource.PENDING_ACTION)
        // 恢复前的额外核对没过（如手机号级路由的 phone_bound 还没查清，共性第 8 条）：保留记录，查清后由调用方再恢复
        if (resumeGate?.invoke(request) == false) return null
        val decision = EntryGuard.decide(request, current)
        // 补同意的弹窗还没有结论：隐私阻断（基本模式 reject）不删这条待办，只记结论
        val heldForConsent = awaitingConsentDecision && current.privacyState != PrivacyState.CONSENTED
        if (current.privacyState == PrivacyState.CONSENTED) awaitingConsentDecision = false
        val keep = decision.kind in KEEP_PENDING_KINDS || heldForConsent
        if (!keep) pendingStore.clear()
        // 仍缺前置时不重复登记（不改计时），只进入前置页；要补同意、等待时只记结论。
        val entersPrerequisite = decision.kind in PREREQUISITE_KINDS && enterPrerequisite && !heldForConsent
        if (keep && !entersPrerequisite) {
            mutableLastOutcome.value = RouteOutcome(request, decision)
        } else {
            dispatch(request, decision)
        }
        return decision
    }

    /**
     * 完成了一个前置步骤（同意、登录、绑手机、实名、授权）：重置计时（BR-ID-10：每完成一步重置）后尝试恢复。
     * [resume] = false 时只续时、不立即恢复（返回 null）：调用方要等启动闸门允许（环境、强更、外壳就绪）后再
     * [resumePending]（enterPrerequisite = true），见 App 的 AppRouting.onConsentEvent（APP-02b R1-03）。
     */
    fun onPrerequisiteCompleted(resume: Boolean = true): GuardDecision? {
        // 先按当前隐私删 Agent 记录，再续时：被删的记录不会被续活
        dropAgentPendingIfPrivacyBlocked(state())
        pendingStore.markStepDone()
        return if (resume) resumePending() else null
    }

    /**
     * 用户在二次说明里明确【仍不同意】（首启或基本模式下补同意，R1-04）：本次补同意登记的待办在基本模式下不可达，
     * 立即删除（03 §4.4「基本模式下丢弃不可达的自动动作」）；之后再点【开启完整功能】并同意也不会自动打开被拒绝的目标。
     */
    fun onConsentDeclined() {
        awaitingConsentDecision = false
        if (state().privacyState != PrivacyState.CONSENTED) pendingStore.clear()
    }

    /**
     * 登记一条不是「打开路由」的 pending_action（APP-06b：购买 open_link，BR-ID-10）：与路由待办共用同一份本地记录（只存 1 条、10 分钟、
     * 完成一步重新计时、退出登录与换环境清除）。Router 的自动恢复不执行它（[resumePending] 只认 [PendingActionStore.OPEN_ROUTE]），
     * 由登记它的功能在前置完成后自己执行一次并删除。含令牌、验证码的参数不登记（返回 false）。
     */
    fun registerAction(
        action: String,
        params: JsonObject,
        origin: String = RouteSource.IN_APP.raw,
    ): Boolean {
        val saved = pendingStore.save(action, params, origin = origin)
        if (saved) {
            awaitingConsentDecision = false
            tagPending()
        }
        return saved
    }

    /** 有效的、动作名为 [action] 的 pending_action；没有或已过期为 null（过期的读取时删除）。 */
    fun pendingAction(action: String): PendingAction? = pendingStore.load()?.takeIf { it.action == action }

    /** 删除动作名为 [action] 的 pending_action（别的动作的记录不动）。 */
    fun clearAction(action: String) {
        if (pendingStore.load()?.action == action) pendingStore.clear()
    }

    /** 本机有没有未过期的 pending_action（登录后计划 AfterLogin 用，APP-05a）。 */
    val hasPendingAction: Boolean get() = pendingStore.load() != null

    /** 删除 pending_action，不执行（登录拿到受限会话或强更低于时丢弃业务待办，APP-05a discard_pending）。 */
    fun discardPendingAction() {
        awaitingConsentDecision = false
        pendingHeld = false
        pendingStore.clear()
    }

    /**
     * 退出登录、切换账号、会话失效：清除原会话的 pending_action（BR-ID-10），代次加一（进行中的推送回查结果作废）。
     *
     * APP-05a 共性第 25 条：只清原会话归属的待办——登记时的会话标签不大于 [throughSession]（原会话那一代及更早），或归属未知
     * （进程重启前登记、记录已改写）时删除；原会话被别处清掉（10404、刷新失败）之后用户新建的待办标签更大，保留给正在进行的登录。
     * [throughSession] 缺省为「此刻的会话标签之前」（清理已经发生、登录世代已前进）。没有接会话标签来源时整体清理。
     */
    fun onSessionEnded(throughSession: Long? = null) {
        awaitingConsentDecision = false
        pendingHeld = false
        generationCounter.incrementAndGet()
        val tag = sessionTag
        if (tag == null) {
            pendingStore.clear()
            pendingTag = null
            return
        }
        val through = throughSession ?: (tag() - 1)
        if (ownedByLaterSession(through)) return
        pendingStore.clear()
        pendingTag = null
    }

    /** 当前待办是本进程登记、且登记时的会话标签晚于 [through]（原会话结束之后新建的）。 */
    @Suppress("ComplexCondition") // 记录在、标签在、同一条、标签更晚四项同时成立
    private fun ownedByLaterSession(through: Long): Boolean {
        val current = pendingStore.load()
        val tagged = pendingTag
        return current != null && tagged != null && sameRecord(tagged.first, current) && tagged.second > through
    }

    /** 刚登记的 pending_action 记下此刻的会话标签。 */
    private fun tagPending() {
        val tag = sessionTag?.invoke()
        val record = pendingStore.load()
        pendingTag = if (tag != null && record != null) record to tag else null
    }

    /** 同一条登记（计时起点会因完成前置步骤而重置，不参与比较）。 */
    private fun sameRecord(
        lhs: PendingAction,
        rhs: PendingAction,
    ): Boolean = lhs.action == rhs.action && lhs.params == rhs.params && lhs.origin == rhs.origin

    /** 切换环境（或环境闸门重新对账）：代次加一，清除 pending_action。 */
    fun onEnvironmentChanged() {
        awaitingConsentDecision = false
        pendingHeld = false
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

/** 仍在检查而排队器已等满（[releasedUpdate] 非空）时，强更状态换成交出时的状态（F-ALL-03）；否则原样。 */
private fun GuardState.afterQueueWait(releasedUpdate: UpdateState?): GuardState =
    if (releasedUpdate != null && updateState == UpdateState.CHECKING) copy(update = releasedUpdate.raw) else this
