package com.couli.feature.wallet

import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallOwnership
import com.couli.core.wallet.EarningsEntry
import com.couli.core.wallet.SendProbe
import com.couli.core.wallet.WalletError
import com.couli.core.wallet.WalletErrors
import com.couli.core.wallet.WalletJson
import com.couli.core.wallet.WalletRoutes
import com.couli.core.wallet.WalletSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 钱包页的一次状态快照。[staleAt] 非 null = 断网显示的是缓存（WalletStale，【提现】置灰）。 */
data class WalletSnapshot(
    val phase: String = LOADING,
    val summary: WalletSummary? = null,
    val staleAt: Long? = null,
    /** [staleAt] 的原因是网络不可用（「网络不可用，以下为…」）；false 为其他读取失败（中性的「更新于 HH:mm」，共性第 38 条）。 */
    val staleNetwork: Boolean = true,
    val error: Notice? = null,
    val refreshing: Boolean = false,
    val version: Long = 0,
    /** 一次性提示（刷新失败但有数据时）。 */
    val toast: Notice? = null,
) {
    companion object {
        const val LOADING = "loading"
        const val CONTENT = "content"
        const val ERROR = "error"
    }
}

/**
 * 钱包页执行器（Wallet 路由；BR-FUND-18、BR-TEXT-01，brief §2.2，G10、G11、G20、G22、G23）。
 * - 摘要只取服务端字段（[com.couli.core.wallet.WalletSummaryView]）；进入、下拉、回到本页（返回、回前台）各取一次，不轮询；
 * - 断网缓存（G22）：只有传输层失败才用缓存，显示 WalletStale（role=status）并置灰【提现】；5xxxx 不用缓存，显示错误态带 trace6；
 *   成功时更新缓存（按账号隔离）；
 * - 42901：读请求共享禁用期（下拉、【重试】置灰，执行层也拦）；10405 强制进入强更。
 * 请求绑定页面与会话世代；会话换代时同步清掉已显示的摘要并按新会话重取。只在主线程使用。
 */
@Suppress("TooManyFunctions", "ReturnCount", "ComplexCondition") // 页面每个入口一个函数；逐项提前返回
class WalletController(
    private val deps: WalletDeps,
    private val scope: CoroutineScope,
) {
    private var left = false
    private var seq = 0L
    private var ids = 0L
    private val owners = mutableListOf<CallOwnership>()

    /**
     * 本页的固定身份（APP-09 共性第 65 条）：构造时一次性取得，只在会话换代通知里随清空一起重建；摘要请求、缓存的读写都只用它，当前会话只用于和它
     * 比较——令牌已在 IO 线程换成别的账号、清空通知还没执行时，不以新账号的身份读取，也不把摘要写进 / 读出别的账号的缓存。
     */
    private var pageOwner = FormOwner.of(deps.env)

    /** 当前显示的摘要属于哪个会话世代（按钮执行前核对）。 */
    private var shownEpoch = Long.MIN_VALUE

    /** 本会话最近一次成功取得摘要的时刻（内存，APP-09 共性第 32 条：断网保留旧摘要时的「更新时间」不依赖磁盘缓存是否写成功）。 */
    private var lastSuccessAt: Long? = null

    private val mutable = MutableStateFlow(WalletSnapshot())
    val state: StateFlow<WalletSnapshot> = mutable.asStateFlow()
    private val s: WalletSnapshot get() = mutable.value

    val blocked: StateFlow<Boolean> get() = deps.gates.read.blocked

    val alive: Boolean get() = !left

    init {
        // 提现成功的钱包失效通知（APP-09 共性第 53 条，Android R3-02，BR-WDR-04、G19）：只收本会话（账号与世代都一致）的通知
        scope.launch {
            deps.walletDirty.dirty.collect { (account, epoch) ->
                // 与本页固定身份比较（共性第 65 条），并核对它仍是当前会话
                val owner = pageOwner
                if (!left && epoch == owner.epoch && account == owner.account && owner.current(deps.env)) invalidate()
            }
        }
    }

    /**
     * 摘要已失效（提现成功）：作废并取消成功之前发出的摘要请求（它们可能取得扣款前的余额），再重取；被限流时按旧数据状态结算（更新时间、42901、
     * 重试、【提现】置灰，共性第 46 条）。
     */
    private fun invalidate() {
        seq += 1
        cancelAll()
        load()
    }

    fun load(refresh: Boolean = false) {
        if (left) return
        if (!deps.env.loggedIn()) {
            update { WalletSnapshot(version = it.version + 1) }
            return
        }
        if (deps.gates.read.active) {
            gated()
            return
        }
        val request = ++seq
        val page = pageOwner
        val epoch = page.epoch
        update { if (it.summary == null) it.copy(phase = WalletSnapshot.LOADING, error = null) else it.copy(refreshing = refresh) }
        // 本请求的发送前限流闸门（共性第 62 条）：被它拦下时记下原因，回调按原因结算
        val probe = deps.gates.read.probe()
        val owner =
            CallOwnership(deps.api.epochs(), sendAllowed = probe::allowed) {
                !left && seq == request && deps.env.sessionEpoch() == epoch
            }.also { owners += it }
        scope.launch {
            // 网络层按 expectedAccount 在读取凭据前核对账号仍是本页身份（共性第 65 条）
            val result = deps.api.send(ApiCall(OPERATION, ownership = owner, expectedAccount = page.account))
            owners -= owner
            if (left || seq != request || deps.env.sessionEpoch() != epoch || pageOwner != page) return@launch
            onResult(result, epoch, probe)
        }
    }

    @Suppress("ReturnCount") // 成功、作废、缓存三个出口
    private fun onResult(
        result: ApiResult,
        epoch: Long,
        probe: SendProbe,
    ) {
        val summary = (result as? ApiResult.Success)?.let { WalletJson.summary(it.data) }
        if (summary != null) {
            // 按本页固定身份写缓存（共性第 65 条），不按响应到达时读到的账号
            deps.cache?.write(pageOwner.account, summary, deps.clock())
            lastSuccessAt = deps.clock()
            shownEpoch = epoch
            update { WalletSnapshot(WalletSnapshot.CONTENT, summary, version = it.version + 1) }
            return
        }
        val error = WalletErrors.of(result)
        if (error.kind == WalletError.CANCELLED) {
            // 本请求被发送前限流拒绝（共性第 62 条）：按拒绝原因结算，不看回调时刻的禁用期（可能已到期）
            if (probe.gated(error)) failed(WalletError(WalletError.RETRY_AFTER, "error.42901"), epoch)
            update { it.copy(refreshing = false) }
            return
        }
        failed(error, epoch)
    }

    private fun failed(
        error: WalletError,
        epoch: Long,
    ) {
        if (error.kind == WalletError.RETRY_AFTER) deps.gates.read.start(error.disableMillis)
        if (error.kind == WalletError.FORCE_UPDATE) deps.env.forceUpdate()
        val message = Notice(error.textKey, ++ids, error.traceId, error.showTrace6)
        val shown = s.summary?.takeIf { shownEpoch == epoch }
        val transport = error.kind == WalletError.TRANSPORT
        // 只有传输层失败才用缓存（G22）；5xxxx 不用缓存、不伪装成余额为 0
        val cached = if (transport && shown == null) deps.cache?.read(pageOwner.account) else null
        when {
            transport && shown != null -> {
                // 断网保留旧摘要一律进 stale（共性第 32 条）：更新时间取内存里最近一次成功（或之前已显示的缓存时间），【提现】置灰
                val at = s.staleAt ?: lastSuccessAt ?: deps.clock()
                update { it.copy(staleAt = at, staleNetwork = true, refreshing = false, error = null, version = it.version + 1) }
            }

            cached != null -> {
                shownEpoch = epoch
                update { WalletSnapshot(WalletSnapshot.CONTENT, cached.summary, cached.fetchedAt, version = it.version + 1) }
            }

            shown != null -> {
                // 已有旧数据时读取失败（共性第 30 条）：保留数据，独立显示错误（带 trace6）与【重试】；旧数据不再算「当前」，【提现】置灰
                // 所有保留旧数据的失败分支都标更新时间（共性第 38 条，含 5xxxx、42901、坏数据），中性文案、不说成网络不可用
                val at = s.staleAt ?: lastSuccessAt ?: deps.clock()
                update { it.copy(staleAt = at, staleNetwork = false, refreshing = false, error = message, version = it.version + 1) }
            }

            else -> {
                update { WalletSnapshot(WalletSnapshot.ERROR, error = message, version = it.version + 1) }
            }
        }
    }

    /**
     * 读取被共享限流短路（APP-09 共性第 46 条）：同样结算——保留的摘要转为旧数据状态（最近成功时间的「更新于 HH:mm」、42901 提示与【重试】、
     * 【提现】置灰），没有摘要的进错误态；下一次成功读取后恢复。
     */
    private fun gated() {
        failed(WalletError(WalletError.RETRY_AFTER, "error.42901"), pageOwner.epoch)
    }

    /** 下拉刷新、【重试】与回到本页（禁用期内不发，但按限流结算显示）。 */
    fun refresh(): Boolean {
        if (left) return false
        if (deps.gates.read.active) {
            if (deps.env.loggedIn()) gated()
            return false
        }
        load(refresh = true)
        return true
    }

    // ---------------------------------------------------------------- 按钮（回调带渲染时的版本）

    /** 【提现】：只按摘要判断（G11）；断网缓存态置灰。能不能提由 Withdraw 页的 rules 决定。 */
    fun withdraw(version: Long) {
        val summary = current(version) ?: return
        if (s.staleAt != null || s.error != null || !summary.withdrawEnabled || summary.negativeFen > 0) return
        deps.env.open(WalletRoutes.WITHDRAW, EMPTY)
    }

    fun openRecords() = deps.env.open(WalletRoutes.WITHDRAW_RECORDS, EMPTY)

    fun openOrders() = deps.env.open(WalletRoutes.ORDER_LIST, EMPTY)

    fun openLedger() = deps.env.open(WalletRoutes.LEDGER, EMPTY)

    fun openRiskNotice() = deps.env.open(WalletRoutes.RISK_NOTICE, EMPTY)

    fun openEarnings() {
        if (earningsVisible()) deps.env.open(WalletRoutes.EARNINGS, EMPTY)
    }

    /** 收益看板入口（G20）：flag 为 true 且 Earnings 在本端本版本可用（现在三端 since 都为 null，入口不显示）。 */
    fun earningsVisible(): Boolean {
        val flags = (deps.env.configData()?.get("features") as? JsonObject)?.get("flags") as? JsonObject
        val flag = (flags?.get(EARNINGS_FLAG) as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()
        return EarningsEntry.visible(flag, deps.env.earningsSince(), deps.env.appVersion())
    }

    fun toggleMask() {
        deps.mask?.toggle()
    }

    fun consumeToast(id: Long) {
        if (s.toast?.id == id) update { it.copy(toast = null) }
    }

    private fun current(version: Long): WalletSummary? =
        s.summary?.takeIf { !left && s.version == version && shownEpoch == deps.env.sessionEpoch() }

    /** 会话换代：作废在途请求，同步清掉已显示的摘要（版本加 1，旧按钮回调失效），再按新会话重取。 */
    fun onSessionChanged() {
        if (left) return
        // 新页面状态（已清空）的固定身份在此一次性取得（共性第 65 条）
        pageOwner = FormOwner.of(deps.env)
        shownEpoch = Long.MIN_VALUE
        seq += 1
        cancelAll()
        update { WalletSnapshot(version = it.version + 1) }
        load()
    }

    fun leave() {
        if (left) return
        left = true
        seq += 1
        cancelAll()
    }

    private fun cancelAll() {
        val cancelled = owners.toList()
        owners.clear()
        cancelled.forEach(deps.api::cancelOwned)
    }

    private inline fun update(change: (WalletSnapshot) -> WalletSnapshot) {
        mutable.value = change(mutable.value)
    }

    companion object {
        const val OPERATION = "getWalletSummary"
        const val EARNINGS_FLAG = "earnings.dashboard.visible"
        val EMPTY = JsonObject(emptyMap())
    }
}
