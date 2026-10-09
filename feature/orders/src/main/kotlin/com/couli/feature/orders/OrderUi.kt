package com.couli.feature.orders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.couli.core.designsystem.AmountCell
import com.couli.core.designsystem.AmountMask
import com.couli.core.designsystem.AmountValue
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonSize
import com.couli.core.designsystem.CouliButtonVariant
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliFormat
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.EndWrapRow
import com.couli.core.designsystem.PriceTerms
import com.couli.core.orders.AmountCellView
import com.couli.core.orders.AmountCellsView
import com.couli.core.orders.OrderAmountCells
import com.couli.core.orders.OrderNumberView
import com.couli.core.orders.OrderTexts
import com.couli.generated.DisplayStatus

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

    /** 列表卡与详情：并排时实付在左、返利在右；纵排时返利在上（AdaptLargeTextOrderDetail，APP-08 共性第 5 条）。返回 (格, 纵排顺序)。 */
    fun listCells(
        texts: OrderTexts,
        cells: AmountCellsView,
    ): Pair<List<AmountCell>, List<Int>?> {
        val left = cell(texts, cells.left)
        val right = cells.right?.let { cell(texts, it) } ?: return listOf(left) to null
        return listOf(left, right) to listOf(1, 0)
    }

    /** 状态行的图标（不只靠颜色：图标 + 文字）。 */
    fun statusIcon(status: DisplayStatus): ImageVector =
        when (status) {
            DisplayStatus.CREDITED, DisplayStatus.CREDITED_PART_CLAWED -> CouliIcons.check
            DisplayStatus.INVALID, DisplayStatus.CLAWED_BACK, DisplayStatus.NO_REBATE -> CouliIcons.alert
            else -> CouliIcons.clock
        }

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

/** 状态行：图标 + 状态文案（+ 预计结算月份），可变长文案按宽度换行。 */
@Composable
internal fun StatusLine(
    status: DisplayStatus,
    label: String,
    extra: String?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            OrderUi.statusIcon(status),
            contentDescription = null,
            tint = CouliColors.textSecondary,
            modifier = Modifier.size(CouliSizes.iconSmall),
        )
        Text(text = label, style = CouliTypography.footnote, color = CouliColors.textPrimary, modifier = Modifier.weight(1f, fill = false))
        if (!extra.isNullOrEmpty()) Text(text = extra, style = CouliTypography.footnote, color = CouliColors.textSecondary)
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
