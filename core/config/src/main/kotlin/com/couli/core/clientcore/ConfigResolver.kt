package com.couli.core.clientcore

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 配置取值顺序（03 §4.3；BR-PROD-10 细则「按平台的搜索开关」；BR-ATTR-29）。纯函数，三端同一规则。
 * 三层都是 /v1/config 的 data 对象（JSON 文本）：[current] 本进程最近一次成功的响应，[lkg] 上一次成功的响应（文件），
 * [bundled] 包内 default_config.json。不能解析或不是对象的层按缺失处理。
 *
 * - [resolve]：顶层键按 当前 → LKG → 包内 取第一个有值的（JSON null 按缺失）。
 * - [searchStatus]：逐个平台取第一个带该平台取值的层，明确的 off 有效，缺字段、缺平台不当作 off、继续回落。
 *   TODO(N-21): 三层都缺、取值为 null 或不认识时结果未定（rule-gaps 第 4 条）：这里按缺失回落，都没有返回 null，由调用方决定。
 * - [linkPatterns]：带版本的完整链接形态表（version 为非空字符串、rules 为数组）按 当前 → 上一次成功 → 包内 取用；
 *   明确的空规则数组是完整的表，不等于缺失。TODO(N-21): 损坏、同版本异内容、版本回退的处理未定，按「不完整即跳过」。
 */
object ConfigResolver {
    private val SEARCH_VALUES = setOf("on", "off")

    fun resolve(
        current: String?,
        lkg: String?,
        bundled: String,
        key: String,
    ): String? =
        layers(current, lkg, bundled)
            .firstNotNullOfOrNull { layer -> layer[key]?.takeIf { it != JsonNull } }
            ?.toString()

    fun searchStatus(
        current: String?,
        lkg: String?,
        bundled: String,
        platformKey: String,
    ): String? = layers(current, lkg, bundled).firstNotNullOfOrNull { searchStatusIn(it, platformKey) }

    fun linkPatterns(
        current: String?,
        lkg: String?,
        bundled: String,
    ): String? = layers(current, lkg, bundled).firstNotNullOfOrNull(::linkPatternsIn)?.toString()

    /** 一层里的 features.search_status.<platform>（只认 on / off）。 */
    @Suppress("ReturnCount") // 逐层缺失即 null
    fun searchStatusIn(
        layer: JsonObject,
        platformKey: String,
    ): String? {
        val features = layer["features"] as? JsonObject ?: return null
        val status = features["search_status"] as? JsonObject ?: return null
        val value = (status[platformKey] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return value.takeIf { it in SEARCH_VALUES }
    }

    /** 一层里完整的 link_patterns（version 为非空字符串、rules 为数组），否则 null。 */
    @Suppress("ReturnCount") // 缺失或不完整即 null
    fun linkPatternsIn(layer: JsonObject): JsonObject? {
        val patterns = layer["link_patterns"] as? JsonObject ?: return null
        val version = (patterns["version"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (version.isNullOrEmpty() || patterns["rules"] !is JsonArray) return null
        return patterns
    }

    /** JSON 文本 → 对象；不能解析或不是对象为 null。 */
    fun parseObject(text: String?): JsonObject? = text?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() as? JsonObject }

    private fun layers(
        current: String?,
        lkg: String?,
        bundled: String,
    ): List<JsonObject> = listOfNotNull(parseObject(current), parseObject(lkg), parseObject(bundled))
}
