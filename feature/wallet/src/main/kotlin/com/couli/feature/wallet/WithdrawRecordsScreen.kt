@file:Suppress("MatchingDeclarationName") // ViewModel 与页面放在一起

package com.couli.feature.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonSize
import com.couli.core.designsystem.CouliButtonVariant
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.EmptyState
import com.couli.core.designsystem.ErrorState
import com.couli.core.designsystem.StateAction
import com.couli.core.wallet.WalletRoutes
import com.couli.core.wallet.WalletTexts
import kotlinx.coroutines.flow.filter
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
 * 按月分组（created_at 在 +08:00 下取月份）；一月一张卡，收起行「提现 {amount}」+ 状态胶囊 +「{日期} · {通道} {脱敏串}」+ 副文案首句（G08、G-W08）；
 * 整行展开 / 收起（读屏名「展开详情 / 收起详情」，读屏描述含完整副文案）；展开项单独成卡：状态块、【修改收款账号】、明细、交易流水号【复制】、
 * 「不能自行撤销」+【联系客服】（APP-09v-b，见 WithdrawRecordRow / WithdrawRecordDetail）。金额不受隐藏开关影响（G23）。
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
                // 首次加载骨架（W35）：与收起行同结构的 3 行，位置同列表（上 8、左右 16）
                Column(modifier = Modifier.padding(start = CouliSpacing.space4, top = CouliSpacing.space2, end = CouliSpacing.space4)) {
                    RecordSkeletonCard(SKELETONS, RECORDS_LOADING_TAG, texts.text("wallet.loading"))
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
    val focusId = (config.params[WalletRoutes.WITHDRAWAL_ID] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val listState = rememberLazyListState()
    val keeper = remember { RecordListKeeper() }
    val ctx = RecordRenderContext(controller, state, texts, slaText, config.deps.clock(), keeper)
    val sections = if (state.phase == RecordsSnapshot.EMPTY) emptyList() else recordSections(state.items, focusId)
    RecordPaging(controller, state, keeper)
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // 评审 r1 S2-1 / r2 S2：分段、记录内容（状态、原因、退回说明等决定行高的字段）、详情、展开集、SLA 文案、列表宽度
        // 任一变化，都按变化前「视口最上面的记录 + 偏移」恢复；ID / 月份 / 顺序不变而上方记录变高变矮也算
        val signature =
            listOf(
                sections.map { section -> section.key to section.items },
                slaText,
                state.details,
                state.expanded,
                maxWidth,
            )
        val lazyOffset = if (state.error != null) 1 else 0
        SideEffect { keeper.content(signature, sections, lazyOffset, listState) }
        LaunchedEffect(signature) { keeper.restore(listState) }
        RecordsLazyList(sections, ctx, listState)
    }
}

/**
 * 翻页触发（评审 r1 S1）：列表最后一条记录真实摆进视口（裁切后有高度）才调 [WithdrawRecordsController.loadMore]——与改版前「一记录一项」时
 * 末条可见即预组合页脚、页脚组合即翻页的时机一致；按月合段后页脚的屏外预组合不再触发。追加后末条换人、条数变化即重新等待；
 * 同一末条离开视口又回来会再调一次（与旧页脚重新进入组合相同）。是否真的发请求、去重、游标仍全由控制器决定。
 */
@Composable
private fun RecordPaging(
    controller: WithdrawRecordsController,
    state: RecordsSnapshot,
    keeper: RecordListKeeper,
) {
    val lastId = state.items.lastOrNull()?.withdrawalId
    if (state.phase != RecordsSnapshot.CONTENT || state.nextCursor == null || lastId == null) return
    LaunchedEffect(lastId, state.items.size) {
        snapshotFlow { keeper.visibleLast == lastId }.filter { it }.collect { controller.loadMore() }
    }
}

@Composable
private fun RecordsLazyList(
    sections: List<RecordSection>,
    ctx: RecordRenderContext,
    listState: LazyListState,
) {
    val controller = ctx.controller
    val state = ctx.state
    val texts = ctx.texts
    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { ctx.keeper.list(it) }
                .testTag(WalletTags.RECORDS_LIST),
        state = listState,
        // W18：上 8、左右 16、下 0
        contentPadding = PaddingValues(start = CouliSpacing.space4, top = CouliSpacing.space2, end = CouliSpacing.space4),
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
                // W31：流水图标、图示上留白 48（EmptyState 自带上内边距 24，这里补足其余）；仍在可下拉的列表里（共性第 82 条）
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = CouliSpacing.space12 - CouliSpacing.space6)
                            .semantics { liveRegion = LiveRegionMode.Polite }
                            .testTag(WalletTags.RECORDS_EMPTY),
                ) {
                    EmptyState(
                        title = texts.text("withdraw_records.empty.title"),
                        message = texts.text("withdraw_records.empty.desc"),
                        icon = CouliIcons.ledger,
                    )
                }
            }
            return@LazyColumn
        }
        // W20 / G-W08：定位置顶区单独一项；其余按原顺序把相邻同月的记录合成一项（月份标题 + 月卡 / 展开卡）
        sections.forEach { section ->
            item(key = section.key) { RecordSectionItem(section, ctx) }
        }
        item(key = "footer") {
            when {
                state.nextCursor != null -> {
                    // 翻页不在这里触发（评审 r1 S1）：页脚会被 Lazy 预组合，见 RecordPaging
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
                        // W35：加载更多骨架与收起行同结构
                        RecordSkeletonCard(1, RECORDS_LOADING_MORE_TAG, texts.text("wallet.loading"))
                    }
                }

                else -> {
                    Text(
                        text = texts.text("withdraw_records.end"),
                        style = CouliTypography.caption,
                        color = CouliColors.textSecondary,
                        // W30：居中
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = CouliSpacing.space2).testTag(WalletTags.RECORDS_END),
                    )
                }
            }
        }
    }
}

private const val SKELETONS = 3
