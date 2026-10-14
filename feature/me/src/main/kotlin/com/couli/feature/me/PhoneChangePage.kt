package com.couli.feature.me

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonSize
import com.couli.core.designsystem.CouliButtonVariant
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliDialog
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.couliCard
import com.couli.core.me.account.PhoneChangeOutcome
import com.couli.core.wallet.stepup.StepUpSheet
import kotlinx.coroutines.delay

/**
 * 换号子页（PhoneChange、PhoneChangeNew、PhoneChangeDone、PhoneChangeNewBlocked、PhoneChangePending*；G22 页内子页）。回调捕获渲染时的控制器、版本与
 * 第 2 步表单世代（S0、共性第 16、28 条）。页面返回与系统返回同一处理（共性第 27 条）。底部主操作随键盘上移（imePadding）。
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
internal fun PhoneChangePage(
    deps: AccountDeps,
    controller: PhoneChangeController,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val stepUpState by controller.stepUp.state.collectAsStateWithLifecycle()
    val phoneBlocked by deps.shared.phoneGate.blocked
        .collectAsStateWithLifecycle()
    // R1-14：第 1 步【下一步】订阅与二次验证面板同一份验证截止时间
    val verifyBlocked by deps.stepUp.verifyGate.blocked
        .collectAsStateWithLifecycle()
    val panelVerifyBlocked by deps.stepUp.verifyGate.blocked
        .collectAsStateWithLifecycle()
    val texts = deps.texts
    val version = state.version
    val formGen = state.formGen
    // 倒计时与按键限流：订阅共享截止时间变化，并每秒重算（到期刷新，共性第 20 条）
    var tick by remember { mutableLongStateOf(0L) }
    val smsVersion by deps.stepUp.sms.changes
        .collectAsStateWithLifecycle()
    val bindVersion by deps.shared.bindSms.changes
        .collectAsStateWithLifecycle()
    LaunchedEffect(controller) {
        while (true) {
            delay(TICK_MS)
            tick += 1
        }
    }
    val resend1 = remember(tick, smsVersion, state) { controller.step1ResendLeft() }
    val limited1 = remember(tick, smsVersion, state) { controller.step1SendLimited() }
    val resend2 = remember(tick, bindVersion, state) { controller.step2ResendLeft() }
    BackHandler { controller.back(version) }
    OnVisible(controller) { controller.onVisibility(it) }
    OnResumed(controller) { controller.refreshPending() }
    val view = LocalView.current
    LaunchedEffect(state.sentNotice?.id) {
        // 「验证码已发送」polite 播报一次（倒计时不逐秒读，adaptation-a11y-spec §10）
        state.sentNotice?.let { notice ->
            @Suppress("DEPRECATION")
            view.announceForAccessibility(notice.message(texts))
            controller.consumeSent(notice.id)
        }
    }
    MeSubPage(
        tag = AccountTags.PHONE_PAGE,
        title = texts.text(if (state.step == PhoneChangeState.DONE) "phone_change.done.title" else "phone_change.title"),
        texts = texts,
        onBack = { controller.back(version) },
        overlay = {
            MeToastHost(state.toast?.message(texts), state.toast?.id) { state.toast?.id?.let(controller::consumeToast) }
        },
        bottom = {
            Column(modifier = Modifier.imePadding(), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
                when (state.step) {
                    PhoneChangeState.STEP1 -> {
                        CouliButton(
                            text = texts.text("phone_change.next"),
                            onClick = { controller.verify(version) },
                            enabled = controller.phoneKnown && state.code1.length == PhoneChangeController.CODE_LENGTH && !verifyBlocked,
                            loading = state.verifying,
                            modifier = Modifier.fillMaxWidth().testTag(AccountTags.PHONE_STEP1_NEXT),
                        )
                    }

                    PhoneChangeState.STEP2 -> {
                        CouliButton(
                            text = texts.text("phone_change.submit"),
                            onClick = { controller.submit(version, formGen) },
                            enabled =
                                state.newPhone.isNotBlank() && state.code2.length == PhoneChangeController.CODE_LENGTH &&
                                    !phoneBlocked && !state.inFlightElsewhere && !state.pendingUnreadable,
                            loading = state.submitting,
                            modifier = Modifier.fillMaxWidth().testTag(AccountTags.PHONE_SUBMIT),
                        )
                    }

                    PhoneChangeState.DONE -> {
                        CouliButton(
                            text = texts.text("phone_change.done.ok"),
                            onClick = { controller.finish(version) },
                            modifier = Modifier.fillMaxWidth().testTag(AccountTags.PHONE_DONE_OK),
                        )
                    }

                    else -> {
                        Unit
                    }
                }
            }
        },
    ) {
        state.error?.let { AccountError(it.message(texts), AccountTags.PHONE_ERROR) }
        if (state.showContact) {
            CouliButton(
                text = texts.text("deletion.contact_cs"),
                onClick = { controller.contactCs(version) },
                variant = CouliButtonVariant.SECONDARY,
                modifier = Modifier.testTag(AccountTags.PHONE_CONTACT_CS),
            )
        }
        state.guideRoute?.let {
            CouliButton(
                text = texts.text(if (it == "Login") "settings.go_login" else "me.retry"),
                onClick = { controller.openGuide(version) },
                variant = CouliButtonVariant.SECONDARY,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (state.step != PhoneChangeState.DONE) StepIndicator(texts, if (state.step == PhoneChangeState.STEP1) 1 else 2)
        when (state.step) {
            PhoneChangeState.STEP1 -> {
                Step1(deps, controller, state, version, resend1, limited1)
            }

            PhoneChangeState.STEP2 -> {
                Step2(deps, controller, state, version, formGen, resend2, locked = false)
            }

            PhoneChangeState.DONE -> {
                Column(
                    modifier = Modifier.fillMaxWidth().testTag(AccountTags.PHONE_DONE),
                    verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
                ) {
                    Text(
                        text = texts.text("phone_change.done.title"),
                        style = CouliTextStyles.bodySemibold,
                        color = CouliColors.textPrimary,
                        modifier = Modifier.semantics { heading() },
                    )
                    val masked = state.doneMasked
                    Text(
                        text =
                            if (masked != null) {
                                texts.format("phone_change.done.body", mapOf("phone" to masked))
                            } else {
                                texts.text("phone_change.done.body_plain")
                            },
                        style = CouliTypography.footnote,
                        color = CouliColors.textSecondary,
                    )
                }
            }

            else -> {
                state.pending?.let { pending ->
                    AccountPendingCard(
                        texts = texts,
                        desc = pendingDesc(texts, pending.summary),
                        confirmLabel = texts.text("pending_confirm.phone_change.action"),
                        details = phoneSummary(texts, pending, deps.clock()),
                        busy = pending.inFlight || phoneBlocked,
                        notice = state.pendingNotice?.message(texts),
                        hint = texts.text("phone_change.pending_hint"),
                        onAbandon = { controller.askAbandon(pending.key, version) },
                        onConfirm = { controller.confirm(pending.key, version) },
                    )
                }
                // brief §2 第 5 项：待确认时下方第 2 步表单保留但整块置灰（不可输入、不可发码、不可提交）
                Step2(deps, controller, state, version, formGen, resendLeft = 0L, locked = true)
            }
        }
    }
    state.abandonDialog?.let { dialog ->
        val pending = state.pending
        AccountAbandonDialog(
            texts = texts,
            note = texts.text("pending_confirm.abandon.note.phone_change"),
            details = pending?.let { phoneSummary(texts, it, deps.clock()) }.orEmpty(),
            onOk = { controller.abandon(dialog, version) },
            onCancel = { controller.cancelAbandon(dialog) },
        )
    }
    state.riskDialog?.let { dialog ->
        // PhoneChangeNewBlocked（44001）：标题 risk_msg.<code> → error.44001；【知道了】【联系客服申诉】；点遮罩不关闭
        CouliDialog(
            title = texts.text(dialog.kind),
            message = texts.text("phone_change.risk_blocked.desc"),
            confirmText = texts.text("phone_change.risk_blocked.ok"),
            onConfirm = { controller.dismissRisk(dialog) },
            dismissText = texts.text("cs.appeal_entry"),
            onDismiss = { controller.appealRisk(dialog, version) },
            onDismissRequest = { controller.dismissRisk(dialog) },
            dismissOnClickOutside = false,
            confirmTag = AccountTags.PHONE_RISK_OK,
            dismissTag = AccountTags.PHONE_RISK_APPEAL,
        )
    }
    stepUpState?.let { panel ->
        StepUpSheet(controller.stepUp, panel, texts, smsBlocked = false, verifyBlocked = panelVerifyBlocked, restore = null)
    }
}

/** 待确认说明：{phone} 为本机脱敏的新号；取不到用 fallbacks（G24）。 */
internal fun pendingDesc(
    texts: AccountTexts,
    masked: String,
): String =
    if (masked.isNotEmpty()) {
        texts.format("pending_confirm.phone_change.desc", mapOf("phone" to masked))
    } else {
        texts.text("pending_confirm.phone_change.desc")
    }

/** 换号待确认摘要（G24）：提交的新手机号（本机脱敏）+ 提交时间；不列当前号码（G03）。 */
internal fun phoneSummary(
    texts: AccountTexts,
    pending: AccountPending,
    now: Long,
): List<Pair<String, String>> {
    val rows = mutableListOf<Pair<String, String>>()
    if (pending.summary.isNotEmpty()) rows += texts.text("pending_confirm.summary.new_phone") to pending.summary
    rows +=
        texts.text("pending_confirm.summary.submitted_at") to
        com.couli.core.me.account.DeletionSummary
            .submittedAt(pending.createdAtMillis, now)
    return rows
}

@Suppress("LongParameterList")
@Composable
private fun Step1(
    deps: AccountDeps,
    controller: PhoneChangeController,
    state: PhoneChangeState,
    version: Long,
    resendLeft: Long,
    limited: Boolean,
) {
    val texts = deps.texts
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .couliCard()
                .padding(CouliSpacing.space4)
                .testTag(AccountTags.PHONE_STEP1),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        Text(
            text = texts.text("phone_change.step1"),
            style = CouliTextStyles.bodySemibold,
            color = CouliColors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        Text(text = texts.text("phone_change.intro"), style = CouliTypography.footnote, color = CouliColors.textSecondary)
        if (!controller.phoneKnown) {
            // G02：本机取不到号码 →「暂时无法发送验证码」，两个按钮置灰，不发请求
            Text(
                text = texts.text("step_up.no_phone"),
                style = CouliTypography.footnote,
                color = CouliColors.textPrimary,
                modifier = Modifier.testTag(AccountTags.PHONE_NO_PHONE),
            )
        } else {
            Text(
                text = texts.text(if (resendLeft > 0) "phone_change.code_sent" else "phone_change.current_hint"),
                style = CouliTypography.footnote,
                color = CouliColors.textSecondary,
            )
        }
        AccountInput(
            value = state.code1,
            onChange = { controller.onCode1(it, version) },
            tag = AccountTags.PHONE_STEP1_CODE,
            spoken = texts.format("step_up.code_spoken", mapOf("n" to state.code1.length.toString())),
            otp = true,
            enabled = controller.phoneKnown && !state.verifying,
            error = state.step1Notice?.message(texts),
            trailing = {
                CouliButton(
                    text =
                        if (resendLeft > 0) {
                            texts.format("phone_change.resend_in", mapOf("n" to resendLeft.toString()))
                        } else {
                            texts.text("phone_change.get_code")
                        },
                    onClick = { controller.sendCode1(version) },
                    enabled = controller.phoneKnown && resendLeft == 0L && !limited && !state.sending1,
                    variant = CouliButtonVariant.TEXT,
                    size = CouliButtonSize.COMPACT,
                    modifier = Modifier.testTag(AccountTags.PHONE_STEP1_SEND),
                )
            },
        )
        state.step1Notice?.let { AccountFieldError(it.message(texts), it.id, AccountTags.PHONE_FIELD_ERROR) }
        CouliButton(
            text = texts.text("phone_change.cant_use_old"),
            onClick = { controller.contactCs(version) },
            variant = CouliButtonVariant.TEXT,
            size = CouliButtonSize.COMPACT,
            modifier = Modifier.testTag(AccountTags.PHONE_CANT_USE_OLD),
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun Step2(
    deps: AccountDeps,
    controller: PhoneChangeController,
    state: PhoneChangeState,
    version: Long,
    formGen: Long,
    resendLeft: Long,
    locked: Boolean,
) {
    val texts = deps.texts
    // 置灰的表单用另一组标签（不与可编辑的第 2 步混淆），整块对读屏标为不可用
    val suffix = if (locked) AccountTags.LOCKED_SUFFIX else ""
    val editable = !locked && !state.submitting
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .couliCard()
                .then(if (locked) Modifier.alpha(LOCKED_ALPHA) else Modifier)
                .padding(CouliSpacing.space4)
                .testTag(AccountTags.PHONE_STEP2 + suffix),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        Text(
            text = texts.text("phone_change.step2"),
            style = CouliTextStyles.bodySemibold,
            color = CouliColors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        Text(text = texts.text("phone_change.new_phone"), style = CouliTypography.footnote, color = CouliColors.textSecondary)
        AccountInput(
            value = state.newPhone,
            onChange = { controller.onNewPhone(it, version, formGen) },
            tag = AccountTags.PHONE_NEW + suffix,
            spoken = texts.text("phone_change.new_phone"),
            otp = false,
            enabled = editable,
            error = state.fieldError?.takeIf { state.fieldErrorRaw == PhoneChangeOutcome.FIELD_PHONE }?.message(texts),
            prefix = texts.text("phone_change.prefix"),
        )
        if (state.fieldErrorRaw == PhoneChangeOutcome.FIELD_PHONE && !locked) {
            state.fieldError?.let { AccountFieldError(it.message(texts), it.id, AccountTags.PHONE_FIELD_ERROR) }
        }
        // 验证码框上方的小标签（设计稿 PhoneChangeNew「验证码」）
        Text(text = texts.text("phone_change.code_label"), style = CouliTypography.footnote, color = CouliColors.textSecondary)
        AccountInput(
            value = state.code2,
            onChange = { controller.onCode2(it, version, formGen) },
            tag = AccountTags.PHONE_STEP2_CODE + suffix,
            spoken = texts.format("step_up.code_spoken", mapOf("n" to state.code2.length.toString())),
            otp = true,
            enabled = editable,
            error = state.fieldError?.takeIf { state.fieldErrorRaw == PhoneChangeOutcome.FIELD_CODE }?.message(texts),
            trailing = {
                CouliButton(
                    text =
                        if (resendLeft > 0) {
                            texts.format("phone_change.resend_in", mapOf("n" to resendLeft.toString()))
                        } else {
                            texts.text("phone_change.get_code")
                        },
                    onClick = { controller.sendCode2(version, formGen) },
                    enabled = editable && state.newPhone.isNotBlank() && resendLeft == 0L && !state.sending2,
                    variant = CouliButtonVariant.TEXT,
                    size = CouliButtonSize.COMPACT,
                    modifier = Modifier.testTag(AccountTags.PHONE_STEP2_SEND + suffix),
                )
            },
        )
        if (state.fieldErrorRaw == PhoneChangeOutcome.FIELD_CODE && !locked) {
            state.fieldError?.let { AccountFieldError(it.message(texts), it.id, AccountTags.PHONE_FIELD_ERROR) }
        }
        Text(text = texts.text("phone_change.new_hint"), style = CouliTypography.caption, color = CouliColors.textSecondary)
        Text(text = texts.text("phone_change.submit_hint"), style = CouliTypography.caption, color = CouliColors.textSecondary)
    }
}

/**
 * 两步指示（设计稿 PhoneChange / PhoneChangeNew 页首「验证当前手机号 / 验证新手机号」）：当前步加粗、品牌色，状态不只靠颜色（读屏读「第 n 步，共 2 步」）。
 */
@Composable
private fun StepIndicator(
    texts: AccountTexts,
    current: Int,
) {
    val spoken = texts.format("phone_change.step_spoken", mapOf("n" to current.toString()))
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics(
                    mergeDescendants = true,
                ) { contentDescription = spoken }
                .testTag(AccountTags.PHONE_STEPS),
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf("phone_change.step1", "phone_change.step2").forEachIndexed { index, key ->
            val active = index + 1 == current
            Text(
                text = "${index + 1} " + texts.text(key),
                style = if (active) CouliTextStyles.bodySemibold else CouliTypography.footnote,
                color = if (active) CouliColors.brandEmphasis else CouliColors.textSecondary,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private const val TICK_MS = 1_000L
private const val LOCKED_ALPHA = 0.5f
