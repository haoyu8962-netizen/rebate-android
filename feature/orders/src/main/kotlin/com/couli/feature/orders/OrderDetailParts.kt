package com.couli.feature.orders

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.couli.core.designsystem.AmountGrid
import com.couli.core.designsystem.AmountValueSize
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliDivider
import com.couli.core.designsystem.CouliIconButton
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliRadius
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.EndWrapRow
import com.couli.core.designsystem.PlatformBadge
import com.couli.core.designsystem.ProductThumbnail
import com.couli.core.designsystem.couliCard
import com.couli.core.orders.CreditPeriodText
import com.couli.core.orders.OrderActionButton
import com.couli.core.orders.OrderAmountCells
import com.couli.core.orders.OrderDetail
import com.couli.core.orders.OrderNumberDisplay
import com.couli.core.orders.OrderStatusText
import com.couli.core.orders.OrderTexts
import com.couli.core.orders.OrderTimelineView
import com.couli.core.orders.OrderTitleImage
import com.couli.core.orders.TimelineRow
import com.couli.core.orders.ViewProductEntry
import com.couli.core.orders.WhyNoRebateView
import com.couli.generated.DisplayStatus
import com.couli.generated.OrderTimelineNode

/** 详情页卡内入口的回调（渲染时由页面带上当时的版本 / 身份构造；执行时只比较，不读最新状态，R3-02）。 */
internal class DetailHandlers(
    val onHelp: () -> Unit,
    val onAppeal: () -> Unit,
    val onAction: (OrderActionButton) -> Unit,
    val onRefresh: () -> Unit,
    val onToggleMask: () -> Unit,
)

/** 状态卡要的数据（规则都已在 core:orders 算好，这里只摆）。 */
@Suppress("LongParameterList") // 只读数据逐项给（详情、原因、入口有无、卡内按钮、隐藏、禁用期、时钟）
internal class StatusCardModel(
    val detail: OrderDetail,
    val why: WhyNoRebateView,
    val hasHelp: Boolean,
    val appealPending: Boolean,
    /** 卡内按钮（[OrderUi.splitActions] 的卡内部分：reason.action 与【查看流水】，原顺序）。 */
    val cardButtons: List<OrderActionButton>,
    val masked: Boolean,
    val blocked: Boolean,
    val now: Long,
)

/**
 * 状态卡（O21–O28）：标题行（状态图标 20 + 状态标题 17/600 heading + 眼睛）；两列数字格（左返利 28/700、右实付 22/600，纵排返利在上，G-O07）；
 * WAITING「预计 {M} 月结算」灰胶囊 / DEPOSIT_PAID 提示紧跟金额；有下列内容时分隔线之后依次为：提示、原因区（「为什么没返利？」heading + 标题 + 说明）
 * 或差额 / 比价行（+「查看说明 ›」）、「申诉中」块、卡内按钮（1 个描边、2 个首个实心）或未知状态的【刷新】+「查看流水」。
 */
@Composable
internal fun StatusCard(
    texts: OrderTexts,
    model: StatusCardModel,
    handlers: DetailHandlers,
) {
    val summary = model.detail.summary
    val status = summary.displayStatus
    val hint = statusHint(texts, model)
    // WAITING 的月份胶囊与 DEPOSIT_PAID 的提示紧跟金额（OrderDetailEstimated / Presale）；其余提示进分隔线下（RightsPending / Reviewing / Unknown）
    val topHint = status == DisplayStatus.WAITING || status == DisplayStatus.DEPOSIT_PAID
    val lowerHint = hint.takeUnless { topHint }
    val unknown = status == DisplayStatus.UNKNOWN
    val lower = lowerHint != null || model.why.show || model.appealPending || model.cardButtons.isNotEmpty() || unknown
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .couliCard()
                .padding(CouliSpacing.space4)
                .testTag(OrderTags.DETAIL_STATUS),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        StatusTitleRow(texts, model, handlers.onToggleMask)
        val (cells, stackOrder) = OrderUi.detailCells(texts, OrderAmountCells.of(summary))
        AmountGrid(
            cells = cells,
            mask = OrderUi.mask(texts, model.masked),
            stackOrder = stackOrder,
            valueSizes = if (cells.size == 2) DETAIL_VALUE_SIZES else null,
            modifier = Modifier.testTag(OrderTags.DETAIL_AMOUNTS),
        )
        if (topHint && hint != null) {
            if (status == DisplayStatus.WAITING) {
                MutedPill(hint)
            } else {
                Text(text = hint, style = CouliTypography.footnote, color = CouliColors.textSecondary)
            }
        }
        if (lower) {
            CouliDivider()
            StatusLower(texts, model, handlers, lowerHint)
        }
    }
}

private val DETAIL_VALUE_SIZES = listOf(AmountValueSize.HEADING, AmountValueSize.TITLE)

/** 提示（WAITING 只在有预计结算月份时「预计 {M} 月结算」，其余按映射表；不变）。 */
private fun statusHint(
    texts: OrderTexts,
    model: StatusCardModel,
): String? {
    val status = model.detail.summary.displayStatus
    val text =
        when (status) {
            DisplayStatus.WAITING -> {
                CreditPeriodText
                    .of(status, model.detail.expectedCreditPeriod, model.now)
                    .monthText
                    ?.let { texts.format(CreditPeriodText.WAITING_KEY, mapOf("credit_period" to it)) }
            }

            else -> {
                OrderStatusText.of(status).hintKey?.let(texts::text)
            }
        }
    return text?.takeIf(String::isNotEmpty)
}

/** 标题行（O21）：图标与标题已结算类为 `status.success` 文字色，其余图标次文字色、标题主文字；图标装饰（读屏只读标题）。 */
@Composable
private fun StatusTitleRow(
    texts: OrderTexts,
    model: StatusCardModel,
    onToggleMask: () -> Unit,
) {
    val summary = model.detail.summary
    val status = summary.displayStatus
    val settled = OrderUi.settled(status)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
        Icon(
            OrderUi.statusIcon(status),
            contentDescription = null,
            tint = if (settled) CouliColors.statusSuccessText else CouliColors.textSecondary,
            modifier = Modifier.size(CouliSizes.iconMedium).testTag(OrderTags.DETAIL_STATUS_ICON),
        )
        Text(
            text = OrderUi.statusLabel(texts, OrderStatusText.of(status).labelKey, summary.clawbackFen, model.masked),
            style = CouliTextStyles.bodySemibold,
            color = if (settled) CouliColors.statusSuccessText else CouliColors.textPrimary,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        // 眼睛命中区 48（不撑高标题行：视觉只按图标高参与测量，上下溢出到卡片内边距）；向右移 12 使图标与画板同位（画板按钮 -12 外边距），
        // 命中区右缘仍在卡片内
        CouliIconButton(
            icon = if (model.masked) CouliIcons.eyeOff else CouliIcons.eye,
            label = texts.text(if (model.masked) OrderUi.MASK_SHOW_KEY else OrderUi.MASK_HIDE_KEY),
            onClick = onToggleMask,
            tint = CouliColors.textSecondary,
            iconSize = CouliSizes.iconMedium,
            modifier = Modifier.visualHeight(CouliSizes.iconMedium).offset(x = CouliSpacing.space3).testTag(OrderTags.MASK),
        )
    }
}

/** 分隔线之下（O23–O28）。 */
@Composable
private fun StatusLower(
    texts: OrderTexts,
    model: StatusCardModel,
    handlers: DetailHandlers,
    hint: String?,
) {
    val why = model.why
    val unknown = model.detail.summary.displayStatus == DisplayStatus.UNKNOWN
    Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3)) {
        if (hint != null || why.show) {
            Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
                hint?.let {
                    Text(
                        text = it,
                        style = CouliTypography.footnote,
                        color = if (unknown) CouliColors.textSecondary else CouliColors.textPrimary,
                    )
                }
                if (why.show &&
                    why.descKey != null
                ) {
                    ReasonBlock(texts, why, model.appealPending)
                } else {
                    InlineReason(texts, model, handlers.onHelp)
                }
            }
        }
        if (model.appealPending) AppealBlock(texts, handlers.onAppeal, reserveBelow = unknown || model.cardButtons.isNotEmpty())
        if (unknown) {
            UnknownActions(texts, model, handlers)
        } else if (model.cardButtons.isNotEmpty()) {
            CardButtons(texts, model.cardButtons, handlers.onAction)
        }
    }
}

/**
 * 原因区（INVALID / CLAWED_BACK，AI-09：直接显示，不进 AI）：「为什么没返利？」14/600 heading（CLAWED_BACK 没有）→ 原因标题 14 主文字 → 说明 14 次文字
 * （CLAWED_BACK 说明与「已从余额扣除」用 order_detail.desc_join 拼）；说明取舍见 [reasonDescKeys]。
 */
@Composable
private fun ReasonBlock(
    texts: OrderTexts,
    why: WhyNoRebateView,
    appealPending: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag(OrderTags.DETAIL_REASON),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
    ) {
        why.headingKey?.let {
            Text(
                text = texts.text(it),
                style = CouliTextStyles.footnoteSemibold,
                color = CouliColors.textPrimary,
                modifier = Modifier.semantics { heading() },
            )
        }
        why.titleKey?.let { Text(text = texts.text(it), style = CouliTypography.footnote, color = CouliColors.textPrimary) }
        val desc = reasonDescKeys(why, appealPending).map(texts::text).filter(String::isNotEmpty)
        if (desc.isNotEmpty()) {
            Text(text = desc.joinToString(texts.text(DESC_JOIN_KEY)), style = CouliTypography.footnote, color = CouliColors.textSecondary)
        }
    }
}

/**
 * 原因说明键（r1 S2-1 / S2-4）：
 * - PUNISH（G-O03）：子原因 `order_reason_sub` 无字段，只显示标题，不画代拟说明；扣回态仍保留「已从余额扣除」。
 * - 只有画板 OrderDetailAppealPending-v2 明确的 INVALID（有 heading）+ BLACKLIST + 申诉处理中省略说明（由下方「申诉中」块代替）；
 *   REFUND / RIGHTS 等其他失效原因与扣回态照常保留原说明。
 */
private fun reasonDescKeys(
    why: WhyNoRebateView,
    appealPending: Boolean,
): List<String> {
    val pendingBlacklist = appealPending && why.headingKey != null && why.reason == BLACKLIST_REASON
    val descKey = why.descKey.takeUnless { why.reason == PUNISH_REASON || pendingBlacklist }
    return listOfNotNull(descKey, why.extraKey)
}

private const val PUNISH_REASON = "PUNISH"
private const val BLACKLIST_REASON = "BLACKLIST"

/**
 * 差额 / 比价行（O25）：比价无返利 hint（BR-TEXT-03 ③，主文字）或 diff 类原因标题（G-O03：差额金额字段契约没有，只显示原因标题，次文字）；
 * 「查看说明 ›」配置了才有，放得下在行尾、放不下换到下一行（不变）。
 */
@Composable
private fun InlineReason(
    texts: OrderTexts,
    model: StatusCardModel,
    onHelp: () -> Unit,
) {
    val why = model.why
    val (inline, color) =
        when {
            why.hintKey != null -> texts.text(why.hintKey!!) to CouliColors.textPrimary
            why.show && why.descKey == null && why.titleKey != null -> texts.text(why.titleKey!!) to CouliColors.textSecondary
            else -> return
        }
    Layout(
        modifier = Modifier.fillMaxWidth().testTag(OrderTags.DETAIL_REASON),
        content = {
            Text(text = inline, style = CouliTypography.footnote, color = color)
            if (model.hasHelp) {
                LinkButton(texts.text("order_detail.view_help"), onHelp, Modifier.testTag(OrderTags.DETAIL_HELP), medium = true)
            }
        },
        measurePolicy = HelpWrapPolicy,
    )
}

/** 放得下：说明在左、「查看说明 ›」在行尾（Settled-v2）；放不下：「查看说明 ›」换到下一行行首（PriceCompareNoRebate-v2 / Risk-v2）。 */
private val HelpWrapPolicy =
    MeasurePolicy { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val width = constraints.maxWidth
        val link = measurables.getOrNull(1)?.measure(loose)
        if (link == null) {
            val text = measurables[0].measure(loose)
            return@MeasurePolicy layout(width, text.height) { text.placeRelative(0, 0) }
        }
        val gap = CouliSpacing.space3.roundToPx()
        val sameLine = measurables[0].maxIntrinsicWidth(constraints.maxHeight) + gap + link.width <= width
        val text = measurables[0].measure(if (sameLine) loose.copy(maxWidth = width - gap - link.width) else loose)
        if (sameLine) {
            val height = maxOf(text.height, link.height)
            layout(width, height) {
                text.placeRelative(0, (height - text.height) / 2)
                link.placeRelative(width - link.width, (height - link.height) / 2)
            }
        } else {
            val linkY = text.height + CouliSpacing.space1.roundToPx()
            layout(width, linkY + link.height) {
                text.placeRelative(0, 0)
                link.placeRelative(0, linkY)
            }
        }
    }

/**
 * 「申诉中」块（G08 / O26）：灰胶囊（钟表 14 +「申诉中」）+ 说明（不带提交时间，G-O03）→「查看申诉进度 ›」（Appeal，不带参数）。
 * 下方还有卡内按钮时（[reserveBelow]），进度链接下方留出其 48 命中区向下扩展的空间（r1 S2-2），与按钮命中区不重叠；链接视觉高度不变。
 */
@Composable
private fun AppealBlock(
    texts: OrderTexts,
    onProgress: () -> Unit,
    reserveBelow: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag(OrderTags.DETAIL_APPEAL),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            MutedPill(texts.text("order_detail.appeal_pending.title"), icon = true)
            Text(text = texts.text("order_detail.appeal_pending.desc"), style = CouliTypography.footnote, color = CouliColors.textSecondary)
        }
        val touch = LocalViewConfiguration.current.minimumTouchTargetSize.height
        Box(modifier = if (reserveBelow) Modifier.reserveTouchExpansionBelow(touch) else Modifier) {
            LinkButton(texts.text("order_detail.appeal_progress"), onProgress, medium = true)
        }
    }
}

/** 内容按原高度摆在顶部，下方多占「最小触控区 − 内容高」的一半（命中区向下扩展的部分）；不改内容自身视觉与命中区。 */
private fun Modifier.reserveTouchExpansionBelow(touch: Dp): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val extra = ((touch.roundToPx() - placeable.height + 1) / 2).coerceAtLeast(0)
        layout(placeable.width, placeable.height + extra) { placeable.placeRelative(0, 0) }
    }

/** 卡内按钮（G-O06）：左对齐并排、放不下换行；1 个描边次按钮，2 个时第一个实心主按钮 + 第二个描边（由区域决定，不回写规则数据）。 */
@Composable
private fun CardButtons(
    texts: OrderTexts,
    buttons: List<OrderActionButton>,
    onAction: (OrderActionButton) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        buttons.forEachIndexed { index, button ->
            CardActionButton(
                text = texts.text(button.labelKey),
                filled = buttons.size > 1 && index == 0,
                onClick = { onAction(button) },
                modifier = Modifier.testTag(OrderTags.DETAIL_ACTION_PREFIX + button.actionRaw),
            )
        }
    }
}

/**
 * 未知状态（G-O08 / OrderDetailUnknown）：描边【刷新】（重取本单，遵守 42901 置灰；渲染时版本 / 身份）+ 卡内按钮画成文字链（规则只给【查看流水】）；
 * 不沿用两按钮主次外观。
 */
@Composable
private fun UnknownActions(
    texts: OrderTexts,
    model: StatusCardModel,
    handlers: DetailHandlers,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        CardActionButton(
            text = texts.text(REFRESH_KEY),
            filled = false,
            enabled = !model.blocked,
            onClick = handlers.onRefresh,
            modifier = Modifier.testTag(OrderTags.DETAIL_REFRESH),
        )
        model.cardButtons.forEach { button ->
            LinkButton(
                text = texts.text(button.labelKey),
                onClick = { handlers.onAction(button) },
                modifier = Modifier.padding(horizontal = CouliSpacing.space2).testTag(OrderTags.DETAIL_ACTION_PREFIX + button.actionRaw),
                medium = true,
                chevron = false,
            )
        }
    }
}

/**
 * 卡内按钮外观（O27）：视觉高 40（上下 8 + 14/500 文字）、圆角 12、左右 16；实心为主色底白字，描边为白底 1 控件边主文字；命中区 ≥48 由最小触控区
 * 扩展给出（不撑高视觉）。字号放大时随文字增高、不截字。
 */
@Composable
private fun CardActionButton(
    text: String,
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(CouliRadius.button)
    val content =
        when {
            !enabled -> CouliColors.buttonPrimaryDisabledText
            filled -> CouliColors.buttonPrimaryDefaultText
            else -> CouliColors.textPrimary
        }
    Box(
        modifier =
            modifier
                .heightIn(min = CouliSpacing.space10)
                .clip(shape)
                .background(if (filled && enabled) CouliColors.buttonPrimaryDefaultBackground else CouliColors.backgroundSurface)
                .then(if (filled && enabled) Modifier else Modifier.border(CouliSizes.hairline, CouliColors.borderControl, shape))
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space2),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = CouliTextStyles.footnoteMedium, color = content)
    }
}

/** 文字链（「查看商品 ›」「查看说明 ›」「查看申诉进度 ›」「查看流水」）：14 强调色 + 尾部 16 箭头；视觉只占文字高，命中区 ≥48 由最小触控区扩展给出。 */
@Composable
private fun LinkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    medium: Boolean = false,
    chevron: Boolean = true,
) {
    Row(
        modifier = modifier.clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
    ) {
        Text(
            text = text,
            style = if (medium) CouliTextStyles.footnoteMedium else CouliTypography.footnote,
            color = CouliColors.brandEmphasis,
        )
        if (chevron) {
            Icon(
                CouliIcons.chevron,
                contentDescription = null,
                tint = CouliColors.brandEmphasis,
                modifier = Modifier.size(CouliSpacing.space4),
            )
        }
    }
}

/** 灰胶囊（O23 / O26）：最小高 22（随字号增高）、左右 8、圆角满、`background.muted` 底 + 12 次文字；[icon] 时前加钟表 14（装饰）。 */
@Composable
private fun MutedPill(
    text: String,
    icon: Boolean = false,
) {
    Row(
        modifier =
            Modifier
                .heightIn(min = CouliSizes.tagHeight)
                .background(CouliColors.backgroundMuted, RoundedCornerShape(CouliRadius.pill))
                .padding(horizontal = CouliSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
    ) {
        if (icon) {
            Icon(
                CouliIcons.clock,
                contentDescription = null,
                tint = CouliColors.textSecondary,
                modifier = Modifier.size(CouliSizes.iconTiny),
            )
        }
        Text(text = text, style = CouliTypography.caption, color = CouliColors.textSecondary)
    }
}

/**
 * 商品卡（O29）：平台徽标 +「查看商品 ›」（放不下换行右对齐）；64 商品图（分享单其他商品为文档图标占位）+ 右列 标题 14/500（不限行数、不省略：
 * 完整名在详情显示）→ 件数 → 订单号 +【复制】紧随（同列表卡）。回调不变。
 */
@Composable
internal fun ProductCard(
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
                    LinkButton(texts.text("order_detail.view_product"), onViewProduct, Modifier.testTag(OrderTags.DETAIL_VIEW_PRODUCT))
                }
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3)) {
            if (summary.isOtherProduct) {
                OtherProductThumbnail()
            } else {
                ProductThumbnail(label = texts.text("order_list.image_placeholder"), size = THUMB.dp, imageUrl = titleView.imageUrl)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
                if (title.isNotEmpty()) Text(text = title, style = CouliTextStyles.footnoteMedium, color = CouliColors.textPrimary)
                summary.quantity?.let {
                    Text(
                        text = texts.format("order_list.quantity", mapOf("n" to it.toString())),
                        style = CouliTextStyles.captionAmount,
                        color = CouliColors.textSecondary,
                    )
                }
                OrderNumberInline(
                    number = OrderNumberDisplay.of(summary),
                    texts = texts,
                    onCopy = onCopy,
                    textTag = OrderTags.DETAIL_ORDER_NO,
                    copyTag = OrderTags.DETAIL_COPY,
                )
            }
        }
    }
}

/**
 * 时间线（O30）：每节点左侧 16 宽轨道——已发生 8 实心（次文字色）、未发生 8 空心（`border.control` 描边）、当前节点 16 外环 + 8 点且名称 600
 * （已结算类用 `status.success`），节点间 1 竖线（`border.subtle`）；名称 14、时间 12 次文字。当前节点：WAITING 的「预计 {M} 月结算」行，
 * 否则最后一个已发生的节点；未知状态不标当前。读屏为列表、未发生读「未发生」、装饰圆点不读（不变）。
 */
@Composable
internal fun TimelineCard(
    texts: OrderTexts,
    detail: OrderDetail,
    now: Long,
) {
    val status = detail.summary.displayStatus
    val rows = OrderTimelineView.of(status, detail.timeline, now)
    if (rows.isEmpty()) return
    val notHappened = texts.text("order_timeline.not_happened")
    val current = currentNode(status, rows)
    // 当前节点用成功色只在已结算（Settled-v2）；部分扣回的当前节点是「部分扣回」，按画板中性（PartClawed-v2）
    val settled = status == DisplayStatus.CREDITED
    val dotArea = with(LocalDensity.current) { CouliTypography.footnote.lineHeight.toDp() }
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
    ) {
        rows.forEachIndexed { index, row ->
            val last = index == rows.lastIndex
            val kind =
                when {
                    index == current -> NodeKind.CURRENT
                    row.grey -> NodeKind.FUTURE
                    else -> NodeKind.DONE
                }
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .semantics(mergeDescendants = true) {
                            collectionItemInfo = CollectionItemInfo(rowIndex = index, rowSpan = 1, columnIndex = 0, columnSpan = 1)
                            if (row.grey) stateDescription = notHappened
                        },
                horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
            ) {
                TimelineRail(kind, settled, dotArea, connector = !last)
                Column(modifier = Modifier.padding(bottom = if (last) CouliSpacing.space0 else CouliSpacing.space4)) {
                    Text(text = timelineLabel(texts, row), style = labelStyle(kind), color = labelColor(kind, settled))
                    row.time?.let { Text(text = it, style = CouliTextStyles.captionAmount, color = CouliColors.textSecondary) }
                }
            }
        }
    }
}

private enum class NodeKind { DONE, CURRENT, FUTURE }

/**
 * 当前节点（按画板）：只在状态本身落在时间线某节点上时标——WAITING 为「预计 {M} 月结算」行，PAID / DEPOSIT_PAID / 已结算 / 部分扣回 / 已扣回 /
 * 已失效为最后一个已发生的节点；售后处理中、入账核对中、本单无返利、未知状态不标（RightsPending / Reviewing / PriceCompareNoRebate / Unknown）。
 */
private fun currentNode(
    status: DisplayStatus,
    rows: List<TimelineRow>,
): Int =
    when {
        status !in CURRENT_STATUSES -> -1
        rows.any { it.monthText != null } -> rows.indexOfFirst { it.monthText != null }
        else -> rows.indexOfLast { !it.grey }
    }

private val CURRENT_STATUSES =
    setOf(
        DisplayStatus.PAID,
        DisplayStatus.DEPOSIT_PAID,
        DisplayStatus.WAITING,
        DisplayStatus.CREDITED,
        DisplayStatus.CREDITED_PART_CLAWED,
        DisplayStatus.CLAWED_BACK,
        DisplayStatus.INVALID,
    )

private fun labelStyle(kind: NodeKind): TextStyle =
    if (kind ==
        NodeKind.CURRENT
    ) {
        CouliTextStyles.footnoteSemibold
    } else {
        CouliTypography.footnote
    }

@Composable
private fun labelColor(
    kind: NodeKind,
    settled: Boolean,
): Color =
    when {
        kind == NodeKind.CURRENT && settled -> CouliColors.statusSuccessText
        kind == NodeKind.FUTURE -> CouliColors.textSecondary
        else -> CouliColors.textPrimary
    }

/** 轨道：圆点居中在名称首行高度内，非末节点下接 1 竖线到下一节点。 */
@Composable
private fun TimelineRail(
    kind: NodeKind,
    settled: Boolean,
    dotArea: androidx.compose.ui.unit.Dp,
    connector: Boolean,
) {
    Column(modifier = Modifier.width(CouliSpacing.space4).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.height(dotArea), contentAlignment = Alignment.Center) {
            when (kind) {
                NodeKind.CURRENT -> {
                    val ring = if (settled) CouliColors.statusSuccessBackground else CouliColors.backgroundMuted
                    val dot = if (settled) CouliColors.statusSuccessText else CouliColors.textPrimary
                    Box(modifier = Modifier.size(CouliSpacing.space4).background(ring, CircleShape), contentAlignment = Alignment.Center) {
                        Box(modifier = Modifier.size(CouliSpacing.space2).background(dot, CircleShape))
                    }
                }

                NodeKind.DONE -> {
                    Box(modifier = Modifier.size(CouliSpacing.space2).background(CouliColors.textSecondary, CircleShape))
                }

                NodeKind.FUTURE -> {
                    Box(
                        modifier =
                            Modifier
                                .size(CouliSpacing.space2)
                                .background(CouliColors.backgroundSurface, CircleShape)
                                .border(CouliSizes.hairline, CouliColors.borderControl, CircleShape),
                    )
                }
            }
        }
        if (connector) Box(modifier = Modifier.width(CouliSizes.hairline).weight(1f).background(CouliColors.borderSubtle))
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

/**
 * 【问问 AI】整行卡（O31，时间线之后）：白卡、32 方块（`brand.subtle`、圆角 8、对话图标 20 强调色）+「问问 AI」17/500 + 尾部 ›；整行一个目标
 * （Role.Button，≥48）。显隐与去向由页面给（不变）。
 */
@Composable
internal fun AskAiRow(
    texts: OrderTexts,
    onAsk: () -> Unit,
) {
    // 内边距放在内层 Row：整卡的布局坐标与读屏 / 命中边界一致（r1 S2-3 的整卡安全区检查按布局坐标量）；外观不变
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .couliCard()
                .clip(RoundedCornerShape(CouliRadius.card))
                .clickable(role = Role.Button, onClick = onAsk)
                .heightIn(min = CouliSizes.touchTarget)
                .testTag(OrderTags.ASK_AI),
        contentAlignment = Alignment.CenterStart,
    ) { AskAiContent(texts) }
}

@Composable
private fun AskAiContent(texts: OrderTexts) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        Box(
            modifier = Modifier.size(CouliSpacing.space8).background(CouliColors.brandSubtle, RoundedCornerShape(CouliRadius.small)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                CouliIcons.chat,
                contentDescription = null,
                tint = CouliColors.brandEmphasis,
                modifier = Modifier.size(CouliSizes.iconMedium),
            )
        }
        Text(text = texts.text("order_detail.ask_ai"), style = CouliTextStyles.bodyMedium, color = CouliColors.textPrimary)
        Spacer(Modifier.weight(1f))
        Icon(
            CouliIcons.chevron,
            contentDescription = null,
            tint = CouliColors.textSecondary,
            modifier = Modifier.size(CouliSizes.iconMedium),
        )
    }
}

private const val CREDIT_MONTH_KEY = "order_timeline.credit_expected_month"
private const val DESC_JOIN_KEY = "order_detail.desc_join"
private const val REFRESH_KEY = "order_detail.refresh"
private const val THUMB = 64
