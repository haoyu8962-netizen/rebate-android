package com.couli.feature.orders

import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallOwnership
import com.couli.core.network.LocalRejectReason
import com.couli.core.network.PageApi
import com.couli.core.network.ResultSignals
import com.couli.core.orders.OrderConditions
import com.couli.core.orders.OrderDetail
import com.couli.core.orders.OrderErrorAction
import com.couli.core.orders.OrderErrorKind
import com.couli.core.orders.OrderErrorView
import com.couli.core.orders.OrderJson
import com.couli.core.orders.OrderListData
import com.couli.core.orders.OrderQuery
import com.couli.core.orders.OrderRateGate
import com.couli.core.orders.OrderStaleCache
import com.couli.core.orders.OrderSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 列表的阶段。 */
object OrderListPhase {
    const val IDLE = "idle"
    const val LOADING = "loading"
    const val CONTENT = "content"
    const val EMPTY = "empty"
    const val ERROR = "error"
}

/**
 * 一次轻提示（[id] 每次加 1：同一句再提示也重新播报）。[view] 不为 null 时正文经 [OrderErrorAction.message] 统一生成（带 trace6，APP-08 共性
 * 第 8 条），[key] 只是兜底。
 */
data class OrderToast(
    val key: String,
    val id: Long,
    val error: Boolean = false,
    val view: OrderErrorView? = null,
)

/**
 * 已显示的订单数据的固定身份（APP-08 共性第 44 条、APP-09 第 65 条；FU-AND-0710 R1-01）：[account] 与 [epoch] 来自发出这份结果的请求（请求开始时
 * 一次性取自本页的固定归属），[version] 是结果版本（每次换一份结果加 1）。复制等回调在渲染时捕获这份身份，执行前只做比较。
 */
data class OrderListIdentity(
    val account: String?,
    val epoch: Long,
    val version: Long,
)

/** 订单列表的一次状态快照（不可变）。 */
data class OrderListSnapshot(
    val conditions: OrderConditions = OrderConditions(OrderQuery.SELF, OrderQuery.ALL, null, null, null),
    val phase: String = OrderListPhase.IDLE,
    val items: List<OrderSummary> = emptyList(),
    val earliestVisibleDate: String? = null,
    val endReached: Boolean = true,
    val loadingMore: Boolean = false,
    /** 翻页失败（保留已有结果，底部错误文案 + trace6 +【重试】）。stale 状态下翻页也显示这一项（不能翻页）。 */
    val loadMoreError: OrderErrorView? = null,
    /** 整页失败（30701 之外的错误态；网络错误或 5xxxx 带 trace6）。 */
    val error: OrderErrorView? = null,
    /** 显示的是缓存（传输失败时，TECH-20）：缓存的成功获取时刻；下拉刷新成功后清除。 */
    val staleAt: Long? = null,
    /** 下拉刷新进行中（保留已显示的结果）。 */
    val refreshing: Boolean = false,
    /** 42901 禁用中：刷新、翻页、筛选与搜索提交、卡片关闭都不可用，到期恢复。 */
    val blocked: Boolean = false,
    /** 查询轮次（条件变化或刷新加 1；翻页不变）：页面只在它变了时滚回顶部。 */
    val round: Long = 0,
    /** 当前显示的结果来自哪个请求序号（测试核对迟到响应）。 */
    val shownSeq: Long? = null,
    val toast: OrderToast? = null,
    /** 已显示的数据的固定身份（没有数据时为 null）。 */
    val identity: OrderListIdentity? = null,
) {
    val scope: String get() = conditions.scope
    val filtered: Boolean get() =
        conditions.group != OrderQuery.ALL || conditions.platform != null || conditions.month != null ||
            conditions.q != null
}

/**
 * 订单列表执行器（APP-08 §2.2、§2.4、§2.7）。请求归属与页面世代（APP-03a 第 14、25、30、34 条，APP-04 第 4、15、22 条，APP-07 第 2、12 条）：
 * - 每个请求 begin 一个序号，归属（序号仍是当前、页面没离开、会话世代没变）交给 ApiClient：写出前、重试前、恢复前都核对；
 * - 切子 Tab、改筛选、搜索、下拉刷新开新的查询轮次，旧轮次的响应（含翻页）一律丢弃；换条件、离开、会话换代时先让旧序号失效，再取消传输；
 * - 响应到达（挂起之后）复核序号、页面、会话世代，旧响应只丢弃（APP-04 第 30、60 条，APP-06b 第 3 条）。
 * 断网显示上次数据（G10）：只有传输层失败才用缓存，只用默认视图第一页；带筛选或搜索词的失败显示错误态；5xxxx 不用缓存、不伪装成空结果。
 * 42901 按 Retry-After 禁用刷新、翻页、筛选与搜索提交，执行层（这里）与页面两处都生效；禁用期是会话级共享的一份（[rateGate]，与详情、待跟单
 * 查询与关闭共用；没给时本页一份）。只在主线程使用。
 *
 * 固定归属（APP-09 共性第 65 条、APP-08 第 44 条；FU-AND-0710 R1-01）：本页的查询条件与请求属于 [owner]（账号 + 会话世代），构造时一次性取得，
 * 只在会话清理（[onSessionChanged] / [onCleanup]）与「发现身份已变、先作废旧列表」时重取；请求开始（begin）不再读当前会话来绑定。每个入口先比较
 * 当前会话与固定归属、已显示数据的身份：不一致就先作废旧列表（清结果、条件回默认，通知页面清搜索框等），再按新会话重取，不把旧数据绑到新会话。
 * 会话通知是否已处理按「最近一次处理的会话通知 / 清理」（[noticedEpoch]）判断，不看最近一次请求的世代。
 */
@Suppress("TooManyFunctions") // 页面每个入口一个函数
class OrderListController(
    private val api: PageApi,
    private val env: OrderEnv,
    private val cache: OrderStaleCache.Store?,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    rateGate: OrderRateGate? = null,
) {
    private var query = OrderQuery.initial(null)
    private var seq = 0L
    private var current = 0L

    /** 本页查询与请求的固定归属（构造时一次性取；只在会话清理、作废旧列表时重取）。 */
    private var owner = Owner(env.sessionEpoch(), env.account())

    /** 最近一次处理过的会话通知 / 清理所对应的会话世代（不随请求变化）。 */
    private var noticedEpoch = owner.epoch

    /** 结果版本（每换一份已显示的结果加 1）。 */
    private var resultVersion = 0L

    /** 发现身份已变、作废了旧列表时通知页面（清搜索框、半屏、锚点与待执行的恢复，待跟单卡）。 */
    var onInvalidated: () -> Unit = {}

    private var left = false
    private var opened = false

    /** 进程恢复等待期间已按保存的条件（或默认条件）备好查询（[prepare]）；[preparedRestored] 为备好的是保存的条件。 */
    private var prepared = false
    private var preparedRestored = false
    private val owners = mutableListOf<CallOwnership>()
    private val gate = rateGate ?: OrderRateGate(scope, clock)
    private var nextCursor: String? = null
    private var toastSerial = 0L

    private val mutableState = MutableStateFlow(OrderListSnapshot())
    val state: StateFlow<OrderListSnapshot> = mutableState.asStateFlow()
    private val s: OrderListSnapshot get() = mutableState.value

    /** 本页查询条件属于哪个账号（保存状态的归属用；与请求、已显示数据同一份固定归属）。 */
    val ownerAccount: String? get() = owner.account

    /** 本页固定归属的会话世代。 */
    val ownerEpoch: Long get() = owner.epoch

    init {
        // 页面的禁用状态跟会话级的那一份（待跟单查询、关闭、详情收到的 42901 也让本页置灰）
        scope.launch { gate.blocked.collect { b -> if (!left) update { it.copy(blocked = b) } } }
    }

    /**
     * 页面打开（订单 Tab 根第一次出现）：按路由参数 [routeScope]；[restored] 为配置重建或进程回收后保存的条件（子 Tab、分组、平台、月份、搜索词），
     * 这时不看路由参数，按保存的条件取第一页但不算新一轮。已经打开过的（切走 Tab 再回来、被详情盖住再返回）不重新加载。
     */
    fun open(
        routeScope: String?,
        restored: OrderConditions? = null,
    ) {
        if (opened || left) return
        opened = true
        // 打开前会话已换代（通知还没到）：先按新会话重置，不把保存的旧条件交给新会话
        if (sessionStale()) {
            invalidateStale()
            return
        }
        if (!prepared) {
            query = if (restored != null) OrderQuery.restore(restored) else OrderQuery.initial(routeScope)
            preparedRestored = restored != null
        }
        load(newRound = !preparedRestored)
    }

    /**
     * 进程恢复等待版本检查期间（03 §4.4）：先把要恢复的条件放进查询并显示出来，不发请求；之后用户的筛选、搜索、切子 Tab、刷新都建立在这些条件上。
     * 用户在等待期间发起了新查询即视为接管（[isOpen] 变 true），等待结束后的 [open] 不再用保存的旧条件覆盖（APP-08 共性第 21 条）。
     */
    fun prepare(
        routeScope: String?,
        restored: OrderConditions?,
    ) {
        if (opened || left) return
        prepared = true
        preparedRestored = restored != null
        query = if (restored != null) OrderQuery.restore(restored) else OrderQuery.initial(routeScope)
        update { it.copy(conditions = query.conditions()) }
    }

    /** 已开始取数（页面打开过，或恢复等待期间用户已发起新查询）。 */
    val isOpen: Boolean get() = opened

    /** 用户发起了新一轮查询：恢复等待期间也算本页已打开（等待结束后不再按保存的条件重来）。 */
    private fun takeOver() {
        opened = true
    }

    /** 路由参数 scope（Router 打开 OrderList {scope}）：与当前子 Tab 不同时切过去（等同用户切子 Tab）。 */
    fun requestScope(scope: String?) {
        val wanted = OrderQuery.normalizeScope(scope)
        // 会话已换代（通知还没到）：先作废旧列表，再按路由参数切子 Tab
        if (opened) invalidatedStale()
        if (!opened && !prepared) {
            open(wanted)
            return
        }
        if (wanted != s.scope) selectScope(wanted)
    }

    /** 切子 Tab：分组回「全部」，平台、月份、搜索词清空（BR-TEXT-02 细则）。42901 禁用中也照常切（不发请求，显示限流错误）。 */
    fun selectScope(scope: String): Boolean {
        if (left || invalidatedStale() || scope == s.scope) return false
        takeOver()
        query.setScope(scope)
        load(newRound = true)
        return true
    }

    fun selectGroup(group: String): Boolean {
        if (invalidatedStale() || blockedOrLeft() || group == s.conditions.group) return false
        takeOver()
        query.setGroup(group)
        load(newRound = true)
        return true
    }

    /** 平台筛选（null = 全部平台）。 */
    fun selectPlatform(platform: String?): Boolean {
        if (invalidatedStale() || blockedOrLeft() || platform == s.conditions.platform) return false
        takeOver()
        query.setPlatform(platform)
        load(newRound = true)
        return true
    }

    /** 月份筛选（YYYY-MM；null = 全部月份）。 */
    fun selectMonth(month: String?): Boolean {
        if (invalidatedStale() || blockedOrLeft() || month == s.conditions.month) return false
        takeOver()
        query.setMonth(month)
        load(newRound = true)
        return true
    }

    /** 搜索框提交或清空（G11：只去首尾空白，客户端不做任何匹配，结果只按服务端返回显示）。 */
    fun submitQuery(text: String?): Boolean {
        if (left || invalidatedStale() || blockedOrLeft()) return false
        takeOver()
        query.setQ(text)
        load(newRound = true)
        return true
    }

    /** 下拉刷新：新一轮查询，保留已显示的结果直到响应回来。 */
    fun refresh(): Boolean {
        if (left || invalidatedStale() || blockedOrLeft()) return false
        takeOver()
        query.apply(OrderQuery.OP_REFRESH, null)
        load(newRound = true, keepItems = s.items.isNotEmpty())
        return true
    }

    /**
     * 页面身份没建立（F-AND-28）：本页固定归属的账号为空、却是已登录（构造时账号读不出），这时停在可恢复的错误态。
     * 页面入口据此决定是否走 [rebuildIdentity]（第 1 轮评审 R1-03）。
     */
    val identityUnbound: Boolean get() = !left && owner.account == null && env.loggedIn()

    /**
     * 身份初始化失败后的重建（第 1 轮评审 R1-03；页面的【重试】、下拉刷新在渲染身份只差「账号此前读不出」时调用）：只在本页仍在、固定归属账号为空、
     * 登录世代仍是本页固定的那一代（[ownerEpoch]）、此刻账号已读得出时执行——作废在途、清空结果与条件、通知页面换渲染身份（[onInvalidated]），
     * 再按此刻的会话重取固定归属并取第一页（与 [invalidateStale] 同一出口）。账号仍读不出、登录世代已变（交给会话通知）都不做，返回 false。
     */
    fun rebuildIdentity(): Boolean {
        if (!identityUnbound || env.sessionEpoch() != owner.epoch || env.account() == null) return false
        invalidateStale()
        return true
    }

    /** 【重试】：整页失败重取第一页；翻页失败重试翻页；stale 状态下翻页的【重试】重取第一页。 */
    fun retry(): Boolean =
        when {
            left || invalidatedStale() || blockedOrLeft() -> false
            s.phase == OrderListPhase.ERROR -> load(newRound = false).let { true }
            s.staleAt != null -> refresh()
            s.loadMoreError != null -> loadMore()
            else -> false
        }

    /** 加载更多：已到底、正在加载、下拉刷新进行中（新一轮还没回来）、42901 禁用中、显示的是缓存时不做（返回 false）。 */
    @Suppress("ReturnCount")
    fun loadMore(): Boolean {
        // 迟到的翻页（滚动触发、恢复补页）先比较身份：会话已换代就作废旧列表，不经 begin() 把旧数据接到新会话（R1-01）
        if (left || invalidatedStale()) return false
        val idle = !s.loadingMore && !s.endReached && s.staleAt == null && !s.refreshing
        if (blockedOrLeft() || !idle || s.phase != OrderListPhase.CONTENT) return false
        val cursor = nextCursor ?: return false
        query.nextPage(cursor)
        if (!query.sendsRequest()) return false
        val request = begin()
        val call = query.queryString()
        update { it.copy(loadingMore = true, loadMoreError = null) }
        scope.launch {
            val result = send(request, call)
            if (!accept(request)) return@launch
            owners.clear()
            nextPageDone(request, result)
        }
        return true
    }

    /** 翻页结果：成功按 order_id 追加；禁用期内被网络层结束的显示限流；作废的只丢弃；其余保留已有结果，底部错误 + trace6 +【重试】。 */
    @Suppress("ReturnCount") // 成功、本地结束两个提前出口
    private fun nextPageDone(
        request: Request,
        result: ApiResult,
    ) {
        val data = (result as? ApiResult.Success)?.let { OrderJson.listData(it.data) }
        if (data != null) {
            nextCursor = data.nextCursor
            update {
                it.copy(
                    items = merge(it.items, data.items),
                    endReached = data.nextCursor == null,
                    earliestVisibleDate = data.earliestVisibleDate ?: it.earliestVisibleDate,
                    loadingMore = false,
                    shownSeq = request.seq,
                )
            }
            return
        }
        if (rateLimitedInFlight(result) || ignorable(result)) {
            // 禁用期内被网络层结束的：底部显示限流与【重试】（随禁用期置灰）；作废的只丢弃
            val held = blockedError().copy(keepItems = true).takeIf { rateLimitedInFlight(result) }
            update { it.copy(loadingMore = false, loadMoreError = held ?: it.loadMoreError) }
            return
        }
        val view = errorView(OrderErrorAction.LIST_NEXT, result, hasCache = false)
        update { it.copy(loadingMore = false, loadMoreError = view) }
        react(view)
    }

    /** 翻页结果按 order_id 去重追加（服务端游标稳定，这里只是防御）。 */
    private fun merge(
        old: List<OrderSummary>,
        page: List<OrderSummary>,
    ): List<OrderSummary> {
        val seen = old.map { it.orderId }.toHashSet()
        return old + page.filter { seen.add(it.orderId) }
    }

    /**
     * 第一页（所有第一页请求的统一发送入口；42901 禁用期间一律不发）。[newRound] 为 false 只在重试与恢复时用；[keepItems] 只给下拉刷新：
     * 响应回来之前保留已显示的结果。
     */
    @Suppress("ReturnCount") // 离开、游客、限流三个提前出口
    private fun load(
        newRound: Boolean,
        keepItems: Boolean = false,
    ) {
        val conditions = query.conditions()
        // 下拉刷新失败、保留旧列表时要恢复旧轮次的游标（不能留下无法继续翻页的列表，APP-08 共性第 7 条）
        val previousCursor = nextCursor
        nextCursor = null
        if (left) return
        if (!env.loggedIn()) {
            // 订单页 auth=login：游客不发请求（Router 守卫负责去登录）；旧轮次照样作废
            begin()
            update { it.reset(conditions).copy(phase = OrderListPhase.IDLE, round = nextRound(it, newRound)) }
            return
        }
        if (owner.account == null) {
            // 页面身份没建立（F-AND-28，APP-09 共性第 65 条）：已登录却读不出账号——可恢复的初始化失败。不以 expectedAccount = null 发请求、不读写
            // 缓存、不发布订单数据；【重试】等入口的身份检查（[invalidatedStale]）发现账号已读得出时先清空、再重新取得固定归属
            begin()
            val view = errorView(OrderErrorAction.LIST_FIRST, ApiResult.LocalReject(LocalRejectReason.SESSION_UNREADABLE), hasCache = false)
            update { it.reset(conditions).copy(phase = OrderListPhase.ERROR, error = view, round = nextRound(it, newRound)) }
            return
        }
        if (gate.active) {
            begin()
            update { it.reset(conditions).copy(phase = OrderListPhase.ERROR, error = blockedError(), round = nextRound(it, newRound)) }
            return
        }
        val request = begin()
        val call = query.queryString()
        val cacheable = query.isDefaultView
        val filtered = query.filtered
        update {
            if (keepItems) {
                // 新一轮开始：旧轮次的翻页随 begin() 作废，它的「加载中」一并清掉
                it.copy(
                    conditions = conditions,
                    refreshing = true,
                    round = nextRound(it, newRound),
                    loadMoreError = null,
                    loadingMore = false,
                )
            } else {
                it.reset(conditions).copy(phase = OrderListPhase.LOADING, round = nextRound(it, newRound))
            }
        }
        scope.launch {
            val result = send(request, call)
            if (!accept(request)) return@launch
            owners.clear()
            val data = (result as? ApiResult.Success)?.let { OrderJson.listData(it.data) }
            if (data != null) {
                // 缓存归属用请求开始时的固定归属（不在结果到达时再读当前会话）
                if (cacheable) cache?.writeList(request.account, conditions.scope, data, clock())
                firstPageLoaded(request, data)
                return@launch
            }
            if (rateLimitedInFlight(result) || ignorable(result)) {
                firstPageEndedLocally(rateLimitedInFlight(result), keepItems, previousCursor)
                return@launch
            }
            firstPageFailed(request, result, cacheable, filtered, previousCursor)
        }
    }

    /**
     * 第一页没有结果就结束了：作废的只丢弃；在途期间别的订单请求触发了 42901（网络层不再重发）时，刷新保留旧列表，否则显示限流错误态（不停在
     * 加载中）。两种情况刷新都恢复旧轮次的游标。
     */
    private fun firstPageEndedLocally(
        limited: Boolean,
        keepItems: Boolean,
        previousCursor: String?,
    ) {
        if (keepItems) nextCursor = previousCursor
        update {
            if (limited && !keepItems) {
                it.copy(phase = OrderListPhase.ERROR, error = blockedError(), refreshing = false)
            } else {
                it.copy(refreshing = false)
            }
        }
    }

    /** 第一页成功：完整更新分页状态（游标、是否到底、翻页的加载中与错误），刷新成功后缓存提示条消失。 */
    private fun firstPageLoaded(
        request: Request,
        data: OrderListData,
    ) {
        nextCursor = data.nextCursor
        update {
            it.copy(
                phase = if (data.items.isEmpty()) OrderListPhase.EMPTY else OrderListPhase.CONTENT,
                items = data.items,
                earliestVisibleDate = data.earliestVisibleDate,
                endReached = data.nextCursor == null,
                error = null,
                loadMoreError = null,
                loadingMore = false,
                staleAt = null,
                refreshing = false,
                shownSeq = request.seq,
                identity = request.identity(++resultVersion),
            )
        }
    }

    private fun firstPageFailed(
        request: Request,
        result: ApiResult,
        cacheable: Boolean,
        filtered: Boolean,
        previousCursor: String?,
    ) {
        val transport = result is ApiResult.TransportFailure
        val cached = if (transport && cacheable) cache?.readList(request.account, s.scope) else null
        val view = errorView(OrderErrorAction.LIST_FIRST, result, hasCache = cached != null, filtered = filtered)
        when (view.actionRaw) {
            OrderErrorKind.SHOW_STALE -> {
                val data = cached?.data ?: return
                update {
                    it.copy(
                        phase = if (data.items.isEmpty()) OrderListPhase.EMPTY else OrderListPhase.CONTENT,
                        items = data.items,
                        earliestVisibleDate = data.earliestVisibleDate,
                        // stale 状态下不能翻页：底部显示同一网络错误与【重试】
                        endReached = data.items.isEmpty(),
                        loadMoreError = OrderErrorView(OrderErrorKind.PAGE_ERROR, OrderErrorAction.NETWORK_KEY, keepItems = true),
                        error = null,
                        staleAt = cached.fetchedAt,
                        refreshing = false,
                        shownSeq = request.seq,
                        identity = request.identity(++resultVersion),
                    )
                }
            }

            else -> {
                update {
                    if (it.refreshing && it.items.isNotEmpty()) {
                        // 下拉刷新失败：保留已显示的结果（含缓存提示）与旧轮次的游标（还能继续翻页），轻提示错误；提示正文经统一的错误消息
                        // 生成，5xxxx 带 trace6
                        nextCursor = previousCursor
                        it.copy(
                            refreshing = false,
                            loadingMore = false,
                            toast = OrderToast(view.textKey ?: OrderErrorAction.NETWORK_KEY, ++toastSerial, error = true, view = view),
                        )
                    } else {
                        it.copy(
                            phase = OrderListPhase.ERROR,
                            error = view,
                            items = emptyList(),
                            staleAt = null,
                            refreshing = false,
                            shownSeq = request.seq,
                            identity = null,
                        )
                    }
                }
                react(view)
            }
        }
    }

    /** 错误的副作用：42901 启动禁用期；10405 强制进入强更。 */
    private fun react(view: OrderErrorView) {
        when (view.actionRaw) {
            OrderErrorKind.RETRY_AFTER -> startBlock(view.disableMs ?: 0L)
            OrderErrorKind.FORCE_UPDATE -> env.forceUpdate()
            else -> Unit
        }
    }

    private fun errorView(
        target: String,
        result: ApiResult,
        hasCache: Boolean,
        filtered: Boolean = false,
    ): OrderErrorView =
        when (result) {
            is ApiResult.Failure -> {
                OrderErrorAction.of(target, result.code, false, ResultSignals.disableMillis(result), result.traceId, hasCache, filtered)
            }

            is ApiResult.TransportFailure -> {
                OrderErrorAction.of(target, null, true, 0L, null, hasCache, filtered)
            }

            // 成功外壳但 data 解析不出、本地拒绝等：通用错误态（不当成空结果）
            else -> {
                OrderErrorAction.of(target, GENERIC_SERVER_ERROR, false, 0L, (result as? ApiResult.Success)?.traceId, false, filtered)
            }
        }

    /** 作废的调用（页面换条件、会话换代、撤回同意等）本地拒绝：只丢弃，不显示错误。 */
    private fun ignorable(result: ApiResult): Boolean =
        (result is ApiResult.LocalReject && result.reason == LocalRejectReason.CALL_CANCELLED) || result is ApiResult.SessionEnded

    /**
     * 在途调用遇上会话级禁用期：网络层在自动重试 / 重放前按归属结束了它（本地拒绝，或首发已断连、不再重试而按传输失败返回）——按限流态处理，
     * 不显示网络错误、不拿缓存冒充（APP-08 共性第 13 条）。
     */
    private fun rateLimitedInFlight(result: ApiResult): Boolean =
        gate.active &&
            ((result is ApiResult.LocalReject && result.reason == LocalRejectReason.CALL_CANCELLED) || result is ApiResult.TransportFailure)

    private fun blockedError(): OrderErrorView = OrderErrorView(OrderErrorKind.RETRY_AFTER, "error.${ResultSignals.RATE_LIMITED}")

    private fun startBlock(millis: Long) {
        gate.start(millis)
    }

    private fun blockedOrLeft(): Boolean = left || gate.active

    // ---------------------------------------------------------------- 卡片操作（回调带渲染时的数据）

    /**
     * 卡内【复制】（自购单）：渲染时的 [orderId] 与 [orderNo] 必须仍是当前列表里的同一行，才经统一写入口写入 order_no 原文；分享单没有这条路径
     * （没有完整单号，也不在客户端拼、补或还原）。记 hash 失败时不写、提示「复制失败，请重试」。
     * 列表卡【复制订单号】（APP-08 共性第 41、44 条）：[round] 与 [identity] 是渲染时取出的查询轮次与已显示数据的固定身份（普通值）。执行前核对
     * 页面仍在、当前会话仍等于已显示数据的身份（会话已换代而作废通知还没到时也不写，并先作废旧列表）、身份与轮次没变、这一行仍在当前结果里且单号
     * 一致，才经统一写入口写；失效的旧按钮零写入。
     */
    @Suppress("ReturnCount", "ComplexCondition") // 页面、会话、身份、轮次、行、单号逐项拦截
    fun copy(
        orderId: String,
        orderNo: String,
        round: Long? = null,
        identity: OrderListIdentity? = null,
    ): Boolean {
        if (left || invalidatedStale()) return false
        val shown = s.identity ?: return false
        if (shown.epoch != env.sessionEpoch() || shown.account != env.account()) return false
        if (identity != null && identity != shown) return false
        if (round != null && round != s.round) return false
        val row = s.items.firstOrNull { it.orderId == orderId } ?: return false
        if (row.orderNo != orderNo || row.maskedOrderNo != null) return false
        val ok = env.copy(orderNo)
        update { it.copy(toast = OrderToast(if (ok) COPY_DONE_KEY else COPY_FAILED_KEY, ++toastSerial, error = !ok)) }
        return ok
    }

    /** 整卡点击：经 Router 打开 OrderDetail（渲染时的行仍在当前列表里才打开）。 */
    fun openDetail(orderId: String): Boolean {
        if (left || invalidatedStale() || s.items.none { it.orderId == orderId }) return false
        env.open(
            OrderRoutes.ORDER_DETAIL,
            kotlinx.serialization.json.JsonObject(
                mapOf("order_id" to kotlinx.serialization.json.JsonPrimitive(orderId)),
            ),
        )
        return true
    }

    fun consumeToast(id: Long) {
        if (s.toast?.id == id) update { it.copy(toast = null) }
    }

    /**
     * 详情请求成功（G17）：用详情响应里与 OrderSummary 同名的字段替换列表中同 order_id 的那一行；其余行、筛选、搜索词、滚动位置不动，不额外请求列表。
     * 显示的是缓存时不动（缓存只在刷新成功后更新）。
     */
    @Suppress("ReturnCount") // 离开、行不在、单号种类不同逐项
    fun patch(detail: OrderDetail) {
        // 已显示的数据不属于当前会话（换代通知还没到）：不把新会话的详情接进旧列表
        if (left || sessionStale()) return
        val fresh = detail.summary.copy(actualFen = null, clawbackFen = null)
        val index = s.items.indexOfFirst { it.orderId == fresh.orderId }
        if (index < 0) return
        val old = s.items[index]
        // 单号种类（自购 / 分享）不同的不替换：不让分享行出现完整单号
        if ((old.orderNo == null) != (fresh.orderNo == null)) return
        update {
            it.copy(
                items =
                    it.items.toMutableList().also { list ->
                        list[index] =
                            fresh.copy(actualFen = old.actualFen, clawbackFen = old.clawbackFen)
                    },
            )
        }
    }

    /**
     * 会话变了（登录、退出、换账号）：会话世代不同时作废全部在途请求、清掉旧账号的结果（不把旧账号的结果显示给新账号），条件回到默认，重新取第一页
     * （游客不发请求）。
     */
    fun onSessionChanged() = resetForSession(force = false)

    /**
     * 撤回同意、进入基本模式、切换环境（APP-08 共性第 36、38 条）：不论会话世代是否已经变化，同步作废在途请求、清掉已显示的结果与条件（含可能是
     * 完整订单号的搜索词），回到默认条件重新取（游客不发请求）。
     */
    fun onCleanup() = resetForSession(force = true)

    /**
     * 是否已处理过这一代会话的通知按 [noticedEpoch]（最近一次处理的会话通知 / 清理）判断，不看最近一次请求的世代：入口作废旧列表时重取了归属，
     * 之后到达的会话通知仍照常重置一次（R1-01）。
     */
    @Suppress("ReturnCount") // 离开、恢复等待中、同一会话三个出口
    private fun resetForSession(force: Boolean) {
        if (left) return
        val now = env.sessionEpoch()
        if (!opened) {
            // 恢复等待期间：丢掉旧账号保存的条件（含搜索词），等待结束后按默认条件打开（共性第 19、21 条）
            noticedEpoch = now
            rebind()
            if (prepared) {
                preparedRestored = false
                query = OrderQuery.initial(s.scope)
                update { it.reset(query.conditions()) }
            }
            return
        }
        if (!force && now == noticedEpoch && !sessionStale()) return
        noticedEpoch = now
        rebind()
        query = OrderQuery.initial(s.scope)
        // 上一账号的轻提示与结果一并清掉（不等新请求回来）
        update { it.reset(query.conditions()).copy(toast = null) }
        load(newRound = true)
    }

    /** 当前会话与本页的固定归属、已显示数据的身份不一致（会话已换代、作废通知还没到）。只比较，不赋值。 */
    @Suppress("ReturnCount") // 固定归属、没有数据、已显示身份逐项
    private fun sessionStale(): Boolean {
        val epoch = env.sessionEpoch()
        val account = env.account()
        if (epoch != owner.epoch || account != owner.account) return true
        val shown = s.identity ?: return false
        return shown.epoch != epoch || shown.account != account
    }

    /** 入口的身份检查：不一致就先作废旧列表并返回 true（这次操作不执行）。 */
    private fun invalidatedStale(): Boolean {
        if (left || !sessionStale()) return false
        invalidateStale()
        return true
    }

    /**
     * 作废旧列表（R1-01）：作废并取消在途请求，同步清掉已显示的结果、游标与条件（含搜索词），通知页面清搜索框等；再按此刻的会话重取固定归属并
     * 取默认条件的第一页（游客不发）。之后到达的会话通知仍会再重置一次（[noticedEpoch] 不变）。
     */
    private fun invalidateStale() {
        seq += 1
        current = seq
        cancelAll()
        nextCursor = null
        rebind()
        query = OrderQuery.initial(s.scope)
        update { it.reset(query.conditions()).copy(toast = null) }
        onInvalidated()
        if (opened) load(newRound = true)
    }

    /** 重取固定归属（只在会话清理与作废旧列表时；同一次读取取账号与世代）。 */
    private fun rebind() {
        owner = Owner(env.sessionEpoch(), env.account())
    }

    /**
     * 页面出栈（订单 Tab 根只在 ViewModel 清理时；被详情盖住、切走 Tab 不算）：作废请求并取消传输，同步结束下拉刷新与翻页的「进行中」
     * （APP-08 共性第 30 条：不让刷新状态依赖已被取消、只会被丢弃的响应）。可重复调用。
     */
    fun leave() {
        left = true
        current = -1
        cancelAll()
        if (s.refreshing || s.loadingMore) mutableState.value = s.copy(refreshing = false, loadingMore = false)
    }

    // ---------------------------------------------------------------- 请求

    /** 本页的固定归属：账号 + 会话世代。 */
    private data class Owner(
        val epoch: Long,
        val account: String?,
    )

    private class Request(
        val seq: Long,
        val epoch: Long,
        val account: String?,
    ) {
        /** 这个请求带回的结果的固定身份。 */
        fun identity(version: Long) = OrderListIdentity(account, epoch, version)
    }

    /** 新请求开始：旧序号立即失效，并取消旧请求的传输。请求只绑本页的固定归属（不读当前会话来绑定，R1-01）。 */
    private fun begin(): Request {
        seq += 1
        current = seq
        cancelAll()
        return Request(seq, owner.epoch, owner.account)
    }

    private fun accept(request: Request): Boolean = !left && current == request.seq && env.sessionEpoch() == request.epoch

    private suspend fun send(
        request: Request,
        query: String,
    ): ApiResult {
        // 禁用期是独立的发送前闸门（不进归属）：网络层每次实际写出前（含自动重试、重放）核对，禁用期内不再发包；已发出的照常收结果（共性第 13、27 条）
        val owner = CallOwnership(api.epochs(), sendAllowed = { !gate.active }) { accept(request) }
        owners += owner
        return api.send(ApiCall(LIST_OPERATION, query = query, ownership = owner))
    }

    private fun cancelAll() {
        val cancelled = owners.toList()
        owners.clear()
        cancelled.forEach(api::cancelOwned)
    }

    private fun nextRound(
        snapshot: OrderListSnapshot,
        newRound: Boolean,
    ): Long = if (newRound) snapshot.round + 1 else snapshot.round

    private fun OrderListSnapshot.reset(conditions: OrderConditions): OrderListSnapshot =
        copy(
            conditions = conditions,
            items = emptyList(),
            earliestVisibleDate = null,
            endReached = true,
            loadingMore = false,
            loadMoreError = null,
            error = null,
            staleAt = null,
            refreshing = false,
            shownSeq = null,
            identity = null,
        )

    private inline fun update(change: (OrderListSnapshot) -> OrderListSnapshot) {
        mutableState.value = change(mutableState.value)
    }

    companion object {
        const val LIST_OPERATION = "listOrders"
        const val COPY_DONE_KEY = "order_detail.copy.done"
        const val COPY_FAILED_KEY = "order_detail.copy.failed"
        private const val GENERIC_SERVER_ERROR = 50_001
    }
}
