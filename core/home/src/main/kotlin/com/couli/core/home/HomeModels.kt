package com.couli.core.home

import kotlinx.serialization.json.JsonObject

/**
 * 首页卡片的动作（G05，03 §4.4）：只认 `{route, params?}`，其余字段（url、scheme、js、script、html、deeplink……）一律忽略。
 * 点击时原样交给 Router（入口 in_app），由 Router 按生成的路由表校验名称、since 与 params schema。
 */
data class RouteTarget(
    val route: String,
    val params: JsonObject?,
) {
    /** 交给 Router 的参数（没给为空对象；不注入 spm，G06）。 */
    val routeParams: JsonObject get() = params ?: JsonObject(emptyMap())
}

/** 首页卡片类型（03 §6.3 卡片注册表；本端只编译这 8 种，未知 type 静默跳过）。 */
object HomeCardType {
    const val BANNER = "banner"
    const val ICON_GRID = "icon_grid"
    const val PRODUCT_SCROLL = "product_scroll"
    const val PRODUCT_GRID = "product_grid"
    const val ACTIVITY_ENTRY = "activity_entry"
    const val NOTICE_BAR = "notice_bar"
    const val FEED_INFINITE = "feed_infinite"
    const val POPUP = "popup"

    val ALL: Set<String> = setOf(BANNER, ICON_GRID, PRODUCT_SCROLL, PRODUCT_GRID, ACTIVITY_ENTRY, NOTICE_BAR, FEED_INFINITE, POPUP)

    /** 每种渲染器支持的最高 section version（G07：本任务都是 1）。 */
    const val MAX_VERSION = 1
}

/** `sdui_card_error.reason` 的代拟取值（G21）：只写原因，不写字段内容。 */
object HomeDropReason {
    const val MISSING_FIELD = "missing_field"
    const val BAD_TYPE = "bad_type"
    const val MISSING_VERSION = "missing_version"
    const val UNSUPPORTED_VERSION = "unsupported_version"
    const val BAD_VALUE = "bad_value"
    const val DUPLICATE_ID = "duplicate_id"
    const val BAD_ACTION = "bad_action"
}

/** 静默跳过（不记事件）的原因。 */
object HomeSkipReason {
    const val UNKNOWN_TYPE = "unknown_type"
    const val MIN_APP_VERSION = "min_app_version"
}

/** 首页图标：网络图（只收 https）或包内资源（`builtin:<名>`，只认 [HomeBuiltinIcons.NAMES]，G17）。 */
sealed interface HomeIcon {
    data class Remote(
        val url: String,
    ) : HomeIcon

    data class Builtin(
        val name: String,
    ) : HomeIcon
}

/** 包内默认用到的内置图标名（G17；代拟写法，只认这 10 个，不放行任何别的协议）。 */
object HomeBuiltinIcons {
    const val PREFIX = "builtin:"
    val NAMES: Set<String> =
        setOf(
            "platform.taobao",
            "platform.jd",
            "platform.pdd",
            "category.supermarket",
            "category.fresh",
            "category.baby",
            "category.beauty",
            "category.household",
            "category.electronics",
            "guide",
        )
}

data class BannerItem(
    val image: String,
    val action: RouteTarget,
    /** 条目级推广标识（非 null 字符串原样显示；条目没有时用卡片级，G22）。 */
    val adLabel: String?,
    val alt: String?,
)

data class IconItem(
    val icon: HomeIcon,
    val title: String,
    val action: RouteTarget,
    val badge: String?,
    /** 条目级推广标识（G22，同 [BannerItem.adLabel]）。 */
    val adLabel: String? = null,
)

data class ActivityItem(
    val image: String?,
    val title: String?,
    val subtitle: String?,
    val action: RouteTarget,
    /** 条目级推广标识（G22，同 [BannerItem.adLabel]）。 */
    val adLabel: String? = null,
)

/** 契约 NoticeItem（04 §6.2，additionalProperties: false）：时刻已在解码阶段换成绝对毫秒。 */
data class NoticeItem(
    val itemId: String,
    val contentVersion: Int,
    val text: String,
    val target: RouteTarget?,
    val closable: Boolean,
    val startMs: Long,
    val endMs: Long?,
)

/** 商品类数据源（G03、G13）：product_pool / union_material，卡片经 listPageSectionItems 自取数（03 §6.2）。 */
data class ProductSource(
    val kind: String,
)

object HomeSourceKind {
    const val STATIC = "static"
    const val API = "api"
    const val PRODUCT_POOL = "product_pool"
    const val UNION_MATERIAL = "union_material"
}

/** 解码成功的一张卡（所有可能出错的计算都在解码阶段完成，视图只做纯展示，03 §6.2 第 2 步）。 */
sealed interface HomeCard {
    val id: String
    val type: String
    val version: Int

    /** 卡片级推广标识（G22；没有为 null）。 */
    val adLabel: String?

    /** 条目数（静态来源的条目；动态来源为 0）。 */
    val itemCount: Int

    data class Banner(
        override val id: String,
        override val version: Int,
        override val adLabel: String?,
        val heightRatio: Double,
        val autoplayMs: Long,
        val items: List<BannerItem>,
    ) : HomeCard {
        override val type: String get() = HomeCardType.BANNER
        override val itemCount: Int get() = items.size
    }

    data class IconGrid(
        override val id: String,
        override val version: Int,
        override val adLabel: String?,
        val columns: Int,
        val rows: Int?,
        val items: List<IconItem>,
    ) : HomeCard {
        override val type: String get() = HomeCardType.ICON_GRID
        override val itemCount: Int get() = items.size
    }

    data class ProductScroll(
        override val id: String,
        override val version: Int,
        override val adLabel: String?,
        val title: String,
        val moreAction: RouteTarget?,
        val source: ProductSource,
    ) : HomeCard {
        override val type: String get() = HomeCardType.PRODUCT_SCROLL
        override val itemCount: Int get() = 0
    }

    data class ProductGrid(
        override val id: String,
        override val version: Int,
        override val adLabel: String?,
        val columns: Int,
        val title: String?,
        val source: ProductSource,
    ) : HomeCard {
        override val type: String get() = HomeCardType.PRODUCT_GRID
        override val itemCount: Int get() = 0
    }

    data class ActivityEntry(
        override val id: String,
        override val version: Int,
        override val adLabel: String?,
        val layout: String?,
        val items: List<ActivityItem>,
    ) : HomeCard {
        override val type: String get() = HomeCardType.ACTIVITY_ENTRY
        override val itemCount: Int get() = items.size
    }

    /** 公告条：[apiSource] = true 时条目来自 listArticles(category=notice) 第一页（G10），否则用 [items]（props.items）。 */
    data class NoticeBar(
        override val id: String,
        override val version: Int,
        override val adLabel: String?,
        val scroll: Boolean,
        val apiSource: Boolean,
        val items: List<NoticeItem>,
    ) : HomeCard {
        override val type: String get() = HomeCardType.NOTICE_BAR
        override val itemCount: Int get() = items.size
    }

    data class FeedInfinite(
        override val id: String,
        override val version: Int,
        override val adLabel: String?,
        val platforms: List<String>,
        val source: ProductSource,
    ) : HomeCard {
        override val type: String get() = HomeCardType.FEED_INFINITE
        override val itemCount: Int get() = 0
    }

    /** 弹窗（G14）：时间段换成「当天第几分钟」，含起点不含终点；起点必须早于终点（跨零点在解码阶段丢弃）。 */
    data class Popup(
        override val id: String,
        override val version: Int,
        override val adLabel: String?,
        val image: String,
        val dailyMax: Int,
        val windowStartMinute: Int,
        val windowEndMinute: Int,
        val action: RouteTarget?,
    ) : HomeCard {
        override val type: String get() = HomeCardType.POPUP
        override val itemCount: Int get() = if (action == null) 0 else 1
    }
}

/** 静默跳过的一项（未知 type、min_app_version 不满足或缺本端键；不记事件）。 */
data class HomeSkipped(
    val id: String?,
    val reason: String,
)

/** 解码失败丢弃的一项（记 sdui_card_error，G21）。 */
data class HomeDropped(
    val id: String?,
    val type: String?,
    val version: Int?,
    val reason: String,
)

/**
 * 一页解码结果：[valid] = 页面级通过（sections 是数组、version 是正整数，G07）；[kept] 是按服务端顺序保留的卡（含第一个解码成功的弹窗，G14 ③，
 * 其余弹窗静默不用）。[raw] 是原始 JSON（写 LKG 只写它）。
 */
data class HomePage(
    val valid: Boolean,
    val pageKey: String?,
    val version: Int,
    val ttlSec: Int?,
    val kept: List<HomeCard>,
    val skipped: List<HomeSkipped>,
    val dropped: List<HomeDropped>,
    val raw: JsonObject,
) {
    /** 普通卡（不含弹窗），按服务端顺序。 */
    val cards: List<HomeCard> get() = kept.filter { it !is HomeCard.Popup }

    /** 本页的弹窗（第一个解码成功的）；没有为 null。 */
    val popup: HomeCard.Popup? get() = kept.firstNotNullOfOrNull { it as? HomeCard.Popup }

    /** 「成功渲染」的前提（G16 ①）：整页通过且至少有一张卡解码成功。 */
    val renderable: Boolean get() = valid && kept.isNotEmpty()
}
