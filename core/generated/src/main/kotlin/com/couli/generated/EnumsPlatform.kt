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
 * 契约枚举 `platform`（contracts/enums/platform.yaml）。
 * 平台字符串编码，不使用数字；天猫是淘宝的 shop_type=tmall，不单独占编码。能力与阶段由平台字典表 platforms 记录，代码不按本列表判断能力
 *
 * 未知取值解码为 [Platform.UNKNOWN]，不抛错。
 */
@Serializable(with = PlatformSerializer::class)
enum class Platform(
    val raw: kotlin.String,
) {
    TAOBAO("taobao"),
    JD("jd"),
    PDD("pdd"),
    MEITUAN("meituan"),
    VIP("vip"),
    DOUYIN("douyin"),
    ELEME("eleme"),
    KUAISHOU("kuaishou"),
    SUNING("suning"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<Platform> =
            listOf(
                TAOBAO,
                JD,
                PDD,
                MEITUAN,
                VIP,
                DOUYIN,
                ELEME,
                KUAISHOU,
                SUNING,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, Platform> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): Platform = byRaw[raw] ?: UNKNOWN
    }
}

/** [Platform] 的序列化器：未知字符串得 [Platform.UNKNOWN]；序列化写 raw。 */
object PlatformSerializer : KSerializer<Platform> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.Platform", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Platform = Platform.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: Platform,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `key_stability`（contracts/enums/platform.yaml）。
 * platforms.key_stability，平台 product_key 派生的稳定性验证结论
 *
 * 未知取值解码为 [KeyStability.UNKNOWN]，不抛错。
 */
@Serializable(with = KeyStabilitySerializer::class)
enum class KeyStability(
    val raw: kotlin.String,
) {
    UNVERIFIED("unverified"),
    STABLE_24H("stable_24h"),
    STABLE_7D("stable_7d"),
    UNSTABLE("unstable"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<KeyStability> =
            listOf(
                UNVERIFIED,
                STABLE_24H,
                STABLE_7D,
                UNSTABLE,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, KeyStability> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): KeyStability = byRaw[raw] ?: UNKNOWN
    }
}

/** [KeyStability] 的序列化器：未知字符串得 [KeyStability.UNKNOWN]；序列化写 raw。 */
object KeyStabilitySerializer : KSerializer<KeyStability> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.KeyStability", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): KeyStability = KeyStability.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: KeyStability,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `client_platform`（contracts/enums/platform.yaml）。
 * 发起请求的客户端
 *
 * 未知取值解码为 [ClientPlatform.UNKNOWN]，不抛错。
 */
@Serializable(with = ClientPlatformSerializer::class)
enum class ClientPlatform(
    val raw: kotlin.String,
) {
    IOS("ios"),
    ANDROID("android"),
    HARMONY("harmony"),
    H5("h5"),
    ADMIN("admin"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<ClientPlatform> =
            listOf(
                IOS,
                ANDROID,
                HARMONY,
                H5,
                ADMIN,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, ClientPlatform> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): ClientPlatform = byRaw[raw] ?: UNKNOWN
    }
}

/** [ClientPlatform] 的序列化器：未知字符串得 [ClientPlatform.UNKNOWN]；序列化写 raw。 */
object ClientPlatformSerializer : KSerializer<ClientPlatform> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.ClientPlatform", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): ClientPlatform = ClientPlatform.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: ClientPlatform,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `route_entry`（contracts/enums/platform.yaml）。
 * contracts/routes.json 每条路由的 entry；入口类别看 route 的来源，不看请求里的标记
 *
 * 未知取值解码为 [RouteEntry.UNKNOWN]，不抛错。
 */
@Serializable(with = RouteEntrySerializer::class)
enum class RouteEntry(
    val raw: kotlin.String,
) {
    IN_APP("in_app"),
    PUSH("push"),
    DEEPLINK("deeplink"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<RouteEntry> =
            listOf(
                IN_APP,
                PUSH,
                DEEPLINK,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, RouteEntry> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): RouteEntry = byRaw[raw] ?: UNKNOWN
    }
}

/** [RouteEntry] 的序列化器：未知字符串得 [RouteEntry.UNKNOWN]；序列化写 raw。 */
object RouteEntrySerializer : KSerializer<RouteEntry> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.RouteEntry", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): RouteEntry = RouteEntry.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: RouteEntry,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `platform_search_status`（contracts/enums/platform.yaml）。
 * /v1/config.features.search_status 的值，由 search.enabled.<platform> 派生
 *
 * 未知取值解码为 [PlatformSearchStatus.UNKNOWN]，不抛错。
 */
@Serializable(with = PlatformSearchStatusSerializer::class)
enum class PlatformSearchStatus(
    val raw: kotlin.String,
) {
    ON("on"),
    OFF("off"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<PlatformSearchStatus> =
            listOf(
                ON,
                OFF,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, PlatformSearchStatus> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): PlatformSearchStatus = byRaw[raw] ?: UNKNOWN
    }
}

/** [PlatformSearchStatus] 的序列化器：未知字符串得 [PlatformSearchStatus.UNKNOWN]；序列化写 raw。 */
object PlatformSearchStatusSerializer : KSerializer<PlatformSearchStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.PlatformSearchStatus", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): PlatformSearchStatus = PlatformSearchStatus.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: PlatformSearchStatus,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `install_channel`（contracts/enums/platform.yaml）。
 * 安装包渠道
 *
 * 未知取值解码为 [InstallChannel.UNKNOWN]，不抛错。
 */
@Serializable(with = InstallChannelSerializer::class)
enum class InstallChannel(
    val raw: kotlin.String,
) {
    APPSTORE("appstore"),
    OFFICIAL("official"),
    HUAWEI("huawei"),
    AGC("agc"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<InstallChannel> =
            listOf(
                APPSTORE,
                OFFICIAL,
                HUAWEI,
                AGC,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, InstallChannel> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): InstallChannel = byRaw[raw] ?: UNKNOWN
    }
}

/** [InstallChannel] 的序列化器：未知字符串得 [InstallChannel.UNKNOWN]；序列化写 raw。 */
object InstallChannelSerializer : KSerializer<InstallChannel> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.InstallChannel", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): InstallChannel = InstallChannel.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: InstallChannel,
    ) {
        encoder.encodeString(value.raw)
    }
}

/**
 * 契约枚举 `auth_level`（contracts/enums/platform.yaml）。
 * 接口的鉴权级别；optional = 带令牌按用户处理、不带按匿名处理
 *
 * 未知取值解码为 [AuthLevel.UNKNOWN]，不抛错。
 */
@Serializable(with = AuthLevelSerializer::class)
enum class AuthLevel(
    val raw: kotlin.String,
) {
    NONE("none"),
    OPTIONAL("optional"),
    LOGIN("login"),
    PHONE("phone"),
    REALNAME("realname"),
    UNKNOWN("__unknown__"),
    ;

    companion object {
        /** 契约顺序的全部已知取值，不含 [UNKNOWN]。 */
        val known: kotlin.collections.List<AuthLevel> =
            listOf(
                NONE,
                OPTIONAL,
                LOGIN,
                PHONE,
                REALNAME,
            )

        private val byRaw: kotlin.collections.Map<kotlin.String, AuthLevel> = known.associateBy { it.raw }

        /** 未知取值返回 [UNKNOWN]。 */
        fun fromRaw(raw: kotlin.String): AuthLevel = byRaw[raw] ?: UNKNOWN
    }
}

/** [AuthLevel] 的序列化器：未知字符串得 [AuthLevel.UNKNOWN]；序列化写 raw。 */
object AuthLevelSerializer : KSerializer<AuthLevel> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.couli.generated.AuthLevel", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): AuthLevel = AuthLevel.fromRaw(decoder.decodeString())

    override fun serialize(
        encoder: Encoder,
        value: AuthLevel,
    ) {
        encoder.encodeString(value.raw)
    }
}
