package com.couli.core.designsystem

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.couli.core.designsystem.CouliTextStyles.tabular

/** 价格的排版尺寸：列表搜索卡 22/700、AI 卡 17/700、详情大卡 34/700（GUIDE §3 / §7.2.1，Product 画板）。 */
enum class PriceTagSize {
    LIST,
    AI,
    LARGE,
}

/**
 * 口径词 + 主价（主价等宽数字，墨棕；读屏读「券后 29.9 元」）。放得下时口径词与主价按基线并排；放不下时主价整块换到口径词下一行。
 * 主价是不可拆分段：单行、不折行（数字中间、小数点前都不断，R2-01）。主价整块仍放不下时由父布局让出宽度
 * （列表卡价格区移到整卡宽度、网格改单列，见 [ProductCardList] / [ProductCardGridLayout]，量宽用 [AmountMetrics]），
 * 不裁切、不省略、不缩字号（03 §10.4）。
 */
@Composable
fun PriceTagMain(
    content: PriceTagContent,
    modifier: Modifier = Modifier,
    size: PriceTagSize = PriceTagSize.LIST,
) {
    val amountStyle = priceAmountStyle(size)
    val labelStyle = if (size == PriceTagSize.LARGE) CouliTypography.footnote else CouliTypography.caption
    Layout(
        modifier = modifier.clearAndSetSemantics { contentDescription = content.mainSpoken },
        content = {
            Text(text = content.mainLabel, style = labelStyle, color = CouliColors.textSecondary)
            AmountText(text = content.mainAmount, style = amountStyle, color = CouliColors.priceText)
        },
    ) { measurables, constraints ->
        val maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else Constraints.Infinity
        val label = measurables[0].measure(Constraints(maxWidth = maxWidth))
        val amount = measurables[1].measure(Constraints(maxWidth = maxWidth))
        val gap = CouliSpacing.space1.roundToPx()
        if (label.width + gap + amount.width <= maxWidth) {
            val labelBase = label.baseline()
            val amountBase = amount.baseline()
            val base = maxOf(labelBase, amountBase)
            val height = maxOf(base - labelBase + label.height, base - amountBase + amount.height)
            layout(label.width + gap + amount.width, height) {
                label.place(0, base - labelBase)
                amount.place(label.width + gap, base - amountBase)
            }
        } else {
            // 主价整块换到口径词下一行（不在数字中间断）
            layout(maxOf(label.width, amount.width), label.height + amount.height) {
                label.place(0, 0)
                amount.place(0, label.height)
            }
        }
    }
}

private fun priceAmountStyle(size: PriceTagSize): TextStyle =
    when (size) {
        PriceTagSize.LIST -> CouliTextStyles.amountList
        PriceTagSize.AI -> CouliTextStyles.amountListAi
        PriceTagSize.LARGE -> CouliTextStyles.amountDisplay
    }

private fun Placeable.baseline(): Int = this[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: height

/**
 * 金额文字：不可拆分段，单行不折行（R2-01：区间只在破折号处断，金额本身不断）。宽度由父布局保证（见 [AmountMetrics]）；
 * 排版结果报给 [LocalAmountLayoutObserver]（测试用来断言每段金额都在一行内、没有被裁切）。
 */
@Composable
internal fun AmountText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val observer = LocalAmountLayoutObserver.current
    Text(
        text = text,
        style = style,
        color = color,
        softWrap = false,
        maxLines = 1,
        modifier = modifier,
        onTextLayout = { observer?.invoke(text, it) },
    )
}

/** 测试钩子：收集每段金额文字的排版结果（默认 null，不影响运行）。 */
internal val LocalAmountLayoutObserver = staticCompositionLocalOf<((String, TextLayoutResult) -> Unit)?> { null }

/**
 * 量金额段的整块宽度（px）：父布局据此决定价格区是否移到整卡宽度、网格是否改单列——金额段放不下时改布局，不拆数字（R2-01）。
 */
@Stable
class AmountMetrics internal constructor(
    private val measurer: TextMeasurer,
    private val density: Density,
) {
    fun textWidth(
        text: String,
        style: TextStyle,
    ): Int = measurer.measure(text, style, softWrap = false, maxLines = 1).size.width

    /** 主价整块宽（口径词放不下时在上一行，不计入）。 */
    fun priceMain(
        content: PriceTagContent?,
        size: PriceTagSize,
    ): Int = if (content == null) 0 else textWidth(content.mainAmount, priceAmountStyle(size))

    /** 返利标签最窄宽度：内边距 + max(钟表 + 标签字，最宽的一段金额)；区间在破折号处断开后每段独占一行。 */
    fun rebateTag(
        content: RebateTagContent,
        size: RebateTagSize = RebateTagSize.SMALL,
    ): Int {
        val large = size == RebateTagSize.LARGE
        val padding = with(density) { (if (large) CouliSpacing.space3 else CouliSpacing.space2).roundToPx() * 2 }
        val icon = with(density) { ((if (large) CouliSizes.iconSmall else CouliSizes.iconTiny) + CouliSpacing.space1).roundToPx() }
        val label = icon + textWidth(content.label, if (large) CouliTextStyles.footnoteMedium else CouliTypography.caption)
        return padding + maxOf(label, rebateSegments(content, rebateAmountStyle(large)))
    }

    /** 最宽的一段返利金额（区间按破折号分两段）。 */
    fun rebateSegments(
        content: RebateTagContent,
        style: TextStyle,
    ): Int = content.amountSegments.maxOfOrNull { textWidth(it, style) } ?: 0

    /** 副行里最宽的金额（「预估返后 ¥11111.11」里的「¥11111.11」）：副行在空格处可折行，金额本身不能断。 */
    fun subLineAmounts(lines: List<String>): Int =
        lines.flatMap { line -> AMOUNT_TOKEN.findAll(line).map { it.value }.toList() }.maxOfOrNull {
            textWidth(it, CouliTextStyles.captionAmount)
        } ?: 0
}

/** 副行里的金额记号：可选符号 + ¥ + 数字与小数点。 */
private val AMOUNT_TOKEN = Regex("[-+]?¥[0-9.]+")

@Composable
fun rememberAmountMetrics(): AmountMetrics {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(measurer, density) { AmountMetrics(measurer, density) }
}

/**
 * 价格副行（12/18 次文字，纵向）：「券前 ¥… · 券 ¥…」或「无券」、规格说明、预售「定金与尾款以下单页为准」、「预估返后 ¥…」。
 * 不划线（BR-PRICE-04）。
 */
@Composable
fun PriceSubLines(
    lines: List<String>,
    modifier: Modifier = Modifier,
) {
    if (lines.isEmpty()) return
    Column(modifier = modifier) {
        lines.forEach { line ->
            Text(text = line, style = CouliTextStyles.captionAmount, color = CouliColors.textSecondary)
        }
    }
}

/** 副行内容：券前与券 → 规格说明 → 「无券」→ 预售说明 → 预估返后（按 Search / ProductCardStress 画板的顺序；没有的不加）。 */
fun PriceTagContent.subLines(
    specNote: String? = null,
    estNet: String? = null,
    includeNoCoupon: Boolean = true,
): List<String> =
    buildList {
        subLine?.let { add(it) }
        specNote?.let { add(it) }
        if (noCoupon && includeNoCoupon) add(PriceTerms.NO_COUPON)
        if (presaleLabel != null) add(PriceTerms.PRESALE_NOTE)
        estNet?.let { add(it) }
    }

/** 价格标签整块：主价 + 副行（单独使用时；商品卡里主价与返利同一行，见 [ProductCardList]）。 */
@Composable
fun PriceTag(
    content: PriceTagContent,
    modifier: Modifier = Modifier,
    size: PriceTagSize = PriceTagSize.LIST,
    specNote: String? = null,
    estNet: String? = null,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
        if (content.presaleLabel != null) CouliTag(text = content.presaleLabel)
        PriceTagMain(content = content, size = size)
        PriceSubLines(lines = content.subLines(specNote = specNote, estNet = estNet))
    }
}

/** 返利标签尺寸：列表 12/18；详情大号（标签 14/500、金额 22/600）。 */
enum class RebateTagSize {
    SMALL,
    LARGE,
}

/**
 * 返利标签（GUIDE §7.1）：返利浅底 + 深橙字 + 钟表图形；金额 600 等宽。区间两段只在破折号处折行（每段整块不拆），放不下整块换行。
 * 「暂无返利」用中性色、不用橙（[RebateTagContent.usesRebateAccent]；列表里由调用方隐藏，BR-TEXT-10）。
 * 读屏读完整句（「预估返 1.35 元到 2.69 元」）。金额隐藏时传 [RebateTagContent.masked] 的结果（「预估返 ****」）。
 */
@Composable
fun RebateTag(
    content: RebateTagContent,
    modifier: Modifier = Modifier,
    size: RebateTagSize = RebateTagSize.SMALL,
) {
    val accent = content.usesRebateAccent
    val background = if (accent) CouliColors.rebateBackground else CouliColors.backgroundMuted
    val color = if (accent) CouliColors.rebateText else CouliColors.textSecondary
    val large = size == RebateTagSize.LARGE
    val labelStyle = if (large) CouliTextStyles.footnoteMedium else CouliTypography.caption
    val amountStyle = rebateAmountStyle(large)
    val padding =
        if (large) {
            PaddingValues(horizontal = CouliSpacing.space3, vertical = CouliSpacing.space1)
        } else {
            PaddingValues(horizontal = CouliSpacing.space2, vertical = CouliSpacing.space0 + CouliSizes.hairline * 2)
        }
    FlowRow(
        modifier =
            modifier
                .heightIn(min = CouliSizes.tagHeight)
                .clip(RoundedCornerShape(CouliRadius.button))
                .background(background)
                .padding(padding)
                .clearAndSetSemantics { contentDescription = content.spoken },
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
            if (accent) {
                Icon(
                    imageVector = CouliIcons.clock,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(if (large) CouliSizes.iconSmall else CouliSizes.iconTiny),
                )
            }
            Text(text = content.label, style = labelStyle, color = color)
        }
        if (content.amountSegments.isNotEmpty()) {
            // 段与段之间不留空隙，只在破折号前折行；每段整块不拆（R2-01）。一段的宽度由父布局保证（[AmountMetrics.rebateTag]）
            RebateAmountSegments(content = content, style = amountStyle, color = color)
        }
    }
}

/** 返利金额段：区间「¥a」「–¥b」两段只在破折号处断开，每段整块单行（R2-01）；用于返利标签与网格卡第二行。 */
@Composable
fun RebateAmountSegments(
    content: RebateTagContent,
    modifier: Modifier = Modifier,
    style: TextStyle = rebateAmountStyle(large = false),
    color: Color = CouliColors.rebateText,
) {
    FlowRow(modifier = modifier) {
        content.amountSegments.forEach { segment -> AmountText(text = segment, style = style, color = color) }
    }
}

private fun rebateAmountStyle(large: Boolean): TextStyle =
    if (large) CouliTextStyles.amountTitle else CouliTextStyles.captionSemibold.tabular()

/**
 * 「预估返后 ¥x」那一行（[EstNetModel] 给文字；不显示时传 null 即不占位）。同卡的返利标签不显示金额（暂无返利、金额隐藏）时
 * 不显示这一行（它能反推返利，F-AND-05 / 三端统一裁定）：商品卡用 [ProductCardData.visibleEstNet]。
 */
@Composable
fun EstNetLine(
    text: String?,
    modifier: Modifier = Modifier,
) {
    if (text == null) return
    Text(text = text, style = CouliTextStyles.captionAmount, color = CouliColors.textSecondary, modifier = modifier)
}

/** 口径说明的一行：图标 + 文字（文字由调用方按 disclaimer_keys 从字典取）。 */
@Immutable
data class DisclaimerLine(
    val text: String,
    val icon: ImageVector? = CouliIcons.info,
)

/** 口径说明全文版的两种呈现：列表底部（无底色，首行带 i 图标）与详情常驻（灰底圆角块，每行带图标）。 */
enum class DisclaimerStyle {
    LIST_FOOTER,
    DETAIL_PANEL,
}

/** 口径说明全文（BR-PRICE-03：详情常驻全文；列表每屏底部一次）。 */
@Composable
fun DisclaimerFooter(
    lines: List<DisclaimerLine>,
    modifier: Modifier = Modifier,
    style: DisclaimerStyle = DisclaimerStyle.LIST_FOOTER,
) {
    val panel = style == DisclaimerStyle.DETAIL_PANEL
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .then(
                    if (panel) {
                        Modifier
                            .clip(RoundedCornerShape(CouliRadius.card))
                            .background(CouliColors.backgroundMuted)
                            .padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space3)
                    } else {
                        Modifier.padding(horizontal = CouliSpacing.space1, vertical = CouliSpacing.space2)
                    },
                ),
        verticalArrangement = Arrangement.spacedBy(if (panel) CouliSpacing.space2 else CouliSpacing.space1),
    ) {
        lines.forEachIndexed { index, line ->
            Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
                val icon = if (panel || index == 0) line.icon else null
                Box(
                    modifier = Modifier.size(CouliSizes.iconSmall).padding(top = CouliSizes.hairline),
                    contentAlignment = Alignment.Center,
                ) {
                    if (icon != null) {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = CouliColors.textSecondary,
                            modifier = Modifier.size(CouliSizes.iconSmall),
                        )
                    }
                }
                Text(text = line.text, style = CouliTypography.caption, color = CouliColors.textSecondary)
            }
        }
    }
}

/** 单卡的 i 图标（点开在卡内展开全文，BR-PRICE-03）；[label] 为读屏名称（如「口径说明」），由调用方给。 */
@Composable
fun DisclaimerInfoButton(
    label: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    expandedStateLabel: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier =
            modifier
                .size(CouliSizes.touchTarget)
                .couliFocusRing(focusRingVisible(interaction), CouliRadius.pill)
                .clip(RoundedCornerShape(CouliRadius.pill))
                .clickable(interactionSource = interaction, indication = LocalIndication.current, role = Role.Button, onClick = onToggle)
                .semantics {
                    contentDescription = if (expanded && expandedStateLabel != null) "$label$expandedStateLabel" else label
                },
        contentAlignment = Alignment.Center,
    ) {
        Icon(CouliIcons.info, contentDescription = null, tint = CouliColors.textSecondary, modifier = Modifier.size(CouliSizes.iconSmall))
    }
}

/**
 * 平台来源标签（GUIDE §7.1 / 03 §10.2：白底 + 1 描边、中性文字）。图标在左：有内置官方图的平台画 14 档原图
 * （[PlatformIcon]，不着色），暂缺 / 未知编码只显示文字，不画占位方块（F-X-05a）。
 * 读屏整枚只读 [name] 一次（平台中文名，调用方从字典给）；图标是装饰，文字节点对读屏隐藏。
 * [platformCode] 显式给时以它取图（空串 / 未知 = 无图）；为 null 的旧调用按已知中文名兼容取图。
 * [remoteIconUrl] 只是 F-X-05 后台替换图的签名预留，本任务不使用。[icon] 是旧矢量参数，仅为源码兼容保留，不再绘制（缺图 / 空码 / 未知码一律只文字）。
 */
@Composable
fun PlatformBadge(
    name: String,
    modifier: Modifier = Modifier,
    @Suppress("UnusedParameter") icon: ImageVector? = null,
    platformCode: String? = null,
    remoteIconUrl: String? = null,
) {
    val shape = RoundedCornerShape(CouliRadius.pill)
    val iconCode = badgeIconCode(name, platformCode)
    Row(
        modifier =
            modifier
                .heightIn(min = CouliSizes.badgeHeight)
                .clip(shape)
                .background(CouliColors.sourceBackground)
                .border(CouliSizes.hairline, CouliColors.sourceBorder, shape)
                .padding(horizontal = CouliSpacing.space2)
                .semantics { contentDescription = name },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
    ) {
        if (platformIconAssetPath(iconCode) != null) {
            PlatformIcon(platformCode = iconCode, size = PlatformIconSize.BADGE, remoteUrl = remoteIconUrl)
        }
        Text(
            text = name,
            style = CouliTypography.caption,
            color = CouliColors.sourceText,
            softWrap = false,
            modifier = Modifier.semantics { hideFromAccessibility() },
        )
    }
}

/** 徽标左侧图标（含间距）要占的宽度；无图时为 0。供行内占位计算，与 [PlatformBadge] 同一取图规则。 */
private fun platformBadgeIconWidth(
    name: String,
    platformCode: String?,
): Dp = if (platformIconAssetPath(badgeIconCode(name, platformCode)) != null) PlatformIconSize.BADGE.dp + CouliSpacing.space1 else 0.dp

/**
 * 标题最前放平台来源标签（行内，GUIDE §7.2.1）；标题按 [maxLines] 截断（标题是唯一允许省略的文字）。
 * 读屏把来源与标题合成一项，来源在前（adaptation-a11y-spec §8）。[description] 给了就替代这句读屏（详情下架「商品已下架，淘宝 …」）；
 * [color] 是标题字色（下架次要色）。两者默认与原来一样。
 */
@Composable
fun BadgedTitle(
    platformName: String,
    title: String,
    modifier: Modifier = Modifier,
    style: TextStyle = CouliTypography.footnote,
    maxLines: Int = 2,
    platformCode: String? = null,
    color: Color = CouliColors.textPrimary,
    description: String? = null,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val badgeStyle = CouliTypography.caption
    val iconWidth = platformBadgeIconWidth(platformName, platformCode)
    val placeholder =
        remember(platformName, iconWidth, density) {
            val measured = measurer.measure(platformName, badgeStyle)
            with(density) {
                // 徽标左右内边距 + 描边 + 行内右侧间距 + 左侧图标与图文间距（F-X-05a，否则中文名被挤掉）
                val horizontal = (CouliSpacing.space2 * 2 + CouliSizes.hairline * 2 + CouliSpacing.space1 + iconWidth).roundToPx()
                val height = maxOf(CouliSizes.badgeHeight.roundToPx(), measured.size.height)
                Placeholder(
                    width = (measured.size.width + horizontal).toSp(),
                    height = height.toSp(),
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                )
            }
        }
    val text =
        buildAnnotatedString {
            appendInlineContent(BADGE_ID, platformName)
            append(title)
        }
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        inlineContent =
            mapOf(
                BADGE_ID to
                    InlineTextContent(placeholder) {
                        Box(modifier = Modifier.padding(end = CouliSpacing.space1), contentAlignment = Alignment.CenterStart) {
                            PlatformBadge(name = platformName, platformCode = platformCode)
                        }
                    },
            ),
        // 唯一允许省略的文字：标签让 200% 字号布局检查只对商品标题放行省略（不得含金额，DS-02）
        modifier = modifier.semantics { contentDescription = description ?: "$platformName $title" }.testTag(TITLE_ELLIPSIS_TAG),
    )
}

/** 商品标题（唯一允许按行数省略的文字）的测试标记。 */
const val TITLE_ELLIPSIS_TAG = "ds02-ellipsis-allowed"

private const val BADGE_ID = "platform_badge"
