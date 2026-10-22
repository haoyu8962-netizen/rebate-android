package com.couli.app.clipboard

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.couli.app.home.HomeVisualFixtures
import com.couli.core.designsystem.ProductImageLoader
import com.couli.core.network.ApiResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** APP-11v-b：只在测试内组数据。金额是 Long 分；不复制设计资产、不扩展接口报文。 */
internal object ClipboardVisualFixtures {
    const val LINK = "https://item.taobao.com/item.htm?id=110026"
    const val TITLE = "全脂纯牛奶 250ml × 24 盒 整箱装 早餐奶 学生奶 送礼礼盒款 生产日期新鲜"
    const val INVITE = "K7M3QX"
    const val AD = "品牌合作 · 原样"
    const val POPUP_URL = "https://img.example.test/visual-b-popup.png"
    val POPUP_COLOR = 0xff497ea1.toInt()

    val images = popupImages()

    fun popupImages(
        width: Int = 326,
        height: Int = 408,
    ): ProductImageLoader =
        ProductImageLoader { url ->
            if (url == POPUP_URL) {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(POPUP_COLOR) }.asImageBitmap()
            } else {
                HomeVisualFixtures.images.load(url)
            }
        }

    fun popupPage(action: String = """{"route":"Search"}"""): JsonObject {
        val page = HomeVisualFixtures.page("main_v3")
        val popup =
            json(
                """{"id":"visual-popup","type":"popup","version":1,"ad_label":"$AD",
                "props":{"image":"$POPUP_URL","daily_max":1,"time_window":["00:00","23:59"]},
                "data_source":{"kind":"static","items":[{"action":$action}]}}""",
            )
        return JsonObject(page + ("sections" to JsonArray(page.getValue("sections").jsonArray + popup)))
    }

    fun card(
        availability: String = "ok",
        key: String = "tb:visual-milk",
    ): JsonObject {
        val template =
            HomeVisualFixtures
                .page("main_v3")
                .getValue("sectionItems")
                .jsonObject
                .getValue("grid")
                .jsonObject
                .getValue("items")
                .jsonArray
                .first()
                .jsonObject
        return JsonObject(
            template +
                mapOf(
                    "product_key" to JsonPrimitive(key),
                    "title" to JsonPrimitive(TITLE),
                    "image" to JsonPrimitive("https://img.example.test/visual-milk.png"),
                    "price_fen" to JsonPrimitive(2345L),
                    "coupon_fen" to JsonPrimitive(1000L),
                    "final_price_fen" to JsonPrimitive(1345L),
                    "rebate_min_fen" to JsonPrimitive(56L),
                    "rebate_max_fen" to JsonPrimitive(112L),
                    "est_net_price_fen" to JsonPrimitive(1289L),
                    "availability" to JsonPrimitive(availability),
                    "cta" to json("""{"text_key":"btn.buy.coupon"}"""),
                    "benefit_tags" to JsonArray(emptyList()),
                    "ad_label" to JsonNull,
                ),
        )
    }

    fun cards(vararg cards: JsonObject): ApiResult =
        success(
            JsonArray(
                cards.map { card ->
                    JsonObject(mapOf("hit" to hit(), "card" to card))
                },
            ),
        )

    fun unrecognized(): ApiResult =
        success(
            JsonArray(
                listOf(
                    JsonObject(mapOf("hit" to hit("「全脂纯牛奶」 $LINK"), "error_code" to JsonPrimitive(30132))),
                ),
            ),
        )

    fun noRebate(): JsonObject =
        JsonObject(
            card() +
                mapOf(
                    "rebate_basis" to JsonPrimitive("no_rebate"),
                    "rebate_min_fen" to JsonNull,
                    "rebate_max_fen" to JsonNull,
                    "est_net_price_fen" to JsonNull,
                    "cta" to json("""{"text_key":"btn.buy.no_rebate"}"""),
                ),
        )

    private fun hit(raw: String = LINK): JsonObject =
        JsonObject(
            mapOf(
                "platform" to JsonPrimitive("taobao"),
                "kind" to JsonPrimitive("url"),
                "raw" to JsonPrimitive(raw),
            ),
        )

    private fun success(rows: JsonArray): ApiResult = ApiResult.Success(JsonObject(mapOf("results" to rows)), "visual-b-parse", null)

    private fun json(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject
}
