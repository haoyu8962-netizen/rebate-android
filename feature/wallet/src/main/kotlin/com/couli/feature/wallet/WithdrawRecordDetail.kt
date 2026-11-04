package com.couli.feature.wallet

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonSize
import com.couli.core.designsystem.CouliButtonVariant
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliDivider
import com.couli.core.designsystem.CouliFormat
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliRadius
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTextStyles.tabular
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.EndWrapRow
import com.couli.core.designsystem.PriceTerms
import com.couli.core.designsystem.couliCard
import com.couli.core.wallet.TradeNoRow
import com.couli.core.wallet.WalletTexts
import com.couli.core.wallet.Withdrawal
import com.couli.core.wallet.WithdrawalText

/**
 * 展开项单独成卡（W23–W29，G-W08）：描边白卡、内边距 16、块间 12。
 * 卡头（目标：收起）→ 状态块 →【修改收款账号】（只账号类失败）→ 分隔线 → 明细 →（已到账且有流水号）分隔线 + 交易流水号 →（可联系客服）分隔线 + 客服行。
 * 读屏顺序同上；回调只捕获渲染时的 version / id（共性第 25、63 条），去向与次数不变。
 */
@Composable
internal fun ExpandedRecordCard(
    view: RecordRowView,
    ctx: RecordRenderContext,
) {
    val id = view.item.withdrawalId
    val version = view.version
    val texts = ctx.texts
    val shape = RoundedCornerShape(CouliRadius.card)
    ForgetRecordOnDispose(ctx.keeper, id)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .trackRecord(ctx.keeper, id)
                .testTag(WalletTags.RECORD_PREFIX + id)
                .couliCard(shape = shape)
                .border(CouliSizes.hairline, CouliColors.borderSubtle, shape)
                .padding(CouliSpacing.space4),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        ExpandedHeader(view, ctx)
        val lines = recordStatusLines(view.lines, texts.text(JOIN_KEY))
        if (lines.isNotEmpty()) StatusBlock(lines)
        if (view.display.showChangeAccount) {
            CouliButton(
                text = texts.text("withdraw_detail.change_account"),
                onClick = { ctx.controller.changeAccount(version, id) },
                variant = CouliButtonVariant.SECONDARY,
                size = CouliButtonSize.COMPACT,
                textStyle = CouliTextStyles.footnoteMedium,
                modifier = Modifier.fillMaxWidth().testTag(WalletTags.RECORD_CHANGE_ACCOUNT),
            )
        }
        CouliDivider()
        DetailRows(view.shown, view.display.netKey, texts, Modifier.testTag(WalletTags.RECORD_DETAIL_PREFIX + id))
        val tradeNo = ctx.state.details[id]?.let { TradeNoRow.copyText(it.status, it.channelOrderId) }
        if (tradeNo != null) {
            CouliDivider()
            TradeNumber(tradeNo, texts) { ctx.controller.copy(version, id) }
        }
        view.serviceHintKey?.let { key ->
            CouliDivider()
            ServiceRow(texts.text(key), texts, ctx.controller::contact)
        }
    }
}

/**
 * 卡头（W23）：「申请金额」12 次文字 + 金额 28/700 等宽，右侧状态胶囊与朝上箭头；整块一个目标（收起）。
 * 读屏描述只含金额、状态、时间与账号（评审 r1 S2-2）：副文案由状态块读一次，不在卡头重复。
 */
@Composable
private fun ExpandedHeader(
    view: RecordRowView,
    ctx: RecordRenderContext,
) {
    val id = view.item.withdrawalId
    val version = view.version
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = CouliSizes.visualTarget)
                .clickable(role = Role.Button, onClickLabel = view.toggleLabel) { ctx.controller.toggle(version, id) }
                .semantics(mergeDescendants = true) {
                    stateDescription = view.toggleLabel
                    contentDescription = view.headerDescription
                },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        EndWrapRow(
            modifier = Modifier.weight(1f),
            start = {
                Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
                    Text(
                        text = ctx.texts.text("withdraw_detail.amount"),
                        style = CouliTypography.caption,
                        color = CouliColors.textSecondary,
                    )
                    Text(text = view.amount, style = AMOUNT_HEADER, color = CouliColors.textPrimary)
                }
            },
            end = { RecordStatusPill(view) },
        )
        RecordArrow(id, expanded = true)
    }
}

/** 状态块（W24）：主句 17 主文字；分号后的半句 14 次文字；附句 12 次文字；句间 4。 */
@Composable
private fun StatusBlock(lines: List<Pair<String, RecordLineLevel>>) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
        lines.forEach { (text, level) ->
            when (level) {
                RecordLineLevel.LEAD -> Text(text = text, style = CouliTypography.body.tabular(), color = CouliColors.textPrimary)
                RecordLineLevel.FOLLOW -> Text(text = text, style = CouliTypography.footnote.tabular(), color = CouliColors.textSecondary)
                RecordLineLevel.NOTE -> Text(text = text, style = CouliTextStyles.captionAmount, color = CouliColors.textSecondary)
            }
        }
    }
}

/**
 * 明细（W26，BR-TEXT-11）：申请金额、手续费、预计 / 实际到账（按状态）、代扣个税（tax_fen>0）、收款账号、申请时间。
 * 标签不伸缩 14 次文字；值右对齐等宽、可折行；行距 8。每行一个读屏元素（金额读「12.34 元」，脱敏号读「星号」）。
 */
@Composable
private fun DetailRows(
    item: Withdrawal,
    netKey: String?,
    texts: WalletTexts,
    modifier: Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
        detailRowValues(item, netKey, texts).forEach { (label, value, spoken) ->
            Row(
                modifier =
                    Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                        contentDescription =
                            label + PriceTerms.SPOKEN_PAUSE + spoken
                    },
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
            ) {
                Text(text = label, style = DETAIL_TEXT, color = CouliColors.textSecondary)
                Text(
                    text = value,
                    style = DETAIL_TEXT.tabular(),
                    color = CouliColors.textPrimary,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** (标签, 显示值, 读屏值)；取值与改版前同一套（金额取服务端原值，不补 0、不推算）。 */
private fun detailRowValues(
    item: Withdrawal,
    netKey: String?,
    texts: WalletTexts,
): List<Triple<String, String, String>> {
    fun money(
        label: String,
        fen: Long,
    ) = Triple(texts.text(label), CouliFormat.money(fen), CouliFormat.moneySpoken(fen))
    return buildList {
        add(money("withdraw_detail.amount", item.amountFen))
        if (netKey != null) item.feeFen.takeIf { it != 0L }?.let { add(money("withdraw_detail.fee", it)) }
        if (item.taxFen > 0) add(money("withdraw_detail.tax", item.taxFen))
        if (netKey != null) {
            val label =
                if (netKey ==
                    WithdrawalText.NET_ACTUAL
                ) {
                    "withdraw_detail.net_label.actual"
                } else {
                    "withdraw_detail.net_label.expected"
                }
            add(money(label, item.netFen))
        }
        val account = "${item.payoutChannelName} ${item.maskedAccount}"
        add(Triple(texts.text("withdraw_detail.payout"), account, spokenMask(texts, account)))
        val time = CouliFormat.timeMinute(item.createdAtMillis)
        add(Triple(texts.text("withdraw_detail.created"), time, time))
    }
}

/**
 * 交易流水号（W27）：标签 / 号码两行（14，号码等宽可折行），右侧【复制】（16 图标 + 14/500 链接色），与号码末行底对齐；
 * 复制只走统一剪贴板入口（G27，不变）。
 */
@Composable
private fun TradeNumber(
    tradeNo: String,
    texts: WalletTexts,
    onCopy: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
            Text(text = texts.text("withdraw_detail.trade_no"), style = CouliTypography.footnote, color = CouliColors.textSecondary)
            Text(
                text = tradeNo,
                style = CouliTypography.footnote.tabular(),
                color = CouliColors.textPrimary,
                modifier = Modifier.testTag(WalletTags.RECORD_TRADE_NO),
            )
        }
        CouliButton(
            text = texts.text("withdraw_detail.copy"),
            onClick = onCopy,
            variant = CouliButtonVariant.TEXT,
            size = CouliButtonSize.COMPACT,
            accessibilityLabel = texts.text("withdraw_detail.copy") + texts.text("withdraw_detail.trade_no"),
            leadingIcon = CouliIcons.copy,
            leadingIconSize = CouliSizes.iconSmall,
            textStyle = CouliTextStyles.footnoteMedium,
            modifier = Modifier.hitBeyondVisualRow().testTag(WalletTags.RECORD_COPY),
        )
    }
}

/** 客服行（W29）：左说明 12 次文字，右「联系客服」14/500 链接色；视觉行高 44（画板），命中区 48 溢出行外（评审 r1 S2-3）。 */
@Composable
private fun ServiceRow(
    hint: String,
    texts: WalletTexts,
    onContact: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        Text(text = hint, style = CouliTypography.caption, color = CouliColors.textSecondary, modifier = Modifier.weight(1f))
        CouliButton(
            text = texts.text("withdraw_detail.cs"),
            onClick = onContact,
            variant = CouliButtonVariant.TEXT,
            size = CouliButtonSize.COMPACT,
            textStyle = CouliTextStyles.footnoteMedium,
            modifier = Modifier.hitBeyondVisualRow().testTag(WalletTags.RECORD_CS),
        )
    }
}

/**
 * 明细行文字 14/21：行高不按字体裁切（画板 line-height 21），中文标签与数字值行高一致，行距才是 8（W26；页面私有样式）。
 */
private val DETAIL_TEXT =
    CouliTypography.footnote.copy(
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )

/** 卡头金额 28/700 等宽（W23；页面私有样式，不改 DS 默认）。 */
private val AMOUNT_HEADER = CouliTypography.heading.copy(fontWeight = FontWeight.Bold).tabular()

/**
 * 记录页私有的「视觉尺寸与命中区分开」（评审 r1 S2-3，做法同订单标题行 `hitBeyondTitleRow`）：按钮照常按 ≥48 测量与摆放
 * （命中区、水波纹、Role.Button 不变），向父行只报告「按钮高 −(48−44)」并居中，命中区上下各溢出 2dp。上下相邻的是块间距 12 与分隔线 /
 * 卡片内边距 16，溢出区不与其他目标重叠。字号放大使按钮高于 48 时按同一差值扣，行随字高增长、不截字。不改共用 `CouliButton` 默认。
 */
private fun Modifier.hitBeyondVisualRow(): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minHeight = 0))
        val trim = (CouliSizes.touchTarget - CouliSizes.visualTarget).roundToPx().coerceAtLeast(0)
        val height = (placeable.height - trim).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(placeable.width, height) { placeable.place(0, (height - placeable.height) / 2) }
    }
