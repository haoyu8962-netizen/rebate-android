package com.couli.core.wallet

import com.couli.generated.DefaultTexts
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 提现金额输入（G18）：只接受半角数字与一个小数点，小数最多 2 位，整数部分最多 9 位；用字符串拆整数与小数部分做整数运算换算成分，不用浮点。
 * 空串、只有小数点、缺整数或小数部分（「.5」「5.」）、换算后为 0 → 不是合法金额（不发估算请求、【提交】置灰）。
 */
object WithdrawAmountInput {
    private const val MAX_INTEGER_DIGITS = 9
    private const val FEN_PER_YUAN = 100L
    private const val TEN = 10L
    private val VALID = Regex("^([0-9]{1,$MAX_INTEGER_DIGITS})(?:\\.([0-9]{1,2}))?$")

    /** 正在编辑的文字（允许「12.」这类半截输入）。 */
    private val EDITING = Regex("^[0-9]{0,$MAX_INTEGER_DIGITS}(?:\\.[0-9]{0,2})?$")

    fun parse(text: String): Long? {
        val match = VALID.matchEntire(text.trim()) ?: return null
        val yuan = match.groupValues[1].toLong()
        val decimals = match.groupValues[2].padEnd(2, '0')
        val fen = yuan * FEN_PER_YUAN + decimals.toLong()
        return fen.takeIf { it > 0 }
    }

    /** 分 → 元的输入文字（【全部提现】与快捷档位）：去掉末尾的 0 和多余的小数点，不带「¥」（BR-TEXT-10 的写法）。 */
    fun display(fen: Long): String {
        val yuan = fen / FEN_PER_YUAN
        val cent = fen % FEN_PER_YUAN
        return when {
            cent == 0L -> yuan.toString()
            cent % TEN == 0L -> "$yuan.${cent / TEN}"
            else -> "$yuan." + cent.toString().padStart(2, '0')
        }
    }

    /**
     * 输入框的过滤：新文字（粘贴先去首尾空白）只含半角数字与至多一个小数点、位数不超限时接受，否则整段不接受、保持原值（G18）。
     */
    fun accept(
        current: String,
        next: String,
    ): String {
        val trimmed = next.trim()
        return if (EDITING.matches(trimmed)) trimmed else current
    }
}

/** 估算行（BR-WDR-04 细则）：隐藏，或 net_only / 带手续费与代扣个税的写法。金额都是服务端 est_*。 */
sealed interface EstimateLine {
    data object Hidden : EstimateLine

    data class NetOnly(
        val netFen: Long,
    ) : EstimateLine {
        val key: String get() = WithdrawRulesView.ESTIMATE_NET_ONLY_KEY
    }

    data class Full(
        val netFen: Long,
        val feeFen: Long,
        val taxFen: Long,
    ) : EstimateLine {
        val key: String get() = WithdrawRulesView.ESTIMATE_KEY
    }
}

/**
 * 提现页的显示结论（BR-WDR-04 细则「提现页的金额输入与提交后去向」）。客户端不判断余额够不够、最低额、步长、次数、费税、到账时间：
 * 能不能提只看 can_withdraw / block_code，【全部提现】只填 max_withdrawable_fen，估算只显示服务端 est_*，时效只按 Config 的 withdraw.sla_text
 * 或 BR-TEXT-07 原文（提交前不预判单据走向）。
 */
data class WithdrawRulesDisplay(
    val balanceFen: Long,
    val dailyRemaining: Long,
    val monthlyRemaining: Long,
    val maxLineFen: Long?,
    val allEnabled: Boolean,
    val allFen: Long?,
    val quick: List<Long>,
    val estimate: EstimateLine,
    val slaText: String,
    val submitEnabled: Boolean,
    val amountEditable: Boolean,
)

object WithdrawRulesView {
    const val ESTIMATE_KEY = "withdraw.estimate"
    const val ESTIMATE_NET_ONLY_KEY = "withdraw.estimate.net_only"
    const val SLA_DEFAULT_KEY = "withdraw.sla_default"
    private const val REALNAME_BLOCK = 30304

    fun of(
        rules: WithdrawRules,
        inputFen: Long?,
        slaText: String?,
        texts: WalletTexts = WalletDraftTexts,
    ): WithdrawRulesDisplay {
        val can = rules.canWithdraw
        val allEnabled = can && rules.maxWithdrawableFen > 0
        return WithdrawRulesDisplay(
            balanceFen = rules.withdrawableFen,
            dailyRemaining = rules.dailyRemaining,
            monthlyRemaining = rules.monthlyRemaining,
            maxLineFen = rules.maxWithdrawableFen.takeIf { can && it < rules.withdrawableFen },
            allEnabled = allEnabled,
            allFen = rules.maxWithdrawableFen.takeIf { allEnabled },
            quick = if (can) rules.quickAmountsFen else emptyList(),
            estimate = estimate(rules, inputFen),
            slaText = slaText?.takeIf { it.isNotBlank() } ?: texts.text(SLA_DEFAULT_KEY),
            submitEnabled = can && inputFen != null,
            // 只有未实名（30304）时金额框不可输入（照 WithdrawRealName 稿，G29）
            amountEditable = rules.blockCode != REALNAME_BLOCK || can,
        )
    }

    /** 估算：输入不合法、不能提、est_net_fen 为 null、费税有缺 → 隐藏；费税都为 0 → net_only。 */
    @Suppress("ReturnCount") // 逐项隐藏条件
    fun estimate(
        rules: WithdrawRules,
        inputFen: Long?,
    ): EstimateLine {
        if (inputFen == null || !rules.canWithdraw) return EstimateLine.Hidden
        val net = rules.estNetFen ?: return EstimateLine.Hidden
        val fee = rules.estFeeFen ?: return EstimateLine.Hidden
        val tax = rules.estTaxFen ?: return EstimateLine.Hidden
        return if (fee == 0L && tax == 0L) EstimateLine.NetOnly(net) else EstimateLine.Full(net, fee, tax)
    }

    fun of(
        rules: JsonObject,
        inputFen: Long?,
        slaText: String?,
    ): JsonObject {
        val parsed = WalletJson.rules(rules) ?: throw IllegalArgumentException("bad rules")
        val view = of(parsed, inputFen, slaText)
        val estimate =
            when (val line = view.estimate) {
                EstimateLine.Hidden -> {
                    JsonPrimitive("hidden")
                }

                is EstimateLine.NetOnly -> {
                    jsonObj("key" to JsonPrimitive(line.key), "net" to JsonPrimitive(line.netFen))
                }

                is EstimateLine.Full -> {
                    jsonObj(
                        "key" to JsonPrimitive(line.key),
                        "net" to JsonPrimitive(line.netFen),
                        "fee" to JsonPrimitive(line.feeFen),
                        "tax" to JsonPrimitive(line.taxFen),
                    )
                }
            }
        return jsonObj(
            "balanceLine" to jsonObj("fen" to JsonPrimitive(view.balanceFen)),
            "remaining" to jsonObj("daily" to JsonPrimitive(view.dailyRemaining), "monthly" to JsonPrimitive(view.monthlyRemaining)),
            "maxLine" to (view.maxLineFen?.let { jsonObj("fen" to JsonPrimitive(it)) } ?: JsonNull),
            "allButton" to
                if (view.allFen != null) {
                    jsonObj("enabled" to view.allEnabled.json(), "fen" to JsonPrimitive(view.allFen))
                } else {
                    jsonObj("enabled" to view.allEnabled.json())
                },
            "quick" to view.quick.json(),
            "estimate" to estimate,
            "slaKeyOrText" to JsonPrimitive(view.slaText),
            "submitEnabled" to view.submitEnabled.json(),
            "amountEditable" to view.amountEditable.json(),
        )
    }
}

/**
 * 估算响应的归属（BR-WDR-04 细则「估算响应的归属」）：四项都对得上才应用——① 账号与发请求时相同；② 仍是同一个 Withdraw 页面实例；③ 是这个实例
 * 最新的请求序号；④ 发请求时的金额等于当前输入换算出的金额（不带金额的请求不是估算）。
 */
object WithdrawEstimateGate {
    data class Request(
        val account: String?,
        val pageInstance: String,
        val seq: Long,
        val amountFen: Long?,
    )

    data class Current(
        val account: String?,
        val pageInstance: String,
        val latestSeq: Long,
        val inputFen: Long?,
    )

    fun shouldApply(
        request: Request,
        current: Current,
    ): Boolean =
        request.amountFen != null &&
            request.account != null &&
            request.account == current.account &&
            request.pageInstance == current.pageInstance &&
            request.seq == current.latestSeq &&
            request.amountFen == current.inputFen

    fun shouldApply(
        request: JsonObject,
        current: JsonObject,
    ): Boolean =
        shouldApply(
            Request(
                request.textOrNull("account"),
                request.textOrNull("pageInstance").orEmpty(),
                request.longOrNullValue("seq") ?: -1,
                request.longOrNullValue("amountFen"),
            ),
            Current(
                current.textOrNull("account"),
                current.textOrNull("pageInstance").orEmpty(),
                current.longOrNullValue("latestSeq") ?: -1,
                current.longOrNullValue("inputFen"),
            ),
        )
}

/** 30303 的原因文案（G06、G07）：带占位符的子键取不到限制值，一律用 fallbacks；没有子键（payee_daily_count 等）回落 error.30303。 */
object WithdrawConditionText {
    const val BASE_KEY = "error.30303"
    private val PLACEHOLDER = Regex("\\{[a-z0-9_]+\\}")

    data class View(
        val textKey: String,
        val useFallback: Boolean,
    )

    fun view(reason: String?): View {
        val key = reason?.let { "$BASE_KEY.$it" }
        val raw = key?.let(DefaultTexts::text) ?: return View(BASE_KEY, true)
        return View(key, PLACEHOLDER.containsMatchIn(raw))
    }

    /** 向量接线。 */
    fun of(reason: String?): JsonObject =
        view(reason).let { jsonObj("textKey" to JsonPrimitive(it.textKey), "useFallback" to it.useFallback.json()) }
}

/**
 * rules 的 block_code 引导表（G03、G04、G28、G29）：30304 → RealName；30305 / 30307 / 30308 → PayoutAccount；10004 → LaborAgreement（rules 不给
 * consent_type，一律按劳务协议）；30306 → 提现按钮置灰；30301 / 30302 / 30309 / 10006 / 44001 → 只提示；30303 按 [WithdrawConditionText]。
 * 表外的码只显示 error.<code>，键缺失时回落 error.30303，不引导、不显示码值。按钮由用户点了才打开前置页（G05，不自动跳）。
 */
object WithdrawBlockGuide {
    const val NONE = "none"
    const val ROUTE = "route"
    const val DISABLE = "disable"
    const val NOTICE = "notice"

    data class View(
        val actionRaw: String,
        val route: String? = null,
        val textKey: String = "",
        val fallbackUsed: Boolean? = null,
        /** 引导按钮的文案键（只有 [ROUTE]）。 */
        val buttonKey: String? = null,
    )

    @Suppress("MagicNumber", "CyclomaticComplexMethod") // 错误码是契约取值，逐码一个分支
    fun view(
        blockCode: Int?,
        blockReason: String?,
    ): View =
        when (blockCode) {
            null -> View(NONE)
            30304 -> View(ROUTE, WalletRoutes.REAL_NAME, "error.30304", buttonKey = "withdraw.guide.realname")
            30305, 30307, 30308 -> View(ROUTE, WalletRoutes.PAYOUT_ACCOUNT, "error.$blockCode", buttonKey = "withdraw.guide.payout")
            10004 -> View(ROUTE, WalletRoutes.LABOR_AGREEMENT, "error.10004", buttonKey = "withdraw.guide.labor")
            30306 -> View(DISABLE, textKey = "error.30306")
            30301, 30302, 30309, 10006, 44001 -> View(NOTICE, textKey = "error.$blockCode")
            30303 -> WithdrawConditionText.view(blockReason).let { View(NOTICE, textKey = it.textKey, fallbackUsed = it.useFallback) }
            else -> fallback(blockCode)
        }

    private fun fallback(code: Int): View {
        val key = "error.$code"
        return if (DefaultTexts.text(key) != null) {
            View(NOTICE, textKey = key, fallbackUsed = false)
        } else {
            View(NOTICE, textKey = WithdrawConditionText.BASE_KEY, fallbackUsed = true)
        }
    }

    /** 向量接线。 */
    fun of(
        blockCode: Int?,
        blockReason: String?,
    ): JsonObject {
        val view = view(blockCode, blockReason)
        val pairs = mutableListOf("actionRaw" to JsonPrimitive(view.actionRaw), "textKey" to JsonPrimitive(view.textKey))
        view.route?.let { pairs += "route" to JsonPrimitive(it) }
        view.fallbackUsed?.let { pairs += "fallbackUsed" to JsonPrimitive(it) }
        return JsonObject(pairs.toMap())
    }
}

/** 钱包类页面用到的路由名（routes.json；跨功能跳转只经 Router）。 */
object WalletRoutes {
    const val WALLET = "Wallet"
    const val WITHDRAW = "Withdraw"
    const val WITHDRAW_RECORDS = "WithdrawRecords"
    const val LEDGER = "Ledger"
    const val EARNINGS = "Earnings"
    const val REAL_NAME = "RealName"
    const val PAYOUT_ACCOUNT = "PayoutAccount"
    const val LABOR_AGREEMENT = "LaborAgreement"
    const val HELP = "Help"
    const val RISK_NOTICE = "RiskNotice"
    const val BIND_PHONE = "BindPhone"
    const val ORDER_LIST = "OrderList"
    const val LOGIN = "Login"
    const val WITHDRAWAL_ID = "withdrawal_id"
}
