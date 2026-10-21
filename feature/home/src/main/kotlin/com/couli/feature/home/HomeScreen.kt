package com.couli.feature.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
 * 首页弹窗（MainPopup 版式，G14 ⑦⑧，F-HOME-08）：系统模态对话框承载——返回键、点遮罩等同关闭；读屏只能访问弹窗内容，顺序为 弹窗名 → 关闭 → 图片
 * （关闭按钮在语义树里先于图片，出现时焦点到关闭），关闭后焦点回到首页原处（对话框收起由系统还原）。只显示图片 + 关闭；关闭按钮与弹窗同时出现、
 * 命中区 48dp、不被图片遮挡，点一次即关，不以任何前置条件为关闭条件。回调捕获的是这个弹窗实例的票据（共性第 17 条）。
 */
@Composable
private fun HomePopupDialog(
    popup: HomePopupUi,
    controller: HomeController,
) {
    val ticket = popup.ticket
    Dialog(
        onDismissRequest = { controller.closePopup(ticket) },
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        val closeFocus = remember { FocusRequester() }
        // 对话框内容已组合：真正显示，这时才计数（G14 ⑤）
        LaunchedEffect(ticket) {
            controller.popupShown(ticket)
            runCatching { closeFocus.requestFocus() }
        }
        val name = HomeDraftTexts.text(HomeDraftTexts.POPUP_NAME)
        Column(
            modifier =
                Modifier
                    .widthIn(max = POPUP_MAX_WIDTH.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(CouliRadius.card))
                    .background(CouliColors.backgroundSurface)
                    .semantics { paneTitle = name }
                    .testTag(HomeTags.POPUP),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(
                    modifier =
                        Modifier
                            .size(CouliSizes.touchTarget)
                            .focusRequester(closeFocus)
                            .clickable(role = Role.Button) { controller.closePopup(ticket) }
                            .semantics { contentDescription = HomeDraftTexts.text(HomeDraftTexts.POPUP_CLOSE) }
                            .testTag(HomeTags.POPUP_CLOSE),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        CouliIcons.close,
                        contentDescription = null,
                        tint = CouliColors.textPrimary,
                        modifier = Modifier.size(CouliSizes.iconMedium),
                    )
                }
            }
            Box(modifier = Modifier.padding(start = CouliSpacing.space3, end = CouliSpacing.space3, bottom = CouliSpacing.space3)) {
                Image(
                    bitmap = popup.image,
                    contentDescription = name,
                    contentScale = ContentScale.Fit,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = POPUP_IMAGE_MAX_HEIGHT.dp)
                            .aspectRatio(popup.image.width.toFloat() / popup.image.height.coerceAtLeast(1))
                            .clip(RoundedCornerShape(CouliRadius.small))
                            .then(
                                if (popup.tappable) {
                                    Modifier.clickable(role = Role.Button) { controller.tapPopup(ticket) }
                                } else {
                                    Modifier
                                },
                            ).testTag(HomeTags.POPUP_IMAGE),
                )
                // 推广标识（R1-03）：非 null 原样显示在图片角上，读屏读出
                popup.adLabel?.let { AdLabel(it, Modifier.align(Alignment.TopStart).padding(CouliSpacing.space2)) }
            }
        }
    }
}

private const val POPUP_MAX_WIDTH = 320
private const val POPUP_IMAGE_MAX_HEIGHT = 420

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
