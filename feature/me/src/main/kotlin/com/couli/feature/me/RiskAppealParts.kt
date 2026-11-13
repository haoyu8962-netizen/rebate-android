// 账号状态 / 申诉的私有视图件（APP-12w-c，G-R03）：只管外观；回调、显隐与文案键全部由页面传入，这里不读执行器、依赖与环境。
@file:Suppress("TooManyFunctions")

package com.couli.feature.me

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliRadius
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTextStyles.tabular
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.KeyValueRow
import com.couli.core.designsystem.WholeNumberText
import com.couli.core.designsystem.couliCard

/** 头部图标块 96、图标 32（画板 RiskNotice* / AppealResultLifted*）。 */
private val HEADER_BLOCK = 96.dp
private val HEADER_ICON = 32.dp

/** 状态胶囊：最小高 22、图标 14（画板 status pill）。 */
private val PILL_MIN_HEIGHT = 22.dp
private val PILL_ICON = 14.dp

/** 时间线：图标列 20、未到步圆圈 10（描边 1.75）、连线 2 宽、最短 16。 */
private val STEP_ICON = 20.dp
private val STEP_RING = 10.dp
private val STEP_RING_STROKE = 1.75.dp
private val STEP_LINE_WIDTH = 2.dp
private val STEP_LINE_MIN = 16.dp

/** 项目符号圆点 4；14 号行高 21 时圆点与首行居中的上距。 */
private val BULLET_DOT = 4.dp
private val BULLET_TOP = 8.5.dp

/** 申诉输入框最小高 160（画板 Appeal；组件库无最小高参数，用 leading 槽里 0 宽占位撑高，不改组件库）。 */
internal val APPEAL_INPUT_MIN_HEIGHT = 160.dp

/** 胶囊色调（底色 / 字色都取 status.* 令牌）。 */
internal enum class PillTone { PENDING, WARNING, ERROR, SUCCESS }

/** 时间线一步的状态：完成（成功圆勾）/ 当前（主文字时钟，标题 600）/ 未到（控件色圆圈，次文字）/ 警告（警告三角，标题 600）。 */
internal enum class StepState { DONE, CURRENT, PENDING, WARNING }

internal class TimelineStep(
    val title: String,
    val desc: String?,
    val state: StepState,
)

/** 头部：图标块（[blockColor] 底、[iconTint] 图标）→ 16 → 标题 22/600（读屏标题）→ 8 → 说明 14 次文字，整体居中。 */
@Suppress("LongParameterList")
@Composable
internal fun RiskHeader(
    icon: ImageVector,
    title: String,
    desc: String?,
    titleTag: String = "",
    blockColor: Color = CouliColors.backgroundMuted,
    iconTint: Color = CouliColors.textSecondary,
) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(HEADER_BLOCK).background(blockColor, RoundedCornerShape(CouliRadius.card)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(HEADER_ICON))
        }
        Spacer(Modifier.height(CouliSpacing.space4))
        Text(
            text = title,
            style = CouliTypography.title,
            color = CouliColors.textPrimary,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics { heading() }
                    .then(if (titleTag.isNotEmpty()) Modifier.testTag(titleTag) else Modifier),
        )
        desc?.let {
            Spacer(Modifier.height(CouliSpacing.space2))
            Text(
                text = it,
                style = CouliTypography.footnote,
                color = CouliColors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 信息卡（画板 padding 4/16）：行通栏、行间 1px border.subtle 齐卡内边距 16。 */
@Composable
internal fun RiskInfoCard(rows: List<@Composable () -> Unit>) {
    Column(modifier = Modifier.fillMaxWidth().couliCard().padding(vertical = CouliSpacing.space1)) {
        rows.forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = CouliSpacing.space4), color = CouliColors.borderSubtle)
            row()
        }
    }
}

/**
 * 信息卡的一行：标签 14 次文字 + 值 14/500 主文字右对齐（等宽数字，可折行）；视觉 min 44 上下 8、命中 48；整行一个读屏节点。
 * [valueContent] 给了时值位放它（如状态胶囊），否则放 [value] 文字。
 */
@Composable
internal fun RiskInfoRow(
    label: String,
    value: String,
    tag: String = "",
    valueContent: (@Composable () -> Unit)? = null,
) {
    // 判据 1 / D10-1265：值（如进行中提现的金额）在标签右侧放不下时整行改上下排，值先独占整行，仍放不下才横滑
    KeyValueRow(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = CouliSizes.touchTarget)
                .semantics(mergeDescendants = true) {}
                .then(if (tag.isNotEmpty()) Modifier.testTag(tag) else Modifier)
                .padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space2),
        centerVertically = true,
    ) {
        Text(text = label, style = CouliTypography.footnote, color = CouliColors.textSecondary)
        if (valueContent != null) {
            Box(contentAlignment = Alignment.CenterEnd) { valueContent() }
        } else {
            // 通栏占满剩余宽度再右对齐（字形右缘 = 卡内 16；折行时每行右对齐）
            WholeNumberText(
                text = value,
                style = CouliTextStyles.footnoteMedium.tabular(),
                color = CouliColors.textPrimary,
                textAlign = TextAlign.End,
            )
        }
    }
}

/** 状态胶囊：pill 圆角、内边距 2/8、最小高 22；[icon] 14 与文字同色（不读）；文字 12/400 状态字色。 */
@Composable
internal fun StatusPill(
    text: String,
    tone: PillTone,
    icon: ImageVector? = null,
) {
    val (background, foreground) = pillColors(tone)
    Row(
        modifier =
            Modifier
                .heightIn(min = PILL_MIN_HEIGHT)
                .background(background, RoundedCornerShape(CouliRadius.pill))
                .padding(horizontal = CouliSpacing.space2, vertical = CouliSpacing.space1 / 2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
    ) {
        icon?.let { Icon(it, contentDescription = null, tint = foreground, modifier = Modifier.size(PILL_ICON)) }
        Text(text = text, style = CouliTypography.caption, color = foreground)
    }
}

@Composable
private fun pillColors(tone: PillTone): Pair<Color, Color> =
    when (tone) {
        PillTone.PENDING -> CouliColors.statusPendingBackground to CouliColors.statusPendingText
        PillTone.WARNING -> CouliColors.statusWarningBackground to CouliColors.statusWarningText
        PillTone.ERROR -> CouliColors.statusErrorBackground to CouliColors.statusErrorText
        PillTone.SUCCESS -> CouliColors.statusSuccessBackground to CouliColors.statusSuccessText
    }

/** 申诉页白卡（padding 16、纵向 8）：[title] 给了时是卡内标题 17/600（读屏标题）。 */
@Composable
internal fun AppealInfoCard(
    title: String?,
    tag: String = "",
    content: @Composable () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .couliCard()
                .padding(CouliSpacing.space4)
                .then(if (tag.isNotEmpty()) Modifier.testTag(tag) else Modifier),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        title?.let {
            Text(
                text = it,
                style = CouliTextStyles.bodySemibold,
                color = CouliColors.textPrimary,
                modifier = Modifier.semantics { heading() },
            )
        }
        content()
    }
}

/** 卡内一行「标签 值」：14 号、值紧跟标签 8（左对齐、可折行，标签与值首行顶对齐）；整行一个读屏节点。[valueContent] 给了时值位放它（状态胶囊）。 */
@Composable
internal fun InlineRow(
    label: String,
    value: String,
    valueContent: (@Composable () -> Unit)? = null,
) {
    // 判据 1：值在标签右侧放不下时整行改上下排，值先独占整行，仍放不下才横滑
    KeyValueRow(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, gap = CouliSpacing.space2) {
        Text(text = label, style = CouliTypography.footnote, color = CouliColors.textSecondary)
        if (valueContent != null) {
            Box { valueContent() }
        } else {
            WholeNumberText(text = value, style = CouliTypography.footnote.tabular(), color = CouliColors.textPrimary)
        }
    }
}

/** 时间线（卡内 16）：每步一个读屏节点（标题，说明）；图标与连线不读。 */
@Composable
internal fun AppealTimeline(steps: List<TimelineStep>) {
    Column(modifier = Modifier.fillMaxWidth().couliCard().padding(CouliSpacing.space4)) {
        steps.forEachIndexed { index, step -> TimelineStepRow(step, last = index == steps.lastIndex) }
    }
}

@Composable
private fun TimelineStepRow(
    step: TimelineStep,
    last: Boolean,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        Column(
            modifier = Modifier.width(STEP_ICON).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
        ) {
            StepIcon(step.state)
            if (!last) {
                Box(
                    modifier =
                        Modifier
                            .width(STEP_LINE_WIDTH)
                            .heightIn(min = STEP_LINE_MIN)
                            .weight(1f)
                            .background(CouliColors.borderSubtle),
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f).padding(bottom = if (last) 0.dp else CouliSpacing.space3),
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1 / 2),
        ) {
            val strong = step.state == StepState.CURRENT || step.state == StepState.WARNING
            Text(
                text = step.title,
                style = if (strong) CouliTextStyles.bodySemibold else CouliTypography.body,
                color = if (step.state == StepState.PENDING) CouliColors.textSecondary else CouliColors.textPrimary,
            )
            step.desc?.let {
                Text(text = it, style = CouliTypography.footnote.tabular(), color = CouliColors.textSecondary)
            }
        }
    }
}

@Composable
private fun StepIcon(state: StepState) {
    when (state) {
        StepState.DONE -> {
            Icon(
                com.couli.core.designsystem.CouliIcons.check,
                contentDescription = null,
                tint = CouliColors.statusSuccessText,
                modifier = Modifier.size(STEP_ICON),
            )
        }

        StepState.CURRENT -> {
            Icon(
                com.couli.core.designsystem.CouliIcons.clock,
                contentDescription = null,
                tint = CouliColors.textPrimary,
                modifier = Modifier.size(STEP_ICON),
            )
        }

        StepState.WARNING -> {
            Icon(
                com.couli.core.designsystem.CouliIcons.alert,
                contentDescription = null,
                tint = CouliColors.statusWarningText,
                modifier = Modifier.size(STEP_ICON),
            )
        }

        StepState.PENDING -> {
            Box(modifier = Modifier.size(STEP_ICON), contentAlignment = Alignment.Center) {
                Box(modifier = Modifier.size(STEP_RING).border(STEP_RING_STROKE, CouliColors.borderControl, CircleShape))
            }
        }
    }
}

/** 项目符号卡（「接下来可以」）：卡内标题 + 逐条成行，每条前一个 4 的次文字色圆点（不读）。 */
@Composable
internal fun BulletCard(
    title: String,
    lines: List<String>,
) {
    AppealInfoCard(title) {
        lines.forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
                Box(
                    modifier =
                        Modifier
                            .padding(top = BULLET_TOP)
                            .size(BULLET_DOT)
                            .background(CouliColors.textSecondary, CircleShape),
                )
                Text(text = line, style = CouliTypography.footnote, color = CouliColors.textPrimary, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** 出口行（客服 | 注销 | 退出）：居中、放不下整块换行；链接由页面放入（回调与去向只在页面里写）。 */
@Composable
internal fun RiskExitRow(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space1, Alignment.CenterHorizontally),
        itemVerticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** 出口行里「| 链接」一组（F-AND-75 ②）：分隔符与其后的链接同组、间距同出口行（4），放不下时整组换行，分隔符不挂行尾。 */
@Composable
internal fun ExitLinkGroup(content: @Composable RowScope.() -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** 出口行分隔符「|」：border.subtle、不进读屏。 */
@Composable
internal fun ExitSeparator() {
    Text(text = "|", style = CouliTypography.footnote, color = CouliColors.borderSubtle, modifier = Modifier.clearAndSetSemantics {})
}
