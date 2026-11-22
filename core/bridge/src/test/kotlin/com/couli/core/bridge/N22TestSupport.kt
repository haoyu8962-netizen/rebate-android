package com.couli.core.bridge

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * N-22 测试共用：三端共用向量与两把公钥（`src/test/resources/n22/`，原样拷自 couli-runs/NATIVE/N22-VECTORS，classpath `/n22/…`）。
 * 只读公钥；向量测试钥的私钥已删，需要现签时用 [N22TestSigner] 临时生成。
 */
internal object N22Vectors {
    const val STAGING_ENV = "staging"
    const val STAGING_KID = "config-staging-2026-11b"
    const val STAGING_SHA16 = "07ba14efc5908e5c"
    const val TEST_ENV = "test"
    const val TEST_KID = "n22-vector-test-key-1"
    const val TEST_SHA16 = "f3494fc5043fc593"
    const val VECTORS_SHA16 = "0b773cfc9a25189d"
    const val APP_ID = "couli"
    const val STG_ORIGIN = "https://h5-stg.couliapp.cn"

    /** 契约约定的 reason 字符串（只供定位；拒绝时 reason 须是其中之一）。 */
    val KNOWN_REASONS: Set<String> =
        setOf(
            "malformed",
            "bad_alg",
            "no_key_for_kid_env",
            "bad_signature_format",
            "bad_signature",
            "bad_typ",
            "bad_v",
            "app_id_mismatch",
            "env_mismatch",
            "bad_payload",
            "version_rollback",
            "bad_origin",
        )

    fun resource(name: String): ByteArray =
        checkNotNull(N22Vectors::class.java.getResourceAsStream("/n22/$name")) { "缺少测试资源 /n22/$name" }.use { it.readBytes() }

    val vectorBytes: ByteArray by lazy { resource("n22-bridge-origins-vectors.json") }

    val vectors: JsonObject by lazy { Json.parseToJsonElement(vectorBytes.decodeToString()).jsonObject }

    val cases: List<JsonObject> by lazy { vectors.getValue("cases").jsonArray.map { it.jsonObject } }

    fun case(name: String): JsonObject = cases.single { it.getValue("name").jsonPrimitive.content == name }

    fun jws(name: String): String = case(name).getValue("jws").jsonPrimitive.content

    /** PEM（SPKI）→ DER。 */
    fun pemToDer(pem: String): ByteArray =
        Base64.getMimeDecoder().decode(pem.lines().filterNot { it.startsWith("-----") }.joinToString(""))

    val stagingDer: ByteArray by lazy { pemToDer(resource("es256.pub.pem").decodeToString()) }
    val testDer: ByteArray by lazy { pemToDer(resource("n22-vector-test-key-1.pub.pem").decodeToString()) }

    /** 向量用的公钥表：staging 钥（env staging）+ 测试钥（env test）。 */
    val keys: ConfigSignKeys by lazy {
        ConfigSignKeys(listOf(Triple(STAGING_ENV, STAGING_KID, stagingDer), Triple(TEST_ENV, TEST_KID, testDer)))
    }

    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun payloadOf(jws: String): JsonObject =
        Json.parseToJsonElement(Base64.getUrlDecoder().decode(jws.split('.')[1]).decodeToString()).jsonObject

    fun describe(verdict: BridgeOriginsVerdict): String =
        when (verdict) {
            is BridgeOriginsVerdict.Accepted -> "Accepted(version=${verdict.allowlist.version}, origins=${verdict.allowlist.origins})"
            is BridgeOriginsVerdict.Rejected -> "Rejected(reason=${verdict.reason})"
        }
}

/**
 * 测试钥现签（N-22）：临时生成 EC 钥对，签出 JWS compact；签名默认转成 64 字节 R‖S（IEEE P1363），[signDer] 保留 JCA 的 DER 形式。
 * header / payload 按原始 JSON 文本给，便于造 `v: true`、`version: 1.5` 这类形状。
 */
internal class N22TestSigner(
    val env: String = N22Vectors.TEST_ENV,
    val kid: String = "n22-test-signer",
    curve: String = "secp256r1",
) {
    val keyPair: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(curve)) }.generateKeyPair()

    /** SPKI DER。 */
    val spkiDer: ByteArray get() = keyPair.public.encoded

    val keys: ConfigSignKeys get() = ConfigSignKeys(listOf(Triple(env, kid, spkiDer)))

    fun header(
        alg: String = "ES256",
        kid: String = this.kid,
        typ: String = TYP,
    ): String = """{"alg":"$alg","kid":"$kid","typ":"$typ"}"""

    /** 各字段给原始 JSON 片段（字符串要自带引号）。 */
    fun payload(
        version: String = "1",
        origins: String = """["https://h5.example.test"]""",
        v: String = "1",
        appId: String = "\"${N22Vectors.APP_ID}\"",
        env: String = "\"${this.env}\"",
    ): String = """{"v":$v,"app_id":$appId,"env":$env,"version":$version,"origins":$origins}"""

    fun sign(
        payloadJson: String = payload(),
        headerJson: String = header(),
    ): String {
        val input = b64(headerJson.encodeToByteArray()) + "." + b64(payloadJson.encodeToByteArray())
        return input + "." + b64(derToP1363(rawSign(input)))
    }

    fun signDer(
        payloadJson: String = payload(),
        headerJson: String = header(),
    ): String {
        val input = b64(headerJson.encodeToByteArray()) + "." + b64(payloadJson.encodeToByteArray())
        return input + "." + b64(rawSign(input))
    }

    private fun rawSign(input: String): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(input.toByteArray(Charsets.US_ASCII))
            sign()
        }

    companion object {
        const val TYP = "couli-bridge-origins+jws"
        private const val HALF = 32

        fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        /** JCA 的 DER（SEQUENCE { INTEGER r, INTEGER s }）→ 64 字节 R‖S。P-256 的 DER 不超过 72 字节，长度都是单字节。 */
        fun derToP1363(der: ByteArray): ByteArray {
            require(der[0] == 0x30.toByte()) { "不是 DER SEQUENCE" }
            var i = 2
            val out = ByteArray(HALF * 2)
            repeat(2) { index ->
                require(der[i] == 0x02.toByte()) { "不是 DER INTEGER" }
                val len = der[i + 1].toInt()
                val value = der.copyOfRange(i + 2, i + 2 + len).dropWhile { it == 0.toByte() }.toByteArray()
                System.arraycopy(value, 0, out, index * HALF + HALF - value.size, value.size)
                i += 2 + len
            }
            return out
        }

        /** 把 JWS 的签名段改坏（翻转一位，仍是 64 字节、仍是 base64url）。 */
        fun corruptSignature(jws: String): String {
            val parts = jws.split('.')
            val sig = Base64.getUrlDecoder().decode(parts[2])
            sig[sig.size - 1] = (sig[sig.size - 1].toInt() xor 1).toByte()
            return parts[0] + "." + parts[1] + "." + b64(sig)
        }
    }
}
