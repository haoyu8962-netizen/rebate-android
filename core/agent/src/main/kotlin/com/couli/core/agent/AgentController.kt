package com.couli.core.agent

import com.couli.core.clientcore.ClientEvent
import com.couli.core.clientcore.ClientEvents
import com.couli.core.clientcore.RouteEntryBlockedEntry
import com.couli.core.designsystem.CouliFormat
import com.couli.core.orders.CreditPeriodText
import com.couli.core.orders.OrderDraftTexts
import com.couli.core.orders.OrderStatusText
import com.couli.core.router.RouteRequest
import com.couli.core.router.RouteSource
import com.couli.core.wallet.WalletRateGate
import com.couli.generated.DefaultTexts
import com.couli.generated.RouteSpec
import com.couli.generated.Routes
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 控制器依赖（App 的 AppAgent 组装；单测按同一接线组装）。[currentIdentity] 只用于**比较**；[contract] 为 null = Release / 没有端口（「未开放」）。
 * [open]：经 Router 打开（只在用户点击时调用）。[recordConsent]：recordConsent{type: ai_third_party, accepted, channel: agent_sheet}（G16）。
 */
@Suppress("LongParameterList") // 控制器的外部能力逐项注入
class AgentDeps(
    val identity: AgentIdentity,
    val currentIdentity: () -> AgentIdentity?,
    val contract: AgentContractPort?,
    val gate: WalletRateGate,
    val scope: CoroutineScope,
    val timer: AgentTimer,
    val clock: () -> Long = System::currentTimeMillis,
    val routes: Map<String, RouteSpec> = Routes.all,
    /** 金额隐藏（AmountMaskStore，与钱包、订单页同一偏好）。 */
    val masked: StateFlow<Boolean> = MutableStateFlow(false),
    val open: (RouteRequest) -> Unit = {},
    /** 卡片购买：交已有 OpenPurchaseController（场景 agent）。 */
    val buy: (JsonObject, AgentProductRef) -> Unit = { _, _ -> },
    /** 企业微信客服（APP-12 已有的统一客服出口，G21）。 */
    val customerService: () -> Unit = {},
    val recordConsent: suspend (Boolean) -> AgentHttpResult = { AgentHttpResult.notSentResult() },
    val track: (ClientEvent) -> Unit = {},
    val toast: (String) -> Unit = {},
    /** 10405：照已有分流进强更。 */
    val forceUpdate: () -> Unit = {},
    /** 购买执行器的在途尝试作废（【停止】、【新对话】：只挡外跳，之后的响应不再更新卡片，鸿蒙 R1-03）。 */
    val cancelPurchase: () -> Unit = {},
    /** 当前会员是否已绑手机（额度用完时已绑手机不再引导绑定，鸿蒙 H10；未知为 null，按未验证处理）。 */
    val phoneBound: () -> Boolean? = { null },
    /** 只写事件名与结果，不写 data、金额。 */
    val log: (String) -> Unit = {},
    val examples: () -> List<String> = { emptyList() },
    val filingText: () -> String = { "" },
    /** 情境入口（G27）：in_app 入口交来的一次性 context，附在第一条消息上。 */
    val context: JsonObject? = null,
    val platformName: (String) -> String = { OrderDraftTexts.platformName(it).ifEmpty { it } },
    /**
     * 身份初始化失败后的【重试】（F-AND-28，FU-AND-0714 第 1 轮评审 R1-04）：会员但账号读不出的控制器停在可恢复的加载失败；用户点【重试】时
     * 控制器核对页面票据与登录世代后调它，App 同步作废这个控制器（清空）并按此刻的身份重建。缺省不做（单测）。
     */
    val rebuildIdentity: () -> Unit = {},
    /**
     * APP-10b：三组 Retry-After 闸门（发送 / 读取 / 动作，按不含页面实例的会话归属，App 级跨页面实例保留，G24、G55、鸿蒙 R3-03）。为 null 时三组都退回
     * APP-10 的共享闸门 [gate]（旧测试接线）。
     */
    val gates: AgentGates? = null,
    /** 同一 run 只发 1 次 cancel 的账本（App 级，与归属协调器共用；重进页面也不再发，G01、G25）。 */
    val ledger: AgentCancelLedger = AgentCancelLedger(),
    /** 此刻不含页面实例的归属键（App 给；【停止】cancel 的准入与结果按它核对，鸿蒙 R3-01 / G77）。为 null 时按完整身份比较。 */
    val currentOwnerKey: (() -> Any?)? = null,
    /** 任何来源的 AI 10004 汇入应用级「AI 同意失效」（参数为构造时固定的不含页面实例的归属键，共性第 33 条）。 */
    val aiConsentLost: (Any) -> Unit = {},
    /** 构造时这个归属已处于应用级「AI 同意失效」：一开始就是「需要重新同意」，不取会话、不拉历史（鸿蒙 R2-02）。 */
    val consentLapsed: Boolean = false,
    /** 应用级准入（身份变更在途、AI 同意失效、配置关闭；鸿蒙 R1-03、R1-07）：控制器准入与端口出口同时核对。 */
    val allowed: () -> Boolean = { true },
    /** App 此刻在前台（后台不发起任何重试，03 §7.2、G38）。 */
    val foreground: () -> Boolean = { true },
    /** 退避抖动的随机数（[0, 1)，可注入，G38）。 */
    val random: () -> Double = { Math.random() },
    /** 「身份变更在途」期间页面要一个控制器时建的挂起态（BLOCKED：空白、不发任何请求；流程结束后 App 重建，鸿蒙 R1-03）。 */
    val suspended: Boolean = false,
)

/** 控制器内部的一轮对话。 */
private class Turn(
    var run: AgentRunTicket,
    val userText: String,
    val body: String,
    /** 发送时刻（历史对齐的时间窗下界用，G05）；历史轮为 created_at。 */
    val sentAtMs: Long = 0,
) {
    /** 历史接口给的这一轮（冷启动恢复、重载替换、翻页、追加）：只用历史形状，没有 run_id、不给【停止】（编排者补充第 3 条）。 */
    var history = false

    /** 发出时的进入世代（发送结果的导航、提示、弹框都按它核对，鸿蒙 R1-05）。 */
    var entry = 0L

    /** 历史里这一轮用户消息的 id（对齐与追加去重用）。 */
    var userMessageId: String? = null

    /** 可信的回复 message_id（流内 meta 给的、或历史里确为 assistant 的；G03）。反馈 / 举报只对它开放。 */
    var replyId: String? = null

    /** meta.message_id 在历史里指向 user（G03 示例冲突）：不当回复 id 用，入口不显示。 */
    var replyIdDistrusted = false

    /** duplicate 收尾尚未以历史替换（G28：替换后才显示【赞】【踩】）。 */
    var duplicatePending = false
    val seq = AgentSeqGuard()

    /** 缺号：本条 incomplete，连接保持只等终止帧或断线（G11）。 */
    var gapped = false

    /** 重载状态机（断线【重试】、缺号、duplicate 收尾、cancel 30505、【刷新】）。 */
    var reconnect: AgentReconnect? = null
    var reloadTimer: (() -> Unit)? = null

    /** 【停止】流程（meta 前点停止显示「正在停止」，G26）。 */
    var stopFlowRef: AgentStopFlow? = null

    val stopFlow: AgentStopFlow? get() = stopFlowRef
    var stopTimer: (() -> Unit)? = null
    val feedback = AgentFeedbackState()
    val report = AgentReportState()

    /**
     * 这条回复的动作票据（Android R2-01，共性第 40 条）：历史替换 / 重载重置反馈与举报状态时 +1，之前发出的反馈 / 举报结果只做共享结算（AI 10004、42901、10405、
     * 30501），不再更新这一轮的新状态。
     */
    var actionSeq = 0L

    /** duplicate 30506 之后的【刷新】（G40，用户点才拉历史）。 */
    var refreshable = false

    var state: AgentStreamState = StreamReducer.initial()
    var phase: String = AgentTurnPhase.CONNECTING
    var client: StreamClient<AgentRunTicket>? = null
    var error: AgentErrorView? = null

    /** 同一 client_msg_id 重发中：duplicate 流不并入原消息（G26）。 */
    var resend = false

    /** 用户已启动的恢复流程重发过（断线【重试】，R1-03）：之后本地没发出也保留原轮次、只留【重试】，不删除。 */
    var resumed = false
    var resendState: AgentStreamState = StreamReducer.initial()
    var consentReplays = 0

    /** 【停止】过的这一轮：卡片照常显示，按钮与购买一律失效（鸿蒙 R1-03）。 */
    var revoked = false

    /** 本轮响应头的 X-Trace-Id（流内 error 的 trace6，鸿蒙 R1-07）。 */
    var traceId: String = ""

    /** 已记过 route_entry_blocked 的卡（同一卡同一对话只记一次，G10）。 */
    val blockedReported = mutableSetOf<String>()

    /** 购买结果对商品卡的更新（card_id#item_card_id → 新卡）。 */
    val products = mutableMapOf<String, JsonObject>()

    val stopping: Boolean get() = stopFlowRef?.phase == AgentStopPhase.STOPPING
}

/**
 * APP-10 §2 第 9 条：AgentController（页面状态机，界面无关；三端同一套，鸿蒙 AgentController.ets 经三轮评审）。页面在 feature:agent，组合在 app（AppAgent）。
 *
 * - **身份构造时一次取得、之后只比较**（brief §0 第 3 条，APP-09 第 63、65 条）：[AgentIdentity] 在构造时固定；会话（session_id）、对话世代（【新对话】+1）、
 *   run 票据（client_msg_id + 本地 run 序号）都在它之下派生。请求、连接、计时器、卡片回调、购买、同意弹窗、停止只用这一份。
 * - **流的每一帧在发起时就绑定 run 票据**：先比票据（控制器存活、对话世代、run 是当前活动 run），不等即丢弃——不改消息、不改额度、不写卡片、不发埋点、
 *   不弹任何层。归属变化由 App 在同步出口 [dispose] 整个控制器并按新身份重建（不发 cancel，G06）。
 * - 消息、卡片、收益金额、client_msg_id、输入草稿都只在内存（G15），不写任何本机存储，不进日志与埋点。
 * - 只在主线程使用。
 */
@Suppress("TooManyFunctions", "LargeClass") // 页面状态机的全部分支集中在一处，逐条对照 03 §7 与鸿蒙三轮评审
class AgentController(
    private val deps: AgentDeps,
) : AgentOwnershipTarget,
    AgentConsentLapseTarget {
    val serial: Long = SERIALS.incrementAndGet()
    private val identity: AgentIdentity = deps.identity

    /** 构造时固定的不含页面实例的归属键（应用级「AI 同意失效」、三组闸门、【停止】cancel 的结果按它核对，鸿蒙 R3-01）。 */
    private val ownerKey: Any = identity.ownerKey

    /**
     * 会员但账号未知（F-AND-28，R1-04：已登录却读不出账号）：可恢复的初始化失败——不取会话、不建会话、不发消息流（canSend = false），
     * 实际发包出口同样拒绝；【重试】经 [AgentDeps.rebuildIdentity] 清空并按新身份重建。
     */
    private val identityUnknown: Boolean = identity.member && identity.account == null
    private val gates: AgentGates = deps.gates ?: AgentGates.shared(deps.gate)
    private var disposed = false
    private var started = false
    private var phase: String = AgentPhase.IDLE
    private var conversation = 1L
    private var runSeq = 0L
    private var sessionId: String? = null
    private var turns: List<Turn> = emptyList()
    private var active: Turn? = null
    private var quotaExhausted = false
    private var quotaLeft: Long? = null
    private var consent: AgentConsentTicket? = null
    private var consentSerial = 0L
    private var consentGate: String = AgentConsentGate.CLEAR

    /** 提交中的那一张弹窗票据（结果只认它）。 */
    private var submitting: AgentConsentTicket? = null
    private var consentFailed = false

    /** 页面此刻可见（Tab 选中、没被覆盖、在前台；App 在同步可见性出口写入）。 */
    private var visible = false

    /**
     * 进入世代：每次离开（不可见）+1——只撤销导航、弹层与读屏播报的许可（APP-10b G02、编排者补充第 2 条：流、计时、在途请求照常）；之前发起的异步结果
     * 照常结算、改内存，但不导航、不弹框、不提示。
     */
    private var entry = 0L

    /** 卡片动作世代：【停止】、【新对话】、流内 10004 +1（鸿蒙 R1-03）。 */
    private var cardEpoch = 0L
    private var loadErrorText = ""
    private var context: JsonObject? = validContext(deps.context)
    private var revision = 0L
    private var announcement = ""
    private val subscriptions = mutableListOf<Job>()
    private val mutableSnapshot = MutableStateFlow(AgentSnapshot())
    val snapshot: StateFlow<AgentSnapshot> = mutableSnapshot.asStateFlow()

    /** 隐藏期间不把快照交给界面，回到可见时一次性应用（G02）。 */
    private val publishGate = AgentPublishGate()

    /** 本次进入周期收到 30501（七个 operation 与流内）：之后不再发任何 agent 请求（鸿蒙 R1-06、G62）。 */
    private var unavailableLocked = false

    /** 会话已过期（expired=true）：整段只读（G09）。 */
    private var readOnly = false

    /** 会话已结束（历史 / 翻页 30504）的提示（error.30504）。 */
    private var sessionEndedText = ""

    /** 向上翻页（G08）。没有拉过历史为 null。 */
    private var pager: AgentHistoryPager? = null
    private var earlierError = ""

    /** 历史票据：作废（新对话、停止、10004、归属变化）时 +1，之前发出的历史、翻页、重载结果只结算共享事实（鸿蒙 R2-03）。 */
    private var historySeq = 0L

    /** 反馈 / 举报的工作世代（【停止】、10004 作废 +1）。 */
    private var workSeq = 0L

    /** 正在把 AI 10004 上报给应用级入口（防重入：App 回调本控制器的 [consentLapsed] 时跳过，鸿蒙 G78）。 */
    private var lapseReporting = false

    /** 此刻打开举报半屏的那一轮（只关本实例）。 */
    private var reportTurn: Turn? = null

    init {
        phase =
            when {
                deps.suspended -> AgentPhase.BLOCKED

                deps.contract == null -> AgentPhase.UNAVAILABLE

                identity.basic -> AgentPhase.BLOCKED

                identityUnknown -> AgentPhase.LOAD_FAILED

                // 应用级「AI 同意失效」（鸿蒙 R2-02）：重建出来的控制器一开始就是「需要重新同意」，不取会话、不拉历史
                deps.consentLapsed -> AgentPhase.CONSENT_DECLINED

                else -> AgentPhase.IDLE
            }
        if (deps.consentLapsed) {
            consentGate = AgentConsentGate.REFUSED
            started = true
        }
        if (identityUnknown) loadErrorText = errorText(AgentErrorCodes.GENERIC, "")
        // 共享订阅（三组限流闸门、金额隐藏偏好）：作废时释放（APP-12 第 1 条：旧对话含收益金额不再可达）
        listOf(gates.send, gates.read, gates.action).map { it.blocked }.distinct().forEach { flow ->
            subscriptions += deps.scope.launch { flow.drop(1).collect { publish() } }
        }
        subscriptions += deps.scope.launch { deps.masked.drop(1).collect { publish() } }
        mutableSnapshot.value = buildSnapshot()
    }

    /** 控制器仍有效：没作废、此刻身份与构造时一致。 */
    fun alive(): Boolean = !disposed && runCatching { deps.currentIdentity() == identity }.getOrDefault(false)

    val boundIdentity: AgentIdentity get() = identity

    /** 此刻的卡片动作世代（App 把购买执行器绑定到它，Android R1-02）。 */
    val cardActionEpoch: Long get() = cardEpoch

    /** 每轮 assistant 消息的流状态（只读；测试按 G32 规范化比较用）。 */
    val streamStates: List<AgentStreamState> get() = turns.map { it.state }

    val currentPhase: String get() = phase

    /** 当前活动 run（测试与诊断用）。 */
    val activeRun: AgentRunTicket? get() = active?.run

    val isDisposed: Boolean get() = disposed

    val isVisible: Boolean get() = visible

    val consentState: String get() = consentGate

    /** 收益卡是否有实时金额正在显示（测试用：金额只在内存，不落盘）。 */
    val liveEarningsShown: Boolean get() = turns.any { t -> t.state.cards.any { it.liveEarnings } }

    /** 当前活动流的交付计数（抓包断言：作废之后不再变化）。 */
    val streamDeliveries: Int get() = turns.sumOf { it.client?.delivered ?: 0 }

    /** 本次进入周期已锁「未开放」（30501）。 */
    val unavailableForEntry: Boolean get() = unavailableLocked

    override fun boundOwnerKey(): Any = ownerKey

    /** 此刻的归属键（App 给的不含页面实例的；没有接时按完整身份）。 */
    private fun ownerNow(): Any? =
        deps.currentOwnerKey?.let { runCatching(it).getOrNull() } ?: runCatching { deps.currentIdentity()?.ownerKey }.getOrNull()

    /** 原归属（不含页面实例）此刻仍是同一归属。 */
    private fun ownerStill(): Boolean = ownerNow() == ownerKey

    /** 第一次显示：取当前会话，有会话再取历史首页（只读，不建会话、不发消息，G39）。Release 关闭 / 30501 为「未开放」，不发请求。 */
    @Suppress("ComplexCondition") // 条件逐项
    fun start() {
        if (started || !alive() || !visible) return
        started = true
        if (phase == AgentPhase.UNAVAILABLE || phase == AgentPhase.BLOCKED || identityUnknown || phase == AgentPhase.CONSENT_DECLINED) {
            publish()
            return
        }
        load()
    }

    @Suppress("ReturnCount") // 账号未知、没有端口、读取闸门依次返回
    private fun load() {
        if (identityUnknown) return
        val contract = deps.contract ?: return
        if (gates.read.active) {
            // 读取闸门（42901）期间不发：整页显示加载失败 + 受读取闸门控制的【重试】（APP-12 第 23 条）
            phase = AgentPhase.LOAD_FAILED
            loadErrorText = errorText(AgentErrorCodes.RATE_LIMITED, "")
            publish()
            return
        }
        phase = AgentPhase.LOADING
        loadErrorText = ""
        publish()
        val conversation = this.conversation
        val entry = this.entry
        val seq = historySeq
        // 这次取会话只在仍处于本次加载时发出（鸿蒙 R2-02）
        val call = call(AgentOperation.CURRENT) { phase == AgentPhase.LOADING && seq == historySeq }
        deps.scope.launch {
            val r = contract.currentSession(call)
            // 先核对归属，再核对对话与历史票据；不合的结果只结算共享事实，不改内存与界面
            // 先共享结算、后页面判断（R3-01）：控制器已作废（页面重建 / 换身份）时只按原 ownerKey 结算共享事实
            if (settleFirst(r, AgentOperation.CURRENT)) return@launch
            if (conversation != this@AgentController.conversation || seq != historySeq || phase != AgentPhase.LOADING) {
                settleOnly(r, AgentOperation.CURRENT)
                return@launch
            }
            if (!r.ok) {
                loadFailed(r, AgentOperation.CURRENT, entry)
                return@launch
            }
            // 「成功但为空」（session=null）与「加载失败」分开（APP-11 第 6 条）：空会话等第一次发送时再建
            val id = sessionIdOf(AgentJson.objectOf(r.data["session"]))
            sessionId = id
            if (id == null) {
                phase = AgentPhase.READY
                publish()
                return@launch
            }
            loadFirstPage(id, entry)
        }
    }

    /** 进入页面拿到当前会话后取历史首页（limit=20，G08、G39；ApiClient 的 GET 自动重试 1 次照旧，G35）。冷启动 / 页面实例新建：整页替换空对话（G06）。 */
    @Suppress("ComplexCondition") // 条件逐项
    private fun loadFirstPage(
        id: String,
        entry: Long,
    ) {
        val contract = deps.contract ?: return
        val conversation = this.conversation
        val seq = historySeq
        val call = call(AgentOperation.HISTORY) { phase == AgentPhase.LOADING && seq == historySeq && sessionId == id }
        deps.scope.launch {
            val r = contract.listMessages(id, null, AgentHistory.LIMIT, call)
            // 先共享结算、后页面判断（R3-01）：控制器已作废（页面重建 / 换身份）时只按原 ownerKey 结算共享事实
            if (settleFirst(r, AgentOperation.HISTORY)) return@launch
            if (conversation != this@AgentController.conversation || seq != historySeq || sessionId != id || phase != AgentPhase.LOADING) {
                settleOnly(r, AgentOperation.HISTORY)
                return@launch
            }
            if (!r.ok) {
                loadFailed(r, AgentOperation.HISTORY, entry)
                return@launch
            }
            val page = AgentHistory.page(r.data, deps.routes)
            if (page == null) {
                // 外壳成功但形状不对：按加载失败（不当成空对话，APP-12 第 23 条）
                loadFailed(AgentHttpResult.network(), AgentOperation.HISTORY, entry)
                return@launch
            }
            turns = historyTurns(page.items)
            pager = AgentHistoryPager(page.nextCursor)
            earlierError = ""
            if (page.dropped > 0) deps.log("agent history dropped ${page.dropped}")
            phase = AgentPhase.READY
            if (page.expired) applyExpired()
            publish()
        }
    }

    /** 取会话 / 历史首页失败（G24 表）：30501 → 未开放；AI 10004 → 锁闸门（取会话弹框）；30504 → 空对话 + error.30504；其余 → 加载失败 + 统一文案（5xxxx 带 trace6）。 */
    private fun loadFailed(
        r: AgentHttpResult,
        op: String,
        entry: Long,
    ) {
        val d = AgentTransportErrorPolicy.decideResult(op, r, current(entry), true)
        when (sharedError(r, null, op, entry)) {
            AgentSharedError.UNAVAILABLE -> {
                Unit
            }

            AgentSharedError.CONSENT -> {
                if (phase == AgentPhase.LOADING) phase = AgentPhase.READY
            }

            else -> {
                if (d.action == AgentOpDecision.EMPTY_CONVERSATION) {
                    // 历史 30504：清掉本地 session_id、显示空对话 + error.30504（下次发送重新建会话）
                    sessionId = null
                    sessionEndedText = errorText(AgentErrorCodes.ROUND_LIMIT, "")
                    phase = AgentPhase.READY
                } else {
                    phase = AgentPhase.LOAD_FAILED
                    loadErrorText = if (r.notSent) errorText(AgentErrorCodes.GENERIC, "") else errorText(r.codeOrGeneric, r.traceId)
                }
            }
        }
        publish()
    }

    /** 过期会话（expired=true，G09）：整段只读——输入框、胶囊、示例问题禁用，显示 error.30504 +【新对话】；卡片按钮、反馈 / 举报照常。 */
    private fun applyExpired() {
        readOnly = true
        sessionEndedText = errorText(AgentErrorCodes.ROUND_LIMIT, "")
    }

    /** 历史项 → 本地轮（按 user + 其后的 assistant 分组；页首没有 user 的回复自成一轮）。 */
    private fun historyTurns(items: List<AgentHistoryItem>): List<Turn> = AgentHistory.groups(items).flatMap(::historyTurnsOf)

    private fun historyTurnsOf(g: AgentHistoryGroup): List<Turn> {
        val replies = g.replies.ifEmpty { listOf(null) }
        return replies.mapIndexed { i, reply -> historyTurn(if (i == 0) g.user else null, reply) }
    }

    private fun historyTurn(
        user: AgentHistoryItem?,
        reply: AgentHistoryItem?,
    ): Turn {
        val id = reply?.messageId ?: user?.messageId ?: ""
        runSeq += 1
        val run = AgentRunTicket(serial, conversation, "h:$id#$runSeq", "")
        val t = Turn(run, user?.text.orEmpty(), "", user?.createdAtMs ?: 0)
        t.history = true
        t.userMessageId = user?.messageId
        t.phase = AgentTurnPhase.ENDED
        if (reply != null) applyHistoryReply(t, reply)
        return t
    }

    /** 历史回复写进这一轮（只用历史形状：收益卡历史形状、没有 run_id、不给【停止】、不更新额度，G42、AC-S1-148 ④）。 */
    private fun applyHistoryReply(
        t: Turn,
        reply: AgentHistoryItem,
    ) {
        t.replyId = reply.messageId
        t.replyIdDistrusted = false
        t.duplicatePending = false
        t.state =
            AgentStreamState(
                status = reply.status,
                metaSeen = true,
                messageId = reply.messageId,
                text = reply.text,
                cards = reply.cards,
            )
        t.error = if (reply.status == AgentStatus.ERROR) AgentErrorView(errorText(AgentErrorCodes.GENERIC, "")) else null
        // 历史替换只作废这条回复旧动作的票据（R2-01）：在途的反馈 / 举报结果之后只做共享结算，不更新这里重置后的新状态
        t.actionSeq += 1
        t.feedback.reset()
        t.report.reset()
        t.phase = AgentTurnPhase.ENDED
    }

    // ---------------------------------------------------------------- 七个 operation 共用的错误规则（APP-10 共性第 6、18 条，APP-10b §2 第 10 条）

    /** 这个 operation 所属的 Retry-After 闸门（G24：发送 / 读取 / 动作三组）。 */
    private fun gate(op: String): AgentGate = gates.of(op)

    /**
     * 七个 operation 同一套（调用方已核对归属与对话）：42901 → 所属闸门（Retry-After 缺省 5 秒）；10405 → 强更；AI 10004 → 同步作废并锁同意闸门（只有发送类
     * 在发起时的进入世代仍有效、页面可见时才弹框，[pendingRun] 为待重放的 run）；10001 / 10005 / 非 AI 10004 → 统一分流（可见才导航）；30501 → 本次进入锁「未开放」；
     * 本地没发出 → 静默；其余交调用方。
     */
    @Suppress("ReturnCount") // 没发出、按码、同意、统一分流依次返回
    private fun sharedError(
        r: AgentHttpResult,
        pendingRun: String?,
        op: String = AgentOperation.SEND,
        entry: Long = this.entry,
    ): String {
        if (r.notSent) return AgentSharedError.SILENT
        when (r.code) {
            AgentErrorCodes.RATE_LIMITED -> {
                gate(op).start(AgentSendErrorPolicy.retryAfterMs(r.retryAfter))
                return AgentSharedError.RATE_LIMITED
            }

            AgentErrorCodes.FORCE_UPDATE -> {
                deps.forceUpdate()
                return AgentSharedError.FORCE_UPDATE
            }

            AgentErrorCodes.UNAVAILABLE -> {
                lockUnavailable()
                return AgentSharedError.UNAVAILABLE
            }

            AgentErrorCodes.RUN_MISSING -> {
                if (op == AgentOperation.CANCEL) return AgentSharedError.SILENT
            }
        }
        if (AgentConsentPolicy.shows(r.code, r.consentType)) {
            val interactive = AgentTransportErrorPolicy.sendClass(op) && current(entry)
            aiConsentRevoked(if (interactive) pendingRun else null, interactive)
            return AgentSharedError.CONSENT
        }
        if (r.code == AgentErrorCodes.LOGIN || r.code == AgentErrorCodes.CONSENT || r.code == AgentErrorCodes.BIND_PHONE) {
            if (current(entry)) unified(r)
            return AgentSharedError.UNIFIED
        }
        return AgentSharedError.OTHER
    }

    /**
     * 七个 operation 的 HTTP 结果（含流式请求没成为流的 JSON 外壳）回到主线程后的统一收口（Android R3-01，共性第 33、35、45 条，S0）：**先**按发起时不含页面实例的
     * ownerKey 结算跨页面共享事实，**再**由调用方做页面 alive / 对话 / 动作票据判断。返回 true = 控制器已作废，结算到此为止，调用方不再碰消息、提示、导航。
     * - 控制器仍有效：返回 false，调用方照常判票据（不合的走 [settleOnly]，合的走 [sharedError]：共享事实在那两处结算，弹框、提示、导航按页面许可）。
     * - 控制器已作废、仍是同一归属（页面实例销毁 / 以相同 ownerKey 重建）：AI 10004 汇入应用级失效入口 [AgentDeps.aiConsentLost]（App 同步停下此刻同归属的控制器：
     *   关新流、取消所属请求、锁同意闸门）；42901 按三组闸门归属写入（App 级，按归属保存，重建页同样受限）；10405 强更。30501 只锁本次进入周期，作废的控制器已没有
     *   进入周期，不处理（重建页由服务端再给）。
     * - 不同归属（换号、撤回、切环境）：整体丢弃，只保留 10405 强更（应用级事实，与 G70 / 【停止】cancel 同口径）。
     */
    @Suppress("ReturnCount") // 有效、无事可结、换归属、同归属依次返回
    private fun settleFirst(
        r: AgentHttpResult,
        op: String,
    ): Boolean {
        if (alive()) return false
        if (r.ok || r.notSent) return true
        if (!ownerStill()) {
            if (r.code == AgentErrorCodes.FORCE_UPDATE) runCatching { deps.forceUpdate() }
            return true
        }
        when {
            r.code == AgentErrorCodes.RATE_LIMITED -> gate(op).start(AgentSendErrorPolicy.retryAfterMs(r.retryAfter))
            r.code == AgentErrorCodes.FORCE_UPDATE -> runCatching { deps.forceUpdate() }
            AgentConsentPolicy.shows(r.code, r.consentType) -> runCatching { deps.aiConsentLost(ownerKey) }
        }
        return true
    }

    /**
     * 迟到的结果（旧对话、旧 run、已被新请求取代、历史票据已作废；调用方已核对固定身份仍是同一身份）：只结算同一身份的共享事实——42901 所属闸门、10405 强更、
     * AI 10004（同步作废并锁闸门，但不弹框，鸿蒙 R1-01、R2-01）、30501（本次进入不再发，R1-06）；不弹层、不导航、不改消息。
     */
    private fun settleOnly(
        r: AgentHttpResult,
        op: String = AgentOperation.SEND,
    ) {
        if (r.notSent) return
        when {
            r.code == AgentErrorCodes.RATE_LIMITED -> {
                gate(op).start(AgentSendErrorPolicy.retryAfterMs(r.retryAfter))
                publish()
            }

            r.code == AgentErrorCodes.FORCE_UPDATE -> {
                deps.forceUpdate()
            }

            r.code == AgentErrorCodes.UNAVAILABLE -> {
                lockUnavailable()
                publish()
            }

            AgentConsentPolicy.shows(r.code, r.consentType) -> {
                aiConsentRevoked(null, interactive = false)
            }
        }
    }

    /** 30501（七个 operation 与流内，鸿蒙 R1-06）：同步作废本控制器的在途工作，本次进入周期一直「未开放」、不再发任何 agent 请求。 */
    private fun lockUnavailable() {
        if (unavailableLocked) return
        unavailableLocked = true
        haltOwnedWork(AgentErrorCodes.UNAVAILABLE)
        phase = AgentPhase.UNAVAILABLE
    }

    /**
     * 统一的同步作废入口（鸿蒙 R1-01，共性第 10、26 条）：关所有流、停 30 秒 / 正在停止 / 重载计时、cancelOwned、作废卡片回调与购买、作废在途历史页与翻页、关举报半屏；
     * 进行中的回复本地结束（error.<code>）、不更新额度。七个 operation 与流内的 AI 10004、30501 都先走它。
     */
    private fun haltOwnedWork(code: Int) {
        turns.forEach { t ->
            t.client?.cancel()
            t.client = null
            cancelTimers(t)
            t.reconnect = null
            t.feedback.abort()
            t.report.abort()
            if (!t.state.ended || t === active) {
                t.state = t.state.copy(status = AgentStatus.ERROR)
                t.error = AgentErrorView(errorText(code, ""))
                t.phase = AgentTurnPhase.ENDED
            }
        }
        active = null
        reportTurn?.report?.close()
        reportTurn = null
        historySeq += 1
        workSeq += 1
        pager?.failed()
        runCatching { deps.contract?.cancelOwned(identity) }
        revokeCardActions()
    }

    /**
     * 收到 AI 10004（APP-10 共性第 16、22 条，APP-10b 共性第 10、27、33 条，S0）：不论来自哪个 operation、流内、重放、旧 run、【停止】的 cancel，都先同步作废
     * 本控制器的在途工作（[haltOwnedWork]）并锁住同意闸门，再以构造时的不含页面实例的归属键上报应用级「AI 同意失效」（重建的控制器、新页面实例照样禁发，
     * 直到同一归属重新同意成功）。[interactive]：发送类且发起时的进入世代仍有效、页面可见 → 弹同意框（[pendingRun] 为待重放的 run）。
     */
    private fun aiConsentRevoked(
        pendingRun: String?,
        interactive: Boolean,
    ) {
        val keep = turns.firstOrNull { it.run.run == pendingRun }
        // HTTP 10004 不清空对话（G61）；待重放的那一轮保留为「待同意」
        val pending = keep?.takeIf { interactive && it.phase == AgentTurnPhase.AWAITING_CONSENT }
        if (pending != null) {
            // 先把待重放的那一轮挪开，免得被同步作废收尾成 error
            pending.client?.cancel()
            pending.client = null
        }
        val saved = pending?.let { it.state to it.error }
        haltOwnedWork(AgentErrorCodes.CONSENT)
        if (pending != null && saved != null) {
            pending.state = saved.first
            pending.error = saved.second
            pending.phase = AgentTurnPhase.AWAITING_CONSENT
        }
        consentLost(pending?.run?.run, interactive && (pending != null || pendingRun == null))
        if (consentGate == AgentConsentGate.CLEAR) consentGate = AgentConsentGate.REFUSED
        if (!lapseReporting) {
            lapseReporting = true
            try {
                runCatching { deps.aiConsentLost(ownerKey) }
            } finally {
                lapseReporting = false
            }
        }
        publish()
    }

    /** 应用级「AI 同意失效」下发（鸿蒙 R2-02）：同步作废并锁闸门（不弹框、不导航、不再上报）。 */
    override fun consentLapsed() {
        if (disposed || lapseReporting) return
        haltOwnedWork(AgentErrorCodes.CONSENT)
        consentLost(null, interactive = false)
        if (consentGate == AgentConsentGate.CLEAR) consentGate = AgentConsentGate.REFUSED
        publish()
    }

    /**
     * 锁同意闸门（APP-10 共性第 16、22 条，S0）。
     * - [interactive]（发起时的进入世代、对话与 operation 票据都仍有效，且页面可见）：弹同意框（[pendingRun] 为待重放的 run；重放过一次的不再给）。
     * - 否则（迟到结果、隐藏期间）：只锁闸门、不弹框（refused，回到页面显示说明与【重新同意】，鸿蒙 H27、G59）。
     * 提交中收到的 10004 来自提交之前发出的请求（闸门非 clear 时不发包）：不改提交中的状态，由记录结果决定（鸿蒙 H28）。已在待同意时不重复开框。
     */
    @Suppress("ReturnCount") // 提交中、待同意、弹框、只锁闸门依次处理
    private fun consentLost(
        pendingRun: String?,
        interactive: Boolean,
    ) {
        if (consentGate == AgentConsentGate.SUBMITTING) return
        if (consentGate == AgentConsentGate.AWAITING && consent != null) return
        if (interactive && visible) {
            openConsent(pendingRun)
            return
        }
        // 隐藏期间（G59）：不保留待重放消息，回到页面显示【重新同意】，用户自己重发
        pendingRun?.let(::dropPending)
        consent = null
        consentGate = AgentConsentGate.REFUSED
        consentFailed = false
        if (phase == AgentPhase.IDLE || phase == AgentPhase.LOADING || phase == AgentPhase.LOAD_FAILED) {
            started = true
            phase = AgentPhase.CONSENT_DECLINED
        }
        publish()
    }

    /**
     * 统一分流（APP-10 共性第 6 条）：10001 → Login、10005 → BindPhone、10004 按 consent_type（labor_agreement → LaborAgreement、id_verification →
     * RealName；privacy / agreement 没有可调用的统一入口，同鸿蒙 H20 只提示 error.10004）。只在发起时的进入世代仍有效、页面可见时打开（调用方已核对），
     * in_app、参数为空；不登记 pending_action、前置完成后不自动重发原消息（G19）。
     */
    private fun unified(r: AgentHttpResult) {
        val route = AgentPrereq.route(r.code, r.data)
        if (route != null) {
            deps.open(RouteRequest(route, AgentJson.EMPTY, RouteSource.IN_APP))
        } else {
            deps.toast(errorText(r.code, r.traceId))
        }
    }

    // ---------------------------------------------------------------- 可见性（APP-10b G02，编排者补充第 2 条）

    /**
     * 同步可见性出口（App 在切 Tab、被同栈页面覆盖、前后台变化时同步调用）。按 03 §7.2（2026-10-14 更正）：**切 Tab 不断流、不重拉历史**——流、30 秒 / 90 秒计时、
     * 在途请求照常；离开只让进入世代 +1（撤销导航、弹层与读屏播报的许可），隐藏期间快照照常算但不交给界面，回到可见时一次性应用。举报半屏随离开关闭（只关本实例）。
     * Activity 真正销毁、会话 / 身份事件由 App 经 [dispose] / [stopForOwnership] 断流。
     */
    fun setVisible(visible: Boolean) {
        if (disposed || visible == this.visible) return
        this.visible = visible
        if (visible) {
            // 回到可见：隐藏期间算好的状态一次性应用（G02）
            publishGate.setVisible(true)
            mutableSnapshot.value = buildSnapshot()
            return
        }
        publishGate.setVisible(false)
        entry += 1
        reportTurn?.report?.close()
        reportTurn = null
        // 隐藏期间结束的回复不播报（回来一次性应用的快照不带播报，避免重复曝光）
        announcement = ""
        publish()
    }

    /** App 前后台变化（03 §7.2、G38、鸿蒙 R1-08）：进后台暂停全部历史重载（退避等待与在途请求都结束为只留【重试】），回前台不续。 */
    fun onForeground(foreground: Boolean) {
        if (disposed || foreground) return
        turns.forEach { t -> t.reconnect?.let { r -> r.pause()?.let { step -> finishReload(t, r, step, entry) } } }
    }

    /** App 此刻在前台（读不出按后台）。 */
    private fun foregroundNow(): Boolean = runCatching(deps.foreground).getOrDefault(false)

    /** 发起时的进入世代仍有效且页面可见。 */
    private fun current(entry: Long): Boolean = entry == this.entry && visible

    /** 加载失败的【重试】（受读取闸门控制）。 */
    fun retryLoad(t: AgentPageTicket) {
        if (identityUnknown) {
            // 身份初始化失败（R1-04）：此刻的身份与构造时只差账号（同一页面实例、登录世代、隐私 / 环境世代、AI 同意世代）才交给 App 重建；
            // 账号此刻读得出时 [alive] 已为 false，所以不经 [pageOk]，改为逐项核对页面票据
            if (!disposed && ownTicket(t) && sameButAccount()) deps.rebuildIdentity()
            return
        }
        if (!pageOk(t) || phase != AgentPhase.LOAD_FAILED || gates.read.active) return
        load()
    }

    /** 此刻的身份与构造时的只差账号（构造时为 null）。 */
    private fun sameButAccount(): Boolean = runCatching { deps.currentIdentity()?.copy(account = null) == identity }.getOrDefault(false)

    // ---------------------------------------------------------------- 票据比较

    /** 页面票据有效：会员账号已知（R1-04：账号未知时一切正常入口都不受理）、控制器有效、票据是本控制器当前对话的。 */
    private fun pageOk(t: AgentPageTicket): Boolean = !identityUnknown && alive() && ownTicket(t)

    private fun ownTicket(t: AgentPageTicket): Boolean = t.controller == serial && t.conversation == conversation

    private fun runOk(t: AgentRunTicket): Boolean {
        val a = active ?: return false
        return alive() && AgentTicketPolicy.accept(t.json(), a.run.json())
    }

    private fun turnOf(
        controller: Long,
        conversation: Long,
        run: String,
    ): Turn? {
        if (!alive() || controller != serial || conversation != this.conversation) return null
        return turns.firstOrNull { it.run.run == run }
    }

    /**
     * 实际发包出口的闸门（APP-10 共性第 1、17 条，APP-10b G02）：创建请求时固定「对话世代 + 本 operation 的票据（[op]）」，sendAllowed 只比较这些固定值与此刻的控制器、
     * 所属 Retry-After 闸门、AI 同意闸门、30501 锁、应用级准入（身份变更在途、AI 同意失效、配置关闭）；任何一次核对不通过就永久失效（新对话、停止、同意被撤销
     * 后都不会恢复）。切 Tab 不撤销它（只撤销导航与弹层许可）。
     */
    private fun call(
        operation: String,
        scope: String = AgentCall.SCOPE_PAGE,
        op: () -> Boolean,
    ): AgentCall {
        val conversation = this.conversation
        val groupGate = gate(operation)
        var revoked = false
        return AgentCall(
            identityKey = identity,
            anonymous = !identity.member,
            sendAllowed = {
                if (revoked) {
                    false
                } else {
                    val ok =
                        runCatching {
                            !identityUnknown && !unavailableLocked && alive() && conversation == this.conversation && !groupGate.active &&
                                consentGate == AgentConsentGate.CLEAR && deps.allowed() && op()
                        }.getOrDefault(false)
                    if (!ok) revoked = true
                    ok
                }
            },
            owner = { alive() },
            scope = scope,
        )
    }

    // ---------------------------------------------------------------- 发送

    /** 输入框是否可发：就绪、不在发送中、不在禁用期、额度没用完、AI 同意已记录、页面可见、不是只读会话。 */
    val canSend: Boolean
        get() =
            !identityUnknown && alive() && visible && phase == AgentPhase.READY && active == null && !gates.send.active &&
                !quotaExhausted && consentGate == AgentConsentGate.CLEAR && !readOnly && !unavailableLocked

    /** 发送一条消息（文本 1–500 字，按 Unicode 码点；超出不让发送）。返回是否已受理。 */
    @Suppress("ReturnCount") // 票据、空、过长、限流、不可发依次返回
    fun send(
        t: AgentPageTicket,
        rawText: String,
    ): Boolean {
        if (!pageOk(t)) return false
        val text = rawText.trim()
        val n = AgentJson.codePoints(text)
        if (n == 0) return false
        if (n > MAX_INPUT) {
            deps.toast(AgentDraftTexts.text(TOO_LONG_KEY))
            return false
        }
        if (gates.send.active) {
            // 本地限流拦下时提示一次 42901（APP-09 第 45、56 条）
            deps.toast(errorText(AgentErrorCodes.RATE_LIMITED, ""))
            return false
        }
        if (!canSend) return false
        val clientMsgId = ClientMsgId.new()
        val run = newRun(clientMsgId)
        val body =
            JsonObject(
                buildMap {
                    put("client_msg_id", JsonPrimitive(clientMsgId))
                    put("text", JsonPrimitive(text))
                    context?.let { put("context", it) }
                },
            )
        // 情境只附在用户发的第一条上（G27）
        context = null
        sessionEndedText = ""
        val turn = Turn(run, text, body.toString(), deps.clock())
        turns = turns + turn
        active = turn
        announcement = ""
        publish()
        dispatch(turn)
        return true
    }

    private fun newRun(clientMsgId: String): AgentRunTicket {
        runSeq += 1
        return AgentRunTicket(serial, conversation, "$clientMsgId#$runSeq", clientMsgId)
    }

    /**
     * 发出（必要时先建会话）；每次异步等待结束后复核控制器、对话世代、run 票据（APP-04 第 30、60 条）。
     * [resume]：用户已启动的恢复流程（断线【重试】查历史后同一 client_msg_id 重发，G07）——切 Tab 保活（G02、Android R1-03）：页面隐藏不拦、不删已有轮次，
     * 隐藏期间只暂停界面发布、回到 Tab 一次性应用；同意闸门、30501、App 在后台照常拦下，但保留原轮次并给 RELOAD【重试】。
     */
    @Suppress("CyclomaticComplexMethod", "ReturnCount", "ComplexCondition") // 闸门、已有会话、建会话依次处理
    private fun dispatch(
        turn: Turn,
        resume: Boolean = false,
    ) {
        val run = turn.run
        val contract = deps.contract ?: return
        val blocked = consentGate != AgentConsentGate.CLEAR || unavailableLocked
        if (resume && (blocked || !runCatching(deps.foreground).getOrDefault(false))) {
            // 恢复流程被闸门 / 后台拦下：不发，原消息与已收到的部分回复保留，只留【重试】（R1-03）
            disconnectedForRetry(turn)
            publish()
            return
        }
        if (!resume && (blocked || !visible)) {
            // 实际发送出口（APP-10 共性第 1、2 条）：同意未记录成功或页面不可见时一律不发；这一轮不留在列表里
            removeTurn(turn)
            publish()
            return
        }
        turn.entry = this.entry
        sessionId?.let {
            stream(turn, it)
            return
        }
        val entry = this.entry
        // 只为这一轮、这一个 run 建会话（停止、新对话、重试换 run 后都永久失效，鸿蒙 R2-02）；挂在 run:<run> 子归属上（【停止】同步取消它，R1-11）
        val call =
            call(AgentOperation.CREATE, AgentCall.runScope(run.run)) {
                active === turn && turn.run == run && sessionId == null && (!resume || foregroundNow())
            }
        deps.scope.launch {
            val r = contract.createSession(call)
            // 先核对固定身份；旧 run 的结果只结算共享事实（42901、10405、AI 10004、30501），再按票据决定是否改界面（共性第 18 条）
            // 先共享结算、后页面判断（R3-01）：控制器已作废（页面重建 / 换身份）时只按原 ownerKey 结算共享事实
            if (settleFirst(r, AgentOperation.CREATE)) return@launch
            if (!runOk(run) || active !== turn) {
                settleOnly(r, AgentOperation.CREATE)
                return@launch
            }
            val id = if (r.ok) sessionIdOf(AgentJson.objectOf(r.data["session"]) ?: r.data) else null
            if (id == null) {
                jsonFailed(turn, if (r.ok) AgentHttpResult.network() else r, AgentOperation.CREATE, entry)
                return@launch
            }
            sessionId = id
            if (resume && !foregroundNow()) {
                // 恢复流程的异步续发在后台到达（Android R2-04，共性第 42 条）：会话照常记下，不发流；保留原轮次与同一 client_msg_id，恢复为 RELOAD【重试】，
                // 回前台不自动续发（与切 Tab 保活区分：页面隐藏但 App 在前台时照发，R1-03）
                disconnectedForRetry(turn)
                publish()
                return@launch
            }
            stream(turn, id)
        }
    }

    private fun stream(
        turn: Turn,
        sessionId: String,
    ) {
        val contract = deps.contract ?: return
        val client =
            StreamClient(contract, deps.timer, turn.run) { ticket: AgentRunTicket, event: AgentStreamEvent -> onStream(ticket, event) }
        client.log = deps.log
        // 流已关闭（作废 / 页面重建）后才回到主线程的 JSON 外壳：不交界面，只进收口函数结算共享事实（R3-01）
        client.late = { _: AgentRunTicket, result: AgentHttpResult -> lateJson(result) }
        turn.client = client
        turn.gapped = false
        val run = turn.run
        // 许可绑定这一轮、这个 run、这一个流客户端（停止、断线收尾、重试换 run 之后都永久失效）；挂在 stream:<run> 子归属上（【停止】在 meta 前保留它，R2-03）
        // 恢复流程（resumed）的最终发送准入另复核前后台（R2-04）：后台时不写出，按 notSent 收尾为保留原轮次的 RELOAD【重试】
        val resumed = turn.resumed
        client.start(
            sessionId,
            turn.body,
            call(AgentOperation.SEND, AgentCall.streamScope(run.run)) {
                active === turn && turn.run == run &&
                    turn.client === client && (!resumed || foregroundNow())
            },
        )
    }

    /** 流已关闭后才到的 JSON 外壳（R3-01）：先经收口函数（控制器已作废 → 按原 ownerKey 结算），控制器仍有效也只结算共享事实，不改消息、不提示、不导航。 */
    private fun lateJson(r: AgentHttpResult) {
        if (settleFirst(r, AgentOperation.SEND)) return
        settleOnly(r, AgentOperation.SEND)
    }

    /**
     * 流事件：先比票据（控制器存活、对话世代、run 是当前活动 run），不等即丢弃，什么都不改（迟到的 JSON 错误与流内 10004 / 30501 只结算共享事实）。
     * 切 Tab 不断流（G02）：隐藏期间照常处理、只是不交界面。
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod", "NestedBlockDepth", "LongMethod", "ComplexCondition") // 票据、各事件依次处理
    private fun onStream(
        ticket: AgentRunTicket,
        event: AgentStreamEvent,
    ) {
        val stopping = turns.firstOrNull { it.run == ticket && it.stopping }
        if (stopping != null) {
            onStoppingStream(stopping, event)
            return
        }
        if (!runOk(ticket)) {
            // 先共享结算、后页面判断（R3-01）：控制器已作废时，迟到的 JSON 外壳只按原 ownerKey 结算共享事实
            val json = event.result?.takeIf { event.kind == AgentStreamEventKind.JSON }
            if (json != null && settleFirst(json, AgentOperation.SEND)) return
            if (alive()) {
                // 同一身份的旧 run 迟到的 JSON 错误只结算共享事实（42901、10405、AI 10004、30501），不改界面（共性第 18 条）
                json?.let { settleOnly(it, AgentOperation.SEND) }
                // 旧 run 迟到的流内 error 10004 同样是同一身份的共享事实：同步作废并锁闸门、不弹框、不改界面（共性第 22、27 条）
                val frame = event.frame
                if ((event.kind == AgentStreamEventKind.FRAME || event.kind == AgentStreamEventKind.GAP_TERMINAL) && frame != null &&
                    frame.ok &&
                    consentFrame(AgentTerminal(frame.event, frame.data))
                ) {
                    aiConsentRevoked(null, interactive = false)
                }
            }
            return
        }
        val turn = active ?: return
        if (event.traceId.isNotEmpty()) turn.traceId = event.traceId
        when (event.kind) {
            AgentStreamEventKind.PING -> {
                Unit
            }

            AgentStreamEventKind.FRAME -> {
                val frame = event.frame ?: return
                if (turn.resend) {
                    turn.resendState = StreamReducer.frame(turn.resendState, frame, deps.routes)
                    when {
                        turn.resendState.metaSeen && !turn.resendState.duplicate -> {
                            // 服务端按新请求处理（不是 duplicate）：这一次的流就是这条消息
                            turn.resend = false
                            turn.state = turn.resendState
                            trustMeta(turn)
                        }

                        turn.resendState.ended -> {
                            endDuplicate(turn)
                            return
                        }

                        else -> {
                            return
                        }
                    }
                } else {
                    val before = turn.state.metaSeen
                    turn.state = StreamReducer.frame(turn.state, frame, deps.routes)
                    if (!before && turn.state.metaSeen) trustMeta(turn)
                }
                if (turn.state.metaSeen) turn.phase = AgentTurnPhase.STREAMING
                afterFrame(turn)
            }

            AgentStreamEventKind.SEQ_GAP -> {
                // 缺号（G11、G36）：本条结束为 incomplete（同「连接中断」样式但不给【重试】），连接保持只等终止帧或断线，之后自动拉历史替换本条
                turn.gapped = true
                turn.state = turn.state.copy(status = AgentStatus.INCOMPLETE)
                turn.error = null
                publish()
            }

            AgentStreamEventKind.GAP_TERMINAL -> {
                val frame = event.frame
                if (frame != null && consentFrame(AgentTerminal(frame.event, frame.data))) {
                    streamConsentLost(turn, AgentTerminal(frame.event, frame.data))
                    return
                }
                if (frame != null && frame.event == AgentEvent.ERROR &&
                    AgentJson.intOf(frame.data["code"]) == AgentErrorCodes.UNAVAILABLE.toLong()
                ) {
                    lockUnavailable()
                    publish()
                    return
                }
                gapEnded(turn)
            }

            AgentStreamEventKind.EOF, AgentStreamEventKind.TIMEOUT, AgentStreamEventKind.ROUND_LIMIT -> {
                if (turn.gapped) {
                    gapEnded(turn)
                    return
                }
                turn.resend = false
                turn.state = StreamReducer.disconnected(turn.state)
                finishTurn(turn)
            }

            AgentStreamEventKind.LIMIT -> {
                // 单帧 data 或半行超过上限（03 §7.2，Android R1-04、APP-10b G22）：StreamClient 已关连接；已并入的内容保留，本地结束为 limited（不更新额度）
                turn.resend = false
                turn.state = StreamReducer.limited(turn.state)
                finishTurn(turn)
            }

            AgentStreamEventKind.JSON -> {
                jsonFailed(turn, event.result ?: AgentHttpResult.network(), AgentOperation.SEND, turn.entry)
            }

            AgentStreamEventKind.FAILED -> {
                if (turn.gapped) {
                    gapEnded(turn)
                } else if (turn.state.metaSeen) {
                    turn.state = StreamReducer.disconnected(turn.state)
                    finishTurn(turn)
                } else {
                    jsonFailed(turn, event.result ?: AgentHttpResult.network(), AgentOperation.SEND, turn.entry)
                }
            }
        }
    }

    /** meta.message_id 当作回复 id（G03、编排者补充第 3 条：流内 meta 给的才画反馈 / 举报；在历史里指向 user 时另行作废）。 */
    private fun trustMeta(turn: Turn) {
        turn.replyId = turn.state.messageId
        turn.replyIdDistrusted = false
    }

    /** 缺号之后收到终止帧或断线：关连接，自动拉一次历史替换本条（失败按 1/2/4/8 退避，5 次后留【重试】，G11、G38）。 */
    private fun gapEnded(turn: Turn) {
        turn.client?.cancel()
        turn.client = null
        turn.resend = false
        turn.phase = AgentTurnPhase.ENDED
        if (active === turn) active = null
        publish()
        startReload(turn, AgentReloadTrigger.SEQ_GAP, userRetry = false)
    }

    private fun afterFrame(turn: Turn) {
        // page_guide 被客户端拦下：同一卡同一对话只记一次 route_entry_blocked（G10）；基本模式不发
        turn.state.cards
            .filter { it.type == AgentCardType.PAGE_GUIDE && it.kind == AgentCardKind.FALLBACK && it.blockedRoute != null }
            .filter { turn.blockedReported.add(it.cardId) }
            .forEach { card ->
                if (!identity.basic) {
                    deps.track(
                        ClientEvents.routeEntryBlocked(PAGE_NAME, card.blockedRoute.orEmpty(), RouteEntryBlockedEntry.IN_APP),
                    )
                }
            }
        if (turn.state.ended) {
            finishTurn(turn)
            return
        }
        publish()
    }

    /**
     * duplicate=true 的流（03 §7.2、APP-10b §2 第 6 条，替代 APP-10 G26 的「不补拉」）：不并入、不更新额度。done / 其他 error → 关连接，自动拉一次历史替换本条
     * （替换后才显示【赞】【踩】，G28）；error 30506 → 不自动轮询，显示 error.30506 +【刷新】，发送闸门禁用 5 秒（G40）；error 10004 → 与普通流同一处理（共性第 22 条）。
     */
    private fun endDuplicate(turn: Turn) {
        val dup = turn.resendState
        turn.resend = false
        val terminal = dup.terminal
        if (consentFrame(terminal)) {
            streamConsentLost(turn, terminal!!)
            return
        }
        if (terminal?.event == AgentEvent.ERROR && terminal.code == AgentErrorCodes.UNAVAILABLE.toLong()) {
            lockUnavailable()
            publish()
            return
        }
        when (AgentReconnect.duplicateEnd(terminal)) {
            AgentReloadAction.REFRESH_BUTTON -> {
                gates.send.start(AgentReconnect.RUN_IN_PROGRESS_DISABLE_MS)
                turn.state = turn.state.copy(status = AgentStatus.ERROR)
                turn.refreshable = true
                turn.error =
                    AgentErrorView(
                        errorText(AgentErrorCodes.RUN_IN_PROGRESS, turn.traceId),
                        retry = AgentRetryTap(turn.run, AgentRetryMode.REFRESH),
                    )
                closeActive(turn)
            }

            else -> {
                // 服务端已结束原 run：本条按已结束显示（保留已收到的内容），拉历史替换后才显示【赞】【踩】
                turn.duplicatePending = true
                turn.state = turn.state.copy(status = if (terminal?.event == AgentEvent.DONE) AgentStatus.DONE else AgentStatus.ERROR)
                turn.error = null
                closeActive(turn)
                startReload(turn, AgentReloadTrigger.DUPLICATE_DONE, userRetry = false)
            }
        }
    }

    @Suppress("ReturnCount") // 逐项提前返回
    private fun finishTurn(turn: Turn) {
        val s = turn.state
        val terminal = s.terminal
        if (consentFrame(terminal)) {
            // 共性第 22 条（S0）：普通流与 duplicate 流（meta.duplicate=true 直接下发）的 error 10004 不按普通错误收尾
            streamConsentLost(turn, terminal!!)
            return
        }
        if (terminal?.event == AgentEvent.ERROR && terminal.code == AgentErrorCodes.UNAVAILABLE.toLong()) {
            // 流内 30501（鸿蒙 R1-06）：本次进入周期锁「未开放」
            lockUnavailable()
            publish()
            return
        }
        if (s.duplicate && terminal != null) {
            // meta.duplicate=true 直接下发的流（没有经同键重发）：同 duplicate 收尾（G40 / 03 §7.2）
            turn.resendState = s
            turn.state = s.copy(duplicate = false)
            endDuplicate(turn)
            return
        }
        turn.error =
            when {
                s.status == AgentStatus.DISCONNECTED -> {
                    AgentErrorView(
                        AgentDraftTexts.text(DISCONNECTED_KEY),
                        retry = AgentRetryTap(turn.run, retryModeForDisconnect()),
                        hint = AgentDraftTexts.text(DISCONNECTED_HINT_KEY),
                    )
                }

                s.status == AgentStatus.ERROR && terminal?.event == AgentEvent.ERROR -> {
                    streamErrorView(turn, terminal.data)
                }

                else -> {
                    null
                }
            }
        if (s.updatesQuota && !s.duplicate) quotaLeft = s.quotaLeft
        closeActive(turn)
    }

    /** 断线【重试】：有历史接口时先拉历史首页（G07）；没有（APP-10 测试替身）同键重发。 */
    private fun retryModeForDisconnect(): String = AgentRetryMode.RELOAD

    /**
     * 流内 error 帧 code=10004（共性第 22 条，S0；普通流、duplicate 流、重试后的 duplicate 收尾同一出口）：同步执行禁发——停止本对话的所有流与计时、作废卡片回调和
     * 购买执行（cardEpoch +1）、对话世代 +1、清空同意被撤销后不可展示的对话，再把闸门锁成 refused，并上报应用级「AI 同意失效」。是否弹框另判：流契约的 error 帧
     * 没有 consent_type，不成立 → 不弹框，页面显示「暂不使用」同款说明与【重新同意】（鸿蒙 H27）；不弹框也必须禁发。
     */
    private fun streamConsentLost(
        turn: Turn,
        terminal: AgentTerminal,
    ) {
        turn.phase = AgentTurnPhase.ENDED
        turn.client?.cancel()
        turn.client = null
        dropConversation()
        val consentType = AgentJson.stringOf(terminal.data["consent_type"])
        val interactive = AgentConsentPolicy.shows(AgentErrorCodes.CONSENT, consentType) && visible
        aiConsentRevoked(null, interactive)
    }

    private fun closeActive(turn: Turn) {
        turn.phase = AgentTurnPhase.ENDED
        turn.client?.cancel()
        turn.client = null
        if (active === turn) active = null
        // 读屏播报只在可见时（隐藏期间结束的回复回来不补播，G02）
        announcement = if (visible) plain(turn.state) else ""
        publish()
    }

    private fun streamErrorView(
        turn: Turn,
        data: JsonObject,
    ): AgentErrorView {
        val v = AgentStreamErrorPolicy.view(data)
        val retry = AgentRetryTap(turn.run, AgentRetryMode.NEW).takeIf { v.retry == AgentStreamErrorView.RETRY_NEW }
        val search =
            if (v.searchButton) {
                val q = v.prefillQ
                val params = if (q != null) JsonObject(mapOf("q" to JsonPrimitive(q))) else AgentJson.EMPTY
                val label =
                    if (q !=
                        null
                    ) {
                        AgentDraftTexts.format("agent.draft.search_q", mapOf("q" to q))
                    } else {
                        AgentDraftTexts.text(SEARCH_KEY)
                    }
                AgentTap(
                    turn.run.controller,
                    turn.run.conversation,
                    turn.run.run,
                    "",
                    AgentTapKind.SEARCH,
                    SEARCH_ROUTE,
                    params,
                    RouteSource.IN_APP,
                    label,
                )
            } else {
                null
            }
        // 流内 error 帧没有 trace_id：5xxxx 用本轮响应头的 X-Trace-Id 追加 trace6（共性第 7 条；都没有就不追加）
        return AgentErrorView(
            text = errorText(v.code, turn.traceId),
            retry = retry,
            search = search,
            searchHint = if (search != null && v.prefillQ != null) AgentDraftTexts.text("agent.draft.search_hint") else "",
        )
    }

    /**
     * 发送 / 建会话返回 JSON 错误（G24、APP-10b §2 第 10 条）；42901、10405、10004、30501、统一分流与其他 operation 共用 [sharedError]（共性第 6 条）。
     * [entry]：发起时的进入世代（导航、提示、弹框都按它核对，鸿蒙 R1-05）。
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount") // 按 G24 的动作表逐条
    private fun jsonFailed(
        turn: Turn,
        r: AgentHttpResult,
        op: String,
        entry: Long,
    ) {
        turn.client?.cancel()
        turn.client = null
        // 业务错误码分流之前先分三类（Android R1-02，G19、G34、A12，共性第 37 条）：本地没发出 → 静默移除；没有统一外壳（断网、超时、网关错误页、planned 期间 404）
        // 或外壳成功却不是流（application/json code=0）→ 本条「连接中断」+ RELOAD【重试】（先查历史再决定替换或同一 client_msg_id 重发，G07）；不把无外壳当 50001
        if (r.notSent) {
            // 本地没有发出（准入不通过、归属已变）：新消息不留在列表里（不改绑到新身份，G23、APP-10 H11）；恢复流程的重发保留原轮次与【重试】（R1-03）
            if (turn.resumed) disconnectedForRetry(turn) else removeTurn(turn)
            publish()
            return
        }
        if (r.code < 0 || r.ok) {
            disconnectedForRetry(turn)
            publish()
            return
        }
        val code = r.code
        val d = AgentSendErrorPolicy.decide(r.httpStatus, code, r.data, r.retryAfter, r.traceId, r.consentType)
        val shown = current(entry)
        turn.phase = AgentTurnPhase.FAILED
        when (d.action) {
            AgentSendAction.UNAVAILABLE -> {
                removeTurn(turn)
                lockUnavailable()
            }

            AgentSendAction.QUOTA -> {
                quotaExhausted = true
                turn.error = quotaView(turn, AgentJson.stringOf(r.data["next"]))
            }

            AgentSendAction.NEW_CHAT -> {
                turn.error = AgentErrorView(errorText(code, r.traceId), newChat = AgentPageTicket(serial, conversation))
            }

            AgentSendAction.RUN_IN_PROGRESS -> {
                gates.send.start(d.disableMs)
                if (shown) deps.toast(errorText(code, r.traceId))
                removeTurn(turn)
            }

            AgentSendAction.RATE_LIMITED -> {
                sharedError(r, null, op, entry)
                if (shown) deps.toast(errorText(code, r.traceId))
                removeTurn(turn)
            }

            AgentSendAction.RETRY_SAME_MESSAGE -> {
                turn.error = AgentErrorView(errorText(code, r.traceId), retry = AgentRetryTap(turn.run, AgentRetryMode.SAME))
            }

            AgentSendAction.AGENT_CONSENT -> {
                if (consentGate == AgentConsentGate.SUBMITTING) {
                    // 提交中收到的 10004 来自提交前发出的请求：不改提交中的状态；这一条给同键【重试】（记录成功后用户自己点，鸿蒙 H28）
                    turn.error =
                        AgentErrorView(errorText(AgentErrorCodes.CONSENT, r.traceId), retry = AgentRetryTap(turn.run, AgentRetryMode.SAME))
                    // 仍要同步作废其余在途工作并上报应用级失效（共性第 10、33 条）
                    aiConsentRevoked(null, interactive = false)
                } else if (turn.consentReplays >= 1) {
                    // 同意后重放只一次；再遇 10004 不再自动重放（按统一错误显示），但必须立即重新锁住闸门并（发起时仍可见才）再弹同意框——这一次的弹窗没有
                    // 待重放消息（共性第 16 条，S0；鸿蒙 R1-05：隐藏期间只锁）
                    turn.error = AgentErrorView(errorText(AgentErrorCodes.CONSENT, r.traceId))
                    if (active === turn) active = null
                    turn.phase = AgentTurnPhase.ENDED
                    aiConsentRevoked(null, interactive = shown)
                } else {
                    turn.phase = AgentTurnPhase.AWAITING_CONSENT
                    if (active === turn) active = null
                    sharedError(r, turn.run.run, op, entry)
                    return
                }
            }

            AgentSendAction.FORCE_UPDATE -> {
                sharedError(r, null, op, entry)
                removeTurn(turn)
            }

            AgentSendAction.TEXT_TOO_LONG -> {
                if (shown) deps.toast(AgentDraftTexts.text(TOO_LONG_KEY))
                removeTurn(turn)
            }

            else -> {
                // 统一分流（10001 / 10005 / 非 AI 的 10004 / 表外码）：交统一入口（可见才打开前置页或提示）；本条显示统一文案（5xxxx 带 trace6）。
                // 无外壳与本地没发出已在上面分出（R1-02）
                sharedError(r, null, op, entry)
                turn.error = AgentErrorView(errorText(code, r.traceId))
            }
        }
        if (active === turn) active = null
        publish()
    }

    /**
     * 发送没有形成流、也没有可用的业务码（无外壳、外壳成功却不是流、流前传输失败）：本条「连接中断」+ 说明句 + RELOAD【重试】（G19、G34、A12）。
     * 【重试】先查历史再决定替换或同一 client_msg_id 重发（G07）；还没有会话时没有历史可查，直接同键重发（[retry]）。
     */
    private fun disconnectedForRetry(turn: Turn) {
        turn.client?.cancel()
        turn.client = null
        turn.resend = false
        turn.state = StreamReducer.disconnected(turn.state)
        turn.gapped = false
        turn.error =
            AgentErrorView(
                AgentDraftTexts.text(DISCONNECTED_KEY),
                retry = AgentRetryTap(turn.run, AgentRetryMode.RELOAD),
                hint = AgentDraftTexts.text(DISCONNECTED_HINT_KEY),
            )
        turn.phase = AgentTurnPhase.ENDED
        if (active === turn) active = null
    }

    /**
     * 30502 额度用完（AgentQuota* 稿；CT-LOCK-01 G08、G27）：按服务端 data.next 选按钮——login →【登录】；bind_phone →【绑定手机号】；none → 只给【去搜索】与
     * 「明天再来」；缺失或不认识 → 回落本地身份规则（游客 → 登录；会员未绑或未知 → 绑定；已绑 → 只有搜索，APP-10 H23 / A14）。reset_at 不显示。
     */
    private fun quotaView(
        turn: Turn,
        next: String?,
    ): AgentErrorView {
        val run = turn.run

        fun tap(
            kind: String,
            route: String,
            labelKey: String,
        ) = AgentTap(
            run.controller,
            run.conversation,
            run.run,
            "",
            kind,
            route,
            AgentJson.EMPTY,
            RouteSource.IN_APP,
            AgentDraftTexts.text(labelKey),
        )
        val bound = if (identity.member) runCatching { deps.phoneBound() }.getOrNull() else null
        val local =
            when {
                !identity.member -> AgentQuotaPolicy.IDENTITY_GUEST
                bound == true -> AgentQuotaPolicy.IDENTITY_BOUND
                else -> AgentQuotaPolicy.IDENTITY_UNBOUND
            }
        val buttons = AgentQuotaPolicy.buttons(next, local)
        val (upgrade, hint) =
            when {
                AgentQuotaPolicy.ROUTE_LOGIN in buttons -> {
                    tap(AgentTapKind.LOGIN, LOGIN_ROUTE, "agent.draft.login") to AgentDraftTexts.text("agent.draft.quota.guest_hint")
                }

                AgentQuotaPolicy.ROUTE_BIND_PHONE in buttons -> {
                    tap(AgentTapKind.BIND_PHONE, BIND_PHONE_ROUTE, "agent.draft.bind_phone") to
                        AgentDraftTexts.text("agent.draft.quota.phone_hint")
                }

                else -> {
                    null to AgentDraftTexts.text("agent.draft.quota.member_hint")
                }
            }
        return AgentErrorView(
            errorText(AgentErrorCodes.QUOTA, ""),
            search = tap(AgentTapKind.SEARCH, SEARCH_ROUTE, SEARCH_KEY),
            upgrade = upgrade,
            quotaHint = hint,
        )
    }

    private fun removeTurn(turn: Turn) {
        turns = turns.filter { it !== turn }
        if (active === turn) active = null
    }

    // ---------------------------------------------------------------- 历史重载（APP-10b §2 第 6 条，G06、G07、G38）

    /**
     * 开始一次重载（断线【重试】、缺号、duplicate 收尾、cancel 30505、【刷新】）：同意闸门锁住 → 不请求；App 在后台 → 只留【重试】（不发）；否则拉历史首页，
     * 失败按 1 / 2 / 4 / 8 秒（带抖动）自动重试，含首次共 5 次（这些请求关掉 ApiClient 的 GET 自动重试，G35）。
     */
    private fun startReload(
        turn: Turn,
        trigger: String,
        userRetry: Boolean,
    ) {
        cancelTimers(turn, stop = false)
        val r = AgentReconnect(trigger, userRetry, deps.random)
        turn.reconnect = r
        val entry = this.entry
        val step = r.begin(runCatching(deps.foreground).getOrDefault(false), consentGate == AgentConsentGate.CLEAR)
        runReload(turn, r, step, entry)
    }

    private fun runReload(
        turn: Turn,
        r: AgentReconnect,
        step: AgentReloadStep,
        entry: Long,
    ) {
        if (turn.reconnect !== r) return
        when (step.kind) {
            AgentReloadStep.REQUEST -> {
                requestReload(turn, r, entry)
            }

            AgentReloadStep.WAIT -> {
                publish()
                turn.reloadTimer =
                    deps.timer.after(step.delayMs) {
                        turn.reloadTimer = null
                        if (turn.reconnect !== r || !alive()) return@after
                        runReload(turn, r, r.onTimer(runCatching(deps.foreground).getOrDefault(false)), entry)
                    }
            }

            else -> {
                finishReload(turn, r, step, entry)
            }
        }
    }

    /** 一次历史请求（读取闸门；挂在 reload:<run> 子归属上，进后台、停止、作废时同步取消）。结果先比票据（控制器、对话、这一轮、这一次重载、历史票据）。 */
    @Suppress("ReturnCount", "ComplexCondition") // 没有端口、没有会话、读取闸门、票据依次返回
    private fun requestReload(
        turn: Turn,
        r: AgentReconnect,
        entry: Long,
    ) {
        val contract = deps.contract
        val id = sessionId
        if (contract == null || id == null) {
            finishReload(turn, r, r.onOutcome(AgentReloadOutcome(AgentReloadOutcome.NETWORK, false, null, -1, null), false, false), entry)
            return
        }
        if (gates.read.active) {
            // G58：读取闸门期内不发，本轮只留【重试】
            finishReload(
                turn,
                r,
                r.onOutcome(AgentReloadOutcome(AgentReloadOutcome.ENVELOPE, false, null, AgentErrorCodes.RATE_LIMITED, null), false, false),
                entry,
            )
            return
        }
        val conversation = this.conversation
        val seq = historySeq
        val scope = AgentCall.reloadScope(turn.run.run)
        val call = call(AgentOperation.HISTORY, scope) { turn.reconnect === r && seq == historySeq && sessionId == id }
        publish()
        deps.scope.launch {
            val res = contract.listMessages(id, null, AgentHistory.LIMIT, call, autoRetry = false)
            // 先共享结算、后页面判断（R3-01）：控制器已作废（页面重建 / 换身份）时只按原 ownerKey 结算共享事实
            if (settleFirst(res, AgentOperation.HISTORY)) return@launch
            if (conversation != this@AgentController.conversation || seq != historySeq || turn.reconnect !== r || turn !in turns) {
                settleOnly(res, AgentOperation.HISTORY)
                return@launch
            }
            if (!runCatching(deps.foreground).getOrDefault(false)) {
                // 结果在后台到达（R1-08）：只结算共享事实，暂停重载（只留【重试】）
                settleOnly(res, AgentOperation.HISTORY)
                r.pause()?.let { finishReload(turn, r, it, entry) }
                return@launch
            }
            reloadResult(turn, r, res, entry)
        }
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod") // 成功、形状、外壳码依次处理
    private fun reloadResult(
        turn: Turn,
        r: AgentReconnect,
        res: AgentHttpResult,
        entry: Long,
    ) {
        val sendOpen = !gates.send.active && consentGate == AgentConsentGate.CLEAR
        val consentOpen = consentGate == AgentConsentGate.CLEAR
        if (res.ok) {
            val page = AgentHistory.page(res.data, deps.routes)
            if (page == null) {
                runReload(
                    turn,
                    r,
                    r.onOutcome(AgentReloadOutcome(AgentReloadOutcome.NETWORK, false, null, -1, null), sendOpen, consentOpen),
                    entry,
                )
                return
            }
            val m = AgentHistoryMerge.match(page, turnKey(turn), claimed(turn))
            // G03：meta.message_id 在历史里指向 user：不当回复 id 用（入口不显示），对齐改按文本与时间
            val replyId = turn.replyId
            if (replyId != null && page.items.any { it.messageId == replyId && it.isUser }) turn.replyIdDistrusted = true
            if (page.expired) applyExpired()
            if (m.matched) {
                lastReloadMatch = m
            } else if (r.trigger != AgentReloadTrigger.REFRESH) {
                // 对不上（鸿蒙 R1-09、G66）：保留本地，按消息 id 去重把历史里本地没有的整轮追加在后面（只在内存）
                appendHistory(page)
            }
            val step =
                r.onOutcome(
                    AgentReloadOutcome(AgentReloadOutcome.SUCCESS, m.matched, m.reply?.finishReason, 0, null),
                    sendOpen,
                    consentOpen,
                )
            if (page.expired && step.action == AgentReloadAction.RESEND) {
                // 只读会话不重发（R1-10）
                finishReload(turn, r, AgentReloadStep.final(AgentReloadAction.NEW_CHAT, AgentStatus.DISCONNECTED), entry)
                return
            }
            finishReload(turn, r, step, entry)
            return
        }
        if (res.notSent || res.code < 0) {
            runReload(
                turn,
                r,
                r.onOutcome(AgentReloadOutcome(AgentReloadOutcome.NETWORK, false, null, -1, null), sendOpen, consentOpen),
                entry,
            )
            return
        }
        when (res.code) {
            AgentErrorCodes.RATE_LIMITED, AgentErrorCodes.UNAVAILABLE, AgentErrorCodes.FORCE_UPDATE -> {
                sharedError(res, null, AgentOperation.HISTORY, entry)
                if (res.code == AgentErrorCodes.UNAVAILABLE) {
                    publish()
                    return
                }
                reloadError = if (res.code == AgentErrorCodes.RATE_LIMITED) errorText(res.code, "") else ""
                finishReload(
                    turn,
                    r,
                    r.onOutcome(
                        AgentReloadOutcome(AgentReloadOutcome.ENVELOPE, false, null, AgentErrorCodes.RATE_LIMITED, null),
                        false,
                        false,
                    ),
                    entry,
                )
            }

            AgentErrorCodes.CONSENT, AgentErrorCodes.LOGIN, AgentErrorCodes.BIND_PHONE -> {
                // G65（鸿蒙 R1-13）：前置码不再退避——按重载开始时的进入世代与可见性做统一分流（可见才打开前置页），本条显示统一文案并保留【重试】；AI 10004 锁闸门
                val ai = AgentConsentPolicy.shows(res.code, res.consentType)
                sharedError(res, null, AgentOperation.HISTORY, entry)
                if (ai) {
                    finishReload(
                        turn,
                        r,
                        r.onOutcome(AgentReloadOutcome(AgentReloadOutcome.ENVELOPE, false, null, res.code, res.consentType), false, false),
                        entry,
                    )
                    return
                }
                reloadError = errorText(res.code, res.traceId)
                finishReload(turn, r, AgentReloadStep.final(AgentReloadAction.RETRY_ONLY, AgentStatus.DISCONNECTED), entry)
            }

            AgentErrorCodes.ROUND_LIMIT -> {
                finishReload(
                    turn,
                    r,
                    r.onOutcome(AgentReloadOutcome(AgentReloadOutcome.ENVELOPE, false, null, res.code, null), false, false),
                    entry,
                )
            }

            else -> {
                // 5xxxx 等：照退避重试；用尽后显示统一文案 + trace6（不压成无诊断的「连接中断」，R1-13）
                reloadError = errorText(res.code, res.traceId)
                runReload(
                    turn,
                    r,
                    r.onOutcome(AgentReloadOutcome(AgentReloadOutcome.ENVELOPE, false, null, res.code, null), sendOpen, consentOpen),
                    entry,
                )
            }
        }
    }

    /** 最近一次重载对上的历史轮（[finishReload] 替换时用）。 */
    private var lastReloadMatch: AgentTurnMatch? = null

    /** 最近一次重载失败的统一文案（只留【重试】时显示；空串用「连接中断」）。 */
    private var reloadError = ""

    /** 重载结束：替换 / 同键重发 / 只留【重试】/ 会话已结束 / 锁同意 / 保持原样（G07）。 */
    @Suppress("CyclomaticComplexMethod", "LongMethod") // 每种动作一个分支
    private fun finishReload(
        turn: Turn,
        r: AgentReconnect,
        step: AgentReloadStep,
        entry: Long,
    ) {
        if (turn.reconnect !== r) return
        turn.reconnect = null
        turn.reloadTimer?.invoke()
        turn.reloadTimer = null
        if (step.paused || step.action == AgentReloadAction.RETRY_ONLY || step.action == AgentReloadAction.PAUSED) {
            // 历史请求在途时进后台也要同步取消（R1-08）
            runCatching { deps.contract?.cancelScope(identity, AgentCall.reloadScope(turn.run.run)) }
        }
        val match = lastReloadMatch
        lastReloadMatch = null
        val failure = reloadError
        reloadError = ""
        when (step.action) {
            AgentReloadAction.REPLACE -> {
                val g = match?.group
                if (g != null) replaceWithHistory(turn, g)
            }

            AgentReloadAction.RESEND -> {
                resendSame(turn)
                return
            }

            AgentReloadAction.RETRY_ONLY, AgentReloadAction.PAUSED -> {
                if (turn.duplicatePending || r.trigger == AgentReloadTrigger.CANCEL_30505) {
                    // duplicate / 30505 重载失败：本条保持本地（不再显示连接中断），【刷新】留给用户
                    turn.refreshable = r.trigger != AgentReloadTrigger.CANCEL_30505
                } else {
                    turn.state = turn.state.copy(status = AgentStatus.DISCONNECTED)
                    turn.gapped = false
                    turn.error =
                        AgentErrorView(
                            failure.ifEmpty { AgentDraftTexts.text(DISCONNECTED_KEY) },
                            retry = AgentRetryTap(turn.run, AgentRetryMode.RELOAD),
                            hint = if (failure.isEmpty()) AgentDraftTexts.text(DISCONNECTED_HINT_KEY) else "",
                        )
                }
            }

            AgentReloadAction.NEW_CHAT -> {
                // 历史 30504：会话已失效（G07）——本轮结束为「这个对话已结束」+【新对话】，清本地会话，不重发
                sessionId = null
                turn.state =
                    turn.state.copy(
                        status =
                            if (turn.state.status ==
                                AgentStatus.INCOMPLETE
                            ) {
                                AgentStatus.DISCONNECTED
                            } else {
                                turn.state.status
                            },
                    )
                turn.error = AgentErrorView(errorText(AgentErrorCodes.ROUND_LIMIT, ""), newChat = AgentPageTicket(serial, conversation))
            }

            AgentReloadAction.CONSENT -> {
                if (step.lockAiGate) aiConsentRevoked(null, interactive = false)
                if (turn.state.status == AgentStatus.INCOMPLETE) turn.state = turn.state.copy(status = AgentStatus.DISCONNECTED)
            }

            else -> {
                // KEEP（duplicate / 30505 / 【刷新】对不上）：本条保持原样
                if (turn.duplicatePending) {
                    turn.duplicatePending = false
                    turn.refreshable = true
                    if (turn.error == null) turn.error = AgentErrorView("", retry = AgentRetryTap(turn.run, AgentRetryMode.REFRESH))
                }
            }
        }
        if (entry != this.entry) announcement = ""
        publish()
    }

    /** 用历史替换发起重载的这一轮（G06）：只替换这一轮；新 run 键让旧卡片回调与购买尝试失效，新卡按新票据渲染；不查钱包、不更新额度、不自动滚动。 */
    private fun replaceWithHistory(
        turn: Turn,
        g: AgentHistoryGroup,
    ) {
        turn.client?.cancel()
        turn.client = null
        runSeq += 1
        turn.run = AgentRunTicket(serial, conversation, "${turn.run.clientMsgId}#$runSeq", turn.run.clientMsgId)
        turn.history = true
        turn.userMessageId = g.user?.messageId
        turn.refreshable = false
        turn.gapped = false
        turn.revoked = false
        turn.products.clear()
        val reply = g.replies.firstOrNull()
        if (reply != null) {
            applyHistoryReply(turn, reply)
        } else {
            turn.state = turn.state.copy(status = AgentStatus.DONE)
            turn.error = null
        }
        runCatching { deps.cancelPurchase() }
    }

    /** 对不上时把历史里本地没有的整轮追加在对话后面（按消息 id 去重，G66）。 */
    private fun appendHistory(page: AgentHistoryPage) {
        val local = turns.flatMap { listOfNotNull(it.userMessageId, it.replyId) }.toSet()
        val extra = AgentHistoryMerge.appendable(page, local).flatMap(::historyTurnsOf)
        if (extra.isNotEmpty()) turns = turns + extra
    }

    /** 本地这一轮的对齐依据（G05）。 */
    private fun turnKey(turn: Turn): AgentTurnKey =
        AgentTurnKey(turn.userText, turn.sentAtMs, turn.replyId?.takeIf { !turn.replyIdDistrusted })

    /** 其他本地轮次已确认的消息归属（鸿蒙 R3-02、F-X-06 ①）：其他轮的历史 user id、回复 id、同键重发流的回复 id。 */
    private fun claimed(turn: Turn): Set<String> =
        turns.filter { it !== turn }.flatMap { listOfNotNull(it.userMessageId, it.replyId, it.resendState.messageId) }.toSet()

    /** 用同一 client_msg_id 重发（G07 没受理；BR-AI-23 ⑥ 服务端去重）。 */
    private fun resendSame(turn: Turn) {
        if (!alive()) return
        if (active != null || readOnly) {
            // 无法立即重发（恢复期间用户已发出新的一轮占着发送通道，或会话只读；Android R2-05，共性第 43 条）：不静默返回——把目标轮恢复为「连接中断」+ RELOAD【重试】
            // 并发布快照（[finishReload] 已清掉 reconnect）；新的一轮结束后也不自动重发，由用户再点【重试】
            if (turn !== active) disconnectedForRetry(turn)
            publish()
            return
        }
        turn.run = newRun(ClientMsgId.forRetry(ClientMsgId.RETRY_DISCONNECTED, turn.run.clientMsgId))
        turn.error = null
        turn.phase = AgentTurnPhase.CONNECTING
        turn.gapped = false
        // 已收到的部分内容保留；回来的若是 duplicate 两帧，按 duplicate 收尾（拉历史替换），不并入
        turn.resend = turn.state.metaSeen
        turn.resendState = StreamReducer.initial()
        if (!turn.resend) turn.state = StreamReducer.initial()
        turn.resumed = true
        active = turn
        publish()
        dispatch(turn, resume = true)
    }

    private fun cancelTimers(
        turn: Turn,
        stop: Boolean = true,
    ) {
        turn.reloadTimer?.invoke()
        turn.reloadTimer = null
        if (stop) {
            turn.stopTimer?.invoke()
            turn.stopTimer = null
        }
    }

    // ---------------------------------------------------------------- 停止、新对话、重试

    /**
     * 【停止】（APP-10b §2 第 7 条，G26）：同步作废本控制器归属的历史 / 反馈 / 举报 / 建会话 / 其他轮重载（保留这一轮的流通道例外，鸿蒙 R2-03），本地立即结束为
     * cancelled、作废这一对话已渲染的卡片回调与在途购买（共性第 3 条）。有 run_id → 关连接、发 1 次 cancel（以 run 去重，连点、重进页面也不再发；动作闸门期间不发）；
     * 没有 run_id（meta 之前）→ 显示「正在停止」，连接保持到 meta 到达（最长 30 秒）后关连接并发 1 次 cancel；30 秒没有 meta 或先到了终止帧就只关连接、不发。
     * 没有连接（建会话在途，G52）→ 立即本地结束、不发。
     */
    @Suppress("ReturnCount") // 票据、没有活动 run 依次返回
    fun stop(t: AgentRunTicket) {
        if (!runOk(t)) return
        val turn = active ?: return
        stopOwnedWork(turn)
        turn.state = StreamReducer.localCancel(turn.state)
        turn.resend = false
        turn.error = null
        turn.phase = AgentTurnPhase.ENDED
        active = null
        revokeCardActions()
        turn.revoked = true
        val flow = AgentStopFlow(deps.ledger)
        turn.stopFlowRef = flow
        val runId = turn.state.runId
        val hasConnection = turn.client?.active == true
        val effect = flow.stop(if (hasConnection || runId != null) runId else null, !gates.action.active)
        if (runId == null && hasConnection) {
            // G26：连接保持到 meta（最长 30 秒）；内容帧一律不交 Reducer
            turn.stopTimer =
                deps.timer.after(AgentStopFlow.META_WAIT_MS) {
                    turn.stopTimer = null
                    applyStop(turn, flow.onWaitExpired())
                }
            publish()
            return
        }
        if (runId == null) {
            // G52：建会话在途、还没有连接：立即本地结束，不发 cancel（这一轮的建会话已随 run 子归属取消）
            flow.onTerminal()
            runCatching { deps.contract?.cancelScope(identity, AgentCall.runScope(turn.run.run)) }
        }
        applyStop(turn, effect)
    }

    /** 「正在停止」期间这一轮的流事件（G26）：meta → 关连接、发 1 次 cancel；终止帧 / 断线 → 只关连接；其余丢弃（内容票据已作废）。 */
    @Suppress("CyclomaticComplexMethod") // 按分支逐条对照规则
    private fun onStoppingStream(
        turn: Turn,
        event: AgentStreamEvent,
    ) {
        val flow = turn.stopFlow ?: return
        val frame = event.frame
        val effect =
            when {
                event.kind == AgentStreamEventKind.FRAME && frame?.event == AgentEvent.META -> {
                    flow.onMeta(
                        AgentJson.stringOf(frame.data["run_id"]),
                        !gates.action.active,
                    )
                }

                event.kind == AgentStreamEventKind.FRAME && frame != null && AgentSeqGuard.isTerminal(frame.event) -> {
                    flow.onTerminal()
                }

                event.kind == AgentStreamEventKind.GAP_TERMINAL ||
                    event.kind == AgentStreamEventKind.EOF ||
                    event.kind == AgentStreamEventKind.TIMEOUT ||
                    event.kind == AgentStreamEventKind.ROUND_LIMIT ||
                    event.kind == AgentStreamEventKind.FAILED ||
                    event.kind == AgentStreamEventKind.JSON ||
                    event.kind == AgentStreamEventKind.LIMIT -> {
                    flow.onTerminal()
                }

                else -> {
                    AgentStopEffect.NONE
                }
            }
        // 正在停止期间迟到的流内 10004 仍是同一身份的共享事实（共性第 22 条）
        if (frame != null && frame.ok && consentFrame(AgentTerminal(frame.event, frame.data))) aiConsentRevoked(null, interactive = false)
        // 等待 meta 期间收到的非 SSE 结果（HTTP JSON：AI 10004、42901、10405、30501；10002 / 10402 已在 ApiClient 重放一次后给出最终结果）同样按原身份结算共享事实：
        // AI 10004 汇入应用级同意失效（不含页面实例的 ownerKey）、同步停流作废所属工作并封住后续发送，不弹框、不导航（Android R1-01，共性第 33、36 条）
        if (event.kind == AgentStreamEventKind.JSON && alive()) event.result?.let { settleOnly(it, AgentOperation.SEND) }
        applyStop(turn, effect)
    }

    private fun applyStop(
        turn: Turn,
        effect: AgentStopEffect,
    ) {
        if (effect.close) {
            turn.stopTimer?.invoke()
            turn.stopTimer = null
            turn.client?.cancel()
            turn.client = null
        }
        effect.cancelRunId?.let { sendCancel(turn, it) }
        publish()
    }

    /**
     * 【停止】的同步作废范围（鸿蒙 R2-03、G72）：除这一轮的流（stream:<run>，meta 前最多等 30 秒）以外，本控制器页面归属的全部请求经 cancelOwnedExcept 同步取消；
     * 历史票据 +1（迟到的历史 / 翻页 / 重载结果只结算共享事实）、其他轮的重载停下只留【重试】、在途翻页回到可点；在途反馈静默回滚、在途举报解除（半屏与已选原因保留）；
     * 工作世代 +1 让迟到结果只结算共享事实（不导航）。应用级 cancel 不挂页面归属，不受影响。
     */
    private fun stopOwnedWork(turn: Turn) {
        historySeq += 1
        workSeq += 1
        pager?.failed()
        turns.filter { it !== turn }.forEach { other ->
            other.reconnect?.let { r -> r.pause()?.let { step -> finishReload(other, r, step, entry) } }
            other.feedback.abort()
            other.report.abort()
        }
        turn.feedback.abort()
        turn.report.abort()
        runCatching { deps.contract?.cancelOwnedExcept(identity, AgentCall.streamScope(turn.run.run)) }
    }

    /**
     * 停止的 cancel（单次发送、不挂页面归属）：准入核对原归属（不含页面实例，[AgentDeps.currentOwnerKey]）与动作闸门；结果按原归属结算——不同归属整体丢弃（只保留
     * 10405 强更）；同归属：42901 写动作闸门（提示一次）、AI 10004 汇入应用级失效（控制器仍在就同步作废，不弹框不导航）、30501 锁本次进入、30505 按契约拉一次历史
     * 替换原轮（G25）；200 静默；其余只记本机日志（鸿蒙 R2-01、R3-01，G60、G70、G77）。
     */
    @Suppress("ComplexCondition", "CyclomaticComplexMethod") // 条件逐项；按分支逐条对照规则
    private fun sendCancel(
        turn: Turn,
        runId: String,
    ) {
        val contract = deps.contract ?: return
        val conversation = this.conversation
        val entry = this.entry
        val seq = runSeq
        val actionGate = gates.action
        val call =
            AgentCall(
                identityKey = identity,
                anonymous = !identity.member,
                sendAllowed = { ownerStill() && !actionGate.active && !unavailableLocked },
                owner = { ownerStill() },
                pageOwned = false,
                singleShot = true,
            )
        deps.scope.launch {
            val r = runCatching { contract.cancelRun(runId, call) }.getOrElse { AgentHttpResult.network() }
            if (r.ok || r.notSent) return@launch
            // 先共享结算、后页面判断（R3-01）：控制器已作废时按原 ownerKey 结算（同归属：42901 写动作闸门、AI 10004 汇入应用级失效、10405；不同归属只保留 10405）
            if (settleFirst(r, AgentOperation.CANCEL)) return@launch
            val result = AgentCancelPolicy.result(r)
            if (result.gateDelayMs > 0) {
                actionGate.start(result.gateDelayMs)
                if (current(entry)) deps.toast(errorText(AgentErrorCodes.RATE_LIMITED, ""))
            }
            if (result.forceUpdate) deps.forceUpdate()
            if (result.lockAiGate) {
                aiConsentRevoked(null, interactive = false)
                return@launch
            }
            if (result.unavailable) {
                lockUnavailable()
                publish()
                return@launch
            }
            if (result.reload && conversation == this@AgentController.conversation && turn in turns && seq >= 0) {
                // 30505：按契约「刷新会话消息」（G25）——拉一次历史，本轮在服务端已以非 cancelled 结束时用历史替换
                startReload(turn, AgentReloadTrigger.CANCEL_30505, userRetry = false)
                return@launch
            }
            if (!r.ok) deps.log("agent cancel result ${r.code}")
        }
    }

    /** 卡片动作世代 +1、作废购买执行器的在途尝试（只挡外跳；之后的响应带旧世代，不再更新卡片）。 */
    private fun revokeCardActions() {
        cardEpoch += 1
        runCatching { deps.cancelPurchase() }
    }

    /**
     * 建会话（新对话）的结果（共性第 6、18 条）：先核对归属；成功不处理；票据失效只结算共享事实；仍有效时本地没发出静默，其余按 [sharedError]，剩下的码提示统一文案
     * （5xxxx 带 trace6），不直接吞掉。
     */
    private fun afterOp(
        r: AgentHttpResult,
        uiOk: () -> Boolean,
        op: String,
        entry: Long,
    ) {
        if (!alive() || r.ok) return
        if (!uiOk()) {
            settleOnly(r, op)
            return
        }
        when (sharedError(r, null, op, entry)) {
            AgentSharedError.UNAVAILABLE, AgentSharedError.RATE_LIMITED -> {
                publish()
            }

            AgentSharedError.OTHER -> {
                if (r.code < 0) {
                    deps.log("agent $op failed")
                } else if (current(entry)) {
                    deps.toast(errorText(r.code, r.traceId))
                }
            }
        }
    }

    /** 【新对话】：作废当前对话与所有卡片回调（在途 run 本地结束、不发 cancel），同步取消本控制器归属的在途请求，createAgentSession。 */
    @Suppress("ReturnCount") // 票据、没有端口、未就绪依次返回
    fun newChat(t: AgentPageTicket) {
        if (!pageOk(t)) return
        dropConversation()
        quotaExhausted = false
        readOnly = false
        sessionEndedText = ""
        pager = null
        earlierError = ""
        publish()
        val contract = deps.contract ?: return
        if (phase != AgentPhase.READY) return
        val conversation = this.conversation
        val entry = this.entry
        val seq = runSeq
        // 只为这一次新对话建会话（再点新对话、已有会话后永久失效）
        val call = call(AgentOperation.CREATE) { sessionId == null }
        deps.scope.launch {
            val r = contract.createSession(call)
            // 先核对固定身份，再结算共享事实；对话、之后是否又发起了 run 决定能不能改界面（共性第 18 条）
            // 先共享结算、后页面判断（R3-01）：控制器已作废（页面重建 / 换身份）时只按原 ownerKey 结算共享事实
            if (settleFirst(r, AgentOperation.CREATE)) return@launch
            val own = conversation == this@AgentController.conversation && sessionId == null
            if (r.ok) {
                val id = sessionIdOf(AgentJson.objectOf(r.data["session"]) ?: r.data)
                if (own && id != null) sessionId = id
                return@launch
            }
            afterOp(r, { own && seq == runSeq && active == null }, AgentOperation.CREATE, entry)
        }
    }

    /**
     * 同步作废当前对话（新对话、流内 10004、会话已不存在）：关连接、停计时、同步取消本控制器归属的在途请求（鸿蒙 R1-11）、清消息与卡片回调；对话世代 +1。
     * 同意闸门不随新对话解除（没记录成功就一直禁发）。
     */
    private fun dropConversation() {
        turns.forEach { turn ->
            turn.client?.cancel()
            turn.client = null
            cancelTimers(turn)
            turn.reconnect = null
        }
        active = null
        turns = emptyList()
        conversation += 1
        sessionId = null
        historySeq += 1
        workSeq += 1
        reportTurn = null
        runCatching { deps.contract?.cancelOwned(identity) }
        revokeCardActions()
        announcement = ""
    }

    /**
     * 【重试】：断线 → 先拉历史首页（G07，[AgentRetryMode.RELOAD]）；50401 等 → 同一 client_msg_id 重发（[AgentRetryMode.SAME]）；流内 error → 新消息
     * （[AgentRetryMode.NEW]）；duplicate 30506 的【刷新】→ 拉一次历史首页（[AgentRetryMode.REFRESH]）。后台不发（03 §7.2）。
     */
    @Suppress("ReturnCount", "ComplexCondition", "CyclomaticComplexMethod") // 票据、限流、各模式
    fun retry(t: AgentRetryTap) {
        val turn = turnOf(t.controller, t.conversation, t.run) ?: return
        val tap = turn.error?.retry
        if (active != null || tap == null || turn.run.clientMsgId != t.clientMsgId || tap.mode != t.mode ||
            consentGate != AgentConsentGate.CLEAR ||
            !visible || turn.reconnect != null || unavailableLocked
        ) {
            return
        }
        when (t.mode) {
            AgentRetryMode.RELOAD, AgentRetryMode.REFRESH -> {
                if (gates.read.active) {
                    deps.toast(errorText(AgentErrorCodes.RATE_LIMITED, ""))
                    return
                }
                if (sessionId == null) {
                    // 还没有会话（建会话前就断了）：没有历史可拉，同键重发
                    if (t.mode == AgentRetryMode.RELOAD) resendSame(turn)
                    return
                }
                turn.error = null
                if (turn.state.status == AgentStatus.DISCONNECTED) turn.state = turn.state.copy(status = AgentStatus.STREAMING)
                startReload(
                    turn,
                    if (t.mode ==
                        AgentRetryMode.REFRESH
                    ) {
                        AgentReloadTrigger.REFRESH
                    } else {
                        AgentReloadTrigger.DISCONNECT
                    },
                    userRetry = true,
                )
                return
            }
        }
        if (gates.send.active) {
            deps.toast(errorText(AgentErrorCodes.RATE_LIMITED, ""))
            return
        }
        if (readOnly) return
        if (t.mode == AgentRetryMode.NEW) {
            sendText(turn.userText)
            return
        }
        resendSame(turn)
    }

    /** 快捷追问 / 示例问题 / 「换一批」：用户点击才发（绑定渲染时的对话世代；卡片上的胶囊另比卡片动作世代）。 */
    fun chip(t: AgentChipTap) {
        if (!pageOk(AgentPageTicket(t.controller, t.conversation))) return
        if (t.epoch >= 0 && t.epoch != cardEpoch) return
        sendText(t.sendText)
    }

    private fun sendText(text: String) {
        send(AgentPageTicket(serial, conversation), text)
    }

    // ---------------------------------------------------------------- 向上翻页（G08）

    /**
     * 滚到顶部（第一条消息进入可见区）：按 next_cursor 取更早一页，一次只在途一页（读取闸门；ApiClient 的 GET 自动重试 1 次照旧）。结果比票据（对话、历史票据）后插在前面；
     * 30504 复用会话失效处理（鸿蒙 R2-04）；失败保留完整结果显示统一文案（5xxxx 带 trace6，R2-05），点它再试。
     */
    @Suppress("ReturnCount", "ComplexCondition", "CyclomaticComplexMethod") // 票据、闸门、游标依次返回
    fun loadEarlier(t: AgentPageTicket) {
        if (!pageOk(t) || phase != AgentPhase.READY || gates.read.active || unavailableLocked) return
        val contract = deps.contract ?: return
        val id = sessionId ?: return
        val p = pager ?: return
        val cursor = p.top() ?: return
        earlierError = ""
        val conversation = this.conversation
        val seq = historySeq
        val entry = this.entry
        publish()
        val call = call(AgentOperation.HISTORY) { seq == historySeq && sessionId == id && pager === p }
        deps.scope.launch {
            val r = contract.listMessages(id, cursor, AgentHistory.LIMIT, call)
            // 先共享结算、后页面判断（R3-01）：控制器已作废（页面重建 / 换身份）时只按原 ownerKey 结算共享事实
            if (settleFirst(r, AgentOperation.HISTORY)) return@launch
            if (conversation != this@AgentController.conversation || seq != historySeq || pager !== p) {
                settleOnly(r, AgentOperation.HISTORY)
                return@launch
            }
            val page = if (r.ok) AgentHistory.page(r.data, deps.routes) else null
            if (page != null) {
                val local = turns.flatMap { listOfNotNull(it.userMessageId, it.replyId) }.toSet()
                val older = AgentHistory.groups(page.items).filter { g -> g.ids.none { it in local } }.flatMap(::historyTurnsOf)
                turns = older + turns
                p.arrived(page.nextCursor)
                if (page.expired) applyExpired()
                publish()
                return@launch
            }
            p.failed()
            if (r.ok) {
                earlierError = AgentDraftTexts.text(EARLIER_FAILED_KEY)
            } else if (r.code == AgentErrorCodes.ROUND_LIMIT) {
                sessionGone()
                return@launch
            } else {
                when (sharedError(r, null, AgentOperation.HISTORY, entry)) {
                    AgentSharedError.CONSENT, AgentSharedError.UNAVAILABLE -> {
                        Unit
                    }

                    else -> {
                        earlierError =
                            if (r.notSent || r.code < 0) errorText(AgentErrorCodes.GENERIC, "") else errorText(r.codeOrGeneric, r.traceId)
                    }
                }
            }
            publish()
        }
    }

    /** 会话已不存在（翻页 30504，鸿蒙 R2-04、G73）：作废旧会话工作、清 session_id / 分页 / 对话，显示 error.30504 +【新对话】（下次发送重建会话）。 */
    private fun sessionGone() {
        dropConversation()
        pager = null
        earlierError = ""
        readOnly = false
        sessionEndedText = errorText(AgentErrorCodes.ROUND_LIMIT, "")
        phase = AgentPhase.READY
        publish()
    }

    // ---------------------------------------------------------------- 反馈与举报（APP-10b §2 第 8、9 条，G28、G29、G31）

    /** 这一轮此刻显示【赞】【踩】与举报入口（G03、G28）。 */
    private fun actionsVisible(t: Turn): Boolean =
        AgentFeedback.visible(
            AgentRole.ASSISTANT,
            t.state.status,
            t.replyId,
            !t.replyIdDistrusted,
            if (t.replyIdDistrusted) AgentRole.USER else AgentRole.ASSISTANT,
            t.duplicatePending,
        ) && !t.stopping && t.reconnect == null && hasBody(t)

    /**
     * 回复有正文（编排者裁定第 3 条、Android R1-05）：正文为空（例如停止于第一个字之前的历史回复）不画【赞】【踩】与举报入口。正文按可见内容算：渲染后的文字非空白，
     * 或有卡片（只有卡片的回复也是用户看到的回答，A18）。出错 / 30506 的回复只要有正文且有可信回复 id 照常可反馈。
     */
    private fun hasBody(t: Turn): Boolean = plain(t.state).isNotBlank() || t.state.cards.isNotEmpty()

    private fun replyTurn(t: AgentReplyTicket): Turn? {
        if (!alive() || t.controller != serial || t.conversation != conversation) return null
        return turns.firstOrNull { it.replyId == t.messageId }?.takeIf(::actionsVisible)
    }

    /** 点【赞】/【踩】：乐观置为实心（读屏「已赞 / 已踩」），发 setAgentMessageFeedback；同值不发、在途不可点、动作闸门期间不发（G28）。 */
    @Suppress("ReturnCount", "ComplexCondition", "CyclomaticComplexMethod") // 票据、闸门、状态依次返回；结果分共享结算与状态两层（R2-01）
    fun feedback(
        t: AgentReplyTicket,
        value: String,
    ) {
        if (!visible || unavailableLocked) return
        val turn = replyTurn(t) ?: return
        val contract = deps.contract ?: return
        if (gates.action.active) {
            deps.toast(errorText(AgentErrorCodes.RATE_LIMITED, ""))
            return
        }
        val send = turn.feedback.tap(value) ?: return
        publish()
        val seq = workSeq
        val conversation = this.conversation
        val entry = this.entry
        val messageId = t.messageId
        val act = turn.actionSeq
        val call = call(AgentOperation.FEEDBACK) { seq == workSeq && turn.replyId == messageId && turn.actionSeq == act }
        deps.scope.launch {
            val r = contract.setFeedback(messageId, send, call)
            // 先共享结算、后页面判断（R3-01）：控制器已作废时只按原 ownerKey 结算共享事实（AI 10004、42901、10405）
            if (settleFirst(r, AgentOperation.FEEDBACK)) return@launch
            // 控制器仍有效：AI 10004 先于动作状态机结算（R2-01，S0），不依赖动作状态机此刻是否仍在途
            if (actionConsentFirst(r)) return@launch
            if (seq != workSeq || conversation != this@AgentController.conversation || turn !in turns || turn.replyId != messageId ||
                turn.actionSeq != act
            ) {
                settleOnly(r, AgentOperation.FEEDBACK)
                return@launch
            }
            val fail = turn.feedback.result(r)
            if (fail != null) {
                actionFailed(fail, r, AgentOperation.FEEDBACK, entry)
            } else if (!r.ok) {
                // 状态机已不在途（被重置）：失败结果仍做共享结算
                settleOnly(r, AgentOperation.FEEDBACK)
            }
            publish()
        }
    }

    /** 长按回复气泡 / 读屏「更多操作 → 举报」：打开举报半屏（同样的显示条件；已举报不再提供）。 */
    @Suppress("ReturnCount") // 逐项提前返回
    fun openReport(t: AgentReplyTicket) {
        if (!visible || unavailableLocked) return
        val turn = replyTurn(t) ?: return
        if (turn.report.reported) return
        reportTurn?.takeIf { it !== turn }?.report?.close()
        turn.report.open()
        reportTurn = turn
        publish()
    }

    /** 关闭举报半屏（【关闭】、系统返回、点遮罩）：只关这一个实例，已填内容保留在内存（同一条再打开仍在）。 */
    fun closeReport(t: AgentReplyTicket) {
        val turn = reportTurn ?: return
        if (turn.replyId != t.messageId || t.controller != serial) return
        turn.report.close()
        reportTurn = null
        publish()
    }

    /** 选原因（五选一，默认不选）。 */
    fun selectReportReason(
        t: AgentReplyTicket,
        reason: String,
    ) {
        val turn = reportTurn?.takeIf { it.replyId == t.messageId && t.controller == serial } ?: return
        if (reason !in AgentReport.reasons()) return
        turn.report.select(reason)
        publish()
    }

    /** 补充说明（选填；按 200 − 原因长度 − 1 个码点限长，G29）。只在内存，不进日志与埋点。 */
    fun editReportSupplement(
        t: AgentReplyTicket,
        text: String,
    ) {
        val turn = reportTurn?.takeIf { it.replyId == t.messageId && t.controller == serial } ?: return
        turn.report.supplement = text
        publish()
    }

    /** 【提交举报】：reason 按 G29 拼成 1–200 码点（超出不让提交）；单飞；动作闸门期间不发。 */
    @Suppress("ReturnCount", "ComplexCondition", "CyclomaticComplexMethod") // 票据、闸门、状态依次返回
    fun submitReport(t: AgentReplyTicket) {
        if (!visible || unavailableLocked) return
        val turn = reportTurn?.takeIf { it.replyId == t.messageId && t.controller == serial && t.conversation == conversation } ?: return
        val contract = deps.contract ?: return
        if (gates.action.active) {
            deps.toast(errorText(AgentErrorCodes.RATE_LIMITED, ""))
            return
        }
        val reason = turn.report.submit() ?: return
        publish()
        val seq = workSeq
        val conversation = this.conversation
        val entry = this.entry
        val messageId = t.messageId
        val act = turn.actionSeq
        val call = call(AgentOperation.REPORT) { seq == workSeq && turn.replyId == messageId && turn.actionSeq == act }
        deps.scope.launch {
            val r = contract.report(messageId, reason, call)
            // 先共享结算、后页面判断（R3-01）
            if (settleFirst(r, AgentOperation.REPORT)) return@launch
            // 控制器仍有效：AI 10004 先于动作状态机结算（R2-01，S0）
            if (actionConsentFirst(r)) return@launch
            if (seq != workSeq || conversation != this@AgentController.conversation || turn !in turns || turn.replyId != messageId ||
                turn.actionSeq != act
            ) {
                settleOnly(r, AgentOperation.REPORT)
                return@launch
            }
            if (!r.ok && !turn.report.inFlight) {
                // 状态机已不在途（被重置）：失败结果仍做共享结算
                settleOnly(r, AgentOperation.REPORT)
                publish()
                return@launch
            }
            val outcome = turn.report.result(r)
            if (outcome.success) {
                if (reportTurn === turn) reportTurn = null
                if (current(entry)) deps.toast(AgentDraftTexts.text(AgentReport.TOAST_KEY))
            } else {
                if (!turn.report.sheetOpen && reportTurn === turn) reportTurn = null
                outcome.failure?.let { actionFailed(it, r, AgentOperation.REPORT, entry) }
            }
            publish()
        }
    }

    /**
     * 反馈 / 举报结果的第一层（Android R2-01，共性第 33、40 条，S0）：控制器仍有效时（已作废的由 [settleFirst] 结算），在消费动作状态机之前独立结算 AI 10004——
     * 同步停流、作废所属工作、锁同意闸门并以构造时不含页面实例的 ownerKey 上报应用级失效 1 次（不弹框、不导航）。返回 true = 已结算，调用方不再处理。
     */
    private fun actionConsentFirst(r: AgentHttpResult): Boolean {
        if (r.notSent || !AgentConsentPolicy.shows(r.code, r.consentType)) return false
        aiConsentRevoked(null, interactive = false)
        return true
    }

    /** 反馈 / 举报失败的共享结算与提示（G24、G31）：AI 10004 → 同步作废并锁闸门（不弹框）；42901 → 动作闸门；前置码 → 可见才导航；其余可见时提示。 */
    private fun actionFailed(
        fail: AgentActionFailure,
        r: AgentHttpResult,
        op: String,
        entry: Long,
    ) {
        val shared = sharedError(r, null, op, entry)
        if (shared == AgentSharedError.CONSENT || shared == AgentSharedError.UNAVAILABLE || shared == AgentSharedError.UNIFIED) return
        if (shared == AgentSharedError.RATE_LIMITED || shared == AgentSharedError.FORCE_UPDATE) return
        if (fail.message.isNotEmpty() && current(entry)) deps.toast(fail.message)
    }

    // ---------------------------------------------------------------- 归属变化（APP-10b G01，应用级协调器调用）

    /**
     * 应用级归属协调器（[AgentOwnershipStop]）在退出登录、撤回 AI 同意、撤回隐私、切环境等流程的确认入口同步调用：同步作废本控制器（关连接、停计时、作废票据与
     * 卡片回调、cancelOwned、清空对话），交出在途的那一轮——run_id（没有 meta 为 null）+ 以原身份准入的 cancel 调用（不挂页面归属、单次发送；准入核对原归属与动作
     * 闸门）。控制器自己不发 cancel（APP-10 G06 / 旧向量 ticket 表照旧）。
     */
    override fun stopForOwnership(event: String): AgentOwnedRun? {
        if (disposed) return null
        val turn = active
        val runId = turn?.state?.runId
        val actionGate = gates.action
        val epochs = identity.epochs
        val call =
            AgentCall(
                identityKey = identity,
                anonymous = !identity.member,
                sendAllowed = {
                    ownerStill() && runCatching { deps.currentIdentity()?.epochs == epochs }.getOrDefault(false) &&
                        !actionGate.active
                },
                owner = { ownerStill() },
                pageOwned = false,
                singleShot = true,
            )
        dispose()
        return turn?.let { AgentOwnedRun(runId, call, ownerKey) }
    }

    // ---------------------------------------------------------------- 卡片动作（只在用户点击时）

    /**
     * 卡片按钮：page_guide 与收益卡按钮以 agent_card 来源、**空参数**交 Router（EntryGuard 再按包内 agent_guide 校验、参数必须为空，brief §0 第 1 条）；
     * 其余按 in_app。离开页面后执行的旧回调不导航（APP-12 第 14 条）。
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod") // 可见性、对话级、卡片级依次判定
    fun tap(t: AgentTap) {
        if (!visible) return
        if (t.kind == AgentTapKind.SEARCH || t.kind == AgentTapKind.LOGIN || t.kind == AgentTapKind.BIND_PHONE) {
            turnOf(t.controller, t.conversation, t.run) ?: return
            deps.open(RouteRequest(t.route, t.params, RouteSource.IN_APP))
            return
        }
        val turn = cardTurnOf(t.controller, t.conversation, t.run, t.epoch) ?: return
        val card = cardIn(turn, t.cardId)?.takeIf { it.known } ?: return
        when (t.kind) {
            AgentTapKind.GUIDE, AgentTapKind.EARNINGS -> {
                // 只认卡上已校验的路由：page_guide 的 route、收益卡的两个固定按钮
                val allowed =
                    if (t.kind == AgentTapKind.GUIDE) {
                        AgentJson.stringOf(card.data["route"]) == t.route
                    } else {
                        t.route == AgentCardDecoder.EARNINGS_WALLET || t.route == AgentCardDecoder.EARNINGS_RECORDS
                    }
                if (allowed) deps.open(RouteRequest(t.route, AgentJson.EMPTY, RouteSource.AGENT_CARD))
            }

            AgentTapKind.HANDOFF -> {
                deps.customerService()
            }

            AgentTapKind.AUTH -> {
                authTap(t)
            }

            else -> {
                deps.open(RouteRequest(t.route, t.params, RouteSource.IN_APP))
            }
        }
    }

    /**
     * auth_required（G19）：login / bind_phone → Login / BindPhone；union_auth → 若本对话里有 resume_link_id 对应的商品卡，经 OpenPurchaseController
     * 对它发起 open（服务端回 30101 / 30111 时由已有流程拉起授权，授权成功后自动执行一次 open）；没有就只打开 AuthSheet{platform}（鸿蒙 H04）。
     */
    private fun authTap(t: AgentTap) {
        val resume = t.resumeLinkId
        if (t.route == AUTH_SHEET_ROUTE && resume != null) {
            for (x in turns.filter { !it.revoked }) {
                for (c in x.state.cards) {
                    val p = productsOf(x, c).firstOrNull { AgentJson.stringOf(it["link_id"]) == resume } ?: continue
                    deps.buy(
                        p,
                        AgentProductRef(
                            serial,
                            conversation,
                            x.run.run,
                            c.cardId,
                            AgentJson.stringOf(p["card_id"]).orEmpty(),
                            resume,
                            cardEpoch,
                        ),
                    )
                    return
                }
            }
        }
        deps.open(RouteRequest(t.route, t.params, RouteSource.IN_APP))
    }

    /** 购买按钮：把渲染时的 link_id 与卡片快照交已有 OpenPurchaseController（BR-PRICE-13、14）。 */
    @Suppress("ReturnCount") // 可见性、票据、卡片依次判定
    fun buy(t: AgentBuyTap) {
        if (!visible) return
        val turn = cardTurnOf(t.controller, t.conversation, t.run, t.epoch) ?: return
        cardIn(turn, t.cardId) ?: return
        deps.buy(t.product, t.ref)
    }

    /**
     * 购买结果对卡片的更新（PurchaseEnv.updateCard）：只替换这一张卡（以 card_id 定位、渲染票据比较）；离开 Agent 页后到达的 open 响应只更新卡片，
     * 控制器已作废、【停止】过时什么都不改。
     */
    @Suppress("ReturnCount") // 票据、卡片、商品依次判定
    fun updateProduct(
        ref: AgentProductRef,
        transform: (JsonObject) -> JsonObject,
    ) {
        val turn = cardTurnOf(ref.controller, ref.conversation, ref.run, ref.epoch) ?: return
        val card = cardIn(turn, ref.cardId) ?: return
        val p = productsOf(turn, card).firstOrNull { AgentJson.stringOf(it["card_id"]) == ref.itemCardId } ?: return
        turn.products["${ref.cardId}#${ref.itemCardId}"] = transform(p)
        publish()
    }

    /** 卡片动作与购买：在 [turnOf] 之上再比卡片动作世代，且这一轮没被【停止】（共性第 3 条）。 */
    private fun cardTurnOf(
        controller: Long,
        conversation: Long,
        run: String,
        epoch: Long,
    ): Turn? = turnOf(controller, conversation, run)?.takeIf { !it.revoked && epoch == cardEpoch }

    private fun cardIn(
        turn: Turn,
        cardId: String,
    ): AgentCard? = turn.state.cards.firstOrNull { it.cardId == cardId }

    /** 卡里的商品（购买结果更新过的用新卡）。 */
    private fun productsOf(
        turn: Turn,
        card: AgentCard,
    ): List<JsonObject> {
        if (!card.known) return emptyList()
        val raw =
            when (card.type) {
                AgentCardType.PRODUCT_LIST -> AgentJson.arrayOf(card.data["items"]).orEmpty()
                AgentCardType.REBATE_QUOTE -> listOfNotNull(card.data["product"])
                else -> emptyList()
            }
        return raw.mapNotNull { AgentJson.objectOf(it) }.map { p ->
            turn.products["${card.cardId}#${AgentJson.stringOf(p["card_id"]).orEmpty()}"] ?: p
        }
    }

    // ---------------------------------------------------------------- AgentConsent（G16、G17）

    private fun openConsent(pendingRun: String?) {
        consentSerial += 1
        consent = AgentConsentTicket(serial, consentSerial, pendingRun)
        // 待同意：从这一刻起禁发（共性第 1 条），直到 recordConsent 成功
        consentGate = AgentConsentGate.AWAITING
        publish()
    }

    /** 当前弹窗票据仍有效（关闭回调只提交自己的票据，APP-11 第 17 条）。 */
    fun consentValid(t: AgentConsentTicket): Boolean {
        val c = consent ?: return false
        return alive() && c == t && consentGate == AgentConsentGate.AWAITING
    }

    /** 同意弹窗此刻能不能显示（共性第 5 条）：票据仍是此刻那一张、页面可见、没有阻挡层。不满足时页面不组合弹窗（不提交），条件恢复后按同一张票据再显示。 */
    fun consentShowable(
        t: AgentConsentTicket,
        blocked: Boolean,
    ): Boolean = consentValid(t) && visible && !blocked

    /**
     * 【同意并使用】：弹窗票据当场作废、闸门进入提交中（仍禁发）；recordConsent 成功才回到 clear，并用同一 client_msg_id 重放原请求一次。失败保持禁发
     * （显示失败说明与【重新同意】），待重放的消息丢弃。结果到达时页面已离开：只解除 / 保持闸门，不重放（原消息给同键【重试】，鸿蒙 H18）。
     */
    fun consentAgree(t: AgentConsentTicket) {
        if (!consentValid(t)) return
        consent = null
        consentGate = AgentConsentGate.SUBMITTING
        submitting = t
        val entry = this.entry
        publish()
        deps.scope.launch {
            val r = runCatching { deps.recordConsent(true) }.getOrElse { AgentHttpResult.network() }
            if (!alive() || submitting != t) return@launch
            submitting = null
            if (!r.ok) {
                consentRecordFailed(t, r, entry)
                return@launch
            }
            consentGate = AgentConsentGate.CLEAR
            consentFailed = false
            if (phase == AgentPhase.CONSENT_DECLINED) phase = AgentPhase.READY
            val turn = t.pendingRun?.let { turnOf(serial, conversation, it) }
            if (turn == null || turn.phase != AgentTurnPhase.AWAITING_CONSENT || active != null) {
                publish()
                return@launch
            }
            if (!current(entry) || turn.consentReplays >= 1) {
                // 离开后才记录成功：不自动发出，原消息给同键【重试】（用户回来自己点）
                turn.phase = AgentTurnPhase.FAILED
                turn.error = AgentErrorView(AgentDraftTexts.text(DISCONNECTED_KEY), retry = AgentRetryTap(turn.run, AgentRetryMode.SAME))
                publish()
                return@launch
            }
            turn.consentReplays += 1
            turn.run = newRun(ClientMsgId.forRetry(ClientMsgId.REPLAY_AFTER_CONSENT, turn.run.clientMsgId))
            turn.phase = AgentTurnPhase.CONNECTING
            active = turn
            publish()
            dispatch(turn)
        }
    }

    /** 同意没记录成功（鸿蒙 H32、R2-06 同口径）：闸门保持禁发、显示失败说明与【重新同意】；发起时页面仍可见时，前置码走统一前置路由，其余按统一文案。 */
    private fun consentRecordFailed(
        t: AgentConsentTicket,
        r: AgentHttpResult,
        entry: Long,
    ) {
        consentGate = AgentConsentGate.REFUSED
        consentFailed = true
        dropPending(t.pendingRun)
        phase = AgentPhase.CONSENT_DECLINED
        if (r.code == AgentErrorCodes.RATE_LIMITED) gates.send.start(AgentSendErrorPolicy.retryAfterMs(r.retryAfter))
        if (r.code == AgentErrorCodes.FORCE_UPDATE) deps.forceUpdate()
        if (current(entry) && !r.notSent && r.code != AgentErrorCodes.FORCE_UPDATE) {
            val route = AgentPrereq.route(r.code, r.data)
            if (route !=
                null
            ) {
                deps.open(RouteRequest(route, AgentJson.EMPTY, RouteSource.IN_APP))
            } else {
                deps.toast(errorText(r.codeOrGeneric, r.traceId))
            }
        }
        publish()
    }

    /** 【暂不使用】：记 accepted=false（channel agent_sheet），留在 Agent 页显示说明与【重新同意】（G17）；闸门保持禁发。 */
    fun consentDecline(t: AgentConsentTicket) {
        if (!consentValid(t)) return
        consent = null
        consentGate = AgentConsentGate.REFUSED
        consentFailed = false
        dropPending(t.pendingRun)
        phase = AgentPhase.CONSENT_DECLINED
        publish()
        deps.scope.launch {
            val r = runCatching { deps.recordConsent(false) }.getOrElse { AgentHttpResult.network() }
            if (!r.ok && alive()) deps.log("agent consent decline not recorded ${r.code}")
        }
    }

    /** 系统返回 / 点遮罩关闭：只关自己这一个弹窗（按暂不使用处理，不记录、不重放，鸿蒙 H09）；闸门保持禁发。 */
    fun consentDismissed(t: AgentConsentTicket) {
        if (!consentValid(t)) return
        consent = null
        consentGate = AgentConsentGate.REFUSED
        consentFailed = false
        dropPending(t.pendingRun)
        phase = AgentPhase.CONSENT_DECLINED
        publish()
    }

    /** 【重新同意】：再弹同意框（没有待重放请求）。 */
    fun consentReopen(t: AgentPageTicket) {
        if (!pageOk(t) || phase != AgentPhase.CONSENT_DECLINED || consentGate != AgentConsentGate.REFUSED) return
        openConsent(null)
    }

    private fun dropPending(run: String?) {
        run ?: return
        turns = turns.filter { it.run.run != run || it.phase != AgentTurnPhase.AWAITING_CONSENT }
    }

    // ---------------------------------------------------------------- 作废

    /**
     * 同步作废（归属变化、页面销毁、配置关闭）：关闭连接、取消全部计时（30 秒 / 90 秒、正在停止、重载退避）、作废全部 run 票据与卡片回调、cancelOwned（历史、反馈、
     * 举报、建会话一并取消）、清空消息与金额、释放订阅；控制器自己不发 cancel（G06；身份事件的 cancel 由应用级协调器在调用它之前捕获）。
     */
    fun dispose() {
        if (disposed) return
        disposed = true
        turns.forEach { turn ->
            turn.client?.cancel()
            turn.client = null
            cancelTimers(turn)
            turn.reconnect = null
        }
        turns = emptyList()
        active = null
        consent = null
        submitting = null
        context = null
        quotaLeft = null
        reportTurn = null
        pager = null
        historySeq += 1
        workSeq += 1
        runCatching { deps.contract?.cancelOwned(identity) }
        subscriptions.forEach { it.cancel() }
        subscriptions.clear()
        mutableSnapshot.value = AgentSnapshot(owner = serial)
    }

    // ---------------------------------------------------------------- 情境入口（G27）

    /**
     * 情境入口（G27）：in_app 入口交来的 context（{product_key} / {order_id} / {text ≤2000}，三选一，不合就丢弃）附在用户接下来发的第一条消息上；
     * 不自动发送、不清空当前对话。
     */
    fun attachContext(raw: JsonObject?) {
        if (!alive()) return
        context = validContext(raw)
        publish()
    }

    private fun contextLabel(): String? {
        val c = context ?: return null
        return when {
            AgentJson.has(c, CONTEXT_PRODUCT) -> "agent.draft.context.product"
            AgentJson.has(c, CONTEXT_ORDER) -> "agent.draft.context.order"
            else -> "agent.draft.context.text"
        }
    }

    // ---------------------------------------------------------------- 快照

    private fun buildSnapshot(): AgentSnapshot {
        val page = AgentPageTicket(serial, conversation)
        if (!alive()) return AgentSnapshot(owner = serial, conversation = conversation, phase = phase, revision = revision, page = page)
        val masked = deps.masked.value
        val orders = AgentDraftTexts.text("agent.draft.chip.orders")
        val p = pager
        return AgentSnapshot(
            owner = serial,
            conversation = conversation,
            phase = phase,
            turns = turns.map { turnView(it, masked) },
            sending = active != null,
            stop = active?.run,
            limited = gates.send.active,
            quotaExhausted = quotaExhausted,
            quotaLeft = quotaLeft,
            consent = consent,
            consentGate = consentGate,
            consentFailed = consentFailed,
            page = page,
            examples = if (readOnly) emptyList() else deps.examples().map { AgentChipTap(serial, conversation, it, it) },
            ordersChip = if (readOnly) null else AgentChipTap(serial, conversation, orders, orders),
            filingText = deps.filingText(),
            contextLabelKey = contextLabel(),
            loadErrorText = loadErrorText,
            revision = revision,
            announcement = announcement,
            amountMasked = masked,
            readLimited = gates.read.active,
            actionLimited = gates.action.active,
            hasEarlier = p?.hasEarlier == true,
            earlierLoading = p?.loading == true,
            earlierError = earlierError,
            historyExhausted = p != null && !p.hasEarlier && turns.isNotEmpty(),
            readOnly = readOnly,
            sessionEndedText = sessionEndedText,
            reportSheet = reportSheetView(),
        )
    }

    @Suppress("ReturnCount") // 逐项提前返回
    private fun reportSheetView(): AgentReportSheet? {
        val t = reportTurn ?: return null
        val id = t.replyId ?: return null
        val r = t.report
        if (!r.sheetOpen) return null
        val check = AgentReport.compose(r.selected, r.supplement)
        return AgentReportSheet(
            ticket = AgentReplyTicket(serial, conversation, id),
            excerpt = excerpt(plain(t.state)),
            reasons = AgentReport.reasons(),
            selected = r.selected,
            supplement = r.supplement,
            supplementLimit = AgentReport.supplementLimit(r.selected),
            submitEnabled = check.validation == AgentReportCheck.OK && !r.inFlight && !gates.action.active,
            inFlight = r.inFlight,
            reasonError = r.inlineError,
            tooLong = check.validation == AgentReportCheck.TOO_LONG,
        )
    }

    @Suppress("CyclomaticComplexMethod") // 按分支逐条对照规则
    private fun turnView(
        t: Turn,
        masked: Boolean,
    ): AgentTurnView {
        val state = t.state
        val ended = t.phase == AgentTurnPhase.ENDED
        val chips = if (readOnly) emptyList() else state.suggestions.map { AgentChipTap(serial, conversation, it.text, it.sendText) }
        // 每条已结束的 assistant 回复都有 AiLabel（done、error、停止、断线收尾都算，03 §7.2；共性第 8 条）：meta.ai_label，缺则字典 ai_label
        val label = if (ended && state.metaSeen && !t.stopping) state.aiLabel ?: DefaultTexts.text(AI_LABEL_KEY) else null
        val id = t.replyId
        val actions =
            if (id != null && ended && actionsVisible(t)) {
                AgentReplyActions(
                    ticket = AgentReplyTicket(serial, conversation, id),
                    selected = t.feedback.selected,
                    enabled = t.feedback.enabled && !gates.action.active,
                    a11y = AgentFeedback.a11y(t.feedback.selected),
                    reported = t.report.reported,
                )
            } else {
                null
            }
        val statusText =
            when {
                t.stopping -> AgentDraftTexts.text(STOPPING_KEY)
                state.status == AgentStatus.CANCELLED && state.text.isEmpty() && t.history -> AgentDraftTexts.text(AgentHistory.STOPPED_KEY)
                state.status == AgentStatus.LIMITED -> AgentDraftTexts.text(LIMITED_KEY)
                state.status == AgentStatus.INCOMPLETE -> AgentDraftTexts.text(INCOMPLETE_KEY)
                else -> ""
            }
        return AgentTurnView(
            key = t.run.run,
            run = t.run,
            userText = t.userText,
            phase = t.phase,
            status = state.status,
            blocks = AgentText.render(state.text),
            tools = state.tools,
            cards = state.cards.map { cardView(t, it, masked) },
            chips = if (ended) chips else emptyList(),
            aiLabel = label,
            error = t.error,
            streaming = t === active || t.reconnect != null,
            history = t.history,
            actions = actions,
            statusText = statusText,
        )
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod") // 每种卡一个分支
    private fun cardView(
        t: Turn,
        c: AgentCard,
        masked: Boolean,
    ): AgentCardView {
        if (!c.known) return AgentCardView(c)
        val run = t.run
        // 【停止】过的这一轮（共性第 3 条）：卡片照常显示，不再给任何按钮票据
        val inert = t.revoked
        val d = c.data

        fun tap(
            kind: String,
            route: String,
            params: JsonObject,
            source: RouteSource,
            label: String,
            text: String = label,
            resume: String? = null,
        ): AgentTap? =
            if (inert) {
                null
            } else {
                AgentTap(run.controller, run.conversation, run.run, c.cardId, kind, route, params, source, label, text, resume, cardEpoch)
            }
        return when (c.type) {
            AgentCardType.PRODUCT_LIST, AgentCardType.REBATE_QUOTE -> {
                val layout =
                    if (c.type ==
                        AgentCardType.PRODUCT_LIST
                    ) {
                        AgentJson.stringOf(d["layout"]) ?: AgentCardView.LAYOUT_LIST
                    } else {
                        AgentCardView.LAYOUT_SINGLE
                    }
                val (title, lines) = if (c.type == AgentCardType.REBATE_QUOTE) materialLines(d) else "" to emptyList()
                AgentCardView(
                    c,
                    layout = layout,
                    products = productsOf(t, c).map { productView(run, c.cardId, it, inert) },
                    title = title,
                    lines = lines,
                )
            }

            AgentCardType.PAGE_GUIDE -> {
                val route = AgentJson.stringOf(d["route"]).orEmpty()
                val key = AgentJson.stringOf(d["text_key"]).orEmpty()
                val open = AgentDraftTexts.text("agent.guide.open")
                val label = AgentDraftTexts.format("agent.draft.a11y.button", mapOf("action" to open, "target" to routeLabel(route)))
                AgentCardView(
                    c,
                    guideText = if (AgentDraftTexts.has(key)) AgentDraftTexts.text(key) else fallbackPlain(c),
                    guide = tap(AgentTapKind.GUIDE, route, AgentJson.EMPTY, RouteSource.AGENT_CARD, label, open),
                )
            }

            AgentCardType.EARNINGS_SUMMARY -> {
                val earnings = EarningsCardView.view(c, masked, deps.clock())
                AgentCardView(
                    c,
                    earnings = earnings,
                    earningsTaps =
                        earnings.buttons.mapNotNull { b ->
                            tap(AgentTapKind.EARNINGS, b.route, AgentJson.EMPTY, RouteSource.AGENT_CARD, AgentDraftTexts.text(b.textKey))
                        },
                )
            }

            AgentCardType.RULE_REF -> {
                val action = AgentJson.objectOf(d["action"]) ?: AgentJson.EMPTY
                AgentCardView(
                    c,
                    rule =
                        tap(
                            AgentTapKind.RULE,
                            AgentJson.stringOf(action["route"]).orEmpty(),
                            AgentJson.objectOf(action["params"]) ?: AgentJson.EMPTY,
                            RouteSource.IN_APP,
                            AgentDraftTexts.text("agent.draft.rule.view"),
                        ),
                    title = AgentJson.stringOf(d["title"]).orEmpty(),
                    lines = listOf(AgentJson.stringOf(d["excerpt"]).orEmpty()),
                )
            }

            AgentCardType.ORDER_STATUS -> {
                orderView(
                    c,
                    d,
                    masked,
                    tap(
                        AgentTapKind.ORDER,
                        ORDER_DETAIL_ROUTE,
                        JsonObject(
                            mapOf("order_id" to (d["order_id"] ?: JsonPrimitive(""))),
                        ),
                        RouteSource.IN_APP,
                        AgentDraftTexts.text("agent.draft.order.title"),
                    ),
                )
            }

            AgentCardType.AUTH_REQUIRED -> {
                val reason = AgentJson.stringOf(d["reason"])
                val platform = AgentJson.stringOf(d["platform"])
                val (route, params, label) =
                    when (reason) {
                        REASON_BIND_PHONE -> {
                            Triple(BIND_PHONE_ROUTE, AgentJson.EMPTY, AgentDraftTexts.text("agent.draft.bind_phone"))
                        }

                        REASON_UNION_AUTH -> {
                            Triple(
                                AUTH_SHEET_ROUTE,
                                JsonObject(mapOf("platform" to JsonPrimitive(platform.orEmpty()))),
                                AgentDraftTexts.text("agent.draft.auth.go"),
                            )
                        }

                        else -> {
                            Triple(LOGIN_ROUTE, AgentJson.EMPTY, AgentDraftTexts.text("agent.draft.login"))
                        }
                    }
                val line =
                    when (reason) {
                        REASON_UNION_AUTH -> {
                            AgentDraftTexts.format(
                                "agent.draft.auth.union",
                                mapOf("platform_name" to deps.platformName(platform.orEmpty())),
                            )
                        }

                        REASON_BIND_PHONE -> {
                            AgentDraftTexts.text("agent.need_phone")
                        }

                        else -> {
                            AgentDraftTexts.text("agent.need_login")
                        }
                    }
                AgentCardView(
                    c,
                    auth =
                        tap(
                            AgentTapKind.AUTH,
                            route,
                            params,
                            RouteSource.IN_APP,
                            label,
                            resume = AgentJson.stringOf(d["resume_link_id"]),
                        ),
                    lines = listOf(line),
                )
            }

            AgentCardType.HANDOFF -> {
                AgentCardView(
                    c,
                    handoff =
                        tap(
                            AgentTapKind.HANDOFF,
                            "",
                            AgentJson.EMPTY,
                            RouteSource.IN_APP,
                            AgentDraftTexts.text("agent.draft.handoff.open"),
                        ),
                    lines = listOf(AgentJson.stringOf(d["summary"]).orEmpty()),
                )
            }

            AgentCardType.NOTICE -> {
                val text = DefaultTexts.text(AgentJson.stringOf(d["text_key"]).orEmpty()).orEmpty()
                val chips =
                    if (inert) {
                        emptyList()
                    } else {
                        AgentJson.arrayOf(d["actions"]).orEmpty().mapNotNull { AgentJson.objectOf(it) }.map {
                            AgentChipTap(
                                serial,
                                conversation,
                                AgentJson.stringOf(it["text"]).orEmpty(),
                                AgentJson.stringOf(it["send_text"]).orEmpty(),
                                cardEpoch,
                            )
                        }
                    }
                AgentCardView(c, chips = chips, lines = listOf(text.ifEmpty { fallbackPlain(c) }))
            }

            AgentCardType.CLAIM_DRAFT -> {
                // G21：只显示平台、订单号、点击时间；不画【提交找回】
                val times =
                    AgentJson.arrayOf(d["click_times"]).orEmpty().mapNotNull { AgentJson.stringOf(it) }.map {
                        AgentDraftTexts.text("agent.draft.claim.click_time") + " " + it.take(DATE_LEN) + " " +
                            it.substring(TIME_FROM, TIME_TO)
                    }
                AgentCardView(
                    c,
                    title = AgentDraftTexts.text("agent.draft.claim.title"),
                    lines =
                        listOf(
                            deps.platformName(AgentJson.stringOf(d["platform"]).orEmpty()),
                            AgentDraftTexts.text("agent.draft.claim.order_no") + " " + AgentJson.stringOf(d["order_no"]).orEmpty(),
                        ) + times,
                )
            }

            else -> {
                AgentCardView(c)
            }
        }
    }

    /** 订单状态卡：复用订单展示规则（鸿蒙 R1-14、R1-15）——状态文案按 OrderStatusText（未知 → 状态更新中，带变量的状态由订单草稿表回落无金额写法）；预计结算月份按 CreditPeriodText.of。 */
    private fun orderView(
        c: AgentCard,
        d: JsonObject,
        masked: Boolean,
        order: AgentTap?,
    ): AgentCardView {
        val status = AgentJson.stringOf(d["display_status"]).orEmpty()
        val labelKey = AgentJson.stringOf(OrderStatusText.of(status)["labelKey"]).orEmpty()
        val lines = mutableListOf(deps.platformName(AgentJson.stringOf(d["platform"]).orEmpty()), OrderDraftTexts.text(labelKey))
        AgentJson
            .stringOf(d["reason"])
            ?.let { OrderDraftTexts.text("order_reason.$it.title") }
            ?.takeIf(String::isNotEmpty)
            ?.let(lines::add)
        val credit = CreditPeriodText.of(status, AgentJson.stringOf(d["expected_credit_period"]), deps.clock())
        val creditKey = AgentJson.stringOf(credit["textKey"])
        val creditMonth = AgentJson.stringOf(credit["monthText"])
        if (creditKey != null && creditMonth != null) {
            OrderDraftTexts.format(creditKey, mapOf("credit_period" to creditMonth)).takeIf(String::isNotEmpty)?.let(lines::add)
        }
        val fen = AgentJson.intOf(d["est_rebate_fen"])
        return AgentCardView(
            c,
            order = order,
            title = if (AgentJson.boolOf(d["is_other_product"]) == true) "" else AgentJson.stringOf(d["title"]).orEmpty(),
            lines = lines,
            amountLabel = OrderDraftTexts.text("order_amount.est_rebate"),
            amountText = fen?.let { if (masked) AgentDraftTexts.MASK else CouliFormat.money(it) },
        )
    }

    /** 素材行（G22）：没有 BR-PRICE-10 的已有组件，只显示 title_hint 与 conditions 原样，不显示 claimed_* 金额与口令。 */
    private fun materialLines(d: JsonObject): Pair<String, List<String>> {
        val m = AgentJson.objectOf(d["material"]) ?: return "" to emptyList()
        val parts =
            listOfNotNull(AgentJson.stringOf(m["title_hint"])) +
                AgentJson.arrayOf(m["conditions"]).orEmpty().mapNotNull { AgentJson.stringOf(it) }
        return AgentDraftTexts.text("agent.draft.material.title") to
            (if (parts.isEmpty()) emptyList() else listOf(parts.joinToString(" · ")))
    }

    private fun productView(
        run: AgentRunTicket,
        cardId: String,
        p: JsonObject,
        inert: Boolean,
    ): AgentProductView {
        val itemId = AgentJson.stringOf(p["card_id"]).orEmpty()
        val platform = AgentJson.stringOf(p["platform"])
        val key = AgentJson.stringOf(p["product_key"])
        val availability = AgentJson.stringOf(p["availability"]) ?: AVAILABILITY_OK
        val couponGone = availability == AVAILABILITY_COUPON_GONE
        if (inert) return AgentProductView(p, null, null, null, couponGone)
        val detail =
            if (platform != null && key != null) {
                val params =
                    JsonObject(
                        buildMap {
                            put("platform", JsonPrimitive(platform))
                            put("product_key", JsonPrimitive(key))
                            AgentJson.stringOf(p["item_ref"])?.let { put("item_ref", JsonPrimitive(it)) }
                        },
                    )
                AgentTap(
                    run.controller,
                    run.conversation,
                    run.run,
                    cardId,
                    AgentTapKind.PRODUCT,
                    PRODUCT_DETAIL_ROUTE,
                    params,
                    RouteSource.IN_APP,
                    AgentJson.stringOf(p["title"]).orEmpty(),
                    epoch = cardEpoch,
                )
            } else {
                null
            }
        val buy =
            AgentJson
                .stringOf(
                    p["link_id"],
                )?.let { AgentBuyTap(run.controller, run.conversation, run.run, cardId, itemId, it, p, cardEpoch) }
        val refreshText = AgentDraftTexts.text("agent.draft.refresh_batch")
        val refresh =
            AgentChipTap(serial, conversation, refreshText, refreshText, cardEpoch).takeIf {
                availability == AVAILABILITY_OFF_SHELF ||
                    couponGone
            }
        return AgentProductView(p, detail, buy, refresh, couponGone)
    }

    private fun publish() {
        if (!alive()) return
        if (consentGate == AgentConsentGate.REFUSED && phase == AgentPhase.READY && active == null) {
            // 迟到的 AI 10004 只锁了闸门（共性第 16 条）：没有在途回复时显示说明与【重新同意】（不弹框）
            phase = AgentPhase.CONSENT_DECLINED
        }
        revision += 1
        // G02：隐藏期间快照照常算但不交给界面（只记有变化），回到可见时一次性应用
        if (!publishGate.offer()) return
        mutableSnapshot.value = buildSnapshot()
    }

    /** 金额隐藏偏好或闸门变化时的重绘入口（App 也可直接调）。 */
    fun refresh() = publish()

    companion object {
        const val MAX_INPUT = 500
        const val CONTEXT_TEXT_MAX = 2000
        const val PAGE_NAME = "AgentChat"
        private val SERIALS = AtomicLong(0)
        private const val DISCONNECTED_KEY = "agent.draft.disconnected"
        private const val DISCONNECTED_HINT_KEY = "agent.draft.disconnected_hint"
        private const val STOPPING_KEY = "agent.draft.stopping"
        private const val LIMITED_KEY = "agent.draft.limited"
        private const val INCOMPLETE_KEY = "agent.draft.incomplete"
        private const val EARLIER_FAILED_KEY = "agent.draft.history.failed"

        /** 举报半屏里被举报回复摘要的码点上限（截断加省略号，代拟）。 */
        private const val EXCERPT_MAX = 60
        private const val ELLIPSIS = "…"

        /** 被举报回复的摘要（受限渲染之后的纯文字，截断）。 */
        fun excerpt(text: String): String {
            val flat = text.replace('\n', ' ').trim()
            if (AgentJson.codePoints(flat) <= EXCERPT_MAX) return flat
            return flat.substring(0, flat.offsetByCodePoints(0, EXCERPT_MAX)) + ELLIPSIS
        }

        private const val TOO_LONG_KEY = "agent.draft.too_long"
        private const val SEARCH_KEY = "agent.draft.search"
        private const val AI_LABEL_KEY = "ai_label"
        private const val SEARCH_ROUTE = "Search"
        private const val LOGIN_ROUTE = "Login"
        private const val BIND_PHONE_ROUTE = "BindPhone"
        private const val AUTH_SHEET_ROUTE = "AuthSheet"
        private const val ORDER_DETAIL_ROUTE = "OrderDetail"
        private const val PRODUCT_DETAIL_ROUTE = "ProductDetail"
        private const val REASON_UNION_AUTH = "union_auth"
        private const val REASON_BIND_PHONE = "bind_phone"
        private const val AVAILABILITY_OK = "ok"
        private const val AVAILABILITY_OFF_SHELF = "off_shelf"
        private const val AVAILABILITY_COUPON_GONE = "coupon_gone"
        private const val CONTEXT_PRODUCT = "product_key"
        private const val CONTEXT_ORDER = "order_id"
        private const val CONTEXT_TEXT = "text"
        private const val PRODUCT_KEY_MIN = 3
        private const val PRODUCT_KEY_MAX = 128
        private const val DATE_LEN = 10
        private const val TIME_FROM = 11
        private const val TIME_TO = 16

        /** 统一错误文案：error.<code>（5xxxx 由 withTrace6 的同一规则独立追加 trace6，没有 trace_id 不追加；没收录的码回落 error.50001）。 */
        fun errorText(
            code: Int,
            traceId: String,
        ): String {
            val key = "error.$code"
            val raw = DefaultTexts.text(key) ?: DefaultTexts.text("error.${AgentErrorCodes.GENERIC}").orEmpty()
            val plain = DefaultTexts.fallback(key) ?: raw.replace("（{trace6}）", "").replace("{trace6}", "")
            val t6 = AgentSendErrorPolicy.trace6(traceId)
            return if (!AgentErrorCodes.server(code) || t6 == null || plain.contains(t6)) plain else "$plain（$t6）"
        }

        /** 流内终止帧是 error 且 code=10004（共性第 22 条）。 */
        private fun consentFrame(t: AgentTerminal?): Boolean =
            t != null && t.event == AgentEvent.ERROR && t.code == AgentErrorCodes.CONSENT.toLong()

        private fun sessionIdOf(session: JsonObject?): String? =
            session?.let {
                AgentJson.stringOf(it["id"])
                    ?: AgentJson.stringOf(it["session_id"])
            }

        /** fallback_text 的展示一律经受限渲染器（剥 URL / scheme / 口令，03 §7.2，Android R1-06）；纯文字。 */
        fun fallbackPlain(card: AgentCard): String = AgentText.render(card.fallbackText).joinToString("\n") { it.plain }

        private fun plain(state: AgentStreamState): String = AgentText.render(state.text).joinToString("\n") { it.plain }

        /** 读屏完整名称里的目标页（「去看看，提现记录」）：路由对应的页面名草稿（代拟，与页面标题一致）。 */
        private fun routeLabel(route: String): String = AgentDraftTexts.text("agent.draft.route.$route").ifEmpty { route }

        /** context 三选一（47eaf92f 的 AgentMessageContext 形状，G27）：{product_key 3–128} / {order_id 实体 id} / {text 1–2000}；不合就丢弃。 */
        fun validContext(raw: JsonObject?): JsonObject? {
            val (k, v) = raw?.entries?.singleOrNull() ?: return null
            val ok =
                (k == CONTEXT_PRODUCT && AgentJson.str(v, PRODUCT_KEY_MIN, PRODUCT_KEY_MAX)) ||
                    (k == CONTEXT_ORDER && AgentJson.entityId(v)) ||
                    (k == CONTEXT_TEXT && AgentJson.str(v, 1, CONTEXT_TEXT_MAX))
            return if (ok) JsonObject(mapOf(k to v)) else null
        }
    }
}

/** 统一前置路由（鸿蒙 H20：与钱包 SensitiveOutcome 的前置表同一张）：10001 → Login、10005 → BindPhone、10004 labor_agreement / id_verification。 */
object AgentPrereq {
    fun route(
        code: Int,
        data: JsonObject,
    ): String? =
        when (code) {
            AgentErrorCodes.LOGIN -> {
                "Login"
            }

            AgentErrorCodes.BIND_PHONE -> {
                "BindPhone"
            }

            AgentErrorCodes.CONSENT -> {
                when (AgentJson.stringOf(data["consent_type"])) {
                    "labor_agreement" -> "LaborAgreement"
                    "id_verification" -> "RealName"
                    else -> null
                }
            }

            else -> {
                null
            }
        }
}
