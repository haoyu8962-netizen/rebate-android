package com.couli.feature.me

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.couli.core.designsystem.CodeInput
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonVariant
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliDialog
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliSpacing
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
            // APP-13v D34 / G-S06：成功类轻提示（已放弃、已处理完成）带对勾，其余不带
            MeToastHost(state.toast?.message(texts), state.toast?.id, phoneToastIcon(state.toast?.key)) {
                state.toast?.id?.let(controller::consumeToast)
            }
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
                        // APP-13v D30：提交说明挪到【提交】下方，12 次文字居中
                        BottomHint(texts.text("phone_change.submit_hint"))
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
                // APP-13v D33：完成图标块 96 + 17/600 标题 + 说明，居中；doneMasked 选句原样
                val masked = state.doneMasked
                DoneHeader(
                    title = texts.text("phone_change.done.title"),
                    body =
                        if (masked != null) {
                            texts.format("phone_change.done.body", mapOf("phone" to masked))
                        } else {
                            texts.text("phone_change.done.body_plain")
                        },
                    modifier = Modifier.testTag(AccountTags.PHONE_DONE),
                )
            }

            else -> {
                state.pending?.let { pending ->
                    // APP-13v D31 / G-D13：待确认卡换 a 段白卡写法；在途行只读 pending.inFlight，按钮可用性与回调原样
                    PendingPanel(
                        texts = texts,
                        desc = pendingDesc(texts, pending.summary),
                        confirmLabel = texts.text("pending_confirm.phone_change.action"),
                        details = phoneSummary(texts, pending, deps.clock()),
                        busy = pending.inFlight || phoneBlocked,
                        inFlight = pending.inFlight,
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
        // APP-13v D32 / G-D18：放弃确认框换 a 段同构包装（标题居中 + 遮罩令牌）；编号与回调原样
        AbandonConfirmDialog(
            texts = texts,
            note = texts.text("pending_confirm.abandon.note.phone_change"),
            details = pending?.let { phoneSummary(texts, it, deps.clock()) }.orEmpty(),
            onOk = { controller.abandon(dialog, version) },
            onCancel = { controller.cancelAbandon(dialog) },
        )
    }
    state.riskDialog?.let { dialog ->
        // PhoneChangeNewBlocked（44001）：标题 risk_msg.<code> → error.44001；【知道了】【联系客服申诉】；点遮罩不关闭（APP-13v G-D18：标题居中、遮罩令牌）
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
            titleAlign = TextAlign.Center,
            scrim = CouliColors.overlayScrim,
        )
    }
    stepUpState?.let { panel ->
        StepUpSheet(controller.stepUp, panel, texts, smsBlocked = false, verifyBlocked = panelVerifyBlocked, restore = null)
    }
}

/** 轻提示图标（G-S06）：已放弃、已处理完成两类成功提示带对勾，其余不带。 */
private fun phoneToastIcon(key: String?): ImageVector? =
    if (key == "pending_confirm.abandoned" || key == "pending_confirm.already_done") CouliIcons.check else null

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

/**
 * 第 1 步（PhoneChange-v2 / PhoneChangeCode / PhoneChangeCodeError；结构不改，G-D04）：说明提示条 → 白卡（「将发送至 / 已发送至」句或取不到号码提示 →
 * 6 格验证码 → 字段错误 → 格下一行【获取验证码 / {n} 秒后重新获取】）→「原手机号不能用了？联系客服 ›」链接；【下一步】在底部。输满 6 位不自动验证。
 */
@Suppress("LongParameterList", "LongMethod")
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
    NoticeStrip(text = texts.text("phone_change.intro"), tone = NoticeTone.MUTED)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .couliCard()
                .padding(CouliSpacing.space4)
                .testTag(AccountTags.PHONE_STEP1),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
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
        // 格子、错误、格下一行之间不加间距（格下一行的 48 命中区紧贴格子下沿、不与格子相交）
        Column {
            val codeError = state.step1Notice?.message(texts)
            CodeInput(
                value = state.code1,
                onValueChange = { controller.onCode1(it, version) },
                accessibilityLabel = texts.format("step_up.code_spoken", mapOf("n" to state.code1.length.toString())),
                enabled = controller.phoneKnown,
                submitting = state.verifying,
                error = codeError != null,
                inputModifier =
                    Modifier.testTag(AccountTags.PHONE_STEP1_CODE).semantics {
                        if (codeError != null) error(codeError)
                    },
            )
            state.step1Notice?.let { FieldErrorLine { AccountFieldError(it.message(texts), it.id, AccountTags.PHONE_FIELD_ERROR) } }
            CodeSendRow(
                text =
                    if (resendLeft > 0) {
                        texts.format("phone_change.resend_in", mapOf("n" to resendLeft.toString()))
                    } else {
                        texts.text("phone_change.get_code")
                    },
                enabled = controller.phoneKnown && resendLeft == 0L && !limited && !state.sending1,
                tag = AccountTags.PHONE_STEP1_SEND,
                onClick = { controller.sendCode1(version) },
            )
        }
    }
    ChevronLink(
        text = texts.text("phone_change.cant_use_old"),
        tag = AccountTags.PHONE_CANT_USE_OLD,
        onClick = { controller.contactCs(version) },
    )
}

/**
 * 第 2 步（PhoneChangeNew-v2 / PhoneChangeNewCode；结构不改，G-D04）：白卡（「新手机号」标签 → 手机号框 → 字段错误 →「验证码」小标签 → 6 格 →
 * 字段错误 → 格下一行发码）→ new_hint 提示条；【提交】与提交说明在底部。待确认时整块置灰（另一组标记）。输满 6 位不自动提交。
 */
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
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
        Text(text = texts.text("phone_change.new_phone"), style = CouliTypography.footnote, color = CouliColors.textSecondary)
        PhoneInputBox(
            value = state.newPhone,
            onValueChange = { controller.onNewPhone(it, version, formGen) },
            prefix = texts.text("phone_change.prefix"),
            placeholder = texts.text("login.phone.placeholder"),
            spoken = texts.text("phone_change.new_phone"),
            enabled = editable,
            error = state.fieldError?.takeIf { state.fieldErrorRaw == PhoneChangeOutcome.FIELD_PHONE }?.message(texts),
            tag = AccountTags.PHONE_NEW + suffix,
        )
        if (state.fieldErrorRaw == PhoneChangeOutcome.FIELD_PHONE && !locked) {
            state.fieldError?.let { AccountFieldError(it.message(texts), it.id, AccountTags.PHONE_FIELD_ERROR) }
        }
        // 验证码框上方的小标签（设计稿 PhoneChangeNew「验证码」）
        Text(text = texts.text("phone_change.code_label"), style = CouliTypography.footnote, color = CouliColors.textSecondary)
        Column {
            val codeError = state.fieldError?.takeIf { state.fieldErrorRaw == PhoneChangeOutcome.FIELD_CODE }?.message(texts)
            CodeInput(
                value = state.code2,
                onValueChange = { controller.onCode2(it, version, formGen) },
                accessibilityLabel = texts.format("step_up.code_spoken", mapOf("n" to state.code2.length.toString())),
                enabled = editable,
                error = codeError != null,
                inputModifier =
                    Modifier.testTag(AccountTags.PHONE_STEP2_CODE + suffix).semantics {
                        if (codeError != null) error(codeError)
                    },
            )
            if (state.fieldErrorRaw == PhoneChangeOutcome.FIELD_CODE && !locked) {
                state.fieldError?.let { FieldErrorLine { AccountFieldError(it.message(texts), it.id, AccountTags.PHONE_FIELD_ERROR) } }
            }
            CodeSendRow(
                text =
                    if (resendLeft > 0) {
                        texts.format("phone_change.resend_in", mapOf("n" to resendLeft.toString()))
                    } else {
                        texts.text("phone_change.get_code")
                    },
                enabled = editable && state.newPhone.isNotBlank() && resendLeft == 0L && !state.sending2,
                tag = AccountTags.PHONE_STEP2_SEND + suffix,
                onClick = { controller.sendCode2(version, formGen) },
            )
        }
    }
    // APP-13v D30：new_hint 改 muted 提示条（卡外）
    NoticeStrip(text = texts.text("phone_change.new_hint"), tone = NoticeTone.MUTED)
}

/**
 * 两步指示（PhoneChange-v2 / PhoneChangeNew-v2 页首步骤条，APP-13v D22）：24 圆编号 / 完成圆勾 + 文字 + 连线；整行合并读「第 n 步，共 2 步」（状态不只靠颜色）。
 */
@Composable
private fun StepIndicator(
    texts: AccountTexts,
    current: Int,
) {
    val spoken = texts.format("phone_change.step_spoken", mapOf("n" to current.toString()))
    StepBar(
        first = texts.text("phone_change.step1"),
        second = texts.text("phone_change.step2"),
        current = current,
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics(
                    mergeDescendants = true,
                ) {
                    contentDescription = spoken
                    // 评审 r1 R1-05：步骤条作为读屏标题（原两步卡内的步骤名标题挪到这里）
                    heading()
                }.testTag(AccountTags.PHONE_STEPS),
    )
}

private const val TICK_MS = 1_000L
private const val LOCKED_ALPHA = 0.5f
