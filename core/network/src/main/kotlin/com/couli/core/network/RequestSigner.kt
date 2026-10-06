package com.couli.core.network

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 请求签名（BR-ID-09 细则；specs/request-sign.vectors.json 为服务端与三端共用的向量）：
 *
 * 签名串 = 大写方法 + "\n" + 不含协议与主机的路径（含 "?" 后原样的查询串）+ "\n" + X-Timestamp + "\n" + X-Nonce + "\n"
 * + lowercase_hex(sha256(原始请求体字节))；X-Sign = lowercase_hex(HMAC-SHA256(install_secret 的 UTF-8 字节, 签名串))。
 * 请求体按发出的原始字节计算，不做 JSON 解析或重新序列化；空请求体对零字节求哈希。
 */
object RequestSigner {
    private const val HMAC = "HmacSHA256"
    private const val NONCE_BYTES = 16
    private const val BYTE_MASK = 0xff
    private const val MILLIS_PER_SECOND = 1000L
    private val random = SecureRandom()

    /** 签名串。[method]、[pathWithQuery] 原样参与（调用方负责传大写方法与发出的路径）。 */
    fun signingString(
        method: String,
        pathWithQuery: String,
        timestamp: String,
        nonce: String,
        body: ByteArray,
    ): String = listOf(method, pathWithQuery, timestamp, nonce, sha256Hex(body)).joinToString("\n")

    /** X-Sign。 */
    fun sign(
        installSecret: String,
        signingString: String,
    ): String {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(installSecret.toByteArray(Charsets.UTF_8), HMAC))
        return mac.doFinal(signingString.toByteArray(Charsets.UTF_8)).toHex()
    }

    /** 一组签名头。 */
    @Suppress("LongParameterList") // 签名的六个输入（BR-ID-09）逐项传入，便于对照向量
    fun headers(
        installSecret: String,
        method: String,
        pathWithQuery: String,
        body: ByteArray,
        timestamp: String,
        nonce: String,
    ): Map<String, String> =
        mapOf(
            HeaderNames.TIMESTAMP to timestamp,
            HeaderNames.NONCE to nonce,
            HeaderNames.SIGN to sign(installSecret, signingString(method, pathWithQuery, timestamp, nonce, body)),
        )

    /** X-Timestamp：Unix 秒（10 位整数串）。 */
    fun timestamp(nowMillis: Long): String = (nowMillis / MILLIS_PER_SECOND).toString()

    /** X-Nonce：32 位小写十六进制。 */
    fun nonce(): String = ByteArray(NONCE_BYTES).also(random::nextBytes).toHex()

    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) }
}
