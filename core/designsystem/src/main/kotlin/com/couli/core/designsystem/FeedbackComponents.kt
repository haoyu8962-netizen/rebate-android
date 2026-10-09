package com.couli.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
 * （F-AND-06，[ErrorStateFocus]）；同一内容重绘不重复，换了内容重新播报、重新移焦。
 */
@Composable
fun ErrorState(
    title: String,
    retry: StateAction,
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    val retryFocus = remember { FocusRequester() }
    ErrorAnnouncement(ErrorStateFocus.announcement(title, message), retryFocus)
    StateLayout(
        title = title,
        message = message,
        icon = CouliIcons.alert,
        action = retry,
        modifier = modifier,
        actionModifier = Modifier.couliFocusTarget(retryFocus),
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
                variant = CouliButtonVariant.SECONDARY,
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
        if (icon !=
            null
        ) {
            Icon(icon, contentDescription = null, tint = CouliColors.statusSuccessText, modifier = Modifier.size(CouliSizes.iconMedium))
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
    message: String? = null,
    dismissText: String? = null,
    onDismiss: () -> Unit = onDismissRequest,
    dismissible: Boolean = true,
    restoreFocusTo: FocusRequester? = null,
    body: (@Composable () -> Unit)? = null,
    /** 主按钮可点（如 42901 限流期间置灰，到期恢复）。 */
    confirmEnabled: Boolean = true,
) {
    RestoreFocusOnDispose(restoreFocusTo)
    Dialog(
        onDismissRequest = onDismissRequest,
        properties =
            DialogProperties(
                dismissOnBackPress = dismissible,
                dismissOnClickOutside = dismissible,
                usePlatformDefaultWidth = false,
            ),
    ) {
        // 可视区域（扣除安全区）四周各留 24：弹窗高度不会超过窗口，正文在弹窗内滚动
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
                dismissText = dismissText,
                onDismiss = onDismiss,
                titleFocus = titleFocus,
                body = body,
                confirmEnabled = confirmEnabled,
            )
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
            Text(
                text = title,
                style = CouliTextStyles.bodySemibold,
                color = CouliColors.textPrimary,
                modifier = Modifier.modalTitle(titleFocus),
            )
        },
        // 正文：可交互的正文（如带协议链接的确认框，链接各自可点、读屏可单独聚焦）用 [body]，否则是纯文字 [message]
        body =
            body ?: message?.let {
                { Text(text = it, style = CouliTypography.footnote, color = CouliColors.textPrimary) }
            },
        footer = {
            DialogButtons(
                primary = {
                    CouliButton(text = confirmText, onClick = onConfirm, enabled = confirmEnabled, modifier = Modifier.fillMaxWidth())
                },
                secondary =
                    if (dismissText != null) {
                        {
                            CouliButton(
                                text = dismissText,
                                onClick = onDismiss,
                                variant = CouliButtonVariant.SECONDARY,
                                modifier = Modifier.fillMaxWidth(),
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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CouliSheet(
    title: String,
    onDismissRequest: () -> Unit,
    actions: (@Composable () -> Unit)? = null,
    restoreFocusTo: FocusRequester? = null,
    closeLabel: String? = null,
    content: @Composable () -> Unit,
) {
    RestoreFocusOnDispose(restoreFocusTo)
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = CouliRadius.panel, topEnd = CouliRadius.panel),
        containerColor = CouliColors.backgroundSurface,
        scrimColor = CouliColors.textPrimary.copy(alpha = CouliAlpha.SCRIM),
        dragHandle = { SheetHandle() },
    ) {
        val titleFocus = remember { FocusRequester() }
        FocusOnAppear(titleFocus)
        CouliSheetBody(
            title = title,
            actions = actions,
            titleFocus = titleFocus,
            onClose = closeLabel?.let { { onDismissRequest() } },
            closeLabel = closeLabel,
            content = content,
        )
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
    content: @Composable () -> Unit,
) {
    ScrollBodyLayout(
        modifier = modifier.fillMaxWidth().padding(start = CouliSpacing.space4, end = CouliSpacing.space4, bottom = CouliSpacing.space6),
        gap = CouliSpacing.space4,
        dividerWhenScrollable = true,
        header = {
            // 标题 → 关闭 → 正文 → 操作（adaptation-a11y-spec §8：关闭在 DOM 里排在标题之后，视觉在右上角）
            if (onClose != null && closeLabel != null) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = title,
                        style = CouliTextStyles.bodySemibold,
                        color = CouliColors.textPrimary,
                        modifier = Modifier.weight(1f).padding(top = CouliSpacing.space3).modalTitle(titleFocus),
                    )
                    CouliIconButton(icon = CouliIcons.close, label = closeLabel, onClick = onClose, tint = CouliColors.textSecondary)
                }
            } else {
                Text(
                    text = title,
                    style = CouliTextStyles.bodySemibold,
                    color = CouliColors.textPrimary,
                    modifier = Modifier.modalTitle(titleFocus),
                )
            }
        },
        body = { Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space4)) { content() } },
        footer = actions,
    )
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
private fun Modifier.modalTitle(focus: FocusRequester?): Modifier =
    semantics { heading() }.then(if (focus != null) Modifier.focusRequester(focus).focusable() else Modifier)

private const val SKELETON_SECOND_LINE = 0.7f
private const val SKELETON_PRICE_LINE = 0.4f

/** 弹窗最大宽 = 3.5 × 96 = 336（手机上约等于屏宽减两侧边距）。 */
private const val DIALOG_MAX_WIDTH_FACTOR = 3.5f
