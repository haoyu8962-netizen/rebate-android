package com.couli.feature.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.couli.core.agent.AgentChipTap
import com.couli.core.agent.AgentConsentGate
import com.couli.core.agent.AgentController
import com.couli.core.agent.AgentPageTicket
import com.couli.core.agent.AgentPhase
import com.couli.core.agent.AgentRunTicket
import com.couli.core.agent.AgentSnapshot
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonSize
import com.couli.core.designsystem.CouliButtonVariant
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliRadius
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliToast
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.EmptyState
import com.couli.core.designsystem.ErrorState
import com.couli.core.designsystem.LocalCouliAnnouncer
import com.couli.core.designsystem.StateAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 底部 Tab「AgentChat」的页面（APP-10，03 §7.5）：顶部标题与【新对话】、公示（config.agent.filing_text，空串不显示）、空态（agent.welcome +「你可以这样问我」示例）、
 * 消息列表（LazyColumn，时间顺序即读屏顺序）、快捷胶囊（粘贴查返利 / 查订单）、输入框（500 字，发送中禁用并显示【停止】）、自动滚动与「回到底部」（读屏开启时
 * 不自动滚动，改为播报文字）。「未开放」态（G01）不显示输入框、不弹同意框、不发请求。数据、票据与文字都由 Core 的 [AgentController] 给（视图只展示）。
 *
 * 回调捕获**渲染时取出的普通不可变值**（APP-09 第 63 条）：票据从这一次的快照里取出再交给 [controller]；[controller] 是这个页面实例构造时那一个（App 按控制器编号
 * 整块重建），旧回调只会打到已作废的旧控制器、被它拒绝，不会转交新控制器（APP-11 第 1 条）。大屏内容区限宽 600dp 居中（03 §10.4）。
 */
@Suppress("CyclomaticComplexMethod", "ComplexCondition") // 页面骨架按阶段逐项组合
@Composable
fun AgentScreen(
    controller: AgentController,
    ui: AgentPageUi,
) {
    val s by controller.snapshot.collectAsStateWithLifecycle()
    val visible by ui.visible.collectAsStateWithLifecycle()
    val blocked by ui.blocked.collectAsStateWithLifecycle()
    val texts = ui.texts
    Box(modifier = Modifier.fillMaxSize().background(CouliColors.backgroundCanvas), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = CONTENT_MAX_WIDTH.dp)
                    .fillMaxSize()
                    .statusBarsPadding()
                    .imePadding()
                    .testTag(AgentTags.PAGE),
        ) {
            val ready = s.phase == AgentPhase.READY && !ui.readonly
            Header(s, ready, controller, ui)
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    // 只读打开、基本模式、还没取到会话：留空（不发请求）
                    ui.readonly || s.phase == AgentPhase.BLOCKED || s.phase == AgentPhase.IDLE -> {}

                    s.phase == AgentPhase.UNAVAILABLE -> {
                        EmptyState(
                            title = texts.text(UNAVAILABLE_KEY),
                            message = texts.text("agent.draft.unavailable_hint"),
                            icon = CouliIcons.chat,
                            modifier = Modifier.fillMaxSize().testTag(AgentTags.UNAVAILABLE),
                        )
                    }

                    s.phase == AgentPhase.LOADING -> {
                        Box(modifier = Modifier.fillMaxSize().testTag(AgentTags.LOADING), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = CouliColors.textSecondary, modifier = Modifier.size(CouliSpacing.space8))
                        }
                    }

                    s.phase == AgentPhase.LOAD_FAILED -> {
                        val page = s.page
                        // 整页加载失败的【重试】受读取闸门控制（G24、鸿蒙 R2-06）
                        ErrorState(
                            title = s.loadErrorText,
                            retry = StateAction(texts.text(RETRY_KEY), enabled = !s.readLimited) { controller.retryLoad(page) },
                            modifier = Modifier.fillMaxSize().testTag(AgentTags.LOAD_FAILED),
                        )
                    }

                    s.phase == AgentPhase.CONSENT_DECLINED -> {
                        ConsentDeclined(s, controller, ui)
                    }

                    s.turns.isEmpty() -> {
                        Welcome(s, controller, ui)
                    }

                    else -> {
                        MessageList(s, controller, ui)
                    }
                }
            }
            ui.purchaseBanner()
            // 会话已结束 / 已过期（G09、历史 30504）：error.30504 +【新对话】
            if (ready) SessionEndedBanner(s, controller, texts)
            if (ready) Composer(s, controller, ui)
        }
        // 举报半屏（G29，AgentReport 稿）：页面可见才组合；离开（切 Tab、被覆盖）时 Core 已关闭本实例
        ReportSheetHost(s, controller, texts, visible)
        ToastHost(ui)
        // 同意弹窗（共性第 5 条，APP-11 第 3、18、21 条）：只在「票据仍是此刻那一张、页面可见、没有阻挡层」时组合；不满足时不组合（不提交、不算拒绝），
        // 条件恢复后按同一张票据再显示。系统模态：系统返回先关它、读屏只读弹窗内容
        val consent = s.consent
        if (consent != null && visible && !blocked && controller.consentShowable(consent, blocked)) {
            AgentConsentDialog(consent, controller, texts)
        }
        ui.purchaseOverlay()
    }
}

@Composable
private fun Header(
    s: AgentSnapshot,
    ready: Boolean,
    controller: AgentController,
    ui: AgentPageUi,
) {
    val texts = ui.texts
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(CouliColors.backgroundSurface)
                .padding(start = CouliSpacing.space4, end = CouliSpacing.space2, top = CouliSpacing.space1, bottom = CouliSpacing.space1),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
    ) {
        Row(modifier = Modifier.fillMaxWidth().heightIn(min = CouliSizes.touchTarget), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = texts.text(NAME_KEY),
                style = CouliTypography.title,
                color = CouliColors.textPrimary,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            if (ready) {
                val page = s.page
                CouliButton(
                    text = texts.text(NEW_CHAT_KEY),
                    onClick = { controller.newChat(page) },
                    variant = CouliButtonVariant.TEXT,
                    size = CouliButtonSize.COMPACT,
                    leadingIcon = CouliIcons.chat,
                    modifier = Modifier.testTag(AgentTags.NEW_CHAT),
                )
            }
        }
        if (s.filingText.isNotEmpty()) {
            Text(
                text = s.filingText,
                style = CouliTypography.caption,
                color = CouliColors.textSecondary,
                modifier = Modifier.padding(end = CouliSpacing.space2).testTag(AgentTags.FILING),
            )
        }
    }
}

/** 空态：agent.welcome +「你可以这样问我」示例（点示例即发送；config.agent.examples 为空不显示分组，G30）。 */
@Composable
private fun Welcome(
    s: AgentSnapshot,
    controller: AgentController,
    ui: AgentPageUi,
) {
    val texts = ui.texts
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(CouliSpacing.space4),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space4),
    ) {
        Text(
            text = texts.text(WELCOME_KEY),
            style = CouliTypography.body,
            color = CouliColors.textPrimary,
            modifier = Modifier.testTag(AgentTags.WELCOME),
        )
        if (s.examples.isNotEmpty()) {
            Text(text = texts.text("agent.draft.examples_title"), style = CouliTypography.footnote, color = CouliColors.textSecondary)
            ChipRow(
                chips = s.examples,
                enabled = s.canCompose,
                scope = "example",
                onChip = controller::chip,
                modifier = Modifier.testTag(AgentTags.EXAMPLES),
            )
        }
    }
}

/** 【暂不使用】、同意记录失败、迟到的 AI 10004 之后：说明 +【重新同意】（G17，BR-ID-14：App 其他功能不受影响，不切 Tab、不出栈）。 */
@Composable
private fun ConsentDeclined(
    s: AgentSnapshot,
    controller: AgentController,
    ui: AgentPageUi,
) {
    val texts = ui.texts
    val page = s.page
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(
                    rememberScrollState(),
                ).padding(CouliSpacing.space6)
                .testTag(AgentTags.CONSENT_DECLINED),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space4, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = texts.text(if (s.consentFailed) "agent.draft.consent.failed" else "agent.draft.consent.declined"),
            style = CouliTypography.body,
            color = CouliColors.textPrimary,
        )
        CouliButton(
            text = texts.text("agent.draft.consent.reagree"),
            onClick = { controller.consentReopen(page) },
            enabled = s.consentGate == AgentConsentGate.REFUSED,
            modifier = Modifier.fillMaxWidth().testTag(AgentTags.REAGREE),
        )
    }
}

/**
 * 消息列表（03 §7.5，G30）：用户没有上滑时跟随新内容（离底部超过一屏 1/4 视为已上滑，代拟），上滑后显示「回到底部」；读屏开启时一律不自动滚动，新内容只播报
 * 文字（不播报卡片与金额，APP-11 第 14、20 条）。滚动位置只在内存里保留（[AgentPageMemory]）。
 */
@Suppress("CyclomaticComplexMethod", "ComplexCondition") // 跟随、读屏、回到底部逐项
@Composable
private fun MessageList(
    s: AgentSnapshot,
    controller: AgentController,
    ui: AgentPageUi,
) {
    val reader by ui.screenReader.collectAsStateWithLifecycle()
    val page = s.page
    val (index, offset) = remember(page) { ui.memory.scrollFor(page) }
    val list = rememberLazyListState(index, offset)
    // 跟随状态随阅读位置一起保存恢复（Android R1-08、APP-12 第 27 条）：恢复到上滑过的位置时不重新开启跟随
    var follow by remember(page) { mutableStateOf(ui.memory.followFor(page)) }
    DisposableEffect(list, page) {
        onDispose { ui.memory.saveScroll(page, list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, follow) }
    }
    // 是否在底部：还没布局（没有可见条目）时为未知（null），不据此改跟随状态
    val atBottomOrNull by remember(list) {
        derivedStateOf {
            val info = list.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            val slack = (info.viewportEndOffset - info.viewportStartOffset) / BOTTOM_SLACK_DIVISOR
            when {
                last == null -> if (info.totalItemsCount == 0) true else null
                else -> last.index >= info.totalItemsCount - 1 && last.offset + last.size - info.viewportEndOffset <= slack
            }
        }
    }
    val atBottom = atBottomOrNull != false
    // 只有用户拖动才算「上滑」（程序滚动不改跟随状态）；回到底部（不论怎么回去的）恢复跟随
    val dragged by list.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(atBottomOrNull, dragged) {
        val bottom = atBottomOrNull ?: return@LaunchedEffect
        if (dragged || bottom) follow = bottom
    }
    var announcedText by remember(page) { mutableStateOf("") }
    val announcer = LocalCouliAnnouncer.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    // 最后一条长高（流式文字、字号变化、图片就绪）时跟随中的列表也要到底
    val lastSize by remember(list) {
        derivedStateOf {
            list.layoutInfo.visibleItemsInfo
                .lastOrNull()
                ?.size ?: 0
        }
    }
    // 唯一的自动滚动路径（Android R1-07，A10）：读屏开启时一律不自动滚动，只播报文字；「回到底部」按钮是用户主动滚动，单独在点击里发起
    LaunchedEffect(s.revision, reader, lastSize) {
        if (reader) {
            if (s.announcement.isNotEmpty() && s.announcement != announcedText) {
                announcedText = s.announcement
                announcer?.announce(s.announcement) ?: view.announceForAccessibility(s.announcement)
            }
            return@LaunchedEffect
        }
        if (follow && s.turns.isNotEmpty()) list.scrollToBottom(s.turns.lastIndex, animated = false)
    }
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().testTag(AgentTags.LIST),
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space4),
            contentPadding =
                androidx.compose.foundation.layout
                    .PaddingValues(CouliSpacing.space4),
        ) {
            // 向上翻页（G08）：顶端一行「加载更早的消息」/「没有更早的消息」
            if (s.hasEarlier || s.earlierLoading || s.historyExhausted) item(key = EARLIER_ITEM_KEY) { EarlierRow(s, controller, ui.texts) }
            items(s.turns, key = { it.key }) { turn -> AgentTurnItem(turn, s, controller, ui) }
        }
        // 第一条消息进入可见区（滚到顶部）：取更早一页（一次只在途一页；读取闸门期间不取，G08）
        val atTop by remember(list) { derivedStateOf { list.firstVisibleItemIndex == 0 && list.layoutInfo.totalItemsCount > 0 } }
        LaunchedEffect(atTop, s.hasEarlier, s.earlierLoading, s.readLimited, s.earlierError) {
            if (atTop && s.hasEarlier && !s.earlierLoading && !s.readLimited && s.earlierError.isEmpty() && list.isScrollInProgress) {
                controller.loadEarlier(page)
            }
        }
        if (!atBottom) {
            Row(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = CouliSpacing.space3)
                        .heightIn(min = CouliSizes.touchTarget)
                        .border(CouliSizes.hairline, CouliColors.borderSubtle, RoundedCornerShape(CouliRadius.pill))
                        .background(CouliColors.backgroundSurface, RoundedCornerShape(CouliRadius.pill))
                        .clickable(role = Role.Button, onClickLabel = ui.texts.text(BACK_KEY)) {
                            // 用户主动回到底部：在点击里直接发起滚动（不经自动跟随的 Effect），之后恢复跟随
                            follow = true
                            val last = s.turns.lastIndex
                            if (last >= 0) scope.launch { list.scrollToBottom(last, animated = true) }
                        }.padding(horizontal = CouliSpacing.space3)
                        .testTag(AgentTags.BACK_TO_BOTTOM),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
            ) {
                Icon(
                    CouliIcons.chevronDown,
                    contentDescription = null,
                    tint = CouliColors.textPrimary,
                    modifier = Modifier.size(CouliSizes.iconSmall),
                )
                Text(text = ui.texts.text(BACK_KEY), style = CouliTypography.footnote, color = CouliColors.textPrimary)
            }
        }
    }
}

/**
 * 快捷胶囊 + 输入框（G29、G30）：「粘贴查返利」自绘按钮（BR-ID-16）读到的文字**填进输入框**，由用户点发送（不自动发送剪贴板内容）；「查订单」按普通消息发送；
 * 发送中、42901 禁用期、AI 同意没记录成功时置灰。输入只写进渲染时那一个对话票据（APP-09 第 59 条），超过 500 字不让发送（Core 再拦一次）。
 */
@Composable
private fun Composer(
    s: AgentSnapshot,
    controller: AgentController,
    ui: AgentPageUi,
) {
    val texts = ui.texts
    val page = s.page
    val stop: AgentRunTicket? = s.stop
    val enabled = s.canCompose
    var draft by remember(page) { mutableStateOf(ui.memory.draftFor(page)) }

    fun write(text: String) {
        draft = text
        ui.memory.setDraft(page, text)
    }

    fun submit() {
        if (controller.send(page, draft)) write("")
    }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(CouliColors.backgroundSurface)
                .padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space2),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        s.contextLabelKey?.let { key ->
            Text(
                text = texts.text(key),
                style = CouliTypography.caption,
                color = CouliColors.textSecondary,
                modifier =
                    Modifier
                        .background(CouliColors.backgroundMuted, RoundedCornerShape(CouliRadius.pill))
                        .padding(horizontal = CouliSpacing.space2, vertical = CouliSpacing.space1)
                        .testTag(AgentTags.CONTEXT),
            )
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
        ) {
            Chip(
                text = texts.text("agent.draft.chip.paste"),
                enabled = enabled,
                onClick = { ui.paste()?.let { pasted -> if (s.page == page) write(pasted) } },
                modifier = Modifier.testTag(AgentTags.PASTE),
            )
            s.ordersChip?.let { chip: AgentChipTap ->
                Chip(
                    text = chip.text,
                    enabled = enabled,
                    onClick = { controller.chip(chip) },
                    modifier = Modifier.testTag(AgentTags.ORDERS_CHIP),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
            val placeholder = texts.text(if (s.quotaExhausted) "agent.draft.quota.input_disabled" else "agent.draft.input_placeholder")
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .heightIn(min = CouliSizes.touchTarget)
                        .border(CouliSizes.hairline, CouliColors.borderControl, RoundedCornerShape(CouliRadius.button))
                        .padding(horizontal = CouliSpacing.space3, vertical = CouliSpacing.space2),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (draft.isEmpty()) Text(text = placeholder, style = CouliTypography.body, color = CouliColors.textSecondary)
                BasicTextField(
                    value = draft,
                    onValueChange = ::write,
                    // 只读会话（G09）同样禁用
                    enabled = !s.quotaExhausted && s.consentGate == AgentConsentGate.CLEAR && !s.readOnly,
                    textStyle = CouliTypography.body.copy(color = CouliColors.textPrimary),
                    cursorBrush = SolidColor(CouliColors.textPrimary),
                    maxLines = INPUT_MAX_LINES,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    modifier = Modifier.fillMaxWidth().testTag(AgentTags.INPUT).semantics { contentDescription = placeholder },
                )
            }
            if (stop != null) {
                CouliButton(
                    text = texts.text("agent.draft.stop"),
                    onClick = { controller.stop(stop) },
                    variant = CouliButtonVariant.SECONDARY,
                    size = CouliButtonSize.COMPACT,
                    modifier = Modifier.testTag(AgentTags.STOP),
                )
            } else {
                CouliButton(
                    text = texts.text("agent.draft.send"),
                    onClick = ::submit,
                    enabled = enabled && draft.isNotBlank(),
                    size = CouliButtonSize.COMPACT,
                    modifier = Modifier.testTag(AgentTags.SEND),
                )
            }
        }
    }
}

/** 一行快捷胶囊（追问、示例、notice 的 actions、「换一批」）：点了才发（Core 比对话世代 / 卡片动作世代）。 */
@Composable
internal fun ChipRow(
    chips: List<AgentChipTap>,
    enabled: Boolean,
    scope: String,
    onChip: (AgentChipTap) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
    ) {
        chips.forEachIndexed { i, chip ->
            Chip(text = chip.text, enabled = enabled, onClick = { onChip(chip) }, modifier = Modifier.testTag(AgentTags.chip(scope, i)))
        }
    }
}

/** 胶囊按钮：最小命中区 48dp（Android），文字放不下换行。 */
@Composable
internal fun Chip(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(CouliRadius.pill)
    Box(
        modifier =
            modifier
                .heightIn(min = CouliSizes.touchTarget)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = CouliTypography.footnote,
            color = if (enabled) CouliColors.textPrimary else CouliColors.textSecondary,
            modifier =
                Modifier
                    .border(CouliSizes.hairline, CouliColors.borderControl, shape)
                    .background(CouliColors.backgroundSurface, shape)
                    .padding(horizontal = CouliSpacing.space3, vertical = CouliSpacing.space1),
        )
    }
}

/** 轻提示：显示 2 秒后消费（只认这一条的编号）。 */
@Composable
private fun ToastHost(ui: AgentPageUi) {
    val toast by ui.toast.collectAsStateWithLifecycle()
    val t = toast ?: return
    LaunchedEffect(t.id) {
        delay(TOAST_MILLIS)
        ui.consumeToast(t.id)
    }
    Box(modifier = Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.TopCenter) {
        CouliToast(
            message = t.text,
            icon = null,
            announcementId = t.id,
            modifier = Modifier.padding(top = CouliSpacing.space12).testTag(AgentTags.TOAST),
        )
    }
}

private const val CONTENT_MAX_WIDTH = 600
private const val BOTTOM_SLACK_DIVISOR = 4
private const val INPUT_MAX_LINES = 4
private const val TOAST_MILLIS = 2_000L
private const val NAME_KEY = "agent.name"
private const val WELCOME_KEY = "agent.welcome"
private const val NEW_CHAT_KEY = "agent.draft.new_chat"
private const val UNAVAILABLE_KEY = "error.30501"
private const val RETRY_KEY = "agent.draft.retry"
private const val BACK_KEY = "agent.draft.back_to_bottom"
private const val EARLIER_ITEM_KEY = "agent_earlier_row"

/** 页面票据（供卡片渲染器把对话级动作交回同一张票据）。 */
internal val AgentSnapshot.pageTicket: AgentPageTicket get() = page

/** 滚到最后一条的底部（最后一条比一屏高时也到底，G30）。 */
private suspend fun androidx.compose.foundation.lazy.LazyListState.scrollToBottom(
    lastIndex: Int,
    animated: Boolean,
) {
    if (animated) animateScrollToItem(lastIndex) else scrollToItem(lastIndex)
    scrollBy(BOTTOM_SCROLL_PX)
}

private const val BOTTOM_SCROLL_PX = 1_000_000f
