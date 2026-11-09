package com.couli.feature.wallet

import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallOwnership
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** 实名两个请求的发送绑定：[ownership] 带禁用期闸门与会话世代，[account] 是表单固定的账号（每次写出前网络层核对）。 */
class RealNameCallBinding(
    val ownership: CallOwnership,
    val account: String,
)

/**
 * 实名提交与实名单独同意的契约实现（D10-887，去掉 N-32 空实现）：经 [WalletApi]（生产为 ApiClient：签名、登录态、版本门槛都在网络层）发
 * - 单独同意：POST /v1/consents {type: id_verification, version, accepted: true, channel: realname_sheet, client_at}（BR-ID-25、BR-ID-12）；
 * - 实名提交：POST /v1/me/realname {name, id_no}，带调用方给的幂等键。
 *
 * 同意版本（D10-887 ①、D10-1033）：[configData] 的 legal.privacy.version，取不到回落 [consentedVersion]（本机已同意版本），都取不到不发。半屏文本目前是包内草稿，版本与展示文本的
 * 对应关系由 FU-X-REALNAME-CONSENT-VERSION-SOURCE 单独跟进。姓名、身份证号只进实名提交的请求体（不进同意记录、查询串、请求头、日志与埋点；submitRealname 不写未决记录）；
 * 本文件不打印任何内容。
 */
class RemoteRealNameBackend(
    private val api: WalletApi,
    private val configData: () -> JsonObject?,
    private val consentedVersion: () -> Int?,
    private val clock: () -> Long = System::currentTimeMillis,
) : RealNameBackend {
    override suspend fun recordConsent(): ApiResult = sendConsent(null)

    override suspend fun recordConsent(binding: RealNameCallBinding): ApiResult = sendConsent(binding)

    override suspend fun submit(
        name: String,
        idNo: String,
        idempotencyKey: String,
    ): ApiResult = sendSubmit(name, idNo, idempotencyKey, null)

    override suspend fun submit(
        name: String,
        idNo: String,
        idempotencyKey: String,
        binding: RealNameCallBinding,
    ): ApiResult = sendSubmit(name, idNo, idempotencyKey, binding)

    private suspend fun sendConsent(binding: RealNameCallBinding?): ApiResult {
        val version = consentVersion() ?: return ApiResult.LocalReject(REASON_NO_VERSION)
        val body =
            JsonObject(
                linkedMapOf(
                    "type" to JsonPrimitive(CONSENT_TYPE),
                    "version" to JsonPrimitive(version),
                    "accepted" to JsonPrimitive(true),
                    "channel" to JsonPrimitive(CONSENT_CHANNEL),
                    "client_at" to JsonPrimitive(CLIENT_AT.format(Instant.ofEpochMilli(clock()).atOffset(SHANGHAI))),
                ),
            )
        return api.send(ApiCall(RECORD_CONSENT, body = body.toString(), ownership = binding?.ownership, expectedAccount = binding?.account))
    }

    private suspend fun sendSubmit(
        name: String,
        idNo: String,
        idempotencyKey: String,
        binding: RealNameCallBinding?,
    ): ApiResult {
        val body = JsonObject(linkedMapOf("name" to JsonPrimitive(name), "id_no" to JsonPrimitive(idNo))).toString()
        return api.send(
            ApiCall(
                SUBMIT_REALNAME,
                body = body,
                idempotencyKey = idempotencyKey,
                ownership = binding?.ownership,
                expectedAccount = binding?.account,
            ),
        )
    }

    private fun consentVersion(): Int? {
        val legal = configData()?.get(CONFIG_LEGAL) as? JsonObject
        val privacy = legal?.get(CONFIG_PRIVACY) as? JsonObject
        val configured = (privacy?.get(CONFIG_VERSION) as? JsonPrimitive)?.intOrNull
        return configured?.takeIf { it >= 1 } ?: consentedVersion()?.takeIf { it >= 1 }
    }

    private companion object {
        const val SUBMIT_REALNAME = "submitRealname"
        const val RECORD_CONSENT = "recordConsent"
        const val CONSENT_TYPE = "id_verification"
        const val CONSENT_CHANNEL = "realname_sheet"
        const val CONFIG_LEGAL = "legal"
        const val CONFIG_PRIVACY = "privacy"
        const val CONFIG_VERSION = "version"
        const val REASON_NO_VERSION = "consent_version_unavailable"
        val SHANGHAI: ZoneOffset = ZoneOffset.ofHours(8)

        /** client_at：点击时刻，上海时区 ISO 8601（「2026-10-10T12:00:00+08:00」，与隐私中心同意记录同一写法）。 */
        val CLIENT_AT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT)
    }
}
