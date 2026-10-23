package com.couli.core.wallet.stepup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.couli.core.designsystem.CodeInput
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliIconButton
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliRadius
import com.couli.core.designsystem.CouliSheetBody
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.SheetHandle
import com.couli.core.wallet.WalletErrors
import com.couli.core.wallet.WalletTexts

/**
 * 二次验证面板（WithdrawStepUp-v2 / WithdrawStepUpDark、AdaptShortStepUp、A11yFocusStepUp；提现、收款账号、换手机号、注销四页共用，APP-09v-d）：
 * 顶部装饰把手；标题（键随契约 error.10003，G-W19）下一行「验证码已发送至你绑定的手机号」（G16 不显示号码，业务摘要不做）；
 * 6 格验证码（「验证码」只作读屏标签，读「验证码，已输入 n 位，共 6 位」；短信验证码自动填充提示，不申请短信读取权限）；
 * 格下一行左错误（14 红字 + 图标，assertive 播报）、右「{n} 秒后可重新获取」/【重新获取】；底部【验证并提交】。
 * 输满 6 位不自动验证、输入法动作也不验证：验证只由【验证并提交】发起。点遮罩、下滑不关闭，关闭后焦点回到【提交】（[restore]）。
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
    val codeFocus = remember { FocusRequester() }
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
        showHandle = true,
        scrim = CouliColors.overlayScrim,
        // 格下一行【重新获取】的 48 命中区向下扩出的半截要落在中段裁切区内（评审 r1 S2-1）
        bodyBottomInset = CouliSpacing.space4,
        // W65：说明 / 状态句挪到标题区（标题下 8），不随中段滚动
        subtitle = { StepUpNotice(state.phase, texts) },
        // 【验证并提交】在底部操作区，键盘弹起时固定可见，只有中段滚动（APP-09 共性第 11 条）
        actions = {
            CouliButton(
                text = texts.text("step_up.submit"),
                onClick = { controller.verify(owner) },
                enabled = state.canVerify && !verifyBlocked,
                loading = state.phase == StepUpUiState.VERIFYING,
                modifier = Modifier.fillMaxWidth().testTag(StepUpTags.STEP_UP_SUBMIT),
            )
        },
    ) {
        // W66：6 格；焦点请求与测试标签挂在实际输入框上（出错清空后重新聚焦、读屏进度、粘贴 / 自动填充都在这个节点）
        CodeInput(
            value = state.code,
            onValueChange = { controller.onCodeChange(it, owner) },
            accessibilityLabel = texts.format("step_up.code_spoken", mapOf("n" to state.code.length.toString())),
            enabled = state.phase == StepUpUiState.READY || state.phase == StepUpUiState.SENDING,
            submitting = state.phase == StepUpUiState.VERIFYING,
            error = state.error != null,
            inputModifier = Modifier.focusRequester(codeFocus).testTag(StepUpTags.STEP_UP_CODE),
        )
        // W67：格下一行（左错误、右倒计时 / 重新获取）；可用条件与发码回调原样从底部操作区搬来
        val waiting = state.resendLeftSec > 0
        CodeStatusRow(
            error = state.error?.let { WalletErrors.message(texts, it.key, it.traceId, it.showTrace6) },
            countdown = if (waiting) texts.format("login.code.countdown", mapOf("n" to state.resendLeftSec.toString())) else null,
            resendLabel = texts.text("step_up.resend"),
            resendEnabled = !waiting && !smsBlocked && !state.smsLimited && state.phase == StepUpUiState.READY,
            onResend = { controller.resend(owner) },
        )
    }
    LaunchedEffect(state.error?.id) {
        // 验证码错误清空后焦点留在输入框，便于重输
        if (state.error != null) runCatching { codeFocus.requestFocus() }
    }
}

/** 标题下一行（W65）：已发送说明（不含号码，G16）；发送中、无号码、受限会话拒绝时换成对应状态句（文案与播报方式不变）。 */
@Composable
private fun StepUpNotice(
    phase: String,
    texts: WalletTexts,
) {
    when (phase) {
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
}

/**
 * 格下一行（W67，画板 min-height 21）：左错误（16 图标 + 14 红字，assertive 播报），右倒计时（14 次文字）或【重新获取】。
 * 两段放得下时同一行（错误靠左、右侧靠右）；放不下（窄屏、大字号）时错误独占整行、右侧换到下一行靠右，错误不被挤成一字一行（评审 r1 S2-2）。
 * 【重新获取】视觉只占一行文字高，48 命中区靠系统最小触控扩展（不撑高视觉行）；它与格子相隔中段间距 16、下方有中段底部留白，扩展区不压格子也不被裁。
 */
@Composable
private fun CodeStatusRow(
    error: String?,
    countdown: String?,
    resendLabel: String,
    resendEnabled: Boolean,
    onResend: () -> Unit,
) {
    val errorPart: @Composable () -> Unit = { if (error != null) StatusRowError(error) }
    val endPart: @Composable () -> Unit = { StatusRowEnd(countdown, resendLabel, resendEnabled, onResend) }
    Layout(contents = listOf(errorPart, endPart), modifier = Modifier.fillMaxWidth()) { (errorMs, endMs), c ->
        val width = c.maxWidth
        val gap = CouliSpacing.space3.roundToPx()
        val end = endMs.map { it.measure(Constraints(maxWidth = width)) }
        val endWidth = end.maxOfOrNull { it.width } ?: 0
        val errorNeed = errorMs.maxOfOrNull { it.maxIntrinsicWidth(Constraints.Infinity) } ?: 0
        val oneLine = errorMs.isEmpty() || errorNeed + gap + endWidth <= width
        val errorMax = if (oneLine && end.isNotEmpty()) (width - gap - endWidth).coerceAtLeast(0) else width
        val errors = errorMs.map { it.measure(Constraints(maxWidth = errorMax)) }
        val errorHeight = errors.maxOfOrNull { it.height } ?: 0
        val endHeight = end.maxOfOrNull { it.height } ?: 0
        val endTop = if (oneLine) 0 else errorHeight + CouliSpacing.space1.roundToPx()
        layout(width, maxOf(errorHeight, endTop + endHeight)) {
            errors.forEach { it.place(0, 0) }
            end.forEach { it.place(width - it.width, endTop) }
        }
    }
}

/** 格下一行左段：16 错误图标（与首行文字垂直居中，随字号缩放）+ 14 红字，assertive 播报。 */
@Composable
private fun StatusRowError(error: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
        val line = with(LocalDensity.current) { CouliTypography.footnote.lineHeight.toDp() }
        Box(modifier = Modifier.height(line), contentAlignment = Alignment.Center) {
            Icon(
                CouliIcons.alert,
                contentDescription = null,
                tint = CouliColors.statusErrorText,
                modifier = Modifier.size(CouliSizes.iconSmall),
            )
        }
        Text(
            text = error,
            style = CouliTypography.footnote,
            color = CouliColors.statusErrorText,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }.testTag(StepUpTags.STEP_UP_ERROR),
        )
    }
}

/** 格下一行右段：倒计时或【重新获取】，只占自身文字宽（排版宽 = 内容宽；放不下时按可用宽折行）。 */
@Composable
private fun StatusRowEnd(
    countdown: String?,
    resendLabel: String,
    resendEnabled: Boolean,
    onResend: () -> Unit,
) {
    if (countdown != null) {
        Text(
            text = countdown,
            style = CouliTypography.footnote,
            color = CouliColors.textSecondary,
            modifier = Modifier.width(IntrinsicSize.Max).testTag(StepUpTags.STEP_UP_RESEND),
        )
    } else {
        Text(
            text = resendLabel,
            style = CouliTypography.footnote,
            color = if (resendEnabled) CouliColors.textLink else CouliColors.textSecondary,
            modifier =
                Modifier
                    .width(IntrinsicSize.Max)
                    .clickable(enabled = resendEnabled, role = Role.Button, onClick = onResend)
                    .testTag(StepUpTags.STEP_UP_RESEND),
        )
    }
}

/** 受限会话拒绝提示的文案键（草稿键缺失时回落 step_up.no_phone）。 */
private fun scopeDeniedKey(texts: WalletTexts): String = StepUpController.SCOPE_DENIED_KEY.takeIf(texts::has) ?: "step_up.no_phone"

/**
 * 资金与验证类半屏（二次验证、实名同意；AdaptSheetRules、AdaptShortStepUp、A11yFocusStepUp，APP-09 共性第 48 条）：点遮罩（不响应点外部）、下滑
 * （关掉半屏手势）都不关闭；系统返回关闭最上层半屏，走与面板里关闭按钮相同的出口 [onClose]（控制器结算验证码请求归属、恢复发起页状态）。
 * 打开时焦点进标题，关闭后焦点回到触发控件（[restore]）。
 * 可选（APP-09v-d，默认保持旧样子）：[subtitle] 标题下一行说明（放标题区、不随中段滚动）；[showHandle] 顶部 36×4 装饰把手（无读屏、无动作，
 * 不用 M3 的把手槽——那个槽带展开 / 收起 / 关闭动作）；[scrim] 遮罩色（如 [CouliColors.overlayScrim]，不给用 M3 默认）；
 * [bodyBottomInset] 三段布局时中段底部留白（算进中段的滚动裁切区、从中段与操作区间距里扣，视觉不变），给中段末行靠最小触控扩展的 48 命中区留出下半截。
 */
@Suppress("LongParameterList") // 半屏外观的可选项逐个列出，默认值即旧行为
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
    subtitle: (@Composable () -> Unit)? = null,
    showHandle: Boolean = false,
    scrim: Color? = null,
    bodyBottomInset: Dp = 0.dp,
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
        scrimColor = scrim ?: BottomSheetDefaults.ScrimColor,
        dragHandle = null,
    ) {
        val titleFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { titleFocus.requestFocus() } }
        // 画板：半屏上内边距 20、把手 4、把手到标题行 16
        if (showHandle) SheetHandle(Modifier.align(Alignment.CenterHorizontally).padding(top = CouliSpacing.space2).clearAndSetSemantics {})
        val top = if (showHandle) CouliSpacing.space1 else CouliSpacing.space4
        if (minBody != null && actions != null) {
            FitSheetBody(
                title = title,
                modifier = Modifier.padding(top = top).semantics { paneTitle = title }.testTag(tag),
                titleFocus = titleFocus,
                onClose = onClose,
                closeLabel = closeLabel,
                minBody = minBody,
                closeTag = closeTag,
                actions = actions,
                subtitle = subtitle,
                bodyBottomInset = bodyBottomInset,
                content = content,
            )
        } else {
            CouliSheetBody(
                title = title,
                modifier = Modifier.padding(top = top).semantics { paneTitle = title }.testTag(tag),
                actions = actions,
                titleFocus = titleFocus,
                onClose = closeLabel?.let { { onClose() } },
                closeLabel = closeLabel,
                content = {
                    subtitle?.invoke()
                    content()
                },
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
    subtitle: (@Composable () -> Unit)? = null,
    bodyBottomInset: Dp = 0.dp,
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
                        // 标题区：标题 + 可选说明（标题下 8，与标题左对齐）
                        Column(
                            modifier = Modifier.weight(1f).padding(top = CouliSpacing.space3),
                            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
                        ) {
                            Text(
                                text = title,
                                style = CouliTextStyles.bodySemibold,
                                color = CouliColors.textPrimary,
                                modifier =
                                    Modifier
                                        .semantics { heading() }
                                        .focusRequester(titleFocus)
                                        .focusable(),
                            )
                            subtitle?.invoke()
                        }
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
                                .padding(start = CouliSpacing.space4, end = CouliSpacing.space4, bottom = bodyBottomInset)
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
                // 中段底部留白算进中段（滚动裁切区内），中段与操作区的间距相应减掉：视觉不变
                val inset = bodyBottomInset.roundToPx().coerceAtMost(gap)
                val loose = Constraints(maxWidth = c.maxWidth)
                val header = headerMs.map { it.measure(loose) }
                val footer = footerMs.map { it.measure(loose) }
                val fixed = header.sumOf { it.height } + footer.sumOf { it.height } + 2 * gap - inset
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
                    listOf(header, body, footer).forEachIndexed { index, group ->
                        group.forEach {
                            it.place(0, y)
                            y += it.height
                        }
                        y += if (index == 1) gap - inset else gap
                    }
                }
            }
        }
    }
}
