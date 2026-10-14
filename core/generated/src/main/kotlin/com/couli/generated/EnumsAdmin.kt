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
 * 契约枚举 `admin_permission`（contracts/enums/admin.yaml）。
 * 权限点 key；标 step-up 的权限点每次操作都须二次验证，只有部分操作要的在该项写明（见 04 §11 与 specs/permissions.yaml）
 *
 * 未知取值解码为 [AdminPermission.UNKNOWN]，不抛错。
 */
@Serializable(with = AdminPermissionSerializer::class)
enum class AdminPermission(
    val raw: kotlin.String,
) {
    USER_LIST("user.list"),
    USER_LOOKUP("user.lookup"),
    PII_REVEAL_PHONE("pii.reveal_phone"),
    PII_REVEAL_IDENTITY("pii.reveal_identity"),
    FUND_VIEW("fund.view"),
    AUDIT_VIEW_ALL("audit.view_all"),
    EXPORT("export"),
    CONTENT_PAGE("content.page"),
    CONTENT_POOL("content.pool"),
    CONTENT_ARTICLE("content.article"),
    CONTENT_AGREEMENT("content.agreement"),
    CONTENT_POSTER("content.poster"),
    CONTENT_APP_VERSION("content.app_version"),
    CONTENT_FUND_TERMS("content.fund_terms"),
    CONTENT_PLATFORM_ICON("content.platform_icon"),
    CONFIG_GENERAL("config.general"),
    CONFIG_RISK("config.risk"),
    CONFIG_BUSINESS("config.business"),
    SWITCH_ALL("switch.all"),
    SWITCH_PAYOUT("switch.payout"),
    SWITCH_PAY("switch.pay"),
    RISK_FREEZE("risk.freeze"),
    RISK_BAN("risk.ban"),
    RISK_BLOCKLIST("risk.blocklist"),
    RISK_APPEAL("risk.appeal"),
    UNION_BINDING_RESET("union.binding_reset"),
    UNION_BINDING_DISABLE("union.binding_disable"),
    UNION_ACCOUNT_AUTH("union.account_auth"),
    UNION_PID("union.pid"),
    USER_LEVEL("user.level"),
    USER_INVITER("user.inviter"),
    USER_REALNAME_FIX("user.realname_fix"),
    USER_PHONE_CHANGE("user.phone_change"),
    USER_DATA_EXPORT("user.data_export"),
    ORDER_VIEW("order.view"),
    ORDER_CLAIM("order.claim"),
    ORDER_ASSIGN("order.assign"),
    ORDER_HOLD("order.hold"),
    ORDER_RESTORE("order.restore"),
    WITHDRAW_REVIEW("withdraw.review"),
    PAYOUT_EXECUTE("payout.execute"),
    PAY_VIEW("pay.view"),
    PAY_REFUND("pay.refund"),
    PAY_RESOLVE("pay.resolve"),
    PAYOUT_MANUAL_ENTRY("payout.manual_entry"),
    SETTLE_BILL("settle.bill"),
    SETTLE_STATEMENT_UPLOAD("settle.statement_upload"),
    FUND_ADJUST("fund.adjust"),
    FUND_WRITEOFF("fund.writeoff"),
    FUND_SETTLE_ADJUST("fund.settle_adjust"),
    FUND_RECON("fund.recon"),
    FUND_CASH_ENTRY("fund.cash_entry"),
    AGENT_TRACE("agent.trace"),
    AGENT_REPORT("agent.report"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<AdminPermission> =
            listOf(
                USER_LIST,
                USER_LOOKUP,
                PII_REVEAL_PHONE,
                PII_REVEAL_IDENTITY,
                FUND_VIEW,
                AUDIT_VIEW_ALL,
                EXPORT,
                CONTENT_PAGE,
                CONTENT_POOL,
                CONTENT_ARTICLE,
                CONTENT_AGREEMENT,
                CONTENT_POSTER,
                CONTENT_APP_VERSION,
                CONTENT_FUND_TERMS,
                CONTENT_PLATFORM_ICON,
                CONFIG_GENERAL,
                CONFIG_RISK,
                CONFIG_BUSINESS,
                SWITCH_ALL,
                SWITCH_PAYOUT,
                SWITCH_PAY,
                RISK_FREEZE,
                RISK_BAN,
                RISK_BLOCKLIST,
                RISK_APPEAL,
                UNION_BINDING_RESET,
                UNION_BINDING_DISABLE,
                UNION_ACCOUNT_AUTH,
                UNION_PID,
                USER_LEVEL,
                USER_INVITER,
                USER_REALNAME_FIX,
                USER_PHONE_CHANGE,
                USER_DATA_EXPORT,
                ORDER_VIEW,
                ORDER_CLAIM,
                ORDER_ASSIGN,
                ORDER_HOLD,
                ORDER_RESTORE,
                WITHDRAW_REVIEW,
                PAYOUT_EXECUTE,
                PAY_VIEW,
                PAY_REFUND,
                PAY_RESOLVE,
                PAYOUT_MANUAL_ENTRY,
                SETTLE_BILL,
                SETTLE_STATEMENT_UPLOAD,
                FUND_ADJUST,
                FUND_WRITEOFF,
                FUND_SETTLE_ADJUST,
                FUND_RECON,
                FUND_CASH_ENTRY,
                AGENT_TRACE,
                AGENT_REPORT,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, AdminPermission> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): AdminPermission = byRaw[raw] ?: UNKNOWN
    }
}

/** [AdminPermission] 的序列化器：未知字符串得 [AdminPermission.UNKNOWN]；序列化写 raw。 */
object AdminPermissionSerializer : KSerializer<AdminPermission> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.AdminPermission", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): AdminPermission = AdminPermission.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: AdminPermission,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `admin_step_up_tier`（contracts/enums/admin.yaml）。
 * 后台 step-up 的档位；step_up_token 记档位，动态码档的 token 不能用于短信档操作；10003 的 data.tier 取本枚举
 *
 * 未知取值解码为 [AdminStepUpTier.UNKNOWN]，不抛错。
 */
@Serializable(with = AdminStepUpTierSerializer::class)
enum class AdminStepUpTier(
    val raw: kotlin.String,
) {
    TOTP("totp"),
    SMS("sms"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<AdminStepUpTier> =
            listOf(
                TOTP,
                SMS,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, AdminStepUpTier> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): AdminStepUpTier = byRaw[raw] ?: UNKNOWN
    }
}

/** [AdminStepUpTier] 的序列化器：未知字符串得 [AdminStepUpTier.UNKNOWN]；序列化写 raw。 */
object AdminStepUpTierSerializer : KSerializer<AdminStepUpTier> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.AdminStepUpTier", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): AdminStepUpTier = AdminStepUpTier.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: AdminStepUpTier,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `admin_login_step`（contracts/enums/admin.yaml）。
 * 后台登录第一步通过后的下一步，凭中间凭证 login_ticket 完成；两步都完成才签发 admin_token
 *
 * 未知取值解码为 [AdminLoginStep.UNKNOWN]，不抛错。
 */
@Serializable(with = AdminLoginStepSerializer::class)
enum class AdminLoginStep(
    val raw: kotlin.String,
) {
    TOTP("totp"),
    CHANGE_PASSWORD("change_password"),
    BIND_TOTP("bind_totp"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<AdminLoginStep> =
            listOf(
                TOTP,
                CHANGE_PASSWORD,
                BIND_TOTP,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, AdminLoginStep> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): AdminLoginStep = byRaw[raw] ?: UNKNOWN
    }
}

/** [AdminLoginStep] 的序列化器：未知字符串得 [AdminLoginStep.UNKNOWN]；序列化写 raw。 */
object AdminLoginStepSerializer : KSerializer<AdminLoginStep> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.AdminLoginStep", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): AdminLoginStep = AdminLoginStep.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: AdminLoginStep,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `admin_account_status`（contracts/enums/admin.yaml）。
 * 后台账号状态；停用的账号名仍占用，不再分配给新账号
 *
 * 未知取值解码为 [AdminAccountStatus.UNKNOWN]，不抛错。
 */
@Serializable(with = AdminAccountStatusSerializer::class)
enum class AdminAccountStatus(
    val raw: kotlin.String,
) {
    ACTIVE("active"),
    DISABLED("disabled"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<AdminAccountStatus> =
            listOf(
                ACTIVE,
                DISABLED,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, AdminAccountStatus> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): AdminAccountStatus = byRaw[raw] ?: UNKNOWN
    }
}

/** [AdminAccountStatus] 的序列化器：未知字符串得 [AdminAccountStatus.UNKNOWN]；序列化写 raw。 */
object AdminAccountStatusSerializer : KSerializer<AdminAccountStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.AdminAccountStatus", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): AdminAccountStatus = AdminAccountStatus.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: AdminAccountStatus,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `admin_auth_level`（contracts/enums/admin.yaml）。
 * /admin/v1 接口的鉴权级别；中间凭证 login_ticket 放请求体，不是鉴权级别
 *
 * 未知取值解码为 [AdminAuthLevel.UNKNOWN]，不抛错。
 */
@Serializable(with = AdminAuthLevelSerializer::class)
enum class AdminAuthLevel(
    val raw: kotlin.String,
) {
    NONE("none"),
    ADMIN("admin"),
    SUPER("super"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<AdminAuthLevel> =
            listOf(
                NONE,
                ADMIN,
                SUPER,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, AdminAuthLevel> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): AdminAuthLevel = byRaw[raw] ?: UNKNOWN
    }
}

/** [AdminAuthLevel] 的序列化器：未知字符串得 [AdminAuthLevel.UNKNOWN]；序列化写 raw。 */
object AdminAuthLevelSerializer : KSerializer<AdminAuthLevel> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.AdminAuthLevel", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): AdminAuthLevel = AdminAuthLevel.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: AdminAuthLevel,
    ) {
        encoder.encodeString(value.raw)
    }
}
