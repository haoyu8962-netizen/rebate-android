package com.couli.core.agent

import java.util.ServiceLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// APP-10b §2 第 7 条（F-X-04）：【停止】与归属变化时的 cancel（编排者补充第 1、5 条，G01、G25、G26）。三端同名（鸿蒙 AgentStop.ets）。
// - AgentCancelLedger：同一 run 只发 1 次 cancel（连点、重放、重进页面、归属协调器都共用这一份账本；只在内存）。
// - AgentStopFlow：用户【停止】——有 run_id：同步本地结束为 cancelled、关连接，发 1 次 cancel；没有 run_id（meta 之前）：显示「正在停止」、内容票据
//   作废（之后的内容帧一律不交 Reducer），连接保持到 meta 到达（最长 30 秒，G26）后立即关连接并发 1 次 cancel；30 秒没有 meta 或先到了终止帧就只关
//   连接、不发（本轮照常计数）。动作闸门（42901）期间不发 cancel，本地照常停止。
// - AgentCancelPolicy：cancel 结果——200 静默；30505 按契约「刷新会话消息」自动拉一次历史，本轮在服务端以非 cancelled 结束时用历史替换（G25）；
//   AI 10004 锁同意闸门、不弹框；42901 写动作闸门、提示一次；10405 交强更；网络失败只记本机日志。结果一律不导航、不弹框。
// - AgentOwnershipStop：应用级协调器（G01）。在退出登录、撤回 AI 同意、撤回隐私同意三个流程「用户确认后的同步入口、发出该流程自己的请求之前」调用：先同步
//   停流（控制器作废：关连接、停计时、作废票据），再以 run 票据里的原身份尽力发一次 cancel（单次发送：不刷新、不重注册、不重放；准入不通过就不发、不重试、
//   不改发新身份；不挂页面归属，离页不取消；不等结果）。切环境只本地停流不发；会话失效、被动换号、新对话、页面销毁不发。控制器层（旧向量 ticket 表）仍不发。

/** 已发出 cancel 的 run（进程内存，按 run_id）。只在主线程使用。 */
class AgentCancelLedger {
    private val runs = mutableSetOf<String>()

    /** 第一次为这个 run 发 cancel 返回 true；之后一律 false。 */
    fun claim(runId: String): Boolean = runs.add(runId)

    fun sent(runId: String): Boolean = runId in runs
}

object AgentStopPhase {
    const val RUNNING = "running"

    /** meta 之前点了停止：等 meta（≤30 秒）。 */
    const val STOPPING = "stopping"
    const val STOPPED = "stopped"
}

/** 停止流程的一步要做的事。 */
data class AgentStopEffect(
    val close: Boolean,
    /** 要发 cancel 的 run_id（不发为 null）。 */
    val cancelRunId: String?,
    /** 进入「正在停止」：等 meta 的毫秒数（0 = 不等）。 */
    val waitMs: Long,
) {
    companion object {
        val NONE: AgentStopEffect = AgentStopEffect(false, null, 0)
    }
}

/** 一轮的停止流程（只在主线程使用）。 */
class AgentStopFlow(
    private val ledger: AgentCancelLedger,
) {
    var phase: String = AgentStopPhase.RUNNING
        private set

    /** 用户点【停止】（连点只算第一次）。[actionGateOpen]：动作闸门此刻放行。 */
    @Suppress("ReturnCount") // 逐项提前返回
    fun stop(
        runId: String?,
        actionGateOpen: Boolean,
    ): AgentStopEffect {
        if (phase != AgentStopPhase.RUNNING) return AgentStopEffect.NONE
        if (runId == null) {
            phase = AgentStopPhase.STOPPING
            return AgentStopEffect(false, null, META_WAIT_MS)
        }
        phase = AgentStopPhase.STOPPED
        return AgentStopEffect(true, claim(runId, actionGateOpen), 0)
    }

    /** 正在停止时 meta 到了：立即关连接、发 1 次 cancel。 */
    fun onMeta(
        runId: String?,
        actionGateOpen: Boolean,
    ): AgentStopEffect {
        if (phase != AgentStopPhase.STOPPING) return AgentStopEffect.NONE
        phase = AgentStopPhase.STOPPED
        return AgentStopEffect(true, runId?.let { claim(it, actionGateOpen) }, 0)
    }

    /** 正在停止时先到了终止帧 / 断线：只关连接、不发。 */
    fun onTerminal(): AgentStopEffect {
        if (phase != AgentStopPhase.STOPPING) return AgentStopEffect.NONE
        phase = AgentStopPhase.STOPPED
        return AgentStopEffect(true, null, 0)
    }

    /** 30 秒没有 meta：只关连接、不发。 */
    fun onWaitExpired(): AgentStopEffect = onTerminal()

    private fun claim(
        runId: String,
        actionGateOpen: Boolean,
    ): String? = if (actionGateOpen && ledger.claim(runId)) runId else null

    companion object {
        /** G26：meta 之前点停止，最长等 30 秒（编排者补充第 5 条）。 */
        const val META_WAIT_MS = 30_000L
    }
}

/** cancel 结果的共享结算（不导航、不弹框、不改对话；30505 例外：按契约重载原 run）。 */
data class AgentCancelResult(
    val reload: Boolean,
    val lockAiGate: Boolean,
    /** 写动作闸门的毫秒数（0 = 不写）。 */
    val gateDelayMs: Long,
    val toast: Boolean,
    val forceUpdate: Boolean,
    /** 30501：本次进入周期不再发 agent 请求（鸿蒙 R1-06）。 */
    val unavailable: Boolean = false,
)

object AgentCancelPolicy {
    fun result(r: AgentHttpResult): AgentCancelResult {
        if (r.ok || r.notSent || r.code < 0) return AgentCancelResult(false, false, 0, false, false)
        return when (r.code) {
            AgentErrorCodes.RUN_MISSING -> AgentCancelResult(true, false, 0, false, false)
            AgentErrorCodes.RATE_LIMITED -> AgentCancelResult(false, false, AgentSendErrorPolicy.retryAfterMs(r.retryAfter), true, false)
            AgentErrorCodes.FORCE_UPDATE -> AgentCancelResult(false, false, 0, false, true)
            AgentErrorCodes.CONSENT -> AgentCancelResult(false, r.consentType == AgentErrorCodes.CONSENT_AI, 0, false, false)
            AgentErrorCodes.UNAVAILABLE -> AgentCancelResult(false, false, 0, false, false, unavailable = true)
            else -> AgentCancelResult(false, false, 0, false, false)
        }
    }

    /** 向量接线（cancel 组）：驱动同一个 [AgentStopFlow] 与账本（虚拟时钟），结果按 [result] 结算。 */
    @Suppress("UnusedParameter", "LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth") // nowMs 与向量入口同形；时间线逐步推进
    fun of(
        input: JsonObject,
        nowMs: Long,
    ): JsonObject {
        val ledger = AgentCancelLedger()
        var flow = AgentStopFlow(ledger)
        var runId = AgentJson.stringOf(input["runId"])
        val gateOpen = AgentJson.boolOf(input["actionGateOpen"]) != false
        val owner = AgentJson.stringOf(input["owner"]) ?: "A"
        var sends = 0
        var closeAt: Long? = null
        var statusAtStop: String? = null
        var deadline = -1L
        var contentAfterStop = 0
        var stopped = false
        for (v in AgentJson.arrayOf(input["events"]).orEmpty()) {
            val e = AgentJson.objectOf(v) ?: AgentJson.EMPTY
            val at = AgentJson.intOf(e["atMs"]) ?: 0L
            val kind = AgentJson.stringOf(e["kindRaw"]).orEmpty()
            var eff = AgentStopEffect.NONE
            when (kind) {
                "stop" -> {
                    eff = flow.stop(runId, gateOpen)
                    if (!stopped) {
                        stopped = true
                        statusAtStop = if (flow.phase == AgentStopPhase.STOPPING) STATUS_STOPPING else AgentStatus.CANCELLED
                        if (eff.waitMs > 0) deadline = at + eff.waitMs
                    }
                }

                // 重进页面：新的停止流程，共用同一本账（同一 run 不再发）
                "reenter" -> {
                    flow = AgentStopFlow(ledger)
                }

                "meta" -> {
                    if (flow.phase == AgentStopPhase.STOPPING && (deadline < 0 || at < deadline)) {
                        runId = AgentJson.stringOf(e["runId"])
                        eff = flow.onMeta(runId, gateOpen)
                    }
                }

                "tick" -> {
                    if (flow.phase == AgentStopPhase.STOPPING && deadline >= 0 && at >= deadline) eff = flow.onWaitExpired()
                }

                "done" -> {
                    eff = flow.onTerminal()
                }

                // 停止之后到达的内容 / 终止帧：内容票据已作废，不交 Reducer
                "late_content", "late_done" -> {
                    contentAfterStop += if (stopped) 0 else 1
                }
            }
            if (eff.close && closeAt == null) closeAt = if (kind == "tick") deadline else at
            if (eff.cancelRunId != null) sends += 1
        }
        val o = linkedMapOf<String, JsonElement>()
        o["sendsCancel"] = JsonPrimitive(sends)
        o["closeAtMs"] = closeAt?.let(::JsonPrimitive) ?: JsonNull
        o["statusAtStopRaw"] = statusAtStop?.let(::JsonPrimitive) ?: JsonNull
        if (statusAtStop == STATUS_STOPPING) o["displayAtStop"] = JsonPrimitive(AgentDraftTexts.text(STOPPING_KEY))
        o["contentDeliveriesAfterStop"] = JsonPrimitive(contentAfterStop)
        o["lateReducerDeliveries"] = JsonPrimitive(contentAfterStop)
        o["cancelOwnerRaw"] = JsonPrimitive(OWNER_APPLICATION)
        o["cardActionGenerationIncrement"] = JsonPrimitive(1)
        o["navigate"] = JsonPrimitive(false)
        o["showConsent"] = JsonPrimitive(false)
        o["retryCancel"] = JsonPrimitive(false)
        var local = AgentStatus.CANCELLED
        var reload = false
        val gateWrites = mutableListOf<JsonElement>()
        var toasts = 0
        var lock = false
        val res = AgentJson.objectOf(input["result"])
        if (sends > 0 && res != null) {
            val r = result(resultOf(res))
            reload = r.reload
            lock = r.lockAiGate
            if (r.gateDelayMs > 0) gateWrites += AgentTransportErrorPolicy.gateWrite(AgentGateGroup.ACTION, owner, r.gateDelayMs)
            toasts = if (r.toast) 1 else 0
            if (reload) {
                // 30505：拉一次历史；本轮在服务端以非 cancelled 结束、且票据仍对得上（同一对话）才替换（G25）
                val ticket = AgentJson.boolOf(input["ticketMatches"]) != false
                val finish = AgentJson.stringOf(input["historyFinishReasonRaw"])
                val replaced = ticket && AgentJson.boolOf(input["historyMatched"]) == true && finish != FINISH_CANCELLED
                o["replaced"] = JsonPrimitive(replaced)
                o["conversationMutations"] = JsonPrimitive(if (replaced) 1 else 0)
                if (replaced) local = AgentHistory.statusOf(finish)
                o["reloadTargetRaw"] = JsonPrimitive("original_run")
            }
        }
        o["localStatusRaw"] = JsonPrimitive(local)
        o["reload"] = JsonPrimitive(reload)
        o["lockAiGate"] = JsonPrimitive(lock)
        o["gateWrites"] = JsonArray(gateWrites)
        o["toastCount"] = JsonPrimitive(toasts)
        o["toast"] = JsonNull
        return JsonObject(o)
    }

    /** 向量里的结果描述 → 端口结果。 */
    fun resultOf(res: JsonObject): AgentHttpResult {
        if (AgentJson.stringOf(res["resultRaw"]) == "network_error") return AgentHttpResult.network()
        val code = (AgentJson.intOf(res["code"]) ?: 0L).toInt()
        val http =
            when (code) {
                0 -> HTTP_OK
                AgentErrorCodes.RATE_LIMITED -> HTTP_TOO_MANY
                else -> HTTP_UNPROCESSABLE
            }
        return AgentHttpResult(
            code == 0,
            http,
            code,
            "",
            AgentJson.objectOf(res["data"]) ?: AgentJson.EMPTY,
            AgentJson.stringOf(res["traceId"]).orEmpty(),
            AgentJson.stringOf(res["retryAfter"]),
        )
    }

    private const val STATUS_STOPPING = "stopping"
    private const val STOPPING_KEY = "agent.draft.stopping"
    private const val OWNER_APPLICATION = "application"
    private const val FINISH_CANCELLED = "cancelled"
    private const val HTTP_OK = 200
    private const val HTTP_TOO_MANY = 429
    private const val HTTP_UNPROCESSABLE = 422
}

object AgentOwnershipEvent {
    const val LOGOUT = "logout"
    const val PRIVACY_WITHDRAWN = "privacy_withdrawn"
    const val AI_CONSENT_WITHDRAWN = "ai_consent_withdrawn"
    const val ENV_SWITCH = "env_switch"
    const val SESSION_INVALID = "session_invalid"
    const val ACCOUNT_SWITCH = "account_switch"
    const val NEW_CHAT = "new_chat"
    const val PAGE_DESTROYED = "page_destroyed"
    const val TAB_HIDDEN = "tab_hidden"
    const val PAGE_COVERED = "page_covered"
}

/** 被同步作废的那一轮（控制器交出）：run_id（没有 meta 为 null）+ 以原身份准入的 cancel 调用。 */
class AgentOwnedRun(
    val runId: String?,
    val call: AgentCall,
    /** 原身份的归属键（不含页面实例；42901 写闸门与应用级「AI 同意失效」都按它核对，鸿蒙 R2-02、R3-03）。 */
    val ownerKey: Any,
) {
    override fun toString(): String = "AgentOwnedRun(${runId != null})"
}

/** 应用级「AI 同意失效」能同步停下的控制器（[AgentController] 实现）。 */
interface AgentConsentLapseTarget {
    /** 控制器构造时固定的归属键（不含页面实例）。 */
    fun boundOwnerKey(): Any

    /** 同步作废全部在途工作并锁同意闸门（不弹框、不导航）。 */
    fun consentLapsed()
}

/**
 * 应用级「AI 同意失效」（鸿蒙 R2-02、R3-01，S0；共性第 28、33 条）：所有来源（普通发送、反馈 / 举报、停止的 cancel、身份事件的 cancel）的 AI 10004 都汇入这里，
 * 按原身份的归属键（不含页面实例）核对——此刻仍是同一归属才记下，并同步停下该归属此刻的控制器（关流、取消所属请求、锁同意闸门）；记下之后，重建出来的控制器
 * 一开始就是「需要重新同意」、请求准入一律不通过，直到同一归属重新同意记录成功才解除。不同身份的结果整体丢弃（不写任何状态、不碰新身份）。只在内存（G71）。
 */
class AgentConsentLapse {
    private var owner: Any? = null

    /** 记下一次失效；返回是否记下（原归属 ≠ 此刻归属时丢弃）。[target] 为此刻的控制器（没有为 null；归属不同不碰它）。 */
    fun lapse(
        originalOwnerKey: Any,
        currentOwnerKey: Any?,
        target: AgentConsentLapseTarget?,
    ): Boolean {
        if (originalOwnerKey != currentOwnerKey) return false
        owner = originalOwnerKey
        if (target != null && target.boundOwnerKey() == originalOwnerKey) runCatching { target.consentLapsed() }
        return true
    }

    /** 这个归属此刻是否处于「AI 同意失效」（准入与重建都按它）。 */
    fun blocks(ownerKey: Any?): Boolean = owner != null && owner == ownerKey

    /** 同一归属重新同意记录成功：解除。 */
    fun agreed(ownerKey: Any?) {
        if (owner == ownerKey) owner = null
    }
}

/** 归属协调器能作废的对象（[AgentController] 实现）。 */
fun interface AgentOwnershipTarget {
    /** 同步作废（关连接、停计时、作废票据与卡片回调、清空对话），交出在途的那一轮（没有为 null）。 */
    fun stopForOwnership(event: String): AgentOwnedRun?
}

/** cancel 结果的应用级结算出口（不导航、不弹框）。 */
class AgentOwnershipHandlers(
    val rateLimited: (ownerKey: Any, millis: Long) -> Unit = { _, _ -> },
    val forceUpdate: () -> Unit = {},
    val aiConsentLost: (ownerKey: Any) -> Unit = {},
    val log: (String) -> Unit = {},
)

class AgentOwnershipDecision(
    val run: AgentOwnedRun?,
    val sendCancel: Boolean,
)

/**
 * 一次已交给端口的应用级 cancel：流程用 [awaitWritten] 等它写出（或确定不写出）再发自己的请求（「早于自身请求」），不等结果。
 *
 * 发送准入与超时撤销共用这里的一个协调状态（Android R2-03，共性第 41 条）：写出前的准入 [admit] 放行即记为「已准入写出」（之后不可撤销）；等写出超时时
 * [revokeIfPending] 只在还没准入时原子地记为「已撤销」并同步取消这次网络调用——之后的准入一律不放行，迟到的 cancel 不会晚于流程自己的请求写出。已准入 / 已写出的
 * 照常结算共享结果。
 */
class AgentCancelDispatch internal constructor() {
    internal val written = CompletableDeferred<Boolean>()
    private val lock = Any()
    private var state = PENDING

    /** 同步取消这次网络调用（端口在发起时交来；还没交来为 null）。 */
    @Volatile private var abort: (() -> Unit)? = null

    internal fun bindAbort(cancel: () -> Unit) {
        abort = cancel
        if (revoked) runCatching(cancel)
    }

    /** 已被超时撤销（测试与诊断）。 */
    val revoked: Boolean get() = synchronized(lock) { state == REVOKED }

    /** 写出前的准入（网络线程）：已撤销 → 不放行；原身份仍准入 → 记为已准入写出（不可再撤销）。 */
    internal fun admit(base: () -> Boolean): Boolean =
        synchronized(lock) {
            val ok = state != REVOKED && runCatching(base).getOrDefault(false)
            if (ok) state = ADMITTED
            ok
        }

    /** 等写出超时：还没准入写出 → 原子撤销并取消网络调用（返回 true）；已准入 / 已写出 → false。 */
    internal fun revokeIfPending(): Boolean {
        val revokedNow =
            synchronized(lock) {
                if (state != PENDING || written.isCompleted) return@synchronized false
                state = REVOKED
                true
            }
        if (revokedNow) {
            runCatching { abort?.invoke() }
            written.complete(false)
        }
        return revokedNow
    }

    /**
     * 等到 cancel 已写出 / 确定没写出（最多 [timeoutMs]）。超时：还没准入写出的原子撤销准入并取消网络调用，按没写出继续（不阻塞原流程）；已准入写出的（正在写）
     * 再等同样的上限拿到写出信号。返回是否已写出。
     */
    suspend fun awaitWritten(timeoutMs: Long = WRITE_WAIT_MS): Boolean {
        val first = withTimeoutOrNull(timeoutMs) { written.await() }
        return when {
            first != null -> first
            revokeIfPending() -> false
            else -> withTimeoutOrNull(timeoutMs) { written.await() } ?: false
        }
    }

    companion object {
        private const val PENDING = 0
        private const val ADMITTED = 1
        private const val REVOKED = 2

        /** 等待写出的上限（代拟，rule-gaps A 号）：连接建立 + 写出通常远小于它；超过就不再等。 */
        const val WRITE_WAIT_MS = 2_000L

        val NONE: AgentCancelDispatch = AgentCancelDispatch().also { it.written.complete(false) }
    }
}

/** 应用级协调器（G01）。只在主线程使用。 */
class AgentOwnershipStop(
    private val ledger: AgentCancelLedger,
    private val handlers: AgentOwnershipHandlers,
    private val scope: CoroutineScope,
) {
    /** 应用级 cancel 的发出次数（测试与诊断）。 */
    var sent: Int = 0
        private set

    /** 同步部分：作废控制器、判定要不要发（不发请求）。 */
    fun before(
        event: String,
        target: AgentOwnershipTarget?,
    ): AgentOwnershipDecision {
        val run = runCatching { target?.stopForOwnership(event) }.getOrNull() ?: return AgentOwnershipDecision(null, false)
        val admitted = runCatching { run.call.sendAllowed() }.getOrDefault(false)
        val send = sendsCancel(event, run.runId != null, admitted) && ledger.claim(run.runId.orEmpty())
        return AgentOwnershipDecision(run, send)
    }

    /** 发出 cancel（尽力一次、单次发送、不等结果；不挂页面归属）。返回写出信号（没发为 [AgentCancelDispatch.NONE]）。 */
    @Suppress("ComplexCondition") // 条件逐项
    fun dispatch(
        decision: AgentOwnershipDecision,
        port: AgentContractPort?,
    ): AgentCancelDispatch {
        val run = decision.run
        val runId = run?.runId
        if (!decision.sendCancel || run == null || runId == null || port == null) return AgentCancelDispatch.NONE
        sent += 1
        val signal = AgentCancelDispatch()
        val base = run.call
        val call =
            AgentCall(
                identityKey = base.identityKey,
                anonymous = base.anonymous,
                // 发送准入与超时撤销同一协调状态（R2-03）：撤销之后不再放行
                sendAllowed = { signal.admit(base.sendAllowed) },
                owner = { !signal.revoked && base.owner() },
                scope = base.scope,
                // 应用级 cancel 不挂页面归属（离页 / 控制器作废不取消它，brief §0 第 2 条）
                pageOwned = false,
                singleShot = true,
                onWritten = { written ->
                    signal.written.complete(written)
                    base.onWritten?.invoke(written)
                },
                onAbortable = signal::bindAbort,
            )
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val r = runCatching { port.cancelRun(runId, call) }.getOrElse { AgentHttpResult.network() }
            signal.written.complete(!r.notSent)
            settle(r, run.ownerKey)
        }
        return signal
    }

    /** 结果只结算共享事实：42901 写动作闸门、10405 强更、AI 10004 → 应用级「AI 同意失效」（R2-02）；其余只记本机日志（只写结果码）。 */
    fun settle(
        r: AgentHttpResult,
        ownerKey: Any,
    ): AgentCancelResult {
        val result = AgentCancelPolicy.result(r)
        // 鸿蒙 R3-03：交原归属键（不含页面实例），App 先核对它仍是此刻归属再写动作闸门
        if (result.gateDelayMs > 0) handlers.rateLimited(ownerKey, result.gateDelayMs)
        if (result.forceUpdate) handlers.forceUpdate()
        if (result.lockAiGate) handlers.aiConsentLost(ownerKey)
        if (!r.ok) handlers.log("agent ownership cancel result ${r.code}")
        return result
    }

    companion object {
        /** G01 表：只有退出登录、撤回 AI 同意、撤回隐私发；有 run_id 且原身份此刻仍能准入才发。 */
        fun sendsCancel(
            event: String,
            hasRunId: Boolean,
            originalAdmitted: Boolean,
        ): Boolean {
            val sends =
                event == AgentOwnershipEvent.LOGOUT || event == AgentOwnershipEvent.AI_CONSENT_WITHDRAWN ||
                    event == AgentOwnershipEvent.PRIVACY_WITHDRAWN
            return sends && hasRunId && originalAdmitted
        }

        /**
         * ownershipCancel 组向量的接线入口（鸿蒙 R1-04，S0）：驱动真实协调器的脚本端口、虚拟流与硬编码帧只在测试源码集（经 [AgentVectorWiring] 的
         * ServiceLoader 注册，core/agent/src/test/resources/META-INF/services）；生产制品里没有。没有注入实现时返回空对象（生产代码不调用它）。
         */
        fun of(
            input: JsonObject,
            nowMs: Long,
        ): JsonObject = AgentVectorWiring.instance?.ownership(input, nowMs) ?: AgentJson.EMPTY
    }
}

/**
 * 两组向量（timeouts、ownershipCancel）驱动真实 StreamClient / 归属协调器的接线（鸿蒙 R1-04，S0，共性第 13、25 条）：实现只在测试源码集，经 ServiceLoader
 * 注册（core/agent/src/test/resources/META-INF/services/com.couli.core.agent.AgentVectorWiring）；生产制品里没有假端口、没有虚拟流、没有硬编码帧。
 */
interface AgentVectorWiring {
    fun timeouts(
        input: JsonObject,
        nowMs: Long,
    ): JsonObject

    fun ownership(
        input: JsonObject,
        nowMs: Long,
    ): JsonObject

    companion object {
        /** 测试源码集登记的实现（生产为 null）。 */
        val instance: AgentVectorWiring? by lazy {
            runCatching {
                ServiceLoader
                    .load(
                        AgentVectorWiring::class.java,
                        AgentVectorWiring::class.java.classLoader,
                    ).firstOrNull()
            }.getOrNull()
        }
    }
}
