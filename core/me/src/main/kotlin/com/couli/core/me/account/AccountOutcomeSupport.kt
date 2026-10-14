// 错误码是契约取值（contracts/error-codes.json），按码直接写
@file:Suppress("MagicNumber")

package com.couli.core.me.account

import com.couli.core.network.ErrorActionMapper
import com.couli.core.network.ResultSignals
import com.couli.generated.DefaultTexts
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** 账号类结局表共用的取值（*Raw 都是展示 / 测试标识，不是服务端字段）。 */
object AccountRaw {
    const val ENDS = "ends"
    const val KEEPS = "keeps"
    const val NEW = "new"
    const val SAME = "same"
    const val NONE = "none"

    const val APPLY = "apply"
    const val SUBMIT = "submit"
    const val CONFIRM = "confirm"
    const val ABANDON = "abandon"

    const val SETTINGS = "settings"
    const val FORCE_UPDATE = "force_update"
    const val RISK_NOTICE = "risk_notice"
    const val ERROR_10007 = "error_10007"
    const val OTHER = "other"

    const val FULL = "full"
    const val DELETION_ONLY = "deletion_only"

    const val ACCOUNT_DELETION = "account_deletion"
    const val PHONE_CHANGE = "phone_change"

    const val TITLE_KEY = "pending_confirm.title"
    const val RETRY_LATER_KEY = "pending_confirm.retry_later"
    const val CANNOT_CONFIRM_KEY = "pending_confirm.cannot_confirm"
    const val ABANDONED_KEY = "pending_confirm.abandoned"
    const val ALREADY_DONE_KEY = "pending_confirm.already_done"
    const val ABANDON_BUSY_KEY = "pending_confirm.abandon_busy"
}

/** 码的分类与文案键（统一错误消息函数：5xxxx 未收录回落 error.50001，trace6 另附）。 */
internal object AccountCodes {
    const val STEP_UP = 10003
    const val LOGIN = 10001
    const val CONSENT = 10004
    const val BIND_PHONE = 10005
    const val DELETION = 10007
    const val FORCE_UPDATE = 10405
    const val INVALID = 20001
    const val WRONG_CODE = 20002
    const val EXPIRED_CODE = 20003
    const val ABANDONED = 20903
    const val BUSY = 40901
    const val RATE_LIMITED = 42901
    const val RISK_BLOCKED = 44001
    const val NEGATIVE = 30416
    const val WITHDRAWING = 30412

    val PREREQUISITES: Set<Int> = setOf(LOGIN, CONSENT, BIND_PHONE)

    fun server(code: Int): Boolean = code / 10_000 == 5

    fun business(code: Int): Boolean = ErrorActionMapper.isBusinessCode(code)

    /** 5xxxx：已收录且非空用 error.<code>，否则 error.50001。 */
    fun serverKey(code: Int): String = "error.$code".takeIf { !DefaultTexts.text(it).isNullOrEmpty() } ?: "error.50001"

    /** 有契约键就用 error.<code>；没有为 null（调用方回落服务端 msg，G26）。 */
    fun errorKey(code: Int): String? = "error.$code".takeIf { DefaultTexts.text(it) != null }

    /** 前置补办的去向（10001 → Login；10005 → BindPhone；10004 按 consent_type，没有对应页面为 null）。 */
    fun guideRoute(
        code: Int,
        data: JsonElement,
    ): String? =
        when (code) {
            LOGIN -> {
                "Login"
            }

            BIND_PHONE -> {
                "BindPhone"
            }

            CONSENT -> {
                when (data.text("consent_type")) {
                    "labor_agreement" -> "LaborAgreement"
                    "id_verification" -> "RealName"
                    else -> null
                }
            }

            else -> {
                null
            }
        }

    /** Retry-After 原串 → 毫秒（缺省或非法 5 秒）。 */
    fun retryAfter(raw: String?): Long = ErrorActionMapper.retryAfterMillis(if (raw == null) emptyMap() else mapOf("Retry-After" to raw))

    fun trace6(traceId: String?): String? = traceId?.let(ResultSignals::trace6)
}

internal fun JsonElement?.text(key: String): String? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonElement?.long(key: String): Long? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

internal fun JsonElement?.strings(key: String): List<String> =
    ((this as? JsonObject)?.get(key) as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        .orEmpty()

internal fun Boolean.json(): JsonPrimitive = JsonPrimitive(this)

internal fun String?.json(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull

internal fun Long?.json(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull

/** 向量接线的结果输入（http / code / data / Retry-After 原串 / traceId）。 */
internal fun accountResult(
    http: Int?,
    code: Int?,
    data: JsonElement,
    retryAfter: String?,
    traceId: String?,
): AccountResult =
    AccountResult(
        http,
        code,
        data,
        retryAfterMillis = if (code == AccountCodes.RATE_LIMITED) AccountCodes.retryAfter(retryAfter) else null,
        traceId = traceId,
    )
