package com.couli.app.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.couli.core.designsystem.AmountCell
import com.couli.core.designsystem.AmountGrid
import com.couli.core.designsystem.AmountGridTone
import com.couli.core.designsystem.AmountMask
import com.couli.core.designsystem.AmountValueSize
import com.couli.core.designsystem.BuyButton
import com.couli.core.designsystem.BuyButtonModel
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonSize
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliIconButton
import com.couli.core.designsystem.CouliIcons
import com.couli.core.designsystem.CouliRadius
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.DisclaimerFooter
import com.couli.core.designsystem.DisclaimerLine
import com.couli.core.designsystem.DisclaimerStyle
import com.couli.core.designsystem.EmptyState
import com.couli.core.designsystem.EntryGrid
import com.couli.core.designsystem.EntryItem
import com.couli.core.designsystem.InfoStrip
import com.couli.core.designsystem.NavBar
import com.couli.core.designsystem.PageTitleBar
import com.couli.core.designsystem.ProductCardData
import com.couli.core.designsystem.ProductCardGridLayout
import com.couli.core.designsystem.ProductCardLarge
import com.couli.core.designsystem.ProductCardList
import com.couli.core.designsystem.ProductCardVariant
import com.couli.core.designsystem.ProductThumbnail
import com.couli.core.designsystem.TabBar
import com.couli.core.designsystem.TabItem
import com.couli.core.designsystem.WalletHeroCard
import com.couli.core.designsystem.WalletHeroTexts
import com.couli.core.designsystem.couliCard

/** 对照页的外壳：米白底、状态栏让位、内容可滚、底栏贴系统导航区。 */
@Composable
internal fun ComparePageScaffold(
    top: @Composable () -> Unit,
    bottom: (@Composable () -> Unit)? = null,
    whiteHeader: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(CouliColors.backgroundCanvas)) {
        Box(
            Modifier.background(if (whiteHeader) CouliColors.backgroundSurface else CouliColors.backgroundCanvas).statusBarsPadding(),
        ) { top() }
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space2),
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
            content = content,
        )
        if (bottom != null) Box(Modifier.background(CouliColors.backgroundSurface).navigationBarsPadding()) { bottom() }
    }
}

internal val tabItems =
    listOf(
        TabItem(CouliIcons.home, "首页"),
        TabItem(CouliIcons.chat, "AI 助手"),
        TabItem(CouliIcons.orders, "订单"),
        TabItem(CouliIcons.user, "我的"),
    )

@Composable
private fun SearchHeader(
    query: String,
    priceFilter: String?,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.background(CouliColors.backgroundSurface)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = CouliSpacing.space4, start = CouliSpacing.space1),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
        ) {
            CouliIconButton(icon = CouliIcons.back, label = CatalogSamples.BACK, onClick = onBack)
            Row(
                modifier =
                    Modifier
                        .weight(1f)
                        .heightIn(min = CouliSizes.touchTarget)
                        .clip(RoundedCornerShape(CouliRadius.button))
                        .border(CouliSizes.hairline, CouliColors.borderControl, RoundedCornerShape(CouliRadius.button))
                        .padding(start = CouliSpacing.space3),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
            ) {
                Icon(CouliIcons.search, null, tint = CouliColors.textSecondary, modifier = Modifier.size(CouliSizes.iconMedium))
                Text(query, style = CouliTypography.body, color = CouliColors.textPrimary, maxLines = 1, modifier = Modifier.weight(1f))
                CouliIconButton(
                    icon = CouliIcons.close,
                    label = "清除",
                    onClick = {},
                    tint = CouliColors.textSecondary,
                    iconSize = CouliSizes.iconSmall,
                )
            }
            CouliButton(text = "搜索", onClick = {})
        }
        Row(
            modifier = Modifier.padding(horizontal = CouliSpacing.space4),
            horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space6),
        ) {
            listOf(CatalogSamples.TAOBAO, CatalogSamples.JD, CatalogSamples.PDD).forEachIndexed { index, name ->
                Column(modifier = Modifier.heightIn(min = CouliSizes.touchTarget), verticalArrangement = Arrangement.Bottom) {
                    Text(
                        name,
                        style = if (index == 0) CouliTextStyles.bodySemibold else CouliTypography.body,
                        color = if (index == 0) CouliColors.brandEmphasis else CouliColors.textSecondary,
                        modifier = Modifier.padding(vertical = CouliSpacing.space2),
                    )
                    Box(
                        Modifier
                            .width(CouliSpacing.space10 + CouliSpacing.space2)
                            .height(CouliSpacing.space1 / 2 + CouliSizes.hairline)
                            .background(if (index == 0) CouliColors.brandPrimary else CouliColors.backgroundSurface),
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(CouliSizes.hairline).background(CouliColors.borderSubtle))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = CouliSpacing.space4),
            horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space6),
        ) {
            listOf("综合", "销量", "券后价从低到高", "返利从高到低").forEachIndexed { index, sort ->
                Box(Modifier.heightIn(min = CouliSizes.touchTarget), contentAlignment = Alignment.Center) {
                    Text(
                        sort,
                        style = if (index == 0) CouliTextStyles.footnoteSemibold else CouliTypography.footnote,
                        color = if (index == 0) CouliColors.textPrimary else CouliColors.textSecondary,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.padding(start = CouliSpacing.space4, end = CouliSpacing.space4, bottom = CouliSpacing.space3),
            horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
        ) {
            FilterChip("只看有券", selected = false)
            FilterChip(priceFilter ?: "价格区间", selected = priceFilter != null, dropdown = true)
        }
        Box(Modifier.fillMaxWidth().height(CouliSizes.hairline).background(CouliColors.borderSubtle))
    }
}

@Composable
private fun FilterChip(
    text: String,
    selected: Boolean,
    dropdown: Boolean = false,
) {
    val shape = RoundedCornerShape(CouliRadius.pill)
    Row(
        modifier =
            Modifier
                .heightIn(min = CouliSizes.visualTarget - CouliSpacing.space2)
                .clip(shape)
                .background(if (selected) CouliColors.brandSubtle else CouliColors.backgroundSurface)
                .then(if (selected) Modifier else Modifier.border(CouliSizes.hairline, CouliColors.borderSubtle, shape))
                .clickable(role = Role.Button) {}
                .padding(horizontal = CouliSpacing.space3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space1),
    ) {
        Text(
            text,
            style = if (selected) CouliTextStyles.footnoteSemibold else CouliTypography.footnote,
            color = if (selected) CouliColors.brandEmphasis else CouliColors.textPrimary,
        )
        if (dropdown) {
            Icon(
                CouliIcons.chevronDown,
                null,
                tint = if (selected) CouliColors.brandEmphasis else CouliColors.textPrimary,
                modifier = Modifier.size(CouliSizes.iconSmall),
            )
        }
    }
}

/** 对照页 1：Search（搜索结果列表卡，含比价区间、min = 0 区间、预售无券）。 */
@Composable
internal fun SearchComparePage(onBack: () -> Unit) {
    ComparePageScaffold(
        top = { SearchHeader(query = "纯牛奶", priceFilter = null, onBack = onBack) },
        whiteHeader = true,
        bottom = { TabBar(items = tabItems, selectedIndex = 0, onSelect = {}) },
    ) {
        Spacer(Modifier.height(CouliSpacing.space1))
        val cards = listOf(CatalogSamples.searchMilk, CatalogSamples.searchMilkSmall, CatalogSamples.searchOrganic)
        cards.forEach { ProductCardList(data = it, onBuy = {}) }
        ListFooter(cards = cards, rebateLine = CatalogSamples.REBATE_COMPARE)
    }
}

/** 对照页 6：SearchEmpty（空结果 + 推荐区）。 */
@Composable
internal fun SearchEmptyComparePage(onBack: () -> Unit) {
    ComparePageScaffold(
        top = { SearchHeader(query = "手冲咖啡滤纸 V60 02 型 100 张", priceFilter = "券后 ¥10–¥20", onBack = onBack) },
        whiteHeader = true,
        bottom = { TabBar(items = tabItems, selectedIndex = 0, onSelect = {}) },
    ) {
        EmptyState(title = "没有找到符合条件的商品", message = "换个关键词，或放宽价格区间再试试")
        Column(verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
            Text(
                "看看淘宝的其他商品",
                style = CouliTextStyles.footnoteSemibold,
                color = CouliColors.textPrimary,
                modifier = Modifier.semantics { heading() },
            )
            Text("淘宝推荐商品，不受当前关键词和价格区间限制", style = CouliTypography.caption, color = CouliColors.textSecondary)
        }
        val cards = listOf(CatalogSamples.emptyTissue, CatalogSamples.emptyRice)
        cards.forEach { ProductCardList(data = it, onBuy = {}) }
        ListFooter(cards = cards, rebateLine = CatalogSamples.REBATE_ESTIMATE)
    }
}

/** 对照页 2：ProductCardStress（最长价格与返利组合不破版）。 */
@Composable
internal fun StressComparePage(onBack: () -> Unit) {
    ComparePageScaffold(top = { NavBar(title = "商品卡 · 长内容压力样例", backLabel = CatalogSamples.BACK, onBack = onBack) }) {
        StressSection("① 单一金额：按钮带「· 预估返 ¥x」", CatalogSamples.stressCable)
        StressSection("② 区间（淘宝比价）：按钮只写动词", CatalogSamples.stressMilkRange)
        StressSection("③ 无返利：不显示返利标签与预估返后", CatalogSamples.stressNoRebate)
        StressSection("④ 长价格 + 带金额按钮（窄屏或放大字号时按钮整块换行）", CatalogSamples.stressCoffeeSingle)
        StressSection("列表卡：有券、多标签、超长标题", CatalogSamples.stressCoffeeRange)
        StressSection("预售、无券", CatalogSamples.stressBookcase)
        StressSection("无券、长规格（京东）", CatalogSamples.stressLaptop)
        StressSection("拼多多有券、大金额", CatalogSamples.stressFridge)
        SectionTitle("首页网格卡：大金额、预售与无券")
        ProductCardGridLayout(items = listOf(CatalogSamples.gridCoffee, CatalogSamples.gridBookcase), onInfo = {})
        StressSection("合法最大长度：列表卡（区间两端各 7 位数字）", CatalogSamples.stressMaxRange)
        StressSection("列表商品卡极值（搜索卡，主价 22）", CatalogSamples.stressExtreme)
        SectionTitle("列表商品卡极值（AI 结果卡，主价 17）")
        ProductCardList(data = CatalogSamples.stressExtreme, onBuy = {}, variant = ProductCardVariant.AI)
        ListFooter(cards = CatalogSamples.stressCards, rebateLine = CatalogSamples.REBATE_COMPARE)
    }
}

/** 列表底部口径说明：价格口径句按各卡 disclaimer_keys 选（BR-PRICE-03 细则：混排或全无券用 price_basis.general）。 */
@Composable
private fun ListFooter(
    cards: List<ProductCardData>,
    rebateLine: String,
) {
    DisclaimerFooter(
        lines = listOfNotNull(CatalogSamples.listFooterPriceBasis(cards), rebateLine).map { DisclaimerLine(it) },
    )
}

@Composable
private fun StressSection(
    title: String,
    data: ProductCardData,
) {
    SectionTitle(title)
    ProductCardList(data = data, onBuy = {})
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text = text,
        style = CouliTextStyles.footnoteSemibold,
        color = CouliColors.textPrimary,
        modifier = Modifier.padding(top = CouliSpacing.space2).semantics { heading() },
    )
}

/** 对照页 3：Product 的价格区与底栏。 */
@Composable
internal fun ProductComparePage(onBack: () -> Unit) {
    val data = CatalogSamples.productDetail
    var favorite by remember { mutableStateOf(false) }
    ComparePageScaffold(
        top = { NavBar(title = "商品详情", backLabel = CatalogSamples.BACK, onBack = onBack) },
        bottom = {
            Column {
                Box(Modifier.fillMaxWidth().height(CouliSizes.hairline).background(CouliColors.borderSubtle))
                Row(
                    modifier =
                        Modifier.fillMaxWidth().padding(
                            start = CouliSpacing.space3,
                            end = CouliSpacing.space4,
                            top = CouliSpacing.space3,
                            bottom = CouliSpacing.space3,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
                ) {
                    BottomEntry(CouliIcons.chat, "问问 AI") {}
                    BottomEntry(CouliIcons.star, if (favorite) "已收藏" else "收藏") { favorite = !favorite }
                    BuyButton(
                        base = data.buyBase,
                        rebate = data.rebate,
                        onClick = {},
                        context = BuyButtonModel.CONTEXT_DETAIL,
                        size = CouliButtonSize.STANDARD,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
    ) {
        ProductThumbnail(
            label = CatalogSamples.IMAGE,
            description = "商品图：全脂纯牛奶 250ml × 24 盒",
            modifier = Modifier.fillMaxWidth().height(PRODUCT_IMAGE_HEIGHT),
        )
        ProductCardLarge(data = data)
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(CouliRadius.card))
                    .background(CouliColors.backgroundSurface)
                    .clickable(role = Role.Button) {}
                    .heightIn(min = CouliSizes.touchTarget)
                    .padding(start = CouliSpacing.space4, end = CouliSpacing.space1),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
        ) {
            Icon(CouliIcons.trend, null, tint = CouliColors.textSecondary, modifier = Modifier.size(CouliSizes.iconMedium))
            Text("近 30 天券后价", style = CouliTextStyles.footnoteMedium, color = CouliColors.textPrimary, modifier = Modifier.weight(1f))
            Box(Modifier.size(CouliSizes.touchTarget), contentAlignment = Alignment.Center) {
                Icon(CouliIcons.chevronDown, null, tint = CouliColors.textSecondary, modifier = Modifier.size(CouliSizes.iconLarge))
            }
        }
        DisclaimerFooter(
            style = DisclaimerStyle.DETAIL_PANEL,
            lines =
                listOf(
                    // 详情页按该商品有没有券取对应的一句（BR-PRICE-03 细则）：这张卡有券，disclaimer 第一条是 price_basis
                    DisclaimerLine(data.disclaimer.first()),
                    DisclaimerLine(CatalogSamples.SETTLE_MONTHLY, icon = CouliIcons.clock),
                ),
        )
    }
}

@Composable
private fun BottomEntry(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .heightIn(min = CouliSizes.touchTarget)
                .width(CouliSizes.touchTarget + CouliSpacing.space2)
                .clip(RoundedCornerShape(CouliRadius.small))
                .clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1 / 2, Alignment.CenterVertically),
    ) {
        Icon(icon, null, tint = CouliColors.textPrimary, modifier = Modifier.size(CouliSizes.iconLarge))
        Text(label, style = CouliTypography.caption, color = CouliColors.textSecondary)
    }
}

private val heroTexts =
    WalletHeroTexts(
        label = "可提现余额",
        amount = "¥12.34",
        amountSpoken = "可提现余额，12.34 元",
        withdraw = "提现",
        records = "提现记录",
        hideLabel = "隐藏金额",
        showLabel = "显示金额",
        hiddenSpoken = "可提现余额，" + CatalogSamples.AMOUNT_HIDDEN,
    )

private fun amountMask(hidden: Boolean) =
    AmountMask(hidden = hidden, mask = CatalogSamples.HIDDEN_MASK, spoken = CatalogSamples.AMOUNT_HIDDEN)

/** 对照页 4：Wallet 的主卡与数字格。 */
@Composable
internal fun WalletComparePage(onBack: () -> Unit) {
    var hidden by remember { mutableStateOf(false) }
    ComparePageScaffold(top = { NavBar(title = "钱包", backLabel = CatalogSamples.BACK, onBack = onBack) }) {
        WalletHeroCard(
            texts = heroTexts,
            onWithdraw = {},
            amountHidden = hidden,
            hiddenMask = CatalogSamples.HIDDEN_MASK,
            onToggleHidden = { hidden = !hidden },
            onRecords = {},
        ) {
            AmountGrid(
                tone = AmountGridTone.ON_SOLID,
                valueSize = AmountValueSize.BODY,
                mask = amountMask(hidden),
                cells =
                    listOf(
                        AmountCell("冻结中", "¥0", "冻结中，0 元", helpLabel = "冻结中说明", onHelp = {}),
                        AmountCell("已提现", "¥5", "已提现，5 元"),
                    ),
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth().couliCard().padding(CouliSpacing.space4),
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
        ) {
            AmountGrid(
                mask = amountMask(hidden),
                cells =
                    listOf(
                        AmountCell(
                            "预估收益",
                            "¥56.78",
                            "预估收益，56.78 元",
                            note = "其中已收货 ¥34.56",
                            hiddenNote = "其中已收货 " + CatalogSamples.HIDDEN_MASK,
                            emphasis = true,
                            labelIcon = CouliIcons.clock,
                            helpLabel = "预估收益说明",
                            onHelp = {},
                        ),
                        AmountCell("最早预计", "10 月结算", "最早预计 10 月结算", isAmount = false),
                    ),
            )
            InfoStrip(
                text = if (hidden) "其中 " + CatalogSamples.HIDDEN_MASK + " 暂缓入账" else "其中 ¥4.56 暂缓入账",
                spoken = if (hidden) "暂缓入账，" + CatalogSamples.AMOUNT_HIDDEN else null,
                helpLabel = "暂缓入账说明",
                onHelp = {},
            )
            Text("按联盟最新预估计算，以联盟结算金额为准", style = CouliTypography.caption, color = CouliColors.textSecondary)
        }
        EntryGrid(
            columns = 2,
            items = listOf(EntryItem(CouliIcons.orders, "订单") {}, EntryItem(CouliIcons.ledger, "余额流水") {}),
        )
    }
}

/** 对照页 5：Me 的余额卡与宫格。 */
@Composable
internal fun MeComparePage(onBack: () -> Unit) {
    var hidden by remember { mutableStateOf(false) }
    ComparePageScaffold(
        top = {
            PageTitleBar(title = "我的") {
                CouliIconButton(icon = CouliIcons.bell, label = "消息中心，有未读消息", onClick = {})
            }
        },
        bottom = { TabBar(items = tabItems, selectedIndex = 3, onSelect = { if (it == 0) onBack() }) },
    ) {
        ProfileCard()
        WalletHeroCard(
            texts = heroTexts.copy(records = null),
            onWithdraw = {},
            amountHidden = hidden,
            hiddenMask = CatalogSamples.HIDDEN_MASK,
            onToggleHidden = { hidden = !hidden },
        ) {
            AmountGrid(
                tone = AmountGridTone.ON_SOLID,
                mask = amountMask(hidden),
                cells =
                    listOf(
                        AmountCell(
                            "预估收益",
                            "¥56.78",
                            "预估收益，56.78 元",
                            note = "其中已收货 ¥34.56\n最早预计 10 月结算",
                            hiddenNote = "其中已收货 " + CatalogSamples.HIDDEN_MASK + "\n最早预计 10 月结算",
                            labelIcon = CouliIcons.clock,
                        ),
                    ),
            )
        }
        EntryGrid(
            title = "订单与钱包",
            items =
                listOf(
                    EntryItem(CouliIcons.orders, "我的订单") {},
                    EntryItem(CouliIcons.wallet, "钱包") {},
                    EntryItem(CouliIcons.ledger, "余额流水") {},
                    EntryItem(CouliIcons.claim, "找回订单") {},
                ),
        )
        EntryGrid(title = "推广与邀请", items = listOf(EntryItem(CouliIcons.invite, "邀请好友") {}))
        EntryGrid(
            title = "更多服务",
            items =
                listOf(
                    EntryItem(CouliIcons.heart, "收藏夹") {},
                    EntryItem(CouliIcons.help, "帮助中心") {},
                    EntryItem(CouliIcons.headset, "联系客服") {},
                    EntryItem(CouliIcons.link, "授权管理") {},
                    EntryItem(CouliIcons.settings, "设置") {},
                ),
        )
    }
}

@Composable
private fun ProfileCard() {
    Row(
        modifier = Modifier.fillMaxWidth().couliCard().padding(CouliSpacing.space4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
    ) {
        Box(
            Modifier
                .size(
                    CouliSpacing.space12 + CouliSpacing.space2,
                ).clip(RoundedCornerShape(CouliRadius.pill))
                .background(CouliColors.backgroundMuted),
            contentAlignment = Alignment.Center,
        ) {
            Icon(CouliIcons.user, null, tint = CouliColors.textSecondary, modifier = Modifier.size(CouliSizes.iconLarge))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
            Text("凑狸用户", style = CouliTextStyles.bodySemibold, color = CouliColors.textPrimary)
            FlowRow(itemVerticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space1)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("我的邀请码 ", style = CouliTypography.caption, color = CouliColors.textSecondary)
                    Text("K7M3QX", style = CouliTextStyles.captionSemibold, color = CouliColors.textPrimary)
                    CouliIconButton(
                        icon = CouliIcons.copy,
                        label = "复制邀请码",
                        onClick = {},
                        tint = CouliColors.textLink,
                        iconSize = CouliSizes.iconSmall,
                    )
                }
                Text("邀请人：已绑定", style = CouliTypography.caption, color = CouliColors.textSecondary)
            }
        }
    }
}

/** Product 画板头图高 264 = 4 × space16 + space2。 */
private val PRODUCT_IMAGE_HEIGHT =
    CouliSpacing.space16 + CouliSpacing.space16 + CouliSpacing.space16 + CouliSpacing.space16 + CouliSpacing.space2
