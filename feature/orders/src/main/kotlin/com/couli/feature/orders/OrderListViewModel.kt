package com.couli.feature.orders

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couli.core.network.IdentityVerdict
import com.couli.core.network.SessionIdentity
import com.couli.core.orders.OrderConditions
import com.couli.core.orders.OrderDetail
import com.couli.core.orders.OrderQuery
import com.couli.core.orders.OrderRateGate
import com.couli.core.orders.OrderRestoreRecord
import com.couli.core.orders.OrderRestoreStore
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

    /** 会话换代，带 App 本次检查已确认的身份快照（第 1 轮修复 R1-03）；缺省同 [onSessionChanged]。 */
    fun onSessionChanged(identity: SessionIdentity?) = onSessionChanged()

    /**
     * 撤回同意、进入基本模式、切换环境（APP-08 共性第 36、38 条）：不论会话通知是否已到，同步清掉页面状态（搜索框与保存状态里可能是完整订单号
     * 的搜索词、半屏、滚动锚点与待执行的恢复）、已显示的结果与待跟单卡，作废在途请求。
     */
    fun onCleanup()

    fun patch(detail: OrderDetail)

    fun requestScope(scope: String?)

    /** 下拉刷新（列表与待跟单卡一起）。 */
    fun refresh()

    /**
     * 会话判据没变、身份已确认（F-X-19 补充三 ②；FU-AND-0717 第 3 轮统一的页面恢复入口）：[identity] 是 AppOrders 这次检查的那份快照。本页按它判定——
     * 未知期建的 / 分区第一次确认的接管归属并完成尚未被用户接管的恢复，加载因身份中止的补取，已确认换代按它重置。
     */
    fun onIdentityConfirmed(identity: SessionIdentity) = Unit

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
 * 页面的渲染身份（R2-01，APP-09 共性第 63、65 条）：本页固定归属的会话世代与账号，加页面代次（会话换代、清理、作废旧列表时加 1）。Compose 在
 * 组合时取出这份普通值交给每个回调；回调执行时只比较（仍是当前这份、当前会话仍等于它），不在执行时读当前会话来绑定。
 */
data class OrderPageTicket(
    val epoch: Long,
    val account: String?,
    val page: Long,
)

/**
 * 订单列表的 ViewModel（订单 Tab 根；03 §3.3）：状态来自 [OrderListController]；搜索框文字、半屏、滚动锚点在这里。
 * 页面状态（APP-06a 第 17、20 条）分两处保存：系统保存状态（SavedStateHandle）只存不含个人数据的子 Tab、分组、半屏、锚点位置，以及恢复记录的随机
 * 引用；搜索词、输入框文字、平台与月份、锚点订单与账号归属（账号哈希）存在 [OrderListConfig.restoreStore]（能确认写入与删除结果的文件，撤回 /
 * 基本模式 / 切环境时由 App 的清理器删除并确认；R1-03、R2-02）。配置重建与进程被系统回收后按保存的条件恢复（不看路由参数），重取第一页但不算
 * 新一轮，再一次性回到滚动锚点（APP-06a 第 40、43 条）。被详情盖住、切走 Tab 不算出栈：条件、搜索词与滚动锚点都保留（AdaptBackRestore）。
 *
 * 恢复前核对：引用对应的记录存在、清理代次没变、记录的账号归属是本页的固定归属，否则一律不恢复并清掉保存状态。会话换代时同步清空搜索框（可能是
 * 上一账号的完整订单号）、关掉半屏、丢弃滚动锚点与待执行的恢复（APP-08 共性第 19 条）。进程恢复的等待任务绑定页面、会话与查询：等待期间用户
 * 发起了新查询，或会话换了代，等待结束后不再用保存的旧条件覆盖（共性第 21 条）。
 *
 * 页面上每个用户入口都带渲染身份 [OrderPageTicket]（R2-01）：失效的旧回调（换号、清理之后）不写内存、不写保存状态与恢复记录、不发请求。
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

    /** 系统保存状态（只给本模块的测试观察：恢复期间是否改动了条件、锚点与引用）。 */
    internal val savedState: SavedStateHandle get() = saved

    /** 恢复记录的落盘（App 给；没给时个人数据不保存、不恢复）。 */
    private val store = initial.restoreStore

    /** 本页恢复记录的引用（随机；系统保存状态里只有它）。 */
    private var ref: String? = null

    /** 本页取得的清理代次（清理之后要先清空内存状态再取新的，旧代次写不进）。 */
    private var boundEpoch: Long = 0L

    /**
     * 本页固定归属时的持久化登录世代绑定（F-AND-29：与 [boundEpoch] 同时取；写入时此刻的世代已不是它就不写）。第 2 轮评审 R2-04：区分「没接来源」与
     * 「绑定时读不出」；读不出的页面之后一律写不进（世代恢复了也不补绑，要等下一次固定身份重新取）。
     */
    private var boundLoginEpoch: OrderRestoreStore.LoginBinding = OrderRestoreStore.LoginBinding.Unbound

    /** 最近一次确认写入的记录（内容没变不重写）。 */
    private var lastWritten: OrderRestoreRecord? = null

    private var gone = false

    /** 本页的页面代次（会话换代、清理、作废旧列表时加 1）。 */
    private var page = 0L

    private val mutableTicket = MutableStateFlow(OrderPageTicket(controller.ownerEpoch, controller.ownerAccount, page))

    /** 渲染身份（Compose 组合时取出普通值交给回调）。 */
    val ticket: StateFlow<OrderPageTicket> = mutableTicket.asStateFlow()

    /**
     * 本页固定身份是在「身份暂时未知」时取得的（F-X-19 补充 / 补充三，FU-AND-0717）：恢复决策延后——不读、不删、不写恢复记录，也不改系统保存状态里的
     * 条件、锚点与引用；身份已知时由 AppOrders 经 [onIdentityConfirmed] 接管已确认身份再决策（未知不等于没有会话，不按「别的账号」丢掉）。
     */
    private var deferred =
        !controller.ownerKnown ||
            // 第 1 轮修复 R1-01：账号已确认、持久化世代缺失（合法旧会话）——在线照常加载，恢复记录不读不删不写
            (
                controller.ownerAccount != null &&
                    initial.restoreStore?.bindingFor(controller.ownerLoginEpoch) == OrderRestoreStore.LoginBinding.Unknown
            )

    /** 恢复等待已结束、但当时身份暂时未知：条件与锚点原样保留，等已确认的身份快照再决策（[finishRestoreWait]）。 */
    private var awaitingIdentity = false

    /**
     * 恢复决策延后期间（[deferred]）用户已接管页面（第 2 轮评审 R2-05，FU-AND-0717 第 3 轮）：切子 Tab、筛选、搜索、输入、下拉、触摸、滚动——从延后阶段
     * 一开始就记。身份确认后只补身份与必要加载，旧记录不再覆盖用户的条件、输入与滚动位置。
     */
    private var takenOver = false

    /** 进入延后时的查询轮次（之后轮次变了即算用户已发起新查询）。 */
    private var deferRound = Long.MIN_VALUE

    /** 测试观察：恢复等待已结束、正等已确认的身份快照（R1-02）。 */
    internal val restoreAwaitingIdentity: Boolean get() = awaitingIdentity

    /** 恢复决策仍在延后（身份 / 分区还没确认；测试观察）。 */
    val restoreDeferred: Boolean get() = deferred

    /** 恢复记录：属于本页的固定归属、清理之后没被删掉才有（别的账号、没有记录、清理之后的旧引用一律丢弃并清掉保存状态）。 */
    private var record: OrderRestoreRecord? = ownedRecord()

    var restored: Boolean = record != null
        private set

    private val mutableInput = MutableStateFlow(record?.input.orEmpty())
    val input: StateFlow<String> = mutableInput.asStateFlow()

    private val mutableSheet =
        MutableStateFlow(
            if (restored) saved.get<String>(K_SHEET)?.let { runCatching { OrderSheet.valueOf(it) }.getOrNull() } else null,
        )
    val sheet: StateFlow<OrderSheet?> = mutableSheet.asStateFlow()

    /** 滚动锚点（切走 Tab 再回来、进程回收后恢复用）。 */
    var anchor: OrderAnchor =
        if (restored) OrderAnchor(saved[K_ANCHOR_INDEX] ?: 0, saved[K_ANCHOR_OFFSET] ?: 0, record?.anchorOrderId) else OrderAnchor(0, 0)
        private set

    /**
     * 进程回收后还没回到的目标订单（一次性：目标所在页与布局就绪后让目标卡顶对齐，然后清除；目标还没加载时不清除，按保存的查询条件继续翻页；
     * APP-06a 第 40–43 条）。离开时第一个可见项不是订单卡（顶部待跟单卡区等）时没有目标。
     */
    var pendingRestore: String? = if (restored) anchor.orderId else null
        private set

    /** 一次性恢复是怎么结束的（F-X-18 / F-X-20 观测：[RESTORE_LANDED]、[RESTORE_NOT_CONFIRMED]、[RESTORE_TAKEN_OVER]、翻页中 [RESTORE_PAGING]）。 */
    var restoreEnd: String? = null
        internal set

    /** 已为哪一轮查询滚回过顶部（返回本页、重建、恢复时轮次没变，不滚回顶部）。 */
    var seenRound: Long = -1L

    /** 进程恢复时等待版本检查的任务（等待结束前为非 null）。 */
    private var restoreJob: Job? = null

    init {
        initial.bindController(this)
        // 执行器发现会话已换代、先作废了旧列表（迟到的翻页、复制等入口；R1-01）：同步清掉页面状态与待跟单卡
        controller.onInvalidated = {
            dropPageState()
            pending?.onSessionChanged()
            if (controller.isOpen && controller.state.value.scope == OrderQuery.SELF) refreshPending()
        }
        // 执行器按一份已确认快照接管了归属（未知期建的页、分区第一次确认）：在这份身份下完成恢复决策（FU-AND-0717 第 3 轮）
        controller.onAdopted = ::onAdopted
        val conditions = savedConditions()
        // 先把条件备好并显示（等待期间不发请求）；之后条件的每次变化写进保存状态（含等待期间用户的改动）
        controller.prepare(config.scope, conditions)
        seenRound = controller.state.value.round
        persistRecord()
        viewModelScope.launch {
            var round = controller.state.value.round
            controller.state.collect {
                // F-X-18 补充（FU-AND-0717）：开始了新一轮查询（换条件、刷新、切子 Tab）→ 同步放弃待执行的恢复、清掉旧锚点，第一次持久化就是「新条件 + 空锚点」
                if (it.round != round) {
                    round = it.round
                    dropAnchorForNewRound()
                }
                persist(it.conditions)
            }
        }
        val gate = initial.restoreGate
        if (conditions != null && gate != null) {
            // 进程回收后的自动恢复：等版本检查有结果再取第一页（03 §4.4）。等待结束后再核对：页面还在、用户没有接管、会话没换代
            // 与恢复记录核对归属时用的是同一份固定归属（构造时一次性取，之后只比较）；等待结束时的复核取一份完整身份快照（第 1 轮修复 R1-02）
            restoreJob =
                viewModelScope.launch {
                    gate()
                    restoreJob = null
                    finishRestoreWait(config.env.identity())
                }
        } else {
            start()
        }
        deferRound = controller.state.value.round
    }

    /**
     * 恢复等待结束（第 1 轮修复 R1-02）：按一份完整身份快照 [identity] 复核归属，不分别读账号与世代——
     * - 快照未知（单次读取失败、屏障未过）：保留条件、输入、锚点与恢复记录，延后决策（[awaitingIdentity]），等 App 交来已确认的快照；
     * - 确认归属变了（换号窗口，F-AND-30）：零请求，丢掉旧账号恢复的条件与页面状态，等会话通知到达后按新会话默认条件打开；
     * - 归属没变：按保存的条件打开并回到锚点。
     */
    @Suppress("ReturnCount") // 已结束、未知、换号三个出口
    private fun finishRestoreWait(identity: SessionIdentity) {
        if (gone || controller.isOpen) return
        if (!identity.known) {
            awaitingIdentity = true
            return
        }
        awaitingIdentity = false
        if (controller.ownerChanged(identity)) {
            controller.openOnSessionNotice(config.scope)
            dropPageState()
            return
        }
        // 同一份快照贯穿打开流程（第 2 轮评审 R2-01）：打开时的归属检查不再另读账号
        start(identity)
    }

    /** 待跟单卡拉一次：带本页固定归属的会话世代（F-AND-30：由调用方传入固定世代，不在发送时读当前会话来绑定）。 */
    private fun refreshPending() {
        pending?.refresh(controller.ownerEpoch)
    }

    private fun start(identity: SessionIdentity? = null) {
        controller.open(config.scope, identity = identity)
        seenRound = controller.state.value.round
        if (controller.state.value.scope == OrderQuery.SELF) refreshPending()
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
        if (controller.state.value.scope == OrderQuery.SELF) refreshPending()
    }

    /** 本页固定归属的账号标记（账号哈希；没有会话为 null）：与执行器的请求、已显示数据同一份，不在写保存状态时再读当前会话。 */
    private fun owner(): String? = controller.ownerAccount?.let(OrderStaleCache.Store::hash)

    /**
     * 读恢复记录（构造时一次）：旧版本留在系统保存状态里的个人数据键一律删掉、不恢复；引用对应的记录存在、清理没有待完成、账号归属一致才恢复。
     * 之后本页用这份引用（没恢复就换新的），并删掉别的旧记录。
     */
    private fun ownedRecord(): OrderRestoreRecord? {
        LEGACY_KEYS.forEach { saved.remove<Any>(it) }
        val candidate = saved.get<String>(K_REF)
        if (deferred) {
            // 身份暂时未知（F-X-19 补充）：不读、不删（不 retainOnly）、不写；引用与保存状态原样保留，等身份已知再决策
            ref = candidate
            boundEpoch = store?.epoch ?: 0L
            boundLoginEpoch = OrderRestoreStore.LoginBinding.Unknown
            lastWritten = null
            return null
        }
        // 绑定取自执行器那一份身份快照（F-X-19 补充二：不另读此刻的世代）
        val binding = store?.bindingFor(controller.ownerLoginEpoch) ?: OrderRestoreStore.LoginBinding.Unbound
        val found = if (saved.contains(K_SCOPE)) store?.read(candidate, binding)?.takeIf { it.owner == owner() } else null
        if (found == null) ALL_KEYS.forEach { saved.remove<Any>(it) }
        ref = if (found != null) candidate else store?.newRef()
        store?.retainOnly(ref)
        boundEpoch = store?.epoch ?: 0L
        boundLoginEpoch = binding
        lastWritten = found
        return found
    }

    override fun onIdentityConfirmed(identity: SessionIdentity) {
        if (gone || !identity.known) return
        when {
            // 恢复等待结束时身份未知、此刻已确认（R1-02）：按这份快照完成当时延后的决策
            awaitingIdentity -> finishRestoreWait(identity)

            // 已确认换代：与会话换代同一出口（清本页状态、按这份快照重置）
            controller.verdict(identity) == IdentityVerdict.CHANGED -> onSessionChanged(identity)

            // 第一次确认（接管 → [onAdopted]）、加载因身份中止的补取都在执行器的统一入口里
            else -> controller.onIdentityConfirmed(identity)
        }
    }

    /**
     * 执行器按一份已确认快照接管了归属（FU-AND-0717 第 3 轮，R2-02 / R2-05）：恢复决策还在延后时，在这份身份下决策——
     * - 用户在延后期间没有接管（没有切子 Tab、筛选、搜索、输入、下拉、触摸、滚动，查询轮次没变）：按构造时同一套核对读恢复记录，有就恢复条件、输入与锚点并
     *   按保存的条件重开；没有就保留当前列表（不清空、不闪回顶部），加载因身份中止的补取；
     * - 已被接管（含这次接管就发生在用户入口里，[byUser]）：只绑定恢复记录的引用（之后照常保存），不读旧内容覆盖用户的条件、输入与滚动；加载因身份中止的
     *   补取（用户入口随后自己的加载会作废它）。
     * 不在延后的（分区已知、只是账号第一次确认等）只做补取。
     */
    private fun onAdopted(
        identity: SessionIdentity,
        byUser: Boolean,
    ) {
        if (gone) return
        page += 1
        mutableTicket.value = OrderPageTicket(controller.ownerEpoch, controller.ownerAccount, page)
        // 不在延后；或分区仍未知（账号刚确认、合法旧会话缺世代）：在线照常补取，恢复决策继续延后（恢复记录不读不删不写）
        if (!deferred || store?.bindingFor(controller.ownerLoginEpoch) == OrderRestoreStore.LoginBinding.Unknown) {
            controller.resumeAfterAdopt()
            return
        }
        deferred = false
        restoreJob?.cancel()
        restoreJob = null
        val took = byUser || takenOver || controller.state.value.round != deferRound
        if (took) {
            keepUserStateOnAdopt()
            controller.resumeAfterAdopt()
        } else {
            restoreOnAdopt()
        }
        persist(controller.state.value.conditions)
        if (!controller.isOpen) {
            start(identity)
        } else {
            seenRound = controller.state.value.round
            if (controller.state.value.scope == OrderQuery.SELF) refreshPending()
        }
    }

    /** 没被接管：已确认身份下的恢复决策（与构造时同一套核对：引用、清理代次、账号归属与登录世代），有记录就恢复条件、输入与锚点并重开。 */
    private fun restoreOnAdopt() {
        record = ownedRecord()
        restored = record != null
        mutableInput.value = record?.input.orEmpty()
        mutableSheet.value = if (restored) saved.get<String>(K_SHEET)?.let { runCatching { OrderSheet.valueOf(it) }.getOrNull() } else null
        anchor =
            if (restored) OrderAnchor(saved[K_ANCHOR_INDEX] ?: 0, saved[K_ANCHOR_OFFSET] ?: 0, record?.anchorOrderId) else OrderAnchor(0, 0)
        pendingRestore = if (restored) anchor.orderId else null
        if (restored) controller.reopenAdopted(config.scope, savedConditions()) else controller.resumeAfterAdopt()
    }

    /**
     * 接管后保留用户的状态（R2-05）：读恢复记录只为绑定引用与清理代次（属于本页归属的沿用原引用，否则换新引用并删掉别的旧记录），内容不用；之后按用户此刻
     * 的条件、输入、半屏与锚点保存。
     */
    private fun keepUserStateOnAdopt() {
        ownedRecord()
        record = null
        restored = false
        lastWritten = null
        pendingRestore = null
        if (gone) return
        saved[K_SHEET] = mutableSheet.value?.name
        saved[K_ANCHOR_INDEX] = anchor.index
        saved[K_ANCHOR_OFFSET] = anchor.offset
    }

    /** 恢复决策延后期间的用户操作（R2-05）：从延后阶段一开始就记接管。 */
    private fun noteUserAction() {
        if (deferred || awaitingIdentity) takenOver = true
    }

    /** 列表上的触摸按下 / 用户滚动（页面入口；R1-06 的恢复接管之外，延后期间同样记接管，R2-05）。 */
    fun onUserTouch(t: OrderPageTicket) {
        if (live(t)) noteUserAction()
    }

    /**
     * 把本页此刻的个人数据写进恢复记录（内容没变不重写）。写不进（清理代次已变、待清理、I/O 失败）时从系统保存状态里去掉引用：之后恢复不出来，
     * 不会恢复出一份旧内容。
     */
    @Suppress("ReturnCount") // 页面已清理、没有落盘、没有引用、内容没变逐项
    private fun persistRecord(conditions: OrderConditions = controller.state.value.conditions) {
        if (gone || deferred) return
        val target = store ?: return
        val id = ref ?: return
        val next = OrderRestoreRecord(owner(), conditions.platform, conditions.month, conditions.q, mutableInput.value, anchor.orderId)
        if (next == lastWritten && saved.contains(K_REF)) return
        if (target.write(id, boundEpoch, next, boundLoginEpoch)) {
            lastWritten = next
            saved[K_REF] = id
        } else {
            lastWritten = null
            saved.remove<String>(K_REF)
        }
    }

    /** 会话换代：清掉上一账号留下的页面状态（搜索框、半屏、滚动锚点与待执行的恢复），恢复记录改记新账号。 */
    private fun dropAccountState(
        cleanup: Boolean = false,
        identity: SessionIdentity? = null,
    ) {
        if (cleanup) controller.onCleanup() else controller.onSessionChanged(identity)
        dropPageState()
    }

    /**
     * 清掉页面状态（搜索框、半屏、滚动锚点与待执行的恢复）：先换渲染身份（旧回调从此失效），内存清空之后才取新的清理代次，恢复记录改记本页此刻的
     * 固定归属与空内容。
     */
    private fun dropPageState() {
        page += 1
        mutableTicket.value = OrderPageTicket(controller.ownerEpoch, controller.ownerAccount, page)
        mutableInput.value = ""
        closeSheetNow()
        pendingRestore = null
        anchor = OrderAnchor(0, 0)
        if (deferred && controller.ownerKnown) {
            // 未知期间建的页遇到真正的会话换代 / 清理、归属已按新会话重取：旧会话的记录不再恢复（换新引用、删掉旧记录），之后按新归属照常保存
            deferred = false
            ALL_KEYS.forEach { saved.remove<Any>(it) }
            ref = store?.newRef()
            store?.retainOnly(ref)
        }
        if (!gone && !deferred) {
            saved[K_ANCHOR_INDEX] = 0
            saved[K_ANCHOR_OFFSET] = 0
            boundEpoch = store?.epoch ?: 0L
            // 绑定取自执行器重取的那一份身份快照（F-X-19 补充二）
            boundLoginEpoch = store?.bindingFor(controller.ownerLoginEpoch) ?: OrderRestoreStore.LoginBinding.Unbound
            persistRecord()
        }
    }

    /** 新一轮查询：恢复任务作废、锚点清零（随后的持久化写的是新条件 + 空锚点；进程此刻被杀也不会把旧锚点套到新结果上）。 */
    private fun dropAnchorForNewRound() {
        pendingRestore = null
        anchor = OrderAnchor(0, 0)
        if (!gone && !deferred) {
            saved[K_ANCHOR_INDEX] = 0
            saved[K_ANCHOR_OFFSET] = 0
        }
    }

    private fun savedConditions(): OrderConditions? {
        val r = record ?: return null
        return OrderConditions(
            scope = saved[K_SCOPE] ?: OrderQuery.SELF,
            group = saved[K_GROUP] ?: OrderQuery.ALL,
            platform = r.platform,
            month = r.month,
            q = r.q,
        )
    }

    private fun persist(c: OrderConditions) {
        if (gone || deferred) return
        saved[K_SCOPE] = c.scope
        saved[K_GROUP] = c.group
        persistRecord(c)
    }

    fun update(next: OrderListConfig) {
        config = next
    }

    /**
     * 渲染身份 [t] 仍有效：页面还在、仍是当前这份身份、当前会话没有确认换代（会话已换代而通知还没到时也不执行）。只比较，不赋值。
     * FU-AND-0717 第 3 轮：只取**一份**快照按 [SessionIdentity.verdictFor] 判（不分别读世代与账号）——已确认换代才算失效；身份暂时未知不让用户操作失效
     * （R2-05：延后期间的操作照常生效并记为接管）；未知期建的页（渲染身份没有账号）第一次确认时仍有效，由执行器入口按同一套判定接管。
     */
    fun live(t: OrderPageTicket): Boolean =
        !gone && t == mutableTicket.value &&
            config.env.identity().verdictFor(t.account, t.epoch, null, trackPartition = false) != IdentityVerdict.CHANGED

    // ---------------------------------------------------------------- 页面入口（都带渲染身份）

    fun selectScope(
        t: OrderPageTicket,
        scope: String,
    ) {
        if (!live(t)) return
        noteUserAction()
        if (!controller.selectScope(scope)) return
        afterUserAction()
        // 切子 Tab：搜索框一并清空（BR-TEXT-02 细则）
        setInput("")
        if (scope == OrderQuery.SELF) refreshPending() else pending?.clear()
    }

    fun selectGroup(
        t: OrderPageTicket,
        group: String,
    ): Boolean {
        if (!live(t)) return false
        noteUserAction()
        return controller.selectGroup(group).also { afterUserAction() }
    }

    /** 搜索框输入（R2-01：旧页面的输入回调不写进换代后的页面与恢复记录）。 */
    fun onInput(
        t: OrderPageTicket,
        text: String,
    ) {
        if (!live(t)) return
        noteUserAction()
        setInput(OrderQuery.limit(text))
    }

    /** 键盘「搜索」：提交（不边输边搜，G11）。 */
    fun submit(t: OrderPageTicket): Boolean {
        if (!live(t)) return false
        noteUserAction()
        return controller.submitQuery(mutableInput.value).also { afterUserAction() }
    }

    /** 清空搜索框：清空即发请求（G11）。 */
    fun clearInput(t: OrderPageTicket): Boolean {
        if (!live(t)) return false
        noteUserAction()
        setInput("")
        return controller.submitQuery(null).also { afterUserAction() }
    }

    private fun setInput(text: String) {
        mutableInput.value = text
        persistRecord()
    }

    fun openSheet(
        t: OrderPageTicket,
        sheet: OrderSheet,
    ) {
        if (!live(t) || controller.state.value.blocked) return
        noteUserAction()
        mutableSheet.value = sheet
        if (!gone) saved[K_SHEET] = sheet.name
    }

    fun closeSheet(t: OrderPageTicket) {
        if (live(t)) closeSheetNow()
    }

    private fun closeSheetNow() {
        mutableSheet.value = null
        if (!gone) saved[K_SHEET] = null
    }

    fun choosePlatform(
        t: OrderPageTicket,
        platform: String?,
    ) {
        if (!live(t)) return
        noteUserAction()
        controller.selectPlatform(platform)
        afterUserAction()
        closeSheetNow()
    }

    fun chooseMonth(
        t: OrderPageTicket,
        month: String?,
    ) {
        if (!live(t)) return
        noteUserAction()
        controller.selectMonth(month)
        afterUserAction()
        closeSheetNow()
    }

    /** 下拉手势（页面入口）：渲染身份有效才刷新。 */
    fun pullRefresh(t: OrderPageTicket) {
        if (!live(t)) return
        noteUserAction()
        // 加载因身份中止（未知期建的页等）：下拉也走【重试】的身份恢复（一份快照）
        if (controller.identityAborted) controller.retry() else refresh()
    }

    /** 下拉刷新：列表与待跟单卡一起刷新（App 入口与页面手势共用）。 */
    override fun refresh() {
        noteUserAction()
        val waiting = restoreJob != null
        if (!controller.refresh()) return
        afterUserAction()
        // 接管恢复时 afterUserAction 已拉过待跟单卡
        if (!waiting && controller.state.value.scope == OrderQuery.SELF) refreshPending()
    }

    /** 整页失败 / 翻页失败的【重试】。 */
    fun retry(t: OrderPageTicket): Boolean = live(t) && controller.retry()

    /** 上拉加载更多（滚动触发、恢复补页）。 */
    fun loadMore(t: OrderPageTicket): Boolean = live(t) && controller.loadMore()

    /** 整卡点击打开详情。 */
    fun openDetail(
        t: OrderPageTicket,
        orderId: String,
    ): Boolean = live(t) && controller.openDetail(orderId)

    /** 卡内【复制】：渲染身份 + 渲染时的轮次与已显示数据身份（APP-08 共性第 41、44 条）。 */
    fun copy(
        t: OrderPageTicket,
        orderId: String,
        orderNo: String,
        round: Long,
        identity: OrderListIdentity?,
    ): Boolean = live(t) && controller.copy(orderId, orderNo, round, identity)

    /** 回到前台（含从外跳的平台 App 回来）：重拉待跟单卡，按最新通知权限重算通知行。 */
    fun onStarted() {
        if (controller.state.value.scope == OrderQuery.SELF) refreshPending() else pending?.recompute()
    }

    /** 记录滚动锚点（渲染身份有效才记；锚点订单进恢复记录）。 */
    fun onScrolled(
        t: OrderPageTicket,
        next: OrderAnchor,
    ) {
        if (!live(t) || pendingRestore != null || deferred) return
        anchor = next
        saved[K_ANCHOR_INDEX] = next.index
        saved[K_ANCHOR_OFFSET] = next.offset
        persistRecord()
    }

    fun restoreDone() {
        pendingRestore = null
    }

    // ---------------------------------------------------------------- App 入口（OrderListHandle）

    override fun onSessionChanged() = onSessionChanged(null)

    /**
     * 会话换代（[identity] 是 App 本次检查已确认的身份快照，第 1 轮修复 R1-03：控制器按它比较账号 + 持久化世代、换代时重新绑定）：同步清掉上一账号的
     * 搜索框（可能是完整订单号）、半屏、锚点与待执行的恢复；条件回到默认并按新会话重取（共性第 19 条）。
     */
    override fun onSessionChanged(identity: SessionIdentity?) {
        // 一份快照（没给时本页取一份）；未知不决策——不清本页状态（FU-AND-0717 第 3 轮）
        val id = identity ?: config.env.identity()
        if (!id.known) return
        dropAccountState(identity = id)
        pending?.onSessionChanged()
        // 恢复等待结束时身份未知、之后确认已换代：不再恢复旧条件，按新会话默认条件打开
        if (awaitingIdentity) {
            awaitingIdentity = false
            start()
        }
        if (controller.isOpen && controller.state.value.scope == OrderQuery.SELF) refreshPending()
    }

    /** 撤回 / 基本模式 / 切环境（AppOrders 的通知）：只清本页内存状态与旧回调；落盘的恢复记录已由 App 的清理器删除。 */
    override fun onCleanup() {
        dropAccountState(cleanup = true)
        pending?.onSessionChanged()
    }

    override fun patch(detail: OrderDetail) = controller.patch(detail)

    override fun requestScope(scope: String?) {
        noteUserAction()
        val before = controller.state.value.scope
        controller.requestScope(scope)
        val after = controller.state.value.scope
        if (after != before) {
            afterUserAction()
            setInput("")
            if (after == OrderQuery.SELF) refreshPending() else pending?.clear()
        }
    }

    override fun onCleared() {
        gone = true
        controller.leave()
        pending?.leave()
        super.onCleared()
    }

    companion object {
        const val RESTORE_LANDED = "aligned"
        const val RESTORE_NOT_CONFIRMED = "not_confirmed"
        const val RESTORE_TAKEN_OVER = "user_took_over"
        const val RESTORE_PAGING = "paging"
        private const val K_SCOPE = "orders.scope"
        private const val K_GROUP = "orders.group"
        private const val K_SHEET = "orders.sheet"
        private const val K_ANCHOR_INDEX = "orders.anchor_index"
        private const val K_ANCHOR_OFFSET = "orders.anchor_offset"

        /** 恢复记录的引用（随机，不含个人数据）。 */
        private const val K_REF = "orders.restore_ref"

        private val ALL_KEYS = listOf(K_SCOPE, K_GROUP, K_SHEET, K_ANCHOR_INDEX, K_ANCHOR_OFFSET, K_REF)

        /** 旧版本放在系统保存状态里的个人数据与归属键：一律删掉、不恢复。 */
        private val LEGACY_KEYS =
            listOf(
                "orders.platform",
                "orders.month",
                "orders.q",
                "orders.input",
                "orders.anchor_order_id",
                "orders.owner",
                "orders.restore_gen",
            )
    }
}
