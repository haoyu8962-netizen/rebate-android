package com.couli.feature.home

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** r1 压力输入只存在测试内存中；不修改 goldenPages 或判定向量。 */
internal object HomeReviewFixtures {
    fun product(
        section: String,
        noRebate: Boolean = false,
        longAmounts: Boolean = false,
    ): JsonObject {
        val page = HomeTestKit.golden("main_v3")
        val sources = page.getValue("sectionItems").jsonObject
        val source = sources.getValue(section).jsonObject
        val first =
            source
                .getValue("items")
                .jsonArray
                .first()
                .jsonObject
        val patch =
            when {
                noRebate -> {
                    mapOf(
                        "rebate_basis" to JsonPrimitive("no_rebate"),
                        "rebate_min_fen" to JsonNull,
                        "rebate_max_fen" to JsonNull,
                        "est_net_price_fen" to JsonNull,
                    )
                }

                longAmounts -> {
                    mapOf(
                        "price_fen" to JsonPrimitive(19_999_998L),
                        "coupon_fen" to JsonPrimitive(9_999_999L),
                        "final_price_fen" to JsonPrimitive(9_999_999L),
                        "rebate_basis" to JsonPrimitive("price_compare_risk"),
                        "rebate_min_fen" to JsonPrimitive(8_888_888L),
                        "rebate_max_fen" to JsonPrimitive(9_999_999L),
                    )
                }

                else -> {
                    emptyMap()
                }
            }
        val items = JsonArray(listOf(JsonObject(first + patch)))
        return JsonObject(page + ("sectionItems" to JsonObject(sources + (section to JsonObject(source + ("items" to items))))))
    }

    fun firstProduct(controller: HomeController): HomeProductUi =
        when (
            val card =
                controller.state.value.cards
                    .single()
        ) {
            is HomeCardUi.ProductScroll -> card.items.first()
            is HomeCardUi.ProductGrid -> card.items.first()
            is HomeCardUi.Feed -> card.items.first()
            else -> error("应加载单件商品")
        }
}
