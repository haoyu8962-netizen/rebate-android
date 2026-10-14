package com.couli.feature.orders

import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallOwnership
import com.couli.core.network.LocalRejectReason
import com.couli.core.network.PageApi
import com.couli.core.network.ResultSignals
import com.couli.core.orders.OrderActionButton
import com.couli.core.orders.OrderDetail
import com.couli.core.orders.OrderDetailActions
import com.couli.core.orders.OrderErrorAction
import com.couli.core.orders.OrderErrorKind
import com.couli.core.orders.OrderErrorView
import com.couli.core.orders.OrderJson
import com.couli.core.orders.OrderRateGate
import com.couli.core.orders.OrderStaleCache
import com.couli.core.orders.PriceCompareEntry
import com.couli.core.orders.ViewProductEntry
import com.couli.generated.Routes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 详情的阶段。 */
object OrderDetailPhase {
    const val LOADING = "loading"
    const val CONTENT = "content"

    /** 30701：范围外、不属于本人、不存在都显示同一个空态（error.30701），不重试。 */
    const val NOT_FOUND = "not_found"
    const val ERROR = "error"
}

/**
 * 详情页状态所属的会话身份（FU-AND-0710 R3-02，APP-08 共性第 44 条）：加载发起时一次性取（会话世代 + 账号），之后内容态、错误态都带着它；
 * 回调执行时只拿它与当前会话比较，不重新读当前会话来绑定。
 */
data class OrderDetailOwner(
    val epoch: Long,
    val account: String?,
)

/**
 * 进程恢复等待的票据（FU-AND-0710 RC-01，APP-08 共性第 21 条、APP-09 共性第 65 条）：等待前一次性捕获详情的固定身份 [owner]、绑定代次 [binding]
 * 与状态版本 [version]；等待结束后只拿它比较，任何一项变了（会话已换代、已清空旧页面、已换数据）都直接放弃，不重新读当前会话来绑定。
 */
data class OrderDetailRestoreTicket(
    val owner: OrderDetailOwner,
    val binding: Long,
    val version: Long,
)

/**
 * 订单详情的一次状态快照。[version] 每次换详情数据加 1：按钮回调带渲染时的版本，执行时核对仍是它。[owner]：这份状态（加载中、内容、错误）
 * 是哪个会话身份发起的；【重试】、缓存态刷新带渲染时的 owner，执行时核对它仍是当前会话。
 */
data class OrderDetailSnapshot(
    val phase: String = OrderDetailPhase.LOADING,
    val detail: OrderDetail? = null,
    val version: Long = 0,
    val owner: OrderDetailOwner? = null,
    val error: OrderErrorView? = null,
    val staleAt: Long? = null,
    val blocked: Boolean = false,
    val toast: OrderToast? = null,
)

/**
 * 订单详情执行器（APP-08 §2.5）。请求绑定页面实例、会话世代与隐私 / 环境世代；页面出栈（同步出口）后迟到的响应不改任何状态。
 * 成功时落盘缓存（最近 30 条，G10）并交给列表回填那一行（G17）；30701 显示空态不重试；传输失败有缓存时显示缓存与「网络不可用，以下为 … 的数据」；
 * 5xxxx 带 trace6；42901 按 Retry-After 禁用【重试】（会话级共享的 [rateGate]，与列表、待跟单共用；禁用期内不发请求）；10405 强制进入强更。
 * 会话换代（[onSessionChanged]，APP-08 共性第 1 条）：同步作废在途请求、清掉已显示的详情（版本加 1，旧按钮回调随之失效），再按新会话重新请求
 * （服务端判归属，不是本人的返回 30701 空态）；按钮执行前同时核对数据所属的会话世代。只在主线程使用。
 */
@Suppress("TooManyFunctions", "LongParameterList") // 页面每个入口一个函数；执行器依赖逐项注入
class OrderDetailController(
    private val api: PageApi,
    private val env: OrderEnv,
    private val cache: OrderStaleCache.Store?,
    private val scope: CoroutineScope,
    private val orderId: String,
    private val clock: () -> Long = System::currentTimeMillis,
    rateGate: OrderRateGate? = null,
) {
    private var seq = 0L
    private var left = false
    private val owners = mutableListOf<CallOwnership>()
    private val gate = rateGate ?: OrderRateGate(scope, clock)
    private var toastSerial = 0L

    /**
     * 本页固定的会话身份（RC-01，APP-09 共性第 65 条）：构造时一次性取（世代与账号同一次读取）；首次加载、恢复等待结束后的加载都只用这一份。
     * 只有清空旧页面的会话换代出口 [onSessionChanged] 重新绑定（[binding] 随之加 1）。
     */
    private var bound = OrderDetailOwner(env.sessionEpoch(), env.account())
    private var binding = 0L

    private val mutableState = MutableStateFlow(OrderDetailSnapshot())
    val state: StateFlow<OrderDetailSnapshot> = mutableState.asStateFlow()
    private val s: OrderDetailSnapshot get() = mutableState.value

    init {
        scope.launch { gate.blocked.collect { b -> if (!left) update { it.copy(blocked = b) } } }
    }

    /**
     * 页面首次加载（ViewModel 创建时）：按构造时固定的身份 [bound] 请求，不在此刻重新读当前会话来绑定（RC-01）。之后的【重试】、刷新沿用状态里
     * 固定的身份（[retry]），只有清空旧页面的会话换代出口 [onSessionChanged] 重新绑定（R3-02）。固定身份已不是当前会话时不发。
     */
    fun load(): Boolean {
        if (left || !isCurrent(bound)) return false
        fetch(bound)
        return true
    }

    /** 进程恢复等待之前调用：捕获固定身份、绑定代次与状态版本（RC-01）。 */
    fun restoreTicket(): OrderDetailRestoreTicket = OrderDetailRestoreTicket(bound, binding, s.version)

    /**
     * 进程恢复等待结束：先比较等待前的票据 [ticket]——页面已离开、已重新绑定（会话出口清空过旧页面）、状态版本变了、固定身份已不是当前会话
     * （会话存储已换号而通知还没到）都直接返回、零请求；通过后按票据里的原身份请求（RC-01）。
     */
    @Suppress("ReturnCount") // 离开 / 换绑 / 版本、身份失效两个出口
    fun resumeRestore(ticket: OrderDetailRestoreTicket): Boolean {
        val waiting = OrderDetailRestoreTicket(bound, binding, s.version)
        if (left || ticket != waiting) return false
        if (!isCurrent(ticket.owner)) return false
        fetch(ticket.owner)
        return true
    }

    /** 按已固定的会话身份 [owner] 发请求：本函数内不读当前会话来绑定（R3-02），结果到达后只比较。 */
    private fun fetch(owner: OrderDetailOwner) {
        if (left) return
        seq += 1
        cancelAll()
        if (heldBeforeSend(owner)) return
        val request = seq
        val epoch = owner.epoch
        val account = owner.account
        update { it.copy(phase = OrderDetailPhase.LOADING, error = null, owner = owner) }
        // 禁用期是独立的发送前闸门（不进归属）：网络层每次实际写出前（含自动重试、重放）核对，禁用期内不再发包；已发出的照常收结果（共性第 13、27 条）
        val call = CallOwnership(api.epochs(), sendAllowed = { !gate.active }) { !left && seq == request && env.sessionEpoch() == epoch }
        owners += call
        scope.launch {
            val result = api.send(ApiCall(GET_OPERATION, pathParams = mapOf("order_id" to orderId), ownership = call))
            owners -= call
            // 等待之后复核：页面已出栈、又发了新请求、会话已换代，都只丢弃
            if (left || seq != request || env.sessionEpoch() != epoch) return@launch
            onResult(result, owner)
        }
    }

    /** 发送前拦截（没有登录会话、42901 禁用期内）：不发请求，按情况显示；拦下了返回 true。 */
    @Suppress("ReturnCount", "LongMethod") // 游客、身份没建立、限流三个拦截出口
    private fun heldBeforeSend(owner: OrderDetailOwner): Boolean {
        if (env.loggedIn() && owner.account == null) {
            // 页面身份没建立（F-AND-28，APP-09 共性第 65 条）：已登录却读不出账号——可恢复的初始化失败。不发请求、不读写缓存、不发布订单数据；
            // 【重试】时清空并重新取得固定身份（[retry]）
            update {
                it.copy(
                    phase = OrderDetailPhase.ERROR,
                    detail = null,
                    version = it.version + 1,
                    error = OrderErrorAction.of(OrderErrorAction.DETAIL, null, true, 0L, null, false, false),
                    staleAt = null,
                    owner = owner,
                )
            }
            return true
        }
        if (!env.loggedIn()) {
            // 订单详情 auth=login：没有登录会话时不发请求、不显示任何订单数据（重新登录后会话换代再取）
            update {
                it.copy(
                    phase = OrderDetailPhase.LOADING,
                    detail = null,
                    version = it.version + 1,
                    error = null,
                    staleAt = null,
                    owner = owner,
                )
            }
            return true
        }
        if (gate.active) {
            // 42901 禁用期内不发（会话级共享，重进页面也一样）：显示限流错误态，【重试】随禁用期置灰
            update {
                it.copy(
                    phase = OrderDetailPhase.ERROR,
                    detail = null,
                    version = it.version + 1,
                    error = OrderErrorView(OrderErrorKind.RETRY_AFTER, "error.${ResultSignals.RATE_LIMITED}"),
                    staleAt = null,
                    owner = owner,
                )
            }
            return true
        }
        return false
    }

    @Suppress("ReturnCount") // 成功、作废两个提前出口
    private fun onResult(
        result: ApiResult,
        owner: OrderDetailOwner,
    ) {
        val detail = (result as? ApiResult.Success)?.data?.let { it as? JsonObject }?.let(OrderJson::detail)
        if (detail != null && detail.summary.orderId == orderId) {
            cache?.writeDetail(owner.account, detail, clock())
            update {
                it.copy(
                    phase = OrderDetailPhase.CONTENT,
                    detail = detail,
                    version = it.version + 1,
                    staleAt = null,
                    error = null,
                    owner = owner,
                )
            }
            env.onDetailLoaded(detail)
            return
        }
        val cancelled = result is ApiResult.LocalReject && result.reason == LocalRejectReason.CALL_CANCELLED
        // 在途期间别的订单请求触发了 42901：网络层按归属不再重发（本地拒绝，或首发已断连而不再重试），显示限流错误态（不停在加载中）
        if (gate.active && (cancelled || result is ApiResult.TransportFailure)) {
            heldBeforeSend(owner)
            return
        }
        if (cancelled || result is ApiResult.SessionEnded) return
        failed(result, owner)
    }

    private fun failed(
        result: ApiResult,
        owner: OrderDetailOwner,
    ) {
        val transport = result is ApiResult.TransportFailure
        val cached = if (transport) cache?.readDetail(owner.account, orderId) else null
        val view = errorView(result, cached != null)
        when (view.actionRaw) {
            OrderErrorKind.EMPTY_30701 -> {
                update {
                    it.copy(phase = OrderDetailPhase.NOT_FOUND, detail = null, version = it.version + 1, error = view, owner = owner)
                }
            }

            OrderErrorKind.SHOW_STALE -> {
                val hit = cached ?: return
                update {
                    it.copy(
                        phase = OrderDetailPhase.CONTENT,
                        detail = hit.detail,
                        version = it.version + 1,
                        staleAt = hit.fetchedAt,
                        error = null,
                        owner = owner,
                    )
                }
            }

            else -> {
                update {
                    it.copy(
                        phase = OrderDetailPhase.ERROR,
                        detail = null,
                        version = it.version + 1,
                        error = view,
                        staleAt = null,
                        owner = owner,
                    )
                }
                if (view.actionRaw == OrderErrorKind.RETRY_AFTER) startBlock(view.disableMs ?: 0L)
                if (view.actionRaw == OrderErrorKind.FORCE_UPDATE) env.forceUpdate()
            }
        }
    }

    /** 失败结果 → 详情错误视图（[hasCache]：断网时有本账号的缓存）。 */
    private fun errorView(
        result: ApiResult,
        hasCache: Boolean,
    ): OrderErrorView =
        when (result) {
            is ApiResult.Failure -> {
                OrderErrorAction.of(
                    OrderErrorAction.DETAIL,
                    result.code,
                    false,
                    ResultSignals.disableMillis(result),
                    result.traceId,
                    false,
                    false,
                )
            }

            is ApiResult.TransportFailure -> {
                OrderErrorAction.of(OrderErrorAction.DETAIL, null, true, 0L, null, hasCache, false)
            }

            else -> {
                OrderErrorAction.of(
                    OrderErrorAction.DETAIL,
                    GENERIC_SERVER_ERROR,
                    false,
                    0L,
                    (result as? ApiResult.Success)?.traceId,
                    false,
                    false,
                )
            }
        }

    /**
     * 【重试】（42901 禁用中不发）；stale 状态下的刷新也走这里。[version] 是渲染时的版本（R2-01：会话换代、换数据后旧画面上的【重试】不再执行）；
     * [owner] 是渲染时这份状态固定的会话身份（R3-02）：执行前先与当前会话比较（世代与账号），会话存储已换代而通知还没到时直接返回、不发请求；
     * 通过后按这份固定身份请求，不在执行时重新读当前会话来绑定。两者为 null 只给 App 内部入口（按当前状态里的版本与身份）。
     */
    @Suppress("ReturnCount") // 页面已离开 / 禁用期 / 版本不符、身份失效两个出口
    fun retry(
        version: Long? = null,
        owner: OrderDetailOwner? = null,
    ): Boolean {
        val stale = version != null && version != s.version
        if (left || gate.active || stale) return false
        if (env.loggedIn() && bound.account == null && (owner ?: s.owner)?.account == null) {
            // 身份没建立（F-AND-28）：清空后重新一次性取得固定身份（与会话换代出口同一做法），再按新身份请求
            onSessionChanged()
            return true
        }
        val fixed = owner ?: s.owner ?: return false
        if (fixed != s.owner || !isCurrent(fixed)) return false
        fetch(fixed)
        return true
    }

    /** 固定身份仍是此刻的会话（世代与账号都相同）。只比较，不赋值。 */
    private fun isCurrent(owner: OrderDetailOwner?): Boolean =
        owner != null && owner.epoch == env.sessionEpoch() && owner.account == env.account()

    // ---------------------------------------------------------------- 按钮（回调带渲染时的版本）

    /** 【复制】：只有自购单（有完整 order_no）；经统一写入口写入原文，记 hash 失败不写并提示。 */
    @Suppress("ReturnCount") // 版本不符、没有完整单号、分享单逐项
    fun copy(version: Long): Boolean {
        val detail = current(version) ?: return false
        val orderNo = detail.summary.orderNo ?: return false
        if (detail.summary.maskedOrderNo != null) return false
        val ok = env.copy(orderNo)
        update {
            it.copy(
                toast =
                    OrderToast(
                        if (ok) OrderListController.COPY_DONE_KEY else OrderListController.COPY_FAILED_KEY,
                        ++toastSerial,
                        error = !ok,
                    ),
            )
        }
        return ok
    }

    /** 「查看商品」：ProductDetail {platform, product_key}（不带 item_ref、不带任何订单或 link 信息）。 */
    fun viewProduct(version: Long): Boolean {
        val target = current(version)?.let(ViewProductEntry::of) ?: return false
        env.open(target.route, target.params)
        return true
    }

    /** 详情按钮：按钮仍在渲染时那份详情的按钮里才打开。 */
    @Suppress("ReturnCount") // 版本不符、按钮不在逐项
    fun action(
        version: Long,
        button: OrderActionButton,
    ): Boolean {
        val detail = current(version) ?: return false
        if (button !in OrderDetailActions.of(detail).buttons) return false
        // 联系客服走统一客服出口（APP-12 brief §2.9，替换「联系客服 → Help」占位）
        if (button.actionRaw == CONTACT_CS) env.contactService() else env.open(button.route, JsonObject(emptyMap()))
        return true
    }

    /** 「查看说明」：配置的帮助文章（help_links.price_compare，按 routes.json 校验）。 */
    @Suppress("ReturnCount") // 版本不符、没有配置逐项
    fun openHelp(version: Long): Boolean {
        val detail = current(version) ?: return false
        val target = PriceCompareEntry.of(detail, env.helpLinks(), Routes.all) ?: return false
        env.open(target.route, target.params)
        return true
    }

    /** 「申诉中」块的【查看申诉进度】→ Appeal（不带参数，G08）。 */
    @Suppress("ReturnCount") // 版本不符、不在申诉中逐项
    fun openAppeal(version: Long): Boolean {
        val detail = current(version) ?: return false
        if (!detail.appealPending) return false
        env.open(OrderRoutes.APPEAL, JsonObject(emptyMap()))
        return true
    }

    fun consumeToast(id: Long) {
        if (s.toast?.id == id) update { it.copy(toast = null) }
    }

    /** 渲染时的版本仍是当前、页面还在、数据仍属于此刻的会话世代，才返回详情（按钮回调用）。 */
    private fun current(version: Long): OrderDetail? = s.detail?.takeIf { !left && s.version == version && isCurrent(s.owner) }

    private fun startBlock(millis: Long) {
        gate.start(millis)
    }

    /**
     * 会话换代（登录、退出、换账号、会话失效；App 在会话通知里同步调用）：作废并取消在途请求，同步清掉已显示的详情快照（版本加 1：旧按钮回调失效，
     * 不会再复制旧账号的订单号），再按新会话重新请求；App 只在会话世代变了时调用。这是唯一允许重新绑定固定身份的出口（清空旧页面之后，RC-01）。
     */
    fun onSessionChanged() {
        if (left) return
        seq += 1
        cancelAll()
        update {
            OrderDetailSnapshot(phase = OrderDetailPhase.LOADING, version = it.version + 1, blocked = gate.blocked.value)
        }
        // 旧页面已清空：此刻重新绑定（世代与账号同一次读取），绑定代次加 1，等待中的旧恢复票据随之失效
        bound = OrderDetailOwner(env.sessionEpoch(), env.account())
        binding += 1
        fetch(bound)
    }

    /** 页面出栈（同步出口；ViewModel 清理兜底）：作废请求并取消传输，之后迟到的响应不改任何状态。 */
    fun leave() {
        if (left) return
        left = true
        seq += 1
        cancelAll()
    }

    val alive: Boolean get() = !left

    private fun cancelAll() {
        val cancelled = owners.toList()
        owners.clear()
        cancelled.forEach(api::cancelOwned)
    }

    private inline fun update(change: (OrderDetailSnapshot) -> OrderDetailSnapshot) {
        mutableState.value = change(mutableState.value)
    }

    companion object {
        const val GET_OPERATION = "getOrder"
        private const val CONTACT_CS = "contact_cs"
        private const val GENERIC_SERVER_ERROR = 50_001

        /** 测试用：把路由参数 order_id 包成 JSON。 */
        fun params(orderId: String): JsonObject = JsonObject(mapOf("order_id" to JsonPrimitive(orderId)))
    }
}
