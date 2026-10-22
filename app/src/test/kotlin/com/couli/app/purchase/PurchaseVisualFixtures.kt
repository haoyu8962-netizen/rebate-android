package com.couli.app.purchase

import com.couli.app.product.ProductVisualFixtures
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** 只读新增 visualCards.purchase；原判定向量及 a 的卡片逐项保留。 */
internal object PurchaseVisualFixtures {
    private val data =
        Json
            .parseToJsonElement(File(ProductVisualFixtures.root, "specs/search-product-vectors.json").readText())
            .jsonObject
            .getValue("visualCards")
            .jsonObject
            .getValue("purchase")
            .jsonObject
    val auth = data.getValue("auth").jsonObject
    val pending = data.getValue("pending").jsonObject

    fun response(name: String = "unchanged"): JsonObject =
        data
            .getValue("responses")
            .jsonObject
            .getValue(name)
            .jsonObject

    fun plan(
        type: String,
        value: String = "fixture-shop://item",
    ): JsonObject =
        Json
            .parseToJsonElement(
                """{"primary":{"type":"$type","value":"$value"},"fallbacks":[],"expire_at":"2026-10-22T11:00:00+08:00"}""",
            ).jsonObject

    fun jump(
        type: String,
        value: String = "fixture-shop://item",
    ): JsonObject = JsonObject(response() + ("jump" to plan(type, value)))

    fun offShelfError(): JsonObject = JsonObject(mapOf("availability" to JsonPrimitive("off_shelf")))
}
