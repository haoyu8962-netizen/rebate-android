package com.couli.app.orders

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavBackStackEntry
import com.couli.app.routing.AppRouting
import com.couli.app.routing.RouteDestination
import com.couli.core.clientcore.ConfigStore
import com.couli.core.clientcore.PermissionTypeRaw
import com.couli.core.network.PageApi
import com.couli.core.orders.AmountMaskStore
import com.couli.core.orders.OrderDetail
import com.couli.core.orders.OrderDraftTexts
import com.couli.core.orders.OrderRateGate
import com.couli.core.orders.OrderStaleCache
import com.couli.core.orders.OrderTexts
import com.couli.core.orders.PendingClosedAttempts
import com.couli.core.orders.PendingTrackEnv
import com.couli.core.orders.PendingTrackRaw
import com.couli.core.orders.PendingTrackService
import com.couli.core.privacy.AndroidPermissionRequester
import com.couli.core.router.RouteRequest
import com.couli.core.router.RouteSource
import com.couli.core.router.SessionState
import com.couli.core.router.UpdateCheck
import com.couli.feature.orders.OrderDetailConfig
import com.couli.feature.orders.OrderDetailScreen
import com.couli.feature.orders.OrderEnv
import com.couli.feature.orders.OrderListConfig
import com.couli.feature.orders.OrderListHandle
import com.couli.feature.orders.OrderListScreen
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * App 侧的订单（APP-08）：
 * - 网络经 ApiClient（请求带页面归属）；Router 打开 OrderDetail、FindOrder、ProductDetail、Help、Withdraw、Ledger、Appeal、AuthManage；
 * - 订单号复制只经 APP-07 的统一写入口（[writer] = ClipboardWriter.write：先记自写 hash 并确认落盘，记不下就不写）；
 * - 10405（dismissPendingTrack 的 x-min-version-gate）：强制进入已有的强更分流（[forceUpdate] = AppUpdate.onServerForced）；
 * - 会话世代（[sessionEpoch]）：登录、退出、换账号时 [onSessionChanged] 作废列表、详情与待跟单卡的在途请求，清掉旧账号的结果与缓存；
 *   栈里的详情页同步清掉已显示的详情快照并按新会话重取（共性第 1 条）；
 * - 42901（[rateGate]）：订单请求的禁用期按会话共享一份，列表、翻页、详情、待跟单查询与关闭（含商品详情 / 搜索页的卡片）共用，会话换代时重置；
 * - 断网缓存（[cache]，G10）：按账号隔离，登记进撤回清理与切换环境清理（AppModule）；
 * - 待跟单卡：订单列表自购子 Tab 顶部全部显示；发起购买的页面（商品详情、搜索结果）在外跳回到前台后拉一次，只显示同平台那一张（G07，[pendingFor]）；
 * - 通知行（G22）：Android 13 以下不出；13 及以上按 NotificationManagerCompat.areNotificationsEnabled() 加 PermissionRequester 的本机记录。
 * 只在主线程使用。
 */
@Suppress("LongParameterList", "TooManyFunctions") // 各能力的来源逐项注入；两个页面的配置、回退栈同步与会话出口各一个入口
class AppOrders(
    private val api: PageApi,
    private val store: ConfigStore?,
    private val routing: AppRouting,
    private val sessionEpoch: () -> Long,
    private val account: () -> String?,
    private val writer: (String) -> Boolean,
    private val permissions: AndroidPermissionRequester?,
    private val notificationsEnabled: () -> Boolean,
    private val forceUpdate: () -> Unit,
    val cache: OrderStaleCache.Store?,
    val mask: AmountMaskStore?,
    val texts: OrderTexts = OrderDraftTexts,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 订单 Tab 根（只有一份；ViewModel 建好后交过来）。 */
    private var list: OrderListHandle? = null

    /** 回退栈条目 id → 该页的作废入口（详情页）。 */
    private val leaves = mutableMapOf<String, () -> Unit>()

    /** 回退栈条目 id → 详情页的会话换代入口（清掉已显示的详情并按新会话重取）。 */
    private val detailSessions = mutableMapOf<String, () -> Unit>()

    /** 订单请求的 42901 禁用期（会话级共享一份；会话换代时重置）。 */
    val rateGate = OrderRateGate(scope, clock)

    /** 已关闭成功的待跟单尝试（会话级一份：订单列表、商品详情、搜索页的卡片共用，共性第 15 条；会话换代时清空）。 */
    val closedAttempts = PendingClosedAttempts()

    /** 回退栈条目 id → 发起购买的页面的待跟单卡（外跳回前台后拉一次）。 */
    private val pages = mutableMapOf<String, PendingPage>()

    private var lastEpoch: Long = sessionEpoch()

    val env: OrderEnv =
        object : OrderEnv {
            override fun loggedIn(): Boolean = routing.session.value.loggedIn()

            override fun sessionEpoch(): Long = this@AppOrders.sessionEpoch()

            override fun account(): String? = this@AppOrders.account()

            override fun helpLinks(): JsonObject? =
                store
                    ?.snapshots
                    ?.value
                    ?.data
                    ?.get(HELP_LINKS) as? JsonObject

            override fun copy(text: String): Boolean = writer(text)

            override fun open(
                route: String,
                params: JsonObject,
            ) {
                routing.router.open(RouteRequest(route, params, RouteSource.IN_APP))
            }

            override fun forceUpdate() = this@AppOrders.forceUpdate()

            override fun onDetailLoaded(detail: OrderDetail) {
                list?.patch(detail)
            }
        }

    val pendingEnv: PendingTrackEnv =
        object : PendingTrackEnv {
            override fun session(): String = if (routing.session.value.loggedIn()) PendingTrackRaw.MEMBER else PendingTrackRaw.GUEST

            override fun notification(): String =
                when {
                    // Android 13 以下没有通知运行时权限（默认允许）：不出通知行（G22）
                    sdkInt < NOTIFICATION_RUNTIME_SDK -> PendingTrackRaw.SYSTEM_DEFAULT

                    notificationsEnabled() -> PendingTrackRaw.GRANTED

                    else -> PendingTrackRaw.NOT_DETERMINED
                }

            override fun requestedBefore(): Boolean = permissions?.hasLocalRecord(PermissionTypeRaw.PUSH) ?: true

            override fun sessionEpoch(): Long = this@AppOrders.sessionEpoch()

            override suspend fun requestNotification() {
                val requester = permissions ?: return
                // 先记下「点过」（这一行之后不再出现，无论结果），再经 PermissionRequester 申请（只有用户点了才弹系统框）。通知行本身就是用途
                // 说明：不再经统一入口重复弹说明浮层，直接进系统申请；状态检查、节流与有效性复核照常（共性第 4 条，BR-ID-13、AC-S1-84④）
                requester.noteTapped(PermissionTypeRaw.PUSH)
                // 「点过」同步给所有显示位置（订单列表、商品详情、搜索页的卡片）：各处立即去掉通知行（共性第 25 条）
                recomputeNotificationRows()
                val epoch = this@AppOrders.sessionEpoch()
                requester.request(
                    PermissionTypeRaw.PUSH,
                    // 有效性复核：排队期间、调系统接口前会话已换代或已退出就不弹
                    isValid = { this@AppOrders.sessionEpoch() == epoch && routing.session.value.loggedIn() },
                    purposeShown = true,
                )
            }

            override fun forceUpdate() = this@AppOrders.forceUpdate()
        }

    /** 本机通知申请记录变了：所有显示位置的待跟单卡按新状态重算通知行。 */
    private fun recomputeNotificationRows() {
        list?.pending?.recompute()
        pages.values.forEach { it.service.recompute() }
    }

    /** 订单 Tab 根的配置（[destination] 带守卫给的只读标记）。 */
    fun listConfig(destination: RouteDestination): OrderListConfig =
        OrderListConfig(
            api = api,
            env = env,
            texts = texts,
            cache = cache,
            mask = mask,
            pending = pendingEnv,
            scope = pendingScope ?: params(destination).text(SCOPE),
            readonly = destination.readonly,
            clock = clock,
            bindController = { handle ->
                list = handle
                pendingScope = null
            },
            restoreGate = ::awaitUpdateCheck,
            rateGate = rateGate,
            closedAttempts = closedAttempts,
        )

    /** OrderDetail 的配置；路由参数不全（Router 已校验，这里兜底）为 null。 */
    fun detailConfig(
        destination: RouteDestination,
        entryId: String?,
        onBack: () -> Unit,
    ): OrderDetailConfig? {
        val orderId = params(destination).text(ORDER_ID) ?: return null
        return OrderDetailConfig(
            api = api,
            env = env,
            texts = texts,
            cache = cache,
            mask = mask,
            orderId = orderId,
            onBack = onBack,
            bindLeave = { leave -> if (entryId != null) leaves[entryId] = leave },
            clock = clock,
            restoreGate = ::awaitUpdateCheck,
            rateGate = rateGate,
            bindSession = { handler -> if (entryId != null) detailSessions[entryId] = handler },
        )
    }

    /** 订单 Tab 根（测试经它走下拉刷新的同一个入口）。 */
    internal fun listHandle(): OrderListHandle? = list

    /** Router 打开 OrderList {scope}（Tab 根带参数）：切到对应子 Tab。 */
    fun requestScope(params: JsonObject) {
        val scope = params.text(SCOPE)
        val current = list
        // 订单 Tab 根还没建好（第一次打开就带 scope）：记下，建列表时按它
        if (current == null) pendingScope = scope else current.requestScope(scope)
    }

    /** 列表还没建好时 Router 给的 scope（建列表时用一次）。 */
    private var pendingScope: String? = null

    /** 回退栈变了（主线程、同一条消息里）：已登记的详情页 / 发起页不在栈里了 → 立即作废它的在途请求（APP-04 第 28、47 条，APP-06a 第 39 条）。 */
    fun followBackStack(entries: List<NavBackStackEntry>) {
        if (entries.isEmpty()) return
        followBackStackIds(entries.map { it.id }.toSet())
    }

    internal fun followBackStackIds(ids: Set<String>) {
        leaves.keys.filter { it !in ids }.forEach { id -> leaves.remove(id)?.invoke() }
        detailSessions.keys.retainAll(ids)
        pages.keys.filter { it !in ids }.forEach { id -> pages.remove(id)?.release() }
    }

    /**
     * 会话变了（登录、退出、换账号、会话失效；AppModule 接 Router 的会话状态与网络层的会话通知）：会话世代变了才处理——缓存归属不一致的全部删除，
     * 列表与各页的待跟单卡作废在途请求、清掉旧账号的结果后按新会话重取（不把旧账号的结果或缓存显示给新账号）。
     */
    fun onSessionChanged() {
        val epoch = sessionEpoch()
        if (epoch == lastEpoch) return
        lastEpoch = epoch
        cache?.reconcileOwner(account())
        // 旧会话的禁用期不带给新会话（先重置，后面的重取才发得出去）
        rateGate.reset()
        closedAttempts.clear()
        list?.onSessionChanged()
        // 栈里的详情页（含被别的页盖住的）：同步清掉旧账号的详情快照、按新会话重取（不能返回后仍看到旧账号的订单号和金额）
        detailSessions.values.toList().forEach { it() }
        pages.values.forEach { it.service.onSessionChanged() }
    }

    /** 接 Router 的会话状态（登录、退出、换账号、受限会话等每次变化；AppModule 与集成测试共用）。网络层的会话通知另由 AppModule 接。 */
    fun connectSession() {
        scope.launch {
            // 冷启动：缓存的归属与当前会话不一致（上次退出后没清掉、换了账号）就全部删掉，不读回旧数据。放在依赖注入之后的下一条主线程消息
            // 里读会话（读会话要经网络核心，不在对象构造期间做）
            yield()
            cache?.reconcileOwner(account())
            routing.session.drop(1).collect { onSessionChanged() }
        }
    }

    // ---------------------------------------------------------------- 发起购买的页面（G07）

    /** 发起购买的页面（[entryId] 为回退栈条目）的待跟单卡；页面出栈时作废。 */
    fun pendingFor(entryId: String?): PendingPage? {
        val id = entryId ?: return null
        return pages.getOrPut(
            id,
        ) { PendingPage(api, pendingEnv, clock, rateGate, closedAttempts) { env.open(FIND_ORDER, JsonObject(emptyMap())) } }
    }

    /** 一个发起页的待跟单卡：外跳执行了（[jumped]）→ 回到前台（[onStart]）后拉一次，只显示同平台那一张。 */
    class PendingPage(
        api: PageApi,
        env: PendingTrackEnv,
        clock: () -> Long,
        rateGate: OrderRateGate,
        closedAttempts: PendingClosedAttempts,
        private val findOrder: () -> Unit,
    ) {
        private val pageScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val service = PendingTrackService(api, env, pageScope, clock, rateGate, closedAttempts)
        private var awaiting: String? = null
        private var stopped = false

        /** 购买执行器报告外跳已执行（拉起平台 App、浏览器或已复制口令）。 */
        fun jumped(platform: String) {
            awaiting = platform
            stopped = false
        }

        fun onStop() {
            if (awaiting != null) stopped = true
        }

        /** 回到前台：外跳之后确实离开过（ON_STOP）才拉卡；之后的回前台按最新通知权限重算通知行。 */
        fun onStart() {
            val platform = awaiting
            if (platform != null && stopped) {
                awaiting = null
                stopped = false
                service.refresh(platform)
            } else {
                service.recompute()
            }
        }

        fun openFindOrder() = findOrder()

        fun release() {
            service.leave()
            pageScope.cancel()
        }
    }

    /** 自动恢复页面（进程被回收后重开）先等本轮版本检查结果，最长 5 秒（03 §4.4，APP-06a 第 28 条）。 */
    private suspend fun awaitUpdateCheck() {
        withTimeoutOrNull(UPDATE_WAIT_MILLIS) { routing.update.first { it != UpdateCheck.CHECKING } }
    }

    private fun params(destination: RouteDestination): JsonObject =
        runCatching { Json.parseToJsonElement(destination.paramsJson).jsonObject }.getOrNull() ?: JsonObject(emptyMap())

    companion object {
        private const val SCOPE = "scope"
        private const val ORDER_ID = "order_id"
        private const val HELP_LINKS = "help_links"
        private const val FIND_ORDER = "FindOrder"
        private const val NOTIFICATION_RUNTIME_SDK = 33
        private const val UPDATE_WAIT_MILLIS = 5_000L
        const val CACHE_DIR = "couli_orders"
        const val ORDER_CACHE_CLEANER = "order_cache"

        /** 订单缓存目录：App 私有、不参与备份（noBackupFilesDir），「清除缓存」不清（03 §4.3）。 */
        fun cacheRoot(context: Context): File = File(context.applicationContext.noBackupFilesDir, CACHE_DIR)
    }
}

private fun SessionState.loggedIn(): Boolean = this == SessionState.LOGGED_IN || this == SessionState.PHONE_BOUND

private fun JsonObject.text(key: String): String? =
    (get(key) as? JsonPrimitive)
        ?.takeIf {
            it.isString
        }?.content
        ?.takeIf(String::isNotEmpty)

/** OrderList 路由（订单 Tab 根）：真实订单列表（APP-08）。 */
@Composable
fun OrderListRoute(
    orders: AppOrders,
    destination: RouteDestination,
) {
    OrderListScreen(orders.listConfig(destination))
}

/** OrderDetail 路由：真实订单详情（APP-08）。参数不全时返回。 */
@Composable
fun OrderDetailRoute(
    orders: AppOrders,
    destination: RouteDestination,
    onBack: () -> Unit,
) {
    val entryId = (LocalLifecycleOwner.current as? NavBackStackEntry)?.id
    val config = orders.detailConfig(destination, entryId, onBack)
    if (config == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    OrderDetailScreen(config)
}

/** 发起页的生命周期交给待跟单卡：ON_STOP（外跳出去）→ ON_START（回到前台）后拉一次。 */
@Composable
fun PendingPageLifecycle(page: AppOrders.PendingPage?) {
    page ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, page) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> page.onStop()
                    Lifecycle.Event.ON_START -> page.onStart()
                    else -> Unit
                }
            }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}
