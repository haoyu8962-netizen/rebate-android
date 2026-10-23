package com.couli.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 输入框外框的状态色（组件清单 §3.2）：默认 1 border.control；输入中 2 border.active（T2，取代绿色）；错误 1.5 status.error.text；
 * 禁用 / 只读 background.muted 底。
 */
@Composable
private fun fieldBorder(
    focused: Boolean,
    error: Boolean,
    enabled: Boolean,
    readOnly: Boolean,
): BorderStroke =
    when {
        error -> BorderStroke(ERROR_BORDER, CouliColors.statusErrorText)
        !enabled || readOnly -> BorderStroke(CouliSizes.hairline, CouliColors.borderSubtle)
        focused -> BorderStroke(ACTIVE_BORDER, CouliColors.borderActive)
        else -> BorderStroke(CouliSizes.hairline, CouliColors.borderControl)
    }

/**
 * 文本输入框（组件清单 §3.2 `CouliTextField` / `CouliTextArea`）：`BasicTextField` + 组件库外框（最小高 52 = T6，圆角 12）。
 * [placeholder] 是占位；[error] 不为空时红框 + 图标 + 文字（读屏报错误）；[clearLabel] 给了且有内容时右侧带清除（命中区 48）；
 * [maxLength] 给了时下方右侧显示字数。多行用 [singleLine] = false（申诉说明等）。[interactionSource] 供目录页展示输入中态。
 */
@Composable
fun CouliTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    error: String? = null,
    clearLabel: String? = null,
    maxLength: Int? = null,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    accessibilityLabel: String? = null,
    leading: (@Composable () -> Unit)? = null,
    fieldContentType: ContentType? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val border = fieldBorder(focused, error != null, enabled, readOnly)
    val editable = enabled && !readOnly
    val textColor = if (enabled) CouliColors.textPrimary else CouliColors.textSecondary
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
        BasicTextField(
            value = value,
            onValueChange = { next -> onValueChange(if (maxLength != null) next.take(maxLength) else next) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics {
                        if (accessibilityLabel != null) contentDescription = accessibilityLabel
                        if (error != null) error(error)
                        if (fieldContentType != null) contentType = fieldContentType
                    },
            enabled = enabled,
            readOnly = readOnly,
            textStyle = CouliTypography.body.copy(color = textColor),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            singleLine = singleLine,
            minLines = if (singleLine) 1 else TEXT_AREA_MIN_LINES,
            interactionSource = interaction,
            cursorBrush = SolidColor(CouliColors.brandPrimary),
            decorationBox = { inner ->
                FieldFrame(
                    inner = inner,
                    showPlaceholder = value.isEmpty() && placeholder != null,
                    placeholder = placeholder.orEmpty(),
                    muted = !editable,
                    border = border,
                    singleLine = singleLine,
                    leading = leading,
                    clearLabel = clearLabel?.takeIf { value.isNotEmpty() && editable },
                    onClear = { onValueChange("") },
                )
            },
        )
        FieldFooter(error = error, counter = maxLength?.let { "${value.length}/$it" })
    }
}

/** 输入框外框：最小高 52、圆角 12、状态描边；禁用 / 只读铺 muted 底；可带前缀与清除。 */
@Composable
private fun FieldFrame(
    inner: @Composable () -> Unit,
    showPlaceholder: Boolean,
    placeholder: String,
    muted: Boolean,
    border: BorderStroke,
    singleLine: Boolean,
    leading: (@Composable () -> Unit)?,
    clearLabel: String?,
    onClear: () -> Unit,
) {
    val shape = RoundedCornerShape(CouliRadius.button)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = CouliComponentTokens.fieldMinHeight)
                .clip(shape)
                .background(if (muted) CouliColors.backgroundMuted else CouliColors.backgroundSurface)
                .border(border, shape)
                .padding(start = CouliSpacing.space4, end = if (clearLabel != null) 0.dp else CouliSpacing.space4),
        verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
    ) {
        leading?.invoke()
        Box(modifier = Modifier.weight(1f).padding(vertical = CouliSpacing.space3), contentAlignment = Alignment.CenterStart) {
            if (showPlaceholder) Text(text = placeholder, style = CouliTypography.body, color = CouliColors.textSecondary)
            inner()
        }
        if (clearLabel != null) {
            CouliIconButton(
                icon = CouliIcons.close,
                label = clearLabel,
                onClick = onClear,
                tint = CouliColors.textSecondary,
                iconSize = CouliSizes.iconMedium,
            )
        }
    }
}

@Composable
private fun FieldFooter(
    error: String?,
    counter: String?,
) {
    if (error == null && counter == null) return
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
        if (error != null) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    CouliIcons.alert,
                    contentDescription = null,
                    tint = CouliColors.statusErrorText,
                    modifier = Modifier.padding(top = CouliSpacing.space1 / 2).size(CouliComponentTokens.iconInline),
                )
                Text(text = error, style = CouliTypography.caption, color = CouliColors.statusErrorText)
            }
        } else {
            Box(Modifier.weight(1f))
        }
        if (counter != null) Text(text = counter, style = CouliTextStyles.captionAmount, color = CouliColors.textSecondary)
    }
}

/**
 * 手机号输入（组件清单 §3.2 `PhoneField`）：「+86」前缀 + 分隔线（border.subtle）+ 输入；电话键盘 + 自动填充语义（电话号码），
 * 不预过滤（BR-ID-05）；带清除。
 */
@Composable
fun PhoneField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    clearLabel: String,
    modifier: Modifier = Modifier,
    prefix: String = PHONE_PREFIX,
    error: String? = null,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
) {
    CouliTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        placeholder = placeholder,
        enabled = enabled,
        error = error,
        clearLabel = clearLabel,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
        fieldContentType = ContentType.PhoneNumber,
        interactionSource = interactionSource,
        leading = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = prefix, style = CouliTextStyles.bodyMedium, color = CouliColors.textPrimary)
                Box(
                    Modifier
                        .padding(horizontal = CouliSpacing.space3)
                        .width(CouliSizes.hairline)
                        .height(CouliSpacing.space5)
                        .background(CouliColors.borderSubtle),
                )
            }
        },
    )
}

/**
 * 验证码输入（组件清单 §3.2 `CodeInput`）：一个整串 `BasicTextField`（数字键盘 + 短信验证码自动填充语义），格子在装饰里画：
 * 6 格（格高 56 = T7，间距 space.2，当前格 2 border.active）；[cells] 为 false 时是单框（后台动态码）。
 * [submitting]（输满自动提交中）时置灰不可输入；[error] 时格子红框。下方一行 [message]（「正在登录…」或错误说明）由调用方给文案。
 * [inputModifier] 加在实际输入框（格子款的 `BasicTextField`）上，给调用方挂焦点请求与测试标签（APP-09v-d 二次验证半屏）；默认不变。
 */
@Composable
fun CodeInput(
    value: String,
    onValueChange: (String) -> Unit,
    accessibilityLabel: String,
    modifier: Modifier = Modifier,
    length: Int = CODE_LENGTH,
    cells: Boolean = true,
    enabled: Boolean = true,
    submitting: Boolean = false,
    error: Boolean = false,
    message: String? = null,
    interactionSource: MutableInteractionSource? = null,
    inputModifier: Modifier = Modifier,
) {
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val active = enabled && !submitting
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
        if (cells) {
            BasicTextField(
                value = value,
                onValueChange = { next -> onValueChange(next.filter(Char::isDigit).take(length)) },
                modifier =
                    Modifier.fillMaxWidth().then(inputModifier).semantics {
                        contentDescription = accessibilityLabel
                        contentType = ContentType.SmsOtpCode
                    },
                enabled = active,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                interactionSource = interaction,
                textStyle = CouliTypography.heading.copy(color = Color.Transparent),
                cursorBrush = SolidColor(Color.Transparent),
                decorationBox = { inner ->
                    Box {
                        // 真正的输入框藏在格子下面（光标与文字透明），格子只负责显示
                        Box(Modifier.matchParentSize()) { inner() }
                        CodeCells(value = value, length = length, focused = focused && active, error = error, enabled = active)
                    }
                },
            )
        } else {
            CouliTextField(
                value = value,
                onValueChange = { next -> onValueChange(next.filter(Char::isDigit).take(length)) },
                enabled = active,
                accessibilityLabel = accessibilityLabel,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                fieldContentType = ContentType.SmsOtpCode,
                error = if (error) message else null,
                interactionSource = interaction,
            )
        }
        if (message != null && (cells || !error)) {
            Text(
                text = message,
                style = CouliTypography.footnote,
                color = if (error) CouliColors.statusErrorText else CouliColors.textSecondary,
            )
        }
    }
}

@Composable
private fun CodeCells(
    value: String,
    length: Int,
    focused: Boolean,
    error: Boolean,
    enabled: Boolean,
) {
    val shape = RoundedCornerShape(CouliRadius.button)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
        repeat(length) { index ->
            val current = focused && index == value.length.coerceAtMost(length - 1)
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .heightIn(min = CouliComponentTokens.codeCellHeight)
                        .clip(shape)
                        .background(if (enabled) CouliColors.backgroundSurface else CouliColors.backgroundMuted)
                        .border(fieldBorder(current, error, enabled = true, readOnly = false), shape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = value.getOrNull(index)?.toString().orEmpty(),
                    style = CouliTypography.heading,
                    color = if (enabled) CouliColors.textPrimary else CouliColors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** 验证码下方一行：「收不到验证码？」+ 倒计时（[countdown] 不为空）或【重新获取】（是否可点只看发码冷却）。 */
@Composable
fun CodeResendRow(
    hint: String,
    resendText: String,
    onResend: () -> Unit,
    modifier: Modifier = Modifier,
    countdown: String? = null,
) {
    FlowRow(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
    ) {
        Text(
            text = hint,
            style = CouliTypography.footnote,
            color = CouliColors.textSecondary,
            modifier = Modifier.align(Alignment.CenterVertically),
        )
        if (countdown != null) {
            Text(
                text = countdown,
                style = CouliTextStyles.captionAmount,
                color = CouliColors.textSecondary,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        } else {
            CouliButton(text = resendText, onClick = onResend, variant = CouliButtonVariant.TEXT)
        }
    }
}

/**
 * 搜索框（组件清单 §3.2 `SearchField`）：输入款 = `BasicTextField` + 搜索键（ImeAction.Search）+ 清除；高 44 视觉、命中 48。
 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    clearLabel: String,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource? = null,
) {
    CouliTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        placeholder = placeholder,
        clearLabel = clearLabel,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        interactionSource = interactionSource,
        leading = {
            Icon(
                CouliIcons.search,
                contentDescription = null,
                tint = CouliColors.textSecondary,
                modifier = Modifier.padding(end = CouliSpacing.space2).size(CouliSizes.iconMedium),
            )
        },
    )
}

/** 首页的搜索入口（假输入框，点了进搜索页）：整块是一个按钮，读屏「搜索，按钮」。 */
@Composable
fun SearchEntry(
    placeholder: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(CouliRadius.button)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = CouliSizes.touchTarget)
                .clip(shape)
                .background(CouliColors.backgroundSurface)
                .border(CouliSizes.hairline, CouliColors.borderControl, shape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(),
                    role = Role.Button,
                    onClick = onClick,
                ).padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        Icon(
            CouliIcons.search,
            contentDescription = null,
            tint = CouliColors.textSecondary,
            modifier = Modifier.size(CouliSizes.iconMedium),
        )
        Text(text = placeholder, style = CouliTypography.body, color = CouliColors.textSecondary)
    }
}

/**
 * 粘贴按钮（组件清单 §3.2 `PasteButton`）：Android 没有系统粘贴控件，用次按钮 + 剪贴板（调用方读 `ClipboardManager`，BR-ID-16）。
 */
@Composable
fun PasteButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    CouliButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        variant = CouliButtonVariant.SECONDARY,
        size = CouliButtonSize.COMPACT,
        enabled = enabled,
        leadingIcon = CouliIcons.copy,
    )
}

private val ACTIVE_BORDER: Dp = 2.dp
private val ERROR_BORDER: Dp = 1.5.dp
private const val TEXT_AREA_MIN_LINES = 4
private const val CODE_LENGTH = 6
private const val PHONE_PREFIX = "+86"
