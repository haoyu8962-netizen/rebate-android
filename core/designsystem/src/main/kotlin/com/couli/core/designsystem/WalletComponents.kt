package com.couli.core.designsystem

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 数字格的一格：上标签（可带钟表图标与问号说明按钮）、下数值（等宽数字），可再带一行说明。
 * [spoken] 是读屏名称（标签与数值合成一项，如「可提现余额，12.34 元」）；[helpLabel] 是问号按钮的读屏名称。
 * 金额隐藏（[AmountGrid] 的 amountHidden）时：[isAmount] 的格数值换成遮罩、读屏读「标签，金额已隐藏」，
 * 说明行换成 [hiddenNote]（不给就不显示说明行，免得说明里的分项金额漏出来）；非金额格（如「最早预计 10 月结算」）不变。
 * 隐藏时说明行默认不单独读（格名称已读「金额已隐藏」）；给了 [hiddenNoteSpoken] 时说明行作为独立一项读它（如「其中已收货 金额已隐藏」）。
 */
@Immutable
data class AmountCell(
    val label: String,
    val value: String,
    val spoken: String,
    val note: String? = null,
    val emphasis: Boolean = false,
    val labelIcon: ImageVector? = null,
    val helpLabel: String? = null,
    val onHelp: (() -> Unit)? = null,
    val isAmount: Boolean = true,
    val hiddenNote: String? = null,
    /** 带类型的数值（[of] 构造时有）：区间按破折号分段排版，强调色只给正金额。 */
    val amount: AmountValue? = null,
    /** 金额隐藏时说明行的独立读屏名称；null（默认）= 隐藏时说明行不读（旧行为）。 */
    val hiddenNoteSpoken: String? = null,
) {
    /** 不可拆分的显示段：区间「¥a」「–¥b」只在破折号处折行；手写文字的格按空格分段。 */
    fun segments(masked: Boolean): List<String> = amount?.takeUnless { masked }?.segments() ?: value.split(' ')

    /**
     * 是否用返利强调色（GUIDE §7.1「无返利 / 返利 ¥0 不用橙」，F-AND-06）：只给 [emphasis] 的格；有 [amount] 时只给正金额，
     * 金额隐藏时按种类（金额、区间仍用，不让「****」的颜色泄露是否为 0），纯文字不用。
     */
    fun usesRebateAccent(amountHidden: Boolean): Boolean {
        val typed = amount
        return emphasis &&
            when {
                typed == null -> true
                typed is AmountValue.Text -> false
                amountHidden -> true
                else -> typed.isPositiveAmount
            }
    }

    companion object {
        /** 按类型构造一格：显示文字与读屏（「预估返，1.35 元到 2.69 元」）由 [value] 生成；[isRebate] 为返利类（强调色）。 */
        @Suppress("LongParameterList")
        fun of(
            label: String,
            value: AmountValue,
            isRebate: Boolean = false,
            note: String? = null,
            labelIcon: ImageVector? = null,
            helpLabel: String? = null,
            onHelp: (() -> Unit)? = null,
            hiddenNote: String? = null,
            hiddenNoteSpoken: String? = null,
        ): AmountCell =
            AmountCell(
                label = label,
                value = value.segments().joinToString(""),
                spoken = label + PriceTerms.SPOKEN_PAUSE + value.spoken(),
                note = note,
                emphasis = isRebate,
                labelIcon = labelIcon,
                helpLabel = helpLabel,
                onHelp = onHelp,
                isAmount = value !is AmountValue.Text,
                hiddenNote = hiddenNote,
                amount = value,
                hiddenNoteSpoken = hiddenNoteSpoken,
            )
    }
}

/** 数字格的数值类型（F-AND-05「AmountGrid 区间金额类型」，与 iOS AmountValue 同义）。 */
@Immutable
sealed interface AmountValue {
    /** 单一金额（分）。 */
    data class Amount(
        val fen: Long,
    ) : AmountValue

    /**
     * 返利区间（订单数字格「预估返」等）：「¥a–¥b」、读「a 元到 b 元」，只在破折号处折行。min = max > 0 按单值；
     * 异常数据（min > max 不排序、端点为负、上限为 0）按 rule-gaps 统一裁定显示「暂无返利」。
     */
    data class Range(
        val minFen: Long,
        val maxFen: Long,
    ) : AmountValue

    /** 非金额文字（如「10 月结算」「暂无返利」）。 */
    data class Text(
        val text: String,
    ) : AmountValue

    /** 不可拆分的显示段。 */
    fun segments(): List<String> =
        when (this) {
            is Amount -> {
                listOf(CouliFormat.money(fen))
            }

            is Range -> {
                when {
                    !validRange() -> listOf(PriceTerms.NO_REBATE)
                    minFen == maxFen -> listOf(CouliFormat.money(minFen))
                    else -> listOf(CouliFormat.money(minFen), PriceTerms.RANGE_DASH + CouliFormat.money(maxFen))
                }
            }

            is Text -> {
                listOf(text)
            }
        }

    fun spoken(): String =
        when (this) {
            is Amount -> {
                CouliFormat.moneySpoken(fen)
            }

            is Range -> {
                when {
                    !validRange() -> PriceTerms.NO_REBATE
                    minFen == maxFen -> CouliFormat.moneySpoken(minFen)
                    else -> CouliFormat.moneySpoken(minFen) + PriceTerms.SPOKEN_TO + CouliFormat.moneySpoken(maxFen)
                }
            }

            is Text -> {
                text
            }
        }

    /** 可显示的正金额：单值 > 0；区间合法且上限 > 0。0、负数、异常区间与纯文字都不是。 */
    val isPositiveAmount: Boolean
        get() =
            when (this) {
                is Amount -> fen > 0
                is Range -> validRange()
                is Text -> false
            }

    private fun Range.validRange(): Boolean = minFen in 0..maxFen && maxFen > 0
}

/** 金额隐藏状态（BR-TEXT-10；adaptation-a11y-spec §9：被隐藏的金额读「金额已隐藏」，文案由调用方从字典给）。 */
@Immutable
data class AmountMask(
    val hidden: Boolean,
    /** 遮罩（如「****」）。 */
    val mask: String,
    /** 读屏用的「金额已隐藏」。 */
    val spoken: String,
) {
    companion object {
        /** 只知道「要隐藏」、调用方没给遮罩时的缺省遮罩（[PriceTerms.AMOUNT_MASK]、[PriceTerms.AMOUNT_HIDDEN_SPOKEN]）。 */
        val DEFAULT_HIDDEN: AmountMask = AmountMask(hidden = true, mask = PriceTerms.AMOUNT_MASK, spoken = PriceTerms.AMOUNT_HIDDEN_SPOKEN)
    }
}

/** 数字格配色：白卡片上（标签灰、数值墨棕，返利类用返利强调色）与狸橙实底上（全白）。 */
enum class AmountGridTone {
    ON_SURFACE,
    ON_SOLID,
}

/** 数值字号：17/600 或 22/600（GUIDE §7.2「数字格」）。 */
enum class AmountValueSize {
    BODY,
    TITLE,
}

/**
 * 两列数字格（GUIDE §7.2）：两列等宽、左对齐；任一列放不下金额的一个不可拆分段时改上下排（第一格在上），
 * 系统字号 200% 一律纵排（adaptation-a11y-spec §2）。[stackOrder]：纵排时各格的先后（按 [cells] 下标；缺省同并排顺序）——订单卡并排时
 * 实付在左、返利在右，纵排时返利在上（AdaptLargeTextOrderDetail，APP-08）。
 * [compactHelpRow]：问号按钮视觉 14、紧跟标签文字（间距 4），48 命中区以图标为中心向外溢出、不撑高标签行（钱包 Wallet 画板标签行 18）；
 * 默认关，标签行仍按旧做法撑到 48 并对齐。
 */
@Composable
fun AmountGrid(
    cells: List<AmountCell>,
    modifier: Modifier = Modifier,
    tone: AmountGridTone = AmountGridTone.ON_SURFACE,
    valueSize: AmountValueSize = AmountValueSize.TITLE,
    mask: AmountMask? = null,
    stackOrder: List<Int>? = null,
    compactHelpRow: Boolean = false,
) {
    val masked = mask?.hidden == true
    val cells = if (masked && mask != null) cells.map { it.masked(mask) } else cells
    val valueStyle = if (valueSize == AmountValueSize.TITLE) CouliTextStyles.amountTitle else CouliTextStyles.amountBody
    val labelStyle = if (tone == AmountGridTone.ON_SOLID) CouliTypography.footnote else CouliTypography.caption
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val gap = CouliSpacing.space3
        val columnWidthPx = with(density) { ((maxWidth - gap) / 2).roundToPx() }
        val widestSegment = widestSegment(cells, masked) { measurer.measure(it, valueStyle).size.width }
        val stacked = density.fontScale >= CouliAlpha.LARGE_FONT_SCALE || widestSegment > columnWidthPx
        if (stacked) {
            val ordered = stackOrder?.mapNotNull { cells.getOrNull(it) }?.takeIf { it.size == cells.size } ?: cells
            Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3)) {
                ordered.forEach {
                    AmountCellView(
                        it,
                        tone,
                        labelStyle,
                        valueStyle,
                        Modifier.fillMaxWidth(),
                        masked = masked && it.isAmount,
                        compactHelp = compactHelpRow,
                    )
                }
            }
        } else {
            // 并排时两列标签行等高（有问号按钮的一列标签行是 48 命中区高），数值对齐；紧凑问号不撑高，无需对齐
            val alignRows = alignedLabelRows(cells, compactHelpRow)
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                cells.forEach {
                    AmountCellView(
                        it,
                        tone,
                        labelStyle,
                        valueStyle,
                        Modifier.weight(1f),
                        alignLabelRow = alignRows,
                        masked =
                            masked && it.isAmount,
                        compactHelp = compactHelpRow,
                    )
                }
            }
        }
    }
}

/** 并排时标签行是否要按 48 对齐：有问号按钮且不是紧凑问号。 */
private fun alignedLabelRows(
    cells: List<AmountCell>,
    compactHelpRow: Boolean,
): Boolean = !compactHelpRow && cells.any { it.onHelp != null && it.helpLabel != null }

/** 所有格里最宽的一个不可拆分段（px）。 */
private fun widestSegment(
    cells: List<AmountCell>,
    masked: Boolean,
    width: (String) -> Int,
): Int = cells.maxOfOrNull { cell -> cell.segments(masked && cell.isAmount).maxOf(width) } ?: 0

/** 隐藏金额后的格：数值换遮罩、读屏不读金额、说明行换成不含金额的版本（或不显示）。 */
private fun AmountCell.masked(mask: AmountMask): AmountCell =
    if (!isAmount) {
        this
    } else {
        copy(value = mask.mask, spoken = label + PriceTerms.SPOKEN_PAUSE + mask.spoken, note = hiddenNote, hiddenNote = null)
    }

@Composable
private fun AmountCellView(
    cell: AmountCell,
    tone: AmountGridTone,
    labelStyle: TextStyle,
    valueStyle: TextStyle,
    modifier: Modifier,
    alignLabelRow: Boolean = false,
    masked: Boolean = false,
    compactHelp: Boolean = false,
) {
    val solid = tone == AmountGridTone.ON_SOLID
    val (labelColor, valueColor) = cellColors(cell, solid, masked)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1 / 2)) {
        Row(
            modifier = if (alignLabelRow) Modifier.heightIn(min = CouliSizes.touchTarget) else Modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (compactHelp) Arrangement.spacedBy(CouliSpacing.space1) else Arrangement.Start,
        ) {
            Column(
                modifier = Modifier.weight(1f, fill = false).clearAndSetSemantics { contentDescription = cell.spoken },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
                    if (cell.labelIcon != null) {
                        Icon(cell.labelIcon, contentDescription = null, tint = labelColor, modifier = Modifier.size(CouliSizes.iconTiny))
                    }
                    Text(text = cell.label, style = labelStyle, color = labelColor)
                }
            }
            if (cell.onHelp != null && cell.helpLabel != null) {
                CouliIconButton(
                    icon = CouliIcons.help,
                    label = cell.helpLabel,
                    onClick = cell.onHelp,
                    tint = labelColor,
                    iconSize = if (compactHelp) CouliSizes.iconTiny else CouliSizes.iconSmall,
                    modifier =
                        if (compactHelp) {
                            // 行内只占 14 × 14；按钮仍按 48 × 48 量、以图标为中心摆放（命中区 ≥48，不撑高标签行）
                            Modifier.size(CouliSizes.iconTiny).wrapContentSize(unbounded = true)
                        } else {
                            Modifier.offset(x = -CouliSpacing.space3)
                        },
                )
            }
        }
        AmountCellValue(cell = cell, masked = masked, style = valueStyle, color = valueColor)
        if (cell.note != null) {
            AmountNote(note = cell.note, solid = solid, masked = masked, hiddenSpoken = cell.hiddenNoteSpoken)
        }
    }
}

/** 标签色与数值色：实底全白；返利强调色只给正金额（[AmountCell.usesRebateAccent]）。 */
@Composable
private fun cellColors(
    cell: AmountCell,
    solid: Boolean,
    masked: Boolean,
): Pair<Color, Color> =
    when {
        solid -> CouliColors.textInverse to CouliColors.textInverse
        cell.usesRebateAccent(amountHidden = masked) -> CouliColors.rebateText to CouliColors.rebateText
        else -> CouliColors.textSecondary to CouliColors.priceText
    }

/** 数值：区间两段只在破折号处折行，每段整块单行（F-AND-05）；其余格整串一个 Text。读屏由格名称读。 */
@Composable
private fun AmountCellValue(
    cell: AmountCell,
    masked: Boolean,
    style: TextStyle,
    color: Color,
) {
    val segments = cell.segments(masked)
    if (cell.amount is AmountValue.Range && segments.size > 1) {
        FlowRow(modifier = Modifier.clearAndSetSemantics { }) {
            segments.forEach { AmountText(text = it, style = style, color = color) }
        }
    } else {
        Text(text = cell.value, style = style, color = color, modifier = Modifier.clearAndSetSemantics { })
    }
}

/**
 * 数字格的说明行；隐藏金额时说明行里是遮罩：默认读屏不读（格的名称已读「金额已隐藏」），
 * 给了 [hiddenSpoken] 时读它（独立一项，不读遮罩字符）。
 */
@Composable
private fun AmountNote(
    note: String,
    solid: Boolean,
    masked: Boolean,
    hiddenSpoken: String? = null,
) {
    Text(
        text = note,
        modifier =
            when {
                !masked -> Modifier
                hiddenSpoken != null -> Modifier.clearAndSetSemantics { contentDescription = hiddenSpoken }
                else -> Modifier.clearAndSetSemantics { }
            },
        style = if (solid) CouliTypography.footnote.copy(fontFeatureSettings = CouliAlpha.TABULAR_NUMS) else CouliTextStyles.captionAmount,
        color = if (solid) CouliColors.textInverse else CouliColors.textSecondary,
    )
}

/** 钱包主卡的文字与回调（文案由调用方从字典给）。 */
@Immutable
data class WalletHeroTexts(
    val label: String,
    val amount: String,
    val amountSpoken: String,
    val withdraw: String,
    val records: String?,
    val hideLabel: String,
    val showLabel: String,
    /** 金额隐藏时的读屏名称（「可提现余额，金额已隐藏」）；不给时只读 [label]。 */
    val hiddenSpoken: String? = null,
)

/**
 * 可提现余额主卡（GUIDE §7.2，03 §10.2 WalletHeroCard）：狸橙实底、圆角 16、内边距 20；第一行「可提现余额 + 眼睛」与
 * 「提现记录 ›」（放不下整块换行）；白色大字金额 34/700；白底【提现】。卡内白字不小于 14。金额隐藏时显示 [hiddenMask]。
 */
@Composable
fun WalletHeroCard(
    texts: WalletHeroTexts,
    onWithdraw: () -> Unit,
    modifier: Modifier = Modifier,
    amountHidden: Boolean = false,
    hiddenMask: String = "",
    onToggleHidden: (() -> Unit)? = null,
    onRecords: (() -> Unit)? = null,
    /** 【提现】可点（APP-09 G11：withdraw_enabled=false、待抵扣、风控暂停、断网缓存时置灰）。 */
    withdrawEnabled: Boolean = true,
    /** 【提现】的测试标记（APP-09）。 */
    withdrawModifier: Modifier = Modifier,
    /** 卡内各行纵向间距：默认 12（「我的」页沿用）；钱包页按 Wallet 画板给 16（APP-09v W2）。 */
    contentGap: Dp = CouliSpacing.space3,
    /**
     * 首行紧凑（钱包 Wallet 画板：首行视觉高 = 文字行高）：眼睛按钮只占 20 × 20、48 命中区以图标为中心向外溢出；「提现记录 ›」不再撑到 48，
     * 命中区靠系统最小触控扩展。默认关（「我的」页首行仍按 48 排）。
     */
    compactHeader: Boolean = false,
    bottom: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxWidth().couliCard(background = CouliColors.rebateSolidBackground).padding(CouliSpacing.space5),
        verticalArrangement = Arrangement.spacedBy(contentGap),
    ) {
        WalletHeroHeader(
            texts = texts,
            amountHidden = amountHidden,
            onToggleHidden = onToggleHidden,
            onRecords = onRecords,
            compactHeader = compactHeader,
        )
        EndWrapRow(
            horizontalGap = CouliSpacing.space3,
            verticalGap = CouliSpacing.space3,
            start = {
                Text(
                    text = if (amountHidden) hiddenMask else texts.amount,
                    style = CouliTextStyles.amountDisplay,
                    color = CouliColors.rebateSolidText,
                    modifier =
                        Modifier.semantics {
                            contentDescription =
                                if (amountHidden) texts.hiddenSpoken ?: texts.label else texts.amountSpoken
                        },
                )
            },
            end = {
                CouliButton(
                    text = texts.withdraw,
                    onClick = onWithdraw,
                    variant = CouliButtonVariant.ON_SOLID,
                    enabled = withdrawEnabled,
                    modifier = withdrawModifier.heightIn(min = CouliSizes.touchTarget),
                )
            },
        )
        bottom()
    }
}

/** 主卡首行：「可提现余额 + 眼睛」与「提现记录 ›」（放不下整块换行）；[compactHeader] 见 [WalletHeroCard]。 */
@Composable
private fun WalletHeroHeader(
    texts: WalletHeroTexts,
    amountHidden: Boolean,
    onToggleHidden: (() -> Unit)?,
    onRecords: (() -> Unit)?,
    compactHeader: Boolean,
) {
    EndWrapRow(
        // 紧凑首行：眼睛 48 命中区以 20 图标为中心各溢出 14；同行时横向间距 16 > 14，换行时两块中线至少相距 48 + 4，
        // 眼睛与「提现记录」两个命中区都不重叠（APP-09v r1 S2-1）。非紧凑沿用旧间距。
        horizontalGap = if (compactHeader) CouliSpacing.space4 else CouliSpacing.space3,
        wrappedCenterGap = if (compactHeader) CouliSizes.touchTarget + CouliSpacing.space1 else 0.dp,
        start = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (compactHeader) Arrangement.spacedBy(CouliSpacing.space1) else Arrangement.Start,
            ) {
                Text(text = texts.label, style = CouliTextStyles.footnoteMedium, color = CouliColors.rebateSolidText)
                if (onToggleHidden != null) {
                    CouliIconButton(
                        icon = if (amountHidden) CouliIcons.eyeOff else CouliIcons.eye,
                        label = if (amountHidden) texts.showLabel else texts.hideLabel,
                        onClick = onToggleHidden,
                        tint = CouliColors.rebateSolidText,
                        iconSize = if (compactHeader) CouliSizes.iconMedium else CouliSizes.iconLarge,
                        modifier =
                            if (compactHeader) {
                                // 行内只占 20 × 20；按钮仍按 48 × 48 量、以图标为中心摆放（命中区 ≥48，不撑高首行）
                                Modifier.size(CouliSizes.iconMedium).wrapContentSize(unbounded = true)
                            } else {
                                Modifier
                            },
                    )
                }
            }
        },
        end = {
            if (texts.records != null && onRecords != null) {
                val interaction = remember { MutableInteractionSource() }
                Row(
                    modifier =
                        Modifier
                            .then(if (compactHeader) Modifier else Modifier.heightIn(min = CouliSizes.touchTarget))
                            .couliFocusRing(focusRingVisible(interaction), CouliRadius.small)
                            .clip(RoundedCornerShape(CouliRadius.small))
                            .clickable(
                                interactionSource = interaction,
                                indication = LocalIndication.current,
                                role = Role.Button,
                                onClick = onRecords,
                            ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = texts.records, style = CouliTextStyles.footnoteMedium, color = CouliColors.rebateSolidText)
                    Icon(
                        CouliIcons.chevron,
                        contentDescription = null,
                        tint = CouliColors.rebateSolidText,
                        modifier = Modifier.size(CouliSizes.iconSmall),
                    )
                }
            }
        },
    )
}

/** 宫格入口的一格。[modifier] 挂在整格可点节点上（如测试标记）；放在 [onClick] 之前，保留尾随 lambda 写法。 */
@Immutable
data class EntryItem(
    val icon: ImageVector,
    val label: String,
    val modifier: Modifier = Modifier,
    val onClick: () -> Unit,
)

/**
 * 宫格入口（GUIDE §7.2）：白卡片内 4 列，图标 24（墨棕线性）+ 14 字，格高 ≥ 72，整格是命中区；分组标题 17/600。
 * 系统字号放大后一格放不下文字时改 3 列、2 列（不缩字号、不截断）。[topPadding]：卡片上内边距，默认 16；钱包页按画板给 8。
 */
@Composable
fun EntryGrid(
    items: List<EntryItem>,
    modifier: Modifier = Modifier,
    title: String? = null,
    columns: Int = DEFAULT_COLUMNS,
    topPadding: Dp = CouliSpacing.space4,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .couliCard()
                .padding(start = CouliSpacing.space2, end = CouliSpacing.space2, top = topPadding, bottom = CouliSpacing.space2),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
    ) {
        if (title != null) {
            Text(
                text = title,
                style = CouliTextStyles.bodySemibold,
                color = CouliColors.textPrimary,
                modifier = Modifier.padding(horizontal = CouliSpacing.space2).semantics { heading() },
            )
        }
        BoxWithConstraints {
            val widest = items.maxOfOrNull { measurer.measure(it.label, CouliTypography.footnote).size.width } ?: 0
            val padding = with(density) { (CouliSpacing.space1).roundToPx() }
            var count = columns
            while (count > MIN_COLUMNS && with(density) { (maxWidth / count).roundToPx() } < widest + padding) count--
            Column {
                items.chunked(count).forEach { row ->
                    Row {
                        row.forEach { item -> EntryCell(item, Modifier.weight(1f)) }
                        repeat(count - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryCell(
    item: EntryItem,
    modifier: Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier =
            modifier
                .then(item.modifier)
                .heightIn(min = CouliSizes.gridCellMinHeight)
                .couliFocusRing(focusRingVisible(interaction), CouliRadius.small)
                .clip(RoundedCornerShape(CouliRadius.small))
                .clickable(
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                    role = Role.Button,
                    onClick = item.onClick,
                ).padding(vertical = CouliSpacing.space2, horizontal = CouliSpacing.space1 / 2),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1, Alignment.CenterVertically),
    ) {
        Icon(item.icon, contentDescription = null, tint = CouliColors.textPrimary, modifier = Modifier.size(CouliSizes.iconLarge))
        Text(text = item.label, style = CouliTypography.footnote, color = CouliColors.textPrimary, textAlign = TextAlign.Center)
    }
}

/**
 * 小号带图标的说明条（钱包「其中 ¥4.56 暂缓入账」等，灰底圆角 12）。[spoken] 给了就作为读屏名称代替可见文字
 * （金额隐藏时可见文字是遮罩，读屏读调用方给的「…金额已隐藏」）。
 */
@Composable
fun InfoStrip(
    text: String,
    modifier: Modifier = Modifier,
    spoken: String? = null,
    helpLabel: String? = null,
    onHelp: (() -> Unit)? = null,
    background: Color = CouliColors.backgroundMuted,
    /**
     * 问号紧凑（钱包 Wallet 画板「其中 ¥x 暂缓入账」：条高 = 文字行高 + 上下 8，问号 14 紧跟文字、间距 4）：48 命中区以图标为中心向条外溢出
     * （条不裁剪，溢出落在外层卡片内边距 / 块间距里）。默认关，仍按旧做法放 48 的问号按钮。
     */
    compactHelp: Boolean = false,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .then(
                    if (compactHelp) {
                        Modifier.background(background, RoundedCornerShape(CouliRadius.button)).padding(end = CouliSpacing.space3)
                    } else {
                        Modifier.clip(RoundedCornerShape(CouliRadius.button)).background(background)
                    },
                ).padding(start = CouliSpacing.space3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (compactHelp) Arrangement.spacedBy(CouliSpacing.space1) else Arrangement.Start,
    ) {
        Text(
            text = text,
            style = CouliTextStyles.captionAmount,
            color = CouliColors.textSecondary,
            modifier =
                Modifier
                    .weight(1f, fill = false)
                    .padding(vertical = CouliSpacing.space2)
                    .then(if (spoken != null) Modifier.clearAndSetSemantics { contentDescription = spoken } else Modifier),
        )
        if (helpLabel != null && onHelp != null) {
            CouliIconButton(
                icon = CouliIcons.help,
                label = helpLabel,
                onClick = onHelp,
                tint = CouliColors.textSecondary,
                iconSize = if (compactHelp) CouliSizes.iconTiny else CouliSizes.iconSmall,
                modifier = if (compactHelp) Modifier.size(CouliSizes.iconTiny).wrapContentSize(unbounded = true) else Modifier,
            )
        }
    }
}

private const val DEFAULT_COLUMNS = 4
private const val MIN_COLUMNS = 2
