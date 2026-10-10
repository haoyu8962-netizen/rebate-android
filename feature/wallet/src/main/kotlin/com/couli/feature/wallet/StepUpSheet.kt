package com.couli.feature.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonSize
import com.couli.core.designsystem.CouliButtonVariant
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliRadius
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTypography
import com.couli.core.wallet.WalletTexts

/**
 * 二次验证面板（WithdrawStepUp、AdaptShortStepUp、A11yFocusStepUp）：「为保障资金安全，请先完成短信验证」+「验证码已发送至你绑定的手机号」（G16，不显示号码）；
 * 一个整串验证码输入框（NumberPassword + 短信验证码自动填充提示，不申请短信读取权限），读屏读「验证码，已输入 n 位，共 6 位」；倒计时后可重新获取；
 * 输满 6 位才能【验证并提交】；错误 assertive 播报。点遮罩、下滑不关闭，关闭后焦点回到【提交】（[restore]）。
 */
@Composable
internal fun StepUpSheet(
    controller: StepUpController,
    state: StepUpUiState,
    texts: WalletTexts,
    smsBlocked: Boolean,
    verifyBlocked: Boolean,
    restore: FocusRequester?,
) {
    val codeFocus = androidx.compose.runtime.remember { FocusRequester() }
    // 打开面板的提交 / 确认身份（共性第 60、63 条）：回调只捕获这个渲染时的值
    val owner = state.owner
    LockedSheet(
        title = texts.text("step_up.title"),
        tag = WalletTags.STEP_UP_SHEET,
        closeLabel = texts.text("step_up.close"),
        onClose = controller::close,
        restore = restore,
        // 中段至少放得下验证码一行；放不下（短窗口 + 200% + 键盘）整块滚动降级（共性第 33 条）
        minBody = CouliSizes.touchTarget + CouliSpacing.space8,
        closeTag = WalletTags.STEP_UP_CLOSE,
        // 倒计时与【验证并提交】在底部操作区，键盘弹起时固定可见，只有中段滚动（APP-09 共性第 11 条）
        actions = {
            Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    val waiting = state.resendLeftSec > 0
                    CouliButton(
                        text =
                            if (waiting) {
                                texts.format("step_up.resend_in", mapOf("sec" to state.resendLeftSec.toString()))
                            } else {
                                texts.text("step_up.resend")
                            },
                        onClick = { controller.resend(owner) },
                        enabled = !waiting && !smsBlocked && state.phase == StepUpUiState.READY,
                        variant = CouliButtonVariant.TEXT,
                        size = CouliButtonSize.COMPACT,
                        modifier = Modifier.testTag(WalletTags.STEP_UP_RESEND),
                    )
                }
                CouliButton(
                    text = texts.text("step_up.submit"),
                    onClick = { controller.verify(owner) },
                    enabled = state.canVerify && !verifyBlocked,
                    loading = state.phase == StepUpUiState.VERIFYING,
                    modifier = Modifier.fillMaxWidth().testTag(WalletTags.STEP_UP_SUBMIT),
                )
            }
        },
    ) {
        when (state.phase) {
            StepUpUiState.NO_PHONE -> {
                Text(
                    text = texts.text("step_up.no_phone"),
                    style = CouliTypography.footnote,
                    color = CouliColors.textPrimary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(WalletTags.STEP_UP_NO_PHONE),
                )
            }

            StepUpUiState.SENDING -> {
                Text(text = texts.text("step_up.sending"), style = CouliTypography.footnote, color = CouliColors.textSecondary)
            }

            else -> {
                Text(text = texts.text("step_up.sent"), style = CouliTypography.footnote, color = CouliColors.textSecondary)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
            Text(text = texts.text("step_up.code_label"), style = CouliTypography.footnote, color = CouliColors.textSecondary)
            val spoken = texts.format("step_up.code_spoken", mapOf("n" to state.code.length.toString()))
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = CouliSizes.touchTarget)
                        .background(CouliColors.backgroundSurface, RoundedCornerShape(CouliRadius.button))
                        .border(
                            CouliSizes.hairline,
                            if (state.error != null) CouliColors.statusErrorText else CouliColors.borderControl,
                            RoundedCornerShape(CouliRadius.button),
                        ).padding(horizontal = CouliSpacing.space3, vertical = CouliSpacing.space2),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = state.code,
                    onValueChange = { controller.onCodeChange(it, owner) },
                    singleLine = true,
                    enabled = state.phase != StepUpUiState.NO_PHONE && state.phase != StepUpUiState.VERIFYING,
                    textStyle = CouliTextStyles.amountTitle.copy(color = CouliColors.textPrimary, letterSpacing = CODE_SPACING.sp),
                    cursorBrush = SolidColor(CouliColors.textPrimary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(codeFocus)
                            .testTag(WalletTags.STEP_UP_CODE)
                            .semantics {
                                contentDescription = spoken
                                contentType = ContentType.SmsOtpCode
                            },
                )
            }
        }
        state.error?.let { notice ->
            Text(
                text = notice.message(texts),
                style = CouliTypography.footnote,
                color = CouliColors.statusErrorText,
                textAlign = TextAlign.Start,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }.testTag(WalletTags.STEP_UP_ERROR),
            )
        }
    }
    LaunchedEffect(state.error?.id) {
        // 验证码错误清空后焦点留在输入框，便于重输
        if (state.error != null) runCatching { codeFocus.requestFocus() }
    }
}

private const val CODE_SPACING = 4
