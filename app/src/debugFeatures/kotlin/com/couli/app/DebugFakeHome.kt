// 假数据的商品名、价格、返利、公告都照设计稿 Main 稿的样例（sample-data §0），按值直接写
@file:Suppress("MagicNumber", "ReturnCount", "TooManyFunctions")

package com.couli.app

import com.couli.core.home.HomeCall
import com.couli.core.home.HomeContractPort
import com.couli.core.network.ApiResult
import com.couli.core.network.ErrorEffect
import com.couli.core.network.ErrorEffectKind
import com.couli.core.network.LocalRejectReason
import com.couli.core.network.ReplayMode
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Debug 专用的本机假首页服务（APP-11；src/debugFeatures，Release 不编译，release-scan 查 [FAKE_HOME_SERVER_MARKER]）：
 * - 待定 operation（G02 getPage、G03 listSectionItems）的唯一实现（[HomeContractPort]）；
 * - 契约已有的 previewPage、listArticles(category=notice) 由假登录服务的拦截器转到 [answer]（真实 ApiClient → 拦截器 → 这里）。
 * **环境闸门**（F-X-02，共性第 10、16 条）：只有「假服务开关文件在」且「当前环境允许 MOCK_DATA」（prod 下一律关闭）才回答；否则待定 operation 按断网、
 * 契约接口交给真实网络（不注入假数据）。全部在本机回答，不连任何地址。
 *
 * 状态文件 files/[FAKE_HOME_STATE_FILE]（每行 key=value）：home=main|guest|slow|fail|limited|bad|v2（默认 main）；feed=fail（信息流第二页失败）；
 * pool=empty（商品池返回空）；notice=none（listArticles 返回空）。
 */
internal class DebugFakeHome(
    private val stateFile: File,
    private val switchOn: () -> Boolean,
    private val mockAllowed: () -> Boolean,
) : HomeContractPort {
    /** 最近一次 getPage 是否匿名（基本模式冒烟核对）。 */
    @Volatile var lastAnonymous: Boolean? = null

    /** 最近的 listSectionItems 请求（只记 section id 与游标，核对请求不带频道、排序参数）。 */
    val itemRequests: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    private var serial = 0

    /** 假服务此刻可用：开关文件在 + 当前环境允许 MOCK_DATA。 */
    fun enabled(): Boolean = switchOn() && mockAllowed()

    fun state(): Map<String, String> =
        runCatching {
            stateFile
                .takeIf(File::exists)
                ?.readLines()
                ?.mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }
                ?.toMap()
        }.getOrNull().orEmpty()

    override suspend fun getPage(
        pageKey: String,
        call: HomeCall,
    ): ApiResult {
        if (!enabled()) return OFFLINE
        if (!call.allowed()) return ApiResult.LocalReject(LocalRejectReason.CALL_CANCELLED)
        lastAnonymous = call.anonymous
        val scene = state()["home"] ?: "main"
        if (scene == "slow") delay(SLOW_MS)
        if (!call.stillOwned()) return ApiResult.LocalReject(LocalRejectReason.CALL_CANCELLED)
        return when (scene) {
            "fail" -> failure(50001)
            "limited" -> failure(42901)
            "bad" -> ok(JsonObject(mapOf("page_key" to JsonPrimitive("home"), "version" to JsonPrimitive(0))))
            "guest" -> ok(page(GUEST_PAGE))
            "v2" -> ok(page(MAIN_PAGE.replace("\"version\":31", "\"version\":32")))
            else -> ok(page(MAIN_PAGE))
        }
    }

    override suspend fun listSectionItems(
        pageKey: String,
        sectionId: String,
        cursor: String?,
        call: HomeCall,
    ): ApiResult {
        if (!enabled()) return OFFLINE
        if (!call.allowed()) return ApiResult.LocalReject(LocalRejectReason.CALL_CANCELLED)
        itemRequests += "$sectionId|${cursor.orEmpty()}"
        if (state()["pool"] == "empty") return ok(JsonObject(mapOf("items" to JsonArray(emptyList()), "next_cursor" to JsonNull)))
        val (ids, next) =
            when {
                sectionId == "scroll" -> listOf("nuts", "liquid", "tissue_small") to null
                sectionId == "grid" -> listOf("pods", "brush", "tissue", "cup", "kitchen", "rice") to null
                cursor == null -> listOf("pods", "cup", "nuts", "brush") to "p2"
                state()["feed"] == "fail" -> return failure(50001)
                else -> listOf("kitchen", "rice") to null
            }
        val items = ids.map { card(sectionId, it) }
        return ok(JsonObject(mapOf("items" to JsonArray(items), "next_cursor" to (next?.let(::JsonPrimitive) ?: JsonNull))))
    }

    /**
     * 契约接口的本机回答（拦截器调用）：(code, data JSON, 响应头)；不是本服务的路径为 null；环境不允许假数据时返回 [PROCEED]（交给真实网络）。
     */
    fun answer(
        path: String,
        query: String?,
    ): Triple<Int, String, Map<String, String>>? {
        val preview = path.startsWith("/v1/pages/") && path.endsWith("/preview")
        val articles = path == "/v1/articles" && query.orEmpty().contains("category=notice")
        if (!preview && !articles) return null
        if (!enabled()) return PROCEED
        if (articles) {
            if (state()["notice"] == "none") return Triple(0, """{"items":[],"next_cursor":null}""", emptyMap())
            return Triple(0, NOTICES, emptyMap())
        }
        val token =
            query
                .orEmpty()
                .split('&')
                .firstOrNull { it.startsWith("token=") }
                ?.removePrefix("token=")
                .orEmpty()
        return when {
            token.isEmpty() || token.startsWith("expired") -> Triple(30701, "null", emptyMap())
            token.startsWith("fail") -> Triple(50001, "null", emptyMap())
            else -> Triple(0, PREVIEW_PAGE, emptyMap())
        }
    }

    private fun page(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun ok(data: JsonElement): ApiResult = ApiResult.Success(data, "fake-home-${"%06d".format(++serial)}", null)

    private fun failure(code: Int): ApiResult =
        ApiResult.Failure(
            code,
            "",
            JsonObject(emptyMap()),
            "fake-home-trace-${"%06d".format(++serial)}",
            ErrorEffect(
                code,
                if (code == 42901) ErrorEffectKind.DISABLE_BUTTON else ErrorEffectKind.TOAST,
                ReplayMode.NONE,
                false,
                disableMillis = if (code == 42901) 30_000L else null,
            ),
            null,
        )

    /** 首页商品卡（契约 ProductCard 形状；数字照 Main 稿）。 */
    private fun card(
        section: String,
        id: String,
    ): JsonObject {
        val s = SAMPLES.getValue(id)
        val coupon = s.coupon > 0
        val keys = listOf(if (coupon) "price_basis" else "price_basis.general", "rebate_estimate")
        val tags =
            buildList {
                if (coupon) add("有券")
                if (s.presale) add("预售")
            }
        return JsonObject(
            linkedMapOf(
                "product_key" to JsonPrimitive("home:$id"),
                "item_ref" to JsonPrimitive("v1.home.$section.$id.sig"),
                "platform" to JsonPrimitive(s.platform),
                "title" to JsonPrimitive(s.title),
                "image" to JsonPrimitive("https://img.example.test/p/home-$id.png"),
                "shop_name" to JsonPrimitive("示例旗舰店"),
                "price_fen" to JsonPrimitive(s.price),
                "coupon_fen" to JsonPrimitive(s.coupon),
                "final_price_fen" to JsonPrimitive(s.price - s.coupon),
                "est_net_price_fen" to JsonPrimitive(s.price - s.coupon - s.rebate),
                "rebate_min_fen" to JsonPrimitive(s.rebate),
                "rebate_max_fen" to JsonPrimitive(s.rebate),
                "rebate_basis" to JsonPrimitive("normal"),
                "benefit_tags" to JsonArray(tags.map(::JsonPrimitive)),
                "is_presale" to JsonPrimitive(s.presale),
                "link_id" to JsonPrimitive("fakelink.home.$id"),
                "cta" to JsonObject(mapOf("text_key" to JsonPrimitive(if (coupon) "btn.buy.coupon" else "btn.buy"))),
                "quoted_at" to JsonPrimitive("2026-10-10T10:00:00+08:00"),
                "stale" to JsonPrimitive(false),
                "age_sec" to JsonPrimitive(30),
                "source" to JsonPrimitive("${s.platform}_union"),
                "disclaimer_keys" to JsonArray(keys.map(::JsonPrimitive)),
                "availability" to JsonPrimitive("ok"),
            ),
        )
    }

    private class Sample(
        val platform: String,
        val title: String,
        val price: Long,
        val coupon: Long,
        val rebate: Long,
        val presale: Boolean = false,
    )

    companion object {
        private const val SLOW_MS = 2_500L

        /** 交给真实网络（拦截器据此 proceed，不注入假数据）。 */
        val PROCEED: Triple<Int, String, Map<String, String>> = Triple(Int.MIN_VALUE, "", emptyMap())

        private val OFFLINE: ApiResult = ApiResult.TransportFailure("disconnect", null, null, false)

        private val SAMPLES: Map<String, Sample> =
            mapOf(
                "nuts" to Sample("taobao", "林间果 每日坚果 30袋", 2290, 300, 110),
                "liquid" to Sample("jd", "洁澄 洗衣液 3kg 薰衣草香", 2680, 0, 90),
                "tissue_small" to Sample("taobao", "加厚双层 抽纸 6包", 980, 100, 42),
                "pods" to Sample("taobao", "洗衣凝珠 52 颗 持久留香 低泡易漂", 1860, 300, 78),
                "brush" to Sample("jd", "电动牙刷替换刷头 4 支装 软毛通用款", 3200, 0, 160),
                "tissue" to Sample("taobao", "抽纸 3 层 120 抽 × 24 包 家用整箱装", 1734, 500, 56),
                "cup" to Sample("jd", "保温杯 500ml 不锈钢 带茶隔", 4560, 0, 234),
                "kitchen" to Sample("pdd", "厨房纸巾 4 卷 加厚 吸油吸水", 1290, 300, 30),
                "rice" to Sample("taobao", "东北长粒香大米 10kg 新米 预售款", 8800, 0, 321, presale = true),
            )

        private const val NOTICES =
            """{"items":[""" +
                """{"article_id":"test_notice_1","category":"notice","title":"测试公告一","version":1,""" +
                """"published_at":"2026-10-01T00:00:00+08:00",""" +
                """"notice":{"item_id":"notice_api_1","content_version":1,"text":"确认收货后，返利随联盟月度结算入账",""" +
                """"target":{"route":"Notice","params":{"article_id":"test_notice_1"}},"closable":true,""" +
                """"start_at":"2026-10-01T00:00:00+08:00","end_at":null}},""" +
                """{"article_id":"test_notice_2","category":"notice","title":"测试公告二","version":1,""" +
                """"published_at":"2026-10-01T00:00:00+08:00",""" +
                """"notice":{"item_id":"notice_api_2","content_version":1,"text":"国庆期间结算顺延，测试公告","target":null,""" +
                """"closable":false,"start_at":"2026-10-01T00:00:00+08:00","end_at":"2099-01-01T00:00:00+08:00"}}""" +
                """],"next_cursor":null}"""

        private const val ICONS =
            """{"id":"icons","type":"icon_grid","version":1,"props":{"columns":5,"rows":2},"data_source":{"kind":"static","items":[""" +
                """{"icon":"builtin:platform.taobao","title":"淘宝","action":{"route":"Search","params":{"platform":"taobao"}}},""" +
                """{"icon":"builtin:platform.jd","title":"京东","action":{"route":"Search","params":{"platform":"jd"}}},""" +
                """{"icon":"builtin:platform.pdd","title":"拼多多","action":{"route":"Search","params":{"platform":"pdd"}}},""" +
                """{"icon":"builtin:category.supermarket","title":"超市百货","action":{"route":"Search","params":{"q":"超市百货"}}},""" +
                """{"icon":"builtin:category.fresh","title":"食品生鲜","action":{"route":"Search","params":{"q":"食品生鲜"}}},""" +
                """{"icon":"builtin:category.baby","title":"母婴用品","action":{"route":"Search","params":{"q":"母婴用品"}}},""" +
                """{"icon":"builtin:category.beauty","title":"美妆个护","action":{"route":"Search","params":{"q":"美妆个护"}}},""" +
                """{"icon":"builtin:category.household","title":"家居日用","action":{"route":"Search","params":{"q":"家居日用"}}},""" +
                """{"icon":"builtin:category.electronics","title":"数码家电","action":{"route":"Search","params":{"q":"数码家电"}}},""" +
                """{"icon":"builtin:guide","title":"新人指南","action":{"route":"Help"}}]}}"""

        private const val BANNER =
            """{"id":"banner","type":"banner","version":1,"ad_label":"推广","props":{"height_ratio":0.4,"autoplay_ms":4000},""" +
                """"data_source":{"kind":"static","items":[""" +
                """{"image":"https://img.example.test/p/home-banner-a.png","alt":"测试活动甲",""" +
                """"action":{"route":"Search","params":{"q":"纸巾"}}},""" +
                """{"image":"https://img.example.test/p/home-banner-b.png","alt":"测试活动乙","action":{"route":"ExternalPage",""" +
                """"params":{"url":"https://x.example.com/a?b=1","title":"品牌活动说明","nav_style":"dark"}}}]}}"""

        /** 已登录完整首页（Main 稿的顺序：公告条 → 轮播 → 金刚区 → 今日精选 → 双入口 → 超市百货 → 商品流 → 弹窗）。 */
        private const val MAIN_PAGE =
            """{"page_key":"home","version":31,"ttl_sec":300,"sections":[""" +
                """{"id":"notice","type":"notice_bar","version":1,"props":{"mode":"static","items":[""" +
                """{"item_id":"notice_main","content_version":1,"text":"确认收货后，返利随联盟月度结算入账",""" +
                """"target":{"route":"Notice","params":{"article_id":"test_notice_main"}},"closable":true,""" +
                """"start_at":"2026-10-01T00:00:00+08:00","end_at":null}]},"data_source":{"kind":"static"}},""" +
                BANNER + "," + ICONS + "," +
                """{"id":"scroll","type":"product_scroll","version":1,"ad_label":"推广","props":{"title":"今日精选",""" +
                """"more_action":{"route":"HotRankList","params":{}}},"data_source":{"kind":"product_pool",""" +
                """"pool_id":"test_pool_scroll","limit":20}},""" +
                """{"id":"activities","type":"activity_entry","version":1,"props":{},"data_source":{"kind":"static","items":[""" +
                """{"title":"新人怎么拿返利","subtitle":"三步看懂","action":{"route":"Help"}},""" +
                """{"title":"订单没显示怎么办","subtitle":"填订单号找回","action":{"route":"FindOrder"}}]}},""" +
                """{"id":"grid","type":"product_grid","version":1,"props":{"columns":2,"title":"超市百货"},""" +
                """"data_source":{"kind":"product_pool","pool_id":"test_pool_grid","limit":20}},""" +
                """{"id":"feed","type":"feed_infinite","version":1,"data_source":{"kind":"union_material",""" +
                """"platforms":["taobao","jd","pdd"],"mix":"interleave"}},""" +
                """{"id":"popup","type":"popup","version":1,"props":{"image":"https://img.example.test/p/home-popup.png","daily_max":1,""" +
                """"time_window":["00:00","23:59"]},"data_source":{"kind":"static",""" +
                """"items":[{"action":{"route":"AuthSheet","params":{"platform":"taobao"}}}]}}""" +
                """]}"""

        /** 游客首页（HomeGuest 稿：搜索、轮播、商品卡）。 */
        private const val GUEST_PAGE =
            """{"page_key":"home","version":41,"sections":[""" + BANNER + "," +
                """{"id":"grid","type":"product_grid","version":1,"props":{"columns":2,"title":"超市百货"},""" +
                """"data_source":{"kind":"product_pool","pool_id":"test_pool_grid","limit":20}}]}"""

        /** 草稿（HomePreview 稿：轮播 + 四个入口 + 商品池；带一张 version=2 的卡给「被丢弃的卡」列表）。 */
        private const val PREVIEW_PAGE =
            """{"page_key":"home","version":12,"sections":[""" + BANNER + "," +
                """{"id":"draft_icons","type":"icon_grid","version":1,"props":{"columns":4,"rows":1},""" +
                """"data_source":{"kind":"static","items":[""" +
                """{"icon":"builtin:platform.taobao","title":"淘宝","action":{"route":"Search","params":{"platform":"taobao"}}},""" +
                """{"icon":"builtin:platform.jd","title":"京东","action":{"route":"Search","params":{"platform":"jd"}}},""" +
                """{"icon":"builtin:platform.pdd","title":"拼多多","action":{"route":"Search","params":{"platform":"pdd"}}},""" +
                """{"icon":"builtin:guide","title":"新人指南","action":{"route":"Help"}}]}},""" +
                """{"id":"grid","type":"product_grid","version":1,"props":{"columns":2,"title":"超市百货"},""" +
                """"data_source":{"kind":"product_pool","pool_id":"test_pool_grid","limit":20}},""" +
                """{"id":"draft_v2","type":"banner","version":2,"props":{"height_ratio":0.4,"autoplay_ms":0},""" +
                """"data_source":{"kind":"static","items":[]}}]}"""
    }
}

/** 假首页服务的唯一标记（release-scan 查；Release 制品里不得出现）。 */
internal const val FAKE_HOME_SERVER_MARKER = "COULI_FAKE_HOME_SERVER_APP11_3d9a5e"

/** 假首页服务的状态文件名（files/ 下，每行 key=value）。 */
internal const val FAKE_HOME_STATE_FILE = "couli_fake_home_state.txt"
