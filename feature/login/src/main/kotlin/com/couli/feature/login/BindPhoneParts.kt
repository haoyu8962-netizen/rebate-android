// 绑手机页（非登录入口）的私有视图件（APP-13v-b，G-D03 / G-D17）：只管外观；回调、可用条件、文案与标记全部由页面传入，这里不读页面状态机，不起协程、不取时钟。
@file:Suppress("TooManyFunctions")

package com.couli.feature.login

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.couli.core.designsystem.CouliColors
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

/** 字段错误行的三角 16。 */
private val ERROR_ICON = 16.dp

/**
 * 顶部区让位档位（G-D17 + 编排 2026-10-29「告知（APP-13v-b 三端口径）」①、评审 r1 R1-02 / R1-03：窄屏大字号时手机号框、验证码、获取验证码、
 * 【绑定】须不滚动就在屏内）：0 = 全部在上；1 = 并号提示条挪到卡片下方；2 = 再收起「验证码」小标签；3 = 副标题也挪到卡片下方（标题始终在上，读屏标题）。
 * 挪下来的说明仍在页面里、滚动可达。
 */
internal const val INTRO_FULL = 0
internal const val INTRO_HINT_BELOW = 1
internal const val INTRO_NO_CODE_LABEL = 2
internal const val INTRO_TITLE_ONLY = 3

/**
 * 让位档位的量取（只管布局量取，不读页面状态机）：档位只随窗口尺寸与字号重置（[rememberIntroFold] 的 key）；格下一行回报下沿时，只有 [onCodeRow] 的
 * measurable 为真（键盘收起、无待确认卡、无字段错误，由页面算好传入）且回报时的档位就是当前档位，才在超出可视区时让一档。
 */
@Stable
internal class IntroFold(
    private val ime: WindowInsets,
    private val density: Density,
) {
    var level by mutableIntStateOf(INTRO_FULL)
        private set
    private var viewportTop = 0f
    private var viewportHeight = 0

    fun onViewport(coordinates: LayoutCoordinates) {
        viewportTop = coordinates.positionInRoot().y
        viewportHeight = coordinates.size.height
    }

    /** [bottomInContent]：格下一行下沿按未滚动位置换算的根坐标；[levelWhenPlaced]：那次布局时的档位。 */
    fun onCodeRow(
        bottomInContent: Float,
        levelWhenPlaced: Int,
        measurable: Boolean,
    ) {
        // 键盘在布局阶段就压缩可视区（早于重组），这里按回报当下的输入法 inset 再判一次，弹起的那一帧也不让位
        val settled = measurable && ime.getBottom(density) == 0
        val overflow = viewportHeight > 0 && bottomInContent - viewportTop > viewportHeight
        val current = levelWhenPlaced == level && level < INTRO_TITLE_ONLY
        if (settled && overflow && current) level += 1
    }
}

/** 按窗口尺寸与字号记住的让位档位（转屏、分屏、改字号时从全部重新量；键盘、待确认卡、错误行不重置）。 */
@Composable
internal fun rememberIntroFold(): IntroFold {
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    val key = LocalWindowInfo.current.containerSize to density.fontScale
    return remember(key, ime, density) { IntroFold(ime, density) }
}

/** 输入法是否弹起（底部 ime inset 大于 0）。 */
@Composable
internal fun imeOpen(): Boolean = WindowInsets.ime.getBottom(LocalDensity.current) > 0

/** 页面标题区（BindPhone-v2）：22/600 标题（读屏标题）+ 14 次文字副标题（[benefit] 为 null 时不画），纵向 8，左对齐。 */
@Composable
internal fun BindIntro(
    title: String,
    benefit: String?,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
        Text(
            text = title,
            style = CouliTypography.title,
            color = CouliColors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        if (benefit != null) Text(text = benefit, style = CouliTypography.footnote, color = CouliColors.textSecondary)
    }
}

/** 让位时挪到卡片下方的副标题（14 次文字）。 */
@Composable
internal fun BindMovedBenefit(text: String) {
    Text(text = text, style = CouliTypography.footnote, color = CouliColors.textSecondary, modifier = Modifier.fillMaxWidth())
}

/** muted 提示条（圆角 12、内边距 12）：20 info（装饰不读）+ 8 + 14 主文字。 */
@Composable
internal fun BindInfoStrip(text: String) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(CouliColors.backgroundMuted, RoundedCornerShape(CouliRadius.button))
                .padding(CouliSpacing.space3),
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        Icon(CouliIcons.info, contentDescription = null, tint = CouliColors.textSecondary, modifier = Modifier.size(CouliSizes.iconMedium))
        Text(text = text, style = CouliTypography.footnote, color = CouliColors.textPrimary, modifier = Modifier.weight(1f))
    }
}

/**
 * 验证码格下一行（同换号写法）：右对齐到格子右缘；可点盒自身至少 48 × 48（触摸盒与语义盒都 ≥ 48），放在格子正下方不与格子相交；文字 14，
 * 可点时 text.link、倒计时 / 不可点时次文字；倒计时不挂实时区域（不逐秒读）。[tag] 挂在可点盒上。
 */
@Composable
internal fun BindCodeSendRow(
    text: String,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            modifier =
                Modifier
                    .heightIn(min = CouliSizes.touchTarget)
                    .widthIn(min = CouliSizes.touchTarget)
                    .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                    .testTag(tag),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(
                text = text,
                style = CouliTypography.footnote.tabular(),
                color = if (enabled) CouliColors.textLink else CouliColors.textSecondary,
            )
        }
    }
}

/** 字段错误行：16 三角（装饰不读）+ 错误句（由页面传入，焦点与一次播报原样），紧贴上一控件下方。 */
@Composable
internal fun BindErrorLine(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = CouliSpacing.space2),
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            CouliIcons.alert,
            contentDescription = null,
            tint = CouliColors.statusErrorText,
            modifier = Modifier.padding(top = CouliSpacing.space1 / 2).size(ERROR_ICON),
        )
        content()
    }
}

/** 待确认卡外框（同注销 / 换号待确认写法）：白卡、内边距 16、纵向 12；[tag] 挂在卡外框上。 */
@Composable
internal fun BindPendingFrame(
    tag: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .couliCard()
                .testTag(tag)
                .padding(CouliSpacing.space4),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
        content = content,
    )
}

/** 待确认警告块：警告浅底、圆角 12、内边距 12；20 三角 + 标题 14/600 + 说明 14（警告字色），整块一个 assertive 元素（只播一次）。 */
@Composable
internal fun BindPendingWarning(
    title: String,
    desc: String,
) {
    WarningShell(modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive }) {
        Text(text = title, style = CouliTextStyles.footnoteSemibold, color = CouliColors.statusWarningText)
        Text(text = desc, style = CouliTypography.footnote, color = CouliColors.statusWarningText)
    }
}

/** 待确认提示警告块（仍无结果 / 40901 等）：[tag] 与 polite 挂在文字上。 */
@Composable
internal fun BindPendingNotice(
    text: String,
    tag: String,
) {
    WarningShell(modifier = Modifier) {
        Text(
            text = text,
            style = CouliTypography.footnote,
            color = CouliColors.statusWarningText,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(tag),
        )
    }
}

@Composable
private fun WarningShell(
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(CouliColors.statusWarningBackground, RoundedCornerShape(CouliRadius.button))
                .padding(CouliSpacing.space3),
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            CouliIcons.alert,
            contentDescription = null,
            tint = CouliColors.statusWarningText,
            modifier = Modifier.size(CouliSizes.iconMedium),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1 / 2), content = content)
    }
}

/** 灰底明细块：muted 底、圆角 12、内边距 12、行距 4（行由页面给）。 */
@Composable
internal fun BindDetailBlock(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(CouliColors.backgroundMuted, RoundedCornerShape(CouliRadius.button))
                .padding(CouliSpacing.space3),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
        content = content,
    )
}

/** 明细一行：标签左（次文字）、值右对齐（主文字等宽，可折行，200% 不截断）。 */
@Composable
internal fun BindDetailRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3)) {
        Text(text = label, style = CouliTypography.footnote, color = CouliColors.textSecondary)
        Text(
            text = value,
            style = CouliTypography.footnote.tabular(),
            color = CouliColors.textPrimary,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 在途状态行（G-D13）：16 转圈 + 14 次文字，polite，整行一个读屏元素。 */
@Composable
internal fun BindBusyRow(text: String) {
    Row(
        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CouliLoading(description = "", size = LoadingSize.SMALL)
        Text(text = text, style = CouliTypography.footnote, color = CouliColors.textSecondary)
    }
}
