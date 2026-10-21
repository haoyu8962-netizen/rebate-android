package com.couli.feature.orders

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.couli.core.designsystem.AmountGrid
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonSize
import com.couli.core.designsystem.CouliButtonVariant
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliIconButton
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.EmptyState
import com.couli.core.designsystem.EndWrapRow
import com.couli.core.designsystem.ErrorState
import com.couli.core.designsystem.NavBar
import com.couli.core.designsystem.PlatformBadge
import com.couli.core.designsystem.ProductThumbnail
import com.couli.core.designsystem.Skeleton
import com.couli.core.designsystem.StateAction
import com.couli.core.designsystem.couliCard
import com.couli.core.network.IdentityVerdict
import com.couli.core.orders.CreditPeriodText
import com.couli.core.orders.OrderAmountCells
import com.couli.core.orders.OrderDetail
import com.couli.core.orders.OrderDetailActions
import com.couli.core.orders.OrderErrorAction
import com.couli.core.orders.OrderNumberDisplay
import com.couli.core.orders.OrderStatusText
import com.couli.core.orders.OrderTexts
import com.couli.core.orders.OrderTimelineView
import com.couli.core.orders.OrderTitleImage
import com.couli.core.orders.PriceCompareEntry
import com.couli.core.orders.TimelineRow
import com.couli.core.orders.ViewProductEntry
import com.couli.core.orders.WhyNoRebate
import com.couli.core.orders.WhyNoRebateView
import com.couli.generated.DisplayStatus
import com.couli.generated.OrderTimelineNode
import com.couli.generated.Routes
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 订单详情的 ViewModel：状态来自 [OrderDetailController]。页面一确定出栈（App 在回退栈移除的同步出口调 bindLeave 给的入口）即作废请求；
 * ViewModel 清理时兜底再调一次。进程被系统回收后恢复本页时先等本轮版本检查结果（restoreGate）再取详情。会话身份在构造时固定（执行器构造即取），
 * 恢复等待前捕获票据、等待结束后只比较（RC-01）。
 */
class OrderDetailViewModel(
    initial: OrderDetailConfig,
    saved: SavedStateHandle,
) : ViewModel() {
    var config: OrderDetailConfig = initial
        private set
    val controller =
        OrderDetailController(initial.api, initial.env, initial.cache, viewModelScope, initial.orderId, initial.clock, initial.rateGate)

    /** 进程恢复时等待版本检查的任务（等待结束前为非 null）。 */
    private var restoreJob: Job? = null

    init {
        initial.bindLeave(controller::leave)
        initial.bindSession {
            // 会话换代：等待中的恢复任务作废（换代入口已按新会话重取，等待结束后不再按旧会话补一次；共性第 21 条）
            restoreJob?.cancel()
            restoreJob = null
            controller.onSessionChanged()
        }
        // App 下发的已确认快照（FU-AND-0717 第 3 轮统一的页面恢复入口，R2-03）：会话换代按它清空重取；判据没变时第一次确认接管、加载曾因身份中止的补取
        initial.bindIdentity { identity, changed ->
            val verdict = if (changed) IdentityVerdict.CHANGED else controller.verdict(identity)
            if (verdict == IdentityVerdict.CHANGED) {
                restoreJob?.cancel()
                restoreJob = null
                controller.onSessionChanged(identity)
            } else {
                controller.onIdentityConfirmed(identity)
            }
        }
        val restored = saved.get<Boolean>(K_STARTED) == true
        saved[K_STARTED] = true
        val gate = initial.restoreGate
        if (restored && gate != null) {
            // 等待前捕获固定身份、绑定代次与版本；等待结束后先比较，失效直接返回，请求沿用原身份（RC-01，APP-08 共性第 21 条）
            val ticket = controller.restoreTicket()
            restoreJob =
                viewModelScope.launch {
                    gate()
                    restoreJob = null
                    controller.resumeRestore(ticket)
                }
        } else {
            controller.load()
        }
    }

    fun update(next: OrderDetailConfig) {
        config = next
    }

    override fun onCleared() {
        controller.leave()
        super.onCleared()
    }

    private companion object {
        const val K_STARTED = "order_detail.started"
    }
}

/**
 * 订单详情（OrderDetail 路由；设计稿 OrderDetailEstimated / Presale / Settled / PartClawed / ClawedBack / NoRebate / PriceCompareNoRebate /
 * PriceCompareRisk / Reviewing / RightsPending / Blacklist / AppealPending / Share；AdaptLargeTextOrderDetail）：状态与金额卡（眼睛按钮）、原因区
 * 「为什么没返利？」（直接显示，不进 AI）、「申诉中」块、平台 +「查看商品」、商品卡（订单号 + 复制）、时间线、底部按钮（最多 2 个）。30701 显示
 * 空态不重试；「问问 AI」只在 Agent 可用的构建与环境里显示（APP-10 G27，带 {order_id}，不自动发送）。大屏内容限宽 600 居中。
 */
@Composable
fun OrderDetailScreen(config: OrderDetailConfig) {
    val model: OrderDetailViewModel = viewModel { OrderDetailViewModel(config, createSavedStateHandle()) }
    model.update(config)
    val state by model.controller.state.collectAsStateWithLifecycle()
    val noMask = remember { MutableStateFlow(false) }
    val masked by (config.mask?.hidden ?: noMask).collectAsStateWithLifecycle()
    val texts = config.texts
    Box(
        modifier = Modifier.fillMaxSize().background(CouliColors.backgroundCanvas).testTag(OrderTags.DETAIL_PAGE),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(modifier = Modifier.widthIn(max = OrderUi.MAX_WIDTH.dp).fillMaxSize().statusBarsPadding()) {
            NavBar(
                title = texts.text("order_detail.title"),
                backLabel = texts.text("order_detail.back"),
                onBack = config.onBack,
                modifier = Modifier.testTag(OrderTags.DETAIL_BACK),
            )
            val detail = state.detail
            when {
                state.phase == OrderDetailPhase.LOADING -> {
                    DetailSkeleton(texts)
                }

                state.phase == OrderDetailPhase.NOT_FOUND -> {
                    Box(modifier = Modifier.fillMaxWidth().testTag(OrderTags.DETAIL_EMPTY)) {
                        EmptyState(title = texts.text("error.${OrderErrorAction.NOT_FOUND}"), icon = CouliIcons.orders)
                    }
                }

                state.phase == OrderDetailPhase.ERROR -> {
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

                detail != null -> {
                    DetailContent(model, config, state, detail, masked, Modifier.weight(1f))
                }
            }
        }
        state.toast?.let { toast ->
            ToastHost(texts.text(toast.key), toast.id, toast.error, OrderTags.DETAIL_TOAST) { model.controller.consumeToast(toast.id) }
        }
    }
}

/** 没给 [OrderDetailConfig.askAiVisible] 时的常量「可见」。 */
private val ASK_AI_ALWAYS: StateFlow<Boolean> = MutableStateFlow(true)

@Composable
private fun DetailContent(
    model: OrderDetailViewModel,
    config: OrderDetailConfig,
    state: OrderDetailSnapshot,
    detail: OrderDetail,
    masked: Boolean,
    modifier: Modifier,
) {
    val texts = config.texts
    // 渲染时的版本与会话身份（普通值）：回调只拿它们比较（R3-02）
    val version = state.version
    val owner = state.owner
    val controller = model.controller
    val why = WhyNoRebate.of(detail)
    val help = PriceCompareEntry.of(detail, config.env.helpLinks(), Routes.all)
    val actions = OrderDetailActions.of(detail)
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(CouliSpacing.space4),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        state.staleAt?.let {
            StaleBanner(texts, it, config.clock(), OrderTags.DETAIL_STALE)
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
        StatusCard(texts, detail, why, help != null, masked, config, onHelp = { controller.openHelp(version) })
        if (why.show && why.descKey != null) {
            ReasonCard(texts, why)
        }
        if (actions.appealPendingBlock) AppealBlock(texts) { controller.openAppeal(version) }
        ProductCard(texts, detail, onViewProduct = { controller.viewProduct(version) }, onCopy = { controller.copy(version) })
        // 【问问 AI】（APP-10 G27，鸿蒙 H06：设计稿 OrderDetail* 没有位置，代拟放在商品卡下方一行文字按钮；待设计补位置）
        val askShown by (config.askAiVisible ?: ASK_AI_ALWAYS).collectAsStateWithLifecycle()
        // 入口关闭（agent.entry.visible=false、Agent 不可用）时不绘制（R1-04、G56）
        config.askAi?.takeIf { askShown }?.let { ask ->
            CouliButton(
                text = texts.text("order_detail.ask_ai"),
                onClick = ask,
                variant = CouliButtonVariant.TEXT,
                size = CouliButtonSize.COMPACT,
                modifier = Modifier.testTag(OrderTags.ASK_AI),
            )
        }
        TimelineCard(texts, detail, config.clock())
    }
    if (actions.buttons.isNotEmpty()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(CouliColors.backgroundSurface)
                    .navigationBarsPadding()
                    .padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space3),
            horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
        ) {
            actions.buttons.forEach { button ->
                CouliButton(
                    text = texts.text(button.labelKey),
                    onClick = { controller.action(version, button) },
                    variant = if (button.primary) CouliButtonVariant.PRIMARY else CouliButtonVariant.SECONDARY,
                    modifier = Modifier.weight(1f).testTag(OrderTags.DETAIL_ACTION_PREFIX + button.actionRaw),
                )
            }
        }
    }
}

/**
 * 状态与金额卡：状态文案（标题角色）+ 眼睛按钮；两列金额格（横排实付在左、返利在右，纵排返利在上）；hint（WAITING 只在有预计结算月份时「预计 {M} 月结算」，PAID「确认
 * 收货后随联盟月度结算入账」，其余按映射表）；diff 类原因标题（G03）与比价无返利 hint，行尾「查看说明」（配置了才有，不占按钮位）。
 */
@Composable
private fun StatusCard(
    texts: OrderTexts,
    detail: OrderDetail,
    why: WhyNoRebateView,
    hasHelp: Boolean,
    masked: Boolean,
    config: OrderDetailConfig,
    onHelp: () -> Unit,
) {
    val summary = detail.summary
    val status = OrderStatusText.of(summary.displayStatus)
    val period = CreditPeriodText.of(summary.displayStatus, detail.expectedCreditPeriod, config.clock())
    val hint =
        when (summary.displayStatus) {
            DisplayStatus.WAITING -> period.monthText?.let { texts.format(CreditPeriodText.WAITING_KEY, mapOf("credit_period" to it)) }
            else -> status.hintKey?.let(texts::text)
        }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .couliCard()
                .padding(CouliSpacing.space4)
                .testTag(OrderTags.DETAIL_STATUS),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = OrderUi.statusLabel(texts, status.labelKey, summary.clawbackFen, masked),
                style = CouliTextStyles.bodySemibold,
                color = CouliColors.textPrimary,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            CouliIconButton(
                icon = if (masked) CouliIcons.eyeOff else CouliIcons.eye,
                label = texts.text(if (masked) OrderUi.MASK_SHOW_KEY else OrderUi.MASK_HIDE_KEY),
                onClick = { config.mask?.toggle() },
                tint = CouliColors.textSecondary,
                iconSize = CouliSizes.iconMedium,
                modifier = Modifier.testTag(OrderTags.MASK),
            )
        }
        // 横排左实付、右返利；纵排（200% 字号、窄屏、金额过长）返利在上、实付在下（与列表卡同一规则，APP-08 共性第 5 条）
        val (cells, stackOrder) = OrderUi.listCells(texts, OrderAmountCells.of(summary))
        AmountGrid(
            cells = cells,
            mask = OrderUi.mask(texts, masked),
            stackOrder = stackOrder,
            modifier = Modifier.testTag(OrderTags.DETAIL_AMOUNTS),
        )
        if (!hint.isNullOrEmpty()) Text(text = hint, style = CouliTypography.footnote, color = CouliColors.textSecondary)
        // 比价无返利 hint（BR-TEXT-03 ③）或 diff 类原因标题（G03：差额行要的字段契约没有，只显示原因标题）
        val inline =
            when {
                why.hintKey != null -> texts.text(why.hintKey!!)
                why.show && why.descKey == null && why.titleKey != null -> texts.text(why.titleKey!!)
                else -> null
            }
        if (inline != null) {
            EndWrapRow(
                modifier = Modifier.fillMaxWidth().testTag(OrderTags.DETAIL_REASON),
                start = { Text(text = inline, style = CouliTypography.footnote, color = CouliColors.textPrimary) },
                end = {
                    if (hasHelp) {
                        CouliButton(
                            text = texts.text("order_detail.view_help"),
                            onClick = onHelp,
                            variant = CouliButtonVariant.TEXT,
                            size = CouliButtonSize.COMPACT,
                            modifier = Modifier.testTag(OrderTags.DETAIL_HELP),
                        )
                    }
                },
            )
        }
    }
}

/** 原因区（INVALID / CLAWED_BACK，AI-09：直接显示原因标题与说明；CLAWED_BACK 另加「已从余额扣除」）。 */
@Composable
private fun ReasonCard(
    texts: OrderTexts,
    why: WhyNoRebateView,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .couliCard()
                .padding(CouliSpacing.space4)
                .testTag(OrderTags.DETAIL_REASON),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
    ) {
        why.headingKey?.let {
            Text(
                text = texts.text(it),
                style = CouliTypography.caption,
                color = CouliColors.textSecondary,
                modifier = Modifier.semantics { heading() },
            )
        }
        why.titleKey?.let { Text(text = texts.text(it), style = CouliTextStyles.bodySemibold, color = CouliColors.textPrimary) }
        val desc = listOfNotNull(why.descKey?.let(texts::text), why.extraKey?.let(texts::text)).filter(String::isNotEmpty)
        if (desc.isNotEmpty()) {
            Text(text = desc.joinToString(texts.text(DESC_JOIN_KEY)), style = CouliTypography.footnote, color = CouliColors.textSecondary)
        }
    }
}

/** 「申诉中」块（G08）：只有 appeal_pending；【查看申诉进度】→ Appeal（不带参数）。 */
@Composable
private fun AppealBlock(
    texts: OrderTexts,
    onProgress: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    CouliColors.statusPendingBackground,
                    androidx.compose.foundation.shape
                        .RoundedCornerShape(com.couli.core.designsystem.CouliRadius.button),
                ).padding(CouliSpacing.space3)
                .testTag(OrderTags.DETAIL_APPEAL),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                CouliIcons.clock,
                contentDescription = null,
                tint = CouliColors.statusPendingText,
                modifier = Modifier.size(CouliSizes.iconSmall),
            )
            Text(
                text = texts.text("order_detail.appeal_pending.title"),
                style = CouliTextStyles.footnoteSemibold,
                color = CouliColors.textPrimary,
            )
        }
        Text(text = texts.text("order_detail.appeal_pending.desc"), style = CouliTypography.footnote, color = CouliColors.textSecondary)
        CouliButton(
            text = texts.text("order_detail.appeal_progress"),
            onClick = onProgress,
            variant = CouliButtonVariant.TEXT,
            size = CouliButtonSize.COMPACT,
        )
    }
}

/** 商品卡：平台 +「查看商品」（放不下时链接换到下一行右对齐）；商品图 + 标题 + 件数；订单号 + 复制。 */
@Composable
private fun ProductCard(
    texts: OrderTexts,
    detail: OrderDetail,
    onViewProduct: () -> Unit,
    onCopy: () -> Unit,
) {
    val summary = detail.summary
    val titleView = OrderTitleImage.of(summary)
    val title = titleView.titleKey?.let(texts::text) ?: titleView.title.orEmpty()
    Column(
        modifier = Modifier.fillMaxWidth().couliCard().padding(CouliSpacing.space4),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        EndWrapRow(
            modifier = Modifier.fillMaxWidth(),
            start = { PlatformBadge(name = texts.text("platform.${summary.platform}"), platformCode = summary.platform) },
            end = {
                if (ViewProductEntry.of(detail) != null) {
                    CouliButton(
                        text = texts.text("order_detail.view_product"),
                        onClick = onViewProduct,
                        variant = CouliButtonVariant.TEXT,
                        size = CouliButtonSize.COMPACT,
                        modifier = Modifier.testTag(OrderTags.DETAIL_VIEW_PRODUCT),
                    )
                }
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3)) {
            ProductThumbnail(label = texts.text("order_list.image_placeholder"), size = THUMB.dp, imageUrl = titleView.imageUrl)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
                if (title.isNotEmpty()) Text(text = title, style = CouliTypography.body, color = CouliColors.textPrimary)
                summary.quantity?.let {
                    Text(
                        text = texts.format("order_list.quantity", mapOf("n" to it.toString())),
                        style = CouliTypography.footnote,
                        color = CouliColors.textSecondary,
                    )
                }
            }
        }
        OrderNumberRow(
            number = OrderNumberDisplay.of(summary),
            texts = texts,
            onCopy = onCopy,
            textTag = OrderTags.DETAIL_ORDER_NO,
            copyTag = OrderTags.DETAIL_COPY,
        )
    }
}

/** 时间线（读屏为列表；未发生的节点置灰并读出「未发生」；装饰圆点不读）。 */
@Composable
private fun TimelineCard(
    texts: OrderTexts,
    detail: OrderDetail,
    now: Long,
) {
    val rows = OrderTimelineView.of(detail.summary.displayStatus, detail.timeline, now)
    if (rows.isEmpty()) return
    val notHappened = texts.text("order_timeline.not_happened")
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .couliCard()
                .padding(CouliSpacing.space4)
                .semantics {
                    contentDescription = texts.text("order_detail.timeline")
                    collectionInfo = CollectionInfo(rowCount = rows.size, columnCount = 1)
                }.testTag(OrderTags.DETAIL_TIMELINE),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        rows.forEachIndexed { index, row ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .semantics(mergeDescendants = true) {
                            collectionItemInfo = CollectionItemInfo(rowIndex = index, rowSpan = 1, columnIndex = 0, columnSpan = 1)
                            if (row.grey) stateDescription = notHappened
                        },
                horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
            ) {
                Box(
                    modifier =
                        Modifier
                            .padding(top = CouliSpacing.space1)
                            .size(DOT.dp)
                            .then(
                                if (row.grey) {
                                    Modifier.border(CouliSizes.hairline, CouliColors.borderControl, CircleShape)
                                } else {
                                    Modifier.background(CouliColors.brandPrimary, CircleShape)
                                },
                            ),
                )
                Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1 / 2)) {
                    Text(
                        text = timelineLabel(texts, row),
                        style = CouliTypography.footnote,
                        color = if (row.grey) CouliColors.textSecondary else CouliColors.textPrimary,
                    )
                    row.time?.let { Text(text = it, style = CouliTypography.caption, color = CouliColors.textSecondary) }
                }
            }
        }
    }
}

/** 节点名：带 {time} 的契约键（付定金 / 付尾款）只取名称，时间单独一行；credit_expected 按 G16。 */
private fun timelineLabel(
    texts: OrderTexts,
    row: TimelineRow,
): String =
    if (row.node == OrderTimelineNode.CREDIT_EXPECTED && row.monthText != null) {
        texts.format(CREDIT_MONTH_KEY, mapOf("credit_period" to row.monthText!!))
    } else {
        texts.format(row.labelKey, emptyMap()).trim()
    }

@Composable
private fun DetailSkeleton(texts: OrderTexts) {
    val loading = texts.text("order_list.loading")
    Column(
        modifier = Modifier.fillMaxWidth().padding(CouliSpacing.space4).semantics { contentDescription = loading },
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        Skeleton(Modifier.fillMaxWidth().heightIn(min = SKELETON_STATUS.dp))
        Skeleton(Modifier.fillMaxWidth().heightIn(min = SKELETON_PRODUCT.dp))
        Skeleton(Modifier.fillMaxWidth().heightIn(min = SKELETON_TIMELINE.dp))
    }
}

private const val CREDIT_MONTH_KEY = "order_timeline.credit_expected_month"
private const val DESC_JOIN_KEY = "order_detail.desc_join"
private const val THUMB = 64
private const val DOT = 10
private const val SKELETON_STATUS = 120
private const val SKELETON_PRODUCT = 96
private const val SKELETON_TIMELINE = 160
