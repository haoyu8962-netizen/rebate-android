package com.couli.core.router

import com.couli.generated.RouteEntry
import com.couli.generated.RouteSpec
import kotlinx.serialization.json.JsonObject

/** [EntryGuard] 第 5～8 步：查路由、版本、入口、参数与登录要求，最后给出打开的结论。 */
internal object RouteResolution {
    fun resolve(context: GuardContext): GuardDecision {
        val spec = context.spec() ?: return decision(GuardKind.UPGRADE, null, GuardReason.UNKNOWN_ROUTE)
        return versionCheck(context, spec)
            ?: entryCheck(context, spec)
            ?: paramsAndAuth(context, spec)
    }

    @Suppress("ReturnCount") // 覆盖、since 缺失、版本逐项
    private fun versionCheck(
        context: GuardContext,
        spec: RouteSpec,
    ): GuardDecision? {
        // TODO(N-14): since=null（本端尚未上线，如 DeleteAccount、LinkLanding）没有规定各来源的结论；
        //  按未知路由处理（升级提示），强更页进 DeleteAccount 的冲突见 rule-gaps G02。
        // TODO(N-37): APP-13a G01——Debug / Staging 的路由可用性覆盖（[GuardState.availabilityOverrides]）按已上线放行；Release 恒为空
        if (spec.name in context.state.availabilityOverrides) return null
        val since = spec.sinceAndroid ?: return decision(GuardKind.UPGRADE, null, GuardReason.UNKNOWN_ROUTE)
        return if (AppVersion.atLeast(context.state.appVersion, since)) {
            null
        } else {
            decision(GuardKind.UPGRADE, null, GuardReason.ROUTE_TOO_NEW)
        }
    }

    private fun entryCheck(
        context: GuardContext,
        spec: RouteSpec,
    ): GuardDecision? {
        val entry =
            when (context.source) {
                RouteSource.PUSH -> RouteEntry.PUSH

                RouteSource.DEEPLINK -> RouteEntry.DEEPLINK

                // JSBridge nav.open、pending_action 恢复、Agent 卡片都属 App 内发起（BR-ID-10「入口类别看 route 的来源」）。
                // TODO(N-14): 由深链登记的 pending_action 恢复时是否保留原入口未规定（rule-gaps G01、G09），按 in_app 校验。
                RouteSource.IN_APP, RouteSource.BRIDGE, RouteSource.PENDING_ACTION, RouteSource.AGENT_CARD -> RouteEntry.IN_APP
            }
        return when {
            entry in spec.entries -> {
                null
            }

            context.source == RouteSource.PUSH && context.update == UpdateState.UNKNOWN -> {
                messagesFallback(context, GuardReason.UPDATE_UNKNOWN_PUSH_FALLBACK)
            }

            context.source == RouteSource.PUSH -> {
                messagesFallback(context, GuardReason.PUSH_ENTRY_FALLBACK)
            }

            else -> {
                reject(GuardReason.ENTRY_NOT_ALLOWED)
            }
        }
    }

    private fun paramsAndAuth(
        context: GuardContext,
        spec: RouteSpec,
    ): GuardDecision {
        // 资金与实名四页、DeleteAccount（APP-13a G21，静默丢弃、不记 route_params_dropped）：丢弃全部参数后照常打开
        val dropAll = spec.name in RouteParams.dropAllParamsRoutes || spec.name in RouteParams.silentDropRoutes
        val params = if (dropAll) JsonObject(emptyMap()) else context.request.params
        val dropped = if (dropAll) context.request.params.size else 0
        return when {
            // 所有来源都按 params schema 校验（编排者裁定；资金与实名四页先丢弃全部参数）
            !dropAll && !RouteParams.isValid(spec, context.request.params) -> reject(GuardReason.PARAMS_INVALID)

            !TargetAllowlist.allows(spec.name, params, context.state, context.request.source) -> reject(GuardReason.TARGET_NOT_ALLOWED)

            else -> authCheck(context, spec) ?: openDecision(context, spec.name, params, dropped)
        }
    }

    private fun authCheck(
        context: GuardContext,
        spec: RouteSpec,
    ): GuardDecision? {
        val session = context.session
        // 受限会话只在低于最低版本时有效（BR-ID-01 细则「受限会话」）；版本恢复后须先刷新，按需要登录处理。
        // TODO(N-14): 受限会话恢复支持后的具体结论未规定（rule-gaps G15）。
        val restrictedUsable = session == SessionState.RESTRICTED && context.update == UpdateState.BELOW_MIN
        val loggedIn = session == SessionState.LOGGED_IN || session == SessionState.PHONE_BOUND || restrictedUsable
        return when (spec.auth) {
            "none" -> null

            "login" -> if (loggedIn) null else requireLogin()

            "phone", "realname" -> phoneOrRealname(context, needsRealname = spec.auth == "realname")

            // 契约出现不认识的 auth 取值：按需要登录处理（保守）。
            else -> requireLogin()
        }
    }

    /**
     * phone / realname：游客先登录，未绑手机先绑手机，已绑手机未实名（realname）先实名。
     * TODO(N-14): 实名状态的来源未定（rule-gaps G07）；守卫按 realnameVerified 判断（已绑手机不等于已实名），页面与服务端仍各自核对。
     */
    private fun phoneOrRealname(
        context: GuardContext,
        needsRealname: Boolean,
    ): GuardDecision? =
        when (context.session) {
            SessionState.PHONE_BOUND -> {
                if (needsRealname && !context.state.realnameVerified) {
                    decision(GuardKind.REQUIRE_REALNAME, RouteNames.REAL_NAME, GuardReason.REALNAME_REQUIRED)
                } else {
                    null
                }
            }

            SessionState.LOGGED_IN -> {
                decision(GuardKind.REQUIRE_BIND_PHONE, RouteNames.BIND_PHONE, GuardReason.PHONE_REQUIRED)
            }

            SessionState.GUEST, SessionState.RESTRICTED -> {
                requireLogin()
            }
        }

    private fun requireLogin(): GuardDecision = decision(GuardKind.REQUIRE_LOGIN, RouteNames.LOGIN, GuardReason.LOGIN_REQUIRED)

    private fun openDecision(
        context: GuardContext,
        route: String,
        params: JsonObject,
        dropped: Int,
    ): GuardDecision {
        val (kind, reason) =
            when {
                context.privacy == PrivacyState.BASIC_MODE -> {
                    GuardKind.OPEN_READONLY to GuardReason.BASIC_READONLY
                }

                context.privacy == PrivacyState.NOT_DECIDED -> {
                    GuardKind.OPEN_READONLY to GuardReason.PRIVACY_UNDECIDED_READONLY
                }

                context.update == UpdateState.BELOW_MIN -> {
                    GuardKind.OPEN to GuardReason.FORCE_UPDATE_EXCEPTION
                }

                context.update == UpdateState.UNKNOWN && context.source.isExternalOrAutomatic -> {
                    GuardKind.OPEN_READONLY to GuardReason.UPDATE_UNKNOWN_READONLY
                }

                dropped > 0 -> {
                    GuardKind.OPEN to GuardReason.PARAMS_DROPPED
                }

                else -> {
                    GuardKind.OPEN to GuardReason.ALLOWED
                }
            }
        return GuardDecision(kind = kind, route = route, reason = reason, params = params, paramsDropped = dropped)
    }
}
