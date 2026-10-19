package com.couli.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/** 提示条的语气。 */
enum class NoticeTone {
    INFO,
    WARNING,
    ERROR,
    SUCCESS,
}

/**
 * 提示条（组件清单 §3.6 `NoticeBar`；现 `InfoStrip` 的通用版）：图标 + 文字（14），按语气取 status.* 底与字色，
 * 右侧可带文字按钮（如「去绑定」「重新登录」）。状态不只靠颜色（带图标）。
 */
@Composable
fun NoticeBar(
    text: String,
    modifier: Modifier = Modifier,
    tone: NoticeTone = NoticeTone.INFO,
    action: StateAction? = null,
) {
    val (background, content, icon) =
        when (tone) {
            NoticeTone.INFO -> Triple(CouliColors.backgroundMuted, CouliColors.textPrimary, CouliIcons.info)
            NoticeTone.WARNING -> Triple(CouliColors.statusWarningBackground, CouliColors.statusWarningText, CouliIcons.alert)
            NoticeTone.ERROR -> Triple(CouliColors.statusErrorBackground, CouliColors.statusErrorText, CouliIcons.alert)
            NoticeTone.SUCCESS -> Triple(CouliColors.statusSuccessBackground, CouliColors.statusSuccessText, CouliIcons.check)
        }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(CouliRadius.button))
                .background(background)
                .padding(start = CouliSpacing.space3, end = if (action == null) CouliSpacing.space3 else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(CouliComponentTokens.iconMedium))
        Text(
            text = text,
            style = CouliTypography.footnote,
            color = if (tone == NoticeTone.INFO) CouliColors.textPrimary else content,
            modifier = Modifier.weight(1f).padding(vertical = CouliSpacing.space3),
        )
        if (action != null) {
            CouliButton(text = action.label, onClick = action.onClick, variant = CouliButtonVariant.TEXT, enabled = action.enabled)
        }
    }
}

/** 加载指示的尺寸：小（按钮内、行内）、中（区域居中）。 */
enum class LoadingSize {
    SMALL,
    MEDIUM,
}

/** 加载指示（组件清单 §3.6 `Loading`）：M3 `CircularProgressIndicator`，brand.primary，轨道 brand.subtle；[description] 是读屏「加载中」。 */
@Composable
fun CouliLoading(
    description: String,
    modifier: Modifier = Modifier,
    size: LoadingSize = LoadingSize.MEDIUM,
) {
    CircularProgressIndicator(
        modifier =
            modifier
                .size(if (size == LoadingSize.SMALL) CouliSizes.iconSmall else CouliSpacing.space8)
                .semantics { contentDescription = description },
        color = CouliColors.brandPrimary,
        trackColor = CouliColors.brandSubtle,
        strokeWidth = if (size == LoadingSize.SMALL) CouliSizes.hairline * 2 else CouliSizes.hairline * 3,
    )
}

/** 下拉刷新的指示器（组件清单 §3.6 `PullToRefresh`）：M3 `PullToRefreshDefaults.Indicator`，白底圆 + brand.primary。配 M3 `PullToRefreshBox` 用。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CouliPullToRefreshIndicator(
    state: PullToRefreshState,
    isRefreshing: Boolean,
    modifier: Modifier = Modifier,
) {
    PullToRefreshDefaults.Indicator(
        state = state,
        isRefreshing = isRefreshing,
        modifier = modifier,
        containerColor = CouliColors.backgroundSurface,
        color = CouliColors.brandPrimary,
    )
}

/**
 * 系统确认框（组件清单 §3.6 `AlertDialog`，DS-02 新增）：只有标题 + 一两句 + 最多两个按钮的确认（清除缓存、退出登录）。
 * 容器是 M3 `BasicAlertDialog`（系统窗口，返回与点外部关闭），内容 [CouliAlertDialogContent] 按 M3 确认框排版、令牌配色：
 * 按钮为 M3 `TextButton`（brand.emphasis；[destructive] 时确认用 status.error.text）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CouliAlertDialog(
    title: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    dismissText: String? = null,
    destructive: Boolean = false,
) {
    val fontScale = callerFontScale()
    BasicAlertDialog(onDismissRequest = onDismissRequest, modifier = modifier) {
        WindowFontScale(fontScale) {
            CouliAlertDialogContent(
                title = title,
                confirmText = confirmText,
                onConfirm = onConfirm,
                message = message,
                dismissText = dismissText,
                onDismiss = onDismissRequest,
                destructive = destructive,
            )
        }
    }
}

/** 系统确认框的内容（单独暴露给目录页与快照直接排版）。按钮放不下时换行（200% 字号）。 */
@Composable
fun CouliAlertDialogContent(
    title: String,
    confirmText: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    dismissText: String? = null,
    onDismiss: () -> Unit = {},
    destructive: Boolean = false,
) {
    Surface(
        modifier = modifier.widthIn(max = ALERT_MAX_WIDTH).semantics { paneTitle = title },
        shape = RoundedCornerShape(CouliRadius.panel),
        color = CouliColors.backgroundSurface,
        contentColor = CouliColors.textPrimary,
    ) {
        Column(modifier = Modifier.padding(CouliSpacing.space6), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space4)) {
            Text(
                text = title,
                style = CouliTextStyles.titleSemibold,
                color = CouliColors.textPrimary,
                modifier = Modifier.semantics { heading() },
            )
            if (message != null) Text(text = message, style = CouliTypography.footnote, color = CouliColors.textSecondary)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2, Alignment.End),
            ) {
                if (dismissText != null) {
                    TextButton(onClick = onDismiss) {
                        Text(text = dismissText, style = CouliTextStyles.footnoteSemibold, color = CouliColors.brandEmphasis)
                    }
                }
                TextButton(onClick = onConfirm) {
                    Text(
                        text = confirmText,
                        style = CouliTextStyles.footnoteSemibold,
                        color = if (destructive) CouliColors.statusErrorText else CouliColors.brandEmphasis,
                    )
                }
            }
        }
    }
}

/** 操作菜单的一项。 */
@Immutable
data class ActionMenuItem(
    val label: String,
    val destructive: Boolean = false,
)

/**
 * 操作菜单（组件清单 §3.6 `ActionMenu`，DS-02 新增）：几项里选一个去处、没有说明文字。底部款 = M3 `ModalBottomSheet` + 列表项
 * （[ActionMenuSheetContent]）；锚点款见 [CouliAnchoredMenu]（M3 `DropdownMenu`）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CouliActionMenu(
    items: List<ActionMenuItem>,
    onSelect: (Int) -> Unit,
    onDismissRequest: () -> Unit,
    title: String? = null,
) {
    val fontScale = callerFontScale()
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = CouliRadius.panel, topEnd = CouliRadius.panel),
        containerColor = CouliColors.backgroundSurface,
        scrimColor = CouliColors.overlayScrim,
        dragHandle = { SheetHandle() },
    ) {
        WindowFontScale(fontScale) { ActionMenuSheetContent(items = items, onSelect = onSelect, title = title) }
    }
}

/** 底部操作菜单的内容：可选标题 + 每项一行（48 高、系统水波纹）。 */
@Composable
fun ActionMenuSheetContent(
    items: List<ActionMenuItem>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
) {
    Column(modifier = modifier.fillMaxWidth().padding(bottom = CouliSpacing.space4)) {
        if (title != null) {
            Text(
                text = title,
                style = CouliTypography.footnote,
                color = CouliColors.textSecondary,
                modifier = Modifier.padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space2),
            )
        }
        items.forEachIndexed { index, item -> ActionMenuRow(item) { onSelect(index) } }
    }
}

/**
 * 锚在控件旁的操作菜单：M3 `DropdownMenu`（系统弹出窗）。内容与 [AnchoredMenuContent] 相同。
 */
@Composable
fun CouliAnchoredMenu(
    expanded: Boolean,
    items: List<ActionMenuItem>,
    onSelect: (Int) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val fontScale = callerFontScale()
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        containerColor = CouliColors.backgroundSurface,
        shape = RoundedCornerShape(CouliRadius.button),
    ) {
        WindowFontScale(fontScale) {
            items.forEachIndexed { index, item -> ActionMenuRow(item) { onSelect(index) } }
        }
    }
}

/** 锚点菜单的内容（目录页与快照直接排版用；真实弹出见 [CouliAnchoredMenu]）。 */
@Composable
fun AnchoredMenuContent(
    items: List<ActionMenuItem>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.widthIn(min = MENU_MIN_WIDTH),
        shape = RoundedCornerShape(CouliRadius.button),
        color = CouliColors.backgroundSurface,
        shadowElevation = CouliSpacing.space2,
    ) {
        Column(modifier = Modifier.padding(vertical = CouliSpacing.space2)) {
            items.forEachIndexed { index, item -> ActionMenuRow(item) { onSelect(index) } }
        }
    }
}

@Composable
private fun ActionMenuRow(
    item: ActionMenuItem,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(text = item.label, style = CouliTypography.body) },
        onClick = onClick,
        colors =
            MenuDefaults.itemColors(
                textColor = if (item.destructive) CouliColors.statusErrorText else CouliColors.brandEmphasis,
            ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 半屏外观的静态框（目录页展示半屏的「中 / 全高 / 键盘弹起」用）：上两角 24 圆角 surface 底 + 把手。 */
@Composable
fun SheetFrame(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = CouliRadius.panel, topEnd = CouliRadius.panel))
                .background(CouliColors.backgroundSurface),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SheetHandle()
        Box(modifier = Modifier.fillMaxWidth()) { content() }
    }
}

private val ALERT_MAX_WIDTH = 560.dp
private val MENU_MIN_WIDTH = 112.dp

/**
 * 系统窗口（Dialog / ModalBottomSheet / DropdownMenu 的 Popup）在自己的组合里按窗口重设 [LocalDensity]，调用处局部改过的字号
 * （组件目录页的 200% 预览等）带不进去。打开窗口前用 [callerFontScale] 记下调用处字号，窗口内容外包 [WindowFontScale]：
 * 像素密度仍取窗口自己的，只把字号倍数换成调用处的。平时两者相同，等于不变。
 */
@Composable
internal fun callerFontScale(): Float = LocalDensity.current.fontScale

@Composable
internal fun WindowFontScale(
    fontScale: Float,
    content: @Composable () -> Unit,
) {
    val window = LocalDensity.current
    if (window.fontScale == fontScale) {
        content()
    } else {
        CompositionLocalProvider(LocalDensity provides Density(window.density, fontScale), content = content)
    }
}
