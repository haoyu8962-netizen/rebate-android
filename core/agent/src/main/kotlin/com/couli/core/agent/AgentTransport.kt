package com.couli.core.agent

import com.couli.generated.Routes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// APP-10b §2 第 4 条（F-X-04）：Reducer 外层的传输守卫（APP-10 的 StreamReducer 与旧向量不改，G11）。三端同名（鸿蒙 AgentTransport.ets）。
// - AgentSeqGuard：按帧 id 判序（所有事件，含 meta / suggestions / 终止帧；meta 必须是 id=1）。id 大于「已应用 id + 1」→ 缺号：本条结束为
//   incomplete（G36），之后连接保持但不再把帧交 Reducer，只等终止帧或断线，再由 AgentReconnect 拉历史替换本条（03 §7.2）；重复或更小的 id 不交付
//   （与 Reducer 的丢弃等价）。agent_stream_seq_gap 不发（G13）。
// - AgentStreamLimitPolicy：真实流的累计上限由 StreamReducer 判定（APP-10 A17：20000 码点 / 12 张卡 / 400 内容帧，超限 limited、不更新额度），
//   这里只是向量接线（驱动同一个 Reducer）。
// - AgentPublishGate：切 Tab / 被同栈页面覆盖时（G02，编排者补充第 2 条）流与计时照常、只是不把快照交给界面；回到可见时一次性应用。

object AgentSeqDecision {
    /** 按序：交 Reducer。 */
    const val DELIVER = "deliver"

    /** 重复或更小的 id：不交付。 */
    const val DUPLICATE = "duplicate"

    /** 这一帧发现缺号：本条 incomplete。 */
    const val GAP = "gap"

    /** 缺号之后到达的帧：不交 Reducer（终止帧只用来触发重载）。 */
    const val AFTER_GAP = "after_gap"
}

/** 一条流的序号守卫（每条流一个实例，只在调用方线程使用）。 */
class AgentSeqGuard {
    private var last = 0L
    private var gapped = false

    /** 交给 Reducer 的帧 id（测试与诊断用；只有数字，不含内容）。 */
    val delivered: MutableList<Long> = mutableListOf()

    val incomplete: Boolean get() = gapped

    val lastApplied: Long get() = last

    @Suppress("ReturnCount") // 缺号后、首帧、重复、按序、缺号依次返回
    fun check(
        id: Long,
        event: String,
    ): String {
        if (gapped) return AgentSeqDecision.AFTER_GAP
        if (last == 0L) {
            if (id == 1L && event == AgentEvent.META) {
                last = 1
                delivered += id
                return AgentSeqDecision.DELIVER
            }
            gapped = true
            return AgentSeqDecision.GAP
        }
        if (id <= last) return AgentSeqDecision.DUPLICATE
        if (id == last + 1) {
            last = id
            delivered += id
            return AgentSeqDecision.DELIVER
        }
        gapped = true
        return AgentSeqDecision.GAP
    }

    companion object {
        fun isTerminal(event: String): Boolean = event == AgentEvent.DONE || event == AgentEvent.ERROR

        /**
         * 向量接线（seqGap 组）：事件序列 {id, eventRaw}（eventRaw=eof 表示连接正常结束）→ 本条状态、交给 Reducer 的 id、重载时机。驱动的是控制器
         * 同一个 [AgentSeqGuard]；重载次数按「缺号后收到终止帧或断线」计（真正的重载由 [AgentReconnect] 执行）。
         */
        @Suppress("UnusedParameter", "CyclomaticComplexMethod", "LoopWithTooManyJumpStatements") // nowMs 与向量入口同形；逐事件判定
        fun of(
            input: JsonObject,
            nowMs: Long,
        ): JsonObject {
            val guard = AgentSeqGuard()
            var reloadAfter = RELOAD_NONE
            var terminal: String? = null
            var open = true
            for (v in AgentJson.arrayOf(input["events"]).orEmpty()) {
                val e = AgentJson.objectOf(v) ?: AgentJson.EMPTY
                val event = AgentJson.stringOf(e["eventRaw"]).orEmpty()
                if (event == EOF) {
                    open = false
                    if (guard.incomplete && reloadAfter != RELOAD_TERMINAL) {
                        reloadAfter = RELOAD_DISCONNECT
                    } else if (terminal == null) {
                        terminal = EOF
                    }
                    break
                }
                val id = AgentJson.intOf(e["id"]) ?: 0L
                val decision = guard.check(id, event)
                if (decision == AgentSeqDecision.DELIVER && isTerminal(event)) {
                    terminal = event
                    open = false
                    break
                }
                if ((decision == AgentSeqDecision.GAP || decision == AgentSeqDecision.AFTER_GAP) && isTerminal(event)) {
                    reloadAfter = RELOAD_TERMINAL
                    open = false
                    break
                }
            }
            if (guard.incomplete && reloadAfter == RELOAD_NONE) reloadAfter = RELOAD_WAITING
            val status =
                when {
                    guard.incomplete -> AgentStatus.INCOMPLETE
                    terminal == AgentEvent.DONE -> AgentStatus.DONE
                    terminal == AgentEvent.ERROR -> AgentStatus.ERROR
                    terminal == EOF -> AgentStatus.DISCONNECTED
                    else -> AgentStatus.STREAMING
                }
            return AgentJson.obj(
                "statusRaw" to status,
                "deliveredIds" to guard.delivered.toList(),
                "reloadAfterRaw" to reloadAfter,
                "reloads" to if (reloadAfter == RELOAD_TERMINAL || reloadAfter == RELOAD_DISCONNECT) 1 else 0,
                "connectionOpen" to open,
                "updatesQuota" to false,
                "analytics" to JsonArray(emptyList()),
            )
        }

        private const val EOF = "eof"
        private const val RELOAD_NONE = "none"
        private const val RELOAD_TERMINAL = "terminal"
        private const val RELOAD_DISCONNECT = "disconnect"
        private const val RELOAD_WAITING = "waiting"
    }
}

/** 真实流累计上限的向量接线（sseBytes 组 modeRaw=limits）：已有计数 + 到达的一帧 → 是否接受、本轮状态。驱动 [StreamReducer.frame]。 */
object AgentStreamLimitPolicy {
    @Suppress("UnusedParameter", "LongMethod") // nowMs 与向量入口同形
    fun of(
        input: JsonObject,
        nowMs: Long,
    ): JsonObject {
        val incoming = AgentJson.stringOf(input["incomingRaw"]) ?: return AgentJson.obj("analytics" to JsonArray(emptyList()))
        val cards = (AgentJson.intOf(input["cardCount"]) ?: 0L).toInt()
        val frames = (AgentJson.intOf(input["contentFrameCount"]) ?: 0L).toInt()
        val points = (AgentJson.intOf(input["textCodePoints"]) ?: 0L).toInt()
        val before =
            AgentStreamState(
                status = AgentStatus.STREAMING,
                metaSeen = true,
                lastSeq = 1,
                textPoints = points,
                contentFrames = frames,
                cards =
                    (1..cards).map {
                        AgentCard(
                            AgentCardKind.FALLBACK,
                            PROBE_TYPE,
                            "c$it",
                            "-",
                            null,
                            null,
                            AgentCardOrigin.STREAM,
                            AgentJson.EMPTY,
                        )
                    },
            )
        val frame =
            if (incoming == INCOMING_CARD) {
                AgentFrame(
                    AgentFrameResult.OK,
                    AgentEvent.CARD,
                    2,
                    AgentJson.obj(
                        "seq" to 2,
                        "card_id" to "c${cards + 1}",
                        "type" to PROBE_TYPE,
                        "schema_version" to 1,
                        "data" to AgentJson.EMPTY,
                        "fallback_text" to "-",
                    ),
                )
            } else {
                AgentFrame(
                    AgentFrameResult.OK,
                    AgentEvent.TEXT_DELTA,
                    2,
                    AgentJson.obj(
                        "seq" to 2,
                        "delta" to AgentJson.stringOf(input["text"]).orEmpty(),
                    ),
                )
            }
        val after = StreamReducer.frame(before, frame, Routes.all)
        val ok = after.status != AgentStatus.LIMITED
        val out =
            linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
                "analytics" to JsonArray(emptyList()),
                "accept" to JsonPrimitive(ok),
                "statusRaw" to JsonPrimitive(if (ok) AgentStatus.STREAMING else AgentStatus.LIMITED),
            )
        if (!ok) {
            out["close"] = JsonPrimitive(true)
            out["updatesQuota"] = JsonPrimitive(after.updatesQuota)
        }
        return JsonObject(out)
    }

    private const val INCOMING_CARD = "card"

    /** 未注册的卡片类型（只按兜底计数，不碰任何已注册卡的严格校验）。 */
    private const val PROBE_TYPE = "limit_probe"
}

/**
 * 隐藏期间的界面更新闸门（G02）：隐藏时 [offer] 只记「有变化」，回到可见时一次性应用一次；读屏播报、弹层、导航由控制器另按可见性判断。只在调用方线程使用。
 */
class AgentPublishGate {
    private var visible = false
    private var pending = false

    /** 隐藏期间被挡下的界面更新次数（测试观测）。 */
    var suppressed: Int = 0
        private set

    /** 真正交给界面的次数。 */
    var applied: Int = 0
        private set

    val isVisible: Boolean get() = visible

    /** 有变化：可见时返回 true（立即应用），隐藏时记下、返回 false。 */
    fun offer(): Boolean {
        if (visible) {
            applied += 1
            return true
        }
        pending = true
        suppressed += 1
        return false
    }

    /** 可见性变化：变为可见且隐藏期间有变化时返回 true（应用一次）。 */
    @Suppress("ReturnCount") // 逐项提前返回
    fun setVisible(value: Boolean): Boolean {
        if (visible == value) return false
        visible = value
        if (value && pending) {
            pending = false
            applied += 1
            return true
        }
        return false
    }

    /** 隐藏期间有没应用的变化。 */
    val hasPending: Boolean get() = pending
}
