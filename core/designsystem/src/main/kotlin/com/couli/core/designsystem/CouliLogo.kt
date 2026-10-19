package com.couli.core.designsystem

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource

private const val LOGO_CANVAS_HEIGHT = 256f
private const val HORIZONTAL_CANVAS_WIDTH = 580f
private const val DARK_BACKGROUND_LUMINANCE = 0.5f

/** 品牌 Logo 的版式（规划仓库 design/logo/README.md「文件」表）。 */
enum class CouliLogoVariant(
    /** 原始画布宽高比（宽 / 高），与 SVG 源文件的 viewBox 一致。 */
    val aspectRatio: Float,
) {
    /** 主图形（256 × 256 画布），建议最小显示 24dp。 */
    MARK(1f),

    /** 横向组合：图形 + 中文字标（580 × 256 画布），建议最小宽 112dp；更窄的地方用 [MARK]。 */
    HORIZONTAL(HORIZONTAL_CANVAS_WIDTH / LOGO_CANVAS_HEIGHT),
}

/** Logo 资源：浅色底用原色版，深色底用反白版（资源由规划仓库 design/logo/source 的 SVG 转成矢量，BRAND-01）。 */
@DrawableRes
fun couliLogoRes(
    variant: CouliLogoVariant,
    inverse: Boolean,
): Int =
    when (variant) {
        CouliLogoVariant.MARK -> {
            if (inverse) R.drawable.couli_logo_mark_inverse else R.drawable.couli_logo_mark
        }

        CouliLogoVariant.HORIZONTAL -> {
            if (inverse) R.drawable.couli_logo_horizontal_inverse else R.drawable.couli_logo_horizontal
        }
    }

/**
 * 当前主题的底色是否为深色：按 M3 背景色亮度判断，跟着 CouliTheme 走，不看系统设置。
 * 反白版不放 drawable-night：主题目前固定浅色（TECH-22），系统切到深色时按资源限定符取图会把白色 Logo 画在浅色底上。
 */
@Composable
@ReadOnlyComposable
fun couliLogoPrefersInverse(): Boolean = MaterialTheme.colorScheme.background.luminance() < DARK_BACKGROUND_LUMINANCE

/**
 * 品牌 Logo（BRAND-01）：只是取用入口，不决定页面摆放（登录等页面的位置以定稿画板为准）。
 * 等比缩放、不拉伸；四周留白由调用方按 Logo 规范排版。
 *
 * @param contentDescription 读屏文本（由调用方经文案字典取）；null 表示装饰图，读屏跳过。
 * @param inverse 是否用反白版；默认按当前主题底色自动选，放在品牌色等深色块上时传 true。
 */
@Composable
fun CouliLogo(
    variant: CouliLogoVariant,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    inverse: Boolean = couliLogoPrefersInverse(),
) {
    Image(
        painter = painterResource(couliLogoRes(variant, inverse)),
        contentDescription = contentDescription,
        modifier = modifier.aspectRatio(variant.aspectRatio),
        contentScale = ContentScale.Fit,
    )
}
