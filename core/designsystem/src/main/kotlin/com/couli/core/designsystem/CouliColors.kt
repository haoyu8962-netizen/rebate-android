package com.couli.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.couli.generated.DesignTokens

/**
 * 颜色令牌的语义名（与 iOS 对齐：令牌路径 `brand.primary` → `brandPrimary`）。
 * 取值来自生成的 [DesignTokens.colors]；只有浅色主题（系统深色模式下强制浅色，TECH-22）。
 */
@Immutable
object CouliColors {
    val brandPrimary: Color = token("brand.primary")
    val brandEmphasis: Color = token("brand.emphasis")
    val brandSecondary: Color = token("brand.secondary")
    val brandSubtle: Color = token("brand.subtle")
    val backgroundCanvas: Color = token("background.canvas")
    val backgroundAdminCanvas: Color = token("background.admin-canvas")
    val backgroundSurface: Color = token("background.surface")
    val backgroundMuted: Color = token("background.muted")
    val backgroundPlaceholder: Color = token("background.placeholder")
    val textPrimary: Color = token("text.primary")
    val textSecondary: Color = token("text.secondary")
    val textInverse: Color = token("text.inverse")
    val textLink: Color = token("text.link")
    val priceText: Color = token("price.text")
    val rebateText: Color = token("rebate.text")
    val rebateBackground: Color = token("rebate.background")
    val rebateSolidBackground: Color = token("rebate.solid.background")
    val rebateSolidText: Color = token("rebate.solid.text")
    val statusPendingText: Color = token("status.pending.text")
    val statusPendingBackground: Color = token("status.pending.background")
    val statusSuccessText: Color = token("status.success.text")
    val statusSuccessBackground: Color = token("status.success.background")
    val statusWarningText: Color = token("status.warning.text")
    val statusWarningBackground: Color = token("status.warning.background")
    val statusErrorText: Color = token("status.error.text")
    val statusErrorBackground: Color = token("status.error.background")
    val borderSubtle: Color = token("border.subtle")
    val borderControl: Color = token("border.control")
    val focusRing: Color = token("focus.ring")
    val sourceText: Color = token("source.text")
    val sourceBackground: Color = token("source.background")
    val sourceBorder: Color = token("source.border")
    val buttonPrimaryDefaultBackground: Color = token("button.primary.default.background")
    val buttonPrimaryDefaultText: Color = token("button.primary.default.text")
    val buttonPrimaryPressedBackground: Color = token("button.primary.pressed.background")
    val buttonPrimaryPressedText: Color = token("button.primary.pressed.text")
    val buttonPrimaryDisabledBackground: Color = token("button.primary.disabled.background")
    val buttonPrimaryDisabledText: Color = token("button.primary.disabled.text")

    /** 令牌路径 → 颜色，便于核对映射完整。 */
    val all: Map<String, Color> =
        linkedMapOf(
            "brand.primary" to brandPrimary,
            "brand.emphasis" to brandEmphasis,
            "brand.secondary" to brandSecondary,
            "brand.subtle" to brandSubtle,
            "background.canvas" to backgroundCanvas,
            "background.admin-canvas" to backgroundAdminCanvas,
            "background.surface" to backgroundSurface,
            "background.muted" to backgroundMuted,
            "background.placeholder" to backgroundPlaceholder,
            "text.primary" to textPrimary,
            "text.secondary" to textSecondary,
            "text.inverse" to textInverse,
            "text.link" to textLink,
            "price.text" to priceText,
            "rebate.text" to rebateText,
            "rebate.background" to rebateBackground,
            "rebate.solid.background" to rebateSolidBackground,
            "rebate.solid.text" to rebateSolidText,
            "status.pending.text" to statusPendingText,
            "status.pending.background" to statusPendingBackground,
            "status.success.text" to statusSuccessText,
            "status.success.background" to statusSuccessBackground,
            "status.warning.text" to statusWarningText,
            "status.warning.background" to statusWarningBackground,
            "status.error.text" to statusErrorText,
            "status.error.background" to statusErrorBackground,
            "border.subtle" to borderSubtle,
            "border.control" to borderControl,
            "focus.ring" to focusRing,
            "source.text" to sourceText,
            "source.background" to sourceBackground,
            "source.border" to sourceBorder,
            "button.primary.default.background" to buttonPrimaryDefaultBackground,
            "button.primary.default.text" to buttonPrimaryDefaultText,
            "button.primary.pressed.background" to buttonPrimaryPressedBackground,
            "button.primary.pressed.text" to buttonPrimaryPressedText,
            "button.primary.disabled.background" to buttonPrimaryDisabledBackground,
            "button.primary.disabled.text" to buttonPrimaryDisabledText,
        )

    private fun token(path: String): Color = parseHexColor(DesignTokens.colors.getValue(path))
}

/** `#RRGGBB` → 不透明 [Color]。 */
fun parseHexColor(hex: String): Color {
    require(hex.length == HEX_LENGTH && hex[0] == '#') { "颜色令牌须为 #RRGGBB：$hex" }
    val rgb = hex.substring(1).toLong(radix = 16)
    return Color(OPAQUE_ALPHA or rgb)
}

private const val HEX_LENGTH = 7
private const val OPAQUE_ALPHA = 0xFF000000L
