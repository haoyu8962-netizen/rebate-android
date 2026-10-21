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
 * 契约枚举 `identity_level`（contracts/enums/identity.yaml）。
 * GET /v1/me 返回的身份等级
 *
 * 未知取值解码为 [IdentityLevel.UNKNOWN]，不抛错。
 */
@Serializable(with = IdentityLevelSerializer::class)
enum class IdentityLevel(
    val raw: kotlin.String,
) {
    BASIC("basic"),
    GUEST("guest"),
    MEMBER("member"),
    PHONE("phone"),
    REALNAME("realname"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<IdentityLevel> =
            listOf(
                BASIC,
                GUEST,
                MEMBER,
                PHONE,
                REALNAME,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, IdentityLevel> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): IdentityLevel = byRaw[raw] ?: UNKNOWN
    }
}

/** [IdentityLevel] 的序列化器：未知字符串得 [IdentityLevel.UNKNOWN]；序列化写 raw。 */
object IdentityLevelSerializer : KSerializer<IdentityLevel> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.IdentityLevel", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): IdentityLevel = IdentityLevel.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: IdentityLevel,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `login_provider`（contracts/enums/identity.yaml）。
 * 第三方登录身份来源（user_oauth.provider）；手机号验证码登录不属于此列
 *
 * 未知取值解码为 [LoginProvider.UNKNOWN]，不抛错。
 */
@Serializable(with = LoginProviderSerializer::class)
enum class LoginProvider(
    val raw: kotlin.String,
) {
    WECHAT("wechat"),
    APPLE("apple"),
    HUAWEI("huawei"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<LoginProvider> =
            listOf(
                WECHAT,
                APPLE,
                HUAWEI,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, LoginProvider> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): LoginProvider = byRaw[raw] ?: UNKNOWN
    }
}

/** [LoginProvider] 的序列化器：未知字符串得 [LoginProvider.UNKNOWN]；序列化写 raw。 */
object LoginProviderSerializer : KSerializer<LoginProvider> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.LoginProvider", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): LoginProvider = LoginProvider.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: LoginProvider,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `device_id_source`（contracts/enums/identity.yaml）。
 * 设备标识哈希的来源（devices.id_source）
 *
 * 未知取值解码为 [DeviceIdSource.UNKNOWN]，不抛错。
 */
@Serializable(with = DeviceIdSourceSerializer::class)
enum class DeviceIdSource(
    val raw: kotlin.String,
) {
    IDFV("idfv"),
    ANDROID_ID("android_id"),
    OAID("oaid"),
    ODID("odid"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<DeviceIdSource> =
            listOf(
                IDFV,
                ANDROID_ID,
                OAID,
                ODID,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, DeviceIdSource> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): DeviceIdSource = byRaw[raw] ?: UNKNOWN
    }
}

/** [DeviceIdSource] 的序列化器：未知字符串得 [DeviceIdSource.UNKNOWN]；序列化写 raw。 */
object DeviceIdSourceSerializer : KSerializer<DeviceIdSource> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.DeviceIdSource", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): DeviceIdSource = DeviceIdSource.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: DeviceIdSource,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `oauth_attempt_purpose`（contracts/enums/identity.yaml）。
 * 第三方授权尝试的用途（POST /v1/auth/oauth-attempts）
 *
 * 未知取值解码为 [OauthAttemptPurpose.UNKNOWN]，不抛错。
 */
@Serializable(with = OauthAttemptPurposeSerializer::class)
enum class OauthAttemptPurpose(
    val raw: kotlin.String,
) {
    LOGIN("login"),
    STEP_UP("step_up"),
    PAYOUT_BIND("payout_bind"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<OauthAttemptPurpose> =
            listOf(
                LOGIN,
                STEP_UP,
                PAYOUT_BIND,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, OauthAttemptPurpose> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): OauthAttemptPurpose = byRaw[raw] ?: UNKNOWN
    }
}

/** [OauthAttemptPurpose] 的序列化器：未知字符串得 [OauthAttemptPurpose.UNKNOWN]；序列化写 raw。 */
object OauthAttemptPurposeSerializer : KSerializer<OauthAttemptPurpose> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.OauthAttemptPurpose", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): OauthAttemptPurpose = OauthAttemptPurpose.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: OauthAttemptPurpose,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `session_scope`（contracts/enums/identity.yaml）。
 * access_token 的 scp；登录与刷新按这次请求的客户端版本判定，含义与可调用的接口见 BR-ID-01 细则「受限会话」
 *
 * 未知取值解码为 [SessionScope.UNKNOWN]，不抛错。
 */
@Serializable(with = SessionScopeSerializer::class)
enum class SessionScope(
    val raw: kotlin.String,
) {
    FULL("full"),
    DELETION_ONLY("deletion_only"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<SessionScope> =
            listOf(
                FULL,
                DELETION_ONLY,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, SessionScope> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): SessionScope = byRaw[raw] ?: UNKNOWN
    }
}

/** [SessionScope] 的序列化器：未知字符串得 [SessionScope.UNKNOWN]；序列化写 raw。 */
object SessionScopeSerializer : KSerializer<SessionScope> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.SessionScope", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): SessionScope = SessionScope.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: SessionScope,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `h5_token_scope`（contracts/enums/identity.yaml）。
 * h5_token 的作用域；取值含义与缺省见 BR-ID-32 细则「只读作用域」
 *
 * 未知取值解码为 [H5TokenScope.UNKNOWN]，不抛错。
 */
@Serializable(with = H5TokenScopeSerializer::class)
enum class H5TokenScope(
    val raw: kotlin.String,
) {
    STANDARD("standard"),
    READ_ONLY("read_only"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<H5TokenScope> =
            listOf(
                STANDARD,
                READ_ONLY,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, H5TokenScope> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): H5TokenScope = byRaw[raw] ?: UNKNOWN
    }
}

/** [H5TokenScope] 的序列化器：未知字符串得 [H5TokenScope.UNKNOWN]；序列化写 raw。 */
object H5TokenScopeSerializer : KSerializer<H5TokenScope> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.H5TokenScope", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): H5TokenScope = H5TokenScope.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: H5TokenScope,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `sms_purpose`（contracts/enums/identity.yaml）。
 *
 * 未知取值解码为 [SmsPurpose.UNKNOWN]，不抛错。
 */
@Serializable(with = SmsPurposeSerializer::class)
enum class SmsPurpose(
    val raw: kotlin.String,
) {
    LOGIN("login"),
    BIND("bind"),
    STEP_UP("step_up"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<SmsPurpose> =
            listOf(
                LOGIN,
                BIND,
                STEP_UP,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, SmsPurpose> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): SmsPurpose = byRaw[raw] ?: UNKNOWN
    }
}

/** [SmsPurpose] 的序列化器：未知字符串得 [SmsPurpose.UNKNOWN]；序列化写 raw。 */
object SmsPurposeSerializer : KSerializer<SmsPurpose> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.SmsPurpose", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): SmsPurpose = SmsPurpose.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: SmsPurpose,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `step_up_action`（contracts/enums/identity.yaml）。
 * step_up_token 的 action，须与所调接口一致
 *
 * 未知取值解码为 [StepUpAction.UNKNOWN]，不抛错。
 */
@Serializable(with = StepUpActionSerializer::class)
enum class StepUpAction(
    val raw: kotlin.String,
) {
    WITHDRAW("withdraw"),
    PAYOUT_ACCOUNT_CHANGE("payout_account_change"),
    PHONE_CHANGE("phone_change"),
    ACCOUNT_DELETION("account_deletion"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<StepUpAction> =
            listOf(
                WITHDRAW,
                PAYOUT_ACCOUNT_CHANGE,
                PHONE_CHANGE,
                ACCOUNT_DELETION,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, StepUpAction> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): StepUpAction = byRaw[raw] ?: UNKNOWN
    }
}

/** [StepUpAction] 的序列化器：未知字符串得 [StepUpAction.UNKNOWN]；序列化写 raw。 */
object StepUpActionSerializer : KSerializer<StepUpAction> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.StepUpAction", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): StepUpAction = StepUpAction.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: StepUpAction,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `idempotency_abandon_outcome`（contracts/enums/identity.yaml）。
 * POST /v1/idempotency-keys/abandon 的 outcome
 *
 * 未知取值解码为 [IdempotencyAbandonOutcome.UNKNOWN]，不抛错。
 */
@Serializable(with = IdempotencyAbandonOutcomeSerializer::class)
enum class IdempotencyAbandonOutcome(
    val raw: kotlin.String,
) {
    ABANDONED("abandoned"),
    COMPLETED("completed"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<IdempotencyAbandonOutcome> =
            listOf(
                ABANDONED,
                COMPLETED,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, IdempotencyAbandonOutcome> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): IdempotencyAbandonOutcome = byRaw[raw] ?: UNKNOWN
    }
}

/** [IdempotencyAbandonOutcome] 的序列化器：未知字符串得 [IdempotencyAbandonOutcome.UNKNOWN]；序列化写 raw。 */
object IdempotencyAbandonOutcomeSerializer : KSerializer<IdempotencyAbandonOutcome> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.IdempotencyAbandonOutcome", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): IdempotencyAbandonOutcome = IdempotencyAbandonOutcome.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: IdempotencyAbandonOutcome,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `consent_type`（contracts/enums/identity.yaml）。
 *
 * 未知取值解码为 [ConsentType.UNKNOWN]，不抛错。
 */
@Serializable(with = ConsentTypeSerializer::class)
enum class ConsentType(
    val raw: kotlin.String,
) {
    PRIVACY("privacy"),
    AGREEMENT("agreement"),
    AI_THIRD_PARTY("ai_third_party"),
    ID_VERIFICATION("id_verification"),
    PERSONALIZATION("personalization"),
    LABOR_AGREEMENT("labor_agreement"),
    CLIPBOARD_AUTO_IMPORT("clipboard_auto_import"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<ConsentType> =
            listOf(
                PRIVACY,
                AGREEMENT,
                AI_THIRD_PARTY,
                ID_VERIFICATION,
                PERSONALIZATION,
                LABOR_AGREEMENT,
                CLIPBOARD_AUTO_IMPORT,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, ConsentType> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): ConsentType = byRaw[raw] ?: UNKNOWN
    }
}

/** [ConsentType] 的序列化器：未知字符串得 [ConsentType.UNKNOWN]；序列化写 raw。 */
object ConsentTypeSerializer : KSerializer<ConsentType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.ConsentType", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): ConsentType = ConsentType.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: ConsentType,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `consent_channel`（contracts/enums/identity.yaml）。
 * consent_records.channel，同意发生的入口；login_merge、h5_landing、withdraw_flow 只由服务端写入（登录合并、落地页注册、劳务协议签署），不经 POST /v1/consents 提交
 *
 * 未知取值解码为 [ConsentChannel.UNKNOWN]，不抛错。
 */
@Serializable(with = ConsentChannelSerializer::class)
enum class ConsentChannel(
    val raw: kotlin.String,
) {
    FIRST_LAUNCH("first_launch"),
    LOGIN_PAGE("login_page"),
    H5_LANDING("h5_landing"),
    AGENT_SHEET("agent_sheet"),
    REALNAME_SHEET("realname_sheet"),
    PRIVACY_CENTER("privacy_center"),
    LOGIN_MERGE("login_merge"),
    WITHDRAW_FLOW("withdraw_flow"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<ConsentChannel> =
            listOf(
                FIRST_LAUNCH,
                LOGIN_PAGE,
                H5_LANDING,
                AGENT_SHEET,
                REALNAME_SHEET,
                PRIVACY_CENTER,
                LOGIN_MERGE,
                WITHDRAW_FLOW,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, ConsentChannel> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): ConsentChannel = byRaw[raw] ?: UNKNOWN
    }
}

/** [ConsentChannel] 的序列化器：未知字符串得 [ConsentChannel.UNKNOWN]；序列化写 raw。 */
object ConsentChannelSerializer : KSerializer<ConsentChannel> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.ConsentChannel", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): ConsentChannel = ConsentChannel.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: ConsentChannel,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `auth_method`（contracts/enums/identity.yaml）。
 * 淘宝授权方式（auth-url 的 auth_methods、bindings 的 auth_method）；按端由服务端配置下发
 *
 * 未知取值解码为 [AuthMethod.UNKNOWN]，不抛错。
 */
@Serializable(with = AuthMethodSerializer::class)
enum class AuthMethod(
    val raw: kotlin.String,
) {
    WEB_CODE("web_code"),
    SDK_TOKEN("sdk_token"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<AuthMethod> =
            listOf(
                WEB_CODE,
                SDK_TOKEN,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, AuthMethod> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): AuthMethod = byRaw[raw] ?: UNKNOWN
    }
}

/** [AuthMethod] 的序列化器：未知字符串得 [AuthMethod.UNKNOWN]；序列化写 raw。 */
object AuthMethodSerializer : KSerializer<AuthMethod> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.AuthMethod", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): AuthMethod = AuthMethod.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: AuthMethod,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `union_binding_status`（contracts/enums/identity.yaml）。
 * 不设 cooling，释放后冷却用 released + cooldown_until；无用户自助换绑
 *
 * 未知取值解码为 [UnionBindingStatus.UNKNOWN]，不抛错。
 */
@Serializable(with = UnionBindingStatusSerializer::class)
enum class UnionBindingStatus(
    val raw: kotlin.String,
) {
    UNBOUND("unbound"),
    PENDING_AUTH("pending_auth"),
    ACTIVE("active"),
    INVALID("invalid"),
    RELEASED("released"),
    BLOCKED("blocked"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<UnionBindingStatus> =
            listOf(
                UNBOUND,
                PENDING_AUTH,
                ACTIVE,
                INVALID,
                RELEASED,
                BLOCKED,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, UnionBindingStatus> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): UnionBindingStatus = byRaw[raw] ?: UNKNOWN
    }
}

/** [UnionBindingStatus] 的序列化器：未知字符串得 [UnionBindingStatus.UNKNOWN]；序列化写 raw。 */
object UnionBindingStatusSerializer : KSerializer<UnionBindingStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.UnionBindingStatus", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): UnionBindingStatus = UnionBindingStatus.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: UnionBindingStatus,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `union_binding_blocked_reason`（contracts/enums/identity.yaml）。
 *
 * 未知取值解码为 [UnionBindingBlockedReason.UNKNOWN]，不抛错。
 */
@Serializable(with = UnionBindingBlockedReasonSerializer::class)
enum class UnionBindingBlockedReason(
    val raw: kotlin.String,
) {
    BAN("ban"),
    ADMIN_DISABLE("admin_disable"),
    DELETION("deletion"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<UnionBindingBlockedReason> =
            listOf(
                BAN,
                ADMIN_DISABLE,
                DELETION,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, UnionBindingBlockedReason> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): UnionBindingBlockedReason = byRaw[raw] ?: UNKNOWN
    }
}

/** [UnionBindingBlockedReason] 的序列化器：未知字符串得 [UnionBindingBlockedReason.UNKNOWN]；序列化写 raw。 */
object UnionBindingBlockedReasonSerializer : KSerializer<UnionBindingBlockedReason> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.UnionBindingBlockedReason", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): UnionBindingBlockedReason = UnionBindingBlockedReason.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: UnionBindingBlockedReason,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `risk_state`（contracts/enums/identity.yaml）。
 * user_risk_state.state，risk 模块唯一写者
 *
 * 未知取值解码为 [RiskState.UNKNOWN]，不抛错。
 */
@Serializable(with = RiskStateSerializer::class)
enum class RiskState(
    val raw: kotlin.String,
) {
    NORMAL("normal"),
    FROZEN("frozen"),
    APPEALING("appealing"),
    BANNED("banned"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<RiskState> =
            listOf(
                NORMAL,
                FROZEN,
                APPEALING,
                BANNED,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, RiskState> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): RiskState = byRaw[raw] ?: UNKNOWN
    }
}

/** [RiskState] 的序列化器：未知字符串得 [RiskState.UNKNOWN]；序列化写 raw。 */
object RiskStateSerializer : KSerializer<RiskState> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.RiskState", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): RiskState = RiskState.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: RiskState,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `risk_reason_category`（contracts/enums/identity.yaml）。
 * user_risk_state.reason_category，用户可见的原因类别（用户文案取字典 risk_reason.<category>）；state 不为 normal 时必填；后台封禁与冻结时必选一项（PUT /admin/v1/users/{user_id}/risk-state，CT-02j）
 *
 * 未知取值解码为 [RiskReasonCategory.UNKNOWN]，不抛错。
 */
@Serializable(with = RiskReasonCategorySerializer::class)
enum class RiskReasonCategory(
    val raw: kotlin.String,
) {
    MALICIOUS_RIGHTS("malicious_rights"),
    FRAUD_INVITE("fraud_invite"),
    ABNORMAL_TRADE("abnormal_trade"),
    ACCOUNT_SECURITY("account_security"),
    OTHER("other"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<RiskReasonCategory> =
            listOf(
                MALICIOUS_RIGHTS,
                FRAUD_INVITE,
                ABNORMAL_TRADE,
                ACCOUNT_SECURITY,
                OTHER,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, RiskReasonCategory> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): RiskReasonCategory = byRaw[raw] ?: UNKNOWN
    }
}

/** [RiskReasonCategory] 的序列化器：未知字符串得 [RiskReasonCategory.UNKNOWN]；序列化写 raw。 */
object RiskReasonCategorySerializer : KSerializer<RiskReasonCategory> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.RiskReasonCategory", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): RiskReasonCategory = RiskReasonCategory.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: RiskReasonCategory,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `realname_status`（contracts/enums/identity.yaml）。
 *
 * 未知取值解码为 [RealnameStatus.UNKNOWN]，不抛错。
 */
@Serializable(with = RealnameStatusSerializer::class)
enum class RealnameStatus(
    val raw: kotlin.String,
) {
    NONE("none"),
    VERIFIED("verified"),
    FAILED("failed"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<RealnameStatus> =
            listOf(
                NONE,
                VERIFIED,
                FAILED,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, RealnameStatus> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): RealnameStatus = byRaw[raw] ?: UNKNOWN
    }
}

/** [RealnameStatus] 的序列化器：未知字符串得 [RealnameStatus.UNKNOWN]；序列化写 raw。 */
object RealnameStatusSerializer : KSerializer<RealnameStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.RealnameStatus", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): RealnameStatus = RealnameStatus.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: RealnameStatus,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `deletion_status`（contracts/enums/identity.yaml）。
 *
 * 未知取值解码为 [DeletionStatus.UNKNOWN]，不抛错。
 */
@Serializable(with = DeletionStatusSerializer::class)
enum class DeletionStatus(
    val raw: kotlin.String,
) {
    COOLING("cooling"),
    PROCESSING("processing"),
    DONE("done"),
    CANCELLED("cancelled"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<DeletionStatus> =
            listOf(
                COOLING,
                PROCESSING,
                DONE,
                CANCELLED,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, DeletionStatus> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): DeletionStatus = byRaw[raw] ?: UNKNOWN
    }
}

/** [DeletionStatus] 的序列化器：未知字符串得 [DeletionStatus.UNKNOWN]；序列化写 raw。 */
object DeletionStatusSerializer : KSerializer<DeletionStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.DeletionStatus", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): DeletionStatus = DeletionStatus.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: DeletionStatus,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `deletion_cancel_reason`（contracts/enums/identity.yaml）。
 *
 * 未知取值解码为 [DeletionCancelReason.UNKNOWN]，不抛错。
 */
@Serializable(with = DeletionCancelReasonSerializer::class)
enum class DeletionCancelReason(
    val raw: kotlin.String,
) {
    USER("user"),
    NEGATIVE_BALANCE("negative_balance"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<DeletionCancelReason> =
            listOf(
                USER,
                NEGATIVE_BALANCE,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, DeletionCancelReason> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): DeletionCancelReason = byRaw[raw] ?: UNKNOWN
    }
}

/** [DeletionCancelReason] 的序列化器：未知字符串得 [DeletionCancelReason.UNKNOWN]；序列化写 raw。 */
object DeletionCancelReasonSerializer : KSerializer<DeletionCancelReason> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.DeletionCancelReason", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): DeletionCancelReason = DeletionCancelReason.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: DeletionCancelReason,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `tip_key`（contracts/enums/identity.yaml）。
 *
 * 未知取值解码为 [TipKey.UNKNOWN]，不抛错。
 */
@Serializable(with = TipKeySerializer::class)
enum class TipKey(
    val raw: kotlin.String,
) {
    JUMP_TIP("jump_tip"),
    INVITER_BEFORE_BUY("inviter_before_buy"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<TipKey> =
            listOf(
                JUMP_TIP,
                INVITER_BEFORE_BUY,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, TipKey> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): TipKey = byRaw[raw] ?: UNKNOWN
    }
}

/** [TipKey] 的序列化器：未知字符串得 [TipKey.UNKNOWN]；序列化写 raw。 */
object TipKeySerializer : KSerializer<TipKey> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.TipKey", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): TipKey = TipKey.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: TipKey,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `appeal_status`（contracts/enums/identity.yaml）。
 *
 * 未知取值解码为 [AppealStatus.UNKNOWN]，不抛错。
 */
@Serializable(with = AppealStatusSerializer::class)
enum class AppealStatus(
    val raw: kotlin.String,
) {
    PROCESSING("processing"),
    UPHELD("upheld"),
    REVOKED("revoked"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<AppealStatus> =
            listOf(
                PROCESSING,
                UPHELD,
                REVOKED,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, AppealStatus> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): AppealStatus = byRaw[raw] ?: UNKNOWN
    }
}

/** [AppealStatus] 的序列化器：未知字符串得 [AppealStatus.UNKNOWN]；序列化写 raw。 */
object AppealStatusSerializer : KSerializer<AppealStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.AppealStatus", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): AppealStatus = AppealStatus.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: AppealStatus,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `appeal_target_type`（contracts/enums/identity.yaml）。
 * appeals.target_type；用户侧申诉接口（POST / GET /v1/me/appeals）只用 account、order，接口里的 AppealTargetType 是本枚举的子集（04 §6.1）
 *
 * 未知取值解码为 [AppealTargetType.UNKNOWN]，不抛错。
 */
@Serializable(with = AppealTargetTypeSerializer::class)
enum class AppealTargetType(
    val raw: kotlin.String,
) {
    ACCOUNT("account"),
    ORDER("order"),
    BLOCKED_REQUEST("blocked_request"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<AppealTargetType> =
            listOf(
                ACCOUNT,
                ORDER,
                BLOCKED_REQUEST,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, AppealTargetType> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): AppealTargetType = byRaw[raw] ?: UNKNOWN
    }
}

/** [AppealTargetType] 的序列化器：未知字符串得 [AppealTargetType.UNKNOWN]；序列化写 raw。 */
object AppealTargetTypeSerializer : KSerializer<AppealTargetType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.AppealTargetType", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): AppealTargetType = AppealTargetType.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: AppealTargetType,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `blocked_request_type`（contracts/enums/identity.yaml）。
 * appeals.request_type 与 risk_hits 的请求类型；仅 target_type=blocked_request（其余为空）；编码由契约线定稿（规划/06 第 22 项 ⑤，照 08 暂定编码）；以后新增会返回 44001 的写操作，上线前在此加值
 *
 * 未知取值解码为 [BlockedRequestType.UNKNOWN]，不抛错。
 */
@Serializable(with = BlockedRequestTypeSerializer::class)
enum class BlockedRequestType(
    val raw: kotlin.String,
) {
    REGISTER("register"),
    WITHDRAW("withdraw"),
    PHONE_CHANGE("phone_change"),
    PAYOUT_ACCOUNT("payout_account"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<BlockedRequestType> =
            listOf(
                REGISTER,
                WITHDRAW,
                PHONE_CHANGE,
                PAYOUT_ACCOUNT,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, BlockedRequestType> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): BlockedRequestType = byRaw[raw] ?: UNKNOWN
    }
}

/** [BlockedRequestType] 的序列化器：未知字符串得 [BlockedRequestType.UNKNOWN]；序列化写 raw。 */
object BlockedRequestTypeSerializer : KSerializer<BlockedRequestType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.BlockedRequestType", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): BlockedRequestType = BlockedRequestType.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: BlockedRequestType,
    ) {
        encoder.encodeString(value.raw)
    }
}
