package com.couli.feature.wallet

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonSize
import com.couli.core.designsystem.CouliButtonVariant
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliDivider
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliLoading
import com.couli.core.designsystem.CouliRadius
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTextStyles.tabular
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.LoadingSize
import com.couli.core.designsystem.couliCard
import com.couli.core.designsystem.couliFocusRing
import com.couli.core.designsystem.focusRingVisible

// 提现页（APP-09v-c W36–W55）的私有视觉件。只画样式、只接调用方给的回调：回调由 WithdrawScreen 在渲染时捕获版本 / 表单身份并 `.tapped(tag)` 登记后传入，
// 这里不读任何可观察状态，不改提交 / 估算 / 引导的可用条件（共性第 4、59、63、64 条）。

/** 提现页白卡（圆角 16、组件库阴影）：余额卡、收款账号行用。 */
internal fun Modifier.withdrawCard(): Modifier = couliCard()

/** 可点的白卡（收款账号行，W51）：同 [withdrawCard]，并按卡片圆角裁切按下水波纹。 */
internal fun Modifier.withdrawClickableCard(): Modifier = couliCard().clip(RoundedCornerShape(CouliRadius.card))

/** 收款账号行的账号文字：14 主文字、靠右（W51）。 */
internal val WITHDRAW_PAYOUT_LINE_STYLE: TextStyle = CouliTypography.footnote.copy(textAlign = TextAlign.End)

/** 30309（未成年人受限）引导框里的【查看提现规则】（G-W15）：键与页底「提现规则」去向相同，只在下发了 help_links.withdraw_rules 时出现。 */
internal const val GUIDE_RULES_TEXT_KEY = "error.30309"
internal const val GUIDE_RULES_LABEL_KEY = "withdraw.guide.rules"
internal const val WITHDRAW_GUIDE_RULES_TAG = "withdraw_guide_rules"

/**
 * 快捷档位胶囊（W41）：视觉高 32、全圆角、浅边、14 等宽；不带选中语义（不用 FilterChip）。视觉不被命中区撑高：可点区由 Compose 的最小触控尺寸向上下各扩到 48，
 * 胶囊最小宽 48，横向不扩；调用方把行距设为 16，相邻行的扩展区不重叠。禁用灰底 + 次文字（W54）。
 */
@Composable
internal fun QuickChip(
    text: String,
    label: String,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(CouliRadius.pill)
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier =
            Modifier
                .heightIn(min = CouliSpacing.space8)
                .widthIn(min = CouliSizes.touchTarget)
                .couliFocusRing(focusRingVisible(interaction), CouliRadius.pill)
                .clip(shape)
                .background(if (enabled) CouliColors.backgroundSurface else CouliColors.backgroundMuted)
                .border(CouliSizes.hairline, CouliColors.borderSubtle, shape)
                .clickable(
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                ).semantics { contentDescription = label }
                .testTag(tag)
                .padding(horizontal = CouliSpacing.space4),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = QUICK_STYLE, color = if (enabled) CouliColors.textPrimary else CouliColors.textSecondary)
    }
}

/** 估算区上方的 1 分隔线（W42）：只随估算行（计算中 / 结果）一起出现，估算隐藏时一起消失（W44）。 */
@Composable
internal fun EstimateDivider() {
    CouliDivider()
}

/** 「计算中…」（W43）：16 转圈 + 14 次文字；整行一个读屏元素，不加播报（G-W20）。 */
@Composable
internal fun CalculatingRow(
    text: String,
    tag: String,
) {
    Row(
        modifier = Modifier.semantics(mergeDescendants = true) {}.testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        CouliLoading(description = "", size = LoadingSize.SMALL)
        Text(text = text, style = CouliTypography.footnote, color = CouliColors.textSecondary)
    }
}

/**
 * 估算句（W42）：仍是一个文字节点（保 polite 播报与 AWIT 的整句断言）；只把服务端 est_net 的金额片段加粗到 17/600。金额由调用方按服务端值格式化后给出，
 * 这里不做任何加减。找不到该片段时原样返回。
 */
internal fun estimateText(
    text: String,
    net: String?,
): AnnotatedString =
    buildAnnotatedString {
        append(text)
        val start = net?.takeIf { it.isNotEmpty() }?.let { text.indexOf(it) } ?: -1
        if (start >= 0) addStyle(ESTIMATE_AMOUNT_SPAN, start, start + net.orEmpty().length)
    }

/** 估算行的 14 问号（W42）：行内只占 14 × 14，按钮仍按 48 × 48 量、以图标为中心摆放（命中区 ≥48，不撑高估算行）。 */
internal fun Modifier.inlineHelpSlot(): Modifier = size(CouliSizes.iconTiny).wrapContentSize(unbounded = true)

/** 引导框（W46）里文字下方的描边按钮：与文字左缘对齐（让过 20 图标 + 8 间距），14/500。 */
@Composable
internal fun GuideButton(
    text: String,
    tag: String,
    onClick: () -> Unit,
) {
    Box(modifier = Modifier.padding(start = CouliSizes.iconMedium + CouliSpacing.space2)) {
        CouliButton(
            text = text,
            onClick = onClick,
            variant = CouliButtonVariant.SECONDARY,
            size = CouliButtonSize.COMPACT,
            textStyle = CouliTextStyles.footnoteMedium,
            modifier = Modifier.testTag(tag),
        )
    }
}

/** 时效提示条（W52）：INFO 灰底、20 信息图标次文字色、14 主文字；`WITHDRAW_SLA` 挂在文字上。 */
@Composable
internal fun SlaNote(
    text: String,
    tag: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(CouliColors.backgroundMuted, RoundedCornerShape(CouliRadius.button))
                .padding(CouliSpacing.space3),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        Icon(CouliIcons.info, contentDescription = null, tint = CouliColors.textSecondary, modifier = Modifier.size(CouliSizes.iconMedium))
        Text(text = text, style = CouliTypography.footnote, color = CouliColors.textPrimary, modifier = Modifier.weight(1f).testTag(tag))
    }
}

private val QUICK_STYLE: TextStyle = CouliTypography.footnote.tabular()

private val ESTIMATE_AMOUNT_SPAN =
    SpanStyle(fontSize = CouliTypography.body.fontSize, fontWeight = FontWeight.SemiBold)

/**
 * 页底「提现规则」（W53，r1 S2-2）：视觉一行 14/21 链接色、左右 12（Withdraw.dc.html:60），布局高 = 21，不被 48 命中撑高；
 * 可点区由 Compose 最小触控尺寸上下各扩到 48。外层留白（上 12、下 4，加上底栏行距 4 与底边距 12）保证扩展区不压到【提交】、不越出页面。
 * 页面私有，不改 [CouliButton] 的共用 48 最小高。回调由调用方传入（同页 `controller::openRules`）。
 */
@Composable
internal fun WithdrawRulesLink(
    text: String,
    tag: String,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(modifier = Modifier.padding(top = CouliSpacing.space3, bottom = CouliSpacing.space1)) {
        Text(
            text = text,
            style = CouliTypography.footnote,
            color = CouliColors.textLink,
            modifier =
                Modifier
                    .couliFocusRing(focusRingVisible(interaction), CouliRadius.button)
                    .clip(RoundedCornerShape(CouliRadius.button))
                    .clickable(
                        interactionSource = interaction,
                        indication = LocalIndication.current,
                        role = Role.Button,
                        onClick = onClick,
                    ).testTag(tag)
                    .padding(horizontal = CouliSpacing.space3),
        )
    }
}
