package com.couli.feature.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.couli.core.designsystem.AmountCell
import com.couli.core.designsystem.AmountGrid
import com.couli.core.designsystem.AmountGridTone
import com.couli.core.designsystem.AmountMask
import com.couli.core.designsystem.AmountValue
import com.couli.core.designsystem.AmountValueSize
import com.couli.core.designsystem.CouliButtonVariant
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliFormat
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.EntryGrid
import com.couli.core.designsystem.EntryItem
import com.couli.core.designsystem.ErrorState
import com.couli.core.designsystem.InfoStrip
import com.couli.core.designsystem.PriceTerms
import com.couli.core.designsystem.Skeleton
import com.couli.core.designsystem.StateAction
import com.couli.core.designsystem.WalletHeroCard
import com.couli.core.designsystem.WalletHeroTexts
import com.couli.core.designsystem.WholeNumberText
import com.couli.core.designsystem.couliCard
import com.couli.core.wallet.MaskableAmount
import com.couli.core.wallet.WalletSummaryDisplay
import com.couli.core.wallet.WalletSummaryView
import com.couli.core.wallet.WalletTexts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject

/** 页面配置（App 给）。[params] 是 Router 给的路由参数（资金与实名四页已被丢弃）。 */
class WalletPageConfig(
    val deps: WalletDeps,
    val onBack: () -> Unit,
    val hooks: PageHooks = PageHooks(),
    val params: JsonObject = JsonObject(emptyMap()),
    /** 本页的回退栈条目（成功后的出栈 / 替换只作用于它，APP-09 共性第 50 条）。 */
    val entryId: String? = null,
)

class WalletViewModel(
    config: WalletPageConfig,
) : ViewModel() {
    val resume = ResumeTracker()

    val controller = WalletController(config.deps, viewModelScope)

    init {
        config.hooks.bindLeave(controller::leave)
        config.hooks.bindSession(controller::onSessionChanged)
        config.hooks.bindIdentity(controller::onIdentity)
        controller.load()
    }

    override fun onCleared() {
        controller.leave()
        super.onCleared()
    }
}

/**
 * 钱包（Wallet 路由；设计稿 Wallet、WalletFrozen、WalletNegative、WalletRiskFrozen、WalletStale、WalletNote*、WalletEarningsEntry；AdaptLargeTextWallet）：
 * 风控暂停提示条 → 断网提示条 → 可提现余额主卡（眼睛、「提现记录 ›」、【提现】；待抵扣行）→ 冻结中 / 已提现两列数字格 → 预估收益卡（其中已收货、最早预计、
 * 入账核对中、暂缓入账、说明句）→ 宫格入口（订单、余额流水、收益看板）。金额隐藏时可提现、待抵扣、冻结中、已提现、预估收益及其分项一律「****」，
 * 月份行与「入账核对中」照常显示。大屏内容限宽 600 居中。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletScreen(config: WalletPageConfig) {
    val model: WalletViewModel = viewModel { WalletViewModel(config) }
    val controller = model.controller
    val state = controller.state.collectAsStateWithLifecycle().value
    val blocked = controller.blocked.collectAsStateWithLifecycle().value
    val noMask = remember { MutableStateFlow(false) }
    val masked = (config.deps.mask?.hidden ?: noMask).collectAsStateWithLifecycle().value
    val texts = config.deps.texts
    var help by remember { mutableStateOf<String?>(null) }
    val helpOrigin = remember { FocusRequester() }
    OnReturn(model.resume) { controller.refresh() }
    WalletPage(
        tag = WalletTags.WALLET_PAGE,
        title = texts.text("wallet.title"),
        texts = texts,
        onBack = config.onBack,
        overlay = {
            state.toast?.let { toast ->
                WalletToast(toast.message(texts), toast.id, error = true) { controller.consumeToast(toast.id) }
            }
        },
    ) {
        val summary = state.summary
        when {
            summary != null -> {
                val pullState = rememberPullToRefreshState()
                Box(
                    modifier =
                        Modifier.fillMaxSize().pullToRefresh(
                            isRefreshing = state.refreshing,
                            state = pullState,
                            enabled = !blocked,
                            onRefresh = { controller.refresh() },
                        ),
                ) {
                    val view = WalletSummaryView.of(summary, masked, config.deps.clock(), texts)
                    WalletContent(controller, state, view, masked, texts, config) { key -> help = key }
                    PullToRefreshDefaults.Indicator(
                        state = pullState,
                        isRefreshing = state.refreshing,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
            }

            state.phase == WalletSnapshot.ERROR -> {
                Box(modifier = Modifier.fillMaxWidth().testTag(WalletTags.WALLET_ERROR)) {
                    ErrorState(
                        title = texts.text("wallet.load_failed"),
                        message = state.error?.message(texts),
                        retry = StateAction(texts.text("wallet.retry"), enabled = !blocked) { controller.refresh() },
                    )
                }
            }

            else -> {
                WalletSkeleton(texts)
            }
        }
    }
    help?.let { key ->
        HelpSheet(
            texts,
            key,
            helpOrigin,
            bodyStyle = CouliTypography.footnote,
            okVariant = CouliButtonVariant.SECONDARY,
        ) { help = null }
    }
}

/**
 * 钱包内容（Wallet 画板）：断网条紧贴导航栏、不随内容滚动；其下可滚动内容 上 8、左右 16、块间 12：风控条 → 刷新失败条 → 主卡（底部冻结中 /
 * 已提现两格）→ 待抵扣 / 提现关闭警告条（G-W06，主卡外下方）→ 预估两列 → 入口行（列数 = 入口数）。
 */
@Suppress("LongParameterList")
@Composable
private fun WalletContent(
    controller: WalletController,
    state: WalletSnapshot,
    view: WalletSummaryDisplay,
    masked: Boolean,
    texts: WalletTexts,
    config: WalletPageConfig,
    onHelp: (String) -> Unit,
) {
    val version = state.version
    val mask = AmountMask(hidden = masked, mask = texts.text(WalletUi.MASK_KEY), spoken = texts.text(WalletUi.HIDDEN_SPOKEN_KEY))
    Column(modifier = Modifier.fillMaxSize()) {
        // 断网 / 旧数据条（WalletStale）：断网图标、警告字色、礼貌播报；最先显示
        state.staleAt?.let { at ->
            StatusBar(
                text =
                    texts.format(
                        if (state.staleNetwork) "net.stale_data" else "wallet.updated_at",
                        mapOf("time" to CouliFormat.timeListPast(at, config.deps.clock())),
                    ),
                tag = WalletTags.WALLET_STALE,
                icon = CouliIcons.wifiOff,
                textStyle = CouliTypography.footnote,
                // 画板：条下外边距 8 + 内容上内边距 8，与主卡（或风控条）相隔 16
                modifier = Modifier.padding(start = CouliSpacing.space4, end = CouliSpacing.space4, bottom = CouliSpacing.space2),
            )
        }
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = CouliSpacing.space4,
                        end = CouliSpacing.space4,
                        top = CouliSpacing.space2,
                        bottom = CouliSpacing.space4,
                    ),
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
        ) {
            // 风控暂停提示条（G10）：可点，经 Router 打开 RiskNotice；不显示原因码原文与恢复时间（G-W03 无字段）
            if (view.riskBanner.show) {
                val text = texts.format(view.riskBanner.textKey.orEmpty(), mapOf("reason" to view.riskBanner.reasonText.orEmpty()))
                StatusBar(
                    text = text,
                    secondary = texts.text("wallet.risk_hint"),
                    tag = WalletTags.WALLET_RISK,
                    onClick = controller::openRiskNotice,
                )
            }
            state.error?.let { error ->
                val blocked = controller.blocked.collectAsStateWithLifecycle().value
                RetryErrorBar(error, texts, WalletTags.WALLET_REFRESH_ERROR, retryEnabled = !blocked) { controller.refresh() }
            }
            Hero(controller, state, view, mask, texts, version, onHelp)
            WalletWarnings(view, masked, texts)
            EstimateCard(view, masked, mask, texts, onHelp)
            val entries =
                buildList {
                    add(entry(CouliIcons.orders, texts.text("wallet.entry.orders"), WalletTags.ENTRY_ORDERS, controller::openOrders))
                    add(entry(CouliIcons.ledger, texts.text("wallet.entry.ledger"), WalletTags.ENTRY_LEDGER, controller::openLedger))
                    if (controller.earningsVisible()) {
                        val label = texts.text("wallet.entry.earnings")
                        add(entry(CouliIcons.barChart, label, WalletTags.ENTRY_EARNINGS, controller::openEarnings))
                    }
                }
            EntryGrid(items = entries, columns = entries.size, topPadding = CouliSpacing.space2)
        }
    }
}

private fun entry(
    icon: ImageVector,
    label: String,
    tag: String,
    onClick: () -> Unit,
): EntryItem = EntryItem(icon = icon, label = label, modifier = Modifier.testTag(tag), onClick = onClick)

@Suppress("LongParameterList")
@Composable
private fun Hero(
    controller: WalletController,
    state: WalletSnapshot,
    view: WalletSummaryDisplay,
    mask: AmountMask,
    texts: WalletTexts,
    version: Long,
    onHelp: (String) -> Unit,
) {
    val label = texts.text("wallet.withdrawable")
    // 断网旧数据（stale）与读取失败的旧数据都不是「当前」：【提现】置灰（共性第 30、32 条）
    val enabled = view.withdrawEnabled && state.staleAt == null && state.error == null
    WalletHeroCard(
        texts =
            WalletHeroTexts(
                label = label,
                amount = CouliFormat.money(view.hero.fen),
                amountSpoken = label + PriceTerms.SPOKEN_PAUSE + CouliFormat.moneySpoken(view.hero.fen),
                withdraw = texts.text("wallet.withdraw"),
                records = texts.text("wallet.records"),
                hideLabel = texts.text(WalletUi.MASK_HIDE_KEY),
                showLabel = texts.text(WalletUi.MASK_SHOW_KEY),
                hiddenSpoken = label + PriceTerms.SPOKEN_PAUSE + texts.text(WalletUi.HIDDEN_SPOKEN_KEY),
            ),
        onWithdraw = { controller.withdraw(version) },
        amountHidden = mask.hidden,
        hiddenMask = view.hero.maskedText.orEmpty(),
        onToggleHidden = controller::toggleMask,
        onRecords = controller::openRecords,
        withdrawEnabled = enabled,
        // 画板【提现】最小宽 96（W3）；置灰判定 enabled 不变
        withdrawModifier = Modifier.testTag(WalletTags.WALLET_WITHDRAW).widthIn(min = CouliSpacing.space12 * 2),
        contentGap = CouliSpacing.space4,
        compactHeader = true,
        modifier = Modifier.testTag(WalletTags.WALLET_HERO),
    ) {
        // 冻结中 / 已提现在主卡底部（W1）：白字，标签 14、数值 17/600；冻结中带白色问号
        AmountGrid(
            cells =
                listOf(
                    cell(texts, "wallet.frozen", view.frozen, help = "frozen") { onHelp("frozen") },
                    cell(texts, "wallet.withdrawn", view.withdrawn),
                ),
            modifier = Modifier.testTag(WalletTags.WALLET_FROZEN),
            tone = AmountGridTone.ON_SOLID,
            valueSize = AmountValueSize.BODY,
            mask = mask,
            compactHelpRow = true,
        )
    }
}

/** 主卡下方的警告条（G-W06，WalletNegative-v2 / WalletStates ①）：待抵扣两行（首行 600 + error.30302）、提现关闭一行（500）。 */
@Composable
private fun WalletWarnings(
    view: WalletSummaryDisplay,
    masked: Boolean,
    texts: WalletTexts,
) {
    view.negativeLine?.let { line ->
        StatusBar(
            text = texts.format("wallet.negative", mapOf("amount" to line.render())),
            secondary = texts.text(NEGATIVE_REASON_KEY),
            tag = WalletTags.WALLET_NEGATIVE,
            spoken = if (masked) texts.format("wallet.negative", mapOf("amount" to texts.text(WalletUi.HIDDEN_SPOKEN_KEY))) else null,
            live = false,
        )
    }
    view.withdrawReasonKey?.let { key ->
        StatusBar(
            text = texts.text(key),
            tag = WalletTags.WALLET_DISABLED_REASON,
            textStyle = CouliTextStyles.footnoteMedium,
            live = false,
        )
    }
}

/**
 * 预估区（Wallet 画板）：两列——左「钟表 + 预估收益 + 问号」返利色 22/600，下附「其中已收货 ¥x」12（为 0 照常显示，G-W04）；右「最早预计 / {M} 月结算」
 * 或 credit_overdue 时「结算进度 / 入账核对中」（G-W07），22/600 墨棕；没有月份也不逾期时只有左列（不造月份）。其下暂缓条与说明句。
 */
@Composable
private fun EstimateCard(
    view: WalletSummaryDisplay,
    masked: Boolean,
    mask: AmountMask,
    texts: WalletTexts,
    onHelp: (String) -> Unit,
) {
    WalletCard(modifier = Modifier.testTag(WalletTags.WALLET_ESTIMATED)) {
        val estimated =
            AmountCell.of(
                label = texts.text("wallet.estimated"),
                value = AmountValue.Amount(view.estimated.fen),
                isRebate = true,
                note = texts.format("wallet.received", mapOf("amount" to CouliFormat.money(view.receivedLine.fen))),
                labelIcon = CouliIcons.clock,
                helpLabel = texts.text("wallet.help.estimated_label"),
                onHelp = { onHelp("estimated") },
                hiddenNote = texts.format("wallet.received", mapOf("amount" to texts.text(WalletUi.MASK_KEY))),
                // 隐藏时「其中已收货」仍是独立一项：读「其中已收货 金额已隐藏」，不读遮罩字符（APP-09v r1 S2-2）
                hiddenNoteSpoken = texts.format("wallet.received", mapOf("amount" to texts.text(WalletUi.HIDDEN_SPOKEN_KEY))),
            )
        val progress =
            when {
                view.overdueNote -> {
                    texts.text("wallet.settle_progress") to texts.text("wallet.overdue")
                }

                view.earliestMonth != null -> {
                    texts.text("wallet.earliest_label") to texts.format("wallet.earliest", mapOf("month" to view.earliestMonth.orEmpty()))
                }

                else -> {
                    null
                }
            }
        EstimateColumns(estimated, progress, mask)
        view.pausedLine?.let { line ->
            InfoStrip(
                text = texts.format("wallet.paused", mapOf("amount" to line.render())),
                spoken = if (masked) texts.format("wallet.paused", mapOf("amount" to texts.text(WalletUi.HIDDEN_SPOKEN_KEY))) else null,
                helpLabel = texts.text("wallet.help.pending_paused_label"),
                onHelp = { onHelp("pending_paused") },
                modifier = Modifier.testTag(WalletTags.WALLET_PAUSED),
                compactHelp = true,
            )
        }
        Text(text = texts.text("wallet.estimate_note"), style = CouliTypography.caption, color = CouliColors.textSecondary)
    }
}

/**
 * 预估两列：左格走 [AmountGrid]（金额遮罩、问号、附注）；右格「最早预计 / 结算进度」是非金额文字、不随遮罩，单独排版（标签 12 次文字、值 22/600 墨棕，
 * 外层一个读屏元素「标签，值」）。与 [AmountGrid] 同一换行规则：系统字号 200% 或任一列放不下最宽一段时上下排（左格在上）。
 */
@Composable
private fun EstimateColumns(
    estimated: AmountCell,
    progress: Pair<String, String>?,
    mask: AmountMask,
) {
    if (progress == null) {
        AmountGrid(cells = listOf(estimated), mask = mask, compactHelpRow = true)
        return
    }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val gap = CouliSpacing.space3
        val columnPx = with(density) { ((maxWidth - gap) / 2).roundToPx() }
        val shown = if (mask.hidden) mask.mask else estimated.value
        val widest = listOf(shown, progress.second).maxOf { measurer.measure(it, CouliTextStyles.amountTitle).size.width }
        if (density.fontScale >= LARGE_FONT_SCALE || widest > columnPx) {
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                AmountGrid(cells = listOf(estimated), mask = mask, compactHelpRow = true)
                ProgressCell(progress, Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                Box(modifier = Modifier.weight(1f)) { AmountGrid(cells = listOf(estimated), mask = mask, compactHelpRow = true) }
                ProgressCell(progress, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ProgressCell(
    progress: Pair<String, String>,
    modifier: Modifier,
) {
    val (label, value) = progress
    Column(
        modifier = modifier.clearAndSetSemantics { contentDescription = label + PriceTerms.SPOKEN_PAUSE + value },
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1 / 2),
    ) {
        Text(text = label, style = CouliTypography.caption, color = CouliColors.textSecondary)
        WholeNumberText(text = value, style = CouliTextStyles.amountTitle, color = CouliColors.textPrimary)
    }
}

private fun MaskableAmount.render(): String = maskedText ?: CouliFormat.money(fen)

private fun cell(
    texts: WalletTexts,
    labelKey: String,
    amount: MaskableAmount,
    help: String? = null,
    onHelp: (() -> Unit)? = null,
): AmountCell =
    AmountCell.of(
        label = texts.text(labelKey),
        value = AmountValue.Amount(amount.fen),
        helpLabel = help?.let { texts.text("wallet.help.${it}_label") },
        onHelp = onHelp,
    )

/**
 * 加载骨架（无稿，按新结构）：主卡块（含底部两格的高度）→ 预估两列块 → 入口行；读屏只读一次「加载中」。
 */
@Composable
private fun WalletSkeleton(texts: WalletTexts) {
    val loading = texts.text("wallet.loading")
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = CouliSpacing.space4, end = CouliSpacing.space4, top = CouliSpacing.space2)
                .clearAndSetSemantics { contentDescription = loading },
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        // 主卡：白卡、内边距 20、行距 16；首行（标签 / 提现记录）、金额行（金额 / 按钮）、底部两格
        Column(
            modifier = Modifier.fillMaxWidth().couliCard().padding(CouliSpacing.space5),
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space4),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Skeleton(Modifier.fillMaxWidth(SKELETON_LABEL).height(CouliSpacing.space5))
                Skeleton(Modifier.fillMaxWidth(SKELETON_LINK).height(CouliSpacing.space5))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Skeleton(Modifier.fillMaxWidth(SKELETON_AMOUNT).height(CouliSpacing.space10))
                Skeleton(Modifier.width(CouliSpacing.space12 * 2).height(CouliSpacing.space12))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3)) {
                repeat(2) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
                        Skeleton(Modifier.fillMaxWidth(SKELETON_LABEL).height(CouliSpacing.space4))
                        Skeleton(Modifier.fillMaxWidth(SKELETON_VALUE).height(CouliSpacing.space5))
                    }
                }
            }
        }
        WalletCard {
            Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3)) {
                repeat(2) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
                        Skeleton(Modifier.fillMaxWidth(SKELETON_LABEL).height(CouliSpacing.space4))
                        Skeleton(Modifier.fillMaxWidth(SKELETON_VALUE).height(CouliSpacing.space6 + CouliSpacing.space1))
                        Skeleton(Modifier.fillMaxWidth(SKELETON_VALUE).height(CouliSpacing.space4))
                    }
                }
            }
            Skeleton(Modifier.fillMaxWidth().height(CouliSpacing.space4))
        }
        Row(
            modifier = Modifier.fillMaxWidth().couliCard().padding(CouliSpacing.space2),
            horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
        ) {
            repeat(2) {
                Column(
                    modifier = Modifier.weight(1f).heightIn(min = CouliSizes.gridCellMinHeight).padding(vertical = CouliSpacing.space2),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1, Alignment.CenterVertically),
                ) {
                    Skeleton(Modifier.size(CouliSizes.iconLarge))
                    Skeleton(Modifier.fillMaxWidth(SKELETON_LABEL).height(CouliSpacing.space4))
                }
            }
        }
    }
}

/** 待抵扣警告条次行：error.30302（契约原文，G-W06）。 */
private const val NEGATIVE_REASON_KEY = "error.30302"

/** 与组件库 AmountGrid 相同的「系统字号 200% 一律纵排」阈值（adaptation-a11y-spec §2）。 */
private const val LARGE_FONT_SCALE = 2f
private const val SKELETON_LABEL = 0.4f
private const val SKELETON_LINK = 0.3f
private const val SKELETON_AMOUNT = 0.45f
private const val SKELETON_VALUE = 0.7f
