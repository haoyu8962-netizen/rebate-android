package com.couli.feature.orders

import com.couli.core.clientcore.AmountMaskStore
import com.couli.core.network.PageApi
import com.couli.core.orders.OrderDetail
import com.couli.core.orders.OrderRateGate
import com.couli.core.orders.OrderRestoreStore
import com.couli.core.orders.OrderStaleCache
import com.couli.core.orders.OrderTexts
import com.couli.core.orders.PendingClosedAttempts
import com.couli.core.orders.PendingTrackEnv
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/**
 * 订单页要的环境（App 给；都在主线程调用）。请求归属绑定 [sessionEpoch]（登录、退出、换账号都会变）；缓存按 [account] 隔离。
 */
private const val HELP_ROUTE = "Help"

interface OrderEnv {
    /** 有登录会话（订单页 auth=login，游客不发请求）。 */
    fun loggedIn(): Boolean

    /** 会话世代：请求在发起时绑定，响应回来不是同一代就丢弃。 */
    fun sessionEpoch(): Long

    /** 当前有效会话的账号（缓存按它隔离；会话读不出时为 null，不读不写缓存）。 */
    fun account(): String?

    /**
     * 一份固定的身份快照（F-X-17 / F-X-19 补充二，FU-AND-0717）：已知与否、账号、会话世代与持久化登录世代（缓存 / 恢复记录的分区）同一次取得。页面与请求的
     * 归属只用这一份，不拼接 [sessionEpoch] 与 [account] 两次读取。缺省（旧单测）按两次读取拼出、没有分区世代。
     */
    fun identity(): com.couli.core.network.SessionIdentity {
        // 先取登录世代、再读账号（与 ApiClient.sessionIdentity 的「读取前世代」同一顺序）
        val epoch = sessionEpoch()
        return com.couli.core.network
            .SessionIdentity(known = true, account = account(), loginEpoch = epoch, partition = null)
    }

    /** /v1/config.help_links（生效配置：当前 → LKG → 包内）。 */
    fun helpLinks(): JsonObject?

    /** 经 App 写剪贴板的唯一入口（APP-07 ClipboardWriter：先记自写 hash 并确认落盘，记不下就不写）；返回是否写入。 */
    fun copy(text: String): Boolean

    /** 经 Router 以 App 内来源打开（路由名都在 routes.json）。 */
    fun open(
        route: String,
        params: JsonObject,
    )

    /** 10405：强制进入已有的强更分流。 */
    fun forceUpdate()

    /** 【联系客服】（reason.action CONTACT_CS）：统一客服出口（APP-12 brief §2.9；App 接 AppMe）。没接时经 Router 打开帮助中心。 */
    fun contactService() = open(HELP_ROUTE, JsonObject(emptyMap()))

    /** 详情请求成功（G17：回到列表时只替换那一行）。 */
    fun onDetailLoaded(detail: OrderDetail) = Unit
}

/** 订单列表页的配置（App 给）。[scope] 是路由参数（routes.json OrderList：self / share，缺省 self）。 */
@Suppress("LongParameterList") // 执行器依赖、文案、端口逐项给
class OrderListConfig(
    val api: PageApi,
    val env: OrderEnv,
    val texts: OrderTexts,
    val cache: OrderStaleCache.Store?,
    val mask: AmountMaskStore?,
    val pending: PendingTrackEnv?,
    val scope: String? = null,
    val readonly: Boolean = false,
    val clock: () -> Long = System::currentTimeMillis,
    /** 列表建好后交给 App（会话变化、详情回填 G17、路由参数 scope）。 */
    val bindController: (OrderListHandle) -> Unit = {},
    /** 进程被回收后自动恢复本页：先等本轮版本检查结果（最长 5 秒，03 §4.4）再取第一页；没有给时不等。 */
    val restoreGate: (suspend () -> Unit)? = null,
    /** 订单请求的 42901 禁用期（会话级共享一份，APP-08 共性第 2 条）；没给时本页一份。 */
    val rateGate: OrderRateGate? = null,
    /** 已关闭成功的待跟单尝试（会话级共享一份，APP-08 共性第 15 条）；没给时本页一份。 */
    val closedAttempts: PendingClosedAttempts? = null,
    /**
     * 恢复记录里个人数据（搜索词、输入框、平台与月份、锚点订单、账号归属）的落盘（App 给，noBackupFilesDir 下原子写；撤回 / 基本模式 / 切环境由
     * AppModule 的两个清理器删除并确认，APP-08 共性第 36、40、45 条）。系统保存状态只存它的随机引用。没给时这些内容不保存、也不恢复。
     */
    val restoreStore: OrderRestoreStore? = null,
)

/** 订单详情页的配置（App 给）。[orderId] 是路由参数（必填）。 */
@Suppress("LongParameterList")
class OrderDetailConfig(
    val api: PageApi,
    val env: OrderEnv,
    val texts: OrderTexts,
    val cache: OrderStaleCache.Store?,
    val mask: AmountMaskStore?,
    val orderId: String,
    val onBack: () -> Unit,
    /** 页面一确定出栈（回退栈移除的同步出口）由 App 调用的作废入口；ViewModel 清理时兜底再调一次。 */
    val bindLeave: (leave: () -> Unit) -> Unit = {},
    val clock: () -> Long = System::currentTimeMillis,
    val restoreGate: (suspend () -> Unit)? = null,
    /** 订单请求的 42901 禁用期（会话级共享一份）；没给时本页一份。 */
    val rateGate: OrderRateGate? = null,
    /** 会话换代的入口交给 App（App 在会话通知里同步调用：清掉已显示的详情并按新会话重取，APP-08 共性第 1 条）。 */
    val bindSession: (onSessionChanged: () -> Unit) -> Unit = {},
    /**
     * 统一的页面恢复入口交给 App（FU-AND-0717 第 3 轮，R2-03）：App 每次身份已知的检查都把那一份快照交来（[changed] 为 App 判定会话换代）。登记了它的页面
     * 由 App 只经它通知（不再调 [bindSession] 的入口）。
     */
    val bindIdentity: (onIdentity: (identity: com.couli.core.network.SessionIdentity, changed: Boolean) -> Unit) -> Unit = { },
    /**
     * APP-10 G27：【问问 AI】（App 给；只在 Agent 可用的构建与环境里有，Release 为 null 不显示）。App 在来源页构造时固定归属与页面票据、渲染时固定 order_id，
     * 点击只把 {order_id} 交给 Agent 页（不自动发送，附在用户发的第一条上）。「为什么没返利」仍直接显示、不进 AI（BR-AI-09）。
     */
    val askAi: (() -> Unit)? = null,
    /** 【问问 AI】此刻是否绘制（App 订阅 `agent.entry.visible` / Agent 可用性，运行中变化随之更新，Android R1-04）；null 时只看 [askAi]。 */
    val askAiVisible: StateFlow<Boolean>? = null,
)

/** 页面上的路由名（routes.json；跨功能跳转只经 Router）。 */
object OrderRoutes {
    const val ORDER_DETAIL = "OrderDetail"
    const val FIND_ORDER = "FindOrder"
    const val HOME = "Home"
    const val APPEAL = "Appeal"
}

/** 测试与冒烟用的标记。 */
object OrderTags {
    const val ASK_AI = "order_detail_ask_ai"
    const val LIST_PAGE = "orders_page"
    const val DETAIL_PAGE = "order_detail_page"
    const val FIND_ENTRY = "orders_find_entry"

    /** 置顶找回整行（G-O04，claim_entry_pinned）；与 [FIND_ENTRY] 互斥。 */
    const val FIND_PINNED = "orders_find_pinned"
    const val TAB_SELF = "orders_tab_self"
    const val TAB_SHARE = "orders_tab_share"
    const val SEARCH = "orders_search"
    const val SEARCH_CLEAR = "orders_search_clear"
    const val GROUP_PREFIX = "orders_group_"
    const val FILTER_PLATFORM = "orders_filter_platform"
    const val FILTER_MONTH = "orders_filter_month"
    const val FILTERS = "orders_filters"
    const val FILTER_OPTION_PREFIX = "orders_filter_option_"
    const val MASK = "orders_mask"
    const val LIST = "orders_list"
    const val CARD_PREFIX = "orders_card_"
    const val COPY_PREFIX = "orders_copy_"
    const val ORDER_NO_PREFIX = "orders_no_"
    const val LOAD_MORE = "orders_load_more"
    const val LOAD_MORE_ERROR = "orders_load_more_error"
    const val END = "orders_end"
    const val HISTORY_HINT = "orders_history_hint"
    const val EMPTY = "orders_empty"
    const val SEARCH_EMPTY = "orders_search_empty"
    const val SEARCH_FOUND = "orders_search_found"
    const val ERROR = "orders_error"
    const val RETRY = "orders_retry"
    const val STALE = "orders_stale"
    const val TOAST = "orders_toast"
    const val SKELETON = "orders_skeleton"
    const val DETAIL_STATUS = "order_detail_status"
    const val DETAIL_AMOUNTS = "order_detail_amounts"
    const val DETAIL_REASON = "order_detail_reason"
    const val DETAIL_HELP = "order_detail_help"
    const val DETAIL_APPEAL = "order_detail_appeal"
    const val DETAIL_VIEW_PRODUCT = "order_detail_view_product"
    const val DETAIL_COPY = "order_detail_copy"
    const val DETAIL_ORDER_NO = "order_detail_no"
    const val DETAIL_TIMELINE = "order_detail_timeline"
    const val DETAIL_ACTION_PREFIX = "order_detail_action_"
    const val DETAIL_EMPTY = "order_detail_empty"
    const val DETAIL_ERROR = "order_detail_error"
    const val DETAIL_RETRY = "order_detail_retry"
    const val DETAIL_STALE = "order_detail_stale"
    const val DETAIL_STALE_RETRY = "order_detail_stale_retry"
    const val DETAIL_BACK = "order_detail_back"
    const val DETAIL_TOAST = "order_detail_toast"

    /** 未知状态卡内【刷新】（G-O08）。 */
    const val DETAIL_REFRESH = "order_detail_refresh"

    /** 状态卡标题行的 20 状态图标（装饰，O21）。 */
    const val DETAIL_STATUS_ICON = "order_detail_status_icon"

    /** 详情底部固定栏（整宽表面，只放【去提现】，G-O06）。 */
    const val DETAIL_BOTTOM_BAR = "order_detail_bottom_bar"
}
