package com.couli.core.wallet

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 提现单的显示结论（BR-TEXT-06、BR-WDR-25、BR-TEXT-07 / 08；G08）。 */
data class WithdrawalDisplay(
    val titleKey: String,
    val subLines: List<TextLine>,
    val showChangeAccount: Boolean,
    /** withdraw_detail.net.expected / .actual；REJECTED、FAILED、未知状态为 null（不显示到账行）。 */
    val netKey: String?,
    /** 认识的通道（alipay、bank_card）：才出【修改收款账号】等操作入口。 */
    val knownChannel: Boolean,
)

/**
 * 提现单的状态标题与副文案（G08）：只按契约里有的字段取。
 * - PENDING_REVIEW / APPROVED：review_mode=auto「预计几分钟内到账」，manual 或 null 用 Config withdraw.sla_text（没下发用 BR-TEXT-07 原文）；
 *   另起一行「{amount} 已冻结」；
 * - PAYING：「处理中，结果会通知你」（读不到首次尝试时间，不按 T+2h 分支）；
 * - PAID_*：「已转入{通道} {脱敏串}，到账 {net}」，tax_fen>0 加「已代扣个税 {tax}」；
 * - REJECTED / FAILED：原因（只有规划原文写出的码才有键，未命中省略）+「{amount} 已退回可提现余额」；FAILED + fail_action=change_payout_account
 *   句尾加「，可修改收款账号后重新提现」并出【修改收款账号】；
 * - 未知状态「处理中」，不出副文案；不认识的通道（含 wechat）只显示通道名与脱敏串，不出任何操作入口。
 * 不显示审核截止时间、超时句、到账时间行、hold_days 句（契约没有这些字段）。
 */
object WithdrawalText {
    const val AUTO = "auto"
    const val CHANGE_PAYOUT_ACCOUNT = "change_payout_account"
    private val KNOWN_STATUSES = setOf("PENDING_REVIEW", "APPROVED", "PAYING", "PAID_API", "PAID_MANUAL", "REJECTED", "FAILED")
    private val REJECT_REASONS = setOf("RISK_SUSPECT", "ORDER_ABNORMAL", "PAYEE_INFO_INVALID", "USER_REQUEST", "OTHER", "NEGATIVE_BALANCE")
    private val FAIL_REASONS = setOf("PAYEE_NOT_EXIST", "PAYEE_USER_INFO_ERROR", "PAYEE_ACCOUNT_STATUS_ERROR")
    const val NET_EXPECTED = "withdraw_detail.net.expected"
    const val NET_ACTUAL = "withdraw_detail.net.actual"

    fun isPaid(status: String): Boolean = status == "PAID_API" || status == "PAID_MANUAL"

    fun isReviewing(status: String): Boolean = status == "PENDING_REVIEW" || status == "APPROVED"

    fun knownChannel(channel: String): Boolean = channel == WalletJson.ALIPAY || channel == WalletJson.BANK_CARD

    @Suppress("CyclomaticComplexMethod", "ReturnCount") // 状态逐项
    fun of(
        item: Withdrawal,
        slaText: String?,
        texts: WalletTexts = WalletDraftTexts,
    ): WithdrawalDisplay {
        val status = item.status
        val known = knownChannel(item.payoutChannel)
        if (status !in KNOWN_STATUSES) return WithdrawalDisplay("withdrawal_status.UNKNOWN.title", emptyList(), false, null, known)
        val title = "withdrawal_status.$status.title"
        val netKey =
            when {
                isPaid(status) -> NET_ACTUAL
                isReviewing(status) || status == "PAYING" -> NET_EXPECTED
                else -> null
            }
        if (!known) {
            val channel = TextLine("withdrawal.draft.channel", strings = channelStrings(item))
            return WithdrawalDisplay(title, listOf(channel), false, netKey, false)
        }
        val lines = mutableListOf<TextLine>()
        var change = false
        when {
            isReviewing(status) -> {
                lines += sla(item.reviewMode, slaText, texts)
                lines += TextLine("withdrawal.draft.frozen", amounts = mapOf("amount" to item.amountFen))
            }

            status == "PAYING" -> {
                lines += TextLine("withdrawal.draft.processing")
            }

            isPaid(status) -> {
                lines += TextLine("withdrawal.draft.paid", amounts = mapOf("net" to item.netFen), strings = channelStrings(item))
                if (item.taxFen > 0) lines += TextLine("withdrawal.draft.tax", amounts = mapOf("tax" to item.taxFen))
            }

            status == "REJECTED" -> {
                item.reasonCode?.takeIf { it in REJECT_REASONS }?.let { lines += TextLine("withdraw_reject_reason.$it") }
                lines += TextLine("withdrawal.draft.returned", amounts = mapOf("amount" to item.amountFen))
            }

            else -> {
                item.reasonCode?.takeIf { it in FAIL_REASONS }?.let { lines += TextLine("withdraw_fail_reason.$it") }
                change = item.failAction == CHANGE_PAYOUT_ACCOUNT
                val key = if (change) "withdrawal.draft.returned.change_account" else "withdrawal.draft.returned"
                lines += TextLine(key, amounts = mapOf("amount" to item.amountFen))
            }
        }
        return WithdrawalDisplay(title, lines, change, netKey, true)
    }

    private fun sla(
        reviewMode: String?,
        slaText: String?,
        texts: WalletTexts,
    ): TextLine =
        if (reviewMode == AUTO) {
            TextLine("withdraw.sla_text_auto")
        } else {
            TextLine(
                "withdraw.sla_text",
                strings =
                    mapOf("text" to (slaText?.takeIf { it.isNotBlank() } ?: texts.text(WithdrawRulesView.SLA_DEFAULT_KEY))),
            )
        }

    private fun channelStrings(item: Withdrawal): Map<String, String> =
        mapOf("payout_channel_name" to item.payoutChannelName, "masked_account" to item.maskedAccount)

    /** 原因片段与退回片段之间用分号连接（G08）；其余各占一行。 */
    fun render(
        display: WithdrawalDisplay,
        texts: WalletTexts,
    ): List<String> {
        val out = mutableListOf<String>()
        var reason: String? = null
        display.subLines.forEach { line ->
            val text = line.render(texts)
            when {
                line.key.startsWith("withdraw_reject_reason.") || line.key.startsWith("withdraw_fail_reason.") -> {
                    reason = text
                }

                line.key.startsWith("withdrawal.draft.returned") && reason != null -> {
                    out += reason + texts.text("withdrawal.draft.join") + text
                    reason = null
                }

                else -> {
                    out += text
                }
            }
        }
        reason?.let { out += it }
        return out
    }

    /** 向量接线。 */
    fun of(
        item: JsonObject,
        slaText: String?,
    ): JsonObject {
        val parsed = WalletJson.withdrawal(item) ?: throw IllegalArgumentException("bad withdrawal")
        val view = of(parsed, slaText)
        return jsonObj(
            "titleKey" to JsonPrimitive(view.titleKey),
            "subLines" to JsonArray(view.subLines.map { it.json() }),
            "showChangeAccount" to view.showChangeAccount.json(),
            "netKey" to view.netKey.json(),
        )
    }
}

/** 交易流水号行（BR-WDR-25 细则）：只有 PAID_API / PAID_MANUAL 且 channel_order_id 非 null 时显示，附【复制】，只在详情。 */
object TradeNoRow {
    fun copyText(
        status: String,
        channelOrderId: String?,
    ): String? = channelOrderId?.takeIf { WithdrawalText.isPaid(status) && it.isNotEmpty() }

    fun of(detail: JsonObject): JsonObject {
        val text = copyText(detail.textOrNull("status").orEmpty(), detail.textOrNull("channel_order_id"))
        return if (text != null) jsonObj("show" to true.json(), "copyText" to JsonPrimitive(text)) else jsonObj("show" to false.json())
    }
}
