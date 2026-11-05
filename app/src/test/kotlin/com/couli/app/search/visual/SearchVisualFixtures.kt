package com.couli.app.search.visual

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Q19：只读新 fixture，不追加 APP-06a / APP-06v 已冻结向量。 */
internal object SearchVisualFixtures {
    val root: File =
        generateSequence(File(System.getProperty("user.dir") ?: ".").canonicalFile) { it.parentFile }
            .first { File(it, "contract.lock").isFile }
    val data = Json.parseToJsonElement(File(root, "specs/search-visual-vectors.json").readText()).jsonObject
    val now =
        data
            .getValue("clock")
            .jsonObject
            .getValue("nowMillis")
            .jsonPrimitive.long
    val history = data.getValue("history").jsonArray.map { it.jsonPrimitive.content }

    fun card(name: String): JsonObject =
        data
            .getValue("cards")
            .jsonObject
            .getValue(name)
            .jsonObject

    fun cards(page: String): List<JsonObject> =
        data
            .getValue("pages")
            .jsonObject
            .getValue(page)
            .jsonArray
            .map { card(it.jsonPrimitive.content) }

    fun error(name: String): JsonObject =
        data
            .getValue("errors")
            .jsonObject
            .getValue(name)
            .jsonObject

    fun query(name: String): String =
        data
            .getValue("queries")
            .jsonObject
            .getValue(name)
            .jsonPrimitive.content

    fun page(
        items: List<JsonObject> = cards("normal"),
        fallback: List<JsonObject> = emptyList(),
        more: Boolean = false,
    ): JsonObject =
        JsonObject(
            mapOf(
                "items" to JsonArray(items),
                "fallback_items" to JsonArray(fallback),
                "has_more" to JsonPrimitive(more),
                "next_cursor" to if (more) JsonPrimitive("sample-page-2") else JsonNull,
            ),
        )
}
