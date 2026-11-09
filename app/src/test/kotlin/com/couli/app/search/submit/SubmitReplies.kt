package com.couli.app.search.submit

import android.net.Uri
import com.couli.core.network.ApiResult
import com.couli.core.network.ErrorEffect
import com.couli.core.network.ErrorEffectKind
import com.couli.core.network.ReplayMode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** v5 用例共用的识别响应与观察（只是数据与读取，不含识别或回落逻辑）。 */
internal object SubmitReplies {
    const val LINK = "https://item.taobao.com/item.htm?id=123456"
    const val SECOND_LINK = "https://item.jd.com/100200.html"
    const val TITLE = "示例纯牛奶整箱"
    const val TITLED_LINK = "「$TITLE」$LINK"
    const val WORD = "全脂纯牛奶"
    const val FIRST_KEY = "tb:7Kq9LmN3pQ"
    const val SECOND_KEY = "jd:100200"
    const val UNSUPPORTED = 30131
    const val RATE_LIMITED = 42901
    const val SERVER_ERROR = 50001

    /** 一张识别卡（带 item_ref 与 link_id，单卡直达时作为 ProductSeeds 的种子）。 */
    fun card(
        key: String = FIRST_KEY,
        platform: String = "taobao",
        raw: String = LINK,
    ): String =
        """
        {
          "hit":{"platform":"$platform","kind":"url","raw":"$raw"},
          "card":{
            "platform":"$platform","product_key":"$key","item_ref":"v1.ref.$platform","title":"示例商品 $key",
            "availability":"ok","rebate_basis":"normal",
            "link_id":"0199a3b4-5c6d-7e8f-9a0b-1c2d3e4f5a70",
            "price_fen":3990,"coupon_fen":1000,"final_price_fen":2990,
            "rebate_min_fen":269,"rebate_max_fen":269,"est_net_price_fen":2721,
            "disclaimer_keys":["price_basis"],"cta":{"text_key":"btn.buy.coupon"}
          }
        }
        """.trimIndent()

    fun cards(vararg card: String): ApiResult =
        ApiResult.Success(SearchSubmitFixture.json("""{"results":[${card.joinToString(",")}]}"""), "parse-submit-000123", null)

    fun single(
        key: String = FIRST_KEY,
        platform: String = "taobao",
        raw: String = LINK,
    ): ApiResult = cards(card(key, platform, raw))

    /** [card] 里的 card 对象（ProductSeeds 应存的那一张）。 */
    fun cardObject(
        key: String = FIRST_KEY,
        platform: String = "taobao",
        raw: String = LINK,
    ): JsonObject = checkNotNull(SearchSubmitFixture.json(card(key, platform, raw))["card"]).jsonObject

    fun detailParams(
        key: String = FIRST_KEY,
        platform: String = "taobao",
    ): JsonObject =
        JsonObject(
            mapOf(
                "platform" to JsonPrimitive(platform),
                "product_key" to JsonPrimitive(key),
                "item_ref" to JsonPrimitive("v1.ref.$platform"),
            ),
        )

    fun failure(code: Int): ApiResult =
        ApiResult.Failure(
            code,
            "",
            JsonObject(emptyMap()),
            null,
            ErrorEffect(code, ErrorEffectKind.GENERIC_ERROR, ReplayMode.NONE, registerPending = false),
            null,
        )

    fun transport(): ApiResult = ApiResult.TransportFailure("disconnect", null, null, pendingKeyUnknown = false)

    fun operations(f: SearchSubmitFixture): List<String> = f.api.calls.map { it.operationId }

    /** 已发出的关键词请求的 q（按发出顺序）。 */
    fun keywords(f: SearchSubmitFixture): List<String?> =
        f.api.calls
            .filter { it.operationId == "searchProducts" }
            .map { Uri.parse("https://fixture.invalid/?${it.query}").getQueryParameter("q") }

    /** 本进程搜索历史（App 共用的一份与本页 ViewModel 的那份）。 */
    fun history(f: SearchSubmitFixture): Pair<List<String>, List<String>> = f.pageConfig.history.items to f.model.history.value

    /** 单卡直达写下的商品种子：以一个新详情条目认领（与详情页同一入口）；没有写为 null。 */
    fun claimSeed(
        f: SearchSubmitFixture,
        key: String,
        entry: String = "seed-probe-$key",
    ): JsonObject? = f.clipboard.seeds.claim(entry, key)
}
