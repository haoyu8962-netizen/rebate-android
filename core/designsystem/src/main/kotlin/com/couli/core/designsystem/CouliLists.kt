package com.couli.core.designsystem

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 分隔线（组件清单 §3.5 `Divider`）：M3 `HorizontalDivider`（border.subtle，1）；[inset] 内缩；给了 [text] 时为带文字分隔
 * （「其他登录方式」：两侧线、中间 12 号次要字）。
 */
@Composable
fun CouliDivider(
    modifier: Modifier = Modifier,
    inset: Dp = 0.dp,
    text: String? = null,
) {
    if (text == null) {
        HorizontalDivider(modifier = modifier.padding(start = inset), thickness = CouliSizes.hairline, color = CouliColors.borderSubtle)
        return
    }
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = inset),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), thickness = CouliSizes.hairline, color = CouliColors.borderSubtle)
        Text(text = text, style = CouliTypography.caption, color = CouliColors.textSecondary, textAlign = TextAlign.Center)
        HorizontalDivider(modifier = Modifier.weight(1f), thickness = CouliSizes.hairline, color = CouliColors.borderSubtle)
    }
}

/** 列表行右侧的内容。 */
sealed interface ListRowTrailing {
    /** 右箭头（进入下一页）。 */
    data object Arrow : ListRowTrailing

    /** 右侧值（如缓存大小、版本号）+ 箭头。 */
    data class Value(
        val text: String,
    ) : ListRowTrailing

    /** 开关：整行切换，读屏「标题，开关，已开启」。 */
    data class Toggle(
        val checked: Boolean,
        val onCheckedChange: (Boolean) -> Unit,
    ) : ListRowTrailing
}

/**
 * 列表行（组件清单 §3.5 `ListRow`，DS-02【改系统】）：M3 `ListItem`（headline / supporting / trailing），放在白卡片里；
 * 按下用系统水波纹。带开关时整行是开关（`toggleable(role = Switch)`），否则整行是按钮。[enabled] 为 false 时置灰不可点。
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: ListRowTrailing = ListRowTrailing.Arrow,
    onClick: () -> Unit = {},
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
) {
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val action =
        when (trailing) {
            is ListRowTrailing.Toggle -> {
                Modifier.toggleable(
                    value = trailing.checked,
                    interactionSource = interaction,
                    indication = ripple(),
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = trailing.onCheckedChange,
                )
            }

            else -> {
                Modifier.clickable(
                    interactionSource = interaction,
                    indication = ripple(),
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                )
            }
        }
    val content = if (enabled) CouliColors.textPrimary else CouliColors.textSecondary
    ListItem(
        headlineContent = { Text(text = title, style = CouliTypography.body) },
        modifier = modifier.fillMaxWidth().then(action),
        supportingContent = subtitle?.let { { Text(text = it, style = CouliTypography.caption) } },
        trailingContent = {
            when (trailing) {
                ListRowTrailing.Arrow -> {
                    RowChevron()
                }

                is ListRowTrailing.Value -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
                        Text(text = trailing.text, style = CouliTypography.footnote, color = CouliColors.textSecondary)
                        RowChevron()
                    }
                }

                is ListRowTrailing.Toggle -> {
                    Switch(
                        checked = trailing.checked,
                        onCheckedChange = null,
                        enabled = enabled,
                        interactionSource = interaction,
                        colors =
                            SwitchDefaults.colors(
                                checkedThumbColor = CouliColors.textInverse,
                                checkedTrackColor = CouliColors.controlChecked,
                                checkedBorderColor = CouliColors.controlChecked,
                                uncheckedThumbColor = CouliColors.borderControl,
                                uncheckedTrackColor = CouliColors.backgroundMuted,
                                uncheckedBorderColor = CouliColors.borderControl,
                                disabledCheckedTrackColor = CouliColors.buttonPrimaryDisabledBackground,
                                disabledUncheckedTrackColor = CouliColors.backgroundMuted,
                            ),
                    )
                }
            }
        },
        colors =
            ListItemDefaults.colors(
                containerColor = CouliColors.backgroundSurface,
                headlineColor = content,
                supportingColor = CouliColors.textSecondary,
                trailingIconColor = CouliColors.textSecondary,
                disabledHeadlineColor = CouliColors.textSecondary,
            ),
    )
}

@Composable
private fun RowChevron() {
    Icon(CouliIcons.chevron, contentDescription = null, tint = CouliColors.textSecondary, modifier = Modifier.size(CouliSizes.iconMedium))
}

/**
 * 角标（组件清单 §3.5 `CouliBadge`）：M3 `Badge`，[count] 为空时是红点，否则数字（T4：badge.background、高 18）；
 * 外圈 background.canvas 描边。[contentDescription] 是读屏文字（如「有未读」），不只靠颜色。
 */
@Composable
fun CouliBadge(
    contentDescription: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
) {
    val outline = CouliColors.backgroundCanvas
    if (count == null) {
        Badge(
            modifier =
                modifier
                    .border(CouliSizes.hairline, outline, CircleShape)
                    .semantics { this.contentDescription = contentDescription },
            containerColor = CouliColors.badgeBackground,
            contentColor = CouliColors.textInverse,
        )
    } else {
        Badge(
            modifier =
                modifier
                    .heightIn(min = CouliComponentTokens.countBadgeSize)
                    .border(CouliSizes.hairline, outline, RoundedCornerShape(CouliRadius.pill))
                    .clearAndSetSemantics { this.contentDescription = contentDescription },
            containerColor = CouliColors.badgeBackground,
            contentColor = CouliColors.textInverse,
        ) {
            Text(text = count.toString(), style = CouliTextStyles.captionSemibold)
        }
    }
}

/** 头像（组件清单 §3.5 `Avatar`）：圆形，56 / 32；无图时 background.muted 底 + 用户图标占位。读屏跳过（名称由旁边文字给）。 */
@Composable
fun Avatar(
    painter: Painter?,
    modifier: Modifier = Modifier,
    size: Dp = AVATAR_LARGE,
) {
    Box(
        modifier =
            modifier
                .size(size)
                .clip(CircleShape)
                .background(CouliColors.backgroundMuted)
                .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        if (painter != null) {
            Image(painter = painter, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        } else {
            Icon(CouliIcons.user, contentDescription = null, tint = CouliColors.textSecondary, modifier = Modifier.size(size / 2))
        }
    }
}

/** 图片加载状态。 */
enum class ImageLoadState {
    LOADING,
    SUCCESS,
    ERROR,
}

/**
 * 图片占位 / 商品图容器（组件清单 §3.5 `ImagePlaceholder`，GUIDE §6 图片容器规则）：background.placeholder 底 + border.subtle 描边，
 * 圆角 small；加载中转圈，失败显示 [icon]（不放文字：固定尺寸的图片框在 200% 字号下放不下说明），成功显示 [painter]。
 * [label] 是图的读屏名称（列表里的装饰图传 [decorative] = true 跳过）。
 */
@Composable
fun ImagePlaceholder(
    state: ImageLoadState,
    label: String,
    modifier: Modifier = Modifier,
    painter: Painter? = null,
    icon: ImageVector = CouliIcons.image,
    decorative: Boolean = false,
) {
    val shape = RoundedCornerShape(CouliRadius.small)
    Box(
        modifier =
            modifier
                .clip(shape)
                .background(CouliColors.backgroundPlaceholder)
                .border(CouliSizes.hairline, CouliColors.borderSubtle, shape)
                .clearAndSetSemantics { if (!decorative) contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        when {
            state == ImageLoadState.SUCCESS && painter != null -> {
                Image(painter = painter, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            }

            state == ImageLoadState.LOADING -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(CouliSpacing.space6),
                    color = CouliColors.textSecondary,
                    trackColor = CouliColors.borderSubtle,
                    strokeWidth = CouliSizes.hairline * 2,
                )
            }

            else -> {
                Icon(icon, contentDescription = null, tint = CouliColors.textSecondary, modifier = Modifier.size(CouliSizes.iconLarge))
            }
        }
    }
}

/** 时间线一步的状态。 */
enum class TimelineState {
    COMPLETED,
    CURRENT,
    FUTURE,
}

/** 时间线的一步（文字由调用方给）。 */
@Immutable
data class TimelineStep(
    val title: String,
    val state: TimelineState,
    val time: String? = null,
    /** 读屏状态词（「已完成」「进行中」「未开始」，字典给）；状态不只靠颜色。 */
    val stateLabel: String? = null,
)

/**
 * 进度时间线（组件清单 §3.5 `Timeline`）：竖排圆点 + 连线；已完成 / 当前 brand.primary 实心（当前加外环），未到 border.control 空心；
 * 标题 14，时间 12 次要色。每步读屏一项「标题，状态，时间」。
 */
@Composable
fun Timeline(
    steps: List<TimelineStep>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        steps.forEachIndexed { index, step ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .semantics(mergeDescendants = true) { step.stateLabel?.let { stateDescription = it } },
                horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TimelineDot(step.state)
                    if (index < steps.lastIndex) {
                        Box(
                            Modifier
                                .width(CouliSizes.hairline * 2)
                                .height(CouliSpacing.space8)
                                .background(
                                    if (steps[index + 1].state ==
                                        TimelineState.FUTURE
                                    ) {
                                        CouliColors.borderSubtle
                                    } else {
                                        CouliColors.brandPrimary
                                    },
                                ),
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f).padding(bottom = CouliSpacing.space2)) {
                    Text(
                        text = step.title,
                        style = if (step.state == TimelineState.CURRENT) CouliTextStyles.footnoteSemibold else CouliTypography.footnote,
                        color = if (step.state == TimelineState.FUTURE) CouliColors.textSecondary else CouliColors.textPrimary,
                    )
                    if (step.time != null) Text(text = step.time, style = CouliTextStyles.captionAmount, color = CouliColors.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun TimelineDot(state: TimelineState) {
    val dot = CouliSpacing.space3
    Box(
        modifier =
            Modifier
                .padding(top = CouliSpacing.space1)
                .size(dot)
                .then(
                    when (state) {
                        TimelineState.COMPLETED -> {
                            Modifier.clip(CircleShape).background(CouliColors.brandPrimary)
                        }

                        TimelineState.CURRENT -> {
                            Modifier
                                .border(CouliSizes.hairline * 2, CouliColors.brandSubtle, CircleShape)
                                .padding(CouliSizes.hairline * 2)
                                .clip(CircleShape)
                                .background(CouliColors.brandPrimary)
                        }

                        TimelineState.FUTURE -> {
                            Modifier
                                .clip(CircleShape)
                                .background(CouliColors.backgroundSurface)
                                .border(CouliSizes.hairline * 2, CouliColors.borderControl, CircleShape)
                        }
                    },
                ),
    )
}

/**
 * 折叠行（组件清单 §3.5 `DisclosureRow`）：整行按钮 + 右侧箭头（展开时朝上），读屏带展开 / 收起操作与状态；
 * 展开后内容在行下方（[content]）。
 */
@Composable
fun DisclosureRow(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    stateLabel: String? = null,
    content: @Composable () -> Unit = {},
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = CouliSizes.touchTarget)
                    .clickable(role = Role.Button, onClick = onToggle)
                    .semantics {
                        stateLabel?.let { stateDescription = it }
                        if (expanded) {
                            collapse {
                                onToggle()
                                true
                            }
                        } else {
                            expand {
                                onToggle()
                                true
                            }
                        }
                    }.padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = title, style = CouliTextStyles.bodySemibold, color = CouliColors.textPrimary, modifier = Modifier.weight(1f))
            Icon(
                CouliIcons.chevronDown,
                contentDescription = null,
                tint = CouliColors.textSecondary,
                modifier = Modifier.size(CouliSizes.iconMedium).rotate(if (expanded) HALF_TURN else 0f),
            )
        }
        if (expanded) {
            HorizontalDivider(thickness = CouliSizes.hairline, color = CouliColors.borderSubtle)
            content()
        }
    }
}

private const val HALF_TURN = 180f
private val AVATAR_LARGE = 56.dp
