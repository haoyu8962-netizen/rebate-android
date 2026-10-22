package com.couli.feature.orders

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonSize
import com.couli.core.designsystem.CouliButtonVariant
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliFormat
import com.couli.core.designsystem.CouliIconButton
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliRadius
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.couliFocusTarget
import com.couli.core.orders.OrderErrorAction
import com.couli.core.orders.OrderQuery
import com.couli.core.orders.OrderTexts

/*
 * 订单列表头部（APP-08v-a O1–O8，Orders-v2 / OrdersClaimPinned / OrdersStale-v2）：标题行、置顶找回行、断网提示条、子 Tab、搜索框、分组胶囊、
 * 平台 / 月份筛选与眼睛。视觉尺寸按画板（Tab 44、搜索 44、胶囊 32），≥48 命中区由最小触控区扩展给出（不参与测量、不撑高视觉行，§9 风险 1）；
 * 胶囊行与筛选行之间留 16，两侧各扩 8 不重叠。
 */

/** 标题「订单」22/600（heading）+ 右上「未跟单？去找回」（置顶时不显示，G-O04）。标题行视觉高 44，入口命中 48 向行外溢出。 */
@Composable
internal fun TitleRow(
    texts: OrderTexts,
    showEntry: Boolean,
    onFind: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(
                    min = CouliSizes.visualTarget,
                ).padding(start = CouliSpacing.space4, end = CouliSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = texts.text("order_list.title"),
            style = CouliTextStyles.titleSemibold,
            color = CouliColors.textPrimary,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (showEntry) {
            CouliButton(
                text = texts.text("order_list.find_entry"),
                onClick = onFind,
                variant = CouliButtonVariant.TEXT,
                size = CouliButtonSize.COMPACT,
                modifier = Modifier.hitBeyondTitleRow().testTag(OrderTags.FIND_ENTRY),
            )
        }
    }
}

/**
 * 标题行右上入口（评审 r1 S2-4）：按钮照常按 ≥48 测量和摆放（命中区、水波纹、Role.Button 不变），向标题行只报告「按钮高 −(48−44)」并居中，
 * 命中区上下各溢出 2dp（上方是状态栏留白，下方是头部上内边距 8，不与 Tab 的命中区重叠）。默认字号标题行视觉高 44；字号放大使按钮高于 48 时
 * 按同一差值扣，行随字高增长、不截字。只用于订单标题行，不改共用 `CouliButton`。
 */
private fun Modifier.hitBeyondTitleRow(): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minHeight = 0))
        val trim = (CouliSizes.touchTarget - CouliSizes.visualTarget).roundToPx().coerceAtLeast(0)
        val height = (placeable.height - trim).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(placeable.width, height) { placeable.place(0, (height - placeable.height) / 2) }
    }

/**
 * 置顶找回行（OrdersClaimPinned，G-O04）：`brand.subtle` 浅底圆角整行，找回图标 20 +「未跟单？去找回」17/600 强调色 + 尾部 ›；整行一个目标
 * （Role.Button，≥48），去向与右上入口相同（FindOrder，不带参数）。只在 LazyColumn 外画，不改列表项下标（§9 风险 10）。
 */
@Composable
internal fun PinnedFindRow(
    texts: OrderTexts,
    onFind: () -> Unit,
) {
    val shape = RoundedCornerShape(CouliRadius.card)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = CouliSizes.touchTarget)
                .clip(shape)
                .background(CouliColors.brandSubtle)
                .clickable(role = Role.Button, onClick = onFind)
                .padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space3)
                .testTag(OrderTags.FIND_PINNED),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        Icon(
            CouliIcons.claim,
            contentDescription = null,
            tint = CouliColors.brandEmphasis,
            modifier = Modifier.size(CouliSizes.iconMedium),
        )
        Text(
            text = texts.text("order_list.find_entry"),
            style = CouliTextStyles.bodySemibold,
            color = CouliColors.brandEmphasis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            CouliIcons.chevron,
            contentDescription = null,
            tint = CouliColors.brandEmphasis,
            modifier = Modifier.size(CouliSizes.iconMedium),
        )
    }
}

/**
 * 「网络不可用，以下为 {time} 的数据」（role=status，礼貌播报；{time} 按 BR-TEXT-11）：警告浅底圆角 12、内边距 12，断网图标 20 + 14 字（警告文字色）。
 * 列表放在 600 列的头部内（[outer] 为 0）；详情沿用原外边距。
 */
@Composable
internal fun StaleBanner(
    texts: OrderTexts,
    fetchedAt: Long,
    now: Long,
    tag: String = OrderTags.STALE,
    outer: PaddingValues = PaddingValues(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space1),
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(outer)
                .background(CouliColors.statusWarningBackground, RoundedCornerShape(CouliRadius.button))
                .padding(CouliSpacing.space3)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            CouliIcons.wifiOff,
            contentDescription = null,
            tint = CouliColors.statusWarningText,
            modifier = Modifier.size(CouliSizes.iconMedium),
        )
        Text(
            text = texts.format(OrderErrorAction.STALE_KEY, mapOf("time" to CouliFormat.timeListPast(fetchedAt, now))),
            style = CouliTypography.footnote,
            color = CouliColors.statusWarningText,
        )
    }
}

/** 子 Tab「自购 / 分享」：次要底容器圆角 12、内边距 4；选中块白底圆角 8，视觉高 44（命中区 48 由触控区扩展给出）；Role.Tab + selected。 */
@Composable
internal fun ScopeTabs(
    texts: OrderTexts,
    scope: String,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(CouliColors.backgroundMuted, RoundedCornerShape(CouliRadius.button))
                .padding(CouliSpacing.space1),
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
    ) {
        listOf(OrderQuery.SELF to OrderTags.TAB_SELF, OrderQuery.SHARE to OrderTags.TAB_SHARE).forEach { (value, tag) ->
            val selected = value == scope
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .heightIn(min = CouliSizes.visualTarget)
                        .background(
                            if (selected) CouliColors.backgroundSurface else CouliColors.backgroundMuted,
                            RoundedCornerShape(CouliRadius.small),
                        ).selectable(selected = selected, role = Role.Tab) { onSelect(value) }
                        .testTag(tag),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = texts.text("order_list.tab.$value"),
                    style = if (selected) CouliTextStyles.footnoteSemibold else CouliTypography.footnote,
                    color = if (selected) CouliColors.textPrimary else CouliColors.textSecondary,
                )
            }
        }
    }
}

/** 状态分组胶囊（全部 / 预估中 / 已结算 / 无返利）：横滑，选中项滚入可见区；视觉 32（命中区 48 由触控区扩展给出）；选中状态读出。 */
@Composable
internal fun GroupChips(
    texts: OrderTexts,
    group: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val selectedIndex = OrderQuery.GROUPS.indexOf(group).coerceAtLeast(0)
    LaunchedEffect(selectedIndex) { listState.animateScrollToItem(selectedIndex) }
    LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2), modifier = Modifier.fillMaxWidth()) {
        itemsIndexed(OrderQuery.GROUPS) { _, value ->
            val selected = value == group
            OutlinedPill(
                selected = selected,
                modifier =
                    Modifier
                        .selectable(selected = selected, enabled = enabled, role = Role.Tab) { onSelect(value) }
                        .testTag(OrderTags.GROUP_PREFIX + value),
            ) {
                Text(
                    text = texts.text("order_status_group.$value"),
                    style = if (selected) CouliTextStyles.footnoteSemibold else CouliTypography.footnote,
                    color = if (selected) CouliColors.brandEmphasis else CouliColors.textPrimary,
                )
            }
        }
    }
}

/**
 * 头部胶囊外观（分组与筛选同款）：视觉最小高 32、左右 12、圆角满；未选白底 + 1 `border.subtle`，选中 `brand.subtle` 底（边同色）。[modifier]
 * 挂点击与标记——它在内边距之外，命中区与语义边界都是整个胶囊。
 */
@Composable
private fun OutlinedPill(
    selected: Boolean,
    modifier: Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(CouliRadius.pill)
    Row(
        modifier =
            Modifier
                .heightIn(min = CouliSpacing.space8)
                .clip(shape)
                .background(if (selected) CouliColors.brandSubtle else CouliColors.backgroundSurface)
                .border(CouliSizes.hairline, if (selected) CouliColors.brandSubtle else CouliColors.borderSubtle, shape)
                .then(modifier)
                .padding(horizontal = CouliSpacing.space3, vertical = CouliSpacing.space1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
    ) {
        content()
    }
}

/** 【平台】【月份】描边胶囊（展开状态读出）+ 眼睛按钮（金额隐藏，G21）。 */
@Composable
internal fun FilterRow(
    texts: OrderTexts,
    state: OrderListSnapshot,
    sheet: OrderSheet?,
    masked: Boolean,
    platformFocus: FocusRequester,
    monthFocus: FocusRequester,
    onSheet: (OrderSheet) -> Unit,
    onMask: () -> Unit,
) {
    // 窄屏、大字号且已选长筛选（「拼多多」+「2026 年 10 月」）时：两个筛选胶囊在左侧的可用宽度里换行，眼睛按钮固定在行尾、先量出完整 48 命中区
    // （Row 先量不带权重的子项；APP-08 共性第 11 条）。眼睛只按图标高参与行高，48 命中区上下溢出，与胶囊的命中扩展同高。
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        val platformLabel = state.conditions.platform?.let { texts.text("platform.$it") } ?: texts.text("order_list.filter.platform")
        val monthLabel = state.conditions.month?.let { monthText(texts, it) } ?: texts.text("order_list.filter.month")
        FlowRow(
            modifier = Modifier.weight(1f).testTag(OrderTags.FILTERS),
            horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space4),
        ) {
            FilterButton(platformLabel, sheet == OrderSheet.PLATFORM, !state.blocked, platformFocus, OrderTags.FILTER_PLATFORM) {
                onSheet(OrderSheet.PLATFORM)
            }
            FilterButton(
                monthLabel,
                sheet == OrderSheet.MONTH,
                !state.blocked,
                monthFocus,
                OrderTags.FILTER_MONTH,
            ) { onSheet(OrderSheet.MONTH) }
        }
        CouliIconButton(
            icon = if (masked) CouliIcons.eyeOff else CouliIcons.eye,
            label = texts.text(if (masked) OrderUi.MASK_SHOW_KEY else OrderUi.MASK_HIDE_KEY),
            onClick = onMask,
            tint = CouliColors.textSecondary,
            iconSize = CouliSizes.iconMedium,
            modifier = Modifier.visualHeight(CouliSizes.iconMedium).testTag(OrderTags.MASK),
        )
    }
}

@Composable
private fun FilterButton(
    label: String,
    expanded: Boolean,
    enabled: Boolean,
    focus: FocusRequester,
    tag: String,
    onClick: () -> Unit,
) {
    OutlinedPill(
        selected = false,
        modifier =
            Modifier
                .couliFocusTarget(focus)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics { if (expanded) collapse { true } else expand { true } }
                .testTag(tag),
    ) {
        // 文字与箭头分开分配空间：箭头先量（固定大小），文字用剩下的宽度、放不下时换行，不把箭头挤出去
        Text(
            text = label,
            style = CouliTypography.footnote,
            color = if (enabled) CouliColors.textPrimary else CouliColors.textSecondary,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(
            CouliIcons.chevronDown,
            contentDescription = null,
            tint = CouliColors.textSecondary,
            modifier = Modifier.size(CouliSizes.iconSmall).testTag(tag + ARROW_SUFFIX),
        )
    }
}

/** 「2026 年 10 月」。 */
internal fun monthText(
    texts: OrderTexts,
    month: String,
): String =
    texts.format(
        "order_list.filter.month_item",
        mapOf(
            "year" to month.take(YEAR_LENGTH),
            "month" to month.takeLast(2).trimStart('0'),
        ),
    )

/**
 * 只按 [visible] 高参与父布局的测量（不足时按内容实高），子项照常按自身大小（如 48 的图标按钮）测量、居中摆放，多出的部分上下溢出——命中区、
 * 水波纹与语义不变，视觉行不被 48 撑高（§9 风险 1）。
 */
internal fun Modifier.visualHeight(visible: Dp): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        val height = minOf(placeable.height, visible.roundToPx()).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(placeable.width, height) { placeable.place(0, (height - placeable.height) / 2) }
    }

private const val YEAR_LENGTH = 4
private const val ARROW_SUFFIX = "_arrow"
