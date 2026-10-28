@file:Suppress("MatchingDeclarationName", "CyclomaticComplexMethod")

package com.couli.feature.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonVariant
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.ErrorState
import com.couli.core.designsystem.StateAction
import com.couli.core.me.account.AccountRaw
import com.couli.core.me.account.DeletionPresentation
import com.couli.core.me.account.DeletionSummary
import com.couli.core.me.account.DeletionView
import com.couli.core.wallet.stepup.StepUpSheet

/** 注销页配置（App 给）。 */
class DeleteAccountConfig(
    val deps: AccountDeps,
    val entryId: String?,
    val onBack: () -> Unit,
    val bindLeave: (() -> Unit) -> Unit = {},
)

/**
 * 注销页（DeleteAccount、DeleteAccountNegative、DeleteAccountCooling、DeleteAccountPending* 与待补稿各态；brief §2 第 2–4 项）。页面不读任何路由参数（G21）；
 * 控制器按身份（含作用域）保存在回退栈条目里，身份变了重建（S0）；回调捕获渲染时的控制器与版本。
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun DeleteAccountScreen(config: DeleteAccountConfig) {
    val deps = config.deps
    val identity by deps.env.accountIdentityFlow.collectAsStateWithLifecycle()
    // 来源只交接一次（G54）：配置重建沿用
    val source = rememberSaveable(config.entryId) { deps.env.takeDeletionSource(config.entryId) }
    val controller =
        rememberPageController(DeleteAccountController.ROUTE, identity, {
            DeleteAccountController(deps, identity, source, config.entryId).also { it.load() }
        }) { it.leave() }
    DisposableEffect(controller) {
        config.bindLeave(controller::leave)
        onDispose { }
    }
    OnResumed(controller) { controller.load() }
    OnVisible(controller) { controller.onVisibility(it) }
    val state by controller.state.collectAsStateWithLifecycle()
    val stepUpState by controller.stepUp.state.collectAsStateWithLifecycle()
    val gateBlocked by deps.shared.deletionGate.blocked
        .collectAsStateWithLifecycle()
    val cancelBlocked by deps.shared.cancelGate.blocked
        .collectAsStateWithLifecycle()
    val verifyBlocked by deps.stepUp.verifyGate.blocked
        .collectAsStateWithLifecycle()
    // 共性第 19 条：订阅依赖的读闸门（钱包摘要、注销进度、Me 读取），到期时重取因限流失败的那一块；离页随组合取消
    val walletReadBlocked by deps.walletRead.blocked
        .collectAsStateWithLifecycle()
    val deletionReadBlocked by deps.deletionRead.blocked
        .collectAsStateWithLifecycle()
    val meReadBlocked by deps.me.gates.read.blocked
        .collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(controller, walletReadBlocked, deletionReadBlocked, meReadBlocked) {
        if (!walletReadBlocked || !deletionReadBlocked || !meReadBlocked) controller.onReadGatesOpen()
    }
    val texts = deps.texts
    val version = state.version
    val view = remember(state, identity) { controller.view() }
    val back = {
        if (source == AccountRaw.FORCE_UPDATE) {
            if (!deps.env.finishDeletion(config.entryId, AccountRaw.FORCE_UPDATE, null)) config.onBack()
        } else {
            config.onBack()
        }
    }
    MeSubPage(
        tag = AccountTags.DELETE_PAGE,
        title = texts.text("deletion.title"),
        texts = texts,
        onBack = back,
        overlay = {
            MeToastHost(state.toast?.message(texts), state.toast?.id, deletionToastIcon(state.toast?.key)) {
                state.toast?.id?.let(controller::consumeToast)
            }
        },
        bottom = view?.let { v -> { DeleteBottom(deps, controller, v, state, version, gateBlocked, cancelBlocked) } },
        // APP-13v D1 / D10：内容上 8 左右 16（设置一族同一常量）；底栏通栏白底固定（上沿 1px border.subtle）
        contentPadding = SETTINGS_CONTENT_PADDING,
        bottomSurface = true,
    ) {
        // R3-04、共性第 46 条：待确认卡独立于进度 / 资金读取的加载、失败与内容渲染，按未决记录、在途状态与写闸门决定可用性（不等 getDeletion 返回）
        val pendingCard: @Composable () -> Unit = {
            state.pending?.let { pending ->
                // APP-13v D14 / G-D13：白卡写法；回调与 busy 原样，在途行只读 pending.inFlight（限流置灰时不显示「正在处理」）
                PendingPanel(
                    texts = texts,
                    desc = texts.text("pending_confirm.deletion.desc"),
                    confirmLabel = texts.text("pending_confirm.deletion.action"),
                    details = deletionSummary(texts, pending, deps.clock()),
                    busy = pending.inFlight || gateBlocked,
                    inFlight = pending.inFlight,
                    notice = state.pendingNotice?.message(texts),
                    hint = texts.text("deletion.pending_card_hint"),
                    onAbandon = { controller.askAbandon(pending.key, version) },
                    onConfirm = { controller.confirm(pending.key, version) },
                )
            }
        }
        if (view == null) {
            pendingCard()
            Text(
                text = texts.text("me.loading"),
                style = CouliTypography.footnote,
                color = CouliColors.textSecondary,
                modifier = Modifier.testTag(AccountTags.DELETE_LOADING),
            )
            return@MeSubPage
        }
        // APP-13v D3：统一错误（30412 等）改页首警告提示条，assertive 原样
        state.error?.let { NoticeStrip(it.message(texts), NoticeTone.WARNING, AccountTags.DELETE_ERROR, LiveRegionMode.Assertive) }
        state.guideRoute?.let {
            CouliButton(
                text = texts.text(guideLabel(it)),
                onClick = { controller.openGuide(version) },
                variant = CouliButtonVariant.SECONDARY,
                modifier = Modifier.fillMaxWidth().testTag(AccountTags.DELETE_GUIDE),
            )
        }
        when (view.stateRaw) {
            DeletionView.LOAD_FAILED -> {
                Box(modifier = Modifier.fillMaxWidth().testTag(AccountTags.DELETE_LOAD_ERROR)) {
                    ErrorState(
                        title = texts.text("deletion.load_failed"),
                        message = state.readError?.message(texts),
                        // R2-09：getDeletion 读闸门（Retry-After）未到期时【重试】置灰，到期随订阅同步恢复；执行层检查照旧
                        retry = StateAction(texts.text("deletion.retry"), enabled = !deletionReadBlocked) { controller.retry(version) },
                    )
                }
            }

            DeletionView.COOLING -> {
                CoolingSection(texts, view)
            }

            DeletionView.PROCESSING -> {
                Text(
                    text = texts.text("error.10007"),
                    style = CouliTextStyles.bodySemibold,
                    color = CouliColors.textPrimary,
                    modifier = Modifier.testTag(AccountTags.DELETE_PROCESSING),
                )
            }

            else -> {
                // APP-13v D14：待确认卡排在页首说明之上（画板 DeleteAccountPendingConfirm-v2；显示条件原样）
                if (view.stateRaw == DeletionView.PENDING_CONFIRM) pendingCard()
                // 页首说明（设计稿 DeleteAccount 原文，deletion.intro）；APP-13v D2：警告提示条
                NoticeStrip(texts.text("deletion.intro"), NoticeTone.WARNING, AccountTags.DELETE_INTRO)
                // APP-13v D4：负余额句 +【联系客服】移到页首警告条（资金卡之前），句子与金额、显示条件、回调原样
                if (view.stateRaw == DeletionView.NEGATIVE) NegativeNotice(deps, controller, view, version)
                FundsSection(deps, controller, view, state, version, walletReadBlocked)
                // APP-13v D9：须知卡内标题 + 圆点列表（G-D09：画板末尾的全文须知新入口不做）
                BulletListCard(
                    title = texts.text("deletion.notice.title"),
                    lines = DeletionPresentation.NOTICE_KEYS.map { texts.text(it) },
                    tag = AccountTags.DELETE_NOTICE,
                )
            }
        }
    }
    state.abandonDialog?.let { dialog ->
        val pending = state.pending
        // APP-13v D15 / G-D18：标题居中 + 遮罩令牌（在视图件里）；回调原样
        AbandonConfirmDialog(
            texts = texts,
            note = texts.text("pending_confirm.abandon.note.deletion"),
            details = pending?.let { deletionSummary(texts, it, deps.clock()) }.orEmpty(),
            onOk = { controller.abandon(dialog, version) },
            onCancel = { controller.cancelAbandon(dialog) },
        )
    }
    stepUpState?.let { panel ->
        StepUpSheet(controller.stepUp, panel, texts, smsBlocked = false, verifyBlocked = verifyBlocked, restore = null)
    }
}

/** 待确认摘要（G24）：「提交的操作：申请注销账号」「已勾选：放弃未提现余额和预估收益」+ 提交时间。 */
internal fun deletionSummary(
    texts: AccountTexts,
    pending: AccountPending,
    now: Long,
): List<Pair<String, String>> =
    DeletionSummary.LINES.map { (label, value) -> texts.text(label) to texts.text(value) } +
        (texts.text("pending_confirm.summary.submitted_at") to DeletionSummary.submittedAt(pending.createdAtMillis, now))

private fun guideLabel(route: String): String =
    when (route) {
        "Login" -> "settings.go_login"
        "BindPhone" -> "deletion.bind_phone"
        else -> "me.retry"
    }

/** APP-13v D20 / G-S06：成功类轻提示（已放弃、已处理）带对勾，其余（撤回结果等）不带；只按轻提示的键选图标，不改轻提示的出现时机与位置。 */
private fun deletionToastIcon(key: String?): ImageVector? =
    if (key == "pending_confirm.abandoned" || key == "pending_confirm.already_done") CouliIcons.check else null

/**
 * 冷静期（DeleteAccountCooling-v2，APP-13v D16–D18）：状态卡（status.pending 胶囊 + 时钟 → 22/600 标题 → 17 截止句 → 14 说明）→ 时间线卡（图标 + 连线，
 * 每步一个读屏节点）→ muted 只读提示条。can_cancel=false（G15，DeleteAccountStates ③）：胶囊位换「注销处理中」标题 + 不能再撤回句。
 * 时间只格式化服务端字段（applyAtText / coolingUntilText），步态只由 showCancelDeletion 映射，不读时钟。
 */
@Composable
private fun CoolingSection(
    texts: AccountTexts,
    view: DeletionView,
) {
    if (view.showCancelDeletion) {
        CoolingStatusCard(
            tag = AccountTags.DELETE_COOLING,
            // 「冷静期中」胶囊（状态不只靠颜色：带文字）
            badge = {
                Box(Modifier.testTag(AccountTags.DELETE_COOLING_BADGE)) {
                    StatusPill(texts.text("deletion.cooling.badge"), PillTone.PENDING, CouliIcons.clock)
                }
            },
            title = texts.text("deletion.cooling.title"),
            deadline = texts.format("deletion.cooling.deadline", mapOf("time" to view.coolingUntilText.orEmpty())),
            deadlineTag = AccountTags.DELETE_COOLING_DEADLINE,
            desc = texts.text("deletion.cooling.restore"),
        )
    } else {
        // G15：can_cancel=false（待补稿）：注销处理中 + 冷静期已结束，不能再撤回
        CoolingStatusCard(
            tag = AccountTags.DELETE_COOLING,
            badge = null,
            title = texts.text("error.10007"),
            deadline = null,
            deadlineTag = AccountTags.DELETE_COOLING_DEADLINE,
            desc = texts.text("deletion.cooling.cancel_expired"),
            descTag = AccountTags.DELETE_CANCEL_EXPIRED,
        )
    }
    // 时间线竖排（G32）：提交时刻、冷静期至、自提交起 15 个工作日内完成
    AppealTimeline(
        listOf(
            TimelineStep(texts.text("deletion.cooling.timeline.apply"), view.applyAtText.orEmpty(), StepState.DONE),
            TimelineStep(
                texts.text("deletion.cooling.timeline.cooling"),
                texts.format("deletion.cooling.timeline.cooling_desc", mapOf("time" to view.coolingUntilText.orEmpty())),
                if (view.showCancelDeletion) StepState.CURRENT else StepState.DONE,
            ),
            TimelineStep(
                texts.text("deletion.cooling.timeline.process"),
                texts.text("deletion.cooling.timeline.process_desc"),
                StepState.PENDING,
            ),
        ),
    )
    NoticeStrip(texts.text("deletion.cooling.readonly"), NoticeTone.MUTED)
}

@Composable
private fun FundsSection(
    deps: AccountDeps,
    controller: DeleteAccountController,
    view: DeletionView,
    state: DeleteAccountState,
    version: Long,
    walletReadBlocked: Boolean,
) {
    val texts = deps.texts
    // APP-13v D5：卡内标题 + 两列大数；读取中 / 失败 / 重试结构原样
    TitledCard(texts.text("deletion.funds.title"), AccountTags.DELETE_FUNDS) {
        if (!state.fundsLoaded) {
            Text(text = texts.text("me.loading"), style = CouliTypography.footnote, color = CouliColors.textSecondary)
        } else if (view.funds == null) {
            AccountError(state.fundsError?.message(texts) ?: texts.text("deletion.funds_failed"), AccountTags.DELETE_FUNDS_ERROR)
            Text(text = texts.text("deletion.funds_failed"), style = CouliTypography.footnote, color = CouliColors.textSecondary)
            CouliButton(
                text = texts.text("deletion.retry"),
                onClick = { controller.retryWallet(version) },
                // R2-09：钱包摘要读闸门（42901 Retry-After）未到期时置灰，到期同步恢复；执行层检查照旧
                enabled = !walletReadBlocked,
                variant = CouliButtonVariant.SECONDARY,
                modifier = Modifier.testTag(AccountTags.DELETE_FUNDS_RETRY),
            )
        } else {
            AmountColumns(
                leftLabel = texts.text("deletion.withdrawable"),
                leftAmount = view.withdrawableText.orEmpty(),
                leftTag = AccountTags.DELETE_WITHDRAWABLE,
                rightLabel = texts.text("deletion.estimated"),
                rightAmount = view.estimatedText.orEmpty(),
                rightTag = AccountTags.DELETE_ESTIMATED,
            )
            if (view.stateRaw == DeletionView.NEGATIVE) {
                view.negativeText?.let { neg -> SplitAmountRow(texts.text("deletion.negative"), neg, AccountTags.DELETE_NEGATIVE) }
                CardNote(texts.text("deletion.negative.note"))
            }
            // APP-13v D7 / D8（G-D11 ② / G-D12）：提示句按 showGoWithdraw 选句，【去提现】改句子右侧文字链接（显示条件、回调原样）
            HintWithLink(
                text = texts.text(if (view.showGoWithdraw) "deletion.withdraw_hint" else "deletion.withdraw_hint.no_withdraw"),
                link =
                    if (view.showGoWithdraw) {
                        {
                            AccountTextLink(
                                texts.text("deletion.go_withdraw"),
                                AccountTags.DELETE_GO_WITHDRAW,
                            ) { controller.goWithdraw(version) }
                        }
                    } else {
                        null
                    },
            )
        }
    }
}

/** 负余额句（error.30416 + 待抵扣金额，polite）+ 条内【联系客服】（APP-13v D4，DeleteAccountNegative-v2）；金额来源与回调原样。 */
@Composable
private fun NegativeNotice(
    deps: AccountDeps,
    controller: DeleteAccountController,
    view: DeletionView,
    version: Long,
) {
    val texts = deps.texts
    val amount = view.negativeAmountFen
    val sentence =
        if (amount != null) {
            texts.format(
                "error.30416",
                mapOf(
                    "amount" to
                        com.couli.core.designsystem.CouliFormat
                            .money(amount),
                ),
            )
        } else {
            texts.text("error.30416")
        }
    NoticeStrip(sentence, NoticeTone.WARNING, AccountTags.DELETE_NEGATIVE_NOTICE, LiveRegionMode.Polite) {
        AccountTextLink(texts.text("deletion.contact_cs"), AccountTags.DELETE_CONTACT_CS) { controller.contactCs(version) }
    }
}

@Suppress("LongParameterList")
@Composable
private fun DeleteBottom(
    deps: AccountDeps,
    controller: DeleteAccountController,
    view: DeletionView,
    state: DeleteAccountState,
    version: Long,
    gateBlocked: Boolean,
    cancelBlocked: Boolean,
) {
    val texts = deps.texts
    Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
        when (view.stateRaw) {
            DeletionView.COOLING -> {
                if (view.showCancelDeletion) {
                    CouliButton(
                        text = texts.text(if (state.cancelling) "deletion.cooling.cancelling" else "deletion.cooling.cancel"),
                        onClick = { controller.cancel(version) },
                        enabled = !state.cancelling && !cancelBlocked,
                        loading = state.cancelling,
                        modifier = Modifier.fillMaxWidth().testTag(AccountTags.DELETE_CANCEL),
                    )
                }
            }

            DeletionView.LOAD_FAILED, DeletionView.PROCESSING -> {
                Unit
            }

            else -> {
                val waiverEnabled = view.waiverEnabled && !state.submitting
                // APP-13v D11：圆形 18 勾选（语义、可用条件、状态描述与回调原样）
                RoundCheckRow(
                    text = texts.text("deletion.waiver"),
                    checked = state.checked,
                    enabled = waiverEnabled,
                    stateText = texts.text(if (state.checked && waiverEnabled) "deletion.waiver.checked" else "deletion.waiver.unchecked"),
                    tag = AccountTags.DELETE_WAIVER,
                ) { controller.toggle(version) }
                if (state.noPhonePrompt) {
                    Text(
                        text = texts.text("deletion.step_up_unsupported"),
                        style = CouliTypography.footnote,
                        color = CouliColors.textPrimary,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(AccountTags.DELETE_NO_PHONE),
                    )
                    if (view.showBindPhone) {
                        CouliButton(
                            text = texts.text("deletion.bind_phone"),
                            onClick = { controller.goBindPhone(version) },
                            variant = CouliButtonVariant.SECONDARY,
                            modifier = Modifier.fillMaxWidth().testTag(AccountTags.DELETE_BIND_PHONE),
                        )
                    }
                }
                CouliButton(
                    text = texts.text("deletion.submit"),
                    onClick = { controller.apply(version) },
                    enabled =
                        view.applyEnabledWhenChecked && state.checked && !state.submitting && !gateBlocked && !state.pendingUnreadable,
                    loading = state.submitting,
                    modifier = Modifier.fillMaxWidth().testTag(AccountTags.DELETE_SUBMIT),
                )
                Text(
                    text =
                        texts.text(
                            if (view.stateRaw == DeletionView.PENDING_CONFIRM) "deletion.pending_hint" else "deletion.submit_hint",
                        ),
                    style = CouliTypography.caption,
                    color = CouliColors.textSecondary,
                    // 底栏提示句居中（画板）
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
