package com.couli.core.network

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** 本 App 固定的公共头取值（03 §4.2）。 */
data class ClientIdentity(
    val appId: String,
    /** client_platform：android。 */
    val platform: String,
    /** SemVer（不带构建类型后缀）。 */
    val appVersion: String,
    val build: String,
    /** install_channel。 */
    val channel: String,
)

/**
 * 设备凭据（BR-ID-09）：服务端签发的 device_id 与 install_secret。生产实现是设备注册 [DeviceRegistry]（APP-03b）；只在凭据可读时读取。
 */
interface DeviceCredentials {
    val deviceId: String?
    val installSecret: String?
}

/** 设备还没注册（测试与不注册设备的场景）。 */
object UnregisteredDevice : DeviceCredentials {
    override val deviceId: String? = null
    override val installSecret: String? = null
}

/** 强更检查结论（ErrorActionState.update 的 raw）。 */
object UpdateRaw {
    const val SUPPORTED = "supported"
    const val BELOW = "below"

    /** 未知或检查中。 */
    const val UNKNOWN = "unknown"
}

/**
 * 每次发请求前取的状态。
 * @property privacy [PrivacyRaw]（已同意 / 基本模式 / 尚未决定）。
 * @property environmentSettled EnvironmentGate 已通过；未通过时不发任何请求。
 * @property credentialsReadable 撤回同意的本地清理已完成；false 时不读、不带任何凭据。
 * @property update [UpdateRaw]：强更检查结论（checking 按 unknown）。
 */
data class NetworkContext(
    val privacy: String,
    val environmentSettled: Boolean,
    val credentialsReadable: Boolean,
    val update: String,
)

/** 网络层对外的回调。 */
class NetworkHooks(
    /** 进强更（Update.on10405）：确需强更的 10405，或强更检查未知期间登录 / 刷新拿到 deletion_only（min 为 null）。 */
    val onForceUpdate: (minSupportedVersion: String?) -> Unit = {},
    /** 每个错误 Effect（页面也能从 [ApiResult.Failure] 拿到）。 */
    val onEffect: (ErrorEffect) -> Unit = {},
    /**
     * 10402：重新注册设备（APP-03b [DeviceRegistry.reRegister]）；参数为这次请求带的 X-Device-Id（被服务端拒绝的那个，单飞判重用）。
     * 成功返回 true 后沿用原键重放 1 次。registerDevice 自己收到 10402 不调它（不递归）。
     */
    val reRegisterDevice: (usedDeviceId: String?) -> Boolean = { false },
)

/**
 * 一次调用。
 * @property operationId openapi 的 operationId（ApiOperations）。
 * @property pathParams 路径参数（按路径段编码）。
 * @property query 原样的查询串（不含 "?"；签名按发出的字节计算）。
 * @property body 请求体原始文本（UTF-8；签名与重放都用这份字节，不重新序列化）。
 * @property idempotencyKey 沿用的 Idempotency-Key；写操作缺省时生成新键。
 * @property stepUpToken step-up 操作的 X-Step-Up-Token。
 * @property summary 未决记录的展示摘要。四个 step-up 操作的账号不由调用方传入，取自 [SessionTokens.userId]（R1-11）。
 * @property fromProductCard 发起页有可重新转链的商品卡（30144 输出 refetch_product）；App 内链接落地页 LinkLanding 传 false。
 * @property ifNoneMatch 读接口的 If-None-Match（原样发送上次成功响应的 ETag，03 §4.3）；304 时结果为 [ApiResult.NotModified]。
 * @property ownership 调用方自带的归属（APP-03b R2-02，设备注册）：有它时这次调用按其中的世代绑定（不再取当前世代），每次写出前的
 *   守卫另外核对 [CallOwnership.stillOwned]；失效即 call_cancelled，不会被重新绑定到切换后的新环境。
 */
data class ApiCall(
    val operationId: String,
    val pathParams: Map<String, String> = emptyMap(),
    val query: String? = null,
    val body: String? = null,
    val idempotencyKey: String? = null,
    val stepUpToken: String? = null,
    val summary: String = "",
    val fromProductCard: Boolean = true,
    val ifNoneMatch: String? = null,
    val ownership: CallOwnership? = null,
)

/**
 * 调用方的归属（APP-03b R2-02）：[epochs] 是调用方任务开始时绑定的隐私 / 环境世代；[stillOwned] 由调用方在自己的同步区里核对
 * 其余世代（如设备注册的清理世代）与隐私状态。ApiClient 在绑定调用归属、每次写出前（网络拦截器）都核对两者。
 */
class CallOwnership(
    val epochs: CallEpochs,
    val stillOwned: () -> Boolean,
)

/** 调用结果。 */
sealed interface ApiResult {
    /** code = 0。[etag] 为响应头 ETag（不透明字符串，原样保存）。 */
    data class Success(
        val data: JsonElement?,
        val traceId: String?,
        val idempotencyKey: String?,
        val etag: String? = null,
    ) : ApiResult

    /** 带 If-None-Match 的读请求得到 304：内容未变（03 §4.3）。[etag] 为响应头 ETag（可能没有）。 */
    data class NotModified(
        val etag: String?,
    ) : ApiResult

    /** 业务错误码，[effect] 为 ErrorActionMapper 的输出。 */
    data class Failure(
        val code: Int,
        val msg: String,
        val data: JsonObject,
        val traceId: String?,
        val effect: ErrorEffect,
        val idempotencyKey: String?,
    ) : ApiResult

    /**
     * 放弃未决键：作废接口返回 abandoned，键已作废、原操作没有执行（A-R2-06）。[idempotencyKey] 为被放弃的键。
     * 作废接口返回 completed 时不用它：按原操作的结果给 [Success] 或 [Failure]（含 Effect）。
     */
    data class Abandoned(
        val idempotencyKey: String,
        val traceId: String?,
    ) : ApiResult

    /** 本地拒绝，没有发出（[LocalRejectReason]）。 */
    data class LocalReject(
        val reason: String,
    ) : ApiResult

    /**
     * 会话已结束、原请求没有发出（R1-09、R1-10）：发送前的受限会话刷新屏障失败（刷新被拒、刷新结果作废）。
     * [effect] 为清会话后跳登录，强更状态下为清会话、停在强更页。[reason] 为三端统一的结果原因 session_cleared（编排者裁定：
     * 等待中的刷新失败、原请求不发出；各端类型名可以不同）。
     */
    data class SessionEnded(
        val effect: ErrorEffect,
        val reason: String = LocalRejectReason.SESSION_CLEARED,
    ) : ApiResult

    /**
     * 登录响应 code=0，但会话没有保存（lessons 40）：令牌写不进安全存储（session_save_failed）、data.tokens 缺项
     * （invalid_session_tokens），或响应到达时已作废 / 已换代（call_cancelled / session_changed）。不是登录成功：调用方停在
     * 登录页提示失败，当前会话保持原样。
     */
    data class SessionNotSaved(
        val reason: String,
        val traceId: String?,
    ) : ApiResult

    /**
     * 没有带统一外壳的响应：timeout / disconnect / no_envelope（网关错误页等）。
     * [pendingKeyUnknown] = 四个 step-up 操作的键因此进入结果未知（发起页进入待确认）。
     */
    data class TransportFailure(
        val kind: String,
        val httpStatus: Int?,
        val idempotencyKey: String?,
        val pendingKeyUnknown: Boolean,
    ) : ApiResult
}
