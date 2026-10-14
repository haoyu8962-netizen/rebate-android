// 错误码是契约取值（contracts/error-codes.json），按码直接写
@file:Suppress("MagicNumber")

package com.couli.core.me.account

import com.couli.core.wallet.StepUpData
import com.couli.core.wallet.WalletJson
import com.couli.generated.DefaultTexts
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 手机号的本机脱敏（BR-ID-33；G24）。 */
object PhoneMask {
    private const val PHONE_DIGITS = 11
    private const val HEAD = 3
    private const val TAIL = 4

    /** 未决摘要：取输入里的数字，恰好 11 位 → 前 3 后 4（139****1234）；否则 null（用 fallbacks 不带号码的句子）。 */
    fun summary(input: String): String? {
        val digits = input.filter { it in '0'..'9' }
        return if (digits.length == PHONE_DIGITS) digits.take(HEAD) + "****" + digits.takeLast(TAIL) else null
    }

    /** 页面展示（同 [summary]，取不到为 null）。 */
    fun mask(phone: String?): String? = phone?.let(::summary)

    private val STRIP = Regex("[\\s\\p{Z}\\u200B-\\u200D\\uFEFF\\-\\u2010-\\u2015]")
    private val PATTERN = Regex("1[3-9][0-9]{9}")
    private val ELEVEN_DIGITS = Regex("[0-9]{11}")

    /**
     * 手机号规范化结果（BR-ID-05 细则「手机号规范化」，与服务端、登录页 PhoneNumber.normalize 同一规格）：NFKC；删除空白、零宽字符与连字符；去国家码
     * （+86，或 0086 / 86 后恰为 11 位数字）；必须匹配 1[3-9] 开头的 11 位。不合法为 null。只用于限流键等本机判断，请求体保留原始输入（R1-13）。
     */
    fun normalized(raw: String): String? {
        var text =
            java.text.Normalizer
                .normalize(raw, java.text.Normalizer.Form.NFKC)
                .replace(STRIP, "")
        when {
            text.startsWith("+86") -> text = text.substring(3)
            text.startsWith("0086") && ELEVEN_DIGITS.matches(text.substring(4)) -> text = text.substring(4)
            text.startsWith("86") && ELEVEN_DIGITS.matches(text.substring(2)) -> text = text.substring(2)
        }
        return text.takeIf(PATTERN::matches)
    }
}

/** 换号第 1 步（验证当前手机号）发码与验证的结局（brief §2 第 5 项；G02、G06、G23、G38）。 */
data class PhoneChangeStep1Outcome(
    val allowed: Boolean,
    val textKey: String?,
    val clearsCode: Boolean,
    val assertive: Boolean,
    val advancesToStep2: Boolean,
    val tokenStored: Boolean,
    val disableMs: Long? = null,
    val trace6: String? = null,
    /** 验证成功的 token（只放本流程实例内存，G23）。 */
    val token: StepUpData? = null,
    /** 发码成功的 resend_after_sec（未返回为 null，调用方按 60 秒）。 */
    val resendAfterSec: Long? = null,
)

object PhoneChangeStep1Policy {
    const val SEND_CODE = "send_code"
    const val VERIFY = "verify"

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    fun of(
        eventRaw: String,
        result: AccountResult?,
        phoneKnown: Boolean,
    ): PhoneChangeStep1Outcome {
        // G02：本机取不到当前账号的号码 → 不发码、不验证（step_up.no_phone）
        if (!phoneKnown) return PhoneChangeStep1Outcome(false, "step_up.no_phone", false, false, false, false)
        val verify = eventRaw == VERIFY
        val code = result?.code ?: return PhoneChangeStep1Outcome(true, "wallet.network_error", false, true, false, false)
        return when {
            code == 0 && verify -> {
                val token = WalletJson.stepUp(result.data)
                if (token == null) {
                    PhoneChangeStep1Outcome(true, "error.50001", false, true, false, false)
                } else {
                    PhoneChangeStep1Outcome(true, null, false, false, true, true, token = token)
                }
            }

            code == 0 -> {
                PhoneChangeStep1Outcome(
                    true,
                    "phone_change.code_sent",
                    false,
                    false,
                    false,
                    false,
                    resendAfterSec = result.data.long("resend_after_sec")?.takeIf { it > 0 },
                )
            }

            code == AccountCodes.WRONG_CODE || code == AccountCodes.EXPIRED_CODE -> {
                PhoneChangeStep1Outcome(true, "error.$code", verify, true, false, false)
            }

            code == AccountCodes.RATE_LIMITED -> {
                PhoneChangeStep1Outcome(
                    true,
                    "error.42901",
                    false,
                    true,
                    false,
                    false,
                    disableMs = result.retryAfterMillis ?: AccountCodes.retryAfter(null),
                )
            }

            AccountCodes.server(code) -> {
                PhoneChangeStep1Outcome(
                    true,
                    AccountCodes.serverKey(code),
                    false,
                    true,
                    false,
                    false,
                    trace6 = AccountCodes.trace6(result.traceId),
                )
            }

            else -> {
                PhoneChangeStep1Outcome(true, AccountCodes.errorKey(code) ?: "error.50001", false, true, false, false)
            }
        }
    }

    /** 向量接线。 */
    fun of(
        eventRaw: String,
        http: Int?,
        code: Int?,
        data: JsonElement,
        phoneKnown: Boolean,
    ): JsonObject {
        val o =
            of(
                eventRaw,
                if (http == null && code == null &&
                    !phoneKnown
                ) {
                    null
                } else {
                    accountResult(http, code, data, null, null)
                },
                phoneKnown,
            )
        val map =
            linkedMapOf<String, JsonElement>(
                "allowed" to o.allowed.json(),
                "textKey" to o.textKey.json(),
                "clearsCode" to o.clearsCode.json(),
                "assertive" to o.assertive.json(),
                "advancesToStep2" to o.advancesToStep2.json(),
                "tokenStored" to o.tokenStored.json(),
            )
        o.disableMs?.let { map["disableMs"] = JsonPrimitive(it) }
        return JsonObject(map)
    }
}

/**
 * 换号第 2 步提交、待确认的确认与放弃的结局（brief §2 第 5 项；G04–G09、G23、G25、G33、G39、G40、G41）。[nextStateRaw]：done / step1 / step2 /
 * pending_confirm / force_update / blocked_dialog。BindPhone 首次绑定的待确认共用这张表（G07）。
 */
data class PhoneChangeOutcome(
    val keyEndsRaw: String,
    val nextKeyRaw: String,
    val autoResendSameKey: Boolean,
    val nextStateRaw: String,
    val textKey: String?,
    val keepsNewPhone: Boolean = true,
    val clearsCode: Boolean = false,
    val keepsToken: Boolean = true,
    val fieldErrorRaw: String = FIELD_NONE,
    val refetchMe: Boolean = false,
    val clearsAllStepUpTokens: Boolean = false,
    val stepUpAction: String? = null,
    val dialogTitleKey: String? = null,
    val disableMs: Long? = null,
    val trace6: String? = null,
    val guideRoute: String? = null,
    /** completed 时提示「已经处理完成」（与原错误 [errorKey] 同时出现，G39）。 */
    val alreadyDone: Boolean = false,
    /** completed + 3xxxx 的原错误键（G39）；其余与 [textKey] 相同。 */
    val errorKey: String? = textKey,
    val serverMsg: String? = null,
    /** 30411 的 data.reason（G08：换号页不做并号；BindPhone 首次绑定 mergeable 沿用 APP-05b 并号）。 */
    val reason: String? = null,
    val mergeTicket: String? = null,
) {
    val keyEnds: Boolean get() = keyEndsRaw == AccountRaw.ENDS

    companion object {
        const val DONE = "done"
        const val STEP1 = "step1"
        const val STEP2 = "step2"
        const val PENDING_CONFIRM = "pending_confirm"
        const val FORCE_UPDATE = "force_update"
        const val BLOCKED_DIALOG = "blocked_dialog"
        const val FIELD_NONE = "none"
        const val FIELD_PHONE = "phone"
        const val FIELD_CODE = "code"
    }
}

object PhoneChangeOutcomePolicy {
    private const val RISK_DESC = "phone_change.risk_blocked.desc"

    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    fun of(
        phaseRaw: String,
        result: AccountResult,
    ): PhoneChangeOutcome {
        if (phaseRaw == AccountRaw.ABANDON) return abandon(result)
        val confirm = phaseRaw == AccountRaw.CONFIRM
        val pending = PhoneChangeOutcome.PENDING_CONFIRM
        val stay = if (confirm) pending else PhoneChangeOutcome.STEP2
        val code =
            result.code
                ?: return PhoneChangeOutcome(
                    AccountRaw.KEEPS,
                    AccountRaw.SAME,
                    false,
                    pending,
                    if (confirm) AccountRaw.RETRY_LATER_KEY else AccountRaw.TITLE_KEY,
                )
        val data = result.data
        return when {
            code == 0 -> {
                done("phone_change.done.title")
            }

            code == AccountCodes.STEP_UP -> {
                PhoneChangeOutcome(
                    AccountRaw.KEEPS,
                    AccountRaw.SAME,
                    true,
                    stay,
                    "error.10003",
                    keepsToken = false,
                    stepUpAction = AccountRaw.PHONE_CHANGE,
                )
            }

            code == AccountCodes.FORCE_UPDATE -> {
                PhoneChangeOutcome(AccountRaw.KEEPS, AccountRaw.SAME, false, PhoneChangeOutcome.FORCE_UPDATE, "error.10405")
            }

            code in AccountCodes.PREREQUISITES -> {
                PhoneChangeOutcome(
                    AccountRaw.KEEPS,
                    if (confirm) AccountRaw.SAME else AccountRaw.NEW,
                    false,
                    stay,
                    "error.$code",
                    clearsCode = true,
                    guideRoute = AccountCodes.guideRoute(code, data),
                )
            }

            code == AccountCodes.ABANDONED -> {
                toStep1("error.20903")
            }

            AccountCodes.business(code) -> {
                val key = AccountCodes.errorKey(code)
                PhoneChangeOutcome(
                    AccountRaw.ENDS,
                    AccountRaw.NEW,
                    false,
                    PhoneChangeOutcome.STEP2,
                    key,
                    serverMsg = result.msg.takeIf { key == null },
                    reason = data.text("reason"),
                    mergeTicket = data.text("merge_ticket"),
                )
            }

            confirm -> {
                confirmOther(code, result)
            }

            code == AccountCodes.INVALID || code == AccountCodes.WRONG_CODE || code == AccountCodes.EXPIRED_CODE -> {
                val field =
                    when {
                        code != AccountCodes.INVALID -> PhoneChangeOutcome.FIELD_CODE
                        "code" in data.strings("fields") && "phone" !in data.strings("fields") -> PhoneChangeOutcome.FIELD_CODE
                        else -> PhoneChangeOutcome.FIELD_PHONE
                    }
                val key =
                    if (code == AccountCodes.INVALID) {
                        data.text("reason")?.let { "error.20001.$it" }?.takeIf { DefaultTexts.text(it) != null } ?: "error.20001"
                    } else {
                        "error.$code"
                    }
                PhoneChangeOutcome(
                    AccountRaw.ENDS,
                    AccountRaw.NEW,
                    false,
                    PhoneChangeOutcome.STEP2,
                    key,
                    clearsCode = code == AccountCodes.EXPIRED_CODE,
                    fieldErrorRaw = field,
                )
            }

            code == AccountCodes.RISK_BLOCKED -> {
                PhoneChangeOutcome(
                    AccountRaw.ENDS,
                    AccountRaw.NEW,
                    false,
                    PhoneChangeOutcome.BLOCKED_DIALOG,
                    RISK_DESC,
                    clearsCode = true,
                    dialogTitleKey = riskTitle(data.text("risk_msg_code")),
                )
            }

            code == AccountCodes.RATE_LIMITED -> {
                PhoneChangeOutcome(
                    AccountRaw.ENDS,
                    AccountRaw.NEW,
                    false,
                    PhoneChangeOutcome.STEP2,
                    "error.42901",
                    disableMs = result.retryAfterMillis ?: AccountCodes.retryAfter(null),
                )
            }

            code == AccountCodes.BUSY -> {
                PhoneChangeOutcome(AccountRaw.ENDS, AccountRaw.NEW, false, PhoneChangeOutcome.STEP2, "error.40901")
            }

            AccountCodes.server(code) -> {
                PhoneChangeOutcome(
                    AccountRaw.KEEPS,
                    AccountRaw.SAME,
                    false,
                    PhoneChangeOutcome.STEP2,
                    AccountCodes.serverKey(code),
                    trace6 = AccountCodes.trace6(result.traceId),
                )
            }

            else -> {
                // 表外 1xxxx / 2xxxx / 4xxxx（G26）：不结束键（held）
                val key = AccountCodes.errorKey(code)
                PhoneChangeOutcome(
                    AccountRaw.KEEPS,
                    AccountRaw.SAME,
                    false,
                    PhoneChangeOutcome.STEP2,
                    key,
                    serverMsg =
                        result.msg.takeIf {
                            key ==
                                null
                        },
                )
            }
        }
    }

    /** 确认阶段的非终结码：2xxxx → cannot_confirm（不标红）；其余 → retry_later。键都不结束。 */
    private fun confirmOther(
        code: Int,
        result: AccountResult,
    ): PhoneChangeOutcome {
        val pending = PhoneChangeOutcome.PENDING_CONFIRM
        return when {
            code / 10_000 == 2 -> {
                PhoneChangeOutcome(AccountRaw.KEEPS, AccountRaw.SAME, false, pending, AccountRaw.CANNOT_CONFIRM_KEY)
            }

            code == AccountCodes.RATE_LIMITED -> {
                PhoneChangeOutcome(
                    AccountRaw.KEEPS,
                    AccountRaw.SAME,
                    false,
                    pending,
                    AccountRaw.RETRY_LATER_KEY,
                    disableMs = result.retryAfterMillis ?: AccountCodes.retryAfter(null),
                )
            }

            AccountCodes.server(code) -> {
                PhoneChangeOutcome(
                    AccountRaw.KEEPS,
                    AccountRaw.SAME,
                    false,
                    pending,
                    AccountRaw.RETRY_LATER_KEY,
                    trace6 = AccountCodes.trace6(result.traceId),
                )
            }

            else -> {
                PhoneChangeOutcome(AccountRaw.KEEPS, AccountRaw.SAME, false, pending, AccountRaw.RETRY_LATER_KEY)
            }
        }
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private fun abandon(result: AccountResult): PhoneChangeOutcome {
        val pending = PhoneChangeOutcome.PENDING_CONFIRM
        val code = result.code ?: return PhoneChangeOutcome(AccountRaw.KEEPS, AccountRaw.SAME, false, pending, AccountRaw.RETRY_LATER_KEY)
        if (code == 0) {
            val view =
                AbandonView.of(result.data)
                    ?: return PhoneChangeOutcome(AccountRaw.KEEPS, AccountRaw.SAME, false, pending, AccountRaw.RETRY_LATER_KEY)
            if (view.abandoned) return toStep1(AccountRaw.ABANDONED_KEY)
            val original = view.originalCode ?: 0
            if (original == 0) return done(AccountRaw.ALREADY_DONE_KEY).copy(alreadyDone = true)
            val key = AccountCodes.errorKey(original)
            return PhoneChangeOutcome(
                AccountRaw.ENDS,
                AccountRaw.NEW,
                false,
                PhoneChangeOutcome.STEP2,
                key,
                alreadyDone = true,
                serverMsg = view.originalMsg.takeIf { key == null },
            )
        }
        return when {
            code == AccountCodes.BUSY -> {
                PhoneChangeOutcome(AccountRaw.KEEPS, AccountRaw.SAME, false, pending, AccountRaw.ABANDON_BUSY_KEY)
            }

            code == AccountCodes.ABANDONED -> {
                toStep1(AccountRaw.ABANDONED_KEY)
            }

            code == AccountCodes.FORCE_UPDATE -> {
                PhoneChangeOutcome(AccountRaw.KEEPS, AccountRaw.SAME, false, PhoneChangeOutcome.FORCE_UPDATE, "error.10405")
            }

            code in AccountCodes.PREREQUISITES -> {
                PhoneChangeOutcome(
                    AccountRaw.KEEPS,
                    AccountRaw.SAME,
                    false,
                    pending,
                    "error.$code",
                    guideRoute = AccountCodes.guideRoute(code, result.data),
                )
            }

            else -> {
                confirmOther(code, result).let {
                    if (it.textKey ==
                        AccountRaw.CANNOT_CONFIRM_KEY
                    ) {
                        it.copy(textKey = AccountRaw.RETRY_LATER_KEY, errorKey = AccountRaw.RETRY_LATER_KEY)
                    } else {
                        it
                    }
                }
            }
        }
    }

    /** 成功（G04）：进结果页；刷新 getMe、丢弃全部 action 的 step_up_token；不清会话。 */
    private fun done(key: String) =
        PhoneChangeOutcome(
            AccountRaw.ENDS,
            AccountRaw.NONE,
            false,
            PhoneChangeOutcome.DONE,
            key,
            keepsToken = false,
            refetchMe = true,
            clearsAllStepUpTokens = true,
        )

    /** 已作废（20903、abandoned，G23）：回第 1 步、清空、丢 token、重新验证当前号码。 */
    private fun toStep1(key: String) =
        PhoneChangeOutcome(
            AccountRaw.ENDS,
            AccountRaw.NEW,
            false,
            PhoneChangeOutcome.STEP1,
            key,
            keepsNewPhone = false,
            clearsCode = true,
            keepsToken = false,
        )

    /** 44001 弹窗标题：risk_msg.<code> 有文案才用，否则 error.44001（G09、G42）。 */
    fun riskTitle(riskMsgCode: String?): String =
        riskMsgCode?.let { "risk_msg.$it" }?.takeIf { !DefaultTexts.text(it).isNullOrEmpty() } ?: "error.44001"

    /** 向量接线（[tokenValid] 是夹具的说明项：token 是否有效由服务端用 10003 表达，结局表不按它推断）。 */
    @Suppress("LongParameterList", "UnusedParameter")
    fun of(
        phaseRaw: String,
        http: Int?,
        code: Int?,
        data: JsonElement,
        retryAfter: String?,
        traceId: String?,
        tokenValid: Boolean,
    ): JsonObject = json(of(phaseRaw, accountResult(http, code, data, retryAfter, traceId)))

    fun json(o: PhoneChangeOutcome): JsonObject {
        val map =
            linkedMapOf<String, JsonElement>(
                "keyEndsRaw" to JsonPrimitive(o.keyEndsRaw),
                "nextKeyRaw" to JsonPrimitive(o.nextKeyRaw),
                "autoResendSameKey" to o.autoResendSameKey.json(),
                "nextStateRaw" to JsonPrimitive(o.nextStateRaw),
                "textKey" to o.textKey.json(),
                "keepsNewPhone" to o.keepsNewPhone.json(),
                "clearsCode" to o.clearsCode.json(),
                "keepsToken" to o.keepsToken.json(),
                "fieldErrorRaw" to JsonPrimitive(o.fieldErrorRaw),
                "refetchMe" to o.refetchMe.json(),
                "clearsAllStepUpTokens" to o.clearsAllStepUpTokens.json(),
                "alreadyDone" to o.alreadyDone.json(),
                "errorKey" to o.errorKey.json(),
            )
        o.stepUpAction?.let { map["stepUpAction"] = JsonPrimitive(it) }
        o.dialogTitleKey?.let { map["dialogTitleKey"] = JsonPrimitive(it) }
        o.disableMs?.let { map["disableMs"] = JsonPrimitive(it) }
        o.trace6?.let { map["trace6"] = JsonPrimitive(it) }
        o.guideRoute?.let { map["guideRoute"] = JsonPrimitive(it) }
        return JsonObject(map)
    }
}
