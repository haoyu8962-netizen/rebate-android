package com.couli.feature.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.couli.core.designsystem.AmountCell
import com.couli.core.designsystem.AmountGrid
import com.couli.core.designsystem.AmountMask
import com.couli.core.designsystem.AmountValue
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliFormat
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliSpacing
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
    help?.let { key -> HelpSheet(texts, key, helpOrigin) { help = null } }
}

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
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(CouliSpacing.space4),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        // 风控暂停提示条（G10）：可点，经 Router 打开 RiskNotice；不显示原因码原文与恢复时间
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
        state.staleAt?.let { at ->
            StatusBar(
                text =
                    texts.format(
                        if (state.staleNetwork) "net.stale_data" else "wallet.updated_at",
                        mapOf("time" to CouliFormat.timeListPast(at, config.deps.clock())),
                    ),
                tag = WalletTags.WALLET_STALE,
            )
        }
        Hero(controller, state, view, masked, texts, version)
        WalletCard(modifier = Modifier.testTag(WalletTags.WALLET_FROZEN)) {
            AmountGrid(
                cells =
                    listOf(
                        cell(texts, "wallet.frozen", view.frozen, help = "frozen") { onHelp("frozen") },
                        cell(texts, "wallet.withdrawn", view.withdrawn),
                    ),
                mask = mask,
            )
        }
        EstimateCard(view, masked, mask, texts, onHelp)
        val entries =
            buildList {
                add(EntryItem(CouliIcons.orders, texts.text("wallet.entry.orders"), controller::openOrders))
                add(EntryItem(CouliIcons.ledger, texts.text("wallet.entry.ledger"), controller::openLedger))
                if (controller.earningsVisible()) {
                    add(
                        EntryItem(CouliIcons.trend, texts.text("wallet.entry.earnings"), controller::openEarnings),
                    )
                }
            }
        EntryGrid(items = entries)
    }
}

@Suppress("LongParameterList")
@Composable
private fun Hero(
    controller: WalletController,
    state: WalletSnapshot,
    view: WalletSummaryDisplay,
    masked: Boolean,
    texts: WalletTexts,
    version: Long,
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
        amountHidden = masked,
        hiddenMask = view.hero.maskedText.orEmpty(),
        onToggleHidden = controller::toggleMask,
        onRecords = controller::openRecords,
        withdrawEnabled = enabled,
        withdrawModifier = Modifier.testTag(WalletTags.WALLET_WITHDRAW),
        modifier = Modifier.testTag(WalletTags.WALLET_HERO),
    ) {
        view.negativeLine?.let { line ->
            Text(
                text = texts.format("wallet.negative", mapOf("amount" to line.render())),
                style = CouliTypography.footnote,
                color = CouliColors.rebateSolidText,
                modifier = Modifier.testTag(WalletTags.WALLET_NEGATIVE),
            )
        }
        view.withdrawReasonKey?.let { key ->
            Text(
                text = texts.text(key),
                style = CouliTypography.footnote,
                color = CouliColors.rebateSolidText,
                modifier = Modifier.testTag(WalletTags.WALLET_DISABLED_REASON),
            )
        }
    }
}

@Composable
private fun EstimateCard(
    view: WalletSummaryDisplay,
    masked: Boolean,
    mask: AmountMask,
    texts: WalletTexts,
    onHelp: (String) -> Unit,
) {
    WalletCard(modifier = Modifier.testTag(WalletTags.WALLET_ESTIMATED)) {
        AmountGrid(cells = listOf(cell(texts, "wallet.estimated", view.estimated, help = "estimated") { onHelp("estimated") }), mask = mask)
        // 其中已收货（为 0 照常显示 ¥0）、最早预计 {月份} 结算、入账核对中、其中 {x} 暂缓入账：三项分开，不出现「待入账」「累计」
        Text(
            text = texts.format("wallet.received", mapOf("amount" to view.receivedLine.render())),
            style = CouliTypography.footnote,
            color = CouliColors.textPrimary,
            modifier = Modifier.testTag(WalletTags.WALLET_RECEIVED).spoken(masked, texts, "wallet.received"),
        )
        view.earliestMonth?.let { month ->
            Text(
                text = texts.text("wallet.earliest_label") + " " + texts.format("wallet.earliest", mapOf("month" to month)),
                style = CouliTypography.footnote,
                color = CouliColors.textPrimary,
                modifier = Modifier.testTag(WalletTags.WALLET_EARLIEST),
            )
        }
        if (view.overdueNote) {
            Text(
                text = texts.text("wallet.overdue"),
                style = CouliTypography.footnote,
                color = CouliColors.textPrimary,
                modifier = Modifier.testTag(WalletTags.WALLET_OVERDUE),
            )
        }
        view.pausedLine?.let { line ->
            InfoStrip(
                text = texts.format("wallet.paused", mapOf("amount" to line.render())),
                spoken = if (masked) texts.format("wallet.paused", mapOf("amount" to texts.text(WalletUi.HIDDEN_SPOKEN_KEY))) else null,
                helpLabel = texts.text("wallet.help.pending_paused_label"),
                onHelp = { onHelp("pending_paused") },
                modifier = Modifier.testTag(WalletTags.WALLET_PAUSED),
            )
        }
        Text(text = texts.text("wallet.estimate_note"), style = CouliTypography.caption, color = CouliColors.textSecondary)
    }
}

/** 金额隐藏时说明行的读屏：「其中已收货 金额已隐藏」（可见文字是遮罩）。 */
private fun Modifier.spoken(
    masked: Boolean,
    texts: WalletTexts,
    key: String,
): Modifier =
    if (masked) {
        clearAndSetSemantics { contentDescription = texts.format(key, mapOf("amount" to texts.text(WalletUi.HIDDEN_SPOKEN_KEY))) }
    } else {
        this
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

@Composable
private fun WalletSkeleton(texts: WalletTexts) {
    val loading = texts.text("wallet.loading")
    Column(
        modifier = Modifier.fillMaxWidth().padding(CouliSpacing.space4).clearAndSetSemantics { contentDescription = loading },
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        Skeleton(Modifier.fillMaxWidth().padding(vertical = CouliSpacing.space12))
        Skeleton(Modifier.fillMaxWidth().padding(vertical = CouliSpacing.space8))
        Skeleton(Modifier.fillMaxWidth().padding(vertical = CouliSpacing.space8))
    }
}
