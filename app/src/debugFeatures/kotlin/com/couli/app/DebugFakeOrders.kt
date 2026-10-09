// 假数据的金额、编号都是演示值（设计稿 sample-data §1、§12.2、§12.3），按值直接写
@file:Suppress("MagicNumber")

package com.couli.app

import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Debug 专用的本机假订单服务（APP-08 设计稿比对与冒烟用；src/debugFeatures，Release 不编译，release-scan 查 [FAKE_ORDERS_SERVER_MARKER]）。
 * 与假登录服务同一个开关文件（[DebugFakeLoginServer] 接管时才回答），全部在本机回答，不连任何地址。数据照设计稿 sample-data §1、§12.2、§12.3
 * （订单号、金额、时间都是样例值，没有个人信息）。
 *
 * - `GET /v1/orders`：自购 15 单（每页 5 单，三页）、分享 5 单（4 + 1）；按 status_group、platform、paid_month 在服务端筛；q：自购单与父单号完全相同
 *   按单号命中，否则按标题包含；分享单只按标题、不按单号、好友买的其他商品不参与（BR-TEXT-02 细则「订单的检索范围」）。§12.3 的补稿订单不进列表，
 *   按完整单号搜索能命中（便于打开它们的详情）。q 里的场景词：`fail` 50001、`busy` 42901（Retry-After 10）、`slow` 3 秒后回答；
 * - `GET /v1/orders/{order_id}`：样例详情；`ord-missing` → 30701；
 * - `GET /v1/orders/pending-tracks` 与 `POST …/dismiss`：状态开关 pending=on 时淘宝、京东各一张（京东带找回入口）、拼多多一张已关闭；关闭记在本进程。
 * 状态开关文件 files/[FAKE_ORDERS_STATE_FILE]（每行 key=value）：offline=1（订单接口一律传输失败，断网演示）、history=YYYY-MM-DD
 * （earliest_visible_date）、empty=1（没有订单）、pending=on、dismiss=busy|stale（关闭回答 42901 / 20001）、appeal=1（剃须刀那单申诉中）。
 */
@Suppress("TooManyFunctions") // 每个接口一个场景函数
internal class DebugFakeOrders(
    private val stateFile: File,
) {
    private val dismissed = ConcurrentHashMap.newKeySet<String>()

    /** 回答：(code, data JSON, 响应头)；不是本服务的路径为 null。离线开关打开时抛 IOException（传输失败）。 */
    @Suppress("ReturnCount")
    fun answer(
        method: String,
        path: String,
        query: String?,
        canceled: () -> Boolean,
    ): Triple<Int, String, Map<String, String>>? {
        if (!path.startsWith(PREFIX)) return null
        val state = state()
        if (state["offline"] == "1") throw IOException("fake offline")
        return when {
            path == "$PREFIX/pending-tracks" -> pending(state)
            path.startsWith("$PREFIX/pending-tracks/") && method == "POST" -> dismiss(path, state)
            path == PREFIX -> list(parse(query), state, canceled)
            else -> detail(URLDecoder.decode(path.removePrefix("$PREFIX/"), "UTF-8"), state)
        }
    }

    // ---------------------------------------------------------------- 列表

    @Suppress("CyclomaticComplexMethod", "ReturnCount") // 场景与筛选逐项
    private fun list(
        params: Map<String, String>,
        state: Map<String, String>,
        canceled: () -> Boolean,
    ): Triple<Int, String, Map<String, String>> {
        val q = params["q"]
        when (q) {
            "fail" -> return fail(50001)
            "busy" -> return Triple(42901, "null", mapOf(MARKER_HEADER to FAKE_ORDERS_SERVER_MARKER, "Retry-After" to "10"))
            "slow" -> waitCancellable(canceled)
        }
        val share = params["scope"] == "share"
        val all =
            if (state["empty"] == "1") {
                emptyList()
            } else if (share) {
                SHARE
            } else {
                SELF
            }
        var rows = all
        params["status_group"]?.let { group -> rows = rows.filter { groupOf(it.status) == group } }
        params["platform"]?.let { platform -> rows = rows.filter { it.platform == platform } }
        params["paid_month"]?.let { month -> rows = rows.filter { (it.paidAt ?: it.depositAt)?.startsWith(month) == true } }
        if (q != null) rows = search(q, share, rows)
        val page = params["cursor"]?.removePrefix(CURSOR_PREFIX)?.toIntOrNull() ?: 0
        val chunk = rows.drop(page * PAGE_SIZE).take(PAGE_SIZE)
        val more = rows.size > (page + 1) * PAGE_SIZE
        val data =
            JsonObject(
                mapOf(
                    "items" to JsonArray(chunk.map { it.summary() }),
                    "next_cursor" to if (more) JsonPrimitive(CURSOR_PREFIX + (page + 1)) else JsonNull,
                    "earliest_visible_date" to (state["history"]?.let(::JsonPrimitive) ?: JsonNull),
                ),
            )
        return ok(data.toString())
    }

    /** 服务端的检索（BR-TEXT-02 细则）：自购单完整单号命中，否则标题包含；分享单只按标题，其他商品不参与。补稿订单只按完整单号命中。 */
    @Suppress("ReturnCount") // 分享、按单号、按标题三个出口
    private fun search(
        q: String,
        share: Boolean,
        rows: List<Sample>,
    ): List<Sample> {
        if (share) return rows.filter { !it.otherProduct && it.title?.contains(q, ignoreCase = true) == true }
        val byNumber = (rows + EXTRA).filter { it.orderNo == q }
        if (byNumber.isNotEmpty()) return byNumber
        return rows.filter { it.title?.contains(q, ignoreCase = true) == true }
    }

    private fun groupOf(status: String): String =
        when (status) {
            "DEPOSIT_PAID", "PAID", "WAITING", "CREDITING", "RIGHTS_PENDING", "REVIEWING" -> "estimating"
            "CREDITED", "CREDITED_PART_CLAWED" -> "credited"
            "NO_REBATE", "INVALID", "CLAWED_BACK" -> "no_rebate"
            else -> "all-only"
        }

    // ---------------------------------------------------------------- 详情

    private fun detail(
        orderId: String,
        state: Map<String, String>,
    ): Triple<Int, String, Map<String, String>> {
        val sample = (SELF + SHARE + EXTRA).firstOrNull { it.id == orderId } ?: return fail(30701)
        val appeal = sample.id == BLACKLIST_ID && state["appeal"] == "1"
        return ok(sample.detail(appeal).toString())
    }

    // ---------------------------------------------------------------- 待跟单卡

    private fun pending(state: Map<String, String>): Triple<Int, String, Map<String, String>> {
        if (state["pending"] != "on") return ok("""{"items":[]}""")
        val items =
            listOf(
                track("lnk-tb-01", "att-tb-01", "taobao", claim = false, closed = false),
                track("lnk-jd-01", "att-jd-01", "jd", claim = true, closed = false),
                track("lnk-pdd-01", "att-pdd-01", "pdd", claim = false, closed = true),
            )
        return ok(JsonObject(mapOf("items" to JsonArray(items))).toString())
    }

    private fun track(
        link: String,
        attempt: String,
        platform: String,
        claim: Boolean,
        closed: Boolean,
    ): JsonObject =
        JsonObject(
            mapOf(
                "link_id" to JsonPrimitive(link),
                "attempt_id" to JsonPrimitive(attempt),
                "platform" to JsonPrimitive(platform),
                "jumped_at" to JsonPrimitive("2026-10-09T10:00:00+08:00"),
                "show_claim_entry" to JsonPrimitive(claim),
                "dismissed" to JsonPrimitive(closed || attempt in dismissed),
            ),
        )

    @Suppress("ReturnCount") // 限流、过期、成功三个出口
    private fun dismiss(
        path: String,
        state: Map<String, String>,
    ): Triple<Int, String, Map<String, String>> {
        when (state["dismiss"]) {
            "busy" -> return Triple(42901, "null", mapOf(MARKER_HEADER to FAKE_ORDERS_SERVER_MARKER, "Retry-After" to "10"))
            "stale" -> return fail(20001)
        }
        val link = path.removePrefix("$PREFIX/pending-tracks/").removeSuffix("/dismiss")
        dismissed += link.replace("lnk-", "att-")
        return ok("{}")
    }

    // ---------------------------------------------------------------- 工具

    private fun state(): Map<String, String> =
        runCatching {
            stateFile
                .takeIf(File::exists)
                ?.readLines()
                ?.mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }
                ?.toMap()
        }.getOrNull().orEmpty()

    private fun parse(query: String?): Map<String, String> =
        query
            ?.split('&')
            ?.filter { it.contains('=') }
            ?.associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            .orEmpty()

    private fun waitCancellable(canceled: () -> Boolean) {
        val until = System.currentTimeMillis() + SLOW_MILLIS
        while (System.currentTimeMillis() < until) {
            if (canceled()) throw IOException("Canceled")
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun ok(data: String) = Triple(0, data, mapOf(MARKER_HEADER to FAKE_ORDERS_SERVER_MARKER))

    private fun fail(code: Int) = Triple(code, "null", mapOf(MARKER_HEADER to FAKE_ORDERS_SERVER_MARKER))

    /** 一单样例（列表行 + 详情字段）。 */
    @Suppress("LongParameterList") // 样例订单的字段逐项
    private class Sample(
        val id: String,
        val orderNo: String?,
        val masked: String?,
        val platform: String,
        val title: String?,
        val image: Boolean,
        val pay: Long?,
        val quantity: Int,
        val status: String,
        val est: Long?,
        val paidAt: String?,
        val depositAt: String? = null,
        val receivedAt: String? = null,
        val period: String? = null,
        val creditedAt: String? = null,
        val endAt: String? = null,
        val actual: Long? = null,
        val clawback: Long? = null,
        val reason: String? = null,
        val actions: List<String> = emptyList(),
        val priceCompare: Boolean? = false,
        val otherProduct: Boolean = false,
        val productKey: String? = null,
    ) {
        fun summary(): JsonObject {
            val out =
                linkedMapOf<String, JsonElement>(
                    "order_id" to JsonPrimitive(id),
                    "platform" to JsonPrimitive(platform),
                    "title" to (title?.let(::JsonPrimitive) ?: JsonNull),
                    "image_url" to if (image) JsonPrimitive("https://img.example.test/p/$id.jpg") else JsonNull,
                    "pay_amount_fen" to (pay?.let(::JsonPrimitive) ?: JsonNull),
                    "quantity" to JsonPrimitive(quantity),
                    "display_status" to JsonPrimitive(status),
                    "reason" to (reason?.let(::JsonPrimitive) ?: JsonNull),
                    "est_rebate_fen" to (est?.let(::JsonPrimitive) ?: JsonNull),
                    "paid_at" to (paidAt?.let(::JsonPrimitive) ?: JsonNull),
                    "is_other_product" to JsonPrimitive(otherProduct),
                )
            if (orderNo != null) out["order_no"] = JsonPrimitive(orderNo) else out["masked_order_no"] = JsonPrimitive(masked.orEmpty())
            return JsonObject(out)
        }

        fun detail(appeal: Boolean): JsonObject =
            JsonObject(
                summary() +
                    mapOf(
                        "product_key" to (productKey?.let(::JsonPrimitive) ?: JsonNull),
                        "reason_action" to JsonArray(actions.map(::JsonPrimitive)),
                        "timeline" to JsonArray(timeline()),
                        "expected_credit_period" to if (status == "WAITING") (period?.let(::JsonPrimitive) ?: JsonNull) else JsonNull,
                        "credit_overdue" to JsonPrimitive(false),
                        "actual_fen" to (actual?.let(::JsonPrimitive) ?: JsonNull),
                        "clawback_fen" to (clawback?.let(::JsonPrimitive) ?: JsonNull),
                        "appeal_pending" to JsonPrimitive(appeal),
                        "is_price_compare" to (priceCompare?.let(::JsonPrimitive) ?: JsonNull),
                    ),
            )

        private fun timeline(): List<JsonObject> {
            val nodes = mutableListOf<JsonObject>()
            if (depositAt != null) {
                nodes += node("deposit_paid", depositAt)
                nodes += node("final_paid", paidAt)
            } else {
                nodes += node("paid", paidAt)
            }
            nodes += node("received", receivedAt)
            nodes += node("credit_expected", null, period)
            nodes += node("credited", creditedAt)
            when (status) {
                "INVALID" -> nodes += node("invalid", endAt)
                "CLAWED_BACK" -> nodes += node("clawed_back", endAt)
                "CREDITED_PART_CLAWED" -> nodes += node("part_clawed_back", endAt)
            }
            return nodes
        }

        private fun node(
            name: String,
            at: String?,
            period: String? = null,
        ): JsonObject =
            JsonObject(
                mapOf(
                    "node" to JsonPrimitive(name),
                    "at" to (at?.let(::JsonPrimitive) ?: JsonNull),
                    "period" to (period?.let(::JsonPrimitive) ?: JsonNull),
                ),
            )
    }

    private companion object {
        const val PREFIX = "/v1/orders"
        const val CURSOR_PREFIX = "page-"
        const val PAGE_SIZE = 5
        const val SLOW_MILLIS = 3_000L
        const val POLL_MILLIS = 50L
        const val MARKER_HEADER = "X-Couli-Debug-Fake"
        const val BLACKLIST_ID = "ord-3768888888888888888"
        const val TZ = "+08:00"

        fun at(value: String): String = "2026-$value:00$TZ"

        /** 自购（sample-data §12.2 自1～自15，按付款时间倒序；预售按付定金时间）。 */
        val SELF: List<Sample> =
            listOf(
                Sample(
                    "ord-3741234567890123456",
                    "3741234567890123456",
                    null,
                    "taobao",
                    "样例商品 A：纯牛奶 250ml×12 盒",
                    true,
                    4560,
                    2,
                    "PAID",
                    234,
                    at("10-12T09:05"),
                    productKey = "tb:sampleA",
                ),
                Sample(
                    "ord-3798765432109876543",
                    "3798765432109876543",
                    null,
                    "taobao",
                    "样例商品 B：预售电饭煲 4L",
                    false,
                    3000,
                    1,
                    "DEPOSIT_PAID",
                    null,
                    null,
                    depositAt = at("10-01T20:00"),
                    productKey = "tb:sampleB",
                ),
                Sample(
                    "ord-309999999999",
                    "309999999999",
                    null,
                    "jd",
                    "样例商品 M：收纳盒 3 个装",
                    false,
                    1990,
                    1,
                    "FUTURE_STATUS",
                    88,
                    at("09-25T19:30"),
                    productKey = "jd:sampleM",
                ),
                Sample(
                    "ord-305678901234",
                    "305678901234",
                    null,
                    "jd",
                    "样例商品 C：洗衣液 3kg",
                    true,
                    3990,
                    1,
                    "WAITING",
                    450,
                    at("09-20T14:03"),
                    receivedAt = at("09-28T10:12"),
                    period = "2026-10",
                    productKey = "jd:sampleC",
                ),
                Sample(
                    "ord-3722222222222222222",
                    "3722222222222222222",
                    null,
                    "taobao",
                    "样例商品 J：蓝牙耳机",
                    false,
                    8900,
                    1,
                    "INVALID",
                    0,
                    at("09-18T21:40"),
                    endAt = at("09-22T11:05"),
                    reason = "REFUND",
                    productKey = "tb:sampleJ",
                ),
                Sample(
                    "ord-308765432101",
                    "308765432101",
                    null,
                    "jd",
                    "样例商品 N：定制衣柜",
                    false,
                    null,
                    1,
                    "PAID",
                    1796,
                    at("09-15T20:10"),
                    productKey = "jd:sampleN",
                ),
                Sample(
                    "ord-3766666666666666666",
                    "3766666666666666666",
                    null,
                    "taobao",
                    "样例商品 P：空气净化器",
                    true,
                    129900,
                    1,
                    "WAITING",
                    1580,
                    at("09-14T12:30"),
                    receivedAt = at("09-19T10:00"),
                    period = "2026-10",
                    productKey = "tb:sampleP",
                ),
                Sample(
                    "ord-260908-555566667777888",
                    "260908-555566667777888",
                    null,
                    "pdd",
                    "样例商品 Q：保鲜袋",
                    false,
                    2290,
                    1,
                    "WAITING",
                    340,
                    at("09-08T08:40"),
                    receivedAt = at("09-12T10:00"),
                    period = "2026-10",
                    productKey = "pdd:sampleQ",
                ),
                Sample(
                    "ord-304433221100",
                    "304433221100",
                    null,
                    "jd",
                    "样例商品 D：洗洁精",
                    false,
                    2590,
                    1,
                    "WAITING",
                    500,
                    at("09-06T22:15"),
                    receivedAt = at("09-10T10:00"),
                    period = "2026-10",
                    productKey = "jd:sampleD",
                ),
                Sample(
                    "ord-3755555555555555555",
                    "3755555555555555555",
                    null,
                    "taobao",
                    "样例商品 K：空气炸锅 4L",
                    false,
                    9900,
                    1,
                    "RIGHTS_PENDING",
                    456,
                    at("09-03T19:00"),
                    receivedAt = at("09-08T16:20"),
                    productKey = "tb:sampleK",
                ),
                Sample(
                    "ord-3744444444444444444",
                    "3744444444444444444",
                    null,
                    "taobao",
                    "样例商品 G2：保温饭盒",
                    false,
                    6900,
                    1,
                    "CREDITED",
                    600,
                    at("08-20T10:00"),
                    receivedAt = at("08-25T10:00"),
                    period = "2026-09",
                    creditedAt = at("10-12T09:05"),
                    actual = 600,
                    productKey = "tb:sampleG2",
                ),
                Sample(
                    "ord-3733333333333333333",
                    "3733333333333333333",
                    null,
                    "taobao",
                    "样例商品 L：床上四件套",
                    false,
                    15900,
                    1,
                    "CREDITED_PART_CLAWED",
                    540,
                    at("08-12T21:00"),
                    receivedAt = at("08-18T09:40"),
                    period = "2026-09",
                    creditedAt = at("09-25T10:00"),
                    endAt = at("10-09T14:30"),
                    actual = 140,
                    clawback = 400,
                    reason = "PART_REFUND",
                    productKey = "tb:sampleL",
                ),
                Sample(
                    "ord-3711111111111111111",
                    "3711111111111111111",
                    null,
                    "taobao",
                    "样例商品 H：电动牙刷",
                    false,
                    12900,
                    1,
                    "CREDITED",
                    450,
                    at("08-03T20:15"),
                    receivedAt = at("08-09T09:30"),
                    period = "2026-09",
                    creditedAt = at("09-25T10:00"),
                    actual = 320,
                    reason = "PRICE_COMPARE",
                    productKey = "tb:sampleH",
                ),
                Sample(
                    "ord-260803-123456789012345",
                    "260803-123456789012345",
                    null,
                    "pdd",
                    "样例商品 F2：抽纸 24 包",
                    false,
                    1234,
                    1,
                    "CREDITED",
                    56,
                    at("08-03T16:20"),
                    receivedAt = at("08-07T10:00"),
                    creditedAt = at("09-05T10:00"),
                    actual = 56,
                    productKey = "pdd:sampleF2",
                ),
                Sample(
                    "ord-302345678901",
                    "302345678901",
                    null,
                    "jd",
                    "样例商品 E2：毛巾 3 条装",
                    false,
                    2990,
                    1,
                    "CREDITED",
                    67,
                    at("07-26T11:00"),
                    receivedAt = at("07-30T10:00"),
                    creditedAt = at("09-05T10:00"),
                    actual = 67,
                    productKey = "jd:sampleE2",
                ),
            )

        /** 分享（sample-data §12.2 分1～分5；单号只有服务端脱敏的 masked_order_no）。 */
        val SHARE: List<Sample> =
            listOf(
                Sample(
                    "ord-s1",
                    null,
                    "3712****67",
                    "taobao",
                    "样例商品 E：保温杯 500ml",
                    true,
                    5900,
                    1,
                    "PAID",
                    180,
                    at("10-12T10:21"),
                    productKey = "tb:sampleE",
                ),
                Sample(
                    "ord-s2",
                    null,
                    "3756****21",
                    "taobao",
                    null,
                    false,
                    2680,
                    1,
                    "WAITING",
                    60,
                    at("10-02T18:30"),
                    receivedAt = at("10-08T15:12"),
                    period = "2026-11",
                    otherProduct = true,
                ),
                Sample(
                    "ord-s3",
                    null,
                    "3056****34",
                    "jd",
                    "样例商品 F：数据线 2 条装",
                    true,
                    1990,
                    1,
                    "CREDITED",
                    35,
                    at("09-18T09:40"),
                    receivedAt = at("09-22T10:00"),
                    creditedAt = at("10-08T10:00"),
                    actual = 35,
                    productKey = "jd:sampleF",
                ),
                Sample(
                    "ord-s4",
                    null,
                    "2510****45",
                    "pdd",
                    "样例商品 G：衣架 20 个装",
                    false,
                    1580,
                    1,
                    "CREDITED",
                    28,
                    at("09-12T21:05"),
                    receivedAt = at("09-16T10:00"),
                    creditedAt = at("10-08T10:00"),
                    actual = 28,
                    productKey = "pdd:sampleG",
                ),
                Sample(
                    "ord-s5",
                    null,
                    "3790****88",
                    "taobao",
                    "样例商品 S2：蓝牙音响",
                    false,
                    15900,
                    1,
                    "CREDITED",
                    187,
                    at("09-05T13:20"),
                    receivedAt = at("09-10T10:00"),
                    creditedAt = at("10-08T10:00"),
                    actual = 187,
                    productKey = "tb:sampleS2",
                ),
            )

        /** 补稿订单（sample-data §12.3）：不进列表，按完整单号能搜到。 */
        val EXTRA: List<Sample> =
            listOf(
                Sample(
                    "ord-3767777777777777777",
                    "3767777777777777777",
                    null,
                    "taobao",
                    "样例商品 S：电热水壶 1.7L",
                    false,
                    8900,
                    1,
                    "REVIEWING",
                    310,
                    at("09-10T20:30"),
                    receivedAt = at("09-15T11:02"),
                    productKey = "tb:sampleS",
                ),
                Sample(
                    "ord-306666666666",
                    "306666666666",
                    null,
                    "jd",
                    "样例商品 T：蓝牙音箱",
                    false,
                    19900,
                    1,
                    "CLAWED_BACK",
                    800,
                    at("08-20T13:10"),
                    receivedAt = at("08-24T10:05"),
                    period = "2026-09",
                    creditedAt = at("09-25T10:00"),
                    endAt = at("10-10T14:00"),
                    clawback = 800,
                    reason = "REFUND",
                    productKey = "jd:sampleT",
                ),
                Sample(
                    "ord-260920-111122223333444",
                    "260920-111122223333444",
                    null,
                    "pdd",
                    "样例商品 U：手机支架",
                    false,
                    3990,
                    1,
                    "NO_REBATE",
                    0,
                    at("09-20T21:15"),
                    receivedAt = at("09-24T10:30"),
                    priceCompare = true,
                    productKey = "pdd:sampleU",
                ),
                Sample(
                    BLACKLIST_ID,
                    "3768888888888888888",
                    null,
                    "taobao",
                    "样例商品 R：电动剃须刀",
                    false,
                    12900,
                    1,
                    "INVALID",
                    0,
                    at("10-02T20:30"),
                    endAt = at("10-06T09:30"),
                    reason = "BLACKLIST",
                    actions = listOf("APPEAL"),
                    productKey = "tb:sampleR",
                ),
            )
    }
}

/** 订单假服务的状态开关文件名，也是 release-scan 查的标记（Release 制品里不得出现）。 */
internal const val FAKE_ORDERS_SERVER_MARKER = "COULI_FAKE_ORDERS_SERVER_APP08_3e8d52"
internal const val FAKE_ORDERS_STATE_FILE = "couli_fake_orders_state.txt"
