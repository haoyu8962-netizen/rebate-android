package com.couli.core.orders

import com.couli.core.network.ErrorActionMapper
import com.couli.core.network.ResultSignals
import com.couli.generated.DefaultTexts
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 错误 → 动作的名字（三端同名）。 */
object OrderErrorKind {
    /** 30701：详情显示空态（范围外、不属于本人、不存在都是它），不重试。 */
    const val EMPTY_30701 = "empty_30701"

    /** 42901：按 Retry-After（缺省或非法 5 秒）禁用刷新、翻页、筛选与搜索提交、卡片关闭。 */
    const val RETRY_AFTER = "retry_after"

    /** 5xxxx：错误态带 trace_id 末 6 位（未收录的回落 error.50001）；不伪装成空结果。 */
    const val ERROR_TRACE6 = "error_trace6"

    /** 10405：强制进入强更。 */
    const val FORCE_UPDATE = "force_update"

    /** 传输失败、没有可用缓存：网络错误态 +【重试】。 */
    const val NETWORK_ERROR = "network_error"

    /** 传输失败、有默认视图的缓存：显示缓存与「网络不可用，以下为 {time} 的数据」。 */
    const val SHOW_STALE = "show_stale"

    /** 翻页失败：保留已有结果，底部错误文案（带 trace6）+【重试】。 */
    const val PAGE_ERROR = "page_error"

    /** 其余错误：错误态（文案按 error.<code>，没有则通用）。 */
    const val ERROR = "error"
}

/** 一次错误的动作。 */
data class OrderErrorView(
    val actionRaw: String,
    val textKey: String?,
    val disableMs: Long? = null,
    val showTrace6: Boolean = false,
    val keepItems: Boolean = false,
    val traceId: String? = null,
)

/**
 * 订单请求的错误 → 动作（03 §4.2、TECH-20 / G10；三端向量 errorAction 组）。[target]：list_first_page / list_next_page / detail。
 * - 只有传输层失败（断网、超时、自动重试之后仍失败）才用缓存，而且只用于默认视图的第一页与详情；带筛选或搜索词的查询失败显示错误态；
 * - 5xxxx、42901、10405 按各自的码处理，不用缓存冒充；翻页失败一律保留已有结果。
 */
object OrderErrorAction {
    const val LIST_FIRST = "list_first_page"
    const val LIST_NEXT = "list_next_page"
    const val DETAIL = "detail"

    const val NOT_FOUND = 30701
    private const val FORCE_UPDATE = 10405
    private const val SERVER_MIN = 50_000
    private const val SERVER_MAX = 59_999
    private const val GENERIC_KEY = "error.50001"
    const val STALE_KEY = "net.stale_data"
    const val NETWORK_KEY = "order_list.network_error"

    /**
     * [forced]：网络层是否判定强更（[ResultSignals.forcesUpdate]）。10405 而没有判定强更（F-X-09：受限会话刷新、刷新失败）→ 统一错误文案
     * （error.50001 + trace6）+【重试】，不进强更；缺省 true 只给三端向量（向量只给错误码）。
     */
    @Suppress("LongParameterList", "CyclomaticComplexMethod", "ReturnCount") // 三端测试接线约定的入参；逐类判定
    fun of(
        target: String,
        code: Int?,
        transport: Boolean,
        disableMs: Long,
        traceId: String?,
        hasCache: Boolean,
        filtered: Boolean,
        forced: Boolean = true,
    ): OrderErrorView {
        val next = target == LIST_NEXT
        if (transport || code == null) {
            return when {
                next -> OrderErrorView(OrderErrorKind.PAGE_ERROR, NETWORK_KEY, keepItems = true)
                hasCache && !(target == LIST_FIRST && filtered) -> OrderErrorView(OrderErrorKind.SHOW_STALE, STALE_KEY)
                else -> OrderErrorView(OrderErrorKind.NETWORK_ERROR, NETWORK_KEY)
            }
        }
        return when {
            code == NOT_FOUND && target == DETAIL -> {
                OrderErrorView(OrderErrorKind.EMPTY_30701, "error.$NOT_FOUND")
            }

            code == ResultSignals.RATE_LIMITED -> {
                OrderErrorView(OrderErrorKind.RETRY_AFTER, "error.${ResultSignals.RATE_LIMITED}", disableMs = disableMs, keepItems = next)
            }

            code == FORCE_UPDATE && forced -> {
                OrderErrorView(OrderErrorKind.FORCE_UPDATE, "error.$FORCE_UPDATE")
            }

            code == FORCE_UPDATE -> {
                val kind = if (next) OrderErrorKind.PAGE_ERROR else OrderErrorKind.ERROR_TRACE6
                OrderErrorView(kind, GENERIC_KEY, showTrace6 = true, keepItems = next, traceId = traceId)
            }

            code in SERVER_MIN..SERVER_MAX -> {
                val kind = if (next) OrderErrorKind.PAGE_ERROR else OrderErrorKind.ERROR_TRACE6
                OrderErrorView(kind, serverKey(code), showTrace6 = true, keepItems = next, traceId = traceId)
            }

            else -> {
                val kind = if (next) OrderErrorKind.PAGE_ERROR else OrderErrorKind.ERROR
                OrderErrorView(
                    kind,
                    "error.$code".takeIf { DefaultTexts.text(it) != null } ?: GENERIC_KEY,
                    keepItems = next,
                    traceId = traceId,
                )
            }
        }
    }

    /** 未收录的 5xxxx（文案键缺失或为空）回落 error.50001（APP-07 第 9 条）。 */
    fun serverKey(code: Int): String = "error.$code".takeIf { !DefaultTexts.text(it).isNullOrEmpty() } ?: GENERIC_KEY

    /** 三端向量的 JSON 出口（headers 里的 Retry-After 按网络层同一个解析：非负整数秒，缺省或非法 5 秒）。 */
    @Suppress("LongParameterList") // 三端测试接线约定
    fun map(
        target: String,
        code: Int?,
        transport: Boolean,
        headers: Map<String, String>,
        traceId: String?,
        hasCache: Boolean,
        filtered: Boolean,
    ): JsonObject {
        val view = of(target, code, transport, ErrorActionMapper.retryAfterMillis(headers), traceId, hasCache, filtered)
        val out = linkedMapOf<String, JsonElement>("actionRaw" to JsonPrimitive(view.actionRaw), "textKey" to view.textKey.json())
        view.disableMs?.let { out["disableMs"] = JsonPrimitive(it) }
        out["showTrace6"] = JsonPrimitive(view.showTrace6)
        out["keepItems"] = JsonPrimitive(view.keepItems)
        return JsonObject(out)
    }

    /** 错误正文 + 追踪编号统一生成（不依赖文案里有 {trace6}；有 trace_id 就带末 6 位，没有时用无编号的 fallback）。 */
    fun message(
        texts: OrderTexts,
        view: OrderErrorView,
    ): String {
        val key = view.textKey ?: GENERIC_KEY
        val trace = ResultSignals.trace6(view.traceId)
        // 正文按 format 统一去掉填不上的占位符（{trace6} 之外的 {platform_name} 等）；trace6 由下面独立追加，不依赖文案里有占位符
        // （50301、50303 等已收录但文案没有 {trace6} 的也带，APP-08 共性第 16 条）
        val body = texts.format(key, if (trace.isEmpty()) emptyMap() else mapOf("trace6" to trace))
        return if (view.showTrace6 || view.traceId != null) ResultSignals.withTrace6(body, view.traceId) else body
    }
}
