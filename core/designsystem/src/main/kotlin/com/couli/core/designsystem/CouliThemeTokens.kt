package com.couli.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.couli.generated.DesignTokens

/**
 * 浅 / 深两套颜色令牌（DS-02；TOKENS-PIPE 起两套都读生成物）：
 * - 浅色 = 生成的 [DesignTokens.colors]（令牌源 design-tokens.json 的 value）；
 * - 深色 = 生成的 [DesignTokens.colorsDark]（令牌源的 dark 字段，v0.4.0 起进唯一源）；
 * - 另加 M3 Fixed 四个角色（令牌源无独立叶子，由浅色品牌令牌派生，见 [FIXED_CONTAINER]）。
 * 这里不写任何色值；改值只改令牌源、升锁、重新生成。
 */
@Immutable
internal object CouliThemeTokens {
    /** 清单 §7 新增颜色键（T1–T5，令牌源 v0.4.0 已收录；无 `color.` 前缀，与 [DesignTokens.colors] 同口径）。 */
    const val BUTTON_INVERSE_BACKGROUND = "button.inverse.background"
    const val BUTTON_INVERSE_TEXT = "button.inverse.text"
    const val BORDER_ACTIVE = "border.active"
    const val OVERLAY_SCRIM = "overlay.scrim"
    const val BADGE_BACKGROUND = "badge.background"
    const val CONTROL_CHECKED = "control.checked"

    /**
     * M3 Fixed 系列角色（primary / secondary / tertiary 的 Fixed、FixedDim、on…Fixed、on…FixedVariant）的令牌键。
     * 清单 §7 未列，令牌源也没有独立叶子；M3 定义 Fixed = 浅 / 深两态取同一色，所以两套取同值。
     * 取值依据：本主题三组容器色都映射 brand.subtle / brand.emphasis，Fixed 取生成物里它们的**浅色**值——
     * 底 = 浅色 brand.subtle，字 = 浅色 brand.emphasis（对比 5.6:1）；令牌源没有更深一档的品牌浅底，
     * Dim 先与 Fixed 同值、on…FixedVariant 先与 on…Fixed 同值。现有组件都不读这四个角色，只防系统控件冒出 M3 默认色。
     */
    const val FIXED_CONTAINER = "fixed.container"
    const val FIXED_CONTAINER_DIM = "fixed.container-dim"
    const val FIXED_ON_CONTAINER = "fixed.on-container"
    const val FIXED_ON_CONTAINER_VARIANT = "fixed.on-container-variant"

    /** 浅色：生成物 colors（含 T1–T5）+ Fixed。 */
    val light: Map<String, Color> = generated(DesignTokens.colors)

    /** 深色：生成物 colorsDark（键与 colors 相同）+ Fixed。 */
    val dark: Map<String, Color> = generated(DesignTokens.colorsDark)

    private fun generated(hexes: Map<String, String>): Map<String, Color> =
        linkedMapOf<String, Color>().apply {
            hexes.forEach { (path, hex) -> put(path, parseHexColor(hex)) }
            putFixed()
        }

    /** Fixed 系列浅 / 深同值：由浅色 brand.subtle / brand.emphasis 派生（见 [FIXED_CONTAINER]）。 */
    private fun MutableMap<String, Color>.putFixed() {
        val container = parseHexColor(DesignTokens.colors.getValue("brand.subtle"))
        val onContainer = parseHexColor(DesignTokens.colors.getValue("brand.emphasis"))
        put(FIXED_CONTAINER, container)
        put(FIXED_CONTAINER_DIM, container)
        put(FIXED_ON_CONTAINER, onContainer)
        put(FIXED_ON_CONTAINER_VARIANT, onContainer)
    }

    init {
        check(light.keys == dark.keys) { "浅 / 深令牌键不一致：${light.keys xor dark.keys}" }
    }

    private infix fun Set<String>.xor(other: Set<String>): Set<String> = (this - other) + (other - this)
}

/** 当前生效的颜色令牌表（[CouliTheme] 提供；主题外默认浅色）。 */
internal val LocalCouliColorTokens = staticCompositionLocalOf { CouliThemeTokens.light }

/**
 * 组件清单 §7 的尺寸令牌（T4、T6–T12）：令牌源 v0.4.0 起收录在 component.*，这里只按语义名读生成物
 * [DesignTokens.component]（dimension）/ [DesignTokens.componentNumber]（number），不写数值。
 */
@Immutable
object CouliComponentTokens {
    /** T4 数字角标高。 */
    val countBadgeSize: Dp = componentDp("count-badge.size")

    /** T6 表单输入框最小高（手机号框；紧凑控件仍用 control.min-height）。 */
    val fieldMinHeight: Dp = componentDp("field.min-height")

    /** T7 验证码格高。 */
    val codeCellHeight: Dp = componentDp("code-cell.height")

    /** T8 勾选框视觉直径与随字号放大的上限（登录 v2 LoginCheckboxSpec）。 */
    val checkboxSize: Dp = componentDp("checkbox.size")
    val CHECKBOX_MAX_SCALE: Float = DesignTokens.componentNumber.getValue("checkbox.max-scale").toFloat()

    /** T9 筛选胶囊视觉高（命中区另扩）。 */
    val chipHeight: Dp = componentDp("chip.height")

    /** T10 顶部标签页下划线。 */
    val tabIndicatorHeight: Dp = componentDp("tab-indicator.height")

    /** T11 三端已写死的尺寸。 */
    val iconSmall: Dp = componentDp("icon.small")
    val iconInline: Dp = componentDp("icon.inline")
    val iconMedium: Dp = componentDp("icon.medium")
    val tagMinHeight: Dp = componentDp("tag.min-height")
    val platformBadgeMinHeight: Dp = componentDp("platform-badge.min-height")
    val imageList: Dp = componentDp("image.list")
    val gridCellMinHeight: Dp = componentDp("grid-cell.min-height")

    /** T12 第三方登录圆钮直径与间距。 */
    val oauthSize: Dp = componentDp("oauth.size")
    val oauthGap: Dp = componentDp("oauth.gap")
}

/** component.*（type=dimension）→ dp；键为去掉 `component.` 前缀的点分路径。 */
internal fun componentDp(path: String): Dp = DesignTokens.component.getValue(path).dp
