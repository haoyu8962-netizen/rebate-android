package com.couli.core.router

import kotlinx.serialization.json.JsonObject

/**
 * 打开请求的来源（03 §4.4「入口限制」）。入口类别看 route 的来源，不看请求里的任何标记：
 * - [IN_APP]、[BRIDGE]（JSBridge nav.open）、[PENDING_ACTION]（恢复）、[AGENT_CARD]（Agent 卡片）都按 in_app 入口校验；
 * - [PUSH] 只给按消息编号回查得到的 route（[PushLanding]）；
 * - [DEEPLINK] 是从外部直接拿到的 route（深链、自定义 scheme、启动参数里的任何 route）。
 */
enum class RouteSource(
    val raw: String,
) {
    IN_APP("in_app"),
    PUSH("push"),
    DEEPLINK("deeplink"),
    BRIDGE("bridge"),
    PENDING_ACTION("pending_action"),
    AGENT_CARD("agent_card"),
    ;

    /** 不是用户在原生页面上直接点的：要过统一入口守卫的外部请求与自动执行的动作（03 §4.4）。 */
    val isExternalOrAutomatic: Boolean get() = this != IN_APP

    companion object {
        fun fromRaw(raw: String): RouteSource? = entries.firstOrNull { it.raw == raw }
    }
}

/** 一次打开请求。[source] 取 [RouteSource.raw]；不认识的来源一律拒绝（[EntryGuard]）。 */
data class RouteRequest(
    val route: String,
    val params: JsonObject,
    val source: String,
) {
    constructor(route: String, params: JsonObject, source: RouteSource) : this(route, params, source.raw)

    val sourceKind: RouteSource? get() = RouteSource.fromRaw(source)
}

/** 隐私闸门（BR-ID-11）。not_decided = 首启弹窗尚未有结论。 */
enum class PrivacyState(
    val raw: String,
) {
    CONSENTED("consented"),
    BASIC_MODE("basic_mode"),
    NOT_DECIDED("not_decided"),
    ;

    companion object {
        /** 不认识的取值按「尚未有结论」处理（最保守：什么都不放行）。 */
        fun fromRaw(raw: String): PrivacyState = entries.firstOrNull { it.raw == raw } ?: NOT_DECIDED
    }
}

/**
 * 强更检查结果（03 §4.1）。[CHECKING]（检查进行中，APP-02c F-ALL-03）：外部来源与 pending 恢复返回 defer（update_checking），
 * App 内用户操作不等待；启动意图由 [StartupIntentQueue] 排队，等满 5 秒仍在检查时按 unknown 交出。
 */
enum class UpdateState(
    val raw: String,
) {
    OK("ok"),
    UNKNOWN("unknown"),
    BELOW_MIN("below_min"),
    BELOW_RECOMMENDED("below_recommended"),
    CHECKING("checking"),
    ;

    companion object {
        /** 不认识的取值按 unknown 处理。 */
        fun fromRaw(raw: String): UpdateState = entries.firstOrNull { it.raw == raw } ?: UNKNOWN
    }
}

/** 会话身份（BR-ID-02）。restricted = 过低版本得到的受限会话（BR-ID-01 细则「受限会话」）。 */
enum class SessionState(
    val raw: String,
) {
    GUEST("guest"),
    LOGGED_IN("logged_in"),
    PHONE_BOUND("phone_bound"),
    RESTRICTED("restricted"),
    ;

    companion object {
        /** 不认识的取值按游客处理。 */
        fun fromRaw(raw: String): SessionState = entries.firstOrNull { it.raw == raw } ?: GUEST
    }
}

/** 守卫看的状态（任务书 §2）。字符串取值见 [PrivacyState]、[UpdateState]、[SessionState] 的 raw。 */
data class GuardState(
    val privacy: String,
    val update: String,
    val session: String,
    val environmentSettled: Boolean,
    /** 本端版本号，与 routes.json since.android 比较（[AppVersion]）。 */
    val appVersion: String,
    /** debug / staging = true：只有这时 debug_only 路由才存在。 */
    val isDebugBuild: Boolean,
    /** 本次打开是否由强更页的次要入口发起（只放行 03 §4.1 列出的几个页面）。 */
    val fromForceUpdatePage: Boolean,
    /** 已实名（auth=realname 的前置；已绑手机不等于已实名）。缺省 false。 */
    val realnameVerified: Boolean = false,
    /** ExternalPage 的目标主机白名单（/v1/config.external_hosts，完整主机名匹配）；缺省空 = 全拒。 */
    val externalHosts: List<String> = emptyList(),
    /** WebPage 的目标来源白名单（bridge_origins，`https://host[:port]`）；缺省空 = 全拒。 */
    val bridgeOrigins: List<String> = emptyList(),
) {
    constructor(
        privacy: PrivacyState,
        update: UpdateState,
        session: SessionState,
        environmentSettled: Boolean,
        appVersion: String,
        isDebugBuild: Boolean,
        fromForceUpdatePage: Boolean = false,
        realnameVerified: Boolean = false,
        externalHosts: List<String> = emptyList(),
        bridgeOrigins: List<String> = emptyList(),
    ) : this(
        privacy.raw,
        update.raw,
        session.raw,
        environmentSettled,
        appVersion,
        isDebugBuild,
        fromForceUpdatePage,
        realnameVerified,
        externalHosts,
        bridgeOrigins,
    )

    val privacyState: PrivacyState get() = PrivacyState.fromRaw(privacy)
    val updateState: UpdateState get() = UpdateState.fromRaw(update)
    val sessionState: SessionState get() = SessionState.fromRaw(session)
}

/** 守卫结论种类。 */
enum class GuardKind(
    val raw: String,
) {
    OPEN("open"),
    OPEN_READONLY("open_readonly"),
    REJECT("reject"),
    UPGRADE("upgrade"),
    REQUIRE_LOGIN("require_login"),
    REQUIRE_BIND_PHONE("require_bind_phone"),
    REQUIRE_REALNAME("require_realname"),
    REQUIRE_CONSENT("require_consent"),
    FORCE_UPDATE("force_update"),
    DEFER("defer"),
}

/**
 * 守卫结论。
 * @property route 实际要打开的路由（被拒、升级提示、等待时为 null；升级提示页没有契约路由名，TODO(N-14)）。
 * @property reason 机读原因码，取值与 specs/router-vectors.json 的 reasons 一致（[GuardReason]）。
 * @property params 实际带给 [route] 的参数：资金与实名四页丢弃全部参数；Login / BindPhone 等前置页不带参数。
 * @property paramsDropped 被丢弃的参数个数（埋点 route_params_dropped 用，只记个数不记内容）。
 * @property fallbackToMessages 推送落点已兜底换成 Messages（本结论是 Messages 的判定；登记 pending_action 时登记 Messages）。
 */
data class GuardDecision(
    val kind: GuardKind,
    val route: String?,
    val reason: String,
    val params: JsonObject = JsonObject(emptyMap()),
    val paramsDropped: Int = 0,
    val fallbackToMessages: Boolean = false,
) {
    val kindRaw: String get() = kind.raw

    val opensPage: Boolean get() = kind == GuardKind.OPEN || kind == GuardKind.OPEN_READONLY
}

/** 机读原因码（与 specs/router-vectors.json 的 reasons 同名）。 */
object GuardReason {
    const val ALLOWED = "allowed"
    const val ENTRY_NOT_ALLOWED = "entry_not_allowed"
    const val PUSH_ENTRY_FALLBACK = "push_entry_fallback"
    const val LOGIN_REQUIRED = "login_required"
    const val PHONE_REQUIRED = "phone_required"
    const val BASIC_READONLY = "basic_readonly"
    const val CONSENT_REQUIRED = "consent_required"
    const val BASIC_MODE_BLOCKED = "basic_mode_blocked"
    const val PRIVACY_UNDECIDED = "privacy_undecided"
    const val ENVIRONMENT_UNSETTLED = "environment_unsettled"
    const val UPDATE_UNKNOWN_READONLY = "update_unknown_readonly"

    /**
     * 强更检查进行中，外部来源（deeplink / bridge / agent_card / push）与 pending_action 恢复：等待（defer），最长 5 秒后
     * 按 unknown 处理（排队器）；App 内用户操作不等待。三端同名（followups F-ALL-03）。向量未定义（router-vectors G11）。
     */
    const val UPDATE_CHECKING = "update_checking"
    const val UPDATE_UNKNOWN_BLOCKED = "update_unknown_blocked"
    const val UPDATE_UNKNOWN_PUSH_FALLBACK = "update_unknown_push_fallback"
    const val FORCE_UPDATE_REQUIRED = "force_update_required"
    const val FORCE_UPDATE_EXCEPTION = "force_update_exception"
    const val UNKNOWN_ROUTE = "unknown_route"
    const val ROUTE_TOO_NEW = "route_too_new"
    const val AGENT_ROUTE_NOT_ALLOWED = "agent_route_not_allowed"
    const val AGENT_PARAMS_NOT_ALLOWED = "agent_params_not_allowed"
    const val AGENT_CONSENT_NO_REPLAY = "agent_consent_no_replay"
    const val PARAMS_DROPPED = "params_dropped"

    /** 已绑手机、未实名，目标需要实名（auth=realname）：先去 RealName。向量未定义（rule-gaps G07），编排者裁定。 */
    const val REALNAME_REQUIRED = "realname_required"

    /** ExternalPage / WebPage 的 url 不在目标白名单（external_hosts / bridge_origins）：不打开。编排者裁定。 */
    const val TARGET_NOT_ALLOWED = "target_not_allowed"

    /**
     * 参数不符合 routes.json 的 params schema：不打开。向量没有定义这一项（rule-gaps G04），
     * 是本端的保守做法。TODO(N-14): 规则补「一般参数错误」的处理后对齐三端。
     */
    const val PARAMS_INVALID = "params_invalid"

    /**
     * 首启隐私未有结论时，用户在弹窗里点开协议（[EntryGuard.undecidedReadableRoutes]）：只读打开。
     * 向量未定义（APP-02b 补），TODO(N-14) 三端对齐后补向量。
     */
    const val PRIVACY_UNDECIDED_READONLY = "privacy_undecided_readonly"

    /** 来源不是 [RouteSource] 之一：拒绝。TODO(N-14) 同上，向量未定义。 */
    const val SOURCE_INVALID = "source_invalid"
}
