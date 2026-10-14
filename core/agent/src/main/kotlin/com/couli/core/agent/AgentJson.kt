package com.couli.core.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * APP-10 Agent：JSON 取值与按 agent-stream.schema.json 校验用的小工具（不抛出）。只做 schema 里写明的类型、必填、多键、取值范围、正则与长度检查；
 * 不猜测、不补值（G07–G09）。三端同一套判定（鸿蒙 AgentJson.ets）。
 */
@Suppress("TooManyFunctions") // 每种 schema 类型一个判定
object AgentJson {
    val PLATFORMS: List<String> = listOf("taobao", "jd", "pdd", "meituan", "vip", "douyin", "eleme", "kuaishou", "suning")

    private const val ENTITY_MAX = 64
    private const val INT32_MAX = 2_147_483_647L
    private const val INT32_MIN = -2_147_483_648L
    private val TIMESTAMP = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,6})?\\+08:00$")
    private val TEXT_KEY = Regex("^[a-z][a-z0-9_]*(\\.[A-Za-z0-9_]+)*$")
    private val ROUTE_NAME = Regex("^[A-Z][A-Za-z0-9]*$")
    private val CARD_ID = Regex("^c[1-9][0-9]*$")
    private val YEAR_MONTH = Regex("^[0-9]{4}-(0[1-9]|1[0-2])$")
    private val INTEGER = Regex("^-?(0|[1-9][0-9]*)$")
    private val LENIENT = Json { isLenient = false }

    val EMPTY: JsonObject = JsonObject(emptyMap())

    fun objectOf(v: JsonElement?): JsonObject? = v as? JsonObject

    fun arrayOf(v: JsonElement?): JsonArray? = v as? JsonArray

    fun stringOf(v: JsonElement?): String? = (v as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun boolOf(v: JsonElement?): Boolean? = (v as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.booleanOrNull

    /** 整数（JSON 数字字面量且没有小数、指数）；字符串、null、小数为 null。 */
    @Suppress("ReturnCount") // 不是数字、字符串、小数依次返回
    fun intOf(v: JsonElement?): Long? {
        val p = v as? JsonPrimitive ?: return null
        if (p.isString || p is JsonNull) return null
        return p.content.takeIf { INTEGER.matches(it) }?.toLongOrNull()
    }

    fun isNull(v: JsonElement?): Boolean = v is JsonNull

    /** JSON 文本 → 值；不合法为 null。 */
    fun parse(text: String): JsonElement? = runCatching { LENIENT.parseToJsonElement(text) }.getOrNull()

    /** 对象的键恰好在 required + optional 里、且 required 全有（additionalProperties:false + required）。 */
    fun exactKeys(
        o: JsonObject,
        required: List<String>,
        optional: List<String> = emptyList(),
    ): Boolean = o.keys.all { it in required || it in optional } && required.all { it in o.keys }

    fun has(
        o: JsonObject,
        key: String,
    ): Boolean = key in o.keys

    /** Unicode 码点数（JSON Schema 的 minLength / maxLength 口径；BR-AI-23 ⑤ 的 500 字同口径）。 */
    fun codePoints(s: String): Int = s.codePointCount(0, s.length)

    fun str(
        v: JsonElement?,
        min: Int = 0,
        max: Int = -1,
    ): Boolean {
        val s = stringOf(v) ?: return false
        val n = codePoints(s)
        return n >= min && (max < 0 || n <= max)
    }

    fun strOrNull(
        v: JsonElement?,
        min: Int = 0,
        max: Int = -1,
    ): Boolean = isNull(v) || str(v, min, max)

    fun int(
        v: JsonElement?,
        min: Long? = null,
        max: Long? = null,
    ): Boolean {
        val n = intOf(v) ?: return false
        return (min == null || n >= min) && (max == null || n <= max)
    }

    fun int32(
        v: JsonElement?,
        min: Long? = null,
        max: Long? = null,
    ): Boolean = int(v, min ?: INT32_MIN, max ?: INT32_MAX)

    fun intOrNull(v: JsonElement?): Boolean = isNull(v) || int(v)

    fun bool(v: JsonElement?): Boolean = boolOf(v) != null

    fun oneOf(
        v: JsonElement?,
        values: List<String>,
    ): Boolean = stringOf(v)?.let { it in values } ?: false

    fun entityId(v: JsonElement?): Boolean = str(v, 1, ENTITY_MAX)

    fun seq(v: JsonElement?): Boolean = int(v, 1)

    fun cardId(v: JsonElement?): Boolean = stringOf(v)?.let(CARD_ID::matches) ?: false

    fun timestamp(v: JsonElement?): Boolean = stringOf(v)?.let(TIMESTAMP::matches) ?: false

    fun textKey(v: JsonElement?): Boolean = stringOf(v)?.let(TEXT_KEY::matches) ?: false

    fun routeName(v: JsonElement?): Boolean = stringOf(v)?.let(ROUTE_NAME::matches) ?: false

    fun platform(v: JsonElement?): Boolean = oneOf(v, PLATFORMS)

    fun yearMonthOrNull(v: JsonElement?): Boolean = isNull(v) || (stringOf(v)?.let(YEAR_MONTH::matches) ?: false)

    /** 每项都满足 [check] 的数组（长度范围可选）。 */
    @Suppress("ReturnCount") // 不是数组、长度、逐项
    fun arrayAll(
        v: JsonElement?,
        min: Int = 0,
        max: Int = -1,
        check: (JsonElement) -> Boolean,
    ): Boolean {
        val a = arrayOf(v) ?: return false
        if (a.size < min || (max >= 0 && a.size > max)) return false
        return a.all(check)
    }

    /** 小工具：键值对 → JsonObject（null 值写 JsonNull）。 */
    fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to element(v) })

    fun element(v: Any?): JsonElement =
        when (v) {
            null -> JsonNull
            is JsonElement -> v
            is String -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            is List<*> -> JsonArray(v.map(::element))
            else -> JsonPrimitive(v.toString())
        }
}
