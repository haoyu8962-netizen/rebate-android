package com.couli.feature.orders

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couli.core.orders.OrderConditions
import com.couli.core.orders.OrderDetail
import com.couli.core.orders.OrderQuery
import com.couli.core.orders.OrderRateGate
import com.couli.core.orders.OrderStaleCache
import com.couli.core.orders.PendingTrackService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** App 经它把会话变化、详情回填（G17）、路由参数 scope 交给订单列表（订单 Tab 根只有一份）。 */
interface OrderListHandle {
    fun onSessionChanged()

    fun patch(detail: OrderDetail)

    fun requestScope(scope: String?)

    /** 下拉刷新（列表与待跟单卡一起）。 */
    fun refresh()

    /** 待跟单卡区（只在自购子 Tab 显示）。 */
    val pending: PendingTrackService?
}

/** 列表上打开的半屏筛选。 */
enum class OrderSheet {
    PLATFORM,
    MONTH,
}

/** 列表的滚动锚点：第一个可见项的下标与偏移（像素），以及它是订单卡时的订单号（进程回收后按它找回目标卡，APP-08 共性第 10 条）。 */
data class OrderAnchor(
    val index: Int,
    val offset: Int,
    val orderId: String? = null,
)

/**
 * 订单列表的 ViewModel（订单 Tab 根；03 §3.3）：状态来自 [OrderListController]；搜索框文字、半屏、滚动锚点在这里。
 * 页面状态写进 [saved]（SavedStateHandle；APP-06a 第 17、20 条）：子 Tab、分组、平台、月份、搜索词、输入框文字、半屏、滚动锚点。配置重建与进程被
 * 系统回收后按保存的条件恢复（不看路由参数），重取第一页但不算新一轮，再一次性回到滚动锚点（APP-06a 第 40、43 条）。被详情盖住、切走 Tab 不算
 * 出栈：条件、搜索词与滚动锚点都保留（AdaptBackRestore）。
 *
 * 保存的状态绑定账号归属（[K_OWNER] = 账号哈希，不落明文）：恢复前核对，不是当前账号保存的一律丢弃。会话换代时同步清空搜索框（可能是上一账号的完整订单号）、
 * 关掉半屏、丢弃滚动锚点与待执行的恢复（APP-08 共性第 19 条）。进程恢复的等待任务绑定页面、会话与查询：等待期间用户发起了新查询，或会话换了代，
 * 等待结束后不再用保存的旧条件覆盖（共性第 21 条）。
 */
@Suppress("TooManyFunctions") // 页面每个操作一个入口
class OrderListViewModel(
    initial: OrderListConfig,
    private val saved: SavedStateHandle,
) : ViewModel(),
    OrderListHandle {
    var config: OrderListConfig = initial
        private set

    /** 订单请求的 42901 禁用期：App 给的会话级那一份；没给时本页一份（列表与待跟单卡共用）。 */
    private val rateGate = initial.rateGate ?: OrderRateGate(viewModelScope, initial.clock)

    val controller = OrderListController(initial.api, initial.env, initial.cache, viewModelScope, initial.clock, rateGate)
    override val pending: PendingTrackService? =
        initial.pending?.let {
            PendingTrackService(
                initial.api,
                it,
                viewModelScope,
                initial.clock,
                rateGate,
                initial.closedAttempts,
            )
        }

    /** 保存的状态属于当前账号才恢复（别的账号、没有归属记录的一律丢弃并清掉）。 */
    val restored: Boolean = ownedSavedState()

    private val mutableInput = MutableStateFlow(if (restored) saved[K_INPUT] ?: "" else "")
    val input: StateFlow<String> = mutableInput.asStateFlow()

    private val mutableSheet =
        MutableStateFlow(
            if (restored) saved.get<String>(K_SHEET)?.let { runCatching { OrderSheet.valueOf(it) }.getOrNull() } else null,
        )
    val sheet: StateFlow<OrderSheet?> = mutableSheet.asStateFlow()

    /** 滚动锚点（切走 Tab 再回来、进程回收后恢复用）。 */
    var anchor: OrderAnchor =
        if (restored) OrderAnchor(saved[K_ANCHOR_INDEX] ?: 0, saved[K_ANCHOR_OFFSET] ?: 0, saved[K_ANCHOR_ID]) else OrderAnchor(0, 0)
        private set

    /**
     * 进程回收后还没回到的目标订单（一次性：目标所在页与布局就绪后让目标卡顶对齐，然后清除；目标还没加载时不清除，按保存的查询条件继续翻页；
     * APP-06a 第 40–43 条）。离开时第一个可见项不是订单卡（顶部待跟单卡区等）时没有目标。
     */
    var pendingRestore: String? = if (restored) anchor.orderId else null
        private set

    /** 已为哪一轮查询滚回过顶部（返回本页、重建、恢复时轮次没变，不滚回顶部）。 */
    var seenRound: Long = -1L

    private var gone = false

    /** 进程恢复时等待版本检查的任务（等待结束前为非 null）。 */
    private var restoreJob: Job? = null

    init {
        initial.bindController(this)
        val conditions = savedConditions()
        // 先把条件备好并显示（等待期间不发请求）；之后条件的每次变化写进保存状态（含等待期间用户的改动）
        controller.prepare(config.scope, conditions)
        seenRound = controller.state.value.round
        saved[K_OWNER] = owner()
        viewModelScope.launch { controller.state.collect { persist(it.conditions) } }
        val gate = initial.restoreGate
        if (conditions != null && gate != null) {
            // 进程回收后的自动恢复：等版本检查有结果再取第一页（03 §4.4）。等待结束后再核对：页面还在、用户没有接管、会话没换代
            val epoch = initial.env.sessionEpoch()
            restoreJob =
                viewModelScope.launch {
                    gate()
                    restoreJob = null
                    if (gone || controller.isOpen) return@launch
                    if (config.env.sessionEpoch() != epoch) dropAccountState()
                    start()
                }
        } else {
            start()
        }
    }

    private fun start() {
        controller.open(config.scope)
        seenRound = controller.state.value.round
        if (controller.state.value.scope == OrderQuery.SELF) pending?.refresh()
    }

    /**
     * 用户的操作之后：恢复等待期间用户已发起新查询（筛选、搜索、切子 Tab、刷新）→ 同步取消等待任务、放弃滚动恢复，按页面已打开处理（共性第 21 条）。
     */
    private fun afterUserAction() {
        val job = restoreJob ?: return
        if (!controller.isOpen) return
        restoreJob = null
        job.cancel()
        pendingRestore = null
        if (controller.state.value.scope == OrderQuery.SELF) pending?.refresh()
    }

    /** 当前账号的归属标记（账号哈希；没有会话为 null）。 */
    private fun owner(): String? = config.env.account()?.let(OrderStaleCache.Store::hash)

    private fun ownedSavedState(): Boolean {
        val owned = saved.contains(K_SCOPE) && saved.contains(K_OWNER) && saved.get<String>(K_OWNER) == owner()
        // 不是当前账号保存的（或没有归属记录）：一律不恢复，并清掉（不让上一账号的搜索词留在保存状态里）
        if (!owned) ALL_KEYS.forEach { saved.remove<Any>(it) }
        return owned
    }

    /** 会话换代：清掉上一账号留下的页面状态（搜索框、半屏、滚动锚点与待执行的恢复），保存状态改记新账号。 */
    private fun dropAccountState() {
        controller.onSessionChanged()
        setInput("")
        closeSheet()
        pendingRestore = null
        anchor = OrderAnchor(0, 0)
        if (!gone) {
            saved[K_ANCHOR_INDEX] = 0
            saved[K_ANCHOR_OFFSET] = 0
            saved[K_ANCHOR_ID] = null
            saved[K_OWNER] = owner()
        }
    }

    private fun savedConditions(): OrderConditions? {
        if (!restored) return null
        return OrderConditions(
            scope = saved[K_SCOPE] ?: OrderQuery.SELF,
            group = saved[K_GROUP] ?: OrderQuery.ALL,
            platform = saved[K_PLATFORM],
            month = saved[K_MONTH],
            q = saved[K_Q],
        )
    }

    private fun persist(c: OrderConditions) {
        if (gone) return
        saved[K_OWNER] = owner()
        saved[K_SCOPE] = c.scope
        saved[K_GROUP] = c.group
        saved[K_PLATFORM] = c.platform
        saved[K_MONTH] = c.month
        saved[K_Q] = c.q
    }

    fun update(next: OrderListConfig) {
        config = next
    }

    // ---------------------------------------------------------------- 页面入口

    fun selectScope(scope: String) {
        if (!controller.selectScope(scope)) return
        afterUserAction()
        // 切子 Tab：搜索框一并清空（BR-TEXT-02 细则）
        setInput("")
        if (scope == OrderQuery.SELF) pending?.refresh() else pending?.clear()
    }

    fun selectGroup(group: String): Boolean = controller.selectGroup(group).also { afterUserAction() }

    fun onInput(text: String) = setInput(OrderQuery.limit(text))

    /** 键盘「搜索」：提交（不边输边搜，G11）。 */
    fun submit(): Boolean = controller.submitQuery(mutableInput.value).also { afterUserAction() }

    /** 清空搜索框：清空即发请求（G11）。 */
    fun clearInput(): Boolean {
        setInput("")
        return controller.submitQuery(null).also { afterUserAction() }
    }

    private fun setInput(text: String) {
        mutableInput.value = text
        if (!gone) saved[K_INPUT] = text
    }

    fun openSheet(sheet: OrderSheet) {
        if (controller.state.value.blocked) return
        mutableSheet.value = sheet
        if (!gone) saved[K_SHEET] = sheet.name
    }

    fun closeSheet() {
        mutableSheet.value = null
        if (!gone) saved[K_SHEET] = null
    }

    fun choosePlatform(platform: String?) {
        controller.selectPlatform(platform)
        afterUserAction()
        closeSheet()
    }

    fun chooseMonth(month: String?) {
        controller.selectMonth(month)
        afterUserAction()
        closeSheet()
    }

    /** 下拉刷新：列表与待跟单卡一起刷新。 */
    override fun refresh() {
        val waiting = restoreJob != null
        if (!controller.refresh()) return
        afterUserAction()
        // 接管恢复时 afterUserAction 已拉过待跟单卡
        if (!waiting && controller.state.value.scope == OrderQuery.SELF) pending?.refresh()
    }

    /** 回到前台（含从外跳的平台 App 回来）：重拉待跟单卡，按最新通知权限重算通知行。 */
    fun onStarted() {
        if (controller.state.value.scope == OrderQuery.SELF) pending?.refresh() else pending?.recompute()
    }

    fun onScrolled(next: OrderAnchor) {
        if (gone || pendingRestore != null) return
        anchor = next
        saved[K_ANCHOR_INDEX] = next.index
        saved[K_ANCHOR_OFFSET] = next.offset
        saved[K_ANCHOR_ID] = next.orderId
    }

    fun restoreDone() {
        pendingRestore = null
    }

    // ---------------------------------------------------------------- App 入口（OrderListHandle）

    override fun onSessionChanged() {
        // 同步清掉上一账号的搜索框（可能是完整订单号）、半屏、锚点与待执行的恢复；条件回到默认并按新会话重取（共性第 19 条）
        dropAccountState()
        pending?.onSessionChanged()
        if (controller.isOpen && controller.state.value.scope == OrderQuery.SELF) pending?.refresh()
    }

    override fun patch(detail: OrderDetail) = controller.patch(detail)

    override fun requestScope(scope: String?) {
        val before = controller.state.value.scope
        controller.requestScope(scope)
        val after = controller.state.value.scope
        if (after != before) {
            afterUserAction()
            setInput("")
            if (after == OrderQuery.SELF) pending?.refresh() else pending?.clear()
        }
    }

    override fun onCleared() {
        gone = true
        controller.leave()
        pending?.leave()
        super.onCleared()
    }

    private companion object {
        const val K_SCOPE = "orders.scope"
        const val K_GROUP = "orders.group"
        const val K_PLATFORM = "orders.platform"
        const val K_MONTH = "orders.month"
        const val K_Q = "orders.q"
        const val K_INPUT = "orders.input"
        const val K_SHEET = "orders.sheet"
        const val K_ANCHOR_INDEX = "orders.anchor_index"
        const val K_ANCHOR_OFFSET = "orders.anchor_offset"
        const val K_ANCHOR_ID = "orders.anchor_order_id"

        /** 保存状态的账号归属（账号哈希）。 */
        const val K_OWNER = "orders.owner"

        val ALL_KEYS =
            listOf(K_SCOPE, K_GROUP, K_PLATFORM, K_MONTH, K_Q, K_INPUT, K_SHEET, K_ANCHOR_INDEX, K_ANCHOR_OFFSET, K_ANCHOR_ID, K_OWNER)
    }
}
