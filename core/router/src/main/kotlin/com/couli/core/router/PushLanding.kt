package com.couli.core.router

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/** 推送回查的结果（GET /v1/messages/{message_id}，04 §6.4；真正的请求在 APP-03 接入）。 */
sealed interface PushLookupResult {
    /** 回查成功；[route] 为空表示没有落点。 */
    data class Found(
        val route: String?,
        val params: JsonObject,
    ) : PushLookupResult

    /** 30701：消息不存在或不属于当前账号。 */
    data object NotFound : PushLookupResult

    /** 其他失败（网络、5xxxx 等）。 */
    data object Failed : PushLookupResult
}

/** 推送点击的处理结论（交给 [Router]）。 */
sealed interface PushLandingResult {
    /** 未登录：先进登录页，登录成功后打开 Messages；不登记原落点，也不拿这条消息编号回查新账号。 */
    data object LoginThenMessages : PushLandingResult

    /** 以 push 入口打开 [request]（回查到的落点，或兜底的 Messages）。 */
    data class Open(
        val request: RouteRequest,
    ) : PushLandingResult
}

/**
 * 推送点击的落点（BR-ID-10 细则「推送点击的落点」，03 §4.4「推送点击」）：载荷只带消息编号。
 * - 未登录（游客；受限会话也不发回查）→ [PushLandingResult.LoginThenMessages]；
 * - 已登录 → 用注入的 [lookup] 回查；失败、超过 [timeoutMillis]、30701、route 为空 → 打开 Messages；
 *   超时在 [timeoutMillis] 到点即兜底，不等不响应取消的回查结束；迟到的结果丢弃；
 * - 回查到的 route 一律以 push 入口交给守卫（未知路由、since 过高由守卫给升级提示）。
 * 回查前的闸门（环境、隐私、强更）与会话代次由 [Router.openPush] 保证，不直接调用本类。
 *
 * TODO(N-14): 回查失败的其余兜底细节（重试、提示）未规定，rule-gaps G12。
 */
class PushLanding(
    private val lookup: suspend (messageId: String) -> PushLookupResult,
    private val timeoutMillis: Long = LOOKUP_TIMEOUT_MILLIS,
) {
    suspend fun resolve(
        messageId: String,
        session: SessionState,
    ): PushLandingResult {
        if (session != SessionState.LOGGED_IN && session != SessionState.PHONE_BOUND) return PushLandingResult.LoginThenMessages
        val found = if (messageId.isBlank()) null else lookupOrNull(messageId)
        val route = found?.route?.takeIf { it.isNotBlank() }
        val request =
            if (route == null) {
                messagesRequest()
            } else {
                RouteRequest(route = route, params = found.params, source = RouteSource.PUSH)
            }
        return PushLandingResult.Open(request)
    }

    /**
     * 回查；抛出的任何异常（调用方被取消除外）与超时都按回查失败处理（落到 Messages）。
     * 回查跑在不挂在调用方之下的作业里，只「等它的结果」：到 [timeoutMillis] 立即返回 null 并取消它，
     * 即使回查不响应取消也不再等；之后回来的结果没人接收（丢弃）。
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun lookupOrNull(messageId: String): PushLookupResult.Found? {
        val detached = CoroutineScope(currentCoroutineContext().minusKey(Job) + SupervisorJob())
        val pending = detached.async { lookup(messageId) }
        return try {
            withTimeoutOrNull(timeoutMillis) { pending.await() } as? PushLookupResult.Found
        } catch (cancelled: CancellationException) {
            // 调用方被取消：照常抛出；回查自己以取消结束：按失败处理
            if (!currentCoroutineContext().isActive) throw cancelled
            null
        } catch (failure: Exception) {
            null
        } finally {
            pending.cancel()
        }
    }

    companion object {
        /** 推送回查等待上限 5 秒（03 §4.4，客户端常量）。 */
        const val LOOKUP_TIMEOUT_MILLIS = 5_000L

        fun messagesRequest(): RouteRequest = RouteRequest(RouteNames.MESSAGES, JsonObject(emptyMap()), RouteSource.PUSH)
    }
}
