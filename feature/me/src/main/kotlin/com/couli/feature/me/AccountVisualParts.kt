// 账号注销页一族的私有视图件（APP-13v-a，G-D03）：只管外观；回调、显隐、文案与标记全部由页面传入，这里不读执行器、依赖与环境，不起协程、不取时钟。
@file:Suppress("TooManyFunctions")

package com.couli.feature.me

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonSize
import com.couli.core.designsystem.CouliButtonVariant
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliDialog
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliLoading
import com.couli.core.designsystem.CouliRadius
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTextStyles.tabular
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.KeyValueRow
import com.couli.core.designsystem.LoadingSize
import com.couli.core.designsystem.NumberLine
import com.couli.core.designsystem.WholeNumberText
import com.couli.core.designsystem.couliCard
import com.couli.core.designsystem.couliFocusRing
import com.couli.core.designsystem.focusRingVisible

/** 本组新增标记（不改 AccountPorts.kt 的 AccountTags，D39）。 */
internal object AccountVisualTags {
    /** 待确认卡的在途状态行（转圈 +「正在处理，请稍候」，G-D13）。 */
    const val PENDING_BUSY = "account_pending_busy"
}

/** 勾选圆 18、描边 1.5、白勾 12（画板圆形勾选，同登录协议勾选行）。 */
private val CHECK_CIRCLE = 18.dp
private val CHECK_STROKE = 1.5.dp
private val CHECK_MARK = 12.dp

/** 须知圆点 6，与 14 号首行（行高 21sp，随字号放大）竖直居中。 */
private val BULLET_DOT = 6.dp
private val BULLET_LINE = 21.sp

/** 提示条色调：警告（status.warning 底 + 三角）/ 中性（muted 底 + info）。 */
internal enum class NoticeTone { WARNING, MUTED }

/**
 * 提示条（注销画板页首 / 冷静期只读说明）：通栏、圆角 12、内边距 12；20 图标 + 8 + 14 文字（警告字色或主文字）。
 * [tag] 与 [live] 挂在文字节点本身（读屏只播这一句；图标不读）。[link] 给了时放在文字下一行（条内链接，由页面传入回调）。
 */
@Composable
internal fun NoticeStrip(
    text: String,
    tone: NoticeTone,
    tag: String = "",
    live: LiveRegionMode? = null,
    link: (@Composable () -> Unit)? = null,
) {
    val warning = tone == NoticeTone.WARNING
    // 判据 1：提示句里的金额（如「账户有待扣回金额 {amount}」）经共用整段数字件；放不下时图标让行，句子先独占整行
    NumberLine(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    if (warning) CouliColors.statusWarningBackground else CouliColors.backgroundMuted,
                    RoundedCornerShape(CouliRadius.button),
                ).padding(CouliSpacing.space3),
        gap = CouliSpacing.space2,
        fill = true,
        centerVertically = false,
        leading = {
            Icon(
                if (warning) CouliIcons.alert else CouliIcons.info,
                contentDescription = null,
                tint = if (warning) CouliColors.statusWarningText else CouliColors.textSecondary,
                modifier = Modifier.size(CouliSizes.iconMedium),
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
            WholeNumberText(
                text = text,
                style = CouliTypography.footnote,
                color = if (warning) CouliColors.statusWarningText else CouliColors.textPrimary,
                textModifier =
                    Modifier
                        .semantics { live?.let { liveRegion = it } }
                        .then(if (tag.isNotEmpty()) Modifier.testTag(tag) else Modifier),
            )
            link?.invoke()
        }
    }
}

/**
 * 文字链接（条内【联系客服】、提示句右侧【去提现】）：14/500 text.link、无下划线（画板）。布局尺寸 = 文字本身（不撑高所在行 / 提示条），
 * 可点区与语义盒向四周扩到至少 48 并居中于文字（命中区与视觉尺寸分开，同 MeUi 的 CompactTextLink 写法）；读屏为按钮，名称为文字。
 */
@Composable
internal fun AccountTextLink(
    text: String,
    tag: String,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val measured = remember { IntArray(2) }
    Box(
        modifier =
            Modifier
                .layout { measurable, constraints ->
                    // 外层：按文字尺寸占位，把内层（≥ 48 的可点盒）居中摆在文字上
                    val hit = CouliSizes.touchTarget.roundToPx()
                    val inner =
                        measurable.measure(
                            Constraints(maxWidth = maxOf(constraints.maxWidth, hit), maxHeight = maxOf(constraints.maxHeight, hit)),
                        )
                    val width = measured[0].coerceIn(constraints.minWidth, constraints.maxWidth)
                    val height = measured[1].coerceIn(constraints.minHeight, constraints.maxHeight)
                    layout(width, height) { inner.place((width - inner.width) / 2, (height - inner.height) / 2) }
                }.couliFocusRing(focusRingVisible(interaction), CouliRadius.small)
                .clickable(interactionSource = interaction, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
                .testTag(tag)
                .layout { measurable, constraints ->
                    // 内层：量出文字尺寸记给外层，自身扩到至少 48 × 48
                    val label = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                    measured[0] = label.width
                    measured[1] = label.height
                    val hit = CouliSizes.touchTarget.roundToPx()
                    val width = maxOf(label.width, hit)
                    val height = maxOf(label.height, hit)
                    layout(width, height) { label.place((width - label.width) / 2, (height - label.height) / 2) }
                },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = CouliTextStyles.footnoteMedium, color = CouliColors.textLink)
    }
}

/** 白卡（内边距 16、纵向 12）：卡内标题 17/600（读屏标题），[tag] 挂在卡的外框上（含内边距）。 */
@Composable
internal fun TitledCard(
    title: String,
    tag: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .couliCard()
                .testTag(tag)
                .padding(CouliSpacing.space4),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        Text(
            text = title,
            style = CouliTextStyles.bodySemibold,
            color = CouliColors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        content()
    }
}

/**
 * 资金两列大数（注销画板资金卡）：两列各占一半、顶对齐；标签 12 在上、金额 28 在下（可提现 700 主文字；预估收益 600 brand.emphasis，
 * 标签前一个 14 装饰时钟）。每列一个读屏节点「标签，金额」，[leftTag] / [rightTag] 挂在列上；金额整段不断、不截断（判据 1，见 [AmountColumnsLayout]）。
 */
@Suppress("LongParameterList")
@Composable
internal fun AmountColumns(
    leftLabel: String,
    leftAmount: String,
    leftTag: String,
    rightLabel: String,
    rightAmount: String,
    rightTag: String,
) {
    AmountColumnsLayout {
        Column(
            modifier = Modifier.semantics(mergeDescendants = true) {}.testTag(leftTag),
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
        ) {
            Text(text = leftLabel, style = CouliTypography.caption, color = CouliColors.textSecondary)
            WholeNumberText(text = leftAmount, style = CouliTextStyles.amountHeading, color = CouliColors.textPrimary)
        }
        Column(
            modifier = Modifier.semantics(mergeDescendants = true) {}.testTag(rightTag),
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
                Icon(
                    CouliIcons.clock,
                    contentDescription = null,
                    tint = CouliColors.brandEmphasis,
                    modifier = Modifier.size(CouliSizes.iconTiny),
                )
                Text(text = rightLabel, style = CouliTypography.caption, color = CouliColors.brandEmphasis)
            }
            WholeNumberText(text = rightAmount, style = CouliTypography.heading.tabular(), color = CouliColors.brandEmphasis)
        }
    }
}

/** 卡内一行「标签 值」两端对齐（待抵扣）：上方 1px border.subtle 分隔；标签 14 次文字、值 17/600 主文字右对齐，[tag] 挂整行。 */
@Composable
internal fun SplitAmountRow(
    label: String,
    value: String,
    tag: String,
) {
    HorizontalDivider(color = CouliColors.borderSubtle)
    KeyValueRow(
        modifier = Modifier.fillMaxWidth().testTag(tag),
        centerVertically = true,
        valueAtEnd = true,
    ) {
        Text(text = label, style = CouliTypography.footnote, color = CouliColors.textSecondary)
        WholeNumberText(text = value, style = CouliTextStyles.amountBody, color = CouliColors.textPrimary, textAlign = TextAlign.End)
    }
}

/** 次文字 14 说明句（卡内）。 */
@Composable
internal fun CardNote(text: String) {
    Text(text = text, style = CouliTypography.footnote, color = CouliColors.textSecondary)
}

/** 提示句 + 右侧文字链接同一行（画板资金卡底行）：句子占剩余宽度可折行，链接与句子首行顶对齐；[link] 为 null 时只有句子。 */
@Composable
internal fun HintWithLink(
    text: String,
    link: (@Composable () -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
        verticalAlignment = Alignment.Top,
    ) {
        Text(text = text, style = CouliTypography.footnote, color = CouliColors.textSecondary, modifier = Modifier.weight(1f))
        link?.invoke()
    }
}

/** 圆点列表卡（注销须知）：卡内标题 + 逐条 14 主文字、条间 8，每条前一个 6 的次文字色圆点（装饰，不读）。 */
@Composable
internal fun BulletListCard(
    title: String,
    lines: List<String>,
    tag: String,
) {
    val dotTop = with(LocalDensity.current) { (BULLET_LINE.toDp() - BULLET_DOT) / 2 }
    TitledCard(title, tag) {
        Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
            lines.forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
                    Box(
                        modifier =
                            Modifier
                                .padding(top = dotTop)
                                .size(BULLET_DOT)
                                .background(CouliColors.textSecondary, CircleShape),
                    )
                    Text(text = line, style = CouliTypography.footnote, color = CouliColors.textPrimary, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * 圆形勾选行（画板圆形 18：未勾 border.control 1.5 描边、勾选 brand.primary 实心 + 白勾 12、禁用 muted 底）：整行可点（命中 ≥48），
 * 读屏读「文字，复选框，状态」。语义与旧勾选行相同：toggleable + Checkbox + stateDescription + 标记挂在可切换节点上。
 */
@Suppress("LongParameterList")
@Composable
internal fun RoundCheckRow(
    text: String,
    checked: Boolean,
    enabled: Boolean,
    stateText: String,
    tag: String,
    onToggle: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = CouliSizes.touchTarget)
                .toggleable(value = checked, enabled = enabled, role = Role.Checkbox) { onToggle() }
                .semantics { stateDescription = stateText }
                .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        val on = checked && enabled
        Box(
            modifier =
                Modifier
                    .size(CHECK_CIRCLE)
                    .background(
                        when {
                            on -> CouliColors.brandPrimary
                            enabled -> CouliColors.backgroundSurface
                            else -> CouliColors.backgroundMuted
                        },
                        CircleShape,
                    ).border(CHECK_STROKE, if (on) CouliColors.brandPrimary else CouliColors.borderControl, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (on) {
                Icon(
                    CouliIcons.check,
                    contentDescription = null,
                    tint = CouliColors.textInverse,
                    modifier = Modifier.size(CHECK_MARK),
                )
            }
        }
        Text(
            text = text,
            style = CouliTypography.footnote,
            color = if (enabled) CouliColors.textPrimary else CouliColors.textSecondary,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 待确认卡（注销待确认 / 在途 / 仍未确认一族画板，照第 2 批钱包待确认卡写法）：白卡 → 警告块（20 三角、标题 14/600 + 说明 14，警告字色，
 * 整块一个 assertive 元素）→ 灰底明细（标签左、值右）→ 两等宽按钮（【放弃】描边、【确认】主）→ 在途状态行（[inFlight] 时：16 转圈 +「正在处理，请稍候」，
 * polite）→ 提示警告块（[notice]，polite）→ 卡底注释 12。按钮可用性只看 [busy]；回调由页面在渲染时捕获未决键与版本后传入。
 */
@Suppress("LongParameterList")
@Composable
internal fun PendingPanel(
    texts: AccountTexts,
    desc: String,
    confirmLabel: String,
    details: List<Pair<String, String>>,
    busy: Boolean,
    inFlight: Boolean,
    notice: String?,
    hint: String,
    onAbandon: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .couliCard()
                .testTag(AccountTags.PENDING_CARD)
                .padding(CouliSpacing.space4),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        WarningBlock(
            modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive },
        ) {
            Text(
                text = texts.text("pending_confirm.title"),
                style = CouliTextStyles.footnoteSemibold,
                color = CouliColors.statusWarningText,
            )
            WholeNumberText(text = desc, style = CouliTypography.footnote, color = CouliColors.statusWarningText)
        }
        DetailBlock(details)
        Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3)) {
            CouliButton(
                text = texts.text("pending_confirm.abandon"),
                onClick = onAbandon,
                enabled = !busy,
                variant = CouliButtonVariant.SECONDARY,
                size = CouliButtonSize.COMPACT,
                textStyle = CouliTextStyles.bodySemibold,
                modifier = Modifier.weight(1f).testTag(AccountTags.PENDING_ABANDON),
            )
            CouliButton(
                text = confirmLabel,
                onClick = onConfirm,
                enabled = !busy,
                size = CouliButtonSize.COMPACT,
                textStyle = CouliTextStyles.bodySemibold,
                modifier = Modifier.weight(1f).testTag(AccountTags.PENDING_CONFIRM),
            )
        }
        if (inFlight) BusyRow(texts.text(PENDING_BUSY_KEY))
        if (notice != null) {
            WarningBlock(modifier = Modifier) {
                WholeNumberText(
                    text = notice,
                    style = CouliTypography.footnote,
                    color = CouliColors.statusWarningText,
                    textModifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(AccountTags.PENDING_NOTICE),
                )
            }
        }
        Text(text = hint, style = CouliTypography.caption, color = CouliColors.textSecondary)
    }
}

/** 在途状态与 40901 共用的文案键（契约 error.40901「正在处理，请稍候」）。 */
private const val PENDING_BUSY_KEY = "error.40901"

/** 警告块：警告浅底、圆角 12、内边距 12；20 三角 + 文字列（行距 2）。 */
@Composable
private fun WarningBlock(
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 判据 1：警告句里的脱敏新号经共用整段数字件；放不下时图标让行，句子先独占整行
    NumberLine(
        modifier =
            modifier
                .fillMaxWidth()
                .background(CouliColors.statusWarningBackground, RoundedCornerShape(CouliRadius.button))
                .padding(CouliSpacing.space3),
        gap = CouliSpacing.space2,
        fill = true,
        centerVertically = false,
        leading = {
            Icon(
                CouliIcons.alert,
                contentDescription = null,
                tint = CouliColors.statusWarningText,
                modifier = Modifier.size(CouliSizes.iconMedium),
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1 / 2), content = content)
    }
}

/** 在途状态行（G-D13）：16 转圈 + 14 次文字，polite（role=status），整行一个读屏元素。 */
@Composable
private fun BusyRow(text: String) {
    Row(
        modifier =
            Modifier
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                .testTag(AccountVisualTags.PENDING_BUSY),
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CouliLoading(description = "", size = LoadingSize.SMALL)
        Text(text = text, style = CouliTypography.footnote, color = CouliColors.textSecondary)
    }
}

/** 灰底明细块（待确认卡、放弃确认框）：muted 底、圆角 12、内边距 12，每行标签左（次文字）值右（主文字、可折行），行距 4。 */
@Composable
private fun DetailBlock(details: List<Pair<String, String>>) {
    if (details.isEmpty()) return
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(CouliColors.backgroundMuted, RoundedCornerShape(CouliRadius.button))
                .padding(CouliSpacing.space3),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
    ) {
        details.forEach { (label, value) ->
            KeyValueRow {
                Text(text = label, style = CouliTypography.footnote, color = CouliColors.textSecondary)
                WholeNumberText(
                    text = value,
                    style = CouliTypography.footnote.tabular(),
                    color = CouliColors.textPrimary,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

/**
 * 放弃上一次提交的二次确认（PendingAbandonConfirm-v2）：标题居中、遮罩 overlay.scrim（G-D18）；正文 → 灰底明细 → 注释 12。
 * 点遮罩不关闭，系统返回 = 【再想想】；两个回调由页面捕获对话框编号与版本后传入。
 */
@Composable
internal fun AbandonConfirmDialog(
    texts: AccountTexts,
    note: String,
    details: List<Pair<String, String>>,
    onOk: () -> Unit,
    onCancel: () -> Unit,
) {
    CouliDialog(
        title = texts.text("pending_confirm.abandon"),
        confirmText = texts.text("pending_confirm.abandon.ok"),
        onConfirm = onOk,
        dismissText = texts.text("pending_confirm.abandon.cancel"),
        onDismiss = onCancel,
        onDismissRequest = onCancel,
        dismissOnClickOutside = false,
        confirmTag = AccountTags.ABANDON_OK,
        dismissTag = AccountTags.ABANDON_CANCEL,
        titleAlign = TextAlign.Center,
        scrim = CouliColors.overlayScrim,
        body = {
            Column(
                verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
                modifier = Modifier.testTag(AccountTags.ABANDON_DIALOG),
            ) {
                Text(
                    text = texts.text("pending_confirm.abandon.confirm"),
                    style = CouliTypography.footnote,
                    color = CouliColors.textPrimary,
                )
                DetailBlock(details)
                Text(text = note, style = CouliTypography.caption, color = CouliColors.textSecondary)
            }
        },
    )
}

/**
 * 冷静期状态卡（注销冷静期画板）：白卡、内边距 16；[badge]（胶囊，可无）在上 → 22/600 标题（读屏标题）→
 * 17 主文字截止句（[deadlineTag]）→ 14 次文字说明。[deadline] 为 null 时不画截止句（can_cancel=false 版式由页面组织）。
 */
@Suppress("LongParameterList")
@Composable
internal fun CoolingStatusCard(
    tag: String,
    badge: (@Composable () -> Unit)?,
    title: String,
    deadline: String?,
    deadlineTag: String,
    desc: String,
    descTag: String = "",
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .couliCard()
                .testTag(tag)
                .padding(CouliSpacing.space4),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        badge?.invoke()
        Text(text = title, style = CouliTypography.title, color = CouliColors.textPrimary, modifier = Modifier.semantics { heading() })
        deadline?.let {
            Text(
                text = it,
                style = CouliTypography.body.tabular(),
                color = CouliColors.textPrimary,
                modifier = Modifier.testTag(deadlineTag),
            )
        }
        Text(
            text = desc,
            style = CouliTypography.footnote,
            color = CouliColors.textSecondary,
            modifier = if (descTag.isNotEmpty()) Modifier.testTag(descTag) else Modifier,
        )
    }
}

/**
 * 资金两列的排法（判据 1，FU-AND-NUM-WRAP）：两个子项（左列、右列）与改前 Row（两列 weight(1f)、间距 12）一样各占一半、顶对齐；任一列的
 * 最小固有宽（金额整段宽）在半宽里放不下时两列改上下排、各占整行（间距 12），金额独占一行仍放不下才由共用整段数字件框内横滑。
 */
@Composable
private fun AmountColumnsLayout(content: @Composable () -> Unit) {
    Layout(content = content, modifier = Modifier.fillMaxWidth()) { columns, constraints ->
        val width = constraints.maxWidth
        val gap = CouliSpacing.space3.roundToPx()
        // 与改前 Row 的 weight 分法一致：奇数余量给第二列
        val first = ((width - gap) / 2).coerceAtLeast(0)
        val second = (width - gap - first).coerceAtLeast(0)
        val halves = listOf(first, second)
        val sideBySide = columns.indices.all { columns[it].minIntrinsicWidth(Constraints.Infinity) <= halves.getOrElse(it) { second } }
        if (sideBySide) {
            val placed = columns.mapIndexed { i, column -> column.measure(Constraints.fixedWidth(halves.getOrElse(i) { second })) }
            layout(width, placed.maxOfOrNull { it.height } ?: 0) {
                placed.forEachIndexed { i, column -> column.place(if (i == 0) 0 else first + gap, 0) }
            }
        } else {
            val placed = columns.map { it.measure(Constraints.fixedWidth(width)) }
            layout(width, placed.sumOf { it.height } + gap * (placed.size - 1).coerceAtLeast(0)) {
                var y = 0
                placed.forEach { column ->
                    column.place(0, y)
                    y += column.height + gap
                }
            }
        }
    }
}
