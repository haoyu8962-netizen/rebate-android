package com.couli.core.router

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Router 登记 pending_action 的判定与清洗（BR-ID-10；R1-02）：
 * - 需要前置（登录、绑手机、实名）或普通请求要补同意时登记；Agent 卡片遇隐私阻断（agent_consent_no_replay）不登记：
 *   同意后不补打开，由用户再点；
 * - 推送兜底到 Messages 时登记 Messages（不是原落点）；
 * - 敏感动作（[Router.noPendingRoutes]）不登记；
 * - 只存清洗后的参数：路由必须已知，参数必须通过 routes.json 的 params schema，ExternalPage / WebPage 的目标必须在白名单；
 *   任一不满足不登记（前置完成后不自动打开）。令牌、验证码由 [PendingActionStore.save] 拒绝。
 */
internal object PendingRegistration {
    const val KEY_ROUTE = "route"
    const val KEY_PARAMS = "params"

    val PREREQUISITE_KINDS: Set<GuardKind> = setOf(GuardKind.REQUIRE_LOGIN, GuardKind.REQUIRE_BIND_PHONE, GuardKind.REQUIRE_REALNAME)

    /** 要登记时返回存进 pending_action 的 `{route, params}`；不登记返回 null。 */
    fun payload(
        request: RouteRequest,
        decision: GuardDecision,
        state: GuardState,
    ): JsonObject? {
        val target = if (decision.fallbackToMessages) PushLanding.messagesRequest() else request
        val source = request.sourceKind
        val registrable =
            registers(decision) &&
                source != null &&
                source != RouteSource.PENDING_ACTION &&
                target.route !in Router.noPendingRoutes
        val spec = if (registrable) RouteCatalog.spec(target.route, includeDebugRoutes = state.isDebugBuild) else null
        val params = target.params
        val clean = spec != null && RouteParams.isValid(spec, params) && TargetAllowlist.allows(spec.name, params, state, request.source)
        return when {
            spec == null || !clean -> null
            params.isEmpty() -> JsonObject(mapOf(KEY_ROUTE to JsonPrimitive(spec.name)))
            else -> JsonObject(mapOf(KEY_ROUTE to JsonPrimitive(spec.name), KEY_PARAMS to params))
        }
    }

    private fun registers(decision: GuardDecision): Boolean =
        decision.kind in PREREQUISITE_KINDS ||
            (decision.kind == GuardKind.REQUIRE_CONSENT && decision.reason == GuardReason.CONSENT_REQUIRED)
}
