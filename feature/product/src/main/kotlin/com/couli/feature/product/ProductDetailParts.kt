package com.couli.feature.product

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonSize
import com.couli.core.designsystem.CouliButtonVariant
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliRadius
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTag
import com.couli.core.designsystem.CouliTagTone
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.ProductCardData
import com.couli.core.designsystem.ProductThumbnail
import com.couli.core.designsystem.Skeleton
import com.couli.core.designsystem.StatusCapsule
import com.couli.core.designsystem.couliCard

/** 内容列最大宽（大屏居中，D14）。 */
internal const val CONTENT_MAX_WIDTH = 600

/** 头图比例：画板 358 × 262（Product-v2 头图 264 含上下 1 描边），单图、contain（D2，G-D03）。 */
internal const val HERO_RATIO = 358f / 262f

private const val SKELETON_PRICE = 0.4f
private const val SKELETON_REBATE = 0.55f
private const val SKELETON_TITLE_SECOND = 0.7f

/** 详情内容区的内边距（Product-v2：上 8、左右 16、下 16；块间距 12）。 */
internal fun Modifier.detailContentPadding(): Modifier =
    padding(start = CouliSpacing.space4, end = CouliSpacing.space4, top = CouliSpacing.space2, bottom = CouliSpacing.space4)

/** 横向安全区（横屏刘海）；底部安全区由底栏按 max(导航栏, 16) 自己处理（D9）。 */
internal val horizontalSafeArea: WindowInsets
    @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)

/**
 * 头图（D2 / D3 / D6）：contain 整图可见、空白处露出占位底色；下架去色。推广角标（ad_label 原样，BR-TEXT-17，G-D04）固定左上 8，
 * 白底 + 1 描边（GUIDE §6）；读屏把角标读在图片名前（合并为一项）。
 */
@Composable
internal fun ProductHero(
    data: ProductCardData,
    description: String,
    grayscale: Boolean,
) {
    val ad = data.adLabel
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(HERO_RATIO)
                .semantics(mergeDescendants = true) {
                    // 合并节点就是读屏的「图片」：有图、加载失败占位、带推广角标都给图片角色（brief §2.2）
                    role = Role.Image
                    if (ad != null) contentDescription = ad
                },
    ) {
        ProductThumbnail(
            label = data.imageLabel,
            modifier = Modifier.matchParentSize(),
            description = description,
            imageUrl = data.imageUrl,
            contentScale = ContentScale.Fit,
            grayscale = grayscale,
        )
        if (ad != null) {
            CouliTag(text = ad, tone = CouliTagTone.OUTLINE, modifier = Modifier.align(Alignment.TopStart).padding(CouliSpacing.space2))
        }
    }
}

/**
 * 比价无返利的原因行（ProductPriceCompareCancelled-v2，D5）：价格卡内、返利标签的位置，灰胶囊「这次被判为比价，没有返利」+
 * 强调色【看看相似商品】（BR-PRICE-08）。
 */
@Composable
internal fun CompareReasonRow(
    reason: String,
    similar: String,
    onSimilar: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(ProductTags.COMPARE_REASON),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        StatusCapsule(text = reason, modifier = Modifier.weight(1f, fill = false))
        CouliButton(
            text = similar,
            onClick = onSimilar,
            modifier = Modifier.hitAreaBeyondVisualRow(),
            variant = CouliButtonVariant.TEXT,
            size = CouliButtonSize.COMPACT,
        )
    }
}

/** 比价原因行的视觉行高（画板胶囊 32；链接 min-height 44 + margin −6，行仍为 32）。 */
private val COMPARE_ROW_HEIGHT = CouliSpacing.space8

/**
 * 详情私有的「视觉尺寸与命中区分开」：按钮仍按 ≥ 48 量与摆（命中区、水波纹不变），但向父布局只报告扣掉上下各
 * (48 − 32) / 2 的高度，命中区上下等量溢出到行外（价格卡内边距内，不裁剪）。字号放大使按钮高于 48 时按同样差值扣，
 * 视觉行随字高增长，不截字。
 */
private fun Modifier.hitAreaBeyondVisualRow(): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        val overflow = (CouliSizes.touchTarget - COMPARE_ROW_HEIGHT).roundToPx()
        val height = (placeable.height - overflow).coerceAtLeast(0)
        layout(placeable.width, height) { placeable.place(0, (height - placeable.height) / 2) }
    }

/**
 * 底栏外壳（D9 / D14）：白底与顶部分隔线铺满整宽；控件在 600 列内居中，内边距 上 12、左右 16、下 max(导航栏, 16)。
 */
@Composable
internal fun BottomBarFrame(content: @Composable (Modifier) -> Unit) {
    val navigation = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottom: Dp = maxOf(navigation, CouliSpacing.space4)
    Column(modifier = Modifier.fillMaxWidth().background(CouliColors.backgroundSurface)) {
        Box(Modifier.fillMaxWidth().height(CouliSizes.hairline).background(CouliColors.borderSubtle))
        Box(modifier = Modifier.fillMaxWidth().windowInsetsPadding(horizontalSafeArea), contentAlignment = Alignment.TopCenter) {
            content(
                Modifier
                    .widthIn(max = CONTENT_MAX_WIDTH.dp)
                    .fillMaxWidth()
                    .padding(start = CouliSpacing.space4, end = CouliSpacing.space4, top = CouliSpacing.space3, bottom = bottom),
            )
        }
    }
}

/**
 * 底栏排法（G-D13）：左侧图标入口 + 主按钮占余宽；按测量放不下（主按钮文字一行的宽 > 余宽，窄屏 + 大字号）时改两行：
 * 上行左对齐入口，下行通栏主按钮（按钮文字可折行、不省略）。没有入口时主按钮通栏。
 */
@Composable
internal fun BottomBarLayout(
    modifier: Modifier,
    entries: (@Composable () -> Unit)?,
    button: @Composable () -> Unit,
) {
    Layout(contents = listOf(entries ?: NO_ENTRIES, button), modifier = modifier) { (entryMeasurables, buttonMeasurables), constraints ->
        val width = constraints.maxWidth
        val gap = CouliSpacing.space2.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val entry = entryMeasurables.firstOrNull()?.measure(loose.copy(maxHeight = Constraints.Infinity))
        val buttonMeasurable = buttonMeasurables.first()
        val entryWidth = entry?.let { it.width + gap } ?: 0
        val oneRow = entry == null || entryWidth + buttonMeasurable.maxIntrinsicWidth(Constraints.Infinity) <= width
        if (oneRow) {
            val buy = buttonMeasurable.measure(Constraints.fixedWidth((width - entryWidth).coerceAtLeast(0)))
            val height = maxOf(buy.height, entry?.height ?: 0)
            layout(width, height) {
                entry?.place(0, (height - entry.height) / 2)
                buy.place(entryWidth, (height - buy.height) / 2)
            }
        } else {
            val buy = buttonMeasurable.measure(Constraints.fixedWidth(width))
            val top = checkNotNull(entry).height + gap
            layout(width, top + buy.height) {
                entry.place(0, 0)
                buy.place(0, top)
            }
        }
    }
}

private val NO_ENTRIES: @Composable () -> Unit = {}

/** 底栏图标入口（Product-v2【问问 AI】）：24 图标在上、12 字在下，命中区 ≥ 48，系统水波纹。 */
@Composable
internal fun BottomEntry(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier =
            modifier
                .sizeIn(minWidth = CouliSizes.touchTarget, minHeight = CouliSizes.touchTarget)
                .clip(RoundedCornerShape(CouliRadius.small))
                .clickable(interactionSource = interaction, indication = LocalIndication.current, role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ENTRY_GAP.dp, Alignment.CenterVertically),
    ) {
        Icon(CouliIcons.chat, contentDescription = null, tint = CouliColors.textPrimary, modifier = Modifier.size(CouliSizes.iconLarge))
        Text(text = label, style = CouliTypography.caption, color = CouliColors.textSecondary)
    }
}

/** 图标与字的间距（画板 2）。 */
private const val ENTRY_GAP = 2

/**
 * 加载骨架（D10，GUIDE §3 按真实布局摆）：头图块（同比例）→ 白卡内 40% 价格行、返利块、标题两行 100% / 70% → 底栏按钮块。
 * 「加载中」不显示文字，只给读屏（polite 播报）。
 */
@Composable
internal fun LoadingSkeleton(
    description: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(ProductTags.LOADING)
                .semantics {
                    contentDescription = description
                    liveRegion = LiveRegionMode.Polite
                },
    ) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier =
                    Modifier
                        .widthIn(
                            max = CONTENT_MAX_WIDTH.dp,
                        ).fillMaxWidth()
                        .windowInsetsPadding(horizontalSafeArea)
                        .detailContentPadding(),
                verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
            ) {
                Skeleton(Modifier.fillMaxWidth().aspectRatio(HERO_RATIO))
                Column(
                    modifier = Modifier.fillMaxWidth().couliCard().padding(CouliSpacing.space4),
                    verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
                ) {
                    Skeleton(Modifier.fillMaxWidth(SKELETON_PRICE).height(CouliSpacing.space10))
                    Skeleton(Modifier.fillMaxWidth(SKELETON_REBATE).height(CouliSpacing.space8))
                    Skeleton(Modifier.fillMaxWidth().height(CouliSpacing.space4 + CouliSpacing.space1))
                    Skeleton(Modifier.fillMaxWidth(SKELETON_TITLE_SECOND).height(CouliSpacing.space4 + CouliSpacing.space1))
                }
            }
        }
        BottomBarFrame { inner ->
            Box(inner) {
                Skeleton(
                    Modifier
                        .fillMaxWidth()
                        .height(CouliSizes.touchTarget)
                        .clip(RoundedCornerShape(CouliRadius.button)),
                )
            }
        }
    }
}
