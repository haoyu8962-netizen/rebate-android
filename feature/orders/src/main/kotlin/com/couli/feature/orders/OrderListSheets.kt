package com.couli.feature.orders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliDivider
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliSheet
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.PlatformIcon
import com.couli.core.designsystem.PlatformIconSize
import com.couli.core.orders.OrderMonthOptions
import com.couli.core.orders.OrderPlatformOptions
import com.couli.core.orders.OrderTexts

/*
 * 平台 / 月份半屏（OrdersFilterPlatform-v2 / OrdersFilterMonth-v2，O17）：系统半屏（CouliSheet：令牌遮罩、窗口内接续字号）；选项行 ≥48、项间 1 分隔线；
 * 选中 17/600 强调色 + 右侧圆圈对勾；平台项前 20 官方图标（装饰，读屏只读平台名一次）。单选语义与焦点回原按钮不变。
 */

@Composable
internal fun PlatformSheet(
    texts: OrderTexts,
    selected: String?,
    restore: FocusRequester,
    onChoose: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    CouliSheet(
        title = texts.text("order_list.filter.platform"),
        onDismissRequest = onDismiss,
        restoreFocusTo = restore,
        closeLabel = texts.text("order_list.filter.close"),
    ) {
        OptionList {
            RadioOption(
                texts.text("order_list.filter.all_platforms"),
                selected == null,
                OrderTags.FILTER_OPTION_PREFIX + ALL_OPTION,
            ) { onChoose(null) }
            OrderPlatformOptions.platforms.forEach { platform ->
                CouliDivider()
                RadioOption(
                    texts.text("platform.$platform"),
                    selected == platform,
                    OrderTags.FILTER_OPTION_PREFIX + platform,
                    leading = { PlatformIcon(platform, size = PlatformIconSize.SELECT) },
                ) { onChoose(platform) }
            }
            CouliDivider()
        }
    }
}

@Composable
internal fun MonthSheet(
    texts: OrderTexts,
    selected: String?,
    earliestVisibleDate: String?,
    now: Long,
    restore: FocusRequester,
    onChoose: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    CouliSheet(
        title = texts.text("order_list.filter.month"),
        onDismissRequest = onDismiss,
        restoreFocusTo = restore,
        closeLabel = texts.text("order_list.filter.close"),
    ) {
        Text(text = texts.text("order_list.filter.month_note"), style = CouliTypography.footnote, color = CouliColors.textSecondary)
        OptionList {
            RadioOption(
                texts.text("order_list.filter.all_months"),
                selected == null,
                OrderTags.FILTER_OPTION_PREFIX + ALL_OPTION,
            ) { onChoose(null) }
            OrderMonthOptions.months(earliestVisibleDate, now).forEach { month ->
                CouliDivider()
                RadioOption(monthText(texts, month), selected == month, OrderTags.FILTER_OPTION_PREFIX + month) { onChoose(month) }
            }
            CouliDivider()
        }
    }
}

/** 选项组：项间不留空隙（分隔线贴着选项）。 */
@Composable
private fun OptionList(content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) { content() }
}

/** 半屏里的单选项（Role.RadioButton + selected，命中区 48）；[leading] 为装饰图标（平台 20 官方图），与文字间距 8。 */
@Composable
private fun RadioOption(
    label: String,
    selected: Boolean,
    tag: String,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = CouliSizes.touchTarget)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .padding(vertical = CouliSpacing.space2)
                .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        leading?.invoke()
        Text(
            text = label,
            style = if (selected) CouliTextStyles.bodySemibold else CouliTypography.body,
            color = if (selected) CouliColors.brandEmphasis else CouliColors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                CouliIcons.check,
                contentDescription = null,
                tint = CouliColors.brandEmphasis,
                modifier = Modifier.size(CouliSizes.iconMedium),
            )
        }
    }
}

private const val ALL_OPTION = "all"
