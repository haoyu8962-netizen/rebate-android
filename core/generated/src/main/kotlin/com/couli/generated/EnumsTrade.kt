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
 * 契约枚举 `scene`（contracts/enums/trade.yaml）。
 * 转链来源，转链请求必填，缺失或非法返回 20001。watch_alert 为 MVP 预埋（开关关闭，不对用户展示，BR-WATCH-19 待确认）；taolijin 在 tlj.enabled=off 期间服务端不接受；share_ext、wechat_bot、mcp 为 P1
 *
 * 未知取值解码为 [Scene.UNKNOWN]，不抛错。
 */
@Serializable(with = SceneSerializer::class)
enum class Scene(
    val raw: kotlin.String,
) {
    SEARCH("search"),
    DETAIL("detail"),
    HOME_CARD("home_card"),
    FEED("feed"),
    CLIPBOARD("clipboard"),
    AGENT("agent"),
    SHARE("share"),
    H5("h5"),
    PUSH("push"),
    TAOLIJIN("taolijin"),
    WATCH_ALERT("watch_alert"),
    SHARE_EXT("share_ext"),
    WECHAT_BOT("wechat_bot"),
    MCP("mcp"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<Scene> =
            listOf(
                SEARCH,
                DETAIL,
                HOME_CARD,
                FEED,
                CLIPBOARD,
                AGENT,
                SHARE,
                H5,
                PUSH,
                TAOLIJIN,
                WATCH_ALERT,
                SHARE_EXT,
                WECHAT_BOT,
                MCP,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, Scene> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): Scene = byRaw[raw] ?: UNKNOWN
    }
}

/** [Scene] 的序列化器：未知字符串得 [Scene.UNKNOWN]；序列化写 raw。 */
object SceneSerializer : KSerializer<Scene> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.Scene", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Scene = Scene.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: Scene,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `pid_scene`（contracts/enums/trade.yaml）。
 * 推广位场景，服务端由 scene 推出，不由客户端传入
 *
 * 未知取值解码为 [PidScene.UNKNOWN]，不抛错。
 */
@Serializable(with = PidSceneSerializer::class)
enum class PidScene(
    val raw: kotlin.String,
) {
    SELF_BUY("self_buy"),
    AGENT("agent"),
    SHARE("share"),
    TAOLIJIN("taolijin"),
    FALLBACK("fallback"),
    QUERY("query"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<PidScene> =
            listOf(
                SELF_BUY,
                AGENT,
                SHARE,
                TAOLIJIN,
                FALLBACK,
                QUERY,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, PidScene> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): PidScene = byRaw[raw] ?: UNKNOWN
    }
}

/** [PidScene] 的序列化器：未知字符串得 [PidScene.UNKNOWN]；序列化写 raw。 */
object PidSceneSerializer : KSerializer<PidScene> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.PidScene", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): PidScene = PidScene.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: PidScene,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `buy_type`（contracts/enums/trade.yaml）。
 *
 * 未知取值解码为 [BuyType.UNKNOWN]，不抛错。
 */
@Serializable(with = BuyTypeSerializer::class)
enum class BuyType(
    val raw: kotlin.String,
) {
    SELF("self"),
    SHARE("share"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<BuyType> =
            listOf(
                SELF,
                SHARE,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, BuyType> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): BuyType = byRaw[raw] ?: UNKNOWN
    }
}

/** [BuyType] 的序列化器：未知字符串得 [BuyType.UNKNOWN]；序列化写 raw。 */
object BuyTypeSerializer : KSerializer<BuyType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.BuyType", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): BuyType = BuyType.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: BuyType,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `scene_basis`（contracts/enums/trade.yaml）。
 * buy_type 的判定依据
 *
 * 未知取值解码为 [SceneBasis.UNKNOWN]，不抛错。
 */
@Serializable(with = SceneBasisSerializer::class)
enum class SceneBasis(
    val raw: kotlin.String,
) {
    PID("pid"),
    PARAM("param"),
    FALLBACK("fallback"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<SceneBasis> =
            listOf(
                PID,
                PARAM,
                FALLBACK,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, SceneBasis> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): SceneBasis = byRaw[raw] ?: UNKNOWN
    }
}

/** [SceneBasis] 的序列化器：未知字符串得 [SceneBasis.UNKNOWN]；序列化写 raw。 */
object SceneBasisSerializer : KSerializer<SceneBasis> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.SceneBasis", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): SceneBasis = SceneBasis.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: SceneBasis,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `user_basis`（contracts/enums/trade.yaml）。
 * user_id 的来源，未归因为 null
 *
 * 未知取值解码为 [UserBasis.UNKNOWN]，不抛错。
 */
@Serializable(with = UserBasisSerializer::class)
enum class UserBasis(
    val raw: kotlin.String,
) {
    PARAM("param"),
    CLAIM("claim"),
    ADMIN("admin"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<UserBasis> =
            listOf(
                PARAM,
                CLAIM,
                ADMIN,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, UserBasis> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): UserBasis = byRaw[raw] ?: UNKNOWN
    }
}

/** [UserBasis] 的序列化器：未知字符串得 [UserBasis.UNKNOWN]；序列化写 raw。 */
object UserBasisSerializer : KSerializer<UserBasis> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.UserBasis", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): UserBasis = UserBasis.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: UserBasis,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `link_event`（contracts/enums/trade.yaml）。
 * link_logs.event；只有 convert、open 且 result_code=0 算点击证据
 *
 * 未知取值解码为 [LinkEvent.UNKNOWN]，不抛错。
 */
@Serializable(with = LinkEventSerializer::class)
enum class LinkEvent(
    val raw: kotlin.String,
) {
    CONVERT("convert"),
    PRECOMPUTE("precompute"),
    REGISTER("register"),
    OPEN("open"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<LinkEvent> =
            listOf(
                CONVERT,
                PRECOMPUTE,
                REGISTER,
                OPEN,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, LinkEvent> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): LinkEvent = byRaw[raw] ?: UNKNOWN
    }
}

/** [LinkEvent] 的序列化器：未知字符串得 [LinkEvent.UNKNOWN]；序列化写 raw。 */
object LinkEventSerializer : KSerializer<LinkEvent> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.LinkEvent", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): LinkEvent = LinkEvent.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: LinkEvent,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `installed_state`（contracts/enums/trade.yaml）。
 * open 与 convert 请求体 installed，缺省 unknown；线上取值是字符串 "true" / "false" / "unknown"，不是 JSON 布尔
 *
 * 未知取值解码为 [InstalledState.UNKNOWN]，不抛错。
 */
@Serializable(with = InstalledStateSerializer::class)
enum class InstalledState(
    val raw: kotlin.String,
) {
    TRUE("true"),
    FALSE("false"),
    UNKNOWN_VALUE("unknown"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<InstalledState> =
            listOf(
                TRUE,
                FALSE,
                UNKNOWN_VALUE,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, InstalledState> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): InstalledState = byRaw[raw] ?: UNKNOWN
    }
}

/** [InstalledState] 的序列化器：未知字符串得 [InstalledState.UNKNOWN]；序列化写 raw。 */
object InstalledStateSerializer : KSerializer<InstalledState> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.InstalledState", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): InstalledState = InstalledState.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: InstalledState,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `no_rebate_reason`（contracts/enums/trade.yaml）。
 * open 的无返利原因；客户端只能传 auth_declined、auth_failed，relation_conflict、binding_blocked 由服务端判定
 *
 * 未知取值解码为 [NoRebateReason.UNKNOWN]，不抛错。
 */
@Serializable(with = NoRebateReasonSerializer::class)
enum class NoRebateReason(
    val raw: kotlin.String,
) {
    AUTH_DECLINED("auth_declined"),
    AUTH_FAILED("auth_failed"),
    RELATION_CONFLICT("relation_conflict"),
    BINDING_BLOCKED("binding_blocked"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<NoRebateReason> =
            listOf(
                AUTH_DECLINED,
                AUTH_FAILED,
                RELATION_CONFLICT,
                BINDING_BLOCKED,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, NoRebateReason> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): NoRebateReason = byRaw[raw] ?: UNKNOWN
    }
}

/** [NoRebateReason] 的序列化器：未知字符串得 [NoRebateReason.UNKNOWN]；序列化写 raw。 */
object NoRebateReasonSerializer : KSerializer<NoRebateReason> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.NoRebateReason", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): NoRebateReason = NoRebateReason.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: NoRebateReason,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `jump_type`（contracts/enums/trade.yaml）。
 * 外跳方案 primary / fallbacks 每一项的类型
 *
 * 未知取值解码为 [JumpType.UNKNOWN]，不抛错。
 */
@Serializable(with = JumpTypeSerializer::class)
enum class JumpType(
    val raw: kotlin.String,
) {
    SDK("sdk"),
    SCHEME("scheme"),
    UNIVERSAL_LINK("universal_link"),
    H5("h5"),
    COPY_TPWD("copy_tpwd"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<JumpType> =
            listOf(
                SDK,
                SCHEME,
                UNIVERSAL_LINK,
                H5,
                COPY_TPWD,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, JumpType> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): JumpType = byRaw[raw] ?: UNKNOWN
    }
}

/** [JumpType] 的序列化器：未知字符串得 [JumpType.UNKNOWN]；序列化写 raw。 */
object JumpTypeSerializer : KSerializer<JumpType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.JumpType", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): JumpType = JumpType.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: JumpType,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `input_kind`（contracts/enums/trade.yaml）。
 * parse_input 命中项的类型
 *
 * 未知取值解码为 [InputKind.UNKNOWN]，不抛错。
 */
@Serializable(with = InputKindSerializer::class)
enum class InputKind(
    val raw: kotlin.String,
) {
    TPWD("tpwd"),
    URL("url"),
    TEXT("text"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<InputKind> =
            listOf(
                TPWD,
                URL,
                TEXT,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, InputKind> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): InputKind = byRaw[raw] ?: UNKNOWN
    }
}

/** [InputKind] 的序列化器：未知字符串得 [InputKind.UNKNOWN]；序列化写 raw。 */
object InputKindSerializer : KSerializer<InputKind> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.InputKind", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): InputKind = InputKind.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: InputKind,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `rebate_basis`（contracts/enums/trade.yaml）。
 *
 * 未知取值解码为 [RebateBasis.UNKNOWN]，不抛错。
 */
@Serializable(with = RebateBasisSerializer::class)
enum class RebateBasis(
    val raw: kotlin.String,
) {
    NORMAL("normal"),
    PRICE_COMPARE_RISK("price_compare_risk"),
    NO_REBATE("no_rebate"),
    AMOUNT_UNKNOWN("amount_unknown"),
    LOGIN_REQUIRED("login_required"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<RebateBasis> =
            listOf(
                NORMAL,
                PRICE_COMPARE_RISK,
                NO_REBATE,
                AMOUNT_UNKNOWN,
                LOGIN_REQUIRED,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, RebateBasis> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): RebateBasis = byRaw[raw] ?: UNKNOWN
    }
}

/** [RebateBasis] 的序列化器：未知字符串得 [RebateBasis.UNKNOWN]；序列化写 raw。 */
object RebateBasisSerializer : KSerializer<RebateBasis> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.RebateBasis", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): RebateBasis = RebateBasis.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: RebateBasis,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `availability`（contracts/enums/trade.yaml）。
 *
 * 未知取值解码为 [Availability.UNKNOWN]，不抛错。
 */
@Serializable(with = AvailabilitySerializer::class)
enum class Availability(
    val raw: kotlin.String,
) {
    OK("ok"),
    OFF_SHELF("off_shelf"),
    COUPON_GONE("coupon_gone"),
    REF_EXPIRED("ref_expired"),
    PRICE_UNAVAILABLE("price_unavailable"),
    UNKNOWN_VALUE("unknown"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<Availability> =
            listOf(
                OK,
                OFF_SHELF,
                COUPON_GONE,
                REF_EXPIRED,
                PRICE_UNAVAILABLE,
                UNKNOWN_VALUE,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, Availability> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): Availability = byRaw[raw] ?: UNKNOWN
    }
}

/** [Availability] 的序列化器：未知字符串得 [Availability.UNKNOWN]；序列化写 raw。 */
object AvailabilitySerializer : KSerializer<Availability> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.Availability", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Availability = Availability.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: Availability,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `match_tag`（contracts/enums/trade.yaml）。
 *
 * 未知取值解码为 [MatchTag.UNKNOWN]，不抛错。
 */
@Serializable(with = MatchTagSerializer::class)
enum class MatchTag(
    val raw: kotlin.String,
) {
    MATCHED("matched"),
    RELAXED("relaxed"),
    SPEC_UNCONFIRMED("spec_unconfirmed"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<MatchTag> =
            listOf(
                MATCHED,
                RELAXED,
                SPEC_UNCONFIRMED,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, MatchTag> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): MatchTag = byRaw[raw] ?: UNKNOWN
    }
}

/** [MatchTag] 的序列化器：未知字符串得 [MatchTag.UNKNOWN]；序列化写 raw。 */
object MatchTagSerializer : KSerializer<MatchTag> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.MatchTag", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): MatchTag = MatchTag.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: MatchTag,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `benefit`（contracts/enums/trade.yaml）。
 *
 * 未知取值解码为 [Benefit.UNKNOWN]，不抛错。
 */
@Serializable(with = BenefitSerializer::class)
enum class Benefit(
    val raw: kotlin.String,
) {
    COUPON("coupon"),
    BIG_COUPON("big_coupon"),
    TAOLIJIN("taolijin"),
    SUBSIDY("subsidy"),
    HIGH_REBATE("high_rebate"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<Benefit> =
            listOf(
                COUPON,
                BIG_COUPON,
                TAOLIJIN,
                SUBSIDY,
                HIGH_REBATE,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, Benefit> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): Benefit = byRaw[raw] ?: UNKNOWN
    }
}

/** [Benefit] 的序列化器：未知字符串得 [Benefit.UNKNOWN]；序列化写 raw。 */
object BenefitSerializer : KSerializer<Benefit> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.Benefit", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Benefit = Benefit.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: Benefit,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `sort`（contracts/enums/trade.yaml）。
 *
 * 未知取值解码为 [Sort.UNKNOWN]，不抛错。
 */
@Serializable(with = SortSerializer::class)
enum class Sort(
    val raw: kotlin.String,
) {
    RELEVANCE("relevance"),
    SALES_DESC("sales_desc"),
    FINAL_PRICE_ASC("final_price_asc"),
    REBATE_DESC("rebate_desc"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<Sort> =
            listOf(
                RELEVANCE,
                SALES_DESC,
                FINAL_PRICE_ASC,
                REBATE_DESC,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, Sort> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): Sort = byRaw[raw] ?: UNKNOWN
    }
}

/** [Sort] 的序列化器：未知字符串得 [Sort.UNKNOWN]；序列化写 raw。 */
object SortSerializer : KSerializer<Sort> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.Sort", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Sort = Sort.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: Sort,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `link_pattern_category`（contracts/enums/trade.yaml）。
 * 平台链接形态表的类别（specs/link-patterns.yaml）
 *
 * 未知取值解码为 [LinkPatternCategory.UNKNOWN]，不抛错。
 */
@Serializable(with = LinkPatternCategorySerializer::class)
enum class LinkPatternCategory(
    val raw: kotlin.String,
) {
    PRODUCT("product"),
    PROMO("promo"),
    UNION_HOST("union_host"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<LinkPatternCategory> =
            listOf(
                PRODUCT,
                PROMO,
                UNION_HOST,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, LinkPatternCategory> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): LinkPatternCategory = byRaw[raw] ?: UNKNOWN
    }
}

/** [LinkPatternCategory] 的序列化器：未知字符串得 [LinkPatternCategory.UNKNOWN]；序列化写 raw。 */
object LinkPatternCategorySerializer : KSerializer<LinkPatternCategory> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.LinkPatternCategory", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): LinkPatternCategory = LinkPatternCategory.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: LinkPatternCategory,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `link_kind`（contracts/enums/trade.yaml）。
 * App 内链接落地页（LinkLanding）取卡片时返回的 link 类别；share 即 pid_scene=share 的分享 link
 *
 * 未知取值解码为 [LinkKind.UNKNOWN]，不抛错。
 */
@Serializable(with = LinkKindSerializer::class)
enum class LinkKind(
    val raw: kotlin.String,
) {
    SHARE("share"),
    OTHER("other"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<LinkKind> =
            listOf(
                SHARE,
                OTHER,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, LinkKind> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): LinkKind = byRaw[raw] ?: UNKNOWN
    }
}

/** [LinkKind] 的序列化器：未知字符串得 [LinkKind.UNKNOWN]；序列化写 raw。 */
object LinkKindSerializer : KSerializer<LinkKind> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.LinkKind", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): LinkKind = LinkKind.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: LinkKind,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `no_rebate_cause`（contracts/enums/trade.yaml）。
 * 商品卡与 open / convert 结果的无返利原因（可空，未知值按空处理）；取值规则见 BR-PRICE-08 细则「无返利原因」
 *
 * 未知取值解码为 [NoRebateCause.UNKNOWN]，不抛错。
 */
@Serializable(with = NoRebateCauseSerializer::class)
enum class NoRebateCause(
    val raw: kotlin.String,
) {
    PRICE_COMPARE("price_compare"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<NoRebateCause> =
            listOf(
                PRICE_COMPARE,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, NoRebateCause> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): NoRebateCause = byRaw[raw] ?: UNKNOWN
    }
}

/** [NoRebateCause] 的序列化器：未知字符串得 [NoRebateCause.UNKNOWN]；序列化写 raw。 */
object NoRebateCauseSerializer : KSerializer<NoRebateCause> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.NoRebateCause", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): NoRebateCause = NoRebateCause.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: NoRebateCause,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `tlj_kind`（contracts/enums/trade.yaml）。
 * 素材淘礼金出资方判定；unknown 不宣称已判定为 third_party
 *
 * 未知取值解码为 [TljKind.UNKNOWN]，不抛错。
 */
@Serializable(with = TljKindSerializer::class)
enum class TljKind(
    val raw: kotlin.String,
) {
    OURS("ours"),
    BRAND_OPEN("brand_open"),
    THIRD_PARTY("third_party"),
    UNKNOWN_VALUE("unknown"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<TljKind> =
            listOf(
                OURS,
                BRAND_OPEN,
                THIRD_PARTY,
                UNKNOWN_VALUE,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, TljKind> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): TljKind = byRaw[raw] ?: UNKNOWN
    }
}

/** [TljKind] 的序列化器：未知字符串得 [TljKind.UNKNOWN]；序列化写 raw。 */
object TljKindSerializer : KSerializer<TljKind> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.TljKind", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): TljKind = TljKind.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: TljKind,
    ) {
        encoder.encodeString(value.raw)
    }
}
