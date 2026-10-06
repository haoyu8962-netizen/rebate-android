@file:Suppress("MagicNumber") // 样例数据：金额、时间取自设计画板，逐条对照，不抽常量

package com.couli.app.catalog

import com.couli.core.designsystem.BenefitTag
import com.couli.core.designsystem.CouliFormat
import com.couli.core.designsystem.CouliTagTone
import com.couli.core.designsystem.DisclaimerKeys
import com.couli.core.designsystem.EstNetModel
import com.couli.core.designsystem.PriceTagModel
import com.couli.core.designsystem.PriceTerms
import com.couli.core.designsystem.ProductCardData
import com.couli.core.designsystem.RebateTagModel
import com.couli.generated.DefaultTexts

/**
 * 组件目录页的样例数据（只在 debug / staging）。商品、金额取自设计画板（Search、SearchEmpty、ProductCardStress、Product）
 * 与 couli-runs/design-hifi/sample-data.md；按钮动词与口径说明在契约补键前是样例文字（TODO(N-11)：btn.buy*、price_basis* 补键后改读字典）。
 */
internal object CatalogSamples {
    const val TAOBAO = "淘宝"
    const val JD = "京东"
    const val PDD = "拼多多"
    const val IMAGE = "商品图"
    const val INFO = "口径说明"
    const val BUY_COUPON = "领券购买"
    const val BUY = "去购买"
    const val BUY_NO_REBATE = "去购买（无返利）"
    const val RETRY_LATER = "稍后再试"
    const val LOADING = "加载中"
    const val BACK = "返回"
    const val HAS_COUPON = "有券"
    const val PRESALE = "预售"
    const val SPEC_PENDING = "规格待确认"

    // 口径说明文案只在 DesignSystem 的 PriceTerms 一处（TODO(N-11)），这里经 DisclaimerKeys 取
    val PRICE_BASIS: String = PriceTerms.PRICE_BASIS
    val PRICE_BASIS_GENERAL: String = PriceTerms.PRICE_BASIS_GENERAL
    val REBATE_ESTIMATE: String = PriceTerms.REBATE_ESTIMATE
    val REBATE_COMPARE: String = PriceTerms.SETTLE_BASIS
    const val SETTLE_MONTHLY = "确认收货后随联盟月度结算入账"
    const val HIDDEN_MASK = "****"
    const val AMOUNT_HIDDEN = "金额已隐藏"

    /** 规格以下单页为准：契约键 spec_min_price_note。 */
    val specNote: String get() = DefaultTexts.text("spec_min_price_note").orEmpty()

    /**
     * 组一张卡：价格三字段 + 返利 basis 与两端 + 返后价，全部经 DesignSystem 的模型格式化。
     * disclaimer_keys 按 BR-PRICE-17 与 BR-PRICE-03 细则模拟服务端下发：有券 price_basis、无券 price_basis.general，
     * normal 加 rebate_estimate、price_compare_risk 加 rebate_compare。
     * amount_unknown（拼多多直链，BR-PRICE-08）：价格字段为空，不出任何价格与价格口径，按钮「去购买」。
     */
    @Suppress("LongParameterList")
    fun card(
        platform: String,
        title: String,
        priceFen: Long,
        couponFen: Long,
        basis: String,
        minFen: Long?,
        maxFen: Long?,
        estNetFen: Long? = null,
        presale: Boolean = false,
        spec: Boolean = false,
        specPending: Boolean = false,
        extraTags: List<String> = emptyList(),
        loginRequired: Boolean = false,
    ): ProductCardData {
        val amountUnknown = basis == "amount_unknown"
        val price = if (amountUnknown) null else PriceTagModel.make(platform, priceFen, couponFen, priceFen - couponFen, presale)
        val rebate = RebateTagModel.make(basis, minFen, maxFen, loginRequired)
        val tags =
            buildList {
                if (specPending) add(BenefitTag(SPEC_PENDING, CouliTagTone.NEUTRAL, withInfoIcon = true))
                if (presale) add(BenefitTag(PRESALE))
                extraTags.forEach { add(BenefitTag(it)) }
            }
        val buy =
            when {
                rebate.kindRaw == "none" -> BUY_NO_REBATE
                amountUnknown -> BUY
                couponFen > 0 -> BUY_COUPON
                else -> BUY
            }
        val keys =
            listOfNotNull(
                when {
                    amountUnknown -> null
                    couponFen > 0 -> DisclaimerKeys.PRICE_BASIS
                    else -> DisclaimerKeys.PRICE_BASIS_GENERAL
                },
                rebateKeyFor(basis),
            )
        return ProductCardData(
            platformName = platformName(platform),
            title = title,
            price = price,
            rebate = rebate,
            buyBase = buy,
            imageLabel = IMAGE,
            estNet = EstNetModel.make(basis, minFen, estNetFen),
            benefitTags = tags,
            specNote = if (spec && !amountUnknown) specNote else null,
            disclaimer = keys.mapNotNull { DisclaimerKeys.text(it) },
            disclaimerKeys = keys,
            infoLabel = INFO,
        )
    }

    private fun rebateKeyFor(basis: String): String? =
        when (basis) {
            "normal" -> DisclaimerKeys.REBATE_ESTIMATE
            "price_compare_risk" -> DisclaimerKeys.REBATE_COMPARE
            else -> null
        }

    /** 列表底部那一句价格口径（BR-PRICE-03 细则：按各卡的 disclaimer_keys 选，混排或全无券用 price_basis.general）。 */
    fun listFooterPriceBasis(cards: List<ProductCardData>): String? =
        DisclaimerKeys.listFooterPriceKey(cards.map { it.disclaimerKeys })?.let { DisclaimerKeys.text(it) }

    private fun platformName(platform: String): String =
        when (platform) {
            "jd" -> JD
            "pdd" -> PDD
            else -> TAOBAO
        }

    // ---- Search 画板 ----
    val searchMilk =
        card(
            "taobao",
            "全脂纯牛奶 250ml × 24 盒 整箱装 早餐奶 学生奶 送礼礼盒款 生产日期新鲜",
            2345,
            1000,
            "price_compare_risk",
            56,
            112,
            1289,
        )
    val searchMilkSmall =
        card("taobao", "纯牛奶 200ml × 12 盒 多规格可选", 1234, 0, "price_compare_risk", 0, 99, 1234, spec = true)
    val searchOrganic =
        card("taobao", "有机纯牛奶 250ml × 24 盒 预售 下月发货", 9999, 0, "price_compare_risk", 250, 500, 9749, presale = true)

    // ---- SearchEmpty 画板（推荐区） ----
    val emptyTissue = card("taobao", "抽纸 3 层 120 抽 × 24 包 家用整箱装", 1734, 500, "normal", 56, 56, 1178)
    val emptyRice = card("taobao", "东北长粒香大米 10kg 新米 预售款", 8800, 0, "normal", 321, 321, 8479, presale = true)

    // ---- ProductCardStress 画板 ----
    val stressCable =
        card("jd", "快充数据线 编织线身 1.5m 双头通用 60W 快充 耐弯折", 2990, 0, "normal", 45, 45, 2945)
    val stressMilkRange =
        card(
            "taobao",
            "全脂纯牛奶 250ml × 24 盒 整箱装 早餐奶",
            2345,
            1000,
            "price_compare_risk",
            56,
            112,
            1289,
            extraTags = listOf(HAS_COUPON),
        )
    val stressNoRebate = card("jd", "抽纸 3 层 120 抽 × 24 包 家用整箱装", 2990, 0, "no_rebate", 0, 0)
    val stressCoffeeSingle =
        card(
            "taobao",
            "进口全自动意式咖啡机 家用商用两用 一体式研磨 双锅炉 豪华礼盒装",
            1334567,
            100000,
            "normal",
            12345,
            12345,
            1222222,
            extraTags = listOf(HAS_COUPON),
        )
    private const val LONG_COFFEE =
        "进口全自动意式咖啡机 家用商用两用 一体式研磨 双锅炉 恒温萃取 蒸汽打奶泡 触屏操作 自动清洗 1.8L 大容量水箱 豪华礼盒装（含磨豆机与奶缸套装）"
    val stressCoffeeRange =
        card("taobao", LONG_COFFEE, 1334567, 100000, "price_compare_risk", 12345, 24690, 1222222, presale = true, spec = false)
            .let { it.copy(benefitTags = it.benefitTags + BenefitTag(specNote)) }
    val stressBookcase =
        card(
            "taobao",
            "高端定制实木书柜 落地整墙 多层置物 胡桃木色 2.4m × 2.6m 全屋定制 免费上门测量 预售 下单后 45 天发货",
            2345678,
            0,
            "price_compare_risk",
            23456,
            46913,
            2322222,
            presale = true,
        )
    val stressLaptop =
        card(
            "jd",
            "笔记本电脑 16 英寸 2.5K 高刷屏 32GB 内存 1TB 固态 独立显卡 多种配置可选（颜色、内存、硬盘容量以下单页所选为准）",
            1234567,
            0,
            "normal",
            12345,
            12345,
            1222222,
            spec = true,
        )
    val stressFridge =
        card(
            "pdd",
            "拼团 家用大容量冰箱 十字对开门 风冷无霜 一级能效 变频 超薄嵌入式 约 500 升",
            1099999,
            100000,
            "normal",
            9999,
            9999,
            990000,
        )
    val stressMaxRange =
        card("taobao", LONG_COFFEE, 10099999, 100000, "price_compare_risk", 8888888, 9999999, 1111111, presale = true)
            .let { it.copy(benefitTags = it.benefitTags + BenefitTag(specNote)) }
    val stressExtreme =
        card(
            "taobao",
            "进口全自动意式咖啡机 家用商用两用 一体式研磨 双锅炉 恒温萃取 多种配置可选（颜色、容量以下单页所选为准） 预售 下单后 45 天发货",
            9999999,
            0,
            "price_compare_risk",
            8888888,
            9999999,
            1111111,
            presale = true,
            specPending = true,
        ).let { it.copy(benefitTags = it.benefitTags + BenefitTag(specNote)) }

    /** 压力页列表里的全部卡（列表底部口径句按它们的 disclaimer_keys 选）。 */
    val stressCards: List<ProductCardData>
        get() =
            listOf(
                stressCable,
                stressMilkRange,
                stressNoRebate,
                stressCoffeeSingle,
                stressCoffeeRange,
                stressBookcase,
                stressLaptop,
                stressFridge,
                gridCoffee,
                gridBookcase,
                stressMaxRange,
                stressExtreme,
            )

    val gridCoffee =
        card(
            "taobao",
            "进口全自动意式咖啡机 家用商用两用 一体式研磨 双锅炉 豪华礼盒装",
            1334567,
            100000,
            "price_compare_risk",
            12345,
            24690,
            extraTags = listOf(HAS_COUPON),
        )
    val gridBookcase =
        card("jd", "高端定制实木书柜 落地整墙 多层置物 胡桃木色 全屋定制 预售", 2345678, 0, "normal", 23456, 23456, presale = true)

    // ---- 其他状态 ----
    val pddNoCoupon = card("pdd", "云朵柔 抽纸 3 层 × 120 抽 24 包", 1990, 0, "normal", 32, 32, 1958)
    val amountUnknown = card("pdd", "云朵柔 抽纸 3 层 × 120 抽 24 包（直链）", 1534, 300, "amount_unknown", null, null)
    val loginToView = card("taobao", "全脂纯牛奶 250ml × 24 盒 整箱装", 2345, 1000, "login_required", null, null, loginRequired = true)

    // ---- Product 画板 ----
    val productDetail =
        card(
            "taobao",
            "全脂纯牛奶 250ml × 24 盒 整箱装 早餐奶 学生奶",
            5678,
            1000,
            "price_compare_risk",
            123,
            234,
            4555,
            extraTags = listOf(HAS_COUPON),
        ).copy(shopName = "乳品生活旗舰店", quotedAt = "取价 " + "10:00")

    // ---- 时间格式样例（sample-data.md：主场景「今天」是 2026-10-12；2026-10-12 10:00 +08:00） ----
    const val NOW_MILLIS = 1_791_770_400_000L
    val timeSamples: List<String>
        get() =
            listOf(
                CouliFormat.timeListPast(NOW_MILLIS - MINUTES_55, NOW_MILLIS),
                CouliFormat.timeListPast(NOW_MILLIS - HOURS_20, NOW_MILLIS),
                CouliFormat.timeListPast(NOW_MILLIS - DAYS_9, NOW_MILLIS),
                CouliFormat.timeListFuture(NOW_MILLIS + DAYS_9, NOW_MILLIS),
                CouliFormat.dateOnly("2027-01-02", NOW_MILLIS),
                CouliFormat.timeMinute(NOW_MILLIS),
            )

    private const val MINUTES_55 = 55L * 60 * 1000
    private const val HOURS_20 = 20L * 60 * 60 * 1000
    private const val DAYS_9 = 9L * 24 * 60 * 60 * 1000
}
