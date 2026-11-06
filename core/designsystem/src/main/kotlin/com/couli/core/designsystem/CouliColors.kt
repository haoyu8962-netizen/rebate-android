package com.couli.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.couli.generated.DesignTokens

/**
 * 颜色令牌的语义名（与 iOS 对齐：令牌路径 `brand.primary` → `brandPrimary`）。
 * DS-02 起按当前主题取值（[CouliTheme] 提供浅 / 深两套与覆盖，见 [CouliThemeTokens]），只能在组合里读；
 * 页面与组件都只认令牌名、不写色值，深色时自动跟随。
 */
@Immutable
object CouliColors {
    val brandPrimary: Color
        @Composable @ReadOnlyComposable
        get() = token("brand.primary")
    val brandEmphasis: Color
        @Composable @ReadOnlyComposable
        get() = token("brand.emphasis")
    val brandSecondary: Color
        @Composable @ReadOnlyComposable
        get() = token("brand.secondary")
    val brandSubtle: Color
        @Composable @ReadOnlyComposable
        get() = token("brand.subtle")
    val backgroundCanvas: Color
        @Composable @ReadOnlyComposable
        get() = token("background.canvas")
    val backgroundAdminCanvas: Color
        @Composable @ReadOnlyComposable
        get() = token("background.admin-canvas")
    val backgroundSurface: Color
        @Composable @ReadOnlyComposable
        get() = token("background.surface")
    val backgroundMuted: Color
        @Composable @ReadOnlyComposable
        get() = token("background.muted")
    val backgroundPlaceholder: Color
        @Composable @ReadOnlyComposable
        get() = token("background.placeholder")
    val textPrimary: Color
        @Composable @ReadOnlyComposable
        get() = token("text.primary")
    val textSecondary: Color
        @Composable @ReadOnlyComposable
        get() = token("text.secondary")
    val textInverse: Color
        @Composable @ReadOnlyComposable
        get() = token("text.inverse")
    val textLink: Color
        @Composable @ReadOnlyComposable
        get() = token("text.link")
    val priceText: Color
        @Composable @ReadOnlyComposable
        get() = token("price.text")
    val rebateText: Color
        @Composable @ReadOnlyComposable
        get() = token("rebate.text")
    val rebateBackground: Color
        @Composable @ReadOnlyComposable
        get() = token("rebate.background")
    val rebateSolidBackground: Color
        @Composable @ReadOnlyComposable
        get() = token("rebate.solid.background")
    val rebateSolidText: Color
        @Composable @ReadOnlyComposable
        get() = token("rebate.solid.text")
    val statusPendingText: Color
        @Composable @ReadOnlyComposable
        get() = token("status.pending.text")
    val statusPendingBackground: Color
        @Composable @ReadOnlyComposable
        get() = token("status.pending.background")
    val statusSuccessText: Color
        @Composable @ReadOnlyComposable
        get() = token("status.success.text")
    val statusSuccessBackground: Color
        @Composable @ReadOnlyComposable
        get() = token("status.success.background")
    val statusWarningText: Color
        @Composable @ReadOnlyComposable
        get() = token("status.warning.text")
    val statusWarningBackground: Color
        @Composable @ReadOnlyComposable
        get() = token("status.warning.background")
    val statusErrorText: Color
        @Composable @ReadOnlyComposable
        get() = token("status.error.text")
    val statusErrorBackground: Color
        @Composable @ReadOnlyComposable
        get() = token("status.error.background")
    val borderSubtle: Color
        @Composable @ReadOnlyComposable
        get() = token("border.subtle")
    val borderControl: Color
        @Composable @ReadOnlyComposable
        get() = token("border.control")
    val focusRing: Color
        @Composable @ReadOnlyComposable
        get() = token("focus.ring")
    val sourceText: Color
        @Composable @ReadOnlyComposable
        get() = token("source.text")
    val sourceBackground: Color
        @Composable @ReadOnlyComposable
        get() = token("source.background")
    val sourceBorder: Color
        @Composable @ReadOnlyComposable
        get() = token("source.border")
    val buttonPrimaryDefaultBackground: Color
        @Composable @ReadOnlyComposable
        get() = token("button.primary.default.background")
    val buttonPrimaryDefaultText: Color
        @Composable @ReadOnlyComposable
        get() = token("button.primary.default.text")
    val buttonPrimaryPressedBackground: Color
        @Composable @ReadOnlyComposable
        get() = token("button.primary.pressed.background")
    val buttonPrimaryPressedText: Color
        @Composable @ReadOnlyComposable
        get() = token("button.primary.pressed.text")
    val buttonPrimaryDisabledBackground: Color
        @Composable @ReadOnlyComposable
        get() = token("button.primary.disabled.background")
    val buttonPrimaryDisabledText: Color
        @Composable @ReadOnlyComposable
        get() = token("button.primary.disabled.text")

    // —— 组件清单 §7 新增颜色（T1–T5），令牌源 v0.4.0 已收录，与上面一样读当前主题的生成表 ——
    val buttonInverseBackground: Color
        @Composable @ReadOnlyComposable
        get() = token(CouliThemeTokens.BUTTON_INVERSE_BACKGROUND)
    val buttonInverseText: Color
        @Composable @ReadOnlyComposable
        get() = token(CouliThemeTokens.BUTTON_INVERSE_TEXT)
    val borderActive: Color
        @Composable @ReadOnlyComposable
        get() = token(CouliThemeTokens.BORDER_ACTIVE)
    val overlayScrim: Color
        @Composable @ReadOnlyComposable
        get() = token(CouliThemeTokens.OVERLAY_SCRIM)
    val badgeBackground: Color
        @Composable @ReadOnlyComposable
        get() = token(CouliThemeTokens.BADGE_BACKGROUND)
    val controlChecked: Color
        @Composable @ReadOnlyComposable
        get() = token(CouliThemeTokens.CONTROL_CHECKED)

    /** 令牌源的浅色值（令牌路径 → 颜色，便于核对生成物映射完整；不含 Fixed 派生键、不随主题变化）。 */
    val all: Map<String, Color> = DesignTokens.colors.mapValues { (_, hex) -> parseHexColor(hex) }

    @Composable
    @ReadOnlyComposable
    private fun token(path: String): Color = LocalCouliColorTokens.current.getValue(path)
}

/**
 * 令牌色值 → [Color]：`#RRGGBB` 为不透明；`#RRGGBBAA`（令牌源写法，末两位为透明度）把 alpha 字节移到 Compose 的
 * 0xAARRGGBB 高位，不截掉、也不当成 #AARRGGBB 读。其他写法一律拒绝。
 */
fun parseHexColor(hex: String): Color {
    require(hex.startsWith('#') && (hex.length == RGB_LENGTH || hex.length == RGBA_LENGTH)) {
        "颜色令牌须为 #RRGGBB / #RRGGBBAA：$hex"
    }
    val bits = hex.substring(1).toLong(radix = 16)
    val argb = if (hex.length == RGBA_LENGTH) (bits ushr BYTE_BITS) or ((bits and BYTE_MASK) shl ALPHA_SHIFT) else OPAQUE_ALPHA or bits
    return Color(argb)
}

private const val RGB_LENGTH = 7
private const val RGBA_LENGTH = 9
private const val BYTE_BITS = 8
private const val ALPHA_SHIFT = 24
private const val BYTE_MASK = 0xFFL
private const val OPAQUE_ALPHA = 0xFF000000L
