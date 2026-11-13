package com.couli.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.couli.core.designsystem.AmountMetrics
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.DisclaimerInfoButton
import com.couli.core.designsystem.GridRebateBlock
import com.couli.core.designsystem.NumberLine
import com.couli.core.designsystem.PriceSubLines
import com.couli.core.designsystem.PriceTagMain
import com.couli.core.designsystem.PriceTagSize
import com.couli.core.designsystem.ProductCardData
import com.couli.core.designsystem.ProductThumbnail
import com.couli.core.designsystem.RebateKind
import com.couli.core.designsystem.couliCard
import com.couli.core.designsystem.rememberAmountMetrics
import com.couli.core.designsystem.subLines
import com.couli.core.home.HomeDraftTexts

/**
 * 今日精选（product_scroll，Main-v3）：整段放进白卡——标题行在卡内（右侧留 16），横滑区左对齐卡内边距、右侧出血到卡边。
 * 按服务端顺序，不重排、不过滤、不补数（AC-S1-90 ③）。
 */
@Composable
internal fun ProductScrollCard(
    card: HomeCardUi.ProductScroll,
    controller: HomeController,
) {
    var note by remember { mutableStateOf<HomeProductUi?>(null) }
    // 卡宽按金额量（r1 S1-2）：画板 144 起，任一张卡的主价 / 返利金额段 / 副行金额 / 返利首行放不下时整组加宽，不裁数字
    val metrics = rememberAmountMetrics()
    val density = LocalDensity.current
    val tileWidth =
        remember(card.items, metrics, density) {
            val required = card.items.maxOfOrNull { it.data.scrollTileMinWidth(metrics, density) } ?: 0
            maxOf(SCROLL_TILE_WIDTH.dp, with(density) { required.toDp() })
        }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .couliCard()
                .padding(start = CouliSpacing.space4, top = CouliSpacing.space4, bottom = CouliSpacing.space4),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        SectionTitle(card.title, card.more, controller, Modifier.padding(end = CouliSpacing.space4))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
            contentPadding = PaddingValues(end = CouliSpacing.space4),
        ) {
            // 键按位置 + 商品键：服务端重复的 product_key 不会撞键（R1-02）
            itemsIndexed(card.items, key = { index, product -> HomeListKeys.product(index, product.key) }) { _, product ->
                HomeScrollTile(product, controller, { note = it }, Modifier.width(tileWidth))
            }
        }
    }
    ProductNote(note, controller) { note = null }
}

/**
 * 首页私有横滑卡（Main-v3「今日精选」）：图（圆角 8 + 描边、`推广` 角标左上 8）+ 价格行（口径词 12 + 主价 17）+ 副行 + 窄卡返利块。
 * 画板上不显示标题与来源标签；读屏整卡一项，名称仍是「平台 + 标题」，后接卡内的价格与返利（各自的读屏句并入整卡）。不改共用的 ProductCardGrid。
 */
@Composable
internal fun HomeScrollTile(
    product: HomeProductUi,
    controller: HomeController,
    onInfo: (HomeProductUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    val data = product.data
    val tap = product.tap
    val price = data.price
    // 读屏整卡一句：平台 + 标题 + 价格（含副行）+ 返利；卡内价格与返利文字读屏隐藏，不重复（口径 i 仍可单独聚焦）
    val rebateShown = data.rebate.kind != RebateKind.NONE && !data.priceUnavailable
    val info = { onInfo(product) }.takeIf { data.showsDisclaimerButton }
    val spoken =
        buildList {
            add(data.platformName)
            add(data.title)
            data.priceUnavailableText?.let { add(it) }
            if (data.priceUnavailableText == null && price != null) {
                add(price.mainSpoken)
                addAll(price.subLines(specNote = data.specNote))
            }
            if (rebateShown) add(data.displayedRebate.spoken)
        }.joinToString(HomeDraftTexts.SEPARATOR)
    Column(
        modifier =
            modifier
                .then(if (tap != null) Modifier.clickable(role = Role.Button) { controller.open(tap) } else Modifier)
                .semantics(mergeDescendants = true) { contentDescription = spoken }
                .then(if (tap != null) Modifier.testTag(HomeTags.tap(tap.sectionId, tap.slot)) else Modifier),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        Box {
            ProductThumbnail(
                label = data.imageLabel,
                modifier = Modifier.fillMaxWidth().height(SCROLL_IMAGE_HEIGHT.dp),
                imageUrl = data.imageUrl,
            )
            product.adLabel?.let { AdLabel(it, Modifier.align(Alignment.TopStart).padding(CouliSpacing.space2)) }
        }
        when {
            data.priceUnavailableText != null -> {
                Text(
                    text = data.priceUnavailableText.orEmpty(),
                    style = CouliTypography.caption,
                    color = CouliColors.textSecondary,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }

            price != null -> {
                Column {
                    // 不显示返利区（暂无返利）时口径 i 放在价格行右侧，不随返利区一起消失（r1 S1-1，同 F-AND-06 网格卡）。
                    // 判据 1：主价在「整行 − 按钮」里放不下时按钮先让到下一行（靠右），主价独占整行；整行也放不下才由 AmountText 框内横滑
                    NumberLine(
                        trailing =
                            if (!rebateShown && info != null) {
                                { DisclaimerInfoButton(label = data.infoLabel, expanded = false, onToggle = info) }
                            } else {
                                null
                            },
                    ) {
                        // 价格读屏已并入整卡一句：这里整块清掉（含 PriceTagMain 自带的读屏句），不重复
                        Box(modifier = Modifier.clearAndSetSemantics {}) {
                            PriceTagMain(content = price, size = PriceTagSize.AI)
                        }
                    }
                    PriceSubLines(lines = price.subLines(specNote = data.specNote), modifier = Modifier.clearAndSetSemantics {})
                }
            }
        }
        if (rebateShown) {
            GridRebateBlock(data = data, onInfo = info, mergeSemantics = false, compactInfo = true)
        }
    }
}

/**
 * 横滑卡内容最窄宽度（px）：max(主价、返利块内边距 + 最宽一段返利金额、副行最宽金额、返利首行「钟表 + 预估返 + i」)。
 * 金额段与首行都不拆，放不下就加宽卡（横向滚动救不回卡内被裁的数字，r1 S1-2）。
 */
private fun ProductCardData.scrollTileMinWidth(
    metrics: AmountMetrics,
    density: Density,
): Int {
    val price = price?.takeIf { priceUnavailableText == null }
    val priceWidth = metrics.priceMain(price, PriceTagSize.AI)
    val subLines = metrics.subLineAmounts(price?.subLines(specNote = specNote).orEmpty())
    val rebateShown = rebate.kind != RebateKind.NONE && !priceUnavailable
    if (!rebateShown) {
        // 暂无返利：i 跟在主价右侧（48 命中区），主价那一块要连 i 一起放下
        val info = if (showsDisclaimerButton) with(density) { CouliSizes.touchTarget.roundToPx() } else 0
        return maxOf(priceWidth + info, subLines)
    }
    val shown = displayedRebate
    val blockPadding = with(density) { (CouliSpacing.space2 * 2).roundToPx() }
    val header =
        with(density) {
            val icon = if (shown.usesRebateAccent) CouliSizes.iconTiny + CouliSpacing.space1 else CouliSpacing.space0
            val info = if (showsDisclaimerButton) CouliSizes.iconSmall else CouliSpacing.space0
            (icon + info).roundToPx()
        } + metrics.textWidth(shown.label, CouliTypography.caption)
    val amounts = metrics.rebateSegments(shown, CouliTextStyles.amountBody)
    return maxOf(priceWidth, subLines, blockPadding + maxOf(header, amounts))
}

/** 横滑卡最窄宽 144、图高 104（Main-v3 画板实测值；任务书估 ≈134，以画板为准）。长金额 / 大字号时按 [scrollTileMinWidth] 加宽。 */
private const val SCROLL_TILE_WIDTH = 144
private const val SCROLL_IMAGE_HEIGHT = 104
