package com.couli.core.wallet

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 收款账号页的显示（PayoutAccountBound / BoundBank）：只显示服务端返回的脱敏字段（BR-WDR-02、BR-ID-33）。 */
data class PayoutAccountDisplay(
    val bound: Boolean,
    val methodRaw: String? = null,
    val maskedAccount: String? = null,
    val bankName: String? = null,
    val maskedPayee: String? = null,
    val changeRemaining: Long? = null,
    /** 认识的方式（alipay、bank_card）才出【更换收款账号】；不认识的（含 wechat）只显示脱敏串。 */
    val known: Boolean = false,
) {
    /** 「支付宝 138****5678」「招商银行 尾号 1234」（Withdraw 收款账号行、待确认句的 {payout_account}）。 */
    fun line(texts: WalletTexts): String? {
        if (!bound) return null
        val account = maskedAccount.orEmpty()
        return when (methodRaw) {
            WalletJson.ALIPAY -> texts.text("payout.channel.alipay") + " " + account
            WalletJson.BANK_CARD -> listOfNotNull(bankName?.takeIf { it.isNotBlank() }, account).joinToString(" ")
            else -> account
        }
    }
}

object PayoutAccountView {
    fun view(account: PayoutAccount): PayoutAccountDisplay =
        when (account) {
            PayoutAccount.Unbound -> {
                PayoutAccountDisplay(bound = false)
            }

            is PayoutAccount.Bound -> {
                PayoutAccountDisplay(
                    bound = true,
                    methodRaw = account.method,
                    maskedAccount = account.maskedAccount,
                    bankName = account.bankName,
                    maskedPayee = account.maskedPayeeName,
                    changeRemaining = account.changeRemainingThisMonth,
                    known = account.known,
                )
            }
        }

    /** 向量接线。 */
    fun of(data: JsonObject): JsonObject {
        val parsed = WalletJson.payoutAccount(data) ?: throw IllegalArgumentException("bad payout account")
        val view = view(parsed)
        if (!view.bound) return jsonObj("bound" to false.json())
        return jsonObj(
            "bound" to true.json(),
            "methodRaw" to view.methodRaw.json(),
            "maskedAccount" to view.maskedAccount.json(),
            "bankName" to view.bankName.json(),
            "maskedPayee" to view.maskedPayee.json(),
            "changeRemaining" to view.changeRemaining.json(),
        )
    }
}

/** 收款账号表单（用户填写的原文；只在内存，不进 SavedStateHandle、日志与截图）。 */
data class PayoutForm(
    val method: String = WalletJson.ALIPAY,
    val alipayLogonId: String = "",
    val cardNo: String = "",
    val bankName: String = "",
    val payeeName: String = "",
) {
    /** 不打印完整内容（BR-ID-33 细则）。 */
    override fun toString(): String = "PayoutForm(method=$method)"
}

/** 表单规整的结果：[body] 为 savePayoutAccount 的请求体（不合格为 null），[fieldErrors] 为要在旁边提示的字段名（契约字段名）。 */
data class PayoutFormResult(
    val body: JsonObject?,
    val fieldErrors: List<String>,
)

/**
 * 收款账号表单（G12、G13、G14）：客户端只去掉首尾空白再原样提交；不做 Luhn、不做手机号或邮箱规范化、不判断卡类型（这些由服务端校验，20001
 * 按 data.fields 在对应字段旁提示）。必填为空、超出契约长度（登录号 128、卡号 32、开户行与姓名 64）或卡号含数字、空格、连字符以外的字符时不提交。
 */
object PayoutAccountForm {
    const val PAYOUT_METHOD = "payout_method"
    const val ALIPAY_LOGON_ID = "alipay_logon_id"
    const val CARD_NO = "card_no"
    const val BANK_NAME = "bank_name"
    const val PAYEE_NAME = "payee_name"
    const val LOGON_MAX = 128
    const val CARD_MAX = 32
    const val NAME_MAX = 64
    private val CARD_CHARS = Regex("^[0-9 \\-]*$")

    fun build(form: PayoutForm): PayoutFormResult {
        val errors = mutableListOf<String>()
        val payee = form.payeeName.trim()
        val pairs = linkedMapOf<String, JsonPrimitive>(PAYOUT_METHOD to JsonPrimitive(form.method))
        if (form.method == WalletJson.BANK_CARD) {
            val card = form.cardNo.trim()
            val bank = form.bankName.trim()
            if (card.isEmpty() || card.length > CARD_MAX || !CARD_CHARS.matches(card)) errors += CARD_NO
            if (bank.isEmpty() || bank.codePointCount(0, bank.length) > NAME_MAX) errors += BANK_NAME
            pairs[CARD_NO] = JsonPrimitive(card)
            pairs[BANK_NAME] = JsonPrimitive(bank)
        } else {
            val logon = form.alipayLogonId.trim()
            if (logon.isEmpty() || logon.codePointCount(0, logon.length) > LOGON_MAX) errors += ALIPAY_LOGON_ID
            pairs[ALIPAY_LOGON_ID] = JsonPrimitive(logon)
        }
        if (payee.isEmpty() || payee.codePointCount(0, payee.length) > NAME_MAX) errors += PAYEE_NAME
        pairs[PAYEE_NAME] = JsonPrimitive(payee)
        return PayoutFormResult(if (errors.isEmpty()) JsonObject(pairs) else null, errors)
    }

    /** 卡号输入框的过滤：只允许数字、空格、连字符，最多 32 字；其余字符整段不接受（保持原值）。 */
    fun acceptCardNo(
        current: String,
        next: String,
    ): String = if (CARD_CHARS.matches(next) && next.length <= CARD_MAX) next else current

    fun form(o: JsonObject): PayoutForm =
        PayoutForm(
            method = o.textOrNull(PAYOUT_METHOD) ?: WalletJson.ALIPAY,
            alipayLogonId = o.textOrNull(ALIPAY_LOGON_ID).orEmpty(),
            cardNo = o.textOrNull(CARD_NO).orEmpty(),
            bankName = o.textOrNull(BANK_NAME).orEmpty(),
            payeeName = o.textOrNull(PAYEE_NAME).orEmpty(),
        )

    /** 向量接线。 */
    fun body(form: JsonObject): JsonObject {
        val result = build(form(form))
        return jsonObj("body" to (result.body ?: JsonNull), "fieldErrors" to JsonArray(result.fieldErrors.map(::JsonPrimitive)))
    }
}

/** 待确认摘要（本机按 BR-ID-33 细则的格式生成，只用于待确认展示；G15）。[account] 为 null 时用 fallback 句（不输出完整登录号）。 */
data class PayoutPendingSummaryView(
    val method: String,
    val account: String?,
    val payee: String?,
)

object PayoutPendingSummary {
    private const val PHONE_DIGITS = 11
    private const val PHONE_HEAD = 3
    private const val PHONE_TAIL = 4
    private const val CARD_TAIL = 4
    private const val EMAIL_KEEP_LONG = 2
    private const val STARS = "***"
    private const val PHONE_STARS = "****"
    private const val BANK_TAIL_KEY = "payout.bank_tail"

    fun summarize(form: PayoutForm): PayoutPendingSummaryView {
        val payee =
            form.payeeName
                .trim()
                .takeIf { it.isNotEmpty() }
                ?.let { "**" + it.lastCodePoint() }
        val account =
            if (form.method == WalletJson.BANK_CARD) {
                val digits = form.cardNo.filter(Char::isDigit)
                val bank = form.bankName.trim()
                if (digits.length >= CARD_TAIL && bank.isNotEmpty()) {
                    WalletDraftTexts.format(BANK_TAIL_KEY, mapOf("bank" to bank, "tail" to digits.takeLast(CARD_TAIL)))
                } else {
                    null
                }
            } else {
                alipay(form.alipayLogonId.trim())
            }
        return PayoutPendingSummaryView(form.method, account, payee)
    }

    /** 支付宝登录号：邮箱形式按 @ 前 2 / 1 / 0 位 +「***」+ @ 域名；只保留数字后为 11 位按 3+4；其余（如 +86 前缀）不定义，返回 null。 */
    @Suppress("ReturnCount")
    private fun alipay(logon: String): String? {
        val at = logon.lastIndexOf('@')
        if (at >= 0) {
            val local = logon.substring(0, at)
            if (local.isEmpty()) return null
            val keep =
                when {
                    local.length > EMAIL_KEEP_LONG -> EMAIL_KEEP_LONG
                    local.length == EMAIL_KEEP_LONG -> 1
                    else -> 0
                }
            return local.take(keep) + STARS + logon.substring(at)
        }
        val digits = logon.filter(Char::isDigit)
        if (digits.length != PHONE_DIGITS) return null
        return digits.take(PHONE_HEAD) + PHONE_STARS + digits.takeLast(PHONE_TAIL)
    }

    private fun String.lastCodePoint(): String {
        val index = offsetByCodePoints(length, -1)
        return substring(index)
    }

    /** 向量接线。 */
    fun of(form: JsonObject): JsonObject {
        val view = summarize(PayoutAccountForm.form(form))
        return jsonObj("account" to view.account.json(), "payee" to view.payee.json())
    }
}

/** 实名表单（§2.8、G01）：姓名去首尾空白；身份证号只收数字与 X，18 位，小写 x 转大写；不做校验位计算。 */
data class RealNameInput(
    val name: String,
    val idNo: String,
    val submitEnabled: Boolean,
) {
    override fun toString(): String = "RealNameInput(submitEnabled=$submitEnabled)"
}

object RealNameForm {
    private const val ID_LENGTH = 18
    private val ID_PATTERN = Regex("^[0-9]{17}[0-9X]$")
    private const val NAME_MAX = 64

    fun normalize(
        name: String,
        idNo: String,
    ): RealNameInput {
        val trimmed = name.trim()
        val id = idNo.trim().replace('x', 'X')
        val ok = trimmed.isNotEmpty() && trimmed.codePointCount(0, trimmed.length) <= NAME_MAX && ID_PATTERN.matches(id)
        return RealNameInput(trimmed, id, ok)
    }

    /** 身份证号输入框的过滤：只收数字与 x / X（转大写），最多 18 位；其余字符整段不接受。 */
    fun acceptIdNo(
        current: String,
        next: String,
    ): String {
        val upper = next.replace('x', 'X')
        return if (upper.length <= ID_LENGTH && upper.all { it in '0'..'9' || it == 'X' }) upper else current
    }

    /** 向量接线。 */
    fun normalize(input: JsonObject): JsonObject {
        val view = normalize(input.textOrNull("name").orEmpty(), input.textOrNull("idNo").orEmpty())
        return jsonObj("name" to JsonPrimitive(view.name), "idNo" to JsonPrimitive(view.idNo), "submitEnabled" to view.submitEnabled.json())
    }
}
