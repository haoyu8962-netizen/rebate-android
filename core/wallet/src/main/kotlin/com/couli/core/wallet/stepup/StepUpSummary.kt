// 摘要字段逐项校验、取不出即返回
@file:Suppress("ReturnCount")

package com.couli.core.wallet.stepup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 二次验证的业务摘要（BR-ID-08 细则「绑定业务摘要」；openapi StepUpSummary，CT-26c）：收款账号变更的 stepUp 请求带上新账号的摘要，服务端写进
 * token，保存请求在锁内比对，不符回 10003。
 * - payout_account_change：与保存接口相同的账号字段原样上送（支付宝 alipay_logon_id、银行卡 card_no、微信零钱 payout_bind 的 attempt_id），
 *   服务端自己规范化并计算指纹；不带收款人姓名，不在客户端算指纹。
 * - withdraw 不带摘要（D10-1665 / D10-1666 / D10-1667：提现收窄后服务端对 action=withdraw 带 summary 回 20001；过渡期收到 10003 仍弹面板，取令牌不带摘要）；
 *   更换手机号与注销也不绑摘要（带了回 20001）。
 * 摘要只从这次要提交 / 重放的原请求体推出（沿用原键原体重放时摘要与请求体一致）。取不出（请求体不认识）为 null。
 */
object StepUpSummary {
    const val PAYOUT_ACCOUNT_CHANGE = "payout_account_change"

    private const val ALIPAY = "alipay"
    private const val BANK_CARD = "bank_card"
    private const val WECHAT = "wechat"
    private const val METHOD = "payout_method"

    /** 这个 action 的 stepUp 必须带摘要（服务端缺了回 20001 data.fields=[summary]）：只有收款账号变更。 */
    fun required(action: String): Boolean = action == PAYOUT_ACCOUNT_CHANGE

    /** 收款账号变更摘要：从保存请求体取 payout_method 与该方式的账号字段（原样，不规范化、不含 payee_name）。 */
    fun payoutChange(body: String): JsonObject? {
        val o = parse(body) ?: return null
        val method = o.text(METHOD) ?: return null
        val field =
            when (method) {
                ALIPAY -> "alipay_logon_id"
                BANK_CARD -> "card_no"
                WECHAT -> "attempt_id"
                else -> return null
            }
        val value = o.text(field)?.takeIf { it.isNotEmpty() } ?: return null
        return JsonObject(linkedMapOf(METHOD to JsonPrimitive(method), field to JsonPrimitive(value)))
    }

    /** 令牌缓存用的摘要键（同一摘要同一串）；没有摘要为 null（不带摘要的 action 按同 action 复用）。 */
    fun key(summary: JsonObject?): String? = summary?.toString()

    private fun parse(body: String): JsonObject? = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
