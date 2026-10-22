package com.couli.feature.wallet

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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliDivider
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTag
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTextStyles.tabular
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.EndWrapRow
import com.couli.core.designsystem.Skeleton
import com.couli.core.designsystem.couliCard
import com.couli.core.wallet.Withdrawal

/** 一段（一个 Lazy 项）：月份标题（12 次文字、缩进 4）+ 若干块；相邻收起行共用一张月卡，展开项单独成卡（W19 / W20，G-W08）。 */
@Composable
internal fun RecordSectionItem(
    section: RecordSection,
    ctx: RecordRenderContext,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3)) {
        if (section.showMonth) {
            Box(modifier = Modifier.padding(horizontal = CouliSpacing.space1)) {
                Text(
                    text =
                        ctx.texts.format(
                            "withdraw_records.month",
                            mapOf("year" to section.year.toString(), "month" to section.month.toString()),
                        ),
                    style = CouliTypography.caption,
                    color = CouliColors.textSecondary,
                    modifier = Modifier.semantics { heading() },
                )
            }
        }
        recordBlocks(section.items, ctx.state.expanded).forEach { block ->
            val first = block.first()
            if (first.withdrawalId in ctx.state.expanded) {
                ExpandedRecordCard(RecordRowView(first, ctx), ctx)
            } else {
                CollapsedRecordCard(block, ctx)
            }
        }
    }
}

/** 月卡：白卡、左右内边距 16，行间 1 分隔线（W20）。 */
@Composable
private fun CollapsedRecordCard(
    items: List<Withdrawal>,
    ctx: RecordRenderContext,
) {
    Column(modifier = Modifier.fillMaxWidth().couliCard()) {
        items.forEachIndexed { index, item ->
            if (index > 0) CouliDivider(modifier = Modifier.padding(horizontal = CouliSpacing.space4))
            CollapsedRecordRow(RecordRowView(item, ctx), ctx)
        }
    }
}

/**
 * 收起行（W21 / W22）：「提现 ¥x」17/600 等宽 + 状态胶囊；时间 · 账号 12 次文字；只画副文案首句（14 主文字）；右侧 20 箭头。
 * 整行一个目标：展开 / 收起（读屏名「展开详情」），读屏描述含完整副文案。
 */
@Composable
private fun CollapsedRecordRow(
    view: RecordRowView,
    ctx: RecordRenderContext,
) {
    val id = view.item.withdrawalId
    val version = view.version
    ForgetRecordOnDispose(ctx.keeper, id)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .trackRecord(ctx.keeper, id)
                .testTag(WalletTags.RECORD_PREFIX + id)
                .clickable(role = Role.Button, onClickLabel = view.toggleLabel) { ctx.controller.toggle(version, id) }
                .semantics(mergeDescendants = true) {
                    stateDescription = view.toggleLabel
                    contentDescription = view.description
                }.padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
            EndWrapRow(
                modifier = Modifier.fillMaxWidth(),
                start = { Text(text = view.title, style = CouliTextStyles.bodySemibold.tabular(), color = CouliColors.textPrimary) },
                end = { RecordStatusPill(view) },
            )
            Text(text = view.channel, style = CouliTextStyles.captionAmount, color = CouliColors.textSecondary)
            recordVisibleSubLines(view.lines, expanded = false).forEach {
                Text(text = it, style = CouliTypography.footnote.tabular(), color = CouliColors.textPrimary)
            }
        }
        RecordArrow(id, expanded = false)
    }
}

/** 状态胶囊（W21）：色调与图标由 [withdrawalStatusTone] / [withdrawalStatusIcon] 决定，文字取字典。 */
@Composable
internal fun RecordStatusPill(view: RecordRowView) {
    val status = view.shown.status
    CouliTag(text = view.status, tone = withdrawalStatusTone(status), icon = withdrawalStatusIcon(status))
}

/** 展开箭头（W22）：20、次文字色；展开时翻转朝上。装饰图标，只挂测试标签、不单独播报。 */
@Composable
internal fun RecordArrow(
    id: String,
    expanded: Boolean,
) {
    Icon(
        imageVector = CouliIcons.chevronDown,
        contentDescription = null,
        tint = CouliColors.textSecondary,
        modifier =
            Modifier
                .size(CouliSizes.iconMedium)
                .rotate(if (expanded) FLIPPED else 0f)
                .testTag(RECORD_ARROW_PREFIX + id),
    )
}

/**
 * 记录骨架（W35）：白卡内 [rows] 行，每行与收起行同结构——金额块与状态块、时间账号块、副文案块；行高 98、左右内边距 16，
 * 行间分隔线画在行底（不占高度）。整卡读屏「加载中」。
 */
@Composable
internal fun RecordSkeletonCard(
    rows: Int,
    tag: String,
    description: String,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .couliCard()
                .semantics { contentDescription = description }
                .testTag(tag),
    ) {
        val line = CouliColors.borderSubtle
        repeat(rows) { row ->
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(SKELETON_ROW_HEIGHT.dp)
                        .drawBehind {
                            if (row < rows - 1) {
                                val y = size.height - CouliSizes.hairline.toPx() / 2
                                val inset = CouliSpacing.space4.toPx()
                                drawLine(line, Offset(inset, y), Offset(size.width - inset, y), CouliSizes.hairline.toPx())
                            }
                        }.padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space3),
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Skeleton(Modifier.size(SKELETON_AMOUNT_WIDTH.dp, SKELETON_TALL.dp))
                    Spacer(Modifier.weight(1f))
                    Skeleton(Modifier.size(SKELETON_STATUS_WIDTH.dp, SKELETON_TALL.dp))
                }
                Spacer(Modifier.height(CouliSpacing.space2))
                Skeleton(Modifier.size(SKELETON_CHANNEL_WIDTH.dp, SKELETON_SHORT.dp))
                Spacer(Modifier.height(CouliSpacing.space2))
                Skeleton(Modifier.size(SKELETON_HINT_WIDTH.dp, SKELETON_SHORT.dp))
            }
        }
    }
}

/** 测试标签：展开箭头（`record_arrow_<id>`）、首次加载骨架、加载更多骨架（测试交回 §2 约定）。 */
internal const val RECORD_ARROW_PREFIX = "record_arrow_"
internal const val RECORDS_LOADING_TAG = "records_loading"
internal const val RECORDS_LOADING_MORE_TAG = "records_loading_more"

private const val FLIPPED = 180f

// 骨架尺寸（dp）：行高 98 = 12 + 22 + 8 + 18 + 8 + 18 + 12；无画板精确尺寸，按收起行结构推导
private const val SKELETON_ROW_HEIGHT = 98
private const val SKELETON_TALL = 22
private const val SKELETON_SHORT = 18
private const val SKELETON_AMOUNT_WIDTH = 92
private const val SKELETON_STATUS_WIDTH = 70
private const val SKELETON_CHANNEL_WIDTH = 200
private const val SKELETON_HINT_WIDTH = 240
