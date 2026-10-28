package com.couli.feature.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import com.couli.core.designsystem.CouliButtonSize
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextField
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.couliCard

/*
 * 设置一族（设置主页、修改昵称、通知设置）的视图件（APP-12w-a，设计稿 Settings-v2 / SettingsGuest-v2 / SettingsBasic / SettingsAgreementSheet-v2 /
 * SettingsNickname* / NotifySettings*）。只管外观：所有回调由页面传入、原样转交，这里不读执行器、不发请求。
 */

/** 设置一族内容区：上 8、左右 16、底 16（S1）。 */
internal val SETTINGS_CONTENT_PADDING =
    PaddingValues(start = CouliSpacing.space4, top = CouliSpacing.space2, end = CouliSpacing.space4, bottom = CouliSpacing.space4)

/** 成功类轻提示（G-S06）：带对勾；错误 / 限流 / 切换失败不带。 */
private val SUCCESS_TOASTS =
    setOf(
        SettingsController.CLEARED_KEY,
        SettingsController.SAVED_KEY,
        SettingsController.RESET_DONE_KEY,
        DELETION_CANCELLED_KEY,
    )

/** 设置页轻提示的图标：成功类对勾（`status.success.text`），其余 null。 */
internal fun toastIcon(notice: MeNotice?): ImageVector? = if (notice?.key in SUCCESS_TOASTS) CouliIcons.check else null

/**
 * 一个分组：分组名（12 次文字、左缩进 4、读屏标题）→ 白卡（卡内上下 4）。名到卡 8；组与组之间由页面列间距 12 + 分组名上边距 8 = 20；
 * 第一组分组名不加上边距（内容上 8，S1 / S2 / S3）。[label] 为 null 时只有卡（游客无名分组、撤回 AI 行）。
 */
@Composable
internal fun SettingsGroup(
    label: String?,
    first: Boolean,
    rows: List<@Composable () -> Unit>,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
        if (label != null) GroupLabel(label, top = if (first) CouliSpacing.space0 else CouliSpacing.space2)
        // 与 RowCard 同写法（白卡、行间 1px 分隔线与卡内边距齐），另加卡内上下 4；RowCard 本身不改（其他页面共用）
        Column(modifier = Modifier.fillMaxWidth().couliCard().padding(vertical = CouliSpacing.space1)) {
            rows.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = CouliSpacing.space4), color = CouliColors.borderSubtle)
                row()
            }
        }
    }
}

/**
 * 设置一族的导航行（S4 / S5 / S26）：上下 4（行高仍按 48 命中区）、chevron 20、值与箭头 4、标题与右侧 12；[chevron] 为 false 时不画箭头（动作行），
 * 仍可点、读屏仍是按钮；长值折行、标题不被挤成一字一行。
 */
@Composable
internal fun SettingsNavRow(
    title: String,
    tag: String,
    value: String?,
    onClick: (() -> Unit)?,
    chevron: Boolean,
) {
    NavRow(
        title = title,
        tag = tag,
        value = value,
        onClick = onClick,
        verticalPadding = CouliSpacing.space1,
        chevronSize = CouliSizes.iconMedium,
        titleGap = CouliSpacing.space3,
        valueGap = CouliSpacing.space1,
        showChevron = chevron,
        wrapValue = true,
    )
}

/** 游客底部（SettingsGuest-v2，G-S08 ①）：居中 14 次文字说明 → 文字链接「去登录」（600、链接色、命中 48 不撑高），间距 8。 */
@Composable
internal fun GuestFooter(
    note: String,
    link: String,
    onLogin: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = CouliSpacing.space4).testTag(MeTags.SETTINGS_FOOTER),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        Text(
            text = note,
            style = CouliTypography.footnote,
            color = CouliColors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        CompactTextLink(text = link, color = CouliColors.textLink, tag = MeTags.SETTINGS_LOGIN, onClick = onLogin)
    }
}

/**
 * 【退出登录】高 48（Settings-v2）：组件库次按钮标准尺寸是 17/500 一行（行高 25.5）+ 上下 12 = 49.5；改用紧凑内边距（上下 8）+ 同一 17/500 文字，
 * 由组件库最小高 48 定高，字号放大时随文字长高。
 */
internal val SETTINGS_LOGOUT_SIZE = CouliButtonSize.COMPACT

/** 【退出登录】文字 17/500（与组件库次按钮标准文字同一样式）。 */
internal val SETTINGS_LOGOUT_TEXT: TextStyle = CouliTextStyles.bodyMedium

/** 基本模式提示条（SettingsBasic，G-S08 ④）：muted 底、info 20 次文字色、14/500 主文字；距上一张卡 20。静态文字，无动作。 */
@Composable
internal fun BasicModeNotice(text: String) {
    NoteStrip(text = text, modifier = Modifier.padding(top = CouliSpacing.space2), textStyle = CouliTextStyles.footnoteMedium)
}

/** 协议选择半屏的一条列表行（SettingsAgreementSheet-v2，G-S08 ②）：17、chevron 20、上下 8（命中 48）；[divider] 时行上方 1px 分隔线。 */
@Composable
internal fun AgreementSheetRow(
    title: String,
    tag: String,
    divider: Boolean,
    onClick: () -> Unit,
) {
    if (divider) HorizontalDivider(color = CouliColors.borderSubtle)
    NavRow(
        title = title,
        tag = tag,
        onClick = onClick,
        verticalPadding = CouliSpacing.space2,
        horizontalPadding = CouliSpacing.space0,
        chevronSize = CouliSizes.iconMedium,
        titleGap = CouliSpacing.space3,
        valueGap = CouliSpacing.space1,
    )
}

/** 修改昵称卡内的可见标签（17/600）。读屏不单独读：同一名称由输入框的 accessibilityLabel 读一次（S17）。 */
@Composable
internal fun NicknameLabel(text: String) {
    Box(modifier = Modifier.clearAndSetSemantics { }) {
        Text(text = text, style = CouliTextStyles.bodySemibold, color = CouliColors.textPrimary)
    }
}

/**
 * 昵称输入（G-S09）：组件库 [CouliTextField]（外框 `border.control` 1、圆角 12、输入中 `border.active` 2、清除 48），读屏名称 [label]。
 * 不传 maxLength（组件按 UTF-16 截断并计数，昵称按字素计，计数由页面画）。[fieldError] 为字段错误句（20001 等）时：外框转错误色，输入框 error 语义
 * 就是这句原因（读屏回到输入框读「错误：原因」，评审 r1 S2-1）；组件不画下方错误提示行（`showErrorText = false`），可见错误句只由页面
 * ErrorFocusText 画一次（14、带图标、移焦、assertive）。清除按钮挂 [MeTags.NICKNAME_CLEAR]（S2-2）。
 */
@Composable
internal fun NicknameField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    clearLabel: String,
    fieldError: String?,
    keyboardOptions: KeyboardOptions,
    modifier: Modifier = Modifier,
) {
    CouliTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        error = fieldError,
        clearLabel = clearLabel,
        keyboardOptions = keyboardOptions,
        accessibilityLabel = label,
        showErrorText = false,
        clearButtonModifier = Modifier.testTag(MeTags.NICKNAME_CLEAR),
    )
}

/** 昵称超长 / 为空的提示：与错误行同位置同写法（14 错误色、带图标），不移焦、不播报（现状只显示）。 */
@Composable
internal fun NicknameHint(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
        ErrorLineIcon(CouliIcons.alert)
        Text(text = text, style = CouliTypography.footnote, color = CouliColors.statusErrorText, modifier = Modifier.weight(1f))
    }
}

/** 通知设置提示条里的「去设置」（NotifySettings，G-S08 ③）：600 警告字色文字链接，与提示句左齐，命中 48 不撑高提示条。只跳系统通知设置（BR-ID-13）。 */
@Composable
internal fun NotifyGoSettingsLink(
    text: String,
    onClick: () -> Unit,
) {
    CompactTextLink(
        text = text,
        color = CouliColors.statusWarningText,
        tag = MeTags.NOTIFY_GO_SETTINGS,
        modifier = Modifier.padding(start = CouliSizes.iconMedium + CouliSpacing.space2),
        onClick = onClick,
    )
}

private const val DELETION_CANCELLED_KEY = "deletion.cancelled_toast"
