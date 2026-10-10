package com.couli.core.wallet

import com.couli.core.orders.CreditPeriodText
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 一个可隐藏的金额（[fen] 始终是服务端值；[masked] 时显示 [MASK]，不带 ¥、位数、正负号）。 */
data class MaskableAmount(
    val fen: Long,
    val masked: Boolean,
) {
    val maskedText: String? get() = if (masked) MASK else null

    companion object {
        /** BR-TEXT-10 细则「金额隐藏」的遮罩（与 APP-08 amount_mask.mask 同文）。 */
        const val MASK = "****"
    }
}

/** 风控暂停提示条（G10）：[textKey] 为 wallet.draft.risk_paused（查不到原因文案）或 wallet.draft.risk_paused_reason（带 [reasonText]）。 */
data class RiskBanner(
    val show: Boolean,
    val textKey: String? = null,
    val reasonText: String? = null,
)

/**
 * 钱包页的显示结论（BR-FUND-18、BR-TEXT-01、03 §10.2 WalletHeroCard / AmountGrid）。金额只取服务端字段：
 * - 主卡「可提现余额」= withdrawable_fen；待抵扣 negative_fen>0 另起一行，冻结中、待抵扣不进大数字；
 * - 「预估收益」= estimated_total_fen（客户端不相加）；其下三项分开：已收货（为 0 照常显示）、「最早预计 {月份} 结算」（next_credit_period
 *   非 null）与「入账核对中」（credit_overdue）、「其中 {x} 暂缓入账」（>0）；不出现「待入账」「累计」；
 * - 【提现】（G11）：withdraw_enabled=false（附 error.30306）、待抵扣、风控暂停都置灰；其余可点，能不能提由 Withdraw 页的 rules 决定。
 */
data class WalletSummaryDisplay(
    val hero: MaskableAmount,
    val negativeLine: MaskableAmount?,
    val frozen: MaskableAmount,
    val withdrawn: MaskableAmount,
    val estimated: MaskableAmount,
    val receivedLine: MaskableAmount,
    val earliestMonth: String?,
    val overdueNote: Boolean,
    val pausedLine: MaskableAmount?,
    val riskBanner: RiskBanner,
    val withdrawEnabled: Boolean,
    val withdrawReasonKey: String?,
)

object WalletSummaryView {
    const val RISK_PAUSED_KEY = "wallet.draft.risk_paused"
    const val RISK_PAUSED_REASON_KEY = "wallet.draft.risk_paused_reason"
    const val FEATURE_OFF_KEY = "error.30306"

    fun of(
        summary: WalletSummary,
        masked: Boolean,
        nowMs: Long,
        texts: WalletTexts = WalletDraftTexts,
    ): WalletSummaryDisplay {
        fun amount(fen: Long) = MaskableAmount(fen, masked)
        val risk = summary.riskPausedReason
        val banner =
            when {
                risk == null -> {
                    RiskBanner(show = false)
                }

                // 原因文案查 risk_msg.<reason>（契约还没有这些键，G10）；查不到只显示「提现已暂停」，不显示原因码原文
                texts.has("risk_msg.$risk") -> {
                    RiskBanner(show = true, textKey = RISK_PAUSED_REASON_KEY, reasonText = texts.text("risk_msg.$risk"))
                }

                else -> {
                    RiskBanner(show = true, textKey = RISK_PAUSED_KEY)
                }
            }
        // 风控冻结不置灰（G11 编排者更正，BR-WDR-05 细则、BR-TEXT-01）：可点，进提现页由 rules 的 30303 account_frozen 提示、不能提交
        val enabled = summary.withdrawEnabled && summary.negativeFen <= 0
        return WalletSummaryDisplay(
            hero = amount(summary.withdrawableFen),
            negativeLine = summary.negativeFen.takeIf { it > 0 }?.let(::amount),
            frozen = amount(summary.frozenFen),
            withdrawn = amount(summary.withdrawnFen),
            estimated = amount(summary.estimatedTotalFen),
            receivedLine = amount(summary.pendingCreditFen),
            earliestMonth = CreditPeriodText.monthText(summary.nextCreditPeriod, nowMs),
            overdueNote = summary.creditOverdue,
            pausedLine = summary.pendingCreditPausedFen.takeIf { it > 0 }?.let(::amount),
            riskBanner = banner,
            withdrawEnabled = enabled,
            withdrawReasonKey = if (!summary.withdrawEnabled) FEATURE_OFF_KEY else null,
        )
    }

    /** 向量接线：summary 是接口 data 原样；坏数据抛错（向量不含坏数据）。 */
    fun of(
        summary: JsonObject,
        masked: Boolean,
        nowMs: Long,
    ): JsonObject {
        val parsed = WalletJson.summary(summary) ?: throw IllegalArgumentException("bad summary")
        val view = of(parsed, masked, nowMs)
        return jsonObj(
            "hero" to view.hero.json(),
            "negativeLine" to (view.negativeLine?.json() ?: JsonNull),
            "frozen" to view.frozen.json(),
            "withdrawn" to view.withdrawn.json(),
            "estimated" to view.estimated.json(),
            "receivedLine" to view.receivedLine.json(),
            "earliestMonth" to (view.earliestMonth?.let { jsonObj("monthText" to JsonPrimitive(it)) } ?: JsonNull),
            "overdueNote" to view.overdueNote.json(),
            "pausedLine" to (view.pausedLine?.json() ?: JsonNull),
            "riskBanner" to
                if (view.riskBanner.show) {
                    jsonObj("show" to true.json(), "textKey" to view.riskBanner.textKey.json())
                } else {
                    jsonObj("show" to false.json())
                },
            "withdrawButton" to
                if (view.withdrawReasonKey != null) {
                    jsonObj("enabled" to view.withdrawEnabled.json(), "reasonKey" to JsonPrimitive(view.withdrawReasonKey))
                } else {
                    jsonObj("enabled" to view.withdrawEnabled.json())
                },
        )
    }

    private fun MaskableAmount.json(): JsonObject =
        maskedText?.let { jsonObj("fen" to JsonPrimitive(fen), "maskedText" to JsonPrimitive(it)) } ?: jsonObj("fen" to JsonPrimitive(fen))
}
