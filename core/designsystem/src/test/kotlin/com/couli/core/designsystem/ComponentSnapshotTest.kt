package com.couli.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 主要组件的快照基准（Roborazzi，基准图在 src/test/snapshots，入库）。
 * 记录：./gradlew :core:designsystem:recordRoborazziDebug；比对：verifyRoborazziDebug（tools/ci/verify.sh）。
 * 样例金额取自 08 细则例子与设计画板；按钮动词、口径说明是样例文字。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-xxhdpi")
class ComponentSnapshotTest {
    private val single = RebateTagModel.make("normal", 269, 269, false)
    private val range = RebateTagModel.make("price_compare_risk", 135, 269, false)

    @Suppress("LongParameterList") // 样例卡：价格三字段 + 返利两端 + 返后价逐项给出
    private fun card(
        platform: String,
        priceFen: Long,
        couponFen: Long,
        basis: String,
        minFen: Long?,
        maxFen: Long?,
        estNetFen: Long?,
        buy: String,
        presale: Boolean = false,
    ): ProductCardData {
        val unknown = basis == "amount_unknown"
        val keys =
            listOfNotNull(
                when {
                    unknown -> null
                    couponFen > 0 -> DisclaimerKeys.PRICE_BASIS
                    else -> DisclaimerKeys.PRICE_BASIS_GENERAL
                },
                when (basis) {
                    "normal" -> DisclaimerKeys.REBATE_ESTIMATE
                    "price_compare_risk" -> DisclaimerKeys.REBATE_COMPARE
                    else -> null
                },
            )
        return ProductCardData(
            platformName = if (platform == "pdd") "拼多多" else "淘宝",
            title = "全脂纯牛奶 250ml × 24 盒 整箱装 早餐奶 学生奶 送礼礼盒款 生产日期新鲜",
            // 金额未知：价格字段为空，不出价格（BR-PRICE-08）
            price = if (unknown) null else PriceTagModel.make(platform, priceFen, couponFen, priceFen - couponFen, presale),
            rebate = RebateTagModel.make(basis, minFen, maxFen, basis == "login_required"),
            buyBase = buy,
            imageLabel = "商品图",
            estNet = EstNetModel.make(basis, minFen, estNetFen),
            benefitTags = if (presale) listOf(BenefitTag("预售")) else emptyList(),
            disclaimer = keys.mapNotNull { DisclaimerKeys.text(it) },
            disclaimerKeys = keys,
            infoLabel = "口径说明",
        )
    }

    private fun snap(
        name: String,
        widthDp: Int = 411,
        content: @Composable () -> Unit,
    ) {
        captureRoboImage("src/test/snapshots/$name.png") {
            CouliTheme {
                Column(
                    modifier = Modifier.width(widthDp.dp).background(CouliColors.backgroundCanvas).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) { content() }
            }
        }
    }

    @Test
    fun buttons() =
        snap("CouliButton") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CouliButton(text = "提现", onClick = {})
                CouliButton(text = "取消", onClick = {}, variant = CouliButtonVariant.SECONDARY)
                CouliButton(text = "提现规则", onClick = {}, variant = CouliButtonVariant.TEXT)
                CouliButton(text = "提现", onClick = {}, enabled = false)
                CouliButton(text = "提现", onClick = {}, variant = CouliButtonVariant.ON_SOLID)
            }
        }

    @Test
    fun buyButtons() =
        snap("BuyButton") {
            BuyButton(base = "领券购买", rebate = single, onClick = {})
            BuyButton(base = "领券购买", rebate = range, onClick = {})
            BuyButton(base = "去购买（无返利）", rebate = RebateTagModel.make("no_rebate", 0, 0, false), onClick = {})
            BuyButton(base = "领券购买", rebate = single, onClick = {}, amountHidden = true)
            BuyButton(base = "领券购买", rebate = single, onClick = {}, state = BuyButtonState.DISABLED)
            BuyButton(base = "领券购买", rebate = single, onClick = {}, state = BuyButtonState.RETRY_LATER, retryLaterText = "稍后再试")
        }

    @Test
    fun priceTags() =
        snap("PriceTag") {
            PriceTag(PriceTagModel.make("taobao", 3990, 1000, 2990, false))
            PriceTag(PriceTagModel.make("taobao", 3990, 0, 3990, false))
            PriceTag(PriceTagModel.make("pdd", 1990, 300, 1690, false))
            PriceTag(PriceTagModel.make("pdd", 1990, 0, 1990, false))
            PriceTag(PriceTagModel.make("taobao", 9999, 0, 9999, true))
            PriceTag(PriceTagModel.make("taobao", 10099999, 100000, 9999999, false), size = PriceTagSize.LARGE)
        }

    @Test
    fun rebateTags() =
        snap("RebateTag") {
            RebateTag(single)
            RebateTag(range)
            RebateTag(RebateTagModel.make("price_compare_risk", 0, 5, false))
            RebateTag(RebateTagModel.make("price_compare_risk", 8888888, 9999999, false))
            RebateTag(RebateTagModel.make("no_rebate", 0, 0, false))
            RebateTag(RebateTagModel.make("amount_unknown", null, null, false))
            RebateTag(RebateTagModel.make("login_required", null, null, true))
            RebateTag(range, size = RebateTagSize.LARGE)
            EstNetLine("预估返后 ¥27.21")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlatformBadge("淘宝")
                MatchBadge("符合条件", MatchBadgeKind.MATCHED)
                MatchBadge("规格待确认", MatchBadgeKind.UNCONFIRMED)
                AiLabel("AI 生成")
            }
        }

    @Test
    fun productCardList() =
        snap("ProductCardList") {
            ProductCardList(data = card("taobao", 2345, 1000, "price_compare_risk", 56, 112, 1289, "领券购买"), onBuy = {})
            ProductCardList(data = card("taobao", 9999, 0, "normal", 250, 250, 9749, "去购买", presale = true), onBuy = {})
            ProductCardList(data = card("pdd", 1099999, 100000, "normal", 9999, 9999, 990000, "领券购买"), onBuy = {})
            ProductCardList(data = card("taobao", 2990, 0, "no_rebate", 0, 0, null, "去购买（无返利）"), onBuy = {})
        }

    @Test
    @Config(fontScale = 2f)
    fun productCardListLargeFont() =
        snap("ProductCardList_font200") {
            ProductCardList(data = card("taobao", 1334567, 100000, "normal", 12345, 12345, 1222222, "领券购买"), onBuy = {})
            ProductCardList(data = card("taobao", 9999999, 0, "price_compare_risk", 8888888, 9999999, 1111111, "去购买"), onBuy = {})
        }

    /**
     * 最窄屏 320dp + 系统字号 200%（R1-02）：最长价格「¥99999.99」在列表搜索卡、AI 卡、网格卡、详情大卡里都完整可见，
     * 列表卡价格区降级到整卡宽度；金额是不可拆分段（区间只在破折号处断），网格两列放不下时改单列（R2-01）。
     */
    private val longest = card("taobao", 9999999, 0, "price_compare_risk", 8888888, 9999999, 1111111, "去购买", presale = true)

    @Test
    @Config(qualifiers = "w320dp-h2400dp-xxhdpi", fontScale = 2f)
    fun productCardNarrowLargeFont() =
        snap("ProductCard_w320_font200", widthDp = 320) {
            ProductCardList(data = longest, onBuy = {})
            ProductCardList(data = longest, onBuy = {}, variant = ProductCardVariant.AI)
            ProductCardList(data = card("pdd", 10099999, 100000, "normal", 12345, 12345, 9987654, "领券购买"), onBuy = {})
        }

    /** 同上（320dp + 200%）：网格（两列放不下改单列，每张卡整卡宽）与详情大卡的最长价格。 */
    @Test
    @Config(qualifiers = "w320dp-h2400dp-xxhdpi", fontScale = 2f)
    fun productCardNarrowLargeFontGridLarge() =
        snap("ProductCard_w320_font200_GridLarge", widthDp = 320) {
            ProductCardGridLayout(items = listOf(longest, card("taobao", 1334567, 100000, "normal", 12345, 12345, null, "领券购买")))
            ProductCardLarge(data = longest)
        }

    /** 411dp + 200%（模拟器宽，ProductCardStress 压力样例）：列表卡与网格的最长价格与区间（大卡见 ProductCard_w320_font200_GridLarge），金额整块不拆、不裁切（R2-01）。 */
    @Test
    @Config(qualifiers = "w411dp-h2400dp-xxhdpi", fontScale = 2f)
    fun productCardStressLargeFont() =
        snap("ProductCardStress_w411_font200") {
            ProductCardList(data = longest, onBuy = {})
            ProductCardList(data = card("pdd", 1099999, 100000, "normal", 9999, 9999, 990000, "领券购买"), onBuy = {})
            ProductCardGridLayout(
                items = listOf(longest, card("taobao", 1334567, 100000, "price_compare_risk", 12345, 24690, null, "领券购买")),
            )
        }

    /** 金额未知（BR-PRICE-08，拼多多直链）：列表 / 网格 / 大卡都不显示任何价格与返利金额，按钮「去购买」不拼后缀（R1-01）。 */
    @Test
    fun productCardAmountUnknown() =
        snap("ProductCardAmountUnknown") {
            val unknown = card("pdd", 0, 0, "amount_unknown", null, null, null, "去购买")
            ProductCardList(data = unknown, onBuy = {})
            ProductCardList(data = unknown, onBuy = {}, variant = ProductCardVariant.AI)
            ProductCardGridLayout(items = listOf(unknown, card("pdd", 1990, 0, "normal", 32, 32, null, "去购买")))
            ProductCardLarge(data = unknown)
        }

    @Test
    fun productCardGridAndLarge() =
        snap("ProductCardGridLarge") {
            ProductCardGridLayout(
                items =
                    listOf(
                        card("taobao", 1334567, 100000, "price_compare_risk", 12345, 24690, null, "领券购买"),
                        card("taobao", 9999, 0, "normal", 23456, 23456, null, "去购买", presale = true),
                    ),
            )
            ProductCardLarge(
                data =
                    card(
                        "taobao",
                        5678,
                        1000,
                        "price_compare_risk",
                        123,
                        234,
                        4555,
                        "领券购买",
                    ).copy(shopName = "乳品生活旗舰店", quotedAt = "取价 10:00"),
            )
        }

    @Test
    fun walletAndGrids() =
        snap("WalletHeroAmountGridEntryGrid") {
            WalletHeroCard(
                texts = WalletHeroTexts("可提现余额", "¥12.34", "可提现余额，12.34 元", "提现", "提现记录", "隐藏金额", "显示金额"),
                onWithdraw = {},
                onToggleHidden = {},
                onRecords = {},
            ) {
                AmountGrid(
                    tone = AmountGridTone.ON_SOLID,
                    valueSize = AmountValueSize.BODY,
                    cells = listOf(AmountCell("冻结中", "¥0", "冻结中，0 元"), AmountCell("已提现", "¥5", "已提现，5 元")),
                )
            }
            AmountGrid(
                cells =
                    listOf(
                        AmountCell("预估收益", "¥56.78", "预估收益，56.78 元", note = "其中已收货 ¥34.56", emphasis = true, labelIcon = CouliIcons.clock),
                        AmountCell("最早预计", "10 月结算", "最早预计 10 月结算"),
                    ),
            )
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
        }

    @Test
    fun feedbackAndNavigation() =
        snap("FeedbackNavigation") {
            NavBar(title = "钱包", backLabel = "返回", onBack = {})
            EmptyState(title = "没有找到符合条件的商品", message = "换个关键词，或放宽价格区间再试试")
            ErrorState(title = "页面加载失败，请重试", retry = StateAction("重试") {})
            SkeletonProductCard(description = "加载中")
            CouliToast(message = "已复制")
            CouliDialogContent(
                title = "价格已变动",
                message = "价格已变动：¥29.9 → ¥31.9，继续购买？",
                confirmText = "继续购买",
                onConfirm = {},
                dismissText = "取消",
            )
            DisclaimerFooter(lines = listOf(DisclaimerLine("券后价按单件计算，不含运费及会员价等优惠，以下单页为准"), DisclaimerLine("以结算为准")))
            TabBar(
                items =
                    listOf(
                        TabItem(CouliIcons.home, "首页"),
                        TabItem(CouliIcons.chat, "AI 助手"),
                        TabItem(CouliIcons.orders, "订单"),
                        TabItem(CouliIcons.user, "我的"),
                    ),
                selectedIndex = 0,
                onSelect = {},
            )
        }
}
