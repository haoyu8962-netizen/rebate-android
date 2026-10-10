package com.couli.feature.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import com.couli.core.designsystem.ProductCardTexts
import com.couli.core.designsystem.ProductImageLoader
import com.couli.core.home.CardErrorDedupe
import com.couli.core.home.HomeCall
import com.couli.core.home.HomeContractPort
import com.couli.core.home.HomeIdentity
import com.couli.core.home.HomeLevel
import com.couli.core.home.HomeLkgStore
import com.couli.core.home.HomePrefsStore
import com.couli.core.home.RouteTarget
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallEpochs
import com.couli.core.network.CallOwnership
import com.couli.core.network.LocalRejectReason
import com.couli.core.network.PageApi
import com.couli.core.wallet.WalletRateGate
import java.io.File
import java.time.OffsetDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** 首页渲染测试的替身（本机、确定性；商品数字照 Main 稿）。 */
internal object HomeTestKit {
    val NOON: Long = OffsetDateTime.parse("2026-10-10T12:00:00+08:00").toInstant().toEpochMilli()

    fun vectors(): JsonObject {
        val start = File(System.getProperty("user.dir") ?: ".").canonicalFile
        val root = generateSequence(start) { it.parentFile }.first { File(it, "contract.lock").isFile }
        return Json.parseToJsonElement(File(root, "specs/home-sdui-vectors.json").readText()).jsonObject
    }

    fun golden(name: String): JsonObject =
        vectors()
            .getValue("goldenPages")
            .jsonObject
            .getValue(name)
            .jsonObject

    /** 按 URL 现画一张色块图（不连网）。 */
    val images =
        ProductImageLoader { url ->
            if (!url.startsWith("https://")) {
                null
            } else {
                val bitmap = Bitmap.createBitmap(IMAGE_W, IMAGE_H, Bitmap.Config.ARGB_8888)
                val hue = (url.hashCode() and 0xff) / 255f * 360f
                Canvas(bitmap).drawColor(Color.HSVToColor(floatArrayOf(hue, 0.35f, 0.9f)))
                bitmap.asImageBitmap()
            }
        }

    private const val IMAGE_W = 300
    private const val IMAGE_H = 200

    /** 商品卡的平台名、口径按钮等（与 App 的搜索草稿表同值的一小部分）。 */
    val cardTexts =
        ProductCardTexts { key ->
            when (key) {
                "platform.taobao" -> "淘宝"
                "platform.jd" -> "京东"
                "platform.pdd" -> "拼多多"
                "card.disclaimer_button" -> "价格与返利说明"
                "card.image_placeholder" -> "商品图"
                "card.disclaimer_title" -> "价格与返利说明"
                "card.disclaimer_close" -> "知道了"
                else -> ""
            }
        }

    private class Sample(
        val platform: String,
        val title: String,
        val price: Long,
        val coupon: Long,
        val rebate: Long,
    )

    private val samples =
        listOf(
            Sample("taobao", "林间果 每日坚果 30袋", 2290, 300, 110),
            Sample("jd", "洁澄 洗衣液 3kg 薰衣草香", 2680, 0, 90),
            Sample("taobao", "洗衣凝珠 52 颗 持久留香 低泡易漂", 1860, 300, 78),
            Sample("jd", "电动牙刷替换刷头 4 支装 软毛通用款", 3200, 0, 160),
            Sample("taobao", "抽纸 3 层 120 抽 × 24 包 家用整箱装", 1734, 500, 56),
            Sample("pdd", "厨房纸巾 4 卷 加厚 吸油吸水", 1290, 300, 30),
        )

    fun product(index: Int): JsonObject {
        val s = samples[index % samples.size]
        val coupon = s.coupon > 0
        return JsonObject(
            linkedMapOf(
                "product_key" to JsonPrimitive("test:p$index"),
                "item_ref" to JsonPrimitive("v1.test.p$index.sig"),
                "platform" to JsonPrimitive(s.platform),
                "title" to JsonPrimitive(s.title),
                "image" to JsonPrimitive("https://img.example.test/p/$index.png"),
                "shop_name" to JsonPrimitive("示例旗舰店"),
                "price_fen" to JsonPrimitive(s.price),
                "coupon_fen" to JsonPrimitive(s.coupon),
                "final_price_fen" to JsonPrimitive(s.price - s.coupon),
                "est_net_price_fen" to JsonPrimitive(s.price - s.coupon - s.rebate),
                "rebate_min_fen" to JsonPrimitive(s.rebate),
                "rebate_max_fen" to JsonPrimitive(s.rebate),
                "rebate_basis" to JsonPrimitive("normal"),
                "benefit_tags" to JsonArray(if (coupon) listOf(JsonPrimitive("有券")) else emptyList()),
                "is_presale" to JsonPrimitive(false),
                "link_id" to JsonPrimitive("link.p$index"),
                "cta" to JsonObject(mapOf("text_key" to JsonPrimitive("btn.buy"))),
                "quoted_at" to JsonPrimitive("2026-10-10T10:00:00+08:00"),
                "stale" to JsonPrimitive(false),
                "age_sec" to JsonPrimitive(30),
                "source" to JsonPrimitive("${s.platform}_union"),
                "disclaimer_keys" to
                    JsonArray(
                        listOf(JsonPrimitive(if (coupon) "price_basis" else "price_basis.general"), JsonPrimitive("rebate_estimate")),
                    ),
                "availability" to JsonPrimitive("ok"),
            ),
        )
    }

    fun ok(data: JsonElement): ApiResult = ApiResult.Success(data, "trace-test-000001", null)

    fun items(
        from: Int,
        count: Int,
        next: String?,
    ): ApiResult =
        ok(
            JsonObject(
                mapOf(
                    "items" to JsonArray((from until from + count).map(::product)),
                    "next_cursor" to (next?.let(::JsonPrimitive) ?: JsonNull),
                ),
            ),
        )

    /** 两条测试公告（listArticles 的 data 形状）。 */
    val notices: JsonObject =
        Json
            .parseToJsonElement(
                """{"items":[{"article_id":"a1","category":"notice","title":"t","version":1,"published_at":"2026-10-01T00:00:00+08:00",""" +
                    """"notice":{"item_id":"api1","content_version":1,"text":"确认收货后，返利随联盟月度结算入账","target":null,"closable":true,""" +
                    """"start_at":"2026-10-01T00:00:00+08:00","end_at":null}},""" +
                    """{"article_id":"a2","category":"notice","title":"t","version":1,"published_at":"2026-10-01T00:00:00+08:00",""" +
                    """"notice":{"item_id":"api2","content_version":1,"text":"测试公告：国庆期间结算顺延","target":null,"closable":false,""" +
                    """"start_at":"2026-10-01T00:00:00+08:00","end_at":"2099-01-01T00:00:00+08:00"}}],"next_cursor":null}""",
            ).jsonObject
}

/** 待定 operation 的替身：页面与商品项都可由测试替换；只记录请求。 */
internal class FakePort(
    var page: () -> ApiResult = { HomeTestKit.ok(HomeTestKit.golden("default_home")) },
) : HomeContractPort {
    val pageCalls = mutableListOf<Boolean>()
    val itemCalls = mutableListOf<String>()
    var items: (String, String?) -> ApiResult = { section, cursor ->
        when {
            section == "feed" && cursor == null -> HomeTestKit.items(0, 4, "p2")
            section == "feed" -> HomeTestKit.items(4, 2, null)
            else -> HomeTestKit.items(0, 4, null)
        }
    }

    override suspend fun getPage(
        pageKey: String,
        call: HomeCall,
    ): ApiResult {
        if (!call.allowed()) return ApiResult.LocalReject(LocalRejectReason.CALL_CANCELLED)
        pageCalls += call.anonymous
        return page()
    }

    override suspend fun listSectionItems(
        pageKey: String,
        sectionId: String,
        cursor: String?,
        call: HomeCall,
    ): ApiResult {
        if (!call.allowed()) return ApiResult.LocalReject(LocalRejectReason.CALL_CANCELLED)
        itemCalls += "$sectionId|${cursor.orEmpty()}"
        return items(sectionId, cursor)
    }
}

/** listArticles 的替身（真实 ApiClient 的集成测试在 app 模块）。 */
internal class FakeArticles(
    var reply: () -> ApiResult = { HomeTestKit.ok(HomeTestKit.notices) },
) : PageApi {
    val calls = mutableListOf<ApiCall>()

    override suspend fun send(call: ApiCall): ApiResult {
        calls += call
        if (call.ownership?.sendAllowed?.invoke() == false) return ApiResult.LocalReject(LocalRejectReason.CALL_CANCELLED)
        return reply()
    }

    override fun cancelOwned(owner: CallOwnership) = Unit

    override fun epochs(): CallEpochs = CallEpochs(0, 0)
}

/** 手动推进的计时（测试不睡）。 */
internal class ManualTimers(
    private val clock: () -> Long,
) : HomeTimers {
    private class Pending(
        val at: Long,
        val block: () -> Unit,
    ) {
        var cancelled = false
    }

    private val pending = mutableListOf<Pending>()

    override fun after(
        delayMs: Long,
        block: () -> Unit,
    ): () -> Unit {
        val item = Pending(clock() + delayMs, block)
        pending += item
        return { item.cancelled = true }
    }

    /** 跑掉到期的计时（[now] 是推进后的时刻）。 */
    fun fire(now: Long) {
        while (true) {
            val due = pending.firstOrNull { !it.cancelled && it.at <= now } ?: return
            pending -= due
            due.block()
        }
    }
}

/** 固定身份与信号的宿主。 */
internal class FakeHost(
    var current: HomeIdentity = IDENTITY,
) : HomeHost {
    val opened = mutableListOf<RouteTarget>()
    val errors = mutableListOf<String>()
    var forced = 0

    override fun identity(): HomeIdentity = current

    override fun open(
        target: RouteTarget,
        spm: String,
    ) {
        opened += target
    }

    override fun forceUpdate() {
        forced += 1
    }

    override fun reportCardError(
        type: String,
        version: Int?,
        pageVersion: Int,
        reason: String,
    ) {
        errors += "$pageVersion|$type|$reason"
    }

    companion object {
        val IDENTITY = HomeIdentity(HomeLevel.GUEST, null, 0L, CallEpochs(0, 0), "test")
    }
}

internal class FakeSignals : HomeSignals {
    val visibleFlow = MutableStateFlow(true)
    val readerFlow = MutableStateFlow(false)
    val changes = MutableStateFlow(0L)
    var blocked: Set<String> = emptySet()

    override val visible: StateFlow<Boolean> get() = visibleFlow
    override val screenReader: StateFlow<Boolean> get() = readerFlow

    override fun blockers(): Set<String> = blocked

    override val blockerChanges: StateFlow<Long> get() = changes
}

/** 组装一个首页控制器的依赖。 */
@Suppress("LongParameterList")
internal fun testDeps(
    host: HomeHost = FakeHost(),
    signals: HomeSignals = FakeSignals(),
    port: HomeContractPort? = FakePort(),
    api: PageApi = FakeArticles(),
    lkg: HomeLkgStore? = null,
    prefs: HomePrefsStore? = null,
    clock: () -> Long = { HomeTestKit.NOON },
    timers: HomeTimers = ManualTimers(clock),
    appVersion: String = "1.10.0",
    bundled: () -> String? = { HomeTestKit.golden("default_home").toString() },
    images: ProductImageLoader? = HomeTestKit.images,
    io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Main.immediate,
): HomeDeps =
    HomeDeps(
        host = host,
        signals = signals,
        port = port,
        api = api,
        lkg = lkg,
        prefs = prefs,
        gate = WalletRateGate(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), clock),
        images = images,
        timers = timers,
        clock = clock,
        bundled = bundled,
        platform = "android",
        appVersion = appVersion,
        cardTexts = HomeTestKit.cardTexts,
        dedupe = CardErrorDedupe(),
        io = io,
    )
