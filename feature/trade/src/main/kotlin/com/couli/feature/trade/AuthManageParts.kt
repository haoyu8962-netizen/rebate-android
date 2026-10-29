package com.couli.feature.trade

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliRadius
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.PlatformIcon
import com.couli.core.designsystem.PlatformIconSize

/*
 * 授权管理页私有视图件（APP-06w，画板 AuthManage-v2 / AuthManageBlocked-v2 / AuthManageStates / AuthManageDark）。
 * 只管外观：收已求值的文字、布尔与可组合槽；点击回调、显隐与可用条件都留在页面文件里原样传入，本文件不取页面状态。
 */

/** 页面内容边距：上 8、左右 16、底 16（同设置一族数值；本模块不依赖设置模块，自定常量）。 */
internal val AuthManageContentPadding: PaddingValues =
    PaddingValues(
        start = CouliSpacing.space4,
        top = CouliSpacing.space2,
        end = CouliSpacing.space4,
        bottom = CouliSpacing.space4,
    )

/**
 * 轻提示图标键表（G-A07，纯函数）：本页轻提示全是失败类（站长授权不可用、30151、凭证无效、限流、断网、5xxxx、20001 等），
 * 未登记的键同样按失败处理，一律警告三角（颜色由页面传警告字色）。空键没有可显示的提示，不给图标。
 */
internal fun authManageToastIcon(key: String): ImageVector? =
    when {
        key.isBlank() -> null
        else -> CouliIcons.alert
    }

/** 平台名：20 官方图标（装饰，读屏不读）+ 8 + 平台名 17/500。 */
@Composable
internal fun AuthManagePlatformName(
    platformCode: String,
    name: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        PlatformIcon(platformCode, size = PlatformIconSize.SELECT)
        Text(text = name, style = CouliTextStyles.bodyMedium, color = CouliColors.textPrimary)
    }
}

/** 授权状态：已授权 = 圆勾 16（次文字色，装饰）+ 4 + 文字 14 主文字；未授权 = 文字 14 次文字。文字由页面选键后传入。 */
@Composable
internal fun AuthManageStatus(
    authorized: Boolean,
    text: String,
) {
    if (authorized) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
            Icon(
                imageVector = CouliIcons.check,
                contentDescription = null,
                tint = CouliColors.textSecondary,
                modifier = Modifier.size(CouliSizes.iconSmall),
            )
            Text(text = text, style = CouliTypography.footnote, color = CouliColors.textPrimary)
        }
    } else {
        Text(text = text, style = CouliTypography.footnote, color = CouliColors.textSecondary)
    }
}

/**
 * 被停用提示块（AuthManageBlocked-v2）：status.error 底、圆角 12、内边距 12；警告三角 20（装饰）+ 8；右列说明 14 + 12 + [action]（页面传入的按钮，左对齐）。
 */
@Composable
internal fun AuthManageBlockedNotice(
    message: String,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(CouliColors.statusErrorBackground, RoundedCornerShape(CouliRadius.button))
                .padding(CouliSpacing.space3),
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        Icon(
            imageVector = CouliIcons.alert,
            contentDescription = null,
            tint = CouliColors.statusErrorText,
            modifier = Modifier.size(CouliSizes.iconMedium),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3)) {
            Text(text = message, style = CouliTypography.footnote, color = CouliColors.statusErrorText)
            action()
        }
    }
}

/**
 * 平台行版式（G-A11）：[leading]（图标 + 名）与 [trailing]（状态 + 按钮）一行放得下就并排（两端对齐、竖向居中、列间 12）；
 * 放不下时 [trailing] 整组换到下一行右对齐，[leading] 独占一行、不被挤成逐字折行。只按测量宽度判定；行高至少 48（命中区）。
 */
@Composable
internal fun AuthRowLayout(
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
) {
    Layout(
        content = {
            Box { leading() }
            Box { trailing() }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val gap = CouliSpacing.space3.roundToPx()
        val lineGap = CouliSpacing.space2.roundToPx()
        val minLine = CouliSizes.touchTarget.roundToPx()
        val lead = measurables[0]
        val trail = measurables[1]
        val bounded = constraints.hasBoundedWidth
        val width = if (bounded) constraints.maxWidth else Constraints.Infinity
        val tail = trail.measure(Constraints(maxWidth = width))
        val leadNeed = lead.maxIntrinsicWidth(Constraints.Infinity)
        val inline = !bounded || leadNeed + gap + tail.width <= width
        if (inline) {
            val leadMax = if (bounded) width - gap - tail.width else Constraints.Infinity
            val head = lead.measure(Constraints(maxWidth = leadMax))
            val layoutWidth = if (bounded) width else head.width + gap + tail.width
            val height = maxOf(minLine, head.height, tail.height).coerceAtLeast(constraints.minHeight)
            layout(layoutWidth, height) {
                head.placeRelative(0, (height - head.height) / 2)
                tail.placeRelative(layoutWidth - tail.width, (height - tail.height) / 2)
            }
        } else {
            val head = lead.measure(Constraints(maxWidth = width))
            val first = maxOf(minLine, head.height)
            val height = (first + lineGap + tail.height).coerceAtLeast(constraints.minHeight)
            layout(width, height) {
                head.placeRelative(0, (first - head.height) / 2)
                tail.placeRelative(width - tail.width, first + lineGap)
            }
        }
    }
}
