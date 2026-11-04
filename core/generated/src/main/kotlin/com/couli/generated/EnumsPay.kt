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
 * 契约枚举 `pay_channel`（contracts/enums/pay.yaml）。
 * pay_orders.pay_channel 等；与 payout_channel 无关
 *
 * 未知取值解码为 [PayChannel.UNKNOWN]，不抛错。
 */
@Serializable(with = PayChannelSerializer::class)
enum class PayChannel(
    val raw: kotlin.String,
) {
    WECHAT_PAY("wechat_pay"),
    ALIPAY("alipay"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<PayChannel> =
            listOf(
                WECHAT_PAY,
                ALIPAY,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, PayChannel> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): PayChannel = byRaw[raw] ?: UNKNOWN
    }
}

/** [PayChannel] 的序列化器：未知字符串得 [PayChannel.UNKNOWN]；序列化写 raw。 */
object PayChannelSerializer : KSerializer<PayChannel> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.PayChannel", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): PayChannel = PayChannel.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: PayChannel,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `pay_order_status`（contracts/enums/pay.yaml）。
 * pay_orders.status；PAID、CLOSED 为终态
 *
 * 未知取值解码为 [PayOrderStatus.UNKNOWN]，不抛错。
 */
@Serializable(with = PayOrderStatusSerializer::class)
enum class PayOrderStatus(
    val raw: kotlin.String,
) {
    PENDING("PENDING"),
    PAID("PAID"),
    CLOSED("CLOSED"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<PayOrderStatus> =
            listOf(
                PENDING,
                PAID,
                CLOSED,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, PayOrderStatus> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): PayOrderStatus = byRaw[raw] ?: UNKNOWN
    }
}

/** [PayOrderStatus] 的序列化器：未知字符串得 [PayOrderStatus.UNKNOWN]；序列化写 raw。 */
object PayOrderStatusSerializer : KSerializer<PayOrderStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.PayOrderStatus", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): PayOrderStatus = PayOrderStatus.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: PayOrderStatus,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `pay_refund_status`（contracts/enums/pay.yaml）。
 * pay_refunds.status
 *
 * 未知取值解码为 [PayRefundStatus.UNKNOWN]，不抛错。
 */
@Serializable(with = PayRefundStatusSerializer::class)
enum class PayRefundStatus(
    val raw: kotlin.String,
) {
    PROCESSING("PROCESSING"),
    SUCCESS("SUCCESS"),
    FAILED("FAILED"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<PayRefundStatus> =
            listOf(
                PROCESSING,
                SUCCESS,
                FAILED,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, PayRefundStatus> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): PayRefundStatus = byRaw[raw] ?: UNKNOWN
    }
}

/** [PayRefundStatus] 的序列化器：未知字符串得 [PayRefundStatus.UNKNOWN]；序列化写 raw。 */
object PayRefundStatusSerializer : KSerializer<PayRefundStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.PayRefundStatus", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): PayRefundStatus = PayRefundStatus.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: PayRefundStatus,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `pay_refund_target_type`（contracts/enums/pay.yaml）。
 * pay_refunds.target_type；退款对象是一张 PAID 的支付单，或一条异常收款（BR-PAY-05 ④）
 *
 * 未知取值解码为 [PayRefundTargetType.UNKNOWN]，不抛错。
 */
@Serializable(with = PayRefundTargetTypeSerializer::class)
enum class PayRefundTargetType(
    val raw: kotlin.String,
) {
    PAY_ORDER("pay_order"),
    PAY_ANOMALY("pay_anomaly"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<PayRefundTargetType> =
            listOf(
                PAY_ORDER,
                PAY_ANOMALY,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, PayRefundTargetType> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): PayRefundTargetType = byRaw[raw] ?: UNKNOWN
    }
}

/** [PayRefundTargetType] 的序列化器：未知字符串得 [PayRefundTargetType.UNKNOWN]；序列化写 raw。 */
object PayRefundTargetTypeSerializer : KSerializer<PayRefundTargetType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.PayRefundTargetType", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): PayRefundTargetType = PayRefundTargetType.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: PayRefundTargetType,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `pay_query_attempt_kind`（contracts/enums/pay.yaml）。
 * pay_query_attempts.kind
 *
 * 未知取值解码为 [PayQueryAttemptKind.UNKNOWN]，不抛错。
 */
@Serializable(with = PayQueryAttemptKindSerializer::class)
enum class PayQueryAttemptKind(
    val raw: kotlin.String,
) {
    QUERY("query"),
    CLOSE("close"),
    REFUND("refund"),
    REFUND_QUERY("refund_query"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<PayQueryAttemptKind> =
            listOf(
                QUERY,
                CLOSE,
                REFUND,
                REFUND_QUERY,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, PayQueryAttemptKind> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): PayQueryAttemptKind = byRaw[raw] ?: UNKNOWN
    }
}

/** [PayQueryAttemptKind] 的序列化器：未知字符串得 [PayQueryAttemptKind.UNKNOWN]；序列化写 raw。 */
object PayQueryAttemptKindSerializer : KSerializer<PayQueryAttemptKind> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.PayQueryAttemptKind", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): PayQueryAttemptKind = PayQueryAttemptKind.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: PayQueryAttemptKind,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `pay_ledger_type`（contracts/enums/pay.yaml）。
 * 收款凭证类型，只用于平台科目，不属于用户流水的 ledger_type，不出现在任何用户流水接口
 *
 * 未知取值解码为 [PayLedgerType.UNKNOWN]，不抛错。
 */
@Serializable(with = PayLedgerTypeSerializer::class)
enum class PayLedgerType(
    val raw: kotlin.String,
) {
    PAY_RECEIPT("PAY_RECEIPT"),
    PAY_REFUND("PAY_REFUND"),
    PAY_ANOMALY("PAY_ANOMALY"),
    PAY_FEE("PAY_FEE"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<PayLedgerType> =
            listOf(
                PAY_RECEIPT,
                PAY_REFUND,
                PAY_ANOMALY,
                PAY_FEE,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, PayLedgerType> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): PayLedgerType = byRaw[raw] ?: UNKNOWN
    }
}

/** [PayLedgerType] 的序列化器：未知字符串得 [PayLedgerType.UNKNOWN]；序列化写 raw。 */
object PayLedgerTypeSerializer : KSerializer<PayLedgerType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.PayLedgerType", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): PayLedgerType = PayLedgerType.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: PayLedgerType,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `pay_refund_reason`（contracts/enums/pay.yaml）。
 * pay_refunds.reason_code；后台退款必须选原因码，中文名见 texts.admin 的 admin_dict.pay_refund_reason
 *
 * 未知取值解码为 [PayRefundReason.UNKNOWN]，不抛错。
 */
@Serializable(with = PayRefundReasonSerializer::class)
enum class PayRefundReason(
    val raw: kotlin.String,
) {
    USER_REQUEST("USER_REQUEST"),
    DUPLICATE_PAYMENT("DUPLICATE_PAYMENT"),
    ANOMALY_RECEIPT("ANOMALY_RECEIPT"),
    RECON_DIFF("RECON_DIFF"),
    OTHER("OTHER"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<PayRefundReason> =
            listOf(
                USER_REQUEST,
                DUPLICATE_PAYMENT,
                ANOMALY_RECEIPT,
                RECON_DIFF,
                OTHER,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, PayRefundReason> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): PayRefundReason = byRaw[raw] ?: UNKNOWN
    }
}

/** [PayRefundReason] 的序列化器：未知字符串得 [PayRefundReason.UNKNOWN]；序列化写 raw。 */
object PayRefundReasonSerializer : KSerializer<PayRefundReason> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.PayRefundReason", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): PayRefundReason = PayRefundReason.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: PayRefundReason,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `pay_unavailable_reason`（contracts/enums/pay.yaml）。
 * 错误码 30901 的 data.reason；用户侧不区分原因，统一提示 error.30901
 *
 * 未知取值解码为 [PayUnavailableReason.UNKNOWN]，不抛错。
 */
@Serializable(with = PayUnavailableReasonSerializer::class)
enum class PayUnavailableReason(
    val raw: kotlin.String,
) {
    PAY_DISABLED("pay_disabled"),
    CHANNEL_DISABLED("channel_disabled"),
    PLATFORM_NOT_ALLOWED("platform_not_allowed"),
    BIZ_NOT_SELLABLE("biz_not_sellable"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<PayUnavailableReason> =
            listOf(
                PAY_DISABLED,
                CHANNEL_DISABLED,
                PLATFORM_NOT_ALLOWED,
                BIZ_NOT_SELLABLE,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, PayUnavailableReason> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): PayUnavailableReason = byRaw[raw] ?: UNKNOWN
    }
}

/** [PayUnavailableReason] 的序列化器：未知字符串得 [PayUnavailableReason.UNKNOWN]；序列化写 raw。 */
object PayUnavailableReasonSerializer : KSerializer<PayUnavailableReason> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.PayUnavailableReason", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): PayUnavailableReason = PayUnavailableReason.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: PayUnavailableReason,
    ) {
        encoder.encodeString(value.raw)
    }
}
