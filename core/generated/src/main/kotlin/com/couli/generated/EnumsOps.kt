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
 * 契约枚举 `notify_template_code`（contracts/enums/ops.yaml）。
 * 消息模板编码；UNION_AUTH_EXPIRING、UNION_AUTH_EXPIRED 只发站长手机号，不属于用户通知分类
 *
 * 未知取值解码为 [NotifyTemplateCode.UNKNOWN]，不抛错。
 */
@Serializable(with = NotifyTemplateCodeSerializer::class)
enum class NotifyTemplateCode(
    val raw: kotlin.String,
) {
    ORDER_TRACKED("ORDER_TRACKED"),
    ORDER_INVALID("ORDER_INVALID"),
    CREDITED("CREDITED"),
    CLAWBACK("CLAWBACK"),
    WD_SUCCESS("WD_SUCCESS"),
    WD_REJECTED("WD_REJECTED"),
    WD_FAILED("WD_FAILED"),
    WD_OVERDUE("WD_OVERDUE"),
    CLAIM_RESULT("CLAIM_RESULT"),
    RISK_STATE_CHANGED("RISK_STATE_CHANGED"),
    APPEAL_RESULT("APPEAL_RESULT"),
    DELETION_PROGRESS("DELETION_PROGRESS"),
    BALANCE_ADJUSTED("BALANCE_ADJUSTED"),
    SMS_CODE("SMS_CODE"),
    UNION_AUTH_EXPIRING("UNION_AUTH_EXPIRING"),
    UNION_AUTH_EXPIRED("UNION_AUTH_EXPIRED"),
    DATA_COPY_READY("DATA_COPY_READY"),
    DATA_COPY_CODE("DATA_COPY_CODE"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<NotifyTemplateCode> =
            listOf(
                ORDER_TRACKED,
                ORDER_INVALID,
                CREDITED,
                CLAWBACK,
                WD_SUCCESS,
                WD_REJECTED,
                WD_FAILED,
                WD_OVERDUE,
                CLAIM_RESULT,
                RISK_STATE_CHANGED,
                APPEAL_RESULT,
                DELETION_PROGRESS,
                BALANCE_ADJUSTED,
                SMS_CODE,
                UNION_AUTH_EXPIRING,
                UNION_AUTH_EXPIRED,
                DATA_COPY_READY,
                DATA_COPY_CODE,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, NotifyTemplateCode> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): NotifyTemplateCode = byRaw[raw] ?: UNKNOWN
    }
}

/** [NotifyTemplateCode] 的序列化器：未知字符串得 [NotifyTemplateCode.UNKNOWN]；序列化写 raw。 */
object NotifyTemplateCodeSerializer : KSerializer<NotifyTemplateCode> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.NotifyTemplateCode", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): NotifyTemplateCode = NotifyTemplateCode.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: NotifyTemplateCode,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `notify_category`（contracts/enums/ops.yaml）。
 * 用户通知分类；交易与提现模板都归 service，MVP 只开放 service 开关，subscription、marketing 预埋不展示（待负责人确认）
 *
 * 未知取值解码为 [NotifyCategory.UNKNOWN]，不抛错。
 */
@Serializable(with = NotifyCategorySerializer::class)
enum class NotifyCategory(
    val raw: kotlin.String,
) {
    SERVICE("service"),
    SUBSCRIPTION("subscription"),
    MARKETING("marketing"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<NotifyCategory> =
            listOf(
                SERVICE,
                SUBSCRIPTION,
                MARKETING,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, NotifyCategory> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): NotifyCategory = byRaw[raw] ?: UNKNOWN
    }
}

/** [NotifyCategory] 的序列化器：未知字符串得 [NotifyCategory.UNKNOWN]；序列化写 raw。 */
object NotifyCategorySerializer : KSerializer<NotifyCategory> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.NotifyCategory", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): NotifyCategory = NotifyCategory.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: NotifyCategory,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `user_level`（contracts/enums/ops.yaml）。
 * 分销等级，晋升只看本人推广订单；用户端不展示（拍板第二批 OPS-20）
 *
 * 未知取值解码为 [UserLevel.UNKNOWN]，不抛错。
 */
@Serializable(with = UserLevelSerializer::class)
enum class UserLevel(
    val raw: kotlin.String,
) {
    L1("L1"),
    L2("L2"),
    L3("L3"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<UserLevel> =
            listOf(
                L1,
                L2,
                L3,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, UserLevel> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): UserLevel = byRaw[raw] ?: UNKNOWN
    }
}

/** [UserLevel] 的序列化器：未知字符串得 [UserLevel.UNKNOWN]；序列化写 raw。 */
object UserLevelSerializer : KSerializer<UserLevel> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.UserLevel", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): UserLevel = UserLevel.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: UserLevel,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `agent_intent`（contracts/enums/ops.yaml）。
 *
 * 未知取值解码为 [AgentIntent.UNKNOWN]，不抛错。
 */
@Serializable(with = AgentIntentSerializer::class)
enum class AgentIntent(
    val raw: kotlin.String,
) {
    FIND_BY_LINK("find_by_link"),
    SEARCH("search"),
    REFINE("refine"),
    ORDER_QUERY("order_query"),
    RULE_QA("rule_qa"),
    HANDOFF("handoff"),
    CLARIFY("clarify"),
    OUT_OF_SCOPE("out_of_scope"),
    PAGE_GUIDE("page_guide"),
    EARNINGS_QUERY("earnings_query"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<AgentIntent> =
            listOf(
                FIND_BY_LINK,
                SEARCH,
                REFINE,
                ORDER_QUERY,
                RULE_QA,
                HANDOFF,
                CLARIFY,
                OUT_OF_SCOPE,
                PAGE_GUIDE,
                EARNINGS_QUERY,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, AgentIntent> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): AgentIntent = byRaw[raw] ?: UNKNOWN
    }
}

/** [AgentIntent] 的序列化器：未知字符串得 [AgentIntent.UNKNOWN]；序列化写 raw。 */
object AgentIntentSerializer : KSerializer<AgentIntent> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.AgentIntent", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): AgentIntent = AgentIntent.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: AgentIntent,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `agent_card_type`（contracts/enums/ops.yaml）。
 * Agent 卡片类型；watch_confirm、watch_list 为 P1，MVP 不注册，不列入
 *
 * 未知取值解码为 [AgentCardType.UNKNOWN]，不抛错。
 */
@Serializable(with = AgentCardTypeSerializer::class)
enum class AgentCardType(
    val raw: kotlin.String,
) {
    PRODUCT_LIST("product_list"),
    REBATE_QUOTE("rebate_quote"),
    ORDER_STATUS("order_status"),
    CLAIM_DRAFT("claim_draft"),
    HANDOFF("handoff"),
    AUTH_REQUIRED("auth_required"),
    NOTICE("notice"),
    RULE_REF("rule_ref"),
    PAGE_GUIDE("page_guide"),
    EARNINGS_SUMMARY("earnings_summary"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<AgentCardType> =
            listOf(
                PRODUCT_LIST,
                REBATE_QUOTE,
                ORDER_STATUS,
                CLAIM_DRAFT,
                HANDOFF,
                AUTH_REQUIRED,
                NOTICE,
                RULE_REF,
                PAGE_GUIDE,
                EARNINGS_SUMMARY,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, AgentCardType> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): AgentCardType = byRaw[raw] ?: UNKNOWN
    }
}

/** [AgentCardType] 的序列化器：未知字符串得 [AgentCardType.UNKNOWN]；序列化写 raw。 */
object AgentCardTypeSerializer : KSerializer<AgentCardType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.AgentCardType", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): AgentCardType = AgentCardType.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: AgentCardType,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `page_guide_reject_reason`（contracts/enums/ops.yaml）。
 * agent_runs.page_guide_reject_reason，页面引导意图不出卡的原因（BR-AI-01 细则「页面引导」）
 *
 * 未知取值解码为 [PageGuideRejectReason.UNKNOWN]，不抛错。
 */
@Serializable(with = PageGuideRejectReasonSerializer::class)
enum class PageGuideRejectReason(
    val raw: kotlin.String,
) {
    NOT_ALLOWED("not_allowed"),
    EXTRA_FIELDS("extra_fields"),
    DISABLED("disabled"),
    UNTRUSTED_INPUT("untrusted_input"),
    PARAMS_REQUESTED("params_requested"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<PageGuideRejectReason> =
            listOf(
                NOT_ALLOWED,
                EXTRA_FIELDS,
                DISABLED,
                UNTRUSTED_INPUT,
                PARAMS_REQUESTED,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, PageGuideRejectReason> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): PageGuideRejectReason = byRaw[raw] ?: UNKNOWN
    }
}

/** [PageGuideRejectReason] 的序列化器：未知字符串得 [PageGuideRejectReason.UNKNOWN]；序列化写 raw。 */
object PageGuideRejectReasonSerializer : KSerializer<PageGuideRejectReason> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.PageGuideRejectReason", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): PageGuideRejectReason = PageGuideRejectReason.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: PageGuideRejectReason,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `agent_finish_reason`（contracts/enums/ops.yaml）。
 * SSE done 事件的 finish_reason
 *
 * 未知取值解码为 [AgentFinishReason.UNKNOWN]，不抛错。
 */
@Serializable(with = AgentFinishReasonSerializer::class)
enum class AgentFinishReason(
    val raw: kotlin.String,
) {
    STOP("stop"),
    CANCELLED("cancelled"),
    LIMIT("limit"),
    BUDGET("budget"),
    ERROR("error"),
    AUTH_REQUIRED("auth_required"),
    SAFETY("safety"),
    FALLBACK("fallback"),
    TIMEOUT("timeout"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<AgentFinishReason> =
            listOf(
                STOP,
                CANCELLED,
                LIMIT,
                BUDGET,
                ERROR,
                AUTH_REQUIRED,
                SAFETY,
                FALLBACK,
                TIMEOUT,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, AgentFinishReason> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): AgentFinishReason = byRaw[raw] ?: UNKNOWN
    }
}

/** [AgentFinishReason] 的序列化器：未知字符串得 [AgentFinishReason.UNKNOWN]；序列化写 raw。 */
object AgentFinishReasonSerializer : KSerializer<AgentFinishReason> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.AgentFinishReason", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): AgentFinishReason = AgentFinishReason.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: AgentFinishReason,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `watch_status`（contracts/enums/ops.yaml）。
 *
 * 未知取值解码为 [WatchStatus.UNKNOWN]，不抛错。
 */
@Serializable(with = WatchStatusSerializer::class)
enum class WatchStatus(
    val raw: kotlin.String,
) {
    ACTIVE("active"),
    PAUSED("paused"),
    UNAVAILABLE("unavailable"),
    EXPIRED("expired"),
    CANCELLED("cancelled"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<WatchStatus> =
            listOf(
                ACTIVE,
                PAUSED,
                UNAVAILABLE,
                EXPIRED,
                CANCELLED,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, WatchStatus> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): WatchStatus = byRaw[raw] ?: UNKNOWN
    }
}

/** [WatchStatus] 的序列化器：未知字符串得 [WatchStatus.UNKNOWN]；序列化写 raw。 */
object WatchStatusSerializer : KSerializer<WatchStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.WatchStatus", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): WatchStatus = WatchStatus.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: WatchStatus,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `watch_event_status`（contracts/enums/ops.yaml）。
 *
 * 未知取值解码为 [WatchEventStatus.UNKNOWN]，不抛错。
 */
@Serializable(with = WatchEventStatusSerializer::class)
enum class WatchEventStatus(
    val raw: kotlin.String,
) {
    PENDING("pending"),
    SUPPRESSED("suppressed"),
    SENT("sent"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<WatchEventStatus> =
            listOf(
                PENDING,
                SUPPRESSED,
                SENT,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, WatchEventStatus> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): WatchEventStatus = byRaw[raw] ?: UNKNOWN
    }
}

/** [WatchEventStatus] 的序列化器：未知字符串得 [WatchEventStatus.UNKNOWN]；序列化写 raw。 */
object WatchEventStatusSerializer : KSerializer<WatchEventStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.WatchEventStatus", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): WatchEventStatus = WatchEventStatus.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: WatchEventStatus,
    ) {
        encoder.encodeString(value.raw)
    }
}
