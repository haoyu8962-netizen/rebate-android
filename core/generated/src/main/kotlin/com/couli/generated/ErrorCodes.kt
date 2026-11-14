// 生成物，禁止手改；来源 contract.lock（tools/codegen/gen.py 由 contracts/snapshot/ 生成）
package com.couli.generated

/** 错误码的重试类别（contracts/error-codes.yaml 的 retry_kind）。 */
enum class RetryKind(
    val raw: kotlin.String,
) {
    NEVER("never"),
    AFTER_ACTION("after_action"),
    LATER("later"),
    IMMEDIATE("immediate"),
    NOT_APPLICABLE("not_applicable"),
}

/** 一条错误码。文案不在这里，取字典 error.<code>（BR-TEXT-14）。 */
data class ErrorCodeInfo(
    val code: kotlin.Int,
    val http: kotlin.Int,
    val retryKind: RetryKind,
    /** 按 data.reason 区分的重试类别，优先于 [retryKind]。 */
    val retryKindByReason: kotlin.collections.Map<kotlin.String, RetryKind>,
    val isDeprecated: kotlin.Boolean,
    /** phase 为 P1：MVP 不返回。 */
    val isP1: kotlin.Boolean,
)

/** 错误码表（含废弃与 P1）。 */
object ErrorCodes {
    val all: kotlin.collections.Map<kotlin.Int, ErrorCodeInfo> =
        linkedMapOf(
            10001 to
                ErrorCodeInfo(
                    code = 10001,
                    http = 401,
                    retryKind = RetryKind.AFTER_ACTION,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            10002 to
                ErrorCodeInfo(
                    code = 10002,
                    http = 401,
                    retryKind = RetryKind.AFTER_ACTION,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            10003 to
                ErrorCodeInfo(
                    code = 10003,
                    http = 403,
                    retryKind = RetryKind.AFTER_ACTION,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            10004 to
                ErrorCodeInfo(
                    code = 10004,
                    http = 403,
                    retryKind = RetryKind.AFTER_ACTION,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            10005 to
                ErrorCodeInfo(
                    code = 10005,
                    http = 403,
                    retryKind = RetryKind.AFTER_ACTION,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            10006 to
                ErrorCodeInfo(
                    code = 10006,
                    http = 403,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            10007 to
                ErrorCodeInfo(
                    code = 10007,
                    http = 403,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            10008 to
                ErrorCodeInfo(
                    code = 10008,
                    http = 401,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            10009 to
                ErrorCodeInfo(
                    code = 10009,
                    http = 403,
                    retryKind = RetryKind.LATER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            10010 to
                ErrorCodeInfo(
                    code = 10010,
                    http = 403,
                    retryKind = RetryKind.AFTER_ACTION,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            10401 to
                ErrorCodeInfo(
                    code = 10401,
                    http = 401,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            10402 to
                ErrorCodeInfo(
                    code = 10402,
                    http = 401,
                    retryKind = RetryKind.AFTER_ACTION,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            10403 to
                ErrorCodeInfo(
                    code = 10403,
                    http = 403,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            10404 to
                ErrorCodeInfo(
                    code = 10404,
                    http = 401,
                    retryKind = RetryKind.NOT_APPLICABLE,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            10405 to
                ErrorCodeInfo(
                    code = 10405,
                    http = 403,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            20001 to
                ErrorCodeInfo(
                    code = 20001,
                    http = 400,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            20002 to
                ErrorCodeInfo(
                    code = 20002,
                    http = 400,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            20003 to
                ErrorCodeInfo(
                    code = 20003,
                    http = 400,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            20004 to
                ErrorCodeInfo(
                    code = 20004,
                    http = 400,
                    retryKind = RetryKind.AFTER_ACTION,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            20005 to
                ErrorCodeInfo(
                    code = 20005,
                    http = 400,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            20901 to
                ErrorCodeInfo(
                    code = 20901,
                    http = 409,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            20902 to
                ErrorCodeInfo(
                    code = 20902,
                    http = 409,
                    retryKind = RetryKind.AFTER_ACTION,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            20903 to
                ErrorCodeInfo(
                    code = 20903,
                    http = 409,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30101 to
                ErrorCodeInfo(
                    code = 30101,
                    http = 422,
                    retryKind = RetryKind.AFTER_ACTION,
                    retryKindByReason = mapOf("auth_unavailable" to RetryKind.LATER),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30102 to
                ErrorCodeInfo(
                    code = 30102,
                    http = 422,
                    retryKind = RetryKind.AFTER_ACTION,
                    retryKindByReason = mapOf("auth_unavailable" to RetryKind.LATER, "sharer_auth_invalid" to RetryKind.NEVER),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30103 to
                ErrorCodeInfo(
                    code = 30103,
                    http = 422,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30104 to
                ErrorCodeInfo(
                    code = 30104,
                    http = 422,
                    retryKind = RetryKind.AFTER_ACTION,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30111 to
                ErrorCodeInfo(
                    code = 30111,
                    http = 422,
                    retryKind = RetryKind.AFTER_ACTION,
                    retryKindByReason = mapOf("sharer_auth_invalid" to RetryKind.NEVER),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30121 to
                ErrorCodeInfo(
                    code = 30121,
                    http = 422,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30131 to
                ErrorCodeInfo(
                    code = 30131,
                    http = 422,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30132 to
                ErrorCodeInfo(
                    code = 30132,
                    http = 422,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30141 to
                ErrorCodeInfo(
                    code = 30141,
                    http = 422,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30142 to
                ErrorCodeInfo(
                    code = 30142,
                    http = 422,
                    retryKind = RetryKind.NOT_APPLICABLE,
                    retryKindByReason = emptyMap(),
                    isDeprecated = true,
                    isP1 = false,
                ),
            30143 to
                ErrorCodeInfo(
                    code = 30143,
                    http = 422,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30144 to
                ErrorCodeInfo(
                    code = 30144,
                    http = 404,
                    retryKind = RetryKind.AFTER_ACTION,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30145 to
                ErrorCodeInfo(
                    code = 30145,
                    http = 422,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30146 to
                ErrorCodeInfo(
                    code = 30146,
                    http = 422,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30147 to
                ErrorCodeInfo(
                    code = 30147,
                    http = 422,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30151 to
                ErrorCodeInfo(
                    code = 30151,
                    http = 422,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30152 to
                ErrorCodeInfo(
                    code = 30152,
                    http = 422,
                    retryKind = RetryKind.NOT_APPLICABLE,
                    retryKindByReason = emptyMap(),
                    isDeprecated = true,
                    isP1 = false,
                ),
            30153 to
                ErrorCodeInfo(
                    code = 30153,
                    http = 422,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30154 to
                ErrorCodeInfo(
                    code = 30154,
                    http = 422,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30155 to
                ErrorCodeInfo(
                    code = 30155,
                    http = 422,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30156 to
                ErrorCodeInfo(
                    code = 30156,
                    http = 422,
                    retryKind = RetryKind.AFTER_ACTION,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30201 to
                ErrorCodeInfo(
                    code = 30201,
                    http = 404,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30202 to
                ErrorCodeInfo(
                    code = 30202,
                    http = 422,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30203 to
                ErrorCodeInfo(
                    code = 30203,
                    http = 422,
                    retryKind = RetryKind.LATER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30204 to
                ErrorCodeInfo(
                    code = 30204,
                    http = 422,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30205 to
                ErrorCodeInfo(
                    code = 30205,
                    http = 422,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30206 to
                ErrorCodeInfo(
                    code = 30206,
                    http = 422,
                    retryKind = RetryKind.LATER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30301 to
                ErrorCodeInfo(
                    code = 30301,
                    http = 422,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30302 to
                ErrorCodeInfo(
                    code = 30302,
                    http = 422,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30303 to
                ErrorCodeInfo(
                    code = 30303,
                    http = 422,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = mapOf("payout_account_verify_limit" to RetryKind.LATER),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30304 to
                ErrorCodeInfo(
                    code = 30304,
                    http = 422,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30305 to
                ErrorCodeInfo(
                    code = 30305,
                    http = 422,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30306 to
                ErrorCodeInfo(
                    code = 30306,
                    http = 422,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30307 to
                ErrorCodeInfo(
                    code = 30307,
                    http = 422,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30308 to
                ErrorCodeInfo(
                    code = 30308,
                    http = 422,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30309 to
                ErrorCodeInfo(
                    code = 30309,
                    http = 422,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30401 to
                ErrorCodeInfo(
                    code = 30401,
                    http = 422,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30402 to
                ErrorCodeInfo(
                    code = 30402,
                    http = 422,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30403 to
                ErrorCodeInfo(
                    code = 30403,
                    http = 422,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30404 to
                ErrorCodeInfo(
                    code = 30404,
                    http = 422,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30405 to
                ErrorCodeInfo(
                    code = 30405,
                    http = 422,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30406 to
                ErrorCodeInfo(
                    code = 30406,
                    http = 422,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30407 to
                ErrorCodeInfo(
                    code = 30407,
                    http = 422,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30408 to
                ErrorCodeInfo(
                    code = 30408,
                    http = 422,
                    retryKind = RetryKind.LATER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30409 to
                ErrorCodeInfo(
                    code = 30409,
                    http = 422,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30410 to
                ErrorCodeInfo(
                    code = 30410,
                    http = 422,
                    retryKind = RetryKind.LATER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30411 to
                ErrorCodeInfo(
                    code = 30411,
                    http = 422,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30412 to
                ErrorCodeInfo(
                    code = 30412,
                    http = 422,
                    retryKind = RetryKind.LATER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30413 to
                ErrorCodeInfo(
                    code = 30413,
                    http = 422,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30414 to
                ErrorCodeInfo(
                    code = 30414,
                    http = 422,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30415 to
                ErrorCodeInfo(
                    code = 30415,
                    http = 422,
                    retryKind = RetryKind.LATER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30416 to
                ErrorCodeInfo(
                    code = 30416,
                    http = 422,
                    retryKind = RetryKind.LATER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30417 to
                ErrorCodeInfo(
                    code = 30417,
                    http = 422,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30418 to
                ErrorCodeInfo(
                    code = 30418,
                    http = 422,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30419 to
                ErrorCodeInfo(
                    code = 30419,
                    http = 422,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30501 to
                ErrorCodeInfo(
                    code = 30501,
                    http = 422,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30502 to
                ErrorCodeInfo(
                    code = 30502,
                    http = 422,
                    retryKind = RetryKind.LATER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30503 to
                ErrorCodeInfo(
                    code = 30503,
                    http = 422,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30504 to
                ErrorCodeInfo(
                    code = 30504,
                    http = 422,
                    retryKind = RetryKind.AFTER_ACTION,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30505 to
                ErrorCodeInfo(
                    code = 30505,
                    http = 404,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30506 to
                ErrorCodeInfo(
                    code = 30506,
                    http = 409,
                    retryKind = RetryKind.LATER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30601 to
                ErrorCodeInfo(
                    code = 30601,
                    http = 422,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30602 to
                ErrorCodeInfo(
                    code = 30602,
                    http = 422,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30603 to
                ErrorCodeInfo(
                    code = 30603,
                    http = 422,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30604 to
                ErrorCodeInfo(
                    code = 30604,
                    http = 422,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30701 to
                ErrorCodeInfo(
                    code = 30701,
                    http = 404,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30801 to
                ErrorCodeInfo(
                    code = 30801,
                    http = 422,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = true,
                ),
            30802 to
                ErrorCodeInfo(
                    code = 30802,
                    http = 422,
                    retryKind = RetryKind.AFTER_ACTION,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = true,
                ),
            30803 to
                ErrorCodeInfo(
                    code = 30803,
                    http = 409,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = true,
                ),
            30804 to
                ErrorCodeInfo(
                    code = 30804,
                    http = 409,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = true,
                ),
            30805 to
                ErrorCodeInfo(
                    code = 30805,
                    http = 422,
                    retryKind = RetryKind.LATER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = true,
                ),
            30806 to
                ErrorCodeInfo(
                    code = 30806,
                    http = 422,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = true,
                ),
            30901 to
                ErrorCodeInfo(
                    code = 30901,
                    http = 422,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            30902 to
                ErrorCodeInfo(
                    code = 30902,
                    http = 409,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            40901 to
                ErrorCodeInfo(
                    code = 40901,
                    http = 409,
                    retryKind = RetryKind.LATER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            42901 to
                ErrorCodeInfo(
                    code = 42901,
                    http = 429,
                    retryKind = RetryKind.LATER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            44001 to
                ErrorCodeInfo(
                    code = 44001,
                    http = 403,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            44002 to
                ErrorCodeInfo(
                    code = 44002,
                    http = 403,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            44003 to
                ErrorCodeInfo(
                    code = 44003,
                    http = 403,
                    retryKind = RetryKind.AFTER_ACTION,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            44004 to
                ErrorCodeInfo(
                    code = 44004,
                    http = 403,
                    retryKind = RetryKind.NEVER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            50001 to
                ErrorCodeInfo(
                    code = 50001,
                    http = 500,
                    retryKind = RetryKind.IMMEDIATE,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            50301 to
                ErrorCodeInfo(
                    code = 50301,
                    http = 503,
                    retryKind = RetryKind.LATER,
                    retryKindByReason = mapOf("maintenance" to RetryKind.LATER, "not_launched" to RetryKind.NEVER),
                    isDeprecated = false,
                    isP1 = false,
                ),
            50302 to
                ErrorCodeInfo(
                    code = 50302,
                    http = 503,
                    retryKind = RetryKind.LATER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            50303 to
                ErrorCodeInfo(
                    code = 50303,
                    http = 503,
                    retryKind = RetryKind.LATER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            50304 to
                ErrorCodeInfo(
                    code = 50304,
                    http = 503,
                    retryKind = RetryKind.LATER,
                    retryKindByReason = mapOf("search_disabled" to RetryKind.NEVER, "rank_unavailable" to RetryKind.NEVER),
                    isDeprecated = false,
                    isP1 = false,
                ),
            50305 to
                ErrorCodeInfo(
                    code = 50305,
                    http = 503,
                    retryKind = RetryKind.LATER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            50306 to
                ErrorCodeInfo(
                    code = 50306,
                    http = 503,
                    retryKind = RetryKind.LATER,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
            50401 to
                ErrorCodeInfo(
                    code = 50401,
                    http = 504,
                    retryKind = RetryKind.IMMEDIATE,
                    retryKindByReason = emptyMap(),
                    isDeprecated = false,
                    isP1 = false,
                ),
        )

    fun info(code: kotlin.Int): ErrorCodeInfo? = all[code]
}
