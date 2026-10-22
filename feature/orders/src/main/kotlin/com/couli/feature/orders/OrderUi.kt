package com.couli.feature.orders

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import com.couli.core.designsystem.AmountCell
import com.couli.core.designsystem.AmountMask
import com.couli.core.designsystem.AmountValue
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonSize
import com.couli.core.designsystem.CouliButtonVariant
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliFormat
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliRadius
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.EndWrapRow
import com.couli.core.designsystem.PriceTerms
import com.couli.core.orders.AmountCellView
import com.couli.core.orders.AmountCellsView
import com.couli.core.orders.OrderActionButton
import com.couli.core.orders.OrderActionsView
import com.couli.core.orders.OrderAmountCells
import com.couli.core.orders.OrderNumberView
import com.couli.core.orders.OrderTexts
import com.couli.generated.DisplayStatus
import kotlin.math.ceil
import kotlin.math.roundToInt

/** 订单页共用的小组件与金额格的拼装（列表卡、详情共用）。文案一律经 [OrderTexts] 取键。 */
internal object OrderUi {
    /** 订单列表与详情的内容最大宽度（大屏限宽居中，与搜索、详情页同为 600dp，03 §10.4）。 */
    const val MAX_WIDTH = 600

    const val STAR_SPOKEN_KEY = "order_list.star_spoken"
    const val HIDDEN_SPOKEN_KEY = "amount_mask.hidden_spoken"
    const val MASK_KEY = "amount_mask.mask"
    const val MASK_HIDE_KEY = "amount_mask.hide"
    const val MASK_SHOW_KEY = "amount_mask.show"

    /** 金额隐藏状态（BR-TEXT-10；读屏「金额已隐藏」）。 */
    fun mask(
        texts: OrderTexts,
        hidden: Boolean,
    ): AmountMask =
        AmountMask(
            hidden = hidden,
            mask =
                texts.text(MASK_KEY).ifEmpty {
                    OrderAmountCells.MASK
                },
            spoken = texts.text(HIDDEN_SPOKEN_KEY),
        )

    /**
     * 一格 → DesignSystem 的数字格。实付 / 已付定金不受隐藏开关管（isAmount = false，读屏自己拼「实付金额，45.6 元」）；金额为 null 的格只显示
     * 标签、不显示金额（不补 0）；扣回显示「-¥z」；INVALID 的「返利 ¥0」是固定值。
     */
    fun cell(
        texts: OrderTexts,
        view: AmountCellView,
    ): AmountCell {
        val label = if (view.labelKey == OrderAmountCells.DEPOSIT_KEY) depositLabel(texts) else texts.text(view.labelKey)
        val fen = view.fen ?: return AmountCell(label = label, value = "", spoken = label, isAmount = false)
        val value = AmountValue.Amount(if (view.minus) -fen else fen)
        return if (view.maskable) {
            AmountCell.of(label, value, isRebate = view.rebate)
        } else {
            AmountCell(
                label = label,
                value = CouliFormat.money(fen),
                spoken = label + SPOKEN_PAUSE + CouliFormat.moneySpoken(fen),
                isAmount = false,
            )
        }
    }

    /** 「已付定金」（契约键 order_list.deposit_amount「已付定金 {amount}」去掉金额作标签，金额在数值行）。 */
    private fun depositLabel(texts: OrderTexts): String = texts.format(OrderAmountCells.DEPOSIT_KEY, emptyMap()).trim()

    /** 列表卡：并排时实付在左、返利在右；纵排时返利在上（APP-08 共性第 5 条；详情见 [detailCells]）。返回 (格, 纵排顺序)。 */
    fun listCells(
        texts: OrderTexts,
        cells: AmountCellsView,
    ): Pair<List<AmountCell>, List<Int>?> {
        val left = cell(texts, cells.left)
        val right = cells.right?.let { cell(texts, it) } ?: return listOf(left) to null
        return listOf(left, right) to listOf(1, 0)
    }

    /**
     * 详情（G-O07，按画板 OrderDetail* 与 AdaptLargeTextOrderDetail）：并排时返利在左、实付在右；纵排仍返利在上（自然顺序，返回 null）。
     * 只调换两格的左右，单格原样；不改 [listCells]。
     */
    fun detailCells(
        texts: OrderTexts,
        cells: AmountCellsView,
    ): Pair<List<AmountCell>, List<Int>?> {
        val (list, _) = listCells(texts, cells)
        return (if (list.size == 2) list.reversed() else list) to null
    }

    /**
     * 详情按钮分区（G-O06）：按钮集合、顺序、最多 2 个、主次数据与去向都由 [com.couli.core.orders.OrderDetailActions] 定，这里只按 actionRaw 分到
     * 两处——状态固有的【去提现】进底部固定栏，其余（reason.action 与【查看流水】）进状态卡内。返回 (卡内, 底栏)，各自保持原顺序与原对象。
     */
    fun splitActions(view: OrderActionsView): Pair<List<OrderActionButton>, List<OrderActionButton>> =
        view.buttons.partition { it.actionRaw != WITHDRAW }

    private const val WITHDRAW = "withdraw"

    /**
     * 状态胶囊 / 状态标题的图标（不只靠颜色：图标 + 文字；APP-08v O13，按画板）：预估类钟表；INVALID / NO_REBATE / UNKNOWN 用 i；CLAWED_BACK 警告三角；
     * 已结算（含部分扣回）对勾。纯视图映射。
     */
    fun statusIcon(status: DisplayStatus): ImageVector =
        when (status) {
            DisplayStatus.CREDITED, DisplayStatus.CREDITED_PART_CLAWED -> CouliIcons.check
            DisplayStatus.CLAWED_BACK -> CouliIcons.alert
            DisplayStatus.INVALID, DisplayStatus.NO_REBATE, DisplayStatus.UNKNOWN -> CouliIcons.info
            else -> CouliIcons.clock
        }

    /** 已结算类（CREDITED / CREDITED_PART_CLAWED）用松叶绿色调，其余中性。 */
    fun settled(status: DisplayStatus): Boolean = status == DisplayStatus.CREDITED || status == DisplayStatus.CREDITED_PART_CLAWED

    /** 「已结算（部分扣回 {z}）」：z 为扣回金额；隐藏金额时为遮罩，为 null 时用不带金额的 fallback。 */
    fun statusLabel(
        texts: OrderTexts,
        labelKey: String,
        clawbackFen: Long?,
        masked: Boolean,
    ): String {
        val z =
            when {
                clawbackFen == null -> ""
                masked -> texts.text(MASK_KEY).ifEmpty { OrderAmountCells.MASK }
                else -> CouliFormat.money(clawbackFen)
            }
        return texts.format(labelKey, mapOf("z" to z))
    }

    /** 脱敏单号的读法：把「*」读成「星号」（原样显示，不在客户端拼、补或还原）。 */
    fun spokenOrderNo(
        texts: OrderTexts,
        text: String,
    ): String {
        val star = texts.text(STAR_SPOKEN_KEY)
        return if (star.isEmpty()) text else text.replace("*", star)
    }

    private val SPOKEN_PAUSE = PriceTerms.SPOKEN_PAUSE
}

/**
 * 状态胶囊（APP-08v O13）：最小高 22（随字号增高，文字可换行不裁切）、左右 8、圆角满、图标 14 + 12 字；中性为次要底 + 次文字，已结算为
 * `status.success` 底 + 字。图标装饰（读屏只读文字）。
 */
@Composable
internal fun StatusPill(
    status: DisplayStatus,
    label: String,
    modifier: Modifier = Modifier,
) {
    val settled = OrderUi.settled(status)
    val content = if (settled) CouliColors.statusSuccessText else CouliColors.textSecondary
    Row(
        modifier =
            modifier
                .heightIn(min = CouliSizes.tagHeight)
                .background(
                    if (settled) CouliColors.statusSuccessBackground else CouliColors.backgroundMuted,
                    RoundedCornerShape(CouliRadius.pill),
                ).padding(horizontal = CouliSpacing.space2),
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(OrderUi.statusIcon(status), contentDescription = null, tint = content, modifier = Modifier.size(CouliSizes.iconTiny))
        Text(text = label, style = CouliTypography.caption, color = content)
    }
}

/**
 * 订单号一行：自购单「订单号 {完整单号}」+【复制】（独立目标，命中区 48，读屏「复制订单号」）；分享单只有脱敏号，没有复制按钮、不可选中、
 * 长按不出菜单（普通 Text，不包 SelectionContainer），读屏把「*」读成「星号」。坏数据（两个都有或都没有）不显示任何单号。
 */
@Composable
internal fun OrderNumberRow(
    number: OrderNumberView,
    texts: OrderTexts,
    onCopy: (() -> Unit)?,
    textTag: String,
    copyTag: String,
    modifier: Modifier = Modifier,
) {
    val text = number.text ?: return
    val shown = texts.format("order_list.order_no", mapOf("no" to text))
    EndWrapRow(
        modifier = modifier.fillMaxWidth(),
        start = {
            Text(
                text = shown,
                style = CouliTypography.footnote,
                color = CouliColors.textSecondary,
                modifier =
                    Modifier
                        .testTag(textTag)
                        .then(
                            if (number.copyable) {
                                Modifier
                            } else {
                                Modifier.semantics { contentDescription = OrderUi.spokenOrderNo(texts, shown) }
                            },
                        ),
            )
        },
        end = {
            if (number.copyable && onCopy != null) {
                CouliButton(
                    text = texts.text("order_list.copy"),
                    onClick = onCopy,
                    variant = CouliButtonVariant.TEXT,
                    size = CouliButtonSize.COMPACT,
                    accessibilityLabel = texts.text("order_list.copy_label"),
                    modifier = Modifier.testTag(copyTag),
                )
            }
        },
    )
}

/**
 * 列表卡的订单号（APP-08v O10）：「订单号 {no}」12 次文字 + 紧随其后的【复制】（12 强调色；视觉只占文字高，≥48 命中区由最小触控区扩展给出，
 * 不撑高这一行）。单号按整列宽度折行；【复制】不拆行，按流式排在单号末行实际文字之后（间距 8、基线对齐），末行放不下时整体换到下一行
 * 的列起点（评审 r1 S2-2：不停在多行中部）。分享单只有脱敏号，没有【复制】，读屏把「*」读成「星号」（不变）。
 * 详情仍用 [OrderNumberRow]（独立一行、复制靠右）。
 */
@Composable
internal fun OrderNumberInline(
    number: OrderNumberView,
    texts: OrderTexts,
    onCopy: (() -> Unit)?,
    textTag: String,
    copyTag: String,
    modifier: Modifier = Modifier,
) {
    val text = number.text ?: return
    val shown = texts.format("order_list.order_no", mapOf("no" to text))
    val lines = remember { NumberLines() }
    Layout(
        modifier = modifier,
        content = {
            Text(
                text = shown,
                style = CouliTextStyles.captionAmount,
                color = CouliColors.textSecondary,
                onTextLayout = { lines.result = it },
                modifier =
                    Modifier
                        .testTag(textTag)
                        .then(
                            if (number.copyable) {
                                Modifier
                            } else {
                                Modifier.semantics { contentDescription = OrderUi.spokenOrderNo(texts, shown) }
                            },
                        ),
            )
            if (number.copyable && onCopy != null) {
                val label = texts.text("order_list.copy_label")
                Text(
                    text = texts.text("order_list.copy"),
                    style = CouliTypography.caption,
                    color = CouliColors.brandEmphasis,
                    softWrap = false,
                    modifier =
                        Modifier
                            .clickable(role = Role.Button, onClick = onCopy)
                            .semantics { contentDescription = label }
                            .testTag(copyTag),
                )
            }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val numberPlaceable = measurables[0].measure(loose)
        val copyPlaceable = measurables.getOrNull(1)?.measure(loose)
        val gap = CouliSpacing.space2.roundToPx()
        val flow = flowCopy(lines.result, numberPlaceable, copyPlaceable, gap, constraints.maxWidth)
        val copyRight = flow.copyX + (copyPlaceable?.width ?: 0)
        val width = maxOf(numberPlaceable.width, copyRight).coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = maxOf(flow.numberY + numberPlaceable.height, flow.copyY + (copyPlaceable?.height ?: 0))
        layout(width, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            numberPlaceable.placeRelative(0, flow.numberY)
            copyPlaceable?.placeRelative(flow.copyX, flow.copyY)
        }
    }
}

/** 单号末行的排版结果（测量时由 onTextLayout 写入，同一次测量里读取）。 */
private class NumberLines {
    var result: TextLayoutResult? = null
}

private class CopyFlow(
    val numberY: Int,
    val copyX: Int,
    val copyY: Int,
)

/**
 * 【复制】按流式排在单号之后（O10）：末行实际文字末端 + 8 放得下就跟在末行后、与末行基线对齐；放不下就整体换到单号下一行的列起点，
 * 不停在多行单号中部、不被推到行尾。
 */
private fun flowCopy(
    layout: TextLayoutResult?,
    number: Placeable,
    copy: Placeable?,
    gap: Int,
    maxWidth: Int,
): CopyFlow {
    val last = (layout?.lineCount ?: 1) - 1
    val end = layout?.getLineRight(last)?.let { ceil(it).toInt() } ?: number.width
    return when {
        copy == null -> {
            CopyFlow(0, 0, 0)
        }

        // 末行放不下：整体换到单号下一行的列起点
        end + gap + copy.width > maxWidth -> {
            CopyFlow(0, 0, number.height)
        }

        else -> {
            val lineBaseline = layout?.getLineBaseline(last)?.roundToInt() ?: number[FirstBaseline]
            val copyBaseline = copy[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: copy.height
            val offset = lineBaseline - copyBaseline
            if (offset >= 0) CopyFlow(0, end + gap, offset) else CopyFlow(-offset, end + gap, 0)
        }
    }
}
