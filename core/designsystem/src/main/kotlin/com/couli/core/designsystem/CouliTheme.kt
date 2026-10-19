package com.couli.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

/**
 * 凑狸主题：Material 3 作底层，颜色、字体、最小命中区由令牌定制。
 *
 * DS-02（负责人 2026-10-18「页面经令牌自动跟随系统深色」）：默认跟随系统深色（[darkTheme] = 系统设置），
 * 浅 / 深两套令牌见 [CouliThemeTokens]；组件目录页与快照可显式指定浅 / 深。[colorOverrides] 按令牌路径覆盖个别颜色
 * （测试用探针色证明组件真的读令牌），覆盖值同时进入 [couliColorTokens]、组件绘制与 M3 配色映射。
 */
@Composable
fun CouliTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    colorOverrides: Map<String, Color> = emptyMap(),
    content: @Composable () -> Unit,
) {
    val tokens =
        remember(darkTheme, colorOverrides) {
            val base = if (darkTheme) CouliThemeTokens.dark else CouliThemeTokens.light
            if (colorOverrides.isEmpty()) base else base + colorOverrides.filterKeys { it in base }
        }
    val scheme = remember(tokens, darkTheme) { couliColorScheme(tokens, darkTheme) }
    MaterialTheme(
        colorScheme = scheme,
        typography = CouliMaterialTypography,
    ) {
        CompositionLocalProvider(
            LocalCouliColorTokens provides tokens,
            LocalMinimumInteractiveComponentSize provides CouliInteraction.minTouchTarget,
            content = content,
        )
    }
}

/** 当前主题的全部颜色令牌（令牌路径 → 颜色，含 §7 新增键与覆盖）。 */
@Composable
@ReadOnlyComposable
fun couliColorTokens(): Map<String, Color> = LocalCouliColorTokens.current

/**
 * 令牌 → M3 配色：显式映射全部 48 个角色（组件清单 §7 末：surfaceContainer*、secondaryContainer、inverseSurface、scrim、tertiary 等，及 12 个 Fixed 角色），
 * 系统控件没显式设色时也不会冒出 M3 默认紫灰。
 */
internal fun couliColorScheme(
    t: Map<String, Color>,
    dark: Boolean,
): ColorScheme {
    val surface = t.getValue("background.surface")
    val muted = t.getValue("background.muted")
    val primary = t.getValue("brand.primary")
    val subtle = t.getValue("brand.subtle")
    val emphasis = t.getValue("brand.emphasis")
    val inverse = t.getValue("text.inverse")
    val textPrimary = t.getValue("text.primary")
    val textSecondary = t.getValue("text.secondary")
    val canvas = t.getValue("background.canvas")
    val error = t.getValue("status.error.text")
    val errorBackground = t.getValue("status.error.background")
    // dark 只决定 M3 内部少数派生（如 ripple 透明度）的基底；全部角色下面逐个覆盖成令牌
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base
        .copy(
            primary = primary,
            onPrimary = inverse,
            primaryContainer = subtle,
            onPrimaryContainer = emphasis,
            inversePrimary = emphasis,
            secondary = t.getValue("brand.secondary"),
            onSecondary = inverse,
            secondaryContainer = subtle,
            onSecondaryContainer = emphasis,
            tertiary = emphasis,
            onTertiary = inverse,
            tertiaryContainer = subtle,
            onTertiaryContainer = emphasis,
            background = canvas,
            onBackground = textPrimary,
            surface = surface,
            onSurface = textPrimary,
            surfaceVariant = muted,
            onSurfaceVariant = textSecondary,
            // tonal elevation 不叠紫：tint 与 surface 同色
            surfaceTint = surface,
            inverseSurface = textPrimary,
            inverseOnSurface = surface,
            error = error,
            onError = inverse,
            errorContainer = errorBackground,
            onErrorContainer = error,
            outline = t.getValue("border.control"),
            outlineVariant = t.getValue("border.subtle"),
            scrim = t.getValue("overlay.scrim"),
            surfaceBright = surface,
            surfaceDim = muted,
            surfaceContainerLowest = surface,
            surfaceContainerLow = surface,
            surfaceContainer = surface,
            surfaceContainerHigh = muted,
            surfaceContainerHighest = muted,
        ).withFixedRoles(t)
}

/** Fixed 系列（M3 1.4 共 12 个角色）：浅 / 深同值，三组共用一套（取值依据见 [CouliThemeTokens.FIXED_CONTAINER]）。 */
private fun ColorScheme.withFixedRoles(t: Map<String, Color>): ColorScheme {
    val fixed = t.getValue(CouliThemeTokens.FIXED_CONTAINER)
    val fixedDim = t.getValue(CouliThemeTokens.FIXED_CONTAINER_DIM)
    val onFixed = t.getValue(CouliThemeTokens.FIXED_ON_CONTAINER)
    val onFixedVariant = t.getValue(CouliThemeTokens.FIXED_ON_CONTAINER_VARIANT)
    return copy(
        primaryFixed = fixed,
        primaryFixedDim = fixedDim,
        onPrimaryFixed = onFixed,
        onPrimaryFixedVariant = onFixedVariant,
        secondaryFixed = fixed,
        secondaryFixedDim = fixedDim,
        onSecondaryFixed = onFixed,
        onSecondaryFixedVariant = onFixedVariant,
        tertiaryFixed = fixed,
        tertiaryFixedDim = fixedDim,
        onTertiaryFixed = onFixed,
        onTertiaryFixedVariant = onFixedVariant,
    )
}

internal val CouliMaterialTypography =
    Typography(
        displaySmall = CouliTypography.display,
        headlineSmall = CouliTypography.heading,
        titleLarge = CouliTypography.title,
        bodyLarge = CouliTypography.body,
        bodyMedium = CouliTypography.footnote,
        labelLarge = CouliTypography.footnote,
        bodySmall = CouliTypography.caption,
        labelSmall = CouliTypography.caption,
    )
