package com.couli.core.wallet

import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** GET /v1/wallet/summary 的 data（13 个字段全部必填；金额不可空，next_credit_period、risk_paused_reason 可空）。 */
data class WalletSummary(
    val availableFen: Long,
    val withdrawableFen: Long,
    val negativeFen: Long,
    val frozenFen: Long,
    val estimatedTotalFen: Long,
    val estimatedFen: Long,
    val pendingCreditFen: Long,
    val pendingCreditPausedFen: Long,
    val nextCreditPeriod: String?,
    val creditOverdue: Boolean,
    val withdrawnFen: Long,
    val riskPausedReason: String?,
    val withdrawEnabled: Boolean,
)

/** GET /v1/withdrawals/rules 的 data（11 个字段全部必填；est_* 可空）。 */
data class WithdrawRules(
    val canWithdraw: Boolean,
    val blockCode: Int?,
    val blockReason: String?,
    val maxWithdrawableFen: Long,
    val withdrawableFen: Long,
    val dailyRemaining: Long,
    val monthlyRemaining: Long,
    val estFeeFen: Long?,
    val estTaxFen: Long?,
    val estNetFen: Long?,
    val quickAmountsFen: List<Long>,
)

/**
 * 一张提现单（WithdrawalSummary；详情 Withdrawal 只多 [channelOrderId]）。[status]、[payoutChannel] 原样保存：不认识的状态显示「处理中」，
 * 不认识的通道（含 wechat）只显示 payout_channel_name 与脱敏串、不出操作入口（BR-TEXT-06，brief §2.1）。
 */
data class Withdrawal(
    val withdrawalId: String,
    val status: String,
    val reviewMode: String?,
    val amountFen: Long,
    val feeFen: Long,
    val taxFen: Long,
    val netFen: Long,
    val payoutChannel: String,
    val payoutChannelName: String,
    val maskedAccount: String,
    val reasonCode: String?,
    val failAction: String?,
    val createdAt: String,
    val createdAtMillis: Long,
    /** 只有详情（GET /v1/withdrawals/{id}）有；列表项为 null 且 [isDetail] = false。 */
    val channelOrderId: String? = null,
    val isDetail: Boolean = false,
)

data class WithdrawalListData(
    val items: List<Withdrawal>,
    val nextCursor: String?,
    /** 必填字段缺失或类型不符被丢弃的条数（只记日志用，不显示）。 */
    val dropped: Int = 0,
)

/** GET /v1/me/payout-account 的 data：空对象 = 未绑定。 */
sealed interface PayoutAccount {
    data object Unbound : PayoutAccount

    data class Bound(
        val method: String,
        val maskedAccount: String,
        val maskedPayeeName: String,
        val changeRemainingThisMonth: Long,
        /** 银行卡才有。 */
        val bankName: String?,
    ) : PayoutAccount {
        /** 契约里认识的两种（alipay、bank_card）；其余（含 wechat）只显示脱敏串、不出操作入口。 */
        val known: Boolean get() = method == WalletJson.ALIPAY || method == WalletJson.BANK_CARD
    }
}

data class StepUpData(
    val token: String,
    val expireAt: String,
    val expireAtMillis: Long,
)

data class SendSmsCodeData(
    val resendAfterSec: Long,
    val expiresInSec: Long,
)

/** 作废接口的 data：abandoned（original 为 null）或 completed（original 为原外壳，code 为 0 或 3xxxx）。 */
data class AbandonData(
    val outcome: String,
    val originalCode: Int?,
    val originalData: JsonObject?,
)

/**
 * 钱包类接口的手写解析（照 APP-08 OrderJson 的先例）：必填字段缺失或类型不符 → 整条按坏数据（null），不补默认值，不把缺失当 0。
 * 列表项坏一条只丢弃这一条（[WithdrawalListData.dropped]）。
 */
@Suppress("TooManyFunctions", "ReturnCount") // 每种数据一个解析入口；坏数据逐项提前返回
object WalletJson {
    const val ALIPAY = "alipay"
    const val BANK_CARD = "bank_card"

    private val SUMMARY_FIELDS =
        setOf(
            "available_fen",
            "withdrawable_fen",
            "negative_fen",
            "frozen_fen",
            "estimated_total_fen",
            "estimated_fen",
            "pending_credit_fen",
            "pending_credit_paused_fen",
            "next_credit_period",
            "credit_overdue",
            "withdrawn_fen",
            "risk_paused_reason",
            "withdraw_enabled",
        )

    private val RULES_FIELDS =
        setOf(
            "can_withdraw",
            "block_code",
            "block_reason",
            "max_withdrawable_fen",
            "withdrawable_fen",
            "daily_remaining",
            "monthly_remaining",
            "est_fee_fen",
            "est_tax_fen",
            "est_net_fen",
            "quick_amounts_fen",
        )

    private val WITHDRAWAL_FIELDS =
        setOf(
            "withdrawal_id",
            "status",
            "review_mode",
            "amount_fen",
            "fee_fen",
            "tax_fen",
            "net_fen",
            "payout_channel",
            "payout_channel_name",
            "masked_account",
            "reason_code",
            "fail_action",
            "created_at",
        )

    private val PERIOD = Regex("^[0-9]{4}-(0[1-9]|1[0-2])$")

    fun summary(element: JsonElement?): WalletSummary? {
        val o = element as? JsonObject ?: return null
        if (!o.keys.containsAll(SUMMARY_FIELDS)) return null
        return parse {
            WalletSummary(
                availableFen = o.long("available_fen"),
                withdrawableFen = o.long("withdrawable_fen"),
                negativeFen = o.long("negative_fen"),
                frozenFen = o.long("frozen_fen"),
                estimatedTotalFen = o.long("estimated_total_fen"),
                estimatedFen = o.long("estimated_fen"),
                pendingCreditFen = o.long("pending_credit_fen"),
                pendingCreditPausedFen = o.long("pending_credit_paused_fen"),
                nextCreditPeriod = o.nullableString("next_credit_period")?.also { require(PERIOD.matches(it)) },
                creditOverdue = o.bool("credit_overdue"),
                withdrawnFen = o.long("withdrawn_fen"),
                riskPausedReason = o.nullableString("risk_paused_reason"),
                withdrawEnabled = o.bool("withdraw_enabled"),
            )
        }
    }

    fun encode(summary: WalletSummary): JsonObject =
        JsonObject(
            mapOf(
                "available_fen" to JsonPrimitive(summary.availableFen),
                "withdrawable_fen" to JsonPrimitive(summary.withdrawableFen),
                "negative_fen" to JsonPrimitive(summary.negativeFen),
                "frozen_fen" to JsonPrimitive(summary.frozenFen),
                "estimated_total_fen" to JsonPrimitive(summary.estimatedTotalFen),
                "estimated_fen" to JsonPrimitive(summary.estimatedFen),
                "pending_credit_fen" to JsonPrimitive(summary.pendingCreditFen),
                "pending_credit_paused_fen" to JsonPrimitive(summary.pendingCreditPausedFen),
                "next_credit_period" to (summary.nextCreditPeriod?.let(::JsonPrimitive) ?: JsonNull),
                "credit_overdue" to JsonPrimitive(summary.creditOverdue),
                "withdrawn_fen" to JsonPrimitive(summary.withdrawnFen),
                "risk_paused_reason" to (summary.riskPausedReason?.let(::JsonPrimitive) ?: JsonNull),
                "withdraw_enabled" to JsonPrimitive(summary.withdrawEnabled),
            ),
        )

    fun rules(element: JsonElement?): WithdrawRules? {
        val o = element as? JsonObject ?: return null
        if (!o.keys.containsAll(RULES_FIELDS)) return null
        return parse {
            WithdrawRules(
                canWithdraw = o.bool("can_withdraw"),
                blockCode = o.nullableLong("block_code")?.let(Math::toIntExact),
                blockReason = o.nullableString("block_reason"),
                maxWithdrawableFen = o.long("max_withdrawable_fen"),
                withdrawableFen = o.long("withdrawable_fen"),
                dailyRemaining = o.long("daily_remaining"),
                monthlyRemaining = o.long("monthly_remaining"),
                estFeeFen = o.nullableLong("est_fee_fen"),
                estTaxFen = o.nullableLong("est_tax_fen"),
                estNetFen = o.nullableLong("est_net_fen"),
                quickAmountsFen = (o["quick_amounts_fen"] as JsonArray).map { (it as JsonPrimitive).strictLong() },
            )
        }
    }

    /** 列表项（WithdrawalSummary）。 */
    fun withdrawal(element: JsonElement?): Withdrawal? = withdrawal(element, detail = false)

    /** 详情（Withdrawal，多一个必填可空的 channel_order_id）。 */
    fun withdrawalDetail(element: JsonElement?): Withdrawal? = withdrawal(element, detail = true)

    private fun withdrawal(
        element: JsonElement?,
        detail: Boolean,
    ): Withdrawal? {
        val o = element as? JsonObject ?: return null
        if (!o.keys.containsAll(WITHDRAWAL_FIELDS)) return null
        if (detail && "channel_order_id" !in o) return null
        return parse {
            val created = o.string("created_at")
            Withdrawal(
                withdrawalId = o.string("withdrawal_id").also { require(it.isNotEmpty()) },
                status = o.string("status"),
                reviewMode = o.nullableString("review_mode"),
                amountFen = o.long("amount_fen"),
                feeFen = o.long("fee_fen"),
                taxFen = o.long("tax_fen"),
                netFen = o.long("net_fen"),
                payoutChannel = o.string("payout_channel"),
                payoutChannelName = o.string("payout_channel_name"),
                maskedAccount = o.string("masked_account"),
                reasonCode = o.nullableString("reason_code"),
                failAction = o.nullableString("fail_action"),
                createdAt = created,
                createdAtMillis = millis(created),
                channelOrderId = if (detail) o.nullableString("channel_order_id") else null,
                isDetail = detail,
            )
        }
    }

    fun withdrawalList(element: JsonElement?): WithdrawalListData? {
        val o = element as? JsonObject ?: return null
        val items = o["items"] as? JsonArray ?: return null
        val cursor =
            when (val raw = o["next_cursor"]) {
                JsonNull -> null
                is JsonPrimitive -> raw.takeIf { it.isString }?.content ?: return null
                else -> return null
            }
        val parsed = items.map(::withdrawal)
        return WithdrawalListData(parsed.filterNotNull(), cursor, dropped = parsed.count { it == null })
    }

    /** 收款账号：空对象 = 未绑定；字段不全或方式不认识但字段齐 → 按「其他收款方式」只显示脱敏串。 */
    fun payoutAccount(element: JsonElement?): PayoutAccount? {
        val o = element as? JsonObject ?: return null
        if (o.isEmpty()) return PayoutAccount.Unbound
        return parse {
            val method = o.string("payout_method")
            PayoutAccount.Bound(
                method = method,
                maskedAccount = o.string("masked_account"),
                maskedPayeeName = o.string("masked_payee_name"),
                changeRemainingThisMonth = o.long("change_remaining_this_month"),
                bankName = if (method == BANK_CARD) o.string("bank_name") else null,
            )
        }
    }

    fun stepUp(element: JsonElement?): StepUpData? {
        val o = element as? JsonObject ?: return null
        return parse {
            val expire = o.string("expire_at")
            StepUpData(o.string("step_up_token").also { require(it.isNotEmpty()) }, expire, millis(expire))
        }
    }

    fun sendSmsCode(element: JsonElement?): SendSmsCodeData? {
        val o = element as? JsonObject ?: return null
        return parse { SendSmsCodeData(o.long("resend_after_sec"), o.long("expires_in_sec")) }
    }

    fun abandon(element: JsonElement?): AbandonData? {
        val o = element as? JsonObject ?: return null
        return parse {
            val outcome = o.string("outcome")
            val original = o["original"] as? JsonObject
            AbandonData(outcome, original?.long("code")?.let(Math::toIntExact), original?.get("data") as? JsonObject)
        }
    }

    /** ISO 8601 带时区 → 毫秒；格式不对抛错（整条按坏数据）。 */
    fun millis(iso: String): Long =
        try {
            OffsetDateTime.parse(iso).toInstant().toEpochMilli()
        } catch (error: DateTimeParseException) {
            throw IllegalArgumentException("bad time", error)
        }

    private inline fun <T> parse(block: () -> T): T? =
        try {
            block()
        } catch (
            @Suppress("SwallowedException") error: IllegalArgumentException,
        ) {
            null
        } catch (
            @Suppress("SwallowedException") error: ClassCastException,
        ) {
            null
        } catch (
            @Suppress("SwallowedException") error: ArithmeticException,
        ) {
            null
        }

    private fun JsonObject.primitive(key: String): JsonPrimitive = get(key) as? JsonPrimitive ?: throw IllegalArgumentException(key)

    private fun JsonObject.string(key: String): String {
        val p = primitive(key)
        require(p.isString) { key }
        return p.content
    }

    private fun JsonObject.nullableString(key: String): String? {
        val value = get(key) ?: throw IllegalArgumentException(key)
        return if (value == JsonNull) null else string(key)
    }

    private fun JsonObject.long(key: String): Long = primitive(key).strictLong()

    private fun JsonObject.nullableLong(key: String): Long? {
        val value = get(key) ?: throw IllegalArgumentException(key)
        return if (value == JsonNull) null else long(key)
    }

    private fun JsonObject.bool(key: String): Boolean {
        val p = primitive(key)
        require(!p.isString) { key }
        return p.booleanOrNull ?: throw IllegalArgumentException(key)
    }

    /** 整数（不接受字符串、小数）。 */
    private fun JsonPrimitive.strictLong(): Long {
        require(!isString && this != JsonNull)
        return longOrNull ?: throw IllegalArgumentException("not integer")
    }
}
