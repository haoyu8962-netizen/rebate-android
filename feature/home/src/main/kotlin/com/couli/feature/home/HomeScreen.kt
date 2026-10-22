package com.couli.feature.home

import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliRadius
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliToast
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.ErrorState
import com.couli.core.designsystem.StateAction
import com.couli.core.designsystem.couliCard
import com.couli.core.designsystem.rememberProductGridColumns
import com.couli.core.home.HomeDraftTexts
import com.couli.core.home.HomeErrors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

/** 首页内容的最大宽度（大屏限宽居中，03 §10.4；与搜索、详情页同为 600dp）。 */
private const val CONTENT_MAX_WIDTH = 600

/**
 * 首页 SDUI 区（03 §6.2）：下拉刷新 + 按页面配置渲染卡片 + 页底口径说明 + 弹窗 + 轻提示。所有数据都由 [controller] 算好，这里只做纯展示；回调捕获的是渲染时的
 * 票据（[HomeUiState.render] 等），执行时由控制器比较。列表本体按渲染世代做键：内容替换后旧节点连同旧回调一起销毁、回到顶部（G23）；同一世代内滚动位置
 * 存在控制器里（切 Tab、从子页返回保持）。
 *
 * [header] 是列表最上方的客户端原生项（APP-11v G-V02：App 传入剪贴板提示条、游客价值卡、粘贴卡；键一律用 [HomeListKeys.header]）：与 SDUI 卡、页脚同一个
 * 滚动列表、同一个下拉刷新（刷新只刷 SDUI，原生项的状态不归控制器管）。[footer] 是列表最底部的附加项（HomePreview 的「被丢弃的卡」列表）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeSduiScreen(
    controller: HomeController,
    signals: HomeSignals,
    modifier: Modifier = Modifier,
    header: LazyListScope.() -> Unit = {},
    footer: LazyListScope.() -> Unit = {},
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val visible by signals.visible.collectAsStateWithLifecycle()
    val reader by signals.screenReader.collectAsStateWithLifecycle()
    val render = state.render
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        if (state.loadFailed) {
            // 最后防线（G16 ⑦）：包内默认也解码失败。原生头部照常显示（顶部原生区不属 SDUI）；整体可滚动（短视口、200% 字号下【重试】也够得着，
            // 共性第 31 条），限宽居中
            LazyColumn(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                header()
                item(key = HomeListKeys.extra("load_failed")) {
                    ErrorState(
                        title = HomeDraftTexts.text(HomeDraftTexts.LOAD_FAILED_TITLE),
                        // 触发它的实际失败（R2-04）；禁用期中【重试】置灰、到期恢复（R2-03）
                        message = state.loadFailedMessage ?: HomeErrors.message(HomeErrors.GENERIC, null),
                        retry =
                            StateAction(HomeDraftTexts.text(HomeDraftTexts.RETRY), enabled = !state.rateLimited) {
                                controller.retryLoadFailed(render)
                            },
                        modifier = Modifier.widthIn(max = CONTENT_MAX_WIDTH.dp).fillMaxWidth().testTag(HomeTags.LOAD_FAILED),
                    )
                }
            }
        } else {
            key(render) {
                HomeList(controller, state, active = visible && !reader, reader = reader, header = header, footer = footer)
            }
        }
        state.toast?.let { toast ->
            HomeToastHost(toast, controller)
        }
    }
    state.popup?.let { popup -> HomePopupDialog(popup, controller) }
}

@Suppress("LongParameterList") // 列表状态、可见性与头尾两个槽
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeList(
    controller: HomeController,
    state: HomeUiState,
    active: Boolean,
    reader: Boolean,
    header: LazyListScope.() -> Unit,
    footer: LazyListScope.() -> Unit,
) {
    val render = state.render
    val listState = rememberLazyListState(controller.scrollIndex, controller.scrollOffset)
    DisposableEffect(listState) {
        onDispose {
            controller.scrollIndex = listState.firstVisibleItemIndex
            controller.scrollOffset = listState.firstVisibleItemScrollOffset
        }
    }
    // 用户在首页滚动过（G16 ③）
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.filter { it }.first()
        controller.interacted(render)
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }.collect { (index, offset) ->
            controller.scrollIndex = index
            controller.scrollOffset = offset
        }
    }
    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = { controller.refresh(render) },
        modifier = Modifier.fillMaxSize(),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // 信息流按行进列表：整组列数（窄屏 + 大字号整组单列，R2-01）要在拆行前定，按内容区宽度（限宽 600、左右 16）算
            val contentWidth = minOf(maxWidth, CONTENT_MAX_WIDTH.dp) - CouliSpacing.space4 * 2
            val feedColumns = rememberFeedColumns(state.cards, contentWidth)
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().testTag(HomeTags.LIST),
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(bottom = CouliSpacing.space4),
            ) {
                // 列表起始的 12 间距（搜索行与第一块之间）：固定高度的首项，后面的块（含此刻没内容、高 0 的原生头部）长高时列表从这里往下推，
                // 不会因首项高 0 而把新出现的提示条留在可见区上方
                item(key = HomeListKeys.extra("top"), contentType = "home_top") { Spacer(Modifier.height(HomeLayout.blockGap)) }
                header()
                state.cards.forEach { card ->
                    homeCard(card, controller, active, reader, CONTENT_MAX_WIDTH, feedColumns[card.id] ?: FEED_COLUMNS)
                }
                if (state.disclaimers.isNotEmpty()) {
                    item(key = HomeListKeys.DISCLAIMER) {
                        HomeDisclaimerFooter(
                            lines = state.disclaimers,
                            modifier =
                                Modifier
                                    .widthIn(max = CONTENT_MAX_WIDTH.dp)
                                    .fillMaxWidth()
                                    .padding(horizontal = CouliSpacing.space4)
                                    .testTag(HomeTags.DISCLAIMER),
                        )
                    }
                }
                footer()
            }
        }
    }
}

/** 每个信息流卡的整组列数（同 [ProductCardGridLayout] 的口径）。 */
@Composable
private fun rememberFeedColumns(
    cards: List<HomeCardUi>,
    width: Dp,
): Map<String, Int> =
    cards.filterIsInstance<HomeCardUi.Feed>().associate { feed ->
        feed.id to
            key(feed.id) {
                val data = remember(feed.items) { feed.items.map { it.data } }
                rememberProductGridColumns(data, width, FEED_COLUMNS, includeRebateHeader = true)
            }
    }

/**
 * 首页口径页脚（Main-v3：列表最底，顶部 1 分隔线 + 两行 12 次文字，无 i 图标、无卡底）。文字由控制器按 disclaimer_keys 取好；只是说明文字，读屏逐行读。
 */
@Composable
private fun HomeDisclaimerFooter(
    lines: List<String>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Box(modifier = Modifier.fillMaxWidth().height(CouliSizes.hairline).background(CouliColors.borderSubtle))
        Column(
            modifier = Modifier.padding(start = CouliSpacing.space1, end = CouliSpacing.space1, top = CouliSpacing.space3),
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
        ) {
            lines.forEach { line -> Text(text = line, style = CouliTypography.caption, color = CouliColors.textSecondary) }
        }
    }
}

@Composable
private fun HomeToastHost(
    toast: HomeToast,
    controller: HomeController,
) {
    LaunchedEffect(toast.id) {
        delay(TOAST_MS)
        controller.consumeToast(toast.id)
    }
    Box(modifier = Modifier.fillMaxSize().padding(CouliSpacing.space4), contentAlignment = Alignment.BottomCenter) {
        CouliToast(message = toast.message, icon = CouliIcons.alert, announcementId = toast.id, modifier = Modifier.testTag(HomeTags.TOAST))
    }
}

private const val TOAST_MS = 2_500L

/**
 * 首页弹窗（MainPopupStatic / MainPopupDark 版式，APP-11v A15；G14 ⑦⑧，F-HOME-08）：系统模态对话框承载——返回键、点遮罩等同关闭；读屏只能访问
 * 弹窗内容，顺序为 弹窗名 → 关闭 → 图片（关闭按钮语义遍历序先于图片，出现时焦点到关闭），关闭后焦点回到首页原处（对话框收起由系统还原）。
 * 只有图片区 + 右上角圆底关闭：无衬底卡、无关闭行；图片圆角 16、按图宽高比、左右各留 32（窗口矮时按高度收）；关闭视觉 40、命中区 48，压在图片右上角、
 * 绘制与触摸都在图片之上，与弹窗同时出现，点一次即关，不以任何前置条件为关闭条件。`ad_label` 原样显示在图片左上。回调捕获这个弹窗实例的票据（共性第 17 条）。
 */
@Composable
private fun HomePopupDialog(
    popup: HomePopupUi,
    controller: HomeController,
) {
    val ticket = popup.ticket
    // 对话框是独立窗口：显式接续首页的字号缩放（系统 200% 时弹窗内文字同样放大）
    val density = LocalDensity.current
    Dialog(
        onDismissRequest = { controller.closePopup(ticket) },
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true, usePlatformDefaultWidth = false),
    ) {
        CompositionLocalProvider(LocalDensity provides density) { HomePopupContent(popup, controller) }
    }
}

@Composable
private fun HomePopupContent(
    popup: HomePopupUi,
    controller: HomeController,
) {
    val ticket = popup.ticket
    PopupScrim()
    // 对话框内容已组合：真正显示，这时才计数（G14 ⑤）
    LaunchedEffect(ticket) { controller.popupShown(ticket) }
    val name = HomeDraftTexts.text(HomeDraftTexts.POPUP_NAME)
    val ratio = popup.image.width.toFloat() / popup.image.height.coerceAtLeast(1)
    BoxWithConstraints(modifier = Modifier.safeDrawingPadding(), contentAlignment = Alignment.Center) {
        // 宽 = 窗口宽 − 左右各 32（最宽 POPUP_MAX_WIDTH）；高按图比例，超出窗口高 − 上下各 24 时按高度收、宽随之收
        val maxImageWidth = (maxWidth - CouliSpacing.space8 * 2).coerceIn(0.dp, POPUP_MAX_WIDTH.dp)
        val maxImageHeight = (maxHeight - CouliSpacing.space6 * 2).coerceAtLeast(0.dp)
        val width = minOf(maxImageWidth, maxImageHeight * ratio)
        val shape = RoundedCornerShape(CouliRadius.card)
        // 关闭层独立于图片比例与裁剪：外框至少容下关闭命中区（48 + 距边 4×2），图片在外框内居中、单独裁圆角；
        // 常规比例下外框 = 图片框，关闭照旧压在图片右上；横图 / 细长图 / 短视口下关闭仍完整 48 且在图片之上（后绘制、先接触摸）
        val closeLayer = CouliSizes.touchTarget + CouliSpacing.space1 * 2
        Box(
            modifier =
                Modifier
                    .size(
                        width.coerceAtLeast(closeLayer).coerceAtMost(maxWidth),
                        (width / ratio).coerceAtLeast(closeLayer).coerceAtMost(maxHeight),
                    ).semantics {
                        paneTitle = name
                        isTraversalGroup = true
                    }.testTag(HomeTags.POPUP),
        ) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .size(width, width / ratio)
                        .couliCard(shape = shape)
                        .clip(shape),
            ) {
                Image(
                    bitmap = popup.image,
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .then(
                                if (popup.tappable) {
                                    Modifier.clickable(role = Role.Button) { controller.tapPopup(ticket) }
                                } else {
                                    Modifier
                                },
                            ).testTag(HomeTags.POPUP_IMAGE),
                )
                // 推广标识（R1-03）：非 null 原样显示在图片左上，读屏读出
                popup.adLabel?.let { AdLabel(it, Modifier.align(Alignment.TopStart).padding(CouliSpacing.space2)) }
            }
            PopupCloseButton(
                onClick = { controller.closePopup(ticket) },
                modifier = Modifier.align(Alignment.TopEnd).padding(CouliSpacing.space1),
            )
        }
    }
}

/**
 * 遮罩用令牌 `overlay.scrim`（浅 / 深随主题）：关掉系统 Dialog 默认 dim（只能是黑色、且会与令牌重复叠加），
 * 改由铺满屏幕的对话框窗口背景画令牌色；点遮罩关闭仍由 Dialog 的点外部处理。
 */
@Composable
private fun PopupScrim() {
    val view = LocalView.current
    val scrim = CouliColors.overlayScrim.toArgb()
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window?.apply {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // 窗口铺满屏幕，令牌遮罩覆盖整屏（内容仍按图片居中，点内容以外仍按点遮罩关闭）
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            decorView.setBackgroundColor(scrim)
        }
    }
}

/** 弹窗关闭：命中区 48（外层），视觉 40 圆底（background.surface + 卡片阴影，深色随令牌）+ 24 关闭图标；语义遍历序排在图片之前。 */
@Composable
private fun PopupCloseButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 打开时焦点到关闭：按钮在 BoxWithConstraints 的子组合里，挂上后才请求（外层效果先于子组合执行会落空）
    val focus = remember { FocusRequester() }
    LaunchedEffect(focus) { runCatching { focus.requestFocus() } }
    Box(
        modifier =
            modifier
                .size(CouliSizes.touchTarget)
                .focusRequester(focus)
                // 触摸模式下 clickable 不可聚焦，这里显式可聚焦，打开时焦点能落到关闭
                .focusable()
                .clickable(role = Role.Button, onClick = onClick)
                .semantics {
                    contentDescription = HomeDraftTexts.text(HomeDraftTexts.POPUP_CLOSE)
                    traversalIndex = -1f
                }.testTag(HomeTags.POPUP_CLOSE),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(POPUP_CLOSE_VISUAL.dp).couliCard(shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                CouliIcons.close,
                contentDescription = null,
                tint = CouliColors.textPrimary,
                modifier = Modifier.size(CouliSizes.iconLarge),
            )
        }
    }
}

/** 弹窗图片最宽（折叠屏内屏 / 平板不随窗口无限放大）；手机 390 宽时 = 326（左右各 32）。 */
private const val POPUP_MAX_WIDTH = 400

/** 关闭按钮视觉直径（画板 44，任务书取 ≈40，命中区仍 48，与图片右上各留 8）。 */
private const val POPUP_CLOSE_VISUAL = 40

/**
 * 首页根视图的全局置灰（03 §6.2 第 8 步，G27）：`ui.grayscale=true` 时给首页根视图加饱和度 0 的颜色矩阵；只作用于调用处的根视图。
 */
fun Modifier.homeGrayscale(enabled: Boolean): Modifier =
    if (!enabled) {
        this
    } else {
        drawWithCache {
            val paint = Paint().apply { colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }
            onDrawWithContent {
                drawIntoCanvas { canvas ->
                    canvas.saveLayer(Rect(Offset.Zero, size), paint)
                    drawContent()
                    canvas.restore()
                }
            }
        }
    }
