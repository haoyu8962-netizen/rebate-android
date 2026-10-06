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
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val ring = focusRingVisible(interaction)
    val active = enabled && !loading
    val colors = buttonColors(variant, enabled = enabled, pressed = pressed)
    val shape = RoundedCornerShape(CouliRadius.button)
    val padding =
        when {
            variant == CouliButtonVariant.TEXT -> PaddingValues(horizontal = CouliSpacing.space2, vertical = CouliSpacing.space1)
            size == CouliButtonSize.COMPACT -> PaddingValues(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space2)
            else -> PaddingValues(horizontal = CouliSpacing.space5, vertical = CouliSpacing.space3)
        }
    val style: TextStyle =
        when {
            size == CouliButtonSize.COMPACT -> CouliTextStyles.footnoteSemibold
            variant == CouliButtonVariant.SECONDARY -> CouliTextStyles.bodyMedium
            else -> CouliTextStyles.bodySemibold
        }
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
                    interactionSource = interaction,
                    indication = null,
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
                Icon(leadingIcon, contentDescription = null, tint = colors.content, modifier = Modifier.size(CouliSizes.iconMedium))
            }
            Text(text = text, style = style, color = colors.content, textAlign = TextAlign.Center)
        }
    }
}

private data class ButtonColors(
    val background: Color,
    val content: Color,
    val border: Color,
)

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
            ButtonColors(
                if (pressed) CouliColors.brandSubtle else CouliColors.backgroundSurface,
                CouliColors.brandEmphasis,
                Color.Transparent,
            )
        }
    }
}

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
) {
    val text =
        when (state) {
            BuyButtonState.NORMAL -> BuyButtonModel.text(base, rebate, amountHidden, context)
            BuyButtonState.RETRY_LATER -> retryLaterText
            else -> base
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
) {
    val interaction = remember { MutableInteractionSource() }
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
