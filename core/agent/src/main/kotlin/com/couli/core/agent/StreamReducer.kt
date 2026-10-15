package com.couli.core.agent

import com.couli.generated.RouteSpec
import com.couli.generated.Routes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// APP-10 §2 第 5 条：一条 assistant 消息的流归约（纯函数 (state, input) → state；03 §7.2，G04–G08、G13、G26）。
// 输入：frame（已解码）、ping、eof（连接正常结束但没有终止帧）、timeout（30 秒没有任何一行）、localCancel（用户【停止】）、historyCard。
// 规则（G08）：以帧 id 为准对所有事件判序，id ≤ lastSeq 丢弃；meta 必须是第一帧（之前的帧丢弃）；没有 meta 就收到终止帧 → error 态；
// 终止帧（done / error）之后、本地结束（localCancel / eof / timeout）之后的一切丢弃；tool.status 的 end / failed 更新同名最近一条进行中的
// 条目（没有就新开一条已结束的）；suggestions 以最后一次为准。duplicate=true 的流不并入消息内容、不更新额度（G26）。
// 同一轮里同 card_id 后到的卡丢弃、保留第一张，只推进序号（03 §7.2，Android R1-05）。输出上限（02 §9.2、03 §7.2，Android R1-04）：同一轮累计文本
// 超过 20000 个码点、卡片超过 12 张、内容帧（text.delta + card + suggestions）超过 400 帧 → 超限的那一帧不并入，本地结束为 limited（不等服务端的
// done(limit)）；单帧 data 超过 64 KiB 由 AgentSse 截停、控制器按 limited 结束。
// Reducer 不读时钟、不读身份；票据比较在控制器层（brief §0 第 3 条）。

object AgentStatus {
    const val IDLE = "idle"
    const val STREAMING = "streaming"
    const val DONE = "done"
    const val CANCELLED = "cancelled"
    const val ERROR = "error"
    const val DISCONNECTED = "disconnected"

    /** 本地按输出上限或单帧上限结束（03 §7.2；规范化约定，向量未覆盖，rule-gaps A16）。 */
    const val LIMITED = "limited"

    /** 缺号（APP-10b G11、G36）：本地结束，等终止帧或断线后拉历史替换；显示同「连接中断」但不给【重试】。 */
    const val INCOMPLETE = "incomplete"

    /** 只由 historyCard 导入的快照（规范化约定，不是新的业务状态，向量 gaps G04）。 */
    const val HISTORY = "history"
}

object AgentInputKind {
    const val FRAME = "frame"
    const val PING = "ping"
    const val EOF = "eof"
    const val TIMEOUT = "timeout"
    const val LOCAL_CANCEL = "localCancel"
    const val HISTORY_CARD = "historyCard"
}

data class AgentTool(
    val tool: String,
    val phase: String,
    val displayText: String,
) {
    val running: Boolean get() = phase == PHASE_START

    companion object {
        const val PHASE_START = "start"
        const val PHASE_FAILED = "failed"
    }
}

data class AgentQuickReply(
    val text: String,
    val sendText: String,
)

/** 远端终止帧（done / error）原样保留。 */
data class AgentTerminal(
    val event: String,
    val data: JsonObject,
) {
    val code: Long? get() = if (event == AgentEvent.ERROR) AgentJson.intOf(data["code"]) else null

    val finishReason: String? get() = if (event == AgentEvent.DONE) AgentJson.stringOf(data["finish_reason"]) else null
}

/** 一条 assistant 消息的状态（不可变：reduce 每次返回新对象）。[quotaLeft]：本轮实际可用于更新额度的 done.quota_left（duplicate 永远 null，G26、G28）。 */
data class AgentStreamState(
    val status: String = AgentStatus.IDLE,
    val metaSeen: Boolean = false,
    val duplicate: Boolean = false,
    val runId: String? = null,
    val messageId: String? = null,
    val aiLabel: String? = null,
    val modelLabel: String? = null,
    val text: String = "",
    val tools: List<AgentTool> = emptyList(),
    val cards: List<AgentCard> = emptyList(),
    val suggestions: List<AgentQuickReply> = emptyList(),
    val rawSuggestions: List<JsonObject> = emptyList(),
    val terminal: AgentTerminal? = null,
    val lastSeq: Long = 0,
    val quotaLeft: Long? = null,
    /** 本轮已并入的文本码点数（输出上限）。 */
    val textPoints: Int = 0,
    /** 本轮已接收的内容帧数（text.delta + card + suggestions，输出上限）。 */
    val contentFrames: Int = 0,
) {
    /** 已结束（远端终止帧、本地停止、断线）：之后的一切输入丢弃。 */
    val ended: Boolean
        get() =
            status == AgentStatus.DONE || status == AgentStatus.CANCELLED || status == AgentStatus.ERROR ||
                status == AgentStatus.DISCONNECTED || status == AgentStatus.LIMITED || status == AgentStatus.INCOMPLETE

    val mergeIntoMessage: Boolean get() = !duplicate

    val updatesQuota: Boolean get() = quotaLeft != null

    /** 不显示金额的调试描述（toString 不带卡片内容与文字，避免金额、对话进日志，G15）。 */
    override fun toString(): String = "AgentStreamState(status=$status, lastSeq=$lastSeq, cards=${cards.size})"
}

object StreamReducer {
    fun initial(): AgentStreamState = AgentStreamState()

    /** 向量与测试用的 JSON 输入：`{kindRaw:"frame", frame}`、`{kindRaw:"ping|eof|timeout|localCancel"}`、`{kindRaw:"historyCard", card}`。 */
    fun reduce(
        state: AgentStreamState,
        input: JsonObject,
        routes: Map<String, RouteSpec> = Routes.all,
    ): AgentStreamState =
        when (AgentJson.stringOf(input["kindRaw"])) {
            AgentInputKind.FRAME -> frame(state, AgentFrame.ofJson(AgentJson.objectOf(input["frame"])), routes)
            AgentInputKind.PING -> state
            AgentInputKind.EOF, AgentInputKind.TIMEOUT -> disconnected(state)
            AgentInputKind.LOCAL_CANCEL -> localCancel(state)
            AgentInputKind.HISTORY_CARD -> AgentJson.objectOf(input["card"])?.let { historyCard(state, it, routes) } ?: state
            else -> state
        }

    /** eof / timeout：还没结束的消息记为 disconnected（G33：给【重试】）。 */
    fun disconnected(state: AgentStreamState): AgentStreamState = if (state.ended) state else state.copy(status = AgentStatus.DISCONNECTED)

    /** 超过输出上限或单帧上限（03 §7.2）：本地结束为 limited，之后的一切丢弃。 */
    fun limited(state: AgentStreamState): AgentStreamState = if (state.ended) state else state.copy(status = AgentStatus.LIMITED)

    /** 用户【停止】：立即 cancelled，之后到达的帧（含服务端的 done(cancelled)）一律丢弃。 */
    fun localCancel(state: AgentStreamState): AgentStreamState = if (state.ended) state else state.copy(status = AgentStatus.CANCELLED)

    /** 历史来源的卡（G02、G13）：只接受卡片；收益卡一律按历史形状处理。不参加流判序。 */
    fun historyCard(
        state: AgentStreamState,
        card: JsonObject,
        routes: Map<String, RouteSpec> = Routes.all,
    ): AgentStreamState {
        val parsed = AgentCardDecoder.parse(card, AgentCardOrigin.HISTORY, routes)
        if (parsed.kind == AgentCardKind.INVALID) return state
        return state.copy(
            status = if (state.status == AgentStatus.IDLE) AgentStatus.HISTORY else state.status,
            cards = state.cards + parsed,
        )
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod") // 结束、乱序、meta 之前、各事件依次处理
    fun frame(
        state: AgentStreamState,
        frame: AgentFrame,
        routes: Map<String, RouteSpec> = Routes.all,
    ): AgentStreamState {
        // 终止之后的一切、未知 event、不合 schema 的帧：丢弃，不推进 lastSeq
        if (state.ended || frame.result != AgentFrameResult.OK) return state
        if (frame.id <= state.lastSeq) return state
        val d = frame.data
        if (!state.metaSeen) {
            return when (frame.event) {
                AgentEvent.META -> {
                    state.copy(
                        metaSeen = true,
                        status = AgentStatus.STREAMING,
                        duplicate = AgentJson.boolOf(d["duplicate"]) == true,
                        runId = AgentJson.stringOf(d["run_id"]),
                        messageId = AgentJson.stringOf(d["message_id"]),
                        aiLabel = AgentJson.stringOf(d["ai_label"]),
                        modelLabel = AgentJson.stringOf(d["model_label"]),
                        lastSeq = frame.id,
                    )
                }

                // 没有 meta 就收到终止帧（G08）：消息记为 error 态，终止帧原样保留，不更新额度
                AgentEvent.DONE, AgentEvent.ERROR -> {
                    state.copy(status = AgentStatus.ERROR, terminal = AgentTerminal(frame.event, d), lastSeq = frame.id)
                }

                else -> {
                    state
                }
            }
        }
        val counted = frame.event == AgentEvent.TEXT_DELTA || frame.event == AgentEvent.CARD || frame.event == AgentEvent.SUGGESTIONS
        // 内容帧上限：第 401 帧不并入，本地结束
        if (counted && state.contentFrames >= CONTENT_FRAMES_MAX) return limited(state.copy(lastSeq = frame.id))
        val seq = state.copy(lastSeq = frame.id, contentFrames = state.contentFrames + if (counted) 1 else 0)
        return when (frame.event) {
            // 第二个 meta：不认（不改显示字段、不推进判序）
            AgentEvent.META -> {
                state
            }

            AgentEvent.TEXT_DELTA -> {
                val delta = AgentJson.stringOf(d["delta"]).orEmpty()
                val points = state.textPoints + delta.codePointCount(0, delta.length)
                when {
                    state.duplicate -> seq

                    // 累计文本上限：超限的这一段不并入，本地结束
                    points > TEXT_POINTS_MAX -> limited(seq)

                    else -> seq.copy(text = state.text + delta, textPoints = points)
                }
            }

            AgentEvent.TOOL_STATUS -> {
                if (state.duplicate) seq else seq.copy(tools = tool(state.tools, d))
            }

            AgentEvent.CARD -> {
                val card = AgentCardDecoder.parse(d, AgentCardOrigin.STREAM, routes)
                when {
                    state.duplicate || card.kind == AgentCardKind.INVALID -> seq

                    // 同 card_id 后到的卡丢弃，保留第一张（只推进序号）
                    state.cards.any { it.cardId == card.cardId } -> seq

                    // 卡片上限：第 13 张不并入，本地结束
                    state.cards.size >= CARDS_MAX -> limited(seq)

                    else -> seq.copy(cards = state.cards + card)
                }
            }

            AgentEvent.SUGGESTIONS -> {
                if (state.duplicate) {
                    seq
                } else {
                    val raw = AgentJson.arrayOf(d["items"]).orEmpty().mapNotNull { AgentJson.objectOf(it) }
                    val replies =
                        raw.map { AgentQuickReply(AgentJson.stringOf(it["text"]).orEmpty(), AgentJson.stringOf(it["send_text"]).orEmpty()) }
                    seq.copy(rawSuggestions = raw, suggestions = replies)
                }
            }

            AgentEvent.DONE -> {
                val cancelled = AgentJson.stringOf(d["finish_reason"]) == FINISH_CANCELLED
                seq.copy(
                    terminal = AgentTerminal(frame.event, d),
                    status = if (cancelled) AgentStatus.CANCELLED else AgentStatus.DONE,
                    quotaLeft = if (state.duplicate) null else AgentJson.intOf(d["quota_left"]),
                )
            }

            AgentEvent.ERROR -> {
                seq.copy(terminal = AgentTerminal(frame.event, d), status = AgentStatus.ERROR)
            }

            else -> {
                state
            }
        }
    }

    private const val FINISH_CANCELLED = "cancelled"

    /** 输出上限（02 §9.2「输出上限」，代理默认）。 */
    const val TEXT_POINTS_MAX = 20_000
    const val CARDS_MAX = 12
    const val CONTENT_FRAMES_MAX = 400

    /** tool.status（G08）：start 新开；end / failed 更新同名最近一条进行中的条目，没有就新开一条已结束的。 */
    private fun tool(
        tools: List<AgentTool>,
        data: JsonObject,
    ): List<AgentTool> {
        val next =
            AgentTool(
                AgentJson.stringOf(data["tool"]).orEmpty(),
                AgentJson.stringOf(data["phase"]).orEmpty(),
                AgentJson.stringOf(data["display_text"]).orEmpty(),
            )
        if (next.running) return tools + next
        val at = tools.indexOfLast { it.tool == next.tool && it.running }
        return if (at < 0) tools + next else tools.toMutableList().also { it[at] = next }
    }

    /** G32 规范化投影（三端比较用；不投影金额）。 */
    fun normalize(state: AgentStreamState): JsonObject =
        JsonObject(
            linkedMapOf(
                "status" to JsonPrimitive(state.status),
                "duplicate" to JsonPrimitive(state.duplicate),
                "text" to JsonPrimitive(state.text),
                "tools" to
                    JsonArray(
                        state.tools.map {
                            JsonObject(
                                linkedMapOf(
                                    "tool" to JsonPrimitive(it.tool),
                                    "phase" to JsonPrimitive(it.phase),
                                    "displayText" to JsonPrimitive(it.displayText),
                                ),
                            )
                        },
                    ),
                "cards" to
                    JsonArray(
                        state.cards.map {
                            JsonObject(
                                linkedMapOf(
                                    "cardId" to JsonPrimitive(it.cardId),
                                    "type" to JsonPrimitive(it.type),
                                    "kind" to JsonPrimitive(it.kind),
                                    "fallbackText" to if (it.kind == AgentCardKind.FALLBACK) JsonPrimitive(it.fallbackText) else JsonNull,
                                    "blockedRoute" to (it.blockedRoute?.let(::JsonPrimitive) ?: JsonNull),
                                ),
                            )
                        },
                    ),
                "suggestions" to JsonArray(state.rawSuggestions),
                "terminal" to
                    (
                        state.terminal?.let {
                            JsonObject(linkedMapOf("event" to JsonPrimitive(it.event), "data" to it.data))
                        } ?: JsonNull
                    ),
                "lastSeq" to JsonPrimitive(state.lastSeq),
                "quotaLeft" to (state.quotaLeft?.let(::JsonPrimitive) ?: JsonNull),
                "mergeIntoMessage" to JsonPrimitive(state.mergeIntoMessage),
                "updatesQuota" to JsonPrimitive(state.updatesQuota),
            ),
        )
}
