package com.couli.feature.agent

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.couli.core.agent.AgentCardType
import com.couli.core.agent.AgentCardView
import com.couli.core.agent.AgentController
import com.couli.core.agent.AgentDraftTexts
import com.couli.core.agent.AgentErrorView
import com.couli.core.agent.AgentJson
import com.couli.core.agent.AgentProductView
import com.couli.core.agent.AgentReplyTicket
import com.couli.core.agent.AgentRetryMode
import com.couli.core.agent.AgentSnapshot
import com.couli.core.agent.AgentStatus
import com.couli.core.agent.AgentTap
import com.couli.core.agent.AgentText
import com.couli.core.agent.AgentTextBlock
import com.couli.core.agent.AgentTextBlockKind
import com.couli.core.agent.AgentTool
import com.couli.core.agent.AgentTurnPhase
import com.couli.core.agent.AgentTurnView
import com.couli.core.agent.EarningsShape
import com.couli.core.agent.EarningsView
import com.couli.core.designsystem.AiLabel
import com.couli.core.designsystem.BuyButton
import com.couli.core.designsystem.BuyButtonState
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonSize
import com.couli.core.designsystem.CouliButtonVariant
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliRadius
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTag
import com.couli.core.designsystem.CouliTagTone
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.MappedProductCard
import com.couli.core.designsystem.MatchBadge
import com.couli.core.designsystem.MatchBadgeKind
import com.couli.core.designsystem.ProductCardGrid
import com.couli.core.designsystem.ProductCardList
import com.couli.core.designsystem.ProductCardVariant
import com.couli.core.designsystem.couliCard

/**
 * 一轮对话：用户这一条（右侧气泡）+ assistant 回复（工具状态折叠行、受限文本、卡片、错误区、追问胶囊、AiLabel）。读屏顺序 = 源码顺序 = 时间顺序。
 * 所有按钮的回调只把这一次渲染里取出的票据交给 [controller]（APP-09 第 63 条）。
 */
@Composable
internal fun AgentTurnItem(
    turn: AgentTurnView,
    s: AgentSnapshot,
    controller: AgentController,
    ui: AgentPageUi,
) {
    val texts = ui.texts
    Column(
        modifier = Modifier.fillMaxWidth().testTag(AgentTags.turn(turn.key)),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        // 历史里页首没有用户消息的回复（G06）：不画空的用户气泡
        if (turn.userText.isNotEmpty()) UserBubble(turn)
        // 回复区没有任何内容（如发送即失败）时不放空容器（否则外层 spacedBy 多出一道间距）
        if (hasReplyBody(turn)) ReplyBody(turn, s, controller, ui)
        turn.error?.let { ErrorBlock(turn.key, it, s, controller, ui) }
        if (turn.chips.isNotEmpty()) ChipRow(turn.chips, enabled = s.canCompose, scope = "suggest_${turn.key}", onChip = controller::chip)
        val actions = turn.actions
        if (actions != null) {
            FeedbackBar(turn.key, turn.aiLabel, actions, controller, texts)
        } else {
            turn.aiLabel?.let { AiLabel(text = it, modifier = Modifier.testTag(AgentTags.AI_LABEL)) }
        }
    }
}

private fun hasReplyBody(turn: AgentTurnView): Boolean =
    turn.tools.isNotEmpty() || turn.blocks.isNotEmpty() || turn.cards.isNotEmpty() || turn.streaming || turn.statusText.isNotEmpty() ||
        (turn.phase == AgentTurnPhase.ENDED && turn.status == AgentStatus.CANCELLED)

/** 用户这一条（右侧气泡；历史里的用户原文原样纯文本显示、不可点，G41）。 */
@Composable
private fun UserBubble(turn: AgentTurnView) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(
            text = turn.userText,
            style = CouliTypography.body,
            color = CouliColors.textPrimary,
            modifier =
                Modifier
                    .widthIn(max = USER_BUBBLE_MAX.dp)
                    .background(CouliColors.brandSubtle, RoundedCornerShape(CouliRadius.card))
                    .padding(horizontal = CouliSpacing.space3, vertical = CouliSpacing.space2)
                    .semantics {
                        contentDescription =
                            AgentDraftTexts.format("agent.draft.a11y.user_said", mapOf("text" to turn.userText))
                    },
        )
    }
}

/**
 * assistant 回复（工具状态、受限文本、卡片、状态说明）。长按回复文字打开举报半屏（AgentReport 稿）；读屏另有等价入口——无障碍「更多操作」里的「举报」
 * （G29、G54：不占视觉空间，长按不可达时可用）。只对 Core 给了 [AgentTurnView.actions] 且没举报过的回复提供。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReplyBody(
    turn: AgentTurnView,
    s: AgentSnapshot,
    controller: AgentController,
    ui: AgentPageUi,
) {
    val texts = ui.texts
    val report = turn.actions?.takeIf { !it.reported }?.ticket
    val reportLabel = reportActionLabel(texts)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(AgentTags.reply(turn.key)),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        turn.tools.forEach { ToolRow(it) }
        // 举报的操作宿主（长按 + 读屏「更多操作 → 举报」）挂在可聚焦的节点上（只挂在不可聚焦的外层容器上 TalkBack 拿不到，截图读屏顺序时发现）：
        // 有文字挂在正文上；只有卡片的回复（A18 算有正文）另有一个铺在卡片区下层的宿主（Android R2-06，见下）
        val hostModifier = Modifier.reportHost(turn.key, reportLabel, report?.let { r -> { controller.openReport(r) } })
        // 没有正文时不放空容器（否则外层 spacedBy 多出一道间距）
        if (turn.blocks.isNotEmpty()) {
            Column(modifier = hostModifier, verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
                turn.blocks.forEach { TextBlock(it) }
            }
        }
        if (turn.streaming && turn.blocks.isEmpty() && turn.cards.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
                CircularProgressIndicator(
                    color = CouliColors.textSecondary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(CouliSizes.iconSmall),
                )
                Text(text = texts.text("agent.draft.a11y.thinking"), style = CouliTypography.footnote, color = CouliColors.textSecondary)
            }
        }
        ReplyCards(turn, s, controller, ui, report, reportLabel)
        // 状态说明：正在停止（G26）/ 回复内容过长（limited）/ 不完整（缺号，G36）/ 已停止（历史）；否则停止后显示「已停止生成」
        val status =
            turn.statusText.ifEmpty {
                if (turn.phase == AgentTurnPhase.ENDED && turn.status == AgentStatus.CANCELLED) texts.text("agent.draft.cancelled") else ""
            }
        if (status.isNotEmpty()) {
            Text(
                text = status,
                style = CouliTypography.footnote,
                color = CouliColors.textSecondary,
                modifier = Modifier.testTag(AgentTags.status(turn.key)),
            )
        }
    }
}

/** 回复里的卡片：只有卡片、可举报的回复另带举报宿主（R2-06），其余照常逐张渲染。 */
@Composable
private fun ReplyCards(
    turn: AgentTurnView,
    s: AgentSnapshot,
    controller: AgentController,
    ui: AgentPageUi,
    report: AgentReplyTicket?,
    reportLabel: String,
) {
    if (turn.blocks.isEmpty() && turn.cards.isNotEmpty() && report != null) {
        CardOnlyReportHost(turn, s, controller, ui, reportLabel) { controller.openReport(report) }
    } else {
        turn.cards.forEach { card -> AgentCard(card, s, controller, ui) }
    }
}

/**
 * 只有卡片的回复（R2-06）：卡片区整块长按打开举报（在 Initial 阶段先于卡片按钮判定，短按照常交给卡片）；读屏宿主是铺满卡片区、排在卡片之前的兄弟叶节点
 * （「AI 回复的卡片」+ 长按动作 + 更多操作 → 举报）。卡片与卡片按钮不并进宿主，仍是各自的节点与可点按钮。
 */
@Composable
private fun CardOnlyReportHost(
    turn: AgentTurnView,
    s: AgentSnapshot,
    controller: AgentController,
    ui: AgentPageUi,
    reportLabel: String,
    open: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth().pointerInput(turn.key) { longPressBeforeChildren(open) }) {
        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .testTag(AgentTags.reportHost(turn.key))
                    .semantics {
                        contentDescription = ui.texts.text(CARD_REPLY_KEY)
                        onLongClick(reportLabel) { open().let { true } }
                        customActions = listOf(CustomAccessibilityAction(reportLabel) { open().let { true } })
                    },
        )
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
            turn.cards.forEach { card -> AgentCard(card, s, controller, ui) }
        }
    }
}

/** 正文的举报操作宿主：长按打开举报半屏，读屏「更多操作 → 举报」（G29、G54）；[open] 为 null（不可举报）时只占满宽度。 */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.reportHost(
    key: String,
    label: String,
    open: (() -> Unit)?,
): Modifier =
    if (open == null) {
        fillMaxWidth()
    } else {
        fillMaxWidth()
            .testTag(AgentTags.reportHost(key))
            .combinedClickable(onClickLabel = null, onLongClickLabel = label, onLongClick = open, onClick = {})
            .semantics { customActions = listOf(CustomAccessibilityAction(label) { open().let { true } }) }
    }

/**
 * 卡片区的长按（R2-06）：在 Initial 阶段先于卡片按钮看到按下；按住超过长按时长、没有移出触摸范围 → 打开举报并吃掉这次手势剩下的事件（松手不再当成卡片按钮的点击）。
 * 提前松手或移动的照常交给卡片。
 */
private suspend fun PointerInputScope.longPressBeforeChildren(open: () -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val ended =
            withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                var gone = false
                while (!gone) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                    gone =
                        change == null || !change.pressed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                }
                true
            }
        if (ended == null) {
            open()
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }
}

/** 工具状态（03 §7.2）：进行中带转圈；结束 / 失败折叠为一行，只显示 display_text。 */
@Composable
private fun ToolRow(tool: AgentTool) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
        when {
            tool.running -> {
                CircularProgressIndicator(
                    color = CouliColors.textSecondary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(CouliSizes.iconTiny),
                )
            }

            tool.phase == AgentTool.PHASE_FAILED -> {
                Icon(
                    CouliIcons.alert,
                    contentDescription = null,
                    tint = CouliColors.statusWarningText,
                    modifier = Modifier.size(CouliSizes.iconTiny),
                )
            }

            else -> {
                Icon(
                    CouliIcons.check,
                    contentDescription = null,
                    tint = CouliColors.textSecondary,
                    modifier = Modifier.size(CouliSizes.iconTiny),
                )
            }
        }
        Text(text = tool.displayText, style = CouliTypography.footnote, color = CouliColors.textSecondary)
    }
}

/** 受限文本（03 §7.3，G23）：段落、加粗、无序列表；不渲染链接、图片、HTML，文字一律不可点（Core 已剥离 URL 与口令）。 */
@Composable
private fun TextBlock(block: AgentTextBlock) {
    val text = annotated(block)
    if (block.kind == AgentTextBlockKind.LIST_ITEM) {
        Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
            Text(text = BULLET, style = CouliTypography.body, color = CouliColors.textPrimary, modifier = Modifier.clearAndSetSemantics { })
            Text(text = text, style = CouliTypography.body, color = CouliColors.textPrimary)
        }
    } else {
        Text(text = text, style = CouliTypography.body, color = CouliColors.textPrimary)
    }
}

private fun annotated(block: AgentTextBlock): AnnotatedString =
    buildAnnotatedString {
        block.spans.forEach { span ->
            if (span.bold) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(span.text) } else append(span.text)
        }
    }

/** 错误区（G24、G26）：统一文案（5xxxx 带 trace6）+【重试】/【新对话】/【去搜索】/【登录】【绑定手机号】；按钮受限流与同意闸门控制。 */
@Composable
private fun ErrorBlock(
    key: String,
    error: AgentErrorView,
    s: AgentSnapshot,
    controller: AgentController,
    ui: AgentPageUi,
) {
    val texts = ui.texts
    Column(
        modifier = Modifier.fillMaxWidth().couliCard().padding(CouliSpacing.space3),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        if (error.text.isNotEmpty()) Text(text = error.text, style = CouliTypography.body, color = CouliColors.textPrimary)
        if (error.quotaHint.isNotEmpty()) Text(text = error.quotaHint, style = CouliTypography.footnote, color = CouliColors.textSecondary)
        if (error.searchHint.isNotEmpty()) {
            Text(
                text = error.searchHint,
                style = CouliTypography.footnote,
                color = CouliColors.textSecondary,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
        ) {
            error.retry?.let { retry ->
                // APP-10b G24、鸿蒙 R2-06：断线重载与【刷新】受读取闸门控制，同键重发 / 新消息受发送闸门控制
                val reading = retry.mode == AgentRetryMode.RELOAD || retry.mode == AgentRetryMode.REFRESH
                CouliButton(
                    text = texts.text(if (retry.mode == AgentRetryMode.REFRESH) "agent.draft.refresh" else "agent.draft.retry"),
                    onClick = { controller.retry(retry) },
                    enabled =
                        !(if (reading) s.readLimited else s.limited) && !s.sending &&
                            s.consentGate == com.couli.core.agent.AgentConsentGate.CLEAR,
                    variant = CouliButtonVariant.SECONDARY,
                    size = CouliButtonSize.COMPACT,
                    modifier = Modifier.testTag(AgentTags.retry(key)),
                )
            }
            error.newChat?.let { page ->
                CouliButton(
                    text = texts.text("agent.draft.new_chat"),
                    onClick = { controller.newChat(page) },
                    variant = CouliButtonVariant.SECONDARY,
                    size = CouliButtonSize.COMPACT,
                    modifier = Modifier.testTag(AgentTags.newChatError(key)),
                )
            }
            error.search?.let { tap ->
                CouliButton(
                    text = tap.label,
                    onClick = { controller.tap(tap) },
                    variant = CouliButtonVariant.SECONDARY,
                    size = CouliButtonSize.COMPACT,
                    modifier = Modifier.testTag(AgentTags.search(key)),
                )
            }
            error.upgrade?.let { tap ->
                CouliButton(
                    text = tap.label,
                    onClick = { controller.tap(tap) },
                    size = CouliButtonSize.COMPACT,
                    modifier = Modifier.testTag(AgentTags.upgrade(key)),
                )
            }
        }
        // AgentDisconnected 稿：「已生成的内容保留在上面，点「重试」继续查询」
        if (error.hint.isNotEmpty()) Text(text = error.hint, style = CouliTypography.footnote, color = CouliColors.textSecondary)
    }
}

/** 一张卡（03 §7.4）：已注册且合 schema 的按类型画；未知 / 不合 schema / 版本过高只显示 fallback_text（纯文字、不可点，G09）。 */
@Composable
private fun AgentCard(
    v: AgentCardView,
    s: AgentSnapshot,
    controller: AgentController,
    ui: AgentPageUi,
) {
    val tag = Modifier.testTag(AgentTags.card(v.card.cardId))
    if (!v.card.known) {
        // 所有 fallback_text 一律经受限渲染器（03 §7.2、§7.3，BR-AI-06，Android R1-06）：先剥 URL / scheme / 口令再分块；纯文字、不可点、不附按钮
        Column(
            modifier = tag.fillMaxWidth().couliCard().padding(CouliSpacing.space3),
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
        ) {
            AgentText.render(v.card.fallbackText).forEach { TextBlock(it) }
        }
        return
    }
    when (v.card.type) {
        AgentCardType.PRODUCT_LIST, AgentCardType.REBATE_QUOTE -> ProductsCard(v, s, controller, ui, tag)
        AgentCardType.PAGE_GUIDE -> GuideCard(v, controller, tag)
        AgentCardType.EARNINGS_SUMMARY -> v.earnings?.let { EarningsCard(v, it, controller, ui, tag) }
        else -> InfoCard(v, s, controller, ui, tag)
    }
}

/**
 * 页面引导卡（D31，G31 无高保真稿：notice 同族版式——说明文字 + 一个次要按钮）：按钮只在用户点击时经 Router 打开（agent_card 来源、空参数，brief §0 第 1 条）；
 * 按钮上显示「去看看」，读屏读完整名称「去看看，提现记录」。
 */
@Composable
private fun GuideCard(
    v: AgentCardView,
    controller: AgentController,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().couliCard().padding(CouliSpacing.space3),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        Text(text = v.guideText, style = CouliTypography.body, color = CouliColors.textPrimary)
        v.guide?.let { tap ->
            CouliButton(
                text = tap.text,
                onClick = { controller.tap(tap) },
                variant = CouliButtonVariant.SECONDARY,
                size = CouliButtonSize.COMPACT,
                accessibilityLabel = tap.label,
                modifier = Modifier.testTag(AgentTags.guide(v.card.cardId)),
            )
        }
    }
}

/**
 * 收益卡（D32，G31 无高保真稿：钱包汇总的字号与颜色——两列金额 + 一行提现摘要 + 两个按钮）。实时形状：可提现余额、预估收益 +「最早预计 X 月结算」（逾期附
 * 「入账核对中」）、最近一笔提现的主状态标题 + 申请金额、「更新于 HH:mm」；历史形状：agent.earnings.history + 两个按钮，没有任何金额（G13）。金额隐藏时三处
 * 「****」，读屏读「金额已隐藏」不读数字（G14）。不显示净额、税费、原因、时效（R-06）。
 */
@Composable
private fun EarningsCard(
    v: AgentCardView,
    e: EarningsView,
    controller: AgentController,
    ui: AgentPageUi,
    modifier: Modifier,
) {
    val texts = ui.texts
    val hidden = texts.text("agent.draft.amount_hidden")

    fun spoken(value: String?) = if (e.masked) hidden else value.orEmpty()
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .couliCard()
                .padding(CouliSpacing.space3)
                .testTag(AgentTags.earnings(v.card.cardId)),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        if (e.shape == EarningsShape.LIVE) {
            Text(text = texts.text("agent.draft.earnings.title"), style = CouliTypography.footnote, color = CouliColors.textSecondary)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space6),
                verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
            ) {
                AmountColumn(texts.text("agent.draft.earnings.withdrawable"), e.withdrawable.orEmpty(), spoken(e.withdrawable), null)
                AmountColumn(
                    texts.text("agent.draft.earnings.estimated"),
                    e.estimatedTotal.orEmpty(),
                    spoken(e.estimatedTotal),
                    AgentDraftTexts.earningsCredit(e.creditPeriod, e.creditOverdue).takeIf(String::isNotEmpty),
                )
            }
            if (e.latestWithdrawalShown) {
                val applied = texts.text("agent.earnings.applied_amount")
                val line =
                    listOfNotNull(
                        texts.text("agent.draft.earnings.latest"),
                        e.latestTitle,
                        "$applied ${e.appliedAmount.orEmpty()}",
                    ).joinToString(AgentDraftTexts.LINE_SEPARATOR)
                val spokenLine =
                    listOfNotNull(
                        texts.text("agent.draft.earnings.latest"),
                        e.latestTitle,
                        "$applied ${spoken(e.appliedAmount)}",
                    ).joinToString(AgentDraftTexts.SPOKEN_SEPARATOR)
                Text(
                    text = line,
                    style = CouliTypography.footnote,
                    color = CouliColors.textPrimary,
                    modifier = Modifier.semantics { contentDescription = spokenLine },
                )
            }
            e.updatedAt?.let {
                Text(
                    text = AgentDraftTexts.format("agent.draft.earnings.updated_at", mapOf("time" to it)),
                    style = CouliTypography.caption,
                    color = CouliColors.textSecondary,
                )
            }
        } else {
            Text(text = texts.text(e.historyTextKey.orEmpty()), style = CouliTypography.body, color = CouliColors.textPrimary)
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
        ) {
            v.earningsTaps.forEach { tap ->
                CouliButton(
                    text = tap.label,
                    onClick = { controller.tap(tap) },
                    variant = CouliButtonVariant.SECONDARY,
                    size = CouliButtonSize.COMPACT,
                    modifier = Modifier.testTag(AgentTags.earningsButton(v.card.cardId, tap.route)),
                )
            }
        }
    }
}

/** 一格金额（钱包汇总口径：标签 caption 灰、数值 amountTitle 墨棕；放不下换行，不截断）。 */
@Composable
private fun AmountColumn(
    label: String,
    value: String,
    spoken: String,
    note: String?,
) {
    Column(
        modifier =
            Modifier.semantics(mergeDescendants = true) {
                contentDescription =
                    listOfNotNull(label, spoken, note).joinToString(AgentDraftTexts.SPOKEN_SEPARATOR)
            },
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
    ) {
        Text(text = label, style = CouliTypography.caption, color = CouliColors.textSecondary)
        Text(text = value, style = CouliTextStyles.amountTitle, color = CouliColors.textPrimary)
        note?.let { Text(text = it, style = CouliTypography.caption, color = CouliColors.textSecondary) }
    }
}

/** 规则、订单状态、找回草稿、客服、授权、提示卡：标题 + 正文行 + 一个按钮（找回草稿没有可点的提交按钮，G21）。 */
@Composable
private fun InfoCard(
    v: AgentCardView,
    s: AgentSnapshot,
    controller: AgentController,
    ui: AgentPageUi,
    modifier: Modifier,
) {
    val texts = ui.texts
    val order = v.order
    val clickable =
        if (order !=
            null
        ) {
            Modifier.clickable(role = Role.Button, onClickLabel = order.label) { controller.tap(order) }
        } else {
            Modifier
        }
    Column(
        modifier =
            modifier.fillMaxWidth().couliCard().then(clickable).padding(CouliSpacing.space3).then(
                if (order !=
                    null
                ) {
                    Modifier.testTag(AgentTags.order(v.card.cardId))
                } else {
                    Modifier
                },
            ),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        if (v.title.isNotEmpty()) {
            Text(
                text = v.title,
                style = CouliTypography.body.copy(fontWeight = FontWeight.SemiBold),
                color = CouliColors.textPrimary,
            )
        }
        v.lines.forEach { Text(text = it, style = CouliTypography.footnote, color = CouliColors.textPrimary) }
        v.amountText?.let { amount ->
            val spoken = if (s.amountMasked) texts.text("agent.draft.amount_hidden") else amount
            Row(
                modifier =
                    Modifier.semantics(mergeDescendants = true) {
                        contentDescription =
                            v.amountLabel + AgentDraftTexts.SPOKEN_SEPARATOR + spoken
                    },
                horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
            ) {
                Text(text = v.amountLabel, style = CouliTypography.footnote, color = CouliColors.textSecondary)
                Text(text = amount, style = CouliTextStyles.amountBody, color = CouliColors.rebateText)
            }
        }
        val button: AgentTap? = v.rule ?: v.auth ?: v.handoff
        button?.let { tap ->
            val testTag =
                when (tap) {
                    v.rule -> AgentTags.rule(v.card.cardId)
                    v.auth -> AgentTags.auth(v.card.cardId)
                    else -> AgentTags.handoff(v.card.cardId)
                }
            CouliButton(
                text = tap.label,
                onClick = { controller.tap(tap) },
                variant = CouliButtonVariant.SECONDARY,
                size = CouliButtonSize.COMPACT,
                modifier = Modifier.testTag(testTag),
            )
        }
        if (v.chips.isNotEmpty()) ChipRow(v.chips, enabled = s.canCompose, scope = "card_${v.card.cardId}", onChip = controller::chip)
    }
}

/**
 * 商品卡（F-AGENT-11「与搜索结果相同的紧凑布局」）：list 逐张 AI 版列表卡；grid 两列网格（每张都有购买按钮，鸿蒙 R1-13；只有 1 件、宽度放不下两列或字号放大
 * 超过 1.3 倍时单列，鸿蒙 H22）；rebate_quote 单卡 + 素材行（G22）。每张卡上方一个 match 标记与「标题显示为…」（G20），ad_label 原样，下架 / 券失效时卡片下方
 * 「这个券已领完」与「换一批」（G25）。
 */
@Composable
private fun ProductsCard(
    v: AgentCardView,
    s: AgentSnapshot,
    controller: AgentController,
    ui: AgentPageUi,
    modifier: Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3)) {
        if (v.layout == AgentCardView.LAYOUT_GRID) {
            ProductGrid(v, s, controller, ui)
        } else {
            v.products.forEach { p -> ProductItem(v, p, s, controller, ui, grid = false) }
        }
        if (v.lines.isNotEmpty()) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(
                            CouliColors.backgroundMuted,
                            RoundedCornerShape(CouliRadius.small),
                        ).padding(CouliSpacing.space2),
                verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
            ) {
                Text(text = v.title, style = CouliTypography.caption, color = CouliColors.textSecondary)
                v.lines.forEach { Text(text = it, style = CouliTypography.footnote, color = CouliColors.textPrimary) }
            }
        }
    }
}

@Composable
private fun ProductGrid(
    v: AgentCardView,
    s: AgentSnapshot,
    controller: AgentController,
    ui: AgentPageUi,
) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val gap = CouliSpacing.space3
        val twoColumns = v.products.size > 1 && maxWidth >= GRID_CELL_MIN.dp * 2 + gap && fontScale <= GRID_FONT_MAX
        if (!twoColumns) {
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                v.products.forEach { p ->
                    ProductItem(v, p, s, controller, ui, grid = false)
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                v.products.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        row.forEach { p -> Box(modifier = Modifier.weight(1f)) { ProductItem(v, p, s, controller, ui, grid = true) } }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ProductItem(
    v: AgentCardView,
    p: AgentProductView,
    s: AgentSnapshot,
    controller: AgentController,
    ui: AgentPageUi,
    grid: Boolean,
) {
    val texts = ui.texts
    val mapped: MappedProductCard = ui.mapCard(p.json)
    val override = ui.buyButton(p)
    val state = override?.state ?: if (p.buy == null) BuyButtonState.DISABLED else mapped.buyState
    val buy = p.buy
    val onBuy: () -> Unit = { buy?.let(controller::buy) }
    val detail = p.detail
    val itemId = p.itemCardId
    Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
        MatchRow(p, texts)
        val open =
            if (detail !=
                null
            ) {
                Modifier.clickable(role = Role.Button, onClickLabel = detail.label) { controller.tap(detail) }
            } else {
                Modifier
            }
        Box(modifier = Modifier.fillMaxWidth().then(open).testTag(AgentTags.product(v.card.cardId, itemId))) {
            if (grid) {
                Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
                    ProductCardGrid(data = mapped.data, modifier = Modifier.fillMaxWidth())
                    BuyButton(
                        base = mapped.data.buyBase,
                        rebate = mapped.data.displayedRebate,
                        onClick = onBuy,
                        state = state,
                        amountHidden = mapped.data.amountHidden,
                        retryLaterText = override?.text ?: mapped.retryLaterText,
                        modifier = Modifier.fillMaxWidth().testTag(AgentTags.buy(v.card.cardId, itemId)),
                    )
                }
            } else {
                ProductCardList(
                    data = mapped.data,
                    onBuy = onBuy,
                    variant = ProductCardVariant.AI,
                    buyState = state,
                    retryLaterText = override?.text ?: mapped.retryLaterText,
                    modifier = Modifier.testTag(AgentTags.buy(v.card.cardId, itemId)),
                )
            }
        }
        AgentJson.stringOf(p.json["ad_label"])?.let { CouliTag(text = it, tone = CouliTagTone.NEUTRAL) }
        if (p.couponGone) {
            Text(
                text = texts.text("agent.draft.coupon_gone"),
                style = CouliTypography.footnote,
                color = CouliColors.statusWarningText,
            )
        }
        p.refresh?.let { chip ->
            Chip(text = chip.text, enabled = s.canCompose, onClick = {
                controller.chip(chip)
            }, modifier = Modifier.testTag(AgentTags.refresh(v.card.cardId, itemId)))
        }
    }
}

/** match_tag 一个标记 + spec_text 非 null 时「标题显示为X」（G20、BR-AI-24：不自造「符合你的要求」一类说法）。 */
@Composable
private fun MatchRow(
    p: AgentProductView,
    texts: com.couli.core.agent.AgentTexts,
) {
    val tag = AgentJson.stringOf(p.json["match_tag"]) ?: return
    val kind =
        when (tag) {
            MATCHED -> MatchBadgeKind.MATCHED
            RELAXED -> MatchBadgeKind.RELAXED
            else -> MatchBadgeKind.UNCONFIRMED
        }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
    ) {
        MatchBadge(text = texts.text("agent.match.$tag"), kind = kind)
        AgentJson.stringOf(p.json["spec_text"])?.let { spec ->
            Text(
                text = AgentDraftTexts.format("agent.match.title_shows", mapOf("spec" to spec)),
                style = CouliTypography.caption,
                color = CouliColors.textSecondary,
                modifier = Modifier.heightIn(min = CouliSizes.badgeHeight).padding(top = CouliSpacing.space1),
            )
        }
    }
}

private const val USER_BUBBLE_MAX = 320
private const val CARD_REPLY_KEY = "agent.draft.a11y.card_reply"
private const val GRID_CELL_MIN = 160
private const val GRID_FONT_MAX = 1.3f
private const val BULLET = "•"
private const val MATCHED = "matched"
private const val RELAXED = "relaxed"
