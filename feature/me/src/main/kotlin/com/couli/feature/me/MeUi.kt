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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.ui.text.style.TextAlign
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
 */
@Composable
internal fun MeSubPage(
    tag: String,
    title: String,
    texts: MeTexts,
    onBack: (() -> Unit)?,
    overlay: @Composable () -> Unit = {},
    bottom: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (onBack == null) BackHandler {}
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(CouliColors.backgroundCanvas)
                .safeDrawingPadding()
                .testTag(tag),
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
                        .padding(CouliSpacing.space4),
                verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
                content = content,
            )
            if (bottom != null) {
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

/** 分组小标题（灰 12，读屏为标题）。 */
@Composable
internal fun GroupLabel(text: String) {
    Text(
        text = text,
        style = CouliTypography.caption,
        color = CouliColors.textSecondary,
        modifier = Modifier.padding(start = CouliSpacing.space1, top = CouliSpacing.space2).semantics { heading() },
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
 */
@Composable
internal fun NavRow(
    title: String,
    tag: String,
    value: String? = null,
    onClick: (() -> Unit)?,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = CouliSizes.touchTarget)
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
                .padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space3)
                .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        Text(text = title, style = CouliTypography.body, color = CouliColors.textPrimary, modifier = Modifier.weight(1f))
        if (value !=
            null
        ) {
            Text(text = value, style = CouliTypography.footnote, color = CouliColors.textSecondary, textAlign = TextAlign.End)
        }
        if (onClick != null) {
            Icon(
                CouliIcons.chevron,
                contentDescription = null,
                tint = CouliColors.textSecondary,
                modifier = Modifier.size(CouliSizes.iconSmall),
            )
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
    onChange: (Boolean) -> Unit,
) {
    val state = texts.text(if (checked) "me.switch.on" else "me.switch.off")
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
                }.padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space3)
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

/** 说明条：信息图标 + 文字，浅灰底圆角 12（RiskNotice、申诉、通知设置）。 */
@Composable
internal fun NoteStrip(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = CouliIcons.info,
    content: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(CouliColors.backgroundMuted, RoundedCornerShape(CouliRadius.button))
                .padding(CouliSpacing.space3),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
            Icon(icon, contentDescription = null, tint = CouliColors.textSecondary, modifier = Modifier.size(CouliSizes.iconMedium))
            Text(text = text, style = CouliTypography.footnote, color = CouliColors.textPrimary, modifier = Modifier.weight(1f))
        }
        content?.invoke()
    }
}

/** 轻提示（polite 播报一次）：顶部居中，2 秒后消费。 */
@Composable
internal fun MeToastHost(
    message: String?,
    id: Long?,
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
            icon = null,
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
 * assertive 播报一次。输入框另以 error 语义关联同一句。
 */
@Composable
internal fun ErrorFocusText(
    text: String,
    id: Long?,
    requester: FocusRequester,
    tag: String,
) {
    LaunchedEffect(id) {
        // 等本帧布局完成（目标已挂上焦点节点）再移焦
        withFrameNanos { }
        runCatching { requester.requestFocus() }
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
        Text(text = text, style = CouliTypography.body, color = CouliColors.brandPrimary)
    }
}
