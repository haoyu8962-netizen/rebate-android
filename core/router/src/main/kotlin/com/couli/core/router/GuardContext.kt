package com.couli.core.router

import com.couli.generated.RouteSpec

/** 一次判定的上下文（[EntryGuard] 与 [RouteResolution] 共用）。 */
internal class GuardContext(
    val request: RouteRequest,
    val state: GuardState,
    val source: RouteSource,
) {
    val route: String get() = request.route
    val privacy: PrivacyState = state.privacyState
    val update: UpdateState = state.updateState
    val session: SessionState = state.sessionState

    fun spec(): RouteSpec? = RouteCatalog.spec(route, includeDebugRoutes = state.isDebugBuild)
}

/**
 * 推送落点兜底到 Messages（入口未声明 push，或强更未知时不在只读清单）：不直接构造打开结论，而是把「以 push 打开 Messages」
 * 重新交给完整的守卫（隐私、强更、入口、登录要求都按 Messages 判定）。能打开时原因码换成兜底原因 [reason]；
 * 不能打开（如回查期间已退出登录 → require_login）时照 Messages 的结论走，[GuardDecision.fallbackToMessages] 标记目标已换成 Messages。
 */
internal fun messagesFallback(
    context: GuardContext,
    reason: String,
): GuardDecision {
    val messages = EntryGuard.decide(PushLanding.messagesRequest(), context.state)
    val relabeled = if (messages.opensPage) messages.copy(reason = reason) else messages
    return relabeled.copy(fallbackToMessages = true)
}

internal fun reject(reason: String): GuardDecision = decision(GuardKind.REJECT, null, reason)

internal fun decision(
    kind: GuardKind,
    route: String?,
    reason: String,
): GuardDecision = GuardDecision(kind = kind, route = route, reason = reason)
