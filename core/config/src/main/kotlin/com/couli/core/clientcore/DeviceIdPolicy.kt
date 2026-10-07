package com.couli.core.clientcore

import java.security.MessageDigest
import java.util.Locale

/** 客户端端别（client_platform 的端侧取值，三端测试接线名）。 */
object ClientPlatformRaw {
    const val IOS = "ios"
    const val ANDROID = "android"
    const val HARMONY = "harmony"
}

/** 隐私状态（与 client-core-vectors 的 privacy 同名）。 */
object PrivacyStateRaw {
    const val NOT_DECIDED = "not_decided"
    const val CONSENTED = "consented"
    const val BASIC_MODE = "basic_mode"
}

/**
 * 设备标识的判定结果。
 * @property register 可以注册设备（求哈希并上报）。
 * @property reasonRaw 不注册的原因（[DeviceIdPolicy] 的 REASON_*；没有稳定枚举，测试不锁死）；可以注册时为 null。
 * @property idSource 请求体的 id_source（device_id_source）；不注册时为 null。
 * @property deviceHash device_hash；不注册时为 null（无效值不求哈希上报）。
 */
data class DeviceIdDecision(
    val register: Boolean,
    val reasonRaw: String?,
    val idSource: String?,
    val deviceHash: String?,
)

/**
 * 设备标识的有效性与 device_hash（BR-ID-09 细则「设备标识的无效值」；specs/device-hash.vectors.json）。纯函数，三端同一规则。
 *
 * 判定顺序：隐私不是已同意 → 不注册（BR-ID-11：同意前连读取都不允许，调用方先判隐私再读标识）→ 端别不认识 → 取不到 / 去首尾空白后为空
 * → 去掉 `0` 与 `-` 后为空（全零）→ 哈希命中无效设备哈希清单的种子（[DeviceHashSeeds]，已知公共固定值等）→ 有效。
 * TODO(N-21): 各标识的长度、字符集规则 BR-ID-09 没有给出，不自造格式校验（rule-gaps 第 1 条）。
 *
 * Android 只用 ANDROID_ID（id_source = android_id；MVP 不取 OAID）；iOS 用 IDFV（idfv）；鸿蒙用 ODID（odid）。
 */
object DeviceIdPolicy {
    const val REASON_PRIVACY = "privacy_not_consented"
    const val REASON_PLATFORM = "unknown_platform"
    const val REASON_UNAVAILABLE = "unavailable"
    const val REASON_EMPTY = "empty"
    const val REASON_ALL_ZERO = "all_zero"
    const val REASON_KNOWN_INVALID = "known_invalid_hash"

    private const val BYTE_MASK = 0xff

    /** 首尾去掉的空白（device-hash.vectors.json：向量只用 ASCII 空白）。 */
    private val ASCII_WHITESPACE = charArrayOf(' ', '\t', '\n', '\r', '\u000B', '\u000C')

    /** 本端的 id_source；不认识的端别为 null。 */
    fun idSource(platform: String): String? =
        when (platform) {
            ClientPlatformRaw.ANDROID -> "android_id"
            ClientPlatformRaw.IOS -> "idfv"
            ClientPlatformRaw.HARMONY -> "odid"
            else -> null
        }

    /** 哈希输入：去首尾空白并转小写。 */
    fun normalize(rawId: String): String = rawId.trim { it in ASCII_WHITESPACE }.lowercase(Locale.ROOT)

    /** device_hash = lowercase_hex(SHA-256(normalize(rawId) 的 UTF-8 字节))。只对有效值调用（无效值不求哈希上报）。 */
    fun hash(rawId: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(normalize(rawId).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) }

    /** 标识本身是否有效（不看隐私）：不为空、不是全零、哈希不在种子清单里。 */
    fun isValid(rawId: String?): Boolean = invalidReason(rawId) == null

    @Suppress("ReturnCount") // 逐条无效规则
    private fun invalidReason(rawId: String?): String? {
        val raw = rawId ?: return REASON_UNAVAILABLE
        val normalized = normalize(raw)
        if (normalized.isEmpty()) return REASON_EMPTY
        if (normalized.all { it == '0' || it == '-' }) return REASON_ALL_ZERO
        if (hash(raw) in DeviceHashSeeds.invalidHashes) return REASON_KNOWN_INVALID
        return null
    }

    /** 能否注册设备（client-core-vectors 的 device 组）。 */
    @Suppress("ReturnCount") // 按判定顺序逐条返回
    fun evaluate(
        platform: String,
        rawId: String?,
        privacy: String,
    ): DeviceIdDecision {
        if (privacy != PrivacyStateRaw.CONSENTED) return reject(REASON_PRIVACY)
        val source = idSource(platform) ?: return reject(REASON_PLATFORM)
        invalidReason(rawId)?.let { return reject(it) }
        return DeviceIdDecision(register = true, reasonRaw = null, idSource = source, deviceHash = hash(checkNotNull(rawId)))
    }

    private fun reject(reason: String) = DeviceIdDecision(register = false, reasonRaw = reason, idSource = null, deviceHash = null)
}
