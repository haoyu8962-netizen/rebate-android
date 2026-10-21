// 错误码是契约取值（contracts/error-codes.yaml），按码列表直接写，不另起常量名。
@file:Suppress("MagicNumber")

package com.couli.core.network

import com.couli.core.generated.ApiOperation
import com.couli.generated.DefaultTexts
import com.couli.generated.ErrorCodes
import com.couli.generated.RetryKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Effect 种类（与 specs/network-vectors.json 的 effectKinds 同名；[NAVIGATE]、[PAGE_ACTION] 是向量之外的码用的）。 */
enum class ErrorEffectKind(
    val raw: String,
) {
    LOGIN("login"),
    BIND_PHONE("bind_phone"),
    CONSENT("consent"),
    STEP_UP("step_up"),
    AUTHORIZE("authorize"),
    GUIDE_ONLY("guide_only"),
    REFRESH_REPLAY("refresh_replay"),
    REGISTER_DEVICE_REPLAY("register_device_replay"),
    FORCE_UPDATE("force_update"),
    REFRESH_ONLY("refresh_only"),
    CLEAR_SESSION_LOGIN("clear_session_login"),
    CLEAR_SESSION_STAY_FORCE_UPDATE("clear_session_stay_force_update"),
    DISABLE_BUTTON("disable_button"),
    TOAST("toast"),
    GENERIC_ERROR("generic_error"),
    FALLBACK_MESSAGE("fallback_message"),
    DISABLE_WITH_TOAST("disable_with_toast"),
    DISABLE_WITHOUT_TOAST("disable_without_toast"),

    /** 跳到 [ErrorEffect.route]，不重放（封禁说明、注销进度、搜索、找回记录、收款账号等）。 */
    NAVIGATE("navigate"),

    /** 发起页按码自己的界面处理（卡片置灰、无返利购买按钮、并号确认、申诉入口等），文案取 [ErrorEffect.textKey]。 */
    PAGE_ACTION("page_action"),

    /**
     * 30144：按卡片的 product_key + item_ref 自动重新请求转链、替换卡片，之后仍须用户再次点击（不重放原请求）。
     * 没有卡片数据的入口（App 内链接落地页 LinkLanding）不输出它，改为 [PAGE_ACTION]（空态、不自动重试）。
     */
    REFETCH_PRODUCT("refetch_product"),

    /** 40901：保持加载态，稍后查询结果（作废请求得到本码时未决键保持待确认）。 */
    KEEP_LOADING("keep_loading"),

    /** 20001：在 [ErrorEffect.fields] 列出的字段旁提示（文案取 error.20001.<reason> 回落 error.20001），不重放（A-R2-08）。 */
    FIELD_ERROR("field_error"),

    /**
     * 20001 且 data.fields 含 device_hash（设备注册的哈希无效，BR-ID-09 细则「设备标识的无效值」）：不向用户提示（textKey 为 null），
     * 由设备注册重新读取设备标识再注册，不带原值重试（与 iOS 同名，lessons 名字追加）。
     */
    REREAD_DEVICE_HASH("reread_device_hash"),

    /**
     * 20004：第三方授权凭证失效，由用户重新发起第三方授权（重新取授权尝试，不复用原尝试与原凭证），不自动重放（A-R2-08）。
     * [ErrorEffect.route] 为 Login（第三方登录：回到登录页）；为 null 时是二次验证（stepUp）：回到二次验证面板重新授权。
     */
    REAUTHORIZE("reauthorize"),
}

/** 前置动作完成后怎样重新发起原请求（BR-ID-10 细则）。 */
enum class ReplayMode(
    val raw: String,
) {
    SAME_KEY("same_key"),
    NEW_KEY("new_key"),
    NONE("none"),
}

/**
 * 当前强更 / 会话状态。
 * @property update 强更检查：supported / below / unknown（Router 的 checking 按 unknown 传）。
 * @property sessionScope [SessionScopeRaw]。
 * @property appVersion 本机版本（SemVer，不带构建后缀）。
 */
data class ErrorActionState(
    val update: String,
    val sessionScope: String,
    val appVersion: String,
)

/** ErrorActionMapper 的输入（03 §4.2）。[headers] 为响应头；[isReplay] = 本次已是重放（原请求至多重放 1 次）。 */
data class ErrorActionInput(
    val code: Int,
    val data: JsonObject,
    val operation: ApiOperation,
    val headers: Map<String, String>,
    val isReplay: Boolean,
    val state: ErrorActionState,
    /** 发起页有可重新转链的商品卡（30144）；LinkLanding 等没有卡片数据的入口为 false（[ApiCall.fromProductCard]）。 */
    val fromProductCard: Boolean = true,
)

/**
 * 页面消费的 Effect：页面只看它，不自行判断码值（03 §4.2「统一入口」）。
 * @property route 跳转或弹出的路由名（Routes）；不跳为 null。
 * @property textKey 文案键（BR-TEXT-14：error.<code>.<reason> 回落 error.<code>）；null = 不提示或用服务端 msg / 通用兜底。
 * @property disableMillis 按钮禁用时长；null = 不限时（由页面按状态恢复）。
 * @property includeTrace6 通用错误态附 trace_id 后 6 位。
 * @property consentType 10004 的 data.consent_type。
 * @property pendingKeyUnknown 四个 step-up 操作的键已结果未知：发起页进入待确认，不换新键（BR-ID-10）。
 */
data class ErrorEffect(
    val code: Int,
    val kind: ErrorEffectKind,
    val replay: ReplayMode,
    val registerPending: Boolean,
    val route: String? = null,
    val textKey: String? = null,
    val disableMillis: Long? = null,
    val includeTrace6: Boolean = false,
    val consentType: String? = null,
    val pendingKeyUnknown: Boolean = false,
    /** 20001 的 data.fields（要提示的字段名）；其余码为 null。 */
    val fields: List<String>? = null,
    /**
     * 清会话类 Effect（clear_session_login / clear_session_stay_force_update）所属的会话（APP-05a 共性第 26 条）：网络层在确定清理
     * 已发生的那一刻、同一同步区里记下的清理之后的登录世代（[SessionTokens.loginEpoch]）。App 只在此刻的登录世代仍等于它（之后没有
     * 新登录、没有别的清理）时按会话失效处理；不能在回调时读「当前世代」补作归属。其余 Effect 为 null。
     */
    val sessionEpoch: Long? = null,
) {
    val effectKindRaw: String get() = kind.raw
    val replayRaw: String get() = replay.raw
}

/**
 * 错误码 → Effect（03 §4.2「ErrorActionMapper 实现要点」；动作以 error-codes.yaml / 08 §13.11 为准）。
 *
 * - 1xxxx 前置（登录、同意、绑手机）完成后沿用原键并登记 pending_action；3xxxx 前置（平台授权）用新键；
 *   四个 step-up 操作（operation 的 x-step-up）被 10001、10004（任何 consent_type）、10005 或 3xxxx 前置码拦下时只引导，
 *   不重放、不登记 pending；10003 沿用原键；10002、10402 自动沿用原键重放 1 次（再失败走通用错误态）。
 * - 10405 进强更不重放；本机版本不低于 data.min_supported_version（或它为空）而会话是 deletion_only 时只刷新令牌；
 *   reason=no_account 提示先更新、回强更。42901 按 Retry-After（缺省 5 秒）禁用按钮。
 * - 带 data.reason 的取 error.<code>.<reason>，字典没有回落 error.<code>；表外码与废弃码：5xxxx 通用错误态附 trace6，
 *   其余用服务端 msg（[ErrorEffectKind.FALLBACK_MESSAGE]）；表内无专门分支、字典也没有文案键的码
 *   （10008 / 10009；CT-LOCK-02 起不限码段：30154 / 30417 / 44004）同样回落 msg；页面自处理码与置灰码不在此列。
 */
@Suppress("TooManyFunctions") // 每类码一个小判定函数，便于逐条对照 03 §4.2
object ErrorActionMapper {
    private const val LOGIN = "Login"
    private const val BIND_PHONE = "BindPhone"
    private const val AUTH_SHEET = "AuthSheet"
    private const val FORCE_UPDATE = "ForceUpdate"
    private const val REAL_NAME = "RealName"
    private const val PAYOUT_ACCOUNT = "PayoutAccount"
    private const val SEARCH = "Search"
    private const val DEVICE_HASH_FIELD = "device_hash"

    /** 42901 没有可用 Retry-After 时的禁用时长（error-codes：缺省 5 秒）。 */
    const val DEFAULT_RETRY_AFTER_MILLIS = 5_000L
    private const val MILLIS_PER_SECOND = 1_000L
    private const val SERVER_ERROR_MIN = 50_000
    private const val SERVER_ERROR_MAX = 59_999
    private const val BUSINESS_MIN = 30_000
    private const val BUSINESS_MAX = 39_999
    private const val AUTH_UNAVAILABLE = "auth_unavailable"
    private const val NO_ACCOUNT = "no_account"
    private const val MAX_SECONDS_DIGITS = 9
    private const val LINK_LANDING_INVALID = "link_landing.invalid"

    /** 30144 只能显示空态的操作：链接落地页取卡（getLink）与分享页取卡（getSharePage）。 */
    private val LINK_PAGE_OPERATIONS = setOf("getLink", "getSharePage")

    /** 20004 后回到登录页的第三方登录接口。 */
    private val THIRD_PARTY_LOGIN_OPERATIONS = setOf("loginByWechat", "loginByApple", "loginByHuawei")

    /** 平台授权类前置码（AuthSheet，新键）。 */
    private val AUTHORIZE_CODES = setOf(30101, 30102, 30111)

    /** 只引导补办、补办后回到发起页由用户重新提交的码（不重放，目标由导航栈决定）。 */
    private val GUIDE_CODES = mapOf(30304 to REAL_NAME, 30305 to PAYOUT_ACCOUNT, 30307 to PAYOUT_ACCOUNT)

    /** 跳转类码（不重放）。 */
    private val NAVIGATE_CODES =
        mapOf(
            10006 to "RiskNotice",
            10007 to "DeleteAccount",
            30143 to SEARCH,
            30205 to "FindOrder",
            30308 to PAYOUT_ACCOUNT,
            30502 to SEARCH,
            50302 to SEARCH,
        )

    /** 由发起页按自己的界面处理的码（文案仍取字典）。 */
    private val PAGE_ACTION_CODES =
        setOf(
            20902,
            30103,
            30104,
            30121,
            30132,
            30141,
            30151,
            30153,
            30302,
            30309,
            30408,
            30411,
            30413,
            30416,
            30501,
            30504,
            30505,
            30506,
            30602,
            30701,
            30802,
            30803,
            30804,
            44001,
            44003,
            50303,
            50304,
            50305,
        )

    /** 按钮置灰（不限时，页面按状态恢复）。 */
    private val DISABLE_CODES = setOf(30206, 30306)

    /** 一次判定的上下文：码与按 code / reason 解析出的文案键。 */
    private class Context(
        val code: Int,
        val textKey: String?,
    ) {
        fun effect(
            kind: ErrorEffectKind,
            replay: ReplayMode = ReplayMode.NONE,
            pending: Boolean = false,
            route: String? = null,
            key: String? = textKey,
        ) = ErrorEffect(code = code, kind = kind, replay = replay, registerPending = pending, route = route, textKey = key)
    }

    /**
     * @param hasText 文案字典里有没有这个键（缺省查包内默认字典 DefaultTexts；服务端下发的字典接入后由调用方注入）。
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod") // 按 03 §4.2 与 error-codes.yaml 逐码判定，拆开反而难对照
    fun map(
        input: ErrorActionInput,
        hasText: (String) -> Boolean = { DefaultTexts.text(it) != null },
    ): ErrorEffect {
        val code = input.code
        val info = ErrorCodes.info(code)
        val stepUpOperation = input.operation.stepUp != null
        val reason = input.data.text("reason")
        val context = Context(code, resolveTextKey(code, reason, hasText))
        val serverError = code in SERVER_ERROR_MIN..SERVER_ERROR_MAX
        if (info == null || info.isDeprecated) {
            // 表外码（未分配）与废弃码（按表外处理）：5xxxx 通用错误态，其余用服务端 msg（BR-TEXT-14）
            return if (serverError) {
                context.effect(ErrorEffectKind.GENERIC_ERROR, key = null).copy(includeTrace6 = true)
            } else {
                context.effect(ErrorEffectKind.FALLBACK_MESSAGE, key = null)
            }
        }
        return when (code) {
            10001 -> {
                context.prerequisite(stepUpOperation, ErrorEffectKind.LOGIN, LOGIN)
            }

            10004 -> {
                context
                    .prerequisite(
                        stepUpOperation,
                        ErrorEffectKind.CONSENT,
                        null,
                    ).copy(consentType = input.data.text("consent_type"))
            }

            10005 -> {
                context.prerequisite(stepUpOperation, ErrorEffectKind.BIND_PHONE, BIND_PHONE)
            }

            10002 -> {
                context.automaticReplay(input.isReplay, ErrorEffectKind.REFRESH_REPLAY)
            }

            10402 -> {
                context.automaticReplay(input.isReplay, ErrorEffectKind.REGISTER_DEVICE_REPLAY)
            }

            10003 -> {
                context.effect(ErrorEffectKind.STEP_UP, replay = ReplayMode.SAME_KEY)
            }

            10007 -> {
                context.deletionInProgress(input.data.text("stage"))
            }

            10404 -> {
                context.effect(ErrorEffectKind.CLEAR_SESSION_LOGIN, route = LOGIN)
            }

            10405 -> {
                context.forceUpdate(input, reason)
            }

            42901 -> {
                context.effect(ErrorEffectKind.DISABLE_BUTTON).copy(disableMillis = retryAfterMillis(input.headers))
            }

            30144 -> {
                // 卡片自动重新转链并替换，仍须用户再点（文案 error.30144）；LinkLanding 没有卡片数据：空态、不自动重试，
                // 文案 link_landing.invalid（lessons 21、34）。getLink / getSharePage 取不到卡片时一律空态，不看 fromProductCard（编排者裁定）
                val landing = !input.fromProductCard || input.operation.operationId in LINK_PAGE_OPERATIONS
                if (landing) {
                    context.effect(ErrorEffectKind.PAGE_ACTION, key = LINK_LANDING_INVALID.takeIf(hasText) ?: context.textKey)
                } else {
                    context.effect(ErrorEffectKind.REFETCH_PRODUCT)
                }
            }

            20001 -> {
                val fields = input.data.textList("fields")
                if (fields?.contains(DEVICE_HASH_FIELD) == true) {
                    context.effect(ErrorEffectKind.REREAD_DEVICE_HASH, key = null).copy(fields = fields)
                } else {
                    context.effect(ErrorEffectKind.FIELD_ERROR).copy(fields = fields)
                }
            }

            20004 -> {
                // 第三方登录回登录页；二次验证回到二次验证面板（route 为 null）。都由用户重新发起授权，不自动重放
                val login = input.operation.operationId in THIRD_PARTY_LOGIN_OPERATIONS
                context.effect(ErrorEffectKind.REAUTHORIZE, route = if (login) LOGIN else null)
            }

            40901 -> {
                context.effect(ErrorEffectKind.KEEP_LOADING)
            }

            44001 -> {
                context.effect(ErrorEffectKind.PAGE_ACTION, key = riskTextKey(input.data, hasText) ?: context.textKey)
            }

            50301 -> {
                context.platformUnavailable(reason, hasText)
            }

            in AUTHORIZE_CODES -> {
                context.authorize(stepUpOperation, reason, promptOnlyReason(code, reason))
            }

            in GUIDE_CODES -> {
                context.effect(ErrorEffectKind.GUIDE_ONLY, route = GUIDE_CODES.getValue(code))
            }

            in NAVIGATE_CODES -> {
                context.effect(ErrorEffectKind.NAVIGATE, route = NAVIGATE_CODES.getValue(code))
            }

            in PAGE_ACTION_CODES -> {
                context.effect(ErrorEffectKind.PAGE_ACTION)
            }

            in DISABLE_CODES -> {
                context.effect(ErrorEffectKind.DISABLE_BUTTON)
            }

            else -> {
                if (serverError) {
                    context
                        .effect(
                            ErrorEffectKind.GENERIC_ERROR,
                        ).copy(includeTrace6 = true)
                } else if (context.textKey == null) {
                    // CT-LOCK-01 起：表内但用户侧字典没有文案键的码与表外码同一回落：用服务端 msg，不写专门分流（与鸿蒙同口径）。
                    // CT-LOCK-02 由 1xxxx 扩到所有码段（三端同口径）：a7c9f1fc 新增只出现在 /admin/v1 的 30154 / 30417 / 44004。
                    // 页面自处理码（PAGE_ACTION_CODES，如 30505 / 44003）与置灰码在上面的分支先处理，不受影响；5xxxx 仍是通用错误态。
                    context.effect(ErrorEffectKind.FALLBACK_MESSAGE, key = null)
                } else {
                    context.effect(ErrorEffectKind.TOAST)
                }
            }
        }
    }

    /**
     * 生成错误码表按 [reason] 规定的重试类别（retry_kind_by_reason）存在且不是 after_action：这个 reason 不能靠补办前置动作后重试，
     * 授权类码只提示（30102 / 30111 的 sharer_auth_invalid=never；30101 / 30102 的 auth_unavailable=later 原本就只提示）。
     * 没有 reason 或表里没有这个 reason 时为 false（走码本身的动作）。
     */
    fun promptOnlyReason(
        code: Int,
        reason: String?,
    ): Boolean {
        val kind = reason?.let { ErrorCodes.info(code)?.retryKindByReason?.get(it) } ?: return false
        return kind != RetryKind.AFTER_ACTION
    }

    /** 文案键：error.<code>.<reason> → error.<code> → null（BR-TEXT-14）。 */
    fun resolveTextKey(
        code: Int,
        reason: String?,
        hasText: (String) -> Boolean,
    ): String? {
        val base = "error.$code"
        if (reason != null && hasText("$base.$reason")) return "$base.$reason"
        return base.takeIf(hasText)
    }

    /** 3xxxx 业务码：未决键的业务结果（BR-ID-10）。 */
    fun isBusinessCode(code: Int): Boolean = code in BUSINESS_MIN..BUSINESS_MAX

    /** 42901：Retry-After 的非负整数秒；缺省或不能解析时 5 秒。TODO(N-19): HTTP-date、小数、超大值的解析与封顶未规定（rule-gaps）。 */
    fun retryAfterMillis(headers: Map<String, String>): Long {
        val raw =
            headers.entries
                .firstOrNull { it.key.equals(HeaderNames.RETRY_AFTER, ignoreCase = true) }
                ?.value
                ?.trim()
        val seconds = raw?.takeIf { it.isNotEmpty() && it.length <= MAX_SECONDS_DIGITS && it.all(Char::isDigit) }?.toLongOrNull()
        return seconds?.times(MILLIS_PER_SECOND) ?: DEFAULT_RETRY_AFTER_MILLIS
    }

    /** 1xxxx 前置：普通操作沿用原键并登记 pending；四个 step-up 操作只引导。 */
    private fun Context.prerequisite(
        stepUpOperation: Boolean,
        kind: ErrorEffectKind,
        route: String?,
    ): ErrorEffect =
        if (stepUpOperation) {
            effect(ErrorEffectKind.GUIDE_ONLY, route = route)
        } else {
            effect(kind, replay = ReplayMode.SAME_KEY, pending = true, route = route)
        }

    /** 10002、10402：不需要用户操作，沿用原键重放 1 次；重放后仍是同一个码走通用错误态。 */
    private fun Context.automaticReplay(
        isReplay: Boolean,
        kind: ErrorEffectKind,
    ): ErrorEffect =
        if (isReplay) {
            effect(ErrorEffectKind.GENERIC_ERROR).copy(includeTrace6 = true)
        } else {
            effect(kind, replay = ReplayMode.SAME_KEY, key = null)
        }

    /** 10007：冷静期跳注销进度页；processing 仅提示。 */
    private fun Context.deletionInProgress(stage: String?): ErrorEffect =
        if (stage == "processing") effect(ErrorEffectKind.TOAST) else effect(ErrorEffectKind.NAVIGATE, route = "DeleteAccount")

    /**
     * 30101 / 30102 / 30111：AuthSheet 后用新键；auth_unavailable 只提示；step-up 操作只引导。
     * [promptOnly]：生成表按 data.reason 给的重试类别不是 after_action（如 sharer_auth_invalid=never，CT-LOCK-01）→ 只提示，
     * 不弹授权、不登记待办、不重放（契约数据驱动，与鸿蒙同口径）。
     */
    private fun Context.authorize(
        stepUpOperation: Boolean,
        reason: String?,
        promptOnly: Boolean,
    ): ErrorEffect =
        when {
            reason == AUTH_UNAVAILABLE || promptOnly -> effect(ErrorEffectKind.TOAST)
            stepUpOperation -> effect(ErrorEffectKind.GUIDE_ONLY, route = AUTH_SHEET)
            else -> effect(ErrorEffectKind.AUTHORIZE, replay = ReplayMode.NEW_KEY, pending = true, route = AUTH_SHEET)
        }

    /** 10405：进强更；升级前留下的受限令牌只刷新（BR-ID-01 细则）；no_account 提示先更新。 */
    private fun Context.forceUpdate(
        input: ErrorActionInput,
        reason: String?,
    ): ErrorEffect {
        val minimum = input.data.text("min_supported_version")
        val restricted = input.state.sessionScope == SessionScopeRaw.DELETION_ONLY
        val supported = minimum == null || SemVer.compare(input.state.appVersion, minimum)?.let { it >= 0 } == true
        return if (reason != NO_ACCOUNT && restricted && supported) {
            effect(ErrorEffectKind.REFRESH_ONLY, key = null)
        } else {
            effect(ErrorEffectKind.FORCE_UPDATE, route = FORCE_UPDATE)
        }
    }

    /** 50301：maintenance（或无 reason）禁用并 Toast；not_launched 禁用不 Toast；不认识的 reason 按 maintenance、文案回落。 */
    private fun Context.platformUnavailable(
        reason: String?,
        hasText: (String) -> Boolean,
    ): ErrorEffect =
        if (reason == "not_launched") {
            effect(ErrorEffectKind.DISABLE_WITHOUT_TOAST)
        } else {
            effect(ErrorEffectKind.DISABLE_WITH_TOAST, key = resolveTextKey(code, reason ?: "maintenance", hasText))
        }

    /** 44001：文案取 risk_msg.<risk_msg_code>，未命中回落 error.44001。 */
    private fun riskTextKey(
        data: JsonObject,
        hasText: (String) -> Boolean,
    ): String? = data.text("risk_msg_code")?.let { "risk_msg.$it" }?.takeIf(hasText)

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.textList(key: String): List<String>? =
        (get(key) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content }
}

/** 语义化版本比较（逐段整数，x.y.z）。非法版本返回 null。 */
object SemVer {
    private val PATTERN = Regex("^\\d+(\\.\\d+){2}$")

    fun compare(
        a: String,
        b: String,
    ): Int? {
        if (!PATTERN.matches(a) || !PATTERN.matches(b)) return null
        val left = a.split('.').map { it.toBigInteger() }
        val right = b.split('.').map { it.toBigInteger() }
        return left.zip(right).map { (x, y) -> x.compareTo(y) }.firstOrNull { it != 0 } ?: 0
    }
}
