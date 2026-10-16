@file:Suppress("MatchingDeclarationName") // ViewModel 与页面放在一起

package com.couli.feature.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliRadius
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.ErrorState
import com.couli.core.designsystem.PriceTerms
import com.couli.core.designsystem.Skeleton
import com.couli.core.designsystem.StateAction
import com.couli.core.designsystem.couliFocusTarget
import com.couli.core.wallet.PayoutAccountDisplay
import com.couli.core.wallet.PayoutAccountForm
import com.couli.core.wallet.WalletJson
import com.couli.core.wallet.WalletTexts
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class PayoutAccountViewModel(
    config: WalletPageConfig,
) : ViewModel() {
    val resume = ResumeTracker()

    val controller = PayoutAccountController(config.deps, viewModelScope, config.entryId)

    init {
        config.hooks.bindLeave(controller::leave)
        config.hooks.bindSession(controller::onSessionChanged)
        // 统一的页面恢复入口（FU-AND-0717 第 3 轮，R2-04）
        config.hooks.bindIdentity(controller::onIdentity)
        controller.start()
    }

    override fun onCleared() {
        controller.leave()
        super.onCleared()
    }
}

/**
 * 收款账号（PayoutAccount 路由，不接受参数；设计稿 PayoutAccount、PayoutAccountBank、PayoutAccountBound、PayoutAccountBoundBank、PayoutAccountBlocked、
 * PayoutAccountPending*）：待确认卡 → 已绑定（方式、脱敏账号、开户行、收款人、本月还能变更 n 次、【更换收款账号】）或表单（支付宝 / 本人借记卡单选、
 * 字段与提示）→ 底部【保存】（随键盘上移）。不显示微信零钱（排除）。
 */
@Suppress("CyclomaticComplexMethod", "ComplexCondition") // 页面、面板、弹窗按状态逐项组合
@Composable
fun PayoutAccountScreen(config: WalletPageConfig) {
    val model: PayoutAccountViewModel = viewModel { PayoutAccountViewModel(config) }
    val controller = model.controller
    val state = controller.state.collectAsStateWithLifecycle().value
    // 渲染时取出的普通快照（APP-09 共性第 63 条）：保存、放弃确认等回调只捕获它与其中的表单身份，回调执行时不读委托 State
    val flow =
        controller.flow.state
            .collectAsStateWithLifecycle()
            .value
    val formOwner = flow.owner
    val stepUp =
        controller.flow.stepUp.state
            .collectAsStateWithLifecycle()
            .value
    val blocked =
        config.deps.gates.payout.blocked
            .collectAsStateWithLifecycle()
            .value
    val readBlocked =
        config.deps.gates.read.blocked
            .collectAsStateWithLifecycle()
            .value
    val smsBlocked =
        config.deps.gates.sms.blocked
            .collectAsStateWithLifecycle()
            .value
    val verifyBlocked =
        config.deps.gates.verify.blocked
            .collectAsStateWithLifecycle()
            .value
    val texts = config.deps.texts
    val saveFocus = remember { FocusRequester() }
    OnReturn(model.resume) { controller.onResume() }
    OnVisibility(controller::onVisibility)
    WalletPage(
        tag = WalletTags.PAYOUT_PAGE,
        title = texts.text("payout.title"),
        texts = texts,
        onBack = config.onBack,
        overlay = {
            val toast = flow.toast ?: state.toast
            toast?.let {
                WalletToast(it.message(texts), it.id, error = false) {
                    controller.flow.consumeToast(it.id)
                    controller.consumeToast(it.id)
                }
            }
        },
    ) {
        when (state.phase) {
            PayoutSnapshot.CONTENT -> {
                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(CouliSpacing.space4),
                    verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
                ) {
                    PayoutPending(controller, flow, texts, blocked)
                    state.loadError?.let {
                        RetryErrorBar(it, texts, WalletTags.PAYOUT_REFRESH_ERROR, retryEnabled = !readBlocked) { controller.retry() }
                    }
                    state.guideRoute?.let { route -> GuideEntry(route, texts, WalletTags.PAYOUT_GUIDE) { controller.openGuide() } }
                    flow.error?.let { ErrorBar(it, texts, WalletTags.PAYOUT_ERROR) }
                    flow.guideRoute?.let { route -> GuideEntry(route, texts, WalletTags.FLOW_GUIDE) { controller.flow.openGuide() } }
                    val account = state.account
                    if (account != null && account.bound && (!state.editing || flow.pending != null)) {
                        // 构建时捕获渲染版本与这张卡的账号身份（APP-09 共性第 4、25 条），回调不在点击时读最新状态
                        val shownVersion = state.version
                        BoundCard(
                            account,
                            texts,
                            enabled = flow.pending == null && state.loadError == null,
                        ) { controller.change(shownVersion, account) }
                    } else {
                        PayoutFormCard(controller, state, flow, texts)
                    }
                }
                if ((state.editing && flow.pending == null) || state.account?.bound != true) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .background(CouliColors.backgroundSurface)
                                .navigationBarsPadding()
                                .imePadding()
                                .padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space3),
                    ) {
                        val saving = flow.phase == FlowState.SUBMITTING
                        CouliButton(
                            text = texts.text(if (saving) "payout.saving" else "payout.save"),
                            onClick = { controller.save(formOwner) }.tapped(WalletTags.PAYOUT_SAVE),
                            enabled = flow.canSubmit && !blocked,
                            loading = saving,
                            loadingDescription = texts.text("payout.saving"),
                            modifier = Modifier.fillMaxWidth().couliFocusTarget(saveFocus).testTag(WalletTags.PAYOUT_SAVE),
                        )
                    }
                }
            }

            PayoutSnapshot.ERROR -> {
                // 待确认卡独立于收款账号的读取状态（APP-09 共性第 2 条）
                Box(modifier = Modifier.padding(CouliSpacing.space4)) { PayoutPending(controller, flow, texts, blocked) }
                Box(modifier = Modifier.fillMaxWidth().testTag(WalletTags.PAYOUT_LOAD_ERROR)) {
                    ErrorState(
                        title = texts.text("payout.load_failed"),
                        message = state.loadError?.message(texts),
                        retry = StateAction(texts.text("wallet.retry"), enabled = !readBlocked) { controller.retry() },
                    )
                }
            }

            else -> {
                Column(modifier = Modifier.padding(CouliSpacing.space4), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3)) {
                    PayoutPending(controller, flow, texts, blocked)
                    Skeleton(Modifier.fillMaxWidth().padding(vertical = CouliSpacing.space12))
                }
            }
        }
    }
    stepUp?.let { StepUpSheet(controller.flow.stepUp, it, texts, smsBlocked, verifyBlocked, saveFocus) }
    flow.riskDialog?.let { dialog ->
        RiskBlockedDialog(
            texts,
            dialog,
            "payout.blocked.desc",
            saveFocus,
            onOk = controller.flow::dismissRisk,
            onAppeal = controller::appeal,
        )
    }
    val abandoning = flow.pending
    if (flow.abandonConfirm && abandoning != null) {
        val key = abandoning.key
        val (method, account, payee) = PayoutAccountController.pendingSummary(abandoning.summary)
        AbandonDialog(
            texts = texts,
            title = texts.text("pending_confirm.abandon"),
            details = pendingDetails(texts, method, account, payee, flow.pending?.createdAtMillis),
            extra = texts.text("pending_confirm.payout_account.abandon_note"),
            onOk = { controller.flow.abandon(key, formOwner) },
            onCancel = controller.flow::cancelAbandon,
        )
    }
}

@Composable
private fun PayoutPending(
    controller: PayoutAccountController,
    flow: FlowState,
    texts: WalletTexts,
    blocked: Boolean,
) {
    val pending = flow.pending ?: return
    // 回调捕获渲染时的未决键与表单身份（共性第 4、63 条）
    val key = pending.key
    val owner = flow.owner
    val (method, account, payee) = PayoutAccountController.pendingSummary(pending.summary)
    val line = account?.let { methodName(texts, method) + " " + it }
    val desc =
        if (line != null) {
            texts.format("pending_confirm.payout_account.desc", mapOf("payout_account" to line))
        } else {
            texts.text("pending_confirm.payout_account.desc")
        }
    PendingCard(
        texts = texts,
        desc = desc,
        abandonLabel = texts.text("pending_confirm.abandon"),
        confirmLabel = texts.text("pending_confirm.payout_account.action"),
        flow = flow,
        blocked = blocked,
        onAbandon = { controller.flow.askAbandon(key, owner) },
        onConfirm = { controller.flow.confirm(key, owner) },
        note = texts.text("pending_confirm.payout_account.note"),
        details = pendingDetails(texts, method, account, payee, pending.createdAtMillis),
    )
}

private fun pendingDetails(
    texts: WalletTexts,
    method: String?,
    account: String?,
    payee: String?,
    createdAt: Long?,
): List<Pair<String, String>> =
    listOfNotNull(
        account?.let { texts.text("pending_confirm.payout_account.account_label") to methodName(texts, method) + " " + it },
        payee?.let { texts.text("pending_confirm.payout_account.payee_label") to it },
        createdAt?.takeIf { it > 0 }?.let {
            texts.text("pending_confirm.payout_account.time_label") to
                Instant.ofEpochMilli(it).atZone(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        },
    )

private fun methodName(
    texts: WalletTexts,
    method: String?,
): String =
    when (method) {
        WalletJson.ALIPAY -> texts.text("payout.channel.alipay")
        WalletJson.BANK_CARD -> ""
        else -> ""
    }.trim()

/** 已绑定（PayoutAccountBound / BoundBank）：只显示服务端返回的脱敏字段；不认识的方式只显示脱敏串、不出【更换收款账号】。 */
@Composable
private fun BoundCard(
    account: PayoutAccountDisplay,
    texts: WalletTexts,
    enabled: Boolean,
    onChange: () -> Unit,
) {
    WalletCard(modifier = Modifier.testTag(WalletTags.PAYOUT_BOUND)) {
        Text(text = texts.text("payout.current"), style = CouliTypography.footnote, color = CouliColors.textSecondary)
        val method =
            when (account.methodRaw) {
                WalletJson.ALIPAY -> texts.text("payout.channel.alipay")
                WalletJson.BANK_CARD -> texts.text("payout.channel.bank_card")
                else -> null
            }
        method?.let { Text(text = it, style = CouliTextStyles.footnoteMedium, color = CouliColors.textPrimary) }
        val value = listOfNotNull(account.bankName?.takeIf { it.isNotBlank() }, account.maskedAccount).joinToString(" ")
        Text(
            text = value,
            style = CouliTextStyles.bodySemibold,
            color = CouliColors.textPrimary,
            modifier = Modifier.semantics { contentDescription = spokenMask(texts, value) },
        )
        account.maskedPayee?.let { payee ->
            LabelValue(texts.text("payout.payee_label"), payee, spoken = spokenMask(texts, payee))
        }
        account.changeRemaining?.let { n ->
            LabelValue(texts.text("payout.remaining_label"), texts.format("payout.remaining", mapOf("n" to n.toString())))
        }
        if (account.known) {
            CouliButton(
                text = texts.text("payout.change"),
                onClick = onChange,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag(WalletTags.PAYOUT_CHANGE),
            )
        }
        Text(text = texts.text("payout.bound_note"), style = CouliTypography.caption, color = CouliColors.textSecondary)
    }
}

@Composable
private fun LabelValue(
    label: String,
    value: String,
    spoken: String? = null,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                contentDescription =
                    label + PriceTerms.SPOKEN_PAUSE + (spoken ?: value)
            },
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = CouliTypography.footnote, color = CouliColors.textSecondary)
        Text(text = value, style = CouliTypography.footnote, color = CouliColors.textPrimary, modifier = Modifier.weight(1f))
    }
}

/** 表单（PayoutAccount / PayoutAccountBank）：单选「支付宝 / 本人借记卡」（G14）；开户行由用户填写（G12，不做卡 BIN 识别）。 */
@Suppress("LongMethod")
@Composable
private fun PayoutFormCard(
    controller: PayoutAccountController,
    state: PayoutSnapshot,
    flow: FlowState,
    texts: WalletTexts,
) {
    val form = state.form
    val editable = flow.canSubmit

    // 渲染这张表单时的身份（APP-09 共性第 59 条）：输入与选择回调都带上它，换代后到达的旧回调不写入新表单
    val owner = flow.owner
    val errors = state.localErrors + flow.fieldErrors
    val errorText = flow.fieldErrorKey?.let(texts::text) ?: texts.text("error.20001")

    fun errorOf(field: String): String? = errorText.takeIf { field in errors }

    // 这次错误事件（本地校验或服务端 20001）只交给按显示顺序的第一个错误字段：滚到它、移焦点并播报一次（APP-09 共性第 24 条）
    // 按屏幕位置：收款方式组（错误提示紧跟在两个选项之后）→ 账号字段 → 姓名
    val shownOrder =
        listOf(PayoutAccountForm.PAYOUT_METHOD) +
            (
                if (form.method == WalletJson.BANK_CARD) {
                    listOf(PayoutAccountForm.CARD_NO, PayoutAccountForm.BANK_NAME)
                } else {
                    listOf(PayoutAccountForm.ALIPAY_LOGON_ID)
                }
            ) + PayoutAccountForm.PAYEE_NAME
    val first = shownOrder.firstOrNull { it in errors }
    val event = state.localErrorId to flow.fieldErrorId

    fun eventOf(field: String): Any? = event.takeIf { field == first }

    fun tagOf(field: String): String = WalletTags.PAYOUT_FIELD_ERROR_PREFIX + field
    WalletCard {
        Text(text = texts.text("payout.intro"), style = CouliTypography.footnote, color = CouliColors.textSecondary)
        Text(text = texts.text("payout.method"), style = CouliTypography.footnote, color = CouliColors.textSecondary)
        MethodOption(
            title = texts.text("payout.alipay"),
            desc = texts.text("payout.alipay_desc"),
            selected = form.method == WalletJson.ALIPAY,
            enabled = editable,
            tag = WalletTags.PAYOUT_METHOD_ALIPAY,
            error = errorOf(PayoutAccountForm.PAYOUT_METHOD),
        ) { controller.chooseMethod(WalletJson.ALIPAY, owner) }
        MethodOption(
            title = texts.text("payout.bank"),
            desc = texts.text("payout.bank_desc"),
            selected = form.method == WalletJson.BANK_CARD,
            enabled = editable,
            tag = WalletTags.PAYOUT_METHOD_BANK,
            error = errorOf(PayoutAccountForm.PAYOUT_METHOD),
        ) { controller.chooseMethod(WalletJson.BANK_CARD, owner) }
        if (PayoutAccountForm.PAYOUT_METHOD in errors) {
            FieldErrorText(errorText, tagOf(PayoutAccountForm.PAYOUT_METHOD), eventOf(PayoutAccountForm.PAYOUT_METHOD))
        }
        if (form.method == WalletJson.BANK_CARD) {
            WalletField(
                label = texts.text("payout.card_no"),
                value = form.cardNo,
                onValueChange = { controller.onCardChange(it, owner) },
                tag = WalletTags.PAYOUT_CARD,
                placeholder = texts.text("payout.card_no_placeholder"),
                error = errorOf(PayoutAccountForm.CARD_NO),
                errorEvent = eventOf(PayoutAccountForm.CARD_NO),
                errorTag = tagOf(PayoutAccountForm.CARD_NO),
                enabled = editable,
                keyboardType = KeyboardType.Number,
            )
            WalletField(
                label = texts.text("payout.bank_name"),
                value = form.bankName,
                onValueChange = { controller.onBankChange(it, owner) },
                tag = WalletTags.PAYOUT_BANK_NAME,
                placeholder = texts.text("payout.bank_name_placeholder"),
                error = errorOf(PayoutAccountForm.BANK_NAME),
                errorEvent = eventOf(PayoutAccountForm.BANK_NAME),
                errorTag = tagOf(PayoutAccountForm.BANK_NAME),
                enabled = editable,
            )
        } else {
            WalletField(
                label = texts.text("payout.alipay_account"),
                value = form.alipayLogonId,
                onValueChange = { controller.onLogonChange(it, owner) },
                tag = WalletTags.PAYOUT_LOGON,
                placeholder = texts.text("payout.alipay_placeholder"),
                error = errorOf(PayoutAccountForm.ALIPAY_LOGON_ID),
                errorEvent = eventOf(PayoutAccountForm.ALIPAY_LOGON_ID),
                errorTag = tagOf(PayoutAccountForm.ALIPAY_LOGON_ID),
                enabled = editable,
                keyboardType = KeyboardType.Email,
            )
        }
        WalletField(
            label = texts.text("payout.payee"),
            value = form.payeeName,
            onValueChange = { controller.onPayeeChange(it, owner) },
            tag = WalletTags.PAYOUT_PAYEE,
            placeholder = texts.text("payout.payee_placeholder"),
            error = errorOf(PayoutAccountForm.PAYEE_NAME),
            errorEvent = eventOf(PayoutAccountForm.PAYEE_NAME),
            errorTag = tagOf(PayoutAccountForm.PAYEE_NAME),
            enabled = editable,
        )
        Text(text = texts.text("payout.payee_note"), style = CouliTypography.caption, color = CouliColors.textSecondary)
        Text(
            text = texts.text(if (form.method == WalletJson.BANK_CARD) "payout.bank_note" else "payout.step_up_note"),
            style = CouliTypography.caption,
            color = CouliColors.textSecondary,
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun MethodOption(
    title: String,
    desc: String,
    selected: Boolean,
    enabled: Boolean,
    tag: String,
    error: String? = null,
    onSelect: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = CouliSizes.touchTarget)
                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect.tapped(tag))
                // 收款方式组的字段错误关联到每个选项的无障碍说明（共性第 36 条）
                .semantics { if (error != null) error(error) }
                .padding(vertical = CouliSpacing.space2)
                .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        Box(
            modifier =
                Modifier
                    .size(CouliSizes.iconMedium)
                    .border(
                        if (selected) CouliSpacing.space1 + CouliSizes.hairline else CouliSizes.hairline,
                        if (selected) CouliColors.brandPrimary else CouliColors.borderControl,
                        CircleShape,
                    ),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = CouliTextStyles.bodyMedium, color = CouliColors.textPrimary)
            Text(text = desc, style = CouliTypography.caption, color = CouliColors.textSecondary)
        }
    }
}
