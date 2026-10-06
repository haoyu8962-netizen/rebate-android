@file:Suppress("MagicNumber") // 样例金额取自 08 细则与设计画板，逐条对照，不抽常量

package com.couli.app.catalog

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import com.couli.core.designsystem.AiLabel
import com.couli.core.designsystem.BuyButton
import com.couli.core.designsystem.BuyButtonState
import com.couli.core.designsystem.CouliButton
import com.couli.core.designsystem.CouliButtonSize
import com.couli.core.designsystem.CouliButtonVariant
import com.couli.core.designsystem.CouliColors
import com.couli.core.designsystem.CouliDialog
import com.couli.core.designsystem.CouliDialogContent
import com.couli.core.designsystem.CouliSheet
import com.couli.core.designsystem.CouliSizes
import com.couli.core.designsystem.CouliSpacing
import com.couli.core.designsystem.CouliTextStyles
import com.couli.core.designsystem.CouliToast
import com.couli.core.designsystem.CouliTypography
import com.couli.core.designsystem.DisclaimerFooter
import com.couli.core.designsystem.DisclaimerLine
import com.couli.core.designsystem.DisclaimerStyle
import com.couli.core.designsystem.EmptyState
import com.couli.core.designsystem.ErrorState
import com.couli.core.designsystem.EstNetLine
import com.couli.core.designsystem.MatchBadge
import com.couli.core.designsystem.MatchBadgeKind
import com.couli.core.designsystem.NavBar
import com.couli.core.designsystem.PlatformBadge
import com.couli.core.designsystem.PriceTag
import com.couli.core.designsystem.PriceTagModel
import com.couli.core.designsystem.PriceTagSize
import com.couli.core.designsystem.ProductCardGridLayout
import com.couli.core.designsystem.ProductCardLarge
import com.couli.core.designsystem.ProductCardList
import com.couli.core.designsystem.ProductCardVariant
import com.couli.core.designsystem.RebateTag
import com.couli.core.designsystem.RebateTagModel
import com.couli.core.designsystem.RebateTagSize
import com.couli.core.designsystem.SkeletonProductCard
import com.couli.core.designsystem.StateAction
import com.couli.core.designsystem.TabBar

/**
 * 组件目录页的固定长标记：只在 debug / staging（src/debugFeatures）里出现；
 * tools/ci/release-scan.sh 在 release 制品里查它（命中即失败），--self-test 要求 debug / staging 命中。
 */
const val COMPONENT_CATALOG_MARKER = "COULI_COMPONENT_CATALOG_DS01_6f2c9a4e81b7d350"

/** 目录页里的页面（[key] 也用于 adb 直接打开：`am start -n com.couli.app/.MainActivity --es couli.catalog <key>`）。 */
internal enum class CatalogPage(
    val key: String,
    val title: String,
) {
    INDEX("index", "Component catalog"),
    COMPONENTS("components", "Components (all states)"),
    SEARCH("Search", "Compare: Search"),
    STRESS("ProductCardStress", "Compare: ProductCardStress"),
    PRODUCT("Product", "Compare: Product"),
    WALLET("Wallet", "Compare: Wallet"),
    ME("Me", "Compare: Me"),
    SEARCH_EMPTY("SearchEmpty", "Compare: SearchEmpty"),
    ;

    companion object {
        fun fromKey(key: String?): CatalogPage? = entries.firstOrNull { it.key == key }
    }
}

/** 已注入 intent extra 的键名。 */
internal const val CATALOG_EXTRA = "couli.catalog"

/** 组件目录（debug / staging）：索引 → 全部组件状态页 / 6 张对照页。 */
@Composable
internal fun ComponentCatalog(
    initial: CatalogPage,
    onClose: () -> Unit,
) {
    var page by rememberSaveable { mutableStateOf(initial) }
    val back: () -> Unit = { if (page == CatalogPage.INDEX) onClose() else page = CatalogPage.INDEX }
    BackHandler(onBack = back)
    Box(modifier = Modifier.fillMaxSize().background(CouliColors.backgroundCanvas).testTag(COMPONENT_CATALOG_MARKER)) {
        when (page) {
            CatalogPage.INDEX -> CatalogIndex(onOpen = { page = it }, onClose = onClose)
            CatalogPage.COMPONENTS -> ComponentStatesPage(onBack = back)
            CatalogPage.SEARCH -> SearchComparePage(onBack = back)
            CatalogPage.STRESS -> StressComparePage(onBack = back)
            CatalogPage.PRODUCT -> ProductComparePage(onBack = back)
            CatalogPage.WALLET -> WalletComparePage(onBack = back)
            CatalogPage.ME -> MeComparePage(onBack = back)
            CatalogPage.SEARCH_EMPTY -> SearchEmptyComparePage(onBack = back)
        }
    }
}

@Composable
private fun CatalogIndex(
    onOpen: (CatalogPage) -> Unit,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        NavBar(title = CatalogPage.INDEX.title, backLabel = CatalogSamples.BACK, onBack = onClose)
        CatalogPage.entries.filter { it != CatalogPage.INDEX }.forEach { entry ->
            Text(
                text = entry.title,
                style = CouliTypography.body,
                color = CouliColors.textPrimary,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = CouliSizes.touchTarget)
                        .clickable(role = Role.Button) { onOpen(entry) }
                        .padding(horizontal = CouliSpacing.space4, vertical = CouliSpacing.space3),
            )
        }
    }
}

/** 全部组件的主要状态（样例数据见 [CatalogSamples]）。 */
@Composable
private fun ComponentStatesPage(onBack: () -> Unit) {
    var dialogOpen by rememberSaveable { mutableStateOf(false) }
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    ComparePageScaffold(
        top = { NavBar(title = CatalogPage.COMPONENTS.title, backLabel = CatalogSamples.BACK, onBack = onBack) },
        bottom = { TabBar(items = tabItems, selectedIndex = 1, onSelect = {}) },
    ) {
        SectionTitle("CouliButton")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
        ) {
            CouliButton(text = "提现", onClick = {})
            CouliButton(text = "取消", onClick = {}, variant = CouliButtonVariant.SECONDARY)
            CouliButton(text = "提现规则", onClick = {}, variant = CouliButtonVariant.TEXT)
            CouliButton(text = "提现", onClick = {}, loading = true, loadingDescription = CatalogSamples.LOADING)
            CouliButton(text = "提现", onClick = {}, enabled = false)
        }
        SectionTitle("BuyButton")
        val single = RebateTagModel.make("normal", 269, 269, false)
        val range = RebateTagModel.make("price_compare_risk", 135, 269, false)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
        ) {
            BuyButton(base = CatalogSamples.BUY_COUPON, rebate = single, onClick = {})
            BuyButton(base = CatalogSamples.BUY_COUPON, rebate = range, onClick = {})
            BuyButton(base = CatalogSamples.BUY_NO_REBATE, rebate = RebateTagModel.make("no_rebate", 0, 0, false), onClick = {})
            BuyButton(base = CatalogSamples.BUY_COUPON, rebate = single, onClick = {}, amountHidden = true)
            BuyButton(base = CatalogSamples.BUY_COUPON, rebate = single, onClick = {
            }, state = BuyButtonState.LOADING, loadingDescription = CatalogSamples.LOADING)
            BuyButton(base = CatalogSamples.BUY_COUPON, rebate = single, onClick = {}, state = BuyButtonState.DISABLED)
            BuyButton(
                base = CatalogSamples.BUY_COUPON,
                rebate = single,
                onClick = {},
                state = BuyButtonState.RETRY_LATER,
                retryLaterText = CatalogSamples.RETRY_LATER,
            )
        }
        SectionTitle("PriceTag")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space6),
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space3),
        ) {
            PriceTag(PriceTagModel.make("taobao", 3990, 1000, 2990, false))
            PriceTag(PriceTagModel.make("taobao", 3990, 0, 3990, false))
            PriceTag(PriceTagModel.make("pdd", 1990, 300, 1690, false))
            PriceTag(PriceTagModel.make("pdd", 1990, 0, 1990, false))
            PriceTag(PriceTagModel.make("taobao", 9999, 0, 9999, true))
            PriceTag(PriceTagModel.make("taobao", 10099999, 100000, 9999999, false), size = PriceTagSize.LARGE)
        }
        SectionTitle("RebateTag")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
        ) {
            RebateTag(single)
            RebateTag(range)
            RebateTag(RebateTagModel.make("price_compare_risk", 0, 5, false))
            RebateTag(RebateTagModel.make("price_compare_risk", 8888888, 9999999, false))
            RebateTag(RebateTagModel.make("no_rebate", 0, 0, false))
            RebateTag(RebateTagModel.make("amount_unknown", null, null, false))
            RebateTag(RebateTagModel.make("login_required", null, null, true))
            RebateTag(range, size = RebateTagSize.LARGE)
        }
        SectionTitle("EstNetLine / PlatformBadge / MatchBadge / AiLabel")
        EstNetLine(text = CatalogSamples.searchMilk.estNet)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
            verticalArrangement = Arrangement.spacedBy(CouliSpacing.space2),
        ) {
            PlatformBadge(CatalogSamples.TAOBAO)
            PlatformBadge(CatalogSamples.JD)
            PlatformBadge(CatalogSamples.PDD)
            MatchBadge("符合条件", MatchBadgeKind.MATCHED)
            MatchBadge("已放宽条件", MatchBadgeKind.RELAXED)
            MatchBadge("规格待确认", MatchBadgeKind.UNCONFIRMED)
            AiLabel("AI 生成")
        }
        SectionTitle("DisclaimerFooter")
        DisclaimerFooter(lines = listOf(DisclaimerLine(CatalogSamples.PRICE_BASIS_GENERAL), DisclaimerLine(CatalogSamples.REBATE_ESTIMATE)))
        DisclaimerFooter(
            style = DisclaimerStyle.DETAIL_PANEL,
            lines = listOf(DisclaimerLine(CatalogSamples.PRICE_BASIS), DisclaimerLine(CatalogSamples.REBATE_COMPARE)),
        )
        SectionTitle("ProductCard: PDD no coupon / amount unknown / login / AI")
        ProductCardList(data = CatalogSamples.pddNoCoupon, onBuy = {})
        ProductCardList(data = CatalogSamples.amountUnknown, onBuy = {})
        SectionTitle("ProductCard: amount unknown (grid / large, no price)")
        ProductCardGridLayout(items = listOf(CatalogSamples.amountUnknown, CatalogSamples.pddNoCoupon), onInfo = {})
        ProductCardLarge(data = CatalogSamples.amountUnknown)
        ProductCardList(data = CatalogSamples.loginToView, onBuy = {})
        ProductCardList(data = CatalogSamples.searchMilk, onBuy = {}, variant = ProductCardVariant.AI)
        ProductCardList(data = CatalogSamples.searchMilk, onBuy = {}, buyState = BuyButtonState.LOADING)
        SectionTitle("EmptyState / ErrorState / Skeleton / Toast")
        EmptyState(title = "还没有收藏的商品", action = StateAction("去逛逛") {})
        ErrorState(title = "页面加载失败，请重试", retry = StateAction("重试") {})
        SkeletonProductCard(description = CatalogSamples.LOADING)
        CouliToast(message = "已复制，可以打开微信粘贴给好友")
        SectionTitle("CouliDialog / CouliSheet")
        CouliDialogContent(title = "价格已变动", message = "价格已变动：¥29.9 → ¥31.9，继续购买？", confirmText = "继续购买", onConfirm = {}, dismissText = "取消")
        Row(horizontalArrangement = Arrangement.spacedBy(CouliSpacing.space2)) {
            CouliButton(
                text = "Open dialog",
                onClick = { dialogOpen = true },
                variant = CouliButtonVariant.SECONDARY,
                size = CouliButtonSize.COMPACT,
            )
            CouliButton(
                text = "Open sheet",
                onClick = { sheetOpen = true },
                variant = CouliButtonVariant.SECONDARY,
                size = CouliButtonSize.COMPACT,
            )
        }
        SectionTitle("CouliFormat (BR-TEXT-11, now = 2026-10-12 10:00)")
        CatalogSamples.timeSamples.forEach { Text(it, style = CouliTextStyles.captionAmount, color = CouliColors.textSecondary) }
        Box(Modifier.width(CouliSpacing.space1))
    }
    if (dialogOpen) {
        CouliDialog(
            title = "该商品当前暂无返利，继续购买？",
            confirmText = "继续购买",
            onConfirm = { dialogOpen = false },
            dismissText = "取消",
            onDismissRequest = { dialogOpen = false },
        )
    }
    if (sheetOpen) {
        CouliSheet(
            title = "授权淘宝账号",
            onDismissRequest = { sheetOpen = false },
            actions = { CouliButton(text = "去授权", onClick = { sheetOpen = false }, modifier = Modifier.fillMaxWidth()) },
        ) {
            Text("购买前需完成淘宝授权，用于识别你的订单", style = CouliTypography.footnote, color = CouliColors.textPrimary)
        }
    }
}
