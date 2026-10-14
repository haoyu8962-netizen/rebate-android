package com.couli.feature.wallet

import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.FocusRequester
import com.couli.core.wallet.WalletTexts

/** 二次验证面板（挪到 core:wallet，APP-13a brief §3 Android）：钱包页沿用 APP-09 的调用方式。 */
@Composable
internal fun StepUpSheet(
    controller: StepUpController,
    state: StepUpUiState,
    texts: WalletTexts,
    smsBlocked: Boolean,
    verifyBlocked: Boolean,
    restore: FocusRequester?,
) = com.couli.core.wallet.stepup
    .StepUpSheet(controller, state, texts, smsBlocked, verifyBlocked, restore)
