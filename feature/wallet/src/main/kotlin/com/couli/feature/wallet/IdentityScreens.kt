package com.couli.feature.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.couli.core.designsystem.AgreementCheckRow
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonVariant
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliRadius
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.PriceTerms
import com.couli.core.designsystem.Skeleton
import com.couli.core.designsystem.couliFocusTarget
import com.couli.core.wallet.WalletTexts
import com.couli.core.wallet.stepup.LockedSheet

class RealNameViewModel(
    config: WalletPageConfig,
) : ViewModel() {
    val controller = RealNameController(config.deps, viewModelScope, config.onBack)

    init {
        config.hooks.bindLeave(controller::leave)
        config.hooks.bindSession(controller::onSessionChanged)
        // 统一的页面恢复入口（FU-AND-0717 第 3 轮）
        config.hooks.bindIdentity(controller::onIdentity)
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
                    // 上 8、左右 16、下 16，块间 12（RealName 画板）
                    .padding(
                        start = CouliSpacing.space4,
                        top = CouliSpacing.space2,
                        end = CouliSpacing.space4,
                        bottom = CouliSpacing.space4,
                    ),
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
        ) {
            RealNameHeader(texts)
            // W90：标签与框 8、字段间 16
            WalletCard(spacing = CouliSpacing.space4) {
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
                    labelGap = CouliSpacing.space2,
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
                    labelGap = CouliSpacing.space2,
                )
            }
            // W92：错误条挪到输入卡之下（ERROR 语气；liveRegion、tag 不变），读屏顺序随之排在字段之后
            state.error?.let { ErrorBar(it, texts, WalletTags.REALNAME_ERROR) }
            // W91：两句说明改为卡外 INFO 条（第二条人像图标，照画板）
            InfoNote(texts.text("realname.note_fixed"))
            InfoNote(texts.text("realname.note_age"), icon = CouliIcons.user)
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
        // W93：标题 22/600、顶部装饰把手、遮罩令牌（LockedSheet 可选参数；关闭出口、默认不勾、遮罩不关闭不变）
        showHandle = true,
        scrim = CouliColors.overlayScrim,
        titleStyle = CouliTextStyles.titleSemibold,
        actions = {
            // W93：纵排两按钮（主在上），间距 12、同宽
            Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3)) {
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
        // W93：三条要点 = 左侧 20 次文字色图标 + 17 主文字（图标照画板：人像、盾牌、信息）
        Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3)) {
            CONSENT_ITEMS.forEach { (key, icon) -> ConsentItem(texts.text(key), icon) }
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
            Text(text = texts.text("realname.consent.body"), style = CouliTypography.footnote, color = CouliColors.textSecondary)
        }
        // W94：DS 圆形 18 勾选（AgreementCheckRow）、14 主文字；视觉行跟文字走，48 命中区由系统最小触控扩展补足。登记的仍是 () -> Unit
        val toggle = { controller.toggleConsent(owner) }.tapped(WalletTags.CONSENT_CHECK)
        AgreementCheckRow(
            checked = state.consentChecked,
            onCheckedChange = { toggle() },
            text = AnnotatedString(texts.text("realname.consent.check")),
            modifier = Modifier.fillMaxWidth().testTag(WalletTags.CONSENT_CHECK),
            textStyle = CouliTypography.footnote,
            textColor = CouliColors.textPrimary,
        )
    }
}

/** W89：说明卡 = 40 方块（brand.subtle、圆角 12、24 品牌色盾牌）+ 间距 12 + 标题 17/600、说明 14 次文字（间距 4）。 */
@Composable
private fun RealNameHeader(texts: WalletTexts) {
    WalletCard {
        Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3), verticalAlignment = Alignment.Top) {
            Box(
                modifier =
                    Modifier
                        .size(CouliSpacing.space10)
                        .background(CouliColors.brandSubtle, RoundedCornerShape(CouliRadius.button)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    CouliIcons.shieldCheck,
                    contentDescription = null,
                    tint = CouliColors.brandEmphasis,
                    modifier = Modifier.size(CouliSizes.iconLarge),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
                Text(text = texts.text("realname.intro"), style = CouliTextStyles.bodySemibold, color = CouliColors.textPrimary)
                Text(text = texts.text("realname.intro_desc"), style = CouliTypography.footnote, color = CouliColors.textSecondary)
            }
        }
    }
}

/** 同意要点一行：20 次文字色图标（与 17/25.5 首行居中）+ 间距 12 + 17 主文字。 */
@Composable
private fun ConsentItem(
    text: String,
    icon: ImageVector,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3), verticalAlignment = Alignment.Top) {
        Icon(
            icon,
            contentDescription = null,
            tint = CouliColors.textSecondary,
            modifier = Modifier.padding(top = CONSENT_ICON_TOP).size(CouliSizes.iconMedium),
        )
        Text(text = text, style = CouliTypography.body, color = CouliColors.textPrimary, modifier = Modifier.weight(1f))
    }
}

private val CONSENT_ITEMS: List<Pair<String, ImageVector>>
    get() =
        listOf(
            "realname.consent.item1" to CouliIcons.user,
            "realname.consent.item2" to CouliIcons.shieldCheck,
            "realname.consent.item3" to CouliIcons.info,
        )

/** 20 图标与 17/25.5 首行居中：(25.5 − 20) / 2 ≈ 3。 */
private val CONSENT_ICON_TOP = CouliSpacing.space1 - CouliSizes.hairline

class LaborAgreementViewModel(
    config: WalletPageConfig,
) : ViewModel() {
    val controller = LaborAgreementController(config.deps, viewModelScope, config.onBack)

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
                    // W101：上 8、左右 16、下 0（LaborAgreement-v2 画板），块间 12
                    .padding(start = CouliSpacing.space4, top = CouliSpacing.space2, end = CouliSpacing.space4),
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
        ) {
            // W96：说明句 14 次文字、卡外左缩进 4
            Text(
                text = texts.text("labor.intro"),
                style = CouliTypography.footnote,
                color = CouliColors.textSecondary,
                modifier = Modifier.padding(horizontal = CouliSpacing.space1),
            )
            state.error?.let { ErrorBar(it, texts, WalletTags.LABOR_ERROR) }
            WalletCard {
                // W98：文档图标（次文字色）+ 间距 12 + 协议名 17/600、版本 12 次文字（照画板 24 图标）
                Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        CouliIcons.document,
                        contentDescription = null,
                        tint = CouliColors.textSecondary,
                        modifier = Modifier.size(CouliSizes.iconLarge),
                    )
                    Column {
                        Text(text = texts.text("labor.name"), style = CouliTextStyles.bodySemibold, color = CouliColors.textPrimary)
                        state.version?.let {
                            Text(
                                text = texts.format("labor.version", mapOf("version" to it)),
                                style = CouliTypography.caption,
                                color = CouliColors.textSecondary,
                            )
                        }
                    }
                }
                when (state.phase) {
                    LaborSnapshot.CONTENT -> {
                        val label = texts.text("labor.fulltext_label")
                        // W99：全文区高 360、内边距 16、浅灰底，可滚动（200% 下仍可滚动）
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(FULLTEXT_HEIGHT.dp)
                                    .background(CouliColors.backgroundMuted, RoundedCornerShape(CouliRadius.button))
                                    .verticalScroll(rememberScrollState())
                                    .semantics { contentDescription = label }
                                    .testTag(WalletTags.LABOR_TEXT)
                                    .padding(CouliSpacing.space4),
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
            // W101：已签说明 12 次文字、卡外左缩进 4
            Text(
                text = texts.text("labor.signed_note"),
                style = CouliTypography.caption,
                color = CouliColors.textSecondary,
                modifier = Modifier.padding(horizontal = CouliSpacing.space1),
            )
        }
        BottomAction {
            // W100：DS 圆形 18 勾选、14 主文字；登记的仍是 () -> Unit（WalletPageWiringTest 强转）。读屏另带协议版本号（brief §4.2）
            val toggle = { controller.toggle(owner) }.tapped(WalletTags.LABOR_CHECK)
            val sentence = texts.text("labor.check")
            val spoken = state.version?.let { sentence + PriceTerms.SPOKEN_PAUSE + texts.format("labor.version", mapOf("version" to it)) }
            AgreementCheckRow(
                checked = state.checked,
                onCheckedChange = { toggle() },
                text = AnnotatedString(sentence),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .then(if (spoken != null) Modifier.semantics { contentDescription = spoken } else Modifier)
                        .testTag(WalletTags.LABOR_CHECK),
                enabled = state.phase == LaborSnapshot.CONTENT,
                textStyle = CouliTypography.footnote,
                textColor = CouliColors.textPrimary,
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

/**
 * 底部操作区（W95 / W100：无底色，透出页面画布色；随键盘上移）。块间 16：勾选行视觉高只跟文字走，其 48 命中区上下各扩约 13.5，不能压到按钮命中区。
 */
@Composable
private fun BottomAction(content: @Composable () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space3),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space4),
    ) { content() }
}

private const val FULLTEXT_HEIGHT = 360
