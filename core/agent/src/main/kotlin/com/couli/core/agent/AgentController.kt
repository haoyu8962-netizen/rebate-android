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
)

/** 控制器内部的一轮对话。 */
private class Turn(
    var run: AgentRunTicket,
    val userText: String,
    val body: String,
) {
    var state: AgentStreamState = StreamReducer.initial()
    var phase: String = AgentTurnPhase.CONNECTING
    var client: StreamClient<AgentRunTicket>? = null
    var error: AgentErrorView? = null

    /** 同一 client_msg_id 重发中：duplicate 流不并入原消息（G26）。 */
    var resend = false
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
) {
    val serial: Long = SERIALS.incrementAndGet()
    private val identity: AgentIdentity = deps.identity
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

    /** 页面此刻可见（Tab 选中、没被覆盖、在前台；App 在同步可见性出口写入，鸿蒙 R1-02）。 */
    private var visible = false

    /** 进入世代：每次离开（不可见）+1，之前发起的异步结果只结算、不改界面。 */
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

    init {
        phase =
            when {
                deps.contract == null -> AgentPhase.UNAVAILABLE
                identity.basic -> AgentPhase.BLOCKED
                else -> AgentPhase.IDLE
            }
        // 共享订阅（限流闸门、金额隐藏偏好）：作废时释放（APP-12 第 1 条：旧对话含收益金额不再可达）
        subscriptions +=
            deps.scope.launch {
                deps.gate.blocked
                    .drop(1)
                    .collect { publish() }
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

    /** 第一次显示：取当前会话（不拉历史，G02）。Release / 无接口为「未开放」，不发请求。 */
    fun start() {
        if (started || !alive() || !visible) return
        started = true
        if (phase == AgentPhase.UNAVAILABLE || phase == AgentPhase.BLOCKED) {
            publish()
            return
        }
        load()
    }

    private fun load() {
        val contract = deps.contract ?: return
        phase = AgentPhase.LOADING
        loadErrorText = ""
        publish()
        val conversation = this.conversation
        val entry = this.entry
        // 鸿蒙 R2-02：这次取会话只在仍处于本次加载时发出（离页退回 idle、回来重取的是新请求）
        val call = call { phase == AgentPhase.LOADING }
        deps.scope.launch {
            val r = contract.currentSession(call)
            // 先核对归属，再核对进入世代与可见性：离开后到达的结果只结算闸门与强更，不改界面（离开时已退回 idle，回来重取）
            if (!alive()) return@launch
            if (!current(entry) || conversation != this@AgentController.conversation) {
                settleOnly(r)
                return@launch
            }
            if (r.ok) {
                // 「成功但为空」（session=null）与「加载失败」分开（APP-11 第 6 条）：空会话等第一次发送时再建
                sessionId = sessionIdOf(AgentJson.objectOf(r.data["session"]))
                phase = AgentPhase.READY
                publish()
                return@launch
            }
            loadFailed(r)
        }
    }

    private fun loadFailed(r: AgentHttpResult) {
        when (sharedError(r, null)) {
            AgentSharedError.UNAVAILABLE -> {
                phase = AgentPhase.UNAVAILABLE
            }

            AgentSharedError.CONSENT -> {
                phase = AgentPhase.READY
            }

            else -> {
                phase = AgentPhase.LOAD_FAILED
                loadErrorText = errorText(r.codeOrGeneric, r.traceId)
            }
        }
        publish()
    }

    // ---------------------------------------------------------------- 四个待定 operation 共用的错误规则（APP-10 共性第 6、18 条）

    /**
     * createAgentSession、getCurrentAgentSession、sendAgentMessage、cancelAgentRun 同一套（调用方已核对归属与进入世代）：42901 → 共享闸门
     * （Retry-After 缺省 5 秒）；10405 → 强更；10004(ai_third_party) → 同意弹窗（[pendingRun] 为待重放的 run）；10001 / 10005 / 其他 10004 → 统一分流；
     * 30501 → 未开放（由调用方切换）；30505 → 静默；本地没发出 → 静默；其余交调用方。
     */
    @Suppress("ReturnCount") // 没发出、按码、同意、统一分流依次返回
    private fun sharedError(
        r: AgentHttpResult,
        pendingRun: String?,
    ): String {
        if (r.notSent) return AgentSharedError.SILENT
        when (r.code) {
            AgentErrorCodes.RATE_LIMITED -> {
                deps.gate.start(AgentSendErrorPolicy.retryAfterMs(r.retryAfter))
                return AgentSharedError.RATE_LIMITED
            }

            AgentErrorCodes.FORCE_UPDATE -> {
                deps.forceUpdate()
                return AgentSharedError.FORCE_UPDATE
            }

            AgentErrorCodes.UNAVAILABLE -> {
                return AgentSharedError.UNAVAILABLE
            }

            AgentErrorCodes.RUN_MISSING -> {
                return AgentSharedError.SILENT
            }
        }
        if (AgentConsentPolicy.shows(r.code, r.consentType)) {
            consentLost(pendingRun, interactive = true)
            return AgentSharedError.CONSENT
        }
        if (r.code == AgentErrorCodes.LOGIN || r.code == AgentErrorCodes.CONSENT || r.code == AgentErrorCodes.BIND_PHONE) {
            unified(r)
            return AgentSharedError.UNIFIED
        }
        return AgentSharedError.OTHER
    }

    /**
     * 迟到的结果（离页后、旧对话、旧 run、已被新请求取代的 operation；调用方已核对归属仍是同一身份）：只结算同一身份的共享事实——42901 闸门、10405 强更、
     * AI 10004（立即锁住发送闸门，但不弹框，鸿蒙 R2-01、H27）；不弹层、不导航、不改消息（鸿蒙 R2-03、R2-04；APP-12 第 34 条：结算前已核对身份）。
     */
    private fun settleOnly(r: AgentHttpResult) {
        if (r.notSent) return
        when {
            r.code == AgentErrorCodes.RATE_LIMITED -> {
                deps.gate.start(AgentSendErrorPolicy.retryAfterMs(r.retryAfter))
                publish()
            }

            r.code == AgentErrorCodes.FORCE_UPDATE -> {
                deps.forceUpdate()
            }

            AgentConsentPolicy.shows(r.code, r.consentType) -> {
                consentLost(null, interactive = false)
            }
        }
    }

    /**
     * 收到 AI 10004（APP-10 共性第 16、22 条，S0）：不论来自发送、同意后的重放、新对话、停止还是取会话，都立即撤销发送许可。
     * - [interactive]（发起时的进入世代、对话与 operation 票据都仍有效）：弹同意框（[pendingRun] 为待重放的 run；重放过一次的不再给）。
     * - 否则（迟到结果）：只锁闸门、不弹框（refused，回到页面显示说明与【重新同意】，鸿蒙 H27）。
     * 提交中收到的 10004 来自提交之前发出的请求（闸门非 clear 时不发包）：不改提交中的状态，由记录结果决定（鸿蒙 H28）。已在待同意时不重复开框。
     */
    @Suppress("ReturnCount") // 提交中、待同意、弹框、只锁闸门依次处理
    private fun consentLost(
        pendingRun: String?,
        interactive: Boolean,
    ) {
        if (consentGate == AgentConsentGate.SUBMITTING) return
        if (consentGate == AgentConsentGate.AWAITING && consent != null) return
        if (interactive) {
            openConsent(pendingRun)
            return
        }
        consent = null
        consentGate = AgentConsentGate.REFUSED
        consentFailed = false
        if (phase == AgentPhase.IDLE || phase == AgentPhase.LOADING || phase == AgentPhase.LOAD_FAILED) {
            // 离页时退回 idle 的加载：不再自动重取（闸门已锁，取会话也不会发出），回来显示【重新同意】
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

    // ---------------------------------------------------------------- 可见性（APP-10 共性第 2、17 条）

    /**
     * 同步可见性出口（App 在切 Tab、被覆盖、前后台变化时同步调用）。离开：进入世代 +1，关闭当前连接、取消计时（本条消息记为连接中断、给同键【重试】），
     * 加载中退回 idle（回来重取）；之后到达的建会话、取会话、停止、同意结果只结算、不改界面。草稿与滚动位置由页面在内存里保留（鸿蒙 H17）。
     */
    fun setVisible(visible: Boolean) {
        if (disposed || visible == this.visible) return
        this.visible = visible
        if (visible) {
            publish()
            return
        }
        entry += 1
        active?.let { a ->
            a.client?.cancel()
            a.client = null
            a.resend = false
            a.state = StreamReducer.disconnected(a.state)
            a.error = AgentErrorView(AgentDraftTexts.text(DISCONNECTED_KEY), retry = AgentRetryTap(a.run, AgentRetryMode.SAME))
            a.phase = AgentTurnPhase.ENDED
            active = null
        }
        if (phase == AgentPhase.LOADING) {
            phase = AgentPhase.IDLE
            started = false
        }
        publish()
    }

    /** 发起时的进入世代仍有效且页面可见。 */
    private fun current(entry: Long): Boolean = entry == this.entry && visible

    /** 加载失败的【重试】（受 42901 闸门控制）。 */
    fun retryLoad(t: AgentPageTicket) {
        if (!pageOk(t) || phase != AgentPhase.LOAD_FAILED || deps.gate.active) return
        load()
    }

    // ---------------------------------------------------------------- 票据比较

    private fun pageOk(t: AgentPageTicket): Boolean = alive() && t.controller == serial && t.conversation == conversation

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
     * 实际发包出口的闸门（APP-10 共性第 1、17 条）：创建请求时固定「进入世代 + 对话世代 + 本 operation 的票据（[op]）」，sendAllowed 只比较这些固定值与
     * 此刻的控制器、42901 闸门、AI 同意闸门、可见性；任何一次核对不通过就永久失效（离页再回来、新对话、停止、同意被撤销后都不会恢复）。
     */
    private fun call(op: () -> Boolean): AgentCall {
        val entry = this.entry
        val conversation = this.conversation
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
                            alive() && entry == this.entry && conversation == this.conversation && visible && !deps.gate.active &&
                                consentGate == AgentConsentGate.CLEAR && op()
                        }.getOrDefault(false)
                    if (!ok) revoked = true
                    ok
                }
            },
            owner = { alive() },
        )
    }

    // ---------------------------------------------------------------- 发送

    /** 输入框是否可发：就绪、不在发送中、不在禁用期、额度没用完、AI 同意已记录、页面可见。 */
    val canSend: Boolean
        get() =
            alive() && visible && phase == AgentPhase.READY && active == null && !deps.gate.active && !quotaExhausted &&
                consentGate == AgentConsentGate.CLEAR

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
        if (deps.gate.active) {
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
        val turn = Turn(run, text, body.toString())
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

    /** 发出（必要时先建会话）；每次异步等待结束后复核控制器、对话世代、run 票据（APP-04 第 30、60 条）。 */
    @Suppress("CyclomaticComplexMethod", "ReturnCount") // 闸门、已有会话、建会话依次处理
    private fun dispatch(turn: Turn) {
        val run = turn.run
        val contract = deps.contract ?: return
        if (consentGate != AgentConsentGate.CLEAR || !visible) {
            // 实际发送出口（APP-10 共性第 1、2 条）：同意未记录成功或页面不可见时一律不发；这一轮不留在列表里
            removeTurn(turn)
            publish()
            return
        }
        sessionId?.let {
            stream(turn, it)
            return
        }
        val entry = this.entry
        // 只为这一轮、这一个 run 建会话（停止、新对话、重试换 run、离页后都永久失效，鸿蒙 R2-02）
        val call = call { active === turn && turn.run == run && sessionId == null }
        deps.scope.launch {
            val r = contract.createSession(call)
            // 先核对固定身份；旧 run / 离页后到达的结果只结算共享事实（42901、10405、AI 10004），再按票据决定是否改界面（共性第 18 条）
            if (!alive()) return@launch
            if (!runOk(run) || active !== turn || !current(entry)) {
                settleOnly(r)
                return@launch
            }
            val id = if (r.ok) sessionIdOf(AgentJson.objectOf(r.data["session"]) ?: r.data) else null
            if (id == null) {
                jsonFailed(turn, if (r.ok) AgentHttpResult.network() else r)
                return@launch
            }
            sessionId = id
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
        turn.client = client
        val run = turn.run
        // 许可绑定这一轮、这个 run、这一个流客户端（停止、断线收尾、重试换 run 之后都永久失效）
        client.start(sessionId, turn.body, call { active === turn && turn.run == run && turn.client === client })
    }

    /** 流事件：先比票据（控制器存活、对话世代、run 是当前活动 run），不等即丢弃，什么都不改。 */
    @Suppress("ReturnCount", "CyclomaticComplexMethod", "NestedBlockDepth", "LongMethod", "ComplexCondition") // 票据、各事件依次处理
    private fun onStream(
        ticket: AgentRunTicket,
        event: AgentStreamEvent,
    ) {
        if (!runOk(ticket) || !visible) {
            if (alive()) {
                // 同一身份的旧 run 迟到的 JSON 错误只结算共享事实（42901、10405、AI 10004），不改界面（共性第 18 条）
                if (event.kind == AgentStreamEventKind.JSON) event.result?.let(::settleOnly)
                // 旧 run 迟到的流内 error 10004 同样是同一身份的共享事实：只锁闸门、不弹框、不改界面（共性第 22 条）
                val frame = event.frame
                if (event.kind == AgentStreamEventKind.FRAME && frame != null && frame.ok &&
                    consentFrame(AgentTerminal(frame.event, frame.data))
                ) {
                    consentLost(null, interactive = false)
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
                    turn.state = StreamReducer.frame(turn.state, frame, deps.routes)
                }
                if (turn.state.metaSeen) turn.phase = AgentTurnPhase.STREAMING
                afterFrame(turn)
            }

            AgentStreamEventKind.EOF, AgentStreamEventKind.TIMEOUT -> {
                turn.resend = false
                turn.state = StreamReducer.disconnected(turn.state)
                finishTurn(turn)
            }

            AgentStreamEventKind.LIMIT -> {
                // 单帧 data 超过 64 KiB（03 §7.2，Android R1-04）：StreamClient 已关连接；已并入的内容保留，本地结束这一轮（不更新额度）
                turn.resend = false
                turn.state = StreamReducer.limited(turn.state)
                finishTurn(turn)
            }

            AgentStreamEventKind.JSON -> {
                jsonFailed(turn, event.result ?: AgentHttpResult.network())
            }

            AgentStreamEventKind.FAILED -> {
                if (turn.state.metaSeen) {
                    turn.state = StreamReducer.disconnected(turn.state)
                    finishTurn(turn)
                } else {
                    jsonFailed(turn, event.result ?: AgentHttpResult.network())
                }
            }
        }
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
     * duplicate=true 的流（G26）：不并入、不更新额度。done → 本地消息结束（保留已收到的内容）；error 30506 → 显示 error.30506 并给同键【重试】；
     * error 10004 → 与普通流同一处理（共性第 22 条）；其他 error → 按流内 error。
     */
    private fun endDuplicate(turn: Turn) {
        val dup = turn.resendState
        turn.resend = false
        val terminal = dup.terminal
        if (consentFrame(terminal)) {
            streamConsentLost(turn, terminal!!)
            return
        }
        when {
            terminal?.event == AgentEvent.DONE -> {
                turn.state = turn.state.copy(status = AgentStatus.DONE)
                turn.error = null
            }

            terminal?.code == AgentErrorCodes.RUN_IN_PROGRESS.toLong() -> {
                turn.state = turn.state.copy(status = AgentStatus.ERROR)
                turn.error =
                    AgentErrorView(
                        errorText(AgentErrorCodes.RUN_IN_PROGRESS, turn.traceId),
                        retry = AgentRetryTap(turn.run, AgentRetryMode.SAME),
                    )
            }

            else -> {
                turn.state = turn.state.copy(status = AgentStatus.ERROR)
                turn.error = terminal?.let { streamErrorView(turn, it.data) }
            }
        }
        closeActive(turn)
    }

    private fun finishTurn(turn: Turn) {
        val s = turn.state
        val terminal = s.terminal
        if (consentFrame(terminal)) {
            // 共性第 22 条（S0）：普通流与 duplicate 流（meta.duplicate=true 直接下发）的 error 10004 不按普通错误收尾
            streamConsentLost(turn, terminal!!)
            return
        }
        turn.error =
            when {
                s.duplicate && terminal?.event == AgentEvent.ERROR && terminal.code == AgentErrorCodes.RUN_IN_PROGRESS.toLong() -> {
                    // duplicate 流收到 30506（G26）：结束 loading，显示 error.30506 并给同键【重试】，不自动轮询
                    AgentErrorView(
                        errorText(AgentErrorCodes.RUN_IN_PROGRESS, turn.traceId),
                        retry = AgentRetryTap(turn.run, AgentRetryMode.SAME),
                    )
                }

                s.status == AgentStatus.DISCONNECTED -> {
                    AgentErrorView(AgentDraftTexts.text(DISCONNECTED_KEY), retry = AgentRetryTap(turn.run, AgentRetryMode.SAME))
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

    /**
     * 流内 error 帧 code=10004（共性第 22 条，S0；普通流、duplicate 流、重试后的 duplicate 收尾同一出口）：同步执行禁发——停止本对话的所有流与计时、
     * 作废卡片回调和购买执行（cardEpoch +1）、对话世代 +1（已发出的 sendAllowed、停止许可、卡片 / 快捷追问 / 【重试】票据一律失效）、清空同意被撤销后
     * 不可展示的对话，再把闸门锁成 refused。是否弹框另判：流契约的 error 帧没有 consent_type（不新增契约字段），不成立 → 不弹框，页面显示「暂不使用」
     * 同款说明与【重新同意】（鸿蒙 H27）；不弹框也必须禁发。
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
        consentLost(null, interactive = AgentConsentPolicy.shows(AgentErrorCodes.CONSENT, consentType) && visible)
        if (consentGate == AgentConsentGate.CLEAR) consentGate = AgentConsentGate.REFUSED
        publish()
    }

    private fun closeActive(turn: Turn) {
        turn.phase = AgentTurnPhase.ENDED
        turn.client?.cancel()
        turn.client = null
        if (active === turn) active = null
        announcement = plain(turn.state)
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

    /** 发送返回 JSON 错误（G24）；42901、10405、10004、统一分流与其他三个 operation 共用 [sharedError]（共性第 6 条）。 */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount") // 按 G24 的动作表逐条
    private fun jsonFailed(
        turn: Turn,
        r: AgentHttpResult,
    ) {
        turn.client?.cancel()
        turn.client = null
        val code = r.codeOrGeneric
        val d = AgentSendErrorPolicy.decide(r.httpStatus, code, r.data, r.retryAfter, r.traceId, r.consentType)
        turn.phase = AgentTurnPhase.FAILED
        when (d.action) {
            AgentSendAction.UNAVAILABLE -> {
                phase = AgentPhase.UNAVAILABLE
                removeTurn(turn)
            }

            AgentSendAction.QUOTA -> {
                quotaExhausted = true
                turn.error = quotaView(turn)
            }

            AgentSendAction.NEW_CHAT -> {
                turn.error =
                    AgentErrorView(errorText(code, r.traceId), newChat = AgentPageTicket(serial, conversation))
            }

            AgentSendAction.RUN_IN_PROGRESS -> {
                deps.gate.start(d.disableMs)
                deps.toast(errorText(code, r.traceId))
                removeTurn(turn)
            }

            AgentSendAction.RATE_LIMITED -> {
                sharedError(r, null)
                deps.toast(errorText(code, r.traceId))
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
                } else if (turn.consentReplays >= 1) {
                    // 同意后重放只一次；再遇 10004 不再自动重放（按统一错误显示），但必须立即重新锁住闸门并再弹同意框（共性第 16 条，S0：
                    // 重放次数只限制自动重放，不跳过同意状态的更新；这一次的弹窗没有待重放消息）
                    turn.error = AgentErrorView(errorText(AgentErrorCodes.CONSENT, r.traceId))
                    if (active === turn) active = null
                    consentLost(null, interactive = true)
                } else {
                    turn.phase = AgentTurnPhase.AWAITING_CONSENT
                    if (active === turn) active = null
                    sharedError(r, turn.run.run)
                    return
                }
            }

            AgentSendAction.FORCE_UPDATE -> {
                sharedError(r, null)
                removeTurn(turn)
            }

            AgentSendAction.TEXT_TOO_LONG -> {
                deps.toast(AgentDraftTexts.text(TOO_LONG_KEY))
                removeTurn(turn)
            }

            else -> {
                // 统一分流（10001 / 10005 / 非 AI 的 10004 / 表外码）：交统一入口（打开前置页或提示）；本条显示统一文案（5xxxx 带 trace6）
                sharedError(r, null)
                turn.error = AgentErrorView(errorText(code, r.traceId))
            }
        }
        if (active === turn) active = null
        publish()
    }

    /**
     * 30502 额度用完（AgentQuotaGuest / AgentQuotaPhone，G24）：游客 →【登录】；会员未绑手机（或未知，按未验证）→【绑定手机号】；已绑手机的会员不再引导绑定
     * （BR-AI-15 none 分支，鸿蒙 H10 / H23），只给【去搜索】与「明天再来」的说明。
     */
    private fun quotaView(turn: Turn): AgentErrorView {
        val run = turn.run
        val search =
            AgentTap(
                run.controller,
                run.conversation,
                run.run,
                "",
                AgentTapKind.SEARCH,
                SEARCH_ROUTE,
                AgentJson.EMPTY,
                RouteSource.IN_APP,
                AgentDraftTexts.text(SEARCH_KEY),
            )
        val bound = if (identity.member) runCatching { deps.phoneBound() }.getOrNull() else null
        val (upgrade, hint) =
            when {
                !identity.member -> {
                    AgentTap(
                        run.controller,
                        run.conversation,
                        run.run,
                        "",
                        AgentTapKind.LOGIN,
                        LOGIN_ROUTE,
                        AgentJson.EMPTY,
                        RouteSource.IN_APP,
                        AgentDraftTexts.text("agent.draft.login"),
                    ) to
                        AgentDraftTexts.text("agent.draft.quota.guest_hint")
                }

                bound != true -> {
                    AgentTap(
                        run.controller,
                        run.conversation,
                        run.run,
                        "",
                        AgentTapKind.BIND_PHONE,
                        BIND_PHONE_ROUTE,
                        AgentJson.EMPTY,
                        RouteSource.IN_APP,
                        AgentDraftTexts.text("agent.draft.bind_phone"),
                    ) to AgentDraftTexts.text("agent.draft.quota.phone_hint")
                }

                else -> {
                    null to AgentDraftTexts.text("agent.draft.quota.member_hint")
                }
            }
        return AgentErrorView(errorText(AgentErrorCodes.QUOTA, ""), search = search, upgrade = upgrade, quotaHint = hint)
    }

    private fun removeTurn(turn: Turn) {
        turns = turns.filter { it !== turn }
        if (active === turn) active = null
    }

    // ---------------------------------------------------------------- 停止、新对话、重试

    /** 【停止】：本地立即 cancelled，再调 cancelAgentRun（带原身份；发包前复核身份未变，30505 静默）。 */
    @Suppress("ReturnCount") // 票据、没有活动 run、不发 cancel 依次返回
    fun stop(t: AgentRunTicket) {
        if (!runOk(t)) return
        val turn = active ?: return
        val action = AgentTicketPolicy.action(AgentTicketEvent.STOP)
        turn.client?.cancel()
        turn.client = null
        turn.state = StreamReducer.localCancel(turn.state)
        turn.resend = false
        turn.error = null
        turn.phase = AgentTurnPhase.ENDED
        active = null
        // 共性第 3 条（brief §0.3）：停止同步作废这一对话已渲染的卡片回调与在途购买尝试；这一轮的卡片照常显示、按钮不再可用
        revokeCardActions()
        turn.revoked = true
        publish()
        val runId = turn.state.runId
        val contract = deps.contract
        if (!action.sendsCancel || runId == null || contract == null) return
        // 停止请求固定原对话与 operation 票据（停止时的 run 序号）：之后有新对话、新消息、重试或离页，结果只结算共享事实，不弹框、不导航、不改新对话
        // （共性第 19 条）。发包许可固定进入世代与原对话（新对话、离页后不再发 cancel，与 G06 一致）；同一对话里接着发新消息不挡停止
        val entry = this.entry
        val conversation = this.conversation
        val seq = runSeq
        val call = call { true }
        deps.scope.launch {
            val r = contract.cancelRun(runId, call)
            afterOp(
                r,
                { current(entry) && conversation == this@AgentController.conversation && seq == runSeq && active == null },
                OP_CANCEL,
            )
        }
    }

    /** 卡片动作世代 +1、作废购买执行器的在途尝试（只挡外跳；之后的响应带旧世代，不再更新卡片）。 */
    private fun revokeCardActions() {
        cardEpoch += 1
        runCatching { deps.cancelPurchase() }
    }

    /**
     * 建会话（新对话）与停止的结果（共性第 6、18 条）：先核对归属；成功不处理；票据失效只结算共享事实；仍有效时 30505、本地没发出静默，其余按 [sharedError]，
     * 剩下的码提示统一文案（5xxxx 带 trace6），不直接吞掉。
     */
    private fun afterOp(
        r: AgentHttpResult,
        uiOk: () -> Boolean,
        op: String,
    ) {
        if (!alive() || r.ok) return
        if (!uiOk()) {
            settleOnly(r)
            return
        }
        when (sharedError(r, null)) {
            AgentSharedError.UNAVAILABLE -> {
                phase = AgentPhase.UNAVAILABLE
                publish()
            }

            AgentSharedError.OTHER -> {
                if (r.code < 0) deps.log("agent $op failed") else deps.toast(errorText(r.code, r.traceId))
            }

            AgentSharedError.RATE_LIMITED -> {
                publish()
            }
        }
    }

    /** 【新对话】：作废当前对话与所有卡片回调（在途 run 本地结束、不发 cancel），createAgentSession。 */
    @Suppress("ReturnCount") // 票据、没有端口、未就绪依次返回
    fun newChat(t: AgentPageTicket) {
        if (!pageOk(t)) return
        dropConversation()
        quotaExhausted = false
        publish()
        val contract = deps.contract ?: return
        if (phase != AgentPhase.READY) return
        val conversation = this.conversation
        val entry = this.entry
        val seq = runSeq
        // 只为这一次新对话建会话（再点新对话、离页、已有会话后永久失效）
        val call = call { sessionId == null }
        deps.scope.launch {
            val r = contract.createSession(call)
            // 先核对固定身份，再结算共享事实；对话、进入世代、之后是否又发起了 run 决定能不能改界面（共性第 18 条）
            if (!alive()) return@launch
            val own = conversation == this@AgentController.conversation && sessionId == null
            if (r.ok) {
                val id = sessionIdOf(AgentJson.objectOf(r.data["session"]) ?: r.data)
                if (own && current(entry) && id != null) sessionId = id
                return@launch
            }
            afterOp(r, { own && current(entry) && seq == runSeq && active == null }, OP_SESSION)
        }
    }

    /** 同步作废当前对话（新对话、流内 10004）：关连接、停计时、清消息与卡片回调；对话世代 +1。同意闸门不随新对话解除（没记录成功就一直禁发）。 */
    private fun dropConversation() {
        turns.forEach { turn ->
            turn.client?.cancel()
            turn.client = null
        }
        active = null
        turns = emptyList()
        conversation += 1
        sessionId = null
        revokeCardActions()
        announcement = ""
    }

    /** 【重试】：沿用同一 client_msg_id（50401、断线、duplicate 30506）或新消息（流内 error）。 */
    @Suppress("ReturnCount", "ComplexCondition") // 票据、限流、两种模式
    fun retry(t: AgentRetryTap) {
        val turn = turnOf(t.controller, t.conversation, t.run) ?: return
        if (active != null || turn.error?.retry == null || turn.run.clientMsgId != t.clientMsgId || consentGate != AgentConsentGate.CLEAR ||
            !visible
        ) {
            return
        }
        if (deps.gate.active) {
            deps.toast(errorText(AgentErrorCodes.RATE_LIMITED, ""))
            return
        }
        if (t.mode == AgentRetryMode.NEW) {
            sendText(turn.userText)
            return
        }
        turn.run = newRun(ClientMsgId.forRetry(ClientMsgId.RETRY_DISCONNECTED, turn.run.clientMsgId))
        turn.error = null
        turn.phase = AgentTurnPhase.CONNECTING
        // 已收到的部分内容保留（不补拉历史，G02 ②）；回来的若是 duplicate 两帧，按 G26 收尾，不并入
        turn.resend = turn.state.metaSeen
        turn.resendState = StreamReducer.initial()
        if (!turn.resend) turn.state = StreamReducer.initial()
        active = turn
        publish()
        dispatch(turn)
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
        if (r.code == AgentErrorCodes.RATE_LIMITED) deps.gate.start(AgentSendErrorPolicy.retryAfterMs(r.retryAfter))
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

    /** 同步作废（归属变化、页面销毁）：关闭连接、取消计时、作废全部 run 票据与卡片回调、清空消息与金额、释放订阅；不发 cancel（G06）。 */
    fun dispose() {
        if (disposed) return
        disposed = true
        turns.forEach { turn ->
            turn.client?.cancel()
            turn.client = null
        }
        turns = emptyList()
        active = null
        consent = null
        submitting = null
        context = null
        quotaLeft = null
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
        return AgentSnapshot(
            owner = serial,
            conversation = conversation,
            phase = phase,
            turns = turns.map { turnView(it, masked) },
            sending = active != null,
            stop = active?.run,
            limited = deps.gate.active,
            quotaExhausted = quotaExhausted,
            quotaLeft = quotaLeft,
            consent = consent,
            consentGate = consentGate,
            consentFailed = consentFailed,
            page = page,
            examples = deps.examples().map { AgentChipTap(serial, conversation, it, it) },
            ordersChip = AgentChipTap(serial, conversation, orders, orders),
            filingText = deps.filingText(),
            contextLabelKey = contextLabel(),
            loadErrorText = loadErrorText,
            revision = revision,
            announcement = announcement,
            amountMasked = masked,
        )
    }

    private fun turnView(
        t: Turn,
        masked: Boolean,
    ): AgentTurnView {
        val state = t.state
        val ended = t.phase == AgentTurnPhase.ENDED
        val chips = state.suggestions.map { AgentChipTap(serial, conversation, it.text, it.sendText) }
        // 每条已结束的 assistant 回复都有 AiLabel（done、error、停止、断线收尾都算，03 §7.2；共性第 8 条）：meta.ai_label，缺则字典 ai_label
        val label = if (ended && state.metaSeen) state.aiLabel ?: DefaultTexts.text(AI_LABEL_KEY) else null
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
            streaming = t === active,
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
        private const val OP_CANCEL = "cancel"
        private const val OP_SESSION = "session"
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
