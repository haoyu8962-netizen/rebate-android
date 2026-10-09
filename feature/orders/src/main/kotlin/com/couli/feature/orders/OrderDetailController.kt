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

/** 订单详情的一次状态快照。[version] 每次换详情数据加 1：按钮回调带渲染时的版本，执行时核对仍是它。 */
data class OrderDetailSnapshot(
    val phase: String = OrderDetailPhase.LOADING,
    val detail: OrderDetail? = null,
    val version: Long = 0,
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

    /** 当前显示的详情属于哪个会话世代（按钮执行前核对）。 */
    private var shownEpoch = Long.MIN_VALUE

    private val mutableState = MutableStateFlow(OrderDetailSnapshot())
    val state: StateFlow<OrderDetailSnapshot> = mutableState.asStateFlow()
    private val s: OrderDetailSnapshot get() = mutableState.value

    init {
        scope.launch { gate.blocked.collect { b -> if (!left) update { it.copy(blocked = b) } } }
    }

    fun load() {
        if (left) return
        seq += 1
        cancelAll()
        if (heldBeforeSend()) return
        val request = seq
        val epoch = env.sessionEpoch()
        update { it.copy(phase = OrderDetailPhase.LOADING, error = null) }
        // 禁用期是独立的发送前闸门（不进归属）：网络层每次实际写出前（含自动重试、重放）核对，禁用期内不再发包；已发出的照常收结果（共性第 13、27 条）
        val owner = CallOwnership(api.epochs(), sendAllowed = { !gate.active }) { !left && seq == request && env.sessionEpoch() == epoch }
        owners += owner
        scope.launch {
            val result = api.send(ApiCall(GET_OPERATION, pathParams = mapOf("order_id" to orderId), ownership = owner))
            owners -= owner
            // 等待之后复核：页面已出栈、又发了新请求、会话已换代，都只丢弃
            if (left || seq != request || env.sessionEpoch() != epoch) return@launch
            onResult(result, epoch)
        }
    }

    /** 发送前拦截（没有登录会话、42901 禁用期内）：不发请求，按情况显示；拦下了返回 true。 */
    @Suppress("ReturnCount") // 游客、限流两个拦截出口
    private fun heldBeforeSend(): Boolean {
        if (!env.loggedIn()) {
            // 订单详情 auth=login：没有登录会话时不发请求、不显示任何订单数据（重新登录后会话换代再取）
            update { it.copy(phase = OrderDetailPhase.LOADING, detail = null, version = it.version + 1, error = null, staleAt = null) }
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
                )
            }
            return true
        }
        return false
    }

    @Suppress("ReturnCount") // 成功、作废两个提前出口
    private fun onResult(
        result: ApiResult,
        epoch: Long,
    ) {
        val detail = (result as? ApiResult.Success)?.data?.let { it as? JsonObject }?.let(OrderJson::detail)
        if (detail != null && detail.summary.orderId == orderId) {
            cache?.writeDetail(env.account(), detail, clock())
            shownEpoch = epoch
            update {
                it.copy(
                    phase = OrderDetailPhase.CONTENT,
                    detail = detail,
                    version = it.version + 1,
                    staleAt = null,
                    error = null,
                )
            }
            env.onDetailLoaded(detail)
            return
        }
        val cancelled = result is ApiResult.LocalReject && result.reason == LocalRejectReason.CALL_CANCELLED
        // 在途期间别的订单请求触发了 42901：网络层按归属不再重发（本地拒绝，或首发已断连而不再重试），显示限流错误态（不停在加载中）
        if (gate.active && (cancelled || result is ApiResult.TransportFailure)) {
            heldBeforeSend()
            return
        }
        if (cancelled || result is ApiResult.SessionEnded) return
        failed(result, epoch)
    }

    private fun failed(
        result: ApiResult,
        epoch: Long,
    ) {
        val transport = result is ApiResult.TransportFailure
        val cached = if (transport) cache?.readDetail(env.account(), orderId) else null
        val view =
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
                    OrderErrorAction.of(OrderErrorAction.DETAIL, null, true, 0L, null, cached != null, false)
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
        when (view.actionRaw) {
            OrderErrorKind.EMPTY_30701 -> {
                update { it.copy(phase = OrderDetailPhase.NOT_FOUND, detail = null, version = it.version + 1, error = view) }
            }

            OrderErrorKind.SHOW_STALE -> {
                val hit = cached ?: return
                shownEpoch = epoch
                update {
                    it.copy(
                        phase = OrderDetailPhase.CONTENT,
                        detail = hit.detail,
                        version = it.version + 1,
                        staleAt = hit.fetchedAt,
                        error = null,
                    )
                }
            }

            else -> {
                update { it.copy(phase = OrderDetailPhase.ERROR, detail = null, version = it.version + 1, error = view, staleAt = null) }
                if (view.actionRaw == OrderErrorKind.RETRY_AFTER) startBlock(view.disableMs ?: 0L)
                if (view.actionRaw == OrderErrorKind.FORCE_UPDATE) env.forceUpdate()
            }
        }
    }

    /** 【重试】（42901 禁用中不发）；stale 状态下的刷新也走这里。 */
    fun retry(): Boolean {
        if (left || gate.active) return false
        load()
        return true
    }

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
        env.open(button.route, JsonObject(emptyMap()))
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
    private fun current(version: Long): OrderDetail? =
        s.detail?.takeIf { !left && s.version == version && shownEpoch == env.sessionEpoch() }

    private fun startBlock(millis: Long) {
        gate.start(millis)
    }

    /**
     * 会话换代（登录、退出、换账号、会话失效；App 在会话通知里同步调用）：作废并取消在途请求，同步清掉已显示的详情快照（版本加 1：旧按钮回调失效，
     * 不会再复制旧账号的订单号），再按新会话重新请求；App 只在会话世代变了时调用。
     */
    fun onSessionChanged() {
        if (left) return
        shownEpoch = Long.MIN_VALUE
        seq += 1
        cancelAll()
        update {
            OrderDetailSnapshot(phase = OrderDetailPhase.LOADING, version = it.version + 1, blocked = gate.blocked.value)
        }
        load()
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
        private const val GENERIC_SERVER_ERROR = 50_001

        /** 测试用：把路由参数 order_id 包成 JSON。 */
        fun params(orderId: String): JsonObject = JsonObject(mapOf("order_id" to JsonPrimitive(orderId)))
    }
}
