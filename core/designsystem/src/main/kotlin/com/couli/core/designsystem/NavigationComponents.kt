package com.couli.core.designsystem

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabIndicatorScope
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp

/**
 * 二级页导航栏（组件清单 §3.4，DS-02【改系统】）：M3 `CenterAlignedTopAppBar`，左侧系统返回（Material Symbols arrow_back，
 * M3 `IconButton` 命中区 48），标题 17/600 居中，右侧操作或同宽占位。系统返回 / 预测性返回经 `BackHandler` 走同一个 [onBack]
 * （[handleSystemBack] 为 false 时只管图标）。大字号时栏高随标题行高增长，标题不截断（03 §10.4）。
 * [scrolled] 为 true 时（内容滚到栏下）铺 surface 底；默认透明，露出页面画布或头图。[backLabel] 是返回按钮的读屏名称（字典给）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavBar(
    title: String,
    backLabel: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    scrolled: Boolean = false,
    handleSystemBack: Boolean = true,
    trailing: @Composable () -> Unit = { Box(Modifier.size(CouliSizes.touchTarget)) },
) {
    BackHandler(enabled = handleSystemBack, onBack = onBack)
    val style = CouliTextStyles.bodySemibold
    TopBarFrame(title = title, style = style, sideSlots = 2, modifier = modifier) { height ->
        CenterAlignedTopAppBar(
            title = {
                Text(
                    text = title,
                    style = style,
                    color = CouliColors.textPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
            },
            navigationIcon = {
                // M3 1.4 的 IconButton 视觉 40、只扩触控区；读屏框也给足 48
                IconButton(onClick = onBack, modifier = Modifier.size(CouliSizes.touchTarget)) {
                    Icon(CouliIcons.systemBack, contentDescription = backLabel, tint = CouliColors.textPrimary)
                }
            },
            actions = { trailing() },
            expandedHeight = height,
            windowInsets = WindowInsets(0),
            colors = topBarColors(scrolled),
        )
    }
}

/** 一级 Tab 页标题栏（组件清单 §3.4「一级页」）：M3 `TopAppBar`，标题左对齐 22/600，右侧可放图标按钮。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageTitleBar(
    title: String,
    modifier: Modifier = Modifier,
    scrolled: Boolean = false,
    trailing: @Composable () -> Unit = {},
) {
    val style = CouliTextStyles.titleSemibold
    TopBarFrame(title = title, style = style, sideSlots = 1, modifier = modifier) { height ->
        TopAppBar(
            title = {
                Text(
                    text = title,
                    style = style,
                    color = CouliColors.textPrimary,
                    modifier = Modifier.semantics { heading() },
                )
            },
            actions = { trailing() },
            expandedHeight = height,
            windowInsets = WindowInsets(0),
            colors = topBarColors(scrolled),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun topBarColors(scrolled: Boolean) =
    TopAppBarDefaults.topAppBarColors(
        containerColor = if (scrolled) CouliColors.backgroundSurface else Color.Transparent,
        scrolledContainerColor = CouliColors.backgroundSurface,
        navigationIconContentColor = CouliColors.textPrimary,
        titleContentColor = CouliColors.textPrimary,
        actionIconContentColor = CouliColors.textPrimary,
    )

/**
 * 系统栏的高度不随字号变：先按可用宽度量标题（两侧各留 [sideSlots] 个 48 命中区），栏高取 max(48, 标题高 + 上下 4)，
 * 200% 字号下标题换行也不被裁切。
 */
@Composable
private fun TopBarFrame(
    title: String,
    style: TextStyle,
    sideSlots: Int,
    modifier: Modifier,
    bar: @Composable (Dp) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val reserved = (CouliSizes.touchTarget + CouliSpacing.space2) * sideSlots
        val titleWidth = (maxWidth - reserved).coerceAtLeast(CouliSizes.touchTarget)
        val titleHeight =
            remember(title, style, titleWidth, density) {
                with(density) {
                    measurer
                        .measure(title, style, constraints = Constraints(maxWidth = titleWidth.roundToPx()))
                        .size.height
                        .toDp()
                }
            }
        bar(maxOf(CouliSizes.touchTarget, titleHeight + CouliSpacing.space2))
    }
}

/** 顶部标签页的一项。 */
@Immutable
data class TopTabItem(
    val label: String,
)

/**
 * 顶部标签页（组件清单 §3.4 `TopTabs`，DS-02【改系统】）：M3 `PrimaryTabRow`（固定）/ `PrimaryScrollableTabRow`（可横滑），
 * 指示条 brand.primary 2（T10），选中字 brand.emphasis 600，未选 text.secondary；读屏「标签，已选中」由 M3 Tab 给。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopTabs(
    items: List<TopTabItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = false,
) {
    val indicator: @Composable TabIndicatorScope.() -> Unit = {
        TabRowDefaults.PrimaryIndicator(
            modifier = Modifier.tabIndicatorOffset(selectedIndex, matchContentSize = true),
            height = CouliComponentTokens.tabIndicatorHeight,
            color = CouliColors.brandPrimary,
        )
    }
    val divider: @Composable () -> Unit = { HorizontalDivider(thickness = CouliSizes.hairline, color = CouliColors.borderSubtle) }
    val tabs: @Composable () -> Unit = {
        items.forEachIndexed { index, item ->
            val selected = index == selectedIndex
            Tab(
                selected = selected,
                onClick = { onSelect(index) },
                selectedContentColor = CouliColors.brandEmphasis,
                unselectedContentColor = CouliColors.textSecondary,
            ) {
                // 用内容版 Tab：文字版固定 48 高，200% 字号会裁字；这里最小 48、随字号长高
                Text(
                    text = item.label,
                    style = if (selected) CouliTextStyles.bodySemibold else CouliTypography.body,
                    textAlign = TextAlign.Center,
                    modifier =
                        Modifier
                            .heightIn(min = CouliSizes.touchTarget)
                            .wrapContentHeight()
                            .padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space2),
                )
            }
        }
    }
    if (scrollable) {
        PrimaryScrollableTabRow(
            selectedTabIndex = selectedIndex,
            modifier = modifier,
            containerColor = CouliColors.backgroundSurface,
            contentColor = CouliColors.brandEmphasis,
            edgePadding = CouliSpacing.space2,
            indicator = indicator,
            divider = divider,
            tabs = tabs,
        )
    } else {
        PrimaryTabRow(
            selectedTabIndex = selectedIndex,
            modifier = modifier,
            containerColor = CouliColors.backgroundSurface,
            contentColor = CouliColors.brandEmphasis,
            indicator = indicator,
            divider = divider,
            tabs = tabs,
        )
    }
}

/** 底部 Tab 的一项（文字由调用方给：首页 / AI 助手 / 订单 / 我的）。 */
@Immutable
data class TabItem(
    val icon: ImageVector,
    val label: String,
    /** 不为空时图标右上画红点角标（[CouliBadge]），读屏读这句（如「有未读」）。 */
    val badgeDescription: String? = null,
)

/**
 * 一级 Tab 栏（GUIDE §3）：白底、上 1 分隔线、4 等分；选中项图标 #C44820、文字 #A83B1F 600，未选 #655E57。
 * 选中状态经 selectable 读出，不只靠颜色（选中项另有字重变化）。系统导航区由调用方加 navigationBarsPadding。
 */
@Composable
fun TabBar(
    items: List<TabItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().background(CouliColors.backgroundSurface)) {
        Box(Modifier.fillMaxWidth().height(CouliSizes.hairline).background(CouliColors.borderSubtle))
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = CouliSpacing.space1)
                    .selectableGroup()
                    .semantics { collectionInfo = CollectionInfo(rowCount = 1, columnCount = items.size) },
        ) {
            items.forEachIndexed { index, item ->
                val selected = index == selectedIndex
                val interaction = remember { MutableInteractionSource() }
                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .heightIn(min = CouliSizes.touchTarget)
                            .couliFocusRing(focusRingVisible(interaction), CouliRadius.small)
                            .selectable(
                                selected = selected,
                                interactionSource = interaction,
                                indication = LocalIndication.current,
                                role = Role.Tab,
                                onClick = { onSelect(index) },
                            ).semantics {
                                // 读屏「标签，第 n 个，共 4 个，已选中」（adaptation-a11y-spec）：Role.Tab + 选中态 + 集合位置
                                collectionItemInfo = CollectionItemInfo(rowIndex = 0, rowSpan = 1, columnIndex = index, columnSpan = 1)
                            }.padding(vertical = CouliSpacing.space1),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1 / 2, Alignment.CenterVertically),
                ) {
                    Box {
                        Icon(
                            item.icon,
                            contentDescription = null,
                            tint = if (selected) CouliColors.brandPrimary else CouliColors.textSecondary,
                            modifier = Modifier.size(CouliSizes.iconLarge),
                        )
                        if (item.badgeDescription != null) {
                            CouliBadge(contentDescription = item.badgeDescription, modifier = Modifier.align(Alignment.TopEnd))
                        }
                    }
                    Text(
                        text = item.label,
                        style = if (selected) CouliTextStyles.captionSemibold else CouliTypography.caption,
                        color = if (selected) CouliColors.brandEmphasis else CouliColors.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
