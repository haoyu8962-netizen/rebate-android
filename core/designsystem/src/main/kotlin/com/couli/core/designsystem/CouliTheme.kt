package com.couli.core.designsystem

import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * 凑狸主题：Material 3 作底层，颜色、字体、最小命中区由令牌定制。
 * 只有浅色（系统深色模式下强制浅色，TECH-22）。
 */
@Composable
fun CouliTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CouliMaterialColors,
        typography = CouliMaterialTypography,
    ) {
        CompositionLocalProvider(
            LocalMinimumInteractiveComponentSize provides CouliInteraction.minTouchTarget,
            content = content,
        )
    }
}

internal val CouliMaterialColors =
    lightColorScheme(
        primary = CouliColors.brandPrimary,
        onPrimary = CouliColors.textInverse,
        primaryContainer = CouliColors.brandSubtle,
        onPrimaryContainer = CouliColors.brandEmphasis,
        secondary = CouliColors.brandSecondary,
        onSecondary = CouliColors.textInverse,
        background = CouliColors.backgroundCanvas,
        onBackground = CouliColors.textPrimary,
        surface = CouliColors.backgroundSurface,
        onSurface = CouliColors.textPrimary,
        surfaceVariant = CouliColors.backgroundMuted,
        onSurfaceVariant = CouliColors.textSecondary,
        outline = CouliColors.borderControl,
        outlineVariant = CouliColors.borderSubtle,
        error = CouliColors.statusErrorText,
        onError = CouliColors.textInverse,
        errorContainer = CouliColors.statusErrorBackground,
        onErrorContainer = CouliColors.statusErrorText,
    )

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
