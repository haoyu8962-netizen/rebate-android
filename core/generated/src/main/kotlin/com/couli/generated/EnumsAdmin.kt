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
    NOTIFY_CHANNELS("notify.channels"),
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
    TAX_EXPORT_FULL("tax.export_full"),
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
                NOTIFY_CHANNELS,
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
                TAX_EXPORT_FULL,
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
    TAX_WITHDRAWALS("tax_withdrawals"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<ExportResource> =
            listOf(
                USERS,
                AUDIT_LOGS,
                TAX_WITHDRAWALS,
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
 * 契约枚举 `audit_actor_kind`（contracts/enums/admin.yaml）。
 * GET /admin/v1/audit-logs 列表、详情的 actor_kind；只在系统行出现（值 system），后台账号的行不带本字段
 *
 * 未知取值解码为 [AuditActorKind.UNKNOWN]，不抛错。
 */
@Serializable(with = AuditActorKindSerializer::class)
enum class AuditActorKind(
    val raw: kotlin.String,
) {
    ADMIN("admin"),
    SYSTEM("system"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<AuditActorKind> =
            listOf(
                ADMIN,
                SYSTEM,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, AuditActorKind> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): AuditActorKind = byRaw[raw] ?: UNKNOWN
    }
}

/** [AuditActorKind] 的序列化器：未知字符串得 [AuditActorKind.UNKNOWN]；序列化写 raw。 */
object AuditActorKindSerializer : KSerializer<AuditActorKind> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.AuditActorKind", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): AuditActorKind = AuditActorKind.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: AuditActorKind,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `audit_system_actor`（contracts/enums/admin.yaml）。
 * 审计日志系统行的 system_actor，值的格式是 ^system:[a-z_]+$，以后追加；中文名就是系统行的 actor_name 与导出的「操作者」列
 *
 * 未知取值解码为 [AuditSystemActor.UNKNOWN]，不抛错。
 */
@Serializable(with = AuditSystemActorSerializer::class)
enum class AuditSystemActor(
    val raw: kotlin.String,
) {
    SYSTEM_RISK("system:risk"),
    SYSTEM_PAYOUT("system:payout"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<AuditSystemActor> =
            listOf(
                SYSTEM_RISK,
                SYSTEM_PAYOUT,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, AuditSystemActor> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): AuditSystemActor = byRaw[raw] ?: UNKNOWN
    }
}

/** [AuditSystemActor] 的序列化器：未知字符串得 [AuditSystemActor.UNKNOWN]；序列化写 raw。 */
object AuditSystemActorSerializer : KSerializer<AuditSystemActor> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.AuditSystemActor", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): AuditSystemActor = AuditSystemActor.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: AuditSystemActor,
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

/**
 * 契约枚举 `admin_order_flag`（contracts/enums/admin.yaml）。
 * GET /admin/v1/orders 的 flag 筛选与列表项 flags 的键；locked 即 orders.locked（后台界面叫「归属已锁定」），status_locked 由 rebate_status 推导、不入库（后台界面叫「已锁定」）；中文名取 BR-TEXT-25 admin_dict，本契约不补文案
 *
 * 未知取值解码为 [AdminOrderFlag.UNKNOWN]，不抛错。
 */
@Serializable(with = AdminOrderFlagSerializer::class)
enum class AdminOrderFlag(
    val raw: kotlin.String,
) {
    LOCKED("locked"),
    STATUS_LOCKED("status_locked"),
    RIGHTS_PENDING("rights_pending"),
    HOLD("hold"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<AdminOrderFlag> =
            listOf(
                LOCKED,
                STATUS_LOCKED,
                RIGHTS_PENDING,
                HOLD,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, AdminOrderFlag> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): AdminOrderFlag = byRaw[raw] ?: UNKNOWN
    }
}

/** [AdminOrderFlag] 的序列化器：未知字符串得 [AdminOrderFlag.UNKNOWN]；序列化写 raw。 */
object AdminOrderFlagSerializer : KSerializer<AdminOrderFlag> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.AdminOrderFlag", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): AdminOrderFlag = AdminOrderFlag.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: AdminOrderFlag,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `admin_order_history_field`（contracts/enums/admin.yaml）。
 * GET /admin/v1/orders/{order_id} 的 status_history[].field，这一行记的是哪个状态字段
 *
 * 未知取值解码为 [AdminOrderHistoryField.UNKNOWN]，不抛错。
 */
@Serializable(with = AdminOrderHistoryFieldSerializer::class)
enum class AdminOrderHistoryField(
    val raw: kotlin.String,
) {
    PLATFORM("platform"),
    REBATE("rebate"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<AdminOrderHistoryField> =
            listOf(
                PLATFORM,
                REBATE,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, AdminOrderHistoryField> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): AdminOrderHistoryField = byRaw[raw] ?: UNKNOWN
    }
}

/** [AdminOrderHistoryField] 的序列化器：未知字符串得 [AdminOrderHistoryField.UNKNOWN]；序列化写 raw。 */
object AdminOrderHistoryFieldSerializer : KSerializer<AdminOrderHistoryField> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.AdminOrderHistoryField", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): AdminOrderHistoryField = AdminOrderHistoryField.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: AdminOrderHistoryField,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `admin_order_history_source`（contracts/enums/admin.yaml）。
 * GET /admin/v1/orders/{order_id} 的 status_history[].source，状态变化的来源
 *
 * 未知取值解码为 [AdminOrderHistorySource.UNKNOWN]，不抛错。
 */
@Serializable(with = AdminOrderHistorySourceSerializer::class)
enum class AdminOrderHistorySource(
    val raw: kotlin.String,
) {
    SYNC("sync"),
    ADMIN("admin"),
    CLAIM("claim"),
    SYSTEM("system"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<AdminOrderHistorySource> =
            listOf(
                SYNC,
                ADMIN,
                CLAIM,
                SYSTEM,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, AdminOrderHistorySource> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): AdminOrderHistorySource = byRaw[raw] ?: UNKNOWN
    }
}

/** [AdminOrderHistorySource] 的序列化器：未知字符串得 [AdminOrderHistorySource.UNKNOWN]；序列化写 raw。 */
object AdminOrderHistorySourceSerializer : KSerializer<AdminOrderHistorySource> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.AdminOrderHistorySource", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): AdminOrderHistorySource = AdminOrderHistorySource.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: AdminOrderHistorySource,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `risk_rule_status`（contracts/enums/admin.yaml）。
 * GET /admin/v1/risk-rules 列表项的 status；启停经 POST /admin/v1/risk-rules/{rule_id}/enable、…/disable；中文名取 BR-TEXT-25 admin_dict，本契约不补文案
 *
 * 未知取值解码为 [RiskRuleStatus.UNKNOWN]，不抛错。
 */
@Serializable(with = RiskRuleStatusSerializer::class)
enum class RiskRuleStatus(
    val raw: kotlin.String,
) {
    ENABLED("enabled"),
    DISABLED("disabled"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<RiskRuleStatus> =
            listOf(
                ENABLED,
                DISABLED,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, RiskRuleStatus> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): RiskRuleStatus = byRaw[raw] ?: UNKNOWN
    }
}

/** [RiskRuleStatus] 的序列化器：未知字符串得 [RiskRuleStatus.UNKNOWN]；序列化写 raw。 */
object RiskRuleStatusSerializer : KSerializer<RiskRuleStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.RiskRuleStatus", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): RiskRuleStatus = RiskRuleStatus.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: RiskRuleStatus,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `risk_action`（contracts/enums/admin.yaml）。
 * 风控规则命中后的动作，GET /admin/v1/risk-rules 列表项的 risk_action；取值与 risk_rules、risk_hits 的 CHECK 约束一致；中文名取 BR-TEXT-25 admin_dict（admin_dict.risk_action.*）
 *
 * 未知取值解码为 [RiskAction.UNKNOWN]，不抛错。
 */
@Serializable(with = RiskActionSerializer::class)
enum class RiskAction(
    val raw: kotlin.String,
) {
    PASS("pass"),
    MANUAL_REVIEW("manual_review"),
    BLOCK("block"),
    VOID_COMMISSION("void_commission"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<RiskAction> =
            listOf(
                PASS,
                MANUAL_REVIEW,
                BLOCK,
                VOID_COMMISSION,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, RiskAction> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): RiskAction = byRaw[raw] ?: UNKNOWN
    }
}

/** [RiskAction] 的序列化器：未知字符串得 [RiskAction.UNKNOWN]；序列化写 raw。 */
object RiskActionSerializer : KSerializer<RiskAction> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.RiskAction", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): RiskAction = RiskAction.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: RiskAction,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `union_auth_status`（contracts/enums/admin.yaml）。
 * 站长联盟授权状态；GET /admin/v1/union-accounts 的 auth_status；提醒节点 alert_stage 不对外返回；中文名取 BR-TEXT-25 admin_dict.union_accounts.auth_status
 *
 * 未知取值解码为 [UnionAuthStatus.UNKNOWN]，不抛错。
 */
@Serializable(with = UnionAuthStatusSerializer::class)
enum class UnionAuthStatus(
    val raw: kotlin.String,
) {
    ACTIVE("active"),
    EXPIRING("expiring"),
    EXPIRED("expired"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<UnionAuthStatus> =
            listOf(
                ACTIVE,
                EXPIRING,
                EXPIRED,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, UnionAuthStatus> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): UnionAuthStatus = byRaw[raw] ?: UNKNOWN
    }
}

/** [UnionAuthStatus] 的序列化器：未知字符串得 [UnionAuthStatus.UNKNOWN]；序列化写 raw。 */
object UnionAuthStatusSerializer : KSerializer<UnionAuthStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.UnionAuthStatus", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): UnionAuthStatus = UnionAuthStatus.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: UnionAuthStatus,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `union_reauth_mode`（contracts/enums/admin.yaml）。
 * 该联盟账号站长凭据的更新方式，由服务端按平台登记表派生（不入库）；前端据此显示入口
 *
 * 未知取值解码为 [UnionReauthMode.UNKNOWN]，不抛错。
 */
@Serializable(with = UnionReauthModeSerializer::class)
enum class UnionReauthMode(
    val raw: kotlin.String,
) {
    OAUTH("oauth"),
    MANUAL("manual"),
    NONE("none"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<UnionReauthMode> =
            listOf(
                OAUTH,
                MANUAL,
                NONE,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, UnionReauthMode> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): UnionReauthMode = byRaw[raw] ?: UNKNOWN
    }
}

/** [UnionReauthMode] 的序列化器：未知字符串得 [UnionReauthMode.UNKNOWN]；序列化写 raw。 */
object UnionReauthModeSerializer : KSerializer<UnionReauthMode> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.UnionReauthMode", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): UnionReauthMode = UnionReauthMode.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: UnionReauthMode,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `admin_union_auth_session_status`（contracts/enums/admin.yaml）。
 * 后台站长重新授权会话的状态；state 单次使用、10 分钟有效
 *
 * 未知取值解码为 [AdminUnionAuthSessionStatus.UNKNOWN]，不抛错。
 */
@Serializable(with = AdminUnionAuthSessionStatusSerializer::class)
enum class AdminUnionAuthSessionStatus(
    val raw: kotlin.String,
) {
    PENDING("pending"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    EXPIRED("expired"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<AdminUnionAuthSessionStatus> =
            listOf(
                PENDING,
                SUCCEEDED,
                FAILED,
                EXPIRED,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, AdminUnionAuthSessionStatus> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): AdminUnionAuthSessionStatus = byRaw[raw] ?: UNKNOWN
    }
}

/** [AdminUnionAuthSessionStatus] 的序列化器：未知字符串得 [AdminUnionAuthSessionStatus.UNKNOWN]；序列化写 raw。 */
object AdminUnionAuthSessionStatusSerializer : KSerializer<AdminUnionAuthSessionStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.AdminUnionAuthSessionStatus", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): AdminUnionAuthSessionStatus = AdminUnionAuthSessionStatus.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: AdminUnionAuthSessionStatus,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `union_pid_status`（contracts/enums/admin.yaml）。
 * 推广位状态；只有 pending→active（启用，须花卷云忽略确认与截图）、active→retired（停用）；三种状态都在白名单里，不可删除
 *
 * 未知取值解码为 [UnionPidStatus.UNKNOWN]，不抛错。
 */
@Serializable(with = UnionPidStatusSerializer::class)
enum class UnionPidStatus(
    val raw: kotlin.String,
) {
    PENDING("pending"),
    ACTIVE("active"),
    RETIRED("retired"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<UnionPidStatus> =
            listOf(
                PENDING,
                ACTIVE,
                RETIRED,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, UnionPidStatus> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): UnionPidStatus = byRaw[raw] ?: UNKNOWN
    }
}

/** [UnionPidStatus] 的序列化器：未知字符串得 [UnionPidStatus.UNKNOWN]；序列化写 raw。 */
object UnionPidStatusSerializer : KSerializer<UnionPidStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.UnionPidStatus", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): UnionPidStatus = UnionPidStatus.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: UnionPidStatus,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `product_pool_status`（contracts/enums/admin.yaml）。
 * 商品池状态
 *
 * 未知取值解码为 [ProductPoolStatus.UNKNOWN]，不抛错。
 */
@Serializable(with = ProductPoolStatusSerializer::class)
enum class ProductPoolStatus(
    val raw: kotlin.String,
) {
    ACTIVE("active"),
    DISABLED("disabled"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<ProductPoolStatus> =
            listOf(
                ACTIVE,
                DISABLED,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, ProductPoolStatus> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): ProductPoolStatus = byRaw[raw] ?: UNKNOWN
    }
}

/** [ProductPoolStatus] 的序列化器：未知字符串得 [ProductPoolStatus.UNKNOWN]；序列化写 raw。 */
object ProductPoolStatusSerializer : KSerializer<ProductPoolStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.ProductPoolStatus", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): ProductPoolStatus = ProductPoolStatus.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: ProductPoolStatus,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `pool_item_status`（contracts/enums/admin.yaml）。
 * 商品池条目的上下架状态
 *
 * 未知取值解码为 [PoolItemStatus.UNKNOWN]，不抛错。
 */
@Serializable(with = PoolItemStatusSerializer::class)
enum class PoolItemStatus(
    val raw: kotlin.String,
) {
    ONLINE("online"),
    OFFLINE("offline"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<PoolItemStatus> =
            listOf(
                ONLINE,
                OFFLINE,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, PoolItemStatus> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): PoolItemStatus = byRaw[raw] ?: UNKNOWN
    }
}

/** [PoolItemStatus] 的序列化器：未知字符串得 [PoolItemStatus.UNKNOWN]；序列化写 raw。 */
object PoolItemStatusSerializer : KSerializer<PoolItemStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.PoolItemStatus", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): PoolItemStatus = PoolItemStatus.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: PoolItemStatus,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `notify_channel`（contracts/enums/admin.yaml）。
 * 通知的发送渠道；后台只能开关 push 与 sms，inbox 不能由后台增删
 *
 * 未知取值解码为 [NotifyChannel.UNKNOWN]，不抛错。
 */
@Serializable(with = NotifyChannelSerializer::class)
enum class NotifyChannel(
    val raw: kotlin.String,
) {
    INBOX("inbox"),
    PUSH("push"),
    SMS("sms"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<NotifyChannel> =
            listOf(
                INBOX,
                PUSH,
                SMS,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, NotifyChannel> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): NotifyChannel = byRaw[raw] ?: UNKNOWN
    }
}

/** [NotifyChannel] 的序列化器：未知字符串得 [NotifyChannel.UNKNOWN]；序列化写 raw。 */
object NotifyChannelSerializer : KSerializer<NotifyChannel> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.NotifyChannel", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): NotifyChannel = NotifyChannel.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: NotifyChannel,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `sms_template_type`（contracts/enums/admin.yaml）。
 * 短信服务商的模板类型；营销短信为 P1，不列入
 *
 * 未知取值解码为 [SmsTemplateType.UNKNOWN]，不抛错。
 */
@Serializable(with = SmsTemplateTypeSerializer::class)
enum class SmsTemplateType(
    val raw: kotlin.String,
) {
    VERIFY("verify"),
    NOTICE("notice"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<SmsTemplateType> =
            listOf(
                VERIFY,
                NOTICE,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, SmsTemplateType> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): SmsTemplateType = byRaw[raw] ?: UNKNOWN
    }
}

/** [SmsTemplateType] 的序列化器：未知字符串得 [SmsTemplateType.UNKNOWN]；序列化写 raw。 */
object SmsTemplateTypeSerializer : KSerializer<SmsTemplateType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.SmsTemplateType", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): SmsTemplateType = SmsTemplateType.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: SmsTemplateType,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `notify_sms_state`（contracts/enums/admin.yaml）。
 * GET /admin/v1/notify-channels 的 sms_state：该编码的短信现在怎样发
 *
 * 未知取值解码为 [NotifySmsState.UNKNOWN]，不抛错。
 */
@Serializable(with = NotifySmsStateSerializer::class)
enum class NotifySmsState(
    val raw: kotlin.String,
) {
    DISABLED("disabled"),
    ENABLED("enabled"),
    ENABLED_NOT_READY("enabled_not_ready"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<NotifySmsState> =
            listOf(
                DISABLED,
                ENABLED,
                ENABLED_NOT_READY,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, NotifySmsState> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): NotifySmsState = byRaw[raw] ?: UNKNOWN
    }
}

/** [NotifySmsState] 的序列化器：未知字符串得 [NotifySmsState.UNKNOWN]；序列化写 raw。 */
object NotifySmsStateSerializer : KSerializer<NotifySmsState> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.NotifySmsState", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): NotifySmsState = NotifySmsState.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: NotifySmsState,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `notify_sms_test_status`（contracts/enums/admin.yaml）。
 * 待测试登记最近一次测试发送的结果
 *
 * 未知取值解码为 [NotifySmsTestStatus.UNKNOWN]，不抛错。
 */
@Serializable(with = NotifySmsTestStatusSerializer::class)
enum class NotifySmsTestStatus(
    val raw: kotlin.String,
) {
    ACCEPTED("accepted"),
    FAILED("failed"),
    NOT_SENT_NO_VERIFY_PHONE("not_sent_no_verify_phone"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<NotifySmsTestStatus> =
            listOf(
                ACCEPTED,
                FAILED,
                NOT_SENT_NO_VERIFY_PHONE,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, NotifySmsTestStatus> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): NotifySmsTestStatus = byRaw[raw] ?: UNKNOWN
    }
}

/** [NotifySmsTestStatus] 的序列化器：未知字符串得 [NotifySmsTestStatus.UNKNOWN]；序列化写 raw。 */
object NotifySmsTestStatusSerializer : KSerializer<NotifySmsTestStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.NotifySmsTestStatus", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): NotifySmsTestStatus = NotifySmsTestStatus.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: NotifySmsTestStatus,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `admin_withdrawal_display_status`（contracts/enums/admin.yaml）。
 * GET /admin/v1/withdrawals 列表项与详情的 display_status 及同名筛选；由 status + review_mode + hold_reason 推导、不入库，不是新的提现状态（withdrawal_status 不变）；中文名取 BR-TEXT-25 admin_dict，本契约不补文案
 *
 * 未知取值解码为 [AdminWithdrawalDisplayStatus.UNKNOWN]，不抛错。
 */
@Serializable(with = AdminWithdrawalDisplayStatusSerializer::class)
enum class AdminWithdrawalDisplayStatus(
    val raw: kotlin.String,
) {
    PENDING_REVIEW("pending_review"),
    PENDING_HANDLE("pending_handle"),
    REJECTED("rejected"),
    PAID_MANUAL("paid_manual"),
    PAID_AUTO_SYSTEM("paid_auto_system"),
    PAID_AUTO_REVIEW("paid_auto_review"),
    PAYING("paying"),
    FAILED("failed"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<AdminWithdrawalDisplayStatus> =
            listOf(
                PENDING_REVIEW,
                PENDING_HANDLE,
                REJECTED,
                PAID_MANUAL,
                PAID_AUTO_SYSTEM,
                PAID_AUTO_REVIEW,
                PAYING,
                FAILED,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, AdminWithdrawalDisplayStatus> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): AdminWithdrawalDisplayStatus = byRaw[raw] ?: UNKNOWN
    }
}

/** [AdminWithdrawalDisplayStatus] 的序列化器：未知字符串得 [AdminWithdrawalDisplayStatus.UNKNOWN]；序列化写 raw。 */
object AdminWithdrawalDisplayStatusSerializer : KSerializer<AdminWithdrawalDisplayStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.AdminWithdrawalDisplayStatus", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): AdminWithdrawalDisplayStatus = AdminWithdrawalDisplayStatus.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: AdminWithdrawalDisplayStatus,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `admin_settle_supplement_outcome`（contracts/enums/admin.yaml）。
 * POST /admin/v1/settle-bills/{bill_id}/supplement-batches 响应的 outcome；无可纳入数据不是错误，outcome 仍为 generated、batch_ids 为空，不生成空批次
 *
 * 未知取值解码为 [AdminSettleSupplementOutcome.UNKNOWN]，不抛错。
 */
@Serializable(with = AdminSettleSupplementOutcomeSerializer::class)
enum class AdminSettleSupplementOutcome(
    val raw: kotlin.String,
) {
    GENERATED("generated"),
    DUPLICATE("duplicate"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<AdminSettleSupplementOutcome> =
            listOf(
                GENERATED,
                DUPLICATE,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, AdminSettleSupplementOutcome> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): AdminSettleSupplementOutcome = byRaw[raw] ?: UNKNOWN
    }
}

/** [AdminSettleSupplementOutcome] 的序列化器：未知字符串得 [AdminSettleSupplementOutcome.UNKNOWN]；序列化写 raw。 */
object AdminSettleSupplementOutcomeSerializer : KSerializer<AdminSettleSupplementOutcome> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.AdminSettleSupplementOutcome", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): AdminSettleSupplementOutcome = AdminSettleSupplementOutcome.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: AdminSettleSupplementOutcome,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `admin_recon_diff_outcome`（contracts/enums/admin.yaml）。
 * POST /admin/v1/recon-diffs/{recon_diff_id}/close 的可选 outcome，B2-09h 起写入 recon_diffs.resolution.outcome；只对 type=settle_amount_mismatch（source R1）有意义，B2-09h 起其他差错单带它是 20001 data.fields=[outcome]；不传 = 无结构化结论，月结按需比金额；closed 状态本身不算结论，自由文本 reason 不解析
 *
 * 未知取值解码为 [AdminReconDiffOutcome.UNKNOWN]，不抛错。
 */
@Serializable(with = AdminReconDiffOutcomeSerializer::class)
enum class AdminReconDiffOutcome(
    val raw: kotlin.String,
) {
    UNION("union"),
    OURS("ours"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<AdminReconDiffOutcome> =
            listOf(
                UNION,
                OURS,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, AdminReconDiffOutcome> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): AdminReconDiffOutcome = byRaw[raw] ?: UNKNOWN
    }
}

/** [AdminReconDiffOutcome] 的序列化器：未知字符串得 [AdminReconDiffOutcome.UNKNOWN]；序列化写 raw。 */
object AdminReconDiffOutcomeSerializer : KSerializer<AdminReconDiffOutcome> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.AdminReconDiffOutcome", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): AdminReconDiffOutcome = AdminReconDiffOutcome.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: AdminReconDiffOutcome,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `admin_settle_bill_status`（contracts/enums/admin.yaml）。
 * GET /admin/v1/settle-bills 列表项与详情的 status 及同名筛选；读时由账单最近一轮批次（首轮 = 该账单全部 kind=main 批次；每次补充发起一轮 = kind=supplement 且 (platform, created_at) 相同的批次；轮时刻取其批次 created_at 最大值，最近一轮 = 轮时刻最大者，并列取含最大 batch_id 的轮；驳回的 CANCELLED 不参与）派生、不另存状态（settle_bills 无状态列）；撤销定时回到 DRAFT 的批次可能不在最近一轮，不影响本状态，却仍由确认接口覆盖，此时账单状态可与待确认批次不一致，待确认以批次 status=DRAFT 为准（编排裁定 D10-444）；按下列顺序取第一条成立的；中文名取 BR-TEXT-25 admin_dict
 *
 * 未知取值解码为 [AdminSettleBillStatus.UNKNOWN]，不抛错。
 */
@Serializable(with = AdminSettleBillStatusSerializer::class)
enum class AdminSettleBillStatus(
    val raw: kotlin.String,
) {
    GENERATING("generating"),
    ISSUED("issued"),
    SETTLED("settled"),
    SETTLING("settling"),
    SCHEDULED("scheduled"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<AdminSettleBillStatus> =
            listOf(
                GENERATING,
                ISSUED,
                SETTLED,
                SETTLING,
                SCHEDULED,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, AdminSettleBillStatus> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): AdminSettleBillStatus = byRaw[raw] ?: UNKNOWN
    }
}

/** [AdminSettleBillStatus] 的序列化器：未知字符串得 [AdminSettleBillStatus.UNKNOWN]；序列化写 raw。 */
object AdminSettleBillStatusSerializer : KSerializer<AdminSettleBillStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.AdminSettleBillStatus", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): AdminSettleBillStatus = AdminSettleBillStatus.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: AdminSettleBillStatus,
    ) {
        encoder.encodeString(value.raw)
    }
}
