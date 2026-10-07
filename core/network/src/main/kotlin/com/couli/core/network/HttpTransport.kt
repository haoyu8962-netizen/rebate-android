package com.couli.core.network

import java.io.IOException
import java.io.InterruptedIOException
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
}

/**
 * 一次发送的归属核对（A-R2-01）：[epochs] 是调用开始时的隐私 / 环境世代；[admit] 在连接建立后、写出请求前由网络拦截器调用，
 * 再核对一次调用归属（账号、会话世代）与放行条件，返回拒绝原因或 null（放行）。
 */
internal class SendGuard(
    val epochs: CallEpochs,
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
) {
    private val clients = mutableMapOf<Long, OkHttpClient>()

    /**
     * 发一次（不重试）。Call 登记进 [InFlightCalls]（撤回同意、切换环境时立即取消）；写出前由网络拦截器按 [guard] 再核对一次。
     * 没写出就结束的（登记前世代已变、核对不放行、写出前被取消）返回 [Exchange.NotSent]。
     */
    @Suppress("SwallowedException", "ReturnCount") // 传输异常转成结果（timeout / disconnect），由管线与未决键按「没有统一外壳」处理
    fun send(
        prepared: PreparedRequest,
        timeoutMillis: Long,
        guard: SendGuard,
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
        val call = client(timeoutMillis).newCall(builder.build())
        if (!inFlight.register(call, guard.epochs)) return Exchange.NotSent(LocalRejectReason.CALL_CANCELLED)
        return try {
            call.execute().use(::toExchange)
        } catch (timeout: InterruptedIOException) {
            // 超时（含 callTimeout）：结果未知，调用方按「没有统一外壳」处理
            attempt.notSent() ?: Exchange.Transport(if (attempt.invalidated()) KIND_CANCELLED else KIND_TIMEOUT, null)
        } catch (io: IOException) {
            // 断连、连接失败、被拦下的底层重发；写出之后被取消（撤回同意、切换环境）
            attempt.notSent() ?: Exchange.Transport(if (attempt.invalidated()) KIND_CANCELLED else KIND_DISCONNECT, null)
        } finally {
            inFlight.unregister(call)
        }
    }

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
                .addNetworkInterceptor(SingleAttemptInterceptor)
                .build()
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

        /** 撤回同意或切换环境作废了这次发送（不看 Call.isCanceled：callTimeout 到期也会取消 Call）。 */
        fun invalidated(): Boolean = inFlight.epochs() != guard.epochs

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
            if (attempt.attempts.incrementAndGet() > 1) throw IOException("底层重发已拦下")
            val reason = attempt.inFlight.admit(attempt.guard.epochs, attempt.guard.admit)
            if (reason != null) {
                attempt.blocked = reason
                throw IOException("写出前核对未放行：$reason")
            }
            attempt.committed = true
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
        private const val HTTP_NOT_MODIFIED = 304
        private const val BYTE_MASK = 0xff
        private const val ASCII_LIMIT = 0x80
        private val JSON = "application/json; charset=utf-8".toMediaType()

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
