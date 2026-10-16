@file:Suppress("MatchingDeclarationName") // ViewModel 与页面放在一起

package com.couli.feature.wallet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonSize
import com.couli.core.designsystem.CouliButtonVariant
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliFormat
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.EmptyState
import com.couli.core.designsystem.EndWrapRow
import com.couli.core.designsystem.ErrorState
import com.couli.core.designsystem.PriceTerms
import com.couli.core.designsystem.Skeleton
import com.couli.core.designsystem.StateAction
import com.couli.core.designsystem.couliCard
import com.couli.core.wallet.TradeNoRow
import com.couli.core.wallet.WalletRoutes
import com.couli.core.wallet.WalletTexts
import com.couli.core.wallet.Withdrawal
import com.couli.core.wallet.WithdrawalText
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.JsonPrimitive

class WithdrawRecordsViewModel(
    config: WalletPageConfig,
) : ViewModel() {
    val resume = ResumeTracker()

    val controller =
        WithdrawRecordsController(
            config.deps,
            viewModelScope,
            (config.params[WalletRoutes.WITHDRAWAL_ID] as? JsonPrimitive)?.takeIf { it.isString }?.content,
        )

    init {
        config.hooks.bindLeave(controller::leave)
        config.hooks.bindSession(controller::onSessionChanged)
        // 统一的页面恢复入口（FU-AND-0717 第 3 轮）
        config.hooks.bindIdentity(controller::onIdentity)
        controller.load()
    }

    override fun onCleared() {
        controller.leave()
        super.onCleared()
    }
}

/**
 * 提现记录（WithdrawRecords 路由，`withdrawal_id?`；设计稿 WithdrawRecords、WithdrawRecordsPending / Paying / Paid / Failed / Empty、
 * WithdrawAbandonCompleted）：
 * 按月分组（created_at 在 +08:00 下取月份）；列表卡「提现 {amount}」+ 状态 +「{日期} · {通道} {脱敏串}」+ 副文案（G08）；整卡展开 / 收起（读屏名
 * 「展开详情 / 收起详情」）；详情明细、交易流水号【复制】、「不能自行撤销」+【联系客服】、【修改收款账号】。金额不受隐藏开关影响（G23）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WithdrawRecordsScreen(config: WalletPageConfig) {
    val model: WithdrawRecordsViewModel = viewModel { WithdrawRecordsViewModel(config) }
    val controller = model.controller
    val state = controller.state.collectAsStateWithLifecycle().value
    val blocked = controller.blocked.collectAsStateWithLifecycle().value
    val texts = config.deps.texts
    OnReturn(model.resume) { controller.refresh() }
    WalletPage(
        tag = WalletTags.RECORDS_PAGE,
        title = texts.text("withdraw_records.title"),
        texts = texts,
        onBack = config.onBack,
        overlay = {
            state.toast?.let { toast ->
                WalletToast(toast.message(texts), toast.id, error = toast.assertive) { controller.consumeToast(toast.id) }
            }
        },
    ) {
        when (state.phase) {
            RecordsSnapshot.CONTENT, RecordsSnapshot.EMPTY -> {
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
                    RecordsList(controller, state, texts, config)
                    PullToRefreshDefaults.Indicator(
                        state = pullState,
                        isRefreshing = state.refreshing,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
            }

            RecordsSnapshot.ERROR -> {
                Box(modifier = Modifier.fillMaxWidth().testTag(WalletTags.RECORDS_ERROR)) {
                    ErrorState(
                        title = texts.text("wallet.load_failed"),
                        message = state.error?.message(texts),
                        retry = StateAction(texts.text("wallet.retry"), enabled = !blocked) { controller.refresh() },
                    )
                }
            }

            else -> {
                Column(modifier = Modifier.padding(CouliSpacing.space4), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3)) {
                    repeat(SKELETONS) { Skeleton(Modifier.fillMaxWidth().padding(vertical = CouliSpacing.space8)) }
                }
            }
        }
    }
}

@Composable
private fun RecordsList(
    controller: WithdrawRecordsController,
    state: RecordsSnapshot,
    texts: WalletTexts,
    config: WalletPageConfig,
) {
    val slaText =
        (
            config.deps.env
                .configData()
                ?.get("texts") as? kotlinx.serialization.json.JsonObject
        )?.get(WithdrawController.SLA_KEY)
            ?.let { it as? JsonPrimitive }
            ?.takeIf { it.isString }
            ?.content
    val now = config.deps.clock()
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(WalletTags.RECORDS_LIST),
        contentPadding = PaddingValues(CouliSpacing.space4),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        // 已有旧列表时刷新失败（共性第 30 条）：独立的错误与【重试】
        state.error?.let { error ->
            item(key = "refresh_error") {
                val blocked = controller.blocked.collectAsStateWithLifecycle().value
                RetryErrorBar(error, texts, WalletTags.RECORDS_REFRESH_ERROR, retryEnabled = !blocked && !state.refreshing) {
                    controller.refresh()
                }
            }
        }
        if (state.phase == RecordsSnapshot.EMPTY) {
            item {
                Box(modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }.testTag(WalletTags.RECORDS_EMPTY)) {
                    EmptyState(
                        title = texts.text("withdraw_records.empty.title"),
                        message = texts.text("withdraw_records.empty.desc"),
                        icon = CouliIcons.wallet,
                    )
                }
            }
            return@LazyColumn
        }
        val groups = state.items.groupBy { monthOf(it.createdAtMillis) }
        groups.forEach { (month, items) ->
            item(key = "month_${month.first}_${month.second}") {
                Text(
                    text =
                        texts.format(
                            "withdraw_records.month",
                            mapOf("year" to month.first.toString(), "month" to month.second.toString()),
                        ),
                    style = CouliTextStyles.footnoteSemibold,
                    color = CouliColors.textSecondary,
                    modifier = Modifier.semantics { heading() },
                )
            }
            items(items, key = { it.withdrawalId }) { item ->
                RecordCard(controller, state, item, texts, slaText, now)
            }
        }
        item(key = "footer") {
            when {
                state.nextCursor != null -> {
                    LaunchedEffect(state.items.size) { controller.loadMore() }
                    val error = state.moreError
                    if (error != null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            ErrorBar(error, texts, WalletTags.RECORDS_ERROR)
                            CouliButton(
                                text = texts.text("withdraw_records.load_more"),
                                onClick = controller::loadMore,
                                variant = CouliButtonVariant.TEXT,
                                size = CouliButtonSize.COMPACT,
                                modifier = Modifier.testTag(WalletTags.RECORDS_LOAD_MORE),
                            )
                        }
                    } else {
                        Skeleton(Modifier.fillMaxWidth().padding(vertical = CouliSpacing.space4))
                    }
                }

                else -> {
                    Text(
                        text = texts.text("withdraw_records.end"),
                        style = CouliTypography.caption,
                        color = CouliColors.textSecondary,
                        modifier = Modifier.fillMaxWidth().padding(vertical = CouliSpacing.space2).testTag(WalletTags.RECORDS_END),
                    )
                }
            }
        }
    }
}

@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod", "ComplexCondition") // 一张卡的摘要、明细、流水号与三个入口
@Composable
private fun RecordCard(
    controller: WithdrawRecordsController,
    state: RecordsSnapshot,
    item: Withdrawal,
    texts: WalletTexts,
    slaText: String?,
    now: Long,
) {
    val version = state.version
    val expanded = item.withdrawalId in state.expanded
    val detail = state.details[item.withdrawalId]
    val display = WithdrawalText.of(detail ?: item, slaText, texts)
    val lines = WithdrawalText.render(display, texts)
    val amount = CouliFormat.money(item.amountFen)
    val title = texts.format("withdraw_records.item", mapOf("amount" to amount))
    val status = texts.text(display.titleKey)
    val channel =
        texts.format(
            "withdraw_records.channel_line",
            mapOf(
                "date" to CouliFormat.timeListPast(item.createdAtMillis, now),
                "account" to "${item.payoutChannelName} ${item.maskedAccount}",
            ),
        )
    val toggleLabel = texts.text(if (expanded) "withdraw_records.collapse" else "withdraw_records.expand")
    Column(
        modifier = Modifier.fillMaxWidth().couliCard().testTag(WalletTags.RECORD_PREFIX + item.withdrawalId),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        // 整卡（摘要部分）是一个目标：展开 / 收起
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClickLabel = toggleLabel) { controller.toggle(version, item.withdrawalId) }
                    .padding(CouliSpacing.space4)
                    .semantics(mergeDescendants = true) {
                        stateDescription = toggleLabel
                        contentDescription =
                            listOf(title, status, spokenMask(texts, channel)).plus(lines).joinToString(PriceTerms.SPOKEN_PAUSE)
                    },
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
        ) {
            EndWrapRow(
                modifier = Modifier.fillMaxWidth(),
                start = {
                    Text(
                        text = if (expanded) texts.text("withdraw_detail.amount") + " " + amount else title,
                        style = CouliTextStyles.bodySemibold,
                        color = CouliColors.textPrimary,
                    )
                },
                end = { Text(text = status, style = CouliTextStyles.footnoteMedium, color = CouliColors.textPrimary) },
            )
            if (!expanded) Text(text = channel, style = CouliTypography.caption, color = CouliColors.textSecondary)
            lines.forEach { Text(text = it, style = CouliTypography.footnote, color = CouliColors.textSecondary) }
        }
        if (expanded) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(start = CouliSpacing.space4, end = CouliSpacing.space4, bottom = CouliSpacing.space4)
                        .testTag(WalletTags.RECORD_DETAIL_PREFIX + item.withdrawalId),
                verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
            ) {
                DetailRows(detail ?: item, display.netKey, texts)
                val tradeNo = detail?.let { TradeNoRow.copyText(it.status, it.channelOrderId) }
                if (tradeNo != null) {
                    EndWrapRow(
                        modifier = Modifier.fillMaxWidth(),
                        start = {
                            Text(
                                text = texts.text("withdraw_detail.trade_no") + " " + tradeNo,
                                style = CouliTypography.footnote,
                                color = CouliColors.textPrimary,
                                modifier = Modifier.testTag(WalletTags.RECORD_TRADE_NO),
                            )
                        },
                        end = {
                            CouliButton(
                                text = texts.text("withdraw_detail.copy"),
                                onClick = { controller.copy(version, item.withdrawalId) },
                                variant = CouliButtonVariant.TEXT,
                                size = CouliButtonSize.COMPACT,
                                accessibilityLabel = texts.text("withdraw_detail.copy") + texts.text("withdraw_detail.trade_no"),
                                modifier = Modifier.testTag(WalletTags.RECORD_COPY),
                            )
                        },
                    )
                }
                if (display.showChangeAccount) {
                    CouliButton(
                        text = texts.text("withdraw_detail.change_account"),
                        onClick = { controller.changeAccount(version, item.withdrawalId) },
                        size = CouliButtonSize.COMPACT,
                        modifier = Modifier.testTag(WalletTags.RECORD_CHANGE_ACCOUNT),
                    )
                }
                val reviewing = WithdrawalText.isReviewing(item.status)
                if (display.knownChannel && (reviewing || item.status == "PAYING" || item.status == "FAILED")) {
                    EndWrapRow(
                        modifier = Modifier.fillMaxWidth(),
                        start = {
                            Text(
                                text = texts.text(if (reviewing) "withdraw.irrevocable" else "withdraw_detail.doubt"),
                                style = CouliTypography.caption,
                                color = CouliColors.textSecondary,
                            )
                        },
                        end = {
                            CouliButton(
                                text = texts.text("withdraw_detail.cs"),
                                onClick = controller::contact,
                                variant = CouliButtonVariant.TEXT,
                                size = CouliButtonSize.COMPACT,
                                modifier = Modifier.testTag(WalletTags.RECORD_CS),
                            )
                        },
                    )
                }
            }
        }
    }
}

/** 详情明细：申请金额、手续费、预计 / 实际到账（按状态）、代扣个税（tax_fen>0）、收款账号、申请时间（BR-TEXT-11）。 */
@Composable
private fun DetailRows(
    item: Withdrawal,
    netKey: String?,
    texts: WalletTexts,
) {
    // (标签, 显示值, 读屏值)：金额读「12.34 元」，脱敏号读「星号」
    fun money(fen: Long) = Triple(CouliFormat.money(fen), CouliFormat.moneySpoken(fen), "")
    val rows =
        buildList {
            add(texts.text("withdraw_detail.amount") to money(item.amountFen))
            if (netKey != null) add(texts.text("withdraw_detail.fee") to money(item.feeFen))
            if (item.taxFen > 0) add(texts.text("withdraw_detail.tax") to money(item.taxFen))
            if (netKey != null) {
                val label =
                    if (netKey ==
                        WithdrawalText.NET_ACTUAL
                    ) {
                        "withdraw_detail.net_label.actual"
                    } else {
                        "withdraw_detail.net_label.expected"
                    }
                add(texts.text(label) to money(item.netFen))
            }
            val account = "${item.payoutChannelName} ${item.maskedAccount}"
            add(texts.text("withdraw_detail.payout") to Triple(account, spokenMask(texts, account), ""))
            val time = CouliFormat.timeMinute(item.createdAtMillis)
            add(texts.text("withdraw_detail.created") to Triple(time, time, ""))
        }
    rows.forEach { (label, shown) ->
        val (value, spoken) = shown.first to shown.second
        Row(
            modifier =
                Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                    contentDescription =
                        label + PriceTerms.SPOKEN_PAUSE + spoken
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
        ) {
            Text(text = label, style = CouliTypography.footnote, color = CouliColors.textSecondary)
            Text(text = value, style = CouliTypography.footnote, color = CouliColors.textPrimary, modifier = Modifier.weight(1f))
        }
    }
}

private val SHANGHAI: ZoneId = ZoneId.of("Asia/Shanghai")

/** created_at 在 +08:00 下的（年, 月）。 */
private fun monthOf(millis: Long): Pair<Int, Int> = Instant.ofEpochMilli(millis).atZone(SHANGHAI).let { it.year to it.monthValue }

private const val SKELETONS = 3
