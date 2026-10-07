package com.couli.core.bridge

import java.math.BigDecimal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * 桥契约用到的 JSON Schema 子集（contracts/bridge.schema.json 的 params）：type（单值或数组）、enum、$ref（#/$defs/…）、
 * minLength / maxLength（按 Unicode 码点）、pattern（不锚定，按 JSON Schema 语义 find）、minimum / maximum、
 * properties / required / additionalProperties（false、true 或 schema）、items / minItems / maxItems、oneOf（恰好一个）。
 * format、description 等注解不参与校验。契约里出现本子集以外的关键字时一律判不合法（保守，不放行）。
 */
@Suppress("ReturnCount", "ComplexCondition") // 逐个关键字校验，不合法即返回
internal class JsonSchemaLite(
    private val defs: JsonObject,
) {
    fun valid(
        value: JsonElement,
        schema: JsonObject,
    ): Boolean {
        val resolved = resolve(schema) ?: return false
        if (resolved.keys.any { it !in KNOWN_KEYWORDS }) return false
        return CHECKS.all { check -> check(this, value, resolved) }
    }

    private fun resolve(schema: JsonObject): JsonObject? {
        val ref = schema.string("\$ref") ?: return schema
        val name = ref.removePrefix(DEFS_PREFIX).takeIf { ref.startsWith(DEFS_PREFIX) } ?: return null
        return (defs[name] as? JsonObject)?.let(::resolve)
    }

    private fun typeOk(
        value: JsonElement,
        schema: JsonObject,
    ): Boolean {
        val declared =
            when (val type = schema["type"]) {
                null -> return true
                is JsonPrimitive -> listOf(type.content)
                is JsonArray -> type.mapNotNull { (it as? JsonPrimitive)?.content }
                else -> return false
            }
        return declared.any { matchesType(value, it) }
    }

    private fun enumOk(
        value: JsonElement,
        schema: JsonObject,
    ): Boolean {
        val options = schema["enum"] as? JsonArray ?: return schema["enum"] == null
        return options.any { sameValue(it, value) }
    }

    private fun stringOk(
        value: JsonElement,
        schema: JsonObject,
    ): Boolean {
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return true
        val length = text.codePointCount(0, text.length)
        val min = schema.int("minLength")
        val max = schema.int("maxLength")
        val pattern = schema.string("pattern")
        return (min == null || length >= min) &&
            (max == null || length <= max) &&
            (pattern == null || patternMatches(pattern, text))
    }

    private fun numberOk(
        value: JsonElement,
        schema: JsonObject,
    ): Boolean {
        val number = numeric(value) ?: return true
        val min = schema.decimal("minimum")
        val max = schema.decimal("maximum")
        return (min == null || number >= min) && (max == null || number <= max)
    }

    private fun objectOk(
        value: JsonElement,
        schema: JsonObject,
    ): Boolean {
        val obj = value as? JsonObject ?: return true
        val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val required = (schema["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        if (required.any { it !in obj }) return false
        return obj.all { (key, child) ->
            val propertySchema = properties[key] as? JsonObject
            when {
                propertySchema != null -> valid(child, propertySchema)
                else -> additionalOk(child, schema["additionalProperties"])
            }
        }
    }

    private fun additionalOk(
        child: JsonElement,
        additional: JsonElement?,
    ): Boolean =
        when (additional) {
            null -> true
            is JsonObject -> valid(child, additional)
            is JsonPrimitive -> additional.booleanOrNull == true
            else -> false
        }

    private fun arrayOk(
        value: JsonElement,
        schema: JsonObject,
    ): Boolean {
        val array = value as? JsonArray ?: return true
        val min = schema.int("minItems")
        val max = schema.int("maxItems")
        val items = schema["items"] as? JsonObject
        return (min == null || array.size >= min) &&
            (max == null || array.size <= max) &&
            (items == null || array.all { valid(it, items) })
    }

    private fun oneOfOk(
        value: JsonElement,
        schema: JsonObject,
    ): Boolean {
        val options = schema["oneOf"] as? JsonArray ?: return schema["oneOf"] == null
        return options.count { option -> option is JsonObject && valid(value, option) } == 1
    }

    private companion object {
        const val DEFS_PREFIX = "#/\$defs/"

        /** 校验关键字之外只允许这些注解关键字。 */
        val KNOWN_KEYWORDS =
            setOf(
                "\$ref",
                "type",
                "enum",
                "minLength",
                "maxLength",
                "pattern",
                "minimum",
                "maximum",
                "properties",
                "required",
                "additionalProperties",
                "items",
                "minItems",
                "maxItems",
                "oneOf",
                "format",
                "description",
                "\$comment",
            )

        val CHECKS: List<(JsonSchemaLite, JsonElement, JsonObject) -> Boolean> =
            listOf(
                { s, v, o -> s.typeOk(v, o) },
                { s, v, o -> s.enumOk(v, o) },
                { s, v, o -> s.stringOk(v, o) },
                { s, v, o -> s.numberOk(v, o) },
                { s, v, o -> s.objectOk(v, o) },
                { s, v, o -> s.arrayOk(v, o) },
                { s, v, o -> s.oneOfOk(v, o) },
            )

        fun matchesType(
            value: JsonElement,
            type: String,
        ): Boolean =
            when (type) {
                "object" -> value is JsonObject
                "array" -> value is JsonArray
                "null" -> value is JsonNull
                "string" -> value is JsonPrimitive && value.isString
                "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
                "number" -> numeric(value) != null
                "integer" -> numeric(value)?.let(::isInteger) == true
                else -> false
            }

        /** 非字符串、非布尔、非 null 的数值字面量。 */
        fun numeric(value: JsonElement): BigDecimal? {
            val primitive = value as? JsonPrimitive ?: return null
            if (primitive.isString || primitive is JsonNull || primitive.booleanOrNull != null) return null
            return primitive.content.toBigDecimalOrNull()
        }

        fun isInteger(number: BigDecimal): Boolean = number.signum() == 0 || number.stripTrailingZeros().scale() <= 0

        fun sameValue(
            a: JsonElement,
            b: JsonElement,
        ): Boolean {
            if (a is JsonPrimitive && b is JsonPrimitive && !a.isString && !b.isString) {
                val x = numeric(a)
                val y = numeric(b)
                if (x != null && y != null) return x.compareTo(y) == 0
            }
            return a == b
        }

        fun patternMatches(
            pattern: String,
            text: String,
        ): Boolean = runCatching { Regex(pattern).containsMatchIn(text) }.getOrDefault(false)

        fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

        fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.let(::numeric)?.toInt()

        fun JsonObject.decimal(key: String): BigDecimal? = (get(key) as? JsonPrimitive)?.let(::numeric)
    }
}
