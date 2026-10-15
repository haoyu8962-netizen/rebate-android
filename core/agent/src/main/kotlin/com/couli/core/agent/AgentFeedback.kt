package com.couli.core.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// APP-10b §2 第 8、9 条（F-X-04）：【赞】【踩】与举报的状态机（只在内存；不落盘、不进日志与埋点，brief §0 第 4 条）。三端同名（鸿蒙 AgentFeedback.ets）。
// - 显示条件（G28、编排者补充第 3 条）：assistant 消息已结束（done / error / cancelled / limited）且有可信的回复 message_id（流内 meta 给的、在历史里
//   确为 assistant 的；拿不到就不画，不猜）；disconnected / incomplete / 回复中 / duplicate 尚未以历史替换的不显示。举报入口同一条件。
// - 反馈：点击乐观置为实心并读屏「已赞 / 已踩」，在途时两个按钮都不可点；同值再点不发；改选发新值（服务端覆盖写）；契约没有「取消」。失败恢复原状态并按统一
//   错误提示（AgentTransportErrorPolicy：5xxxx 带 trace6；AI 10004 锁闸门不弹框；42901 写动作闸门；30504 按 G31）。重载后显示为未选。
// - 举报（G29）：reason = 所选原因；补充说明去掉首尾空白后非空时拼成「<原因>：<补充说明>」；整体按 Unicode 码点 ≤200（超出不让提交）。成功：关半屏、
//   这条显示「已举报」+ 轻提示，本对话内不再提供入口；失败：半屏保留已填内容（20001 原因旁提示；10004 关半屏并锁闸门；30504 关半屏提示）。
// - 回调捕获 {控制器编号, 对话世代, message_id}（[AgentReplyTicket]）；结果迟到（对话换代、归属变化）丢弃。

object AgentFeedbackValue {
    const val UP = "up"
    const val DOWN = "down"
}

/** 反馈 / 举报按钮的渲染时票据（控制器只比较）。 */
data class AgentReplyTicket(
    val controller: Long,
    val conversation: Long,
    val messageId: String,
)

/** 反馈或举报失败后页面要做的事（共享结算 + 页面提示）。 */
class AgentActionFailure(
    val decision: AgentOpDecision,
    /** 要提示的文字（空串不提示）。 */
    val message: String,
)

object AgentFeedback {
    /** 已结束的规范化状态（显示【赞】【踩】与举报入口）。 */
    val ENDED: List<String> = listOf(AgentStatus.DONE, AgentStatus.ERROR, AgentStatus.CANCELLED, AgentStatus.LIMITED)

    /** 显示条件（G03、G28）。 */
    @Suppress("LongParameterList") // 显示条件逐项
    fun visible(
        role: String,
        status: String,
        messageId: String?,
        replyIdTrusted: Boolean,
        resolvedRole: String,
        duplicatePending: Boolean,
    ): Boolean =
        role == AgentRole.ASSISTANT && status in ENDED && !messageId.isNullOrEmpty() && replyIdTrusted &&
            resolvedRole == AgentRole.ASSISTANT && !duplicatePending

    fun a11y(selected: String?): String =
        when (selected) {
            AgentFeedbackValue.UP -> AgentDraftTexts.text("agent.draft.feedback.up_selected")
            AgentFeedbackValue.DOWN -> AgentDraftTexts.text("agent.draft.feedback.down_selected")
            else -> ""
        }

    /** 失败的页面提示（G31：30504 用代拟的「操作没有成功」；其余统一错误文案，5xxxx 带 trace6；10004 / 42901 由闸门处理）。 */
    fun failure(
        op: String,
        r: AgentHttpResult,
    ): AgentActionFailure {
        val d = AgentTransportErrorPolicy.decideResult(op, r)
        val message =
            when {
                r.code == AgentErrorCodes.ROUND_LIMIT -> AgentDraftTexts.text(ACTION_FAILED_KEY)

                d.action == AgentOpDecision.UNIFIED_ERROR || d.action == AgentOpDecision.NETWORK_ERROR ||
                    d.action == AgentOpDecision.ACTION_FAILED -> d.text

                else -> ""
            }
        return AgentActionFailure(d, message)
    }

    fun body(value: String): JsonObject = AgentJson.obj("feedback" to value)

    /** 失败的投影（反馈与举报共用）。 */
    fun failureJson(
        o: MutableMap<String, JsonElement>,
        fail: AgentActionFailure,
        owner: String,
        gateWrites: MutableList<JsonElement>,
    ) {
        val d = fail.decision
        o["actionRaw"] = JsonPrimitive(d.action)
        if (fail.message.isNotEmpty()) o["message"] = JsonPrimitive(fail.message)
        o["lockAiGate"] = JsonPrimitive(d.lockAiGate)
        o["showConsent"] = JsonPrimitive(false)
        o["trace6"] = d.trace6?.let(::JsonPrimitive) ?: JsonNull
        if (d.disableMs > 0) gateWrites += AgentTransportErrorPolicy.gateWrite(d.gateGroup, owner, d.disableMs)
    }

    /** 向量接线（feedback 组）。 */
    @Suppress("UnusedParameter", "LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth") // nowMs 与向量入口同形；时间线逐步推进
    fun of(
        input: JsonObject,
        nowMs: Long,
    ): JsonObject {
        if (AgentJson.stringOf(input["modeRaw"]) == MODE_VISIBILITY) return AgentJson.obj("visible" to visibleOf(input))
        val state = AgentFeedbackState()
        state.selected = AgentJson.stringOf(input["selectedRaw"])
        val owner = AgentJson.stringOf(input["owner"]) ?: "A"
        val bodies = mutableListOf<JsonElement>()
        val steps = mutableListOf<JsonElement>()
        val o = linkedMapOf<String, JsonElement>()
        var ticketValid = true
        val gateWrites = mutableListOf<JsonElement>()

        fun step() {
            steps +=
                AgentJson.obj(
                    "selectedRaw" to state.selected,
                    "enabled" to state.enabled,
                    "a11y" to a11y(state.selected),
                    "requestCount" to state.requests,
                )
        }

        fun apply(r: AgentHttpResult) {
            if (!ticketValid) return
            state.result(r)?.let { failureJson(o, it, owner, gateWrites) }
        }
        val events = AgentJson.arrayOf(input["events"])
        if (events == null) {
            // 单次点击 + 结果（失败回滚组）
            state.tap(AgentJson.stringOf(input["tapRaw"]) ?: AgentFeedbackValue.UP)?.let { bodies += body(it) }
            AgentJson.objectOf(input["result"])?.let { apply(AgentCancelPolicy.resultOf(it)) }
        } else {
            for (v in events) {
                val e = AgentJson.objectOf(v) ?: AgentJson.EMPTY
                when (AgentJson.stringOf(e["kindRaw"])) {
                    "tap" -> state.tap(AgentJson.stringOf(e["valueRaw"]) ?: AgentFeedbackValue.UP)?.let { bodies += body(it) }
                    "result" -> apply(AgentCancelPolicy.resultOf(e))
                    "history_reload" -> state.reset()
                    "invalidate" -> ticketValid = false
                }
                step()
            }
        }
        o["steps"] = JsonArray(steps)
        o["bodies"] = JsonArray(bodies)
        o["selectedRaw"] = state.selected?.let(::JsonPrimitive) ?: JsonNull
        o["requestCount"] = JsonPrimitive(state.requests)
        o["reasonSheetCount"] = JsonPrimitive(0)
        o["idempotencyKeyPresent"] = JsonPrimitive(false)
        o["persisted"] = JsonPrimitive(false)
        o["clearSessionId"] = JsonPrimitive(false)
        o["lateResultAccepted"] = JsonPrimitive(false)
        o["conversationMutationsAfterInvalidation"] = JsonPrimitive(0)
        o["navigate"] = JsonPrimitive(false)
        o["gateWrites"] = JsonArray(gateWrites)
        return JsonObject(o)
    }

    internal fun visibleOf(input: JsonObject): Boolean =
        visible(
            AgentJson.stringOf(input["roleRaw"]).orEmpty(),
            AgentJson.stringOf(input["statusRaw"]).orEmpty(),
            AgentJson.stringOf(input["messageId"]),
            AgentJson.boolOf(input["replyIdTrusted"]) == true,
            AgentJson.stringOf(input["resolvedRoleRaw"]).orEmpty(),
            AgentJson.boolOf(input["duplicatePending"]) == true,
        )

    const val ACTION_FAILED_KEY = "agent.draft.action_failed"
    internal const val MODE_VISIBILITY = "visibility"
}

/** 一条回复的反馈状态（只在内存）。只在主线程使用。 */
class AgentFeedbackState {
    var selected: String? = null
    var inFlight: Boolean = false
        private set
    var requests: Int = 0
        private set
    private var before: String? = null

    val enabled: Boolean get() = !inFlight

    /** 点击：在途不可点；同值不发；否则乐观置为新值并返回要发的值（null = 不发）。 */
    @Suppress("ComplexCondition") // 条件逐项
    fun tap(value: String): String? {
        if (inFlight || value == selected || (value != AgentFeedbackValue.UP && value != AgentFeedbackValue.DOWN)) return null
        before = selected
        selected = value
        inFlight = true
        requests += 1
        return value
    }

    /** 结果（调用方已比过票据）：成功保持；失败回滚并返回失败的页面动作。 */
    @Suppress("ReturnCount") // 逐项提前返回
    fun result(r: AgentHttpResult): AgentActionFailure? {
        if (!inFlight) return null
        inFlight = false
        if (r.ok) return null
        selected = before
        return AgentFeedback.failure(AgentOperation.FEEDBACK, r)
    }

    /** 【停止】同步作废在途的这一次（鸿蒙 R2-03）：回滚到点击前、解除在途（迟到结果由控制器丢弃，不提示、不导航）。 */
    fun abort() {
        if (!inFlight) return
        inFlight = false
        selected = before
    }

    /** 历史重载 / 替换：显示为未选（历史不返回反馈状态，G28）。 */
    fun reset() {
        selected = null
        inFlight = false
        before = null
    }
}

class AgentReportCheck(
    val validation: String,
    val reason: String,
    val codePoints: Int,
) {
    companion object {
        const val OK = "ok"
        const val NO_REASON = "no_reason"
        const val TOO_LONG = "too_long"
    }
}

class AgentReportOutcome(
    val success: Boolean,
    val failure: AgentActionFailure?,
)

object AgentReport {
    /** AgentReport 稿五个原因（逐字，草稿表 TODO(N-38)）。 */
    val REASON_KEYS: List<String> =
        listOf(
            "agent.draft.report.reason.misleading",
            "agent.draft.report.reason.illegal",
            "agent.draft.report.reason.vulgar",
            "agent.draft.report.reason.infringe",
            "agent.draft.report.reason.other",
        )
    const val MAX_CODE_POINTS = 200

    fun reasons(): List<String> = REASON_KEYS.map(AgentDraftTexts::text)

    /** G29：拼 reason 并核对 1–200 码点（按码点，不按字素）。 */
    fun compose(
        reason: String?,
        supplement: String,
    ): AgentReportCheck {
        if (reason.isNullOrEmpty()) return AgentReportCheck(AgentReportCheck.NO_REASON, "", 0)
        val extra = supplement.trim()
        val text = if (extra.isNotEmpty()) reason + AgentDraftTexts.text(JOINER_KEY) + extra else reason
        val n = AgentJson.codePoints(text)
        return if (n >
            MAX_CODE_POINTS
        ) {
            AgentReportCheck(AgentReportCheck.TOO_LONG, "", n)
        } else {
            AgentReportCheck(AgentReportCheck.OK, text, n)
        }
    }

    /** 补充说明输入框的码点上限：200 − 原因长度 − 1（G29）。 */
    fun supplementLimit(reason: String?): Int {
        val base = if (reason == null) 0 else AgentJson.codePoints(reason) + 1
        return maxOf(0, MAX_CODE_POINTS - base)
    }

    fun body(reason: String): JsonObject = AgentJson.obj("reason" to reason)

    /** 向量接线（report 组）。 */
    @Suppress("UnusedParameter", "LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth") // nowMs 与向量入口同形；时间线逐步推进
    fun of(
        input: JsonObject,
        nowMs: Long,
    ): JsonObject {
        val o = linkedMapOf<String, JsonElement>()
        if (AgentJson.stringOf(input["modeRaw"]) == AgentFeedback.MODE_VISIBILITY) {
            o["visible"] = JsonPrimitive(AgentFeedback.visibleOf(input))
            return JsonObject(o)
        }
        val owner = AgentJson.stringOf(input["owner"]) ?: "A"
        val state = AgentReportState()
        state.reported = AgentJson.boolOf(input["reported"]) == true
        state.sheetOpen = AgentJson.boolOf(input["sheetOpen"]) == true
        val visible = AgentFeedback.visibleOf(input)
        val bodies = mutableListOf<JsonElement>()
        val gateWrites = mutableListOf<JsonElement>()
        var ticketValid = true
        var toast: String? = null

        fun fill() {
            if (!state.sheetOpen && state.entryVisible(visible)) state.open()
            state.select(AgentJson.stringOf(input["selectedReason"]))
            state.supplement = AgentJson.stringOf(input["supplement"]).orEmpty()
        }

        fun submit() {
            state.submit()?.let { bodies += body(it) }
        }

        fun apply(r: AgentHttpResult) {
            if (!ticketValid) return
            val res = state.result(r)
            if (res.success) {
                toast = AgentDraftTexts.text(TOAST_KEY)
            } else {
                res.failure?.let { AgentFeedback.failureJson(o, it, owner, gateWrites) }
            }
        }
        val events = AgentJson.arrayOf(input["events"])
        if (AgentJson.boolOf(input["submitted"]) == true || events == null) {
            fill()
            submit()
        }
        if (events == null) {
            val res = AgentJson.objectOf(input["result"])
            if (res != null && state.inFlight) apply(AgentCancelPolicy.resultOf(res))
        } else {
            for (v in events) {
                val e = AgentJson.objectOf(v) ?: AgentJson.EMPTY
                when (AgentJson.stringOf(e["kindRaw"])) {
                    "submit" -> {
                        if (!state.sheetOpen && !state.inFlight && !state.reported) fill()
                        submit()
                    }

                    "result" -> {
                        apply(AgentCancelPolicy.resultOf(e))
                    }

                    "open" -> {
                        if (state.entryVisible(visible)) state.open()
                    }

                    "invalidate" -> {
                        ticketValid = false
                        state.close()
                    }

                    "history_reload" -> {
                        state.reset()
                    }

                    "system_back" -> {
                        state.close()
                    }
                }
            }
        }
        val check = compose(state.selected, state.supplement)
        o["requestCount"] = JsonPrimitive(state.requests)
        o["bodies"] = JsonArray(bodies)
        o["submitEnabled"] = JsonPrimitive(state.sheetOpen && check.validation == AgentReportCheck.OK && !state.inFlight)
        if (check.validation == AgentReportCheck.TOO_LONG) o["validationRaw"] = JsonPrimitive(AgentReportCheck.TOO_LONG)
        bodies.firstOrNull()?.let { b ->
            o["reasonCodePoints"] = JsonPrimitive(AgentJson.codePoints(AgentJson.stringOf(AgentJson.objectOf(b)?.get("reason")).orEmpty()))
        }
        o["sheetOpen"] = JsonPrimitive(state.sheetOpen)
        o["supplement"] = JsonPrimitive(state.supplement)
        o["reported"] = JsonPrimitive(state.reported)
        o["entryVisible"] = JsonPrimitive(state.entryVisible(visible))
        if (state.reported) o["label"] = JsonPrimitive(AgentDraftTexts.text(REPORTED_KEY))
        o["toast"] = toast?.let(::JsonPrimitive) ?: JsonNull
        o["reasonInlineError"] = JsonPrimitive(state.inlineError)
        o["stepUpRequests"] = JsonPrimitive(0)
        o["idempotencyKeyPresent"] = JsonPrimitive(false)
        o["persisted"] = JsonPrimitive(false)
        o["clearSessionId"] = JsonPrimitive(false)
        o["lateResultAccepted"] = JsonPrimitive(false)
        o["toastCountAfterInvalidation"] = JsonPrimitive(0)
        o["closeOtherSheet"] = JsonPrimitive(false)
        o["navigate"] = JsonPrimitive(false)
        o["gateWrites"] = JsonArray(gateWrites)
        return JsonObject(o)
    }

    const val TOAST_KEY = "agent.draft.report.toast"
    const val REPORTED_KEY = "agent.draft.report.reported"
    private const val JOINER_KEY = "agent.draft.report.joiner"
}

/** 一条回复的举报状态（半屏与「已举报」；只在内存，原因与补充说明不落盘、不进日志）。只在主线程使用。 */
class AgentReportState {
    var sheetOpen: Boolean = false
    var selected: String? = null
        private set
    var supplement: String = ""
    var inFlight: Boolean = false
        private set
    var reported: Boolean = false
    var inlineError: Boolean = false
        private set
    var requests: Int = 0
        private set

    fun entryVisible(replyVisible: Boolean): Boolean = replyVisible && !reported

    fun open() {
        if (reported) return
        sheetOpen = true
    }

    fun select(reason: String?) {
        selected = reason
        inlineError = false
    }

    /** 提交（单飞）：返回要发的 reason（null = 不发：未选、超长、在途、已关）。 */
    @Suppress("ReturnCount") // 逐项提前返回
    fun submit(): String? {
        if (!sheetOpen || inFlight || reported) return null
        val check = AgentReport.compose(selected, supplement)
        if (check.validation != AgentReportCheck.OK) return null
        inFlight = true
        requests += 1
        return check.reason
    }

    /** 结果（调用方已比过票据）。 */
    @Suppress("ReturnCount") // 逐项提前返回
    fun result(r: AgentHttpResult): AgentReportOutcome {
        if (!inFlight) return AgentReportOutcome(false, null)
        inFlight = false
        if (r.ok) {
            sheetOpen = false
            reported = true
            selected = null
            supplement = ""
            return AgentReportOutcome(true, null)
        }
        val fail = AgentFeedback.failure(AgentOperation.REPORT, r)
        if (fail.decision.closeSheet) sheetOpen = false
        inlineError = fail.decision.reasonInlineError
        return AgentReportOutcome(false, fail)
    }

    /** 【停止】同步作废在途的这一次提交（鸿蒙 R2-03）：解除在途，半屏与已选原因保留（用户可再提交）；迟到结果由控制器丢弃。 */
    fun abort() {
        inFlight = false
    }

    /** 系统返回、作废：只关本实例（不提交、不导航）。 */
    fun close() {
        sheetOpen = false
    }

    /** 历史重载 / 替换：「已举报」状态清空（内存状态，服务端覆盖写，G29）。 */
    fun reset() {
        reported = false
        inFlight = false
        sheetOpen = false
        inlineError = false
    }
}
