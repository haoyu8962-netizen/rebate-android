package com.couli.feature.wallet

import com.couli.core.network.ApiCall
import com.couli.core.network.ApiResult
import com.couli.core.network.CallOwnership
import com.couli.core.network.IdentityVerdict
import com.couli.core.network.LocalRejectReason
import com.couli.core.network.SessionIdentity
import com.couli.core.network.percentEncode
import com.couli.core.wallet.SendProbe
import com.couli.core.wallet.TradeNoRow
import com.couli.core.wallet.WalletError
import com.couli.core.wallet.WalletErrors
import com.couli.core.wallet.WalletJson
import com.couli.core.wallet.WalletRoutes
import com.couli.core.wallet.Withdrawal
import com.couli.core.wallet.WithdrawalText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** 提现记录页的一次状态快照。 */
data class RecordsSnapshot(
    val phase: String = LOADING,
    val items: List<Withdrawal> = emptyList(),
    val nextCursor: String? = null,
    val loadingMore: Boolean = false,
    val moreError: Notice? = null,
    val expanded: Set<String> = emptySet(),
    /** 展开后取到的详情（带 channel_order_id）。 */
    val details: Map<String, Withdrawal> = emptyMap(),
    val error: Notice? = null,
    val refreshing: Boolean = false,
    val toast: Notice? = null,
    val version: Long = 0,
) {
    companion object {
        const val LOADING = "loading"
        const val CONTENT = "content"
        const val EMPTY = "empty"
        const val ERROR = "error"
    }
}

/**
 * 提现记录执行器（WithdrawRecords 路由，`withdrawal_id?`；BR-TEXT-06、BR-WDR-25，G08、G27、G30）：
 * - 进入、下拉、回到本页各取一次，不轮询；翻页按 next_cursor；
 * - 带 withdrawal_id 打开时另取 GET /v1/withdrawals/{id}：成功把这张单放在列表最前并展开；30701 只显示列表，不提示（「不是本人的按不存在处理」）；
 * - 展开一张单时取详情（交易流水号只在详情有），PAID_* 且 channel_order_id 非 null 才显示流水号与【复制】，复制只走统一写入口（G27）；
 * - 金额不受隐藏开关影响（G23）。
 * 请求绑定页面与会话世代；出栈后作废。只在主线程使用。
 */
@Suppress("TooManyFunctions", "ReturnCount", "ComplexCondition") // 页面每个入口一个函数；逐项提前返回
class WithdrawRecordsController(
    private val deps: WalletDeps,
    private val scope: CoroutineScope,
    private val focusId: String?,
) {
    private var left = false
    private var round = 0L
    private var serial = 0L
    private val owners = mutableListOf<CallOwnership>()
    private var shownEpoch = Long.MIN_VALUE

    /**
     * 本页的固定身份（APP-09 共性第 65 条）：构造时一次性取得，只在会话换代通知里随清空一起重建；列表、翻页、详情请求都只用它（世代 + 网络层
     * expectedAccount），不读取执行时的当前会话——令牌已换、清空通知还没执行时，不会以新账号的身份取数据拼进本页。
     */
    private var pageOwner = FormOwner.of(deps.env)

    /**
     * 新一轮第一页还没回来（APP-09 共性第 20 条）：期间禁止翻页，旧游标不得以新一轮的名义发出；第一页成功后才发布新游标，失败则旧列表与旧游标照旧配对。
     */
    private var firstPagePending = false

    /** 每张单最新一次详情请求的序号与归属（共性第 20 条）：同一 withdrawal_id 只收最新请求的响应。 */
    private var detailCounter = 0L
    private val detailSeq = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val detailOwners = mutableMapOf<String, CallOwnership>()

    /**
     * 本轮（[round]）已经接受的详情（APP-09 共性第 34 条）：列表与详情统一接收顺序——本轮第一页回来之前已接受的详情是在列表请求之后取得的更新数据，
     * 提交列表时保留它们（整卡用同一份详情），不被较早取得的列表覆盖；新一轮开始时清空。
     */
    private val roundDetails = mutableMapOf<String, Withdrawal>()

    private val mutable = MutableStateFlow(RecordsSnapshot())
    val state: StateFlow<RecordsSnapshot> = mutable.asStateFlow()
    private val s: RecordsSnapshot get() = mutable.value

    val blocked: StateFlow<Boolean> get() = deps.gates.read.blocked

    val alive: Boolean get() = !left

    /**
     * 最近一次读取因身份中止（未知期建的页、本进程登录世代已变而新身份还没确认；FU-AND-0717 第 3 轮，鸿蒙 F-HM-39 同类）：停在可重试的错误态，身份确认
     * （App 下发 / 【重试】的一份快照）后接管页面身份并补取。
     */
    var identityAborted: Boolean = false
        private set

    /** 页面身份没建立（未知期取得）：这时不读取（不以 expectedAccount = null 发）。 */
    private fun identityPending(): Boolean = pageOwner.account == null

    private fun identityHeld() {
        identityAborted = true
        firstPagePending = false
        val error = WalletErrors.of(ApiResult.LocalReject(LocalRejectReason.SESSION_UNREADABLE))
        val message = Notice(error.textKey, ++serial, null, false)
        update {
            if (it.items.isEmpty()) {
                it.copy(
                    phase = RecordsSnapshot.ERROR,
                    error = message,
                )
            } else {
                it.copy(error = message, refreshing = false)
            }
        }
    }

    /**
     * 统一的页面恢复入口（FU-AND-0717 第 3 轮）：[identity] 是 App 这次检查的那份快照（或【重试】取的一份），不另读。[changed] 或已确认换代按它清空重取；
     * 未知期取得的身份第一次确认按它接管，读取曾中止的补取；同一身份而读取曾中止的补取；未知不动。
     */
    fun onIdentity(
        identity: SessionIdentity,
        changed: Boolean = false,
    ) {
        if (left) return
        when (if (changed) IdentityVerdict.CHANGED else pageOwner.verdict(identity)) {
            IdentityVerdict.CHANGED -> {
                onSessionChanged(identity)
            }

            IdentityVerdict.ADOPT -> {
                pageOwner = FormOwner.of(identity)
                if (identityAborted) load()
            }

            IdentityVerdict.SAME -> {
                if (identityAborted) load()
            }

            IdentityVerdict.UNKNOWN -> {
                Unit
            }
        }
    }

    /** 新一轮（进入、下拉、回到本页）：第一页 + 带 withdrawal_id 时的那张单。 */
    @Suppress("CyclomaticComplexMethod") // 列表、定位读取的每单序号与归属逐项
    fun load(refresh: Boolean = false) {
        if (left || !deps.env.loggedIn()) return
        if (identityPending()) {
            identityHeld()
            return
        }
        identityAborted = false
        if (deps.gates.read.active) {
            gated()
            return
        }
        val current = ++round
        val page = pageOwner
        val epoch = page.epoch
        cancelAll()
        roundDetails.clear()
        firstPagePending = true
        update {
            if (it.items.isEmpty()) {
                it.copy(
                    phase = RecordsSnapshot.LOADING,
                    error = null,
                )
            } else {
                it.copy(refreshing = refresh, loadingMore = false)
            }
        }
        // 本轮请求的发送前限流闸门（共性第 62 条）：被它拦下时记下原因，回调按原因结算
        val probe = deps.gates.read.probe()
        val owner = owner(current, epoch, probe)
        scope.launch {
            val list = deps.api.send(ApiCall(LIST_OPERATION, query = "limit=$PAGE_SIZE", ownership = owner, expectedAccount = page.account))
            // 每个请求回来先按会话归属结算限流、强更等共享副作用，再决定是否发下一个（共性第 42 条）
            settleShared(list, epoch)
            val listed = list is ApiResult.Success && WalletJson.withdrawalList(list.data) != null
            val focus =
                focusId?.takeIf { listed && !left && round == current && !deps.gates.read.active }?.let { id ->
                    // 定位读取与普通详情共用每单请求序号（APP-09 共性第 54 条，Android R3-03）：它也作废这张单更早的详情请求，自己也会被更新的取代
                    val request = ++detailCounter
                    detailSeq[id] = request
                    detailOwners.remove(id)?.let {
                        owners -= it
                        deps.api.cancelOwned(it)
                    }
                    val call = detailCall(id, owner, page.account)
                    FocusRead(id, request, deps.api.send(call).also { settleShared(it, epoch) })
                }
            owners -= owner
            if (left || round != current) return@launch
            // 仍是本轮而本进程登录世代已变（换代还没确认，FU-AND-0717 第 3 轮）：结束「刷新在途」（否则之后的刷新永远不受理），进可重试的错误态
            if (deps.env.sessionEpoch() != epoch) return@launch identityHeld()
            firstPagePending = false
            onFirstPage(list, focus, epoch, probe)
        }
    }

    /** 本地限流拦下也结算（共性第 30、45、46 条）：没有列表的进错误态；已有旧列表的独立显示 42901 与【重试】。 */
    private fun gated() {
        val message = notice("error.42901")
        update {
            if (it.items.isEmpty()) {
                it.copy(
                    phase = RecordsSnapshot.ERROR,
                    error = message,
                )
            } else {
                it.copy(error = message, refreshing = false)
            }
        }
    }

    /** 读取结果的共享副作用（共性第 3、42 条）：42901 写入共享禁用期、10405 进强更；只按会话归属，页面是否还在都做。 */
    private fun settleShared(
        result: ApiResult,
        epoch: Long,
    ) {
        if (deps.env.sessionEpoch() != epoch) return
        val error = (result as? ApiResult.Failure)?.let(WalletErrors::of) ?: return
        if (error.kind == WalletError.RETRY_AFTER) deps.gates.read.start(error.disableMillis)
        if (error.kind == WalletError.FORCE_UPDATE) deps.env.forceUpdate()
    }

    /** 一次定位读取：单号、它占用的每单请求序号与结果。 */
    private class FocusRead(
        val id: String,
        val request: Long,
        val result: ApiResult,
    )

    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod") // 列表、定位单、展开项逐项
    private fun onFirstPage(
        list: ApiResult,
        focus: FocusRead?,
        epoch: Long,
        probe: SendProbe,
    ) {
        val data = (list as? ApiResult.Success)?.let { WalletJson.withdrawalList(it.data) }
        if (data == null) {
            val error = WalletErrors.of(list)
            if (error.kind == WalletError.CANCELLED) {
                // 本轮列表被发送前限流拒绝（共性第 62 条）：按记下的原因结算，不看回调时刻的禁用期
                if (probe.gated(error)) failed(WalletError(WalletError.RETRY_AFTER, "error.42901"), settled = true)
                return
            }
            // 共享副作用已在结果回来时结算
            failed(error, settled = true)
            return
        }
        // 带 withdrawal_id：成功放在最前并展开；30701 或失败只显示列表，不提示
        val focused = (focus?.result as? ApiResult.Success)?.let { WalletJson.withdrawalDetail(it.data) }
        // 定位读取按每单请求序号接收（共性第 54 条）：之后又发了这张单的普通详情的，这次结果不再是最新
        val focusLatest = focus != null && detailSeq[focus.id] == focus.request
        // 定位单读取错误统一处理（共性第 3 条）：30701 静默（不是本人的按不存在处理），其余按读取错误提示
        if (focus != null && focused == null && focusLatest) readFailed(focus.result, probe, settled = true)
        if (focused != null && focusLatest) roundDetails[focused.withdrawalId] = focused
        // 统一接收顺序（共性第 34、54 条）：本轮已接受的详情比这份列表新，整卡用详情；定位单放最前时也用该单最终被接受的详情，不另用定位结果强制覆盖
        val front = focused?.let { roundDetails[it.withdrawalId] ?: it }
        val accepted = roundDetails.toMap()
        val listed = data.items.map { item -> accepted[item.withdrawalId] ?: item }
        val items = if (front != null) listOf(front) + listed.filter { it.withdrawalId != front.withdrawalId } else listed
        shownEpoch = epoch
        update {
            it.copy(
                phase = if (items.isEmpty()) RecordsSnapshot.EMPTY else RecordsSnapshot.CONTENT,
                items = items,
                nextCursor = data.nextCursor,
                expanded = front?.let { f -> it.expanded + f.withdrawalId } ?: it.expanded,
                // 刷新后旧详情作废（共性第 7 条）：只留本轮已接受的详情（含定位单），其余展开项按本轮重取
                details = accepted.filterKeys { id -> items.any { item -> item.withdrawalId == id } },
                error = null,
                refreshing = false,
                loadingMore = false,
                moreError = null,
                version = it.version + 1,
            )
        }
        // 展开项按本轮重取；这张单本轮已有更新的详情请求在途（定位读取被它取代）的不重复发
        s.expanded
            .filter { id -> id !in s.details && items.any { it.withdrawalId == id } && detailOwners[id]?.stillOwned?.invoke() != true }
            .forEach(::fetchDetail)
    }

    /** 读取错误统一处理（共性第 3 条）：42901 更新共享截止时间，10405 进强更，5xxxx 带 trace6 提示；30701、作废不提示。 */
    private fun readFailed(
        result: ApiResult,
        probe: SendProbe,
        settled: Boolean = false,
    ) {
        if ((result as? ApiResult.Failure)?.code == NOT_FOUND) return
        val error = WalletErrors.of(result)
        if (error.kind == WalletError.CANCELLED) {
            // 本次读取被发送前限流闸门拦下（共性第 56、62 条，按本请求记下的原因）：提示 42901；其余作废不提示
            if (probe.gated(error)) update { it.copy(toast = notice("error.42901")) }
            return
        }
        if (!settled && error.kind == WalletError.RETRY_AFTER) deps.gates.read.start(error.disableMillis)
        if (!settled && error.kind == WalletError.FORCE_UPDATE) deps.env.forceUpdate()
        update { it.copy(toast = Notice(error.textKey, ++serial, error.traceId, error.showTrace6)) }
    }

    private fun failed(
        error: WalletError,
        settled: Boolean = false,
    ) {
        if (!settled && error.kind == WalletError.RETRY_AFTER) deps.gates.read.start(error.disableMillis)
        if (!settled && error.kind == WalletError.FORCE_UPDATE) deps.env.forceUpdate()
        val message = Notice(error.textKey, ++serial, error.traceId, error.showTrace6)
        if (s.items.isNotEmpty()) {
            // 已有旧列表时刷新失败（共性第 29、30 条）：保留旧列表与它配套的游标（刷新期间没有发布新游标），独立显示错误与【重试】
            update { it.copy(refreshing = false, error = message, loadingMore = false) }
        } else {
            update { it.copy(phase = RecordsSnapshot.ERROR, error = message, refreshing = false, version = it.version + 1) }
        }
    }

    /** 翻页（滚到底）：按 next_cursor。 */
    fun loadMore() {
        val cursor = s.nextCursor ?: return
        if (left || firstPagePending || s.loadingMore || deps.gates.read.active) return
        val current = round
        val page = pageOwner
        val epoch = page.epoch
        update { it.copy(loadingMore = true, moreError = null) }
        val probe = deps.gates.read.probe()
        val owner = owner(current, epoch, probe)
        scope.launch {
            val result =
                deps.api.send(
                    ApiCall(
                        LIST_OPERATION,
                        query = "cursor=${percentEncode(cursor)}&limit=$PAGE_SIZE",
                        ownership = owner,
                        expectedAccount = page.account,
                    ),
                )
            owners -= owner
            settleShared(result, epoch)
            if (left || round != current) return@launch
            // 仍是本轮而本进程登录世代已变（换代还没确认）：结束翻页的加载中（FU-AND-0717 第 3 轮：不停在没有出口的加载）
            if (deps.env.sessionEpoch() != epoch) return@launch update { it.copy(loadingMore = false) }
            val data = (result as? ApiResult.Success)?.let { WalletJson.withdrawalList(it.data) }
            if (data == null) {
                val error = WalletErrors.of(result)
                val message =
                    when {
                        // 发送前被禁用期闸门拦下：按限流提示（共性第 45 条）；其余作废不提示
                        error.kind == WalletError.CANCELLED -> if (probe.gated(error)) notice("error.42901") else null

                        else -> Notice(error.textKey, ++serial, error.traceId, error.showTrace6)
                    }
                update { it.copy(loadingMore = false, moreError = message) }
                return@launch
            }
            val known = s.items.map { it.withdrawalId }.toSet()
            update {
                it.copy(
                    items = it.items + data.items.filter { item -> item.withdrawalId !in known },
                    nextCursor = data.nextCursor,
                    loadingMore = false,
                    version = it.version + 1,
                )
            }
        }
    }

    /** 刷新在途（第一页还没回来）：不再受理刷新（共性第 35 条）；页面据此禁用【重试】与下拉。 */
    val refreshPending: Boolean get() = firstPagePending

    /** 下拉、【重试】与回到本页（OnReturn）。共享限流中也按限流结算（APP-09 共性第 55 条，Android R3-04）：保留列表、显示 42901 与受限的【重试】。 */
    fun refresh(): Boolean {
        if (left || firstPagePending) return false
        if (identityAborted || identityPending()) {
            // 读取因身份中止：取一份快照走统一的恢复入口（第一次确认接管并补取；仍未知停在可重试的错误态）
            onIdentity(deps.env.identitySnapshot())
            if (identityPending()) identityHeld()
            return !identityAborted
        }
        if (deps.gates.read.active) {
            if (deps.env.loggedIn()) gated()
            return false
        }
        load(refresh = true)
        return true
    }

    /** 整卡展开 / 收起；展开时取详情（交易流水号只在详情）。 */
    fun toggle(
        version: Long,
        id: String,
    ) {
        if (!valid(version) || s.items.none { it.withdrawalId == id }) return
        if (id in s.expanded) {
            update { it.copy(expanded = it.expanded - id) }
            return
        }
        update { it.copy(expanded = it.expanded + id) }
        if (id !in s.details) fetchDetail(id)
    }

    @Suppress("CyclomaticComplexMethod") // 每张单的序号、归属与结果逐项核对
    private fun fetchDetail(id: String) {
        if (deps.gates.read.active) {
            // 本地限流拦下：提示 42901（卡片保持列表数据，不留加载中）
            update { it.copy(toast = notice("error.42901")) }
            return
        }
        val current = round
        val page = pageOwner
        val epoch = page.epoch
        // 同一张单的新请求作废旧请求（收起再展开、刷新重取）：只收最新序号
        val request = ++detailCounter
        detailSeq[id] = request
        detailOwners.remove(id)?.let {
            owners -= it
            deps.api.cancelOwned(it)
        }
        val probe = deps.gates.read.probe()
        val owner =
            CallOwnership(deps.api.epochs(), sendAllowed = probe::allowed) {
                !left && round == current && deps.env.sessionEpoch() == epoch && detailSeq[id] == request
            }.also {
                owners += it
                detailOwners[id] = it
            }
        scope.launch {
            val result = deps.api.send(detailCall(id, owner, page.account))
            owners -= owner
            if (detailOwners[id] === owner) detailOwners.remove(id)
            settleShared(result, epoch)
            if (left || round != current || deps.env.sessionEpoch() != epoch || detailSeq[id] != request) return@launch
            val detail = (result as? ApiResult.Success)?.let { WalletJson.withdrawalDetail(it.data) }
            if (detail == null) {
                readFailed(result, probe, settled = true)
                return@launch
            }
            if (detail.withdrawalId != id) return@launch
            roundDetails[id] = detail
            update {
                it.copy(
                    details = it.details + (id to detail),
                    items =
                        it.items.map { item ->
                            if (item.withdrawalId ==
                                id
                            ) {
                                detail
                            } else {
                                item
                            }
                        },
                    // 详情改了卡片内容（状态、流水号）：渲染版本前进，旧画面的回调不再生效
                    version = it.version + 1,
                )
            }
        }
    }

    /** 【复制】交易流水号：只走统一写入口（先记自写 hash 并确认落盘，记不下就不写、提示失败；G27）。 */
    fun copy(
        version: Long,
        id: String,
    ): Boolean {
        if (!valid(version)) return false
        val detail = s.details[id] ?: return false
        val text = TradeNoRow.copyText(detail.status, detail.channelOrderId) ?: return false
        val ok = deps.env.copy(text)
        update { it.copy(toast = Notice(if (ok) COPIED_KEY else COPY_FAILED_KEY, ++serial, assertive = !ok)) }
        return ok
    }

    /** FAILED + fail_action=change_payout_account 的【修改收款账号】（认识的通道才有）。 */
    fun changeAccount(
        version: Long,
        id: String,
    ) {
        if (!valid(version)) return
        val item = s.items.firstOrNull { it.withdrawalId == id } ?: return
        if (!WithdrawalText.of(item, null).showChangeAccount) return
        deps.env.open(WalletRoutes.PAYOUT_ACCOUNT, EMPTY)
    }

    /** 【联系客服】：统一客服出口接入前经 Router 打开 Help（同 APP-08 G09）。 */
    fun contact() = deps.env.contactService(WalletRoutes.WITHDRAW_RECORDS)

    fun consumeToast(id: Long) {
        if (s.toast?.id == id) update { it.copy(toast = null) }
    }

    private fun valid(version: Long): Boolean = !left && s.version == version && shownEpoch == deps.env.sessionEpoch()

    fun onSessionChanged() = onSessionChanged(deps.env.identitySnapshot())

    /** 会话换代，按调用方那一份快照 [identity] 重建页面身份（FU-AND-0717 第 3 轮：App 下发的那份，不另读一次）。 */
    fun onSessionChanged(identity: SessionIdentity) {
        if (left) return
        // 新页面状态（已清空）的固定身份：调用方那一份快照（共性第 65 条）
        pageOwner = FormOwner.of(identity)
        round += 1
        shownEpoch = Long.MIN_VALUE
        cancelAll()
        roundDetails.clear()
        update { RecordsSnapshot(version = it.version + 1) }
        load()
    }

    fun leave() {
        if (left) return
        left = true
        round += 1
        cancelAll()
    }

    private fun owner(
        current: Long,
        epoch: Long,
        probe: SendProbe,
    ): CallOwnership =
        CallOwnership(deps.api.epochs(), sendAllowed = probe::allowed) {
            !left && round == current && deps.env.sessionEpoch() == epoch
        }.also { owners += it }

    /** 详情读取：带本页固定身份的账号（共性第 65 条，网络层在读取凭据前核对）。 */
    private fun detailCall(
        id: String,
        owner: CallOwnership,
        account: String?,
    ) = ApiCall(DETAIL_OPERATION, pathParams = mapOf(WalletRoutes.WITHDRAWAL_ID to id), ownership = owner, expectedAccount = account)

    private fun cancelAll() {
        val cancelled = owners.toList()
        owners.clear()
        cancelled.forEach(deps.api::cancelOwned)
    }

    private fun notice(key: String) = Notice(key, ++serial)

    private inline fun update(change: (RecordsSnapshot) -> RecordsSnapshot) {
        mutable.value = change(mutable.value)
    }

    companion object {
        const val LIST_OPERATION = "listWithdrawals"
        const val DETAIL_OPERATION = "getWithdrawal"
        const val COPIED_KEY = "withdraw_detail.copied"
        const val COPY_FAILED_KEY = "withdraw_detail.copy_failed"
        const val PAGE_SIZE = 20
        private const val NOT_FOUND = 30_701
        val EMPTY = JsonObject(emptyMap())
    }
}
