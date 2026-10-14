@file:Suppress("MatchingDeclarationName", "CyclomaticComplexMethod")

package com.couli.feature.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonVariant
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTag
import com.couli.core.designsystem.CouliTagTone
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.ErrorState
import com.couli.core.designsystem.StateAction
import com.couli.core.designsystem.couliCard
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
            MeToastHost(state.toast?.message(texts), state.toast?.id) { state.toast?.id?.let(controller::consumeToast) }
        },
        bottom = view?.let { v -> { DeleteBottom(deps, controller, v, state, version, gateBlocked, cancelBlocked) } },
    ) {
        // R3-04、共性第 46 条：待确认卡独立于进度 / 资金读取的加载、失败与内容渲染，按未决记录、在途状态与写闸门决定可用性（不等 getDeletion 返回）
        val pendingCard: @Composable () -> Unit = {
            state.pending?.let { pending ->
                AccountPendingCard(
                    texts = texts,
                    desc = texts.text("pending_confirm.deletion.desc"),
                    confirmLabel = texts.text("pending_confirm.deletion.action"),
                    details = deletionSummary(texts, pending, deps.clock()),
                    busy = pending.inFlight || gateBlocked,
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
        state.error?.let { AccountError(it.message(texts), AccountTags.DELETE_ERROR) }
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
                // 页首说明（设计稿 DeleteAccount 原文，deletion.intro）
                Text(
                    text = texts.text("deletion.intro"),
                    style = CouliTypography.footnote,
                    color = CouliColors.textSecondary,
                    modifier = Modifier.testTag(AccountTags.DELETE_INTRO),
                )
                if (view.stateRaw == DeletionView.PENDING_CONFIRM) pendingCard()
                FundsSection(deps, controller, view, state, version, walletReadBlocked)
                NoticeSection(texts)
            }
        }
    }
    state.abandonDialog?.let { dialog ->
        val pending = state.pending
        AccountAbandonDialog(
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

@Composable
private fun CoolingSection(
    texts: AccountTexts,
    view: DeletionView,
) {
    Column(
        modifier = Modifier.fillMaxWidth().couliCard().testTag(AccountTags.DELETE_COOLING),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(CouliSpacing.space4),
                verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
            ) {
                // 「冷静期中」徽标（设计稿 DeleteAccountCooling；状态不只靠颜色：带文字）
                CouliTag(
                    text = texts.text("deletion.cooling.badge"),
                    tone = CouliTagTone.WARNING,
                    modifier = Modifier.testTag(AccountTags.DELETE_COOLING_BADGE),
                )
                if (view.showCancelDeletion) {
                    Text(
                        text = texts.text("deletion.cooling.title"),
                        style = CouliTextStyles.bodySemibold,
                        color = CouliColors.textPrimary,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = texts.format("deletion.cooling.deadline", mapOf("time" to view.coolingUntilText.orEmpty())),
                        style = CouliTypography.footnote,
                        color = CouliColors.textPrimary,
                        modifier = Modifier.testTag(AccountTags.DELETE_COOLING_DEADLINE),
                    )
                    Text(text = texts.text("deletion.cooling.restore"), style = CouliTypography.footnote, color = CouliColors.textSecondary)
                } else {
                    // G15：can_cancel=false（待补稿）：注销处理中 + 冷静期已结束，不能再撤回
                    Text(text = texts.text("error.10007"), style = CouliTextStyles.bodySemibold, color = CouliColors.textPrimary)
                    Text(
                        text = texts.text("deletion.cooling.cancel_expired"),
                        style = CouliTypography.footnote,
                        color = CouliColors.textSecondary,
                        modifier = Modifier.testTag(AccountTags.DELETE_CANCEL_EXPIRED),
                    )
                }
                // 时间线竖排（G32）：提交时刻、冷静期至、自提交起 15 个工作日内完成
                SummaryRow(texts.text("deletion.cooling.timeline.apply"), view.applyAtText.orEmpty())
                SummaryRow(
                    texts.text("deletion.cooling.timeline.cooling"),
                    texts.format("deletion.cooling.timeline.cooling_desc", mapOf("time" to view.coolingUntilText.orEmpty())),
                )
                SummaryRow(texts.text("deletion.cooling.timeline.process"), texts.text("deletion.cooling.timeline.process_desc"))
                Text(text = texts.text("deletion.cooling.readonly"), style = CouliTypography.caption, color = CouliColors.textSecondary)
            }
        }
    }
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
    GroupLabel(texts.text("deletion.funds.title"))
    Column(
        modifier = Modifier.fillMaxWidth().couliCard().testTag(AccountTags.DELETE_FUNDS),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(CouliSpacing.space4),
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
        ) {
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
                Box(Modifier.testTag(AccountTags.DELETE_WITHDRAWABLE)) {
                    AmountRow(texts.text("deletion.withdrawable"), view.withdrawableText.orEmpty())
                }
                Box(Modifier.testTag(AccountTags.DELETE_ESTIMATED)) {
                    AmountRow(texts.text("deletion.estimated"), view.estimatedText.orEmpty())
                }
                if (view.stateRaw == DeletionView.NEGATIVE) {
                    view.negativeText?.let { neg ->
                        Box(Modifier.testTag(AccountTags.DELETE_NEGATIVE)) { AmountRow(texts.text("deletion.negative"), neg) }
                    }
                    Text(text = texts.text("deletion.negative.note"), style = CouliTypography.caption, color = CouliColors.textSecondary)
                }
                Text(text = texts.text("deletion.withdraw_hint"), style = CouliTypography.footnote, color = CouliColors.textSecondary)
                if (view.showGoWithdraw) {
                    CouliButton(
                        text = texts.text("deletion.go_withdraw"),
                        onClick = { controller.goWithdraw(version) },
                        variant = CouliButtonVariant.SECONDARY,
                        modifier = Modifier.testTag(AccountTags.DELETE_GO_WITHDRAW),
                    )
                }
            }
        }
    }
    if (view.stateRaw == DeletionView.NEGATIVE) {
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
        Text(
            text = sentence,
            style = CouliTypography.footnote,
            color = CouliColors.statusErrorText,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(AccountTags.DELETE_NEGATIVE_NOTICE),
        )
        CouliButton(
            text = texts.text("deletion.contact_cs"),
            onClick = { controller.contactCs(version) },
            variant = CouliButtonVariant.SECONDARY,
            modifier = Modifier.testTag(AccountTags.DELETE_CONTACT_CS),
        )
    }
}

@Composable
private fun NoticeSection(texts: AccountTexts) {
    GroupLabel(texts.text("deletion.notice.title"))
    Column(modifier = Modifier.fillMaxWidth().couliCard().testTag(AccountTags.DELETE_NOTICE)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(CouliSpacing.space4),
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
        ) {
            DeletionPresentation.NOTICE_KEYS.forEachIndexed { index, key ->
                Text(
                    text = "${index + 1}. " + texts.text(key),
                    style = CouliTypography.footnote,
                    color = CouliColors.textPrimary,
                )
            }
        }
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
                AccountCheckRow(
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
                )
            }
        }
    }
}
