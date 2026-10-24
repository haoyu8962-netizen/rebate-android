package com.couli.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 圆形勾选框的图形（组件清单 §3.3，登录 v2 LoginCheckboxSpec）：直径 [baseSize]（T8，默认 18）× min(字号倍数, 1.5)，
 * 未选 2dp 描边空心（border.control，底 surface）、选中实心（control.checked）+ 白勾（text.inverse，线宽 2）；
 * 禁用用 button.primary.disabled.*。只画图，交互与读屏由外层 toggleable 给。
 */
@Composable
internal fun CheckboxGlyph(
    checked: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    baseSize: Dp = CouliComponentTokens.checkboxSize,
) {
    val scale = minOf(LocalDensity.current.fontScale, CouliComponentTokens.CHECKBOX_MAX_SCALE)
    val diameter = baseSize * scale
    val stroke = CHECKBOX_STROKE * scale
    val fill =
        when {
            checked && enabled -> CouliColors.controlChecked
            checked -> CouliColors.buttonPrimaryDisabledBackground
            enabled -> CouliColors.backgroundSurface
            else -> CouliColors.backgroundMuted
        }
    val ring = if (enabled) CouliColors.borderControl else CouliColors.borderSubtle
    val mark = if (enabled) CouliColors.textInverse else CouliColors.buttonPrimaryDisabledText
    Canvas(modifier = modifier.size(diameter)) {
        val radius = size.minDimension / 2
        val strokePx = stroke.toPx()
        if (checked) {
            drawCircle(color = fill, radius = radius)
            val d = size.minDimension
            val path =
                Path().apply {
                    moveTo(d * CHECK_START_X, d * CHECK_START_Y)
                    lineTo(d * CHECK_MID_X, d * CHECK_MID_Y)
                    lineTo(d * CHECK_END_X, d * CHECK_END_Y)
                }
            drawPath(path, color = mark, style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round))
        } else {
            drawCircle(color = fill, radius = radius - strokePx)
            drawCircle(color = ring, radius = radius - strokePx / 2, center = Offset(radius, radius), style = Stroke(width = strokePx))
        }
    }
}

/**
 * 单独的圆形勾选框（组件清单 §3.3，DS-02【改系统】）：系统交互语义 `toggleable(role = Checkbox)` + 圆形矢量，
 * 命中区 48（`minimumInteractiveComponentSize`），系统水波纹（无界，半径 24）。M3 `Checkbox` 只有方形，不用。
 * [contentDescription] 是读屏名称（整句，字典给）。[interactionSource] 供目录页展示按下态。
 */
@Composable
fun CouliCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    glyphSize: Dp = CouliComponentTokens.checkboxSize,
    interactionSource: MutableInteractionSource? = null,
) {
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    Box(
        modifier =
            modifier
                // 命中区 48（M3 的 minimumInteractiveComponentSize 在 1.4 起只扩触控区、不扩节点，读屏框仍是 18，故显式撑开）
                .sizeIn(minWidth = CouliSizes.touchTarget, minHeight = CouliSizes.touchTarget)
                .semantics { this.contentDescription = contentDescription }
                .toggleable(
                    value = checked,
                    interactionSource = interaction,
                    indication = ripple(bounded = false, radius = CouliSizes.touchTarget / 2),
                    enabled = enabled,
                    role = Role.Checkbox,
                    onValueChange = onCheckedChange,
                ),
        contentAlignment = Alignment.Center,
    ) {
        CheckboxGlyph(checked = checked, enabled = enabled, baseSize = glyphSize)
    }
}

/** 带文字整行的勾选（授权管理多选等）：整行可点、读屏一项「文字，复选框，已选中」，行高不低于 48。 */
@Composable
fun CouliCheckboxRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
) {
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    Row(
        modifier =
            modifier
                .heightIn(min = CouliSizes.touchTarget)
                .toggleable(
                    value = checked,
                    interactionSource = interaction,
                    indication = ripple(),
                    enabled = enabled,
                    role = Role.Checkbox,
                    onValueChange = onCheckedChange,
                ).padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        CheckboxGlyph(checked = checked, enabled = enabled)
        Text(
            text = label,
            style = CouliTypography.body,
            color = if (enabled) CouliColors.textPrimary else CouliColors.textSecondary,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 协议勾选行（组件清单 §3.7，03 §10.2 `AgreementCheckRow`）：圆形勾选 + 一段系统富文本，协议链接嵌在句子里
 * （`LinkAnnotation.Clickable`，点链接只打开协议、不勾选）。整行是一个读屏项「整句，复选框，未选中」，协议链接另作自定义操作可达。
 * 行高跟文字走（不撑成 48）；圆形的 48 命中区由系统最小触控区扩展补足（不与上下相邻目标重叠时生效）。
 * 未勾选是默认（BR-ID-04）；确认框同意后由调用方把 [checked] 置真。
 */
@Composable
fun AgreementCheckRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    /** 句子样式（APP-09v-e：实名同意、劳务协议用 14 主文字）；默认 12 次文字不变（登录、目录页）。行高与圆形对齐都跟它走。 */
    textStyle: TextStyle = CouliTypography.caption,
    /** 句子颜色；默认次文字色不变。 */
    textColor: Color = CouliColors.textSecondary,
) {
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val linkColor = CouliColors.textLink
    val links = remember(text) { text.getLinkAnnotations(0, text.length) }
    val styled =
        remember(text, linkColor) {
            buildAnnotatedString {
                append(text)
                links.forEach { addStyle(SpanStyle(color = linkColor), it.start, it.end) }
            }
        }
    val style = textStyle
    Layout(
        contents =
            listOf(
                {
                    Box(modifier = Modifier.indication(interaction, ripple(bounded = false, radius = CouliSizes.touchTarget / 2))) {
                        CheckboxGlyph(checked = checked, enabled = enabled, modifier = Modifier.testTag(AGREEMENT_CIRCLE_TAG))
                    }
                },
                { Text(text = styled, style = style, color = textColor) },
            ),
        modifier =
            modifier
                .toggleable(
                    value = checked,
                    interactionSource = interaction,
                    // 水波纹画在圆形上（上面的 indication），整行不铺底
                    indication = null,
                    enabled = enabled,
                    role = Role.Checkbox,
                    onValueChange = onCheckedChange,
                ).semantics {
                    customActions =
                        links.mapNotNull { range ->
                            val link = range.item as? LinkAnnotation.Clickable ?: return@mapNotNull null
                            CustomAccessibilityAction(text.text.substring(range.start, range.end)) {
                                link.linkInteractionListener?.onClick(link)
                                true
                            }
                        }
                },
    ) { (circleMeasurables, textMeasurables), constraints ->
        // 行高只跟文字走：圆形对齐首行居中，不撑高整行（48 命中区靠系统最小触控扩展）
        val gap = CouliSpacing.space2.roundToPx()
        val circle = circleMeasurables.single().measure(Constraints())
        val textMax = (constraints.maxWidth - circle.width - gap).coerceAtLeast(0)
        val sentence = textMeasurables.single().measure(Constraints(maxWidth = textMax))
        val width = (circle.width + gap + sentence.width).coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = sentence.height.coerceIn(constraints.minHeight, constraints.maxHeight)
        val firstLine = minOf(sentence.height, style.lineHeight.roundToPx())
        layout(width, height) {
            circle.place(0, (firstLine - circle.height) / 2)
            sentence.place(circle.width + gap, 0)
        }
    }
}

/** 协议行圆形的测试标记（命中区测试量可见圆）。 */
const val AGREEMENT_CIRCLE_TAG = "agreement-circle"

/**
 * 开关（组件清单 §3.3）：M3 `Switch` 套令牌（选中轨道 control.checked、关时描边 border.control），整行可点、读屏一项
 * 「文字，开关，已开启」（开关本身不单独读）。行高不低于 48。
 */
@Composable
fun CouliSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supportingText: String? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    Row(
        modifier =
            modifier
                .heightIn(min = CouliSizes.touchTarget)
                .toggleable(
                    value = checked,
                    interactionSource = interaction,
                    indication = ripple(),
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                ).padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        SelectionLabels(label = label, supportingText = supportingText, enabled = enabled, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            interactionSource = interaction,
            colors = couliSwitchColors(),
        )
    }
}

@Composable
private fun couliSwitchColors() =
    SwitchDefaults.colors(
        checkedThumbColor = CouliColors.textInverse,
        checkedTrackColor = CouliColors.controlChecked,
        checkedBorderColor = CouliColors.controlChecked,
        checkedIconColor = CouliColors.controlChecked,
        uncheckedThumbColor = CouliColors.borderControl,
        uncheckedTrackColor = CouliColors.backgroundMuted,
        uncheckedBorderColor = CouliColors.borderControl,
        uncheckedIconColor = CouliColors.backgroundMuted,
        disabledCheckedThumbColor = CouliColors.buttonPrimaryDisabledText,
        disabledCheckedTrackColor = CouliColors.buttonPrimaryDisabledBackground,
        disabledCheckedBorderColor = CouliColors.buttonPrimaryDisabledBackground,
        disabledUncheckedThumbColor = CouliColors.borderSubtle,
        disabledUncheckedTrackColor = CouliColors.backgroundMuted,
        disabledUncheckedBorderColor = CouliColors.borderSubtle,
    )

/** 单选的一行（组件清单 §3.3）：M3 `RadioButton` + 整行 `selectable(role = RadioButton)`；同组的行放进 [CouliRadioGroup]。 */
@Composable
fun CouliRadio(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supportingText: String? = null,
) {
    Row(
        modifier =
            modifier
                .heightIn(min = CouliSizes.touchTarget)
                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
                .padding(horizontal = CouliSpacing.space2, vertical = CouliSpacing.space1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
            colors =
                RadioButtonDefaults.colors(
                    selectedColor = CouliColors.controlChecked,
                    unselectedColor = CouliColors.borderControl,
                    disabledSelectedColor = CouliColors.buttonPrimaryDisabledBackground,
                    disabledUnselectedColor = CouliColors.borderSubtle,
                ),
            modifier = Modifier.minimumInteractiveComponentSize(),
        )
        SelectionLabels(label = label, supportingText = supportingText, enabled = enabled, modifier = Modifier.weight(1f))
    }
}

/** 单选组容器：读屏把组内单选当一组（`selectableGroup`）。 */
@Composable
fun CouliRadioGroup(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.selectableGroup()) { content() }
}

@Composable
private fun SelectionLabels(
    label: String,
    supportingText: String?,
    enabled: Boolean,
    modifier: Modifier,
) {
    Column(modifier = modifier) {
        Text(text = label, style = CouliTypography.body, color = if (enabled) CouliColors.textPrimary else CouliColors.textSecondary)
        if (supportingText != null) {
            Text(text = supportingText, style = CouliTypography.caption, color = CouliColors.textSecondary)
        }
    }
}

/**
 * 分段选择（组件清单 §3.3 `Segmented`，DS-02【改系统】）：M3 `SingleChoiceSegmentedButtonRow`（40 高、48 命中区），
 * 选中 brand.subtle 底 + brand.emphasis 字。[selectedIndex] 为 -1 时都不选。
 */
@Composable
fun CouliSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors =
        SegmentedButtonDefaults.colors(
            activeContainerColor = CouliColors.brandSubtle,
            activeContentColor = CouliColors.brandEmphasis,
            activeBorderColor = CouliColors.borderControl,
            inactiveContainerColor = CouliColors.backgroundSurface,
            inactiveContentColor = CouliColors.textSecondary,
            inactiveBorderColor = CouliColors.borderControl,
            disabledActiveContainerColor = CouliColors.backgroundMuted,
            disabledActiveContentColor = CouliColors.textSecondary,
            disabledActiveBorderColor = CouliColors.borderSubtle,
            disabledInactiveContainerColor = CouliColors.backgroundSurface,
            disabledInactiveContentColor = CouliColors.textSecondary,
            disabledInactiveBorderColor = CouliColors.borderSubtle,
        )
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                enabled = enabled,
                colors = colors,
                label = { Text(text = option, style = CouliTextStyles.footnoteMedium) },
            )
        }
    }
}

/** 筛选胶囊的种类：普通筛选、带下拉（价格区间）、排序项。 */
enum class FilterChipKind {
    FILTER,
    DROPDOWN,
    SORT,
}

/**
 * 筛选胶囊（组件清单 §3.3 `FilterChip`，DS-02【改系统】）：M3 `FilterChip`（视觉高 32 = T9，命中区 48），已选加对勾，
 * 已选 brand.subtle 底 + brand.emphasis 字，未选白底 + border.subtle 描边，胶囊圆角。
 */
@Composable
fun CouliFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    kind: FilterChipKind = FilterChipKind.FILTER,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text = label, style = CouliTypography.caption) },
        modifier = modifier.heightIn(min = CouliComponentTokens.chipHeight),
        enabled = enabled,
        leadingIcon =
            if (selected) {
                { Icon(CouliIcons.check, contentDescription = null, modifier = Modifier.size(CouliComponentTokens.iconInline)) }
            } else {
                null
            },
        trailingIcon =
            when (kind) {
                FilterChipKind.FILTER -> {
                    null
                }

                FilterChipKind.DROPDOWN -> {
                    { Icon(CouliIcons.chevronDown, contentDescription = null, modifier = Modifier.size(CouliComponentTokens.iconInline)) }
                }

                FilterChipKind.SORT -> {
                    { Icon(CouliIcons.trend, contentDescription = null, modifier = Modifier.size(CouliComponentTokens.iconInline)) }
                }
            },
        shape = RoundedCornerShape(CouliRadius.pill),
        colors =
            FilterChipDefaults.filterChipColors(
                containerColor = CouliColors.backgroundSurface,
                labelColor = CouliColors.textPrimary,
                iconColor = CouliColors.textSecondary,
                selectedContainerColor = CouliColors.brandSubtle,
                selectedLabelColor = CouliColors.brandEmphasis,
                selectedLeadingIconColor = CouliColors.brandEmphasis,
                selectedTrailingIconColor = CouliColors.brandEmphasis,
                disabledContainerColor = CouliColors.backgroundMuted,
                disabledLabelColor = CouliColors.textSecondary,
                disabledLeadingIconColor = CouliColors.textSecondary,
                disabledTrailingIconColor = CouliColors.textSecondary,
                disabledSelectedContainerColor = CouliColors.backgroundMuted,
            ),
        border =
            FilterChipDefaults.filterChipBorder(
                enabled = enabled,
                selected = selected,
                borderColor = CouliColors.borderSubtle,
                selectedBorderColor = CouliColors.brandSubtle,
                disabledBorderColor = CouliColors.borderSubtle,
                disabledSelectedBorderColor = CouliColors.borderSubtle,
                borderWidth = CouliSizes.hairline,
                selectedBorderWidth = CouliSizes.hairline,
            ),
    )
}

private val CHECKBOX_STROKE = 2.dp
private const val CHECK_START_X = 0.28f
private const val CHECK_START_Y = 0.52f
private const val CHECK_MID_X = 0.44f
private const val CHECK_MID_Y = 0.67f
private const val CHECK_END_X = 0.73f
private const val CHECK_END_Y = 0.36f
