package com.couli.core.router

import com.couli.generated.RouteEntry
import com.couli.generated.RouteSpec
import kotlinx.serialization.json.JsonObject

/**
 * 统一入口守卫（03 §4.4「统一入口守卫」「入口限制」、§4.1 强更；BR-ID-10、BR-ID-02、BR-ID-11、BR-ID-01）。纯函数，不碰界面。
 *
 * 判定顺序（每一步要么给出结论，要么交给下一步）：
 * 1. 来源合法；环境闸门（EnvironmentGate）未 settled → 等待。
 * 2. 隐私闸门：未有结论 → 等待（Agent 卡片 → 先补同意、同意后不补打开）；基本模式只放行八个页面，
 *    用户在 App 内主动点的其余页面先补同意，外部与自动的丢弃。
 * 3. 强更：低于最低版本 → ForceUpdate（只放行从强更页进入的次要入口）；未知 → 外部与自动的请求只开只读页面清单，
 *    推送改开 Messages；用户在原生页面上自己的操作不拦。
 * 4. Agent 卡片：只开 agent_guide=true 的路由，且不带参数。
 * 5. 查路由：未知（含 Release 里的 debug_only 名）→ 升级提示；since 高于本端版本 → 升级提示。
 * 6. 入口：没声明该入口 → 不打开（推送改开 Messages）。
 * 7. 参数：资金与实名四页丢弃全部参数；其余按 params schema 校验。
 * 8. 登录要求：游客 → Login；未绑手机 → BindPhone。
 */
object EntryGuard {
    /** 基本模式可达的路由（03 §4.4「无需登录的页面」：首页、搜索、商品详情与五个无需登录的页面）。 */
    val basicModeRoutes: Set<String> =
        setOf("Home", "Search", "ProductDetail", "Settings", "PrivacyCenter", "Agreement", "About", "Help")

    /**
     * 首启隐私尚未有结论时，用户在弹窗里主动点开的页面（只读）：协议文本（BR-ID-11「同意前只允许请求协议文本与 /v1/config」）。
     * 只放行 App 内来源；深链、推送、pending_action 仍等待。TODO(N-14): 向量没有这一项（APP-02b 补），三端对齐后补向量。
     */
    val undecidedReadableRoutes: Set<String> = setOf("Agreement")

    /** 强更检查结果未知时外部请求与自动动作只能打开的只读页面（BR-ID-10 细则「路由入口矩阵」）。 */
    val updateUnknownReadonlyRoutes: Set<String> =
        setOf(
            "Home",
            "Search",
            "ProductDetail",
            "LinkLanding",
            "OrderList",
            "OrderDetail",
            "Wallet",
            "WithdrawRecords",
            "Ledger",
            "Earnings",
            "Messages",
            "Rules",
            "Help",
            "Notice",
            "Agreement",
            "About",
        )

    /** 强更状态下只放行「从强更页进入」的这几个页面（03 §4.1）。 */
    val forceUpdateSecondaryRoutes: Set<String> = setOf("PrivacyCenter", "Agreement", "Login", "DeleteAccount")

    fun decide(
        request: RouteRequest,
        state: GuardState,
    ): GuardDecision {
        val source = request.sourceKind ?: return reject(GuardReason.SOURCE_INVALID)
        val context = GuardContext(request, state, source)
        return environmentGate(context)
            ?: privacyGate(context)
            ?: updateGate(context)
            ?: agentCardGate(context)
            ?: RouteResolution.resolve(context)
    }

    /**
     * 只看前三道闸门（环境、隐私、强更）：推送回查之前用它判断「现在能不能为这条推送发请求」（R1-03）。
     * 返回 null = 三道闸门都放行（之后才回查、再走完整的 [decide]）；否则返回阻断的结论（等待、丢弃、ForceUpdate）。
     */
    fun preconditions(
        request: RouteRequest,
        state: GuardState,
    ): GuardDecision? {
        val source = request.sourceKind ?: return reject(GuardReason.SOURCE_INVALID)
        val context = GuardContext(request, state, source)
        return environmentGate(context) ?: privacyGate(context) ?: updateGate(context)
    }

    private fun environmentGate(context: GuardContext): GuardDecision? =
        if (context.state.environmentSettled) null else decision(GuardKind.DEFER, null, GuardReason.ENVIRONMENT_UNSETTLED)

    private fun privacyGate(context: GuardContext): GuardDecision? =
        when (context.privacy) {
            PrivacyState.CONSENTED -> {
                null
            }

            PrivacyState.NOT_DECIDED -> {
                if (context.source == RouteSource.IN_APP && context.route in undecidedReadableRoutes) {
                    // 首启弹窗、二次说明里的《隐私政策》《用户协议》：同意前允许请求协议文本（BR-ID-11），只读打开
                    null
                } else if (context.source == RouteSource.AGENT_CARD) {
                    // Agent 卡片：先走首启弹窗，同意后不自动补打开，由用户再点（BR-ID-10 Agent 卡片 ②）。
                    decision(GuardKind.REQUIRE_CONSENT, null, GuardReason.AGENT_CONSENT_NO_REPLAY)
                } else {
                    decision(GuardKind.DEFER, null, GuardReason.PRIVACY_UNDECIDED)
                }
            }

            PrivacyState.BASIC_MODE -> {
                when {
                    context.route in basicModeRoutes -> null
                    context.source == RouteSource.IN_APP -> decision(GuardKind.REQUIRE_CONSENT, null, GuardReason.CONSENT_REQUIRED)
                    else -> reject(GuardReason.BASIC_MODE_BLOCKED)
                }
            }
        }

    private fun updateGate(context: GuardContext): GuardDecision? =
        when (context.update) {
            UpdateState.OK, UpdateState.BELOW_RECOMMENDED -> {
                null
            }

            UpdateState.BELOW_MIN -> {
                if (isForceUpdateSecondaryEntry(context)) {
                    null
                } else {
                    decision(GuardKind.FORCE_UPDATE, RouteNames.FORCE_UPDATE, GuardReason.FORCE_UPDATE_REQUIRED)
                }
            }

            UpdateState.UNKNOWN -> {
                when {
                    !context.source.isExternalOrAutomatic -> null
                    context.route in updateUnknownReadonlyRoutes -> null
                    context.source == RouteSource.PUSH -> messagesFallback(context, GuardReason.UPDATE_UNKNOWN_PUSH_FALLBACK)
                    else -> reject(GuardReason.UPDATE_UNKNOWN_BLOCKED)
                }
            }
        }

    private fun isForceUpdateSecondaryEntry(context: GuardContext): Boolean =
        context.state.fromForceUpdatePage &&
            context.source == RouteSource.IN_APP &&
            context.route in forceUpdateSecondaryRoutes

    private fun agentCardGate(context: GuardContext): GuardDecision? {
        if (context.source != RouteSource.AGENT_CARD) return null
        val spec = context.spec()
        return when {
            spec == null || !spec.agentGuide -> reject(GuardReason.AGENT_ROUTE_NOT_ALLOWED)
            context.request.params.isNotEmpty() -> reject(GuardReason.AGENT_PARAMS_NOT_ALLOWED)
            else -> null
        }
    }
}
