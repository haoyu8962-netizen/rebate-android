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
 * 契约枚举 `platform_status`（contracts/enums/order.yaml）。
 * 联盟订单状态，唯一写者 order-sync；SETTLED 只表示联盟结算，不表示回款或我方入账
 *
 * 未知取值解码为 [PlatformStatus.UNKNOWN]，不抛错。
 */
@Serializable(with = PlatformStatusSerializer::class)
enum class PlatformStatus(
    val raw: kotlin.String,
) {
    DEPOSIT_PAID("DEPOSIT_PAID"),
    PAID("PAID"),
    RECEIVED("RECEIVED"),
    SETTLED("SETTLED"),
    INVALID("INVALID"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<PlatformStatus> =
            listOf(
                DEPOSIT_PAID,
                PAID,
                RECEIVED,
                SETTLED,
                INVALID,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, PlatformStatus> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): PlatformStatus = byRaw[raw] ?: UNKNOWN
    }
}

/** [PlatformStatus] 的序列化器：未知字符串得 [PlatformStatus.UNKNOWN]；序列化写 raw。 */
object PlatformStatusSerializer : KSerializer<PlatformStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.PlatformStatus", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): PlatformStatus = PlatformStatus.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: PlatformStatus,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `rebate_status`（contracts/enums/order.yaml）。
 * 返利状态，唯一写者 settlement；VOID、CLAWED_BACK 为终态——自动事件不得离开，只有人工恢复 R12、R13 可以离开，R3b 在 VOID 上只写归属（BR-FUND-01）
 *
 * 未知取值解码为 [RebateStatus.UNKNOWN]，不抛错。
 */
@Serializable(with = RebateStatusSerializer::class)
enum class RebateStatus(
    val raw: kotlin.String,
) {
    UNATTRIBUTED("UNATTRIBUTED"),
    ESTIMATED("ESTIMATED"),
    WAITING("WAITING"),
    CREDITED("CREDITED"),
    VOID("VOID"),
    CLAWED_BACK("CLAWED_BACK"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<RebateStatus> =
            listOf(
                UNATTRIBUTED,
                ESTIMATED,
                WAITING,
                CREDITED,
                VOID,
                CLAWED_BACK,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, RebateStatus> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): RebateStatus = byRaw[raw] ?: UNKNOWN
    }
}

/** [RebateStatus] 的序列化器：未知字符串得 [RebateStatus.UNKNOWN]；序列化写 raw。 */
object RebateStatusSerializer : KSerializer<RebateStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.RebateStatus", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): RebateStatus = RebateStatus.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: RebateStatus,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `display_status`（contracts/enums/order.yaml）。
 * 用户可见状态，接口按 BR-FUND-17 派生表派生、不入库；文案取字典 order_status.<值>（BR-TEXT-02）；WAITING_SETTLE 在月结口径下停用，不进契约
 *
 * 未知取值解码为 [DisplayStatus.UNKNOWN]，不抛错。
 */
@Serializable(with = DisplayStatusSerializer::class)
enum class DisplayStatus(
    val raw: kotlin.String,
) {
    PAID("PAID"),
    DEPOSIT_PAID("DEPOSIT_PAID"),
    WAITING("WAITING"),
    CREDITING("CREDITING"),
    REVIEWING("REVIEWING"),
    RIGHTS_PENDING("RIGHTS_PENDING"),
    CREDITED("CREDITED"),
    CREDITED_PART_CLAWED("CREDITED_PART_CLAWED"),
    NO_REBATE("NO_REBATE"),
    INVALID("INVALID"),
    CLAWED_BACK("CLAWED_BACK"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<DisplayStatus> =
            listOf(
                PAID,
                DEPOSIT_PAID,
                WAITING,
                CREDITING,
                REVIEWING,
                RIGHTS_PENDING,
                CREDITED,
                CREDITED_PART_CLAWED,
                NO_REBATE,
                INVALID,
                CLAWED_BACK,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, DisplayStatus> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): DisplayStatus = byRaw[raw] ?: UNKNOWN
    }
}

/** [DisplayStatus] 的序列化器：未知字符串得 [DisplayStatus.UNKNOWN]；序列化写 raw。 */
object DisplayStatusSerializer : KSerializer<DisplayStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.DisplayStatus", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): DisplayStatus = DisplayStatus.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: DisplayStatus,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `order_hold_reason`（contracts/enums/order.yaml）。
 * orders.hold_reason，不改 rebate_status
 *
 * 未知取值解码为 [OrderHoldReason.UNKNOWN]，不抛错。
 */
@Serializable(with = OrderHoldReasonSerializer::class)
enum class OrderHoldReason(
    val raw: kotlin.String,
) {
    RISK("RISK"),
    CS("CS"),
    UNMAPPED_STATUS("UNMAPPED_STATUS"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<OrderHoldReason> =
            listOf(
                RISK,
                CS,
                UNMAPPED_STATUS,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, OrderHoldReason> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): OrderHoldReason = byRaw[raw] ?: UNKNOWN
    }
}

/** [OrderHoldReason] 的序列化器：未知字符串得 [OrderHoldReason.UNKNOWN]；序列化写 raw。 */
object OrderHoldReasonSerializer : KSerializer<OrderHoldReason> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.OrderHoldReason", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): OrderHoldReason = OrderHoldReason.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: OrderHoldReason,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `order_status_group`（contracts/enums/order.yaml）。
 * GET /v1/orders 的 status_group；各组包含哪些 display_status 只在 BR-TEXT-02 细则
 *
 * 未知取值解码为 [OrderStatusGroup.UNKNOWN]，不抛错。
 */
@Serializable(with = OrderStatusGroupSerializer::class)
enum class OrderStatusGroup(
    val raw: kotlin.String,
) {
    ALL("all"),
    ESTIMATING("estimating"),
    CREDITED("credited"),
    NO_REBATE("no_rebate"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<OrderStatusGroup> =
            listOf(
                ALL,
                ESTIMATING,
                CREDITED,
                NO_REBATE,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, OrderStatusGroup> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): OrderStatusGroup = byRaw[raw] ?: UNKNOWN
    }
}

/** [OrderStatusGroup] 的序列化器：未知字符串得 [OrderStatusGroup.UNKNOWN]；序列化写 raw。 */
object OrderStatusGroupSerializer : KSerializer<OrderStatusGroup> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.OrderStatusGroup", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): OrderStatusGroup = OrderStatusGroup.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: OrderStatusGroup,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `order_timeline_node`（contracts/enums/order.yaml）。
 * 订单详情时间线节点（编码代理自定；04 只点名 deposit_paid，final_paid 与 08 12_TEXT 文案表的键 order_timeline.final_paid 对齐（BR-TEXT-02 细则「时间线」））；未发生的节点 at 为空
 *
 * 未知取值解码为 [OrderTimelineNode.UNKNOWN]，不抛错。
 */
@Serializable(with = OrderTimelineNodeSerializer::class)
enum class OrderTimelineNode(
    val raw: kotlin.String,
) {
    DEPOSIT_PAID("deposit_paid"),
    PAID("paid"),
    FINAL_PAID("final_paid"),
    RECEIVED("received"),
    CREDIT_EXPECTED("credit_expected"),
    CREDITED("credited"),
    INVALID("invalid"),
    CLAWED_BACK("clawed_back"),
    PART_CLAWED_BACK("part_clawed_back"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<OrderTimelineNode> =
            listOf(
                DEPOSIT_PAID,
                PAID,
                FINAL_PAID,
                RECEIVED,
                CREDIT_EXPECTED,
                CREDITED,
                INVALID,
                CLAWED_BACK,
                PART_CLAWED_BACK,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, OrderTimelineNode> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): OrderTimelineNode = byRaw[raw] ?: UNKNOWN
    }
}

/** [OrderTimelineNode] 的序列化器：未知字符串得 [OrderTimelineNode.UNKNOWN]；序列化写 raw。 */
object OrderTimelineNodeSerializer : KSerializer<OrderTimelineNode> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.OrderTimelineNode", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): OrderTimelineNode = OrderTimelineNode.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: OrderTimelineNode,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `order_reason`（contracts/enums/order.yaml）。
 * 订单原因码；NOT_TRACKED、EXPIRED_CLICK、OTHER_TLJ、RELATION_INVALID 不写入 orders.reason。CANCELLED 待 09 实测，启用前不写入。rebate_status=CLAWED_BACK 同样写 orders.reason（迁移 R8 同事务写，取值按 BR-FUND-08 细则的映射表）
 *
 * 未知取值解码为 [OrderReason.UNKNOWN]，不抛错。
 */
@Serializable(with = OrderReasonSerializer::class)
enum class OrderReason(
    val raw: kotlin.String,
) {
    REFUND("REFUND"),
    RIGHTS("RIGHTS"),
    PUNISH("PUNISH"),
    PRESALE_UNPAID("PRESALE_UNPAID"),
    COMMISSION_ZERO("COMMISSION_ZERO"),
    OTHER("OTHER"),
    BLACKLIST("BLACKLIST"),
    PART_REFUND("PART_REFUND"),
    PRICE_COMPARE("PRICE_COMPARE"),
    PRICE_PROTECT("PRICE_PROTECT"),
    SETTLE_DIFF("SETTLE_DIFF"),
    NOT_TRACKED("NOT_TRACKED"),
    EXPIRED_CLICK("EXPIRED_CLICK"),
    OTHER_TLJ("OTHER_TLJ"),
    RELATION_INVALID("RELATION_INVALID"),
    CANCELLED("CANCELLED"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<OrderReason> =
            listOf(
                REFUND,
                RIGHTS,
                PUNISH,
                PRESALE_UNPAID,
                COMMISSION_ZERO,
                OTHER,
                BLACKLIST,
                PART_REFUND,
                PRICE_COMPARE,
                PRICE_PROTECT,
                SETTLE_DIFF,
                NOT_TRACKED,
                EXPIRED_CLICK,
                OTHER_TLJ,
                RELATION_INVALID,
                CANCELLED,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, OrderReason> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): OrderReason = byRaw[raw] ?: UNKNOWN
    }
}

/** [OrderReason] 的序列化器：未知字符串得 [OrderReason.UNKNOWN]；序列化写 raw。 */
object OrderReasonSerializer : KSerializer<OrderReason> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.OrderReason", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): OrderReason = OrderReason.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: OrderReason,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `order_reason_sub`（contracts/enums/order.yaml）。
 * orders.reason_sub，细分 orders.reason 的说明，不改金额、不改父编码的 kind 与状态迁移；每个值只属一个父编码（说明里写明），PUNISH 12 项、REFUND 2 项、RIGHTS 1 项，其余父编码没有子原因；「无」= 不写（null），「其他原因」= 父编码 OTHER，不另设子原因；用户文案取字典 order_reason_sub.<值>.desc；编码为凑狸自定
 *
 * 未知取值解码为 [OrderReasonSub.UNKNOWN]，不抛错。
 */
@Serializable(with = OrderReasonSubSerializer::class)
enum class OrderReasonSub(
    val raw: kotlin.String,
) {
    VIOLATION_PROMO("VIOLATION_PROMO"),
    SHOP_TK("SHOP_TK"),
    INSITE_PROMO("INSITE_PROMO"),
    PROXY_BUY("PROXY_BUY"),
    FAKE_TRADE("FAKE_TRADE"),
    INSITE_RESALE("INSITE_RESALE"),
    OVER_LIMIT("OVER_LIMIT"),
    MARKET_DISRUPT("MARKET_DISRUPT"),
    MULTI_TRADE_ABNORMAL("MULTI_TRADE_ABNORMAL"),
    SINGLE_TRADE_ABNORMAL("SINGLE_TRADE_ABNORMAL"),
    RISK_ORDER("RISK_ORDER"),
    PUNISH_ORDER("PUNISH_ORDER"),
    IN_SALE("IN_SALE"),
    AFTER_SETTLE("AFTER_SETTLE"),
    AFTER_SALE("AFTER_SALE"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<OrderReasonSub> =
            listOf(
                VIOLATION_PROMO,
                SHOP_TK,
                INSITE_PROMO,
                PROXY_BUY,
                FAKE_TRADE,
                INSITE_RESALE,
                OVER_LIMIT,
                MARKET_DISRUPT,
                MULTI_TRADE_ABNORMAL,
                SINGLE_TRADE_ABNORMAL,
                RISK_ORDER,
                PUNISH_ORDER,
                IN_SALE,
                AFTER_SETTLE,
                AFTER_SALE,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, OrderReasonSub> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): OrderReasonSub = byRaw[raw] ?: UNKNOWN
    }
}

/** [OrderReasonSub] 的序列化器：未知字符串得 [OrderReasonSub.UNKNOWN]；序列化写 raw。 */
object OrderReasonSubSerializer : KSerializer<OrderReasonSub> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.OrderReasonSub", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): OrderReasonSub = OrderReasonSub.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: OrderReasonSub,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `diff_reason`（contracts/enums/order.yaml）。
 * orders.diff_reason_code，差额原因只用这 4 个
 *
 * 未知取值解码为 [DiffReason.UNKNOWN]，不抛错。
 */
@Serializable(with = DiffReasonSerializer::class)
enum class DiffReason(
    val raw: kotlin.String,
) {
    PART_REFUND("PART_REFUND"),
    PRICE_COMPARE("PRICE_COMPARE"),
    PRICE_PROTECT("PRICE_PROTECT"),
    SETTLE_DIFF("SETTLE_DIFF"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<DiffReason> =
            listOf(
                PART_REFUND,
                PRICE_COMPARE,
                PRICE_PROTECT,
                SETTLE_DIFF,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, DiffReason> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): DiffReason = byRaw[raw] ?: UNKNOWN
    }
}

/** [DiffReason] 的序列化器：未知字符串得 [DiffReason.UNKNOWN]；序列化写 raw。 */
object DiffReasonSerializer : KSerializer<DiffReason> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.DiffReason", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): DiffReason = DiffReason.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: DiffReason,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `order_rights_status`（contracts/enums/order.yaml）。
 * order_rights.status；PROCESSING、WAIT_COMMISSION 使 rights_pending=true
 *
 * 未知取值解码为 [OrderRightsStatus.UNKNOWN]，不抛错。
 */
@Serializable(with = OrderRightsStatusSerializer::class)
enum class OrderRightsStatus(
    val raw: kotlin.String,
) {
    PROCESSING("PROCESSING"),
    WAIT_COMMISSION("WAIT_COMMISSION"),
    SUCCEEDED("SUCCEEDED"),
    FAILED("FAILED"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<OrderRightsStatus> =
            listOf(
                PROCESSING,
                WAIT_COMMISSION,
                SUCCEEDED,
                FAILED,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, OrderRightsStatus> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): OrderRightsStatus = byRaw[raw] ?: UNKNOWN
    }
}

/** [OrderRightsStatus] 的序列化器：未知字符串得 [OrderRightsStatus.UNKNOWN]；序列化写 raw。 */
object OrderRightsStatusSerializer : KSerializer<OrderRightsStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.OrderRightsStatus", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): OrderRightsStatus = OrderRightsStatus.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: OrderRightsStatus,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `order_rights_type`（contracts/enums/order.yaml）。
 * order_rights.type；处罚类平台码与处罚接口写 PUNISH；淘宝维权码到 order_rights.status 的映射待 CAP-TB-08
 *
 * 未知取值解码为 [OrderRightsType.UNKNOWN]，不抛错。
 */
@Serializable(with = OrderRightsTypeSerializer::class)
enum class OrderRightsType(
    val raw: kotlin.String,
) {
    RIGHTS("RIGHTS"),
    PUNISH("PUNISH"),
    INVALID_AFTER_SETTLE("INVALID_AFTER_SETTLE"),
    REFUND_AFTER_SETTLE("REFUND_AFTER_SETTLE"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<OrderRightsType> =
            listOf(
                RIGHTS,
                PUNISH,
                INVALID_AFTER_SETTLE,
                REFUND_AFTER_SETTLE,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, OrderRightsType> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): OrderRightsType = byRaw[raw] ?: UNKNOWN
    }
}

/** [OrderRightsType] 的序列化器：未知字符串得 [OrderRightsType.UNKNOWN]；序列化写 raw。 */
object OrderRightsTypeSerializer : KSerializer<OrderRightsType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.OrderRightsType", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): OrderRightsType = OrderRightsType.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: OrderRightsType,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `claim_status`（contracts/enums/order.yaml）。
 *
 * 未知取值解码为 [ClaimStatus.UNKNOWN]，不抛错。
 */
@Serializable(with = ClaimStatusSerializer::class)
enum class ClaimStatus(
    val raw: kotlin.String,
) {
    SUBMITTED("submitted"),
    AUTO_MATCHED("auto_matched"),
    MANUAL_REVIEW("manual_review"),
    APPROVED("approved"),
    REJECTED("rejected"),
    CANCELLED("cancelled"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<ClaimStatus> =
            listOf(
                SUBMITTED,
                AUTO_MATCHED,
                MANUAL_REVIEW,
                APPROVED,
                REJECTED,
                CANCELLED,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, ClaimStatus> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): ClaimStatus = byRaw[raw] ?: UNKNOWN
    }
}

/** [ClaimStatus] 的序列化器：未知字符串得 [ClaimStatus.UNKNOWN]；序列化写 raw。 */
object ClaimStatusSerializer : KSerializer<ClaimStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.ClaimStatus", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): ClaimStatus = ClaimStatus.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: ClaimStatus,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `claim_evidence_level`（contracts/enums/order.yaml）。
 *
 * 未知取值解码为 [ClaimEvidenceLevel.UNKNOWN]，不抛错。
 */
@Serializable(with = ClaimEvidenceLevelSerializer::class)
enum class ClaimEvidenceLevel(
    val raw: kotlin.String,
) {
    STRONG("strong"),
    WEAK("weak"),
    NONE("none"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<ClaimEvidenceLevel> =
            listOf(
                STRONG,
                WEAK,
                NONE,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, ClaimEvidenceLevel> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): ClaimEvidenceLevel = byRaw[raw] ?: UNKNOWN
    }
}

/** [ClaimEvidenceLevel] 的序列化器：未知字符串得 [ClaimEvidenceLevel.UNKNOWN]；序列化写 raw。 */
object ClaimEvidenceLevelSerializer : KSerializer<ClaimEvidenceLevel> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.ClaimEvidenceLevel", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): ClaimEvidenceLevel = ClaimEvidenceLevel.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: ClaimEvidenceLevel,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `claim_item_decision`（contracts/enums/order.yaml）。
 * claim_items.decision，待审为 null
 *
 * 未知取值解码为 [ClaimItemDecision.UNKNOWN]，不抛错。
 */
@Serializable(with = ClaimItemDecisionSerializer::class)
enum class ClaimItemDecision(
    val raw: kotlin.String,
) {
    APPROVED("approved"),
    REJECTED("rejected"),
    CANCELLED("cancelled"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<ClaimItemDecision> =
            listOf(
                APPROVED,
                REJECTED,
                CANCELLED,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, ClaimItemDecision> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): ClaimItemDecision = byRaw[raw] ?: UNKNOWN
    }
}

/** [ClaimItemDecision] 的序列化器：未知字符串得 [ClaimItemDecision.UNKNOWN]；序列化写 raw。 */
object ClaimItemDecisionSerializer : KSerializer<ClaimItemDecision> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.ClaimItemDecision", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): ClaimItemDecision = ClaimItemDecision.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: ClaimItemDecision,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `claim_reject_reason`（contracts/enums/order.yaml）。
 * 前 6 个客服可选，NOT_IN_POOL、ORDER_INVALID 系统专用
 *
 * 未知取值解码为 [ClaimRejectReason.UNKNOWN]，不抛错。
 */
@Serializable(with = ClaimRejectReasonSerializer::class)
enum class ClaimRejectReason(
    val raw: kotlin.String,
) {
    NO_EVIDENCE("NO_EVIDENCE"),
    EXPIRED_CLICK("EXPIRED_CLICK"),
    NOT_TRACKED("NOT_TRACKED"),
    OTHER_TLJ("OTHER_TLJ"),
    FRAUD("FRAUD"),
    OTHER("OTHER"),
    NOT_IN_POOL("NOT_IN_POOL"),
    ORDER_INVALID("ORDER_INVALID"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<ClaimRejectReason> =
            listOf(
                NO_EVIDENCE,
                EXPIRED_CLICK,
                NOT_TRACKED,
                OTHER_TLJ,
                FRAUD,
                OTHER,
                NOT_IN_POOL,
                ORDER_INVALID,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, ClaimRejectReason> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): ClaimRejectReason = byRaw[raw] ?: UNKNOWN
    }
}

/** [ClaimRejectReason] 的序列化器：未知字符串得 [ClaimRejectReason.UNKNOWN]；序列化写 raw。 */
object ClaimRejectReasonSerializer : KSerializer<ClaimRejectReason> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.ClaimRejectReason", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): ClaimRejectReason = ClaimRejectReason.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: ClaimRejectReason,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `order_sync_mode`（contracts/enums/order.yaml）。
 * 后台手动同步的方式（order_sync_requests.mode）；POST /admin/v1/order-syncs 的请求体二选一
 *
 * 未知取值解码为 [OrderSyncMode.UNKNOWN]，不抛错。
 */
@Serializable(with = OrderSyncModeSerializer::class)
enum class OrderSyncMode(
    val raw: kotlin.String,
) {
    WINDOW("window"),
    ORDER_NOS("order_nos"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<OrderSyncMode> =
            listOf(
                WINDOW,
                ORDER_NOS,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, OrderSyncMode> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): OrderSyncMode = byRaw[raw] ?: UNKNOWN
    }
}

/** [OrderSyncMode] 的序列化器：未知字符串得 [OrderSyncMode.UNKNOWN]；序列化写 raw。 */
object OrderSyncModeSerializer : KSerializer<OrderSyncMode> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.OrderSyncMode", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): OrderSyncMode = OrderSyncMode.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: OrderSyncMode,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `order_sync_status`（contracts/enums/order.yaml）。
 * 后台手动同步请求的状态（order_sync_requests.status）；同一筛选条件在 queued 或 running 期间再提交返回原请求
 *
 * 未知取值解码为 [OrderSyncStatus.UNKNOWN]，不抛错。
 */
@Serializable(with = OrderSyncStatusSerializer::class)
enum class OrderSyncStatus(
    val raw: kotlin.String,
) {
    QUEUED("queued"),
    RUNNING("running"),
    DONE("done"),
    FAILED("failed"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<OrderSyncStatus> =
            listOf(
                QUEUED,
                RUNNING,
                DONE,
                FAILED,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, OrderSyncStatus> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): OrderSyncStatus = byRaw[raw] ?: UNKNOWN
    }
}

/** [OrderSyncStatus] 的序列化器：未知字符串得 [OrderSyncStatus.UNKNOWN]；序列化写 raw。 */
object OrderSyncStatusSerializer : KSerializer<OrderSyncStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.OrderSyncStatus", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): OrderSyncStatus = OrderSyncStatus.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: OrderSyncStatus,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `rights_import_kind`（contracts/enums/order.yaml）。
 * 后台 Excel 导入的类型（rights_imports.kind）；channel 另需 risk.blocklist 与动态码（BR-ATTR-26 细则「渠道单导入」）
 *
 * 未知取值解码为 [RightsImportKind.UNKNOWN]，不抛错。
 */
@Serializable(with = RightsImportKindSerializer::class)
enum class RightsImportKind(
    val raw: kotlin.String,
) {
    RIGHTS("rights"),
    PUNISH("punish"),
    CHANNEL("channel"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<RightsImportKind> =
            listOf(
                RIGHTS,
                PUNISH,
                CHANNEL,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, RightsImportKind> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): RightsImportKind = byRaw[raw] ?: UNKNOWN
    }
}

/** [RightsImportKind] 的序列化器：未知字符串得 [RightsImportKind.UNKNOWN]；序列化写 raw。 */
object RightsImportKindSerializer : KSerializer<RightsImportKind> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.RightsImportKind", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): RightsImportKind = RightsImportKind.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: RightsImportKind,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `rights_import_time_basis`（contracts/enums/order.yaml）。
 * 维权 / 违规单导入按哪个时间筛选行（rights_imports.time_basis）；渠道单恒为空
 *
 * 未知取值解码为 [RightsImportTimeBasis.UNKNOWN]，不抛错。
 */
@Serializable(with = RightsImportTimeBasisSerializer::class)
enum class RightsImportTimeBasis(
    val raw: kotlin.String,
) {
    PAID("paid"),
    RECEIVED("received"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<RightsImportTimeBasis> =
            listOf(
                PAID,
                RECEIVED,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, RightsImportTimeBasis> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): RightsImportTimeBasis = byRaw[raw] ?: UNKNOWN
    }
}

/** [RightsImportTimeBasis] 的序列化器：未知字符串得 [RightsImportTimeBasis.UNKNOWN]；序列化写 raw。 */
object RightsImportTimeBasisSerializer : KSerializer<RightsImportTimeBasis> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.RightsImportTimeBasis", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): RightsImportTimeBasis = RightsImportTimeBasis.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: RightsImportTimeBasis,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `rights_import_status`（contracts/enums/order.yaml）。
 * 后台 Excel 导入任务的状态（rights_imports.status）
 *
 * 未知取值解码为 [RightsImportStatus.UNKNOWN]，不抛错。
 */
@Serializable(with = RightsImportStatusSerializer::class)
enum class RightsImportStatus(
    val raw: kotlin.String,
) {
    QUEUED("queued"),
    RUNNING("running"),
    DONE("done"),
    FAILED("failed"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<RightsImportStatus> =
            listOf(
                QUEUED,
                RUNNING,
                DONE,
                FAILED,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, RightsImportStatus> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): RightsImportStatus = byRaw[raw] ?: UNKNOWN
    }
}

/** [RightsImportStatus] 的序列化器：未知字符串得 [RightsImportStatus.UNKNOWN]；序列化写 raw。 */
object RightsImportStatusSerializer : KSerializer<RightsImportStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.RightsImportStatus", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): RightsImportStatus = RightsImportStatus.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: RightsImportStatus,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `rights_import_failure_reason`（contracts/enums/order.yaml）。
 * 导入逐行失败的原因（rights_import_failures.reason），是行原因枚举，不是错误码
 *
 * 未知取值解码为 [RightsImportFailureReason.UNKNOWN]，不抛错。
 */
@Serializable(with = RightsImportFailureReasonSerializer::class)
enum class RightsImportFailureReason(
    val raw: kotlin.String,
) {
    INVALID_ROW("invalid_row"),
    ORDER_NOT_FOUND("order_not_found"),
    SUB_ORDER_NO_MISSING("sub_order_no_missing"),
    RECEIVED_AT_MISSING("received_at_missing"),
    PLATFORM_MISMATCH("platform_mismatch"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<RightsImportFailureReason> =
            listOf(
                INVALID_ROW,
                ORDER_NOT_FOUND,
                SUB_ORDER_NO_MISSING,
                RECEIVED_AT_MISSING,
                PLATFORM_MISMATCH,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, RightsImportFailureReason> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): RightsImportFailureReason = byRaw[raw] ?: UNKNOWN
    }
}

/** [RightsImportFailureReason] 的序列化器：未知字符串得 [RightsImportFailureReason.UNKNOWN]；序列化写 raw。 */
object RightsImportFailureReasonSerializer : KSerializer<RightsImportFailureReason> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.RightsImportFailureReason", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): RightsImportFailureReason = RightsImportFailureReason.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: RightsImportFailureReason,
    ) {
        encoder.encodeString(value.raw)
    }
}
