package com.couli.feature.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliRadius
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTag
import com.couli.core.designsystem.CouliTagTone
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.DisclaimerNoteDialog
import com.couli.core.designsystem.LocalProductImageLoader
import com.couli.core.designsystem.ProductCardGrid
import com.couli.core.designsystem.ProductCardKeys
import com.couli.core.designsystem.couliCard
import com.couli.core.home.HomeDraftTexts
import com.couli.core.home.HomeErrors
import com.couli.core.home.HomeIcon
import kotlinx.coroutines.delay

/** 一张卡进列表：信息流按行展开（触底加载下一页、底部【重试】），其余每张卡一项。 */
internal fun LazyListScope.homeCard(
    card: HomeCardUi,
    controller: HomeController,
    active: Boolean,
    reader: Boolean,
    maxWidth: Int,
) {
    if (card is HomeCardUi.Feed) {
        feed(card, controller, maxWidth)
        return
    }
    item(key = HomeListKeys.card(card.id), contentType = card::class.simpleName) {
        Box(
            modifier =
                Modifier
                    .widthIn(max = maxWidth.dp)
                    .fillMaxWidth()
                    .padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space2)
                    .testTag(HomeTags.CARD_PREFIX + card.id),
        ) {
            when (card) {
                is HomeCardUi.Banner -> BannerCard(card, controller, active)
                is HomeCardUi.IconGrid -> IconGridCard(card, controller)
                is HomeCardUi.Activity -> ActivityCard(card, controller)
                is HomeCardUi.Notice -> NoticeCard(card, controller, active && !reader, reader)
                is HomeCardUi.ProductScroll -> ProductScrollCard(card, controller)
                is HomeCardUi.ProductGrid -> ProductGridCard(card, controller)
                is HomeCardUi.Feed -> Unit
            }
        }
    }
}

// ---------------------------------------------------------------- 图片（复用 App 的商品图加载：只收 https，失败显示占位且不可点）

private sealed interface HomeImageState {
    data object Loading : HomeImageState

    data class Ready(
        val bitmap: ImageBitmap,
    ) : HomeImageState

    data object Failed : HomeImageState
}

@Composable
private fun rememberHomeImage(url: String): HomeImageState {
    val loader = LocalProductImageLoader.current
    val state by produceState<HomeImageState>(HomeImageState.Loading, url, loader) {
        value =
            if (loader == null) {
                HomeImageState.Failed
            } else {
                runCatching { loader.load(url) }.getOrNull()?.let { HomeImageState.Ready(it) } ?: HomeImageState.Failed
            }
    }
    return state
}

@Composable
internal fun AdLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    // 「推广」角标原样显示、读屏读出（BR-TEXT-17，G22）
    CouliTag(text = text, tone = CouliTagTone.NEUTRAL, modifier = modifier.testTag(HomeTags.AD_LABEL))
}

// ---------------------------------------------------------------- banner

@Composable
private fun BannerCard(
    card: HomeCardUi.Banner,
    controller: HomeController,
    active: Boolean,
) {
    val count = card.frames.size
    val pager = rememberPagerState(initialPage = (controller.bannerFrames[card.id] ?: 0).coerceIn(0, count - 1)) { count }
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { controller.bannerFrames[card.id] = it } }
    // 自动轮播：切走 Tab、首页被盖住、读屏开启时暂停（WCAG 2.2.2）
    if (active && card.autoplayMs > 0 && count > 1) {
        LaunchedEffect(pager, card.autoplayMs) {
            while (true) {
                delay(card.autoplayMs)
                pager.animateScrollToPage((pager.currentPage + 1) % count)
            }
        }
    }
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f / card.heightRatio)
                .clip(RoundedCornerShape(CouliRadius.card)),
    ) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { index ->
            BannerFrame(card.frames[index], index, count, controller)
        }
        if (count > 1) {
            Row(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = CouliSpacing.space2)
                        .clearAndSetSemantics {},
                horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
            ) {
                repeat(count) { dot ->
                    Box(
                        modifier =
                            Modifier
                                .size(DOT_SIZE.dp)
                                .clip(CircleShape)
                                .background(if (dot == pager.currentPage) CouliColors.brandPrimary else CouliColors.backgroundSurface)
                                .testTag(HomeTags.BANNER_DOT),
                    )
                }
            }
        }
    }
}

private const val DOT_SIZE = 6

@Composable
private fun BannerFrame(
    frame: HomeBannerFrame,
    index: Int,
    count: Int,
    controller: HomeController,
) {
    val image = rememberHomeImage(frame.image)
    val position = HomeDraftTexts.format(HomeDraftTexts.BANNER_POSITION, mapOf("index" to "${index + 1}", "count" to "$count"))
    val spoken = listOfNotNull(frame.adLabel, frame.description, position).joinToString(HomeDraftTexts.SEPARATOR)
    val tap = frame.tap
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(CouliColors.backgroundPlaceholder)
                .then(
                    if (image is HomeImageState.Ready) {
                        Modifier.clickable(role = Role.Button) { controller.open(tap) }
                    } else {
                        Modifier
                    },
                ).semantics(mergeDescendants = true) { contentDescription = spoken }
                .testTag(HomeTags.tap(tap.sectionId, tap.slot)),
    ) {
        if (image is HomeImageState.Ready) {
            Image(bitmap = image.bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        frame.adLabel?.let { AdLabel(it, Modifier.align(Alignment.TopEnd).padding(CouliSpacing.space2)) }
    }
}

// ---------------------------------------------------------------- 金刚区

@Composable
private fun IconGridCard(
    card: HomeCardUi.IconGrid,
    controller: HomeController,
) {
    Column(
        modifier = Modifier.fillMaxWidth().couliCard().padding(vertical = CouliSpacing.space2),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
    ) {
        // 列数按 props 的 columns，不随屏宽增加（APP-05a 第 17 条）
        card.cells.chunked(card.columns).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { cell -> IconCell(cell, controller, Modifier.weight(1f)) }
                repeat(card.columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun IconCell(
    cell: HomeIconCell,
    controller: HomeController,
    modifier: Modifier,
) {
    val tap = cell.tap
    // 只有图标的格整格一个读屏节点，读格名（与角标）（adaptation-a11y-spec §9）
    val spoken = listOfNotNull(cell.adLabel, cell.title, cell.badge).joinToString(HomeDraftTexts.SEPARATOR)
    Column(
        modifier =
            modifier
                .heightIn(min = CouliSizes.touchTarget)
                .clickable(role = Role.Button) { controller.open(tap) }
                .semantics(mergeDescendants = true) { contentDescription = spoken }
                .padding(horizontal = CouliSpacing.space1, vertical = CouliSpacing.space1)
                .testTag(HomeTags.tap(tap.sectionId, tap.slot)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
    ) {
        cell.adLabel?.let { AdLabel(it) }
        Box(contentAlignment = Alignment.TopEnd) {
            IconTile(cell.icon, cell.title)
            cell.badge?.let { badge ->
                Text(
                    text = badge,
                    style = CouliTypography.caption,
                    color = CouliColors.textInverse,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(CouliRadius.pill))
                            .background(CouliColors.brandPrimary)
                            .padding(horizontal = CouliSpacing.space1),
                )
            }
        }
        Text(
            text = cell.title,
            style = CouliTypography.caption,
            color = CouliColors.textPrimary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun IconTile(
    icon: HomeIcon,
    title: String,
) {
    Box(
        modifier =
            Modifier
                .size(ICON_TILE.dp)
                .clip(RoundedCornerShape(CouliRadius.button))
                .background(CouliColors.backgroundMuted),
        contentAlignment = Alignment.Center,
    ) {
        when (icon) {
            is HomeIcon.Remote -> {
                val image = rememberHomeImage(icon.url)
                if (image is HomeImageState.Ready) {
                    Image(
                        bitmap = image.bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            is HomeIcon.Builtin -> {
                val vector = builtinIcon(icon.name)
                if (vector != null) {
                    Icon(
                        vector,
                        contentDescription = null,
                        tint = CouliColors.brandEmphasis,
                        modifier = Modifier.size(CouliSizes.iconLarge),
                    )
                } else {
                    // 平台格：平台 logo 资源还没入库（H08 同口径），显示平台名（来源色）
                    Text(
                        text = title,
                        style = CouliTypography.caption,
                        color = CouliColors.sourceText,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(CouliRadius.small))
                                .background(CouliColors.sourceBackground)
                                .border(CouliSizes.hairline, CouliColors.sourceBorder, RoundedCornerShape(CouliRadius.small))
                                .padding(horizontal = CouliSpacing.space1),
                    )
                }
            }
        }
    }
}

private const val ICON_TILE = 44

/** 包内默认的类目 / 指南图标借用设计系统已有线性图标（G17；平台格返回 null，按文字显示）。 */
private fun builtinIcon(name: String): ImageVector? =
    when (name) {
        "category.supermarket" -> CouliIcons.orders
        "category.fresh" -> CouliIcons.heart
        "category.baby" -> CouliIcons.user
        "category.beauty" -> CouliIcons.star
        "category.household" -> CouliIcons.home
        "category.electronics" -> CouliIcons.trend
        "guide" -> CouliIcons.help
        else -> null
    }

// ---------------------------------------------------------------- 双入口

@Composable
private fun ActivityCard(
    card: HomeCardUi.Activity,
    controller: HomeController,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3)) {
        card.cells.forEach { cell ->
            val tap = cell.tap
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .heightIn(min = CouliSizes.touchTarget)
                        .couliCard()
                        .clickable(role = Role.Button) { controller.open(tap) }
                        .semantics(mergeDescendants = true) {
                            // 纯图片入口（G12 允许只有 image + action）：没有标题时整个入口读草稿表的通用名称（R1-08）
                            if (cell.title == null) contentDescription = HomeDraftTexts.text(HomeDraftTexts.ACTIVITY_IMAGE)
                        }.padding(CouliSpacing.space3)
                        .testTag(HomeTags.tap(tap.sectionId, tap.slot)),
                verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
            ) {
                cell.adLabel?.let { AdLabel(it) }
                cell.image?.let { url ->
                    val image = rememberHomeImage(url)
                    if (image is HomeImageState.Ready) {
                        Image(
                            bitmap = image.bitmap,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().aspectRatio(2f).clip(RoundedCornerShape(CouliRadius.small)),
                        )
                    }
                }
                cell.title?.let {
                    // 标题最多 2 行省略（brief §2.9）
                    Text(
                        text = it,
                        style = CouliTextStyles.bodySemibold,
                        color = CouliColors.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                cell.subtitle?.let { Text(text = it, style = CouliTypography.caption, color = CouliColors.textSecondary) }
            }
        }
    }
}

// ---------------------------------------------------------------- 公告条

@Composable
private fun NoticeCard(
    card: HomeCardUi.Notice,
    controller: HomeController,
    autoScroll: Boolean,
    reader: Boolean,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(CouliRadius.button))
                .background(CouliColors.brandSubtle),
    ) {
        card.adLabel?.let { AdLabel(it, Modifier.padding(start = CouliSpacing.space3, top = CouliSpacing.space2)) }
        if (card.scroll && card.lines.size > 1 && !reader) {
            // 滚动模式：一次一条；不可见时不自动滚动。读屏开启时走下面的静态分支：按顺序展开全部有效公告（R1-07，共性第 29 条）
            var index by remember(card.lines) { mutableIntStateOf(0) }
            if (autoScroll) {
                LaunchedEffect(card.lines) {
                    while (true) {
                        delay(NOTICE_SCROLL_MS)
                        index = (index + 1) % card.lines.size
                    }
                }
            }
            NoticeRow(card.id, card.lines[index.coerceIn(0, card.lines.size - 1)], controller)
        } else {
            card.lines.forEach { line -> NoticeRow(card.id, line, controller) }
        }
    }
}

private const val NOTICE_SCROLL_MS = 3_000L

@Composable
private fun NoticeRow(
    sectionId: String,
    line: HomeNoticeLine,
    controller: HomeController,
) {
    val tap = line.tap
    val close = line.close
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = CouliSizes.touchTarget).padding(start = CouliSpacing.space3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
    ) {
        Icon(CouliIcons.bell, contentDescription = null, tint = CouliColors.brandEmphasis, modifier = Modifier.size(CouliSizes.iconSmall))
        Text(
            text = line.text,
            style = CouliTypography.footnote,
            color = CouliColors.textPrimary,
            modifier =
                Modifier
                    .weight(1f)
                    .padding(vertical = CouliSpacing.space2)
                    .then(
                        if (tap != null) {
                            Modifier
                                .clickable(role = Role.Button) { controller.open(tap) }
                                .testTag(HomeTags.tap(tap.sectionId, tap.slot))
                        } else {
                            Modifier
                        },
                    ),
        )
        if (close != null) {
            Box(
                modifier =
                    Modifier
                        .size(CouliSizes.touchTarget)
                        .clickable(role = Role.Button) { controller.closeNotice(close) }
                        .semantics { contentDescription = HomeDraftTexts.text(HomeDraftTexts.NOTICE_CLOSE) }
                        .testTag("${HomeTags.NOTICE_CLOSE_PREFIX}${sectionId}_${line.itemId}"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    CouliIcons.close,
                    contentDescription = null,
                    tint = CouliColors.textSecondary,
                    modifier = Modifier.size(CouliSizes.iconSmall),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 商品卡（首页窄卡 / 网格卡；不放购买按钮，G13）

@Composable
private fun ProductTile(
    product: HomeProductUi,
    controller: HomeController,
    onInfo: (HomeProductUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tap = product.tap
    Box(
        modifier =
            modifier
                .then(if (tap != null) Modifier.clickable(role = Role.Button) { controller.open(tap) } else Modifier)
                .then(if (tap != null) Modifier.testTag(HomeTags.tap(tap.sectionId, tap.slot)) else Modifier),
    ) {
        ProductCardGrid(data = product.data, modifier = Modifier.fillMaxWidth(), onInfo = { onInfo(product) })
        product.adLabel?.let { AdLabel(it, Modifier.align(Alignment.TopStart).padding(CouliSpacing.space3)) }
    }
}

@Composable
private fun ProductNote(
    note: HomeProductUi?,
    controller: HomeController,
    onDismiss: () -> Unit,
) {
    note ?: return
    // 首页自己的弹层也是弹窗的阻挡项（R1-05，共性第 27 条）：出现时登记、消失时注销，首页与预览实例各自的控制器据此复核
    val token = remember { Any() }
    DisposableEffect(token) {
        controller.overlayShown(token)
        onDispose { controller.overlayGone(token) }
    }
    val texts = controller.cardTexts
    DisclaimerNoteDialog(
        title = texts.text(ProductCardKeys.DISCLAIMER_TITLE),
        lines = note.data.disclaimer,
        closeText = texts.text(ProductCardKeys.DISCLAIMER_CLOSE),
        onDismiss = onDismiss,
    )
}

@Composable
private fun SectionTitle(
    title: String,
    more: HomeTap?,
    controller: HomeController,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = CouliTypography.title,
            color = CouliColors.textPrimary,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (more != null) {
            Row(
                modifier =
                    Modifier
                        .heightIn(min = CouliSizes.touchTarget)
                        .clickable(role = Role.Button) { controller.open(more) }
                        .padding(start = CouliSpacing.space2)
                        .testTag(HomeTags.MORE_PREFIX + more.sectionId),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = HomeDraftTexts.text(HomeDraftTexts.SCROLL_MORE),
                    style = CouliTypography.footnote,
                    color = CouliColors.textSecondary,
                )
                Icon(
                    CouliIcons.chevron,
                    contentDescription = null,
                    tint = CouliColors.textSecondary,
                    modifier = Modifier.size(CouliSizes.iconSmall),
                )
            }
        }
    }
}

@Composable
private fun ProductScrollCard(
    card: HomeCardUi.ProductScroll,
    controller: HomeController,
) {
    var note by remember { mutableStateOf<HomeProductUi?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
        SectionTitle(card.title, card.more, controller)
        // 按服务端顺序，不重排、不过滤、不补数（AC-S1-90 ③）
        LazyRow(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
            // 键按位置 + 商品键：服务端重复的 product_key 不会撞键（R1-02）
            itemsIndexed(card.items, key = { index, product -> HomeListKeys.product(index, product.key) }) { _, product ->
                ProductTile(product, controller, { note = it }, Modifier.width(SCROLL_CARD_WIDTH.dp))
            }
        }
    }
    ProductNote(note, controller) { note = null }
}

private const val SCROLL_CARD_WIDTH = 156

@Composable
private fun ProductGridCard(
    card: HomeCardUi.ProductGrid,
    controller: HomeController,
) {
    var note by remember { mutableStateOf<HomeProductUi?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
        card.title?.let { SectionTitle(it, null, controller) }
        ProductRows(card.items, card.columns, controller) { note = it }
    }
    ProductNote(note, controller) { note = null }
}

@Composable
private fun ProductRows(
    items: List<HomeProductUi>,
    columns: Int,
    controller: HomeController,
    onInfo: (HomeProductUi) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3)) {
        items.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3)) {
                row.forEach { product -> ProductTile(product, controller, onInfo, Modifier.weight(1f)) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

// ---------------------------------------------------------------- 信息流（置底、触底加载下一页；一页失败只停在【重试】，不影响上方卡片）

@Suppress("LongMethod") // 行、失败行（实际文案 + 随禁用期置灰的重试）、触底项
private fun LazyListScope.feed(
    card: HomeCardUi.Feed,
    controller: HomeController,
    maxWidth: Int,
) {
    val rows = card.items.chunked(FEED_COLUMNS)
    rows.forEachIndexed { index, row ->
        item(key = HomeListKeys.feedRow(card.id, index), contentType = "feed_row") {
            var note by remember { mutableStateOf<HomeProductUi?>(null) }
            Box(
                modifier =
                    Modifier
                        .widthIn(max = maxWidth.dp)
                        .fillMaxWidth()
                        .padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space1)
                        .then(if (index == 0) Modifier.testTag(HomeTags.CARD_PREFIX + card.id) else Modifier),
            ) {
                ProductRows(row, FEED_COLUMNS, controller) { note = it }
            }
            ProductNote(note, controller) { note = null }
        }
    }
    val ticket = card.ticket
    when {
        card.failed -> {
            item(key = HomeListKeys.feedRetry(card.id), contentType = "feed_retry") {
                Column(
                    modifier =
                        Modifier
                            .widthIn(max = maxWidth.dp)
                            .fillMaxWidth()
                            .padding(CouliSpacing.space4),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
                ) {
                    // 实际失败的文案（R2-04：错误码与 trace6 已经由控制器经 HomeErrors.message 生成）
                    Text(
                        text = card.error ?: HomeErrors.message(HomeErrors.GENERIC, null),
                        style = CouliTypography.footnote,
                        color = CouliColors.textSecondary,
                        modifier = Modifier.testTag(HomeTags.FEED_ERROR),
                    )
                    // 共享禁用期中置灰、到期恢复（R2-03）
                    val enabled = !card.rateLimited
                    Text(
                        text = HomeDraftTexts.text(HomeDraftTexts.RETRY),
                        style = CouliTextStyles.bodySemibold,
                        color = if (enabled) CouliColors.textLink else CouliColors.textSecondary,
                        modifier =
                            Modifier
                                .heightIn(min = CouliSizes.touchTarget)
                                .clickable(enabled = enabled, role = Role.Button) { controller.retryFeed(ticket) }
                                .semantics { role = Role.Button }
                                .padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space3)
                                .testTag(HomeTags.FEED_RETRY),
                    )
                }
            }
        }

        card.hasMore -> {
            item(key = HomeListKeys.feedMore(card.id), contentType = "feed_more") {
                // 触底：这一项进入组合即加载下一页（只在没有在途、没有失败时）。禁用期内被拦下的，到期（wake 变化）时这一项若仍在组合中（仍触底），
                // 用原渲染票据再试一次（R2-03）；控制器照旧核对票据与禁用期
                LaunchedEffect(ticket, card.items.size, card.wake) { controller.loadMore(ticket) }
                Spacer(Modifier.fillMaxWidth().heightIn(min = CouliSpacing.space4).testTag(HomeTags.FEED_MORE))
            }
        }
    }
}

private const val FEED_COLUMNS = 2
