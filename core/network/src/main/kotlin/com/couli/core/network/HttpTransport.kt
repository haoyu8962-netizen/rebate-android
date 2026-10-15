package com.couli.core.network

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink

/** 一次发送的结果（解包之前）。 */
internal sealed interface Exchange {
    /** 带统一外壳 `{code, msg, data, trace_id}` 的响应。 */
    data class Envelope(
        val code: Int,
        val msg: String,
        val data: JsonElement?,
        val traceId: String?,
        val headers: Map<String, String>,
    ) : Exchange

    /** HTTP 304（带 If-None-Match 的读请求，内容未变，03 §4.3）：没有统一外壳。 */
    data class NotModified(
        val headers: Map<String, String>,
    ) : Exchange

    /** 超时、断连，或响应不是统一外壳（网关错误页等）。 */
    data class Transport(
        val kind: String,
        val httpStatus: Int?,
    ) : Exchange

    /** 没有写出（登记前世代已变、写出前核对不放行、或在写出前被取消）：[reason] 为 [LocalRejectReason]（A-R2-01）。 */
    data class NotSent(
        val reason: String,
    ) : Exchange

    /** APP-10b：响应分流为 SSE 流，响应头与字节已逐块交给 [StreamTap]（终结已交出：结束 / 失败 / 被取消）。 */
    data object Streamed : Exchange
}

/**
 * 一次流式调用在传输层的接收端（APP-10b §2 第 1 条）：[ApiClient.stream] 建、[HttpTransport.send] 用。响应头到达后按 Content-Type 分流（G19）：SSE → 头与
 * 原始字节块逐块交 [sink]（IO 线程；[handle] 已取消的一律丢弃）；其他 → 照普通请求读完整正文解包（[started] 保持 false，ApiClient 按结果路径交 onEnvelope）。
 */
internal class StreamTap(
    val handle: ApiStreamHandle,
    val sink: ApiStreamSink,
) {
    /** 已分流为流（之后不再重放、不再交 onEnvelope）。 */
    @Volatile
    var started = false

    /** 已收到的 SSE 字节数（G23：有字节之后不再重放）。 */
    @Volatile
    var bytes = 0L
}

/**
 * 一次发送的归属核对（A-R2-01）：[epochs] 是调用开始时的隐私 / 环境世代；[admit] 在连接建立后、写出请求前由网络拦截器调用，
 * 再核对一次调用归属（账号、会话世代）与放行条件，返回拒绝原因或 null（放行）。
 */
internal class SendGuard(
    val epochs: CallEpochs,
    /** 调用方整次调用的截止时刻（[System.nanoTime] 口径，R2-01）；Call 的超时截到剩余预算，到期不再发出。 */
    val deadlineNanos: Long? = null,
    /**
     * 写出之后仍在等回包时，这次发送是否已没有人要（共享刷新：最后一个有效的调用方已失效或到期，APP-04 共性第 54 条）。不为 null 时
     * 发送期间每 [HttpTransport.ABANDON_POLL_MS] 毫秒核对一次，为 true 即取消这个 Call（按 cancelled 结束）。
     */
    val abandoned: (() -> Boolean)? = null,
    /** 调用方的归属标记（[CallOwnership]）：[InFlightCalls.cancelTagged] 据此取消这次发送；没有为 null。 */
    val tag: Any? = null,
    /** 写出前核对放行、即将写出时调一次（[CallOwnership.onWritten]，APP-10b：身份事件的 cancel 早于流程自己的请求）。 */
    val onCommitted: (() -> Unit)? = null,
    val admit: () -> String?,
)

/** 已决定好头与字节、待发出的请求。 */
internal class PreparedRequest(
    val method: String,
    val url: HttpUrl,
    val headers: Map<String, String>,
    val body: ByteArray?,
)

/**
 * 发送与解包（03 §4.2 中间件的「发送 → 解包」两步）：一次调用只发一次。HTTP 状态码不决定成败，以外壳的 code 为准；读不出外壳即
 * no_envelope。自动重试由 [ApiClient] 负责：每次重试都重新做放行判定、重新组头与签名（R1-01），这里不重发。
 *
 * 底层不得自己重发或跟随（R1-03、R1-05）：
 * - 关闭 retryOnConnectionFailure、followRedirects、followSslRedirects（重定向的目标不经过操作登记、白名单与凭据判定）；
 * - 写请求的请求体是一次性的（isOneShot）：OkHttp 遇到 503 + `Retry-After: 0`、408 等内部重发分支时直接把响应交回；
 * - 网络拦截器给每次调用计数：同一次调用出现第二次网络发送（GET 的 503 + `Retry-After: 0` 等）一律在发出前中止。
 */
internal class HttpTransport(
    private val http: OkHttpClient,
    private val inFlight: InFlightCalls,
    /** Debug / Staging 的本机替身（写出前核对之后才问它，APP-10b §2 第 13 条）；Release 为 null。 */
    private val local: LocalExchange? = null,
) {
    private val clients = mutableMapOf<Long, OkHttpClient>()
    private val streamClients = mutableMapOf<Long, OkHttpClient>()

    /**
     * 发一次（不重试）。Call 登记进 [InFlightCalls]（撤回同意、切换环境时立即取消）；写出前由网络拦截器按 [guard] 再核对一次。
     * 没写出就结束的（登记前世代已变、核对不放行、写出前被取消）返回 [Exchange.NotSent]。
     */
    @Suppress("SwallowedException", "ReturnCount", "CyclomaticComplexMethod") // 传输异常转成结果（timeout / disconnect），由管线与未决键按「没有统一外壳」处理
    fun send(
        prepared: PreparedRequest,
        timeoutMillis: Long,
        guard: SendGuard,
        tap: StreamTap? = null,
    ): Exchange {
        val attempt = SingleAttempt(guard, inFlight)
        val builder = Request.Builder().url(prepared.url).tag(SingleAttempt::class.java, attempt)
        prepared.headers.forEach { (name, value) -> builder.header(name, value) }
        val body =
            when {
                prepared.body != null -> OneShotBody(prepared.body, JSON)
                prepared.method == "GET" -> null
                else -> OneShotBody(ByteArray(0), null)
            }
        builder.method(prepared.method, body)
        val call = (if (tap != null) streamClient(timeoutMillis) else client(timeoutMillis)).newCall(builder.build())
        // 与调用方的截止时间绑定（R2-01、共性第 45 条）：Call 的整体超时取「本次超时」与「剩余预算」中较小的，到期由 OkHttp
        // 取消这个 Call（阻塞中的 execute 立即结束）；预算已用完的不发出。流式请求没有整体调用超时（G16：空闲与单轮上限由 StreamClient 负责）
        if (tap == null &&
            !bindDeadline(call, guard.deadlineNanos, timeoutMillis)
        ) {
            return Exchange.NotSent(LocalRejectReason.CALL_CANCELLED)
        }
        if (!inFlight.register(call, guard.epochs, guard.tag)) return Exchange.NotSent(LocalRejectReason.CALL_CANCELLED)
        // 流式句柄的取消 = Call.cancel()（同步关闭连接；还没写出的不再写出）
        tap?.handle?.bind { call.cancel() }
        // 没人要了就取消（共性第 54 条）：共享刷新的最后一个调用方到期或失效时，连同在途的刷新请求一起取消
        val watch = guard.abandoned?.let { abandoned -> watchAbandoned(call, abandoned) }
        return try {
            call.execute().use { response -> if (tap != null) streamExchange(response, tap, attempt) else toExchange(response) }
        } catch (timeout: InterruptedIOException) {
            // 超时（含 callTimeout）：结果未知，调用方按「没有统一外壳」处理
            attempt.notSent() ?: Exchange.Transport(if (attempt.invalidated()) KIND_CANCELLED else KIND_TIMEOUT, null)
        } catch (io: IOException) {
            // 断连、连接失败、被拦下的底层重发；写出之后被取消（撤回同意、切换环境）
            attempt.notSent() ?: Exchange.Transport(if (attempt.invalidated()) KIND_CANCELLED else KIND_DISCONNECT, null)
        } finally {
            watch?.cancel(false)
            inFlight.unregister(call)
        }
    }

    /** 把 Call 的整体超时截到调用方剩余预算（R2-01）；预算已用完返回 false（不发出）。没有截止时间的照常。 */
    private fun bindDeadline(
        call: okhttp3.Call,
        deadlineNanos: Long?,
        timeoutMillis: Long,
    ): Boolean {
        val remaining = deadlineNanos?.let { it - System.nanoTime() } ?: return true
        if (remaining > 0) call.timeout().timeout(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(timeoutMillis)), TimeUnit.NANOSECONDS)
        return remaining > 0
    }

    /** 发送期间定时核对 [abandoned]，为 true 即取消 [call]（共性第 54 条）。 */
    private fun watchAbandoned(
        call: okhttp3.Call,
        abandoned: () -> Boolean,
    ): ScheduledFuture<*> =
        ABANDON_WATCH.scheduleWithFixedDelay(
            { if (abandoned()) call.cancel() },
            ABANDON_POLL_MS,
            ABANDON_POLL_MS,
            TimeUnit.MILLISECONDS,
        )

    @Synchronized
    private fun client(timeoutMillis: Long): OkHttpClient =
        clients.getOrPut(timeoutMillis) {
            http
                .newBuilder()
                .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .apply { local?.let { addInterceptor(LocalAnswerInterceptor(it)) } }
                .addNetworkInterceptor(SingleAttemptInterceptor)
                .build()
        }

    /**
     * 流式客户端（APP-10b §3 Android，20261014 §4「Android 流式连接单独网络客户端」）：由同一个共享 OkHttpClient 派生，保留它的拦截器与连接池，
     * 加同一个写出前核对的网络拦截器；retryOnConnectionFailure(false)、不跟随重定向；readTimeout / callTimeout 为 0（空闲 30 秒与单轮 90 秒由 StreamClient
     * 负责），connectTimeout / writeTimeout 沿用普通请求的值。请求头显式 Accept-Encoding: identity（关掉 OkHttp 透明 gzip，G17）。
     */
    @Synchronized
    private fun streamClient(timeoutMillis: Long): OkHttpClient =
        streamClients.getOrPut(timeoutMillis) {
            http
                .newBuilder()
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .apply { local?.let { addInterceptor(LocalAnswerInterceptor(it)) } }
                .addNetworkInterceptor(SingleAttemptInterceptor)
                .build()
        }

    /**
     * 流式响应（APP-10b G19）：text/event-stream 且 2xx → 头交 sink，ResponseBody.source() 逐块读出的原始字节交 sink（每块交付前核对句柄与调用归属），
     * 结束 / 失败各交一次终结（被取消不交）；其他 → 与普通请求同一解包（[toExchange]）。
     */
    @Suppress("SwallowedException", "CyclomaticComplexMethod", "LoopWithTooManyJumpStatements", "ReturnCount")
    private fun streamExchange(
        response: Response,
        tap: StreamTap,
        attempt: SingleAttempt,
    ): Exchange {
        if (!ApiStreamRoutingPolicy.isStream(response.code, response.header(HeaderNames.CONTENT_TYPE))) return toExchange(response)
        tap.started = true
        val handle = tap.handle
        val sink = tap.sink
        if (handle.cancelled || attempt.invalidated()) return Exchange.Streamed
        sink.onHeaders(response.code, response.headers.toMap().mapKeys { it.key.lowercase() })
        val source = response.body.source()
        val buffer = ByteArray(STREAM_CHUNK)
        try {
            while (true) {
                val n = source.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                if (handle.cancelled || attempt.invalidated() || attempt.guard.abandoned?.invoke() == true) {
                    handle.countDropped()
                    return Exchange.Streamed
                }
                tap.bytes += n
                handle.countDelivered()
                sink.onBytes(buffer.copyOf(n))
            }
        } catch (io: IOException) {
            if (!handle.cancelled && !attempt.invalidated()) sink.onFailure(KIND_DISCONNECT)
            return Exchange.Streamed
        }
        if (!handle.cancelled && !attempt.invalidated()) sink.onEnd()
        return Exchange.Streamed
    }

    /**
     * Debug / Staging 本机替身在实际传输入口的拦截器（APP-10b §2 第 13 条）：替身认领的请求先过同一个写出前核对（发送计数、世代、调用归属、发送前闸门、放行判定），
     * 放行才交替身回答（替身每次现判 DebugMockGate，不回答就交真实网络）。不认领的照常往下走。
     */
    private class LocalAnswerInterceptor(
        private val local: LocalExchange,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val attempt = request.tag(SingleAttempt::class.java)
            if (attempt == null || !runCatching { local.handles(request) }.getOrDefault(false)) return chain.proceed(request)
            admitOrThrow(attempt)
            return local.answer(request) { chain.call().isCanceled() } ?: run {
                // 替身此刻不回答（闸门关了）：交真实网络。写出前核对已做过一次，网络拦截器不再重复计数
                attempt.attempts.decrementAndGet()
                chain.proceed(request)
            }
        }
    }

    /**
     * 一次调用的网络发送（Request 的 tag；OkHttp 内部重发与跟随沿用同一个 tag）：发送计数、写出前核对的结论、是否已写出。
     */
    private class SingleAttempt(
        val guard: SendGuard,
        val inFlight: InFlightCalls,
    ) {
        val attempts = AtomicInteger()

        @Volatile
        var blocked: String? = null

        @Volatile
        var committed = false

        /** 撤回同意、切换环境或调用方截止时间已到，作废了这次发送（不看 Call.isCanceled：callTimeout 到期也会取消 Call）。 */
        fun invalidated(): Boolean =
            inFlight.epochs() != guard.epochs ||
                guard.deadlineNanos?.let { System.nanoTime() - it >= 0 } == true ||
                guard.abandoned?.invoke() == true

        /** 没写出就结束了：写出前核对不放行，或写出前被作废取消。已写出（或连接失败、超时）为 null。 */
        fun notSent(): Exchange.NotSent? =
            when {
                blocked != null -> Exchange.NotSent(blocked.orEmpty())
                !committed && invalidated() -> Exchange.NotSent(LocalRejectReason.CALL_CANCELLED)
                else -> null
            }
    }

    /**
     * 网络拦截器（连接已建立、请求还没写出）：同一次调用的第二次网络发送中止（R1-03）；写出前再核对一次世代、调用归属与放行条件，
     * 不放行或已被取消的不写出（A-R2-01）。
     */
    private object SingleAttemptInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val attempt = chain.request().tag(SingleAttempt::class.java) ?: return chain.proceed(chain.request())
            admitOrThrow(attempt)
            return chain.proceed(chain.request())
        }
    }

    /** 一次性请求体：OkHttp 不会用它重发（R1-03）。 */
    private class OneShotBody(
        private val bytes: ByteArray,
        private val type: MediaType?,
    ) : RequestBody() {
        override fun contentType(): MediaType? = type

        override fun contentLength(): Long = bytes.size.toLong()

        override fun isOneShot(): Boolean = true

        override fun writeTo(sink: BufferedSink) {
            sink.write(bytes)
        }
    }

    private fun okhttp3.Headers.toMap(): Map<String, String> = names().associateWith { get(it).orEmpty() }

    /** 收到的响应 → 结果：304（带 If-None-Match 的读请求，03 §4.3）没有统一外壳；其余按统一外壳解包。 */
    private fun toExchange(response: Response): Exchange {
        if (response.code == HTTP_NOT_MODIFIED) return Exchange.NotModified(response.headers.toMap())
        val text = response.body.string()
        return parseEnvelope(text, response.headers.toMap()) ?: Exchange.Transport(KIND_NO_ENVELOPE, response.code)
    }

    companion object {
        const val KIND_TIMEOUT = "timeout"
        const val KIND_DISCONNECT = "disconnect"
        const val KIND_NO_ENVELOPE = "no_envelope"

        /** 写出之后被取消（撤回同意、切换环境）：结果未知，不自动重试。 */
        const val KIND_CANCELLED = "cancelled"

        /** [SendGuard.abandoned] 的核对间隔。 */
        const val ABANDON_POLL_MS = 50L

        /** 核对「是否已没人要」的后台线程（守护线程，只做核对与 Call.cancel）。 */
        private val ABANDON_WATCH: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "couli-abandon-watch").apply { isDaemon = true } }
        private const val HTTP_NOT_MODIFIED = 304
        private const val BYTE_MASK = 0xff
        private const val ASCII_LIMIT = 0x80
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** 流式响应每次读的字节上限（一块交付一次；行与帧的上限由 Core 的 AgentSse 按字节判定）。 */
        private const val STREAM_CHUNK = 8 * 1024

        /** 写出前核对（普通与流式、真实网络与本机替身同一处）：同一次调用的第二次网络发送中止；世代、归属、闸门与放行判定不通过的不写出。 */
        private fun admitOrThrow(attempt: SingleAttempt) {
            if (attempt.attempts.incrementAndGet() > 1) throw IOException("底层重发已拦下")
            val reason = attempt.inFlight.admit(attempt.guard.epochs, attempt.guard.admit)
            if (reason != null) {
                attempt.blocked = reason
                throw IOException("写出前核对未放行：$reason")
            }
            attempt.committed = true
            attempt.guard.onCommitted?.let { runCatching(it) }
        }

        /** 统一外壳：JSON 对象，code 为整数，msg 为字符串。 */
        @Suppress("ReturnCount") // 外壳的三个必需字段逐项校验
        fun parseEnvelope(
            text: String,
            headers: Map<String, String>,
        ): Exchange.Envelope? {
            val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
            val code = (root["code"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: return null
            val msg = (root["msg"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val traceId = (root["trace_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            return Exchange.Envelope(code, msg, root["data"], traceId, headers)
        }

        /** 基址 + 路径模板（填入编码后的路径参数）+ 原样查询串。基址不可用返回 null。 */
        fun buildUrl(
            baseUrl: String,
            pathTemplate: String,
            pathParams: Map<String, String>,
            query: String?,
        ): HttpUrl? {
            var path = pathTemplate
            pathParams.forEach { (name, value) -> path = path.replace("{$name}", encodePathSegment(value)) }
            if ('{' in path) return null
            val suffix = query?.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
            return (baseUrl.trimEnd('/') + path + suffix).toHttpUrlOrNull()
        }

        /** 签名用的路径：发出的编码路径 + 原样查询串（BR-ID-09）。 */
        fun signedPath(url: HttpUrl): String = url.encodedPath + (url.encodedQuery?.let { "?$it" }.orEmpty())

        private fun encodePathSegment(value: String): String =
            value.toByteArray(Charsets.UTF_8).joinToString("") { byte ->
                val unsigned = byte.toInt() and BYTE_MASK
                val c = unsigned.toChar()
                if ((c.isLetterOrDigit() && unsigned < ASCII_LIMIT) || c in "-._~") c.toString() else "%%%02X".format(unsigned)
            }
    }
}
