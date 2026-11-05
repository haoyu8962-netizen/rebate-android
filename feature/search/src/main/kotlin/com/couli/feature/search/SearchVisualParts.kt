package com.couli.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
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
 * APP-06x-a（搜索与结果高保真第 1 批：SearchTyping-v2、Search-v2、SearchFilterPrice-v2、SearchDisclaimerNote-v2、SearchError、SearchDisabled）
 * 的纯视图件：只收显示值与 lambda，不碰 ViewModel / 执行器 / 配置；回调表达式与 SearchTags 都留在 SearchScreen 的调用处。
 * 命中区一律 48（F-ALL-02，画板 44），视觉尺寸按画板（胶囊 32、下划线 2），不被命中区撑大。
 * 不可用（42901 等 `enabled = false`）时外观取组件库禁用色（同 CouliFilterChip / 单选）：文字 `text.secondary`、胶囊底 `background.muted`、
 * 描边与选中下划线 `border.subtle`；拦截仍只靠 `selectable(enabled)`。
 */

/** 头部 / Tab 行下沿 1px `border.subtle`（Search-v2、SearchTyping-v2 头部整块下沿）。 */
@Composable
internal fun SearchDivider() {
    Box(Modifier.fillMaxWidth().height(CouliSizes.hairline).background(CouliColors.borderSubtle))
}

/**
 * 平台 Tab 一项（Search-v2）：20 官方平台图标（装饰，读屏不读）+ 6 + 名称 17；选中 `brand.emphasis` 600 + 通宽 2 `brand.primary` 下划线，
 * 未选次文字 400。`Role.Tab`、选中态、[enabled] 由调用方原样给。选中项在选中值 / 自身尺寸 / 行视口宽（[revealKey]）变化时滚入横滑视口。
 */
@Composable
internal fun SearchPlatformTab(
    platform: String,
    name: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    revealKey: Int = 0,
) {
    Box(
        modifier =
            modifier
                .selectedReveal(rememberSelectedReveal(selected, revealKey))
                .heightIn(min = CouliSizes.touchTarget)
                .widthIn(min = CouliSizes.touchTarget)
                .selectable(selected = selected, enabled = enabled, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.padding(vertical = UNDERLINE.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TAB_ICON_GAP.dp),
        ) {
            PlatformIcon(platformCode = platform, size = PlatformIconSize.SELECT)
            Text(
                text = name,
                style = if (selected) CouliTextStyles.bodySemibold else CouliTypography.body,
                color = if (selected && enabled) CouliColors.brandEmphasis else CouliColors.textSecondary,
            )
        }
        // 下划线与本项同宽（画板 border-bottom 2px），只画选中项
        if (selected) {
            Box(modifier = Modifier.matchParentSize(), contentAlignment = Alignment.BottomCenter) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(
                            UNDERLINE.dp,
                        ).background(if (enabled) CouliColors.brandPrimary else CouliColors.borderSubtle),
                )
            }
        }
    }
}

/**
 * 排序一项（Search-v2）：14/21，选中主文字 600、未选次文字 400；命中 48。`Role.RadioButton` 与选中态由调用方原样给；
 * 选中项随选中值 / 尺寸变化滚入横滑视口（同平台 Tab）。
 */
@Composable
internal fun SearchSortItem(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    revealKey: Int = 0,
) {
    Box(
        modifier =
            modifier
                .selectedReveal(rememberSelectedReveal(selected, revealKey))
                .heightIn(min = CouliSizes.touchTarget)
                .widthIn(min = CouliSizes.touchTarget)
                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = if (selected) CouliTextStyles.footnoteSemibold else CouliTypography.footnote,
            color = if (selected && enabled) CouliColors.textPrimary else CouliColors.textSecondary,
        )
    }
}

/**
 * 筛选胶囊（Search-v2 / SearchEmpty-v2）：视觉 32 高、左右 12、圆角 999、12/18；未选白底 + 1px `border.subtle` + 主文字，选中
 * `brand.subtle` 底（同色描边）+ `brand.emphasis` 500；【价格区间】带 14 下箭头。外层命中 48，复选角色与选中态读屏原样。
 */
@Composable
internal fun SearchFilterCapsule(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: Boolean = false,
) {
    Box(
        modifier =
            modifier
                .heightIn(min = CouliSizes.touchTarget)
                .widthIn(min = CouliSizes.touchTarget)
                .selectable(selected = selected, enabled = enabled, role = Role.Checkbox, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CapsuleFace(
            background =
                when {
                    !enabled -> CouliColors.backgroundMuted
                    selected -> CouliColors.brandSubtle
                    else -> CouliColors.backgroundSurface
                },
            outline = if (selected && enabled) CouliColors.brandSubtle else CouliColors.borderSubtle,
        ) {
            Text(
                text = label,
                style = if (selected) CouliTextStyles.captionMedium else CouliTypography.caption,
                color =
                    when {
                        !enabled -> CouliColors.textSecondary
                        selected -> CouliColors.brandEmphasis
                        else -> CouliColors.textPrimary
                    },
            )
            if (trailing) {
                Icon(
                    CouliIcons.chevronDown,
                    contentDescription = null,
                    tint = if (selected && enabled) CouliColors.brandEmphasis else CouliColors.textSecondary,
                    modifier = Modifier.size(CouliSizes.iconTiny),
                )
            }
        }
    }
}

/**
 * 历史词胶囊（SearchTyping-v2）：视觉 32 高、左右 12、1px `border.subtle`、白底、14 主文字；外层命中 48。
 * 点一下等于提交这个词，读屏为按钮（不是复选，Q09 附）。与筛选胶囊分开写，不共用样式开关。
 */
@Composable
internal fun SearchHistoryCapsule(
    word: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .heightIn(min = CouliSizes.touchTarget)
                .widthIn(min = CouliSizes.touchTarget)
                .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CapsuleFace(background = CouliColors.backgroundSurface, outline = CouliColors.borderSubtle) {
            Text(text = word, style = CouliTypography.footnote, color = CouliColors.textPrimary)
        }
    }
}

/** 胶囊外观：恰好一层描边外框（视觉 32 高，随字号可长高）。 */
@Composable
private fun CapsuleFace(
    background: Color,
    outline: Color,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(CouliRadius.pill)
    Row(
        modifier =
            Modifier
                .heightIn(min = CAPSULE_HEIGHT.dp)
                .background(background, shape)
                .border(CouliSizes.hairline, outline, shape)
                .padding(horizontal = CouliSpacing.space3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
    ) {
        content()
    }
}

/**
 * 提示条（GUIDE §3 提示条：圆角 12、内边距 12、14 字、20 图标、左对齐顶对齐；search_disabled 回落 / 缓存降级用警示色）。
 * polite 播报与标记由调用方挂在 [modifier] 上。
 */
@Composable
internal fun SearchNoticeFace(
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(CouliColors.statusWarningBackground, RoundedCornerShape(CouliRadius.button))
                .padding(CouliSpacing.space3),
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            CouliIcons.info,
            contentDescription = null,
            tint = CouliColors.statusWarningText,
            modifier = Modifier.size(CouliSizes.iconMedium),
        )
        Text(text = text, style = CouliTypography.footnote, color = CouliColors.statusWarningText, modifier = Modifier.weight(1f))
    }
}

/**
 * 价格区间一栏（SearchFilterPrice-v2）：12 次文字标签在框上方（间距 4），框 min 48（画板 44）、1px `border.control`、圆角 12、
 * 内边距 8/12、输入 17（金额等宽）。读屏名仍为标签（框内不再画占位）。
 */
@Composable
internal fun SearchPriceField(
    value: String,
    label: String,
    tag: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(CouliRadius.button)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
        Text(text = label, style = CouliTypography.caption, color = CouliColors.textSecondary)
        Box(
            modifier =
                Modifier
                    .priceFrameCenterLine()
                    .fillMaxWidth()
                    .heightIn(min = CouliSizes.touchTarget)
                    .border(CouliSizes.hairline, CouliColors.borderControl, shape)
                    .background(CouliColors.backgroundSurface, shape)
                    .padding(horizontal = CouliSpacing.space3, vertical = CouliSpacing.space2),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = PriceInputStyle.copy(color = CouliColors.textPrimary),
                cursorBrush = SolidColor(CouliColors.textPrimary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().testTag(tag).semantics { contentDescription = label },
            )
        }
    }
}

/**
 * 价格框外框的垂直中线：挂在 [SearchPriceField] 的输入框外框上，随标签与输入字号一起变化（200% 时标签变高、框也变高）。
 * 同一行里两栏用 `alignBy(SearchPriceFrameCenter)`，横杠用自身中线对齐它，不按固定偏移推算。
 */
internal val SearchPriceFrameCenter = HorizontalAlignmentLine { old, _ -> old }

private fun Modifier.priceFrameCenterLine(): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height, mapOf(SearchPriceFrameCenter to placeable.height / 2)) { placeable.place(0, 0) }
    }

/** 价格两栏之间的「–」（画板 aria-hidden）：纯装饰，清除语义；按自身中线与两栏输入框外框中线 [SearchPriceFrameCenter] 对齐。 */
@Composable
internal fun RowScope.SearchPriceDash(text: String) {
    Text(
        text = text,
        style = CouliTypography.body,
        color = CouliColors.textSecondary,
        modifier = Modifier.alignBy { it.measuredHeight / 2 }.clearAndSetSemantics {},
    )
}

/** 口径说明半屏正文（SearchDisclaimerNote-v2）：每句 14 主文字，句间 8。 */
@Composable
internal fun SearchDisclaimerLines(lines: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
        lines.forEach { line -> Text(text = line, style = CouliTypography.footnote, color = CouliColors.textPrimary) }
    }
}

/** 价格输入 17 常规、等宽数字（画板 17/400；金额一律等宽，GUIDE §2）。 */
private val PriceInputStyle: TextStyle =
    with(CouliTextStyles) { CouliTypography.body.copy(fontWeight = FontWeight.Normal).tabular() }

private const val UNDERLINE = 2
private const val TAB_ICON_GAP = 6
private const val CAPSULE_HEIGHT = 32

/**
 * 选中项滚入所在横滑行的视口（S2-3）：选中值、自身尺寸（字号变化）或行视口宽 revealKey 变化时请求一次 bringIntoView，
 * 只滚动视图，不调回调、不发请求；用户手动横滑后不会被拉回，直到下一次上述变化。
 */
private class SelectedReveal {
    val requester = BringIntoViewRequester()
    var size by mutableStateOf(IntSize.Zero)
}

@Composable
private fun rememberSelectedReveal(
    selected: Boolean,
    revealKey: Int,
): SelectedReveal {
    val reveal = remember { SelectedReveal() }
    LaunchedEffect(selected, reveal.size, revealKey) {
        if (selected && reveal.size != IntSize.Zero) reveal.requester.bringIntoView()
    }
    return reveal
}

private fun Modifier.selectedReveal(reveal: SelectedReveal): Modifier =
    bringIntoViewRequester(reveal.requester).onSizeChanged { reveal.size = it }
