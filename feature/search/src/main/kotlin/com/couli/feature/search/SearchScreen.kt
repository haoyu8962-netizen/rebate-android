package com.couli.feature.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.couli.core.designsystem.BuyButtonModel
import com.couli.core.designsystem.BuyButtonState
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonSize
import com.couli.core.designsystem.CouliButtonVariant
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliFormat
import com.couli.core.designsystem.CouliIconButton
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliRadius
import com.couli.core.designsystem.CouliSheet
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.DisclaimerFooter
import com.couli.core.designsystem.DisclaimerKeys
import com.couli.core.designsystem.DisclaimerLine
import com.couli.core.designsystem.EmptyState
import com.couli.core.designsystem.ErrorAnnouncement
import com.couli.core.designsystem.ErrorState
import com.couli.core.designsystem.MappedProductCard
import com.couli.core.designsystem.ProductCardKeys
import com.couli.core.designsystem.ProductCardList
import com.couli.core.designsystem.ProductCardMapper
import com.couli.core.designsystem.RestoreLanding
import com.couli.core.designsystem.SkeletonProductCard
import com.couli.core.designsystem.StateAction
import com.couli.core.designsystem.awaitLanding
import com.couli.core.designsystem.couliFocusTarget
import com.couli.core.network.ResultSignals
import com.couli.generated.Sort
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 测试与冒烟用的标记。 */
object SearchTags {
    const val PAGE = "search_page"
    const val INPUT = "search_input"
    const val SUBMIT = "search_submit"
    const val CLEAR = "search_clear"
    const val PASTE_ENTRY = "search_paste_entry"
    const val TAB_PREFIX = "search_tab_"
    const val SORT_PREFIX = "search_sort_"
    const val COUPON = "search_filter_coupon"
    const val PRICE = "search_filter_price"
    const val RESULTS = "search_results"
    const val CARD_PREFIX = "search_card_"

    /** 卡片下的比价无返利原因行（后接 [cardKey]）；其中【看看相似商品】再加后缀 _similar。 */
    const val COMPARE_PREFIX = "search_compare_"
    const val ERROR = "search_error"
    const val RETRY = "search_retry"
    const val NOTICE = "search_notice"
    const val STALE = "search_stale"
    const val LOAD_MORE = "search_load_more"
    const val LOAD_MORE_ERROR = "search_load_more_error"
    const val END = "search_end"
    const val EMPTY = "search_empty"
    const val UNAVAILABLE = "search_unavailable"
    const val HISTORY_PREFIX = "search_history_"
    const val HISTORY_CLEAR = "search_history_clear"
    const val PRICE_MIN = "search_price_min"
    const val PRICE_MAX = "search_price_max"
    const val PRICE_CONFIRM = "search_price_confirm"
    const val PRICE_RESET = "search_price_reset"

    /** 结果区底部常驻的口径说明（BR-PRICE-03「列表每屏底部一次」）。 */
    const val DISCLAIMER = "search_disclaimer"
}

/** 文案键（草稿表 SearchDraftTexts 三端同名；有契约键的直接用契约键）。 */
internal object SearchKeys {
    const val BACK = "search.back"
    const val PLACEHOLDER = "search.placeholder"
    const val CLEAR = "search.clear"
    const val SUBMIT = "search.submit"
    const val HISTORY_TITLE = "search.history.title"
    const val HISTORY_CLEAR = "search.history.clear"
    const val COUPON = "search.filter.coupon"
    const val PRICE = "search.filter.price"
    const val PRICE_RANGE = "search.filter.price_range"
    const val PRICE_MIN_ONLY = "search.filter.price_min_only"
    const val PRICE_MAX_ONLY = "search.filter.price_max_only"
    const val SHEET_MIN = "search.filter.sheet.min"
    const val SHEET_MAX = "search.filter.sheet.max"
    const val SHEET_NOTE = "search.filter.sheet.note"
    const val SHEET_RESET = "search.filter.sheet.reset"
    const val SHEET_CONFIRM = "search.filter.sheet.confirm"
    const val SHEET_INVALID = "search.filter.sheet.invalid"
    const val EMPTY_TITLE = "search.empty.title"
    const val EMPTY_MESSAGE = "search.empty.message"
    const val FALLBACK_TITLE = "search.fallback.title"
    const val FALLBACK_NOTE = "search.fallback.note"
    const val LOAD_MORE = "search.load_more"
    const val LOADING = "search.loading"
    const val END = "search.end"
    const val STALE = "search.stale_notice"
    const val UNAVAILABLE = "search.unavailable"

    /** 三家都关的说明（契约字典；{platform_name} 空时回落「该平台」，Q10）。 */
    const val SEARCH_DISABLED = "error.50304.search_disabled"

    /** 半屏右上关闭的读屏名（契约字典 external_page.close「关闭」）。 */
    const val CLOSE = "external_page.close"
    const val RETRY = "external_page.retry"
    const val GENERIC_ERROR = "error.50001"
    const val PLATFORM_NAME = "platform_name"
    const val TRACE6 = "trace6"

    fun sort(raw: String): String = "search.sort.$raw"
}

/**
 * 搜索页（设计稿 Search、SearchTyping、SearchEmpty、SearchFilterPrice、SearchCardCompare、SearchDisclaimerNote、AdaptNarrowSearch）。
 * ViewModel 取导航回退栈项的 SavedStateHandle（进程被系统回收后按保存的页面状态恢复，A-R2-03）。
 */
@Composable
fun SearchScreen(config: SearchPageConfig) {
    val model: SearchViewModel = viewModel { SearchViewModel(config, createSavedStateHandle()) }
    SearchScreen(model, config)
}

/** 给定 ViewModel 的搜索页（测试里用来模拟「销毁旧 ViewModel、用同一份保存状态新建」）。 */
@Composable
internal fun SearchScreen(
    model: SearchViewModel,
    config: SearchPageConfig,
) {
    model.update(config)
    val state by model.state.collectAsStateWithLifecycle()
    val input by model.input.collectAsStateWithLifecycle()
    val history by model.history.collectAsStateWithLifecycle()
    val overlay by model.overlay.collectAsStateWithLifecycle()
    val texts = config.texts
    // 每张卡 i 的焦点目标（口径说明弹层关闭后把焦点还给它）；不是界面状态，不触发重组
    val infoFocus = remember { HashMap<String, FocusRequester>() }
    // 【价格区间】胶囊的焦点目标（价格半屏关闭后把焦点还给它，SearchFilterPrice-v2）
    val priceFocus = remember { FocusRequester() }
    BackHandler { config.actions.onBack() }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(CouliColors.backgroundCanvas)
                .safeDrawingPadding()
                .imePadding()
                .testTag(SearchTags.PAGE),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(modifier = Modifier.widthIn(max = CONTENT_MAX_WIDTH.dp).fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth().background(CouliColors.backgroundSurface)) {
                // Q10（SearchDisabled）：三家都关时头部只留返回；Q09（SearchTyping-v2）：输入态不画平台 Tab / 排序 / 筛选，进结果态即出现
                val backOnly = state.phase == SearchPhase.UNAVAILABLE
                SearchBar(model, input, state, texts, autoFocus = config.query.isNullOrEmpty(), backOnly = backOnly)
                if (state.phase != SearchPhase.IDLE && !backOnly) {
                    if (state.tabs.isNotEmpty()) PlatformTabs(state, texts, model.controller::selectTab)
                    SortRow(state, texts, model.controller::selectSort)
                    FilterRow(state, texts, priceFocus, onCoupon = model::onCouponToggle, onPrice = model::openPriceFilter)
                }
                SearchDivider()
            }
            // 粘贴结果（APP-07，30132）：不依赖搜索阶段，粘贴后用户已开始关键词搜索也照样显示（A-R2-02）
            config.pasteResult?.let { slot ->
                val fill =
                    remember(model) {
                        { query: String ->
                            model.onInput(query)
                            if (query.isNotEmpty()) model.onSubmit()
                        }
                    }
                slot(fill)
            }
            SearchBody(model, state, history, texts, infoFocus)
        }
    }
    val current = overlay
    if (current is SearchOverlay.Disclaimer) {
        // SearchDisclaimerNote-v2（Q13）：半屏 + 右上关闭 + 通栏描边【知道了】；遮罩、返回、关闭、【知道了】同一个关闭动作，关闭后焦点回该卡 i
        val dismiss = model::dismissOverlay
        CouliSheet(
            title = texts.text(ProductCardKeys.DISCLAIMER_TITLE),
            onDismissRequest = dismiss,
            restoreFocusTo = infoFocus[current.cardKey],
            closeLabel = texts.text(SearchKeys.CLOSE),
            actions = {
                CouliButton(
                    text = texts.text(ProductCardKeys.DISCLAIMER_CLOSE),
                    onClick = dismiss,
                    variant = CouliButtonVariant.SECONDARY,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        ) {
            SearchDisclaimerLines(current.lines)
        }
    }
    if (current == SearchOverlay.PriceFilter) {
        PriceFilterSheet(model, texts, priceFocus)
    }
}

@Composable
private fun SearchBar(
    model: SearchViewModel,
    input: String,
    state: SearchSnapshot,
    texts: SearchTexts,
    autoFocus: Boolean,
    backOnly: Boolean,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    // 只在第一次打开（没有路由 q）时自动聚焦；从详情返回不再弹键盘
    LaunchedEffect(Unit) {
        // 等输入框挂上焦点节点（首帧之后）再聚焦
        withFrameNanos { }
        if (autoFocus && !model.autoFocused) runCatching { focus.requestFocus() }
        model.autoFocused = true
    }
    val submit = {
        if (model.onSubmit()) keyboard?.hide()
    }
    val shape = RoundedCornerShape(CouliRadius.button)
    // SearchTyping-v2 / Search-v2 头部：左 4、右 16、上下 4（命中 48 比画板 44 高 4，整行仍 56 高）；返回 → 4 → 搜索框 → 8 →【搜索】
    Row(
        modifier =
            Modifier.fillMaxWidth().padding(
                start = CouliSpacing.space1,
                end = CouliSpacing.space4,
                top = CouliSpacing.space1,
                bottom = CouliSpacing.space1,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CouliIconButton(icon = CouliIcons.back, label = texts.text(SearchKeys.BACK), onClick = model.config.actions.onBack)
        if (!backOnly) {
            Spacer(Modifier.size(CouliSpacing.space1))
            Row(
                modifier =
                    Modifier
                        .weight(1f)
                        .heightIn(min = CouliSizes.touchTarget)
                        .border(CouliSizes.hairline, CouliColors.borderControl, shape)
                        .background(CouliColors.backgroundSurface, shape)
                        .padding(start = CouliSpacing.space3),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
            ) {
                Icon(
                    CouliIcons.search,
                    contentDescription = null,
                    tint = CouliColors.textSecondary,
                    modifier = Modifier.size(CouliSizes.iconMedium),
                )
                Box(modifier = Modifier.weight(1f).padding(vertical = CouliSpacing.space2)) {
                    if (input.isEmpty()) {
                        // 占位一行、末尾省略（窄屏 / 200% 不把头部撑高；读屏名仍是完整占位，见输入框 contentDescription）
                        Text(
                            text = texts.text(SearchKeys.PLACEHOLDER),
                            style = CouliTypography.body,
                            color = CouliColors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    BasicTextField(
                        value = input,
                        onValueChange = model::onInput,
                        singleLine = true,
                        textStyle = CouliTypography.body.copy(color = CouliColors.textPrimary),
                        cursorBrush = SolidColor(CouliColors.textPrimary),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submit() }),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .focusRequester(focus)
                                .testTag(SearchTags.INPUT)
                                .semantics { contentDescription = texts.text(SearchKeys.PLACEHOLDER) },
                    )
                }
                if (input.isNotEmpty()) {
                    CouliIconButton(
                        icon = CouliIcons.close,
                        label = texts.text(SearchKeys.CLEAR),
                        onClick = model::onClear,
                        tint = CouliColors.textSecondary,
                        iconSize = CouliSizes.iconSmall,
                        modifier = Modifier.testTag(SearchTags.CLEAR),
                    )
                }
            }
            Spacer(Modifier.size(CouliSpacing.space2))
            // 【搜索】实心：组件库紧凑档（左右 16、min 48）+ 画板 17/600 字
            CouliButton(
                text = texts.text(SearchKeys.SUBMIT),
                onClick = submit,
                size = CouliButtonSize.COMPACT,
                enabled = !state.blocked,
                textStyle = CouliTextStyles.bodySemibold,
                modifier = Modifier.testTag(SearchTags.SUBMIT),
            )
        }
    }
}

/**
 * 平台 Tab（只有 search_status=on 的平台，BR-PROD-10；Search-v2）：左右 16、项间 24；每项 20 官方平台图标（装饰）+ 6 + 名称 17，选中强调色 600 +
 * 与本项同宽的 2 下划线；读屏为标签与选中态。行下 1px 分隔。
 */
@Composable
private fun PlatformTabs(
    state: SearchSnapshot,
    texts: SearchTexts,
    onSelect: (String) -> Unit,
) {
    // S2-3：选中 Tab 随选中值 / 字号 / 视口宽变化滚入视口（纯视图滚动，不切 Tab、不发请求）
    var viewport by remember { mutableIntStateOf(0) }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .onSizeChanged { viewport = it.width }
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = CouliSpacing.space4)
                .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space6),
    ) {
        state.tabs.forEach { platform ->
            SearchPlatformTab(
                platform = platform,
                name = texts.text(ProductCardKeys.platform(platform)),
                selected = platform == state.platform,
                enabled = !state.blocked,
                onClick = { onSelect(platform) },
                modifier = Modifier.testTag(SearchTags.TAB_PREFIX + platform),
                revealKey = viewport,
            )
        }
    }
    SearchDivider()
}

/** 四个排序（契约 SortCode；名称照 BR-PRICE-18；Search-v2 14/21、左右 16、项间 20）：窄屏横滑，选中项随选中值 / 尺寸变化滚入视口。 */
@Composable
private fun SortRow(
    state: SearchSnapshot,
    texts: SearchTexts,
    onSelect: (String) -> Unit,
) {
    var viewport by remember { mutableIntStateOf(0) }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .onSizeChanged { viewport = it.width }
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = CouliSpacing.space4)
                .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(SORT_GAP.dp),
    ) {
        Sort.known.forEach { sort ->
            SearchSortItem(
                label = texts.text(SearchKeys.sort(sort.raw)),
                selected = sort.raw == state.sort,
                enabled = !state.blocked,
                onClick = { onSelect(sort.raw) },
                modifier = Modifier.testTag(SearchTags.SORT_PREFIX + sort.raw),
                revealKey = viewport,
            )
        }
    }
}

/**
 * 筛选行（Search-v2：左右 16、下 8、间距 8；画板 / brief §4.5「筛选行横滑」）：【只看有券】与【价格区间】两个胶囊不换行，超宽（长价格区间、200%）时横滑；
 * 价格胶囊挂 [priceFocus]（价格半屏关闭后回到这里）。
 */
@Composable
private fun FilterRow(
    state: SearchSnapshot,
    texts: SearchTexts,
    priceFocus: FocusRequester,
    onCoupon: () -> Unit,
    onPrice: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = CouliSpacing.space4, end = CouliSpacing.space4, bottom = CouliSpacing.space1),
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchFilterCapsule(
            label = texts.text(SearchKeys.COUPON),
            selected = state.hasCoupon,
            enabled = !state.blocked,
            onClick = onCoupon,
            modifier = Modifier.testTag(SearchTags.COUPON),
        )
        SearchFilterCapsule(
            label = priceChipLabel(state, texts),
            selected = state.priceMinFen != null || state.priceMaxFen != null,
            enabled = !state.blocked,
            onClick = onPrice,
            modifier = Modifier.couliFocusTarget(priceFocus).testTag(SearchTags.PRICE),
            trailing = true,
        )
    }
}

private fun priceChipLabel(
    state: SearchSnapshot,
    texts: SearchTexts,
): String {
    val min = state.priceMinFen?.let(CouliFormat::money)
    val max = state.priceMaxFen?.let(CouliFormat::money)
    return when {
        min != null && max != null -> texts.format(SearchKeys.PRICE_RANGE, mapOf("min" to min, "max" to max))
        min != null -> texts.format(SearchKeys.PRICE_MIN_ONLY, mapOf("min" to min))
        max != null -> texts.format(SearchKeys.PRICE_MAX_ONLY, mapOf("max" to max))
        else -> texts.text(SearchKeys.PRICE)
    }
}

/** 筛选胶囊、历史胶囊、Tab、排序项、提示条外观在 SearchVisualParts.kt（只收显示值与 lambda）。下面是按阶段的页面主体。 */
@Composable
private fun SearchBody(
    model: SearchViewModel,
    state: SearchSnapshot,
    history: List<String>,
    texts: SearchTexts,
    infoFocus: MutableMap<String, FocusRequester>,
) {
    when (state.phase) {
        SearchPhase.IDLE -> {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 配置恢复了本轮已关闭的平台、等用户触发下一次搜索时（A-R1-01），search_disabled 的提示仍在
                if (state.notice != null) Column(Modifier.padding(CouliSpacing.space4)) { Notices(state, texts) }
                // SearchTyping「粘贴查返利」（APP-07）
                PasteSlot(model, top = state.notice == null)
                HistoryPanel(history, texts, onWord = model::onHistory, onClear = model::onClearHistory)
            }
        }

        SearchPhase.LOADING -> {
            Column(
                modifier = Modifier.fillMaxWidth().padding(CouliSpacing.space4),
                verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
            ) {
                Notices(state, texts)
                DisabledPasteSlot(model, state)
                repeat(SKELETONS) { SkeletonProductCard(description = texts.text(SearchKeys.LOADING)) }
            }
        }

        SearchPhase.ERROR -> {
            Column(modifier = Modifier.fillMaxWidth().padding(CouliSpacing.space4)) {
                Notices(state, texts)
                DisabledPasteSlot(model, state)
                // SearchError（Q12）：组件库错误态（96 占位 → 17/600 一句 → 实心【重试】min 160×48）；trace6、立即播报一次后焦点到【重试】、
                // 42901 期间置灰均原样（ErrorState 内同一个 ErrorAnnouncement）
                ErrorState(
                    title = errorText(state.error, state.platform, texts),
                    retry =
                        StateAction(
                            label = texts.text(SearchKeys.RETRY),
                            enabled = !state.blocked,
                            primary = true,
                            onClick = { model.controller.retry() },
                        ),
                    modifier = Modifier.padding(top = PAGE_STATE_TOP.dp).testTag(SearchTags.ERROR),
                    actionModifier = Modifier.widthIn(min = RETRY_MIN_WIDTH.dp).testTag(SearchTags.RETRY),
                )
            }
        }

        SearchPhase.UNAVAILABLE -> {
            // SearchDisabled（Q10）：头部只有返回；居中 96 占位 + error.50304.search_disabled（三家都关，平台名回落「该平台」）+ 粘贴入口；无【重试】
            Column(modifier = Modifier.fillMaxWidth().padding(CouliSpacing.space4)) {
                Notices(state, texts)
                EmptyState(
                    title = texts.text(SearchKeys.SEARCH_DISABLED),
                    icon = CouliIcons.info,
                    modifier = Modifier.padding(top = PAGE_STATE_TOP.dp).testTag(SearchTags.UNAVAILABLE),
                )
            }
            // 没有可搜索的平台：「可以粘贴商品链接查返利」（APP-07），在说明下居中（SearchDisabled）
            PasteSlot(model, top = false, centered = true)
        }

        else -> {
            ResultList(model, state, texts, infoFocus)
        }
    }
}

/**
 * App 给的「粘贴查返利」入口（APP-07）；[top] 时上边距 16（输入态的第一块），否则与上一块之间只留下边距；[inset] 为 false 时不加左右边距
 * （所在容器已有）；[centered] 时入口水平居中（三家都关，SearchDisabled）。
 */
@Composable
private fun PasteSlot(
    model: SearchViewModel,
    top: Boolean,
    inset: Boolean = true,
    centered: Boolean = false,
) {
    val entry = model.config.pasteEntry ?: return
    val side = if (inset) CouliSpacing.space4 else CouliSpacing.space0
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = side, end = side, top = if (top) CouliSpacing.space4 else CouliSpacing.space0)
                .testTag(SearchTags.PASTE_ENTRY),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        entry()
    }
}

/**
 * search_disabled 回落到其他平台后（加载、结果、空结果、出错各阶段）独立显示的粘贴入口（A-R2-03，error-codes 50304.search_disabled）；
 * 输入态与无可搜索平台已各自显示，不在这里重复。限流置灰与执行层拦截沿用剪贴板的共享禁用期。
 */
@Composable
private fun DisabledPasteSlot(
    model: SearchViewModel,
    state: SearchSnapshot,
) {
    if (state.notice == null) return
    Column(modifier = Modifier.padding(bottom = CouliSpacing.space2)) { PasteSlot(model, top = false, inset = false) }
}

/** 搜索历史（SearchTyping-v2）：上 16 左右 16；「搜索历史」14/600 + 右侧【清空】14 链接色；历史词 32 高胶囊（按钮角色，点一下 = 提交这个词）。 */
@Composable
private fun HistoryPanel(
    history: List<String>,
    texts: SearchTexts,
    onWord: (String) -> Unit,
    onClear: () -> Unit,
) {
    if (history.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth().padding(CouliSpacing.space4),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = texts.text(SearchKeys.HISTORY_TITLE),
                style = CouliTextStyles.footnoteSemibold,
                color = CouliColors.textPrimary,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            CouliButton(
                text = texts.text(SearchKeys.HISTORY_CLEAR),
                onClick = onClear,
                variant = CouliButtonVariant.TEXT,
                size = CouliButtonSize.COMPACT,
                textStyle = CouliTypography.footnote,
                modifier = Modifier.testTag(SearchTags.HISTORY_CLEAR),
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
            history.forEach { word ->
                SearchHistoryCapsule(
                    word = word,
                    onClick = { onWord(word) },
                    modifier = Modifier.testTag(SearchTags.HISTORY_PREFIX + word),
                )
            }
        }
    }
}

/** 搜索关闭（search_disabled）与缓存降级的提示条（结果顶部）。 */
@Composable
private fun Notices(
    state: SearchSnapshot,
    texts: SearchTexts,
) {
    state.notice?.let { notice ->
        NoticeBar(text = errorText(notice, notice.platform, texts), tag = SearchTags.NOTICE)
    }
    if (state.stale && (state.phase == SearchPhase.CONTENT || state.phase == SearchPhase.EMPTY)) {
        NoticeBar(text = texts.text(SearchKeys.STALE), tag = SearchTags.STALE)
    }
}

/** 提示条（GUIDE §3：圆角 12、内边距 12、14 字、20 图标）：结果顶部，polite 播报；条下 8 与下一块隔开，没有提示时不占高度。 */
@Composable
private fun NoticeBar(
    text: String,
    tag: String,
) {
    SearchNoticeFace(
        text = text,
        modifier =
            Modifier
                .padding(bottom = CouliSpacing.space2)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag(tag),
    )
}

/**
 * 错误块（03 §4.2 通用错误态）：一句错误文案（带 trace6）+【重试】。42901 限时禁用期间【重试】置灰（共性第 4 条），到期恢复。
 * 出现即播报、读完后焦点移到【重试】（adaptation-a11y-spec §10「加载失败：立即读，焦点到【重试】，assertive」，03 §10.5，A-R1-09；
 * 与 DesignSystem ErrorState 同一个 [ErrorAnnouncement]）；禁用中的【重试】仍可获得读屏焦点，读出「已停用」。
 */
@Composable
internal fun ErrorBlock(
    message: String,
    retryLabel: String?,
    enabled: Boolean,
    onRetry: () -> Unit,
    tag: String,
    retryTag: String = SearchTags.RETRY,
) {
    val retryFocus = remember { FocusRequester() }
    ErrorAnnouncement(message, retryFocus.takeIf { retryLabel != null })
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = CouliSpacing.space6).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        Icon(CouliIcons.alert, contentDescription = null, tint = CouliColors.textSecondary, modifier = Modifier.size(CouliSpacing.space8))
        Text(text = message, style = CouliTypography.body, color = CouliColors.textPrimary)
        if (retryLabel != null) {
            CouliButton(
                text = retryLabel,
                onClick = onRetry,
                variant = CouliButtonVariant.SECONDARY,
                size = CouliButtonSize.COMPACT,
                enabled = enabled,
                modifier = Modifier.couliFocusTarget(retryFocus).testTag(retryTag),
            )
        }
    }
}

/**
 * 错误文案（03 §4.2、BR-TEXT-14）：error.<code>[.<reason>]；{platform_name} 用平台显示名，{trace6} 用服务端 trace_id 后 6 位（共性第 6 条）。
 * 没有键、或键的文字为空时用通用失败键。追踪编号统一由 [ResultSignals.withTrace6] 附上（共性第 12 条，A-R1-03）：键里没有 {trace6}
 * 占位符的（50304、search_disabled、42901…）也带，已经带上的不重复；只有确实没有 trace_id 时不带编号。
 */
internal fun errorText(
    error: SearchResultView?,
    currentPlatform: String?,
    texts: SearchTexts,
): String {
    if (error == null) return ""
    val platform = error.platform ?: currentPlatform
    val vars =
        mapOf(
            SearchKeys.PLATFORM_NAME to platform?.let { texts.text(ProductCardKeys.platform(it)) }.orEmpty(),
            SearchKeys.TRACE6 to ResultSignals.trace6(error.traceId),
        )
    val text = texts.format(error.textKey ?: SearchKeys.GENERIC_ERROR, vars).ifEmpty { texts.format(SearchKeys.GENERIC_ERROR, vars) }
    return ResultSignals.withTrace6(text, error.traceId)
}

@Composable
private fun ResultList(
    model: SearchViewModel,
    state: SearchSnapshot,
    texts: SearchTexts,
    infoFocus: MutableMap<String, FocusRequester>,
) {
    // F-X-20 / F-X-18（FU-AND-0717，模拟器实走发现）：进程重建后有待执行的一次性恢复时，列表自己不再按系统保存的旧位置恢复（rememberSaveable 的位置
    // 在第一页只够几张卡时会被截到末尾，恢复任务把这次截断当成别人的移动而让位，锚点在第一页末尾时停在上一张卡）：从顶部开始，由恢复任务按锚点定位。
    // 没有待恢复（配置重建、返回本页）照旧沿用保存的位置
    val restoring = remember { model.pendingScroll != null }
    val listState = if (restoring) remember { LazyListState() } else rememberLazyListState()
    // 只有开始了新一轮查询才回到顶部；返回本页、配置重建、系统回收后恢复（轮次没变）保留滚动位置（03 §10.4，A-R1-06、A-R2-03）
    LaunchedEffect(state.round) {
        if (model.scrolledRound != state.round) {
            model.scrolledRound = state.round
            model.scrollRestored()
            listState.scrollToItem(0)
        }
    }
    LaunchedEffect(listState) {
        // 临时没有可见项（重排、被盖住前后的空帧）时不记：保留最后有效的锚点，只在新一轮查询或真正出栈时重置（APP-06a 共性第 29 条）
        snapshotFlow { viewportAnchor(listState) }.filterNotNull().collect(model::onScrolled)
    }
    // 被覆盖 / 进入后台（ON_STOP）：同步写入此刻视口的锚点后冻结记录；回到前台解冻（共性第 32、33 条）
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, model, listState) {
        // 页面已出栈（退出动画期间组合与生命周期还在）：持久化入口自己拒绝写入（SearchViewModel.persist，A-R2-04）
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> model.onPageStopped(viewportAnchor(listState))
                    Lifecycle.Event.ON_START -> model.onPageStarted()
                    else -> Unit
                }
            }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    AutoLoadMore(listState, state) { model.controller.loadMore() }
    val now = model.config.clock()
    // 配置变化（platform_status）时重新映射卡片（APP-06a 共性第 27、31 条：订阅配置变化）
    var configTick by remember { mutableIntStateOf(0) }
    DisposableEffect(model) {
        val unsubscribe = model.config.config.subscribe { configTick += 1 }
        onDispose(unsubscribe)
    }
    val port = model.config.config
    val mapped = remember(state.items, configTick, now) { state.items.map { mapCard(it, texts, now, port) } }
    val fallback = remember(state.fallbackItems, configTick, now) { state.fallbackItems.map { mapCard(it, texts, now, port) } }
    val shownRaw = if (state.phase == SearchPhase.EMPTY) state.fallbackItems else state.items
    val shownCards = if (state.phase == SearchPhase.EMPTY) fallback else mapped
    RestoreScroll(model, listState)
    Column(modifier = Modifier.fillMaxSize()) {
        ResultItems(model, state, texts, infoFocus, listState, shownRaw.zip(shownCards), fallback.isNotEmpty())
        ScreenDisclaimer(listState, shownRaw.map(::cardKey).zip(shownCards))
    }
}

/** [ResultItems] 里的项数：提示 +（空态 + 推荐标题）+ 卡片 + 底部。 */
private fun listItemCount(
    state: SearchSnapshot,
    cards: Int,
    hasFallback: Boolean,
): Int {
    val emptyItems = if (state.phase == SearchPhase.EMPTY) 1 + (if (hasFallback) 1 else 0) else 0
    return 1 + emptyItems + cards + 1
}

/** 此刻视口的锚点：顶部可见项与偏移、最后一个可见项；临时没有可见项时为 null。 */
private fun viewportAnchor(listState: LazyListState): ScrollAnchor? {
    val last =
        listState.layoutInfo.visibleItemsInfo
            .lastOrNull()
            ?.index ?: return null
    return ScrollAnchor(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, last)
}

/**
 * 列表此刻的位置（恢复任务比对用）：顶部可见项与偏移、已布局的项数、是否有滚动在进行；[leading] 是卡片之前的非卡片项数（提示行，空态
 * 时加空态与推荐标题），这些项可能是零高度，列表在顶部时顶部可见项可以是其中任一项之后的第一项。
 * [key] 是顶部可见项的稳定 key（LazyColumn item key；前插 / 前删时同一项按 key 留在原处，位移的证据）；[atEnd] 是列表已到真实底部
 * （不能再向后滚，底部截断的证据）。[zeroLeading] 是此刻能证明为零高度的前置项数（从第 0 项起连续；提示行没有内容时为 1，空态与推荐标题
 * 总有高度），「仍在顶部」只在顶部可见项不越过它们时成立（F2-03）。
 */
internal data class ListPosition(
    val index: Int,
    val offset: Int,
    val count: Int,
    val scrolling: Boolean,
    val leading: Int = 1,
    val key: Any? = null,
    val atEnd: Boolean = false,
    val zeroLeading: Int = 0,
)

/**
 * 恢复任务预期的列表位置（A-R2-03，APP-06a 共性第 38 条）：任务每次自己移动列表后记下实际落点；还没移动过 = 任务开始时的位置。
 * 列表的项数变了（首屏到达、补页、提示行增减）时，只放行能证明是布局造成的位置变化（F-AND-18、FU-AND-0607 F1-06），按当前位置重取基准：
 * - 位置不变（在后面追加 / 删除）；
 * - 前插 / 前删：顶部可见项的稳定 key 不变、偏移不变，只是序号位移（项数差本身不是证据：尾部追加量恰等于一次瞬时滚动的距离时，
 *   序号差与项数差相同，但顶部已是另一项）；
 * - 底部截断：项数变少、列表此刻已在真实底部（不能再向后滚），且位置只向前收；没到底部的向前移动不算截断；
 * - 原来在顶部、现在仍在顶部（偏移为 0，顶部可见项不超过前置非卡片项数）：还要证明顶部可见项之前的前置项此刻都是零高度（[ListPosition.zeroLeading]），
 *   或顶部可见项的 key 没变；只比较序号与前置项数不够（有高度的提示条之后的第一张卡不是顶部，F2-03）。
 * 其余（如项数变化与瞬时语义滚动 ScrollToIndex 合并在同一帧，观测时滚动已结束）算被别人移动了，恢复让位，不把用户的新位置当基准抢回。
 */
internal class RestoreExpectation(
    private var expected: ListPosition,
) {
    /** 任务自己正在移动列表（这期间的滚动与位置变化是任务的）。 */
    var moving: Boolean = false
        private set

    /**
     * [now] 是否说明列表被别人移动了：有不属于任务的滚动在进行（触摸拖动、惯性、读屏的滚动动作、键盘 / 焦点移动带来的滚动都经过列表的
     * scroll，都算），或项数没变而位置偏离预期（超出 [TOLERANCE_PX]）。
     */
    fun drifted(now: ListPosition): Boolean =
        when {
            moving -> {
                false
            }

            now.scrolling -> {
                true
            }

            now.count != expected.count -> {
                val explained = layoutShift(expected, now)
                if (explained) expected = now
                !explained
            }

            else -> {
                now.index != expected.index || kotlin.math.abs(now.offset - expected.offset) > TOLERANCE_PX
            }
        }

    private fun layoutShift(
        before: ListPosition,
        now: ListPosition,
    ): Boolean {
        val sameOffset = kotlin.math.abs(now.offset - before.offset) <= TOLERANCE_PX
        val unchanged = now.index == before.index && sameOffset
        val sameItemShifted = before.key != null && now.key == before.key && sameOffset
        val clamped =
            now.count < before.count &&
                now.atEnd &&
                (now.index < before.index || (now.index == before.index && now.offset <= before.offset))
        return unchanged || sameItemShifted || clamped || stillAtTop(before, now)
    }

    /** 原来在顶部、现在仍在顶部：序号、偏移之外还要有证明（F2-03）。 */
    private fun stillAtTop(
        before: ListPosition,
        now: ListPosition,
    ): Boolean {
        val bothAtTopIndex = before.index <= before.leading && before.offset == 0 && now.index <= now.leading && now.offset == 0
        // 前置项零高度的证据（两次观测各自证明顶部可见项之前都是零高度），或顶部可见项还是同一个 key
        val provedTop =
            (before.index <= before.zeroLeading && now.index <= now.zeroLeading) || (before.key != null && now.key == before.key)
        return bothAtTopIndex && provedTop
    }

    /** 任务自己移动列表：[move] 期间不判偏离，之后按实际落点记预期。 */
    suspend fun move(
        move: suspend () -> Unit,
        landed: () -> ListPosition,
    ) {
        moving = true
        try {
            move()
        } finally {
            moving = false
            expected = landed()
        }
    }

    companion object {
        /** 位置比较的容差（像素）：取整误差。 */
        const val TOLERANCE_PX = 1
    }
}

private fun listPosition(
    listState: LazyListState,
    state: SearchSnapshot,
): ListPosition {
    // 提示行此刻没有内容（与 Notices、DisabledPasteSlot 一致）即零高度；空态与推荐标题总有内容
    val staleShown = state.stale && (state.phase == SearchPhase.CONTENT || state.phase == SearchPhase.EMPTY)
    val noticesEmpty = state.notice == null && !staleShown
    return ListPosition(
        listState.firstVisibleItemIndex,
        listState.firstVisibleItemScrollOffset,
        listState.layoutInfo.totalItemsCount,
        listState.isScrollInProgress,
        leadingItems(state),
        key =
            listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == listState.firstVisibleItemIndex }
                ?.key,
        atEnd = !listState.canScrollForward,
        zeroLeading = if (noticesEmpty) 1 else 0,
    )
}

/** 卡片之前的非卡片项数（与 [ResultItems] 一致）：提示行 +（空态 + 推荐标题）。 */
private fun leadingItems(state: SearchSnapshot): Int =
    1 + if (state.phase == SearchPhase.EMPTY) 1 + (if (state.fallbackItems.isNotEmpty()) 1 else 0) else 0

/**
 * 系统回收后恢复（A-R2-03）：结果重新加载到保存时最后一个可见项之后，再滚到锚点；锚点还没加载到就继续翻页（翻到第二页的位置也能回去），到底、翻页
 * 失败或被限流时停在已有的最后一项。按执行器的实时状态判断（不用组合时的快照，免得和自动翻页抢着发请求时误判为「翻不动」）。
 *
 * 恢复任务绑定查询轮次与页面实例（A-R1-17、A-R2-03 / 04，APP-06a 共性第 33、38、39 条）：
 * - 让位用通用判定（[RestoreExpectation]）：持续比对列表实际位置与任务预期，任何不是任务造成的移动（触摸、惯性、读屏滚动、键盘 / 焦点移动）
 *   都取消恢复，并按用户此刻的位置记录；每一步（翻页、滚动）之前再核对一次；
 * - 锚点被放弃（让位、新一轮查询）或页面实例已出栈（[SearchViewModel.alive]）即结束，滚动、翻页前都复核；
 * - 页面不在前台（被覆盖、在后台）时暂停等待，回到前台再继续；等待目标内容按加载状态（项数、翻页中、到底、失败），不用固定超时。
 * 每个等待分支的结束条件见 NATIVE/APP-06b/compare-android.md「滚动恢复的等待与结束」。
 */
@Suppress("CyclomaticComplexMethod", "LongMethod") // 放弃、暂停、加载中、到位、继续翻页逐项判定
@Composable
private fun RestoreScroll(
    model: SearchViewModel,
    listState: LazyListState,
) {
    val anchor = model.pendingScrollFlow.collectAsState().value ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(anchor, lifecycle) {
        val expectation = RestoreExpectation(listPosition(listState, model.state.value))

        fun stillMine(): Boolean = model.alive && model.pendingScroll === anchor

        fun takeOverIfDrifted(): Boolean {
            if (!expectation.drifted(listPosition(listState, model.state.value))) return false
            model.restoreTakenOver(viewportAnchor(listState))
            return true
        }
        // 持续比对：读屏的滚动动作、键盘 / 焦点移动、惯性滚动都不经过拖动事件，只看实际位置与是否有别人的滚动
        launch {
            snapshotFlow { listPosition(listState, model.state.value) }.collect {
                if (stillMine()) takeOverIfDrifted()
            }
        }
        // 列表已布局的项数要追上执行器的状态再判断（状态先变、列表下一帧才有新项；在旧项数上滚动会被截到旧的末尾）
        combine(
            snapshotFlow { listState.layoutInfo.totalItemsCount },
            model.state,
            lifecycle.currentStateFlow,
        ) { laidOut, s, life -> Triple(laidOut, s, life) }
            .first { (laidOut, s, life) ->
                val cards = if (s.phase == SearchPhase.EMPTY) s.fallbackItems.size else s.items.size
                val count = listItemCount(s, cards, s.fallbackItems.isNotEmpty())
                val shown = s.phase == SearchPhase.CONTENT || s.phase == SearchPhase.EMPTY
                when {
                    // 页面已出栈、锚点已放弃（让位、新查询）或已换了一轮查询：结束，不再滚
                    !stillMine() || s.round != model.pendingScrollRound -> {
                        if (model.pendingScroll === anchor) model.scrollRestored()
                        true
                    }

                    // 被覆盖、在后台：暂停（不滚、不翻页），回到前台再判断
                    !life.isAtLeast(Lifecycle.State.STARTED) -> {
                        false
                    }

                    // 等待期间列表被别人移动过：让位
                    takeOverIfDrifted() -> {
                        true
                    }

                    s.phase == SearchPhase.LOADING || s.phase == SearchPhase.IDLE -> {
                        false
                    }

                    !shown -> {
                        model.scrollRestored()
                        true
                    }

                    laidOut != count -> {
                        false
                    }

                    anchor.lastVisible < count - 1 || s.endReached || s.loadMoreError != null || s.blocked -> {
                        val reached = anchor.index < count - 1
                        val index = minOf(anchor.index, count - 1)
                        val offset = if (reached) anchor.offset else 0
                        // F-X-18（FU-AND-0717）：定位之后按真实布局确认落点再结束（布局滞后时等它追上再补发；帧上限内没确认按没确认结束）。确认期间
                        // 接管判定照常（上面的持续比对）：别人的移动立即放弃，之后不再下发定位
                        val step =
                            awaitLanding(RestoreLanding(index, offset), { RestoreLanding.layoutOf(listState) }, ::stillMine) {
                                expectation.move(
                                    move = {
                                        // 滚动前复核页面实例与锚点（等布局期间出栈、被放弃的不滚）
                                        if (stillMine()) listState.scrollToItem(index, offset)
                                    },
                                    landed = { listPosition(listState, model.state.value) },
                                )
                            }
                        val end = if (step == RestoreLanding.Step.CONFIRMED) RESTORE_LANDED else RESTORE_NOT_CONFIRMED
                        if (step != null) model.restoreEnd = end
                        if (model.pendingScroll === anchor) model.scrollRestored()
                        true
                    }

                    else -> {
                        if (!s.loadingMore && stillMine()) model.controller.loadMore()
                        false
                    }
                }
            }
    }
}

@Suppress("LongParameterList") // 列表需要页面状态、文案、焦点表、滚动状态与卡片
@Composable
private fun ColumnScope.ResultItems(
    model: SearchViewModel,
    state: SearchSnapshot,
    texts: SearchTexts,
    infoFocus: MutableMap<String, FocusRequester>,
    listState: LazyListState,
    cards: List<Pair<JsonObject, MappedProductCard>>,
    hasFallback: Boolean,
) {
    LazyColumn(
        state = listState,
        modifier =
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag(SearchTags.RESULTS)
                // 第 1 轮修复 R1-06：恢复期间手指按下即算用户接管（未移动也取消），按此刻视口记位置；不消费事件
                .pointerInput(model) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        if (model.pendingScroll != null) model.restoreTakenOver(viewportAnchor(listState))
                    }
                },
        contentPadding = PaddingValues(CouliSpacing.space4),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        item(key = "notices") {
            Column {
                Notices(state, texts)
                DisabledPasteSlot(model, state)
            }
        }
        if (state.phase == SearchPhase.EMPTY) {
            item(key = "empty") {
                EmptyState(
                    title = texts.text(SearchKeys.EMPTY_TITLE),
                    message = texts.text(SearchKeys.EMPTY_MESSAGE),
                    modifier = Modifier.testTag(SearchTags.EMPTY),
                )
            }
            if (hasFallback) {
                item(key = "fallback_title") { FallbackHeader(state, texts) }
            }
        }
        itemsIndexed(cards, key = { _, (raw, _) -> cardKey(raw) }) { slot, (raw, card) ->
            ResultCard(model, raw, card, infoFocus, slot)
        }
        item(key = "footer") { ListFooter(model, state, texts) }
    }
}

/**
 * 结果区底部常驻的口径说明（BR-PRICE-03 细则「列表每屏底部一次 + 单卡 i 图标」，A-R1-05）：不放在无限列表的最后一项里，固定在列表
 * 下方，每一屏都能看到；句子按这一屏可见的卡选（[pageDisclaimerLines]），还没有可见卡时按全部卡。内容太长（200% 字号）时自身可滚动。
 *
 * 占用高度不随可见卡片集合变化（A-R2-04，共性第 21 条）：按当前已加载结果可能用到的全部句子（[reservedDisclaimerLines]）预留高度
 * （不可见、不进读屏），可见的句子叠在上面。否则混排边界上「露出一张普通返利卡 → 说明区变高 → 列表变矮把它挤出去 → 说明区变矮 → 它
 * 又露出来」会反复重排。预留只在结果变化（翻页、换条件）时变。
 */
@Composable
private fun ScreenDisclaimer(
    listState: LazyListState,
    cards: List<Pair<String, MappedProductCard>>,
) {
    val byKey = remember(cards) { cards.toMap() }
    val visible by remember(listState, byKey) {
        derivedStateOf {
            listState.layoutInfo.visibleItemsInfo
                .mapNotNull { byKey[it.key] }
                .ifEmpty { byKey.values.toList() }
        }
    }
    val reserved = remember(byKey) { reservedDisclaimerLines(byKey.values.map { it.data.disclaimerKeys }) }
    val lines = pageDisclaimerLines(visible.map { it.data.disclaimerKeys })
    if (reserved.isEmpty()) return
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(CouliColors.backgroundCanvas)
                .heightIn(max = DISCLAIMER_MAX_HEIGHT.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space2)
                .testTag(SearchTags.DISCLAIMER),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // 预留高度：不画、不进读屏
            DisclaimerFooter(
                lines = reserved.mapIndexed { i, text -> DisclaimerLine(text, icon = if (i == 0) CouliIcons.info else null) },
                modifier = Modifier.fillMaxWidth().alpha(0f).clearAndSetSemantics {},
            )
            DisclaimerFooter(
                lines = lines.mapIndexed { i, text -> DisclaimerLine(text, icon = if (i == 0) CouliIcons.info else null) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun FallbackHeader(
    state: SearchSnapshot,
    texts: SearchTexts,
) {
    val name = state.platform?.let { texts.text(ProductCardKeys.platform(it)) }.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
        Text(
            text = texts.format(SearchKeys.FALLBACK_TITLE, mapOf(SearchKeys.PLATFORM_NAME to name)),
            style = CouliTextStyles.footnoteSemibold,
            color = CouliColors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = texts.format(SearchKeys.FALLBACK_NOTE, mapOf(SearchKeys.PLATFORM_NAME to name)),
            style = CouliTypography.caption,
            color = CouliColors.textSecondary,
        )
    }
}

@Composable
private fun ResultCard(
    model: SearchViewModel,
    raw: JsonObject,
    card: MappedProductCard,
    infoFocus: MutableMap<String, FocusRequester>,
    slot: Int,
) {
    val key = cardKey(raw)
    val focus = remember(key) { FocusRequester() }
    SideEffect { infoFocus[key] = focus }
    val productKey = raw.string("product_key")
    val platform = raw.string("platform")
    // 金额未知卡（product_key 为空，BR-PRICE-08）进不了详情路由（routes.json 要求非空 product_key，rule-gaps）
    val open: (() -> Unit)? =
        if (productKey != null && platform != null) {
            { model.config.actions.onOpenProduct(platform, productKey, raw.string("item_ref")) }
        } else {
            null
        }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(if (open != null) Modifier.clickable(role = Role.Button, onClick = open) else Modifier)
                .testTag(SearchTags.CARD_PREFIX + key),
    ) {
        // 购买流程的覆盖（加载、限流、点击结果禁用、未安装提示）只对正在购买的这张卡、且卡片本来可买时生效
        val override =
            model.config.purchase
                .button(raw)
                ?.takeIf { card.buyState == BuyButtonState.NORMAL }
        val buyState = override?.state ?: card.buyState
        val data = override?.text?.takeIf { buyState == BuyButtonState.NORMAL }?.let { card.data.copy(buyBase = it) } ?: card.data
        ProductCardList(
            data = data,
            onBuy = { model.config.actions.onBuyIntent(raw, "$SPM_PREFIX${slot + 1}") },
            buyState = buyState,
            retryLaterText = override?.text?.takeIf { buyState == BuyButtonState.RETRY_LATER } ?: card.retryLaterText,
            context = BuyButtonModel.CONTEXT_LIST,
            onInfo = { model.showDisclaimer(key, card.data.disclaimer) },
            infoFocus = focus,
            // 下架（open 返回 availability=off_shelf 或 30141，执行器写进这张卡）：按钮由映射置灰，卡顶「商品已下架」胶囊 polite 播报，
            // 不出轻提示（G-D07；与详情原位置灰一致）
            statusLabel =
                model.config.texts
                    .text(OFF_SHELF_KEY)
                    .takeIf { raw.string("availability") == OFF_SHELF },
        )
        // 比价无返利（open 复核后卡片的 no_rebate_cause=price_compare，或接口直接给出）：原因行 +【看看相似商品】，绑定这张卡（BR-PRICE-08，A-R1-16）
        if (raw.string("no_rebate_cause") == PRICE_COMPARE) {
            CompareReasonRow(texts = model.config.texts, tag = SearchTags.COMPARE_PREFIX + key) {
                model.config.actions.onSimilar(raw)
            }
        }
    }
}

/** 比价无返利的原因行（no_rebate.price_compare +【看看相似商品】，ProductPriceCompareCancelled）。 */
@Composable
private fun CompareReasonRow(
    texts: SearchTexts,
    tag: String,
    onSimilar: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = CouliSpacing.space2).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        Text(
            text = texts.text(COMPARE_REASON_KEY),
            style = CouliTypography.footnote,
            color = CouliColors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        CouliButton(
            text = texts.text(COMPARE_SIMILAR_KEY),
            onClick = onSimilar,
            variant = CouliButtonVariant.TEXT,
            size = CouliButtonSize.COMPACT,
            modifier = Modifier.testTag(tag + SIMILAR_SUFFIX),
        )
    }
}

/** 列表底部：加载更多 / 翻页失败（错误文案 + trace6，再【重试】，H-R2-03）/ 到底；最后是页底口径说明（BR-PRICE-03）。 */
@Composable
private fun ListFooter(
    model: SearchViewModel,
    state: SearchSnapshot,
    texts: SearchTexts,
) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        when {
            state.loadMoreError != null -> {
                ErrorBlock(
                    message = errorText(state.loadMoreError, state.platform, texts),
                    retryLabel = texts.text(SearchKeys.RETRY),
                    enabled = !state.blocked,
                    onRetry = { model.controller.retry() },
                    tag = SearchTags.LOAD_MORE_ERROR,
                )
            }

            state.loadingMore -> {
                // SearchLoading（Q14）：翻页中在列表底部多一张卡片骨架，画在原 footer 项里（不新增列表项）；读屏仍为「加载中」
                SkeletonProductCard(description = texts.text(SearchKeys.LOADING), modifier = Modifier.testTag(SearchTags.LOAD_MORE))
            }

            !state.endReached -> {
                CouliButton(
                    text = texts.text(SearchKeys.LOAD_MORE),
                    onClick = { model.controller.loadMore() },
                    variant = CouliButtonVariant.TEXT,
                    size = CouliButtonSize.COMPACT,
                    enabled = !state.blocked,
                    modifier = Modifier.testTag(SearchTags.LOAD_MORE),
                )
            }

            state.phase == SearchPhase.CONTENT -> {
                Text(
                    text = texts.text(SearchKeys.END),
                    style = CouliTypography.caption,
                    color = CouliColors.textSecondary,
                    modifier = Modifier.padding(CouliSpacing.space3).testTag(SearchTags.END),
                )
            }
        }
    }
}

/**
 * 列表底部口径说明的句子（BR-PRICE-03 细则「无券商品与混排列表的口径说明」、BR-PRICE-09、BR-PRICE-17，A-R1-04）：按各卡下发的
 * disclaimer_keys 选，不自己判断。价格句混排时合并为一句（[DisclaimerKeys.listFooterPriceKey]）；返利句不合并、不丢：有卡带
 * rebate_estimate 就放「预估返利以平台结算为准…」，有卡带 rebate_compare 就放「以结算为准」。顺序同 BR-PRICE-17（价格句在前）。
 */
internal fun pageDisclaimerLines(cardKeys: List<List<String>>): List<String> {
    if (cardKeys.isEmpty()) return emptyList()
    val rebateKeys = listOf(DisclaimerKeys.REBATE_ESTIMATE, DisclaimerKeys.REBATE_COMPARE).filter { key -> cardKeys.any { key in it } }
    return (listOfNotNull(DisclaimerKeys.listFooterPriceKey(cardKeys)) + rebateKeys).mapNotNull(DisclaimerKeys::text)
}

/**
 * 说明区预留高度用的句子（A-R2-04）：已加载结果里任意一组可见卡可能用到的句子的并集——全部卡一起时的句子，加上每张卡单独可见时的句子
 * （混排时合并的价格句与单一价格句都算）。可见句子总是它的子集，所以叠在上面不会超出预留高度。
 */
internal fun reservedDisclaimerLines(cardKeys: List<List<String>>): List<String> =
    (pageDisclaimerLines(cardKeys) + cardKeys.flatMap { pageDisclaimerLines(listOf(it)) }).distinct()

/** 滚到接近底部时自动加载下一页（还有更多、没在加载、没有翻页错误时）。 */
@Composable
private fun AutoLoadMore(
    listState: LazyListState,
    state: SearchSnapshot,
    load: () -> Unit,
) {
    val canLoad = !state.endReached && !state.loadingMore && state.loadMoreError == null && !state.blocked
    var lastTotal by remember { mutableStateOf(-1) }
    LaunchedEffect(listState, canLoad) {
        if (!canLoad) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= info.totalItemsCount - 2 && info.totalItemsCount > 0
        }.distinctUntilChanged().collect { nearEnd ->
            if (nearEnd && lastTotal != listState.layoutInfo.totalItemsCount) {
                lastTotal = listState.layoutInfo.totalItemsCount
                load()
            }
        }
    }
}

/**
 * 价格区间面板（SearchFilterPrice）：最低价 – 最高价（元，最多两位小数）、按券后价筛选、重置 / 确定；不合法在面板内提示，不发请求。
 * 未提交的输入在 ViewModel 的 [PriceDraft] 里，配置重建不丢（03 §10.4，A-R1-07）。
 */
@Composable
private fun PriceFilterSheet(
    model: SearchViewModel,
    texts: SearchTexts,
    priceFocus: FocusRequester,
) {
    val draft by model.priceDraft.collectAsStateWithLifecycle()
    // SearchFilterPrice-v2：标题 + 右上关闭；「最低价」「最高价」12 标签在框上方；关闭（按钮、遮罩、返回）后焦点回【价格区间】胶囊
    CouliSheet(
        title = texts.text(SearchKeys.PRICE),
        onDismissRequest = model::dismissOverlay,
        restoreFocusTo = priceFocus,
        closeLabel = texts.text(SearchKeys.CLOSE),
        actions = {
            Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3)) {
                CouliButton(
                    text = texts.text(SearchKeys.SHEET_RESET),
                    onClick = model::resetPriceDraft,
                    variant = CouliButtonVariant.SECONDARY,
                    modifier = Modifier.weight(1f).testTag(SearchTags.PRICE_RESET),
                )
                CouliButton(
                    text = texts.text(SearchKeys.SHEET_CONFIRM),
                    onClick = { model.applyPriceDraft() },
                    modifier = Modifier.weight(1f).testTag(SearchTags.PRICE_CONFIRM),
                )
            }
        },
    ) {
        // 两栏按输入框外框中线对齐，横杠按自身中线对齐同一条线（S2-5：不再用固定偏移，200% 下仍居中）
        Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
            val frame = Modifier.weight(1f).alignBy(SearchPriceFrameCenter)
            SearchPriceField(draft.min, texts.text(SearchKeys.SHEET_MIN), SearchTags.PRICE_MIN, model::onPriceMin, frame)
            SearchPriceDash("–")
            SearchPriceField(draft.max, texts.text(SearchKeys.SHEET_MAX), SearchTags.PRICE_MAX, model::onPriceMax, frame)
        }
        Text(text = texts.text(SearchKeys.SHEET_NOTE), style = CouliTypography.caption, color = CouliColors.textSecondary)
        if (draft.invalid) {
            Text(
                text = texts.text(SearchKeys.SHEET_INVALID),
                style = CouliTypography.caption,
                color = CouliColors.statusErrorText,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/** 卡片的稳定键：product_key，金额未知卡用 link_id。 */
internal fun cardKey(raw: JsonObject): String = raw.string("product_key") ?: raw.string("link_id") ?: raw.hashCode().toString()

/** 大屏（最小宽度 ≥ 600dp）内容限宽并居中（03 §10.4）。 */
private const val CONTENT_MAX_WIDTH = 600
private const val SORT_GAP = 20
private const val SKELETONS = 3

/** 页面级错误 / 三家都关的状态块离头部的距离（SearchError、SearchDisabled 上 96：外层 16 + 状态块自身 24 + 这里 56）。 */
private const val PAGE_STATE_TOP = 56

/** 页面级【重试】最小宽（SearchError：min 160 × 48）。 */
private const val RETRY_MIN_WIDTH = 160
private const val DISCLAIMER_MAX_HEIGHT = 160

/** 卡片映射：带上此刻的平台购买状态（features.platform_status，APP-06a 共性第 27、31 条）。 */
private fun mapCard(
    card: JsonObject,
    texts: SearchTexts,
    now: Long,
    port: SearchConfigPort,
): MappedProductCard {
    val platform = (card["platform"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
    return ProductCardMapper.map(card, texts, now, purchaseStatus = port.platformStatus(platform))
}

private const val PRICE_COMPARE = "price_compare"
private const val OFF_SHELF = "off_shelf"

/** 下架状态句（契约字典 error.30141「商品已下架」，与详情原位置灰的胶囊同键）。 */
private const val OFF_SHELF_KEY = "error.30141"
private const val COMPARE_REASON_KEY = "no_rebate.price_compare"
private const val COMPARE_SIMILAR_KEY = "no_rebate.price_compare.similar"
private const val SIMILAR_SUFFIX = "_similar"

/** 搜索结果卡购买按钮的 spm（页面.模块.坑位，03 §4.7）。 */
private const val SPM_PREFIX = "search.result_list."
