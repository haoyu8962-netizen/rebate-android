package com.couli.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp

/** 优惠标签（有券、预售、规格待确认、规格以下单页为准……，文字由调用方给）。 */
@Immutable
data class BenefitTag(
    val text: String,
    val tone: CouliTagTone = CouliTagTone.OUTLINE,
    val withInfoIcon: Boolean = false,
)

/**
 * 商品卡的数据（全部是已格式化的文字或模型结果；文案由调用方从字典取）。
 * [imageLabel]：图片占位里的字（商品图未接入前）。
 * [price] 为 null 表示这张卡不显示任何价格：rebate_basis = amount_unknown（拼多多直链）时价格字段为空，
 * 不显示主价、副行、规格说明与预估返后（BR-PRICE-08），由调用方传 null。
 * [disclaimerKeys]：服务端下发的 disclaimer_keys（列表底部那一句按它选，见 [DisclaimerKeys.listFooterPriceKey]）；
 * [disclaimer]：这些键对应的文案（卡内 i 展开的全文），为空时不显示 i 按钮。
 */
@Immutable
data class ProductCardData(
    val platformName: String,
    val title: String,
    val price: PriceTagContent?,
    val rebate: RebateTagContent,
    val buyBase: String,
    val imageLabel: String,
    val estNet: String? = null,
    val benefitTags: List<BenefitTag> = emptyList(),
    val specNote: String? = null,
    val disclaimer: List<String> = emptyList(),
    val disclaimerKeys: List<String> = emptyList(),
    val infoLabel: String = "",
    val shopName: String? = null,
    val quotedAt: String? = null,
    /**
     * 金额隐藏（BR-TEXT-10；三端统一裁定）：隐藏时返利标签显示「预估返 ****」（读屏「预估返，金额已隐藏」），不显示「预估返后」，
     * 购买按钮只显示动词；价格、口径 i、「以结算为准」照常。遮罩与读屏文案由调用方从字典给。
     */
    val amountMask: AmountMask? = null,
) {
    val amountHidden: Boolean get() = amountMask?.hidden == true

    /** 卡上实际显示的返利标签（金额隐藏时已遮罩）。 */
    val displayedRebate: RebateTagContent get() = amountMask?.takeIf { it.hidden }?.let { rebate.masked(it) } ?: rebate

    /** 「预估返后」只跟着同卡返利标签显示金额时出现：有价格、返利有金额、金额没隐藏（F-AND-05）。 */
    val visibleEstNet: String? get() = estNet?.takeIf { price != null && rebate.hasAmount && !amountHidden }

    /**
     * 整张卡的金额隐藏状态统一成一份（R1-08）：[hidden] 与卡上现有的 [amountMask] 不一致时，换成同一个遮罩来源——
     * 要隐藏但没给遮罩用 [AmountMask.DEFAULT_HIDDEN]，要显示就把遮罩设为不隐藏。返利标签、读屏、预估返后、购买按钮都读这份结果。
     */
    fun withAmountHidden(hidden: Boolean): ProductCardData =
        when {
            hidden == amountHidden -> this
            hidden -> copy(amountMask = (amountMask ?: AmountMask.DEFAULT_HIDDEN).copy(hidden = true))
            else -> copy(amountMask = amountMask?.copy(hidden = false))
        }

    /** 价格口径 i 跟着价格走：卡上有价格、有说明全文就显示，与返利区是否显示（暂无返利、隐藏）无关（F-AND-06）。 */
    val showsDisclaimerButton: Boolean get() = price != null && disclaimer.isNotEmpty()
}

/** 列表卡的两种用处：搜索卡（主价 22，底行左侧口径说明）与 AI 卡（主价 17，口径按钮在右上角）。 */
enum class ProductCardVariant {
    SEARCH,
    AI,
}

/**
 * 列表商品卡（GUIDE §7.2.1 定稿写法）：左图右文；标题前平台来源标签；优惠标签一行；价格行 = 口径词 + 主价 + 返利标签
 * （放不下返利标签整块换行）；副行；底行左口径说明、右购买按钮（放不下按钮整块换到下一行靠右）。
 * 不缩字号、不截断金额；只有标题两行截断。无返利时列表隐藏返利标签（BR-TEXT-10）。
 * 主价、返利标签任一金额段（区间按破折号分段）或副行金额在右栏整块放不下（窄屏 + 大字号）时，价格行与副行移到图片下方
 * 占整卡宽度（价格区降级）；金额本身从不拆开（R2-01）。
 * [ProductCardData.price] 为 null（金额未知）时只显示返利文案，不显示任何价格。
 */
@Composable
fun ProductCardList(
    data: ProductCardData,
    onBuy: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ProductCardVariant = ProductCardVariant.SEARCH,
    buyState: BuyButtonState = BuyButtonState.NORMAL,
    amountHidden: Boolean = data.amountHidden,
    context: String = if (variant == ProductCardVariant.AI) BuyButtonModel.CONTEXT_AGENT else BuyButtonModel.CONTEXT_LIST,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val ai = variant == ProductCardVariant.AI
    // 整卡同一份隐藏状态：只传 amountHidden=true（没给遮罩）时返利标签、读屏、预估返后与按钮一起隐藏（R1-08）
    val shown = data.withAmountHidden(amountHidden)
    val priceSize = if (ai) PriceTagSize.AI else PriceTagSize.LIST
    val metrics = rememberAmountMetrics()
    val density = LocalDensity.current
    val priceBlockMinWidth = remember(shown, priceSize, metrics) { shown.listPriceBlockMinWidth(metrics, priceSize) }
    BoxWithConstraints(modifier = modifier.fillMaxWidth().couliCard()) {
        // 右栏宽 = 卡宽 − 两侧内边距 − 左图 − 间距；任一金额段放不下右栏时价格区移到整卡宽度
        val rightColumn = maxWidth - CouliSpacing.space3 * 2 - CouliSizes.listThumbnail - CouliSpacing.space3
        val priceFullWidth = priceBlockMinWidth > with(density) { rightColumn.roundToPx() }
        Column(
            modifier = Modifier.padding(CouliSpacing.space3),
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3), verticalAlignment = Alignment.Top) {
                ProductThumbnail(label = shown.imageLabel, size = CouliSizes.listThumbnail)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
                    BadgedTitle(
                        platformName = shown.platformName,
                        title = shown.title,
                        modifier = if (ai) Modifier.padding(end = CouliSpacing.space8 - CouliSpacing.space1) else Modifier,
                    )
                    BenefitTagRow(tags = shown.benefitTags)
                    if (!priceFullWidth) ListPriceBlock(data = shown, size = priceSize)
                }
            }
            if (priceFullWidth) ListPriceBlock(data = shown, size = priceSize)
            EndWrapRow(
                modifier = Modifier.fillMaxWidth().heightIn(min = CouliSizes.touchTarget),
                start = {
                    if (!ai) SettleNoteWithInfo(data = shown, expanded = expanded, onToggle = { expanded = !expanded })
                },
                end = {
                    BuyButton(
                        base = shown.buyBase,
                        rebate = shown.displayedRebate,
                        onClick = onBuy,
                        context = context,
                        state = buyState,
                        amountHidden = shown.amountHidden,
                    )
                },
            )
            if (expanded) DisclaimerFooter(lines = shown.disclaimer.map { DisclaimerLine(it, icon = null) })
        }
        if (ai && shown.showsDisclaimerButton) {
            DisclaimerInfoButton(
                label = shown.infoLabel,
                expanded = expanded,
                onToggle = { expanded = !expanded },
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
    }
}

/** 列表卡的价格行（口径词 + 主价 + 返利标签，放不下返利标签整块换行）与副行；金额未知时只有返利文案。 */
@Composable
private fun ListPriceBlock(
    data: ProductCardData,
    size: PriceTagSize,
) {
    Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            if (data.price != null) PriceTagMain(content = data.price, size = size)
            if (data.rebate.kind != RebateKind.NONE) RebateTag(content = data.displayedRebate)
        }
        PriceSubLines(lines = data.priceSubLines(estNet = data.visibleEstNet))
    }
}

/** 列表卡价格区最窄宽度（px）：主价、返利标签（按破折号分段后）、副行金额三者中最宽的不可拆分块。 */
private fun ProductCardData.listPriceBlockMinWidth(
    metrics: AmountMetrics,
    size: PriceTagSize,
): Int =
    maxOf(
        metrics.priceMain(price, size),
        if (rebate.kind == RebateKind.NONE) 0 else metrics.rebateTag(displayedRebate),
        metrics.subLineAmounts(priceSubLines(estNet = visibleEstNet)),
    )

/** 网格卡内容最窄宽度（px）：卡内边距 + max(主价、返利块最宽的一段金额、副行金额)。 */
internal fun ProductCardData.gridMinWidth(
    metrics: AmountMetrics,
    density: Density,
): Int {
    val cardPadding = with(density) { ((CouliSpacing.space2 + CouliSpacing.space1) * 2).roundToPx() }
    val rebatePadding = with(density) { (CouliSpacing.space2 * 2).roundToPx() }
    val rebate =
        if (rebate.kind ==
            RebateKind.NONE
        ) {
            0
        } else {
            rebatePadding + metrics.rebateSegments(displayedRebate, CouliTextStyles.amountBody)
        }
    return cardPadding + maxOf(metrics.priceMain(price, PriceTagSize.AI), rebate, metrics.subLineAmounts(priceSubLines(estNet = null)))
}

/** 副行：没有价格（金额未知）时一行都没有——规格说明与预估返后也是价格信息（BR-PRICE-08）。 */
private fun ProductCardData.priceSubLines(estNet: String?): List<String> = price?.subLines(specNote = specNote, estNet = estNet).orEmpty()

@Composable
private fun SettleNoteWithInfo(
    data: ProductCardData,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // 比价区间时显示「以结算为准」（BR-PRICE-07 rebate_compare）；单一金额只有口径按钮
        if (data.rebate.kind == RebateKind.RANGE) {
            Text(text = PriceTerms.SETTLE_BASIS, style = CouliTypography.caption, color = CouliColors.textSecondary)
        }
        // 48 命中区比 16 图标大，向左收 12 让图标贴近文字 / 卡片内边距（设计稿用负外边距达到同样效果，GUIDE §7.2.1）
        if (data.showsDisclaimerButton) {
            DisclaimerInfoButton(
                label = data.infoLabel,
                expanded = expanded,
                onToggle = onToggle,
                modifier = Modifier.offset(x = -CouliSpacing.space3),
            )
        }
    }
}

@Composable
private fun BenefitTagRow(tags: List<BenefitTag>) {
    if (tags.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
    ) {
        tags.forEach { tag ->
            CouliTag(text = tag.text, tone = tag.tone, icon = if (tag.withInfoIcon) CouliIcons.info else null)
        }
    }
}

/** 商品图占位（GUIDE §7.1：占位底 #E9E2D9 + 1 描边，圆角 8）；列表里是装饰，读屏跳过。 */
@Composable
fun ProductThumbnail(
    label: String,
    modifier: Modifier = Modifier,
    size: Dp? = null,
    description: String? = null,
) {
    val shape = RoundedCornerShape(CouliRadius.small)
    Box(
        modifier =
            modifier
                .then(if (size != null) Modifier.size(size) else Modifier)
                .clip(shape)
                .background(CouliColors.backgroundPlaceholder)
                .border(CouliSizes.hairline, CouliColors.borderSubtle, shape)
                .then(
                    if (description != null) {
                        Modifier.clearAndSetSemantics { contentDescription = description }
                    } else {
                        Modifier.clearAndSetSemantics { }
                    },
                ),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = CouliTypography.caption, color = CouliColors.textSecondary)
    }
}

/**
 * 首页网格卡（窄卡）：上图下文；返利标签两行结构——第一行「钟表 + 预估返 + 信息按钮」，第二行完整金额，只在破折号处折行（GUIDE §6）。
 * 多张卡请用 [ProductCardGridLayout]：列宽放不下整块金额时它改单列。
 */
@Composable
fun ProductCardGrid(
    data: ProductCardData,
    modifier: Modifier = Modifier,
    onInfo: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.couliCard().padding(CouliSpacing.space2),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        ProductThumbnail(label = data.imageLabel, modifier = Modifier.fillMaxWidth().aspectRatio(GRID_IMAGE_RATIO))
        Column(
            modifier = Modifier.padding(horizontal = CouliSpacing.space1),
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
        ) {
            BadgedTitle(platformName = data.platformName, title = data.title)
            BenefitTagRow(tags = data.benefitTags)
            val info = onInfo.takeIf { data.showsDisclaimerButton }
            val rebateShown = data.rebate.kind != RebateKind.NONE
            if (data.price != null) {
                // 返利区不显示（暂无返利）时口径 i 放在价格行右侧，不随返利区一起消失（F-AND-06）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PriceTagMain(content = data.price, size = PriceTagSize.AI, modifier = Modifier.weight(1f, fill = false))
                    if (!rebateShown && info != null) {
                        DisclaimerInfoButton(label = data.infoLabel, expanded = false, onToggle = info)
                    }
                }
            }
            PriceSubLines(lines = data.priceSubLines(estNet = null))
            if (rebateShown) GridRebateBlock(data = data, onInfo = info)
        }
    }
}

/**
 * 首页网格（默认两列）：任一张卡的不可拆分金额段（主价、返利区间按破折号分开后的每段、副行金额）在当前列宽下整块放不下
 * （窄屏 + 大字号）时，整组改为单列、每张卡占整行宽，而不是拆开数字（R2-01）。
 */
@Composable
fun ProductCardGridLayout(
    items: List<ProductCardData>,
    modifier: Modifier = Modifier,
    columns: Int = 2,
    onInfo: ((ProductCardData) -> Unit)? = null,
) {
    val metrics = rememberAmountMetrics()
    val density = LocalDensity.current
    val required = remember(items, metrics, density) { items.maxOfOrNull { it.gridMinWidth(metrics, density) } ?: 0 }
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val gap = CouliSpacing.space3
        val columnWidth = (maxWidth - gap * (columns - 1)) / columns
        val effective = if (required > with(density) { columnWidth.roundToPx() }) 1 else columns
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            items.chunked(effective).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { item ->
                        ProductCardGrid(
                            data = item,
                            modifier = Modifier.weight(1f),
                            onInfo = onInfo?.let { { it(item) } },
                        )
                    }
                    repeat(effective - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun GridRebateBlock(
    data: ProductCardData,
    onInfo: (() -> Unit)?,
) {
    val rebate = data.displayedRebate
    val accent = rebate.usesRebateAccent
    val color = if (accent) CouliColors.rebateText else CouliColors.textSecondary
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(CouliRadius.small))
                .background(if (accent) CouliColors.rebateBackground else CouliColors.backgroundMuted)
                .padding(horizontal = CouliSpacing.space2, vertical = CouliSpacing.space1)
                .semantics(mergeDescendants = true) { contentDescription = rebate.spoken },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (accent) {
                Icon(CouliIcons.clock, contentDescription = null, tint = color, modifier = Modifier.size(CouliSizes.iconTiny))
                Spacer(Modifier.width(CouliSpacing.space1))
            }
            Text(text = rebate.label, style = CouliTypography.caption, color = color, modifier = Modifier.weight(1f))
            if (onInfo != null) {
                DisclaimerInfoButton(
                    label = data.infoLabel,
                    expanded = false,
                    onToggle = onInfo,
                    modifier = Modifier.height(CouliSizes.touchTarget),
                )
            }
        }
        RebateAmountSegments(content = rebate, style = CouliTextStyles.amountBody, color = color)
    }
}

/**
 * 详情页头部大卡（Product 画板）：价格大数字（34/700 墨棕）→ 券前副行 → 大号返利标签（+ 比价「以结算为准」）
 * → 预估返后与取价时间 → 优惠标签 → 分隔线 → 来源 + 标题（17/600 两行）→ 店铺。
 * 金额未知（[ProductCardData.price] 为 null）时不显示价格区与预估返后、取价，只显示返利文案。
 */
@Composable
fun ProductCardLarge(
    data: ProductCardData,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().couliCard().padding(CouliSpacing.space4),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        if (data.price != null) {
            Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
                PriceTagMain(content = data.price, size = PriceTagSize.LARGE)
                PriceSubLines(lines = data.priceSubLines(estNet = null))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
                verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                RebateTag(content = data.displayedRebate, size = RebateTagSize.LARGE)
                if (data.rebate.kind == RebateKind.RANGE) {
                    Text(text = PriceTerms.SETTLE_BASIS, style = CouliTypography.caption, color = CouliColors.textSecondary)
                }
            }
            val meta = if (data.price == null) emptyList() else listOfNotNull(data.visibleEstNet, data.quotedAt)
            if (meta.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3)) {
                    meta.forEach { Text(text = it, style = CouliTextStyles.captionAmount, color = CouliColors.textSecondary) }
                }
            }
        }
        BenefitTagRow(tags = data.benefitTags)
        Box(modifier = Modifier.fillMaxWidth().height(CouliSizes.hairline).background(CouliColors.borderSubtle))
        Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
            BadgedTitle(platformName = data.platformName, title = data.title, style = CouliTextStyles.bodySemibold)
            if (data.shopName != null) {
                Text(text = data.shopName, style = CouliTypography.caption, color = CouliColors.textSecondary)
            }
        }
    }
}

/** 网格卡图片宽高比（ProductCardStress 网格卡 157 × 132 的近似；GUIDE §6 未给令牌）。 */
private const val GRID_IMAGE_RATIO = 1.19f
