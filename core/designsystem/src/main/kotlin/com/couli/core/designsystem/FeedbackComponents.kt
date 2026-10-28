package com.couli.core.designsystem

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.delay

/** Agent 卡片的条件标记（BR-AI-24）：符合条件 / 已放宽 / 规格待确认；文字取字典。 */
enum class MatchBadgeKind {
    MATCHED,
    RELAXED,
    UNCONFIRMED,
}

/** 条件标记：状态不只靠颜色（带图标）。 */
@Composable
fun MatchBadge(
    text: String,
    kind: MatchBadgeKind,
    modifier: Modifier = Modifier,
) {
    when (kind) {
        MatchBadgeKind.MATCHED -> CouliTag(text = text, tone = CouliTagTone.SUCCESS, icon = CouliIcons.check, modifier = modifier)
        MatchBadgeKind.RELAXED -> CouliTag(text = text, tone = CouliTagTone.NEUTRAL, icon = CouliIcons.info, modifier = modifier)
        MatchBadgeKind.UNCONFIRMED -> CouliTag(text = text, tone = CouliTagTone.WARNING, icon = CouliIcons.alert, modifier = modifier)
    }
}

/** AI 内容标识（文字取 texts.ai_label，BR-TEXT-16）。 */
@Composable
fun AiLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    CouliTag(text = text, tone = CouliTagTone.NEUTRAL, modifier = modifier)
}

/** 空态 / 错误态的操作按钮。[enabled] 为 false 时按钮置灰不可点（如 42901 禁用期内的【重试】）。 */
data class StateAction(
    val label: String,
    val enabled: Boolean = true,
    /** true：实心主按钮（如订单空态【去首页逛逛】，OrdersEmpty）；缺省 false 为描边次按钮，保持原外观。 */
    val primary: Boolean = false,
    val onClick: () -> Unit,
)

/** 空态（GUIDE §3）：96 占位块（32 图标）→ 一句说明 17/600 → 一句辅助 14 → 一个按钮。 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: ImageVector = CouliIcons.search,
    action: StateAction? = null,
) {
    StateLayout(title = title, message = message, icon = icon, action = action, modifier = modifier)
}

/**
 * 错误态：同空态布局，图标为警示三角。出现时立即播报整句（adaptation-a11y-spec §10），播报读完后读屏焦点移到【重试】
 * （F-AND-06，[ErrorStateFocus]）；同一内容重绘不重复，换了内容重新播报、重新移焦。[actionModifier] 挂在按钮上（页面的测试标记等，默认无）。
 */
@Composable
fun ErrorState(
    title: String,
    retry: StateAction,
    modifier: Modifier = Modifier,
    message: String? = null,
    actionModifier: Modifier = Modifier,
) {
    val retryFocus = remember { FocusRequester() }
    ErrorAnnouncement(ErrorStateFocus.announcement(title, message), retryFocus)
    StateLayout(
        title = title,
        message = message,
        icon = CouliIcons.alert,
        action = retry,
        modifier = modifier,
        actionModifier = Modifier.couliFocusTarget(retryFocus).then(actionModifier),
    )
}

/**
 * 加载失败的播报与移焦（adaptation-a11y-spec §10「加载失败：立即读，焦点到【重试】，assertive」；03 §10.5）：出现时立即播报
 * [announcement]，读完后把焦点移到 [actionFocus]（挂 [couliFocusTarget] 的【重试】等动作）；没有动作时 [actionFocus] 给 null，只播报。
 * 同一句重绘不重复，换了内容重新播报、重新移焦。[ErrorState] 与页面自己的错误块（搜索列表底部的翻页失败等）共用。
 */
@Composable
fun ErrorAnnouncement(
    announcement: String,
    actionFocus: FocusRequester?,
) {
    val announcer = rememberCouliAnnouncer()
    LaunchedEffect(announcement, actionFocus) {
        announcer.announce(announcement)
        if (actionFocus == null) return@LaunchedEffect
        delay(ErrorStateFocus.focusDelayMillis(announcement))
        actionFocus.requestFocusSafely()
    }
}

@Composable
private fun StateLayout(
    title: String,
    message: String?,
    icon: ImageVector,
    action: StateAction?,
    modifier: Modifier,
    actionModifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = CouliSpacing.space6, horizontal = CouliSpacing.space4),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        Box(
            modifier =
                Modifier
                    .size(CouliSizes.emptyIllustration)
                    .clip(RoundedCornerShape(CouliRadius.card))
                    .background(CouliColors.backgroundMuted),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = CouliColors.textSecondary, modifier = Modifier.size(CouliSpacing.space8))
        }
        Text(
            text = title,
            style = CouliTextStyles.bodySemibold,
            color = CouliColors.textPrimary,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .padding(top = CouliSpacing.space2)
                    .semantics { heading() },
        )
        if (message != null) {
            Text(text = message, style = CouliTypography.footnote, color = CouliColors.textSecondary, textAlign = TextAlign.Center)
        }
        if (action != null) {
            CouliButton(
                text = action.label,
                onClick = action.onClick,
                variant = if (action.primary) CouliButtonVariant.PRIMARY else CouliButtonVariant.SECONDARY,
                enabled = action.enabled,
                modifier = Modifier.padding(top = CouliSpacing.space2).then(actionModifier),
            )
        }
    }
}

/** 骨架块（GUIDE §3：#EFEAE2 圆角 8，按真实布局摆）。 */
@Composable
fun Skeleton(modifier: Modifier = Modifier) {
    Box(modifier = modifier.clip(RoundedCornerShape(CouliRadius.small)).background(CouliColors.backgroundMuted))
}

/** 列表商品卡的骨架（GUIDE §3「搜索结果加载骨架」）：图片块 + 三条横块 + 底行按钮块；[description] 为读屏「加载中」。 */
@Composable
fun SkeletonProductCard(
    description: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .couliCard()
                .padding(CouliSpacing.space3)
                .semantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3)) {
            Skeleton(Modifier.size(CouliSizes.listThumbnail))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
                Skeleton(Modifier.fillMaxWidth().height(CouliSpacing.space4))
                Skeleton(Modifier.fillMaxWidth(SKELETON_SECOND_LINE).height(CouliSpacing.space4))
                Skeleton(Modifier.fillMaxWidth(SKELETON_PRICE_LINE).height(CouliSpacing.space6))
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Skeleton(Modifier.width(CouliSizes.listThumbnail - CouliSpacing.space2).height(CouliSpacing.space8 + CouliSpacing.space1))
        }
    }
}

/**
 * 轻提示（页顶）：白底浮层 + 文字，出现时播报（adaptation-a11y-spec §10）。显示时机由调用方控制。
 * 内容变化重新播报；同一句再次提示时调用方换一个 [announcementId]（如递增计数）也重新播报（F-AND-05）。
 */
@Composable
fun CouliToast(
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = CouliIcons.check,
    announcementId: Any? = null,
    /** 图标色（默认成功色，旧调用外观不变；购买流程按提示类别传次文字色 / 警告色，APP-06v B4；钱包「已经处理完成」用次文字色，APP-09v-c W62）。 */
    iconTint: Color = CouliColors.statusSuccessText,
) {
    val announcer = rememberCouliAnnouncer()
    LaunchedEffect(message, announcementId) { announcer.announce(message) }
    val shape = RoundedCornerShape(CouliRadius.button)
    Row(
        modifier =
            modifier
                .shadow(CouliSpacing.space2, shape, ambientColor = CouliColors.textPrimary, spotColor = CouliColors.textPrimary)
                .clip(shape)
                .background(CouliColors.backgroundSurface)
                .padding(CouliSpacing.space3),
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(CouliSizes.iconMedium))
        }
        Text(text = message, style = CouliTypography.footnote, color = CouliColors.textPrimary)
    }
}

/**
 * 居中弹窗（GUIDE §3 / §6）：白底圆角 16、内边距 20；标题 17/600 → 正文 14 → 按钮「一主一次」：放得下并排等宽（次左主右），
 * 放不下整块换行、各占通栏、主按钮在上。最大高 = 可视高度 − 2 × (安全区 + 24)；标题与按钮行固定，正文在中间可滚动
 * （adaptation-a11y-spec §4，R2-03）。模态：系统 Dialog 窗口，底层不可达；打开时焦点进标题，关闭后回到 [restoreFocusTo]
 * （触发控件挂 [couliFocusTarget]，F-AND-05）。[dismissible] 为 false 时返回键与点外部都不关闭（如首启隐私弹窗）。
 */
@Composable
fun CouliDialog(
    title: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
    /** 弹窗内容根节点的修饰（如测试标记）。 */
    modifier: Modifier = Modifier,
    message: String? = null,
    dismissText: String? = null,
    onDismiss: () -> Unit = onDismissRequest,
    dismissible: Boolean = true,
    restoreFocusTo: FocusRequester? = null,
    body: (@Composable () -> Unit)? = null,
    /** 主按钮可点（如 42901 限流期间置灰，到期恢复）。 */
    confirmEnabled: Boolean = true,
    /** 点遮罩是否关闭（APP-12：撤回同意、退出登录的确认框点遮罩不执行任何操作、也不关闭；系统返回仍按 [dismissible] 走 [onDismissRequest]）。 */
    dismissOnClickOutside: Boolean = dismissible,
    /** 次按钮、主按钮的测试标记（撤回确认框的【确认撤回】是次按钮）。 */
    dismissTag: String? = null,
    confirmTag: String? = null,
    /** 标题前的装饰图标（ProductAuthBlocked 警告、ProductPriceUnconfirmed 提示，G-D08）；默认 null 不画，旧调用外观不变。 */
    titleIcon: ImageVector? = null,
    /** [titleIcon] 的颜色（默认警告色）。 */
    titleIconTint: Color = CouliColors.statusWarningText,
    /** 右上关闭的读屏名；给了才画，点击 = [onDismissRequest]（ProductPriceCompareNoRebate-v2，G-D10）。默认 null 不画。 */
    closeLabel: String? = null,
    /** 标题对齐（APP-09v-c W60 / W61：钱包 44001 与放弃确认弹窗居中）；默认靠左，旧调用不变。仅在无标题图标、无关闭时生效。 */
    titleAlign: TextAlign = TextAlign.Start,
    /**
     * 遮罩色：给了（如 [CouliColors.overlayScrim]）就关掉系统窗口 dim、整屏铺这层颜色（购买浮层 APP-06v-b、钱包 44001 / 放弃确认 APP-09v-c）；
     * 默认 [Color.Unspecified] 沿用系统 dim，旧调用外观不变。
     */
    scrim: Color = Color.Unspecified,
) {
    RestoreFocusOnDispose(restoreFocusTo)
    val fontScale = callerFontScale()
    Dialog(
        onDismissRequest = onDismissRequest,
        properties =
            DialogProperties(
                dismissOnBackPress = dismissible,
                dismissOnClickOutside = dismissOnClickOutside,
                usePlatformDefaultWidth = false,
            ),
    ) {
        if (scrim.isSpecified) CouliDialogScrim(scrim)
        // 可视区域（扣除安全区）四周各留 24：弹窗高度不会超过窗口，正文在弹窗内滚动
        WindowFontScale(fontScale) {
            Box(
                modifier = Modifier.safeDrawingPadding().padding(CouliSpacing.space6),
                contentAlignment = Alignment.Center,
            ) {
                val titleFocus = remember { FocusRequester() }
                FocusOnAppear(titleFocus)
                CouliDialogContent(
                    title = title,
                    message = message,
                    confirmText = confirmText,
                    onConfirm = onConfirm,
                    modifier = modifier,
                    dismissText = dismissText,
                    onDismiss = onDismiss,
                    titleFocus = titleFocus,
                    body = body,
                    confirmEnabled = confirmEnabled,
                    dismissTag = dismissTag,
                    confirmTag = confirmTag,
                    titleIcon = titleIcon,
                    titleIconTint = titleIconTint,
                    closeLabel = closeLabel,
                    onClose = onDismissRequest,
                    titleAlign = titleAlign,
                )
            }
        }
    }
}

/**
 * 用令牌遮罩代替系统窗口 dim（首页弹窗同一做法）：在系统 Dialog 的内容里调用，关掉 FLAG_DIM_BEHIND、窗口铺满屏幕、窗口底色设为 [color]。
 * 内容位置与点内容以外关闭的行为不变。
 */
@Composable
fun CouliDialogScrim(color: Color) {
    val view = LocalView.current
    val argb = color.toArgb()
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window?.apply {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            decorView.setBackgroundColor(argb)
        }
    }
}

/**
 * 弹窗内容（单独暴露，便于目录页与快照测试直接排版）。父布局给了有限高度时，标题与按钮行固定、正文区滚动；
 * 高度不受限（如放在可滚动的目录页里）时按内容自然高度排版。
 */
@Composable
fun CouliDialogContent(
    title: String,
    confirmText: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    dismissText: String? = null,
    onDismiss: () -> Unit = {},
    titleFocus: FocusRequester? = null,
    body: (@Composable () -> Unit)? = null,
    confirmEnabled: Boolean = true,
    dismissTag: String? = null,
    confirmTag: String? = null,
    /** 标题前的装饰图标；默认 null 不画（见 [CouliDialog]）。 */
    titleIcon: ImageVector? = null,
    titleIconTint: Color = CouliColors.statusWarningText,
    /** 右上关闭：[closeLabel] 与 [onClose] 都给了才画；默认不画。 */
    closeLabel: String? = null,
    onClose: (() -> Unit)? = null,
    /** 标题对齐：默认靠左（旧外观）；非靠左时标题占满宽度再按对齐排版（仅无图标、无关闭时）。 */
    titleAlign: TextAlign = TextAlign.Start,
) {
    ScrollBodyLayout(
        modifier =
            modifier
                .widthIn(max = CouliSizes.listThumbnail * DIALOG_MAX_WIDTH_FACTOR)
                .couliCard()
                .padding(CouliSpacing.space5)
                .semantics { paneTitle = title },
        gap = CouliSpacing.space3,
        header = {
            val close = onClose?.takeIf { closeLabel != null }
            if (titleIcon == null && close == null) {
                val titleWidth = if (titleAlign == TextAlign.Start) Modifier else Modifier.fillMaxWidth()
                Text(
                    text = title,
                    style = CouliTextStyles.bodySemibold,
                    color = CouliColors.textPrimary,
                    textAlign = titleAlign,
                    modifier = titleWidth.modalTitle(titleFocus),
                )
            } else {
                DialogTitleRow(title, titleFocus, titleIcon, titleIconTint, closeLabel, close)
            }
        },
        // 正文：可交互的正文（如带协议链接的确认框，链接各自可点、读屏可单独聚焦）用 [body]，否则是纯文字 [message]
        body =
            body ?: message?.let {
                { Text(text = it, style = CouliTypography.footnote, color = CouliColors.textPrimary) }
            },
        footer = {
            DialogButtons(
                primary = {
                    CouliButton(
                        text = confirmText,
                        onClick = onConfirm,
                        enabled = confirmEnabled,
                        modifier = Modifier.fillMaxWidth().then(confirmTag?.let { Modifier.testTag(it) } ?: Modifier),
                    )
                },
                secondary =
                    if (dismissText != null) {
                        {
                            CouliButton(
                                text = dismissText,
                                onClick = onDismiss,
                                variant = CouliButtonVariant.SECONDARY,
                                modifier = Modifier.fillMaxWidth().then(dismissTag?.let { Modifier.testTag(it) } ?: Modifier),
                            )
                        }
                    } else {
                        null
                    },
            )
        },
    )
}

/**
 * 带前置图标 / 右上关闭的弹窗标题行。图标 20 装饰（不进读屏），与标题首行垂直居中；有关闭时标题在两侧等宽留白之间居中，关闭的视觉 24、
 * 命中区 48 向外溢出到弹窗内边距里，不把标题行撑高（画板 ProductPriceCompareNoRebate-v2）。读屏顺序：标题 → 关闭。
 */
@Composable
private fun DialogTitleRow(
    title: String,
    titleFocus: FocusRequester?,
    icon: ImageVector?,
    iconTint: Color,
    closeLabel: String?,
    onClose: (() -> Unit)?,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2), verticalAlignment = Alignment.Top) {
        if (onClose != null) Spacer(Modifier.size(CouliSizes.iconLarge))
        if (icon != null) {
            val line = with(LocalDensity.current) { CouliTextStyles.bodySemibold.lineHeight.toDp() }
            Box(modifier = Modifier.height(line), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(CouliSizes.iconMedium))
            }
        }
        Text(
            text = title,
            style = CouliTextStyles.bodySemibold,
            color = CouliColors.textPrimary,
            textAlign = if (onClose != null) TextAlign.Center else TextAlign.Start,
            modifier = Modifier.weight(1f).modalTitle(titleFocus),
        )
        if (onClose != null && closeLabel != null) {
            Box(modifier = Modifier.size(CouliSizes.iconLarge), contentAlignment = Alignment.Center) {
                CouliIconButton(
                    icon = CouliIcons.close,
                    label = closeLabel,
                    onClick = onClose,
                    tint = CouliColors.textSecondary,
                    modifier = Modifier.wrapContentSize(unbounded = true),
                )
            }
        }
    }
}

/**
 * 头部 + 可滚动正文 + 固定底部（弹窗、半屏共用，adaptation-a11y-spec §4）：先量头部与底部，正文只拿剩下的高度并在其中纵向滚动，
 * 底部操作始终可见；正文可滚时底部上方加一条分隔线（[dividerWhenScrollable]）。父布局高度不受限时不滚动、按自然高度排版。
 */
@Composable
internal fun ScrollBodyLayout(
    gap: Dp,
    header: @Composable () -> Unit,
    body: (@Composable () -> Unit)?,
    footer: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    dividerWhenScrollable: Boolean = false,
) {
    BoxWithConstraints(modifier = modifier) {
        val bounded = constraints.hasBoundedHeight
        val scroll = rememberScrollState()
        val scrollable = bounded && scroll.maxValue > 0
        val parts: List<@Composable () -> Unit> =
            listOf(
                header,
                { if (body != null) Box(if (bounded) Modifier.verticalScroll(scroll) else Modifier) { body() } },
                {
                    if (footer != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                            if (dividerWhenScrollable && scrollable) {
                                Box(Modifier.fillMaxWidth().height(CouliSizes.hairline).background(CouliColors.borderSubtle))
                            }
                            footer()
                        }
                    }
                },
            )
        Layout(contents = parts) { (headerMs, bodyMs, footerMs), c ->
            val gapPx = gap.roundToPx()
            val loose = Constraints(maxWidth = c.maxWidth)
            val headerP = headerMs.map { it.measure(loose) }
            val footerP = footerMs.map { it.measure(loose) }
            val fixed = headerP.sumOf { it.height } + footerP.sumOf { it.height }
            val hasBody = bodyMs.isNotEmpty() && body != null
            val gaps = gapPx * (listOf(headerP.isNotEmpty(), hasBody, footerP.isNotEmpty()).count { it } - 1).coerceAtLeast(0)
            val bodyMax = if (c.hasBoundedHeight) (c.maxHeight - fixed - gaps).coerceAtLeast(0) else Constraints.Infinity
            val bodyP = if (hasBody) bodyMs.map { it.measure(Constraints(maxWidth = c.maxWidth, maxHeight = bodyMax)) } else emptyList()
            val all = headerP + bodyP + footerP
            val width = (all.maxOfOrNull { it.width } ?: 0).coerceIn(c.minWidth, c.maxWidth)
            val height = (fixed + bodyP.sumOf { it.height } + gaps).coerceIn(c.minHeight, c.maxHeight)
            layout(width, height) {
                var y = 0
                listOf(headerP, bodyP, footerP).filter { it.isNotEmpty() }.forEach { group ->
                    group.forEach {
                        it.place(0, y)
                        y += it.height
                    }
                    y += gapPx
                }
            }
        }
    }
}

/**
 * 一主一次的按钮行（弹窗、半屏共用，GUIDE §6）：放得下则等宽并排、次左主右；放不下（大字号 / 窄屏）纵排、主在上。半屏底部操作（如剪贴板用途说明
 * HomeClipboardConsent-v3）经 [CouliSheet] 的 actions 槽用它，与 [CouliDialog] 同一排法。
 */
@Composable
fun CouliButtonPair(
    primary: @Composable () -> Unit,
    secondary: @Composable () -> Unit,
) = DialogButtons(primary = primary, secondary = secondary)

/** 一主一次：并排放得下（两者都不折行）则等宽并排，次左主右；否则纵排，主在上（GUIDE §6）。 */
@Composable
private fun DialogButtons(
    primary: @Composable () -> Unit,
    secondary: (@Composable () -> Unit)?,
) {
    if (secondary == null) {
        primary()
        return
    }
    val contents: List<@Composable () -> Unit> = listOf(primary, secondary)
    Layout(contents = contents) { (primaryMeasurables, secondaryMeasurables), constraints ->
        val gap = CouliSpacing.space3.roundToPx()
        val p = primaryMeasurables.first()
        val s = secondaryMeasurables.first()
        val natural = maxOf(p.maxIntrinsicWidth(Constraints.Infinity), s.maxIntrinsicWidth(Constraints.Infinity))
        val sideBySide = natural * 2 + gap <= constraints.maxWidth
        if (sideBySide) {
            val each = (constraints.maxWidth - gap) / 2
            val fixed = Constraints.fixedWidth(each)
            val pp = p.measure(fixed)
            val sp = s.measure(fixed)
            val height = maxOf(pp.height, sp.height)
            layout(constraints.maxWidth, height) {
                sp.place(0, 0)
                pp.place(each + gap, 0)
            }
        } else {
            val full = Constraints.fixedWidth(constraints.maxWidth)
            val pp = p.measure(full)
            val sp = s.measure(full)
            layout(constraints.maxWidth, pp.height + gap + sp.height) {
                pp.place(0, 0)
                sp.place(0, pp.height + gap)
            }
        }
    }
}

/**
 * 底部半屏（GUIDE §3）：白底、上两角 24、顶部 36×4 把手；标题 → 正文 → 操作。头部与底部操作 [actions] 固定，只有正文滚动，
 * 正文可滚时操作区上方加分隔线（adaptation-a11y-spec §4）。模态由 ModalBottomSheet 提供（底层不可达）；
 * 打开时焦点进标题，关闭（按钮、遮罩、返回、下拉都一样）后回到 [restoreFocusTo]（F-AND-05）。
 *
 * 保护型（资金与验证类：提现二次验证、实名、换收款账号；组件清单 §3.6）传 [dismissOnSwipe] = false、[dismissOnClickOutside] = false：
 * 不能下滑、点遮罩关闭，保留右上关闭（[closeLabel]）与系统返回（返回先关半屏，adaptation-a11y-spec §4）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CouliSheet(
    title: String,
    onDismissRequest: () -> Unit,
    /** 半屏内容区（把手下方、通栏宽）的修饰（如测试标记）；默认不加。 */
    modifier: Modifier = Modifier,
    actions: (@Composable () -> Unit)? = null,
    restoreFocusTo: FocusRequester? = null,
    closeLabel: String? = null,
    dismissOnSwipe: Boolean = true,
    dismissOnClickOutside: Boolean = true,
    /** false：不画可见标题（如识别到单张商品，HomeClipboardCard-v3），[title] 仍作读屏 paneTitle，打开焦点落到右上关闭（APP-11v）。 */
    showTitle: Boolean = true,
    /** 标题字号（默认 17/600；用途说明半屏 HomeClipboardConsent-v3 用 22/600）。 */
    titleStyle: TextStyle = CouliTextStyles.bodySemibold,
    /** 标题前的装饰槽（授权半屏的 32 平台图标，G-D14）；默认 null 不画，旧调用外观不变。读屏仍只读标题文字。 */
    titleLeading: (@Composable () -> Unit)? = null,
    /**
     * true：居中头部（客服半屏 CsOpenFailed-v2，G-P05）——[titleLeading] 画在标题上方（间距 12）、两者水平居中；关闭仍在右侧、与标题同一行、
     * 读屏在标题之后。默认 false 外观不变。
     */
    headerCentered: Boolean = false,
    content: @Composable () -> Unit,
) {
    RestoreFocusOnDispose(restoreFocusTo)
    val fontScale = callerFontScale()
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = dismissOnSwipe,
        shape = RoundedCornerShape(topStart = CouliRadius.panel, topEnd = CouliRadius.panel),
        containerColor = CouliColors.backgroundSurface,
        scrimColor = CouliColors.overlayScrim,
        dragHandle = { SheetHandle() },
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = true, shouldDismissOnClickOutside = dismissOnClickOutside),
    ) {
        WindowFontScale(fontScale) {
            val titleFocus = remember { FocusRequester() }
            FocusOnAppear(titleFocus)
            CouliSheetBody(
                title = title,
                actions = actions,
                titleFocus = titleFocus,
                onClose = closeLabel?.let { { onDismissRequest() } },
                closeLabel = closeLabel,
                showTitle = showTitle,
                titleStyle = titleStyle,
                titleLeading = titleLeading,
                headerCentered = headerCentered,
                modifier = modifier,
                content = content,
            )
        }
    }
}

/** 半屏内容（标题 + 可滚动正文 + 固定操作），单独暴露给目录页与快照测试。 */
@Composable
fun CouliSheetBody(
    title: String,
    modifier: Modifier = Modifier,
    actions: (@Composable () -> Unit)? = null,
    titleFocus: FocusRequester? = null,
    onClose: (() -> Unit)? = null,
    closeLabel: String? = null,
    showTitle: Boolean = true,
    titleStyle: TextStyle = CouliTextStyles.bodySemibold,
    /** 标题前的装饰槽；默认 null 不画（见 [CouliSheet]）。 */
    titleLeading: (@Composable () -> Unit)? = null,
    /** 居中头部（见 [CouliSheet]）；默认 false 外观不变。 */
    headerCentered: Boolean = false,
    content: @Composable () -> Unit,
) {
    ScrollBodyLayout(
        modifier =
            modifier
                .fillMaxWidth()
                .then(if (showTitle) Modifier else Modifier.semantics { paneTitle = title })
                .padding(start = CouliSpacing.space4, end = CouliSpacing.space4, bottom = CouliSpacing.space6),
        gap = CouliSpacing.space4,
        dividerWhenScrollable = true,
        header = {
            // 标题 → 关闭 → 正文 → 操作（adaptation-a11y-spec §8：关闭在 DOM 里排在标题之后，视觉在右上角）
            when {
                headerCentered && showTitle -> {
                    CenteredSheetHeader(title, titleStyle, titleFocus, titleLeading, onClose?.takeIf { closeLabel != null }, closeLabel)
                }

                !showTitle -> {
                    // 无可见标题：只留右上关闭（不留空标题行），打开焦点落到关闭；读屏名由 paneTitle 给
                    if (onClose != null && closeLabel != null) {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
                            CouliIconButton(
                                icon = CouliIcons.close,
                                label = closeLabel,
                                onClick = onClose,
                                tint = CouliColors.textSecondary,
                                modifier = Modifier.modalTitle(titleFocus, heading = false),
                            )
                        }
                    }
                }

                onClose != null && closeLabel != null -> {
                    Row(verticalAlignment = Alignment.Top) {
                        if (titleLeading != null) {
                            Box(Modifier.padding(top = CouliSpacing.space3, end = CouliSpacing.space3)) { titleLeading() }
                        }
                        Text(
                            text = title,
                            style = titleStyle,
                            color = CouliColors.textPrimary,
                            modifier = Modifier.weight(1f).padding(top = CouliSpacing.space3).modalTitle(titleFocus),
                        )
                        CouliIconButton(icon = CouliIcons.close, label = closeLabel, onClick = onClose, tint = CouliColors.textSecondary)
                    }
                }

                titleLeading != null -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3), verticalAlignment = Alignment.Top) {
                        titleLeading()
                        Text(
                            text = title,
                            style = titleStyle,
                            color = CouliColors.textPrimary,
                            modifier = Modifier.weight(1f).modalTitle(titleFocus),
                        )
                    }
                }

                else -> {
                    Text(
                        text = title,
                        style = titleStyle,
                        color = CouliColors.textPrimary,
                        modifier = Modifier.modalTitle(titleFocus),
                    )
                }
            }
        },
        body = { Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space4)) { content() } },
        footer = actions,
    )
}

/**
 * 居中半屏头部（[CouliSheet] headerCentered = true）：[leading] → 12 → 标题（居中，左右各让出关闭的 48 以保持居中）；关闭垂直居中于标题行、
 * 不撑高该行（命中 48 向上下外扩），在标题之后组合（读屏 标题 → 关闭）。
 */
@Suppress("LongParameterList")
@Composable
private fun CenteredSheetHeader(
    title: String,
    titleStyle: TextStyle,
    titleFocus: FocusRequester?,
    leading: (@Composable () -> Unit)?,
    onClose: (() -> Unit)?,
    closeLabel: String?,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        leading?.invoke()
        Box(modifier = Modifier.fillMaxWidth()) {
            val side = if (onClose != null) CouliSizes.touchTarget else CouliSpacing.space0
            Text(
                text = title,
                style = titleStyle,
                color = CouliColors.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = side).modalTitle(titleFocus),
            )
            if (onClose != null && closeLabel != null) {
                Box(modifier = Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
                    CouliIconButton(
                        icon = CouliIcons.close,
                        label = closeLabel,
                        onClick = onClose,
                        tint = CouliColors.textSecondary,
                        modifier = Modifier.wrapContentHeight(unbounded = true),
                    )
                }
            }
        }
    }
}

/** 半屏把手 36 × 4。 */
@Composable
fun SheetHandle(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .padding(vertical = CouliSpacing.space3)
                .size(width = CouliSizes.sheetHandleWidth, height = CouliSizes.sheetHandleHeight)
                .clip(RoundedCornerShape(CouliRadius.pill))
                .background(CouliColors.borderSubtle),
    )
}

/** 弹层标题：标题语义；给了 [focus] 时可被程序聚焦（打开时焦点进标题）。 */
private fun Modifier.modalTitle(
    focus: FocusRequester?,
    heading: Boolean = true,
): Modifier =
    (if (heading) semantics { heading() } else this)
        .then(if (focus != null) Modifier.focusRequester(focus).focusable() else Modifier)

private const val SKELETON_SECOND_LINE = 0.7f
private const val SKELETON_PRICE_LINE = 0.4f

/** 弹窗最大宽 = 3.5 × 96 = 336（手机上约等于屏宽减两侧边距）。 */
private const val DIALOG_MAX_WIDTH_FACTOR = 3.5f
