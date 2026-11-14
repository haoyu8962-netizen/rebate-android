// 摘要字段逐项校验、取不出即返回
@file:Suppress("ReturnCount")

package com.couli.app

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * debug / staging 假服务的二次验证契约规则（src/debugFeatures，Release 不编译）：假登录、假钱包、假账号三个服务共用，按 openapi 照做。
 * - 发码（sendSmsCode purpose=step_up，B1-02zzz）：不带 phone 也发（发往会话账号的绑定手机）；带了 phone（任何值）→ 20001 data.fields=[phone]；
 *   没登录 → 10001；没绑手机 → 20001 data.fields=[code]。
 * - 业务摘要（stepUp，CT-26c / BR-ID-08 细则「绑定业务摘要」）：payout_account_change 必须带 summary 且形状配对，否则 20001
 *   data.fields=[summary]；withdraw（D10-1666 / D10-1667 收窄后）、phone_change、account_deletion 带了 summary 都回 20001，不带照常。签发时把摘要
 *   写成一条 claim（与服务端同一写法：`p:<payout_method>:<规范化账号>`、`p:wechat:<attempt_id>`；假服务不算 HMAC，直接用规范化后的账号）。
 * - 保存收款账号按 claim 比对，不符 → 10003（不核销）。
 * 不连任何地址、不落盘、不打印号码与账号。
 */
internal object DebugFakeStepUp {
    const val SMS_PATH = "/v1/auth/sms-codes"
    const val FIELDS_PHONE = """{"fields":["phone"]}"""
    const val FIELDS_CODE = """{"fields":["code"]}"""
    const val FIELDS_SUMMARY = """{"fields":["summary"]}"""
    const val PAYOUT_ACCOUNT_CHANGE = "payout_account_change"

    private const val BAD_REQUEST = 20001
    private const val LOGIN_REQUIRED = 10001

    /** 发码的 step_up 前置检查：不通过给 (code, data)，通过（或不是 step_up 发码）为 null，由原来的发码场景继续回答。 */
    fun smsCheck(
        path: String,
        body: JsonObject,
        loggedIn: Boolean,
        phoneBound: Boolean,
    ): Pair<Int, String>? {
        if (path != SMS_PATH || body.text("purpose") != "step_up") return null
        return when {
            "phone" in body -> BAD_REQUEST to FIELDS_PHONE
            !loggedIn -> LOGIN_REQUIRED to "null"
            !phoneBound -> BAD_REQUEST to FIELDS_CODE
            else -> null
        }
    }

    /**
     * stepUp 请求的摘要：返回 (错误, claim)。错误非 null 时按它回答（20001 data.fields=[summary]）；否则 claim 是要写进 token 的那一条
     * （不绑摘要的 action 为 null）。
     */
    fun summaryClaim(
        action: String,
        body: JsonObject,
    ): Pair<Pair<Int, String>?, String?> {
        val summary = body["summary"]
        val bad = (BAD_REQUEST to FIELDS_SUMMARY) to null
        if (action != PAYOUT_ACCOUNT_CHANGE) return if (summary == null) null to null else bad
        val o = summary as? JsonObject ?: return bad
        val claim = payoutClaim(o).takeIf { o.keys.size == 2 }
        return if (claim == null) bad else null to claim
    }

    /** 收款账号的 claim：从摘要或保存请求体取方式与账号字段，规范化后拼出；不认识为 null。 */
    fun payoutClaim(o: JsonObject): String? =
        when (o.text("payout_method")) {
            "alipay" -> {
                o
                    .text("alipay_logon_id")
                    ?.let(::normalizeLogon)
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { "p:alipay:$it" }
            }

            "bank_card" -> {
                o
                    .text("card_no")
                    ?.filter(Char::isDigit)
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { "p:bank_card:$it" }
            }

            "wechat" -> {
                o.text("attempt_id")?.takeIf { it.isNotEmpty() }?.let { "p:wechat:$it" }
            }

            else -> {
                null
            }
        }

    /** 支付宝登录号的规范化（假服务的近似：去空格与连字符、手机号去 +86 / 86 前缀、邮箱转小写）。 */
    private fun normalizeLogon(raw: String): String {
        val text = raw.trim().filterNot { it == ' ' || it == '-' }
        if ('@' in text) return text.lowercase()
        val digits = text.removePrefix("+86").let { if (it.length == PREFIXED_PHONE) it.removePrefix("86") else it }
        return digits
    }

    private const val PREFIXED_PHONE = 13

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
