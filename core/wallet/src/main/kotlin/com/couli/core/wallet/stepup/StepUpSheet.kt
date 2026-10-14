package com.couli.core.wallet.stepup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonSize
import com.couli.core.designsystem.CouliButtonVariant
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliIconButton
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliRadius
import com.couli.core.designsystem.CouliSheetBody
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTypography
import com.couli.core.wallet.WalletErrors
import com.couli.core.wallet.WalletTexts

/**
 * 二次验证面板（WithdrawStepUp、AdaptShortStepUp、A11yFocusStepUp）：「为保障资金安全，请先完成短信验证」+「验证码已发送至你绑定的手机号」（G16，不显示号码）；
 * 一个整串验证码输入框（NumberPassword + 短信验证码自动填充提示，不申请短信读取权限），读屏读「验证码，已输入 n 位，共 6 位」；倒计时后可重新获取；
 * 输满 6 位才能【验证并提交】；错误 assertive 播报。点遮罩、下滑不关闭，关闭后焦点回到【提交】（[restore]）。
 */
@Composable
fun StepUpSheet(
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
        title = texts.text(StepUpController.titleKey(state.action, texts::has)),
        tag = StepUpTags.STEP_UP_SHEET,
        closeLabel = texts.text("step_up.close"),
        onClose = controller::close,
        restore = restore,
        // 中段至少放得下验证码一行；放不下（短窗口 + 200% + 键盘）整块滚动降级（共性第 33 条）
        minBody = CouliSizes.touchTarget + CouliSpacing.space8,
        closeTag = StepUpTags.STEP_UP_CLOSE,
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
                        enabled = !waiting && !smsBlocked && !state.smsLimited && state.phase == StepUpUiState.READY,
                        variant = CouliButtonVariant.TEXT,
                        size = CouliButtonSize.COMPACT,
                        modifier = Modifier.testTag(StepUpTags.STEP_UP_RESEND),
                    )
                }
                CouliButton(
                    text = texts.text("step_up.submit"),
                    onClick = { controller.verify(owner) },
                    enabled = state.canVerify && !verifyBlocked,
                    loading = state.phase == StepUpUiState.VERIFYING,
                    modifier = Modifier.fillMaxWidth().testTag(StepUpTags.STEP_UP_SUBMIT),
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
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(StepUpTags.STEP_UP_NO_PHONE),
                )
            }

            StepUpUiState.SCOPE_DENIED -> {
                // 受限会话下非 account_deletion：本地拒绝，不发码不验证（BR-ID-01 细则「受限会话」）
                Text(
                    text = texts.text(scopeDeniedKey(texts)),
                    style = CouliTypography.footnote,
                    color = CouliColors.textPrimary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(StepUpTags.STEP_UP_SCOPE_DENIED),
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
                    enabled = state.phase == StepUpUiState.READY || state.phase == StepUpUiState.SENDING,
                    textStyle = CouliTextStyles.amountTitle.copy(color = CouliColors.textPrimary, letterSpacing = CODE_SPACING.sp),
                    cursorBrush = SolidColor(CouliColors.textPrimary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(codeFocus)
                            .testTag(StepUpTags.STEP_UP_CODE)
                            .semantics {
                                contentDescription = spoken
                                contentType = ContentType.SmsOtpCode
                            },
                )
            }
        }
        state.error?.let { notice ->
            Text(
                text = WalletErrors.message(texts, notice.key, notice.traceId, notice.showTrace6),
                style = CouliTypography.footnote,
                color = CouliColors.statusErrorText,
                textAlign = TextAlign.Start,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }.testTag(StepUpTags.STEP_UP_ERROR),
            )
        }
    }
    LaunchedEffect(state.error?.id) {
        // 验证码错误清空后焦点留在输入框，便于重输
        if (state.error != null) runCatching { codeFocus.requestFocus() }
    }
}

private const val CODE_SPACING = 4

/** 受限会话拒绝提示的文案键（草稿键缺失时回落 step_up.no_phone）。 */
private fun scopeDeniedKey(texts: WalletTexts): String = StepUpController.SCOPE_DENIED_KEY.takeIf(texts::has) ?: "step_up.no_phone"

/**
 * 资金与验证类半屏（二次验证、实名同意；AdaptSheetRules、AdaptShortStepUp、A11yFocusStepUp，APP-09 共性第 48 条）：点遮罩（不响应点外部）、下滑
 * （关掉半屏手势）都不关闭；系统返回关闭最上层半屏，走与面板里关闭按钮相同的出口 [onClose]（控制器结算验证码请求归属、恢复发起页状态）。
 * 打开时焦点进标题，关闭后焦点回到触发控件（[restore]）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LockedSheet(
    title: String,
    tag: String,
    closeLabel: String?,
    onClose: () -> Unit,
    restore: FocusRequester?,
    actions: (@Composable () -> Unit)? = null,
    /** 中段至少要放得下的高度（二次验证：一行验证码格）；给了就用 [FitSheetBody]（APP-09 共性第 11、33 条）。 */
    minBody: Dp? = null,
    closeTag: String? = null,
    content: @Composable () -> Unit,
) {
    // 收起只可能来自系统返回（手势与点外部都已关掉）：返回键收起后由 onDismissRequest 走关闭按钮的同一出口
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    DisposableEffect(Unit) { onDispose { restore?.let { runCatching { it.requestFocus() } } } }
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = true, shouldDismissOnClickOutside = false),
        shape = RoundedCornerShape(topStart = CouliRadius.panel, topEnd = CouliRadius.panel),
        containerColor = CouliColors.backgroundSurface,
        dragHandle = null,
    ) {
        val titleFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { titleFocus.requestFocus() } }
        if (minBody != null && actions != null) {
            FitSheetBody(
                title = title,
                modifier = Modifier.padding(top = CouliSpacing.space4).semantics { paneTitle = title }.testTag(tag),
                titleFocus = titleFocus,
                onClose = onClose,
                closeLabel = closeLabel,
                minBody = minBody,
                closeTag = closeTag,
                actions = actions,
                content = content,
            )
        } else {
            CouliSheetBody(
                title = title,
                modifier = Modifier.padding(top = CouliSpacing.space4).semantics { paneTitle = title }.testTag(tag),
                actions = actions,
                titleFocus = titleFocus,
                onClose = closeLabel?.let { { onClose() } },
                closeLabel = closeLabel,
                content = content,
            )
        }
    }
}

/**
 * 三段半屏（APP-09 共性第 11、33 条）：放得下时标题区与底部操作区固定、只有中段滚动；标题 + 操作区 + 中段最小高 [minBody] 超过可用高度（短窗口、200% 字号、
 * 键盘弹起）时，整块切到可滚动的降级布局，完整标题、关闭与【验证并提交】都可滚到。可用高度取半屏得到的约束（键盘上方）。
 */
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod") // 三段测量与降级判定在同一个 Layout 里
@Composable
fun FitSheetBody(
    title: String,
    modifier: Modifier,
    titleFocus: FocusRequester,
    onClose: () -> Unit,
    closeLabel: String?,
    minBody: Dp,
    closeTag: String?,
    actions: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // 半屏窗口随键盘缩小（约束已是键盘上方的高度）
        val budget = if (constraints.hasBoundedHeight) constraints.maxHeight else Constraints.Infinity
        val outer = rememberScrollState()
        val middle = rememberScrollState()
        var fallback by remember { mutableStateOf(false) }
        val parts: List<@Composable () -> Unit> =
            listOf(
                {
                    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(horizontal = CouliSpacing.space4)) {
                        Text(
                            text = title,
                            style = CouliTextStyles.bodySemibold,
                            color = CouliColors.textPrimary,
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .padding(top = CouliSpacing.space3)
                                    .semantics { heading() }
                                    .focusRequester(titleFocus)
                                    .focusable(),
                        )
                        if (closeLabel != null) {
                            CouliIconButton(
                                icon = CouliIcons.close,
                                label = closeLabel,
                                onClick = onClose,
                                tint = CouliColors.textSecondary,
                                modifier = if (closeTag != null) Modifier.testTag(closeTag) else Modifier,
                            )
                        }
                    }
                },
                {
                    Column(
                        modifier =
                            Modifier
                                .then(if (fallback) Modifier else Modifier.verticalScroll(middle))
                                .padding(horizontal = CouliSpacing.space4)
                                .testTag(StepUpTags.SHEET_MIDDLE),
                        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space4),
                    ) { content() }
                },
                {
                    Box(modifier = Modifier.padding(start = CouliSpacing.space4, end = CouliSpacing.space4, bottom = CouliSpacing.space6)) {
                        actions()
                    }
                },
            )
        val density = LocalDensity.current
        val visible = if (budget == Constraints.Infinity) Modifier else Modifier.heightIn(max = with(density) { budget.toDp() })
        // 降级：整块在「键盘上方的可见高度」里滚动，最后一段（【验证并提交】）也能滚到键盘之上
        Box(modifier = if (fallback) visible.verticalScroll(outer).testTag(StepUpTags.SHEET_FALLBACK) else Modifier) {
            Layout(contents = parts) { (headerMs, bodyMs, footerMs), c ->
                val gap = CouliSpacing.space4.roundToPx()
                val loose = Constraints(maxWidth = c.maxWidth)
                val header = headerMs.map { it.measure(loose) }
                val footer = footerMs.map { it.measure(loose) }
                val fixed = header.sumOf { it.height } + footer.sumOf { it.height } + 2 * gap
                val fits = budget == Constraints.Infinity || fixed + minBody.roundToPx() <= budget
                if (fits == fallback) {
                    // 放得下 ↔ 放不下切换：下一帧按新布局重排（不在本次测量里改滚动容器）
                    fallback = !fits
                }
                val bodyMax =
                    when {
                        fallback -> bodyMs.maxOfOrNull { it.maxIntrinsicHeight(c.maxWidth) } ?: 0
                        budget == Constraints.Infinity -> bodyMs.maxOfOrNull { it.maxIntrinsicHeight(c.maxWidth) } ?: 0
                        else -> (budget - fixed).coerceAtLeast(0)
                    }
                val body = bodyMs.map { it.measure(Constraints(maxWidth = c.maxWidth, maxHeight = bodyMax)) }
                val height = fixed + body.sumOf { it.height }
                val shown = if (fallback || budget == Constraints.Infinity) height else height.coerceAtMost(budget)
                layout(c.maxWidth, shown) {
                    var y = 0
                    listOf(header, body, footer).forEach { group ->
                        group.forEach {
                            it.place(0, y)
                            y += it.height
                        }
                        y += gap
                    }
                }
            }
        }
    }
}
