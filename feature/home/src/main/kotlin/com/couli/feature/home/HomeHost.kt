package com.couli.feature.home

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import com.couli.core.designsystem.ProductCardData
import com.couli.core.designsystem.ProductCardTexts
import com.couli.core.designsystem.ProductImageLoader
import com.couli.core.home.CardErrorDedupe
import com.couli.core.home.HomeContractPort
import com.couli.core.home.HomeDropped
import com.couli.core.home.HomeIcon
import com.couli.core.home.HomeIdentity
import com.couli.core.home.HomeLkgStore
import com.couli.core.home.HomePrefsStore
import com.couli.core.home.HomeShowing
import com.couli.core.home.RouteTarget
import com.couli.core.network.CallEpochs
import com.couli.core.network.PageApi
import com.couli.core.wallet.WalletRateGate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow

/** App 给首页控制器的能力（都在主线程调用）。 */
interface HomeHost {
    /** 此刻的身份（App 现算）：控制器只拿它与构造时固定的身份**比较**，不用它赋值或绑定。 */
    fun identity(): HomeIdentity

    /** 经 Router 以 App 内来源打开（F-HOME-09；路由名、since、params 由 Router 按生成的路由表校验）。[spm] 只作旁路上下文，不进参数（G06）。 */
    fun open(
        target: RouteTarget,
        spm: String,
    )

    /**
     * 强更分流入口。首页控制器收到 10405 时**不再**调用它（D10-618）：10405 只由网络层 Update.on10405 一处结算，
     * 页面只显示网络层给出的结论。成员保留给既有宿主实现，收口随 FU-X-10405-PAGES。
     */
    fun forceUpdate()

    /** sdui_card_error（G20、G21）：经生成的事件构造函数，四个字段；基本模式与 HomePreview 不调。 */
    fun reportCardError(
        type: String,
        version: Int?,
        pageVersion: Int,
        reason: String,
    )
}

/**
 * 首页所在位置的信号（共性第 18、20–22 条）：正式首页与每个 HomePreview 实例各有一份，控制器在自己的作用域里持续订阅（可共存，不是可被覆盖的单一回调），
 * 离开时随作用域一起注销；重建时按此刻的真实值同步。
 */
interface HomeSignals {
    /** 本实例此刻可见（首页：选中首页 Tab、首页之上没有别的页面、App 在前台；预览：本实例在栈顶、App 在前台）。由 App 在同步出口里先写好。 */
    val visible: StateFlow<Boolean>

    /** 读屏开启（banner、滚动公告停自动播放）。 */
    val screenReader: StateFlow<Boolean>

    /** 此刻的阻挡项（G14 ④：privacy_dialog、force_update、update_prompt、clipboard_prompt、sheet_open），现算（含不可观察、只能现查的来源）。 */
    fun blockers(): Set<String>

    /** 阻挡项变化（每次 +1，共性第 21 条）：控制器据此复核弹窗。 */
    val blockerChanges: StateFlow<Long>
}

/** 计时（生产是主线程 Handler；测试可推进）。返回取消函数。 */
fun interface HomeTimers {
    fun after(
        delayMs: Long,
        block: () -> Unit,
    ): () -> Unit
}

/** 首页控制器的依赖（App 组装；正式首页与 HomePreview 各自一份 [host] / [signals]）。 */
@Suppress("LongParameterList") // 各能力逐项给
class HomeDeps(
    val host: HomeHost,
    val signals: HomeSignals,
    /** 首页契约接口（getPage、listPageSectionItems）：生产经 ApiClient（[com.couli.core.home.ApiClientHomeContractPort]）；为 null 时不发、卡隐藏。 */
    val port: HomeContractPort?,
    /** listArticles（公告 api 来源）经 ApiClient（当前环境）。 */
    val api: PageApi,
    val lkg: HomeLkgStore?,
    val prefs: HomePrefsStore?,
    /** 首页三个接口共享的 42901 截止时间（共性第 4 条；执行层与页面两处都生效）。 */
    val gate: WalletRateGate,
    val images: ProductImageLoader?,
    val timers: HomeTimers,
    val clock: () -> Long,
    /** 包内 default_home.json 原文（测试可替换成坏文件）。 */
    val bundled: () -> String?,
    val platform: String,
    val appVersion: String,
    val cardTexts: ProductCardTexts,
    val purchaseStatus: (String) -> String? = { null },
    val dedupe: CardErrorDedupe = CardErrorDedupe(),
    val io: CoroutineDispatcher = Dispatchers.IO,
)

// ---------------------------------------------------------------- 渲染时捕获的票据（共性第 1、17 条：回调只比较，不在执行时取当前 owner）

/** 一次渲染：控制器编号 + 渲染世代。 */
@Immutable
data class HomeRenderTicket(
    val controllerId: Long,
    val renderGen: Long,
)

/** 卡片点击：section id + 坑位 + RouteTarget（渲染时固定）。 */
@Immutable
data class HomeTap(
    val render: HomeRenderTicket,
    val sectionId: String,
    val slot: Int,
    val target: RouteTarget,
)

/** 公告关闭：section id + item_id + content_version（设备级记录），另带渲染时的隐私 / 环境世代与是否允许落盘（基本模式、预览只在内存）。 */
@Immutable
data class HomeNoticeClose(
    val render: HomeRenderTicket,
    val sectionId: String,
    val itemId: String,
    val contentVersion: Int,
    val epochs: CallEpochs,
    val persist: Boolean,
)

/** 弹窗：渲染世代 + 弹窗编号 + section id。 */
@Immutable
data class HomePopupTicket(
    val render: HomeRenderTicket,
    val serial: Long,
    val sectionId: String,
)

/** 信息流的翻页 / 重试。 */
@Immutable
data class HomeFeedTicket(
    val render: HomeRenderTicket,
    val sectionId: String,
)

// ---------------------------------------------------------------- 界面状态（纯展示：所有计算都在控制器里完成）

@Immutable
data class HomeBannerFrame(
    val image: String,
    val adLabel: String?,
    /** 读屏：alt 或通用名（G28）。 */
    val description: String,
    val tap: HomeTap,
)

@Immutable
data class HomeIconCell(
    val icon: HomeIcon,
    val title: String,
    val badge: String?,
    val tap: HomeTap,
    /** 推广标识（G22：条目级优先，否则卡片级；非 null 原样显示并读出，R1-03）。 */
    val adLabel: String? = null,
)

@Immutable
data class HomeActivityCell(
    val image: String?,
    val title: String?,
    val subtitle: String?,
    val tap: HomeTap,
    /** 推广标识（同 [HomeIconCell.adLabel]）。 */
    val adLabel: String? = null,
)

@Immutable
data class HomeNoticeLine(
    val itemId: String,
    val text: String,
    /** 不可关闭为 null。 */
    val close: HomeNoticeClose?,
    /** target 为 null 时不可点。 */
    val tap: HomeTap?,
)

@Immutable
data class HomeProductUi(
    val key: String,
    val data: ProductCardData,
    val adLabel: String?,
    /** product_key 为空（金额未知卡）进不了详情：不可点。 */
    val tap: HomeTap?,
)

/** 一张要显示的卡（数据源为空的卡不进列表：不显示空壳与骨架残留，F-HOME-05）。 */
@Immutable
sealed interface HomeCardUi {
    val id: String

    data class Banner(
        override val id: String,
        val heightRatio: Float,
        val autoplayMs: Long,
        val frames: List<HomeBannerFrame>,
    ) : HomeCardUi

    data class IconGrid(
        override val id: String,
        val columns: Int,
        val cells: List<HomeIconCell>,
    ) : HomeCardUi

    data class ProductScroll(
        override val id: String,
        val title: String,
        val more: HomeTap?,
        val items: List<HomeProductUi>,
    ) : HomeCardUi

    data class ProductGrid(
        override val id: String,
        val title: String?,
        val columns: Int,
        val items: List<HomeProductUi>,
    ) : HomeCardUi

    data class Activity(
        override val id: String,
        val cells: List<HomeActivityCell>,
    ) : HomeCardUi

    data class Notice(
        override val id: String,
        val scroll: Boolean,
        val lines: List<HomeNoticeLine>,
        /** 卡片级推广标识（公告条目是契约 NoticeItem，没有条目级字段，R1-03）。 */
        val adLabel: String? = null,
    ) : HomeCardUi

    data class Feed(
        override val id: String,
        val items: List<HomeProductUi>,
        val failed: Boolean,
        val loading: Boolean,
        val hasMore: Boolean,
        val ticket: HomeFeedTicket,
        /** 失败文案（R2-04：实际错误码与 trace6，经 HomeErrors.message）；没失败为 null。 */
        val error: String? = null,
        /** 共享禁用期中（R2-03）：【重试】置灰，触底加载等到期。 */
        val rateLimited: Boolean = false,
        /** 禁用期到期的次数（R2-03）：变化时触底项若仍在组合中，用原渲染票据再试一次加载。 */
        val wake: Long = 0L,
    ) : HomeCardUi
}

@Immutable
data class HomePopupUi(
    val ticket: HomePopupTicket,
    val image: ImageBitmap,
    val tappable: Boolean,
    /** 卡片级推广标识（R1-03：非 null 原样显示在图片角上并读出）。 */
    val adLabel: String? = null,
)

/** 一次性的轻提示（下拉刷新失败、被限流）。 */
@Immutable
data class HomeToast(
    val id: Long,
    val message: String,
)

/** 首页界面状态。 */
@Immutable
data class HomeUiState(
    val render: HomeRenderTicket,
    val showing: String = HomeShowing.NONE,
    val pageVersion: Int? = null,
    val cards: List<HomeCardUi> = emptyList(),
    /** 页底口径说明（BR-PRICE-03，按各卡 disclaimer_keys，每屏底部一次；没有商品卡为空）。 */
    val disclaimers: List<String> = emptyList(),
    val refreshing: Boolean = false,
    val toast: HomeToast? = null,
    val popup: HomePopupUi? = null,
    /** 本页被丢弃的卡与原因（HomePreview 页底列出；首页不显示）。 */
    val dropped: List<HomeDropped> = emptyList(),
    /** 共享 42901 禁用期中（R2-03）：StateLoadFailed 与信息流的【重试】置灰，到期恢复。 */
    val rateLimited: Boolean = false,
    /** StateLoadFailed 的文案（R2-04：触发它的实际失败经 HomeErrors.message；没有网络失败时为通用文案）。 */
    val loadFailedMessage: String? = null,
    /**
     * 各卡数据源的局部失败（R2-04）：section id → 实际错误码与 trace_id。信息流显示在底部【重试】上方；商品池与公告按 G03 / G10、H23 隐藏卡片、不提示，
     * 只保留在这里（诊断与 HomePreview 核对用）。
     */
    val sectionErrors: Map<String, com.couli.core.home.HomeFailure> = emptyMap(),
) {
    val loadFailed: Boolean get() = showing == HomeShowing.LOAD_FAILED
}

/**
 * 首页列表（LazyColumn / LazyRow）的键（R1-02，共性第 24 条）：服务端 section id 与客户端自造的项不在同一个命名空间里。每个键是「种类:id 长度:id」，
 * 再按需接后缀；种类互不相同、id 带长度编码，任何合法的 section id（含 `feed#0`、`disclaimer`）都撞不上别的项。业务 id 本身原样保留。
 * 都是字符串（可存进 Bundle）。列表底部的附加项（HomePreview 的被丢弃列表、debug 开发入口）用 [extra]。
 */
object HomeListKeys {
    private const val CARD = "card"
    private const val FEED_ROW = "feed_row"
    private const val FEED_RETRY = "feed_retry"
    private const val FEED_MORE = "feed_more"
    private const val EXTRA = "extra"
    private const val PRODUCT = "product"
    private const val HEADER = "header"

    fun card(sectionId: String): String = encode(CARD, sectionId)

    fun feedRow(
        sectionId: String,
        row: Int,
    ): String = encode(FEED_ROW, sectionId) + "#" + row

    fun feedRetry(sectionId: String): String = encode(FEED_RETRY, sectionId)

    fun feedMore(sectionId: String): String = encode(FEED_MORE, sectionId)

    /** 列表底部的附加项（客户端自己的名字）。 */
    fun extra(name: String): String = encode(EXTRA, name)

    /** 横滑商品卡（同一行里按位置，服务端重复的商品键也不撞）。 */
    fun product(
        index: Int,
        productKey: String,
    ): String = encode(PRODUCT, productKey) + "#" + index

    /**
     * 列表头部的客户端原生项（APP-11v G-V02：剪贴板提示条、游客价值卡、粘贴卡；不属 SDUI）：独立种类，任何 section id 都撞不上（共性第 24 条）。
     */
    fun header(name: String): String = encode(HEADER, name)

    /** 页底口径说明。 */
    val DISCLAIMER: String = extra("disclaimer")

    private fun encode(
        kind: String,
        id: String,
    ): String = "$kind:${id.length}:$id"
}

/** 首页的测试标记（集成测试、截图、冒烟用）。 */
object HomeTags {
    const val LIST = "home_sdui_list"
    const val LOAD_FAILED = "home_load_failed"
    const val TOAST = "home_toast"
    const val POPUP = "home_popup"
    const val POPUP_CLOSE = "home_popup_close"
    const val POPUP_IMAGE = "home_popup_image"
    const val DISCLAIMER = "home_disclaimer"
    const val CARD_PREFIX = "home_card_"
    const val TAP_PREFIX = "home_tap_"
    const val NOTICE_CLOSE_PREFIX = "home_notice_close_"
    const val AD_LABEL = "home_ad_label"
    const val FEED_RETRY = "home_feed_retry"
    const val FEED_ERROR = "home_feed_error"
    const val FEED_MORE = "home_feed_more"
    const val MORE_PREFIX = "home_more_"
    const val BANNER_DOT = "home_banner_dot"

    /** 金刚区平台格的图形（后接 builtin 名，如 `platform.taobao`；F-X-05a 换官方图的接入点）。 */
    const val PLATFORM_CELL_PREFIX = "home_platform_cell_"

    /** 一个可点区域的标记：`home_tap_<section id>_<坑位>`。 */
    fun tap(
        sectionId: String,
        slot: Int,
    ): String = "$TAP_PREFIX${sectionId}_$slot"
}
