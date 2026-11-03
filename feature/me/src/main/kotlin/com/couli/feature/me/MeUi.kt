package com.couli.feature.me

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliRadius
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliToast
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.NavBar
import com.couli.core.designsystem.couliCard
import com.couli.core.designsystem.couliFocusRing
import com.couli.core.designsystem.focusRingVisible
import com.couli.core.me.MeTexts
import kotlinx.coroutines.delay

/** 内容区最大宽度（大屏限宽居中，APP-05a 第 17 条，03 §10.4）。 */
internal val ME_MAX_WIDTH = 600.dp

/**
 * 二级页的骨架：返回 + 居中标题（NavBar）→ 可滚动内容（限宽 600 居中）。[onBack] 为 null 时不显示返回（RiskNoticeBanned）并吞掉系统返回。
 * contentPadding：内容区内边距（默认四周 16；设置一族传上 8，APP-12w-a S1）。bottomSurface：true 时底部槽是通栏白底固定操作栏（上沿 1px 分隔线，
 * 内容仍限宽 600，S19），白底向下铺到底部安全区（导航条 / 键盘）之下、按钮仍在安全区之上；默认 false 外观不变。
 */
@Composable
internal fun MeSubPage(
    tag: String,
    title: String,
    texts: MeTexts,
    onBack: (() -> Unit)?,
    overlay: @Composable () -> Unit = {},
    bottom: (@Composable () -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(CouliSpacing.space4),
    bottomSurface: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (onBack == null) BackHandler {}
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(CouliColors.backgroundCanvas)
                .then(
                    // 白底固定栏：底部安全区（导航条 / 键盘）留给栏自己铺白底（评审 r1 S2-5）；其余页面照旧四边 safeDrawing
                    if (bottom != null && bottomSurface) {
                        Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    } else {
                        Modifier.safeDrawingPadding()
                    },
                ).testTag(tag),
    ) {
        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (onBack != null) {
                NavBar(title = title, backLabel = texts.text("me.back"), onBack = onBack)
            } else {
                Text(
                    text = title,
                    style = CouliTextStyles.bodySemibold,
                    color = CouliColors.textPrimary,
                    textAlign = TextAlign.Center,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = CouliSizes.touchTarget)
                            .padding(vertical = CouliSpacing.space3)
                            .semantics { heading() },
                )
            }
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .widthIn(max = ME_MAX_WIDTH)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(contentPadding),
                verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
                content = content,
            )
            if (bottom != null && bottomSurface) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(CouliColors.backgroundSurface)
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    HorizontalDivider(color = CouliColors.borderSubtle)
                    Box(
                        modifier =
                            Modifier
                                .widthIn(max = ME_MAX_WIDTH)
                                .fillMaxWidth()
                                .padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space3),
                    ) { bottom() }
                }
            } else if (bottom != null) {
                Box(
                    modifier =
                        Modifier
                            .widthIn(max = ME_MAX_WIDTH)
                            .fillMaxWidth()
                            .padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space3),
                ) { bottom() }
            }
        }
        overlay()
    }
}

/** 分组小标题（灰 12，读屏为标题）。[top] 上边距（默认 8；设置页第一组传 0，APP-12w-a S1 / S2）。 */
@Composable
internal fun GroupLabel(
    text: String,
    top: Dp = CouliSpacing.space2,
) {
    Text(
        text = text,
        style = CouliTypography.caption,
        color = CouliColors.textSecondary,
        modifier = Modifier.padding(start = CouliSpacing.space1, top = top).semantics { heading() },
    )
}

/** 白卡片里的一组行，行间 1 像素分隔线。 */
@Composable
internal fun RowCard(rows: List<@Composable () -> Unit>) {
    Column(modifier = Modifier.fillMaxWidth().couliCard()) {
        rows.forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = CouliSpacing.space4), color = CouliColors.borderSubtle)
            row()
        }
    }
}

/**
 * 设置类的一行：标题（可换行）、右侧值与箭头；整行一个读屏节点、整行可点（min-height 44 + 上下内边距，命中 48dp）。[onClick] 为 null 时不可点、不显示箭头
 * （不画成置灰按钮）。
 * 可选参数（默认值 = 旧外观，APP-12w-a S4 / S5 / S14 / S26）：verticalPadding / horizontalPadding 行内边距；chevronSize 箭头尺寸；titleGap 标题与右侧间距；
 * valueGap 值与箭头间距；showChevron 是否画箭头（动作行不画，仍可点、读屏仍是按钮）；wrapValue 为 true 时标题优先完整、长值折行（200% 不把标题挤成一字一行）。
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
internal fun NavRow(
    title: String,
    tag: String,
    value: String? = null,
    onClick: (() -> Unit)?,
    verticalPadding: Dp = CouliSpacing.space3,
    horizontalPadding: Dp = CouliSpacing.space4,
    chevronSize: Dp = CouliSizes.iconSmall,
    titleGap: Dp = CouliSpacing.space2,
    valueGap: Dp = CouliSpacing.space2,
    showChevron: Boolean = onClick != null,
    wrapValue: Boolean = false,
    minHeight: Dp = CouliSizes.touchTarget,
) {
    val interaction = remember { MutableInteractionSource() }
    val split = wrapValue || titleGap != valueGap
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .then(
                    if (onClick != null) {
                        Modifier
                            .couliFocusRing(focusRingVisible(interaction), CouliRadius.card)
                            .clickable(
                                interactionSource = interaction,
                                indication = LocalIndication.current,
                                role = Role.Button,
                                onClick = onClick,
                            )
                    } else {
                        Modifier
                    },
                ).semantics(mergeDescendants = true) {}
                .padding(horizontal = horizontalPadding, vertical = verticalPadding)
                .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (split) valueGap else CouliSpacing.space2),
    ) {
        if (split) {
            TitleValue(title = title, value = value, gap = titleGap, wrapValue = wrapValue, modifier = Modifier.weight(1f))
        } else {
            Text(text = title, style = CouliTypography.body, color = CouliColors.textPrimary, modifier = Modifier.weight(1f))
            if (value !=
                null
            ) {
                Text(text = value, style = CouliTypography.footnote, color = CouliColors.textSecondary, textAlign = TextAlign.End)
            }
        }
        if (showChevron) {
            Icon(
                CouliIcons.chevron,
                contentDescription = null,
                tint = CouliColors.textSecondary,
                modifier = Modifier.size(chevronSize),
            )
        }
    }
}

/**
 * 标题 + 右侧值（[NavRow] 的设置一族路径）：放得下时标题占满剩余、值取本身宽度（与旧排法一致）；放不下且 [wrapValue] 时标题先取完整宽度（不超过一半），
 * 剩下给值折行右对齐，标题不被挤成一字一行（S26）。
 */
@Composable
private fun TitleValue(
    title: String,
    value: String?,
    gap: Dp,
    wrapValue: Boolean,
    modifier: Modifier = Modifier,
) {
    Layout(
        modifier = modifier,
        content = {
            Text(text = title, style = CouliTypography.body, color = CouliColors.textPrimary)
            if (value != null) {
                Text(text = value, style = CouliTypography.footnote, color = CouliColors.textSecondary, textAlign = TextAlign.End)
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val titleMeasurable = measurables[0]
        val valueMeasurable = measurables.getOrNull(1)
        if (valueMeasurable == null) {
            val placeable = titleMeasurable.measure(Constraints.fixedWidth(width))
            return@Layout layout(width, maxOf(placeable.height, constraints.minHeight)) { placeable.place(0, 0) }
        }
        val gapPx = gap.roundToPx()
        val available = (width - gapPx).coerceAtLeast(0)
        val valueWanted = valueMeasurable.maxIntrinsicWidth(Constraints.Infinity)
        val titleWanted = titleMeasurable.maxIntrinsicWidth(Constraints.Infinity)
        val titleWidth =
            when {
                titleWanted + valueWanted <= available || !wrapValue -> (available - valueWanted).coerceAtLeast(0)
                else -> minOf(titleWanted, maxOf(available / 2, available - valueWanted))
            }
        val valueWidth = (available - titleWidth).coerceAtLeast(0)
        val titlePlaceable = titleMeasurable.measure(Constraints.fixedWidth(titleWidth))
        val valuePlaceable = valueMeasurable.measure(Constraints(maxWidth = valueWidth))
        val height = maxOf(titlePlaceable.height, valuePlaceable.height, constraints.minHeight)
        layout(width, height) {
            titlePlaceable.place(0, (height - titlePlaceable.height) / 2)
            valuePlaceable.place(width - valuePlaceable.width, (height - valuePlaceable.height) / 2)
        }
    }
}

/** 标签 + 值的只读行（RiskNotice、申诉对象）。 */
@Composable
internal fun ValueRow(
    label: String,
    value: String,
    tag: String = "",
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = CouliSizes.touchTarget)
                .semantics(mergeDescendants = true) {}
                .padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space3)
                .then(if (tag.isNotEmpty()) Modifier.testTag(tag) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        Text(text = label, style = CouliTypography.body, color = CouliColors.textSecondary)
        Text(
            text = value,
            style = CouliTextStyles.bodySemibold,
            color = CouliColors.textPrimary,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 开关行（A11yFocusStyles）：整行一个节点、角色为开关，读作「{名称}，开关，已打开 / 已关闭」（名称 + 状态由系统按 Switch 角色读出）。[enabled] 为 false 时不可切换。
 * descBelow 为 true 时（NotifySettings，APP-12w-a S21）：标题与开关一行，说明通栏放在整行下方；仍是同一个开关节点。默认 false 外观不变。
 * verticalPadding：行上下内边距（默认 12 = 旧外观；隐私中心个性化卡传 16，APP-12w-b P3）。
 */
@Suppress("LongParameterList")
@Composable
internal fun SwitchRow(
    title: String,
    desc: String?,
    checked: Boolean,
    texts: MeTexts,
    tag: String,
    enabled: Boolean = true,
    descBelow: Boolean = false,
    verticalPadding: Dp = CouliSpacing.space3,
    onChange: (Boolean) -> Unit,
) {
    val state = texts.text(if (checked) "me.switch.on" else "me.switch.off")
    if (descBelow) {
        SwitchRowDescBelow(title, desc, checked, state, tag, enabled, verticalPadding, onChange)
        return
    }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = CouliSizes.touchTarget)
                .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
                .semantics(mergeDescendants = true) {
                    toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
                    contentDescription = title
                    stateDescription = state
                }.padding(horizontal = CouliSpacing.space4, vertical = verticalPadding)
                .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
            Text(text = title, style = CouliTypography.body, color = CouliColors.textPrimary)
            if (desc != null) Text(text = desc, style = CouliTypography.caption, color = CouliColors.textSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors =
                SwitchDefaults.colors(
                    checkedTrackColor = CouliColors.brandPrimary,
                    checkedThumbColor = CouliColors.backgroundSurface,
                    uncheckedTrackColor = CouliColors.backgroundMuted,
                    uncheckedThumbColor = CouliColors.backgroundSurface,
                    uncheckedBorderColor = CouliColors.borderControl,
                ),
        )
    }
}

/** 说明条的色调：MUTED 浅灰底 + 次文字色图标 + 主文字（旧外观）；WARNING 警告底 + 警告色图标与文字（NotifySettings，APP-12w-a S20）。 */
internal enum class NoteTone { MUTED, WARNING }

/**
 * 说明条：信息图标 + 文字，浅灰底圆角 12（RiskNotice、申诉、通知设置）。tone / textStyle 可选（默认 MUTED、footnote = 旧外观）。
 */
@Composable
internal fun NoteStrip(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = CouliIcons.info,
    tone: NoteTone = NoteTone.MUTED,
    textStyle: TextStyle = CouliTypography.footnote,
    content: (@Composable () -> Unit)? = null,
) {
    val warning = tone == NoteTone.WARNING
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(
                    if (warning) CouliColors.statusWarningBackground else CouliColors.backgroundMuted,
                    RoundedCornerShape(CouliRadius.button),
                ).padding(CouliSpacing.space3),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (warning) CouliColors.statusWarningText else CouliColors.textSecondary,
                modifier = Modifier.size(CouliSizes.iconMedium),
            )
            Text(
                text = text,
                style = textStyle,
                color = if (warning) CouliColors.statusWarningText else CouliColors.textPrimary,
                modifier = Modifier.weight(1f),
            )
        }
        content?.invoke()
    }
}

/** 轻提示（polite 播报一次）：顶部居中，2 秒后消费。[icon] 默认 null 不带图标（旧外观）；设置一族成功类传对勾（APP-12w-a G-S06）。 */
@Composable
internal fun MeToastHost(
    message: String?,
    id: Long?,
    icon: ImageVector? = null,
    onDone: () -> Unit,
) {
    if (message == null || id == null) return
    LaunchedEffect(id) {
        delay(TOAST_MILLIS)
        onDone()
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        CouliToast(
            message = message,
            icon = icon,
            announcementId = id,
            modifier = Modifier.padding(top = CouliSpacing.space12).testTag(MeTags.TOAST),
        )
    }
}

private const val TOAST_MILLIS = 2_000L

/** 页面生命周期：每次 ON_RESUME（含第一次）调 [onResume]（「我的」进入 Tab、回前台、从子页返回各取一次，G36）。 */
@Composable
internal fun OnResumed(
    key: Any?,
    onResume: () -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, key) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) onResume()
            }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

/** 页面可见性（ON_RESUME 可见、ON_PAUSE 被覆盖或退到后台）。 */
@Composable
internal fun OnVisible(
    key: Any?,
    onChange: (Boolean) -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, key) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> onChange(true)
                    Lifecycle.Event.ON_PAUSE -> onChange(false)
                    else -> Unit
                }
            }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

/**
 * 字段错误提示（Android R1-09，共性第 25 条）：真正可聚焦的目标（focusable，获得焦点时自动滚入可见区）；每条错误（[id]）出现后等这一帧布局完成再把焦点移过来，
 * assertive 播报一次。输入框另以 error 语义关联同一句。[icon] 给了时（昵称错误行，APP-12w-a S18）文字左侧画错误色图标、文字 14；默认 null 旧外观。
 */
@Composable
internal fun ErrorFocusText(
    text: String,
    id: Long?,
    requester: FocusRequester,
    tag: String,
    icon: ImageVector? = null,
) {
    LaunchedEffect(id) {
        // 等本帧布局完成（目标已挂上焦点节点）再移焦
        withFrameNanos { }
        runCatching { requester.requestFocus() }
    }
    if (icon != null) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
            ErrorLineIcon(icon)
            Text(
                text = text,
                style = CouliTypography.footnote,
                color = CouliColors.statusErrorText,
                modifier =
                    Modifier
                        .weight(1f)
                        .focusRequester(requester)
                        .focusable()
                        .semantics { liveRegion = LiveRegionMode.Assertive }
                        .testTag(tag),
            )
        }
        return
    }
    Text(
        text = text,
        style = CouliTypography.footnote,
        color = CouliColors.statusErrorText,
        modifier =
            Modifier
                .fillMaxWidth()
                .focusRequester(requester)
                .focusable()
                .semantics { liveRegion = LiveRegionMode.Assertive }
                .testTag(tag),
    )
}

/** 文字链接按钮（「联系客服 | 退出登录」）：品牌色、命中 48dp。 */
@Composable
internal fun LinkText(
    text: String,
    tag: String,
    style: TextStyle = CouliTypography.body,
    color: Color = CouliColors.brandPrimary,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier =
            Modifier
                .heightIn(min = CouliSizes.touchTarget)
                .couliFocusRing(focusRingVisible(interaction), CouliRadius.small)
                .clickable(interactionSource = interaction, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
                .padding(horizontal = CouliSpacing.space2)
                .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = style, color = color)
    }
}

/** 错误行左侧的图标（16，错误色，与 14 号文字首行居中）。 */
@Composable
internal fun ErrorLineIcon(icon: ImageVector) {
    Icon(
        icon,
        contentDescription = null,
        tint = CouliColors.statusErrorText,
        modifier = Modifier.padding(top = CouliSpacing.space1 / 2).size(CouliSizes.iconSmall),
    )
}

/** [SwitchRow] 的说明下置写法：标题 + 开关一行（min 48），说明通栏在下；整块一个开关节点（名称 + 状态 + 说明）。 */
@Suppress("LongParameterList")
@Composable
private fun SwitchRowDescBelow(
    title: String,
    desc: String?,
    checked: Boolean,
    state: String,
    tag: String,
    enabled: Boolean,
    verticalPadding: Dp,
    onChange: (Boolean) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
                .semantics(mergeDescendants = true) {
                    toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
                    contentDescription = title
                    stateDescription = state
                }.padding(horizontal = CouliSpacing.space4, vertical = verticalPadding)
                .testTag(tag),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = CouliSizes.visualTarget),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
        ) {
            Text(text = title, style = CouliTypography.body, color = CouliColors.textPrimary, modifier = Modifier.weight(1f))
            MeSwitch(checked = checked, enabled = enabled)
        }
        if (desc != null) Text(text = desc, style = CouliTypography.caption, color = CouliColors.textSecondary)
    }
}

@Composable
private fun MeSwitch(
    checked: Boolean,
    enabled: Boolean,
) {
    Switch(
        checked = checked,
        onCheckedChange = null,
        enabled = enabled,
        colors =
            SwitchDefaults.colors(
                checkedTrackColor = CouliColors.brandPrimary,
                checkedThumbColor = CouliColors.backgroundSurface,
                uncheckedTrackColor = CouliColors.backgroundMuted,
                uncheckedThumbColor = CouliColors.backgroundSurface,
                uncheckedBorderColor = CouliColors.borderControl,
            ),
    )
}

/**
 * 文字链接（游客「去登录」、通知「去设置」，APP-12w-a S10 / S20）：布局尺寸 = 文字本身（不撑高所在行 / 提示条），可点区域向四周扩到至少 48dp 并居中于文字
 * （命中区与视觉尺寸分开）；读屏为按钮，名称为文字。600、下划线，颜色由调用方给。
 */
@Composable
internal fun CompactTextLink(
    text: String,
    color: Color,
    tag: String,
    modifier: Modifier = Modifier,
    style: TextStyle = CouliTextStyles.footnoteSemibold,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val measured = remember { IntArray(2) }
    Box(
        modifier =
            modifier
                .layout { measurable, constraints ->
                    // 外层：按文字尺寸占位，把内层（≥ 48 的可点盒）居中摆在文字上
                    val hit = CouliSizes.touchTarget.roundToPx()
                    val inner =
                        measurable.measure(
                            Constraints(maxWidth = maxOf(constraints.maxWidth, hit), maxHeight = maxOf(constraints.maxHeight, hit)),
                        )
                    val width = measured[0].coerceIn(constraints.minWidth, constraints.maxWidth)
                    val height = measured[1].coerceIn(constraints.minHeight, constraints.maxHeight)
                    layout(width, height) { inner.place((width - inner.width) / 2, (height - inner.height) / 2) }
                }.couliFocusRing(focusRingVisible(interaction), CouliRadius.small)
                .clickable(interactionSource = interaction, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
                .testTag(tag)
                .layout { measurable, constraints ->
                    // 内层：量出文字尺寸记给外层，自身扩到至少 48 × 48
                    val label = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                    measured[0] = label.width
                    measured[1] = label.height
                    val hit = CouliSizes.touchTarget.roundToPx()
                    val width = maxOf(label.width, hit)
                    val height = maxOf(label.height, hit)
                    layout(width, height) { label.place((width - label.width) / 2, (height - label.height) / 2) }
                },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = style.copy(textDecoration = TextDecoration.Underline), color = color)
    }
}
