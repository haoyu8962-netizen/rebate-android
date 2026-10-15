package com.couli.core.agent

import com.couli.core.generated.ApiMinVersionGate
import com.couli.core.generated.ApiOperations
import com.couli.core.wallet.WalletRateGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// APP-10b §2 第 10 条（F-X-04）：7 个 agent operation 一张错误表（扩展 APP-10 G24 / H21）与三组 Retry-After 闸门（G24，APP-12 第 5、22 条）。三端同名。
// - 共用：10001 / 10005 / 非 AI 的 10004 → 统一前置路由（APP-10 H20）；AI 10004 → 同步锁同意闸门（7 个 operation 都锁；只有发送类——发送、建会话、
//   取当前会话——在页面可见且票据仍有效时才弹同意框，发送才在同意后重放原消息；历史 / 反馈 / 举报 / cancel 不弹）；10405 → 强更；30501 → 「未开放」且
//   之后不再发 agent 请求；5xxxx → 统一文案 + trace6；无外壳 → 网络失败。cancel 单列（R2-07）：一律不导航、不弹框，公共码只记本机日志。
// - 三组闸门（各自 Retry-After，缺省 / 非法 / 非正 5 秒，按会话归属，归属变化即失效）：发送（sendAgentMessage、createAgentSession）、读取
//   （getCurrentAgentSession、listAgentSessionMessages）、动作（setAgentMessageFeedback、reportAgentMessage、cancelAgentRun）。页面置灰与执行层
//   （AgentCall.sendAllowed → CallOwnership.sendAllowed，每次实际写出前）两处生效。

object AgentOperation {
    const val CREATE = "createAgentSession"
    const val CURRENT = "getCurrentAgentSession"
    const val HISTORY = "listAgentSessionMessages"
    const val SEND = "sendAgentMessage"
    const val CANCEL = "cancelAgentRun"
    const val FEEDBACK = "setAgentMessageFeedback"
    const val REPORT = "reportAgentMessage"
    val ALL: List<String> = listOf(CREATE, CURRENT, HISTORY, SEND, CANCEL, FEEDBACK, REPORT)
}

object AgentGateGroup {
    const val SEND = "send"
    const val READ = "read"
    const val ACTION = "action"

    fun of(operationId: String): String =
        when (operationId) {
            AgentOperation.SEND, AgentOperation.CREATE -> SEND
            AgentOperation.CURRENT, AgentOperation.HISTORY -> READ
            else -> ACTION
        }
}

/** 一组 Retry-After 闸门的最小接口（[AgentRateGate] 与 APP-10 的共享闸门 [WalletRateGate] 两种实现）。 */
interface AgentGate {
    val active: Boolean
    val blocked: StateFlow<Boolean>

    fun start(millis: Long)

    fun remainingMillis(): Long
}

/** APP-10 的共享闸门（四个 operation 共用一份）当作一组用（控制器没有拿到三组闸门时的退路，G55、APP-10 H21）。 */
class AgentSharedGate(
    private val gate: WalletRateGate,
) : AgentGate {
    override val active: Boolean get() = gate.active
    override val blocked: StateFlow<Boolean> get() = gate.blocked

    override fun start(millis: Long) = gate.start(millis)

    override fun remainingMillis(): Long = gate.remainingMillis()
}

/**
 * 按会话归属的 Retry-After 闸门（G24、G55、鸿蒙 R3-03）：写入时记下此刻的归属（[owner]，不含页面实例）；判定时只有归属仍是写入时那一个、且没到截止时刻
 * 才算禁用（换号之后旧闸门不生效）。[timers] 为 null 时不起计时器（向量接线的虚拟时钟），[blocked] 只在 [start] / [reset] 时更新。只在主线程使用。
 */
class AgentRateGate(
    private val clock: () -> Long,
    private val owner: () -> Any?,
    private val timers: CoroutineScope? = null,
) : AgentGate {
    private var until = 0L
    private var startedOwner: Any? = null
    private var job: Job? = null
    private val mutable = MutableStateFlow(false)

    override val blocked: StateFlow<Boolean> = mutable.asStateFlow()

    override val active: Boolean
        get() = clock() < until && runCatching { owner() }.getOrNull() == startedOwner

    override fun remainingMillis(): Long = if (active) (until - clock()).coerceAtLeast(0L) else 0L

    /** 开始禁用（不缩短同一归属已有的截止时刻；归属变了从头算）。 */
    override fun start(millis: Long) {
        if (millis <= 0) return
        val now = runCatching { owner() }.getOrNull()
        until = if (now == startedOwner) maxOf(until, clock() + millis) else clock() + millis
        startedOwner = now
        mutable.value = true
        val scope = timers ?: return
        job?.cancel()
        job =
            scope.launch {
                while (clock() < until) delay((until - clock()).coerceAtLeast(1L))
                mutable.value = false
            }
    }

    fun reset() {
        job?.cancel()
        job = null
        until = 0L
        startedOwner = null
        mutable.value = false
    }
}

/** 三组 Retry-After 闸门（按会话归属；页面与执行层共用）。 */
class AgentGates(
    val send: AgentGate,
    val read: AgentGate,
    val action: AgentGate,
) {
    /** 上一次保留时的会话归属（不含页面实例；null = 还没绑定）。 */
    private var retainedOwner: Any? = null
    private var bound = false

    fun of(operationId: String): AgentGate =
        when (AgentGateGroup.of(operationId)) {
            AgentGateGroup.SEND -> send
            AgentGateGroup.READ -> read
            else -> action
        }

    /** 执行层：这个 operation 此刻能不能写出。 */
    fun allowed(operationId: String): Boolean = !of(operationId).active

    fun reset() {
        listOf(send, read, action).forEach { (it as? AgentRateGate)?.reset() }
    }

    /**
     * 控制器作废 / 页面实例重建时调用（鸿蒙 R3-03，G55）：三组闸门按不含页面实例的会话归属保存——归属没变就保留截止时间（同归属重建后剩余时间照旧、
     * 请求照样不发），归属真正改变才清理（换回原归属也不复活）。返回是否清理了。
     */
    @Suppress("ReturnCount") // 逐项提前返回
    fun retain(ownerKey: Any): Boolean {
        if (bound && retainedOwner == ownerKey) return false
        val first = !bound
        bound = true
        retainedOwner = ownerKey
        if (first) return false
        reset()
        return true
    }

    companion object {
        fun create(
            clock: () -> Long,
            owner: () -> Any?,
            timers: CoroutineScope? = null,
        ): AgentGates =
            AgentGates(AgentRateGate(clock, owner, timers), AgentRateGate(clock, owner, timers), AgentRateGate(clock, owner, timers))

        /** APP-10 的共享闸门当三组用（测试接线与没有三组闸门的旧接线）。 */
        fun shared(gate: WalletRateGate): AgentGates {
            val g = AgentSharedGate(gate)
            return AgentGates(g, g, g)
        }
    }
}

/** 一个 agent operation 失败后的页面与共享动作（不含任何正文）。 */
@Suppress("LongParameterList") // 动作表逐项
class AgentOpDecision {
    var action: String = UNIFIED_ERROR
    var gateGroup: String = AgentGateGroup.ACTION
    var disableMs: Long = 0
    var lockAiGate: Boolean = false
    var showConsent: Boolean = false
    var replayAfterConsent: String = REPLAY_NONE
    var route: String? = null
    var clearSessionId: Boolean = false
    var rollback: Boolean = false
    var closeSheet: Boolean = false
    var keepSheet: Boolean = false
    var reasonInlineError: Boolean = false
    var reuseClientMsgId: Boolean = false
    var showError: Boolean = true
    var trace6: String? = null

    /** 统一提示文字（error.<code>，5xxxx 带 trace6；没有为空串）。 */
    var text: String = ""

    override fun toString(): String = "AgentOpDecision($action)"

    companion object {
        const val UNIFIED_ERROR = "unified_error"
        const val NETWORK_ERROR = "network_error"
        const val UNAVAILABLE = "unavailable"
        const val FORCE_UPDATE = "force_update"
        const val RATE_LIMITED = "rate_limited"
        const val AGENT_CONSENT = "agent_consent"
        const val UNIFIED_CONSENT = "unified_consent"
        const val LOAD_FAILED = "load_failed"
        const val EMPTY_CONVERSATION = "empty_conversation"
        const val RELOAD_ORIGINAL_RUN = "reload_original_run"
        const val LOGGED = "logged"
        const val ACTION_FAILED = "action_failed"
        const val REASON_ERROR = "reason_error"
        const val REPLAY_NONE = "none"
        const val REPLAY_SAME = "same_message"
    }
}

object AgentTransportErrorPolicy {
    /** 发送类（建会话、取当前会话、发送）：AI 10004 才弹同意框。 */
    fun sendClass(operationId: String): Boolean =
        operationId == AgentOperation.SEND || operationId == AgentOperation.CREATE || operationId == AgentOperation.CURRENT

    fun decideResult(
        operationId: String,
        r: AgentHttpResult,
        visible: Boolean = true,
        ticketMatches: Boolean = true,
    ): AgentOpDecision = decide(operationId, r.code >= 0 && !r.notSent, r.code, r.data, r.retryAfter, r.traceId, visible, ticketMatches)

    @Suppress("LongParameterList", "CyclomaticComplexMethod", "LongMethod", "ReturnCount", "ComplexCondition") // 按 operation × 码逐条（G24 表）
    fun decide(
        operationId: String,
        hasEnvelope: Boolean,
        code: Int,
        data: JsonObject,
        retryAfter: String?,
        traceId: String?,
        visible: Boolean = true,
        ticketMatches: Boolean = true,
    ): AgentOpDecision {
        val d = AgentOpDecision()
        d.gateGroup = AgentGateGroup.of(operationId)
        val t6 = if (AgentErrorCodes.server(code)) AgentSendErrorPolicy.trace6(traceId) else null
        d.trace6 = t6
        d.text = AgentController.errorText(code, traceId.orEmpty())
        if (!hasEnvelope) {
            d.action = AgentOpDecision.NETWORK_ERROR
            d.rollback = true
            d.keepSheet = true
            d.text = AgentController.errorText(AgentErrorCodes.GENERIC, "")
            return d
        }
        val consentType = AgentJson.stringOf(data["consent_type"])
        val aiConsent = code == AgentErrorCodes.CONSENT && consentType == AgentErrorCodes.CONSENT_AI
        if (operationId == AgentOperation.CANCEL && code != AgentErrorCodes.RATE_LIMITED && code != AgentErrorCodes.FORCE_UPDATE &&
            code != AgentErrorCodes.UNAVAILABLE && !aiConsent
        ) {
            // 鸿蒙 R2-07（编排者改向量；brief §0.2、编排者补充第 1 条）：cancel 结果一律不导航、不弹框、不改对话——公共码只记本机日志；只结算
            // AI 10004（同步锁闸门）、42901（动作闸门）、10405（强更）、30501（本次进入不再发）与停止场景的 30505
            d.action = if (code == AgentErrorCodes.RUN_MISSING) AgentOpDecision.RELOAD_ORIGINAL_RUN else AgentOpDecision.LOGGED
            d.showError = false
            return d
        }
        when (code) {
            AgentErrorCodes.UNAVAILABLE -> {
                d.action = AgentOpDecision.UNAVAILABLE
                d.showError = false
                return d
            }

            AgentErrorCodes.LOGIN -> {
                d.route = ROUTE_LOGIN
                d.rollback = true
                return d
            }

            AgentErrorCodes.BIND_PHONE -> {
                d.route = ROUTE_BIND_PHONE
                d.rollback = true
                return d
            }

            AgentErrorCodes.FORCE_UPDATE -> {
                d.action = AgentOpDecision.FORCE_UPDATE
                d.rollback = true
                d.keepSheet = true
                return d
            }

            AgentErrorCodes.RATE_LIMITED -> {
                d.action = AgentOpDecision.RATE_LIMITED
                d.disableMs = AgentSendErrorPolicy.retryAfterMs(retryAfter)
                d.rollback = true
                d.keepSheet = true
                return d
            }
        }
        if (code == AgentErrorCodes.CONSENT) {
            if (aiConsent) {
                // 同步锁同意闸门（所有 operation）；是否弹框与是否禁发分开（APP-10 H27、R3-01）
                d.action = AgentOpDecision.AGENT_CONSENT
                d.lockAiGate = true
                d.showConsent = sendClass(operationId) && visible && ticketMatches
                d.replayAfterConsent =
                    if (operationId == AgentOperation.SEND && ticketMatches) AgentOpDecision.REPLAY_SAME else AgentOpDecision.REPLAY_NONE
                d.rollback = true
                d.closeSheet = true
                d.showError = false
                return d
            }
            d.action = AgentOpDecision.UNIFIED_CONSENT
            d.rollback = true
            return d
        }
        when (operationId) {
            AgentOperation.SEND, AgentOperation.CREATE -> {
                val s = AgentSendErrorPolicy.decide(0, code, data, retryAfter, traceId, consentType)
                d.action = s.action
                d.disableMs = if (s.action == AgentSendAction.RUN_IN_PROGRESS) s.disableMs else 0
                d.reuseClientMsgId = s.reuseClientMsgId
                d.trace6 = s.trace6 ?: t6
            }

            AgentOperation.CURRENT -> {
                d.action = AgentOpDecision.LOAD_FAILED
            }

            AgentOperation.HISTORY -> {
                if (code == AgentErrorCodes.ROUND_LIMIT) {
                    d.action = AgentOpDecision.EMPTY_CONVERSATION
                    d.clearSessionId = true
                } else {
                    d.action = AgentOpDecision.LOAD_FAILED
                }
            }

            AgentOperation.FEEDBACK -> {
                d.rollback = true
                d.action = if (code == AgentErrorCodes.ROUND_LIMIT) AgentOpDecision.ACTION_FAILED else AgentOpDecision.UNIFIED_ERROR
            }

            AgentOperation.REPORT -> {
                val fields = AgentJson.arrayOf(data["fields"]).orEmpty().map { AgentJson.stringOf(it) }
                when {
                    code == AgentErrorCodes.ROUND_LIMIT -> {
                        d.action = AgentOpDecision.ACTION_FAILED
                        d.closeSheet = true
                    }

                    code == AgentErrorCodes.TEXT_INVALID && FIELD_REASON in fields -> {
                        d.action = AgentOpDecision.REASON_ERROR
                        d.keepSheet = true
                        d.reasonInlineError = true
                    }

                    else -> {
                        d.action = AgentOpDecision.UNIFIED_ERROR
                        d.keepSheet = true
                    }
                }
            }
        }
        return d
    }

    /**
     * 向量接线（jsonErrors 组）。
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount") // 按决定逐项投影
    fun of(
        input: JsonObject,
        nowMs: Long,
    ): JsonObject {
        val mode = AgentJson.stringOf(input["modeRaw"])
        if (mode == MODE_GATE) return gateJson(input, nowMs)
        val op = AgentJson.stringOf(input["operationRaw"]).orEmpty()
        val owner = AgentJson.stringOf(input["owner"]) ?: "A"
        if (mode == MODE_STREAM_ERROR) {
            val headers = AgentJson.objectOf(input["responseHeaders"]) ?: AgentJson.EMPTY
            val code = (AgentJson.intOf(input["code"]) ?: AgentErrorCodes.GENERIC.toLong()).toInt()
            val trace = AgentJson.stringOf(headers["x-trace-id"])
            return AgentJson.obj(
                "trace6" to if (AgentErrorCodes.server(code)) AgentSendErrorPolicy.trace6(trace) else null,
                "automaticReplay" to false,
            )
        }
        val codeValue = AgentJson.intOf(input["code"])
        val hasEnvelope = AgentJson.boolOf(input["hasEnvelope"]) != false && codeValue != null
        val code = codeValue?.toInt() ?: -1
        val data = AgentJson.objectOf(input["data"]) ?: AgentJson.EMPTY
        val d =
            decide(
                op,
                hasEnvelope,
                code,
                data,
                AgentJson.stringOf(input["retryAfter"]),
                AgentJson.stringOf(input["traceId"]),
                AgentJson.boolOf(input["visible"]) != false,
                AgentJson.boolOf(input["ticketMatches"]) != false,
            )
        val operation = ApiOperations.byId(op)
        val o = linkedMapOf<String, JsonElement>()
        o["gateGroupRaw"] = JsonPrimitive(d.gateGroup)
        o["minVersionGate"] = JsonPrimitive(operation?.minVersionGate == ApiMinVersionGate.GATED)
        o["actionRaw"] = JsonPrimitive(d.action)
        o["automaticReplay"] = JsonPrimitive(false)
        o["lockAiGate"] = JsonPrimitive(d.lockAiGate)
        o["lockSynchronous"] = JsonPrimitive(d.lockAiGate)
        o["showConsent"] = JsonPrimitive(d.showConsent)
        o["replayAfterConsentRaw"] = JsonPrimitive(d.replayAfterConsent)
        if (d.lockAiGate) {
            o["historyAllowed"] = JsonPrimitive(false)
            o["feedbackAllowed"] = JsonPrimitive(false)
            o["reportAllowed"] = JsonPrimitive(false)
            if (AgentJson.boolOf(input["ticketMatches"]) == false) o["conversationMutations"] = JsonPrimitive(0)
        }
        o["routeRaw"] = d.route?.let(::JsonPrimitive) ?: JsonNull
        // R2-07：是否导航、是否弹框（cancel 单列：一律 false）
        o["navigates"] = JsonPrimitive(d.route != null)
        o["showsDialog"] = JsonPrimitive(d.showConsent)
        if (d.action == AgentOpDecision.UNAVAILABLE) o["furtherAgentRequests"] = JsonPrimitive(0)
        o["clearSessionId"] = JsonPrimitive(d.clearSessionId)
        o["rollback"] = JsonPrimitive(d.rollback)
        o["closeSheet"] = JsonPrimitive(d.closeSheet)
        o["keepSheet"] = JsonPrimitive(d.keepSheet)
        o["showError"] = JsonPrimitive(d.showError)
        o["reuseClientMsgId"] = JsonPrimitive(d.reuseClientMsgId)
        o["trace6"] = d.trace6?.let(::JsonPrimitive) ?: JsonNull
        val writes = mutableListOf<JsonElement>()
        if (d.disableMs > 0) {
            o["disableMs"] = JsonPrimitive(d.disableMs)
            if (d.action == AgentOpDecision.RATE_LIMITED) {
                writes += gateWrite(d.gateGroup, owner, d.disableMs)
                o["gateWriteCount"] = JsonPrimitive(1)
                o["gateOwner"] = JsonPrimitive(owner)
            }
        }
        o["gateWrites"] = JsonArray(writes)
        return JsonObject(o)
    }

    /** 一次闸门写入的投影（向量的 gateWrites 项）。 */
    fun gateWrite(
        group: String,
        owner: String,
        delayMs: Long,
    ): JsonObject = AgentJson.obj("groupRaw" to group, "owner" to owner, "delayMs" to delayMs)

    /** 三组闸门的独立性与两处生效（虚拟时钟、不起计时器）。 */
    @Suppress("UnusedParameter") // nowMs 与向量入口同形
    private fun gateJson(
        input: JsonObject,
        nowMs: Long,
    ): JsonObject {
        var now = 0L
        val owner = AgentJson.stringOf(input["owner"]) ?: "A"
        var current = owner
        val gates = AgentGates.create({ now }, { current })
        for (v in AgentJson.arrayOf(input["writes"]).orEmpty()) {
            val w = AgentJson.objectOf(v) ?: AgentJson.EMPTY
            now = AgentJson.intOf(w["atMs"]) ?: 0L
            gates
                .of(
                    AgentJson.stringOf(w["operationRaw"]).orEmpty(),
                ).start(AgentSendErrorPolicy.retryAfterMs(AgentJson.stringOf(w["retryAfter"])))
        }
        now = AgentJson.intOf(input["checkAtMs"]) ?: 0L
        current = AgentJson.stringOf(input["currentOwner"]) ?: owner
        return AgentJson.obj(
            "sendUiAllowed" to !gates.send.active,
            "sendExecutionAllowed" to gates.allowed(AgentOperation.SEND),
            "readUiAllowed" to !gates.read.active,
            "readExecutionAllowed" to gates.allowed(AgentOperation.HISTORY),
            "actionUiAllowed" to !gates.action.active,
            "actionExecutionAllowed" to gates.allowed(AgentOperation.FEEDBACK),
            "oldGateApplied" to (current == owner),
        )
    }

    private const val MODE_GATE = "gate"
    private const val MODE_STREAM_ERROR = "stream_error"
    private const val FIELD_REASON = "reason"
    private const val ROUTE_LOGIN = "Login"
    private const val ROUTE_BIND_PHONE = "BindPhone"
}
