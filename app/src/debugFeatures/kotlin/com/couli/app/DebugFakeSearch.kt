// 假数据的金额、编号都是演示值，按值直接写
@file:Suppress("MagicNumber")

package com.couli.app

import com.couli.core.clientcore.BundledConfig
import java.io.IOException
import java.net.URLDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Debug 专用的本机假搜索与详情服务（APP-06a 设计稿比对与冒烟用；src/debugFeatures，Release 不编译，release-scan 查
 * [FAKE_SEARCH_SERVER_MARKER]）。与假登录服务同一个开关文件（[DebugFakeLoginServer] 接管时才回答），全部在本机回答，不连任何地址。
 *
 * 场景（按关键词选）：
 * - `/v1/config`：包内配置原样，只把淘宝 / 京东 / 拼多多的 search_status 都设为 on（截三个 Tab 用）；
 * - 搜索：任意关键词第一页 3 张（Search 画板的三张卡），has_more=true，翻页第二页 2 张到底；
 *   「空 / none」无结果 + 推荐 2 张；「慢 / slow」3 秒后回答（被取消立即结束）；「失败 / fail」50401；「关闭 / disabled」京东 Tab 50304
 *   search_disabled；「翻页关闭 / p2disabled」京东第二页 50304 search_disabled；「翻页失败 / p2fail」第二页 50401；「频繁 / busy」42901
 *   （Retry-After 10）；「状态 / states」详情各状态样例（无返利、已下架 30141、信息失效 30143、缓存降级、价格不可用；拼多多另有金额未知卡）；
 *   价格区间、只看有券、券后价 / 返利排序在本页内执行；
 * - 详情：按 product_key 回同一张卡；`*:offshelf` → 30141；`*:expired` → 30143。
 */
@Suppress("TooManyFunctions") // 场景、卡片样例、解析逐项一个函数
internal object DebugFakeSearch {
    /** 回答：(code, data JSON, 响应头)；不是本服务的路径为 null。 */
    fun answer(
        path: String,
        query: String?,
        canceled: () -> Boolean,
    ): Triple<Int, String, Map<String, String>>? {
        val params = parse(query)
        return when {
            path == "/v1/config" -> ok(config())
            path == "/v1/products/search" -> search(params, canceled)
            path.startsWith(PRODUCT_PREFIX) -> detail(URLDecoder.decode(path.removePrefix(PRODUCT_PREFIX), "UTF-8"))
            else -> null
        }
    }

    private fun config(): String {
        val data = Json.parseToJsonElement(BundledConfig.dataJson) as JsonObject
        val features = (data["features"] as? JsonObject).orEmpty()
        val status = JsonObject(listOf("taobao", "jd", "pdd").associateWith { JsonPrimitive("on") })
        return JsonObject(data + ("features" to JsonObject(features + ("search_status" to status)))).toString()
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount") // 场景逐项
    private fun search(
        params: Map<String, String>,
        canceled: () -> Boolean,
    ): Triple<Int, String, Map<String, String>> {
        val q = params["q"].orEmpty()
        val platform = params["platform"] ?: "taobao"
        val page2 = params["cursor"] == CURSOR

        fun has(vararg words: String) = words.any { q.contains(it, ignoreCase = true) }
        if (has("慢", "slow")) waitCancellable(SLOW_MILLIS, canceled)
        when {
            has("频繁", "busy") -> return Triple(RATE_LIMITED, "null", mapOf("Retry-After" to "10"))
            has("翻页失败", "p2fail") && page2 -> return fail(UPSTREAM_TIMEOUT)
            has("翻页关闭", "p2disabled") && page2 && platform == "jd" -> return disabled(platform)
            has("翻页", "p2") -> Unit
            has("失败", "fail") -> return fail(UPSTREAM_TIMEOUT)
            has("关闭", "disabled") && platform == "jd" -> return disabled(platform)
        }
        val prefix = PREFIX[platform] ?: platform
        if (has("空", "none")) {
            return ok(page(emptyList(), cursor = null, fallback = listOf(card(prefix, "fb1", platform), card(prefix, "fb2", platform))))
        }
        val ids =
            when {
                has("状态", "states") -> STATE_IDS + if (platform == "pdd") listOf("unknown") else emptyList()
                page2 -> PAGE2_IDS
                else -> PAGE1_IDS
            }
        var cards = ids.map { card(prefix, it, platform) }
        if (params["has_coupon"] == "true") cards = cards.filter { (it["coupon_fen"] as? JsonPrimitive)?.longOrNull ?: 0 > 0 }
        val min = params["price_min_fen"]?.toLongOrNull()
        val max = params["price_max_fen"]?.toLongOrNull()
        cards =
            cards.filter { c ->
                val f =
                    c.fen("final_price_fen") ?: return@filter true
                (min == null || f >= min) && (max == null || f <= max)
            }
        cards =
            when (params["sort"]) {
                "final_price_asc" -> cards.sortedBy { it.fen("final_price_fen") ?: Long.MAX_VALUE }
                "rebate_desc" -> cards.sortedByDescending { it.fen("rebate_max_fen") ?: 0 }
                "sales_desc" -> cards.reversed()
                else -> cards
            }
        val more = !page2 && !has("状态", "states")
        return ok(page(cards, cursor = if (more) CURSOR else null, fallback = emptyList()))
    }

    private fun detail(key: String): Triple<Int, String, Map<String, String>> {
        val prefix = key.substringBefore(':')
        val id = key.substringAfter(':')
        val platform = PREFIX.entries.firstOrNull { it.value == prefix }?.key ?: "taobao"
        return when (id) {
            "offshelf" -> fail(OFF_SHELF)
            "expired" -> fail(REF_EXPIRED)
            else -> ok(card(prefix, id, platform).toString())
        }
    }

    private fun disabled(platform: String) =
        Triple(DISABLED, """{"platform":"$platform","reason":"search_disabled"}""", emptyMap<String, String>())

    private fun page(
        items: List<JsonObject>,
        cursor: String?,
        fallback: List<JsonObject>,
    ): String =
        JsonObject(
            mapOf(
                "items" to kotlinx.serialization.json.JsonArray(items),
                "next_cursor" to (cursor?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull),
                "has_more" to JsonPrimitive(cursor != null),
                "fallback_items" to kotlinx.serialization.json.JsonArray(fallback),
            ),
        ).toString()

    @Suppress("LongMethod", "CyclomaticComplexMethod") // 画板样例逐张
    private fun card(
        prefix: String,
        id: String,
        platform: String,
    ): JsonObject {
        val s =
            when (id) {
                "milk" -> Sample("全脂纯牛奶 250ml × 24 盒 整箱装 早餐奶 学生奶 送礼礼盒款 家庭装", 2345, 1000, 1345, 1289, 56, 112, "price_compare_risk")
                "milk2" -> Sample("纯牛奶 200ml × 12 盒 多规格可选", 1234, 0, 1234, null, 0, 99, "price_compare_risk")
                "milk3" -> Sample("有机纯牛奶 250ml × 24 盒 预售 下月发货", 9999, 0, 9999, 9749, 250, 500, "normal", presale = true)
                "p2a" -> Sample("低脂高钙牛奶 250ml × 16 盒", 4590, 500, 4090, 3990, 100, 100, "normal")
                "p2b" -> Sample("儿童成长牛奶 190ml × 15 盒", 5990, 0, 5990, 5790, 200, 200, "normal")
                "fb1" -> Sample("酸奶 200g × 12 杯 原味", 3990, 1000, 2990, 2721, 269, 269, "normal")
                "fb2" -> Sample("燕麦奶 1L × 6 盒", 6990, 0, 6990, 6890, 100, 100, "normal")
                "nonrebate" -> Sample("纯牛奶 250ml × 10 盒（无返利样例）", 2990, 0, 2990, 2990, 0, 0, "no_rebate")
                "offshelf" -> Sample("已下架样例 纯牛奶 250ml × 24 盒", 3990, 0, 3990, 3790, 200, 200, "normal")
                "expired" -> Sample("信息已失效样例 纯牛奶 250ml × 24 盒", 3990, 0, 3990, 3790, 200, 200, "normal")
                "stale" -> Sample("缓存降级样例 纯牛奶 250ml × 24 盒", 4990, 1000, 3990, 3790, 200, 200, "normal", stale = true)
                "unavailable" -> Sample("价格不可用样例 纯牛奶 250ml × 24 盒", null, null, null, null, null, null, "normal", unavailable = true)
                "unknown" -> Sample("拼多多直链 金额未知样例", null, null, null, null, null, null, "amount_unknown")
                else -> Sample("全脂纯牛奶 250ml × 24 盒 整箱装 早餐奶 学生奶", 5678, 1000, 4678, 4555, 123, 234, "normal")
            }
        val detailMilk = id == "detail"
        val coupon = (s.coupon ?: 0) > 0
        val cta =
            when {
                s.basis == "no_rebate" -> "btn.buy.no_rebate"
                coupon -> "btn.buy.coupon"
                else -> "btn.buy"
            }
        val disclaimer =
            buildList {
                if (s.basis != "amount_unknown") add(if (coupon) "price_basis" else "price_basis.general")
                if (s.basis == "price_compare_risk") {
                    add("rebate_compare")
                } else if (s.basis == "normal") {
                    add("rebate_estimate")
                }
            }
        val tags =
            buildList {
                if (coupon) add("有券")
                if (s.presale) add("预售")
            }
        val unknown = s.basis == "amount_unknown"
        val fields =
            linkedMapOf<String, JsonElement>(
                "product_key" to if (unknown) kotlinx.serialization.json.JsonNull else JsonPrimitive("$prefix:$id"),
                "item_ref" to if (unknown) kotlinx.serialization.json.JsonNull else JsonPrimitive("v1.$prefix.$id.sig"),
                "platform" to JsonPrimitive(platform),
                "title" to JsonPrimitive(s.title),
                "image" to JsonPrimitive("https://img.example.test/p/$id.jpg"),
                "shop_name" to JsonPrimitive(if (detailMilk || id == "milk") "乳品生活旗舰店" else "示例旗舰店"),
                "price_fen" to s.price.json(),
                "coupon_fen" to s.coupon.json(),
                "final_price_fen" to s.final.json(),
                "est_net_price_fen" to s.estNet.json(),
                "rebate_min_fen" to s.min.json(),
                "rebate_max_fen" to s.max.json(),
                "rebate_basis" to JsonPrimitive(s.basis),
                "benefit_tags" to kotlinx.serialization.json.JsonArray(tags.map(::JsonPrimitive)),
                "is_presale" to JsonPrimitive(s.presale),
                "link_id" to JsonPrimitive("0199a3b4-5c6d-7e8f-9a0b-1c2d3e4f5a70"),
                "cta" to JsonObject(mapOf("text_key" to JsonPrimitive(cta))),
                "quoted_at" to JsonPrimitive(QUOTED_AT),
                "stale" to JsonPrimitive(s.stale),
                "age_sec" to JsonPrimitive(if (s.stale) STALE_AGE else FRESH_AGE),
                "source" to JsonPrimitive("${platform}_union"),
                "disclaimer_keys" to kotlinx.serialization.json.JsonArray(disclaimer.map(::JsonPrimitive)),
                "availability" to
                    JsonPrimitive(
                        if (s.unavailable) {
                            "price_unavailable"
                        } else if (unknown) {
                            "unknown"
                        } else {
                            "ok"
                        },
                    ),
            )
        return JsonObject(fields)
    }

    private data class Sample(
        val title: String,
        val price: Long?,
        val coupon: Long?,
        val final: Long?,
        val estNet: Long?,
        val min: Long?,
        val max: Long?,
        val basis: String,
        val presale: Boolean = false,
        val stale: Boolean = false,
        val unavailable: Boolean = false,
    )

    private fun Long?.json(): JsonElement = this?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull

    private fun JsonObject.fen(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull

    /** 「慢」场景：分段等待，请求被 ApiClient 取消（换 Tab、离开页面）时立即结束。 */
    private fun waitCancellable(
        millis: Long,
        canceled: () -> Boolean,
    ) {
        var waited = 0L
        while (waited < millis) {
            if (canceled()) throw IOException("Canceled")
            Thread.sleep(STEP_MILLIS)
            waited += STEP_MILLIS
        }
        if (canceled()) throw IOException("Canceled")
    }

    private fun parse(query: String?): Map<String, String> =
        query
            ?.split('&')
            ?.filter { it.contains('=') }
            ?.associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            .orEmpty()

    private fun ok(data: String) = Triple(0, data, mapOf(MARKER_HEADER to FAKE_SEARCH_SERVER_MARKER))

    private fun fail(code: Int) = Triple(code, "null", mapOf(MARKER_HEADER to FAKE_SEARCH_SERVER_MARKER))

    private val PREFIX = mapOf("taobao" to "tb", "jd" to "jd", "pdd" to "pdd")
    private val PAGE1_IDS = listOf("milk", "milk2", "milk3")
    private val PAGE2_IDS = listOf("p2a", "p2b")
    private val STATE_IDS = listOf("nonrebate", "offshelf", "expired", "stale", "unavailable")
    private const val PRODUCT_PREFIX = "/v1/products/"
    private const val CURSOR = "p2"
    private const val QUOTED_AT = "2026-10-07T10:00:00+08:00"
    private const val FRESH_AGE = 12
    private const val STALE_AGE = 2160
    private const val SLOW_MILLIS = 3_000L
    private const val STEP_MILLIS = 100L
    private const val RATE_LIMITED = 42901
    private const val UPSTREAM_TIMEOUT = 50401
    private const val DISABLED = 50304
    private const val OFF_SHELF = 30141
    private const val REF_EXPIRED = 30143
    private const val MARKER_HEADER = "X-Couli-Debug-Fake"
}

/** 假搜索服务的唯一标记（release-scan 查；Release 制品里不得出现）。 */
internal const val FAKE_SEARCH_SERVER_MARKER = "COULI_FAKE_SEARCH_SERVER_APP06A_5c1d84"
