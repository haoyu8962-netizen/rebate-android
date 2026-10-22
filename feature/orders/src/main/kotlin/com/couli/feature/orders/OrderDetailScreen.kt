package com.couli.feature.orders

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonSize
import com.couli.core.designsystem.CouliButtonVariant
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliDivider
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.EmptyState
import com.couli.core.designsystem.ErrorState
import com.couli.core.designsystem.NavBar
import com.couli.core.designsystem.Skeleton
import com.couli.core.designsystem.StateAction
import com.couli.core.designsystem.couliCard
import com.couli.core.orders.OrderActionButton
import com.couli.core.orders.OrderDetail
import com.couli.core.orders.OrderDetailActions
import com.couli.core.orders.OrderErrorAction
import com.couli.core.orders.OrderTexts
import com.couli.core.orders.PriceCompareEntry
import com.couli.core.orders.WhyNoRebate
import com.couli.generated.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 订单详情（OrderDetail 路由；设计稿 OrderDetailEstimated / Presale / Settled / PartClawed / ClawedBack / NoRebate / PriceCompareNoRebate /
 * PriceCompareRisk / Reviewing / RightsPending / Blacklist / AppealPending / Share / Unknown / Dark、ReasonStates；AdaptLargeTextOrderDetail；
 * APP-08v O20–O33）：状态卡（状态图标 + 标题 + 眼睛、左返利右实付、提示；分隔线下原因区「为什么没返利？」、差额 / 比价行、「申诉中」块、卡内按钮、
 * 未知状态的【刷新】与「查看流水」）、商品卡（平台 +「查看商品 ›」、商品图 + 标题 + 件数 + 订单号【复制】）、时间线、【问问 AI】整行卡；底部固定栏
 * 只放【去提现】（整宽白底、内容限宽）。30701 显示空态不重试；「问问 AI」只在 Agent 可用的构建与环境里显示（APP-10 G27，带 {order_id}，不自动发送）。
 * 大屏内容限宽 600 居中。
 */
@Composable
fun OrderDetailScreen(config: OrderDetailConfig) {
    val model: OrderDetailViewModel = viewModel { OrderDetailViewModel(config, createSavedStateHandle()) }
    model.update(config)
    val state by model.controller.state.collectAsStateWithLifecycle()
    val noMask = remember { MutableStateFlow(false) }
    val masked by (config.mask?.hidden ?: noMask).collectAsStateWithLifecycle()
    val texts = config.texts
    val detail = state.detail.takeIf { state.phase !in BLANK_PHASES }
    // 渲染时的版本（普通值，R2-01、R3-02）：底栏回调只拿它比较，不在执行时读最新状态
    val version = state.version
    val (cardButtons, barButtons) =
        detail?.let { OrderUi.splitActions(OrderDetailActions.of(it)) }
            ?: (emptyList<OrderActionButton>() to emptyList())
    val hasBar = barButtons.isNotEmpty()
    Box(modifier = Modifier.fillMaxSize().background(CouliColors.backgroundCanvas).testTag(OrderTags.DETAIL_PAGE)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(modifier = Modifier.widthIn(max = OrderUi.MAX_WIDTH.dp).fillMaxSize().statusBarsPadding()) {
                    NavBar(
                        title = texts.text("order_detail.title"),
                        backLabel = texts.text("order_detail.back"),
                        onBack = config.onBack,
                        modifier = Modifier.testTag(OrderTags.DETAIL_BACK),
                    )
                    when {
                        state.phase == OrderDetailPhase.LOADING -> DetailSkeleton(texts)
                        state.phase == OrderDetailPhase.NOT_FOUND -> DetailNotFound(texts)
                        state.phase == OrderDetailPhase.ERROR -> DetailError(model, texts, state)
                        detail != null -> DetailContent(model, config, state, detail, masked, cardButtons, hasBar, Modifier.weight(1f))
                    }
                }
            }
            if (hasBar) {
                DetailBottomBar(texts, barButtons) { model.controller.action(version, it) }
            }
        }
        state.toast?.let { toast ->
            ToastHost(texts.text(toast.key), toast.id, toast.error, OrderTags.DETAIL_TOAST) { model.controller.consumeToast(toast.id) }
        }
    }
}

private val BLANK_PHASES = setOf(OrderDetailPhase.LOADING, OrderDetailPhase.NOT_FOUND, OrderDetailPhase.ERROR)

@Composable
private fun DetailNotFound(texts: OrderTexts) {
    Box(modifier = Modifier.fillMaxWidth().testTag(OrderTags.DETAIL_EMPTY)) {
        EmptyState(title = texts.text("error.${OrderErrorAction.NOT_FOUND}"), icon = CouliIcons.orders)
    }
}

@Composable
private fun DetailError(
    model: OrderDetailViewModel,
    texts: OrderTexts,
    state: OrderDetailSnapshot,
) {
    val view = state.error
    // 渲染时的版本与会话身份（普通值，R2-01、R3-02；APP-09 共性第 63 条）
    val version = state.version
    val owner = state.owner
    Box(modifier = Modifier.fillMaxWidth().testTag(OrderTags.DETAIL_ERROR)) {
        ErrorState(
            title = texts.text("order_list.load_failed"),
            message = view?.let { OrderErrorAction.message(texts, it) },
            // 42901 禁用期内【重试】置灰（与执行层同一份禁用期）
            retry =
                StateAction(
                    texts.text("order_list.retry"),
                    enabled = !state.blocked,
                ) { model.controller.retry(version, owner) },
        )
    }
}

/** 没给 [OrderDetailConfig.askAiVisible] 时的常量「可见」。 */
private val ASK_AI_ALWAYS: StateFlow<Boolean> = MutableStateFlow(true)

@Suppress("LongParameterList") // 页面状态、渲染时快照与卡内按钮逐项给
@Composable
private fun DetailContent(
    model: OrderDetailViewModel,
    config: OrderDetailConfig,
    state: OrderDetailSnapshot,
    detail: OrderDetail,
    masked: Boolean,
    cardButtons: List<OrderActionButton>,
    bottomBar: Boolean,
    modifier: Modifier,
) {
    val texts = config.texts
    // 有底栏时由底栏处理导航栏（不变）；无底栏时滚动内容底部 max(导航栏, 24)，滚到底末卡不落入系统导航区（r1 S2-3）
    val navigation = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottom = if (bottomBar) CouliSpacing.space6 else max(navigation, CouliSpacing.space6)
    // 渲染时的版本与会话身份（普通值）：回调只拿它们比较（R3-02）
    val version = state.version
    val owner = state.owner
    val controller = model.controller
    val handlers =
        DetailHandlers(
            onHelp = { controller.openHelp(version) },
            onAppeal = { controller.openAppeal(version) },
            onAction = { controller.action(version, it) },
            onRefresh = { controller.retry(version, owner) },
            onToggleMask = { config.mask?.toggle() },
        )
    val status =
        StatusCardModel(
            detail = detail,
            why = WhyNoRebate.of(detail),
            hasHelp = PriceCompareEntry.of(detail, config.env.helpLinks(), Routes.all) != null,
            appealPending = OrderDetailActions.of(detail).appealPendingBlock,
            cardButtons = cardButtons,
            masked = masked,
            blocked = state.blocked,
            now = config.clock(),
        )
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = CouliSpacing.space4, end = CouliSpacing.space4, top = CouliSpacing.space2, bottom = bottom),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        state.staleAt?.let {
            StaleBanner(texts, it, config.clock(), OrderTags.DETAIL_STALE, outer = PaddingValues(0.dp))
            // 断网缓存态的刷新入口（共性第 26 条）：遵守会话级 42901 禁用期（置灰、执行层也拦）；成功后更新内容与缓存、提示条消失
            CouliButton(
                text = texts.text("order_list.retry"),
                onClick = { controller.retry(version, owner) },
                variant = CouliButtonVariant.SECONDARY,
                size = CouliButtonSize.COMPACT,
                enabled = !state.blocked,
                modifier = Modifier.testTag(OrderTags.DETAIL_STALE_RETRY),
            )
        }
        StatusCard(texts, status, handlers)
        ProductCard(texts, detail, onViewProduct = { controller.viewProduct(version) }, onCopy = { controller.copy(version) })
        TimelineCard(texts, detail, config.clock())
        // 【问问 AI】（APP-10 G27；APP-08v O31：时间线之后的整行卡）；入口关闭（agent.entry.visible=false、Agent 不可用）时不绘制（R1-04、G56）
        val askShown by (config.askAiVisible ?: ASK_AI_ALWAYS).collectAsStateWithLifecycle()
        config.askAi?.takeIf { askShown }?.let { ask -> AskAiRow(texts, ask) }
    }
}

/**
 * 底部固定栏（G-O06：只放状态固有的【去提现】）：白底整宽（≥600 仍铺满）+ 顶部 1 分隔线，内部按钮列限宽 600 居中；内边距 上 12 / 左右 16 /
 * 下 max(导航栏, 16)；通栏主按钮 48、17/600。按钮 tag 与回调（渲染时版本）不变。
 */
@Composable
private fun DetailBottomBar(
    texts: OrderTexts,
    buttons: List<OrderActionButton>,
    onAction: (OrderActionButton) -> Unit,
) {
    val navigation = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(modifier = Modifier.fillMaxWidth().background(CouliColors.backgroundSurface).testTag(OrderTags.DETAIL_BOTTOM_BAR)) {
        CouliDivider()
        Box(
            modifier = Modifier.fillMaxWidth().padding(top = CouliSpacing.space3, bottom = max(navigation, CouliSpacing.space4)),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier.widthIn(max = OrderUi.MAX_WIDTH.dp).fillMaxWidth().padding(horizontal = CouliSpacing.space4),
                verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
            ) {
                buttons.forEach { button ->
                    CouliButton(
                        text = texts.text(button.labelKey),
                        onClick = { onAction(button) },
                        variant = CouliButtonVariant.PRIMARY,
                        modifier = Modifier.fillMaxWidth().testTag(OrderTags.DETAIL_ACTION_PREFIX + button.actionRaw),
                    )
                }
            }
        }
    }
}

/**
 * 加载骨架（O32，按真实结构摆三块）：状态卡（图标 + 标题行、两列数字块）、商品卡（徽标、64 图 + 右列三行）、时间线 4 行（点 + 名称 + 时间）；
 * 读屏「加载中」，不画可见文字与假数据。
 */
@Composable
private fun DetailSkeleton(texts: OrderTexts) {
    val loading = texts.text("order_list.loading")
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = CouliSpacing.space4, end = CouliSpacing.space4, top = CouliSpacing.space2)
                .semantics { contentDescription = loading },
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        SkeletonCard {
            Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2), verticalAlignment = Alignment.CenterVertically) {
                Skeleton(Modifier.size(CouliSizes.iconMedium))
                Skeleton(Modifier.size(width = SKELETON_TITLE.dp, height = CouliSizes.iconMedium))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3)) {
                repeat(2) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
                        Skeleton(Modifier.size(width = SKELETON_LABEL.dp, height = SKELETON_LINE.dp))
                        Skeleton(Modifier.size(width = SKELETON_AMOUNT.dp, height = SKELETON_VALUE.dp))
                    }
                }
            }
        }
        SkeletonCard {
            Skeleton(Modifier.size(width = SKELETON_BADGE.dp, height = CouliSizes.tagHeight))
            Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3)) {
                Skeleton(Modifier.size(THUMB.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
                    Skeleton(Modifier.size(width = SKELETON_TITLE.dp, height = CouliSpacing.space4))
                    Skeleton(Modifier.size(width = SKELETON_LABEL.dp, height = SKELETON_LINE.dp))
                    Skeleton(Modifier.size(width = SKELETON_TITLE.dp, height = SKELETON_LINE.dp))
                }
            }
        }
        SkeletonCard {
            repeat(TIMELINE_ROWS) {
                Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3)) {
                    Box(modifier = Modifier.size(CouliSpacing.space4), contentAlignment = Alignment.Center) {
                        Skeleton(Modifier.size(CouliSpacing.space2).clip(CircleShape))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
                        Skeleton(Modifier.size(width = SKELETON_LABEL.dp, height = SKELETON_LINE.dp))
                        Skeleton(Modifier.size(width = SKELETON_TIME.dp, height = SKELETON_LINE.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SkeletonCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().couliCard().padding(CouliSpacing.space4),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) { content() }
}

private const val SKELETON_TITLE = 140
private const val SKELETON_LABEL = 48
private const val SKELETON_AMOUNT = 96
private const val SKELETON_VALUE = 28
private const val SKELETON_LINE = 14
private const val SKELETON_BADGE = 56
private const val SKELETON_TIME = 120
private const val THUMB = 64
private const val TIMELINE_ROWS = 4
