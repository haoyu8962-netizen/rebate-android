package com.couli.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.couli.generated.DesignTokens

/**
 * 组件尺寸。令牌里有的直接取令牌；令牌没有、只在 GUIDE 里给出的尺寸（图标 14 / 16、缩略图 96 等）用令牌间距组合出来，
 * 组合不出来的写成命名常量并注明出处。
 */
@Immutable
object CouliSizes {
    /** 最小命中区（interaction.target.android = 48）。 */
    val touchTarget: Dp = CouliInteraction.minTouchTarget

    /** 设计稿视觉高度 44（interaction.target.minimum）；Android 命中区仍扩到 [touchTarget]。 */
    val visualTarget: Dp = DesignTokens.interactionTarget.getValue("minimum").dp

    /** 图标：24 = space6，20 = space5，16 = space4；14 是 GUIDE §3 标签内的小图标（令牌没有）。 */
    val iconLarge: Dp = CouliSpacing.space6
    val iconMedium: Dp = CouliSpacing.space5
    val iconSmall: Dp = CouliSpacing.space4
    val iconTiny: Dp = ICON_TINY.dp

    /** 列表卡左图 96 = space16 + space8。 */
    val listThumbnail: Dp = CouliSpacing.space16 + CouliSpacing.space8

    /** 宫格格高 ≥ 72 = space16 + space2（GUIDE §7.2）。 */
    val gridCellMinHeight: Dp = CouliSpacing.space16 + CouliSpacing.space2

    /** 标签（胶囊）高 22、来源标签高 20（GUIDE §3 / §7.2.1）。 */
    val tagHeight: Dp = TAG_HEIGHT.dp
    val badgeHeight: Dp = CouliSpacing.space5

    /** 空态占位块 96。 */
    val emptyIllustration: Dp = listThumbnail

    /** 半屏把手 36 × 4。 */
    val sheetHandleWidth: Dp = CouliSpacing.space8 + CouliSpacing.space1
    val sheetHandleHeight: Dp = CouliSpacing.space1

    /** 卡片阴影（GUIDE §2：0 2 8 rgba(40,37,31,0.06)），Compose 用 elevation 近似。 */
    val cardElevation: Dp = CouliSpacing.space1 / 2

    /** 分隔线、描边 1。 */
    val hairline: Dp = 1.dp

    /** 宫格最小单元宽（4 列宫格在 200% 字号下改 2 列的判断阈值，GUIDE 未给，取 72 与格高一致）。 */
    val gridCellMinWidth: Dp = gridCellMinHeight

    private const val ICON_TINY = 14
    private const val TAG_HEIGHT = 22
}

/** 透明度与字体特性常量。 */
internal object CouliAlpha {
    /** 遮罩 rgba(40, 37, 31, 0.4)。 */
    const val SCRIM = 0.4f

    /** 卡片阴影颜色透明度 0.06（GUIDE §2）。 */
    const val CARD_SHADOW = 0.06f

    /** 200% 字号：两列数字格、宫格一律纵排（adaptation-a11y-spec §2）。 */
    const val LARGE_FONT_SCALE = 2f

    /** 等宽数字。 */
    const val TABULAR_NUMS = "tnum"
}

/** 常用文字样式（在 6 级令牌上调字重 / 等宽数字）。 */
@Immutable
object CouliTextStyles {
    val captionMedium: TextStyle = CouliTypography.caption.copy(fontWeight = FontWeight.Medium)
    val captionSemibold: TextStyle = CouliTypography.caption.copy(fontWeight = FontWeight.SemiBold)
    val footnoteMedium: TextStyle = CouliTypography.footnote.copy(fontWeight = FontWeight.Medium)
    val footnoteSemibold: TextStyle = CouliTypography.footnote.copy(fontWeight = FontWeight.SemiBold)
    val bodySemibold: TextStyle = CouliTypography.body.copy(fontWeight = FontWeight.SemiBold)
    val bodyMedium: TextStyle = CouliTypography.body.copy(fontWeight = FontWeight.Medium)
    val titleSemibold: TextStyle = CouliTypography.title

    /** 等宽数字版本（金额一律等宽，GUIDE §2）。 */
    fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = CouliAlpha.TABULAR_NUMS)

    val captionAmount: TextStyle = CouliTypography.caption.tabular()
    val amountListAi: TextStyle = CouliTypography.body.copy(fontWeight = FontWeight.Bold).tabular()
    val amountList: TextStyle = CouliTypography.title.copy(fontWeight = FontWeight.Bold).tabular()
    val amountDisplay: TextStyle = CouliTypography.display.tabular()
    val amountTitle: TextStyle = CouliTypography.title.copy(fontWeight = FontWeight.SemiBold).tabular()
    val amountBody: TextStyle = bodySemibold.tabular()
}

/**
 * 白卡片：圆角 16、卡片阴影、白底。不裁切内容：卡内控件的焦点环（控件外 2 + 2）贴近卡片边缘时也完整可见
 * （adaptation-a11y-spec §8）；卡内图片等需要圆角的元素各自裁切。
 */
fun Modifier.couliCard(
    background: Color = CouliColors.backgroundSurface,
    shape: Shape = RoundedCornerShape(CouliRadius.card),
): Modifier =
    this
        .shadow(
            elevation = CouliSizes.cardElevation,
            shape = shape,
            clip = false,
            ambientColor = CouliColors.textPrimary.copy(alpha = CouliAlpha.CARD_SHADOW),
            spotColor = CouliColors.textPrimary.copy(alpha = CouliAlpha.CARD_SHADOW),
        ).background(background, shape)

/** 焦点令牌：focus.width（线宽）、focus.offset（与控件的间距），颜色 focus.ring（[CouliColors.focusRing]）。 */
@Immutable
object CouliFocus {
    val width: Dp = DesignTokens.focus.getValue("width").dp
    val offset: Dp = DesignTokens.focus.getValue("offset").dp
}

/**
 * 控件此刻要不要画焦点环：有焦点、且是键盘或辅助输入（非触摸，InputMode.Keyboard）时才画（adaptation-a11y-spec §8）。
 * 传入控件 clickable / selectable 用的同一个 [interactionSource]。
 */
@Composable
fun focusRingVisible(interactionSource: InteractionSource): Boolean {
    val focused by interactionSource.collectIsFocusedAsState()
    return focused && LocalInputModeManager.current.inputMode == InputMode.Keyboard
}

/**
 * 焦点环：控件外 [CouliFocus.offset] 处画 [CouliFocus.width] 实线（focus.ring），圆角跟随控件（控件圆角 + 外距）。
 * 放在 clip 之前，环画在控件外侧不被自身裁切；[visible] 用 [focusRingVisible] 算。
 */
fun Modifier.couliFocusRing(
    visible: Boolean,
    cornerRadius: Dp,
): Modifier =
    if (!visible) {
        this
    } else {
        this.drawWithContent {
            drawContent()
            val stroke = CouliFocus.width.toPx()
            val inset = CouliFocus.offset.toPx() + stroke / 2
            val radius = minOf(cornerRadius.toPx(), size.minDimension / 2) + inset
            drawRoundRect(
                color = CouliColors.focusRing,
                topLeft = Offset(-inset, -inset),
                size = Size(size.width + inset * 2, size.height + inset * 2),
                cornerRadius = CornerRadius(radius, radius),
                style = Stroke(width = stroke),
            )
        }
    }

/**
 * 「左边一块 + 右边一块」：放得下时同一行、右块靠右；放不下时右块整块换到下一行并靠右
 * （等同 CSS `flex-wrap: wrap` + `margin-left: auto`，GUIDE §6 / §7.2.1）。
 */
@Composable
fun EndWrapRow(
    modifier: Modifier = Modifier,
    horizontalGap: Dp = CouliSpacing.space2,
    verticalGap: Dp = CouliSpacing.space1,
    start: @Composable () -> Unit,
    end: @Composable () -> Unit,
) {
    Layout(
        contents = listOf(start, end),
        modifier = modifier,
    ) { (startMeasurables, endMeasurables), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val hGap = horizontalGap.roundToPx()
        val vGap = verticalGap.roundToPx()
        val starts = startMeasurables.map { it.measure(loose) }
        val ends = endMeasurables.map { it.measure(loose) }
        val startSize = starts.boxSize()
        val endSize = ends.boxSize()
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else startSize.first + hGap + endSize.first
        val sameLine = startSize.first == 0 || startSize.first + hGap + endSize.first <= width
        val contentHeight = if (sameLine) maxOf(startSize.second, endSize.second) else startSize.second + vGap + endSize.second
        val height = maxOf(contentHeight, constraints.minHeight)
        layout(width, height) {
            val endTop = if (sameLine) 0 else startSize.second + vGap
            val rowHeight = if (sameLine) height else null
            starts.forEach { it.place(0, rowHeight?.let { h -> (h - it.height) / 2 } ?: 0) }
            ends.forEach { it.place(width - it.width, rowHeight?.let { h -> (h - it.height) / 2 } ?: endTop) }
        }
    }
}

private fun List<Placeable>.boxSize(): Pair<Int, Int> = (maxOfOrNull { it.width } ?: 0) to (maxOfOrNull { it.height } ?: 0)

/** 标签（胶囊）的配色（GUIDE §3 / §7.1）。 */
enum class CouliTagTone {
    /** 白底描边（来源、优惠标签：有券、预售、规格以下单页为准）。 */
    OUTLINE,

    /** 中性灰底（规格待确认、预估中等）。 */
    NEUTRAL,

    /** 已选：品牌浅底 + 强调字。 */
    SELECTED,
    SUCCESS,
    WARNING,
    ERROR,
}

/** 标签（胶囊）：高 22，12/18 字，可带 14 图标；状态不只靠颜色（带图标与文字）。 */
@Composable
fun CouliTag(
    text: String,
    modifier: Modifier = Modifier,
    tone: CouliTagTone = CouliTagTone.OUTLINE,
    icon: ImageVector? = null,
) {
    val (background, content) =
        when (tone) {
            CouliTagTone.OUTLINE -> CouliColors.backgroundSurface to CouliColors.textPrimary
            CouliTagTone.NEUTRAL -> CouliColors.statusPendingBackground to CouliColors.statusPendingText
            CouliTagTone.SELECTED -> CouliColors.brandSubtle to CouliColors.brandEmphasis
            CouliTagTone.SUCCESS -> CouliColors.statusSuccessBackground to CouliColors.statusSuccessText
            CouliTagTone.WARNING -> CouliColors.statusWarningBackground to CouliColors.statusWarningText
            CouliTagTone.ERROR -> CouliColors.statusErrorBackground to CouliColors.statusErrorText
        }
    val shape = RoundedCornerShape(CouliRadius.pill)
    Row(
        modifier =
            modifier
                .heightIn(min = CouliSizes.tagHeight)
                .clip(shape)
                .background(background)
                .then(
                    if (tone == CouliTagTone.OUTLINE) Modifier.border(CouliSizes.hairline, CouliColors.borderSubtle, shape) else Modifier,
                ).padding(horizontal = CouliSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, tint = content, modifier = Modifier.size(CouliSizes.iconTiny))
        }
        Text(text = text, style = CouliTypography.caption, color = content)
    }
}
