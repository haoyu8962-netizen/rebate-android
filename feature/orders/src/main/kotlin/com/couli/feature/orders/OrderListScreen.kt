package com.couli.feature.orders

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
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
import com.couli.core.designsystem.CouliToast
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.EmptyState
import com.couli.core.designsystem.ErrorAnnouncement
import com.couli.core.designsystem.ErrorState
import com.couli.core.designsystem.RestoreLanding
import com.couli.core.designsystem.StateAction
import com.couli.core.designsystem.awaitLanding
import com.couli.core.designsystem.couliFocusTarget
import com.couli.core.orders.OrderErrorAction
import com.couli.core.orders.OrderQuery
import com.couli.core.orders.OrderTexts
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
            // 置顶（G-O04）时右上入口不画，改在标题下整行；两处去向相同（FindOrder，不带参数），不发额外请求
            val onFind = { config.env.open(OrderRoutes.FIND_ORDER, EMPTY) }
            TitleRow(texts, showEntry = !state.claimPinned, onFind = onFind)
            // 头部各行按画板视觉高排（Tab 44、搜索 44、胶囊 32，行距 12）；胶囊行上下各留 16：上下两行的 48 命中区各扩 8 不重叠；筛选行下留 8
            // 给它与眼睛的命中扩展（在头部之内，不伸进列表），列表上内边距 8 补足卡片前的 16
            Column(
                modifier =
                    Modifier
                        .padding(horizontal = CouliSpacing.space4)
                        .padding(top = CouliSpacing.space2, bottom = CouliSpacing.space2),
                verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
            ) {
                if (state.claimPinned) PinnedFindRow(texts, onFind)
                state.staleAt?.let { StaleBanner(texts, it, config.clock(), outer = PaddingValues()) }
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
                Box(modifier = Modifier.padding(vertical = CouliSpacing.space1)) {
                    GroupChips(texts, state.conditions.group, enabled = !state.blocked, onSelect = actions.onGroup)
                }
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

/** 搜索框（G11）：视觉高 44、1 控件边、圆角 12；最多 40 个 Unicode 字符；键盘「搜索」或清空才发请求，不边输边搜。 */
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
                .heightIn(min = CouliSizes.visualTarget)
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
                // 48 的清除按钮不把 44 的搜索框撑高（命中区上下溢出 2）
                modifier = Modifier.visualHeight(CouliSizes.iconMedium).testTag(OrderTags.SEARCH_CLEAR),
            )
        }
    }
}

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
    val restoreGuard = remember(model) { RestoreGuard() }
    ScrollKeeping(
        model,
        actions,
        state,
        listState,
        leading =
            if (state.scope == OrderQuery.SELF &&
                model.pending != null
            ) {
                1
            } else {
                0
            },
        restoreGuard,
    )
    val pullState = rememberPullToRefreshState()
    // 下拉刷新：42901 禁用期内手势不可用（enabled = false；执行层也拦）
    BoxWithConstraints(
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
            modifier =
                Modifier
                    .fillMaxSize()
                    .testTag(OrderTags.LIST)
                    // 第 1 轮修复 R1-06：恢复期间手指按下即算用户接管（未移动也取消），不消费事件
                    .pointerInput(restoreGuard) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            restoreGuard.touched()
                        }
                    },
            // 上内边距 8：头部已留出筛选行命中扩展的 8（APP-08v O8），卡片离筛选行视觉仍是 16
            contentPadding =
                PaddingValues(
                    start = CouliSpacing.space4,
                    top = CouliSpacing.space2,
                    end = CouliSpacing.space4,
                    bottom = CouliSpacing.space4,
                ),
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
                    val fill = emptyFill(state, pending != null, if (constraints.hasBoundedHeight) maxHeight else null)
                    item(key = EMPTY_KEY) { ListEmpty(texts, state, config, fill) }
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
    guard: RestoreGuard,
) {
    // 放弃恢复（换条件、列表变空或失败、被挪动、翻到底仍找不到）：恢复期间冻结的锚点立即改记当前位置，不留旧目标（共性第 17、24 条）
    val giveUp = {
        model.restoreDone()
        actions.onScrolled(anchorOf(listState, model))
    }
    // 第 1 轮修复 R1-06：恢复全过程（含等翻页的网络等待期）持续记录用户接管——触摸按下、不是本任务的滚动（拖动、惯性、读屏、键盘 / 焦点）、
    // 项数没变而位置变了；一旦接管就放弃并按当前位置记录，之后即使回到原位置也不再恢复（pendingRestore 已清）
    val takeOver = {
        if (model.pendingRestore != null) {
            model.restoreEnd = OrderListViewModel.RESTORE_TAKEN_OVER
            giveUp()
        }
    }
    // FU-AND-0717 第 3 轮（R2-05）：恢复决策还在延后（身份 / 分区没确认）时，触摸与用户滚动同样记为接管，身份确认后不再用旧记录覆盖。
    // 末轮窄修 R3-04：常驻的滚动监听不随 actions 重启，经最新渲染身份的引用调用（分阶段确认身份换了票据之后，旧票据不会让接管被拒）
    val currentActions by rememberUpdatedState(actions)
    guard.onTouch = {
        model.onUserTouch(currentActions.ticket)
        takeOver()
    }
    LaunchedEffect(listState, guard) {
        var last: Pair<Pair<Int, Int>, Int>? = null
        snapshotFlow { Triple(listState.isScrollInProgress, listState.position(), listState.layoutInfo.totalItemsCount) }
            .collect { (scrolling, position, count) ->
                // 延后期间：不是本页自己挪的滚动、或项数没变而位置变了（读屏 / 键盘 / 焦点移动，没有触摸按下）都记为接管
                val moved = last?.let { (p, c) -> c == count && p != position } == true
                last = position to count
                if ((scrolling || moved) && !guard.moving) model.onUserTouch(currentActions.ticket)
                if (model.pendingRestore == null || guard.moving) return@collect
                val expected = guard.expected ?: return@collect
                when {
                    scrolling -> takeOver()

                    count == guard.expectedCount && position != expected -> takeOver()

                    // 项数变了（翻页到达、布局追上）而没有滚动在进行：按此刻位置重设基准
                    count != guard.expectedCount -> guard.rebase(position, count)
                }
            }
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
        val valid = { model.pendingRestore == target && model.controller.state.value.round == round && model.live(currentActions.ticket) }
        when (state.phase) {
            OrderListPhase.CONTENT -> Unit
            OrderListPhase.EMPTY, OrderListPhase.ERROR -> return@LaunchedEffect giveUp()
            else -> return@LaunchedEffect
        }
        val base =
            restoreBase.value ?: run {
                // 基准在列表首次可交互时建立：先回到顶部（列表自己保存的旧下标会被第一页的条数截断，不能当基准）
                guard.own(listState) { listState.scrollToItem(0) }
                listState.position().also { restoreBase.value = it }
            }
        if (!valid()) return@LaunchedEffect
        if (listState.position() != base) return@LaunchedEffect giveUp()
        val row = state.items.indexOfFirst { it.orderId == target }
        when {
            row >= 0 -> {
                // 自己的每次定位之后按实际位置重设基准（之后状态变化再进来时，基准比对只抓别人的移动）
                alignRestoreTarget(model, actions, state, listState, leading + row, valid, giveUp, guard) { restoreBase.value = it }
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
 * 目标卡已加载：顶对齐并按真实布局确认落点（F-X-18 / 补充，FU-AND-0717：不在 scrollToItem 返回后就算完成；[awaitLanding] 逐帧看布局，布局滞后时等它追上
 * 再补发，帧上限内没确认按没确认结束）。确认期间持续判接管：不是本任务造成的滚动（拖动、惯性、读屏滚动、键盘 / 焦点移动）或项数没变而位置变了（同卡片内
 * 小幅滚动）→ 立即放弃并按当前位置记录，之后不再定位。
 * 目标在已加载内容末尾、被列表底部截住（F-X-20：顶不上去）：还能翻页就先翻页、等新的一页到了再对齐（恢复任务保留）；正在翻页时等它结束（不重复发请求）；
 * 翻页失败、到底或翻不动时停在已能到的最近位置结束，不挂死。完成时按实际布局记锚点（不记假设的落点）。
 */
@Suppress("LongParameterList", "ReturnCount", "CyclomaticComplexMethod") // 接管 / 确认 / 截住翻页 / 结束逐步判定
private suspend fun alignRestoreTarget(
    model: OrderListViewModel,
    actions: OrderListActions,
    state: OrderListSnapshot,
    listState: LazyListState,
    index: Int,
    valid: () -> Boolean,
    giveUp: () -> Unit,
    guard: RestoreGuard,
    onMoved: (Pair<Int, Int>) -> Unit,
) {
    val landing = RestoreLanding(index, 0)
    // 接管由持续的监视（[RestoreGuard]，触摸 / 滚动 / 位置变化）判定并清掉恢复任务；这里每帧复核任务仍有效
    val step =
        awaitLanding(landing, { RestoreLanding.layoutOf(listState) }, valid) {
            guard.own(listState) { listState.scrollToItem(index) }
            onMoved(listState.position())
        }
    // 接管 / 失效（step 为 null）：结束原因已由接管处记下，这里什么都不做
    step ?: return
    model.restoreEnd =
        if (step == RestoreLanding.Step.CONFIRMED) OrderListViewModel.RESTORE_LANDED else OrderListViewModel.RESTORE_NOT_CONFIRMED
    if (step != RestoreLanding.Step.CONFIRMED) return giveUp()
    val clamped = listState.firstVisibleItemIndex != index && !listState.canScrollForward
    if (clamped && state.loadMoreError == null && !state.endReached) {
        // 目标之后不够一屏：先翻页再对齐（翻页中就等它结束，状态变化会再进来）
        model.restoreEnd = OrderListViewModel.RESTORE_PAGING
        if (state.loadingMore || actions.onLoadMore()) return
    }
    model.restoreDone()
    // 对齐完成才更新持久化锚点（恢复期间的滚动记录被冻结）：按真实布局记落点
    actions.onScrolled(anchorOf(listState, model))
}

/**
 * 一次性恢复的接管判定状态（第 1 轮修复 R1-06）：本任务自己挪动列表期间（[own]）不判接管，挪完按实际位置记基准；触摸按下（[touched]）交给 [onTouch]。
 */
internal class RestoreGuard {
    var moving: Boolean = false
        private set
    var expected: Pair<Int, Int>? = null
        private set
    var expectedCount: Int = -1
        private set
    var onTouch: () -> Unit = {}

    fun touched() = onTouch()

    fun rebase(
        position: Pair<Int, Int>,
        count: Int,
    ) {
        expected = position
        expectedCount = count
    }

    suspend fun own(
        listState: LazyListState,
        move: suspend () -> Unit,
    ) {
        moving = true
        try {
            move()
        } finally {
            moving = false
            rebase(listState.position(), listState.layoutInfo.totalItemsCount)
        }
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

/**
 * OrdersEmpty：空态在列表可视区内上下居中的最小高（列表里只有它时；有待跟单卡或查询范围提示时为 0，照常排在顶部）。[viewport] 为列表高，
 * 减去列表上下内边距。
 */
private fun emptyFill(
    state: OrderListSnapshot,
    hasPending: Boolean,
    viewport: Dp?,
): Dp {
    val alone = (state.scope != OrderQuery.SELF || !hasPending) && state.earliestVisibleDate == null
    return if (alone && viewport != null) viewport - CouliSpacing.space2 - CouliSpacing.space4 else 0.dp
}

@Composable
private fun ListEmpty(
    texts: OrderTexts,
    state: OrderListSnapshot,
    config: OrderListConfig,
    minHeight: Dp,
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
    Box(
        modifier = Modifier.fillMaxWidth().heightIn(min = minHeight).testTag(OrderTags.EMPTY),
        contentAlignment = Alignment.Center,
    ) {
        EmptyState(
            title = texts.text("order_list.empty.$scope.title"),
            message = texts.text("order_list.empty.$scope.message"),
            icon = CouliIcons.orders,
            // OrdersEmpty：实心主按钮、紧凑宽（不通栏）
            action = StateAction(texts.text("order_list.empty.action"), primary = true) { config.env.open(OrderRoutes.HOME, EMPTY) },
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

private val EMPTY = kotlinx.serialization.json.JsonObject(emptyMap())
private const val LIST_VM_KEY = "order_list"
private const val PENDING_KEY = "pending"
private const val ERROR_KEY = "error"
private const val EMPTY_KEY = "empty"
private const val FOOTER_KEY = "footer"
private const val HISTORY_KEY = "history"
private const val SKELETON_COUNT = 3
private const val LOAD_AHEAD = 2
private const val TOAST_MILLIS = 2_000L

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
