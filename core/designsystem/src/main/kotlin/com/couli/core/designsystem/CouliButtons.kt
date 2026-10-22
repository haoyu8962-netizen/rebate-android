package com.couli.core.designsystem

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp

/** 按钮种类（GUIDE §3）：主 / 次 / 文字；狸橙实底卡上的白底按钮。 */
enum class CouliButtonVariant {
    PRIMARY,
    SECONDARY,
    TEXT,

    /** 狸橙实底卡内：白底 + 强调字 600（GUIDE §7.1）。 */
    ON_SOLID,
}

/** 按钮尺寸：标准（17/600，内边距 12×20）与紧凑（14/600，内边距 8×16，列表卡）。 */
enum class CouliButtonSize {
    STANDARD,
    COMPACT,
}

/**
 * 凑狸按钮：最小高 48（interaction.target.android），圆角 12；loading 时显示转圈并不可点；禁用用令牌禁用色。
 * [loadingDescription] 是 loading 时读屏读的状态词（由调用方从字典给）。
 */
@Composable
fun CouliButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: CouliButtonVariant = CouliButtonVariant.PRIMARY,
    size: CouliButtonSize = CouliButtonSize.STANDARD,
    enabled: Boolean = true,
    loading: Boolean = false,
    loadingDescription: String? = null,
    accessibilityLabel: String? = null,
    leadingIcon: ImageVector? = null,
    /** 外部交互源（目录页展示「按下」态用）；不给时组件自建。 */
    interactionSource: MutableInteractionSource? = null,
    /** 前置图标尺寸（APP-09v-b W27：提现记录【复制】传 16）；默认 20 不变。 */
    leadingIconSize: Dp = CouliSizes.iconMedium,
    /** 文字样式覆盖（APP-09v-b：记录页 14/500 链接与描边按钮）；null 按种类与尺寸取默认样式。 */
    textStyle: TextStyle? = null,
) {
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val ring = focusRingVisible(interaction)
    val active = enabled && !loading
    val colors = buttonColors(variant, enabled = enabled, pressed = pressed)
    val shape = RoundedCornerShape(CouliRadius.button)
    val padding = buttonPadding(variant, size)
    val style = textStyle ?: buttonTextStyle(variant, size)
    Box(
        modifier =
            modifier
                .defaultMinSize(minWidth = CouliSizes.touchTarget, minHeight = CouliSizes.touchTarget)
                .couliFocusRing(ring, CouliRadius.button)
                .clip(shape)
                .background(colors.background)
                .then(
                    if (variant == CouliButtonVariant.SECONDARY) Modifier.border(CouliSizes.hairline, colors.border, shape) else Modifier,
                ).clickable(
                    // DS-02：恢复系统水波纹（M3 ripple，主题提供）；按下另有令牌底色变化
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                    enabled = active,
                    role = Role.Button,
                    onClick = onClick,
                ).semantics {
                    if (accessibilityLabel != null) contentDescription = accessibilityLabel
                    if (loading && loadingDescription != null) stateDescription = loadingDescription
                }.padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(CouliSizes.iconSmall),
                    color = colors.content,
                    strokeWidth = CouliSizes.hairline * 2,
                )
            } else if (leadingIcon != null) {
                Icon(leadingIcon, contentDescription = null, tint = colors.content, modifier = Modifier.size(leadingIconSize))
            }
            Text(text = text, style = style, color = colors.content, textAlign = TextAlign.Center)
        }
    }
}

private fun buttonPadding(
    variant: CouliButtonVariant,
    size: CouliButtonSize,
): PaddingValues =
    when {
        variant == CouliButtonVariant.TEXT -> PaddingValues(horizontal = CouliSpacing.space2, vertical = CouliSpacing.space1)
        size == CouliButtonSize.COMPACT -> PaddingValues(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space2)
        else -> PaddingValues(horizontal = CouliSpacing.space5, vertical = CouliSpacing.space3)
    }

private fun buttonTextStyle(
    variant: CouliButtonVariant,
    size: CouliButtonSize,
): TextStyle =
    when {
        size == CouliButtonSize.COMPACT -> CouliTextStyles.footnoteSemibold
        variant == CouliButtonVariant.SECONDARY -> CouliTextStyles.bodyMedium
        else -> CouliTextStyles.bodySemibold
    }

private data class ButtonColors(
    val background: Color,
    val content: Color,
    val border: Color,
)

@Composable
private fun buttonColors(
    variant: CouliButtonVariant,
    enabled: Boolean,
    pressed: Boolean,
): ButtonColors {
    if (!enabled && variant != CouliButtonVariant.TEXT) {
        return ButtonColors(
            CouliColors.buttonPrimaryDisabledBackground,
            CouliColors.buttonPrimaryDisabledText,
            CouliColors.buttonPrimaryDisabledBackground,
        )
    }
    return when (variant) {
        CouliButtonVariant.PRIMARY -> {
            ButtonColors(
                if (pressed) CouliColors.buttonPrimaryPressedBackground else CouliColors.buttonPrimaryDefaultBackground,
                if (pressed) CouliColors.buttonPrimaryPressedText else CouliColors.buttonPrimaryDefaultText,
                Color.Transparent,
            )
        }

        CouliButtonVariant.SECONDARY -> {
            ButtonColors(
                if (pressed) CouliColors.backgroundMuted else CouliColors.backgroundSurface,
                CouliColors.textPrimary,
                CouliColors.borderControl,
            )
        }

        CouliButtonVariant.TEXT -> {
            ButtonColors(
                Color.Transparent,
                if (enabled) CouliColors.textLink else CouliColors.textSecondary,
                Color.Transparent,
            )
        }

        CouliButtonVariant.ON_SOLID -> {
            // T1：狸橙实底卡内按钮，深色下仍白底
            ButtonColors(
                if (pressed) CouliColors.brandSubtle else CouliColors.buttonInverseBackground,
                if (pressed) CouliColors.brandEmphasis else CouliColors.buttonInverseText,
                Color.Transparent,
            )
        }
    }
}

/**
 * 购买流程对按钮的覆盖（APP-06b：加载中、限流、点击结果禁用、「安装X后下单才有返利」）：[text] 替换按钮文字（NORMAL 时替换动词且不拼金额，
 * RETRY_LATER 时是「稍后再试」类文字），null 用卡片的。
 */
data class BuyButtonOverride(
    val state: BuyButtonState,
    val text: String?,
)

/** 购买按钮的状态（03 §10.2 BuyButton：loading、禁用、「稍后再试」）。 */
enum class BuyButtonState {
    NORMAL,
    LOADING,
    DISABLED,

    /**
     * BR-PRICE-13 / BR-TEXT-14：复核失败无缓存、转链熔断时主按钮置为禁用态「稍后再试」（文案由调用方给）：
     * 不可点、读屏报告不可用、禁用样式，不拼金额。
     */
    RETRY_LATER,
}

/**
 * 购买按钮。动词 [base] 由调用方从字典给（cta.text_key）；只有 [BuyButtonState.NORMAL] 才经 [BuyButtonModel] 拼「· 预估返 ¥x」，
 * loading、禁用、稍后再试不拼（BR-TEXT-14 表 C）；禁用与「稍后再试」都是禁用态（不可点、读屏报告不可用、禁用样式）。读屏名称以可见文字开头，区间时补上返利区间（adaptation-a11y-spec §9）。
 */
@Composable
fun BuyButton(
    base: String,
    rebate: RebateTagContent,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    context: String = BuyButtonModel.CONTEXT_LIST,
    amountHidden: Boolean = false,
    state: BuyButtonState = BuyButtonState.NORMAL,
    retryLaterText: String = base,
    loadingDescription: String? = null,
    size: CouliButtonSize = CouliButtonSize.COMPACT,
    /** RETRY_LATER 画成描边次按钮外观（仍不可点；剪贴板识别结果④ HomeClipboardResults-v3，APP-11v A18）。默认 false 保持原外观。 */
    retryLaterOutlined: Boolean = false,
) {
    val text =
        when (state) {
            BuyButtonState.NORMAL -> BuyButtonModel.text(base, rebate, amountHidden, context)
            BuyButtonState.RETRY_LATER -> retryLaterText
            else -> base
        }
    if (state == BuyButtonState.RETRY_LATER && retryLaterOutlined) {
        OutlinedDisabledButton(text = text, size = size, modifier = modifier)
        return
    }
    val showsAmount = !amountHidden && context in BuyButtonModel.contextsShowingAmount
    val spoken =
        when {
            state != BuyButtonState.NORMAL || !showsAmount || !rebate.hasAmount -> null
            else -> base + PriceTerms.SPOKEN_PAUSE + rebate.spoken
        }
    CouliButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        size = size,
        enabled = state != BuyButtonState.DISABLED && state != BuyButtonState.RETRY_LATER,
        loading = state == BuyButtonState.LOADING,
        loadingDescription = loadingDescription,
        accessibilityLabel = spoken,
    )
}

/** 不可点的描边次按钮外观：surface 底 + 控件边 + 次文字色；语义与禁用的 [CouliButton] 相同（按钮角色、不可用）。 */
@Composable
private fun OutlinedDisabledButton(
    text: String,
    size: CouliButtonSize,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(CouliRadius.button)
    Box(
        modifier =
            modifier
                .defaultMinSize(minWidth = CouliSizes.touchTarget, minHeight = CouliSizes.touchTarget)
                .clip(shape)
                .background(CouliColors.backgroundSurface)
                .border(CouliSizes.hairline, CouliColors.borderControl, shape)
                .clickable(enabled = false, role = Role.Button) {}
                .padding(buttonPadding(CouliButtonVariant.SECONDARY, size)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = buttonTextStyle(CouliButtonVariant.SECONDARY, size),
            color = CouliColors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/** 图标按钮：视觉图标 [iconSize]，命中区不小于 48（Android）。[label] 是读屏名称，必须由调用方给。 */
@Composable
fun CouliIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = CouliColors.textPrimary,
    iconSize: Dp = CouliSizes.iconLarge,
    enabled: Boolean = true,
    /** 外部交互源（目录页展示「按下」态用）；不给时组件自建。 */
    interactionSource: MutableInteractionSource? = null,
) {
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    Box(
        modifier =
            modifier
                .size(CouliSizes.touchTarget)
                .couliFocusRing(focusRingVisible(interaction), CouliRadius.pill)
                .clip(RoundedCornerShape(CouliRadius.pill))
                .clickable(
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                ).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}
