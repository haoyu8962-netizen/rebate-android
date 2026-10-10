package com.couli.feature.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import com.couli.core.designsystem.DisclaimerFooter
import com.couli.core.designsystem.DisclaimerLine
import com.couli.core.designsystem.ErrorState
import com.couli.core.designsystem.StateAction
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
 * 存在控制器里（切 Tab、从子页返回保持）。[footer] 是列表最底部的附加项（HomePreview 的「被丢弃的卡」列表）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeSduiScreen(
    controller: HomeController,
    signals: HomeSignals,
    modifier: Modifier = Modifier,
    footer: LazyListScope.() -> Unit = {},
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val visible by signals.visible.collectAsStateWithLifecycle()
    val reader by signals.screenReader.collectAsStateWithLifecycle()
    val render = state.render
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        if (state.loadFailed) {
            // 最后防线（G16 ⑦）：包内默认也解码失败。可滚动（短视口、200% 字号下【重试】也够得着，共性第 31 条），限宽居中
            Box(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
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
        } else {
            key(render) {
                HomeList(controller, state, active = visible && !reader, reader = reader, footer = footer)
            }
        }
        state.toast?.let { toast ->
            HomeToastHost(toast, controller)
        }
    }
    state.popup?.let { popup -> HomePopupDialog(popup, controller) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeList(
    controller: HomeController,
    state: HomeUiState,
    active: Boolean,
    reader: Boolean,
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
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().testTag(HomeTags.LIST),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            state.cards.forEach { card -> homeCard(card, controller, active, reader, CONTENT_MAX_WIDTH) }
            if (state.disclaimers.isNotEmpty()) {
                item(key = HomeListKeys.DISCLAIMER) {
                    DisclaimerFooter(
                        lines =
                            state.disclaimers.mapIndexed { i, text ->
                                DisclaimerLine(
                                    text,
                                    icon =
                                        if (i ==
                                            0
                                        ) {
                                            CouliIcons.info
                                        } else {
                                            null
                                        },
                                )
                            },
                        modifier =
                            Modifier
                                .widthIn(max = CONTENT_MAX_WIDTH.dp)
                                .fillMaxWidth()
                                .padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space2)
                                .testTag(HomeTags.DISCLAIMER),
                    )
                }
            }
            footer()
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
