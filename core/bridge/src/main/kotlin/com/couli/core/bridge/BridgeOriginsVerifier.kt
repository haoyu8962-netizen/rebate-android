package com.couli.core.bridge

import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 随包的配置签名公钥表（N-22，D10-2539）：按 (运行环境 id, kid) 取 ES256 公钥。生产 kid 与公钥做成构建配置、不写死；每个 env 随包
 * 两把（current + next，契约 ConfigBridgeOrigins「Key rotation」）。
 *
 * 构建配置串格式：空白或逗号分隔的若干项 `env:kid:<SPKI DER 十六进制>`；坏项（段数不对、非十六进制、不是 P-256 公钥）跳过，不崩。
 */
class ConfigSignKeys(
    /** 测试注入用：(env, kid, SPKI DER)；不是 P-256 公钥的项跳过；同一 (env, kid) 出现多次时后者覆盖前者。 */
    derKeys: List<Triple<String, String, ByteArray>>,
) {
    /** 一把公钥的可见信息（不含公钥本身）。[spkiSha256Prefix16] = SPKI DER 的 sha256 十六进制前 16 位（小写）。 */
    data class Entry(
        val env: String,
        val kid: String,
        val spkiSha256Prefix16: String,
    )

    private class Loaded(
        val entry: Entry,
        val key: PublicKey,
    )

    private val byEnvKid: Map<Pair<String, String>, Loaded> =
        buildMap {
            derKeys.forEach { (env, kid, der) ->
                if (env.isEmpty() || kid.isEmpty()) return@forEach
                val key = p256PublicKey(der) ?: return@forEach
                put(env to kid, Loaded(Entry(env, kid, sha256Prefix16(der)), key))
            }
        }

    /** 全部可用的公钥，按 env、kid 排序（同一 env + kid 只一项）。 */
    val entries: List<Entry> = byEnvKid.values.map { it.entry }.sortedWith(compareBy(Entry::env, Entry::kid))

    /** 是否有 (env, kid) 这一把。 */
    fun hasKey(
        env: String,
        kid: String,
    ): Boolean = (env to kid) in byEnvKid

    /** (env, kid) 的公钥；没有为 null。 */
    internal fun publicKey(
        env: String,
        kid: String,
    ): PublicKey? = byEnvKid[env to kid]?.key

    companion object {
        private val SEPARATORS = Regex("[\\s,]+")
        private val HEX = Regex("(?:[0-9a-fA-F]{2})+")
        private const val HEX_RADIX = 16
        private const val SHA_PREFIX_CHARS = 16
        private const val ITEM_SEGMENTS = 3

        /** P-256（secp256r1）的域参数 p 与阶 n（FIPS 186-4 D.1.2.3），用来认出 P-256 公钥。 */
        private val P256_P = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", HEX_RADIX)
        private val P256_N = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", HEX_RADIX)

        /** 空表：任何 jws 都验不过（no_key_for_kid_env）。 */
        val EMPTY: ConfigSignKeys = ConfigSignKeys(emptyList())

        /** 解析构建配置串（见类注释）。 */
        fun parse(spec: String): ConfigSignKeys {
            val items = spec.split(SEPARATORS).filter { it.isNotEmpty() }
            return ConfigSignKeys(items.mapNotNull(::parseItem))
        }

        /** 一项 `env:kid:<hex>`；段数不对、十六进制不成立为 null（公钥本身是不是 P-256 由构造时判）。 */
        private fun parseItem(item: String): Triple<String, String, ByteArray>? {
            val parts = item.split(':')
            if (parts.size != ITEM_SEGMENTS || !HEX.matches(parts[2])) return null
            val hex = parts[2]
            val der = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(HEX_RADIX).toByte() }
            return Triple(parts[0], parts[1], der)
        }

        private fun sha256Prefix16(der: ByteArray): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(der)
                .joinToString("") { "%02x".format(it) }
                .take(SHA_PREFIX_CHARS)

        /** SPKI DER → P-256 公钥；解析不了或不是 P-256 为 null。 */
        private fun p256PublicKey(der: ByteArray): PublicKey? {
            val key = runCatching { KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der)) }.getOrNull() as? ECPublicKey
            val field = key?.params?.curve?.field as? ECFieldFp ?: return null
            return key.takeIf { field.p == P256_P && it.params.order == P256_N }
        }
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
    const val REASON_MALFORMED: String = "malformed"
    const val REASON_BAD_ALG: String = "bad_alg"
    const val REASON_NO_KEY: String = "no_key_for_kid_env"
    const val REASON_BAD_SIGNATURE_FORMAT: String = "bad_signature_format"
    const val REASON_BAD_SIGNATURE: String = "bad_signature"
    const val REASON_BAD_TYP: String = "bad_typ"
    const val REASON_BAD_V: String = "bad_v"
    const val REASON_APP_ID_MISMATCH: String = "app_id_mismatch"
    const val REASON_ENV_MISMATCH: String = "env_mismatch"
    const val REASON_BAD_PAYLOAD: String = "bad_payload"
    const val REASON_VERSION_ROLLBACK: String = "version_rollback"
    const val REASON_BAD_ORIGIN: String = "bad_origin"

    private const val ALG = "ES256"
    private const val TYP = "couli-bridge-origins+jws"
    private const val JWS_SEGMENTS = 3
    private const val HTTPS_DEFAULT_PORT = 443
    private const val MAX_PORT = 65535
    private const val KEY_BRIDGE_ORIGINS = "bridge_origins"
    private const val KEY_JWS = "jws"

    private val B64URL = Regex("[A-Za-z0-9_-]+")

    /** JSON 整数字面量（不含小数点与指数；布尔、字符串另行排除）。 */
    private val JSON_INTEGER = Regex("-?(?:0|[1-9][0-9]*)")

    /**
     * 规范化 https origin 的**原样**写法（与参考校验器 check-vectors.py 同一正则）：小写 LDH 主机标签、可选端口；不做任何规范化。
     * 端口另外要求无前导 0、1..65535、不是 443。
     */
    private val ORIGIN =
        Regex("https://[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*(?::([1-9][0-9]{0,4}))?")

    /** 验一段 JWS compact。[lastGoodVersion] 为上次可用白名单的 version，没有为 null。 */
    @Suppress("ReturnCount", "CyclomaticComplexMethod") // 规则逐条一个出口，顺序同向量 rules_order
    fun verify(
        jws: String,
        appId: String,
        env: String,
        lastGoodVersion: Long?,
        keys: ConfigSignKeys,
    ): BridgeOriginsVerdict {
        // 1. 三段 base64url（无填充、每段非空），三段都能解码；header 是 JSON 对象
        val parts = jws.split('.')
        if (parts.size != JWS_SEGMENTS || parts.any { !B64URL.matches(it) }) return reject(REASON_MALFORMED)
        val decoded = parts.map { decode(it) ?: return reject(REASON_MALFORMED) }
        val header = parseObject(decoded[0]) ?: return reject(REASON_MALFORMED)
        // 2. alg
        if (header.string("alg") != ALG) return reject(REASON_BAD_ALG)
        // 3. 按 kid + 当前运行环境 id 取随包公钥
        val kid = header.string("kid") ?: return reject(REASON_NO_KEY)
        val key = keys.publicKey(env, kid) ?: return reject(REASON_NO_KEY)
        // 4. 签名：正好 64 字节 R‖S（P1363），转 DER 交给 JCA；签名输入是 ASCII 的原样两段
        val signature = decoded[2]
        if (signature.size != Es256.SIGNATURE_BYTES) return reject(REASON_BAD_SIGNATURE_FORMAT)
        val input = (parts[0] + "." + parts[1]).toByteArray(Charsets.US_ASCII)
        if (!Es256.verify(key, input, signature)) return reject(REASON_BAD_SIGNATURE)
        // 5. typ（缺失也拒）
        if (header.string("typ") != TYP) return reject(REASON_BAD_TYP)
        // 6. payload：v == 1（整数，布尔 / 字符串 / 小数不算）、app_id、env
        val payload = parseObject(decoded[1]) ?: return reject(REASON_BAD_PAYLOAD)
        val v = payload["v"] as? JsonPrimitive
        if (v == null || v.isString || v.content != "1") return reject(REASON_BAD_V)
        if (payload.string("app_id") != appId) return reject(REASON_APP_ID_MISMATCH)
        if (payload.string("env") != env) return reject(REASON_ENV_MISMATCH)
        // 7. version：整数且 ≥ 0；有上次可用时 ≥ 它（相等算通过）
        val version = integer(payload["version"])
        if (version == null || version < 0) return reject(REASON_BAD_PAYLOAD)
        if (lastGoodVersion != null && version < lastGoodVersion) return reject(REASON_VERSION_ROLLBACK)
        // 8. origins：非空字符串数组，每项本身就是规范化 https origin（不先规范化再接受）
        val origins = payload["origins"] as? JsonArray
        if (origins == null || origins.isEmpty()) return reject(REASON_BAD_PAYLOAD)
        val values =
            origins.map { item ->
                val text = (item as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (text == null || !isCanonicalHttpsOrigin(text)) return reject(REASON_BAD_ORIGIN)
                text
            }
        return BridgeOriginsVerdict.Accepted(BridgeOriginsAllowlist(version, values))
    }

    /**
     * 从配置文档（/v1/config 的 data 对象）取 `bridge_origins.jws` 再验；字段缺失 / 形状不对（含旧的字符串数组形状、jws 不是字符串）
     * = Rejected("malformed")。
     */
    fun verify(
        config: JsonObject,
        appId: String,
        env: String,
        lastGoodVersion: Long?,
        keys: ConfigSignKeys,
    ): BridgeOriginsVerdict {
        val jws = jwsIn(config) ?: return reject(REASON_MALFORMED)
        return verify(jws = jws, appId = appId, env = env, lastGoodVersion = lastGoodVersion, keys = keys)
    }

    /**
     * 只供日志定位：配置文档里 bridge_origins.jws 的 header.kid（未验签的原值，调用方自行截断 / 过滤）；取不到为 null。
     */
    fun headerKidIn(config: JsonObject): String? {
        val parts = jwsIn(config)?.split('.')?.takeIf { it.size == JWS_SEGMENTS && B64URL.matches(it[0]) } ?: return null
        return decode(parts[0])?.let(::parseObject)?.string("kid")
    }

    private fun jwsIn(config: JsonObject): String? =
        ((config[KEY_BRIDGE_ORIGINS] as? JsonObject)?.get(KEY_JWS) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun reject(reason: String): BridgeOriginsVerdict = BridgeOriginsVerdict.Rejected(reason)

    /** base64url（已确认字符集、无填充）解码；长度不成立为 null。 */
    private fun decode(segment: String): ByteArray? = runCatching { Base64.getUrlDecoder().decode(segment) }.getOrNull()

    private fun parseObject(bytes: ByteArray): JsonObject? =
        runCatching { Json.parseToJsonElement(bytes.decodeToString()) }.getOrNull() as? JsonObject

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** JSON 整数（不是字符串、布尔、null、小数、指数写法），超出 Long 为 null。 */
    @Suppress("ReturnCount") // 不是原语、不是整数字面量逐项提前结束
    private fun integer(element: JsonElement?): Long? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive.isString || !JSON_INTEGER.matches(primitive.content)) return null
        return primitive.content.toLongOrNull()
    }

    @Suppress("ReturnCount") // 写法不符、没有端口、端口不是数字逐项提前结束
    private fun isCanonicalHttpsOrigin(text: String): Boolean {
        val match = ORIGIN.matchEntire(text) ?: return false
        val port = match.groupValues[1].takeIf { it.isNotEmpty() } ?: return true
        val number = port.toIntOrNull() ?: return false
        return number in 1..MAX_PORT && number != HTTPS_DEFAULT_PORT
    }
}

/** ES256（ECDSA P-256 / SHA-256）验签：JWS 的签名是 64 字节 R‖S（IEEE P1363），JCA 要 DER，这里转换后交给 `SHA256withECDSA`。 */
private object Es256 {
    const val SIGNATURE_BYTES = 64
    private const val SIGN_BIT = 0x80
    private const val DER_INTEGER: Byte = 0x02
    private const val DER_SEQUENCE: Byte = 0x30

    /** [p1363] 必须已是 64 字节；验不过、密钥或编码出错一律 false。 */
    fun verify(
        key: PublicKey,
        input: ByteArray,
        p1363: ByteArray,
    ): Boolean =
        runCatching {
            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initVerify(key)
            signature.update(input)
            signature.verify(toDer(p1363))
        }.getOrDefault(false)

    /** 64 字节 R‖S → DER SEQUENCE { INTEGER r, INTEGER s }（P-256 的长度都在单字节内）。 */
    private fun toDer(p1363: ByteArray): ByteArray {
        fun derInteger(half: ByteArray): ByteArray {
            var value = half.dropWhile { it == 0.toByte() }.toByteArray()
            if (value.isEmpty()) value = byteArrayOf(0)
            if (value[0].toInt() and SIGN_BIT != 0) value = byteArrayOf(0) + value
            return byteArrayOf(DER_INTEGER, value.size.toByte()) + value
        }

        val half = SIGNATURE_BYTES / 2
        val body = derInteger(p1363.copyOfRange(0, half)) + derInteger(p1363.copyOfRange(half, SIGNATURE_BYTES))
        return byteArrayOf(DER_SEQUENCE, body.size.toByte()) + body
    }
}
