package com.couli.app.wallet

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.toRoute
import com.couli.app.BuildConfig
import com.couli.app.routing.AppRouting
import com.couli.app.routing.RouteDestination
import com.couli.core.clientcore.AmountMaskStore
import com.couli.core.clientcore.ClientEvent
import com.couli.core.clientcore.ClientEvents
import com.couli.core.clientcore.ConfigStore
import com.couli.core.clientcore.RouteParamsDroppedRoute
import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallOwnership
import com.couli.core.router.RouteRequest
import com.couli.core.router.RouteSource
import com.couli.core.router.SessionState
import com.couli.core.wallet.FundRouteParams
import com.couli.core.wallet.StepUpTokenCache
import com.couli.core.wallet.WalletError
import com.couli.core.wallet.WalletErrors
import com.couli.core.wallet.WalletGates
import com.couli.core.wallet.WalletRateGate
import com.couli.core.wallet.WalletRoutes
import com.couli.core.wallet.WalletStaleCache
import com.couli.feature.login.MeReadLimit
import com.couli.feature.wallet.LaborAgreementScreen
import com.couli.feature.wallet.LaborBackend
import com.couli.feature.wallet.Notice
import com.couli.feature.wallet.OpenErrorBar
import com.couli.feature.wallet.PageHooks
import com.couli.feature.wallet.PayoutAccountScreen
import com.couli.feature.wallet.RealNameBackend
import com.couli.feature.wallet.RealNameScreen
import com.couli.feature.wallet.WalletApi
import com.couli.feature.wallet.WalletDeps
import com.couli.feature.wallet.WalletEnv
import com.couli.feature.wallet.WalletPageConfig
import com.couli.feature.wallet.WalletScreen
import com.couli.feature.wallet.WithdrawRecordsScreen
import com.couli.feature.wallet.WithdrawScreen
import com.couli.generated.Routes
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * App 侧的钱包（APP-09）：
 * - 网络经 ApiClient（读请求带页面归属；提交、确认、放弃走未决键管线）；Router 打开 Withdraw、WithdrawRecords、Ledger（h5）、Earnings（h5，G20）、
 *   RealName、PayoutAccount、LaborAgreement、Help、RiskNotice、BindPhone、OrderList、Login；
 * - 交易流水号复制只经 APP-07 的统一写入口（[writer] = ClipboardWriter.write）；10405 强制进入已有的强更分流（[forceUpdate]）；
 * - 会话换代（[sessionEpoch] 变化：登录、退出、换账号、会话失效）时同步：禁用期整体重置、内存里的 step-up token 清空、缓存归属不一致的删除、
 *   栈里每个钱包类页面清掉已显示的余额、金额与已填内容并按新会话重取（未决记录本身按账号保留在 PendingKeyStore）；
 * - 实名状态（守卫 realnameVerified 的来源）：会话换代后取 GET /v1/me 的 realname_status；实名成功后再取一次；
 * - 资金与实名四页带参数打开时，Router 丢弃全部参数照常打开，这里记 route_params_dropped（只有路由名与个数，AC-S1-81 ⑫）；
 * - 断网缓存（[cache]，G22）：按账号隔离，登记进撤回清理与切换环境清理（AppModule）。
 * 只在主线程使用。
 */
@Suppress("LongParameterList", "TooManyFunctions") // 各能力的来源逐项注入；每个页面一个配置入口
class AppWallet(
    private val api: WalletApi,
    private val store: ConfigStore?,
    private val routing: AppRouting,
    private val sessionEpoch: () -> Long,
    private val account: () -> String?,
    private val writer: (String) -> Boolean,
    private val forceUpdate: () -> Unit,
    val cache: WalletStaleCache.Store?,
    val mask: AmountMaskStore?,
    realname: RealNameBackend?,
    labor: LaborBackend?,
    private val stepUpPhone: (String) -> String?,
    private val track: (ClientEvent) -> Unit = {},
    private val onOverlay: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Me 读取（GET /v1/me）的全局唯一截止时间（APP-12 Android R3-01，共性第 33 条；AppModule 接 AppLogin.meReadGate）：实名查询发的也是 getMe，
     * 与登录校验、「我的」、桥 auth.getUser 共用这一份——每次实际写出前核对、收到 42901 按 Retry-After 写入，双向生效。没有接（更早任务的单测）时为 null。
     */
    private val meRead: () -> WalletRateGate? = { null },
    /**
     * 当前会话作用域（F-AND-37，FU-AND-0717；AppModule 接 ApiClient 的有效会话作用域）：钱包二次验证 token 按「账号 + 登录世代 + 作用域」写入与复用。
     * 取不到按 full（StepUpEnv 口径）。
     */
    private val sessionScope: () -> String = { com.couli.core.wallet.stepup.StepUpEnv.SCOPE_FULL },
    /** 身份快照的持久化登录世代接了来源（AppModule 接 ApiClient 时为 true；末轮窄修 R3-02）。 */
    private val partitionTracked: Boolean = false,
    /**
     * 一份固定的身份快照（F-X-17 / F-X-19 补充二，FU-AND-0717；AppModule 接 ApiClient.sessionIdentity）：页面身份、缓存分区与会话对账只用它。
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
    val gates = WalletGates(scope, clock)

    /** 共享 Me 读取截止时间（页面置灰【重试】用）。 */
    internal fun meReadGate(): WalletRateGate? = meRead()

    /** 实名查询的【重试】是否被禁用期挡住（钱包读取闸门或共享 Me 读取截止时间，R3-01）。 */
    private fun realnameBlocked(): Boolean = gates.read.active || meRead()?.active == true

    /** 统一客服出口（APP-12 brief §2.9；AppModule 接 AppMe）。没接时打开帮助中心。 */
    var customerService: (
        fromRoute: String,
    ) -> Unit = { routing.router.open(RouteRequest(HELP, JsonObject(emptyMap()), RouteSource.IN_APP)) }
    val tokens = StepUpTokenCache()
    private var navController: NavController? = null

    /** 回退栈条目 id → 该页的作废入口。 */
    private val leaves = mutableMapOf<String, () -> Unit>()

    /** 回退栈条目 id → 该页的会话换代入口。 */
    private val sessions = mutableMapOf<String, () -> Unit>()

    /** 回退栈条目 id → 该页的统一恢复入口（F-X-19 补充三；FU-AND-0717 第 3 轮：每次身份已知的检查都交那一份快照，登记了它的不再调 [sessions]）。 */
    private val identities = mutableMapOf<String, (com.couli.core.network.SessionIdentity, Boolean) -> Unit>()

    /** 会话变化对账（F-X-17 补充、F-X-19 及补充二、三；与订单同一实现）。 */
    private val reconciler =
        com.couli.app.SessionCacheReconciler(
            identity = { identity() },
            initialEpoch = sessionEpoch(),
            completePendingClear = { cache?.completePendingClear() ?: true },
            reconcile = { id -> cache?.reconcileOwner(id.account, id.partition) ?: true },
        )

    /** 测试观察：对账没完成、等重试。 */
    internal val reconcilePending: Boolean get() = reconciler.reconcilePending

    /** 实名状态：(会话世代, 已实名)；不是当前世代的按「不知道」。 */
    private var realnameState: Pair<Long, Boolean>? = null

    val env: WalletEnv =
        object : WalletEnv {
            override fun loggedIn(): Boolean = routing.session.value.loggedIn()

            override fun sessionEpoch(): Long = this@AppWallet.sessionEpoch()

            override fun account(): String? = this@AppWallet.account()

            override fun identitySnapshot(): com.couli.core.network.SessionIdentity = this@AppWallet.identity()

            override fun partitionTracked(): Boolean = this@AppWallet.partitionTracked

            override fun sessionScope(): String = this@AppWallet.sessionScope()

            override fun configData(): JsonObject? = store?.snapshots?.value?.data

            override fun appVersion(): String = BuildConfig.VERSION_NAME.substringBefore('-')

            override fun earningsSince(): String? = Routes.spec(WalletRoutes.EARNINGS)?.sinceAndroid

            override fun open(
                route: String,
                params: JsonObject,
            ) = this@AppWallet.open(route, params)

            override fun replaceTop(
                route: String,
                params: JsonObject,
                entryId: String?,
            ) {
                replaceTopAccepted(route, params, entryId)
            }

            @Suppress("ReturnCount") // 未接导航、发起页不在栈顶、出栈失败分别拒绝；实际替换后才受理
            override fun replaceTopAccepted(
                route: String,
                params: JsonObject,
                entryId: String?,
            ): Boolean {
                // 只替换发起页这一个导航实例（共性第 50 条）：它不在栈顶就不动
                val controller = navController ?: return false
                if (entryId != null && controller.currentBackStackEntry?.id != entryId) return false
                if (!controller.popBackStack()) return false
                open(route, params)
                return true
            }

            override fun copy(text: String): Boolean = writer(text)

            override fun forceUpdate() = this@AppWallet.forceUpdate()

            override fun contactService(fromRoute: String) = customerService(fromRoute)

            override fun stepUpPhone(account: String): String? = this@AppWallet.stepUpPhone(account)

            override fun realnameChanged() = refreshRealname()

            // 提现成功的共享结算（共性第 40、53、61 条）：调用方已核对并固定点击时的账号与世代，原样通知仍存在的钱包页（订阅端再按自己的会话核对）；
            // 不在这里重新读取当前会话——结算之后新会话的令牌可能已在 IO 线程保存
            override fun markWalletDirty(
                account: String,
                epoch: Long,
            ) = deps.walletDirty.notify(account, epoch)

            override fun payoutSaved(entryId: String?): Boolean = popPayoutIfFromWithdraw(entryId)

            override fun sensitiveOverlay(showing: Boolean) {
                if (showing) onOverlay()
            }
        }

    val deps: WalletDeps =
        WalletDeps(
            api = api,
            env = env,
            gates = gates,
            tokens = tokens,
            cache = cache,
            mask = mask,
            realname = realname,
            labor = labor,
            clock = clock,
            // 会话级执行作用域（共性第 40 条）：已发出的写请求与发码的共享结算不随页面作用域取消
            service = scope,
        )

    fun attach(controller: NavController) {
        navController = controller
    }

    fun detach(controller: NavController) {
        if (navController === controller) navController = null
    }

    /** 测试接线：让本会话回到「还没查过实名状态」（下一次打开要实名的资金页先查 GET /v1/me）。 */
    internal fun forgetRealname() {
        realnameState = null
    }

    /** 守卫用的实名状态（当前会话世代查清了且已实名）。 */
    fun realnameVerified(): Boolean = realnameState?.takeIf { it.first == sessionEpoch() }?.second == true

    /**
     * 经 Router 以 App 内来源打开。要实名的资金页（Withdraw、PayoutAccount、LaborAgreement）在本会话还没查过实名状态时，先取 GET /v1/me 再打开
     * （守卫按查到的状态放行或去 RealName）。
     */
    fun open(
        route: String,
        params: JsonObject,
    ) {
        val needsRealname = Routes.spec(route)?.auth == AUTH_REALNAME
        if (needsRealname && realnameState?.first != sessionEpoch() && routing.session.value.loggedIn()) {
            // 打开动作绑定发起页面条目与导航世代（APP-09 共性第 21 条）：发起页出栈、导航变了、被更新的查询取代都不再跳转；
            // 还绑定发起页的可见世代（共性第 57 条，Android R3-07）：发起页 ON_PAUSE（被覆盖、切后台）即作废这次导航许可，回来也不恢复
            val entry = navController?.currentBackStackEntry
            val origin = entry?.id
            origin?.let { openErrors.value -= it }
            val intent = OpenIntent(route, params, origin, navGeneration)
            if (entry != null) {
                val observer =
                    androidx.lifecycle.LifecycleEventObserver { _, event ->
                        if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) intent.paused = true
                    }
                entry.lifecycle.addObserver(observer)
                intent.release = { entry.lifecycle.removeObserver(observer) }
            }
            refreshRealname(intent)
            return
        }
        routing.router.open(RouteRequest(route, params, RouteSource.IN_APP))
    }

    /** 用户点的、要先查实名状态的一次打开（[origin] 发起页的回退栈条目，[nav] 点击时的导航世代）。 */
    private class OpenIntent(
        val route: String,
        val params: JsonObject,
        val origin: String?,
        val nav: Long,
    ) {
        /** 点击之后发起页经历过 ON_PAUSE（可见世代已变，共性第 57 条）。 */
        var paused = false

        /** 摘掉发起页上的可见性观察（查询结束时）。 */
        var release: () -> Unit = {}
    }

    /** 发起页仍在栈顶且可见（至少 STARTED，APP-09 共性第 31 条）、点击之后导航没有变过（读取失败的提示据此显示在发起页）。 */
    @Suppress("ReturnCount") // 导航世代、发起页、可见性逐项
    private fun stillOnOrigin(intent: OpenIntent): Boolean {
        if (navGeneration != intent.nav) return false
        if (intent.origin == null) return true
        val top = navController?.currentBackStackEntry ?: return false
        return top.id == intent.origin && top.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
    }

    /**
     * 查询之后的导航许可（共性第 31、37、57 条，Android R3-07）：同一发起实例、导航世代未变、点击之后没有 ON_PAUSE（可见世代未变），且此刻处于 RESUMED
     * （ON_PAUSE 之后、ON_STOP 之前的 STARTED 不算前台）。
     */
    @Suppress("ReturnCount") // 可见世代与发起页、无发起页、前台逐项
    private fun mayNavigate(intent: OpenIntent): Boolean {
        if (intent.paused || !stillOnOrigin(intent)) return false
        if (intent.origin == null) return true
        return navController
            ?.currentBackStackEntry
            ?.lifecycle
            ?.currentState
            ?.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) == true
    }

    /**
     * 实名查询的读取失败（APP-09 共性第 21 条，R1-10）：按发起页显示（统一错误文案，5xxxx 追加 trace6），可【重试】；条目 id → 提示。
     * 不当成未实名继续导航。
     */
    val openErrors = MutableStateFlow<Map<String, OpenError>>(emptyMap())

    /** 发起页上的一条实名查询错误；[retry] 重新发起同一次打开。 */
    class OpenError(
        val notice: Notice,
        val retry: () -> Unit,
    )

    fun dismissOpenError(entryId: String) {
        openErrors.value -= entryId
    }

    /** 取 GET /v1/me 的 realname_status（只认 verified；查不到按未实名，由页面与服务端再核对）。 */
    fun refreshRealname() = refreshRealname(null)

    @Suppress("CyclomaticComplexMethod") // 序号、成功导航、读取错误与限流拦截逐项
    private fun refreshRealname(intent: OpenIntent?) {
        // 共享请求世代（APP-09 共性第 9 条）：只接收最新一次查询的结论，旧查询不得覆盖实名完成后的结论；换代与新查询都作废旧请求
        val epoch = sessionEpoch()
        val request = ++realnameSeq
        realnameOwner?.let(api::cancelOwned)
        // 本次查询的发送前限流闸门（共性第 62 条）：被它拦下时记下原因，回调按原因结算
        val probe = gates.read.probe()
        // 实名查询是 getMe：另受共享 Me 读取截止时间约束（R3-01），每次实际写出前两份都核对；请求归属仍是本次查询
        val meGate = meRead()
        val meProbe = meGate?.probe()
        val owner =
            CallOwnership(api.epochs(), sendAllowed = { probe.allowed() && meProbe?.allowed() != false }) {
                realnameSeq == request && sessionEpoch() == epoch
            }
        realnameOwner = owner
        scope.launch {
            val result =
                try {
                    api.send(ApiCall(GET_ME, ownership = owner))
                } finally {
                    intent?.release?.invoke()
                }
            // 被更新的查询取代或会话换代：结论与打开动作都交给最新那次，这次的 continuation 不执行（共性第 21 条）
            if (realnameSeq != request || sessionEpoch() != epoch) return@launch
            val status = ((result as? ApiResult.Success)?.data as? JsonObject)?.get("realname_status") as? JsonPrimitive
            if (result is ApiResult.Success) {
                realnameState = epoch to (status?.content == VERIFIED)
                if (intent != null && mayNavigate(intent)) {
                    routing.router.open(RouteRequest(intent.route, intent.params, RouteSource.IN_APP))
                }
                return@launch
            }
            // 读取错误统一处理（共性第 3 条）：42901 更新共享截止时间、10405 进强更；5xxxx 带 trace6 显示在发起页并可重试，不当成未实名继续导航
            val error = WalletErrors.of(result)
            if (error.kind == WalletError.RETRY_AFTER) gates.read.start(error.disableMillis)
            // 共享 Me 读取截止时间：结算这一刻复核发起时的归属与隐私、环境世代，失效结果不改它（R3-02）
            if (meGate != null) MeReadLimit.settle(result, owner, api::epochs, meGate::start)
            if (error.kind == WalletError.FORCE_UPDATE) forceUpdate()
            val origin = intent?.origin ?: return@launch
            // 仍属当前点击、被共享 read 闸门在发送前拦下（共性第 56 条，Android R3-06）：同样在发起页给 42901 与受限流控制的【重试】；
            // 其余作废（真正失效的旧请求在上面已丢弃）静默
            // 按本请求记下的拒绝原因（共性第 62 条，Android RC-04），不看回调时刻的禁用期（拒绝之后可能已到期）
            val gated = probe.gated(error) || meProbe?.gated(error) == true
            val silent = (error.kind == WalletError.CANCELLED && !gated) || error.kind == WalletError.FORCE_UPDATE
            if (silent || !stillOnOrigin(intent)) return@launch
            val notice =
                if (gated) Notice("error.42901", ++errorSerial) else Notice(error.textKey, ++errorSerial, error.traceId, error.showTrace6)
            openErrors.value += origin to OpenError(notice) { retryOpen(origin, intent) }
        }
    }

    private fun retryOpen(
        origin: String,
        intent: OpenIntent,
    ) {
        // 【重试】受共享 read 禁用期与共享 Me 读取截止时间控制（页面上置灰，执行层也拦）
        if (realnameBlocked()) return
        openErrors.value -= origin
        if (navController?.currentBackStackEntry?.id != origin) return
        open(intent.route, intent.params)
    }

    private var errorSerial = 0L

    @Volatile private var realnameSeq = 0L
    private var realnameOwner: CallOwnership? = null

    /** 导航世代：回退栈每变一次加 1（发起页被盖住、出栈、换页都算）。 */
    private var navGeneration = 0L
    private var lastStackIds: Set<String> = emptySet()

    /** 收款账号保存成功：它下面是 Withdraw（从提现页进来补办）就出栈回发起页；从别处进来的停在本页（brief §2.7）。 */
    @SuppressLint("RestrictedApi") // 完整回退栈只有 currentBackStack 给得出（只读）
    @Suppress("ReturnCount") // 没有导航、不是发起实例、不是从提现页进来逐项
    private fun popPayoutIfFromWithdraw(entryId: String?): Boolean {
        val controller = navController ?: return false
        // 只出栈发起的那个收款账号页实例（共性第 50 条），不按路由名
        if (entryId != null && controller.currentBackStackEntry?.id != entryId) return false
        val routes =
            controller.currentBackStack.value.mapNotNull { entry ->
                runCatching { entry.toRoute<RouteDestination>().name }.getOrNull()
            }
        val fromWithdraw =
            routes.size >= 2 && routes.last() == WalletRoutes.PAYOUT_ACCOUNT && routes[routes.size - 2] == WalletRoutes.WITHDRAW
        if (fromWithdraw) controller.popBackStack()
        return fromWithdraw
    }

    /** 页面配置（[entryId] 为回退栈条目）。 */
    fun config(
        destination: RouteDestination,
        entryId: String?,
        onBack: () -> Unit,
    ): WalletPageConfig =
        WalletPageConfig(
            deps = deps,
            // 出栈只作用于本页这一个导航实例（共性第 50 条）：本页不在栈顶时不出栈别的页面
            onBack = { if (entryId == null || navController?.currentBackStackEntry?.id == entryId) onBack() },
            entryId = entryId,
            hooks =
                PageHooks(
                    bindLeave = { leave -> if (entryId != null) leaves[entryId] = leave },
                    bindSession = { handler -> if (entryId != null) sessions[entryId] = handler },
                    bindIdentity = { handler -> if (entryId != null) identities[entryId] = handler },
                ),
            params = params(destination),
        )

    /** 回退栈变了（主线程、同一条消息里）：已登记的页面不在栈里了 → 立即作废它的读请求（APP-04 第 28、47 条，APP-07 第 3 条）。 */
    fun followBackStack(entries: List<NavBackStackEntry>) {
        if (entries.isEmpty()) return
        followBackStackIds(entries.map { it.id }.toSet())
    }

    internal fun followBackStackIds(ids: Set<String>) {
        if (ids != lastStackIds) {
            lastStackIds = ids
            navGeneration += 1
        }
        leaves.keys.filter { it !in ids }.forEach { id -> leaves.remove(id)?.invoke() }
        sessions.keys.retainAll(ids)
        identities.keys.retainAll(ids)
        if (openErrors.value.keys.any { it !in ids }) openErrors.value = openErrors.value.filterKeys { it in ids }
    }

    /**
     * 会话变了（登录、退出、换账号、会话失效；AppModule 接 Router 的会话状态与网络层的会话通知）：会话世代变了才处理（APP-08 共性第 1、19 条）。
     */
    fun onSessionChanged() {
        // F-X-19 补充二 / 三：一份身份快照；未知只延后（待清理照做）；已知按固定标签对账、没完成登记重试；判据没变不重置页面
        val decision = reconciler.check()
        val known = reconciler.snapshot?.takeIf { it.known && decision != com.couli.app.SessionCacheReconciler.Decision.UNKNOWN } ?: return
        if (decision != com.couli.app.SessionCacheReconciler.Decision.CHANGED) {
            // 判据相同：提前返回前，栈里每个钱包类页面按这份已确认快照走统一的恢复入口（补充三 ②；FU-AND-0717 第 3 轮：钱包摘要、提现、收款账号、实名、
            // 劳务协议、提现记录一套机制，R2-04）
            identities.values.toList().forEach { it(known, false) }
            return
        }
        gates.reset()
        tokens.onSessionChanged()
        deps.sms.clear()
        realnameState = null
        openErrors.value = emptyMap()
        // 按这份快照重新绑定（页面不另读一次身份）；没登记统一入口的页面（旧接线）走原会话入口
        val identified = identities.toMap()
        identified.values.forEach { it(known, true) }
        sessions
            .filterKeys { it !in identified }
            .values
            .toList()
            .forEach { it() }
        if (routing.session.value.loggedIn()) {
            runCatching { api.restorePending() }
            refreshRealname()
        }
    }

    /** 接 Router 的会话状态与路由结论（AppModule 与集成测试共用）。 */
    fun connectSession() {
        routing.realnameSource = ::realnameVerified
        scope.launch {
            yield()
            // F-X-19：冷启动首次对账走同一入口（身份暂时未知不以 null 账号清缓存；待清理照常）
            reconciler.check()
            if (routing.session.value.loggedIn()) refreshRealname()
            routing.session.drop(1).collect { onSessionChanged() }
        }
        scope.launch {
            // 资金与实名四页带参数打开：Router 已丢弃参数，这里只记个数（route_params_dropped，不记内容）
            routing.router.lastOutcome.filterNotNull().collect { outcome ->
                val route = outcome.decision.route ?: return@collect
                val dropped = outcome.decision.paramsDropped
                if (dropped <= 0 || FundRouteParams.filtered(route, outcome.request.params).event == null) return@collect
                val raw = RouteParamsDroppedRoute.entries.firstOrNull { it.raw == route } ?: return@collect
                track(ClientEvents.routeParamsDropped(page = route, route = raw, droppedCount = dropped.toLong()))
            }
        }
    }

    private fun params(destination: RouteDestination): JsonObject =
        runCatching { Json.parseToJsonElement(destination.paramsJson).jsonObject }.getOrNull() ?: JsonObject(emptyMap())

    companion object {
        private const val GET_ME = "getMe"
        private const val HELP = "Help"
        private const val VERIFIED = "verified"
        private const val AUTH_REALNAME = "realname"
        const val CACHE_DIR = "couli_wallet"
        const val WALLET_CACHE_CLEANER = "wallet_cache"

        /** 钱包缓存目录：App 私有、不参与备份（noBackupFilesDir），「清除缓存」不清（03 §4.3）。 */
        fun cacheRoot(context: Context): File = File(context.applicationContext.noBackupFilesDir, CACHE_DIR)
    }
}

private fun SessionState.loggedIn(): Boolean = this == SessionState.LOGGED_IN || this == SessionState.PHONE_BOUND

/** 钱包类路由的页面（AppShell 注册）。 */
@Composable
fun WalletRoute(
    wallet: AppWallet,
    destination: RouteDestination,
    entryId: String?,
    onBack: () -> Unit,
) {
    val config = wallet.config(destination, entryId, onBack)
    Box(modifier = Modifier.fillMaxSize()) {
        when (destination.name) {
            WalletRoutes.WALLET -> WalletScreen(config)
            WalletRoutes.WITHDRAW -> WithdrawScreen(config)
            WalletRoutes.WITHDRAW_RECORDS -> WithdrawRecordsScreen(config)
            WalletRoutes.PAYOUT_ACCOUNT -> PayoutAccountScreen(config)
            WalletRoutes.REAL_NAME -> RealNameScreen(config)
            WalletRoutes.LABOR_AGREEMENT -> LaborAgreementScreen(config)
        }
        // 实名查询失败（R1-10）：显示在发起页，带 trace6 与【重试】
        val errors by wallet.openErrors.collectAsState()
        val error = entryId?.let(errors::get)
        if (error != null && entryId != null) {
            val readBlocked by wallet.gates.read.blocked
                .collectAsState()
            val meBlocked by (wallet.meReadGate()?.blocked ?: kotlinx.coroutines.flow.MutableStateFlow(false))
                .collectAsState()
            OpenErrorBar(
                notice = error.notice,
                texts = config.deps.texts,
                retryEnabled = !readBlocked && !meBlocked,
                onRetry = error.retry,
                onDismiss = { wallet.dismissOpenError(entryId) },
            )
        }
    }
}

/** 本模块接管的路由名。 */
val WALLET_ROUTES: Set<String> =
    setOf(
        WalletRoutes.WALLET,
        WalletRoutes.WITHDRAW,
        WalletRoutes.WITHDRAW_RECORDS,
        WalletRoutes.PAYOUT_ACCOUNT,
        WalletRoutes.REAL_NAME,
        WalletRoutes.LABOR_AGREEMENT,
    )
