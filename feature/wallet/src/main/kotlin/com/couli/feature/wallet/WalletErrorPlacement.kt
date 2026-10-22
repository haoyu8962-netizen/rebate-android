package com.couli.feature.wallet

import com.couli.core.wallet.PayoutAccountForm
import com.couli.core.wallet.WalletJson

// 提交被拒的业务错误显示在哪里（APP-09v-c G-W13，纯函数、只读 [FlowState.error] 的键）：只换显示位置，不改 SensitiveOutcome 的分类、结局与键的去留；
// 字段下的提示沿用既有字段错误路径（滚到可见、移焦点、播报一次），页顶错误条同时不再出现（不重复报错）。

/** 提现：30301 与 30303 的金额类子键显示在金额框下；白名单外（冻结、次数、自购、收款人日限等）仍在页顶错误条。 */
internal fun isWithdrawAmountError(key: String?): Boolean = key in WITHDRAW_AMOUNT_ERROR_KEYS

/** 收款账号：30307 → 姓名字段；30308 → 账号字段（支付宝账号 / 卡号）。null = 仍在页顶错误条。 */
internal fun payoutErrorField(
    key: String?,
    method: String?,
): String? =
    when (key) {
        PAYOUT_PAYEE_ERROR_KEY -> PayoutAccountForm.PAYEE_NAME
        PAYOUT_ACCOUNT_ERROR_KEY -> if (method == WalletJson.BANK_CARD) PayoutAccountForm.CARD_NO else PayoutAccountForm.ALIPAY_LOGON_ID
        else -> null
    }

private const val PAYOUT_PAYEE_ERROR_KEY = "error.30307"
private const val PAYOUT_ACCOUNT_ERROR_KEY = "error.30308"

private val WITHDRAW_AMOUNT_ERROR_KEYS =
    setOf(
        "error.30301",
        "error.30303.below_min",
        "error.30303.not_multiple",
        "error.30303.above_max",
        "error.30303.above_method_max",
        "error.30303.net_too_small",
    )
