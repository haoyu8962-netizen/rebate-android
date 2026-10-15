package com.couli.core.agent

import com.couli.generated.Routes
import kotlin.math.roundToLong
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// APP-10b §2 第 6 条（F-X-04）：断线、缺号、duplicate、cancel 30505 之后的历史重载（纯策略状态机；控制器负责真正的请求与计时）。三端同名。
// - 断线本身不自动请求、不自动重连流、不自动重发（03 §7.2，brief §0 第 8 条）：只有用户点【重试】才先拉历史首页。
// - 缺号（终止帧或断线之后）、duplicate 收尾（done / 非 30506、非 10004 的 error）、cancel 30505：自动拉一次历史替换本条。
// - 失败按 1 / 2 / 4 / 8 秒（含首次共 5 次，G38、编排者补充第 6 条；抖动 [0.5×, 1.5×] 均匀分布，随机数可注入）自动重试；这些请求关掉 ApiClient 的
//   GET 自动重试（G35，不叠成 10 次）。App 在后台不发起任何重试：触发时在后台直接只留【重试】，退避或请求在途时进后台就停（回前台不续，鸿蒙 R1-08）。
// - 拉到后（G07）：本轮在历史里 → 用历史替换（历史 finish_reason=null 且没有 run_id 按已结束渲染，编排者补充第 3 条，不给【停止】、不发 cancel）；
//   本轮不在历史里：用户【重试】→ 发送闸门与同意闸门都放行时以同一 client_msg_id 重发，否则只留【重试】；缺号 → 只留【重试】；duplicate / 30505 →
//   不改。历史 30504 → 「这个对话已结束」+【新对话】并清本地会话；AI 10004 → 锁同意闸门、不弹框；42901 → 写读取闸门、只留【重试】（G58）。
// - duplicate 30506 → 不自动轮询，显示 error.30506 +【刷新】（G40），发送闸门禁用 5 秒；duplicate 流内 10004 → 先锁同意闸门、不补拉（G24）。

object AgentReloadTrigger {
    const val DISCONNECT = "disconnect"
    const val SEQ_GAP = "seq_gap"
    const val DUPLICATE_DONE = "duplicate_done"
    const val CANCEL_30505 = "cancel_30505"

    /** 「回复中」/ duplicate 30506 的【刷新】（用户点才拉一次）。 */
    const val REFRESH = "refresh"
}

object AgentReloadAction {
    const val REPLACE = "replace"
    const val RESEND = "resend_same_id"
    const val RETRY_ONLY = "retry_button_only"
    const val PAUSED = "paused_in_background"
    const val CONSENT = "consent_required"
    const val NEW_CHAT = "new_chat"

    /** 历史里没有可替换的内容：本条保持原样。 */
    const val KEEP = "keep"
    const val REFRESH_BUTTON = "refresh_button"
}

/** 一次历史请求的结论（控制器先比票据、再用 [AgentHistoryMerge.match] 判 matched）。 */
class AgentReloadOutcome(
    val kind: String,
    val matched: Boolean,
    val finishReason: String?,
    val code: Int,
    val consentType: String?,
) {
    companion object {
        const val SUCCESS = "success"
        const val ENVELOPE = "envelope"
        const val NETWORK = "network_error"
    }
}

/** 状态机的下一步。 */
@Suppress("LongParameterList") // 一步的结论逐项
class AgentReloadStep(
    val kind: String,
    val delayMs: Long,
    val action: String,
    /** 最终本条的规范化状态（replace 为历史状态；只留【重试】为 disconnected；其余空串 = 不改）。 */
    val status: String,
    val clearSessionId: Boolean = false,
    val lockAiGate: Boolean = false,
    val paused: Boolean = false,
) {
    companion object {
        const val REQUEST = "request"
        const val WAIT = "wait"
        const val FINAL = "final"

        fun request(): AgentReloadStep = AgentReloadStep(REQUEST, 0, "", "")

        fun final(
            action: String,
            status: String = "",
        ): AgentReloadStep = AgentReloadStep(FINAL, 0, action, status)
    }
}

/** 一次重载流程（每次触发一个实例；只在调用方线程使用）。 */
class AgentReconnect(
    val trigger: String,
    val userRetry: Boolean,
    private val random: () -> Double,
) {
    var attempts: Int = 0
        private set

    var isWaiting: Boolean = false
        private set

    var isFinished: Boolean = false
        private set

    /** 开始：同意闸门锁住 → 不请求；在后台 → 不请求（只留【重试】）；否则发第一次。 */
    @Suppress("ReturnCount") // 逐项提前返回
    fun begin(
        foreground: Boolean,
        consentOpen: Boolean,
    ): AgentReloadStep {
        if (!consentOpen) return finish(AgentReloadStep.final(AgentReloadAction.CONSENT))
        if (!foreground) {
            val action = if (userRetry) AgentReloadAction.RETRY_ONLY else AgentReloadAction.PAUSED
            return finish(AgentReloadStep(AgentReloadStep.FINAL, 0, action, AgentStatus.DISCONNECTED, paused = true))
        }
        attempts = 1
        return AgentReloadStep.request()
    }

    /** 一次请求的结论。[sendGateOpen] / [consentOpen]：此刻发送闸门与同意闸门（只在「没受理 → 同键重发」时用）。 */
    @Suppress("ReturnCount") // 已结束、成功、外壳码、退避依次返回
    fun onOutcome(
        o: AgentReloadOutcome,
        sendGateOpen: Boolean,
        consentOpen: Boolean,
    ): AgentReloadStep {
        if (isFinished) return AgentReloadStep.final(AgentReloadAction.KEEP)
        if (o.kind == AgentReloadOutcome.SUCCESS) return finish(decide(o, sendGateOpen, consentOpen))
        if (o.kind == AgentReloadOutcome.ENVELOPE) {
            when (o.code) {
                AgentErrorCodes.ROUND_LIMIT -> {
                    return finish(
                        AgentReloadStep(AgentReloadStep.FINAL, 0, AgentReloadAction.NEW_CHAT, AgentStatus.DONE, clearSessionId = true),
                    )
                }

                AgentErrorCodes.CONSENT -> {
                    // 历史不是发送类：先锁同意闸门（ai_third_party 才锁；是否弹框由控制器决定——历史一律不弹）
                    return finish(
                        AgentReloadStep(
                            AgentReloadStep.FINAL,
                            0,
                            AgentReloadAction.CONSENT,
                            "",
                            lockAiGate = o.consentType == AgentErrorCodes.CONSENT_AI,
                        ),
                    )
                }

                AgentErrorCodes.RATE_LIMITED -> {
                    return finish(AgentReloadStep.final(AgentReloadAction.RETRY_ONLY, AgentStatus.DISCONNECTED))
                }
            }
        }
        if (attempts >= ATTEMPTS) return finish(AgentReloadStep.final(AgentReloadAction.RETRY_ONLY, AgentStatus.DISCONNECTED))
        val base = DELAYS_MS[attempts - 1]
        val r = runCatching { random() }.getOrDefault(HALF).coerceIn(0.0, 1.0)
        isWaiting = true
        return AgentReloadStep(AgentReloadStep.WAIT, (base * (HALF + r)).roundToLong(), "", "")
    }

    /** 退避计时到：在前台就发下一次；在后台按「后台不重试」只留【重试】。 */
    @Suppress("ReturnCount") // 逐项提前返回
    fun onTimer(foreground: Boolean): AgentReloadStep {
        if (isFinished || !isWaiting) return AgentReloadStep.final(AgentReloadAction.KEEP)
        isWaiting = false
        if (!foreground) {
            return finish(
                AgentReloadStep(AgentReloadStep.FINAL, 0, AgentReloadAction.RETRY_ONLY, AgentStatus.DISCONNECTED, paused = true),
            )
        }
        attempts += 1
        return AgentReloadStep.request()
    }

    /** 退避期间进后台：停（控制器取消计时），回前台不续，只留【重试】。 */
    fun onBackground(): AgentReloadStep? {
        if (isFinished || !isWaiting) return null
        isWaiting = false
        return finish(AgentReloadStep(AgentReloadStep.FINAL, 0, AgentReloadAction.RETRY_ONLY, AgentStatus.DISCONNECTED, paused = true))
    }

    /**
     * 进入后台（鸿蒙 R1-08）：暂停整个重载流程——不论在退避等待还是历史请求在途，都立即结束为「只留【重试】」（控制器取消计时、丢弃在途请求的结果）；
     * 回前台不续，由用户再点。已结束的返回 null。
     */
    fun pause(): AgentReloadStep? {
        if (isFinished) return null
        return finish(AgentReloadStep(AgentReloadStep.FINAL, 0, AgentReloadAction.RETRY_ONLY, AgentStatus.DISCONNECTED, paused = true))
    }

    @Suppress("ReturnCount") // 匹配、用户重试、缺号 / 断线、其余
    private fun decide(
        o: AgentReloadOutcome,
        sendGateOpen: Boolean,
        consentOpen: Boolean,
    ): AgentReloadStep {
        if (o.matched) {
            // G25（Android R3-02）：cancel 30505 拉到的历史里原轮以 cancelled 结束 → 不替换（保留本地正文、卡片、动作状态）；只有非 cancelled 的匹配才替换
            if (trigger == AgentReloadTrigger.CANCEL_30505 &&
                o.finishReason == FINISH_CANCELLED
            ) {
                return AgentReloadStep.final(AgentReloadAction.KEEP)
            }
            return AgentReloadStep.final(AgentReloadAction.REPLACE, AgentHistory.statusOf(o.finishReason))
        }
        if (trigger == AgentReloadTrigger.DISCONNECT && userRetry) {
            return if (sendGateOpen && consentOpen) {
                AgentReloadStep.final(AgentReloadAction.RESEND)
            } else {
                AgentReloadStep.final(AgentReloadAction.RETRY_ONLY, AgentStatus.DISCONNECTED)
            }
        }
        if (trigger == AgentReloadTrigger.SEQ_GAP || trigger == AgentReloadTrigger.DISCONNECT) {
            return AgentReloadStep.final(AgentReloadAction.RETRY_ONLY, AgentStatus.DISCONNECTED)
        }
        return AgentReloadStep.final(AgentReloadAction.KEEP)
    }

    private fun finish(step: AgentReloadStep): AgentReloadStep {
        isFinished = true
        isWaiting = false
        return step
    }

    companion object {
        /** G38：首次之后的退避（秒 1 / 2 / 4 / 8），含首次共 5 次。 */
        val DELAYS_MS: List<Long> = listOf(1_000L, 2_000L, 4_000L, 8_000L)
        const val ATTEMPTS = 5
        private const val HALF = 0.5

        /** 历史 finish_reason：用户停止（G25）。 */
        private const val FINISH_CANCELLED = "cancelled"

        /** duplicate 30506 / 发送 30506：发送闸门禁用 5 秒（APP-10 G24）。 */
        const val RUN_IN_PROGRESS_DISABLE_MS = AgentSendErrorPolicy.DEFAULT_DISABLE_MS

        /** duplicate 流收尾怎么办（03 §7.2、G24、G40）：done / 其他 error → reload；30506 → 【刷新】；10004 → 先锁同意闸门、不补拉。 */
        fun duplicateEnd(terminal: AgentTerminal?): String =
            when {
                terminal == null -> {
                    AgentReloadAction.RETRY_ONLY
                }

                terminal.event == AgentEvent.ERROR && terminal.code == AgentErrorCodes.CONSENT.toLong() -> {
                    AgentReloadAction.CONSENT
                }

                terminal.event == AgentEvent.ERROR && terminal.code == AgentErrorCodes.RUN_IN_PROGRESS.toLong() -> {
                    AgentReloadAction.REFRESH_BUTTON
                }

                else -> {
                    AgentReloadAction.REPLACE
                }
            }

        /** 向量接线（reconnect 组）：驱动同一个状态机（虚拟时钟、脚本化的历史结果与前后台事件），观察请求时刻与最终动作。 */
        @Suppress("UnusedParameter", "LongMethod", "CyclomaticComplexMethod", "ReturnCount", "LoopWithTooManyJumpStatements")
        fun of(
            input: JsonObject,
            nowMs: Long,
        ): JsonObject {
            val o =
                linkedMapOf<String, JsonElement>(
                    "updatesQuota" to JsonPrimitive(false),
                    "cancelRequests" to JsonPrimitive(0),
                    "apiGetRetries" to JsonPrimitive(0),
                    "showConsent" to JsonPrimitive(false),
                )
            var trigger = AgentJson.stringOf(input["triggerRaw"]) ?: AgentReloadTrigger.DISCONNECT
            if (AgentJson.boolOf(input["duplicate"]) == true) {
                when (duplicateEnd(terminalOf(input))) {
                    AgentReloadAction.CONSENT -> {
                        o["lockAiGate"] = JsonPrimitive(true)
                        o["historyRequestTimesMs"] = JsonArray(emptyList())
                        o["streamRequests"] = JsonPrimitive(0)
                        o["actionRaw"] = JsonPrimitive(AgentReloadAction.CONSENT)
                        return JsonObject(o)
                    }

                    AgentReloadAction.REFRESH_BUTTON -> {
                        o["historyRequestTimesMs"] = JsonArray(emptyList())
                        o["actionRaw"] = JsonPrimitive(AgentReloadAction.REFRESH_BUTTON)
                        o["sendDisableMs"] = JsonPrimitive(RUN_IN_PROGRESS_DISABLE_MS)
                        o["streamRequests"] = JsonPrimitive(0)
                        return JsonObject(o)
                    }
                }
                trigger = AgentReloadTrigger.DUPLICATE_DONE
            }
            val userRetry = AgentJson.boolOf(input["userRetry"]) == true
            if (trigger == AgentReloadTrigger.DISCONNECT && !userRetry) {
                // 断线本身：不自动拉历史、不重连、不重发，只给【重试】
                o["historyRequestTimesMs"] = JsonArray(emptyList())
                o["streamRequests"] = JsonPrimitive(0)
                o["actionRaw"] = JsonPrimitive(AgentReloadAction.RETRY_ONLY)
                return JsonObject(o)
            }
            val randoms =
                AgentJson
                    .arrayOf(
                        input["randomUnits"],
                    ).orEmpty()
                    .map { (it as? JsonPrimitive)?.content?.toDoubleOrNull() ?: HALF }
            var randomAt = 0
            val machine =
                AgentReconnect(trigger, userRetry) {
                    val r = randoms.getOrElse(randomAt) { HALF }
                    randomAt += 1
                    r
                }
            val results = AgentJson.arrayOf(input["historyResults"]).orEmpty()
            val lifecycle = AgentJson.arrayOf(input["lifecycle"]).orEmpty().mapNotNull { AgentJson.objectOf(it) }
            val sendGate = AgentJson.boolOf(input["sendGateOpen"]) != false
            val consent = AgentJson.boolOf(input["consentGateOpen"]) != false
            val times = mutableListOf<Long>()
            var now = 0L
            var step = machine.begin(AgentJson.boolOf(input["foreground"]) != false, consent)
            var guard = 0
            while (guard < GUARD && step.kind != AgentReloadStep.FINAL) {
                guard += 1
                if (step.kind == AgentReloadStep.REQUEST) {
                    times += now
                    val r = AgentJson.objectOf(results.getOrNull(machine.attempts - 1)) ?: AgentJson.EMPTY
                    step = machine.onOutcome(outcomeOf(r), sendGate, consent)
                    continue
                }
                // WAIT：退避期间有进后台事件就停
                val until = now + step.delayMs
                val background =
                    lifecycle.any { e ->
                        val at = AgentJson.intOf(e["atMs"]) ?: 0L
                        AgentJson.stringOf(e["kindRaw"]) == "background" && at >= now && at < until
                    }
                if (background) {
                    step = machine.onBackground() ?: AgentReloadStep.final(AgentReloadAction.KEEP)
                    break
                }
                now = until
                step = machine.onTimer(true)
            }
            o["historyRequestTimesMs"] = AgentJson.element(times)
            o["attempts"] = JsonPrimitive(machine.attempts)
            o["actionRaw"] = JsonPrimitive(step.action)
            if (step.status.isNotEmpty()) o["statusRaw"] = JsonPrimitive(step.status)
            o["pendingTimers"] = JsonPrimitive(0)
            o["pausedInBackground"] = JsonPrimitive(step.paused)
            o["clearSessionId"] = JsonPrimitive(step.clearSessionId)
            o["lockAiGate"] = JsonPrimitive(step.lockAiGate)
            val resend = step.action == AgentReloadAction.RESEND
            o["streamRequests"] = JsonPrimitive(if (resend) 1 else 0)
            if (resend) o["clientMsgId"] = input["clientMsgId"] ?: kotlinx.serialization.json.JsonNull
            return JsonObject(o)
        }

        private const val GUARD = 20

        private fun outcomeOf(r: JsonObject): AgentReloadOutcome =
            when (AgentJson.stringOf(r["resultRaw"]) ?: AgentReloadOutcome.NETWORK) {
                AgentReloadOutcome.SUCCESS -> {
                    AgentReloadOutcome(
                        AgentReloadOutcome.SUCCESS,
                        AgentJson.boolOf(r["matched"]) == true,
                        AgentJson.stringOf(r["finishReasonRaw"]),
                        0,
                        null,
                    )
                }

                AgentReloadOutcome.ENVELOPE -> {
                    AgentReloadOutcome(
                        AgentReloadOutcome.ENVELOPE,
                        false,
                        null,
                        (AgentJson.intOf(r["code"]) ?: AgentErrorCodes.GENERIC.toLong()).toInt(),
                        AgentJson.stringOf(r["consentTypeRaw"]),
                    )
                }

                else -> {
                    AgentReloadOutcome(AgentReloadOutcome.NETWORK, false, null, -1, null)
                }
            }

        /** duplicate 向量的终止帧：fixture 两帧经同一解码器与 Reducer 得出；没有 fixture 时用 terminalRaw + code。 */
        private fun terminalOf(input: JsonObject): AgentTerminal? {
            val frames = AgentJson.arrayOf(input["fixtureFrames"])
            if (frames != null) {
                var state = StreamReducer.initial()
                for (f in frames) {
                    val item = AgentSse.fromNdjsonLine(f.toString())
                    state = StreamReducer.frame(state, AgentFrameDecoder.frame(item), Routes.all)
                }
                return state.terminal
            }
            val data = AgentJson.intOf(input["code"])?.let { AgentJson.obj("code" to it) } ?: AgentJson.EMPTY
            val event = if (AgentJson.stringOf(input["terminalRaw"]) == "done") AgentEvent.DONE else AgentEvent.ERROR
            return AgentTerminal(event, data)
        }
    }
}
