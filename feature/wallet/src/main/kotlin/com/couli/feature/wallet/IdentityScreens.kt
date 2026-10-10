package com.couli.feature.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonVariant
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliRadius
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.Skeleton
import com.couli.core.designsystem.couliFocusTarget
import com.couli.core.wallet.WalletTexts

class RealNameViewModel(
    config: WalletPageConfig,
) : ViewModel() {
    val controller = RealNameController(config.deps, viewModelScope, config.onBack)

    init {
        config.hooks.bindLeave(controller::leave)
        config.hooks.bindSession(controller::onSessionChanged)
    }

    override fun onCleared() {
        controller.leave()
        super.onCleared()
    }
}

/**
 * 实名认证（RealName 路由，phone 档，不接受参数；设计稿 RealName、RealNameConsent）：说明 → 姓名、身份证号 → 两句提示 → 底部【提交认证】；点【提交认证】先弹
 * 单独同意（默认不勾选，点遮罩不关闭）。Release 里【提交认证】置灰，下方「实名认证暂未开放，请稍后再试」（G01）。
 */
@Composable
fun RealNameScreen(config: WalletPageConfig) {
    val model: RealNameViewModel = viewModel { RealNameViewModel(config) }
    val controller = model.controller
    // 渲染时取出的普通快照（APP-09 共性第 63 条）：下面的回调只捕获它与其中的身份，回调执行时不读委托 State
    val state = controller.state.collectAsStateWithLifecycle().value
    val owner = state.owner
    val texts = config.deps.texts
    val submitFocus = remember { FocusRequester() }
    OnVisibility(controller::onVisibility)
    WalletPage(tag = WalletTags.REALNAME_PAGE, title = texts.text("realname.title"), texts = texts, onBack = config.onBack) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(CouliSpacing.space4),
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
        ) {
            WalletCard {
                Text(text = texts.text("realname.intro"), style = CouliTextStyles.bodySemibold, color = CouliColors.textPrimary)
                Text(text = texts.text("realname.intro_desc"), style = CouliTypography.footnote, color = CouliColors.textSecondary)
            }
            state.error?.let { ErrorBar(it, texts, WalletTags.REALNAME_ERROR) }
            WalletCard {
                val fieldError = texts.text("error.20001")
                WalletField(
                    label = texts.text("realname.name"),
                    value = state.name,
                    onValueChange = { controller.onNameChange(it, owner) },
                    tag = WalletTags.REALNAME_NAME,
                    placeholder = texts.text("realname.name_placeholder"),
                    error = fieldError.takeIf { RealNameController.FIELD_NAME in state.fieldErrors },
                    errorEvent = state.fieldErrorId.takeIf { RealNameController.FIELD_NAME in state.fieldErrors },
                    enabled = !state.submitting,
                )
                WalletField(
                    label = texts.text("realname.id_no"),
                    value = state.idNo,
                    onValueChange = { controller.onIdChange(it, owner) },
                    tag = WalletTags.REALNAME_ID,
                    placeholder = texts.text("realname.id_no_placeholder"),
                    error = fieldError.takeIf { RealNameController.FIELD_ID in state.fieldErrors },
                    errorEvent =
                        state.fieldErrorId.takeIf {
                            RealNameController.FIELD_ID in state.fieldErrors && RealNameController.FIELD_NAME !in state.fieldErrors
                        },
                    enabled = !state.submitting,
                    keyboardType = KeyboardType.Ascii,
                )
            }
            Text(text = texts.text("realname.note_fixed"), style = CouliTypography.caption, color = CouliColors.textSecondary)
            Text(text = texts.text("realname.note_age"), style = CouliTypography.caption, color = CouliColors.textSecondary)
        }
        BottomAction {
            CouliButton(
                text = texts.text("realname.submit"),
                onClick = { controller.submit(owner) }.tapped(WalletTags.REALNAME_SUBMIT),
                enabled = controller.submitEnabled,
                loading = state.submitting,
                modifier = Modifier.fillMaxWidth().couliFocusTarget(submitFocus).testTag(WalletTags.REALNAME_SUBMIT),
            )
            if (!controller.available) {
                Text(
                    text = texts.text("realname.unavailable"),
                    style = CouliTypography.footnote,
                    color = CouliColors.textSecondary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(WalletTags.REALNAME_UNAVAILABLE),
                )
            }
        }
    }
    if (state.consentOpen) ConsentSheet(controller, state, texts, submitFocus)
}

/** 单独同意（RealNameConsent；BR-ID-12 consent_type=id_verification、channel=realname_sheet）：默认不勾选；点遮罩、下滑不关闭。 */
@Composable
private fun ConsentSheet(
    controller: RealNameController,
    state: RealNameSnapshot,
    texts: WalletTexts,
    restore: FocusRequester,
) {
    // 渲染这张同意面板时的表单身份（共性第 63 条）
    val owner = state.owner
    LockedSheet(
        title = texts.text("realname.consent.title"),
        tag = WalletTags.CONSENT_SHEET,
        closeLabel = null,
        onClose = controller::declineConsent,
        restore = restore,
        actions = {
            Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
                CouliButton(
                    text = texts.text("realname.consent.ok"),
                    onClick = { controller.agreeAndSubmit(owner) }.tapped(WalletTags.CONSENT_OK),
                    enabled = state.consentChecked,
                    modifier = Modifier.fillMaxWidth().testTag(WalletTags.CONSENT_OK),
                )
                CouliButton(
                    text = texts.text("realname.consent.cancel"),
                    onClick = controller::declineConsent,
                    variant = CouliButtonVariant.SECONDARY,
                    modifier = Modifier.fillMaxWidth().testTag(WalletTags.CONSENT_CANCEL),
                )
            }
        },
    ) {
        listOf("realname.consent.item1", "realname.consent.item2", "realname.consent.item3").forEach {
            Text(text = texts.text(it), style = CouliTypography.footnote, color = CouliColors.textPrimary)
        }
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(CouliColors.backgroundMuted, RoundedCornerShape(CouliRadius.button))
                    .padding(CouliSpacing.space3),
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
        ) {
            Text(text = texts.text("realname.consent.body_title"), style = CouliTextStyles.footnoteMedium, color = CouliColors.textPrimary)
            Text(text = texts.text("realname.consent.body"), style = CouliTypography.caption, color = CouliColors.textSecondary)
        }
        CheckRow(
            texts.text("realname.consent.check"),
            state.consentChecked,
            { controller.toggleConsent(owner) },
            WalletTags.CONSENT_CHECK,
        )
    }
}

class LaborAgreementViewModel(
    config: WalletPageConfig,
) : ViewModel() {
    val controller = LaborAgreementController(config.deps, viewModelScope, config.onBack)

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
 * 劳务协议签署（LaborAgreement 路由，只有 in_app，不接受参数；设计稿 LaborAgreement）：说明 → 协议名与版本 → 全文区（可滚动、读屏能完整读到）→ 已签版本的
 * 查看位置 → 勾选（默认不勾）→【同意并签署】。Release 全文区「协议正文暂未提供」、按钮置灰（G02）。
 */
@Composable
fun LaborAgreementScreen(config: WalletPageConfig) {
    val model: LaborAgreementViewModel = viewModel { LaborAgreementViewModel(config) }
    val controller = model.controller
    // 渲染时取出的普通快照（共性第 63 条）：勾选、签署回调只捕获这份协议的身份
    val state = controller.state.collectAsStateWithLifecycle().value
    val owner = state.owner
    val blocked =
        config.deps.gates.labor.blocked
            .collectAsStateWithLifecycle()
            .value
    val texts = config.deps.texts
    OnVisibility(controller::onVisibility)
    WalletPage(tag = WalletTags.LABOR_PAGE, title = texts.text("labor.title"), texts = texts, onBack = config.onBack) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(CouliSpacing.space4),
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
        ) {
            Text(text = texts.text("labor.intro"), style = CouliTypography.footnote, color = CouliColors.textSecondary)
            state.error?.let { ErrorBar(it, texts, WalletTags.LABOR_ERROR) }
            WalletCard {
                Text(text = texts.text("labor.name"), style = CouliTextStyles.bodySemibold, color = CouliColors.textPrimary)
                state.version?.let {
                    Text(
                        text = texts.format("labor.version", mapOf("version" to it)),
                        style = CouliTypography.caption,
                        color = CouliColors.textSecondary,
                    )
                }
                when (state.phase) {
                    LaborSnapshot.CONTENT -> {
                        val label = texts.text("labor.fulltext_label")
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = FULLTEXT_MAX.dp)
                                    .background(CouliColors.backgroundMuted, RoundedCornerShape(CouliRadius.button))
                                    .verticalScroll(rememberScrollState())
                                    .padding(CouliSpacing.space3)
                                    .semantics { contentDescription = label }
                                    .testTag(WalletTags.LABOR_TEXT),
                        ) {
                            Text(text = state.text.orEmpty(), style = CouliTypography.footnote, color = CouliColors.textPrimary)
                        }
                    }

                    LaborSnapshot.UNAVAILABLE -> {
                        Text(
                            text = texts.text("labor.unavailable"),
                            style = CouliTypography.footnote,
                            color = CouliColors.textSecondary,
                            modifier = Modifier.testTag(WalletTags.LABOR_UNAVAILABLE),
                        )
                    }

                    LaborSnapshot.ERROR -> {
                        CouliButton(text = texts.text("wallet.retry"), onClick = controller::load, variant = CouliButtonVariant.SECONDARY)
                    }

                    else -> {
                        Skeleton(Modifier.fillMaxWidth().padding(vertical = CouliSpacing.space12))
                    }
                }
            }
            Text(text = texts.text("labor.signed_note"), style = CouliTypography.caption, color = CouliColors.textSecondary)
        }
        BottomAction {
            CheckRow(
                texts.text("labor.check"),
                state.checked,
                { controller.toggle(owner) },
                WalletTags.LABOR_CHECK,
                enabled = state.phase == LaborSnapshot.CONTENT,
            )
            CouliButton(
                text = texts.text("labor.sign"),
                onClick = { controller.sign(owner) }.tapped(WalletTags.LABOR_SIGN),
                enabled = controller.signEnabled && !blocked,
                loading = state.signing,
                modifier = Modifier.fillMaxWidth().testTag(WalletTags.LABOR_SIGN),
            )
        }
    }
}

/** 底部操作区（白底；随键盘上移）。 */
@Composable
private fun BottomAction(content: @Composable () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(CouliColors.backgroundSurface)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space3),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) { content() }
}

private const val FULLTEXT_MAX = 320
