package com.couli.core.designsystem

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
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
    /**
     * 价格不可用的提示（BR-PRICE-01、AC-S1-09「暂时查不到该商品价格」，APP-06a 共性第 1 条）：不为 null 时卡上不显示任何金额
     * （主价、副行、返利、预估返后、取价），只显示这句；价格保持未知，不补 0。购买按钮由调用方置为「稍后再试」。
     */
    val priceUnavailableText: String? = null,
    /** 取价处的价格变化提示（BR-PRICE-11：stale=true 或 age_sec > 300；APP-06a 共性第 3 条），接在取价时间后；没有为 null。 */
    val staleNote: String? = null,
    /** 商品图地址（契约 image，https；没有为 null → 占位，APP-06a 共性第 22 条）。 */
    val imageUrl: String? = null,
    /**
     * 契约平台编码（ProductCard.platform）：给了就按它取来源徽标的官方图（F-X-05a）；null 的旧数据按 [platformName] 中文名兼容取图。
     * [ProductCardMapper] 原样带入。
     */
    val platformCode: String? = null,
    /** 推广标识（契约 ad_label，BR-TEXT-17）：非 null 时原样显示为角标（详情头图左上，APP-06v G-D04）；不 trim、不换字。 */
    val adLabel: String? = null,
) {
    /** 价格不可用：不显示任何金额（[priceUnavailableText]）。 */
    val priceUnavailable: Boolean get() = priceUnavailableText != null

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
    val showsDisclaimerButton: Boolean get() = price != null && disclaimer.isNotEmpty() && !priceUnavailable
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
@Suppress("CyclomaticComplexMethod") // 卡片各区按数据逐项组合
@Composable
fun ProductCardList(
    data: ProductCardData,
    onBuy: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ProductCardVariant = ProductCardVariant.SEARCH,
    buyState: BuyButtonState = BuyButtonState.NORMAL,
    amountHidden: Boolean = data.amountHidden,
    context: String = if (variant == ProductCardVariant.AI) BuyButtonModel.CONTEXT_AGENT else BuyButtonModel.CONTEXT_LIST,
    retryLaterText: String = data.buyBase,
    /**
     * 口径 i 的去向（APP-06a 共性第 5 条，SearchDisclaimerNote）：给了就由调用方弹出说明弹层（[DisclaimerNoteDialog]），不在卡内展开；
     * [infoFocus] 挂在 i 上，弹层关闭后焦点回到这里。
     */
    onInfo: (() -> Unit)? = null,
    infoFocus: FocusRequester? = null,
    /** 「稍后再试」画成描边次按钮（剪贴板识别结果④，APP-11v A18）；默认 false 保持原外观。 */
    retryLaterOutlined: Boolean = false,
    /** false：不画卡框（白底、阴影与 12 内边距），内容直接贴容器边排（半屏里的单张识别卡 HomeClipboardCard-v3）；默认 true 保持原外观。 */
    framed: Boolean = true,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val toggleInfo: () -> Unit = onInfo ?: { expanded = !expanded }
    val ai = variant == ProductCardVariant.AI
    // 整卡同一份隐藏状态：只传 amountHidden=true（没给遮罩）时返利标签、读屏、预估返后与按钮一起隐藏（R1-08）
    val shown = data.withAmountHidden(amountHidden)
    val priceSize = if (ai) PriceTagSize.AI else PriceTagSize.LIST
    val metrics = rememberAmountMetrics()
    val density = LocalDensity.current
    val priceBlockMinWidth = remember(shown, priceSize, metrics) { shown.listPriceBlockMinWidth(metrics, priceSize) }
    val inset = if (framed) CouliSpacing.space3 else CouliSpacing.space0
    BoxWithConstraints(modifier = modifier.fillMaxWidth().then(if (framed) Modifier.couliCard() else Modifier)) {
        // 右栏宽 = 卡宽 − 两侧内边距 − 左图 − 间距；任一金额段放不下右栏时价格区移到整卡宽度
        val rightColumn = maxWidth - inset * 2 - CouliSizes.listThumbnail - CouliSpacing.space3
        val priceFullWidth = priceBlockMinWidth > with(density) { rightColumn.roundToPx() }
        Column(
            modifier = Modifier.padding(inset),
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3), verticalAlignment = Alignment.Top) {
                ProductThumbnail(label = shown.imageLabel, size = CouliSizes.listThumbnail, imageUrl = shown.imageUrl)
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
            shown.staleNote?.takeIf { !shown.priceUnavailable && shown.price != null }?.let {
                Text(text = it, style = CouliTypography.caption, color = CouliColors.textSecondary)
            }
            EndWrapRow(
                modifier = Modifier.fillMaxWidth().heightIn(min = CouliSizes.touchTarget),
                start = {
                    if (!ai) SettleNoteWithInfo(data = shown, expanded = expanded, onToggle = toggleInfo, infoFocus = infoFocus)
                },
                end = {
                    BuyButton(
                        base = shown.buyBase,
                        rebate = shown.displayedRebate,
                        onClick = onBuy,
                        context = context,
                        state = buyState,
                        amountHidden = shown.amountHidden,
                        retryLaterText = retryLaterText,
                        retryLaterOutlined = retryLaterOutlined,
                    )
                },
            )
            if (expanded && onInfo == null) DisclaimerFooter(lines = shown.disclaimer.map { DisclaimerLine(it, icon = null) })
        }
        if (ai && shown.showsDisclaimerButton) {
            DisclaimerInfoButton(
                label = shown.infoLabel,
                expanded = expanded,
                onToggle = toggleInfo,
                modifier = Modifier.align(Alignment.TopEnd).then(infoFocus?.let { Modifier.couliFocusTarget(it) } ?: Modifier),
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
    if (data.priceUnavailableText != null) {
        Text(text = data.priceUnavailableText, style = CouliTypography.body, color = CouliColors.textSecondary)
        return
    }
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

/**
 * 网格返利块第一行（钟表 + 「预估返」+ 口径 i）的最窄宽度（px，含卡内边距）：这一行也不拆（GUIDE §6 窄卡两行结构）。
 * 只给 [rememberProductGridColumns] 的 includeRebateHeader 用（首页，返利块是紧凑变体：i 在行内只占 16 图标位，48 命中区向外溢出）；
 * [ProductCardGridLayout] 口径不变。
 */
internal fun ProductCardData.gridRebateHeaderMinWidth(
    metrics: AmountMetrics,
    density: Density,
): Int {
    if (rebate.kind == RebateKind.NONE) return 0
    val shown = displayedRebate
    val fixed =
        with(density) {
            val cardPadding = (CouliSpacing.space2 + CouliSpacing.space1) * 2
            val blockPadding = CouliSpacing.space2 * 2
            val icon = if (shown.usesRebateAccent) CouliSizes.iconTiny + CouliSpacing.space1 else CouliSpacing.space0
            val info = if (showsDisclaimerButton) CouliSizes.iconSmall else CouliSpacing.space0
            (cardPadding + blockPadding + icon + info).roundToPx()
        }
    return fixed + metrics.textWidth(shown.label, CouliTypography.caption)
}

/** 副行：没有价格（金额未知）时一行都没有——规格说明与预估返后也是价格信息（BR-PRICE-08）。 */
private fun ProductCardData.priceSubLines(estNet: String?): List<String> = price?.subLines(specNote = specNote, estNet = estNet).orEmpty()

@Composable
private fun SettleNoteWithInfo(
    data: ProductCardData,
    expanded: Boolean,
    onToggle: () -> Unit,
    infoFocus: FocusRequester? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // 比价区间时显示「以结算为准」（BR-PRICE-07 rebate_compare）；单一金额只有口径按钮。价格不可用时没有返利区，也不显示
        if (data.rebate.kind == RebateKind.RANGE && !data.priceUnavailable) {
            Text(text = PriceTerms.SETTLE_BASIS, style = CouliTypography.caption, color = CouliColors.textSecondary)
        }
        // 48 命中区比 16 图标大，向左收 12 让图标贴近文字 / 卡片内边距（设计稿用负外边距达到同样效果，GUIDE §7.2.1）
        if (data.showsDisclaimerButton) {
            DisclaimerInfoButton(
                label = data.infoLabel,
                expanded = expanded,
                onToggle = onToggle,
                modifier = Modifier.offset(x = -CouliSpacing.space3).then(infoFocus?.let { Modifier.couliFocusTarget(it) } ?: Modifier),
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

/**
 * 商品图（契约 ProductCard.image，APP-06a 共性第 22 条）：有 [imageUrl] 且 App 给了 [LocalProductImageLoader] 时加载显示；加载中、字段为空
 * 或加载失败时显示占位（GUIDE §7.1：占位底 #E9E2D9 + 1 描边，圆角 8）。列表里是装饰，读屏跳过。
 * 详情头图（APP-06v D2 / D6）传 [contentScale] = Fit（整图可见，空白处露出占位底色）与 [grayscale] = true（下架去色）；
 * 默认值保持列表 / 网格的旧行为（居中裁切、圆角 8、不去色）。
 */
@Composable
fun ProductThumbnail(
    label: String,
    modifier: Modifier = Modifier,
    size: Dp? = null,
    description: String? = null,
    imageUrl: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    shape: Shape = RoundedCornerShape(CouliRadius.small),
    grayscale: Boolean = false,
) {
    val loader = LocalProductImageLoader.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, imageUrl, loader) {
        value = if (imageUrl.isNullOrEmpty() || loader == null) null else runCatching { loader.load(imageUrl) }.getOrNull()
    }
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
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = contentScale,
                colorFilter = if (grayscale) GRAYSCALE else null,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            Text(text = label, style = CouliTypography.caption, color = CouliColors.textSecondary)
        }
    }
}

/** 去色滤镜（饱和度 0；下架商品图，ProductOffShelf-v3）。 */
private val GRAYSCALE = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/** 商品图加载（App 给：经 App 的 HTTP 客户端取 https 图片并解码；没给时一律占位）。 */
fun interface ProductImageLoader {
    suspend fun load(url: String): ImageBitmap?
}

/** 当前的商品图加载器（App 在根部提供；目录页、快照测试不提供 → 占位）。 */
val LocalProductImageLoader = staticCompositionLocalOf<ProductImageLoader?> { null }

/**
 * 首页网格卡（窄卡）：上图下文；返利标签两行结构——第一行「钟表 + 预估返 + 信息按钮」，第二行完整金额，只在破折号处折行（GUIDE §6）。
 * 多张卡请用 [ProductCardGridLayout]：列宽放不下整块金额时它改单列。
 */
@Composable
fun ProductCardGrid(
    data: ProductCardData,
    modifier: Modifier = Modifier,
    onInfo: (() -> Unit)? = null,
    framed: Boolean = true,
    compactRebate: Boolean = false,
) {
    Column(
        // [framed] = false：放进已有白卡（首页「超市百货」段卡）里时不再自带卡底与内边距（APP-11v Main-v3）
        modifier = if (framed) modifier.couliCard().padding(CouliSpacing.space2) else modifier,
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        ProductThumbnail(
            label = data.imageLabel,
            modifier = Modifier.fillMaxWidth().aspectRatio(GRID_IMAGE_RATIO),
            imageUrl = data.imageUrl,
        )
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
            if (rebateShown) GridRebateBlock(data = data, onInfo = info, compactInfo = compactRebate)
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
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val gap = CouliSpacing.space3
        val effective = rememberProductGridColumns(items, maxWidth, columns)
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

/**
 * 网格列数判定（与 [ProductCardGridLayout] 同一口径，R2-01）：在 [width] 宽、列距 space3 下，任一张卡的不可拆分金额段放不下时返回 1（整组单列），
 * 否则返回 [columns]。给需要自定义每格外层（点击、角标、测试标记）的调用方（首页网格 / 信息流）用，保证同一套回退规则。
 */
@Composable
fun rememberProductGridColumns(
    items: List<ProductCardData>,
    width: Dp,
    columns: Int = 2,
    includeRebateHeader: Boolean = false,
): Int {
    val metrics = rememberAmountMetrics()
    val density = LocalDensity.current
    val required =
        remember(items, metrics, density, includeRebateHeader) {
            items.maxOfOrNull {
                val header = if (includeRebateHeader) it.gridRebateHeaderMinWidth(metrics, density) else 0
                maxOf(it.gridMinWidth(metrics, density), header)
            } ?: 0
        }
    val gap = CouliSpacing.space3
    val columnWidth = (width - gap * (columns - 1)) / columns
    return if (columns > 1 && required > with(density) { columnWidth.roundToPx() }) 1 else columns
}

/**
 * 网格卡 / 首页横滑卡的返利块（GUIDE §6 窄卡两行结构）：第一行「钟表 + 预估返 + 口径 i」，第二行完整金额（只在破折号处折行）。
 * 读屏读完整一句；[onInfo] 为 null 时不显示 i。
 * [compactInfo] = true（首页紧凑变体，APP-11v r1 S2-5）：i 在行内只占图标大小（16），不把首行撑到 48；48 的命中区以图标为中心
 * 向外溢出（块底只画圆角、不裁剪，溢出部分仍能点到）。默认 false 时与原来完全一样。
 */
@Composable
fun GridRebateBlock(
    data: ProductCardData,
    onInfo: (() -> Unit)?,
    mergeSemantics: Boolean = true,
    compactInfo: Boolean = false,
) {
    val rebate = data.displayedRebate
    val accent = rebate.usesRebateAccent
    val color = if (accent) CouliColors.rebateText else CouliColors.textSecondary
    // [mergeSemantics] = false：外层整卡已读出返利句（首页横滑卡），块内文字读屏隐藏，只留口径 i 按钮
    val silent = if (mergeSemantics) Modifier else Modifier.clearAndSetSemantics {}
    val shape = RoundedCornerShape(CouliRadius.small)
    val fill = if (accent) CouliColors.rebateBackground else CouliColors.backgroundMuted
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                // 紧凑变体不裁剪：i 的 48 命中区溢出块边时仍要能点到（裁剪层会挡住块外的触摸）
                .then(if (compactInfo) Modifier.background(fill, shape) else Modifier.clip(shape).background(fill))
                .padding(horizontal = CouliSpacing.space2, vertical = CouliSpacing.space1)
                .then(
                    if (mergeSemantics) Modifier.semantics(mergeDescendants = true) { contentDescription = rebate.spoken } else Modifier,
                ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (accent) {
                Icon(CouliIcons.clock, contentDescription = null, tint = color, modifier = Modifier.size(CouliSizes.iconTiny))
                Spacer(Modifier.width(CouliSpacing.space1))
            }
            Text(text = rebate.label, style = CouliTypography.caption, color = color, modifier = Modifier.weight(1f).then(silent))
            if (onInfo != null) {
                DisclaimerInfoButton(
                    label = data.infoLabel,
                    expanded = false,
                    onToggle = onInfo,
                    modifier =
                        if (compactInfo) {
                            // 行内只占 16 × 16；按钮仍按 48 × 48 量、以图标为中心摆放（命中区 ≥48，不撑高首行）
                            Modifier.size(CouliSizes.iconSmall).wrapContentSize(unbounded = true)
                        } else {
                            Modifier.height(CouliSizes.touchTarget)
                        },
                )
            }
        }
        RebateAmountSegments(content = rebate, style = CouliTextStyles.amountBody, color = color, modifier = silent)
    }
}

/**
 * 详情页头部大卡（Product 画板）：价格大数字（34/700 墨棕）→ 券前副行 → 大号返利标签（+ 比价「以结算为准」）
 * → 预估返后与取价时间 → 优惠标签 → 分隔线 → 来源 + 标题（17/600 两行）→ 店铺。
 * 金额未知（[ProductCardData.price] 为 null）时不显示价格区与预估返后、取价，只显示返利文案。
 * [rebateOverride]：给了就替代返利标签那一行（详情比价无返利的原因行 +【看看相似商品】，ProductPriceCompareCancelled-v2）。
 * [offShelfLabel]：给了就是原位置灰（ProductOffShelf-v3）：卡顶灰胶囊写这句，只留标题（次要色）与店铺，不显示任何金额、返利、
 * 优惠标签；读屏把它读在标题前。两者默认 null，与原来完全一样。
 */
@Composable
fun ProductCardLarge(
    data: ProductCardData,
    modifier: Modifier = Modifier,
    rebateOverride: (@Composable () -> Unit)? = null,
    offShelfLabel: String? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().couliCard().padding(CouliSpacing.space4),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        if (offShelfLabel != null) {
            // 下架：只有状态胶囊（读屏随标题读出，这里不重复）、标题与店铺（BR-PRICE-14，不留旧价）
            StatusCapsule(text = offShelfLabel, modifier = Modifier.semantics { hideFromAccessibility() })
        } else {
            if (data.priceUnavailableText != null) {
                // 价格不可用：不显示任何金额（价格、返利、预估返后、取价），只有提示（BR-PRICE-01）；替代行（不含金额）照常
                Text(text = data.priceUnavailableText, style = CouliTextStyles.bodySemibold, color = CouliColors.textSecondary)
                rebateOverride?.invoke()
            } else {
                LargePriceBlock(data, rebateOverride)
            }
            BenefitTagRow(tags = data.benefitTags)
            Box(modifier = Modifier.fillMaxWidth().height(CouliSizes.hairline).background(CouliColors.borderSubtle))
        }
        val muted = offShelfLabel != null
        Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
            // 详情的「平台 + 商品名」合为一项、读屏标题角色（A11yFocusProduct 第 10 项，APP-06a 共性第 30 条）
            // 标题角色与测试标记放外层合并节点：标题 Text 本身留给「唯一允许省略的文字」标记（DS-02）
            Box(Modifier.semantics(mergeDescendants = true) { heading() }.testTag(DETAIL_TITLE_TAG)) {
                BadgedTitle(
                    platformName = data.platformName,
                    title = data.title,
                    style = CouliTextStyles.bodySemibold,
                    platformCode = data.platformCode,
                    color = if (muted) CouliColors.textSecondary else CouliColors.textPrimary,
                    description = offShelfLabel?.let { "$it${PriceTerms.SPOKEN_PAUSE}${data.platformName} ${data.title}" },
                )
            }
            if (data.shopName != null) {
                Text(text = data.shopName, style = CouliTypography.caption, color = CouliColors.textSecondary)
            }
        }
    }
}

/** 灰底状态胶囊（高 32、14 字次要色，ProductOffShelf-v3「商品已下架」、比价原因行同款）。 */
@Composable
fun StatusCapsule(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .heightIn(min = CouliSpacing.space8)
                .clip(RoundedCornerShape(CouliRadius.pill))
                .background(CouliColors.backgroundMuted)
                .padding(horizontal = CouliSpacing.space3, vertical = CouliSpacing.space1),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text = text, style = CouliTypography.footnote, color = CouliColors.textSecondary)
    }
}

/** 详情大卡的价格与返利区：价格（金额未知时没有）→ 大号返利标签（或 [rebateOverride]）→ 预估返后、取价时间与价格变化提示。 */
@Composable
private fun LargePriceBlock(
    data: ProductCardData,
    rebateOverride: (@Composable () -> Unit)?,
) {
    if (data.price != null) {
        Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
            PriceTagMain(content = data.price, size = PriceTagSize.LARGE)
            PriceSubLines(lines = data.priceSubLines(estNet = null))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
        if (rebateOverride != null) {
            rebateOverride()
        } else {
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
        }
        val meta = if (data.price == null) emptyList() else listOfNotNull(data.visibleEstNet, data.quotedAt)
        if (meta.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3)) {
                meta.forEach { Text(text = it, style = CouliTextStyles.captionAmount, color = CouliColors.textSecondary) }
            }
        }
        if (data.price != null && data.staleNote != null) {
            Text(text = data.staleNote, style = CouliTypography.caption, color = CouliColors.statusWarningText)
        }
    }
}

/** 网格卡图片宽高比（ProductCardStress 网格卡 157 × 132 的近似；GUIDE §6 未给令牌）。 */
private const val GRID_IMAGE_RATIO = 1.19f

/** 详情大卡标题的测试标记（读屏标题角色断言用）。 */
const val DETAIL_TITLE_TAG = "product_detail_title"
