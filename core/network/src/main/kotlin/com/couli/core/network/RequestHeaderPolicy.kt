package com.couli.core.network

import com.couli.core.generated.ApiOperation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 请求头名（03 §4.2、04 §5）。 */
object HeaderNames {
    const val AUTHORIZATION = "Authorization"
    const val APP_ID = "X-App-Id"
    const val PLATFORM = "X-Platform"
    const val APP_VERSION = "X-App-Version"
    const val BUILD = "X-Build"
    const val DEVICE_ID = "X-Device-Id"
    const val CHANNEL = "X-Channel"
    const val TRACE_ID = "X-Trace-Id"
    const val IDEMPOTENCY_KEY = "Idempotency-Key"
    const val TIMESTAMP = "X-Timestamp"
    const val NONCE = "X-Nonce"
    const val SIGN = "X-Sign"
    const val STEP_UP_TOKEN = "X-Step-Up-Token"
    const val RETRY_AFTER = "Retry-After"
    const val DATE = "Date"

    /** 个性化推荐已关闭（APP-12 G03，BR-ID-13）：值固定为 off；只读本机偏好，基本模式不带。 */
    const val PERSONALIZATION = "X-Personalization"
    const val PERSONALIZATION_OFF = "off"

    /** 配置拉取的条件请求（03 §4.3，APP-03b）。 */
    const val IF_NONE_MATCH = "If-None-Match"
    const val ETAG = "ETag"

    /** 公共头里不含凭据与设备标识的部分：基本模式、撤回清理未完成时也带。 */
    val ANONYMOUS: List<String> = listOf(APP_ID, PLATFORM, APP_VERSION, BUILD, CHANNEL, TRACE_ID)

    /** 签名头（BR-ID-09）。 */
    val SIGNATURE: List<String> = listOf(TIMESTAMP, NONCE, SIGN)
}

/** 隐私状态（与向量的 raw 同名）。 */
object PrivacyRaw {
    /** 已同意隐私政策：完整功能。 */
    const val FULL = "full"

    /** 基本模式（BR-ID-11）：匿名只读白名单。 */
    const val BASIC = "basic"

    /** 尚未决定（首启弹窗之前或之中）：只允许配置与协议文本（BR-ID-11，01 F-PRIV-03）。 */
    const val UNDECIDED = "undecided"
}

/** 会话作用域（04 §2.5 session_scope）。 */
object SessionScopeRaw {
    const val FULL = "full"
    const val DELETION_ONLY = "deletion_only"
}

/** 本地拒绝原因（三端测试接线名称）。 */
object LocalRejectReason {
    const val ENVIRONMENT_UNSETTLED = "environment_unsettled"
    const val BASIC_MODE_DENIED = "basic_mode_denied"
    const val SESSION_SCOPE_DENIED = "session_scope_denied"
    const val DEVICE_NOT_READY = "device_not_ready"

    /** 四个 step-up 操作的键结果未知或在途时，新的提交被挡（BR-ID-10「敏感操作的幂等键」）。 */
    const val PENDING_KEY_UNRESOLVED = "pending_key_unresolved"

    /** 四个 step-up 操作取不到可信的会话账号（SessionTokens.userId），不发（R1-11）。 */
    const val ACCOUNT_UNKNOWN = "account_unknown"

    /** 四个 step-up 操作的未决记录没能写入安全存储，不发（R1-11：持久化成功是发送的前置条件）。 */
    const val PENDING_STORE_FAILED = "pending_store_failed"

    /** 未决记录属于别的账号：当前会话账号与记录的账号不一致（换了账号或已退出），不查、不发（lessons 15）。 */
    const val ACCOUNT_MISMATCH = "account_mismatch"

    /**
     * 刷新后、校时后或重新注册后的重放：发出前会话已不是这次重放所属的那一代（退出、切换账号、重新登录），不替新会话重放旧请求
     * （lessons 14）。
     */
    const val SESSION_CHANGED = "session_changed"

    /**
     * 撤回同意或切换环境后（隐私世代、环境世代已变）：这次调用已作废，在途的请求被取消、还没写出的不再写出，内部续发
     * （GET 重试、刷新后重放等）也不再发出（A-R2-01）。
     */
    const val CALL_CANCELLED = "call_cancelled"

    /** 登录响应 code=0，但令牌没能写进安全存储（[ApiResult.SessionNotSaved]）：不算登录成功（lessons 40，与 iOS 同名）。 */
    const val SESSION_SAVE_FAILED = "session_save_failed"

    /** 登录响应 code=0，但 data.tokens 缺项（[ApiResult.SessionNotSaved]，与 iOS 同名）。 */
    const val INVALID_SESSION_TOKENS = "invalid_session_tokens"

    /**
     * 会话读取失败（安全存储此刻读不出，可能有会话；与 iOS 同名，编排者裁定 2026-10-07）：x-auth 不是 none 的接口本地拒绝，不能当成
     * 没有会话匿名发出；不刷新、不清会话。读到「不存在」或内容损坏（按没有会话、尽力删除）不是这种情况。
     */
    const val SESSION_UNREADABLE = "session_unreadable"

    /** 等待中的刷新失败、原请求不发出（[ApiResult.SessionEnded.reason]，三端统一名，编排者裁定 2026-10-07）。 */
    const val SESSION_CLEARED = "session_cleared"
}

/**
 * 判定请求头与能否发送的输入（三端测试接线约定）。
 * @property privacy [PrivacyRaw]；不认识的取值按 [PrivacyRaw.UNDECIDED]（最保守）。
 * @property sessionScope [SessionScopeRaw]；不认识的按 deletion_only（最保守）。
 * @property hasAccessToken 本机有 access_token（凭据可读时才读）。
 * @property deviceId 服务端签发的 device_id；未注册为 null。
 * @property environmentSettled 环境闸门已通过（EnvironmentGate）。
 * @property credentialsReadable 撤回同意的本地清理已完成（CredentialAccessGuard.readable）。
 */
data class NetworkRequestState(
    val privacy: String,
    val sessionScope: String,
    val hasAccessToken: Boolean,
    val deviceId: String?,
    val environmentSettled: Boolean,
    val credentialsReadable: Boolean,
)

/** 判定结果：[send] 为 false 时本地拒绝、不发出，[localRejectReason] 为原因；[headerNames] 为要带的头。 */
data class HeaderDecision(
    val send: Boolean,
    val localRejectReason: String?,
    val headerNames: List<String>,
    /** 带 Authorization（凭据可读、有令牌、接口不是 x-auth=none）。 */
    val includeAuthorization: Boolean = false,
    /** 带 X-Device-Id。 */
    val includeDeviceId: Boolean = false,
) {
    val includeSignature: Boolean get() = HeaderNames.SIGN in headerNames
    val includeIdempotencyKey: Boolean get() = HeaderNames.IDEMPOTENCY_KEY in headerNames
}

/**
 * 公共头、签名头与本地放行判定（03 §4.1、§4.2；BR-ID-01 细则「受限会话」；BR-ID-09；BR-ID-11）。纯函数，三端同一规则。
 *
 * 判定顺序：环境未就绪 → 不发；隐私不是已同意 → 只放行匿名白名单（基本模式 BR-ID-11；尚未决定只放行配置与协议文本），
 * 剥离 Authorization、X-Device-Id、签名头；撤回清理未完成（credentialsReadable = false）同样只放行基本模式匿名白名单
 * （R1-02）；会话 deletion_only → 只放行 x-session-scopes 含 deletion_only 的接口，
 * 按请求体才接受的接口再看请求体（[RESTRICTED_BODY_RULES]，没有请求体按不接受）；签名接口没有可读的设备凭据 → 不发。
 */
object RequestHeaderPolicy {
    /**
     * 基本模式匿名白名单（BR-ID-11）：配置、搜索、商品详情、帮助与规则文章列表。
     * TODO(N-19): 首页内容（/v1/pages/home）与 GET /v1/dict 在锁定 openapi 里没有 operationId；基本模式文章详情
     * （getArticle，需区分协议 / 帮助类别）未定，暂不放行（rule-gaps 第 2 条）。
     */
    val BASIC_MODE_ALLOWED: Set<String> = setOf("getConfig", "searchProducts", "getProduct", "listArticles")

    /**
     * 隐私尚未决定时（首启弹窗前后）只允许配置与协议文本（BR-ID-11「同意前禁止除配置和协议文本外的网络请求」）。
     * TODO(N-19): 协议正文接口（文章详情按类别）未定，暂只放行列表。
     */
    val UNDECIDED_ALLOWED: Set<String> = setOf("getConfig", "listArticles")

    /** 不带 Idempotency-Key 的写接口（openapi：Takes no Idempotency-Key header）。 */
    val NO_IDEMPOTENCY_KEY: Set<String> = setOf("abandonIdempotencyKey")

    /**
     * deletion_only 会话下按请求体才接受的接口（BR-ID-01 细则「受限会话」表与「版本策略」表）。
     * 没有请求体时一律不接受（rule-gaps 第 1 条：不捏造判定结果）。
     */
    val RESTRICTED_BODY_RULES: Map<String, (JsonObject) -> Boolean> =
        mapOf(
            "sendSmsCode" to { body -> body.text("purpose") == "login" || body.isDeletionStepUp() },
            "createOauthAttempt" to { body -> body.text("purpose") == "login" || body.isDeletionStepUp() },
            "stepUp" to { body -> body.text("action") == ACCOUNT_DELETION },
            "abandonIdempotencyKey" to { body -> body.text("action") == ACCOUNT_DELETION },
            "recordConsent" to { body ->
                body.flag("accepted") == false || body.text("type") in setOf("privacy", "agreement")
            },
        )

    private const val ACCOUNT_DELETION = "account_deletion"

    @Suppress("CyclomaticComplexMethod", "ReturnCount") // 按规则顺序逐项本地拒绝，再决定要带的头
    fun decide(
        state: NetworkRequestState,
        operation: ApiOperation,
        body: JsonObject? = null,
    ): HeaderDecision {
        val privacy = state.privacy.takeIf { it == PrivacyRaw.FULL || it == PrivacyRaw.BASIC } ?: PrivacyRaw.UNDECIDED
        val rejection =
            when {
                !state.environmentSettled -> LocalRejectReason.ENVIRONMENT_UNSETTLED

                privacy == PrivacyRaw.BASIC && operation.operationId !in BASIC_MODE_ALLOWED -> LocalRejectReason.BASIC_MODE_DENIED

                privacy == PrivacyRaw.UNDECIDED && operation.operationId !in UNDECIDED_ALLOWED -> LocalRejectReason.BASIC_MODE_DENIED

                // 撤回清理未完成（隐私投影可能还是 full）：与基本模式同一个匿名白名单，不只剥离请求头（R1-02）——
                // registerDevice 这类请求体里带设备标识的接口不得发出
                !state.credentialsReadable && operation.operationId !in BASIC_MODE_ALLOWED -> LocalRejectReason.BASIC_MODE_DENIED

                !scopeAllows(state.sessionScope, operation, body) -> LocalRejectReason.SESSION_SCOPE_DENIED

                else -> null
            }
        if (rejection != null) return reject(rejection)

        val credentials = privacy == PrivacyRaw.FULL && state.credentialsReadable
        val deviceId = state.deviceId?.takeIf { credentials && it.isNotEmpty() }
        if (operation.signed && deviceId == null) return reject(LocalRejectReason.DEVICE_NOT_READY)

        val authorization = credentials && state.hasAccessToken && operation.auth != "none"
        val names = HeaderNames.ANONYMOUS.toMutableList()
        if (authorization) names += HeaderNames.AUTHORIZATION
        if (deviceId != null) names += HeaderNames.DEVICE_ID
        if (!operation.isRead && operation.operationId !in NO_IDEMPOTENCY_KEY) names += HeaderNames.IDEMPOTENCY_KEY
        if (operation.signed) names += HeaderNames.SIGNATURE
        return HeaderDecision(
            send = true,
            localRejectReason = null,
            headerNames = names,
            includeAuthorization = authorization,
            includeDeviceId = deviceId != null,
        )
    }

    /** 当前会话作用域能否发这个接口（BR-ID-01 细则「受限会话」）。 */
    @Suppress("ReturnCount") // 受限会话表的三种情况逐条返回
    fun scopeAllows(
        sessionScope: String,
        operation: ApiOperation,
        body: JsonObject? = null,
    ): Boolean {
        if (sessionScope == SessionScopeRaw.FULL) return SessionScopeRaw.FULL in operation.sessionScopes
        if (SessionScopeRaw.DELETION_ONLY !in operation.sessionScopes) return false
        val rule = RESTRICTED_BODY_RULES[operation.operationId] ?: return true
        return body != null && rule(body)
    }

    private fun reject(reason: String) = HeaderDecision(send = false, localRejectReason = reason, headerNames = emptyList())

    private fun JsonObject.isDeletionStepUp(): Boolean = text("purpose") == "step_up" && text("action") == ACCOUNT_DELETION

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.flag(key: String): Boolean? =
        (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()
}

/** 自动重试策略（03 §4.2 超时、重试与弱网；TECH-20、TRADE-22）。 */
data class RetryDecision(
    val autoRetries: Int,
    val backoffMillis: Long,
    val timeoutMillis: Long,
)

object RetryPolicy {
    const val TIMEOUT_MILLIS = 10_000L
    const val OPEN_LINK_TIMEOUT_MILLIS = 8_000L
    const val READ_BACKOFF_MILLIS = 500L
    private const val OPEN_LINK = "openLink"

    /**
     * 读接口（GET）超时 / 断连自动重试 1 次（退避 500 毫秒）；写接口不自动重试；`POST /v1/links/{link_id}/open` 8 秒结束。
     * TODO(N-19): 读接口 HTTP 5xx（无外壳）是否重试未规定（rule-gaps 第 4 条），ApiClient 只对超时、断连重试。
     */
    fun forOperation(operation: ApiOperation): RetryDecision {
        val timeout = if (operation.operationId == OPEN_LINK) OPEN_LINK_TIMEOUT_MILLIS else TIMEOUT_MILLIS
        return if (operation.isRead) {
            RetryDecision(autoRetries = 1, backoffMillis = READ_BACKOFF_MILLIS, timeoutMillis = timeout)
        } else {
            RetryDecision(autoRetries = 0, backoffMillis = 0L, timeoutMillis = timeout)
        }
    }
}
