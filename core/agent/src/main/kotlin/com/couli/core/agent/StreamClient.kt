package com.couli.core.agent

import kotlinx.serialization.json.JsonObject

// APP-10 §2 第 4 条：StreamClient（界面无关）。一次 run 一个实例：发 sendAgentMessage → 按响应 Content-Type 分流
// （text/event-stream → 增量喂 AgentSse；其他 → 统一外壳 JSON 错误，G24）→ 每一帧连同发起时的 run 票据交给控制器（控制器先比票据）。
// - 30 秒内没收到任何一行（含 `: ping` 与空行）→ 断线（G33：从请求发出起算，连接建立前的等待也计入；只有收到完整的 SSE 行才重置，
//   响应头与半行都不延长期限（鸿蒙 R1-09）；计时器绑定本实例）。
// - 响应头的 X-Trace-Id 随每个事件交回（流内 error 帧没有 trace_id，5xxxx 文案的 trace6 用它，鸿蒙 R1-07）。
// - cancel()：同步关闭连接、停计时、本实例作废；之后到达的任何字节一律丢弃（不解析、不交付）。
// 待定 operation（G01、编排者补充 1、6）：锁定契约没有 /v1/agent/…，四个接口只由 Debug / Staging 的本地假服务实现（AgentContractPort）；Release
// 没有端口，Agent 页显示「未开放」、不发请求。契约补上（F-X-04）后由 ApiClient 管线（OkHttp ResponseBody.source() 逐行读）实现同一端口，
// 控制器与 Reducer 代码路径不变。TODO(N-36)。只在主线程使用（端口在主线程回调）。

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
 * [owner] 在响应到达后核对。[anonymous]：游客与基本模式不带令牌（x-auth: optional，由端口自己处理）。
 */
class AgentCall(
    val identityKey: Any,
    val anonymous: Boolean,
    val sendAllowed: () -> Boolean,
    val owner: () -> Boolean,
)

/** 流式响应的接收端（端口在主线程按到达顺序调用；cancel 之后端口可能仍回调，StreamClient 丢弃）。 */
interface AgentStreamSink {
    fun onHeaders(
        status: Int,
        headers: Map<String, String>,
    )

    fun onData(chunk: String)

    fun onEnd()

    /** 断连、超时等传输失败（没有完整的 HTTP 响应）。 */
    fun onFailure()
}

fun interface AgentStreamHandle {
    /** 同步关闭连接（还没发出的不再发出）。 */
    fun cancel()
}

/**
 * 待定 operation（形状取 rebate-platform 47eaf92f，G01）：createAgentSession（POST /v1/agent/sessions）、getCurrentAgentSession
 * （GET /v1/agent/sessions/current，session 可为 null）、sendAgentMessage（POST /v1/agent/sessions/{id}/messages，成功 text/event-stream，
 * 失败 JSON）、cancelAgentRun（POST /v1/agent/runs/{run_id}/cancel）。都是 x-auth optional、不签名、不幂等、x-min-version-gate。
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

    /** 作废这个归属键的全部在途请求（控制器作废时同步调用）。 */
    fun cancelOwned(identityKey: Any)
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

    /** 非 SSE 响应（JSON 错误外壳）。 */
    const val JSON = "json"

    /** 传输失败（断网、本地没发出）。 */
    const val FAILED = "failed"

    /** 单帧 data 超过 64 KiB（03 §7.2）：本地已关连接，这一轮本地结束。 */
    const val LIMIT = "limit"
}

/** 流客户端交给控制器的一个事件。[traceId]：本次响应头的 X-Trace-Id（没有为空串）。 */
data class AgentStreamEvent(
    val kind: String,
    val frame: AgentFrame? = null,
    val result: AgentHttpResult? = null,
    val traceId: String = "",
)

/** 一次 run 的流客户端。[deliver] 由控制器给：每个事件都带发起时的票据（这里不认识票据的结构，只原样交回）。 */
class StreamClient<T>(
    private val port: AgentContractPort,
    private val timer: AgentTimer,
    private val ticket: T,
    private val deliver: (T, AgentStreamEvent) -> Unit,
) {
    private val sse = AgentSse()
    private var handle: AgentStreamHandle? = null
    private var cancelTimer: (() -> Unit)? = null
    private var live = false
    private var streaming = false
    private val jsonBody = StringBuilder()
    private var status = 0
    private var headers: Map<String, String> = emptyMap()
    private var lines = 0L

    /** 本机日志（只写事件名与结果，不写 data）。 */
    var log: (String) -> Unit = {}

    val active: Boolean get() = live

    /** 已收到 SSE 响应头（流已开始）。 */
    val started: Boolean get() = streaming

    fun start(
        sessionId: String,
        body: String,
        call: AgentCall,
    ) {
        if (live || handle != null) return
        live = true
        // G33：计时从请求发出起算（连接建立前的等待也计入同一个 30 秒）
        arm()
        val sink = ClientSink()
        val h = runCatching { port.sendMessage(sessionId, body, call, sink) }.getOrNull()
        if (!live) {
            // 发出前已被同步取消或同步收尾（端口同步回调了 onEnd / onFailure）
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
        if (!live) return
        this.status = status
        this.headers = headers.mapKeys { it.key.lowercase() }
        val type = this.headers[CONTENT_TYPE].orEmpty().lowercase()
        streaming = status in HTTP_OK_MIN..HTTP_OK_MAX && EVENT_STREAM in type
        // 鸿蒙 R1-09（G33）：响应头不算「一行」，不重置 30 秒计时（首行等待期限从请求发出起算）
    }

    /** 响应头的 X-Trace-Id（还没收到响应头为空串）。 */
    val headerTraceId: String get() = headers[TRACE_HEADER].orEmpty()

    @Suppress("ReturnCount") // 作废、JSON 正文、逐条交付
    private fun onData(chunk: String) {
        if (!live) return
        if (!streaming) {
            // JSON 正文分块不算「完整的 SSE 行」，不重置 30 秒（鸿蒙 H26）
            jsonBody.append(chunk)
            return
        }
        val items = sse.feed(chunk)
        if (sse.linesSeen != lines) {
            lines = sse.linesSeen
            arm()
        }
        for (item in items) {
            if (!live) return
            if (AgentJson.stringOf(item["kindRaw"]) == AgentSse.KIND_TOO_LARGE) {
                // 单帧 data 超过 64 KiB（03 §7.2，Android R1-04）：协议错误，同步关连接、停计时，交出 limit（控制器本地结束这一轮），不再累积
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
            if (AgentJson.stringOf(item["kindRaw"]) == AgentSse.KIND_PING) {
                emit(AgentStreamEvent(AgentStreamEventKind.PING))
                continue
            }
            val frame = AgentFrameDecoder.frame(item)
            // 只记事件名与结果（不记 data，G05、G15）
            if (!frame.ok) log("agent frame dropped ${AgentJson.stringOf(item["event"]) ?: "-"} ${frame.result}")
            emit(AgentStreamEvent(AgentStreamEventKind.FRAME, frame = frame))
        }
    }

    private fun onEnd() {
        if (!live) return
        if (!streaming) {
            finish(AgentStreamEvent(AgentStreamEventKind.JSON, result = AgentHttpResult.of(status, headers, jsonBody.toString())))
            return
        }
        sse.finish()
        finish(AgentStreamEvent(AgentStreamEventKind.EOF))
    }

    private fun onFailure() {
        if (!live) return
        finish(AgentStreamEvent(AgentStreamEventKind.FAILED, result = AgentHttpResult.network()))
    }

    private fun finish(event: AgentStreamEvent) {
        live = false
        disarm()
        handle = null
        emit(event)
    }

    /** 交给控制器：带发起时的票据与响应头的 trace_id。 */
    private fun emit(event: AgentStreamEvent) = deliver(ticket, event.copy(traceId = headerTraceId))

    private fun arm() {
        disarm()
        cancelTimer =
            timer.after(IDLE_TIMEOUT_MS) {
                cancelTimer = null
                if (!live) return@after
                // 断线：本地关闭连接，再交出 timeout（控制器把消息记为 disconnected 并给【重试】）
                val h = handle
                handle = null
                live = false
                runCatching { h?.cancel() }
                emit(AgentStreamEvent(AgentStreamEventKind.TIMEOUT))
            }
    }

    private fun disarm() {
        cancelTimer?.invoke()
        cancelTimer = null
    }

    private inner class ClientSink : AgentStreamSink {
        override fun onHeaders(
            status: Int,
            headers: Map<String, String>,
        ) = this@StreamClient.onHeaders(status, headers)

        override fun onData(chunk: String) = this@StreamClient.onData(chunk)

        override fun onEnd() = this@StreamClient.onEnd()

        override fun onFailure() = this@StreamClient.onFailure()
    }

    companion object {
        const val IDLE_TIMEOUT_MS = 30_000L
        private const val HTTP_OK_MIN = 200
        private const val HTTP_OK_MAX = 299
        private const val CONTENT_TYPE = "content-type"
        private const val EVENT_STREAM = "text/event-stream"
        private const val TRACE_HEADER = "x-trace-id"
    }
}
