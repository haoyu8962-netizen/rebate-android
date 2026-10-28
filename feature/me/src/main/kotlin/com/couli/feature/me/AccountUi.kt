package com.couli.feature.me

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonVariant
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliDialog
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliRadius
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.LocalCouliAnnouncer
import com.couli.core.designsystem.couliFocusTarget
import kotlinx.coroutines.delay

/**
 * 待确认警告卡（DeleteAccountPendingConfirm / PhoneChangePendingConfirm 一族；BR-TEXT-14 表 C）：alert 角色（assertive），标题 + 说明 + 摘要 +
 * 【放弃上一次提交】【确认上一次提交】；在途或限流时两个都置灰（PendingBusy）。回调由调用方捕获渲染时的键与版本。
 */
@Suppress("LongParameterList")
@Composable
internal fun AccountPendingCard(
    texts: AccountTexts,
    desc: String,
    confirmLabel: String,
    details: List<Pair<String, String>>,
    busy: Boolean,
    notice: String?,
    hint: String,
    onAbandon: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(CouliColors.statusWarningBackground, RoundedCornerShape(CouliRadius.card))
                .padding(CouliSpacing.space4)
                .testTag(AccountTags.PENDING_CARD),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        Column(
            modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive },
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
        ) {
            Text(text = texts.text("pending_confirm.title"), style = CouliTextStyles.bodySemibold, color = CouliColors.textPrimary)
            Text(text = desc, style = CouliTypography.footnote, color = CouliColors.textPrimary)
        }
        details.forEach { (label, value) -> SummaryRow(label, value) }
        Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3)) {
            CouliButton(
                text = texts.text("pending_confirm.abandon"),
                onClick = onAbandon,
                enabled = !busy,
                variant = CouliButtonVariant.SECONDARY,
                modifier = Modifier.weight(1f).testTag(AccountTags.PENDING_ABANDON),
            )
            CouliButton(
                text = confirmLabel,
                onClick = onConfirm,
                enabled = !busy,
                modifier = Modifier.weight(1f).testTag(AccountTags.PENDING_CONFIRM),
            )
        }
        if (notice != null) {
            Text(
                text = notice,
                style = CouliTypography.footnote,
                color = CouliColors.textPrimary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(AccountTags.PENDING_NOTICE),
            )
        }
        Text(text = hint, style = CouliTypography.caption, color = CouliColors.textSecondary)
    }
}

@Composable
internal fun SummaryRow(
    label: String,
    value: String,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3), verticalAlignment = Alignment.Top) {
        Text(text = label, style = CouliTypography.footnote, color = CouliColors.textSecondary)
        Text(text = value, style = CouliTypography.footnote, color = CouliColors.textPrimary, modifier = Modifier.weight(1f))
    }
}

/** 金额行（DeleteAccount 资金区）：标签在左、金额右对齐（设计稿左右两端对齐）；放不下时金额换行仍靠右，不截断（G32）。 */
@Composable
internal fun AmountRow(
    label: String,
    value: String,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3), verticalAlignment = Alignment.Top) {
        Text(text = label, style = CouliTypography.footnote, color = CouliColors.textSecondary, modifier = Modifier.weight(1f))
        Text(
            text = value,
            style = CouliTextStyles.bodySemibold,
            color = CouliColors.textPrimary,
            textAlign = TextAlign.End,
        )
    }
}

/** 放弃上一次提交的二次确认（PendingAbandonConfirm）：点遮罩不关闭，系统返回 = 【再想想】。 */
@Composable
internal fun AccountAbandonDialog(
    texts: AccountTexts,
    note: String,
    details: List<Pair<String, String>>,
    onOk: () -> Unit,
    onCancel: () -> Unit,
) {
    CouliDialog(
        title = texts.text("pending_confirm.abandon"),
        confirmText = texts.text("pending_confirm.abandon.ok"),
        onConfirm = onOk,
        dismissText = texts.text("pending_confirm.abandon.cancel"),
        onDismiss = onCancel,
        onDismissRequest = onCancel,
        dismissOnClickOutside = false,
        confirmTag = AccountTags.ABANDON_OK,
        dismissTag = AccountTags.ABANDON_CANCEL,
        body = {
            Column(
                verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
                modifier = Modifier.testTag(AccountTags.ABANDON_DIALOG),
            ) {
                Text(
                    text = texts.text("pending_confirm.abandon.confirm"),
                    style = CouliTypography.footnote,
                    color = CouliColors.textPrimary,
                )
                details.forEach { (label, value) -> SummaryRow(label, value) }
                Text(text = note, style = CouliTypography.caption, color = CouliColors.textSecondary)
            }
        },
    )
}

/** 勾选行（「放弃未提现余额和预估收益」）：整行可点，读屏读「…，复选框，未选中 / 已选中」。 */
@Composable
internal fun AccountCheckRow(
    text: String,
    checked: Boolean,
    enabled: Boolean,
    stateText: String,
    tag: String,
    onToggle: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = CouliSizes.touchTarget)
                .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
                .semantics { stateDescription = stateText }
                .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        val on = checked && enabled
        Box(
            modifier =
                Modifier
                    .size(CouliSizes.iconMedium)
                    .background(if (on) CouliColors.brandPrimary else CouliColors.backgroundSurface, RoundedCornerShape(CouliRadius.small))
                    .border(
                        CouliSizes.hairline,
                        if (on) CouliColors.brandPrimary else CouliColors.borderControl,
                        RoundedCornerShape(CouliRadius.small),
                    ),
            contentAlignment = Alignment.Center,
        ) {
            if (on) {
                Icon(
                    CouliIcons.check,
                    contentDescription = null,
                    tint = CouliColors.backgroundSurface,
                    modifier = Modifier.size(CouliSizes.iconSmall),
                )
            }
        }
        Text(
            text = text,
            style = CouliTypography.footnote,
            color = if (enabled) CouliColors.textPrimary else CouliColors.textSecondary,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 输入框（新手机号、验证码）：一个整串输入框；验证码 NumberPassword + 短信验证码自动填充提示（不申请短信读取权限），读屏读「验证码，已输入 n 位，共 6 位」
 * （adaptation-a11y-spec §9）；新号 KeyboardType.Phone。[error] 是具体的错误说明：非空时边框标红，并作为输入框的 error 语义（读屏回到输入框也能读到，
 * R1-15）；输入保留。
 */
@Suppress("LongParameterList")
@Composable
internal fun AccountInput(
    value: String,
    onChange: (String) -> Unit,
    tag: String,
    spoken: String,
    otp: Boolean,
    enabled: Boolean,
    error: String?,
    prefix: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = CouliSizes.touchTarget)
                .background(CouliColors.backgroundSurface, RoundedCornerShape(CouliRadius.button))
                .border(
                    CouliSizes.hairline,
                    if (error != null) CouliColors.statusErrorText else CouliColors.borderControl,
                    RoundedCornerShape(CouliRadius.button),
                ).padding(horizontal = CouliSpacing.space3, vertical = CouliSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        if (prefix != null) Text(text = prefix, style = CouliTypography.body, color = CouliColors.textPrimary)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            enabled = enabled,
            textStyle = CouliTypography.body.copy(color = CouliColors.textPrimary),
            cursorBrush = SolidColor(CouliColors.textPrimary),
            keyboardOptions = KeyboardOptions(keyboardType = if (otp) KeyboardType.NumberPassword else KeyboardType.Phone),
            modifier =
                Modifier
                    .weight(1f)
                    .testTag(tag)
                    .semantics {
                        contentDescription = spoken
                        if (otp) contentType = ContentType.SmsOtpCode
                        // 错误说明关联到输入框（brief §0「错误说明关联到输入框」）
                        if (error != null) error(error)
                    },
        )
        trailing?.invoke()
    }
}

/**
 * 字段错误提示（R1-15，照 APP-09 FieldErrorText 的做法，brief §0「字段错误即时播报一次，焦点移到错误提示」）：每次错误事件（[event]，一次性编号）出现后，
 * 等布局完成先把提示滚到可见区域，再把焦点（读屏随之）移到提示上，并播报一次；同一事件重组不重复。输入框另以 error 语义关联同一句。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AccountFieldError(
    text: String,
    event: Long?,
    tag: String,
) {
    val bring = remember { BringIntoViewRequester() }
    val focus = remember { FocusRequester() }
    val injected = LocalCouliAnnouncer.current
    val view = LocalView.current
    LaunchedEffect(event) {
        if (event == null) return@LaunchedEffect
        // 等错误提示挂上布局与无障碍树
        delay(FIELD_ERROR_FOCUS_DELAY_MILLIS)
        bring.bringIntoView()
        runCatching { focus.requestFocus() }
        @Suppress("DEPRECATION") // 一次性播报（与 DesignSystem 的 ViewAnnouncer 同一做法）
        injected?.announce(text) ?: view.announceForAccessibility(text)
    }
    Text(
        text = text,
        style = CouliTypography.footnote,
        color = CouliColors.statusErrorText,
        modifier =
            Modifier
                .bringIntoViewRequester(bring)
                .couliFocusTarget(focus)
                .focusable()
                .testTag(tag),
    )
}

private const val FIELD_ERROR_FOCUS_DELAY_MILLIS = 100L

/** 页内错误提示（assertive，一次）。 */
@Composable
internal fun AccountError(
    text: String,
    tag: String,
) {
    Text(
        text = text,
        style = CouliTypography.footnote,
        color = CouliColors.statusErrorText,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }.testTag(tag),
    )
}

/** 注销与换号依赖（App 在「我的」类路由页外层提供；没接时为 null）：封禁说明页、申诉维持结果页的「注销账号」入口用。 */
val LocalAccountDeps: androidx.compose.runtime.ProvidableCompositionLocal<AccountDeps?> =
    androidx.compose.runtime.staticCompositionLocalOf { null }

/**
 * 「| 注销账号」链接（RiskNotice 封禁态底部、AppealResultUpheld；G01：按路由可用性显示，since=null 时隐藏；经 Router 打开 DeleteAccount，来源 risk_notice）。
 */
@Composable
internal fun DeleteAccountLink(
    tag: String,
    linkStyle: TextStyle = CouliTypography.body,
    linkColor: Color = CouliColors.brandPrimary,
    separatorColor: Color = CouliColors.borderControl,
    separatorSilent: Boolean = false,
) {
    val account = LocalAccountDeps.current ?: return
    if (!deletionAvailable()) return
    Text(
        text = "|",
        style = CouliTypography.footnote,
        color = separatorColor,
        modifier = if (separatorSilent) Modifier.clearAndSetSemantics {} else Modifier,
    )
    LinkText(account.texts.text("settings.delete_account"), tag, linkStyle, linkColor) {
        account.env.openDeletion(com.couli.core.me.account.AccountRaw.RISK_NOTICE)
    }
}

/**
 * 注销入口是否可用（G01 / G-R17）：宿主提供了注销依赖且路由表含 DeleteAccount。[DeleteAccountLink] 的显隐与封禁说明句 / 「接下来可以」第 3 条
 * 用的是同一判据（APP-12w-c：句子提到注销 ⇔ 页面有注销入口）。只读，不发请求。
 */
@Composable
internal fun deletionAvailable(): Boolean {
    val account = LocalAccountDeps.current ?: return false
    return DeleteAccountController.ROUTE in account.env.availableRoutes()
}
