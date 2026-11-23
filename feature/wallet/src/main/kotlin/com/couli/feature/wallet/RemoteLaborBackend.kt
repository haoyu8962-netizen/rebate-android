package com.couli.feature.wallet

import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 劳务协议的契约实现（FU-AND-LABOR-AGREEMENT-API，D10-2634；照实名 [RemoteRealNameBackend] 的先例）：经 [WalletApi]（生产为 ApiClient：签名、登录态、
 * 版本门槛都在网络层）发
 * - 读取：GET /v1/me/labor-agreement（getLaborAgreement，x-auth realname）；
 * - 签署：POST /v1/me/labor-agreement/sign（signLaborAgreement，signed + idempotent），请求体 {version: 整数, text_sha256, agreed: true}。
 *
 * 契约与 [LaborAgreementController] 的差异在这里转换（控制器不改）：契约 data.version 是整数 → 交给控制器的是版本文字；契约只给全文地址 text_url →
 * 按地址经 [fetchText] 取一次全文，放进 data.text；签署时把控制器回传的版本文字还原成整数，text_sha256 原样回传服务端给的值。
 *
 * 全文核对显式化：[fetchText] 的第二个参数是服务端给的 text_sha256（expectedSha256）。生产实现（App 的 LaborTextFetcher）按取回的原始字节算
 * sha256（小写 hex），与它相等才交出正文，否则返回 null；返回 null、空白或抛错都按加载失败（不进可签正文态）。
 *
 * 幂等键：由 [newKey] 生成、只在内存保留一把。同账号（[account]）、同版本、同摘要的签署在上次结果未知（断连）后重试沿用同一把；成功、版本或摘要
 * 变化、换账号用新键（同键异体会得 20901）。读取与签署的错误（30304、10403、10004、20001、10405 等）原样上抛，不吞、不改写。本文件不打印任何内容。
 */
class RemoteLaborBackend(
    private val api: WalletApi,
    private val fetchText: suspend (url: String, expectedSha256: String) -> String?,
    private val account: () -> String?,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) : LaborBackend {
    private val lock = Any()
    private var held: HeldSign? = null

    @Suppress("ReturnCount") // 错误原样上抛、坏数据原样交回、取不到全文、成功
    override suspend fun current(): ApiResult {
        val result = api.send(ApiCall(GET_OPERATION))
        if (result !is ApiResult.Success) return result
        val data = result.data as? JsonObject ?: return result
        val version = data.integer(FIELD_VERSION)?.takeIf { it >= 1 }
        val sha = data.string(FIELD_SHA)?.takeIf { SHA256.matches(it) }
        val url = data.string(FIELD_TEXT_URL)?.takeIf { it.isNotBlank() }
        // 坏数据（缺字段、类型不对）：原样交回，控制器按「code=0 却解析不了」显示加载失败
        if (version == null || sha == null || url == null) return result
        val text = fetch(url, sha) ?: return ApiResult.LocalReject(REASON_TEXT_UNAVAILABLE)
        val adapted =
            linkedMapOf<String, JsonPrimitive>(
                FIELD_VERSION to JsonPrimitive(version.toString()),
                FIELD_TEXT to JsonPrimitive(text),
                FIELD_SHA to JsonPrimitive(sha),
            )
        data.boolean(FIELD_NEED_SIGN)?.let { adapted[FIELD_NEED_SIGN] = JsonPrimitive(it) }
        return result.copy(data = JsonObject(adapted))
    }

    override suspend fun sign(
        version: String,
        textSha256: String,
    ): ApiResult {
        val number = version.toIntOrNull() ?: return ApiResult.LocalReject(REASON_BAD_VERSION)
        val owner = account()
        val key =
            synchronized(lock) {
                held?.takeIf { it.matches(owner, number, textSha256) }?.key
                    ?: newKey().also { held = HeldSign(owner, number, textSha256, it, unknown = false) }
            }
        val body =
            JsonObject(
                linkedMapOf(
                    FIELD_VERSION to JsonPrimitive(number),
                    FIELD_SHA to JsonPrimitive(textSha256),
                    FIELD_AGREED to JsonPrimitive(true),
                ),
            ).toString()
        val result = api.send(ApiCall(SIGN_OPERATION, body = body, idempotencyKey = key))
        synchronized(lock) {
            val current = held
            if (current != null && current.key == key) held = keyAfter(result, current)
        }
        return result
    }

    /** 取全文：把服务端给的摘要显式交给 [fetchText] 核对；空文、取不到（含摘要不符）、取文函数抛错都按取不到（取消照常向上传）。 */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // 取文失败一律按加载失败，不让异常打断页面
    private suspend fun fetch(
        url: String,
        sha: String,
    ): String? =
        try {
            fetchText(url, sha)?.takeIf { it.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    /** 内存里保留的那把键：[unknown] 表示这把键出现过结果未知（断连）。 */
    private data class HeldSign(
        val account: String?,
        val version: Int,
        val sha: String,
        val key: String,
        val unknown: Boolean,
    ) {
        fun matches(
            owner: String?,
            version: Int,
            sha: String,
        ): Boolean = owner != null && owner == account && version == this.version && sha == this.sha
    }

    private companion object {
        const val GET_OPERATION = "getLaborAgreement"
        const val SIGN_OPERATION = "signLaborAgreement"
        const val FIELD_VERSION = "version"
        const val FIELD_TEXT = "text"
        const val FIELD_TEXT_URL = "text_url"
        const val FIELD_SHA = "text_sha256"
        const val FIELD_NEED_SIGN = "need_sign"
        const val FIELD_AGREED = "agreed"
        const val REASON_TEXT_UNAVAILABLE = "labor_text_unavailable"
        const val REASON_BAD_VERSION = "labor_version_invalid"
        const val BUSY_CODE = 40901
        const val FORCE_UPDATE_CODE = 10405
        const val RATE_LIMITED_CODE = 42901
        const val CODE_CLASS = 10_000
        const val SERVER_CLASS = 5
        val SHA256 = Regex("^[0-9a-f]{64}$")

        /** 没发出或前置条件未满足（登录、重放保护、处理中）：同一次签署仍用原键（照实名 SAME_KEY_CODES）。 */
        val SAME_KEY_CODES = setOf(10001, 10005, BUSY_CODE)

        /**
         * 一次签署结果之后键的去留（null = 结束，下次用新键；照实名 keyAfter）：成功结束；断连记下「出现过结果未知」并保留；本地拒绝（没发出）保留；
         * 前置码与 40901 保留；出现过结果未知时 10405 / 5xxxx / 42901 也保留；其余（含 10004、20001）结束。
         */
        fun keyAfter(
            result: ApiResult,
            held: HeldSign,
        ): HeldSign? =
            when (result) {
                is ApiResult.TransportFailure -> held.copy(unknown = true)
                is ApiResult.LocalReject, is ApiResult.SessionEnded -> held
                is ApiResult.Failure -> held.takeIf { result.code in SAME_KEY_CODES || (held.unknown && keepsUnknownKey(result.code)) }
                else -> null
            }

        fun keepsUnknownKey(code: Int): Boolean =
            code == FORCE_UPDATE_CODE || code == RATE_LIMITED_CODE || code / CODE_CLASS == SERVER_CLASS

        fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

        fun JsonObject.integer(key: String): Int? = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

        fun JsonObject.boolean(key: String): Boolean? = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    }
}
