package com.couli.core.clipboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliIconButton
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliRadius
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.PlatformBadge
import com.couli.core.designsystem.couliCard

/** 剪贴板界面组件的测试标记（UI 测试、冒烟用）。 */
object ClipboardUi {
    const val PROMPT_BAR = "clipboard_prompt_bar"
    const val PROMPT_ACTION = "clipboard_prompt_action"
    const val PROMPT_CLOSE = "clipboard_prompt_close"

    /** 带平台的提示文字区（FU-CLIP C7）与第二行（平台名 + 缩略候选）。 */
    const val PROMPT_MESSAGE = "clipboard_prompt_message"
    const val PROMPT_DETAIL = "clipboard_prompt_detail"
    const val PASTE_ENTRY = "clipboard_paste_entry"
    const val HOME_PASTE_CARD = "clipboard_home_paste_card"

    /** 首页粘贴卡右侧的示意插图（装饰，读屏隐藏）。 */
    const val HOME_PASTE_ILLUSTRATION = "home_paste_illustration"
}

/**
 * 首页剪贴板提示条（设计稿 HomeClipboard / HomeClipboardInvite-v3）：白卡浮层（圆角 16、浮起阴影、内边距 8 / 12）；左侧 14/600 文案（出现时读屏
 * 播报一次），右侧浅底胶囊按钮（brand.subtle 底 + brand.emphasis 字）与独立的【关闭】（44 命中区，Android 扩到 48）。Android 的按钮是自绘按钮：
 * 用户点了才上传已读到的片段（商品）或进入确认（邀请）。[actionLabel] 是读屏名称（邀请条为「绑定邀请人」）。[enabled] = false：42901 限流期间按钮置灰。
 * [keepLastClause]：文案最后一个「，」之后的问句不拆行，放不下时整句换到下一行（邀请条「绑定为邀请人？」，APP-11v A6）。
 */
@Composable
fun ClipboardPromptBar(
    message: String,
    actionText: String,
    actionLabel: String,
    closeLabel: String,
    onAction: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    detail: String? = null,
    badge: String? = null,
    messageLabel: String? = null,
    keepLastClause: Boolean = false,
) {
    val shape = RoundedCornerShape(CouliRadius.card)
    val barModifier =
        modifier
            .fillMaxWidth()
            .couliCard(shape = shape)
            .padding(horizontal = CouliSpacing.space3, vertical = CouliSpacing.space2)
            .testTag(ClipboardUi.PROMPT_BAR)
    val action: @Composable () -> Unit = {
        PillButton(
            text = actionText,
            label = actionLabel,
            onClick = onAction,
            enabled = enabled && !busy,
            modifier = Modifier.testTag(ClipboardUi.PROMPT_ACTION),
        )
    }
    val close: @Composable () -> Unit = {
        CouliIconButton(
            icon = CouliIcons.close,
            label = closeLabel,
            onClick = onClose,
            tint = CouliColors.textSecondary,
            iconSize = CouliSizes.iconMedium,
            modifier = Modifier.testTag(ClipboardUi.PROMPT_CLOSE),
        )
    }
    if (keepLastClause && detail == null && badge == null) {
        KeepClausePromptLayout(message = message, action = action, close = close, modifier = barModifier)
        return
    }
    Row(
        modifier = barModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        if (detail == null && badge == null) {
            KeepTogetherText(
                text = message,
                keepFrom = 0,
                style = PROMPT_STYLE,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            )
        } else {
            PromptMessage(message, detail, badge, messageLabel, Modifier.weight(1f))
        }
        action()
        close()
    }
}

/**
 * 末句不拆的提示条（邀请条，APP-11v A6）：同行时文字 | 按钮 | 关闭，与普通提示条同排法；末句（最后一个「，」之后，如「绑定为邀请人？」）连同行
 * 文字栏都放不下时（大字号 / 窄屏），按钮与关闭整体换到文字下方靠右，文字获得整行宽度，问句整句换行不拆开。字号、文字与读屏内容不变。
 */
@Composable
private fun KeepClausePromptLayout(
    message: String,
    action: @Composable () -> Unit,
    close: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keepFrom = message.lastIndexOf(CLAUSE_MARK) + 1
    val measurer = rememberTextMeasurer()
    Layout(
        modifier = modifier,
        content = {
            KeepTogetherText(
                text = message,
                keepFrom = keepFrom,
                style = PROMPT_STYLE,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            action()
            close()
        },
    ) { measurables, constraints ->
        val gap = CouliSpacing.space2.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val actionPlaceable = measurables[1].measure(loose)
        val closePlaceable = measurables[2].measure(loose)
        val full = constraints.maxWidth
        val inlineRoom = (full - actionPlaceable.width - closePlaceable.width - gap * 2).coerceAtLeast(0)
        val clause = if (keepFrom in 1 until message.length) message.substring(keepFrom) else ""
        val clauseWidth = if (clause.isEmpty()) 0 else measurer.measure(clause, PROMPT_STYLE).size.width
        val stacked = clauseWidth > inlineRoom
        val textWidth = if (stacked) full else inlineRoom
        val text = measurables[0].measure(Constraints(minWidth = textWidth, maxWidth = textWidth))
        val actionsHeight = maxOf(actionPlaceable.height, closePlaceable.height)
        val height = if (stacked) text.height + actionsHeight else maxOf(text.height, actionsHeight)
        layout(full, height.coerceAtLeast(constraints.minHeight)) {
            val actionsTop = if (stacked) text.height else 0
            val actionsBand = if (stacked) actionsHeight else height
            text.place(0, if (stacked) 0 else (height - text.height) / 2)
            closePlaceable.place(full - closePlaceable.width, actionsTop + (actionsBand - closePlaceable.height) / 2)
            actionPlaceable.place(
                full - closePlaceable.width - gap - actionPlaceable.width,
                actionsTop + (actionsBand - actionPlaceable.height) / 2,
            )
        }
    }
}

/**
 * 带平台的提示文字（FU-CLIP C7，K26）：第一行 [message]（14/600），第二行平台来源标签 [badge] +「{平台}商品链接 {缩略主机}」[detail]（12，灰）。
 * 文字区合成一个读屏单元，读作 [label]（「检测到淘宝商品链接，查返利」），出现时礼貌播报一次。只显示缩略主机，不显示路径与查询。
 */
@Composable
private fun PromptMessage(
    message: String,
    detail: String?,
    badge: String?,
    label: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                    if (label != null) contentDescription = label
                }.testTag(ClipboardUi.PROMPT_MESSAGE),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
    ) {
        Text(
            text = message,
            style = CouliTypography.footnote.copy(fontWeight = CouliTextStyles.bodySemibold.fontWeight),
            color = CouliColors.textPrimary,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            if (badge != null) PlatformBadge(name = badge)
            if (detail != null) {
                Text(
                    text = detail,
                    style = CouliTypography.caption,
                    color = CouliColors.textSecondary,
                    modifier = Modifier.testTag(ClipboardUi.PROMPT_DETAIL),
                )
            }
        }
    }
}

/**
 * 文字从 [keepFrom] 到末尾不拆到两行：按当前宽度排一次，若这段跨行且本身放得进一行，就让它从新的一行开始（段落样式分段，不插入任何字符，
 * 文字与读屏内容不变、文字栏宽度不变）；这段比一行还长时照常换行。[keepFrom] ≤ 0 时就是普通文字。
 */
@Composable
private fun KeepTogetherText(
    text: String,
    keepFrom: Int,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier = modifier) {
        val full = constraints.maxWidth
        val content =
            remember(text, keepFrom, style, full, measurer) {
                if (keepFrom <= 0 || keepFrom >= text.length || full == Constraints.Infinity) {
                    AnnotatedString(text)
                } else {
                    keepClauseTogether(text, keepFrom) { measurer.measure(it, style, constraints = Constraints(maxWidth = full)) }
                }
            }
        Text(text = content, style = style, color = CouliColors.textPrimary)
    }
}

/** [KeepTogetherText] 的排版内容：[layout] 按当前宽度排版；末段跨行时改为从 [keepFrom] 起另起一段，仍放不进一行就保持原样。 */
internal fun keepClauseTogether(
    text: String,
    keepFrom: Int,
    layout: (AnnotatedString) -> TextLayoutResult,
): AnnotatedString {
    val plain = AnnotatedString(text)
    if (layout(plain).keepsTogether(keepFrom, text.lastIndex)) return plain
    val split =
        buildAnnotatedString {
            append(text)
            addStyle(ParagraphStyle(), keepFrom, text.length)
        }
    return if (layout(split).keepsTogether(keepFrom, text.lastIndex)) split else plain
}

private fun TextLayoutResult.keepsTogether(
    from: Int,
    to: Int,
): Boolean = getLineForOffset(from) == getLineForOffset(to)

private const val CLAUSE_MARK = '，'
private val PROMPT_STYLE = CouliTypography.footnote.copy(fontWeight = CouliTextStyles.bodySemibold.fontWeight)

/** 浅底胶囊按钮（HomeClipboardInvite-v3：brand.subtle 底、brand.emphasis 14/600 字、左右 16、命中区 ≥ 48）。 */
@Composable
private fun PillButton(
    text: String,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .heightIn(min = CouliSizes.touchTarget)
                .widthIn(min = CouliSizes.touchTarget)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = text,
            style = CouliTextStyles.footnoteSemibold,
            color = if (enabled) CouliColors.brandEmphasis else CouliColors.textSecondary,
            modifier =
                Modifier
                    .clip(RoundedCornerShape(CouliRadius.pill))
                    .background(if (enabled) CouliColors.brandSubtle else CouliColors.backgroundMuted)
                    .padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space2)
                    .clearAndSetSemantics {},
        )
    }
}

/**
 * 用户主动粘贴的入口（Android：自绘按钮，文字「粘贴查返利」；设计稿 SearchTyping 的整行按钮：品牌浅底、品牌深色字 17/600、圆角 12、
 * 高 ≥ 48）。上方可带说明文字 [hint]（SearchTyping「复制了商品链接？点下方按钮识别并查返利」）。点了才读剪贴板。
 */
@Composable
fun PasteEntry(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    enabled: Boolean = true,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .couliCard()
                .padding(CouliSpacing.space4),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        if (!hint.isNullOrEmpty()) {
            Text(text = hint, style = CouliTypography.caption, color = CouliColors.textSecondary)
        }
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = CouliSizes.touchTarget)
                    .clip(RoundedCornerShape(CouliRadius.button))
                    .background(CouliColors.brandSubtle)
                    .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                    .padding(horizontal = CouliSpacing.space5, vertical = CouliSpacing.space3)
                    .testTag(ClipboardUi.PASTE_ENTRY),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                CouliIcons.copy,
                contentDescription = null,
                tint = if (enabled) CouliColors.brandEmphasis else CouliColors.textSecondary,
                modifier = Modifier.size(CouliSizes.iconMedium),
            )
            Spacer(Modifier.size(CouliSpacing.space2))
            Text(
                text = text,
                style = CouliTextStyles.bodySemibold,
                color = if (enabled) CouliColors.brandEmphasis else CouliColors.textSecondary,
            )
        }
    }
}
