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
    DASHBOARD_VIEW("dashboard.view"),
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
                DASHBOARD_VIEW,
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

/**
 * 契约枚举 `admin_dashboard_todo_key`（contracts/enums/admin.yaml）。
 * GET /admin/v1/dashboard 的 todo[].key；每张卡只在账号持有括号里的权限点、且对应模块已上线时返回（count 为 0 也返回）；编码独立于权限点，不复用权限点字符串；卡标题与副文案取 BR-TEXT-25 admin_dict
 *
 * 未知取值解码为 [AdminDashboardTodoKey.UNKNOWN]，不抛错。
 */
@Serializable(with = AdminDashboardTodoKeySerializer::class)
enum class AdminDashboardTodoKey(
    val raw: kotlin.String,
) {
    WITHDRAW_REVIEW_PENDING("withdraw_review_pending"),
    CLAIM_REVIEW_PENDING("claim_review_pending"),
    SETTLE_BILL_PENDING("settle_bill_pending"),
    RECON_DIFF_OPEN("recon_diff_open"),
    APPEAL_PENDING("appeal_pending"),
    AGENT_REPORT_OVERDUE("agent_report_overdue"),
    BENEFICIARY_HELD_OVERDUE("beneficiary_held_overdue"),
    UNION_AUTH_EXPIRING("union_auth_expiring"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<AdminDashboardTodoKey> =
            listOf(
                WITHDRAW_REVIEW_PENDING,
                CLAIM_REVIEW_PENDING,
                SETTLE_BILL_PENDING,
                RECON_DIFF_OPEN,
                APPEAL_PENDING,
                AGENT_REPORT_OVERDUE,
                BENEFICIARY_HELD_OVERDUE,
                UNION_AUTH_EXPIRING,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, AdminDashboardTodoKey> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): AdminDashboardTodoKey = byRaw[raw] ?: UNKNOWN
    }
}

/** [AdminDashboardTodoKey] 的序列化器：未知字符串得 [AdminDashboardTodoKey.UNKNOWN]；序列化写 raw。 */
object AdminDashboardTodoKeySerializer : KSerializer<AdminDashboardTodoKey> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.AdminDashboardTodoKey", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): AdminDashboardTodoKey = AdminDashboardTodoKey.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: AdminDashboardTodoKey,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `admin_search_hit_type`（contracts/enums/admin.yaml）。
 * GET /admin/v1/search 的 items[].type；每类只返回给持有括号里权限点的账号，无权限与无命中同样不出现；类型名取 BR-TEXT-25 admin_dict
 *
 * 未知取值解码为 [AdminSearchHitType.UNKNOWN]，不抛错。
 */
@Serializable(with = AdminSearchHitTypeSerializer::class)
enum class AdminSearchHitType(
    val raw: kotlin.String,
) {
    ORDER("order"),
    USER("user"),
    WITHDRAWAL("withdrawal"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<AdminSearchHitType> =
            listOf(
                ORDER,
                USER,
                WITHDRAWAL,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, AdminSearchHitType> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): AdminSearchHitType = byRaw[raw] ?: UNKNOWN
    }
}

/** [AdminSearchHitType] 的序列化器：未知字符串得 [AdminSearchHitType.UNKNOWN]；序列化写 raw。 */
object AdminSearchHitTypeSerializer : KSerializer<AdminSearchHitType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.AdminSearchHitType", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): AdminSearchHitType = AdminSearchHitType.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: AdminSearchHitType,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `export_status`（contracts/enums/admin.yaml）。
 * 后台导出任务（/admin/v1/exports）的状态；迁移只有 pending→running→done 或 failed、done→expired（生成后 24 小时）、pending 或 running→cancelled（创建人账号停用）、failed→pending（创建人重试）；没有取消接口；中文名取 BR-TEXT-25 admin_dict
 *
 * 未知取值解码为 [ExportStatus.UNKNOWN]，不抛错。
 */
@Serializable(with = ExportStatusSerializer::class)
enum class ExportStatus(
    val raw: kotlin.String,
) {
    PENDING("pending"),
    RUNNING("running"),
    DONE("done"),
    FAILED("failed"),
    EXPIRED("expired"),
    CANCELLED("cancelled"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<ExportStatus> =
            listOf(
                PENDING,
                RUNNING,
                DONE,
                FAILED,
                EXPIRED,
                CANCELLED,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, ExportStatus> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): ExportStatus = byRaw[raw] ?: UNKNOWN
    }
}

/** [ExportStatus] 的序列化器：未知字符串得 [ExportStatus.UNKNOWN]；序列化写 raw。 */
object ExportStatusSerializer : KSerializer<ExportStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.ExportStatus", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): ExportStatus = ExportStatus.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: ExportStatus,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `export_resource`（contracts/enums/admin.yaml）。
 * 后台导出任务的来源列表；创建时须持有该列表本身的权限点（括号内），filters 与该列表的查询参数同形；其他列表随各自的契约追加
 *
 * 未知取值解码为 [ExportResource.UNKNOWN]，不抛错。
 */
@Serializable(with = ExportResourceSerializer::class)
enum class ExportResource(
    val raw: kotlin.String,
) {
    USERS("users"),
    AUDIT_LOGS("audit_logs"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<ExportResource> =
            listOf(
                USERS,
                AUDIT_LOGS,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, ExportResource> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): ExportResource = byRaw[raw] ?: UNKNOWN
    }
}

/** [ExportResource] 的序列化器：未知字符串得 [ExportResource.UNKNOWN]；序列化写 raw。 */
object ExportResourceSerializer : KSerializer<ExportResource> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.ExportResource", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): ExportResource = ExportResource.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: ExportResource,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `admin_claim_match_field`（contracts/enums/admin.yaml）。
 * GET /admin/v1/claims/{claim_id} 的 items[].matches[].field，证据视图逐项比对用户填写与联盟回传的字段；预售单的联盟侧付款日期取定金支付日
 *
 * 未知取值解码为 [AdminClaimMatchField.UNKNOWN]，不抛错。
 */
@Serializable(with = AdminClaimMatchFieldSerializer::class)
enum class AdminClaimMatchField(
    val raw: kotlin.String,
) {
    ORDER_NO("order_no"),
    PAID_DATE("paid_date"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<AdminClaimMatchField> =
            listOf(
                ORDER_NO,
                PAID_DATE,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, AdminClaimMatchField> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): AdminClaimMatchField = byRaw[raw] ?: UNKNOWN
    }
}

/** [AdminClaimMatchField] 的序列化器：未知字符串得 [AdminClaimMatchField.UNKNOWN]；序列化写 raw。 */
object AdminClaimMatchFieldSerializer : KSerializer<AdminClaimMatchField> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.AdminClaimMatchField", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): AdminClaimMatchField = AdminClaimMatchField.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: AdminClaimMatchField,
    ) {
        encoder.encodeString(value.raw)
    }
}
