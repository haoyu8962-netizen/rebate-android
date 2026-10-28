package com.couli.feature.me

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliLogo
import com.couli.core.designsystem.CouliLogoVariant
import com.couli.core.designsystem.CouliRadius
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.couliCard

/*
 * 隐私中心 / 关于 / 联系客服半屏的视图件（APP-12w-b，设计稿 PrivacyCenter / PrivacyCenterBasic-v2 / PrivacyCenterForceUpdate / About-v2 /
 * AboutBeforeFiling / CsOpenFailed-v2 / CsWechatMissing-v2）。只管外观：按钮、回调与显隐判定都由页面给，这里不读执行器、不发请求。
 */

/**
 * 隐私中心的动作卡（P6 撤回同意 / P7 开启完整功能）：白卡内边距 16；标题 17/600（读屏标题）→ 4 → 说明 14 次文字（[desc] 为 null 时不画，
 * G-P08）→ 12 → [action]（页面原样给的通栏按钮，回调、可用态、标记都在页面里）。说明是纯文字，不可点（隐私红线 §0.1）。
 */
@Composable
internal fun PrivacyActionCard(
    title: String,
    desc: String?,
    action: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().couliCard().padding(CouliSpacing.space4)) {
        Text(
            text = title,
            style = CouliTextStyles.bodySemibold,
            color = CouliColors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        if (desc != null) {
            Spacer(Modifier.height(CouliSpacing.space1))
            Text(text = desc, style = CouliTypography.footnote, color = CouliColors.textSecondary)
        }
        Spacer(Modifier.height(CouliSpacing.space3))
        action()
    }
}

/** 关于页 App 图标（G-P10）：80 品牌色圆角块 + 组件库反白 Logo 图形（不加资源）；装饰，读屏不读。 */
private val ABOUT_ICON_SIZE = 80.dp
private val ABOUT_ICON_MARK = 56.dp

/**
 * 关于页头部（P13–P15）：上 24 下 8、纵向 8、居中：App 图标 80 →「凑狸」17/600（读屏标题）→ 版本 14 次文字、等宽数字。
 */
@Composable
internal fun AboutHeader(
    name: String,
    version: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = CouliSpacing.space6, bottom = CouliSpacing.space2),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        Box(
            modifier = Modifier.size(ABOUT_ICON_SIZE).background(CouliColors.brandPrimary, RoundedCornerShape(CouliRadius.card)),
            contentAlignment = Alignment.Center,
        ) {
            CouliLogo(
                variant = CouliLogoVariant.MARK,
                contentDescription = null,
                modifier = Modifier.size(ABOUT_ICON_MARK),
                inverse = true,
            )
        }
        Text(
            text = name,
            style = CouliTextStyles.bodySemibold,
            color = CouliColors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = version,
            style = CouliTypography.footnote.copy(fontFeatureSettings = "tnum"),
            color = CouliColors.textSecondary,
        )
    }
}

/**
 * 生成式 AI 服务公示（P16，G-P11）：卡外分组名（12 次文字、左缩进 4、读屏标题）→ 8 → 白卡（内边距 12 / 16）正文 14 主文字，[text] 原样显示。
 * 显示与否由页面判定（未配置整节不显示）。
 */
@Composable
internal fun AboutFiling(
    label: String,
    text: String,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
        GroupLabel(label, top = CouliSpacing.space0)
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .couliCard()
                    .padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space3)
                    .testTag(MeTags.ABOUT_FILING),
        ) {
            Text(text = text, style = CouliTypography.footnote, color = CouliColors.textPrimary)
        }
    }
}

/** 客服半屏图标块 56 圆角 16、图形 32（P21，G-P06）。 */
private val CS_BADGE_SIZE = 56.dp
private val CS_BADGE_ICON = 32.dp

/**
 * 客服半屏头部的图标块（P21 / P26）：[warning] 为 true（打不开、只有帮助）警告底 + 警告色三角；否则（未装微信、HANDOFF）muted 底 + 次文字色消息气泡。
 * 装饰，读屏不读（读屏只读标题）。
 */
@Composable
internal fun CsBadge(warning: Boolean) {
    Box(
        modifier =
            Modifier
                .size(CS_BADGE_SIZE)
                .background(
                    if (warning) CouliColors.statusWarningBackground else CouliColors.backgroundMuted,
                    RoundedCornerShape(CouliRadius.card),
                ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (warning) CouliIcons.alert else CouliIcons.chat,
            contentDescription = null,
            tint = if (warning) CouliColors.statusWarningText else CouliColors.textSecondary,
            modifier = Modifier.size(CS_BADGE_ICON),
        )
    }
}

/**
 * 打不开半屏的「去帮助中心」文字链接（P24，G-P06）：14、链接色、下划线、居中；布局 = 文字（不撑高按钮列），命中与读屏盒 ≥ 48（[CompactTextLink]）。
 * 上方多留 2：按钮列间距 12 + 2 = 14，使链接 48 命中区（14 字号行高 21，向上外扩 13.5）不压到上方按钮。回调与标记由页面原样给。
 */
@Composable
internal fun CsHelpLink(
    text: String,
    tag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    CompactTextLink(
        text = text,
        color = CouliColors.textLink,
        tag = tag,
        modifier = modifier.padding(top = CS_LINK_EXTRA_TOP),
        style = CouliTypography.footnote,
        onClick = onClick,
    )
}

private val CS_LINK_EXTRA_TOP = 2.dp
