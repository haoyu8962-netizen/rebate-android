@file:Suppress("MagicNumber") // 令牌表本身：色值与尺寸逐条对照令牌提案，不再抽常量

package com.couli.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.couli.generated.DesignTokens

/**
 * 浅 / 深两套颜色令牌（DS-02）：
 * - 浅色 = 生成的 [DesignTokens.colors]（令牌源 design-tokens.json，不在这里改值）+ 组件清单 §7 的新增颜色 T1–T5；
 * - 深色 = T13 深色提案（couli-runs/design-hifi/components/tokens/tokens-dark-proposal.css，P1，未进令牌源），
 *   每个叶子都有深色值，新增颜色按 tokens-new-proposal.css 的 `[data-theme="dark"]` 段。
 * 令牌源支持「同名多主题」后改为生成；在那之前这里是唯一写深色色值的地方（Ds02ColorDisciplineTest 只允许令牌定义文件出现色值）。
 */
@Immutable
internal object CouliThemeTokens {
    /** 清单 §7 新增颜色键（无 `color.` 前缀，与 [DesignTokens.colors] 同口径）。 */
    const val BUTTON_INVERSE_BACKGROUND = "button.inverse.background"
    const val BUTTON_INVERSE_TEXT = "button.inverse.text"
    const val BORDER_ACTIVE = "border.active"
    const val OVERLAY_SCRIM = "overlay.scrim"
    const val BADGE_BACKGROUND = "badge.background"
    const val CONTROL_CHECKED = "control.checked"

    /**
     * M3 Fixed 系列角色（primary / secondary / tertiary 的 Fixed、FixedDim、on…Fixed、on…FixedVariant）的令牌键。
     * 清单 §7 未列（实现方补，待令牌源收录）；M3 定义 Fixed = 浅 / 深两态取同一色，所以两套取同值。
     * 取值依据：本主题三组容器色都映射 brand.subtle / brand.emphasis，Fixed 取它们的**浅色**值——
     * 底 #FBEFE8（浅色 brand.subtle），字 #A83B1F（浅色 brand.emphasis，对比 5.6:1）；令牌源没有更深一档的品牌浅底，
     * Dim 先与 Fixed 同值、on…FixedVariant 先与 on…Fixed 同值。现有组件都不读这四个角色，只防系统控件冒出 M3 默认色。
     */
    const val FIXED_CONTAINER = "fixed.container"
    const val FIXED_CONTAINER_DIM = "fixed.container-dim"
    const val FIXED_ON_CONTAINER = "fixed.on-container"
    const val FIXED_ON_CONTAINER_VARIANT = "fixed.on-container-variant"

    /** 浅色：令牌源 + T1–T5。 */
    val light: Map<String, Color> =
        linkedMapOf<String, Color>().apply {
            DesignTokens.colors.forEach { (path, hex) -> put(path, parseHexColor(hex)) }
            // T1 狸橙实底卡内按钮：白底 + #A83B1F（GUIDE §7.1）
            put(BUTTON_INVERSE_BACKGROUND, Color(0xFFFFFFFF))
            put(BUTTON_INVERSE_TEXT, Color(0xFFA83B1F))
            // T2 输入中 / 验证码当前格 2px 描边（深色，取代绿色）
            put(BORDER_ACTIVE, Color(0xFF28251F))
            // T3 弹层遮罩 rgba(40, 37, 31, 0.4)
            put(OVERLAY_SCRIM, Color(0x6628251F))
            // T4 角标底、T5 选中填充：与 brand.primary 同值，单列便于深色单独调
            put(BADGE_BACKGROUND, Color(0xFFC44820))
            put(CONTROL_CHECKED, Color(0xFFC44820))
            putFixed()
        }

    /** 深色（T13 提案）：键集合与 [light] 完全相同。 */
    val dark: Map<String, Color> =
        linkedMapOf(
            "brand.primary" to Color(0xFFC44820),
            "brand.emphasis" to Color(0xFFF0956B),
            "brand.secondary" to Color(0xFF6CC49F),
            "brand.subtle" to Color(0xFF3A2219),
            "background.canvas" to Color(0xFF171513),
            "background.admin-canvas" to Color(0xFF171513),
            "background.surface" to Color(0xFF24211E),
            "background.muted" to Color(0xFF2F2B27),
            "background.placeholder" to Color(0xFF36312C),
            "text.primary" to Color(0xFFF2EEE6),
            "text.secondary" to Color(0xFFB5ACA2),
            "text.inverse" to Color(0xFFFFFFFF),
            "text.link" to Color(0xFFF0956B),
            "price.text" to Color(0xFFF2EEE6),
            "rebate.text" to Color(0xFFF29A6E),
            "rebate.background" to Color(0xFF3A2219),
            "rebate.solid.background" to Color(0xFFC44820),
            "rebate.solid.text" to Color(0xFFFFFFFF),
            "status.pending.text" to Color(0xFFB5ACA2),
            "status.pending.background" to Color(0xFF2F2B27),
            "status.success.text" to Color(0xFF6CC49F),
            "status.success.background" to Color(0xFF16302A),
            "status.warning.text" to Color(0xFFE3B65C),
            "status.warning.background" to Color(0xFF33290F),
            "status.error.text" to Color(0xFFF28DA4),
            "status.error.background" to Color(0xFF3B1A22),
            "border.subtle" to Color(0xFF3A3530),
            "border.control" to Color(0xFF8C8278),
            "focus.ring" to Color(0xFF5DBB97),
            "source.text" to Color(0xFFB5ACA2),
            "source.background" to Color(0xFF24211E),
            "source.border" to Color(0xFF3A3530),
            "button.primary.default.background" to Color(0xFFC44820),
            "button.primary.default.text" to Color(0xFFFFFFFF),
            "button.primary.pressed.background" to Color(0xFFA83B1F),
            "button.primary.pressed.text" to Color(0xFFFFFFFF),
            "button.primary.disabled.background" to Color(0xFF36312C),
            "button.primary.disabled.text" to Color(0xFFA39A90),
            // T1 深色下仍白底
            BUTTON_INVERSE_BACKGROUND to Color(0xFFFFFFFF),
            BUTTON_INVERSE_TEXT to Color(0xFFA83B1F),
            BORDER_ACTIVE to Color(0xFFF2EEE6),
            // rgba(0, 0, 0, 0.55)
            OVERLAY_SCRIM to Color(0x8C000000),
            // 深色也用狸橙：白字 4.89:1
            BADGE_BACKGROUND to Color(0xFFC44820),
            CONTROL_CHECKED to Color(0xFFC44820),
        ).apply { putFixed() }

    /** Fixed 系列浅 / 深同值（见 [FIXED_CONTAINER]）。 */
    private fun MutableMap<String, Color>.putFixed() {
        put(FIXED_CONTAINER, Color(0xFFFBEFE8))
        put(FIXED_CONTAINER_DIM, Color(0xFFFBEFE8))
        put(FIXED_ON_CONTAINER, Color(0xFFA83B1F))
        put(FIXED_ON_CONTAINER_VARIANT, Color(0xFFA83B1F))
    }

    init {
        check(light.keys == dark.keys) { "浅 / 深令牌键不一致：${light.keys xor dark.keys}" }
    }

    private infix fun Set<String>.xor(other: Set<String>): Set<String> = (this - other) + (other - this)
}

/** 当前生效的颜色令牌表（[CouliTheme] 提供；主题外默认浅色）。 */
internal val LocalCouliColorTokens = staticCompositionLocalOf { CouliThemeTokens.light }

/**
 * 组件清单 §7 新增的尺寸令牌（T4、T6–T12），先以常量落在组件库，令牌源补齐后改读生成物（不改 design-tokens.json）。
 */
@Immutable
object CouliComponentTokens {
    /** T4 数字角标高。 */
    val countBadgeSize: Dp = 18.dp

    /** T6 表单输入框最小高（手机号框 52；紧凑控件仍用 control.min-height 44）。 */
    val fieldMinHeight: Dp = 52.dp

    /** T7 验证码格高。 */
    val codeCellHeight: Dp = 56.dp

    /** T8 勾选框视觉直径与随字号放大的上限（登录 v2 LoginCheckboxSpec）。 */
    val checkboxSize: Dp = 18.dp
    const val CHECKBOX_MAX_SCALE: Float = 1.5f

    /** T9 筛选胶囊视觉高（命中区仍 48）。 */
    val chipHeight: Dp = 32.dp

    /** T10 顶部标签页下划线。 */
    val tabIndicatorHeight: Dp = 2.dp

    /** T11 三端已写死的尺寸。 */
    val iconSmall: Dp = 14.dp
    val iconInline: Dp = 16.dp
    val iconMedium: Dp = 20.dp
    val tagMinHeight: Dp = 22.dp
    val platformBadgeMinHeight: Dp = 20.dp
    val imageList: Dp = 96.dp
    val gridCellMinHeight: Dp = 72.dp

    /** T12 第三方登录圆钮直径与间距。 */
    val oauthSize: Dp = 48.dp
    val oauthGap: Dp = 32.dp
}
