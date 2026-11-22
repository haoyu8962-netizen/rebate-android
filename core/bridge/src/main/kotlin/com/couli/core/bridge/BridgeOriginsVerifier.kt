package com.couli.core.bridge

import kotlinx.serialization.json.JsonObject

/**
 * 随包的配置签名公钥表（N-22，D10-2539）：按 (运行环境 id, kid) 取 ES256 公钥。生产 kid 与公钥做成构建配置、不写死；每个 env 随包
 * 两把（current + next，契约 ConfigBridgeOrigins「Key rotation」）。
 *
 * 构建配置串格式：空白或逗号分隔的若干项 `env:kid:<SPKI DER 十六进制>`；坏项（段数不对、非十六进制、不是 P-256 公钥）跳过，不崩。
 */
@Suppress("UnusedPrivateProperty", "UNUSED_PARAMETER") // N-22 接口桩：derKeys 在第 2 段实现时使用
class ConfigSignKeys(
    /** 测试注入用：(env, kid, SPKI DER)；不是 P-256 公钥的项跳过。 */
    derKeys: List<Triple<String, String, ByteArray>>,
) {
    /** 一把公钥的可见信息（不含公钥本身）。[spkiSha256Prefix16] = SPKI DER 的 sha256 十六进制前 16 位（小写）。 */
    data class Entry(
        val env: String,
        val kid: String,
        val spkiSha256Prefix16: String,
    )

    /** 全部可用的公钥，按 env、kid 排序。 */
    val entries: List<Entry> = emptyList() // TODO(N-22 第 2 段)

    /** 是否有 (env, kid) 这一把。 */
    @Suppress("FunctionOnlyReturningConstant", "UnusedParameter") // N-22 接口桩
    fun hasKey(
        env: String,
        kid: String,
    ): Boolean = false // TODO(N-22 第 2 段)

    companion object {
        /** 空表：任何 jws 都验不过（no_key_for_kid_env）。 */
        val EMPTY: ConfigSignKeys = ConfigSignKeys(emptyList())

        /** 解析构建配置串（见类注释）。 */
        @Suppress("UnusedParameter") // N-22 接口桩
        fun parse(spec: String): ConfigSignKeys = EMPTY // TODO(N-22 第 2 段)
    }
}

/** 验过签的可信 H5 源白名单：[version] 为 payload.version，[origins] 为 payload.origins 原样（每项本身就是规范化 https origin）。 */
data class BridgeOriginsAllowlist(
    val version: Long,
    val origins: List<String>,
)

/** 一次验签的判定。 */
sealed interface BridgeOriginsVerdict {
    data class Accepted(
        val allowlist: BridgeOriginsAllowlist,
    ) : BridgeOriginsVerdict

    /** [reason] 只供日志定位，取值见 [BridgeOriginsVerifier]。 */
    data class Rejected(
        val reason: String,
    ) : BridgeOriginsVerdict
}

/**
 * bridge_origins 的 ES256 JWS 验签（N-22；契约 `components.schemas.ConfigBridgeOrigins` 的 description 为准；三端共用向量
 * `NATIVE/N22-VECTORS/n22-bridge-origins-vectors.json`）。纯 Kotlin + java.security，不依赖 Android。
 *
 * 规则（顺序同向量 rules_order）：三段 base64url（`[A-Za-z0-9_-]`、无填充、每段非空）→ header.alg == "ES256" → 按 header.kid +
 * 当前运行环境 id 取随包公钥 → 签名段正好 64 字节（R‖S，IEEE P1363；转 DER 交给 `Signature("SHA256withECDSA")`），签名输入是
 * ASCII `header.payload` → header.typ == "couli-bridge-origins+jws" → payload.v == 1（整数）、app_id、env 相等 →
 * version 为整数且 ≥ 0、有上次可用时 ≥ 它（等于算通过）→ origins 非空，每项**本身就是**规范化 https origin（不得先规范化再接受）。
 *
 * reason 取向量里的那套字符串：malformed / bad_alg / no_key_for_kid_env / bad_signature_format / bad_signature / bad_typ / bad_v /
 * app_id_mismatch / env_mismatch / bad_payload / version_rollback / bad_origin。
 */
object BridgeOriginsVerifier {
    /** 接口桩的判定理由（第 2 段实现后不再出现）。 */
    const val REASON_UNIMPLEMENTED: String = "unimplemented"

    /** 验一段 JWS compact。[lastGoodVersion] 为上次可用白名单的 version，没有为 null。 */
    @Suppress("UnusedParameter") // N-22 接口桩
    fun verify(
        jws: String,
        appId: String,
        env: String,
        lastGoodVersion: Long?,
        keys: ConfigSignKeys,
    ): BridgeOriginsVerdict = BridgeOriginsVerdict.Rejected(REASON_UNIMPLEMENTED) // TODO(N-22 第 2 段)

    /**
     * 从配置文档（/v1/config 的 data 对象）取 `bridge_origins.jws` 再验；字段缺失 / 形状不对（含旧的字符串数组形状、jws 不是字符串）
     * = Rejected("malformed")。
     */
    @Suppress("UnusedParameter") // N-22 接口桩
    fun verify(
        config: JsonObject,
        appId: String,
        env: String,
        lastGoodVersion: Long?,
        keys: ConfigSignKeys,
    ): BridgeOriginsVerdict = BridgeOriginsVerdict.Rejected(REASON_UNIMPLEMENTED) // TODO(N-22 第 2 段)
}
