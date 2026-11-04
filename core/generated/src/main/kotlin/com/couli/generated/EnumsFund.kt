// 生成物，禁止手改；来源 contract.lock（tools/codegen/gen.py 由 contracts/snapshot/ 生成）
package com.couli.generated

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * 契约枚举 `ledger_type`（contracts/enums/fund.yaml）。
 * 余额流水类型，共 13 种，细分只用 sub_type；用户侧名称见 BR-TEXT-19
 *
 * 未知取值解码为 [LedgerType.UNKNOWN]，不抛错。
 */
@Serializable(with = LedgerTypeSerializer::class)
enum class LedgerType(
    val raw: kotlin.String,
) {
    REBATE_CREDIT("REBATE_CREDIT"),
    SHARE_CREDIT("SHARE_CREDIT"),
    REFERRAL_CREDIT("REFERRAL_CREDIT"),
    CLAWBACK("CLAWBACK"),
    SETTLE_ADJUST("SETTLE_ADJUST"),
    WITHDRAW_FREEZE("WITHDRAW_FREEZE"),
    WITHDRAW_PAID("WITHDRAW_PAID"),
    WITHDRAW_RETURN("WITHDRAW_RETURN"),
    WITHDRAW_FEE("WITHDRAW_FEE"),
    TAX_WITHHOLD("TAX_WITHHOLD"),
    REWARD("REWARD"),
    ADMIN_ADJUST("ADMIN_ADJUST"),
    BAD_DEBT_WRITEOFF("BAD_DEBT_WRITEOFF"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<LedgerType> =
            listOf(
                REBATE_CREDIT,
                SHARE_CREDIT,
                REFERRAL_CREDIT,
                CLAWBACK,
                SETTLE_ADJUST,
                WITHDRAW_FREEZE,
                WITHDRAW_PAID,
                WITHDRAW_RETURN,
                WITHDRAW_FEE,
                TAX_WITHHOLD,
                REWARD,
                ADMIN_ADJUST,
                BAD_DEBT_WRITEOFF,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, LedgerType> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): LedgerType = byRaw[raw] ?: UNKNOWN
    }
}

/** [LedgerType] 的序列化器：未知字符串得 [LedgerType.UNKNOWN]；序列化写 raw。 */
object LedgerTypeSerializer : KSerializer<LedgerType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.LedgerType", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): LedgerType = LedgerType.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: LedgerType,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `referral_credit_sub_type`（contracts/enums/fund.yaml）。
 * REFERRAL_CREDIT 的 sub_type；INDIRECT 只在间推开关开启的规则版本下产生
 *
 * 未知取值解码为 [ReferralCreditSubType.UNKNOWN]，不抛错。
 */
@Serializable(with = ReferralCreditSubTypeSerializer::class)
enum class ReferralCreditSubType(
    val raw: kotlin.String,
) {
    DIRECT("DIRECT"),
    INDIRECT("INDIRECT"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<ReferralCreditSubType> =
            listOf(
                DIRECT,
                INDIRECT,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, ReferralCreditSubType> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): ReferralCreditSubType = byRaw[raw] ?: UNKNOWN
    }
}

/** [ReferralCreditSubType] 的序列化器：未知字符串得 [ReferralCreditSubType.UNKNOWN]；序列化写 raw。 */
object ReferralCreditSubTypeSerializer : KSerializer<ReferralCreditSubType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.ReferralCreditSubType", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): ReferralCreditSubType = ReferralCreditSubType.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: ReferralCreditSubType,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `clawback_sub_type`（contracts/enums/fund.yaml）。
 * 按逆向事件来源取值，映射只在 BR-FUND-08 细则维护
 *
 * 未知取值解码为 [ClawbackSubType.UNKNOWN]，不抛错。
 */
@Serializable(with = ClawbackSubTypeSerializer::class)
enum class ClawbackSubType(
    val raw: kotlin.String,
) {
    FULL("FULL"),
    PART_REFUND("PART_REFUND"),
    RIGHTS("RIGHTS"),
    PUNISH("PUNISH"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<ClawbackSubType> =
            listOf(
                FULL,
                PART_REFUND,
                RIGHTS,
                PUNISH,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, ClawbackSubType> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): ClawbackSubType = byRaw[raw] ?: UNKNOWN
    }
}

/** [ClawbackSubType] 的序列化器：未知字符串得 [ClawbackSubType.UNKNOWN]；序列化写 raw。 */
object ClawbackSubTypeSerializer : KSerializer<ClawbackSubType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.ClawbackSubType", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): ClawbackSubType = ClawbackSubType.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: ClawbackSubType,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `settle_adjust_sub_type`（contracts/enums/fund.yaml）。
 *
 * 未知取值解码为 [SettleAdjustSubType.UNKNOWN]，不抛错。
 */
@Serializable(with = SettleAdjustSubTypeSerializer::class)
enum class SettleAdjustSubType(
    val raw: kotlin.String,
) {
    SETTLE_DIFF("SETTLE_DIFF"),
    PRICE_COMPARE("PRICE_COMPARE"),
    PRICE_PROTECT("PRICE_PROTECT"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<SettleAdjustSubType> =
            listOf(
                SETTLE_DIFF,
                PRICE_COMPARE,
                PRICE_PROTECT,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, SettleAdjustSubType> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): SettleAdjustSubType = byRaw[raw] ?: UNKNOWN
    }
}

/** [SettleAdjustSubType] 的序列化器：未知字符串得 [SettleAdjustSubType.UNKNOWN]；序列化写 raw。 */
object SettleAdjustSubTypeSerializer : KSerializer<SettleAdjustSubType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.SettleAdjustSubType", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): SettleAdjustSubType = SettleAdjustSubType.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: SettleAdjustSubType,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `reward_sub_type`（contracts/enums/fund.yaml）。
 * REWARD 的 sub_type；invite 随邀请奖励活动启用，邀请人与被邀请人都用 invite，两方由 invite_rewards 区分；newbie、checkin 仍 P1
 *
 * 未知取值解码为 [RewardSubType.UNKNOWN]，不抛错。
 */
@Serializable(with = RewardSubTypeSerializer::class)
enum class RewardSubType(
    val raw: kotlin.String,
) {
    NEWBIE("newbie"),
    CHECKIN("checkin"),
    INVITE("invite"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<RewardSubType> =
            listOf(
                NEWBIE,
                CHECKIN,
                INVITE,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, RewardSubType> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): RewardSubType = byRaw[raw] ?: UNKNOWN
    }
}

/** [RewardSubType] 的序列化器：未知字符串得 [RewardSubType.UNKNOWN]；序列化写 raw。 */
object RewardSubTypeSerializer : KSerializer<RewardSubType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.RewardSubType", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): RewardSubType = RewardSubType.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: RewardSubType,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `admin_adjust_sub_type`（contracts/enums/fund.yaml）。
 * 人工调账原因码，含义与流程只在 BR-FUND-24 维护
 *
 * 未知取值解码为 [AdminAdjustSubType.UNKNOWN]，不抛错。
 */
@Serializable(with = AdminAdjustSubTypeSerializer::class)
enum class AdminAdjustSubType(
    val raw: kotlin.String,
) {
    RESTORE("RESTORE"),
    RECON_FIX("RECON_FIX"),
    PAYOUT_RECOVERY("PAYOUT_RECOVERY"),
    ACCOUNT_CLOSED("ACCOUNT_CLOSED"),
    OTHER("OTHER"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<AdminAdjustSubType> =
            listOf(
                RESTORE,
                RECON_FIX,
                PAYOUT_RECOVERY,
                ACCOUNT_CLOSED,
                OTHER,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, AdminAdjustSubType> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): AdminAdjustSubType = byRaw[raw] ?: UNKNOWN
    }
}

/** [AdminAdjustSubType] 的序列化器：未知字符串得 [AdminAdjustSubType.UNKNOWN]；序列化写 raw。 */
object AdminAdjustSubTypeSerializer : KSerializer<AdminAdjustSubType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.AdminAdjustSubType", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): AdminAdjustSubType = AdminAdjustSubType.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: AdminAdjustSubType,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `admin_adjustment_status`（contracts/enums/fund.yaml）。
 * admin_adjustments.status；人工单一步生效为 APPROVED，PENDING 只剩系统预填单；已红冲是标记不是状态
 *
 * 未知取值解码为 [AdminAdjustmentStatus.UNKNOWN]，不抛错。
 */
@Serializable(with = AdminAdjustmentStatusSerializer::class)
enum class AdminAdjustmentStatus(
    val raw: kotlin.String,
) {
    PENDING("PENDING"),
    APPROVED("APPROVED"),
    REJECTED("REJECTED"),
    CANCELLED("CANCELLED"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<AdminAdjustmentStatus> =
            listOf(
                PENDING,
                APPROVED,
                REJECTED,
                CANCELLED,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, AdminAdjustmentStatus> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): AdminAdjustmentStatus = byRaw[raw] ?: UNKNOWN
    }
}

/** [AdminAdjustmentStatus] 的序列化器：未知字符串得 [AdminAdjustmentStatus.UNKNOWN]；序列化写 raw。 */
object AdminAdjustmentStatusSerializer : KSerializer<AdminAdjustmentStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.AdminAdjustmentStatus", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): AdminAdjustmentStatus = AdminAdjustmentStatus.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: AdminAdjustmentStatus,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `admin_adjustment_direction`（contracts/enums/fund.yaml）。
 * admin_adjustments.direction，对 available 的方向
 *
 * 未知取值解码为 [AdminAdjustmentDirection.UNKNOWN]，不抛错。
 */
@Serializable(with = AdminAdjustmentDirectionSerializer::class)
enum class AdminAdjustmentDirection(
    val raw: kotlin.String,
) {
    INCREASE("increase"),
    DECREASE("decrease"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<AdminAdjustmentDirection> =
            listOf(
                INCREASE,
                DECREASE,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, AdminAdjustmentDirection> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): AdminAdjustmentDirection = byRaw[raw] ?: UNKNOWN
    }
}

/** [AdminAdjustmentDirection] 的序列化器：未知字符串得 [AdminAdjustmentDirection.UNKNOWN]；序列化写 raw。 */
object AdminAdjustmentDirectionSerializer : KSerializer<AdminAdjustmentDirection> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.AdminAdjustmentDirection", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): AdminAdjustmentDirection = AdminAdjustmentDirection.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: AdminAdjustmentDirection,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `admin_adjustment_ref_type`（contracts/enums/fund.yaml）。
 * 调账单关联单据类型；order 的 ref_id 是子订单 order_key
 *
 * 未知取值解码为 [AdminAdjustmentRefType.UNKNOWN]，不抛错。
 */
@Serializable(with = AdminAdjustmentRefTypeSerializer::class)
enum class AdminAdjustmentRefType(
    val raw: kotlin.String,
) {
    RECON_DIFF("recon_diff"),
    WITHDRAWAL("withdrawal"),
    ORDER("order"),
    ACCOUNT_DELETION("account_deletion"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<AdminAdjustmentRefType> =
            listOf(
                RECON_DIFF,
                WITHDRAWAL,
                ORDER,
                ACCOUNT_DELETION,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, AdminAdjustmentRefType> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): AdminAdjustmentRefType = byRaw[raw] ?: UNKNOWN
    }
}

/** [AdminAdjustmentRefType] 的序列化器：未知字符串得 [AdminAdjustmentRefType.UNKNOWN]；序列化写 raw。 */
object AdminAdjustmentRefTypeSerializer : KSerializer<AdminAdjustmentRefType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.AdminAdjustmentRefType", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): AdminAdjustmentRefType = AdminAdjustmentRefType.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: AdminAdjustmentRefType,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `admin_adjustment_contra_subject`（contracts/enums/fund.yaml）。
 * 调账对方科目；RECON_FIX、OTHER 由发起人从前四个选，PAYOUT_RECOVERY 按提现通道取现金科目，ACCOUNT_CLOSED 固定 COMMISSION_REVENUE
 *
 * 未知取值解码为 [AdminAdjustmentContraSubject.UNKNOWN]，不抛错。
 */
@Serializable(with = AdminAdjustmentContraSubjectSerializer::class)
enum class AdminAdjustmentContraSubject(
    val raw: kotlin.String,
) {
    COMMISSION_REVENUE("COMMISSION_REVENUE"),
    UNION_RECEIVABLE("UNION_RECEIVABLE"),
    BAD_DEBT("BAD_DEBT"),
    MKT_EXPENSE("MKT_EXPENSE"),
    CASH_ALIPAY("CASH_ALIPAY"),
    CASH_WECHAT("CASH_WECHAT"),
    CASH_BANK("CASH_BANK"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<AdminAdjustmentContraSubject> =
            listOf(
                COMMISSION_REVENUE,
                UNION_RECEIVABLE,
                BAD_DEBT,
                MKT_EXPENSE,
                CASH_ALIPAY,
                CASH_WECHAT,
                CASH_BANK,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, AdminAdjustmentContraSubject> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): AdminAdjustmentContraSubject = byRaw[raw] ?: UNKNOWN
    }
}

/** [AdminAdjustmentContraSubject] 的序列化器：未知字符串得 [AdminAdjustmentContraSubject.UNKNOWN]；序列化写 raw。 */
object AdminAdjustmentContraSubjectSerializer : KSerializer<AdminAdjustmentContraSubject> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.AdminAdjustmentContraSubject", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): AdminAdjustmentContraSubject = AdminAdjustmentContraSubject.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: AdminAdjustmentContraSubject,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `beneficiary_role`（contracts/enums/fund.yaml）。
 * 受益角色；indirect 只在所选规则版本 indirect_enabled=true 时出现；客户端遇未知值按通用收入显示
 *
 * 未知取值解码为 [BeneficiaryRole.UNKNOWN]，不抛错。
 */
@Serializable(with = BeneficiaryRoleSerializer::class)
enum class BeneficiaryRole(
    val raw: kotlin.String,
) {
    SELF("self"),
    SHARE("share"),
    DIRECT("direct"),
    INDIRECT("indirect"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<BeneficiaryRole> =
            listOf(
                SELF,
                SHARE,
                DIRECT,
                INDIRECT,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, BeneficiaryRole> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): BeneficiaryRole = byRaw[raw] ?: UNKNOWN
    }
}

/** [BeneficiaryRole] 的序列化器：未知字符串得 [BeneficiaryRole.UNKNOWN]；序列化写 raw。 */
object BeneficiaryRoleSerializer : KSerializer<BeneficiaryRole> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.BeneficiaryRole", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): BeneficiaryRole = BeneficiaryRole.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: BeneficiaryRole,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `forfeit_reason`（contracts/enums/fund.yaml）。
 * commission_splits.beneficiaries[].forfeit_reason，受益人份额被剥夺的原因
 *
 * 未知取值解码为 [ForfeitReason.UNKNOWN]，不抛错。
 */
@Serializable(with = ForfeitReasonSerializer::class)
enum class ForfeitReason(
    val raw: kotlin.String,
) {
    BANNED("banned"),
    DELETED("deleted"),
    MINOR("minor"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<ForfeitReason> =
            listOf(
                BANNED,
                DELETED,
                MINOR,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, ForfeitReason> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): ForfeitReason = byRaw[raw] ?: UNKNOWN
    }
}

/** [ForfeitReason] 的序列化器：未知字符串得 [ForfeitReason.UNKNOWN]；序列化写 raw。 */
object ForfeitReasonSerializer : KSerializer<ForfeitReason> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.ForfeitReason", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): ForfeitReason = ForfeitReason.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: ForfeitReason,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `commission_rule_status`（contracts/enums/fund.yaml）。
 *
 * 未知取值解码为 [CommissionRuleStatus.UNKNOWN]，不抛错。
 */
@Serializable(with = CommissionRuleStatusSerializer::class)
enum class CommissionRuleStatus(
    val raw: kotlin.String,
) {
    DRAFT("draft"),
    PUBLISHED("published"),
    REVOKED("revoked"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<CommissionRuleStatus> =
            listOf(
                DRAFT,
                PUBLISHED,
                REVOKED,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, CommissionRuleStatus> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): CommissionRuleStatus = byRaw[raw] ?: UNKNOWN
    }
}

/** [CommissionRuleStatus] 的序列化器：未知字符串得 [CommissionRuleStatus.UNKNOWN]；序列化写 raw。 */
object CommissionRuleStatusSerializer : KSerializer<CommissionRuleStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.CommissionRuleStatus", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): CommissionRuleStatus = CommissionRuleStatus.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: CommissionRuleStatus,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `income_type`（contracts/enums/fund.yaml）。
 * 所得类型；SELF_REBATE 停用，编码保留不复用，不列入
 *
 * 未知取值解码为 [IncomeType.UNKNOWN]，不抛错。
 */
@Serializable(with = IncomeTypeSerializer::class)
enum class IncomeType(
    val raw: kotlin.String,
) {
    SERVICE_FEE("SERVICE_FEE"),
    INCIDENTAL("INCIDENTAL"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<IncomeType> =
            listOf(
                SERVICE_FEE,
                INCIDENTAL,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, IncomeType> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): IncomeType = byRaw[raw] ?: UNKNOWN
    }
}

/** [IncomeType] 的序列化器：未知字符串得 [IncomeType.UNKNOWN]；序列化写 raw。 */
object IncomeTypeSerializer : KSerializer<IncomeType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.IncomeType", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): IncomeType = IncomeType.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: IncomeType,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `withdrawal_status`（contracts/enums/fund.yaml）。
 * 终态 REJECTED、PAID_API、PAID_MANUAL、FAILED；迁移 W1–W11，W11 事件 CHANNEL_SUCCESS_PROVEN（付款方侧失败而通道已成功时核销原单，BR-WDR-17）；用户文案见 BR-TEXT-06
 *
 * 未知取值解码为 [WithdrawalStatus.UNKNOWN]，不抛错。
 */
@Serializable(with = WithdrawalStatusSerializer::class)
enum class WithdrawalStatus(
    val raw: kotlin.String,
) {
    PENDING_REVIEW("PENDING_REVIEW"),
    APPROVED("APPROVED"),
    REJECTED("REJECTED"),
    PAYING("PAYING"),
    PAID_API("PAID_API"),
    PAID_MANUAL("PAID_MANUAL"),
    FAILED("FAILED"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<WithdrawalStatus> =
            listOf(
                PENDING_REVIEW,
                APPROVED,
                REJECTED,
                PAYING,
                PAID_API,
                PAID_MANUAL,
                FAILED,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, WithdrawalStatus> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): WithdrawalStatus = byRaw[raw] ?: UNKNOWN
    }
}

/** [WithdrawalStatus] 的序列化器：未知字符串得 [WithdrawalStatus.UNKNOWN]；序列化写 raw。 */
object WithdrawalStatusSerializer : KSerializer<WithdrawalStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.WithdrawalStatus", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): WithdrawalStatus = WithdrawalStatus.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: WithdrawalStatus,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `withdraw_reject_reason`（contracts/enums/fund.yaml）。
 * withdrawals.reject_reason_code；前 5 个人工可选，NEGATIVE_BALANCE 系统专用
 *
 * 未知取值解码为 [WithdrawRejectReason.UNKNOWN]，不抛错。
 */
@Serializable(with = WithdrawRejectReasonSerializer::class)
enum class WithdrawRejectReason(
    val raw: kotlin.String,
) {
    RISK_SUSPECT("RISK_SUSPECT"),
    ORDER_ABNORMAL("ORDER_ABNORMAL"),
    PAYEE_INFO_INVALID("PAYEE_INFO_INVALID"),
    USER_REQUEST("USER_REQUEST"),
    OTHER("OTHER"),
    NEGATIVE_BALANCE("NEGATIVE_BALANCE"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<WithdrawRejectReason> =
            listOf(
                RISK_SUSPECT,
                ORDER_ABNORMAL,
                PAYEE_INFO_INVALID,
                USER_REQUEST,
                OTHER,
                NEGATIVE_BALANCE,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, WithdrawRejectReason> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): WithdrawRejectReason = byRaw[raw] ?: UNKNOWN
    }
}

/** [WithdrawRejectReason] 的序列化器：未知字符串得 [WithdrawRejectReason.UNKNOWN]；序列化写 raw。 */
object WithdrawRejectReasonSerializer : KSerializer<WithdrawRejectReason> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.WithdrawRejectReason", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): WithdrawRejectReason = WithdrawRejectReason.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: WithdrawRejectReason,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `withdrawal_hold_reason`（contracts/enums/fund.yaml）。
 * withdrawals.hold_reason，W9 回到 APPROVED 的原因
 *
 * 未知取值解码为 [WithdrawalHoldReason.UNKNOWN]，不抛错。
 */
@Serializable(with = WithdrawalHoldReasonSerializer::class)
enum class WithdrawalHoldReason(
    val raw: kotlin.String,
) {
    PAYOUT_DISABLED("payout_disabled"),
    QUEUE_PAUSED("queue_paused"),
    CHANNEL_DISABLED("channel_disabled"),
    MEMBER_BLOCKED("member_blocked"),
    SINGLE_CAP("single_cap"),
    DAILY_CAP("daily_cap"),
    PAYER_SIDE_AFTER_TRANSFER("payer_side_after_transfer"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<WithdrawalHoldReason> =
            listOf(
                PAYOUT_DISABLED,
                QUEUE_PAUSED,
                CHANNEL_DISABLED,
                MEMBER_BLOCKED,
                SINGLE_CAP,
                DAILY_CAP,
                PAYER_SIDE_AFTER_TRANSFER,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, WithdrawalHoldReason> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): WithdrawalHoldReason = byRaw[raw] ?: UNKNOWN
    }
}

/** [WithdrawalHoldReason] 的序列化器：未知字符串得 [WithdrawalHoldReason.UNKNOWN]；序列化写 raw。 */
object WithdrawalHoldReasonSerializer : KSerializer<WithdrawalHoldReason> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.WithdrawalHoldReason", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): WithdrawalHoldReason = WithdrawalHoldReason.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: WithdrawalHoldReason,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `withdrawal_blocked_reason`（contracts/enums/fund.yaml）。
 * NEGATIVE_BALANCE_OTHER 随单一余额停用，编码不复用，不列入
 *
 * 未知取值解码为 [WithdrawalBlockedReason.UNKNOWN]，不抛错。
 */
@Serializable(with = WithdrawalBlockedReasonSerializer::class)
enum class WithdrawalBlockedReason(
    val raw: kotlin.String,
) {
    NEGATIVE_BALANCE("NEGATIVE_BALANCE"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<WithdrawalBlockedReason> =
            listOf(
                NEGATIVE_BALANCE,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, WithdrawalBlockedReason> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): WithdrawalBlockedReason = byRaw[raw] ?: UNKNOWN
    }
}

/** [WithdrawalBlockedReason] 的序列化器：未知字符串得 [WithdrawalBlockedReason.UNKNOWN]；序列化写 raw。 */
object WithdrawalBlockedReasonSerializer : KSerializer<WithdrawalBlockedReason> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.WithdrawalBlockedReason", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): WithdrawalBlockedReason = WithdrawalBlockedReason.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: WithdrawalBlockedReason,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `withdrawal_manual_resolution`（contracts/enums/fund.yaml）。
 *
 * 未知取值解码为 [WithdrawalManualResolution.UNKNOWN]，不抛错。
 */
@Serializable(with = WithdrawalManualResolutionSerializer::class)
enum class WithdrawalManualResolution(
    val raw: kotlin.String,
) {
    PAID("paid"),
    FAILED("failed"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<WithdrawalManualResolution> =
            listOf(
                PAID,
                FAILED,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, WithdrawalManualResolution> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): WithdrawalManualResolution = byRaw[raw] ?: UNKNOWN
    }
}

/** [WithdrawalManualResolution] 的序列化器：未知字符串得 [WithdrawalManualResolution.UNKNOWN]；序列化写 raw。 */
object WithdrawalManualResolutionSerializer : KSerializer<WithdrawalManualResolution> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.WithdrawalManualResolution", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): WithdrawalManualResolution = WithdrawalManualResolution.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: WithdrawalManualResolution,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `withdraw_hold_kind`（contracts/enums/fund.yaml）。
 * withdraw_holds.reason
 *
 * 未知取值解码为 [WithdrawHoldKind.UNKNOWN]，不抛错。
 */
@Serializable(with = WithdrawHoldKindSerializer::class)
enum class WithdrawHoldKind(
    val raw: kotlin.String,
) {
    MANUAL("manual"),
    RECON_DIFF("recon_diff"),
    LEDGER_MISMATCH("ledger_mismatch"),
    MANUAL_FAILED_WATCH("manual_failed_watch"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<WithdrawHoldKind> =
            listOf(
                MANUAL,
                RECON_DIFF,
                LEDGER_MISMATCH,
                MANUAL_FAILED_WATCH,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, WithdrawHoldKind> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): WithdrawHoldKind = byRaw[raw] ?: UNKNOWN
    }
}

/** [WithdrawHoldKind] 的序列化器：未知字符串得 [WithdrawHoldKind.UNKNOWN]；序列化写 raw。 */
object WithdrawHoldKindSerializer : KSerializer<WithdrawHoldKind> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.WithdrawHoldKind", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): WithdrawHoldKind = WithdrawHoldKind.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: WithdrawHoldKind,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `withdrawal_review_mode`（contracts/enums/fund.yaml）。
 *
 * 未知取值解码为 [WithdrawalReviewMode.UNKNOWN]，不抛错。
 */
@Serializable(with = WithdrawalReviewModeSerializer::class)
enum class WithdrawalReviewMode(
    val raw: kotlin.String,
) {
    AUTO("auto"),
    MANUAL("manual"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<WithdrawalReviewMode> =
            listOf(
                AUTO,
                MANUAL,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, WithdrawalReviewMode> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): WithdrawalReviewMode = byRaw[raw] ?: UNKNOWN
    }
}

/** [WithdrawalReviewMode] 的序列化器：未知字符串得 [WithdrawalReviewMode.UNKNOWN]；序列化写 raw。 */
object WithdrawalReviewModeSerializer : KSerializer<WithdrawalReviewMode> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.WithdrawalReviewMode", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): WithdrawalReviewMode = WithdrawalReviewMode.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: WithdrawalReviewMode,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `payout_method`（contracts/enums/fund.yaml）。
 * payout_accounts.payout_method 与 withdrawals.payout_channel
 *
 * 未知取值解码为 [PayoutMethod.UNKNOWN]，不抛错。
 */
@Serializable(with = PayoutMethodSerializer::class)
enum class PayoutMethod(
    val raw: kotlin.String,
) {
    ALIPAY("alipay"),
    BANK_CARD("bank_card"),
    WECHAT("wechat"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<PayoutMethod> =
            listOf(
                ALIPAY,
                BANK_CARD,
                WECHAT,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, PayoutMethod> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): PayoutMethod = byRaw[raw] ?: UNKNOWN
    }
}

/** [PayoutMethod] 的序列化器：未知字符串得 [PayoutMethod.UNKNOWN]；序列化写 raw。 */
object PayoutMethodSerializer : KSerializer<PayoutMethod> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.PayoutMethod", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): PayoutMethod = PayoutMethod.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: PayoutMethod,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `withdrawal_channel_state`（contracts/enums/fund.yaml）。
 * withdrawals.channel_state，可空，只有微信零钱单在 PAYING 期间有值；不是提现状态（withdrawal_status 不变）
 *
 * 未知取值解码为 [WithdrawalChannelState.UNKNOWN]，不抛错。
 */
@Serializable(with = WithdrawalChannelStateSerializer::class)
enum class WithdrawalChannelState(
    val raw: kotlin.String,
) {
    PROCESSING("processing"),
    WAIT_USER_CONFIRM("wait_user_confirm"),
    CANCELING("canceling"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<WithdrawalChannelState> =
            listOf(
                PROCESSING,
                WAIT_USER_CONFIRM,
                CANCELING,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, WithdrawalChannelState> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): WithdrawalChannelState = byRaw[raw] ?: UNKNOWN
    }
}

/** [WithdrawalChannelState] 的序列化器：未知字符串得 [WithdrawalChannelState.UNKNOWN]；序列化写 raw。 */
object WithdrawalChannelStateSerializer : KSerializer<WithdrawalChannelState> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.WithdrawalChannelState", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): WithdrawalChannelState = WithdrawalChannelState.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: WithdrawalChannelState,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `withdrawal_sub_text_branch`（contracts/enums/fund.yaml）。
 * 提现单副文案分支 key，由服务端按 BR-WDR-25 分支表自上而下取第一条匹配的行算出，取该行 sub_text_branch 列的值；本枚举与该列的取值逐一对应，分支条件与措辞只在 BR-WDR-25 与 /v1/dict 维护，这里只列 key、不按行号引用；客户端遇未知值按 processing 显示
 *
 * 未知取值解码为 [WithdrawalSubTextBranch.UNKNOWN]，不抛错。
 */
@Serializable(with = WithdrawalSubTextBranchSerializer::class)
enum class WithdrawalSubTextBranch(
    val raw: kotlin.String,
) {
    PROCESSING("processing"),
    WECHAT_WAIT_CONFIRM("wechat_wait_confirm"),
    WECHAT_CONFIRM_EXPIRED("wechat_confirm_expired"),
    WECHAT_CANCELING("wechat_canceling"),
    WECHAT_PROCESSING("wechat_processing"),
    AUTO_EXPECTED_SOON("auto_expected_soon"),
    MANUAL_REVIEW_PENDING("manual_review_pending"),
    MANUAL_REVIEW_OVERDUE("manual_review_overdue"),
    CHANNEL_PROCESSING("channel_processing"),
    PAYING_DELAYED("paying_delayed"),
    PAID_API("paid_api"),
    PAID_MANUAL("paid_manual"),
    REJECTED_OFFSET("rejected_offset"),
    REJECTED("rejected"),
    FAILED_ACCOUNT("failed_account"),
    FAILED_OTHER("failed_other"),
    FAILED_MANUAL("failed_manual"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<WithdrawalSubTextBranch> =
            listOf(
                PROCESSING,
                WECHAT_WAIT_CONFIRM,
                WECHAT_CONFIRM_EXPIRED,
                WECHAT_CANCELING,
                WECHAT_PROCESSING,
                AUTO_EXPECTED_SOON,
                MANUAL_REVIEW_PENDING,
                MANUAL_REVIEW_OVERDUE,
                CHANNEL_PROCESSING,
                PAYING_DELAYED,
                PAID_API,
                PAID_MANUAL,
                REJECTED_OFFSET,
                REJECTED,
                FAILED_ACCOUNT,
                FAILED_OTHER,
                FAILED_MANUAL,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, WithdrawalSubTextBranch> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): WithdrawalSubTextBranch = byRaw[raw] ?: UNKNOWN
    }
}

/** [WithdrawalSubTextBranch] 的序列化器：未知字符串得 [WithdrawalSubTextBranch.UNKNOWN]；序列化写 raw。 */
object WithdrawalSubTextBranchSerializer : KSerializer<WithdrawalSubTextBranch> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.WithdrawalSubTextBranch", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): WithdrawalSubTextBranch = WithdrawalSubTextBranch.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: WithdrawalSubTextBranch,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `payout_attempt_kind`（contracts/enums/fund.yaml）。
 * payout_attempts.kind；cancel 只用于微信零钱单的撤销
 *
 * 未知取值解码为 [PayoutAttemptKind.UNKNOWN]，不抛错。
 */
@Serializable(with = PayoutAttemptKindSerializer::class)
enum class PayoutAttemptKind(
    val raw: kotlin.String,
) {
    TRANSFER("transfer"),
    QUERY("query"),
    CANCEL("cancel"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<PayoutAttemptKind> =
            listOf(
                TRANSFER,
                QUERY,
                CANCEL,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, PayoutAttemptKind> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): PayoutAttemptKind = byRaw[raw] ?: UNKNOWN
    }
}

/** [PayoutAttemptKind] 的序列化器：未知字符串得 [PayoutAttemptKind.UNKNOWN]；序列化写 raw。 */
object PayoutAttemptKindSerializer : KSerializer<PayoutAttemptKind> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.PayoutAttemptKind", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): PayoutAttemptKind = PayoutAttemptKind.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: PayoutAttemptKind,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `withdraw_condition_reason`（contracts/enums/fund.yaml）。
 * 30303 的 data.reason（文案键 error.30303.<reason>，BR-TEXT-14）；编码由契约任务定（contract-delta b2-24）
 *
 * 未知取值解码为 [WithdrawConditionReason.UNKNOWN]，不抛错。
 */
@Serializable(with = WithdrawConditionReasonSerializer::class)
enum class WithdrawConditionReason(
    val raw: kotlin.String,
) {
    ACCOUNT_FROZEN("account_frozen"),
    BELOW_MIN("below_min"),
    NOT_MULTIPLE("not_multiple"),
    ABOVE_MAX("above_max"),
    ABOVE_METHOD_MAX("above_method_max"),
    NET_TOO_SMALL("net_too_small"),
    DAILY_COUNT("daily_count"),
    MONTHLY_COUNT("monthly_count"),
    PAYEE_DAILY_COUNT("payee_daily_count"),
    PAYEE_DAILY_USERS("payee_daily_users"),
    SELF_PURCHASE_REQUIRED("self_purchase_required"),
    PAYOUT_ACCOUNT_CHANGE_LIMIT("payout_account_change_limit"),
    PAYOUT_ACCOUNT_VERIFY_LIMIT("payout_account_verify_limit"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<WithdrawConditionReason> =
            listOf(
                ACCOUNT_FROZEN,
                BELOW_MIN,
                NOT_MULTIPLE,
                ABOVE_MAX,
                ABOVE_METHOD_MAX,
                NET_TOO_SMALL,
                DAILY_COUNT,
                MONTHLY_COUNT,
                PAYEE_DAILY_COUNT,
                PAYEE_DAILY_USERS,
                SELF_PURCHASE_REQUIRED,
                PAYOUT_ACCOUNT_CHANGE_LIMIT,
                PAYOUT_ACCOUNT_VERIFY_LIMIT,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, WithdrawConditionReason> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): WithdrawConditionReason = byRaw[raw] ?: UNKNOWN
    }
}

/** [WithdrawConditionReason] 的序列化器：未知字符串得 [WithdrawConditionReason.UNKNOWN]；序列化写 raw。 */
object WithdrawConditionReasonSerializer : KSerializer<WithdrawConditionReason> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.WithdrawConditionReason", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): WithdrawConditionReason = WithdrawConditionReason.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: WithdrawConditionReason,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `payout_batch_kind`（contracts/enums/fund.yaml）。
 *
 * 未知取值解码为 [PayoutBatchKind.UNKNOWN]，不抛错。
 */
@Serializable(with = PayoutBatchKindSerializer::class)
enum class PayoutBatchKind(
    val raw: kotlin.String,
) {
    MANUAL("manual"),
    AUTO("auto"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<PayoutBatchKind> =
            listOf(
                MANUAL,
                AUTO,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, PayoutBatchKind> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): PayoutBatchKind = byRaw[raw] ?: UNKNOWN
    }
}

/** [PayoutBatchKind] 的序列化器：未知字符串得 [PayoutBatchKind.UNKNOWN]；序列化写 raw。 */
object PayoutBatchKindSerializer : KSerializer<PayoutBatchKind> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.PayoutBatchKind", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): PayoutBatchKind = PayoutBatchKind.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: PayoutBatchKind,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `bad_debt_writeoff_status`（contracts/enums/fund.yaml）。
 *
 * 未知取值解码为 [BadDebtWriteoffStatus.UNKNOWN]，不抛错。
 */
@Serializable(with = BadDebtWriteoffStatusSerializer::class)
enum class BadDebtWriteoffStatus(
    val raw: kotlin.String,
) {
    PENDING("PENDING"),
    APPROVED("APPROVED"),
    CANCELLED("CANCELLED"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<BadDebtWriteoffStatus> =
            listOf(
                PENDING,
                APPROVED,
                CANCELLED,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, BadDebtWriteoffStatus> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): BadDebtWriteoffStatus = byRaw[raw] ?: UNKNOWN
    }
}

/** [BadDebtWriteoffStatus] 的序列化器：未知字符串得 [BadDebtWriteoffStatus.UNKNOWN]；序列化写 raw。 */
object BadDebtWriteoffStatusSerializer : KSerializer<BadDebtWriteoffStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.BadDebtWriteoffStatus", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): BadDebtWriteoffStatus = BadDebtWriteoffStatus.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: BadDebtWriteoffStatus,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `settle_batch_status`（contracts/enums/fund.yaml）。
 * 月结账单状态由批次派生、不入库
 *
 * 未知取值解码为 [SettleBatchStatus.UNKNOWN]，不抛错。
 */
@Serializable(with = SettleBatchStatusSerializer::class)
enum class SettleBatchStatus(
    val raw: kotlin.String,
) {
    DRAFT("DRAFT"),
    CONFIRMED("CONFIRMED"),
    EXECUTING("EXECUTING"),
    PARTIAL("PARTIAL"),
    DONE("DONE"),
    CANCELLED("CANCELLED"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<SettleBatchStatus> =
            listOf(
                DRAFT,
                CONFIRMED,
                EXECUTING,
                PARTIAL,
                DONE,
                CANCELLED,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, SettleBatchStatus> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): SettleBatchStatus = byRaw[raw] ?: UNKNOWN
    }
}

/** [SettleBatchStatus] 的序列化器：未知字符串得 [SettleBatchStatus.UNKNOWN]；序列化写 raw。 */
object SettleBatchStatusSerializer : KSerializer<SettleBatchStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.SettleBatchStatus", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): SettleBatchStatus = SettleBatchStatus.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: SettleBatchStatus,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `settle_mode`（contracts/enums/fund.yaml）。
 *
 * 未知取值解码为 [SettleMode.UNKNOWN]，不抛错。
 */
@Serializable(with = SettleModeSerializer::class)
enum class SettleMode(
    val raw: kotlin.String,
) {
    IMMEDIATE("IMMEDIATE"),
    SCHEDULED("SCHEDULED"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<SettleMode> =
            listOf(
                IMMEDIATE,
                SCHEDULED,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, SettleMode> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): SettleMode = byRaw[raw] ?: UNKNOWN
    }
}

/** [SettleMode] 的序列化器：未知字符串得 [SettleMode.UNKNOWN]；序列化写 raw。 */
object SettleModeSerializer : KSerializer<SettleMode> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.SettleMode", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): SettleMode = SettleMode.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: SettleMode,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `settle_batch_item_result`（contracts/enums/fund.yaml）。
 *
 * 未知取值解码为 [SettleBatchItemResult.UNKNOWN]，不抛错。
 */
@Serializable(with = SettleBatchItemResultSerializer::class)
enum class SettleBatchItemResult(
    val raw: kotlin.String,
) {
    CREDITED("credited"),
    SKIPPED("skipped"),
    DEFERRED("deferred"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<SettleBatchItemResult> =
            listOf(
                CREDITED,
                SKIPPED,
                DEFERRED,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, SettleBatchItemResult> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): SettleBatchItemResult = byRaw[raw] ?: UNKNOWN
    }
}

/** [SettleBatchItemResult] 的序列化器：未知字符串得 [SettleBatchItemResult.UNKNOWN]；序列化写 raw。 */
object SettleBatchItemResultSerializer : KSerializer<SettleBatchItemResult> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.SettleBatchItemResult", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): SettleBatchItemResult = SettleBatchItemResult.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: SettleBatchItemResult,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `settle_batch_item_type`（contracts/enums/fund.yaml）。
 * settle_batch_items.item_type
 *
 * 未知取值解码为 [SettleBatchItemType.UNKNOWN]，不抛错。
 */
@Serializable(with = SettleBatchItemTypeSerializer::class)
enum class SettleBatchItemType(
    val raw: kotlin.String,
) {
    ORDER("order"),
    BENEFICIARY("beneficiary"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<SettleBatchItemType> =
            listOf(
                ORDER,
                BENEFICIARY,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, SettleBatchItemType> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): SettleBatchItemType = byRaw[raw] ?: UNKNOWN
    }
}

/** [SettleBatchItemType] 的序列化器：未知字符串得 [SettleBatchItemType.UNKNOWN]；序列化写 raw。 */
object SettleBatchItemTypeSerializer : KSerializer<SettleBatchItemType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.SettleBatchItemType", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): SettleBatchItemType = SettleBatchItemType.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: SettleBatchItemType,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `settle_adjustment_status`（contracts/enums/fund.yaml）。
 * settle_adjustments.status
 *
 * 未知取值解码为 [SettleAdjustmentStatus.UNKNOWN]，不抛错。
 */
@Serializable(with = SettleAdjustmentStatusSerializer::class)
enum class SettleAdjustmentStatus(
    val raw: kotlin.String,
) {
    PENDING("pending"),
    APPROVED("approved"),
    REJECTED("rejected"),
    VOIDED_STALE_SEQ("voided_stale_seq"),
    VOIDED_BY_CLAWBACK("voided_by_clawback"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<SettleAdjustmentStatus> =
            listOf(
                PENDING,
                APPROVED,
                REJECTED,
                VOIDED_STALE_SEQ,
                VOIDED_BY_CLAWBACK,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, SettleAdjustmentStatus> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): SettleAdjustmentStatus = byRaw[raw] ?: UNKNOWN
    }
}

/** [SettleAdjustmentStatus] 的序列化器：未知字符串得 [SettleAdjustmentStatus.UNKNOWN]；序列化写 raw。 */
object SettleAdjustmentStatusSerializer : KSerializer<SettleAdjustmentStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.SettleAdjustmentStatus", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): SettleAdjustmentStatus = SettleAdjustmentStatus.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: SettleAdjustmentStatus,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `beneficiary_credit_kind`（contracts/enums/fund.yaml）。
 * beneficiary_credits.kind
 *
 * 未知取值解码为 [BeneficiaryCreditKind.UNKNOWN]，不抛错。
 */
@Serializable(with = BeneficiaryCreditKindSerializer::class)
enum class BeneficiaryCreditKind(
    val raw: kotlin.String,
) {
    FIRST_CREDIT("first_credit"),
    REASSIGN_CREDIT("reassign_credit"),
    DEFERRED("deferred"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<BeneficiaryCreditKind> =
            listOf(
                FIRST_CREDIT,
                REASSIGN_CREDIT,
                DEFERRED,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, BeneficiaryCreditKind> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): BeneficiaryCreditKind = byRaw[raw] ?: UNKNOWN
    }
}

/** [BeneficiaryCreditKind] 的序列化器：未知字符串得 [BeneficiaryCreditKind.UNKNOWN]；序列化写 raw。 */
object BeneficiaryCreditKindSerializer : KSerializer<BeneficiaryCreditKind> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.BeneficiaryCreditKind", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): BeneficiaryCreditKind = BeneficiaryCreditKind.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: BeneficiaryCreditKind,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `beneficiary_credit_status`（contracts/enums/fund.yaml）。
 * beneficiary_credits.status
 *
 * 未知取值解码为 [BeneficiaryCreditStatus.UNKNOWN]，不抛错。
 */
@Serializable(with = BeneficiaryCreditStatusSerializer::class)
enum class BeneficiaryCreditStatus(
    val raw: kotlin.String,
) {
    OPEN("open"),
    DONE("done"),
    FORFEITED("forfeited"),
    VOIDED("voided"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<BeneficiaryCreditStatus> =
            listOf(
                OPEN,
                DONE,
                FORFEITED,
                VOIDED,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, BeneficiaryCreditStatus> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): BeneficiaryCreditStatus = byRaw[raw] ?: UNKNOWN
    }
}

/** [BeneficiaryCreditStatus] 的序列化器：未知字符串得 [BeneficiaryCreditStatus.UNKNOWN]；序列化写 raw。 */
object BeneficiaryCreditStatusSerializer : KSerializer<BeneficiaryCreditStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.BeneficiaryCreditStatus", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): BeneficiaryCreditStatus = BeneficiaryCreditStatus.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: BeneficiaryCreditStatus,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `recon_diff_type`（contracts/enums/fund.yaml）。
 *
 * 未知取值解码为 [ReconDiffType.UNKNOWN]，不抛错。
 */
@Serializable(with = ReconDiffTypeSerializer::class)
enum class ReconDiffType(
    val raw: kotlin.String,
) {
    UNION_MISMATCH("union_mismatch"),
    CHANNEL_MISMATCH("channel_mismatch"),
    LEDGER_MISMATCH("ledger_mismatch"),
    PLATFORM_RESTORE("platform_restore"),
    MONTH_END_UNBALANCED("month_end_unbalanced"),
    SETTLE_AMOUNT_MISMATCH("settle_amount_mismatch"),
    SETTLE_MISSING("settle_missing"),
    APPEAL_RESTORE("appeal_restore"),
    ESTIMATE_CHANGED_AFTER_CREDIT("estimate_changed_after_credit"),
    PAY_MISMATCH("pay_mismatch"),
    PAY_ANOMALY("pay_anomaly"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<ReconDiffType> =
            listOf(
                UNION_MISMATCH,
                CHANNEL_MISMATCH,
                LEDGER_MISMATCH,
                PLATFORM_RESTORE,
                MONTH_END_UNBALANCED,
                SETTLE_AMOUNT_MISMATCH,
                SETTLE_MISSING,
                APPEAL_RESTORE,
                ESTIMATE_CHANGED_AFTER_CREDIT,
                PAY_MISMATCH,
                PAY_ANOMALY,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, ReconDiffType> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): ReconDiffType = byRaw[raw] ?: UNKNOWN
    }
}

/** [ReconDiffType] 的序列化器：未知字符串得 [ReconDiffType.UNKNOWN]；序列化写 raw。 */
object ReconDiffTypeSerializer : KSerializer<ReconDiffType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.ReconDiffType", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): ReconDiffType = ReconDiffType.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: ReconDiffType,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `payout_batch_item_result`（contracts/enums/fund.yaml）。
 * payout_batch_items.result；只插入不改，改挂到别的批次时原批次记一行 moved_out
 *
 * 未知取值解码为 [PayoutBatchItemResult.UNKNOWN]，不抛错。
 */
@Serializable(with = PayoutBatchItemResultSerializer::class)
enum class PayoutBatchItemResult(
    val raw: kotlin.String,
) {
    PAYING("paying"),
    SKIPPED("skipped"),
    BLOCKED("blocked"),
    MOVED_OUT("moved_out"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<PayoutBatchItemResult> =
            listOf(
                PAYING,
                SKIPPED,
                BLOCKED,
                MOVED_OUT,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, PayoutBatchItemResult> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): PayoutBatchItemResult = byRaw[raw] ?: UNKNOWN
    }
}

/** [PayoutBatchItemResult] 的序列化器：未知字符串得 [PayoutBatchItemResult.UNKNOWN]；序列化写 raw。 */
object PayoutBatchItemResultSerializer : KSerializer<PayoutBatchItemResult> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.PayoutBatchItemResult", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): PayoutBatchItemResult = PayoutBatchItemResult.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: PayoutBatchItemResult,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `recon_diff_status`（contracts/enums/fund.yaml）。
 *
 * 未知取值解码为 [ReconDiffStatus.UNKNOWN]，不抛错。
 */
@Serializable(with = ReconDiffStatusSerializer::class)
enum class ReconDiffStatus(
    val raw: kotlin.String,
) {
    OPEN("open"),
    PROCESSING("processing"),
    ADJUSTED("adjusted"),
    WRITTEN_OFF("written_off"),
    CLOSED("closed"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<ReconDiffStatus> =
            listOf(
                OPEN,
                PROCESSING,
                ADJUSTED,
                WRITTEN_OFF,
                CLOSED,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, ReconDiffStatus> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): ReconDiffStatus = byRaw[raw] ?: UNKNOWN
    }
}

/** [ReconDiffStatus] 的序列化器：未知字符串得 [ReconDiffStatus.UNKNOWN]；序列化写 raw。 */
object ReconDiffStatusSerializer : KSerializer<ReconDiffStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.ReconDiffStatus", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): ReconDiffStatus = ReconDiffStatus.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: ReconDiffStatus,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `union_receipt_method`（contracts/enums/fund.yaml）。
 * 联盟回款记录的登记方式；同一笔回款只登记一条
 *
 * 未知取值解码为 [UnionReceiptMethod.UNKNOWN]，不抛错。
 */
@Serializable(with = UnionReceiptMethodSerializer::class)
enum class UnionReceiptMethod(
    val raw: kotlin.String,
) {
    ADMIN_ENTRY("ADMIN_ENTRY"),
    R1_RECON("R1_RECON"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<UnionReceiptMethod> =
            listOf(
                ADMIN_ENTRY,
                R1_RECON,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, UnionReceiptMethod> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): UnionReceiptMethod = byRaw[raw] ?: UNKNOWN
    }
}

/** [UnionReceiptMethod] 的序列化器：未知字符串得 [UnionReceiptMethod.UNKNOWN]；序列化写 raw。 */
object UnionReceiptMethodSerializer : KSerializer<UnionReceiptMethod> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.UnionReceiptMethod", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): UnionReceiptMethod = UnionReceiptMethod.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: UnionReceiptMethod,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `union_receipt_cash_account`（contracts/enums/fund.yaml）。
 * 联盟回款借记的现金科目；银行卡通道未开通时不接受 CASH_BANK
 *
 * 未知取值解码为 [UnionReceiptCashAccount.UNKNOWN]，不抛错。
 */
@Serializable(with = UnionReceiptCashAccountSerializer::class)
enum class UnionReceiptCashAccount(
    val raw: kotlin.String,
) {
    CASH_ALIPAY("CASH_ALIPAY"),
    CASH_BANK("CASH_BANK"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<UnionReceiptCashAccount> =
            listOf(
                CASH_ALIPAY,
                CASH_BANK,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, UnionReceiptCashAccount> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): UnionReceiptCashAccount = byRaw[raw] ?: UNKNOWN
    }
}

/** [UnionReceiptCashAccount] 的序列化器：未知字符串得 [UnionReceiptCashAccount.UNKNOWN]；序列化写 raw。 */
object UnionReceiptCashAccountSerializer : KSerializer<UnionReceiptCashAccount> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.UnionReceiptCashAccount", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): UnionReceiptCashAccount = UnionReceiptCashAccount.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: UnionReceiptCashAccount,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `admin_fund_water_level`（contracts/enums/fund.yaml）。
 * 资金台账概览的水位结论；W 或 P 没有、或 W 已过期时不下结论（字段为 null），未回款超告警线不在此枚举
 *
 * 未知取值解码为 [AdminFundWaterLevel.UNKNOWN]，不抛错。
 */
@Serializable(with = AdminFundWaterLevelSerializer::class)
enum class AdminFundWaterLevel(
    val raw: kotlin.String,
) {
    NORMAL("normal"),
    LOW("low"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<AdminFundWaterLevel> =
            listOf(
                NORMAL,
                LOW,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, AdminFundWaterLevel> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): AdminFundWaterLevel = byRaw[raw] ?: UNKNOWN
    }
}

/** [AdminFundWaterLevel] 的序列化器：未知字符串得 [AdminFundWaterLevel.UNKNOWN]；序列化写 raw。 */
object AdminFundWaterLevelSerializer : KSerializer<AdminFundWaterLevel> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.AdminFundWaterLevel", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): AdminFundWaterLevel = AdminFundWaterLevel.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: AdminFundWaterLevel,
    ) {
        encoder.encodeString(value.raw)
    }
}
