package com.couli.feature.orders

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.couli.core.designsystem.AmountGrid
import com.couli.core.designsystem.AmountValueSize
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliDivider
import com.couli.core.designsystem.CouliFormat
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliRadius
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.PlatformBadge
import com.couli.core.designsystem.ProductThumbnail
import com.couli.core.designsystem.Skeleton
import com.couli.core.designsystem.couliCard
import com.couli.core.orders.CreditPeriodText
import com.couli.core.orders.OrderAmountCells
import com.couli.core.orders.OrderFormat
import com.couli.core.orders.OrderNumberDisplay
import com.couli.core.orders.OrderStatusText
import com.couli.core.orders.OrderSummary
import com.couli.core.orders.OrderTexts
import com.couli.core.orders.OrderTitleImage

/**
 * 订单卡（Orders-v2 / OrdersShare-v2，O9–O14）：整卡一个目标（打开详情，读屏名称含商品名），卡内【复制】是独立的第二个目标（点击不穿透）。
 * 头行 来源徽标左、付款时间右；商品块 64 图 + 右列（标题 14/500 单行省略 → 件数 → 订单号 +【复制】紧随）；分隔线；两列金额格（不变）；状态胶囊
 * （+ 预计结算月份：列表行没有 expected_credit_period，不显示，G-O02）。
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
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
        ) {
            PlatformBadge(name = texts.text("platform.${item.platform}"), platformCode = item.platform)
            Spacer(Modifier.weight(1f))
            OrderFormat.epochMillis(item.paidAt)?.let {
                Text(text = CouliFormat.timeListPast(it, now), style = CouliTextStyles.captionAmount, color = CouliColors.textSecondary)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3)) {
            if (item.isOtherProduct) {
                OtherProductThumbnail()
            } else {
                ProductThumbnail(label = texts.text("order_list.image_placeholder"), size = THUMB.dp, imageUrl = titleView.imageUrl)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
                if (title.isNotEmpty()) {
                    // 商品名是卡内唯一允许省略的文字（AdaptNarrowOrders：单行省略，完整名在详情）
                    Text(
                        text = title,
                        style = CouliTextStyles.footnoteMedium,
                        color = if (item.isOtherProduct) CouliColors.textSecondary else CouliColors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                item.quantity?.let {
                    Text(
                        text = texts.format("order_list.quantity", mapOf("n" to it.toString())),
                        style = CouliTextStyles.captionAmount,
                        color = CouliColors.textSecondary,
                    )
                }
                OrderNumberInline(
                    number = OrderNumberDisplay.of(item),
                    texts = texts,
                    onCopy = onCopy,
                    textTag = OrderTags.ORDER_NO_PREFIX + item.orderId,
                    copyTag = OrderTags.COPY_PREFIX + item.orderId,
                )
            }
        }
        CouliDivider()
        AmountGrid(cells = cells, valueSize = AmountValueSize.BODY, mask = OrderUi.mask(texts, masked), stackOrder = stackOrder)
        StatusPill(item.displayStatus, OrderUi.statusLabel(texts, status.labelKey, item.clawbackFen, masked))
        period.monthText?.takeIf(String::isNotEmpty)?.let {
            Text(text = it, style = CouliTypography.caption, color = CouliColors.textSecondary)
        }
    }
}

/** 分享单「好友购买的其他商品」的占位（OrdersShare-v2）：占位底 + 文档图标（装饰），不写「商品图」。 */
@Composable
private fun OtherProductThumbnail() {
    Box(
        modifier =
            Modifier
                .size(THUMB.dp)
                .clip(RoundedCornerShape(CouliRadius.small))
                .background(CouliColors.backgroundPlaceholder)
                .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Icon(CouliIcons.orders, contentDescription = null, tint = CouliColors.textSecondary, modifier = Modifier.size(CouliSizes.iconLarge))
    }
}

/**
 * 列表骨架（O18，按新卡结构）：头行（徽标块 + 右侧时间块）、64 图块 + 右列三行、分隔线、两列数字块（标签 + 数值）、胶囊块；读屏「加载中」，
 * 不画可见文字。
 */
@Composable
internal fun SkeletonOrderCard(texts: OrderTexts) {
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
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Skeleton(Modifier.size(width = SKELETON_BADGE.dp, height = CouliSizes.tagHeight))
            Spacer(Modifier.weight(1f))
            Skeleton(Modifier.size(width = SKELETON_TIME.dp, height = CouliSpacing.space4))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3)) {
            Skeleton(Modifier.size(THUMB.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
                Skeleton(Modifier.fillMaxWidth(SKELETON_TITLE).height(CouliSpacing.space4))
                Skeleton(Modifier.size(width = SKELETON_SHORT.dp, height = SKELETON_LINE.dp))
                Skeleton(Modifier.fillMaxWidth(SKELETON_NUMBER).height(SKELETON_LINE.dp))
            }
        }
        CouliDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3)) {
            repeat(2) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
                    Skeleton(Modifier.size(width = SKELETON_SHORT.dp, height = SKELETON_LINE.dp))
                    Skeleton(Modifier.size(width = SKELETON_AMOUNT.dp, height = CouliSizes.tagHeight))
                }
            }
        }
        Skeleton(Modifier.size(width = SKELETON_PILL.dp, height = CouliSizes.tagHeight))
    }
}

private const val THUMB = 64
private const val SKELETON_BADGE = 56
private const val SKELETON_TIME = 48
private const val SKELETON_SHORT = 40
private const val SKELETON_AMOUNT = 72
private const val SKELETON_PILL = 112
private const val SKELETON_LINE = 14
private const val SKELETON_TITLE = 0.8f
private const val SKELETON_NUMBER = 0.6f
