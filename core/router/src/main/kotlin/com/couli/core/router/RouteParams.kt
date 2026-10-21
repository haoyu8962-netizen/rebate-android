package com.couli.core.router

import com.couli.generated.RouteParamSpec
import com.couli.generated.RouteParamType
import com.couli.generated.RouteSpec
import java.math.BigDecimal
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 按 routes.json 的 params schema 校验参数（生成物 [RouteSpec.params]；schema 一律 additionalProperties=false）。
 * 只做契约里出现的约束：type（string / object / integer）、required、enum、minLength、maxLength（按 Unicode 码点计，同 JSON Schema）、
 * pattern（ECMA 正则，按「在字符串中找到匹配」判定，同 JSON Schema）、minimum（integer 的下限，含；CT-LOCK-02 Agreement.version）。
 * integer 同 JSON Schema：JSON 数值且小数部分为 0（2.0 算整数）；字符串 "3"、布尔、null 都不是——深链查询串的值一律是字符串，
 * 所以 integer 参数照旧不能从深链进入。
 */
object RouteParams {
    /** 资金与实名四页：传入参数一律丢弃后照常打开（03 §4.4「资金与实名页面」）。 */
    val dropAllParamsRoutes: Set<String> = setOf("Withdraw", "PayoutAccount", "LaborAgreement", "RealName")

    /**
     * 参数一律丢弃、照常打开，但**不记** route_params_dropped 的路由（APP-13a G21：DeleteAccount 的 params 是空对象，events.json 的 route 枚举里没有它，
     * 不新增值）。页面不读任何参数。
     */
    val silentDropRoutes: Set<String> = setOf("DeleteAccount")

    fun isValid(
        spec: RouteSpec,
        params: JsonObject,
    ): Boolean {
        val declared = spec.params.associateBy { it.name }
        val noExtra = params.keys.all { it in declared }
        val requiredPresent = spec.params.filter { it.required }.all { it.name in params }
        return noExtra && requiredPresent && params.all { (key, value) -> valueMatches(declared.getValue(key), value) }
    }

    private fun valueMatches(
        param: RouteParamSpec,
        value: JsonElement,
    ): Boolean =
        when (param.type) {
            RouteParamType.OBJECT -> value is JsonObject
            RouteParamType.STRING -> value is JsonPrimitive && value.isString && stringMatches(param, value.content)
            RouteParamType.INTEGER -> integerMatches(param, value)
        }

    private fun integerMatches(
        param: RouteParamSpec,
        value: JsonElement,
    ): Boolean {
        // 复审 r1：解析、去尾零、比较下限都不许抛异常（如 100e2147483647 去尾零时 scale 溢出、1e-2147483648 解析时 scale 溢出），
        // 出错一律按不合法；scale <= 0 已是整数，直接短路，不做去尾零。
        val number =
            (value as? JsonPrimitive)
                ?.takeUnless { it.isString || it is JsonNull }
                ?.let { runCatching { BigDecimal(it.content) }.getOrNull() }
                ?: return false
        val whole = number.scale() <= 0 || runCatching { number.stripTrailingZeros().scale() <= 0 }.getOrDefault(false)
        val minOk = param.minimum?.let { min -> runCatching { number >= BigDecimal.valueOf(min) }.getOrDefault(false) } ?: true
        return whole && minOk
    }

    private fun stringMatches(
        param: RouteParamSpec,
        value: String,
    ): Boolean {
        val length = value.codePointCount(0, value.length)
        val enumOk = param.enumValues?.contains(value) ?: true
        val minOk = param.minLength?.let { length >= it } ?: true
        val maxOk = param.maxLength?.let { length <= it } ?: true
        val patternOk = param.pattern?.let { Regex(it).containsMatchIn(value) } ?: true
        return enumOk && minOk && maxOk && patternOk
    }
}
