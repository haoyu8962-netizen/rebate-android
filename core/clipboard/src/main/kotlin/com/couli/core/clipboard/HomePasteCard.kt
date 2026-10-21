package com.couli.core.clipboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonSize
import com.couli.core.designsystem.CouliCategoryIcons
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliRadius
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles

/**
 * 首页常驻粘贴卡（Main-v3 / HomeGuest-v3，APP-11v A5 / G-V03）：品牌浅底圆角 16、高约 132；左侧标题 17/600（卡内标题，读屏标题角色）+ 一个主按钮
 * （Android 自绘，BR-ID-16 文字「粘贴查返利」+ 粘贴图标），右侧示意插图「链接 → 带预估返的卡」（装饰，读屏跳过）。无副标题。
 * 卡本身不可点，只有按钮承接点击与禁用 / 在途置灰（42901、共享 busy）；[buttonModifier] 给按钮挂测试标记。
 * 大字号或窄屏放不下插图时只保留标题与按钮（03 §10.4：不截断按钮文字）。
 */
@Composable
fun HomePasteCard(
    title: String,
    actionText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    buttonModifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(CouliRadius.card)
    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = PASTE_CARD_MIN_HEIGHT.dp)
                .clip(shape)
                .background(CouliColors.brandSubtle, shape)
                .testTag(ClipboardUi.HOME_PASTE_CARD),
    ) {
        val fontScale = LocalDensity.current.fontScale
        val showIllustration = fontScale <= ILLUSTRATION_MAX_FONT_SCALE && maxWidth >= ILLUSTRATION_MIN_CARD_WIDTH.dp
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(
                            start = CouliSpacing.space4,
                            end = CouliSpacing.space2,
                            top = CouliSpacing.space5 + CouliSpacing.space1,
                            bottom = CouliSpacing.space4,
                        ),
                verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
            ) {
                Text(
                    text = title,
                    style = CouliTextStyles.bodySemibold,
                    color = CouliColors.textPrimary,
                    modifier = Modifier.semantics { heading() },
                )
                CouliButton(
                    text = actionText,
                    onClick = onClick,
                    size = CouliButtonSize.COMPACT,
                    enabled = enabled,
                    leadingIcon = CouliCategoryIcons.paste,
                    modifier = buttonModifier,
                )
            }
            if (showIllustration) {
                PasteIllustration(
                    Modifier
                        .padding(top = ILLUSTRATION_INSET.dp, end = ILLUSTRATION_INSET.dp)
                        .size(ILLUSTRATION_WIDTH.dp, ILLUSTRATION_HEIGHT.dp)
                        .align(Alignment.Top),
                )
            }
        }
    }
}

/**
 * 示意插图（照 Main-v3 粘贴卡 132×112 的 SVG 逐条转成矢量路径；颜色全部取令牌，随浅 / 深主题重建）：上方「链接」卡 → 向下箭头 → 下方带「预估返」
 * 标签的商品卡。纯装饰：Image 不给描述，不建任何文字 / 点击语义，只保留测试标记。画板标签里的「预估返」三个字画成一条标签色块（页面不写死中文）。
 * 画板里的商品绿色（#5FA36F）没有对应令牌，取 status.success.text。
 */
@Composable
private fun PasteIllustration(modifier: Modifier) {
    val colors =
        IllustrationColors(
            surface = CouliColors.backgroundSurface,
            line = CouliColors.borderSubtle,
            accent = CouliColors.brandEmphasis,
            arrow = CouliColors.borderControl,
            thumb = CouliColors.backgroundPlaceholder,
            goods = CouliColors.statusSuccessText,
            tag = CouliColors.rebateBackground,
        )
    val vector = remember(colors) { pasteIllustration(colors) }
    Image(
        painter = rememberVectorPainter(vector),
        contentDescription = null,
        modifier = modifier.testTag(ClipboardUi.HOME_PASTE_ILLUSTRATION),
    )
}

private data class IllustrationColors(
    val surface: Color,
    val line: Color,
    val accent: Color,
    val arrow: Color,
    val thumb: Color,
    val goods: Color,
    val tag: Color,
)

private fun pasteIllustration(c: IllustrationColors): ImageVector {
    val builder =
        ImageVector.Builder(
            name = "PasteIllustration",
            defaultWidth = ILLUSTRATION_WIDTH.dp,
            defaultHeight = ILLUSTRATION_HEIGHT.dp,
            viewportWidth = ILLUSTRATION_WIDTH,
            viewportHeight = ILLUSTRATION_HEIGHT,
        )

    fun fill(
        color: Color,
        data: String,
        alpha: Float = 1f,
    ) = builder.addPath(pathData = PathParser().parsePathString(data).toNodes(), fill = SolidColor(color), fillAlpha = alpha)

    fun stroke(
        color: Color,
        data: String,
        width: Float = STROKE,
    ) = builder.addPath(
        pathData = PathParser().parsePathString(data).toNodes(),
        stroke = SolidColor(color),
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )
    fill(c.surface, "M18 56a58 58 0 1 0 116 0a58 58 0 1 0 -116 0Z", alpha = HALO_ALPHA)
    LINK_CARD.let {
        fill(c.surface, it)
        stroke(c.line, it, width = HAIRLINE)
    }
    builder.group(translationX = LINK_OFFSET_X, translationY = LINK_OFFSET_Y) {
        stroke(c.accent, "M10.5 13.5a3.5 3.5 0 0 0 5 0l3-3a3.5 3.5 0 0 0-5-5l-.8.8")
        stroke(c.accent, "M13.5 10.5a3.5 3.5 0 0 0-5 0l-3 3a3.5 3.5 0 0 0 5 5l.8-.8")
    }
    fill(c.line, "M48.5 20H99.5A2.5 2.5 0 0 1 99.5 25H48.5A2.5 2.5 0 0 1 48.5 20Z")
    fill(c.line, "M48.5 30H79.5A2.5 2.5 0 0 1 79.5 35H48.5A2.5 2.5 0 0 1 48.5 30Z")
    stroke(c.arrow, "M62 50v10M58 56l4 4 4-4")
    GOODS_CARD.let {
        fill(c.surface, it)
        stroke(c.line, it, width = HAIRLINE)
    }
    fill(c.thumb, "M24 72H38A6 6 0 0 1 44 78V92A6 6 0 0 1 38 98H24A6 6 0 0 1 18 92V78A6 6 0 0 1 24 72Z")
    fill(c.surface, "M26 78H36A2 2 0 0 1 38 80V92A2 2 0 0 1 36 94H26A2 2 0 0 1 24 92V80A2 2 0 0 1 26 78Z")
    fill(c.goods, "M24 88h14v6h-14Z")
    fill(c.line, "M52.5 74H101.5A2.5 2.5 0 0 1 101.5 79H52.5A2.5 2.5 0 0 1 52.5 74Z")
    fill(c.tag, "M57.5 84H110.5A7.5 7.5 0 0 1 110.5 99H57.5A7.5 7.5 0 0 1 57.5 84Z")
    stroke(c.accent, "M54.5 91.5a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0 -7 0Z", width = TAG_STROKE)
    stroke(c.accent, "M58 89.5v2l1.2.8", width = TAG_STROKE)
    fill(c.accent, "M67.5 89H90.5A2.5 2.5 0 0 1 90.5 94H67.5A2.5 2.5 0 0 1 67.5 89Z", alpha = TAG_TEXT_ALPHA)
    return builder.build()
}

private const val LINK_CARD = "M20 10H104A10 10 0 0 1 114 20V36A10 10 0 0 1 104 46H20A10 10 0 0 1 10 36V20A10 10 0 0 1 20 10Z"
private const val GOODS_CARD = "M20 64H116A10 10 0 0 1 126 74V96A10 10 0 0 1 116 106H20A10 10 0 0 1 10 96V74A10 10 0 0 1 20 64Z"
private const val LINK_OFFSET_X = 18f
private const val LINK_OFFSET_Y = 16f
private const val PASTE_CARD_MIN_HEIGHT = 132
private const val ILLUSTRATION_WIDTH = 132f
private const val ILLUSTRATION_HEIGHT = 112f
private const val ILLUSTRATION_INSET = 10
private const val ILLUSTRATION_MAX_FONT_SCALE = 1.3f
private const val ILLUSTRATION_MIN_CARD_WIDTH = 300
private const val STROKE = 1.75f
private const val HAIRLINE = 1f
private const val TAG_STROKE = 1.25f
private const val HALO_ALPHA = 0.55f
private const val TAG_TEXT_ALPHA = 0.6f
