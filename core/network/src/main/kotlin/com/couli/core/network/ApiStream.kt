package com.couli.core.network

import com.couli.core.generated.ApiOperations
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.Request
import okhttp3.Response

// APP-10b §2 第 1 条（F-X-04）：ApiClient 的流式通道的类型与纯判定（三端同名：ApiStreamRequest、ApiStreamSink、ApiStreamHandle、ApiClient.stream）。执行在
// ApiClient.stream（与普通请求共用 operation 查表、路径参数编码、公共头、可选 Authorization、调用归属、每次实际写出前的 admit / sendAllowed、单飞刷新、10402
// 重新注册、10405、撤回 / 切环境的同步取消；HttpTransport 由共享 OkHttpClient.newBuilder() 派生流式客户端：readTimeout / callTimeout 为 0、connectTimeout
// 沿用、retryOnConnectionFailure(false)、不跟随重定向）。
// - 只给契约里登记为流式的 operation 用（ApiStreamOperations；表外本地拒绝）。
// - 请求头另加 Accept: text/event-stream（G18）与 Accept-Encoding: identity（G17）；不签名、不带 Idempotency-Key（7 个 agent operation 都是
//   x-signed:false、x-idempotent:false）。
// - 响应头到达后按 Content-Type 分流（G19，ApiStreamRoutingPolicy）：text/event-stream（忽略大小写与参数）且 2xx → 头交 onHeaders，之后 ResponseBody.source()
//   逐块读出的原始字节交 onBytes（IO 线程；行与上限由 Core 的 AgentSse 在解码前按字节判定）；其他 → 读完整正文按统一外壳解包，交 onEnvelope(ApiResult)。
//   不按 HTTP 状态码分支。
// - 10002 / 10402：只在拿到 JSON 外壳、尚未收到任何 SSE 字节时，单飞刷新 / 重新注册成功后以同一请求体（同一 client_msg_id）至多重放一次，重放前重新核对归属与
//   admit（G23，ApiStreamRequestPolicy.replay）。

/** 契约里响应为 text/event-stream 的 operation（40cfc268 openapi：只有 sendAgentMessage；生成表没有这个标记，鸿蒙 G51 同口径）。表外 operation 一律本地拒绝。 */
object ApiStreamOperations {
    val STREAMING: Set<String> = setOf("sendAgentMessage")

    fun isStreaming(operationId: String): Boolean = operationId in STREAMING && ApiOperations.byId(operationId) != null

    /** 表外 operation 走流式入口：本地拒绝（不发出）。 */
    const val NOT_STREAMING = "stream_not_allowed"
}

/**
 * 一次流式请求（只有 POST 请求体；路径参数照普通请求编码）。[ownership]：调用方的归属（每次写出前的守卫核对 [CallOwnership.stillOwned] 与
 * [CallOwnership.sendAllowed]；[ApiClient.cancelOwned] 同步取消）；[expectedAccount]：点击时固定的账号。
 */
class ApiStreamRequest(
    val operationId: String,
    val pathParams: Map<String, String>,
    val body: String,
    val ownership: CallOwnership? = null,
    val expectedAccount: String? = null,
) {
    override fun toString(): String = "ApiStreamRequest($operationId)"
}

/** 流式结果的接收端（IO 线程按到达顺序调用；同一请求至多一个终结：onEnd / onFailure / onEnvelope）。 */
interface ApiStreamSink {
    /** SSE 响应头（小写键）。只在分流为流之后调用一次。 */
    fun onHeaders(
        status: Int,
        headers: Map<String, String>,
    )

    /** 原始字节块（不解码、不切行）。 */
    fun onBytes(chunk: ByteArray)

    /** 流正常结束。 */
    fun onEnd()

    /** 流开始之后的传输失败（断连、超时）：[kind] 为 HttpTransport 的种类。被取消（[ApiStreamHandle.cancel]）不回调。 */
    fun onFailure(kind: String)

    /** 没有成为流：JSON 外壳（含 10002 / 10402 重放后的最终结果）、无外壳、本地没发出、流开始之前的传输失败，都是一个 [ApiResult]（与普通请求同一结果路径）。 */
    fun onEnvelope(result: ApiResult)
}

/** 流式请求的句柄：[cancel] 同步关闭连接，之后到达的字节一律不再交付。线程安全。 */
class ApiStreamHandle internal constructor() {
    private val stopped = AtomicBoolean(false)
    private val canceller = AtomicReference<(() -> Unit)?>(null)
    private val deliveredCount = AtomicInteger()
    private val droppedCount = AtomicInteger()

    val cancelled: Boolean get() = stopped.get()

    /** 已交付给 sink 的字节块数（抓包断言「作废之后 0 块」用）。 */
    val delivered: Int get() = deliveredCount.get()

    /** 作废之后读到、被丢弃的字节块数。 */
    val dropped: Int get() = droppedCount.get()

    /** ApiClient 内部：登记真正的取消动作（已取消就立即执行）。 */
    internal fun bind(cancel: () -> Unit) {
        canceller.set(cancel)
        if (stopped.get()) runCatching(cancel)
    }

    internal fun countDelivered() = deliveredCount.incrementAndGet()

    internal fun countDropped() = droppedCount.incrementAndGet()

    fun cancel() {
        if (!stopped.compareAndSet(false, true)) return
        canceller.get()?.let { runCatching(it) }
    }
}

/** G19 分流结论。 */
data class ApiStreamRoute(
    val route: String,
    /** 外壳 code（流与无外壳为 null）。 */
    val code: Int?,
    /** 外壳 code=0 但不是流：协议错误，本条按连接中断、同键【重试】。 */
    val protocolError: Boolean,
) {
    companion object {
        const val STREAM = "stream"
        const val ENVELOPE = "envelope"
        const val NO_ENVELOPE = "no_envelope"
    }
}

object ApiStreamRoutingPolicy {
    private const val HTTP_OK_MIN = 200
    private const val HTTP_OK_MAX = 299
    const val EVENT_STREAM = "text/event-stream"

    /** text/event-stream（忽略大小写与参数）且 2xx。 */
    fun isStream(
        status: Int,
        contentType: String?,
    ): Boolean {
        if (status !in HTTP_OK_MIN..HTTP_OK_MAX || contentType == null) return false
        return contentType.substringBefore(';').trim().lowercase() == EVENT_STREAM
    }

    /** 响应头 + 完整正文（只在不是流时才读完整正文）→ 分流结论。正文能解出统一外壳就是外壳（不看状态码与类型），否则无外壳。 */
    @Suppress("ReturnCount") // 逐项提前返回
    fun route(
        status: Int,
        contentType: String?,
        body: String,
    ): ApiStreamRoute {
        if (isStream(status, contentType)) return ApiStreamRoute(ApiStreamRoute.STREAM, null, false)
        val code = envelopeCode(body) ?: return ApiStreamRoute(ApiStreamRoute.NO_ENVELOPE, null, false)
        return ApiStreamRoute(ApiStreamRoute.ENVELOPE, code, code == 0)
    }

    /** 统一外壳的 code（JSON 对象且 code 为整数）；不是外壳为 null。 */
    fun envelopeCode(body: String): Int? {
        val o = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        return (o["code"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
    }

    /** 向量接线（contentRouting 组的分流行）：{http, contentType, body} → 路由、交给哪个 sink 回调、外壳 code、本条状态。 */
    @Suppress("UnusedParameter") // nowMs 与向量入口同形
    fun of(
        input: JsonObject,
        nowMs: Long,
    ): JsonObject {
        val http = (input["http"] as? JsonPrimitive)?.intOrNull ?: 0
        val type = (input["contentType"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val body = (input["body"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        val r = route(http, type, body)
        val o = linkedMapOf<String, JsonElement>()
        o["routeRaw"] = JsonPrimitive(r.route)
        o["sinkRaw"] = JsonPrimitive(if (r.route == ApiStreamRoute.STREAM) "onBytes" else "onEnvelope")
        o["code"] = r.code?.let(::JsonPrimitive) ?: JsonNull
        o["protocolError"] = JsonPrimitive(r.protocolError)
        if (r.route == ApiStreamRoute.NO_ENVELOPE || r.protocolError) {
            // 没有外壳 / 外壳成功却不是流：本条「连接中断」，【重试】沿用同一 client_msg_id（G19）
            o["statusRaw"] = JsonPrimitive("disconnected")
            o["reuseClientMsgId"] = JsonPrimitive(true)
        }
        return JsonObject(o)
    }
}

/** G23 重放的恢复方式。 */
object ApiStreamRecovery {
    const val REFRESH = "single_flight_refresh"
    const val REGISTER = "register_device"
}

object ApiStreamRequestPolicy {
    /** 流式请求另加的头（G17、G18）。 */
    const val ACCEPT = ApiStreamRoutingPolicy.EVENT_STREAM
    const val ACCEPT_ENCODING = "identity"

    /** 刷新 / 重新注册后的重放上限（G23）。 */
    const val MAX_REPLAYS = 1
    private const val REFRESH_CODE = 10002
    private const val REGISTER_CODE = 10402

    fun extraHeaders(headers: MutableMap<String, String>) {
        headers[HeaderNames.ACCEPT] = ACCEPT
        headers[HeaderNames.ACCEPT_ENCODING] = ACCEPT_ENCODING
    }

    /** 外壳错误码 → 重放的恢复方式（null = 不重放）：10002 → 单飞刷新，10402 → 重新注册；已经收到 SSE 字节、或已经重放过一次就不再重放。 */
    fun replay(
        code: Int,
        sseBytesReceived: Long,
        priorReplays: Int,
    ): String? {
        if (sseBytesReceived > 0 || priorReplays >= MAX_REPLAYS) return null
        return when (code) {
            REFRESH_CODE -> ApiStreamRecovery.REFRESH
            REGISTER_CODE -> ApiStreamRecovery.REGISTER
            else -> null
        }
    }

    /** 向量接线（contentRouting 组的 request / replay 行）。判定与 ApiClient.stream 共用 [RequestHeaderPolicy.decide] 与 [replay]。 */
    @Suppress("UnusedParameter") // nowMs 与向量入口同形
    fun of(
        input: JsonObject,
        nowMs: Long,
    ): JsonObject = if (text(input, "modeRaw") == "replay") replayJson(input) else requestJson(input)

    private fun text(
        o: JsonObject,
        key: String,
    ): String? = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun flag(
        o: JsonObject,
        key: String,
    ): Boolean? = (o[key] as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.content?.toBooleanStrictOrNull()

    private fun int(
        o: JsonObject,
        key: String,
    ): Int? = (o[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    @Suppress("ReturnCount", "CyclomaticComplexMethod") // 不准入、准入依次返回
    private fun requestJson(input: JsonObject): JsonObject {
        val op = text(input, "operationRaw").orEmpty()
        val identity = text(input, "identityRaw") ?: "guest"
        val credentialsAllowed = flag(input, "credentialsAllowed") == true
        val privacyCurrent = flag(input, "privacyCurrent") == true
        val operation = ApiOperations.byId(op)
        // 读凭据之前的三道闸门（与 ApiClient 同序）：表外 operation；会员但账号未知（Agent 入口层，FU-AND-0714 第 4 条）；隐私未同意当前版本 / 凭据读取屏障关闭
        // （CredentialReadBarrier 与 credentialsReadable）——不通过一次都不读凭据
        var credentialReads = 0
        var decision: HeaderDecision? = null
        if (operation != null && ApiStreamOperations.isStreaming(op) && identity != MEMBER_UNKNOWN) {
            val readable = credentialsAllowed && privacyCurrent
            if (readable) credentialReads = 1
            decision =
                RequestHeaderPolicy.decide(
                    NetworkRequestState(
                        privacy = if (privacyCurrent) PrivacyRaw.FULL else PrivacyRaw.UNDECIDED,
                        sessionScope = SessionScopeRaw.FULL,
                        hasAccessToken = readable && text(input, "token") != null,
                        deviceId = if (readable) SYNTHETIC_DEVICE else null,
                        environmentSettled = true,
                        credentialsReadable = credentialsAllowed,
                    ),
                    operation,
                )
        }
        val sends = decision != null && decision.send && flag(input, "admitted") == true
        val o =
            linkedMapOf<String, JsonElement>(
                "sendsRequests" to JsonPrimitive(sends),
                "credentialReads" to JsonPrimitive(credentialReads),
            )
        val d = decision?.takeIf { sends }
        if (d == null) {
            o["resultRaw"] = JsonPrimitive("notSent")
            return JsonObject(o)
        }
        o["resultRaw"] = JsonPrimitive("sent")
        o["signed"] = JsonPrimitive(d.includeSignature)
        o["idempotencyKeyPresent"] = JsonPrimitive(d.includeIdempotencyKey)
        o["authorizationPresent"] = JsonPrimitive(d.includeAuthorization)
        o["accept"] = JsonPrimitive(ACCEPT)
        o["acceptEncoding"] = JsonPrimitive(ACCEPT_ENCODING)
        // G15：流式基址缺省等于 API 基址（stream. 子域待部署确定）
        o["baseUrl"] = text(input, "streamBaseUrl")?.let(::JsonPrimitive) ?: (input["baseUrl"] ?: JsonNull)
        val hidden = setOf(HeaderNames.AUTHORIZATION, HeaderNames.IDEMPOTENCY_KEY) + HeaderNames.SIGNATURE
        o["publicHeaderNames"] = JsonArray(d.headerNames.filter { it !in hidden }.map(::JsonPrimitive))
        return JsonObject(o)
    }

    @Suppress("ReturnCount") // 逐项提前返回
    private fun replayJson(input: JsonObject): JsonObject {
        val recovery = replay(int(input, "code") ?: 0, (int(input, "sseBytesReceived") ?: 0).toLong(), int(input, "priorReplays") ?: 0)
        val o =
            linkedMapOf<String, JsonElement>(
                "recoveryRaw" to (recovery?.let(::JsonPrimitive) ?: JsonNull),
                "rebindIdentity" to JsonPrimitive(false),
            )
        if (recovery == null) {
            o["replays"] = JsonPrimitive(0)
            return JsonObject(o)
        }
        if (flag(input, "recoverySucceeded") != true || flag(input, "replayAdmitted") != true) {
            // 恢复失败、恢复后归属变了（换号、切环境）：这一条按没发出结束，不改绑到新身份，输入框不恢复（G23、APP-10 H11）
            o["replays"] = JsonPrimitive(0)
            o["resultRaw"] = JsonPrimitive("notSent")
            o["restoreInput"] = JsonPrimitive(false)
            return JsonObject(o)
        }
        o["replays"] = JsonPrimitive(1)
        o["sameBody"] = JsonPrimitive(true)
        o["clientMsgId"] = (input["body"] as? JsonObject)?.get("client_msg_id") ?: JsonNull
        return JsonObject(o)
    }

    private const val MEMBER_UNKNOWN = "member_unknown"
    private const val SYNTHETIC_DEVICE = "synthetic-device"
}

/**
 * Debug / Staging 的本机替身在**实际传输入口**的接口（APP-10b §2 第 1、13 条，APP-11 共性第 10、16 条）：HttpTransport 在写出前核对（归属、发送前闸门、
 * 放行判定）通过之后才问它；它在每次回答时现判 DebugMockGate（开关 + MOCK_DATA + 环境就绪），不回答就交真实网络。Release 不接（ApiClient 构造参数为 null）。
 */
interface LocalExchange {
    /** 这次请求归本机替身回答（只看路径；不读请求体）。 */
    fun handles(request: Request): Boolean

    /**
     * 回答（null = 交真实网络）。[canceled]：这次调用已被取消（[ApiStreamHandle.cancel]、撤回同意、切换环境）——流式正文应据此尽快结束。在 OkHttp 的调用线程上调用。
     */
    fun answer(
        request: Request,
        canceled: () -> Boolean,
    ): Response?
}
