package com.couli.feature.orders

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.couli.core.designsystem.AmountGrid
import com.couli.core.designsystem.AmountValueSize
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonSize
import com.couli.core.designsystem.CouliButtonVariant
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliFormat
import com.couli.core.designsystem.CouliIconButton
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliRadius
import com.couli.core.designsystem.CouliSheet
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliToast
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.EmptyState
import com.couli.core.designsystem.ErrorAnnouncement
import com.couli.core.designsystem.ErrorState
import com.couli.core.designsystem.PlatformBadge
import com.couli.core.designsystem.ProductThumbnail
import com.couli.core.designsystem.Skeleton
import com.couli.core.designsystem.StateAction
import com.couli.core.designsystem.couliCard
import com.couli.core.designsystem.couliFocusTarget
import com.couli.core.orders.CreditPeriodText
import com.couli.core.orders.OrderAmountCells
import com.couli.core.orders.OrderErrorAction
import com.couli.core.orders.OrderFormat
import com.couli.core.orders.OrderMonthOptions
import com.couli.core.orders.OrderNumberDisplay
import com.couli.core.orders.OrderPlatformOptions
import com.couli.core.orders.OrderQuery
import com.couli.core.orders.OrderStatusText
import com.couli.core.orders.OrderSummary
import com.couli.core.orders.OrderTexts
import com.couli.core.orders.OrderTitleImage
import com.couli.core.orders.PendingTrackCards
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 订单列表（OrderList 路由，订单 Tab 根；设计稿 Orders / OrdersShare / OrdersEmpty / OrdersShareEmpty / OrdersSearch / OrdersSearchEmpty /
 * OrdersFilterPlatform / OrdersFilterMonth / OrdersStale；适配 AdaptNarrowOrders、A11yHitOrders、AdaptBackRestore）：
 * 标题「订单」+ 右上常驻「未跟单？去找回」（G06）；子 Tab「自购 / 分享」；搜索框（自购「搜订单号或商品名」、分享「搜商品名」，最多 40 字，按键盘
 * 「搜索」或清空才发请求）；状态分组胶囊（横滑、选中项滚入可见区）；【平台】【月份】半屏单选；眼睛按钮（金额隐藏）；列表卡（整卡打开详情，卡内
 * 【复制】是独立目标）；下拉刷新、上拉加载更多；自购子 Tab 顶部是待跟单卡。大屏内容限宽 600 居中。
 */
@Composable
fun OrderListScreen(config: OrderListConfig) {
    val model: OrderListViewModel = viewModel(key = LIST_VM_KEY) { OrderListViewModel(config, createSavedStateHandle()) }
    model.update(config)
    val state by model.controller.state.collectAsStateWithLifecycle()
    val input by model.input.collectAsStateWithLifecycle()
    val sheet by model.sheet.collectAsStateWithLifecycle()
    // 渲染身份（R2-01，APP-09 共性第 63 条）：组合时取出普通值，本次组合交给控件的每个回调都捕获这一份；执行时只比较
    val ticket = model.ticket.collectAsStateWithLifecycle().value
    val actions = remember(model, ticket) { OrderListActions(model, ticket) }
    val noMask = remember { MutableStateFlow(false) }
    val masked by (config.mask?.hidden ?: noMask).collectAsStateWithLifecycle()
    val texts = config.texts
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) model.onStarted() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val platformFocus = remember { FocusRequester() }
    val monthFocus = remember { FocusRequester() }
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(CouliColors.backgroundCanvas)
                .semantics { this[OrderListActions.Key] = actions }
                .testTag(OrderTags.LIST_PAGE),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(modifier = Modifier.widthIn(max = OrderUi.MAX_WIDTH.dp).fillMaxSize().statusBarsPadding()) {
            TitleRow(texts) { config.env.open(OrderRoutes.FIND_ORDER, EMPTY) }
            state.staleAt?.let { StaleBanner(texts, it, config.clock()) }
            Column(
                modifier = Modifier.padding(horizontal = CouliSpacing.space4).padding(top = CouliSpacing.space2),
                verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
            ) {
                ScopeTabs(texts, state.scope, actions.onScope)
                SearchField(
                    texts,
                    input,
                    state.scope,
                    enabled = !state.blocked,
                    onInput = actions.onInput,
                    onSubmit = actions.onSubmit,
                    onClear = actions.onClear,
                )
                GroupChips(texts, state.conditions.group, enabled = !state.blocked, onSelect = actions.onGroup)
                FilterRow(
                    texts = texts,
                    state = state,
                    sheet = sheet,
                    masked = masked,
                    platformFocus = platformFocus,
                    monthFocus = monthFocus,
                    onSheet = actions.onSheet,
                    onMask = { config.mask?.toggle() },
                )
            }
            OrderListBody(model, actions, config, state, masked, Modifier.weight(1f))
        }
        state.toast?.let { toast ->
            // 错误提示经统一的错误消息生成（带 trace6；APP-08 共性第 8 条）
            val message = toast.view?.let { OrderErrorAction.message(texts, it) } ?: texts.text(toast.key)
            ToastHost(message, toast.id, toast.error, OrderTags.TOAST) { model.controller.consumeToast(toast.id) }
        }
    }
    when (sheet) {
        OrderSheet.PLATFORM -> {
            PlatformSheet(texts, state.conditions.platform, platformFocus, actions.onPlatform, actions.onCloseSheet)
        }

        OrderSheet.MONTH -> {
            MonthSheet(
                texts,
                state.conditions.month,
                state.earliestVisibleDate,
                config.clock(),
                monthFocus,
                actions.onMonth,
                actions.onCloseSheet,
            )
        }

        null -> {
            Unit
        }
    }
}

@Composable
private fun TitleRow(
    texts: OrderTexts,
    onFind: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(
                    min = CouliSizes.touchTarget,
                ).padding(start = CouliSpacing.space4, end = CouliSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = texts.text("order_list.title"),
            style = CouliTextStyles.titleSemibold,
            color = CouliColors.textPrimary,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        CouliButton(
            text = texts.text("order_list.find_entry"),
            onClick = onFind,
            variant = CouliButtonVariant.TEXT,
            size = CouliButtonSize.COMPACT,
            modifier = Modifier.testTag(OrderTags.FIND_ENTRY),
        )
    }
}

/** 「网络不可用，以下为 {time} 的数据」（role=status，礼貌播报；{time} 按 BR-TEXT-11）。 */
@Composable
internal fun StaleBanner(
    texts: OrderTexts,
    fetchedAt: Long,
    now: Long,
    tag: String = OrderTags.STALE,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space1)
                .background(CouliColors.statusWarningBackground, RoundedCornerShape(CouliRadius.button))
                .padding(CouliSpacing.space3)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            CouliIcons.alert,
            contentDescription = null,
            tint = CouliColors.statusWarningText,
            modifier = Modifier.size(CouliSizes.iconSmall),
        )
        Text(
            text = texts.format(OrderErrorAction.STALE_KEY, mapOf("time" to CouliFormat.timeListPast(fetchedAt, now))),
            style = CouliTypography.footnote,
            color = CouliColors.textPrimary,
        )
    }
}

/** 子 Tab「自购 / 分享」：分段控件，选中状态读出（Role.Tab + selected）。 */
@Composable
private fun ScopeTabs(
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
                        .heightIn(min = CouliSizes.touchTarget)
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

/** 搜索框（G11）：最多 40 个 Unicode 字符；键盘「搜索」或清空才发请求，不边输边搜。 */
@Composable
private fun SearchField(
    texts: OrderTexts,
    input: String,
    scope: String,
    enabled: Boolean,
    onInput: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
) {
    val focus = LocalFocusManager.current
    val placeholder = texts.text(if (scope == OrderQuery.SHARE) "order_list.search.placeholder_share" else "order_list.search.placeholder")
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = CouliSizes.touchTarget)
                .border(CouliSizes.hairline, CouliColors.borderControl, RoundedCornerShape(CouliRadius.button))
                .background(CouliColors.backgroundSurface, RoundedCornerShape(CouliRadius.button))
                .padding(start = CouliSpacing.space3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        Icon(
            CouliIcons.search,
            contentDescription = null,
            tint = CouliColors.textSecondary,
            modifier = Modifier.size(CouliSizes.iconMedium),
        )
        Box(modifier = Modifier.weight(1f).padding(vertical = CouliSpacing.space2)) {
            if (input.isEmpty()) Text(text = placeholder, style = CouliTypography.body, color = CouliColors.textSecondary)
            BasicTextField(
                value = input,
                onValueChange = onInput,
                enabled = enabled,
                singleLine = true,
                textStyle = CouliTypography.body.copy(color = CouliColors.textPrimary),
                cursorBrush = SolidColor(CouliColors.brandPrimary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions =
                    KeyboardActions(
                        onSearch = {
                            onSubmit()
                            focus.clearFocus()
                        },
                    ),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = texts.text("order_list.search.label") }
                        .testTag(OrderTags.SEARCH),
            )
        }
        if (input.isNotEmpty()) {
            CouliIconButton(
                icon = CouliIcons.close,
                label = texts.text("order_list.search.clear"),
                onClick = onClear,
                tint = CouliColors.textSecondary,
                iconSize = CouliSizes.iconMedium,
                enabled = enabled,
                modifier = Modifier.testTag(OrderTags.SEARCH_CLEAR),
            )
        }
    }
}

/** 状态分组胶囊（全部 / 预估中 / 已结算 / 无返利）：横滑，选中项滚入可见区；视觉高 32，命中区 48；选中状态读出。 */
@Composable
private fun GroupChips(
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
            Box(
                modifier =
                    Modifier
                        .heightIn(min = CouliSizes.touchTarget)
                        .selectable(selected = selected, enabled = enabled, role = Role.Tab) { onSelect(value) }
                        .testTag(OrderTags.GROUP_PREFIX + value),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = texts.text("order_status_group.$value"),
                    style = if (selected) CouliTextStyles.footnoteSemibold else CouliTypography.footnote,
                    color = if (selected) CouliColors.brandEmphasis else CouliColors.textSecondary,
                    modifier =
                        Modifier
                            .background(
                                if (selected) CouliColors.brandSubtle else CouliColors.backgroundSurface,
                                RoundedCornerShape(CouliRadius.pill),
                            ).padding(horizontal = CouliSpacing.space3, vertical = CouliSpacing.space1),
                )
            }
        }
    }
}

/** 【平台】【月份】（展开状态读出）+ 眼睛按钮（金额隐藏，G21）。 */
@Composable
private fun FilterRow(
    texts: OrderTexts,
    state: OrderListSnapshot,
    sheet: OrderSheet?,
    masked: Boolean,
    platformFocus: FocusRequester,
    monthFocus: FocusRequester,
    onSheet: (OrderSheet) -> Unit,
    onMask: () -> Unit,
) {
    // 窄屏、大字号且已选长筛选（「拼多多」+「2026 年 10 月」）时：两个筛选按钮在左侧的可用宽度里换行，眼睛按钮固定在行尾、先量出完整 48 命中区
    // （Row 先量不带权重的子项；APP-08 共性第 11 条）
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
            modifier = Modifier.testTag(OrderTags.MASK),
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
    Row(
        modifier =
            Modifier
                .heightIn(min = CouliSizes.touchTarget)
                .couliFocusTarget(focus)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics { if (expanded) collapse { true } else expand { true } }
                .padding(horizontal = CouliSpacing.space2)
                .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
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
private fun monthText(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrderListBody(
    model: OrderListViewModel,
    actions: OrderListActions,
    config: OrderListConfig,
    state: OrderListSnapshot,
    masked: Boolean,
    modifier: Modifier,
) {
    val texts = config.texts
    val listState = rememberLazyListState(model.anchor.index, model.anchor.offset)
    ScrollKeeping(model, actions, state, listState, leading = if (state.scope == OrderQuery.SELF && model.pending != null) 1 else 0)
    val pullState = rememberPullToRefreshState()
    // 下拉刷新：42901 禁用期内手势不可用（enabled = false；执行层也拦）
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .pullToRefresh(
                    isRefreshing = state.refreshing,
                    state = pullState,
                    enabled = !state.blocked,
                    onRefresh = actions.onRefresh,
                ),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().testTag(OrderTags.LIST),
            contentPadding =
                androidx.compose.foundation.layout
                    .PaddingValues(CouliSpacing.space4),
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
        ) {
            val pending = model.pending
            if (state.scope == OrderQuery.SELF && pending != null) {
                item(key = PENDING_KEY) {
                    PendingTrackCards(
                        pending,
                        texts,
                        onClaim = { config.env.open(OrderRoutes.FIND_ORDER, EMPTY) },
                        readonly = config.readonly,
                    )
                }
            }
            when (state.phase) {
                OrderListPhase.LOADING -> {
                    items(SKELETON_COUNT) { SkeletonOrderCard(texts) }
                }

                OrderListPhase.ERROR -> {
                    item(key = ERROR_KEY) { ListError(texts, state, actions.onRetry) }
                }

                OrderListPhase.EMPTY -> {
                    item(key = EMPTY_KEY) { ListEmpty(texts, state, config) }
                }

                OrderListPhase.CONTENT -> {
                    // 渲染时取出普通值（APP-09 共性第 63 条）：复制回调只用这一轮的轮次，执行时不读最新状态（APP-08 共性第 41 条）
                    val round = state.round
                    val identity = state.identity
                    items(state.items, key = { it.orderId }) { item ->
                        OrderCard(
                            item = item,
                            texts = texts,
                            masked = masked,
                            now = config.clock(),
                            onOpen = { actions.onOpen(item.orderId) },
                            onCopy = item.orderNo?.let { no -> { actions.onCopy(item.orderId, no, round, identity) } },
                        )
                    }
                    item(key = FOOTER_KEY) { ListFooter(texts, state, actions.onRetry) }
                }

                else -> {
                    Unit
                }
            }
            val listed = state.phase == OrderListPhase.CONTENT || state.phase == OrderListPhase.EMPTY
            state.earliestVisibleDate?.takeIf { listed }?.let { date ->
                item(key = HISTORY_KEY) {
                    Text(
                        text = texts.format("order_list.history_hint", mapOf("date" to CouliFormat.dateOnly(date, config.clock()))),
                        style = CouliTypography.caption,
                        color = CouliColors.textSecondary,
                        modifier = Modifier.fillMaxWidth().testTag(OrderTags.HISTORY_HINT),
                    )
                }
            }
        }
        PullToRefreshDefaults.Indicator(
            state = pullState,
            isRefreshing = state.refreshing,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

/**
 * 滚动位置：新一轮查询滚回顶部（返回本页、切回 Tab、重建、恢复时轮次没变，不滚）；记录锚点（第一个可见项的下标、偏移与订单号）；接近底部时加载更多。
 * 进程回收后的一次性恢复（APP-06a 第 40–43 条，APP-08 共性第 10 条）：按保存的订单号找目标卡——列表首次可交互时先回到顶部并记下基准位置；目标
 * 还没加载就按保存的查询条件继续翻页（不丢弃恢复任务）；目标就绪后一次 scrollToItem 让目标卡顶对齐，不做卡内偏移细调；每次挪动前比对基准，被
 * 用户（或别的来源）挪过就取消；翻到底、翻页失败或列表变空仍找不到时放弃，停在顶部。[leading] 为列表卡前面的固定项数（待跟单卡区）。
 */
@Composable
@Suppress("CyclomaticComplexMethod") // 一次性恢复逐步判定
private fun ScrollKeeping(
    model: OrderListViewModel,
    actions: OrderListActions,
    state: OrderListSnapshot,
    listState: LazyListState,
    leading: Int,
) {
    // 放弃恢复（换条件、列表变空或失败、被挪动、翻到底仍找不到）：恢复期间冻结的锚点立即改记当前位置，不留旧目标（共性第 17、24 条）
    val giveUp = {
        model.restoreDone()
        actions.onScrolled(anchorOf(listState, model))
    }
    LaunchedEffect(state.round) {
        if (model.seenRound != state.round) {
            model.seenRound = state.round
            // 用户换了条件（新一轮查询）：放弃进行中的恢复，之后照常记录锚点（APP-08 共性第 17 条）
            model.restoreDone()
            listState.scrollToItem(0)
            actions.onScrolled(anchorOf(listState, model))
        }
    }
    val restoreBase = remember { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(state.phase, state.items.size, state.endReached, state.loadingMore, state.loadMoreError) {
        val target = model.pendingRestore ?: return@LaunchedEffect
        // 每次挂起（滚动）之后复核：恢复任务仍是这一个、查询轮次没变（会话换代、换条件都会清掉恢复或换轮次），失效后不滚动、不持久化（共性第 24 条）
        val round = state.round
        val valid = { model.pendingRestore == target && model.controller.state.value.round == round && model.live(actions.ticket) }
        when (state.phase) {
            OrderListPhase.CONTENT -> Unit
            OrderListPhase.EMPTY, OrderListPhase.ERROR -> return@LaunchedEffect giveUp()
            else -> return@LaunchedEffect
        }
        val base =
            restoreBase.value ?: run {
                // 基准在列表首次可交互时建立：先回到顶部（列表自己保存的旧下标会被第一页的条数截断，不能当基准）
                listState.scrollToItem(0)
                listState.position().also { restoreBase.value = it }
            }
        if (!valid()) return@LaunchedEffect
        if (listState.position() != base) return@LaunchedEffect giveUp()
        val row = state.items.indexOfFirst { it.orderId == target }
        when {
            row >= 0 -> {
                listState.scrollToItem(leading + row)
                if (!valid()) return@LaunchedEffect
                model.restoreDone()
                // 对齐完成才更新持久化锚点（恢复期间的滚动记录被冻结，这里补记一次落点）
                actions.onScrolled(OrderAnchor(leading + row, 0, target))
            }

            state.loadMoreError != null || state.endReached -> {
                giveUp()
            }

            !state.loadingMore -> {
                if (!actions.onLoadMore()) giveUp()
            }

            else -> {
                Unit
            }
        }
    }
    // 渲染身份换了（换号、清理）就重启：旧收集器捕获的旧身份不再写锚点
    LaunchedEffect(listState, actions) {
        snapshotFlow { anchorOf(listState, model) }
            .distinctUntilChanged()
            .collect { actions.onScrolled(it) }
    }
    LaunchedEffect(listState, state.items.size, state.endReached, actions) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo
                .lastOrNull()
                ?.index ?: 0
        }.distinctUntilChanged()
            .collect { last -> if (last >= listState.layoutInfo.totalItemsCount - LOAD_AHEAD) actions.onLoadMore() }
    }
}

/**
 * 当前锚点：顶部第一张没有整张滚进上内边距的项（列表有上内边距，顶对齐时前一项会露在内边距里，不能把它当锚点）；它是当前列表里的订单卡时带订单号。
 */
private fun anchorOf(
    listState: LazyListState,
    model: OrderListViewModel,
): OrderAnchor {
    val first = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.offset + it.size > 0 }
    val id =
        (first?.key as? String)?.takeIf { k ->
            model.controller.state.value.items
                .any { it.orderId == k }
        }
    return OrderAnchor(first?.index ?: listState.firstVisibleItemIndex, first?.let { -it.offset } ?: 0, id)
}

@Composable
private fun ListError(
    texts: OrderTexts,
    state: OrderListSnapshot,
    onRetry: () -> Boolean,
) {
    val view = state.error ?: return
    Box(modifier = Modifier.fillMaxWidth().testTag(OrderTags.ERROR)) {
        ErrorState(
            title = texts.text("order_list.load_failed"),
            message = OrderErrorAction.message(texts, view),
            // 42901 禁用期内【重试】置灰（与执行层同一份禁用期）
            retry = StateAction(texts.text("order_list.retry"), enabled = !state.blocked) { onRetry() },
        )
    }
}

@Composable
private fun ListEmpty(
    texts: OrderTexts,
    state: OrderListSnapshot,
    config: OrderListConfig,
) {
    if (state.filtered) {
        // OrdersSearchEmpty：带搜索词或筛选的无结果（分享子 Tab 加「分享订单只按商品名搜索」），role=status
        Box(modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }.testTag(OrderTags.SEARCH_EMPTY)) {
            EmptyState(
                title = texts.text("order_list.search.none"),
                message =
                    texts
                        .text(
                            "order_list.search.share_note",
                        ).takeIf { state.scope == OrderQuery.SHARE && state.conditions.q != null },
            )
        }
        return
    }
    val scope = state.scope
    Box(modifier = Modifier.fillMaxWidth().testTag(OrderTags.EMPTY)) {
        EmptyState(
            title = texts.text("order_list.empty.$scope.title"),
            message = texts.text("order_list.empty.$scope.message"),
            icon = CouliIcons.orders,
            action = StateAction(texts.text("order_list.empty.action")) { config.env.open(OrderRoutes.HOME, EMPTY) },
        )
    }
}

/** 列表底部：加载中 / 翻页失败（错误文案 + trace6 +【重试】，先播报、焦点到【重试】）/ 上拉加载更多 / 搜索结果数。 */
@Composable
private fun ListFooter(
    texts: OrderTexts,
    state: OrderListSnapshot,
    onRetry: () -> Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        val error = state.loadMoreError
        when {
            state.loadingMore -> {
                Text(text = texts.text("order_list.loading"), style = CouliTypography.caption, color = CouliColors.textSecondary)
            }

            error != null -> {
                val retryFocus = remember { FocusRequester() }
                val message = OrderErrorAction.message(texts, error)
                ErrorAnnouncement(message, retryFocus)
                Text(
                    text = message,
                    style = CouliTypography.footnote,
                    color = CouliColors.textSecondary,
                    modifier = Modifier.testTag(OrderTags.LOAD_MORE_ERROR),
                )
                CouliButton(
                    text = texts.text("order_list.retry"),
                    onClick = { onRetry() },
                    variant = CouliButtonVariant.SECONDARY,
                    size = CouliButtonSize.COMPACT,
                    enabled = !state.blocked,
                    modifier = Modifier.couliFocusTarget(retryFocus).testTag(OrderTags.RETRY),
                )
            }

            !state.endReached -> {
                Text(
                    text = texts.text("order_list.load_more"),
                    style = CouliTypography.caption,
                    color = CouliColors.textSecondary,
                    modifier = Modifier.testTag(OrderTags.LOAD_MORE),
                )
            }

            else -> {
                Unit
            }
        }
        state.conditions.q?.let {
            Text(
                text = texts.format("order_list.search.found", mapOf("n" to state.items.size.toString())),
                style = CouliTypography.caption,
                color = CouliColors.textSecondary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(OrderTags.SEARCH_FOUND),
            )
        }
    }
}

/**
 * 订单卡（Orders）：整卡一个目标（打开详情，读屏名称含商品名），卡内【复制】是独立的第二个目标（点击不穿透）。来源 + 付款时间；商品图（只在加载中、
 * 为 null 或失败时占位）+ 标题（is_other_product 用「好友购买的其他商品」）+ 件数；订单号；两列金额格；状态行（+ 预计结算月份）。
 */
@Composable
internal fun OrderCard(
    item: OrderSummary,
    texts: OrderTexts,
    masked: Boolean,
    now: Long,
    onOpen: () -> Unit,
    onCopy: (() -> Unit)?,
) {
    val titleView = OrderTitleImage.of(item)
    val title = titleView.titleKey?.let(texts::text) ?: titleView.title.orEmpty()
    val status = OrderStatusText.of(item.displayStatus)
    val (cells, stackOrder) = OrderUi.listCells(texts, OrderAmountCells.of(item))
    val period = CreditPeriodText.of(item.displayStatus, null, now)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .couliCard()
                .clickable(role = Role.Button, onClick = onOpen)
                .padding(CouliSpacing.space4)
                .testTag(OrderTags.CARD_PREFIX + item.orderId),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
            PlatformBadge(name = texts.text("platform.${item.platform}"))
            OrderFormat.epochMillis(item.paidAt)?.let {
                Text(text = CouliFormat.timeListPast(it, now), style = CouliTypography.caption, color = CouliColors.textSecondary)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3)) {
            ProductThumbnail(label = texts.text("order_list.image_placeholder"), size = THUMB.dp, imageUrl = titleView.imageUrl)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
                if (title.isNotEmpty()) {
                    Text(
                        text = title,
                        style = CouliTypography.footnote,
                        color = CouliColors.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                item.quantity?.let {
                    Text(
                        text = texts.format("order_list.quantity", mapOf("n" to it.toString())),
                        style = CouliTypography.caption,
                        color = CouliColors.textSecondary,
                    )
                }
            }
        }
        OrderNumberRow(
            number = OrderNumberDisplay.of(item),
            texts = texts,
            onCopy = onCopy,
            textTag = OrderTags.ORDER_NO_PREFIX + item.orderId,
            copyTag = OrderTags.COPY_PREFIX + item.orderId,
        )
        AmountGrid(cells = cells, valueSize = AmountValueSize.BODY, mask = OrderUi.mask(texts, masked), stackOrder = stackOrder)
        // 列表只给状态文案；预计结算月份要 expected_credit_period（只在详情里有），列表行没有这个字段
        StatusLine(item.displayStatus, OrderUi.statusLabel(texts, status.labelKey, item.clawbackFen, masked), period.monthText)
    }
}

/** 列表骨架（GUIDE 未出订单骨架稿，按卡片结构自画）：读屏「加载中」。 */
@Composable
private fun SkeletonOrderCard(texts: OrderTexts) {
    val loading = texts.text("order_list.loading")
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .couliCard()
                .padding(CouliSpacing.space4)
                .semantics {
                    contentDescription = loading
                }.testTag(OrderTags.SKELETON),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        Skeleton(Modifier.size(width = SKELETON_BADGE.dp, height = CouliSpacing.space5))
        Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3)) {
            Skeleton(Modifier.size(THUMB.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
                Skeleton(Modifier.fillMaxWidth().heightIn(min = CouliSpacing.space4))
                Skeleton(Modifier.fillMaxWidth(SKELETON_LINE).heightIn(min = CouliSpacing.space4))
            }
        }
        Skeleton(Modifier.fillMaxWidth().heightIn(min = CouliSpacing.space10))
    }
}

/** 轻提示（页顶，2 秒）：同一句再提示时 [id] 不同，重新播报（复制成功 polite 播报一次）。 */
@Composable
internal fun ToastHost(
    message: String,
    id: Long,
    error: Boolean,
    tag: String,
    onDone: () -> Unit,
) {
    LaunchedEffect(id) {
        delay(TOAST_MILLIS)
        onDone()
    }
    Box(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(top = CouliSpacing.space12),
        contentAlignment = Alignment.TopCenter,
    ) {
        CouliToast(
            message = message,
            icon = if (error) CouliIcons.alert else CouliIcons.check,
            announcementId = id,
            modifier = Modifier.testTag(tag),
        )
    }
}

@Composable
private fun PlatformSheet(
    texts: OrderTexts,
    selected: String?,
    restore: FocusRequester,
    onChoose: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    CouliSheet(
        title = texts.text("order_list.filter.platform"),
        onDismissRequest = onDismiss,
        restoreFocusTo = restore,
        closeLabel = texts.text("order_list.filter.close"),
    ) {
        RadioOption(
            texts.text("order_list.filter.all_platforms"),
            selected == null,
            OrderTags.FILTER_OPTION_PREFIX + ALL_OPTION,
        ) { onChoose(null) }
        OrderPlatformOptions.platforms.forEach { platform ->
            RadioOption(
                texts.text("platform.$platform"),
                selected == platform,
                OrderTags.FILTER_OPTION_PREFIX + platform,
            ) { onChoose(platform) }
        }
    }
}

@Composable
private fun MonthSheet(
    texts: OrderTexts,
    selected: String?,
    earliestVisibleDate: String?,
    now: Long,
    restore: FocusRequester,
    onChoose: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    CouliSheet(
        title = texts.text("order_list.filter.month"),
        onDismissRequest = onDismiss,
        restoreFocusTo = restore,
        closeLabel = texts.text("order_list.filter.close"),
    ) {
        Text(text = texts.text("order_list.filter.month_note"), style = CouliTypography.footnote, color = CouliColors.textSecondary)
        RadioOption(
            texts.text("order_list.filter.all_months"),
            selected == null,
            OrderTags.FILTER_OPTION_PREFIX + ALL_OPTION,
        ) { onChoose(null) }
        OrderMonthOptions.months(earliestVisibleDate, now).forEach { month ->
            RadioOption(monthText(texts, month), selected == month, OrderTags.FILTER_OPTION_PREFIX + month) { onChoose(month) }
        }
    }
}

/** 半屏里的单选项（Role.RadioButton + selected，命中区 48）。 */
@Composable
private fun RadioOption(
    label: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = CouliSizes.touchTarget)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = if (selected) CouliTextStyles.bodySemibold else CouliTypography.body,
            color = if (selected) CouliColors.brandEmphasis else CouliColors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                CouliIcons.check,
                contentDescription = null,
                tint = CouliColors.brandEmphasis,
                modifier = Modifier.size(CouliSizes.iconMedium),
            )
        }
    }
}

private val EMPTY = kotlinx.serialization.json.JsonObject(emptyMap())
private const val LIST_VM_KEY = "order_list"
private const val PENDING_KEY = "pending"
private const val ERROR_KEY = "error"
private const val EMPTY_KEY = "empty"
private const val FOOTER_KEY = "footer"
private const val HISTORY_KEY = "history"
private const val ALL_OPTION = "all"
private const val SKELETON_COUNT = 3
private const val LOAD_AHEAD = 2
private const val THUMB = 64
private const val SKELETON_BADGE = 56
private const val SKELETON_LINE = 0.6f
private const val YEAR_LENGTH = 4
private const val TOAST_MILLIS = 2_000L
private const val ARROW_SUFFIX = "_arrow"

private fun LazyListState.position(): Pair<Int, Int> = firstVisibleItemIndex to firstVisibleItemScrollOffset

/**
 * 本次组合交给列表页控件的全部用户回调（R2-01）：每个都捕获组合时取出的渲染身份 [ticket]（普通值），执行时交给 ViewModel 比较；身份失效（换号、
 * 撤回 / 切环境清理、作废旧列表）后不写内存、保存状态与恢复记录，不发请求。挂在页面根节点的语义属性 [Key] 上（同一批对象），测试与无障碍工具可取出。
 */
class OrderListActions internal constructor(
    model: OrderListViewModel,
    val ticket: OrderPageTicket,
) {
    val onScope: (String) -> Unit = { model.selectScope(ticket, it) }
    val onInput: (String) -> Unit = { model.onInput(ticket, it) }
    val onSubmit: () -> Unit = { model.submit(ticket) }
    val onClear: () -> Unit = { model.clearInput(ticket) }
    val onGroup: (String) -> Unit = { model.selectGroup(ticket, it) }
    val onSheet: (OrderSheet) -> Unit = { model.openSheet(ticket, it) }
    val onCloseSheet: () -> Unit = { model.closeSheet(ticket) }
    val onPlatform: (String?) -> Unit = { model.choosePlatform(ticket, it) }
    val onMonth: (String?) -> Unit = { model.chooseMonth(ticket, it) }
    val onRefresh: () -> Unit = { model.pullRefresh(ticket) }
    val onRetry: () -> Boolean = { model.retry(ticket) }
    val onLoadMore: () -> Boolean = { model.loadMore(ticket) }
    val onOpen: (String) -> Unit = { model.openDetail(ticket, it) }
    val onCopy: (
        String,
        String,
        Long,
        OrderListIdentity?,
    ) -> Unit = { id, no, round, identity -> model.copy(ticket, id, no, round, identity) }
    val onScrolled: (OrderAnchor) -> Unit = { model.onScrolled(ticket, it) }

    companion object {
        /** 页面根节点上的语义属性：本次组合的回调。 */
        val Key = SemanticsPropertyKey<OrderListActions>("OrderListActions")
    }
}
