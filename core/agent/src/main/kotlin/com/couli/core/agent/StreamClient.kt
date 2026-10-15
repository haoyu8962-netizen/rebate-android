package com.couli.core.agent

import kotlinx.serialization.json.JsonObject

// APP-10 §2 第 4 条：StreamClient（界面无关）。一次 run 一个实例：发 sendAgentMessage → 端口按响应 Content-Type 分流（APP-10b G19：text/event-stream 且 2xx →
// 响应头 + 原始字节块；其他 → 统一外壳 JSON 结果 onEnvelope）→ 字节经 AgentSse.feedBytes（解码前按 UTF-8 字节计上限，StreamUtf8Decoder 只解码一次）→
// 每一帧连同发起时的 run 票据交给控制器（控制器先比票据）。
// - 30 秒内没收到任何完整的一行（含 `: ping` 与空行）→ 断线（G33：从请求发出起算，只有完整的 SSE 行才重置，响应头、半行、JSON 正文分块都不延长期限，
//   鸿蒙 R1-09、H26）；另有单轮总时长上限 90 秒（APP-10b G16、编排者补充第 6 条：持续 ping 也在 90 秒结束），到时按断线收尾。计时器绑定本实例。
// - 响应头的 X-Trace-Id 随每个事件交回（流内 error 帧没有 trace_id，5xxxx 文案的 trace6 用它，鸿蒙 R1-07）。
// - cancel()：同步关闭连接、停计时、本实例作废；之后到达的任何字节一律丢弃（不解析、不交付；[delivered] / [dropped] 供抓包断言「作废后 0 帧交付」）。
// APP-10b（F-X-04）：真实端口 ApiAgentContractPort 经 ApiClient 的流式通道（OkHttp ResponseBody.source() 逐块读）实现同一端口；Debug / Staging 的本机
// 假服务在同一个传输入口按 DebugMockGate 换入。只在主线程使用（端口在主线程回调）。

/** 一次待定 operation 的 JSON 结果（统一外壳 `{code, msg, data, trace_id}`）。toString 不带 data（不进日志）。 */
@Suppress("LongParameterList") // 统一外壳与响应头逐项
class AgentHttpResult(
    /** 拿到了 HTTP 响应且外壳 code=0。 */
    val ok: Boolean,
    val httpStatus: Int,
    /** 外壳 code；没有外壳（断网、超时、本地拒绝）为 -1。 */
    val code: Int,
    val msg: String,
    val data: JsonObject,
    /** 外壳 trace_id，没有时取响应头 X-Trace-Id，都没有为空串。 */
    val traceId: String,
    /** 响应头 Retry-After 原值。 */
    val retryAfter: String?,
    /** 本地没有发出（闸门、归属失效）。 */
    val notSent: Boolean = false,
) {
    /** 10004 的 consent_type（在 data 里；没有为 null）。 */
    val consentType: String? get() = AgentJson.stringOf(data["consent_type"])

    /** 有统一外壳的错误码（没有外壳按 50001）。 */
    val codeOrGeneric: Int get() = if (code < 0) AgentErrorCodes.GENERIC else code

    override fun toString(): String = "AgentHttpResult(ok=$ok, http=$httpStatus, code=$code, notSent=$notSent)"

    companion object {
        const val NO_CODE = -1
        private const val HTTP_OK_MIN = 200
        private const val HTTP_OK_MAX = 299

        fun network(): AgentHttpResult = AgentHttpResult(false, 0, NO_CODE, "", AgentJson.EMPTY, "", null)

        fun notSentResult(): AgentHttpResult = AgentHttpResult(false, 0, NO_CODE, "", AgentJson.EMPTY, "", null, notSent = true)

        /** 响应头（键小写）+ 正文 → 结果；正文不是统一外壳按网络错误。 */
        fun of(
            status: Int,
            headers: Map<String, String>,
            body: String,
        ): AgentHttpResult {
            val env = AgentJson.objectOf(AgentJson.parse(body))
            val headerTrace = headers["x-trace-id"].orEmpty()
            val retryAfter = headers["retry-after"]
            val code =
                env?.let { AgentJson.intOf(it["code"]) }?.toInt()
                    ?: return AgentHttpResult(false, status, NO_CODE, "", AgentJson.EMPTY, headerTrace, retryAfter)
            val trace = AgentJson.stringOf(env["trace_id"])?.takeIf(String::isNotEmpty) ?: headerTrace
            return AgentHttpResult(
                ok = code == 0 && status in HTTP_OK_MIN..HTTP_OK_MAX,
                httpStatus = status,
                code = code,
                msg = AgentJson.stringOf(env["msg"]).orEmpty(),
                data = AgentJson.objectOf(env["data"]) ?: AgentJson.EMPTY,
                traceId = trace,
                retryAfter = retryAfter,
            )
        }
    }
}

/**
 * 发起一次请求时固定的归属（控制器构造时的身份键）与两道闸门：[sendAllowed] 在每次实际发包前核对（不通过就不发：notSent / 传输失败），
 * [owner] 在响应到达后核对。[anonymous]：游客与基本模式不带令牌（x-auth: optional，由 ApiClient 按会话决定）。
 *
 * APP-10b：
 * - [scope]：控制器内的子归属（stream:<run>、run:<run>、reload:<run>、page 等）；端口按 [identityKey] 登记页面归属，[AgentContractPort.cancelOwnedExcept]
 *   作废时可保留一个子归属（【停止】在 meta 前保留这一轮的流，鸿蒙 R1-11、R2-03）。
 * - [pageOwned]：false = 不挂页面归属（【停止】与身份事件的 cancel 由协调器持有，离页 / 控制器作废不取消它，brief §0 第 2 条）。
 * - [singleShot]：单次发送（身份事件与【停止】的 cancel，鸿蒙 R1-02、共性第 11、23 条）：不等刷新屏障、不刷新、不重新注册、不重放，也不清会话、不导航；
 *   只结算 10405 与 42901。
 * - [onWritten]：这次请求已写出（true）或确定不会写出（false）时调一次（流程据此保证 cancel 早于自己的请求，不等结果）。
 * - [onAbortable]：端口把「取消这次网络调用」交给调用方（不挂页面归属的应用级 cancel 用：等写出超时后撤销准入并取消它，Android R2-03）。
 */
@Suppress("LongParameterList") // 归属、闸门与发送策略逐项
class AgentCall(
    val identityKey: Any,
    val anonymous: Boolean,
    val sendAllowed: () -> Boolean,
    val owner: () -> Boolean,
    val scope: String = SCOPE_PAGE,
    val pageOwned: Boolean = true,
    val singleShot: Boolean = false,
    val onWritten: ((Boolean) -> Unit)? = null,
    val onAbortable: ((abort: () -> Unit) -> Unit)? = null,
) {
    override fun toString(): String = "AgentCall($scope, pageOwned=$pageOwned, singleShot=$singleShot)"

    companion object {
        const val SCOPE_PAGE = "page"

        fun streamScope(run: String): String = "stream:$run"

        fun runScope(run: String): String = "run:$run"

        fun reloadScope(run: String): String = "reload:$run"
    }
}

/** 流式响应的接收端（端口在主线程按到达顺序调用；cancel 之后端口可能仍回调，StreamClient 丢弃）。 */
interface AgentStreamSink {
    /** 响应头（键原样；StreamClient 转小写）。真实端口只在分流为流之后调用；本机假服务也可以先给 JSON 类型的头再给正文字节。 */
    fun onHeaders(
        status: Int,
        headers: Map<String, String>,
    )

    /** 原始字节块（不解码、不切行）。 */
    fun onBytes(chunk: ByteArray)

    fun onEnd()

    /** 断连、超时等传输失败（没有完整的 HTTP 响应）。 */
    fun onFailure()

    /** 没有成为流：统一外壳 JSON 结果（含 10002 / 10402 重放后的最终结果）、无外壳、本地没发出（APP-10b G19）。 */
    fun onEnvelope(result: AgentHttpResult)

    /**
     * 句柄已关闭（作废、页面重建）之后才回到主线程的 JSON 外壳（Android R3-01）：不再当作这条流的结果，只交调用方结算跨页面共享事实（AI 10004、42901、10405）。
     * 缺省不处理（测试替身与旧接线）。
     */
    fun onLateEnvelope(result: AgentHttpResult) = Unit
}

fun interface AgentStreamHandle {
    /** 同步关闭连接（还没发出的不再发出）。 */
    fun cancel()
}

/**
 * 七个 agent operation（契约 40cfc268，CT-08d/e/f）：createAgentSession、getCurrentAgentSession、listAgentSessionMessages、sendAgentMessage（成功
 * text/event-stream，失败 JSON）、cancelAgentRun、setAgentMessageFeedback、reportAgentMessage。都是 x-auth optional、不签名、不幂等。
 * 每个实现都必须在实际发包前核对 [AgentCall.sendAllowed]，不通过就不发（notSent）。挂起函数在主线程恢复。
 */
interface AgentContractPort {
    suspend fun createSession(call: AgentCall): AgentHttpResult

    suspend fun currentSession(call: AgentCall): AgentHttpResult

    fun sendMessage(
        sessionId: String,
        body: String,
        call: AgentCall,
        sink: AgentStreamSink,
    ): AgentStreamHandle

    suspend fun cancelRun(
        runId: String,
        call: AgentCall,
    ): AgentHttpResult

    /** listAgentSessionMessages（G08：limit 显式传 20；[cursor] 为 null 取首页）。[autoRetry]=false：断线类重载关掉 ApiClient 的 GET 自动重试（G35）。 */
    suspend fun listMessages(
        sessionId: String,
        cursor: String?,
        limit: Int,
        call: AgentCall,
        autoRetry: Boolean = true,
    ): AgentHttpResult

    /** setAgentMessageFeedback（[value]：up / down）。 */
    suspend fun setFeedback(
        messageId: String,
        value: String,
        call: AgentCall,
    ): AgentHttpResult

    /** reportAgentMessage（[reason]：1–200 码点自由文本，G29）。 */
    suspend fun report(
        messageId: String,
        reason: String,
        call: AgentCall,
    ): AgentHttpResult

    /** 作废这个归属键的全部在途页面归属请求（控制器作废时同步调用；不挂页面归属的 cancel 不受影响）。 */
    fun cancelOwned(identityKey: Any)

    /** 作废这个归属键的页面归属请求，但保留子归属 [keepScope]（【停止】在 meta 前保留这一轮的流，鸿蒙 R2-03）。 */
    fun cancelOwnedExcept(
        identityKey: Any,
        keepScope: String,
    ) = Unit

    /** 只作废这个归属键下子归属 [scope] 的请求（进后台暂停重载、作废一轮的建会话，鸿蒙 R1-08、R1-11）。 */
    fun cancelScope(
        identityKey: Any,
        scope: String,
    ) = Unit
}

/** 计时器端口（生产是主线程 Handler；单测注入假计时器推进 30 秒）。返回取消函数。 */
fun interface AgentTimer {
    fun after(
        delayMs: Long,
        block: () -> Unit,
    ): () -> Unit
}

object AgentStreamEventKind {
    const val FRAME = "frame"
    const val PING = "ping"

    /** 连接正常结束但没有终止帧。 */
    const val EOF = "eof"

    /** 30 秒没有任何一行。 */
    const val TIMEOUT = "timeout"

    /** 单轮总时长到上限（G16，90 秒）：按断线收尾。 */
    const val ROUND_LIMIT = "round_limit"

    /** 非 SSE 响应（JSON 错误外壳）。 */
    const val JSON = "json"

    /** 传输失败（断网、本地没发出）。 */
    const val FAILED = "failed"

    /** 单帧 data 超过 64 KiB（03 §7.2）：本地已关连接，这一轮本地结束。 */
    const val LIMIT = "limit"

    /** 发现缺号（[AgentSeqGuard]，G11、G36）：本条 incomplete，连接保持只等终止帧或断线。 */
    const val SEQ_GAP = "seq_gap"

    /** 缺号之后到达的终止帧（不交 Reducer；10004 仍要锁同意闸门，其余触发重载）。 */
    const val GAP_TERMINAL = "gap_terminal"
}

/** 流客户端交给控制器的一个事件。[traceId]：本次响应头的 X-Trace-Id（没有为空串）。 */
data class AgentStreamEvent(
    val kind: String,
    val frame: AgentFrame? = null,
    val result: AgentHttpResult? = null,
    val traceId: String = "",
)

/** 一次 run 的流客户端。[deliver] 由控制器给：每个事件都带发起时的票据（这里不认识票据的结构，只原样交回）。 */
@Suppress("TooManyFunctions") // 一处集中
class StreamClient<T>(
    private val port: AgentContractPort,
    private val timer: AgentTimer,
    private val ticket: T,
    private val deliver: (T, AgentStreamEvent) -> Unit,
) {
    private val sse = AgentSse()
    private val guard = AgentSeqGuard()
    private var gapReported = false
    private var handle: AgentStreamHandle? = null
    private var cancelIdle: (() -> Unit)? = null
    private var cancelRound: (() -> Unit)? = null
    private var live = false
    private var streaming = false
    private var jsonBody = java.io.ByteArrayOutputStream()
    private var status = 0
    private var headers: Map<String, String> = emptyMap()
    private var lines = 0L

    /** 本机日志（只写事件名与结果，不写 data）。 */
    var log: (String) -> Unit = {}

    /** 作废之后端口仍交来的 JSON 外壳（[AgentStreamSink.onLateEnvelope]）：不计入 [delivered]，只交控制器结算共享事实（R3-01）。 */
    var late: (T, AgentHttpResult) -> Unit = { _, _ -> }

    val active: Boolean get() = live

    /** 已收到 SSE 响应头（流已开始）。 */
    val started: Boolean get() = streaming

    /** 本流已发现缺号（G11）。 */
    val incomplete: Boolean get() = guard.incomplete

    /** 交给控制器的事件数（抓包断言：作废之后这个数不再变）。 */
    var delivered: Int = 0
        private set

    /** 作废之后端口仍交来、被丢弃的回调次数。 */
    var dropped: Int = 0
        private set

    /** 已收到的 SSE 原始字节数（不含 JSON 正文）。 */
    var sseBytes: Long = 0
        private set

    fun start(
        sessionId: String,
        body: String,
        call: AgentCall,
    ) {
        if (live || handle != null) return
        live = true
        // G33：计时从请求发出起算（连接建立前的等待也计入同一个 30 秒）；G16：单轮总时长同时起算、不重置
        armIdle()
        cancelRound =
            timer.after(ROUND_LIMIT_MS) {
                cancelRound = null
                expire(AgentStreamEventKind.ROUND_LIMIT)
            }
        val sink = ClientSink()
        val h = runCatching { port.sendMessage(sessionId, body, call, sink) }.getOrNull()
        if (!live) {
            // 发出前已被同步取消或同步收尾（端口同步回调了 onEnd / onFailure / onEnvelope）
            h?.cancel()
            return
        }
        handle = h
        if (h == null) finish(AgentStreamEvent(AgentStreamEventKind.FAILED, result = AgentHttpResult.network()))
    }

    /** 同步关闭连接、停计时、作废；之后的字节丢弃。 */
    fun cancel() {
        if (!live) return
        live = false
        disarm()
        val h = handle
        handle = null
        runCatching { h?.cancel() }
    }

    private fun onHeaders(
        status: Int,
        headers: Map<String, String>,
    ) {
        if (!live) {
            dropped += 1
            return
        }
        this.status = status
        this.headers = headers.mapKeys { it.key.lowercase() }
        val type =
            this.headers[CONTENT_TYPE]
                .orEmpty()
                .substringBefore(';')
                .trim()
                .lowercase()
        streaming = status in HTTP_OK_MIN..HTTP_OK_MAX && type == EVENT_STREAM
        // 鸿蒙 R1-09（G33）：响应头不算「一行」，不重置 30 秒计时（首行等待期限从请求发出起算）
    }

    /** 响应头的 X-Trace-Id（还没收到响应头为空串）。 */
    val headerTraceId: String get() = headers[TRACE_HEADER].orEmpty()

    @Suppress("ReturnCount") // 作废、JSON 正文、逐条交付
    private fun onBytes(chunk: ByteArray) {
        if (!live) {
            dropped += 1
            return
        }
        if (!streaming) {
            // JSON 正文分块不算「完整的 SSE 行」，不重置 30 秒（鸿蒙 H26）；正文只在 onEnd 时整体解码一次
            jsonBody.write(chunk)
            return
        }
        sseBytes += chunk.size
        val items = sse.feedBytes(chunk)
        if (sse.linesSeen != lines) {
            lines = sse.linesSeen
            armIdle()
        }
        for (item in items) {
            if (!live) return
            when (AgentJson.stringOf(item["kindRaw"])) {
                AgentSse.KIND_TOO_LARGE -> {
                    // 单帧 data 或半行超过上限（03 §7.2，Android R1-04、APP-10b G22）：协议错误，同步关连接、停计时，交出 limit（控制器本地结束为 limited）
                    log("agent frame too large")
                    val h = handle
                    handle = null
                    // 先作废本实例再关连接：关连接期间端口的同步回调一律丢弃
                    live = false
                    disarm()
                    runCatching { h?.cancel() }
                    emit(AgentStreamEvent(AgentStreamEventKind.LIMIT))
                    return
                }

                AgentSse.KIND_PING -> {
                    emit(AgentStreamEvent(AgentStreamEventKind.PING))
                }

                else -> {
                    val frame = AgentFrameDecoder.frame(item)
                    if (frame.result == AgentFrameResult.INVALID) {
                        // 只记事件名与结果（不记 data，G05、G15）
                        log("agent frame dropped ${AgentJson.stringOf(item["event"]) ?: "-"} ${frame.result}")
                    } else {
                        route(frame)
                    }
                }
            }
        }
    }

    /**
     * 帧交控制器之前经 [AgentSeqGuard]（APP-10b §2 第 4 条，G11）：按序交付；重复或更小的 id 丢弃；缺号 → 交一次 SEQ_GAP（之后不再交内容帧），缺号之后的终止帧
     * 交 GAP_TERMINAL（控制器据此关连接、重载；10004 照样锁闸门）。未知 event 也参加判序（Reducer 自己忽略它）。agent_stream_seq_gap 不发（G13）。
     */
    private fun route(frame: AgentFrame) {
        when (guard.check(frame.id, frame.event)) {
            AgentSeqDecision.DELIVER -> {
                emit(AgentStreamEvent(AgentStreamEventKind.FRAME, frame = frame))
            }

            AgentSeqDecision.DUPLICATE -> {
                Unit
            }

            else -> {
                if (!gapReported) {
                    gapReported = true
                    // 只记两个数字（G13）
                    log("agent stream seq_gap expected=${guard.lastApplied + 1} got=${frame.id}")
                    emit(AgentStreamEvent(AgentStreamEventKind.SEQ_GAP))
                }
                if (AgentSeqGuard.isTerminal(frame.event) && frame.ok &&
                    live
                ) {
                    emit(AgentStreamEvent(AgentStreamEventKind.GAP_TERMINAL, frame = frame))
                }
            }
        }
    }

    private fun onEnd() {
        if (!live) {
            dropped += 1
            return
        }
        if (!streaming) {
            val text = StreamUtf8Decoder.decodeAll(jsonBody.toByteArray())
            jsonBody = java.io.ByteArrayOutputStream()
            finish(AgentStreamEvent(AgentStreamEventKind.JSON, result = AgentHttpResult.of(status, headers, text)))
            return
        }
        sse.finish()
        finish(AgentStreamEvent(AgentStreamEventKind.EOF))
    }

    private fun onFailure() {
        if (!live) {
            dropped += 1
            return
        }
        finish(AgentStreamEvent(AgentStreamEventKind.FAILED, result = AgentHttpResult.network()))
    }

    private fun onEnvelope(result: AgentHttpResult) {
        if (!live) {
            dropped += 1
            return
        }
        finish(AgentStreamEvent(AgentStreamEventKind.JSON, result = result))
    }

    private fun onLateEnvelope(result: AgentHttpResult) {
        dropped += 1
        runCatching { late(ticket, result) }
    }

    private fun finish(event: AgentStreamEvent) {
        live = false
        disarm()
        handle = null
        emit(event)
    }

    /** 交给控制器：带发起时的票据与响应头的 trace_id。 */
    private fun emit(event: AgentStreamEvent) {
        delivered += 1
        deliver(ticket, event.copy(traceId = headerTraceId.ifEmpty { event.result?.traceId.orEmpty() }))
    }

    /** 计时到（30 秒空闲或 90 秒单轮）：本地关闭连接，再交出断线事件（控制器把消息记为 disconnected 并给【重试】）。 */
    private fun expire(kind: String) {
        if (!live) return
        val h = handle
        handle = null
        live = false
        disarm()
        runCatching { h?.cancel() }
        emit(AgentStreamEvent(kind))
    }

    private fun armIdle() {
        cancelIdle?.invoke()
        cancelIdle =
            timer.after(IDLE_TIMEOUT_MS) {
                cancelIdle = null
                expire(AgentStreamEventKind.TIMEOUT)
            }
    }

    private fun disarm() {
        cancelIdle?.invoke()
        cancelIdle = null
        cancelRound?.invoke()
        cancelRound = null
    }

    private inner class ClientSink : AgentStreamSink {
        override fun onHeaders(
            status: Int,
            headers: Map<String, String>,
        ) = this@StreamClient.onHeaders(status, headers)

        override fun onBytes(chunk: ByteArray) = this@StreamClient.onBytes(chunk)

        override fun onEnd() = this@StreamClient.onEnd()

        override fun onFailure() = this@StreamClient.onFailure()

        override fun onEnvelope(result: AgentHttpResult) = this@StreamClient.onEnvelope(result)

        override fun onLateEnvelope(result: AgentHttpResult) = this@StreamClient.onLateEnvelope(result)
    }

    companion object {
        const val IDLE_TIMEOUT_MS = 30_000L

        /** G16（编排者补充第 6 条）：单轮总时长上限 90 秒（> 服务端 20 秒时限 + run 锁 30 秒宽限 + 余量）。 */
        const val ROUND_LIMIT_MS = 90_000L
        private const val HTTP_OK_MIN = 200
        private const val HTTP_OK_MAX = 299
        private const val CONTENT_TYPE = "content-type"
        private const val EVENT_STREAM = "text/event-stream"
        private const val TRACE_HEADER = "x-trace-id"
    }
}

/**
 * timeouts 组向量的接线入口（鸿蒙 R1-04，S0）：驱动真实 StreamClient 的脚本端口、虚拟计时器与「正常结束」的 meta / done 帧只在测试源码集（经
 * [AgentVectorWiring] 的 ServiceLoader 注册）；生产制品里没有。没有注入实现时返回空对象（生产代码不调用它）。
 */
object AgentTimeoutPolicy {
    fun of(
        input: JsonObject,
        nowMs: Long,
    ): JsonObject = AgentVectorWiring.instance?.timeouts(input, nowMs) ?: AgentJson.EMPTY
}
