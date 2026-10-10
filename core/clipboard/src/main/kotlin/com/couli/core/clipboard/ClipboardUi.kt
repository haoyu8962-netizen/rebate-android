package com.couli.core.clipboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
}

/**
 * 首页剪贴板提示条（设计稿 HomeClipboard / HomeClipboardInvite）：白底圆角 16、浮起阴影、内边距 8 / 12；左侧 14/600 文案（出现时读屏
 * 播报一次），右侧胶囊按钮与独立的【关闭】（44 命中区，Android 扩到 48）。Android 的按钮是自绘按钮：用户点了才上传已读到的片段（商品）或
 * 进入确认（邀请）。[actionLabel] 是读屏名称（邀请条为「粘贴并绑定邀请人」）。[enabled] = false：42901 限流期间按钮置灰。
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
) {
    val shape = RoundedCornerShape(CouliRadius.card)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .couliCard(shape = shape)
                .padding(horizontal = CouliSpacing.space3, vertical = CouliSpacing.space2)
                .testTag(ClipboardUi.PROMPT_BAR),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        if (detail == null && badge == null) {
            Text(
                text = message,
                style = CouliTypography.footnote.copy(fontWeight = CouliTextStyles.bodySemibold.fontWeight),
                color = CouliColors.textPrimary,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            )
        } else {
            PromptMessage(message, detail, badge, messageLabel, Modifier.weight(1f))
        }
        PillButton(
            text = actionText,
            label = actionLabel,
            onClick = onAction,
            enabled = enabled && !busy,
            modifier = Modifier.testTag(ClipboardUi.PROMPT_ACTION),
        )
        CouliIconButton(
            icon = CouliIcons.close,
            label = closeLabel,
            onClick = onClose,
            tint = CouliColors.textSecondary,
            iconSize = CouliSizes.iconMedium,
            modifier = Modifier.testTag(ClipboardUi.PROMPT_CLOSE),
        )
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

/** 浅灰胶囊按钮（设计稿：#EFEAE2 底、12 号字、左右 12、命中区 ≥ 48）。 */
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
            style = CouliTypography.caption,
            color = if (enabled) CouliColors.textPrimary else CouliColors.textSecondary,
            modifier =
                Modifier
                    .clip(RoundedCornerShape(CouliRadius.pill))
                    .background(CouliColors.backgroundMuted)
                    .padding(horizontal = CouliSpacing.space3, vertical = CouliSpacing.space2)
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

/**
 * 首页「粘贴链接，先查返利 · 去粘贴」卡（设计稿 HomeClipboard / Main 的首屏卡）：品牌浅底圆角 16，标题 17/600、副标题 12，下方白底胶囊
 * 「去粘贴 ›」。整卡是一个按钮（读屏名 = 标题 + 去粘贴），点了才读剪贴板。轮播的「推广」角标与圆点属于首页 SDUI（APP-11），这里不画。
 */
@Composable
fun HomePasteCard(
    title: String,
    subtitle: String,
    actionText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(CouliRadius.card)
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(shape)
                .background(CouliColors.brandSubtle, shape)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics(mergeDescendants = true) { role = Role.Button }
                .padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space5)
                .testTag(ClipboardUi.HOME_PASTE_CARD),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
    ) {
        Text(text = title, style = CouliTextStyles.bodySemibold, color = CouliColors.textPrimary)
        Text(text = subtitle, style = CouliTypography.caption, color = CouliColors.textSecondary)
        Row(
            modifier =
                Modifier
                    .padding(top = CouliSpacing.space2)
                    .clip(RoundedCornerShape(CouliRadius.pill))
                    .background(CouliColors.backgroundSurface)
                    .padding(
                        start = CouliSpacing.space3,
                        end = CouliSpacing.space2,
                        top = CouliSpacing.space1,
                        bottom = CouliSpacing.space1,
                    ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = actionText,
                style = CouliTextStyles.captionMedium,
                color = if (enabled) CouliColors.brandEmphasis else CouliColors.textSecondary,
            )
            Icon(
                CouliIcons.chevron,
                contentDescription = null,
                tint = if (enabled) CouliColors.brandEmphasis else CouliColors.textSecondary,
                modifier = Modifier.size(CouliSizes.iconSmall),
            )
        }
    }
}
