package com.couli.feature.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.couli.core.designsystem.CouliCategoryIcons
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
import com.couli.core.designsystem.PlatformIcon
import com.couli.core.designsystem.PlatformIconSize
import com.couli.core.designsystem.ProductCardGrid
import com.couli.core.designsystem.ProductCardKeys
import com.couli.core.designsystem.couliCard
import com.couli.core.designsystem.platformIconAssetPath
import com.couli.core.designsystem.rememberProductGridColumns
import com.couli.core.home.HomeDraftTexts
import com.couli.core.home.HomeErrors
import com.couli.core.home.HomeIcon
import kotlinx.coroutines.delay

/** 一张卡进列表：信息流按行展开（触底加载下一页、底部【重试】），其余每张卡一项。 */
@Suppress("LongParameterList") // 卡、控制器、可见性 / 读屏、限宽与信息流列数
internal fun LazyListScope.homeCard(
    card: HomeCardUi,
    controller: HomeController,
    active: Boolean,
    reader: Boolean,
    maxWidth: Int,
    feedColumns: Int = FEED_COLUMNS,
) {
    if (card is HomeCardUi.Feed) {
        feed(card, controller, maxWidth, feedColumns)
        return
    }
    item(key = HomeListKeys.card(card.id), contentType = card::class.simpleName) {
        Box(
            modifier =
                Modifier
                    .widthIn(max = maxWidth.dp)
                    .fillMaxWidth()
                    // 区块间距 12、左右 16（Main-v3）：每块只带下方间距，空块不留缝（列表顶另有一个 12 的起始项）
                    .padding(start = CouliSpacing.space4, end = CouliSpacing.space4, bottom = HomeLayout.blockGap)
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
    // Main-v3：白卡内边距 16，行距 16、列距 8
    Column(
        modifier = Modifier.fillMaxWidth().couliCard().padding(CouliSpacing.space4),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space4),
    ) {
        // 列数按 props 的 columns，不随屏宽增加（APP-05a 第 17 条）
        card.cells.chunked(card.columns).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
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
        // 标签 12 次文字色（Main-v3）
        Text(
            text = cell.title,
            style = CouliTypography.caption,
            color = CouliColors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun IconTile(
    icon: HomeIcon,
    title: String,
) {
    when (icon) {
        is HomeIcon.Remote -> {
            // 服务端下发图：44 图框，加载中 / 失败为占位底
            Box(
                modifier =
                    Modifier
                        .size(ICON_TILE.dp)
                        .clip(RoundedCornerShape(CouliRadius.button))
                        .background(CouliColors.backgroundCanvas),
            ) {
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
        }

        is HomeIcon.Builtin -> {
            val vector = CouliCategoryIcons.byName(icon.name)
            if (vector != null) {
                // 类目格（G-V08）：44 圆角 12、页面底色底 + 24 线性图标（主文字色）
                Box(
                    modifier =
                        Modifier
                            .size(ICON_TILE.dp)
                            .clip(RoundedCornerShape(CouliRadius.button))
                            .background(CouliColors.backgroundCanvas),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(vector, contentDescription = null, tint = CouliColors.textPrimary, modifier = Modifier.size(CouliSizes.iconLarge))
                }
            } else {
                PlatformCellIcon(icon, title)
            }
        }
    }
}

/**
 * 金刚区平台格的图形（G-V01）：有内置官方图的平台画 F-X-05a 的 `PlatformIcon(code, HOME)`（44，原图不改色不描边，装饰、读屏不可见）；
 * 暂缺官方图的平台退回文字胶囊（字直接画在画布上，不建文字节点）。两种都只是装饰，整格只读一次格名。
 */
@Composable
private fun PlatformCellIcon(
    icon: HomeIcon.Builtin,
    title: String,
) {
    val code = icon.name.removePrefix(PLATFORM_ICON_PREFIX)
    if (platformIconAssetPath(code) != null) {
        PlatformIcon(
            platformCode = code,
            size = PlatformIconSize.HOME,
            modifier = Modifier.testTag(HomeTags.PLATFORM_CELL_PREFIX + icon.name),
        )
        return
    }
    // 胶囊字直接画在画布上（不建文字节点）：与格下标签同字，读屏与语义树都只有格名一份
    val measurer = rememberTextMeasurer()
    val style = CouliTypography.caption
    val color = CouliColors.sourceText
    val layout = remember(title, style, measurer) { measurer.measure(title, style, maxLines = 1, softWrap = false) }
    val size = with(LocalDensity.current) { DpSize(layout.size.width.toDp(), layout.size.height.toDp()) }
    val chip = RoundedCornerShape(CouliRadius.small)
    Box(
        modifier =
            Modifier
                .size(ICON_TILE.dp)
                .clip(RoundedCornerShape(CouliRadius.button))
                .background(CouliColors.backgroundMuted)
                .testTag(HomeTags.PLATFORM_CELL_PREFIX + icon.name),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .clip(chip)
                    .background(CouliColors.sourceBackground)
                    .border(CouliSizes.hairline, CouliColors.sourceBorder, chip)
                    .padding(horizontal = CouliSpacing.space1)
                    .size(size)
                    .drawBehind { drawText(layout, color = color) },
        )
    }
}

private const val ICON_TILE = 44

/** 包内默认 / 服务端平台格的内置图标名前缀（`platform.taobao` → 平台编码 `taobao`）。 */
private const val PLATFORM_ICON_PREFIX = "platform."

// ---------------------------------------------------------------- 双入口

@Composable
private fun ActivityCard(
    card: HomeCardUi.Activity,
    controller: HomeController,
) {
    // Main-v3：两列等宽白卡、列距 12；服务端只给一张时占整行（游客，G-V10）。装饰（步骤点、圆底图标）无字段，不做（G-V04）
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3)) {
        card.cells.forEach { cell ->
            val tap = cell.tap
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .heightIn(min = ACTIVITY_MIN_HEIGHT.dp)
                        .couliCard()
                        .clickable(role = Role.Button) { controller.open(tap) }
                        .semantics(mergeDescendants = true) {
                            // 纯图片入口（G12 允许只有 image + action）：没有标题时整个入口读草稿表的通用名称（R1-08）
                            if (cell.title == null) contentDescription = HomeDraftTexts.text(HomeDraftTexts.ACTIVITY_IMAGE)
                        }.padding(CouliSpacing.space3)
                        .testTag(HomeTags.tap(tap.sectionId, tap.slot)),
                verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1, Alignment.CenterVertically),
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
                    // 标题 14/600，最多 2 行省略（brief §2.9）
                    Text(
                        text = it,
                        style = CouliTextStyles.footnoteSemibold,
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

private const val ACTIVITY_MIN_HEIGHT = 72

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
                // 公告条无定稿，按令牌推导：圆角同卡片 16（A14）
                .clip(RoundedCornerShape(CouliRadius.card))
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

// ---------------------------------------------------------------- 商品卡（首页网格卡；不放购买按钮，G13）

@Composable
private fun ProductTile(
    product: HomeProductUi,
    controller: HomeController,
    onInfo: (HomeProductUi) -> Unit,
    modifier: Modifier = Modifier,
    framed: Boolean = true,
) {
    val tap = product.tap
    Box(
        modifier =
            modifier
                .then(if (tap != null) Modifier.clickable(role = Role.Button) { controller.open(tap) } else Modifier)
                .then(if (tap != null) Modifier.testTag(HomeTags.tap(tap.sectionId, tap.slot)) else Modifier),
    ) {
        // 首页紧凑返利块（r1 S2-5）：i 视觉 16 不撑高首行，命中区仍 48
        ProductCardGrid(
            data = product.data,
            modifier = Modifier.fillMaxWidth(),
            onInfo = { onInfo(product) },
            framed = framed,
            compactRebate = true,
        )
        // 「推广」角标在商品图左上 8（GUIDE §6）：带卡底时图片本身离卡边 8
        val inset = if (framed) CouliSpacing.space2 + CouliSpacing.space2 else CouliSpacing.space2
        product.adLabel?.let { AdLabel(it, Modifier.align(Alignment.TopStart).padding(inset)) }
    }
}

@Composable
internal fun ProductNote(
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

/** 段卡标题行（Main-v3：17/600 在卡内；【更多 ›】强调色 14，命中区 48）。 */
@Composable
internal fun SectionTitle(
    title: String,
    more: HomeTap?,
    controller: HomeController,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = CouliTextStyles.bodySemibold,
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
                    color = CouliColors.brandEmphasis,
                )
                Icon(
                    CouliIcons.chevron,
                    contentDescription = null,
                    tint = CouliColors.brandEmphasis,
                    modifier = Modifier.size(CouliSizes.iconSmall),
                )
            }
        }
    }
}

@Composable
private fun ProductGridCard(
    card: HomeCardUi.ProductGrid,
    controller: HomeController,
) {
    var note by remember { mutableStateOf<HomeProductUi?>(null) }
    // 整段放进白卡（Main-v3「超市百货」）：标题在卡内；窄屏 + 大字号放不下时整组单列（ProductCardGridLayout 同一口径，R2-01）
    Column(
        modifier = Modifier.fillMaxWidth().couliCard().padding(CouliSpacing.space4),
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        card.title?.let { SectionTitle(it, null, controller) }
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val data = remember(card.items) { card.items.map { it.data } }
            val columns = rememberProductGridColumns(data, maxWidth, card.columns, includeRebateHeader = true)
            ProductRows(card.items, columns, controller, framed = false) { note = it }
        }
    }
    ProductNote(note, controller) { note = null }
}

@Composable
private fun ProductRows(
    items: List<HomeProductUi>,
    columns: Int,
    controller: HomeController,
    framed: Boolean = true,
    onInfo: (HomeProductUi) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3)) {
        items.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3)) {
                row.forEach { product -> ProductTile(product, controller, onInfo, Modifier.weight(1f), framed) }
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
    columns: Int,
) {
    // 每件商品一张独立白卡（Main-v3），默认两列；窄屏 + 大字号整组单列（列数由列表按内容区宽度先算好）
    val rows = card.items.chunked(columns)
    rows.forEachIndexed { index, row ->
        item(key = HomeListKeys.feedRow(card.id, index), contentType = "feed_row") {
            var note by remember { mutableStateOf<HomeProductUi?>(null) }
            Box(
                modifier =
                    Modifier
                        .widthIn(max = maxWidth.dp)
                        .fillMaxWidth()
                        .padding(start = CouliSpacing.space4, end = CouliSpacing.space4, bottom = HomeLayout.blockGap)
                        .then(if (index == 0) Modifier.testTag(HomeTags.CARD_PREFIX + card.id) else Modifier),
            ) {
                ProductRows(row, columns, controller) { note = it }
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

internal const val FEED_COLUMNS = 2

/** 首页版式常量（Main-v3）。 */
internal object HomeLayout {
    /** 区块间距 12。 */
    val blockGap = CouliSpacing.space3
}
