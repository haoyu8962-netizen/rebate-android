// 换手机号子页的私有视图件（APP-13v-b，G-D03）：只管外观；回调、可用条件、文案与标记全部由页面传入，这里不读执行器与页面依赖，不起协程、不取时钟。
@file:Suppress("TooManyFunctions")

package com.couli.feature.me

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliRadius
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTextStyles.tabular
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.SendCodeButton
import com.couli.core.designsystem.WholeNumberText
import com.couli.core.designsystem.sendCodeAsButton

/** 步骤圆 24、连线最短 16、圆与文字间距 8、文字与连线间距 12、整条左右内边距 4（PhoneChange-v2 步骤条）。 */
private val STEP_CIRCLE = 24.dp
private val STEP_LINE_MIN = 16.dp
private val STEP_LABEL_GAP = 8.dp
private val STEP_LINE_GAP = 12.dp
private val STEP_SIDE = 4.dp

/** 步骤条子项顺序：圆 1、文字 1、连线、圆 2、文字 2。 */
private const val STEP_CIRCLE_1 = 0
private const val STEP_LABEL_1 = 1
private const val STEP_LINE = 2
private const val STEP_CIRCLE_2 = 3
private const val STEP_LABEL_2 = 4

/** 手机号输入框最小高 52（画板）、错误行三角 16、完成图标块 96 / 圆勾 32、完成内容上 48（页面内边距 16 + 32）。 */
private val PHONE_BOX_MIN = 52.dp
private val ERROR_ICON = 16.dp
private val DONE_BLOCK = 96.dp
private val DONE_ICON = 32.dp
private val DONE_TOP = 32.dp

/** 步骤状态（只由页面传入的当前步映射：之前 = 完成、等于 = 当前、之后 = 未到）。 */
internal enum class StepMark { DONE, CURRENT, UPCOMING }

/** 步骤 [index]（从 1 起）相对当前步 [current] 的状态（纯映射，单测用）。 */
internal fun stepMark(
    index: Int,
    current: Int,
): StepMark =
    when {
        index < current -> StepMark.DONE
        index == current -> StepMark.CURRENT
        else -> StepMark.UPCOMING
    }

/**
 * 两步步骤条：24 圆（当前 brand.primary 实心白字 12/600；未到 1px border.control 描边次文字；完成 status.success 圆勾）+ 14 文字（当前 600 主文字、完成 400 主文字、
 * 未到 400 次文字）+ 中间 1px border.subtle 连线（最短 16）。放不下时两段文字各取一半宽度折行（200% 不截断）。读屏名称与标记由页面挂在 [modifier] 上，
 * 圆与连线不读。
 */
@Composable
internal fun StepBar(
    first: String,
    second: String,
    current: Int,
    modifier: Modifier,
) {
    Layout(
        modifier = modifier.padding(horizontal = STEP_SIDE),
        content = {
            StepCircle(number = 1, mark = stepMark(1, current))
            StepLabel(first, stepMark(1, current))
            Box(Modifier.background(CouliColors.borderSubtle))
            StepCircle(number = 2, mark = stepMark(2, current))
            StepLabel(second, stepMark(2, current))
        },
    ) { measurables, constraints ->
        val circleItem = measurables[STEP_CIRCLE_1]
        val firstItem = measurables[STEP_LABEL_1]
        val lineItem = measurables[STEP_LINE]
        val circle2Item = measurables[STEP_CIRCLE_2]
        val secondItem = measurables[STEP_LABEL_2]
        val circle = circleItem.measure(Constraints())
        val circle2 = circle2Item.measure(Constraints())
        val labelGap = STEP_LABEL_GAP.roundToPx()
        val lineGap = STEP_LINE_GAP.roundToPx()
        val lineMin = STEP_LINE_MIN.roundToPx()
        val width = constraints.maxWidth
        val fixed = circle.width + circle2.width + labelGap * 2 + lineGap * 2
        val room = (width - fixed).coerceAtLeast(0)
        val want1 = firstItem.maxIntrinsicWidth(Constraints.Infinity)
        val want2 = secondItem.maxIntrinsicWidth(Constraints.Infinity)
        val fits = want1 + want2 + lineMin <= room
        val half = ((room - lineMin) / 2).coerceAtLeast(0)
        val label1 = firstItem.measure(Constraints(maxWidth = if (fits) want1 else half))
        val label2 = secondItem.measure(Constraints(maxWidth = if (fits) want2 else half))
        val lineWidth = (room - label1.width - label2.width).coerceAtLeast(lineMin)
        val line = lineItem.measure(Constraints.fixed(lineWidth, CouliSizes.hairline.roundToPx().coerceAtLeast(1)))
        val height = maxOf(circle.height, label1.height, label2.height)
        layout(width, height) {
            var x = 0
            circle.place(x, (height - circle.height) / 2)
            x += circle.width + labelGap
            label1.place(x, (height - label1.height) / 2)
            x += label1.width + lineGap
            line.place(x, (height - line.height) / 2)
            x += line.width + lineGap
            circle2.place(x, (height - circle2.height) / 2)
            x += circle2.width + labelGap
            label2.place(x, (height - label2.height) / 2)
        }
    }
}

/** 步骤圆（装饰，不读）：当前 = 品牌实心 + 白字编号；未到 = 描边 + 次文字编号；完成 = status.success 圆勾。 */
@Composable
private fun StepCircle(
    number: Int,
    mark: StepMark,
) {
    when (mark) {
        StepMark.DONE -> {
            Icon(
                CouliIcons.check,
                contentDescription = null,
                tint = CouliColors.statusSuccessText,
                modifier = Modifier.size(STEP_CIRCLE),
            )
        }

        StepMark.CURRENT, StepMark.UPCOMING -> {
            val current = mark == StepMark.CURRENT
            Box(
                modifier =
                    Modifier
                        .size(STEP_CIRCLE)
                        .clearAndSetSemantics {}
                        .background(if (current) CouliColors.brandPrimary else CouliColors.backgroundSurface, CircleShape)
                        .then(if (current) Modifier else Modifier.border(CouliSizes.hairline, CouliColors.borderControl, CircleShape)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = number.toString(),
                    style = if (current) CouliTextStyles.captionSemibold else CouliTypography.caption,
                    color = if (current) CouliColors.textInverse else CouliColors.textSecondary,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.wrapContentSize(unbounded = true),
                )
            }
        }
    }
}

@Composable
private fun StepLabel(
    text: String,
    mark: StepMark,
) {
    Text(
        text = text,
        style = if (mark == StepMark.CURRENT) CouliTextStyles.footnoteSemibold else CouliTypography.footnote,
        color = if (mark == StepMark.UPCOMING) CouliColors.textSecondary else CouliColors.textPrimary,
    )
}

/**
 * 验证码格下一行（PhoneChangeCode「{n} 秒后重新获取」/ 可点「获取验证码」）：右对齐到格子右缘；可点盒自身至少 48 × 48（触摸盒与语义盒都 ≥ 48），
 * 放在格子正下方不与格子相交；文字 14，可点时 text.link、倒计时 / 不可点时次文字。倒计时不挂实时区域（不逐秒读）。
 */
@Composable
internal fun CodeSendRow(
    text: String,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        if (sendCodeAsButton()) {
            // FU-AND-ADAPT-CHECK ①：200% 下改描边按钮（发码前 / 倒计时禁用 / 可重发三态同款），仍靠右、不缩字
            SendCodeButton(
                text = text,
                onClick = onClick,
                textStyle = CouliTypography.footnote.tabular(),
                enabled = enabled,
                modifier = Modifier.testTag(tag),
            )
        } else {
            Box(
                modifier =
                    Modifier
                        .heightIn(min = CouliSizes.touchTarget)
                        .widthIn(min = CouliSizes.touchTarget)
                        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                        .testTag(tag),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    text = text,
                    style = CouliTypography.footnote.tabular(),
                    color = if (enabled) CouliColors.textLink else CouliColors.textSecondary,
                )
            }
        }
    }
}

/** 字段错误行（PhoneChangeCodeError：16 三角 + 14 红字紧贴格下）：三角装饰不读，错误句本身由页面传入（焦点与一次播报原样）。 */
@Composable
internal fun FieldErrorLine(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = CouliSpacing.space2),
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            CouliIcons.alert,
            contentDescription = null,
            tint = CouliColors.statusErrorText,
            modifier = Modifier.padding(top = CouliSpacing.space1 / 2).size(ERROR_ICON),
        )
        content()
    }
}

/** 文字链接 + 右侧 chevron（「原手机号不能用了？联系客服 ›」）：14/500 text.link；整行可点、高至少 48；chevron 装饰不读。 */
@Composable
internal fun ChevronLink(
    text: String,
    tag: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .heightIn(min = CouliSizes.touchTarget)
                .clickable(role = Role.Button, onClick = onClick)
                .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
    ) {
        Text(
            text = text,
            style = CouliTextStyles.footnoteMedium,
            color = CouliColors.textLink,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(
            CouliIcons.chevron,
            contentDescription = null,
            tint = CouliColors.textLink,
            modifier = Modifier.size(CouliSizes.iconSmall),
        )
    }
}

/**
 * 手机号输入框（PhoneChangeNew-v2）：白底、1px border.control（错误时 status.error）、圆角 12、最小高 52、左右内边距 12；+86 次文字 17、无分隔线；
 * 空值时占位次文字。[tag] 挂在可输入的 BasicTextField 本身（测试按标记直接写入），读屏名称 [spoken]、错误说明作为输入框的 error 语义。右侧无尾部控件。
 */
@Suppress("LongParameterList")
@Composable
internal fun PhoneInputBox(
    value: String,
    onValueChange: (String) -> Unit,
    prefix: String,
    placeholder: String,
    spoken: String,
    enabled: Boolean,
    error: String?,
    tag: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = PHONE_BOX_MIN)
                .background(CouliColors.backgroundSurface, RoundedCornerShape(CouliRadius.button))
                .border(
                    CouliSizes.hairline,
                    if (error != null) CouliColors.statusErrorText else CouliColors.borderControl,
                    RoundedCornerShape(CouliRadius.button),
                ).padding(horizontal = CouliSpacing.space3, vertical = CouliSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        Text(text = prefix, style = CouliTypography.body, color = CouliColors.textSecondary)
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                // 占位只给视觉（读屏读输入框自己的名称），节点保留文字以便按字核对
                Text(
                    text = placeholder,
                    style = CouliTypography.body,
                    color = CouliColors.textSecondary,
                    modifier = Modifier.semantics { hideFromAccessibility() },
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                enabled = enabled,
                textStyle = CouliTypography.body.copy(color = CouliColors.textPrimary),
                cursorBrush = SolidColor(CouliColors.textPrimary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag(tag)
                        .semantics {
                            contentDescription = spoken
                            if (error != null) error(error)
                        },
            )
        }
    }
}

/**
 * 完成页头部（PhoneChangeDone）：内容上 48、居中、纵向 12：96 × 96 圆角 16 的 status.success 图标块 + 32 圆勾（装饰不读）→ 17/600 标题（读屏标题）→
 * 14 次文字说明。[modifier] 由页面挂标记。
 */
@Composable
internal fun DoneHeader(
    title: String,
    body: String,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(top = DONE_TOP),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        Box(
            modifier =
                Modifier
                    .size(DONE_BLOCK)
                    .background(CouliColors.statusSuccessBackground, RoundedCornerShape(CouliRadius.card)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(CouliIcons.check, contentDescription = null, tint = CouliColors.statusSuccessText, modifier = Modifier.size(DONE_ICON))
        }
        Text(
            text = title,
            style = CouliTextStyles.bodySemibold,
            color = CouliColors.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().semantics { heading() },
        )
        // 判据 1：句中脱敏新号经共用整段数字件，整段不断
        WholeNumberText(
            text = body,
            style = CouliTypography.footnote.tabular(),
            color = CouliColors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 底部按钮下的 12 次文字居中提示（PhoneChangeNew-v2「提交后，当前手机号会换成新手机号」）。 */
@Composable
internal fun BottomHint(text: String) {
    Text(
        text = text,
        style = CouliTypography.caption,
        color = CouliColors.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}
