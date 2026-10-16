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
import com.couli.app.privacy.AppPrivacy
import com.couli.app.routing.AppRouting
import com.couli.app.routing.RouteDestination
import com.couli.core.clientcore.AmountMaskStore
import com.couli.core.clientcore.ConfigStore
import com.couli.core.clientcore.PermissionTypeRaw
import com.couli.core.config.EnvironmentSwitchCleanerRegistry
import com.couli.core.network.PageApi
import com.couli.core.orders.OrderDetail
import com.couli.core.orders.OrderDraftTexts
import com.couli.core.orders.OrderRateGate
import com.couli.core.orders.OrderRestoreStore
import com.couli.core.orders.OrderStaleCache
import com.couli.core.orders.OrderTexts
import com.couli.core.orders.PendingClosedAttempts
import com.couli.core.orders.PendingTrackEnv
import com.couli.core.orders.PendingTrackRaw
import com.couli.core.orders.PendingTrackService
import com.couli.core.privacy.AndroidPermissionRequester
import com.couli.core.router.PrivacyState
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
    /**
     * 页面展示用的时钟（订单付款时间的「今天 / 昨天」、缓存的「更新于」、月份选项；BR-TEXT-11）：App 接 ApiClient.serverNow（服务端 Date 校准，
     * 偏差超过 5 分钟才采用，APP-08 共性第 34 条）。42901 禁用期等计时仍用 [clock]。缺省同 [clock]。
     */
    private val displayClock: () -> Long = clock,
    /**
     * 订单页恢复记录里的个人数据（搜索词、输入框、筛选、锚点订单、账号归属；FU-AND-0710 R1-03、R2-02）：AppModule 给 noBackupFilesDir 下的
     * [OrderRestoreStore]，并把它的删除直接登记进凭据清理器与环境清理器（Application 启动的补清理、环境对账同样执行并计入成败）。[onCleanup] 不再
     * 负责落盘的清理，只清已创建页面的内存状态。没给时恢复记录不保存。
     */
    val restoreStore: OrderRestoreStore? = null,
    /**
     * 一份固定的身份快照（F-X-17 / F-X-19 补充二，FU-AND-0717；AppModule 接 ApiClient.sessionIdentity）：页面、请求的归属与会话对账只用它。
     * 缺省（旧单测）按 [sessionEpoch] 与 [account] 拼出、已知、没有分区世代。
     */
    private val identity: () -> com.couli.core.network.SessionIdentity = {
        // 先取登录世代、再读账号（与 ApiClient.sessionIdentity 的「读取前世代」同一顺序）
        val epoch = sessionEpoch()
        com.couli.core.network
            .SessionIdentity(known = true, account = account(), loginEpoch = epoch, partition = null)
    },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 统一客服出口（APP-12 brief §2.9；AppModule 接 AppMe）。没接时打开帮助中心。 */
    var customerService: (
        fromRoute: String,
    ) -> Unit = { routing.router.open(RouteRequest(HELP_ROUTE, JsonObject(emptyMap()), RouteSource.IN_APP)) }

    /** 订单 Tab 根（只有一份；ViewModel 建好后交过来）。 */
    private var list: OrderListHandle? = null

    /** 回退栈条目 id → 该页的作废入口（详情页）。 */
    private val leaves = mutableMapOf<String, () -> Unit>()

    /** Agent 页（APP-10 G27）：订单详情【问问 AI】的来源票据（回退栈条目 → 页面构造时固定的归属）；AppModule 在建好 AppAgent 后接上，没接时不显示。 */
    var agent: com.couli.app.agent.AppAgent? = null
    private val askSources = mutableMapOf<String, com.couli.core.agent.AgentContextSource>()

    /** 回退栈条目 id → 详情页的会话换代入口（清掉已显示的详情并按新会话重取）。 */
    private val detailSessions = mutableMapOf<String, () -> Unit>()

    /** 回退栈条目 id → 详情页的统一恢复入口（FU-AND-0717 第 3 轮：每次身份已知的检查都交那一份快照；登记了它的不再调 [detailSessions]）。 */
    private val detailIdentities = mutableMapOf<String, (com.couli.core.network.SessionIdentity, Boolean) -> Unit>()

    /** 订单请求的 42901 禁用期（会话级共享一份；会话换代时重置）。 */
    val rateGate = OrderRateGate(scope, clock)

    /** 已关闭成功的待跟单尝试（会话级一份：订单列表、商品详情、搜索页的卡片共用，共性第 15 条；会话换代时清空）。 */
    val closedAttempts = PendingClosedAttempts()

    /** 回退栈条目 id → 发起购买的页面的待跟单卡（外跳回前台后拉一次）。 */
    private val pages = mutableMapOf<String, PendingPage>()

    /** 会话变化对账（F-X-17 补充、F-X-19 及补充二、三）：一份快照、先完成待清理、未知只延后、已知按固定标签对账，对账没完成登记重试。 */
    private val reconciler =
        com.couli.app.SessionCacheReconciler(
            identity = { identity() },
            initialEpoch = sessionEpoch(),
            // 订单缓存与订单页恢复记录都在这里对账（第 1 轮修复 R1-05：恢复记录不依赖页面创建或读取）
            completePendingClear = { (cache?.completePendingClear() ?: true) and (restoreStore?.completePendingClear() ?: true) },
            reconcile = { id ->
                (cache?.reconcileOwner(id.account, id.partition) ?: true) and
                    (restoreStore?.reconcileOwner(id.account, id.partition) ?: true)
            },
        )

    /** 测试观察：对账没完成、等重试。 */
    internal val reconcilePending: Boolean get() = reconciler.reconcilePending

    val env: OrderEnv =
        object : OrderEnv {
            override fun loggedIn(): Boolean = routing.session.value.loggedIn()

            override fun sessionEpoch(): Long = this@AppOrders.sessionEpoch()

            override fun account(): String? = this@AppOrders.account()

            override fun identity(): com.couli.core.network.SessionIdentity = this@AppOrders.identity()

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

            override fun contactService() = customerService(ORDER_DETAIL)

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

            override suspend fun requestNotification(stillValid: () -> Boolean) {
                val requester = permissions ?: return
                // 点击时那张卡的有效性（页面、会话、隐私 / 环境世代、卡片票据，APP-08 共性第 31、42 条）：失效的旧点击不记「点过」、不申请
                val valid = { stillValid() && routing.session.value.loggedIn() }
                if (!valid()) return
                // 先记下「点过」（这一行之后不再出现，无论结果），再经 PermissionRequester 申请（只有用户点了才弹系统框）。通知行本身就是用途
                // 说明：不再经统一入口重复弹说明浮层，直接进系统申请；状态检查、节流与有效性复核照常（共性第 4 条，BR-ID-13、AC-S1-84④）
                requester.noteTapped(PermissionTypeRaw.PUSH)
                // 「点过」同步给所有显示位置（订单列表、商品详情、搜索页的卡片）：各处立即去掉通知行（共性第 25 条）
                recomputeNotificationRows()
                requester.request(
                    PermissionTypeRaw.PUSH,
                    // 有效性复核：排队期间、调系统接口前页面已离开、会话已换代或已退出、撤回同意或切了环境就不弹（点击时的有效性一路带下去：
                    // 票据里的会话世代只比较，不在这里再读当前会话来绑定，R1-01 排查）
                    isValid = valid,
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
            clock = displayClock,
            bindController = { handle ->
                list = handle
                pendingScope = null
            },
            restoreGate = ::awaitUpdateCheck,
            rateGate = rateGate,
            closedAttempts = closedAttempts,
            restoreStore = restoreStore,
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
            clock = displayClock,
            restoreGate = ::awaitUpdateCheck,
            rateGate = rateGate,
            bindSession = { handler -> if (entryId != null) detailSessions[entryId] = handler },
            bindIdentity = { handler -> if (entryId != null) detailIdentities[entryId] = handler },
            askAi = agent?.askEntry(askSources, entryId, { entryId in leaves }, JsonObject(mapOf(ORDER_ID to JsonPrimitive(orderId)))),
            // 入口开关运行中变化随之更新（R1-04、G56）
            askAiVisible = agent?.askVisibleState,
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
        askSources.keys.retainAll(ids)
        detailSessions.keys.retainAll(ids)
        detailIdentities.keys.retainAll(ids)
        pages.keys.filter { it !in ids }.forEach { id -> pages.remove(id)?.release() }
    }

    /**
     * 会话变了（登录、退出、换账号、会话失效；AppModule 接 Router 的会话状态与网络层的会话通知）：会话世代变了才处理——缓存归属不一致的全部删除，
     * 列表与各页的待跟单卡作废在途请求、清掉旧账号的结果后按新会话重取（不把旧账号的结果或缓存显示给新账号）。
     */
    fun onSessionChanged() {
        // F-X-19 补充二 / 三：判据、对账与页面替换只用这一次检查里的那份身份快照（FU-AND-0717 第 3 轮：下发给每个页面的也是它，页面不另读）
        val decision = reconciler.check()
        val known = reconciler.snapshot?.takeIf { it.known && decision != com.couli.app.SessionCacheReconciler.Decision.UNKNOWN } ?: return
        if (decision != com.couli.app.SessionCacheReconciler.Decision.CHANGED) {
            // 判据相同（含冷启动的未知 → 已知、分区第一次确认）：提前返回前，每个页面按这份已确认快照走统一的恢复入口——未知期建的页接管并补取、
            // 加载因身份中止的补取（否则恢复后一直停在加载 / 错误态，R2-02 / R2-03）
            list?.onIdentityConfirmed(known)
            detailIdentities.values.toList().forEach { it(known, false) }
            return
        }
        // 旧会话的禁用期不带给新会话（先重置，后面的重取才发得出去）
        rateGate.reset()
        closedAttempts.clear()
        // 第 1 轮修复 R1-03：把本次检查已确认的身份快照交给列表（控制器按账号 + 持久化世代比较，计数不变的同账号换代也重新绑定）
        list?.onSessionChanged(known)
        // 栈里的详情页（含被别的页盖住的）：同步清掉旧账号的详情快照、按新会话重取（不能返回后仍看到旧账号的订单号和金额）；按这份快照重新绑定
        val identified = detailIdentities.toMap()
        identified.values.forEach { it(known, true) }
        detailSessions
            .filterKeys { it !in identified }
            .values
            .toList()
            .forEach { it() }
        // 发起页的待跟单卡：清掉旧账号的卡、作废在途请求与旧会话里的外跳记录（旧会话点的购买，回来不替新会话拉卡）
        pages.values.forEach { it.reset() }
    }

    /**
     * 撤回同意、进入基本模式、切换环境（同步出口，APP-08 共性第 38、43 条）：订单列表与仍在栈里的商品详情、搜索页上已显示的待跟单卡一并清空，
     * 作废在途的拉取、关闭与通知申请，清掉旧环境 / 旧会话的外跳记录、已关闭记录与禁用期。执行器本身保留并按新环境、新会话重新绑定
     * （每次请求按当时的隐私 / 环境世代与会话世代绑定），之后再外跳回来照常出卡。不依赖会话状态通知是否到达。
     * 落盘的订单页恢复记录不在这里删：AppModule 已把 [restoreStore] 的删除登记进凭据清理器与环境清理器（R2-02），这里只清内存状态与旧回调。
     */
    fun onCleanup() {
        rateGate.reset()
        closedAttempts.clear()
        // 订单列表：搜索框与保存状态里的搜索词（可能是完整订单号）、结果、待跟单卡一并清掉（共性第 36 条，不等会话通知）
        list?.onCleanup()
        // 栈里的详情页：同步清掉已显示的详情快照（完整订单号、金额），按清理后的会话重取
        detailSessions.values.toList().forEach { it() }
        pages.values.forEach { it.reset() }
    }

    /** 接 Router 的会话状态（登录、退出、换账号、受限会话等每次变化；AppModule 与集成测试共用）。网络层的会话通知另由 AppModule 接。 */
    fun connectSession() {
        scope.launch {
            // 冷启动：缓存的归属与当前会话不一致（上次退出后没清掉、换了账号）就全部删掉，不读回旧数据。放在依赖注入之后的下一条主线程消息
            // 里读会话（读会话要经网络核心，不在对象构造期间做）
            yield()
            // F-X-19：冷启动的首次对账与之后的会话变化同一个入口——身份暂时未知只延后（不以 null 账号清缓存）、待清理照常、已知按固定标签对账
            onSessionChanged()
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
        private val env: PendingTrackEnv,
        clock: () -> Long,
        rateGate: OrderRateGate,
        closedAttempts: PendingClosedAttempts,
        private val findOrder: () -> Unit,
    ) {
        private val pageScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val service = PendingTrackService(api, env, pageScope, clock, rateGate, closedAttempts)
        private var awaiting: String? = null

        /** 外跳执行时的会话世代（外跳等待前捕获；回到前台后只比较，RC-01 同机制）。 */
        private var awaitingEpoch = 0L
        private var stopped = false

        /** 购买执行器报告外跳已执行（拉起平台 App、浏览器或已复制口令）。 */
        fun jumped(platform: String) {
            awaiting = platform
            awaitingEpoch = env.sessionEpoch()
            stopped = false
        }

        fun onStop() {
            if (awaiting != null) stopped = true
        }

        /**
         * 回到前台：外跳之后确实离开过（ON_STOP）才拉卡；之后的回前台按最新通知权限重算通知行。外跳时的会话世代已不是当前（会话存储已换号而通知
         * 还没到）：丢掉这条外跳记录、不拉卡（不替新会话按旧会话的外跳拉卡；之后到达的会话通知照常清卡，RC-01 同机制）。
         */
        fun onStart() {
            val platform = awaiting
            if (platform != null && stopped) {
                awaiting = null
                stopped = false
                if (awaitingEpoch != env.sessionEpoch()) {
                    service.recompute()
                    return
                }
                // 带外跳时捕获的世代（F-AND-30：由调用方传入固定世代）
                service.refresh(awaitingEpoch, platform)
            } else {
                service.recompute()
            }
        }

        fun openFindOrder() = findOrder()

        /** 会话换代、撤回、切环境：清卡、作废在途请求与票据，丢掉旧的外跳记录（回来不再按旧外跳拉卡）。 */
        fun reset() {
            awaiting = null
            stopped = false
            service.onSessionChanged()
        }

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
        private const val HELP_ROUTE = "Help"
        private const val ORDER_DETAIL = "OrderDetail"
        private const val NOTIFICATION_RUNTIME_SDK = 33
        private const val UPDATE_WAIT_MILLIS = 5_000L
        const val CACHE_DIR = "couli_orders"
        const val ORDER_CACHE_CLEANER = "order_cache"

        /** 订单页恢复记录（撤回清理名单，R2-02）。 */
        const val ORDER_RESTORE_CLEANER = "order_restore"
        const val RESTORE_DIR = "couli_order_restore"

        /** 订单页恢复记录的目录：App 私有、不参与备份（noBackupFilesDir）。 */
        fun restoreRoot(context: Context): File = File(context.applicationContext.noBackupFilesDir, RESTORE_DIR)

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

/**
 * 清理出口的接线（AppModule 与集成测试共用，APP-08 共性第 38、43 条）：隐私结论不再是已同意（撤回、基本模式）、切换环境时同步调
 * [AppOrders.onCleanup]，清空各显示位置的待跟单卡并作废其在途请求（不等会话状态通知）。
 */
fun AppOrders.connectCleanup(
    privacy: AppPrivacy,
    cleaners: EnvironmentSwitchCleanerRegistry?,
) {
    privacy.onConclusionChanged { if (it != PrivacyState.CONSENTED) onCleanup() }
    cleaners?.register { _, _ -> onCleanup() }
}

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
