package com.couli.feature.search

import com.couli.core.network.ErrorActionMapper
import com.couli.core.network.percentEncode
import com.couli.generated.DefaultTexts
import com.couli.generated.Platform
import com.couli.generated.Sort
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/*
 * 搜索的纯逻辑（APP-06a；向量 specs/search-product-vectors.json，三端同名）。不发请求、不碰界面；执行器是 [SearchController]。
 * 依据：BR-PROD-07（关键词）、BR-PROD-08（游标）、BR-PROD-10 细则「按平台的搜索开关」、BR-PRICE-08 / 15、BR-TEXT-14、03 §3.2 / §4.2 / §4.3。
 * 规则空白（rule-gaps、N-27）一律保守处理。
 */

/** 详情判定在 Core（feature:product 不依赖本模块）；本包转出同名入口（向量测试接线）。 */
typealias ProductDetailState = com.couli.core.designsystem.ProductDetailState

/** SearchQuery.build 的结果。valid=false 时 [query] 为空对象（占位）；[reasonRaw] 恒为 null（TODO(N-27)：无效原因的词表未规定）。 */
data class SearchQueryResult(
    val valid: Boolean,
    /** 平铺的契约 query 参数（q、platform、sort、has_coupon、price_min_fen、price_max_fen、cursor）。 */
    val query: JsonObject,
    val reasonRaw: String? = null,
) {
    /** 拼成 ApiCall.query（不含「?」；值按 RFC 3986 百分号编码，顺序固定）。无效时为空串。 */
    fun toQueryString(): String =
        if (!valid) {
            ""
        } else {
            SearchQuery.ORDER
                .mapNotNull { key ->
                    (query[key] as? JsonPrimitive)?.takeIf { it != JsonNull }?.let { "$key=${percentEncode(it.content)}" }
                }.joinToString("&")
        }

    companion object {
        val INVALID = SearchQueryResult(valid = false, query = JsonObject(emptyMap()))
    }
}

/** 搜索请求的构造（searchProducts 参数，openapi 9cd1e6f）。 */
object SearchQuery {
    /** 关键词长度上限（openapi searchProducts.q maxLength）。 */
    const val KEYWORD_MAX = 100

    /** 价格筛选边界（BR-PRICE-15：1～10,000,000 分，两端含，min ≤ max；比契约 minimum:0 更严，rule-gaps 已登记）。 */
    const val PRICE_MIN_FEN = 1L
    const val PRICE_MAX_FEN = 10_000_000L

    const val KEY_Q = "q"
    const val KEY_PLATFORM = "platform"
    const val KEY_SORT = "sort"
    const val KEY_HAS_COUPON = "has_coupon"
    const val KEY_PRICE_MIN = "price_min_fen"
    const val KEY_PRICE_MAX = "price_max_fen"
    const val KEY_CURSOR = "cursor"

    /** 契约 query 参数的发送顺序。 */
    val ORDER = listOf(KEY_Q, KEY_PLATFORM, KEY_SORT, KEY_HAS_COUPON, KEY_PRICE_MIN, KEY_PRICE_MAX, KEY_CURSOR)

    private val WHITESPACE = Regex("\\s+")

    /**
     * 关键词规范化（客户端）：去掉首尾空白、连续空白合并为一个空格。
     * TODO(N-27)：BR-PROD-07 的 NFKC 与转小写是服务端 norm(q)（缓存键）的步骤，客户端是否复用未规定；客户端不改写大小写与兼容字符。
     */
    fun normalizeKeyword(raw: String): String = raw.replace(WHITESPACE, " ").trim()

    /** 码点个数（代理对算一个）。 */
    fun keywordLength(keyword: String): Int = keyword.codePointCount(0, keyword.length)

    /**
     * input：keyword（必填）、platform（契约 PlatformCode）、sort（契约 SortCode，省略按 relevance）、
     * filters { has_coupon?, price_min_fen?, price_max_fen? }（整数分）、cursor（不透明字符串，原样透传）。
     * 关键词规范化后 1～100 个码点（TODO(N-27)：长度在规范化前还是后、按什么计数未规定，这里按规范化后的码点）。
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod") // 逐项校验，不合法即无效
    fun build(input: JsonObject): SearchQueryResult {
        val keyword = input.string("keyword")?.let(::normalizeKeyword) ?: return SearchQueryResult.INVALID
        if (keywordLength(keyword) !in 1..KEYWORD_MAX) return SearchQueryResult.INVALID
        val platform = input.string(KEY_PLATFORM)?.takeIf { Platform.fromRaw(it) != Platform.UNKNOWN } ?: return SearchQueryResult.INVALID
        val sortRaw = input[KEY_SORT]
        val sort =
            if (sortRaw == null || sortRaw == JsonNull) {
                Sort.RELEVANCE.raw
            } else {
                input.string(KEY_SORT)?.takeIf { Sort.fromRaw(it) != Sort.UNKNOWN } ?: return SearchQueryResult.INVALID
            }
        val query =
            linkedMapOf<String, JsonElement>(
                KEY_Q to JsonPrimitive(keyword),
                KEY_PLATFORM to JsonPrimitive(platform),
                KEY_SORT to JsonPrimitive(sort),
            )
        val filtersRaw = input["filters"]
        if (filtersRaw != null && filtersRaw != JsonNull) {
            val filters = filtersRaw as? JsonObject ?: return SearchQueryResult.INVALID
            val coupon = filters[KEY_HAS_COUPON]
            if (coupon != null && coupon != JsonNull) {
                val flag = (coupon as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: return SearchQueryResult.INVALID
                query[KEY_HAS_COUPON] = JsonPrimitive(flag)
            }
            val min = priceBound(filters[KEY_PRICE_MIN]) ?: return SearchQueryResult.INVALID
            val max = priceBound(filters[KEY_PRICE_MAX]) ?: return SearchQueryResult.INVALID
            if (min.value != null && max.value != null && min.value > max.value) return SearchQueryResult.INVALID
            min.value?.let { query[KEY_PRICE_MIN] = JsonPrimitive(it) }
            max.value?.let { query[KEY_PRICE_MAX] = JsonPrimitive(it) }
        }
        val cursor = input[KEY_CURSOR]
        if (cursor != null && cursor != JsonNull) {
            val text = input.string(KEY_CURSOR)?.takeIf(String::isNotEmpty) ?: return SearchQueryResult.INVALID
            query[KEY_CURSOR] = JsonPrimitive(text)
        }
        return SearchQueryResult(valid = true, query = JsonObject(query))
    }

    /** 价格边界：缺省为 Bound(null)；合法整数分为 Bound(值)；其余（负数、0、小数、超上限、非数值）为 null = 无效。 */
    @Suppress("ReturnCount") // 逐项校验
    fun priceBound(value: JsonElement?): Bound? {
        if (value == null || value == JsonNull) return Bound(null)
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        val fen = primitive.content.toLongOrNull() ?: return null
        return if (fen in PRICE_MIN_FEN..PRICE_MAX_FEN) Bound(fen) else null
    }

    /** 价格边界（null 为不限）。 */
    data class Bound(
        val value: Long?,
    )

    /** 页面条件 → build 的输入。 */
    @Suppress("LongParameterList") // 契约参数逐项
    fun input(
        keyword: String,
        platform: String,
        sort: String,
        hasCoupon: Boolean,
        minFen: Long?,
        maxFen: Long?,
        cursor: String? = null,
    ): JsonObject {
        val filters = linkedMapOf<String, JsonElement>()
        if (hasCoupon) filters[KEY_HAS_COUPON] = JsonPrimitive(true)
        minFen?.let { filters[KEY_PRICE_MIN] = JsonPrimitive(it) }
        maxFen?.let { filters[KEY_PRICE_MAX] = JsonPrimitive(it) }
        val fields =
            linkedMapOf<String, JsonElement>(
                "keyword" to JsonPrimitive(keyword),
                KEY_PLATFORM to JsonPrimitive(platform),
                KEY_SORT to JsonPrimitive(sort),
                "filters" to JsonObject(filters),
            )
        cursor?.let { fields[KEY_CURSOR] = JsonPrimitive(it) }
        return JsonObject(fields)
    }

    /** 价格区间是否合法（与 [build] 同一规则）。 */
    fun filtersValid(
        minFen: Long?,
        maxFen: Long?,
    ): Boolean = build(input("x", Platform.TAOBAO.raw, Sort.RELEVANCE.raw, false, minFen, maxFen)).valid
}

/** 搜索平台 Tab（BR-PROD-10 细则「按平台的搜索开关」）。 */
object SearchTabs {
    /** 候选平台与显示顺序：契约 PlatformCode 的顺序。 */
    val CANDIDATES: List<String> = Platform.known.map { it.raw }
    private const val ON = "on"
    private val LAYER_ORDER = listOf("current", "lkg", "bundled")

    /**
     * [searchStatus]：{ current?, lkg?, bundled? }，每层是 平台 → 取值 的对象（ConfigStore.searchStatusLayers）。逐个平台按
     * current → lkg → bundled 取第一个带该平台取值的层：on 显示；off 与不认识的取值都按 off，且不再回落（「未知状态按 off」）；三层都没有
     * 该平台按 off。TODO(N-27)：取值为 JSON null 时按缺失回落（未规定）。
     */
    fun resolve(searchStatus: JsonObject): List<String> {
        val layers = LAYER_ORDER.mapNotNull { searchStatus[it] as? JsonObject }
        return CANDIDATES.filter { platform -> statusOf(layers, platform) == ON }
    }

    private fun statusOf(
        layers: List<JsonObject>,
        platform: String,
    ): String {
        for (layer in layers) {
            val value = layer[platform]
            if (value == null || value == JsonNull) continue
            return if ((value as? JsonPrimitive)?.takeIf { it.isString }?.content == ON) ON else "off"
        }
        return "off"
    }

    /** 选中的 Tab：想要的平台在可用 Tab 里就用它，否则第一个；没有可用 Tab 为 null。 */
    fun select(
        tabs: List<String>,
        wanted: String?,
    ): String? = wanted?.takeIf { it in tabs } ?: tabs.firstOrNull()
}

/** 结果态。 */
object SearchStateRaw {
    const val CONTENT = "content"
    const val EMPTY = "empty"
    const val ERROR = "error"
}

/**
 * 分页累积（BR-PROD-08：游标不透明、服务端去重；BR-PRICE-08：has_more 按联盟口径，与过滤后页大小无关）。[items] 只含搜索命中，
 * 按服务端顺序追加；fallback_items（无结果推荐）另放，不混入。到底：has_more=false。
 * TODO(N-27)：has_more=true 却没有 next_cursor 时无法继续翻页，按到底处理（矛盾的处理未规定）。
 */
class SearchPager {
    private val collected = mutableListOf<JsonObject>()
    val items: List<JsonObject> get() = collected
    var fallbackItems: List<JsonObject> = emptyList()
        private set
    var endReached: Boolean = false
        private set
    var nextCursor: String? = null
        private set
    private var pages = 0

    val stateRaw: String get() = if (collected.isNotEmpty()) SearchStateRaw.CONTENT else SearchStateRaw.EMPTY

    /** 追加一页（完整成功 envelope）。 */
    fun append(envelope: JsonObject) {
        val data = envelope["data"] as? JsonObject ?: JsonObject(emptyMap())
        collected += data.objects("items")
        // fallback_items 只在首页 items 为空时出现（openapi SearchProductsData）
        if (pages == 0) fallbackItems = data.objects("fallback_items")
        pages += 1
        nextCursor = data.string("next_cursor")?.takeIf(String::isNotEmpty)
        val hasMore = (data["has_more"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true
        endReached = !hasMore || nextCursor == null
    }
}

/** 一次请求的错误（或成功）判定。[textKey]：error.<code>[.<reason>]（字典没有为 null，页面用通用失败键）。 */
data class SearchResultView(
    val stateRaw: String,
    val textKey: String?,
    val code: Int,
    /** 50304 等错误带的 data.platform（文案 {platform_name} 用）。 */
    val platform: String?,
    val traceId: String?,
    /** data.reason（search_disabled 等）。 */
    val reason: String? = null,
)

/** 搜索响应 → 结果态（03 §3.2 content / empty / error；错误文案 BR-TEXT-14，复用 Core 的 [ErrorActionMapper.resolveTextKey]）。 */
object SearchResultState {
    fun map(envelope: JsonObject): SearchResultView {
        val code = (envelope["code"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: -1
        val data = envelope["data"] as? JsonObject ?: JsonObject(emptyMap())
        val traceId = envelope.string("trace_id")
        if (code == 0) {
            val state = if (data.objects("items").isNotEmpty()) SearchStateRaw.CONTENT else SearchStateRaw.EMPTY
            return SearchResultView(state, null, 0, null, traceId)
        }
        val reason = data.string("reason")
        val key = if (code > 0) ErrorActionMapper.resolveTextKey(code, reason) { DefaultTexts.text(it) != null } else null
        return SearchResultView(SearchStateRaw.ERROR, key, code, data.string("platform"), traceId, reason)
    }
}

/**
 * 请求归属与页面世代（APP-06a；lessons：迟到响应只丢弃、出栈即失效）。每次搜索、换 Tab、换排序、换筛选、翻页都 [begin] 一个新序号：
 * 新请求一开始，旧序号就失效（不等新响应）。[leave]（页面出栈）让当前序号失效；序号单调递增、不复用，重新进入后旧请求也不会重新生效。
 * [accept] 只接受当前序号，接受后记为 [shownSeq]。
 */
class SearchSession {
    private var counter = 0L
    private var current: Long? = null

    var shownSeq: Long? = null
        private set

    @Suppress("UnusedParameter") // 种类只用于对照向量与日志
    fun begin(kind: String): Long {
        counter += 1
        current = counter
        return counter
    }

    fun isCurrent(seq: Long): Boolean = current == seq

    /** 响应到达：是当前序号返回 true 并记为已展示；否则丢弃（false）。 */
    fun accept(seq: Long): Boolean {
        if (!isCurrent(seq)) return false
        shownSeq = seq
        return true
    }

    fun leave() {
        current = null
    }
}

internal fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.objects(key: String): List<JsonObject> = (get(key) as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
