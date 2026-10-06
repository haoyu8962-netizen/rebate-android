package com.couli.core.designsystem

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign

/**
 * 二级页导航栏（GUIDE §3）：左侧返回（命中区 48，Android），标题 17/600 居中，右侧同宽占位或操作。
 * [backLabel] 是返回按钮的读屏名称（字典给）。
 */
@Composable
fun NavBar(
    title: String,
    backLabel: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = { Box(Modifier.size(CouliSizes.touchTarget)) },
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = CouliSizes.touchTarget).padding(horizontal = CouliSpacing.space1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CouliIconButton(icon = CouliIcons.back, label = backLabel, onClick = onBack)
        Text(
            text = title,
            style = CouliTextStyles.bodySemibold,
            color = CouliColors.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        trailing()
    }
}

/** 一级 Tab 页标题栏：标题左对齐 22/600，右侧可放图标按钮（GUIDE §3）。 */
@Composable
fun PageTitleBar(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = CouliSizes.touchTarget)
                .padding(start = CouliSpacing.space4, end = CouliSpacing.space1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = CouliTextStyles.titleSemibold,
            color = CouliColors.textPrimary,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        trailing()
    }
}

/** 底部 Tab 的一项（文字由调用方给：首页 / AI 助手 / 订单 / 我的）。 */
@Immutable
data class TabItem(
    val icon: ImageVector,
    val label: String,
)

/**
 * 一级 Tab 栏（GUIDE §3）：白底、上 1 分隔线、4 等分；选中项图标 #C44820、文字 #A83B1F 600，未选 #655E57。
 * 选中状态经 selectable 读出，不只靠颜色（选中项另有字重变化）。系统导航区由调用方加 navigationBarsPadding。
 */
@Composable
fun TabBar(
    items: List<TabItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().background(CouliColors.backgroundSurface)) {
        Box(Modifier.fillMaxWidth().height(CouliSizes.hairline).background(CouliColors.borderSubtle))
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = CouliSpacing.space1).selectableGroup(),
        ) {
            items.forEachIndexed { index, item ->
                val selected = index == selectedIndex
                val interaction = remember { MutableInteractionSource() }
                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .heightIn(min = CouliSizes.touchTarget)
                            .couliFocusRing(focusRingVisible(interaction), CouliRadius.small)
                            .selectable(
                                selected = selected,
                                interactionSource = interaction,
                                indication = LocalIndication.current,
                                role = Role.Tab,
                                onClick = { onSelect(index) },
                            ).padding(vertical = CouliSpacing.space1),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1 / 2, Alignment.CenterVertically),
                ) {
                    Icon(
                        item.icon,
                        contentDescription = null,
                        tint = if (selected) CouliColors.brandPrimary else CouliColors.textSecondary,
                        modifier = Modifier.size(CouliSizes.iconLarge),
                    )
                    Text(
                        text = item.label,
                        style = if (selected) CouliTextStyles.captionSemibold else CouliTypography.caption,
                        color = if (selected) CouliColors.brandEmphasis else CouliColors.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
