// 错误码与字段名是契约取值（contracts/openapi.json Deletion、error-codes.json），按值直接写
@file:Suppress("MagicNumber")

package com.couli.core.me.account

import com.couli.core.network.ApiResult
import com.couli.core.network.ResultSignals
import java.time.OffsetDateTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 一条注销申请（openapi `Deletion`，04 §3.2 deletion_requests；BR-ID-27）。七个字段全部必填（可空字段也必须出现）：
 * [status] cooling / processing / done / cancelled；[canCancel] 由服务端判定（客户端不按本机时钟推算，G15）；
 * [cancelReason] 只认 user / negative_balance，不认识的原值保留但只当作 cancelled（不显示原因）。
 */
data class Deletion(
    val status: String,
    val applyAt: String,
    val applyAtMillis: Long,
    val coolingUntil: String,
    val coolingUntilMillis: Long,
    val canCancel: Boolean,
    val cancelledAt: String?,
    val cancelReason: String?,
    val processedAt: String?,
) {
    /** cancel_reason 是已知值（user / negative_balance）。 */
    val cancelReasonKnown: Boolean get() = cancelReason in KNOWN_REASONS

    companion object {
        const val COOLING = "cooling"
        const val PROCESSING = "processing"
        const val DONE = "done"
        const val CANCELLED = "cancelled"
        const val REASON_USER = "user"
        const val REASON_NEGATIVE = "negative_balance"
        val STATUSES: Set<String> = setOf(COOLING, PROCESSING, DONE, CANCELLED)
        val KNOWN_REASONS: Set<String> = setOf(REASON_USER, REASON_NEGATIVE)
    }
}

/** getDeletion 的读取结论：从未申请（data=null）、一条注销申请、坏数据（整条按读取失败：加载失败态与【重试】，不补默认值）。 */
sealed interface DeletionRead {
    data object None : DeletionRead

    data class Ok(
        val deletion: Deletion,
    ) : DeletionRead

    data object Bad : DeletionRead
}

/**
 * 注销接口的手写解析（brief §2 第 1 项）：`Deletion | null`（getDeletion）、`DeletionResponse`（requestDeletion / cancelDeletion 的 data 就是 Deletion）。
 * 必填字段缺失或类型不符、不认识的 status、根不是对象 → 坏数据；不认识的 cancel_reason（字符串）只当作 cancelled。
 * step-up 与作废的结果复用 core/wallet 的 WalletJson（StepUpData、AbandonData）。
 */
object AccountJson {
    private val REQUIRED = listOf("status", "apply_at", "cooling_until", "can_cancel", "cancelled_at", "cancel_reason", "processed_at")

    /** getDeletion 的 data（null = 从未申请）。 */
    fun read(element: JsonElement?): DeletionRead =
        when (element) {
            null, JsonNull -> DeletionRead.None
            is JsonObject -> deletion(element)?.let { DeletionRead.Ok(it) } ?: DeletionRead.Bad
            else -> DeletionRead.Bad
        }

    /** requestDeletion / cancelDeletion 成功的 data（必须是一条 Deletion，null 也按坏数据）。 */
    fun response(element: JsonElement?): Deletion? = (element as? JsonObject)?.let(::deletion)

    @Suppress("ReturnCount", "CyclomaticComplexMethod") // 逐字段严格校验
    fun deletion(o: JsonObject): Deletion? {
        if (!REQUIRED.all { it in o }) return null
        val status = o.str("status") ?: return null
        if (status !in Deletion.STATUSES) return null
        val apply = o.str("apply_at") ?: return null
        val until = o.str("cooling_until") ?: return null
        val canCancel = (o["can_cancel"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: return null
        val cancelledAt = o.nullableStr("cancelled_at") ?: return null
        val reason = o.nullableStr("cancel_reason") ?: return null
        val processed = o.nullableStr("processed_at") ?: return null
        val applyMs = millis(apply) ?: return null
        val untilMs = millis(until) ?: return null
        return Deletion(status, apply, applyMs, until, untilMs, canCancel, cancelledAt.value, reason.value, processed.value)
    }

    /** 向量接线：resultRaw none / ok / bad_data；ok 时 deletion 保留 status、can_cancel、cancel_reason 原值。 */
    fun parseDeletion(element: JsonElement): JsonObject =
        when (val read = read(element)) {
            DeletionRead.None -> {
                JsonObject(mapOf("resultRaw" to JsonPrimitive("none")))
            }

            DeletionRead.Bad -> {
                JsonObject(mapOf("resultRaw" to JsonPrimitive("bad_data")))
            }

            is DeletionRead.Ok -> {
                JsonObject(mapOf("resultRaw" to JsonPrimitive("ok"), "deletion" to toJson(read.deletion)))
            }
        }

    fun toJson(d: Deletion): JsonObject =
        JsonObject(
            linkedMapOf(
                "status" to JsonPrimitive(d.status),
                "apply_at" to JsonPrimitive(d.applyAt),
                "cooling_until" to JsonPrimitive(d.coolingUntil),
                "can_cancel" to JsonPrimitive(d.canCancel),
                "cancelled_at" to (d.cancelledAt?.let(::JsonPrimitive) ?: JsonNull),
                "cancel_reason" to (d.cancelReason?.let(::JsonPrimitive) ?: JsonNull),
                "processed_at" to (d.processedAt?.let(::JsonPrimitive) ?: JsonNull),
            ),
        )

    fun millis(iso: String): Long? = runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()

    private class Nullable(
        val value: String?,
    )

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** 可空字符串字段：null → Nullable(null)；字符串 → Nullable(值)；其他类型 → null（坏数据）。 */
    private fun JsonObject.nullableStr(key: String): Nullable? =
        when (val v = this[key]) {
            JsonNull -> Nullable(null)
            is JsonPrimitive -> if (v.isString) Nullable(v.content) else null
            else -> null
        }
}

/**
 * 一次账号类请求的已解包结果（结局表的输入，与向量同形）：[http] / [code] 都为 null = 没有任何响应（超时、断连、进程被杀）；[http] 有值而 [code] 为 null =
 * 没有统一外壳的网关错误；两者都算结果未知。[retryAfterMillis] 是 42901 的禁用时长（缺省或非法 5 秒）。
 */
data class AccountResult(
    val http: Int?,
    val code: Int?,
    val data: JsonElement = JsonNull,
    val retryAfterMillis: Long? = null,
    val traceId: String? = null,
    val msg: String? = null,
) {
    val unknown: Boolean get() = code == null

    companion object {
        private const val HTTP_OK = 200

        /**
         * 普通写请求（申请、确认、提交、撤回、发码、验证）的结果。本地拒绝、会话已结束不是结果，返回 null（调用方另行处理）。
         * 10405 只消费 ApiResult 已确定的 Effect（APP-13a R1-07、共性第 12 条，G62）：网络层判为 FORCE_UPDATE 的才是业务结果（结局表进强更）；
         * REFRESH_ONLY（受支持版本的 deletion_only 只刷新）与刷新失败清会话沿用网络层结论，这里返回 null（本地没有业务结果，不按码重判强更）。
         */
        fun of(result: ApiResult): AccountResult? =
            when (result) {
                is ApiResult.Success -> {
                    AccountResult(HTTP_OK, 0, result.data ?: JsonNull, traceId = result.traceId)
                }

                is ApiResult.Failure -> {
                    if (refreshOnly(result)) {
                        null
                    } else {
                        AccountResult(
                            HTTP_OK,
                            result.code,
                            result.data,
                            retryAfterMillis = if (result.code == ResultSignals.RATE_LIMITED) ResultSignals.disableMillis(result) else null,
                            traceId = result.traceId,
                            msg = result.msg,
                        )
                    }
                }

                is ApiResult.TransportFailure -> {
                    AccountResult(result.httpStatus, null)
                }

                else -> {
                    null
                }
            }

        /** 10405 而网络层的 Effect 不是 FORCE_UPDATE（只刷新、刷新失败清会话）：本地没有业务结果，页面提示稍后再试（G62），不进强更。 */
        fun refreshOnly(result: ApiResult): Boolean =
            result is ApiResult.Failure && result.code == AccountCodes.FORCE_UPDATE &&
                result.effect.kind != com.couli.core.network.ErrorEffectKind.FORCE_UPDATE

        /** 网络层已判定进强更（10405 且 Effect 为 FORCE_UPDATE）：页面只在这时调强更出口（R1-07）。 */
        fun forcesUpdate(result: ApiResult): Boolean =
            result is ApiResult.Failure && result.code == AccountCodes.FORCE_UPDATE &&
                result.effect.kind == com.couli.core.network.ErrorEffectKind.FORCE_UPDATE

        /**
         * 作废接口的结果（ApiClient 已按原操作解包）：Abandoned → outcome=abandoned；Success → completed 且原结果成功（data 为原操作的 data）；
         * 3xxxx 的 Failure → completed 且原结果是这个码；其余按普通错误。
         */
        fun ofAbandon(result: ApiResult): AccountResult? =
            when (result) {
                is ApiResult.Abandoned -> {
                    AccountResult(HTTP_OK, 0, outcome("abandoned", null), traceId = result.traceId)
                }

                is ApiResult.Success -> {
                    AccountResult(HTTP_OK, 0, outcome("completed", original(0, result.data ?: JsonNull)), traceId = result.traceId)
                }

                is ApiResult.Failure -> {
                    if (com.couli.core.network.ErrorActionMapper
                            .isBusinessCode(result.code)
                    ) {
                        AccountResult(HTTP_OK, 0, outcome("completed", original(result.code, result.data, result.msg)))
                    } else {
                        of(result)
                    }
                }

                else -> {
                    of(result)
                }
            }

        private fun outcome(
            value: String,
            original: JsonObject?,
        ): JsonObject = JsonObject(mapOf("outcome" to JsonPrimitive(value), "original" to (original ?: JsonNull)))

        private fun original(
            code: Int,
            data: JsonElement,
            msg: String = "",
        ): JsonObject = JsonObject(mapOf("code" to JsonPrimitive(code), "msg" to JsonPrimitive(msg), "data" to data))
    }
}

/** 作废接口 data 的解读（outcome abandoned / completed 与原结果）。 */
internal class AbandonView(
    val abandoned: Boolean,
    val completed: Boolean,
    val originalCode: Int?,
    val originalData: JsonElement,
    val originalMsg: String?,
) {
    companion object {
        @Suppress("ReturnCount") // 不是对象、没有 outcome 两个出口
        fun of(data: JsonElement): AbandonView? {
            val o = data as? JsonObject ?: return null
            val outcome = (o["outcome"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val original = o["original"] as? JsonObject
            val code = (original?.get("code") as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.toInt()
            val msg = (original?.get("msg") as? JsonPrimitive)?.takeIf { it.isString }?.content
            return AbandonView(outcome == "abandoned", outcome == "completed", code, original?.get("data") ?: JsonNull, msg)
        }
    }
}
