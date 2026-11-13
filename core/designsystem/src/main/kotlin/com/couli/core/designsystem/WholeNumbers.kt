package com.couli.core.designsystem

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 数字串在框内放不下时的状态（FU-AND-NUM-WRAP，D10-1265）：[scrollable] 框内横滑（滚动范围 > 0）；[fadeEnd] 末端正在画渐隐（还能往后滑）；
 * [indicator] 文字下方正在画横向滚动指示器。三项都从 [WholeNumberText] 的真实滚动状态推出，与绘制同源，不是调用方声明的。
 */
@Immutable
data class AmountOverflow(
    val scrollable: Boolean,
    val fadeEnd: Boolean,
    val indicator: Boolean,
)

/** [WholeNumberText] 挂在自身外层的语义（读屏不读；供测试与无障碍工具核对横滑提示）。 */
object WholeNumberSemantics {
    val Overflow: SemanticsPropertyKey<AmountOverflow> = SemanticsPropertyKey("AmountOverflow")
}

private var SemanticsPropertyReceiver.amountOverflow by WholeNumberSemantics.Overflow

/**
 * 整段数字件（判据 1 / D10-1265 的共用金额件）：金额、订单号、脱敏账号、流水号这类数字串**任何字号和宽度下都不从中间断开，也不缩字**。
 * - 文字按给定宽度正常排版：只要最长的不可断段（数字串）一行放得下，就照常排（含「订单号 {号码}」在空格处整段换到下一行），与普通 Text 完全一样；
 * - 最长的不可断段在给定宽度里一行也放不下时，整段改为单行、在框内横向滚动（框架的 horizontalScroll，滚动范围按真实内容宽度算），初始停在开头；
 *   这时末端画 [FADE] 宽的渐隐（还能往后滑时），文字下方画横向滚动指示器；
 * - 字号、字距、缩放一律不动；文字始终是一个完整 Text，读屏读完整内容（调用方可在 [textModifier] 上给读屏写法）。
 * 「先换行或上下排、独占一行，仍放不下才横滑」由所在容器保证：容器按本件的最小固有宽（= 数字串整段宽）决定是否让它独占一行，见 [KeyValueRow]。
 */
@Composable
fun WholeNumberText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    textModifier: Modifier = Modifier,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    WholeNumberLayout(style, color, modifier) {
        Text(text = text, style = style, color = color, textAlign = textAlign, onTextLayout = onTextLayout, modifier = textModifier)
    }
}

/** 带样式片段的 [WholeNumberText]（如估算句里加粗放大的到账金额），规则相同。 */
@Composable
fun WholeNumberText(
    text: AnnotatedString,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    textModifier: Modifier = Modifier,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    WholeNumberLayout(style, color, modifier) {
        Text(text = text, style = style, color = color, textAlign = textAlign, onTextLayout = onTextLayout ?: {}, modifier = textModifier)
    }
}

/**
 * [WholeNumberText] 的排版与横滑外壳：[content] 是那一个完整的 Text。[wholeLine] 为 true 时整串是一个不可断段（金额段 [AmountText]：
 * 单行不折行，连破折号处也不断），按整串宽判定放不下、报最小固有宽。
 */
@Composable
internal fun WholeNumberLayout(
    style: TextStyle,
    color: Color,
    modifier: Modifier,
    wholeLine: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scroll = rememberScrollState()
    val wrap = remember { WrapState() }
    val overflow = remember(scroll, wrap) { NumberOverflowState(scroll, wrap) }
    val scrollable = overflow.scrollable
    val fading = overflow.fadeEnd
    val tint = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
    val fade = with(LocalDensity.current) { FADE.toPx() }
    Layout(
        content = {
            Layout(
                content = content,
                // 只有数字串整段放不下视口时才挂框架的横滑（放得下时与普通 Text 一样，没有滚动语义）
                modifier =
                    if (wrap.overflowing) {
                        Modifier.drawWithContent { drawFadingEnd(overflow, fade) }.horizontalScroll(scroll, overscrollEffect = null)
                    } else {
                        Modifier
                    },
                measurePolicy = remember(wrap, wholeLine) { WrapPolicy(wrap, wholeLine) },
            )
            if (scrollable) ScrollIndicator(scroll, tint)
        },
        modifier = modifier.semantics { amountOverflow = AmountOverflow(scrollable, fading, indicator = scrollable) },
        measurePolicy = remember(wrap) { ViewportPolicy(wrap) },
    )
}

/** 末端渐隐：还能往后滑时，把视口最后 [fadePx] 宽的内容按从实到透明的线性渐变遮掉（DstIn，底色透出来）；不滑时原样画。 */
private fun ContentDrawScope.drawFadingEnd(
    overflow: NumberOverflowState,
    fadePx: Float,
) {
    if (!overflow.fadeEnd) {
        drawContent()
        return
    }
    val span = minOf(fadePx, size.width / FADE_MAX_SHARE)
    drawIntoCanvas { canvas ->
        canvas.saveLayer(Rect(Offset.Zero, size), Paint())
        drawContent()
        drawRect(
            brush = Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - span, endX = size.width),
            topLeft = Offset(size.width - span, 0f),
            size = Size(span, size.height),
            blendMode = BlendMode.DstIn,
        )
        canvas.restore()
    }
}

/** 横向滚动指示器：文字下方 [INDICATOR_GAP] 处一条 [INDICATOR_HEIGHT] 高的轨道 + 按可视比例与滚动位置画的滑块（装饰，无语义）。 */
@Composable
private fun ScrollIndicator(
    scroll: ScrollState,
    tint: Color,
) {
    val min = with(LocalDensity.current) { INDICATOR_MIN_THUMB.toPx() }
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = INDICATOR_GAP)
                .height(INDICATOR_HEIGHT)
                .drawBehind {
                    val radius = CornerRadius(size.height / 2, size.height / 2)
                    drawRoundRect(tint.copy(alpha = TRACK_ALPHA), cornerRadius = radius)
                    val max = scroll.maxValue.coerceAtLeast(1)
                    val thumb = (size.width * size.width / (size.width + max)).coerceIn(minOf(min, size.width), size.width)
                    val x = (size.width - thumb) * scroll.value / max
                    drawRoundRect(
                        tint.copy(alpha = THUMB_ALPHA),
                        topLeft = Offset(x, 0f),
                        size = Size(thumb, size.height),
                        cornerRadius = radius,
                    )
                },
    )
}

/** 横滑状态：已挂横滑且滚动范围（框架按真实内容宽度算出的 maxValue）> 0 才算横滑；首次测量前（maxValue 仍是初值）不算。 */
@Stable
private class NumberOverflowState(
    private val scroll: ScrollState,
    private val wrap: WrapState,
) {
    val scrollable: Boolean by derivedStateOf { wrap.overflowing && scroll.maxValue in 1 until Int.MAX_VALUE }
    val fadeEnd: Boolean by derivedStateOf { scrollable && scroll.value < scroll.maxValue }
}

/**
 * 外层测量写、内层测量读的排版状态：[max] 视口可用宽（同一次测量里先写后读）；[overflowing] 数字串整段放不下视口（由外层按真实约束判定后写入，
 * 改变时重组，挂上 / 去掉横滑）。
 */
@Stable
private class WrapState {
    var max: Int = Constraints.Infinity
    var overflowing: Boolean by mutableStateOf(false)
}

/**
 * 外层：记下可用宽给内层；数字串整段放不下时内层改单行。宽度不受限（父级横向不设上限）时按内容宽给定上限，避免滚动容器在无限宽下测量。
 * 固有尺寸直接取文字的（最小固有宽 = 最长的不可断段，即数字串整段宽），供容器决定是否让本件独占一行。
 */
private class ViewportPolicy(
    private val wrap: WrapState,
) : MeasurePolicy {
    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        val viewport = measurables[0]
        val bounded =
            if (constraints.hasBoundedWidth) {
                constraints
            } else {
                constraints.copy(maxWidth = viewport.maxIntrinsicWidth(Constraints.Infinity).coerceAtLeast(constraints.minWidth))
            }
        wrap.max = bounded.maxWidth
        val overflowing = viewport.minIntrinsicWidth(Constraints.Infinity) > bounded.maxWidth
        if (wrap.overflowing != overflowing) wrap.overflowing = overflowing
        val box = viewport.measure(bounded)
        val bar = measurables.getOrNull(1)?.measure(Constraints.fixedWidth(box.width))
        return layout(box.width, box.height + (bar?.height ?: 0)) {
            box.place(0, 0)
            bar?.place(0, box.height)
        }
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(
        measurables: List<IntrinsicMeasurable>,
        height: Int,
    ): Int = measurables[0].minIntrinsicWidth(height)

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(
        measurables: List<IntrinsicMeasurable>,
        height: Int,
    ): Int = measurables[0].maxIntrinsicWidth(height)

    override fun IntrinsicMeasureScope.minIntrinsicHeight(
        measurables: List<IntrinsicMeasurable>,
        width: Int,
    ): Int = measurables[0].minIntrinsicHeight(width)

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(
        measurables: List<IntrinsicMeasurable>,
        width: Int,
    ): Int = measurables[0].maxIntrinsicHeight(width)
}

/**
 * 滚动容器内层（horizontalScroll 给的宽度上限是无限）：最长的不可断段放得下视口宽时，按视口宽排版（照常折行、对齐）；放不下时不设上限，
 * 单行排出整段，由外面的 horizontalScroll 框内横滑。
 */
private class WrapPolicy(
    private val wrap: WrapState,
    private val wholeLine: Boolean,
) : MeasurePolicy {
    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        val text = measurables[0]
        val viewport = wrap.max
        val natural = text.maxIntrinsicWidth(Constraints.Infinity)
        val unbreakable = if (wholeLine) natural else text.minIntrinsicWidth(Constraints.Infinity)
        val fits = viewport == Constraints.Infinity || unbreakable <= viewport
        // 文字一律按确定宽度排版（右对齐等也落在自身框内）：放得下时取「内容宽，最多视口宽」（容器给了确定宽时用它）；放不下时取整段单行宽
        val target = if (fits) minOf(natural, viewport) else natural
        val exact = maxOf(target, minOf(constraints.minWidth, viewport))
        val placeable = text.measure(Constraints(exact, exact, constraints.minHeight, constraints.maxHeight))
        // 刚判定放不下、横滑还没挂上的那一次测量：仍整段单行，只报视口宽（下一帧挂上横滑），绝不在数字中间折行
        val width = if (constraints.hasBoundedWidth) placeable.width.coerceAtMost(constraints.maxWidth) else placeable.width
        return layout(width, placeable.height) { placeable.place(0, 0) }
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(
        measurables: List<IntrinsicMeasurable>,
        height: Int,
    ): Int = if (wholeLine) measurables[0].maxIntrinsicWidth(height) else measurables[0].minIntrinsicWidth(height)

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(
        measurables: List<IntrinsicMeasurable>,
        height: Int,
    ): Int = measurables[0].maxIntrinsicWidth(height)

    override fun IntrinsicMeasureScope.minIntrinsicHeight(
        measurables: List<IntrinsicMeasurable>,
        width: Int,
    ): Int = measurables[0].minIntrinsicHeight(width)

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(
        measurables: List<IntrinsicMeasurable>,
        width: Int,
    ): Int = measurables[0].maxIntrinsicHeight(width)
}

/**
 * 标签 + 值一行（明细键值行共用，判据 1「右列放不下时整行改成上下排」）。[content] 依次放两个子项：标签、值（值一般是 [WholeNumberText]）。
 * 值的最小固有宽（数字串整段宽）放得下标签右侧的剩余宽时，与改前的 Row（标签 + weight(1f) 的值）一样同一行、值占满剩余宽（对齐方式由值自己的
 * textAlign 定）；放不下时整行改上下排——标签在上、值在下独占整行（值仍放不下时由 [WholeNumberText] 框内横滑）。
 * [gap] 是同行时标签与值的间距，[stackGap] 是上下排时两行的间距；[centerVertically] 同行时垂直居中（否则顶对齐）。
 */
@Composable
fun KeyValueRow(
    modifier: Modifier = Modifier,
    gap: Dp = CouliSpacing.space3,
    stackGap: Dp = CouliSpacing.space1,
    centerVertically: Boolean = false,
    /** 同行时值按自身宽度贴右（改前「标签 weight(1f) + 值」的写法），而不是占满剩余宽。 */
    valueAtEnd: Boolean = false,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val space = gap.roundToPx()
        val labels = measurables.take(1)
        val values = measurables.drop(1)
        val title = labels.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val titleWidth = title.maxOfOrNull { it.width } ?: 0
        val rest = (width - titleWidth - space).coerceAtLeast(0)
        val need = values.maxOfOrNull { it.minIntrinsicWidth(Constraints.Infinity) } ?: 0
        if (!constraints.hasBoundedWidth || need <= rest) {
            val fill = if (valueAtEnd) Constraints(maxWidth = rest) else Constraints.fixedWidth(rest)
            val parts = values.map { it.measure(if (constraints.hasBoundedWidth) fill else Constraints()) }
            val end = width - (parts.maxOfOrNull { it.width } ?: 0)
            val valueX = if (valueAtEnd && constraints.hasBoundedWidth) end else titleWidth + space
            sameLine(title, parts, valueX, centerVertically, constraints)
        } else {
            stacked(title, values.map { it.measure(Constraints(maxWidth = width)) }, stackGap.roundToPx(), constraints)
        }
    }
}

/**
 * 带右侧尾件（如展开箭头）的数字行（判据 1 / D10-1265「先独占一行，仍放不下才横滑」）：[lead] 是含数字串的主内容，[badge] 跟在 [lead] 后、
 * 放不下时换到下一行靠右（同 [EndWrapRow]），[body] 是主内容下面的其余行（间距 [lineGap]），[trailing] 是右侧尾件（间距 [gap]）。
 * - [lead] 与 [body] 的最小固有宽（数字串整段宽）都放得下「整行 − 尾件」时：与改前的
 *   Row(CenterVertically){ Column(weight(1f)){ EndWrapRow(lead, badge); body }; trailing } 完全同样的排法（同宽、同位置）；
 * - 放不下时尾件让位：主内容占满整行，[badge] 与 [trailing] 排在 [lead] 下一行靠右，[body] 再往下；数字串独占整行仍放不下才由 [WholeNumberText] 横滑。
 */
@Composable
fun TrailingWrapRow(
    lead: @Composable () -> Unit,
    badge: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    body: @Composable () -> Unit = {},
    gap: Dp = CouliSpacing.space3,
    lineGap: Dp = CouliSpacing.space1,
    badgeGap: Dp = CouliSpacing.space2,
    badgeLineGap: Dp = CouliSpacing.space1,
) {
    Layout(contents = listOf(lead, badge, body, trailing), modifier = modifier) { parts, constraints ->
        val (leads, badges, bodies) = parts
        val width = constraints.maxWidth
        val space = gap.roundToPx()
        val tail = parts.last().map { it.measure(Constraints(maxWidth = width)) }
        val column = (width - space - (tail.maxOfOrNull { it.width } ?: 0)).coerceAtLeast(0)
        val need = (leads + bodies).maxOfOrNull { it.minIntrinsicWidth(Constraints.Infinity) } ?: 0
        val gaps = RowGaps(badgeGap.roundToPx(), badgeLineGap.roundToPx(), lineGap.roundToPx(), space)
        if (!constraints.hasBoundedWidth || need <= column) {
            besideTrailing(Slots(leads, badges, bodies, tail), column, gaps, constraints)
        } else {
            belowLead(Slots(leads, badges, bodies, tail), gaps, constraints)
        }
    }
}

private class Slots(
    val leads: List<Measurable>,
    val badges: List<Measurable>,
    val bodies: List<Measurable>,
    val tail: List<Placeable>,
)

private class RowGaps(
    val badge: Int,
    val badgeLine: Int,
    val line: Int,
    val trailing: Int,
)

/** [TrailingWrapRow] 尾件在右（改前排法）：主列宽 [column]，主列与尾件在行内垂直居中；主列内 lead / badge 同 [EndWrapRow]。 */
private fun MeasureScope.besideTrailing(
    slots: Slots,
    column: Int,
    gaps: RowGaps,
    constraints: Constraints,
): MeasureResult {
    val loose = Constraints(maxWidth = column)
    val lead = slots.leads.map { it.measure(loose) }
    val badge = slots.badges.map { it.measure(loose) }
    val body = slots.bodies.map { it.measure(loose) }
    val leadH = lead.maxHeight()
    val sameLine = lead.maxWidth() == 0 || lead.maxWidth() + gaps.badge + badge.maxWidth() <= column
    val headH = if (sameLine) maxOf(leadH, badge.maxHeight()) else leadH + gaps.badgeLine + badge.maxHeight()
    val columnH = headH + if (body.isEmpty()) 0 else gaps.line + body.maxHeight()
    val height = maxOf(columnH, slots.tail.maxHeight(), constraints.minHeight)
    val top = Alignment.CenterVertically.align(columnH, height)
    val inHead = { child: Placeable, wrapped: Int -> top + if (sameLine) (headH - child.height) / 2 else wrapped }
    return layout(constraints.maxWidth, height) {
        lead.forEach { it.place(0, inHead(it, 0)) }
        badge.forEach { it.place(column - it.width, inHead(it, leadH + gaps.badgeLine)) }
        body.forEach { it.place(0, top + headH + gaps.line) }
        slots.tail.forEach { it.place(constraints.maxWidth - it.width, Alignment.CenterVertically.align(it.height, height)) }
    }
}

private fun List<Placeable>.maxWidth(): Int = maxOfOrNull { it.width } ?: 0

private fun List<Placeable>.maxHeight(): Int = maxOfOrNull { it.height } ?: 0

/**
 * [TrailingWrapRow] 尾件让位：lead 占满整行；下一行 badge + 尾件靠右（垂直居中）；badge + 间距 + 尾件超过整行时再拆两行（badge 一行靠右、
 * 尾件下一行靠右），不出现负坐标；body 再往下。
 */
private fun MeasureScope.belowLead(
    slots: Slots,
    gaps: RowGaps,
    constraints: Constraints,
): MeasureResult {
    val width = constraints.maxWidth
    val full = Constraints(maxWidth = width)
    val lead = slots.leads.map { it.measure(full) }
    val badge = slots.badges.map { it.measure(full) }
    val body = slots.bodies.map { it.measure(full) }
    val tailW = slots.tail.maxWidth()
    val together = badge.isEmpty() || badge.maxWidth() + gaps.trailing + tailW <= width
    val endTop = lead.maxHeight() + gaps.badgeLine
    // 同一行：行高取两者较高；拆两行：badge 一行、尾件下一行
    val badgeRowH = if (together) maxOf(badge.maxHeight(), slots.tail.maxHeight()) else badge.maxHeight()
    val tailTop = if (together) endTop else endTop + badgeRowH + gaps.badgeLine
    val tailRowH = if (together) badgeRowH else slots.tail.maxHeight()
    val bodyTop = tailTop + tailRowH + gaps.line
    val height = maxOf(bodyTop + body.maxHeight() - if (body.isEmpty()) gaps.line else 0, constraints.minHeight)
    val badgeRight = if (together) width - tailW - gaps.trailing else width
    return layout(width, height) {
        lead.forEach { it.place(0, 0) }
        val mid = { child: Placeable, rowH: Int -> Alignment.CenterVertically.align(child.height, rowH) }
        slots.tail.forEach { it.place((width - it.width).coerceAtLeast(0), tailTop + mid(it, tailRowH)) }
        badge.forEach { it.place((badgeRight - it.width).coerceAtLeast(0), endTop + mid(it, badgeRowH)) }
        body.forEach { it.place(0, bodyTop) }
    }
}

/**
 * 「前置图标 + 含数字串的内容 + 尾随控件」一行（判据 1 / D10-1265 先让行再横滑）。[content] 是含数字串的文字（一般是 [WholeNumberText]，
 * 也可以是几行文字的 Column），[leading] / [trailing] 是两侧的图标、按钮（可没有）。
 * - 内容的最小固有宽（数字串整段宽）放得下「整行 − 两侧控件 − 间距」时：与改前
 *   Row(spacedBy([gap]), CenterVertically 或 Top){ leading; content.weight(1f, fill = [fill]); trailing } 同宽同位置；
 * - 放不下时两侧控件让行：前置控件单独一行在上、内容占满整行在中、尾随控件单独一行靠右在下；内容整行也放不下才由 [WholeNumberText] 横滑。
 */
@Composable
fun NumberLine(
    modifier: Modifier = Modifier,
    gap: Dp = 0.dp,
    fill: Boolean = false,
    centerVertically: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Layout(contents = listOf(leading ?: {}, content, trailing ?: {}), modifier = modifier) { parts, constraints ->
        val width = constraints.maxWidth
        val loose = Constraints(maxWidth = width)
        val lead = parts[0].map { it.measure(loose) }
        val tail = parts.last().map { it.measure(loose) }
        val sides = NumberSides(lead, tail, gap.roundToPx())
        val rest = (width - sides.used).coerceAtLeast(0)
        val fits = !constraints.hasBoundedWidth || parts[1].all { it.minIntrinsicWidth(Constraints.Infinity) <= rest }
        if (fits) {
            val inline = if (fill && constraints.hasBoundedWidth) Constraints.fixedWidth(rest) else Constraints(maxWidth = rest)
            val body = parts[1].map { it.measure(inline) }
            numberInline(sides, body, centerVertically, constraints)
        } else {
            val body = parts[1].map { it.measure(if (fill) Constraints.fixedWidth(width) else loose) }
            numberStacked(sides, body, constraints)
        }
    }
}

/** [NumberLine] 两侧控件与间距：[used] = 两侧宽 + 出现的间距。 */
private class NumberSides(
    val lead: List<Placeable>,
    val tail: List<Placeable>,
    val gap: Int,
) {
    val leadGap = if (lead.isEmpty()) 0 else gap
    val tailGap = if (tail.isEmpty()) 0 else gap
    val used = lead.maxWidth() + leadGap + tail.maxWidth() + tailGap
}

/** [NumberLine] 同一行（改前 Row 的排法）。 */
private fun MeasureScope.numberInline(
    sides: NumberSides,
    body: List<Placeable>,
    centerVertically: Boolean,
    constraints: Constraints,
): MeasureResult {
    val bodyX = sides.lead.maxWidth() + sides.leadGap
    val tailX = bodyX + body.maxWidth() + sides.tailGap
    val used = tailX + sides.tail.maxWidth()
    val height = maxOf(sides.lead.maxHeight(), body.maxHeight(), sides.tail.maxHeight(), constraints.minHeight)
    val y = { child: Placeable -> if (centerVertically) Alignment.CenterVertically.align(child.height, height) else 0 }
    return layout(maxOf(constraints.minWidth, used), height) {
        sides.lead.forEach { it.place(0, y(it)) }
        body.forEach { it.place(bodyX, y(it)) }
        sides.tail.forEach { it.place(tailX, y(it)) }
    }
}

/** [NumberLine] 让行：前置控件一行、内容整行、尾随控件一行靠右。 */
private fun MeasureScope.numberStacked(
    sides: NumberSides,
    body: List<Placeable>,
    constraints: Constraints,
): MeasureResult {
    val width = constraints.maxWidth
    val bodyTop = sides.lead.maxHeight() + if (sides.lead.isEmpty()) 0 else sides.gap
    val tailTop = bodyTop + body.maxHeight() + if (sides.tail.isEmpty()) 0 else sides.gap
    val height = maxOf(tailTop + sides.tail.maxHeight(), constraints.minHeight)
    return layout(width, height) {
        sides.lead.forEach { it.place(0, 0) }
        body.forEach { it.place(0, bodyTop) }
        sides.tail.forEach { it.place((width - it.width).coerceAtLeast(0), tailTop) }
    }
}

/** [KeyValueRow] 同一行：标签在 0、值在 [valueX]；行宽 = 可用宽（不受限时取内容宽）。 */
private fun MeasureScope.sameLine(
    title: List<Placeable>,
    content: List<Placeable>,
    valueX: Int,
    centerVertically: Boolean,
    constraints: Constraints,
): MeasureResult {
    val height = maxOf(title.maxOfOrNull { it.height } ?: 0, content.maxOfOrNull { it.height } ?: 0).coerceAtLeast(constraints.minHeight)
    val width = if (constraints.hasBoundedWidth) constraints.maxWidth else valueX + (content.maxOfOrNull { it.width } ?: 0)
    val y = { child: Placeable -> if (centerVertically) Alignment.CenterVertically.align(child.height, height) else 0 }
    return layout(width, height) {
        title.forEach { it.place(0, y(it)) }
        content.forEach { it.place(valueX, y(it)) }
    }
}

/** [KeyValueRow] 上下排：标签在上，值在下独占整行。 */
private fun MeasureScope.stacked(
    title: List<Placeable>,
    content: List<Placeable>,
    gap: Int,
    constraints: Constraints,
): MeasureResult {
    val top = (title.maxOfOrNull { it.height } ?: 0) + gap
    val height = (top + (content.maxOfOrNull { it.height } ?: 0)).coerceAtLeast(constraints.minHeight)
    return layout(constraints.maxWidth, height) {
        title.forEach { it.place(0, 0) }
        content.forEach { it.place(0, top) }
    }
}

/** 末端渐隐宽（建议 ≥ 24dp）；视口很窄时不超过视口的 1 / [FADE_MAX_SHARE]。 */
private val FADE = 24.dp
private const val FADE_MAX_SHARE = 3f
private val INDICATOR_GAP = 3.dp
private val INDICATOR_HEIGHT = 3.dp
private val INDICATOR_MIN_THUMB = 24.dp
private const val TRACK_ALPHA = 0.2f
private const val THUMB_ALPHA = 0.7f
