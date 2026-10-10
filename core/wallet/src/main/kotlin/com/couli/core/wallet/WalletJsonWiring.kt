package com.couli.core.wallet

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/*
 * 三端向量测试的 JSON 接线（specs/wallet-vectors.json）：判定都在强类型的入口里，这里只把 JSON 转成强类型、把结论转回 JSON，不做业务判定。
 */

internal fun jsonObj(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(linkedMapOf(*pairs))

internal fun Long?.json(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull

internal fun String?.json(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull

internal fun Boolean.json(): JsonElement = JsonPrimitive(this)

internal fun JsonObject.textOrNull(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.longOrNullValue(key: String): Long? = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

internal fun JsonObject.boolOrNull(key: String): Boolean? = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

internal fun List<Long>.json(): JsonArray = JsonArray(map(::JsonPrimitive))

/** 一行文案 → {key, values}（金额变量是整数分，字符串变量原样）；没有变量时不带 values。 */
internal fun TextLine.json(): JsonObject {
    val values: Map<String, JsonElement> = strings.mapValues { JsonPrimitive(it.value) } + amounts.mapValues { JsonPrimitive(it.value) }
    return if (values.isEmpty()) {
        jsonObj("key" to JsonPrimitive(key))
    } else {
        jsonObj(
            "key" to JsonPrimitive(key),
            "values" to JsonObject(values),
        )
    }
}
